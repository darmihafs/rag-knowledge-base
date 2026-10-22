package com.bishe.kb.service;

import java.util.List;

/**
 * 一次召回允许的语料范围。
 *
 * <p>为什么不是一个简单的 {@code Long spaceId}：V8 之前空间只是管理员手选的过滤条件，
 * 而真正做多租户隔离时需要同时表达三种情况——
 * <ol>
 *   <li>{@link #unrestricted()}：管理员全站检索，不加任何空间条件；</li>
 *   <li>{@link #ofSpace}：明确收敛到一个空间（调用方必须已经校验过它可见）；</li>
 *   <li>{@link #visible}：普通用户没选空间，但只能在他有权限的那几个空间里检索。</li>
 * </ol>
 *
 * <p>{@code visibleSpaceIds} 为<b>空列表</b>与为 {@code null} 含义完全不同：空列表表示
 * 「这个账号一个空间都没授权」，召回必须是<b>空结果</b>；只有在 {@code spaceId} 与
 * {@code visibleSpaceIds} 同时为 null 时才是不限制。把这两者混为一谈就是越权的来源。
 *
 * @param spaceId         指定收敛的单个空间，null 表示不按单空间过滤
 * @param visibleSpaceIds 可见空间集合；null 表示不限制，空集合表示什么都看不到
 */
public record RetrievalScope(Long spaceId, List<Long> visibleSpaceIds) {

    public static RetrievalScope unrestricted() {
        return new RetrievalScope(null, null);
    }

    public static RetrievalScope ofSpace(Long spaceId) {
        return new RetrievalScope(spaceId, null);
    }

    public static RetrievalScope visible(List<Long> visibleSpaceIds) {
        return new RetrievalScope(null, visibleSpaceIds);
    }

    /** 是否需要施加空间条件：语义路要据此决定候选放大倍数 */
    public boolean restricted() {
        return spaceId != null || visibleSpaceIds != null;
    }

    /** 授权集合为空：召回必须直接返回空，绝不能退化成全库检索 */
    public boolean deniesEverything() {
        return spaceId == null && visibleSpaceIds != null && visibleSpaceIds.isEmpty();
    }
}
