import { expect } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../e2e/support/http.js'

export class WorkflowIntegrationPage {
  /**
   * 创建集成运维页面对象。
   * @param {import('@playwright/test').Page} page 已完成流程管理员登录的浏览器页面。
   */
  constructor(page) {
    this.page = page
  }

  /**
   * 通过集成账号页面创建一次性 Token，并保持敏感正文仅存在于当前测试进程内存。
   * @param {{name:string,scopes:string[],allowedVariables?:string[],rateLimitPerMinute?:number}} credential 账号名称、事件范围、变量白名单和限流。
   * @returns {Promise<{credentialId:string,token:string}>} 正式凭据主键和只显示一次的明文 Token。
   */
  async createCredential(credential) {
    await this.page.goto('/workflow/extensions/integrationCredential')
    await expect(this.page.getByRole('heading', { name: '集成账号', exact: true })).toBeVisible()
    await this.page.getByRole('button', { name: '新增账号', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增集成账号' })
    await dialog.getByLabel('账号名称').fill(credential.name)
    for (const scope of credential.scopes) {
      const checkbox = dialog.getByRole('checkbox', { name: scope, exact: true })
      if (!await checkbox.isChecked()) {
        // Element Plus 隐藏原生 checkbox，真实用户点击可见标签容器完成范围选择。
        await dialog.locator('.el-checkbox').filter({ hasText: scope }).click()
      }
    }
    await dialog.getByLabel('变量白名单').fill((credential.allowedVariables || []).join(', '))
    const rateLimit = dialog.getByRole('spinbutton', { name: '每分钟上限' })
    await rateLimit.fill(String(credential.rateLimitPerMinute || 60))
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/integration-credential', 'POST'))
    await dialog.getByRole('button', { name: '创建账号', exact: true }).click()
    const payload = await expectAjaxSuccess(await responsePromise, '/workflow/integration-credential')
    const credentialId = String(payload.data?.credentialId || '')
    const token = String(payload.data?.token || '')
    expect(credentialId, '集成账号创建必须返回正式凭据主键').not.toBe('')
    expect(token, '集成账号创建必须仅本次返回明文 Token').not.toBe('')
    const secretDialog = this.page.getByRole('dialog', { name: '保存集成 Token' })
    await expect(secretDialog).toBeVisible()
    await expect(secretDialog.locator('.secret-value code')).toHaveText(token)
    await secretDialog.getByRole('button', { name: '我已保存', exact: true }).click()
    await expect(secretDialog).toBeHidden()
    return { credentialId, token }
  }

  /**
   * 通过集成账号列表永久吊销当前测试创建的凭据。
   * @param {string} credentialName 唯一测试账号名称。
   * @returns {Promise<void>} 正式吊销接口成功且列表状态回显“已吊销”后结束。
   */
  async revokeCredential(credentialName) {
    await this.page.goto('/workflow/extensions/integrationCredential')
    const filter = this.page.getByPlaceholder('账号名称或 Token 前缀')
    await filter.fill(credentialName)
    const row = this.page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: credentialName })
    await expect(row, `集成账号 ${credentialName} 必须唯一`).toHaveCount(1)
    await row.getByRole('button', { name: '吊销账号', exact: true }).click()
    const messageBox = this.page.locator('.el-message-box')
    await expect(messageBox).toContainText(credentialName)
    const responsePromise = this.page.waitForResponse(response => (
      response.request().method() === 'DELETE'
      && /\/workflow\/integration-credential\/\d+$/u.test(new URL(response.url()).pathname)
    ))
    await messageBox.getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/integration-credential/{id}')
    await expect(this.page.getByText('集成账号已吊销', { exact: true })).toBeVisible()
    await expect(row.getByText('已吊销', { exact: true })).toBeVisible()
  }

  /**
   * 在运行事件页面按 requestId 核对正式脱敏审计结果。
   * @param {{requestId:string,eventName:string,status:string,resultCode:string}} expected 运行事件唯一键和预期结果。
   * @returns {Promise<void>} 页面刷新后唯一审计行与期望一致。
   */
  async expectRuntimeEventAudit(expected) {
    await this.page.goto('/workflow/extensions/runtimeEvent')
    await expect(this.page.getByRole('heading', { name: '运行事件', exact: true })).toBeVisible()
    const search = this.page.getByPlaceholder('requestId、事件或关联值')
    await search.fill(expected.requestId)
    await this.page.getByRole('button', { name: '刷新', exact: true }).click()
    const row = this.page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: expected.requestId })
    await expect(row, `运行事件 ${expected.requestId} 必须唯一`).toHaveCount(1)
    await expect(row).toContainText(expected.eventName)
    await expect(row).toContainText(expected.status)
    await expect(row).toContainText(expected.resultCode)
  }
}
