import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'

const designerSource = readFileSync(
  new URL('../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')
const designerDoc = readFileSync(
  new URL('../../src/components/workflow/ProcessDesigner.md', import.meta.url), 'utf8')
const toolbarSource = readFileSync(
  new URL('../../src/components/workflow/designer/DesignerToolbar.vue', import.meta.url), 'utf8')
const advancedPaletteSource = readFileSync(
  new URL('../../src/components/workflow/designer/AdvancedElementPalette.vue', import.meta.url), 'utf8')
const advancedPaletteDoc = readFileSync(
  new URL('../../src/components/workflow/designer/AdvancedElementPalette.md', import.meta.url), 'utf8')
const settingsSource = readFileSync(
  new URL('../../src/components/workflow/designer/DesignerSettingsDrawer.vue', import.meta.url), 'utf8')
const propertiesPanelSource = readFileSync(
  new URL('../../src/components/workflow/designer/DesignerPropertiesPanel.vue', import.meta.url), 'utf8')
const propertiesPanelDoc = readFileSync(
  new URL('../../src/components/workflow/designer/DesignerPropertiesPanel.md', import.meta.url), 'utf8')
const processDetailSource = readFileSync(
  new URL('../../src/views/workflow/work/detail.vue', import.meta.url), 'utf8')
const businessListenerEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/BusinessListenerEditor.vue', import.meta.url), 'utf8')
const extensionPropertyEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/ExtensionPropertyEditor.vue', import.meta.url), 'utf8')
const userTaskSlaEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/UserTaskSlaEditor.vue', import.meta.url), 'utf8')
const userTaskSlaEditorDoc = readFileSync(
  new URL('../../src/components/workflow/designer/UserTaskSlaEditor.md', import.meta.url), 'utf8')
const slaApiSource = readFileSync(
  new URL('../../src/api/workflow/sla.js', import.meta.url), 'utf8')
const embeddedFormEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/EmbeddedFormFieldEditor.vue', import.meta.url), 'utf8')
const celExpressionEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/CelExpressionEditor.vue', import.meta.url), 'utf8')
const celExpressionEditorDoc = readFileSync(
  new URL('../../src/components/workflow/designer/CelExpressionEditor.md', import.meta.url), 'utf8')
const httpConnectorEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/HttpConnectorEditor.vue', import.meta.url), 'utf8')
const httpConnectorEditorDoc = readFileSync(
  new URL('../../src/components/workflow/designer/HttpConnectorEditor.md', import.meta.url), 'utf8')
const flowableModdleSource = readFileSync(
  new URL('../../src/components/workflow/bpmn/flowableModdle.js', import.meta.url), 'utf8')
const designPageSource = readFileSync(
  new URL('../../src/views/workflow/model/design.vue', import.meta.url), 'utf8')
const designerApiSource = readFileSync(
  new URL('../../src/api/workflow/designer.js', import.meta.url), 'utf8')
const modelApiSource = readFileSync(
  new URL('../../src/api/workflow/model.js', import.meta.url), 'utf8')
const extensionApiSource = readFileSync(
  new URL('../../src/api/workflow/extension.js', import.meta.url), 'utf8')
const extensionPageSource = readFileSync(
  new URL('../../src/views/workflow/extension/index.vue', import.meta.url), 'utf8')
const extensionPageDoc = readFileSync(
  new URL('../../src/views/workflow/extension/index.md', import.meta.url), 'utf8')
const connectorApiSource = readFileSync(
  new URL('../../src/api/workflow/connector.js', import.meta.url), 'utf8')
const connectorPageSource = readFileSync(
  new URL('../../src/views/workflow/connector/index.vue', import.meta.url), 'utf8')
const dmnApiSource = readFileSync(
  new URL('../../src/api/workflow/dmn.js', import.meta.url), 'utf8')
const dmnPageSource = readFileSync(
  new URL('../../src/views/workflow/dmn/index.vue', import.meta.url), 'utf8')
const dmnPageDoc = readFileSync(
  new URL('../../src/views/workflow/dmn/index.md', import.meta.url), 'utf8')
const bpmnEventPageSource = readFileSync(
  new URL('../../src/views/workflow/bpmnEvent/index.vue', import.meta.url), 'utf8')
const bpmnEventPageDoc = readFileSync(
  new URL('../../src/views/workflow/bpmnEvent/index.md', import.meta.url), 'utf8')

/**
 * 验证设计器能力接入真实 Modeler 模块和服务端 API，而不是只渲染工具栏按钮。
 * @returns {void} 任一核心能力未连接真实实现时断言失败。
 */
test('设计器工具命令连接真实 BPMN 服务', () => {
  assert.match(designerSource, /additionalModules: \[minimapModule, gridSnappingModule, lintModule, tokenSimulationModule\]/)
  assert.match(designerSource, /modeler\.saveSVG\(\)/)
  assert.match(designerSource, /modeler\.get\('alignElements'\)\.trigger/)
  assert.match(designerSource, /modeler\.get\('distributeElements'\)\.trigger/)
  assert.match(designerSource, /modeler\.get\('gridSnapping'\)\.setActive/)
  assert.match(designerSource, /modeler\.get\('minimap'\)\.toggle/)
  assert.match(designerSource, /modeler\.get\('linting'\)\.toggle/)
  assert.match(designerSource, /toggleMode\.toggleMode/)
  assert.match(designerSource, /validateModelBpmn\(xml\)/)
  assert.match(designerSource, /onActivated\(repairCachedSequenceFlowReferences\)/)
  assert.match(designerSource, /modeler\.saveXML\(\{ format: true \}\)[\s\S]*?normalizeSequenceFlowReferences\(cachedXml\)[\s\S]*?await importXml\(normalizedXml\)/)
  assert.match(modelApiSource, /url: '\/workflow\/model\/validate'/)
})

/**
 * 验证设计器布局通过容器尺寸驱动，并在属性面板变化时同步真实 bpmn-js 画布。
 * @returns {void} 固定最小宽高、不可折叠面板或缺失画布 resize 通知时断言失败。
 */
test('设计器属性面板支持完整显示、折叠和响应式调整', () => {
  assert.match(designerSource, /new ResizeObserver\(handleDesignerBodyResize\)/)
  assert.match(designerSource, /modeler\?\.get\('canvas'\)\?\.resized\(\)/)
  assert.match(designerSource, /role="separator"[\s\S]*?aria-label="调整属性面板宽度"/)
  assert.match(designerSource, /process-designer__body--compact-properties/)
  assert.doesNotMatch(designerSource, /min-width:\s*1040px/)
  assert.doesNotMatch(designerSource, /min-height:\s*640px/)
  assert.match(propertiesPanelSource, /<el-collapse v-model="activeSections">/)
  assert.match(propertiesPanelSource, /展开全部属性分区[\s\S]*?收起全部属性分区/)
  assert.match(propertiesPanelSource, /emit\('close'\)/)
  assert.match(designPageSource, /height="100%"/)
  assert.match(designerDoc, /不足 960px 时，属性检查器切换为工作区内浮层/)
  assert.match(propertiesPanelDoc, /长表单只在面板内部滚动/)
})

/**
 * 验证整改循环只写入平台固定属性，字段目录、显式应用和详情审计均连接正式业务投影。
 * @returns {Promise<void>} 出现任意表达式、半成品即时写入或循环审计姓名丢失时断言失败。
 */
test('受控整改循环以固定属性完成作者 XML 往返并连接正式详情审计', async () => {
  assert.match(designerSource, /CONTROLLED_LOOP_PROPERTY_PREFIX = 'approva\.controlledLoop\.'/)
  assert.match(designerSource, /resolveTemplateControlledLoopKind[\s\S]*?workflowWritable !== false/)
  assert.match(designerSource, /function resolveTemplateControlledLoopKind[\s\S]*?if \(tag === 'el-select'\)[\s\S]*?return null/)
  assert.match(propertiesPanelSource, /应用整改循环配置/)
  assert.match(propertiesPanelSource, /value !== 'approvalLoop'[\s\S]*?emit\('multi-instance-change'\)/)
  assert.match(propertiesPanelSource, /:allow-create="!controlledLoopValueRestricted"/)
  assert.match(processDetailSource, /controlledLoopActorNames[\s\S]*?completedByName \|\| node\.assigneeName/)
  assert.match(processDetailSource, /controlledLoopActorName\(row\)/)
  assert.match(designerDoc, /任意 `standardLoopCharacteristics` 仍仅支持 XML 往返并明确禁止部署/)
  assert.match(propertiesPanelDoc, /半成品只保留在当前面板草稿中/)
  assert.doesNotMatch(`${designerSource}\n${propertiesPanelSource}`, /controlledLoop.*expression/i)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/controlled-loop-contract">
  <process id="controlledLoopContract" isExecutable="true">
    <userTask id="rectify" flowable:assignee="1">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="approva.controlledLoop.enabled" value="true" />
          <flowable:property name="approva.controlledLoop.decisionVariable" value="reviewResult" />
          <flowable:property name="approva.controlledLoop.repeatValue" value="RECTIFY" />
          <flowable:property name="approva.controlledLoop.exitValue" value="PASS" />
          <flowable:property name="approva.controlledLoop.maxIterations" value="3" />
        </flowable:properties>
      </extensionElements>
    </userTask>
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.$type === 'bpmn:UserTask')
  const properties = task.extensionElements.values
    .find(value => value.$type === 'flowable:Properties').values
  assert.deepEqual(properties.map(property => [property.name, property.value]), [
    ['approva.controlledLoop.enabled', 'true'],
    ['approva.controlledLoop.decisionVariable', 'reviewResult'],
    ['approva.controlledLoop.repeatValue', 'RECTIFY'],
    ['approva.controlledLoop.exitValue', 'PASS'],
    ['approva.controlledLoop.maxIterations', '3']
  ])
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /approva\.controlledLoop\.maxIterations" value="3"/)
  assert.doesNotMatch(xml, /standardLoopCharacteristics|conditionExpression/)
})

