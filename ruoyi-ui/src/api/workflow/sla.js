import request from '@/utils/request'

/**
 * 查询全部正式业务日历。
 * @returns {Promise<object>} 包含日历规则和日期覆盖的后端响应。
 */
export function listSlaCalendars() {
  return request({ url: '/workflow/sla/calendars', method: 'get' })
}

/**
 * 查询设计器可选择的启用业务日历。
 * @returns {Promise<object>} 服务端按启停状态过滤后的日历选项。
 */
export function listEnabledSlaCalendars() {
  return request({ url: '/workflow/sla/calendars/enabled', method: 'get' })
}

/**
 * 新增正式业务日历。
 * @param {object} data 业务日历规则和日期覆盖完整字段。
 * @returns {Promise<object>} 包含正式日历主键的新增结果。
 */
export function createSlaCalendar(data) {
  return request({ url: '/workflow/sla/calendars', method: 'post', data })
}

/**
 * 修改正式业务日历规则。
 * @param {number|string} calendarId 业务日历主键。
 * @param {object} data 日历规则和日期覆盖完整字段。
 * @returns {Promise<object>} 修改结果。
 */
export function updateSlaCalendar(calendarId, data) {
  return request({ url: `/workflow/sla/calendars/${calendarId}`, method: 'put', data })
}

/**
 * 切换正式业务日历启停状态。
 * @param {number|string} calendarId 业务日历主键。
 * @param {boolean} enabled 目标启用状态。
 * @returns {Promise<object>} 状态修改结果。
 */
export function changeSlaCalendarStatus(calendarId, enabled) {
  return request({
    url: `/workflow/sla/calendars/${calendarId}/status`,
    method: 'put',
    data: { enabled }
  })
}

/**
 * 分页查询 SLA 执行状态。
 * @param {object} query 分页、状态、关键字和时间筛选。
 * @returns {Promise<object>} 当前权限范围内的 rows/total。
 */
export function listSlaExecutions(query) {
  return request({ url: '/workflow/sla/executions', method: 'get', params: query })
}

/**
 * 分页查询 SLA 生命周期与触发审计。
 * @param {object} query 分页、动作、关键字和时间筛选。
 * @returns {Promise<object>} 创建、提醒、升级、暂停、恢复和完成审计 rows/total。
 */
export function listSlaAudits(query) {
  return request({ url: '/workflow/sla/audits', method: 'get', params: query })
}

/**
 * 查询当前用户 SLA 提醒与升级通知。
 * @returns {Promise<object>} 服务端按接收人隔离的通知集合。
 */
export function listMySlaNotifications() {
  return request({ url: '/workflow/sla/notifications', method: 'get' })
}

/**
 * 将当前用户的一条 SLA 通知标记为已读。
 * @param {number|string} notificationId 通知主键。
 * @returns {Promise<object>} 服务端鉴权后的已读结果。
 */
export function markSlaNotificationRead(notificationId) {
  return request({ url: `/workflow/sla/notifications/${notificationId}/read`, method: 'put' })
}
