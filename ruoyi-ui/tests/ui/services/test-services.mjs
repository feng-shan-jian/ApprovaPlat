import { execFileSync } from 'node:child_process'
import { existsSync, lstatSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import path from 'node:path'

const state = {
  httpMode: 'ok',
  smtpMode: 'accept',
  mysqlMode: 'ok',
  redisMode: 'ok',
  attachmentMode: 'writable',
  requests: [],
  messages: [],
  faultTransitions: []
}

// 代理连接只保存内存中的 socket 引用，用于模式切换时原子切断；不读取或记录数据库协议正文。
const proxyConnections = {
  mysql: new Set(),
  redis: new Set()
}

// SMTP 连接集合只保存 socket 引用和数量，用于证明后端确实在投递租约内阻塞；不记录命令、地址或正文。
const smtpConnections = new Set()

/** 当前 Windows 登录身份的 SID，仅用于本轮隔离附件目录 ACL，不写入任何测试证据。 */
let currentWindowsSid = ''

/**
 * 比较两个 Windows 绝对路径，兼容盘符和目录名大小写差异。
 * @param {string} left 第一个绝对路径。
 * @param {string} right 第二个绝对路径。
 * @returns {boolean} 两个路径指向相同词法位置时返回 true。
 */
function sameWindowsPath(left, right) {
  return path.resolve(left).toLowerCase() === path.resolve(right).toLowerCase()
}

/**
 * 解析当前 Windows 登录身份 SID，避免 ACL 命令依赖本地化用户名。
 * @returns {string} 形如 S-1-5-21-... 的当前用户 SID。
 */
function resolveCurrentWindowsSid() {
  if (currentWindowsSid) return currentWindowsSid
  const output = execFileSync('whoami.exe', ['/user', '/fo', 'csv', '/nh'], {
    encoding: 'utf8', windowsHide: true
  })
  const match = output.match(/S-\d-(?:\d+-)+\d+/u)
  if (!match) throw new Error('无法解析当前 Windows 身份 SID')
  currentWindowsSid = match[0]
  return currentWindowsSid
}

/**
 * 解析并校验本轮隔离附件根，拒绝触碰开发目录、符号链接或其他运行产物。
 * @returns {string} 当前 runId 下真实存在的 workflow-attachments 绝对路径。
 */
function resolveFaultAttachmentRoot() {
  const outputRootValue = process.env.FLOWABLE_E2E_OUTPUT_ROOT?.trim()
  const profileRootValue = process.env.FLOWABLE_E2E_PROFILE_ROOT?.trim()
  if (!outputRootValue || !profileRootValue) {
    throw new Error('缺少附件故障隔离目录配置')
  }
  const outputRoot = path.resolve(outputRootValue)
  const profileRoot = path.resolve(profileRootValue)
  const expectedProfileRoot = path.resolve(outputRoot, 'runtime', 'profile')
  if (!sameWindowsPath(profileRoot, expectedProfileRoot)) {
    throw new Error('附件故障目录不属于当前 runId 隔离 profile')
  }
  const attachmentRoot = path.resolve(profileRoot, 'workflow-attachments')
  const status = lstatSync(attachmentRoot)
  if (!status.isDirectory() || status.isSymbolicLink()) {
    throw new Error('附件故障目标不是普通目录')
  }
  const realProfileRoot = realpathSync(profileRoot)
  const realAttachmentRoot = realpathSync(attachmentRoot)
  if (!sameWindowsPath(path.dirname(realAttachmentRoot), realProfileRoot)) {
    throw new Error('附件故障目标真实路径越出隔离 profile')
  }
  return realAttachmentRoot
}

/**
 * 执行受控 icacls 操作，不把物理路径、SID 或命令输出带入 HTTP 错误响应。
 * @param {string[]} args icacls.exe 参数列表。
 * @returns {void} ACL 操作成功后结束。
 */
function runIcacls(args) {
  try {
    execFileSync('icacls.exe', args, { stdio: 'ignore', windowsHide: true })
  } catch {
    throw new Error('附件故障 ACL 操作失败')
  }
}

/**
 * 用一次无残留文件写入确认隔离附件根当前是否符合预期可写状态。
 * @param {string} attachmentRoot 已完成边界校验的附件根绝对路径。
 * @param {boolean} shouldBeWritable 当前模式是否应允许应用身份写入。
 * @returns {void} 实际权限与预期一致后结束。
 */
function verifyAttachmentWriteMode(attachmentRoot, shouldBeWritable) {
  const probePath = path.join(attachmentRoot, `.e2e-acl-probe-${process.pid}-${Date.now()}.tmp`)
  let writeSucceeded = false
  try {
    writeFileSync(probePath, 'acl-probe', { encoding: 'ascii', flag: 'wx' })
    writeSucceeded = true
  } catch (error) {
    if (shouldBeWritable || !['EACCES', 'EPERM'].includes(error?.code)) {
      throw new Error('附件故障 ACL 写入验证失败')
    }
  } finally {
    if (writeSucceeded) rmSync(probePath, { force: true })
  }
  if (writeSucceeded !== shouldBeWritable) {
    throw new Error('附件故障 ACL 模式未生效')
  }
}

/**
 * 仅对本轮隔离附件根切换写权限，并在每次切换后执行真实写入验证。
 * @param {'writable'|'read-only'} mode 需要设置的附件目录模式。
 * @returns {void} ACL 已切换、验证且故障转换已记录后结束。
 */
function setAttachmentMode(mode) {
  const configuredProfileRoot = process.env.FLOWABLE_E2E_PROFILE_ROOT?.trim()
  if (mode === 'writable' && configuredProfileRoot
    && !existsSync(path.resolve(configuredProfileRoot, 'workflow-attachments'))) {
    // 后端尚未完成存储初始化时没有 ACL 可恢复；总控仍会校验 profile 边界。
    state.attachmentMode = mode
    return
  }
  const attachmentRoot = resolveFaultAttachmentRoot()
  const sidArgument = `*${resolveCurrentWindowsSid()}`
  if (mode === 'read-only') {
    // 断点续跑时先移除可能残留的同身份 deny ACE，再写入唯一受控规则。
    runIcacls([attachmentRoot, '/remove:d', sidArgument])
    runIcacls([attachmentRoot, '/deny', `${sidArgument}:(OI)(CI)(W)`])
    try {
      verifyAttachmentWriteMode(attachmentRoot, false)
    } catch (error) {
      runIcacls([attachmentRoot, '/remove:d', sidArgument])
      throw error
    }
  } else {
    runIcacls([attachmentRoot, '/remove:d', sidArgument])
    verifyAttachmentWriteMode(attachmentRoot, true)
  }
  state.attachmentMode = mode
  state.faultTransitions.push({
    dependency: 'attachment-storage', mode, changedAt: new Date().toISOString()
  })
}

/**
 * 销毁指定依赖代理当前持有的全部双向连接。
 * @param {'mysql'|'redis'} dependency 需要切断的正式依赖类型。
 * @returns {void} 客户端和上游 socket 均关闭后结束。
 */
function destroyProxyConnections(dependency) {
  for (const connection of proxyConnections[dependency]) {
    connection.client.destroy()
    connection.upstream?.destroy()
  }
  proxyConnections[dependency].clear()
}

/**
 * 切换依赖故障模式并记录不含协议内容的恢复证据。
 * @param {'mysql'|'redis'} dependency 正式依赖类型。
 * @param {'ok'|'disconnect'|'timeout'} mode 新故障模式。
 * @returns {void} 非正常模式会立即切断已有连接，保证连接池真实感知故障。
 */
function setDependencyMode(dependency, mode) {
  const stateKey = `${dependency}Mode`
  if (state[stateKey] === mode) return
  state[stateKey] = mode
  destroyProxyConnections(dependency)
  state.faultTransitions.push({ dependency, mode, changedAt: new Date().toISOString() })
}

/**
 * 创建不解析、不缓存协议正文的本机 TCP 故障代理。
 * @param {{dependency:'mysql'|'redis',listenPort:number,targetPort:number}} options 依赖名、测试监听端口和真实服务端口。
 * @returns {import('node:net').Server} 可由总控统一关闭的 TCP 服务。
 */
function createDependencyProxy(options) {
  const { dependency, listenPort, targetPort } = options
  const server = net.createServer(client => {
    const mode = state[`${dependency}Mode`]
    const connection = { client, upstream: null }
    proxyConnections[dependency].add(connection)
    const cleanup = () => proxyConnections[dependency].delete(connection)
    client.once('close', cleanup)
    client.once('error', () => client.destroy())

    if (mode === 'disconnect') {
      client.destroy()
      return
    }
    if (mode === 'timeout') {
      // 保持 TCP 已连接但不建立上游连接，模拟依赖请求无响应；模式恢复时统一销毁该连接。
      return
    }

    const upstream = net.createConnection({ host: '127.0.0.1', port: targetPort })
    connection.upstream = upstream
    upstream.once('error', () => client.destroy())
    upstream.once('close', () => client.destroy())
    client.once('close', () => upstream.destroy())
    client.pipe(upstream)
    upstream.pipe(client)
  })
  server.listen(listenPort, '127.0.0.1', () => {
    console.log(`${dependency.toUpperCase()}_PROXY_READY=${listenPort}`)
  })
  return server
}

/**
 * 读取并限制控制接口 JSON 请求体大小。
 * @param {import('node:http').IncomingMessage} request HTTP 请求。
 * @returns {Promise<object>} 解析后的 JSON 对象。
 */
async function readJson(request) {
  const chunks = []
  let size = 0
  for await (const chunk of request) {
    size += chunk.length
    if (size > 64 * 1024) throw new Error('控制请求体超过 64 KiB')
    chunks.push(chunk)
  }
  if (!chunks.length) return {}
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

/**
 * 返回不含敏感请求正文的 JSON 控制响应。
 * @param {import('node:http').ServerResponse} response HTTP 响应。
 * @param {number} status HTTP 状态码。
 * @param {object} payload 响应对象。
 * @returns {void}
 */
function sendJson(response, status, payload) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify(payload))
}

