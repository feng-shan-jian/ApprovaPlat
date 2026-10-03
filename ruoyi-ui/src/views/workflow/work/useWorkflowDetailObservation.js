import { reactive, toRefs } from 'vue'

const terminal = status => ['completed', 'canceled', 'rejected', 'terminated'].includes(status)
const browserClock = {
  now: () => Date.now(),
  hidden: () => document.hidden,
  setTimeout: (callback, delay) => window.setTimeout(callback, delay),
  clearTimeout: timer => window.clearTimeout(timer)
}

/** 详情观察的唯一请求槽和调度所有者；表单、弹窗及 MI 动作恢复由页面提交回调维护。 */
export function useWorkflowDetailObservation({
  identity, blocked, requests, facts, commitDetail, commitRuntime, invalidate, reportError,
  clock = browserClock
}) {
  const state = reactive({ loading: false, ready: false, observationError: '', observationBusy: false })
  let epoch = 0, detailSequence = 0, timer, failures = 0, active = true, stopped = false
  let request = null, lastFullAt = null, initialLoad = null, initialized = false

  function currentIdentity(expected) {
    const actual = identity()
    return actual.processInstanceId === expected.processInstanceId && actual.taskId === expected.taskId
      && active && !clock.hidden()
  }

  function schedule() {
    clock.clearTimeout(timer)
    if (active && !clock.hidden() && !stopped && !request) {
      const delay = Math.min(3000 * (2 ** failures), 30000)
      const reviewDue = Math.max(0, 30000 - (clock.now() - lastFullAt))
      timer = clock.setTimeout(refresh,
        failures || state.loading || lastFullAt === null || blocked() ? delay : Math.min(delay, reviewDue))
    }
  }

  function failed(error) {
    failures += 1
    state.observationError = error?.message || '状态刷新失败，当前显示上次成功查询的结果，稍后重试'
    if ([403, 404].includes(Number(error?.code))) {
      epoch += 1
      detailSequence += 1
      state.ready = false
      state.loading = false
      stopped = true
      clock.clearTimeout(timer)
      invalidate('denied')
    }
  }

  /** 动作成功和指定冲突恢复可在 busy=true 时强制全量；普通观察始终服从 blocked。 */
  async function loadDetail(preserveMultiInstanceDraft = false, background = false) {
    if (background && blocked()) { schedule(); return }
    epoch += 1
    const sequence = ++detailSequence
    clock.clearTimeout(timer)
    stopped = false
    const expected = identity()
    const invalid = !expected.processInstanceId || expected.processInstanceId.length > 64
      ? '流程实例主键不合法' : expected.taskId.length > 64 ? '任务主键不合法' : ''
    if (invalid) {
      state.ready = false
      state.loading = false
      stopped = true
      invalidate('reload')
      reportError(new Error(invalid), invalid)
      return
    }
    if (!background) {
      state.loading = true
      state.ready = false
      lastFullAt = null
      invalidate('reload', preserveMultiInstanceDraft)
    }
    let pending
    const current = () => sequence === detailSequence && currentIdentity(expected)
    try {
      // 旧请求失效后仍须等其结束，任何刷新入口都不能另开观察通道。
      while (request) await request.catch(() => {})
      if (!current() || (background && blocked())) return
      pending = requests.detail(expected.processInstanceId, expected.taskId || undefined, background)
      request = pending
      const response = await pending
      if (!current() || (background && blocked())) return
      const data = response.data
      if (data?.detail?.processInstanceId !== expected.processInstanceId
        || data?.waiting?.processInstanceId !== expected.processInstanceId) {
        throw new Error('流程详情实例关系不一致')
      }
      commitDetail(data, { background, preserveMultiInstanceDraft })
      state.observationError = ''
      failures = 0
      lastFullAt = clock.now()
      stopped = terminal(data.detail.processStatus)
      state.ready = true
    } catch (error) {
      if (current()) {
        failed(error)
        if (!background) reportError(error, '流程详情加载失败')
      }
    } finally {
      if (request === pending) request = null
      if (sequence === detailSequence) state.loading = false
      schedule()
    }
  }

  async function refresh() {
    if (!active || clock.hidden() || stopped) return
    if (state.loading || request || blocked()) { schedule(); return }
    const previous = facts()
    if (!state.ready || terminal(previous.detail.processStatus) || clock.now() - lastFullAt >= 30000) {
      await loadDetail(false, true)
      return
    }
    const sequence = ++epoch
    const contextSequence = detailSequence
    const expected = identity()
    const current = () => sequence === epoch && contextSequence === detailSequence && currentIdentity(expected)
    state.observationBusy = true
    let fullReview = false, pending
    try {
      pending = requests.runtime(expected.processInstanceId, expected.taskId || undefined)
      request = pending
      const response = await pending
      if (!current() || blocked()) return
      const data = response.data
      if (data?.runtime?.processInstanceId !== expected.processInstanceId
        || data?.waiting?.processInstanceId !== expected.processInstanceId) {
        throw new Error('运行状态响应不完整')
      }
      // observedAt 不代表业务变化；比较任务、状态、能力、修订和等待事实。
      const latest = facts()
      fullReview = Object.keys(data.runtime).some(key => JSON.stringify(data.runtime[key]) !== JSON.stringify(latest.detail[key]))
        || JSON.stringify(data.waiting.waits) !== JSON.stringify(latest.waiting?.waits)
      if (fullReview) state.ready = false
      else commitRuntime(data)
      state.observationError = ''
      failures = 0
    } catch (error) {
      if (current()) failed(error)
    } finally {
      if (request === pending) request = null
      state.observationBusy = false
      // 终态轻量事实也必须成功提交完整详情后才能停止；失败时 ready=false 继续全量退避。
      if (fullReview && current() && !blocked()) await loadDetail(false, true)
      else schedule()
    }
  }

  function pause() {
    epoch += 1
    detailSequence += 1
    state.ready = false
    state.loading = false
    invalidate('pause')
    clock.clearTimeout(timer)
  }

  function visibilityChanged() {
    if (clock.hidden()) pause()
    else if (active) return loadDetail(false, true)
  }

  function start() {
    initialLoad = loadDetail().finally(() => { initialized = true })
    return initialLoad
  }

  function activate() {
    const wasActive = active
    active = true
    if (wasActive && !initialized) return initialLoad
    return loadDetail(false, true)
  }

  function deactivate() {
    active = false
    pause()
  }

  function routeChanged() {
    if (active) return loadDetail()
  }

  return {
    ...toRefs(state),
    get detailSequence() { return detailSequence },
    loadDetail, failed, start, activate, deactivate, routeChanged, visibilityChanged
  }
}
