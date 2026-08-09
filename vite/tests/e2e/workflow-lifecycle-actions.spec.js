import { test, expect } from './fixtures/workflow.js'
import { randomUUID } from 'node:crypto'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'
import {
  buildCandidateLifecycleBpmn,
  buildSequentialLifecycleBpmn,
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createAndDeployWorkflowModel,
  createWorkflowCategory,
  createWorkflowForm,
  expectRejectedWorkflowActionWithoutSideEffects,
  expectWorkflowAudit,
  findAssignedWorkflowTask,
  findClaimableWorkflowTask,
  findCompletedWorkflowTask,
  findStartableWorkflowDefinition,
  findWorkflowUserOption,
  getWorkflowDetail,
  openWorkflowRoleSession,
  startWorkflowThroughUi,
  workflowAccounts
} from './support/workflow-fixture.js'

/**
 * 打开对象授权后的任务详情页，并确认指定真实任务允许当前角色进入。
 * @param {import('@playwright/test').Page} page 当前办理人页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} taskId 活动任务主键。
 * @returns {Promise<void>} 详情完成加载且流程实例摘要可见后结束。
 */
async function openTaskDetail(page, processInstanceId, taskId) {
  await page.goto(`/workflow/process-detail/${encodeURIComponent(processInstanceId)}?taskId=${encodeURIComponent(taskId)}`)
  await expect(page.getByText(processInstanceId, { exact: true }).first()).toBeVisible()
}

/**
 * 在详情时间线核对后端用户可见审批意见，并确认内部审计 JSON 未进入可见文本。
 * @param {import('@playwright/test').Page} page 已打开对应流程详情的参与者页面。
 * @param {string} opinion 业务动作提交的正式审批意见。
 * @returns {Promise<void>} 意见可见且内部 actor/revision 字段均不可见后结束。
 */
async function expectTimelineOpinion(page, opinion) {
  await page.getByRole('tab', { name: /流转记录/ }).click()
  const timeline = page.locator('.workflow-detail__timeline')
  await expect(timeline.getByText(opinion, { exact: true }), '时间线必须显示后端投影的用户审批意见').toBeVisible()
  await expect(timeline.locator('.workflow-detail__comment-message').filter({
    hasText: /"(?:actorUserId|beforeRevision|afterRevision)"/
  }), '时间线不得显示内部审计 JSON').toHaveCount(0)
}

/**
 * 在详情页提交一个只需填写意见的任务动作，并核对真实后端响应。
 * @param {import('@playwright/test').Page} page 当前任务办理人页面。
 * @param {{buttonName: string, dialogName: string, commentPlaceholder: string, comment: string, endpoint: string, nextUserAssignmentPolicy?: 'OPTIONAL'|'DISABLED'}} input 按钮、弹窗、意见、正式接口和可选下一办理人策略配置。
 * @returns {Promise<void>} 后端成功且动作弹窗关闭后结束。
 */
async function submitCommentTaskAction(page, input) {
  await page.getByRole('button', { name: input.buttonName, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: input.dialogName })
  await expect(dialog).toBeVisible()
  if (input.nextUserAssignmentPolicy === 'OPTIONAL') {
    // OPTIONAL 必须显示可选字段；页面不能把普通串行后继误判为必填动态会签。
    const nextUserField = dialog.locator('.el-form-item').filter({ hasText: '下一办理人' })
    await expect(nextUserField, 'OPTIONAL 策略必须显示唯一下一办理人字段').toHaveCount(1)
    await expect(nextUserField).not.toHaveClass(/is-required/)
    // Element Plus 2.13 将 el-select 占位文案渲染为展示层节点，不再写入内部 input 的 placeholder。
    await expect(nextUserField.getByRole('combobox'), 'OPTIONAL 字段必须是唯一可操作选择器').toHaveCount(1)
    await expect(nextUserField.locator('.el-select__placeholder'), '可选语义必须在真实展示层可见')
      .toHaveText('选择下一办理人（可选）')
  }
  if (input.nextUserAssignmentPolicy === 'DISABLED') {
    // DISABLED 必须完全隐藏字段，避免终节点提交无效 nextUserIds。
    await expect(dialog.locator('.el-form-item').filter({
      hasText: /下一办理人|会签办理人|或签办理人/
    }), 'DISABLED 策略不得显示任何下一办理人字段').toHaveCount(0)
  }
  await dialog.getByPlaceholder(input.commentPlaceholder).fill(input.comment)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, input.endpoint, 'POST'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, input.endpoint)
  await expect(dialog).toBeHidden()
}

/**
 * 在任务动作弹窗中通过审批资格远程目录选择目标用户。
 * @param {import('@playwright/test').Page} page 当前任务办理人页面。
 * @param {import('@playwright/test').Locator} dialog 已打开的委派或转办弹窗。
 * @param {string} roleKey 目标用户职责角色键。
 * @param {{value: string, label: string}} option 服务端身份目录返回的目标用户选项。
 * @returns {Promise<void>} 远程接口成功且目标用户标签已经选中后结束。
 */
async function selectRemoteApprovalUser(page, dialog, roleKey, option) {
  const account = workflowAccounts[roleKey]
  // Element Plus 远程 select 的 placeholder 由展示层渲染，按业务字段定位真实 combobox 并点击 wrapper。
  const targetField = dialog.locator('.el-form-item').filter({ hasText: '目标用户' })
  const input = targetField.getByRole('combobox')
  await expect(input, '委派或转办弹窗必须展示唯一目标用户选择器').toHaveCount(1)
  await targetField.locator('.el-select__wrapper').click()
  const responsePromise = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname.endsWith('/workflow/identity/options')
      && response.request().method() === 'GET'
      && url.searchParams.get('capability') === 'approval'
      && url.searchParams.get('keyword') === account.username
  })
  await input.pressSequentially(account.username, { delay: 25 })
  await expectAjaxSuccess(await responsePromise, '/workflow/identity/options')
  const dropdownOption = page.locator('.el-select-dropdown:visible').getByText(option.label, { exact: true })
  await expect(dropdownOption, '远程审批资格目录必须包含目标用户').toBeVisible()
  await dropdownOption.click()
  await expect(targetField).toContainText(option.label)
}

