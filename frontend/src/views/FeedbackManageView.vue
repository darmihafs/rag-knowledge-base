<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  CircleClose,
  Refresh,
  Search,
  Select,
  Tickets,
  TrendCharts,
} from '@element-plus/icons-vue'

import { fetchFeedbackStats, fetchFeedbackTickets, resolveFeedback } from '@/api/feedback'
import { FEEDBACK_ISSUE_TYPES } from '@/types/api'
import type { FeedbackIssueTypeCount, FeedbackStatsVO, FeedbackTicketVO, PageResult } from '@/types/api'

/**
 * 问答质量反馈与 BadCase 纠偏工单。
 *
 * <p>闭环是这样的：问答页对某条回答点「需改进」→ 后端生成一张待处理工单（带 messageId / sessionId /
 * userId）→ 这里按问题类型和状态筛出 BadCase，展开看当时的提问与回答，判定后写处理备注并结单。
 *
 * <p>四张指标卡的数据全部来自 {@code /api/admin/feedback/stats}，一次请求拿全，不为卡片单独加接口；
 * 好评率与问题类型占比由后端算好（占比只在点踩样本内统计），前端只做百分比格式化。
 */

const stats = ref<FeedbackStatsVO | null>(null)
const statsLoading = ref(false)

const list = ref<PageResult<FeedbackTicketVO>>({
  records: [],
  total: 0,
  size: 10,
  current: 1,
  pages: 0,
})
const loading = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  rating: '' as number | '',
  status: '' as number | '',
  issueType: '',
})

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await fetchFeedbackStats()
  } catch {
    /* 拦截器已提示 */
  } finally {
    statsLoading.value = false
  }
}

