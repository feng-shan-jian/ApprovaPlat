import { test, expect } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { once } from 'node:events'
import { createServer } from 'node:http'
import { DOMParser } from '@xmldom/xmldom'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowIntegrationPage } from '../../page-objects/integration.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { openRoleSession } from '../../support/role-session.js'
import { queryReadOnly } from '../../support/database.js'

/**
 * 生成 Participant 真实 UI 探针使用的唯一测试资产前缀。
 * @param {import('@playwright/test').TestInfo} testInfo 当前 Playwright 用例信息。
 * @returns {string} 以 `E2E_UI_` 开头且可用于正式模型键的唯一前缀。
 */
function participantProbePrefix(testInfo) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-20)
  return `E2E_UI_${runId}_collab_probe_${testInfo.workerIndex}_${Date.now().toString(36)}`
}

/**
 * 从作者 BPMN 中提取 Participant 的非敏感结构证据。
 * @param {string} xml 设计器通过可见 XML 预览提供的作者 BPMN。
 * @returns {Array<{id:string,name:string,processRef:string}>} Participant 标识、名称和流程引用摘要。
 */
function participantEvidence(xml) {
  const document = new DOMParser().parseFromString(xml, 'application/xml')
  return [...document.getElementsByTagNameNS('*', 'participant')].map(participant => ({
    id: participant.getAttribute('id') || '',
    name: participant.getAttribute('name') || '',
    processRef: participant.getAttribute('processRef') || ''
  }))
}

/**
 * 转义只读验收 SQL 中的字符串字面量。
 * @param {unknown} value 待拼入只读查询的业务主键。
 * @returns {string} 已转义单引号的字符串。
 */
function sqlLiteral(value) {
  return String(value ?? '').replaceAll("'", "''")
}

/**
 * 转义 BPMN XML 属性值，确保动态 JSON 和业务标识经过 bpmn-js 导入导出后仍保持完整。
 * @param {unknown} value 待写入 XML 属性的业务值。
 * @returns {string} 已转义 XML 五类保留字符的属性值。
 */
function xmlAttribute(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
}

/**
 * 生成单个协作验收场景的唯一正式资产名称。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例信息。
 * @returns {object} 分类、表单、端点、流程和消息的稳定名称集合。
 */
