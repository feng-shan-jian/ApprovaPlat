const SLA_PROPERTY_NAMES = Object.freeze({
  enabled: 'approva.sla.enabled',
  calendarKey: 'approva.sla.calendarKey',
  reminderMinutes: 'approva.sla.reminderMinutes',
  reminderRepeatMinutes: 'approva.sla.reminderRepeatMinutes',
  maxReminders: 'approva.sla.maxReminders',
  escalationMinutes: 'approva.sla.escalationMinutes',
  escalationUserId: 'approva.sla.escalationUserId',
  escalationEventCode: 'approva.sla.escalationEventCode'
})
const SLA_PROPERTY_NAME_SET = new Set(Object.values(SLA_PROPERTY_NAMES))

/**
 * 创建字段完整的 UserTask SLA 默认配置。
 * @returns {object} 未启用且数值处于合法范围的作者配置。
 */
export function createDefaultSlaConfig() {
  return {
    enabled: false,
    calendarKey: '',
    reminderMinutes: 60,
    reminderRepeatMinutes: 60,
    maxReminders: 1,
    escalationMinutes: 240,
    escalationUserId: '',
    escalationEventCode: ''
  }
}

/**
 * 判断属性是否属于 UserTask SLA 协议。
 * @param {unknown} name Flowable Property 名称。
 * @returns {boolean} SLA 协议字段返回 true。
 */
export function isUserTaskSlaProperty(name) {
  return SLA_PROPERTY_NAME_SET.has(name)
}

/**
 * 创建只依赖正式日历与升级事件目录的 UserTask SLA 领域。
 * @param {object} context SLA 校验所需的两个只读目录函数。
 * @returns {object} SLA 回读、校验、分钟边界和属性投影入口。
 */
export function createUserTaskSlaDomain({ readSlaCalendarOptions, readEscalationEventOptions }) {
  /**
   * 从 Flowable 通用属性集合解析 UserTask SLA 作者配置。
   * @param {Array<{name:string,value:string}>} properties 当前元素全部扩展属性。
   * @returns {object} 字段完整的结构化 SLA 配置；旧模型没有属性时返回停用默认值。
   */
  function readSlaConfig(properties) {
    const values = new Map((Array.isArray(properties) ? properties : [])
      .filter(item => SLA_PROPERTY_NAME_SET.has(item.name))
      .map(item => [item.name, String(item.value ?? '')]))
    const defaults = createDefaultSlaConfig()
    return {
      enabled: values.get(SLA_PROPERTY_NAMES.enabled) === 'true',
      calendarKey: values.get(SLA_PROPERTY_NAMES.calendarKey) || defaults.calendarKey,
      reminderMinutes: Number(values.get(SLA_PROPERTY_NAMES.reminderMinutes) || defaults.reminderMinutes),
      reminderRepeatMinutes: Number(values.get(SLA_PROPERTY_NAMES.reminderRepeatMinutes) || defaults.reminderRepeatMinutes),
      maxReminders: Number(values.get(SLA_PROPERTY_NAMES.maxReminders) || defaults.maxReminders),
      escalationMinutes: Number(values.get(SLA_PROPERTY_NAMES.escalationMinutes) || defaults.escalationMinutes),
      escalationUserId: values.get(SLA_PROPERTY_NAMES.escalationUserId) || defaults.escalationUserId,
      escalationEventCode: values.get(SLA_PROPERTY_NAMES.escalationEventCode) || defaults.escalationEventCode
    }
  }

  /**
   * 规范化并校验 SLA 数值、目录引用、提醒顺序和升级目标。
   * @param {object} config UserTask SLA 编辑值或从 XML 回读的配置。
   * @returns {object} 字段类型确定且可写入 XML 的 SLA 配置。
   */
  function normalizeAndValidateSlaConfig(config) {
    const normalized = {
      enabled: config?.enabled === true,
      calendarKey: String(config?.calendarKey || '').trim(),
      reminderMinutes: Number(config?.reminderMinutes),
      reminderRepeatMinutes: Number(config?.reminderRepeatMinutes),
      maxReminders: Number(config?.maxReminders),
      escalationMinutes: Number(config?.escalationMinutes),
      escalationUserId: String(config?.escalationUserId || '').trim(),
      escalationEventCode: String(config?.escalationEventCode || '').trim()
    }
    if (!normalized.enabled) return { ...createDefaultSlaConfig(), ...normalized }
    if (!readSlaCalendarOptions().some(item => item.calendarKey === normalized.calendarKey)) {
      throw new Error('审批 SLA 必须选择已启用的正式业务日历')
    }
    if (!isBoundedSlaMinute(normalized.reminderMinutes)
      || !isBoundedSlaMinute(normalized.reminderRepeatMinutes)
      || !isBoundedSlaMinute(normalized.escalationMinutes)) {
      throw new Error('SLA 提醒与升级时间必须是 1 至 525600 的整数分钟')
    }
    if (!Number.isInteger(normalized.maxReminders) || normalized.maxReminders < 1 || normalized.maxReminders > 100) {
      throw new Error('SLA 最大提醒次数必须是 1 至 100 的整数')
    }
    const lastReminderMinutes = normalized.reminderMinutes
      + normalized.reminderRepeatMinutes * (normalized.maxReminders - 1)
    if (normalized.escalationMinutes <= lastReminderMinutes) {
      throw new Error('SLA 超时升级时间必须晚于最后一次提醒')
    }
    if (!normalized.escalationUserId && !normalized.escalationEventCode) {
      throw new Error('SLA 必须配置升级办理人或受控升级事件')
    }
    if (normalized.escalationEventCode && !readEscalationEventOptions()
      .some(item => item.eventCode === normalized.escalationEventCode)) {
      throw new Error('SLA 超时升级只能引用已启用的正式升级编码')
    }
    return normalized
  }

  /**
   * 判断 SLA 工作分钟是否处于后端允许的一年上限内。
   * @param {number} value 待校验的提醒或升级工作分钟。
   * @returns {boolean} 值为 1 至 525600 的整数时返回 true。
   */
  function isBoundedSlaMinute(value) {
    return Number.isInteger(value) && value >= 1 && value <= 525600
  }

  /**
   * 将结构化 SLA 配置转换为后端部署编译器约定的八个 Flowable 属性。
   * @param {object} config 已规范化的 SLA 配置。
   * @returns {Array<{name:string,value:string}>} 按固定顺序输出的属性名值列表。
   */
  function slaConfigToProperties(config) {
    return Object.entries(SLA_PROPERTY_NAMES).map(([field, name]) => ({
      name,
      value: field === 'enabled' ? String(config.enabled === true) : String(config[field] ?? '')
    }))
  }

  return { readSlaConfig, normalizeAndValidateSlaConfig, isBoundedSlaMinute, slaConfigToProperties }
}
