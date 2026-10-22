<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Connection, Refresh } from '@element-plus/icons-vue'
import {
  askAgentStream,
  cancelAgent,
  type AgentAskResponse,
  type AgentStep,
  type AgentStreamMeta,
} from '@/api/agent'
import { fetchVisibleSpaces } from '@/api/space'
import type { SpaceVO } from '@/types/api'
import AgentRunResult from './AgentRunResult.vue'
import AgentToolTrace from './AgentToolTrace.vue'

const form = reactive<{ question: string; spaceId: number; maxSteps: number | undefined }>({
  question: '', spaceId: 0, maxSteps: 4,
})
const spaces = ref<SpaceVO[] | null>(null)
const spacesLoading = ref(false)
const spacesError = ref('')
const loading = ref(false)
const error = ref('')
const validation = ref('')
const result = ref<AgentAskResponse | null>(null)
const submitted = ref<{ question: string; scope: string; maxSteps: number } | null>(null)
const taskId = ref('')
const liveMeta = ref<AgentStreamMeta | null>(null)
const liveSteps = ref<AgentStep[]>([])
const cancelling = ref(false)
const cancelError = ref('')
let disposed = false
let spaceRequest = 0
let runRequest = 0
let activeController: AbortController | null = null
const canRun = computed(() => !loading.value && !spacesLoading.value && !spacesError.value && !!spaces.value?.length)
// 服务端硬超时预算由 meta 下发，文案跟着真实配置走，改环境变量后不需要改前端。
const timeoutLabel = computed(() => {
  const seconds = Math.max(1, Math.round((liveMeta.value?.timeoutMillis ?? 90_000) / 1000))
  return seconds <= 120 ? `${seconds} 秒` : `${Math.round(seconds / 60)} 分钟`
})

async function loadSpaces() {
  if (disposed || spacesLoading.value) return
  const request = ++spaceRequest
  spacesLoading.value = true
  spacesError.value = ''
  try {
    const data = await fetchVisibleSpaces()
    if (disposed || request !== spaceRequest) return
    spaces.value = data
    if (form.spaceId && !data.some((space) => space.id === form.spaceId)) form.spaceId = 0
  } catch {
    if (!disposed && request === spaceRequest) spacesError.value = '无法加载可见空间，请检查网络或权限后重试。'
  } finally {
    if (!disposed && request === spaceRequest) spacesLoading.value = false
  }
}

async function run() {
  if (disposed || !canRun.value) return
  const question = form.question.trim()
  const maxSteps = form.maxSteps
  const spaceId = form.spaceId
  validation.value = ''
  if (!question || question.length > 1000) {
    validation.value = '请输入 1～1000 字的问题，不能只包含空白。'
    return
  }
  if (maxSteps == null || !Number.isInteger(maxSteps) || maxSteps < 1 || maxSteps > 5) {
    validation.value = '模型轮数上限须为 1～5 的整数。'
    return
  }
  const space = spaces.value?.find((item) => item.id === spaceId)
  if (spaceId !== 0 && (!Number.isInteger(spaceId) || spaceId <= 0 || !space)) {
    validation.value = '请选择有效的可见空间。'
    return
  }
  const request = ++runRequest
  const controller = new AbortController()
  activeController?.abort()
  activeController = controller
  loading.value = true
  cancelling.value = false
  cancelError.value = ''
  error.value = ''
  result.value = null
  liveMeta.value = null
  liveSteps.value = []
  // 客户端先生成任务 ID：meta 到达之前如果用户就点取消，也有可寻址的任务句柄。
  taskId.value = newTaskId()
  submitted.value = { question, scope: space?.name ?? '所有可见空间', maxSteps }
  let terminal = false
  const current = () => !disposed && request === runRequest && !controller.signal.aborted
  try {
    await askAgentStream({
      question,
      spaceId: spaceId || undefined,
      maxSteps,
      taskId: taskId.value,
    }, {
      onMeta: (meta) => {
        if (!current()) return
        liveMeta.value = meta
        if (meta.taskId) taskId.value = meta.taskId
      },
      onStep: (step) => {
        if (current()) liveSteps.value = [...liveSteps.value, step]
      },
      onDone: (data) => {
        terminal = true
        if (!current()) return
        result.value = data
        loading.value = false
      },
      onError: (message) => {
        terminal = true
        if (!current()) return
        error.value = message || '智能体执行失败，请稍后重试。'
        loading.value = false
      },
    }, controller.signal)
    // 连接被正常读到末尾却没有终止事件：传输层中断，服务端会在断连钩子里取消任务。
    if (current() && !terminal) {
      error.value = '连接已中断，未收到最终结果。服务端会取消本次任务，请重试。'
      loading.value = false
    }
  } catch (cause) {
    if (!current()) return
    terminal = true
    error.value = cause instanceof Error && cause.name === 'AbortError'
      ? '请求已中断，请重试。'
      : cause instanceof Error
        ? `连接失败：${cause.message}`
        : '服务暂不可用，请稍后重试。'
    loading.value = false
  } finally {
    if (activeController === controller) activeController = null
  }
}

