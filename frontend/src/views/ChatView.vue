<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  ChatDotRound,
  CircleClose,
  Delete,
  Document,
  MagicStick,
  Plus,
  Promotion,
  Reading,
  Search,
  Setting,
} from '@element-plus/icons-vue'

import { fetchDocumentPreview } from '@/api/kb'
import { fetchActiveTemplates } from '@/api/template'
import { fetchMyFeedback, submitFeedback } from '@/api/feedback'
import { useChatStore, type ChatMessage } from '@/stores/chat'
import { useDocumentStore } from '@/stores/document'
import { useSpaceStore } from '@/stores/space'
import { useUserStore } from '@/stores/user'
import { extractTerms, highlightSnippet } from '@/utils/citation'
import { renderMarkdown } from '@/utils/markdown'
import { FEEDBACK_ISSUE_TYPES } from '@/types/api'
import type { Citation, FeedbackBriefVO, PromptTemplateVO, SessionVO } from '@/types/api'

const chatStore = useChatStore()
const documentStore = useDocumentStore()
const userStore = useUserStore()
const spaceStore = useSpaceStore()

const { messages, sending, sessions, currentSessionId, sessionsLoading, historyLoading, spaceId } =
  storeToRefs(chatStore)
const { documents, total } = storeToRefs(documentStore)
const { accessibleSpaces, accessibleLoading } = storeToRefs(spaceStore)

/** 仅管理员能访问文档接口，普通用户不拉取（否则侧栏统计会触发 403） */
const isAdmin = computed(() => userStore.isAdmin)

const question = ref('')
const scrollRef = ref<HTMLElement | null>(null)
/** ElInput 暴露了 focus() 与内部 textarea 元素，填入模板后要把光标放到 {topic} 的位置 */
const inputRef = ref<{ focus: () => void; textarea?: HTMLTextAreaElement } | null>(null)
const showCitations = ref<Record<string, boolean>>({})

const SUGGESTIONS = [
  '这份文档的核心结论是什么？',
  '总结一下知识库里的关键概念',
  '文档中提到的步骤有哪些？',
]

const indexedCount = computed(
  () => documents.value.filter((doc) => doc.status === 'INDEXED').length,
)

/**
 * 检索范围选择器的绑定值：0 = 不指定空间（后端按当前账号的可读空间集合收敛），
 * 其余 = 只在该空间召回。
 *
 * <p>用 0 而不是 null 兜住下拉框，是因为 Element Plus 把 null 当「未选中」渲染成 placeholder，
 * 而「不指定」本身是一个有语义的选项，得能显式选中并显示出来。后端对 spaceId<=0 也归一为不指定，
 * 前后端口径一致。
 */
const scopeModel = computed({
  get: () => spaceId.value ?? 0,
  set: (value: number) => {
    spaceId.value = value > 0 ? value : null
  },
})

/** 管理员的「不指定」= 全库；普通用户的「不指定」= 自己有权限的那几个空间，文案要说清这个差别 */
const scopeAllLabel = computed(() => (isAdmin.value ? '全部空间（不过滤）' : '我的全部空间'))

// 被撤销授权 / 空间被删之后，选择器里已经没有这一项了，要把选中值退回「不指定」，
// 否则会拿着一个看不见的 spaceId 去提问，被后端判成越权
watch(accessibleSpaces, (spaces) => {
  if (spaceId.value != null && !spaces.some((s) => s.id === spaceId.value)) {
    spaceId.value = null
  }
})

async function handleSend(text?: string) {
  const payload = (text ?? question.value).trim()
  if (!payload || sending.value) {
    return
  }
  question.value = ''
  await chatStore.send(payload)
}

function handleKeydown(event: KeyboardEvent) {
  // 回车发送、Shift+回车换行；输入法组词期间的回车要放过，否则会把候选词直接发出去
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  void handleSend()
}

/* ---------------------------------------------------------------- 快捷指令模板 */

const templates = ref<PromptTemplateVO[]>([])

/** 只读接口，普通用户与管理员都走 /api/templates/active，拿到的就是上架模板 */
async function loadTemplates() {
  try {
    templates.value = await fetchActiveTemplates()
  } catch {
    templates.value = []
  }
}

/**
 * 点击胶囊：把模板正文填进输入框，光标停在 `{topic}` 被剥掉的位置。
 *
 * <p>模板里写 `{topic}` 是给用户的填空位，填入时整段删掉占位符比留着更好改；
 * 没有占位符的模板就退化成纯前缀，直接接着写问题。
 */
