import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createWorkflowCategory,
  createWorkflowForm,
  findAssignedWorkflowTask,
  findStartableWorkflowDefinition,
  findWorkflowUserOption,
  openWorkflowRoleSession,
  readWorkflowRuntimeBooleanVariable,
  startWorkflowThroughUi
} from './support/workflow-fixture.js'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'

test.describe.configure({ mode: 'serial' })

/**
 * 生成待通过属性面板配置的 CEL ServiceTask 和可观察审批任务流程。
 * @param {{processKey: string, processName: string, formId: string, approverUserId: string}} input 流程、正式表单和真实办理人标识。
 * @returns {string} 带完整 BPMN DI 的可执行作者 XML；ServiceTask 初始不含受控扩展字段。
 */
function buildCelDesignerBpmn({ processKey, processName, formId, approverUserId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="https://approvaplat.example/cel-e2e">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flow_start_evaluate" sourceRef="start" targetRef="evaluateEligibility" />
    <serviceTask id="evaluateEligibility" name="计算审批分支" />
    <sequenceFlow id="flow_evaluate_review" sourceRef="evaluateEligibility" targetRef="approvedReview" />
    <userTask id="approvedReview" name="CEL 命中审批" flowable:assignee="${approverUserId}" />
    <sequenceFlow id="flow_approved_end" sourceRef="approvedReview" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="80" y="182" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_evaluate" bpmnElement="evaluateEligibility"><omgdc:Bounds x="170" y="160" width="110" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_approved" bpmnElement="approvedReview"><omgdc:Bounds x="350" y="160" width="110" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="540" y="182" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_evaluate" bpmnElement="flow_start_evaluate"><omgdi:waypoint x="116" y="200" /><omgdi:waypoint x="170" y="200" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_evaluate_review" bpmnElement="flow_evaluate_review"><omgdi:waypoint x="280" y="200" /><omgdi:waypoint x="350" y="200" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_approved_end" bpmnElement="flow_approved_end"><omgdi:waypoint x="460" y="200" /><omgdi:waypoint x="540" y="200" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 打开 XML 预览并读取当前 Modeler 的完整作者 XML。
 * @param {import('@playwright/test').Page} page 已登录且打开模型设计器的页面。
 * @returns {Promise<string>} 只读预览框中的完整 XML。
 */
async function readXmlPreview(page) {
  await page.getByRole('button', { name: '预览流程源码' }).click()
  await page.getByRole('menuitem', { name: 'XML 预览' }).click()
  const dialog = page.getByRole('dialog', { name: 'XML 预览' })
  await expect(dialog).toBeVisible()
  const xml = await dialog.getByRole('textbox').inputValue()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  return xml
}

/**
 * 通过真实设计器配置 CEL，保存重开后部署并发起，最终用运行变量和任务证明执行结果。
 * @param {{browser: import('@playwright/test').Browser}} fixture Playwright 浏览器夹具。
 * @returns {Promise<void>} 全链路成功且正式数据清理完成后结束。
 */
test('CEL 从属性面板配置到 Flowable 运行变量形成真实闭环', async ({ browser }) => {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  const processKey = `cel_e2e_${suffix}`
  const processName = `CEL闭环_${suffix}`
  const formName = `CEL发起表单_${suffix}`
  const subject = `CEL-E2E-${suffix}`
  const resources = { draftFixtures: [], processInstanceIds: [], deploymentIds: [], modelIds: [] }
  const sessions = []
  const pages = {}

  try {
    // CEL 编辑器和 bpmn-js 同时驻留时只开启设计者上下文，避免无关角色争用浏览器资源。
    const designerSession = await openWorkflowRoleSession(browser, 'workflow_designer')
    sessions.push(designerSession)
    pages.designer = designerSession.page

    const celOptions = await callWorkflowApi(
      pages.designer, 'GET', '/workflow/extension/options/cel')
    expect(celOptions.data).toEqual(expect.arrayContaining([
      expect.objectContaining({
        extensionKey: 'approva.cel-expression',
        extensionType: 'CEL',
        implementationKey: 'CEL_EXPRESSION_V1',
        versionNo: 1
      })
    ]))

    const approver = await findWorkflowUserOption(pages.designer, 'workflow_approver')
    expect(approver, 'CEL E2E 必须取得真实审批办理人').toBeTruthy()
    const categoryCode = `cel_${suffix}`
    await createWorkflowCategory(pages.designer, `CEL分类_${suffix}`, categoryCode, resources)
    const formId = await createWorkflowForm(pages.designer, formName, resources)
    const created = await callWorkflowApi(pages.designer, 'POST', '/workflow/model', {
      data: {
        modelName: processName,
        modelKey: processKey,
        category: categoryCode,
        description: 'CEL 设计部署运行真实浏览器验收',
        formType: 0,
        formId: Number(formId)
      }
    })
    const modelId = String(created.data?.modelId || '')
    expect(modelId, 'CEL 模型创建必须返回正式主键').not.toBe('')
    resources.modelIds.push(modelId)

    const modelPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, `/workflow/model/${modelId}`, 'GET'))
    const optionsPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/extension/options/cel', 'GET'))
    await pages.designer.goto(`/workflow/model-design/${modelId}`)
    await expectAjaxSuccess(await modelPromise, `/workflow/model/${modelId}`)
    await expectAjaxSuccess(await optionsPromise, '/workflow/extension/options/cel')
    const authorXml = buildCelDesignerBpmn({
      processKey,
      processName,
      formId,
      approverUserId: String(approver.value)
    })
    await pages.designer.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}.bpmn`,
      mimeType: 'application/xml',
      buffer: Buffer.from(authorXml, 'utf8')
    })
    await pages.designer.locator('[data-element-id="evaluateEligibility"]').click()

    const properties = pages.designer.locator('.designer-properties-panel')
    const extensionSelect = properties.getByRole('combobox', { name: '受控处理器' })
    await extensionSelect.press('Enter')
    await pages.designer.getByRole('option', { name: /CEL 安全表达式.*CEL.*v1/i }).click()
    await expect(properties.locator('.cel-expression-editor')).toBeVisible()

    const expressionInput = properties.getByRole('textbox', { name: '表达式' })
    await expressionInput.fill(`requestTitle == '${subject}'`)
    await expressionInput.press('Tab')
    const resultVariableInput = properties.getByRole('textbox', { name: '结果变量' })
    await resultVariableInput.fill('eligible')
    await resultVariableInput.press('Tab')
    await properties.getByRole('button', { name: '添加变量', exact: true }).click()
    const inputVariable = properties.getByRole('textbox', { name: '输入变量 1 名称' })
    await inputVariable.fill('requestTitle')
    await inputVariable.press('Tab')
    const inputType = properties.getByRole('combobox', { name: '输入变量 1 类型' })
    await inputType.press('Enter')
    await pages.designer.getByRole('option', { name: '文本', exact: true }).click()

    const editedXml = await readXmlPreview(pages.designer)
    expect(editedXml).toContain('approvaExtensionKey')
    expect(editedXml).toContain('approva.cel-expression')
    expect(editedXml).toContain('approvaExtensionConfig')
    expect(editedXml).toContain('requestTitle')
    expect(editedXml).toContain('eligible')

    const validationPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/validate', 'POST'))
    const savePromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/save', 'POST'))
    await pages.designer.getByRole('button', { name: '保存', exact: true }).click()
    const validation = await expectAjaxSuccess(
      await validationPromise, '/workflow/model/validate')
    expect(validation.data?.valid, JSON.stringify(validation.data?.issues || [])).toBe(true)
    await expectAjaxSuccess(await savePromise, '/workflow/model/save')

    const reopenXmlPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, `/workflow/model/bpmnXml/${modelId}`, 'GET'))
    await pages.designer.goto(`/workflow/model-design/${modelId}`)
    expect((await reopenXmlPromise).status()).toBe(200)
    await pages.designer.locator('[data-element-id="evaluateEligibility"]').click()
    await expect(properties.getByRole('textbox', { name: '表达式' }))
      .toHaveValue(`requestTitle == '${subject}'`)
    await expect(properties.getByRole('textbox', { name: '结果变量' })).toHaveValue('eligible')
    await expect(properties.getByRole('textbox', { name: '输入变量 1 名称' }))
      .toHaveValue('requestTitle')

    const deployed = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/deploy', {
      query: { modelId }
    })
    const deploymentId = String(deployed.data?.deploymentId || '')
    expect(deploymentId, 'CEL 部署必须返回正式 deploymentId').not.toBe('')
    resources.deploymentIds.push(deploymentId)

    // 部署完成后再开启发起、办理和运维角色，保证真实职责隔离且控制设计阶段资源峰值。
    for (const roleKey of ['workflow_starter', 'workflow_approver', 'workflow_admin']) {
      const session = await openWorkflowRoleSession(browser, roleKey)
      sessions.push(session)
      pages[roleKey.replace('workflow_', '')] = session.page
    }
    const definition = await findStartableWorkflowDefinition(pages.starter, processKey)
    expect(definition.deploymentId).toBe(deploymentId)
    const processInstanceId = await startWorkflowThroughUi(
      pages.starter,
      definition,
      formName,
      `CEL-BIZ-${suffix}`,
      subject,
      resources
    )
    const approvedTask = await findAssignedWorkflowTask(
      pages.approver, processKey, 'approvedReview', processInstanceId)
    expect(String(approvedTask.taskId || '')).not.toBe('')
    expect(readWorkflowRuntimeBooleanVariable(processInstanceId, 'eligible'),
      'CEL 结果必须作为唯一 Flowable boolean 运行变量真实落库').toEqual({
      count: 1,
      type: 'boolean',
      longValue: 1
    })
    const todoPayload = await callWorkflowApi(pages.approver, 'GET', '/workflow/process/todoList', {
      query: { processKey, pageNum: 1, pageSize: 100 }
    })
    const instanceTasks = (todoPayload.rows || []).filter(row => (
      String(row.processInstanceId) === processInstanceId
    ))
    expect(instanceTasks.map(row => row.taskDefinitionKey)).toEqual(['approvedReview'])
  } finally {
    const cleanupErrors = pages.designer
      ? await cleanupWorkflowResources(
          { admin: pages.admin, designer: pages.designer }, resources)
      : []
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    expect([...cleanupErrors, ...sessionErrors], 'CEL E2E 必须清理全部正式数据和会话').toEqual([])
  }
})
