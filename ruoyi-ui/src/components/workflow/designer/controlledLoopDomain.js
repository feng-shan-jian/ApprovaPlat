const CONTROLLED_LOOP_PROPERTY_PREFIX = 'approva.controlledLoop.'
const CONTROLLED_LOOP_PROPERTIES = Object.freeze({
  enabled: `${CONTROLLED_LOOP_PROPERTY_PREFIX}enabled`,
  decisionVariable: `${CONTROLLED_LOOP_PROPERTY_PREFIX}decisionVariable`,
  repeatValue: `${CONTROLLED_LOOP_PROPERTY_PREFIX}repeatValue`,
  exitValue: `${CONTROLLED_LOOP_PROPERTY_PREFIX}exitValue`,
  maxIterations: `${CONTROLLED_LOOP_PROPERTY_PREFIX}maxIterations`
})
const CONTROLLED_LOOP_PROPERTY_NAMES = new Set(Object.values(CONTROLLED_LOOP_PROPERTIES))

/**
 * 判断属性是否属于受控整改循环固定协议。
 * @param {unknown} name Flowable Property 名称。
 * @returns {boolean} 属于五项受控整改循环属性时返回 true。
 */
export function isControlledLoopProperty(name) {
  return CONTROLLED_LOOP_PROPERTY_NAMES.has(name)
}

/**
 * 创建受控整改循环领域模块。
 * @param {object} context 表单字段目录、当前节点状态与扩展属性构造端口。
 * @returns {object} 整改循环属性读取、字段投影、校验和变更构造入口。
 */
export function createControlledLoopDomain(context) {
  const { buildPropertiesExtensionElements, formFieldCatalog, isUserTask, propertyState, selectedBusinessObject } = context

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
      .filter(property => isControlledLoopProperty(property.name))
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
   * @returns {Array<{name:string,value:string}>} 按协议顺序排列的五项属性。
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
   * 解析正式表单 JSON，并收窄为条件字段目录。
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
   * 校验整改循环面板状态并构造一次命令栈写入所需的完整变更。
   * @param {Array<{name:string,value:string}>} editableProperties 应原样保留的平台可编辑属性。
   * @returns {{loopCharacteristics:undefined,extensionElements:object}} 合法整改循环的 BPMN 变更对象。
   */
  function buildChanges(editableProperties) {
    if (!isUserTask.value) throw new Error('整改循环只能配置在用户任务上')
    const maxIterations = Number(propertyState.controlledLoopMaxIterations)
    const field = resolveControlledLoopFieldOptions().find(option => (
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
      selectedBusinessObject.value, editableProperties, controlledLoopPropertyItems())
    return { loopCharacteristics: undefined, extensionElements }
  }

  return { readControlledLoop, controlledLoopPropertyItems, resolveControlledLoopFieldOptions,
    describeFormalFormFields, describeTemplateFormFields, resolveTemplateControlledLoopKind, buildChanges }
}
