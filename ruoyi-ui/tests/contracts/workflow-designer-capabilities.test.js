import assert from 'node:assert/strict'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'
import {
  DEFAULT_DESIGNER_PREFERENCE,
  designerPreferenceStorageKey,
  loadDesignerPreference,
  resetDesignerPreference,
  saveDesignerPreference
} from '../../src/utils/workflowDesignerPreference.js'
/**
 * 验证模型级自动抄送结构化作者契约、正式身份目录和抄送首次阅读链路。
 * @returns {Promise<void>} BPMN 往返、服务端已读或受控目录任一退化时断言失败。
 */
test('自动抄送规则与抄送首次阅读连接正式 BPMN 和服务端状态', async () => {
  const processRules = JSON.stringify({
    version: 1,
    rules: [
      {
        id: 'auto_copy_process',
        trigger: 'PROCESS_COMPLETED',
        recipients: [{ type: 'INITIATOR', values: [] }]
      }
    ]
  }).replaceAll('"', '&quot;')
  const taskRules = JSON.stringify({
    version: 1,
    rules: [
      {
        id: 'auto_copy_task',
        trigger: 'NODE_ARRIVED',
        recipients: [
          { type: 'USER', values: ['1', '2'] },
          { type: 'GROUP', values: ['ROLE3', 'DEPT4'] },
          { type: 'FORM_USER_FIELD', values: ['managerId'] }
        ]
      }
    ]
  }).replaceAll('"', '&quot;')
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:auto-copy">
  <process id="autoCopyContract" isExecutable="true">
    <extensionElements><flowable:properties><flowable:property name="approva.autoCopyRules" value="${processRules}" /></flowable:properties></extensionElements>
    <userTask id="review"><extensionElements><flowable:properties><flowable:property name="approva.autoCopyRules" value="${taskRules}" /></flowable:properties></extensionElements></userTask>
  </process>
</definitions>`
  const { rootElement, warnings } = await moddle.fromXML(source)
  assert.deepEqual(warnings, [])
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const task = process.flowElements.find(element => element.$type === 'bpmn:UserTask')
  /**
   * 读取测试 BPMN 元素中的自动抄送属性正文。
   * @param {object} element Process 或 UserTask moddle 对象。
   * @returns {string} `approva.autoCopyRules` JSON 文本。
   */
  const propertyValue = element =>
    element.extensionElements.values.find(value => value.$type === 'flowable:Properties').values.find(value => value.name === 'approva.autoCopyRules').value
  assert.equal(JSON.parse(propertyValue(process)).rules[0].trigger, 'PROCESS_COMPLETED')
  assert.deepEqual(JSON.parse(propertyValue(task)).rules[0].recipients[1].values, ['ROLE3', 'DEPT4'])
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /approva\.autoCopyRules/)
  assert.match(xml, /NODE_ARRIVED/)
})
/**
 * 验证整改循环只写入平台固定属性，字段目录、显式应用和详情审计均连接正式业务投影。
 * @returns {Promise<void>} 出现任意表达式、半成品即时写入或循环审计姓名丢失时断言失败。
 */
test('受控整改循环以固定属性完成作者 XML 往返并连接正式详情审计', async () => {
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
  const properties = task.extensionElements.values.find(value => value.$type === 'flowable:Properties').values
  assert.deepEqual(
    properties.map(property => [property.name, property.value]),
    [
      ['approva.controlledLoop.enabled', 'true'],
      ['approva.controlledLoop.decisionVariable', 'reviewResult'],
      ['approva.controlledLoop.repeatValue', 'RECTIFY'],
      ['approva.controlledLoop.exitValue', 'PASS'],
      ['approva.controlledLoop.maxIterations', '3']
    ]
  )
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /approva\.controlledLoop\.maxIterations" value="3"/)
  assert.doesNotMatch(xml, /standardLoopCharacteristics|conditionExpression/)
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
    'bpmn:ManualTask',
    'bpmn:SendTask',
    'bpmn:ReceiveTask',
    'bpmn:BusinessRuleTask',
    'bpmn:ParallelGateway',
    'bpmn:InclusiveGateway',
    'bpmn:EventBasedGateway',
    'bpmn:IntermediateCatchEvent',
    'bpmn:IntermediateThrowEvent',
    'bpmn:SubProcess',
    'bpmn:Transaction',
    'bpmn:DataObjectReference',
    'bpmn:DataStoreReference'
  ])
    assert.equal(types.has(type), true, `缺少 ${type}`)
  assert.equal(process.laneSets[0].lanes[0].flowNodeRef[0].id, 'manual')
  assert.equal(
    process.artifacts.some(element => element.$type === 'bpmn:Association'),
    true
  )
  assert.equal(
    process.artifacts.some(element => element.$type === 'bpmn:Group'),
    true
  )
  assert.equal(
    process.artifacts.some(element => element.$type === 'bpmn:TextAnnotation'),
    true
  )
  const collaboration = rootElement.rootElements.find(element => element.$type === 'bpmn:Collaboration')
  assert.equal(collaboration.participants.length, 2)
  assert.equal(collaboration.messageFlows[0].messageRef.id, 'messageNotice')

  const { xml } = await moddle.toXML(rootElement, { format: true })
  for (const tag of [
    'laneSet',
    'manualTask',
    'sendTask',
    'receiveTask',
    'businessRuleTask',
    'parallelGateway',
    'inclusiveGateway',
    'eventBasedGateway',
    'intermediateCatchEvent',
    'intermediateThrowEvent',
    'subProcess',
    'transaction',
    'dataObjectReference',
    'dataStoreReference',
    'textAnnotation',
    'association',
    'group',
    'participant',
    'messageFlow'
  ])
    assert.match(xml, new RegExp(`<${tag}(?:\\s|>)`))
})

/**
 * 使用实际 moddle 验证活动循环和通用扩展属性通过属性面板完整往返。
 * @returns {Promise<void>} 循环类型、名值属性或受控编辑入口丢失时断言失败。
 */
test('活动循环和通用扩展属性执行真实 XML 往返', async () => {
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
  assert.deepEqual(
    properties.values.map(item => [item.name, item.value]),
    [
      ['business.owner', 'finance'],
      ['retentionDays', '30']
    ]
  )
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /<standardLoopCharacteristics loopMaximum="3">/)
  assert.match(xml, /<flowable:property name="business.owner" value="finance" \/>/)
})

/**
 * 验证五种会签和或签人员来源使用受控字段，并完成指定角色、旧固定用户与发起来源 BPMN 往返。
 * @returns {Promise<void>} 人员来源、身份属性、启动请求或受控多实例核心字段缺失时断言失败。
 */
test('会签和或签五种人员来源执行受控页面与 XML 契约', async () => {
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:configured-multi-instance">
  <process id="configuredMultiInstance" isExecutable="true">
    <startEvent id="start" />
    <userTask id="countersign" flowable:assignee="\${assignee}">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="approva.multiInstance.identityType" value="ROLE" />
          <flowable:property name="approva.multiInstance.identityIds" value="101,102" />
        </flowable:properties>
      </extensionElements>
      <multiInstanceLoopCharacteristics flowable:collection="\${multiInstanceHandler.getConfiguredUserIds(execution)}" flowable:elementVariable="assignee">
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
  assert.equal(task.loopCharacteristics.get('flowable:collection'), '${multiInstanceHandler.getConfiguredUserIds(execution)}')
  assert.equal(task.loopCharacteristics.get('flowable:elementVariable'), 'assignee')
  assert.equal(task.loopCharacteristics.completionCondition.body, '${nrOfCompletedInstances == nrOfInstances}')
  const identityProperties = task.extensionElements.values.find(value => value.$type === 'flowable:Properties').values
  assert.deepEqual(
    identityProperties.map(property => [property.name, property.value]),
    [
      ['approva.multiInstance.identityType', 'ROLE'],
      ['approva.multiInstance.identityIds', '101,102']
    ]
  )
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /flowable:collection="\$\{multiInstanceHandler\.getConfiguredUserIds\(execution\)\}"/)
  assert.match(xml, /approva\.multiInstance\.identityType" value="ROLE"/)
  assert.match(xml, /approva\.multiInstance\.identityIds" value="101,102"/)

  const startSource = source
    .replace('${multiInstanceHandler.getConfiguredUserIds(execution)}', '${multiInstanceHandler.getStartUserIds(execution)}')
    .replace(/\s*<extensionElements>[\s\S]*?<\/extensionElements>/, '')
  const startDocument = await moddle.fromXML(startSource)
  assert.deepEqual(startDocument.warnings, [])
  const startProcess = startDocument.rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const startTask = startProcess.flowElements.find(element => element.id === 'countersign')
  assert.equal(startTask.loopCharacteristics.get('flowable:collection'), '${multiInstanceHandler.getStartUserIds(execution)}')
  const serializedStart = await moddle.toXML(startDocument.rootElement, {
    format: true
  })
  assert.match(serializedStart.xml, /flowable:collection="\$\{multiInstanceHandler\.getStartUserIds\(execution\)\}"/)

  const legacySource = startSource.replace('${multiInstanceHandler.getStartUserIds(execution)}', "${multiInstanceHandler.getFixedUserIds(execution, '8,9')}")
  const legacyDocument = await moddle.fromXML(legacySource)
  const legacyProcess = legacyDocument.rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const legacyTask = legacyProcess.flowElements.find(element => element.id === 'countersign')
  assert.equal(legacyTask.loopCharacteristics.get('flowable:collection'), "${multiInstanceHandler.getFixedUserIds(execution, '8,9')}")
})

/**
 * 验证 UserTask SLA 使用正式目录和八个受控属性完成真实 XML 往返。
 * @returns {Promise<void>} 自由输入目录、保留属性旁路或任一 SLA 字段丢失时测试失败。
 */
test('UserTask 审批 SLA 通过正式目录写入受控属性', async () => {
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
 * 验证非业务设计器偏好按协议版本和用户隔离保存在浏览器，损坏数据可自动恢复。
 * @returns {void} 用户隔离、字段白名单、损坏恢复或重置边界任一漂移时断言失败。
 */
test('设计器偏好按用户隔离并自动恢复损坏本地数据', () => {
  const values = new Map()
  const storage = {
    getItem: key => values.has(key) ? values.get(key) : null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  }
  const firstKey = designerPreferenceStorageKey(101)
  const secondKey = designerPreferenceStorageKey(202)
  const firstPreference = saveDesignerPreference(101, {
    theme: 'DARK',
    gridEnabled: false,
    minimapEnabled: false,
    lintEnabled: true,
    tokenSimulationEnabled: true,
    propertiesCollapsed: true,
    ignoredField: '不能持久化'
  }, storage)
  saveDesignerPreference(202, { theme: 'LIGHT' }, storage)

  assert.deepEqual(loadDesignerPreference(101, storage), firstPreference)
  assert.equal(loadDesignerPreference(202, storage).theme, 'LIGHT')
  assert.deepEqual(Object.keys(JSON.parse(values.get(firstKey))).sort(), [
    'gridEnabled', 'lintEnabled', 'minimapEnabled', 'propertiesCollapsed',
    'schemaVersion', 'theme', 'tokenSimulationEnabled'
  ])

  values.set(firstKey, '{broken-json')
  assert.deepEqual(loadDesignerPreference(101, storage), DEFAULT_DESIGNER_PREFERENCE)
  assert.deepEqual(JSON.parse(values.get(firstKey)), {
    schemaVersion: 1,
    ...DEFAULT_DESIGNER_PREFERENCE
  })
  values.set(firstKey, JSON.stringify({ schemaVersion: 0, theme: 'DARK' }))
  assert.deepEqual(loadDesignerPreference(101, storage), DEFAULT_DESIGNER_PREFERENCE)

  resetDesignerPreference(101, storage)
  assert.equal(values.has(firstKey), false)
  assert.equal(values.has(secondKey), true)
})
/**
 * 使用实际 bpmn-moddle 解析并序列化 FormData，验证自定义类型不会丢失日期、读写约束和枚举值。
 * @returns {Promise<void>} XML 往返不完整时断言失败。
 */
test('Flowable FormData moddle 执行真实 XML 往返', async () => {
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
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const config =
    '{&quot;expression&quot;:&quot;amount &gt;= 1000.5 &amp;&amp; approved&quot;,&quot;resultVariable&quot;:&quot;eligible&quot;,&quot;resultType&quot;:&quot;BOOL&quot;,&quot;variables&quot;:[{&quot;name&quot;:&quot;amount&quot;,&quot;type&quot;:&quot;DOUBLE&quot;},{&quot;name&quot;:&quot;approved&quot;,&quot;type&quot;:&quot;BOOL&quot;}]}'
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
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const config =
    '{&quot;endpointKey&quot;:&quot;finance.api&quot;,&quot;method&quot;:&quot;POST&quot;,&quot;path&quot;:&quot;/workflow/notify&quot;,&quot;bodyVariable&quot;:&quot;payload&quot;,&quot;statusVariable&quot;:&quot;httpStatus&quot;}'
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
