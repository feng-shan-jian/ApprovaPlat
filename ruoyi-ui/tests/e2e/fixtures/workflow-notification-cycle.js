import { expect, test } from '@playwright/test'
import { createServer } from 'node:net'
import { setTimeout as delay } from 'node:timers/promises'

const notificationPath = '/workflow/notification'

async function responseFrom(page, path, method, action, query) {
  const [response] = await Promise.all([
    page.waitForResponse(response => {
      const url = new URL(response.url())
      return url.pathname.endsWith(path) && response.request().method() === method
        && (!query || query(url.searchParams))
    }),
    action()
  ])
  const result = await response.json()
  expect(response.ok(), `${method} ${path}`).toBe(true)
  expect(result.code, `${path}: ${result.msg}`).toBe(200)
  return result
}

async function selectOption(page, dialog, label, option) {
  await dialog.locator('.el-form-item')
    .filter({ has: page.getByText(label, { exact: true }) }).locator('.el-select').click()
  await page.getByRole('option', { name: option, exact: true }).click()
}

async function checkbox(page, dialog, label, checked) {
  const input = dialog.getByRole('checkbox', { name: label, exact: true })
  if (await input.isChecked() !== checked) {
    await dialog.locator('label.el-checkbox').filter({ has: page.getByRole('checkbox', { name: label, exact: true }) }).click()
  }
  await expect(input).toBeChecked({ checked })
}

function fieldInput(page, dialog, label) {
  return dialog.locator('.el-form-item')
    .filter({ has: page.getByText(label, { exact: true }) }).locator('input')
}

async function unusedLoopbackPort() {
  const server = createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const port = server.address().port
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  return port
}

// 仅接受本套件的合成认证；不转发邮件、不连接任何外部地址，也不记录 AUTH 明文。
function loopbackSmtp(port, username, credential) {
  const messages = [], sockets = new Set(), errors = []
  const server = createServer(socket => {
    sockets.add(socket)
    socket.setEncoding('utf8')
    socket.setTimeout(15000, () => socket.destroy())
    socket.on('close', () => sockets.delete(socket))
    socket.on('error', error => errors.push(error.message))
    let buffer = '', authStage = '', loginUser = '', authenticated = false, data = null
    const reply = line => socket.write(`${line}\r\n`)
    const authenticate = (user, password) => {
      authenticated = user === username && password === credential
      reply(authenticated ? '235 2.7.0 Authentication successful' : '535 5.7.8 Authentication failed')
    }
    const receive = line => {
      if (data !== null) {
        if (line !== '.') {
          data.push(line.startsWith('..') ? line.slice(1) : line)
          return
        }
        const rawHeaders = data.join('\r\n').split('\r\n\r\n')[0].replace(/\r\n[\t ]+/g, ' ')
        const headers = Object.fromEntries(rawHeaders.split('\r\n').filter(line => line.includes(':'))
          .map(line => [line.slice(0, line.indexOf(':')).toLowerCase(), line.slice(line.indexOf(':') + 1).trim()]))
        messages.push({ acceptedAt: new Date().toISOString(),
          idempotencyKey: headers['x-approvaplat-idempotency-key'], messageId: headers['message-id'] })
        data = null
        reply('250 2.0.0 Accepted by loopback fixture')
        return
      }
      if (authStage === 'plain') {
        const parts = Buffer.from(line, 'base64').toString('utf8').split('\0')
        authStage = ''
        authenticate(parts[1], parts[2])
        return
      }
      if (authStage === 'username') {
        loginUser = Buffer.from(line, 'base64').toString('utf8')
        authStage = 'password'
        reply('334 UGFzc3dvcmQ6')
        return
      }
      if (authStage === 'password') {
        authStage = ''
        authenticate(loginUser, Buffer.from(line, 'base64').toString('utf8'))
        return
      }
      const [command, argument, initial] = line.split(' ')
      switch (command.toUpperCase()) {
        case 'EHLO':
          reply('250-loopback.invalid\r\n250-AUTH LOGIN PLAIN\r\n250 8BITMIME')
          break
        case 'HELO': reply('250 loopback.invalid'); break
        case 'AUTH':
          if (argument?.toUpperCase() === 'PLAIN') {
            authStage = 'plain'
            if (initial) receive(initial)
            else reply('334 ')
          } else if (argument?.toUpperCase() === 'LOGIN') {
            authStage = 'username'
            if (initial) receive(initial)
            else reply('334 VXNlcm5hbWU6')
          } else reply('504 5.5.4 Unsupported authentication mechanism')
          break
        case 'MAIL':
        case 'RCPT': reply(authenticated ? '250 2.1.0 OK' : '530 5.7.0 Authentication required'); break
        case 'DATA':
          if (authenticated) { data = []; reply('354 End data with <CRLF>.<CRLF>') }
          else reply('530 5.7.0 Authentication required')
          break
        case 'RSET': data = null; reply('250 2.0.0 Reset'); break
        case 'NOOP': reply('250 2.0.0 OK'); break
        case 'QUIT': socket.end('221 2.0.0 Bye\r\n'); break
        default: reply('500 5.5.1 Unsupported command')
      }
    }
    socket.on('data', chunk => {
      buffer += chunk
      let newline
      while ((newline = buffer.indexOf('\r\n')) >= 0) {
        const line = buffer.slice(0, newline)
        buffer = buffer.slice(newline + 2)
        receive(line)
      }
    })
    reply('220 loopback.invalid ESMTP N09 fixture')
  })
  server.on('error', error => errors.push(error.message))
  return {
    messages, errors,
    async start() {
      await new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(port, '127.0.0.1', resolve)
      })
    },
    async close() {
      for (const socket of sockets) socket.destroy()
      if (server.listening) await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
    }
  }
}

