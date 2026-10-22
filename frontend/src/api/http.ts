import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

import type { ApiResult } from '@/types/api'
import { clearToken, getToken, redirectToLogin } from '@/utils/auth'

/** 后端返回 code != 0 时抛出，便于调用方 catch 后做业务分支 */
export class BizError extends Error {
  readonly code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'BizError'
    this.code = code
  }
}

const http = axios.create({
  // 走 vite proxy，所以这里只写相对路径
  baseURL: '/api',
  timeout: 120000,
})

http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // 自动附加 JWT：登录后每个请求都带上 Authorization: Bearer <token>
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse<ApiResult<unknown>>) => {
    const body = response.data
    // 后端统一 Result 结构；非该结构（如文件流）原样放行
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== 0) {
        ElMessage.error(body.message || `请求失败(${body.code})`)
        return Promise.reject(new BizError(body.code, body.message))
      }
    }
    return response
  },
  (error) => {
    const status = error?.response?.status
    // 401：登录态失效——清本地 token 并跳登录页，不再叠加通用错误提示
    if (status === 401) {
      clearToken()
      redirectToLogin()
      return Promise.reject(error)
    }
    const friendly =
      status === 403
        ? '无权限访问该功能（仅管理员可用）'
        : status === 404
          ? '接口 404：后端未启动，或路径不匹配（检查 vite proxy 是否指向 8080）'
          : status === 413
            ? '文件过大，超出后端上传限制'
            : status === 500
              ? '后端 500：请看后端日志'
              : error?.message === 'Network Error'
                ? '网络错误：后端 http://localhost:8080 是否已启动？'
                : (error?.response?.data?.message ?? error?.message ?? '未知错误')
    ElMessage.error(friendly)
    return Promise.reject(error)
  },
)

async function unwrap<T>(promise: Promise<AxiosResponse<ApiResult<T>>>): Promise<T> {
  const response = await promise
  return response.data.data
}

export const api = {
  get: <T>(url: string, params?: Record<string, unknown>) =>
    unwrap<T>(http.get(url, { params })),
  post: <T>(url: string, data?: unknown) => unwrap<T>(http.post(url, data)),
  put: <T>(url: string, data?: unknown) => unwrap<T>(http.put(url, data)),
  postForm: <T>(url: string, form: FormData, onProgress?: (percent: number) => void) =>
    unwrap<T>(
      http.post(url, form, {
        headers: { 'Content-Type': 'multipart/form-data' },
        onUploadProgress: (event) => {
          if (onProgress && event.total) {
            onProgress(Math.round((event.loaded * 100) / event.total))
          }
        },
      }),
    ),
  delete: <T>(url: string) => unwrap<T>(http.delete(url)),
}

export default http
