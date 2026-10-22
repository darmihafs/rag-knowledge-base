<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Refresh, Search } from '@element-plus/icons-vue'

import { fetchAuditDetail, fetchAuditMessages } from '@/api/admin'
import { downloadCsv, formatForCsv } from '@/utils/csv'
import type { AuditDetailVO, AuditMessageVO, PageResult } from '@/types/api'

/**
 * 问答审计日志：全站「一问一答」的留痕。
 *
 * <p>列表行由后端把 chat_message / chat_session / sys_user 三张表关联好，前端只负责展示；
 * 点开某一行才按 messageId 拉详情（完整回答 + 引用切片），避免一次把全站回答都塞进列表响应。
 */

const list = ref<PageResult<AuditMessageVO>>({ records: [], total: 0, size: 10, current: 1, pages: 0 })
const loading = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  username: '',
})

async function load() {
  loading.value = true
  try {
    list.value = await fetchAuditMessages({ ...query })
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

function resetQuery() {
  query.keyword = ''
  query.username = ''
  search()
}

onMounted(load)

// ------------------------------------------------------------------ 行展开详情

/** 已加载过的详情缓存：同一行反复展开不必再请求 */
const details = reactive<Record<number, AuditDetailVO>>({})
const detailLoading = reactive<Record<number, boolean>>({})

async function onExpand(row: AuditMessageVO, expandedRows: AuditMessageVO[]) {
  const isOpen = expandedRows.some((r) => r.messageId === row.messageId)
  if (!isOpen || details[row.messageId] || detailLoading[row.messageId]) return
  detailLoading[row.messageId] = true
  try {
    details[row.messageId] = await fetchAuditDetail(row.messageId)
  } catch {
    ElMessage.error('该记录详情加载失败')
  } finally {
    detailLoading[row.messageId] = false
  }
}

function formatTime(value: string) {
  return (value ?? '').replace('T', ' ').slice(0, 19)
}

// ------------------------------------------------------------------ 报表导出

const CSV_HEADERS = ['消息ID', '会话ID', '会话标题', '用户名', '昵称', '用户问题', 'AI 回答摘要', '引用数', '提问时间']

/**
 * 导出当前筛选下的这一页。
 *
 * <p>不做「导出全部」：全站问答可能是几万条，一次拉完会把浏览器和后端一起拖垮；
 * 需要全量时报错成本更高的做法是后端加异步任务，目前按页导足够核对。
 * 文件带 UTF-8 BOM，Excel 打开中文不乱码；正文里的逗号引号由 csv 工具统一转义。
 */
function exportCsv() {
  const rows = list.value.records
  if (!rows.length) {
    ElMessage.warning('当前页没有可导出的问答记录')
    return
  }
  downloadCsv(
    `问答审计_第${query.pageNum}页`,
    CSV_HEADERS,
    rows.map((row) => [
      row.messageId,
      row.sessionId,
      row.sessionTitle,
      row.username,
      row.nickname ?? '',
      row.question,
      row.answerSummary ?? '',
      row.citationCount,
      formatForCsv(row.askTime),
    ]),
  )
  ElMessage.success(`已导出当前页 ${rows.length} 条问答记录`)
}
</script>

<template>
  <div class="audit">
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>问答审计日志</span>
          <span class="hint">共 {{ list.total }} 条提问 · GET /api/admin/audit/messages</span>
        </div>
      </template>

      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="按提问内容检索"
          :prefix-icon="Search"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-input
          v-model="query.username"
          placeholder="按用户名精确检索"
          clearable
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>
        <div class="spacer" />
        <span class="tip">点击行首箭头展开完整问答与引用来源</span>
        <el-button :icon="Download" :disabled="!list.records.length" @click="exportCsv">
          导出审计报表 (CSV)
        </el-button>
      </div>

      <el-table
        :data="list.records"
        v-loading="loading"
        stripe
        row-key="messageId"
        empty-text="没有匹配的问答记录"
        @expand-change="onExpand"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="detail" v-loading="detailLoading[row.messageId]">
              <template v-if="details[row.messageId]">
                <div class="detail-block">
                  <div class="detail-label">完整提问</div>
                  <pre class="detail-text">{{ details[row.messageId].question }}</pre>
                </div>
                <div class="detail-block">
                  <div class="detail-label">完整回答</div>
                  <pre class="detail-text">{{ details[row.messageId].answer || '（该回答未落库或已被清理）' }}</pre>
                </div>
                <div class="detail-block">
                  <div class="detail-label">
                    引用来源
                    <span class="detail-count">{{ details[row.messageId].citations.length }} 个切片</span>
                  </div>
                  <div v-if="details[row.messageId].citations.length" class="cite-list">
                    <div v-for="(c, i) in details[row.messageId].citations" :key="i" class="cite-item">
                      <div class="cite-head">
                        <el-tag size="small" effect="plain">[{{ i + 1 }}]</el-tag>
                        <span class="cite-title">{{ c.title }}</span>
                        <span class="cite-meta">
                          第 {{ c.chunkIndex }} 段
                          <template v-if="c.pageNumber"> · P{{ c.pageNumber }}</template>
                          <template v-if="c.score !== null && c.score !== undefined">
                            · 相似度 {{ (c.score * 100).toFixed(1) }}%
                          </template>
                        </span>
                      </div>
                      <div class="cite-snippet">{{ c.snippet }}</div>
                    </div>
                  </div>
                  <el-empty v-else description="本次回答没有引用任何切片" :image-size="52" />
                </div>
              </template>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="messageId" label="消息ID" width="90" />
        <el-table-column label="提问用户" width="150">
          <template #default="{ row }">
            <div class="who">
              <span class="username">{{ row.username }}</span>
              <el-tag
                v-if="row.userId === null"
                size="small"
                type="info"
                effect="plain"
                title="账号已注销，历史提问保留"
              >
                已注销
              </el-tag>
            </div>
            <div class="nickname">{{ row.nickname || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="sessionTitle" label="会话标题" min-width="150" show-overflow-tooltip />
        <el-table-column prop="question" label="用户问题" min-width="240" show-overflow-tooltip />
        <el-table-column prop="answerSummary" label="AI 回答摘要" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="summary">{{ row.answerSummary || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="引用数" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.citationCount > 0 ? 'success' : 'info'" effect="plain">
              {{ row.citationCount }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提问时间" width="170">
          <template #default="{ row }">{{ formatTime(row.askTime) }}</template>
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
  </div>
</template>

<style scoped>
.audit {
  max-width: 1280px;
  margin: 0 auto;
}

.block {
  border-radius: 10px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
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
  color: var(--el-text-color-primary);
}

.nickname {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.summary {
  color: var(--el-text-color-regular);
}

.detail {
  padding: 8px 20px 16px;
  min-height: 80px;
}

.detail-block + .detail-block {
  margin-top: 14px;
}

.detail-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin-bottom: 6px;
}

.detail-count {
  font-weight: 400;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.detail-text {
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
  max-height: 260px;
  overflow-y: auto;
}

.cite-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.cite-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 8px 10px;
}

.cite-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.cite-title {
  font-size: 13px;
  font-weight: 500;
}

.cite-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cite-snippet {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
