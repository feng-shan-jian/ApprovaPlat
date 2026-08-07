import request from '@/utils/request'

/**
 * 查询每个模型标识的最新版本。
 * @param {object} query 模型名称、标识、分类和分页参数。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listModels(query) {
  return request({ url: '/workflow/model/list', method: 'get', params: query })
}

/**
 * 查询指定模型标识的版本历史。
 * @param {object} query 必含 modelKey 的分页过滤条件。
 * @returns {Promise<object>} 若依分页响应。
 */
export function listModelHistory(query) {
  return request({ url: '/workflow/model/historyList', method: 'get', params: query })
}

/**
 * 查询模型元数据详情。
 * @param {string} modelId Flowable 模型主键。
 * @returns {Promise<object>} data 为模型详情的响应。
 */
export function getModel(modelId) {
  return request({
    url: `/workflow/model/${encodeURIComponent(modelId)}`,
    method: 'get'
  })
}

/**
 * 查询经过后端安全校验的模型 BPMN XML。
 * @param {string} modelId Flowable 模型主键。
 * @returns {Promise<object>} data 为 BPMN XML 的响应。
 */
export function getModelBpmnXml(modelId) {
  return request({
    url: `/workflow/model/bpmnXml/${encodeURIComponent(modelId)}`,
    method: 'get'
  })
}

/**
 * 新增未部署流程模型和最小 BPMN 资源。
 * @param {object} data 模型名称、标识、分类、描述和表单配置。
 * @returns {Promise<object>} data.modelId 为真实模型主键的响应。
 */
export function createModel(data) {
  return request({ url: '/workflow/model', method: 'post', data })
}

/**
 * 修改未部署模型的受控元数据。
 * @param {object} data 模型主键及允许修改的元数据。
 * @returns {Promise<object>} 修改结果。
 */
export function updateModel(data) {
  return request({ url: '/workflow/model', method: 'put', data })
}

/**
 * 原子保存 BPMN XML；已部署或历史版本由后端自动创建新模型版本。
 * @param {object} data requestId、modelId、bpmnXml 和兼容旧客户端的 newVersion。
 * @returns {Promise<object>} data.modelId 为实际保存版本主键的响应。
 */
export function saveModel(data) {
  return request({ url: '/workflow/model/save', method: 'post', data })
}

/**
 * 使用保存和部署共同服务端门禁校验 BPMN，不产生模型或部署写副作用。
 * @param {string} bpmnXml 完整 BPMN 2.0 XML。
 * @returns {Promise<object>} data.valid 和 data.issues 为结构化诊断结果。
 */
export function validateModelBpmn(bpmnXml) {
  return request({
    url: '/workflow/model/validate',
    method: 'post',
    data: { bpmnXml },
    headers: { repeatSubmit: false }
  })
}

/**
 * 将指定历史模型复制为新的最高版本。
 * @param {string} modelId 待提升的历史模型主键。
 * @returns {Promise<object>} data.modelId 为新版本主键的响应。
 */
export function promoteModel(modelId) {
  return request({
    url: '/workflow/model/latest',
    method: 'post',
    params: { modelId }
  })
}

/**
 * 删除一个或多个未部署且未被定义引用的模型。
 * @param {Array<string>|string} modelIds 模型主键集合或单个主键。
 * @returns {Promise<object>} 受引用检查保护的删除结果。
 */
export function deleteModels(modelIds) {
  const ids = Array.isArray(modelIds) ? modelIds : [modelIds]
  const pathIds = ids.map(id => encodeURIComponent(id)).join(',')
  return request({ url: `/workflow/model/${pathIds}`, method: 'delete' })
}

/**
 * 部署模型并在同一后端事务固化节点表单快照。
 * @param {string} modelId 待部署模型主键。
 * @returns {Promise<object>} data.deploymentId 为真实部署主键的响应。
 */
export function deployModel(modelId) {
  return request({
    url: '/workflow/model/deploy',
    method: 'post',
    params: { modelId }
  })
}
