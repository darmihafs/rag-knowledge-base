<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, nextTick } from 'vue'
import { init, verticalGradient, type EChartsType } from '@/utils/echarts'
import {
  Collection,
  DataLine,
  Document,
  Files,
  Refresh,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'

import { fetchDashboardActivity, fetchDashboardComponents, fetchDashboardStats } from '@/api/dashboard'
import type {
  ComponentState,
  ComponentStatus,
  DashboardActivity,
  DashboardHealth,
  DashboardStats,
} from '@/types/api'

/**
 * 首页数据大屏：顶部 4 个核心指标卡，中间左折线（近 7 日提问走势）右饼图（文档格式分布），
 * 第二排左「系统核心组件健康状态」右「最近入库文档与问答动态」。
 * ECharts 实例用 shallowRef 持有，避免被 Vue 深度代理影响内部状态；组件卸载时 dispose 释放。
 *
 * <p>第二排走两个独立只读接口：健康检查含连接池与向量库文件读取，不比首屏统计轻，
 * 拆开后任一路慢都不会拖住整页指标。
 */
const stats = ref<DashboardStats | null>(null)
const health = ref<DashboardHealth | null>(null)
const activity = ref<DashboardActivity | null>(null)
const loading = ref(false)
const secondLoading = ref(false)

const lineEl = ref<HTMLDivElement | null>(null)
const pieEl = ref<HTMLDivElement | null>(null)
const lineChart = shallowRef<EChartsType | null>(null)
const pieChart = shallowRef<EChartsType | null>(null)

async function loadStats() {
  loading.value = true
  try {
    stats.value = await fetchDashboardStats()
    await nextTick()
    renderCharts()
  } catch {
    // 拦截器已弹出错误提示，这里只需保证 loading 收敛
  } finally {
    loading.value = false
  }
}

function renderCharts() {
  if (!stats.value) return
  renderLine(stats.value)
  renderPie(stats.value)
}

/** 第二排：两个只读接口并发拉，任一失败不影响另一侧已有数据 */
async function loadSecondRow() {
  secondLoading.value = true
  try {
    const [components, recent] = await Promise.all([
      fetchDashboardComponents(),
      fetchDashboardActivity(),
    ])
    health.value = components
    activity.value = recent
  } catch {
    // 拦截器已提示
  } finally {
    secondLoading.value = false
  }
}

/** ONLINE 绿 / DEGRADED 橙 / OFFLINE 红 / DISABLED 灰，与后端 stateCn 一一对应 */
const stateTagType: Record<ComponentState, 'success' | 'warning' | 'danger' | 'info'> = {
  ONLINE: 'success',
  DEGRADED: 'warning',
  OFFLINE: 'danger',
  DISABLED: 'info',
}

function tagOf(component: ComponentStatus) {
  return stateTagType[component.state] ?? 'info'
}

function formatTime(value: string | null | undefined) {
  return (value ?? '').replace('T', ' ').slice(5, 16)
}

/** 最近入库文档的状态色，直接用后端回传的 status 原文匹配，statusCn 负责文案 */
const docTagType: Record<string, 'success' | 'warning' | 'info' | 'danger'> = {
  INDEXED: 'success',
  PARSING: 'warning',
  PENDING: 'info',
  FAILED: 'danger',
}

function renderLine(data: DashboardStats) {
  if (!lineEl.value) return
  if (!lineChart.value) {
    lineChart.value = init(lineEl.value)
  }
  const dates = data.trend.map((p) => p.date.slice(5)) // MM-DD，横轴更紧凑
  const counts = data.trend.map((p) => p.count)
  lineChart.value.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 24, top: 30, bottom: 40 },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#c0c4cc' } },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: '#f0f2f5' } },
    },
    series: [
      {
        name: '提问数',
        type: 'line',
        smooth: true,
        data: counts,
        symbolSize: 7,
        lineStyle: { width: 3, color: '#409eff' },
        itemStyle: { color: '#409eff' },
        areaStyle: {
          color: verticalGradient([
            { offset: 0, color: 'rgba(64,158,255,0.35)' },
            { offset: 1, color: 'rgba(64,158,255,0.02)' },
          ]),
        },
      },
    ],
  })
}

