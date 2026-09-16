import cache from '@/plugins/cache'
import useSettingsStore from '@/store/modules/settings'

const PERSIST_KEY = 'tags-view-visited'

function isPersistEnabled() {
  return useSettingsStore().tagsViewPersist
}

function saveVisitedViews(views) {
  if (!isPersistEnabled()) return
  const toSave = views.filter(v => !(v.meta && v.meta.affix)).map(v => ({ path: v.path, fullPath: v.fullPath, name: v.name, title: v.title, query: v.query, meta: v.meta }))
  cache.local.setJSON(PERSIST_KEY, toSave)
}

function loadVisitedViews() {
  return cache.local.getJSON(PERSIST_KEY) || []
}

function clearVisitedViews() {
  cache.local.remove(PERSIST_KEY)
}

const useTagsViewStore = defineStore(
  'tags-view',
  {
    state: () => ({
      visitedViews: [],
      cachedViews: [],
      iframeViews: []
    }),
    actions: {
      addView(view) {
        this.addVisitedView(view)
        this.addCachedView(view)
      },
      addIframeView(view) {
        if (this.iframeViews.some(v => v.path === view.path)) return
        this.iframeViews.push(
          Object.assign({}, view, {
            title: view.meta.title || 'no-name'
          })
        )
      },
      addVisitedView(view) {
        if (this.visitedViews.some(v => v.path === view.path)) return
        this.visitedViews.push(
          Object.assign({}, view, {
            title: view.meta.title || 'no-name'
          })
        )
        saveVisitedViews(this.visitedViews)
      },
      addAffixView(view) {
        if (this.visitedViews.some(v => v.path === view.path)) return
        this.visitedViews.unshift(
          Object.assign({}, view, {
            title: view.meta.title || 'no-name'
          })
        )
      },
      addCachedView(view) {
        if (this.cachedViews.includes(view.name)) return
        if (!view.meta.noCache) {
          this.cachedViews.push(view.name)
        }
      },
      async delView(view) {
        await this.delVisitedView(view)
        await this.delCachedView(view)
        return {
          visitedViews: [...this.visitedViews],
          cachedViews: [...this.cachedViews]
        }
      },
      async delVisitedView(view) {
        for (const [i, v] of this.visitedViews.entries()) {
          if (v.path === view.path) {
            this.visitedViews.splice(i, 1)
            break
          }
        }
        this.iframeViews = this.iframeViews.filter(item => item.path !== view.path)
        saveVisitedViews(this.visitedViews)
        return [...this.visitedViews]
      },
      async delIframeView(view) {
        this.iframeViews = this.iframeViews.filter(item => item.path !== view.path)
        return [...this.iframeViews]
      },
      async delCachedView(view) {
        const index = this.cachedViews.indexOf(view.name)
        index > -1 && this.cachedViews.splice(index, 1)
        return [...this.cachedViews]
      },
      async delOthersViews(view) {
        await this.delOthersVisitedViews(view)
        await this.delOthersCachedViews(view)
        return {
          visitedViews: [...this.visitedViews],
          cachedViews: [...this.cachedViews]
        }
      },
      async delOthersVisitedViews(view) {
        this.visitedViews = this.visitedViews.filter(v => {
          return v.meta.affix || v.path === view.path
        })
        this.iframeViews = this.iframeViews.filter(item => item.path === view.path)
        saveVisitedViews(this.visitedViews)
        return [...this.visitedViews]
      },
      async delOthersCachedViews(view) {
        const index = this.cachedViews.indexOf(view.name)
        if (index > -1) {
          this.cachedViews = this.cachedViews.slice(index, index + 1)
        } else {
          this.cachedViews = []
        }
        return [...this.cachedViews]
      },
      async delAllViews() {
        await this.delAllVisitedViews()
        await this.delAllCachedViews()
        return {
          visitedViews: [...this.visitedViews],
          cachedViews: [...this.cachedViews]
        }
      },
      async delAllVisitedViews() {
        const affixTags = this.visitedViews.filter(tag => tag.meta.affix)
        this.visitedViews = affixTags
        this.iframeViews = []
        clearVisitedViews()
        return [...this.visitedViews]
      },
      async delAllCachedViews() {
        this.cachedViews = []
        return [...this.cachedViews]
      },
      updateVisitedView(view) {
        for (let v of this.visitedViews) {
          if (v.path === view.path) {
            v = Object.assign(v, view)
            break
          }
        }
      },
      async delRightTags(view) {
        const index = this.visitedViews.findIndex(v => v.path === view.path)
        if (index === -1) {
          return [...this.visitedViews]
        }
        this.visitedViews = this.visitedViews.filter((item, idx) => {
          if (idx <= index || (item.meta && item.meta.affix)) {
            return true
          }
          const i = this.cachedViews.indexOf(item.name)
          if (i > -1) {
            this.cachedViews.splice(i, 1)
          }
          if(item.meta.link) {
            const fi = this.iframeViews.findIndex(v => v.path === item.path)
            this.iframeViews.splice(fi, 1)
          }
          return false
        })
        saveVisitedViews(this.visitedViews)
        return [...this.visitedViews]
      },
      async delLeftTags(view) {
        const index = this.visitedViews.findIndex(v => v.path === view.path)
        if (index === -1) {
          return [...this.visitedViews]
        }
        this.visitedViews = this.visitedViews.filter((item, idx) => {
          if (idx >= index || (item.meta && item.meta.affix)) {
            return true
          }
          const i = this.cachedViews.indexOf(item.name)
          if (i > -1) {
            this.cachedViews.splice(i, 1)
          }
          if(item.meta.link) {
            const fi = this.iframeViews.findIndex(v => v.path === item.path)
            this.iframeViews.splice(fi, 1)
          }
          return false
        })
        saveVisitedViews(this.visitedViews)
        return [...this.visitedViews]
      },
      // 恢复持久化的 tags
      loadPersistedViews() {
        const views = loadVisitedViews()
        views.forEach(view => {
          this.addVisitedView(view)
        })
      }
    }
  })

export default useTagsViewStore
