import { test, expect } from './fixtures/workflow.js'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { loadWorkflowAccounts } from './support/environment.js'
import {
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  openWorkflowRoleSession,
  submitWorkflowStartPage
} from './support/workflow-fixture.js'

const accounts = loadWorkflowAccounts()
const baseURL = process.env.FLOWABLE_E2E_BASE_URL?.trim() || 'http://127.0.0.1:1024'
const execFileAsync = promisify(execFile)
const isolatedDatabaseName = 'ry_vue_codex_flowable_it'

/**
 * 把服务端生成的 Flowable 主键限制为可安全嵌入只读验收 SQL 的字符集。
 * @param {unknown} value 流程实例或 BPMN 活动主键。
 * @param {string} fieldName 失败信息使用的业务字段名。
 * @returns {string} 仅含字母、数字、冒号、下划线或连字符的主键。
 */
function requireDatabaseSafeId(value, fieldName) {
  const normalized = String(value || '').trim()
  if (!/^[A-Za-z0-9:_-]{1,64}$/.test(normalized)) {
    throw new Error(`${fieldName} 不满足数据库验收主键边界`)
  }
  return normalized
}

/**
 * 使用隔离库只读账号执行固定结构 SQL，并解析 MySQL 返回的单行 JSON 证据。
 * @param {string} sql 仅由测试固定模板和已校验主键组成的 SELECT 语句。
 * @returns {Promise<Record<string, unknown>>} Flowable 正式表中的计数和状态投影。
 */
async function queryWorkflowDatabaseEvidence(sql) {
  const database = String(process.env.FLOWABLE_E2E_MYSQL_DATABASE || '').trim()
  const username = String(process.env.FLOWABLE_E2E_MYSQL_USERNAME || '').trim()
  const password = String(process.env.FLOWABLE_E2E_MYSQL_PASSWORD || '')
  if (database !== isolatedDatabaseName) throw new Error('E2E 数据库必须是指定隔离 schema')
  if (!/^[A-Za-z0-9_]{1,32}$/.test(username) || !password) {
    throw new Error('缺少隔离 MySQL 只读验收凭据')
  }
  const command = String(process.env.FLOWABLE_E2E_MYSQL_COMMAND || 'mysql').trim()
  if (!command) throw new Error('MySQL 客户端命令不能为空')
  const result = await execFileAsync(command, [
    '--host=127.0.0.1',
    `--user=${username}`,
    `--database=${database}`,
    '--default-character-set=utf8mb4',
    '--batch',
    '--skip-column-names',
    `--execute=${sql}`
  ], {
    env: { ...process.env, MYSQL_PWD: password },
    windowsHide: true,
    maxBuffer: 1024 * 1024
  })
  const output = String(result.stdout || '').trim()
  if (!output || output.includes('\n')) throw new Error('MySQL 验收结果必须是单行 JSON')
  try {
    return JSON.parse(output)
  } catch {
    throw new Error('MySQL 验收结果不是合法 JSON')
  }
}

/**
 * 从 Flowable 运行表读取当前多实例成员快照、revision、模式、任务和 execution 数量。
 * @param {string} processInstanceId 正在运行的真实流程实例主键。
 * @param {string} activityId 受控多实例 BPMN 活动主键。
 * @returns {Promise<Record<string, unknown>>} 当前事务提交后的正式运行态证据。
 */
async function loadRuntimeMultiInstanceDatabaseEvidence(processInstanceId, activityId) {
  const instance = requireDatabaseSafeId(processInstanceId, '流程实例主键')
  const activity = requireDatabaseSafeId(activityId, '多实例活动主键')
  const memberVariable = `_wfMiMembers_${activity}`
  const revisionVariable = `_wfMiRevision_${activity}`
  const modeVariable = `_wfMiMode_${activity}`
  return queryWorkflowDatabaseEvidence(`SELECT JSON_OBJECT(
    'mode', COALESCE((SELECT TEXT_ FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${instance}' AND NAME_ = '${modeVariable}'), ''),
    'revision', COALESCE((SELECT LONG_ FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${instance}' AND NAME_ = '${revisionVariable}'), -1),
    'stateVariableCount', (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${instance}' AND NAME_ IN ('${memberVariable}', '${revisionVariable}', '${modeVariable}')),
    'memberVariableCount', (SELECT COUNT(*) FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_ = '${instance}' AND NAME_ = '${memberVariable}' AND BYTEARRAY_ID_ IS NOT NULL),
    'taskCount', (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${instance}' AND TASK_DEF_KEY_ = '${activity}'),
    'activeExecutionCount', (SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = '${instance}' AND ACT_ID_ = '${activity}' AND IS_ACTIVE_ = 1)
  )`)
}

/**
 * 从 Flowable 历史表读取流程结束后的成员状态、任务删除原因和结构化审批审计数量。
 * @param {string} processInstanceId 已完成的真实流程实例主键。
 * @param {string} activityId 受控多实例 BPMN 活动主键。
 * @returns {Promise<Record<string, unknown>>} 历史变量、任务和 comment 的正式持久化证据。
 */
async function loadHistoricMultiInstanceDatabaseEvidence(processInstanceId, activityId) {
  const instance = requireDatabaseSafeId(processInstanceId, '流程实例主键')
  const activity = requireDatabaseSafeId(activityId, '多实例活动主键')
  const memberVariable = `_wfMiMembers_${activity}`
  const revisionVariable = `_wfMiRevision_${activity}`
  const modeVariable = `_wfMiMode_${activity}`
  return queryWorkflowDatabaseEvidence(`SELECT JSON_OBJECT(
    'mode', COALESCE((SELECT TEXT_ FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${instance}' AND NAME_ = '${modeVariable}'), ''),
    'revision', COALESCE((SELECT LONG_ FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${instance}' AND NAME_ = '${revisionVariable}'), -1),
    'stateVariableCount', (SELECT COUNT(*) FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${instance}' AND NAME_ IN ('${memberVariable}', '${revisionVariable}', '${modeVariable}')),
    'memberVariableCount', (SELECT COUNT(*) FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${instance}' AND NAME_ = '${memberVariable}' AND BYTEARRAY_ID_ IS NOT NULL),
    'runtimeTaskCount', (SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_ = '${instance}'),
    'runtimeExecutionCount', (SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = '${instance}'),
    'historicTaskCount', (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${instance}' AND TASK_DEF_KEY_ = '${activity}'),
    'naturalTaskCount', (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${instance}' AND TASK_DEF_KEY_ = '${activity}' AND DELETE_REASON_ IS NULL),
    'canceledTaskCount', (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE PROC_INST_ID_ = '${instance}' AND TASK_DEF_KEY_ = '${activity}' AND DELETE_REASON_ IS NOT NULL),
    'auditCommentCount', (SELECT COUNT(*) FROM ACT_HI_COMMENT WHERE PROC_INST_ID_ = '${instance}' AND TYPE_ = '1')
  )`)
}

/**
 * 从失败信息中移除五角色凭据和临时 JWT，禁止测试清理异常把认证材料写入报告。
 * @param {unknown} value Playwright、浏览器或 HTTP 客户端抛出的原始错误。
 * @returns {string} 只保留接口、动作和状态信息的脱敏错误文本。
 */
function redactE2ESecrets(value) {
  let text = String(value?.message || value || '')
  Object.values(accounts).forEach(account => {
    text = text.split(account.username).join('<username>')
      .split(account.password).join('<password>')
  })
  return text
    .replace(/Bearer\s+[A-Za-z0-9._-]+/gi, 'Bearer <token>')
    .replace(/Admin-Token=[A-Za-z0-9._-]+/gi, 'Admin-Token=<token>')
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '<token>')
}

