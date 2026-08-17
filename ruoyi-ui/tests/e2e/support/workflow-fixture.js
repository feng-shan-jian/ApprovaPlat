import { randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { expect } from '@playwright/test'
import { loginThroughUi, logoutThroughUi } from '../fixtures/workflow.js'
import { loadWorkflowAccounts } from './environment.js'
import { expectAjaxSuccess, matchesEndpoint } from './http.js'

export const workflowAccounts = loadWorkflowAccounts()
export const workflowBaseURL = process.env.FLOWABLE_E2E_BASE_URL?.trim() || 'http://127.0.0.1:1024'

/**
 * 从失败信息中移除预登记账号、密码和临时 JWT，避免清理异常泄漏认证材料。
 * @param {unknown} value Playwright、浏览器或 HTTP 客户端抛出的原始错误。
 * @returns {string} 仅保留接口、动作和状态信息的脱敏错误文本。
 */
export function redactWorkflowSecrets(value) {
  let text = String(value?.message || value || '')
  Object.values(workflowAccounts).forEach(account => {
    text = text.split(account.username).join('<username>')
      .split(account.password).join('<password>')
  })
  return text
    .replace(/Bearer\s+[A-Za-z0-9._-]+/gi, 'Bearer <token>')
    .replace(/Admin-Token=[A-Za-z0-9._-]+/gi, 'Admin-Token=<token>')
    .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '<token>')
}

/**
 * 通过真实登录页创建一个职责角色会话，不预置 Cookie、Token 或 storageState。
 * @param {import('@playwright/test').Browser} browser Playwright 浏览器实例。
 * @param {string} roleKey 五角色中的目标角色键。
 * @returns {Promise<{context: import('@playwright/test').BrowserContext, page: import('@playwright/test').Page, roleKey: string, pageErrors: string[], consoleErrors: string[], traceStarted: boolean}>} 已完成真实登录并启用脱敏运行期检查的独立会话。
 */
export async function openWorkflowRoleSession(browser, roleKey) {
  const account = workflowAccounts[roleKey]
  if (!account) throw new Error(`未登记 E2E 角色：${roleKey}`)
  const context = await browser.newContext({
    baseURL: workflowBaseURL,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 960 }
  })
  const page = await context.newPage()
  // 错误在进入共享清理集合前立即脱敏，后续断言与失败报告都不接触认证材料原文。
  const pageErrors = []
  const consoleErrors = []
  page.on('pageerror', error => {
    pageErrors.push(redactWorkflowSecrets(error?.stack || error?.message || error))
  })
  page.on('console', message => {
    if (message.type() === 'error') consoleErrors.push(redactWorkflowSecrets(message.text()))
  })
  try {
    await loginThroughUi(page, account)
    // 真实登录后才启动 trace，且关闭全部页面、DOM 和源码采集，避免凭据及业务正文进入证据。
    await context.tracing.start({
      screenshots: false,
      snapshots: false,
      sources: false,
      title: `workflow-role-${roleKey}`
    })
    return { context, page, roleKey, pageErrors, consoleErrors, traceStarted: true }
  } catch (error) {
    const cleanupErrors = []
    const hasToken = (await context.cookies().catch(() => []))
      .some(cookie => cookie.name === 'Admin-Token' && cookie.value)
    if (hasToken) {
      await logoutThroughUi(page, roleKey).catch(cleanupError => {
        cleanupErrors.push(redactWorkflowSecrets(cleanupError))
      })
    }
    await context.close().catch(cleanupError => {
      cleanupErrors.push(redactWorkflowSecrets(cleanupError))
    })
    const failure = new Error(redactWorkflowSecrets(error))
    if (pageErrors.length) failure.message += `；页面异常：${pageErrors.join(' | ')}`
    if (consoleErrors.length) failure.message += `；控制台异常：${consoleErrors.join(' | ')}`
    if (cleanupErrors.length) failure.message += `；失败会话清理异常：${cleanupErrors.join(' | ')}`
    throw failure
  }
}

/**
 * 按页面别名批量创建职责分离角色会话，并在任一登录失败时回收此前已创建的真实登录态。
 * @param {import('@playwright/test').Browser} browser Playwright 浏览器实例。
 * @param {Record<string, string>} roleByPageKey 页面别名到五角色键的映射，插入顺序即登录顺序。
 * @returns {Promise<{sessions: Array<{context: import('@playwright/test').BrowserContext, page: import('@playwright/test').Page, roleKey: string, pageErrors: string[], consoleErrors: string[], traceStarted: boolean}>, pages: Record<string, import('@playwright/test').Page>}>} 可统一清理的会话集合和按别名访问的页面集合。
 */
export async function openWorkflowRoleSessions(browser, roleByPageKey) {
  if (!roleByPageKey || typeof roleByPageKey !== 'object' || Array.isArray(roleByPageKey)
    || Object.keys(roleByPageKey).length === 0) {
    throw new Error('批量登录必须提供非空页面别名与角色映射')
  }
  // sessions 用于按登录逆序注销并关闭上下文；pages 仅暴露调用方约定的业务页面别名。
  const sessions = []
  const pages = {}
  try {
    for (const [pageKey, roleKey] of Object.entries(roleByPageKey)) {
      const session = await openWorkflowRoleSession(browser, roleKey)
      sessions.push(session)
      pages[pageKey] = session.page
    }
    return { sessions, pages }
  } catch (error) {
    // 批量创建未完成时由共享清理链立即回收已登录角色，调用方无需处理半成品集合。
    const cleanupErrors = await closeWorkflowRoleSessions(sessions)
    const failure = new Error(redactWorkflowSecrets(error))
    if (cleanupErrors.length) failure.message += `；批量登录回收异常：${cleanupErrors.join(' | ')}`
    throw failure
  }
}

/**
 * 使用页面真实登录产生的 JWT 调用正式后端 API，并同时校验 HTTP 与 AjaxResult 业务码。
 * @param {import('@playwright/test').Page} page 已登录角色页面。
 * @param {'GET'|'POST'|'PUT'|'DELETE'} method HTTP 方法。
 * @param {string} path 不含 `/dev-api` 前缀的工作流业务路径。
 * @param {{query?: Record<string, unknown>, data?: unknown, expectedCode?: number}} options 查询参数、请求体和期望业务码。
 * @returns {Promise<any>} 已通过传输层和业务码校验的 JSON 响应。
 */
