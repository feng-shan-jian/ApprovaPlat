import { computed, ref } from 'vue'

// 条件分支作者 XML 只保存版本化受控 JSON，服务端部署时生成固定表达式。
const CONDITION_RULE_PROPERTY = 'approva.conditionRule.config'

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
    describeFormalFormFields,
    designerLocked,
    emit,
    getModeler,
    listCallActivityOptions,
    listDmnDecisionOptions,
    loadPropertyState,
    propertyFlags,
    propertyState,
    props,
    readAllFlowableProperties,
    readEmbeddedFormFields,
    selectedBusinessObject,
    selectedElement,
    updateProperties
  } = context
  // 目录状态由本领域持有，主组件只把它们传给属性面板。
  const dmnOptions = ref([])
  const dmnLoading = ref(false)
  const callActivityOptions = ref([])
  const callActivityLoading = ref(false)
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
    const fieldsByName = new Map()
    const conflicts = new Set()
    const visited = new Set()
    for (const element of getModeler().get('elementRegistry').getAll()) {
      const businessObject = element?.businessObject
      if (!businessObject?.id || visited.has(businessObject.id) || owningProcess(businessObject) !== sourceProcess) continue
      if (!businessObject.$instanceOf?.('bpmn:StartEvent') && !businessObject.$instanceOf?.('bpmn:UserTask')) continue
      visited.add(businessObject.id)
      for (const field of describeFormalFormFields(businessObject)) {
        const signature = JSON.stringify({ type: field.type, values: field.values, valueRestricted: field.valueRestricted })
        const existing = fieldsByName.get(field.value)
        if (existing && existing.signature !== signature) {
          conflicts.add(field.value)
          fieldsByName.delete(field.value)
        } else if (!existing && !conflicts.has(field.value)) {
          fieldsByName.set(field.value, { ...field, signature })
        }
      }
    }
    return {
      fields: [...fieldsByName.values()].map(({ signature: _signature, ...field }) => field),
      conflicts: [...conflicts].sort()
    }
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
    let process = callActivity
    while (process && process.$type !== 'bpmn:Process') process = process.$parent
    if (!process) return []
    const fields = new Map()
    const visit = flowElements => {
      for (const element of Array.isArray(flowElements) ? flowElements : []) {
        if (['bpmn:StartEvent', 'bpmn:UserTask'].includes(element?.$type)) {
          const embedded = readEmbeddedFormFields(element).map(field => ({
            name: String(field.variable || '').trim(),
            label: field.name || field.variable,
            type: embeddedCallFieldType(field.type),
            required: field.required === true,
            readable: field.readable !== false,
            writable: field.writable !== false
          }))
          mergeCallVariableFields(fields, embedded)
          const formId = Number(String(element.get?.('flowable:formKey') || '').replace(/^key_/, ''))
          const form = props.forms.find(item => Number(item.formId) === formId)
          if (form?.content) mergeCallVariableFields(fields, extractTemplateCallFields(form.content))
        }
        visit(element?.flowElements)
      }
    }
    visit(process.flowElements)
    return [...fields.values()].sort((left, right) => left.name.localeCompare(right.name))
  }

  /**
   * 从正式表单 JSON 提取 CallActivity 允许映射的单值字段。
   * @param {string} content 已由页面从正式表单接口回读的 JSON。
   * @returns {Array<object>} 不包含集合、附件、表格和范围字段的字段目录。
   */
  function extractTemplateCallFields(content) {
    try {
      const root = JSON.parse(content)
      const result = []
      const visit = nodes => {
        for (const field of Array.isArray(nodes) ? nodes : []) {
          const config = field?.__config__ || {}
          const name = String(field?.__vModel__ || '').trim()
          const type = templateCallFieldType(config.tag, field)
          if (name && type) {
            result.push({
              name,
              label: config.label || name,
              type,
              required: config.required === true,
              readable: config.workflowReadable !== false,
              writable: config.workflowWritable !== false && field.disabled !== true
            })
          }
          visit(config.children)
        }
      }
      visit(root?.fields)
      return result
    } catch {
      return []
    }
  }

  /**
   * 将正式表单组件类型归一为服务端调用映射的四类标量。
   * @param {string} tag Element Plus 组件 tag。
   * @param {object} field 完整字段配置。
   * @returns {'TEXT'|'NUMBER'|'BOOLEAN'|'SCALAR'|null} 可映射类型或空。
   */
  function templateCallFieldType(tag, field) {
    if (['el-input', 'tinymce', 'el-color-picker'].includes(tag)) return 'TEXT'
    if (['el-date-picker', 'el-time-picker'].includes(tag)) {
      return String(field?.type || '').toLowerCase().includes('range') ? null : 'TEXT'
    }
    if (['el-input-number', 'el-rate'].includes(tag)) return 'NUMBER'
    if (tag === 'el-slider') return field?.range === true ? null : 'NUMBER'
    if (tag === 'el-switch') return 'BOOLEAN'
    if (tag === 'el-radio-group') return 'SCALAR'
    if (tag === 'el-select') return field?.multiple === true ? null : 'SCALAR'
    return null
  }

  /**
   * 将 Flowable 内嵌 FormData 类型归一为服务端调用映射类型。
   * @param {string} type 内嵌表单字段类型。
   * @returns {'TEXT'|'NUMBER'|'BOOLEAN'|'SCALAR'|null} 可映射类型或空。
   */
  function embeddedCallFieldType(type) {
    if (['long', 'integer'].includes(type)) return 'NUMBER'
    if (type === 'boolean') return 'BOOLEAN'
    if (type === 'enum') return 'SCALAR'
    if (['string', 'date'].includes(type)) return 'TEXT'
    return null
  }

  /**
   * 合并父流程字段并移除同名异构字段，避免页面提供必然被服务端拒绝的映射。
   * @param {Map<string, object>} target 当前字段目录。
   * @param {Array<object>} source 待合并字段。
   * @returns {void} 同名同型保留首次名称，同名异型从目录移除。
   */
  function mergeCallVariableFields(target, source) {
    for (const field of source) {
      if (!field?.name || !field.type) continue
      const previous = target.get(field.name)
      if (previous && previous.type !== field.type) {
        target.delete(field.name)
        continue
      }
      if (!previous) target.set(field.name, field)
    }
  }

  /**
   * 加载当前设计者有权引用的全部已发布子流程版本和正式变量字段。
   * @returns {Promise<void>} 失败时清空目录并把真实接口错误交给页面。
   */
  async function loadCallActivityOptions() {
    callActivityLoading.value = true
    try {
      const response = await listCallActivityOptions()
      callActivityOptions.value = Array.isArray(response?.data) ? response.data : []
      if (propertyFlags.value.callActivity) {
        propertyState.callDefinitionId = resolveCallDefinitionId(
          propertyState.calledElement,
          propertyState.callVersionPolicy === 'FIXED' ? 'id' : 'key'
        )
      }
    } catch (error) {
      callActivityOptions.value = []
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
    const nodeName = String(callActivity?.name || callActivity?.id || '调用活动')
    const reference = String(callActivity?.calledElement || '').trim()
    const referenceType = String(callActivity?.get?.('flowable:calledElementType') || 'key').trim()
    if (!reference || !['key', 'id'].includes(referenceType) || reference.includes('${') || reference.includes('#{')) {
      return `${nodeName}必须从已发布子流程目录选择目标和版本策略`
    }
    const target = referenceType === 'id'
      ? callActivityOptions.value.find(option => option.definitionId === reference)
      : callActivityOptions.value
        .filter(option => option.processKey === reference && option.status === 'ACTIVE')
        .sort((left, right) => Number(right.version) - Number(left.version))[0]
    if (!target) return `${nodeName}引用的子流程不存在或当前用户无权引用`
    if (target.status !== 'ACTIVE') return `${nodeName}引用的子流程已停用`

    // 当前选中节点可能保留尚未写入 XML 的半行草稿，保存前必须显式阻止丢失。
    if (selectedBusinessObject.value === callActivity) {
      const drafts = [...propertyState.callInMappings, ...propertyState.callOutMappings]
      if (drafts.some(mapping => !String(mapping?.source || '').trim() || !String(mapping?.target || '').trim())) {
        return `${nodeName}存在未完成的变量映射`
      }
    }
    const parentFields = resolveCallActivityParentFields(callActivity)
    const inputError = validateCallMappings(
      readCallMappings(callActivity, 'flowable:In'),
      parentFields,
      target.inputFields || [],
      `${nodeName}输入`
    )
    if (inputError) return inputError
    return validateCallMappings(
      readCallMappings(callActivity, 'flowable:Out'),
      target.outputFields || [],
      parentFields,
      `${nodeName}输出`
    )
  }

  /**
   * 校验一组调用变量映射的数量、目录权限、重复目标和标量类型兼容性。
   * @param {Array<{source:string,target:string}>} mappings Flowable 原生 in 或 out 映射。
   * @param {Array<object>} sourceFields 来源变量字段目录。
   * @param {Array<object>} targetFields 目标变量字段目录。
   * @param {string} direction 包含节点名称的输入或输出方向。
   * @returns {string} 空串表示通过，否则返回稳定业务错误。
   */
  function validateCallMappings(mappings, sourceFields, targetFields, direction) {
    if (mappings.length > 64) return `${direction}变量映射不能超过64项`
    const sources = new Map(sourceFields.map(field => [field.name, field]))
    const targets = new Map(targetFields.map(field => [field.name, field]))
    const usedTargets = new Set()
    for (const mapping of mappings) {
      const sourceName = String(mapping?.source || '').trim()
      const targetName = String(mapping?.target || '').trim()
      if (!sourceName || !targetName) return `${direction}存在未完成的变量映射`
      if (usedTargets.has(targetName)) return `${direction}变量目标不能重复`
      usedTargets.add(targetName)
      const source = sources.get(sourceName)
      const target = targets.get(targetName)
      if (!source?.readable || !target?.writable) return `${direction}变量不在可映射字段目录中`
      if (!callVariableTypesCompatible(source.type, target.type)) return `${direction}变量字段类型不兼容`
    }
    return ''
  }

  /**
   * 判断两个受控标量字段类型是否可安全映射。
   * @param {string} sourceType 来源字段的 TEXT、NUMBER、BOOLEAN 或 SCALAR 类型。
   * @param {string} targetType 目标字段的 TEXT、NUMBER、BOOLEAN 或 SCALAR 类型。
   * @returns {boolean} 同型或一端为受控 SCALAR 时返回 true。
   */
  function callVariableTypesCompatible(sourceType, targetType) {
    if (sourceType === targetType) return true
    const scalarTypes = new Set(['TEXT', 'NUMBER', 'BOOLEAN'])
    return (sourceType === 'SCALAR' && scalarTypes.has(targetType))
      || (targetType === 'SCALAR' && scalarTypes.has(sourceType))
  }

  return {
    dmnOptions,
    dmnLoading,
    callActivityOptions,
    callActivityLoading,
    conditionFieldOptions,
    conditionContext,
    callActivityParentFields,
    readConditionRule,
    isDefaultConditionFlow,
    resolveConditionFieldCatalog,
    resolveConditionContext,
    isConditionGatewayFlow,
    owningProcess,
    updateDmnDecision,
    loadDmnOptions,
    resolveCallActivityParentFields,
    extractTemplateCallFields,
    templateCallFieldType,
    embeddedCallFieldType,
    mergeCallVariableFields,
    loadCallActivityOptions,
    resolveCallDefinitionId,
    readCallMappings,
    updateCallActivityProperties,
    updateConditionRule,
    makeConditionDefault,
    persistConditionConfig,
    validateCallActivityConfiguration,
    validateCallMappings,
    callVariableTypesCompatible
  }
}
