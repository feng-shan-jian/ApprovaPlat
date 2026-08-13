import { expect } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../e2e/support/http.js'

export class WorkflowDeploymentPage {
  /**
   * 创建流程部署管理页面对象。
   * @param {import('@playwright/test').Page} page 已完成流程管理员登录的浏览器页面。
   */
  constructor(page) {
    this.page = page
  }

  /**
   * 打开部署管理页并按流程标识执行真实列表筛选。
   * @param {string} processKey 流程定义稳定标识。
   * @returns {Promise<object>} 已核对业务成功且只包含目标流程的列表响应。
   */
  async openAndFilter(processKey) {
    const initialResponsePromise = this.page.waitForResponse(response => (
      matchesEndpoint(response, '/workflow/deploy/list', 'GET')
    ))
    await this.page.goto('/workflow/deploy')
    await expectAjaxSuccess(await initialResponsePromise, '/workflow/deploy/list 初始化查询')
    await expect(this.page.locator('.workflow-page .el-table')).toBeVisible()

    const input = this.page.getByPlaceholder('请输入流程标识')
    await input.fill(processKey)
    const queryForm = input.locator('xpath=ancestor::form[1]')
    const responsePromise = this.page.waitForResponse(response => {
      if (!matchesEndpoint(response, '/workflow/deploy/list', 'GET')) return false
      return new URL(response.url()).searchParams.get('processKey') === processKey
    })
    await queryForm.getByRole('button', { name: '搜索', exact: true }).click()
    const payload = await expectAjaxSuccess(await responsePromise, '/workflow/deploy/list')
    await expect(this.page.locator('.workflow-page .el-loading-mask')).toHaveCount(0)
    await expect(this.row(processKey), `部署列表必须唯一回显流程 ${processKey}`).toHaveCount(1)
    expect(payload.rows, '部署列表接口必须只返回当前筛选流程').toHaveLength(1)
    expect(payload.rows[0]?.processKey).toBe(processKey)
    return payload
  }

  /**
   * 打开指定流程的发布版本弹窗并核对版本、状态及接口结果。
   * @param {string} processKey 流程定义稳定标识。
   * @param {Array<{version:number,status:'已激活'|'已挂起'}>} expectedVersions 按版本倒序排列的预期页面状态。
   * @returns {Promise<object>} 已核对业务成功的发布版本响应。
   */
  async openVersions(processKey, expectedVersions) {
    const responsePromise = this.page.waitForResponse(response => {
      if (!matchesEndpoint(response, '/workflow/deploy/publishList', 'GET')) return false
      return new URL(response.url()).searchParams.get('processKey') === processKey
    })
    await this.row(processKey).locator('button').nth(1).click()
    const payload = await expectAjaxSuccess(await responsePromise, '/workflow/deploy/publishList')
    const dialog = this.page.getByRole('dialog', { name: /发布版本/u })
    await expect(dialog).toBeVisible()
    const rows = dialog.locator('.el-table__body-wrapper tbody tr')
    await expect(rows, '发布版本弹窗必须完整回显全部版本').toHaveCount(expectedVersions.length)
    expect(payload.rows, '发布版本接口数量必须与页面一致').toHaveLength(expectedVersions.length)

    for (let index = 0; index < expectedVersions.length; index += 1) {
      const expectedVersion = expectedVersions[index]
      const row = rows.nth(index)
      // 版本列和状态列使用固定表格结构核对，避免同名流程或时间文本造成误命中。
      await expect(row.locator('td').nth(0)).toHaveText(String(expectedVersion.version))
      await expect(row.locator('td').nth(3)).toContainText(expectedVersion.status)
      expect(Number(payload.rows[index]?.version)).toBe(expectedVersion.version)
      expect(Boolean(payload.rows[index]?.suspended)).toBe(expectedVersion.status === '已挂起')
    }
    return payload
  }

  /**
   * 关闭当前发布版本弹窗。
   * @returns {Promise<void>} 弹窗完全关闭后结束。
   */
  async closeVersions() {
    const dialog = this.page.getByRole('dialog', { name: /发布版本/u })
    await dialog.getByRole('button', { name: '关闭此对话框' }).click()
    await expect(dialog).toBeHidden()
  }