/**
 * 通过正式页面配置本次专属流程通知，返回供调用方精确清理的主键和 SMTP 生命周期。
 * 前置条件：已登录管理账号，model 已部署，发起人有合成邮箱，隔离库 SMTP 原本为空。
 * 不写数据库、不改 worker 参数；准备中途失败时 error.notificationCycle 仍提供清理信息。
 */
export async function prepareNotificationCycle(page, model, runKey) {
  const port = await unusedLoopbackPort()
  const username = `${runKey}@example.invalid`
  const smtp = loopbackSmtp(port, username, `N09-synthetic-${runKey}`)
  const evidence = { transport: 'controlled-loopback-smtp', port, modelKey: model.key, snapshots: [] }
  const cycle = {
    policyId: null, mailCreated: false, mailRevision: null, outboxIds: [], evidence,
    close: () => smtp.close(),
    finish: processInstanceId => finishCycle(page, processInstanceId, cycle, smtp)
  }
  try {
    await page.goto(notificationPath)
    await expect(page.getByRole('heading', { name: '审批通知', exact: true })).toBeVisible()
    await expect(page.locator('.notification-admin__table:visible .el-loading-mask')).toHaveCount(0)
    const config = (await responseFrom(page, `${notificationPath}/mail-config`, 'GET',
      () => page.getByRole('button', { name: '邮件服务', exact: true }).click())).data
    expect(config.configured, '只允许在原本没有 SMTP 的隔离测试库创建配置').toBe(false)
    expect(Number(config.revision)).toBe(0)
    const mail = page.getByRole('dialog', { name: '配置邮件服务' })
    await expect(mail).toBeVisible()
    await fieldInput(page, mail, 'SMTP 服务器').fill('127.0.0.1')
    await fieldInput(page, mail, '端口').fill(String(port))
    await selectOption(page, mail, '加密方式', '不加密')
    await fieldInput(page, mail, '登录账号').fill(username)
    await fieldInput(page, mail, '授权码或密码').fill(`N09-synthetic-${runKey}`)
    await fieldInput(page, mail, '发件邮箱').fill(username)
    await fieldInput(page, mail, '发件人名称').fill(`${runKey}验收邮件`)
    const saved = (await responseFrom(page, `${notificationPath}/mail-config`, 'PUT',
      () => mail.getByRole('button', { name: '保存配置', exact: true }).click())).data
    cycle.mailCreated = true
    cycle.mailRevision = saved.revision
    expect(saved).toMatchObject({ configured: true, credentialConfigured: true, smtpHost: '127.0.0.1', smtpPort: port })
    await expect(mail).toBeHidden()

    await page.getByRole('button', { name: '新增策略', exact: true }).click()
    const policy = page.getByRole('dialog', { name: '新增通知策略' })
    await policy.getByText('指定流程', { exact: true }).click()
    await expect(policy.getByRole('radio', { name: '指定流程', exact: true })).toBeChecked()
    await policy.locator('.el-form-item').filter({ has: page.getByText('选择流程', { exact: true }) }).locator('.el-select').click()
    const modelOption = page.getByRole('option').filter({ hasText: model.name })
    await expect(modelOption).toHaveCount(1)
    await modelOption.click()
    await selectOption(page, policy, '通知场景', '审批节点完成')
    await checkbox(page, policy, '当前办理人', false)
    await checkbox(page, policy, '流程发起人', true)
    await checkbox(page, policy, '当前操作人', false)
    await checkbox(page, policy, '站内信', true)
    await checkbox(page, policy, '邮件', true)
    const created = (await responseFrom(page, `${notificationPath}/policies`, 'PUT',
      () => policy.getByRole('button', { name: '保存', exact: true }).click())).data
    cycle.policyId = created.policyId
    expect(created).toMatchObject({ scopeType: 'PROCESS', processDefinitionKey: model.key,
      eventType: 'TASK_COMPLETED', recipientRules: 'INITIATOR', channels: 'INBOX,EMAIL', status: 'ENABLED' })
    expect(Number(created.maxAttempts)).toBe(6)
    await expect(policy).toBeHidden()
    return cycle
  } catch (error) {
    error.notificationCycle = cycle
    await cycle.close()
    throw error
  }
}

