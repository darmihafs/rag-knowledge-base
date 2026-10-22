import { api } from '@/api/http'
import type { DashboardActivity, DashboardHealth, DashboardStats } from '@/types/api'

/** GET /api/dashboard/stats —— 首页数据大盘统计 */
export const fetchDashboardStats = () => api.get<DashboardStats>('/dashboard/stats')

/** GET /api/dashboard/components —— 系统核心组件健康状态 */
export const fetchDashboardComponents = () => api.get<DashboardHealth>('/dashboard/components')

/** GET /api/dashboard/activity —— 最近入库文档与问答动态 */
export const fetchDashboardActivity = () => api.get<DashboardActivity>('/dashboard/activity')
