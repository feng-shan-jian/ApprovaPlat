import { test, expect } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { loadSystemAdminAccount } from '../../../e2e/support/environment.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openAccountSession, openRoleSession } from '../../support/role-session.js'

const assignmentScenarios = Object.freeze([
  {
    caseId: 'UI-ASSIGN-001', title: '固定用户', ruleLabel: '固定用户',
    targetFieldLabel: '固定办理人', targetName: 'UI流程审批人', claimRequired: false,
    runtimeMode: 'assignee'
  },
  {
    caseId: 'UI-ASSIGN-002', title: '候选用户', ruleLabel: '候选用户',
    targetFieldLabel: '候选用户', targetName: 'UI流程审批人', claimRequired: true,
    runtimeMode: 'candidateUser'
  },
  {
    caseId: 'UI-ASSIGN-003', title: '候选部门', ruleLabel: '候选角色 / 部门',
    targetFieldLabel: '候选角色或部门', targetName: 'UI自动化测试部', claimRequired: true,
    runtimeMode: 'candidateDepartment'
  },
  {
    caseId: 'UI-ASSIGN-004', title: '发起人直属上级', ruleLabel: '发起人直属上级',
    claimRequired: false, runtimeMode: 'assignee'
  },
  {
    caseId: 'UI-ASSIGN-005', title: '指定部门负责人', ruleLabel: '指定部门负责人',
    targetFieldLabel: '负责人所属部门', targetName: 'UI自动化测试部', claimRequired: false,
    runtimeMode: 'assignee'
  }
])

/**
 * 生成办理人来源用例的唯一 ASCII 资产前缀。
 * @param {string} caseId 可追踪用例编号。
 * @returns {string} 可用于分类编码和模型标识的唯一前缀。
 */
function assignmentPrefix(caseId) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  return `E2E_UI_${runId}_${caseId.replaceAll('-', '')}_${Date.now().toString(36)}`
}

/**
 * 由系统管理员从用户管理页面读取唯一审批账号的正式用户主键。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<string>} 用户管理表格回显的正整数用户主键。
 */
async function readApproverIdThroughUi(browser, testInfo) {
  const admin = await openAccountSession(browser, loadSystemAdminAccount(), testInfo, 'system-admin')
  let failed = true
  try {
    const page = admin.page
    await page.goto('/system/user')
    const usernameInput = page.getByPlaceholder('请输入用户名称')
    await usernameInput.fill('e2e_ui_wf_approver')
    const responsePromise = page.waitForResponse(response => matchesEndpoint(response, '/system/user/list', 'GET'))
    await usernameInput.locator('xpath=ancestor::form[1]').getByRole('button', { name: '搜索', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/system/user/list')
    const row = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: 'e2e_ui_wf_approver' })
    await expect(row, '用户管理必须唯一返回审批账号').toHaveCount(1)
    // 第一列是选择框，第二列是页面显式展示的正式用户编号。
    const userId = String(await row.locator('td').nth(1).innerText()).trim()
    expect(userId, '用户管理必须显示正整数用户编号').toMatch(/^[1-9][0-9]*$/u)
    failed = false
    return userId
  } finally {
    await admin.close(failed)
  }
}

/**
 * 核对任务创建时的真实 assignee 或候选身份链。
 * @param {string} processInstanceId 当前流程实例主键。
 * @param {'assignee'|'candidateUser'|'candidateDepartment'} runtimeMode 预期运行时办理模式。
 * @returns {{taskId:string,approverId:string}} 当前活动任务和审批用户主键。
 */
function verifyRuntimeAssignment(processInstanceId, runtimeMode) {
  const escapedInstanceId = processInstanceId.replaceAll("'", "''")
  const approverRows = queryReadOnly(
    "SELECT user_id FROM sys_user WHERE user_name = 'e2e_ui_wf_approver' AND status = '0' AND del_flag = '0'"
  )
  expect(approverRows, '审批账号必须唯一启用').toHaveLength(1)
  const approverId = approverRows[0][0]
  const taskRows = queryReadOnly(
    `SELECT ID_, COALESCE(ASSIGNEE_, '') FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
  )
  expect(taskRows, '单级审批必须创建唯一活动任务').toHaveLength(1)
  const [taskId, assignee] = taskRows[0]
  if (runtimeMode === 'assignee') {
    expect(assignee, '固定或管理关系规则必须直接分配给审批人').toBe(approverId)
  } else {
    expect(assignee, '候选任务在认领前不得预置办理人').toBe('')
    const identityRows = queryReadOnly(
      `SELECT COALESCE(USER_ID_, ''), COALESCE(GROUP_ID_, '') FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_ = '${taskId.replaceAll("'", "''")}' AND TYPE_ = 'candidate'`
    )
    if (runtimeMode === 'candidateUser') {
      expect(identityRows.some(row => row[0] === approverId), '候选用户身份链必须包含审批账号').toBe(true)
    } else {
      const deptRows = queryReadOnly("SELECT dept_id FROM sys_dept WHERE dept_name = 'UI自动化测试部' AND status = '0'")
      expect(deptRows, '测试部门必须唯一启用').toHaveLength(1)
      expect(identityRows.some(row => row[1] === `DEPT${deptRows[0][0]}`), '候选部门身份链必须使用正式 DEPT 组').toBe(true)
    }
  }
  return { taskId, approverId }
}

/**
 * 通过真实 UI 完成一个参与者规则从建模到运行结束的业务闭环。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @param {typeof assignmentScenarios[number]} scenario 办理人来源配置。
 * @returns {Promise<void>} 页面、运行表和历史表全部核验通过后结束。
 */