/**
 * 在详情页通过真实远程用户目录提交委派或转办动作。
 * @param {import('@playwright/test').Page} page 当前任务办理人页面。
 * @param {'delegate'|'transfer'} action 动作类型。
 * @param {string} targetRoleKey 目标用户职责角色键。
 * @param {{value: string, label: string}} targetOption 服务端返回的目标用户选项。
 * @param {string} comment 委派或转办意见。
 * @returns {Promise<void>} 后端动作成功且当前办理人离开详情页后结束。
 */
async function submitUserTaskAction(page, action, targetRoleKey, targetOption, comment) {
  const contract = action === 'delegate'
    ? { button: '委派', dialog: '委派任务', placeholder: '请输入委派意见', endpoint: '/workflow/task/delegate' }
    : { button: '转办', dialog: '转办任务', placeholder: '请输入转办意见', endpoint: '/workflow/task/transfer' }
  await page.getByRole('button', { name: contract.button, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: contract.dialog })
  await expect(dialog).toBeVisible()
  await selectRemoteApprovalUser(page, dialog, targetRoleKey, targetOption)
  await dialog.getByPlaceholder(contract.placeholder).fill(comment)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, contract.endpoint, 'POST'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, contract.endpoint)
  await expect(dialog).toBeHidden()
}

/**
 * 在详情页直接把整条申请退回发起人修改。
 * @param {import('@playwright/test').Page} page 当前任务办理人页面。
 * @param {string} comment 退回原因。
 * @returns {Promise<void>} 退回写接口成功且弹窗关闭后结束。
 */
async function returnTaskThroughUi(page, comment) {
  await page.getByRole('button', { name: '退回', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '退回任务' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.el-form-item').filter({ hasText: '退回节点' })).toHaveCount(0)
  await dialog.getByPlaceholder('请输入退回原因').fill(comment)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/task/return', 'POST'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/workflow/task/return')
  await expect(dialog).toBeHidden()
}

/**
 * 触发 Element Plus 确认框动作并核对对应真实后端接口成功。
 * @param {import('@playwright/test').Page} page 当前角色页面。
 * @param {string} endpoint 不含 `/dev-api` 的正式后端接口。
 * @param {() => Promise<unknown>} trigger 打开确认框的页面动作。
 * @returns {Promise<void>} 用户确认、后端成功且确认框关闭后结束。
 */
async function confirmMessageBoxAction(page, endpoint, trigger) {
  await trigger()
  const messageBox = page.locator('.el-message-box')
  const confirmButton = messageBox.getByRole('button', { name: '确定', exact: true })
  await expect(confirmButton, '业务动作必须经过确认框').toBeVisible()
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, 'POST'))
  await confirmButton.click()
  await expectAjaxSuccess(await responsePromise, endpoint)
  await expect(messageBox).toBeHidden()
}

/**
 * 按流程标识查询工作台，并返回包含唯一业务主键的真实表格行。
 * @param {import('@playwright/test').Page} page 当前职责角色页面。
 * @param {string} route 工作台前端路由。
 * @param {string} endpoint 工作台正式列表接口。
 * @param {string} processKey 流程定义标识筛选值。
 * @param {string} businessKey 场景唯一业务主键。
 * @returns {Promise<import('@playwright/test').Locator>} 唯一匹配的 Element Plus 表格行。
 */
async function findWorkflowTableRow(page, route, endpoint, processKey, businessKey) {
  await page.goto(route)
  const processKeyInput = page.getByPlaceholder('请输入流程标识')
  await expect(processKeyInput).toBeVisible()
  await processKeyInput.fill(processKey)
  const responsePromise = page.waitForResponse(response => {
    if (!matchesEndpoint(response, endpoint, 'GET')) return false
    return new URL(response.url()).searchParams.get('processKey') === processKey
  })
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, endpoint)
  const rows = page.locator('.el-table__body tbody tr').filter({ hasText: businessKey })
  await expect(rows, '工作台必须仅返回本场景业务对象').toHaveCount(1)
  return rows.first()
}

/**
 * 在待签工作台通过真实确认框认领候选任务。
 * @param {import('@playwright/test').Page} page 候选审批人页面。
 * @param {string} processKey 候选流程定义标识。
 * @param {string} businessKey 场景唯一业务主键。
 * @returns {Promise<void>} 认领接口成功后结束。
 */
async function claimTaskThroughUi(page, processKey, businessKey) {
  const row = await findWorkflowTableRow(
    page, '/office/claim', '/workflow/process/claimList', processKey, businessKey)
  const claimButton = row.locator('button.el-button--success')
  await expect(claimButton, '待签行必须提供认领动作').toHaveCount(1)
  await confirmMessageBoxAction(page, '/workflow/task/claim', () => claimButton.click())
}

/**
 * 在任务详情页通过真实确认框取消本人认领。
 * @param {import('@playwright/test').Page} page 已认领审批人页面。
 * @param {string} processInstanceId 已认领任务所属流程实例主键。
 * @param {string} taskId 已认领活动任务主键。
 * @returns {Promise<void>} 取消认领接口成功后结束。
 */
async function unclaimTaskThroughUi(page, processInstanceId, taskId) {
  await openTaskDetail(page, processInstanceId, taskId)
  const unclaimButton = page.getByRole('button', { name: '取消认领', exact: true })
  await expect(unclaimButton, '详情页必须为本人真实认领任务提供取消认领动作').toHaveCount(1)
  await confirmMessageBoxAction(page, '/workflow/task/unClaim', () => unclaimButton.click())
}

