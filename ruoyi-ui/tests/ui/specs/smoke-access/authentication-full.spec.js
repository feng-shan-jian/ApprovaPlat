import { test, expect } from '@playwright/test'
import {
  fillCredentialInput,
  loginThroughUi,
  logoutThroughUi
} from '../../../e2e/fixtures/workflow.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import {
  loadSystemAdminAccount,
  loadWorkflowAccounts
} from '../../../e2e/support/environment.js'
import { openAccountSession } from '../../support/role-session.js'

const workflowAccounts = loadWorkflowAccounts()

/**
 * 新建不共享 Cookie、localStorage 或 Token 的真实浏览器登录页。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试项目信息。
 * @returns {Promise<{context:import('@playwright/test').BrowserContext,page:import('@playwright/test').Page}>} 独立浏览器上下文和页面。
 */
async function openAnonymousLoginPage(browser, testInfo) {
  const context = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
    viewport: { width: 1440, height: 960 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
  return { context, page: await context.newPage() }
}

/**
 * 通过真实登录页提交一组凭据并立即读取失败响应，避免页面状态变化后响应正文被浏览器释放。
 * @param {import('@playwright/test').Page} page 无登录状态的浏览器页面。
 * @param {{username:string,password:string}} account 当前登录尝试使用的账号和密码。
 * @returns {Promise<{status:number,payload:object}>} 后端 HTTP 状态和 AjaxResult 失败正文。
 */
async function submitRejectedLogin(page, account) {
  const captchaPromise = page.waitForResponse(response => matchesEndpoint(response, '/captchaImage', 'GET'))
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  const captcha = await expectAjaxSuccess(await captchaPromise, '/captchaImage')
  expect(captcha.captchaEnabled, '普通负向登录开始前验证码必须保持关闭').toBe(false)

  await fillCredentialInput(page.locator('input[type="text"]').first(), account.username)
  await fillCredentialInput(page.locator('input[type="password"]'), account.password)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, '/login', 'POST'))
    .then(async response => ({ status: response.status(), payload: await response.json() }))
  await page.locator('.login-form .el-button--primary').click()
  const result = await responsePromise
  expect(result.status, '失败登录仍必须返回可解析的统一 HTTP 响应').toBe(200)
  expect(result.payload?.code, '失败登录不得返回成功业务码').not.toBe(200)
  await expect(page).toHaveURL(/\/login(?:\?|$)/u)
  const tokenCookies = await page.context().cookies()
  expect(tokenCookies.some(cookie => cookie.name === 'Admin-Token'), '失败登录不得创建认证 Cookie').toBe(false)
  return result
}

/**
 * 在系统列表页按唯一关键字执行一次真实查询并返回唯一行。
 * @param {import('@playwright/test').Page} page 已登录系统管理员页面。
 * @param {string} pagePath 系统管理页面路由。
 * @param {string} placeholder 查询输入框占位文本。
 * @param {string} keyword 唯一查询关键字。
 * @param {string} endpoint 列表查询后端入口。
 * @returns {Promise<import('@playwright/test').Locator>} 唯一业务表格行。
 */
async function filterSystemRow(page, pagePath, placeholder, keyword, endpoint) {
  await page.goto(pagePath)
  await expect(page.locator('.app-container .el-table')).toBeVisible()
  const input = page.getByPlaceholder(placeholder)
  await input.fill(keyword)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, 'GET'))
  await input.locator('xpath=ancestor::form[1]').getByRole('button', { name: '搜索', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, endpoint)
  const row = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: keyword })
  await expect(row, `${pagePath} 查询结果必须唯一`).toHaveCount(1)
  return row
}

/**
 * 通过用户管理状态开关修改测试账号状态，并核对正式接口及开关回显。
 * @param {import('@playwright/test').Page} page 已登录系统管理员页面。
 * @param {import('@playwright/test').Locator} row 唯一测试账号行。
 * @param {boolean} enabled 目标状态，true 表示启用、false 表示停用。
 * @returns {Promise<void>} 状态接口成功且页面开关与目标一致后结束。
 */
