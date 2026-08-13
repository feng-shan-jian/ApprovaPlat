import { test, expect } from '@playwright/test'
import { DOMParser } from '@xmldom/xmldom'
import { randomUUID } from 'node:crypto'
import { WorkflowBpmnEventPage } from '../../page-objects/bpmn-event.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowIntegrationPage } from '../../page-objects/integration.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成高级 BPMN 场景的唯一测试资产前缀。
 * @param {import('@playwright/test').TestInfo} testInfo 当前 Playwright 用例信息。
 * @param {string} domain 当前高级 BPMN 场景缩写。
 * @returns {string} 可用于模型、分类和表单的唯一 ASCII 前缀。
 */
function advancedPrefix(testInfo, domain) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-16)
  return `E2E_UI_${runId}_${domain}_${testInfo.workerIndex}_${Date.now().toString(36)}`
}

/**
 * 转义只读核验 SQL 中的字符串字面量。
 * @param {string} value 流程实例或模型稳定标识。
 * @returns {string} 可安全嵌入单条只读 SQL 的字符串。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 将发起页上下文接口响应转换为不包含业务正文和凭据的测试证据。
 * @param {import('@playwright/test').Response} response Playwright 捕获的真实浏览器响应。
 * @returns {Promise<{endpoint:string,httpStatus:number,code:number|null,subCode:string,message:string}>} 脱敏后的接口状态与稳定错误语义。
 */
async function startContextEvidence(response) {
  const pathname = new URL(response.url()).pathname
  let payload = {}
  try {
    payload = await response.json()
  } catch {
    // 非 JSON 响应只保留 HTTP 状态，禁止把原始正文写入测试报告。
  }
  return {
    endpoint: pathname,
    httpStatus: response.status(),
    code: Number.isFinite(Number(payload?.code)) ? Number(payload.code) : null,
    subCode: String(payload?.subCode || '').slice(0, 128),
    message: String(payload?.message || payload?.msg || '').slice(0, 300)
  }
}

/**
 * 将调用活动正式目录响应转换为不包含表单正文和凭据的测试证据。
 * @param {import('@playwright/test').Response} response Playwright 捕获的真实目录响应。
 * @param {string} targetDefinitionId 本次 UI 创建的目标子流程定义 ID。
 * @returns {Promise<{endpoint:string,httpStatus:number,code:number|null,subCode:string,message:string,total:number,targetPresent:boolean,targetStatus:string,targetInputFields:string[],targetOutputFields:string[]}>} 目录状态、数量和目标项摘要。
 */
async function callActivityCatalogEvidence(response, targetDefinitionId) {
  const pathname = new URL(response.url()).pathname
  let payload = {}
  try {
    payload = await response.json()
  } catch {
    // 非 JSON 响应只保留 HTTP 状态，禁止把原始正文或网关错误页写入测试报告。
  }
  const options = Array.isArray(payload?.data) ? payload.data : []
  const target = options.find(option => String(option?.definitionId || '') === targetDefinitionId)
  return {
    endpoint: pathname,
    httpStatus: response.status(),
    code: Number.isFinite(Number(payload?.code)) ? Number(payload.code) : null,
    subCode: String(payload?.subCode || '').slice(0, 128),
    message: String(payload?.message || payload?.msg || '').slice(0, 300),
    total: options.length,
    targetPresent: Boolean(target),
    targetStatus: String(target?.status || ''),
    targetInputFields: Array.isArray(target?.inputFields)
      ? target.inputFields.map(field => String(field?.name || '')).filter(Boolean) : [],
    targetOutputFields: Array.isArray(target?.outputFields)
      ? target.outputFields.map(field => String(field?.name || '')).filter(Boolean) : []
  }
}

/**
 * 从测试资产前缀生成满足正式 BPMN 事件目录约束的稳定编码。
 * @param {string} prefix 当前场景唯一资产前缀。
 * @param {'ERROR'|'ESCALATION'} eventType 正式事件类型。
 * @returns {string} 以大写字母开头且不超过 64 字符的事件编码。
 */
function businessEventCode(prefix, eventType) {
  return `${prefix.replace(/[^A-Za-z0-9]/gu, '_').toUpperCase()}_${eventType}`.slice(0, 64)
}

/**
 * 通过真实 UI 创建、部署并运行一条受控 Error 或 Escalation 边界事件流程。
 * @param {{browser:import('@playwright/test').Browser,testInfo:import('@playwright/test').TestInfo,eventType:'ERROR'|'ESCALATION',interrupting:boolean,domain:string}} options 浏览器、用例信息、事件类型、中断语义和资产域。
 * @returns {Promise<void>} 事件目录、作者模型、运行捕获、人工处理、审计和停用清理全部完成后结束。
 */
