import request from '@/utils/request'

/**
 * 查询每个流程标识的最新已部署定义。
 * @param {object} query 定义名称、标识、分类、状态和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listDeployments(query) {
  return request({ url: '/workflow/deploy/list', method: 'get', params: query })
}

/**
 * 查询指定流程标识的全部发布版本。
 * @param {object} query processKey 和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listPublishedVersions(query) {
  return request({ url: '/workflow/deploy/publishList', method: 'get', params: query })
}

/**
 * 查询经过服务端安全校验的已部署 BPMN XML。
 * @param {string} definitionId Flowable 流程定义主键。
 * @returns {Promise<object>} data 为 BPMN XML 的响应。
 */
export function getDeploymentBpmnXml(definitionId) {
  return request({
    url: `/workflow/deploy/bpmnXml/${encodeURIComponent(definitionId)}`,
    method: 'get'
  })
}

/**
 * 激活或挂起流程定义及其运行实例。
 * @param {string} definitionId Flowable 流程定义主键。
 * @param {'active'|'suspended'} state 目标状态。
 * @returns {Promise<object>} 状态变更结果。
 */
export function changeDeploymentState(definitionId, state) {
  return request({
    url: '/workflow/deploy/changeState',
    method: 'put',
    params: { definitionId, state }
  })
}

/**
 * 删除没有运行或历史实例引用的一个或多个部署。
 * @param {Array<string>|string} deploymentIds 部署主键集合或单个主键。
 * @returns {Promise<object>} 受引用检查保护的删除结果。
 */
export function deleteDeployments(deploymentIds) {
  const ids = Array.isArray(deploymentIds) ? deploymentIds : [deploymentIds]
  const pathIds = ids.map(id => encodeURIComponent(id)).join(',')
  return request({ url: `/workflow/deploy/${pathIds}`, method: 'delete' })
}
