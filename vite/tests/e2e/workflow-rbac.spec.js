import { test, expect } from './fixtures/workflow.js'
import { WORKFLOW_ROLE_KEYS } from './support/environment.js'
import { WORKFLOW_ROUTE_CONTRACTS, isRouteAllowed } from './support/contracts.js'
import { captureResponse, expectAjaxSuccess } from './support/http.js'

/**
 * 核对一个角色的菜单、允许页面、直接 URL 拒绝和禁止 API 零调用矩阵。
 * @param {import('@playwright/test').Page} page 已通过真实 UI 登录的页面。
 * @param {string} roleKey 当前职责分离角色键。
 * @returns {Promise<void>} 11 个页面单元全部通过后结束。
 */
async function verifyRoleRouteMatrix(page, roleKey) {
  for (const contract of WORKFLOW_ROUTE_CONTRACTS) {
    const expectedCount = isRouteAllowed(contract, roleKey) ? 1 : 0
    await expect(page.locator(`.sidebar-container a[href="${contract.path}"]`), `${contract.path} 菜单可见性`).toHaveCount(expectedCount)
  }

  for (const contract of WORKFLOW_ROUTE_CONTRACTS) {
    if (!isRouteAllowed(contract, roleKey)) continue
    const response = await captureResponse(page, contract.endpoint, () => page.goto(contract.path))
    await expectAjaxSuccess(response, contract.endpoint)
    await expect(page.locator('.wscn-http404-container')).toHaveCount(0)
    await expect(page.locator('.app-container .el-table').first(), `${contract.path} 真实表格`).toBeVisible()
  }

  for (const contract of WORKFLOW_ROUTE_CONTRACTS) {
    if (isRouteAllowed(contract, roleKey)) continue
    const forbiddenRequests = []
    /**
     * 记录被拒绝页面是否仍错误调用对应业务接口，用于验证前端拒绝后的零副作用边界。
     * @param {import('@playwright/test').Request} request 当前浏览器发出的网络请求。
     * @returns {void} 命中受检入口时只更新本地请求摘要数组。
     */
    const requestListener = request => {
      if (new URL(request.url()).pathname.endsWith(contract.endpoint)) {
        forbiddenRequests.push(`${request.method()} ${contract.endpoint}`)
      }
    }
    page.on('request', requestListener)
    try {
      await page.goto(contract.path)
      await expect(page.locator('.wscn-http404-container'), `${contract.path} 直接 URL 必须拒绝`).toBeVisible()
      expect(forbiddenRequests, `${contract.path} 被拒绝后不得调用业务 API`).toEqual([])
    } finally {
      page.off('request', requestListener)
    }
  }
}

for (const roleKey of WORKFLOW_ROLE_KEYS) {
  test.describe(`${roleKey} 页面与直接 URL 权限`, () => {
    test.use({ roleKey })

    /**
     * 使用当前角色执行完整页面矩阵。
     * @param {{workflowPage: import('@playwright/test').Page}} fixtures 已完成真实登录的 Playwright fixture。
     * @returns {Promise<void>} 当前角色 11 个页面单元全部通过后结束。
     */
    test('11 个工作流页面与七工作台遵循正式菜单授权', async ({ workflowPage }) => {
      await verifyRoleRouteMatrix(workflowPage, roleKey)
    })
  })
}
