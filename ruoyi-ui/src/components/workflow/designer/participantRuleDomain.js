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
/**
 * 将参与者属性中的逗号分隔身份转换为去重数组。
 * @param {unknown} value Flowable 参与者属性值。
 * @returns {string[]} 去除空值后的身份数组。
 */
function splitParticipantValues(value) {
  return [...new Set(String(value || '').split(',').map(item => item.trim()).filter(Boolean))]
}
/**
 * 从 BPMN 当前属性中筛选参与者协议并保持最后一个同名属性的既有回读语义。
 * @param {object} businessObject 当前 BPMN 流程或元素业务对象。
 * @param {(businessObject:object)=>Array<{name:string,value:string}>} readAllFlowableProperties 属性读取函数。
 * @returns {Map<string,string>} 仅包含参与者协议字段的属性映射。
 */
function readParticipantPropertyMap(businessObject, readAllFlowableProperties) {
  return new Map(readAllFlowableProperties(businessObject)
    .filter(item => isParticipantRuleProperty(item.name))
    .map(item => [item.name, item.value]))
}
/**
 * 判断属性是否属于流程发起范围或单实例任务办理人协议。
 * @param {unknown} name Flowable Property 名称。
 * @returns {boolean} 属于参与者规则协议字段时返回 true。
 */
export function isParticipantRuleProperty(name) {
  return PARTICIPANT_RULE_PROPERTY_NAMES.has(name)
}
/**
 * 创建参与者规则领域，只读取当前 BPMN 属性和正式表单字段目录。
 * @param {object} context 参与者规则的只读依赖。
 * @param {()=>Array<{value:string}>} context.readParticipantFormFieldOptions 当前任务可用的正式表单用户字段。
 * @param {(businessObject:object)=>Array<{name:string,value:string}>} context.readAllFlowableProperties BPMN 属性读取函数。
 * @returns {object} 参与者规则的回读、规范化、属性构造和保存校验入口。
 */
export function createParticipantRuleDomain({
  readParticipantFormFieldOptions,
  readAllFlowableProperties
}) {
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
   * 从流程或单实例 UserTask 的平台属性回读受控参与者规则。
   * @param {object} businessObject 当前 BPMN 流程或元素业务对象。
   * @returns {{type:string,targetIds:string[],formField:string}} 可直接交给规则编辑器的值。
   */
  function readParticipantRule(businessObject) {
    const values = readParticipantPropertyMap(businessObject, readAllFlowableProperties)
    if (businessObject?.$instanceOf?.('bpmn:Process')) {
      const type = values.get(PARTICIPANT_RULE_PROPERTIES.startType) || 'PUBLIC'
      return {
        type,
        targetIds: decorateParticipantTargets(type,
          splitParticipantValues(values.get(PARTICIPANT_RULE_PROPERTIES.startTargetIds))),
        formField: ''
      }
    }
    const configuredType = values.get(PARTICIPANT_RULE_PROPERTIES.taskType) || ''
    if (configuredType) {
      return {
        type: configuredType,
        targetIds: decorateParticipantTargets(configuredType,
          splitParticipantValues(values.get(PARTICIPANT_RULE_PROPERTIES.taskTargetIds))),
        formField: values.get(PARTICIPANT_RULE_PROPERTIES.taskFormField) || ''
      }
    }
    // 旧模型的静态身份在第一次修改时平滑转换为受控规则，表达式继续由后端兼容链处理。
    const assignee = String(businessObject?.get?.('flowable:assignee') || '').trim()
    if (/^[1-9]\d{0,18}$/.test(assignee)) {
      return { type: 'FIXED_USER', targetIds: [assignee], formField: '' }
    }
    const candidateUsers = splitParticipantValues(businessObject?.get?.('flowable:candidateUsers'))
    if (candidateUsers.length && candidateUsers.every(value => /^[1-9]\d{0,18}$/.test(value))) {
      return { type: 'CANDIDATE_USERS', targetIds: candidateUsers, formField: '' }
    }
    const candidateGroups = splitParticipantValues(businessObject?.get?.('flowable:candidateGroups'))
    if (candidateGroups.length && candidateGroups.every(value => /^(?:ROLE|DEPT)[1-9]\d{0,18}$/.test(value))) {
      return { type: 'CANDIDATE_GROUPS', targetIds: candidateGroups, formField: '' }
    }
    return { type: '', targetIds: [], formField: '' }
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
        && !readParticipantFormFieldOptions().some(option => option.value === formField)) {
        throw new Error('表单用户字段必须来自当前任务正式表单')
      }
    } else if (formField) {
      throw new Error('非表单用户规则不能携带表单字段')
    }
    return { type, targetIds, formField }
  }
  /**
   * 将界面目录值转换为作者 BPMN 固定顺序的受控属性。
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
   * 对流程和单实例任务执行参与者规则的即时结构校验。
   * @param {object} businessObject BPMN Process 或 UserTask 业务对象。
   * @param {boolean} processRule 是否校验流程发起范围。
   * @returns {string} 空串表示通过，否则返回稳定错误提示。
   */
  function validateParticipantProperties(businessObject, processRule) {
    if (!processRule && businessObject?.loopCharacteristics) return ''
    const properties = readParticipantPropertyMap(businessObject, readAllFlowableProperties)
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

  return {
    readParticipantRule, decorateParticipantTargets, normalizeParticipantRule,
    participantRulePropertyItems, validateParticipantProperties
  }
}
