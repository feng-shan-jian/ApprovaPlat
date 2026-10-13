import { test, expect, openPage, settleResponse } from './fixtures/page-host.js'

test('extension registry independently settles handlers and ignores the prior pair of failures', async ({ page, componentHost }) => {
  const gates = []
  await openPage(page, componentHost, 'workflow/extension/index', async ({ path, reply }) => {
    if (!['/workflow/extension/list', '/workflow/extension/installed-handlers/java'].includes(path)) return false
    const index = gates.length
    await new Promise(resolve => gates.push(resolve))
    await reply(index < 2 || path.includes('/installed-handlers/') ? { code: 500, msg: index < 2 ? '过期目录失败' : '当前处理器失败' }
      : { data: [{ extensionId: 1, extensionName: '当前目录', extensionKey: 'current', extensionType: 'JAVA', status: 'ENABLED' }] })
    return true
  })
  await expect.poll(() => gates.length).toBe(2)
  await page.locator('.top-right-btn button').last().click()
  await expect.poll(() => gates.length).toBe(4)
  gates[0](); gates[1]()
  await expect(page.locator('.el-table .el-loading-mask')).toBeVisible()
  await expect(page.getByText('过期目录失败', { exact: true })).toHaveCount(0)
  gates[2](); gates[3]()
  await expect(page.getByText('当前目录', { exact: true })).toBeVisible()
  await expect(page.getByText(/已安装处理器加载失败/)).toBeVisible()
  await expect(page.locator('.el-table .el-loading-mask')).toBeHidden()
})

for (const action of ['direction', 'unmount', 'current']) {
  test(`collaboration confirmation ${action} preserves its frozen message and direction`, async ({ page, componentHost }) => {
    const calls = await openPage(page, componentHost, 'workflow/collaboration/index', async ({ path, reply }) => {
      if (!['/workflow/collaboration/outbox', '/workflow/collaboration/inbound'].includes(path)) return false
      await reply({ rows: [{ messageId: 'A', messageName: '受控消息', status: 'RETRYING', retryAllowed: true }], total: 1 })
      return true
    })
    await page.getByRole('button', { name: '重新投递', exact: true }).click()
    const dialog = page.getByRole('dialog')
    if (action === 'direction') await page.getByRole('tab', { name: '入站消息', exact: true }).dispatchEvent('click')
    if (action === 'unmount') await page.getByRole('button', { name: '卸载被测页面', exact: true }).dispatchEvent('click')
    await dialog.getByRole('button', { name: '确定', exact: true }).click()
    await expect(dialog).toBeHidden()
    const writes = calls.filter(call => call.method === 'POST')
    if (action === 'current') {
      await expect.poll(() => calls.filter(call => call.method === 'POST').length).toBe(1)
      expect(calls.find(call => call.method === 'POST').path).toContain('/outbox/A/retry')
    } else expect(writes).toHaveLength(0)
  })
}

test('cache selection invalidates a pending value before the next keys finish', async ({ page, componentHost }) => {
  let releaseOld, releaseKeys
  await openPage(page, componentHost, 'monitor/cache/list', async ({ path, reply }) => {
    if (path === '/monitor/cache/getNames') await reply({ data: [{ cacheName: 'A', remark: '目录A' }, { cacheName: 'B', remark: '目录B' }] })
    else if (path === '/monitor/cache/getKeys/A') await reply({ data: ['old-key'] })
    else if (path === '/monitor/cache/getKeys/B') {
      await new Promise(resolve => { releaseKeys = resolve })
      await reply({ data: ['current-key'] })
    } else if (path === '/monitor/cache/getValue/A/old-key') {
      await new Promise(resolve => { releaseOld = resolve })
      await reply({ data: { cacheName: 'A', cacheKey: 'old-key', cacheValue: '过期缓存内容' } })
    } else return false
    return true
  })
  await page.getByRole('cell', { name: '目录A', exact: true }).click()
  await page.getByRole('cell', { name: 'old-key', exact: true }).click()
  await expect.poll(() => Boolean(releaseOld)).toBe(true)
  await page.getByRole('cell', { name: '目录B', exact: true }).click()
  await expect.poll(() => Boolean(releaseKeys)).toBe(true)
  const response = page.waitForResponse(response => response.url().endsWith('/getValue/A/old-key'))
  releaseOld()
  await settleResponse(page, response)
  await expect(page.getByRole('textbox', { name: '缓存内容:' })).toHaveValue('')
  releaseKeys()
  await expect(page.getByRole('cell', { name: 'current-key', exact: true })).toBeVisible()
})

