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
 * 确认 Element Plus 的全局确认框并等待目标接口真实完成。
 * @param {import('@playwright/test').Page} page 已登录管理员页面。
 * @param {Promise<import('@playwright/test').Response>} responsePromise 目标写接口响应等待器。
 * @returns {Promise<any>} 已通过 HTTP 与 AjaxResult 校验的响应正文。
 */
async function confirmWrite(page, responsePromise) {
  const confirmation = page.locator('.el-message-box')
    .getByRole('button', { name: '确定', exact: true })
  await expect(confirmation).toBeVisible()
  await confirmation.click()
  return expectAjaxSuccess(await responsePromise, '扩展注册表写接口')
}

/**
 * 在失败分支通过正式 API 恢复可删除状态并清理当前测试目录。
 * @param {import('@playwright/test').Page} page 已登录管理员页面。
 * @param {number|null} extensionId 当前测试创建的目录主键。
 * @returns {Promise<string[]>} 脱敏清理错误集合。
 */
async function cleanupExtension(page, extensionId) {
  if (!extensionId) return []
  const errors = []
  try {
    const listPayload = await callWorkflowApi(page, 'GET', '/workflow/extension/list')
    const current = (listPayload.data || []).find(row => Number(row.extensionId) === extensionId)
    if (!current) return errors
    if (current.status === 'ENABLED') {
      await callWorkflowApi(page, 'PUT', `/workflow/extension/${extensionId}/status`, {
        data: { enabled: false }
      })
    }
    await callWorkflowApi(page, 'DELETE', `/workflow/extension/${extensionId}`)
  } catch (error) {
    errors.push(String(error?.message || error))
  }
  return errors
}

test('管理员通过真实页面完成扩展目录、版本、处理器、启停和受约束删除', async ({ browser }) => {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  const extensionName = `扩展页面验收_${suffix}`
  const extensionKey = `approva.e2e-${suffix}`
  const sessions = []
  let adminSession = null
  let extensionId = null
  let removed = false

  try {
    adminSession = await openWorkflowRoleSession(browser, 'workflow_admin')
    sessions.push(adminSession)
    const page = adminSession.page

    const listPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/extension/list', 'GET'))
    const handlersPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/extension/installed-handlers/java', 'GET'))
    await page.goto('/workflow/extension')
    await expectAjaxSuccess(await listPromise, '/workflow/extension/list')
    const handlerPayload = await expectAjaxSuccess(
      await handlersPromise, '/workflow/extension/installed-handlers/java')
    expect(handlerPayload.data).toEqual(expect.arrayContaining([
      expect.objectContaining({ implementationKey: 'SET_VARIABLE' })
    ]))

    await page.getByRole('button', { name: '已安装处理器' }).click()
    const handlerDialog = page.getByRole('dialog', { name: '服务端已安装处理器' })
    await expect(handlerDialog).toContainText('SET_VARIABLE')
    await expect(handlerDialog).toContainText('配置 Schema')
    await handlerDialog.getByRole('button', { name: '关闭此对话框' }).click()

    await page.getByRole('button', { name: '新增目录' }).click()
    const createDialog = page.getByRole('dialog', { name: '新增扩展目录' })
    await createDialog.getByRole('textbox', { name: '目录名称' }).fill(extensionName)
    await createDialog.getByRole('textbox', { name: '稳定键' }).fill(extensionKey)
    await createDialog.getByRole('textbox', { name: '业务说明' }).fill('正式页面自动验收目录')
    const createPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/extension', 'POST'))
    await createDialog.getByRole('button', { name: '保存目录' }).click()
    const createPayload = await expectAjaxSuccess(await createPromise, '/workflow/extension')
    extensionId = Number(createPayload.data?.extensionId)
    expect(extensionId).toBeGreaterThan(0)

    const keywordInput = page.getByRole('textbox', { name: '检索' })
    await keywordInput.fill(extensionKey)
    await page.getByRole('button', { name: '查询' }).click()
    const row = page.locator('.el-table__body tr').filter({ hasText: extensionKey })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('未发布')
    await expect(row).toContainText('已启用')

    await row.getByRole('button', { name: '发布新版本' }).click()
    const versionDialog = page.getByRole('dialog', { name: '发布不可变版本' })
    const extensionNameInput = versionDialog.locator('.el-form-item')
      .filter({ hasText: '扩展目录' }).locator('input')
    await expect(extensionNameInput).toHaveValue(extensionName)
    const handlerSelect = versionDialog.getByRole('combobox', { name: '处理器' })
    await handlerSelect.press('Enter')
    await page.getByRole('option', { name: /设置流程变量/ }).click()
    const versionPromise = page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/extension/${extensionId}/versions`, 'POST'))
    await versionDialog.getByRole('button', { name: '发布版本' }).click()
    await confirmWrite(page, versionPromise)
    await expect(row).toContainText('V1')
    await expect(row).toContainText('SET_VARIABLE')

    const disablePromise = page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/extension/${extensionId}/status`, 'PUT'))
    await row.getByRole('button', { name: '停用目录' }).click()
    await confirmWrite(page, disablePromise)
    await expect(row).toContainText('已停用')

    const enablePromise = page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/extension/${extensionId}/status`, 'PUT'))
    await row.getByRole('button', { name: '启用目录' }).click()
    await confirmWrite(page, enablePromise)
    await expect(row).toContainText('已启用')

    const finalDisablePromise = page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/extension/${extensionId}/status`, 'PUT'))
    await row.getByRole('button', { name: '停用目录' }).click()
    await confirmWrite(page, finalDisablePromise)
    await expect(row).toContainText('已停用')

    const deletePromise = page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/extension/${extensionId}`, 'DELETE'))
    await row.getByRole('button', { name: '删除目录' }).click()
    await confirmWrite(page, deletePromise)
    await expect(row).toHaveCount(0)
    removed = true

    const finalList = await callWorkflowApi(page, 'GET', '/workflow/extension/list')
    expect((finalList.data || []).some(item => Number(item.extensionId) === extensionId)).toBe(false)
  } finally {
    const cleanupErrors = adminSession && !removed
      ? await cleanupExtension(adminSession.page, extensionId)
      : []
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    expect([...cleanupErrors, ...sessionErrors], '扩展页面验收必须清理正式目录和浏览器会话').toEqual([])
  }
})
