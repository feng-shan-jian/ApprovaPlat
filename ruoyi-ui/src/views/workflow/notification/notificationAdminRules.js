export const WORKFLOW_NOTIFICATION_PERMISSIONS = Object.freeze({
  manage: 'workflow:notification:manage',
  audit: 'workflow:notification:audit',
  retry: 'workflow:notification:retry',
  mailManage: 'workflow:notification:mailManage'
})

const SUPPORTED_ENCRYPTION_MODES = Object.freeze(['NONE', 'STARTTLS', 'SSL'])

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
 * 创建不含凭据的 SMTP 认证身份基线。
 * @returns {{smtpHost:string,smtpPort:number|null,encryptionMode:string,username:string}} 空认证身份。
 */
export function createEmptyMailAuthenticationIdentity() {
  return { smtpHost: '', smtpPort: null, encryptionMode: '', username: '' }
}

/**
 * 规范化决定授权码归属的 SMTP 认证身份，From 地址和发件人名称不参与比较。
 * @param {object} source 当前表单或最近一次后端回读表单。
 * @returns {{smtpHost:string,smtpPort:number|null,encryptionMode:string,username:string}} 可比较的公开认证身份。
 */
export function normalizeMailAuthenticationIdentity(source) {
  const parsedPort = Number(source?.smtpPort)
  return {
    smtpHost: String(source?.smtpHost || '').trim().toLowerCase(),
    smtpPort: Number.isInteger(parsedPort) ? parsedPort : null,
    encryptionMode: String(source?.encryptionMode || '').trim().toUpperCase(),
    // SMTP 登录账号可能区分大小写，只去除输入两端空白，不擅自折叠大小写。
    username: String(source?.username || '').trim()
  }
}

/**
 * 比较当前 SMTP 认证身份与后端回读基线，决定是否必须重新输入授权码。
 * @param {object} loadedIdentity 最近一次后端回读并规范化的认证身份。
 * @param {object} currentSource 当前可编辑 SMTP 表单。
 * @returns {boolean} 主机、端口、加密方式或登录账号任一变化时为 true。
 */
export function hasMailAuthenticationIdentityChanged(loadedIdentity, currentSource) {
  const currentIdentity = normalizeMailAuthenticationIdentity(currentSource)
  const baseline = normalizeMailAuthenticationIdentity(loadedIdentity)
  return Object.keys(baseline).some(key => currentIdentity[key] !== baseline[key])
}

/**
 * 只从查询响应提取前端允许展示的 SMTP 字段，主动丢弃所有未知和敏感字段。
 * @param {object|null|undefined} data 后端 mail-config 脱敏响应。
 * @returns {{form:object,meta:object}} 白名单 SMTP 表单和配置状态。
 */
export function normalizeMailConfigResponse(data) {
  const source = data && typeof data === 'object' ? data : {}
  const configured = source.configured === true
  const responsePort = Number(source.smtpPort)
  return {
    form: {
      smtpHost: typeof source.smtpHost === 'string' ? source.smtpHost : '',
      smtpPort: Number.isInteger(responsePort) && responsePort >= 1 && responsePort <= 65535
        ? responsePort
        : (configured ? null : 587),
      encryptionMode: SUPPORTED_ENCRYPTION_MODES.includes(source.encryptionMode)
        ? source.encryptionMode
        : (configured ? '' : 'STARTTLS'),
      username: typeof source.username === 'string' ? source.username : '',
      credential: '',
      fromAddress: typeof source.fromAddress === 'string' ? source.fromAddress : '',
      senderName: typeof source.senderName === 'string'
        ? source.senderName
        : (configured ? '' : 'ApprovaPlat 审批通知')
    },
    meta: {
      configured,
      credentialConfigured: source.credentialConfigured === true,
      revision: Number.isInteger(Number(source.revision)) && Number(source.revision) >= 0
        ? Number(source.revision)
        : 0
    }
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
 * 以服务端状态投影和当前用户权限共同判断是否允许发起死信补偿。
 * @param {boolean} canRetry 当前用户是否具有补偿权限。
 * @param {object} row 当前脱敏 outbox 行。
 * @returns {boolean} 有权限、服务端明确允许且当前仍为死信时返回 true。
 */
export function canCompensateNotificationOutbox(canRetry, row) {
  const allowed = row?.canCompensate === true || Number(row?.canCompensate) === 1
  return canRetry === true && allowed && row?.status === 'DEAD_LETTER'
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
