package com.bishe.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bishe.kb.model.entity.KbSpace;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库空间 Mapper：常规 CRUD 继承 BaseMapper。
 */
@Mapper
public interface KbSpaceMapper extends BaseMapper<KbSpace> {
}