export async function callWorkflowApi(page, method, path, options = {}) {
  const tokenCookie = (await page.context().cookies()).find(cookie => cookie.name === 'Admin-Token')
  if (!tokenCookie?.value) throw new Error('真实登录会话缺少 Admin-Token')
  const url = new URL(`/dev-api${path}`, workflowBaseURL)
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
    throw new Error(`${method} ${path} 请求失败：${redactWorkflowSecrets(error)}`)
  }
  expect(response.status(), `${method} ${path} 传输层状态`).toBe(200)
  const payload = await response.json()
  // 失败证据仅附加经脱敏的业务消息，不在报告中暴露 Token 或账号密码。
  const responseMessage = redactWorkflowSecrets(payload.msg || '')
  const assertionLabel = responseMessage
    ? `${method} ${path} 业务码：${responseMessage}`
    : `${method} ${path} 业务码`
  expect(payload.code, assertionLabel).toBe(options.expectedCode ?? 200)
  return payload
}

/**
 * 按流程标识查询指定工作台，并定位包含唯一业务主键的正式表格行。
 * @param {import('@playwright/test').Page} page 当前职责角色页面。
 * @param {string} route 工作台前端路由。
 * @param {string} endpoint 工作台正式列表接口。
 * @param {string} processKey 流程定义标识筛选值。
 * @param {string} businessKey 场景唯一业务主键。
 * @returns {Promise<import('@playwright/test').Locator>} 唯一匹配的 Element Plus 表格行。
 */
export async function findWorkflowTableRow(page, route, endpoint, processKey, businessKey) {
  await page.goto(route)
  const processKeyInput = page.getByPlaceholder('请输入流程标识')
  await expect(processKeyInput).toBeVisible()
  await processKeyInput.fill(processKey)
  const responsePromise = page.waitForResponse(response => {
    if (!matchesEndpoint(response, endpoint, 'GET')) return false
    return new URL(response.url()).searchParams.get('processKey') === processKey
  })
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, endpoint)
  const rows = page.locator('.el-table__body tbody tr').filter({ hasText: businessKey })
  await expect(rows, '工作台必须仅返回本场景业务对象').toHaveCount(1)
  return rows.first()
}

/**
 * 从正式身份目录定位预登记账号对应的唯一用户选项。
 * @param {import('@playwright/test').Page} page 有身份目录查询权限的页面。
 * @param {string} roleKey 用于定位账号但不会写入失败报告的职责角色键。
 * @param {boolean} approvalOnly 是否要求后端按审批办理资格过滤。
 * @returns {Promise<{value: string, label: string, type: string}|null>} 唯一匹配用户；不具备资格时返回 null。
 */
export async function findWorkflowUserOption(page, roleKey, approvalOnly = true) {
  const account = workflowAccounts[roleKey]
  if (!account) throw new Error(`未登记 E2E 角色：${roleKey}`)
  const payload = await callWorkflowApi(page, 'GET', '/workflow/identity/options', {
    query: {
      type: 'user',
      capability: approvalOnly ? 'approval' : undefined,
      keyword: account.username,
      pageNum: 1,
      pageSize: 20
    }
  })
  const matches = (payload.rows || []).filter(option => option?.type === 'user'
    && String(option.label || '').endsWith(`(${account.username})`))
  expect(matches.length, `角色 ${roleKey} 的身份目录结果必须唯一`).toBeLessThanOrEqual(1)
  return matches[0] || null
}

/**
 * 创建流程分类并从正式列表回查服务端生成的主键。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {string} name 唯一分类名称。
 * @param {string} code 唯一分类编码。
 * @param {{categoryId?: string}} resourceRegistry finally 清理使用的正式资源登记簿。
 * @returns {Promise<string>} 正式分类主键。
 */
export async function createWorkflowCategory(page, name, code, resourceRegistry) {
  if (!resourceRegistry) throw new Error('分类创建前必须提供正式资源登记簿')
  const created = await callWorkflowApi(page, 'POST', '/workflow/category', {
    data: { categoryName: name, code, remark: 'P3 普通审批生命周期真实浏览器验收' }
  })
  const categoryId = String(created.data?.categoryId || '')
  // POST 已成功落库时先登记返回主键，再执行任何回查断言，保证断言失败也可正式删除。
  if (categoryId) resourceRegistry.categoryId = categoryId
  expect(categoryId, '分类创建必须返回正式主键').not.toBe('')
  const payload = await callWorkflowApi(page, 'GET', '/workflow/category/list', {
    query: { categoryName: name, code, pageNum: 1, pageSize: 20 }
  })
  const rows = (payload.rows || []).filter(row => row.categoryName === name && row.code === code)
  expect(rows, '新建分类必须可从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].categoryId), '分类回查主键必须与创建响应一致').toBe(categoryId)
  return categoryId
}

/**
 * 创建开始表单并从正式列表回查服务端生成的主键。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {string} name 唯一表单名称。
 * @param {{formId?: string}} resourceRegistry finally 清理使用的正式资源登记簿。
 * @returns {Promise<string>} 正式表单主键。
 */
export async function createWorkflowForm(page, name, resourceRegistry) {
  if (!resourceRegistry) throw new Error('表单创建前必须提供正式资源登记簿')
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
    data: { formName: name, content, remark: 'P3 普通审批生命周期真实浏览器验收' }
  })
  const formId = String(created.data?.formId || '')
  // 表单一经正式落库立即登记，后续列表一致性检查失败时仍能由 finally 回收。
  if (formId) resourceRegistry.formId = formId
  expect(formId, '表单创建必须返回正式主键').not.toBe('')
  const payload = await callWorkflowApi(page, 'GET', '/workflow/form/list', {
    query: { formName: name, pageNum: 1, pageSize: 20 }
  })
  const rows = (payload.rows || []).filter(row => row.formName === name)
  expect(rows, '新建表单必须可从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].formId), '表单回查主键必须与创建响应一致').toBe(formId)
  return formId
}

