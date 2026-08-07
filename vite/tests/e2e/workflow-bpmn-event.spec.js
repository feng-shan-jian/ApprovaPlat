import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createWorkflowCategory,
  createWorkflowForm,
  findWorkflowUserOption,
  openWorkflowRoleSession
} from './support/workflow-fixture.js'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'

test.describe.configure({ mode: 'serial' })

/**
 * 通过真实页面和 API 清理本轮事件目录，避免失败重跑污染正式目录。
 * @param {import('@playwright/test').Page} page 已登录管理员页面。
 * @param {number|null} eventCodeId 本轮创建的目录主键。
 * @returns {Promise<string[]>} 脱敏清理错误集合。
 */
async function cleanupEventCode(page, eventCodeId) {
  if (!eventCodeId) return []
  try {
    const rows = (await callWorkflowApi(page, 'GET', '/workflow/bpmn-event/codes')).data || []
    const row = rows.find(item => Number(item.eventCodeId) === eventCodeId)
    if (!row) return []
    if (row.status === 'ENABLED') {
      await callWorkflowApi(page, 'PUT', `/workflow/bpmn-event/codes/${eventCodeId}/status`, {
        data: { enabled: false }
      })
    }
    // 事件目录是正式审计引用对象，页面没有删除入口；停用后保留历史追踪。
    return []
  } catch (error) {
    return [String(error?.message || error)]
  }
}

/**
 * 停用本轮浏览器验收创建的正式业务日历，保留不可删除的发布历史。
 * @param {import('@playwright/test').Page} page 已登录工作流管理员页面。
 * @param {number|null} calendarId 本轮创建的业务日历主键。
 * @returns {Promise<string[]>} 脱敏清理错误集合。
 */
async function cleanupSlaCalendar(page, calendarId) {
  if (!calendarId) return []
  try {
    const payload = await callWorkflowApi(page, 'GET', '/workflow/sla/calendars')
    const rows = Array.isArray(payload.data) ? payload.data : payload.rows || []
    const row = rows.find(item => Number(item.calendarId) === calendarId)
    if (!row) return []
    if (row.status === 'ENABLED') {
      await callWorkflowApi(page, 'PUT', `/workflow/sla/calendars/${calendarId}/status`, {
        data: { enabled: false }
      })
    }
    return []
  } catch (error) {
    return [String(error?.message || error)]
  }
}

/**
 * 生成供浏览器设计器配置审批 SLA 的最小可部署 BPMN。
 * @param {{processKey:string,processName:string,formId:string,approverUserId:string}} input 流程标识、名称、正式表单和审批用户主键。
 * @returns {string} 包含完整 BPMN DI 坐标且尚未配置 SLA 的 UTF-8 XML。
 */
