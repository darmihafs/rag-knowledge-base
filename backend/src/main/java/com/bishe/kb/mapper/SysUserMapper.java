package com.bishe.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bishe.kb.model.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户 Mapper。逻辑删除由 MyBatis-Plus 全局配置（deleted 字段）自动接管。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