/**
 * 验证高级元素目录覆盖计划要求的标准 BPMN 类型，并连接真实创建和全局连接服务。
 * @returns {void} 元素目录、容器创建或连接工具退化时断言失败。
 */
test('高级 Palette 通过 Modeler 创建完整标准 BPMN 元素', () => {
  const requiredTypes = [
    'ManualTask', 'ReceiveTask', 'SendTask', 'BusinessRuleTask', 'CallActivity',
    'SubProcess', 'Transaction', 'ParallelGateway', 'InclusiveGateway', 'EventBasedGateway',
    'IntermediateCatchEvent', 'IntermediateThrowEvent', 'BoundaryEvent', 'Participant',
    'DataObjectReference', 'DataStoreReference', 'Group', 'TextAnnotation'
  ]
  for (const type of requiredTypes) assert.match(advancedPaletteSource, new RegExp(`bpmn:${type}`))
  assert.match(advancedPaletteSource, /MessageEventDefinition[\s\S]*?SignalEventDefinition[\s\S]*?TimerEventDefinition/)
  assert.match(advancedPaletteSource, /ErrorEventDefinition[\s\S]*?EscalationEventDefinition[\s\S]*?CompensateEventDefinition/)
  assert.match(designerSource, /function createAdvancedElement\([\s\S]*?elementFactory\.createShape\([\s\S]*?create\.start/)
  assert.match(designerSource, /globalConnect'\)\.start\(event\)/)
  assert.match(designerSource, /elementFactory\.createParticipantShape\(\)/)
  assert.match(designerSource, /modeler\.get\('modeling'\)\.addLane\(selected, 'bottom'\)/)
  assert.match(advancedPaletteSource, /advanced-element-palette__reference[\s\S]*?<el-tooltip/)
  assert.match(advancedPaletteDoc, /复杂网关不进入正式工具入口/)
})

/**
 * 使用实际 bpmn-moddle 验证执行元素、协作元素、数据和制品不会在 XML 往返中丢失。
 * @returns {Promise<void>} 任一标准元素或跨元素引用无法稳定往返时断言失败。
 */
test('高级 BPMN 元素执行真实 XML 往返', async () => {
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:approvaplat:advanced-contract">
  <message id="messageNotice" name="notice" />
  <signal id="signalEscalation" name="escalation" />
  <dataStore id="storeAudit" name="审计存储" />
  <category id="categoryOps"><categoryValue id="categoryValueOps" value="运营" /></category>
  <process id="advancedContract" isExecutable="true">
    <laneSet id="lanes"><lane id="laneReviewer" name="复核泳道"><flowNodeRef>manual</flowNodeRef></lane></laneSet>
    <startEvent id="start" />
    <manualTask id="manual" />
    <sendTask id="send" />
    <receiveTask id="receive" />
    <businessRuleTask id="decision" />
    <parallelGateway id="parallel" />
    <inclusiveGateway id="inclusive" />
    <eventBasedGateway id="eventGateway" />
    <intermediateCatchEvent id="messageCatch"><messageEventDefinition messageRef="messageNotice" /></intermediateCatchEvent>
    <intermediateThrowEvent id="signalThrow"><signalEventDefinition signalRef="signalEscalation" /></intermediateThrowEvent>
    <subProcess id="embedded"><startEvent id="embeddedStart" /><endEvent id="embeddedEnd" /><sequenceFlow id="embeddedFlow" sourceRef="embeddedStart" targetRef="embeddedEnd" /></subProcess>
    <transaction id="transaction"><startEvent id="transactionStart" /><endEvent id="transactionEnd" /><sequenceFlow id="transactionFlow" sourceRef="transactionStart" targetRef="transactionEnd" /></transaction>
    <dataObject id="payload" /><dataObjectReference id="payloadRef" dataObjectRef="payload" />
    <dataStoreReference id="auditRef" dataStoreRef="storeAudit" />
    <endEvent id="end" />
    <sequenceFlow id="flow1" sourceRef="start" targetRef="manual" />
    <sequenceFlow id="flow2" sourceRef="manual" targetRef="end" />
    <textAnnotation id="annotation"><text>复核说明</text></textAnnotation>
    <association id="association" sourceRef="manual" targetRef="annotation" />
    <group id="groupOps" categoryValueRef="categoryValueOps" />
  </process>
  <process id="externalContract" isExecutable="true">
    <startEvent id="externalStart" />
    <receiveTask id="externalReceive" />
    <endEvent id="externalEnd" />
    <sequenceFlow id="externalFlow1" sourceRef="externalStart" targetRef="externalReceive" />
    <sequenceFlow id="externalFlow2" sourceRef="externalReceive" targetRef="externalEnd" />
  </process>
  <collaboration id="collaboration">
    <participant id="participantMain" processRef="advancedContract" />
    <participant id="participantExternal" name="外部系统" processRef="externalContract" />
    <messageFlow id="messageFlow" name="notice" sourceRef="send" targetRef="externalReceive" messageRef="messageNotice" />
  </collaboration>
</definitions>`
  const { rootElement, warnings } = await moddle.fromXML(source)
  assert.deepEqual(warnings, [])
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const types = new Set(process.flowElements.map(element => element.$type))
  for (const type of [
    'bpmn:ManualTask', 'bpmn:SendTask', 'bpmn:ReceiveTask', 'bpmn:BusinessRuleTask',
    'bpmn:ParallelGateway', 'bpmn:InclusiveGateway', 'bpmn:EventBasedGateway',
    'bpmn:IntermediateCatchEvent', 'bpmn:IntermediateThrowEvent', 'bpmn:SubProcess',
    'bpmn:Transaction', 'bpmn:DataObjectReference', 'bpmn:DataStoreReference'
  ]) assert.equal(types.has(type), true, `缺少 ${type}`)
  assert.equal(process.laneSets[0].lanes[0].flowNodeRef[0].id, 'manual')
  assert.equal(process.artifacts.some(element => element.$type === 'bpmn:Association'), true)
  assert.equal(process.artifacts.some(element => element.$type === 'bpmn:Group'), true)
  assert.equal(process.artifacts.some(element => element.$type === 'bpmn:TextAnnotation'), true)
  const collaboration = rootElement.rootElements.find(element => element.$type === 'bpmn:Collaboration')
  assert.equal(collaboration.participants.length, 2)
  assert.equal(collaboration.messageFlows[0].messageRef.id, 'messageNotice')

  const { xml } = await moddle.toXML(rootElement, { format: true })
  for (const tag of [
    'laneSet', 'manualTask', 'sendTask', 'receiveTask', 'businessRuleTask', 'parallelGateway',
    'inclusiveGateway', 'eventBasedGateway', 'intermediateCatchEvent', 'intermediateThrowEvent',
    'subProcess', 'transaction', 'dataObjectReference', 'dataStoreReference', 'textAnnotation',
    'association', 'group', 'participant', 'messageFlow'
  ]) assert.match(xml, new RegExp(`<${tag}(?:\\s|>)`))
})

/**
 * 使用实际 moddle 验证活动循环和通用扩展属性通过属性面板完整往返。
 * @returns {Promise<void>} 循环类型、名值属性或受控编辑入口丢失时断言失败。
 */
test('活动循环和通用扩展属性执行真实 XML 往返', async () => {
  assert.match(propertiesPanelSource, /flags\.activity[\s\S]*?activityLoopOptions[\s\S]*?loopMaximum/)
  assert.match(designerSource, /function updateMultiInstance\([\s\S]*?bpmn:StandardLoopCharacteristics/)
  assert.match(designerSource, /function updateExtensionProperties\([\s\S]*?flowable:Properties/)
  assert.match(extensionPropertyEditorSource, /maxlength="1024"/)
  assert.match(extensionPropertyEditorSource, /maxItems: \{ type: Number, default: 32 \}/)
  assert.doesNotMatch(extensionPropertyEditorSource, /className|beanName|delegateExpression/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:loop-properties">
  <process id="loopProperties" isExecutable="true">
    <startEvent id="start" />
    <manualTask id="review">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="business.owner" value="finance" />
          <flowable:property name="retentionDays" value="30" />
        </flowable:properties>
      </extensionElements>
      <standardLoopCharacteristics testBefore="false" loopMaximum="3">
        <loopCondition>\${continueLoop}</loopCondition>
      </standardLoopCharacteristics>
    </manualTask>
    <endEvent id="end" />
    <sequenceFlow id="toReview" sourceRef="start" targetRef="review" />
    <sequenceFlow id="toEnd" sourceRef="review" targetRef="end" />
  </process>
</definitions>`
  const { rootElement, warnings } = await moddle.fromXML(source)
  assert.deepEqual(warnings, [])
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.id === 'review')
  assert.equal(task.loopCharacteristics.$type, 'bpmn:StandardLoopCharacteristics')
  assert.equal(task.loopCharacteristics.loopMaximum, 3)
  const properties = task.extensionElements.values.find(value => value.$type === 'flowable:Properties')
  assert.deepEqual(properties.values.map(item => [item.name, item.value]), [
    ['business.owner', 'finance'],
    ['retentionDays', '30']
  ])
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /<standardLoopCharacteristics loopMaximum="3">/)
  assert.match(xml, /<flowable:property name="business.owner" value="finance" \/>/)
})

/**
 * 验证固定会签和或签使用严格受控人员表达式完成真实 BPMN 往返。
 * @returns {Promise<void>} 人员来源面板、受控表达式或多实例核心字段缺失时断言失败。
 */
test('固定会签和或签人员来源执行真实 XML 往返', async () => {
  assert.match(propertiesPanelSource, /人员来源[\s\S]*?动态选择[\s\S]*?固定人员/)
  assert.match(propertiesPanelSource, /fixedMultiInstanceUserIds[\s\S]*?请选择会签或或签办理人/)
  assert.match(designerSource, /getFixedUserIds\(execution, '\$\{userIds\.join\(','\)\}'\)/)
  assert.match(designerSource, /FIXED_MULTI_INSTANCE_COLLECTION_PATTERN[\s\S]*?getFixedUserIds/)
  assert.match(designerSource, /固定会签或或签办理人必须选择 1 至 100 名有效用户/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:fixed-multi-instance">
  <process id="fixedMultiInstance" isExecutable="true">
    <startEvent id="start" />
    <userTask id="countersign" flowable:assignee="\${assignee}">
      <multiInstanceLoopCharacteristics flowable:collection="\${multiInstanceHandler.getFixedUserIds(execution, '8,9')}" flowable:elementVariable="assignee">
        <completionCondition>\${nrOfCompletedInstances == nrOfInstances}</completionCondition>
      </multiInstanceLoopCharacteristics>
    </userTask>
    <endEvent id="end" />
    <sequenceFlow id="toCountersign" sourceRef="start" targetRef="countersign" />
    <sequenceFlow id="toEnd" sourceRef="countersign" targetRef="end" />
  </process>
</definitions>`
  const { rootElement, warnings } = await moddle.fromXML(source)
  assert.deepEqual(warnings, [])
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.id === 'countersign')
  assert.equal(task.assignee, '${assignee}')
  assert.equal(task.loopCharacteristics.get('flowable:collection'), "${multiInstanceHandler.getFixedUserIds(execution, '8,9')}")
  assert.equal(task.loopCharacteristics.get('flowable:elementVariable'), 'assignee')
  assert.equal(task.loopCharacteristics.completionCondition.body, '${nrOfCompletedInstances == nrOfInstances}')
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /flowable:collection="\$\{multiInstanceHandler\.getFixedUserIds\(execution, &#39;8,9&#39;\)\}"/)
})

/**
 * 验证 UserTask SLA 使用正式目录和八个受控属性完成真实 XML 往返。
 * @returns {Promise<void>} 自由输入目录、保留属性旁路或任一 SLA 字段丢失时测试失败。
 */
test('UserTask 审批 SLA 通过正式目录写入受控属性', async () => {
  assert.match(slaApiSource, /listEnabledSlaCalendars[\s\S]*?\/workflow\/sla\/calendars\/enabled/)
  assert.match(designerSource, /listEnabledSlaCalendars\(\)/)
  assert.match(designerSource, /SLA_PROPERTY_NAMES[\s\S]*?'approva\.sla\.enabled'[\s\S]*?'approva\.sla\.escalationEventCode'/)
  assert.match(designerSource, /function normalizeAndValidateSlaConfig\([\s\S]*?最后一次提醒[\s\S]*?受控升级事件/)
  assert.match(propertiesPanelSource, /UserTaskSlaEditor[\s\S]*?:calendars="slaCalendarOptions"/)
  assert.match(userTaskSlaEditorSource, /el-input-number[\s\S]*?escalationUserId[\s\S]*?escalationEventCode/)
  assert.match(userTaskSlaEditorSource, /emit\('identity-search', keyword\)/)
  assert.doesNotMatch(userTaskSlaEditorSource, /localStorage|sessionStorage|timerDefinition|timeDuration/)
  assert.match(userTaskSlaEditorDoc, /业务日历负责将工作分钟解析为实际到期时间/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:sla-contract">
  <process id="slaContract" isExecutable="true">
    <startEvent id="start" />
    <sequenceFlow id="toApprove" sourceRef="start" targetRef="approve" />
    <userTask id="approve" flowable:assignee="1">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="approva.sla.enabled" value="true" />
          <flowable:property name="approva.sla.calendarKey" value="DEFAULT" />
          <flowable:property name="approva.sla.reminderMinutes" value="60" />
          <flowable:property name="approva.sla.reminderRepeatMinutes" value="30" />
          <flowable:property name="approva.sla.maxReminders" value="3" />
          <flowable:property name="approva.sla.escalationMinutes" value="180" />
          <flowable:property name="approva.sla.escalationUserId" value="2" />
          <flowable:property name="approva.sla.escalationEventCode" value="APPROVAL_TIMEOUT" />
        </flowable:properties>
      </extensionElements>
    </userTask>
    <sequenceFlow id="toEnd" sourceRef="approve" targetRef="end" />
    <endEvent id="end" />
  </process>
</definitions>`
  const { rootElement, warnings } = await moddle.fromXML(source)
  assert.deepEqual(warnings, [])
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.id === 'approve')
  const properties = task.extensionElements.values.find(value => value.$type === 'flowable:Properties')
  assert.equal(properties.values.length, 8)
  assert.deepEqual(Object.fromEntries(properties.values.map(item => [item.name, item.value])), {
    'approva.sla.enabled': 'true',
    'approva.sla.calendarKey': 'DEFAULT',
    'approva.sla.reminderMinutes': '60',
    'approva.sla.reminderRepeatMinutes': '30',
    'approva.sla.maxReminders': '3',
    'approva.sla.escalationMinutes': '180',
    'approva.sla.escalationUserId': '2',
    'approva.sla.escalationEventCode': 'APPROVAL_TIMEOUT'
  })
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /name="approva\.sla\.calendarKey" value="DEFAULT"/)
  assert.match(xml, /name="approva\.sla\.escalationEventCode" value="APPROVAL_TIMEOUT"/)
})