async function finishCycle(page, processInstanceId, cycle, smtp) {
  cycle.evidence.processInstanceId = processInstanceId
  const seen = new Set()
  const query = async () => {
    const result = await responseFrom(page, `${notificationPath}/outbox`, 'GET',
      () => page.getByRole('button', { name: '查询', exact: true }).click(),
      params => params.get('keyword') === processInstanceId)
    expect(Number(result.total), '合成实例的投递证据必须完整处于当前页').toBeLessThanOrEqual(20)
    const rows = result.rows.filter(row => row.processInstanceId === processInstanceId
      && row.channel === 'EMAIL' && row.eventType === 'TASK_COMPLETED')
    for (const row of rows) {
      if (!cycle.outboxIds.includes(row.outboxId)) cycle.outboxIds.push(row.outboxId)
      const signature = `${row.outboxId}:${row.status}:${row.deliveryCycle}:${row.attemptCount}`
      if (!seen.has(signature)) {
        seen.add(signature)
        cycle.evidence.snapshots.push({ observedAt: new Date().toISOString(), ...row })
      }
    }
    return rows
  }
  try {
    await page.goto(notificationPath)
    await page.getByRole('tab', { name: '投递运维', exact: true }).click()
    await page.getByPlaceholder('搜索流程或投递编号').fill(processInstanceId)
    const deadline = Date.now() + 120000
    let rows = []
    while (Date.now() < deadline) {
      rows = await query()
      for (const row of rows) {
        if (['RETRYING', 'DEAD_LETTER'].includes(row.status)) {
          expect(row.lastErrorCode, '必须由真实连接拒绝触发 SMTP 重试').toBe('SMTP_CONNECT_FAILED')
          expect(row.nextAttemptAt).toBeTruthy()
          expect(Number.isNaN(Date.parse(row.nextAttemptAt))).toBe(false)
        }
      }
      if (rows.length && rows.every(row => row.status === 'DEAD_LETTER')) break
      await delay(1500)
    }
    expect(rows.length, '真实业务动作应登记 EMAIL outbox').toBeGreaterThan(0)
    expect(rows.every(row => row.status === 'DEAD_LETTER'), '默认六次投递必须最终进入死信').toBe(true)
    expect(cycle.evidence.snapshots.some(row => row.status === 'RETRYING'), '需要捕获真实重试中状态').toBe(true)
    const selected = rows[0]
    expect(Number(selected.attemptCount)).toBe(6)
    expect(Number(selected.totalAttemptCount)).toBe(6)
    expect(Number(selected.deliveryCycle)).toBe(1)
    expect(selected.canCompensate).toBe(true)
    await test.info().attach('notification-dead-letter', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })

    await smtp.start()
    const selectedRow = page.getByRole('row').filter({
      has: page.getByText(`#${selected.outboxId}`, { exact: true })
    })
    await selectedRow.getByRole('button', { name: '补偿重试', exact: true }).click()
    const confirmation = page.getByRole('dialog').filter({ hasText: `确认重新投递通知 ${selected.outboxId} 吗？` })
    await responseFrom(page, `${notificationPath}/outbox/${selected.outboxId}/compensate`, 'POST',
      () => confirmation.getByRole('button', { name: '确定', exact: true }).click())
    let completed
    const deliveryDeadline = Date.now() + 20000
    while (Date.now() < deliveryDeadline) {
      completed = (await query()).find(row => row.outboxId === selected.outboxId)
      if (completed?.status === 'PROCESSED') break
      await delay(1000)
    }
    expect(completed).toMatchObject({ status: 'PROCESSED', canCompensate: false })
    expect(Number(completed.deliveryCycle)).toBe(2)
    expect(Number(completed.attemptCount)).toBe(1)
    expect(Number(completed.totalAttemptCount)).toBe(7)
    expect(smtp.messages).toHaveLength(1)
    expect(smtp.messages[0].idempotencyKey).toMatch(/^[0-9a-f]{64}$/)
    expect(smtp.messages[0].messageId).toBeTruthy()
    await expect(selectedRow.getByRole('button', { name: '补偿重试', exact: true })).toHaveCount(0)
    // 默认 worker 每秒一次；跨两个以上调度周期确认终态不再被领取。
    await delay(3000)
    const stable = (await query()).find(row => row.outboxId === selected.outboxId)
    expect(stable).toMatchObject({ status: 'PROCESSED', revision: completed.revision,
      totalAttemptCount: completed.totalAttemptCount })
    expect(smtp.messages).toHaveLength(1)
    expect(smtp.errors).toEqual([])
    cycle.evidence.compensatedOutboxId = selected.outboxId
    await test.info().attach('notification-processed', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' })
    return { outboxIds: [...cycle.outboxIds], compensatedOutboxId: selected.outboxId }
  } finally {
    cycle.evidence.smtp = { messages: smtp.messages, errors: smtp.errors }
    await test.info().attach('notification-cycle', {
      body: Buffer.from(JSON.stringify(cycle.evidence, null, 2)), contentType: 'application/json'
    })
  }
}
