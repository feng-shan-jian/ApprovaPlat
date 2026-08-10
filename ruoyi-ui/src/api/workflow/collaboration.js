import request from '@/utils/request'

/** @returns {Promise<object>} 最近 1000 条脱敏入站消息。 */
export function listCollaborationInbound() {
  return request({ url: '/workflow/collaboration/inbound', method: 'get' })
}

/** @returns {Promise<object>} 最近 1000 条脱敏事务 outbox。 */
export function listCollaborationOutbox() {
  return request({ url: '/workflow/collaboration/outbox', method: 'get' })
}

/** @param {string} messageId 消息主键。 @returns {Promise<object>} 逐次状态审计。 */
export function listCollaborationAudit(messageId) {
  return request({ url: `/workflow/collaboration/${messageId}/audit`, method: 'get' })
}

/** @param {string} messageId 入站消息主键。 @returns {Promise<object>} 补偿后状态。 */
export function retryCollaborationInbound(messageId) {
  return request({ url: `/workflow/collaboration/inbound/${messageId}/retry`, method: 'post' })
}

/** @param {string} messageId outbox 主键。 @returns {Promise<object>} 补偿后状态。 */
export function retryCollaborationOutbox(messageId) {
  return request({ url: `/workflow/collaboration/outbox/${messageId}/retry`, method: 'post' })
}

/** @param {string} messageId outbox 主键。 @returns {Promise<object>} 取消后状态。 */
export function cancelCollaborationOutbox(messageId) {
  return request({ url: `/workflow/collaboration/outbox/${messageId}/cancel`, method: 'post' })
}
