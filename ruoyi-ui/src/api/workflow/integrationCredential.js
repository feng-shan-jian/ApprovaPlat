import request from '@/utils/request'

/**
 * 查询全部脱敏集成账号。
 * @returns {Promise<object>} AjaxResult.data 为不含 Token 正文和哈希的账号数组。
 */
export function listIntegrationCredentials() {
  return request({ url: '/workflow/integration-credential/list', method: 'get' })
}

/**
 * 创建集成账号，明文 Token 仅在本次响应返回。
 * @param {object} data 范围、变量白名单、限流和可选到期时间。
 * @returns {Promise<object>} AjaxResult.data.token 为一次性明文 Token。
 */
export function createIntegrationCredential(data) {
  return request({ url: '/workflow/integration-credential', method: 'post', data })
}

/**
 * 原子轮换集成 Token，并立即使旧 Token 失效。
 * @param {number|string} credentialId 正式凭据主键。
 * @param {object} data 可选的新到期时间；空值表示保持原值。
 * @returns {Promise<object>} AjaxResult.data.token 为一次性新 Token。
 */
export function rotateIntegrationCredential(credentialId, data) {
  return request({
    url: `/workflow/integration-credential/${encodeURIComponent(credentialId)}/rotate`,
    method: 'put',
    data
  })
}

/**
 * 永久吊销集成账号并保留运行事件审计。
 * @param {number|string} credentialId 正式凭据主键。
 * @returns {Promise<object>} 吊销成功响应。
 */
export function revokeIntegrationCredential(credentialId) {
  return request({
    url: `/workflow/integration-credential/${encodeURIComponent(credentialId)}`,
    method: 'delete'
  })
}