/**
 * 验证 SLA 管理页连接正式日历、执行、审计和通知接口并落实权限按钮。
 * @returns {void} 本地状态冒充、缺少状态回读或管理入口越权时测试失败。
 */
test('审批 SLA 管理页连接正式 API 和权限边界', () => {
  assert.match(slaApiSource, /listSlaCalendars[\s\S]*?\/workflow\/sla\/calendars/)
  assert.match(slaApiSource, /createSlaCalendar[\s\S]*?method: 'post'/)
  assert.match(slaApiSource, /updateSlaCalendar[\s\S]*?method: 'put'/)
  assert.match(slaApiSource, /changeSlaCalendarStatus[\s\S]*?data: \{ enabled \}/)
  assert.match(slaApiSource, /listSlaExecutions[\s\S]*?\/workflow\/sla\/executions/)
  assert.match(slaApiSource, /listSlaAudits[\s\S]*?\/workflow\/sla\/audits/)
  assert.match(slaApiSource, /markSlaNotificationRead[\s\S]*?notifications\/\$\{notificationId\}\/read/)
  assert.match(bpmnEventPageSource, /workflow:sla:add/)
  assert.match(bpmnEventPageSource, /workflow:sla:edit/)
  assert.match(bpmnEventPageSource, /await updateSlaCalendar\(calendarForm\.calendarId, payload\)[\s\S]*?await loadCalendars\(\)/)
  assert.match(bpmnEventPageSource, /onActivated\(loadActiveTab\)/)
  assert.match(bpmnEventPageSource, /REMINDER|SLA 通知/)
  assert.doesNotMatch(bpmnEventPageSource, /localStorage|sessionStorage|setTimeout/)
  assert.match(bpmnEventPageDoc, /IANA 时区[\s\S]*?正式数据库/)
})

