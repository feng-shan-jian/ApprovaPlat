import request from '@/utils/request'

/**
 * 查询有效流程表单模板分页列表。
 * @param {object} query 表单名称和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listForms(query) {
  return request({ url: '/workflow/form/list', method: 'get', params: query })
}

/**
 * 查询可编辑流程表单模板详情。
 * @param {number|string} formId 流程表单主键。
 * @returns {Promise<object>} data 为模板详情的响应。
 */
export function getForm(formId) {
  return request({
    url: `/workflow/form/${encodeURIComponent(String(formId))}`,
    method: 'get'
  })
}

/**
 * 新增经过服务端 schema 校验的流程表单模板。
 * @param {object} data 表单名称、JSON 模板正文和备注。
 * @returns {Promise<object>} 新增结果。
 */
export function createForm(data) {
  return request({ url: '/workflow/form', method: 'post', data })
}

/**
 * 修改当前可编辑模板，不覆盖任何已部署表单快照。
 * @param {object} data 表单主键、名称、JSON 模板正文和备注。
 * @returns {Promise<object>} 修改结果。
 */
export function updateForm(data) {
  return request({ url: '/workflow/form', method: 'put', data })
}

/**
 * 删除一个或多个未被模型或部署快照引用的流程表单。
 * @param {Array<number|string>|number|string} formIds 表单主键集合或单个主键。
 * @returns {Promise<object>} 受引用检查保护的删除结果。
 */
export function deleteForms(formIds) {
  const ids = Array.isArray(formIds) ? formIds : [formIds]
  const pathIds = ids.map(id => encodeURIComponent(String(id))).join(',')
  return request({ url: `/workflow/form/${pathIds}`, method: 'delete' })
}
