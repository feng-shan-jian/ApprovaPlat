import request from '@/utils/request'

/**
 * 上传当前用户所有的工作流临时附件。
 * @param {string} fieldName 部署表单中的 el-upload 变量名。
 * @param {File} file 浏览器选择的真实文件。
 * @returns {Promise<object>} data 为不含存储路径的附件安全元数据。
 */
export function uploadWorkflowAttachment(fieldName, file) {
  const data = new FormData()
  data.append('fieldName', fieldName)
  data.append('file', file)
  return request({
    url: '/workflow/attachment',
    method: 'post',
    headers: { 'Content-Type': 'multipart/form-data', repeatSubmit: false },
    timeout: 120000,
    data
  })
}

/**
 * 查询经过附件或所属流程对象授权的安全元数据。
 * @param {string} attachmentId 服务端生成的附件 UUID。
 * @returns {Promise<object>} data 为附件安全元数据。
 */
export function getWorkflowAttachment(attachmentId) {
  return request({
    url: `/workflow/attachment/${encodeURIComponent(attachmentId)}`,
    method: 'get'
  })
}

/**
 * 下载经过对象授权的私有工作流附件。
 * @param {string} attachmentId 服务端生成的附件 UUID。
 * @returns {Promise<Blob>} 私有附件二进制内容。
 */
export function downloadWorkflowAttachment(attachmentId) {
  return request({
    url: `/workflow/attachment/${encodeURIComponent(attachmentId)}/content`,
    method: 'get',
    responseType: 'blob',
    timeout: 120000
  })
}

/**
 * 删除当前用户所有且尚未绑定流程的临时附件。
 * @param {string} attachmentId 服务端生成的附件 UUID。
 * @returns {Promise<object>} 删除和物理清理均完成后的响应。
 */
export function deleteWorkflowAttachment(attachmentId) {
  return request({
    url: `/workflow/attachment/${encodeURIComponent(attachmentId)}`,
    method: 'delete'
  })
}
