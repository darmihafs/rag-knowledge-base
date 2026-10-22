import { api } from '@/api/http'
import type { ChatMessageVO, SessionVO } from '@/types/api'

/** GET /api/sessions —— 会话列表，后端已按最后活跃时间倒序 */
export const fetchSessions = () => api.get<SessionVO[]>('/sessions')

/** POST /api/sessions —— 新建会话，title 可不传，后端回落成「新对话」 */
export const createSession = (title?: string) =>
  api.post<SessionVO>('/sessions', title ? { title } : {})

/** DELETE /api/sessions/{id} —— 删除会话，后端连带清理名下消息 */
export const deleteSession = (id: number) => api.delete<void>(`/sessions/${id}`)

/** GET /api/sessions/{id}/messages —— 切换会话时拉取历史消息 */
export const fetchMessages = (id: number) => api.get<ChatMessageVO[]>(`/sessions/${id}/messages`)
