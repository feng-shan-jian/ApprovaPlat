import { test, expect } from '@playwright/test'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成生命周期用例的唯一测试资产。
 * @param {string} caseId 当前可追踪用例编号。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,processInstanceId:string}} 流程资产登记。
 */
function lifecycleAssets(caseId) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_${caseId.replaceAll('-', '')}_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`, modelName: `${prefix}_生命周期`,
    modelKey: `${prefix}_model`, processInstanceId: ''
  }
}

/**
 * 通过真实 UI 创建并部署一个候选角色单级审批模型。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {ReturnType<typeof lifecycleAssets>} assets 当前用例资产。
 * @returns {Promise<void>} 模型经画布配置、校验、保存和部署后结束。
 */
async function createCandidateModel(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
  await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
  await configuration.createModel({
    name: assets.modelName, key: assets.modelKey,
    categoryName: assets.categoryName, formName: assets.formName,
    description: `${assets.prefix} 生命周期真实UI`
  })
  await configuration.openDesigner(assets.modelKey)
  const designerPage = new WorkflowDesignerPage(page)
  await designerPage.configureCandidateRole('流程审批人', '生命周期审批')
  await designerPage.validateAndSave()
  await designerPage.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 通过真实画布创建并部署两个候选角色节点组成的串行审批模型。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {ReturnType<typeof lifecycleAssets>} assets 当前用例资产。
 * @returns {Promise<void>} 二级模型校验、保存和部署完成后结束。
 */
async function createTwoLevelCandidateModel(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
  await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
  await configuration.createModel({
    name: assets.modelName, key: assets.modelKey,
    categoryName: assets.categoryName, formName: assets.formName,
    description: `${assets.prefix} 二级生命周期真实UI`
  })
  await configuration.openDesigner(assets.modelKey)
  const designerPage = new WorkflowDesignerPage(page)
  await designerPage.configureCandidateRoleForElement('review', '流程审批人', '第1级审批')
  await designerPage.deleteElement('end')
  const generatedTaskId = await designerPage.appendUserTaskAfter('review')
  await designerPage.configureCandidateRoleForElement(generatedTaskId, '流程审批人', '第2级审批', 'review2')
  await designerPage.appendEndEventAfter('review2')
  await designerPage.validateAndSave()
  await designerPage.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 打开五角色中的指定会话并在 finally 中统一关闭。
 * @param {import('@playwright/test').Browser} browser Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试证据上下文。
 * @param {string} caseId 用例编号。
 * @param {(context:{assets:ReturnType<typeof lifecycleAssets>,designer:any,starter:any,approver:any,admin:any})=>Promise<void>} scenario 生命周期场景实现。
 * @param {(page:import('@playwright/test').Page,assets:ReturnType<typeof lifecycleAssets>)=>Promise<void>} modelFactory 真实 UI 模型创建函数。
 * @returns {Promise<void>} 场景执行和证据登记完成后结束。
 */
async function runLifecycleScenario(browser, testInfo, caseId, scenario, modelFactory = createCandidateModel) {
  const assets = lifecycleAssets(caseId)
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let admin
  let failed = true
  try {
    await modelFactory(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请内容`)
    await scenario({ assets, designer, starter, approver, admin })
    failed = false
  } finally {
    await Promise.allSettled([
      admin?.close(failed), approver?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

test('@full [UI-LIFECYCLE-001] 候选任务取消认领后可重新认领并完成', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-001', async ({ assets, approver }) => {
    const workbench = new WorkflowWorkbenchPage(approver.page)
    await workbench.claimProcess(assets.modelName)
    await workbench.unclaimProcess(assets.modelName)
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    const unclaimedRows = queryReadOnly(
      `SELECT ID_, COALESCE(ASSIGNEE_, '') FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )
    expect(unclaimedRows, '取消认领后活动任务必须继续存在').toHaveLength(1)
    expect(unclaimedRows[0][1], '取消认领后 assignee 必须清空').toBe('')
    await workbench.claimProcess(assets.modelName)
    await workbench.approveProcess(assets.modelName, `${assets.prefix}_重新认领后通过`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
  })
})

test('@full [UI-LIFECYCLE-002] 当前办理人从详情页驳回整实例', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-002', async ({ assets, approver }) => {
    const workbench = new WorkflowWorkbenchPage(approver.page)
    await workbench.claimProcess(assets.modelName)
    await workbench.rejectProcess(assets.modelName, `${assets.prefix}_材料不符合要求`)
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL, COALESCE(DELETE_REASON_, '') <> '' FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1', '1']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['0']])
  })
})

test('@full [UI-LIFECYCLE-003] 发起人从我的流程取消运行实例', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-003', async ({ assets, starter }) => {
    await new WorkflowWorkbenchPage(starter.page)
      .cancelOwnedProcess(assets.modelName, `${assets.prefix}_发起人取消`)
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL, COALESCE(DELETE_REASON_, '') <> '' FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1', '1']])
  })
})

test('@full [UI-LIFECYCLE-004] 管理员挂起恢复终止并删除流程历史', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-004', async ({ assets, admin }) => {
    const workbench = new WorkflowWorkbenchPage(admin.page)
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    await workbench.toggleManagedProcessState(assets.modelName, '已挂起')
    expect(queryReadOnly(
      `SELECT DISTINCT SUSPENSION_STATE_ FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['2']])
    await workbench.toggleManagedProcessState(assets.modelName, '运行中')
    expect(queryReadOnly(
      `SELECT DISTINCT SUSPENSION_STATE_ FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    await workbench.terminateManagedProcess(assets.modelName, `${assets.prefix}_管理员终止`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    await workbench.deleteManagedHistory(assets.modelName)
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['0']])
  })
})

test('@full [UI-LIFECYCLE-005] 当前办理人委派后由受托人办结并返回原办理人', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-005', async ({ assets, approver, admin }) => {
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.assignCurrentTaskToUser(
      assets.modelName, 'delegate', 'e2e_ui_wf_admin', 'UI流程管理员', `${assets.prefix}_请管理员协助核验`
    )
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    const userRows = queryReadOnly(
      "SELECT user_name, user_id FROM sys_user WHERE user_name IN ('e2e_ui_wf_admin','e2e_ui_wf_approver') ORDER BY user_name"
    )
    const userIds = Object.fromEntries(userRows)
    expect(queryReadOnly(
      `SELECT COALESCE(ASSIGNEE_, ''), COALESCE(OWNER_, ''), COALESCE(DELEGATION_, '') FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([[userIds.e2e_ui_wf_admin, userIds.e2e_ui_wf_approver, 'PENDING']])

    await new WorkflowWorkbenchPage(admin.page)
      .resolveDelegatedProcess(assets.modelName, `${assets.prefix}_委派事项已核验`)
    expect(queryReadOnly(
      `SELECT COALESCE(ASSIGNEE_, ''), COALESCE(OWNER_, ''), COALESCE(DELEGATION_, '') FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([[userIds.e2e_ui_wf_approver, userIds.e2e_ui_wf_approver, 'RESOLVED']])
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_委派返回后通过`)
  })
})

test('@full [UI-LIFECYCLE-006] 当前办理人转办后由新办理人完成任务', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-006', async ({ assets, approver, admin }) => {
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.assignCurrentTaskToUser(
      assets.modelName, 'transfer', 'e2e_ui_wf_admin', 'UI流程管理员', `${assets.prefix}_转管理员办理`
    )
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    const adminRows = queryReadOnly("SELECT user_id FROM sys_user WHERE user_name = 'e2e_ui_wf_admin'")
    expect(adminRows).toHaveLength(1)
    expect(queryReadOnly(
      `SELECT COALESCE(ASSIGNEE_, ''), COALESCE(OWNER_, ''), COALESCE(DELEGATION_, '') FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([[adminRows[0][0], '', '']])
    await new WorkflowWorkbenchPage(admin.page)
      .approveProcess(assets.modelName, `${assets.prefix}_转办后通过`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
  })
})

test('@full [UI-LIFECYCLE-007] 二级审批退回发起人修改后重新提交并完成', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-007', async ({ assets, starter, approver }) => {
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_一级通过`, false)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.returnProcess(assets.modelName, `${assets.prefix}_退回修改`)

    await new WorkflowWorkbenchPage(starter.page)
      .resubmitOwnedProcess(assets.modelName, `${assets.prefix}_修改后内容`)
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['review']])
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_重提一级通过`, false)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_重提二级通过`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
  }, createTwoLevelCandidateModel)
})

test('@full [UI-LIFECYCLE-008] 一级已办在后继未处理时撤回并重新流转', async ({ browser }, testInfo) => {
  await runLifecycleScenario(browser, testInfo, 'UI-LIFECYCLE-008', async ({ assets, approver }) => {
    const workbench = new WorkflowWorkbenchPage(approver.page)
    await workbench.claimProcess(assets.modelName)
    await workbench.approveProcess(assets.modelName, `${assets.prefix}_一级通过准备撤回`, false)
    await workbench.revokeFinishedProcess(assets.modelName, `${assets.prefix}_撤回一级`)
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['review']])
    await workbench.claimProcess(assets.modelName)
    await workbench.approveProcess(assets.modelName, `${assets.prefix}_撤回后一级通过`, false)
    await workbench.claimProcess(assets.modelName)
    await workbench.approveProcess(assets.modelName, `${assets.prefix}_撤回后二级通过`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
  }, createTwoLevelCandidateModel)
})
