import request from '@/utils/request'

/**
 * 由当前候选用户认领未分配活动任务。
 * @param {string} taskId Flowable 任务主键。
 * @returns {Promise<object>} 认领和审计原子写入结果。
 */
export function claimTask(taskId) {
  return request({ url: '/workflow/task/claim', method: 'post', data: { taskId } })
}

/**
 * 由当前办理人取消本人认领。
 * @param {string} taskId Flowable 任务主键。
 * @returns {Promise<object>} 取消认领和审计原子写入结果。
 */
export function unclaimTask(taskId) {
  return request({ url: '/workflow/task/unClaim', method: 'post', data: { taskId } })
}

/**
 * 由当前受托人提交真实办理意见并完成 PENDING 委派。
 * @param {{taskId: string, comment: string, copyUserIds?: number[]}} data 委派办结请求。
 * @returns {Promise<object>} 状态迁移、审计和抄送记录原子写入结果。
 */
export function resolveTask(data) {
  return request({ url: '/workflow/task/resolve', method: 'post', data })
}

/**
 * 将当前任务委派给正式启用用户。
 * @param {object} data taskId、目标 userId、委派意见 comment 和可选 copyUserIds。
 * @returns {Promise<object>} 委派状态、审计和抄送记录原子写入结果。
 */
export function delegateTask(data) {
  return request({ url: '/workflow/task/delegate', method: 'post', data })
}

/**
 * 将当前任务永久转办给正式启用用户。
 * @param {object} data taskId、目标 userId、转办意见 comment 和可选 copyUserIds。
 * @returns {Promise<object>} 转办状态、审计和抄送记录原子写入结果。
 */
export function transferTask(data) {
  return request({ url: '/workflow/task/transfer', method: 'post', data })
}

/**
 * 由发起人或受控管理员取消运行流程。
 * @param {object} data 流程实例主键和取消原因。
 * @returns {Promise<object>} 流程取消和审计结果。
 */
export function cancelProcess(data) {
  return request({ url: '/workflow/task/stopProcess', method: 'post', data })
}

/**
 * 撤回当前用户已办且后继尚未处理的任务。
 * @param {object} data 历史任务主键和撤回原因。
 * @returns {Promise<object>} 执行树校验后的撤回结果。
 */
export function revokeTask(data) {
  return request({ url: '/workflow/task/revokeProcess', method: 'post', data })
}

/**
 * 查询任务参与者可见的安全变量投影。
 * @param {string} taskId 活动或历史任务主键。
 * @returns {Promise<object>} data 为允许展示的变量映射。
 */
export function getTaskVariables(taskId) {
  return request({
    url: `/workflow/task/processVariables/${encodeURIComponent(taskId)}`,
    method: 'get'
  })
}

/**
 * 查询当前办理任务所在动态并行多实例根的正式成员状态。
 * @param {string} taskId 当前登录用户真实办理的 Flowable 活动任务主键。
 * @returns {Promise<object>} data 包含 mode、activityId、revision 和有序 members。
 */
export function getMultiInstanceState(taskId) {
  return request({
    url: `/workflow/task/multiInstance/${encodeURIComponent(taskId)}`,
    method: 'get'
  })
}

/**
 * 按服务端 revision 动态增加或移除同一并行多实例根的活动成员。
 * @param {object} data taskId、ADD/REMOVE、expectedRevision、comment 以及 userIds 或 targetTaskId。
 * @returns {Promise<object>} data 为引擎写后重新对账的最新多实例状态。
 */
export function adjustMultiInstance(data) {
  return request({ url: '/workflow/task/multiInstance/adjust', method: 'post', data })
}

/**
 * 完成当前用户合法办理的任务。
 * @param {object} data taskId、comment、variables、可选 copyUserIds、nextUserIds，以及仅动态多实例需要的 expectedRevision。
 * @returns {Promise<object>} 引擎完成、动态下一办理人、审计和抄送同事务写入结果。
 */
export function completeTask(data) {
  return request({ url: '/workflow/task/complete', method: 'post', data })
}

/**
 * 由当前办理人将普通、并行或多实例流程整实例原子驳回为 rejected 终态。
 * @param {object} data taskId、驳回意见 comment 和可选 copyUserIds。
 * @returns {Promise<object>} 执行树校验、终止状态、审计和抄送原子写入结果。
 */
export function rejectTask(data) {
  return request({ url: '/workflow/task/reject', method: 'post', data })
}

/**
 * 将整条申请直接退回发起人修改。
 * @param {object} data taskId、退回意见 comment 和可选 copyUserIds。
 * @returns {Promise<object>} 发起人任务创建、状态迁移、审计和抄送原子写入结果。
 */
export function returnTask(data) {
  return request({ url: '/workflow/task/return', method: 'post', data })
}

/**
 * 由流程发起人保存原表单修改，并重新开放首个审批节点。
 * @param {object} data taskId 和覆盖后的原开始表单 variables。
 * @returns {Promise<object>} 表单、附件、审计、办理配置和流程状态同事务恢复结果。
 */
export function resubmitApplication(data) {
  return request({ url: '/workflow/task/resubmit', method: 'post', data })
}

/**
 * 查询对象授权后的流程实例图形资源。
 * @param {string} processInstanceId 流程实例主键。
 * @returns {Promise<Blob>} 后端生成的流程图二进制内容。
 */
export function getProcessDiagram(processInstanceId) {
  return request({
    url: `/workflow/task/diagram/${encodeURIComponent(processInstanceId)}`,
    method: 'get',
    responseType: 'blob'
  })
}