const controlServer = http.createServer(async (request, response) => {
  try {
    if (request.method === 'GET' && request.url === '/health') {
      sendJson(response, 200, {
        status: 'UP',
        httpMode: state.httpMode,
        smtpMode: state.smtpMode,
        mysqlMode: state.mysqlMode,
        redisMode: state.redisMode,
        attachmentMode: state.attachmentMode,
        activeSmtpConnections: smtpConnections.size
      })
      return
    }
    if (request.method === 'POST' && request.url === '/mode') {
      const body = await readJson(request)
      if (body.httpMode && !['ok', 'server-error', 'timeout', 'disconnect', 'duplicate'].includes(body.httpMode)) {
        throw new Error('未知 HTTP 故障模式')
      }
      if (body.smtpMode && !['accept', 'reject', 'timeout'].includes(body.smtpMode)) {
        throw new Error('未知 SMTP 故障模式')
      }
      if (body.mysqlMode && !['ok', 'disconnect', 'timeout'].includes(body.mysqlMode)) {
        throw new Error('未知 MySQL 故障模式')
      }
      if (body.redisMode && !['ok', 'disconnect', 'timeout'].includes(body.redisMode)) {
        throw new Error('未知 Redis 故障模式')
      }
      if (body.attachmentMode && !['writable', 'read-only'].includes(body.attachmentMode)) {
        throw new Error('未知附件存储故障模式')
      }
      if (body.httpMode && state.httpMode !== body.httpMode) {
        state.httpMode = body.httpMode
        state.faultTransitions.push({
          dependency: 'external-http', mode: body.httpMode, changedAt: new Date().toISOString()
        })
      }
      if (body.smtpMode && state.smtpMode !== body.smtpMode) {
        state.smtpMode = body.smtpMode
        // SMTP 证据只记录网络行为切换，不保留地址、命令或邮件正文。
        state.faultTransitions.push({
          dependency: 'smtp', mode: body.smtpMode, changedAt: new Date().toISOString()
        })
      }
      if (body.mysqlMode) setDependencyMode('mysql', body.mysqlMode)
      if (body.redisMode) setDependencyMode('redis', body.redisMode)
      if (body.attachmentMode) setAttachmentMode(body.attachmentMode)
      sendJson(response, 200, {
        httpMode: state.httpMode,
        smtpMode: state.smtpMode,
        mysqlMode: state.mysqlMode,
        redisMode: state.redisMode,
        attachmentMode: state.attachmentMode
      })
      return
    }
    if (request.method === 'GET' && request.url === '/evidence') {
      sendJson(response, 200, {
        requests: state.requests,
        messages: state.messages,
        faultTransitions: state.faultTransitions,
        activeConnections: { smtp: smtpConnections.size }
      })
      return
    }
    if (request.method === 'DELETE' && request.url === '/evidence') {
      state.requests.length = 0
      state.messages.length = 0
      state.faultTransitions.length = 0
      sendJson(response, 200, { cleared: true })
      return
    }
    sendJson(response, 404, { message: 'Not Found' })
  } catch (error) {
    sendJson(response, 400, { message: error.message })
  }
})

