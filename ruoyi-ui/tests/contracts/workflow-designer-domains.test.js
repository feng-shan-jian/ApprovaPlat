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

/**
 * 验证共享字段目录保持正式模板与内嵌 FormProperty 的既有字段顺序和投影结果。
 * @returns {void} 新目录或旧 facade 的字段类型、枚举值、标签和顺序漂移时测试失败。
 */
test('共享字段目录保持正式模板和内嵌表单字段投影', () => {
  const templateContent = JSON.stringify({
    fields: [
      {
        vModel: 'amount', tag: 'el-input-number', label: '申请金额', layout: 'colFormItem',
        workflowHidden: false, workflowReadable: true, workflowWritable: true
      },
      {
        vModel: 'decision', tag: 'el-radio-group', label: '审批结论',
        layout: 'colFormItem', workflowEnum: true,
        workflowHidden: false, workflowReadable: true, workflowWritable: true,
        options: [{ value: 'PASS', label: '通过' }, { value: 'REJECT', label: '驳回' }]
      },
      {
        layout: 'rowFormItem',
        workflowHidden: false, workflowReadable: true, workflowWritable: true,
        children: [{
          vModel: 'readonlyNote', tag: 'el-input', label: '只读说明',
          layout: 'colFormItem', workflowHidden: false,
          workflowReadable: true, workflowWritable: false
        }]
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
  const formParticipantDomain = createFormParticipantDomain(context)
  context.readTemplatePermissionPolicy = formParticipantDomain.readTemplatePermissionPolicy
  const controlledLoopDomain = createControlledLoopDomain({
    buildPropertiesExtensionElements: context.buildPropertiesExtensionElements,
    formFieldCatalog: context.formFieldCatalog,
    isUserTask: context.isUserTask,
    propertyState: context.propertyState,
    selectedBusinessObject: context.selectedBusinessObject
  })
  const participantRuleDomain = createParticipantRuleDomain({
    readParticipantFormFieldOptions: () => formParticipantDomain.participantFormFieldOptions.value,
    readAllFlowableProperties: context.readAllFlowableProperties
  })
  const templateBusinessObject = {
    get(name) { return name === 'flowable:formKey' ? 'key_7' : '' },
    extensionElements: {
      values: [
        {
          $type: 'flowable:FormProperty',
          id: 'approva_permission_default',
          readable: true,
          writable: true
        },
        {
          $type: 'flowable:FormProperty',
          id: 'approva_permission_field_1',
          variable: 'decision',
          readable: false,
          writable: false
        },
        {
          $type: 'flowable:FormProperty', id: 'approva_permission_field_2',
          variable: 'amount', readable: true, writable: true
        },
        {
          $type: 'flowable:FormProperty', id: 'approva_permission_field_3',
          variable: 'readonlyNote', readable: true, writable: false
        }
      ]
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
  assert.deepEqual(controlledLoopDomain.describeFormalFormFields(templateBusinessObject), expectedTemplate)
  assert.deepEqual(controlledLoopDomain.describeFormalFormFields(embeddedBusinessObject), expectedEmbedded)

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
  assert.deepEqual(formParticipantDomain.resolveFormPermissionSourceFields(), [
    { variable: 'amount', label: '申请金额', mode: 'EDITABLE' },
    { variable: 'decision', label: '审批结论', mode: 'EDITABLE' },
    { variable: 'readonlyNote', label: '只读说明', mode: 'READONLY' }
  ])
  assert.deepEqual(formParticipantDomain.readTemplatePermissionPolicy(templateBusinessObject), {
    defaultMode: 'EDITABLE',
    permissions: new Map([['decision', 'HIDDEN'], ['amount', 'EDITABLE'], ['readonlyNote', 'READONLY']])
  })
  assert.deepEqual(context.formFieldCatalog.resolveUserIdFieldCatalog(
    templateBusinessObject,
    formParticipantDomain.readTemplatePermissionPolicy(templateBusinessObject)
  ), [
    { value: 'amount', label: '申请金额（amount）', eligible: true, signature: 'el-input-number' },
    { value: 'decision', label: '审批结论（decision）', eligible: false, signature: '' },
    { value: 'readonlyNote', label: '只读说明（readonlyNote）', eligible: true, signature: 'el-input' }
  ])
  assert.deepEqual(context.formFieldCatalog.resolveUserIdFieldCatalog(
    embeddedBusinessObject,
    formParticipantDomain.readTemplatePermissionPolicy(embeddedBusinessObject)
  ), [
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
  const rolesRule = participantRuleDomain.normalizeParticipantRule(
    { type: 'ROLES', targetIds: ['ROLE12', 'ROLE35'], formField: '' }, true)
  const emptyProcess = { $instanceOf(type) { return type === 'bpmn:Process' } }
  assert.deepEqual(participantRuleDomain.readParticipantRule(emptyProcess),
    { type: '', targetIds: [], formField: '' })
  assert.deepEqual(participantRuleDomain.participantRulePropertyItems(rolesRule, true), [
    { name: 'approva.startScope.ruleVersion', value: '1' },
    { name: 'approva.startScope.type', value: 'ROLES' },
    { name: 'approva.startScope.targetIds', value: '12,35' },
    { name: 'approva.startScope.noMatchPolicy', value: 'FAIL' }
  ])
  const formUserRule = participantRuleDomain.normalizeParticipantRule(
    { type: 'FORM_USER', targetIds: [], formField: 'amount' }, false)
  const formUserProperties = participantRuleDomain.participantRulePropertyItems(formUserRule, false)
  assert.deepEqual(formUserProperties, [
    { name: 'approva.assignment.ruleVersion', value: '1' },
    { name: 'approva.assignment.type', value: 'FORM_USER' },
    { name: 'approva.assignment.targetIds', value: '' },
    { name: 'approva.assignment.formField', value: 'amount' },
    { name: 'approva.assignment.noMatchPolicy', value: 'FAIL' }
  ])
  assert.throws(() => participantRuleDomain.normalizeParticipantRule(
    { type: 'ROLES', targetIds: ['12'], formField: '' }, true),
    { message: '角色目录选项不合法' })
  assert.deepEqual(formParticipantDomain.readParticipantRule({
    get(name) { return name === 'flowable:assignee' ? '12' : '' }
  }), { type: '', targetIds: [], formField: '' })

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
  const resultMapping = [{ source: 'value', target: 'result' }]
  const resultSource = [{ name: 'value', type: 'NUMBER', readable: true }]
  const systemResult = [{ name: 'result', type: 'NUMBER', readable: true, writable: false, systemWritable: true }]
  assert.equal(routingFacade.validateCallMappings(resultMapping, resultSource, systemResult, 'C02调用模板子流程输出', true), '')
  assert.match(routingFacade.validateCallMappings(resultMapping, resultSource, systemResult, 'C02调用模板子流程输入'), /目标字段不可写/)
  assert.match(routingFacade.validateCallMappings(resultMapping, resultSource,
    [{ ...systemResult[0], systemWritable: false }], 'C02调用模板子流程输出', true), /目标字段不可写/)
  assert.match(routingFacade.validateCallMappings(resultMapping, resultSource,
    [{ ...systemResult[0], readable: false }], 'C02调用模板子流程输出', true), /目标字段不可写/)
  assert.deepEqual(routingFacade.resolveCallActivityParentFields(callActivity), [
    { name: 'approved', label: '是否通过', type: 'BOOLEAN', required: false, readable: true, writable: true, systemWritable: false },
    { name: 'decision', label: '审批结论', type: 'SCALAR', required: false, readable: false, writable: false, systemWritable: false },
    { name: 'readonlyNote', label: '只读说明', type: 'TEXT', required: false, readable: true, writable: false, systemWritable: false }
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

test('共享模板目录保留重名声明，用户字段资格拒绝类型冲突与损坏权限', () => {
  const form = { formId: 7, content: JSON.stringify({ fields: [
    { vModel: ' applicant ', tag: 'el-input', label: '申请人' },
    { children: [
      { vModel: 'reviewer', tag: 'el-input-number', label: '审核人' },
      { vModel: 'applicant', tag: 'el-select', multiple: true },
      { vModel: 'hiddenUser', tag: 'el-input' }
    ] }
  ] }) }
  const catalog = createDesignerFormFieldCatalog({ forms: [form] })
  const policy = { defaultMode: 'EDITABLE', permissions: new Map([['hiddenUser', 'HIDDEN']]) }

  assert.deepEqual(catalog.readTemplateFieldDescriptors(form.content).map(item => item.variable),
    ['applicant', 'reviewer', 'applicant', 'hiddenUser'])
  assert.deepEqual(catalog.resolveUserIdFieldCatalog('key_7', policy), [
    { value: 'applicant', label: '申请人（applicant）', eligible: false, signature: 'el-input' },
    { value: 'reviewer', label: '审核人（reviewer）', eligible: true, signature: 'el-input-number' },
    { value: 'hiddenUser', label: 'hiddenUser', eligible: false, signature: '' }
  ])
  assert.deepEqual(catalog.resolveUserIdFieldCatalog('key_7', { permissions: {} }), [])
  for (const content of ['{', 'null', '{}']) {
    form.content = content
    assert.deepEqual(catalog.readTemplateFieldDescriptors(content), [])
    assert.deepEqual(catalog.resolveUserIdFieldCatalog('key_7', policy), [])
  }
})

/**
 * 验证真实生成器字段在保存前递归补齐正式节点权限，同时保留明确的只读配置。
 * @returns {void} 缺失权限仍会被渲染为隐藏或显式权限被覆盖时测试失败。
 */
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

  assert.equal(assignmentChange, undefined)
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
  assert.deepEqual(propertyState.sla, createDefaultSlaConfig())
  assert.deepEqual(persistedProperties.slice(0, 2), [
    { name: 'approva.conditionRule.config', value: 'keep' },
    { name: 'approva.autoCopyRules', value: 'old' }
  ])
})