/**
 * 生成含三级静态办理人与完整 userTaskListener 的可部署串行 BPMN。
 * @param {{processKey: string, processName: string, formId: string, approverUserId: string, adminUserId: string}} input 流程、表单和两名办理人的正式主键。
 * @returns {string} 带 BPMN DI 坐标的 UTF-8 XML 正文。
 */
export function buildSequentialLifecycleBpmn({ processKey, processName, formId, approverUserId, adminUserId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://ruoyi.example/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flow_start_a" sourceRef="start" targetRef="reviewA" />
    <userTask id="reviewA" name="一级审批" flowable:assignee="${approverUserId}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_a_b" sourceRef="reviewA" targetRef="reviewB" />
    <userTask id="reviewB" name="二级审批" flowable:assignee="${adminUserId}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_b_c" sourceRef="reviewB" targetRef="reviewC" />
    <userTask id="reviewC" name="三级审批" flowable:assignee="${approverUserId}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_c_end" sourceRef="reviewC" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="80" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_a" bpmnElement="reviewA"><omgdc:Bounds x="180" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_b" bpmnElement="reviewB"><omgdc:Bounds x="350" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_c" bpmnElement="reviewC"><omgdc:Bounds x="520" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="700" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_a" bpmnElement="flow_start_a"><omgdi:waypoint x="116" y="190" /><omgdi:waypoint x="180" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_a_b" bpmnElement="flow_a_b"><omgdi:waypoint x="280" y="190" /><omgdi:waypoint x="350" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_b_c" bpmnElement="flow_b_c"><omgdi:waypoint x="450" y="190" /><omgdi:waypoint x="520" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_c_end" bpmnElement="flow_c_end"><omgdi:waypoint x="620" y="190" /><omgdi:waypoint x="700" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 生成含真实候选用户和完整 userTaskListener 的可部署候选任务 BPMN。
 * @param {{processKey: string, processName: string, formId: string, candidateUserId: string}} input 流程、表单和候选用户正式主键。
 * @returns {string} 带 BPMN DI 坐标的 UTF-8 XML 正文。
 */
export function buildCandidateLifecycleBpmn({ processKey, processName, formId, candidateUserId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://ruoyi.example/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flow_start_candidate" sourceRef="start" targetRef="candidateReview" />
    <userTask id="candidateReview" name="候选审批" flowable:candidateUsers="${candidateUserId}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_candidate_end" sourceRef="candidateReview" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="100" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_candidate" bpmnElement="candidateReview"><omgdc:Bounds x="240" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="440" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_candidate" bpmnElement="flow_start_candidate"><omgdi:waypoint x="136" y="190" /><omgdi:waypoint x="240" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_candidate_end" bpmnElement="flow_candidate_end"><omgdi:waypoint x="340" y="190" /><omgdi:waypoint x="440" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 创建、保存并部署一个真实流程模型。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {{processKey: string, processName: string, categoryCode: string, formId: string, bpmnXml: string, resourceRegistry?: {modelIds: string[], deploymentIds: string[]}}} input 模型、分类、表单、BPMN 正文和可选清理登记簿。
 * @returns {Promise<{modelId: string, deploymentId: string}>} 正式模型和部署主键。
 */
export async function createAndDeployWorkflowModel(page, input) {
  const created = await callWorkflowApi(page, 'POST', '/workflow/model', {
    data: {
      modelName: input.processName,
      modelKey: input.processKey,
      category: input.categoryCode,
      description: 'P3 普通审批生命周期真实浏览器验收',
      formType: 0,
      formId: Number(input.formId)
    }
  })
  const modelId = String(created.data?.modelId || '')
  expect(modelId, '模型创建必须返回正式主键').not.toBe('')
  // 模型一经正式落库立即登记，后续保存或部署失败时 finally 仍可回收半成品。
  if (input.resourceRegistry) input.resourceRegistry.modelIds.push(modelId)
  const modelDetail = await callWorkflowApi(page, 'GET', `/workflow/model/${encodeURIComponent(modelId)}`)
  const expectedBpmnSha256 = String(modelDetail.data?.bpmnSha256 || '')
  expect(expectedBpmnSha256, '模型详情必须返回初始 BPMN 内容摘要').toMatch(/^[0-9a-f]{64}$/)
  await callWorkflowApi(page, 'POST', '/workflow/model/save', {
    data: { modelId, bpmnXml: input.bpmnXml, expectedBpmnSha256, newVersion: false }
  })
  const deployed = await callWorkflowApi(page, 'POST', '/workflow/model/deploy', {
    query: { modelId }
  })
  const deploymentId = String(deployed.data?.deploymentId || '')
  expect(deploymentId, '模型部署必须返回正式主键').not.toBe('')
  // 部署成功后立即登记，后续业务场景失败时按依赖顺序删除定义和历史。
  if (input.resourceRegistry) input.resourceRegistry.deploymentIds.push(deploymentId)
  return { modelId, deploymentId }
}

/**
 * 从可发起列表定位刚部署的唯一流程定义。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @param {string} processKey 唯一流程定义标识。
 * @returns {Promise<{definitionId: string, deploymentId: string}>} 可发起定义与部署关系。
 */
export async function findStartableWorkflowDefinition(page, processKey) {
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
 * 判断浏览器响应是否为申请草稿正式提交接口。
 * @param {import('@playwright/test').Response} response 浏览器捕获的真实响应。
 * @returns {boolean} POST 且路径匹配唯一草稿 UUID 的 submit 接口时返回 true。
 */
function matchesDraftSubmitResponse(response) {
  const pathname = new URL(response.url()).pathname
  return response.request().method() === 'POST'
    && /\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/i.test(pathname)
}

/**
 * 通过当前真实发起页执行一次正式提交，并登记草稿与流程实例供 finally 精确清理。
 * @param {import('@playwright/test').Page} page 已填写完成的真实发起页面。
 * @param {{processInstanceIds: string[], draftFixtures: Array<{draftId: string, processInstanceId: string}>}} resources 本用例正式资源登记簿。
 * @param {{doubleClick?: boolean}} options 是否模拟同步双击以验证写互斥。
 * @returns {Promise<string>} 草稿提交事务创建的唯一 Flowable 流程实例主键。
 */
export async function submitWorkflowStartPage(page, resources, options = {}) {
  if (!Array.isArray(resources?.processInstanceIds) || !Array.isArray(resources?.draftFixtures)) {
    throw new Error('流程发起前必须提供实例与草稿清理登记簿')
  }
  const createEndpoint = '/workflow/process/draft'
  let createRequestCount = 0
  let submitRequestCount = 0
  const countRequest = request => {
    const pathname = new URL(request.url()).pathname
    if (request.method() !== 'POST') return
    if (pathname.endsWith(createEndpoint)) createRequestCount += 1
    if (/\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/i.test(pathname)) submitRequestCount += 1
  }
  page.on('request', countRequest)
  try {
    const createPromise = page.waitForResponse(response => matchesEndpoint(response, createEndpoint, 'POST'))
    const submitPromise = page.waitForResponse(matchesDraftSubmitResponse)
    const submit = page.getByRole('button', { name: '正式提交', exact: true })
    if (options.doubleClick) await submit.evaluate(button => { button.click(); button.click() })
    else await submit.click()

    const created = await expectAjaxSuccess(await createPromise, createEndpoint)
    const draftId = String(created.data?.draftId || created.data?.id || '')
    expect(draftId, '首次正式提交必须先返回可追踪草稿 UUID').toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
    const draftFixture = { draftId, processInstanceId: '' }
    resources.draftFixtures.push(draftFixture)

    const submitResponse = await submitPromise
    const submitPath = new URL(submitResponse.url()).pathname
    const submittedDraftId = decodeURIComponent(submitPath.split('/').at(-2) || '')
    expect(submittedDraftId, '草稿提交路径必须与刚创建的 UUID 一致').toBe(draftId)
    const payload = await expectAjaxSuccess(submitResponse, `/workflow/process/draft/${draftId}/submit`)
    const processInstanceId = String(payload.data?.id
      || payload.data?.processInstanceId
      || payload.data?.procInsId
      || payload.data?.processInstance?.id
      || payload.data?.processInstance?.processInstanceId
      || '')
    draftFixture.processInstanceId = processInstanceId
    if (processInstanceId) resources.processInstanceIds.push(processInstanceId)
    expect(processInstanceId, '草稿提交响应必须返回正式实例主键').not.toBe('')

    if (options.doubleClick) {
      await page.waitForTimeout(500)
      expect(createRequestCount, '同步双击只能创建一个正式申请草稿').toBe(1)
      expect(submitRequestCount, '同步双击只能提交一次正式申请草稿').toBe(1)
    }
    return processInstanceId
  } finally {
    page.off('request', countRequest)
  }
}

/**
 * 通过真实发起页面提交部署表单并取得正式流程实例主键。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @param {{definitionId: string, deploymentId: string}} definition 已部署定义关系。
 * @param {string} formName 页面必须展示的部署表单名称。
 * @param {string} businessKey 本场景唯一业务主键。
 * @param {string} subject 本场景申请主题。
 * @param {{processInstanceIds: string[], draftFixtures: Array<{draftId: string, processInstanceId: string}>}} resources finally 清理使用的正式资源登记簿。
 * @returns {Promise<string>} 正式流程实例主键。
 */
export async function startWorkflowThroughUi(
  page,
  definition,
  formName,
  businessKey,
  subject,
  resources
) {
  await page.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
  await expect(page.getByRole('heading', { name: formName })).toBeVisible()
  await page.getByPlaceholder('可选').fill(businessKey)
  await page.getByPlaceholder('请输入申请主题').fill(subject)
  const processInstanceId = await submitWorkflowStartPage(page, resources)
  await expect(page).toHaveURL(/\/workflow\/process-detail\/[^/?]+/)
  const routedProcessInstanceId = decodeURIComponent(new URL(page.url()).pathname.split('/').pop())
  expect(routedProcessInstanceId, '详情路由实例主键必须与发起响应一致').toBe(processInstanceId)
  return processInstanceId
}

/**
 * 在唯一白名单隔离库执行固定草稿清理 SQL，数据库凭据只通过子进程环境传递。
 * @param {string} sql 仅包含固定表名与已校验主键的 SQL。
 * @returns {string[]} mysql 返回的非敏感结果行。
 */
function runWorkflowFixtureMysql(sql) {
  const jdbcUrl = String(process.env.FLOWABLE_RBAC_JDBC_URL || '')
  const connection = /^jdbc:mysql:\/\/(127\.0\.0\.1|localhost):(\d+)\/([^?]+)(?:\?.*)?$/.exec(jdbcUrl)
  if (!connection) throw new Error('流程 E2E 清理 JDBC 地址不合法')
  const database = connection[3]
  const disposableSchema = String(process.env.FLOWABLE_E2E_DISPOSABLE_SCHEMA || '').trim()
  const sharedAllowed = database === 'ry_vue_codex_flowable_it'
  const disposableAllowed = /^ry_vue_codex_fieldperm_[a-z0-9_]+$/.test(database)
    && database === disposableSchema
  if (!sharedAllowed && !disposableAllowed) {
    throw new Error('流程 E2E 清理数据库不在唯一白名单 schema')
  }
  const username = String(process.env.FLOWABLE_RBAC_DB_USERNAME || '').trim()
  const password = String(process.env.FLOWABLE_RBAC_DB_PASSWORD || '')
  if (!/^[A-Za-z0-9_]{1,32}$/.test(username) || !password) {
    throw new Error('流程 E2E 清理缺少数据库运行账号')
  }
  const command = String(process.env.FLOWABLE_E2E_MYSQL_COMMAND || 'mysql').trim()
  const result = spawnSync(command, [
    `--host=${connection[1]}`,
    `--port=${connection[2]}`,
    `--user=${username}`,
    `--database=${database}`,
    '--default-character-set=utf8mb4',
    '--batch',
    '--skip-column-names'
  ], {
    env: { ...process.env, MYSQL_PWD: password },
    input: sql,
    encoding: 'utf8',
    shell: false,
    windowsHide: true
  })
  if (result.status !== 0) throw new Error('流程 E2E 草稿清理 SQL 执行失败')
  return String(result.stdout || '').split(/\r?\n/).filter(Boolean)
}

/**
 * 从当前 E2E 隔离库读取唯一运行时布尔变量，证明受控扩展结果已由 Flowable 正式持久化。
 * @param {string} processInstanceId Flowable 流程实例主键。
 * @param {string} variableName 需要核验的流程变量名。
 * @returns {{count:number,type:string,longValue:number}} 唯一变量行数、Flowable 类型和布尔数值投影。
 */
export function readWorkflowRuntimeBooleanVariable(processInstanceId, variableName) {
  const instance = String(processInstanceId || '')
  const name = String(variableName || '')
  if (!/^[A-Za-z0-9:_-]{1,64}$/.test(instance)
      || !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(name)) {
    throw new Error('流程 E2E 变量证据主键或变量名不合法')
  }
  const rows = runWorkflowFixtureMysql(
    `SELECT COUNT(*), COALESCE(MAX(TYPE_), ''), COALESCE(MAX(LONG_), -1) `
      + `FROM ACT_RU_VARIABLE WHERE PROC_INST_ID_='${instance}' AND NAME_='${name}';\n`)
  if (rows.length !== 1) throw new Error('流程 E2E 运行变量证据行数不唯一')
  const [countText, type, longValueText] = rows[0].split('\t')
  const count = Number(countText)
  const longValue = Number(longValueText)
  if (!Number.isInteger(count) || !Number.isInteger(longValue)) {
    throw new Error('流程 E2E 运行变量证据格式不合法')
  }
  return { count, type, longValue }
}

/**
 * 精确删除本轮发起页创建的草稿当前状态，解除流程历史与部署清理前的正式引用。
 * @param {{draftId: string, processInstanceId?: string}} fixture 草稿 UUID 与可选已提交实例关系。
 * @returns {Promise<void>} 草稿状态、实例关系和删除计数全部一致后结束。
 */
async function purgeWorkflowDraftFixture(fixture) {
  const draftId = String(fixture?.draftId || '')
  const processInstanceId = String(fixture?.processInstanceId || '')
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(draftId)
      || (processInstanceId && !/^[A-Za-z0-9:_-]{1,64}$/.test(processInstanceId))) {
    throw new Error('流程 E2E 草稿清理主键不合法')
  }
  const draftLiteral = draftId.replaceAll("'", "''")
  const instanceLiteral = processInstanceId.replaceAll("'", "''")
  const rows = runWorkflowFixtureMysql(
    `SELECT draft_status, revision_no, COALESCE(submitted_process_instance_id, '') `
      + `FROM wf_process_draft WHERE draft_id='${draftLiteral}';\n`)
  if (rows.length !== 1) throw new Error('流程 E2E 草稿当前状态不唯一')
  const [status, revisionText, storedInstanceId] = rows[0].split('\t')
  const revision = Number(revisionText)
  const submitted = status === 'SUBMITTED'
  if (!['ACTIVE', 'DELETED', 'SUBMITTED'].includes(status)
      || (submitted ? (!processInstanceId || storedInstanceId !== processInstanceId) : storedInstanceId)
      || !Number.isInteger(revision) || revision < 1) {
    throw new Error('流程 E2E 草稿记录不满足精确清理门禁')
  }
  const statusPredicate = submitted
    ? `draft_status='SUBMITTED' AND submitted_process_instance_id='${instanceLiteral}'`
    : `draft_status IN ('ACTIVE','DELETED') AND submitted_process_instance_id IS NULL`
  const deleted = runWorkflowFixtureMysql(
    `DELETE FROM wf_process_draft WHERE draft_id='${draftLiteral}' AND ${statusPredicate};\n`
      + `SELECT ROW_COUNT();\n`)
  if (deleted.at(-1) !== '1') throw new Error('流程 E2E 草稿条件删除计数不一致')
}

/**
 * 从当前办理人的正式待办列表定位指定实例和 BPMN 节点。
 * @param {import('@playwright/test').Page} page 当前办理人页面。
 * @param {string} processKey 流程定义标识。
 * @param {string} taskDefinitionKey BPMN 用户任务节点标识。
 * @param {string} processInstanceId 流程实例主键。
 * @returns {Promise<any>} 唯一活动待办行。
 */
export async function findAssignedWorkflowTask(page, processKey, taskDefinitionKey, processInstanceId) {
  const payload = await callWorkflowApi(page, 'GET', '/workflow/process/todoList', {
    query: { processKey, pageNum: 1, pageSize: 100 }
  })
  const rows = (payload.rows || []).filter(row => row.processKey === processKey
    && row.taskDefinitionKey === taskDefinitionKey
    && String(row.processInstanceId) === String(processInstanceId))
  expect(rows, `节点 ${taskDefinitionKey} 必须产生唯一真实待办`).toHaveLength(1)
  expect(String(rows[0].taskId || ''), '待办任务主键不能为空').not.toBe('')
  return rows[0]
}

/**
 * 从当前候选人的正式待签列表定位指定实例和 BPMN 节点。
 * @param {import('@playwright/test').Page} page 当前候选人页面。
 * @param {string} processKey 流程定义标识。
 * @param {string} taskDefinitionKey BPMN 用户任务节点标识。
 * @param {string} processInstanceId 流程实例主键。
 * @returns {Promise<any>} 唯一可认领任务行。
 */
export async function findClaimableWorkflowTask(page, processKey, taskDefinitionKey, processInstanceId) {
  const payload = await callWorkflowApi(page, 'GET', '/workflow/process/claimList', {
    query: { processKey, pageNum: 1, pageSize: 100 }
  })
  const rows = (payload.rows || []).filter(row => row.processKey === processKey
    && row.taskDefinitionKey === taskDefinitionKey
    && String(row.processInstanceId) === String(processInstanceId))
  expect(rows, `节点 ${taskDefinitionKey} 必须产生唯一真实待签`).toHaveLength(1)
  expect(String(rows[0].taskId || ''), '待签任务主键不能为空').not.toBe('')
  return rows[0]
}

/**
 * 从当前用户的正式已办列表定位指定实例和 BPMN 节点。
 * @param {import('@playwright/test').Page} page 当前历史办理人页面。
 * @param {string} processKey 流程定义标识。
 * @param {string} taskDefinitionKey BPMN 用户任务节点标识。
 * @param {string} processInstanceId 流程实例主键。
 * @returns {Promise<any>} 唯一已办任务行。
 */
export async function findCompletedWorkflowTask(page, processKey, taskDefinitionKey, processInstanceId) {
  const payload = await callWorkflowApi(page, 'GET', '/workflow/process/finishedList', {
    query: { processKey, pageNum: 1, pageSize: 100 }
  })
  const rows = (payload.rows || []).filter(row => row.processKey === processKey
    && row.taskDefinitionKey === taskDefinitionKey
    && String(row.processInstanceId) === String(processInstanceId))
  expect(rows, `节点 ${taskDefinitionKey} 必须产生唯一真实已办`).toHaveLength(1)
  return rows[0]
}

/**
 * 查询对象授权后的流程详情，可选任务主键用于回读活动或历史任务快照。
 * @param {import('@playwright/test').Page} page 有实例读取权限的页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} taskId 可选活动或历史任务主键。
 * @returns {Promise<any>} 正式流程详情 data 对象。
 */
export async function getWorkflowDetail(page, processInstanceId, taskId = '') {
  const payload = await callWorkflowApi(page, 'GET', '/workflow/process/detail', {
    query: { procInsId: processInstanceId, taskId: taskId || undefined }
  })
  return payload.data
}

/**
 * 深复制正式返回值并固定对象键顺序，不省略任何业务字段。
 * @param {unknown} value 流程详情、变量、附件或抄送返回值。
 * @returns {unknown} 对象键稳定排序的可比较值。
 */
function canonicalWorkflowValue(value) {
  if (Array.isArray(value)) return value.map(item => canonicalWorkflowValue(item))
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(Object.entries(value)
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, nestedValue]) => [key, canonicalWorkflowValue(nestedValue)]))
}

/**
 * 仅移除尚未结束实例及活动节点的实时耗时，保留固定耗时和同名表单变量。
 * @param {any} detail 正式流程详情 data 对象。
 * @returns {any} 可严格比较且不受查询时刻影响的完整详情快照。
 */
function canonicalWorkflowDetail(detail) {
  const snapshot = canonicalWorkflowValue(detail)
  if (!snapshot || typeof snapshot !== 'object') return snapshot
  if (!snapshot.endTime) delete snapshot.durationMillis
  for (const node of snapshot.historyProcNodeList || []) {
    if (!node.endTime) delete node.durationMillis
  }
  return snapshot
}

/**
 * 从部署表单 schema 中提取附件字段变量名，避免把普通 UUID 业务字段误当附件查询。
 * @param {any[]} forms 当前任务表单与历史表单快照集合。
 * @returns {Set<string>} 所有 `el-upload` 字段对应的变量名。
 */
function collectAttachmentFieldNames(forms) {
  const fieldNames = new Set()
  for (const form of forms) {
    let template
    try {
      template = JSON.parse(String(form?.content || '{}'))
    } catch {
      throw new Error('流程表单快照不是合法 JSON')
    }
    const pendingFields = [...(Array.isArray(template?.fields) ? template.fields : [])]
    while (pendingFields.length) {
      const field = pendingFields.shift()
      if (!field || typeof field !== 'object') continue
      const config = field.__config__ || {}
      const tag = config.tag || field.tag || field.type
      const variable = field.__vModel__ || field.variable
      if (tag === 'el-upload' && typeof variable === 'string' && variable.trim()) {
        fieldNames.add(variable.trim())
      }
      if (Array.isArray(config.children)) pendingFields.push(...config.children)
      if (Array.isArray(field.children)) pendingFields.push(...field.children)
    }
  }
  return fieldNames
}

/**
 * 从表单白名单值中提取经过格式校验的附件 UUID。
 * @param {Array<Record<string, unknown>>} valueSources 当前及历史表单值和安全变量集合。
 * @param {Set<string>} fieldNames 部署 schema 声明的附件字段名。
 * @returns {string[]} 去重并排序后的附件主键。
 */
function collectAttachmentIds(valueSources, fieldNames) {
  const attachmentIds = new Set()
  for (const values of valueSources) {
    for (const fieldName of fieldNames) {
      const rawValue = values?.[fieldName]
      const items = Array.isArray(rawValue) ? rawValue : rawValue == null ? [] : [rawValue]
      for (const item of items) {
        const attachmentId = String(typeof item === 'string' ? item : item?.attachmentId || '').trim()
        if (!attachmentId) continue
        if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(attachmentId)) {
          throw new Error(`附件字段 ${fieldName} 包含非法主键`)
        }
        attachmentIds.add(attachmentId)
      }
    }
  }
  return [...attachmentIds].sort()
}

