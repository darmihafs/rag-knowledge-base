<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  CircleCheck,
  CircleClose,
  Collection,
  Warning,
  SwitchButton,
  UserFilled,
} from '@element-plus/icons-vue'

import { fetchHealth } from '@/api/kb'
import type { HealthInfo } from '@/types/api'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 登录页等 bare 页面不套系统外壳（无侧栏顶栏），直接整屏渲染 */
const bare = computed(() => route.meta?.bare === true)
/** 聊天页需要占满视口高度、由内部自己滚动；其余页面沿用普通页面流 */
const flush = computed(() => route.meta?.flush === true)
const isAdmin = computed(() => userStore.isAdmin)

/** 侧栏折叠状态：管理员页面多、屏幕窄时收起来只看图标 */
const collapsed = ref(false)

// ---------------------------------------------------------------------- 侧栏菜单（派生自路由表）

interface MenuLeaf {
  path: string
  title: string
  icon: string
  name: symbol | string
}

interface MenuGroup {
  group: string
  items: MenuLeaf[]
}

/**
 * 菜单不另写一份数组，直接从路由表派生：带上 meta.group 的才是后台页面，
 * 再按角色过滤掉 adminOnly，普通用户自然只剩「问答服务」一组。
 */
const menuGroups = computed<MenuGroup[]>(() => {
  const groups: MenuGroup[] = []
  const walk = (routes: readonly RouteRecordRaw[]) => {
    for (const item of routes) {
      if (item.meta?.group) {
        if (item.meta.adminOnly && !isAdmin.value) {
          continue
        }
        // /Laboratory 配了 /laboratory 别名，菜单固定用声明路径，避免同一页出现两个入口
        const path = item.path
        const leaf: MenuLeaf = {
          path,
          title: item.meta.title ?? path,
          icon: item.meta.icon ?? '',
          name: item.name ?? path,
        }
        const group = item.meta.group
        const hit = groups.find((g) => g.group === group)
        if (hit) {
          hit.items.push(leaf)
        } else {
          groups.push({ group, items: [leaf] })
        }
      }
      if (item.children?.length) {
        walk(item.children)
      }
    }
  }
  walk(router.options.routes)
  return groups
})

/** 当前高亮项：按路由 name 比对，别名路径（/laboratory）也能点亮 */
const activePath = computed(() => {
  for (const group of menuGroups.value) {
    const hit = group.items.find((item) => item.name === route.name)
    if (hit) {
      return hit.path
    }
  }
  return route.path
})

const currentTitle = computed(() => route.meta?.title ?? '')

// ---------------------------------------------------------------------- 顶部系统状态徽标

const health = ref<HealthInfo | null>(null)
const healthLoading = ref(false)

/**
 * 徽标口径来自后端 /api/health（白名单接口，普通用户也能读）：
 * 存活且已配置密钥才算「服务正常」，密钥缺失只是能跑但不能生成，标成「降级」。
 */
const healthState = computed(() => {
  if (!health.value) {
    return { label: '状态未知', type: 'info' as const, icon: Warning }
  }
  if (health.value.status !== 'UP') {
    return { label: '服务异常', type: 'danger' as const, icon: CircleClose }
  }
  if (!health.value.apiKeyConfigured) {
    return { label: '未配置密钥', type: 'warning' as const, icon: Warning }
  }
  return { label: '服务正常', type: 'success' as const, icon: CircleCheck }
})