async function applyTemplate(tpl: PromptTemplateVO) {
  const raw = tpl.promptContent ?? ''
  const marker = raw.indexOf('{topic}')
  const text = marker >= 0 ? raw.replace(/\{topic\}/g, '') : raw
  question.value = text
  await nextTick()
  const el = inputRef.value
  el?.focus()
  const area = el?.textarea
  if (area) {
    // 光标落在原占位符处（正文被 trim 过就退到末尾），选中空区间方便直接打字
    const caret = marker >= 0 ? Math.min(Math.max(marker, 0), area.value.length) : area.value.length
    area.setSelectionRange(caret, caret)
  }
}

/* ---------------------------------------------------------------- 回答评价与 BadCase */

/** 按后端消息主键存本人的既有评价，用来点亮回答下方已经点过的赞/踩按钮 */
const ratings = ref<Record<number, FeedbackBriefVO>>({})

async function loadMyFeedback() {
  try {
    const list = await fetchMyFeedback()
    const map: Record<number, FeedbackBriefVO> = {}
    list.forEach((item) => {
      map[item.messageId] = item
    })
    ratings.value = map
  } catch {
    /* 回显失败不影响问答，评价按钮仍可按当前会话状态提交 */
  }
}

function ratingOf(message: ChatMessage | null): FeedbackBriefVO | null {
  if (message?.backendId == null) {
    return null
  }
  return ratings.value[message.backendId] ?? null
}

/**
 * 落库失败的回答拿不到 messageId（done 事件里是 null），这种气泡没法锚定评价。
 *
 * <p>与其把反馈丢进黑洞，不如明确告诉用户这条回答没入库、刷新后也评不了。
 */
function targetMessageId(message: ChatMessage): number | null {
  if (message.backendId == null) {
    ElMessage.warning('这条回答未成功落库，暂时无法评价')
    return null
  }
  return message.backendId
}

async function onThumbUp(message: ChatMessage) {
  const messageId = targetMessageId(message)
  if (messageId == null) {
    return
  }
  if (ratingOf(message)?.rating === 1) {
    ElMessage.info('你已经给这条回答点过赞了')
    return
  }
  try {
    // 点赞不收集归因：后端会顺手清空问题类型与说明，避免残留上一次点踩的内容
    const brief = await submitFeedback({ messageId, rating: 1 })
    ratings.value = { ...ratings.value, [messageId]: brief }
    ElMessage.success('感谢反馈，已记为好样本')
  } catch {
    /* 拦截器已提示 */
  }
}

const downDialog = reactive({
  visible: false,
  submitting: false,
  message: null as ChatMessage | null,
  messageId: 0,
  issueType: FEEDBACK_ISSUE_TYPES[0] as string,
  comment: '',
})

function openDownDialog(message: ChatMessage) {
  const messageId = targetMessageId(message)
  if (messageId == null) {
    return
  }
  const existing = ratingOf(message)
  downDialog.message = message
  downDialog.messageId = messageId
  // 已点过踩就沿用原归因，方便补充说明；新评价默认「检索漏检」，是 RAG 最常见的根因
  downDialog.issueType = existing?.issueType || FEEDBACK_ISSUE_TYPES[0]
  downDialog.comment = existing?.comment ?? ''
  downDialog.visible = true
}

/** 弹窗只回放回答开头一段：够判断有没有答非所问，全文看气泡即可 */
const downPreview = computed(() => {
  const raw = (downDialog.message?.content ?? '').replace(/\s+/g, ' ').trim()
  if (!raw) {
    return '（这条回答没有内容）'
  }
  return raw.length > 200 ? `${raw.slice(0, 200)}…` : raw
})

async function submitDown() {
  downDialog.submitting = true
  try {
    const brief = await submitFeedback({
      messageId: downDialog.messageId,
      rating: -1,
      issueType: downDialog.issueType,
      comment: downDialog.comment.trim() || undefined,
    })
    ratings.value = { ...ratings.value, [downDialog.messageId]: brief }
    downDialog.visible = false
    ElMessage.success('已生成 BadCase 工单，管理员会在反馈台处理')
  } catch {
    /* 拦截器已提示 */
  } finally {
    downDialog.submitting = false
  }
}

async function handleNewSession() {
  try {
    await chatStore.newSession()
    await nextTick()
    inputRef.value?.focus()
  } catch {
    /* 后端没起时错误提示已由拦截器弹出 */
  }
}

async function handleSelectSession(id: number) {
  await chatStore.selectSession(id)
  await nextTick()
  scrollToBottom(false)
}