/**
 * 验证导入、导出和预览具备文件大小门禁、结构化 XML 转换和审计监听器标准化。
 * @returns {void} 文件能力退化为占位命令或字符串伪转换时断言失败。
 */
test('设计器导入导出和双格式预览执行真实数据转换', () => {
  assert.match(toolbarSource, /command="bpmn"[\s\S]*?command="xml"[\s\S]*?command="svg"/)
  assert.match(designerSource, /file\.size > 2 \* 1024 \* 1024/)
  assert.match(designerSource, /await file\.text\(\)[\s\S]*?await importXml\(xml\)/)
  assert.match(designerSource, /function xmlElementToJson\(element\)[\s\S]*?element\.attributes[\s\S]*?element\.children/)
  assert.match(designerSource, /async function exportDiagram\(format\)[\s\S]*?emitPersistedXml\(\)/)
})

/**
 * 验证偏好只通过正式后端接口回读和保存，不使用浏览器本地状态冒充配置。
 * @returns {void} 偏好未持久化、保存结果未回读或出现 localStorage 时断言失败。
 */
test('设计器偏好使用正式数据库闭环', () => {
  assert.match(designerApiSource, /getDesignerPreference[\s\S]*?\/workflow\/designer\/preference/)
  assert.match(designerApiSource, /saveDesignerPreference[\s\S]*?method: 'put'/)
  assert.match(designPageSource, /getDesignerPreference\(\)/)
  assert.match(designPageSource, /saveDesignerPreference\(preference\)[\s\S]*?Object\.assign\(designerPreference, response\.data/)
  assert.match(settingsSource, /emit\('save', \{ \.\.\.draft \}\)/)
  assert.doesNotMatch(`${designerSource}\n${settingsSource}\n${designPageSource}`, /localStorage|sessionStorage/)
})

/**
 * 验证正式模板和内嵌 FormData 通过同一命令栈互斥写入，字段编辑器不建立本地假数据闭环。
 * @returns {void} 来源切换、字段白名单或服务端保存边界退化时断言失败。
 */
test('设计器双表单协议通过 moddle 命令栈互斥写入', () => {
  assert.match(propertiesPanelSource, /value: 'TEMPLATE'[\s\S]*?value: 'EMBEDDED'/)
  assert.match(propertiesPanelSource, /@form-source-change="updateFormSource"|emit\('form-source-change'\)/)
  assert.match(propertiesPanelSource, /EmbeddedFormFieldEditor[\s\S]*?embedded-form-change/)
  assert.match(designerSource, /function syncFormDefinition\(\)[\s\S]*?updateModdleProperties/)
  assert.match(designerSource, /'flowable:formKey':[\s\S]*?extensionElements/)
  assert.match(designerSource, /filter\(value => value\?\.\$type !== 'flowable:FormProperty'\)/)
  assert.match(embeddedFormEditorSource, /'string'[\s\S]*?'long'[\s\S]*?'integer'[\s\S]*?'boolean'[\s\S]*?'date'[\s\S]*?'enum'/)
  assert.match(embeddedFormEditorSource, /maxlength="128"[\s\S]*?maxlength="255"/)
  assert.doesNotMatch(`${designerSource}\n${propertiesPanelSource}\n${embeddedFormEditorSource}`, /localStorage|sessionStorage/)
})

/**
 * 使用实际 bpmn-moddle 解析并序列化 FormData，验证自定义类型不会丢失日期、读写约束和枚举值。
 * @returns {Promise<void>} XML 往返不完整时断言失败。
 */
test('Flowable FormData moddle 执行真实 XML 往返', async () => {
  assert.match(flowableModdleSource, /name: 'FormProperty'[\s\S]*?name: 'Value'/)
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/form-contract">
  <process id="formContract" isExecutable="true">
    <startEvent id="start">
      <extensionElements>
        <flowable:formProperty id="requestDate" name="申请日期" type="date" variable="requestDateValue" datePattern="yyyy-MM-dd" readable="true" writable="true" required="true" />
        <flowable:formProperty id="decision" name="审批结论" type="enum" readable="true" writable="true" required="true">
          <flowable:value id="APPROVE" name="同意" />
          <flowable:value id="REJECT" name="拒绝" />
        </flowable:formProperty>
      </extensionElements>
    </startEvent>
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const start = process.flowElements.find(element => element.$type === 'bpmn:StartEvent')
  const properties = start.extensionElements.values.filter(value => value.$type === 'flowable:FormProperty')
  assert.equal(properties.length, 2)
  assert.equal(properties[0].variable, 'requestDateValue')
  assert.equal(properties[0].datePattern, 'yyyy-MM-dd')
  assert.equal(properties[1].values[1].id, 'REJECT')
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /flowable:formProperty[^>]+id="requestDate"[^>]+datePattern="yyyy-MM-dd"/)
  assert.match(xml, /flowable:formProperty[^>]+variable="requestDateValue"/)
  assert.match(xml, /flowable:value id="APPROVE" name="同意"/)
  assert.match(xml, /flowable:value id="REJECT" name="拒绝"/)
})

