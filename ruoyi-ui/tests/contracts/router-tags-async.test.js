import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { createPinia, defineStore, disposePinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { isHttp, isPathMatch } from '../../src/utils/validate.js'
import { confirmPageLeave, registerPageLeaveGuard } from '../../src/utils/pageLeaveGuards.js'

// 沿用 request-submission 的生产源码加载方式，仅替换模块绑定、Vite glob 与自动导入。
// action、路由守卫、tab、auth、cache 均执行生产实现；Pinia 和内存 router 使用真实依赖。
function load(path, dependencies = {}, namedExport = '') {
  const source = readFileSync(new URL(`../../src/${path}`, import.meta.url), 'utf8')
    .replace(/^import ['"][^'"]+['"];?\r?\n/gm, '')
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gm, '')
    .replace(/import\.meta\.glob\([^\n]+\)/g, 'viewModules')
    .replace(/export (function|const) /g, '$1 ')
    .replace('export default', 'return')
  return new Function(...Object.keys(dependencies), source + (namedExport ? `\nreturn ${namedExport}` : ''))(...Object.values(dependencies))
}

function piniaFor(t) {
  const pinia = createPinia()
  setActivePinia(pinia)
  t.after(() => disposePinia(pinia))
  return pinia
}

// 有限等待只作为测试失败信号，避免旧实现 pending 使测试挂住；生产无超时兜底。
async function settled(promise) {
  assert.equal(typeof promise?.then, 'function', '必须保留 .then 调用契约')
  let timer
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error('操作仍 pending')), 100)
      })
    ])
  } finally {
    clearTimeout(timer)
  }
}

function routesFor(t, request = () => Promise.resolve({ data: [] })) {
  const pinia = piniaFor(t)
  const component = { render: () => null }
  const constantRoutes = [
    { path: '/', name: 'Home', component },
    { path: '/login', name: 'Login', component },
    { path: '/work', name: 'Work', component }
  ]
  const dynamicRoutes = [
    { path: '/allowed', name: 'Allowed', permissions: ['read'], component },
    { path: '/role', name: 'Role', roles: ['reviewer'], component },
    { path: '/denied', name: 'Denied', permissions: ['write'], component },
    { path: '/denied-role', name: 'DeniedRole', roles: ['other'], component },
    { path: '/permission-first', permissions: ['write'], roles: ['reviewer'], component },
    { path: '/unscoped', component }
  ]
  const router = createRouter({ history: createMemoryHistory(), routes: constantRoutes })
  let token = 'test-token'
  let logoutCount = 0
  const user = {
    roles: [], permissions: ['read'],
    async getInfo() { this.roles = ['reviewer'] },
    async logOut() { logoutCount++; token = ''; this.roles = [] }
  }
  const useUserStore = () => user
  const auth = load('plugins/auth.js', { useUserStore })
  const getRouters = load('api/menu.js', { request }, 'getRouters')
  const usePermissionStore = load('store/modules/permission.js', {
    defineStore, auth, router, constantRoutes, dynamicRoutes, getRouters,
    Layout: component, ParentView: component, InnerLink: component,
    viewModules: { './../../views/work/item.vue': () => Promise.resolve(component) }
  })
  const store = usePermissionStore(pinia)
  const errors = []
  const failures = []
  const isRelogin = { show: false }
  const progress = { starts: 0, ends: 0, configure() {}, start() { this.starts++ }, done() { this.ends++ } }
  store.$onAction(({ onError }) => onError(error => failures.push(error)))
  load('permission.js', {
    router, ElMessage: { error: error => errors.push(error) }, NProgress: progress,
    getToken: () => token, isHttp, isPathMatch, isRelogin, useUserStore,
    useLockStore: () => ({ isLock: false }), useSettingsStore: () => ({ setTitle() {} }),
    usePermissionStore: () => store
  })
  return { store, router, user, errors, failures, isRelogin, progress,
    get logoutCount() { return logoutCount } }
}