async function executeBusinessBoundaryScenario(options) {
  const { browser, testInfo, eventType, interrupting, domain } = options
  const prefix = advancedPrefix(testInfo, domain)
  const typeLabel = eventType === 'ERROR' ? '业务错误' : '业务升级'
  const eventCode = businessEventCode(prefix, eventType)
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_${typeLabel}边界`,
    modelKey: `${prefix}_model`,
    eventType,
    eventCode,
    eventName: `${prefix}_${typeLabel}`,
    raiseElementId: 'raiseEvent',
    boundaryElementId: 'eventBoundary',
    handlerElementId: 'eventHandler',
    handlerTaskName: `${prefix}_${typeLabel}人工处理`,
    processInstanceId: ''
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let designer
  let starter
  let approver
  let eventCodeCreated = false
  let eventCodeDisabled = false
  let failed = true
  try {
    const bpmnEventPage = new WorkflowBpmnEventPage(admin.page)
    await bpmnEventPage.createEventCode({
      eventType,
      eventCode: assets.eventCode,
      eventName: assets.eventName,
      notificationPolicy: 'NONE',
      description: `${assets.prefix} 真实边界事件`
    })
    eventCodeCreated = true

    designer = await openRoleSession(browser, 'workflow_designer', testInfo)
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
    })
    await configuration.createTextForm({
      name: assets.formName, remark: `${assets.prefix} 业务边界事件表单`
    })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${assets.prefix} ${eventType} boundary`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.replaceTaskWithServiceTask('review')
    await designerPage.configureElementIdentity(
      'review', assets.raiseElementId, `${assets.prefix}_${typeLabel}产生器`
    )
    await designerPage.attachBoundaryEvent({
      paletteLabel: eventType === 'ERROR' ? '错误边界' : '升级边界',
      hostElementId: assets.raiseElementId,
      stableElementId: assets.boundaryElementId,
      elementName: `${assets.prefix}_${typeLabel}边界`,
      eventDefinitionLocalName: eventType === 'ERROR'
        ? 'errorEventDefinition' : 'escalationEventDefinition'
    })
    const eventOptionLabel = `${assets.eventName} · ${assets.eventCode}`
    await designerPage.configureBusinessBoundaryEvent({
      elementId: assets.boundaryElementId,
      eventOptionLabel,
      interrupting
    })
    const appendedHandler = await designerPage.appendUserTaskAfter(assets.boundaryElementId)
    await designerPage.configureCandidateRoleForElement(
      appendedHandler, '流程审批人', assets.handlerTaskName, assets.handlerElementId
    )
    await designerPage.connectShapes(assets.handlerElementId, 'end')
    await designerPage.configureBpmnEventRaiseService({
      elementId: assets.raiseElementId,
      eventType,
      eventOptionLabel,
      sourceLabel: '服务任务',
      operatorLabel: '始终触发'
    })

    const authorXml = await designerPage.readDesignerXml()
    expect(authorXml).toContain(`<serviceTask id="${assets.raiseElementId}"`)
    expect(authorXml).toContain('approva.raise-bpmn-event')
    expect(authorXml).toContain(assets.eventCode)
    expect(authorXml).toContain(`<boundaryEvent id="${assets.boundaryElementId}"`)
    expect(authorXml).toContain(`attachedToRef="${assets.raiseElementId}"`)
    expect(authorXml).toContain(eventType === 'ERROR'
      ? 'errorEventDefinition' : 'escalationEventDefinition')
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)

    const compiledXmlRows = queryReadOnly(
      `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_ WHERE p.KEY_='${sqlLiteral(assets.modelKey)}' ORDER BY p.VERSION_ DESC LIMIT 1`
    )
    expect(compiledXmlRows, '受控业务边界事件必须形成唯一部署 BPMN').toHaveLength(1)
    expect(compiledXmlRows[0][0]).toContain('${workflowExtensionDelegate}')
    expect(compiledXmlRows[0][0]).toContain(assets.eventCode)
    expect(compiledXmlRows[0][0]).toContain(`attachedToRef="${assets.raiseElementId}"`)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_${typeLabel}申请`)
    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_,NAME_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '业务事件捕获后必须只创建边界人工处理任务').toEqual([
      [assets.handlerElementId, assets.handlerTaskName]
    ])
    expect(queryReadOnly(
      `SELECT EVENT_TYPE,EVENT_CODE,SOURCE_ELEMENT_ID,MATCH_STATUS,BOUNDARY_EVENT_ID,INTERRUPTING FROM wf_bpmn_event_audit WHERE PROCESS_INSTANCE_ID='${escapedInstanceId}'`
    ), '业务事件必须形成唯一精确捕获审计').toEqual([[
      eventType,
      assets.eventCode,
      assets.raiseElementId,
      'CAPTURED',
      assets.boundaryElementId,
      interrupting ? '1' : '0'
    ]])
    expect(queryReadOnly(
      `SELECT COALESCE(TEXT_,CAST(DOUBLE_ AS CHAR),CAST(LONG_ AS CHAR),'') FROM ACT_HI_VARINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND NAME_='wfBpmnEventCode'`
    ), '受控产生器必须把冻结事件编码写入真实流程变量').toEqual([[assets.eventCode]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_='end' AND END_TIME_ IS NOT NULL`
    ), interrupting
      ? 'Error 中断边界捕获后主路径结束事件不得提前执行'
      : '非中断 Escalation 捕获后主路径结束事件必须继续执行')
      .toEqual([[interrupting ? '0' : '1']])

    await bpmnEventPage.expectCapturedAudit({
      processInstanceId: assets.processInstanceId,
      eventType,
      eventCode: assets.eventCode,
      sourceElementId: assets.raiseElementId,
      boundaryEventId: assets.boundaryElementId
    })
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimTask(assets.modelName, assets.handlerTaskName)
    await approverWorkbench.approveTask(
      assets.modelName, assets.handlerTaskName, `${assets.prefix}_${typeLabel}处理完成`
    )
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '边界人工处理完成后流程必须自然结束').toEqual([['1', '']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_='end' AND END_TIME_ IS NOT NULL`
    ), '主路径和边界路径的结束事件数量必须符合中断语义')
      .toEqual([[interrupting ? '1' : '2']])
    expect(queryReadOnly(
      `SELECT ACT_ID_,END_TIME_ IS NOT NULL FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_ IN ('${assets.raiseElementId}','${assets.boundaryElementId}','${assets.handlerElementId}') ORDER BY START_TIME_,ID_`
    ), '事件产生器、边界和人工处理必须全部进入真实历史').toEqual([
      [assets.raiseElementId, '1'],
      [assets.boundaryElementId, '1'],
      [assets.handlerElementId, '1']
    ])
    failed = false
  } finally {
    let eventCodeCleanupError = null
    if (eventCodeCreated && !eventCodeDisabled) {
      try {
        // 不可删除的正式编码通过 UI 停用，既保留不可变审计，也避免影响后续设计目录。
        await new WorkflowBpmnEventPage(admin.page).disableEventCode(assets.eventCode)
        eventCodeDisabled = true
      } catch (error) {
        eventCodeCleanupError = error
      }
    }
    await Promise.allSettled([
      approver?.close(failed), starter?.close(failed), designer?.close(failed), admin.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({ ...assets, eventCodeDisabled }, null, 2)),
      contentType: 'application/json'
    })
    expect(eventCodeCleanupError, '测试事件编码必须通过正式 UI 停用').toBeNull()
  }
}

test('@full [UI-BPMN-001] 展开子流程通过真实画布建模并完成内部审批', async ({ browser }, testInfo) => {
  const prefix = advancedPrefix(testInfo, 'embedded_sub')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_展开子流程审批`,
    modelKey: `${prefix}_model`,
    subProcessId: 'embeddedApproval',
    taskId: 'embeddedReview',
    taskName: `${prefix}_子流程审批`,
    processInstanceId: ''
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
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
    })
    await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${assets.prefix} 真实展开子流程链路`
    })
    await configuration.openDesigner(assets.modelKey)

    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.deleteElement('review')
    // 删除默认任务后 bpmn-js 会把原连线自动重接为 start -> end，先通过画布删除该直达线。
    const directFlowId = await designerPage.findSequenceFlowId('start', 'end')
    await designerPage.deleteSequenceFlow(directFlowId)
    await designerPage.createAdvancedElement({
      paletteLabel: '展开子流程',
      sourceElementId: 'start',
      stableElementId: assets.subProcessId,
      elementName: `${assets.prefix}_审批子流程`,
      offsetX: 260,
      offsetY: 180,
      expectedLocalName: 'subProcess'
    })
    const nestedStartId = await designerPage.nestedDirectChildId(assets.subProcessId, 'startEvent')
    const generatedTaskId = await designerPage.appendUserTaskAfter(nestedStartId)
    await designerPage.configureCandidateRoleForElement(
      generatedTaskId, '流程审批人', assets.taskName, assets.taskId
    )
    const nestedEndId = await designerPage.appendEndEventAfter(assets.taskId)
    await designerPage.configureElementIdentity(nestedEndId, 'embeddedEnd', `${assets.prefix}_子流程结束`)
    await designerPage.connectShapes('start', assets.subProcessId)
    await designerPage.connectShapes(assets.subProcessId, 'end')
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)

    const modelKey = sqlLiteral(assets.modelKey)
    const deployedXmlRows = queryReadOnly(
      `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_ WHERE p.KEY_='${modelKey}' ORDER BY p.VERSION_ DESC LIMIT 1`
    )
    expect(deployedXmlRows, '展开子流程部署必须形成唯一 BPMN 资源').toHaveLength(1)
    expect(deployedXmlRows[0][0], '部署资源必须保留子流程和内部审批节点')
      .toContain(`<subProcess id="${assets.subProcessId}"`)
    expect(deployedXmlRows[0][0]).toContain(`<userTask id="${assets.taskId}"`)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    // 记录发起页两项只读上下文请求，区分表单接口产品缺陷与测试等待方式错误。
    const contextEvidence = []
    const responseListener = async response => {
      const pathname = new URL(response.url()).pathname
      if (pathname.endsWith('/workflow/process/getProcessForm')
          || pathname.includes('/workflow/process/bpmnXml/')) {
        contextEvidence.push(await startContextEvidence(response))
      }
    }
    starter.page.on('response', responseListener)
    try {
      assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
        .startProcess(assets.modelName, `${assets.prefix}_申请内容`)
    } finally {
      starter.page.off('response', responseListener)
      await testInfo.attach('start-context-responses.json', {
        body: Buffer.from(JSON.stringify(contextEvidence, null, 2)), contentType: 'application/json'
      })
    }

    const instanceId = sqlLiteral(assets.processInstanceId)
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_,NAME_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${instanceId}'`
    ), '流程启动后必须只创建子流程内部审批任务').toEqual([[assets.taskId, assets.taskName]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_ACTINST WHERE PROC_INST_ID_='${instanceId}' AND ACT_ID_='${assets.subProcessId}' AND END_TIME_ IS NULL`
    )).toEqual([['1']])

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const workbench = new WorkflowWorkbenchPage(approver.page)
    await workbench.claimTask(assets.modelName, assets.taskName)
    await workbench.approveTask(assets.modelName, assets.taskName, `${assets.prefix}_子流程通过`)

    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${instanceId}'`
    )).toEqual([['1', '']])
    const activityRows = queryReadOnly(
      `SELECT ACT_ID_,ACT_TYPE_,END_TIME_ IS NOT NULL FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${instanceId}' AND ACT_ID_ IN ('${assets.subProcessId}','${assets.taskId}','embeddedEnd') ORDER BY START_TIME_,ID_`
    )
    expect(activityRows.map(row => row[0]), '子流程容器、内部审批和内部结束事件必须进入真实历史')
      .toEqual(expect.arrayContaining([assets.subProcessId, assets.taskId, 'embeddedEnd']))
    expect(activityRows.every(row => row[2] === '1'), '子流程内部活动必须全部自然结束').toBe(true)
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
})

