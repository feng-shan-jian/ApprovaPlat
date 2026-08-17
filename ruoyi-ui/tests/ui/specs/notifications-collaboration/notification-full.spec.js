import { test, expect } from '@playwright/test'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'

/**
 * 生成通知场景使用的唯一测试资产前缀。
 * @param {import('@playwright/test').TestInfo} testInfo 当前 Playwright 用例信息。
 * @param {string} domain 当前通知业务域缩写。
 * @returns {string} 可用于流程 key、名称和通知模板的 ASCII 前缀。
 */
function notificationPrefix(testInfo, domain) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-16)
  return `E2E_UI_${runId}_${domain}_${testInfo.workerIndex}_${Date.now().toString(36)}`
}

/**
 * 转义只读核验 SQL 中的字符串字面量。
 * @param {string} value 待写入单引号字面量的业务主键或测试标识。
 * @returns {string} 可安全嵌入单条只读 SQL 的字符串。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 记录可发起列表并发请求的完成顺序和脱敏结果，用于区分页面竞态与后端查询缺陷。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @returns {{responses:Array<object>,dispose:()=>void}} 响应证据集合和监听清理函数。
 */
function observeStartableResponses(page) {
  const responses = []
  const listener = async response => {
    if (!matchesEndpoint(response, '/workflow/process/list', 'GET')) return
    const requestUrl = new URL(response.url())
    const payload = await response.json().catch(() => ({}))
    responses.push({
      completedAt: new Date().toISOString(),
      processName: requestUrl.searchParams.get('processName') || '',
      processKey: requestUrl.searchParams.get('processKey') || '',
      code: payload?.code,
      total: payload?.total,
      rowNames: Array.isArray(payload?.rows) ? payload.rows.map(row => row.processName) : []
    })
  }
  page.on('response', listener)
  return { responses, dispose: () => page.off('response', listener) }
}

/**
 * 返回当前 Element Plus 表格中包含唯一测试标识的业务行。
 * @param {import('@playwright/test').Page} page 当前通知管理页面。
 * @param {string} value 流程 key 或唯一标题。
 * @returns {import('@playwright/test').Locator} 唯一业务行定位器。
 */
function tableRow(page, value) {
  return page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: value })
}

/**
 * 在 Element Plus 对话框的单值下拉框中选择指定选项。
 * @param {import('@playwright/test').Page} page 当前浏览器页面。
 * @param {import('@playwright/test').Locator} dialog 当前通知策略对话框。
 * @param {string} label 表单项标签。
 * @param {string} option 目标选项文案。
 * @returns {Promise<void>} 选项完成真实点击并回填后结束。
 */
async function selectDialogOption(page, dialog, label, option) {
  const matchingLabels = dialog.locator('.el-form-item__label').filter({ hasText: label })
  await expect(matchingLabels, `${label} 标签必须唯一`).toHaveCount(1)
  await matchingLabels.first().locator('..').locator('.el-select').click()
  await page.getByRole('option', { name: option, exact: true }).click()
}

/**
 * 通过通知管理页面创建指定流程、事件和站内通道策略。
 * @param {import('@playwright/test').Page} page 工作流管理员页面。
 * @param {{processKey:string,eventType:string,title:string,content:string}} policy 策略作用域、事件和模板。
 * @returns {Promise<void>} 策略保存并在正式列表唯一回显后结束。
 */
async function createInboxPolicy(page, policy) {
  await page.goto('/workflow/notification')
  await page.getByRole('button', { name: '新增策略', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增通知策略' })
  await selectDialogOption(page, dialog, '作用域', '指定流程')
  await selectDialogOption(page, dialog, '事件', policy.eventType)
  await dialog.getByLabel('流程 key').fill(policy.processKey)
  await expect(dialog.locator('.el-form-item').filter({ hasText: '接收人' })).toContainText('当前待办接收人')
  const inboxChannel = dialog.getByRole('checkbox', { name: '站内', exact: true })
  const inboxChannelControl = dialog.locator('.el-checkbox').filter({ hasText: '站内' })
  await expect(inboxChannel).toBeChecked()
  // 显式关闭并重新启用站内通道，证明最终配置来自真实用户操作而非只依赖表单默认值。
  await inboxChannelControl.click()
  await expect(inboxChannel).not.toBeChecked()
  await inboxChannelControl.click()
  await expect(inboxChannel).toBeChecked()
  await dialog.getByLabel('标题模板').fill(policy.title)
  await dialog.getByLabel('正文模板').fill(policy.content)
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByText('通知策略已保存', { exact: true })).toBeVisible()
  const row = tableRow(page, policy.processKey)
  await expect(row, `${policy.eventType} 流程策略必须唯一`).toHaveCount(1)
  await expect(row).toContainText(policy.eventType)
  await expect(row).toContainText('INBOX')
  await expect(row).toContainText('启用')
}

/**
 * 通过通知管理页面停用测试流程的通知策略，保留不可变审计但停止后续投递影响。
 * @param {import('@playwright/test').Page} page 工作流管理员页面。
 * @param {string} processKey 测试流程定义 key。
 * @returns {Promise<void>} 策略状态在列表回显为停用后结束。
 */
async function disableInboxPolicy(page, processKey) {
  await page.goto('/workflow/notification')
  const row = tableRow(page, processKey)
  await expect(row).toHaveCount(1)
  await row.getByRole('button', { name: '编辑策略' }).click()
  const dialog = page.getByRole('dialog', { name: '编辑通知策略' })
  const statusSwitch = dialog.getByRole('switch')
  const statusSwitchControl = dialog.locator('.el-switch')
  await expect(statusSwitch).toBeChecked()
  await statusSwitchControl.click()
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByText('通知策略已保存', { exact: true })).toBeVisible()
  await expect(row).toContainText('停用')
}

