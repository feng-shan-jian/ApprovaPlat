import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
import { createControlledLoopDomain } from '../../src/components/workflow/designer/controlledLoopDomain.js'
import { createDesignerFormFieldCatalog } from '../../src/components/workflow/designer/designerFormFieldCatalog.js'
import { createFormParticipantDomain } from '../../src/components/workflow/designer/formParticipantDomain.js'
import { createParticipantRuleDomain } from '../../src/components/workflow/designer/participantRuleDomain.js'
import { createRoutingCallActivityDomain } from '../../src/components/workflow/designer/routingCallActivityDomain.js'
import {
  createDefaultSlaConfig,
  createExtensionEventSlaDomain
} from '../../src/components/workflow/designer/extensionEventSlaDomain.js'
import {
  createUserTaskSlaDomain,
  isUserTaskSlaProperty
} from '../../src/components/workflow/designer/userTaskSlaDomain.js'
import { completeWorkflowFieldPermissions } from '../../src/components/workflow/form/formTemplate.js'

/**
 * 创建三个设计器领域测试共用的只读上下文。
 * @param {object} overrides 需要覆盖的选择状态、属性状态或外部目录函数。
 * @returns {object} 不连接浏览器和 bpmn-js 的领域契约测试上下文。
 */
function createDomainContext(overrides = {}) {
  // 测试上下文与生产装配相同：先创建共享扩展目录，再创建只读字段目录实例。
  const formFieldOptions = overrides.formFieldOptions || ref([])
  const props = overrides.props || { forms: [], identityOptions: {} }
  const formFieldCatalog = overrides.formFieldCatalog || createDesignerFormFieldCatalog({
    forms: () => props.forms,
    formFieldOptions: () => formFieldOptions.value
  })
  return {
    buildPropertiesExtensionElements() {},
    designerLocked: ref(false),
    emit() {},
    formFieldCatalog,
    formFieldOptions,
    getModeler() { return null },
    isForeignProtectedPropertyName() { return false },
    isProcess: ref(false),
    isType(type) { return type === 'bpmn:UserTask' },
    isUserTask: ref(true),
    listBpmnEventCodeOptions: async () => ({ data: [] }),
    listCallActivityOptions: async () => ({ data: [] }),
    listCelExtensionOptions: async () => ({ data: [] }),
    listConnectorEndpointOptions: async () => ({ data: [] }),
    listDmnDecisionOptions: async () => ({ data: [] }),
    listEnabledSlaCalendars: async () => ({ data: [] }),
    listFormFieldExtensionOptions: async () => ({ data: [] }),
    listHttpExtensionOptions: async () => ({ data: [] }),
    listJavaExtensionOptions: async () => ({ data: [] }),
    listSqlDataSourceOptions: async () => ({ data: [] }),
    listSqlExtensionOptions: async () => ({ data: [] }),
    loadPropertyState() {},
    persistExtensionProperties() {},
    propertyFlags: ref({ process: false, formSupported: true, callActivity: false }),
    propertyState: {},
    props,
    readAllFlowableProperties() { return [] },
    readExtensionProperties() { return [] },
    selectedBusinessObject: ref(null),
    selectedElement: ref(null),
    updateProperties() {},
    ...overrides
  }
}

