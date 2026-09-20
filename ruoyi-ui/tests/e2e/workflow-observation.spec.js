import { expect, test } from '@playwright/test'

// 真实页面与 HTTP 客户端的调度验收；受控响应不替代 RuntimeFactReuse 的真实引擎验证。
const endpoint = '/workflow/process/observation'
const content = JSON.stringify({ fields: [{ vModel: 'note', label: '审批备注', tag: 'el-input', layout: 'colFormItem', workflowReadable: true, workflowWritable: true }] })
const form = () => ({ formName: '办理表单', content, values: { note: '服务端原值' } })
const runtimeKeys = ['processInstanceId', 'processStatus', 'currentTask', 'nextUserAssignmentPolicy', 'multiInstanceState',
  'completeAllowed', 'rejectAllowed', 'returnAllowed', 'delegateAllowed', 'transferAllowed', 'resolveAllowed', 'resubmitAllowed', 'urgeAllowed']

async function openDetail(page) {
  const state = {
    detail: {
      processInstanceId: 'instance-a', processName: '观察验收', processStatus: 'running', version: 1,
      currentTask: { taskId: 'task-a', taskName: '审批', assignee: '1', active: true, unclaimAllowed: true },
      nextUserAssignmentPolicy: 'DISABLED', multiInstanceState: null,
      completeAllowed: true, rejectAllowed: true, returnAllowed: true, delegateAllowed: true, transferAllowed: true,
      resolveAllowed: false, resubmitAllowed: false, urgeAllowed: true,
      formDetail: { applicationForm: form(), nodeTaskForm: form(), historyForms: [], returnedApplication: false },
      historyProcNodeList: [], controlledLoopStates: [], processRelations: [], flowViewer: { activityInstances: [] }, bpmnXml: ''
    },
    waiting: { processInstanceId: 'instance-a', localTimerExecutionReady: true, truncated: false,
      waits: [{ type: 'RECEIVE', nodeId: 'wait', eventName: '收件', executionId: 'execution-a', state: 'running' }] },
    full: 0, light: 0, inFlight: 0, maxInFlight: 0, fullFailure: 0, lightFailure: 0, hold: null, miFailure: 0, completions: []
  }
  await page.context().addCookies([{ name: 'Admin-Token', value: 'observation-frontend-only', url: test.info().project.use.baseURL }])
  await page.route('**/dev-api/**', async route => {
    const path = new URL(route.request().url()).pathname.replace('/dev-api', '')
    const reply = data => route.fulfill({ json: { code: 200, ...data } })
    if (path === '/getInfo') return reply({ user: { userId: 1, userName: '观察测试' }, roles: ['admin'], permissions: ['*:*:*'] })
    if (path === '/getRouters') return reply({ data: [] })
    if (path === '/workflow/task/complete') {
      state.completions.push(route.request().postDataJSON())
      return reply({ code: 409, subCode: 'WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT', msg: '成员修订冲突' })
    }
    if (path.startsWith('/workflow/task/multiInstance/')) return reply(state.miFailure
      ? { code: state.miFailure, msg: '会签任务失权' } : { data: state.detail.multiInstanceState })
    if (path !== endpoint && path !== `${endpoint}/runtime`) return reply({ data: [], rows: [], total: 0 })
    const full = path === endpoint
    state[full ? 'full' : 'light']++
    state.inFlight++
    state.maxInFlight = Math.max(state.maxInFlight, state.inFlight)
    const snapshot = structuredClone(full ? { detail: state.detail, waiting: state.waiting }
      : { runtime: Object.fromEntries(runtimeKeys.map(key => [key, state.detail[key]])), waiting: state.waiting })
    const failure = state[full ? 'fullFailure' : 'lightFailure']
    if (state.hold === (full ? 'full' : 'light')) {
      state.hold = null
      await new Promise(resolve => { state.release = resolve })
    }
    state.inFlight--
    return reply(failure ? { code: failure, msg: `观察失败${failure}` } : { data: { observedAt: new Date().toISOString(), ...snapshot } })
  })
  await page.clock.install({ time: new Date('2026-09-20T00:00:00Z') })
  await page.clock.pauseAt(new Date('2026-09-20T00:01:00Z'))
  await page.goto('/workflow/process-detail/instance-a?taskId=task-a')
  await expect(page.getByRole('textbox', { name: '审批备注', exact: true })).toBeVisible()
  return state
}

