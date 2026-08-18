import request from '@/utils/request'

/**
 * 查询当前用户有权查看的脱敏 OSS 配置列表。
 * @returns {Promise<object>} 包含脱敏 OSS 配置列表的接口响应。
 */
export function listOssConfigs() {
  return request({ url: '/system/oss/configs', method: 'get' })
}

/**
 * 新增一条停用状态的 OSS 配置。
 * @param {object} data 待持久化的 OSS 配置。
 * @returns {Promise<object>} 包含新配置主键的接口响应。
 */
export function addOssConfig(data) {
  return request({ url: '/system/oss/configs', method: 'post', data })
}

/**
 * 更新指定 OSS 配置，空密钥由服务端解释为保留原值。
 * @param {object} data 包含配置主键的 OSS 配置。
 * @returns {Promise<object>} 配置修改结果。
 */
export function updateOssConfig(data) {
  return request({ url: '/system/oss/configs', method: 'put', data })
}

/**
 * 对指定 OSS 配置执行服务端真实 HeadBucket 测试。
 * @param {number|string} configId 待测试的配置主键。
 * @returns {Promise<object>} HeadBucket 连通性结果。
 */
export function testOssConfig(configId) {
  return request({ url: `/system/oss/configs/${configId}/test`, method: 'post' })
}

/**
 * 启用指定 OSS 配置并由服务端维护唯一启用约束。
 * @param {number|string} configId 待启用的配置主键。
 * @returns {Promise<object>} 配置启用结果。
 */
export function activateOssConfig(configId) {
  return request({ url: `/system/oss/configs/${configId}/activate`, method: 'put' })
}

/**
 * 删除没有对象台账引用的停用 OSS 配置。
 * @param {number|string} configId 待删除的配置主键。
 * @returns {Promise<object>} 配置删除结果。
 */
export function deleteOssConfig(configId) {
  return request({ url: `/system/oss/configs/${configId}`, method: 'delete' })
}

/**
 * 分页查询当前用户有权查看的 OSS 对象台账。
 * @param {object} params pageNum 和 pageSize 分页参数。
 * @returns {Promise<object>} 包含对象元数据和生命周期状态的接口响应。
 */
export function listOssObjects(params) {
  return request({ url: '/system/oss/objects', method: 'get', params })
}

/**
 * 将单个文件上传到当前启用的正式 OSS 配置。
 * @param {FormData} data 包含单文件字段的 multipart 数据。
 * @returns {Promise<object>} 已持久化对象元数据的接口响应。
 */
export function uploadOssObject(data) {
  return request({
    url: '/system/oss/objects', method: 'post', data,
    headers: { 'Content-Type': 'multipart/form-data' }, timeout: 60000
  })
}

/**
 * 经后端鉴权下载指定 OSS 对象的二进制内容。
 * @param {number|string} objectId 待下载的对象主键。
 * @returns {Promise<Blob>} 鉴权通过后的对象内容。
 */
export function downloadOssObject(objectId) {
  return request({ url: `/system/oss/objects/${objectId}/download`, method: 'get', responseType: 'blob', timeout: 60000 })
}

/**
 * 删除指定 OSS 对象，失败状态下由服务端执行受控重试。
 * @param {number|string} objectId 待删除或重试删除的对象主键。
 * @returns {Promise<object>} 对象删除状态机处理结果。
 */
export function deleteOssObject(objectId) {
  return request({ url: `/system/oss/objects/${objectId}`, method: 'delete' })
}
