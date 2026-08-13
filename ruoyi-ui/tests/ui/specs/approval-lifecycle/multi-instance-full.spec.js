import { expect, test } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openAccountSession, openRoleSession } from '../../support/role-session.js'

const MEMBERS = Object.freeze([
  { roleKey: 'workflow_approver', username: 'e2e_ui_wf_approver', displayName: 'UI流程审批人' },
  { roleKey: 'workflow_admin', username: 'e2e_ui_wf_admin', displayName: 'UI流程管理员' }
])

const GENERAL_MANAGER_ACCOUNT = Object.freeze({
  roleKey: 'workflow_approver', username: 'general_manager', password: 'wang',
  requiredRoles: ['workflow_approver']
})

/**
 * 生成受控多实例正式 UI 用例的唯一测试资产。
 * @param {'ALL'|'ANY'} mode 会签或或签模式。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,processInstanceId:string}} 本轮资产登记。
 */
function multiInstanceAssets(mode) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_UIMI${mode}_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`, modelName: `${prefix}_${mode === 'ALL' ? '会签' : '或签'}`,
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
 * 通过真实设计器、发起页和两个办理人会话执行指定受控多实例模式。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前证据上下文。
 * @param {'ALL'|'ANY'} mode 会签或或签模式。
 * @returns {Promise<void>} 运行任务、完成语义和 Flowable 历史一致性全部核验后结束。
 */
async function runControlledMultiInstance(browser, testInfo, mode) {
  const assets = multiInstanceAssets(mode)
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let admin
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 真实受控多实例`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureControlledMultiInstanceForUsers({
      elementId: 'review', taskName: mode === 'ALL' ? '指定用户会签' : '指定用户或签',
      mode, users: MEMBERS
    })
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请`)
    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    const userRows = queryReadOnly(
      "SELECT user_name, user_id FROM sys_user WHERE user_name IN ('e2e_ui_wf_admin','e2e_ui_wf_approver') AND status = '0' AND del_flag = '0' ORDER BY user_name"
    )
    const userIds = Object.fromEntries(userRows)
    expect(Object.keys(userIds)).toHaveLength(2)
    const runtimeRows = queryReadOnly(
      `SELECT COALESCE(ASSIGNEE_, ''), TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' ORDER BY ASSIGNEE_`
    )
    expect(runtimeRows, '指定用户受控多实例必须原子创建两份活动任务').toEqual([
      [userIds.e2e_ui_wf_admin, 'review'], [userIds.e2e_ui_wf_approver, 'review']
    ].sort((left, right) => left[0].localeCompare(right[0])))

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    await new WorkflowWorkbenchPage(approver.page)
      .approveProcess(assets.modelName, `${assets.prefix}_首名成员通过`, mode === 'ANY')

    if (mode === 'ALL') {
      expect(queryReadOnly(
        `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
      )).toEqual([['1']])
      expect(queryReadOnly(
        `SELECT END_TIME_ IS NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
      )).toEqual([['1']])
      await new WorkflowWorkbenchPage(admin.page)
        .approveProcess(assets.modelName, `${assets.prefix}_末名成员通过`, true)
    }

    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['0']])
    const historyRows = queryReadOnly(
      `SELECT COALESCE(DELETE_REASON_, '__NATURAL__') FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'review' ORDER BY ID_`
    )
    expect(historyRows, '受控多实例必须保留两份成员历史').toHaveLength(2)
    if (mode === 'ALL') {
      expect(historyRows, 'ALL 会签的两名成员都必须自然完成').toEqual([['__NATURAL__'], ['__NATURAL__']])
    } else {
      expect(historyRows.filter(row => row[0] === '__NATURAL__'), 'ANY 或签只能有一名成员自然完成').toHaveLength(1)
      expect(historyRows.filter(row => row[0] !== '__NATURAL__'), 'ANY 或签必须原子取消剩余 sibling').toHaveLength(1)
    }
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

/**
 * 从发起页的正式审批资格目录选择一个多实例成员。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @param {import('@playwright/test').Locator} assignmentItem 发起时成员表单项。
 * @param {{username:string,displayName:string}} member 待选职责账号和显示名称。
 * @returns {Promise<void>} 目录检索结果写入多选控件并稳定回显后结束。
 */
