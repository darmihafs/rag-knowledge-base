<script setup lang="ts">
import { computed, ref, watchPostEffect } from 'vue'
import DOMPurify from 'dompurify'
import type { AgentAskResponse, AgentStopReason } from '@/api/agent'
import { renderMarkdown } from '@/utils/markdown'
import AgentToolTrace from './AgentToolTrace.vue'

const props = defineProps<{
  result: AgentAskResponse
  question: string
  scope: string
  maxSteps: number
}>()

type RunStatus = { type: 'success' | 'warning' | 'error'; title: string; description: string }
const statuses: Record<AgentStopReason, RunStatus> = {
  COMPLETED: { type: 'success', title: '运行已完成', description: '请结合下方真实来源核对回答。' },
  STEP_LIMIT: { type: 'warning', title: '达到模型轮数上限 · 未完成', description: '流程已停止，下方内容仅为本次已返回的结果。可缩小问题范围后重试。' },
  TOOL_LIMIT: { type: 'warning', title: '达到工具调用上限 · 未完成', description: '工具调用预算已用尽，请勿将部分结果视为完整回答。' },
  TIME_LIMIT: { type: 'warning', title: '达到运行时间上限 · 未完成', description: '运行已超时停止，请核对已有证据，或缩小问题范围后重试。' },
  CANCELLED: { type: 'warning', title: '任务已取消 · 未完成', description: '本次运行由你取消，已有工具记录与来源仅供排查。' },
  MODEL_ERROR: { type: 'error', title: '模型调用错误 · 未完成', description: '未能正常完成回答，已有工具记录仅供排查，可稍后重试。' },
  NO_EVIDENCE: { type: 'warning', title: '未找到可用证据', description: '无法据此给出有来源支撑的回答，请更换问题或检查可见空间内的文档。' },
}
const status = computed(() => statuses[props.result.stopReason] ?? {
  type: 'warning', title: '未知停止状态', description: '无法确认运行是否完成，请勿将结果视为成功。',
} as RunStatus)
// 工具“返回过片段”不等于“片段被采信为证据”：NO_EVIDENCE 时 citations 已由服务端清空，文案要如实区分。
const citationTitle = computed(() => props.result.citations.length
  ? `已读取的来源证据 · ${props.result.citations.length} 条`
  : '来源证据')
const citationHint = computed(() => props.result.citations.length
  ? '工具实际读取的去重片段，不表示回答逐条引用；请按文档名和片段核对。'
  : props.result.steps.length
    ? '工具返回的片段未被采信为来源证据；可在下方工具记录核对原始观察结果。'
    : '本次运行未读取任何正文片段。')
// Agent 只提供本地来源证据，阻止不可信资料诱导模型生成外链或外部图片带走正文。
const answerHtml = computed(() => DOMPurify.sanitize(renderMarkdown(props.result.answer), {
  FORBID_TAGS: ['img'], FORBID_ATTR: ['href', 'src', 'srcset'],
}))
const answerEl = ref<HTMLElement | null>(null)

// Agent 按文档名/片段定位，不把模型自写的 [N] 冒充成服务端证据数组的对应关系。
watchPostEffect(() => {
  answerEl.value?.querySelectorAll<HTMLElement>('sup.cite-ref').forEach((marker) => {
    marker.replaceWith(document.createTextNode(marker.textContent ?? ''))
  })
})
</script>

<template>
  <section class="run-result" aria-label="本次运行结果">
    <el-alert v-if="result.mock" type="warning" show-icon :closable="false"
      title="演示模式：固定检索流程，未调用模型" />
    <el-alert :type="status.type" :title="status.title" :description="status.description" show-icon :closable="false" />
    <el-card shadow="never">
      <template #header>
        <div class="result-head">
          <h3>本次运行</h3>
          <el-tag type="info" effect="plain">{{ result.stopReason }}</el-tag>
        </div>
        <p class="question">{{ question }}</p>
        <div class="metadata">
          <span>范围：{{ scope }}</span><span>模型轮数上限：{{ maxSteps }}</span>
          <span>总耗时：{{ result.elapsedMillis }} ms</span>
          <span>模型调用：{{ result.modelCalls }} 次</span>
          <span>工具记录：{{ result.steps.length }} 条</span>
        </div>
      </template>
      <h3>{{ result.stopReason === 'COMPLETED' ? '回答' : '本次返回内容（非完整成功结果）' }}</h3>
      <div v-if="result.answer.trim()" ref="answerEl" class="agent-markdown" v-html="answerHtml" />
      <el-empty v-else description="服务端未返回回答正文" :image-size="64" />
    </el-card>

    <AgentToolTrace :steps="result.steps" />

    <el-card shadow="never">
      <template #header>
        <h3>{{ citationTitle }}</h3>
        <p class="hint">{{ citationHint }}</p>
      </template>
      <el-empty v-if="!result.citations.length"
        :description="result.steps.length ? '本次未采信任何片段作为来源证据' : '本次运行未读取正文片段'"
        :image-size="64" />
      <div v-else class="citations">
        <article v-for="(citation, index) in result.citations" :key="index" class="citation">
          <h4>{{ citation.title || `文档 #${citation.documentId}` }}</h4>
          <div class="metadata">
            <span>文档 #{{ citation.documentId }}</span><span>第 {{ citation.chunkIndex + 1 }} 段</span>
            <span v-if="citation.pageNumber != null">第 {{ citation.pageNumber }} 页</span>
            <span v-if="citation.score != null">检索得分 {{ citation.score }}</span>
          </div>
          <p class="snippet">{{ citation.snippet || '服务端未返回原文片段' }}</p>
        </article>
      </div>
    </el-card>
  </section>
</template>

<style scoped>
.run-result { display: grid; gap: 16px; min-width: 0; }
h3 { margin: 0; font-size: 16px; }
h4 { margin: 0 0 8px; font-size: 14px; overflow-wrap: anywhere; }
.result-head, .metadata { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 18px; }
.result-head { justify-content: space-between; }
.metadata, .hint { color: var(--el-text-color-secondary); font-size: 12px; }
.hint { margin: 6px 0 0; }
.question { white-space: pre-wrap; overflow-wrap: anywhere; }
.citations { display: grid; gap: 12px; }
.citation { padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.citation:focus { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.snippet { margin: 12px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; line-height: 1.8; }
.agent-markdown { margin-top: 12px; font-size: 14px; line-height: 1.8; overflow-wrap: anywhere; overflow-x: auto; }
.agent-markdown :deep(p) { margin: 8px 0; }
.agent-markdown :deep(h1), .agent-markdown :deep(h2), .agent-markdown :deep(h3) { font-size: 17px; }
.agent-markdown :deep(pre) { padding: 14px; border-radius: 6px; background: var(--el-fill-color-light); overflow-x: auto; }
.agent-markdown :deep(code) { font-family: Consolas, monospace; background: var(--el-fill-color-light); }
.agent-markdown :deep(blockquote) { margin: 12px 0; padding-left: 14px; border-left: 3px solid var(--el-border-color); color: var(--el-text-color-secondary); }
.agent-markdown :deep(table) { border-collapse: collapse; }
.agent-markdown :deep(th), .agent-markdown :deep(td) { padding: 6px 10px; border: 1px solid var(--el-border-color); }
.agent-markdown :deep(img) { max-width: 100%; }
.agent-markdown :deep(.cite-ref[role='button']) { color: var(--el-color-primary); cursor: pointer; padding: 0 3px; }
.agent-markdown :deep(.cite-ref:focus-visible) { outline: 2px solid var(--el-color-primary); }
</style>
