import { expect, test } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成受控整改循环用例的唯一正式资产。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,processInstanceId:string,taskIds:string[]}} 测试资产登记。
 */
function controlledLoopAssets() {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_UILOOP001_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_整改表单`, modelName: `${prefix}_受控整改循环`,
    modelKey: `${prefix}_model`, processInstanceId: '', taskIds: []
  }
}

/**
 * 转义只读 SQL 使用的业务字符串。
 * @param {string} value 流程实例或模型业务值。
 * @returns {string} MySQL 字符串字面量正文。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 读取当前受控循环活动任务主键。
 * @param {string} processInstanceId 流程实例主键。
 * @returns {string} 当前唯一活动任务主键。
 */
function activeTaskId(processInstanceId) {
  const rows = queryReadOnly(
    `SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${sqlLiteral(processInstanceId)}' AND TASK_DEF_KEY_ = 'review'`
  )
  expect(rows, '受控整改循环每轮必须只有一份活动任务').toHaveLength(1)
  return rows[0][0]
}

/**
 * 从待办详情填写正式节点表单并完成一轮受控整改。
 * @param {WorkflowWorkbenchPage} workbench 当前审批工作台页面对象。
 * @param {string} processName 流程名称。
 * @param {{decisionLabel:'继续整改'|'整改通过',note:string,opinion:string}} round 本轮判断值、整改说明和办理意见。
 * @returns {Promise<void>} 任务完成接口成功且动作弹窗关闭后结束。
 */
async function completeControlledLoopRound(workbench, processName, round) {
  const row = await workbench.filterTaskRow('/office/todo', processName, '整改审批')
  await row.locator('button').first().click()
  await expect(workbench.page).toHaveURL(/\/workflow\/process-detail\//u)
  const form = workbench.page.getByRole('tabpanel', { name: '办理表单' })
  const decisionItem = form.locator('.el-form-item').filter({ hasText: '审批结论' })
  await decisionItem.locator('.el-select__wrapper').click()
  await workbench.page.locator('.el-select-dropdown:visible')
    .getByRole('option', { name: round.decisionLabel, exact: true }).click()
  await form.getByPlaceholder('请输入本轮整改说明').fill(round.note)

  await workbench.page.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = workbench.page.getByRole('dialog', { name: '通过任务' })
  await dialog.getByLabel('办理意见').fill(round.opinion)
  const responsePromise = workbench.page.waitForResponse(response => (
    matchesEndpoint(response, '/workflow/task/complete', 'POST')
  ))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/workflow/task/complete')
  await expect(dialog).toBeHidden()
}

test('@full [UI-ROUTE-006] 受控整改循环通过真实UI重复办理并按正式字段退出', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  const assets = controlledLoopAssets()
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createControlledLoopForm({ name: assets.formName, remark: `${assets.prefix} 受控整改循环` })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 真实受控整改循环`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureCandidateRole('流程审批人', '整改审批')
    await processDesigner.configureFormPermissionsForElement({
      elementId: 'review', formName: assets.formName, defaultMode: '可编辑', fieldModes: {}
    })
    await processDesigner.configureControlledApprovalLoop({
      elementId: 'review', maxIterations: 3,
      decisionFieldLabel: '审批结论（reviewResult）',
      repeatLabel: '继续整改', exitLabel: '整改通过'
    })
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请主题`)

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const workbench = new WorkflowWorkbenchPage(approver.page)
    await workbench.claimTask(assets.modelName, '整改审批')
    const firstTaskId = activeTaskId(assets.processInstanceId)
    assets.taskIds.push(firstTaskId)
    await completeControlledLoopRound(workbench, assets.modelName, {
      decisionLabel: '继续整改', note: `${assets.prefix}_第一轮需整改`, opinion: `${assets.prefix}_继续整改`
    })

    const firstAudit = queryReadOnly(
      `SELECT iteration_no, decision_value, outcome FROM wf_controlled_loop_execution WHERE process_instance_id = '${sqlLiteral(assets.processInstanceId)}' ORDER BY iteration_no`
    )
    expect(firstAudit, '第一轮必须持久化再次整改审计').toEqual([['1', 'RECTIFY', 'REPEAT']])
    await workbench.claimTask(assets.modelName, '整改审批')
    const secondTaskId = activeTaskId(assets.processInstanceId)
    assets.taskIds.push(secondTaskId)
    expect(secondTaskId, '再次整改必须生成新的真实任务主键').not.toBe(firstTaskId)

    const secondTodo = await workbench.filterTaskRow('/office/todo', assets.modelName, '整改审批')
    await secondTodo.locator('button').first().click()
    const loopSection = approver.page.locator('.workflow-detail__controlled-loops')
    await expect(loopSection.getByText('第 2 / 3 轮办理中', { exact: true })).toBeVisible()
    await expect(loopSection.getByText('再次整改', { exact: true })).toBeVisible()
    await approver.page.goBack()

    await completeControlledLoopRound(workbench, assets.modelName, {
      decisionLabel: '整改通过', note: `${assets.prefix}_第二轮已通过`, opinion: `${assets.prefix}_退出循环`
    })
    await expect(approver.page.getByText('已完成', { exact: true }).first()).toBeVisible()

    const finalAudit = queryReadOnly(
      `SELECT iteration_no, decision_value, outcome FROM wf_controlled_loop_execution WHERE process_instance_id = '${sqlLiteral(assets.processInstanceId)}' ORDER BY iteration_no`
    )
    expect(finalAudit, '两轮受控循环审计必须按 REPEAT、EXIT 顺序持久化').toEqual([
      ['1', 'RECTIFY', 'REPEAT'], ['2', 'PASS', 'EXIT']
    ])
    const taskRows = queryReadOnly(
      `SELECT ID_, END_TIME_ IS NOT NULL, DELETE_REASON_ IS NULL FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${sqlLiteral(assets.processInstanceId)}' AND TASK_DEF_KEY_ = 'review' ORDER BY START_TIME_, ID_`
    )
    expect(taskRows.map(row => row[0]), '历史任务必须保留两轮不同主键').toEqual(assets.taskIds)
    expect(taskRows.every(row => row[1] === '1' && row[2] === '1'), '两轮任务都必须自然完成').toBe(true)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${sqlLiteral(assets.processInstanceId)}'`
    )).toEqual([['1']])
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
