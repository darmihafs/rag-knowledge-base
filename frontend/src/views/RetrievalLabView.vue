<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { DataAnalysis, Refresh, Search } from '@element-plus/icons-vue'

import { compareRetrieval } from '@/api/admin'
import { fetchAllSpaces } from '@/api/space'
import type { RetrieveCompareResult, RetrievalRoute, SpaceVO } from '@/types/api'

/**
 * 检索对比实验室：同一个问题并排跑「纯词法 / 纯语义 / RRF 混合」三条召回路。
 *
 * <p>这里没有任何模拟数据——三路结果由后端用线上问答同一段召回代码跑出来，
 * 因此卡片里看到的排序差异，就是真实回答里进入 Prompt 的切片顺序。
 *
 * <p>检索范围可选空间：spaceId 会被后端下推到三路召回的 SQL 里（不是召回完再筛），
 * 所以选定空间后 topK 是在该空间内部截断的，跨空间对比时这一栏要一起看。
 */
const form = reactive<{ question: string; topK: number; spaceId: number }>({
  question: '',
  topK: 5,
  // 0 = 全部空间，与后端 normalizeSpace 的口径一致（非正数视为不限制）
  spaceId: 0,
})
const loading = ref(false)
const result = ref<RetrieveCompareResult | null>(null)
const spaces = ref<SpaceVO[]>([])

onMounted(async () => {
  try {
    spaces.value = await fetchAllSpaces()
  } catch {
    /* 拦截器已提示；空间列表拿不到时仍可按全库跑对比 */
  }
})

/**
 * 三个预设问题对着仓库里现有的两篇示例文档而写：
 * 第一题正文里有「RRF 倒数排名融合」的原词，词法必中；
 * 第二题只说「限制上下文长度」，语料里写的是「携带最近 6 条历史消息」，字面几乎不重叠，考语义；
 * 第三题跨章节，答案散在多个切片里，考两路叠加后的覆盖度。
 */
const presets = [
  {
    label: '字面精确题',
    tag: '考词法',
    type: 'primary' as const,
    question: 'RRF 倒数排名融合的常数 k 取多少？',
    hint: '问题里有语料原词，词法路应当直接命中',
  },
  {
    label: '同义改写题',
    tag: '考语义',
    type: 'warning' as const,
    question: '系统是怎么限制单次对话上下文长度的？',
    hint: '语料写的是「携带最近 6 条历史消息」，与提问字面几乎不重叠',
  },
  {
    label: '跨章节综合题',
    tag: '考覆盖度',
    type: 'success' as const,
    question: '文档从上传到能被检索，中间经历了哪些处理？',
    hint: '答案散在分块、向量化、融合三节，需要两路互补才铺得开',
  },
]

function fillPreset(question: string) {
  form.question = question
}

