import { api } from '@/api/http'
import type { PromptTemplateVO, TemplateSavePayload } from '@/types/api'

/**
 * 提示词模板市场接口封装。
 *
 * <p>问答页只读启用中的模板（`/templates/active`，登录用户皆可），管理端读写走
 * `/admin/templates`（后端按前缀锁管理员）。普通用户看到的模板页因此是「只读目录」，
 * 新增/编辑/启停/删除按钮在前端同样按角色隐藏。
 */

/** GET /api/templates/active —— 问答页快捷指令胶囊的数据源，按权重升序 */
export const fetchActiveTemplates = () => api.get<PromptTemplateVO[]>('/templates/active')

/** GET /api/admin/templates —— 管理端全量列表（含已下架） */
export const fetchAllTemplates = () => api.get<PromptTemplateVO[]>('/admin/templates')

/** POST /api/admin/templates —— 新建模板 */
export const createTemplate = (payload: TemplateSavePayload) =>
  api.post<PromptTemplateVO>('/admin/templates', payload)

/** PUT /api/admin/templates/{id} —— 修改模板，含启用 / 下架 */
export const updateTemplate = (id: number, payload: TemplateSavePayload) =>
  api.put<PromptTemplateVO>(`/admin/templates/${id}`, payload)

/** DELETE /api/admin/templates/{id} */
export const deleteTemplate = (id: number) => api.delete<void>(`/admin/templates/${id}`)
