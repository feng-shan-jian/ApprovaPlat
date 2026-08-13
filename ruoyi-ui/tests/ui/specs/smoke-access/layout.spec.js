import { test, expect } from '../../fixtures/workflow.js'

const viewports = [
  { width: 1366, height: 768 },
  { width: 1440, height: 960 },
  { width: 1920, height: 1080 }
]

test.use({ roleKey: 'workflow_designer' })

test('@smoke [UI-LAYOUT-001] 关键管理页面在三种桌面视口可见且可操作', async ({ uiSession }) => {
  const { page } = uiSession
  for (const viewport of viewports) {
    await page.setViewportSize(viewport)
    for (const pagePath of ['/workflow/category', '/workflow/model', '/workflow/extensions/bpmnEvent']) {
      await page.goto(pagePath)
      const table = page.locator('.app-container .el-table').first()
      await expect(table).toBeVisible()
      const box = await table.boundingBox()
      expect(box, `${pagePath} 必须具有稳定布局尺寸`).not.toBeNull()
      expect(box.x).toBeGreaterThanOrEqual(0)
      expect(box.y).toBeGreaterThanOrEqual(0)
      expect(box.width).toBeGreaterThan(480)
      const firstEnabledButton = page.locator('.app-container button:not([disabled])').first()
      await expect(firstEnabledButton).toBeVisible()
      await expect(firstEnabledButton).toBeEnabled()
    }
  }
})