async function selectStartMember(page, assignmentItem, member) {
  const select = assignmentItem.locator('.el-select')
  await select.locator('.el-select__wrapper').click()
  await select.getByRole('combobox').fill(member.username)
  const option = page.locator('.el-select-dropdown:visible').getByRole('option')
    .filter({ hasText: member.displayName }).first()
  await expect(option, `发起审批资格目录必须返回 ${member.displayName}`).toBeVisible()
  await option.click()
  await expect(assignmentItem).toContainText(member.displayName)
}

/**
 * 在普通任务完成弹窗中从正式审批资格目录选择后继动态多实例成员。
 * @param {import('@playwright/test').Page} page 当前普通任务办理人页面。
 * @param {import('@playwright/test').Locator} dialog 通过任务弹窗。
 * @param {'会签办理人'|'或签办理人'} fieldLabel 后继多实例模式对应的字段标签。
 * @param {{username:string,displayName:string}} member 待选职责账号和显示名称。
 * @returns {Promise<void>} 后继成员完成远程检索和选择器回显后结束。
 */
async function selectNextMember(page, dialog, fieldLabel, member) {
  const memberItem = dialog.locator('.el-form-item').filter({ hasText: fieldLabel })
  await expect(memberItem, `${fieldLabel}字段必须唯一`).toHaveCount(1)
  const select = memberItem.locator('.el-select')
  await select.locator('.el-select__wrapper').click()
  await select.getByRole('combobox').fill(member.username)
  const option = page.locator('.el-select-dropdown:visible').getByRole('option')
    .filter({ hasText: member.displayName }).first()
  await expect(option, `下一办理人目录必须返回 ${member.displayName}`).toBeVisible()
  await option.click()
  await expect(memberItem).toContainText(member.displayName)
  await page.keyboard.press('Escape')
}

/**
 * 在动态多实例详情页通过正式审批资格目录增加一名成员。
 * @param {import('@playwright/test').Page} page 当前动态多实例办理人页面。
 * @param {{username:string,displayName:string}} member 待增加职责账号和显示名称。
 * @param {string} comment 加签业务原因。
 * @returns {Promise<void>} 调整事务成功且成员表格刷新后结束。
 */
async function addMultiInstanceMember(page, member, comment) {
  await page.getByRole('button', { name: '加签', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: '增加会签成员' })
  const memberItem = dialog.locator('.el-form-item').filter({ hasText: '新增成员' })
  const select = memberItem.locator('.el-select')
  await select.locator('.el-select__wrapper').click()
  await select.getByRole('combobox').fill(member.username)
  const option = page.locator('.el-select-dropdown:visible').getByRole('option')
    .filter({ hasText: member.displayName }).first()
  await expect(option, `加签目录必须返回 ${member.displayName}`).toBeVisible()
  await option.click()
  await page.keyboard.press('Escape')
  await dialog.getByPlaceholder('请输入加签原因').fill(comment)
  const adjustPromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/task/multiInstance/adjust', 'POST'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await adjustPromise, '/workflow/task/multiInstance/adjust')
  await expect(dialog).toBeHidden()
}

/**
 * 从动态成员表格通过正式减签入口移除指定成员。
 * @param {import('@playwright/test').Page} page 当前动态多实例办理人页面。
 * @param {string} displayName 待移除成员显示名称。
 * @param {string} comment 减签业务原因。
 * @returns {Promise<void>} 调整事务成功且目标活动任务退出成员表后结束。
 */
async function removeMultiInstanceMember(page, displayName, comment) {
  const memberTable = page.locator('.workflow-detail__multi-instance')
  const memberRow = memberTable.locator('.el-table__body tbody tr').filter({ hasText: displayName })
  await expect(memberRow, `成员表必须唯一展示 ${displayName}`).toHaveCount(1)
  await memberRow.getByRole('button', { name: `移除 ${displayName}`, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '移除会签成员' })
  await dialog.getByPlaceholder('请输入减签原因').fill(comment)
  const adjustPromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/task/multiInstance/adjust', 'POST'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await adjustPromise, '/workflow/task/multiInstance/adjust')
  await expect(dialog).toBeHidden()
}

/**
 * 通过真实设计器验证指定角色或部门在节点进入时实时展开审批成员，并执行 ANY 原子完成。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前证据上下文。
 * @param {{source:'指定角色'|'指定部门',targetName:string,actor:'workflow_approver'|'general_manager',expectedMembersSql:string}} scenario 身份来源、目录目标、首名办理账号和期望成员只读 SQL。
 * @returns {Promise<void>} UI 建模、部署、发起、办理和 Flowable 成员历史全部核验后结束。
 */
