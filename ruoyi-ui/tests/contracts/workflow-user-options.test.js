import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, ref } from 'vue'
import { useWorkflowUserOptions } from '../../src/components/workflow/useWorkflowUserOptions.js'

function selector(t, selected = ref([]), config) {
  const requests = []
  const scope = effectScope()
  const state = scope.run(() => useWorkflowUserOptions(query => new Promise((resolve, reject) => {
    requests.push({ query, resolve: rows => resolve({ rows }), reject })
  }), () => selected.value, config))
  t.after(() => scope.stop())
  return { state, requests, scope, selected }
}
const user = (value, label = `用户${value}`) => ({ type: 'user', value, label })

test('详情选择器保持 30 条、各自缓存独立，reset 和销毁丢弃已发出的响应', async t => {
  const approval = selector(t, ref(['1']))
  const copy = selector(t, ref(['2']))
  const first = approval.state.search(' a ')
  const second = copy.state.search(' b ')
  assert.deepEqual(approval.requests[0].query, { keyword: 'a', pageNum: 1, pageSize: 30 })
  approval.requests[0].resolve([user(1)])
  copy.requests[0].resolve([user(2)])
  await Promise.all([first, second])
  assert.deepEqual(approval.state.options.value.map(option => option.value), ['1'])
  assert.deepEqual(copy.state.options.value.map(option => option.value), ['2'])
  const staleReset = approval.state.search('pending')
  approval.state.reset({ keepSelected: true })
  approval.requests[1].resolve([user(3)])
  await staleReset
  assert.deepEqual(approval.state.options.value.map(option => option.value), ['1'])
  assert.equal(approval.state.loading.value, false)
  const staleDispose = copy.state.search('pending')
  copy.scope.stop()
  copy.requests[1].resolve([user(4)])
  await staleDispose
  assert.deepEqual(copy.state.options.value, [])
  assert.equal(copy.state.loading.value, false)
})

test('交错请求只有最新响应更新，失败后保留多节点已选标签且可重试', async t => {
  const { state, requests, selected } = selector(t, ref([]), { pageSize: 50 })
  const initial = state.search('')
  requests[0].resolve([user(1, '张一'), user(2, '李二')])
  await initial
  selected.value = ['1', '2']
  const slow = state.search('slow')
  const fast = state.search('fast')
  requests[2].resolve([user(3, '最新')])
  await fast
  requests[1].resolve([user(4, '旧结果')])
  await slow
  assert.deepEqual(state.options.value.map(option => option.label), ['张一', '李二', '最新'])
  const failure = state.search('failure')
  requests[3].reject(new Error('目录暂时不可用'))
  await assert.rejects(failure, /目录暂时不可用/)
  assert.equal(state.loading.value, false)
  assert.deepEqual(state.options.value.map(option => option.label), ['张一', '李二', '最新'])
  const retry = state.search(` ${'x'.repeat(65)} `)
  assert.deepEqual(requests[4].query, { keyword: 'x'.repeat(64), pageNum: 1, pageSize: 50 })
  requests[4].resolve([user(5, '重试成功'), { type: 'role', value: 'ROLE1', label: '非用户' }])
  await retry
  assert.deepEqual(state.options.value.map(option => option.label), ['张一', '李二', '重试成功'])
})
