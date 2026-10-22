<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Collection,
  Document as DocIcon,
  Download,
  Edit,
  Files,
  Refresh,
  Search,
  View,
} from '@element-plus/icons-vue'

import { batchChunkStatus, fetchCorpusChunks, fetchCorpusStats, toggleChunkStatus, updateChunk } from '@/api/chunk'
import { fetchDocuments } from '@/api/kb'
import { fetchAllSpaces } from '@/api/space'
import { downloadCsv, formatForCsv } from '@/utils/csv'
import type { ChunkCorpusStats, CorpusChunkVO, DocumentVO, PageResult, SpaceVO } from '@/types/api'

/**
 * 全局切片语料中心：跨文档看语料、改语料、启停语料。
 *
 * <p>与知识库管理页的「切片抽屉」区别在于视角：那里是「一篇文档下的分段」，这里是「全库切片」，
 * 因此支持按知识库 / 文档 / 状态 / 正文四条件联合过滤，并提供批量启停。
 *
 * <p>顶部统计条走后端 {@code /api/chunks/corpus-stats} 一次取回，口径是全库而非当前筛选结果；
 * 批量操作成功后重新拉一次，数字与列表才不会各说各话。
 */

const list = ref<PageResult<CorpusChunkVO>>({
  records: [],
  total: 0,
  size: 20,
  current: 1,
  pages: 0,
})
const loading = ref(false)
const stats = ref<ChunkCorpusStats | null>(null)

const spaces = ref<SpaceVO[]>([])
const documents = ref<DocumentVO[]>([])
const documentsLoading = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  spaceId: '' as number | '',
  documentId: '' as number | '',
  enabled: '' as number | '',
  keyword: '',
})

async function load() {
  loading.value = true
  try {
    list.value = await fetchCorpusChunks({ ...query })
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await fetchCorpusStats()
  } catch {
    /* 统计失败不影响主表格 */
  }
}

/** 文档下拉跟随知识库联动：选了空间就只列该空间下的文档 */
async function loadDocuments() {
  documentsLoading.value = true
  try {
    const page = await fetchDocuments({
      pageNum: 1,
      pageSize: 100,
      spaceId: query.spaceId === '' ? null : query.spaceId,
    })
    documents.value = page.records ?? []
  } catch {
    documents.value = []
  } finally {
    documentsLoading.value = false
  }
}

function onSpaceChange() {
  // 换了空间，原先选中的文档必然不再属于它，清空避免两个条件互斥查出空集
  query.documentId = ''
  void loadDocuments()
  search()
}

function search() {
  query.pageNum = 1
  void load()
}

function resetQuery() {
  query.spaceId = ''
  query.documentId = ''
  query.enabled = ''
  query.keyword = ''
  void loadDocuments()
  search()
}

onMounted(async () => {
  const tasks: Promise<unknown>[] = [load(), loadStats(), loadDocuments()]
  try {
    spaces.value = await fetchAllSpaces()
  } catch {
    spaces.value = []
  }
  await Promise.allSettled(tasks)
})

// ------------------------------------------------------------------ 批量启停

const selected = ref<CorpusChunkVO[]>([])

function onSelectionChange(rows: CorpusChunkVO[]) {
  selected.value = rows
}

/**
 * 批量设状态：后端按「目标状态」而非「取反」处理，所以勾选里混有已启用/已禁用也能正确收敛，
 * 返回值是实际被改动的条数（已处于目标状态的不重复处理）。
 */
