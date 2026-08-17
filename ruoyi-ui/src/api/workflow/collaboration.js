import request from '@/utils/request'

/** @param {object} query 分页和领域筛选；@returns {Promise<object>} 脱敏入站消息 rows/total。 */
export function listCollaborationInbound(query) {
  return request({ url: '/workflow/collaboration/inbound', method: 'get', params: query })
}

/** @param {object} query 分页和领域筛选；@returns {Promise<object>} 脱敏 outbox rows/total。 */
export function listCollaborationOutbox(query) {
  return request({ url: '/workflow/collaboration/outbox', method: 'get', params: query })
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
