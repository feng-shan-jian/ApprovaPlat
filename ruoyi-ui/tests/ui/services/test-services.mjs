import http from 'node:http'
import net from 'node:net'


/**
 * 返回 JSON 测试服务响应。
 * @param {import('node:http').ServerResponse} response HTTP 响应。
 * @param {number} status HTTP 状态码。
 * @param {object} payload 响应对象。
 * @returns {void}
 */
function sendJson(response, status, payload) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
  response.end(JSON.stringify(payload))
}


const faultServer = http.createServer(async (request, response) => {
  for await (const _chunk of request) {
    // 消费请求体后返回正常响应，避免测试连接复用时残留未读字节。
  }
  return sendJson(response, 200, { eventId: String(Date.now()), accepted: true })
})

/**
 * 创建正常接收邮件的最小 SMTP 会话状态机。
 * @param {import('node:net').Socket} socket SMTP 客户端连接。
 * @returns {void}
 */
function handleSmtp(socket) {
  socket.on('error', () => socket.destroy())
  socket.setEncoding('utf8')
  socket.write('220 localhost E2E UI SMTP\r\n')
  let dataMode = false
  socket.on('data', chunk => {
    const lines = chunk.split(/\r?\n/u)
    for (const line of lines) {
      if (dataMode) {
        if (line === '.') {
          dataMode = false
          socket.write('250 queued\r\n')
        }
        continue
      }
      if (/^(EHLO|HELO)\b/iu.test(line)) socket.write('250-localhost\r\n250 SIZE 10485760\r\n')
      else if (/^MAIL FROM:/iu.test(line) || /^RCPT TO:/iu.test(line)) socket.write('250 ok\r\n')
      else if (/^DATA$/iu.test(line)) {
        dataMode = true
        socket.write('354 end with <CRLF>.<CRLF>\r\n')
      } else if (/^QUIT$/iu.test(line)) {
        socket.end('221 bye\r\n')
      } else if (line) {
        socket.write('250 ok\r\n')
      }
    }
  })
}

const smtpServer = net.createServer(handleSmtp)

faultServer.listen(18082, '127.0.0.1', () => console.log('FAULT_HTTP_READY=18082'))
smtpServer.listen(2525, '127.0.0.1', () => console.log('SMTP_READY=2525'))

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    faultServer.close()
    smtpServer.close(() => process.exit(0))
  })
}