/**
 * 通过真实表单、模型和 BPMN 设计器创建固定审批人的单级流程。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,taskName:string}} assets 测试资产及节点名称。
 * @returns {Promise<void>} 模型部署成功并可由发起人从工作台启动后结束。
 */
async function createFixedApproverProcess(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({
    name: assets.categoryName,
    code: assets.categoryCode,
    remark: assets.prefix
  })
  await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
  await configuration.createModel({
    name: assets.modelName,
    key: assets.modelKey,
    categoryName: assets.categoryName,
    formName: assets.formName,
    description: `${assets.prefix} 通知真实链路`
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
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 等待通知 outbox 累计投递状态与收件箱形成一致的已处理事实。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} eventType 通知事件类型。
 * @returns {Promise<string[][]>} 唯一 outbox 与收件箱联合投影。
 */
async function waitForProcessedInbox(processInstanceId, eventType) {
  const instanceId = sqlLiteral(processInstanceId)
  const event = sqlLiteral(eventType)
  await expect.poll(() => queryReadOnly(
    `SELECT COUNT(*) FROM wf_notification_outbox o JOIN wf_notification_inbox i ON i.outbox_id=o.outbox_id WHERE o.process_instance_id='${instanceId}' AND o.event_type='${event}' AND o.channel='INBOX' AND o.status='PROCESSED'`
  )[0]?.[0] || '0', {
    message: `${eventType} 必须完成真实站内投递`,
    timeout: 30_000,
    intervals: [250, 500, 1000]
  }).toBe('1')
  return queryReadOnly(
    `SELECT o.outbox_id,o.recipient_user_id,o.status,i.notification_id,i.read_status,o.attempt_count,o.total_attempt_count,COALESCE(o.last_error_code,''),COALESCE(o.last_error_summary,''),o.processed_time IS NOT NULL,o.source_id,o.content FROM wf_notification_outbox o JOIN wf_notification_inbox i ON i.outbox_id=o.outbox_id WHERE o.process_instance_id='${instanceId}' AND o.event_type='${event}' AND o.channel='INBOX'`
  )
}

/**
 * 从审批人消息中心打开唯一通知，触发真实已读写入并进入受对象授权的流程详情。
 * @param {import('@playwright/test').Page} page 审批人页面。
 * @param {string} title 唯一通知标题。
 * @returns {Promise<void>} 已读接口成功且详情路由打开后结束。
 */
async function openNotificationFromHeader(page, title) {
  const initialResponsePromise = page.waitForResponse(response => matchesEndpoint(response, '/workflow/process/todoList', 'GET'))
  await page.goto('/office/todo')
  await expectAjaxSuccess(await initialResponsePromise, '/workflow/process/todoList 初始化查询')
  await expect(page.locator('.workflow-process-list .el-loading-mask')).toHaveCount(0)
  const noticeTrigger = page.locator('.notice-trigger')
  await expect(noticeTrigger).toBeVisible()
  await noticeTrigger.hover()
  const popover = page.locator('.notice-popover')
  await expect(popover).toBeVisible()
  const notice = popover.locator('.notice-item--workflow').filter({ hasText: title })
  await expect(notice, `消息中心必须显示 ${title}`).toHaveCount(1)
  const readResponse = page.waitForResponse(response =>
    response.request().method() === 'POST'
      && /\/workflow\/notification\/inbox\/\d+\/read$/u.test(new URL(response.url()).pathname)
  )
  await notice.click()
  await expectAjaxSuccess(await readResponse, '/workflow/notification/inbox/{id}/read')
  await expect(page).toHaveURL(/\/workflow\/process-detail\//u)
}

test('@full [UI-NOTIFY-003] 任务到达策略通过真实流程形成站内通知并由审批人打开已读', async ({ browser }, testInfo) => {
  const prefix = notificationPrefix(testInfo, 'notify_arrived')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_任务到达通知`,
    modelKey: `${prefix}_model`,
    taskName: `${prefix}_审批`,
    notificationTitle: `${prefix}_新待办`,
    processInstanceId: ''
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)),
    contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let admin
  let starter
  let approver
  let startableEvidence
  let failed = true
  try {
    await createFixedApproverProcess(designer.page, assets)
    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    await createInboxPolicy(admin.page, {
      processKey: assets.modelKey,
      eventType: 'TASK_ARRIVED',
      title: assets.notificationTitle,
      content: `${prefix} {{processName}} {{taskName}}`
    })

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    startableEvidence = observeStartableResponses(starter.page)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${prefix}_申请内容`)
    const deliveryRows = await waitForProcessedInbox(assets.processInstanceId, 'TASK_ARRIVED')
    expect(deliveryRows, '任务到达通知必须只有一条站内投递').toHaveLength(1)
    expect(deliveryRows[0][2], 'outbox 必须提交为已处理').toBe('PROCESSED')
    expect(deliveryRows[0][4], '消息中心打开前必须为未读').toBe('UNREAD')
    expect(deliveryRows[0].slice(5, 10), '站内投递必须累计一次成功尝试并清空错误').toEqual([
      '1', '1', '', '', '1'
    ])

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await openNotificationFromHeader(approver.page, assets.notificationTitle)
    const notificationId = deliveryRows[0][3]
    const readRows = queryReadOnly(
      `SELECT read_status,read_time IS NOT NULL FROM wf_notification_inbox WHERE notification_id=${Number(notificationId)}`
    )
    expect(readRows, '消息已读状态必须唯一落库').toEqual([['READ', '1']])
    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const approvalDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await approvalDialog.getByLabel('办理意见').fill(`${prefix}_通知已处理`)
    await approvalDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expect(approver.page.getByText('通过任务成功', { exact: true })).toBeVisible()
    await disableInboxPolicy(admin.page, assets.modelKey)
    failed = false
  } finally {
    startableEvidence?.dispose()
    await Promise.allSettled([
      approver?.close(failed),
      starter?.close(failed),
      admin?.close(failed),
      designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)),
      contentType: 'application/json'
    })
    await testInfo.attach('startable-list-responses.json', {
      body: Buffer.from(JSON.stringify(startableEvidence?.responses || [], null, 2)),
      contentType: 'application/json'
    })
  }
})

