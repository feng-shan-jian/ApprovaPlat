import { computed, ref } from 'vue'
import { createProcessUserIdFieldCatalog, eligibleUserIdFieldOptions } from './formUserFieldCatalog.js'
import { createDefaultSlaConfig, createUserTaskSlaDomain, isUserTaskSlaProperty } from './userTaskSlaDomain.js'
export { createDefaultSlaConfig } from './userTaskSlaDomain.js'

const BUSINESS_LISTENER_DELEGATE_EXPRESSION = '${workflowBusinessListener}'
const EXTENSION_DELEGATE_EXPRESSION = '${workflowExtensionDelegate}'
const EXTENSION_KEY_FIELD = 'approvaExtensionKey'
const EXTENSION_CONFIG_FIELD = 'approvaExtensionConfig'
const EXTENSION_PROPERTY_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/
const CEL_DEFAULT_CONFIG = Object.freeze({
  expression: 'true',
  resultVariable: 'celResult',
  resultType: 'BOOL',
  variables: []
})
const AUTO_COPY_PROPERTY_NAME = 'approva.autoCopyRules'
const AUTO_COPY_MAX_PROPERTY_LENGTH = 8192
const AUTO_COPY_MAX_RULES = 10
const AUTO_COPY_MAX_SOURCES = 20
const AUTO_COPY_MAX_VALUES = 100
const AUTO_COPY_RULE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/
const AUTO_COPY_USER_ID_PATTERN = /^[1-9]\d{0,18}$/
const AUTO_COPY_GROUP_ID_PATTERN = /^(?:ROLE|DEPT)[1-9]\d{0,18}$/
const AUTO_COPY_VARIABLE_PATTERN = /^[A-Za-z][A-Za-z0-9_.]{0,63}$/
/**
 * 判断属性是否由扩展事件或 SLA 模块独占维护。
 * @param {unknown} name Flowable Property 名称。
 * @returns {boolean} SLA 或自动抄送协议字段返回 true。
 */
export function isExtensionEventSlaProperty(name) {
  return isUserTaskSlaProperty(name) || name === AUTO_COPY_PROPERTY_NAME
}

/**
 * 创建扩展事件与 SLA 设计器领域模块。
 * @param {object} context 主组件提供的命令栈、选择状态、中性字段目录、权限读取和错误出口。
 * @returns {object} 扩展处理器、事件、自动抄送和 SLA 的状态、BPMN 读写与校验入口。
 */