/**
 * 通过页面真实登录产生的 Cookie 调用正式后端 API，不创建或注入伪造登录态。
 * @param {import('@playwright/test').Page} page 已登录角色页面。
 * @param {'GET'|'POST'|'PUT'|'DELETE'} method HTTP 方法。
 * @param {string} path `/workflow/**` 业务路径。
 * @param {{query?: Record<string, unknown>, data?: unknown, expectedCode?: number}} options 查询、请求体和期望业务码。
 * @returns {Promise<any>} 已校验传输层和业务码的 JSON 响应。
 */
async function callWorkflowApi(page, method, path, options = {}) {
  const tokenCookie = (await page.context().cookies()).find(cookie => cookie.name === 'Admin-Token')
  if (!tokenCookie?.value) throw new Error('真实登录会话缺少 Admin-Token')
  const url = new URL(`/dev-api${path}`, baseURL)
  Object.entries(options.query || {}).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') url.searchParams.set(key, String(value))
  })
  let response
  try {
    response = await page.request.fetch(url.toString(), {
      method,
      headers: { Authorization: `Bearer ${tokenCookie.value}` },
      data: options.data
    })
  } catch (error) {
    throw new Error(`${method} ${path} 请求失败：${redactE2ESecrets(error)}`)
  }
  expect(response.status(), `${method} ${path} 传输层状态`).toBe(200)
  const payload = await response.json()
  expect(payload.code, `${method} ${path} 业务码`).toBe(options.expectedCode ?? 200)
  return payload
}

/**
 * 从正式身份目录定位预登记账号对应的最小用户选项。
 * @param {import('@playwright/test').Page} page 有身份目录权限的已登录页面。
 * @param {string} username 预登记账号名。
 * @param {boolean} approvalOnly 是否要求服务端按流程办理权限过滤。
 * @returns {Promise<{value: string, label: string, type: string}|null>} 唯一匹配选项，不具备资格时返回 null。
 */
async function findUserOption(page, username, approvalOnly) {
  const payload = await callWorkflowApi(page, 'GET', '/workflow/identity/options', {
    query: {
      type: 'user',
      capability: approvalOnly ? 'approval' : undefined,
      keyword: username,
      pageNum: 1,
      pageSize: 20
    }
  })
  const matches = (payload.rows || []).filter(option =>
    option?.type === 'user' && String(option.label || '').endsWith(`(${username})`))
  expect(matches.length, `身份目录账号 ${username} 必须唯一`).toBeLessThanOrEqual(1)
  return matches[0] || null
}

/**
 * 从当前办理人的真实待办目录回查指定流程节点，避免仅凭实例路由伪造可办理上下文。
 * @param {import('@playwright/test').Page} page 已登录且具备待办查询权限的办理人页面。
 * @param {string} processKey 本次夹具创建的唯一流程定义标识。
 * @param {string} taskDefinitionKey 需要定位的 BPMN 用户任务节点标识。
 * @returns {Promise<{taskId: string, processInstanceId: string, taskDefinitionKey: string}>} 唯一活动待办快照。
 */
async function findAssignedTask(page, processKey, taskDefinitionKey) {
  const payload = await callWorkflowApi(page, 'GET', '/workflow/process/todoList', {
    query: { processKey, pageNum: 1, pageSize: 20 }
  })
  const matches = (payload.rows || []).filter(row =>
    row?.processKey === processKey && row?.taskDefinitionKey === taskDefinitionKey)
  expect(matches, `节点 ${taskDefinitionKey} 必须产生唯一真实待办`).toHaveLength(1)
  expect(String(matches[0].taskId || ''), '待办任务主键不能为空').not.toBe('')
  expect(String(matches[0].processInstanceId || ''), '待办流程实例主键不能为空').not.toBe('')
  return matches[0]
}

/**
 * 创建流程分类并查询服务端生成的正式主键。
 * @param {import('@playwright/test').Page} page 设计者页面。
 * @param {string} name 唯一分类名称。
 * @param {string} code 唯一分类编码。
 * @param {object} resourceRegistry 清理登记簿，POST 成功后立即写入 categoryId。
 * @returns {Promise<string>} 正式分类主键。
 */