/**
 * 在已办工作台填写原因并撤回本人可撤回任务。
 * @param {import('@playwright/test').Page} page 历史办理人页面。
 * @param {string} processKey 串行流程定义标识。
 * @param {string} businessKey 场景唯一业务主键。
 * @param {string} comment 撤回原因。
 * @returns {Promise<void>} 撤回接口成功且动作弹窗关闭后结束。
 */
async function revokeTaskThroughUi(page, processKey, businessKey, comment) {
  const row = await findWorkflowTableRow(
    page, '/office/finished', '/workflow/process/finishedList', processKey, businessKey)
  const revokeButton = row.locator('button.el-button--warning')
  await expect(revokeButton, '可撤回已办必须提供撤回动作').toHaveCount(1)
  await revokeButton.click()
  const dialog = page.getByRole('dialog', { name: '撤回已办任务' })
  await expect(dialog).toBeVisible()
  await dialog.locator('textarea').fill(comment)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/task/revokeProcess', 'POST'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/workflow/task/revokeProcess')
  await expect(dialog).toBeHidden()
}

/**
 * 通过真实发起页面创建并立即登记一个独立业务场景实例。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @param {{definitionId: string, deploymentId: string}} definition 可发起流程定义。
 * @param {string} formName 部署表单名称。
 * @param {string} runId 本轮测试唯一标识。
 * @param {string} scenario 场景短标识。
 * @param {{draftFixtures: Array<object>, processInstanceIds: string[]}} resources finally 清理使用的资源登记簿。
 * @returns {Promise<{processInstanceId: string, businessKey: string}>} 正式实例和唯一业务主键。
 */
async function startTrackedScenario(page, definition, formName, runId, scenario, resources) {
  const businessKey = `BUS-${runId}-${scenario}`
  const processInstanceId = await startWorkflowThroughUi(
    page,
    definition,
    formName,
    businessKey,
    `生命周期验收-${scenario}-${runId}`,
    resources
  )
  return { processInstanceId, businessKey }
}

/**
 * 核对指定任务在详情 API 中的活动节点、办理人和可选委派/认领元数据。
 * @param {import('@playwright/test').Page} page 可读取目标实例的页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} taskId 活动任务主键。
 * @param {{taskDefinitionKey: string, assignee?: string|null, owner?: string|null, delegationState?: string|null, claimedBy?: string|null, claimTimePresent?: boolean}} expected 期望持久化状态。
 * @returns {Promise<any>} 已通过断言的正式详情 data 对象。
 */
async function expectActiveTaskState(page, processInstanceId, taskId, expected) {
  const detail = await getWorkflowDetail(page, processInstanceId, taskId)
  expect(detail.processStatus).toBe('running')
  expect(detail.currentTask?.active).toBe(true)
  expect(String(detail.currentTask?.taskId || '')).toBe(String(taskId))
  expect(detail.currentTask?.taskDefinitionKey).toBe(expected.taskDefinitionKey)
  if (Object.hasOwn(expected, 'assignee')) expect(detail.currentTask?.assignee ?? null).toBe(expected.assignee)
  if (Object.hasOwn(expected, 'owner')) expect(detail.currentTask?.owner ?? null).toBe(expected.owner)
  if (Object.hasOwn(expected, 'delegationState')) {
    expect(detail.currentTask?.delegationState ?? null).toBe(expected.delegationState)
  }
  if (Object.hasOwn(expected, 'claimedBy')) expect(detail.currentTask?.claimedBy ?? null).toBe(expected.claimedBy)
  if (Object.hasOwn(expected, 'claimTimePresent')) {
    expect(Boolean(detail.currentTask?.claimTime)).toBe(expected.claimTimePresent)
  }
  return detail
}

