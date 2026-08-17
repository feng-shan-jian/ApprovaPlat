import { test, expect } from '../../fixtures/workflow.js'
import { captureResponse, expectAjaxSuccess } from '../../../e2e/support/http.js'
import {
  ROLE_REQUIRED_PERMISSIONS,
  WORKFLOW_ROLE_KEYS,
  WORKFLOW_ROUTE_CONTRACTS,
  isRouteAllowed
} from '../../support/contracts.js'

for (const [roleIndex, roleKey] of WORKFLOW_ROLE_KEYS.entries()) {
  test.describe(`${roleKey} 全量页面权限`, () => {
    test.use({ roleKey })

    test(`@smoke [UI-RBAC-${String(roleIndex + 1).padStart(3, '0')}] 真实登录覆盖21页和75个按钮权限`, async ({ uiSession }) => {
      const { page, permissions } = uiSession
      if (roleKey !== 'workflow_admin') {
        expect(permissions, `${roleKey} 必须包含其职责按钮权限`).toEqual(expect.arrayContaining(ROLE_REQUIRED_PERMISSIONS[roleKey]))
        expect(permissions.every(permission => permission === '*:*:*' || permission.startsWith('workflow:')), `${roleKey} 不得携带无关系统权限`).toBe(true)
      }

      for (const contract of WORKFLOW_ROUTE_CONTRACTS) {
        const menuCount = isRouteAllowed(contract, roleKey) ? 1 : 0
        await expect(page.locator(`.sidebar-container a[href="${contract.path}"]`), `${contract.path} 菜单`).toHaveCount(menuCount)
      }

      for (const contract of WORKFLOW_ROUTE_CONTRACTS) {
        if (!isRouteAllowed(contract, roleKey)) continue
        const response = await captureResponse(page, contract.endpoint, () => page.goto(contract.path))
        await expectAjaxSuccess(response, contract.endpoint)
        await expect(page.locator('.wscn-http404-container')).toHaveCount(0)
        await expect(page.locator('.app-container .el-table').first(), `${contract.path} 真实列表`).toBeVisible()
      }

      for (const contract of WORKFLOW_ROUTE_CONTRACTS) {
        if (isRouteAllowed(contract, roleKey)) continue
        const forbiddenRequests = []
        const listener = request => {
          if (new URL(request.url()).pathname.endsWith(contract.endpoint)) forbiddenRequests.push(request.method())
        }
        page.on('request', listener)
        try {
          await page.goto(contract.path)
          await expect(page.locator('.wscn-http404-container'), `${contract.path} 直接 URL 必须拒绝`).toBeVisible()
          expect(forbiddenRequests, `${contract.path} 拒绝后不得调用业务接口`).toEqual([])
        } finally {
          page.off('request', listener)
        }
      }
    })
  })
}
