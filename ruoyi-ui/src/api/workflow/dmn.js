import request from '@/utils/request'

/**
 * 查询全部 Flowable 官方 DMN 来源版本。
 * @returns {Promise<object>} AjaxResult.data 为按 key 和版本排序的决策版本数组。
 */
export function listDmnDecisions() {
  return request({ url: '/workflow/dmn/list', method: 'get' })
}

/**
 * 查询设计器可选择的每个决策 key 最新来源版本。
 * @returns {Promise<object>} AjaxResult.data 中每项都包含精确 decisionId。
 */
export function listDmnDecisionOptions() {
  return request({ url: '/workflow/dmn/options', method: 'get' })
}

/**
 * 部署经过服务端 XML 安全门禁的 DMN 资源。
 * @param {{resourceName:string, category:string, dmnXml:string}} data DMN 资源正文与元数据。
 * @returns {Promise<object>} AjaxResult.data.deploymentId 为官方部署主键。
 */
export function deployDmnDecision(data) {
  return request({ url: '/workflow/dmn', method: 'post', data })
}

/**
 * 删除未被任一流程冻结快照引用的 DMN 来源部署。
 * @param {string} deploymentId Flowable 官方 DMN 部署主键。
 * @returns {Promise<object>} 删除成功响应。
 */
export function removeDmnDeployment(deploymentId) {
  return request({ url: `/workflow/dmn/${encodeURIComponent(deploymentId)}`, method: 'delete' })
}