/**
 * 查询一个抄送接收角色对目标实例和任务正式可读的完整抄送状态。
 * @param {import('@playwright/test').Page} page 具备抄送列表权限的真实角色页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} taskId 当前完整状态快照关联的活动或终态历史任务主键。
 * @returns {Promise<object>} 实例范围和任务范围的总数及稳定排序行。
 */
async function readStableCopyState(page, processInstanceId, taskId) {
  const instancePayload = await callWorkflowApi(page, 'GET', '/workflow/process/copyList', {
    query: { instanceId: processInstanceId, pageNum: 1, pageSize: 200 }
  })
  const taskPayload = await callWorkflowApi(page, 'GET', '/workflow/process/copyList', {
    query: { instanceId: processInstanceId, taskId, pageNum: 1, pageSize: 200 }
  })
  /**
   * 校验精确筛选结果未被分页截断并固定行顺序。
   * @param {any} payload TableDataInfo 正式响应。
   * @param {string} label 实例或任务查询范围标签。
   * @returns {{total: number, rows: any[]}} 可做严格深比较的完整分页。
  */
  const normalizePage = (payload, label) => {
    expect(Array.isArray(payload.rows), `${label} 抄送查询必须返回正式行集合`).toBe(true)
    const rows = payload.rows
    const total = Number(payload.total)
    expect(Number.isSafeInteger(total) && total >= 0, `${label} 抄送查询必须返回有效总数`).toBe(true)
    expect(total, `${label} 抄送查询必须在单页完整返回`).toBe(rows.length)
    return {
      total,
      rows: rows.map(row => canonicalWorkflowValue(row))
        .sort((left, right) => String(left.copyId).localeCompare(String(right.copyId)))
    }
  }
  return {
    instance: normalizePage(instancePayload, '实例'),
    task: normalizePage(taskPayload, '任务')
  }
}

