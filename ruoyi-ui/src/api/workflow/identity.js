import request from '@/utils/request'
import { buildApprovalUserQuery, buildClaimIdentityQuery } from './identityQuery'

/**
 * 分页查询工作流专用有效身份选项。
 * @param {object} query type、keyword、pageNum 和 pageSize 查询参数。
 * @returns {Promise<object>} rows 仅包含 value、label 和 type 的分页响应。
 */
export function listIdentityOptions(query) {
  return request({ url: '/workflow/identity/options', method: 'get', params: query })
}

/**
 * 分页查询具备正式审批办理资格的启用用户。
 * @param {object} query keyword、pageNum 和 pageSize 查询参数；调用方不能覆盖身份类型和能力范围。
 * @returns {Promise<object>} rows 仅包含通过服务端 RBAC 资格校验的用户选项。
 */
export function listApprovalUserOptions(query = {}) {
  return request({
    url: '/workflow/identity/options',
    method: 'get',
    params: buildApprovalUserQuery(query)
  })
}

/**
 * 分页查询具备完整候选认领闭环资格的启用用户、角色或部门。
 * @param {object} query type、keyword、pageNum 和 pageSize 查询参数；调用方不能覆盖 claim 能力范围。
 * @returns {Promise<object>} rows 仅包含至少存在真实可认领办理人的安全身份选项。
 */
export function listClaimableIdentityOptions(query = {}) {
  return request({
    url: '/workflow/identity/options',
    method: 'get',
    params: buildClaimIdentityQuery(query)
  })
}

/**
 * 批量回显作者 BPMN 已保存的正式身份对象及其实时可用状态。
 * @param {{type:string, capability:string, values:string[]}} data 身份类型、能力和受控目录值。
 * @returns {Promise<object>} data 为保持请求顺序的正式名称和 available 状态。
 */
export function resolveIdentityOptions(data) {
  return request({ url: '/workflow/identity/options/resolve', method: 'post', data })
}
