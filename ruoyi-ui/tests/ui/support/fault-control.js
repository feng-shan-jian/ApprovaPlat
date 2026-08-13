const DEFAULT_CONTROL_URL = 'http://127.0.0.1:18081'

/**
 * 解析并限制 UI 故障控制服务地址，避免测试脚本把控制请求发送到非本机目标。
 * @returns {string} 不带末尾斜杠的本机 HTTP 控制服务地址。
 */
function resolveControlUrl() {
  const rawUrl = process.env.FLOWABLE_E2E_FAULT_CONTROL_URL?.trim() || DEFAULT_CONTROL_URL
  const parsed = new URL(rawUrl)
  if (parsed.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(parsed.hostname)
    || parsed.username || parsed.password || parsed.search || parsed.hash) {
    throw new Error('FLOWABLE_E2E_FAULT_CONTROL_URL 必须是无凭据的本机 HTTP 地址')
  }
  return parsed.href.replace(/\/$/u, '')
}

/**
 * 校验当前进程确实由 fault 阶段总控启动，防止普通回归误切换依赖连接。
 * @returns {{controlUrl:string}} 已通过校验的故障运行环境。
 */
export function requireFaultRuntime() {
  if (process.env.FLOWABLE_E2E_FAULT_PROXY_ENABLED?.trim().toLowerCase() !== 'true') {
    throw new Error('故障用例必须通过 run-ui-tests.ps1 -Phase fault 或 -Phase all 启动')
  }
  return { controlUrl: resolveControlUrl() }
}

/**
 * 调用本机故障控制接口并返回结构化响应。
 * @param {'GET'|'POST'|'DELETE'} method HTTP 方法。
 * @param {string} pathname 控制服务相对路径。
 * @param {object|undefined} body 可选模式切换请求体。
 * @returns {Promise<object>} 控制服务 JSON 响应。
 */
async function requestControl(method, pathname, body = undefined) {
  let lastError
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      const response = await fetch(`${resolveControlUrl()}${pathname}`, {
        method,
        // 后端强制重启会关闭并发 SMTP socket；控制请求禁用复用，避免陈旧本机连接遮蔽业务断言。
        headers: {
          Connection: 'close',
          ...(body ? { 'Content-Type': 'application/json' } : {})
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(5_000)
      })
      const payload = await response.json().catch(() => ({}))
      if (!response.ok) {
        throw new Error(`故障控制接口 ${pathname} 返回 HTTP ${response.status}：${String(payload?.message || '')}`)
      }
      return payload
    } catch (error) {
      lastError = error
      if (attempt < 3) await new Promise(resolve => setTimeout(resolve, attempt * 150))
    }
  }
  throw lastError
}

/**
 * 原子切换一组本机故障模式。
 * @param {{mysqlMode?:'ok'|'disconnect'|'timeout',redisMode?:'ok'|'disconnect'|'timeout',httpMode?:'ok'|'server-error'|'timeout'|'disconnect'|'duplicate',smtpMode?:'accept'|'reject'|'timeout',attachmentMode?:'writable'|'read-only'}} modes 需要变更的依赖模式。
 * @returns {Promise<object>} 切换后的完整模式状态。
 */
export async function setFaultModes(modes) {
  const allowedKeys = new Set([
    'mysqlMode', 'redisMode', 'httpMode', 'smtpMode', 'attachmentMode'
  ])
  const entries = Object.entries(modes || {})
  if (!entries.length || entries.some(([key]) => !allowedKeys.has(key))) {
    throw new Error('故障模式请求必须包含受支持的依赖字段')
  }
  return requestControl('POST', '/mode', Object.fromEntries(entries))
}

/**
 * 切换单个数据库或缓存依赖的透明代理模式。
 * @param {'mysql'|'redis'} dependency 正式依赖名称。
 * @param {'ok'|'disconnect'|'timeout'} mode 透明代理行为。
 * @returns {Promise<object>} 切换后的完整模式状态。
 */
export async function setDependencyMode(dependency, mode) {
  if (!['mysql', 'redis'].includes(dependency) || !['ok', 'disconnect', 'timeout'].includes(mode)) {
    throw new Error('数据库故障控制参数不合法')
  }
  return setFaultModes({ [`${dependency}Mode`]: mode })
}

/**
 * 切换当前 runId 隔离附件目录的 Windows ACL 写入模式。
 * @param {'writable'|'read-only'} mode 正常可写或拒绝应用身份写入。
 * @returns {Promise<object>} 切换后的完整故障模式状态。
 */
export async function setAttachmentStorageMode(mode) {
  if (!['writable', 'read-only'].includes(mode)) {
    throw new Error('附件存储故障控制参数不合法')
  }
  return setFaultModes({ attachmentMode: mode })
}

/**
 * 把全部辅助服务恢复到正常模式，供用例开始和 finally 共同调用。
 * @returns {Promise<object>} 恢复后的完整模式状态。
 */
export async function resetFaultModes() {
  return setFaultModes({
    mysqlMode: 'ok', redisMode: 'ok', httpMode: 'ok', smtpMode: 'accept',
    attachmentMode: 'writable'
  })
}

/**
 * 清空上一用例的非敏感故障转换与外部服务证据。
 * @returns {Promise<object>} 清理确认结果。
 */
export async function clearFaultEvidence() {
  return requestControl('DELETE', '/evidence')
}

/**
 * 读取不包含数据库协议正文和凭据的故障转换证据。
 * @returns {Promise<{requests:object[],messages:object[],faultTransitions:object[],activeConnections:{smtp:number}}>} 当前辅助服务证据。
 */
export async function readFaultEvidence() {
  return requestControl('GET', '/evidence')
}
