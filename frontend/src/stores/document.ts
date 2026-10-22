import { ref } from 'vue'
import { defineStore } from 'pinia'

import { fetchDocuments, fetchHealth, removeDocument, uploadDocument } from '@/api/kb'
import type { DocumentVO, HealthInfo } from '@/types/api'

/**
 * 文档 + 后端状态的前端单一数据源：视图只管渲染，请求与 loading 都收敛在这里。
 * 支持按知识库空间（spaceId）过滤，upload 也带上目标空间。
 */
export const useDocumentStore = defineStore('document', () => {
  const health = ref<HealthInfo | null>(null)
  const healthLoading = ref(false)
  const healthCheckedAt = ref<string>('')

  const documents = ref<DocumentVO[]>([])
  const total = ref(0)
  const pageNum = ref(1)
  const pageSize = ref(10)
  const listLoading = ref(false)
  /** 当前列表所属空间；null 表示不限空间（全部文档） */
  const spaceId = ref<number | null>(null)

  const uploading = ref(false)
  const uploadPercent = ref(0)

  async function loadHealth() {
    healthLoading.value = true
    try {
      health.value = await fetchHealth()
      healthCheckedAt.value = new Date().toLocaleTimeString()
      return health.value
    } finally {
      healthLoading.value = false
    }
  }

  async function loadDocuments(page = pageNum.value, size = pageSize.value) {
    listLoading.value = true
    try {
      const pageResult = await fetchDocuments({
        pageNum: page,
        pageSize: size,
        spaceId: spaceId.value,
      })
      documents.value = pageResult.records ?? []
      total.value = pageResult.total ?? 0
      pageNum.value = pageResult.current ?? page
      pageSize.value = pageResult.size ?? size
    } finally {
      listLoading.value = false
    }
  }

  /** 切换空间后从第一页重新加载 */
  async function loadBySpace(id: number | null) {
    spaceId.value = id
    await loadDocuments(1, pageSize.value)
  }

  async function upload(file: File, targetSpaceId?: number | null) {
    uploading.value = true
    uploadPercent.value = 0
    try {
      return await uploadDocument(
        file,
        (percent: number) => {
          uploadPercent.value = percent
        },
        targetSpaceId ?? spaceId.value,
      )
    } finally {
      uploading.value = false
    }
  }

  async function remove(id: number) {
    await removeDocument(id)
    await loadDocuments()
  }

  return {
    health,
    healthLoading,
    healthCheckedAt,
    documents,
    total,
    pageNum,
    pageSize,
    listLoading,
    spaceId,
    uploading,
    uploadPercent,
    loadHealth,
    loadDocuments,
    loadBySpace,
    upload,
    remove,
  }
})
