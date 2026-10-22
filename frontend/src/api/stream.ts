import type { AskStreamRequest, Citation } from '@/types/api'
import { clearToken, getToken, redirectToLogin } from '@/utils/auth'

/** 后端 SSE 各事件的载荷（见 RagAnswerServiceImpl 类注释） */
interface SsePayload {
  text?: string
  message?: string
  code?: number
  elapsedMillis?: number
  /**
   * done 事件带回落库后的回答消息主键。
   *
   * <p>前端本地的气泡 id 是临时生成的字符串，点赞/点踩必须锚到后端真实消息上，
   * 所以后端把落库改到推 done 之前同步完成，随耗时一起把 id 回传。落库失败时为 null。
   */
  messageId?: number | null
  retrieved?: number
  topK?: number
  history?: number
  sessionId?: number
  mock?: boolean
}

export interface StreamMeta {
  retrieved: number
  topK: number
  /** 本轮带进 Prompt 的历史消息条数（3 轮 = 6 条） */
  history: number
  sessionId: number
  mock: boolean
}

export interface StreamHandlers {
  onMeta?: (meta: StreamMeta) => void
  /** 每次收到一个增量片段，调用方直接追加到当前回答后面 */
  onDelta?: (text: string) => void
  onCitations?: (citations: Citation[]) => void
  /**
   * 回答结束。
   *
   * @param elapsedMillis 后端统计的生成耗时
   * @param messageId 落库后的回答消息主键；为 null 表示落库失败，此时该气泡无法点赞/点踩
   */
  onDone?: (elapsedMillis: number, messageId: number | null) => void
  onError?: (message: string, code?: number) => void
}

/**
 * 用 fetch + ReadableStream 消费后端的 SSE 流。
 *
 * 为什么不用 EventSource：它只支持 GET，问题只能塞 URL（中文还要处理编码），
 * 也没法带自定义请求头；fetch 流式读取配合 POST 更干净，还能用 AbortController 中断。
 */
export async function askStream(
  payload: AskStreamRequest,
  handlers: StreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch('/api/knowledge/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      // SSE 走 fetch，也要手动带上 JWT，否则会被后端拦截器判为未登录
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
    },
    body: JSON.stringify(payload),
    signal,
  })

  // 401：登录态失效，清 token 跳登录页
  if (response.status === 401) {
    clearToken()
    handlers.onError?.('登录已过期，请重新登录', 401)
    redirectToLogin()
    return
  }

  // 参数校验失败等情况后端返回的是普通 JSON（Result 结构），不是 SSE
  if (!response.ok || !response.body) {
    let message = `HTTP ${response.status}`
    try {
      const body = (await response.json()) as { message?: string }
      message = body?.message ?? message
    } catch {
      /* 响应体不是 JSON，沿用状态码信息 */
    }
    handlers.onError?.(message, response.status)
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

    // SSE 以空行分帧；一帧可能被 TCP 拆成多次 read，所以要留在 buffer 里等下一段
    let separator = buffer.indexOf('\n\n')
    while (separator >= 0) {
      dispatchFrame(buffer.slice(0, separator), handlers)
      buffer = buffer.slice(separator + 2)
      separator = buffer.indexOf('\n\n')
    }
  }

  // 冲刷解码器与最后一帧（服务端未补空行时）
  buffer += decoder.decode()
  if (buffer.trim()) {
    dispatchFrame(buffer, handlers)
  }
}

function dispatchFrame(frame: string, handlers: StreamHandlers): void {
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
  if (dataLines.length === 0) {
    return
  }

  const raw = dataLines.join('\n')
  let data: SsePayload
  try {
    data = JSON.parse(raw) as SsePayload
  } catch {
    // 不是 JSON 就当成纯文本增量，保证不会因格式问题丢字
    handlers.onDelta?.(raw)
    return
  }

  switch (event) {
    case 'meta':
      handlers.onMeta?.({
        retrieved: data.retrieved ?? 0,
        topK: data.topK ?? 0,
        history: data.history ?? 0,
        sessionId: data.sessionId ?? 0,
        mock: data.mock ?? false,
      })
      break
    case 'delta':
      handlers.onDelta?.(data.text ?? '')
      break
    case 'citations':
      handlers.onCitations?.((data as unknown as Citation[]) ?? [])
      break
    case 'done':
      handlers.onDone?.(data.elapsedMillis ?? 0, data.messageId ?? null)
      break
    case 'error':
      handlers.onError?.(data.message ?? '未知错误', data.code)
      break
    default:
      break
  }
}
