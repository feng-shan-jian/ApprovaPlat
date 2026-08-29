import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
import { createDesignerFormFieldCatalog } from '../../src/components/workflow/designer/designerFormFieldCatalog.js'
import { createFormParticipantDomain } from '../../src/components/workflow/designer/formParticipantDomain.js'
import { createRoutingCallActivityDomain } from '../../src/components/workflow/designer/routingCallActivityDomain.js'
import {
  createDefaultSlaConfig,
  createExtensionEventSlaDomain
} from '../../src/components/workflow/designer/extensionEventSlaDomain.js'

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

/**
 * 验证共享字段目录保持正式模板与内嵌 FormProperty 的既有字段顺序和投影结果。
 * @returns {void} 新目录或旧 facade 的字段类型、枚举值、标签和顺序漂移时测试失败。
 */
test('共享字段目录保持正式模板和内嵌表单字段投影', () => {
  const templateContent = JSON.stringify({
    fields: [
      {
        __vModel__: 'amount',
        __config__: { tag: 'el-input-number', label: '申请金额' }
      },
      {
        __vModel__: 'decision',
        __config__: { tag: 'el-radio-group', label: '审批结论', workflowEnum: true },
        __slot__: { options: [{ value: 'PASS', label: '通过' }, { value: 'REJECT', label: '驳回' }] }
      },
      {
        __config__: {
          children: [{
            __vModel__: 'readonlyNote',
            __config__: { tag: 'el-input', label: '只读说明', workflowWritable: false }
          }]
        }
      }
    ]
  })
  const props = { forms: [{ formId: 7, content: templateContent }], identityOptions: {} }
  const context = createDomainContext({
    props,
    propertyState: {
      formSource: 'TEMPLATE',
      formKey: 'key_7',
      formPermissionDefault: 'EDITABLE',
      formPermissionFields: []
    }
  })
  const legacyFacade = createFormParticipantDomain(context)
  const templateBusinessObject = {
    get(name) { return name === 'flowable:formKey' ? 'key_7' : '' },
    extensionElements: {
      values: [{
        $type: 'flowable:FormProperty',
        id: 'approva_permission_field_1',
        variable: 'decision',
        readable: false,
        writable: false
      }]
    }
  }
  const embeddedBusinessObject = {
    get() { return '' },
    extensionElements: {
      values: [
        {
          $type: 'flowable:FormProperty',
          id: 'approved',
          variable: 'approved',
          name: '是否通过',
          type: 'BOOLEAN'
        },
        {
          $type: 'flowable:FormProperty',
          id: 'result',
          variable: 'result',
          name: '处理结果',
          type: 'enum',
          values: [{ id: 'DONE', name: '完成' }]
        },
        {
          $type: 'flowable:FormProperty',
          id: 'readonlyDate',
          variable: 'readonlyDate',
          name: '只读日期',
          type: 'date',
          writable: false
        }
      ]
    }
  }
  const expectedTemplate = [
    {
      value: 'amount',
      label: '申请金额（amount）',
      type: 'NUMBER',
      values: [],
      valueRestricted: false
    },
    {
      value: 'decision',
      label: '审批结论（decision）',
      type: 'SCALAR',
      values: [{ value: 'PASS', label: '通过' }, { value: 'REJECT', label: '驳回' }],
      valueRestricted: true
    }
  ]
  const expectedEmbedded = [
    {
      value: 'approved',
      label: '是否通过（approved）',
      type: 'BOOLEAN',
      values: [{ value: 'true', label: '是' }, { value: 'false', label: '否' }],
      valueRestricted: true
    },
    {
      value: 'result',
      label: '处理结果（result）',
      type: 'SCALAR',
      values: [{ value: 'DONE', label: '完成' }],
      valueRestricted: true
    }
  ]

  assert.deepEqual(context.formFieldCatalog.readTemplateFieldDescriptors(templateContent)
    .map(field => field.variable), ['amount', 'decision', 'readonlyNote'])
  assert.deepEqual(context.formFieldCatalog.resolveElementFieldDescriptors(templateBusinessObject)
    .map(field => field.source), ['TEMPLATE', 'TEMPLATE', 'TEMPLATE'])
  assert.deepEqual(context.formFieldCatalog.resolveElementFieldDescriptors(embeddedBusinessObject)
    .map(field => field.variable), ['approved', 'result', 'readonlyDate'])
  assert.deepEqual(legacyFacade.describeFormalFormFields(templateBusinessObject), expectedTemplate)
  assert.deepEqual(legacyFacade.describeFormalFormFields(embeddedBusinessObject), expectedEmbedded)

  const controlledLoopState = {
    multiInstanceType: 'approvalLoop',
    controlledLoopDecisionVariable: 'decision',
    controlledLoopRepeatValue: 'REJECT',
    controlledLoopExitValue: 'PASS',
    controlledLoopMaxIterations: 3
  }
  let controlledLoopBuild
  let controlledLoopWrite
  let controlledLoopWriteCount = 0
  let controlledLoopError
  let controlledLoopRestoreCount = 0
  const controlledLoopFacade = createFormParticipantDomain(createDomainContext({
    props,
    propertyState: controlledLoopState,
    selectedBusinessObject: ref(templateBusinessObject),
    selectedElement: ref({ businessObject: templateBusinessObject }),
    buildPropertiesExtensionElements(businessObject, editable, controlled) {
      controlledLoopBuild = { businessObject, editable, controlled }
      return { id: 'controlled-loop-extension-elements' }
    },
    updateProperties(changes) {
      controlledLoopWrite = changes
      controlledLoopWriteCount += 1
    },
    loadPropertyState() { controlledLoopRestoreCount += 1 },
    emit(name, error) {
      assert.equal(name, 'error')
      controlledLoopError = error
    }
  }))
  controlledLoopFacade.updateMultiInstance()
  assert.deepEqual(controlledLoopBuild.controlled, [
    { name: 'approva.controlledLoop.enabled', value: 'true' },
    { name: 'approva.controlledLoop.decisionVariable', value: 'decision' },
    { name: 'approva.controlledLoop.repeatValue', value: 'REJECT' },
    { name: 'approva.controlledLoop.exitValue', value: 'PASS' },
    { name: 'approva.controlledLoop.maxIterations', value: '3' }
  ])
  assert.deepEqual(controlledLoopWrite, {
    loopCharacteristics: undefined,
    extensionElements: { id: 'controlled-loop-extension-elements' }
  })

  controlledLoopState.controlledLoopRepeatValue = 'PASS'
  controlledLoopFacade.updateMultiInstance()
  assert.equal(controlledLoopError.message, '再次进入和退出条件不能相同')
  assert.equal(controlledLoopWriteCount, 1)
  assert.equal(controlledLoopRestoreCount, 1)
  assert.deepEqual(legacyFacade.resolveFormPermissionSourceFields(), [
    { variable: 'amount', label: '申请金额', mode: 'EDITABLE' },
    { variable: 'decision', label: '审批结论', mode: 'EDITABLE' },
    { variable: 'readonlyNote', label: '只读说明', mode: 'READONLY' }
  ])
  assert.deepEqual(legacyFacade.readTemplatePermissionPolicy(templateBusinessObject), {
    configured: true,
    defaultMode: 'EDITABLE',
    permissions: new Map([['decision', 'HIDDEN']])
  })
  assert.deepEqual(legacyFacade.resolveUserIdFieldCatalog(templateBusinessObject), [
    { value: 'amount', label: '申请金额（amount）', eligible: true, signature: 'el-input-number' },
    { value: 'decision', label: '审批结论（decision）', eligible: false, signature: '' },
    { value: 'readonlyNote', label: '只读说明（readonlyNote）', eligible: true, signature: 'el-input' }
  ])
  assert.deepEqual(legacyFacade.resolveUserIdFieldCatalog(embeddedBusinessObject), [
    { value: 'approved', label: '是否通过（approved）', eligible: false, signature: '' },
    { value: 'result', label: '处理结果（result）', eligible: true, signature: 'el-select' },
    { value: 'readonlyDate', label: '只读日期（readonlyDate）', eligible: false, signature: '' }
  ])

  const process = { $type: 'bpmn:Process', flowElements: [] }
  const embeddedStart = {
    id: 'Start_1',
    $type: 'bpmn:StartEvent',
    $parent: process,
    $instanceOf(type) { return type === 'bpmn:StartEvent' },
    get() { return '' },
    extensionElements: {
      values: [
        { $type: 'flowable:FormProperty', variable: 'amount', name: '文本金额', type: 'string' },
        { $type: 'flowable:FormProperty', variable: 'approved', name: '是否通过', type: 'boolean' }
      ]
    }
  }
  const templateTask = {
    ...templateBusinessObject,
    id: 'Task_1',
    $type: 'bpmn:UserTask',
    $parent: process,
    $instanceOf(type) { return type === 'bpmn:UserTask' }
  }
  const callActivity = { $type: 'bpmn:CallActivity', $parent: process }
  process.flowElements = [embeddedStart, templateTask, callActivity]
  const rolesRule = legacyFacade.normalizeParticipantRule(
    { type: 'ROLES', targetIds: ['ROLE12', 'ROLE35'], formField: '' }, true)
  assert.deepEqual(legacyFacade.participantRulePropertyItems(rolesRule, true), [
    { name: 'approva.startScope.ruleVersion', value: '1' },
    { name: 'approva.startScope.type', value: 'ROLES' },
    { name: 'approva.startScope.targetIds', value: '12,35' },
    { name: 'approva.startScope.noMatchPolicy', value: 'FAIL' }
  ])
  const formUserRule = legacyFacade.normalizeParticipantRule(
    { type: 'FORM_USER', targetIds: [], formField: 'amount' }, false)
  const formUserProperties = legacyFacade.participantRulePropertyItems(formUserRule, false)
  assert.deepEqual(formUserProperties, [
    { name: 'approva.assignment.ruleVersion', value: '1' },
    { name: 'approva.assignment.type', value: 'FORM_USER' },
    { name: 'approva.assignment.targetIds', value: '' },
    { name: 'approva.assignment.formField', value: 'amount' },
    { name: 'approva.assignment.noMatchPolicy', value: 'FAIL' }
  ])
  assert.throws(() => legacyFacade.normalizeParticipantRule(
    { type: 'ROLES', targetIds: ['12'], formField: '' }, true),
    { message: '角色目录选项不合法' })
  assert.deepEqual(legacyFacade.readParticipantRule({
    get(name) { return name === 'flowable:assignee' ? '12' : '' }
  }), { type: 'FIXED_USER', targetIds: ['12'], formField: '' })

  let participantWriteCount = 0
  let persistedParticipantProperties
  const participantState = {
    formSource: 'TEMPLATE', formKey: 'key_7', formPermissionDefault: 'EDITABLE',
    formPermissionFields: [], multiInstanceType: 'none'
  }
  const participantFacade = createFormParticipantDomain(createDomainContext({
    props,
    getModeler() { return {} },
    propertyState: participantState,
    readAllFlowableProperties() { return [{ name: 'business.property', value: 'keep' }] },
    selectedBusinessObject: ref(templateTask),
    selectedElement: ref({ businessObject: templateTask }),
    persistExtensionProperties(properties) {
      persistedParticipantProperties = properties
      participantWriteCount += 1
    }
  }))
  participantFacade.updateParticipantRule(formUserRule)
  assert.equal(participantWriteCount, 1)
  assert.deepEqual(persistedParticipantProperties, [
    { name: 'business.property', value: 'keep' }, ...formUserProperties
  ])
  const routingFacade = createRoutingCallActivityDomain(context)
  assert.deepEqual(routingFacade.resolveCallActivityParentFields(callActivity), [
    { name: 'approved', label: '是否通过', type: 'BOOLEAN', required: false, readable: true, writable: true },
    { name: 'decision', label: '审批结论', type: 'SCALAR', required: false, readable: true, writable: true },
    { name: 'readonlyNote', label: '只读说明', type: 'TEXT', required: false, readable: true, writable: false }
  ])

  const gateway = { $type: 'bpmn:ExclusiveGateway', outgoing: [{ id: 'Flow_1' }, { id: 'Flow_2' }] }
  const selectedFlow = { businessObject: { $parent: process }, source: { businessObject: gateway } }
  const conditionDomain = createRoutingCallActivityDomain(createDomainContext({
    props,
    selectedElement: { value: selectedFlow },
    getModeler() {
      return {
        get(service) {
          assert.equal(service, 'elementRegistry')
          return { getAll: () => [{ businessObject: embeddedStart }, { businessObject: templateTask }] }
        }
      }
    }
  }))
  assert.deepEqual(conditionDomain.resolveConditionFieldCatalog(), {
    fields: [
      {
        value: 'approved',
        label: '是否通过（approved）',
        type: 'BOOLEAN',
        values: [{ value: 'true', label: '是' }, { value: 'false', label: '否' }],
        valueRestricted: true
      },
      {
        value: 'decision',
        label: '审批结论（decision）',
        type: 'SCALAR',
        values: [{ value: 'PASS', label: '通过' }, { value: 'REJECT', label: '驳回' }],
        valueRestricted: true
      }
    ],
    conflicts: ['amount']
  })
})

