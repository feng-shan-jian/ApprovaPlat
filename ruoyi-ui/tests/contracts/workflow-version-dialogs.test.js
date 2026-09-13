import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import {
  createRenderer, effectScope, getCurrentInstance, h, KeepAlive, nextTick,
  onActivated, onBeforeUnmount, onDeactivated, reactive, ref, watch
} from 'vue'

const pages = [
  {
    page: 'deploy', open: 'openVersions', load: 'loadVersions', action: 'toggleVersionState',
    visible: 'versionsOpen', loading: 'versionsLoading', rows: 'versionRows', total: 'versionTotal',
    title: 'versionProcessName', query: 'versionQuery', key: 'processKey', id: 'definitionId',
    listApi: 'listDeployments', versionApi: 'listPublishedVersions', actionApi: 'changeDeploymentState',
    row: (key, version = 1) => ({ processKey: key, processName: key === 'A' ? 'Alpha' : 'Beta', definitionId: `${key}:${version}`, version, suspended: false })
  },
  {
    page: 'model', open: 'openHistory', load: 'loadHistory', action: 'promote',
    visible: 'historyOpen', loading: 'historyLoading', rows: 'historyRows', total: 'historyTotal',
    title: 'historyModelName', query: 'historyQuery', key: 'modelKey', id: 'modelId',
    listApi: 'listModels', versionApi: 'listModelHistory', actionApi: 'promoteModel',
    row: (key, version = 1) => ({ modelKey: key, modelName: key === 'A' ? 'Alpha' : 'Beta', modelId: `${key}:${version}`, version })
  }
]

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

// 无浏览器的渲染宿主仅承载 Vue 组件与 KeepAlive，生命周期、响应式和 watcher 均使用真实 Vue。
const node = () => ({ children: [], parent: null })
function remove(child) {
  if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1)
  child.parent = null
}
const renderer = createRenderer({
  createElement: node, createText: node, createComment: node,
  setText() {}, setElementText() {}, patchProp() {},
  parentNode: child => child.parent,
  nextSibling: child => child.parent?.children[child.parent.children.indexOf(child) + 1] || null,
  remove,
  insert(child, parent, anchor = null) {
    remove(child)
    parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, child)
    child.parent = parent
  }
})

async function dialog(t, spec) {
  const source = readFileSync(new URL(`../../src/views/workflow/${spec.page}/index.vue`, import.meta.url), 'utf8')
  const script = compileScript(parse(source).descriptor, { id: `${spec.page}-dialog-contract` })
  const queries = [], confirmations = [], actions = []
  let listCalls = 0
  const bindings = {
    ref, reactive, watch, nextTick, getCurrentInstance, onActivated, onBeforeUnmount, onDeactivated,
    useRouter: () => ({ push() {} })
  }
  for (const [name, entry] of Object.entries(script.imports)) {
    bindings[name] = entry.source.endsWith('.vue') ? {} : () => { throw new Error(`Unexpected API: ${name}`) }
  }
  Object.assign(bindings, {
    listAllCategories: async () => ({ data: [] }),
    listForms: async () => ({ rows: [], total: 0 }),
    [spec.listApi]: async () => { listCalls += 1; return { rows: [], total: 0 } },
    [spec.versionApi]: query => {
      const request = { query, ...deferred() }
      queries.push(request)
      return request.promise
    },
    [spec.actionApi]: (...args) => {
      const request = { args, ...deferred() }
      actions.push(request)
      return request.promise
    }
  })
  // 只替换模块导入；运行编译后的生产 setup，不在测试中重写弹窗逻辑。
  const body = script.content.replace(/^import[\s\S]*?from ['"][^'"]+['"]\r?\n/gm, '').replace('export default', 'return')
  const component = new Function(...Object.keys(bindings), body)(...Object.values(bindings))
  const setup = component.setup
  let state
  component.setup = (props, context) => {
    state = effectScope().run(() => setup(props, context))
    return state
  }
  component.render = () => null
  const active = ref(true)
  const app = renderer.createApp({ render: () => h(KeepAlive, null, { default: () => active.value ? h(component) : null }) })
  app.config.globalProperties.$modal = {
    confirm(message) {
      const confirmation = { message, ...deferred() }
      confirmations.push(confirmation)
      return confirmation.promise
    },
    msgSuccess() {}
  }
  app.mount(node())
  let mounted = true
  const unmount = () => { if (mounted) { app.unmount(); mounted = false } }
  t.after(unmount)
  await state.initialLoad
  return {
    state, queries, confirmations, actions, active, unmount,
    listCalls: () => listCalls,
    open: key => state[spec.open](spec.row(key)),
    load: () => state[spec.load](),
    act: row => state[spec.action](row),
    visible: state[spec.visible], loading: state[spec.loading], rows: state[spec.rows],
    total: state[spec.total], title: state[spec.title], query: state[spec.query],
    finish: (index, key, version = 1) => queries[index].resolve({ rows: [spec.row(key, version)], total: version * 10 })
  }
}

