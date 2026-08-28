/**
 * 创建保留后端稳定业务码和安全子码的前端错误。
 * @param {number|string} code 后端 AjaxResult 返回的业务状态码。
 * @param {string} message 经统一错误码表规范后的用户提示。
 * @param {unknown} subCode 后端可选的稳定机器子码。
 * @returns {Error & {code:number,subCode?:string}} 不携带响应正文的业务错误。
 */
export function createBusinessError(code, message, subCode) {
  const error = new Error(message)
  error.name = 'BusinessError'
  error.code = Number(code)
  // 子码只保留有限机器字符，禁止把任意响应正文或诊断信息带入页面错误对象。
  const normalizedSubCode = typeof subCode === 'string' ? subCode.trim() : ''
  if (/^[A-Z][A-Z0-9_]{0,63}$/.test(normalizedSubCode)) error.subCode = normalizedSubCode
  return error
}

/**
 * 将可能携带请求体的 Axios 错误收敛为不含 config、request 和 data 的安全错误。
 * @param {unknown} sourceError 原始 Axios 或请求构造错误。
 * @param {string} message 可安全展示的错误语义。
 * @param {boolean} preserveSuppress 是否保留页面负责提示的控制位。
 * @returns {Error & {suppressErrorMessage?:boolean}} 只保留提示控制位的安全错误。
 */
export function createSafeRequestError(sourceError, message = '请求发送失败', preserveSuppress = false) {
  const safeError = new Error(message)
  safeError.name = 'RequestError'
  if (preserveSuppress) {
    safeError.suppressErrorMessage = sourceError?.suppressErrorMessage === true ||
      sourceError?.config?.suppressErrorMessage === true
  }
  return safeError
}

/**
 * 将真实非 2xx AjaxResult 规范为有界提示和稳定子码的安全业务错误。
 * @param {unknown} responseData Axios response.data。
 * @param {unknown} httpStatus 真实 HTTP 状态码。
 * @param {Record<string|number,string>} messages 前端统一错误码表。
 * @returns {Error|null} 可识别响应返回安全业务错误，否则返回 null。
 */
export function createHttpBusinessError(responseData, httpStatus, messages) {
  if (!responseData || typeof responseData !== 'object') return null
  const responseCode = Number(responseData.code || httpStatus)
  if (!Number.isFinite(responseCode)) return null
  const backendMessage = typeof responseData.msg === 'string' ? responseData.msg.trim() : ''
  const message = (backendMessage || messages?.[responseCode] || messages?.default || '系统异常')
    .slice(0, 180)
  return createBusinessError(responseCode, message, responseData.subCode)
}