/**
 * 验证自定义表单字段只来自正式 FORM_FIELD 目录，并以 custom: 稳定键完成 XML 往返。
 * @returns {Promise<void>} 出现本地组件注入、目录旁路或类型丢失时断言失败。
 */
test('自定义表单字段通过正式注册表选择并完成 XML 往返', async () => {
  assert.match(extensionApiSource, /listFormFieldExtensionOptions[\s\S]*?\/workflow\/extension\/options\/form-field/)
  assert.match(designerSource, /listFormFieldExtensionOptions\(\)/)
  assert.match(propertiesPanelSource, /:custom-field-options="formFieldOptions"/)
  assert.match(embeddedFormEditorSource, /value: `custom:\$\{option\.extensionKey\}`/)
  assert.doesNotMatch(embeddedFormEditorSource, /componentName|componentTemplate|eval\(|new Function/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/custom-field-contract">
  <process id="customFieldContract" isExecutable="true">
    <startEvent id="start">
      <extensionElements>
        <flowable:formProperty id="detail" name="详细说明" type="custom:approva.form.textarea" readable="true" writable="true" required="true" />
      </extensionElements>
    </startEvent>
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const start = process.flowElements.find(element => element.$type === 'bpmn:StartEvent')
  const property = start.extensionElements.values.find(value => value.$type === 'flowable:FormProperty')
  assert.equal(property.type, 'custom:approva.form.textarea')
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /type="custom:approva\.form\.textarea"/)
})

/**
 * 验证受控 ServiceTask 的两个 Flowable Field 完整往返，并保留同节点其他扩展元素。
 * @returns {Promise<void>} 受控扩展字段或无关扩展元素丢失时测试失败。
 */
test('受控 ServiceTask Flowable Field 执行真实 XML 往返', async () => {
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/extension-contract">
  <process id="extensionContract" isExecutable="true">
    <serviceTask id="setResult" flowable:delegateExpression="\${workflowExtensionDelegate}">
      <extensionElements>
        <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable" />
        <flowable:field name="approvaExtensionConfig" stringValue="{&quot;targetVariable&quot;:&quot;result&quot;,&quot;value&quot;:true}" />
        <flowable:failedJobRetryTimeCycle>R3/PT10S</flowable:failedJobRetryTimeCycle>
      </extensionElements>
    </serviceTask>
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.$type === 'bpmn:ServiceTask')
  const fields = task.extensionElements.values.filter(value => value.$type === 'flowable:Field')
  assert.equal(fields.length, 2)
  assert.equal(fields[0].name, 'approvaExtensionKey')
  assert.equal(fields[1].stringValue, '{"targetVariable":"result","value":true}')
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /flowable:field name="approvaExtensionKey" stringValue="approva.set-variable"/)
  assert.match(xml, /flowable:field name="approvaExtensionConfig"/)
  assert.match(xml, /flowable:failedJobRetryTimeCycle>R3\/PT10S/)
})

