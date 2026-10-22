import { api } from '@/api/http'
import type { FeedbackBriefVO, FeedbackStatsVO, FeedbackTicketVO, PageResult } from '@/types/api'

/**
 * 问答质量反馈接口封装。
 *
 * <p>提交与回显走 `/feedback`（登录用户皆可，是问答页的本职工作），工单列表、处置与指标
 * 走 `/admin/feedback`（后端拦截器按前缀整段锁管理员）。普通用户调后者会拿到 403，
 * 由 http 拦截器统一提示。
 */

/** POST /api/feedback —— 点赞(rating=1) / 点踩(rating=-1)，同一人对同一回答只保留一张工单 */
export const submitFeedback = (payload: {
  messageId: number
  rating: number
  issueType?: string
  comment?: string
}) => api.post<FeedbackBriefVO>('/feedback', payload)

/**
 * GET /api/feedback/mine —— 回显本人已有评价，用于把点过赞/踩的回答高亮出来。
 *
 * <p>sessionId 留空则取本人全部评价；问答页按会话切换时不必传，一次拉回来本地映射即可。
 */
export const fetchMyFeedback = (sessionId?: number | '') =>
  api.get<FeedbackBriefVO[]>('/feedback/mine', sessionId ? { sessionId } : {})

/** GET /api/admin/feedback/page —— 管理端工单列表，按点赞/点踩、处理状态、问题类型筛选 */
export const fetchFeedbackTickets = (params: {
  pageNum: number
  pageSize: number
  rating?: number | ''
  status?: number | ''
  issueType?: string
}) => {
  const query = Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== '' && v !== undefined && v !== null),
  )
  return api.get<PageResult<FeedbackTicketVO>>('/admin/feedback/page', query)
}

/** PUT /api/admin/feedback/{id}/resolve —— 处置工单：0 待处理 / 1 已修复 / 2 已忽略 */
export const resolveFeedback = (id: number, payload: { status: number; adminNote?: string }) =>
  api.put<FeedbackTicketVO>(`/admin/feedback/${id}/resolve`, payload)

/** GET /api/admin/feedback/stats —— 质量指标卡与问题类型占比 */
export const fetchFeedbackStats = () => api.get<FeedbackStatsVO>('/admin/feedback/stats')