async function createCategory(page, name, code, resourceRegistry) {
  const created = await callWorkflowApi(page, 'POST', '/workflow/category', {
    data: { categoryName: name, code, remark: 'P3 动态多实例真实浏览器验收' }
  })
  const categoryId = String(created.data?.categoryId || '')
  expect(categoryId, '分类创建必须返回正式主键').not.toBe('')
  // 正式写入一旦成功立即登记，后续列表回查失败时 finally 仍可回收资源。
  resourceRegistry.categoryId = categoryId
  const result = await callWorkflowApi(page, 'GET', '/workflow/category/list', {
    query: { categoryName: name, code, pageNum: 1, pageSize: 20 }
  })
  const rows = (result.rows || []).filter(row => row.categoryName === name && row.code === code)
  expect(rows, '新建分类必须可从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].categoryId)).toBe(categoryId)
  return categoryId
}

/**
 * 创建开始表单并查询服务端生成的正式主键。
 * @param {import('@playwright/test').Page} page 设计者页面。
 * @param {string} name 唯一表单名称。
 * @param {object} resourceRegistry 清理登记簿，POST 成功后立即写入 formId。
 * @returns {Promise<string>} 正式表单主键。
 */
async function createForm(page, name, resourceRegistry) {
  const content = JSON.stringify({
    fields: [{
      type: 'text',
      placeholder: '请输入申请主题',
      style: { width: '100%' },
      clearable: true,
      __config__: {
        label: '申请主题', tag: 'el-input', tagIcon: 'input', span: 24,
        required: true, regList: [], layout: 'colFormItem'
      },
      __vModel__: 'requestTitle'
    }],
    size: 'default', labelPosition: 'right', labelWidth: 100,
    gutter: 15, disabled: false, span: 24, formBtns: true
  })
  const created = await callWorkflowApi(page, 'POST', '/workflow/form', {
    data: { formName: name, content, remark: 'P3 动态多实例真实浏览器验收' }
  })
  const formId = String(created.data?.formId || '')
  expect(formId, '表单创建必须返回正式主键').not.toBe('')
  // 与分类相同，必须先登记正式主键再执行可失败的回查断言。
  resourceRegistry.formId = formId
  const result = await callWorkflowApi(page, 'GET', '/workflow/form/list', {
    query: { formName: name, pageNum: 1, pageSize: 20 }
  })
  const rows = (result.rows || []).filter(row => row.formName === name)
  expect(rows, '新建表单必须可从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].formId)).toBe(formId)
  return formId
}

/**
 * 在 Element Plus 下拉框中按正式可见标签选择唯一选项。
 * @param {import('@playwright/test').Page} page 当前真实浏览器页面。
 * @param {import('@playwright/test').Locator} formItem 包含目标下拉框的表单项。
 * @param {string} optionLabel 服务端目录或正式元数据返回的完整显示标签。
 * @returns {Promise<void>} 选项已写入 Vue 状态并回显后结束。
 */
async function selectElementPlusOption(page, formItem, optionLabel) {
  await formItem.locator('.el-select__wrapper').click()
  const option = page.locator('.el-select-dropdown:visible')
    .getByText(optionLabel, { exact: true })
  await expect(option, `下拉选项 ${optionLabel} 必须可见`).toBeVisible()
  await option.click()
  await expect(formItem).toContainText(optionLabel)
}

/**
 * 通过可访问字段名称定位 Element Plus 下拉框，并选择唯一正式选项。
 * @param {import('@playwright/test').Page} page 当前真实浏览器页面。
 * @param {import('@playwright/test').Locator} container 包含目标字段的对话框或属性面板。
 * @param {string} fieldLabel 下拉框的完整可访问名称。
 * @param {string} optionLabel 需要选择的正式选项标签。
 * @returns {Promise<void>} 选项已选中并在所属表单项回显后结束。
 */
async function selectNamedElementPlusOption(page, container, fieldLabel, optionLabel) {
  const combobox = container.getByRole('combobox', { name: fieldLabel })
  if (await combobox.getAttribute('aria-expanded') !== 'true') await combobox.press('Enter')
  const option = page.locator('.el-select-dropdown:visible')
    .getByText(optionLabel, { exact: true })
  await expect(option, `下拉选项 ${optionLabel} 必须可见`).toBeVisible()
  await option.click()
  await expect(container).toContainText(optionLabel)
}

/**
 * 在设计器属性面板中选择受控循环方式。
 * @param {import('@playwright/test').Page} page 已打开模型设计页的真实页面。
 * @param {string} fieldLabel 属性字段标签。
 * @param {string} optionLabel 需要选择的业务选项标签。
 * @returns {Promise<void>} bpmn-js 命令栈和页面回显均已更新后结束。
 */
async function selectDesignerOption(page, fieldLabel, optionLabel) {
  const properties = page.locator('.designer-properties-panel')
  await selectNamedElementPlusOption(page, properties, fieldLabel, optionLabel)
}

/**
 * 通过模型页、设计器属性面板和部署确认框创建并发布受控 ALL/ANY 模型。
 * @param {import('@playwright/test').Page} page 工作流设计者页面。
 * @param {{processKey: string, processName: string, categoryName: string, formName: string, mode: 'ALL'|'ANY', memberSource: 'start'|'fixed', fixedUsers?: Array<{value: string, label: string}>, resourceRegistry: object}} input 页面建模参数与清理登记簿。
 * @returns {Promise<{modelId: string, deploymentId: string, activityId: string, activityName: string}>} 正式模型、部署和活动标识。
 */
async function createAndDeployMultiInstanceModelThroughUi(page, input) {
  const activityId = input.mode === 'ALL' ? 'allReview' : 'anyReview'
  const activityName = input.mode === 'ALL' ? '固定会签' : '发起时或签'
  await page.goto('/workflow/model')
  await page.getByRole('button', { name: '新增', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增流程模型' })
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox', { name: '模型名称' }).fill(input.processName)
  await dialog.getByRole('textbox', { name: '模型标识' }).fill(input.processKey)
  await selectNamedElementPlusOption(page, dialog, '流程分类', input.categoryName)
  await selectNamedElementPlusOption(page, dialog, '流程表单', input.formName)
  await dialog.getByRole('textbox', { name: '模型描述' })
    .fill('会签或或签真实设计器与运行闭环验收')
  const createPromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/model'))
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  const created = await expectWorkflowResponseSuccess(await createPromise, '/workflow/model')
  const modelId = String(created.data?.modelId || '')
  expect(modelId, '页面创建模型必须返回正式主键').not.toBe('')
  input.resourceRegistry.modelId = modelId

  await page.goto(`/workflow/model-design/${encodeURIComponent(modelId)}`)
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeVisible()
  await page.locator('[data-element-id="review"]').click()
  const properties = page.locator('.designer-properties-panel')
  const nameInput = properties.getByRole('textbox', { name: '元素名称' })
  await nameInput.fill(activityName)
  await nameInput.press('Tab')
  const idInput = properties.getByRole('textbox', { name: '元素标识' })
  await idInput.fill(activityId)
  await idInput.press('Tab')
  if (!await properties.getByText('循环方式', { exact: true }).isVisible()) {
    await properties.getByText('执行配置', { exact: true }).click()
  }
  await selectDesignerOption(page, '循环方式', '会签 / 或签')

  const approvalModeLabel = input.mode === 'ALL' ? '会签' : '或签'
  await properties.locator('.el-segmented__item')
    .filter({ hasText: new RegExp(`^${approvalModeLabel}$`) }).click()
  const memberSourceLabel = input.memberSource === 'fixed' ? '固定人员' : '发起时选择'
  await properties.locator('.el-segmented__item')
    .filter({ hasText: new RegExp(`^${memberSourceLabel}$`) }).click()

  if (input.memberSource === 'fixed') {
    // 固定名单为空时必须停留在可编辑状态，但保存边界必须在任何 API 写入前拒绝。
    const saveRequests = []
    const captureSaveRequest = request => {
      if (new URL(request.url()).pathname.endsWith('/workflow/model/save')) saveRequests.push(request)
    }
    page.on('request', captureSaveRequest)
    await page.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('固定会签或或签办理人必须选择 1 至 100 名有效用户', { exact: true }))
      .toBeVisible()
    await page.waitForTimeout(250)
    page.off('request', captureSaveRequest)
    expect(saveRequests, '空固定名单不得产生模型保存写请求').toHaveLength(0)

    for (const user of input.fixedUsers || []) {
      await selectNamedElementPlusOption(page, properties, '固定办理人', user.label)
    }
    await page.keyboard.press('Escape')
  }

  const validationPromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/model/validate'))
  const savePromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/model/save'))
  await page.getByRole('button', { name: '保存', exact: true }).click()
  const validation = await expectWorkflowResponseSuccess(
    await validationPromise, '/workflow/model/validate')
  expect(validation.data?.valid, JSON.stringify(validation.data?.issues || [])).toBe(true)
  const saved = await expectWorkflowResponseSuccess(await savePromise, '/workflow/model/save')
  expect(String(saved.data?.modelId || '')).toBe(modelId)

  // 重开设计页必须从服务端作者 BPMN 回读相同的业务配置，不能依赖当前组件内存状态。
  await page.goto(`/workflow/model-design/${encodeURIComponent(modelId)}`)
  await page.locator(`[data-element-id="${activityId}"]`).click()
  await expect(properties.getByRole('textbox', { name: '元素名称' })).toHaveValue(activityName)
  await expect(properties.getByRole('textbox', { name: '元素标识' })).toHaveValue(activityId)
  if (!await properties.getByText('循环方式', { exact: true }).isVisible()) {
    await properties.getByText('执行配置', { exact: true }).click()
  }
  await expect(properties.getByText('会签 / 或签', { exact: true })).toBeVisible()
  await expect(properties.getByRole('radio', { name: approvalModeLabel, exact: true }))
    .toBeChecked()
  await expect(properties.getByRole('radio', { name: memberSourceLabel, exact: true }))
    .toBeChecked()
  if (input.memberSource === 'fixed') {
    await expect(properties.getByRole('combobox', { name: '固定办理人' })).toHaveCount(1)
    for (const user of input.fixedUsers || []) await expect(properties).toContainText(user.label)
  }

  await page.goto('/workflow/model')
  await page.getByPlaceholder('请输入模型标识').fill(input.processKey)
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  const modelRow = page.locator('.el-table__body tbody tr').filter({ hasText: input.processKey })
  await expect(modelRow, '页面模型列表必须唯一回显新建模型').toHaveCount(1)
  const deployPromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/model/deploy'))
  await modelRow.locator('button.el-button--success').click()
  await page.getByRole('button', { name: '确定', exact: true }).click()
  const deployed = await expectWorkflowResponseSuccess(await deployPromise, '/workflow/model/deploy')
  const deploymentId = String(deployed.data?.deploymentId || '')
  expect(deploymentId, '页面部署模型必须返回正式部署主键').not.toBe('')
  input.resourceRegistry.deploymentId = deploymentId
  await expect(modelRow).toContainText('已部署')
  return { modelId, deploymentId, activityId, activityName }
}

/**
 * 从可发起列表定位刚部署的唯一流程定义。
 * @param {import('@playwright/test').Page} page 发起人页面。
 * @param {string} processKey 唯一流程标识。
 * @returns {Promise<{definitionId: string, deploymentId: string}>} 可发起定义与部署关系。
 */
async function findStartableDefinition(page, processKey) {
  const payload = await callWorkflowApi(page, 'GET', '/workflow/process/list', {
    query: { processKey, pageNum: 1, pageSize: 20 }
  })
  const rows = (payload.rows || []).filter(row => row.processKey === processKey)
  expect(rows, '部署结果必须在可发起列表唯一可见').toHaveLength(1)
  return {
    definitionId: String(rows[0].definitionId),
    deploymentId: String(rows[0].deploymentId)
  }
}

/**
 * 通过真实发起页创建动态多实例流程，并在导航断言前登记服务端返回的实例主键。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @param {{definitionId: string, deploymentId: string}} definition 已部署定义关系。
 * @param {string} formName 页面必须展示的部署表单名称。
 * @param {string} businessKey 本场景唯一业务主键。
 * @param {string} subject 申请主题。
 * @param {{fieldLabel: string, users: Array<{value: string, label: string}>}|undefined} startAssignment 发起时多实例成员字段及正式用户选项。
 * @param {{processInstanceId?:string,processInstanceIds:string[],draftFixtures:Array<{draftId:string,processInstanceId:string}>}} resourceRegistry 清理登记簿，正式提交成功后立即写入实例与草稿主键。
 * @returns {Promise<string>} 正式流程实例主键。
 */
async function startDynamicProcessThroughUi(
  page,
  definition,
  formName,
  businessKey,
  subject,
  startAssignment,
  resourceRegistry
) {
  await page.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
  await expect(page.getByRole('heading', { name: formName })).toBeVisible()
  await page.getByPlaceholder('可选').fill(businessKey)
  await page.getByPlaceholder('请输入申请主题').fill(subject)
  if (startAssignment) {
    const assignments = page.locator('.process-start-page__assignments')
    await expect(assignments.getByRole('combobox', { name: startAssignment.fieldLabel }),
      '发起页必须投影受控多实例成员字段').toHaveCount(1)
    for (const user of startAssignment.users) {
      await selectNamedElementPlusOption(
        page, assignments, startAssignment.fieldLabel, user.label)
    }
    await page.keyboard.press('Escape')
  }
  const processInstanceId = await submitWorkflowStartPage(page, resourceRegistry)
  // 草稿提交已经成功，保留单值别名供本文件后续业务断言读取。
  resourceRegistry.processInstanceId = processInstanceId
  await expect(page).toHaveURL(new RegExp(`/workflow/process-detail/${processInstanceId}(?:[/?]|$)`))
  return processInstanceId
}

/**
 * 判断浏览器响应是否对应指定正式工作流接口。
 * @param {import('@playwright/test').Response} response 浏览器捕获的真实响应。
 * @param {string} path 不含 `/dev-api` 的工作流接口路径。
 * @param {string} method 期望 HTTP 方法。
 * @returns {boolean} 路径和方法同时匹配时返回 true。
 */
function matchesWorkflowResponse(response, path, method = 'POST') {
  const url = new URL(response.url())
  return url.pathname.endsWith(path) && response.request().method() === method
}

/**
 * 校验页面动作返回 HTTP 200 和 AjaxResult 业务成功。
 * @param {import('@playwright/test').Response} response 页面触发的真实接口响应。
 * @param {string} endpoint 用于失败信息的正式接口路径。
 * @returns {Promise<any>} 已通过传输层和业务码校验的响应正文。
 */
async function expectWorkflowResponseSuccess(response, endpoint) {
  expect(response.status(), `${endpoint} 传输层状态`).toBe(200)
  const payload = await response.json()
  expect(payload.code, `${endpoint} 业务码`).toBe(200)
  return payload
}

/**
 * 通过详情页远程审批目录提交一次真实动态加签。
 * @param {import('@playwright/test').Page} page 当前动态多实例办理人页面。
 * @param {{username: string}} account 目标职责账号，仅用于触发服务端远程检索。
 * @param {{value: string, label: string}} option 服务端返回的目标用户选项。
 * @param {string} comment 加签业务意见。
 * @returns {Promise<void>} 身份目录与加签写接口成功且弹窗关闭后结束。
 */
async function addMultiInstanceMemberThroughUi(page, account, option, comment) {
  await page.getByRole('button', { name: '加签', exact: true }).first().click()
  const dialog = page.getByRole('dialog', { name: '增加会签成员' })
  await expect(dialog).toBeVisible()
  const memberField = dialog.locator('.el-form-item').filter({ hasText: '新增成员' })
  const input = memberField.getByRole('combobox')
  await expect(input, '加签弹窗必须展示唯一成员选择器').toHaveCount(1)
  await memberField.locator('.el-select__wrapper').click()
  const searchResponsePromise = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname.endsWith('/workflow/identity/options')
      && response.request().method() === 'GET'
      && url.searchParams.get('capability') === 'approval'
      && url.searchParams.get('keyword') === account.username
  })
  await input.pressSequentially(account.username, { delay: 25 })
  await expectWorkflowResponseSuccess(await searchResponsePromise, '/workflow/identity/options')
  const targetOption = page.locator('.el-select-dropdown:visible').getByText(option.label, { exact: true })
  await expect(targetOption, '审批资格远程目录必须包含加签目标').toBeVisible()
  await targetOption.click()
  await expect(memberField, '加签目标必须真实写入选择器后才能提交').toContainText(option.label)
  // Element Plus 多选器选中后会继续展开并重新聚焦；先关闭下拉，避免加签原因被写入远程检索框。
  await page.keyboard.press('Escape')
  await expect(page.locator('.el-select-dropdown:visible'), '填写原因前必须结束成员检索交互').toHaveCount(0)
  const commentInput = dialog.locator('textarea[placeholder="请输入加签原因"]')
  await commentInput.click()
  await commentInput.fill(comment)
  await expect(commentInput, '加签原因必须写入调整表单后才能提交').toHaveValue(comment)
  const adjustResponsePromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/task/multiInstance/adjust'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectWorkflowResponseSuccess(await adjustResponsePromise, '/workflow/task/multiInstance/adjust')
  await expect(dialog).toBeHidden()
}

/**
 * 通过成员表格和真实确认弹窗提交一次动态减签。
 * @param {import('@playwright/test').Page} page 当前动态多实例办理人页面。
 * @param {string} targetUserId 待移除成员的正式用户主键。
 * @param {string} comment 减签业务意见。
 * @returns {Promise<void>} 目标任务、减签接口和弹窗状态均成功后结束。
 */
async function removeMultiInstanceMemberThroughUi(page, targetUserId, comment) {
  const memberSection = page.locator('.workflow-detail__multi-instance')
  const targetRow = memberSection.locator('.el-table__body tbody tr').filter({ hasText: `ID ${targetUserId}` })
  await expect(targetRow, '成员表格必须唯一展示待减签用户').toHaveCount(1)
  const removeButton = targetRow.getByRole('button', { name: /^移除 / })
  await expect(removeButton, '可减签成员必须展示移除动作').toHaveCount(1)
  await removeButton.click()
  const dialog = page.getByRole('dialog', { name: '移除会签成员' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.workflow-detail__remove-target')).toContainText(`任务 `)
  await dialog.getByPlaceholder('请输入减签原因').fill(comment)
  const adjustResponsePromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/task/multiInstance/adjust'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectWorkflowResponseSuccess(await adjustResponsePromise, '/workflow/task/multiInstance/adjust')
  await expect(dialog).toBeHidden()
}

/**
 * 通过当前详情页完成一个无需指定下一办理人的真实动态多实例任务。
 * @param {import('@playwright/test').Page} page 当前成员的任务详情页。
 * @param {string} comment 完成业务意见。
 * @returns {Promise<void>} 完成接口成功且动作弹窗关闭后结束。
 */
async function completeMultiInstanceTaskThroughUi(page, comment) {
  await page.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '通过任务' })
  await expect(dialog).toBeVisible()
  // 当前动态多实例节点的后继是结束事件，详情策略必须使完成弹窗完全隐藏下一办理人字段。
  await expect(dialog.locator('.el-form-item').filter({
    hasText: /下一办理人|会签办理人|或签办理人/
  }), '动态多实例完成弹窗不得显示下一办理人字段').toHaveCount(0)
  await dialog.getByPlaceholder('请输入审批意见').fill(comment)
  const responsePromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/task/complete'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectWorkflowResponseSuccess(await responsePromise, '/workflow/task/complete')
  await expect(dialog).toBeHidden()
}

/**
 * 从正式流程详情精确核对唯一动态成员调整审计。
 * @param {import('@playwright/test').Page} page 有实例读取权限的页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {{action: string, opinion: string, taskId: string, actorUserId: string, beforeRevision: number, afterRevision: number, targetUserIds?: string[], targetTaskId?: string, targetUserId?: string}} expected 审计字段完整期望。
 * @returns {Promise<void>} 唯一 comment 的任务、类型、操作人、revision 和目标均匹配后结束。
 */
async function expectMultiInstanceAdjustmentAudit(page, processInstanceId, expected) {
  const detail = await callWorkflowApi(page, 'GET', '/workflow/process/detail', {
    query: { procInsId: processInstanceId }
  })
  const matching = (detail.data?.historyProcNodeList || [])
    .flatMap(node => node.comments || [])
    .map(comment => {
      try {
        return { comment, audit: JSON.parse(String(comment.message || '')) }
      } catch {
        return null
      }
    })
    .filter(Boolean)
    .filter(item => item.audit.action === expected.action && item.audit.opinion === expected.opinion)
  expect(matching, `${expected.action} 审计必须唯一持久化`).toHaveLength(1)
  const { comment, audit } = matching[0]
  expect(String(comment.taskId)).toBe(String(expected.taskId))
  expect(String(comment.type)).toBe('1')
  expect(String(audit.actorUserId)).toBe(String(expected.actorUserId))
  expect(audit.beforeRevision).toBe(expected.beforeRevision)
  expect(audit.afterRevision).toBe(expected.afterRevision)
  if (expected.targetUserIds) expect(audit.targetUserIds).toEqual(expected.targetUserIds.map(String))
  if (expected.targetTaskId) expect(String(audit.targetTaskId)).toBe(String(expected.targetTaskId))
  if (expected.targetUserId) expect(String(audit.targetUserId)).toBe(String(expected.targetUserId))
}

/**
 * 从正式流程详情精确核对唯一动态多实例完成审计及其 revision 区间。
 * @param {import('@playwright/test').Page} page 有实例读取权限的页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {{opinion: string, taskId: string, actorUserId: string, activityId: string, beforeRevision: number, afterRevision: number}} expected 完成审计完整期望。
 * @returns {Promise<void>} 唯一完成 comment 的任务、操作人、活动和 revision 均匹配后结束。
 */
async function expectMultiInstanceCompletionAudit(page, processInstanceId, expected) {
  const detail = await callWorkflowApi(page, 'GET', '/workflow/process/detail', {
    query: { procInsId: processInstanceId }
  })
  const matching = (detail.data?.historyProcNodeList || [])
    .flatMap(node => node.comments || [])
    .map(comment => {
      try {
        return { comment, audit: JSON.parse(String(comment.message || '')) }
      } catch {
        return null
      }
    })
    .filter(Boolean)
    .filter(item => item.audit.action === 'COMPLETE' && item.audit.opinion === expected.opinion)
  expect(matching, '动态多实例完成审计必须唯一持久化').toHaveLength(1)
  const { comment, audit } = matching[0]
  expect(String(comment.taskId)).toBe(String(expected.taskId))
  expect(String(comment.type)).toBe('1')
  expect(String(audit.actorUserId)).toBe(String(expected.actorUserId))
  expect(audit.multiInstanceActivityId).toBe(expected.activityId)
  expect(audit.beforeRevision).toBe(expected.beforeRevision)
  expect(audit.afterRevision).toBe(expected.afterRevision)
}

/**
 * 通过正式删除入口清理本用例创建的流程历史、部署、模型、表单和分类。
 * @param {{admin?: import('@playwright/test').Page, designer?: import('@playwright/test').Page}} pages 管理员和设计者页面。
 * @param {{draftFixtures?:Array<{draftId:string,processInstanceId?:string}>,processInstanceId?:string,processInstanceIds?:string[],deploymentId?:string,modelId?:string,formId?:string,categoryId?:string}} resources 已成功创建的资源主键。
 * @returns {Promise<string[]>} 脱敏后的清理错误集合。
 */
async function cleanupFixture(pages, resources) {
  return cleanupWorkflowResources(pages, {
    ...resources,
    processInstanceIds: resources.processInstanceIds
      || (resources.processInstanceId ? [resources.processInstanceId] : []),
    deploymentIds: resources.deploymentIds
      || (resources.deploymentId ? [resources.deploymentId] : []),
    modelIds: resources.modelIds || (resources.modelId ? [resources.modelId] : [])
  })
}

test('ANY 发起时或签由设计器发布，首人完成即原子结束且拒绝越权和失效成员', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  const runId = `p3any_${Date.now()}`
  const resources = { processInstanceIds: [], draftFixtures: [] }
  const sessions = []
  const pages = {}
  let primaryError = null
  try {
    const designerSession = await openWorkflowRoleSession(browser, 'workflow_designer')
    // 每个登录成功的会话立即登记，后续角色登录失败时 finally 仍能注销已签发的 Redis Token。
    sessions.push(designerSession)
    const starterSession = await openWorkflowRoleSession(browser, 'workflow_starter')
    sessions.push(starterSession)
    const approverSession = await openWorkflowRoleSession(browser, 'workflow_approver')
    sessions.push(approverSession)
    const adminSession = await openWorkflowRoleSession(browser, 'workflow_admin')
    sessions.push(adminSession)
    const auditorSession = await openWorkflowRoleSession(browser, 'workflow_auditor')
    sessions.push(auditorSession)
    pages.designer = designerSession.page
    pages.starter = starterSession.page
    pages.approver = approverSession.page
    pages.admin = adminSession.page
    pages.auditor = auditorSession.page

    // 审批资格用户必须来自实时 RBAC 目录；审计角色仅可作为抄送人，不能成为任务办理人。
    const approver = await findUserOption(pages.designer, accounts.workflow_approver.username, true)
    const admin = await findUserOption(pages.designer, accounts.workflow_admin.username, true)
    const auditor = await findUserOption(pages.designer, accounts.workflow_auditor.username, false)
    const ineligibleAuditor = await findUserOption(pages.designer, accounts.workflow_auditor.username, true)
    expect(approver, '审批人账号必须具备流程办理权限').not.toBeNull()
    expect(admin, '超级管理员必须遵循若依超级管理员权限语义').not.toBeNull()
    expect(auditor, '审计账号必须存在于通用启用用户目录').not.toBeNull()
    expect(ineligibleAuditor, '审计账号不能进入审批资格目录').toBeNull()

    const categoryName = `P3或签验收-${runId}`
    const categoryCode = `p3any_${Date.now()}`
    const formName = `P3或签表单-${runId}`
    const processKey = `p3any_${Date.now()}`
    const processName = `P3动态或签-${runId}`
    resources.categoryId = await createCategory(pages.designer, categoryName, categoryCode, resources)
    resources.formId = await createForm(pages.designer, formName, resources)
    Object.assign(resources, await createAndDeployMultiInstanceModelThroughUi(pages.designer, {
      processKey,
      processName,
      categoryName,
      formName,
      mode: 'ANY',
      memberSource: 'start',
      resourceRegistry: resources
    }))
    const definition = await findStartableDefinition(pages.starter, processKey)
    expect(definition.deploymentId).toBe(resources.deploymentId)

    // 发起动作必须走真实页面、部署表单快照和正式 start API。
    resources.processInstanceId = await startDynamicProcessThroughUi(
      pages.starter,
      definition,
      formName,
      `BUS-${runId}`,
      `动态或签申请-${runId}`,
      {
        fieldLabel: `${resources.activityName}（或签）`,
        users: [approver]
      },
      resources
    )

    // 发起页正式成员直接创建真实或签任务，进入节点后继续验证动态加减签链路。
    const anyTask = await findAssignedTask(pages.approver, processKey, 'anyReview')
    expect(anyTask.processInstanceId).toBe(resources.processInstanceId)
    await pages.approver.goto(`/workflow/process-detail/${encodeURIComponent(resources.processInstanceId)}?taskId=${encodeURIComponent(anyTask.taskId)}`)
    await expect(pages.approver.getByText('任一通过', { exact: true })).toBeVisible()
    await expect(pages.approver.getByText('活动 1 人，已完成 0 人', { exact: true })).toBeVisible()

    const detailBefore = await callWorkflowApi(pages.approver, 'GET', '/workflow/process/detail', {
      query: { procInsId: resources.processInstanceId, taskId: anyTask.taskId }
    })
    const currentTaskId = String(detailBefore.data?.currentTask?.taskId || '')
    expect(currentTaskId).toBe(String(anyTask.taskId))
    const stateBefore = await callWorkflowApi(pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(currentTaskId)}`)
    expect(stateBefore.data?.mode).toBe('ANY')
    expect(stateBefore.data?.revision).toBe(0)
    expect(stateBefore.data?.members).toHaveLength(1)

    // 只读审计角色不能绕过页面直接调整成员，拒绝后 execution、成员变量和历史必须保持不变。
    await callWorkflowApi(pages.auditor, 'POST', '/workflow/task/multiInstance/adjust', {
      data: {
        taskId: currentTaskId,
        action: 'ADD',
        expectedRevision: stateBefore.data.revision,
        comment: '审计角色越权加签必须拒绝',
        userIds: [admin.value]
      },
      expectedCode: 403
    })
    const stateAfterPermissionDenied = await callWorkflowApi(
      pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(currentTaskId)}`)
    expect(stateAfterPermissionDenied.data).toEqual(stateBefore.data)
    const detailAfterPermissionDenied = await callWorkflowApi(
      pages.approver, 'GET', '/workflow/process/detail', {
        query: { procInsId: resources.processInstanceId, taskId: anyTask.taskId }
      })
    expect(detailAfterPermissionDenied.data?.historyProcNodeList)
      .toEqual(detailBefore.data?.historyProcNodeList)

    // 直接 API 绕过页面提交无办理权限审计用户必须整批失败，revision、成员和意见均不得改变。
    await callWorkflowApi(pages.approver, 'POST', '/workflow/task/multiInstance/adjust', {
      data: {
        taskId: currentTaskId,
        action: 'ADD',
        expectedRevision: stateBefore.data.revision,
        comment: '无权限用户加签应被拒绝',
        userIds: [auditor.value]
      },
      expectedCode: 400
    })
    const stateAfterDeniedAdd = await callWorkflowApi(pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(currentTaskId)}`)
    expect(stateAfterDeniedAdd.data).toEqual(stateBefore.data)
    const detailAfterDeniedAdd = await callWorkflowApi(pages.approver, 'GET', '/workflow/process/detail', {
      query: { procInsId: resources.processInstanceId, taskId: anyTask.taskId }
    })
    expect(detailAfterDeniedAdd.data?.historyProcNodeList).toEqual(detailBefore.data?.historyProcNodeList)

    // 页面远程目录同样不能展示审计用户，前端过滤与后端最终校验形成双层边界。
    await pages.approver.getByRole('button', { name: '加签', exact: true }).first().click()
    const addDialog = pages.approver.getByRole('dialog', { name: '增加会签成员' })
    await expect(addDialog).toBeVisible()
    const addInput = addDialog.getByRole('combobox', { name: /新增成员/ })
    await expect(addInput).toBeVisible()
    const [approvalSearchResponse] = await Promise.all([
      pages.approver.waitForResponse(response => {
        const url = new URL(response.url())
        return url.pathname.endsWith('/workflow/identity/options')
          && url.searchParams.get('capability') === 'approval'
          && url.searchParams.get('keyword') === accounts.workflow_auditor.username
      }),
      (async () => {
        // Element Plus remote select 依赖逐次 input 事件；可见输入框逐键输入才能触发真实远程目录查询。
        await addInput.click()
        await addInput.pressSequentially(accounts.workflow_auditor.username, { delay: 25 })
      })()
    ])
    expect(approvalSearchResponse.status(), '审批资格远程检索传输层状态').toBe(200)
    const approvalSearchPayload = await approvalSearchResponse.json()
    expect(approvalSearchPayload.code, '审批资格远程检索业务码').toBe(200)
    expect(approvalSearchPayload.rows, '审计用户不能进入审批资格远程目录').toEqual([])
    await expect(pages.approver.locator('.el-select-dropdown:visible').getByText(
      accounts.workflow_auditor.username, { exact: false })).toHaveCount(0)
    await addDialog.getByRole('button', { name: '取消', exact: true }).click()
    // 等待 Element Plus 完成关闭动画和 @closed 清理，禁止旧弹窗清理第二次打开后的真实选择。
    await expect(addDialog).toBeHidden()

    // 真实 UI 加签管理员，服务端 revision、execution、成员快照和结构化审计必须同步提交。
    await addMultiInstanceMemberThroughUi(
      pages.approver, accounts.workflow_admin, admin, '增加管理员联合或签')
    const stateAfterAdd = await callWorkflowApi(
      pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(currentTaskId)}`)
    expect(stateAfterAdd.data?.revision).toBe(1)
    expect(stateAfterAdd.data?.members).toHaveLength(2)
    expect(stateAfterAdd.data.members.map(member => String(member.userId)).sort())
      .toEqual([String(admin.value), String(approver.value)].sort())
    const addedAdminMember = stateAfterAdd.data.members.find(member => String(member.userId) === String(admin.value))
    expect(addedAdminMember?.active).toBe(true)
    expect(String(addedAdminMember?.activeTaskId || '')).not.toBe('')
    await expectMultiInstanceAdjustmentAudit(pages.admin, resources.processInstanceId, {
      action: 'MULTI_INSTANCE_ADD',
      opinion: '增加管理员联合或签',
      taskId: currentTaskId,
      actorUserId: String(approver.value),
      beforeRevision: 0,
      afterRevision: 1,
      targetUserIds: [String(admin.value)]
    })

    // 使用加签前冻结的旧 revision 发起真实减签，必须命中专用 CAS 冲突且业务状态零变化。
    const detailAfterAdd = await callWorkflowApi(pages.admin, 'GET', '/workflow/process/detail', {
      query: { procInsId: resources.processInstanceId, taskId: currentTaskId }
    })
    const staleRevisionConflict = await callWorkflowApi(
      pages.approver, 'POST', '/workflow/task/multiInstance/adjust', {
        data: {
          taskId: currentTaskId,
          action: 'REMOVE',
          expectedRevision: stateBefore.data.revision,
          comment: '过期版本减签必须拒绝',
          userIds: [],
          targetTaskId: String(addedAdminMember.activeTaskId)
        },
        expectedCode: 409
      })
    expect(staleRevisionConflict.subCode, 'revision 失配必须返回稳定机器子码')
      .toBe('WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT')
    const stateAfterStaleConflict = await callWorkflowApi(
      pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(currentTaskId)}`)
    expect(stateAfterStaleConflict.data, 'revision 冲突不得改变成员、任务或服务端版本')
      .toEqual(stateAfterAdd.data)
    const detailAfterStaleConflict = await callWorkflowApi(
      pages.admin, 'GET', '/workflow/process/detail', {
        query: { procInsId: resources.processInstanceId, taskId: currentTaskId }
      })
    expect(detailAfterStaleConflict.data?.historyProcNodeList, 'revision 冲突不得新增或改写审计记录')
      .toEqual(detailAfterAdd.data?.historyProcNodeList)

    // 真实 UI 减签刚新增的 sibling，目标任务历史必须结束且成员版本连续递增。
    await removeMultiInstanceMemberThroughUi(
      pages.approver, String(admin.value), '管理员暂不参与本轮或签')
    const stateAfterRemove = await callWorkflowApi(
      pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(currentTaskId)}`)
    expect(stateAfterRemove.data?.revision).toBe(2)
    expect(stateAfterRemove.data?.members).toHaveLength(1)
    expect(String(stateAfterRemove.data.members[0]?.userId)).toBe(String(approver.value))
    // 页面必须完成 revision 和成员投影刷新，且上一轮关闭期已经解除，才能开始下一次真实加签。
    const memberSectionAfterRemove = pages.approver.locator('.workflow-detail__multi-instance')
    await expect(memberSectionAfterRemove.getByText('版本 2', { exact: true })).toBeVisible()
    await expect(memberSectionAfterRemove.getByText('活动 1 人，已完成 0 人', { exact: true })).toBeVisible()
    await expect(memberSectionAfterRemove.locator('.el-table__body tbody tr').filter({
      hasText: `ID ${admin.value}`
    })).toHaveCount(0)
    await expect(memberSectionAfterRemove.getByRole('button', { name: '加签', exact: true })).toBeEnabled()
    await expectMultiInstanceAdjustmentAudit(pages.admin, resources.processInstanceId, {
      action: 'MULTI_INSTANCE_REMOVE',
      opinion: '管理员暂不参与本轮或签',
      taskId: currentTaskId,
      actorUserId: String(approver.value),
      beforeRevision: 1,
      afterRevision: 2,
      targetTaskId: String(addedAdminMember.activeTaskId),
      targetUserId: String(admin.value)
    })

    // 再次加签产生全新 sibling，证明减签历史不会阻止同一合格用户重新加入。
    await addMultiInstanceMemberThroughUi(
      pages.approver, accounts.workflow_admin, admin, '重新加入管理员完成或签')
    const stateBeforeCompletion = await callWorkflowApi(
      pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(currentTaskId)}`)
    expect(stateBeforeCompletion.data?.revision).toBe(3)
    expect(stateBeforeCompletion.data?.members).toHaveLength(2)
    const readdedAdminMember = stateBeforeCompletion.data.members.find(
      member => String(member.userId) === String(admin.value))
    expect(String(readdedAdminMember?.activeTaskId || '')).not.toBe(String(addedAdminMember.activeTaskId))
    await expectMultiInstanceAdjustmentAudit(pages.admin, resources.processInstanceId, {
      action: 'MULTI_INSTANCE_ADD',
      opinion: '重新加入管理员完成或签',
      taskId: currentTaskId,
      actorUserId: String(approver.value),
      beforeRevision: 2,
      afterRevision: 3,
      targetUserIds: [String(admin.value)]
    })
    await expect(pages.approver.getByText('活动 2 人，已完成 0 人', { exact: true })).toBeVisible()

    const anyRuntimeDatabase = await loadRuntimeMultiInstanceDatabaseEvidence(
      resources.processInstanceId, 'anyReview')
    expect(anyRuntimeDatabase).toMatchObject({
      mode: 'ANY', revision: 3, stateVariableCount: 3,
      memberVariableCount: 1, taskCount: 2
    })
    expect(Number(anyRuntimeDatabase.activeExecutionCount)).toBeGreaterThanOrEqual(2)

    await testInfo.attach('any-active-state.png', {
      body: await pages.approver.screenshot({ fullPage: true }),
      contentType: 'image/png'
    })

    // ANY 模式由首名成员完成后原子结束，其余 sibling 只能留下受控取消历史，不能继续办理。
    await completeMultiInstanceTaskThroughUi(pages.approver, '首名成员完成或签')
    await expect(pages.approver.getByText('已完成', { exact: true }).first()).toBeVisible()
    await expect(pages.approver.getByRole('button', { name: '通过', exact: true })).toHaveCount(0)

    const completedDetail = await callWorkflowApi(pages.approver, 'GET', '/workflow/process/detail', {
      query: { procInsId: resources.processInstanceId }
    })
    expect(completedDetail.data?.processStatus).toBe('completed')
    expect(completedDetail.data?.currentTask).toBeNull()
    await expectMultiInstanceCompletionAudit(pages.admin, resources.processInstanceId, {
      opinion: '首名成员完成或签',
      taskId: currentTaskId,
      actorUserId: String(approver.value),
      activityId: 'anyReview',
      beforeRevision: 3,
      afterRevision: 4
    })
    const anyHistory = (completedDetail.data?.historyProcNodeList || [])
      .filter(node => node.activityId === 'anyReview')
    expect(anyHistory, 'ANY 节点必须保留原成员、减签成员和重新加签成员的三份历史').toHaveLength(3)
    expect(anyHistory.filter(node => !node.deleteReason), '只能有首名成员自然完成').toHaveLength(1)
    expect(anyHistory.filter(node => Boolean(node.deleteReason)), '减签任务与剩余 sibling 必须记录受控删除原因').toHaveLength(2)
    const removedHistory = anyHistory.find(node => String(node.taskId) === String(addedAdminMember.activeTaskId))
    const canceledSiblingHistory = anyHistory.find(
      node => String(node.taskId) === String(readdedAdminMember.activeTaskId))
    expect(removedHistory?.deleteReason, '减签任务必须持久化删除原因').toBeTruthy()
    expect(canceledSiblingHistory?.deleteReason, 'ANY 剩余 sibling 必须持久化取消原因').toBeTruthy()

    const anyHistoricDatabase = await loadHistoricMultiInstanceDatabaseEvidence(
      resources.processInstanceId, 'anyReview')
    expect(anyHistoricDatabase).toMatchObject({
      mode: 'ANY', revision: 4, stateVariableCount: 3,
      memberVariableCount: 1, runtimeTaskCount: 0, runtimeExecutionCount: 0,
      historicTaskCount: 3, naturalTaskCount: 1, canceledTaskCount: 2,
      auditCommentCount: 4
    })

    await pages.admin.goto(`/workflow/process-detail/${encodeURIComponent(resources.processInstanceId)}`)
    await expect(pages.admin.getByText('已完成', { exact: true }).first()).toBeVisible()
    await expect(pages.admin.getByRole('button', { name: '通过', exact: true })).toHaveCount(0)
    await testInfo.attach('any-completed-state.png', {
      body: await pages.admin.screenshot({ fullPage: true }),
      contentType: 'image/png'
    })
    await testInfo.attach('any-evidence.json', {
      body: Buffer.from(JSON.stringify({
        runId,
        processKey,
        processInstanceId: resources.processInstanceId,
        mode: stateBefore.data.mode,
        initialMemberCount: stateBefore.data.members.length,
        postAdjustmentMemberCount: stateBeforeCompletion.data.members.length,
        adjustmentRevision: stateBeforeCompletion.data.revision,
        staleRevisionConflictSubCode: staleRevisionConflict.subCode,
        deniedUserRole: 'workflow_auditor',
        deniedBusinessCode: 400,
        finalStatus: completedDetail.data.processStatus,
        naturalCompletionCount: anyHistory.filter(node => !node.deleteReason).length,
        removedMemberHistoryCount: removedHistory ? 1 : 0,
        canceledSiblingCount: canceledSiblingHistory ? 1 : 0,
        database: anyHistoricDatabase
      }, null, 2)),
      contentType: 'application/json'
    })
  } catch (error) {
    primaryError = error
  } finally {
    const cleanupErrors = await cleanupFixture(pages, resources)
    const logoutErrors = await closeWorkflowRoleSessions(sessions)
    const finalErrors = [...cleanupErrors, ...logoutErrors]
    if (primaryError) {
      if (finalErrors.length) primaryError.message += `；清理失败：${finalErrors.join(' | ')}`
      throw primaryError
    }
    expect(finalErrors, '正式业务夹具和 Redis 登录态必须全部清理').toEqual([])
  }
})

test('ALL 固定会签由设计器发布并必须由全部真实成员完成', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  const runId = `p3all_${Date.now()}`
  const resources = { processInstanceIds: [], draftFixtures: [] }
  const sessions = []
  const pages = {}
  let primaryError = null
  try {
    const designerSession = await openWorkflowRoleSession(browser, 'workflow_designer')
    // 会话逐个登记，确保任意后续登录异常都不会遗留已创建的真实登录态。
    sessions.push(designerSession)
    const starterSession = await openWorkflowRoleSession(browser, 'workflow_starter')
    sessions.push(starterSession)
    const approverSession = await openWorkflowRoleSession(browser, 'workflow_approver')
    sessions.push(approverSession)
    const adminSession = await openWorkflowRoleSession(browser, 'workflow_admin')
    sessions.push(adminSession)
    pages.designer = designerSession.page
    pages.starter = starterSession.page
    pages.approver = approverSession.page
    pages.admin = adminSession.page

    const approver = await findUserOption(pages.designer, accounts.workflow_approver.username, true)
    const admin = await findUserOption(pages.designer, accounts.workflow_admin.username, true)
    expect(approver, '审批人账号必须具备流程办理权限').not.toBeNull()
    expect(admin, '超级管理员必须具备动态会签办理资格').not.toBeNull()

    const categoryName = `P3会签验收-${runId}`
    const categoryCode = `p3all_${Date.now()}`
    const formName = `P3会签表单-${runId}`
    const processKey = `p3all_${Date.now()}`
    const processName = `P3动态会签-${runId}`
    resources.categoryId = await createCategory(
      pages.designer, categoryName, categoryCode, resources)
    resources.formId = await createForm(pages.designer, formName, resources)
    Object.assign(resources, await createAndDeployMultiInstanceModelThroughUi(pages.designer, {
      processKey,
      processName,
      categoryName,
      formName,
      mode: 'ALL',
      memberSource: 'fixed',
      fixedUsers: [approver, admin],
      resourceRegistry: resources
    }))
    const definition = await findStartableDefinition(pages.starter, processKey)
    expect(definition.deploymentId).toBe(resources.deploymentId)
    resources.processInstanceId = await startDynamicProcessThroughUi(
      pages.starter,
      definition,
      formName,
      `BUS-${runId}`,
      `动态会签申请-${runId}`,
      undefined,
      resources
    )

    // 固定名单进入节点时重新核验审批资格，并在同一引擎命令中创建两个 execution/task。
    const approverTask = await findAssignedTask(pages.approver, processKey, 'allReview')
    const adminTask = await findAssignedTask(pages.admin, processKey, 'allReview')
    expect(approverTask.processInstanceId).toBe(resources.processInstanceId)
    expect(adminTask.processInstanceId).toBe(resources.processInstanceId)
    expect(String(approverTask.taskId)).not.toBe(String(adminTask.taskId))

    await pages.approver.goto(`/workflow/process-detail/${encodeURIComponent(resources.processInstanceId)}?taskId=${encodeURIComponent(approverTask.taskId)}`)
    await expect(pages.approver.getByText('全部通过', { exact: true })).toBeVisible()
    await expect(pages.approver.getByText('活动 2 人，已完成 0 人', { exact: true })).toBeVisible()
    const initialState = await callWorkflowApi(
      pages.approver, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(approverTask.taskId)}`)
    expect(initialState.data?.mode).toBe('ALL')
    expect(initialState.data?.revision).toBe(0)
    expect(initialState.data?.members).toHaveLength(2)

    // 首名成员完成后流程必须继续运行，不能提前按 ANY 语义结束。
    await completeMultiInstanceTaskThroughUi(pages.approver, '会签成员一通过')
    const afterFirstCompletion = await callWorkflowApi(
      pages.admin, 'GET', `/workflow/task/multiInstance/${encodeURIComponent(adminTask.taskId)}`)
    expect(afterFirstCompletion.data?.mode).toBe('ALL')
    expect(afterFirstCompletion.data?.revision).toBe(1)
    expect(afterFirstCompletion.data?.members).toHaveLength(2)
    expect(afterFirstCompletion.data.members.filter(member => member.active)).toHaveLength(1)
    const runningDetail = await callWorkflowApi(pages.admin, 'GET', '/workflow/process/detail', {
      query: { procInsId: resources.processInstanceId, taskId: adminTask.taskId }
    })
    expect(runningDetail.data?.processStatus).toBe('running')
    expect(String(runningDetail.data?.currentTask?.taskId)).toBe(String(adminTask.taskId))
    const allRunningDatabase = await loadRuntimeMultiInstanceDatabaseEvidence(
      resources.processInstanceId, 'allReview')
    expect(allRunningDatabase).toMatchObject({
      mode: 'ALL', revision: 1, stateVariableCount: 3,
      memberVariableCount: 1, taskCount: 1
    })
    expect(Number(allRunningDatabase.activeExecutionCount)).toBeGreaterThanOrEqual(1)
    await expectMultiInstanceCompletionAudit(pages.admin, resources.processInstanceId, {
      opinion: '会签成员一通过',
      taskId: String(approverTask.taskId),
      actorUserId: String(approver.value),
      activityId: 'allReview',
      beforeRevision: 0,
      afterRevision: 1
    })

    await pages.admin.goto(`/workflow/process-detail/${encodeURIComponent(resources.processInstanceId)}?taskId=${encodeURIComponent(adminTask.taskId)}`)
    await expect(pages.admin.getByText('活动 1 人，已完成 1 人', { exact: true })).toBeVisible()
    await completeMultiInstanceTaskThroughUi(pages.admin, '会签成员二通过')
    const completedDetail = await callWorkflowApi(pages.admin, 'GET', '/workflow/process/detail', {
      query: { procInsId: resources.processInstanceId }
    })
    expect(completedDetail.data?.processStatus).toBe('completed')
    expect(completedDetail.data?.currentTask).toBeNull()
    const allHistory = (completedDetail.data?.historyProcNodeList || [])
      .filter(node => node.activityId === 'allReview')
    expect(allHistory, 'ALL 节点必须保留两名正式成员历史').toHaveLength(2)
    expect(allHistory.filter(node => !node.deleteReason), '两名会签成员都必须自然完成').toHaveLength(2)
    await expectMultiInstanceCompletionAudit(pages.admin, resources.processInstanceId, {
      opinion: '会签成员二通过',
      taskId: String(adminTask.taskId),
      actorUserId: String(admin.value),
      activityId: 'allReview',
      beforeRevision: 1,
      afterRevision: 2
    })

    const allHistoricDatabase = await loadHistoricMultiInstanceDatabaseEvidence(
      resources.processInstanceId, 'allReview')
    expect(allHistoricDatabase).toMatchObject({
      mode: 'ALL', revision: 2, stateVariableCount: 3,
      memberVariableCount: 1, runtimeTaskCount: 0, runtimeExecutionCount: 0,
      historicTaskCount: 2, naturalTaskCount: 2, canceledTaskCount: 0,
      auditCommentCount: 2
    })

    await testInfo.attach('all-evidence.json', {
      body: Buffer.from(JSON.stringify({
        runId,
        processKey,
        processInstanceId: resources.processInstanceId,
        mode: initialState.data.mode,
        initialMemberCount: initialState.data.members.length,
        runningAfterFirstCompletion: runningDetail.data.processStatus,
        finalStatus: completedDetail.data.processStatus,
        finalRevision: 2,
        naturalCompletionCount: allHistory.filter(node => !node.deleteReason).length,
        database: allHistoricDatabase
      }, null, 2)),
      contentType: 'application/json'
    })
  } catch (error) {
    primaryError = error
  } finally {
    const cleanupErrors = await cleanupFixture(pages, resources)
    const logoutErrors = await closeWorkflowRoleSessions(sessions)
    const finalErrors = [...cleanupErrors, ...logoutErrors]
    if (primaryError) {
      if (finalErrors.length) primaryError.message += `；清理失败：${finalErrors.join(' | ')}`
      throw primaryError
    }
    expect(finalErrors, '正式业务夹具和 Redis 登录态必须全部清理').toEqual([])
  }
})