function renderPie(data: DashboardStats) {
  if (!pieEl.value) return
  if (!pieChart.value) {
    pieChart.value = init(pieEl.value)
  }
  const items = data.formats.map((f) => ({
    name: (f.type || '未知').toUpperCase(),
    value: f.count,
  }))
  pieChart.value.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} 篇 ({d}%)' },
    legend: { bottom: 0, type: 'scroll' },
    series: [
      {
        name: '文档格式',
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2 },
        label: { formatter: '{b}\n{d}%' },
        data: items.length > 0 ? items : [{ name: '暂无文档', value: 1 }],
      },
    ],
  })
}

function handleResize() {
  lineChart.value?.resize()
  pieChart.value?.resize()
}

onMounted(() => {
  loadStats()
  loadSecondRow()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  lineChart.value?.dispose()
  pieChart.value?.dispose()
  lineChart.value = null
  pieChart.value = null
})

async function refresh() {
  await Promise.all([loadStats(), loadSecondRow()])
  ElMessage.success('数据已刷新')
}
</script>

<template>
  <div class="dashboard">
    <div class="dash-head">
      <div>
        <h2 class="dash-title">数据监控大屏</h2>
        <p class="dash-sub">多知识库协同管理与检索质量概览</p>
      </div>
      <el-button :icon="Refresh" :loading="loading || secondLoading" @click="refresh">刷新数据</el-button>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #ecf5ff">
            <el-icon color="#409eff" :size="24"><Collection /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ stats?.spaceCount ?? 0 }}</div>
            <div class="stat-label">知识库总数</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #f0f9eb">
            <el-icon color="#67c23a" :size="24"><Document /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ stats?.documentCount ?? 0 }}</div>
            <div class="stat-label">文档总数</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #fdf6ec">
            <el-icon color="#e6a23c" :size="24"><Files /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ stats?.chunkCount ?? 0 }}</div>
            <div class="stat-label">切片总数</div>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" style="background: #fef0f0">
            <el-icon color="#f56c6c" :size="24"><DataLine /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ stats?.todayQuestions ?? 0 }}</div>
            <div class="stat-label">今日提问数</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :md="14">
        <el-card shadow="hover" class="chart-card" v-loading="loading">
          <template #header>
            <span class="chart-title">近 7 日提问走势</span>
          </template>
          <div ref="lineEl" class="chart-box"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="10">
        <el-card shadow="hover" class="chart-card" v-loading="loading">
          <template #header>
            <span class="chart-title">文档格式分布</span>
          </template>
          <div ref="pieEl" class="chart-box"></div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="second-row">
      <el-col :xs="24" :md="14">
        <el-card shadow="hover" class="chart-card" v-loading="secondLoading">
          <template #header>
            <div class="card-head">
              <span class="chart-title">系统核心组件健康状态</span>
              <span class="head-meta">
                <el-tag
                  :type="health && health.online === health.total ? 'success' : 'warning'"
                  size="small"
                  effect="light"
                >
                  {{ health?.online ?? 0 }} / {{ health?.total ?? 0 }} 在线
                </el-tag>
                <span class="checked-at">体检时间 {{ health ? formatTime(health.checkedAt) : '—' }}</span>
              </span>
            </div>
          </template>

          <div class="comp-list">
            <div v-for="comp in health?.components ?? []" :key="comp.key" class="comp-item">
              <div class="comp-head">
                <span class="comp-dot" :class="`comp-dot--${comp.state.toLowerCase()}`"></span>
                <span class="comp-name">{{ comp.name }}</span>
                <el-tag :type="tagOf(comp)" size="small" effect="plain">{{ comp.stateCn }}</el-tag>
                <span class="comp-endpoint" :title="comp.endpoint ?? ''">{{ comp.endpoint || '本地装配' }}</span>
              </div>
              <div class="comp-fields">
                <span v-for="field in comp.fields" :key="field.label" class="comp-field">
                  <em>{{ field.label }}</em>{{ field.value }}
                </span>
              </div>
              <div v-if="comp.note" class="comp-note">{{ comp.note }}</div>
            </div>
            <el-empty
              v-if="!secondLoading && !health"
              description="健康状态加载失败，点右上角刷新重试"
              :image-size="60"
            />
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="10">
        <el-card shadow="hover" class="chart-card" v-loading="secondLoading">
          <template #header>
            <div class="card-head">
              <span class="chart-title">最近入库文档与问答动态</span>
              <span class="head-meta">实时快照，随刷新更新</span>
            </div>
          </template>

          <el-tabs class="activity-tabs">
            <el-tab-pane label="最近入库文档">
              <div class="feed">
                <div v-for="doc in activity?.recentDocuments ?? []" :key="doc.id" class="feed-item">
                  <div class="feed-main">
                    <span class="feed-title" :title="doc.title">{{ doc.title }}</span>
                    <el-tag size="small" :type="docTagType[doc.status] ?? 'info'" effect="plain">
                      {{ doc.statusCn }}
                    </el-tag>
                  </div>
                  <div class="feed-meta">
                    {{ doc.spaceName || '默认知识库' }} · {{ (doc.fileType || '?').toUpperCase() }} ·
                    {{ doc.chunkCount }} 切片
                    <span class="feed-time">{{ formatTime(doc.createTime) }}</span>
                  </div>
                </div>
                <el-empty
                  v-if="!secondLoading && (activity?.recentDocuments.length ?? 0) === 0"
                  description="还没有入库文档"
                  :image-size="52"
                />
              </div>
            </el-tab-pane>
            <el-tab-pane label="最近问答动态">
              <div class="feed">
                <div v-for="q in activity?.recentQuestions ?? []" :key="q.messageId" class="feed-item">
                  <div class="feed-main">
                    <span class="feed-title" :title="q.question">{{ q.question }}</span>
                    <el-tag size="small" type="info" effect="plain">引用 {{ q.citationCount ?? 0 }}</el-tag>
                  </div>
                  <div class="feed-meta">
                    {{ q.nickname || q.username }} · {{ q.username }}
                    <span class="feed-time">{{ formatTime(q.createTime) }}</span>
                  </div>
                </div>
                <el-empty
                  v-if="!secondLoading && (activity?.recentQuestions.length ?? 0) === 0"
                  description="还没有问答记录"
                  :image-size="52"
                />
              </div>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.dashboard {
  max-width: 1280px;
  margin: 0 auto;
}

