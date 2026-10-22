<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  Check,
  Close,
  Delete,
  Edit,
  MagicStick,
  Plus,
  Promotion,
  Refresh,
  Star,
} from '@element-plus/icons-vue'

import {
  createTemplate,
  deleteTemplate,
  fetchActiveTemplates,
  fetchAllTemplates,
  updateTemplate,
} from '@/api/template'
import { useUserStore } from '@/stores/user'
import { TEMPLATE_CATEGORIES } from '@/types/api'
import type { PromptTemplateVO } from '@/types/api'

/**
 * 提示词模板市场：把「好用的提问方式」沉淀成可复用的模板。
 *
 * <p>数据出入口按角色分流：管理员读 {@code /api/admin/templates}（含已下架，可增删改），
 * 普通用户读 {@code /api/templates/active}（只见上架模板，页面自动收成只读卡片）。
 * 这样普通用户不需要任何管理接口权限，也不会看见被下架的内容。
 *
 * <p>模板正文里的 {@code {topic}} 是占位符：问答页点击胶囊时用它当光标定位点，
 * 用户只需替换成自己要问的主题。
 */

const userStore = useUserStore()
const router = useRouter()
const isAdmin = computed(() => userStore.isAdmin)

const templates = ref<PromptTemplateVO[]>([])
const loading = ref(false)
const categoryFilter = ref('')

async function load() {
  loading.value = true
  try {
    templates.value = isAdmin.value ? await fetchAllTemplates() : await fetchActiveTemplates()
  } catch {
    templates.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)

/** 分类筛选项从已加载数据里派生，保证筛出的一定是看得见的卡片 */
const categories = computed(() => {
  const present = new Set(templates.value.map((item) => item.category).filter(Boolean))
  // 先按后端白名单顺序排，再补数据里出现的自定义分类
  return [...TEMPLATE_CATEGORIES.filter((c) => present.has(c)), ...[...present].filter((c) => !TEMPLATE_CATEGORIES.includes(c as never))]
})

const visibleList = computed(() =>
  categoryFilter.value
    ? templates.value.filter((item) => item.category === categoryFilter.value)
    : templates.value,
)

const stats = computed(() => ({
  total: templates.value.length,
  enabled: templates.value.filter((item) => item.enabled === 1).length,
  offline: templates.value.filter((item) => item.enabled === 0).length,
}))

// ------------------------------------------------------------------ 新增 / 编辑

const dialog = reactive({
  visible: false,
  submitting: false,
  mode: 'create' as 'create' | 'edit',
  id: 0,
  title: '',
  category: '概念解析',
  promptContent: '',
  description: '',
  enabled: 1,
  sortOrder: 100,
})
const formRef = ref<FormInstance>()

const rules: FormRules = {
  title: [
    { required: true, message: '请填写模板名称', trigger: 'blur' },
    { max: 128, message: '模板名称最多 128 字', trigger: 'blur' },
  ],
  promptContent: [
    { required: true, message: '请填写模板正文', trigger: 'blur' },
    { max: 2000, message: '模板正文最多 2000 字', trigger: 'blur' },
  ],
}

function openCreate() {
  dialog.mode = 'create'
  dialog.id = 0
  dialog.title = ''
  dialog.category = '概念解析'
  dialog.promptContent = ''
  dialog.description = ''
  dialog.enabled = 1
  dialog.sortOrder = 100
  dialog.visible = true
}

function openEdit(row: PromptTemplateVO) {
  dialog.mode = 'edit'
  dialog.id = row.id
  dialog.title = row.title
  dialog.category = row.category
  dialog.promptContent = row.promptContent
  dialog.description = row.description ?? ''
  dialog.enabled = row.enabled
  dialog.sortOrder = row.sortOrder
  dialog.visible = true
}

async function submit() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  dialog.submitting = true
  const payload = {
    title: dialog.title.trim(),
    category: dialog.category,
    promptContent: dialog.promptContent.trim(),
    description: dialog.description.trim() || undefined,
    enabled: dialog.enabled,
    sortOrder: dialog.sortOrder,
  }
  try {
    if (dialog.mode === 'create') {
      await createTemplate(payload)
      ElMessage.success('模板已上架')
    } else {
      await updateTemplate(dialog.id, payload)
      ElMessage.success('模板已更新')
    }
    dialog.visible = false
    await load()
  } catch {
    /* 重名、非法分类等由拦截器提示 */
  } finally {
    dialog.submitting = false
  }
}

/** 启停：下架的模板不会出现在问答页胶囊里，但记录仍在管理端可见 */
async function onToggleEnabled(row: PromptTemplateVO) {
  const next = row.enabled === 1 ? 0 : 1
  try {
    await updateTemplate(row.id, {
      title: row.title,
      category: row.category,
      promptContent: row.promptContent,
      description: row.description ?? undefined,
      enabled: next,
      sortOrder: row.sortOrder,
    })
    row.enabled = next
    ElMessage.success(next === 1 ? '已上架，问答页可见' : '已下架，问答页不再展示')
  } catch {
    /* 拦截器已提示，列表保持原样 */
  }
}

