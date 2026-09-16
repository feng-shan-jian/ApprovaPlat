import { computed, ref } from 'vue'

// 条件分支作者 XML 只保存版本化受控 JSON，服务端部署时生成固定表达式。
const CONDITION_RULE_PROPERTY = 'approva.conditionRule.config'

export function createRoutingCallActivityState() {
  return {
    dmnDecisionId: '',
    calledElement: '',
    callDefinitionId: '',
    callVersionPolicy: 'LATEST_ACTIVE',
    callBusinessKeyPolicy: 'INHERIT',
    callInheritVariables: false,
    callInMappings: [],
    callOutMappings: [],
    callOutputScope: 'PARENT',
    processInstanceName: '',
    conditionExpression: '',
    conditionRule: null,
    conditionDefault: false
  }
}

/**
 * 判断属性是否由路由与调用活动模块独占维护。
 * @param {unknown} name Flowable Property 名称。
 * @returns {boolean} 当前仅条件规则属性属于本模块协议。
 */
export function isRoutingCallActivityProperty(name) {
  return name === CONDITION_RULE_PROPERTY
}

/**
 * 创建路由与调用活动设计器领域模块。
 * @param {object} context 主组件提供的命令栈、选择状态、表单目录和错误出口。
 * @returns {object} 条件路由、DMN、CallActivity 的状态、BPMN 读写与校验入口。
 */
