import request from '@/utils/request'

/** @returns {Promise<object>} 全部 BPMN 错误与升级编码目录。 */
export function listBpmnEventCodes() {
  return request({ url: '/workflow/bpmn-event/codes', method: 'get' })
}

/**
 * 查询设计器可选择的启用编码。
 * @param {'ERROR'|'ESCALATION'} eventType BPMN 事件类型。
 * @returns {Promise<object>} 真实数据库目录选项。
 */
export function listBpmnEventCodeOptions(eventType) {
  return request({ url: `/workflow/bpmn-event/codes/options/${eventType}`, method: 'get' })
}

/** @param {object} data 目录字段；@returns {Promise<object>} 新增结果。 */
export function createBpmnEventCode(data) {
  return request({ url: '/workflow/bpmn-event/codes', method: 'post', data })
}

/** @param {number|string} eventCodeId 主键；@param {object} data 完整字段；@returns {Promise<object>} 修改结果。 */
export function updateBpmnEventCode(eventCodeId, data) {
  return request({ url: `/workflow/bpmn-event/codes/${eventCodeId}`, method: 'put', data })
}

/** @param {number|string} eventCodeId 主键；@param {boolean} enabled 目标状态；@returns {Promise<object>} 状态修改结果。 */
export function changeBpmnEventCodeStatus(eventCodeId, enabled) {
  return request({
    url: `/workflow/bpmn-event/codes/${eventCodeId}/status`,
    method: 'put',
    data: { enabled }
  })
}

/** @param {object} query 分页、状态、类型、来源和时间筛选；@returns {Promise<object>} 若依 rows/total 分页结果。 */
export function listBpmnEventAudit(query) {
  return request({ url: '/workflow/bpmn-event/audit', method: 'get', params: query })
}