test('各业务目录只消费同一次后端投影，选择节点不重新推演原始模板', async () => {
  const process = { $type: 'bpmn:Process', id: 'main' }
  const task = { $type: 'bpmn:UserTask', id: 'task', $parent: process }
  const call = { $type: 'bpmn:CallActivity', id: 'call', $parent: process }
  const owner = { value: 'owner', label: '负责人（owner）' }
  const decision = { value: 'decision', label: '结论', type: 'SCALAR',
    values: [{ value: 'PASS', label: '通过' }], valueRestricted: true }
  const system = { name: 'result', type: 'NUMBER', readable: false, writable: false, systemWritable: true }
  const data = { nodes: [{ processKey: 'main', nodeKey: 'task', userIdFields: [owner], loopFields: [decision] }],
    processes: [{ processKey: 'main', userIdFields: [], conditionFields: [decision], callFields: [system] }], issues: [] }
  const catalog = createDesignerFormFieldCatalog({
    forms: [{ formId: 7, content: JSON.stringify({ fields: [{ vModel: 'forged', tag: 'el-input', workflowWritable: true }] }) }],
    loadAuthorFields: async () => ({ data })
  })
  const context = createDomainContext({ formFieldCatalog: catalog, selectedBusinessObject: ref(task),
    selectedElement: ref({ businessObject: task }), propertyState: { formKey: 'key_7', formSource: 'TEMPLATE' } })
  const participants = createFormParticipantDomain(context)
  const loops = createControlledLoopDomain(context)
  const extensions = createExtensionEventSlaDomain(context)
  const routing = createRoutingCallActivityDomain(context)
  assert.deepEqual(participants.participantFormFieldOptions.value, [])
  await catalog.refresh('current XML')
  assert.deepEqual(participants.participantFormFieldOptions.value, [owner])
  assert.deepEqual(loops.resolveControlledLoopFieldOptions(), [decision])
  assert.deepEqual(extensions.autoCopyFormFieldOptions.value, [owner])
  assert.deepEqual(routing.resolveCallActivityParentFields(call), [system])
  context.selectedBusinessObject.value = process
  assert.deepEqual(extensions.autoCopyFormFieldOptions.value, [])
  context.selectedBusinessObject.value = { ...task, id: 'other' }
  assert.deepEqual(participants.participantFormFieldOptions.value, [])
  const foreign = { $type: 'bpmn:Process', id: 'foreign' }
  assert.equal(catalog.nodeFields({ ...task, $parent: foreign }), undefined)
  assert.equal(catalog.processFields(foreign), undefined)
  catalog.invalidate()
  context.selectedBusinessObject.value = task
  assert.deepEqual(participants.participantFormFieldOptions.value, [])
  assert.deepEqual(routing.resolveCallActivityParentFields(call), [])
})

test('作者字段目录拒绝乱序、撤销及卸载后的响应，失败不回退，坏节点不屏蔽健康节点', async () => {
  const requests = []
  const catalog = createDesignerFormFieldCatalog({ loadAuthorFields: xml => new Promise((resolve, reject) => requests.push({ xml, resolve, reject })) })
  const process = { id: 'main', $type: 'bpmn:Process' }
  const task = { id: 'task', $type: 'bpmn:UserTask', $parent: process }
  const data = value => ({ nodes: [{ processKey: 'main', nodeKey: 'task', userIdFields: [{ value }], loopFields: [] }],
    processes: [], issues: [{ processKey: 'main', nodeKey: 'broken', message: '请配置完整字段权限' }] })
  const first = catalog.refresh('before permission change')
  catalog.invalidate()
  const second = catalog.refresh('after permission change')
  requests[1].resolve({ data: data('newOwner') })
  await second
  requests[0].resolve({ data: data('oldOwner') })
  await first
  assert.equal(catalog.nodeFields(task).userIdFields[0].value, 'newOwner')
  assert.equal(catalog.issueFor(task), '')
  assert.match(catalog.issueFor(process), /broken：请配置/)
  assert.match(catalog.issueFor({ id: 'call', $type: 'bpmn:CallActivity', $parent: process }), /broken/)
  await catalog.refresh('after permission change')
  assert.equal(requests.length, 2)
  const third = catalog.refresh('changed again')
  assert.equal(catalog.nodeFields(task), undefined)
  requests[2].reject(new Error('offline'))
  await third
  assert.equal(catalog.nodeFields(task), undefined)
  assert.equal(catalog.issueFor(task), 'offline')
  const fourth = catalog.refresh('changed again')
  catalog.invalidate()
  requests[3].resolve({ data: data('stale after unmount') })
  await fourth
  assert.equal(catalog.nodeFields(task), undefined)
})