test('普通审批全生命周期动作具备真实 UI、对象授权、状态门禁、持久化审计与零副作用', async ({ browser }, testInfo) => {
  test.setTimeout(600_000)
  const runId = `p3life_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const resources = {
    draftFixtures: [],
    processInstanceIds: [],
    deploymentIds: [],
    modelIds: [],
    formId: '',
    categoryId: ''
  }
  const sessions = []
  const pages = {}
  const evidence = []
  let primaryError = null

  try {
    const designerSession = await openWorkflowRoleSession(browser, 'workflow_designer')
    sessions.push(designerSession)
    pages.designer = designerSession.page
    const starterSession = await openWorkflowRoleSession(browser, 'workflow_starter')
    sessions.push(starterSession)
    pages.starter = starterSession.page
    const approverSession = await openWorkflowRoleSession(browser, 'workflow_approver')
    sessions.push(approverSession)
    pages.approver = approverSession.page
    const adminSession = await openWorkflowRoleSession(browser, 'workflow_admin')
    sessions.push(adminSession)
    pages.admin = adminSession.page
    const auditorSession = await openWorkflowRoleSession(browser, 'workflow_auditor')
    sessions.push(auditorSession)
    pages.auditor = auditorSession.page

    // 静态办理人、委派对象、转办对象和候选人全部来自实时审批资格目录。
    const approver = await findWorkflowUserOption(pages.designer, 'workflow_approver', true)
    const admin = await findWorkflowUserOption(pages.designer, 'workflow_admin', true)
    const starter = await findWorkflowUserOption(pages.designer, 'workflow_starter', false)
    expect(approver, '审批角色必须具备流程办理资格').not.toBeNull()
    expect(admin, '超级管理员必须遵循若依超级管理员审批权限语义').not.toBeNull()
    expect(starter, '流程发起人必须存在于有效用户目录').not.toBeNull()

    const categoryName = `P3生命周期-${runId}`
    const categoryCode = `p3life_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    const formName = `P3生命周期表单-${runId}`
    const sequentialProcessKey = `p3life_seq_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    const candidateProcessKey = `p3life_claim_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    resources.categoryId = await createWorkflowCategory(
      pages.designer, categoryName, categoryCode, resources)
    resources.formId = await createWorkflowForm(pages.designer, formName, resources)

    // 顺序流程 XML 后续用于从已部署 V1 保存 V2，验证真实版本切换和历史定义自动挂起。
    const sequentialBpmnXml = buildSequentialLifecycleBpmn({
      processKey: sequentialProcessKey,
      processName: `P3普通审批-${runId}`,
      formId: resources.formId,
      approverUserId: approver.value,
      adminUserId: admin.value
    })
    const sequentialModel = await createAndDeployWorkflowModel(pages.designer, {
      processKey: sequentialProcessKey,
      processName: `P3普通审批-${runId}`,
      categoryCode,
      formId: resources.formId,
      bpmnXml: sequentialBpmnXml,
      resourceRegistry: resources
    })
    const candidateModel = await createAndDeployWorkflowModel(pages.designer, {
      processKey: candidateProcessKey,
      processName: `P3候选审批-${runId}`,
      categoryCode,
      formId: resources.formId,
      bpmnXml: buildCandidateLifecycleBpmn({
        processKey: candidateProcessKey,
        processName: `P3候选审批-${runId}`,
        formId: resources.formId,
        candidateUserId: approver.value
      }),
      resourceRegistry: resources
    })
    const sequentialDefinition = await findStartableWorkflowDefinition(pages.starter, sequentialProcessKey)
    const candidateDefinition = await findStartableWorkflowDefinition(pages.starter, candidateProcessKey)
    expect(sequentialDefinition.deploymentId).toBe(sequentialModel.deploymentId)
    expect(candidateDefinition.deploymentId).toBe(candidateModel.deploymentId)
    // 四个职责角色均拥有只读抄送列表权限，拒绝动作前后必须逐会话核对正式接收记录。
    const copyReadablePages = [pages.starter, pages.approver, pages.auditor, pages.admin]

    // 场景一：三级串行审批全部通过，越权完成和历史任务重复完成均为零副作用。
    const normal = await startTrackedScenario(
      pages.starter, sequentialDefinition, formName, runId, 'normal', resources)
    const normalA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', normal.processInstanceId)
    const normalADetail = await getWorkflowDetail(
      pages.approver, normal.processInstanceId, normalA.taskId)
    expect(normalADetail.nextUserAssignmentPolicy, '串行首节点必须允许可选指定下一办理人').toBe('OPTIONAL')
    expect(normalADetail.nextUserSelectionRequired).toBe(false)
    expect(normalADetail.nextUserSelectionMode).toBeNull()
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, normal.processInstanceId, normalA.taskId, 403, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/complete', {
          data: { taskId: normalA.taskId, comment: '越权完成必须拒绝', variables: {}, copyUserIds: [], nextUserIds: [] },
          expectedCode
        }), copyReadablePages)
    await openTaskDetail(pages.approver, normal.processInstanceId, normalA.taskId)
    await submitCommentTaskAction(pages.approver, {
      buttonName: '通过', dialogName: '通过任务', commentPlaceholder: '请输入审批意见',
      comment: '一级审批通过', endpoint: '/workflow/task/complete',
      nextUserAssignmentPolicy: 'OPTIONAL'
    })
    await expectWorkflowAudit(pages.admin, normal.processInstanceId, {
      taskId: normalA.taskId,
      type: '1',
      action: 'COMPLETE',
      actorUserId: approver.value,
      opinion: '一级审批通过'
    })
    await expectTimelineOpinion(pages.approver, '一级审批通过')
    const normalB = await findAssignedWorkflowTask(
      pages.admin, sequentialProcessKey, 'reviewB', normal.processInstanceId)
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, normal.processInstanceId, normalB.taskId, 409, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/complete', {
          data: { taskId: normalA.taskId, comment: '一级重复完成', variables: {}, copyUserIds: [], nextUserIds: [] },
          expectedCode
        }), copyReadablePages)
    await openTaskDetail(pages.admin, normal.processInstanceId, normalB.taskId)
    await submitCommentTaskAction(pages.admin, {
      buttonName: '通过', dialogName: '通过任务', commentPlaceholder: '请输入审批意见',
      comment: '二级审批通过', endpoint: '/workflow/task/complete'
    })
    const normalC = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewC', normal.processInstanceId)
    const normalCDetail = await getWorkflowDetail(
      pages.approver, normal.processInstanceId, normalC.taskId)
    expect(normalCDetail.nextUserAssignmentPolicy, '直达结束事件的终节点必须禁用下一办理人').toBe('DISABLED')
    expect(normalCDetail.nextUserSelectionRequired).toBe(false)
    expect(normalCDetail.nextUserSelectionMode).toBeNull()
    await openTaskDetail(pages.approver, normal.processInstanceId, normalC.taskId)
    await submitCommentTaskAction(pages.approver, {
      buttonName: '通过', dialogName: '通过任务', commentPlaceholder: '请输入审批意见',
      comment: '三级审批通过', endpoint: '/workflow/task/complete',
      nextUserAssignmentPolicy: 'DISABLED'
    })
    const completedNormal = await getWorkflowDetail(pages.admin, normal.processInstanceId)
    expect(completedNormal.processStatus).toBe('completed')
    expect(completedNormal.endTime).not.toBeNull()
    await expectWorkflowAudit(pages.admin, normal.processInstanceId, {
      taskId: normalC.taskId,
      type: '1',
      action: 'COMPLETE',
      actorUserId: approver.value,
      opinion: '三级审批通过'
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, normal.processInstanceId, normalC.taskId, 409, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/complete', {
          data: { taskId: normalC.taskId, comment: '终态重复完成', variables: {}, copyUserIds: [], nextUserIds: [] },
          expectedCode
        }), copyReadablePages)
    evidence.push({ scenario: 'normal', finalStatus: completedNormal.processStatus, deniedCode: 403, staleCode: 409 })

    // 场景二：二级审批直接退回发起人，发起人修改后重新提交并从一级审批重新开始。
    const returned = await startTrackedScenario(
      pages.starter, sequentialDefinition, formName, runId, 'return', resources)
    const returnA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', returned.processInstanceId)
    await openTaskDetail(pages.approver, returned.processInstanceId, returnA.taskId)
    await submitCommentTaskAction(pages.approver, {
      buttonName: '通过', dialogName: '通过任务', commentPlaceholder: '请输入审批意见',
      comment: '进入二级后退回', endpoint: '/workflow/task/complete'
    })
    const returnB = await findAssignedWorkflowTask(
      pages.admin, sequentialProcessKey, 'reviewB', returned.processInstanceId)
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, returned.processInstanceId, returnB.taskId, 403, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/return', {
          data: { taskId: returnB.taskId, comment: '越权退回必须拒绝', copyUserIds: [] },
          expectedCode
        }), copyReadablePages)
    await openTaskDetail(pages.admin, returned.processInstanceId, returnB.taskId)
    await returnTaskThroughUi(pages.admin, '申请资料需要修改')
    const returnedDetail = await getWorkflowDetail(pages.starter, returned.processInstanceId)
    expect(returnedDetail.processStatus).toBe('returned')
    expect(returnedDetail.currentTask?.assignee).toBe(String(starter.value))
    await pages.starter.goto(`/workflow/process-detail/${encodeURIComponent(returned.processInstanceId)}?source=own`)
    await expect(pages.starter.getByText('待修改', { exact: true })).toBeVisible()
    // 只编辑当前办理表单，避免同字段名的只读历史快照干扰用户操作。
    await pages.starter.getByRole('tabpanel', { name: '办理表单' })
      .getByPlaceholder('请输入申请主题').fill(`重新提交-${runId}`)
    await pages.starter.getByRole('button', { name: '重新提交', exact: true }).click()
    const resubmitPromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/resubmit', 'POST'))
    await pages.starter.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await resubmitPromise, '/workflow/task/resubmit')
    const returnedA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', returned.processInstanceId)
    expect(String(returnedA.taskId)).not.toBe(String(returnA.taskId))
    const resubmittedDetail = await expectActiveTaskState(
      pages.approver, returned.processInstanceId, returnedA.taskId, {
      taskDefinitionKey: 'reviewA', assignee: String(approver.value)
    })
    expect(resubmittedDetail.flowViewer?.unfinishedActivityIds,
      '重新提交后的一级审批必须投影为当前节点').toContain('reviewA')
    expect(resubmittedDetail.flowViewer?.returnedActivityIds,
      '重新提交后当前一级审批不能继续保留历史退回标记').not.toContain('reviewA')
    await openTaskDetail(pages.approver, returned.processInstanceId, returnedA.taskId)
    await pages.approver.getByRole('tab', { name: '流程图', exact: true }).click()
    const resubmittedReviewA = pages.approver.locator(
      '.workflow-viewer__canvas .djs-element[data-element-id="reviewA"]')
    await expect(resubmittedReviewA, '流程图必须渲染重新提交后的当前一级审批节点').toBeVisible()
    await expect(resubmittedReviewA).toHaveClass(/workflow-current/)
    await expect(resubmittedReviewA).not.toHaveClass(/workflow-returned/)
    await expectWorkflowAudit(pages.admin, returned.processInstanceId, {
      taskId: returnB.taskId,
      type: '2',
      action: 'RETURN',
      actorUserId: admin.value,
      opinion: '申请资料需要修改',
      targetNodeKey: 'reviewA'
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, returned.processInstanceId, returnedA.taskId, 409, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/return', {
          data: { taskId: returnB.taskId, comment: '旧任务重复退回', copyUserIds: [] },
          expectedCode
        }), copyReadablePages)
    evidence.push({ scenario: 'return-resubmit', finalStatus: 'running', activeNode: 'reviewA', deniedCode: 403, staleCode: 409 })

    // 场景三：一级办理人从页面驳回整实例，持久化 rejected 终态并拒绝越权及终态重复驳回。
    const rejected = await startTrackedScenario(
      pages.starter, sequentialDefinition, formName, runId, 'reject', resources)
    const rejectA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', rejected.processInstanceId)
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, rejected.processInstanceId, rejectA.taskId, 403, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/reject', {
          data: { taskId: rejectA.taskId, comment: '越权驳回必须拒绝', copyUserIds: [] }, expectedCode
        }), copyReadablePages)
    await openTaskDetail(pages.approver, rejected.processInstanceId, rejectA.taskId)
    await submitCommentTaskAction(pages.approver, {
      buttonName: '驳回', dialogName: '驳回任务', commentPlaceholder: '请输入驳回原因',
      comment: '申请材料不符合要求', endpoint: '/workflow/task/reject'
    })
    const rejectedDetail = await getWorkflowDetail(pages.admin, rejected.processInstanceId)
    expect(rejectedDetail.processStatus).toBe('rejected')
    expect(rejectedDetail.endTime).not.toBeNull()
    await expectWorkflowAudit(pages.admin, rejected.processInstanceId, {
      taskId: rejectA.taskId,
      type: '3',
      action: 'REJECT',
      actorUserId: approver.value,
      opinion: '申请材料不符合要求',
      targetNodeKey: 'reviewA'
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, rejected.processInstanceId, rejectA.taskId, 409, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/reject', {
          data: { taskId: rejectA.taskId, comment: '终态重复驳回', copyUserIds: [] }, expectedCode
        }), copyReadablePages)
    evidence.push({ scenario: 'reject', finalStatus: rejectedDetail.processStatus, deniedCode: 403, staleCode: 409 })

    // 场景四：一级已办从工作台撤回未处理后继，恢复为全新一级任务并记录撤回审计。
    const revoked = await startTrackedScenario(
      pages.starter, sequentialDefinition, formName, runId, 'revoke', resources)
    const revokeA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', revoked.processInstanceId)
    await openTaskDetail(pages.approver, revoked.processInstanceId, revokeA.taskId)
    await submitCommentTaskAction(pages.approver, {
      buttonName: '通过', dialogName: '通过任务', commentPlaceholder: '请输入审批意见',
      comment: '一级通过后准备撤回', endpoint: '/workflow/task/complete'
    })
    const completedRevokeA = await findCompletedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', revoked.processInstanceId)
    expect(completedRevokeA.revocable).toBe(true)
    const revokeB = await findAssignedWorkflowTask(
      pages.admin, sequentialProcessKey, 'reviewB', revoked.processInstanceId)
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, revoked.processInstanceId, revokeB.taskId, 403, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/revokeProcess', {
          data: { procInsId: revoked.processInstanceId, taskId: revokeA.taskId, comment: '非完成人撤回必须拒绝' },
          expectedCode
        }), copyReadablePages)
    await revokeTaskThroughUi(pages.approver, sequentialProcessKey, revoked.businessKey, '发现材料需要补充')
    const revokedA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', revoked.processInstanceId)
    expect(String(revokedA.taskId)).not.toBe(String(revokeA.taskId))
    await expectActiveTaskState(pages.admin, revoked.processInstanceId, revokedA.taskId, {
      taskDefinitionKey: 'reviewA', assignee: String(approver.value)
    })
    await expectWorkflowAudit(pages.admin, revoked.processInstanceId, {
      taskId: revokeB.taskId,
      type: '7',
      action: 'REVOKE',
      actorUserId: approver.value,
      opinion: '发现材料需要补充',
      targetNodeKey: 'reviewA',
      sourceTaskId: revokeA.taskId
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, revoked.processInstanceId, revokedA.taskId, 409, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/revokeProcess', {
          data: { procInsId: revoked.processInstanceId, taskId: revokeA.taskId, comment: '重复撤回旧已办' },
          expectedCode
        }), copyReadablePages)
    evidence.push({ scenario: 'revoke', finalStatus: 'running', activeNode: 'reviewA', deniedCode: 403, staleCode: 409 })

    // 场景五：一级任务委派给管理员，受托人只能完成委派，resolve 后任务回到原 owner。
    const delegated = await startTrackedScenario(
      pages.starter, sequentialDefinition, formName, runId, 'delegate', resources)
    const delegateA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', delegated.processInstanceId)
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, delegated.processInstanceId, delegateA.taskId, 403, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/delegate', {
          data: { taskId: delegateA.taskId, userId: Number(admin.value), comment: '越权委派必须拒绝', copyUserIds: [] },
          expectedCode
        }), copyReadablePages)
    await openTaskDetail(pages.approver, delegated.processInstanceId, delegateA.taskId)
    await submitUserTaskAction(pages.approver, 'delegate', 'workflow_admin', admin, '请管理员协助核验')
    const delegatedAdminTask = await findAssignedWorkflowTask(
      pages.admin, sequentialProcessKey, 'reviewA', delegated.processInstanceId)
    expect(String(delegatedAdminTask.taskId)).toBe(String(delegateA.taskId))
    await expectActiveTaskState(pages.admin, delegated.processInstanceId, delegateA.taskId, {
      taskDefinitionKey: 'reviewA',
      assignee: String(admin.value),
      owner: String(approver.value),
      delegationState: 'PENDING'
    })
    await expectWorkflowAudit(pages.admin, delegated.processInstanceId, {
      taskId: delegateA.taskId,
      type: '4',
      action: 'DELEGATE',
      actorUserId: approver.value,
      opinion: '请管理员协助核验',
      targetUserId: admin.value
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, delegated.processInstanceId, delegateA.taskId, 409, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/delegate', {
          data: { taskId: delegateA.taskId, userId: Number(approver.value), comment: '委派中重复委派', copyUserIds: [] },
          expectedCode
        }), copyReadablePages)
    await openTaskDetail(pages.admin, delegated.processInstanceId, delegateA.taskId)
    const resolveOpinion = '已完成委派事项核验'
    await submitCommentTaskAction(pages.admin, {
      buttonName: '完成委派',
      dialogName: '完成委派',
      commentPlaceholder: '请输入委派事项的真实办理意见',
      comment: resolveOpinion,
      endpoint: '/workflow/task/resolve'
    })
    const resolvedApproverTask = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', delegated.processInstanceId)
    expect(String(resolvedApproverTask.taskId)).toBe(String(delegateA.taskId))
    await expectActiveTaskState(pages.admin, delegated.processInstanceId, delegateA.taskId, {
      taskDefinitionKey: 'reviewA',
      assignee: String(approver.value),
      owner: String(approver.value),
      delegationState: 'RESOLVED'
    })
    await expectWorkflowAudit(pages.admin, delegated.processInstanceId, {
      taskId: delegateA.taskId,
      type: '4',
      action: 'RESOLVE',
      actorUserId: admin.value,
      opinion: resolveOpinion,
      targetUserId: approver.value
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, delegated.processInstanceId, delegateA.taskId, 409, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/resolve', {
          data: { taskId: delegateA.taskId, comment: '重复完成委派', copyUserIds: [] }, expectedCode
        }), copyReadablePages)
    evidence.push({ scenario: 'delegate-resolve', finalStatus: 'running', delegationState: 'RESOLVED', deniedCode: 403, staleCode: 409 })

    // 场景六：一级任务永久转办给管理员，原办理人失去对象权限，管理员完成后旧任务不可重复提交。
    const transferred = await startTrackedScenario(
      pages.starter, sequentialDefinition, formName, runId, 'transfer', resources)
    const transferA = await findAssignedWorkflowTask(
      pages.approver, sequentialProcessKey, 'reviewA', transferred.processInstanceId)
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, transferred.processInstanceId, transferA.taskId, 403, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/transfer', {
          data: { taskId: transferA.taskId, userId: Number(admin.value), comment: '越权转办必须拒绝', copyUserIds: [] },
          expectedCode
        }), copyReadablePages)
    await openTaskDetail(pages.approver, transferred.processInstanceId, transferA.taskId)
    await submitUserTaskAction(pages.approver, 'transfer', 'workflow_admin', admin, '转管理员继续办理')
    const transferredAdminTask = await findAssignedWorkflowTask(
      pages.admin, sequentialProcessKey, 'reviewA', transferred.processInstanceId)
    expect(String(transferredAdminTask.taskId)).toBe(String(transferA.taskId))
    await expectActiveTaskState(pages.admin, transferred.processInstanceId, transferA.taskId, {
      taskDefinitionKey: 'reviewA', assignee: String(admin.value), owner: null, delegationState: null
    })
    await expectWorkflowAudit(pages.admin, transferred.processInstanceId, {
      taskId: transferA.taskId,
      type: '5',
      action: 'TRANSFER',
      actorUserId: approver.value,
      opinion: '转管理员继续办理',
      targetUserId: admin.value
    })
    await openTaskDetail(pages.admin, transferred.processInstanceId, transferA.taskId)
    await submitCommentTaskAction(pages.admin, {
      buttonName: '通过', dialogName: '通过任务', commentPlaceholder: '请输入审批意见',
      comment: '转办后完成一级', endpoint: '/workflow/task/complete'
    })
    const transferB = await findAssignedWorkflowTask(
      pages.admin, sequentialProcessKey, 'reviewB', transferred.processInstanceId)
    expect(String(transferB.taskId)).not.toBe(String(transferA.taskId))
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, transferred.processInstanceId, transferB.taskId, 409, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/complete', {
          data: { taskId: transferA.taskId, comment: '转办历史任务重复完成', variables: {}, copyUserIds: [], nextUserIds: [] },
          expectedCode
        }), copyReadablePages)
    await expectWorkflowAudit(pages.admin, transferred.processInstanceId, {
      taskId: transferA.taskId,
      type: '1',
      action: 'COMPLETE',
      actorUserId: admin.value,
      opinion: '转办后完成一级'
    })
    evidence.push({ scenario: 'transfer', finalStatus: 'running', activeNode: 'reviewB', deniedCode: 403, staleCode: 409 })

    // 场景七：候选审批人从待签页认领并从待办页取消认领，错误候选与重复动作均无副作用。
    const claimed = await startTrackedScenario(
      pages.starter, candidateDefinition, formName, runId, 'claim', resources)
    const candidateTask = await findClaimableWorkflowTask(
      pages.approver, candidateProcessKey, 'candidateReview', claimed.processInstanceId)
    await expectActiveTaskState(pages.admin, claimed.processInstanceId, candidateTask.taskId, {
      taskDefinitionKey: 'candidateReview', assignee: null, claimedBy: null, claimTimePresent: false
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, claimed.processInstanceId, candidateTask.taskId, 403, expectedCode => callWorkflowApi(
        pages.admin, 'POST', '/workflow/task/claim', {
          data: { taskId: candidateTask.taskId }, expectedCode
        }), copyReadablePages)
    await claimTaskThroughUi(pages.approver, candidateProcessKey, claimed.businessKey)
    const claimedTask = await findAssignedWorkflowTask(
      pages.approver, candidateProcessKey, 'candidateReview', claimed.processInstanceId)
    expect(String(claimedTask.taskId)).toBe(String(candidateTask.taskId))
    await expectActiveTaskState(pages.admin, claimed.processInstanceId, candidateTask.taskId, {
      taskDefinitionKey: 'candidateReview',
      assignee: String(approver.value),
      claimedBy: String(approver.value),
      claimTimePresent: true
    })
    await expectWorkflowAudit(pages.admin, claimed.processInstanceId, {
      taskId: candidateTask.taskId,
      type: '1',
      action: 'CLAIM',
      actorUserId: approver.value,
      opinion: '用户认领任务'
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, claimed.processInstanceId, candidateTask.taskId, 409, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/claim', {
          data: { taskId: candidateTask.taskId }, expectedCode
        }), copyReadablePages)
    await unclaimTaskThroughUi(
      pages.approver, claimed.processInstanceId, candidateTask.taskId)
    const unclaimedTask = await findClaimableWorkflowTask(
      pages.approver, candidateProcessKey, 'candidateReview', claimed.processInstanceId)
    expect(String(unclaimedTask.taskId)).toBe(String(candidateTask.taskId))
    await expectActiveTaskState(pages.admin, claimed.processInstanceId, candidateTask.taskId, {
      taskDefinitionKey: 'candidateReview', assignee: null, claimedBy: null, claimTimePresent: false
    })
    await expectWorkflowAudit(pages.admin, claimed.processInstanceId, {
      taskId: candidateTask.taskId,
      type: '1',
      action: 'UNCLAIM',
      actorUserId: approver.value,
      opinion: '用户取消认领任务'
    })
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin, claimed.processInstanceId, candidateTask.taskId, 403, expectedCode => callWorkflowApi(
        pages.approver, 'POST', '/workflow/task/unClaim', {
          data: { taskId: candidateTask.taskId }, expectedCode
        }), copyReadablePages)
    evidence.push({ scenario: 'claim-unclaim', finalStatus: 'running', assignee: null, deniedCode: 403, staleCode: 409 })

    // 场景八：缓存部署页和模型页后创建 V2，经模型页真实部署按钮发布，并在返回部署页时自动刷新。
    const deploymentListPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/deploy/list', 'GET'))
    await pages.designer.goto('/workflow/deploy')
    await expectAjaxSuccess(await deploymentListPromise, '/workflow/deploy/list')
    await pages.designer.getByPlaceholder('请输入流程标识').fill(sequentialProcessKey)
    const filteredDeploymentPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/deploy/list', 'GET'))
    await pages.designer.getByRole('button', { name: '搜索', exact: true }).click()
    await expectAjaxSuccess(await filteredDeploymentPromise, '/workflow/deploy/list')
    await expect(pages.designer.locator('.el-table__body-wrapper tbody tr').filter({
      hasText: sequentialProcessKey
    }).first()).toContainText('V1')

    const initialModelListPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/list', 'GET'))
    await pages.designer.locator('.sidebar-container a[href="/workflow/model"]').click()
    await expectAjaxSuccess(await initialModelListPromise, '/workflow/model/list')
    await pages.designer.getByPlaceholder('请输入模型标识').fill(sequentialProcessKey)
    const filteredModelPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/list', 'GET'))
    await pages.designer.getByRole('button', { name: '搜索', exact: true }).click()
    await expectAjaxSuccess(await filteredModelPromise, '/workflow/model/list')

    const savedVersion = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/save', {
      data: {
        requestId: randomUUID(),
        modelId: sequentialModel.modelId,
        bpmnXml: sequentialBpmnXml,
        newVersion: false
      }
    })
    const versionTwoModelId = String(savedVersion.data?.modelId || '')
    expect(versionTwoModelId, '从已部署 V1 保存必须返回正式 V2 模型主键').not.toBe('')
    expect(versionTwoModelId).not.toBe(sequentialModel.modelId)
    resources.modelIds.push(versionTwoModelId)

    const cachedDeploymentPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/deploy/list', 'GET'))
    await pages.designer.locator('.sidebar-container a[href="/workflow/deploy"]').click()
    await expectAjaxSuccess(await cachedDeploymentPromise, '/workflow/deploy/list')
    const cachedModelPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/list', 'GET'))
    await pages.designer.locator('.sidebar-container a[href="/workflow/model"]').click()
    await expectAjaxSuccess(await cachedModelPromise, '/workflow/model/list')
    const versionTwoModelRow = pages.designer.locator('.el-table__body-wrapper tbody tr').filter({
      hasText: sequentialProcessKey
    }).first()
    await expect(versionTwoModelRow, '重新进入缓存模型页必须自动显示最新 V2').toContainText('V2')
    await expect(versionTwoModelRow).toContainText('未部署')

    const deployVersionTwoPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/deploy', 'POST'))
    await versionTwoModelRow.locator('button.el-button--success').click()
    const deploymentConfirmation = pages.designer.locator('.el-message-box')
    await expect(deploymentConfirmation).toContainText('V2')
    await deploymentConfirmation.getByRole('button', { name: '确定', exact: true }).click()
    const deployedVersionTwo = await expectAjaxSuccess(
      await deployVersionTwoPromise, '/workflow/model/deploy')
    const versionTwoDeploymentId = String(deployedVersionTwo.data?.deploymentId || '')
    expect(versionTwoDeploymentId, 'V2 部署必须返回正式部署主键').not.toBe('')
    resources.deploymentIds.push(versionTwoDeploymentId)

    const refreshedDeploymentPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/deploy/list', 'GET'))
    await pages.designer.locator('.sidebar-container a[href="/workflow/deploy"]').click()
    await expectAjaxSuccess(await refreshedDeploymentPromise, '/workflow/deploy/list')
    const versionTwoDeploymentRow = pages.designer.locator('.el-table__body-wrapper tbody tr').filter({
      hasText: sequentialProcessKey
    }).first()
    await expect(versionTwoDeploymentRow, '返回缓存部署页必须自动显示最新发布版本').toContainText('V2')
    await expect(versionTwoDeploymentRow).toContainText('已激活')

    const published = await callWorkflowApi(pages.designer, 'GET', '/workflow/deploy/publishList', {
      query: { processKey: sequentialProcessKey, pageNum: 1, pageSize: 20 }
    })
    const publishedVersions = (published.rows || []).filter(row => row.processKey === sequentialProcessKey)
    expect(publishedVersions, '唯一流程标识必须保留两个可追踪发布版本').toHaveLength(2)
    const publishedV1 = publishedVersions.find(row => Number(row.version) === 1)
    const publishedV2 = publishedVersions.find(row => Number(row.version) === 2)
    expect(publishedV1?.suspended, '发布 V2 后历史 V1 必须自动挂起').toBe(true)
    expect(publishedV2?.suspended, '发布 V2 后最新定义必须保持激活').toBe(false)
    expect(String(publishedV1?.definitionId || '')).toBe(sequentialDefinition.definitionId)

    const latestSequentialDefinition = await findStartableWorkflowDefinition(
      pages.starter, sequentialProcessKey)
    expect(latestSequentialDefinition.definitionId).toBe(String(publishedV2.definitionId))
    expect(latestSequentialDefinition.deploymentId).toBe(versionTwoDeploymentId)
    await expectActiveTaskState(pages.approver, returned.processInstanceId, returnedA.taskId, {
      taskDefinitionKey: 'reviewA', assignee: String(approver.value)
    })
    evidence.push({
      scenario: 'deploy-version-refresh',
      latestVersion: 2,
      latestState: 'active',
      historicalState: 'suspended',
      oldVersionInstanceState: 'running'
    })

    await testInfo.attach('workflow-lifecycle-evidence.json', {
      body: Buffer.from(JSON.stringify({ runId, scenarios: evidence }, null, 2)),
      contentType: 'application/json'
    })
  } catch (error) {
    primaryError = error
  } finally {
    const cleanupErrors = await cleanupWorkflowResources(pages, resources)
    const logoutErrors = await closeWorkflowRoleSessions(sessions)
    const finalErrors = [...cleanupErrors, ...logoutErrors]
    if (primaryError) {
      if (finalErrors.length) primaryError.message += `；清理失败：${finalErrors.join(' | ')}`
      throw primaryError
    }
    expect(finalErrors, '正式业务夹具和 Redis 登录态必须全部清理').toEqual([])
  }
})
