import request from '@/utils/request'

/**
 * 查询当前用户可发起的最新激活流程定义。
 * @param {object} query 定义过滤和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listStartableProcesses(query) {
  return request({ url: '/workflow/process/list', method: 'get', params: query })
}

/**
 * 查询当前用户真实发起的流程实例。
 * @param {object} query 实例过滤、日期范围和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listOwnedProcesses(query) {
  return request({ url: '/workflow/process/ownList', method: 'get', params: query })
}

/**
 * 查询流程管理员可运维的全部用户流程实例。
 * @param {object} query 实例、定义、发起人、日期范围和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listManagedProcesses(query) {
  return request({ url: '/workflow/process/manageList', method: 'get', params: query })
}

/**
 * 查询当前用户作为办理人的活动任务。
 * @param {object} query 任务过滤、日期范围和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listAssignedTasks(query) {
  return request({ url: '/workflow/process/todoList', method: 'get', params: query })
}

/**
 * 查询当前用户或其角色、部门可认领的活动任务。
 * @param {object} query 任务过滤、日期范围和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listClaimableTasks(query) {
  return request({ url: '/workflow/process/claimList', method: 'get', params: query })
}

/**
 * 查询当前用户真实完成的历史任务。
 * @param {object} query 任务过滤、日期范围和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listCompletedTasks(query) {
  return request({ url: '/workflow/process/finishedList', method: 'get', params: query })
}

/**
 * 查询正式业务表中抄送给当前用户的记录。
 * @param {object} query 抄送记录过滤和分页参数，接收用户由后端固定。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listCopiedProcesses(query) {
  return request({ url: '/workflow/process/copyList', method: 'get', params: query })
}

/**
 * 查询定义、部署和可选实例关系校验后的开始表单快照。
 * @param {object} query definitionId、deployId 和可选 procInsId。
 * @returns {Promise<object>} data 为不可变部署表单快照的响应。
 */
export function getProcessForm(query) {
  return request({ url: '/workflow/process/getProcessForm', method: 'get', params: query })
}

/**
 * 发起经过 starter、快照和变量白名单校验的真实流程实例。
 * @param {string} processDefinitionId Flowable 流程定义主键。
 * @param {object} data 可选业务主键和开始表单变量。
 * @returns {Promise<object>} data 为新流程实例快照的响应。
 */
export function startProcess(processDefinitionId, data) {
  return request({
    url: `/workflow/process/start/${encodeURIComponent(processDefinitionId)}`,
    method: 'post',
    data: { ...data, processDefinitionId }
  })
}

/**
 * 受控删除一个或多个已结束且无业务引用的流程实例历史。
 * @param {Array<string>|string} processInstanceIds 流程实例主键集合或单个主键。
 * @returns {Promise<object>} 引用与状态校验后的删除结果。
 */
export function deleteProcessInstances(processInstanceIds) {
  const ids = Array.isArray(processInstanceIds) ? processInstanceIds : [processInstanceIds]
  const pathIds = ids.map(id => encodeURIComponent(id)).join(',')
  return request({ url: `/workflow/process/instance/${pathIds}`, method: 'delete' })
}

/**
 * 查询可发起定义或对象授权实例对应的安全 BPMN XML。
 * @param {string} processDefinitionId Flowable 流程定义主键。
 * @param {string|undefined} processInstanceId 可选流程实例主键。
 * @returns {Promise<object>} data 为 BPMN XML 的响应。
 */
export function getProcessBpmnXml(processDefinitionId, processInstanceId) {
  return request({
    url: `/workflow/process/bpmnXml/${encodeURIComponent(processDefinitionId)}`,
    method: 'get',
    params: processInstanceId ? { procInsId: processInstanceId } : undefined
  })
}

/**
 * 查询对象授权后的完整流程详情。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string|undefined} taskId 可选活动或历史任务主键。
 * @returns {Promise<object>} data 为表单、变量、轨迹、意见和 Viewer 状态。
 */
export function getProcessDetail(processInstanceId, taskId) {
  return request({
    url: '/workflow/process/detail',
    method: 'get',
    params: { procInsId: processInstanceId, taskId }
  })
}
