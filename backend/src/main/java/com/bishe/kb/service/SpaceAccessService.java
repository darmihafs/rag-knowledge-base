package com.bishe.kb.service;

import com.bishe.kb.model.entity.SysUser;
import com.bishe.kb.model.vo.SpaceMemberVO;
import com.bishe.kb.model.vo.SpaceVO;

import java.util.List;

/**
 * 空间授权：决定「当前这个账号能读哪些知识库空间」，并把结论表达成检索能用的范围。
 *
 * <p>存在的原因：V8 把 {@code spaceId} 下推进了召回 SQL，但那只是<b>管理员手选的过滤条件</b>。
 * 普通用户在 {@code /api/knowledge/ask} 里传任意 spaceId 依然能召回到别人私有空间的切片，
 * 答案引用还会把原文片段直接带出去——这是越权读取，不是过滤没生效。本服务把空间从
 * 「分类标签」变成「权限边界」。
 *
 * <p>模型刻意保持最小：一行成员 = 「某用户可读某空间」。ADMIN 角色绕过成员表（否则管理员
 * 新建的空间自己反而看不见），普通用户只看得见自己有成员行的空间。
 */
public interface SpaceAccessService {

    /**
     * 当前登录用户可读的空间 ID 列表。
     *
     * @return 管理员返回 {@code null}（表示不限制）；普通用户返回可能为空的列表
     */
    List<Long> currentVisibleSpaceIds();

    /** 当前登录用户可读的空间列表，供问答页选择器使用（管理员=全部） */
    List<SpaceVO> visibleSpaces();

    /**
     * 把「请求里带的 spaceId」翻译成一次检索的范围，并在翻译过程中完成鉴权。
     *
     * @param requestedSpaceId 请求体里的空间，null/非正数表示调用方没有指定
     * @throws com.bishe.kb.common.BizException 指定了无权访问的空间时 4030
     */
    RetrievalScope scopeForCurrentUser(Long requestedSpaceId);

    /** 校验当前用户可读该空间，否则 4030；文档预览、语料查询都走这一个入口 */
    void requireReadable(Long spaceId);

    /** 空间成员列表（管理员视角） */
    List<SpaceMemberVO> members(Long spaceId);

    /**
     * 批量授权，幂等：已经是成员的直接跳过，不会插出重复行。
     *
     * @return 本次真正新增的成员数
     */
    int grant(Long spaceId, List<Long> userIds);

    /**
     * 批量撤销授权。
     *
     * @return 本次真正移除的成员数
     * @throws com.bishe.kb.common.BizException 试图撤销空间创建人时拒绝——否则空间会没人能管
     */
    int revoke(Long spaceId, List<Long> userIds);

    /**
     * 让账号加入默认知识库空间（公共库），使「注册即可问答」成立。
     *
     * <p>没有这一步，新建账号一条成员行都没有，按本服务的口径就是「可见集合为空 = 拒绝一切」，
     * 表现为「刚注册的用户问什么都没结果」——权限收紧不该把新用户默默关在库外。
     * ADMIN 角色本来就绕过成员表，传进来直接跳过。
     *
     * <p>刻意不抛异常：默认空间被人删掉时，注册也不该跟着失败，宁可退回「等管理员显式授权」。
     */
    void joinDefaultSpace(SysUser user);
}
