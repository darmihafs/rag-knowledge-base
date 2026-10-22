import http from '@/api/http'
import type { ApiResult, Citation } from '@/types/api'
import { clearToken, getToken, redirectToLogin } from '@/utils/auth'

export interface AgentAskRequest {
  /** 1..1000 字符 */
  question: string
  /** 正整数；不传表示所有可见空间 */
  spaceId?: number
  /** 1..5，后端默认 4 */
  maxSteps?: number
  /** 客户端生成的任务 ID，用于运行中取消；8~64 位字母/数字/下划线/短横线 */
  taskId?: string
}

export interface AgentStep {
  step: number
  tool: string
  arguments: string
  observation: string
  success: boolean
  elapsedMillis: number
}

export type AgentStopReason =
  | 'COMPLETED'
  | 'STEP_LIMIT'
  | 'TOOL_LIMIT'
  | 'TIME_LIMIT'
  | 'CANCELLED'
  | 'MODEL_ERROR'
  | 'NO_EVIDENCE'

export interface AgentAskResponse {
  answer: string
  steps: AgentStep[]
  citations: Citation[]
  elapsedMillis: number
  modelCalls: number
  stopReason: AgentStopReason
  mock: boolean
}

export interface AgentStreamMeta {
  /** 服务端确认的任务 ID，取消接口用它定位任务 */
  taskId: string
  mock: boolean
  maxSteps: number
  /** 服务端硬超时预算（毫秒） */
  timeoutMillis: number
}

export interface AgentStreamHandlers {
  onMeta?: (meta: AgentStreamMeta) => void
  /** 每完成一次工具调用推送一条，不是增量 token */
  onStep?: (step: AgentStep) => void
  /** 完整结果：回答、全部工具记录与引用 */
  onDone?: (result: AgentAskResponse) => void
  onError?: (message: string, code?: number) => void
}

/** 同步返回整次运行；保留给脚本与旧客户端，前端页面使用流式接口。 */
export async function askAgent(payload: AgentAskRequest): Promise<AgentAskResponse> {
  const response = await http.post<ApiResult<AgentAskResponse>>('/agent/ask', payload, {
    timeout: 300000,
  })
  return response.data.data
}

/**
 * 用 fetch + ReadableStream 消费 Agent 的 SSE。
 *
 * <p>事件顺序：meta（拿到 taskId）→ step（0..8 条）→ done 或 error。
 * 服务端在 90 秒硬预算内收尾；AbortSignal 断开连接会让服务端取消任务。
 */
export async function askAgentStream(
  payload: AgentAskRequest,
  handlers: AgentStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/agent/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
    },
    body: JSON.stringify(payload),
    signal,
  })

  if (response.status === 401) {
    clearToken()
    handlers.onError?.('登录已过期，请重新登录', 401)
    redirectToLogin()
    return
  }

  // 参数校验、忙碌等错误在建立 SSE 之前由全局异常处理器返回普通 JSON。
  if (!response.ok || !response.body) {
    let message = `HTTP ${response.status}`
    let code: number | undefined
    try {
      const body = (await response.json()) as { message?: string; code?: number }
      message = body?.message ?? message
      code = body?.code
    } catch {
      /* 响应体不是 JSON，沿用状态码信息 */
    }
    handlers.onError?.(message, code)
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
    buffer += decoder.decode(value, { stream: true })

    let separator = buffer.indexOf('\n\n')
    while (separator >= 0) {
      dispatchAgentFrame(buffer.slice(0, separator), handlers)
      buffer = buffer.slice(separator + 2)
      separator = buffer.indexOf('\n\n')
    }
  }

  buffer += decoder.decode()
  if (buffer.trim()) {
    dispatchAgentFrame(buffer, handlers)
  }
}

/** 取消运行中的任务；data=false 表示任务已结束、已取消或不属于当前账号。 */
export async function cancelAgent(taskId: string): Promise<boolean> {
  const response = await http.post<ApiResult<boolean>>(
    `/agent/tasks/${encodeURIComponent(taskId)}/cancel`,
  )
  return response.data.data
}

function dispatchAgentFrame(frame: string, handlers: AgentStreamHandlers): void {
  let event = 'message'
  const dataLines: string[] = []
  for (const rawLine of frame.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trim())
    }
  }
  if (!dataLines.length) {
    return
  }

  let data: Record<string, unknown>
  try {
    data = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
  } catch {
    return
  }

  switch (event) {
    case 'meta':
      handlers.onMeta?.({
        taskId: String(data.taskId ?? ''),
        mock: data.mock === true,
        maxSteps: Number(data.maxSteps ?? 0),
        timeoutMillis: Number(data.timeoutMillis ?? 0),
      })
      break
    case 'step':
      handlers.onStep?.(data as unknown as AgentStep)
      break
    case 'done':
      handlers.onDone?.(data as unknown as AgentAskResponse)
      break
    case 'error':
      handlers.onError?.(
        typeof data.message === 'string' ? data.message : '智能体执行失败',
        typeof data.code === 'number' ? data.code : undefined,
      )
      break
    default:
      break
  }
}