.dash-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.dash-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}

.dash-sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.stat-row {
  margin-bottom: 4px;
}

.stat-card {
  margin-bottom: 16px;
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.chart-row {
  margin-top: 4px;
}

.chart-card {
  margin-bottom: 16px;
}

.chart-title {
  font-size: 15px;
  font-weight: 600;
}

.chart-box {
  width: 100%;
  height: 320px;
}

.second-row {
  margin-top: 4px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.head-meta {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.checked-at {
  font-variant-numeric: tabular-nums;
}

/* ---------------- 组件健康 ---------------- */

.comp-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 320px;
}

.comp-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
}

.comp-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.comp-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--el-color-info);
}

.comp-dot--online {
  background: var(--el-color-success);
}

.comp-dot--degraded {
  background: var(--el-color-warning);
}

.comp-dot--offline {
  background: var(--el-color-danger);
}

.comp-dot--disabled {
  background: var(--el-color-info);
}

.comp-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.comp-endpoint {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, Menlo, Consolas, monospace;
  max-width: 46%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.comp-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.comp-field em {
  font-style: normal;
  color: var(--el-text-color-secondary);
  margin-right: 5px;
}

.comp-note {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

/* ---------------- 最近动态 ---------------- */

.activity-tabs :deep(.el-tabs__header) {
  margin-bottom: 8px;
}

.feed {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 300px;
  max-height: 320px;
  overflow-y: auto;
  padding-right: 4px;
}

.feed-item {
  border-bottom: 1px dashed var(--el-border-color-lighter);
  padding-bottom: 8px;
}

.feed-item:last-child {
  border-bottom: none;
}

.feed-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.feed-title {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.feed-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.feed-time {
  float: right;
  font-variant-numeric: tabular-nums;
}
</style>
