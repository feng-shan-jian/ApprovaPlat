import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import {
  callWorkflowApi,
  closeWorkflowRoleSessions,
  openWorkflowRoleSession
} from './support/workflow-fixture.js'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'

test.describe.configure({ mode: 'serial' })

/**
 * 通过真实页面和 API 清理本轮事件目录，避免失败重跑污染正式目录。
 * @param {import('@playwright/test').Page} page 已登录管理员页面。
 * @param {number|null} eventCodeId 本轮创建的目录主键。
 * @returns {Promise<string[]>} 脱敏清理错误集合。
 */
async function cleanupEventCode(page, eventCodeId) {
  if (!eventCodeId) return []
  try {
    const rows = (await callWorkflowApi(page, 'GET', '/workflow/bpmn-event/codes')).data || []
    const row = rows.find(item => Number(item.eventCodeId) === eventCodeId)
    if (!row) return []
    if (row.status === 'ENABLED') {
      await callWorkflowApi(page, 'PUT', `/workflow/bpmn-event/codes/${eventCodeId}/status`, {
        data: { enabled: false }
      })
    }
    // 事件目录是正式审计引用对象，页面没有删除入口；停用后保留历史追踪。
    return []
  } catch (error) {
    return [String(error?.message || error)]
  }
}

test('管理员通过真实页面完成错误升级编码、审计和通知页签回显', async ({ browser }) => {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  const eventCode = `E2E_BUSINESS_ERROR_${suffix}`
  const eventName = `页面业务错误_${suffix}`
  const sessions = []
  let adminSession = null
  let eventCodeId = null
  try {
    adminSession = await openWorkflowRoleSession(browser, 'workflow_admin')
    sessions.push(adminSession)
    const page = adminSession.page

    const listPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/bpmn-event/codes', 'GET'))
    await page.goto('/workflow/bpmnEvent')
    await expectAjaxSuccess(await listPromise, '/workflow/bpmn-event/codes')
    await expect(page.getByRole('heading', { name: '错误与升级边界' })).toBeVisible()

    await page.getByRole('button', { name: '新增编码' }).click()
    const dialog = page.getByRole('dialog', { name: '新增事件编码' })
    await dialog.getByRole('textbox', { name: '稳定编码' }).fill(eventCode)
    await dialog.getByRole('textbox', { name: '显示名称' }).fill(eventName)
    const createPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/bpmn-event/codes', 'POST'))
    await dialog.getByRole('button', { name: '保存' }).click()
    const created = await expectAjaxSuccess(await createPromise, '/workflow/bpmn-event/codes')
    eventCodeId = Number(created.data?.eventCodeId)
    expect(eventCodeId).toBeGreaterThan(0)

    await expect(page.locator('.el-table__body tr').filter({ hasText: eventCode })).toContainText('已启用')
    await page.getByRole('tab', { name: '运行审计' }).click()
    await expect(page.getByRole('tab', { name: '运行审计' })).toHaveAttribute('aria-selected', 'true')
    await page.getByRole('tab', { name: '我的通知' }).click()
    await expect(page.getByRole('tab', { name: '我的通知' })).toHaveAttribute('aria-selected', 'true')
  } finally {
    const cleanupErrors = adminSession ? await cleanupEventCode(adminSession.page, eventCodeId) : []
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    expect([...cleanupErrors, ...sessionErrors], 'BPMN 事件浏览器验收必须清理会话').toEqual([])
  }
})