/**
 * 验证业务监听器只能选择 Java 注册表，并由固定 Bean 和受控字段完成 XML 往返。
 * @returns {Promise<void>} 任意类名入口、字段丢失或系统监听器暴露时测试失败。
 */
test('受控业务监听器执行真实 XML 往返', async () => {
  assert.match(propertiesPanelSource, /BusinessListenerEditor[\s\S]*?business-execution-listener-change/)
  assert.match(designerSource, /businessListenerOptions[\s\S]*?extensionType === 'JAVA'/)
  assert.match(designerSource, /BUSINESS_LISTENER_DELEGATE_EXPRESSION = '\$\{workflowBusinessListener\}'/)
  assert.match(businessListenerEditorSource, /EXECUTION[\s\S]*?TASK/)
  assert.doesNotMatch(businessListenerEditorSource, /className|beanName|delegateExpression/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/listener-contract">
  <process id="listenerContract" isExecutable="true">
    <startEvent id="start" flowable:formKey="key_1" />
    <sequenceFlow id="toApprove" sourceRef="start" targetRef="approve" />
    <userTask id="approve">
      <extensionElements>
        <flowable:executionListener event="start" delegateExpression="\${workflowBusinessListener}">
          <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable" />
          <flowable:field name="approvaExtensionConfig" stringValue="{&quot;targetVariable&quot;:&quot;entered&quot;,&quot;value&quot;:true}" />
        </flowable:executionListener>
        <flowable:taskListener event="complete" delegateExpression="\${workflowBusinessListener}">
          <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable" />
          <flowable:field name="approvaExtensionConfig" stringValue="{&quot;targetVariable&quot;:&quot;completed&quot;,&quot;value&quot;:true}" />
        </flowable:taskListener>
      </extensionElements>
    </userTask>
    <sequenceFlow id="toEnd" sourceRef="approve" targetRef="end" />
    <endEvent id="end" />
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.$type === 'bpmn:UserTask')
  const extensionValues = task.extensionElements.values
  const executionListener = extensionValues.find(value => value.$type === 'flowable:ExecutionListener')
  const taskListener = extensionValues.find(value => value.$type === 'flowable:TaskListener')
  assert.equal(executionListener.fields[0].stringValue, 'approva.set-variable')
  assert.equal(taskListener.event, 'complete')
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /flowable:executionListener event="start" delegateExpression="\$\{workflowBusinessListener\}"/)
  assert.match(xml, /flowable:taskListener event="complete" delegateExpression="\$\{workflowBusinessListener\}"/)
})