async function runConfiguredIdentityAny(browser, testInfo, scenario) {
  const assets = multiInstanceAssets('ANY')
  assets.modelName = `${assets.prefix}_${scenario.source}`
  assets.modelKey = `${assets.prefix}_${scenario.source === '指定角色' ? 'role' : 'dept'}_model`
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let actor
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} ${scenario.source}实时展开`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureControlledMultiInstance({
      elementId: 'review', taskName: `${scenario.source}或签`, mode: 'ANY', memberSource: scenario.source,
      identities: [{ keyword: scenario.targetName, displayName: scenario.targetName }]
    })
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请`)
    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    const expectedMembers = queryReadOnly(scenario.expectedMembersSql).map(row => row[0]).sort()
    expect(expectedMembers.length, `${scenario.targetName}必须实时展开至少两名审批用户`).toBeGreaterThan(1)
    const runtimeMembers = queryReadOnly(
      `SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'review' ORDER BY ASSIGNEE_`
    ).map(row => row[0]).sort()
    expect(runtimeMembers, `${scenario.source}必须按当前正式 RBAC 完整展开`).toEqual(expectedMembers)

    actor = scenario.actor === 'workflow_approver'
      ? await openRoleSession(browser, 'workflow_approver', testInfo)
      : await openAccountSession(browser, GENERAL_MANAGER_ACCOUNT, testInfo, 'workflow_approver_sample')
    await new WorkflowWorkbenchPage(actor.page)
      .approveProcess(assets.modelName, `${assets.prefix}_${scenario.source}首人完成`, true)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    const historyRows = queryReadOnly(
      `SELECT COALESCE(DELETE_REASON_, '__NATURAL__') FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'review' ORDER BY ID_`
    )
    expect(historyRows).toHaveLength(expectedMembers.length)
    expect(historyRows.filter(row => row[0] === '__NATURAL__')).toHaveLength(1)
    expect(historyRows.filter(row => row[0] !== '__NATURAL__')).toHaveLength(expectedMembers.length - 1)
    failed = false
  } finally {
    await Promise.allSettled([actor?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

/**
 * 创建并运行到办理时选择的动态 ANY 节点，供多标签并发场景复用真实作者和用户路径。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前证据上下文。
 * @returns {Promise<{assets:object,designer:object,starter:object,approver:object,approverWorkbench:WorkflowWorkbenchPage}>} 已进入 revision 0 动态任务详情的会话和资产。
 */
async function openDynamicMultiInstanceScenario(browser, testInfo) {
  const assets = multiInstanceAssets('ANY')
  assets.modelName = `${assets.prefix}_多标签动态或签`
  assets.modelKey = `${assets.prefix}_tabs_model`
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 多标签 revision 冲突`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '初审选择并发成员')
    await processDesigner.deleteElement('end')
    const generatedId = await processDesigner.appendUserTaskAfter('review')
    await processDesigner.configureControlledMultiInstance({
      elementId: generatedId, stableElementId: 'dynamicReview',
      taskName: '多标签动态或签', mode: 'ANY', memberSource: '办理时选择'
    })
    await processDesigner.appendEndEventAfter('dynamicReview')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请`)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    const firstRow = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await firstRow.locator('button').first().click()
    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const firstDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await firstDialog.getByLabel('办理意见').fill(`${assets.prefix}_选择审批人为并发成员`)
    await selectNextMember(approver.page, firstDialog, '或签办理人', MEMBERS[0])
    const completePromise = approver.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await firstDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await completePromise, '/workflow/task/complete')
    const dynamicRow = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await dynamicRow.locator('button').first().click()
    const panel = approver.page.locator('.workflow-detail__multi-instance')
    await expect(panel.getByText('版本 0', { exact: true })).toBeVisible()
    await expect(panel.getByText('活动 1 人，已完成 0 人', { exact: true })).toBeVisible()
    return { assets, designer, starter, approver, approverWorkbench }
  } catch (error) {
    await Promise.allSettled([approver?.close(true), starter?.close(true), designer.close(true)])
    throw error
  }
}