test('原始模板展开仅服务权限编辑，保留重名与内嵌字段结构', () => {
  const catalog = createDesignerFormFieldCatalog()
  assert.deepEqual(catalog.readTemplateFieldDescriptors(JSON.stringify({ fields: [
    { children: [{ vModel: 'owner' }, { vModel: 'owner' }] }
  ] })).map(field => field.variable), ['owner', 'owner'])
  assert.deepEqual(catalog.readTemplateFieldDescriptors('{'), [])
  assert.deepEqual(catalog.readEmbeddedFormFields({ extensionElements: { values: [
    { $type: 'flowable:FormProperty', id: 'owner', variable: 'owner', type: 'CUSTOM:Employee', readable: true, writable: false }
  ] } }), [{ id: 'owner', variable: 'owner', name: 'owner', type: 'custom:Employee', required: false,
    readable: true, writable: false, datePattern: '', values: [] }])
})

test('服务端字段投影供整改循环与 FORM_USER 原子写入，错误恢复且保留普通属性', async () => {
  const process = { id: 'main', $type: 'bpmn:Process' }
  const task = { id: 'task', $type: 'bpmn:UserTask', $parent: process, extensionElements: { values: [] } }
  const catalog = createDesignerFormFieldCatalog({ loadAuthorFields: async () => ({ data: {
    nodes: [{ processKey: 'main', nodeKey: 'task', userIdFields: [{ value: 'owner', label: '负责人' }],
      loopFields: [{ value: 'decision', type: 'SCALAR', values: [], valueRestricted: false }] }], processes: [], issues: []
  } }) })
  await catalog.refresh('current')
  const state = { multiInstanceType: 'approvalLoop', controlledLoopDecisionVariable: 'decision',
    controlledLoopRepeatValue: 'REJECT', controlledLoopExitValue: 'PASS', controlledLoopMaxIterations: 3 }
  const writes = []
  const builds = []
  const errors = []
  let restored = 0
  let properties
  const domain = createFormParticipantDomain(createDomainContext({
    formFieldCatalog: catalog, propertyState: state, getModeler() { return {} },
    selectedBusinessObject: ref(task), selectedElement: ref({ businessObject: task }),
    readAllFlowableProperties() { return [{ name: 'business.property', value: 'keep' }] },
    buildPropertiesExtensionElements(businessObject, editable, controlled) {
      builds.push({ businessObject, editable, controlled }); return { id: 'atomic' }
    },
    updateProperties(changes) { writes.push(changes) },
    persistExtensionProperties(value) { properties = value },
    loadPropertyState() { restored += 1 }, emit(name, error) { errors.push(error) }
  }))
  domain.updateMultiInstance()
  assert.equal(writes.length, 1)
  assert.deepEqual(writes[0], { loopCharacteristics: undefined, extensionElements: { id: 'atomic' } })
  assert.deepEqual(builds[0].editable, [{ name: 'business.property', value: 'keep' }])
  assert.deepEqual(builds.at(-1).controlled, [
    { name: 'approva.controlledLoop.enabled', value: 'true' },
    { name: 'approva.controlledLoop.decisionVariable', value: 'decision' },
    { name: 'approva.controlledLoop.repeatValue', value: 'REJECT' },
    { name: 'approva.controlledLoop.exitValue', value: 'PASS' },
    { name: 'approva.controlledLoop.maxIterations', value: '3' }
  ])
  state.controlledLoopRepeatValue = 'PASS'
  domain.updateMultiInstance()
  assert.equal(writes.length, 1)
  assert.equal(restored, 1)
  assert.match(errors[0].message, /不能相同/)
  state.multiInstanceType = 'none'
  domain.updateParticipantRule({ type: 'FORM_USER', targetIds: [], formField: 'owner' })
  assert.deepEqual(properties, [
    { name: 'business.property', value: 'keep' },
    { name: 'approva.assignment.ruleVersion', value: '1' },
    { name: 'approva.assignment.type', value: 'FORM_USER' },
    { name: 'approva.assignment.targetIds', value: '' },
    { name: 'approva.assignment.formField', value: 'owner' },
    { name: 'approva.assignment.noMatchPolicy', value: 'FAIL' }
  ])
  const rules = createParticipantRuleDomain({ readParticipantFormFieldOptions: () => [{ value: 'owner' }], readAllFlowableProperties: () => [] })
  assert.throws(() => rules.normalizeParticipantRule({ type: 'ROLES', targetIds: ['12'], formField: '' }, true), /角色目录/)
})