/**
 * 验证三个领域按中性目录、表单权限 facade、字段消费者的固定顺序完成初始化。
 * @returns {void} 任一领域仍要求另一个尚未赋值的领域变量时测试失败。
 */
test('三个设计器领域按确定顺序初始化且无延迟领域引用', () => {
  const userTask = {
    $type: 'bpmn:UserTask',
    get() { return '' },
    extensionElements: {
      values: [{
        $type: 'flowable:FormProperty',
        id: 'reviewerId',
        variable: 'reviewerId',
        name: '复核人',
        type: 'string'
      }]
    }
  }
  const context = createDomainContext({ selectedBusinessObject: ref(userTask) })

  // 生产装配先完成表单领域，再向扩展领域注入已初始化的一行用户字段 facade。
  const formDomain = createFormParticipantDomain(context)
  const routingDomain = createRoutingCallActivityDomain(context)
  const extensionDomain = createExtensionEventSlaDomain({
    ...context,
    readTemplatePermissionPolicy: formDomain.readTemplatePermissionPolicy
  })
  assert.deepEqual(extensionDomain.resolveAutoCopyFormFieldOptionsForBusinessObject(userTask), [
    { value: 'reviewerId', label: '复核人（reviewerId）' }
  ])

  assert.deepEqual(Object.keys(context.formFieldCatalog), [
    'resolveTemplateForm',
    'readEmbeddedFormFields',
    'readTemplateFieldDescriptors',
    'resolveElementFieldDescriptors',
    'resolveUserIdFieldCatalog'
  ])
  assert.equal(typeof formDomain.resolveUserIdFieldCatalog, 'function')
  assert.equal(routingDomain.embeddedCallFieldType('long'), 'NUMBER')
  assert.equal(extensionDomain.formFieldOptions, context.formFieldOptions)
})

