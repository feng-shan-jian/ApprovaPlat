/** 审批办理用户目录固定使用的后端能力值。 */
export const APPROVAL_USER_CAPABILITY = 'approval'

/** 候选身份目录固定使用的完整认领能力值。 */
export const CLAIM_IDENTITY_CAPABILITY = 'claim'

/** 候选认领目录允许查询的正式身份类型。 */
const CLAIM_IDENTITY_TYPES = Object.freeze(['user', 'role', 'dept'])

/**
 * 构造审批办理用户目录查询，调用方不能把类型或能力降级为通用用户目录。
 * @param {unknown} query keyword、pageNum 和 pageSize 等分页检索参数。
 * @returns {object} 固定包含 type=user 和 capability=approval 的新查询对象。
 */
export function buildApprovalUserQuery(query = {}) {
  const safeQuery = query && typeof query === 'object' && !Array.isArray(query) ? query : {}
  return {
    ...safeQuery,
    type: 'user',
    capability: APPROVAL_USER_CAPABILITY
  }
}

/**
 * 构造候选认领身份目录查询，调用方不能覆盖能力范围或提交伪身份类型。
 * @param {unknown} query 必须包含 type=user、role 或 dept，并可携带分页和检索参数。
 * @returns {object} 固定包含规范身份类型和 capability=claim 的新查询对象。
 */
export function buildClaimIdentityQuery(query = {}) {
  const safeQuery = query && typeof query === 'object' && !Array.isArray(query) ? query : {}
  // identityType 是后端真实目录类型；设计器使用的 group 聚合类型不得直接进入 API。
  const identityType = String(safeQuery.type || '').trim()
  if (!CLAIM_IDENTITY_TYPES.includes(identityType)) {
    throw new TypeError('候选身份类型必须为 user、role 或 dept')
  }
  return {
    ...safeQuery,
    type: identityType,
    capability: CLAIM_IDENTITY_CAPABILITY
  }
}