test('节点权限编辑读取原始模板与 XML 四态，不把显示元数据当作业务目录', () => {
  const content = JSON.stringify({ fields: [{ vModel: 'owner', label: '负责人', tag: 'el-input',
    workflowHidden: false, workflowReadable: true, workflowWritable: false }] })
  const context = createDomainContext({ props: { forms: [{ formId: 7, content }] },
    propertyState: { formSource: 'TEMPLATE', formKey: 'key_7', formPermissionDefault: 'EDITABLE', formPermissionFields: [] } })
  const domain = createFormParticipantDomain(context)
  assert.deepEqual(domain.resolveFormPermissionSourceFields(), [{ variable: 'owner', label: '负责人', mode: 'READONLY' }])
  const policy = domain.readTemplatePermissionPolicy({ extensionElements: { values: [
    { $type: 'flowable:FormProperty', id: 'approva_permission_default', readable: true, writable: true },
    { $type: 'flowable:FormProperty', id: 'approva_permission_field_1', variable: 'owner', readable: false, writable: false }
  ] } })
  assert.deepEqual(policy, { defaultMode: 'EDITABLE', permissions: new Map([['owner', 'HIDDEN']]) })
  assert.deepEqual(domain.participantFormFieldOptions.value, [])
})

test('表单生成器输出补齐正式节点权限', () => {
  const fields = completeWorkflowFieldPermissions([{
    vModel: 'title', tag: 'el-input', layout: 'colFormItem'
  }, {
    layout: 'rowFormItem',
    children: [{
      vModel: 'readonlyNote', tag: 'el-input', layout: 'colFormItem',
      workflowHidden: false, workflowReadable: true, workflowWritable: false
    }]
  }])

  assert.deepEqual(
    [fields[0].workflowHidden, fields[0].workflowReadable, fields[0].workflowWritable],
    [false, true, true])
  assert.deepEqual([
    fields[1].children[0].workflowHidden,
    fields[1].children[0].workflowReadable,
    fields[1].children[0].workflowWritable
  ], [false, true, false])
})

/**
 * 验证表单键只接受后端精确协议，并以十进制文本匹配完整 Long 主键。
 * @returns {void} 裸数字键被接受或超出安全整数的主键发生 Number 精度丢失时测试失败。
 */
test('表单键使用精确 key_正Long 文本协议', () => {
  const unsafeLong = '9007199254740993'
  const catalog = createDesignerFormFieldCatalog({
    forms: [{ formId: unsafeLong, content: '{"fields":[]}' }]
  })

  assert.equal(catalog.resolveTemplateForm(`key_${unsafeLong}`)?.formId, unsafeLong)
  assert.equal(catalog.resolveTemplateForm(unsafeLong), undefined)
  assert.equal(catalog.resolveTemplateForm(`key_0`), undefined)
  assert.equal(catalog.resolveTemplateForm(`key_${unsafeLong}x`), undefined)
})

/**
 * 验证表单与参与者模块严格规范化多实例身份，并在保存前拒绝未完成选择。
 * @returns {void} 身份前缀、Java Long 上限或重复规则漂移时测试失败。
 */
