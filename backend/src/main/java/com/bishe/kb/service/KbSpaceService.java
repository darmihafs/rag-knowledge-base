package com.bishe.kb.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.bishe.kb.model.dto.SpacePageQuery;
import com.bishe.kb.model.dto.SpaceSaveRequest;
import com.bishe.kb.model.entity.KbSpace;
import com.bishe.kb.model.vo.SpaceVO;

import java.util.List;

/**
 * 知识库空间服务：空间的增删改查 + 冗余计数重算。
 */
public interface KbSpaceService extends IService<KbSpace> {

    /** 系统默认空间 ID，随 V3 迁移脚本 seed，历史文档归入此库，不可删除 */
    long DEFAULT_SPACE_ID = 1L;

    /** 分页查询空间列表（按 id 升序，默认空间永远在最前） */
    Page<SpaceVO> pageSpaces(SpacePageQuery query);

    /** 查询全部空间，供前端侧栏一次性加载 */
    List<SpaceVO> listSpaces();

    /** 新建空间，返回空间 ID */
    Long createSpace(SpaceSaveRequest request);

    /** 编辑空间名称 / 描述 */
    void updateSpace(Long id, SpaceSaveRequest request);

    /**
     * 删除空间。为保护数据，默认空间（id=1）与仍有文档的空间都禁止删除，
     * 需先把文档迁走或删空后才允许删除。
     */
    void deleteSpace(Long id);

    /**
     * 重算某空间的冗余计数（doc_count = 空间内文档数，chunk_count = 空间内文档分段数之和），
     * 幂等，文档增删后调用。spaceId 为空时忽略。
     */
    void recount(Long spaceId);
}
