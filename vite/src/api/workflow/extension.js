import request from '@/utils/request'

/**
 * 查询设计器可选择的已启用 Java 扩展最新版。
 * @returns {Promise<object>} 若依 AjaxResult，data 为真实扩展目录选项。
 */
export function listJavaExtensionOptions() {
  return request({ url: '/workflow/extension/options/java', method: 'get' })
}

/**
 * 查询设计器可选择的已启用 CEL 扩展最新版。
 * @returns {Promise<object>} 若依 AjaxResult，data 为真实 CEL 扩展目录选项。
 */
export function listCelExtensionOptions() {
  return request({ url: '/workflow/extension/options/cel', method: 'get' })
}

/**
 * 查询设计器可选择的已启用 HTTP 扩展最新版。
 * @returns {Promise<object>} 若依 AjaxResult，data 为真实 HTTP 扩展目录选项。
 */
export function listHttpExtensionOptions() {
  return request({ url: '/workflow/extension/options/http', method: 'get' })
}

/**
 * 查询设计器可选择的已启用 SQL 扩展最新版。
 * @returns {Promise<object>} data 为真实 SQL 扩展目录选项。
 */
export function listSqlExtensionOptions() {
  return request({ url: '/workflow/extension/options/sql', method: 'get' })
}

/**
 * 查询设计器可选择的已启用自定义表单字段最新版。
 * @returns {Promise<object>} data 为真实 FORM_FIELD 扩展目录选项。
 */
export function listFormFieldExtensionOptions() {
  return request({ url: '/workflow/extension/options/form-field', method: 'get' })
}

/**
 * 查询扩展管理清单，包含停用和尚未发布版本的目录。
 * @returns {Promise<object>} AjaxResult.data 为真实扩展目录数组。
 */
export function listWorkflowExtensions() {
  return request({ url: '/workflow/extension/list', method: 'get' })
}

/**
 * 查询服务端代码实际安装的 Java 处理器。
 * @returns {Promise<object>} 若依 AjaxResult，data 为已安装处理器清单。
 */
export function listInstalledJavaHandlers() {
  return request({ url: '/workflow/extension/installed-handlers/java', method: 'get' })
}

/**
 * 创建受控扩展目录。
 * @param {object} data 稳定键、名称、类型和说明。
 * @returns {Promise<object>} 包含数据库 extensionId 的响应。
 */
export function createWorkflowExtension(data) {
  return request({ url: '/workflow/extension', method: 'post', data })
}

/**
 * 发布扩展不可变新版本。
 * @param {number|string} extensionId 扩展目录主键。
 * @param {object} data 已安装处理器稳定键。
 * @returns {Promise<object>} 包含数据库 versionId 的响应。
 */
export function createWorkflowExtensionVersion(extensionId, data) {
  return request({ url: `/workflow/extension/${extensionId}/versions`, method: 'post', data })
}

/**
 * 修改扩展目录启停状态。
 * @param {number|string} extensionId 扩展目录主键。
 * @param {boolean} enabled 是否允许后续选择和部署。
 * @returns {Promise<object>} 操作结果。
 */
export function changeWorkflowExtensionStatus(extensionId, enabled) {
  return request({
    url: `/workflow/extension/${extensionId}/status`,
    method: 'put',
    data: { enabled }
  })
}

/**
 * 删除已停用且未被部署快照引用的扩展目录。
 * @param {number|string} extensionId 扩展目录主键。
 * @returns {Promise<object>} 操作结果。
 */
export function removeWorkflowExtension(extensionId) {
  return request({
    url: `/workflow/extension/${extensionId}`,
    method: 'delete'
  })
}
