import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import { createRenderer, h } from 'vue'
import { createRequestSequence, useRequestSequence } from '../../src/utils/requestSequence.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

test('category page commits only the latest response and latest loading completion', async () => {
  const source = fs.readFileSync(new URL('../../src/views/workflow/category/index.vue', import.meta.url), 'utf8')
  const body = source.match(/async function loadList\(\) \{[\s\S]*?\n\}/)[0]
  const requests = [], rows = { value: [] }, total = { value: 0 }, loading = { value: false }
  const listRequest = createRequestSequence()
  const load = new Function('listCategories', 'rows', 'total', 'loading', 'queryParams', 'listRequest', `${body}; return loadList`)(
    query => { const request = deferred(); requests.push({ ...request, query }); return request.promise }, rows, total, loading,
    { categoryName: 'original' }, listRequest)
  const old = load()
  const current = load()
  requests[0].resolve({ rows: ['old'], total: 1 })
  await old
  assert.equal(loading.value, true)
  assert.deepEqual(rows.value, [])
  requests[1].resolve({ rows: ['new'], total: 2 })
  await current
  assert.deepEqual(rows.value, ['new'])
  assert.equal(total.value, 2)
  assert.equal(loading.value, false)
})

function componentFunctions(path, names, dependencies) {
  const source = fs.readFileSync(new URL(`../../src/${path}`, import.meta.url), 'utf8')
  const body = names.map(name => source.match(new RegExp(`(?:async )?function ${name}\\([^\\n]*[\\s\\S]*?\\n\\}`))[0]).join('\n')
  return new Function(...Object.keys(dependencies), `${body}; return { ${names.join(', ')} }`)(...Object.values(dependencies))
}

test('Vue component unmount invalidates pending ownership and result sets stay independent', () => {
  const renderer = createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, parentNode() {}, nextSibling() {}, patchProp() {} })
  let first, second
  const app = renderer.createApp({ setup() { first = useRequestSequence(); second = useRequestSequence(); return () => h('div') } })
  app.mount({})
  const old = first.begin(), current = first.begin(), independent = second.begin()
  assert.equal(old(), false)
  assert.equal(current(), true)
  assert.equal(independent(), true)
  first.invalidate()
  assert.equal(current(), false)
  assert.equal(independent(), true)
  app.unmount()
  assert.equal(independent(), false)
})

test('extension directory settles current errors independently and late failures cannot replace current results', async () => {
  const requests = [], rows = { value: [] }, handlers = { value: [] }, loading = { value: false }
  const registryError = { value: '' }, handlerError = { value: '' }
  const load = () => { const request = deferred(); requests.push(request); return request.promise }
  const { loadRegistry } = componentFunctions('views/workflow/extension/index.vue', ['loadRegistry'], {
    registryRequest: createRequestSequence(), rows, handlers, loading, registryError, handlerError,
    invalidateDeletion() {}, applyFilters() {}, listWorkflowExtensions: load, listInstalledJavaHandlers: load
  })
  const old = loadRegistry(), current = loadRegistry()
  requests[0].reject(new Error('stale directory'))
  requests[1].reject(new Error('stale handlers'))
  await old
  assert.equal(loading.value, true)
  assert.equal(registryError.value, '')
  assert.equal(handlerError.value, '')
  requests[2].resolve({ data: ['current'] })
  requests[3].reject(new Error('current handlers'))
  await current
  assert.deepEqual(rows.value, ['current'])
  assert.match(handlerError.value, /已安装处理器加载失败/)
  assert.equal(loading.value, false)
})