  /**
   * 通过部署列表切换最新定义状态并等待接口和列表回读。
   * @param {string} processKey 流程定义稳定标识。
   * @param {'已激活'|'已挂起'} expectedState 操作完成后的页面状态。
   * @returns {Promise<{change:object,list:object}>} 状态变更与刷新列表的成功响应。
   */
  async toggleLatestState(processKey, expectedState) {
    const targetState = expectedState === '已挂起' ? 'suspended' : 'active'
    const changeResponsePromise = this.page.waitForResponse(response => {
      if (!matchesEndpoint(response, '/workflow/deploy/changeState', 'PUT')) return false
      return new URL(response.url()).searchParams.get('state') === targetState
    })
    const listResponsePromise = this.page.waitForResponse(response => {
      if (!matchesEndpoint(response, '/workflow/deploy/list', 'GET')) return false
      return new URL(response.url()).searchParams.get('processKey') === processKey
    })
    await this.row(processKey).locator('button').nth(2).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    const change = await expectAjaxSuccess(await changeResponsePromise, '/workflow/deploy/changeState')
    const list = await expectAjaxSuccess(await listResponsePromise, '/workflow/deploy/list 状态刷新')
    await expect(this.row(processKey).getByText(expectedState, { exact: true })).toBeVisible()
    return { change, list }
  }

  /**
   * 通过部署页删除入口触发受引用保护，并核对稳定冲突及页面留存。
   * @param {string} processKey 流程定义稳定标识。
   * @param {string} deploymentId 待删除 Flowable 部署主键。
   * @param {string} expectedMessage 后端应返回的稳定冲突消息。
   * @returns {Promise<object>} HTTP 200 承载且业务码为 409 的响应正文。
   */
  async deleteLatestExpectConflict(processKey, deploymentId, expectedMessage) {
    const endpoint = `/workflow/deploy/${deploymentId}`
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, endpoint, 'DELETE'))
    await this.row(processKey).locator('button').nth(3).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    const response = await responsePromise
    expect(response.status(), `${endpoint} HTTP 状态`).toBe(200)
    const payload = await response.json()
    expect(payload?.code, `${endpoint} 业务冲突码`).toBe(409)
    expect(String(payload?.msg || '')).toContain(expectedMessage)
    await expect(this.page.getByText(expectedMessage, { exact: true })).toBeVisible()
    await expect(this.row(processKey), '删除被拒绝后部署行必须继续存在').toHaveCount(1)
    return payload
  }

  /**
   * 通过部署页删除没有业务引用的最新部署，并等待列表回读为空。
   * @param {string} processKey 流程定义稳定标识。
   * @param {string} deploymentId 待删除 Flowable 部署主键。
   * @returns {Promise<{remove:object,list:object}>} 删除与刷新列表的成功响应。
   */
  async deleteLatest(processKey, deploymentId) {
    const endpoint = `/workflow/deploy/${deploymentId}`
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, endpoint, 'DELETE'))
    const listResponsePromise = this.page.waitForResponse(response => {
      if (!matchesEndpoint(response, '/workflow/deploy/list', 'GET')) return false
      return new URL(response.url()).searchParams.get('processKey') === processKey
    })
    await this.row(processKey).locator('button').nth(3).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    const remove = await expectAjaxSuccess(await responsePromise, endpoint)
    await expect(this.page.getByText('流程部署删除成功', { exact: true })).toBeVisible()
    const list = await expectAjaxSuccess(await listResponsePromise, '/workflow/deploy/list 删除刷新')
    await expect(this.row(processKey)).toHaveCount(0)
    expect(list.rows, '删除后筛选列表必须为空').toHaveLength(0)
    return { remove, list }
  }

  /**
   * 返回包含指定流程标识的部署表格行。
   * @param {string} processKey 流程定义稳定标识。
   * @returns {import('@playwright/test').Locator} 当前部署列表行定位器。
   */
  row(processKey) {
    return this.page.locator('.workflow-page .el-table__body-wrapper tbody tr').filter({ hasText: processKey })
  }
}
