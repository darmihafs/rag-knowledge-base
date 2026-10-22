import { api } from '@/api/http'
import type { PageResult, SpaceMemberVO, SpaceVO } from '@/types/api'

/** GET /api/spaces —— 分页查询知识库空间 */
export const fetchSpaces = (params: { pageNum: number; pageSize: number; keyword?: string }) =>
  api.get<PageResult<SpaceVO>>('/spaces', { ...params })

/** GET /api/spaces/all —— 全部空间，供侧栏一次性加载 */
export const fetchAllSpaces = () => api.get<SpaceVO[]>('/spaces/all')

/**
 * GET /api/spaces/visible —— 当前登录用户可读的空间。
 *
 * 管理员拿到全部，普通用户只拿到自己有成员关系的那些。问答页的空间选择器用它而不是
 * {@link fetchAllSpaces}：后者在 `/api/spaces` 前缀下是管理员专属，普通用户调会 403。
 */
export const fetchVisibleSpaces = () => api.get<SpaceVO[]>('/spaces/visible')

/** POST /api/spaces —— 新建空间，返回空间 ID */
export const createSpace = (payload: { name: string; description?: string }) =>
  api.post<number>('/spaces', payload)

/** PUT /api/spaces/{id} —— 编辑空间名称 / 描述 */
export const updateSpace = (id: number, payload: { name: string; description?: string }) =>
  api.put<void>(`/spaces/${id}`, payload)

/** DELETE /api/spaces/{id} —— 删除空间（默认空间或仍有文档时后端会拒绝） */
export const deleteSpace = (id: number) => api.delete<void>(`/spaces/${id}`)

/** GET /api/spaces/{id}/members —— 空间成员列表（仅管理员） */
export const fetchSpaceMembers = (id: number) => api.get<SpaceMemberVO[]>(`/spaces/${id}/members`)

/**
 * POST /api/spaces/{id}/members —— 批量授权成员（仅管理员）
 *
 * 后端幂等：已经是成员的会被跳过，返回本次真正新增的人数，所以重复提交不会产生重复授权。
 */
export const grantSpaceMembers = (id: number, userIds: number[]) =>
  api.post<number>(`/spaces/${id}/members`, { userIds })

/** DELETE /api/spaces/{id}/members/{userId} —— 撤销单个成员（仅管理员，空间创建人不可撤销） */
export const revokeSpaceMember = (id: number, userId: number) =>
  api.delete<number>(`/spaces/${id}/members/${userId}`)