async function runAssignmentScenario(browser, testInfo, scenario) {
  const prefix = assignmentPrefix(scenario.caseId)
  const assets = {
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`, modelName: `${prefix}_${scenario.title}`,
    modelKey: `${prefix}_model`, processInstanceId: ''
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: prefix })
    await configuration.createTextForm({ name: assets.formName, remark: prefix })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${prefix} ${scenario.title}办理规则`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureTaskParticipantRuleForElement({
      elementId: 'review', taskName: scenario.title, ruleLabel: scenario.ruleLabel,
      targetFieldLabel: scenario.targetFieldLabel, targetName: scenario.targetName
    })
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${prefix}_申请内容`)
    const runtime = verifyRuntimeAssignment(assets.processInstanceId, scenario.runtimeMode)

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const workbench = new WorkflowWorkbenchPage(approver.page)
    if (scenario.claimRequired) await workbench.claimProcess(assets.modelName)
    await workbench.approveProcess(assets.modelName, `${prefix}_${scenario.title}通过`)

    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    const historyRows = queryReadOnly(
      `SELECT COALESCE(ASSIGNEE_, ''), END_TIME_ IS NOT NULL, DELETE_REASON_ IS NULL FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )
    expect(historyRows, '参与者规则必须形成唯一历史任务').toHaveLength(1)
    expect(historyRows[0]).toEqual([runtime.approverId, '1', '1'])
    const processRows = queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )
    expect(processRows).toEqual([['1']])
    failed = false
  } finally {
    await Promise.allSettled([
      approver?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

for (const scenario of assignmentScenarios) {
  test(`@full [${scenario.caseId}] ${scenario.title}办理规则通过真实UI完成建模与运行`, async ({ browser }, testInfo) => {
    await runAssignmentScenario(browser, testInfo, scenario)
  })
}

test('@full [UI-ASSIGN-006] 表单用户字段通过真实下拉选择并直接分配审批任务', async ({ browser }, testInfo) => {
  const prefix = assignmentPrefix('UI-ASSIGN-006')
  const assets = {
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_用户字段表单`, modelName: `${prefix}_表单用户审批`,
    modelKey: `${prefix}_model`, processInstanceId: '', approverId: ''
  }
  assets.approverId = await readApproverIdThroughUi(browser, testInfo)
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: prefix })
    await configuration.createFormUserAssignmentForm({
      name: assets.formName,
      remark: `${prefix} FORM_USER 真实运行链`,
      userId: assets.approverId,
      userLabel: 'UI流程审批人'
    })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} 表单用户字段办理规则`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureFormUserParticipantRule({
      elementId: 'review',
      taskName: '表单用户审批',
      formName: assets.formName,
      formFieldLabel: '审批人（approverId）'
    })
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const starterWorkbench = new WorkflowWorkbenchPage(starter.page)
    const row = await starterWorkbench.filterRow('/office/create', '请输入流程名称', assets.modelName)
    await row.locator('button').first().click()
    await expect(starter.page).toHaveURL(/\/workflow\/process-start\//u)
    const form = starter.page.locator('.workflow-form-renderer')
    await form.getByPlaceholder('请输入申请主题').fill(`${prefix}_申请内容`)
    const approverField = form.locator('.el-form-item').filter({ hasText: '审批人' })
    await approverField.locator('.el-select__wrapper').click()
    await starter.page.getByRole('option', { name: 'UI流程审批人', exact: true }).click()
    const submitPromise = starter.page.waitForResponse(response => (
      response.request().method() === 'POST'
      && /\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/iu.test(new URL(response.url()).pathname)
    ))
    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    const submitted = await expectAjaxSuccess(await submitPromise, '/workflow/process/draft/{id}/submit')
    assets.processInstanceId = String(submitted.data?.processInstanceId || submitted.data?.id || '')
    expect(assets.processInstanceId, 'FORM_USER 提交必须返回流程实例主键').not.toBe('')

    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    const formValueRows = queryReadOnly(
      `SELECT COALESCE(TEXT_, CAST(LONG_ AS CHAR), '') FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ = 'approverId'`
    )
    expect(formValueRows, '审批人下拉值必须持久化为正式用户主键').toEqual([[assets.approverId]])
    const taskRows = queryReadOnly(
      `SELECT ID_, COALESCE(ASSIGNEE_, '') FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )
    expect(taskRows, 'FORM_USER 必须创建唯一活动任务').toHaveLength(1)
    expect(taskRows[0][1], 'FORM_USER 必须直接分配给下拉选择的用户').toBe(assets.approverId)
    const roleRows = queryReadOnly(
      `SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.role_id = ur.role_id WHERE ur.user_id = ${assets.approverId} AND r.role_key = 'workflow_approver' AND r.status = '0'`
    )
    expect(roleRows, '页面选中的用户必须具备实时审批角色').toEqual([['1']])

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await new WorkflowWorkbenchPage(approver.page)
      .approveProcess(assets.modelName, `${prefix}_表单用户审批通过`)
    const historyRows = queryReadOnly(
      `SELECT COALESCE(ASSIGNEE_, ''), END_TIME_ IS NOT NULL, DELETE_REASON_ IS NULL FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )
    expect(historyRows, 'FORM_USER 必须形成唯一正常完成的历史任务')
      .toEqual([[assets.approverId, '1', '1']])
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    failed = false
  } finally {
    await Promise.allSettled([
      approver?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
