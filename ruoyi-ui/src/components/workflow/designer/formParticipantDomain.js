import { computed } from 'vue'
import { participantUserIdFieldOptions } from './formUserFieldCatalog.js'
import { multiInstanceAuthorProperties } from './multiInstancePropertyContract.js'

// 受控多实例的技术表达式属于发布协议，设计器只能在结构化业务选项间切换。
const CONTROLLED_MULTI_INSTANCE_COLLECTION = '${multiInstanceHandler.getUserIds(execution)}'
const START_MULTI_INSTANCE_COLLECTION = '${multiInstanceHandler.getStartUserIds(execution)}'
const CONFIGURED_MULTI_INSTANCE_COLLECTION = '${multiInstanceHandler.getConfiguredUserIds(execution)}'
const FIXED_MULTI_INSTANCE_COLLECTION_PATTERN = /^\$\{multiInstanceHandler\.getFixedUserIds\(execution, '([1-9]\d*(?:,[1-9]\d*)*)'\)\}$/
const CONTROLLED_MULTI_INSTANCE_ASSIGNEE = '${assignee}'
const CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE = 'assignee'
const CONTROLLED_MULTI_INSTANCE_ALL_CONDITION = '${nrOfCompletedInstances == nrOfInstances}'
const CONTROLLED_MULTI_INSTANCE_ANY_CONDITION = '${nrOfCompletedInstances > 0}'
const MULTI_INSTANCE_IDENTITY_PROPERTIES = Object.freeze({
  type: 'approva.multiInstance.identityType',
  ids: 'approva.multiInstance.identityIds'
})
const MULTI_INSTANCE_IDENTITY_PROPERTY_NAMES = new Set(Object.values(MULTI_INSTANCE_IDENTITY_PROPERTIES))
const MULTI_INSTANCE_IDENTITY_TYPES = Object.freeze({ user: 'USER', role: 'ROLE', dept: 'DEPT' })
const MULTI_INSTANCE_IDENTITY_SOURCES = Object.freeze(
  Object.fromEntries(Object.entries(MULTI_INSTANCE_IDENTITY_TYPES).map(([source, type]) => [type, source]))
)
const MULTI_INSTANCE_IDENTITY_VALUE_PATTERNS = Object.freeze({
  user: /^[1-9]\d{0,18}$/,
  role: /^ROLE[1-9]\d{0,18}$/,
  dept: /^DEPT[1-9]\d{0,18}$/
})
const JAVA_LONG_MAX_TEXT = '9223372036854775807'
const CONTROLLED_LOOP_PROPERTY_PREFIX = 'approva.controlledLoop.'
const CONTROLLED_LOOP_PROPERTIES = Object.freeze({
  enabled: `${CONTROLLED_LOOP_PROPERTY_PREFIX}enabled`,
  decisionVariable: `${CONTROLLED_LOOP_PROPERTY_PREFIX}decisionVariable`,
  repeatValue: `${CONTROLLED_LOOP_PROPERTY_PREFIX}repeatValue`,
  exitValue: `${CONTROLLED_LOOP_PROPERTY_PREFIX}exitValue`,
  maxIterations: `${CONTROLLED_LOOP_PROPERTY_PREFIX}maxIterations`
})
const CONTROLLED_LOOP_PROPERTY_NAMES = new Set(Object.values(CONTROLLED_LOOP_PROPERTIES))
const PARTICIPANT_RULE_PROPERTIES = Object.freeze({
  startVersion: 'approva.startScope.ruleVersion',
  startType: 'approva.startScope.type',
  startTargetIds: 'approva.startScope.targetIds',
  startNoMatch: 'approva.startScope.noMatchPolicy',
  taskVersion: 'approva.assignment.ruleVersion',
  taskType: 'approva.assignment.type',
  taskTargetIds: 'approva.assignment.targetIds',
  taskFormField: 'approva.assignment.formField',
  taskNoMatch: 'approva.assignment.noMatchPolicy'
})
const PARTICIPANT_RULE_PROPERTY_NAMES = new Set(Object.values(PARTICIPANT_RULE_PROPERTIES))
const EMBEDDED_FORM_TYPES = Object.freeze(['string', 'long', 'integer', 'boolean', 'date', 'enum'])
const EMBEDDED_FORM_VARIABLE_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/
const EMBEDDED_FORM_DATE_PATTERN = /^[A-Za-z0-9 /:._-]{1,64}$/
const EMBEDDED_FORM_RESERVED_VARIABLES = new Set([
  'initiator', 'processStatus', 'processInstanceId', 'processDefinitionId',
  'deploymentId', 'startUserId', 'authenticatedUserId', 'businessKey',
  'assignee', 'nrOfInstances', 'nrOfActiveInstances', 'nrOfCompletedInstances',
  'loopCounter', '_FLOWABLE_SKIP_EXPRESSION_ENABLED'
])
const EMBEDDED_FORM_RESERVED_PREFIXES = Object.freeze([
  'wfMiUsers_', '_wfMiMembers_', '_wfMiRevision_', '_wfMiMode_', '__ruoyi_workflow_'
])
const FORM_PERMISSION_DEFAULT_ID = 'approva_permission_default'
const FORM_PERMISSION_FIELD_ID_PREFIX = 'approva_permission_field_'
const FORM_PERMISSION_MODES = new Set(['HIDDEN', 'READONLY', 'EDITABLE', 'REQUIRED'])
const IDENTITY_SEARCH_CONTRACTS = Object.freeze({
  assignees: Object.freeze({ type: 'user', capability: 'approval' }),
  candidateUsers: Object.freeze({ type: 'user', capability: 'claim' }),
  candidateGroups: Object.freeze({ type: 'group', capability: 'claim' }),
  candidateRoles: Object.freeze({ type: 'role', capability: 'claim' }),
  activeUsers: Object.freeze({ type: 'user', capability: '' }),
  activeRoles: Object.freeze({ type: 'role', capability: '' }),
  activeDepts: Object.freeze({ type: 'dept', capability: '' }),
  autoCopyUsers: Object.freeze({ type: 'user', capability: 'copy' }),
  autoCopyGroups: Object.freeze({ type: 'group', capability: 'copy' })
})

/**
 * 判断属性是否由表单、参与者或受控多实例模块独占维护。
 * @param {unknown} name Flowable Property 名称。
 * @returns {boolean} 属于本模块协议字段时返回 true。
 */
export function isFormParticipantProperty(name) {
  return PARTICIPANT_RULE_PROPERTY_NAMES.has(name)
    || MULTI_INSTANCE_IDENTITY_PROPERTY_NAMES.has(name)
    || CONTROLLED_LOOP_PROPERTY_NAMES.has(name)
}

/**
 * 创建表单与参与者设计器领域模块。
 * @param {object} context 主组件提供的命令栈、选择状态和正式目录依赖。
 * @returns {object} 表单、参与者、多实例的 BPMN 读写、校验和面板入口。
 */
