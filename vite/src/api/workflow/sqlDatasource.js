import request from '@/utils/request'

/**
 * 查询 SQL 数据源管理清单。
 * @returns {Promise<object>} AjaxResult.data 为不含凭据正文的数据源数组。
 */
export function listSqlDataSources() {
  return request({ url: '/workflow/sql-datasource/list', method: 'get' })
}

/**
 * 查询设计器可选择的已启用 SQL 数据源。
 * @returns {Promise<object>} AjaxResult.data 为已启用数据源选项。
 */
export function listSqlDataSourceOptions() {
  return request({ url: '/workflow/sql-datasource/options', method: 'get' })
}

/**
 * 创建 SQL 数据源目录。
 * @param {object} data 逻辑连接、环境引用和表白名单。
 * @returns {Promise<object>} 包含 dataSourceId 的响应。
 */
export function createSqlDataSource(data) {
  return request({ url: '/workflow/sql-datasource', method: 'post', data })
}

/**
 * 发布 SQL 数据源下一不可回退修订。
 * @param {number|string} dataSourceId 数据源主键。
 * @param {object} data 新修订配置。
 * @returns {Promise<object>} 包含 revisionNo 的响应。
 */
export function updateSqlDataSource(dataSourceId, data) {
  return request({ url: `/workflow/sql-datasource/${dataSourceId}`, method: 'put', data })
}

/**
 * 修改 SQL 数据源启停状态。
 * @param {number|string} dataSourceId 数据源主键。
 * @param {boolean} enabled 是否允许后续设计和部署。
 * @returns {Promise<object>} 操作响应。
 */
export function changeSqlDataSourceStatus(dataSourceId, enabled) {
  return request({
    url: `/workflow/sql-datasource/${dataSourceId}/status`,
    method: 'put',
    data: { enabled }
  })
}