async function applyBatchStatus(enabled: number) {
  const ids = selected.value.map((row) => row.id)
  if (!ids.length) {
    ElMessage.warning('先勾选要处理的切片')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认把选中的 ${ids.length} 条切片设为「${enabled === 1 ? '启用' : '禁用'}」？` +
        (enabled === 1
          ? '启用会同步把这些片段补写回向量库，检索时可被召回。'
          : '禁用后向量库对应条目一并移除，检索不再召回，但原文与切片记录保留、随时可恢复。'),
      enabled === 1 ? '批量启用' : '批量禁用',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const changed = await batchChunkStatus(ids, enabled)
    ElMessage.success(
      changed > 0 ? `已更新 ${changed} 条切片` : `选中的切片本来就处于「${enabled === 1 ? '启用' : '禁用'}」状态`,
    )
    selected.value = []
    await Promise.all([load(), loadStats()])
  } catch {
    /* 拦截器已提示 */
  }
}

async function onToggle(row: CorpusChunkVO) {
  try {
    const next = await toggleChunkStatus(row.id)
    row.enabled = next
    ElMessage.success(next === 1 ? '已启用，恢复参与检索' : '已禁用，检索时不再召回')
    await loadStats()
  } catch {
    await load()
  }
}

// ------------------------------------------------------------------ 预览 / 编辑

const viewDialog = reactive({ visible: false, chunk: null as CorpusChunkVO | null })

function openPreview(row: CorpusChunkVO) {
  viewDialog.chunk = row
  viewDialog.visible = true
}

const editDialog = reactive({
  visible: false,
  submitting: false,
  id: 0,
  title: '',
  content: '',
})

function openEdit(row: CorpusChunkVO) {
  editDialog.id = row.id
  editDialog.title = `${row.documentTitle} · 片段 ${row.chunkIndex + 1}`
  editDialog.content = row.content
  editDialog.visible = true
}

async function submitEdit() {
  const text = editDialog.content.trim()
  if (!text) {
    ElMessage.warning('切片正文不能为空')
    return
  }
  editDialog.submitting = true
  try {
    await updateChunk(editDialog.id, text)
    ElMessage.success('已保存，向量索引同步更新')
    editDialog.visible = false
    await load()
  } catch {
    /* 拦截器已提示 */
  } finally {
    editDialog.submitting = false
  }
}

// ------------------------------------------------------------------ 导出当前页

const CSV_HEADERS = ['切片ID', '所属文档', '知识库', '文件类型', '片段序号', '页码', '字数', '状态', '入库时间', '正文']

function exportCurrentPage() {
  const rows = list.value.records
  if (!rows.length) {
    ElMessage.warning('当前页没有可导出的切片')
    return
  }
  downloadCsv(
    `语料切片_第${query.pageNum}页`,
    CSV_HEADERS,
    rows.map((row) => [
      row.id,
      row.documentTitle,
      row.spaceName,
      row.fileType ?? '',
      row.chunkIndex + 1,
      row.pageNumber ?? '',
      row.charCount,
      row.enabled === 1 ? '启用' : '禁用',
      formatForCsv(row.createTime),
      row.content,
    ]),
  )
  ElMessage.success(`已导出当前页 ${rows.length} 条切片`)
}

// ------------------------------------------------------------------ 展示辅助

/** 表格里的正文只做摘要展示，全文看预览弹窗，避免一行撑到十几行高 */
function excerpt(content: string) {
  const text = (content ?? '').replace(/\s+/g, ' ').trim()
  return text.length > 90 ? `${text.slice(0, 90)}…` : text
}

const statsCards = computed(() => [
  { label: '切片总数', value: stats.value?.totalChunks ?? 0, icon: Files },
  { label: '参与检索', value: stats.value?.enabledChunks ?? 0, icon: DocIcon },
  { label: '人工禁用', value: stats.value?.disabledChunks ?? 0, icon: View },
  { label: '已入库文档', value: stats.value?.indexedDocuments ?? 0, icon: Collection },
])

const disabledPercent = computed(() => {
  const current = stats.value
  if (!current || !current.totalChunks) {
    return '0%'
  }
  return `${((current.disabledChunks * 100) / current.totalChunks).toFixed(1)}%`
})
</script>

<template>
  <div class="corpus">
    <el-row :gutter="16" class="stat-row">
      <el-col v-for="card in statsCards" :key="card.label" :xs="12" :sm="12" :md="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-icon">
            <el-icon :size="22"><component :is="card.icon" /></el-icon>
          </div>
          <div>
            <div class="stat-value">{{ card.value }}</div>
            <div class="stat-label">{{ card.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>全局切片语料中心</span>
          <span class="hint">
            禁用占比 {{ disabledPercent }} · 共 {{ stats?.activeSpaces ?? 0 }} 个在用知识库 ·
            GET /api/chunks/page
          </span>
        </div>
      </template>

      <div class="toolbar">
        <el-select
          v-model="query.spaceId"
          placeholder="全部知识库"
          clearable
          style="width: 170px"
          @change="onSpaceChange"
        >
          <el-option v-for="space in spaces" :key="space.id" :label="space.name" :value="space.id" />
        </el-select>
        <el-select
          v-model="query.documentId"
          placeholder="全部文档"
          clearable
          filterable
          :loading="documentsLoading"
          style="width: 220px"
          @change="search"
        >
          <el-option v-for="doc in documents" :key="doc.id" :label="doc.title" :value="doc.id" />
        </el-select>
        <el-select v-model="query.enabled" placeholder="全部状态" clearable style="width: 130px" @change="search">
          <el-option label="参与检索" :value="1" />
          <el-option label="已禁用" :value="0" />
        </el-select>
        <el-input
          v-model="query.keyword"
          placeholder="按切片正文关键词检索"
          :prefix-icon="Search"
          clearable
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>
      </div>

      <div class="toolbar">
        <el-button type="success" plain :disabled="!selected.length" @click="applyBatchStatus(1)">
          批量启用{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
        <el-button type="warning" plain :disabled="!selected.length" @click="applyBatchStatus(0)">
          批量禁用{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
        <div class="spacer" />
        <el-button :icon="Download" :disabled="!list.records.length" @click="exportCurrentPage">
          导出当前页 CSV
        </el-button>
      </div>

      <el-table
        :data="list.records"
        v-loading="loading"
        stripe
        row-key="id"
        empty-text="没有匹配的切片，试试放宽筛选条件"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" reserve-selection />
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="所属文档" min-width="190">
          <template #default="{ row }">
            <div class="doc-cell">
              <el-icon><DocIcon /></el-icon>
              <span class="doc-title" :title="row.documentTitle">{{ row.documentTitle }}</span>
              <el-tag v-if="row.fileType" size="small" type="info" effect="plain">{{ row.fileType }}</el-tag>
            </div>
            <div class="doc-space">{{ row.spaceName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="片段" width="90" align="center">
          <template #default="{ row }">#{{ row.chunkIndex + 1 }}</template>
        </el-table-column>
        <el-table-column label="页码" width="80" align="center">
          <template #default="{ row }">
            <span v-if="row.pageNumber">P{{ row.pageNumber }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="charCount" label="字数" width="80" align="center" />
        <el-table-column label="切片正文" min-width="300">
          <template #default="{ row }">
            <span class="excerpt" :title="row.content">{{ excerpt(row.content) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled === 1"
              inline-prompt
              active-text="启用"
              inactive-text="禁用"
              @change="onToggle(row)"
            />
          </template>
        </el-table-column>
        <el-table-column label="入库时间" width="160">
          <template #default="{ row }">{{ formatForCsv(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="View" @click="openPreview(row)">预览</el-button>
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="list.total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        class="pager"
        @current-change="load"
        @size-change="search"
      />
    </el-card>

    <!-- 全文预览 -->
    <el-dialog v-model="viewDialog.visible" title="切片全文" width="720px">
      <div v-if="viewDialog.chunk" class="preview">
        <div class="preview-meta">
          <el-tag size="small" effect="plain">{{ viewDialog.chunk.documentTitle }}</el-tag>
          <el-tag size="small" type="info" effect="plain">
            片段 {{ viewDialog.chunk.chunkIndex + 1 }}
          </el-tag>
          <el-tag v-if="viewDialog.chunk.pageNumber" size="small" type="success" effect="plain">
            第 {{ viewDialog.chunk.pageNumber }} 页
          </el-tag>
          <span class="preview-chars">{{ viewDialog.chunk.charCount }} 字</span>
        </div>
        <pre class="preview-body">{{ viewDialog.chunk.content }}</pre>
      </div>
      <template #footer>
        <el-button @click="viewDialog.visible = false">关闭</el-button>
        <el-button
          type="primary"
          :icon="Edit"
          @click="
            () => {
              const target = viewDialog.chunk
              viewDialog.visible = false
              if (target) openEdit(target)
            }
          "
        >
          去编辑
        </el-button>
      </template>
    </el-dialog>

    <!-- 人工纠偏 -->
    <el-dialog v-model="editDialog.visible" :title="`编辑切片 · ${editDialog.title}`" width="760px">
      <el-input v-model="editDialog.content" type="textarea" :rows="14" resize="vertical" />
      <div class="edit-tip">
        保存后后端会重新嵌入该片段并同步向量库，下一次问答即用新正文；请只改事实性内容，别删出处上下文。
      </div>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.submitting" @click="submitEdit">
          保存并重嵌入
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.corpus {
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

/* 统计图标底色只取 Element Plus 的语义色派生变量，不引入新的色值 */
.stat-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  flex-shrink: 0;
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
  margin-bottom: 12px;
}

.spacer {
  flex: 1;
}

.doc-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.doc-title {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-space {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.excerpt {
  display: block;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.preview-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.preview-chars {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.preview-body {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 12px 14px;
  max-height: 52vh;
  overflow-y: auto;
}

.edit-tip {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
