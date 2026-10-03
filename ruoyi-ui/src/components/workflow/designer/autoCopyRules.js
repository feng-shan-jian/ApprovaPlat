const AUTO_COPY_RULE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/
const AUTO_COPY_USER_ID_PATTERN = /^[1-9]\d{0,18}$/
const AUTO_COPY_GROUP_ID_PATTERN = /^(?:ROLE|DEPT)[1-9]\d{0,18}$/
export const AUTO_COPY_VARIABLE_PATTERN = /^[A-Za-z][A-Za-z0-9_.]{0,63}$/

/** Pure authoring contract shared by the editor and BPMN property writer. */
export function normalizeAutoCopyRules(rules, options = {}) {
  const { maxRules = 10, maxSources = 20, maxValues = 100, maxLength = 8192 } = options
  if (!Array.isArray(rules) || (!rules.length && options.allowEmpty === false) || rules.length > maxRules) {
    throw new Error(`自动抄送规则数量必须为 1 至 ${maxRules}`)
  }
  const validatePlacement = options.validatePlacement !== false
  const validateFormFields = options.validateFormFields !== false
  const allowedTriggers = new Set(options.allowedTriggers || [])
  const allowedFormFields = new Set(options.allowedFormFields || [])
  const ruleIds = new Set()
  const normalized = rules.map(rule => {
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
      || rule.recipients.length > maxSources) {
      throw new Error(`自动抄送接收人来源数量必须为 1 至 ${maxSources}`)
    }
    const sourceKeys = new Set()
    const recipients = rule.recipients.map(source => {
      const type = String(source?.type || '')
      if (!['USER', 'GROUP', 'INITIATOR', 'FORM_USER_FIELD'].includes(type)) {
        throw new Error('自动抄送接收人来源不受支持')
      }
      if (!Array.isArray(source?.values) || source.values.length > maxValues) {
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
  if (options.validateLength !== false && normalized.length
    && JSON.stringify({ version: 1, rules: normalized }).length > maxLength) {
    throw new Error(`自动抄送规则不能超过 ${maxLength} 个字符`)
  }
  return normalized
}