test('表单与参与者领域独立校验指定多实例身份', () => {
  const propertyState = {
    multiInstanceType: 'controlled',
    multiInstanceMemberSource: 'role',
    configuredMultiInstanceIdentityIds: ['ROLE12', 'ROLE35'],
    assignmentType: 'assignee',
    assignee: '12',
    candidateUsers: [],
    candidateGroups: []
  }
  const domain = createFormParticipantDomain(createDomainContext({
    propertyState,
  }))

  assert.deepEqual(domain.normalizeConfiguredMultiInstanceIdentity('role', ['ROLE12', 'ROLE35']), {
    source: 'role',
    type: 'ROLE',
    ids: ['12', '35'],
    selectionValues: ['ROLE12', 'ROLE35']
  })
  assert.equal(domain.normalizeConfiguredMultiInstanceIdentity('role', ['12']), null)
  assert.equal(domain.normalizeConfiguredMultiInstanceIdentity('user', ['7', '7']), null)
  assert.equal(domain.normalizeConfiguredMultiInstanceIdentity('user', ['9223372036854775808']), null)
  assert.equal(domain.validatePendingMultiInstanceSelection(), '')

  propertyState.configuredMultiInstanceIdentityIds = []
  assert.match(domain.validatePendingMultiInstanceSelection(), /必须选择 1 至 100 个有效用户、角色或部门/)
})

/**
 * 验证路由与调用活动模块独立执行变量目录、重复目标和类型兼容约束。
 * @returns {void} 非目录字段、重复目标或不兼容类型被放行时测试失败。
 */
test('保存映射只拦截未写入 XML 的半行，目录加载中由服务端裁决已有规则', () => {
  const call = { id: 'call', $type: 'bpmn:CallActivity' }
  const state = { callInMappings: [{ source: 'unknown', target: 'target' }], callOutMappings: [] }
  const context = createDomainContext({ selectedBusinessObject: ref(call), propertyState: state })
  const domain = createRoutingCallActivityDomain(context)
  assert.equal(domain.validateCallActivityConfiguration(context.selectedBusinessObject.value), '')
  state.callOutMappings.push({ source: 'result', target: '' })
  assert.match(domain.validateCallActivityConfiguration(context.selectedBusinessObject.value), /未完成/)
})

test('路由领域把条件编辑原子写入命令栈', () => {
  let extensionBuild
  let command
  const extensionElements = { id: 'condition-extension-elements' }
  const domain = createRoutingCallActivityDomain(createDomainContext({
    buildPropertiesExtensionElements(businessObject, ordinary, controlled) {
      extensionBuild = { businessObject, ordinary, controlled }
      return extensionElements
    },
    getModeler() {
      return {
        get(service) {
          assert.equal(service, 'modeling')
          return {
            updateProperties(element, changes) { command = { element, changes } }
          }
        }
      }
    },
    readAllFlowableProperties() {
      return [
        { name: 'business.property', value: 'keep' },
        { name: 'approva.conditionRule.config', value: 'old' }
      ]
    }
  }))
  const flowElement = { businessObject: { id: 'Flow_Approved' } }
  const condition = { version: 1, default: false, clauses: [] }
  domain.persistConditionConfig(flowElement, '批准', condition)
  assert.deepEqual(extensionBuild, {
    businessObject: flowElement.businessObject,
    ordinary: [{ name: 'business.property', value: 'keep' }],
    controlled: [{ name: 'approva.conditionRule.config', value: JSON.stringify(condition) }]
  })
  assert.deepEqual(command, {
    element: flowElement,
    changes: {
      name: '批准',
      conditionExpression: undefined,
      extensionElements
    }
  })
})

/**
 * 验证扩展事件与 SLA 模块独立固化自动抄送结构和 SLA 默认边界。
 * @returns {void} 非法触发、越权表单字段或默认 SLA 协议漂移时测试失败。
 */