/**
 * 验证表单与参与者模块严格规范化多实例身份，并在保存前拒绝未完成选择。
 * @returns {void} 身份前缀、Java Long 上限或重复规则漂移时测试失败。
 */
test('表单与参与者领域独立校验指定多实例身份', () => {
  let assignmentChange
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
    updateProperties(changes) { assignmentChange = changes }
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

  propertyState.multiInstanceType = 'none'
  domain.updateAssignment()
  assert.deepEqual(assignmentChange, {
    'flowable:assignee': '12',
    'flowable:candidateUsers': undefined,
    'flowable:candidateGroups': undefined
  })
})

/**
 * 验证路由与调用活动模块独立执行变量目录、重复目标和类型兼容约束。
 * @returns {void} 非目录字段、重复目标或不兼容类型被放行时测试失败。
 */
test('路由与调用活动领域独立校验调用变量映射', () => {
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
  const sourceFields = [
    { name: 'amount', type: 'NUMBER', readable: true, writable: false },
    { name: 'approved', type: 'BOOLEAN', readable: true, writable: false }
  ]
  const targetFields = [
    { name: 'requestAmount', type: 'NUMBER', readable: true, writable: true },
    { name: 'comment', type: 'TEXT', readable: true, writable: true }
  ]

  assert.equal(domain.validateCallMappings(
    [{ source: 'amount', target: 'requestAmount' }], sourceFields, targetFields, '调用活动输入'), '')
  assert.match(domain.validateCallMappings(
    [{ source: 'approved', target: 'comment' }], sourceFields, targetFields, '调用活动输入'), /类型不兼容/)
  assert.match(domain.validateCallMappings([
    { source: 'amount', target: 'requestAmount' },
    { source: 'approved', target: 'requestAmount' }
  ], sourceFields, targetFields, '调用活动输入'), /目标不能重复/)
  assert.equal(domain.callVariableTypesCompatible('SCALAR', 'BOOLEAN'), true)
  assert.equal(domain.callVariableTypesCompatible('NUMBER', 'TEXT'), false)

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
})
