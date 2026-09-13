import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as vue from 'vue'

const node = () => ({ children: [], parent: null })
function remove(child) {
  if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1)
  child.parent = null
}
const renderer = vue.createRenderer({
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
function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); await vue.nextTick() }

async function page(t) {
  const source = readFileSync(new URL('../../src/views/workflow/extension/index.vue', import.meta.url), 'utf8')
  const script = compileScript(parse(source).descriptor, { id: 'extension-deletion-contract' })
  const checks = [], confirmations = [], deletes = [], warnings = [], errors = []
  const rows = [1, 2].map(extensionId => ({ extensionId, extensionName: `目录${extensionId}`,
    extensionKey: `key${extensionId}`, status: 'DISABLED', deleteAllowed: false, deleteCheckAllowed: true }))
  let listCalls = 0
  const bindings = Object.fromEntries(['ref', 'reactive', 'computed', 'getCurrentInstance', 'nextTick',
    'onMounted', 'onActivated', 'onDeactivated', 'onBeforeUnmount'].map(name => [name, vue[name]]))
  for (const name of Object.keys(script.imports)) bindings[name] = () => { throw new Error(`Unexpected API ${name}`) }
  Object.assign(bindings, {
    listWorkflowExtensions: async () => { listCalls++; return { data: rows.map(row => ({ ...row })) } },
    listInstalledJavaHandlers: async () => ({ data: [] }),
    checkWorkflowExtensionDeletion: extensionId => {
      const request = { extensionId, ...deferred() }; checks.push(request); return request.promise
    },
    removeWorkflowExtension: extensionId => {
      const request = { extensionId, ...deferred() }; deletes.push(request); return request.promise
    }
  })
  const body = script.content.replace(/^import[\s\S]*?from ['"][^'"]+['"]\r?\n/gm, '').replace('export default', 'return')
  const component = new Function(...Object.keys(bindings), body)(...Object.values(bindings))
  const setup = component.setup
  let state
  component.setup = (props, context) => { state = setup(props, context); return state }
  component.render = () => null
  const active = vue.ref(true)
  const app = renderer.createApp({ render: () => vue.h(vue.KeepAlive, null,
    { default: () => active.value ? vue.h(component) : null }) })
  app.config.globalProperties.$modal = {
    confirm(message) { const confirmation = { message, ...deferred() }; confirmations.push(confirmation); return confirmation.promise },
    msgSuccess() {}, msgWarning: message => warnings.push(message), msgError: message => errors.push(message)
  }
  app.mount(node())
  let mounted = true
  const unmount = () => { if (mounted) { app.unmount(); mounted = false } }
  t.after(unmount)
  await settle()
  return { state, checks, confirmations, deletes, warnings, errors, active, unmount,
    listCalls: () => listCalls, act: (index = 0) => state.removeExtension(state.filteredRows.value[index]) }
}

test('版本目录先检查，再确认明确目标，最后删除并刷新', async t => {
  const p = await page(t)
  const action = p.act()
  assert.equal(p.checks[0].extensionId, 1)
  assert.equal(p.confirmations.length, 0)
  p.checks[0].resolve({ data: { deleteAllowed: true } })
  await settle()
  assert.match(p.confirmations[0].message, /目录1/)
  assert.equal(p.deletes.length, 0)
  p.confirmations[0].resolve()
  await settle()
  assert.equal(p.deletes[0].extensionId, 1)
  p.deletes[0].resolve()
  await action
  assert.equal(p.listCalls(), 2)
  assert.equal(p.state.deletionTargetId.value, null)
})

for (const value of [false, undefined, 'true', 1]) {
  test(`检查必须返回布尔 true；${String(value)} 不产生确认或删除`, async t => {
    const p = await page(t)
    const action = p.act()
    p.checks[0].resolve({ data: { deleteAllowed: value } })
    await action
    assert.equal(p.confirmations.length, 0)
    assert.equal(p.deletes.length, 0)
    assert.equal(p.warnings.length, 1)
    assert.equal(p.state.filteredRows.value.length, 2)
  })
}

test('列表能力严格失败关闭，不能由旧 deleteAllowed 或目录状态推断入口', async t => {
  const p = await page(t)
  for (const value of [false, undefined, 'true']) {
    p.state.filteredRows.value[0].deleteCheckAllowed = value
    p.state.filteredRows.value[0].deleteAllowed = true
    await p.act()
  }
  assert.equal(p.checks.length, 0)
})

test('检查失败不影响目录，释放加载状态后允许重试', async t => {
  const p = await page(t)
  const action = p.act()
  p.checks[0].reject(new Error('历史部署资源无法核验'))
  await action
  assert.equal(p.errors.length, 1)
  assert.equal(p.state.filteredRows.value.length, 2)
  assert.equal(p.state.deletionTargetId.value, null)
  const retry = p.act()
  p.checks[1].resolve({ data: { deleteAllowed: false } })
  await retry
  assert.equal(p.deletes.length, 0)
})

test('同一行重复点击仅检查一次；切换行后旧响应与 finally 不接管新目标', async t => {
  const p = await page(t)
  const first = p.act()
  await p.act()
  assert.equal(p.checks.length, 1)
  const second = p.act(1)
  p.checks[0].resolve({ data: { deleteAllowed: true } })
  await first
  assert.equal(p.confirmations.length, 0)
  assert.equal(p.state.deletionTargetId.value, 2)
  p.checks[1].resolve({ data: { deleteAllowed: true } })
  await settle()
  assert.match(p.confirmations[0].message, /目录2/)
  p.confirmations[0].reject('cancel')
  await second
  assert.equal(p.deletes.length, 0)
})

test('旧检查失败不弹错误，也不释放另一行正在进行的检查', async t => {
  const p = await page(t)
  const first = p.act()
  const second = p.act(1)
  p.checks[0].reject(new Error('旧目标历史资源损坏'))
  await first
  assert.equal(p.errors.length, 0)
  assert.equal(p.state.deletionTargetId.value, 2)
  p.checks[1].reject(new Error('当前目标无法核验'))
  await second
  assert.deepEqual(p.errors, ['当前目标无法核验'])
})

for (const stage of ['check', 'confirmation']) {
  for (const context of ['filter', 'refresh', 'deactivate', 'unmount']) {
    test(`${stage} 等待期间 ${context}，旧操作不得继续`, async t => {
      const p = await page(t)
      const action = p.act()
      if (stage === 'confirmation') {
        p.checks[0].resolve({ data: { deleteAllowed: true } })
        await settle()
      }
      if (context === 'filter') { p.state.filters.keyword = 'key2'; p.state.applyFilters() }
      if (context === 'refresh') await p.state.loadRegistry()
      if (context === 'deactivate') { p.active.value = false; await vue.nextTick() }
      if (context === 'unmount') p.unmount()
      if (stage === 'check') p.checks[0].resolve({ data: { deleteAllowed: true } })
      else p.confirmations[0].resolve()
      await action
      assert.equal(p.deletes.length, 0)
      if (stage === 'check') assert.equal(p.confirmations.length, 0)
    })
  }
}

test('最终删除拒绝说明检查已过期，保留列表且允许重新检查', async t => {
  const p = await page(t)
  const action = p.act()
  p.checks[0].resolve({ data: { deleteAllowed: true } })
  await settle()
  p.confirmations[0].resolve()
  await settle()
  p.deletes[0].reject(new Error('扩展目录已被部署快照引用，不能删除'))
  await action
  assert.match(p.errors[0], /已被部署快照引用/)
  assert.equal(p.state.filteredRows.value.length, 2)
  assert.equal(p.state.deletionTargetId.value, null)
})
