export const NOTIFICATION_API_PATHS = Object.freeze({
  policies: '/workflow/notification/policies',
  processCatalog: '/workflow/notification/catalog/processes',
  mailConfig: '/workflow/notification/mail-config',
  mailConfigTest: '/workflow/notification/mail-config/test',
  outbox: '/workflow/notification/outbox'
})

/**
 * 构造由页面按稳定业务子码提示的通知策略保存请求。
 * @param {object} data 完整策略请求。
 * @returns {object} Axios 请求配置。
 */
export function notificationPolicySaveRequest(data) {
  return {
    url: NOTIFICATION_API_PATHS.policies,
    method: 'put',
    data,
    suppressErrorMessage: true
  }
}

/**
 * 构造当前管理员有权维护的真实流程目录请求。
 * @returns {object} Axios 请求配置。
 */
export function notificationProcessCatalogRequest() {
  return { url: NOTIFICATION_API_PATHS.processCatalog, method: 'get' }
}

/**
 * 构造指定流程的真实用户任务节点目录请求。
 * @param {string} processDefinitionKey 服务端流程目录返回的流程定义标识。
 * @returns {object} Axios 请求配置。
 */
export function notificationNodeCatalogRequest(processDefinitionKey) {
  return {
    url: `${NOTIFICATION_API_PATHS.processCatalog}/${encodeURIComponent(processDefinitionKey)}/nodes`,
    method: 'get',
    suppressErrorMessage: true
  }
}

/**
 * 构造脱敏 SMTP 单例配置读取请求。
 * @returns {object} Axios 请求配置。
 */
export function notificationMailConfigReadRequest() {
  return {
    url: NOTIFICATION_API_PATHS.mailConfig,
    method: 'get',
    suppressErrorMessage: true
  }
}

/**
 * 构造 SMTP 保存请求，禁止明文授权码进入通用会话重复提交缓存。
 * @param {object} data 当前 SMTP 字段、可选新授权码和 expectedRevision。
 * @returns {object} Axios 请求配置。
 */
export function notificationMailConfigSaveRequest(data) {
  return {
    url: NOTIFICATION_API_PATHS.mailConfig,
    method: 'put',
    data,
    suppressErrorMessage: true,
    headers: { repeatSubmit: false }
  }
}

/**
 * 构造 SMTP 测试请求，禁止缓存明文授权码并保留受控网络超时分类窗口。
 * @param {object} data 当前 SMTP 字段、收件邮箱和 expectedRevision。
 * @returns {object} Axios 请求配置。
 */
export function notificationMailConfigTestRequest(data) {
  return {
    url: NOTIFICATION_API_PATHS.mailConfigTest,
    method: 'post',
    data,
    suppressErrorMessage: true,
    headers: { repeatSubmit: false },
    timeout: 30000
  }
}

/**
 * 构造死信补偿请求，由页面按真实状态冲突子码输出唯一提示。
 * @param {number|string} outboxId 死信主键。
 * @returns {object} Axios 请求配置。
 */
export function notificationOutboxCompensationRequest(outboxId) {
  return {
    url: `${NOTIFICATION_API_PATHS.outbox}/${outboxId}/compensate`,
    method: 'post',
    suppressErrorMessage: true
  }
}