test('生产路由 action 保留独立展示树、扁平注册树与授权筛选，守卫完成重新导航', async t => {
  const data = [{ path: '/workspace', component: 'Layout', children: [
    { path: 'group', component: 'ParentView', children: [
      { path: 'item', name: 'Item', component: 'work/item', children: [], redirect: 'unused' }
    ] },
    { path: 'external', name: 'External', component: 'InnerLink' }
  ] }]
  const original = structuredClone(data)
  const calls = []
  const state = routesFor(t, config => { calls.push(config); return Promise.resolve({ data }) })
  await settled(state.router.push('/work'))
  assert.deepEqual(calls, [{ url: '/getRouters', method: 'get' }])
  assert.equal(state.router.currentRoute.value.path, '/work')
  assert.equal(state.router.hasRoute('Allowed'), true)
  assert.equal(state.router.hasRoute('Role'), true)
  assert.equal(state.router.hasRoute('Denied'), false)
  assert.equal(state.router.hasRoute('DeniedRole'), false)
  assert.equal(state.router.hasRoute('Item'), true)
  assert.equal(state.router.getRoutes().some(route => ['/permission-first', '/unscoped'].includes(route.path)), false)
  const { store } = state
  assert.equal(store.addRoutes[0].children[0].path, 'group/item')
  assert.equal(store.sidebarRouters.at(-1).children[0].path, 'group')
  assert.equal(store.defaultRoutes.at(-1).children[0].path, 'group')
  assert.equal(store.topbarRouters[0].children[0].path, 'group')
  assert.equal(store.routes.length, 4)
  assert.equal(store.sidebarRouters.length, 4)
  assert.equal(store.defaultRoutes.length, 4)
  assert.equal(store.topbarRouters.length, 1)
  assert.notEqual(store.addRoutes[0], store.sidebarRouters.at(-1))
  assert.notEqual(store.topbarRouters[0], store.sidebarRouters.at(-1))
  assert.equal(typeof store.addRoutes[0].children[0].component, 'function')
  assert.equal(Object.hasOwn(store.addRoutes[0].children[0], 'redirect'), false)
  assert.deepEqual(data, original)
  assert.deepEqual(state.errors, [])
  assert.equal(state.logoutCount, 0)
  assert.equal(state.isRelogin.show, false)
  assert.ok(state.progress.ends > 0)
})

test('空路由树仍返回可 .then 的空结果并保留常量路由', async t => {
  const { store } = routesFor(t)
  const result = await settled(store.generateRoutes().then(routes => routes))
  assert.deepEqual(result, [])
  assert.equal(store.routes.length, 3)
  assert.equal(store.sidebarRouters.length, 3)
  assert.equal(store.defaultRoutes.length, 3)
  assert.deepEqual(store.topbarRouters, [])
})

test('路由请求拒绝以原错误拒绝 action，不能伪装成空路由成功', async t => {
  const error = new Error('menu unavailable')
  const { store } = routesFor(t, () => Promise.reject(error))
  await assert.rejects(settled(store.generateRoutes()), failure => failure === error)
})

for (const fault of ['network', 'conversion', 'store-write']) {
  test(`路由 ${fault} 失败到达生产守卫既有 catch、登出和提示`, async t => {
    const error = new Error(`${fault} unavailable`)
    const state = routesFor(t, () => fault === 'network'
      ? Promise.reject(error)
      : Promise.resolve({ data: fault === 'conversion' ? { invalid: 'route response' } : [] }))
    if (fault === 'store-write') {
      // 真实 Pinia 同步订阅者抛错，验证状态写入抛错也在 action 返回链上。
      t.mock.method(console, 'warn', () => {})
      const stop = state.store.$subscribe(() => { throw error }, { flush: 'sync' })
      t.after(stop)
    }
    await settled(state.router.push('/work'))
    assert.equal(state.logoutCount, 1)
    assert.equal(state.errors.length, 1)
    assert.equal(state.errors[0], state.failures[0])
    if (fault === 'conversion') assert.ok(state.errors[0] instanceof TypeError)
    else assert.equal(state.errors[0], error)
    assert.equal(state.router.currentRoute.value.path, '/login')
    assert.equal(state.router.currentRoute.value.query.redirect, '/')
    assert.equal(state.isRelogin.show, false)
    assert.ok(state.progress.ends > 0)
  })
}

