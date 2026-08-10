import request from '@/utils/request'

/**
 * 查询当前用户有权查看的脱敏短信配置列表。
 * @returns {Promise<object>} 包含脱敏短信配置列表的接口响应。
 */
export function listSmsConfigs() {
  return request({ url: '/system/sms/configs', method: 'get' })
}

/**
 * 新增一条停用状态的短信供应商配置。
 * @param {object} data 待持久化的短信供应商配置。
 * @returns {Promise<object>} 包含新配置主键的接口响应。
 */
export function addSmsConfig(data) {
  return request({ url: '/system/sms/configs', method: 'post', data })
}

/**
 * 更新指定短信供应商配置，空密钥由服务端解释为保留原值。
 * @param {object} data 包含配置主键的短信供应商配置。
 * @returns {Promise<object>} 配置修改结果。
 */
export function updateSmsConfig(data) {
  return request({ url: '/system/sms/configs', method: 'put', data })
}

/**
 * 启用指定短信配置并由服务端维护唯一启用约束。
 * @param {number|string} configId 待启用的配置主键。
 * @returns {Promise<object>} 配置启用结果。
 */
export function activateSmsConfig(configId) {
  return request({ url: `/system/sms/configs/${configId}/activate`, method: 'put' })
}

/**
 * 删除没有发送审计引用的停用短信配置。
 * @param {number|string} configId 待删除的配置主键。
 * @returns {Promise<object>} 配置删除结果。
 */
export function deleteSmsConfig(configId) {
  return request({ url: `/system/sms/configs/${configId}`, method: 'delete' })
}

/**
 * 通过当前启用配置向真实短信供应商发起测试发送。
 * @param {object} data 手机号、供应商模板 ID 和模板参数。
 * @returns {Promise<object>} 脱敏后的供应商处理结果与审计主键。
 */
export function sendSmsTest(data) {
  return request({ url: '/system/sms/send', method: 'post', data })
}

/**
 * 查询最近的脱敏短信发送审计日志。
 * @returns {Promise<object>} 包含短信发送审计列表的接口响应。
 */
export function listSmsLogs() {
  return request({ url: '/system/sms/logs', method: 'get' })
}
