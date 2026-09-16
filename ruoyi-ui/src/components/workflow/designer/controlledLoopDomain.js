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

  function describeFormalFormFields(businessObject) {
    return formFieldCatalog.nodeFields(businessObject)?.loopFields || []
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
    describeFormalFormFields, buildChanges }
}
