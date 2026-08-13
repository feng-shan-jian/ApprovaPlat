import { test, expect } from '@playwright/test'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成串行审批用例的唯一 ASCII 资产前缀。
 * @param {number} taskCount 串行审批节点数量。
 * @returns {string} 可用于模型 key 和分类 code 的稳定前缀。
 */
function serialPrefix(taskCount) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-16)
  return `E2E_UI_${runId}_serial${taskCount}_${Date.now().toString(36)}`
}

/**
 * 通过真实 UI 创建、部署、发起并完成指定级数的串行审批。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试证据上下文。
 * @param {number} taskCount 串行用户任务数量，只接受 2 或 3。
 * @returns {Promise<void>} 页面流转和 Flowable 历史顺序全部核验通过后结束。
 */
async function runSerialApproval(browser, testInfo, taskCount) {
  expect([2, 3], '串行审批正式用例只允许二级或三级').toContain(taskCount)
  const prefix = serialPrefix(taskCount)
  const assets = {
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`, modelName: `${prefix}_${taskCount}级审批`,
    modelKey: `${prefix}_model`, processInstanceId: '', taskIds: []
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
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} 真实画布串行审批`
    })
    await configuration.openDesigner(assets.modelKey)

    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRoleForElement('review', '流程审批人', '第1级审批')
    assets.taskIds.push('review')
    await designerPage.deleteElement('end')
    let previousTaskId = 'review'
    for (let taskIndex = 2; taskIndex <= taskCount; taskIndex += 1) {
      // 每一级任务都从上一级上下文菜单追加，并在属性面板绑定真实候选角色。
      const generatedId = await designerPage.appendUserTaskAfter(previousTaskId)
      const stableId = `review${taskIndex}`
      await designerPage.configureCandidateRoleForElement(
        generatedId, '流程审批人', `第${taskIndex}级审批`, stableId
      )
      assets.taskIds.push(stableId)
      previousTaskId = stableId
    }
    await designerPage.appendEndEventAfter(previousTaskId)
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${prefix}_申请内容`)

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const workbench = new WorkflowWorkbenchPage(approver.page)
    for (let taskIndex = 1; taskIndex <= taskCount; taskIndex += 1) {
      // 同一候选角色依次认领当前唯一活动任务，最终节点才要求页面回显流程完成。
      await workbench.claimProcess(assets.modelName)
      await workbench.approveProcess(
        assets.modelName, `${prefix}_第${taskIndex}级通过`, taskIndex === taskCount
      )
    }

    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    const processRows = queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL, COALESCE(DELETE_REASON_, '') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )
    expect(processRows, '串行流程历史实例必须唯一落库').toHaveLength(1)
    expect(processRows[0][0], '串行审批最终必须真实结束').toBe('1')

    const taskRows = queryReadOnly(
      `SELECT TASK_DEF_KEY_, NAME_, END_TIME_ IS NOT NULL, DELETE_REASON_ IS NULL FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' ORDER BY START_TIME_, ID_`
    )
    expect(taskRows.map(row => row[0]), 'Flowable 历史任务必须按画布串行顺序创建').toEqual(assets.taskIds)
    expect(taskRows.every(row => row[2] === '1' && row[3] === '1'), '每一级任务都必须自然完成').toBe(true)
    const commentRows = queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_COMMENT WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )
    expect(Number(commentRows[0][0]), '每一级审批动作必须留下持久化意见或动作审计').toBeGreaterThanOrEqual(taskCount)
    failed = false
  } finally {
    await Promise.allSettled([
      approver?.close(failed),
      starter?.close(failed),
      designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

test('@full [UI-APPROVAL-002] 通过真实BPMN画布完成二级串行审批', async ({ browser }, testInfo) => {
  await runSerialApproval(browser, testInfo, 2)
})

test('@full [UI-APPROVAL-003] 通过真实BPMN画布完成三级串行审批', async ({ browser }, testInfo) => {
  await runSerialApproval(browser, testInfo, 3)
})
