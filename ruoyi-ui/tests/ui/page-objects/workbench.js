import { expect } from '@playwright/test'
import { expectAjaxSuccess, matchesEndpoint } from '../../e2e/support/http.js'

export class WorkflowWorkbenchPage {
  /**
   * 创建审批工作台页面对象。
   * @param {import('@playwright/test').Page} page 已完成当前角色登录的浏览器页面。
   */
  constructor(page) {
    this.page = page
  }

  /**
   * 从新建流程列表进入指定部署并正式提交表单。
   * @param {string} processName 流程名称。
   * @param {string} value 必填文本字段值。
   * @returns {Promise<string>} 提交后真实流程实例主键。
   */
  async startProcess(processName, value) {
    await this.page.goto('/office/create')
    const row = await this.filterRow('/office/create', '请输入流程名称', processName)
    await row.locator('button').first().click()
    await expect(this.page).toHaveURL(/\/workflow\/process-start\//u)
    const formInput = this.page.locator('.workflow-form-renderer input:not([type="file"])').first()
    await expect(formInput).toBeVisible()
    await formInput.fill(value)
    await this.page.getByRole('button', { name: '正式提交', exact: true }).click()
    await expect(this.page).toHaveURL(/\/workflow\/process-detail\//u)
    const processInstanceId = new URL(this.page.url()).pathname.split('/').filter(Boolean).at(-1)
    if (!processInstanceId) throw new Error('正式提交后 URL 缺少流程实例主键')
    return processInstanceId
  }

  /**
   * 从待签列表认领指定流程的唯一任务。
   * @param {string} processName 流程名称。
   * @returns {Promise<void>} 认领成功且待签列表刷新后结束。
   */
  async claimProcess(processName) {
    const row = await this.filterRow('/office/claim', '请输入流程名称', processName)
    await row.locator('button').nth(1).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expect(this.page.getByText('任务认领成功', { exact: true })).toBeVisible()
  }

  /**
   * 从待签列表按流程名称和任务名称认领唯一分支任务。
   * @param {string} processName 流程名称。
   * @param {string} taskName 任务节点显示名称。
   * @returns {Promise<void>} 指定候选任务认领成功且列表刷新后结束。
   */
  async claimTask(processName, taskName) {
    const row = await this.filterTaskRow('/office/claim', processName, taskName)
    await row.locator('button').nth(1).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expect(this.page.getByText('任务认领成功', { exact: true })).toBeVisible()
  }

  /**
   * 打开待办详情并通过当前审批任务。
   * @param {string} processName 流程名称。
   * @param {string} comment 办理意见。
   * @param {boolean} completed 当前任务是否应结束整个流程实例。
   * @returns {Promise<void>} 后端完成任务；最终任务还需由详情页回显流程完成。
   */
  async approveProcess(processName, comment, completed = true) {
    const row = await this.filterRow('/office/todo', '请输入流程名称', processName)
    await row.locator('button').first().click()
    await expect(this.page).toHaveURL(/\/workflow\/process-detail\//u)
    await this.page.getByRole('button', { name: '通过', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '通过任务' })
    await dialog.getByLabel('办理意见').fill(comment)
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expect(this.page.getByText('通过任务成功', { exact: true })).toBeVisible()
    if (completed) {
      await expect(this.page.getByText('已完成', { exact: true }).first()).toBeVisible()
    }
  }

  /**
   * 从待办列表按流程名称和任务名称完成唯一分支任务。
   * @param {string} processName 流程名称。
   * @param {string} taskName 任务节点显示名称。
   * @param {string} comment 办理意见。
   * @param {boolean} completed 当前任务是否应结束整个流程实例。
   * @returns {Promise<void>} 指定任务完成且页面刷新到预期状态后结束。
   */
  async approveTask(processName, taskName, comment, completed = true) {
    const row = await this.filterTaskRow('/office/todo', processName, taskName)
    await row.locator('button').first().click()
    await expect(this.page).toHaveURL(/\/workflow\/process-detail\//u)
    await this.page.getByRole('button', { name: '通过', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '通过任务' })
    await dialog.getByLabel('办理意见').fill(comment)
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expect(this.page.getByText('通过任务成功', { exact: true })).toBeVisible()
    if (completed) await expect(this.page.getByText('已完成', { exact: true }).first()).toBeVisible()
  }

  /**
   * 从待办列表取消本人对候选任务的真实认领。
   * @param {string} processName 流程名称。
   * @returns {Promise<void>} 取消认领接口成功且待办列表刷新后结束。
   */
  async unclaimProcess(processName) {
    const row = await this.filterRow('/office/todo', '请输入流程名称', processName)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/unClaim', 'POST'))
    await row.locator('button').nth(1).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/task/unClaim')
    await expect(this.page.getByText('已取消任务认领', { exact: true })).toBeVisible()
  }

  /**
   * 从待办详情整实例驳回当前任务。
   * @param {string} processName 流程名称。
   * @param {string} comment 驳回原因。
   * @returns {Promise<void>} 实例进入已驳回终态并在详情页回显后结束。
   */
  async rejectProcess(processName, comment) {
    const row = await this.filterRow('/office/todo', '请输入流程名称', processName)
    await row.locator('button').first().click()
    const dialogPromise = this.page.getByRole('dialog', { name: '驳回任务' })
    await this.page.getByRole('button', { name: '驳回', exact: true }).click()
    await dialogPromise.getByPlaceholder('请输入驳回原因').fill(comment)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/reject', 'POST'))
    await dialogPromise.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/task/reject')
    await expect(this.page.getByText('已驳回', { exact: true }).first()).toBeVisible()
  }

  /**
   * 由流程发起人在“我的流程”列表取消仍在运行的实例。
   * @param {string} processName 流程名称。
   * @param {string} comment 取消原因。
   * @returns {Promise<void>} 取消写入成功且列表回显已取消后结束。
   */
  async cancelOwnedProcess(processName, comment) {
    let row = await this.filterRow('/office/own', '请输入流程名称', processName)
    await row.locator('button').nth(1).click()
    const dialog = this.page.getByRole('dialog', { name: '取消流程' })
    await dialog.locator('textarea').fill(comment)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/stopProcess', 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/task/stopProcess')
    await expect(this.page.getByText('流程操作成功', { exact: true })).toBeVisible()
    row = await this.filterRow('/office/own', '请输入流程名称', processName)
    await expect(row.getByText('已取消', { exact: true })).toBeVisible()
  }

  /**
   * 由流程管理员切换运行实例的挂起或激活状态。
   * @param {string} processName 流程名称。
   * @param {'已挂起'|'运行中'} expectedStatus 切换后的列表状态。
   * @returns {Promise<void>} 后端状态变更成功且管理列表刷新后结束。
   */
  async toggleManagedProcessState(processName, expectedStatus) {
    let row = await this.filterRow('/workflow/extensions/instance', '请输入流程名称', processName)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/instance/updateState', 'POST'))
    await row.locator('button').nth(1).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/instance/updateState')
    row = await this.filterRow('/workflow/extensions/instance', '请输入流程名称', processName)
    await expect(row.getByText(expectedStatus, { exact: true })).toBeVisible()
  }

  /**
   * 由流程管理员终止仍有活动执行树的实例。
   * @param {string} processName 流程名称。
   * @param {string} reason 终止原因。
   * @returns {Promise<void>} 终止接口成功且管理列表回显已终止后结束。
   */
  async terminateManagedProcess(processName, reason) {
    let row = await this.filterRow('/workflow/extensions/instance', '请输入流程名称', processName)
    await row.locator('button').nth(2).click()
    const dialog = this.page.getByRole('dialog', { name: '终止流程实例' })
    await dialog.locator('textarea').fill(reason)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/instance/terminate', 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/instance/terminate')
    row = await this.filterRow('/workflow/extensions/instance', '请输入流程名称', processName)
    await expect(row.getByText('已终止', { exact: true })).toBeVisible()
  }

  /**
   * 从管理员实例列表删除已经结束且无正式引用的历史实例。
   * @param {string} processName 流程名称。
   * @returns {Promise<void>} 删除接口成功且目标实例从管理列表消失后结束。
   */
  async deleteManagedHistory(processName) {
    const row = await this.filterRow('/workflow/extensions/instance', '请输入流程名称', processName)
    const responsePromise = this.page.waitForResponse(response => (
      response.request().method() === 'DELETE'
      && new URL(response.url()).pathname.includes('/workflow/process/instance/')
    ))
    await row.locator('button').nth(1).click()
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/process/instance/')
    await expect(this.page.getByText('流程历史删除成功', { exact: true })).toBeVisible()
    await expect(this.page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: processName })).toHaveCount(0)
  }

