package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.cache.CacheKeys;
import com.bishe.kb.cache.RedisCacheService;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.KbDocumentMapper;
import com.bishe.kb.mapper.KbSpaceMapper;
import com.bishe.kb.mapper.KbSpaceMemberMapper;
import com.bishe.kb.model.dto.SpacePageQuery;
import com.bishe.kb.model.dto.SpaceSaveRequest;
import com.bishe.kb.model.entity.KbDocument;
import com.bishe.kb.model.entity.KbSpace;
import com.bishe.kb.model.entity.KbSpaceMember;
import com.bishe.kb.model.vo.SpaceVO;
import com.bishe.kb.security.LoginUser;
import com.bishe.kb.security.UserContext;
import com.bishe.kb.service.KbSpaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 知识库空间服务实现。
 *
 * <p>计数策略：{@code doc_count} / {@code chunk_count} 不做并发自增，而是每次文档增删后调用
 * {@link #recount(Long)} 从 kb_document 全量重算，天然幂等、不会漂移。
 *
 * <p>删除保护：默认空间（{@value #DEFAULT_SPACE_ID}）永不删除；仍有文档的空间也拒绝删除，
 * 避免误删导致文档“失去归属”。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbSpaceServiceImpl extends ServiceImpl<KbSpaceMapper, KbSpace> implements KbSpaceService {

    private final KbDocumentMapper documentMapper;
    private final KbSpaceMemberMapper memberMapper;
    /** Redis 缓存可选注入：删除空间时淘汰受影响成员的可见集合缓存，关闭时静默跳过 */
    private final ObjectProvider<RedisCacheService> redisCacheProvider;

    @Override
    public Page<SpaceVO> pageSpaces(SpacePageQuery query) {
        LambdaQueryWrapper<KbSpace> wrapper = new LambdaQueryWrapper<KbSpace>()
                .like(StringUtils.isNotBlank(query.keyword()), KbSpace::getName, query.keyword())
                .orderByAsc(KbSpace::getId);

        Page<KbSpace> page = page(new Page<>(query.pageNum(), query.pageSize()), wrapper);
        List<SpaceVO> records = page.getRecords().stream().map(this::toVo).toList();

        Page<SpaceVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(records);
        return result;
    }

    @Override
    public List<SpaceVO> listSpaces() {
        return list(new LambdaQueryWrapper<KbSpace>().orderByAsc(KbSpace::getId))
                .stream().map(this::toVo).toList();
    }

    @Override
    public Long createSpace(SpaceSaveRequest request) {
        KbSpace space = new KbSpace();
        space.setName(StringUtils.trim(request.name()));
        space.setDescription(request.description());
        // 记录创建人（RBAC 下只有管理员能建空间）；未登录上下文时留空，交由 DB 默认值/回填处理
        LoginUser current = UserContext.get();
        if (current != null) {
            space.setCreatedBy(current.id());
        }
        space.setDocCount(0);
        space.setChunkCount(0);
        save(space);
        log.info("新建知识库空间 id={} name={} createdBy={}", space.getId(), space.getName(), space.getCreatedBy());
        return space.getId();
    }

    @Override
    public void updateSpace(Long id, SpaceSaveRequest request) {
        KbSpace space = getById(id);
        if (space == null) {
            throw new BizException(4040, "知识库空间不存在: " + id);
        }
        space.setName(StringUtils.trim(request.name()));
        space.setDescription(request.description());
        updateById(space);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSpace(Long id) {
        if (id != null && id == DEFAULT_SPACE_ID) {
            throw new BizException("默认知识库不可删除");
        }
        KbSpace space = getById(id);
        if (space == null) {
            throw new BizException(4040, "知识库空间不存在: " + id);
        }
        long docCount = documentMapper.selectCount(
                new LambdaQueryWrapper<KbDocument>().eq(KbDocument::getSpaceId, id));
        if (docCount > 0) {
            throw new BizException("该知识库下仍有 " + docCount + " 篇文档，请先删除或迁移后再删空间");
        }
        // 空间和成员行必须同进同退：只删空间会留下孤儿授权，
        // 之后自增 ID 复用时可能把新空间意外授权给旧用户。
        // 先记下受影响的成员，删完后淘汰他们的可见集合缓存，避免缓存里仍留着已删除的空间。
        List<Long> affectedUserIds = memberMapper.selectList(new LambdaQueryWrapper<KbSpaceMember>()
                        .select(KbSpaceMember::getUserId)
                        .eq(KbSpaceMember::getSpaceId, id))
                .stream().map(KbSpaceMember::getUserId).filter(Objects::nonNull).distinct().toList();
        int members = memberMapper.delete(new LambdaQueryWrapper<KbSpaceMember>()
                .eq(KbSpaceMember::getSpaceId, id));
        removeById(id);
        RedisCacheService redis = redisCacheProvider.getIfAvailable();
        if (redis != null) {
            affectedUserIds.forEach(userId -> redis.deleteAfterCommit(CacheKeys.spaces(userId)));
        }
        log.info("删除知识库空间 id={} name={} 清理成员授权={} 条", id, space.getName(), members);
    }

    @Override
    public void recount(Long spaceId) {
        if (spaceId == null) {
            return;
        }
        // 只取 chunk_count 一列做求和，逻辑删除由 @TableLogic 自动过滤
        List<KbDocument> docs = documentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                .select(KbDocument::getChunkCount)
                .eq(KbDocument::getSpaceId, spaceId));
        int docCount = docs.size();
        int chunkCount = docs.stream()
                .mapToInt(d -> d.getChunkCount() == null ? 0 : d.getChunkCount())
                .sum();

        KbSpace update = new KbSpace();
        update.setId(spaceId);
        update.setDocCount(docCount);
        update.setChunkCount(chunkCount);
        updateById(update);
    }

    private SpaceVO toVo(KbSpace space) {
        return new SpaceVO(space.getId(), space.getName(), space.getDescription(),
                space.getDocCount() == null ? 0 : space.getDocCount(),
                space.getChunkCount() == null ? 0 : space.getChunkCount(),
                space.getCreateTime(), space.getUpdateTime());
    }
}