/**
 * 读取拒绝动作前后的全部正式可读可变状态，除持续增长耗时外不省略详情字段。
 * @param {import('@playwright/test').Page} auditPage 可读取实例、任务变量和附件的管理员页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} taskId 活动或历史任务主键。
 * @param {import('@playwright/test').Page[]} copyPages 具备当前用户抄送列表权限的角色页面。
 * @returns {Promise<object>} 详情、变量、附件元数据及各接收角色抄送状态的稳定快照。
 */
async function stableWorkflowSnapshot(auditPage, processInstanceId, taskId, copyPages) {
  const detail = await getWorkflowDetail(auditPage, processInstanceId, taskId)
  expect(String(detail?.currentTask?.taskId || ''), '详情快照必须回读指定活动或历史任务').toBe(String(taskId))
  const variablePayload = await callWorkflowApi(
    auditPage,
    'GET',
    `/workflow/task/processVariables/${encodeURIComponent(taskId)}`
  )
  const safeVariables = variablePayload.data
  expect(safeVariables && typeof safeVariables === 'object' && !Array.isArray(safeVariables),
    '任务变量接口必须返回正式安全字段映射').toBe(true)
  const forms = [detail?.currentTaskForm, ...(detail?.processFormList || [])].filter(Boolean)
  const attachmentFieldNames = collectAttachmentFieldNames(forms)
  const attachmentIds = collectAttachmentIds(
    [...forms.map(form => form.values || {}), safeVariables],
    attachmentFieldNames
  )
  const attachments = []
  for (const attachmentId of attachmentIds) {
    const payload = await callWorkflowApi(
      auditPage,
      'GET',
      `/workflow/attachment/${encodeURIComponent(attachmentId)}`
    )
    expect(String(payload.data?.attachmentId || ''), '附件元数据必须与表单引用主键一致').toBe(attachmentId)
    attachments.push(canonicalWorkflowValue(payload.data))
  }
  const readableCopyPages = [...new Set([auditPage, ...(copyPages || [])])]
  const copies = []
  for (const page of readableCopyPages) {
    copies.push(await readStableCopyState(page, processInstanceId, taskId))
  }
  return {
    detail: canonicalWorkflowDetail(detail),
    safeVariables: canonicalWorkflowValue(safeVariables),
    attachments,
    copies
  }
}

