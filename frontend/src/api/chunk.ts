import { api } from '@/api/http'
import type {
  ChunkCorpusStats,
  ChunkVO,
  CorpusChunkVO,
  PageResult,
} from '@/types/api'

/** GET /api/documents/{id}/chunks/page —— 分页查看某文档下的分段 */
export const fetchChunks = (
  documentId: number,
  params: { pageNum: number; pageSize: number; keyword?: string },
) => api.get<PageResult<ChunkVO>>(`/documents/${documentId}/chunks/page`, { ...params })

/** PUT /api/chunks/{id} —— 人工编辑分段正文（后端同步刷新向量） */
export const updateChunk = (id: number, content: string) =>
  api.put<void>(`/chunks/${id}`, { content })

/** PUT /api/chunks/{id}/toggle-status —— 启用/禁用分段，返回切换后的状态（1 启用 / 0 禁用） */
export const toggleChunkStatus = (id: number) => api.put<number>(`/chunks/${id}/toggle-status`)

/**
 * GET /api/chunks/page —— 全局切片语料中心：跨文档分页。
 *
 * <p>四个过滤条件都可留空；后端对 null 与空串一律按「不过滤」处理，所以这里把空值剔除后再发，
 * 避免地址栏出现 `spaceId=` 这种歧义参数。
 */
export const fetchCorpusChunks = (params: {
  pageNum: number
  pageSize: number
  spaceId?: number | ''
  documentId?: number | ''
  enabled?: number | ''
  keyword?: string
}) => {
  const query = Object.fromEntries(
    Object.entries(params).filter(([, v]) => v !== '' && v !== undefined && v !== null),
  )
  return api.get<PageResult<CorpusChunkVO>>('/chunks/page', query)
}

/** GET /api/chunks/corpus-stats —— 语料规模统计（顶部统计条），口径为全库 */
export const fetchCorpusStats = () => api.get<ChunkCorpusStats>('/chunks/corpus-stats')

/**
 * POST /api/chunks/batch-status —— 批量启用 / 禁用，返回实际被改动的条数。
 *
 * <p>后端会同步维护向量库：启用即补写向量，禁用即删除向量，所以返回值可能小于勾选数
 * （本来就处于目标状态的条目不重复处理）。
 */
export const batchChunkStatus = (ids: number[], enabled: number) =>
  api.post<number>('/chunks/batch-status', { ids, enabled })