test('@full [UI-BPMN-002] 调用活动通过真实目录映射变量并冻结发布时子流程版本', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  const prefix = advancedPrefix(testInfo, 'call_activity')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    childFormName: `${prefix}_子流程表单`,
    childModelName: `${prefix}_子流程`,
    childModelKey: `${prefix}_child`,
    childTaskV1: `${prefix}_子流程V1审批`,
    childTaskV2: `${prefix}_子流程V2审批`,
    parentFormName: `${prefix}_父流程表单`,
    parentModelName: `${prefix}_父流程`,
    parentModelKey: `${prefix}_parent`,
    callActivityId: 'callChild',
    parentInstanceId: '',
    childInstanceId: '',
    frozenChildDefinitionId: ''
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
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
    })
    await configuration.createPermissionFieldsForm({
      name: assets.childFormName,
      remark: `${assets.prefix} CallActivity 子流程字段`,
      fields: [
        { fieldName: 'childInput', label: '子流程输入', placeholder: '请输入子流程输入', required: false },
        { fieldName: 'childOutput', label: '子流程结果', placeholder: '请输入子流程结果', required: false }
      ]
    })
    await configuration.createPermissionFieldsForm({
      name: assets.parentFormName,
      remark: `${assets.prefix} CallActivity 父流程字段`,
      fields: [
        { fieldName: 'parentInput', label: '父流程输入', placeholder: '请输入父流程输入', required: false },
        { fieldName: 'parentOutput', label: '父流程结果', placeholder: '请输入父流程结果', required: false }
      ]
    })

    // 先发布子流程 V1，并冻结开始和审批节点的真实字段权限目录。
    await configuration.createModel({
      name: assets.childModelName,
      key: assets.childModelKey,
      categoryName: assets.categoryName,
      formName: assets.childFormName,
      description: `${assets.prefix} CallActivity 子流程 V1`
    })
    await configuration.openDesigner(assets.childModelKey)
    let designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureFormPermissionsForElement({
      elementId: 'start', formName: assets.childFormName, defaultMode: '可编辑',
      fieldModes: { 子流程输入: '必填', 子流程结果: '可编辑' }
    })
    await designerPage.configureCandidateRole('流程审批人', assets.childTaskV1)
    await designerPage.configureFormPermissionsForElement({
      elementId: 'review', formName: assets.childFormName, defaultMode: '可编辑',
      fieldModes: { 子流程输入: '只读', 子流程结果: '必填' }
    })
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.childModelKey)
    const childKey = sqlLiteral(assets.childModelKey)
    const childV1Rows = queryReadOnly(
      `SELECT ID_ FROM ACT_RE_PROCDEF WHERE KEY_='${childKey}' AND VERSION_=1`
    )
    expect(childV1Rows, '子流程 V1 必须形成唯一流程定义').toHaveLength(1)
    assets.frozenChildDefinitionId = childV1Rows[0][0]

    // 父流程从正式目录选择当时最新的 V1，并用结构化下拉配置 in/out 映射。
    await configuration.createModel({
      name: assets.parentModelName,
      key: assets.parentModelKey,
      categoryName: assets.categoryName,
      formName: assets.parentFormName,
      description: `${assets.prefix} CallActivity 父流程`
    })
    const catalogResponses = []
    const catalogListener = async response => {
      if (new URL(response.url()).pathname.endsWith('/workflow/call-activity/catalog')) {
        catalogResponses.push(await callActivityCatalogEvidence(response, assets.frozenChildDefinitionId))
      }
    }
    designer.page.on('response', catalogListener)
    try {
      await configuration.openDesigner(assets.parentModelKey)
      await expect.poll(() => catalogResponses.length, {
        message: '父流程设计器必须完成调用活动正式目录请求'
      }).toBeGreaterThan(0)
    } finally {
      designer.page.off('response', catalogListener)
      await testInfo.attach('call-activity-catalog.json', {
        body: Buffer.from(JSON.stringify(catalogResponses, null, 2)), contentType: 'application/json'
      })
    }
    const latestCatalog = catalogResponses.at(-1)
    expect(latestCatalog?.httpStatus, '调用活动目录 HTTP 状态').toBe(200)
    expect(latestCatalog?.code, '调用活动目录业务码').toBe(200)
    expect(latestCatalog?.targetPresent, '调用活动目录必须包含刚由 UI 发布的子流程 V1').toBe(true)
    expect(latestCatalog?.targetStatus, '调用活动目录目标版本必须处于启用状态').toBe('ACTIVE')
    expect(latestCatalog?.targetInputFields, '调用活动目录必须公开子流程开始节点输入字段')
      .toEqual(expect.arrayContaining(['childInput']))
    expect(latestCatalog?.targetOutputFields, '调用活动目录必须公开子流程节点输出字段')
      .toEqual(expect.arrayContaining(['childOutput']))
    designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureFormPermissionsForElement({
      elementId: 'start', formName: assets.parentFormName, defaultMode: '可编辑',
      fieldModes: { 父流程输入: '必填', 父流程结果: '可编辑' }
    })
    await designerPage.deleteElement('review')
    const parentDirectFlowId = await designerPage.findSequenceFlowId('start', 'end')
    await designerPage.deleteSequenceFlow(parentDirectFlowId)
    await designerPage.createAdvancedElement({
      paletteLabel: '调用活动',
      sourceElementId: 'start',
      stableElementId: assets.callActivityId,
      elementName: `${assets.prefix}_调用子流程`,
      offsetX: 230,
      offsetY: 80,
      expectedLocalName: 'callActivity'
    })
    await designerPage.connectShapes('start', assets.callActivityId)
    await designerPage.connectShapes(assets.callActivityId, 'end')
    await designerPage.configureCallActivity({
      elementId: assets.callActivityId,
      targetOption: {
        processName: assets.childModelName,
        processKey: assets.childModelKey,
        version: 1,
        status: '启用'
      },
      versionPolicy: '发布时最新版',
      businessKeyPolicy: '继承父流程',
      inheritVariables: false,
      processInstanceName: `${assets.prefix}_冻结子实例`,
      inputMappings: [
        { sourceLabel: '父流程输入（parentInput）· TEXT', targetLabel: '子流程输入（childInput）· TEXT' }
      ],
      outputMappings: [
        { sourceLabel: '子流程结果（childOutput）· TEXT', targetLabel: '父流程结果（parentOutput）· TEXT' }
      ],
      outputScope: '父流程变量'
    })
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.parentModelKey)

    const parentKey = sqlLiteral(assets.parentModelKey)
    const compiledParentRows = queryReadOnly(
      `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_ WHERE p.KEY_='${parentKey}' ORDER BY p.VERSION_ DESC LIMIT 1`
    )
    expect(compiledParentRows, '父流程必须形成唯一编译 BPMN').toHaveLength(1)
    expect(compiledParentRows[0][0], '父部署必须把发布时最新子流程冻结为精确定义 ID')
      .toContain(`calledElement="${assets.frozenChildDefinitionId}"`)
    expect(compiledParentRows[0][0]).toContain('flowable:calledElementType="id"')
    expect(compiledParentRows[0][0]).toContain('source="parentInput" target="childInput"')
    expect(compiledParentRows[0][0]).toContain('source="childOutput" target="parentOutput"')

    // 父流程发布后再通过真实设计器保存并部署子流程 V2，后续运行仍必须调用冻结的 V1。
    await configuration.openDesigner(assets.childModelKey)
    designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRole('流程审批人', assets.childTaskV2)
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.childModelKey)
    const childDefinitions = queryReadOnly(
      `SELECT VERSION_,ID_ FROM ACT_RE_PROCDEF WHERE KEY_='${childKey}' ORDER BY VERSION_`
    )
    expect(childDefinitions, '子流程必须保留 V1 并发布 V2').toHaveLength(2)
    expect(childDefinitions[0]).toEqual(['1', assets.frozenChildDefinitionId])
    expect(childDefinitions[1][0]).toBe('2')
    expect(childDefinitions[1][1]).not.toBe(assets.frozenChildDefinitionId)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const starterWorkbench = new WorkflowWorkbenchPage(starter.page)
    const createRow = await starterWorkbench.filterRow('/office/create', '请输入流程名称', assets.parentModelName)
    await createRow.locator('button').first().click()
    await expect(starter.page).toHaveURL(/\/workflow\/process-start\//u)
    const parentForm = starter.page.locator('.workflow-form-renderer')
    await parentForm.getByPlaceholder('请输入父流程输入').fill(`${assets.prefix}_映射输入`)
    const submitPromise = starter.page.waitForResponse(response => (
      response.request().method() === 'POST'
      && /\/workflow\/process\/draft\/[0-9a-f-]{36}\/submit$/iu.test(new URL(response.url()).pathname)
    ))
    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    const submitted = await (await submitPromise).json()
    expect(submitted?.code, '父流程正式提交业务码').toBe(200)
    assets.parentInstanceId = String(submitted.data?.processInstanceId || submitted.data?.id || '')
    expect(assets.parentInstanceId).not.toBe('')

    const parentInstance = sqlLiteral(assets.parentInstanceId)
    const childInstances = queryReadOnly(
      `SELECT PROC_INST_ID_,PROC_DEF_ID_ FROM ACT_HI_PROCINST WHERE SUPER_PROCESS_INSTANCE_ID_='${parentInstance}'`
    )
    expect(childInstances, 'CallActivity 必须创建唯一子流程实例').toHaveLength(1)
    assets.childInstanceId = childInstances[0][0]
    expect(childInstances[0][1], '父流程必须继续调用冻结的子流程 V1').toBe(assets.frozenChildDefinitionId)
    expect(queryReadOnly(
      `SELECT COALESCE(TEXT_,CAST(DOUBLE_ AS CHAR),CAST(LONG_ AS CHAR),'') FROM ACT_HI_VARINST WHERE PROC_INST_ID_='${sqlLiteral(assets.childInstanceId)}' AND NAME_='childInput'`
    )).toEqual([[`${assets.prefix}_映射输入`]])

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimTask(assets.childModelName, assets.childTaskV1)
    const todoRow = await approverWorkbench.filterTaskRow('/office/todo', assets.childModelName, assets.childTaskV1)
    await todoRow.locator('button').first().click()
    const childTaskForm = approver.page.locator('.workflow-form-renderer').first()
    await expect(childTaskForm.getByPlaceholder('请输入子流程输入')).toHaveValue(`${assets.prefix}_映射输入`)
    await expect(childTaskForm.getByPlaceholder('请输入子流程输入')).toBeDisabled()
    await childTaskForm.getByPlaceholder('请输入子流程结果').fill(`${assets.prefix}_映射输出`)
    await approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const completeDialog = approver.page.getByRole('dialog', { name: '通过任务' })
    await completeDialog.getByLabel('办理意见').fill(`${assets.prefix}_CallActivity通过`)
    await completeDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expect(approver.page.getByText('已完成', { exact: true }).first()).toBeVisible()

    const completedInstances = queryReadOnly(
      `SELECT PROC_INST_ID_,END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ IN ('${parentInstance}','${sqlLiteral(assets.childInstanceId)}') ORDER BY PROC_INST_ID_`
    )
    expect(completedInstances, '父子实例必须全部自然结束').toHaveLength(2)
    expect(completedInstances.every(row => row[1] === '1' && row[2] === '')).toBe(true)
    expect(queryReadOnly(
      `SELECT COALESCE(TEXT_,CAST(DOUBLE_ AS CHAR),CAST(LONG_ AS CHAR),'') FROM ACT_HI_VARINST WHERE PROC_INST_ID_='${parentInstance}' AND NAME_='parentOutput'`
    )).toEqual([[`${assets.prefix}_映射输出`]])
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
})