/**
 * 执行预期被拒绝的真实写请求，并确认流程状态、任务历史和审计意见均未改变。
 * @param {import('@playwright/test').Page} auditPage 可读取完整实例的管理员页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} snapshotTaskId 用于完整状态回读的当前活动任务；终态无活动任务时使用目标历史任务。
 * @param {number} expectedCode 预期稳定业务码，通常为 403 或 409。
 * @param {(expectedCode: number) => Promise<unknown>} action 发起真实拒绝请求的回调。
 * @param {import('@playwright/test').Page[]} copyPages 可读取各自抄送记录的职责角色页面。
 * @returns {Promise<void>} 业务码和前后快照均符合预期后结束。
 */
export async function expectRejectedWorkflowActionWithoutSideEffects(
  auditPage,
  processInstanceId,
  snapshotTaskId,
  expectedCode,
  action,
  copyPages = []
) {
  const before = await stableWorkflowSnapshot(
    auditPage, processInstanceId, snapshotTaskId, copyPages)
  await action(expectedCode)
  const after = await stableWorkflowSnapshot(
    auditPage, processInstanceId, snapshotTaskId, copyPages)
  expect(after, `业务码 ${expectedCode} 的拒绝动作不得改变任何正式可读状态`).toEqual(before)
}

/**
 * 从结构化 Flowable comment 中读取服务端固定动作编码。
 * @param {unknown} message 流程详情返回的受控 comment 正文。
 * @returns {{action?: string, opinion?: string}|null} 可解析审计对象；非 JSON 正文返回 null。
 */
