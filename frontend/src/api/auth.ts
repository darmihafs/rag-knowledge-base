import { api } from '@/api/http'
import type { LoginVO, MyStatsVO, UserVO } from '@/types/api'

/** POST /api/auth/login —— 账号密码登录，成功返回 token + 用户信息 */
export const login = (username: string, password: string) =>
  api.post<LoginVO>('/auth/login', { username, password })

/** POST /api/auth/register —— 自助注册（默认普通用户），注册即登录 */
export const register = (username: string, password: string, nickname?: string) =>
  api.post<LoginVO>('/auth/register', { username, password, nickname })

/** GET /api/auth/me —— 回显当前登录用户，用于刷新页面后恢复用户态 */
export const fetchMe = () => api.get<UserVO>('/auth/me')

/** POST /api/auth/logout —— 退出登录（JWT 无状态，服务端仅确认，本地 token 由 store 清除） */
export const logout = () => api.post<void>('/auth/logout')

/**
 * PUT /api/auth/profile —— 修改昵称，直接返回最新用户信息。
 *
 * <p>后端做了「只能改自己」的校验（userId 取 JWT，不接受前端传入），所以这里只需要提交昵称；
 * 返回体可用于刷新本地用户缓存，省掉一次 /auth/me。
 */
export const updateProfile = (nickname: string) => api.put<UserVO>('/auth/profile', { nickname })

/** PUT /api/auth/password —— 校验原密码后改密；成功后旧 token 虽在有效期内，也应引导重新登录 */
export const updatePassword = (payload: { oldPassword: string; newPassword: string }) =>
  api.put<void>('/auth/password', payload)

/** GET /api/auth/my-stats —— 本人使用统计（会话数 / 提问数 / 回答数 / 反馈数 / 注册时间） */
export const fetchMyStats = () => api.get<MyStatsVO>('/auth/my-stats')
