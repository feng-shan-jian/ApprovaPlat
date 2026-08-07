import request from '@/utils/request'

/**
 * 查询最近 1000 条脱敏运行事件台账。
 * @returns {Promise<object>} AjaxResult.data 为成功、失败和幂等结果数组。
 */
export function listRuntimeEvents() {
  return request({ url: '/workflow/runtime-event-audit/list', method: 'get' })
}