/**
 * 验证 BusinessRuleTask 与通用扩展任务分离，并以精确 decisionId 完成 XML 往返。
 * @returns {Promise<void>} DMN 引用丢失、接受多值或页面未连接正式 API 时测试失败。
 */
test('BusinessRuleTask 通过正式 DMN 目录绑定精确版本', async () => {
  assert.match(designerSource, /listDmnDecisionOptions\(\)/)
  assert.match(designerSource, /businessRuleTask: isBusinessRuleTask\.value/)
  assert.match(designerSource, /function updateDmnDecision\(\)[\s\S]*?'flowable:rules': decisionId/)
  assert.doesNotMatch(designerSource, /serviceTaskLike:[^\n]*BusinessRuleTask/)
  assert.match(propertiesPanelSource, /flags\.businessRuleTask[\s\S]*?DMN 决策版本[\s\S]*?:value="decision\.decisionId"/)
  assert.match(dmnApiSource, /listDmnDecisionOptions[\s\S]*?\/workflow\/dmn\/options/)
  assert.match(dmnPageSource, /listDmnDecisions\(\)[\s\S]*?deployDmnDecision[\s\S]*?removeDmnDeployment/)
  assert.match(dmnPageSource, /onActivated\(\(\) => \{ if \(pageInitialized\) loadRows\(\) \}\)/)
  assert.match(dmnPageDoc, /精确 `decisionId`/)
  assert.doesNotMatch(`${designerSource}\n${dmnPageSource}`, /localStorage|sessionStorage/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/dmn-contract">
  <process id="dmnContract" isExecutable="true">
    <businessRuleTask id="riskDecision" flowable:rules="riskDecision:7:source-decision-id" />
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.$type === 'bpmn:BusinessRuleTask')
  assert.equal(task.get('flowable:rules'), 'riskDecision:7:source-decision-id')
  assert.equal(task.get('flowable:class'), undefined)
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /businessRuleTask[^>]+flowable:rules="riskDecision:7:source-decision-id"/)
  assert.doesNotMatch(xml, /approvaExtensionKey|workflowExtensionDelegate/)
})

/**
 * 验证 CEL 设计选项、结构化编辑器和作者 XML 共享同一受控配置协议。
 * @returns {Promise<void>} 任一类型白名单、配置字段或 XML 往返丢失时测试失败。
 */
test('CEL 配置通过结构化编辑器写入受控 ServiceTask', async () => {
  assert.match(designerSource, /listCelExtensionOptions\(\)/)
  assert.match(designerSource, /selectedOption\?\.extensionType === 'CEL'[\s\S]*?CEL_DEFAULT_CONFIG/)
  assert.match(propertiesPanelSource, /selectedExtensionType === 'CEL'[\s\S]*?CelExpressionEditor/)
  assert.match(celExpressionEditorSource, /value: 'BOOL'[\s\S]*?value: 'INT'[\s\S]*?value: 'DOUBLE'[\s\S]*?value: 'STRING'/)
  assert.match(celExpressionEditorSource, /draft\.variables\.length >= 32/)
  assert.match(celExpressionEditorSource, /emit\('update:modelValue', configJson\)[\s\S]*?emit\('change', configJson\)/)
  assert.doesNotMatch(celExpressionEditorSource, /localStorage|sessionStorage/)
  assert.match(celExpressionEditorDoc, /WorkflowCelSandbox/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const config = '{&quot;expression&quot;:&quot;amount &gt;= 1000.5 &amp;&amp; approved&quot;,&quot;resultVariable&quot;:&quot;eligible&quot;,&quot;resultType&quot;:&quot;BOOL&quot;,&quot;variables&quot;:[{&quot;name&quot;:&quot;amount&quot;,&quot;type&quot;:&quot;DOUBLE&quot;},{&quot;name&quot;:&quot;approved&quot;,&quot;type&quot;:&quot;BOOL&quot;}]}'
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/cel-contract">
  <process id="celContract" isExecutable="true">
    <serviceTask id="evaluateEligibility" flowable:delegateExpression="\${workflowExtensionDelegate}">
      <extensionElements>
        <flowable:field name="approvaExtensionKey" stringValue="approva.cel-expression" />
        <flowable:field name="approvaExtensionConfig" stringValue="${config}" />
      </extensionElements>
    </serviceTask>
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.$type === 'bpmn:ServiceTask')
  const configField = task.extensionElements.values.find(value => value.name === 'approvaExtensionConfig')
  assert.deepEqual(JSON.parse(configField.stringValue), {
    expression: 'amount >= 1000.5 && approved',
    resultVariable: 'eligible',
    resultType: 'BOOL',
    variables: [
      { name: 'amount', type: 'DOUBLE' },
      { name: 'approved', type: 'BOOL' }
    ]
  })
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /approva\.cel-expression/)
  assert.match(xml, /amount &#62;= 1000\.5 &#38;&#38; approved/)
})