  /**
   * 从待办详情把当前任务委派或转办给正式审批资格用户。
   * @param {string} processName 流程名称。
   * @param {'delegate'|'transfer'} action 委派或转办动作。
   * @param {string} targetUsername 目标用户登录名，用于远程目录检索。
   * @param {string} targetDisplayName 目标用户页面显示名称。
   * @param {string} comment 动作意见。
   * @returns {Promise<void>} 后端状态迁移成功且动作弹窗关闭后结束。
   */
  async assignCurrentTaskToUser(processName, action, targetUsername, targetDisplayName, comment) {
    const contract = action === 'delegate'
      ? { button: '委派', dialog: '委派任务', placeholder: '请输入委派意见', endpoint: '/workflow/task/delegate' }
      : { button: '转办', dialog: '转办任务', placeholder: '请输入转办意见', endpoint: '/workflow/task/transfer' }
    const row = await this.filterRow('/office/todo', '请输入流程名称', processName)
    await row.locator('button').first().click()
    await this.page.getByRole('button', { name: contract.button, exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: contract.dialog })
    const targetField = dialog.locator('.el-form-item').filter({ hasText: '目标用户' })
    await targetField.locator('.el-select__wrapper').click()
    const input = targetField.getByRole('combobox')
    await input.fill(targetUsername)
    const option = this.page.getByRole('option').filter({ hasText: targetDisplayName }).first()
    await expect(option, `审批资格目录必须返回 ${targetDisplayName}`).toBeVisible()
    await option.click()
    await dialog.getByPlaceholder(contract.placeholder).fill(comment)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, contract.endpoint, 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, contract.endpoint)
    await expect(dialog).toBeHidden()
  }

  /**
   * 由当前受托人填写意见并完成 PENDING 委派。
   * @param {string} processName 流程名称。
   * @param {string} comment 委派事项办理意见。
   * @returns {Promise<void>} 委派办结成功且任务返回原 owner 后结束。
   */
  async resolveDelegatedProcess(processName, comment) {
    const row = await this.filterRow('/office/todo', '请输入流程名称', processName)
    await row.locator('button').first().click()
    await this.page.getByRole('button', { name: '完成委派', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '完成委派' })
    await dialog.getByPlaceholder('请输入委派事项的真实办理意见').fill(comment)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/resolve', 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/task/resolve')
    await expect(dialog).toBeHidden()
  }

  /**
   * 由当前办理人把整条申请退回发起人修改。
   * @param {string} processName 流程名称。
   * @param {string} comment 退回原因。
   * @returns {Promise<void>} 退回接口成功且当前办理人离开详情后结束。
   */
  async returnProcess(processName, comment) {
    const row = await this.filterRow('/office/todo', '请输入流程名称', processName)
    await row.locator('button').first().click()
    await this.page.getByRole('button', { name: '退回', exact: true }).click()
    const dialog = this.page.getByRole('dialog', { name: '退回任务' })
    await dialog.getByPlaceholder('请输入退回原因').fill(comment)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/return', 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/task/return')
    await expect(dialog).toBeHidden()
  }

  /**
   * 由发起人在待修改详情编辑原申请表单并重新提交。
   * @param {string} processName 流程名称。
   * @param {string} value 修改后的首个文本字段值。
   * @returns {Promise<void>} 表单覆盖和首审任务恢复成功后结束。
   */
  async resubmitOwnedProcess(processName, value) {
    const row = await this.filterRow('/office/own', '请输入流程名称', processName)
    await expect(row.getByText('待修改', { exact: true })).toBeVisible()
    await row.locator('button').first().click()
    const taskForm = this.page.getByRole('tabpanel', { name: '办理表单' })
    await taskForm.locator('input:not([type="file"])').first().fill(value)
    await this.page.getByRole('button', { name: '重新提交', exact: true }).click()
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/resubmit', 'POST'))
    await this.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/task/resubmit')
    await expect(this.page.getByText('重新提交成功', { exact: true })).toBeVisible()
  }

  /**
   * 从已办列表撤回本人完成且后继尚未处理的任务。
   * @param {string} processName 流程名称。
   * @param {string} comment 撤回原因。
   * @returns {Promise<void>} 撤回成功并重新创建原活动节点后结束。
   */
  async revokeFinishedProcess(processName, comment) {
    const row = await this.filterRow('/office/finished', '请输入流程名称', processName)
    await row.locator('button').nth(1).click()
    const dialog = this.page.getByRole('dialog', { name: '撤回已办任务' })
    await dialog.locator('textarea').fill(comment)
    const responsePromise = this.page.waitForResponse(response => matchesEndpoint(response, '/workflow/task/revokeProcess', 'POST'))
    await dialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, '/workflow/task/revokeProcess')
    await expect(this.page.getByText('流程操作成功', { exact: true })).toBeVisible()
  }

  /**
   * 按流程名称过滤工作台并返回唯一数据行。
   * @param {string} pagePath 工作台路由。
   * @param {string} placeholder 搜索框占位文本。
   * @param {string} value 流程名称。
   * @returns {Promise<import('@playwright/test').Locator>} 唯一匹配表格行。
   */
  async filterRow(pagePath, placeholder, value) {
    // 先等待页面自身初始化查询完成，再执行用户筛选；初始化与立即搜索的竞态另由 DEF-UI-005 专项证据覆盖。
    const endpoint = {
      '/office/create': '/workflow/process/list',
      '/office/own': '/workflow/process/ownList',
      '/office/todo': '/workflow/process/todoList',
      '/office/claim': '/workflow/process/claimList',
      '/office/finished': '/workflow/process/finishedList',
      '/office/copy': '/workflow/process/copyList',
      '/workflow/extensions/instance': '/workflow/process/manageList'
    }[pagePath]
    if (!endpoint) throw new Error(`未定义工作台列表入口：${pagePath}`)
    const initialResponsePromise = this.page.waitForResponse(response => matchesEndpoint(response, endpoint, 'GET'))
    await this.page.goto(pagePath)
    await expectAjaxSuccess(await initialResponsePromise, `${endpoint} 初始化查询`)
    await expect(this.page.locator('.workflow-process-list .el-loading-mask')).toHaveCount(0)
    const input = this.page.getByPlaceholder(placeholder)
    await input.fill(value)
    const queryForm = input.locator('xpath=ancestor::form[1]')
    // 页面查询方法是异步 fire-and-forget；必须先监听真实列表响应，避免搜索点击后读取旧表格快照。
    const responsePromise = this.page.waitForResponse(response => {
      if (!matchesEndpoint(response, endpoint, 'GET')) return false
      // 页面进入时也会并行发起同一路径的初始化查询；只接受携带本次筛选值的请求。
      return new URL(response.url()).searchParams.get('processName') === value
    })
    await queryForm.getByRole('button', { name: '搜索', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, endpoint)
    await expect(this.page.locator('.workflow-process-list .el-loading-mask')).toHaveCount(0)
    const row = this.page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: value })
    await expect(row).toHaveCount(1)
    return row
  }

  /**
   * 按流程名称和任务名称过滤待签或待办列表并返回唯一分支行。
   * @param {'/office/claim'|'/office/todo'} pagePath 任务列表路由。
   * @param {string} processName 流程名称。
   * @param {string} taskName 任务名称。
   * @returns {Promise<import('@playwright/test').Locator>} 唯一匹配的任务行。
   */
  async filterTaskRow(pagePath, processName, taskName) {
    const endpoint = pagePath === '/office/todo'
      ? '/workflow/process/todoList'
      : pagePath === '/office/claim' ? '/workflow/process/claimList' : ''
    if (!endpoint) throw new Error(`未定义任务列表入口：${pagePath}`)
    const initialResponsePromise = this.page.waitForResponse(response => matchesEndpoint(response, endpoint, 'GET'))
    await this.page.goto(pagePath)
    await expectAjaxSuccess(await initialResponsePromise, `${endpoint} 初始化查询`)
    await expect(this.page.locator('.workflow-process-list .el-loading-mask')).toHaveCount(0)
    await this.page.getByPlaceholder('请输入流程名称').fill(processName)
    await this.page.getByPlaceholder('请输入任务名称').fill(taskName)
    const queryForm = this.page.getByPlaceholder('请输入流程名称').locator('xpath=ancestor::form[1]')
    // 同时等待业务码和加载遮罩，确保后续唯一行断言对应本次筛选请求。
    const responsePromise = this.page.waitForResponse(response => {
      if (!matchesEndpoint(response, endpoint, 'GET')) return false
      const query = new URL(response.url()).searchParams
      return query.get('processName') === processName && query.get('taskName') === taskName
    })
    await queryForm.getByRole('button', { name: '搜索', exact: true }).click()
    await expectAjaxSuccess(await responsePromise, endpoint)
    await expect(this.page.locator('.workflow-process-list .el-loading-mask')).toHaveCount(0)
    const row = this.page.locator('.el-table__body-wrapper tbody tr')
      .filter({ hasText: processName }).filter({ hasText: taskName })
    await expect(row, `${processName} 的任务 ${taskName} 必须唯一`).toHaveCount(1)
    return row
  }
}