test.skip('@full [UI-NOTIFY-005] 真实短信通道成功投递', {
  annotation: {
    type: 'not-executed',
    description: '当前环境未配置真实短信通道，按测试规格不得使用 mock 冒充成功'
  }
}, async () => {})

test('@full [UI-NOTIFY-004] 发起人真实催办形成通知且重复催办被无副作用拒绝', async ({ browser }, testInfo) => {
  const prefix = notificationPrefix(testInfo, 'notify_urge')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_人工催办`,
    modelKey: `${prefix}_model`,
    taskName: `${prefix}_待审批`,
    notificationTitle: `${prefix}_催办`,
    urgeReason: `${prefix}_请尽快处理`,
    processInstanceId: ''
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)),
    contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let admin
  let starter
  let approver
  let failed = true
  try {
    await createFixedApproverProcess(designer.page, assets)
    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    await createInboxPolicy(admin.page, {
      processKey: assets.modelKey,
      eventType: 'MANUAL_URGE',
      title: assets.notificationTitle,
      content: `${prefix} {{processName}} {{taskName}}`
    })

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${prefix}_申请内容`)
    await starter.page.getByRole('button', { name: '催办', exact: true }).click()
    const urgeDialog = starter.page.getByRole('dialog', { name: '催办当前审批' })
    await urgeDialog.getByLabel('催办原因').fill(assets.urgeReason)
    const urgeResponsePromise = starter.page.waitForResponse(response =>
      matchesEndpoint(response, '/workflow/notification/urge', 'POST')
    )
    await urgeDialog.getByRole('button', { name: '发送催办', exact: true }).click()
    const urgePayload = await expectAjaxSuccess(await urgeResponsePromise, '/workflow/notification/urge')
    const urgeEventKey = String(urgePayload.data?.urgeEventKey || '')
    expect(urgeEventKey, '催办必须返回稳定业务事件键').toMatch(/^URGE:[0-9a-f-]{36}$/u)
    expect(Number(urgePayload.data?.recipientCount), '催办必须解析唯一当前办理人').toBe(1)
    expect(Number(urgePayload.data?.outboxCount), '催办必须返回真实新增 outbox 数量').toBe(1)
    await expect(starter.page.getByText('催办已发送给 1 名当前办理人', { exact: true })).toBeVisible()

    const deliveryRows = await waitForProcessedInbox(assets.processInstanceId, 'MANUAL_URGE')
    expect(deliveryRows, '人工催办必须只有一条站内投递').toHaveLength(1)
    expect(deliveryRows[0][10].startsWith(`${urgeEventKey}:`), '催办来源键必须以事件键开头').toBe(true)
    expect(deliveryRows[0][11], '催办 outbox 必须保存用户填写的业务原因摘要').toContain(assets.urgeReason)
    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    const escapedEventKey = sqlLiteral(urgeEventKey)
    const escapedReason = sqlLiteral(assets.urgeReason)
    const firstSnapshot = queryReadOnly(
      `SELECT COUNT(*),SUM(source_id LIKE '${escapedEventKey}:%'),SUM(content LIKE '%${escapedReason}%'),(SELECT COUNT(*) FROM wf_notification_inbox i JOIN wf_notification_outbox oi ON oi.outbox_id=i.outbox_id WHERE oi.process_instance_id='${escapedInstanceId}' AND oi.event_type='MANUAL_URGE' AND oi.source_id LIKE '${escapedEventKey}:%') FROM wf_notification_outbox WHERE process_instance_id='${escapedInstanceId}' AND event_type='MANUAL_URGE'`
    )
    expect(firstSnapshot, '首次催办必须形成带稳定来源和原因摘要的 outbox/inbox').toEqual([
      ['1', '1', '1', '1']
    ])

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await openNotificationFromHeader(approver.page, assets.notificationTitle)

    await starter.page.getByRole('button', { name: '催办', exact: true }).click()
    const repeatedDialog = starter.page.getByRole('dialog', { name: '催办当前审批' })
    await repeatedDialog.getByLabel('催办原因').fill(`${assets.urgeReason}_重复`)
    const rejectedResponsePromise = starter.page.waitForResponse(response =>
      matchesEndpoint(response, '/workflow/notification/urge', 'POST')
    )
    await repeatedDialog.getByRole('button', { name: '发送催办', exact: true }).click()
    const rejectedResponse = await rejectedResponsePromise
    expect(rejectedResponse.status(), '频率拒绝仍应返回统一 AjaxResult').toBe(200)
    const rejectedPayload = await rejectedResponse.json()
    expect(rejectedPayload.code, '重复催办必须返回限流业务码').toBe(429)
    expect(rejectedPayload.subCode, '重复催办必须返回稳定 Redis 冷却子码')
      .toBe('WORKFLOW_URGE_COOLDOWN_ACTIVE')
    expect(rejectedPayload.msg, '重复催办必须给出明确冷却语义').toContain('催办冷却中')
    await expect(starter.page.getByText(/催办冷却中/u)).toBeVisible()
    const rejectedSnapshot = queryReadOnly(
      `SELECT COUNT(*),SUM(source_id LIKE '${escapedEventKey}:%'),SUM(content LIKE '%${escapedReason}%'),(SELECT COUNT(*) FROM wf_notification_inbox i JOIN wf_notification_outbox oi ON oi.outbox_id=i.outbox_id WHERE oi.process_instance_id='${escapedInstanceId}' AND oi.event_type='MANUAL_URGE' AND oi.source_id LIKE '${escapedEventKey}:%') FROM wf_notification_outbox WHERE process_instance_id='${escapedInstanceId}' AND event_type='MANUAL_URGE'`
    )
    expect(rejectedSnapshot, '频率拒绝后 outbox 和收件箱必须零新增').toEqual(firstSnapshot)

    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const approvalDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await approvalDialog.getByLabel('办理意见').fill(`${prefix}_催办后通过`)
    await approvalDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expect(approver.page.getByText('通过任务成功', { exact: true })).toBeVisible()
    await disableInboxPolicy(admin.page, assets.modelKey)
    failed = false
  } finally {
    await Promise.allSettled([
      approver?.close(failed),
      starter?.close(failed),
      admin?.close(failed),
      designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)),
      contentType: 'application/json'
    })
  }
})
