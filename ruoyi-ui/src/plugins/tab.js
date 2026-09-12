import useTagsViewStore from '@/store/modules/tagsView'
import router from '@/router'
import { confirmPageLeave } from '@/utils/pageLeaveGuards'

async function protectViews(views) {
  return confirmPageLeave(views.map(view => view.path))
}

function cancelledClose() {
  return { cancelled: true, visitedViews: [...useTagsViewStore().visitedViews] }
}

export default {
  // 刷新当前tab页签
  async refreshPage(obj) {
    const { path, query, matched } = router.currentRoute.value
    // 防止在重定向过程中重复刷新
    if (path.startsWith('/redirect/')) {
      return Promise.resolve()
    }
    if (obj === undefined) {
      matched.forEach((m) => {
        if (m.components && m.components.default && m.components.default.name) {
          if (!['Layout', 'ParentView'].includes(m.components.default.name)) {
            obj = { name: m.components.default.name, path: path, query: query }
          }
        }
      })
    }
    if (!await protectViews([obj])) return cancelledClose()
    return useTagsViewStore().delCachedView(obj).then(() => {
      const { path, query } = obj
      router.replace({
        path: '/redirect' + path,
        query: query
      })
    })
  },
  // 关闭当前tab页签，打开新页签
  async closeOpenPage(obj) {
    const current = router.currentRoute.value
    if (!await protectViews([current])) return cancelledClose()
    if (obj !== undefined) {
      const failure = await router.push(obj)
      if (failure) return cancelledClose()
    }
    return useTagsViewStore().delView(current)
  },
  // 关闭指定tab页签
  async closePage(obj) {
    if (!await protectViews([obj || router.currentRoute.value])) return cancelledClose()
    if (obj === undefined) {
      return useTagsViewStore().delView(router.currentRoute.value).then(({ visitedViews }) => {
        const latestView = visitedViews.slice(-1)[0]
        if (latestView) {
          return router.push(latestView.fullPath)
        }
        return router.push('/')
      })
    }
    return useTagsViewStore().delView(obj)
  },
  // 关闭所有tab页签
  async closeAllPage() {
    if (!await protectViews(useTagsViewStore().visitedViews.filter(view => !view.meta?.affix))) return cancelledClose()
    return useTagsViewStore().delAllViews()
  },
  // 关闭左侧tab页签
  async closeLeftPage(obj) {
    const views = useTagsViewStore().visitedViews
    const index = views.findIndex(view => view.path === (obj || router.currentRoute.value).path)
    if (!await protectViews(views.slice(0, Math.max(0, index)).filter(view => !view.meta?.affix))) return [...views]
    return useTagsViewStore().delLeftTags(obj || router.currentRoute.value)
  },
  // 关闭右侧tab页签
  async closeRightPage(obj) {
    const views = useTagsViewStore().visitedViews
    const index = views.findIndex(view => view.path === (obj || router.currentRoute.value).path)
    if (index >= 0 && !await protectViews(views.slice(index + 1).filter(view => !view.meta?.affix))) return [...views]
    return useTagsViewStore().delRightTags(obj || router.currentRoute.value)
  },
  // 关闭其他tab页签
  async closeOtherPage(obj) {
    const retained = obj || router.currentRoute.value
    if (!await protectViews(useTagsViewStore().visitedViews.filter(view => view.path !== retained.path && !view.meta?.affix))) return cancelledClose()
    return useTagsViewStore().delOthersViews(obj || router.currentRoute.value)
  },
  // 打开tab页签
  openPage(title, url, params) {
    const obj = { path: url, meta: { title: title } }
    useTagsViewStore().addView(obj)
    return router.push({ path: url, query: params })
  },
  // 修改tab页签
  updatePage(obj) {
    return useTagsViewStore().updateVisitedView(obj)
  }
}
