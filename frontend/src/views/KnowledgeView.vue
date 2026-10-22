<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadRequestOptions } from 'element-plus'
import { storeToRefs } from 'pinia'
import {
  Delete,
  Document as DocIcon,
  Edit,
  Plus,
  Refresh,
  Search,
  UploadFilled,
  UserFilled,
} from '@element-plus/icons-vue'

import { useDocumentStore } from '@/stores/document'
import { useSpaceStore } from '@/stores/space'
import { reindexDocument } from '@/api/kb'
import { fetchChunks, toggleChunkStatus, updateChunk } from '@/api/chunk'
import { fetchSpaceMembers, grantSpaceMembers, revokeSpaceMember } from '@/api/space'
import { fetchUsers } from '@/api/admin'
import type { AdminUserVO, ChunkVO, DocumentVO, SpaceMemberVO, SpaceVO } from '@/types/api'

const docStore = useDocumentStore()
const spaceStore = useSpaceStore()

const {
  documents,
  total,
  pageNum,
  pageSize,
  listLoading,
  uploading,
  uploadPercent,
} = storeToRefs(docStore)
const { spaces, activeSpaceId, loading: spaceLoading } = storeToRefs(spaceStore)

// 与后端 KbDocumentServiceImpl.ALLOWED_TYPES 保持一致：不含 .html
// （预览以同源文件流返回，HTML 内嵌脚本会在本站上下文执行，属于存储型 XSS 入口）
const ALLOWED = ['.pdf', '.txt', '.md', '.doc', '.docx', '.ppt', '.pptx', '.xls', '.xlsx', '.csv']
const MAX_SIZE_MB = 50

const activeSpace = computed<SpaceVO | undefined>(() =>
  spaces.value.find((s) => s.id === activeSpaceId.value),
)

const statusMeta: Record<string, { text: string; type: 'success' | 'warning' | 'info' | 'danger' }> = {
  INDEXED: { text: '已入库', type: 'success' },
  PARSING: { text: '解析中', type: 'warning' },
  PENDING: { text: '待处理', type: 'info' },
  FAILED: { text: '失败', type: 'danger' },
}

function statusOf(row: DocumentVO) {
  return statusMeta[row.status] ?? { text: row.status, type: 'info' as const }
}

/**
 * 当前页仍在后台排队或解析的文档数。
 * 上传接口只负责落盘入队，解析 → 分块 → 向量化在后台线程完成，这里只做展示，不自动轮询。
 */
const processingCount = computed(
  () => documents.value.filter((d) => d.status === 'PENDING' || d.status === 'PARSING').length,
)

