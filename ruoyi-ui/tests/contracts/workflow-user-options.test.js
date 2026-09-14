import assert from 'node:assert/strict'
import test from 'node:test'
import { effectScope, ref } from 'vue'
import { useWorkflowUserOptions } from '../../src/components/workflow/useWorkflowUserOptions.js'

function selector(t) {
  const requests = []
  const selected = ref([])
  const scope = effectScope()
  const state = scope.run(() => useWorkflowUserOptions(query => new Promise((resolve, reject) => {
    requests.push({ query, resolve, reject })
  }), () => selected.value))
  t.after(() => scope.stop())
  return { ...state, requests, selected, scope }
}

const user = (value, label = `用户${value}`) => ({ value, label, type: 'user' })

test('旧搜索不能覆盖新结果或提前解除加载态，保留已选标签和服务端顺序', async t => {
  const state = selector(t)
  const first = state.search('  李  ')
  assert.deepEqual(state.requests[0].query, { keyword: '李', pageNum: 1, pageSize: 30 })
  state.requests[0].resolve({ rows: [user(1), user(2)] })
  await first
  state.selected.value = ['1']
  const stale = state.search('旧')
  const current = state.search('新')
  state.requests[1].resolve({ rows: [user(9)] })
  await stale
  assert.equal(state.loading.value, true)
  state.requests[2].resolve({ rows: [user(3), { value: 'ROLE1', type: 'role' }, user(2)] })
  await current
  assert.deepEqual(state.options.value, [user('1'), user('3'), user('2')])
  state.reset({ keepSelected: true })
  assert.deepEqual(state.options.value, [user('1')])
})

test('关闭重开或切换任务后，前一轮响应不能恢复缓存或覆盖新加载态', async t => {
  const state = selector(t)
  const previous = state.search('旧任务')
  state.reset()
  const next = state.search('新任务')
  state.requests[0].resolve({ rows: [user(1)] })
  await previous
  assert.deepEqual(state.options.value, [])
  assert.equal(state.loading.value, true)
  state.requests[1].resolve({ rows: [user(2)] })
  await next
  assert.deepEqual(state.options.value, [user('2')])
  state.scope.stop()
  assert.deepEqual(state.options.value, [])
})

test('审批、抄送和加签的请求与标签缓存完全独立', async t => {
  const selectors = [selector(t), selector(t), selector(t)]
  const pending = selectors.map(state => state.search('张'))
  selectors.forEach((state, index) => state.requests[0].resolve({ rows: [user(index + 1)] }))
  await Promise.all(pending)
  selectors.forEach((state, index) => {
    state.selected.value = ['1', '2', '3']
    state.reset({ keepSelected: true })
    assert.deepEqual(state.options.value, [user(String(index + 1))])
  })
})

test('目录失败继续抛出原异常，关闭后到达的失败不干扰下一次搜索', async t => {
  const state = selector(t)
  const failed = state.search('失败')
  const error = new Error('目录不可用')
  const rejected = assert.rejects(failed, failure => failure === error)
  state.reset()
  const current = state.search('重试')
  state.requests[0].reject(error)
  await rejected
  assert.equal(state.loading.value, true)
  state.requests[1].resolve({ rows: [] })
  await current
  assert.equal(state.loading.value, false)
})
