import { test, expect } from '@playwright/test'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 将运行编号规范为模型键和分类编码可接受的 ASCII 片段。
 * @returns {string} 不超过 24 个字符的运行编号。
 */
function normalizedRunId() {
  return String(process.env.FLOWABLE_E2E_RUN_ID || Date.now()).replace(/[^A-Za-z0-9]/gu, '').slice(-24)
}

test('@smoke [UI-APPROVAL-001] 通过真实UI创建、部署、发起、认领并完成一级审批', async ({ browser }, testInfo) => {
  const runId = normalizedRunId()
  const prefix = `E2E_UI_${runId}`
  const assets = {
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_一级审批`,
    modelKey: `${prefix}_single_approval`,
    processInstanceId: ''
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)),
    contentType: 'application/json'
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
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} 真实UI一级审批`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRole('流程审批人', '一级审批')
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const starterWorkbench = new WorkflowWorkbenchPage(starter.page)
    assets.processInstanceId = await starterWorkbench.startProcess(assets.modelName, `${prefix}_申请内容`)

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.approveProcess(assets.modelName, `${prefix}_审批通过`)

    const processRows = queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL, COALESCE(DELETE_REASON_, '') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${assets.processInstanceId.replaceAll("'", "''")}'`
    )
    expect(processRows, 'Flowable 历史实例必须唯一落库').toHaveLength(1)
    expect(processRows[0][0], '流程必须真实结束').toBe('1')
    const commentRows = queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_COMMENT WHERE PROC_INST_ID_ = '${assets.processInstanceId.replaceAll("'", "''")}'`
    )
    expect(Number(commentRows[0][0]), '审批意见和动作审计必须持久化').toBeGreaterThan(0)
    failed = false
  } finally {
    await Promise.allSettled([
      approver?.close(failed),
      starter?.close(failed),
      designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)),
      contentType: 'application/json'
    })
  }
})