function formatBytes(size: number | null) {
  if (!size) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(2)} MB`
}

/** 后端返回 ISO 字符串，这里只保留到分钟——授权时间不需要秒级精度 */
function formatTime(iso: string) {
  if (!iso) return '-'
  const date = new Date(iso)
  return Number.isNaN(date.getTime())
    ? iso
    : `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
        date.getDate(),
      ).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(
        date.getMinutes(),
      ).padStart(2, '0')}`
}

// ------------------------------------------------------------------ 空间管理

const spaceDialog = reactive({
  visible: false,
  mode: 'create' as 'create' | 'edit',
  id: 0,
  name: '',
  description: '',
  submitting: false,
})

function openCreateSpace() {
  spaceDialog.mode = 'create'
  spaceDialog.id = 0
  spaceDialog.name = ''
  spaceDialog.description = ''
  spaceDialog.visible = true
}

function openEditSpace(space: SpaceVO) {
  spaceDialog.mode = 'edit'
  spaceDialog.id = space.id
  spaceDialog.name = space.name
  spaceDialog.description = space.description ?? ''
  spaceDialog.visible = true
}

async function submitSpace() {
  const name = spaceDialog.name.trim()
  if (!name) {
    ElMessage.warning('请填写知识库名称')
    return
  }
  spaceDialog.submitting = true
  try {
    if (spaceDialog.mode === 'create') {
      const id = await spaceStore.create(name, spaceDialog.description.trim())
      spaceStore.setActive(id)
      await docStore.loadBySpace(id)
      ElMessage.success('知识库已创建')
    } else {
      await spaceStore.rename(spaceDialog.id, name, spaceDialog.description.trim())
      ElMessage.success('知识库已更新')
    }
    spaceDialog.visible = false
  } catch {
    /* 拦截器已提示 */
  } finally {
    spaceDialog.submitting = false
  }
}

async function onDeleteSpace(space: SpaceVO) {
  if (space.id === 1) {
    ElMessage.warning('默认知识库不可删除')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认删除知识库「${space.name}」？该操作仅在空间内没有文档时可执行。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await spaceStore.remove(space.id)
    ElMessage.success('已删除')
    await docStore.loadBySpace(spaceStore.activeSpaceId)
  } catch {
    /* 后端会因“仍有文档”拒绝，拦截器已提示 */
  }
}

async function selectSpace(space: SpaceVO) {
  if (space.id === activeSpaceId.value) return
  spaceStore.setActive(space.id)
  await docStore.loadBySpace(space.id)
}

// ------------------------------------------------------------------ 空间成员授权

/**
 * 空间成员 = 「这个账号能检索到哪个知识库」。
 *
 * <p>后端在召回 SQL 里就按成员关系收敛了范围，所以这里改动的不是界面文案，而是别人能不能
 * 读到这个空间的切片。默认知识库（id=1）在 V9 迁移里对全部在册账号开放，是公共库语义。
 */
const memberDialog = reactive({
  visible: false,
  loading: false,
  submitting: false,
  spaceId: 0,
  spaceName: '',
})
const members = ref<SpaceMemberVO[]>([])
/** 全站账号列表，用来挑「还没授权的人」 */
const allUsers = ref<AdminUserVO[]>([])
const selectedUserIds = ref<number[]>([])

const candidateUsers = computed(() => {
  const granted = new Set(members.value.map((m) => m.userId))
  return allUsers.value.filter((u) => !granted.has(u.id))
})

async function openMembers(space: SpaceVO) {
  memberDialog.spaceId = space.id
  memberDialog.spaceName = space.name
  memberDialog.visible = true
  selectedUserIds.value = []
  memberDialog.loading = true
  try {
    // 账号列表一次取满一页即可：毕设体量下账号数量级很小，不做分页搜索
    const [list, page] = await Promise.all([
      fetchSpaceMembers(space.id),
      fetchUsers({ pageNum: 1, pageSize: 200 }),
    ])
    members.value = list
    allUsers.value = page.records
  } catch {
    /* 拦截器已提示 */
  } finally {
    memberDialog.loading = false
  }
}

async function submitGrant() {
  if (selectedUserIds.value.length === 0) {
    ElMessage.warning('请先选择要授权的账号')
    return
  }
  memberDialog.submitting = true
  try {
    const added = await grantSpaceMembers(memberDialog.spaceId, selectedUserIds.value)
    ElMessage.success(added > 0 ? `已授权 ${added} 个账号` : '所选账号已经是成员')
    selectedUserIds.value = []
    members.value = await fetchSpaceMembers(memberDialog.spaceId)
  } catch {
    /* 拦截器已提示（账号不存在等） */
  } finally {
    memberDialog.submitting = false
  }
}

async function onRevoke(member: SpaceMemberVO) {
  try {
    await ElMessageBox.confirm(
      `确认撤销「${member.nickname || member.username}」对知识库「${memberDialog.spaceName}」的访问权限？` +
        `撤销后他的问答将不再召回该空间的切片。`,
      '撤销授权',
      { type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await revokeSpaceMember(memberDialog.spaceId, member.userId)
    ElMessage.success('已撤销授权')
    members.value = await fetchSpaceMembers(memberDialog.spaceId)
  } catch {
    /* 空间创建人被后端拒绝（4031），拦截器已提示 */
  }
}

// ------------------------------------------------------------------ 文档上传 / 删除

function beforeUpload(file: File) {
  const name = file.name.toLowerCase()
  if (!ALLOWED.some((ext) => name.endsWith(ext))) {
    ElMessage.warning(`不支持的类型，仅支持：${ALLOWED.join(' / ')}`)
    return false
  }
  if (file.size > MAX_SIZE_MB * 1024 * 1024) {
    ElMessage.warning(`文件超过 ${MAX_SIZE_MB}MB 限制`)
    return false
  }
  return true
}

async function handleUpload(options: UploadRequestOptions) {
  const file = options.file as File
  const id = await docStore.upload(file, activeSpaceId.value)
  ElMessage.success(
    `《${file.name}》已上传到「${activeSpace.value?.name ?? '默认知识库'}」，正在后台解析；完成后点「刷新」查看结果`,
  )
  await Promise.all([docStore.loadDocuments(), spaceStore.loadSpaces()])
  return id
}

function onUploadError(error: unknown) {
  ElMessage.error(`上传失败：${(error as Error)?.message ?? '未知错误'}`)
}

async function onDeleteDoc(row: DocumentVO) {
  try {
    await ElMessageBox.confirm(`确认删除《${row.title}》及其 ${row.chunkCount} 个分段？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await docStore.remove(row.id)
  await spaceStore.loadSpaces()
  ElMessage.success('已删除')
}