async function handleDeleteSession(session: SessionVO, event: MouseEvent) {
  // 阻止冒泡，否则点删除会顺带触发切换会话
  event.stopPropagation()
  try {
    await ElMessageBox.confirm(
      `确定删除「${session.title}」吗？该会话下的聊天记录会一并清除，且不可恢复。`,
      '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户点了取消
  }
  try {
    await chatStore.removeSession(session.id)
    ElMessage.success('会话已删除')
  } catch {
    /* 拦截器已提示 */
  }
}

function toggleCitations(message: ChatMessage) {
  showCitations.value = {
    ...showCitations.value,
    [message.id]: !showCitations.value[message.id],
  }
}

/* ---------------------------------------------------------------- 原文溯源抽屉 */

interface TraceSource {
  url: string
  mime: string
}

/** 原文 blob 缓存：同一篇文档被多条引用命中时不重复下载 */
const traceCache = new Map<number, TraceSource>()

const traceVisible = ref(false)
const traceLoading = ref(false)
const traceError = ref('')
const traceCite = ref<Citation | null>(null)
const traceSource = ref<TraceSource | null>(null)
const tracePage = ref(1)
const traceKeywords = ref<string[]>([])

const traceIsPdf = computed(() => (traceSource.value?.mime ?? '').includes('pdf'))
/** PDF 与纯文本能被浏览器直接内嵌渲染；docx/pptx 这类只给片段对照框 */
const traceEmbeddable = computed(() => {
  const mime = traceSource.value?.mime ?? ''
  return traceIsPdf.value || mime.startsWith('text/')
})
const traceFrameSrc = computed(() => {
  const url = traceSource.value?.url ?? ''
  if (!url) {
    return ''
  }
  // 浏览器内置 PDF 查看器认 #page=N；非首页才拼锚点，避免多余的 hash 触发无谓重载
  return traceIsPdf.value && tracePage.value > 1 ? `${url}#page=${tracePage.value}` : url
})
const traceSnippetHtml = computed(() =>
  highlightSnippet(traceCite.value?.snippet ?? '', traceKeywords.value),
)

/** 某条 AI 回答对应的提问：往前找最近一条用户消息，用来抽高亮关键词 */
function questionOf(message: ChatMessage): string {
  const index = messages.value.findIndex((item) => item.id === message.id)
  for (let i = index - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') {
      return messages.value[i].content
    }
  }
  return ''
}

async function openTrace(cite: Citation, keywords: string[]) {
  traceCite.value = cite
  traceKeywords.value = keywords
  tracePage.value = cite.pageNumber ?? 1
  traceError.value = ''
  traceVisible.value = true

  const cached = traceCache.get(cite.documentId)
  if (cached) {
    traceSource.value = cached
    return
  }
  traceSource.value = null
  traceLoading.value = true
  try {
    const blob = await fetchDocumentPreview(cite.documentId)
    const source: TraceSource = { url: URL.createObjectURL(blob), mime: blob.type }
    traceCache.set(cite.documentId, source)
    traceSource.value = source
  } catch (err) {
    traceError.value = err instanceof Error ? err.message : '原文加载失败'
  } finally {
    traceLoading.value = false
  }
}

function openTraceFromMessage(cite: Citation, message: ChatMessage) {
  void openTrace(cite, extractTerms(questionOf(message)))
}

/** 正文角标 [1] 的点击：事件委托，因为 v-html 里的节点不在 Vue 的模板编译范围内 */
function onAnswerClick(event: MouseEvent, message: ChatMessage) {
  const target = event.target as HTMLElement | null
  const sup = target?.closest<HTMLElement>('.cite-ref')
  if (!sup) {
    return
  }
  const index = Number(sup.dataset.ref)
  const cite = message.citations[index]
  if (!cite) {
    ElMessage.warning(`回答里的角标 [${index + 1}] 没有对应的引用记录`)
    return
  }
  openTraceFromMessage(cite, message)
}

function stepPage(delta: number) {
  tracePage.value = Math.max(1, tracePage.value + delta)
}

onBeforeUnmount(() => {
  // 离开页面时回收 objectURL，否则整篇 PDF 的二进制会一直挂在内存里
  traceCache.forEach((source) => URL.revokeObjectURL(source.url))
  traceCache.clear()
})

