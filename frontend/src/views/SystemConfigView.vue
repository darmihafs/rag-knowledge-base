<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, RefreshLeft, Search } from '@element-plus/icons-vue'

import { fetchSystemConfig, resetSystemConfig, updateSystemConfig } from '@/api/admin'
import type { SystemConfig, SystemConfigPayload } from '@/types/api'

/**
 * 引擎参数与 Prompt 配置：运行时改 RagProperties 里「每次调用都重新读」的那几项。
 *
 * <p>表单初值一律来自 {@code GET /api/admin/config}，前端不写死任何出厂数值；
 * 保存时只提交被改过的字段（后端是 null-保持原值 的部分更新语义），
 * 避免某个控件没渲染就把参数误置为 0。模型名、温度、密钥属于装配期参数，
 * 只放在右列只读区并标明「需重启」。
 */

const cfg = ref<SystemConfig | null>(null)
const loading = ref(false)
const saving = ref(false)

/** 编辑区模型 */
const form = reactive({
  topK: 5,
  rrfK: 60,
  semanticMinScore: 0,
  chunkMaxChars: 800,
  chunkOverlapChars: 120,
  historyMessages: 6,
  historyMaxChars: 1200,
  systemPrompt: '',
})

/** 上一次服务端返回值，用来判定「哪些字段被改过」 */
const baseline = ref({ ...form })

function applyConfig(next: SystemConfig) {
  cfg.value = next
  const editable = {
    topK: next.topK,
    rrfK: next.rrfK,
    semanticMinScore: next.semanticMinScore,
    chunkMaxChars: next.chunkMaxChars,
    chunkOverlapChars: next.chunkOverlapChars,
    historyMessages: next.historyMessages,
    historyMaxChars: next.historyMaxChars,
    systemPrompt: next.systemPrompt,
  }
  Object.assign(form, editable)
  baseline.value = { ...editable }
}

