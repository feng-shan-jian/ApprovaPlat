import { expect, test } from '@playwright/test'
import { expectAjaxSuccess } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成表单数据用例的唯一正式资产。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,processInstanceId:string}} 测试资产登记。
 */
function validationAssets() {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_UIFORM001_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_数据表单`, modelName: `${prefix}_表单校验`,
    modelKey: `${prefix}_model`, processInstanceId: ''
  }
}

/**
 * 转义测试生成的只读 SQL 字符串。
 * @param {string} value 测试主键或业务值。
 * @returns {string} MySQL 字符串字面量正文。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 通过 Element Plus 日期面板真实选择指定日期。
 * @param {import('@playwright/test').Page} page 当前发起人页面。
 * @param {import('@playwright/test').Locator} input 日期组件的可见输入框。
 * @param {{year:number,month:number,day:number,value:string}} target 目标年月日和预期提交值。
 * @returns {Promise<void>} 日期组件值更新且弹层关闭后结束。
 */
async function selectCalendarDate(page, input, target) {
  await input.click()
  await expect(input, '日期输入框必须真实展开日历面板').toHaveAttribute('aria-expanded', 'true')
  const panelId = await input.getAttribute('aria-controls')
  expect(panelId, '日期输入框必须关联唯一日历面板').toBeTruthy()
  const panel = page.locator(`[id="${panelId}"]`)
  await expect(panel, '日期输入框关联的日历面板必须可见').toBeVisible()

  // 最多允许跨越十年逐月导航，防止控件状态异常时测试进入无界循环。
  for (let attempt = 0; attempt < 120; attempt += 1) {
    const headerText = (await panel.locator('.el-date-picker__header-label').allTextContents()).join(' ')
    const year = Number(headerText.match(/(\d{4})\s*年/u)?.[1])
    const month = Number(headerText.match(/(\d{1,2})\s*月/u)?.[1])
    expect(Number.isInteger(year) && Number.isInteger(month), `日期面板年月标题无法识别: ${headerText}`).toBeTruthy()
    const monthOffset = (target.year - year) * 12 + target.month - month
    if (monthOffset === 0) break
    const navigation = monthOffset > 0 ? panel.locator('button.arrow-right') : panel.locator('button.arrow-left')
    await expect(navigation, '日期面板必须提供逐月导航按钮').toBeVisible()
    await navigation.click()
  }

  const finalHeaderText = (await panel.locator('.el-date-picker__header-label').allTextContents()).join(' ')
  expect(finalHeaderText, '日期面板必须导航到目标年月').toMatch(
    new RegExp(`${target.year}\\s*年.*${target.month}\\s*月`, 'u')
  )
  const targetDay = panel
    .locator('td:not(.prev-month):not(.next-month):not(.disabled) .el-date-table-cell__text')
    .filter({ hasText: new RegExp(`^${target.day}$`, 'u') })
  await expect(targetDay, '目标日期必须在当前月份中唯一可选').toHaveCount(1)
  await targetDay.click()
  await expect(input, '选择日期后组件必须提交格式化值').toHaveValue(target.value)
  await expect(input, '选择日期后日历面板必须关闭').toHaveAttribute('aria-expanded', 'false')
  await expect(panel, '日期选择完成后关联面板必须隐藏').toBeHidden()
}

test('@full [UI-FORM-001] 表单必填、金额、日期和枚举通过真实UI校验并持久化', async ({ browser }, testInfo) => {
  test.setTimeout(120_000)
  const assets = validationAssets()
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
    await configuration.createValidationForm({ name: assets.formName, remark: `${assets.prefix} 真实表单数据校验` })
    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 表单数据校验`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureCandidateRole('流程审批人', '表单校验审批')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const workbench = new WorkflowWorkbenchPage(starter.page)
    const row = await workbench.filterRow('/office/create', '请输入流程名称', assets.modelName)
    await row.locator('button').first().click()
    await expect(starter.page).toHaveURL(/\/workflow\/process-start\//u)
    const form = starter.page.locator('.workflow-form-renderer')
    await expect(form).toBeVisible()
    const title = form.getByPlaceholder('请输入申请主题')
    const amount = form.locator('.el-form-item').filter({ hasText: '申请金额' }).getByRole('spinbutton')
    const date = form.getByPlaceholder('请选择申请日期')
    const typeField = form.locator('.el-form-item').filter({ hasText: '申请类型' })
    const description = form.getByPlaceholder('请输入申请说明')

    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    for (const message of ['申请主题不能为空', '申请金额不能为空', '申请日期不能为空', '申请类型不能为空']) {
      await expect(form.getByText(message, { exact: true }), `空提交必须提示 ${message}`).toBeVisible()
    }
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE NAME_ = '${sqlLiteral(assets.modelName)}'`
    )).toEqual([['0']])

    await title.fill('真实UI表单数据持久化')
    await amount.fill('1234.56')
    await amount.press('Tab')
    expect(await amount.inputValue(), '金额控件必须保留合法数值输入').toBe('1234.56')
    await selectCalendarDate(starter.page, date, { year: 2026, month: 12, day: 31, value: '2026-12-31' })
    await typeField.locator('.el-select__wrapper').click()
    await starter.page.getByRole('option', { name: '紧急申请', exact: true }).click()
    await description.fill('真实UI表单数据持久化')

    const submitPromise = starter.page.waitForResponse(response => (
      response.request().method() === 'POST'
      && /\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/iu.test(new URL(response.url()).pathname)
    ))
    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    const submitted = await expectAjaxSuccess(await submitPromise, '/workflow/process/draft/{id}/submit')
    assets.processInstanceId = String(submitted.data?.processInstanceId || submitted.data?.id || '')
    expect(assets.processInstanceId).not.toBe('')
    await expect(starter.page).toHaveURL(new RegExp(`/workflow/process-detail/${assets.processInstanceId}(?:[/?]|$)`, 'u'))

    const variableRows = queryReadOnly(
      `SELECT NAME_, COALESCE(TEXT_, CAST(DOUBLE_ AS CHAR), CAST(LONG_ AS CHAR), '') FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${sqlLiteral(assets.processInstanceId)}' AND NAME_ IN ('requestTitle','amount','requestDate','requestType','description') ORDER BY NAME_`
    )
    expect(Object.fromEntries(variableRows)).toEqual({
      amount: '1234.56',
      description: '真实UI表单数据持久化',
      requestDate: '2026-12-31',
      requestTitle: '真实UI表单数据持久化',
      requestType: 'URGENT'
    })

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_表单校验通过`)
    failed = false
  } finally {
    await Promise.allSettled([approver?.close(failed), starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-FORM-002] 表单设计器计数器显示最小值、最大值和步长配置', async ({ browser }, testInfo) => {
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let failed = true
  try {
    await designer.page.goto('/workflow/form')
    await designer.page.getByRole('button', { name: '新增', exact: true }).click()
    await expect(designer.page).toHaveURL(/\/workflow\/form-design/u)
    await designer.page.locator('.components-item').filter({ hasText: '计数器' }).first().click()
    await expect(designer.page.locator('.drawing-board .drawing-item')).toHaveCount(1)
    const panel = designer.page.locator('.right-board')
    const expectedControls = ['最小值', '最大值', '步长', '精度']
    const evidence = {}
    for (const label of expectedControls) {
      evidence[label] = await panel.locator('.el-form-item').filter({ hasText: label }).count()
    }
    await testInfo.attach('number-constraint-controls.json', {
      body: Buffer.from(JSON.stringify(evidence, null, 2)), contentType: 'application/json'
    })
    expect(evidence.精度, '计数器精度配置必须对真实设计者可见').toBe(1)
    expect({ 最小值: evidence.最小值, 最大值: evidence.最大值, 步长: evidence.步长 },
      '计数器边界和步长配置必须对真实设计者可见').toEqual({ 最小值: 1, 最大值: 1, 步长: 1 })
    failed = false
  } finally {
    await designer.close(failed)
  }
})

test('@full [UI-FORM-003] 文本最大长度按数值保存并允许模型校验部署', async ({ browser }, testInfo) => {
  const assets = validationAssets()
  assets.formName = `${assets.prefix}_长度表单`
  assets.modelName = `${assets.prefix}_长度校验`
  assets.modelKey = `${assets.prefix}_maxlength_model`
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix })
    await configuration.createMaxlengthForm({
      name: assets.formName, remark: `${assets.prefix} 最大长度序列化`, maxlength: 20
    })
    const rows = queryReadOnly(
      `SELECT JSON_TYPE(JSON_EXTRACT(content, '$.fields[0].maxlength')), JSON_UNQUOTE(JSON_EXTRACT(content, '$.fields[0].maxlength')) FROM wf_form WHERE form_name = '${sqlLiteral(assets.formName)}' AND del_flag = '0'`
    )
    await testInfo.attach('maxlength-storage.json', {
      body: Buffer.from(JSON.stringify({ expectedType: 'INTEGER', rows }, null, 2)),
      contentType: 'application/json'
    })
    expect(rows, '真实用户输入的最大长度必须以 JSON 数值持久化').toEqual([['INTEGER', '20']])

    await configuration.createModel({
      name: assets.modelName, key: assets.modelKey,
      categoryName: assets.categoryName, formName: assets.formName,
      description: `${assets.prefix} 最大长度部署校验`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(designer.page)
    await processDesigner.configureCandidateRole('流程审批人', '长度校验审批')
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)
    failed = false
  } finally {
    await designer.close(failed)
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