function scrollToBottom(smooth = true) {
  const el = scrollRef.value
  if (!el) {
    return
  }
  el.scrollTo({ top: el.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
}

// 打字机期间内容每变一次就贴底，保证最新字符始终可见
watch(
  () => messages.value.map((m) => m.content.length).join(','),
  () => {
    void nextTick(() => scrollToBottom(false))
  },
)

watch(
  () => messages.value.length,
  () => {
    void nextTick(() => scrollToBottom())
  },
)

onMounted(async () => {
  inputRef.value?.focus()
  // 会话历史必拉；文档统计仅管理员有权限，普通用户不拉以免触发 403
  // 可读空间列表是登录级接口（/spaces/visible），两种角色都能调，选择器靠它渲染
  const tasks: Promise<unknown>[] = [chatStore.init(), spaceStore.loadAccessibleSpaces()]
  // 模板胶囊与本人评价都是「有则更好、失败不阻断」的旁路数据
  tasks.push(loadTemplates(), loadMyFeedback())
  if (isAdmin.value) {
    tasks.push(documentStore.loadDocuments(1, 50))
  }
  await Promise.allSettled(tasks)
  await nextTick()
  scrollToBottom(false)
})

onBeforeUnmount(() => chatStore.stop())
</script>

<template>
  <div class="chat-page">
    <aside class="chat-side">
      <div class="side-head">
        <div class="side-title">
          <el-icon><ChatDotRound /></el-icon>
          <span>历史对话</span>
        </div>
        <span class="side-count">{{ sessions.length }}</span>
      </div>

      <div class="side-new">
        <el-button type="primary" :icon="Plus" @click="handleNewSession">新建对话</el-button>
      </div>

      <div v-loading="sessionsLoading" class="side-list">
        <el-empty
          v-if="!sessionsLoading && sessions.length === 0"
          :image-size="60"
          description="还没有对话，点击上方新建"
        />

        <div
          v-for="session in sessions"
          :key="session.id"
          class="session-item"
          :class="{ 'session-item--active': session.id === currentSessionId }"
          @click="handleSelectSession(session.id)"
        >
          <el-icon class="session-icon"><Document /></el-icon>
          <span class="session-title" :title="session.title">{{ session.title }}</span>
          <el-icon
            class="session-delete"
            title="删除会话"
            @click="handleDeleteSession(session, $event)"
          >
            <Delete />
          </el-icon>
        </div>
      </div>

      <div v-if="isAdmin" class="side-foot">
        <div class="side-summary">
          已索引 <b>{{ indexedCount }}</b> / {{ total }} 篇
        </div>
        <el-button size="small" :icon="Setting" @click="$router.push('/knowledge')">
          知识库管理
        </el-button>
      </div>
    </aside>

    <section class="chat-main">
      <div ref="scrollRef" v-loading="historyLoading" class="chat-scroll">
        <div v-if="messages.length === 0 && !historyLoading" class="chat-empty">
          <el-icon :size="46" color="var(--el-color-primary)"><ChatDotRound /></el-icon>
          <h2>基于你自己文档的开卷问答</h2>
          <p>
            提问后会先在知识库中做「语义 + 关键词」双路混合召回，再把命中的片段交给大模型生成答案，
            回答末尾会附上引用来源。多轮追问会自动带上最近 3 轮对话上下文。
          </p>
          <div class="suggestions">
            <el-button
              v-for="item in SUGGESTIONS"
              :key="item"
              plain
              :icon="MagicStick"
              @click="handleSend(item)"
            >
              {{ item }}
            </el-button>
          </div>
        </div>

        <div
          v-for="message in messages"
          :key="message.id"
          class="msg-row"
          :class="message.role === 'user' ? 'msg-row--user' : 'msg-row--ai'"
        >
          <el-avatar v-if="message.role === 'assistant'" :size="32" class="avatar avatar--ai">
            AI
          </el-avatar>

          <div class="bubble-wrap">
            <div class="bubble" :class="message.role === 'user' ? 'bubble--user' : 'bubble--ai'">
              <!-- 用户消息按纯文本渲染；AI 回答走 marked + DOMPurify -->
              <span v-if="message.role === 'user'" class="plain-text">{{ message.content }}</span>
              <template v-else>
                <div
                  v-if="message.content"
                  class="markdown-body"
                  v-html="renderMarkdown(message.content)"
                  @click="onAnswerClick($event, message)"
                />
                <span v-if="message.streaming" class="cursor" />
                <span v-if="!message.content && message.streaming" class="thinking">
                  正在检索知识库…
                </span>
              </template>
            </div>

            <el-alert
              v-if="message.error"
              class="msg-error"
              type="error"
              :closable="false"
              show-icon
              :title="message.error"
            />

            <div v-if="message.role === 'assistant' && !message.streaming" class="msg-foot">
              <el-tag v-if="message.mock" type="warning" size="small" effect="dark">演示模式</el-tag>
              <span v-if="message.elapsedMillis" class="foot-text">
                耗时 {{ (message.elapsedMillis / 1000).toFixed(1) }}s
              </span>
              <span class="foot-text">召回 {{ message.retrieved }} 段</span>
              <span v-if="message.history" class="foot-text">上下文 {{ message.history }} 条</span>
              <el-button
                v-if="message.citations.length"
                link
                type="primary"
                size="small"
                @click="toggleCitations(message)"
              >
                {{ showCitations[message.id] ? '收起引用' : `引用 ${message.citations.length} 条` }}
              </el-button>

              <!-- 评价锚在后端消息主键上；未落库的回答（backendId 为 null）只能禁用 -->
              <span class="foot-rate">
                <el-button
                  link
                  size="small"
                  class="rate-btn"
                  :class="{ 'rate-btn--up-on': ratingOf(message)?.rating === 1 }"
                  :disabled="message.backendId == null"
                  @click="onThumbUp(message)"
                >
                  {{ ratingOf(message)?.rating === 1 ? '👍 已记为好样本' : '👍 有帮助' }}
                </el-button>
                <el-button
                  link
                  size="small"
                  class="rate-btn"
                  :class="{ 'rate-btn--down-on': ratingOf(message)?.rating === -1 }"
                  :disabled="message.backendId == null"
                  @click="openDownDialog(message)"
                >
                  {{
                    ratingOf(message)?.rating === -1
                      ? `👎 已反馈${ratingOf(message)?.issueType ? `：${ratingOf(message)?.issueType}` : ''}`
                      : '👎 需改进'
                  }}
                </el-button>
              </span>
            </div>

            <div v-if="showCitations[message.id] && message.citations.length" class="citations">
              <div v-for="(cite, index) in message.citations" :key="index" class="citation">
                <div class="citation-head">
                  <span class="citation-no">[{{ index + 1 }}]</span>
                  <el-icon><Document /></el-icon>
                  <b>{{ cite.title }}</b>
                  <el-tag size="small" type="info" effect="plain">第 {{ cite.chunkIndex + 1 }} 段</el-tag>
                  <el-tag v-if="cite.pageNumber" size="small" type="success" effect="plain">
                    第 {{ cite.pageNumber }} 页
                  </el-tag>
                  <el-button
                    link
                    type="primary"
                    size="small"
                    :icon="Reading"
                    @click="openTraceFromMessage(cite, message)"
                  >
                    原文溯源
                  </el-button>
                </div>
                <p class="citation-body">{{ cite.snippet }}</p>
              </div>
            </div>
          </div>

          <el-avatar v-if="message.role === 'user'" :size="32" class="avatar avatar--user">我</el-avatar>
        </div>
      </div>

      <div class="chat-input">
        <!-- 快捷指令模板：来自提示词模板市场，点击把引导前缀填入输入框并把光标停在占位处 -->
        <div v-if="templates.length" class="quick-templates">
          <span class="quick-label">
            <el-icon><MagicStick /></el-icon>
            快捷指令
          </span>
          <el-tooltip
            v-for="tpl in templates"
            :key="tpl.id"
            :content="tpl.promptContent"
            placement="top"
            :show-after="400"
            popper-class="template-pop"
          >
            <el-button size="small" round plain class="template-chip" @click="applyTemplate(tpl)">
              {{ tpl.title }}
            </el-button>
          </el-tooltip>
        </div>

        <!-- 检索范围：只列当前账号可读的空间，空值=不指定（后端按可读集合收敛召回） -->
        <div class="input-scope">
          <span class="quick-label">
            <el-icon><Reading /></el-icon>
            检索范围
          </span>
          <el-select
            v-model="scopeModel"
            size="small"
            class="scope-select"
            :loading="accessibleLoading"
            :no-data-text="accessibleLoading ? '加载中' : '没有可读的知识库空间'"
          >
            <el-option :value="0" :label="scopeAllLabel" />
            <el-option
              v-for="space in accessibleSpaces"
              :key="space.id"
              :value="space.id"
              :label="space.name"
            >
              <span>{{ space.name }}</span>
              <span class="scope-option-count">{{ space.docCount }} 篇</span>
            </el-option>
          </el-select>
          <span class="scope-hint">
            {{ spaceId ? '只在该知识库内召回' : isAdmin ? '全库召回' : '在我有权限的知识库内召回' }}
          </span>
        </div>

        <div class="input-shell">
          <el-input
            ref="inputRef"
            v-model="question"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            resize="none"
            maxlength="1000"
            show-word-limit
            placeholder="输入问题，Enter 发送 / Shift + Enter 换行"
            @keydown="handleKeydown"
          />
          <div class="input-actions">
            <el-button
              v-if="sending"
              type="danger"
              plain
              :icon="CircleClose"
              @click="chatStore.stop()"
            >
              停止
            </el-button>
            <el-button
              v-else
              type="primary"
              :icon="Promotion"
              :disabled="!question.trim()"
              @click="handleSend()"
            >
              发送
            </el-button>
          </div>
        </div>
        <div class="input-tip">
          答案由大模型基于召回片段生成，可能存在偏差，重要内容请核对引用来源。
        </div>
      </div>
    </section>

    <!-- 原文溯源抽屉：内嵌 PDF 原件并自动跳到引用切片所在页，右侧给命中片段对照框 -->
    <el-drawer v-model="traceVisible" title="原文溯源" size="52%" class="trace-drawer">
      <div v-if="traceCite" class="trace-body">
        <div class="trace-head">
          <div class="trace-title">
            <el-icon><Document /></el-icon>
            <b :title="traceCite.title">{{ traceCite.title }}</b>
            <el-tag size="small" type="info" effect="plain">第 {{ traceCite.chunkIndex + 1 }} 段</el-tag>
            <el-tag v-if="traceCite.pageNumber" size="small" type="success" effect="plain">
              第 {{ traceCite.pageNumber }} 页
            </el-tag>
            <el-tag v-if="traceCite.score != null" size="small" type="warning" effect="plain">
              相关度 {{ (traceCite.score * 100).toFixed(1) }}
            </el-tag>
          </div>

          <div class="trace-pager">
            <el-button
              size="small"
              :icon="ArrowLeft"
              :disabled="!traceIsPdf || tracePage <= 1"
              @click="stepPage(-1)"
            >
              上一页
            </el-button>
            <el-input-number
              v-model="tracePage"
              size="small"
              :min="1"
              :step="1"
              :disabled="!traceIsPdf"
              controls-position="right"
              class="trace-page-input"
            />
            <el-button size="small" :icon="ArrowRight" :disabled="!traceIsPdf" @click="stepPage(1)">
              下一页
            </el-button>
          </div>
        </div>

        <el-alert
          v-if="traceError"
          class="trace-alert"
          type="error"
          show-icon
          :closable="false"
          :title="traceError"
        />
        <el-alert
          v-else-if="traceSource && !traceEmbeddable"
          class="trace-alert"
          type="info"
          show-icon
          :closable="false"
          title="该格式浏览器无法内嵌渲染，请直接用下方片段对照核验，或到知识库管理页下载原件。"
        />
        <el-alert
          v-else-if="traceIsPdf && !traceCite.pageNumber"
          class="trace-alert"
          type="info"
          show-icon
          :closable="false"
          title="该切片未记录页码（入库早于页码功能），PDF 从首页打开，可用上方页码控件手动定位。"
        />

        <div v-loading="traceLoading" class="trace-viewer">
          <iframe
            v-if="traceSource && traceEmbeddable"
            :key="`${traceCite.documentId}-${tracePage}`"
            :src="traceFrameSrc"
            class="trace-frame"
            frameborder="0"
            title="原文预览"
          />
          <el-empty v-else-if="!traceLoading && !traceError" :image-size="60" description="暂无可内嵌的原文" />
        </div>

        <div class="trace-compare">
          <div class="compare-head">
            <el-icon><Search /></el-icon>
            <span>命中片段原文对照</span>
            <span class="compare-tip">黄色高亮 = 与本次提问重合的词句</span>
          </div>
          <!-- eslint-disable-next-line vue/no-v-html：内容已由 highlightSnippet 全量转义，只保留自插的 <mark> -->
          <p class="compare-body" v-html="traceSnippetHtml" />
        </div>
      </div>
    </el-drawer>

    <!-- 点踩弹窗：选一个归因 + 可补充说明，提交后在反馈台生成一张 BadCase 工单 -->
    <el-dialog v-model="downDialog.visible" title="这条回答哪里不对？" width="560px">
      <div v-if="downDialog.message" class="down-context">
        <div class="down-line">
          <span class="down-tag down-tag--q">提问</span>
          <p class="down-text">{{ questionOf(downDialog.message) || '（本轮提问未记录）' }}</p>
        </div>
        <div class="down-line">
          <span class="down-tag down-tag--a">回答</span>
          <p class="down-text down-answer">{{ downPreview }}</p>
        </div>
      </div>

      <el-form label-position="top" class="down-form">
        <el-form-item label="问题类型">
          <el-radio-group v-model="downDialog.issueType">
            <el-radio v-for="item in FEEDBACK_ISSUE_TYPES" :key="item" :value="item">
              {{ item }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="补充说明（选填）">
          <el-input
            v-model="downDialog.comment"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="比如：正确的资料其实在文档第 3 页，但这次没召回；或者答案里的结论原文没有提到"
          />
        </el-form-item>
      </el-form>
      <div class="down-tip">
        提交后管理员会在「问答反馈」页看到这张工单，可对照原始问答判定是漏检、切片该禁用，还是提示词要调整。
      </div>

      <template #footer>
        <el-button @click="downDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="downDialog.submitting" @click="submitDown">
          {{ ratingOf(downDialog.message)?.rating === -1 ? '更新工单' : '提交工单' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.chat-page {
  display: flex;
  height: 100%;
  gap: 12px;
  overflow: hidden;
}

/* ---------- 左侧历史会话 ---------- */
.chat-side {
  display: flex;
  flex-direction: column;
  width: 260px;
  flex-shrink: 0;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  overflow: hidden;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px 8px;
}

.side-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.side-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
  border-radius: 10px;
  padding: 0 8px;
  line-height: 18px;
}

.side-new {
  padding: 4px 12px 10px;
}

.side-new .el-button {
  width: 100%;
}

.side-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px 8px;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border-radius: 8px;
  margin-bottom: 4px;
  cursor: pointer;
  color: var(--el-text-color-regular);
  transition: background 0.15s;
}

.session-item:hover {
  background: var(--el-fill-color-light);
}

.session-item--active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 500;
}

.session-icon {
  flex-shrink: 0;
  font-size: 15px;
}

.session-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-delete {
  flex-shrink: 0;
  font-size: 14px;
  color: var(--el-text-color-secondary);
  opacity: 0;
  transition: opacity 0.15s, color 0.15s;
}

.session-item:hover .session-delete {
  opacity: 1;
}

.session-delete:hover {
  color: var(--el-color-danger);
}

.side-foot {
  padding: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.side-summary {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
  text-align: center;
}

.side-foot .el-button {
  width: 100%;
}

/* ---------- 右侧聊天区 ---------- */
.chat-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  overflow: hidden;
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 20px 22px;
}

.chat-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.chat-empty h2 {
  margin: 12px 0 6px;
  color: var(--el-text-color-primary);
}

.chat-empty p {
  max-width: 460px;
  margin: 0 0 18px;
  font-size: 13px;
  line-height: 1.7;
}

.suggestions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 18px;
}

.msg-row--user {
  justify-content: flex-end;
}

.avatar {
  flex-shrink: 0;
  font-size: 13px;
}

.avatar--ai {
  background: var(--el-color-primary);
}

.avatar--user {
  background: var(--el-color-success);
}

.bubble-wrap {
  max-width: min(76%, 760px);
  min-width: 0;
}

.bubble {
  padding: 10px 14px;
  border-radius: 10px;
  line-height: 1.7;
  word-break: break-word;
}

.bubble--user {
  background: var(--el-color-primary);
  color: #fff;
  border-top-right-radius: 2px;
}

.bubble--ai {
  background: var(--el-fill-color-light);
  border-top-left-radius: 2px;
}

.plain-text {
  white-space: pre-wrap;
}

.msg-error {
  margin-top: 8px;
}

.msg-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
  flex-wrap: wrap;
}

