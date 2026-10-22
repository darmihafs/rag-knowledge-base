import http, { api, BizError } from '@/api/http'
import type { AskRequest, AskResponse, DocumentVO, HealthInfo, PageResult } from '@/types/api'

/** GET /api/health —— 后端存活 + 模型配置状态 */
export const fetchHealth = () => api.get<HealthInfo>('/health')

/**
 * POST /api/documents/upload —— 上传单个文档，后端落盘并入队后立即返回文档 ID（状态 PENDING）。
 * 解析 → 分块 → 向量化在后台完成，列表状态最终变为 INDEXED / FAILED。
 * spaceId 可选，为空时归入默认知识库。
 */
export const uploadDocument = (
  file: File,
  onProgress?: (percent: number) => void,
  spaceId?: number | null,
) => {
  const form = new FormData()
  form.append('file', file)
  const url = spaceId ? `/documents/upload?spaceId=${spaceId}` : '/documents/upload'
  return api.postForm<number>(url, form, onProgress)
}

/** GET /api/documents —— 分页列表，可按 spaceId 过滤 */
export const fetchDocuments = (params: {
  pageNum: number
  pageSize: number
  keyword?: string
  spaceId?: number | null
}) => api.get<PageResult<DocumentVO>>('/documents', { ...params })

/** DELETE /api/documents/{id} */
export const removeDocument = (id: number) => api.delete<void>(`/documents/${id}`)

/**
 * POST /api/documents/{id}/reindex —— 清空旧切片与向量后重新解析。
 * 用于 FAILED 文档恢复；文档仍在 PENDING / PARSING 时后端返回 4090。
 */
export const reindexDocument = (id: number) => api.post<void>(`/documents/${id}/reindex`)

/**
 * GET /api/documents/{id}/preview —— 取原文二进制，供「原文溯源」抽屉内嵌渲染。
 *
 * <p>必须直接用 axios 实例：`api.get` 会把响应按统一 Result 结构解包，文件流走它拿不到字节。
 * 登录用户都可调用（后端为该路径豁免了「文档管理仅管理员」）。
 *
 * <p>后端异常走 {@code GlobalExceptionHandler}，返回的是 HTTP 200 + JSON，因此在
 * {@code responseType: 'blob'} 下错误体也会以 Blob 形式到达，这里按 MIME 识别后还原成 BizError 抛出。
 */
export const fetchDocumentPreview = async (id: number): Promise<Blob> => {
  const response = await http.get<Blob>(`/documents/${id}/preview`, { responseType: 'blob' })
  const blob = response.data
  if (blob.type.includes('application/json')) {
    const body = JSON.parse(await blob.text()) as { code: number; message: string }
    throw new BizError(body.code, body.message || '原文加载失败')
  }
  return blob
}

/** POST /api/knowledge/ask —— RAG 问答 */
export const askKnowledge = (payload: AskRequest) => api.post<AskResponse>('/knowledge/ask', payload)