function collaborationAssets(testInfo) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const suffix = `${testInfo.title.match(/UI-COLLAB-\d+/u)?.[0]?.replaceAll('-', '') || 'COLLAB'}_${Date.now().toString(36)}`
  const prefix = `E2E_UI_${runId}_${suffix}`
  return {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_双池协作`,
    modelKey: `${prefix}_source`,
    sourceProcessKey: `${prefix}_source`,
    targetProcessKey: `${prefix}_target`,
    sourceProcessName: `${prefix}_发送流程`,
    targetProcessName: `${prefix}_接收流程`,
    endpointName: `${prefix}_中继端点`,
    endpointKey: `${prefix.toLowerCase()}.endpoint`,
    credentialName: `${prefix}_集成账号`,
    messageName: `${prefix}.message`,
    businessKey: `${prefix}_business`
  }
}

/**
 * 构造包含真实 Participant、MessageFlow、SendTask outbox 和 ReceiveTask 的双池 BPMN。
 * @param {object} assets 当前场景正式资产。
 * @param {string} formId 已创建正式表单主键。
 * @param {number} maxAttempts outbox 有界投递次数。
 * @returns {string} 可由设计器导入、保存和部署的完整 BPMN XML。
 */
function buildCollaborationBpmn(assets, formId, maxAttempts) {
  const config = JSON.stringify({
    endpointKey: assets.endpointKey,
    path: '/workflow/runtime-event/collaboration/message',
    messageName: assets.messageName,
    targetProcessDefinitionKey: assets.targetProcessKey,
    variableNames: [],
    maxAttempts
  })
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  targetNamespace="https://approvaplat.example/ui-collaboration">
  <message id="collaborationMessage" name="${assets.messageName}" />
  <collaboration id="collaboration">
    <participant id="sourcePool" name="发送方" processRef="${assets.sourceProcessKey}" />
    <participant id="targetPool" name="接收方" processRef="${assets.targetProcessKey}" />
    <messageFlow id="messageFlow" name="${assets.messageName}" messageRef="collaborationMessage" sourceRef="sendApproval" targetRef="receiveApproval" />
  </collaboration>
  <process id="${assets.sourceProcessKey}" name="${assets.sourceProcessName}" isExecutable="true">
    <startEvent id="sourceStart" flowable:formKey="key_${formId}" />
    <sequenceFlow id="sourceFlow1" sourceRef="sourceStart" targetRef="sendApproval" />
    <sendTask id="sendApproval" name="可靠发送">
      <extensionElements>
        <flowable:field name="approvaExtensionKey" stringValue="approva.collaboration-outbox" />
        <flowable:field name="approvaExtensionConfig" stringValue="${xmlAttribute(config)}" />
      </extensionElements>
    </sendTask>
    <sequenceFlow id="sourceFlow2" sourceRef="sendApproval" targetRef="sourceHold" />
    <receiveTask id="sourceHold" name="等待业务确认" />
    <sequenceFlow id="sourceFlow3" sourceRef="sourceHold" targetRef="sourceEnd" />
    <endEvent id="sourceEnd" />
  </process>
  <process id="${assets.targetProcessKey}" name="${assets.targetProcessName}" isExecutable="true">
    <startEvent id="targetStart" flowable:formKey="key_${formId}" />
    <sequenceFlow id="targetFlow1" sourceRef="targetStart" targetRef="receiveApproval" />
    <receiveTask id="receiveApproval" name="接收协作消息" />
    <sequenceFlow id="targetFlow2" sourceRef="receiveApproval" targetRef="targetEnd" />
    <endEvent id="targetEnd" />
  </process>
  <bpmndi:BPMNDiagram id="collaborationDiagram">
    <bpmndi:BPMNPlane id="collaborationPlane" bpmnElement="collaboration">
      <bpmndi:BPMNShape id="sourcePool_di" bpmnElement="sourcePool" isHorizontal="true"><dc:Bounds x="80" y="80" width="820" height="240" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="targetPool_di" bpmnElement="targetPool" isHorizontal="true"><dc:Bounds x="80" y="380" width="820" height="240" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sourceStart_di" bpmnElement="sourceStart"><dc:Bounds x="150" y="180" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sendApproval_di" bpmnElement="sendApproval"><dc:Bounds x="270" y="158" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sourceHold_di" bpmnElement="sourceHold"><dc:Bounds x="470" y="158" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="sourceEnd_di" bpmnElement="sourceEnd"><dc:Bounds x="680" y="180" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="targetStart_di" bpmnElement="targetStart"><dc:Bounds x="150" y="480" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="receiveApproval_di" bpmnElement="receiveApproval"><dc:Bounds x="370" y="458" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="targetEnd_di" bpmnElement="targetEnd"><dc:Bounds x="680" y="480" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="sourceFlow1_di" bpmnElement="sourceFlow1"><di:waypoint x="186" y="198" /><di:waypoint x="270" y="198" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="sourceFlow2_di" bpmnElement="sourceFlow2"><di:waypoint x="370" y="198" /><di:waypoint x="470" y="198" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="sourceFlow3_di" bpmnElement="sourceFlow3"><di:waypoint x="570" y="198" /><di:waypoint x="680" y="198" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="targetFlow1_di" bpmnElement="targetFlow1"><di:waypoint x="186" y="498" /><di:waypoint x="370" y="498" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="targetFlow2_di" bpmnElement="targetFlow2"><di:waypoint x="470" y="498" /><di:waypoint x="680" y="498" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="messageFlow_di" bpmnElement="messageFlow"><di:waypoint x="320" y="238" /><di:waypoint x="420" y="458" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 启动受控 HTTP 中继；先验证 outbox 的 API Key，再把原始 JSON 转发到真实匿名协作入口。
 * @param {string} token 当前一次性集成 Token，仅保留在测试进程内存。
 * @param {string} connectorApiKey 测试后端通过环境变量解析的出站 API Key，仅保留在进程内存。
 * @returns {Promise<object>} 中继地址、脱敏请求记录、模式切换和关闭方法。
 */
async function startCollaborationRelay(token, connectorApiKey) {
  const backendUrl = process.env.FLOWABLE_E2E_BACKEND_URL || 'http://127.0.0.1:8080'
  const state = { mode: 'forward', requests: [] }
  const server = createServer(async (incoming, outgoing) => {
    try {
      const chunks = []
      for await (const chunk of incoming) chunks.push(chunk)
      const body = Buffer.concat(chunks)
      const parsed = JSON.parse(body.toString('utf8'))
      const authenticationMatched = incoming.headers['x-integration-token'] === connectorApiKey
      state.requests.push({
        messageId: String(parsed.messageId || ''),
        receivedAt: Date.now(),
        authenticationMatched
      })
      if (!authenticationMatched) {
        outgoing.writeHead(401, { 'content-type': 'application/json' })
        outgoing.end(JSON.stringify({ code: 401, msg: '协作出站认证失败' }))
        return
      }
      if (state.mode === 'fail') {
        outgoing.writeHead(503, { 'content-type': 'application/json' })
        outgoing.end(JSON.stringify({ code: 503, msg: '受控外部端点暂不可用' }))
        return
      }
      const response = await fetch(new URL('/workflow/runtime-event/collaboration/message', backendUrl), {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'X-Integration-Token': token },
        body
      })
      const responseBody = Buffer.from(await response.arrayBuffer())
      outgoing.writeHead(response.status, { 'content-type': response.headers.get('content-type') || 'application/json' })
      outgoing.end(responseBody)
    } catch {
      outgoing.writeHead(502, { 'content-type': 'application/json' })
      outgoing.end(JSON.stringify({ code: 502, msg: '受控中继失败' }))
    }
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('协作中继未绑定有效 TCP 端口')
  return {
    baseUrl: `http://127.0.0.1:${address.port}`,
    requests: state.requests,
    setMode(mode) { state.mode = mode },
    async close() { await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())) }
  }
}

