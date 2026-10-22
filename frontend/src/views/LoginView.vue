<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'

import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const activeTab = ref<'login' | 'register'>('login')
const loading = ref(false)

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive({ username: '', password: '', confirm: '', nickname: '' })

const loginRef = ref<FormInstance>()
const registerRef = ref<FormInstance>()

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3-32 位字母、数字或下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度 6-32 位', trigger: 'blur' },
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (_rule, value, callback) =>
        value === registerForm.password ? callback() : callback(new Error('两次输入的密码不一致')),
      trigger: 'blur',
    },
  ],
}

/** 登录成功后回到来源页（路由守卫带的 redirect），默认回首页 */
function afterAuth() {
  const redirect = route.query.redirect as string | undefined
  router.replace(redirect && redirect.startsWith('/') ? redirect : '/')
}

async function handleLogin() {
  const ok = await loginRef.value?.validate().catch(() => false)
  if (!ok) {
    return
  }
  loading.value = true
  try {
    const user = await userStore.login(loginForm.username.trim(), loginForm.password)
    ElMessage.success(`欢迎回来，${user.nickname || user.username}`)
    afterAuth()
  } catch {
    /* 错误提示已由 http 拦截器弹出 */
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  const ok = await registerRef.value?.validate().catch(() => false)
  if (!ok) {
    return
  }
  loading.value = true
  try {
    const user = await userStore.register(
      registerForm.username.trim(),
      registerForm.password,
      registerForm.nickname.trim() || undefined,
    )
    ElMessage.success(`注册成功，已为你登录（${user.nickname || user.username}）`)
    afterAuth()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

/** 一键填充体验账号 */
function fillAdmin() {
  activeTab.value = 'login'
  loginForm.username = 'admin'
  loginForm.password = 'admin123'
}

function fillStudent() {
  activeTab.value = 'login'
  loginForm.username = 'student'
  loginForm.password = '123456'
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-head">
        <h1>私有知识库问答系统</h1>
        <p>基于你自己文档的开卷问答 · 语义 + 关键词双路召回</p>
      </div>

      <el-tabs v-model="activeTab" class="login-tabs" stretch>
        <el-tab-pane label="账号登录" name="login">
          <el-form
            ref="loginRef"
            :model="loginForm"
            :rules="loginRules"
            label-position="top"
            size="large"
            @submit.prevent="handleLogin"
          >
            <el-form-item label="用户名" prop="username">
              <el-input v-model="loginForm.username" :prefix-icon="User" placeholder="请输入用户名" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input
                v-model="loginForm.password"
                :prefix-icon="Lock"
                type="password"
                placeholder="请输入密码"
                show-password
                @keyup.enter="handleLogin"
              />
            </el-form-item>
            <el-button type="primary" class="submit-btn" :loading="loading" @click="handleLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="新用户注册" name="register">
          <el-form
            ref="registerRef"
            :model="registerForm"
            :rules="registerRules"
            label-position="top"
            size="large"
            @submit.prevent="handleRegister"
          >
            <el-form-item label="用户名" prop="username">
              <el-input v-model="registerForm.username" :prefix-icon="User" placeholder="3-32 位字母/数字/下划线" clearable />
            </el-form-item>
            <el-form-item label="昵称（可选）">
              <el-input v-model="registerForm.nickname" placeholder="不填则默认用用户名" clearable />
            </el-form-item>
            <el-form-item label="密码" prop="password">
              <el-input v-model="registerForm.password" :prefix-icon="Lock" type="password" placeholder="6-32 位" show-password />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirm">
              <el-input
                v-model="registerForm.confirm"
                :prefix-icon="Lock"
                type="password"
                placeholder="再次输入密码"
                show-password
                @keyup.enter="handleRegister"
              />
            </el-form-item>
            <el-button type="primary" class="submit-btn" :loading="loading" @click="handleRegister">
              注册并登录
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <div class="login-foot">
        <span class="foot-label">体验账号（点击一键填充）：</span>
        <el-button link type="primary" @click="fillAdmin">管理员 admin / admin123</el-button>
        <el-divider direction="vertical" />
        <el-button link type="success" @click="fillStudent">学生 student / 123456</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 20% -10%, var(--el-color-primary-light-8), transparent),
    radial-gradient(1000px 500px at 100% 110%, var(--el-color-success-light-9), transparent),
    var(--el-bg-color-page);
}

.login-card {
  width: 100%;
  max-width: 420px;
  padding: 32px 32px 24px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.08);
}

.login-head {
  text-align: center;
  margin-bottom: 8px;
}

.login-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
}

.login-head p {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.login-tabs {
  margin-top: 8px;
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.login-foot {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 12px;
}

.foot-label {
  color: var(--el-text-color-secondary);
}
</style>
