<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { ChatDotRound, Document, EditPen, Refresh, Tickets } from '@element-plus/icons-vue'

import { fetchMyStats, updatePassword, updateProfile } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import type { MyStatsVO } from '@/types/api'

/**
 * 个人中心与安全设置：所有角色可见，是普通学生登录后除了问答台之外的第二个落脚点。
 *
 * <p>三个接口都只认 JWT 里的身份：改昵称（{@code PUT /api/auth/profile}）、改密码
 * （{@code PUT /api/auth/password}）、使用统计（{@code GET /api/auth/my-stats}）。
 * 前端不传 userId，后端一律回表校验「只能改自己」，因此这里不存在越权入口。
 *
 * <p>改密成功后本地立即清态并要求重新登录：JWT 无状态，旧 token 在有效期内技术上仍可用，
 * 让用户用新密码重新拿一张是最省事且最安全的收尾。
 */

const userStore = useUserStore()
const router = useRouter()

const user = computed(() => userStore.user)
const stats = ref<MyStatsVO | null>(null)
const statsLoading = ref(false)

async function loadStats() {
  statsLoading.value = true
  try {
    stats.value = await fetchMyStats()
  } catch {
    /* 拦截器已提示 */
  } finally {
    statsLoading.value = false
  }
}

onMounted(loadStats)

const metrics = computed(() => [
  { label: '累计会话', value: stats.value?.sessionCount ?? 0, icon: ChatDotRound },
  { label: '累计提问', value: stats.value?.questionCount ?? 0, icon: Tickets },
  { label: '收到回答', value: stats.value?.answerCount ?? 0, icon: Document },
  { label: '提交反馈', value: stats.value?.feedbackCount ?? 0, icon: EditPen },
])

function formatTime(value: string | null | undefined) {
  return (value ?? '').replace('T', ' ').slice(0, 19)
}

// ------------------------------------------------------------------ 基本资料

const profileForm = ref<FormInstance>()
const profile = reactive({ nickname: '' })

/**
 * 用当前登录用户信息回填昵称。
 *
 * <p>硬刷新时用户信息要先经 restore() 才到位，保存资料后本地缓存也会更新，
 * 因此进页面和保存成功都同步一次，输入框不会停在旧值上。
 */
function syncNickname() {
  profile.nickname = userStore.user?.nickname ?? ''
}
syncNickname()

const profileRules: FormRules = {
  nickname: [
    { required: true, message: '请填写昵称', trigger: 'blur' },
    { max: 32, message: '昵称最多 32 字', trigger: 'blur' },
  ],
}

const profileSubmitting = ref(false)

async function submitProfile() {
  const ok = await profileForm.value?.validate().catch(() => false)
  if (!ok) return
  profileSubmitting.value = true
  try {
    await updateProfile(profile.nickname.trim())
    // 后端返回最新用户信息，用 restore 刷新本地缓存，顶栏与侧栏的名字一起更新
    await userStore.restore()
    syncNickname()
    ElMessage.success('资料已保存')
  } catch {
    /* 拦截器已提示 */
  } finally {
    profileSubmitting.value = false
  }
}

// ------------------------------------------------------------------ 修改密码

const passwordForm = ref<FormInstance>()
const password = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const passwordSubmitting = ref(false)

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '新密码长度需为 6-32 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== password.newPassword) {
          callback(new Error('两次输入的新密码不一致'))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

async function submitPassword() {
  const ok = await passwordForm.value?.validate().catch(() => false)
  if (!ok) return
  passwordSubmitting.value = true
  try {
    await updatePassword({
      oldPassword: password.oldPassword,
      newPassword: password.newPassword,
    })
    passwordSubmitting.value = false
    // 清掉表单内容，别让新密码留在 DOM 里
    password.oldPassword = ''
    password.newPassword = ''
    password.confirmPassword = ''
    await ElMessageBox.alert('密码已修改，请使用新密码重新登录。', '修改成功', {
      type: 'success',
      confirmButtonText: '去重新登录',
      showCancelButton: false,
      closeOnClickModal: false,
    }).catch(() => undefined)
    userStore.reset()
    await router.replace('/login')
  } catch {
    /* 原密码错误等业务码由拦截器提示 */
  } finally {
    passwordSubmitting.value = false
  }
}
</script>

