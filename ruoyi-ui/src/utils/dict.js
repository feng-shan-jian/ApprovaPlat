import useDictStore from '@/store/modules/dict'

/**
 * 获取字典数据
 */
export function useDict(...args) {
  const result = ref({})
  const store = useDictStore()
  args.forEach(type => {
    result.value[type] = store.getDict(type) || []
    if (store.getDict(type)) return
    store.loadDict(type).then(values => {
      // Invalidation may occur between store completion and this consumer's continuation.
      if (values && store.getDict(type) === values) result.value[type] = values
    }, () => {
      // The HTTP client reports the failure. Keep the empty display and permit the next load.
    })
  })
  return toRefs(result.value)
}
