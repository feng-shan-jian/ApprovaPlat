import request from '@/utils/request'

/**
 * 查询当前登录用户自己的申请草稿。
 * @param {object} query 流程名称、更新时间范围和分页条件。
 * @returns {Promise<object>} 若依分页响应，rows 仅包含当前用户草稿。
 */
export function listProcessDrafts(query) {
  return request({ url: '/workflow/process/draft/list', method: 'get', params: query })
}

/**
 * 查询当前用户有权访问的单个申请草稿及不可变部署表单快照。
 * @param {string} draftId 草稿主键。
 * @returns {Promise<object>} data 为草稿详情、表单值和附件安全元数据。
 */
export function getProcessDraft(draftId) {
  return request({
    url: `/workflow/process/draft/${encodeURIComponent(draftId)}`,
    method: 'get'
  })
}

/**
 * 基于当前可发起流程定义创建本人申请草稿。
 * @param {object} data processDefinitionId、可选 businessKey 和允许不完整的 variables。
 * @returns {Promise<object>} data 为已持久化草稿及初始乐观锁版本。
 */
export function createProcessDraft(data) {
  return request({
    url: '/workflow/process/draft',
    method: 'post',
    headers: { repeatSubmit: false },
    data
  })
}

/**
 * 按乐观锁版本更新本人申请草稿。
 * @param {string} draftId 草稿主键。
 * @param {object} data expectedVersion、可选 businessKey 和允许不完整的 variables。
 * @returns {Promise<object>} data 为更新后的草稿和新乐观锁版本。
 */
export function updateProcessDraft(draftId, data) {
  return request({
    url: `/workflow/process/draft/${encodeURIComponent(draftId)}`,
    method: 'put',
    headers: { repeatSubmit: false },
    data
  })
}

/**
 * 按乐观锁版本删除本人尚未提交的申请草稿。
 * @param {string} draftId 草稿主键。
 * @param {number} expectedVersion 当前页面读取到的乐观锁版本。
 * @returns {Promise<object>} 草稿及其附件引用清理完成后的响应。
 */
export function deleteProcessDraft(draftId, expectedVersion) {
  return request({
    url: `/workflow/process/draft/${encodeURIComponent(draftId)}`,
    method: 'delete',
    params: { expectedVersion }
  })
}

/**
 * 按乐观锁版本正式提交申请草稿。
 * @param {string} draftId 草稿主键。
 * @param {object} data expectedVersion、最终 businessKey 和最终 variables。
 * @returns {Promise<object>} data 为唯一真实 Flowable 流程实例结果。
 */
export function submitProcessDraft(draftId, data) {
  return request({
    url: `/workflow/process/draft/${encodeURIComponent(draftId)}/submit`,
    method: 'post',
    headers: { repeatSubmit: false },
    data
  })
}