async function load() {
  loading.value = true
  try {
    list.value = await fetchFeedbackTickets({ ...query })
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  void load()
}

function resetQuery() {
  query.rating = ''
  query.status = ''
  query.issueType = ''
  search()
}

async function refresh() {
  await Promise.allSettled([load(), loadStats()])
}

onMounted(refresh)

// ------------------------------------------------------------------ 指标卡

/** 闭环率 = 已修复 / 总反馈，纯派生自已加载的 stats，不为卡片另发请求 */
const closeRate = computed(() => {
  const current = stats.value
  if (!current || !current.totalFeedback) {
    return '0%'
  }
  return `${((current.fixedTickets * 100) / current.totalFeedback).toFixed(1)}%`
})

const cards = computed(() => [
  {
    label: '总评价数',
    value: stats.value ? `${stats.value.totalFeedback}` : '0',
    sub: stats.value ? `赞 ${stats.value.thumbsUp} · 踩 ${stats.value.thumbsDown}` : '暂无评价',
    icon: Tickets,
  },
  {
    label: '回答好评率',
    value: stats.value ? `${(stats.value.positiveRate * 100).toFixed(1)}%` : '—',
    sub: stats.value?.totalFeedback ? `基于 ${stats.value.totalFeedback} 条评价` : '样本不足',
    icon: TrendCharts,
  },
  {
    label: '待处理 BadCase',
    value: stats.value ? `${stats.value.pendingBadCase}` : '0',
    sub: stats.value ? `已忽略 ${stats.value.ignoredTickets} 单` : '需要人工判定',
    icon: CircleClose,
  },
  {
    label: '已修复工单',
    value: stats.value ? `${stats.value.fixedTickets}` : '0',
    sub: stats.value ? `闭环率 ${closeRate.value}` : '结单后自动累计',
    icon: Select,
  },
])

/** 问题类型占比条：后端只在点踩样本内算 ratio，这里按最大 count 归一化条宽 */
const issueBars = computed(() => {
  const items: FeedbackIssueTypeCount[] = stats.value?.issueTypes ?? []
  const max = items.reduce((acc, item) => Math.max(acc, item.count), 0)
  return items.map((item) => ({
    ...item,
    percent: (item.ratio * 100).toFixed(1),
    width: max > 0 ? `${Math.max(6, (item.count / max) * 100)}%` : '6%',
  }))
})

// ------------------------------------------------------------------ 工单表格

const statusMeta: Record<number, { text: string; type: 'warning' | 'success' | 'info' }> = {
  0: { text: '待处理', type: 'warning' },
  1: { text: '已修复', type: 'success' },
  2: { text: '已忽略', type: 'info' },
}

function statusOf(row: FeedbackTicketVO) {
  return statusMeta[row.status] ?? { text: `状态 ${row.status}`, type: 'info' as const }
}

function formatTime(value: string | null) {
  return (value ?? '').replace('T', ' ').slice(0, 19)
}

// ------------------------------------------------------------------ 处理纠偏弹窗

const dialog = reactive({
  visible: false,
  submitting: false,
  ticket: null as FeedbackTicketVO | null,
  status: 1,
  adminNote: '',
})

function openResolve(row: FeedbackTicketVO) {
  dialog.ticket = row
  // 已结单的再次打开时沿用原结论，待处理的默认给「已修复」
  dialog.status = row.status === 0 ? 1 : row.status
  dialog.adminNote = row.adminNote ?? ''
  dialog.visible = true
}

async function submitResolve() {
  const ticket = dialog.ticket
  if (!ticket) {
    return
  }
  const note = dialog.adminNote.trim()
  // 后端同样校验：非「待处理」的结论必须留下备注，否则一键结单就成了甩锅
  if (dialog.status !== 0 && !note) {
    ElMessage.warning('请填写处理备注，说明这单是怎么处理的')
    return
  }
  dialog.submitting = true
  try {
    await resolveFeedback(ticket.id, { status: dialog.status, adminNote: note || undefined })
    ElMessage.success(dialog.status === 0 ? '已退回待处理' : '工单已结单')
    dialog.visible = false
    await refresh()
  } catch {
    /* 拦截器已提示 */
  } finally {
    dialog.submitting = false
  }
}
</script>

<template>
  <div class="feedback">
    <el-row :gutter="16" class="stat-row" v-loading="statsLoading">
      <el-col v-for="card in cards" :key="card.label" :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-icon">
            <el-icon :size="22"><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-body">
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
            <div class="stat-sub">{{ card.sub }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>BadCase 纠偏工单</span>
          <span class="hint">共 {{ list.total }} 单 · GET /api/admin/feedback/page</span>
          <el-button size="small" :icon="Refresh" @click="refresh">刷新</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-select v-model="query.rating" placeholder="全部评价" clearable style="width: 130px" @change="search">
          <el-option label="点赞" :value="1" />
          <el-option label="点踩" :value="-1" />
        </el-select>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px" @change="search">
          <el-option label="待处理" :value="0" />
          <el-option label="已修复" :value="1" />
          <el-option label="已忽略" :value="2" />
        </el-select>
        <el-select v-model="query.issueType" placeholder="全部问题类型" clearable style="width: 160px" @change="search">
          <el-option v-for="type in FEEDBACK_ISSUE_TYPES" :key="type" :label="type" :value="type" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>
        <div class="spacer" />
        <span class="tip">点踩默认按「其他」兜底入单，问题类型可在详情里核对</span>
      </div>

      <el-table :data="list.records" v-loading="loading" stripe row-key="id" empty-text="还没有反馈工单">
        <el-table-column prop="id" label="工单" width="70" />
        <el-table-column label="评价" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.rating === 1 ? 'success' : 'danger'" effect="plain" size="small">
              {{ row.rating === 1 ? '有帮助' : '需改进' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="问题类型" width="110">
          <template #default="{ row }">
            <span v-if="row.issueType">{{ row.issueType }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="提交人" width="140">
          <template #default="{ row }">
            <div class="who">
              <span class="username">{{ row.username }}</span>
              <el-tag v-if="row.userId === null" size="small" type="info" effect="plain">已注销</el-tag>
            </div>
            <div class="sub-line">{{ row.nickname || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="question" label="当时的提问" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.question || '（该会话记录已被清理）' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="comment" label="用户反馈" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.comment || '未填写补充说明' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusOf(row).type" size="small" effect="light">{{ statusOf(row).text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="adminNote" label="处理备注" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.adminNote || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openResolve(row)">处理纠偏</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="list.total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="load"
        @size-change="search"
      />
    </el-card>

    <el-card shadow="never" class="block" v-loading="statsLoading">
      <template #header>
        <div class="card-header">
          <span>点踩原因分布</span>
          <span class="hint">占比在点踩样本内计算 · /api/admin/feedback/stats</span>
        </div>
      </template>
      <div v-if="issueBars.length" class="issue-list">
        <div v-for="item in issueBars" :key="item.issueType" class="issue-row">
          <span class="issue-name">{{ item.issueType }}</span>
          <div class="issue-track">
            <div class="issue-bar" :style="{ width: item.width }"></div>
          </div>
          <span class="issue-count">{{ item.count }} 单 · {{ item.percent }}%</span>
        </div>
      </div>
      <el-empty v-else description="还没有点踩工单，暂时看不出问题分布" :image-size="60" />
    </el-card>

    <!-- 处理弹窗：原问答并排看，判完写结论 -->
    <el-dialog v-model="dialog.visible" title="工单处理" width="860px" top="6vh">
      <div v-if="dialog.ticket" class="resolve">
        <div class="resolve-head">
          <el-tag :type="dialog.ticket.rating === 1 ? 'success' : 'danger'" effect="plain" size="small">
            {{ dialog.ticket.rating === 1 ? '有帮助' : '需改进' }}
          </el-tag>
          <el-tag v-if="dialog.ticket.issueType" size="small" effect="plain">
            {{ dialog.ticket.issueType }}
          </el-tag>
          <span class="resolve-user">{{ dialog.ticket.username }} · 消息 #{{ dialog.ticket.messageId }}</span>
        </div>

        <div class="qa-grid">
          <div class="qa-col">
            <div class="qa-label">原始提问</div>
            <pre class="qa-body">{{ dialog.ticket.question || '（该会话记录已被清理）' }}</pre>
          </div>
          <div class="qa-col">
            <div class="qa-label">AI 回答</div>
            <pre class="qa-body">{{ dialog.ticket.answer || '（该回答未落库或已被清理）' }}</pre>
          </div>
        </div>

        <div class="qa-label">用户补充说明</div>
        <pre class="qa-body comment-body">{{ dialog.ticket.comment || '未填写' }}</pre>

        <el-form label-width="88px" class="resolve-form">
          <el-form-item label="处理结论">
            <el-radio-group v-model="dialog.status">
              <el-radio :value="1">已修复</el-radio>
              <el-radio :value="2">已忽略</el-radio>
              <el-radio :value="0">退回待处理</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="处理备注">
            <el-input
              v-model="dialog.adminNote"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="写清怎么纠偏的：禁用哪个切片、补传哪份资料，或是判定为无效反馈的原因"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submitResolve">保存结论</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.feedback {
  max-width: 1360px;
  margin: 0 auto;
}

.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  border-radius: 10px;
}

.stat-card :deep(.el-card__body) {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
}

.stat-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.stat-body {
  min-width: 0;
}

.stat-value {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.stat-sub {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-top: 2px;
}

.block {
  border-radius: 10px;
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
}

.card-header .hint {
  flex: 1;
}

.hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.spacer {
  flex: 1;
}

.tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.who {
  display: flex;
  align-items: center;
  gap: 6px;
}

.username {
  font-weight: 500;
}

.sub-line {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.muted {
  color: var(--el-text-color-placeholder);
}

.issue-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.issue-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.issue-name {
  width: 88px;
  flex-shrink: 0;
  font-size: 13px;
}

.issue-track {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  overflow: hidden;
}

.issue-bar {
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary-light-3);
}

.issue-count {
  width: 130px;
  flex-shrink: 0;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.resolve-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.resolve-user {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.qa-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 12px;
}

.qa-label {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}

.qa-body {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 10px 12px;
  max-height: 220px;
  overflow-y: auto;
}

.comment-body {
  max-height: 140px;
}

.resolve-form {
  margin-top: 14px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

@media (max-width: 900px) {
  .qa-grid {
    grid-template-columns: 1fr;
  }
}
</style>