export function createRoutingCallActivityDomain(context) {
  const {
    buildPropertiesExtensionElements,
    designerLocked,
    emit,
    formFieldCatalog,
    getModeler,
    listCallActivityOptions,
    listDmnDecisionOptions,
    loadPropertyState,
    propertyFlags,
    propertyState,
    readAllFlowableProperties,
    selectedBusinessObject,
    selectedElement,
    updateProperties
  } = context
  // 目录状态由本领域持有，主组件只把它们传给属性面板。
  const dmnOptions = ref([])
  const dmnLoading = ref(false)
  const callActivityOptions = ref([])
  const callActivityLoading = ref(false)
  const callActivityLoadError = ref('')
  const conditionFieldCatalog = computed(() => resolveConditionFieldCatalog())
  const conditionFieldOptions = computed(() => conditionFieldCatalog.value.fields)
  const conditionContext = computed(() => resolveConditionContext(conditionFieldCatalog.value.conflicts, {
    id: propertyState.id,
    name: propertyState.name,
    conditionRule: propertyState.conditionRule,
    conditionDefault: propertyState.conditionDefault
  }))
  const callActivityParentFields = computed(() => resolveCallActivityParentFields())

  /**
   * 从 SequenceFlow 的平台保留属性回读版本化条件规则。
   * @param {object|undefined} businessObject 当前顺序流业务对象。
   * @returns {object|null} 结构完整的规则对象；缺失或历史异常值返回空并交由后端校验提示。
   */
  function readConditionRule(businessObject) {
    const values = (businessObject?.extensionElements?.values || [])
      .filter(value => value?.$type === 'flowable:Properties')
      .flatMap(container => container.values || [])
      .filter(property => property.name === CONDITION_RULE_PROPERTY)
    if (values.length !== 1) return null
    try {
      const config = JSON.parse(String(values[0].value || ''))
      return config?.version === 1 && typeof config.default === 'boolean' ? config : null
    } catch {
      return null
    }
  }

  /**
   * 以网关标准 default 引用判断当前顺序流是否为真实默认分支。
   * @param {object|undefined} element 当前 bpmn-js 连接元素。
   * @returns {boolean} 网关 default 精确指向当前连线时返回 true。
   */
  function isDefaultConditionFlow(element) {
    const source = element?.source?.businessObject
    const flow = element?.businessObject
    const defaultId = source?.default?.id || source?.default || ''
    return Boolean(flow?.id && defaultId === flow.id)
  }

  /**
   * 合并当前可执行流程全部正式表单字段，并识别同名异构冲突。
   * @returns {{fields:Array<object>,conflicts:string[]}} 可选字段和被剔除的冲突变量名。
   */
  function resolveConditionFieldCatalog() {
    // 在 modeler 门禁前读取响应式选中项，避免首次空画布求值后永久缓存空目录。
    const selected = selectedElement.value
    if (!getModeler() || !selected || !isConditionGatewayFlow()) return { fields: [], conflicts: [] }
    // 顺序流自身的 moddle 父链是导入、撤销和重建后的稳定归属，不依赖图形 source 短暂引用。
    const sourceProcess = owningProcess(selected.businessObject)
    if (!sourceProcess) return { fields: [], conflicts: [] }
    return { fields: formFieldCatalog.processFields(sourceProcess)?.conditionFields || [], conflicts: [] }
  }

  /**
   * 构造当前网关全部出线的配置状态，供属性面板提示默认遗漏和半成品分支。
   * @param {string[]} fieldConflicts 当前流程同名异构字段名。
   * @param {{id:string,name:string,conditionRule:object|null,conditionDefault:boolean}} currentState 当前出线刚写入的响应式状态。
   * @returns {{gatewayType:string,branches:Array<object>,fieldConflicts:string[]}} 网关上下文。
   */
  function resolveConditionContext(fieldConflicts = [], currentState = {}) {
    if (!isConditionGatewayFlow()) return { gatewayType: '', branches: [], fieldConflicts }
    const source = selectedElement.value.source.businessObject
    const defaultId = source.default?.id || source.default || ''
    const branches = (source.outgoing || []).map(flow => {
      const isCurrent = flow.id === currentState.id
      // bpmn-js 原位修改 moddle 对象，当前出线使用 propertyState 触发即时视图刷新。
      const config = isCurrent ? currentState.conditionRule : readConditionRule(flow)
      const isDefault = isCurrent ? Boolean(currentState.conditionDefault) : defaultId === flow.id
      return {
        id: flow.id,
        name: isCurrent ? currentState.name || '' : flow.name || '',
        default: isDefault,
        configured: Boolean(config && config.default === isDefault)
      }
    })
    return {
      gatewayType: source.$type === 'bpmn:InclusiveGateway' ? 'INCLUSIVE' : 'EXCLUSIVE',
      branches,
      fieldConflicts
    }
  }

  /**
   * 判断当前连线是否来自具有多条出线的排他或包容网关。
   * @returns {boolean} 满足无代码条件编辑适用范围时返回 true。
   */
  function isConditionGatewayFlow() {
    const source = selectedElement.value?.source?.businessObject
    return Boolean(source
      && ['bpmn:ExclusiveGateway', 'bpmn:InclusiveGateway'].includes(source.$type)
      && Array.isArray(source.outgoing)
      && source.outgoing.length > 1)
  }

  /**
   * 沿 BPMN moddle 父链解析所属可执行流程。
   * @param {object|undefined} businessObject 任意流程元素业务对象。
   * @returns {object|null} 所属 bpmn:Process；未找到时为空。
   */
  function owningProcess(businessObject) {
    let current = businessObject
    while (current) {
      if (current.$type === 'bpmn:Process') return current
      current = current.$parent
    }
    return null
  }

  /**
   * 将业务规则任务绑定到一个服务端目录中的精确 DMN 来源版本。
   * @returns {void} 非 BusinessRuleTask、未知 decisionId 或多值引用会恢复当前 BPMN 状态。
   */
  function updateDmnDecision() {
    if (!selectedBusinessObject.value?.$instanceOf?.('bpmn:BusinessRuleTask')) return
    const decisionId = String(propertyState.dmnDecisionId || '').trim()
    const selected = dmnOptions.value.find(option => option.decisionId === decisionId)
    if (!selected || decisionId.includes(',')) {
      loadPropertyState(selectedElement.value)
      emit('error', new Error('请选择一个正式 DMN 决策精确版本'))
      return
    }
    updateProperties({
      'flowable:rules': decisionId,
      'flowable:class': undefined,
      'flowable:ruleVariablesInput': undefined,
      'flowable:exclude': false
    })
  }

  /**
   * 从正式后端加载每个 DMN key 的最新来源版本供设计器选择。
   * @returns {Promise<void>} 失败时清空选项并向页面上报，不使用本地回退目录。
   */
  async function loadDmnOptions() {
    dmnLoading.value = true
    try {
      const response = await listDmnDecisionOptions()
      dmnOptions.value = Array.isArray(response?.data) ? response.data : []
    } catch (error) {
      dmnOptions.value = []
      emit('error', error)
    } finally {
      dmnLoading.value = false
    }
  }

  /**
   * 从当前 CallActivity 所属父流程的正式模板和内嵌表单提取可映射标量字段。
   * @returns {Array<{name:string,label:string,type:string,required:boolean,readable:boolean,writable:boolean}>} 按变量名排序的父流程字段目录。
   */
  function resolveCallActivityParentFields(callActivity = selectedBusinessObject.value) {
    if (!callActivity || callActivity.$type !== 'bpmn:CallActivity') return []
    return formFieldCatalog.processFields(callActivity)?.callFields || []
  }

  /**
   * 加载当前设计者有权引用的全部已发布子流程版本和正式变量字段。
   * @returns {Promise<void>} 失败时清空目录并把真实接口错误交给页面。
   */
  async function loadCallActivityOptions() {
    callActivityLoading.value = true
    callActivityLoadError.value = ''
    try {
      const response = await listCallActivityOptions()
      if (!Array.isArray(response?.data)) throw new Error('调用活动目录响应格式不正确')
      callActivityOptions.value = response.data
      if (propertyFlags.value.callActivity) {
        propertyState.callDefinitionId = resolveCallDefinitionId(
          propertyState.calledElement,
          propertyState.callVersionPolicy === 'FIXED' ? 'id' : 'key'
        )
      }
    } catch (error) {
      callActivityOptions.value = []
      callActivityLoadError.value = '子流程目录加载失败，请刷新重试'
      emit('error', error)
    } finally {
      callActivityLoading.value = false
    }
  }

  /**
   * 从服务端流程目录解析作者引用对应的精确定义选项。
   * @param {string} calledElement 作者 XML 中的流程 key 或定义 ID。
   * @param {'id'|'key'|string} calledElementType Flowable 调用绑定类型。
   * @returns {string} 设计器选中的定义 ID；目录尚未加载或目标已不可见时为空。
   */
  function resolveCallDefinitionId(calledElement, calledElementType) {
    const reference = String(calledElement || '').trim()
    if (!reference) return ''
    if (calledElementType === 'id') {
      return callActivityOptions.value.some(option => option.definitionId === reference) ? reference : ''
    }
    return callActivityOptions.value
      .filter(option => option.processKey === reference && option.status === 'ACTIVE')
      .sort((left, right) => Number(right.version) - Number(left.version))[0]?.definitionId || ''
  }

  /**
   * 从 CallActivity 的 extensionElements 回读 Flowable 原生 in/out 变量映射。
   * @param {object} businessObject 当前 CallActivity 业务对象。
   * @param {'flowable:In'|'flowable:Out'} mappingType 映射元素类型。
   * @returns {Array<{source:string,target:string}>} 保持 XML 顺序的结构化映射。
   */
  function readCallMappings(businessObject, mappingType) {
    return (businessObject?.extensionElements?.values || [])
      .filter(value => value?.$type === mappingType)
      .map(value => ({ source: String(value.source || ''), target: String(value.target || '') }))
  }

  /**
   * 更新调用活动的目录引用、版本策略、变量作用域和原生 in/out 映射。
   * @returns {void} 所有变更通过单个 bpmn-js 命令进入撤销、重做和 XML 保存链路。
   */
  function updateCallActivityProperties() {
    if (!getModeler() || !selectedElement.value || !propertyFlags.value.callActivity) return
    const selectedDefinition = callActivityOptions.value.find(option => (
      option.definitionId === propertyState.callDefinitionId
    ))
    const fixedVersion = propertyState.callVersionPolicy === 'FIXED'
    propertyState.calledElement = selectedDefinition
      ? (fixedVersion ? selectedDefinition.definitionId : selectedDefinition.processKey)
      : ''

    const businessObject = selectedBusinessObject.value
    const moddle = getModeler().get('moddle')
    // 只替换调用映射，监听器、通用属性和其他扩展必须原样保留。
    const preservedValues = (businessObject.extensionElements?.values || [])
      .filter(value => !['flowable:In', 'flowable:Out'].includes(value?.$type))
    const inputMappings = propertyState.callInMappings
      .filter(mapping => mapping.source && mapping.target)
      .map(mapping => moddle.create('flowable:In', {
        source: String(mapping.source).trim(),
        target: String(mapping.target).trim()
      }))
    const outputMappings = propertyState.callOutMappings
      .filter(mapping => mapping.source && mapping.target)
      .map(mapping => moddle.create('flowable:Out', {
        source: String(mapping.source).trim(),
        target: String(mapping.target).trim()
      }))
    const extensionValues = [...preservedValues, ...inputMappings, ...outputMappings]
    const extensionElements = extensionValues.length
      ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
      : undefined

    updateProperties({
      calledElement: propertyState.calledElement || undefined,
      'flowable:calledElementType': fixedVersion ? 'id' : 'key',
      'flowable:businessKey': undefined,
      'flowable:inheritBusinessKey': propertyState.callBusinessKeyPolicy === 'INHERIT',
      'flowable:inheritVariables': propertyState.callInheritVariables === true,
      'flowable:sameDeployment': false,
      'flowable:completeAsync': false,
      'flowable:useLocalScopeForOutParameters': propertyState.callOutputScope === 'LOCAL',
      'flowable:processInstanceName': propertyState.processInstanceName.trim() || undefined,
      extensionElements
    })
  }

  /**
   * 校验并把结构化条件规则写入当前网关出线，作者 XML 不生成任意 EL。
   * @param {{name:string,config:object}} payload 编辑器提交的分支名称与完整规则。
   * @returns {void} 配置与真实默认状态不一致时恢复当前 BPMN 状态并上报。
   */
  function updateConditionRule(payload) {
    if (designerLocked.value || !getModeler() || !isConditionGatewayFlow()) return
    try {
      const name = String(payload?.name || '').trim()
      const config = payload?.config
      if (!name || name.length > 100 || config?.version !== 1
        || config?.default !== propertyState.conditionDefault) {
        throw new Error('条件分支名称或默认状态不完整')
      }
      persistConditionConfig(selectedElement.value, name, config)
      loadPropertyState(selectedElement.value)
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 将当前出线设为网关唯一默认分支，并清除旧默认分支的过期默认配置。
   * @returns {void} 旧默认分支会进入“待配置”状态，保存 API 会阻止半成品发布。
   */
  function makeConditionDefault() {
    if (designerLocked.value || !getModeler() || !isConditionGatewayFlow()) return
    try {
      const sourceElement = selectedElement.value.source
      const source = sourceElement.businessObject
      const oldDefaultId = source.default?.id || source.default || ''
      const oldDefaultElement = oldDefaultId ? getModeler().get('elementRegistry').get(oldDefaultId) : null
      getModeler().get('modeling').updateProperties(sourceElement, { default: selectedBusinessObject.value })
      if (oldDefaultElement && oldDefaultElement !== selectedElement.value) {
        persistConditionConfig(oldDefaultElement, oldDefaultElement.businessObject.name || '', null)
      }
      persistConditionConfig(selectedElement.value, propertyState.name || '', { version: 1, default: true })
      loadPropertyState(selectedElement.value)
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 使用 bpmn-js 命令栈更新任意网关出线的名称、受控规则属性并移除历史表达式。
   * @param {object} flowElement bpmn-js SequenceFlow 图形元素。
   * @param {string} name 分支名称，允许旧默认切换时暂为空并由保存门禁阻止。
   * @param {object|null} config 版本化受控规则；null 表示清除过期配置。
   * @returns {void} 当前命令不会直接序列化 XML。
   */
  function persistConditionConfig(flowElement, name, config) {
    const businessObject = flowElement?.businessObject
    if (!businessObject) throw new Error('条件分支元素不存在')
    const ordinaryProperties = readAllFlowableProperties(businessObject)
      .filter(item => item.name !== CONDITION_RULE_PROPERTY)
    const controlledProperties = config
      ? [{ name: CONDITION_RULE_PROPERTY, value: JSON.stringify(config) }]
      : []
    const extensionElements = buildPropertiesExtensionElements(
      businessObject, ordinaryProperties, controlledProperties)
    getModeler().get('modeling').updateProperties(flowElement, {
      name: String(name || '').trim() || undefined,
      conditionExpression: undefined,
      extensionElements
    })
  }

  /**
   * 校验一个 CallActivity 的正式目录引用、版本策略和原生变量映射。
   * @param {object} callActivity 当前画布中的 bpmn:CallActivity 业务对象。
   * @returns {string} 空串表示通过，否则返回带节点名称的首个业务错误。
   */
  function validateCallActivityConfiguration(callActivity) {
    if (selectedBusinessObject.value !== callActivity) return ''
    const drafts = [...propertyState.callInMappings, ...propertyState.callOutMappings]
    return drafts.some(mapping => !String(mapping?.source || '').trim() || !String(mapping?.target || '').trim())
      ? `${callActivity.name || callActivity.id || ''}存在未完成的变量映射` : ''
  }

  function loadRoutingState(element, businessObject) {
    propertyState.conditionExpression = businessObject.conditionExpression?.body || ''
    propertyState.conditionRule = readConditionRule(businessObject)
    propertyState.conditionDefault = isDefaultConditionFlow(element)
  }

  function loadCallActivityState(businessObject) {
    propertyState.dmnDecisionId = businessObject.get('flowable:rules') || ''
    propertyState.calledElement = businessObject.calledElement || ''
    const calledElementType = businessObject.get('flowable:calledElementType') || 'key'
    propertyState.callVersionPolicy = calledElementType === 'id' ? 'FIXED' : 'LATEST_ACTIVE'
    propertyState.callDefinitionId = resolveCallDefinitionId(propertyState.calledElement, calledElementType)
    propertyState.callBusinessKeyPolicy = businessObject.get('flowable:inheritBusinessKey') === true ? 'INHERIT' : 'NONE'
    propertyState.callInheritVariables = businessObject.get('flowable:inheritVariables') === true
    propertyState.callOutputScope = businessObject.get('flowable:useLocalScopeForOutParameters') === true ? 'LOCAL' : 'PARENT'
    propertyState.callInMappings = readCallMappings(businessObject, 'flowable:In')
    propertyState.callOutMappings = readCallMappings(businessObject, 'flowable:Out')
    propertyState.processInstanceName = businessObject.get('flowable:processInstanceName') || ''
  }

  return {
    loadRoutingState,
    loadCallActivityState,
    dmnOptions,
    dmnLoading,
    callActivityOptions,
    callActivityLoading,
    callActivityLoadError,
    conditionFieldOptions,
    conditionContext,
    callActivityParentFields,
    resolveConditionFieldCatalog,
    isConditionGatewayFlow,
    updateDmnDecision,
    loadDmnOptions,
    resolveCallActivityParentFields,
    loadCallActivityOptions,
    updateCallActivityProperties,
    updateConditionRule,
    makeConditionDefault,
    persistConditionConfig,
    validateCallActivityConfiguration
  }
}