async function cancelRun() {
  const id = taskId.value
  if (!loading.value || cancelling.value || !id) return
  cancelling.value = true
  cancelError.value = ''
  try {
    const accepted = await cancelAgent(id)
    // false 表示任务刚好在取消请求前结束，等流里的 done 收尾即可，不必报错。
    if (!accepted && loading.value && !disposed) {
      cancelError.value = '任务可能已经结束，正在等待最终结果…'
      cancelling.value = false
    }
  } catch (cause) {
    if (disposed) return
    cancelling.value = false
    cancelError.value = cause instanceof Error
      ? `取消请求失败：${cause.message}`
      : '取消请求失败，请稍后重试。'
  }
}

function newTaskId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `agent-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function clearResult() {
  if (loading.value) return
  result.value = null
  submitted.value = null
  error.value = ''
  validation.value = ''
  cancelError.value = ''
  liveSteps.value = []
  liveMeta.value = null
  taskId.value = ''
}

onMounted(loadSpaces)
onBeforeUnmount(() => {
  disposed = true
  ++spaceRequest
  ++runRequest
  // 断开 SSE 会触发服务端的断连钩子，取消仍在运行的任务，避免离开页面后空烧模型额度。
  activeController?.abort()
  activeController = null
})
</script>

<template>
  <div class="agent-page">
    <header class="page-head">
      <div>
        <h2><el-icon><Connection /></el-icon>知识库智能体</h2>
        <p>围绕一个问题调用知识库工具，核对回答、调用记录与引用证据。</p>
      </div>
      <el-tag type="info" effect="plain">单次任务 · 不保存对话</el-tag>
    </header>

    <el-card shadow="never">
      <el-form label-position="top" :disabled="loading" @submit.prevent="run">
        <el-form-item label="你的问题" :error="validation">
          <el-input v-model="form.question" type="textarea" :rows="3" maxlength="1000" show-word-limit
            placeholder="输入需要结合知识库资料回答的问题，可要求查找文档并核对相关章节。" aria-label="你的问题" />
        </el-form-item>
        <div class="controls">
          <el-form-item label="知识库范围" class="scope-field">
            <el-select v-model="form.spaceId" :loading="spacesLoading" :disabled="spacesLoading || !!spacesError || !spaces?.length"
              aria-label="知识库范围">
              <el-option label="所有可见空间" :value="0" />
              <el-option v-for="space in spaces ?? []" :key="space.id" :label="space.name" :value="space.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型轮数上限（1～5）">
            <el-input-number v-model="form.maxSteps" :min="1" :max="5" :step="1" step-strictly :precision="0" aria-label="模型轮数上限" />
          </el-form-item>
          <div class="actions">
            <el-button :disabled="loading || (!result && !error)" @click="clearResult">清空结果</el-button>
            <el-button type="primary" native-type="submit" :icon="Connection" :loading="loading" :disabled="!canRun">
              {{ loading ? '运行中' : '运行智能体' }}
            </el-button>
          </div>
        </div>
      </el-form>
      <p v-if="spacesLoading" class="hint" role="status">正在加载可见空间…</p>
      <el-alert v-else-if="spacesError" type="error" :closable="false" show-icon title="空间加载失败">
        <p>{{ spacesError }}</p>
        <el-button :icon="Refresh" size="small" @click="loadSpaces">重试加载空间</el-button>
      </el-alert>
      <el-alert v-else-if="spaces && !spaces.length" type="warning" :closable="false" show-icon title="当前没有可见空间">
        <p>请联系管理员授权知识库空间，然后重新加载。</p>
        <el-button :icon="Refresh" size="small" @click="loadSpaces">重新加载空间</el-button>
      </el-alert>
      <details class="tool-guide">
        <summary>可用工具与运行说明</summary>
        <ul>
          <li><code>search_knowledge</code>：检索知识；query 必填，limit 可选。</li>
          <li><code>list_documents</code>：查找文档；keyword、limit 可选。</li>
          <li><code>read_chunks</code>：读取切片；documentId 必填，startIndex、limit 可选。</li>
        </ul>
        <p>工具调用记录在每次调用完成后实时推送；单次任务有服务端硬超时，运行中可以取消。演示模式使用固定检索流程，未调用模型。</p>
      </details>
    </el-card>

    <section v-if="loading" class="waiting" role="status" aria-live="polite" aria-busy="true">
      <el-icon class="is-loading" :size="24"><Refresh /></el-icon>
      <h3>{{ cancelling ? '正在取消任务…' : '智能体正在运行' }}</h3>
      <p v-if="liveSteps.length">已实时收到 {{ liveSteps.length }} 条工具调用记录，模型仍在继续。</p>
      <p v-else>模型正在规划或调用工具；每次工具调用完成后，记录会立即出现在下方。</p>
      <p>单次任务最长运行 {{ timeoutLabel }}，到时自动停止并保留已有记录；离开页面会取消本次任务。</p>
      <el-button v-if="taskId" type="danger" plain :loading="cancelling" @click="cancelRun">
        {{ cancelling ? '取消中' : '取消任务' }}
      </el-button>
      <p v-if="cancelError" class="cancel-error" role="alert">{{ cancelError }}</p>
    </section>
    <AgentToolTrace v-if="loading && liveSteps.length" :steps="liveSteps" running />
    <el-alert v-if="!loading && error" type="error" show-icon :closable="false" title="请求失败 · 未获得运行结果">
      <p>{{ error }}</p>
      <p>请检查网络与服务状态后点击“运行智能体”重试；未收到最终结果时，服务端会取消本次任务。</p>
    </el-alert>
    <AgentRunResult v-if="!loading && !error && result && submitted" :result="result" :question="submitted.question"
      :scope="submitted.scope" :max-steps="submitted.maxSteps" />
    <el-empty v-if="!loading && !error && !result" description="输入问题并运行，查看真实回答、工具调用记录与引用来源" :image-size="96" />
  </div>
</template>

<style scoped>
.agent-page { max-width: 1180px; margin: 0 auto; display: grid; gap: 16px; }
.page-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
h2 { display: flex; align-items: center; gap: 10px; margin: 0; font-size: 22px; }
h2 .el-icon { color: var(--el-color-primary); }
.page-head p, .hint { margin: 6px 0 0; font-size: 13px; color: var(--el-text-color-secondary); }
.controls { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 12px 20px; }
.controls .el-form-item { margin-bottom: 0; }
.scope-field { flex: 1; min-width: 220px; }
.scope-field .el-select { width: 100%; }
.actions { display: flex; padding-bottom: 1px; }
.tool-guide { margin-top: 20px; font-size: 12px; color: var(--el-text-color-secondary); }
.tool-guide summary { cursor: pointer; }
.tool-guide ul { padding-left: 20px; line-height: 1.9; }
.waiting { padding: 36px 20px; text-align: center; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: var(--el-bg-color); }
.waiting .el-icon { color: var(--el-color-primary); }
.waiting h3 { font-size: 16px; }
.waiting p { font-size: 13px; color: var(--el-text-color-secondary); }
.waiting .el-button { margin-top: 4px; }
.cancel-error { font-size: 13px; color: var(--el-color-danger); }
@media (max-width: 600px) {
  .scope-field, .actions { flex-basis: 100%; min-width: 0; }
  .actions { justify-content: flex-end; }
}
</style>
