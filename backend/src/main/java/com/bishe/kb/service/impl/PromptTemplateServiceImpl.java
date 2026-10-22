package com.bishe.kb.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.bishe.kb.common.BizException;
import com.bishe.kb.mapper.PromptTemplateMapper;
import com.bishe.kb.model.dto.TemplateSaveRequest;
import com.bishe.kb.model.entity.PromptTemplate;
import com.bishe.kb.model.vo.TemplateVO;
import com.bishe.kb.service.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 提示词模板实现：CRUD + 分类校验 + 排序。
 *
 * <p>分类必须落在 {@link PromptTemplate#CATEGORIES} 内：前端按分类分页签展示，
 * 一个近义词会让该模板从所有页签里消失，比直接报错更难排查，所以宁可拒掉。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateServiceImpl extends ServiceImpl<PromptTemplateMapper, PromptTemplate>
        implements PromptTemplateService {

    /** 未指定排序权重时的默认值：靠后排列，管理员可再调整 */
    private static final int DEFAULT_SORT_ORDER = 100;

    @Override
    public List<TemplateVO> listActive() {
        return listOrdered(new LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getEnabled, PromptTemplate.ENABLED));
    }

    @Override
    public List<TemplateVO> listAll() {
        return listOrdered(new LambdaQueryWrapper<>());
    }

    @Override
    public TemplateVO create(TemplateSaveRequest request) {
        PromptTemplate template = new PromptTemplate();
        apply(template, request, null);
        try {
            save(template);
        } catch (DuplicateKeyException e) {
            throw new BizException(4001, "模板名称已存在: " + template.getTitle());
        }
        log.info("新建提示词模板 id={} title={} category={} enabled={}",
                template.getId(), template.getTitle(), template.getCategory(), template.getEnabled());
        return toVo(template);
    }

    @Override
    public TemplateVO update(Long id, TemplateSaveRequest request) {
        PromptTemplate template = id == null ? null : getById(id);
        if (template == null) {
            throw new BizException(4040, "模板不存在: " + id);
        }
        apply(template, request, id);
        updateById(template);
        log.info("修改提示词模板 id={} title={} enabled={}", id, template.getTitle(), template.getEnabled());
        return toVo(template);
    }

    @Override
    public void delete(Long id) {
        PromptTemplate template = id == null ? null : getById(id);
        if (template == null) {
            throw new BizException(4040, "模板不存在: " + id);
        }
        removeById(id);
        log.info("删除提示词模板 id={} title={}", id, template.getTitle());
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 把入参写进实体并做统一校验。
     *
     * @param selfId 更新时排除自身，避免「名称没改也提示重名」
     */
    private void apply(PromptTemplate template, TemplateSaveRequest request, Long selfId) {
        String title = StringUtils.trimToEmpty(request.title());
        if (existsTitle(title, selfId)) {
            throw new BizException(4001, "模板名称已存在: " + title);
        }
        template.setTitle(title);
        template.setCategory(normalizeCategory(request.category()));
        template.setPromptContent(request.promptContent().trim());
        template.setDescription(StringUtils.trimToNull(request.description()));
        template.setEnabled(request.enabled() == null ? PromptTemplate.ENABLED : request.enabled());
        template.setSortOrder(request.sortOrder() == null ? DEFAULT_SORT_ORDER : request.sortOrder());
    }

    private boolean existsTitle(String title, Long selfId) {
        return count(new LambdaQueryWrapper<PromptTemplate>()
                .eq(PromptTemplate::getTitle, title)
                .ne(selfId != null, PromptTemplate::getId, selfId)) > 0;
    }

    private String normalizeCategory(String category) {
        String trimmed = StringUtils.trimToNull(category);
        if (trimmed == null) {
            return PromptTemplate.DEFAULT_CATEGORY;
        }
        if (!PromptTemplate.CATEGORIES.contains(trimmed)) {
            throw new BizException(4004, "分类只能取：" + String.join(" / ", PromptTemplate.CATEGORIES));
        }
        return trimmed;
    }

    /** 排序权重小的在前，权重相同按 ID 升序，保证种子数据的展示顺序稳定 */
    private List<TemplateVO> listOrdered(LambdaQueryWrapper<PromptTemplate> wrapper) {
        return list(wrapper.orderByAsc(PromptTemplate::getSortOrder).orderByAsc(PromptTemplate::getId))
                .stream().map(this::toVo).toList();
    }

    private TemplateVO toVo(PromptTemplate t) {
        return new TemplateVO(t.getId(), t.getTitle(), t.getCategory(), t.getPromptContent(),
                t.getDescription(),
                t.getEnabled() == null ? PromptTemplate.ENABLED : t.getEnabled(),
                t.getSortOrder(), t.getCreateTime(), t.getUpdateTime());
    }
}