async function setUserEnabled(page, row, enabled) {
  const statusSwitch = row.getByRole('switch')
  const current = await statusSwitch.getAttribute('aria-checked') === 'true'
  if (current === enabled) return
  await row.locator('.el-switch').click()
  const messageBox = page.locator('.el-message-box')
  await expect(messageBox).toBeVisible()
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/system/user/changeStatus', 'PUT'))
  await messageBox.getByRole('button', { name: '确定', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/system/user/changeStatus')
  await expect(statusSwitch).toHaveAttribute('aria-checked', String(enabled))
}

/**
 * 通过参数设置修改验证码开关，配置值只能在系统管理员页面内读取和恢复。
 * @param {import('@playwright/test').Page} page 已登录系统管理员页面。
 * @param {string} value 目标参数字符串，固定为 true 或 false。
 * @returns {Promise<void>} 参数更新成功且列表回显新值后结束。
 */
async function setCaptchaConfig(page, value) {
  expect(['true', 'false']).toContain(value)
  const row = await filterSystemRow(
    page, '/system/config', '请输入参数键名', 'sys.account.captchaEnabled', '/system/config/list')
  await row.getByRole('button', { name: '修改', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '修改参数' })
  const valueInput = dialog.getByLabel('参数键值')
  await expect(valueInput).toBeVisible()
  if (await valueInput.inputValue() !== value) await valueInput.fill(value)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, '/system/config', 'PUT'))
  await dialog.getByRole('button', { name: '确 定', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/system/config')
  await expect(page.getByText('修改成功', { exact: true })).toBeVisible()
  await expect(row).toContainText(value)
}

test('@full [UI-AUTH-001] 错误密码和不存在账号均通过真实登录页拒绝且不创建会话', async ({ browser }, testInfo) => {
  const knownAccount = workflowAccounts.workflow_starter
  const attempts = [
    { scenario: 'wrong-password', username: knownAccount.username, password: `${knownAccount.password}_invalid` },
    { scenario: 'unknown-account', username: `E2E_UI_UNKNOWN_${Date.now().toString(36)}`, password: 'invalid-password' }
  ]
  const evidence = []
  for (const attempt of attempts) {
    const anonymous = await openAnonymousLoginPage(browser, testInfo)
    try {
      const result = await submitRejectedLogin(anonymous.page, attempt)
      evidence.push({ scenario: attempt.scenario, httpStatus: result.status, businessCode: result.payload.code })
    } finally {
      await anonymous.context.close()
    }
  }
  expect(evidence.map(item => item.businessCode).every(code => Number(code) !== 200)).toBe(true)
  await testInfo.attach('authentication-negative-evidence.json', {
    body: Buffer.from(JSON.stringify(evidence, null, 2)), contentType: 'application/json'
  })
})

test('@full [UI-AUTH-002] 系统管理员停用账号后登录被拒绝并可通过UI恢复', async ({ browser }, testInfo) => {
  const systemAdmin = loadSystemAdminAccount()
  const targetAccount = workflowAccounts.workflow_auditor
  const admin = await openAccountSession(browser, systemAdmin, testInfo, 'system-admin')
  let targetRow
  let statusChanged = false
  let failed = true
  try {
    targetRow = await filterSystemRow(
      admin.page, '/system/user', '请输入用户名称', targetAccount.username, '/system/user/list')
    await expect(targetRow.getByRole('switch'), '测试账号开始时必须启用').toHaveAttribute('aria-checked', 'true')
    await setUserEnabled(admin.page, targetRow, false)
    statusChanged = true

    const anonymous = await openAnonymousLoginPage(browser, testInfo)
    try {
      const rejected = await submitRejectedLogin(anonymous.page, targetAccount)
      expect(String(rejected.payload?.msg || ''), '停用账号必须返回稳定封禁语义').toContain('封禁')
      await testInfo.attach('disabled-login-evidence.json', {
        body: Buffer.from(JSON.stringify({
          httpStatus: rejected.status,
          businessCode: rejected.payload.code,
          blockedSemantic: true
        }, null, 2)),
        contentType: 'application/json'
      })
    } finally {
      await anonymous.context.close()
    }
    failed = false
  } finally {
    let recoveryError = null
    try {
      if (statusChanged) {
        targetRow = await filterSystemRow(
          admin.page, '/system/user', '请输入用户名称', targetAccount.username, '/system/user/list')
        await setUserEnabled(admin.page, targetRow, true)
        const verification = await openAnonymousLoginPage(browser, testInfo)
        try {
          await loginThroughUi(verification.page, targetAccount)
          await logoutThroughUi(verification.page, targetAccount.roleKey)
        } finally {
          await verification.context.close()
        }
      }
    } catch (error) {
      failed = true
      recoveryError = error
    } finally {
      await admin.close(failed)
    }
    if (recoveryError) throw recoveryError
  }
})

test('@full [UI-AUTH-003] 验证码开启后前端阻止空提交且后端拒绝错误验证码', async ({ browser }, testInfo) => {
  const systemAdmin = loadSystemAdminAccount()
  const admin = await openAccountSession(browser, systemAdmin, testInfo, 'system-admin')
  let captchaChanged = false
  let failed = true
  try {
    await setCaptchaConfig(admin.page, 'true')
    captchaChanged = true

    const anonymous = await openAnonymousLoginPage(browser, testInfo)
    try {
      const captchaPromise = anonymous.page.waitForResponse(response => matchesEndpoint(
        response, '/captchaImage', 'GET')).then(async response => await response.json())
      await anonymous.page.goto('/login', { waitUntil: 'domcontentloaded' })
      const captcha = await captchaPromise
      expect(captcha?.code).toBe(200)
      expect(captcha?.captchaEnabled).toBe(true)
      expect(typeof captcha?.uuid).toBe('string')
      expect(String(captcha?.img || '').length).toBeGreaterThan(100)
      await expect(anonymous.page.getByPlaceholder('验证码')).toBeVisible()
      await expect(anonymous.page.locator('.login-code-img')).toBeVisible()

      await fillCredentialInput(anonymous.page.getByPlaceholder('账号'), systemAdmin.username)
      await fillCredentialInput(anonymous.page.getByPlaceholder('密码'), systemAdmin.password)
      const loginRequests = []
      const requestListener = request => {
        const pathname = new URL(request.url()).pathname
        if (request.method() === 'POST' && pathname.endsWith('/login')) loginRequests.push(request)
      }
      anonymous.page.on('request', requestListener)
      await anonymous.page.locator('.login-form .el-button--primary').click()
      await expect(anonymous.page.getByText('请输入验证码', { exact: true })).toBeVisible()
      expect(loginRequests, '空验证码必须由前端表单校验阻止，后端不得收到登录请求').toHaveLength(0)

      await fillCredentialInput(anonymous.page.getByPlaceholder('验证码'), '0000')
      const rejectedPromise = anonymous.page.waitForResponse(response => matchesEndpoint(response, '/login', 'POST'))
        .then(async response => ({ status: response.status(), payload: await response.json() }))
      await anonymous.page.locator('.login-form .el-button--primary').click()
      const rejected = await rejectedPromise
      anonymous.page.off('request', requestListener)
      expect(rejected.status).toBe(200)
      expect(rejected.payload?.code).not.toBe(200)
      expect(String(rejected.payload?.msg || ''), '错误验证码必须返回验证码业务语义').toContain('验证码')
      await expect(anonymous.page).toHaveURL(/\/login(?:\?|$)/u)
      expect((await anonymous.context.cookies()).some(cookie => cookie.name === 'Admin-Token')).toBe(false)
      await testInfo.attach('captcha-gate-evidence.json', {
        body: Buffer.from(JSON.stringify({
          captchaEnabled: true,
          emptyCodeLoginRequests: 0,
          invalidCodeBusinessCode: rejected.payload.code,
          tokenCookieCreated: false
        }, null, 2)),
        contentType: 'application/json'
      })
    } finally {
      await anonymous.context.close()
    }
    failed = false
  } finally {
    let recoveryError = null
    try {
      if (captchaChanged) {
        await setCaptchaConfig(admin.page, 'false')
        const verification = await openAnonymousLoginPage(browser, testInfo)
        try {
          const captchaPromise = verification.page.waitForResponse(response => matchesEndpoint(
            response, '/captchaImage', 'GET')).then(async response => await response.json())
          await verification.page.goto('/login', { waitUntil: 'domcontentloaded' })
          expect((await captchaPromise)?.captchaEnabled, '恢复后验证码必须重新关闭').toBe(false)
          await expect(verification.page.getByPlaceholder('验证码')).toHaveCount(0)
        } finally {
          await verification.context.close()
        }
      }
    } catch (error) {
      failed = true
      recoveryError = error
    } finally {
      await admin.close(failed)
    }
    if (recoveryError) throw recoveryError
  }
})
