import { computed, onScopeDispose, ref } from 'vue'

// 每个选择器独占请求和标签缓存；资格范围由调用方选择的正式接口决定。
export function useWorkflowUserOptions(fetchOptions, selectedValues, { pageSize = 30 } = {}) {
  const loading = ref(false)
  const results = ref([])
  const cache = new Map()
  let sequence = 0

  const options = computed(() => {
    const merged = new Map()
    selectedValues().map(String).forEach(value => {
      if (cache.has(value)) merged.set(value, cache.get(value))
    })
    results.value.forEach(option => merged.set(option.value, option))
    return [...merged.values()]
  })

  async function search(keyword) {
    const current = ++sequence
    loading.value = true
    try {
      const response = await fetchOptions({
        keyword: String(keyword || '').trim().slice(0, 64), pageNum: 1, pageSize
      })
      if (current !== sequence) return
      const rows = (Array.isArray(response.rows) ? response.rows : [])
        .filter(option => option?.type === 'user')
        .map(option => ({ ...option, value: String(option.value), label: String(option.label || '') }))
      rows.forEach(option => cache.set(option.value, option))
      results.value = rows
    } finally {
      if (current === sequence) loading.value = false
    }
  }

  function reset({ keepSelected = false } = {}) {
    sequence++
    const selected = new Set(keepSelected ? selectedValues().map(String) : [])
    for (const value of cache.keys()) {
      if (!selected.has(value)) cache.delete(value)
    }
    results.value = []
    loading.value = false
  }

  onScopeDispose(reset)
  return { options, loading, search, reset }
}