async function onDelete(row: PromptTemplateVO) {
  try {
    await ElMessageBox.confirm(
      `确认删除模板「${row.title}」？删除后问答页的对应胶囊一并消失，此操作不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteTemplate(row.id)
    ElMessage.success('已删除')
    await load()
  } catch {
    /* 拦截器已提示 */
  }
}

/** 复制正文，方便用户改两个词就直接用 */
async function copyContent(row: PromptTemplateVO) {
  try {
    await navigator.clipboard.writeText(row.promptContent)
    ElMessage.success('模板正文已复制，粘贴到问答框改两个词即可')
  } catch {
    ElMessage.warning('当前浏览器不允许读取剪贴板，请手动选中复制')
  }
}

function goToChat() {
  router.push('/chat')
}

/** 卡片上的正文预览：过长截断，完整内容看 tooltip */
function excerpt(text: string) {
  const flat = (text ?? '').replace(/\s+/g, ' ').trim()
  return flat.length > 88 ? `${flat.slice(0, 88)}…` : flat
}
</script>

<template>
  <div class="templates">
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>提示词模板市场</span>
          <span class="hint">
            {{
              isAdmin
                ? `在架 ${stats.enabled} / 下架 ${stats.offline} / 共 ${stats.total} · GET /api/admin/templates`
                : `共 ${stats.total} 个可用模板 · GET /api/templates/active`
            }}
          </span>
          <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>

      <div class="toolbar">
        <el-radio-group v-model="categoryFilter" size="small">
          <el-radio-button value="">全部分类</el-radio-button>
          <el-radio-button v-for="item in categories" :key="item" :value="item">{{ item }}</el-radio-button>
        </el-radio-group>
        <div class="spacer" />
        <el-button :icon="Promotion" @click="goToChat">去问答页使用</el-button>
        <el-button v-if="isAdmin" type="primary" plain :icon="Plus" @click="openCreate">新增模板</el-button>
      </div>

      <div v-loading="loading" class="grid">
        <el-card
          v-for="item in visibleList"
          :key="item.id"
          shadow="hover"
          class="tpl-card"
          :class="{ 'tpl-card--offline': item.enabled === 0 }"
        >
          <div class="tpl-head">
            <div class="tpl-title" :title="item.title">
              <el-icon><Star /></el-icon>
              <span>{{ item.title }}</span>
            </div>
            <div class="tpl-tags">
              <el-tag size="small" effect="plain">{{ item.category }}</el-tag>
              <el-tag v-if="item.enabled === 0" size="small" type="info" effect="plain">已下架</el-tag>
            </div>
          </div>

          <p class="tpl-desc">{{ item.description || '未填写说明' }}</p>

          <el-tooltip :content="item.promptContent" placement="top" :show-after="300">
            <pre class="tpl-body">{{ excerpt(item.promptContent) }}</pre>
          </el-tooltip>

          <div class="tpl-foot">
            <span class="tpl-order">权重 {{ item.sortOrder }}</span>
            <div class="tpl-ops">
              <el-button link type="primary" size="small" :icon="MagicStick" @click="copyContent(item)">
                复制
              </el-button>
              <template v-if="isAdmin">
                <el-button link type="primary" size="small" :icon="Edit" @click="openEdit(item)">编辑</el-button>
                <el-button
                  link
                  :type="item.enabled === 1 ? 'warning' : 'success'"
                  size="small"
                  :icon="item.enabled === 1 ? Close : Check"
                  @click="onToggleEnabled(item)"
                >
                  {{ item.enabled === 1 ? '下架' : '上架' }}
                </el-button>
                <el-button link type="danger" size="small" :icon="Delete" @click="onDelete(item)">
                  删除
                </el-button>
              </template>
            </div>
          </div>
        </el-card>

        <el-empty
          v-if="!loading && visibleList.length === 0"
          class="grid-empty"
          description="该分类下暂无模板"
          :image-size="70"
        />
      </div>

      <div v-if="!isAdmin" class="role-tip">
        模板由管理员维护；这里展示的是已上架的模板，点击问答页输入框上方的胶囊即可一键填入。
      </div>
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'create' ? '新增模板' : '编辑模板'"
      width="640px"
    >
      <el-form ref="formRef" :model="dialog" :rules="rules" label-width="88px">
        <el-form-item label="模板名称" prop="title">
          <el-input v-model="dialog.title" maxlength="128" show-word-limit placeholder="如：概念四步解析" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="dialog.category" style="width: 100%">
            <el-option v-for="item in TEMPLATE_CATEGORIES" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="模板正文" prop="promptContent">
          <el-input
            v-model="dialog.promptContent"
            type="textarea"
            :rows="7"
            maxlength="2000"
            show-word-limit
            placeholder="建议写成引导前缀，并用 {topic} 标出用户要替换的主题，例如：请用四步法解释 {topic}"
          />
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="dialog.description"
            type="textarea"
            :rows="2"
            maxlength="256"
            show-word-limit
            placeholder="选填，告诉用户这个模板适合什么场景"
          />
        </el-form-item>
        <el-form-item label="排序权重">
          <el-input-number v-model="dialog.sortOrder" :min="0" :max="9999" controls-position="right" />
          <span class="field-hint">数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="上架状态">
          <el-switch
            :model-value="dialog.enabled === 1"
            inline-prompt
            active-text="上架"
            inactive-text="下架"
            @change="(v: boolean) => (dialog.enabled = v ? 1 : 0)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.templates {
  max-width: 1360px;
  margin: 0 auto;
}

.block {
  border-radius: 10px;
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

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
  min-height: 120px;
}

.grid-empty {
  grid-column: 1 / -1;
}

.tpl-card {
  border-radius: 10px;
  display: flex;
  flex-direction: column;
}

.tpl-card--offline {
  opacity: 0.72;
}

.tpl-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
}

.tpl-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.tpl-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  min-width: 0;
}

.tpl-title span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tpl-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.tpl-desc {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.tpl-body {
  flex: 1;
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  cursor: default;
}

.tpl-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tpl-order {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.tpl-ops {
  display: flex;
  align-items: center;
  gap: 2px;
}

.field-hint {
  margin-left: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.role-tip {
  margin-top: 14px;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