test('collaboration confirms a frozen direction and cannot act after change, close or replacement', async () => {
  const confirmations = [], calls = [], activeDirection = { value: 'OUTBOUND' }
  const operationRequest = createRequestSequence()
  const { retryRow } = componentFunctions('views/workflow/collaboration/index.vue', ['retryRow'], {
    operationRequest, activeDirection, proxy: { $modal: { confirm: () => { const request = deferred(); confirmations.push(request); return request.promise }, msgSuccess() {} } },
    retryCollaborationOutbox: id => calls.push(['out', id]), retryCollaborationInbound: id => calls.push(['in', id]), loadRows() {}
  })
  const changed = retryRow({ messageId: 'A', messageName: 'A', retryAllowed: true })
  activeDirection.value = 'INBOUND'
  confirmations[0].resolve()
  await changed
  assert.deepEqual(calls, [])
  const closed = retryRow({ messageId: 'B', messageName: 'B', retryAllowed: true })
  operationRequest.invalidate()
  confirmations[1].resolve()
  await closed
  assert.deepEqual(calls, [])
  const current = retryRow({ messageId: 'C', messageName: 'C', retryAllowed: true })
  confirmations[2].resolve()
  await current
  assert.deepEqual(calls, [['in', 'C']])
})

test('cache cascading selection invalidates old keys and old values before the next response', async () => {
  const keys = [], values = [], keysRequest = createRequestSequence(), valueRequest = createRequestSequence()
  const cacheKeys = { value: [] }, cacheForm = { value: {} }, nowCacheName = { value: '' }, subLoading = { value: false }
  const { getCacheKeys, handleCacheValue } = componentFunctions('views/monitor/cache/list.vue', ['getCacheKeys', 'handleCacheValue'], {
    keysRequest, valueRequest, cacheKeys, cacheForm, nowCacheName, subLoading,
    listCacheKey: name => { const request = deferred(); keys.push({ ...request, name }); return request.promise },
    getCacheValue: (name, key) => { const request = deferred(); values.push({ ...request, name, key }); return request.promise }
  })
  const first = getCacheKeys({ cacheName: 'A' })
  keys[0].resolve({ data: ['A:key'] })
  await first
  const oldValue = handleCacheValue('A:key')
  const second = getCacheKeys({ cacheName: 'B' })
  assert.equal(nowCacheName.value, 'B')
  assert.deepEqual(cacheForm.value, {})
  values[0].resolve({ data: { cacheValue: 'obsolete' } })
  await oldValue
  assert.deepEqual(cacheForm.value, {})
  keys[1].resolve({ data: ['B:key'] })
  await second
  assert.deepEqual(cacheKeys.value, ['B:key'])
  assert.equal(subLoading.value, false)
})

test('notification outbox still requires audit permission before sending a request', async () => {
  let calls = 0
  const canAuditOutbox = { value: false }
  const { loadOutbox } = componentFunctions('views/workflow/notification/index.vue', ['loadOutbox'], {
    canAuditOutbox, outboxRequest: createRequestSequence(), outboxLoading: { value: false },
    outboxQuery: { pageNum: 1, pageSize: 10, keyword: '' }, outbox: { value: [] }, outboxTotal: { value: 0 },
    listWorkflowNotificationOutbox: async () => { calls += 1; return { rows: [], total: 0 } }
  })
  await loadOutbox()
  assert.equal(calls, 0)
  canAuditOutbox.value = true
  await loadOutbox()
  assert.equal(calls, 1)
})

test('integration credential dialog only becomes ready after the current endpoint catalog loads', async () => {
  const requests = [], createRequest = createRequestSequence()
  const endpoints = { value: [] }, createLoading = { value: false }, createOptionsReady = { value: false }
  const { openCreate } = componentFunctions('views/workflow/integrationCredential/index.vue', ['openCreate'], {
    createRequest, endpoints, createLoading, createOptionsReady, form: {}, emptyForm: () => ({}),
    bindEndpointId: { value: null }, canBindConnector: { value: true }, createOpen: { value: false },
    nextTick: callback => callback(), formRef: { value: null },
    listConnectorEndpoints: () => { const request = deferred(); requests.push(request); return request.promise }
  })
  const old = openCreate()
  createRequest.invalidate()
  const current = openCreate()
  requests[0].resolve({ data: ['old'] })
  await old
  assert.equal(createLoading.value, true)
  assert.equal(createOptionsReady.value, false)
  requests[1].resolve({ data: ['current'] })
  await current
  assert.deepEqual(endpoints.value, ['current'])
  assert.equal(createLoading.value, false)
  assert.equal(createOptionsReady.value, true)
})
