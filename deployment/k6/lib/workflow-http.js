import { check } from 'k6'
import http from 'k6/http'
import { Rate } from 'k6/metrics'

/** 工作流 AjaxResult 传输层、业务码和响应结构的联合成功率。 */
export const workflowBusinessSuccess = new Rate('workflow_business_success')

/**
 * 读取有上下界的整数环境变量，避免错误参数造成无界负载。
 * @param {string} name 环境变量名。
 * @param {number} defaultValue 未配置时使用的默认值。
 * @param {number} minimum 允许的最小值。
 * @param {number} maximum 允许的最大值。
 * @returns {number} 已通过整数和边界校验的值。
 */
export function integerEnvironment(name, defaultValue, minimum, maximum) {
  const rawValue = (__ENV[name] || '').trim()
  const value = rawValue === '' ? defaultValue : Number(rawValue)
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} 必须是 ${minimum} 到 ${maximum} 之间的整数`)
  }
  return value
}

/**
 * 读取 k6 时长格式的安全上限；该值只用于防止挂死，不决定测试负载量。
 * @param {string} name 环境变量名。
 * @param {string} defaultValue 未配置时使用的默认时长。
 * @returns {string} k6 可识别的毫秒、秒或分钟时长。
 */
export function durationEnvironment(name, defaultValue) {
  const value = (__ENV[name] || defaultValue).trim()
  if (!/^[1-9]\d*(?:ms|s|m)$/.test(value)) {
    throw new Error(`${name} 必须使用大于 0 的 ms、s 或 m k6 时长格式`)
  }
  return value
}

/**
 * 规范业务 API 根地址，允许调用直连后端或带 /prod-api 的反向代理地址。
 * @param {string} rawValue 用户注入的 API 根地址。
 * @returns {string} 不带尾部斜杠的 HTTP(S) 根地址。
 */
export function normalizeBaseUrl(rawValue) {
  const value = (rawValue || '').trim().replace(/\/+$/, '')
  if (!/^https?:\/\/[^/\s]+(?:\/[^\s]*)?$/.test(value)) {
    throw new Error('FLOWABLE_K6_BASE_URL 必须是完整的 HTTP(S) API 根地址')
  }
  return value
}

/**
 * 解析 JSON 对象环境变量，表单变量只允许对象，禁止数组和标量混入协议。
 * @param {string} name 环境变量名。
 * @param {object} defaultValue 未配置时使用的默认对象。
 * @returns {object} 已完成对象类型校验的 JSON 值。
 */
export function objectEnvironment(name, defaultValue = {}) {
  const rawValue = (__ENV[name] || '').trim()
  if (rawValue === '') return defaultValue
  let value
  try {
    value = JSON.parse(rawValue)
  } catch (_) {
    throw new Error(`${name} 必须是合法 JSON 对象`)
  }
  if (value === null || Array.isArray(value) || typeof value !== 'object') {
    throw new Error(`${name} 必须是 JSON 对象`)
  }
  return value
}

/**
 * 去除可选 Bearer 前缀，保证请求头始终只拼接一次认证方案。
 * @param {string} rawToken 外部注入或登录返回的 Token。
 * @returns {string} 不含 Bearer 前缀的 Token。
 */
function normalizeToken(rawToken) {
  return (rawToken || '').trim().replace(/^Bearer\s+/i, '')
}

/**
 * 安全解析 AjaxResult；解析失败只返回 null，绝不把响应正文带入错误信息。
 * @param {object} response k6 HTTP 响应。
 * @returns {object|null} JSON 对象，或无法解析时的 null。
 */
export function parseAjaxPayload(response) {
  try {
    const value = response.json()
    return value && typeof value === 'object' && !Array.isArray(value) ? value : null
  } catch (_) {
    return null
  }
}

/**
 * 优先使用外部 Token；未提供 Token 时仅在 setup 中执行一次真实 /login。
 * @param {string} baseUrl 业务 API 根地址。
 * @param {{token: string, username: string, password: string}} credentials 进程环境注入的认证材料。
 * @param {string} label 固定角色标签，仅用于脱敏错误定位。
 * @returns {{token: string, shouldLogout: boolean}} 可供 VU 使用的 Token 和是否需要注销标志。
 */
export function loginOrUseToken(baseUrl, credentials, label) {
  const injectedToken = normalizeToken(credentials.token)
  if (injectedToken !== '') return { token: injectedToken, shouldLogout: false }

  const username = (credentials.username || '').trim()
  const password = credentials.password || ''
  if (username === '' || password === '') {
    throw new Error(`${label} 缺少 Token，且用户名或密码环境变量未完整注入`)
  }

  const response = http.post(
    `${baseUrl}/login`,
    JSON.stringify({ username, password, code: '', uuid: '' }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { gate: 'workflow-auth', operation: `${label}_login`, name: 'workflow_login' },
      timeout: durationEnvironment('FLOWABLE_K6_REQUEST_TIMEOUT', '10s')
    }
  )
  const payload = parseAjaxPayload(response)
  const token = normalizeToken(payload && payload.token)
  if (response.status !== 200 || !payload || Number(payload.code) !== 200 || token === '') {
    const businessCode = payload && payload.code !== undefined ? Number(payload.code) : 'invalid-json'
    throw new Error(`${label} 登录失败：HTTP ${response.status}，业务码 ${businessCode}`)
  }
  return { token, shouldLogout: true }
}

/**
 * 构造带真实 JWT 的请求参数；标签使用固定操作名，避免实例主键造成指标高基数。
 * @param {string} token 不含 Bearer 前缀的 JWT。
 * @param {string} gate 固定门禁名。
 * @param {string} operation 固定业务操作名。
 * @returns {object} k6 HTTP 请求参数。
 */
export function authenticatedParams(token, gate, operation) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    tags: { gate, operation, name: operation },
    timeout: durationEnvironment('FLOWABLE_K6_REQUEST_TIMEOUT', '10s')
  }
}

/**
 * 调用真实 AjaxResult API，不在异常或指标中保存请求体、Token 和响应正文。
 * @param {'GET'|'POST'|'DELETE'} method HTTP 方法。
 * @param {string} baseUrl 业务 API 根地址。
 * @param {string} path 以 / 开始的固定业务路径，可包含经过编码的查询参数。
 * @param {string} token 不含 Bearer 前缀的 JWT。
 * @param {string} gate 固定门禁名。
 * @param {string} operation 固定业务操作名。
 * @param {object|undefined} body 可选 JSON 请求体。
 * @returns {object} k6 HTTP 响应。
 */
export function ajaxRequest(method, baseUrl, path, token, gate, operation, body) {
  const requestBody = body === undefined ? null : JSON.stringify(body)
  return http.request(
    method,
    `${baseUrl}${path}`,
    requestBody,
    authenticatedParams(token, gate, operation)
  )
}

/**
 * 联合校验 HTTP 200、AjaxResult.code=200 和业务响应结构。
 * @param {object} response k6 HTTP 响应。
 * @param {string} gate 固定门禁名。
 * @param {string} operation 固定业务操作名。
 * @param {(payload: object) => boolean} shapeValidator 响应结构校验函数。
 * @returns {{ok: boolean, payload: object|null}} 校验结论和已解析正文。
 */
export function validateAjaxSuccess(response, gate, operation, shapeValidator = () => true) {
  const payload = parseAjaxPayload(response)
  const transportOk = response.status === 200
  const businessOk = payload !== null && Number(payload.code) === 200
  let shapeOk = false
  if (businessOk) {
    try {
      shapeOk = Boolean(shapeValidator(payload))
    } catch (_) {
      shapeOk = false
    }
  }
  const ok = transportOk && businessOk && shapeOk

  check(response, {
    [`${operation}: HTTP 200`]: () => transportOk,
    [`${operation}: AjaxResult.code 200`]: () => businessOk,
    [`${operation}: 响应结构合法`]: () => shapeOk
  })
  workflowBusinessSuccess.add(ok, { gate, operation })
  return { ok, payload }
}

/**
 * 注销由本轮 setup 创建的登录会话；外部注入 Token 的生命周期由调用方管理。
 * @param {string} baseUrl 业务 API 根地址。
 * @param {{token: string, shouldLogout: boolean}|undefined} authentication setup 返回的认证结果。
 * @returns {void} 不返回业务数据，注销失败也不输出 Token 或响应正文。
 */
export function logoutIfCreated(baseUrl, authentication) {
  if (!authentication || !authentication.shouldLogout || !authentication.token) return
  http.post(
    `${baseUrl}/logout`,
    null,
    authenticatedParams(authentication.token, 'workflow-auth', 'workflow_logout')
  )
}
