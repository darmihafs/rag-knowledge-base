import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchMe, login as loginApi, logout as logoutApi, register as registerApi } from '@/api/auth'
import type { UserVO } from '@/types/api'
import { cacheUser, clearToken, getToken, readCachedUser, setToken } from '@/utils/auth'

/**
 * 登录态单一数据源：token 与用户信息。
 *
 * <p>token 持久化在 localStorage（见 utils/auth），刷新页面后 {@link restore} 会先用本地缓存
 * 立即恢复用户信息（避免导航闪烁），再异步调 /auth/me 校验 token 是否仍有效；失效则由 http
 * 拦截器统一清态跳登录。
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string>(getToken())
  const user = ref<UserVO | null>(readCachedUser<UserVO>())

  const isLoggedIn = computed(() => token.value.length > 0)
  const isAdmin = computed(() => user.value?.role === 'ADMIN')
  const role = computed(() => user.value?.role ?? null)
  const displayName = computed(() => user.value?.nickname || user.value?.username || '')

  function applyAuth(nextToken: string, nextUser: UserVO) {
    token.value = nextToken
    user.value = nextUser
    setToken(nextToken)
    cacheUser(nextUser)
  }

  async function login(username: string, password: string) {
    const result = await loginApi(username, password)
    applyAuth(result.token, result.user)
    return result.user
  }

  async function register(username: string, password: string, nickname?: string) {
    const result = await registerApi(username, password, nickname)
    applyAuth(result.token, result.user)
    return result.user
  }

  /** 刷新页面后校验本地 token 是否仍有效，并拉取最新用户信息 */
  async function restore() {
    if (!token.value) {
      return null
    }
    try {
      const me = await fetchMe()
      user.value = me
      cacheUser(me)
      return me
    } catch {
      // 401 已由拦截器处理（清态 + 跳登录）；这里兜底清一次
      reset()
      return null
    }
  }

  function reset() {
    token.value = ''
    user.value = null
    clearToken()
  }

  async function logout() {
    try {
      await logoutApi()
    } catch {
      // 即便服务端确认失败，本地也要登出
    } finally {
      reset()
    }
  }

  return {
    token,
    user,
    isLoggedIn,
    isAdmin,
    role,
    displayName,
    login,
    register,
    restore,
    logout,
    reset,
  }
})