.foot-text {
  color: var(--el-text-color-secondary);
}

/* 赞/踩成组靠右，与耗时、召回数这些元信息共用一行；
   选择器多带一层 .msg-foot，否则压不过 Element Plus 的 .el-button.is-link 默认色 */
.foot-rate {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
}

.msg-foot .rate-btn {
  color: var(--el-text-color-secondary);
}

.msg-foot .rate-btn:not(.is-disabled):hover {
  color: var(--el-text-color-regular);
}

.msg-foot .rate-btn--up-on {
  color: var(--el-color-success);
}

.msg-foot .rate-btn--down-on {
  color: var(--el-color-danger);
}

.citations {
  margin-top: 8px;
  border-left: 3px solid var(--el-color-primary-light-5);
  padding-left: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.citation {
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
  padding: 8px 10px;
}

.citation-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  flex-wrap: wrap;
}

.citation-no {
  font-weight: 600;
  color: var(--el-color-primary);
}

.citation-body {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}

/* ---------- 原文溯源抽屉 ---------- */
.trace-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
}

.trace-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.trace-title {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
}

.trace-title b {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.trace-pager {
  display: flex;
  align-items: center;
  gap: 6px;
}

.trace-page-input {
  width: 96px;
}

.trace-viewer {
  flex: 1 1 auto;
  /* 抽屉 body 的百分比高度链在不同缩放级别下不稳，直接按视口给高，保证 PDF 有可用阅读区 */
  height: 58vh;
  min-height: 320px;
  border-radius: 8px;
  overflow: hidden;
  background: var(--el-fill-color-lighter);
}

.trace-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
}