export function createFormParticipantDomain(context) {
  const {
    buildPropertiesExtensionElements,
    designerLocked,
    emit,
    formFieldCatalog,
    formFieldOptions,
    getModeler,
    isProcess,
    isUserTask,
    loadPropertyState,
    persistExtensionProperties,
    propertyFlags,
    propertyState,
    readAllFlowableProperties,
    selectedBusinessObject,
    selectedElement,
    updateProperties
  } = context
  // 每个身份池独立防抖，不能让办理、认领和抄送资格请求相互覆盖。
  const identitySearchTimers = new Map()
  const assignmentOptions = [
    { label: '办理人', value: 'assignee' },
    { label: '用户', value: 'users' },
    { label: '角色/部门', value: 'groups' }
  ]
  const multiInstanceOptions = [
    { label: '无', value: 'none' },
    { label: '标准循环（仅往返）', value: 'standard' },
    { label: '整改循环（受控）', value: 'approvalLoop' },
    { label: '串行', value: 'sequential' },
    { label: '并行', value: 'parallel' },
    { label: '会签 / 或签', value: 'controlled' }
  ]
  const multiInstanceApprovalOptions = [
    { label: '会签', value: 'all' },
    { label: '或签', value: 'any' }
  ]
  const controlledLoopFieldOptions = computed(() => resolveControlledLoopFieldOptions())
  const participantFormFieldOptions = computed(() => resolveParticipantFormFieldOptions())

  /**
   * 按办理身份目标对远程检索进行独立防抖，避免资格不同的请求互相覆盖。
   * @param {'assignees'|'candidateUsers'|'candidateGroups'|'candidateRoles'|'activeUsers'|'activeRoles'|'activeDepts'|'autoCopyUsers'|'autoCopyGroups'} target 受控身份选项池。
   * @param {string} keyword 用户输入的名称关键字。
   * @returns {void} 到达防抖窗口后通过事件交由页面请求真实后端。
   */
  function scheduleIdentitySearch(target, keyword) {
    const previousTimer = identitySearchTimers.get(target)
    window.clearTimeout(previousTimer)
    const timer = window.setTimeout(() => {
      identitySearchTimers.delete(target)
      // directoryContract 是当前选项池不可降级的服务端资格契约。
      const directoryContract = IDENTITY_SEARCH_CONTRACTS[target]
      if (!directoryContract) return
      emit('identity-search', {
        target,
        ...directoryContract,
        keyword: String(keyword || '').trim()
      })
    }, 250)
    identitySearchTimers.set(target, timer)
  }

  /**
   * 接收属性面板的身份检索请求并转入受控资格目录。
   * @param {{target:string, keyword:string}|undefined} request 属性面板给出的目标池和检索词。
   * @returns {void} 未知目标会被拒绝，不向页面发出降级目录请求。
   */
  function handlePanelIdentitySearch(request) {
    const target = request?.target
    if (!IDENTITY_SEARCH_CONTRACTS[target]) return
    scheduleIdentitySearch(target, request?.keyword)
  }

  /**
   * 转发受控目录中未加载已选值的批量回显请求。
   * @param {{target:string,values:string[]}} request 目录池与作者 BPMN 已保存值。
   * @returns {void} 未知目录池或空值请求直接丢弃。
   */
  function handlePanelIdentityResolve(request) {
    const target = String(request?.target || '')
    const values = Array.isArray(request?.values)
      ? [...new Set(request.values.map(value => String(value || '').trim()).filter(Boolean))]
      : []
    if (!IDENTITY_SEARCH_CONTRACTS[target] || !values.length || values.length > 200) return
    emit('identity-resolve', {
      target,
      type: IDENTITY_SEARCH_CONTRACTS[target].type,
      capability: IDENTITY_SEARCH_CONTRACTS[target].capability,
      values
    })
  }

  /**
   * 从流程或单实例 UserTask 的平台属性回读受控参与者规则。
   * @param {object} businessObject 当前 BPMN 流程或元素业务对象。
   * @returns {{type:string,targetIds:string[],formField:string}} 可直接交给规则编辑器的值。
   */
  function readParticipantRule(businessObject) {
    const values = new Map(readAllFlowableProperties(businessObject)
      .filter(item => PARTICIPANT_RULE_PROPERTY_NAMES.has(item.name))
      .map(item => [item.name, item.value]))
    if (businessObject?.$instanceOf?.('bpmn:Process')) {
      const type = values.get(PARTICIPANT_RULE_PROPERTIES.startType) || 'PUBLIC'
      return {
        type,
        targetIds: decorateParticipantTargets(type,
          splitValues(values.get(PARTICIPANT_RULE_PROPERTIES.startTargetIds))),
        formField: ''
      }
    }
    const configuredType = values.get(PARTICIPANT_RULE_PROPERTIES.taskType) || ''
    if (configuredType) {
      return {
        type: configuredType,
        targetIds: decorateParticipantTargets(configuredType,
          splitValues(values.get(PARTICIPANT_RULE_PROPERTIES.taskTargetIds))),
        formField: values.get(PARTICIPANT_RULE_PROPERTIES.taskFormField) || ''
      }
    }
    // 旧模型的静态身份在第一次修改时平滑转换为受控规则，表达式继续由后端兼容链处理。
    const assignee = String(businessObject?.get?.('flowable:assignee') || '').trim()
    if (/^[1-9]\d{0,18}$/.test(assignee)) {
      return { type: 'FIXED_USER', targetIds: [assignee], formField: '' }
    }
    const candidateUsers = splitValues(businessObject?.get?.('flowable:candidateUsers'))
    if (candidateUsers.length && candidateUsers.every(value => /^[1-9]\d{0,18}$/.test(value))) {
      return { type: 'CANDIDATE_USERS', targetIds: candidateUsers, formField: '' }
    }
    const candidateGroups = splitValues(businessObject?.get?.('flowable:candidateGroups'))
    if (candidateGroups.length && candidateGroups.every(value => /^(?:ROLE|DEPT)[1-9]\d{0,18}$/.test(value))) {
      return { type: 'CANDIDATE_GROUPS', targetIds: candidateGroups, formField: '' }
    }
    return { type: '', targetIds: [], formField: '' }
  }

  /**
   * 把后端快照使用的数字目标还原为目录选项值，混合候选组已自带类型前缀。
   * @param {string} type 受控规则类型。
   * @param {string[]} values 作者属性中的目标值。
   * @returns {string[]} 可与正式目录选项精确匹配的值。
   */
  function decorateParticipantTargets(type, values) {
    if (type === 'ROLES' || type === 'STARTER_DEPT_ROLE') return values.map(value => `ROLE${value}`)
    if (type === 'DEPTS' || type === 'DEPT_MANAGER') return values.map(value => `DEPT${value}`)
    return values
  }

  /**
   * 从用户任务固定 Flowable 属性回读受控整改循环配置。
   * @param {object} businessObject 当前 BPMN 用户任务业务对象。
   * @returns {{decisionVariable:string,repeatValue:string,exitValue:string,maxIterations:number}|null} 完整配置；未启用时为空。
   */
  function readControlledLoop(businessObject) {
    const properties = (businessObject?.extensionElements?.values || [])
      .filter(value => value?.$type === 'flowable:Properties')
      .flatMap(container => container.values || [])
    const values = Object.fromEntries(properties
      .filter(property => CONTROLLED_LOOP_PROPERTY_NAMES.has(property.name))
      .map(property => [property.name, String(property.value ?? '')]))
    if (!Object.keys(values).length) return null
    if (values[CONTROLLED_LOOP_PROPERTIES.enabled] !== 'true') return null
    const maxIterations = Number(values[CONTROLLED_LOOP_PROPERTIES.maxIterations])
    return {
      decisionVariable: values[CONTROLLED_LOOP_PROPERTIES.decisionVariable] || '',
      repeatValue: values[CONTROLLED_LOOP_PROPERTIES.repeatValue] || '',
      exitValue: values[CONTROLLED_LOOP_PROPERTIES.exitValue] || '',
      maxIterations: Number.isInteger(maxIterations) ? maxIterations : 3
    }
  }

  /**
   * 将受控整改循环面板状态转换为固定 Flowable Property 集合。
   * @returns {Array<{name:string,value:string}>} 可原子写入命令栈的协议属性。
   */
  function controlledLoopPropertyItems() {
    return [
      { name: CONTROLLED_LOOP_PROPERTIES.enabled, value: 'true' },
      { name: CONTROLLED_LOOP_PROPERTIES.decisionVariable, value: propertyState.controlledLoopDecisionVariable.trim() },
      { name: CONTROLLED_LOOP_PROPERTIES.repeatValue, value: propertyState.controlledLoopRepeatValue.trim() },
      { name: CONTROLLED_LOOP_PROPERTIES.exitValue, value: propertyState.controlledLoopExitValue.trim() },
      { name: CONTROLLED_LOOP_PROPERTIES.maxIterations, value: String(propertyState.controlledLoopMaxIterations) }
    ]
  }

  /**
   * 从当前节点正式模板或内嵌 FormData 提取可作为循环判断条件的字段和值目录。
   * @returns {Array<{value:string,label:string,values:Array<{value:string,label:string}>,valueRestricted:boolean}>} 去重后的字段选项。
   */
  function resolveControlledLoopFieldOptions() {
    if (!isUserTask.value) return []
    return describeFormalFormFields(selectedBusinessObject.value)
  }

  /**
   * 从正式模板或 BPMN 内嵌 FormData 提取后端允许参与条件判断的可写标量字段。
   * @param {object|undefined} businessObject 开始节点或用户任务业务对象。
   * @returns {Array<{value:string,label:string,type:string,values:Array,valueRestricted:boolean}>} 字段目录。
   */
  function describeFormalFormFields(businessObject) {
    return describeControlledLoopFields(formFieldCatalog.resolveElementFieldDescriptors(businessObject))
  }

  /**
   * 解析已由后端模板校验器返回的正式表单 JSON，并收窄为条件字段目录。
   * @param {string} content 正式 wf_form 模板 JSON。
   * @returns {Array<{value:string,label:string,type:string,values:Array,valueRestricted:boolean}>} 字段目录。
   */
  function describeTemplateFormFields(content) {
    return describeControlledLoopFields(formFieldCatalog.readTemplateFieldDescriptors(content))
  }

  /**
   * 将中性字段描述投影为整改循环可选的可写标量目录。
   * @param {Array<{source:string,variable:string,field:object}>} descriptors 正式模板或内嵌字段描述。
   * @returns {Array<{value:string,label:string,type:string,values:Array,valueRestricted:boolean}>} 保持来源顺序的循环字段。
   */
  function describeControlledLoopFields(descriptors) {
    const result = []
    const seen = new Set()
    for (const { source, variable, field } of descriptors) {
      const embedded = source === 'EMBEDDED'
      const type = embedded
        ? ({ string: 'TEXT', date: 'TEXT', long: 'NUMBER', integer: 'NUMBER', boolean: 'BOOLEAN', enum: 'SCALAR' })[field.type]
        : resolveTemplateControlledLoopKind(field)
      if (!variable || !type || (embedded ? field.writable === false
        : field?.__config__?.workflowWritable === false || seen.has(variable))) continue
      if (!embedded) seen.add(variable)
      result.push({
        value: variable,
        label: (embedded ? field.name : field?.__config__?.label)
          ? `${embedded ? field.name : field.__config__.label}（${variable}）` : variable,
        type,
        values: type === 'BOOLEAN'
          ? [{ value: 'true', label: '是' }, { value: 'false', label: '否' }]
          : embedded
            ? (field.values || []).map(item => ({ value: String(item.id), label: item.name || item.id }))
            : (Array.isArray(field?.__slot__?.options) ? field.__slot__.options : []).map(item => ({
                value: String(item?.value ?? item?.label ?? ''),
                label: String(item?.label ?? item?.value ?? '')
              })).filter(item => item.value),
        valueRestricted: type === 'BOOLEAN' || (embedded ? field.type === 'enum' : field?.__config__?.workflowEnum === true)
      })
    }
    return result
  }

  /**
   * 从指定 BPMN 节点构建后端同口径的用户主键字段完整声明目录。
   * @param {object|undefined} businessObject 开始节点或用户任务业务对象。
   * @returns {Array<{value:string,label:string,eligible:boolean}>} 包含不合格声明的字段目录。
   */
  function resolveUserIdFieldCatalog(businessObject) {
    return formFieldCatalog.resolveUserIdFieldCatalog(businessObject, readTemplatePermissionPolicy(businessObject))
  }

  /**
   * 从当前 UserTask 正式表单提取可供 FORM_USER 规则读取的可见、可读单值变量。
   * @returns {Array<{value:string,label:string}>} 去重且保持表单顺序的正式字段目录。
   */
  function resolveParticipantFormFieldOptions() {
    if (!isUserTask.value) return []
    if (propertyState.formSource === 'EMBEDDED') {
      return participantUserIdFieldOptions(
        formFieldCatalog.resolveUserIdFieldCatalog(propertyState.embeddedFields))
    }
    const form = formFieldCatalog.resolveTemplateForm(propertyState.formKey)
    const permissionPolicy = {
      configured: propertyState.formPermissionFields.length > 0,
      defaultMode: propertyState.formPermissionDefault,
      permissions: new Map(propertyState.formPermissionFields
        .map(field => [field.variable, field.mode]))
    }
    return form?.content
      ? participantUserIdFieldOptions(formFieldCatalog.resolveUserIdFieldCatalog(propertyState.formKey, permissionPolicy))
      : []
  }

  /**
   * 将正式模板组件收窄为后端循环条件允许的可写标量种类。
   * @param {object} field 使用 __config__ 的正式模板字段。
   * @returns {'TEXT'|'NUMBER'|'BOOLEAN'|'SCALAR'|null} 标量种类；集合、附件、表格和范围字段返回空。
   */
  function resolveTemplateControlledLoopKind(field) {
    const tag = String(field?.__config__?.tag || '')
    if (['el-input', 'tinymce', 'el-color-picker'].includes(tag)) return 'TEXT'
    if (['el-input-number', 'el-rate'].includes(tag)) return 'NUMBER'
    if (tag === 'el-slider') return field?.range === true ? null : 'NUMBER'
    if (tag === 'el-switch') return 'BOOLEAN'
    if (tag === 'el-radio-group') return 'SCALAR'
    if (tag === 'el-select') return field?.multiple === true ? null : 'SCALAR'
    if (['el-time-picker', 'el-date-picker'].includes(tag)) {
      const temporalType = String(field?.type || '').toLowerCase()
      return field?.['is-range'] === true || temporalType.includes('range') ? null : 'TEXT'
    }
    return null
  }

  /**
   * 从当前 BPMN 元素的 extensionElements 回读 Flowable FormData。
   * @param {object} businessObject StartEvent 或 UserTask 的 moddle 业务对象。
   * @returns {Array<object>} 可供字段编辑器使用的确定性字段列表；正式模板权限描述不作为内嵌字段。
   */
  function readEmbeddedFormFields(businessObject) {
    return formFieldCatalog.readEmbeddedFormFields(businessObject)
  }

  /**
   * 判断循环配置是否引用平台受控多实例 handler；完整结构仍由保存门禁单独核验。
   * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
   * @returns {boolean} 集合表达式精确引用动态或固定人员 handler 时返回 true。
   */
  function isControlledMultiInstanceLoop(loop) {
    const collection = String(loop?.get?.('flowable:collection') || '').trim()
    return collection === CONTROLLED_MULTI_INSTANCE_COLLECTION
      || collection === START_MULTI_INSTANCE_COLLECTION
      || collection === CONFIGURED_MULTI_INSTANCE_COLLECTION
      || FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(collection)
  }

  /**
   * 从受控多实例完成条件解析会签或或签模式。
   * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
   * @returns {'all'|'any'} 仅固定任一完成条件返回 any，其余返回 all。
   */
  function controlledMultiInstanceApprovalMode(loop) {
    return String(loop?.completionCondition?.body || '').trim() === CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
      ? 'any'
      : 'all'
  }

  /**
   * 判断受控多实例是否由流程发起页面提供正式成员。
   * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
   * @returns {boolean} 集合表达式精确命中发起时 handler 时返回 true。
   */
  function isStartMultiInstanceLoop(loop) {
    return String(loop?.get?.('flowable:collection') || '').trim() === START_MULTI_INSTANCE_COLLECTION
  }

  /**
   * 判断受控多实例是否使用设计时指定用户、角色或部门来源。
   * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
   * @returns {boolean} 集合表达式精确命中指定身份 handler 时返回 true。
   */
  function isConfiguredMultiInstanceLoop(loop) {
    return String(loop?.get?.('flowable:collection') || '').trim() === CONFIGURED_MULTI_INSTANCE_COLLECTION
  }

  /**
   * 判断受控多实例是否使用设计时固定人员来源。
   * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
   * @returns {boolean} 集合表达式命中固定人员 handler 时返回 true。
   */
  function isFixedMultiInstanceLoop(loop) {
    return Boolean(loop && FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(
      String(loop.get?.('flowable:collection') || '').trim()
    ))
  }

  /**
   * 从固定人员 handler 表达式读取会签或或签成员，并保留设计时选择顺序。
   * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
   * @returns {string[]} 已去重的用户主键数组；表达式不符合受控契约时返回空数组。
   */
  function readFixedMultiInstanceUserIds(loop) {
    const collection = String(loop?.get?.('flowable:collection') || '').trim()
    const match = collection.match(FIXED_MULTI_INSTANCE_COLLECTION_PATTERN)
    return match ? splitValues(match[1]) : []
  }

  /**
   * 校验文本是否为 Java Long 范围内的规范正整数主键。
   * @param {unknown} value 待校验的用户、角色或部门数字主键。
   * @returns {boolean} 无前导零且不超过 Long.MAX_VALUE 时返回 true。
   */
  function isCanonicalJavaLongId(value) {
    const text = String(value ?? '')
    if (!/^[1-9]\d{0,18}$/.test(text)) return false
    return text.length < JAVA_LONG_MAX_TEXT.length || text <= JAVA_LONG_MAX_TEXT
  }

  /**
   * 将指定身份选择值严格转换为后端属性使用的数字主键。
   * @param {'user'|'role'|'dept'} source 指定用户、角色或部门来源。
   * @param {unknown[]} values 正式目录返回的选择值；角色和部门必须携带 ROLE/DEPT 前缀。
   * @returns {{source:string,type:string,ids:string[],selectionValues:string[]}|null} 合法且唯一的 1 至 100 项配置；非法时返回 null。
   */
  function normalizeConfiguredMultiInstanceIdentity(source, values) {
    const type = MULTI_INSTANCE_IDENTITY_TYPES[source]
    const pattern = MULTI_INSTANCE_IDENTITY_VALUE_PATTERNS[source]
    if (!type || !pattern || !Array.isArray(values) || values.length < 1 || values.length > 100) return null
    const ids = []
    const selectionValues = []
    const seenIds = new Set()
    const prefix = source === 'role' ? 'ROLE' : source === 'dept' ? 'DEPT' : ''
    for (const value of values) {
      const selectionValue = String(value ?? '')
      if (!pattern.test(selectionValue)) return null
      const id = prefix ? selectionValue.slice(prefix.length) : selectionValue
      if (!isCanonicalJavaLongId(id) || seenIds.has(id)) return null
      seenIds.add(id)
      ids.push(id)
      selectionValues.push(selectionValue)
    }
    return { source, type, ids, selectionValues }
  }

  /**
   * 从 UserTask 的平台保留属性严格回读指定身份配置。
   * @param {object|undefined} task bpmn-js 用户任务业务对象。
   * @returns {{source:string,type:string,ids:string[],selectionValues:string[]}|null} 完整合法配置；缺失、重复或格式非法时返回 null。
   */
  function readConfiguredMultiInstanceIdentity(task) {
    const properties = readAllFlowableProperties(task)
      .filter(item => MULTI_INSTANCE_IDENTITY_PROPERTY_NAMES.has(item.name))
    if (properties.length !== MULTI_INSTANCE_IDENTITY_PROPERTY_NAMES.size) return null
    const values = new Map()
    for (const property of properties) {
      if (values.has(property.name)) return null
      values.set(property.name, property.value)
    }
    const type = values.get(MULTI_INSTANCE_IDENTITY_PROPERTIES.type)
    const source = MULTI_INSTANCE_IDENTITY_SOURCES[type]
    const rawIds = String(values.get(MULTI_INSTANCE_IDENTITY_PROPERTIES.ids) ?? '').split(',')
    const prefix = source === 'role' ? 'ROLE' : source === 'dept' ? 'DEPT' : ''
    return source
      ? normalizeConfiguredMultiInstanceIdentity(source, rawIds.map(id => `${prefix}${id}`))
      : null
  }

  /**
   * 将已校验的指定身份配置转换为两个 Flowable 平台保留属性。
   * @param {{type:string,ids:string[]}} identity 用户、角色或部门的规范配置。
   * @returns {Array<{name:string,value:string}>} 可原子写入 BPMN extensionElements 的属性项。
   */
  function configuredMultiInstancePropertyItems(identity) {
    return [
      { name: MULTI_INSTANCE_IDENTITY_PROPERTIES.type, value: identity.type },
      { name: MULTI_INSTANCE_IDENTITY_PROPERTIES.ids, value: identity.ids.join(',') }
    ]
  }

  /**
   * 将 Flowable 逗号分隔身份列表转换为去重数组。
   * @param {unknown} value Flowable 属性值。
   * @returns {string[]} 去除空值后的身份数组。
   */
  function splitValues(value) {
    return [...new Set(String(value || '').split(',').map(item => item.trim()).filter(Boolean))]
  }

  /**
   * 规范化节点字段权限，未知值不会进入 BPMN 作者模型。
   * @param {unknown} mode 待校验权限值。
   * @returns {'HIDDEN'|'READONLY'|'EDITABLE'|'REQUIRED'} 四态权限之一。
   */
  function normalizeFormPermissionMode(mode) {
    const normalized = String(mode || '').trim().toUpperCase()
    return FORM_PERMISSION_MODES.has(normalized) ? normalized : 'EDITABLE'
  }

  /**
   * 从正式模板字段配置或 BPMN 权限描述恢复隐藏、只读、可编辑或必填权限。
   * @param {object} field 字段配置或内嵌字段。
   * @param {boolean} embedded 是否为 Flowable FormProperty 内嵌字段。
   * @returns {'HIDDEN'|'READONLY'|'EDITABLE'|'REQUIRED'} 当前字段权限。
   */
  function permissionModeFromField(field, embedded = false) {
    const config = embedded ? field : (field?.__config__ || {})
    const readable = embedded ? field?.readable !== false : config.workflowReadable !== false
    const writable = embedded
      ? field?.writable !== false
      : config.workflowWritable !== false && field?.disabled !== true
    const hidden = embedded
      ? !readable && !writable
      : config.workflowHidden === true || (!readable && !writable)
    if (hidden) return 'HIDDEN'
    if (!writable) return 'READONLY'
    return config.required === true || field?.required === true ? 'REQUIRED' : 'EDITABLE'
  }

  /**
   * 从当前绑定的正式模板提取唯一字段目录，内嵌 FormData 不进入节点权限策略。
   * @returns {Array<{variable:string,label:string,mode:string}>} 按正式表单顺序排列的字段目录。
   */
  function resolveFormPermissionSourceFields() {
    if (propertyState.formSource !== 'TEMPLATE') return []
    const form = formFieldCatalog.resolveTemplateForm(propertyState.formKey)
    if (!form?.content) return []
    const fields = []
    const seen = new Set()
    for (const { variable, field } of formFieldCatalog.readTemplateFieldDescriptors(form.content, true)) {
      if (!variable || seen.has(variable)) continue
      seen.add(variable)
      fields.push({
        variable,
        label: String(field?.__config__?.label || variable).trim(),
        mode: permissionModeFromField(field)
      })
    }
    return fields
  }

  /**
   * 从正式模板节点的受控 FormProperty 描述恢复批量默认策略和逐字段权限。
   * @param {object} businessObject StartEvent 或 UserTask 的 BPMN 业务对象。
   * @returns {{configured:boolean,defaultMode:string,permissions:Map<string,string>}} 权限作者状态。
   */
  function readTemplatePermissionPolicy(businessObject) {
    let configured = false
    let defaultMode = 'EDITABLE'
    const permissions = new Map()
    for (const property of businessObject?.extensionElements?.values || []) {
      if (property?.$type !== 'flowable:FormProperty') continue
      const id = String(property.id || '')
      if (id === FORM_PERMISSION_DEFAULT_ID) {
        configured = true
        defaultMode = permissionModeFromField({
          readable: property.readable,
          writable: property.writable,
          required: property.required
        }, true)
        continue
      }
      if (!id.startsWith(FORM_PERMISSION_FIELD_ID_PREFIX)) continue
      configured = true
      const variable = String(property.variable || '').trim()
      if (variable) {
        permissions.set(variable, permissionModeFromField({
          readable: property.readable,
          writable: property.writable,
          required: property.required
        }, true))
      }
    }
    return { configured, defaultMode, permissions }
  }

  /**
   * 依据当前正式表单重建属性面板字段目录，并按变量名保留仍然有效的设计权限。
   * @param {{configured:boolean,defaultMode:string,permissions:Map<string,string>}|undefined} policy 从 BPMN 回读的正式策略。
   * @returns {void} 无返回值。
   */
  function rebuildFormPermissionFields(policy) {
    const previous = new Map(propertyState.formPermissionFields.map(field => [field.variable, field.mode]))
    const defaultMode = normalizeFormPermissionMode(policy?.configured
      ? policy.defaultMode
      : propertyState.formPermissionDefault)
    propertyState.formPermissionDefault = defaultMode
    propertyState.formPermissionFields = resolveFormPermissionSourceFields().map(field => ({
      ...field,
      mode: normalizeFormPermissionMode(
        policy?.permissions?.get(field.variable)
        || (policy?.configured ? defaultMode : undefined)
        || previous.get(field.variable)
        || field.mode
        || defaultMode
      )
    }))
  }

  /**
   * 选择元素时从 BPMN 和正式表单共同恢复节点字段权限。
   * @param {object} businessObject 当前节点业务对象。
   * @returns {void} 无返回值。
   */
  function loadFormPermissionState(businessObject) {
    const policy = propertyState.formSource === 'TEMPLATE'
      ? readTemplatePermissionPolicy(businessObject)
      : undefined
    rebuildFormPermissionFields(policy)
  }

  /**
   * 切换正式模板或内嵌 FormData，并在一条命令中清理另一来源。
   * @returns {void} 来源非法或当前元素不支持表单时恢复 BPMN 原值。
   */
  function updateFormSource() {
    if (!['TEMPLATE', 'EMBEDDED'].includes(propertyState.formSource) || !propertyFlags.value.formSupported) {
      loadPropertyState(selectedElement.value)
      return
    }
    if (propertyState.formSource === 'EMBEDDED' && !propertyState.embeddedFields.length) {
      // 空 FormData 无法在 XML 中表达来源；切换时创建首个合法字段，后续可继续编辑或删除。
      propertyState.embeddedFields = [{
        id: 'field1', variable: '', name: '字段 1', type: 'string', required: false,
        readable: true, writable: true, datePattern: '', values: []
      }]
    }
    rebuildFormPermissionFields()
    syncFormDefinition()
  }

  /**
   * 更新开始节点或用户任务的正式表单键，并确保不存在内嵌字段。
   * @returns {void} 当前来源不是正式模板时不执行。
   */
  function updateFormKey() {
    if (propertyState.formSource !== 'TEMPLATE') return
    rebuildFormPermissionFields()
    syncFormDefinition()
  }

  /**
   * 接收字段编辑器的完整值，执行与后端一致的即时门禁后写入 BPMN。
   * @param {Array<object>} fields 用户编辑后的完整内嵌字段列表。
   * @returns {void} 校验失败时恢复当前 BPMN 值并触发 error。
   */
  function updateEmbeddedForm(fields) {
    try {
      validateEmbeddedFormFields(fields)
      propertyState.embeddedFields = fields.map(field => ({
        ...field,
        values: (field.values || []).map(value => ({ ...value }))
      }))
      propertyState.formSource = 'EMBEDDED'
      rebuildFormPermissionFields()
      syncFormDefinition()
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 校验并保存正式模板的完整节点字段权限。
   * @param {{defaultMode:string,fields:Array<object>}} policy 字段编辑器提交的完整策略。
   * @returns {void} 配置不完整时恢复 BPMN 真实状态并上报错误。
   */
  function updateFormPermissions(policy) {
    try {
      if (propertyState.formSource !== 'TEMPLATE' || !propertyState.formKey) {
        throw new Error('节点字段权限只能配置绑定的正式表单')
      }
      const sourceFields = resolveFormPermissionSourceFields()
      const requested = Array.isArray(policy?.fields) ? policy.fields : []
      const requestedByVariable = new Map(requested.map(field => [String(field?.variable || '').trim(), field]))
      if (requestedByVariable.size !== sourceFields.length
        || sourceFields.some(field => !requestedByVariable.has(field.variable))) {
        throw new Error('节点字段权限与当前正式表单不一致')
      }
      propertyState.formPermissionDefault = normalizeFormPermissionMode(policy.defaultMode)
      propertyState.formPermissionFields = sourceFields.map(field => ({
        ...field,
        mode: normalizeFormPermissionMode(requestedByVariable.get(field.variable)?.mode)
      }))
      syncFormDefinition()
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 将四态字段权限转换为 Flowable FormProperty readable/writeable/required 标志。
   * @param {string} mode 字段权限模式。
   * @returns {{readable:boolean,writable:boolean,required:boolean}} Flowable 原生权限标志。
   */
  function permissionFlags(mode) {
    const normalized = normalizeFormPermissionMode(mode)
    return {
      readable: normalized !== 'HIDDEN',
      writable: ['EDITABLE', 'REQUIRED'].includes(normalized),
      required: normalized === 'REQUIRED'
    }
  }

  /**
   * 校验内嵌字段数量、变量、类型、日期格式和静态枚举完整性。
   * @param {Array<object>} fields 待写入 BPMN 的内嵌字段列表。
   * @returns {void} 任一字段违反后端协议时抛出业务错误。
   */
  function validateEmbeddedFormFields(fields) {
    if (!Array.isArray(fields) || !fields.length || fields.length > 500) {
      throw new Error('内嵌表单必须包含 1 至 500 个字段')
    }
    const fieldIds = new Set()
    const variables = new Set()
    for (const field of fields) {
      const fieldId = String(field?.id || '')
      if (!EMBEDDED_FORM_VARIABLE_PATTERN.test(fieldId) || fieldIds.has(fieldId)) {
        throw new Error('内嵌表单字段标识非法或重复')
      }
      fieldIds.add(fieldId)
      const configuredVariable = String(field?.variable || '')
      const variable = configuredVariable || fieldId
      const reserved = EMBEDDED_FORM_RESERVED_VARIABLES.has(variable)
        || EMBEDDED_FORM_RESERVED_PREFIXES.some(prefix => variable.startsWith(prefix))
      if ((configuredVariable && configuredVariable !== configuredVariable.trim())
        || !EMBEDDED_FORM_VARIABLE_PATTERN.test(variable) || reserved || variables.has(variable)) {
        throw new Error('内嵌表单变量名非法、重复或属于保留变量')
      }
      variables.add(variable)
      if (!String(field.name || '').trim() || String(field.name).trim().length > 255) {
        throw new Error('内嵌表单字段名称不能为空且不能超过 255 个字符')
      }
      const customType = String(field.type || '').startsWith('custom:')
        && formFieldOptions.value.some(option => `custom:${option.extensionKey}` === field.type)
      if (!EMBEDDED_FORM_TYPES.includes(field.type) && !customType) {
        throw new Error(`内嵌表单字段类型不受支持: ${field.type || ''}`)
      }
      if (field.type === 'date' && !EMBEDDED_FORM_DATE_PATTERN.test(String(field.datePattern || '').trim())) {
        throw new Error('内嵌表单日期格式不合法')
      }
      if (field.type !== 'enum') continue
      const values = field.values || []
      if (!values.length || values.length > 500) throw new Error('内嵌枚举字段必须配置 1 至 500 个静态选项')
      const optionIds = new Set()
      for (const value of values) {
        const id = String(value?.id || '')
        const name = String(value?.name || '').trim()
        if (!id || id !== id.trim() || id.length > 255 || optionIds.has(id) || !name || name.length > 255) {
          throw new Error('内嵌枚举选项非法或重复')
        }
        optionIds.add(id)
      }
    }
  }

  /**
   * 把一个已校验字段转换为 Flowable FormProperty moddle 对象。
   * @param {object} field 字段编辑器中的确定性字段值。
   * @returns {object} 可放入 bpmn:ExtensionElements.values 的 FormProperty。
   */
  function createEmbeddedFormProperty(field) {
    const moddle = getModeler().get('moddle')
    const values = field.type === 'enum'
      ? field.values.map(value => moddle.create('flowable:Value', {
        id: value.id,
        name: value.name.trim()
      }))
      : []
    return moddle.create('flowable:FormProperty', {
      id: field.id,
      variable: field.variable?.trim() || undefined,
      name: field.name.trim(),
      type: field.type,
      required: Boolean(field.required && field.writable),
      readable: Boolean(field.readable),
      writable: Boolean(field.writable),
      datePattern: field.type === 'date' ? field.datePattern.trim() : undefined,
      values
    })
  }

  /**
   * 创建正式模板节点的批量默认和逐字段权限 FormProperty 描述。
   * @returns {object[]} 可随模型 XML 稳定往返并由后端部署编译器解析的权限描述。
   */
  function createTemplatePermissionProperties() {
    if (!propertyState.formKey || !propertyState.formPermissionFields.length) return []
    const moddle = getModeler().get('moddle')

    /**
     * 创建一条只承载权限、不会作为运行表单字段使用的 Flowable FormProperty。
     * @param {string} id 稳定权限描述主键。
     * @param {string|undefined} variable 正式模板字段变量；批量默认策略为空。
     * @param {string} label 字段显示名称。
     * @param {string} mode 四态权限模式。
     * @returns {object} Flowable FormProperty moddle 对象。
     */
    function createPermissionProperty(id, variable, label, mode) {
      return moddle.create('flowable:FormProperty', {
        id,
        variable,
        name: label,
        type: 'string',
        ...permissionFlags(mode)
      })
    }

    return [
      createPermissionProperty(
        FORM_PERMISSION_DEFAULT_ID,
        undefined,
        '批量默认字段权限',
        propertyState.formPermissionDefault
      ),
      ...propertyState.formPermissionFields.map((field, index) => createPermissionProperty(
        `${FORM_PERMISSION_FIELD_ID_PREFIX}${index + 1}`,
        field.variable,
        field.label || field.variable,
        field.mode
      ))
    ]
  }

  /**
   * 同步当前表单定义，保留审计监听器等非表单扩展并原子互斥两种来源。
   * @returns {void} 未选中可配置元素或设计器锁定时不执行。
   */
  function syncFormDefinition() {
    if (designerLocked.value || !getModeler() || !selectedElement.value || !propertyFlags.value.formSupported) return
    const businessObject = selectedBusinessObject.value
    const preservedValues = (businessObject.extensionElements?.values || [])
      .filter(value => value?.$type !== 'flowable:FormProperty')
    const formValues = propertyState.formSource === 'EMBEDDED'
      ? propertyState.embeddedFields.map(createEmbeddedFormProperty)
      : createTemplatePermissionProperties()
    const extensionValues = [...formValues, ...preservedValues]
    const extensionElements = extensionValues.length
      ? getModeler().get('moddle').create('bpmn:ExtensionElements', { values: extensionValues })
      : undefined
    getModeler().get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
      'flowable:formKey': propertyState.formSource === 'TEMPLATE'
        ? propertyState.formKey || undefined
        : undefined,
      extensionElements
    })
  }

  /**
   * 按办理方式互斥写入办理人、候选用户或候选组。
   * @returns {void} 无返回值。
   */
  function updateAssignment() {
    if (propertyState.multiInstanceType === 'controlled') return
    // 当前办理方式是尚未选择具体身份时唯一的编辑态，不能依赖 BPMN 空属性反推。
    const assignmentType = propertyState.assignmentType
    updateProperties({
      'flowable:assignee': assignmentType === 'assignee' ? propertyState.assignee || undefined : undefined,
      'flowable:candidateUsers': assignmentType === 'users' ? propertyState.candidateUsers.join(',') || undefined : undefined,
      'flowable:candidateGroups': assignmentType === 'groups' ? propertyState.candidateGroups.join(',') || undefined : undefined
    })
    // bpmn-js 会同步触发属性回读；候选值为空时需恢复用户显式选择的办理方式。
    propertyState.assignmentType = assignmentType
  }

  /**
   * 校验并把流程发起范围或单实例任务规则写入平台属性集合。
   * @param {{type:string,targetIds:string[],formField:string}} rule 规则编辑器提交的完整值。
   * @returns {void} 非法目录值会恢复 BPMN 当前状态；分步选择产生的暂存空值由保存门禁拦截。
   */
  function updateParticipantRule(rule) {
    if (designerLocked.value || !getModeler() || !selectedElement.value) return
    try {
      const processRule = isProcess.value
      if (!processRule && (!isUserTask.value || propertyState.multiInstanceType !== 'none')) return
      // 规则类型与目录目标是两个独立控件；切换类型时允许目标暂空，才能继续展示正式目录选择器。
      const normalized = normalizeParticipantRule(rule, processRule, true)
      propertyState.participantRule = normalized
      const preserved = readAllFlowableProperties(selectedBusinessObject.value)
        .filter(item => !PARTICIPANT_RULE_PROPERTY_NAMES.has(item.name))
      persistExtensionProperties([...preserved, ...participantRulePropertyItems(normalized, processRule)])
      if (!processRule) {
        // 受控规则是单一事实来源，清理旧静态身份以免 Flowable 在监听器解析前创建残留链接。
        updateProperties({
          'flowable:assignee': undefined,
          'flowable:candidateUsers': undefined,
          'flowable:candidateGroups': undefined
        })
      }
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 按后端参与者协议规范规则类型、目标数量、目录编码和表单字段。
   * @param {object} rule 编辑器提交值。
   * @param {boolean} processRule 是否为流程级发起范围。
   * @param {boolean} allowIncomplete 是否允许分步编辑期间暂缺目录目标或表单字段。
   * @returns {{type:string,targetIds:string[],formField:string}} 可持久化规则。
   */
  function normalizeParticipantRule(rule, processRule, allowIncomplete = false) {
    const type = String(rule?.type || '')
    const targetIds = [...new Set((Array.isArray(rule?.targetIds) ? rule.targetIds : [])
      .map(value => String(value || '').trim()).filter(Boolean))]
    const formField = String(rule?.formField || '').trim()
    const allowed = processRule
      ? new Set(['PUBLIC', 'USERS', 'ROLES', 'DEPTS'])
      : new Set(['FIXED_USER', 'CANDIDATE_USERS', 'CANDIDATE_GROUPS', 'STARTER',
          'STARTER_MANAGER', 'DEPT_MANAGER', 'STARTER_DEPT_ROLE', 'FORM_USER'])
    if (!allowed.has(type)) throw new Error(processRule ? '请选择流程发起范围' : '请选择单实例任务办理人规则')
    const requiresTargets = new Set(['USERS', 'ROLES', 'DEPTS', 'FIXED_USER',
      'CANDIDATE_USERS', 'CANDIDATE_GROUPS', 'DEPT_MANAGER', 'STARTER_DEPT_ROLE'])
    const singleTarget = new Set(['FIXED_USER', 'DEPT_MANAGER', 'STARTER_DEPT_ROLE'])
    if (requiresTargets.has(type) && (targetIds.length > 200 || (!allowIncomplete && !targetIds.length))) {
      throw new Error('参与者规则必须选择 1 至 200 个正式目录对象')
    }
    if (singleTarget.has(type) && (targetIds.length > 1 || (!allowIncomplete && targetIds.length !== 1))) {
      throw new Error('当前办理规则只能选择一个目录对象')
    }
    if (!requiresTargets.has(type) && targetIds.length) throw new Error('当前参与者规则不能携带目录目标')
    const numericTypes = new Set(['USERS', 'FIXED_USER', 'CANDIDATE_USERS'])
    const roleTypes = new Set(['ROLES', 'STARTER_DEPT_ROLE'])
    const deptTypes = new Set(['DEPTS', 'DEPT_MANAGER'])
    if (numericTypes.has(type) && targetIds.some(value => !/^[1-9]\d{0,18}$/.test(value))) {
      throw new Error('用户目录选项不合法')
    }
    if (roleTypes.has(type) && targetIds.some(value => !/^ROLE[1-9]\d{0,18}$/.test(value))) {
      throw new Error('角色目录选项不合法')
    }
    if (deptTypes.has(type) && targetIds.some(value => !/^DEPT[1-9]\d{0,18}$/.test(value))) {
      throw new Error('部门目录选项不合法')
    }
    if (type === 'CANDIDATE_GROUPS'
      && targetIds.some(value => !/^(?:ROLE|DEPT)[1-9]\d{0,18}$/.test(value))) {
      throw new Error('候选角色或部门目录选项不合法')
    }
    if (type === 'FORM_USER') {
      if ((!allowIncomplete || formField)
        && !participantFormFieldOptions.value.some(option => option.value === formField)) {
        throw new Error('表单用户字段必须来自当前任务正式表单')
      }
    } else if (formField) {
      throw new Error('非表单用户规则不能携带表单字段')
    }
    return { type, targetIds, formField }
  }

  /**
   * 将界面目录值转换为作者 BPMN 固定的五项受控属性。
   * @param {{type:string,targetIds:string[],formField:string}} rule 已校验规则。
   * @param {boolean} processRule 是否为流程级发起范围。
   * @returns {Array<{name:string,value:string}>} 后端可完整冻结的稳定属性集合。
   */
  function participantRulePropertyItems(rule, processRule) {
    const numericTargets = rule.targetIds.map(value => (
      ['ROLES', 'STARTER_DEPT_ROLE'].includes(rule.type)
        ? value.substring(4)
        : ['DEPTS', 'DEPT_MANAGER'].includes(rule.type) ? value.substring(4) : value
    ))
    if (processRule) {
      return [
        { name: PARTICIPANT_RULE_PROPERTIES.startVersion, value: '1' },
        { name: PARTICIPANT_RULE_PROPERTIES.startType, value: rule.type },
        { name: PARTICIPANT_RULE_PROPERTIES.startTargetIds, value: numericTargets.join(',') },
        { name: PARTICIPANT_RULE_PROPERTIES.startNoMatch, value: 'FAIL' }
      ]
    }
    return [
      { name: PARTICIPANT_RULE_PROPERTIES.taskVersion, value: '1' },
      { name: PARTICIPANT_RULE_PROPERTIES.taskType, value: rule.type },
      { name: PARTICIPANT_RULE_PROPERTIES.taskTargetIds, value: numericTargets.join(',') },
      { name: PARTICIPANT_RULE_PROPERTIES.taskFormField, value: rule.formField },
      { name: PARTICIPANT_RULE_PROPERTIES.taskNoMatch, value: 'FAIL' }
    ]
  }

  /**
   * 更新用户任务到期时间和优先级表达式。
   * @returns {void} 无返回值。
   */
  function updateUserTaskProperties() {
    updateProperties({
      'flowable:dueDate': propertyState.dueDate.trim() || undefined,
      'flowable:priority': propertyState.priority.trim() || undefined,
      'flowable:category': propertyState.taskCategory.trim() || undefined,
      'flowable:skipExpression': propertyState.skipExpression.trim() || undefined,
      'flowable:localScope': Boolean(propertyState.localScope)
    })
  }

  /**
   * 创建、更新或删除活动循环配置；受控模式一次写入完整的人员来源和签署技术契约。
   * @returns {void} 无返回值。
   */
  function updateMultiInstance() {
    const existingLoop = selectedBusinessObject.value?.loopCharacteristics
    const wasControlled = isControlledMultiInstanceLoop(existingLoop)
    const wasApprovalLoop = Boolean(readControlledLoop(selectedBusinessObject.value))
    // 当前 BPMN 是命令栈的权威状态；只替换本模块拥有的循环和参与者字段，其余扩展能力原样保留。
    const preservedPlatformProperties = readAllFlowableProperties(selectedBusinessObject.value)
      .filter(item => !CONTROLLED_LOOP_PROPERTY_NAMES.has(item.name)
        && !MULTI_INSTANCE_IDENTITY_PROPERTY_NAMES.has(item.name))
    const editableWithPlatformProperties = multiInstanceAuthorProperties(
      preservedPlatformProperties, PARTICIPANT_RULE_PROPERTY_NAMES)
    const participantProperties = readAllFlowableProperties(selectedBusinessObject.value)
      .filter(item => PARTICIPANT_RULE_PROPERTY_NAMES.has(item.name))
    const editableSingleInstanceProperties = [...editableWithPlatformProperties, ...participantProperties]
    const clearedControlledExtensions = wasApprovalLoop || wasControlled
      ? buildPropertiesExtensionElements(selectedBusinessObject.value, editableSingleInstanceProperties, [])
      : selectedBusinessObject.value?.extensionElements
    const plainMultiInstanceExtensions = buildPropertiesExtensionElements(
      selectedBusinessObject.value, editableWithPlatformProperties, [])
    if (propertyState.multiInstanceType === 'approvalLoop') {
      try {
        if (!isUserTask.value) throw new Error('整改循环只能配置在用户任务上')
        const maxIterations = Number(propertyState.controlledLoopMaxIterations)
        const field = controlledLoopFieldOptions.value.find(option => (
          option.value === propertyState.controlledLoopDecisionVariable
        ))
        const repeatValue = propertyState.controlledLoopRepeatValue.trim()
        const exitValue = propertyState.controlledLoopExitValue.trim()
        if (!Number.isInteger(maxIterations) || maxIterations < 2 || maxIterations > 50) {
          throw new Error('最大办理轮次必须是 2 至 50 的整数')
        }
        if (!field) throw new Error('循环判断字段必须来自当前节点正式表单')
        if (!repeatValue || !exitValue || repeatValue.length > 128 || exitValue.length > 128) {
          throw new Error('再次进入和退出条件值必须填写且不能超过 128 个字符')
        }
        if (repeatValue === exitValue) throw new Error('再次进入和退出条件不能相同')
        const extensionElements = buildPropertiesExtensionElements(
          selectedBusinessObject.value, editableWithPlatformProperties, controlledLoopPropertyItems())
        const changes = { loopCharacteristics: undefined, extensionElements }
        if (wasControlled) resetControlledAssignment(changes)
        updateProperties(changes)
        return
      } catch (error) {
        loadPropertyState(selectedElement.value)
        emit('error', error)
        return
      }
    }
    if (propertyState.multiInstanceType === 'none') {
      const changes = { loopCharacteristics: undefined, extensionElements: clearedControlledExtensions }
      if (wasControlled) resetControlledAssignment(changes)
      updateProperties(changes)
      return
    }
    const moddle = getModeler().get('moddle')
    if (propertyState.multiInstanceType === 'standard') {
      const maximumText = propertyState.loopMaximum.trim()
      const maximum = maximumText ? Number(maximumText) : undefined
      if (maximumText && (!Number.isInteger(maximum) || maximum < 1 || maximum > 10000)) {
        loadPropertyState(selectedElement.value)
        emit('error', new Error('最大循环次数必须是 1 至 10000 的整数'))
        return
      }
      const loopCondition = propertyState.loopCondition.trim()
      const standardLoop = moddle.create('bpmn:StandardLoopCharacteristics', {
        testBefore: Boolean(propertyState.testBefore),
        loopMaximum: maximum,
        loopCondition: loopCondition
          ? moddle.create('bpmn:FormalExpression', { body: loopCondition })
          : undefined
      })
      const changes = { loopCharacteristics: standardLoop, extensionElements: plainMultiInstanceExtensions }
      if (wasControlled) resetControlledAssignment(changes)
      updateProperties(changes)
      return
    }
    const controlled = propertyState.multiInstanceType === 'controlled'
    const configuredMemberSource = controlled
      && Object.hasOwn(MULTI_INSTANCE_IDENTITY_TYPES, propertyState.multiInstanceMemberSource)
    const leavingControlled = !controlled && wasControlled
    let collection = controlled
      ? propertyState.multiInstanceMemberSource === 'start'
        ? START_MULTI_INSTANCE_COLLECTION
        : configuredMemberSource
          ? CONFIGURED_MULTI_INSTANCE_COLLECTION
          : CONTROLLED_MULTI_INSTANCE_COLLECTION
      : propertyState.collection.trim()
    let elementVariable = controlled
      ? CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE
      : propertyState.elementVariable.trim()
    let condition = controlled
      ? propertyState.multiInstanceApprovalMode === 'any'
        ? CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
        : CONTROLLED_MULTI_INSTANCE_ALL_CONDITION
      : propertyState.completionCondition.trim()

    // 固定 handler 不能作为静态集合继续存在，否则属性回读会把刚切换的静态模式再次识别成动态模式。
    if (leavingControlled) {
      collection = ''
      elementVariable = ''
      condition = ''
      propertyState.collection = ''
      propertyState.elementVariable = ''
      propertyState.completionCondition = ''
    }

    let configuredIdentity = null
    if (controlled && !['dynamic', 'start'].includes(propertyState.multiInstanceMemberSource)
      && !configuredMemberSource) {
      loadPropertyState(selectedElement.value)
      emit('error', new Error('会签或或签人员来源不合法'))
      return
    }
    if (configuredMemberSource) {
      configuredIdentity = normalizeConfiguredMultiInstanceIdentity(
        propertyState.multiInstanceMemberSource,
        propertyState.configuredMultiInstanceIdentityIds
      )
      if (!configuredIdentity) {
        loadPropertyState(selectedElement.value)
        emit('error', new Error('指定会签或或签身份必须选择 1 至 100 个有效用户、角色或部门'))
        return
      }
      // 只把类型和正式主键写入作者 BPMN；角色或部门成员在每次进入节点时由后端按实时 RBAC 展开。
      propertyState.configuredMultiInstanceIdentityIds = configuredIdentity.selectionValues
    }

    const multiInstanceExtensions = buildPropertiesExtensionElements(
      selectedBusinessObject.value,
      editableWithPlatformProperties,
      configuredIdentity ? configuredMultiInstancePropertyItems(configuredIdentity) : [])

    // 已导入的静态多实例可能带有后端允许但面板未编辑的标准属性，原位更新可保持其往返完整性。
    if (!controlled && !wasControlled
      && existingLoop?.$type === 'bpmn:MultiInstanceLoopCharacteristics') {
      updateExistingStaticMultiInstance(
        existingLoop, collection, elementVariable, condition, multiInstanceExtensions)
      return
    }

    const loop = moddle.create('bpmn:MultiInstanceLoopCharacteristics', {
      isSequential: controlled ? false : propertyState.multiInstanceType === 'sequential',
      completionCondition: condition
        ? moddle.create('bpmn:FormalExpression', { body: condition })
        : undefined
    })
    loop.set('flowable:collection', collection || undefined)
    loop.set('flowable:elementVariable', elementVariable || undefined)
    // 任一多实例模式都必须移除单实例参与者属性，避免切回单实例时恢复未经确认的旧办理人。
    const changes = { loopCharacteristics: loop, extensionElements: multiInstanceExtensions }
    if (controlled) {
      propertyState.assignmentType = 'assignee'
      propertyState.assignee = CONTROLLED_MULTI_INSTANCE_ASSIGNEE
      propertyState.candidateUsers = []
      propertyState.candidateGroups = []
      propertyState.collection = collection
      propertyState.elementVariable = elementVariable
      propertyState.completionCondition = condition
      Object.assign(changes, {
        'flowable:assignee': CONTROLLED_MULTI_INSTANCE_ASSIGNEE,
        'flowable:candidateUsers': undefined,
        'flowable:candidateGroups': undefined
      })
    } else if (wasControlled) {
      resetControlledAssignment(changes)
    }
    updateProperties(changes)
  }

  /**
   * 原位更新已导入的静态多实例核心字段，保留 loopCardinality、索引变量及标准数据引用等未编辑属性。
   * @param {object} loop 当前用户任务已有的 bpmn:MultiInstanceLoopCharacteristics。
   * @param {string} collection 静态集合表达式或变量名。
   * @param {string} elementVariable 单个实例使用的元素变量名。
   * @param {string} condition 可选完成条件表达式。
   * @param {object|undefined} extensionElements 已清除单实例参与者规则的扩展元素。
   * @returns {void} 通过 bpmn-js 命令栈更新循环和扩展属性，可由撤销操作恢复。
   */
  function updateExistingStaticMultiInstance(
    loop, collection, elementVariable, condition, extensionElements
  ) {
    if (designerLocked.value || !getModeler() || !selectedElement.value) return
    const moddle = getModeler().get('moddle')
    // 已导入的静态多实例走原位更新分支，也必须同步清理隐藏的单实例规则。
    getModeler().get('modeling').updateProperties(selectedElement.value, { extensionElements })
    getModeler().get('modeling').updateModdleProperties(selectedElement.value, loop, {
      isSequential: propertyState.multiInstanceType === 'sequential',
      completionCondition: condition
        ? moddle.create('bpmn:FormalExpression', { body: condition })
        : undefined,
      'flowable:collection': collection || undefined,
      'flowable:elementVariable': elementVariable || undefined
    })
  }

  /**
   * 离开动态模式时清理仅对 handler 有意义的固定办理人，要求设计者重新选择静态身份。
   * @param {object} changes 本次 bpmn-js 属性更新映射。
   * @returns {void} 同步重置属性面板及待提交属性。
   */
  function resetControlledAssignment(changes) {
    propertyState.assignmentType = 'assignee'
    propertyState.assignee = ''
    propertyState.candidateUsers = []
    propertyState.candidateGroups = []
    Object.assign(changes, {
      'flowable:assignee': undefined,
      'flowable:candidateUsers': undefined,
      'flowable:candidateGroups': undefined
    })
  }

  /**
   * 对全部流程和单实例任务执行参与者规则的即时结构校验。
   * @param {object} businessObject BPMN Process 或 UserTask 业务对象。
   * @param {boolean} processRule 是否校验流程发起范围。
   * @returns {string} 空串表示通过，否则返回稳定错误提示。
   */
  function validateParticipantProperties(businessObject, processRule) {
    if (!processRule && businessObject?.loopCharacteristics) return ''
    const properties = new Map(readAllFlowableProperties(businessObject)
      .filter(item => PARTICIPANT_RULE_PROPERTY_NAMES.has(item.name))
      .map(item => [item.name, item.value]))
    const names = processRule
      ? [PARTICIPANT_RULE_PROPERTIES.startVersion, PARTICIPANT_RULE_PROPERTIES.startType,
          PARTICIPANT_RULE_PROPERTIES.startTargetIds, PARTICIPANT_RULE_PROPERTIES.startNoMatch]
      : [PARTICIPANT_RULE_PROPERTIES.taskVersion, PARTICIPANT_RULE_PROPERTIES.taskType,
          PARTICIPANT_RULE_PROPERTIES.taskTargetIds, PARTICIPANT_RULE_PROPERTIES.taskFormField,
          PARTICIPANT_RULE_PROPERTIES.taskNoMatch]
    if (!properties.size) {
      if (processRule) return ''
      const legacy = ['flowable:assignee', 'flowable:candidateUsers', 'flowable:candidateGroups']
        .some(name => String(businessObject?.get?.(name) || '').trim())
      return legacy ? '' : `用户任务 ${businessObject?.name || businessObject?.id || ''} 必须配置办理人规则`
    }
    if (names.some(name => !properties.has(name))) return processRule ? '流程发起范围配置不完整' : '用户任务办理人规则配置不完整'
    const version = properties.get(processRule
      ? PARTICIPANT_RULE_PROPERTIES.startVersion : PARTICIPANT_RULE_PROPERTIES.taskVersion)
    const policy = properties.get(processRule
      ? PARTICIPANT_RULE_PROPERTIES.startNoMatch : PARTICIPANT_RULE_PROPERTIES.taskNoMatch)
    if (version !== '1' || policy !== 'FAIL') return '参与者规则版本或无匹配策略不受支持'
    return ''
  }

  /**
   * 判断 BPMN 元素是否包含至少一个 Flowable 内嵌表单字段。
   * @param {object} businessObject StartEvent 或 UserTask 的 moddle 业务对象。
   * @returns {boolean} extensionElements 中存在 FormProperty 时返回 true。
   */
  function hasEmbeddedFormFields(businessObject) {
    return (businessObject?.extensionElements?.values || [])
      .some(value => value?.$type === 'flowable:FormProperty')
  }

  /**
   * 校验用户任务的受控 handler 只以固定并行会签/或签组合出现，并阻断近似方法名。
   * @param {object} task bpmn-js 用户任务业务对象。
   * @returns {string} 空串表示通过，否则返回稳定业务错误。
   */
  function validateUserTaskMultiInstance(task) {
    const configuredProperties = readAllFlowableProperties(task)
      .filter(item => MULTI_INSTANCE_IDENTITY_PROPERTY_NAMES.has(item.name))
    const loop = task.loopCharacteristics
    if (!loop) return configuredProperties.length ? '指定多实例身份属性缺少会签或或签循环' : ''
    const collection = String(loop.get?.('flowable:collection') || '').trim()
    const configuredCollection = collection === CONFIGURED_MULTI_INSTANCE_COLLECTION
    const controlledCollection = collection === CONTROLLED_MULTI_INSTANCE_COLLECTION
      || collection === START_MULTI_INSTANCE_COLLECTION
      || configuredCollection
      || FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(collection)
    if (!controlledCollection) {
      return configuredProperties.length ? '指定多实例身份属性与集合表达式不一致' : ''
    }
    const condition = String(loop.completionCondition?.body || '').trim()
    const approvedCondition = [
      CONTROLLED_MULTI_INSTANCE_ALL_CONDITION,
      CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
    ].includes(condition)
    const parentIsMainProcess = task.$parent?.$type === 'bpmn:Process'
    const hasBoundaryEvents = Array.isArray(task.boundaryEventRefs) && task.boundaryEventRefs.length > 0
    if (loop.isSequential
      || loop.get('flowable:elementVariable') !== CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE
      || task.get('flowable:assignee') !== CONTROLLED_MULTI_INSTANCE_ASSIGNEE
      || task.get('flowable:candidateUsers')
      || task.get('flowable:candidateGroups')
      || loop.loopCardinality
      || !approvedCondition
      || task.isForCompensation
      || hasBoundaryEvents
      || !parentIsMainProcess) {
      return '受控多实例配置不符合会签或或签契约'
    }
    if (FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(collection)) {
      const fixedUserIds = collection.match(FIXED_MULTI_INSTANCE_COLLECTION_PATTERN)?.[1]?.split(',') || []
      if (!fixedUserIds.length || fixedUserIds.length > 100
        || new Set(fixedUserIds).size !== fixedUserIds.length
        || fixedUserIds.some(userId => !isCanonicalJavaLongId(userId))) {
        return '固定会签或或签必须预设 1 至 100 名有效办理人'
      }
    }
    if (configuredCollection) {
      if (configuredProperties.length !== MULTI_INSTANCE_IDENTITY_PROPERTY_NAMES.size
        || !readConfiguredMultiInstanceIdentity(task)) {
        return '指定会签或或签身份配置不完整或不合法'
      }
    } else if (configuredProperties.length) {
      return '指定多实例身份属性与集合表达式不一致'
    }
    return ''
  }

  /**
   * 释放身份远程检索防抖计时器。
   * @returns {void} 组件卸载后不再向页面发出目录请求。
   */
  function disposeIdentitySearchTimers() {
    identitySearchTimers.forEach(timer => window.clearTimeout(timer))
    identitySearchTimers.clear()
  }

  /**
   * 校验当前选中受控多实例尚未写入命令栈的身份选择。
   * @returns {string} 空串表示可进入保存校验，否则返回稳定业务错误。
   */
  function validatePendingMultiInstanceSelection() {
    const configuredSource = Object.hasOwn(
      MULTI_INSTANCE_IDENTITY_TYPES, propertyState.multiInstanceMemberSource)
    if (isUserTask.value
      && propertyState.multiInstanceType === 'controlled'
      && configuredSource
      && !normalizeConfiguredMultiInstanceIdentity(
        propertyState.multiInstanceMemberSource,
        propertyState.configuredMultiInstanceIdentityIds)) {
      return '指定会签或或签身份必须选择 1 至 100 个有效用户、角色或部门'
    }
    return ''
  }

  return {
    assignmentOptions,
    multiInstanceOptions,
    multiInstanceApprovalOptions,
    controlledLoopFieldOptions,
    participantFormFieldOptions,
    scheduleIdentitySearch,
    handlePanelIdentitySearch,
    handlePanelIdentityResolve,
    readParticipantRule,
    decorateParticipantTargets,
    readControlledLoop,
    controlledLoopPropertyItems,
    resolveControlledLoopFieldOptions,
    describeFormalFormFields,
    describeTemplateFormFields,
    resolveUserIdFieldCatalog,
    resolveParticipantFormFieldOptions,
    resolveTemplateControlledLoopKind,
    readEmbeddedFormFields,
    isControlledMultiInstanceLoop,
    controlledMultiInstanceApprovalMode,
    isStartMultiInstanceLoop,
    isConfiguredMultiInstanceLoop,
    isFixedMultiInstanceLoop,
    readFixedMultiInstanceUserIds,
    isCanonicalJavaLongId,
    normalizeConfiguredMultiInstanceIdentity,
    readConfiguredMultiInstanceIdentity,
    configuredMultiInstancePropertyItems,
    splitValues,
    normalizeFormPermissionMode,
    permissionModeFromField,
    resolveFormPermissionSourceFields,
    readTemplatePermissionPolicy,
    rebuildFormPermissionFields,
    loadFormPermissionState,
    updateFormSource,
    updateFormKey,
    updateEmbeddedForm,
    updateFormPermissions,
    permissionFlags,
    validateEmbeddedFormFields,
    createEmbeddedFormProperty,
    createTemplatePermissionProperties,
    syncFormDefinition,
    updateAssignment,
    updateParticipantRule,
    normalizeParticipantRule,
    participantRulePropertyItems,
    updateUserTaskProperties,
    updateMultiInstance,
    updateExistingStaticMultiInstance,
    resetControlledAssignment,
    validateParticipantProperties,
    hasEmbeddedFormFields,
    validateUserTaskMultiInstance,
    disposeIdentitySearchTimers,
    validatePendingMultiInstanceSelection
  }
}
