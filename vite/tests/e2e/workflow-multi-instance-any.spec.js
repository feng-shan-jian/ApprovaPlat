import { randomUUID } from 'node:crypto'
import { test, expect } from './fixtures/workflow.js'
import { loadWorkflowAccounts } from './support/environment.js'
import {
  closeWorkflowRoleSessions,
  openWorkflowRoleSession
} from './support/workflow-fixture.js'

const accounts = loadWorkflowAccounts()
const baseURL = process.env.FLOWABLE_E2E_BASE_URL?.trim() || 'http://127.0.0.1:1024'

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
 * 生成包含监听器、普通来源任务和受控 ALL/ANY 动态多实例任务的可部署 BPMN。
 * @param {{processKey: string, processName: string, formId: string, sourceAssigneeId: string, mode: 'ALL'|'ANY'}} input 流程标识、名称、表单、来源办理人和完成模式。
 * @returns {string} 带 BPMN DI 坐标的 UTF-8 XML 正文。
 */
function buildDynamicMultiInstanceBpmn({ processKey, processName, formId, sourceAssigneeId, mode }) {
  if (!['ALL', 'ANY'].includes(mode)) throw new Error('动态多实例模式必须是 ALL 或 ANY')
  // activityId、节点名称和完成条件共同表达服务端冻结的 ALL/ANY 业务契约。
  const activityId = mode === 'ALL' ? 'allReview' : 'anyReview'
  const sourceName = mode === 'ALL' ? '会签发起审批' : '或签发起审批'
  const activityName = mode === 'ALL' ? '动态会签' : '动态或签'
  const completionCondition = mode === 'ALL'
    ? '${nrOfCompletedInstances == nrOfInstances}'
    : '${nrOfCompletedInstances &gt; 0}'
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://ruoyi.example/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flow_start_source" sourceRef="start" targetRef="sourceReview" />
    <userTask id="sourceReview" name="${sourceName}" flowable:assignee="${sourceAssigneeId}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_source_dynamic" sourceRef="sourceReview" targetRef="${activityId}" />
    <userTask id="${activityId}" name="${activityName}" flowable:assignee="\${assignee}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
      <multiInstanceLoopCharacteristics flowable:collection="\${multiInstanceHandler.getUserIds(execution)}" flowable:elementVariable="assignee">
        <completionCondition xsi:type="tFormalExpression">${completionCondition}</completionCondition>
      </multiInstanceLoopCharacteristics>
    </userTask>
    <sequenceFlow id="flow_dynamic_end" sourceRef="${activityId}" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="120" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_source" bpmnElement="sourceReview"><omgdc:Bounds x="240" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_dynamic" bpmnElement="${activityId}"><omgdc:Bounds x="420" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="610" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_source" bpmnElement="flow_start_source"><omgdi:waypoint x="156" y="190" /><omgdi:waypoint x="240" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_source_dynamic" bpmnElement="flow_source_dynamic"><omgdi:waypoint x="340" y="190" /><omgdi:waypoint x="420" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_dynamic_end" bpmnElement="flow_dynamic_end"><omgdi:waypoint x="520" y="190" /><omgdi:waypoint x="610" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 创建、保存并部署真实 ALL/ANY 动态多实例流程模型。
 * @param {import('@playwright/test').Page} page 设计者页面。
 * @param {{processKey: string, processName: string, categoryCode: string, formId: string, sourceAssigneeId: string, mode: 'ALL'|'ANY', resourceRegistry: object}} input 模型、BPMN 参数与清理登记簿。
 * @returns {Promise<{modelId: string, deploymentId: string}>} 正式模型和部署主键。
 */
async function createAndDeployMultiInstanceModel(page, input) {
  const created = await callWorkflowApi(page, 'POST', '/workflow/model', {
    data: {
      modelName: input.processName,
      modelKey: input.processKey,
      category: input.categoryCode,
      description: 'P3 动态多实例真实浏览器验收',
      formType: 0,
      formId: Number(input.formId)
    }
  })
  const modelId = String(created.data?.modelId || '')
  expect(modelId, '模型创建必须返回正式主键').not.toBe('')
  // 保存或部署失败前先登记模型，避免正式 ACT_DE_MODEL 半成品泄漏。
  input.resourceRegistry.modelId = modelId
  await callWorkflowApi(page, 'POST', '/workflow/model/save', {
    data: {
      requestId: randomUUID(),
      modelId,
      bpmnXml: buildDynamicMultiInstanceBpmn(input),
      newVersion: false
    }
  })
  const deployed = await callWorkflowApi(page, 'POST', '/workflow/model/deploy', {
    query: { modelId }
  })
  const deploymentId = String(deployed.data?.deploymentId || '')
  expect(deploymentId, '模型部署必须返回正式主键').not.toBe('')
  input.resourceRegistry.deploymentId = deploymentId
  return { modelId, deploymentId }
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
 * @param {object} resourceRegistry 清理登记簿，start 成功后立即写入 processInstanceId。
 * @returns {Promise<string>} 正式流程实例主键。
 */
async function startDynamicProcessThroughUi(
  page,
  definition,
  formName,
  businessKey,
  subject,
  resourceRegistry
) {
  await page.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
  await expect(page.getByRole('heading', { name: formName })).toBeVisible()
  await page.getByPlaceholder('可选').fill(businessKey)
  await page.getByPlaceholder('请输入申请主题').fill(subject)
  const responsePromise = page.waitForResponse(response => {
    const url = new URL(response.url())
    return url.pathname.includes('/workflow/process/start/')
      && response.request().method() === 'POST'
  })
  await page.getByRole('button', { name: '提交申请', exact: true }).click()
  const payload = await expectWorkflowResponseSuccess(
    await responsePromise, '/workflow/process/start/{processDefinitionId}')
  const processInstanceId = String(
    payload.data?.id || payload.data?.processInstanceId || payload.data?.procInsId || '')
  expect(processInstanceId, '流程发起结果必须返回正式实例主键').not.toBe('')
  // start 已经提交成功，先登记实例再等待前端导航，导航失败时仍由 finally 终止并删除历史。
  resourceRegistry.processInstanceId = processInstanceId
  await expect(page).toHaveURL(new RegExp(`/workflow/process-detail/${processInstanceId}(?:[/?]|$)`))
  return processInstanceId
}

/**
 * 在 Element Plus 多选器中选择两个真实审批用户。
 * @param {import('@playwright/test').Page} page 审批详情页面。
 * @param {import('@playwright/test').Locator} dialog 当前通过任务对话框。
 * @param {'会签办理人'|'或签办理人'} fieldLabel 部署模型投影的下一办理人字段标签。
 * @param {string[]} optionLabels 服务端身份目录返回的完整显示标签。
 * @returns {Promise<void>} 两个办理人均显示为已选后结束。
 */
async function selectApprovalUsers(page, dialog, fieldLabel, optionLabels) {
  const formItem = dialog.locator('.el-form-item').filter({ hasText: fieldLabel })
  const select = formItem.locator('.el-select')
  await select.click()
  for (const label of optionLabels) {
    const option = page.locator('.el-select-dropdown:visible').getByText(label, { exact: true })
    await expect(option, `审批资格目录必须包含 ${label}`).toBeVisible()
    await option.click()
  }
  await page.keyboard.press('Escape')
  for (const label of optionLabels) await expect(formItem).toContainText(label)
}

/**
 * 通过来源任务页面选择动态多实例成员并完成初始化动作。
 * @param {import('@playwright/test').Page} page 来源任务办理人页面。
 * @param {'ALL'|'ANY'} mode 动态多实例完成模式，决定页面字段必须显示会签或或签。
 * @param {string[]} optionLabels 服务端审批资格目录返回的成员标签。
 * @param {string} comment 来源审批意见。
 * @returns {Promise<void>} 完成接口成功且动态成员初始化事务提交后结束。
 */
async function completeSourceTaskThroughUi(page, mode, optionLabels, comment) {
  await page.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '通过任务' })
  await expect(dialog).toBeVisible()
  await selectApprovalUsers(page, dialog, mode === 'ALL' ? '会签办理人' : '或签办理人', optionLabels)
  await dialog.getByPlaceholder('请输入审批意见').fill(comment)
  const responsePromise = page.waitForResponse(response => matchesWorkflowResponse(
    response, '/workflow/task/complete'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectWorkflowResponseSuccess(await responsePromise, '/workflow/task/complete')
  await expect(dialog).toBeHidden()
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
 * @param {{processInstanceId?: string, deploymentId?: string, modelId?: string, formId?: string, categoryId?: string}} resources 已成功创建的资源主键。
 * @returns {Promise<string[]>} 脱敏后的清理错误集合。
 */
async function cleanupFixture(pages, resources) {
  const errors = []
  /**
   * 执行单个清理动作并收集脱敏错误，后续资源仍继续按引用顺序清理。
   * @param {string} label 清理动作名称。
   * @param {() => Promise<unknown>} action 正式 API 清理动作。
   * @returns {Promise<void>} 动作完成或错误已收集后结束。
   */
  const attempt = async (label, action) => {
    try { await action() } catch (error) { errors.push(`${label}: ${redactE2ESecrets(error)}`) }
  }
  if (pages.admin && resources.processInstanceId) {
    let detail = null
    await attempt('查询待清理流程', async () => {
      detail = await callWorkflowApi(pages.admin, 'GET', '/workflow/process/detail', {
        query: { procInsId: resources.processInstanceId }
      })
    })
    if (detail?.data?.processStatus === 'suspended') {
      await attempt('激活待清理流程', () => callWorkflowApi(
        pages.admin, 'POST', '/workflow/instance/updateState', {
          data: { instanceId: resources.processInstanceId, state: 'ACTIVE' }
        }))
    }
    if (['running', 'suspended'].includes(detail?.data?.processStatus)) {
      await attempt('终止待清理流程', () => callWorkflowApi(
        pages.admin, 'POST', '/workflow/instance/terminate', {
          data: { instanceId: resources.processInstanceId, reason: 'P3 E2E 失败清理' }
        }))
    }
    await attempt('删除流程历史', () => callWorkflowApi(
      pages.admin, 'DELETE', `/workflow/process/instance/${encodeURIComponent(resources.processInstanceId)}`))
  }
  if (pages.designer && resources.deploymentId) {
    await attempt('删除流程部署', () => callWorkflowApi(
      pages.designer, 'DELETE', `/workflow/deploy/${encodeURIComponent(resources.deploymentId)}`))
  }
  if (pages.designer && resources.modelId) {
    await attempt('删除流程模型', () => callWorkflowApi(
      pages.designer, 'DELETE', `/workflow/model/${encodeURIComponent(resources.modelId)}`))
  }
  if (pages.designer && resources.formId) {
    await attempt('删除流程表单', () => callWorkflowApi(
      pages.designer, 'DELETE', `/workflow/form/${encodeURIComponent(resources.formId)}`))
  }
  if (pages.designer && resources.categoryId) {
    await attempt('删除流程分类', () => callWorkflowApi(
      pages.designer, 'DELETE', `/workflow/category/${encodeURIComponent(resources.categoryId)}`))
  }
  return errors
}

test('ANY 动态或签首人完成即原子结束，并拒绝无办理权限用户且零副作用', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  const runId = `p3any_${Date.now()}`
  const resources = {}
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
    pages.designer = designerSession.page
    pages.starter = starterSession.page
    pages.approver = approverSession.page
    pages.admin = adminSession.page

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
    Object.assign(resources, await createAndDeployMultiInstanceModel(pages.designer, {
      processKey,
      processName,
      categoryCode,
      formId: resources.formId,
      sourceAssigneeId: approver.value,
      mode: 'ANY',
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
      resources
    )

    // 来源审批先选择一名真实办理人，进入节点后再通过页面加签和减签验证正式动态调整链路。
    const sourceTask = await findAssignedTask(pages.approver, processKey, 'sourceReview')
    expect(sourceTask.processInstanceId).toBe(resources.processInstanceId)
    await pages.approver.goto(`/workflow/process-detail/${encodeURIComponent(resources.processInstanceId)}?taskId=${encodeURIComponent(sourceTask.taskId)}`)
    await expect(pages.approver.getByRole('button', { name: '通过', exact: true })).toBeVisible()
    await completeSourceTaskThroughUi(
      pages.approver, 'ANY', [approver.label], '进入动态或签节点')
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
        canceledSiblingCount: canceledSiblingHistory ? 1 : 0
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

test('ALL 动态会签必须由全部真实成员完成并保持连续 revision 与审计', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  const runId = `p3all_${Date.now()}`
  const resources = {}
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
    Object.assign(resources, await createAndDeployMultiInstanceModel(pages.designer, {
      processKey,
      processName,
      categoryCode,
      formId: resources.formId,
      sourceAssigneeId: approver.value,
      mode: 'ALL',
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
      resources
    )

    // 来源任务一次选择两名真实成员，服务端建立两个并行 execution/task。
    const sourceTask = await findAssignedTask(pages.approver, processKey, 'sourceReview')
    expect(sourceTask.processInstanceId).toBe(resources.processInstanceId)
    await pages.approver.goto(`/workflow/process-detail/${encodeURIComponent(resources.processInstanceId)}?taskId=${encodeURIComponent(sourceTask.taskId)}`)
    await completeSourceTaskThroughUi(
      pages.approver, 'ALL', [approver.label, admin.label], '进入动态会签节点')
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
        naturalCompletionCount: allHistory.filter(node => !node.deleteReason).length
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