async function refreshList() {
  await Promise.all([docStore.loadDocuments(), spaceStore.loadSpaces()])
}

/**
 * 失败文档重试：后端会清空旧切片与向量，重新入队解析。
 * 文档仍在 PENDING / PARSING 时返回 4090，原文丢失返回 4041，均由拦截器提示。
 */
async function onReindex(row: DocumentVO) {
  try {
    await ElMessageBox.confirm(
      `确认重新解析《${row.title}》？将清空现有分段与向量后重新入库。`,
      '重新解析',
      { type: 'warning', confirmButtonText: '重新解析', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await reindexDocument(row.id)
    ElMessage.success('已提交后台重新解析，完成后状态会变为「已入库」')
    await refreshList()
  } catch {
    /* 拦截器已提示 */
  }
}

// ------------------------------------------------------------------ 切片抽屉（人工干预）

const drawer = reactive({
  visible: false,
  documentId: 0,
  documentTitle: '',
  chunks: [] as ChunkVO[],
  total: 0,
  pageNum: 1,
  pageSize: 5,
  loading: false,
  editingId: 0,
  editingText: '',
  savingId: 0,
})

async function openChunks(row: DocumentVO) {
  drawer.documentId = row.id
  drawer.documentTitle = row.title
  drawer.pageNum = 1
  drawer.editingId = 0
  drawer.visible = true
  await loadChunks()
}

async function loadChunks() {
  drawer.loading = true
  try {
    const page = await fetchChunks(drawer.documentId, {
      pageNum: drawer.pageNum,
      pageSize: drawer.pageSize,
    })
    drawer.chunks = page.records ?? []
    drawer.total = page.total ?? 0
  } catch {
    /* 拦截器已提示 */
  } finally {
    drawer.loading = false
  }
}

function startEdit(chunk: ChunkVO) {
  drawer.editingId = chunk.id
  drawer.editingText = chunk.content
}

function cancelEdit() {
  drawer.editingId = 0
  drawer.editingText = ''
}

async function saveEdit(chunk: ChunkVO) {
  const text = drawer.editingText.trim()
  if (!text) {
    ElMessage.warning('分段内容不能为空')
    return
  }
  drawer.savingId = chunk.id
  try {
    await updateChunk(chunk.id, text)
    ElMessage.success('已保存，向量索引同步更新')
    drawer.editingId = 0
    await loadChunks()
  } catch {
    /* 拦截器已提示 */
  } finally {
    drawer.savingId = 0
  }
}

async function onToggle(chunk: ChunkVO) {
  try {
    const next = await toggleChunkStatus(chunk.id)
    chunk.enabled = next
    ElMessage.success(next === 1 ? '已启用，恢复参与检索' : '已禁用，检索时不再召回')
  } catch {
    /* 拦截器已提示；回滚开关显示 */
    await loadChunks()
  }
}

onMounted(async () => {
  await spaceStore.loadSpaces()
  await docStore.loadBySpace(spaceStore.activeSpaceId)
})
</script>

<template>
  <div class="kb">
    <!-- 左：知识库空间侧栏 -->
    <aside class="kb-side">
      <div class="side-head">
        <span class="side-title">知识库</span>
        <el-button type="primary" :icon="Plus" size="small" @click="openCreateSpace">新建</el-button>
      </div>

      <div v-loading="spaceLoading" class="space-list">
        <div
          v-for="space in spaces"
          :key="space.id"
          class="space-item"
          :class="{ active: space.id === activeSpaceId }"
          @click="selectSpace(space)"
        >
          <div class="space-main">
            <div class="space-name">
              <el-icon><DocIcon /></el-icon>
              <span>{{ space.name }}</span>
              <el-tag v-if="space.id === 1" size="small" type="info" effect="plain">默认</el-tag>
            </div>
            <div class="space-meta">{{ space.docCount }} 文档 · {{ space.chunkCount }} 切片</div>
          </div>
          <div class="space-ops" @click.stop>
            <el-button link :icon="UserFilled" title="成员授权" @click="openMembers(space)" />
            <el-button link :icon="Edit" title="编辑" @click="openEditSpace(space)" />
            <el-button
              link
              type="danger"
              :icon="Delete"
              title="删除"
              :disabled="space.id === 1"
              @click="onDeleteSpace(space)"
            />
          </div>
        </div>
        <el-empty v-if="!spaceLoading && spaces.length === 0" description="暂无知识库" :image-size="60" />
      </div>
    </aside>

    <!-- 右：当前空间的文档管理 -->
    <section class="kb-main">
      <el-card shadow="never" class="block">
        <template #header>
          <div class="card-header">
            <span>
              上传到「{{ activeSpace?.name ?? '默认知识库' }}」
              <span class="hint">POST /api/documents/upload?spaceId={{ activeSpaceId }}</span>
            </span>
            <el-button :icon="Refresh" :loading="listLoading" @click="refreshList">刷新</el-button>
          </div>
        </template>

        <el-upload
          drag
          :auto-upload="true"
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="handleUpload"
          :on-error="onUploadError"
          :disabled="uploading"
          :accept="ALLOWED.join(',')"
        >
          <el-icon class="upload-icon"><UploadFilled /></el-icon>
          <div class="el-upload__text">把文件拖进来，或 <em>点击选择</em></div>
          <template #tip>
            <div class="el-upload__tip">
              支持 {{ ALLOWED.join(' / ') }}，单文件不超过 {{ MAX_SIZE_MB }}MB；上传后归属当前选中知识库
            </div>
          </template>
        </el-upload>

        <el-progress
          v-if="uploading"
          :percentage="uploadPercent"
          :stroke-width="10"
          class="upload-progress"
          status="success"
        />
      </el-card>

      <el-card shadow="never" class="block">
        <template #header>
          <div class="card-header">
            <span>文档列表</span>
            <span class="hint">共 {{ total }} 篇</span>
          </div>
        </template>

        <el-alert
          v-if="processingCount > 0"
          type="info"
          :closable="false"
          show-icon
          class="processing-tip"
          :title="`当前页有 ${processingCount} 篇文档正在后台解析，完成后状态变为「已入库」才会参与问答召回。点右上角「刷新」查看进度。`"
        />

        <el-table :data="documents" v-loading="listLoading" stripe empty-text="该知识库暂无文档，先上传一个试试">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
          <el-table-column prop="fileType" label="类型" width="80" />
          <el-table-column label="大小" width="100">
            <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tooltip :disabled="!row.errorMsg" :content="row.errorMsg" placement="top">
                <el-tag :type="statusOf(row).type" effect="plain">{{ statusOf(row).text }}</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" label="分段数" width="80" />
          <el-table-column prop="createTime" label="入库时间" width="170">
            <template #default="{ row }">{{ (row.createTime ?? '').replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" :icon="Search" @click="openChunks(row)">查看切片</el-button>
              <el-button
                v-if="row.status === 'FAILED'"
                link
                type="warning"
                :icon="Refresh"
                @click="onReindex(row)"
              >
                重新解析
              </el-button>
              <el-button link type="danger" :icon="Delete" @click="onDeleteDoc(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="pager"
          @current-change="(p: number) => docStore.loadDocuments(p, pageSize)"
          @size-change="(s: number) => docStore.loadDocuments(1, s)"
        />
      </el-card>
    </section>

    <!-- 空间新建 / 编辑弹窗 -->
    <el-dialog
      v-model="spaceDialog.visible"
      :title="spaceDialog.mode === 'create' ? '新建知识库' : '编辑知识库'"
      width="440px"
    >
      <el-form label-width="72px">
        <el-form-item label="名称" required>
          <el-input v-model="spaceDialog.name" maxlength="128" show-word-limit placeholder="如：网络工程复习库" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="spaceDialog.description"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            placeholder="选填，简要说明这个知识库的用途"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="spaceDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="spaceDialog.submitting" @click="submitSpace">确定</el-button>
      </template>
    </el-dialog>

    <!-- 空间成员授权弹窗：决定哪些账号能检索到这个知识库的内容 -->
    <el-dialog
      v-model="memberDialog.visible"
      :title="`成员授权 · ${memberDialog.spaceName}`"
      width="560px"
    >
      <div v-loading="memberDialog.loading" class="member-body">
        <el-alert
          v-if="memberDialog.spaceId === 1"
          type="info"
          :closable="false"
          show-icon
          title="默认知识库在 V9 迁移时已对所有在册账号开放，这里可以按需撤销。"
          class="member-tip"
        />
        <div class="member-grant">
          <el-select
            v-model="selectedUserIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="2"
            placeholder="选择要授权的账号"
            class="grant-select"
          >
            <el-option
              v-for="user in candidateUsers"
              :key="user.id"
              :value="user.id"
              :label="user.nickname ? `${user.nickname}（${user.username}）` : user.username"
            />
          </el-select>
          <el-button
            type="primary"
            :icon="Plus"
            :loading="memberDialog.submitting"
            :disabled="selectedUserIds.length === 0"
            @click="submitGrant"
          >
            授权
          </el-button>
        </div>

        <el-table :data="members" size="small" max-height="300" empty-text="还没有成员">
          <el-table-column label="账号" min-width="170">
            <template #default="{ row }">
              <span>{{ row.nickname || row.username }}</span>
              <span class="member-account">{{ row.username }}</span>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.role === 'ADMIN' ? 'danger' : 'info'" effect="plain">
                {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="授权时间" min-width="140">
            <template #default="{ row }">{{ formatTime(row.grantTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="76" fixed="right">
            <template #default="{ row }">
              <el-button link type="danger" @click="onRevoke(row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="member-note">
          管理员默认能检索全部空间，不需要成员行；空间创建人不可被撤销，否则这个空间会没人能管。
        </p>
      </div>
      <template #footer>
        <el-button @click="memberDialog.visible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 切片抽屉：人工纠偏（编辑正文 / 启停） -->
    <el-drawer v-model="drawer.visible" :title="`切片管理 · ${drawer.documentTitle}`" size="620px">
      <div class="drawer-tip">
        <el-icon><Search /></el-icon>
        可直接编辑切片正文做人工纠偏，或临时禁用低质量切片（禁用后检索不再召回）。
      </div>

      <div v-loading="drawer.loading" class="chunk-list">
        <div v-for="chunk in drawer.chunks" :key="chunk.id" class="chunk-item">
          <div class="chunk-head">
            <span class="chunk-index">片段 {{ chunk.chunkIndex + 1 }}</span>
            <el-tag v-if="chunk.pageNumber" size="small" type="info" effect="plain">
              第 {{ chunk.pageNumber }} 页
            </el-tag>
            <span class="chunk-chars">{{ chunk.charCount }} 字</span>
            <el-switch
              :model-value="chunk.enabled === 1"
              active-text="启用"
              inactive-text="禁用"
              inline-prompt
              @change="onToggle(chunk)"
            />
          </div>

          <template v-if="drawer.editingId === chunk.id">
            <el-input
              v-model="drawer.editingText"
              type="textarea"
              :rows="6"
              resize="vertical"
              class="chunk-edit"
            />
            <div class="chunk-edit-ops">
              <el-button size="small" @click="cancelEdit">取消</el-button>
              <el-button
                size="small"
                type="primary"
                :loading="drawer.savingId === chunk.id"
                @click="saveEdit(chunk)"
              >
                保存并重嵌入
              </el-button>
            </div>
          </template>
          <template v-else>
            <pre class="chunk-content">{{ chunk.content }}</pre>
            <div class="chunk-ops">
              <el-button link type="primary" :icon="Edit" @click="startEdit(chunk)">编辑</el-button>
            </div>
          </template>
        </div>
        <el-empty v-if="!drawer.loading && drawer.chunks.length === 0" description="该文档暂无切片" :image-size="60" />
      </div>

      <el-pagination
        v-model:current-page="drawer.pageNum"
        :page-size="drawer.pageSize"
        :total="drawer.total"
        layout="total, prev, pager, next"
        class="pager"
        @current-change="loadChunks"
      />
    </el-drawer>
  </div>
</template>

<style scoped>
.kb {
  display: flex;
  gap: 16px;
  max-width: 1280px;
  margin: 0 auto;
  align-items: flex-start;
}

.kb-side {
  width: 260px;
  flex-shrink: 0;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  overflow: hidden;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.side-title {
  font-weight: 600;
  font-size: 15px;
}

.space-list {
  max-height: calc(100vh - 180px);
  overflow-y: auto;
  padding: 6px;
}

.space-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.space-item:hover {
  background: var(--el-fill-color-light);
}

.space-item.active {
  background: var(--el-color-primary-light-9);
}

.space-main {
  min-width: 0;
  flex: 1;
}

.space-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.space-name span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.space-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 3px;
}

.space-ops {
  display: flex;
  gap: 2px;
  opacity: 0;
  transition: opacity 0.15s;
}

.space-item:hover .space-ops,
.space-item.active .space-ops {
  opacity: 1;
}

.kb-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
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
  margin-left: 8px;
}

.upload-icon {
  font-size: 48px;
  color: var(--el-text-color-placeholder);
}

.upload-progress {
  margin-top: 14px;
}

.processing-tip {
  margin-bottom: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.drawer-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 8px;
  padding: 8px 12px;
  margin-bottom: 14px;
}

.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 120px;
}

.chunk-item {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
}

.chunk-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.chunk-index {
  font-weight: 600;
  font-size: 13px;
}

.chunk-chars {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.chunk-head .el-switch {
  margin-left: auto;
}

.chunk-content {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  max-height: 160px;
  overflow-y: auto;
}

.chunk-ops {
  margin-top: 6px;
  text-align: right;
}

.chunk-edit {
  margin-top: 4px;
}

.chunk-edit-ops {
  margin-top: 8px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ---------- 空间成员授权弹窗 ---------- */
.member-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.member-grant {
  display: flex;
  align-items: center;
  gap: 8px;
}

.grant-select {
  flex: 1;
  min-width: 0;
}

/* 昵称后面跟上裸账号，避免两个同昵称的人分不清 */
.member-account {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.member-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

@media (max-width: 900px) {
  .kb {
    flex-direction: column;
  }
  .kb-side {
    width: 100%;
  }
  .space-list {
    max-height: 220px;
  }
}
</style>
