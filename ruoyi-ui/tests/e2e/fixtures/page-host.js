import { test as base, expect } from '@playwright/test'
import { createComponentHost } from './component-host.js'

export const pageHarness = `
import { createApp, h, ref, KeepAlive } from 'vue'
import { createRouter, createMemoryHistory, RouterView } from 'vue-router'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as icons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import * as helpers from '/src/utils/ruoyi.js'
import modal from '/src/plugins/modal.js'
import auth from '/src/plugins/auth.js'
import directive from '/src/directive/index.js'
import useUserStore from '/src/store/modules/user.js'
import useDictStore from '/src/store/modules/dict.js'
import Pagination from '/src/components/Pagination/index.vue'
import RightToolbar from '/src/components/RightToolbar/index.vue'
import DictTag from '/src/components/DictTag/index.vue'
const views = import.meta.glob('/src/views/**/*.vue')
const options = new URLSearchParams(location.search)
const component = (await views['/src/views/' + options.get('view') + '.vue']()).default
const route = options.get('route') || '/subject/1'
const router = createRouter({ history: createMemoryHistory(), routes: [
  { path: '/subject/:id', component, name: 'Subject', props: false },
  { path: '/away', component: { render: () => h('p', '已离开被测页面') } }
] })
// The route parameters are host inputs, just as they are in the application router.
router.addRoute({ path: '/dict/:dictId', component, name: 'DictSubject' })
router.addRoute({ path: '/job/:jobId', component, name: 'JobSubject' })
router.addRoute({ path: '/workflow/model-design/:modelId', component, name: 'WorkflowModelDesign' })
const mounted = ref(true), active = ref(true)
const app = createApp({ render: () => h('main', [
  h('nav', [h('button', { onClick: () => router.push('/away') }, '导航离页'),
    h('button', { onClick: () => { mounted.value = false } }, '卸载被测页面'),
    h('button', { onClick: () => { active.value = !active.value } }, '切换缓存激活')]),
  options.has('cacheProof') ? h('output', { 'data-testid': 'dictionary-cache' },
    ['old_type', 'new_type', 'later_edit'].map(key => key + ':' + (useDictStore().getDict(key) ? 'cached' : 'missing')).join(',')) : null,
  mounted.value ? h(RouterView, null, { default: ({ Component, route }) =>
    options.has('keepAlive') ? h(KeepAlive, null, () => active.value ? h(Component, { key: route.path }) : null) : h(Component, { key: route.path }) }) : null
]) })
app.use(createPinia()).use(router).use(ElementPlus)
Object.assign(useUserStore(), { id: 1, permissions: JSON.parse(options.get('permissions') || '["*:*:*"]'), roles: ['admin'] })
if (options.has('cacheProof')) for (const key of ['old_type', 'new_type', 'later_edit']) useDictStore().setDict(key, [{ label: key, value: '1' }])
Object.assign(app.config.globalProperties, helpers, { $modal: modal, $auth: auth,
  resetForm(name) { this.$refs[name]?.resetFields() },
  $tab: { closeOpenPage: () => router.push('/away') }
})
for (const [name, icon] of Object.entries(icons)) app.component(name, icon)
for (const [name, component] of Object.entries({ Pagination, RightToolbar, DictTag })) app.component(name, component)
directive(app)
await router.push(route)
await router.isReady()
app.mount('#app')
`

export const test = base.extend({
  componentHost: [async ({ browserName: _browserName }, use) => {
    const host = await createComponentHost(pageHarness)
    try { await use(host) } finally { await host.close() }
  }, { scope: 'worker' }]
})
export { expect }

export async function openPage(page, host, view, handler, options = {}) {
  const calls = []
  await page.route('**/test-api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace('/test-api', '')
    const reply = data => route.fulfill({ json: { code: 200, ...data } })
    calls.push({ path, method: request.method(), query: Object.fromEntries(url.searchParams), body: request.postDataJSON() })
    if (await handler({ route, request, url, path, reply })) return
    await reply({ data: [], rows: [], total: 0 })
  })
  const query = new URLSearchParams({ view, ...options })
  await page.goto(host.url + '/component-harness?' + query)
  return calls
}

export async function settleResponse(page, response) {
  await (await response).finished()
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}
