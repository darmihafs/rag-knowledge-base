import { ref } from 'vue'
import { defineStore } from 'pinia'

import { createSpace, deleteSpace, fetchAllSpaces, fetchVisibleSpaces, updateSpace } from '@/api/space'
import type { SpaceVO } from '@/types/api'

/**
 * 知识库空间的前端单一数据源：侧栏一次性加载全部空间，维护当前选中空间。
 * 选中空间变化后由知识库管理页据此过滤文档列表。
 *
 * <p>两份列表刻意分开：{@link spaces} 走 {@code /spaces/all}（管理员专属，给知识库管理页用），
 * {@link accessibleSpaces} 走 {@code /spaces/visible}（登录级，普通用户也有）。
 * 问答页必须用后者——用前者普通用户直接 403，用后者的管理员则等于全库；
 * 而后端返回什么是由它的角色决定的，两边拿到的都是「自己真能检索到的范围」。
 */
export const useSpaceStore = defineStore('space', () => {
  const spaces = ref<SpaceVO[]>([])
  const loading = ref(false)
  /** 当前选中空间 ID（知识库管理页的过滤条件），默认选中 id=1 的默认知识库 */
  const activeSpaceId = ref<number>(1)

  /** 当前账号可读的空间：管理员=全部，普通用户=有成员关系的那些 */
  const accessibleSpaces = ref<SpaceVO[]>([])
  const accessibleLoading = ref(false)

  async function loadSpaces() {
    loading.value = true
    try {
      spaces.value = await fetchAllSpaces()
      // 若当前选中空间已不存在（被删），回落到第一个空间
      if (!spaces.value.some((s) => s.id === activeSpaceId.value) && spaces.value.length > 0) {
        activeSpaceId.value = spaces.value[0].id
      }
      return spaces.value
    } finally {
      loading.value = false
    }
  }

  /** 拉取「我可读」的空间列表，供问答页的空间选择器使用 */
  async function loadAccessibleSpaces() {
    accessibleLoading.value = true
    try {
      accessibleSpaces.value = await fetchVisibleSpaces()
      return accessibleSpaces.value
    } finally {
      accessibleLoading.value = false
    }
  }

  async function create(name: string, description?: string) {
    const id = await createSpace({ name, description })
    await Promise.all([loadSpaces(), loadAccessibleSpaces()])
    return id
  }

  async function rename(id: number, name: string, description?: string) {
    await updateSpace(id, { name, description })
    await Promise.all([loadSpaces(), loadAccessibleSpaces()])
  }

  async function remove(id: number) {
    await deleteSpace(id)
    await Promise.all([loadSpaces(), loadAccessibleSpaces()])
  }

  function setActive(id: number) {
    activeSpaceId.value = id
  }

  return {
    spaces,
    loading,
    activeSpaceId,
    accessibleSpaces,
    accessibleLoading,
    loadSpaces,
    loadAccessibleSpaces,
    create,
    rename,
    remove,
    setActive,
  }
})
