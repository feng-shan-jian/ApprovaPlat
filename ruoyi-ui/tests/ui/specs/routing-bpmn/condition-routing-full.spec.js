import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowIntegrationPage } from '../../page-objects/integration.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成排他条件路由用例的唯一正式资产。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,processInstanceIds:string[]}} 测试资产登记。
 */
function routingAssets(caseKey = 'UIROUTE001', routeName = '排他路由') {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_${caseKey}_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_路由表单`, modelName: `${prefix}_${routeName}`,
    modelKey: `${prefix}_model`, processInstanceIds: []
  }
}

/**
 * 转义测试生成的只读 SQL 字符串。
 * @param {string} value 流程实例主键或测试名称。
 * @returns {string} 可安全放入只读 SQL 字符串字面量的正文。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 通过新建流程页填写条件表单并正式提交。
 * @param {import('@playwright/test').Page} page 发起人真实浏览器页面。
 * @param {string} processName 流程部署显示名称。
 * @param {{title:string,amount?:number}} values 条件路由使用的表单值；amount 缺省时保持用户未填写状态。
 * @returns {Promise<string>} 真实流程实例主键。
 */
async function startRoutingProcess(page, processName, values) {
  const workbench = new WorkflowWorkbenchPage(page)
  const row = await workbench.filterRow('/office/create', '请输入流程名称', processName)
  await row.locator('button').first().click()
  await expect(page).toHaveURL(/\/workflow\/process-start\//u)
  const form = page.locator('.workflow-form-renderer')
  await form.getByPlaceholder('请输入申请主题').fill(values.title)
  const amount = form.locator('.el-form-item').filter({ hasText: '申请金额' }).getByRole('spinbutton')
  if (values.amount !== undefined) {
    // 仅在用例显式提供金额时模拟用户输入，缺失字段场景必须保持真实空值并由表单序列化自然省略变量。
    await amount.fill(String(values.amount))
    await amount.press('Tab')
  }
  const submitPromise = page.waitForResponse(response => (
    response.request().method() === 'POST'
    && /\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/iu.test(new URL(response.url()).pathname)
  ))
  await page.getByRole('button', { name: '正式提交', exact: true }).click()
  const submitted = await expectAjaxSuccess(await submitPromise, '/workflow/process/draft/{id}/submit')
  const processInstanceId = String(submitted.data?.processInstanceId || submitted.data?.id || '')
  expect(processInstanceId, '正式提交必须返回流程实例主键').not.toBe('')
  await expect(page).toHaveURL(new RegExp(`/workflow/process-detail/${processInstanceId}(?:[/?]|$)`, 'u'))
  return processInstanceId
}

/**
 * 从审批工作台完成当前唯一任务，并核对下一活动节点或流程终态。
 * @param {WorkflowWorkbenchPage} workbench 审批人工作台页面对象。
 * @param {string} processName 流程部署显示名称。
 * @param {string} comment 办理意见。
 * @param {boolean} completed 本次任务是否应完成整个实例。
 * @returns {Promise<void>} 任务通过且页面刷新到预期状态后结束。
 */
async function approveCurrentTask(workbench, processName, comment, completed) {
  await workbench.claimProcess(processName)
  await workbench.approveProcess(processName, comment, completed)
}

test('@full [UI-ROUTE-001] 排他网关通过真实UI建模并覆盖命中、默认和冲突回滚', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  const assets = routingAssets()
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
    await configuration.createConditionRoutingForm({
      name: assets.formName, remark: `${assets.prefix} 排他条件真实 UI 路由`
    })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 排他条件真实 UI 建模`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.deleteElement('end')
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '条件预审', 'intake')
    await processDesigner.appendExclusiveGatewayAfter('intake', 'exclusiveRoute', '申请分流')

    const highTask = await processDesigner.appendUserTaskAfter('exclusiveRoute')
    await processDesigner.configureCandidateRoleForElement(highTask, '流程审批人', '大额审批', 'highReview')
    const highEnd = await processDesigner.appendEndEventAfter('highReview')
    await processDesigner.configureElementIdentity(highEnd, 'highEnd', '大额结束')

    const textTask = await processDesigner.appendUserTaskAfter('exclusiveRoute')
    await processDesigner.configureCandidateRoleForElement(textTask, '流程审批人', '重点审批', 'textReview')
    const textEnd = await processDesigner.appendEndEventAfter('textReview')
    await processDesigner.configureElementIdentity(textEnd, 'textEnd', '重点结束')

    const defaultTask = await processDesigner.appendUserTaskAfter('exclusiveRoute')
    await processDesigner.configureCandidateRoleForElement(defaultTask, '流程审批人', '普通审批', 'defaultReview')
    const defaultEnd = await processDesigner.appendEndEventAfter('defaultReview')
    await processDesigner.configureElementIdentity(defaultEnd, 'defaultEnd', '普通结束')

    const highFlow = await processDesigner.findSequenceFlowId('exclusiveRoute', 'highReview')
    const textFlow = await processDesigner.findSequenceFlowId('exclusiveRoute', 'textReview')
    const defaultFlow = await processDesigner.findSequenceFlowId('exclusiveRoute', 'defaultReview')
    await processDesigner.configureConditionRuleBranch({
      flowId: highFlow,
      branchName: '金额达到五千',
      rules: [{ fieldLabel: '申请金额（amount）', operatorLabel: '大于等于', value: 5000 }]
    })
    await processDesigner.configureConditionRuleBranch({
      flowId: textFlow,
      branchName: '重点申请',
      rules: [{ fieldLabel: '申请主题（requestTitle）', operatorLabel: '包含', value: '重点' }]
    })
    await processDesigner.configureDefaultConditionBranch(defaultFlow, '普通默认')
    const authorXml = await processDesigner.readDesignerXml()
    expect(authorXml).toContain('approva.conditionRule.config')
    expect(authorXml).not.toContain('conditionExpression')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    const scenarios = [
      { key: 'high', title: '普通大额申请', amount: 6000, target: 'highReview' },
      { key: 'text', title: '重点小额申请', amount: 100, target: 'textReview' },
      { key: 'default', title: '普通小额申请', amount: 100, target: 'defaultReview' }
    ]
    const routingEvidence = []
    for (const scenario of scenarios) {
      const processInstanceId = await startRoutingProcess(starter.page, assets.modelName, scenario)
      assets.processInstanceIds.push(processInstanceId)
      const escapedInstanceId = sqlLiteral(processInstanceId)
      expect(queryReadOnly(
        `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
      )).toEqual([['intake']])
      await approveCurrentTask(approverWorkbench, assets.modelName, `${assets.prefix}_${scenario.key}_预审`, false)
      expect(queryReadOnly(
        `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
      ), `${scenario.key} 必须只进入目标分支`).toEqual([[scenario.target]])
      await approveCurrentTask(approverWorkbench, assets.modelName, `${assets.prefix}_${scenario.key}_分支`, true)
      expect(queryReadOnly(
        `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
      )).toEqual([['1']])
      routingEvidence.push({ scenario: scenario.key, processInstanceId, target: scenario.target })
    }

    const conflictId = await startRoutingProcess(starter.page, assets.modelName, {
      title: '重点大额冲突申请', amount: 6000
    })
    assets.processInstanceIds.push(conflictId)
    const escapedConflictId = sqlLiteral(conflictId)
    await approverWorkbench.claimProcess(assets.modelName)
    const conflictRow = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await conflictRow.locator('button').first().click()
    await expect(approver.page).toHaveURL(/\/workflow\/process-detail\//u)
    const beforeConflict = queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedConflictId}'), (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedConflictId}' AND END_TIME_ IS NOT NULL), (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedConflictId}')`
    )
    const intakeTaskId = queryReadOnly(
      `SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedConflictId}' AND TASK_DEF_KEY_ = 'intake'`
    )[0]?.[0]
    expect(intakeTaskId, '冲突场景必须保留唯一预审任务').toBeTruthy()
    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const dialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await dialog.getByLabel('办理意见').fill(`${assets.prefix}_冲突回滚`)
    const responsePromise = approver.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    const conflictResponse = await responsePromise
    expect(conflictResponse.status()).toBe(200)
    const conflictPayload = await conflictResponse.json()
    expect(conflictPayload.code).toBe(409)
    expect(String(conflictPayload.msg || '')).toContain('多个条件同时命中')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('多个条件同时命中')
    expect(queryReadOnly(
      `SELECT ID_, TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedConflictId}'`
    ), '条件冲突必须回滚并保留同一预审任务').toEqual([[intakeTaskId, 'intake']])
    expect(queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedConflictId}'), (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedConflictId}' AND END_TIME_ IS NOT NULL), (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedConflictId}')`
    ), '条件冲突不得产生任务、历史或变量副作用').toEqual(beforeConflict)
    routingEvidence.push({ scenario: 'conflict', processInstanceId: conflictId, code: conflictPayload.code })
    await testInfo.attach('routing-evidence.json', {
      body: Buffer.from(JSON.stringify(routingEvidence, null, 2)), contentType: 'application/json'
    })
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-ROUTE-002] 包容网关通过真实UI多命中并等待全部分支完成', async ({ browser }, testInfo) => {
  test.setTimeout(210_000)
  const assets = routingAssets('UIROUTE002', '包容路由')
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
    await configuration.createConditionRoutingForm({
      name: assets.formName, remark: `${assets.prefix} 包容条件真实 UI 路由`
    })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 包容条件真实 UI 建模`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.deleteElement('end')
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '包容预审', 'intake')
    await processDesigner.appendExclusiveGatewayAfter('intake', 'inclusiveRoute', '包容分流')
    await processDesigner.replaceGatewayType('inclusiveRoute', 'inclusive')

    const amountTask = await processDesigner.appendUserTaskAfter('inclusiveRoute')
    await processDesigner.configureCandidateRoleForElement(amountTask, '流程审批人', '金额复核', 'amountReview')
    const amountEnd = await processDesigner.appendEndEventAfter('amountReview')
    await processDesigner.configureElementIdentity(amountEnd, 'amountEnd', '金额结束')

    const textTask = await processDesigner.appendUserTaskAfter('inclusiveRoute')
    await processDesigner.configureCandidateRoleForElement(textTask, '流程审批人', '重点复核', 'importantReview')
    const textEnd = await processDesigner.appendEndEventAfter('importantReview')
    await processDesigner.configureElementIdentity(textEnd, 'importantEnd', '重点结束')

    const defaultTask = await processDesigner.appendUserTaskAfter('inclusiveRoute')
    await processDesigner.configureCandidateRoleForElement(defaultTask, '流程审批人', '包容默认复核', 'inclusiveDefaultReview')
    const defaultEnd = await processDesigner.appendEndEventAfter('inclusiveDefaultReview')
    await processDesigner.configureElementIdentity(defaultEnd, 'inclusiveDefaultEnd', '默认结束')

    const amountFlow = await processDesigner.findSequenceFlowId('inclusiveRoute', 'amountReview')
    const textFlow = await processDesigner.findSequenceFlowId('inclusiveRoute', 'importantReview')
    const defaultFlow = await processDesigner.findSequenceFlowId('inclusiveRoute', 'inclusiveDefaultReview')
    await processDesigner.configureConditionRuleBranch({
      flowId: amountFlow,
      branchName: '金额达到五千',
      rules: [{ fieldLabel: '申请金额（amount）', operatorLabel: '大于等于', value: 5000 }]
    })
    await processDesigner.configureConditionRuleBranch({
      flowId: textFlow,
      branchName: '重点申请',
      rules: [{ fieldLabel: '申请主题（requestTitle）', operatorLabel: '包含', value: '重点' }]
    })
    await processDesigner.configureDefaultConditionBranch(defaultFlow, '包容默认')
    const authorXml = await processDesigner.readDesignerXml()
    expect(authorXml).toContain('<inclusiveGateway')
    expect(authorXml).toContain('approva.conditionRule.config')
    expect(authorXml).not.toContain('conditionExpression')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const processInstanceId = await startRoutingProcess(starter.page, assets.modelName, {
      title: '重点大额包容申请', amount: 8000
    })
    assets.processInstanceIds.push(processInstanceId)
    const escapedInstanceId = sqlLiteral(processInstanceId)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approveCurrentTask(approverWorkbench, assets.modelName, `${assets.prefix}_包容预审`, false)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' ORDER BY TASK_DEF_KEY_`
    ), '包容网关必须同时创建两条命中分支且不得进入默认分支').toEqual([
      ['amountReview'], ['importantReview']
    ])

    await approverWorkbench.claimTask(assets.modelName, '金额复核')
    await approverWorkbench.approveTask(assets.modelName, '金额复核', `${assets.prefix}_金额分支`, false)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '首条包容分支完成后实例必须等待剩余分支').toEqual([['importantReview']])
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])

    await approverWorkbench.claimTask(assets.modelName, '重点复核')
    await approverWorkbench.approveTask(assets.modelName, '重点复核', `${assets.prefix}_重点分支`, true)
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['0']])
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    await testInfo.attach('routing-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId,
        matchedTargets: ['amountReview', 'importantReview'],
        defaultTargetCreated: false
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-ROUTE-003] 并行网关通过真实UI分支汇聚且只创建一个后继任务', async ({ browser }, testInfo) => {
  test.setTimeout(210_000)
  const assets = routingAssets('UIROUTE003', '并行汇聚')
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
    await configuration.createTextForm({ name: assets.formName, remark: `${assets.prefix} 并行汇聚真实 UI` })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 并行分支与汇聚`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.deleteElement('end')
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '并行预审', 'intake')
    await processDesigner.appendExclusiveGatewayAfter('intake', 'parallelSplit', '并行拆分')
    await processDesigner.replaceGatewayType('parallelSplit', 'parallel')

    const firstTask = await processDesigner.appendUserTaskAfter('parallelSplit')
    await processDesigner.configureCandidateRoleForElement(firstTask, '流程审批人', '并行甲审批', 'parallelReviewA')
    await processDesigner.appendExclusiveGatewayAfter('parallelReviewA', 'parallelJoin', '并行汇聚')
    await processDesigner.replaceGatewayType('parallelJoin', 'parallel')

    const secondTask = await processDesigner.appendUserTaskAfter('parallelSplit')
    await processDesigner.configureCandidateRoleForElement(secondTask, '流程审批人', '并行乙审批', 'parallelReviewB')
    await processDesigner.connectShapes('parallelReviewB', 'parallelJoin')

    const summaryTask = await processDesigner.appendUserTaskAfter('parallelJoin')
    await processDesigner.configureCandidateRoleForElement(summaryTask, '流程审批人', '并行汇总审批', 'parallelSummary')
    const end = await processDesigner.appendEndEventAfter('parallelSummary')
    await processDesigner.configureElementIdentity(end, 'parallelEnd', '并行结束')
    const authorXml = await processDesigner.readDesignerXml()
    expect(authorXml.match(/<parallelGateway/gu) || []).toHaveLength(2)
    expect(authorXml).toContain('sourceRef="parallelReviewB" targetRef="parallelJoin"')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_并行申请`)
    assets.processInstanceIds.push(processInstanceId)
    const escapedInstanceId = sqlLiteral(processInstanceId)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approveCurrentTask(approverWorkbench, assets.modelName, `${assets.prefix}_并行预审`, false)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' ORDER BY TASK_DEF_KEY_`
    )).toEqual([['parallelReviewA'], ['parallelReviewB']])

    await approverWorkbench.claimTask(assets.modelName, '并行甲审批')
    await approverWorkbench.approveTask(assets.modelName, '并行甲审批', `${assets.prefix}_甲完成`, false)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '首条并行分支完成不得提前穿透汇聚').toEqual([['parallelReviewB']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'parallelSummary'`
    )).toEqual([['0']])

    await approverWorkbench.claimTask(assets.modelName, '并行乙审批')
    await approverWorkbench.approveTask(assets.modelName, '并行乙审批', `${assets.prefix}_乙完成`, false)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '全部并行分支完成后必须只创建一份汇总任务').toEqual([['parallelSummary']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'parallelSummary'`
    )).toEqual([['1']])

    await approverWorkbench.claimTask(assets.modelName, '并行汇总审批')
    await approverWorkbench.approveTask(assets.modelName, '并行汇总审批', `${assets.prefix}_汇总完成`, true)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    await testInfo.attach('routing-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId,
        splitTargets: ['parallelReviewA', 'parallelReviewB'],
        joinTarget: 'parallelSummary',
        joinTaskCount: 1
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-ROUTE-004] 条件字段缺失时安全失败且任务历史变量零副作用', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  const assets = routingAssets('UIROUTE004', '缺失条件字段')
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
    await configuration.createConditionRoutingForm({
      name: assets.formName,
      remark: `${assets.prefix} 缺失条件字段安全失败`,
      amountRequired: false
    })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 缺失条件字段安全失败`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.deleteElement('end')
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '缺失字段预审', 'intake')
    await processDesigner.appendExclusiveGatewayAfter('intake', 'missingFieldRoute', '缺失字段分流')

    const amountTask = await processDesigner.appendUserTaskAfter('missingFieldRoute')
    await processDesigner.configureCandidateRoleForElement(amountTask, '流程审批人', '金额条件审批', 'amountReview')
    const amountEnd = await processDesigner.appendEndEventAfter('amountReview')
    await processDesigner.configureElementIdentity(amountEnd, 'amountEnd', '金额结束')

    const defaultTask = await processDesigner.appendUserTaskAfter('missingFieldRoute')
    await processDesigner.configureCandidateRoleForElement(defaultTask, '流程审批人', '缺失字段默认审批', 'defaultReview')
    const defaultEnd = await processDesigner.appendEndEventAfter('defaultReview')
    await processDesigner.configureElementIdentity(defaultEnd, 'defaultEnd', '默认结束')

    const amountFlow = await processDesigner.findSequenceFlowId('missingFieldRoute', 'amountReview')
    const defaultFlow = await processDesigner.findSequenceFlowId('missingFieldRoute', 'defaultReview')
    await processDesigner.configureConditionRuleBranch({
      flowId: amountFlow,
      branchName: '金额达到五千',
      rules: [{ fieldLabel: '申请金额（amount）', operatorLabel: '大于等于', value: 5000 }]
    })
    await processDesigner.configureDefaultConditionBranch(defaultFlow, '缺失字段默认')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const processInstanceId = await startRoutingProcess(starter.page, assets.modelName, {
      title: '未填写金额的合法申请'
    })
    assets.processInstanceIds.push(processInstanceId)
    const escapedInstanceId = sqlLiteral(processInstanceId)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    const row = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await row.locator('button').first().click()
    await expect(approver.page).toHaveURL(/\/workflow\/process-detail\//u)

    const beforeFailure = queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'), (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND END_TIME_ IS NOT NULL), (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}')`
    )
    const intakeTaskId = queryReadOnly(
      `SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'intake'`
    )[0]?.[0]
    expect(intakeTaskId, '缺失字段场景必须保留唯一预审任务').toBeTruthy()
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ = 'amount'`
    ), '发起人未填写金额时不得伪造 amount 流程变量').toEqual([['0']])

    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const dialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await dialog.getByLabel('办理意见').fill(`${assets.prefix}_缺失字段回滚`)
    const responsePromise = approver.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    const response = await responsePromise
    expect(response.status()).toBe(200)
    const payload = await response.json()
    expect(payload.code).toBe(409)
    expect(String(payload.msg || '')).toContain('条件字段在当前流程实例中不存在: amount')
    await expect(dialog).toBeVisible()
    await expect(dialog).toContainText('条件字段在当前流程实例中不存在: amount')
    expect(queryReadOnly(
      `SELECT ID_, TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '缺失条件字段必须回滚并保留同一预审任务').toEqual([[intakeTaskId, 'intake']])
    expect(queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'), (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND END_TIME_ IS NOT NULL), (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}')`
    ), '缺失条件字段不得产生任务、历史或变量副作用').toEqual(beforeFailure)
    await testInfo.attach('routing-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId,
        code: payload.code,
        message: payload.msg,
        intakeTaskId,
        beforeFailure
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-ROUTE-005] 事件网关由消息优先消费并取消定时竞争分支', async ({ browser, request }, testInfo) => {
  test.setTimeout(210_000)
  const assets = routingAssets('UIROUTE005', '事件网关')
  assets.messageName = `${assets.prefix}_message`
  assets.credentialName = `${assets.prefix}_集成账号`
  assets.runtimeRequestId = randomUUID()
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let admin
  let integration
  let credential = null
  let credentialRevoked = false
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createTextForm({ name: assets.formName, remark: `${assets.prefix} 事件网关真实 UI` })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 消息与定时竞争`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.deleteElement('end')
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '事件预审', 'intake')
    await processDesigner.appendExclusiveGatewayAfter('intake', 'eventGateway', '事件竞争')
    await processDesigner.replaceGatewayType('eventGateway', 'event-based')

    const appendedMessage = await processDesigner.appendIntermediateCatchEventAfter('eventGateway', 'message')
    await processDesigner.configureElementIdentity(appendedMessage, 'messageWait', '等待外部消息')
    await processDesigner.configureEventReference('messageWait', assets.messageName)
    const messageEnd = await processDesigner.appendEndEventAfter('messageWait')
    await processDesigner.configureElementIdentity(messageEnd, 'messageEnd', '消息结束')

    const appendedTimer = await processDesigner.appendIntermediateCatchEventAfter('eventGateway', 'timer')
    await processDesigner.configureElementIdentity(appendedTimer, 'timerWait', '等待十分钟')
    await processDesigner.configureTimerEvent('timerWait', '持续时间', 'PT10M')
    const timerEnd = await processDesigner.appendEndEventAfter('timerWait')
    await processDesigner.configureElementIdentity(timerEnd, 'timerEnd', '定时结束')

    const authorXml = await processDesigner.readDesignerXml()
    expect(authorXml).toContain('eventBasedGateway')
    expect(authorXml).toContain('messageEventDefinition')
    expect(authorXml).toContain(`id="Message_${assets.messageName}"`)
    expect(authorXml).toContain(`name="${assets.messageName}"`)
    expect(authorXml).toContain('timeDuration')
    expect(authorXml).toContain('PT10M')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    integration = new WorkflowIntegrationPage(admin.page)
    credential = await integration.createCredential({
      name: assets.credentialName,
      scopes: ['MESSAGE'],
      allowedVariables: [],
      rateLimitPerMinute: 60
    })
    assets.credentialId = credential.credentialId

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_事件申请`)
    assets.processInstanceIds.push(processInstanceId)
    const escapedInstanceId = sqlLiteral(processInstanceId)
    const escapedMessageName = sqlLiteral(assets.messageName)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approveCurrentTask(approverWorkbench, assets.modelName, `${assets.prefix}_事件预审`, false)
    expect(queryReadOnly(
      `SELECT EVENT_TYPE_, EVENT_NAME_, ACTIVITY_ID_ FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '事件网关必须创建唯一消息等待订阅').toEqual([['message', assets.messageName, 'messageWait']])
    expect(queryReadOnly(
      `SELECT ELEMENT_ID_ FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_ = '${escapedInstanceId}'`
    ), '事件网关必须同时创建定时竞争作业').toEqual([['timerWait']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['0']])

    const protocolPayload = {
      requestId: assets.runtimeRequestId,
      eventName: assets.messageName,
      processInstanceId,
      businessKey: null,
      variables: {}
    }
    const protocolUrl = new URL('/dev-api/workflow/runtime-event/message', testInfo.project.use.baseURL).toString()
    const firstResponse = await request.post(protocolUrl, {
      headers: { 'X-Integration-Token': credential.token },
      data: protocolPayload
    })
    expect(firstResponse.status()).toBe(200)
    const firstPayload = await firstResponse.json()
    expect(firstPayload.code).toBe(200)
    expect(firstPayload.data?.status).toBe('PROCESSED')
    expect(firstPayload.data?.resultCode).toBe('EVENT_PROCESSED')
    expect(firstPayload.data?.matchedProcessInstanceId).toBe(processInstanceId)

    const replayResponse = await request.post(protocolUrl, {
      headers: { 'X-Integration-Token': credential.token },
      data: protocolPayload
    })
    expect(replayResponse.status()).toBe(200)
    const replayPayload = await replayResponse.json()
    expect(replayPayload.code).toBe(200)
    expect(replayPayload.data?.matchedExecutionId).toBe(firstPayload.data?.matchedExecutionId)
    credential.token = ''

    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '消息竞争分支完成后流程实例必须结束').toEqual([['1']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '消息消费后不得保留竞争订阅').toEqual([['0']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_ = '${escapedInstanceId}'`
    ), '消息分支获胜后必须取消定时竞争作业').toEqual([['0']])
    expect(queryReadOnly(
      `SELECT ACT_ID_, END_TIME_ IS NOT NULL FROM ACT_HI_ACTINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND ACT_ID_ IN ('messageEnd','timerEnd') ORDER BY ACT_ID_`
    ), '事件网关只能完成消息结束分支').toEqual([['messageEnd', '1']])
    expect(queryReadOnly(
      `SELECT EVENT_TYPE, EVENT_NAME, CORRELATION_VALUE, STATUS, RESULT_CODE, MATCHED_PROCESS_INSTANCE_ID FROM wf_runtime_event_request WHERE REQUEST_ID = '${sqlLiteral(assets.runtimeRequestId)}'`
    ), '同一 requestId 重放只能保留一条成功审计').toEqual([
      ['MESSAGE', assets.messageName, processInstanceId, 'PROCESSED', 'EVENT_PROCESSED', processInstanceId]
    ])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM wf_runtime_event_request WHERE REQUEST_ID = '${sqlLiteral(assets.runtimeRequestId)}' AND EVENT_NAME = '${escapedMessageName}'`
    )).toEqual([['1']])
    await integration.expectRuntimeEventAudit({
      requestId: assets.runtimeRequestId,
      eventName: assets.messageName,
      status: '已处理',
      resultCode: 'EVENT_PROCESSED'
    })
    await integration.revokeCredential(assets.credentialName)
    credentialRevoked = true
    expect(queryReadOnly(
      `SELECT REVOKED_AT IS NOT NULL FROM wf_integration_credential WHERE CREDENTIAL_ID = ${Number(credential.credentialId)}`
    )).toEqual([['1']])
    await testInfo.attach('routing-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId,
        messageName: assets.messageName,
        runtimeRequestId: assets.runtimeRequestId,
        resultCode: firstPayload.data?.resultCode,
        matchedExecutionId: firstPayload.data?.matchedExecutionId,
        replayReturnedOriginalResult: replayPayload.data?.matchedExecutionId === firstPayload.data?.matchedExecutionId,
        credentialId: credential.credentialId,
        credentialRevoked
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    let credentialCleanupError = null
    if (integration && credential?.credentialId && !credentialRevoked) {
      try {
        // 失败路径仍需通过正式 UI 吊销一次性凭据，避免测试异常遗留有效外部访问能力。
        await integration.revokeCredential(assets.credentialName)
      } catch (error) {
        credentialCleanupError = error
      } finally {
        credential.token = ''
      }
    }
    await Promise.allSettled([
      approver?.close(failed), starter?.close(failed), admin?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
    expect(credentialCleanupError, '失败路径必须通过正式 UI 吊销一次性集成账号').toBeNull()
  }
})

test('@full [UI-ROUTE-007] 正式运行事件写入错误字段类型时条件路由安全回滚', async ({ browser, request }, testInfo) => {
  test.setTimeout(240_000)
  const assets = routingAssets('UIROUTE007', '条件类型异常')
  assets.receiveTaskId = 'receiveAmount'
  assets.credentialName = `${assets.prefix}_集成账号`
  assets.runtimeRequestId = randomUUID()
  assets.credentialId = ''
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let admin
  let integration
  let credential = null
  let credentialRevoked = false
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createConditionRoutingForm({
      name: assets.formName,
      remark: `${assets.prefix} 条件字段运行类型异常`,
      amountRequired: false
    })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} ReceiveTask 条件类型安全失败`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.deleteElement('review')
    const directFlowId = await processDesigner.findSequenceFlowId('start', 'end')
    await processDesigner.deleteSequenceFlow(directFlowId)

    // ReceiveTask、网关和两条分支均由真实 Palette、上下文菜单和连接工具完成，不注入 BPMN XML。
    await processDesigner.createAdvancedElement({
      paletteLabel: '接收任务',
      sourceElementId: 'start',
      stableElementId: assets.receiveTaskId,
      elementName: '等待金额事件',
      offsetX: 220,
      offsetY: -80,
      expectedLocalName: 'receiveTask'
    })
    await processDesigner.connectShapes('start', assets.receiveTaskId)
    await processDesigner.appendExclusiveGatewayAfter(assets.receiveTaskId, 'typedRoute', '金额类型分流')

    const amountTask = await processDesigner.appendUserTaskAfter('typedRoute')
    await processDesigner.configureCandidateRoleForElement(amountTask, '流程审批人', '金额条件审批', 'amountReview')
    const amountEnd = await processDesigner.appendEndEventAfter('amountReview')
    await processDesigner.configureElementIdentity(amountEnd, 'amountEnd', '金额结束')

    const defaultTask = await processDesigner.appendUserTaskAfter('typedRoute')
    await processDesigner.configureCandidateRoleForElement(defaultTask, '流程审批人', '类型默认审批', 'defaultReview')
    const defaultEnd = await processDesigner.appendEndEventAfter('defaultReview')
    await processDesigner.configureElementIdentity(defaultEnd, 'defaultEnd', '默认结束')

    const amountFlow = await processDesigner.findSequenceFlowId('typedRoute', 'amountReview')
    const defaultFlow = await processDesigner.findSequenceFlowId('typedRoute', 'defaultReview')
    await processDesigner.configureConditionRuleBranch({
      flowId: amountFlow,
      branchName: '金额达到五千',
      rules: [{ fieldLabel: '申请金额（amount）', operatorLabel: '大于等于', value: 5000 }]
    })
    await processDesigner.configureDefaultConditionBranch(defaultFlow, '类型默认')
    const authorXml = await processDesigner.readDesignerXml()
    expect(authorXml).toContain(`<receiveTask id="${assets.receiveTaskId}"`)
    expect(authorXml).toContain('approva.conditionRule.config')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    integration = new WorkflowIntegrationPage(admin.page)
    credential = await integration.createCredential({
      name: assets.credentialName,
      scopes: ['RECEIVE'],
      allowedVariables: ['amount'],
      rateLimitPerMinute: 60
    })
    assets.credentialId = credential.credentialId

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceIds.push(await startRoutingProcess(starter.page, assets.modelName, {
      title: '金额由正式运行事件提供'
    }))
    const processInstanceId = assets.processInstanceIds[0]
    const escapedInstanceId = sqlLiteral(processInstanceId)
    expect(queryReadOnly(
      `SELECT ACT_ID_ FROM ACT_RU_ACTINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND END_TIME_ IS NULL AND ACT_TYPE_ NOT IN ('process')`
    ), '发起后必须唯一停在 ReceiveTask').toEqual([[assets.receiveTaskId]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ = 'amount'`
    ), '用户未填写可选金额时不得提前持久化 amount').toEqual([['0']])

    const beforeFailure = queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_RU_ACTINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND ACT_ID_ = '${assets.receiveTaskId}' AND END_TIME_ IS NULL), (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'), (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND END_TIME_ IS NOT NULL), (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}')`
    )
    const protocolPayload = {
      requestId: assets.runtimeRequestId,
      eventName: assets.receiveTaskId,
      processInstanceId,
      businessKey: null,
      // 字符串是正式协议允许的标量，但与部署时冻结的 NUMBER 条件字段类型不兼容。
      variables: { amount: '8000' }
    }
    const protocolUrl = new URL('/dev-api/workflow/runtime-event/receive', testInfo.project.use.baseURL).toString()
    const response = await request.post(protocolUrl, {
      headers: { 'X-Integration-Token': credential.token },
      data: protocolPayload
    })
    expect(response.status()).toBe(200)
    const payload = await response.json()
    expect(payload.code).toBe(409)
    expect(String(payload.msg || '')).toContain('条件字段运行值类型不合法: amount')

    expect(queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_RU_ACTINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND ACT_ID_ = '${assets.receiveTaskId}' AND END_TIME_ IS NULL), (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'), (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND END_TIME_ IS NOT NULL), (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}')`
    ), '类型异常必须回滚 ReceiveTask、后继任务、历史和变量写入').toEqual(beforeFailure)
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ = 'amount'`
    ), '字符串型 amount 不得在失败事务中持久化').toEqual([['0']])
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '类型异常后实例必须保持活动等待').toEqual([['1']])
    expect(queryReadOnly(
      `SELECT EVENT_TYPE, EVENT_NAME, CORRELATION_VALUE, STATUS, RESULT_CODE, RESULT_SUMMARY, COALESCE(MATCHED_PROCESS_INSTANCE_ID,''), COALESCE(MATCHED_EXECUTION_ID,'') FROM wf_runtime_event_request WHERE REQUEST_ID = '${sqlLiteral(assets.runtimeRequestId)}'`
    ), '主事务回滚后必须形成不含匹配执行主键的独立失败审计').toEqual([[
      'RECEIVE', assets.receiveTaskId, processInstanceId, 'FAILED', 'RUNTIME_EVENT_REJECTED',
      '条件字段运行值类型不合法: amount', '', ''
    ]])
    await integration.expectRuntimeEventAudit({
      requestId: assets.runtimeRequestId,
      eventName: assets.receiveTaskId,
      status: '失败',
      resultCode: 'RUNTIME_EVENT_REJECTED'
    })
    await integration.revokeCredential(assets.credentialName)
    credentialRevoked = true
    credential.token = ''
    expect(queryReadOnly(
      `SELECT REVOKED_AT IS NOT NULL FROM wf_integration_credential WHERE CREDENTIAL_ID = ${Number(credential.credentialId)}`
    )).toEqual([['1']])
    await testInfo.attach('routing-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId,
        runtimeRequestId: assets.runtimeRequestId,
        response: { code: payload.code, message: payload.msg },
        submittedVariableType: 'string',
        beforeFailure,
        credentialId: credential.credentialId,
        credentialRevoked
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    let credentialCleanupError = null
    if (integration && credential?.credentialId && !credentialRevoked) {
      try {
        // 失败路径仍必须经真实 UI 吊销凭据，不能因测试断言失败遗留有效外部访问能力。
        await integration.revokeCredential(assets.credentialName)
      } catch (error) {
        credentialCleanupError = error
      } finally {
        credential.token = ''
      }
    }
    await Promise.allSettled([starter?.close(failed), admin?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
    expect(credentialCleanupError, '失败路径必须通过正式 UI 吊销一次性集成账号').toBeNull()
  }
})