<template>
  <div class="profile">
    <div class="profile-grid">
      <!-- 左：身份卡 + 使用统计 -->
      <div class="profile-left">
        <el-card shadow="never" class="block">
          <div class="id-card">
            <el-avatar :size="56" class="id-avatar">{{ (user?.nickname || user?.username || '用').slice(0, 1) }}</el-avatar>
            <div class="id-name">{{ userStore.displayName || '未登录' }}</div>
            <div class="id-account">@{{ user?.username }}</div>
            <el-tag :type="userStore.isAdmin ? 'danger' : 'info'" effect="plain" size="small">
              {{ userStore.isAdmin ? '管理员' : '普通用户' }}
            </el-tag>
          </div>
          <div class="id-foot">
            <span class="id-label">注册时间</span>
            <span class="id-value">{{ formatTime(stats?.registerTime) || '—' }}</span>
          </div>
        </el-card>

        <el-card shadow="never" class="block" v-loading="statsLoading">
          <template #header>
            <div class="card-header">
              <span>我的使用统计</span>
              <el-button size="small" :icon="Refresh" @click="loadStats">刷新</el-button>
            </div>
          </template>
          <div class="metrics">
            <div v-for="item in metrics" :key="item.label" class="metric">
              <div class="metric-icon">
                <el-icon :size="16"><component :is="item.icon" /></el-icon>
              </div>
              <div>
                <div class="metric-value">{{ item.value }}</div>
                <div class="metric-label">{{ item.label }}</div>
              </div>
            </div>
          </div>
          <div class="badcase-line">
            其中「需改进」反馈
            <b>{{ stats?.badCaseCount ?? 0 }}</b>
            条，已同步给管理员作为 BadCase 工单处理。
          </div>
        </el-card>
      </div>

      <!-- 右：两张表单 -->
      <div class="profile-right">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>基本资料修改</span>
              <span class="hint">PUT /api/auth/profile</span>
            </div>
          </template>
          <el-form ref="profileForm" :model="profile" :rules="profileRules" label-width="88px">
            <el-form-item label="用户名">
              <el-input :model-value="user?.username" disabled />
              <div class="field-hint">登录账号不可修改，如需变更请联系管理员。</div>
            </el-form-item>
            <el-form-item label="昵称" prop="nickname">
              <el-input
                v-model="profile.nickname"
                maxlength="32"
                show-word-limit
                placeholder="展示名，问答页与顶栏都会显示它"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="profileSubmitting" @click="submitProfile">
                保存资料
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>登录密码修改</span>
              <span class="hint">PUT /api/auth/password</span>
            </div>
          </template>
          <el-form ref="passwordForm" :model="password" :rules="passwordRules" label-width="88px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input
                v-model="password.oldPassword"
                type="password"
                show-password
                autocomplete="current-password"
                placeholder="校验身份，后端比对的是库里的密文"
              />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input
                v-model="password.newPassword"
                type="password"
                show-password
                maxlength="32"
                autocomplete="new-password"
                placeholder="6-32 位，不能与原密码相同"
              />
            </el-form-item>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input
                v-model="password.confirmPassword"
                type="password"
                show-password
                maxlength="32"
                autocomplete="new-password"
                placeholder="再输入一次，防止手滑"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="passwordSubmitting" @click="submitPassword">
                修改密码
              </el-button>
              <span class="field-hint inline">改完需要用新密码重新登录。</span>
            </el-form-item>
          </el-form>
        </el-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.profile {
  max-width: 1200px;
  margin: 0 auto;
}

.profile-grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 16px;
  align-items: start;
}

.profile-left,
.profile-right {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.block {
  border-radius: 10px;
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
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.id-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 6px 0 14px;
}

.id-avatar {
  background: var(--el-color-primary-light-7);
  color: var(--el-color-primary);
  font-weight: 600;
}

.id-name {
  font-size: 16px;
  font-weight: 600;
}

.id-account {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.id-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
}

.id-label {
  color: var(--el-text-color-secondary);
}

.id-value {
  color: var(--el-text-color-regular);
  font-family: ui-monospace, Menlo, Consolas, monospace;
}

.metrics {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.metric {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.metric-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.metric-value {
  font-size: 18px;
  font-weight: 600;
  line-height: 1.2;
}

.metric-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.badcase-line {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.field-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.field-hint.inline {
  margin-left: 10px;
}

@media (max-width: 900px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}
</style>