for (const allowed of [false, true]) {
  test(`notification outbox requires its actual audit permission (${allowed})`, async ({ page, componentHost }) => {
    const calls = await openPage(page, componentHost, 'workflow/notification/index', async () => false,
      { permissions: JSON.stringify(allowed ? ['workflow:notification:audit'] : []) })
    await expect(page.getByRole('heading', { name: '审批通知', exact: true })).toBeVisible()
    if (allowed) {
      await expect.poll(() => calls.filter(call => call.path.includes('/notification/outbox')).length).toBe(1)
    } else {
      await expect(page.getByRole('tab', { name: '投递记录', exact: true })).toHaveCount(0)
      expect(calls.filter(call => call.path.includes('/notification/outbox'))).toHaveLength(0)
    }
  })
}

for (const failRefresh of [false, true]) {
  test(`process list mutation refresh cannot reacquire an unmounted owner (${failRefresh ? 'failure' : 'success'})`, async ({ page, componentHost }) => {
    let releaseWrite, releaseRefresh, listReads = 0
    await openPage(page, componentHost, 'workflow/work/claim', async ({ path, request, reply }) => {
      if (path === '/workflow/process/claimList') {
        listReads++
        if (listReads > 1) await new Promise(resolve => { releaseRefresh = resolve })
        await reply(listReads > 1 && failRefresh ? { code: 500, msg: '卸载后刷新失败' }
          : { rows: [{ taskId: 'task-A', processInstanceId: 'instance-A', processName: '待签流程', taskName: '待签任务' }], total: 1 })
      } else if (path === '/workflow/task/claim' && request.method() === 'POST') {
        await new Promise(resolve => { releaseWrite = resolve })
        await reply({ data: {} })
      } else return false
      return true
    })
    const row = page.getByRole('row').filter({ hasText: '待签任务' })
    await row.locator('button').last().click()
    await page.getByRole('button', { name: '确定', exact: true }).click()
    await expect.poll(() => Boolean(releaseWrite)).toBe(true)
    await page.getByRole('button', { name: '卸载被测页面', exact: true }).click()
    releaseWrite()
    await expect.poll(() => Boolean(releaseRefresh)).toBe(true)
    const response = page.waitForResponse(response => response.url().includes('/workflow/process/claimList'))
    releaseRefresh()
    await settleResponse(page, response)
    await expect(page.getByText('卸载后刷新失败', { exact: true })).toHaveCount(0)
    await expect(page.locator('.workflow-process-list')).toHaveCount(0)
  })
}

for (const kind of ['type', 'data']) {
  test(`dictionary ${kind} write invalidates captured types despite a reopened form`, async ({ page, componentHost }) => {
    let releaseWrite
    const view = kind === 'type' ? 'system/dict/index' : 'system/dict/data'
    const calls = await openPage(page, componentHost, view, async ({ path, request, reply }) => {
      if (path === `/system/dict/${kind}/list`) await reply({ rows: [1, 2].map(id => ({ dictId: id, dictCode: id,
        dictName: `字典${id}`, dictLabel: `条目${id}`, dictValue: String(id), dictSort: id, dictType: id === 1 ? 'old_type' : 'later_edit', status: '0' })), total: 2 })
      else if (path === `/system/dict/${kind}/1` || path === `/system/dict/${kind}/2`) {
        const id = Number(path.at(-1))
        await reply({ data: { dictId: id, dictCode: id, dictName: `字典${id}`, dictLabel: `条目${id}`, dictValue: String(id), dictSort: id,
          dictType: id === 1 ? 'old_type' : 'later_edit', status: '0' } })
      } else if (path === `/system/dict/${kind}` && request.method() === 'PUT') {
        await new Promise(resolve => { releaseWrite = resolve })
        await reply({})
      } else return false
      return true
    }, { route: kind === 'type' ? '/subject/1' : '/dict/1', cacheProof: 'true' })
    const first = page.getByRole('row').filter({ hasText: kind === 'type' ? '字典1' : '条目1' })
    await first.getByRole('button', { name: '修改', exact: true }).click()
    let dialog = page.getByRole('dialog')
    if (kind === 'type') await dialog.getByPlaceholder('请输入字典类型').fill('new_type')
    await dialog.getByRole('button', { name: '确 定', exact: true }).click()
    await expect.poll(() => Boolean(releaseWrite)).toBe(true)
    await dialog.getByRole('button', { name: '取 消', exact: true }).click()
    await expect(dialog).toBeHidden()
    const second = page.getByRole('row').filter({ hasText: kind === 'type' ? '字典2' : '条目2' })
    await second.getByRole('button', { name: '修改', exact: true }).click()
    dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    const response = page.waitForResponse(response => response.request().method() === 'PUT')
    releaseWrite()
    await settleResponse(page, response)
    expect(calls.find(call => call.method === 'PUT').body.dictType).toBe(kind === 'type' ? 'new_type' : 'old_type')
    await expect(page.getByTestId('dictionary-cache')).toHaveText(`old_type:missing,new_type:${kind === 'type' ? 'missing' : 'cached'},later_edit:cached`)
  })
}
