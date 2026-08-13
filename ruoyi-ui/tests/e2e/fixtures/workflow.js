import { test as base, expect } from '@playwright/test'
import { loadWorkflowAccounts } from '../support/environment.js'
import { expectAjaxSuccess, matchesEndpoint } from '../support/http.js'

const accounts = loadWorkflowAccounts()

/**
 * 从日志文本中移除本次账号的用户名和密码，失败报告不得回显登录凭据。
 * @param {unknown} value 浏览器异常或 console 文本。
 * @param {{username: string, password: string}} account 当前角色账号。
 * @returns {string} 已脱敏的错误文本。
 */
function redactAccountSecrets(value, account) {
  return String(value)
    .split(account.username).join('<username>')
    .split(account.password).join('<password>')
}

/**
 * 关闭首次密码或过期密码提示，避免安全提醒遮挡后续真实页面操作。
 * @param {import('@playwright/test').Page} page 已完成登录的浏览器页面。
 * @returns {Promise<void>} 提示不存在或关闭后结束。
 */
async function dismissPasswordNotice(page) {
  const messageBox = page.locator('.el-message-box')
  if (await messageBox.isVisible({ timeout: 1_000 }).catch(() => false)) {
    const cancelButton = messageBox.getByRole('button', { name: '取消', exact: true })
    if (await cancelButton.isVisible().catch(() => false)) {
      await cancelButton.click()
    }
  }
}

/**
 * 向登录输入框写入真实凭据，同时避免 Playwright reporter 把账号或密码参数写入步骤标题。
 * @param {import('@playwright/test').Locator} input 用户名或密码输入框定位器。
 * @param {string} credential 当前预登记账号的用户名或密码。
 * @returns {Promise<void>} 输入事件与变更事件派发完成后结束。
 */
export async function fillCredentialInput(input, credential) {
  await input.evaluate((element, value) => {
    if (!(element instanceof HTMLInputElement)) {
      throw new Error('登录凭据只能写入 HTMLInputElement')
    }
    // 调用原生 value setter 并派发真实表单事件，保持 Vue 双向绑定与用户输入行为一致。
    const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set
    if (!valueSetter) {
      throw new Error('当前浏览器不支持登录输入框原生 value setter')
    }
    valueSetter.call(element, value)
    element.dispatchEvent(new Event('input', { bubbles: true, composed: true }))
    element.dispatchEvent(new Event('change', { bubbles: true, composed: true }))
  }, credential)
}

/**
 * 通过登录页、真实 `/login`、JWT/Redis 和动态路由完成职责分离角色登录。
 * @param {import('@playwright/test').Page} page 新建且无预置 Token 的浏览器页面。
 * @param {{roleKey: string, username: string, password: string, requiredRoles?: string[]}} account 进程环境注入的预登记账号；样例业务账号可声明必须包含的角色集合。
 * @returns {Promise<{roleKey: string, captchaDisabled: boolean}>} 不含账号、密码和 Token 的登录证据摘要。
 */
export async function loginThroughUi(page, account) {
  const captchaPromise = page.waitForResponse(response => matchesEndpoint(response, '/captchaImage', 'GET'))
  await page.goto('/login', { waitUntil: 'domcontentloaded' })
  const captchaPayload = await expectAjaxSuccess(await captchaPromise, '/captchaImage')
  expect(captchaPayload.captchaEnabled, 'E2E 隔离环境必须关闭验证码').toBe(false)

  await fillCredentialInput(page.locator('input[type="text"]').first(), account.username)
  await fillCredentialInput(page.locator('input[type="password"]'), account.password)

  const loginPromise = page.waitForResponse(response => matchesEndpoint(response, '/login', 'POST'))
  const infoPromise = page.waitForResponse(response => matchesEndpoint(response, '/getInfo', 'GET'))
  const routerPromise = page.waitForResponse(response => matchesEndpoint(response, '/getRouters', 'GET'))
  await page.locator('.login-form .el-button--primary').click()

  const loginPayload = await expectAjaxSuccess(await loginPromise, '/login')
  expect(typeof loginPayload.token, '/login 必须返回真实 Token').toBe('string')
  expect(loginPayload.token.length, '/login Token 不能为空').toBeGreaterThan(20)
  const infoPayload = await expectAjaxSuccess(await infoPromise, '/getInfo')
  await expectAjaxSuccess(await routerPromise, '/getRouters')
  const actualRoles = Array.isArray(infoPayload.roles) ? infoPayload.roles : []
  if (Array.isArray(account.requiredRoles) && account.requiredRoles.length > 0) {
    // 样例业务账号允许同时承担业务角色，但必须真实包含当前 UI 场景要求的工作流角色。
    for (const requiredRole of account.requiredRoles) {
      expect(actualRoles, `账号必须包含 ${requiredRole} 角色`).toContain(requiredRole)
    }
  } else {
    expect(actualRoles, '职责分离账号必须仅绑定目标工作流角色').toEqual([account.roleKey])
  }
  await expect(page).not.toHaveURL(/\/login(?:\?|$)/)
  await dismissPasswordNotice(page)
  return { roleKey: account.roleKey, captchaDisabled: true }
}

