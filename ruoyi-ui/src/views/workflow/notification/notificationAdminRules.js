export const WORKFLOW_NOTIFICATION_PERMISSIONS = Object.freeze({
  manage: 'workflow:notification:manage',
  audit: 'workflow:notification:audit',
  retry: 'workflow:notification:retry',
  mailManage: 'workflow:notification:mailManage'
})

/**
 * 创建不含任何正式凭据的 SMTP 表单初始值。
 * @returns {object} 可编辑的未保存 SMTP 草稿。
 */
export function createEmptyMailConfigForm() {
  return {
    smtpHost: '',
    smtpPort: 587,
    encryptionMode: 'STARTTLS',
    username: '',
    credential: '',
    fromAddress: '',
    senderName: 'ApprovaPlat 审批通知'
  }
}

/**
 * 根据实际权限选择通知管理页首个可读取页签。
 * @param {(permission:string) => boolean} hasPermission 权限查询函数。
 * @returns {'policies'|'outbox'|''} 策略、投递或无可读页签。
 */
export function resolveNotificationInitialTab(hasPermission) {
  if (hasPermission(WORKFLOW_NOTIFICATION_PERMISSIONS.manage)) return 'policies'
  if (hasPermission(WORKFLOW_NOTIFICATION_PERMISSIONS.audit)) return 'outbox'
  return ''
}

/**
 * 校验流程范围必须来自当前用户有权读取的真实流程目录。
 * @param {string} scopeType DEFAULT、PROCESS 或 NODE。
 * @param {string} processDefinitionKey 当前选择的流程定义标识。
 * @param {object[]} processOptions 服务端授权目录返回的流程选项。
 * @returns {string} 合法时为空字符串，否则返回稳定校验语义。
 */
export function processCatalogValidationError(scopeType, processDefinitionKey, processOptions) {
  if (scopeType === 'DEFAULT') return ''
  const authorized = Array.isArray(processOptions) && processOptions
    .some(item => item?.processDefinitionKey === processDefinitionKey)
  return authorized ? '' : '请选择当前有权管理的真实流程'
}

/**
 * 校验节点范围必须来自当前流程的真实节点目录，目录失败时保持失败关闭。
 * @param {string} scopeType DEFAULT、PROCESS 或 NODE。
 * @param {string} taskDefinitionKey 当前选择的节点定义标识。
 * @param {object[]} nodeOptions 服务端授权目录返回的节点选项。
 * @param {string} nodeLoadError 当前流程节点目录的独立加载错误。
 * @returns {string} 合法时为空字符串，否则返回稳定校验语义。
 */
export function nodeCatalogValidationError(scopeType, taskDefinitionKey, nodeOptions, nodeLoadError) {
  if (scopeType !== 'NODE') return ''
  if (nodeLoadError) return '节点目录加载失败，请重新加载后再保存'
  const authorized = Array.isArray(nodeOptions) && nodeOptions
    .some(item => item?.taskDefinitionKey === taskDefinitionKey)
  return authorized ? '' : '请选择当前流程中的真实节点'
}
