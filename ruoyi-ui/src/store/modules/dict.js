import { getDicts } from '@/api/system/dict/data'

// Request ownership is per store instance; promises are not persisted as Pinia state.
const pendingLoads = new WeakMap()

function loadsFor(store) {
  if (!pendingLoads.has(store)) pendingLoads.set(store, new Map())
  return pendingLoads.get(store)
}

const useDictStore = defineStore('dict', {
  state: () => ({ dict: [] }),
  actions: {
    getDict(key) {
      if (key == null || key === '') return null
      return this.dict.find(item => item.key === key)?.value
    },
    setDict(key, value) {
      if (key == null || key === '') return
      this.removeDict(key)
      this.dict.push({ key, value })
    },
    removeDict(key) {
      loadsFor(this).delete(key)
      const before = this.dict.length
      this.dict = this.dict.filter(item => item.key !== key)
      return this.dict.length !== before
    },
    cleanDict() {
      loadsFor(this).clear()
      this.dict = []
    },
    loadDict(key) {
      if (key == null || key === '') return Promise.resolve(undefined)
      const cached = this.getDict(key)
      if (cached) return Promise.resolve(cached)
      const pending = loadsFor(this)
      if (pending.has(key)) return pending.get(key)
      const request = getDicts(key).then(response => {
        if (pending.get(key) !== request) return undefined
        const values = response.data.map(item => ({ label: item.dictLabel, value: item.dictValue,
          elTagType: item.listClass, elTagClass: item.cssClass }))
        this.setDict(key, values)
        return this.getDict(key)
      }).finally(() => {
        if (pending.get(key) === request) pending.delete(key)
      })
      pending.set(key, request)
      return request
    }
  }
})

export default useDictStore
