import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
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
  return {
    buildPropertiesExtensionElements() {},
    describeFormalFormFields() { return [] },
    designerLocked: ref(false),
    emit() {},
    formFieldOptions: ref([]),
    getModeler() { return null },
    isForeignProtectedPropertyName() { return false },
    isProcess: ref(false),
    isType() { return false },
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
    propertyFlags: ref({ process: false, userTask: true, callActivity: false, businessRuleTask: false }),
    propertyState: {},
    props: { forms: [], identityOptions: {} },
    readAllFlowableProperties() { return [] },
    readEmbeddedFormFields() { return [] },
    readExtensionProperties() { return [] },
    resolveUserIdFieldCatalog() { return { fields: [], conflicts: [] } },
    selectedBusinessObject: ref(null),
    selectedElement: ref(null),
    updateProperties() {},
    ...overrides
  }
}

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