/**
 * 在指定页面打开加签弹窗并通过正式审批资格目录选择成员，但保留弹窗等待调用方提交。
 * @param {import('@playwright/test').Page} page 动态多实例任务详情页。
 * @param {{username:string,displayName:string}} member 待加签职责账号和显示名称。
 * @param {string} comment 加签业务原因。
 * @returns {Promise<import('@playwright/test').Locator>} 已完成输入但尚未提交的增加成员弹窗。
 */
async function prepareAddMultiInstanceMember(page, member, comment) {
  await page.getByRole('button', { name: '加签', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: '增加会签成员' })
  const memberItem = dialog.locator('.el-form-item').filter({ hasText: '新增成员' })
  const select = memberItem.locator('.el-select')
  await select.locator('.el-select__wrapper').click()
  await select.getByRole('combobox').fill(member.username)
  const option = page.locator('.el-select-dropdown:visible').getByRole('option')
    .filter({ hasText: member.displayName }).first()
  await expect(option, `并发加签目录必须返回 ${member.displayName}`).toBeVisible()
  await option.click()
  await page.keyboard.press('Escape')
  await dialog.getByPlaceholder('请输入加签原因').fill(comment)
  return dialog
}

test('@full [UI-MI-003] 发起人从正式目录选择 ANY 或签成员并由首人完成', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  const assets = multiInstanceAssets('ANY')
  assets.modelName = `${assets.prefix}_发起时或签`
  assets.modelKey = `${assets.prefix}_start_model`
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 发起时选择或签成员`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureControlledMultiInstance({
      elementId: 'review', taskName: '发起时或签', mode: 'ANY', memberSource: '发起时选择'
    })
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const starterWorkbench = new WorkflowWorkbenchPage(starter.page)
    const createRow = await starterWorkbench.filterRow('/office/create', '请输入流程名称', assets.modelName)
    await createRow.locator('button').first().click()
    await expect(starter.page).toHaveURL(/\/workflow\/process-start\//u)
    await starter.page.locator('.workflow-form-renderer input:not([type="file"])').first()
      .fill(`${assets.prefix}_申请`)
    const assignmentItem = starter.page.locator('.process-start-page__assignments .el-form-item')
      .filter({ hasText: '发起时或签（或签）' })
    await expect(assignmentItem, '发起页必须展示唯一或签成员字段').toHaveCount(1)
    for (const member of MEMBERS) await selectStartMember(starter.page, assignmentItem, member)
    await starter.page.keyboard.press('Escape')
    const submitPromise = starter.page.waitForResponse(response => (
      response.request().method() === 'POST'
      && /\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/iu.test(new URL(response.url()).pathname)
    ))
    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    const response = await submitPromise
    expect(response.status()).toBe(200)
    const payload = await response.json()
    expect(payload.code, '发起时成员选择必须随草稿事务提交成功').toBe(200)
    assets.processInstanceId = String(payload.data?.processInstanceId || payload.data?.id || '')
    expect(assets.processInstanceId).not.toBe('')

    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'review'`
    )).toEqual([['2']])
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await new WorkflowWorkbenchPage(approver.page)
      .approveProcess(assets.modelName, `${assets.prefix}_首人完成发起时或签`, true)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    )).toEqual([['1']])
    const historyRows = queryReadOnly(
      `SELECT COALESCE(DELETE_REASON_, '__NATURAL__') FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'review' ORDER BY ID_`
    )
    expect(historyRows.filter(row => row[0] === '__NATURAL__')).toHaveLength(1)
    expect(historyRows.filter(row => row[0] !== '__NATURAL__')).toHaveLength(1)
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-MI-004] 办理时选择或签成员并通过UI完成加签减签和revision流转', async ({ browser }, testInfo) => {
  test.setTimeout(210_000)
  const assets = multiInstanceAssets('ANY')
  assets.modelName = `${assets.prefix}_办理时动态或签`
  assets.modelKey = `${assets.prefix}_dynamic_model`
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 办理时动态或签`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureCandidateRoleForElement('review', '流程审批人', '初审选择或签成员')
    await processDesigner.deleteElement('end')
    const generatedId = await processDesigner.appendUserTaskAfter('review')
    await processDesigner.configureControlledMultiInstance({
      elementId: generatedId, stableElementId: 'dynamicReview',
      taskName: '办理时动态或签', mode: 'ANY', memberSource: '办理时选择'
    })
    await processDesigner.appendEndEventAfter('dynamicReview')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_申请`)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    const firstRow = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await firstRow.locator('button').first().click()
    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const firstDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await firstDialog.getByLabel('办理意见').fill(`${assets.prefix}_选择动态成员`)
    await selectNextMember(approver.page, firstDialog, '或签办理人', MEMBERS[0])
    const completeFirstPromise = approver.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await firstDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await completeFirstPromise, '/workflow/task/complete')
    // 完成接口不会替换详情 URL 中已经失效的 taskId，必须按真实用户路径从待办列表进入后继任务。
    const dynamicRow = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await expect(dynamicRow.getByText('办理时动态或签', { exact: true })).toBeVisible()
    await dynamicRow.locator('button').first().click()
    await expect(approver.page).toHaveURL(/\/workflow\/process-detail\//u)
    await expect(approver.page.getByText('当前任务').locator('..')).toContainText('办理时动态或签')
    const multiInstancePanel = approver.page.locator('.workflow-detail__multi-instance')
    await expect(multiInstancePanel.getByText('版本 0', { exact: true })).toBeVisible()
    await expect(multiInstancePanel.getByText('活动 1 人，已完成 0 人', { exact: true })).toBeVisible()

    await addMultiInstanceMember(approver.page, MEMBERS[1], `${assets.prefix}_加签管理员`)
    await expect(multiInstancePanel.getByText('版本 1', { exact: true })).toBeVisible()
    await expect(multiInstancePanel.getByText('活动 2 人，已完成 0 人', { exact: true })).toBeVisible()
    await removeMultiInstanceMember(approver.page, MEMBERS[1].displayName, `${assets.prefix}_减签管理员`)
    await expect(multiInstancePanel.getByText('版本 2', { exact: true })).toBeVisible()
    // 减签记录为删除原因而非自然完成，成员统计中的“已完成”必须保持为 0。
    await expect(multiInstancePanel.getByText('活动 1 人，已完成 0 人', { exact: true })).toBeVisible()
    await addMultiInstanceMember(approver.page, MEMBERS[1], `${assets.prefix}_重新加签管理员`)
    await expect(multiInstancePanel.getByText('版本 3', { exact: true })).toBeVisible()
    await expect(multiInstancePanel.getByText('活动 2 人，已完成 0 人', { exact: true })).toBeVisible()

    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const finalDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await finalDialog.getByLabel('办理意见').fill(`${assets.prefix}_首名成员完成动态或签`)
    const completeDynamicPromise = approver.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await finalDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await completeDynamicPromise, '/workflow/task/complete')
    await expect(approver.page.getByText('已完成', { exact: true }).first()).toBeVisible()

    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    const historyRows = queryReadOnly(
      `SELECT COALESCE(DELETE_REASON_, '__NATURAL__') FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'dynamicReview' ORDER BY START_TIME_, ID_`
    )
    expect(historyRows, '减签、重新加签和 ANY 完成必须保留三份成员历史').toHaveLength(3)
    expect(historyRows.filter(row => row[0] === '__NATURAL__'), '当前办理人必须自然完成').toHaveLength(1)
    expect(historyRows.filter(row => row[0] !== '__NATURAL__'), '减签任务和剩余 sibling 必须记录删除原因').toHaveLength(2)
    const auditRows = queryReadOnly(
      `SELECT COALESCE(CONVERT(FULL_MSG_ USING gbk), MESSAGE_) FROM ACT_HI_COMMENT WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND (MESSAGE_ LIKE '%Revision%' OR FULL_MSG_ LIKE '%Revision%') ORDER BY TIME_`
    )
    expect(auditRows.some(row => row[0].includes('"afterRevision":4')), '最终完成审计必须把 revision 推进到 4').toBe(true)
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-MI-005] 指定角色按实时RBAC展开全部成员并由ANY首人完成', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  await runConfiguredIdentityAny(browser, testInfo, {
    source: '指定角色', targetName: '流程审批人', actor: 'workflow_approver',
    expectedMembersSql: "SELECT DISTINCT u.user_id FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.user_id JOIN sys_role r ON r.role_id = ur.role_id WHERE r.role_name = '流程审批人' AND r.status = '0' AND u.status = '0' AND u.del_flag = '0' ORDER BY u.user_id"
  })
})