function tagsFor(t, persist = true) {
  const pinia = piniaFor(t)
  const values = new Map()
  const writes = []
  const storage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { writes.push(['set', key]); values.set(key, value) },
    removeItem: key => { writes.push(['remove', key]); values.delete(key) }
  }
  const cache = load('plugins/cache.js', { localStorage: storage, sessionStorage: storage })
  const useTagsViewStore = load('store/modules/tagsView.js', {
    defineStore, cache, useSettingsStore: () => ({ tagsViewPersist: persist })
  })
  const store = useTagsViewStore(pinia)
  const component = { render: () => null }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/:pathMatch(.*)*', component }
  ] })
  const tab = load('plugins/tab.js', { useTagsViewStore: () => store, router, confirmPageLeave })
  return { store, tab, router, storage, writes,
    persisted: () => JSON.parse(values.get('tags-view-visited') || '[]') }
}

const view = (name, meta = {}) => ({ path: `/${name}`, fullPath: `/${name}?id=1`, name,
  query: { id: '1' }, meta: { title: name, ...meta } })
const paths = views => views.map(item => item.path)

function seed(state) {
  const views = [view('home', { affix: true }), view('left', { link: 'https://left.example' }),
    view('target'), view('right', { link: 'https://right.example' }), view('fixed', { affix: true })]
  for (const item of views) {
    state.store.addView(item)
    if (item.meta.link) state.store.addIframeView(item)
  }
  state.writes.length = 0
  return views[2]
}

for (const [method, remaining, cached, iframe] of [
  ['closeLeftPage', ['/home', '/target', '/right', '/fixed'], ['home', 'target', 'right', 'fixed'], ['/right']],
  ['closeRightPage', ['/home', '/left', '/target', '/fixed'], ['home', 'left', 'target', 'fixed'], ['/left']]
]) {
  test(`${method} 经生产 tab .then 返回结果，保留固定标签、顺序、缓存及 iframe`, async t => {
    const state = tagsFor(t)
    const target = seed(state)
    const result = await settled(state.tab[method](target).then(views => views))
    assert.deepEqual(paths(result), remaining)
    assert.notEqual(result, state.store.visitedViews)
    assert.deepEqual(state.store.cachedViews, cached)
    assert.deepEqual(paths(state.store.iframeViews), iframe)
    assert.deepEqual(paths(state.persisted()), remaining.filter(path => !['/home', '/fixed'].includes(path)))
    assert.deepEqual(state.writes, [['set', 'tags-view-visited']])
  })

  for (const empty of [false, true]) {
    test(`${method} 目标不存在（空列表=${empty}）及时返回副本且不删除或持久化`, async t => {
      const state = tagsFor(t)
      if (!empty) seed(state)
      const before = JSON.stringify(state.store.$state)
      const result = await settled(state.tab[method](view('missing')).then(views => views))
      assert.deepEqual(result, state.store.visitedViews)
      assert.notEqual(result, state.store.visitedViews)
      assert.equal(JSON.stringify(state.store.$state), before)
      assert.deepEqual(state.writes, [])
    })
  }

  test(`${method} 保留页面离开取消策略`, async t => {
    const state = tagsFor(t)
    const target = seed(state)
    const removed = method === 'closeLeftPage' ? '/left' : '/right'
    let confirmations = 0
    t.after(registerPageLeaveGuard(removed, () => { confirmations++; return false }))
    const before = JSON.stringify(state.store.$state)
    const result = await settled(state.tab[method](target))
    assert.equal(confirmations, 1)
    assert.deepEqual(result, state.store.visitedViews)
    assert.equal(JSON.stringify(state.store.$state), before)
    assert.deepEqual(state.writes, [])
  })
}

