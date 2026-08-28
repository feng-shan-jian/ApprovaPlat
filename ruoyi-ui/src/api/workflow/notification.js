import request from '@/utils/request'
import {
  notificationMailConfigReadRequest,
  notificationMailConfigSaveRequest,
  notificationMailConfigTestRequest,
  notificationNodeCatalogRequest,
  notificationOutboxCompensationRequest,
  notificationPolicySaveRequest,
  notificationProcessCatalogRequest
} from './notificationRequestConfig.js'

/**
 * 查询当前用户审批通知。
 * @param {'ALL'|'UNREAD'|'READ'} readStatus 阅读状态。
 * @param {number} pageNum 页码。
 * @param {number} pageSize 每页数量。
 * @returns {Promise<object>} data 含 items、total 和 unreadCount。
 */
export function listWorkflowNotifications(readStatus = 'ALL', pageNum = 1, pageSize = 20) {
  return request({ url: '/workflow/notification/inbox', method: 'get', params: { readStatus, pageNum, pageSize } })
}

/**
 * 标记一条审批通知已读。
 * @param {number|string} notificationId 通知主键。
 * @returns {Promise<object>} 服务端写入结果。
 */
export function markWorkflowNotificationRead(notificationId) {
  return request({ url: `/workflow/notification/inbox/${notificationId}/read`, method: 'post' })
}

/** @returns {Promise<object>} 当前用户全部审批通知已读结果。 */
export function markAllWorkflowNotificationsRead() {
  return request({ url: '/workflow/notification/inbox/read-all', method: 'post' })
}

/** @returns {Promise<object>} 当前用户站内和邮件偏好。 */
export function getWorkflowNotificationPreference() {
  return request({ url: '/workflow/notification/preference', method: 'get' })
}

/**
 * 保存当前用户通知偏好。
 * @param {{inboxEnabled:boolean,emailEnabled:boolean,expectedRevision:number}} data 偏好和乐观锁版本。
 * @returns {Promise<object>} 写后偏好。
 */
export function saveWorkflowNotificationPreference(data) {
  return request({ url: '/workflow/notification/preference', method: 'put', data })
}

/**
 * 催办运行流程的真实活动待办。
 * @param {{processInstanceId:string,reason:string}} data 流程实例和催办原因。
 * @returns {Promise<object>} data 仅含实际收件人数 recipientCount。
 */
export function urgeWorkflow(data) {
  return request({ url: '/workflow/notification/urge', method: 'post', data })
}

/** @returns {Promise<object>} 管理员通知策略列表。 */
export function listWorkflowNotificationPolicies() {
  return request({ url: '/workflow/notification/policies', method: 'get' })
}

/** @param {object} data 完整策略请求。 @returns {Promise<object>} 写后策略。 */
export function saveWorkflowNotificationPolicy(data) {
  return request(notificationPolicySaveRequest(data))
}

/**
 * 查询当前管理员有权维护的已部署流程目录。
 * @returns {Promise<object>} data 为 processDefinitionKey、processName 和 version 组成的流程数组。
 */
export function listWorkflowNotificationProcesses() {
  return request(notificationProcessCatalogRequest())
}

/**
 * 查询指定已部署流程的真实用户任务节点目录。
 * @param {string} processDefinitionKey 服务端流程目录返回的流程定义标识。
 * @returns {Promise<object>} data 为 taskDefinitionKey 和 taskName 组成的节点数组。
 */
export function listWorkflowNotificationNodes(processDefinitionKey) {
  return request(notificationNodeCatalogRequest(processDefinitionKey))
}

/**
 * 查询脱敏后的平台 SMTP 单例配置。
 * @returns {Promise<object>} data 只包含可展示字段、credentialConfigured 和 revision。
 */
export function getWorkflowNotificationMailConfig() {
  return request(notificationMailConfigReadRequest())
}

/**
 * 以乐观锁保存平台 SMTP 单例配置。
 * @param {object} data 当前 SMTP 字段、可选新授权码和 expectedRevision。
 * @returns {Promise<object>} 保存结果；调用方成功后必须重新查询正式配置。
 */
export function saveWorkflowNotificationMailConfig(data) {
  return request(notificationMailConfigSaveRequest(data))
}

/**
 * 使用弹窗中的未保存 SMTP 配置发送真实测试邮件。
 * @param {object} data 当前 SMTP 字段、可选新授权码、收件邮箱和 expectedRevision。
 * @returns {Promise<object>} data.success 表示测试邮件已由当前输入成功发送。
 */
export function testWorkflowNotificationMailConfig(data) {
  return request(notificationMailConfigTestRequest(data))
}

/**
 * 分页查询脱敏 outbox 运维列表。
 * @param {{pageNum:number,pageSize:number,status?:string,sourceType?:string,eventType?:string,channel?:string,keyword?:string,beginTime?:string,endTime?:string}} query 分页和筛选条件。
 * @returns {Promise<object>} 若依标准 rows、total 分页响应。
 */
export function listWorkflowNotificationOutbox(query) {
  return request({ url: '/workflow/notification/outbox', method: 'get', params: query })
}

/** @param {number|string} outboxId 死信主键。 @returns {Promise<object>} 补偿结果。 */
export function compensateWorkflowNotification(outboxId) {
  return request(notificationOutboxCompensationRequest(outboxId))
}
