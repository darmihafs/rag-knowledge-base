import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { askStream } from '@/api/stream'
import { createSession, deleteSession, fetchMessages, fetchSessions } from '@/api/session'
import type { ChatMessageVO, Citation, SessionVO } from '@/types/api'

export type ChatRole = 'user' | 'assistant'

export interface ChatMessage {
  id: string
  role: ChatRole
  content: string
  /**
   * 后端 chat_message 主键：历史消息直接映射，流式回答由 done 事件带回。
   *
   * <p>本地气泡 id 是临时字符串，点赞/点踩要锚到真实消息上只能用它。为 null 表示该回答
   * 落库失败或仍在生成中，此时评价按钮不可用。
   */
  backendId: number | null
  /** 仅 assistant 消息使用：本次回答引用的知识片段 */
  citations: Citation[]
  /** 流式进行中，用于展示光标动画 */
  streaming: boolean
  /** 失败信息，非空时该气泡按错误态渲染 */
  error: string
  /** 后端 done 事件回传的耗时 */
  elapsedMillis: number
  /** meta 事件：本次命中的片段数 */
  retrieved: number
  /** meta 事件：本轮带进 Prompt 的历史消息条数 */
  history: number
  /** meta 事件：是否演示模式（未配置 RAG_API_KEY 时的本地假流） */
  mock: boolean
  createdAt: string
}

let seq = 0
function nextId(prefix: string): string {
  seq += 1
  return `${prefix}-${Date.now().toString(36)}-${seq}`
}

function createMessage(role: ChatRole, content = ''): ChatMessage {
  return {
    id: nextId(role),
    role,
    content,
    backendId: null,
    citations: [],
    streaming: false,
    error: '',
    elapsedMillis: 0,
    retrieved: 0,
    history: 0,
    mock: false,
    createdAt: new Date().toLocaleTimeString(),
  }
}

/** 后端历史消息 -> 前端气泡模型，保证「历史记录」和「实时回答」用同一套渲染 */
function fromVo(vo: ChatMessageVO): ChatMessage {
  const msg = createMessage(vo.role, vo.content)
  // 历史消息自带主键，切回旧会话时赞/踩状态才能对上同一条回答
  msg.backendId = vo.id
  msg.citations = vo.citations ?? []
  // 历史里没有单独的 retrieved 字段，但召回数恒等于引用数，直接复用
  msg.retrieved = msg.citations.length
  msg.createdAt = formatTime(vo.createTime)
  return msg
}

function formatTime(iso: string): string {
  if (!iso) {
    return ''
  }
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString()
}

