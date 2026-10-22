package com.bishe.kb.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户：JWT 登录主体，也是 RBAC 角色与会话/知识库归属的锚点。
 *
 * <p>{@code password} 存的是 BCrypt 密文（自带盐、不可逆），任何接口都不得回传该字段。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 管理员：全功能（数据大屏 / 知识库管理 / 切片干预 / 智能问答） */
    public static final String ROLE_ADMIN = "ADMIN";
    /** 普通用户：仅智能问答 + 本人会话管理 */
    public static final String ROLE_USER = "USER";

    /** 账号正常 */
    public static final int STATUS_ENABLED = 1;
    /** 账号禁用 */
    public static final int STATUS_DISABLED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文，序列化到前端前必须剔除 */
    private String password;

    private String nickname;

    /** ADMIN / USER，取值见 {@link #ROLE_ADMIN} / {@link #ROLE_USER} */
    private String role;

    /** 1正常 0禁用 */
    private Integer status;

    /**
     * 令牌版本号：签发时写进 JWT 的 {@code tv} 载荷，每次请求与库中当前值比对。
     *
     * <p>改密码、管理员重置密码、主动退出登录都会让它 +1，从而<b>一次性作废该用户此前签发的全部
     * token</b>——JWT 本身无状态、无法作废，版本号就是它的吊销位（增量脚本 V7）。
     */
    private Integer tokenVersion;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(this.role);
    }

    public boolean isEnabled() {
        return this.status != null && this.status == STATUS_ENABLED;
    }

    /** 令牌版本号，历史行可能为 null，按 0 处理 */
    public int tokenVersionOrDefault() {
        return this.tokenVersion == null ? 0 : this.tokenVersion;
    }
}
