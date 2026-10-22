<script setup lang="ts">
import type { AgentStep } from '@/api/agent'

defineProps<{ steps: AgentStep[]; running?: boolean }>()

const toolLabels: Record<string, string> = {
  search_knowledge: '检索知识',
  list_documents: '查找文档',
  read_chunks: '读取切片',
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <h3>工具调用记录{{ running ? ' · 实时' : '' }}</h3>
      <p class="hint">{{ running
        ? '运行中，每完成一次工具调用即时追加，不展示模型隐藏推理。'
        : '仅展示服务端返回的工具参数与观察结果，不展示模型隐藏推理。' }}</p>
    </template>
    <el-empty v-if="!steps.length" :description="running
      ? '模型正在规划或调用工具，尚无记录' : '本次运行未返回工具调用记录'" :image-size="64" />
    <ol v-else class="trace-list" :aria-live="running ? 'polite' : undefined">
      <li v-for="(item, index) in steps" :key="`${item.step}-${index}`">
        <details :open="!item.success || (!running && steps.length === 1)">
          <summary>
            <span class="step-number">步骤 {{ item.step }}</span>
            <strong>{{ toolLabels[item.tool] || '工具调用' }}</strong>
            <code>{{ item.tool }}</code>
            <el-tag :type="item.success ? 'success' : 'danger'" size="small" effect="plain">
              {{ item.success ? '调用成功' : '调用失败' }}
            </el-tag>
            <span class="hint">{{ item.elapsedMillis }} ms</span>
          </summary>
          <div class="trace-content">
            <h4>参数</h4>
            <pre>{{ item.arguments || '服务端未返回参数' }}</pre>
            <h4>观察结果</h4>
            <pre>{{ item.observation || '服务端未返回观察结果' }}</pre>
          </div>
        </details>
      </li>
    </ol>
  </el-card>
</template>

<style scoped>
h3 { margin: 0; font-size: 16px; }
h4 { margin: 14px 0 6px; font-size: 13px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; }
p.hint { margin: 6px 0 0; }
.trace-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 12px; }
details { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
summary { padding: 14px; cursor: pointer; line-height: 2; overflow-wrap: anywhere; }
summary > * { margin-right: 10px; }
summary:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.step-number { font-size: 12px; color: var(--el-color-primary); }
code { color: var(--el-text-color-regular); font-size: 12px; }
.trace-content { padding: 0 14px 14px; border-top: 1px solid var(--el-border-color-lighter); }
pre {
  margin: 0;
  padding: 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font: 12px/1.7 Consolas, monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 360px;
  overflow: auto;
}
</style>
