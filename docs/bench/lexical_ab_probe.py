#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""词法召回 A/B 压测：逐词 LIKE（含分页 COUNT）vs 单条 MATCH AGAINST。

背景：一期词法召回对每个检索词跑一次 `LIKE '%词%'`，MySQL 用不上任何索引，10 万条切片
就是 10 万次全表扫描；MyBatis-Plus 的分页还会在数据查询前额外发一条 COUNT。改成
`MATCH(content) AGAINST(... IN BOOLEAN MODE)` 后，一条 SQL 就完成多词 OR 匹配、相关度打分
和排序截断，扫描量交给 ngram 全文索引。本脚本把这个差值量化出来。

测的是与后端一致的 SQL 形态（见 RetrievalServiceImpl.likeRetrieve / searchByFulltext）：

  LIKE   ：每个词两条语句——`select count(*) ... like '%词%'` + `select ... limit 2*topK`
  FULLTEXT：一条 `match(content) against(词表 in boolean mode) ... order by relevance desc limit topK`

计时在 MySQL 服务端会话内用 `now(6)` 前后差得出，包含每条语句的客户端往返，因此更接近
JDBC 真实感受；每条被探测语句都用 `select count(*) into @c` 吞掉结果集，避免打印干扰。

前置：先用 lexical_load_setup.sql 灌出临时库 kb_rag_bench（只读 kb_rag 的结构，不写业务数据）。

用法：
  python docs/bench/lexical_ab_probe.py --mysql "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe"