/**
 * 验证 HTTP 节点只选择服务端端点白名单，自动启用异步重试并完整往返作者 XML。
 * @returns {Promise<void>} 任一任意 URL、密钥正文或本地状态旁路出现时断言失败。
 */
test('HTTP 连接器通过真实端点注册表写入异步 ServiceTask', async () => {
  assert.match(designerSource, /Promise\.all\(\[[\s\S]*?listHttpExtensionOptions\(\)[\s\S]*?listConnectorEndpointOptions\(\)/)
  assert.match(designerSource, /selectedOption\?\.extensionType === 'HTTP'[\s\S]*?propertyState\.asyncBefore = true[\s\S]*?'flowable:async': true/)
  assert.match(propertiesPanelSource, /HttpConnectorEditor[\s\S]*?selectedExtensionType === 'HTTP'[\s\S]*?:endpoints="connectorEndpoints"/)
  assert.match(httpConnectorEditorSource, /allowedMethods[\s\S]*?selectedEndpoint\.value\?\.pathPrefix/)
  assert.match(httpConnectorEditorSource, /!\['GET', 'DELETE'\]\.includes\(draft\.method\)/)
  assert.match(httpConnectorEditorSource, /endpointKey: draft\.endpointKey\.trim\(\)[\s\S]*?method: draft\.method[\s\S]*?path: draft\.path\.trim\(\)/)
  assert.doesNotMatch(httpConnectorEditorSource, /baseUrl|Authorization|apiKeyHeader|secretRef|localStorage|sessionStorage/)
  assert.match(httpConnectorEditorDoc, /不接受任意 URL、请求头或密钥正文/)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const config = '{&quot;endpointKey&quot;:&quot;finance.api&quot;,&quot;method&quot;:&quot;POST&quot;,&quot;path&quot;:&quot;/workflow/notify&quot;,&quot;bodyVariable&quot;:&quot;payload&quot;,&quot;statusVariable&quot;:&quot;httpStatus&quot;}'
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/http-contract">
  <process id="httpContract" isExecutable="true">
    <serviceTask id="notifyFinance" flowable:async="true" flowable:delegateExpression="\${workflowExtensionDelegate}">
      <extensionElements>
        <flowable:field name="approvaExtensionKey" stringValue="approva.http-connector" />
        <flowable:field name="approvaExtensionConfig" stringValue="${config}" />
      </extensionElements>
    </serviceTask>
  </process>
</definitions>`
  const { rootElement } = await moddle.fromXML(source)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.$type === 'bpmn:ServiceTask')
  assert.equal(task.get('flowable:async'), true)
  const fields = task.extensionElements.values.filter(value => value.$type === 'flowable:Field')
  assert.equal(fields.find(value => value.name === 'approvaExtensionKey').stringValue, 'approva.http-connector')
  assert.deepEqual(JSON.parse(fields.find(value => value.name === 'approvaExtensionConfig').stringValue), {
    endpointKey: 'finance.api',
    method: 'POST',
    path: '/workflow/notify',
    bodyVariable: 'payload',
    statusVariable: 'httpStatus'
  })
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /flowable:async="true"/)
  assert.match(xml, /approva\.http-connector/)
  assert.doesNotMatch(xml, /baseUrl|Authorization|secretRef/)
})

/**
 * 验证连接端点管理页通过正式 API 发布不可回退修订和启停状态。
 * @returns {void} 页面使用本地状态冒充端点管理或缺少权限边界时断言失败。
 */
test('连接端点管理页连接真实后端并在缓存激活时刷新', () => {
  assert.match(connectorApiSource, /listConnectorEndpoints[\s\S]*?\/workflow\/connector\/list/)
  assert.match(connectorApiSource, /createConnectorEndpoint[\s\S]*?method: 'post'/)
  assert.match(connectorApiSource, /updateConnectorEndpoint[\s\S]*?method: 'put'/)
  assert.match(connectorApiSource, /changeConnectorEndpointStatus[\s\S]*?data: \{ enabled \}/)
  assert.match(connectorPageSource, /workflow:connector:add/)
  assert.match(connectorPageSource, /workflow:connector:edit/)
  assert.match(connectorPageSource, /await updateConnectorEndpoint\(editingId\.value, payload\)[\s\S]*?await loadEndpoints\(\)/)
  assert.match(connectorPageSource, /onActivated\(\(\) => \{[\s\S]*?loadEndpoints\(\)/)
  assert.match(connectorPageSource, /WORKFLOW_CONNECTOR_SECRET_\[A-Z0-9_\]/)
  assert.doesNotMatch(connectorPageSource, /localStorage|sessionStorage/)
})

/**
 * 验证扩展管理页通过真实管理清单和安装处理器 API 完成目录、版本及状态闭环。
 * @returns {void} 页面退化为设计选项或本地状态管理时测试失败。
 */
test('扩展注册表管理页连接真实后端与权限按钮', () => {
  assert.match(extensionApiSource, /listWorkflowExtensions[\s\S]*?\/workflow\/extension\/list/)
  assert.match(extensionApiSource, /createWorkflowExtensionVersion[\s\S]*?method: 'post'/)
  assert.match(extensionApiSource, /changeWorkflowExtensionStatus[\s\S]*?method: 'put'/)
  assert.match(extensionPageSource, /Promise\.all\(\[[\s\S]*?listWorkflowExtensions\(\)[\s\S]*?listInstalledJavaHandlers\(\)/)
  assert.match(extensionPageSource, /workflow:extension:add/)
  assert.match(extensionPageSource, /workflow:extension:version:add/)
  assert.match(extensionPageSource, /workflow:extension:edit/)
  assert.match(extensionPageSource, /onActivated\([\s\S]*?loadRegistry\(\)/)
  assert.doesNotMatch(extensionPageSource, /localStorage|sessionStorage/)
  assert.match(extensionPageDoc, /停用和尚未发布版本的目录/)
})
