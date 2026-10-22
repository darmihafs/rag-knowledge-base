#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地服务并发压测：只读接口在 1/4/8/16 并发下的 QPS 与 P50/P95/P99。

背景：前面的 A/B 压测（lexical_ab_probe.py）量化的是「一条检索 SQL 在 10 万切片下要多久」，
那是单请求的容量下限；答辩常被追问的另一半是「多个人同时点会怎样」——Tomcat 线程池、
Druid 连接池、鉴权拦截器、JSON 序列化在并发下会不会先撑不住。这个脚本量的就是这一层。

三个被压的接口刻意都满足两条：只读（跑完不留脏数据）和不碰大模型（不产生 API 费用）。

  /api/health           不查库，量框架 + 序列化 + Tomcat 线程的天花板
  /api/chunks/page      语料中心分页检索：鉴权 + MyBatis + 分页 COUNT + 全文关键词 + JSON
  /api/dashboard/stats  大盘首屏统计，多条聚合 SQL，最接近真实页面开销

向量召回与答案生成没有纳入本轮：每次语义请求都会真实调用 embedding 接口（按 token 计费），
把几百次同样的钱烧在同一个问题上没有信息量；语义路的成本口径看 lexical_ab_probe.py 的
SQL 数据，首查与复用延迟看问答链路的实测记录。

实现要点：

  * 每个压测线程持有自己的 keep-alive 连接，模拟浏览器复用连接，量服务端处理时间，
    不把 TCP 握手噪声算进去；
  * 登录只做一次并复用 JWT：登录含 BCrypt（刻意做的慢哈希），每次都登录会把哈希成本
    算进被压接口，测出来的就不是接口本身；
  * 每个并发档位先预热再正式采样，避开 JIT 与连接池冷启动；
  * 成功判定是双重的：HTTP 200 且业务 `code == 0`。只认 HTTP 会把业务失败算成成功；
  * 默认只允许打本机，压远端必须显式加 --allow-remote，防止误伤线上。

用法（账号密码用 README 里的种子账号，可用环境变量覆盖，不写死在脚本里）：
  set KB_API_USER=admin&& set KB_API_PASSWORD=admin123
  python docs/bench/api_concurrency_probe.py --duration 6