const note = page => page.getByRole('textbox', { name: '审批备注', exact: true })
async function tick(page, state, full = false, milliseconds = 3001) {
  const before = state[full ? 'full' : 'light']
  await page.clock.runFor(milliseconds)
  await expect.poll(() => state[full ? 'full' : 'light']).toBeGreaterThan(before)
  await expect.poll(() => state.inFlight).toBe(0)
}

test('3秒轻量、30秒历史复核和手动全量均保留同任务输入与当前页签', async ({ page }) => {
  const state = await openDetail(page)
  await note(page).fill('未提交输入')
  await tick(page, state)
  await tick(page, state)
  expect(state.full).toBe(1)
  await expect(note(page)).toHaveValue('未提交输入')
  state.detail.formDetail.historyForms.push({ ...form(), formName: '新增历史提交' })
  for (let i = 0; i < 7; i++) await tick(page, state)
  await tick(page, state, true, 2991)
  expect(state.full).toBe(2)
  await expect(page.getByRole('tab', { name: '历史表单 (1)' })).toBeVisible()
  await expect(note(page)).toHaveValue('未提交输入')
  await page.getByRole('tab', { name: '历史表单 (1)' }).click()
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect.poll(() => state.full).toBe(3)
  await expect(page.getByRole('tab', { name: '历史表单 (1)' })).toHaveAttribute('aria-selected', 'true')
  expect(state.maxInFlight).toBe(1)
})

test('转办、退回新任务与MI修订触发全量，换办理人清除旧草稿', async ({ page }) => {
  const state = await openDetail(page)
  await note(page).fill('旧办理人草稿')
  state.detail.currentTask.assignee = '2'
  state.detail.completeAllowed = false
  await tick(page, state, true)
  await expect(note(page)).toHaveValue('服务端原值')
  state.detail.processStatus = 'returned'
  state.detail.currentTask = { taskId: 'applicant', active: true, assignee: '1' }
  state.detail.resubmitAllowed = true
  state.detail.formDetail.returnedApplication = true
  state.detail.formDetail.nodeTaskForm = null
  await tick(page, state, true)
  await expect(page.getByRole('button', { name: '重新提交', exact: true })).toBeVisible()
  await note(page).fill('申请修改')
  state.detail.processStatus = 'running'
  state.detail.currentTask = { taskId: 'new-review', active: true, assignee: '1' }
  state.detail.completeAllowed = true
  state.detail.resubmitAllowed = false
  state.detail.formDetail.nodeTaskForm = form()
  state.detail.multiInstanceState = { mode: 'ALL', activityId: 'review', revision: 1, adjustmentAllowed: true, addAllowed: true, remainingCapacity: 4, members: [] }
  await tick(page, state, true)
  await note(page).fill('会签办理草稿')
  state.detail.multiInstanceState.revision = 2
  await tick(page, state, true)
  // 全量重授权期间卸载的 Element Plus 标签需要完成进入动画。
  await page.clock.runFor(300)
  await expect(page.locator('.workflow-detail__multi-instance').getByText('版本 2', { exact: true })).toBeVisible()
  await expect(note(page)).toHaveValue('会签办理草稿')
})

test('挂起继续观察恢复；终态最终全量失败后重试，成功才停止', async ({ page }) => {
  const state = await openDetail(page)
  state.detail.processStatus = 'suspended'
  state.detail.completeAllowed = false
  await tick(page, state, true)
  const full = state.full
  await tick(page, state)
  expect(state.full).toBe(full)
  state.detail.processStatus = 'running'
  state.detail.completeAllowed = true
  await tick(page, state, true)
  state.detail.processStatus = 'completed'
  state.detail.completeAllowed = false
  state.detail.currentTask.active = false
  state.fullFailure = 500
  await tick(page, state, true)
  await expect(page.getByText('观察失败500', { exact: true })).toBeVisible()
  state.fullFailure = 0
  await tick(page, state, true, 6001)
  await expect(page.locator('.workflow-detail__meta').getByText('已完成', { exact: true })).toBeVisible()
  const stopped = [state.full, state.light]
  await page.clock.runFor(90000)
  expect([state.full, state.light]).toEqual(stopped)
})