/**
 * 通过头像菜单和确认框调用真实 `/logout`，确保 Redis 登录 Token 在用例结束时被删除。
 * @param {import('@playwright/test').Page} page 当前已登录的浏览器页面。
 * @param {string} roleKey 当前职责分离角色键，仅写入脱敏证据。
 * @returns {Promise<{roleKey: string, loggedOut: boolean}>} 不含用户名和 Token 的注销证据摘要。
 */
export async function logoutThroughUi(page, roleKey) {
  await page.goto('/index')
  const avatar = page.locator('.avatar-wrapper')
  await expect(avatar, '注销前用户头像必须可见').toBeVisible()
  await avatar.hover()
  const logoutItem = page.getByText('退出登录', { exact: true })
  await expect(logoutItem, '头像菜单必须提供退出登录').toBeVisible()
  await logoutItem.click()
  const confirmation = page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true })
  await expect(confirmation, '注销必须经过确认').toBeVisible()
  const logoutPromise = page.waitForResponse(response => matchesEndpoint(response, '/logout', 'POST'))
  await confirmation.click()
  const logoutResponse = await logoutPromise
  expect(logoutResponse.status(), '/logout HTTP 状态').toBe(200)
  // 前端只在 AjaxResult 成功分支清理 Token 并跳转，回到登录页可避免与响应正文释放发生竞态。
  await expect(page).toHaveURL(/\/login(?:\?|$)/)
  return { roleKey, loggedOut: true }
}

export const test = base.extend({
  roleKey: ['workflow_admin', { option: true }],
  /**
   * 为每个用例建立无预置 Token 的真实登录页面，并统一收集脱敏异常与失败 trace。
   * @param {{page: import('@playwright/test').Page, context: import('@playwright/test').BrowserContext, roleKey: string}} fixtures Playwright 页面、上下文和目标角色。
   * @param {(page: import('@playwright/test').Page) => Promise<void>} use 把已登录页面交给测试体的 fixture 回调。
   * @param {import('@playwright/test').TestInfo} testInfo 当前用例状态与证据目录。
   * @returns {Promise<void>} 测试体及 fixture 清理完成后结束。
   */
  workflowPage: async ({ page, context, roleKey }, use, testInfo) => {
    const account = accounts[roleKey]
    if (!account) {
      throw new Error(`未定义 E2E 角色：${roleKey}`)
    }
    const pageErrors = []
    const consoleErrors = []
    page.on('pageerror', error => pageErrors.push(redactAccountSecrets(error.stack || error.message, account)))
    page.on('console', message => {
      if (message.type() === 'error') {
        consoleErrors.push(redactAccountSecrets(message.text(), account))
      }
    })

    const loginEvidence = await loginThroughUi(page, account)
    await testInfo.attach('login-evidence.json', {
      body: Buffer.from(JSON.stringify(loginEvidence, null, 2)),
      contentType: 'application/json'
    })
    // 登录完成后才开始无 DOM/网络快照 trace，避免登录输入值和 Authorization 头进入 trace。
    await context.tracing.start({ screenshots: true, snapshots: false, sources: false, title: testInfo.title })
    let logoutError = null
    try {
      await use(page)
      expect(pageErrors, '页面不得出现未捕获 JavaScript 异常').toEqual([])
      expect(consoleErrors, '页面不得输出 console.error').toEqual([])
    } finally {
      try {
        const logoutEvidence = await logoutThroughUi(page, roleKey)
        await testInfo.attach('logout-evidence.json', {
          body: Buffer.from(JSON.stringify(logoutEvidence, null, 2)),
          contentType: 'application/json'
        })
      } catch (error) {
        logoutError = new Error(`真实 UI 注销失败：${redactAccountSecrets(error?.message || error, account)}`)
      }
      const tracePath = testInfo.outputPath('trace.zip')
      if (testInfo.status !== testInfo.expectedStatus || pageErrors.length || consoleErrors.length || logoutError) {
        await context.tracing.stop({ path: tracePath })
        await testInfo.attach('trace', { path: tracePath, contentType: 'application/zip' })
      } else {
        await context.tracing.stop()
      }
      if (logoutError && testInfo.status === testInfo.expectedStatus) {
        throw logoutError
      }
    }
  }
})

export { expect }