/**
 * 通过真实 UI 创建协作模型、端点和一次性账号，并完成模型部署。
 * @param {import('@playwright/test').Browser} browser Playwright 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例信息。
 * @param {number} maxAttempts outbox 最大投递次数。
 * @returns {Promise<object>} 场景资产、会话、中继和安全清理方法。
 */
async function provisionCollaborationScenario(browser, testInfo, maxAttempts = 3) {
  const assets = collaborationAssets(testInfo)
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  const designerSession = await openRoleSession(browser, 'workflow_designer', testInfo)
  const integration = new WorkflowIntegrationPage(admin.page)
  let credential
  let relay
  const configurationAdmin = new WorkflowConfigurationPage(admin.page)
  const configurationDesigner = new WorkflowConfigurationPage(designerSession.page)
  try {
    const connectorApiKey = String(process.env.WORKFLOW_CONNECTOR_SECRET_COLLABORATION_UI || '')
    expect(connectorApiKey, 'UI 总控必须向测试后端和浏览器进程注入同一份临时协作端点密钥').not.toBe('')
    credential = await integration.createCredential({
      name: assets.credentialName, scopes: ['MESSAGE'], allowedVariables: [], rateLimitPerMinute: 1000
    })
    relay = await startCollaborationRelay(credential.token, connectorApiKey)
    await configurationAdmin.createHttpEndpoint({
      name: assets.endpointName,
      key: assets.endpointKey,
      baseUrl: relay.baseUrl,
      pathPrefix: '/workflow/runtime-event',
      authType: 'API_KEY',
      secretRef: 'WORKFLOW_CONNECTOR_SECRET_COLLABORATION_UI',
      apiKeyHeader: 'X-Integration-Token',
      connectTimeoutMs: 1000,
      requestTimeoutMs: 5000
    })
    await configurationDesigner.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: `${assets.prefix} 多池协作`
    })
    await configurationDesigner.createTextForm({ name: assets.formName, remark: `${assets.prefix} 多池协作` })
    const formRows = queryReadOnly(
      `SELECT form_id FROM wf_form WHERE form_name='${sqlLiteral(assets.formName)}' AND del_flag='0' ORDER BY form_id DESC LIMIT 1`
    )
    expect(formRows, '协作模型必须绑定唯一正式表单').toHaveLength(1)
    assets.formId = formRows[0][0]
    await configurationDesigner.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${assets.prefix} 多池协作真实验收`
    })
    await configurationDesigner.openDesigner(assets.modelKey)
    await designerSession.page.locator('input.process-designer__file-input').setInputFiles({
      name: `${assets.modelKey}.bpmn`,
      mimeType: 'application/xml',
      buffer: Buffer.from(buildCollaborationBpmn(assets, assets.formId, maxAttempts), 'utf8')
    })
    const designer = new WorkflowDesignerPage(designerSession.page)
    await designer.validateAndSave()
    await designer.returnToModels()
    await configurationDesigner.deployModel(assets.modelKey)
    return {
      assets, admin, designerSession, integration, configurationAdmin, credential, relay,
      /** @param {boolean} failed 用例是否失败。 @returns {Promise<void>} 吊销凭据、停用端点并关闭会话。 */
      async close(failed) {
        const cleanupErrors = []
        try { await integration.revokeCredential(assets.credentialName) } catch (error) { cleanupErrors.push(error) }
        credential.token = ''
        try { await configurationAdmin.disableHttpEndpoint(assets.endpointKey) } catch (error) { cleanupErrors.push(error) }
        try { await relay.close() } catch (error) { cleanupErrors.push(error) }
        await Promise.allSettled([designerSession.close(failed), admin.close(failed)])
        expect(cleanupErrors, '协作场景必须吊销一次性账号、停用端点并关闭中继').toEqual([])
      }
    }
  } catch (error) {
    if (credential?.credentialId) await integration.revokeCredential(assets.credentialName).catch(() => undefined)
    if (relay) await relay.close().catch(() => undefined)
    await Promise.allSettled([designerSession.close(true), admin.close(true)])
    throw error
  }
}

/**
 * 通过真实发起页填写业务主键和正式表单并提交流程。
 * @param {import('@playwright/test').Page} page 发起人页面。
 * @param {string} processName 目标流程定义名称。
 * @param {string} businessKey 协作通道业务关联键。
 * @param {string} formValue 正式表单首个文本值。
 * @returns {Promise<string>} Flowable 流程实例主键。
 */
async function startProcessWithBusinessKey(page, processName, businessKey, formValue) {
  const workbench = new WorkflowWorkbenchPage(page)
  const row = await workbench.filterRow('/office/create', '请输入流程名称', processName)
  await row.locator('button').first().click()
  await expect(page).toHaveURL(/\/workflow\/process-start\//u)
  await page.getByPlaceholder('可选').fill(businessKey)
  const formInput = page.locator('.workflow-form-renderer input:not([type="file"])').first()
  await expect(formInput).toBeVisible()
  await formInput.fill(formValue)
  await page.getByRole('button', { name: '正式提交', exact: true }).click()
  await expect(page).toHaveURL(/\/workflow\/process-detail\//u)
  const processInstanceId = new URL(page.url()).pathname.split('/').filter(Boolean).at(-1)
  if (!processInstanceId) throw new Error('协作流程正式提交后缺少实例主键')
  return processInstanceId
}

/**
 * 向真实匿名协作 API 发布一条消息并返回 HTTP 与业务响应。
 * @param {import('@playwright/test').APIRequestContext} request Playwright HTTP 客户端。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例信息。
 * @param {string} token 一次性集成 Token。
 * @param {object} payload 协作消息协议正文。
 * @returns {Promise<{status:number,payload:object}>} HTTP 状态和 AjaxResult 正文。
 */
async function publishCollaboration(request, testInfo, token, payload) {
  const url = new URL('/dev-api/workflow/runtime-event/collaboration/message', testInfo.project.use.baseURL).toString()
  const response = await request.post(url, {
    headers: { 'X-Integration-Token': token },
    data: payload
  })
  return { status: response.status(), payload: await response.json() }
}

/**
 * 打开协作台账并按方向和唯一关键字筛选。
 * @param {import('@playwright/test').Page} page 管理员页面。
 * @param {'OUTBOUND'|'INBOUND'} direction 台账方向。
 * @param {string} keyword 唯一消息或业务键。
 * @returns {Promise<import('@playwright/test').Locator>} 唯一或成组结果行定位器。
 */
async function filterCollaborationRows(page, direction, keyword) {
  await page.goto('/workflow/extensions/collaboration')
  await expect(page.getByRole('heading', { name: '多池协作', exact: true })).toBeVisible()
  if (direction === 'INBOUND') await page.getByRole('tab', { name: '入站消息', exact: true }).click()
  const input = page.getByPlaceholder('消息、流程或关联键')
  await input.fill(keyword)
  await page.getByRole('button', { name: '查询', exact: true }).click()
  await expect(page.locator('.collaboration-page .el-loading-mask')).toHaveCount(0)
  return page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: keyword })
}

test('@full [UI-COLLAB-000] Participant 通过真实画布创建并稳定保存流程引用', async ({ browser }, testInfo) => {
  const prefix = participantProbePrefix(testInfo)
  const assets = {
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_协作探针`,
    modelKey: `${prefix}_model`,
    participantId: 'sourcePool',
    participantName: `${prefix}_发送池`,
    participantProcessKey: `${prefix}_source_process`
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designerSession = await openRoleSession(browser, 'workflow_designer', testInfo)
  const pageErrors = []
  designerSession.page.on('pageerror', error => pageErrors.push(String(error?.message || error).slice(0, 500)))
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designerSession.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: `${prefix} Participant UI 探针`
    })
    await configuration.createTextForm({ name: assets.formName, remark: `${prefix} Participant UI 探针` })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} Participant UI 探针`
    })
    await configuration.openDesigner(assets.modelKey)

    const designer = new WorkflowDesignerPage(designerSession.page)
    const createdParticipantId = await designer.createAdvancedElement({
      paletteLabel: '池 / 参与者',
      sourceElementId: 'review',
      stableElementId: assets.participantId,
      elementName: assets.participantName,
      offsetX: 0,
      offsetY: 250,
      expectedLocalName: 'participant'
    })
    expect(createdParticipantId).toBe(assets.participantId)

    const processRefInput = designer.properties.getByLabel('绑定流程定义 key')
    await expect(processRefInput, 'Participant 必须提供唯一流程引用输入框').toHaveCount(1)
    const initialVisibleValue = await processRefInput.inputValue()
    const initialParticipantState = participantEvidence(await designer.readDesignerXml())
    await processRefInput.fill(assets.participantProcessKey)
    await processRefInput.press('Tab')
    await expect(processRefInput, 'Participant 流程引用必须在真实失焦提交后稳定回显')
      .toHaveValue(assets.participantProcessKey)
    const submittedVisibleValue = await processRefInput.inputValue()

    const authorXml = await designer.readDesignerXml()
    const beforeSave = participantEvidence(authorXml)
    await testInfo.attach('participant-author-evidence.json', {
      body: Buffer.from(JSON.stringify({
        initialVisibleValue,
        initialParticipantState,
        submittedVisibleValue,
        beforeSave,
        pageErrors
      }, null, 2)),
      contentType: 'application/json'
    })
    expect(beforeSave).toEqual([{
      id: assets.participantId,
      name: assets.participantName,
      processRef: assets.participantProcessKey
    }])
    expect(pageErrors, 'Participant 创建和流程引用提交不得产生页面 JavaScript 错误').toEqual([])

    await designer.validateAndSave()
    await designer.returnToModels()
    await configuration.openDesigner(assets.modelKey)
    const reopenedDesigner = new WorkflowDesignerPage(designerSession.page)
    await reopenedDesigner.selectCanvasShape(assets.participantId)
    await expect(reopenedDesigner.properties.getByLabel('绑定流程定义 key'),
      '保存重开后 Participant 流程引用必须保持字符串值')
      .toHaveValue(assets.participantProcessKey)
    expect(participantEvidence(await reopenedDesigner.readDesignerXml())).toEqual(beforeSave)
    expect(pageErrors, 'Participant 保存重开不得产生页面 JavaScript 错误').toEqual([])
    failed = false
  } finally {
    await designerSession.close(failed)
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({ ...assets, pageErrors }, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-COLLAB-001] 多池协作按通道序号顺序投递并消费', async ({ browser, request }, testInfo) => {
  const scenario = await provisionCollaborationScenario(browser, testInfo)
  const starter = await openRoleSession(browser, 'workflow_starter', testInfo)
  let failed = true
  try {
    const { assets, credential } = scenario
    const messages = [1, 2, 3].map(sequenceNo => ({
      messageId: randomUUID(), messageName: assets.messageName,
      sourceProcessDefinitionKey: assets.sourceProcessKey,
      targetProcessDefinitionKey: assets.targetProcessKey,
      correlationKey: assets.businessKey, sequenceNo, variables: {}, maxAttempts: 3
    }))
    const third = await publishCollaboration(request, testInfo, credential.token, messages[2])
    expect(third.payload.code).toBe(200)
    expect(third.payload.data?.status).toBe('RETRYING')
    const targetInstances = []
    targetInstances.push(await startProcessWithBusinessKey(
      starter.page, assets.targetProcessName, assets.businessKey, `${assets.prefix}_接收1`))
    const first = await publishCollaboration(request, testInfo, credential.token, messages[0])
    expect(first.payload.code).toBe(200)
    expect(first.payload.data?.status).toBe('PROCESSED')
    targetInstances.push(await startProcessWithBusinessKey(
      starter.page, assets.targetProcessName, assets.businessKey, `${assets.prefix}_接收2`))
    const second = await publishCollaboration(request, testInfo, credential.token, messages[1])
    expect(second.payload.code).toBe(200)
    expect(second.payload.data?.status).toBe('PROCESSED')
    targetInstances.push(await startProcessWithBusinessKey(
      starter.page, assets.targetProcessName, assets.businessKey, `${assets.prefix}_接收3`))
    await expect.poll(() => queryReadOnly(
      `SELECT sequence_no,status FROM wf_collaboration_message WHERE correlation_key='${sqlLiteral(assets.businessKey)}' ORDER BY sequence_no`
    )).toEqual([['1', 'PROCESSED'], ['2', 'PROCESSED'], ['3', 'PROCESSED']])
    expect(queryReadOnly(
      `SELECT inbound_sequence FROM wf_collaboration_channel WHERE target_process_definition_key='${sqlLiteral(assets.targetProcessKey)}' AND correlation_value='${sqlLiteral(assets.businessKey)}'`
    )).toEqual([['3']])
    for (const instanceId of targetInstances) {
      expect(queryReadOnly(
        `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${sqlLiteral(instanceId)}'`
      )).toEqual([['1']])
    }
    const rows = await filterCollaborationRows(scenario.admin.page, 'INBOUND', assets.businessKey)
    await expect(rows).toHaveCount(3)
    for (let index = 0; index < 3; index += 1) {
      await expect(rows.nth(index)).toContainText('已完成')
    }
    failed = false
  } finally {
    await starter.close(failed)
    await scenario.close(failed)
  }
})

