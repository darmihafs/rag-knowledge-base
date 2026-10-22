package com.bishe.kb.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bishe.kb.model.entity.KbSpaceMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 空间成员 Mapper：只有普通 CRUD。
 *
 * <p>成员列表要显示昵称与账号角色，那属于 {@code sys_user} 的字段，因此在服务层用
 * {@code selectBatchIds} 批量回查再在内存里拼装（与 {@code KbChunkServiceImpl.withOwnership}
 * 同一口径），而不是在这里写 join 返回投影对象——MyBatis 对 record 的构造器映射依赖
 * 编译期参数名，不如显式组装可靠。
 */
@Mapper
public interface KbSpaceMemberMapper extends BaseMapper<KbSpaceMember> {
}