/**
 * 问答会话状态：多会话列表 + 当前会话消息 + 流式请求控制。
 *
 * <p>会话与消息都由后端持久化（chat_session / chat_message），刷新页面后 {@link init}
 * 会重新拉列表并选中最近活跃的那条，聊天记录不丢失。首次提问时才真正建会话，
 * 避免用户狂点「新建」堆出一屏空会话。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionVO[]>([])
  const currentSessionId = ref<number | null>(null)
  const messages = ref<ChatMessage[]>([])
  const sending = ref(false)
  const sessionsLoading = ref(false)
  const historyLoading = ref(false)
  const topK = ref(5)
  /**
   * 问答的检索范围：null = 不指定具体空间，后端按「当前账号可读的空间集合」收敛召回；
   * 非空 = 只在该空间召回，且后端会先校验它确实可读（不可读直接 4030，不返回别人的切片）。
   *
   * <p>这里只是「选哪个」，权限判定全在后端——前端把 spaceId 改成别人的空间 ID 也拿不到内容。
   */
  const spaceId = ref<number | null>(null)

  let controller: AbortController | null = null

  const currentSession = computed(
    () => sessions.value.find((s) => s.id === currentSessionId.value) ?? null,
  )
  const streamingMessage = computed(() => messages.value.find((m) => m.streaming) ?? null)
  const hasMessages = computed(() => messages.value.length > 0)

  /** 切换/删除会话前先掐断在途的流，否则增量会继续写进已经不属于它的气泡 */
  function abortInFlight() {
    controller?.abort()
    controller = null
    sending.value = false
  }

  async function loadSessions() {
    sessionsLoading.value = true
    try {
      sessions.value = await fetchSessions()
    } finally {
      sessionsLoading.value = false
    }
  }

  /** 页面加载时调用：拉会话列表并自动选中最近活跃的一条 */
  async function init() {
    await loadSessions()
    if (sessions.value.length > 0) {
      currentSessionId.value = null // 强制 selectSession 重新拉历史
      await selectSession(sessions.value[0].id)
    } else {
      currentSessionId.value = null
      messages.value = []
    }
  }

  async function selectSession(id: number) {
    if (id === currentSessionId.value) {
      return
    }
    abortInFlight()
    currentSessionId.value = id
    messages.value = []
    historyLoading.value = true
    try {
      const history = await fetchMessages(id)
      messages.value = history.map(fromVo)
    } catch {
      // 拉历史失败时留空，错误提示已由 axios 拦截器统一弹出
      messages.value = []
    } finally {
      historyLoading.value = false
    }
  }

  async function newSession() {
    abortInFlight()
    const session = await createSession()
    sessions.value = [session, ...sessions.value]
    currentSessionId.value = session.id
    messages.value = []
    return session
  }

  async function removeSession(id: number) {
    await deleteSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (currentSessionId.value === id) {
      abortInFlight()
      currentSessionId.value = null
      const next = sessions.value[0]
      if (next) {
        await selectSession(next.id)
      } else {
        messages.value = []
      }
    }
  }

  /** 没有当前会话就现建一个（默认标题「新对话」，后端会在首轮提问后自动改名） */
  async function ensureSessionId(): Promise<number> {
    if (currentSessionId.value != null) {
      return currentSessionId.value
    }
    const session = await createSession()
    sessions.value = [session, ...sessions.value]
    currentSessionId.value = session.id
    return session.id
  }

  async function send(question: string) {
    const text = question.trim()
    if (!text || sending.value) {
      return
    }

    let sessionId: number
    try {
      sessionId = await ensureSessionId()
    } catch (err) {
      // 建会话失败（多半是后端没起），把错误挂到一条空气泡上，不至于点了发送毫无反应
      const failed = createMessage('assistant')
      failed.error = err instanceof Error ? err.message : String(err)
      messages.value.push(createMessage('user', text), failed)
      return
    }

    const firstTurn = messages.value.length === 0

    messages.value.push(createMessage('user', text))
    messages.value.push(createMessage('assistant'))
    // 必须取回数组里的响应式代理对象：直接改 push 进去的原始对象不会触发视图更新，
    // 那样打字机就退化成“憋到最后一次性显示”。
    const answer = messages.value[messages.value.length - 1]
    answer.streaming = true

    sending.value = true
    controller = new AbortController()

    try {
      await askStream(
        {
          question: text,
          sessionId,
          topK: topK.value,
          // null 时整个字段不序列化，后端按「可读空间集合」收敛；选定了空间就只在该空间召回
          spaceId: spaceId.value ?? undefined,
        },
        {
          onMeta(meta) {
            answer.retrieved = meta.retrieved
            answer.history = meta.history
            answer.mock = meta.mock
          },
          onDelta(chunk) {
            answer.content += chunk
          },
          onCitations(citations) {
            answer.citations = citations ?? []
          },
          onDone(elapsedMillis, messageId) {
            answer.elapsedMillis = elapsedMillis
            // 后端在推 done 之前已同步落库，这里拿到 id 就能立刻给这条回答点赞/点踩
            answer.backendId = messageId ?? null
          },
          onError(message, code) {
            answer.error = code ? `${message}（code=${code}）` : message
          },
        },
        controller.signal,
      )
    } catch (err) {
      // AbortController.abort() 会让 fetch 抛 DOMException(AbortError)，这是用户主动停止
      if (isAbort(err)) {
        answer.error = answer.error || '已手动停止生成'
      } else {
        answer.error = err instanceof Error ? err.message : String(err)
      }
    } finally {
      answer.streaming = false
      // 一个字都没收到又没报错，通常是连接被中途掐断，给个兜底提示避免空白气泡
      if (!answer.content && !answer.error) {
        answer.error = '没有收到任何内容，请检查后端是否已启动'
      }
      sending.value = false
      controller = null
      // 首轮提问后后端把「新对话」改成了问题前 15 字，刷新列表让侧边栏标题同步；
      // 只在首轮刷，避免聊天过程中侧边栏因为活跃时间变化而反复跳动。
      if (firstTurn) {
        try {
          await loadSessions()
        } catch {
          /* 列表刷新失败不影响本轮对话 */
        }
      }
    }
  }

  function stop() {
    controller?.abort()
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    messages,
    sending,
    sessionsLoading,
    historyLoading,
    topK,
    spaceId,
    streamingMessage,
    hasMessages,
    init,
    loadSessions,
    selectSession,
    newSession,
    removeSession,
    send,
    stop,
  }
})

function isAbort(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError'
}