test('@full [UI-COLLAB-002] 多池协作重复请求保持发送与接收幂等', async ({ browser, request }, testInfo) => {
  const scenario = await provisionCollaborationScenario(browser, testInfo)
  const starter = await openRoleSession(browser, 'workflow_starter', testInfo)
  let failed = true
  try {
    const { assets, credential } = scenario
    await startProcessWithBusinessKey(starter.page, assets.targetProcessName, assets.businessKey, `${assets.prefix}_幂等接收`)
    const message = {
      messageId: randomUUID(), messageName: assets.messageName,
      sourceProcessDefinitionKey: assets.sourceProcessKey,
      targetProcessDefinitionKey: assets.targetProcessKey,
      correlationKey: assets.businessKey, sequenceNo: 1, variables: {}, maxAttempts: 3
    }
    const first = await publishCollaboration(request, testInfo, credential.token, message)
    const duplicate = await publishCollaboration(request, testInfo, credential.token, message)
    const tampered = await publishCollaboration(request, testInfo, credential.token, {
      ...message, sourceProcessDefinitionKey: `${assets.sourceProcessKey}_tampered`
    })
    expect(first.payload.code).toBe(200)
    expect(duplicate.payload.code).toBe(200)
    expect(duplicate.payload.data?.status).toBe('PROCESSED')
    expect(tampered.payload.code).toBe(409)
    expect(queryReadOnly(
      `SELECT COUNT(*),MIN(status),MAX(attempt_count) FROM wf_collaboration_message WHERE message_id='${sqlLiteral(message.messageId)}'`
    )).toEqual([['1', 'PROCESSED', '1']])
    expect(queryReadOnly(
      `SELECT action,COUNT(*) FROM wf_collaboration_message_audit WHERE message_id='${sqlLiteral(message.messageId)}' GROUP BY action ORDER BY action`
    )).toEqual([['DELIVER', '1'], ['RECEIVE', '1']])
    const row = await filterCollaborationRows(scenario.admin.page, 'INBOUND', message.messageId)
    await expect(row).toHaveCount(1)
    await row.getByRole('button', { name: '查看审计', exact: true }).click()
    await expect(scenario.admin.page.getByRole('heading', { name: '消息审计', exact: true })).toBeVisible()
    await expect(scenario.admin.page.locator('.el-timeline-item')).toHaveCount(2)
    failed = false
  } finally {
    await starter.close(failed)
    await scenario.close(failed)
  }
})