"""

import argparse
import csv
import os
import json
import re
import statistics
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
QUERIES = REPO / "backend" / "src" / "test" / "resources" / "benchmark" / "queries.json"
OUT_CSV = Path(__file__).resolve().parent / "lexical_ab_results.csv"

MAX_TERMS = 12   # RetrievalServiceImpl.MAX_TERMS
TOP_K = 5        # rag.top-k 默认值
PAGE_SIZE = TOP_K * 2
CHUNK_COLS = ("id, document_id, chunk_index, page_number, content, char_count, "
              "enabled, vector_ref, create_time")

CJK_PUNCT = "，。？！、；：‘’“”（）【】《》"
SPLIT_RE = re.compile(r"[!-/:-@[-`{-~\s" + CJK_PUNCT + r"]+")
NUMBER_RE = re.compile(r"^[\d.]+$")


def contains_cjk(word: str) -> bool:
    return any(0x4E00 <= ord(c) <= 0x9FFF for c in word)


def build_terms(question: str) -> list[str]:
    """Java RetrievalServiceImpl.buildTerms 的等价实现：洗标点、英文按空格、中文补 2-gram。"""
    normalized = SPLIT_RE.sub(" ", question.lower()).strip()
    terms: list[str] = []
    for word in (raw.strip() for raw in normalized.split(" ")):
        if not word:
            continue
        if word not in terms:
            terms.append(word)
        if len(word) > 4 and contains_cjk(word):
            for i in range(len(word) - 1):
                gram = word[i:i + 2]
                if gram not in terms:
                    terms.append(gram)
    return [t for t in terms if len(t) >= 2 or not contains_cjk(t)][:MAX_TERMS]


def esc(text: str) -> str:
    return text.replace("\\", "\\\\").replace("'", "''")


def boolean_expression(terms: list[str]) -> str:
    """Java booleanExpression：去掉 BOOLEAN MODE 操作符后空格相连，即 OR 语义。"""
    return " ".join(t for t in (re.sub(r"[+\-><()~*\"@]", "", term) for term in terms) if t)


def sql_session(table: str, mode: str, terms: list[str]) -> str:
    """拼一个自计时会话脚本，最后一行输出 `elapsed_ms<TAB>queries<TAB>hits`。"""
    lines = ["set @t0 = now(6)", "set @q = 0", "set @h = 0"]
    if mode == "LIKE":
        for term in terms:
            like = f"'%{esc(term)}%'"
            # 与 selectPage 一致：先 COUNT，再取 2*topK 条数据
            lines.append(f"select count(*) into @c from {table} "
                         f"where enabled = 1 and content like {like}")
            lines.append(f"select count(*) into @c from (select {CHUNK_COLS} from {table} "
                         f"where enabled = 1 and content like {like} order by id limit {PAGE_SIZE}) s")
            lines.append("set @q = @q + 2")
            lines.append("set @h = @h + @c")
    else:
        expr = esc(boolean_expression(terms))
        lines.append(
            f"select count(*) into @c from (select {CHUNK_COLS}, "
            f"match(content) against('{expr}' in boolean mode) as relevance from {table} "
            f"where enabled = 1 and match(content) against('{expr}' in boolean mode) "
            f"order by relevance desc, document_id asc, chunk_index asc limit {TOP_K}) s")
        lines.append("set @q = @q + 1")
        lines.append("set @h = @h + @c")
    lines.append("select round(timestampdiff(microsecond, @t0, now(6)) / 1000.0, 3) "
                 "as elapsed_ms, @q as queries, @h as hits")
    return ";\n".join(lines) + ";\n"


class Probe:
    """一次问题 = 一个 mysql 会话；密码走 MYSQL_PWD 环境变量，不进命令行、不进仓库。"""

    def __init__(self, mysql: str, database: str):
        self.mysql = mysql
        self.database = database
        if not os.environ.get("MYSQL_PWD"):
            raise SystemExit("请先设置环境变量 MYSQL_PWD（mysql 客户端会自行读取，避免密码出现在命令行）")
        self.base = [mysql, "-uroot", "--default-character-set=utf8mb4",
                     "--batch", "--skip-column-names", "--raw", database]

    def run(self, script: str) -> tuple[float, int, int]:
        proc = subprocess.run(self.base, input=script.encode("utf-8"), capture_output=True)
        if proc.returncode != 0:
            raise SystemExit("mysql 执行失败：" + proc.stderr.decode("utf-8", "replace")[-500:])
        out = proc.stdout.decode("utf-8", "replace").strip().splitlines()
        if not out:
            raise SystemExit("mysql 没有输出计时结果")
        elapsed, queries, hits = out[-1].split("\t")
        if not NUMBER_RE.match(elapsed):
            raise SystemExit(f"计时结果解析失败：{out[-1]!r}")
        return float(elapsed), int(queries), int(hits)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mysql", required=True, help="mysql.exe / mysql 可执行文件路径")
    parser.add_argument("--database", default="kb_rag_bench")
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()

    queries = json.loads(QUERIES.read_text(encoding="utf-8"))["queries"]
    table = f"`{args.database}`.`kb_chunk`"
    probe = Probe(args.mysql, args.database)

    print(f"临时库 {args.database}，{len(queries)} 道真实问题，每模式跑 {args.rounds} 轮"
          f"（第 1 轮为预热，不计入结果）\n")

    rows: list[dict] = []
    for rnd in range(1, args.rounds + 1):
        for q in queries:
            terms = build_terms(q["question"])
            if not terms:
                continue
            for mode in ("LIKE", "FULLTEXT"):
                elapsed, n_queries, hits = probe.run(sql_session(table, mode, terms))
                record = {"round": rnd, "qid": q["id"], "category": q["category"], "mode": mode,
                          "question": q["question"], "terms": len(terms), "queries": n_queries,
                          "elapsed_ms": elapsed, "hits": hits}
                if rnd > 1:
                    rows.append(record)
                print(f"round={rnd} {q['id']:>3} {mode:<8} terms={len(terms):>2} "
                      f"sql={n_queries:>3} {elapsed:9.2f} ms  hits={hits}")

    measured = rows
    write_csv(measured)
    summarize(measured, args.rounds - 1, table)


def write_csv(rows: list[dict]) -> None:
    with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"\n逐题明细：{OUT_CSV}")


def summarize(rows: list[dict], samples: int, table: str) -> None:
    print(f"\n=== 结论（{table}，每模式 {samples} 个样本）===\n")
    stats = {}
    for mode in ("LIKE", "FULLTEXT"):
        vals = sorted(r["elapsed_ms"] for r in rows if r["mode"] == mode)
        stats[mode] = vals
        print(f"{mode:<9} 平均检索词 {statistics.mean(r['terms'] for r in rows if r['mode'] == mode):4.1f}"
              f"  平均 SQL 条数 {statistics.mean(r['queries'] for r in rows if r['mode'] == mode):5.1f}"
              f"  P50 {pct(vals, 0.50):8.2f} ms  P95 {pct(vals, 0.95):8.2f} ms"
              f"  均值 {statistics.mean(vals):8.2f} ms")
    like, full = stats["LIKE"], stats["FULLTEXT"]
    print(f"\nP50 {statistics.median(like) / max(statistics.median(full), 0.001):6.1f}× 提速   "
          f"P95 {pct(like, 0.95) / max(pct(full, 0.95), 0.001):6.1f}× 提速   "
          f"均值 {statistics.mean(like) / max(statistics.mean(full), 0.001):6.1f}× 提速")


def pct(vals: list[float], p: float) -> float:
    return vals[min(int(p * (len(vals) - 1)), len(vals) - 1)]


if __name__ == "__main__":
    main()
