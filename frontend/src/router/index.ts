import { createRouter, createWebHistory } from 'vue-router'

import ChatView from '@/views/ChatView.vue'
import { useUserStore } from '@/stores/user'
import { isAuthenticated } from '@/utils/auth'

/**
 * 侧栏菜单的唯一数据源就是这张路由表：meta 里写清标题、所属分组、图标名与是否管理员可见，
 * App.vue 直接按角色过滤渲染，不再维护第二份菜单数组（两处列表迟早会对不上）。
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 侧栏分组标题 */
    group?: string
    /** 仅管理员可见（后端接口同样会 403 兜底） */
    adminOnly?: boolean
    /** 侧栏图标名：main.ts 已全局注册 Element Plus 图标，模板里用 <component :is> 取 */
    icon?: string
    /** 登录页等不套系统外壳的页面 */
    bare?: boolean
    /** 聊天页需要占满视口高度、由内部自己滚动 */
    flush?: boolean
    /** 无需登录 */
    public?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录', public: true, bare: true },
    },
    {
      // 管理员落地页；普通用户由守卫改派到智能问答
      path: '/',
      redirect: '/dashboard',
    },
    {
      path: '/dashboard',
      name: 'dashboard',
      // 数据大屏是系统管理主页，异步加载减少主包体积（echarts 单独分包）；仅管理员可见
      component: () => import('@/views/DashboardView.vue'),
      meta: { title: '数据监控大屏', group: '概览', icon: 'Odometer', adminOnly: true },
    },
    {
      path: '/knowledge',
      name: 'knowledge',
      component: () => import('@/views/KnowledgeView.vue'),
      meta: { title: '知识库与文档管理', group: '知识资产', icon: 'Collection', adminOnly: true },
    },
    {
      path: '/Laboratory',
      alias: '/laboratory',
      name: 'laboratory',
      component: () => import('@/views/RetrievalLabView.vue'),
      meta: { title: '检索对比实验室', group: '知识资产', icon: 'DataAnalysis', adminOnly: true },
    },
    {
      path: '/corpus',
      name: 'corpus',
      component: () => import('@/views/ChunkCorpusView.vue'),
      meta: { title: '全局切片语料中心', group: '知识资产', icon: 'Files', adminOnly: true },
    },
    {
      path: '/chat',
      name: 'chat',
      component: ChatView,
      meta: { title: 'AI 智能问答台', group: '问答服务', icon: 'ChatDotRound' },
    },
    {
      path: '/agent',
      name: 'agent',
      component: () => import('@/views/AgentView.vue'),
      meta: { title: '知识库智能体', group: '问答服务', icon: 'Connection' },
    },
    {
      // 模板市场两类角色都进得来：管理员看到全量并可增删改，普通用户只看到上架模板
      path: '/templates',
      name: 'templates',
      component: () => import('@/views/PromptTemplateView.vue'),
      meta: { title: '提示词模板市场', group: '问答服务', icon: 'MagicStick' },
    },
    {
      path: '/feedback',
      name: 'feedback',
      component: () => import('@/views/FeedbackManageView.vue'),
      meta: { title: '问答质量反馈', group: '系统管理', icon: 'ChatLineSquare', adminOnly: true },
    },
    {
      path: '/audit',
      name: 'audit',
      component: () => import('@/views/ChatAuditView.vue'),
      meta: { title: '问答审计日志', group: '系统管理', icon: 'Tickets', adminOnly: true },
    },
    {
      path: '/users',
      name: 'users',
      component: () => import('@/views/UserManageView.vue'),
      meta: { title: '用户与权限管理', group: '系统管理', icon: 'UserFilled', adminOnly: true },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SystemConfigView.vue'),
      meta: { title: '引擎参数配置', group: '系统管理', icon: 'Setting', adminOnly: true },
    },
    {
      // 个人中心不判角色：普通学生登录后除了问答台，这里是他能改昵称、改密码、看统计的地方
      path: '/profile',
      name: 'profile',
      component: () => import('@/views/UserProfileView.vue'),
      meta: { title: '个人中心', group: '账号中心', icon: 'User' },
    },
    {
      // 兼容旧的 /documents 入口，重定向到知识库管理
      path: '/documents',
      redirect: '/knowledge',
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/dashboard',
    },
  ],
})

/** 依据角色决定登录后的落地页：管理员进数据大屏，普通用户直接进智能问答 */
function homeForRole(isAdmin: boolean): string {
  return isAdmin ? '/dashboard' : '/chat'
}

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  // 公开页（登录页）：已登录则不再停留，按角色回主页
  if (to.meta?.public) {
    if (isAuthenticated()) {
      if (!userStore.user) {
        await userStore.restore()
      }
      return homeForRole(userStore.isAdmin)
    }
    return true
  }

  // 受保护页：无 token 一律去登录，并记下来源以便登录后回跳
  if (!isAuthenticated()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 有 token 但用户信息还没加载（如硬刷新）：先校验并拉取，失败会被 401 拦截器清态
  if (!userStore.user) {
    await userStore.restore()
    if (!isAuthenticated()) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // RBAC：管理员专属页面，普通用户访问则回落到智能问答（后端也会 403 兜底）
  if (to.meta?.adminOnly && !userStore.isAdmin) {
    return '/chat'
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta?.title as string | undefined
  document.title = title ? `${title} · 私有知识库问答` : '私有知识库问答'
})

export default router