function buildSlaDesignerBpmn({ processKey, processName, formId, approverUserId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="https://approvaplat.example/sla-e2e">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="toReview" sourceRef="start" targetRef="review" />
    <userTask id="review" name="审批处理" flowable:assignee="${approverUserId}" />
    <sequenceFlow id="toEnd" sourceRef="review" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="100" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_review" bpmnElement="review"><omgdc:Bounds x="260" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="520" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_to_review" bpmnElement="toReview"><omgdi:waypoint x="136" y="190" /><omgdi:waypoint x="260" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_to_end" bpmnElement="toEnd"><omgdi:waypoint x="360" y="190" /><omgdi:waypoint x="520" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 在 SLA 组件中选择一个由后端正式目录返回的选项。
 * @param {import('@playwright/test').Page} page 当前设计器页面。
 * @param {import('@playwright/test').Locator} editor SLA 组件根元素。
 * @param {string} fieldLabel 表单字段中文标签。
 * @param {string} optionLabel 正式目录选项的完整显示文本。
 * @returns {Promise<void>} 选项完成选择并回显后结束。
 */
async function selectSlaOption(page, editor, fieldLabel, optionLabel) {
  const formItem = editor.locator('.el-form-item').filter({ hasText: fieldLabel }).first()
  await formItem.locator('.el-select').click()
  await page.getByRole('option', { name: optionLabel, exact: true }).click()
  await expect(formItem.locator('.el-select')).toContainText(optionLabel)
}

/**
 * 在 SLA 组件中填写一个工作分钟或次数字段并触发真实 change 事件。
 * @param {import('@playwright/test').Locator} editor SLA 组件根元素。
 * @param {string} fieldLabel 数字字段中文标签。
 * @param {number} value 待写入的合法整数。
 * @returns {Promise<void>} 输入失焦且组件完成提交后结束。
 */
async function fillSlaNumber(editor, fieldLabel, value) {
  const formItem = editor.locator('.el-form-item').filter({ hasText: fieldLabel }).first()
  const input = formItem.getByRole('spinbutton')
  await input.fill(String(value))
  await input.press('Tab')
  await expect(input).toHaveValue(String(value))
}

test('管理员和设计者通过真实页面完成 SLA 目录、模型配置、保存与部署', async ({ browser }) => {
  // 稳定编码契约只接受大写字母，随机后缀统一大写避免浏览器表单校验阻断真实 POST。
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12).toUpperCase()
  const eventCode = `E2E_APPROVAL_TIMEOUT_${suffix}`
  const eventName = `页面审批超时升级_${suffix}`
  const calendarKey = `E2E_CALENDAR_${suffix}`
  const calendarName = `页面业务日历_${suffix}`
  const processKey = `sla_designer_${suffix}`
  const processName = `审批SLA设计_${suffix}`
  const resources = { modelIds: [], deploymentIds: [] }
  const sessions = []
  let adminSession = null
  let designerSession = null
  let eventCodeId = null
  let calendarId = null
  try {
    adminSession = await openWorkflowRoleSession(browser, 'workflow_admin')
    sessions.push(adminSession)
    const page = adminSession.page

    const listPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/bpmn-event/codes', 'GET'))
    await page.goto('/workflow/bpmnEvent')
    await expectAjaxSuccess(await listPromise, '/workflow/bpmn-event/codes')
    await expect(page.getByRole('heading', { name: '错误、升级与审批 SLA' })).toBeVisible()

    await page.getByRole('button', { name: '新增编码' }).click()
    const dialog = page.getByRole('dialog', { name: '新增事件编码' })
    await dialog.getByText('业务升级', { exact: true }).click()
    await dialog.getByRole('textbox', { name: '稳定编码' }).fill(eventCode)
    await dialog.getByRole('textbox', { name: '显示名称' }).fill(eventName)
    const createPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/bpmn-event/codes', 'POST'))
    await dialog.getByRole('button', { name: '保存' }).click()
    const created = await expectAjaxSuccess(await createPromise, '/workflow/bpmn-event/codes')
    eventCodeId = Number(created.data?.eventCodeId)
    expect(eventCodeId).toBeGreaterThan(0)

    await expect(page.locator('.el-table__body tr').filter({ hasText: eventCode })).toContainText('已启用')
    await page.getByRole('tab', { name: '运行审计' }).click()
    await expect(page.getByRole('tab', { name: '运行审计' })).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('tab', { name: '我的通知' }).click()
    await expect(page.getByRole('tab', { name: '我的通知' })).toHaveAttribute('aria-selected', 'true')

    const calendarListPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/sla/calendars', 'GET'))
    await page.getByRole('tab', { name: '业务日历' }).click()
    await expectAjaxSuccess(await calendarListPromise, '/workflow/sla/calendars')
    await page.getByRole('button', { name: '新增日历' }).click()
    const calendarDialog = page.getByRole('dialog', { name: '新增业务日历' })
    await calendarDialog.getByRole('textbox', { name: '稳定编码' }).fill(calendarKey)
    await calendarDialog.getByRole('textbox', { name: '日历名称' }).fill(calendarName)
    const createCalendarPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/sla/calendars', 'POST'))
    await calendarDialog.getByRole('button', { name: '保存' }).click()
    const createdCalendar = await expectAjaxSuccess(
      await createCalendarPromise, '/workflow/sla/calendars')
    // 后端新增日历接口返回正式主键标量，兼容 AjaxResult.data 的 Long 形态并保留严格回查。
    calendarId = Number(createdCalendar.data?.calendarId ?? createdCalendar.data)
    expect(calendarId).toBeGreaterThan(0)
    await expect(page.locator('.el-table__body tr').filter({ hasText: calendarKey })).toContainText('已启用')

    for (const tab of [
      { name: 'SLA 执行', endpoint: '/workflow/sla/executions' },
      { name: 'SLA 审计', endpoint: '/workflow/sla/audits' },
      { name: 'SLA 通知', endpoint: '/workflow/sla/notifications' }
    ]) {
      const responsePromise = page.waitForResponse(response => matchesEndpoint(response, tab.endpoint, 'GET'))
      await page.getByRole('tab', { name: tab.name }).click()
      await expectAjaxSuccess(await responsePromise, tab.endpoint)
      await expect(page.getByRole('tab', { name: tab.name })).toHaveAttribute('aria-selected', 'true')
    }

    designerSession = await openWorkflowRoleSession(browser, 'workflow_designer')
    sessions.push(designerSession)
    const designerPage = designerSession.page
    const approver = await findWorkflowUserOption(designerPage, 'workflow_approver')
    expect(approver, 'SLA 设计器验收必须取得具备真实审批资格的用户').toBeTruthy()
    const categoryCode = `sla_${suffix}`
    await createWorkflowCategory(designerPage, `SLA分类_${suffix}`, categoryCode, resources)
    const formId = await createWorkflowForm(designerPage, `SLA表单_${suffix}`, resources)
    const createdModel = await callWorkflowApi(designerPage, 'POST', '/workflow/model', {
      data: {
        modelName: processName,
        modelKey: processKey,
        category: categoryCode,
        description: '审批 SLA 真实浏览器验收',
        formType: 0,
        formId: Number(formId)
      }
    })
    const modelId = String(createdModel.data?.modelId || '')
    expect(modelId, 'SLA 模型创建必须返回正式主键').not.toBe('')
    resources.modelIds.push(modelId)

    const modelResponsePromise = designerPage.waitForResponse(response => matchesEndpoint(
      response, `/workflow/model/${modelId}`, 'GET'))
    await designerPage.goto(`/workflow/model-design/${modelId}`)
    await expectAjaxSuccess(await modelResponsePromise, `/workflow/model/${modelId}`)
    const source = buildSlaDesignerBpmn({
      processKey,
      processName,
      formId,
      approverUserId: String(approver.value)
    })
    await designerPage.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}.bpmn`,
      mimeType: 'application/xml',
      buffer: Buffer.from(source, 'utf8')
    })
    await designerPage.locator('[data-element-id="review"]').click()
    const slaEditor = designerPage.locator('.user-task-sla-editor')
    await expect(slaEditor).toBeVisible()
    const slaSwitch = slaEditor.getByRole('switch')
    await expect(slaSwitch).toBeEnabled()
    await slaEditor.locator('.el-switch').click()
    await expect(slaSwitch).toBeChecked()

    await selectSlaOption(
      designerPage, slaEditor, '业务日历', `${calendarName} · ${calendarKey}`)
    await fillSlaNumber(slaEditor, '首次提醒（分钟）', 5)
    await fillSlaNumber(slaEditor, '最大提醒次数', 2)
    await fillSlaNumber(slaEditor, '重复提醒间隔（分钟）', 5)
    await fillSlaNumber(slaEditor, '超时升级（分钟）', 20)
    await selectSlaOption(designerPage, slaEditor, '升级办理人', approver.label)
    await selectSlaOption(
      designerPage, slaEditor, '受控升级事件', `${eventName} · ${eventCode}`)

    const validationPromise = designerPage.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/validate', 'POST'))
    const savePromise = designerPage.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/save', 'POST'))
    await designerPage.getByRole('button', { name: '保存', exact: true }).click()
    const validation = await expectAjaxSuccess(
      await validationPromise, '/workflow/model/validate')
    expect(validation.data?.valid).toBe(true)
    const saved = await expectAjaxSuccess(await savePromise, '/workflow/model/save')
    expect(String(saved.data?.modelId || '')).toBe(modelId)

    const authorXml = (await callWorkflowApi(
      designerPage, 'GET', `/workflow/model/bpmnXml/${encodeURIComponent(modelId)}`)).data
    const expectedProperties = {
      enabled: 'true',
      calendarKey,
      reminderMinutes: '5',
      reminderRepeatMinutes: '5',
      maxReminders: '2',
      escalationMinutes: '20',
      escalationUserId: String(approver.value),
      escalationEventCode: eventCode
    }
    for (const [field, value] of Object.entries(expectedProperties)) {
      expect(authorXml, `作者 BPMN 必须回读 approva.sla.${field}`).toContain(
        `name="approva.sla.${field}" value="${value}"`)
    }

    const deployment = await callWorkflowApi(designerPage, 'POST', '/workflow/model/deploy', {
      query: { modelId }
    })
    const deploymentId = String(deployment.data?.deploymentId || '')
    expect(deploymentId, 'SLA 模型必须通过正式部署接口返回部署主键').not.toBe('')
    resources.deploymentIds.push(deploymentId)
    const published = await callWorkflowApi(designerPage, 'GET', '/workflow/deploy/list', {
      query: { processKey, pageNum: 1, pageSize: 20 }
    })
    const deployedRows = (published.rows || []).filter(row => row.processKey === processKey
      && String(row.deploymentId) === deploymentId)
    expect(deployedRows, 'SLA 部署必须可从正式定义列表唯一回查').toHaveLength(1)
  } finally {
    const workflowCleanupErrors = designerSession
      ? await cleanupWorkflowResources({ designer: designerSession.page }, resources)
      : []
    const cleanupErrors = adminSession ? await cleanupEventCode(adminSession.page, eventCodeId) : []
    const calendarCleanupErrors = adminSession
      ? await cleanupSlaCalendar(adminSession.page, calendarId)
      : []
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    expect([
      ...workflowCleanupErrors,
      ...cleanupErrors,
      ...calendarCleanupErrors,
      ...sessionErrors
    ], 'BPMN 与 SLA 浏览器验收必须精确清理全部正式资源和会话').toEqual([])
  }
})