test('@full [UI-MI-006] 指定部门按实时RBAC展开全部成员并由ANY首人完成', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  await runConfiguredIdentityAny(browser, testInfo, {
    source: '指定部门', targetName: '总经办', actor: 'general_manager',
    expectedMembersSql: "SELECT DISTINCT u.user_id FROM sys_user u JOIN sys_dept d ON d.dept_id = u.dept_id WHERE d.dept_name = '总经办' AND d.status = '0' AND u.status = '0' AND u.del_flag = '0' AND EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.role_id = ur.role_id WHERE ur.user_id = u.user_id AND r.status = '0' AND r.role_key = 'workflow_approver') ORDER BY u.user_id"
  })
})

test('@full [UI-MI-007] 多标签陈旧revision加签必须409并保持零副作用', async ({ browser }, testInfo) => {
  test.setTimeout(210_000)
  const scenario = await openDynamicMultiInstanceScenario(browser, testInfo)
  const { assets, designer, starter, approver } = scenario
  let secondPage
  let failed = true
  try {
    const firstPage = approver.page
    secondPage = await firstPage.context().newPage()
    await secondPage.goto(firstPage.url())
    const secondPanel = secondPage.locator('.workflow-detail__multi-instance')
    await expect(secondPanel.getByText('版本 0', { exact: true })).toBeVisible()
    const staleDialog = await prepareAddMultiInstanceMember(
      secondPage, MEMBERS[1], `${assets.prefix}_陈旧标签加签`)

    await addMultiInstanceMember(firstPage, MEMBERS[1], `${assets.prefix}_标签A加签`)
    const firstPanel = firstPage.locator('.workflow-detail__multi-instance')
    await expect(firstPanel.getByText('版本 1', { exact: true })).toBeVisible()
    await expect(firstPanel.getByText('活动 2 人，已完成 0 人', { exact: true })).toBeVisible()

    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    const stateSql = `SELECT
      COALESCE((SELECT LONG_ FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ = '_wfMiRevision_dynamicReview'), -1),
      (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'dynamicReview'),
      (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'dynamicReview'),
      (SELECT COUNT(*) FROM ACT_HI_COMMENT WHERE PROC_INST_ID_ = '${escapedInstanceId}')`
    const stateBeforeConflict = queryReadOnly(stateSql)
    const assigneesBeforeConflict = queryReadOnly(
      `SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'dynamicReview' ORDER BY ASSIGNEE_`
    )
    const staleResponsePromise = secondPage.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/multiInstance/adjust', 'POST'))
    await staleDialog.getByRole('button', { name: '确认', exact: true }).click()
    const staleResponse = await staleResponsePromise
    // 平台业务异常保持若依 AjaxResult 协议：HTTP 200 承载业务码 409 和稳定子码。
    expect(staleResponse.status(), '陈旧标签请求必须由正式业务接口正常返回').toBe(200)
    const stalePayload = await staleResponse.json()
    expect(stalePayload.code).toBe(409)
    expect(stalePayload.subCode).toBe('WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT')
    await expect(secondPage.getByText('会签成员状态已变化，已为你刷新最新结果', { exact: true })).toBeVisible()
    await expect(secondPanel.getByText('版本 1', { exact: true })).toBeVisible()
    expect(queryReadOnly(stateSql), 'revision 冲突不得改变版本、任务、历史或审计计数')
      .toEqual(stateBeforeConflict)
    expect(queryReadOnly(
      `SELECT ASSIGNEE_ FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND TASK_DEF_KEY_ = 'dynamicReview' ORDER BY ASSIGNEE_`
    ), 'revision 冲突不得新增、删除或改写活动办理人').toEqual(assigneesBeforeConflict)

    await firstPage.getByRole('button', { name: '通过', exact: true }).click()
    const completeDialog = firstPage.getByRole('dialog', { name: '通过任务' })
    await completeDialog.getByLabel('办理意见').fill(`${assets.prefix}_并发验证后完成`)
    const completePromise = firstPage.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await completeDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await completePromise, '/workflow/task/complete')
    await expect(firstPage.getByText('已完成', { exact: true }).first()).toBeVisible()
    failed = false
  } finally {
    await secondPage?.close().catch(() => {})
    await Promise.allSettled([approver.close(failed), starter.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-MI-001] 指定用户 ALL 会签必须由全部真实成员完成', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  await runControlledMultiInstance(browser, testInfo, 'ALL')
})

test('@full [UI-MI-002] 指定用户 ANY 或签由首名成员完成并原子取消 sibling', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  await runControlledMultiInstance(browser, testInfo, 'ANY')
})
