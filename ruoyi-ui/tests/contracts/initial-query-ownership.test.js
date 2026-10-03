import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { createRenderer, h, ref, reactive, toRefs, getCurrentInstance } from 'vue'
import { useRequestSequence } from '../../src/utils/requestSequence.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

const pages = [
  { path: 'system/dict/data.vue', key: 'dictType', rows: 'dataList', defaults: { dictType: 'A' }, current: { dictType: 'B' } },
  { path: 'monitor/job/log.vue', key: 'jobName', rows: 'jobLogList', defaults: { jobName: 'A', jobGroup: 'DEFAULT' }, current: { jobName: 'B', jobGroup: 'SYSTEM' } }
]
const settle = () => new Promise(resolve => setImmediate(resolve))

function mountPage(t, config) {
  const initialization = deferred(), requests = []
  let initialOptions
  const readInitial = (id, options) => { initialOptions = options; return initialization.promise }
  const readList = (query, options) => {
    const request = deferred()
    requests.push({ ...request, query, options })
    return request.promise
  }
  const dependencies = {
    ref, reactive, toRefs, getCurrentInstance, useRequestSequence,
    useRoute: () => ({ params: { dictId: 1, jobId: 1 } }),
    useDict: () => ({ sys_normal_disable: ref([]), sys_common_status: ref([]), sys_job_group: ref([]) }),
    getDictOptionselect: async () => ({ data: [] }), getType: readInitial, getJob: readInitial,
    listData: readList, listJobLog: readList
  }
  const script = fs.readFileSync(new URL(`../../src/views/${config.path}`, import.meta.url), 'utf8')
    .match(/<script setup[^>]*>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gm, '')
  const setup = new Function(...Object.keys(dependencies), `${script}; return { queryParams, loading, handleQuery, resetQuery,
    defaultDictType: typeof defaultDictType === 'undefined' ? undefined : defaultDictType, rows: ${config.rows} }`)
  const renderer = createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, parentNode() {}, nextSibling() {}, patchProp() {} })
  let state, mounted = true
  const app = renderer.createApp({ setup() { state = setup(...Object.values(dependencies)); return () => h('div') } })
  app.config.globalProperties.addDateRange = query => query
  app.config.globalProperties.resetForm = () => {}
  app.mount({})
  const unmount = () => { if (mounted) { app.unmount(); mounted = false } }
  t.after(unmount)
  return { ...state, initialization, requests, initialOptions: () => initialOptions, unmount }
}

for (const config of pages) {
  test(`${config.path}: a user query owns filters and rows after delayed initialization`, async t => {
    const state = mountPage(t, config)
    Object.assign(state.queryParams.value, config.current)
    state.handleQuery()
    assert.equal(state.requests.length, 1)
    state.requests[0].resolve({ rows: ['B result'], total: 1 })
    await settle()
    state.initialization.resolve({ data: config.defaults })
    await settle()
    assert.equal(state.queryParams.value[config.key], 'B')
    assert.equal(state.requests.length, 1, 'an obsolete default must not start a newer list query')
    assert.deepEqual(state.rows.value, ['B result'])
    assert.equal(state.loading.value, false)
    assert.equal(state.initialOptions().isCurrentRequest(), false)
  })

  test(`${config.path}: initialization hands loading ownership to its list and settles current failure`, async t => {
    const state = mountPage(t, config)
    assert.equal(state.initialOptions().isCurrentRequest(), true)
    state.initialization.resolve({ data: config.defaults })
    await settle()
    assert.equal(state.queryParams.value[config.key], 'A')
    assert.equal(state.requests.length, 1)
    assert.equal(state.loading.value, true)
    state.requests[0].reject(new Error('current list failure'))
    await settle()
    assert.equal(state.loading.value, false)
    const failed = mountPage(t, config)
    failed.initialization.reject(new Error('current initialization failure'))
    await settle()
    assert.equal(failed.loading.value, false)
    assert.equal(failed.requests.length, 0)
  })

  test(`${config.path}: stale failures and unmounted initialization cannot take over current work`, async t => {
    const state = mountPage(t, config)
    Object.assign(state.queryParams.value, config.current)
    state.handleQuery()
    state.initialization.reject(new Error('stale initialization failure'))
    await settle()
    assert.equal(state.initialOptions().isCurrentRequest(), false)
    assert.equal(state.loading.value, true, 'obsolete initialization cannot clear the active list spinner')
    state.requests[0].resolve({ rows: ['B result'], total: 1 })
    await settle()
    assert.equal(state.loading.value, false)
    const disposed = mountPage(t, config)
    disposed.unmount()
    disposed.initialization.resolve({ data: config.defaults })
    await settle()
    assert.equal(disposed.requests.length, 0)
    assert.equal(disposed.initialOptions().isCurrentRequest(), false)
    if (disposed.defaultDictType) assert.equal(disposed.defaultDictType.value, '')
  })
}

test('dictionary reset retains the route default after a user query supersedes initialization', async t => {
  const state = mountPage(t, pages[0])
  state.queryParams.value.dictType = 'B'
  state.handleQuery()
  state.requests[0].resolve({ rows: ['B result'], total: 1 })
  await settle()
  state.initialization.resolve({ data: pages[0].defaults })
  await settle()
  assert.equal(state.queryParams.value.dictType, 'B')
  assert.equal(state.defaultDictType.value, 'A')
  state.resetQuery()
  assert.deepEqual(state.requests.map(request => request.query.dictType), ['B', 'A'])
  state.requests[1].resolve({ rows: ['A reset result'], total: 1 })
  await settle()
  assert.deepEqual(state.rows.value, ['A reset result'])
})
