import request from '@/utils/request'

/**
 * 分页查询脱敏运行事件台账。
 * @param {object} query 分页、状态、类型、来源和时间筛选。
 * @returns {Promise<object>} 若依 rows/total 分页结果。
 */
export function listRuntimeEvents(query) {
  return request({ url: '/workflow/runtime-event-audit/list', method: 'get', params: query })
}
