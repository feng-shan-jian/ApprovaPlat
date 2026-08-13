import { expect } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../e2e/support/http.js'

export class WorkflowBpmnEventPage {
  /**
   * 创建 BPMN 业务事件管理页面对象。
   * @param {import('@playwright/test').Page} page 已完成流程管理员登录的浏览器页面。
   */
  constructor(page) {
    this.page = page
  }

  /**
   * 通过编码目录页面创建一条启用的 Error 或 Escalation 正式编码。
   * @param {{eventType:'ERROR'|'ESCALATION',eventCode:string,eventName:string,notificationPolicy?:'NONE'|'INITIATOR',description:string}} eventCode 事件类型、稳定编码、名称、通知策略和说明。
   * @returns {Promise<void>} 创建接口成功且列表唯一回显启用状态后结束。
   */
  async createEventCode(eventCode) {
    await this.page.goto('/workflow/extensions/bpmnEvent')
    await expect(this.page.getByRole('heading', { name: '错误、升级与审批 SLA', exact: true })).toBeVisible()
    const existingRow = this.codeRow(eventCode.eventCode)
    const existingCount = await existingRow.count()
    expect(existingCount, `BPMN 事件编码 ${eventCode.eventCode} 不得重复`).toBeLessThanOrEqual(1)
    if (existingCount === 1) {
      await expect(existingRow.getByText('已启用', { exact: true })).toBeVisible()
      return
    }

    await this.page.getByRole('button', { name: '新增编码', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '新增事件编码' })
    const typeLabel = eventCode.eventType === 'ERROR' ? '业务错误' : '业务升级'
    await dialog.locator('.el-segmented__item').filter({ hasText: typeLabel }).click()
    await expect(dialog.locator('.el-segmented__item.is-selected').filter({ hasText: typeLabel }),
      '事件类型必须通过可见分段控件回显').toHaveCount(1)
    await dialog.getByLabel('稳定编码').fill(eventCode.eventCode)
    await dialog.getByLabel('显示名称').fill(eventCode.eventName)
    const notification = dialog.locator('.el-form-item').filter({ hasText: '通知策略' }).getByRole('combobox')
    await notification.locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
      .locator('.el-select__wrapper').click()
    const notificationLabel = (eventCode.notificationPolicy || 'NONE') === 'INITIATOR'
      ? '通知流程发起人' : '不通知'
    await this.page.locator('.el-select-dropdown:visible').getByRole('option', {
      name: notificationLabel, exact: true
    }).click()
    await dialog.getByLabel('业务说明').fill(eventCode.description)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/bpmn-event/codes', 'POST'))
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/bpmn-event/codes')
    await expect(this.page.getByText('BPMN 事件编码已保存', { exact: true })).toBeVisible()
    const row = this.codeRow(eventCode.eventCode)
    await expect(row, `BPMN 事件编码 ${eventCode.eventCode} 必须唯一回显`).toHaveCount(1)
    await expect(row.getByText(typeLabel, { exact: true })).toBeVisible()
    await expect(row.getByText('已启用', { exact: true })).toBeVisible()
  }

  /**
   * 通过编码目录页面停用测试创建的正式事件编码。
   * @param {string} eventCode 稳定事件编码。
   * @returns {Promise<void>} 用户确认后列表状态回显已停用。
   */
  async disableEventCode(eventCode) {
    await this.page.goto('/workflow/extensions/bpmnEvent')
    const row = this.codeRow(eventCode)
    await expect(row, `BPMN 事件编码 ${eventCode} 必须唯一`).toHaveCount(1)
    if (await row.getByText('已停用', { exact: true }).isVisible().catch(() => false)) return
    await row.getByRole('button', { name: '停用', exact: true }).click()
    const messageBox = this.page.locator('.el-message-box')
    await expect(messageBox).toContainText('确认停用')
    const responsePromise = this.page.waitForResponse(response => (
      response.request().method() === 'PUT'
      && /\/workflow\/bpmn-event\/codes\/\d+\/status$/u.test(new URL(response.url()).pathname)
    ))
    await messageBox.getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/bpmn-event/codes/{id}/status')
    await expect(row.getByText('已停用', { exact: true })).toBeVisible()
  }

  /**
   * 在运行审计页签核对一条真实捕获的 BPMN 业务事件。
   * @param {{processInstanceId:string,eventType:'ERROR'|'ESCALATION',eventCode:string,sourceElementId:string,boundaryEventId:string}} expected 实例、事件和捕获边界的期望值。
   * @returns {Promise<void>} 当前管理权限范围内唯一审计行完整回显后结束。
   */
  async expectCapturedAudit(expected) {
    await this.page.goto('/workflow/extensions/bpmnEvent')
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/bpmn-event/audit', 'GET'))
    await this.page.getByRole('tab', { name: '运行审计', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/bpmn-event/audit')
    const row = this.page.locator('.el-table__body-wrapper tbody tr')
      .filter({ hasText: expected.processInstanceId })
      .filter({ hasText: expected.eventCode })
    await expect(row, `实例 ${expected.processInstanceId} 的 BPMN 事件审计必须唯一`).toHaveCount(1)
    await expect(row).toContainText(expected.eventType)
    await expect(row).toContainText(expected.sourceElementId)
    await expect(row).toContainText('CAPTURED')
    await expect(row).toContainText(expected.boundaryEventId)
  }

  /**
   * 返回编码目录中包含指定稳定编码的唯一候选行。
   * @param {string} eventCode 稳定事件编码。
   * @returns {import('@playwright/test').Locator} 当前编码目录表格行定位器。
   */
  codeRow(eventCode) {
    return this.page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: eventCode })
  }
}
