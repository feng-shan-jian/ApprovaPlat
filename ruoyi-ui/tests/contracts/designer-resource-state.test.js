import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
import { createDesignerFormFieldCatalog } from '../../src/components/workflow/designer/designerFormFieldCatalog.js'
import { createExtensionEventSlaDomain } from '../../src/components/workflow/designer/extensionEventSlaDomain.js'
import { createDefaultSlaConfig } from '../../src/components/workflow/designer/userTaskSlaDomain.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

test('字段目录复用同版请求，失败可重试，过期成功和失败不能覆盖最新快照', async () => {
  const requests = []
  const catalog = createDesignerFormFieldCatalog({ loadAuthorFields: xml => {
    const request = deferred()
    requests.push({ xml, ...request })
    return request.promise
  } })
  const process = { $type: 'bpmn:Process', id: 'process' }
  const node = { $type: 'bpmn:UserTask', id: 'task', $parent: process }
  const snapshot = value => ({ nodes: [{ processKey: 'process', nodeKey: 'task', value }], processes: [], issues: [] })
  const first = catalog.refresh('first')
  const reused = catalog.refresh('first')
  assert.equal(requests.length, 1)
  requests[0].reject(new Error('目录读取失败'))
  await Promise.all([first, reused])
  assert.equal(catalog.issueFor(node), '目录读取失败')
  const retry = catalog.refresh('first')
  requests[1].resolve({ data: snapshot('重试') })
  await retry
  assert.equal(catalog.issueFor(node), '')
  assert.equal(catalog.nodeFields(node).value, '重试')
  const slow = catalog.refresh('slow')
  const fast = catalog.refresh('fast')
  requests[3].resolve({ data: snapshot('最新') })
  await fast
  requests[2].resolve({ data: snapshot('过期') })
  await slow
  assert.equal(catalog.nodeFields(node).value, '最新')
  const obsolete = catalog.refresh('obsolete')
  catalog.invalidate()
  const current = catalog.refresh('current')
  requests[5].resolve({ data: snapshot('当前') })
  await current
  requests[4].reject(new Error('过期错误'))
  await obsolete
  assert.equal(catalog.nodeFields(node).value, '当前')
  assert.equal(catalog.issueFor(node), '')
})

test('共享资源加载保留各目录独立结果和错误，旧请求不能结束新请求的 loading', async () => {
  const requests = [], errors = []
  const load = name => () => {
    const request = deferred()
    requests.push({ name, ...request })
    return request.promise
  }
  const domain = createExtensionEventSlaDomain({
    emit: (_event, error) => errors.push(error.message), formFieldOptions: ref([]),
    listJavaExtensionOptions: load('java'), listCelExtensionOptions: load('cel'),
    listHttpExtensionOptions: load('http'), listSqlExtensionOptions: load('sql'),
    listFormFieldExtensionOptions: load('field'), listConnectorEndpointOptions: load('endpoint'),
    listSqlDataSourceOptions: load('source'), listBpmnEventCodeOptions: type => load(type)(),
    listEnabledSlaCalendars: load('calendar')
  })
  assert.equal(domain.extensionLoading, domain.eventCodeLoading)
  assert.equal(domain.extensionLoading, domain.slaLoading)
  const first = domain.loadExtensionOptions()
  assert.equal(domain.slaLoading.value, true)
  for (const request of requests.slice(0, 10)) request.resolve({ data: [{ name: `${request.name}-initial` }] })
  await first
  assert.equal(domain.slaLoading.value, false)
  const slow = domain.loadExtensionOptions()
  const fast = domain.loadExtensionOptions()
  for (const request of requests.slice(10, 20)) request.resolve({ data: [{ name: 'obsolete' }] })
  await slow
  assert.equal(domain.extensionLoading.value, true)
  assert.deepEqual(domain.connectorEndpoints.value, [{ name: 'endpoint-initial' }])
  for (const request of requests.slice(20)) {
    if (request.name === 'calendar') request.reject(new Error('日历不可用'))
    else request.resolve({ data: [{ name: `${request.name}-current` }] })
  }
  await fast
  assert.equal(domain.eventCodeLoading.value, false)
  assert.deepEqual(domain.connectorEndpoints.value, [{ name: 'endpoint-current' }])
  assert.deepEqual(domain.slaCalendarOptions.value, [{ name: 'calendar-initial' }])
  assert.deepEqual(Object.keys(domain.resourceLoadErrors.value), ['SLA 日历'])
  assert.deepEqual(errors, ['日历不可用'])
  const stale = domain.loadExtensionOptions()
  domain.invalidateResourceRequests()
  for (const request of requests.slice(30)) request.reject(new Error('销毁后的请求'))
  await stale
  assert.deepEqual(errors, ['日历不可用'])
})

test('SLA 默认配置每次创建独立对象且八字段默认值稳定', () => {
  const first = createDefaultSlaConfig()
  const second = createDefaultSlaConfig()
  first.calendarKey = 'changed'
  assert.deepEqual(second, {
    enabled: false, calendarKey: '', reminderMinutes: 60, reminderRepeatMinutes: 60,
    maxReminders: 1, escalationMinutes: 240, escalationUserId: '', escalationEventCode: ''
  })
})