async function load() {
  loading.value = true
  try {
    applyConfig(await fetchSystemConfig())
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ------------------------------------------------------------------ 差异与校验

const changedKeys = computed(() =>
  (Object.keys(form) as (keyof typeof form)[]).filter((k) => form[k] !== baseline.value[k]),
)
const isDirty = computed(() => changedKeys.value.length > 0)

/** 跨字段约束：与后端 4021/4022 同一套规则，提前在页面上说清楚，省一次失败请求 */
const chunkWarn = computed(() => {
  const { chunkOverlapChars: overlap, chunkMaxChars: max } = form
  if (overlap >= max) return '重叠字数必须小于切片上限，否则切分窗口无法前进，保存会被后端拒绝'
  if (overlap > max / 2) return '重叠字数已超过切片上限的一半，切片会大量重复、候选池被稀释'
  return ''
})

/** 清空提示词后端视为「不改」，真要回落内置规则得走恢复默认，这里如实说明 */
const promptNote = computed(() =>
  form.systemPrompt.trim()
    ? '已自定义；下一次问答即按它拼装 System Prompt。'
    : '当前为空，问答链路使用代码内置的默认防幻觉规则。',
)

async function onSave() {
  if (chunkWarn.value) {
    ElMessage.warning(chunkWarn.value)
    return
  }
  const payload: SystemConfigPayload = {}
  for (const key of changedKeys.value) {
    (payload as Record<string, unknown>)[key] = form[key]
  }
  saving.value = true
  try {
    applyConfig(await updateSystemConfig(payload))
    ElMessage.success(`已保存 ${changedKeys.value.length || 0} 项，下一次问答即生效`)
  } catch {
    /* 校验失败由拦截器提示，保留表单内容让用户改 */
  } finally {
    saving.value = false
  }
}

async function onResetDefaults() {
  try {
    await ElMessageBox.confirm(
      '确认恢复为后端启动时从 yaml 读到的出厂配置？本次进程内的改动会被全部覆盖。',
      '恢复默认配置',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    applyConfig(await resetSystemConfig())
    ElMessage.success('已恢复出厂配置')
  } catch {
    /* 拦截器已提示 */
  }
}

function defaultOf(key: keyof typeof form) {
  return cfg.value?.defaults[key] ?? '—'
}

/** 只读装配区：runtime 是后端给的键值Map，前端按原样列出，不额外猜字段 */
const runtimeEntries = computed(() => Object.entries(cfg.value?.runtime ?? {}))
</script>

<template>
  <div class="config" v-loading="loading">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="lead"
      title="这里改的是进程内的运行时参数，保存后下一次请求立即生效，不需要重启"
      description="模型名、温度、API Key、目录路径属于装配期参数，已固化进模型客户端 Bean，列在右侧只读区，改动需重启后端。切分粒度只影响此后上传的文档，已入库切片不会自动重切。"
    />

    <el-row :gutter="16">
      <el-col :lg="16" :md="24">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>检索超参数</span>
              <span class="hint">即时生效 · 问答与检索实验室共用</span>
            </div>
          </template>

          <div class="field">
            <div class="field-head">
              <span class="field-label">召回条数 topK</span>
              <span class="field-value">{{ form.topK }} 条</span>
            </div>
            <el-slider v-model="form.topK" :min="1" :max="20" :step="1" show-stops />
            <div class="field-foot">
              <span>{{ cfg?.hints.topK }}</span>
              <span class="default">出厂值 {{ defaultOf('topK') }}</span>
            </div>
          </div>

          <el-divider />

          <div class="field">
            <div class="field-head">
              <span class="field-label">RRF 常数 k</span>
              <span class="field-value">{{ form.rrfK }}</span>
            </div>
            <el-slider v-model="form.rrfK" :min="1" :max="200" :step="1" />
            <div class="field-foot">
              <span>{{ cfg?.hints.rrfK }}</span>
              <span class="default">出厂值 {{ defaultOf('rrfK') }}</span>
            </div>
          </div>

          <el-divider />

          <div class="field">
            <div class="field-head">
              <span class="field-label">语义相似度下限</span>
              <span class="field-value">{{ form.semanticMinScore.toFixed(2) }}</span>
            </div>
            <!-- Element Plus 的 marks 文案绝对定位在滑轨下方，不会自动占位；留出下边距避免压住帮助文案。 -->
            <el-slider v-model="form.semanticMinScore" class="score-slider" :min="0" :max="1" :step="0.05"
              :marks="{ 0: '0 不按分数过滤' }" />
            <div class="field-foot">
              <span>{{ cfg?.hints.semanticMinScore }}</span>
              <span class="default">出厂值 {{ defaultOf('semanticMinScore') }}</span>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>分块滑动窗口参数</span>
              <el-tag v-if="chunkWarn" type="warning" size="small" effect="light">窗口约束告警</el-tag>
              <span v-else class="hint">只影响此后上传的文档</span>
            </div>
          </template>

          <div class="field">
            <div class="field-head">
              <span class="field-label">单切片上限</span>
              <span class="field-value">{{ form.chunkMaxChars }} 字</span>
            </div>
            <el-slider v-model="form.chunkMaxChars" :min="100" :max="5000" :step="50" />
            <div class="field-foot">
              <span>{{ cfg?.hints.chunkMaxChars }}</span>
              <span class="default">出厂值 {{ defaultOf('chunkMaxChars') }}</span>
            </div>
          </div>

          <el-divider />

          <div class="field">
            <div class="field-head">
              <span class="field-label">相邻切片重叠</span>
              <span class="field-value">{{ form.chunkOverlapChars }} 字</span>
            </div>
            <el-slider v-model="form.chunkOverlapChars" :min="0" :max="2000" :step="10" />
            <div class="field-foot">
              <span>{{ cfg?.hints.chunkOverlapChars }}</span>
              <span class="default">出厂值 {{ defaultOf('chunkOverlapChars') }}</span>
            </div>
          </div>

          <el-alert
            v-if="chunkWarn"
            :title="chunkWarn"
            type="warning"
            :closable="false"
            show-icon
            class="inline-warn"
          />
          <div v-else class="ratio">
            重叠占比 {{ ((form.chunkOverlapChars / form.chunkMaxChars) * 100).toFixed(1) }}%，安全区间为 0 ~ 50%
          </div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>多轮上下文</span>
              <span class="hint">影响带历史的大模型入参</span>
            </div>
          </template>

          <div class="field">
            <div class="field-head">
              <span class="field-label">携带历史条数</span>
              <span class="field-value">{{ form.historyMessages }} 条（约 {{ (form.historyMessages / 2).toFixed(1) }} 轮）</span>
            </div>
            <el-slider v-model="form.historyMessages" :min="0" :max="20" :step="1" />
            <div class="field-foot">
              <span>{{ cfg?.hints.historyMessages }}</span>
              <span class="default">出厂值 {{ defaultOf('historyMessages') }}</span>
            </div>
          </div>

          <el-divider />

          <div class="field">
            <div class="field-head">
              <span class="field-label">单条历史上限</span>
              <span class="field-value">{{ form.historyMaxChars }} 字</span>
            </div>
            <el-slider v-model="form.historyMaxChars" :min="100" :max="5000" :step="50" />
            <div class="field-foot">
              <span>{{ cfg?.hints.historyMaxChars }}</span>
              <span class="default">出厂值 {{ defaultOf('historyMaxChars') }}</span>
            </div>
          </div>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>大模型系统级防幻觉 Prompt</span>
              <span class="hint">{{ promptNote }}</span>
            </div>
          </template>
          <el-input
            v-model="form.systemPrompt"
            type="textarea"
            :rows="8"
            maxlength="4000"
            show-word-limit
            placeholder="例如：只依据【资料】回答，资料未覆盖时明确说明不知道，不要编造参数与结论……"
          />
          <div class="field-foot">
            <span>{{ cfg?.hints.systemPrompt }}</span>
          </div>
          <el-alert
            v-if="form.systemPrompt && !form.systemPrompt.trim()"
            title="提示词不能全为空白；想回落内置规则请点「恢复默认配置」"
            type="warning"
            :closable="false"
            show-icon
            class="inline-warn"
          />
        </el-card>
      </el-col>

      <el-col :lg="8" :md="24">
        <el-card shadow="never" class="block side">
          <template #header>
            <div class="card-header">
              <span>装配期信息（只读）</span>
              <el-tag size="small" type="warning" effect="plain">需重启</el-tag>
            </div>
          </template>
          <div class="runtime">
            <div v-for="[key, value] in runtimeEntries" :key="key" class="runtime-row">
              <span class="runtime-key">{{ key }}</span>
              <span class="runtime-value" :title="String(value)">{{ value === null ? '—' : String(value) }}</span>
            </div>
          </div>
          <el-divider />
          <div class="switches">
            <div class="switch-row">
              <span>向量检索</span>
              <el-tag :type="cfg?.embeddingEnabled ? 'success' : 'info'" size="small" effect="plain">
                {{ cfg?.embeddingEnabled ? '已启用' : '未启用（纯词法）' }}
              </el-tag>
            </div>
            <div class="switch-row">
              <span>Mock 应答</span>
              <el-tag :type="cfg?.mockEnabled ? 'danger' : 'success'" size="small" effect="plain">
                {{ cfg?.mockEnabled ? '开启（不调大模型）' : '关闭（真实模型）' }}
              </el-tag>
            </div>
          </div>
          <div class="hint-line">{{ cfg?.hints.runtime }}</div>
        </el-card>

        <el-card shadow="never" class="block actions">
          <div class="action-head">
            <span class="dirty">
              <template v-if="isDirty">
                <el-icon><Check /></el-icon>
                {{ changedKeys.length }} 项待保存：{{ changedKeys.join('、') }}
              </template>
              <template v-else>当前表单与服务端一致</template>
            </span>
            <el-button size="small" :icon="Search" @click="load">重新拉取</el-button>
          </div>
          <div class="action-btns">
            <el-button
              type="primary"
              :icon="Check"
              :loading="saving"
              :disabled="!isDirty || !!chunkWarn"
              @click="onSave"
            >
              保存生效
            </el-button>
            <el-button :icon="RefreshLeft" @click="onResetDefaults">恢复默认配置</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.config {
  max-width: 1280px;
  margin: 0 auto;
}

.lead {
  margin-bottom: 16px;
}

.block {
  border-radius: 10px;
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-weight: 600;
}

.hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.field + .el-divider {
  margin: 18px 0;
}

.field-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 4px;
}

.field-label {
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.field-value {
  font-size: 13px;
  color: var(--el-color-primary);
  font-variant-numeric: tabular-nums;
}

.field-foot {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

/* marks 文案悬浮在滑轨下方约 15px，需要额外占位，否则会与下一行说明文字重叠。 */
.score-slider {
  margin-bottom: 22px;
}

/* 0 位置的刻度默认以中心对齐，左半边会被卡片内边距裁掉；首个刻度改为左对齐。 */
.score-slider :deep(.el-slider__marks-text:first-child) {
  transform: none;
}

.default {
  white-space: nowrap;
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.inline-warn {
  margin-top: 12px;
}

.ratio {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.runtime {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.runtime-row {
  display: flex;
  gap: 10px;
  font-size: 12px;
  align-items: baseline;
}

.runtime-key {
  flex-shrink: 0;
  width: 128px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.runtime-value {
  flex: 1;
  min-width: 0;
  color: var(--el-text-color-primary);
  word-break: break-all;
}

.switches {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
}

.hint-line {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.action-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}

.dirty {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.action-btns {
  display: flex;
  gap: 10px;
}

@media (max-width: 900px) {
  .side,
  .actions {
    margin-bottom: 16px;
  }
}
</style>