async function run() {
  const question = form.question.trim()
  if (!question) {
    ElMessage.warning('先输入要对比的问题，或点上面任意一个预设')
    return
  }
  loading.value = true
  try {
    result.value = await compareRetrieval({ question, topK: form.topK, spaceId: form.spaceId || undefined })
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/** 三路里最好走的耗时，用来给每列打「相对快 / 相对慢」的标签 */
const fastestMillis = computed(() => {
  const values = (result.value?.routes ?? [])
    .filter((r) => r.available)
    .map((r) => r.elapsedMillis)
  return values.length ? Math.min(...values) : 0
})

function speedTag(route: RetrievalRoute) {
  if (!route.available) {
    return { text: '不可用', type: 'info' as const }
  }
  if (route.elapsedMillis <= fastestMillis.value) {
    return { text: '本轮最快', type: 'success' as const }
  }
  const ratio = fastestMillis.value > 0 ? route.elapsedMillis / fastestMillis.value : 1
  return { text: `慢 ${ratio.toFixed(1)}×`, type: ratio >= 3 ? 'danger' as const : 'warning' as const }
}

/**
 * 混合路相对最优单路的补位量：同一个 chunkId 只出现在另一路里、却被融合抬进名额，
 * 就是 RRF 的实际收益。纯前端从已加载的三路结果派生，不额外发请求。
 */
const coverage = computed(() => {
  const routes = result.value?.routes ?? []
  const idsOf = (strategy: string) =>
    new Set((routes.find((r) => r.strategy === strategy)?.items ?? []).map((i) => i.chunkId))
  const lexical = idsOf('LEXICAL')
  const semantic = idsOf('SEMANTIC')
  const hybrid = idsOf('HYBRID')
  const onlyHybrid = [...hybrid].filter((id) => !lexical.has(id) && !semantic.has(id))
  return {
    lexical: lexical.size,
    semantic: semantic.size,
    hybrid: hybrid.size,
    /** 混合独有：两路都给不出、只有融合后排进名额的切片 */
    onlyHybrid: onlyHybrid.length,
    /** 只有一路命中、另一路漏掉，但被融合保留下来的切片数 */
    rescued: [...hybrid].filter((id) => (lexical.has(id) !== semantic.has(id))).length,
  }
})

function rankClass(rank: number) {
  return rank <= 3 ? `rank rank--top` : 'rank'
}
</script>

<template>
  <div class="lab">
    <div class="lab-head">
      <div>
        <h2 class="lab-title">检索对比实验室</h2>
        <p class="lab-sub">同一问题并排跑三条召回路，直观看出 RRF 混合检索补在了哪里</p>
      </div>
      <el-tag v-if="result" type="info" effect="plain">
        RRF 常数 k = {{ result.engine.rrfK }} · 向量模型 {{ result.engine.embeddingModel }}
      </el-tag>
      <el-tag v-if="result" :type="result.spaceId ? 'success' : 'info'" effect="plain">
        {{ result.engine.scopeStrategy }}
      </el-tag>
    </div>

    <el-card shadow="never" class="panel">
      <el-form label-position="top" @submit.prevent="run">
        <el-form-item label="待对比的问题">
          <el-input
            v-model="form.question"
            type="textarea"
            :rows="2"
            maxlength="300"
            show-word-limit
            placeholder="例如：RRF 倒数排名融合的常数 k 取多少？"
          />
        </el-form-item>

        <div class="preset-row">
          <el-button
            v-for="preset in presets"
            :key="preset.label"
            :type="preset.type"
            plain
            size="small"
            @click="fillPreset(preset.question)"
          >
            {{ preset.label }}
          </el-button>
          <span v-if="presets.length" class="preset-tip">
            预设针对当前库内的《知识库问答系统设计说明书》《网络工程复习示例》设计
          </span>
        </div>

        <div class="run-row">
          <div class="run-controls">
            <span class="ctrl-label">召回名额 topK</span>
            <el-input-number v-model="form.topK" :min="1" :max="10" size="small" />
            <span class="ctrl-hint">与线上问答共用同一套截断规则，最大 10</span>
            <span class="ctrl-label">检索范围</span>
            <el-select v-model="form.spaceId" size="small" class="scope-select">
              <el-option label="全部空间" :value="0" />
              <el-option v-for="space in spaces" :key="space.id" :label="space.name" :value="space.id" />
            </el-select>
            <span class="ctrl-hint">空间过滤下推到三路召回的 SQL，topK 在该空间内部截断</span>
          </div>
          <div class="run-actions">
            <el-button :icon="Refresh" :disabled="!result" @click="form.question = ''; form.spaceId = 0; result = null">
              清空
            </el-button>
            <el-button type="primary" :icon="Search" :loading="loading" @click="run">运行三路对比</el-button>
          </div>
        </div>
      </el-form>
    </el-card>

    <el-alert
      v-if="result && !result.engine.vectorAvailable"
      type="warning"
      show-icon
      :closable="false"
      class="vector-alert"
      title="向量引擎当前不可用，语义路与混合路会自动退化"
      description="rag.embedding-enabled=false 或向量模型未就绪时，语义路返回空、RRF 融合只剩词法一路。要看三路真实差异，请在后端开启向量化并配好密钥。"
    />

    <template v-if="result">
      <el-card shadow="never" class="summary">
        <div class="summary-item">
          <div class="summary-value">{{ coverage.lexical }}</div>
          <div class="summary-label">词法路命中切片</div>
        </div>
        <el-divider direction="vertical" />
        <div class="summary-item">
          <div class="summary-value">{{ coverage.semantic }}</div>
          <div class="summary-label">语义路命中切片</div>
        </div>
        <el-divider direction="vertical" />
        <div class="summary-item">
          <div class="summary-value">{{ coverage.hybrid }}</div>
          <div class="summary-label">混合路最终切片</div>
        </div>
        <el-divider direction="vertical" />
        <div class="summary-item">
          <div class="summary-value summary-value--accent">{{ coverage.rescued }}</div>
          <div class="summary-label">融合保住的单路独有命中</div>
        </div>
        <div class="summary-note">
          「单路独有命中」指只有一路召回、另一路漏掉，但最终仍被送进 Prompt 的切片数——
          这正是混合检索相对任何单一路的收益来源。
        </div>
      </el-card>

      <el-row :gutter="16" class="route-row">
        <el-col v-for="route in result.routes" :key="route.strategy" :xs="24" :md="8">
          <el-card shadow="hover" class="route-card">
            <template #header>
              <div class="route-head">
                <div class="route-title">
                  <el-icon class="route-icon"><DataAnalysis /></el-icon>
                  <span>{{ route.strategyCn }}</span>
                </div>
                <el-tag :type="speedTag(route).type" size="small" effect="dark">
                  {{ route.elapsedMillis }} ms · {{ speedTag(route).text }}
                </el-tag>
              </div>
              <p class="route-desc">{{ route.description }}</p>
              <el-alert v-if="route.note" :type="route.available ? 'info' : 'warning'" :closable="false" class="route-note">
                {{ route.note }}
              </el-alert>
            </template>

            <div v-if="!route.items.length" class="empty">本轮该路没有召回任何切片</div>

            <div v-for="item in route.items" :key="item.chunkId" class="hit">
              <div :class="[rankClass(item.rank), route.strategy]">{{ item.rank }}</div>
              <div class="hit-body">
                <div class="hit-meta">
                  <span class="hit-doc">{{ item.documentTitle ?? `文档 #${item.documentId}` }}</span>
                  <el-tag size="small" effect="plain">第 {{ item.chunkIndex }} 段</el-tag>
                  <el-tag v-if="item.pageNumber" size="small" type="warning" effect="plain">
                    P{{ item.pageNumber }}
                  </el-tag>
                  <span class="hit-chars">{{ item.charCount ?? 0 }} 字</span>
                </div>
                <p class="hit-snippet">{{ item.snippet }}</p>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </template>

    <el-empty v-else description="输入问题或点一个预设，跑一次三路对比" />
  </div>
</template>

<style scoped>
.lab {
  max-width: 1400px;
  margin: 0 auto;
}

.lab-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.lab-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}

.lab-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.panel {
  margin-bottom: 16px;
}

.preset-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin: -6px 0 14px;
}