test('@full [UI-BPMN-003] 接收任务、消息和信号通过正式运行事件协议串行消费', async ({ browser, request }, testInfo) => {
  test.setTimeout(240_000)
  const prefix = advancedPrefix(testInfo, 'runtime_events')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_运行事件串行链`,
    modelKey: `${prefix}_model`,
    credentialName: `${prefix}_集成账号`,
    receiveTaskId: 'receiveWait',
    messageWaitId: 'messageWait',
    signalWaitId: 'signalWait',
    messageName: `${prefix}_message`,
    signalName: `${prefix}_signal`,
    processInstanceId: '',
    credentialId: '',
    requestIds: {
      RECEIVE: randomUUID(),
      MESSAGE: randomUUID(),
      SIGNAL: randomUUID()
    }
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let admin
  let integration
  let credential = null
  let credentialRevoked = false
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
    })
    await configuration.createTextForm({
      name: assets.formName, remark: `${assets.prefix} 运行事件真实串行链`
    })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${assets.prefix} ReceiveTask Message Signal`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.deleteElement('review')
    const directFlowId = await designerPage.findSequenceFlowId('start', 'end')
    await designerPage.deleteSequenceFlow(directFlowId)

    // 三个高级节点均从用户可见 Palette 真实拖放，并用连接工具按运行顺序建立顺序流。
    await designerPage.createAdvancedElement({
      paletteLabel: '接收任务',
      sourceElementId: 'start',
      stableElementId: assets.receiveTaskId,
      elementName: `${assets.prefix}_等待接收任务`,
      offsetX: 220,
      offsetY: -90,
      expectedLocalName: 'receiveTask'
    })
    await designerPage.connectShapes('start', assets.receiveTaskId)
    await designerPage.createAdvancedElement({
      paletteLabel: '消息捕获',
      sourceElementId: assets.receiveTaskId,
      stableElementId: assets.messageWaitId,
      elementName: `${assets.prefix}_等待消息`,
      offsetX: 210,
      offsetY: 90,
      expectedLocalName: 'intermediateCatchEvent'
    })
    await designerPage.configureEventReference(assets.messageWaitId, assets.messageName)
    await designerPage.connectShapes(assets.receiveTaskId, assets.messageWaitId)
    await designerPage.createAdvancedElement({
      paletteLabel: '信号捕获',
      sourceElementId: assets.messageWaitId,
      stableElementId: assets.signalWaitId,
      elementName: `${assets.prefix}_等待信号`,
      offsetX: 0,
      offsetY: 180,
      expectedLocalName: 'intermediateCatchEvent'
    })
    await designerPage.configureEventReference(assets.signalWaitId, assets.signalName)
    await designerPage.connectShapes(assets.messageWaitId, assets.signalWaitId)
    await designerPage.connectShapes(assets.signalWaitId, 'end')

    const authorXml = await designerPage.readDesignerXml()
    expect(authorXml).toContain(`<receiveTask id="${assets.receiveTaskId}"`)
    expect(authorXml).toContain(`id="Message_${assets.messageName}"`)
    expect(authorXml).toContain(`name="${assets.messageName}"`)
    expect(authorXml).toContain(`id="Signal_${assets.signalName}"`)
    expect(authorXml).toContain(`name="${assets.signalName}"`)
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)

    const deployedXmlRows = queryReadOnly(
      `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_ WHERE p.KEY_='${sqlLiteral(assets.modelKey)}' ORDER BY p.VERSION_ DESC LIMIT 1`
    )
    expect(deployedXmlRows, '运行事件串行链必须形成唯一部署 BPMN').toHaveLength(1)
    expect(deployedXmlRows[0][0]).toContain(`<receiveTask id="${assets.receiveTaskId}"`)
    expect(deployedXmlRows[0][0]).toContain('messageEventDefinition')
    expect(deployedXmlRows[0][0]).toContain('signalEventDefinition')

    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    integration = new WorkflowIntegrationPage(admin.page)
    credential = await integration.createCredential({
      name: assets.credentialName,
      scopes: ['RECEIVE', 'MESSAGE', 'SIGNAL'],
      allowedVariables: ['receiveValue', 'messageValue', 'signalValue'],
      rateLimitPerMinute: 60
    })
    assets.credentialId = credential.credentialId

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_运行事件申请`)
    const escapedInstanceId = sqlLiteral(assets.processInstanceId)

    /**
     * 调用一类正式运行事件协议，并验证首次处理与同 requestId 重放返回同一执行结果。
     * @param {'RECEIVE'|'MESSAGE'|'SIGNAL'} eventType 正式运行事件类型。
     * @param {string} eventName ReceiveTask activityId、消息名或信号名。
     * @param {string} endpoint 正式协议相对路径。
     * @param {Record<string,string>} variables 本步骤白名单内的标量变量。
     * @returns {Promise<{requestId:string,eventType:string,eventName:string,matchedExecutionId:string}>} 脱敏后的处理证据。
     */
    const publishRuntimeEvent = async (eventType, eventName, endpoint, variables) => {
      const requestId = assets.requestIds[eventType]
      const protocolPayload = {
        requestId,
        eventName,
        processInstanceId: assets.processInstanceId,
        businessKey: null,
        variables
      }
      const protocolUrl = new URL(endpoint, testInfo.project.use.baseURL).toString()
      const firstResponse = await request.post(protocolUrl, {
        headers: { 'X-Integration-Token': credential.token },
        data: protocolPayload
      })
      expect(firstResponse.status(), `${eventType} 正式协议 HTTP 状态`).toBe(200)
      const firstPayload = await firstResponse.json()
      expect(firstPayload.code, `${eventType} 正式协议业务码`).toBe(200)
      expect(firstPayload.data?.status).toBe('PROCESSED')
      expect(firstPayload.data?.resultCode).toBe('EVENT_PROCESSED')
      expect(firstPayload.data?.matchedProcessInstanceId).toBe(assets.processInstanceId)
      expect(firstPayload.data?.matchedExecutionId).toBeTruthy()

      const replayResponse = await request.post(protocolUrl, {
        headers: { 'X-Integration-Token': credential.token },
        data: protocolPayload
      })
      expect(replayResponse.status(), `${eventType} 幂等重放 HTTP 状态`).toBe(200)
      const replayPayload = await replayResponse.json()
      expect(replayPayload.code, `${eventType} 幂等重放业务码`).toBe(200)
      expect(replayPayload.data).toEqual(firstPayload.data)
      expect(queryReadOnly(
        `SELECT EVENT_TYPE,EVENT_NAME,CORRELATION_VALUE,STATUS,RESULT_CODE,MATCHED_PROCESS_INSTANCE_ID FROM wf_runtime_event_request WHERE REQUEST_ID='${sqlLiteral(requestId)}'`
      ), `${eventType} 同一 requestId 只能保留一条成功台账`).toEqual([[
        eventType, eventName, assets.processInstanceId, 'PROCESSED', 'EVENT_PROCESSED', assets.processInstanceId
      ]])
      return {
        requestId,
        eventType,
        eventName,
        matchedExecutionId: String(firstPayload.data?.matchedExecutionId || '')
      }
    }

    // 启动后必须唯一停在 ReceiveTask，且尚未提前建立后续消息或信号订阅。
    expect(queryReadOnly(
      `SELECT ACT_ID_ FROM ACT_RU_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND END_TIME_ IS NULL AND ACT_TYPE_ NOT IN ('process') ORDER BY ACT_ID_`
    ), '流程启动后必须唯一停在 ReceiveTask').toEqual([[assets.receiveTaskId]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_='${escapedInstanceId}'`
    )).toEqual([['0']])
    const protocolEvidence = []
    protocolEvidence.push(await publishRuntimeEvent(
      'RECEIVE', assets.receiveTaskId, '/dev-api/workflow/runtime-event/receive',
      { receiveValue: `${assets.prefix}_receive` }
    ))

    // ReceiveTask 被唯一消费后，执行必须串行推进到对应 Message Catch。
    expect(queryReadOnly(
      `SELECT EVENT_TYPE_,EVENT_NAME_,ACTIVITY_ID_ FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), 'ReceiveTask 消费后必须唯一停在消息订阅').toEqual([['message', assets.messageName, assets.messageWaitId]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_='${assets.receiveTaskId}' AND END_TIME_ IS NOT NULL`
    )).toEqual([['1']])
    protocolEvidence.push(await publishRuntimeEvent(
      'MESSAGE', assets.messageName, '/dev-api/workflow/runtime-event/message',
      { messageValue: `${assets.prefix}_message` }
    ))

    // Message 被唯一消费后只能推进到当前实例的 Signal Catch，不允许广播或跳过等待态。
    expect(queryReadOnly(
      `SELECT EVENT_TYPE_,EVENT_NAME_,ACTIVITY_ID_ FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), 'Message 消费后必须唯一停在信号订阅').toEqual([['signal', assets.signalName, assets.signalWaitId]])
    protocolEvidence.push(await publishRuntimeEvent(
      'SIGNAL', assets.signalName, '/dev-api/workflow/runtime-event/signal',
      { signalValue: `${assets.prefix}_signal` }
    ))
    credential.token = ''

    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '三类运行事件依次消费后流程必须自然结束').toEqual([['1', '']])
    expect(queryReadOnly(
      `SELECT ACT_ID_,END_TIME_ IS NOT NULL FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_ IN ('${assets.receiveTaskId}','${assets.messageWaitId}','${assets.signalWaitId}') ORDER BY START_TIME_,ID_`
    ), 'ReceiveTask、Message Catch 和 Signal Catch 必须按序进入完整历史').toEqual([
      [assets.receiveTaskId, '1'], [assets.messageWaitId, '1'], [assets.signalWaitId, '1']
    ])
    expect(queryReadOnly(
      `SELECT NAME_,COALESCE(TEXT_,CAST(DOUBLE_ AS CHAR),CAST(LONG_ AS CHAR),'') FROM ACT_HI_VARINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND NAME_ IN ('receiveValue','messageValue','signalValue') ORDER BY NAME_`
    ), '三次正式协议提交的白名单变量必须真实持久化').toEqual([
      ['messageValue', `${assets.prefix}_message`],
      ['receiveValue', `${assets.prefix}_receive`],
      ['signalValue', `${assets.prefix}_signal`]
    ])
    expect(queryReadOnly(
      `SELECT EVENT_TYPE,STATUS,RESULT_CODE FROM wf_runtime_event_request WHERE REQUEST_ID IN ('${sqlLiteral(assets.requestIds.RECEIVE)}','${sqlLiteral(assets.requestIds.MESSAGE)}','${sqlLiteral(assets.requestIds.SIGNAL)}') ORDER BY FIELD(EVENT_TYPE,'RECEIVE','MESSAGE','SIGNAL')`
    ), '三类正式运行事件必须各形成一条成功台账').toEqual([
      ['RECEIVE', 'PROCESSED', 'EVENT_PROCESSED'],
      ['MESSAGE', 'PROCESSED', 'EVENT_PROCESSED'],
      ['SIGNAL', 'PROCESSED', 'EVENT_PROCESSED']
    ])
    for (const evidence of protocolEvidence) {
      await integration.expectRuntimeEventAudit({
        requestId: evidence.requestId,
        eventName: evidence.eventName,
        status: '已处理',
        resultCode: 'EVENT_PROCESSED'
      })
    }
    await integration.revokeCredential(assets.credentialName)
    credentialRevoked = true
    expect(queryReadOnly(
      `SELECT REVOKED_AT IS NOT NULL FROM wf_integration_credential WHERE CREDENTIAL_ID=${Number(credential.credentialId)}`
    )).toEqual([['1']])
    await testInfo.attach('runtime-event-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId: assets.processInstanceId,
        credentialId: assets.credentialId,
        credentialRevoked,
        protocolEvidence
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    let credentialCleanupError = null
    if (integration && credential?.credentialId && !credentialRevoked) {
      try {
        // 失败路径也必须通过正式 UI 吊销一次性凭据，避免有效外部访问能力遗留到开发环境。
        await integration.revokeCredential(assets.credentialName)
      } catch (error) {
        credentialCleanupError = error
      } finally {
        credential.token = ''
      }
    }
    await Promise.allSettled([
      starter?.close(failed), admin?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
    expect(credentialCleanupError, '失败路径必须通过正式 UI 吊销一次性集成账号').toBeNull()
  }
})

test('@full [UI-BPMN-004] BPMN Error 由受控服务任务产生并经中断边界进入人工处理', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await executeBusinessBoundaryScenario({
    browser,
    testInfo,
    eventType: 'ERROR',
    interrupting: true,
    domain: 'error_boundary'
  })
})

test('@full [UI-BPMN-005] BPMN Escalation 由受控服务任务产生并经非中断边界并行处理', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await executeBusinessBoundaryScenario({
    browser,
    testInfo,
    eventType: 'ESCALATION',
    interrupting: false,
    domain: 'escalation_boundary'
  })
})

test('@full [UI-BPMN-006] Timer 边界按中断语义取消或保留原审批任务', async ({ browser }, testInfo) => {
  test.setTimeout(360_000)
  const prefix = advancedPrefix(testInfo, 'timer_boundary')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_Timer边界`,
    modelKey: `${prefix}_model`,
    hostElementId: 'timerReview',
    hostTaskName: `${prefix}_等待审批`,
    nonInterruptBoundaryId: 'nonInterruptTimer',
    nonInterruptHandlerId: 'nonInterruptHandler',
    nonInterruptHandlerName: `${prefix}_非中断超时处理`,
    interruptBoundaryId: 'interruptTimer',
    interruptHandlerId: 'interruptHandler',
    interruptHandlerName: `${prefix}_中断超时处理`,
    nonInterruptExpression: 'PT3S',
    interruptExpression: 'PT30S',
    processInstanceId: ''
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
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
    })
    await configuration.createTextForm({
      name: assets.formName, remark: `${assets.prefix} Timer 边界真实运行表单`
    })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${assets.prefix} interrupting and non-interrupting timer boundary`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRoleForElement(
      'review', '流程审批人', assets.hostTaskName, assets.hostElementId
    )

    // 同一审批活动挂载两种 Timer，较大的到期时间差用于稳定观察非中断触发后的中间状态。
    await designerPage.attachBoundaryEvent({
      paletteLabel: '补偿边界',
      hostElementId: assets.hostElementId,
      stableElementId: assets.nonInterruptBoundaryId,
      elementName: `${assets.prefix}_非中断Timer`,
      eventDefinitionLocalName: 'compensateEventDefinition'
    })
    await designerPage.replaceBoundaryWithTimer(assets.nonInterruptBoundaryId, false)
    const nonInterruptCommit = await designerPage.configureTimerBoundary({
      elementId: assets.nonInterruptBoundaryId,
      timerTypeLabel: '持续时间',
      expression: assets.nonInterruptExpression
    })
    const nonInterruptHandler = await designerPage.appendUserTaskAfter(assets.nonInterruptBoundaryId)
    await designerPage.configureCandidateRoleForElement(
      nonInterruptHandler,
      '流程审批人',
      assets.nonInterruptHandlerName,
      assets.nonInterruptHandlerId
    )
    await designerPage.connectShapes(assets.nonInterruptHandlerId, 'end')

    await designerPage.attachBoundaryEvent({
      paletteLabel: '补偿边界',
      hostElementId: assets.hostElementId,
      stableElementId: assets.interruptBoundaryId,
      elementName: `${assets.prefix}_中断Timer`,
      eventDefinitionLocalName: 'compensateEventDefinition'
    })
    await designerPage.replaceBoundaryWithTimer(assets.interruptBoundaryId, true)
    const interruptCommit = await designerPage.configureTimerBoundary({
      elementId: assets.interruptBoundaryId,
      timerTypeLabel: '持续时间',
      expression: assets.interruptExpression
    })
    const interruptHandler = await designerPage.appendUserTaskAfter(assets.interruptBoundaryId)
    await designerPage.configureCandidateRoleForElement(
      interruptHandler,
      '流程审批人',
      assets.interruptHandlerName,
      assets.interruptHandlerId
    )
    await designerPage.connectShapes(assets.interruptHandlerId, 'end')

    const authorXml = await designerPage.readDesignerXml()
    const authorDocument = new DOMParser().parseFromString(authorXml, 'application/xml')
    const timerEvidence = [
      {
        boundaryId: assets.nonInterruptBoundaryId,
        expression: assets.nonInterruptExpression,
        interrupting: false,
        visibleValueAfterCommit: nonInterruptCommit.visibleValueAfterCommit
      },
      {
        boundaryId: assets.interruptBoundaryId,
        expression: assets.interruptExpression,
        interrupting: true,
        visibleValueAfterCommit: interruptCommit.visibleValueAfterCommit
      }
    ].map(expectedTimer => {
      const boundary = [...authorDocument.getElementsByTagNameNS('*', 'boundaryEvent')]
        .find(element => element.getAttribute('id') === expectedTimer.boundaryId)
      const timerDefinition = boundary?.getElementsByTagNameNS('*', 'timerEventDefinition')?.[0]
      const duration = timerDefinition?.getElementsByTagNameNS('*', 'timeDuration')?.[0]
      return {
        ...expectedTimer,
        attachedToRef: boundary?.getAttribute('attachedToRef') || '',
        authorInterrupting: boundary ? boundary.getAttribute('cancelActivity') !== 'false' : null,
        authorExpression: duration?.textContent || ''
      }
    })
    await testInfo.attach('timer-author-evidence.json', {
      body: Buffer.from(JSON.stringify(timerEvidence, null, 2)), contentType: 'application/json'
    })
    for (const timer of timerEvidence) {
      expect(timer.attachedToRef, `${timer.boundaryId} 必须附着到目标审批活动`).toBe(assets.hostElementId)
      expect(timer.authorInterrupting, `${timer.boundaryId} 必须保存目标中断语义`).toBe(timer.interrupting)
      expect(timer.authorExpression, `${timer.boundaryId} 必须把用户提交的 Timer 表达式写入作者 XML`)
        .toBe(timer.expression)
    }

    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)
    const deployedXmlRows = queryReadOnly(
      `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_ WHERE p.KEY_='${sqlLiteral(assets.modelKey)}' ORDER BY p.VERSION_ DESC LIMIT 1`
    )
    expect(deployedXmlRows, 'Timer 边界流程必须形成唯一部署 BPMN').toHaveLength(1)
    const deployedDocument = new DOMParser().parseFromString(deployedXmlRows[0][0], 'application/xml')
    const deployedDurations = [...deployedDocument.getElementsByTagNameNS('*', 'timeDuration')]
      .map(duration => duration.textContent).sort()
    expect(deployedDurations, '部署 BPMN 必须冻结两个 Timer 持续时间表达式')
      .toEqual([assets.interruptExpression, assets.nonInterruptExpression].sort())

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_Timer申请`)
    const instanceId = sqlLiteral(assets.processInstanceId)
    const initialTasks = queryReadOnly(
      `SELECT ID_,TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${instanceId}' ORDER BY TASK_DEF_KEY_`
    )
    expect(initialTasks, '流程启动后必须只有原审批任务').toHaveLength(1)
    expect(initialTasks[0][1]).toBe(assets.hostElementId)
    const originalTaskId = initialTasks[0][0]
    expect(queryReadOnly(
      `SELECT ELEMENT_ID_ FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}' ORDER BY ELEMENT_ID_`
    ), '原审批任务必须同时登记两个真实 Timer 作业').toEqual([
      [assets.interruptBoundaryId], [assets.nonInterruptBoundaryId]
    ])

    await expect.poll(() => queryReadOnly(
      `SELECT ID_,TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${instanceId}' ORDER BY TASK_DEF_KEY_`
    ), { timeout: 25_000, message: '非中断 Timer 到期后必须保留原审批并创建旁路任务' }).toEqual([
      [expect.any(String), assets.nonInterruptHandlerId],
      [originalTaskId, assets.hostElementId]
    ])
    expect(queryReadOnly(
      `SELECT ELEMENT_ID_ FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}' ORDER BY ELEMENT_ID_`
    ), '非中断 Timer 消费后只允许保留后续中断 Timer').toEqual([[assets.interruptBoundaryId]])

    await expect.poll(() => queryReadOnly(
      `SELECT TASK_DEF_KEY_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${instanceId}' ORDER BY TASK_DEF_KEY_`
    ), { timeout: 55_000, message: '中断 Timer 到期后必须取消原审批并创建中断处理任务' }).toEqual([
      [assets.interruptHandlerId], [assets.nonInterruptHandlerId]
    ])
    await expect.poll(() => queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TIMER_JOB WHERE PROCESS_INSTANCE_ID_='${instanceId}'`
    ), { timeout: 15_000, message: '两个 Timer 触发后不得遗留运行作业' }).toEqual([['0']])
    const interruptedTask = queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_TASKINST WHERE PROC_INST_ID_='${instanceId}' AND TASK_DEF_KEY_='${assets.hostElementId}'`
    )
    expect(interruptedTask, '中断 Timer 必须形成唯一原审批历史').toHaveLength(1)
    expect(interruptedTask[0][0]).toBe('1')
    expect(interruptedTask[0][1], '原审批任务必须记录非空取消原因').not.toBe('')

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimTask(assets.modelName, assets.nonInterruptHandlerName)
    await approverWorkbench.approveTask(
      assets.modelName, assets.nonInterruptHandlerName, `${assets.prefix}_非中断处理完成`, false
    )
    await approverWorkbench.claimTask(assets.modelName, assets.interruptHandlerName)
    await approverWorkbench.approveTask(
      assets.modelName, assets.interruptHandlerName, `${assets.prefix}_中断处理完成`
    )

    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${instanceId}'`
    ), '两条 Timer 旁路处理完成后流程必须自然结束').toEqual([['1', '']])
    expect(queryReadOnly(
      `SELECT ACT_ID_,COUNT(*),SUM(END_TIME_ IS NOT NULL) FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${instanceId}' AND ACT_ID_ IN ('${assets.nonInterruptBoundaryId}','${assets.interruptBoundaryId}') GROUP BY ACT_ID_ ORDER BY ACT_ID_`
    ), '中断与非中断 Timer 边界必须各执行一次并完整结束').toEqual([
      [assets.interruptBoundaryId, '1', '1'],
      [assets.nonInterruptBoundaryId, '1', '1']
    ])
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_,END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_TASKINST WHERE PROC_INST_ID_='${instanceId}' AND TASK_DEF_KEY_ IN ('${assets.nonInterruptHandlerId}','${assets.interruptHandlerId}') ORDER BY TASK_DEF_KEY_`
    ), '两条 Timer 人工处理任务必须自然完成且无删除原因').toEqual([
      [assets.interruptHandlerId, '1', ''],
      [assets.nonInterruptHandlerId, '1', '']
    ])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${instanceId}' AND ACT_ID_='end' AND END_TIME_ IS NOT NULL`
    ), '两个边界分支必须各自到达结束事件').toEqual([['2']])
    await testInfo.attach('timer-runtime-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId: assets.processInstanceId,
        originalTaskId,
        interruptedDeleteReason: interruptedTask[0][1]
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await Promise.allSettled([
      approver?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-BPMN-007] 非中断 Signal 事件子流程与主 ReceiveTask 并行运行', async ({ browser, request }, testInfo) => {
  test.setTimeout(300_000)
  const prefix = advancedPrefix(testInfo, 'signal_event_sub')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_Signal事件子流程`,
    modelKey: `${prefix}_model`,
    mainReceiveId: 'mainReceive',
    eventSubProcessId: 'signalEventSubProcess',
    eventStartId: 'signalEventStart',
    eventTaskId: 'signalEventReview',
    eventTaskName: `${prefix}_事件子流程审批`,
    eventEndId: 'signalEventEnd',
    signalName: `${prefix}_signal`,
    credentialName: `${prefix}_集成账号`,
    processInstanceId: '',
    credentialId: '',
    requestIds: {
      SIGNAL: randomUUID(),
      RECEIVE: randomUUID()
    }
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let admin
  let integration
  let credential = null
  let credentialRevoked = false
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
    })
    await configuration.createTextForm({
      name: assets.formName, remark: `${assets.prefix} 非中断 Signal 事件子流程表单`
    })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${assets.prefix} non-interrupting signal event subprocess`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.deleteElement('review')
    const directFlowId = await designerPage.findSequenceFlowId('start', 'end')
    await designerPage.deleteSequenceFlow(directFlowId)

    // 主路径停在 ReceiveTask；事件子流程不连接顶层顺序流，只由非中断 Signal Start 激活。
    await designerPage.createAdvancedElement({
      paletteLabel: '接收任务',
      sourceElementId: 'start',
      stableElementId: assets.mainReceiveId,
      elementName: `${assets.prefix}_主路径等待接收`,
      offsetX: 230,
      offsetY: -80,
      expectedLocalName: 'receiveTask'
    })
    await designerPage.connectShapes('start', assets.mainReceiveId)
    await designerPage.connectShapes(assets.mainReceiveId, 'end')
    await designerPage.createAdvancedElement({
      paletteLabel: '事件子流程',
      sourceElementId: 'start',
      stableElementId: assets.eventSubProcessId,
      elementName: `${assets.prefix}_Signal事件子流程`,
      offsetX: 260,
      offsetY: 190,
      expectedLocalName: 'subProcess'
    })
    const generatedEventStartId = await designerPage.nestedDirectChildId(
      assets.eventSubProcessId, 'startEvent'
    )
    await designerPage.replaceEventSubProcessStartWithSignal(generatedEventStartId, false)
    await designerPage.configureElementIdentity(
      generatedEventStartId, assets.eventStartId, `${assets.prefix}_非中断Signal开始`
    )
    await designerPage.configureEventReference(assets.eventStartId, assets.signalName, '开始节点')
    const generatedEventTaskId = await designerPage.appendUserTaskAfter(assets.eventStartId)
    await designerPage.configureCandidateRoleForElement(
      generatedEventTaskId, '流程审批人', assets.eventTaskName, assets.eventTaskId
    )
    const generatedEventEndId = await designerPage.appendEndEventAfter(assets.eventTaskId)
    await designerPage.configureElementIdentity(
      generatedEventEndId, assets.eventEndId, `${assets.prefix}_事件子流程结束`
    )

    const authorXml = await designerPage.readDesignerXml()
    const authorDocument = new DOMParser().parseFromString(authorXml, 'application/xml')
    const eventSubProcesses = [...authorDocument.getElementsByTagNameNS('*', 'subProcess')]
      .filter(element => element.getAttribute('id') === assets.eventSubProcessId)
    expect(eventSubProcesses, '作者 BPMN 必须包含唯一事件子流程').toHaveLength(1)
    expect(eventSubProcesses[0].getAttribute('triggeredByEvent')).toBe('true')
    const eventStarts = [...eventSubProcesses[0].childNodes]
      .filter(node => node.nodeType === 1 && node.localName === 'startEvent'
        && node.getAttribute('id') === assets.eventStartId)
    expect(eventStarts, '事件子流程必须包含唯一内部 Signal Start').toHaveLength(1)
    expect(eventStarts[0].getAttribute('isInterrupting')).toBe('false')
    const signalDefinitions = eventStarts[0].getElementsByTagNameNS('*', 'signalEventDefinition')
    expect(signalDefinitions).toHaveLength(1)
    const signalRef = signalDefinitions[0].getAttribute('signalRef')
    expect(signalRef, '事件子流程 Signal Start 必须引用 Definitions 根 Signal').not.toBe('')
    const rootSignals = [...authorDocument.getElementsByTagNameNS('*', 'signal')]
      .filter(signal => signal.getAttribute('id') === signalRef
        && signal.getAttribute('name') === assets.signalName)
    expect(rootSignals, 'Definitions 根必须保存唯一 Signal 名称').toHaveLength(1)
    expect([...authorDocument.getElementsByTagNameNS('*', 'sequenceFlow')]
      .filter(flow => flow.getAttribute('sourceRef') === assets.eventSubProcessId
        || flow.getAttribute('targetRef') === assets.eventSubProcessId),
    '事件子流程不得接入顶层顺序流').toHaveLength(0)
    await testInfo.attach('event-subprocess-author-evidence.json', {
      body: Buffer.from(JSON.stringify({
        eventSubProcessId: assets.eventSubProcessId,
        triggeredByEvent: eventSubProcesses[0].getAttribute('triggeredByEvent'),
        eventStartId: assets.eventStartId,
        interrupting: eventStarts[0].getAttribute('isInterrupting') !== 'false',
        signalRef,
        signalName: assets.signalName
      }, null, 2)),
      contentType: 'application/json'
    })

    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)
    const deployedXmlRows = queryReadOnly(
      `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_ WHERE p.KEY_='${sqlLiteral(assets.modelKey)}' ORDER BY p.VERSION_ DESC LIMIT 1`
    )
    expect(deployedXmlRows, '事件子流程必须形成唯一部署 BPMN').toHaveLength(1)
    expect(deployedXmlRows[0][0]).toContain(`<subProcess id="${assets.eventSubProcessId}"`)
    expect(deployedXmlRows[0][0]).toContain('triggeredByEvent="true"')
    expect(deployedXmlRows[0][0]).toContain(`<receiveTask id="${assets.mainReceiveId}"`)
    expect(deployedXmlRows[0][0]).toContain(`name="${assets.signalName}"`)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    // 发起页上下文证据用于区分事件子流程运行缺陷与已知递归开始节点表单门禁。
    const contextEvidence = []
    const responseListener = async response => {
      const pathname = new URL(response.url()).pathname
      if (pathname.endsWith('/workflow/process/getProcessForm')
          || pathname.includes('/workflow/process/bpmnXml/')) {
        contextEvidence.push(await startContextEvidence(response))
      }
    }
    starter.page.on('response', responseListener)
    try {
      assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
        .startProcess(assets.modelName, `${assets.prefix}_事件子流程申请`)
    } finally {
      starter.page.off('response', responseListener)
      await testInfo.attach('start-context-responses.json', {
        body: Buffer.from(JSON.stringify(contextEvidence, null, 2)), contentType: 'application/json'
      })
    }
    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    expect(queryReadOnly(
      `SELECT ACT_ID_ FROM ACT_RU_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND END_TIME_ IS NULL AND ACT_TYPE_ NOT IN ('process') ORDER BY ACT_ID_`
    ), '流程启动后主路径必须停在 ReceiveTask').toEqual([[assets.mainReceiveId]])
    expect(queryReadOnly(
      `SELECT EVENT_TYPE_,EVENT_NAME_,ACTIVITY_ID_ FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '流程启动后必须登记事件子流程 Signal Start 订阅').toEqual([
      ['signal', assets.signalName, assets.eventStartId]
    ])

    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    integration = new WorkflowIntegrationPage(admin.page)
    credential = await integration.createCredential({
      name: assets.credentialName,
      scopes: ['SIGNAL', 'RECEIVE'],
      allowedVariables: ['eventSubValue', 'receiveValue'],
      rateLimitPerMinute: 60
    })
    assets.credentialId = credential.credentialId

    /**
     * 向本用例的正式运行事件入口提交一次受控协议请求。
     * @param {'SIGNAL'|'RECEIVE'} eventType 正式运行事件类型。
     * @param {string} eventName Signal 名称或 ReceiveTask activityId。
     * @param {string} endpoint 正式协议相对路径。
     * @param {Record<string,string>} variables 当前凭据白名单内的流程变量。
     * @returns {Promise<object>} 不含凭据正文的协议处理结果。
     */
    const publishRuntimeEvent = async (eventType, eventName, endpoint, variables) => {
      const response = await request.post(new URL(endpoint, testInfo.project.use.baseURL).toString(), {
        headers: { 'X-Integration-Token': credential.token },
        data: {
          requestId: assets.requestIds[eventType],
          eventName,
          processInstanceId: assets.processInstanceId,
          businessKey: null,
          variables
        }
      })
      expect(response.status(), `${eventType} 正式协议 HTTP 状态`).toBe(200)
      const payload = await response.json()
      expect(payload.code, `${eventType} 正式协议业务码`).toBe(200)
      expect(payload.data?.status).toBe('PROCESSED')
      expect(payload.data?.resultCode).toBe('EVENT_PROCESSED')
      expect(payload.data?.matchedProcessInstanceId).toBe(assets.processInstanceId)
      return payload.data
    }

    const signalResult = await publishRuntimeEvent(
      'SIGNAL', assets.signalName, '/dev-api/workflow/runtime-event/signal',
      { eventSubValue: `${assets.prefix}_signal` }
    )
    expect(queryReadOnly(
      `SELECT TASK_DEF_KEY_,NAME_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), 'Signal 必须只创建事件子流程人工审批任务').toEqual([
      [assets.eventTaskId, assets.eventTaskName]
    ])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_='${assets.mainReceiveId}' AND END_TIME_ IS NULL`
    ), '非中断 Signal 不得结束主 ReceiveTask').toEqual([['1']])

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimTask(assets.modelName, assets.eventTaskName)
    await approverWorkbench.approveTask(
      assets.modelName, assets.eventTaskName, `${assets.prefix}_事件子流程处理完成`, false
    )
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_TASK WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '事件子流程完成后主路径等待期间不得遗留人工任务').toEqual([['0']])
    expect(queryReadOnly(
      `SELECT EVENT_TYPE_,EVENT_NAME_,ACTIVITY_ID_ FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '非中断事件子流程完成一次后必须保留 Signal 订阅直到主流程结束').toEqual([
      ['signal', assets.signalName, assets.eventStartId]
    ])

    const receiveResult = await publishRuntimeEvent(
      'RECEIVE', assets.mainReceiveId, '/dev-api/workflow/runtime-event/receive',
      { receiveValue: `${assets.prefix}_receive` }
    )
    credential.token = ''
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), 'ReceiveTask 消费后主流程必须自然结束').toEqual([['1', '']])
    expect(queryReadOnly(
      `SELECT ACT_ID_,END_TIME_ IS NOT NULL FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_ IN ('${assets.mainReceiveId}','${assets.eventSubProcessId}','${assets.eventStartId}','${assets.eventTaskId}','${assets.eventEndId}') ORDER BY START_TIME_,ID_`
    ).map(row => row[0]), '主等待和事件子流程全部活动必须进入真实历史').toEqual(
      expect.arrayContaining([
        assets.mainReceiveId,
        assets.eventSubProcessId,
        assets.eventStartId,
        assets.eventTaskId,
        assets.eventEndId
      ])
    )
    expect(queryReadOnly(
      `SELECT NAME_,COALESCE(TEXT_,CAST(DOUBLE_ AS CHAR),CAST(LONG_ AS CHAR),'') FROM ACT_HI_VARINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND NAME_ IN ('eventSubValue','receiveValue') ORDER BY NAME_`
    ), 'Signal 与 Receive 正式协议变量必须真实持久化').toEqual([
      ['eventSubValue', `${assets.prefix}_signal`],
      ['receiveValue', `${assets.prefix}_receive`]
    ])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EVENT_SUBSCR WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '主流程结束后事件子流程 Signal 订阅必须清理').toEqual([['0']])
    await integration.revokeCredential(assets.credentialName)
    credentialRevoked = true
    await testInfo.attach('event-subprocess-runtime-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId: assets.processInstanceId,
        credentialId: assets.credentialId,
        signalMatchedExecutionId: signalResult?.matchedExecutionId || '',
        receiveMatchedExecutionId: receiveResult?.matchedExecutionId || '',
        credentialRevoked
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    let credentialCleanupError = null
    if (integration && credential?.credentialId && !credentialRevoked) {
      try {
        // 失败路径也必须通过正式 UI 吊销一次性凭据，避免有效外部访问能力遗留。
        await integration.revokeCredential(assets.credentialName)
      } catch (error) {
        credentialCleanupError = error
      } finally {
        credential.token = ''
      }
    }
    await Promise.allSettled([
      approver?.close(failed), admin?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({ ...assets, credentialRevoked }, null, 2)),
      contentType: 'application/json'
    })
    expect(credentialCleanupError, '失败路径必须通过正式 UI 吊销事件子流程集成账号').toBeNull()
  }
})