test('@full [UI-COLLAB-003] 多池协作失败重试进入死信并由UI补偿', async ({ browser, request }, testInfo) => {
  const scenario = await provisionCollaborationScenario(browser, testInfo)
  const starter = await openRoleSession(browser, 'workflow_starter', testInfo)
  let failed = true
  try {
    const { assets, credential } = scenario
    const message = {
      messageId: randomUUID(), messageName: assets.messageName,
      sourceProcessDefinitionKey: assets.sourceProcessKey,
      targetProcessDefinitionKey: assets.targetProcessKey,
      correlationKey: assets.businessKey, sequenceNo: 1, variables: {}, maxAttempts: 1
    }
    const first = await publishCollaboration(request, testInfo, credential.token, message)
    expect(first.payload.code).toBe(200)
    expect(first.payload.data?.status).toBe('DEAD_LETTER')
    expect(queryReadOnly(
      `SELECT status,attempt_count,last_error_code FROM wf_collaboration_message WHERE message_id='${sqlLiteral(message.messageId)}'`
    )).toEqual([['DEAD_LETTER', '1', 'COLLAB_MESSAGE_INSTANCE_NOT_FOUND']])

    const targetInstanceId = await startProcessWithBusinessKey(
      starter.page, assets.targetProcessName, assets.businessKey, `${assets.prefix}_补偿接收`)
    const row = await filterCollaborationRows(scenario.admin.page, 'INBOUND', message.messageId)
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('死信')
    await row.getByRole('button', { name: '重新投递', exact: true }).click()
    await scenario.admin.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expect(scenario.admin.page.getByText('消息已进入有界重试', { exact: true })).toBeVisible()
    await expect(row).toContainText('已完成')
    expect(queryReadOnly(
      `SELECT status,attempt_count,compensation_count,matched_process_instance_id FROM wf_collaboration_message WHERE message_id='${sqlLiteral(message.messageId)}'`
    )).toEqual([['PROCESSED', '1', '1', targetInstanceId]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM wf_collaboration_message_audit WHERE message_id='${sqlLiteral(message.messageId)}' AND action='COMPENSATE'`
    )).toEqual([['1']])
    failed = false
  } finally {
    await starter.close(failed)
    await scenario.close(failed)
  }
})

test('@full [UI-COLLAB-004] 多池协作源实例取消后停止后续投递', async ({ browser }, testInfo) => {
  const scenario = await provisionCollaborationScenario(browser, testInfo, 3)
  const starter = await openRoleSession(browser, 'workflow_starter', testInfo)
  let failed = true
  try {
    const { assets, relay } = scenario
    relay.setMode('fail')
    const sourceInstanceId = await startProcessWithBusinessKey(
      starter.page, assets.sourceProcessName, assets.businessKey, `${assets.prefix}_发送申请`)
    await expect.poll(() => queryReadOnly(
      `SELECT status,attempt_count FROM wf_collaboration_outbox WHERE source_process_instance_id='${sqlLiteral(sourceInstanceId)}'`
    )).toEqual([['RETRYING', '1']])
    expect(relay.requests).toHaveLength(1)
    await new WorkflowWorkbenchPage(starter.page).cancelOwnedProcess(
      assets.sourceProcessName, `${assets.prefix}_取消后禁止继续投递`)
    await expect.poll(() => queryReadOnly(
      `SELECT status FROM wf_collaboration_outbox WHERE source_process_instance_id='${sqlLiteral(sourceInstanceId)}'`
    )).toEqual([['CANCELLED']])
    const cancelledState = queryReadOnly(
      `SELECT status,attempt_count FROM wf_collaboration_outbox WHERE source_process_instance_id='${sqlLiteral(sourceInstanceId)}'`)
    expect(cancelledState).toHaveLength(1)
    expect(Number(cancelledState[0][1])).toBeGreaterThanOrEqual(1)
    const requestsAfterCancellation = relay.requests.length
    await new Promise(resolve => setTimeout(resolve, 3500))
    expect(queryReadOnly(
      `SELECT status,attempt_count FROM wf_collaboration_outbox WHERE source_process_instance_id='${sqlLiteral(sourceInstanceId)}'`
    )).toEqual(cancelledState)
    expect(relay.requests).toHaveLength(requestsAfterCancellation)
    expect(queryReadOnly(
      `SELECT action,from_status,to_status FROM wf_collaboration_message_audit WHERE direction='OUTBOUND' AND message_id=(SELECT message_id FROM wf_collaboration_outbox WHERE source_process_instance_id='${sqlLiteral(sourceInstanceId)}') ORDER BY audit_id DESC LIMIT 1`
    )).toEqual([['CANCEL', 'RETRYING', 'CANCELLED']])
    const messageIdRows = queryReadOnly(
      `SELECT message_id FROM wf_collaboration_outbox WHERE source_process_instance_id='${sqlLiteral(sourceInstanceId)}'`
    )
    const row = await filterCollaborationRows(scenario.admin.page, 'OUTBOUND', messageIdRows[0][0])
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('已取消')
    await expect(row.getByRole('button', { name: '重新投递', exact: true })).toHaveCount(0)
    failed = false
  } finally {
    await starter.close(failed)
    await scenario.close(failed)
  }
})
