package com.bishe.kb.service;

import com.bishe.kb.model.dto.PasswordUpdateRequest;
import com.bishe.kb.model.vo.MyStatsVO;
import com.bishe.kb.model.vo.UserVO;
import com.bishe.kb.security.LoginUser;

/**
 * 个人中心：自己改自己的资料与密码，外加本人维度的使用统计。
 *
 * <p>与「用户与权限管理」（管理员治理他人账号）刻意分开：这里的所有入参都只接受
 * JWT 身份，不接受 {@code userId}，因此不存在越权改别人的可能。
 */
public interface UserProfileService {

    /** 修改昵称，返回最新用户信息供前端刷新本地缓存 */
    UserVO updateProfile(String nickname, LoginUser current);

    /**
     * 退出登录：递增令牌版本号，让该用户此前签发的 token 全部失效。
     *
     * <p>光靠前端删掉 localStorage 里的 token 只是「本地登出」——token 一旦被抓走仍然能用，
     * 所以服务端这里补上真正的吊销动作。
     */
    void logout(LoginUser current);

    /**
     * 修改登录密码：先验原密码，再用 BCrypt 重写，并<b>递增令牌版本号</b>，
     * 使该用户此前签发的所有 JWT 立刻失效（包括当前这一个），前端随后引导重新登录。
     */
    void changePassword(PasswordUpdateRequest request, LoginUser current);

    /** 本人使用统计：会话数 / 提问数 / 回答数 / 反馈数 / 注册时间 */
    MyStatsVO myStats(LoginUser current);
}