test('扩展事件与 SLA 领域独立校验自动抄送契约', () => {
  let persistedProperties
  const propertyState = {}
  const domain = createExtensionEventSlaDomain(createDomainContext({
    getModeler() { return {} },
    persistExtensionProperties(properties) { persistedProperties = properties },
    propertyState,
    readAllFlowableProperties() {
      return [
        { name: 'approva.conditionRule.config', value: 'keep' },
        { name: 'approva.autoCopyRules', value: 'old' }
      ]
    },
    selectedBusinessObject: ref({ id: 'Task_Approve' }),
    selectedElement: ref({ id: 'Task_Approve' })
  }))
  const rules = [{
    id: 'copy-on-completed',
    trigger: 'NODE_COMPLETED',
    recipients: [
      { type: 'USER', values: ['12'] },
      { type: 'FORM_USER_FIELD', values: ['approverId'] }
    ]
  }]

  assert.deepEqual(domain.normalizeAndValidateAutoCopyRules(rules, {
    allowedTriggers: ['NODE_COMPLETED'],
    allowedFormFields: ['approverId']
  }), rules)
  assert.throws(() => domain.normalizeAndValidateAutoCopyRules(rules, {
    allowedTriggers: ['NODE_ARRIVED'],
    allowedFormFields: ['approverId']
  }), /触发时机与当前元素不匹配/)
  assert.throws(() => domain.normalizeAndValidateAutoCopyRules(rules, {
    allowedTriggers: ['NODE_COMPLETED'],
    allowedFormFields: ['requesterId']
  }), /必须来自当前元素可见的正式标量字段/)

  const persistedRules = [{
    id: 'copy-user',
    trigger: 'NODE_COMPLETED',
    recipients: [{ type: 'USER', values: ['12'] }]
  }]
  domain.updateAutoCopyRules(persistedRules)
  assert.deepEqual(persistedProperties, [
    { name: 'approva.conditionRule.config', value: 'keep' },
    {
      name: 'approva.autoCopyRules',
      value: JSON.stringify({ version: 1, rules: persistedRules })
    }
  ])
  assert.deepEqual(propertyState.autoCopyRules, persistedRules)
  assert.deepEqual(createDefaultSlaConfig(), {
    enabled: false,
    calendarKey: '',
    reminderMinutes: 60,
    reminderRepeatMinutes: 60,
    maxReminders: 1,
    escalationMinutes: 240,
    escalationUserId: '',
    escalationEventCode: ''
  })
  const slaDomain = createUserTaskSlaDomain({
    readSlaCalendarOptions: () => [{ calendarKey: 'workday' }],
    readEscalationEventOptions: () => [{ eventCode: 'ESCALATE' }]
  })
  const normalizedSla = slaDomain.normalizeAndValidateSlaConfig({
    enabled: true,
    calendarKey: ' workday ',
    reminderMinutes: '30',
    reminderRepeatMinutes: '60',
    maxReminders: '2',
    escalationMinutes: '180',
    escalationUserId: '',
    escalationEventCode: ' ESCALATE '
  })
  const slaProperties = slaDomain.slaConfigToProperties(normalizedSla)
  assert.deepEqual(slaDomain.readSlaConfig(slaProperties), normalizedSla)
  assert.equal(isUserTaskSlaProperty('approva.sla.maxReminders'), true)
  assert.equal(isUserTaskSlaProperty('approva.autoCopyRules'), false)
  assert.equal(slaDomain.isBoundedSlaMinute(525600), true)
  assert.throws(() => slaDomain.normalizeAndValidateSlaConfig({
    ...normalizedSla,
    calendarKey: 'disabled-calendar'
  }), /审批 SLA 必须选择已启用的正式业务日历/)
  domain.updateSlaProperties(createDefaultSlaConfig())
  assert.deepEqual(persistedProperties.slice(0, 2), [
    { name: 'approva.conditionRule.config', value: 'keep' },
    { name: 'approva.autoCopyRules', value: 'old' }
  ])
})
