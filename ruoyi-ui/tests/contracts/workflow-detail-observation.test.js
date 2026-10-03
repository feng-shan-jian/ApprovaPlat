import assert from 'node:assert/strict'
import test from 'node:test'
import { useWorkflowDetailObservation } from '../../src/views/workflow/work/useWorkflowDetailObservation.js'

async function settle() {
  for (let turn = 0; turn < 12; turn++) await Promise.resolve()
}

function fixture() {
  let now = 0, hidden = false, timerId = 0, inFlight = 0, maxInFlight = 0
  const timers = new Map(), pending = [], commits = [], invalidations = [], errors = [], blockers = new Set()
  const identity = { processInstanceId: 'instance-a', taskId: 'task-a' }
  let current = { detail: {}, waiting: null }
  const request = kind => (instanceId, taskId, background) => {
    inFlight++
    maxInFlight = Math.max(maxInFlight, inFlight)
    return new Promise((resolve, reject) => pending.push({ kind, instanceId, taskId, background, resolve, reject }))
      .finally(() => { inFlight-- })
  }
  const controller = useWorkflowDetailObservation({
    identity: () => ({ ...identity }), blocked: () => blockers.size > 0,
    requests: { detail: request('detail'), runtime: request('runtime') }, facts: () => current,
    commitDetail: (data, options) => { current = data; commits.push({ ...data, options }) },
    commitRuntime: data => { current = { detail: { ...current.detail, ...data.runtime }, waiting: data.waiting }; commits.push(data) },
    invalidate: (reason, preserveDraft) => invalidations.push({ reason, preserveDraft }),
    reportError: error => errors.push(error),
    clock: {
      now: () => now, hidden: () => hidden,
      setTimeout: (callback, delay) => { timers.set(++timerId, { at: now + delay, callback }); return timerId },
      clearTimeout: id => timers.delete(id)
    }
  })
  function response(index = pending.length - 1, overrides = {}) {
    const item = pending[index]
    const detail = { processInstanceId: item.instanceId, processStatus: 'running', currentTask: { taskId: item.taskId }, ...overrides }
    item.resolve({ data: { [item.kind === 'detail' ? 'detail' : 'runtime']: detail,
      waiting: { processInstanceId: item.instanceId, waits: [] } } })
    return settle()
  }
  async function advance(milliseconds) {
    const end = now + milliseconds
    while (true) {
      const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0]
      if (!next || next[1].at > end) break
      now = next[1].at
      timers.delete(next[0])
      next[1].callback()
      await settle()
    }
    now = end
  }
  return { controller, pending, commits, invalidations, errors, blockers, identity, response, advance,
    hide(value) { hidden = value; return controller.visibilityChanged() },
    async start() { const started = controller.start(); await response(); await started },
    get maxInFlight() { return maxInFlight }, get timerCount() { return timers.size } }
}

test('首次缓存激活只请求一次，3秒轻量与30秒完整复核共享调度', async () => {
  const f = fixture()
  const initial = f.controller.start()
  const activation = f.controller.activate()
  assert.equal(f.pending.length, 1)
  await f.response()
  await Promise.all([initial, activation])
  for (let index = 1; index <= 9; index++) {
    await f.advance(2999)
    assert.equal(f.pending.length, index)
    await f.advance(1)
    assert.equal(f.pending[index].kind, 'runtime')
    await f.response()
  }
  await f.advance(3000)
  assert.equal(f.pending[10].kind, 'detail')
  assert.equal(f.pending[10].background, true)
  await f.response()
  assert.equal(f.maxInFlight, 1)
})

test('失败依次退避6、12、24、30秒，成功恢复3秒且保留已提交事实', async () => {
  const f = fixture()
  await f.start()
  await f.advance(3000)
  for (const delay of [6000, 12000, 24000, 30000]) {
    f.pending.at(-1).reject(new Error('临时失败'))
    await settle()
    assert.equal(f.controller.ready.value, true)
    assert.equal(f.commits.length, 1)
    const count = f.pending.length
    await f.advance(delay - 1)
    assert.equal(f.pending.length, count)
    await f.advance(1)
    assert.equal(f.pending.length, count + 1)
  }
  await f.response()
  assert.equal(f.controller.observationError.value, '')
  const count = f.pending.length
  await f.advance(3000)
  assert.equal(f.pending.length, count + 1)
})

for (const blocker of ['actionBusy', 'multiInstanceBusy', 'multiInstanceRefreshing', 'urgeBusy', 'actionDialog', 'multiInstanceDialog', 'urgeDialog']) {
  test(`${blocker}阻止周期、完整复核和普通手动观察，解除后补全量`, async () => {
    const f = fixture()
    await f.start()
    f.blockers.add(blocker)
    await f.advance(33000)
    await f.controller.loadDetail(false, true)
    assert.equal(f.pending.length, 1)
    f.blockers.clear()
    await f.advance(3000)
    assert.equal(f.pending[1].kind, 'detail')
    await f.response()
  })
}

