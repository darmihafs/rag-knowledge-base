<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'

import {
  createUser,
  fetchUsers,
  resetUserPassword,
  updateUserRole,
  updateUserStatus,
} from '@/api/admin'
import { useUserStore } from '@/stores/user'
import type { AdminUserVO, PageResult, UserRole } from '@/types/api'

/**
 * 用户与权限管理：sys_user 表的管理员视角。
 *
 * <p>所有写操作都直接落到后端，由后端做「不能改自己」「不能降级/禁用最后一名管理员」
 * 这两条自我保护校验；前端同样把当前登录账号的操作禁用掉，但那只为了少发一次注定
 * 失败的请求，真正的边界在 <code>UserServiceImpl</code>。
 */

const userStore = useUserStore()
/** 当前登录管理员自己：角色与状态两列对他禁用 */
const selfId = computed(() => userStore.user?.id ?? 0)

const INITIAL_PASSWORD = '123456'

const list = ref<PageResult<AdminUserVO>>({ records: [], total: 0, size: 10, current: 1, pages: 0 })
const loading = ref(false)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  role: '' as UserRole | '',
  status: '' as number | '',
})

async function load() {
  loading.value = true
  try {
    list.value = await fetchUsers({ ...query })
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
  query.role = ''
  query.status = ''
  search()
}

onMounted(load)

// ------------------------------------------------------------------ 新建账号

const dialog = reactive({
  visible: false,
  submitting: false,
  username: '',
  password: '',
  nickname: '',
  role: 'USER' as UserRole,
})
const formRef = ref<FormInstance>()

const rules: FormRules = {
  username: [
    { required: true, message: '请填写用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度 3-32 位', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请填写初始密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码至少 6 位', trigger: 'blur' },
  ],
}

function openCreate() {
  dialog.username = ''
  dialog.password = ''
  dialog.nickname = ''
  dialog.role = 'USER'
  dialog.visible = true
}

async function submitCreate() {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  dialog.submitting = true
  try {
    await createUser({
      username: dialog.username.trim(),
      password: dialog.password,
      nickname: dialog.nickname.trim() || undefined,
      role: dialog.role,
    })
    ElMessage.success('账号已创建')
    dialog.visible = false
    await load()
  } catch {
    /* 用户名重复等错误由拦截器提示 */
  } finally {
    dialog.submitting = false
  }
}

// ------------------------------------------------------------------ 行内操作

/** 角色切换：下拉直接改，失败（如最后一个管理员）回滚到原值 */
async function onRoleChange(row: AdminUserVO, role: UserRole) {
  try {
    await updateUserRole(row.id, role)
    row.role = role
    ElMessage.success(`已将 ${row.username} 设为${role === 'ADMIN' ? '管理员' : '普通用户'}`)
  } catch {
    await load()
  }
}

async function onStatusChange(row: AdminUserVO, next: number) {
  try {
    await ElMessageBox.confirm(
      next === 1
        ? `确认启用账号「${row.username}」？启用后他可以重新登录。`
        : `确认禁用账号「${row.username}」？禁用后他已登录的令牌会立即失效。`,
      next === 1 ? '启用确认' : '禁用确认',
      { type: 'warning', confirmButtonText: next === 1 ? '启用' : '禁用', cancelButtonText: '取消' },
    )
  } catch {
    // 用户取消：Switch 已经本地翻转了，重新拉一次列表把它拨回去
    await load()
    return
  }
  try {
    await updateUserStatus(row.id, next)
    row.status = next
    ElMessage.success(next === 1 ? '已启用' : '已禁用')
  } catch {
    await load()
  }
}

async function onResetPassword(row: AdminUserVO) {
  try {
    await ElMessageBox.confirm(
      `确认把 ${row.username} 的密码重置为初始密码「${INITIAL_PASSWORD}」？`,
      '重置密码',
      { type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    const password = await resetUserPassword(row.id)
    ElMessage.success(`已重置为 ${password}，请提醒本人尽快修改`)
  } catch {
    /* 拦截器已提示 */
  }
}

function formatTime(value: string) {
  return (value ?? '').replace('T', ' ').slice(0, 19)
}

const adminCount = computed(() => list.value.records.filter((u) => u.role === 'ADMIN').length)
</script>

<template>
  <div class="user-manage">
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>用户与权限管理</span>
          <span class="hint">
            共 {{ list.total }} 个账号，本页 {{ adminCount }} 个管理员 · PUT /api/users/:id/role
          </span>
        </div>
      </template>

      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          placeholder="搜索用户名或昵称"
          :prefix-icon="Search"
          clearable
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
        <el-select v-model="query.role" placeholder="全部角色" clearable style="width: 130px">
          <el-option label="管理员" value="ADMIN" />
          <el-option label="普通用户" value="USER" />
        </el-select>
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 130px">
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetQuery">重置</el-button>
        <div class="spacer" />
        <el-button type="primary" plain :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>

      <el-table
        :data="list.records"
        v-loading="loading"
        stripe
        empty-text="没有匹配的账号"
        row-key="id"
      >
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="账号" min-width="180">
          <template #default="{ row }">
            <div class="who">
              <span class="username">{{ row.username }}</span>
              <el-tag v-if="row.id === selfId" size="small" type="info" effect="plain">当前登录</el-tag>
            </div>
            <div class="nickname">{{ row.nickname || '未设置昵称' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="150">
          <template #default="{ row }">
            <el-select
              :model-value="row.role"
              :disabled="row.id === selfId"
              size="small"
              style="width: 118px"
              @change="(v: UserRole) => onRoleChange(row, v)"
            >
              <el-option label="管理员" value="ADMIN" />
              <el-option label="普通用户" value="USER" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              :disabled="row.id === selfId"
              inline-prompt
              active-text="启用"
              inactive-text="禁用"
              @change="(v: boolean) => onStatusChange(row, v ? 1 : 0)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="sessionCount" label="会话数" width="90" align="center" />
        <el-table-column prop="questionCount" label="提问数" width="90" align="center" />
        <el-table-column label="注册时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="warning" @click="onResetPassword(row)">重置密码</el-button>
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

    <el-dialog v-model="dialog.visible" title="新增用户" width="460px">
      <el-form ref="formRef" :model="dialog" :rules="rules" label-width="76px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="dialog.username" maxlength="32" placeholder="登录用的唯一账号名" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input
            v-model="dialog.password"
            type="password"
            show-password
            maxlength="64"
            :placeholder="`告知本人，建议 ${INITIAL_PASSWORD} 或更强`"
          />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="dialog.nickname" maxlength="64" placeholder="展示名，可留空" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="dialog.role">
            <el-radio value="USER">普通用户</el-radio>
            <el-radio value="ADMIN">管理员</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.user-manage {
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

.who {
  display: flex;
  align-items: center;
  gap: 8px;
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

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
