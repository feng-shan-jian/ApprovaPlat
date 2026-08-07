import request from '@/utils/request'

/**
 * 查询连接器端点管理清单。
 * @returns {Promise<object>} AjaxResult.data 为真实端点修订数组。
 */
export function listConnectorEndpoints() {
  return request({ url: '/workflow/connector/list', method: 'get' })
}

/**
 * 查询设计器可选择的已启用连接器端点。
 * @returns {Promise<object>} AjaxResult.data 不包含密钥正文。
 */
export function listConnectorEndpointOptions() {
  return request({ url: '/workflow/connector/options', method: 'get' })
}

/**
 * 新增 HTTP 连接器端点白名单。
 * @param {object} data 端点配置和外部密钥引用。
 * @returns {Promise<object>} 包含 endpointId 的响应。
 */
export function createConnectorEndpoint(data) {
  return request({ url: '/workflow/connector', method: 'post', data })
}

/**
 * 发布 HTTP 连接器端点下一修订。
 * @param {number|string} endpointId 端点主键。
 * @param {object} data 新修订配置。
 * @returns {Promise<object>} 包含 revisionNo 的响应。
 */
export function updateConnectorEndpoint(endpointId, data) {
  return request({ url: `/workflow/connector/${endpointId}`, method: 'put', data })
}

/**
 * 修改连接器端点启停状态。
 * @param {number|string} endpointId 端点主键。
 * @param {boolean} enabled 是否允许后续设计和部署。
 * @returns {Promise<object>} 操作结果。
 */
export function changeConnectorEndpointStatus(endpointId, enabled) {
  return request({
    url: `/workflow/connector/${endpointId}/status`,
    method: 'put',
    data: { enabled }
  })
}