function parseAuditMessage(message) {
  try {
    const parsed = JSON.parse(String(message || ''))
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

/**
 * 从正式流程详情确认指定任务只有一条完全符合类型、操作人和目标关系的审计 comment。
 * @param {import('@playwright/test').Page} page 有实例读取权限的页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {{taskId: string, type: string, action: string, actorUserId: string, opinion: string, targetUserId?: string, targetNodeKey?: string, sourceTaskId?: string}} expected 任务、comment 类型及服务端审计 JSON 的完整期望契约。
 * @returns {Promise<void>} 唯一审计及外层 Flowable 操作人均精确匹配后结束。
 */
export async function expectWorkflowAudit(page, processInstanceId, expected) {
  const expectedAudit = {
    action: expected.action,
    actorUserId: String(expected.actorUserId),
    opinion: expected.opinion
  }
  if (Object.hasOwn(expected, 'targetUserId')) expectedAudit.targetUserId = String(expected.targetUserId)
  if (Object.hasOwn(expected, 'targetNodeKey')) expectedAudit.targetNodeKey = expected.targetNodeKey
  if (Object.hasOwn(expected, 'sourceTaskId')) expectedAudit.sourceTaskId = String(expected.sourceTaskId)

  const detail = await getWorkflowDetail(page, processInstanceId, expected.taskId)
  const matches = (detail.historyProcNodeList || [])
    .flatMap(node => node.comments || [])
    .map(comment => ({ comment, audit: parseAuditMessage(comment.message) }))
    .filter(({ comment, audit }) => {
      // 唯一性必须按完整业务契约判断，允许同一任务存在动作相同但目标或操作人不同的合法记录。
      const auditEntries = audit && typeof audit === 'object' ? Object.entries(audit) : []
      const auditMatches = auditEntries.length === Object.keys(expectedAudit).length
        && Object.entries(expectedAudit).every(([key, value]) => audit[key] === value)
      return String(comment.taskId || '') === String(expected.taskId)
        && String(comment.type || '') === String(expected.type)
        && String(comment.userId || '') === String(expected.actorUserId)
        && auditMatches
    })
  expect(matches, `任务 ${expected.taskId} 的动作 ${expected.action} 审计必须唯一`).toHaveLength(1)
  const { comment, audit } = matches[0]
  expect(String(comment.userId || ''), 'Flowable comment 操作人必须为真实当前用户')
    .toBe(String(expected.actorUserId))
  expect(audit, `任务 ${expected.taskId} 的结构化审计字段必须完整且无歧义`).toEqual(expectedAudit)
}

/**
 * 通过真实 `/logout` 回收全部会话的 Redis Token，再关闭浏览器上下文。
 * @param {Array<{context: import('@playwright/test').BrowserContext, page: import('@playwright/test').Page, roleKey: string, pageErrors?: string[], consoleErrors?: string[], traceStarted?: boolean}>} sessions 待关闭角色会话。
 * @returns {Promise<string[]>} 脱敏后的注销或关闭错误集合。
 */
export async function closeWorkflowRoleSessions(sessions) {
  const errors = []
  for (const session of [...sessions].reverse()) {
    try {
      await logoutThroughUi(session.page, session.roleKey)
    } catch (error) {
      errors.push(`${session.roleKey}: ${redactWorkflowSecrets(error)}`)
    } finally {
      try {
        expect(session.pageErrors || [], `${session.roleKey} 页面不得出现未捕获异常`).toEqual([])
      } catch (error) {
        errors.push(`${session.roleKey}: ${redactWorkflowSecrets(error)}`)
      }
      try {
        expect(session.consoleErrors || [], `${session.roleKey} 页面不得输出 console.error`).toEqual([])
      } catch (error) {
        errors.push(`${session.roleKey}: ${redactWorkflowSecrets(error)}`)
      }
      if (session.traceStarted) {
        await session.context.tracing.stop().catch(error => {
          errors.push(`${session.roleKey}: ${redactWorkflowSecrets(error)}`)
        })
      }
      await session.context.close().catch(error => {
        errors.push(`${session.roleKey}: ${redactWorkflowSecrets(error)}`)
      })
    }
  }
  return errors
}

/**
 * 按实例、部署、模型、表单和分类的引用顺序清理本用例正式数据。
 * @param {{admin?: import('@playwright/test').Page, designer?: import('@playwright/test').Page}} pages 管理员和设计者页面。
 * @param {{draftFixtures?: Array<{draftId: string, processInstanceId?: string}>, processInstanceIds?: string[], deploymentIds?: string[], modelIds?: string[], formId?: string, categoryId?: string}} resources 本用例成功创建的资源主键集合。
 * @returns {Promise<string[]>} 脱敏后的清理错误集合；单项失败不会阻断后续清理。
 */
export async function cleanupWorkflowResources(pages, resources) {
  const errors = []

  /**
   * 执行一个正式清理动作并收集脱敏错误，确保后续依赖仍继续回收。
   * @param {string} label 清理动作名称。
   * @param {() => Promise<unknown>} action 正式 API 清理动作。
   * @returns {Promise<void>} 动作成功或错误已收集后结束。
   */
  const attempt = async (label, action) => {
    try {
      await action()
    } catch (error) {
      errors.push(`${label}: ${redactWorkflowSecrets(error)}`)
    }
  }

  for (const fixture of [...(resources.draftFixtures || [])].reverse()) {
    await attempt(`删除申请草稿 ${fixture.draftId}`, () => purgeWorkflowDraftFixture(fixture))
  }
  for (const processInstanceId of [...(resources.processInstanceIds || [])].reverse()) {
    let detail = null
    if (pages.admin) {
      await attempt(`查询流程 ${processInstanceId}`, async () => {
        detail = await getWorkflowDetail(pages.admin, processInstanceId)
      })
      if (detail?.processStatus === 'suspended') {
        await attempt(`激活流程 ${processInstanceId}`, () => callWorkflowApi(
          pages.admin, 'POST', '/workflow/instance/updateState', {
            data: { instanceId: processInstanceId, state: 'ACTIVE' }
          }))
      }
      // returned 仍保留活动申请人任务，必须先终止实例才能继续删除历史和设计资源。
      // 详情本身损坏时仍要尝试终止本用例登记的唯一实例，避免后续设计资源无法回收。
      if (!detail || ['running', 'returned', 'suspended'].includes(detail.processStatus)) {
        await attempt(`终止流程 ${processInstanceId}`, () => callWorkflowApi(
          pages.admin, 'POST', '/workflow/instance/terminate', {
            data: { instanceId: processInstanceId, reason: 'P3 生命周期 E2E 清理' }
          }))
      }
      await attempt(`删除流程历史 ${processInstanceId}`, () => callWorkflowApi(
        pages.admin, 'DELETE', `/workflow/process/instance/${encodeURIComponent(processInstanceId)}`))
    }
  }
  if (pages.designer) {
    for (const deploymentId of [...(resources.deploymentIds || [])].reverse()) {
      await attempt(`删除部署 ${deploymentId}`, () => callWorkflowApi(
        pages.designer, 'DELETE', `/workflow/deploy/${encodeURIComponent(deploymentId)}`))
    }
    for (const modelId of [...(resources.modelIds || [])].reverse()) {
      await attempt(`删除模型 ${modelId}`, () => callWorkflowApi(
        pages.designer, 'DELETE', `/workflow/model/${encodeURIComponent(modelId)}`))
    }
    if (resources.formId) {
      await attempt(`删除表单 ${resources.formId}`, () => callWorkflowApi(
        pages.designer, 'DELETE', `/workflow/form/${encodeURIComponent(resources.formId)}`))
    }
    if (resources.categoryId) {
      await attempt(`删除分类 ${resources.categoryId}`, () => callWorkflowApi(
        pages.designer, 'DELETE', `/workflow/category/${encodeURIComponent(resources.categoryId)}`))
    }
  }
  return errors
}
