/**
 * 将详情 API 的开始表单、当前节点表单和历史节点表单拆分为互斥业务语义。
 * @param {object} payload 已完成实例和任务关系校验的详情 API 响应。
 * @returns {{applicationForm: object|null,nodeTaskForm: object|null,historyForms: object[],applicationMissing: boolean,returnedApplication: boolean,defaultTab: string}} 页面表单分组与默认页签。
 */
export function normalizeDetailFormSemantics(payload) {
  const processForms = Array.isArray(payload?.processFormList) ? payload.processFormList : []
  const startForms = processForms.filter(form => form?.taskId == null)
  if (startForms.length > 1) throw new Error('申请表单快照不唯一')

  const startForm = startForms[0] || null
  const returnedApplication = payload?.processStatus === 'returned'
    && Boolean(payload?.currentTask?.active)
  if (returnedApplication && (!startForm || !sameStartForm(startForm, payload.currentTaskForm))) {
    throw new Error('退回申请表单快照关系不一致')
  }

  const applicationForm = returnedApplication ? payload.currentTaskForm : startForm
  const nodeTaskForm = returnedApplication ? null : (payload?.currentTaskForm || null)
  const currentTaskId = String(payload?.currentTask?.taskId || '')
  const historyForms = processForms.filter(form => form?.taskId != null
    && String(form.taskId) !== currentTaskId)
  const defaultTab = !startForm
    ? 'diagram'
    : returnedApplication
      ? 'applicationForm'
      : nodeTaskForm
        ? 'nodeTaskForm'
        : 'applicationForm'
  return {
    applicationForm,
    nodeTaskForm,
    historyForms,
    applicationMissing: !startForm,
    returnedApplication,
    defaultTab
  }
}

/**
 * 核验退回可编辑表单确实是唯一开始提交快照的当前任务投影。
 * @param {object} startForm processFormList 中 taskId 为空的开始提交快照。
 * @param {object|null} currentTaskForm 后端绑定申请人任务返回的可编辑表单。
 * @returns {boolean} 部署来源、表单和开始节点身份全部一致时返回 true。
 */
function sameStartForm(startForm, currentTaskForm) {
  if (!currentTaskForm) return false
  return ['sourceType', 'formId', 'formKey', 'nodeKey'].every(field =>
    String(startForm?.[field] ?? '') === String(currentTaskForm?.[field] ?? ''))
}
