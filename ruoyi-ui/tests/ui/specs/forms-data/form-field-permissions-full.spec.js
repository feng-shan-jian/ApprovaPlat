import { expect, test } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成节点字段权限正式 UI 用例的唯一测试资产。
 * @param {string} caseKey 用例稳定键，用于并行运行时隔离资产名称。
 * @param {string} processName 流程业务名称后缀。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,processInstanceId:string}} 本轮资产登记。
 */
function permissionAssets(caseKey = 'UIPERM001', processName = '字段权限审批') {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_${caseKey}_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_字段权限表单`, modelName: `${prefix}_${processName}`,
    modelKey: `${prefix}_model`, processInstanceId: ''
  }
}

/**
 * 转义测试生成的只读 SQL 字符串。
 * @param {string} value 流程实例主键或业务值。
 * @returns {string} 可安全放入只读 SQL 字符串字面量的正文。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 通过新建流程页填写字段权限表单并正式提交。
 * @param {import('@playwright/test').Page} page 发起人真实浏览器页面。
 * @param {string} processName 流程部署显示名称。
 * @param {Record<string,string>} values 占位文本到业务值的映射。
 * @returns {Promise<string>} 后端草稿提交事务返回的流程实例主键。
 */
async function startPermissionProcess(page, processName, values) {
  const workbench = new WorkflowWorkbenchPage(page)
  const row = await workbench.filterRow('/office/create', '请输入流程名称', processName)
  await row.locator('button').first().click()
  await expect(page).toHaveURL(/\/workflow\/process-start\//u)
  const form = page.locator('.workflow-form-renderer')
  for (const [placeholder, value] of Object.entries(values)) {
    await form.getByPlaceholder(placeholder).fill(value)
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

test('@full [UI-PERM-001] 开始和审批节点通过真实设计器执行隐藏、只读、可编辑和必填权限', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  const assets = permissionAssets()
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
    await configuration.createPermissionFieldsForm({
      name: assets.formName,
      remark: `${assets.prefix} 节点字段权限`,
      fields: [
        { fieldName: 'requestTitle', label: '申请主题', placeholder: '请输入申请主题' },
        { fieldName: 'hiddenNote', label: '隐藏说明', placeholder: '请输入隐藏说明' },
        { fieldName: 'readonlyCode', label: '只读编码', placeholder: '请输入只读编码' },
        { fieldName: 'taskEditable', label: '审批补充', placeholder: '请输入审批补充' },
        { fieldName: 'taskRequired', label: '审批结论', placeholder: '请输入审批结论' }
      ]
    })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 真实节点字段权限`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureFormPermissionsForElement({
      elementId: 'start', formName: assets.formName, defaultMode: '可编辑',
      fieldModes: { 申请主题: '必填', 隐藏说明: '隐藏', 只读编码: '只读' }
    })
    await processDesigner.configureCandidateRole('流程审批人', '字段权限审批')
    await processDesigner.configureFormPermissionsForElement({
      elementId: 'review', formName: assets.formName, defaultMode: '可编辑',
      fieldModes: { 申请主题: '只读', 隐藏说明: '隐藏', 只读编码: '隐藏', 审批结论: '必填' }
    })
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const starterWorkbench = new WorkflowWorkbenchPage(starter.page)
    const createRow = await starterWorkbench.filterRow('/office/create', '请输入流程名称', assets.modelName)
    await createRow.locator('button').first().click()
    await expect(starter.page).toHaveURL(/\/workflow\/process-start\//u)
    const startForm = starter.page.locator('.workflow-form-renderer')
    await expect(startForm.getByPlaceholder('请输入隐藏说明')).toHaveCount(0)
    await expect(startForm.getByPlaceholder('请输入只读编码')).toBeDisabled()
    await expect(startForm.getByPlaceholder('请输入申请主题')).toBeEditable()
    await expect(startForm.getByPlaceholder('请输入审批补充')).toBeEditable()
    await expect(startForm.getByPlaceholder('请输入审批结论')).toBeEditable()

    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    await expect(startForm.getByText('申请主题不能为空', { exact: true })).toBeVisible()
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE NAME_ = '${sqlLiteral(assets.modelName)}'`
    )).toEqual([['0']])

    await startForm.getByPlaceholder('请输入申请主题').fill(`${assets.prefix}_真实申请`)
    await startForm.getByPlaceholder('请输入审批补充').fill(`${assets.prefix}_发起补充`)
    const submitPromise = starter.page.waitForResponse(response => (
      response.request().method() === 'POST'
      && /\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/iu.test(new URL(response.url()).pathname)
    ))
    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    const submitted = await expectAjaxSuccess(await submitPromise, '/workflow/process/draft/{id}/submit')
    assets.processInstanceId = String(submitted.data?.processInstanceId || submitted.data?.id || '')
    expect(assets.processInstanceId).not.toBe('')

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    const todoRow = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await todoRow.locator('button').first().click()
    await expect(approver.page).toHaveURL(/\/workflow\/process-detail\//u)
    const taskForm = approver.page.locator('.workflow-form-renderer').first()
    await expect(taskForm.getByPlaceholder('请输入申请主题')).toBeDisabled()
    await expect(taskForm.getByPlaceholder('请输入隐藏说明')).toHaveCount(0)
    await expect(taskForm.getByPlaceholder('请输入只读编码')).toHaveCount(0)
    await expect(taskForm.getByPlaceholder('请输入审批补充')).toBeEditable()
    await expect(taskForm.getByPlaceholder('请输入审批结论')).toBeEditable()
    await expect(taskForm.getByPlaceholder('请输入审批补充')).toHaveValue(`${assets.prefix}_发起补充`)

    await taskForm.getByPlaceholder('请输入审批补充').fill(`${assets.prefix}_审批补充`)
    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    let completeDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await completeDialog.getByLabel('办理意见').fill(`${assets.prefix}_字段权限校验`)
    await completeDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expect(taskForm.getByText('审批结论不能为空', { exact: true })).toBeVisible()
    await expect(completeDialog).toBeVisible()
    await completeDialog.getByRole('button', { name: '取消', exact: true }).click()

    await taskForm.getByPlaceholder('请输入审批结论').fill(`${assets.prefix}_同意`)
    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    completeDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await completeDialog.getByLabel('办理意见').fill(`${assets.prefix}_通过`)
    const completePromise = approver.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/complete', 'POST'))
    await completeDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await completePromise, '/workflow/task/complete')
    await expect(approver.page.getByText('已完成', { exact: true }).first()).toBeVisible()

    const variableRows = queryReadOnly(
      `SELECT NAME_, COALESCE(TEXT_, CAST(DOUBLE_ AS CHAR), CAST(LONG_ AS CHAR), '') FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${sqlLiteral(assets.processInstanceId)}' AND NAME_ IN ('requestTitle','hiddenNote','readonlyCode','taskEditable','taskRequired') ORDER BY NAME_`
    )
    expect(Object.fromEntries(variableRows), '节点写权限必须只持久化用户可写字段').toEqual({
      requestTitle: `${assets.prefix}_真实申请`,
      taskEditable: `${assets.prefix}_审批补充`,
      taskRequired: `${assets.prefix}_同意`
    })
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-PERM-002] 并行任务从旧页面提交不同字段补丁且互不覆盖', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  const assets = permissionAssets('UIPERM002', '并发字段补丁审批')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approverA
  let approverB
  let failed = true
  const requestEvidence = []
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createPermissionFieldsForm({
      name: assets.formName,
      remark: `${assets.prefix} 并行任务字段补丁`,
      fields: [
        { fieldName: 'requestTitle', label: '申请主题', placeholder: '请输入申请主题' },
        { fieldName: 'branchAValue', label: '甲字段', placeholder: '请输入甲字段' },
        { fieldName: 'branchBValue', label: '乙字段', placeholder: '请输入乙字段' }
      ]
    })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 并行旧快照字段补丁`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureFormPermissionsForElement({
      elementId: 'start', formName: assets.formName, defaultMode: '可编辑',
      fieldModes: { 申请主题: '必填' }
    })
    await processDesigner.deleteElement('end')
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '并行预审', 'intake')
    await processDesigner.appendExclusiveGatewayAfter('intake', 'parallelSplit', '并行拆分')
    await processDesigner.replaceGatewayType('parallelSplit', 'parallel')

    const taskA = await processDesigner.appendUserTaskAfter('parallelSplit')
    await processDesigner.configureCandidateRoleForElement(taskA, '流程审批人', '甲字段审批', 'branchAReview')
    await processDesigner.configureFormPermissionsForElement({
      elementId: 'branchAReview', formName: assets.formName, defaultMode: '只读',
      fieldModes: { 甲字段: '可编辑' }
    })
    await processDesigner.appendExclusiveGatewayAfter('branchAReview', 'parallelJoin', '并行汇聚')
    await processDesigner.replaceGatewayType('parallelJoin', 'parallel')

    const taskB = await processDesigner.appendUserTaskAfter('parallelSplit')
    await processDesigner.configureCandidateRoleForElement(taskB, '流程审批人', '乙字段审批', 'branchBReview')
    await processDesigner.configureFormPermissionsForElement({
      elementId: 'branchBReview', formName: assets.formName, defaultMode: '只读',
      fieldModes: { 乙字段: '可编辑' }
    })
    await processDesigner.connectShapes('branchBReview', 'parallelJoin')

    const summaryTask = await processDesigner.appendUserTaskAfter('parallelJoin')
    await processDesigner.configureCandidateRoleForElement(summaryTask, '流程审批人', '字段汇总审批', 'fieldSummary')
    await processDesigner.configureFormPermissionsForElement({
      elementId: 'fieldSummary', formName: assets.formName, defaultMode: '只读', fieldModes: {}
    })
    const end = await processDesigner.appendEndEventAfter('fieldSummary')
    await processDesigner.configureElementIdentity(end, 'parallelEnd', '并行结束')
    const authorXml = await processDesigner.readDesignerXml()
    expect(authorXml.match(/<parallelGateway/gu) || []).toHaveLength(2)
    expect(authorXml).toContain('sourceRef="branchBReview" targetRef="parallelJoin"')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const initialA = `${assets.prefix}_甲初值`
    const initialB = `${assets.prefix}_乙初值`
    assets.processInstanceId = await startPermissionProcess(starter.page, assets.modelName, {
      请输入申请主题: `${assets.prefix}_并行申请`,
      请输入甲字段: initialA,
      请输入乙字段: initialB
    })
    const escapedInstanceId = sqlLiteral(assets.processInstanceId)

    approverA = await openRoleSession(browser, 'workflow_approver', testInfo)
    const workbenchA = new WorkflowWorkbenchPage(approverA.page)
    await workbenchA.claimTask(assets.modelName, '并行预审')
    await workbenchA.approveTask(assets.modelName, '并行预审', `${assets.prefix}_预审通过`, false)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' ORDER BY TASK_DEF_KEY_`
    )).toEqual([['branchAReview'], ['branchBReview']])

    approverB = await openRoleSession(browser, 'workflow_approver', testInfo)
    await workbenchA.claimTask(assets.modelName, '甲字段审批')
    const rowA = await workbenchA.filterTaskRow('/office/todo', assets.modelName, '甲字段审批')
    await rowA.locator('button').first().click()
    const formA = approverA.page.locator('.workflow-form-renderer').first()
    await expect(formA.getByPlaceholder('请输入申请主题')).toBeDisabled()
    await expect(formA.getByPlaceholder('请输入甲字段')).toBeEditable()
    await expect(formA.getByPlaceholder('请输入乙字段')).toBeDisabled()
    await expect(formA.getByPlaceholder('请输入甲字段')).toHaveValue(initialA)
    await expect(formA.getByPlaceholder('请输入乙字段')).toHaveValue(initialB)

    const workbenchB = new WorkflowWorkbenchPage(approverB.page)
    await workbenchB.claimTask(assets.modelName, '乙字段审批')
    const rowB = await workbenchB.filterTaskRow('/office/todo', assets.modelName, '乙字段审批')
    await rowB.locator('button').first().click()
    const formB = approverB.page.locator('.workflow-form-renderer').first()
    await expect(formB.getByPlaceholder('请输入申请主题')).toBeDisabled()
    await expect(formB.getByPlaceholder('请输入甲字段')).toBeDisabled()
    await expect(formB.getByPlaceholder('请输入乙字段')).toBeEditable()
    await expect(formB.getByPlaceholder('请输入甲字段')).toHaveValue(initialA)
    await expect(formB.getByPlaceholder('请输入乙字段')).toHaveValue(initialB)

    const nextA = `${assets.prefix}_甲更新`
    const nextB = `${assets.prefix}_乙更新`
    await formA.getByPlaceholder('请输入甲字段').fill(nextA)
    await formB.getByPlaceholder('请输入乙字段').fill(nextB)

    await approverA.page.getByRole('button', { name: '通过', exact: true }).click()
    const dialogA = approverA.page.getByRole('dialog', { name: '通过任务' })
    await dialogA.getByLabel('办理意见').fill(`${assets.prefix}_甲补丁`)
    const completeAPromise = approverA.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await dialogA.getByRole('button', { name: '确认', exact: true }).click()
    const completeAResponse = await completeAPromise
    await expectAjaxSuccess(completeAResponse, '/workflow/task/complete 甲字段')
    const requestA = completeAResponse.request().postDataJSON()
    requestEvidence.push({ task: 'branchAReview', variables: requestA.variables })
    expect(requestA.variables).toEqual({ branchAValue: nextA })
    expect(queryReadOnly(
      `SELECT NAME_, COALESCE(TEXT_, CAST(DOUBLE_ AS CHAR), CAST(LONG_ AS CHAR), '') FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ IN ('branchAValue','branchBValue') ORDER BY NAME_`
    ), '甲节点完成后乙字段必须保持发起初值').toEqual([
      ['branchAValue', nextA], ['branchBValue', initialB]
    ])

    // 乙页面没有刷新，仍显示甲字段旧值；提交必须只携带乙字段补丁，不能把旧甲值覆盖回流程变量。
    await expect(formB.getByPlaceholder('请输入甲字段')).toHaveValue(initialA)
    await approverB.page.getByRole('button', { name: '通过', exact: true }).click()
    const dialogB = approverB.page.getByRole('dialog', { name: '通过任务' })
    await dialogB.getByLabel('办理意见').fill(`${assets.prefix}_乙补丁`)
    const completeBPromise = approverB.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await dialogB.getByRole('button', { name: '确认', exact: true }).click()
    const completeBResponse = await completeBPromise
    await expectAjaxSuccess(completeBResponse, '/workflow/task/complete 乙字段')
    const requestB = completeBResponse.request().postDataJSON()
    requestEvidence.push({ task: 'branchBReview', variables: requestB.variables, staleBranchAValue: initialA })
    expect(requestB.variables).toEqual({ branchBValue: nextB })
    expect(queryReadOnly(
      `SELECT NAME_, COALESCE(TEXT_, CAST(DOUBLE_ AS CHAR), CAST(LONG_ AS CHAR), '') FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ IN ('branchAValue','branchBValue') ORDER BY NAME_`
    ), '乙节点旧页面完成后两份字段补丁必须同时保留').toEqual([
      ['branchAValue', nextA], ['branchBValue', nextB]
    ])
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['fieldSummary']])

    await workbenchA.claimTask(assets.modelName, '字段汇总审批')
    const summaryRow = await workbenchA.filterTaskRow('/office/todo', assets.modelName, '字段汇总审批')
    await summaryRow.locator('button').first().click()
    const summaryForm = approverA.page.locator('.workflow-form-renderer').first()
    await expect(summaryForm.getByPlaceholder('请输入甲字段')).toBeDisabled()
    await expect(summaryForm.getByPlaceholder('请输入乙字段')).toBeDisabled()
    await expect(summaryForm.getByPlaceholder('请输入甲字段')).toHaveValue(nextA)
    await expect(summaryForm.getByPlaceholder('请输入乙字段')).toHaveValue(nextB)
    await approverA.page.getByRole('button', { name: '通过', exact: true }).click()
    const summaryDialog = approverA.page.getByRole('dialog', { name: '通过任务' })
    await summaryDialog.getByLabel('办理意见').fill(`${assets.prefix}_汇总通过`)
    const summaryPromise = approverA.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await summaryDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await summaryPromise, '/workflow/task/complete 汇总')
    await expect(approverA.page.getByText('已完成', { exact: true }).first()).toBeVisible()

    expect(queryReadOnly(
      `SELECT NAME_, COALESCE(TEXT_, CAST(DOUBLE_ AS CHAR), CAST(LONG_ AS CHAR), '') FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ IN ('branchAValue','branchBValue') ORDER BY NAME_`
    )).toEqual([['branchAValue', nextA], ['branchBValue', nextB]])
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    await testInfo.attach('field-patch-evidence.json', {
      body: Buffer.from(JSON.stringify({ processInstanceId: assets.processInstanceId, requestEvidence }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await Promise.allSettled([
      approverB?.close(failed), approverA?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