"""

import argparse
import csv
import http.client
import json
import os
import statistics
import sys
import threading
import time
import urllib.parse
from pathlib import Path

OUT_CSV = Path(__file__).resolve().parent / "concurrency_results.csv"

LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "0.0.0.0"}

ENDPOINTS = [
    {"key": "health", "name": "健康检查", "path": "/api/health"},
    {"key": "corpus", "name": "语料检索分页",
     "path": "/api/chunks/page?pageNum=1&pageSize=10&keyword=" + urllib.parse.quote("滑动窗口")},
    {"key": "stats", "name": "大盘统计", "path": "/api/dashboard/stats"},
]

FIELDS = ["endpoint", "endpoint_name", "concurrency", "requests", "success", "errors",
          "error_rate_pct", "qps", "avg_ms", "p50_ms", "p95_ms", "p99_ms", "max_ms",
          "wall_s", "error_kinds"]


class Target:
    """被压服务地址。校验放在构造时，避免任何一次请求在没确认目标前就发出去。"""

    def __init__(self, base_url, allow_remote=False):
        parsed = urllib.parse.urlsplit(base_url)
        if parsed.scheme != "http":
            raise SystemExit("只支持 http:// 形式的本地地址，避免误打线上或被证书问题干扰")
        self.host = parsed.hostname
        self.port = parsed.port or 80
        if not allow_remote and self.host not in LOCAL_HOSTS:
            raise SystemExit(f"{self.host} 不是本机地址；确认要压请加 --allow-remote")

    def connect(self):
        return http.client.HTTPConnection(self.host, self.port, timeout=15)


def http_request(target, path, token, method="GET", payload=None, conn=None):
    """发一个请求，返回 (状态码, 响应体文本)。传 conn 就复用连接。"""
    own = conn is None
    if own:
        conn = target.connect()
    try:
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        body = None
        if payload is not None:
            body = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        conn.request(method, path, body=body, headers=headers)
        resp = conn.getresponse()
        return resp.status, resp.read().decode("utf-8", "replace")
    finally:
        if own:
            conn.close()


def login(target, username, password):
    status, text = http_request(target, "/api/auth/login", None, method="POST",
                                payload={"username": username, "password": password})
    if status != 200:
        raise SystemExit(f"登录失败 HTTP={status} {(text or '')[:200]}")
    data = json.loads(text)
    if data.get("code") != 0:
        raise SystemExit(f"登录被拒：{data.get('message')}")
    return data["data"]["token"]


def percentile(sorted_vals, p):
    """最近秩分位数，入参已排序。"""
    if not sorted_vals:
        return float("nan")
    return sorted_vals[min(int(p * (len(sorted_vals) - 1)), len(sorted_vals) - 1)]


class Worker(threading.Thread):
    """一个压测线程：抱着自己的连接反复请求，直到超时或被请求数上限叫停。"""

    def __init__(self, target, path, token, deadline, max_requests):
        super().__init__(daemon=True)
        self.target = target
        self.path = path
        self.token = token
        self.deadline = deadline
        self.max_requests = max_requests
        self.samples = []
        self.errors = {}

    def run(self):
        conn = self.target.connect()
        done = 0
        try:
            while done < self.max_requests and time.monotonic() < self.deadline:
                start = time.perf_counter()
                try:
                    status, text = http_request(self.target, self.path, self.token, conn=conn)
                    cost_ms = (time.perf_counter() - start) * 1000.0
                    if status != 200:
                        self._fail(f"HTTP {status}")
                        conn = self._reopen(conn)
                        continue
                    if json.loads(text).get("code") != 0:
                        self._fail("业务码非 0")
                        continue
                    self.samples.append(cost_ms)
                except (ConnectionError, OSError, http.client.HTTPException, ValueError) as exc:
                    # ValueError 来自 json 解析：返回体不是 JSON（比如被网关截断）同样算失败
                    self._fail(type(exc).__name__)
                    conn = self._reopen(conn)
                done += 1
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def _fail(self, kind):
        self.errors[kind] = self.errors.get(kind, 0) + 1

    def _reopen(self, conn):
        """连接可能已被服务端回收，换一条新的继续，否则整个线程就哑在这里。"""
        try:
            conn.close()
        except Exception:
            pass
        return self.target.connect()


def run_level(target, path, token, concurrency, duration, max_per_thread):
    deadline = time.monotonic() + duration
    workers = [Worker(target, path, token, deadline, max_per_thread) for _ in range(concurrency)]
    started = time.perf_counter()
    for worker in workers:
        worker.start()
    for worker in workers:
        worker.join()
    elapsed = time.perf_counter() - started

    samples = sorted(v for w in workers for v in w.samples)
    errors = {}
    for w in workers:
        for kind, count in w.errors.items():
            errors[kind] = errors.get(kind, 0) + count
    error_total = sum(errors.values())
    total = len(samples) + error_total
    return {
        "requests": total,
        "success": len(samples),
        "errors": error_total,
        "error_rate_pct": round(0.0 if total == 0 else error_total / total * 100.0, 2),
        "qps": round(len(samples) / elapsed, 1) if elapsed > 0 else 0.0,
        "avg_ms": round(statistics.mean(samples), 1) if samples else float("nan"),
        "p50_ms": round(percentile(samples, 0.50), 1),
        "p95_ms": round(percentile(samples, 0.95), 1),
        "p99_ms": round(percentile(samples, 0.99), 1),
        "max_ms": round(max(samples), 1) if samples else float("nan"),
        "wall_s": round(elapsed, 2),
        "error_kinds": ", ".join(f"{k}×{v}" for k, v in sorted(errors.items(), key=lambda kv: -kv[1])) or "-",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default=os.environ.get("KB_API_USER", "admin"))
    parser.add_argument("--password", default=os.environ.get("KB_API_PASSWORD", ""))
    parser.add_argument("--levels", default="1,4,8,16", help="逗号分隔的并发档位")
    parser.add_argument("--duration", type=float, default=6.0, help="每档位正式采样秒数")
    parser.add_argument("--warmup", type=float, default=1.0, help="每档位预热秒数（不计入结果）")
    parser.add_argument("--max-per-thread", type=int, default=4000, help="单线程请求上限，兜底防跑飞")
    parser.add_argument("--allow-remote", action="store_true", help="允许压非本机地址")
    args = parser.parse_args()

    if not args.password:
        raise SystemExit("缺少密码：设 KB_API_PASSWORD 环境变量或传 --password（不写死在脚本里）")

    target = Target(args.base_url, args.allow_remote)
    token = login(target, args.username, args.password)
    levels = [int(v) for v in args.levels.split(",") if v.strip()]
    print(f"目标 {args.base_url}｜并发档位 {levels}｜每档预热 {args.warmup}s + 采样 {args.duration}s"
          f"｜单线程上限 {args.max_per_thread} 次\n")

    rows = []
    for ep in ENDPOINTS:
        print(f"### {ep['name']}  {ep['path']}")
        for level in levels:
            if args.warmup > 0:
                run_level(target, ep["path"], token, level, args.warmup, args.max_per_thread)
            metrics = run_level(target, ep["path"], token, level, args.duration, args.max_per_thread)
            record = {"endpoint": ep["key"], "endpoint_name": ep["name"], "concurrency": level}
            record.update(metrics)
            rows.append(record)
            print(f"  并发 {level:>2}  QPS {metrics['qps']:>8.1f}  "
                  f"P50 {metrics['p50_ms']:>7.1f}ms  P95 {metrics['p95_ms']:>7.1f}ms  "
                  f"P99 {metrics['p99_ms']:>7.1f}ms  均值 {metrics['avg_ms']:>7.1f}ms  "
                  f"成功 {metrics['success']}/{metrics['requests']}  "
                  f"错误率 {metrics['error_rate_pct']}%  [{metrics['error_kinds']}]")
        print()

    with OUT_CSV.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"汇总结果：{OUT_CSV}\n")

    print("=== 结论 ===")
    print(f"{'接口':<14}{'并发':>6}{'QPS':>10}{'P50ms':>10}{'P95ms':>10}{'P99ms':>10}{'错误率%':>10}")
    for r in rows:
        print(f"{r['endpoint_name']:<14}{r['concurrency']:>6}{r['qps']:>10.1f}{r['p50_ms']:>10.1f}"
              f"{r['p95_ms']:>10.1f}{r['p99_ms']:>10.1f}{r['error_rate_pct']:>10.2f}")


if __name__ == "__main__":
    sys.exit(main())