.preset-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.run-row {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.run-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ctrl-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.ctrl-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.scope-select {
  width: 150px;
}

.run-actions {
  display: flex;
  gap: 8px;
}

.vector-alert {
  margin-bottom: 16px;
}

.summary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-bottom: 16px;
}

.summary :deep(.el-card__body) {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 18px;
  padding: 16px 20px;
  width: 100%;
}

.summary-item {
  text-align: center;
}

.summary-value {
  font-size: 22px;
  font-weight: 700;
}

.summary-value--accent {
  color: var(--el-color-primary);
}

.summary-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.summary-note {
  flex: 1;
  min-width: 260px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.route-card {
  margin-bottom: 16px;
  height: 100%;
}

.route-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.route-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
}

.route-icon {
  color: var(--el-color-primary);
}

.route-desc {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.route-note {
  margin-top: 8px;
}

.route-note :deep(.el-alert__title) {
  font-size: 12px;
  line-height: 1.5;
}

.empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}

.hit {
  display: flex;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.hit:last-child {
  border-bottom: none;
}

.rank {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  line-height: 24px;
  text-align: center;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 700;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
}

/* 前 3 名按路别上色，横向对比同一名次时一眼能看出排名变化 */
.rank--top {
  color: var(--el-color-white);
}

.rank--top.LEXICAL {
  background: var(--el-color-info);
}

.rank--top.SEMANTIC {
  background: var(--el-color-warning);
}

.rank--top.HYBRID {
  background: var(--el-color-primary);
}

.hit-body {
  min-width: 0;
  flex: 1;
}

.hit-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 4px;
}

.hit-doc {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.hit-chars {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.hit-snippet {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  word-break: break-all;
}
</style>
