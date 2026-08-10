import request from '@/utils/request'

/**
 * 查询未删除的流程分类分页列表。
 * @param {object} query 分类名称、编码和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listCategories(query) {
  return request({ url: '/workflow/category/list', method: 'get', params: query })
}

/**
 * 查询当前用户可读取的全部有效分类选项。
 * @param {object} query 可选名称或编码过滤条件。
 * @returns {Promise<object>} data 为精简分类选项数组的响应。
 */
export function listAllCategories(query) {
  return request({ url: '/workflow/category/listAll', method: 'get', params: query })
}

/**
 * 查询单个流程分类详情。
 * @param {number|string} categoryId 流程分类主键。
 * @returns {Promise<object>} data 为分类详情的响应。
 */
export function getCategory(categoryId) {
  return request({
    url: `/workflow/category/${encodeURIComponent(String(categoryId))}`,
    method: 'get'
  })
}

/**
 * 新增流程分类。
 * @param {object} data 分类名称、编码和备注。
 * @returns {Promise<object>} 新增结果。
 */
export function createCategory(data) {
  return request({ url: '/workflow/category', method: 'post', data })
}

/**
 * 修改流程分类。
 * @param {object} data 分类主键、名称、编码和备注。
 * @returns {Promise<object>} 修改结果。
 */
export function updateCategory(data) {
  return request({ url: '/workflow/category', method: 'put', data })
}

/**
 * 删除一个或多个未被引用的流程分类。
 * @param {Array<number|string>|number|string} categoryIds 分类主键集合或单个主键。
 * @returns {Promise<object>} 受引用检查保护的删除结果。
 */
export function deleteCategories(categoryIds) {
  const ids = Array.isArray(categoryIds) ? categoryIds : [categoryIds]
  const pathIds = ids.map(id => encodeURIComponent(String(id))).join(',')
  return request({ url: `/workflow/category/${pathIds}`, method: 'delete' })
}
