import { test as base, expect } from '@playwright/test'
import { loginThroughUi, logoutThroughUi } from '../../e2e/fixtures/workflow.js'
import { loadWorkflowAccounts } from '../../e2e/support/environment.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../e2e/support/http.js'

const accounts = loadWorkflowAccounts()

export const test = base.extend({
  roleKey: ['workflow_admin', { option: true }],
  /**
   * 通过真实登录页建立职责角色会话，并把登录响应中的权限集合交给当前用例核验。
   * @param {{page:import('@playwright/test').Page,context:import('@playwright/test').BrowserContext,roleKey:string}} fixtures 浏览器页面、上下文和目标角色。
   * @param {(session:{page:import('@playwright/test').Page,roleKey:string,permissions:string[]})=>Promise<void>} use 测试体回调。
   * @param {import('@playwright/test').TestInfo} testInfo 当前用例信息。
   * @returns {Promise<void>} 登录、测试、注销和证据收集结束后完成。
   */
  uiSession: async ({ page, context, roleKey }, use, testInfo) => {
    const account = accounts[roleKey]
    if (!account) throw new Error(`未配置 UI 测试角色：${roleKey}`)
    const infoPromise = page.waitForResponse(response => matchesEndpoint(response, '/getInfo', 'GET'))
    await loginThroughUi(page, account)
    const info = await expectAjaxSuccess(await infoPromise, '/getInfo')
    const permissions = [...new Set(Array.isArray(info.permissions) ? info.permissions : [])].sort()
    await testInfo.attach('permission-evidence.json', {
      body: Buffer.from(JSON.stringify({ roleKey, permissionCount: permissions.length }, null, 2)),
      contentType: 'application/json'
    })
    await context.tracing.start({ screenshots: true, snapshots: false, sources: false, title: testInfo.title })
    let logoutError = null
    try {
      await use({ page, roleKey, permissions })
    } finally {
      try {
        await logoutThroughUi(page, roleKey)
      } catch (error) {
        logoutError = error
      }
      const tracePath = testInfo.outputPath('trace.zip')
      if (testInfo.status !== testInfo.expectedStatus || logoutError) {
        await context.tracing.stop({ path: tracePath })
        await testInfo.attach('trace', { path: tracePath, contentType: 'application/zip' })
      } else {
        await context.tracing.stop()
      }
      if (logoutError && testInfo.status === testInfo.expectedStatus) throw logoutError
    }
  }
})

export { expect }
