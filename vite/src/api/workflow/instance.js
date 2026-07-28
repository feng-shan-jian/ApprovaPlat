import request from '@/utils/request'

/**
 * 由流程管理员激活或挂起运行实例。
 * @param {object} data 流程实例主键和目标状态枚举。
 * @returns {Promise<object>} 状态校验后的实例变更结果。
 */
export function changeProcessInstanceState(data) {
  return request({ url: '/workflow/instance/updateState', method: 'post', data })
}

/**
 * 由流程管理员真实终止运行实例并写入原因和操作人。
 * @param {object} data 流程实例主键和终止原因。
 * @returns {Promise<object>} 终止与审计原子写入结果。
 */
export function terminateProcessInstance(data) {
  return request({ url: '/workflow/instance/terminate', method: 'post', data })
}
