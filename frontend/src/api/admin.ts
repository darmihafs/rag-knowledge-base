import { api } from '@/api/http'
import type {
  AdminUserVO,
  AuditDetailVO,
  AuditMessageVO,
  PageResult,
  RetrieveCompareResult,
  SystemConfig,
  SystemConfigPayload,
  UserRole,
} from '@/types/api'

/**
 * 第十阶段后台管理接口封装。
 *
 * <p>分成两组：检索对比走 `/knowledge` 前缀（按精确路径要求管理员），其余三组走
 * `/users` 与 `/admin` 前缀（后端拦截器整段锁管理员）。普通用户调用会拿到 403，
 * 由 http 拦截器统一提示，页面本身不会渲染这些入口。
 */

// ---------------------------------------------------------------------- 检索对比实验室

/** POST /api/knowledge/retrieve-compare —— 同一问题并排跑词法 / 语义 / 混合三路，可选收敛到某个知识库空间 */
export const compareRetrieval = (payload: { question: string; topK?: number; spaceId?: number }) =>
  api.post<RetrieveCompareResult>('/knowledge/retrieve-compare', payload)

// ---------------------------------------------------------------------- 用户与权限管理

/** GET /api/users —— 分页查账号，keyword 同时匹配用户名与昵称 */
export const fetchUsers = (params: {
  pageNum: number
  pageSize: number
  keyword?: string
  role?: UserRole | ''
  status?: number | ''
}) => {
  // 空字符串会被 axios 原样发成 role=，后端按「不过滤」处理，但少发一个参数更干净
  const query = Object.fromEntries(Object.entries(params).filter(([, v]) => v !== '' && v !== undefined))
  return api.get<PageResult<AdminUserVO>>('/users', query)
}

/** POST /api/users —— 新建账号 */
export const createUser = (payload: {
  username: string
  password: string
  nickname?: string
  role?: UserRole
}) => api.post<AdminUserVO>('/users', payload)

/** PUT /api/users/{id}/role —— 切换管理员 / 普通用户（不能改当前登录的自己） */
export const updateUserRole = (id: number, role: UserRole) =>
  api.put<AdminUserVO>(`/users/${id}/role`, { role })

/** PUT /api/users/{id}/status —— 1 启用 / 0 禁用（禁用后该账号 token 立即失效） */
export const updateUserStatus = (id: number, status: number) =>
  api.put<AdminUserVO>(`/users/${id}/status`, { status })

/** PUT /api/users/{id}/reset-password —— 重置为系统初始密码，响应只回脱敏提示（不再明文回传密码） */
export const resetUserPassword = (id: number) => api.put<string>(`/users/${id}/reset-password`)

// ---------------------------------------------------------------------- 问答审计日志

/** GET /api/admin/audit/messages —— 全站问答记录，一行一问 */
export const fetchAuditMessages = (params: {
  pageNum: number
  pageSize: number
  keyword?: string
  username?: string
}) => {
  const query = Object.fromEntries(Object.entries(params).filter(([, v]) => v !== '' && v !== undefined))
  return api.get<PageResult<AuditMessageVO>>('/admin/audit/messages', query)
}

/** GET /api/admin/audit/messages/{id} —— 展开完整回答与引用来源 */
export const fetchAuditDetail = (messageId: number) =>
  api.get<AuditDetailVO>(`/admin/audit/messages/${messageId}`)

// ---------------------------------------------------------------------- 引擎参数配置

/** GET /api/admin/config —— 当前生效值 + 出厂默认 + 只读装配信息 + 参数说明 */
export const fetchSystemConfig = () => api.get<SystemConfig>('/admin/config')

/** PUT /api/admin/config —— 部分更新，保存后下一次问答即生效 */
export const updateSystemConfig = (payload: SystemConfigPayload) =>
  api.put<SystemConfig>('/admin/config', payload)

/** POST /api/admin/config/reset —— 恢复进程启动时的出厂配置 */
export const resetSystemConfig = () => api.post<SystemConfig>('/admin/config/reset')