test('@full [UI-BPMN-008] 事务 Cancel End 触发补偿并沿 Cancel Boundary 自然结束', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  const prefix = advancedPrefix(testInfo, 'transaction_comp')
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_事务补偿`,
    modelKey: `${prefix}_model`,
    transactionId: 'bookingTransaction',
    transactionStartId: 'transactionStart',
    bookTaskId: 'book',
    undoTaskId: 'undoBooking',
    compensationBoundaryId: 'bookCompensation',
    cancelEndId: 'cancelEnd',
    cancelBoundaryId: 'cancelBoundary',
    afterCancelId: 'afterCancel',
    compensationAssociationId: '',
    processInstanceId: ''
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
    })
    await configuration.createTextForm({
      name: assets.formName, remark: `${assets.prefix} 事务补偿真实运行表单`
    })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${assets.prefix} transaction cancel compensation`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.deleteElement('review')
    const directFlowId = await designerPage.findSequenceFlowId('start', 'end')
    await designerPage.deleteSequenceFlow(directFlowId)
    await designerPage.createAdvancedElement({
      paletteLabel: '事务',
      sourceElementId: 'start',
      stableElementId: assets.transactionId,
      elementName: `${assets.prefix}_预订事务`,
      offsetX: 270,
      offsetY: 160,
      expectedLocalName: 'transaction'
    })
    const generatedTransactionStartId = await designerPage.nestedDirectChildId(
      assets.transactionId, 'startEvent'
    )
    await designerPage.configureElementIdentity(
      generatedTransactionStartId, assets.transactionStartId, `${assets.prefix}_事务开始`
    )

    // 先用追加动作保证三个内部节点属于事务容器，再由可见类型菜单和补偿开关形成标准语义。
    const generatedBookId = await designerPage.appendManualTaskAfter(assets.transactionStartId)
    await designerPage.configureElementIdentity(
      generatedBookId, assets.bookTaskId, `${assets.prefix}_执行预订`
    )
    await designerPage.createAdvancedElement({
      paletteLabel: '手工任务',
      sourceElementId: assets.bookTaskId,
      stableElementId: assets.undoTaskId,
      elementName: `${assets.prefix}_撤销预订`,
      offsetX: 150,
      offsetY: 70,
      expectedLocalName: 'manualTask'
    })
    await designerPage.configureCompensationActivity(assets.undoTaskId, true)
    await designerPage.attachBoundaryEvent({
      paletteLabel: '补偿边界',
      hostElementId: assets.bookTaskId,
      stableElementId: assets.compensationBoundaryId,
      elementName: `${assets.prefix}_预订补偿边界`,
      eventDefinitionLocalName: 'compensateEventDefinition'
    })
    assets.compensationAssociationId = await designerPage.connectCompensationAssociation(
      assets.compensationBoundaryId, assets.undoTaskId
    )
    const generatedCancelEndId = await designerPage.appendEndEventAfter(assets.bookTaskId)
    await designerPage.configureElementIdentity(
      generatedCancelEndId, assets.cancelEndId, `${assets.prefix}_取消事务`
    )
    await designerPage.replaceEndWithCancel(assets.cancelEndId)

    await designerPage.attachBoundaryEvent({
      paletteLabel: '错误边界',
      hostElementId: assets.transactionId,
      stableElementId: assets.cancelBoundaryId,
      elementName: `${assets.prefix}_事务取消边界`,
      eventDefinitionLocalName: 'errorEventDefinition'
    })
    await designerPage.replaceBoundaryWithCancel(assets.cancelBoundaryId)
    const generatedAfterCancelId = await designerPage.appendManualTaskAfter(assets.cancelBoundaryId)
    await designerPage.configureElementIdentity(
      generatedAfterCancelId, assets.afterCancelId, `${assets.prefix}_取消后收口`
    )
    await designerPage.connectShapes(assets.afterCancelId, 'end')
    await designerPage.connectShapes('start', assets.transactionId)

    const authorXml = await designerPage.readDesignerXml()
    const authorDocument = new DOMParser().parseFromString(authorXml, 'application/xml')
    const transactions = [...authorDocument.getElementsByTagNameNS('*', 'transaction')]
      .filter(element => element.getAttribute('id') === assets.transactionId)
    expect(transactions, '作者 BPMN 必须包含唯一事务容器').toHaveLength(1)
    const directChildren = localName => [...transactions[0].childNodes]
      .filter(node => node.nodeType === 1 && node.localName === localName)
    expect(directChildren('startEvent').map(node => node.getAttribute('id')))
      .toContain(assets.transactionStartId)
    expect(directChildren('manualTask').map(node => node.getAttribute('id')))
      .toEqual(expect.arrayContaining([assets.bookTaskId, assets.undoTaskId]))
    expect(directChildren('sequenceFlow')
      .filter(node => node.getAttribute('sourceRef') === assets.bookTaskId
        && node.getAttribute('targetRef') === assets.undoTaskId),
    '补偿活动不得接入普通顺序流').toHaveLength(0)
    const undoTask = directChildren('manualTask')
      .find(node => node.getAttribute('id') === assets.undoTaskId)
    expect(undoTask?.getAttribute('isForCompensation')).toBe('true')
    const cancelEnd = directChildren('endEvent')
      .find(node => node.getAttribute('id') === assets.cancelEndId)
    expect(cancelEnd?.getElementsByTagNameNS('*', 'cancelEventDefinition')).toHaveLength(1)
    const compensationBoundaries = directChildren('boundaryEvent')
      .filter(node => node.getAttribute('id') === assets.compensationBoundaryId)
    expect(compensationBoundaries).toHaveLength(1)
    expect(compensationBoundaries[0].getAttribute('attachedToRef')).toBe(assets.bookTaskId)
    expect(compensationBoundaries[0].getAttribute('cancelActivity')).toBe('false')
    expect(compensationBoundaries[0].getElementsByTagNameNS('*', 'compensateEventDefinition'))
      .toHaveLength(1)
    const associations = directChildren('association')
      .filter(node => node.getAttribute('sourceRef') === assets.compensationBoundaryId
        && node.getAttribute('targetRef') === assets.undoTaskId)
    expect(associations, '事务内部必须包含唯一补偿 Association').toHaveLength(1)
    expect(associations[0].getAttribute('associationDirection')).toBe('One')
    const cancelBoundaries = [...authorDocument.getElementsByTagNameNS('*', 'boundaryEvent')]
      .filter(node => node.getAttribute('id') === assets.cancelBoundaryId)
    expect(cancelBoundaries).toHaveLength(1)
    expect(cancelBoundaries[0].getAttribute('attachedToRef')).toBe(assets.transactionId)
    expect(cancelBoundaries[0].getElementsByTagNameNS('*', 'cancelEventDefinition')).toHaveLength(1)
    await testInfo.attach('transaction-compensation-author-evidence.json', {
      body: Buffer.from(JSON.stringify({
        transactionId: assets.transactionId,
        transactionStartId: assets.transactionStartId,
        bookTaskId: assets.bookTaskId,
        undoTaskId: assets.undoTaskId,
        undoForCompensation: undoTask?.getAttribute('isForCompensation') === 'true',
        compensationBoundaryId: assets.compensationBoundaryId,
        compensationAssociationId: associations[0].getAttribute('id') || '',
        cancelEndId: assets.cancelEndId,
        cancelBoundaryId: assets.cancelBoundaryId,
        afterCancelId: assets.afterCancelId
      }, null, 2)),
      contentType: 'application/json'
    })

    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)
    const deployedXmlRows = queryReadOnly(
      `SELECT CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_PROCDEF p JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_=p.DEPLOYMENT_ID_ AND b.NAME_=p.RESOURCE_NAME_ WHERE p.KEY_='${sqlLiteral(assets.modelKey)}' ORDER BY p.VERSION_ DESC LIMIT 1`
    )
    expect(deployedXmlRows, '事务补偿模型必须形成唯一部署 BPMN').toHaveLength(1)
    const deployedXml = deployedXmlRows[0][0]
    expect(deployedXml).toContain(`<transaction id="${assets.transactionId}"`)
    expect(deployedXml).toContain(`<manualTask id="${assets.undoTaskId}"`)
    expect(deployedXml).toContain('isForCompensation="true"')
    expect(deployedXml).toContain(`<association id="${assets.compensationAssociationId}"`)
    expect(deployedXml).toContain(`sourceRef="${assets.compensationBoundaryId}"`)
    expect(deployedXml).toContain(`targetRef="${assets.undoTaskId}"`)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const contextEvidence = []
    const responseListener = async response => {
      const pathname = new URL(response.url()).pathname
      if (pathname.endsWith('/workflow/process/getProcessForm')
          || pathname.includes('/workflow/process/bpmnXml/')) {
        contextEvidence.push(await startContextEvidence(response))
      }
    }
    starter.page.on('response', responseListener)
    try {
      assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
        .startProcess(assets.modelName, `${assets.prefix}_事务补偿申请`)
    } finally {
      starter.page.off('response', responseListener)
      await testInfo.attach('start-context-responses.json', {
        body: Buffer.from(JSON.stringify(contextEvidence, null, 2)), contentType: 'application/json'
      })
    }

    const escapedInstanceId = sqlLiteral(assets.processInstanceId)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '事务 Cancel End 和补偿处理完成后流程必须同步自然结束').toEqual([['1', '']])
    const activityRows = queryReadOnly(
      `SELECT ACT_ID_,ACT_TYPE_,END_TIME_ IS NOT NULL FROM ACT_HI_ACTINST WHERE PROC_INST_ID_='${escapedInstanceId}' AND ACT_ID_ IN ('${assets.transactionId}','${assets.bookTaskId}','${assets.undoTaskId}','${assets.cancelEndId}','${assets.cancelBoundaryId}','${assets.afterCancelId}') ORDER BY START_TIME_,ID_`
    )
    expect(activityRows.map(row => row[0]), '事务、原活动、补偿活动和取消路径必须全部进入真实历史')
      .toEqual(expect.arrayContaining([
        assets.transactionId,
        assets.bookTaskId,
        assets.undoTaskId,
        assets.cancelEndId,
        assets.cancelBoundaryId,
        assets.afterCancelId
      ]))
    expect(activityRows.every(row => row[2] === '1'), '事务补偿全部活动必须完整结束').toBe(true)
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${escapedInstanceId}'`
    ), '同步补偿完成后不得遗留运行执行树').toEqual([['0']])
    await testInfo.attach('transaction-compensation-runtime-evidence.json', {
      body: Buffer.from(JSON.stringify({
        processInstanceId: assets.processInstanceId,
        historicActivities: activityRows
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await Promise.allSettled([starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