test('busy动作在finally之前可await强制全量；MI冲突保留草稿请求不死锁', async () => {
  const f = fixture()
  await f.start()
  for (const preserve of [false, true]) {
    f.blockers.add(preserve ? 'multiInstanceBusy' : 'actionBusy')
    const reload = f.controller.loadDetail(preserve)
    assert.equal(f.pending.at(-1).background, false)
    assert.equal(f.controller.loading.value, true)
    assert.deepEqual(f.invalidations.at(-1), { reason: 'reload', preserveDraft: preserve })
    await f.response()
    await reload
    assert.equal(f.controller.ready.value, true)
    assert.equal(f.commits.at(-1).options.preserveMultiInstanceDraft, preserve)
    f.blockers.clear()
  }
})

test('切任务使旧轻量响应与排队全量失效，最后一次身份独占请求槽', async () => {
  const f = fixture()
  await f.start()
  const previousSequence = f.controller.detailSequence
  await f.advance(3000)
  f.identity.taskId = 'task-b'
  const second = f.controller.routeChanged()
  f.identity.taskId = 'task-c'
  const third = f.controller.routeChanged()
  assert.equal(f.pending.length, 2)
  f.pending[1].reject({ code: 403, message: '旧任务失权' })
  await settle()
  assert.equal(f.pending.length, 3)
  assert.equal(f.pending[2].taskId, 'task-c')
  await f.response()
  await Promise.all([second, third])
  assert.equal(f.commits.length, 2)
  assert.equal(f.controller.ready.value, true)
  assert.equal(f.controller.observationError.value, '')
  assert.ok(f.controller.detailSequence > previousSequence)
  assert.equal(f.maxInFlight, 1)
})

test('排队背景全量遇到后来打开的动作弹窗不会发出或覆盖草稿', async () => {
  const f = fixture()
  await f.start()
  await f.advance(3000)
  const refresh = f.controller.loadDetail(false, true)
  f.blockers.add('actionDialog')
  await f.response()
  await refresh
  assert.equal(f.pending.length, 2)
  assert.equal(f.commits.length, 1)
})

test('隐藏使在途结果失效，恢复串行全量；缓存失活及卸载停止调度', async () => {
  const f = fixture()
  await f.start()
  await f.advance(3000)
  f.hide(true)
  assert.equal(f.controller.ready.value, false)
  assert.equal(f.invalidations.at(-1).reason, 'pause')
  await f.advance(60000)
  assert.equal(f.pending.length, 2)
  const visible = f.hide(false)
  await f.response(1)
  assert.equal(f.pending[2].kind, 'detail')
  await f.response(2)
  await visible
  assert.equal(f.commits.length, 2)
  assert.equal(f.commits.at(-1).options.background, true)
  f.controller.deactivate()
  await f.controller.routeChanged()
  await f.advance(60000)
  assert.equal(f.pending.length, 3)
  assert.equal(f.timerCount, 0)
  const active = f.controller.activate()
  await f.response()
  await active
  f.controller.deactivate()
  await f.advance(60000)
  assert.equal(f.pending.length, 4)
  assert.equal(f.maxInFlight, 1)
})

test('首次请求未结束就失活再激活仍会重新授权，首次旧结果不能恢复页面', async () => {
  const f = fixture()
  const initial = f.controller.start()
  f.controller.deactivate()
  const active = f.controller.activate()
  await f.response(0)
  assert.equal(f.commits.length, 0)
  assert.equal(f.pending.length, 2)
  await f.response(1)
  await Promise.all([initial, active])
  assert.equal(f.commits.length, 1)
  assert.equal(f.maxInFlight, 1)
})

test('挂起仍观察，轻量终态先撤销能力，全量失败继续重试且成功后才停止', async () => {
  const f = fixture()
  await f.start()
  await f.advance(3000)
  await f.response(1, { processStatus: 'suspended' })
  assert.equal(f.controller.ready.value, false)
  await f.response(2, { processStatus: 'suspended' })
  await f.advance(3000)
  await f.response(3, { processStatus: 'completed' })
  assert.equal(f.controller.ready.value, false)
  assert.equal(f.pending[4].kind, 'detail')
  f.pending[4].reject(new Error('终态全量失败'))
  await settle()
  await f.advance(5999)
  assert.equal(f.pending.length, 5)
  await f.advance(1)
  assert.equal(f.pending[5].kind, 'detail')
  await f.response(5, { processStatus: 'completed' })
  assert.equal(f.controller.ready.value, true)
  await f.advance(90000)
  assert.equal(f.pending.length, 6)
})

for (const code of [403, 404]) {
  test(`${code}撤销详情上下文及MI动作恢复，停止后续调度`, async () => {
    const f = fixture()
    await f.start()
    const sequence = f.controller.detailSequence
    await f.advance(3000)
    f.pending[1].reject({ code, message: '失去访问资格' })
    await settle()
    assert.equal(f.controller.ready.value, false)
    assert.equal(f.invalidations.at(-1).reason, 'denied')
    assert.ok(f.controller.detailSequence > sequence)
    await f.advance(60000)
    assert.equal(f.pending.length, 2)
  })
}

test('跨实例响应不能提交，独立MI恢复失权也可立即失效观察', async () => {
  const f = fixture()
  await f.start()
  await f.advance(3000)
  await f.response(1, { processInstanceId: 'other-instance' })
  assert.equal(f.commits.length, 1)
  assert.match(f.controller.observationError.value, /响应不完整/)
  f.controller.failed({ code: 403 })
  assert.equal(f.controller.ready.value, false)
  await f.advance(60000)
  assert.equal(f.pending.length, 2)
})