.trace-compare {
  flex-shrink: 0;
  max-height: 30vh;
  overflow-y: auto;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.compare-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
}

.compare-tip {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.compare-body {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--el-text-color-regular);
}

/* v-html 生成的 <mark> 拿不到 scoped 标记，必须走 :deep */
.compare-body :deep(mark) {
  padding: 0 2px;
  border-radius: 2px;
  background: #fff1a8;
  color: #6b3f00;
}

/* 打字机光标 */
.cursor {
  display: inline-block;
  width: 7px;
  height: 15px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--el-color-primary);
  animation: blink 1s steps(2, start) infinite;
}

.thinking {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

/* ---------- 底部输入区 ---------- */
.chat-input {
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 12px 16px 10px;
  background: var(--el-bg-color);
}

/* 快捷指令胶囊：横向排列，窄屏换行而不是横向滚动，避免出现第二条滚动条 */
.quick-templates {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.quick-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 标题过长时截断（文字在 el-button 内层 span 上，得用 :deep 才管得到） */
.template-chip {
  max-width: 240px;
}

.template-chip :deep(span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 检索范围一行：靠左排，选择器给固定宽度，右侧提示语随角色变化 */
.input-scope {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.scope-select {
  width: 180px;
}

.scope-option-count {
  float: right;
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.scope-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.input-shell {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.input-shell :deep(.el-textarea) {
  flex: 1;
}

.input-shell :deep(.el-textarea__inner) {
  box-shadow: none;
  background: var(--el-fill-color-lighter);
  padding: 9px 12px;
}

.input-shell :deep(.el-input__count) {
  background: transparent;
  right: 10px;
  bottom: 4px;
}

.input-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.input-tip {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  text-align: center;
}

/* 侧边栏在任何桌面窗口尺寸下都常驻可见：小屏只是收窄，绝不 display:none 隐藏 */
@media (max-width: 900px) {
  .chat-side {
    width: 220px;
  }
}

/* ---------- 点踩反馈弹窗 ---------- */
.down-context {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  margin-bottom: 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
}

.down-line {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.down-tag {
  flex-shrink: 0;
  font-size: 12px;
  line-height: 20px;
  padding: 0 6px;
  border-radius: 4px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.down-tag--a {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color);
}

.down-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  word-break: break-word;
}

/* 回答可能很长，弹窗里只露 4 行，多余部分靠 downPreview 已截断 */
.down-answer {
  max-height: 84px;
  overflow: hidden;
}

.down-form :deep(.el-radio-group) {
  flex-wrap: wrap;
  gap: 4px 14px;
}

.down-tip {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-placeholder);
}
</style>

<style>
/* Markdown 正文样式不能 scoped，否则作用不到 v-html 生成的节点 */
.markdown-body {
  font-size: 14px;
}

.markdown-body > :first-child {
  margin-top: 0;
}

.markdown-body > :last-child {
  margin-bottom: 0;
}

.markdown-body p {
  margin: 6px 0;
}

.markdown-body h1,
.markdown-body h2,
.markdown-body h3 {
  margin: 14px 0 8px;
  font-size: 16px;
  font-weight: 600;
}

.markdown-body ul,
.markdown-body ol {
  margin: 6px 0;
  padding-left: 22px;
}

.markdown-body li {
  margin: 3px 0;
}

.markdown-body code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color-dark);
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 13px;
}

.markdown-body pre {
  margin: 8px 0;
  padding: 12px;
  border-radius: 8px;
  background: #1e1e1e;
  overflow-x: auto;
}

.markdown-body pre code {
  padding: 0;
  background: transparent;
  color: #e6e6e6;
}

.markdown-body blockquote {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--el-color-primary-light-5);
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-lighter);
}

.markdown-body table {
  border-collapse: collapse;
  margin: 8px 0;
}

.markdown-body th,
.markdown-body td {
  border: 1px solid var(--el-border-color);
  padding: 5px 10px;
  font-size: 13px;
}

.markdown-body a {
  color: var(--el-color-primary);
}

/* 引用角标 [n]：v-html 注入的节点拿不到 scoped 标记，样式写在非 scoped 块里 */
.markdown-body .cite-ref {
  margin: 0 1px;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  cursor: pointer;
  user-select: none;
  transition: background 0.15s, color 0.15s;
}

.markdown-body .cite-ref:hover {
  color: #fff;
  background: var(--el-color-primary);
}

/* 快捷指令 tooltip 的内容被 teleport 到 body，scoped 块管不到，所以放全局；
   模板正文可能是一段引导语，限制宽度并允许换行，避免撑成一整行 */
.template-pop {
  max-width: 340px;
  line-height: 1.7;
  white-space: pre-wrap;
}
</style>
