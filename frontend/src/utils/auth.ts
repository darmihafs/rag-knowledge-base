/**
 * 登录态的底层读写工具：token 持久化在 localStorage，供 http 拦截器、SSE 流、user store 共用。
 *
 * <p>单独抽出来而不是直接放 store，是为了避免「http.ts -> store -> api -> http.ts」的循环依赖：
 * 拦截器只依赖这个无副作用的小模块即可拿到 token。
 */
const TOKEN_KEY = 'kb_token'
const USER_KEY = 'kb_user'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/** 把用户信息缓存到本地，刷新页面时先渲染再异步校验，避免白屏闪烁 */
export function cacheUser(user: unknown): void {
  if (user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  }
}

export function readCachedUser<T>(): T | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

/** 已登录（本地存在 token）——仅做路由守卫的初判，真正的有效性由后端 401 兜底 */
export function isAuthenticated(): boolean {
  return getToken().length > 0
}

/**
 * 401 时跳转到登录页。直接用 window.location 而非 router，避免在拦截器里引入 router 造成循环依赖；
 * 带上 redirect 参数，登录后可回到原页面。
 */
export function redirectToLogin(): void {
  if (window.location.pathname === '/login') {
    return
  }
  const redirect = encodeURIComponent(window.location.pathname + window.location.search)
  window.location.href = `/login?redirect=${redirect}`
}