for (const spec of pages) {
  for (const order of [[0, 1], [1, 0]]) {
    test(`${spec.page} 切换 A 到 B，响应顺序 ${order} 只保留 B`, async t => {
      const d = await dialog(t, spec)
      const a = d.open('A')
      const b = d.open('B')
      assert.equal(d.queries[0].query[spec.key], 'A', 'API 持有查询快照')
      assert.notEqual(d.queries[0].query, d.query)
      assert.deepEqual(d.rows.value, [])
      for (const index of order) {
        d.finish(index, index ? 'B' : 'A')
        await [a, b][index]
        assert.equal(d.loading.value, index === 0 && order[0] === 0)
      }
      assert.equal(d.title.value, 'Beta')
      assert.equal(d.query[spec.key], 'B')
      assert.equal(d.rows.value[0][spec.id], 'B:1')
      assert.equal(d.total.value, 10)
    })
  }

  test(`${spec.page} 关闭后同一事件轮次重开同对象，旧请求与旧 finally 均失效`, async t => {
    const d = await dialog(t, spec)
    const old = d.open('A')
    d.visible.value = false
    assert.equal(d.loading.value, false)
    assert.deepEqual(d.rows.value, [])
    assert.equal(d.total.value, 0)
    const current = d.open('A')
    d.finish(0, 'A')
    await old
    assert.equal(d.loading.value, true)
    assert.deepEqual(d.rows.value, [])
    d.finish(1, 'A', 2)
    await current
    assert.equal(d.rows.value[0][spec.id], 'A:2')
    d.visible.value = false
    await d.load()
    assert.equal(d.queries.length, 2, '关闭状态不发查询')
    assert.deepEqual(d.rows.value, [])
  })

  test(`${spec.page} 同对象重复打开及翻页时快照独立，旧页不覆盖新页`, async t => {
    const d = await dialog(t, spec)
    const old = d.open('A')
    const reopened = d.open('A')
    d.query.pageNum = 2
    d.query.pageSize = 20
    const page = d.load()
    assert.deepEqual(d.queries.map(request => [request.query.pageNum, request.query.pageSize]), [[1, 10], [1, 10], [2, 20]])
    d.finish(2, 'A', 3)
    await page
    d.finish(1, 'A', 2)
    d.finish(0, 'A')
    await Promise.all([old, reopened])
    assert.equal(d.rows.value[0][spec.id], 'A:3')
    assert.equal(d.total.value, 30)
  })

  test(`${spec.page} 旧失败不结束新加载，当前失败保留原错误且清空旧行`, async t => {
    const d = await dialog(t, spec)
    const old = d.open('A')
    const current = d.open('B')
    const error = Object.assign(new Error('版本查询失败'), { code: 500, subCode: 'TEST_ERROR' })
    const oldRejected = assert.rejects(old, value => value === error)
    d.queries[0].reject(error)
    await oldRejected
    assert.equal(d.loading.value, true)
    assert.deepEqual(d.rows.value, [])
    d.finish(1, 'B')
    await current
    const failed = d.load()
    assert.deepEqual(d.rows.value, [])
    const currentRejected = assert.rejects(failed, value => value === error)
    d.queries[2].reject(error)
    await currentRejected
    assert.equal(d.loading.value, false)
    assert.deepEqual(d.rows.value, [])
  })

  for (const leave of ['deactivate', 'unmount']) {
    test(`${spec.page} ${leave} 使未完成请求失效`, async t => {
      const d = await dialog(t, spec)
      const pending = d.open('A')
      if (leave === 'deactivate') {
        d.active.value = false
        await nextTick()
      } else d.unmount()
      assert.equal(d.visible.value, false)
      assert.equal(d.loading.value, false)
      d.finish(0, 'A')
      await pending
      assert.deepEqual(d.rows.value, [])
      assert.equal(d.total.value, 0)
    })
  }

  for (const change of ['switch', 'reopen', 'page']) {
    test(`${spec.page} 确认期间 ${change} 不执行旧动作`, async t => {
      const d = await dialog(t, spec)
      const first = d.open('A')
      d.finish(0, 'A')
      await first
      const row = d.rows.value[0]
      const action = d.act(row)
      assert.match(d.confirmations[0].message, /Alpha.*V1/)
      let current
      if (change === 'switch') current = d.open('B')
      else if (change === 'reopen') { d.visible.value = false; current = d.open('A') }
      else { d.query.pageNum = 2; current = d.load() }
      d.confirmations[0].resolve()
      await action
      assert.equal(d.actions.length, 0)
      d.finish(1, change === 'switch' ? 'B' : 'A', 2)
      await current
      await d.act(row)
      assert.equal(d.confirmations.length, 1, '旧结果行不能再次进入确认')
    })
  }

  test(`${spec.page} 当前动作绑定确认时主键，正常成功刷新版本和主列表`, async t => {
    const d = await dialog(t, spec)
    const opened = d.open('A')
    d.finish(0, 'A')
    await opened
    const row = d.rows.value[0]
    const action = d.act(row)
    row[spec.id] = 'changed-after-confirm'
    d.confirmations[0].resolve()
    await nextTick()
    assert.deepEqual(d.actions[0].args, spec.page === 'deploy' ? ['A:1', 'suspended'] : ['A:1'])
    d.actions[0].resolve()
    await nextTick()
    assert.equal(d.queries.length, 2)
    assert.equal(d.listCalls(), 2)
    d.finish(1, 'A', 2)
    await action
    assert.equal(d.rows.value[0][spec.id], 'A:2')
  })

  test(`${spec.page} 已发出动作在切换对象后完成，不刷新新弹窗`, async t => {
    const d = await dialog(t, spec)
    const opened = d.open('A')
    d.finish(0, 'A')
    await opened
    const action = d.act(d.rows.value[0])
    d.confirmations[0].resolve()
    await nextTick()
    assert.equal(d.actions.length, 1)
    const current = d.open('B')
    d.actions[0].resolve()
    await action
    assert.equal(d.queries.length, 2)
    assert.equal(d.listCalls(), 2)
    assert.equal(d.loading.value, true)
    d.finish(1, 'B')
    await current
    assert.equal(d.rows.value[0][spec.id], 'B:1')
  })
}