const faultServer = http.createServer(async (request, response) => {
  const bodyChunks = []
  for await (const chunk of request) bodyChunks.push(chunk)
  state.requests.push({
    method: request.method,
    path: request.url,
    bodyBytes: Buffer.concat(bodyChunks).length,
    receivedAt: new Date().toISOString(),
    mode: state.httpMode,
    // 只保留稳定幂等键摘要；不记录 Authorization、Cookie 或业务正文。
    idempotencyKey: String(request.headers['idempotency-key'] || '')
  })
  if (state.httpMode === 'server-error') return sendJson(response, 500, { code: 'E2E_UI_EXTERNAL_FAILURE' })
  if (state.httpMode === 'timeout') return
  if (state.httpMode === 'disconnect') return request.socket.destroy()
  if (state.httpMode === 'duplicate') return sendJson(response, 200, { eventId: 'duplicate-fixed-id', accepted: true })
  return sendJson(response, 200, { eventId: String(Date.now()), accepted: true })
})

/**
 * 创建最小 SMTP 会话状态机，支持接收、拒绝和超时三种真实网络行为。
 * @param {import('node:net').Socket} socket SMTP 客户端连接。
 * @returns {void}
 */
function handleSmtp(socket) {
  // 后端崩溃会主动重置正在投递的 SMTP 连接；该预期网络故障只关闭当前会话，不能带崩辅助服务。
  socket.on('error', () => socket.destroy())
  socket.setEncoding('utf8')
  if (state.smtpMode === 'timeout') return
  socket.write('220 localhost E2E UI SMTP\r\n')
  let dataMode = false
  let message = ''
  socket.on('data', chunk => {
    const lines = chunk.split(/\r?\n/u)
    for (const line of lines) {
      if (dataMode) {
        if (line === '.') {
          state.messages.push({ bytes: Buffer.byteLength(message), receivedAt: new Date().toISOString() })
          message = ''
          dataMode = false
          socket.write('250 queued\r\n')
        } else {
          message += line + '\n'
        }
        continue
      }
      if (/^(EHLO|HELO)\b/iu.test(line)) socket.write('250-localhost\r\n250 SIZE 10485760\r\n')
      else if (/^MAIL FROM:/iu.test(line) || /^RCPT TO:/iu.test(line)) {
        socket.write(state.smtpMode === 'reject' ? '550 rejected by E2E UI SMTP\r\n' : '250 ok\r\n')
      } else if (/^DATA$/iu.test(line)) {
        if (state.smtpMode === 'reject') socket.write('554 transaction rejected\r\n')
        else {
          dataMode = true
          socket.write('354 end with <CRLF>.<CRLF>\r\n')
        }
      } else if (/^QUIT$/iu.test(line)) {
        socket.end('221 bye\r\n')
      } else if (line) {
        socket.write('250 ok\r\n')
      }
    }
  })
}

const smtpServer = net.createServer(socket => {
  smtpConnections.add(socket)
  socket.once('close', () => smtpConnections.delete(socket))
  handleSmtp(socket)
})
const mysqlProxyServer = createDependencyProxy({ dependency: 'mysql', listenPort: 13306, targetPort: 3306 })
const redisProxyServer = createDependencyProxy({ dependency: 'redis', listenPort: 16379, targetPort: 6379 })

controlServer.listen(18081, '127.0.0.1', () => console.log('CONTROL_READY=18081'))
faultServer.listen(18082, '127.0.0.1', () => console.log('FAULT_HTTP_READY=18082'))
smtpServer.listen(2525, '127.0.0.1', () => console.log('SMTP_READY=2525'))

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    if (state.attachmentMode === 'read-only') {
      try {
        setAttachmentMode('writable')
      } catch {
        console.error('ATTACHMENT_ACL_RESTORE_FAILED')
      }
    }
    destroyProxyConnections('mysql')
    destroyProxyConnections('redis')
    for (const socket of smtpConnections) socket.destroy()
    controlServer.close()
    faultServer.close()
    mysqlProxyServer.close()
    redisProxyServer.close()
    smtpServer.close(() => process.exit(0))
  })
}
