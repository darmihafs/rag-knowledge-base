package com.bishe.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bishe.kb.model.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档分段 Mapper。
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 全文索引词法召回：一条 SQL 走完「多词 OR 匹配 + 相关度排序 + 截断」。
     *
     * <p>依赖 {@code kb_chunk} 上 {@code WITH PARSER ngram} 的 FULLTEXT 索引（见
     * {@code db/schema.sql} 与 V8），MySQL 侧实现；H2 测试库不支持该语法，
     * 由 {@code rag.lexical-mode=like} 走 {@code LIKE} 兜底分支。
     *
     * @param expression BOOLEAN MODE 表达式，词与词之间空格分隔即 OR 语义
     * @param limit      最多返回多少条候选
     * @return 按相关度降序的分段，{@code relevance} 为 MySQL 算出的相关度分值
     */
    @Select("""
            select id, document_id, chunk_index, page_number, content, char_count, enabled, vector_ref, create_time,
                   match(content) against(#{expression} in boolean mode) as relevance
            from kb_chunk
            where enabled = 1
              and match(content) against(#{expression} in boolean mode)
            order by relevance desc, document_id asc, chunk_index asc
            limit #{limit}
            """)
    List<KbChunk> searchByFulltext(@Param("expression") String expression, @Param("limit") int limit);

    /**
     * 全文索引词法召回的空间收敛版本：命中范围限制在 {@code spaceId} 所属空间的文档切片内。
     *
     * <p>用子查询而不是「先召回再内存过滤」，是为了让 topK 截断发生在空间之内——
     * 内存过滤会让别的空间的切片挤占名额，指定空间时召回条数莫名变少。
     *
     * @param expression BOOLEAN MODE 表达式
     * @param limit      最多返回多少条候选
     * @param spaceId    知识库空间 ID，必须为正数；不做空间限制时走 {@link #searchByFulltext}
     * @return 按相关度降序的分段
     */
    @Select("""
            select id, document_id, chunk_index, page_number, content, char_count, enabled, vector_ref, create_time,
                   match(content) against(#{expression} in boolean mode) as relevance
            from kb_chunk
            where enabled = 1
              and document_id in (select id from kb_document where space_id = #{spaceId} and deleted = 0)
              and match(content) against(#{expression} in boolean mode)
            order by relevance desc, document_id asc, chunk_index asc
            limit #{limit}
            """)
    List<KbChunk> searchByFulltextInSpace(@Param("expression") String expression,
                                         @Param("limit") int limit,
                                         @Param("spaceId") Long spaceId);

    /**
     * 全文索引词法召回的<b>多空间</b>版本：范围是「当前账号有权限的那几个空间」的并集。
     *
     * <p>{@link #searchByFulltextInSpace} 只能表达「收敛到一个空间」，而多租户下普通用户
     * 往往同时有几个可读空间、并且在问答页不主动选空间——那时范围是权限集合本身，
     * 不能退化成不限制（那是越权），也不能只挑其中一个（那是凭空少召回）。
     *
     * <p>{@code spaceIds} 为空集合时这条 SQL 的 {@code in ()} 会直接语法报错，
     * 调用方（{@code RetrievalServiceImpl}）必须先用
     * {@code RetrievalScope.deniesEverything()} 挡掉空授权，不该把空集合送到这里。
     *
     * @param expression BOOLEAN MODE 表达式
     * @param limit      最多返回多少条候选
     * @param spaceIds   可读空间 ID 列表，非空
     * @return 按相关度降序的分段
     */
    @Select("""
            <script>
            select id, document_id, chunk_index, page_number, content, char_count, enabled, vector_ref, create_time,
                   match(content) against(#{expression} in boolean mode) as relevance
            from kb_chunk
            where enabled = 1
              and document_id in (select id from kb_document where deleted = 0 and space_id in
                <foreach item="spaceId" collection="spaceIds" open="(" separator="," close=")">#{spaceId}</foreach>)
              and match(content) against(#{expression} in boolean mode)
            order by relevance desc, document_id asc, chunk_index asc
            limit #{limit}
            </script>
            """)
    List<KbChunk> searchByFulltextInSpaces(@Param("expression") String expression,
                                           @Param("limit") int limit,
                                           @Param("spaceIds") List<Long> spaceIds);
}