for (const [method, visited, cached, iframe, write] of [
  ['closePage', ['/home', '/left', '/right', '/fixed'], ['home', 'left', 'right', 'fixed'], ['/left', '/right'], 'set'],
  ['closeOtherPage', ['/home', '/target', '/fixed'], ['target'], [], 'set'],
  ['closeAllPage', ['/home', '/fixed'], [], [], 'remove']
]) {
  test(`${method} 组合动作保留返回字段、副本和持久化结果`, async t => {
    const state = tagsFor(t)
    const target = seed(state)
    const result = await settled(state.tab[method](target).then(result => result))
    assert.deepEqual(Object.keys(result), ['visitedViews', 'cachedViews'])
    assert.deepEqual(paths(result.visitedViews), visited)
    assert.deepEqual(result.cachedViews, cached)
    assert.notEqual(result.visitedViews, state.store.visitedViews)
    assert.notEqual(result.cachedViews, state.store.cachedViews)
    assert.deepEqual(paths(state.store.iframeViews), iframe)
    assert.deepEqual(paths(state.persisted()), visited.filter(path => !['/home', '/fixed'].includes(path)))
    assert.deepEqual(state.writes, [[write, 'tags-view-visited']])
  })

  test(`${method} 子动作的 Storage 故障通过组合 action 与 tab 原样拒绝`, async t => {
    const state = tagsFor(t)
    const target = seed(state)
    const error = new DOMException('Storage unavailable', write === 'set' ? 'QuotaExceededError' : 'SecurityError')
    // 只在平台 Storage 边界制造故障，生产 cache 和所有子 action 继续真实运行。
    t.mock.method(state.storage, write === 'set' ? 'setItem' : 'removeItem', () => { throw error })
    await assert.rejects(settled(state.tab[method](target)), failure => failure === error)
  })
}

test('各删除 action 在空状态保留 Promise 及返回形态', async t => {
  const { store } = tagsFor(t)
  for (const method of ['delView', 'delOthersViews', 'delAllViews']) {
    assert.deepEqual(await settled(store[method](view('missing'))), { visitedViews: [], cachedViews: [] })
  }
  for (const method of ['delVisitedView', 'delIframeView', 'delCachedView', 'delOthersVisitedViews',
    'delOthersCachedViews', 'delAllVisitedViews', 'delAllCachedViews', 'delLeftTags', 'delRightTags']) {
    assert.deepEqual(await settled(store[method](view('missing')).then(result => result)), [])
  }
})

test('缓存 miss、noCache、固定标签与持久化恢复保持既有行为', async t => {
  const state = tagsFor(t)
  seed(state)
  const uncached = view('uncached', { noCache: true })
  state.store.addView(uncached)
  assert.equal(state.store.cachedViews.includes(uncached.name), false)
  assert.deepEqual(await settled(state.store.delOthersCachedViews(uncached)), [])
  const saved = state.persisted()
  assert.deepEqual(paths(saved), ['/left', '/target', '/right', '/uncached'])
  assert.deepEqual(saved[1], { ...view('target'), title: 'target' })
  state.store.$reset()
  state.store.loadPersistedViews()
  state.store.addAffixView(view('home', { affix: true }))
  assert.deepEqual(paths(state.store.visitedViews), ['/home', ...paths(saved)])
  assert.deepEqual(state.store.cachedViews, [])
  assert.deepEqual(state.store.iframeViews, [])
  await settled(state.store.delAllViews())
  assert.deepEqual(paths(state.store.visitedViews), ['/home'])
  assert.deepEqual(state.persisted(), [])
})

test('关闭当前页无参数及刷新沿用 .then 导航；禁用持久化时不写 Storage', async t => {
  const state = tagsFor(t, false)
  seed(state)
  await state.router.push('/target')
  await settled(state.tab.closePage())
  assert.equal(state.router.currentRoute.value.fullPath, '/fixed?id=1')
  assert.deepEqual(state.writes, [])
  await settled(state.tab.refreshPage(view('left')))
  // tab 刷新只等待删缓存，沿用既有不返回 router.replace 结果的契约。
  assert.equal(state.store.cachedViews.includes('left'), false)
})
