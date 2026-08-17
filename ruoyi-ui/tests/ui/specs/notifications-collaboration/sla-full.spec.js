import { test, expect } from '@playwright/test'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成 SLA 场景使用的唯一测试资产前缀。
 * @param {import('@playwright/test').TestInfo} testInfo 当前 Playwright 用例信息。
 * @param {string} domain 当前 SLA 场景缩写。
 * @returns {string} 可用于日历、模型和流程名称的唯一 ASCII 前缀。
 */
function slaPrefix(testInfo, domain) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-16)
  return `E2E_UI_${runId}_${domain}_${testInfo.workerIndex}_${Date.now().toString(36)}`
}

/**
 * 转义只读核验 SQL 中的字符串字面量。
 * @param {string} value 流程实例、日历或模型的稳定标识。
 * @returns {string} 可安全嵌入单条只读 SQL 的字符串。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 创建一个 SLA 用例所需的正式资产名称集合。
 * @param {import('@playwright/test').TestInfo} testInfo 当前 Playwright 用例信息。
 * @param {string} domain 当前 SLA 场景缩写。
 * @returns {{prefix:string,calendarKey:string,calendarName:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,taskName:string,processInstanceId:string,escalationUserId:string}} 可追踪测试资产。
 */
function slaAssets(testInfo, domain) {
  const prefix = slaPrefix(testInfo, domain)
  return {
    prefix,
    calendarKey: `${prefix.replace(/[^A-Za-z0-9]/gu, '_').toUpperCase()}_CAL`.slice(0, 64),
    calendarName: `${prefix}_宽时段日历`,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_SLA审批`,
    modelKey: `${prefix}_model`,
    taskName: `${prefix}_审批`,
    processInstanceId: '',
    escalationUserId: ''
  }
}

/**
 * 通过 SLA 运维页面新增周一至周日宽时段工作的正式业务日历。
 * @param {import('@playwright/test').Page} page 工作流管理员页面。
 * @param {{calendarKey:string,calendarName:string,prefix:string}} assets 测试日历编码、名称和说明。
 * @returns {Promise<void>} 日历保存且数据库完整回显后结束。
 */
async function createWideWindowCalendar(page, assets) {
  await page.goto('/workflow/extensions/bpmnEvent')
  await page.getByRole('tab', { name: '业务日历' }).click()
  await page.getByRole('button', { name: '新增日历', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增业务日历' })
  await dialog.getByLabel('稳定编码').fill(assets.calendarKey)
  await dialog.getByLabel('日历名称').fill(assets.calendarName)
  for (const dayName of ['周六', '周日']) {
    await dialog.locator('.el-checkbox-button').filter({ hasText: dayName }).click()
  }
  const timeSelects = dialog.locator('.calendar-time-range .el-select')
  await expect(timeSelects, '业务日历必须提供开始和结束时间选择器').toHaveCount(2)
  for (const [index, value] of [['0', '00:00'], ['1', '23:30']]) {
    const select = timeSelects.nth(Number(index))
    const input = select.getByRole('combobox')
    await select.locator('.el-select__wrapper').click()
    await expect(input, `${value} 时间选择器必须真实展开`).toHaveAttribute('aria-expanded', 'true')
    const listboxId = await input.getAttribute('aria-controls')
    expect(listboxId, `${value} 时间选择器必须关联唯一选项列表`).toBeTruthy()
    const listbox = page.locator(`[id="${listboxId}"]`)
    const option = listbox.getByRole('option', { name: value, exact: true })
    await expect(option, `${value} 必须是用户可见时间选项`).toBeVisible()
    await option.click()
    await expect(input, `${value} 选择完成后弹层必须关闭`).toHaveAttribute('aria-expanded', 'false')
    await expect(select.locator('.el-select__selected-item.el-select__placeholder'),
      `${value} 必须在时间选择器回显`).toHaveText(value)
    await expect(listbox, `${value} 关联选项列表必须完成隐藏`).toBeHidden()
  }
  await dialog.getByLabel('说明').fill(`${assets.prefix} SLA 宽时段运行日历`)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByText('业务日历已保存', { exact: true })).toBeVisible()
  const row = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: assets.calendarKey })
  await expect(row, '新增业务日历必须在正式列表唯一回显').toHaveCount(1)
  await expect(row).toContainText('周一、周二、周三、周四、周五、周六、周日')
  await expect(row).toContainText('00:00 - 23:30')
  await expect(row).toContainText('已启用')
}

/**
 * 通过分类、表单、模型和 BPMN 属性面板创建固定审批人的 SLA 流程。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {ReturnType<typeof slaAssets>} assets 当前 SLA 测试资产。
 * @returns {Promise<void>} SLA 作者配置通过服务端校验、保存并部署后结束。
 */
async function createSlaProcess(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
  await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
  await configuration.createModel({
    name: assets.modelName,
    key: assets.modelKey,
    categoryName: assets.categoryName,
    formName: assets.formName,
    description: `${assets.prefix} SLA 真实 UI 链路`
  })
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.configureTaskParticipantRuleForElement({
    elementId: 'review',
    taskName: assets.taskName,
    ruleLabel: '固定用户',
    targetFieldLabel: '固定办理人',
    targetName: 'UI流程审批人'
  })
  await designer.configureTaskSla({
    elementId: 'review',
    calendarName: assets.calendarName,
    calendarKey: assets.calendarKey,
    reminderMinutes: 1,
    maxReminders: 2,
    reminderRepeatMinutes: 1,
    escalationMinutes: 3,
    escalationUsername: 'e2e_ui_wf_approver',
    escalationUserName: 'UI流程审批人'
  })
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)

  const key = sqlLiteral(assets.modelKey)
  const snapshots = queryReadOnly(
    `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_RE_DEPLOYMENT a ON a.PARENT_DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND a.CATEGORY_='APPROVAPLAT_WORKFLOW_ARTIFACTS' JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=a.ID_ AND b.NAME_='approvaplat/task-sla-v1.json' WHERE p.KEY_='${key}' ORDER BY p.VERSION_ DESC LIMIT 1`
  )
  expect(snapshots, 'SLA 部署必须写入唯一正式业务资源').toHaveLength(1)
  const snapshotRows = JSON.parse(snapshots[0][0])
  expect(snapshotRows, 'SLA 正式业务资源必须只冻结目标审批节点').toHaveLength(1)
  // 隔离库每次从最终基线重建，账号主键必须按用户名实时解析，禁止依赖旧开发库自增值。
  const escalationUsers = queryReadOnly(
    "SELECT user_id FROM sys_user WHERE user_name='e2e_ui_wf_approver' AND status='0' AND del_flag='0'"
  )
  expect(escalationUsers, 'SLA 升级办理账号必须唯一且有效').toHaveLength(1)
  // 记录本次隔离库实际主键，后续 Flowable 运行任务对账必须使用同一正式身份。
  assets.escalationUserId = escalationUsers[0][0]
  expect(snapshotRows[0]).toMatchObject({
    calendarKey: assets.calendarKey,
    processKey: assets.modelKey,
    taskDefinitionKey: 'review',
    reminderMinutes: 1,
    reminderRepeatMinutes: 1,
    maxReminders: 2,
    escalationMinutes: 3,
    escalationAssignee: assets.escalationUserId,
    workStart: '00:00',
    workEnd: '23:30',
    workingDays: '1,2,3,4,5,6,7'
  })
}

/**
 * 等待指定流程实例形成唯一 SLA 执行。
 * @param {string} processInstanceId 流程实例主键。
 * @returns {Promise<string[]>} SLA 执行的关键字段投影。
 */
async function waitForSlaExecution(processInstanceId) {
  const instanceId = sqlLiteral(processInstanceId)
  await expect.poll(() => queryReadOnly(
    `SELECT COUNT(*) FROM wf_task_sla_execution WHERE process_instance_id='${instanceId}'`
  )[0]?.[0] || '0', {
    message: '流程启动后必须同步建立唯一 SLA 执行',
    timeout: 20_000,
    intervals: [250, 500, 1000]
  }).toBe('1')
  return queryReadOnly(
    `SELECT sla_execution_id,task_id,status,reminders_sent,COALESCE(paused_at,''),paused_millis,revision,DATE_FORMAT(reminder_due_at,'%Y-%m-%d %H:%i:%s.%f'),DATE_FORMAT(escalation_due_at,'%Y-%m-%d %H:%i:%s.%f') FROM wf_task_sla_execution WHERE process_instance_id='${instanceId}'`
  )[0]
}

/**
 * 等待 SLA 审计形成指定动作序列。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string[]} expectedActions 按审计主键顺序排列的预期动作。
 * @param {number} timeout 最大等待毫秒数。
 * @returns {Promise<string[][]>} 最终审计动作、序号和操作人投影。
 */
async function waitForSlaAuditActions(processInstanceId, expectedActions, timeout = 90_000) {
  const instanceId = sqlLiteral(processInstanceId)
  await expect.poll(() => queryReadOnly(
    `SELECT a.action_type FROM wf_task_sla_audit a JOIN wf_task_sla_execution e ON e.sla_execution_id=a.sla_execution_id WHERE e.process_instance_id='${instanceId}' ORDER BY a.audit_id`
  ).map(row => row[0]), {
    message: `SLA 审计必须按顺序形成 ${expectedActions.join(' -> ')}`,
    timeout,
    intervals: [1000, 2000, 3000]
  }).toEqual(expectedActions)
  return queryReadOnly(
    `SELECT a.action_type,a.action_ordinal,COALESCE(a.actor_user_id,'') FROM wf_task_sla_audit a JOIN wf_task_sla_execution e ON e.sla_execution_id=a.sla_execution_id WHERE e.process_instance_id='${instanceId}' ORDER BY a.audit_id`
  )
}

/**
 * 等待统一通知模型为指定 SLA 动作形成已处理 outbox 与收件箱。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} actionType REMINDER 或 ESCALATE。
 * @param {number} expectedCount 预期唯一通知数量。
 * @returns {Promise<string[][]>} 通知动作、处理状态、已读状态、累计尝试与终态投影。
 */
async function waitForSlaNotifications(processInstanceId, actionType, expectedCount) {
  const instanceId = sqlLiteral(processInstanceId)
  const action = sqlLiteral(actionType)
  await expect.poll(() => queryReadOnly(
    `SELECT COUNT(*) FROM wf_notification_outbox o JOIN wf_notification_inbox i ON i.outbox_id=o.outbox_id WHERE o.source_type='SLA' AND o.process_instance_id='${instanceId}' AND o.event_type='${action}' AND o.status='PROCESSED'`
  )[0]?.[0] || '0', {
    message: `${actionType} 必须完成 ${expectedCount} 条真实站内通知投递`,
    timeout: 60_000,
    intervals: [500, 1000, 2000]
  }).toBe(String(expectedCount))
  return queryReadOnly(
    `SELECT o.event_type,o.status,i.read_status,o.attempt_count,o.total_attempt_count,COALESCE(o.last_error_code,''),COALESCE(o.last_error_summary,''),o.processed_time IS NOT NULL FROM wf_notification_outbox o JOIN wf_notification_inbox i ON i.outbox_id=o.outbox_id WHERE o.source_type='SLA' AND o.process_instance_id='${instanceId}' AND o.event_type='${action}' ORDER BY o.outbox_id`
  )
}

/**
 * 在 SLA 运维页签中核对指定实例的执行、审计和通知事实。
 * @param {import('@playwright/test').Page} page 管理员或审计员可访问的 SLA 运维页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {{status:string,reminders:number,actions:string[],notificationActions?:string[]}} expected 页面应回显的最终状态。
 * @returns {Promise<void>} 三个正式页签均完成真实查询和可见核验后结束。
 */
async function expectSlaOperationsUi(page, processInstanceId, expected) {
  await page.goto('/workflow/extensions/bpmnEvent')
  await page.getByRole('tab', { name: 'SLA 执行' }).click()
  let row = page.locator('.el-tab-pane:visible .el-table__body-wrapper tbody tr').filter({ hasText: processInstanceId })
  await expect(row, 'SLA 执行页必须显示目标实例').toHaveCount(1)
  await expect(row).toContainText(expected.status)
  await expect(row).toContainText(String(expected.reminders))

  await page.getByRole('tab', { name: 'SLA 审计' }).click()
  const actionCounts = expected.actions.reduce((counts, action) => {
    counts[action] = (counts[action] || 0) + 1
    return counts
  }, {})
  for (const [action, count] of Object.entries(actionCounts)) {
    row = page.locator('.el-tab-pane:visible .el-table__body-wrapper tbody tr')
      .filter({ hasText: processInstanceId }).filter({ hasText: action })
    await expect(row, `SLA 审计页必须显示 ${count} 条 ${action}`).toHaveCount(count)
  }

  if (expected.notificationActions?.length) {
    await page.getByRole('tab', { name: 'SLA 通知' }).click()
    for (const action of expected.notificationActions) {
      const notificationRow = page.locator('.el-tab-pane:visible .el-table__body-wrapper tbody tr')
        .filter({ hasText: action })
      await expect(notificationRow.first(), `SLA 通知页必须显示 ${action}`).toBeVisible()
    }
  }
}

/**
 * 在 finally 中关闭 SLA 场景创建的全部浏览器角色会话并保留失败 trace。
 * @param {Array<{close:(failed?:boolean)=>Promise<void>}|undefined>} sessions 可能尚未创建完整的角色会话列表。
 * @param {boolean} failed 当前业务场景是否失败。
 * @returns {Promise<void>} 所有已建立会话完成真实注销和关闭后结束。
 */
async function closeSessions(sessions, failed) {
  await Promise.allSettled(sessions.filter(Boolean).map(session => session.close(failed)))
}

test('@full [UI-SLA-001] 两次提醒后升级为人工任务并保持运行数据一致', async ({ browser }, testInfo) => {
  test.setTimeout(600_000)
  const assets = slaAssets(testInfo, 'sla_escalate')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  let admin
  let designer
  let starter
  let approver
  let failed = true
  try {
    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    await createWideWindowCalendar(admin.page, assets)
    designer = await openRoleSession(browser, 'workflow_designer', testInfo)
    await createSlaProcess(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请内容`)

    const execution = await waitForSlaExecution(assets.processInstanceId)
    expect(execution[2], 'SLA 启动状态必须为 ACTIVE').toBe('ACTIVE')
    const instanceId = sqlLiteral(assets.processInstanceId)
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}'`
    )).toEqual([['3']])
    await waitForSlaAuditActions(assets.processInstanceId, ['CREATE', 'REMINDER', 'REMINDER', 'ESCALATE'], 250_000)
    const reminderNotifications = await waitForSlaNotifications(assets.processInstanceId, 'REMINDER', 2)
    const escalationNotifications = await waitForSlaNotifications(assets.processInstanceId, 'ESCALATE', 1)
    expect(reminderNotifications.every(row => row.slice(3).join('|') === '0|0|||1'),
      '每条同步提醒通知必须无 worker 尝试、无错误并进入终态').toBe(true)
    expect(escalationNotifications[0].slice(3), '同步升级通知必须无 worker 尝试、无错误并进入终态')
      .toEqual(['0', '0', '', '', '1'])
    expect(queryReadOnly(
      `SELECT status,reminders_sent,COALESCE(paused_at,'') FROM wf_task_sla_execution WHERE process_instance_id='${instanceId}'`
    )).toEqual([['ESCALATED', '2', '']])
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_,COALESCE(ASSIGNEE_,'') FROM ACT_RU_TASK WHERE PROC_INST_ID_='${instanceId}'`
    )).toEqual(expect.arrayContaining([
      [expect.stringContaining('approva_sla_review_escalation_user_task'), assets.escalationUserId]
    ]))
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}'`
    )).toEqual([['0']])
    await expectSlaOperationsUi(admin.page, assets.processInstanceId, {
      status: 'ESCALATED', reminders: 2, actions: ['CREATE', 'REMINDER', 'REMINDER', 'ESCALATE']
    })

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await new WorkflowWorkbenchPage(approver.page)
      .approveTask(assets.modelName, `${assets.taskName}超时升级处理`, `${assets.prefix}_升级任务通过`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${instanceId}'`
    )).toEqual([['1']])
    const completionActions = queryReadOnly(
      `SELECT a.action_type FROM wf_task_sla_audit a JOIN wf_task_sla_execution e ON e.sla_execution_id=a.sla_execution_id WHERE e.process_instance_id='${instanceId}' ORDER BY a.audit_id`
    ).map(row => row[0])
    const completionStatus = queryReadOnly(
      `SELECT status FROM wf_task_sla_execution WHERE process_instance_id='${instanceId}'`
    )[0]?.[0] || ''
    assets.escalationCompletion = { completionActions, completionStatus }
    expect(completionActions, '完成升级任务后 SLA 必须形成 COMPLETE 审计').toEqual([
      'CREATE', 'REMINDER', 'REMINDER', 'ESCALATE', 'COMPLETE'
    ])
    expect(completionStatus, '完成升级任务后 SLA 执行必须进入 COMPLETED').toBe('COMPLETED')
    failed = false
  } finally {
    await closeSessions([approver, starter, designer, admin], failed)
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-SLA-002] 挂起冻结时钟且恢复后平移并继续提醒', async ({ browser }, testInfo) => {
  test.setTimeout(600_000)
  const assets = slaAssets(testInfo, 'sla_pause')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  let admin
  let designer
  let starter
  let approver
  let failed = true
  try {
    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    await createWideWindowCalendar(admin.page, assets)
    designer = await openRoleSession(browser, 'workflow_designer', testInfo)
    await createSlaProcess(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请内容`)
    const before = await waitForSlaExecution(assets.processInstanceId)
    const instanceId = sqlLiteral(assets.processInstanceId)
    // Flowable 在恢复实例时会重建 timer 数据行，因此使用 BPMN 元素标识追踪同一业务 timer。
    const timerBefore = queryReadOnly(
      `SELECT ELEMENT_ID_,DATE_FORMAT(DUEDATE_,'%Y-%m-%d %H:%i:%s.%f') FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}' ORDER BY ELEMENT_ID_`
    )
    expect(timerBefore, '挂起前必须存在两个提醒和一个升级 timer').toHaveLength(3)

    const adminWorkbench = new WorkflowWorkbenchPage(admin.page)
    await adminWorkbench.toggleManagedProcessState(assets.modelName, '已挂起')
    const paused = await waitForSlaExecution(assets.processInstanceId)
    expect(paused[2], '挂起期间 SLA 业务状态仍为 ACTIVE').toBe('ACTIVE')
    expect(paused[4], '挂起必须记录 paused_at').not.toBe('')
    await waitForSlaAuditActions(assets.processInstanceId, ['CREATE', 'PAUSE'])
    await new Promise(resolve => setTimeout(resolve, 75_000))
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM wf_task_sla_audit a JOIN wf_task_sla_execution e ON e.sla_execution_id=a.sla_execution_id WHERE e.process_instance_id='${instanceId}' AND a.action_type IN ('REMINDER','ESCALATE')`
    )).toEqual([['0']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM wf_notification_outbox WHERE source_type='SLA' AND process_instance_id='${instanceId}'`
    )).toEqual([['0']])

    await adminWorkbench.toggleManagedProcessState(assets.modelName, '运行中')
    await waitForSlaAuditActions(assets.processInstanceId, ['CREATE', 'PAUSE', 'RESUME'])
    const resumed = await waitForSlaExecution(assets.processInstanceId)
    expect(resumed[4], '恢复后 paused_at 必须清空').toBe('')
    expect(Number(resumed[5]), '恢复后累计暂停毫秒必须覆盖真实等待时长').toBeGreaterThanOrEqual(70_000)
    const dueShift = new Date(`${resumed[7].replace(' ', 'T')}Z`).getTime()
      - new Date(`${before[7].replace(' ', 'T')}Z`).getTime()
    expect(dueShift, 'SLA 首次提醒到期时间必须按暂停时长平移').toBeGreaterThanOrEqual(70_000)
    const timerAfter = queryReadOnly(
      `SELECT ELEMENT_ID_,DATE_FORMAT(DUEDATE_,'%Y-%m-%d %H:%i:%s.%f') FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}' ORDER BY ELEMENT_ID_`
    )
    expect(timerAfter, '恢复后必须重新排定两个提醒和一个升级 timer').toHaveLength(3)
    expect(timerAfter.map(row => row[0]), '恢复后必须保留三个 BPMN timer 元素')
      .toEqual(timerBefore.map(row => row[0]))
    // 逐个元素比较到期时间，防止只验证最早作业而遗漏重复提醒或升级作业。
    const timerDueBefore = new Map(timerBefore.map(row => [row[0], row[1]]))
    for (const [elementId, dueDate] of timerAfter) {
      const originalDueDate = timerDueBefore.get(elementId)
      expect(originalDueDate, `恢复前必须存在 ${elementId} timer`).toBeTruthy()
      const timerShift = new Date(`${dueDate.replace(' ', 'T')}Z`).getTime()
        - new Date(`${originalDueDate.replace(' ', 'T')}Z`).getTime()
      expect(timerShift, `${elementId} timer 到期时间必须与 SLA 一并平移`)
        .toBeGreaterThanOrEqual(70_000)
    }

    await waitForSlaAuditActions(assets.processInstanceId, ['CREATE', 'PAUSE', 'RESUME', 'REMINDER'], 100_000)
    await waitForSlaNotifications(assets.processInstanceId, 'REMINDER', 1)
    await expectSlaOperationsUi(admin.page, assets.processInstanceId, {
      status: 'ACTIVE', reminders: 1, actions: ['CREATE', 'PAUSE', 'RESUME', 'REMINDER']
    })
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await new WorkflowWorkbenchPage(approver.page)
      .approveProcess(assets.modelName, `${assets.prefix}_恢复后及时通过`)
    await waitForSlaAuditActions(assets.processInstanceId, ['CREATE', 'PAUSE', 'RESUME', 'REMINDER', 'COMPLETE'])
    expect(queryReadOnly(
      `SELECT status,reminders_sent,COALESCE(paused_at,'') FROM wf_task_sla_execution WHERE process_instance_id='${instanceId}'`
    )).toEqual([['COMPLETED', '1', '']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}'`
    )).toEqual([['0']])
    const notificationCount = Number(queryReadOnly(
      `SELECT COUNT(*) FROM wf_notification_outbox WHERE source_type='SLA' AND process_instance_id='${instanceId}'`
    )[0][0])
    await new Promise(resolve => setTimeout(resolve, 70_000))
    expect(Number(queryReadOnly(
      `SELECT COUNT(*) FROM wf_notification_outbox WHERE source_type='SLA' AND process_instance_id='${instanceId}'`
    )[0][0]), '任务完成后不得产生后续提醒或升级通知').toBe(notificationCount)
    failed = false
  } finally {
    await closeSessions([approver, starter, designer, admin], failed)
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
