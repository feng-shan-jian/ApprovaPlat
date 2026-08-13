import { expect } from '@playwright/test'
import { loginThroughUi, logoutThroughUi } from '../../e2e/fixtures/workflow.js'
import { loadWorkflowAccounts } from '../../e2e/support/environment.js'

const accounts = loadWorkflowAccounts()

/**
 * 创建通过真实登录页认证的独立角色浏览器上下文。
 * @param {import('@playwright/test').Browser} browser Playwright 浏览器实例。
 * @param {string} roleKey 五职责角色键。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试信息。
 * @returns {Promise<{page:import('@playwright/test').Page,close:(failed?:boolean)=>Promise<void>}>} 页面及安全注销方法。
 */
export async function openRoleSession(browser, roleKey, testInfo) {
  const account = accounts[roleKey]
  if (!account) throw new Error(`未配置 UI 测试角色：${roleKey}`)
  return openAccountSession(browser, account, testInfo, roleKey)
}

/**
 * 为正式样例业务账号建立无 Token 注入的独立浏览器会话。
 * @param {import('@playwright/test').Browser} browser Playwright 浏览器实例。
 * @param {{roleKey:string,username:string,password:string,requiredRoles?:string[]}} account 通过进程配置或正式样例目录登记的登录账号。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试信息。
 * @param {string} traceKey 脱敏 trace 的职责标识，不使用用户名作为证据文件名。
 * @returns {Promise<{page:import('@playwright/test').Page,close:(failed?:boolean)=>Promise<void>}>} 页面及安全注销方法。
 */
export async function openAccountSession(browser, account, testInfo, traceKey = account.roleKey) {
  const context = await browser.newContext({
    baseURL: testInfo.project.use.baseURL,
    viewport: { width: 1440, height: 960 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    acceptDownloads: true
  })
  const page = await context.newPage()
  await loginThroughUi(page, account)
  // 运行期可能出现一次性集成 Token，trace 禁止采集截图和 DOM，避免失败证据泄漏凭据正文。
  await context.tracing.start({ screenshots: false, snapshots: false, sources: false, title: `${testInfo.title}-${traceKey}` })
  let closed = false
  return {
    page,
    /**
     * 通过真实头像菜单注销并关闭当前角色上下文。
     * @param {boolean} failed 当前场景是否失败；失败时保存脱敏 trace。
     * @returns {Promise<void>} 注销、证据和上下文关闭完成后结束。
     */
    async close(failed = false) {
      if (closed) return
      closed = true
      let logoutError = null
      try {
        await logoutThroughUi(page, traceKey)
      } catch (error) {
        logoutError = error
      }
      if (failed || logoutError) {
        const tracePath = testInfo.outputPath(`${traceKey}-trace.zip`)
        await context.tracing.stop({ path: tracePath })
        await testInfo.attach(`${traceKey}-trace`, { path: tracePath, contentType: 'application/zip' })
      } else {
        await context.tracing.stop()
      }
      await context.close()
      expect(logoutError, `${traceKey} 必须通过真实 UI 注销`).toBeNull()
    }
  }
}