export function createExtensionEventSlaDomain(context) {
  const {
    buildPropertiesExtensionElements,
    designerLocked,
    emit,
    formFieldCatalog,
    formFieldOptions,
    getModeler,
    isForeignProtectedPropertyName,
    isType,
    listBpmnEventCodeOptions,
    listCelExtensionOptions,
    listConnectorEndpointOptions,
    listEnabledSlaCalendars,
    listFormFieldExtensionOptions,
    listHttpExtensionOptions,
    listJavaExtensionOptions,
    listSqlDataSourceOptions,
    listSqlExtensionOptions,
    loadPropertyState,
    persistExtensionProperties,
    propertyFlags,
    propertyState,
    readAllFlowableProperties,
    readExtensionProperties,
    readTemplatePermissionPolicy,
    selectedBusinessObject,
    selectedElement,
    updateProperties
  } = context
  // 正式目录和加载状态归扩展领域持有，主组件只负责展示和生命周期触发。
  const extensionOptions = ref([])
  const businessListenerOptions = computed(() => (
    extensionOptions.value.filter(option => option.extensionType === 'JAVA')
  ))
  const connectorEndpoints = ref([])
  const sqlDataSources = ref([])
  const extensionLoading = ref(false)
  const errorEventOptions = ref([])
  const escalationEventOptions = ref([])
  const eventCodeLoading = ref(false)
  const slaCalendarOptions = ref([])
  const slaLoading = ref(false)
  const {
    readSlaConfig, normalizeAndValidateSlaConfig, isBoundedSlaMinute, slaConfigToProperties
  } = createUserTaskSlaDomain({
    readSlaCalendarOptions: () => slaCalendarOptions.value,
    readEscalationEventOptions: () => escalationEventOptions.value
  })
  const autoCopyTriggerOptions = computed(() => {
    if (propertyFlags.value.process) return [{ label: '流程完成', value: 'PROCESS_COMPLETED' }]
    if (isType('bpmn:UserTask')) {
      return [
        { label: '节点到达', value: 'NODE_ARRIVED' },
        { label: '节点完成', value: 'NODE_COMPLETED' }
      ]
    }
    return []
  })
  const autoCopyFormFieldOptions = computed(() => resolveAutoCopyFormFieldOptions())

  /**
   * 从 Flowable 属性中读取并校验自动抄送 JSON，供选中元素回显结构化规则。
   * @param {Array<{name:string,value:string}>} properties 当前 BPMN 元素的全部非循环扩展属性。
   * @param {boolean} strict 是否将非法历史配置作为异常抛出；普通回显采用失败关闭的空草稿。
   * @returns {Array<object>} 契约合法的自动抄送规则；未配置或非严格模式解析失败时返回空数组。
   */
  function readAutoCopyRules(properties, strict = false) {
    try {
      const matches = (Array.isArray(properties) ? properties : [])
        .filter(item => item.name === AUTO_COPY_PROPERTY_NAME)
      if (!matches.length) return []
      if (matches.length !== 1) throw new Error('同一元素不能重复配置自动抄送规则')
      const json = String(matches[0].value ?? '')
      if (!json || json.length > AUTO_COPY_MAX_PROPERTY_LENGTH) {
        throw new Error('自动抄送规则内容为空或超过长度限制')
      }
      const document = JSON.parse(json)
      if (!document || Array.isArray(document) || document.version !== 1 || !Array.isArray(document.rules)) {
        throw new Error('自动抄送规则版本不受支持')
      }
      return normalizeAndValidateAutoCopyRules(document.rules, {
        validatePlacement: false,
        validateFormFields: false
      })
    } catch (error) {
      if (strict) throw error
      return []
    }
  }

  /**
   * 汇总当前自动抄送元素允许引用的正式标量表单字段。
   * @returns {Array<{value:string,label:string}>} UserTask 返回自身字段，Process 返回其开始节点和全部用户任务字段并按变量去重。
   */
  function resolveAutoCopyFormFieldOptions() {
    return resolveAutoCopyFormFieldOptionsForBusinessObject(selectedBusinessObject.value)
  }

  /**
   * 解析指定流程或用户任务可供自动抄送引用的正式标量表单字段。
   * @param {object|undefined} businessObject BPMN Process 或 UserTask 业务对象。
   * @returns {Array<{value:string,label:string}>} 按变量名去重后的受控字段目录。
   */
  function resolveAutoCopyFormFieldOptionsForBusinessObject(businessObject) {
    // 自动抄送只消费中性目录；模板权限仍由表单领域按原协议解释。
    const resolveCatalog = element => formFieldCatalog.resolveUserIdFieldCatalog(
      element, readTemplatePermissionPolicy(element))
    if (businessObject?.$type === 'bpmn:UserTask') {
      return eligibleUserIdFieldOptions(resolveCatalog(businessObject))
        .filter(item => AUTO_COPY_VARIABLE_PATTERN.test(item.value))
    }
    if (businessObject?.$type !== 'bpmn:Process') return []
    return eligibleUserIdFieldOptions(createProcessUserIdFieldCatalog(
      businessObject, resolveCatalog))
      .filter(item => AUTO_COPY_VARIABLE_PATTERN.test(item.value))
  }

  /**
   * 从 ServiceTask 或 SendTask 的受控 Flowable Field 回读作者扩展键和配置。
   * @param {object} businessObject 当前 BPMN 业务对象。
   * @returns {{extensionKey: string, extensionConfig: string}} 稳定作者配置；缺失时使用空键和空对象。
   */
  function readControlledTaskExtension(businessObject) {
    const result = { extensionKey: '', extensionConfig: '{}' }
    for (const value of businessObject?.extensionElements?.values || []) {
      if (value?.$type !== 'flowable:Field') continue
      if (value.name === EXTENSION_KEY_FIELD) result.extensionKey = value.stringValue || ''
      if (value.name === EXTENSION_CONFIG_FIELD) result.extensionConfig = value.stringValue || '{}'
    }
    return result
  }

  /**
   * 从 moddle 监听器集合回读固定业务监听器，系统审计和未知实现不会进入可编辑状态。
   * @param {object} businessObject 当前 BPMN 元素业务对象。
   * @param {string} listenerType flowable:ExecutionListener 或 flowable:TaskListener。
   * @returns {Array<object>} 包含 event、extensionKey 和 config 的业务监听器数组。
   */
  function readBusinessListeners(businessObject, listenerType) {
    return (businessObject?.extensionElements?.values || [])
      .filter(listener => listener?.$type === listenerType)
      .filter(listener => listener?.delegateExpression === BUSINESS_LISTENER_DELEGATE_EXPRESSION)
      .map(listener => {
        const fields = Array.isArray(listener.fields) ? listener.fields : []
        const keyField = fields.find(field => field?.name === EXTENSION_KEY_FIELD)
        const configField = fields.find(field => field?.name === EXTENSION_CONFIG_FIELD)
        return {
          event: listener.event || '',
          extensionKey: keyField?.stringValue || '',
          config: configField?.stringValue || '{}'
        }
      })
  }

  /**
   * 将 ServiceTask 或 SendTask 的扩展键和 JSON 配置写入作者 XML，并固定为系统调度器。
   * @returns {void} 校验失败时恢复当前 BPMN 值并触发 error。
   */
  function updateControlledTask() {
    try {
      const extensionKey = propertyState.extensionKey.trim()
      const configText = propertyState.extensionConfig.trim() || '{}'
      const config = JSON.parse(configText)
      if (!config || Array.isArray(config) || typeof config !== 'object') {
        throw new Error('处理器配置必须是 JSON 对象')
      }
      const businessObject = selectedBusinessObject.value
      const preservedValues = (businessObject.extensionElements?.values || []).filter(value => (
        value?.$type !== 'flowable:Field'
        || ![EXTENSION_KEY_FIELD, EXTENSION_CONFIG_FIELD].includes(value.name)
      ))
      const moddle = getModeler().get('moddle')
      const extensionValues = extensionKey
        ? [
            moddle.create('flowable:Field', { name: EXTENSION_KEY_FIELD, stringValue: extensionKey }),
            moddle.create('flowable:Field', { name: EXTENSION_CONFIG_FIELD, stringValue: JSON.stringify(config) }),
            ...preservedValues
          ]
        : preservedValues
      const extensionElements = extensionValues.length
        ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
        : undefined
      getModeler().get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
        'flowable:class': undefined,
        'flowable:delegateExpression': extensionKey ? EXTENSION_DELEGATE_EXPRESSION : undefined,
        'flowable:expression': undefined,
        'flowable:resultVariable': undefined,
        extensionElements
      })
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 切换受控扩展类型时建立与服务端 Schema 一致的初始配置。
   * @param {string} extensionKey ServiceTask 或 SendTask 从正式目录选择的稳定扩展键。
   * @returns {void} 更新编辑状态并通过命令栈写入作者 BPMN。
   */
  function updateControlledTaskSelection(extensionKey) {
    propertyState.extensionKey = String(extensionKey || '')
    const selectedOption = extensionOptions.value.find(option => (
      option.extensionKey === propertyState.extensionKey
    ))
    if (selectedOption?.extensionType === 'CEL') {
      propertyState.extensionConfig = JSON.stringify(CEL_DEFAULT_CONFIG)
    } else if (selectedOption?.extensionType === 'HTTP') {
      const endpoint = connectorEndpoints.value[0]
      // HTTP 外部副作用必须交给 Flowable Job 重试；该技术约束由系统自动维护。
      propertyState.asyncBefore = true
      updateProperties({ 'flowable:async': true })
      propertyState.extensionConfig = JSON.stringify({
        endpointKey: endpoint?.endpointKey || '',
        method: String(endpoint?.allowedMethods || '').split(',').filter(Boolean)[0] || '',
        path: endpoint?.pathPrefix || '/'
      })
    } else if (selectedOption?.implementationKey === 'COLLABORATION_OUTBOX_V1') {
      const endpoint = connectorEndpoints.value[0]
      propertyState.extensionConfig = JSON.stringify({
        endpointKey: endpoint?.endpointKey || '',
        path: '/workflow/runtime-event/collaboration/message',
        messageName: '',
        targetProcessDefinitionKey: '',
        variableNames: [],
        maxAttempts: 5
      })
    } else if (selectedOption?.extensionType === 'SQL') {
      const source = sqlDataSources.value[0]
      // SQL 查询和写入统一交给 Flowable Job，异常才能按引擎配置重试并进入原生死信。
      propertyState.asyncBefore = true
      updateProperties({ 'flowable:async': true })
      propertyState.extensionConfig = JSON.stringify({
        dataSourceKey: source?.dataSourceKey || '',
        sql: '',
        parameters: {},
        maxRows: 100
      })
    } else if (selectedOption?.implementationKey === 'RAISE_BPMN_EVENT') {
      propertyState.extensionConfig = JSON.stringify({
        eventType: 'ERROR',
        eventCode: errorEventOptions.value[0]?.eventCode || '',
        sourceType: 'SERVICE_TASK',
        operator: 'ALWAYS'
      })
    } else {
      propertyState.extensionConfig = '{}'
    }
    updateControlledTask()
  }

  /**
   * 从正式后端加载可选择的 Java、CEL、HTTP 扩展和 HTTP 端点白名单。
   * @returns {Promise<void>} 请求完成后更新扩展选项；失败时不提供本地伪造回退。
   */
  async function loadExtensionOptions() {
    extensionLoading.value = true
    eventCodeLoading.value = true
    slaLoading.value = true
    try {
      const [javaResponse, celResponse, httpResponse, sqlResponse, formFieldResponse, endpointResponse, sqlSourceResponse, errorCodeResponse, escalationCodeResponse, calendarResponse] = await Promise.all([
        listJavaExtensionOptions(),
        listCelExtensionOptions(),
        listHttpExtensionOptions(),
        listSqlExtensionOptions(),
        listFormFieldExtensionOptions(),
        listConnectorEndpointOptions(),
        listSqlDataSourceOptions(),
        listBpmnEventCodeOptions('ERROR'),
        listBpmnEventCodeOptions('ESCALATION'),
        listEnabledSlaCalendars()
      ])
      extensionOptions.value = [
        ...(Array.isArray(javaResponse?.data) ? javaResponse.data : []),
        ...(Array.isArray(celResponse?.data) ? celResponse.data : []),
        ...(Array.isArray(httpResponse?.data) ? httpResponse.data : []),
        ...(Array.isArray(sqlResponse?.data) ? sqlResponse.data : [])
      ]
      formFieldOptions.value = Array.isArray(formFieldResponse?.data) ? formFieldResponse.data : []
      connectorEndpoints.value = Array.isArray(endpointResponse?.data) ? endpointResponse.data : []
      sqlDataSources.value = Array.isArray(sqlSourceResponse?.data) ? sqlSourceResponse.data : []
      errorEventOptions.value = Array.isArray(errorCodeResponse?.data) ? errorCodeResponse.data : []
      escalationEventOptions.value = Array.isArray(escalationCodeResponse?.data) ? escalationCodeResponse.data : []
      slaCalendarOptions.value = Array.isArray(calendarResponse?.data) ? calendarResponse.data : []
    } catch (error) {
      extensionOptions.value = []
      formFieldOptions.value = []
      connectorEndpoints.value = []
      sqlDataSources.value = []
      errorEventOptions.value = []
      escalationEventOptions.value = []
      slaCalendarOptions.value = []
      emit('error', error)
    } finally {
      extensionLoading.value = false
      eventCodeLoading.value = false
      slaLoading.value = false
    }
  }

  /**
   * 更新当前 FlowNode 的受控执行监听器。
   * @param {Array<object>} listeners 属性面板提交的事件、扩展键和 JSON 配置数组。
   * @returns {void} 配置合法时通过 moddle 命令栈写入，异常时恢复当前 BPMN 状态。
   */
  function updateBusinessExecutionListeners(listeners) {
    updateBusinessListeners('EXECUTION', listeners)
  }

  /**
   * 更新当前 UserTask 的受控任务监听器，同时保留系统身份审计监听器。
   * @param {Array<object>} listeners 属性面板提交的事件、扩展键和 JSON 配置数组。
   * @returns {void} 配置合法时通过 moddle 命令栈写入，异常时恢复当前 BPMN 状态。
   */
  function updateBusinessTaskListeners(listeners) {
    updateBusinessListeners('TASK', listeners)
  }

  /**
   * 校验并写入当前流程或元素的 Flowable 通用扩展属性。
   * @param {Array<{name:string,value:string}>} properties 属性编辑器提交的完整名值列表。
   * @returns {void} 非法名称、重复名称、超长值或超量输入会恢复当前 XML 并上报错误。
   */
  function updateExtensionProperties(properties) {
    if (designerLocked.value || !getModeler() || !selectedElement.value) return
    try {
      if (!Array.isArray(properties) || properties.length > 32) {
        throw new Error('单个元素最多允许 32 个扩展属性')
      }
      const normalized = properties.map(item => ({
        name: String(item?.name || '').trim(),
        value: String(item?.value || '')
      }))
      const names = new Set()
      for (const item of normalized) {
        if (!EXTENSION_PROPERTY_NAME_PATTERN.test(item.name) || item.name.startsWith('approva.')) {
          throw new Error('扩展属性名必须为合法非保留标识')
        }
        if (item.value.length > 1024) throw new Error('扩展属性值不能超过 1024 个字符')
        if (names.has(item.name)) throw new Error('同一元素的扩展属性名不能重复')
        names.add(item.name)
      }
      const businessObject = selectedBusinessObject.value
      const platformProperties = readAllFlowableProperties(businessObject)
        .filter(item => isExtensionEventSlaProperty(item.name)
          || isForeignProtectedPropertyName(item.name))
      const extensionElements = buildPropertiesExtensionElements(
        businessObject, normalized, platformProperties)
      getModeler().get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
        extensionElements
      })
      propertyState.extensionProperties = normalized
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 规范化并校验自动抄送规则的结构、元素触发范围和表单字段引用。
   * @param {Array<object>} rules 结构化编辑器或 BPMN JSON 提供的规则集合。
   * @param {object} options 校验开关及可选触发、字段白名单覆盖值。
   * @returns {Array<object>} 字段类型稳定、可直接序列化为后端契约的规则副本。
   */
  function normalizeAndValidateAutoCopyRules(rules, options = {}) {
    if (!Array.isArray(rules) || !rules.length || rules.length > AUTO_COPY_MAX_RULES) {
      throw new Error(`自动抄送规则数量必须为 1 至 ${AUTO_COPY_MAX_RULES}`)
    }
    const validatePlacement = options.validatePlacement !== false
    const validateFormFields = options.validateFormFields !== false
    const allowedTriggers = new Set(options.allowedTriggers || autoCopyTriggerOptions.value.map(item => item.value))
    const allowedFormFields = new Set(options.allowedFormFields || autoCopyFormFieldOptions.value.map(item => item.value))
    const ruleIds = new Set()
    return rules.map(rule => {
      const id = String(rule?.id || '')
      const trigger = String(rule?.trigger || '')
      if (!AUTO_COPY_RULE_ID_PATTERN.test(id) || ruleIds.has(id)) {
        throw new Error('自动抄送规则主键不合法或重复')
      }
      ruleIds.add(id)
      if (!['NODE_ARRIVED', 'NODE_COMPLETED', 'PROCESS_COMPLETED'].includes(trigger)
        || (validatePlacement && !allowedTriggers.has(trigger))) {
        throw new Error('自动抄送触发时机与当前元素不匹配')
      }
      if (!Array.isArray(rule?.recipients)
        || !rule.recipients.length
        || rule.recipients.length > AUTO_COPY_MAX_SOURCES) {
        throw new Error(`自动抄送接收人来源数量必须为 1 至 ${AUTO_COPY_MAX_SOURCES}`)
      }
      const sourceKeys = new Set()
      const recipients = rule.recipients.map(source => {
        const type = String(source?.type || '')
        if (!['USER', 'GROUP', 'INITIATOR', 'FORM_USER_FIELD'].includes(type)) {
          throw new Error('自动抄送接收人来源不受支持')
        }
        if (!Array.isArray(source?.values) || source.values.length > AUTO_COPY_MAX_VALUES) {
          throw new Error('自动抄送来源值必须为有界数组')
        }
        const values = source.values.map(value => String(value))
        const valuePattern = {
          USER: AUTO_COPY_USER_ID_PATTERN,
          GROUP: AUTO_COPY_GROUP_ID_PATTERN,
          FORM_USER_FIELD: AUTO_COPY_VARIABLE_PATTERN
        }[type]
        if (type === 'INITIATOR') {
          if (values.length) throw new Error('流程发起人来源不能携带额外值')
        } else if (!values.length
          || new Set(values).size !== values.length
          || values.some(value => !valuePattern.test(value))) {
          throw new Error('自动抄送来源值格式不合法、为空或重复')
        }
        if (type === 'FORM_USER_FIELD' && validateFormFields
          && values.some(value => !allowedFormFields.has(value))) {
          throw new Error('自动抄送表单用户字段必须来自当前元素可见的正式标量字段')
        }
        const sourceKey = `${type}:${values.join(',')}`
        if (sourceKeys.has(sourceKey)) throw new Error('同一自动抄送规则不能重复配置接收人来源')
        sourceKeys.add(sourceKey)
        return { type, values }
      })
      return { id, trigger, recipients }
    })
  }

  /**
   * 对指定 Process/UserTask 的已保存自动抄送属性执行完整本地部署前门禁。
   * @param {object} businessObject 当前待校验的 BPMN 业务对象。
   * @param {Array<string>} allowedTriggers 该元素生命周期允许的触发时机。
   * @returns {void} 未配置时直接返回，非法 JSON、位置或表单字段引用会抛出稳定错误。
   */
  function validateAutoCopyRulesForElement(businessObject, allowedTriggers) {
    const properties = readExtensionProperties(businessObject)
    if (!properties.some(item => item.name === AUTO_COPY_PROPERTY_NAME)) return
    const rules = readAutoCopyRules(properties, true)
    normalizeAndValidateAutoCopyRules(rules, {
      allowedTriggers,
      allowedFormFields: resolveAutoCopyFormFieldOptionsForBusinessObject(businessObject).map(item => item.value)
    })
  }

  /**
   * 原子创建、更新或删除当前 Process/UserTask 的自动抄送受控属性。
   * @param {Array<object>} rules 自动抄送编辑器显式应用的完整规则数组；空数组表示删除该属性。
   * @returns {void} 校验失败时回读 BPMN 原值并向页面上报，绝不写入部分规则。
   */
  function updateAutoCopyRules(rules) {
    if (designerLocked.value || !getModeler() || !selectedElement.value
      || (!propertyFlags.value.process && !isType('bpmn:UserTask'))) return
    try {
      const normalized = Array.isArray(rules) && rules.length
        ? normalizeAndValidateAutoCopyRules(rules)
        : []
      const document = normalized.length ? JSON.stringify({ version: 1, rules: normalized }) : ''
      if (document.length > AUTO_COPY_MAX_PROPERTY_LENGTH) {
        throw new Error(`自动抄送规则不能超过 ${AUTO_COPY_MAX_PROPERTY_LENGTH} 个字符`)
      }
      propertyState.autoCopyRules = normalized
      // 自动抄送只替换自身协议字段，其余平台与普通扩展属性以当前 BPMN 为权威原样保留。
      const platformProperties = readAllFlowableProperties(selectedBusinessObject.value)
        .filter(item => item.name !== AUTO_COPY_PROPERTY_NAME)
      persistExtensionProperties([
        ...platformProperties,
        ...(document ? [{ name: AUTO_COPY_PROPERTY_NAME, value: document }] : [])
      ])
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 校验并写入固定 Bean 业务监听器，禁止重复事件、未知目录和非对象 JSON 配置。
   * @param {'EXECUTION'|'TASK'} kind 监听器种类，决定 moddle 类型和目标属性。
   * @param {Array<object>} listeners 待写入的业务监听器编辑值。
   * @returns {void} 无返回值；失败时向页面上报稳定错误并回读原状态。
   */
  function updateBusinessListeners(kind, listeners) {
    if (designerLocked.value || !getModeler() || !selectedElement.value) return
    try {
      const allowedEvents = kind === 'TASK'
        ? new Set(['create', 'assignment', 'complete', 'delete'])
        : new Set(['start', 'end', 'take'])
      const seenEvents = new Set()
      const moddle = getModeler().get('moddle')
      const generated = (Array.isArray(listeners) ? listeners : []).map(listener => {
        const event = String(listener?.event || '').trim()
        const extensionKey = String(listener?.extensionKey || '').trim()
        if (!allowedEvents.has(event) || !seenEvents.add(event)) {
          throw new Error('同一元素的业务监听器事件必须合法且唯一')
        }
        const option = businessListenerOptions.value.find(item => item.extensionKey === extensionKey)
        if (!option) throw new Error('请选择已启用的 Java 业务监听处理器')
        const config = JSON.parse(String(listener?.config || '{}'))
        if (!config || Array.isArray(config) || typeof config !== 'object') {
          throw new Error('业务监听器配置必须是 JSON 对象')
        }
        return moddle.create(kind === 'TASK' ? 'flowable:TaskListener' : 'flowable:ExecutionListener', {
          event,
          delegateExpression: BUSINESS_LISTENER_DELEGATE_EXPRESSION,
          fields: [
            moddle.create('flowable:Field', { name: EXTENSION_KEY_FIELD, stringValue: extensionKey }),
            moddle.create('flowable:Field', { name: EXTENSION_CONFIG_FIELD, stringValue: JSON.stringify(config) })
          ]
        })
      })
      const businessObject = selectedBusinessObject.value
      const listenerType = kind === 'TASK' ? 'flowable:TaskListener' : 'flowable:ExecutionListener'
      const preserved = (businessObject.extensionElements?.values || []).filter(value => (
        value?.$type !== listenerType
        || value?.delegateExpression !== BUSINESS_LISTENER_DELEGATE_EXPRESSION
      ))
      const extensionValues = [...preserved, ...generated]
      const extensionElements = extensionValues.length
        ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
        : undefined
      getModeler().get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
        extensionElements
      })
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 返回引用型事件的根元素类型、引用属性和业务键字段。
   * @param {string} eventDefinitionType BPMN 事件定义类型。
   * @returns {object|undefined} 引用配置；非引用型事件返回 undefined。
   */
  function eventReferenceConfig(eventDefinitionType) {
    return {
      'bpmn:MessageEventDefinition': {
        rootType: 'bpmn:Message', referenceProperty: 'messageRef', keyProperty: 'name', idPrefix: 'Message'
      },
      'bpmn:SignalEventDefinition': {
        rootType: 'bpmn:Signal', referenceProperty: 'signalRef', keyProperty: 'name', idPrefix: 'Signal'
      },
      'bpmn:ErrorEventDefinition': {
        rootType: 'bpmn:Error', referenceProperty: 'errorRef', keyProperty: 'errorCode', idPrefix: 'Error'
      },
      'bpmn:EscalationEventDefinition': {
        rootType: 'bpmn:Escalation', referenceProperty: 'escalationRef', keyProperty: 'escalationCode', idPrefix: 'Escalation'
      }
    }[eventDefinitionType]
  }

  /**
   * 沿 moddle 父级查找 Definitions，供事件引用登记为 BPMN 根元素。
   * @param {object|undefined} businessObject 当前事件业务对象。
   * @returns {object|undefined} BPMN Definitions；找不到时返回 undefined。
   */
  function findDefinitions(businessObject) {
    let current = businessObject
    while (current && current.$type !== 'bpmn:Definitions') current = current.$parent
    return current
  }

  /**
   * 将业务键转换为稳定且合法的 BPMN 根元素标识片段。
   * @param {string} value 消息、信号、错误或升级的业务键。
   * @returns {string} 可用于 BPMN id 的非空片段。
   */
  function eventReferenceIdPart(value) {
    const normalized = value.replace(/[^A-Za-z0-9_.-]/g, '_').replace(/^[^A-Za-z_]+/, '')
    return normalized || 'Reference'
  }

  /**
   * 查找或创建消息、信号、错误、升级根元素，并通过命令栈登记到 Definitions。
   * @param {object} eventDefinition 当前事件定义。
   * @param {object} config 引用类型配置。
   * @param {string} key 用户维护的稳定业务键。
   * @returns {object|undefined} 可写入事件定义的根元素；空键返回 undefined。
   */
  function resolveEventRootReference(eventDefinition, config, key) {
    if (!key) return undefined
    const definitions = findDefinitions(eventDefinition)
    if (!definitions) return undefined
    const roots = Array.isArray(definitions.rootElements) ? definitions.rootElements : []
    const existing = roots.find(root => root.$type === config.rootType
      && (root[config.keyProperty] === key || root.name === key || root.id === key))
    if (existing) return existing
    const reference = getModeler().get('moddle').create(config.rootType, {
      id: `${config.idPrefix}_${eventReferenceIdPart(key)}`,
      [config.keyProperty]: key,
      ...(config.keyProperty === 'name' ? {} : { name: key })
    })
    getModeler().get('modeling').updateModdleProperties(selectedElement.value, definitions, {
      rootElements: [...roots, reference]
    })
    return reference
  }

  /**
   * 更新当前事件定义的根引用、定时表达式和边界中断语义。
   * @returns {void} 事件内部对象使用 updateModdleProperties，确保 XML 与撤销栈一致。
   */
  function updateEventProperties() {
    if (designerLocked.value || !getModeler() || !selectedElement.value) return
    const businessObject = selectedBusinessObject.value
    const eventDefinition = businessObject?.eventDefinitions?.[0]
    if (isType('bpmn:BoundaryEvent')) {
      updateProperties({ cancelActivity: Boolean(propertyState.cancelActivity) })
    }
    if (!eventDefinition) return
    const modeling = getModeler().get('modeling')
    const config = eventReferenceConfig(eventDefinition.$type)
    if (config) {
      const key = propertyState.eventReference.trim()
      const reference = resolveEventRootReference(eventDefinition, config, key)
      modeling.updateModdleProperties(selectedElement.value, eventDefinition, {
        [config.referenceProperty]: reference
      })
      return
    }
    if (eventDefinition.$type !== 'bpmn:TimerEventDefinition') return
    const timerType = ['timeDate', 'timeDuration', 'timeCycle'].includes(propertyState.timerDefinitionType)
      ? propertyState.timerDefinitionType
      : 'timeDuration'
    const expression = propertyState.timerDefinition.trim()
    const formalExpression = expression
      ? getModeler().get('moddle').create('bpmn:FormalExpression', { body: expression })
      : undefined
    modeling.updateModdleProperties(selectedElement.value, eventDefinition, {
      timeDate: timerType === 'timeDate' ? formalExpression : undefined,
      timeDuration: timerType === 'timeDuration' ? formalExpression : undefined,
      timeCycle: timerType === 'timeCycle' ? formalExpression : undefined
    })
  }

  /**
   * 从当前事件定义读取引用或定时表达式，保证属性面板与作者 XML 保持一致。
   * @param {object} businessObject 当前选中 BPMN 元素的业务对象。
   * @returns {void} 非事件或无事件定义时保留空状态。
   */
  function loadEventPropertyState(businessObject) {
    const eventDefinition = businessObject.eventDefinitions?.[0]
    if (!eventDefinition) return
    propertyState.eventDefinitionType = eventDefinition.$type || ''
    const referenceConfig = eventReferenceConfig(eventDefinition.$type)
    if (referenceConfig) {
      const reference = eventDefinition[referenceConfig.referenceProperty]
      propertyState.eventReference = reference?.[referenceConfig.keyProperty]
        || reference?.name
        || reference?.id
        || ''
      return
    }
    if (eventDefinition.$type !== 'bpmn:TimerEventDefinition') return
    const timerType = ['timeDate', 'timeDuration', 'timeCycle']
      .find(type => eventDefinition[type]) || 'timeDuration'
    propertyState.timerDefinitionType = timerType
    propertyState.timerDefinition = eventDefinition[timerType]?.body || ''
  }

  /**
   * 校验并写入当前 UserTask 的受控 SLA 属性。
   * @param {object} config SLA 编辑器提交的八个结构化作者字段。
   * @returns {void} 目录或跨字段约束不合法时恢复 BPMN 原值并向页面上报。
   */
  function updateSlaProperties(config) {
    if (designerLocked.value || !getModeler() || !selectedElement.value || !isType('bpmn:UserTask')) return
    try {
      const normalized = normalizeAndValidateSlaConfig(config)
      propertyState.sla = normalized
      // SLA 只替换自己的八个字段，其余扩展协议必须保留在同一命令栈更新中。
      const controlledProperties = readAllFlowableProperties(selectedBusinessObject.value)
        .filter(item => !isUserTaskSlaProperty(item.name))
      persistExtensionProperties([
        ...controlledProperties,
        ...slaConfigToProperties(normalized)
      ])
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
    }
  }

  /**
   * 从错误或升级事件定义读取最终作者编码。
   * @param {object} eventDefinition bpmn-js 事件定义对象。
   * @returns {string} 根引用中的稳定业务编码。
   */
  function readEventReference(eventDefinition) {
    const config = eventReferenceConfig(eventDefinition?.$type)
    const reference = config ? eventDefinition?.[config.referenceProperty] : undefined
    return String(reference?.[config?.keyProperty] || reference?.name || '').trim()
  }

  return {
    extensionOptions,
    businessListenerOptions,
    formFieldOptions,
    connectorEndpoints,
    sqlDataSources,
    extensionLoading,
    errorEventOptions,
    escalationEventOptions,
    eventCodeLoading,
    slaCalendarOptions,
    slaLoading,
    autoCopyTriggerOptions,
    autoCopyFormFieldOptions,
    readAutoCopyRules,
    resolveAutoCopyFormFieldOptions,
    resolveAutoCopyFormFieldOptionsForBusinessObject,
    readControlledTaskExtension,
    readBusinessListeners,
    updateControlledTask,
    updateControlledTaskSelection,
    loadExtensionOptions,
    updateBusinessExecutionListeners,
    updateBusinessTaskListeners,
    updateExtensionProperties,
    normalizeAndValidateAutoCopyRules,
    validateAutoCopyRulesForElement,
    updateAutoCopyRules,
    updateBusinessListeners,
    eventReferenceConfig,
    findDefinitions,
    eventReferenceIdPart,
    resolveEventRootReference,
    updateEventProperties,
    loadEventPropertyState,
    readSlaConfig,
    updateSlaProperties,
    normalizeAndValidateSlaConfig,
    isBoundedSlaMinute,
    slaConfigToProperties,
    createDefaultSlaConfig,
    readEventReference
  }
}