test('隐藏丢弃在途旧响应，回归串行全量重授权且保留同任务输入', async ({ page }) => {
  const state = await openDetail(page)
  await note(page).fill('隐藏前输入')
  state.hold = 'light'
  await page.clock.runFor(3001)
  await expect.poll(() => Boolean(state.release)).toBe(true)
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  const before = [state.full, state.light]
  await page.clock.runFor(40000)
  expect([state.full, state.light]).toEqual(before)
  state.detail.processName = '重新授权后的详情'
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  state.release()
  await expect(page.getByRole('heading', { name: '重新授权后的详情' })).toBeVisible()
  await expect(note(page)).toHaveValue('隐藏前输入')
  expect(state.full).toBe(2)
  expect(state.maxInFlight).toBe(1)
})

for (const code of [403, 404]) {
  test(`${code}关闭全部动作并停止；旧MI、取消认领和选人能力不残留`, async ({ page }) => {
    const state = await openDetail(page)
    state.detail.multiInstanceState = { mode: 'ALL', activityId: 'review', revision: 1, adjustmentAllowed: true, addAllowed: true, remainingCapacity: 4, members: [] }
    state.detail.nextUserAssignmentPolicy = 'REQUIRED_ALL'
    await tick(page, state, true)
    await expect(page.locator('.workflow-detail__actions').getByRole('button', { name: '加签', exact: true })).toBeVisible()
    state.lightFailure = code
    await tick(page, state)
    await expect(page.locator('.workflow-detail__actions')).toHaveCount(0)
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.locator('.workflow-detail__multi-instance')).toHaveCount(0)
    const stopped = [state.full, state.light]
    await page.clock.runFor(60000)
    expect([state.full, state.light]).toEqual(stopped)
  })
}

test('失败指数退避；打开动作弹窗时既不请求也不重绑输入', async ({ page }) => {
  const state = await openDetail(page)
  state.lightFailure = 500
  await tick(page, state)
  const first = state.light
  await page.clock.runFor(5000)
  expect(state.light).toBe(first)
  await tick(page, state, false, 1001)
  await page.clock.runFor(11000)
  expect(state.light).toBe(first + 1)
  state.lightFailure = 0
  await tick(page, state, false, 1001)
  await page.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '通过任务' })
  await dialog.getByPlaceholder('请输入审批意见').fill('正在输入意见')
  const before = [state.full, state.light]
  await page.clock.runFor(40000)
  expect([state.full, state.light]).toEqual(before)
  await expect(dialog.getByPlaceholder('请输入审批意见')).toHaveValue('正在输入意见')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await tick(page, state, true)
  expect(state.maxInFlight).toBe(1)
})


test('完成修订冲突只更新成员版本并保留意见；冲突后失权不恢复旧能力', async ({ page }) => {
  const state = await openDetail(page)
  state.detail.multiInstanceState = { mode: 'ALL', activityId: 'review', revision: 1, adjustmentAllowed: true, addAllowed: true, remainingCapacity: 4, members: [] }
  await tick(page, state, true)
  await page.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '通过任务' })
  await dialog.getByPlaceholder('请输入审批意见').fill('冲突后保留意见')
  state.detail.multiInstanceState.revision = 2
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(dialog.getByText('会签成员已发生变化，已刷新最新版本。请核对成员后再次点击确认提交。')).toBeVisible()
  await expect(dialog.getByPlaceholder('请输入审批意见')).toHaveValue('冲突后保留意见')
  expect(state.completions[0].expectedRevision).toBe(1)
  const fullBefore = state.full
  state.miFailure = 403
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(page.locator('.workflow-detail__actions')).toHaveCount(0)
  await page.clock.runFor(500)
  await expect(dialog).toBeHidden()
  expect(state.completions[1].expectedRevision).toBe(2)
  await page.clock.runFor(30000)
  expect(state.full).toBe(fullBefore)
})