async function loadHealth() {
  healthLoading.value = true
  try {
    health.value = await fetchHealth()
  } catch {
    health.value = null
  } finally {
    healthLoading.value = false
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '退出确认', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  await userStore.logout()
  router.replace('/login')
}

/** 顶栏用户下拉的命令分发：退出登录要清态，其余只是站内跳转 */
function handleCommand(command: string) {
  if (command === 'logout') {
    void handleLogout()
    return
  }
  const target = command === 'profile' ? '/profile' : command === 'knowledge' ? '/knowledge' : ''
  if (!target) {
    ElMessage.info('没有可跳转的后台页面')
    return
  }
  router.push(target)
}

onMounted(() => {
  loadHealth()
})
</script>

<template>
  <!-- 登录页：不显示系统外壳 -->
  <router-view v-if="bare" />

  <div v-else class="admin-shell">
    <!-- 左侧常驻深色导航栏 -->
    <aside class="side" :class="{ 'side--collapsed': collapsed }">
      <div class="side-brand">
        <el-icon :size="22" color="var(--el-color-primary)"><Collection /></el-icon>
        <span v-show="!collapsed" class="side-brand-text">知识库问答后台</span>
      </div>

      <el-scrollbar class="side-scroll">
        <el-menu
          :default-active="activePath"
          :collapse="collapsed"
          :collapse-transition="false"
          router
          class="side-menu"
          background-color="var(--nav-bg)"
          text-color="var(--nav-text)"
          active-text-color="var(--nav-text-active)"
        >
          <el-menu-item-group v-for="group in menuGroups" :key="group.group" :title="group.group">
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon || 'Menu'" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </el-menu-item-group>
        </el-menu>
      </el-scrollbar>

      <div class="side-foot">
        <el-button text class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon>
            <component :is="collapsed ? ArrowRight : ArrowLeft" />
          </el-icon>
          <span v-show="!collapsed">收起菜单</span>
        </el-button>
      </div>
    </aside>

    <div class="body">
      <!-- 右侧白底顶栏：面包屑 + 系统状态 + 当前用户 -->
      <header class="top">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="route.meta?.group">{{ route.meta.group }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
        </el-breadcrumb>

        <div class="top-right">
          <el-tag :type="healthState.type" effect="light" round class="status-chip">
            <el-icon class="status-icon"><component :is="healthState.icon" /></el-icon>
            {{ healthState.label }}
            <span v-if="health" class="status-model">{{ health.chatModel }}</span>
          </el-tag>

          <el-dropdown trigger="click" @command="handleCommand">
            <span class="user-chip">
              <el-icon><UserFilled /></el-icon>
              <span class="user-name">{{ userStore.displayName || '未登录' }}</span>
              <el-tag :type="isAdmin ? 'danger' : 'info'" effect="plain" size="small" class="role-tag">
                {{ isAdmin ? '管理员' : '普通用户' }}
              </el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  账号：{{ userStore.user?.username }}
                </el-dropdown-item>
                <!-- 个人中心两类角色都进得来：普通学生的第二个落脚点 -->
                <el-dropdown-item :icon="UserFilled" command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item v-if="isAdmin" :icon="Collection" command="knowledge">
                  前往知识库管理
                </el-dropdown-item>
                <el-dropdown-item divided command="logout" :icon="SwitchButton">
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="page" :class="{ 'page--flush': flush }">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--el-bg-color-page);
}

/* ------------------------------------------------------------------ 左侧深色导航 */

.side {
  /* 深色导航的色值令牌集中在这里：#001529 是本次改造明确指定的深蓝黑，
     也是全项目唯一允许出现的自定义底色，其余一律走 Element Plus 变量或其透明度派生 */
  --nav-bg: #001529;
  --nav-border: rgba(255, 255, 255, 0.08);
  --nav-hover: rgba(255, 255, 255, 0.06);
  --nav-text: #a6adbb;
  --nav-text-dim: rgba(166, 173, 187, 0.6);
  --nav-text-active: var(--el-color-white);

  display: flex;
  flex-direction: column;
  width: 220px;
  flex-shrink: 0;
  background: var(--nav-bg);
  transition: width 0.2s ease;
}

.side--collapsed {
  width: 64px;
}

.side-brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 16px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--nav-border);
  white-space: nowrap;
  overflow: hidden;
}

.side-brand-text {
  font-size: 15px;
  font-weight: 600;
  color: var(--nav-text-active);
}

.side-scroll {
  flex: 1;
  min-height: 0;
}

.side-menu {
  border-right: none;
  width: 100%;
}

/* 分组标题：深色底上压暗一档，配一条细分隔线，收起时自动只剩间距 */
.side-menu :deep(.el-menu-item-group__title) {
  padding: 12px 16px 6px;
  font-size: 12px;
  color: var(--nav-text-dim);
  letter-spacing: 1px;
}

.side-menu :deep(.el-menu-item) {
  height: 44px;
  line-height: 44px;
}

.side-menu :deep(.el-menu-item:hover) {
  background: var(--nav-hover);
}

.side-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary);
  color: var(--nav-text-active);
  font-weight: 600;
}

.side-foot {
  flex-shrink: 0;
  border-top: 1px solid var(--nav-border);
  padding: 8px;
}

.collapse-btn {
  width: 100%;
  justify-content: flex-start;
  color: var(--nav-text);
  gap: 6px;
}

.collapse-btn:hover {
  color: var(--nav-text-active);
}

/* ------------------------------------------------------------------ 右侧内容区 */

.body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 56px;
  padding: 0 20px;
  flex-shrink: 0;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}

.top-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.status-icon {
  margin-right: 2px;
}

.status-model {
  margin-left: 6px;
  font-size: 12px;
  opacity: 0.75;
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  color: var(--el-text-color-regular);
  outline: none;
  transition: background 0.2s;
}

.user-chip:hover {
  background: var(--el-fill-color-light);
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.role-tag {
  margin-left: 2px;
}

.page {
  flex: 1;
  min-height: 0;
  padding: 20px 24px 40px;
  overflow-y: auto;
}

/* 聊天页：不留大内边距，交给 ChatView 自己撑满并内部滚动 */
.page--flush {
  display: flex;
  flex-direction: column;
  padding: 12px 16px;
  overflow: hidden;
}

.page--flush > * {
  flex: 1;
  min-height: 0;
}

@media (max-width: 900px) {
  .user-name,
  .role-tag,
  .status-model {
    display: none;
  }
}
</style>
