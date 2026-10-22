package com.bishe.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bishe.kb.model.entity.KbDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档主表 Mapper：常规 CRUD 直接继承 BaseMapper，
 * 复杂 SQL（如召回统计）再在本接口上加方法 + resources/mapper/KbDocumentMapper.xml。
 */
@Mapper
public interface KbDocumentMapper extends BaseMapper<KbDocument> {
}
