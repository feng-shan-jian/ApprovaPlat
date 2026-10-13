import { test, expect, openPage, settleResponse } from './fixtures/page-host.js'

const subjects = [
  { view: 'system/dict/data', route: '/dict/1', initial: '/system/dict/type/1', list: '/system/dict/data/list',
    defaults: { dictId: 1, dictType: 'dict_A' }, row: { dictCode: 1, dictLabel: '当前结果', dictValue: '1', dictSort: 1, status: '0' } },
  { view: 'monitor/job/log', route: '/job/1', initial: '/monitor/job/1', list: '/monitor/jobLog/list',
    defaults: { jobName: '初始任务A', jobGroup: 'DEFAULT' }, row: { jobLogId: 1, jobName: '当前结果', jobGroup: 'DEFAULT', status: '0' } }
]

async function query(page, subject) {
  if (subject.view.includes('dict')) {
    await page.locator('.el-form-item').filter({ hasText: '字典名称' }).locator('.el-select').click()
    await page.getByRole('option', { name: '字典B', exact: true }).click()
  } else await page.getByPlaceholder('请输入任务名称').fill('当前任务B')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
}

for (const subject of subjects) {
  for (const outcome of ['success', 'failure']) {
    test(`R5 ${subject.view}: late initialization ${outcome} cannot settle the user's pending list`, async ({ page, componentHost }) => {
      let releaseInitial, releaseList
      const calls = await openPage(page, componentHost, subject.view, async ({ path, reply }) => {
        if (path === subject.initial) {
          await new Promise(resolve => { releaseInitial = resolve })
          await reply(outcome === 'success' ? { data: subject.defaults } : { code: 500, msg: '过期初始化失败' })
        } else if (path === subject.list) {
          await new Promise(resolve => { releaseList = resolve })
          await reply({ rows: [subject.row], total: 1 })
        } else if (path === '/system/dict/type/optionselect') {
          await reply({ data: ['A', 'B'].map((id, index) => ({ dictId: index + 1, dictType: `dict_${id}`, dictName: `字典${id}` })) })
        } else return false
        return true
      }, { route: subject.route })
      await expect.poll(() => Boolean(releaseInitial)).toBe(true)
      await query(page, subject)
      await expect.poll(() => Boolean(releaseList)).toBe(true)
      const response = page.waitForResponse(response => response.url().includes(subject.initial))
      releaseInitial()
      await settleResponse(page, response)
      await expect(page.locator('.el-table .el-loading-mask')).toBeVisible()
      await expect(page.getByText('过期初始化失败', { exact: true })).toHaveCount(0)
      expect(calls.filter(call => call.path === subject.list)).toHaveLength(1)
      releaseList()
      await expect(page.getByRole('cell', { name: '当前结果', exact: true })).toBeVisible()
      await expect(page.locator('.el-table .el-loading-mask')).toBeHidden()
    })
  }

  for (const failure of ['initialization', 'list']) {
    test(`R5 ${subject.view}: current ${failure} failure releases loading`, async ({ page, componentHost }) => {
      let releaseList
      const calls = await openPage(page, componentHost, subject.view, async ({ path, reply }) => {
        if (path === subject.initial) await reply(failure === 'initialization' ? { code: 500, msg: '当前初始化失败' } : { data: subject.defaults })
        else if (path === subject.list) {
          await new Promise(resolve => { releaseList = resolve })
          await reply({ code: 500, msg: '当前列表失败' })
        } else return false
        return true
      }, { route: subject.route })
      if (failure === 'list') {
        await expect.poll(() => Boolean(releaseList)).toBe(true)
        await expect(page.locator('.el-table .el-loading-mask')).toBeVisible()
        releaseList()
      }
      await expect(page.getByText(failure === 'initialization' ? '当前初始化失败' : '当前列表失败', { exact: true })).toBeVisible()
      await expect(page.locator('.el-table .el-loading-mask')).toBeHidden()
      expect(calls.filter(call => call.path === subject.list)).toHaveLength(failure === 'initialization' ? 0 : 1)
    })
  }

  test(`R5 ${subject.view}: unmount revokes initialization without a list request`, async ({ page, componentHost }) => {
    let releaseInitial
    const calls = await openPage(page, componentHost, subject.view, async ({ path, reply }) => {
      if (path !== subject.initial) return false
      await new Promise(resolve => { releaseInitial = resolve })
      await reply({ data: subject.defaults })
      return true
    }, { route: subject.route })
    await expect.poll(() => Boolean(releaseInitial)).toBe(true)
    await page.getByRole('button', { name: '卸载被测页面', exact: true }).click()
    const response = page.waitForResponse(response => response.url().includes(subject.initial))
    releaseInitial()
    await settleResponse(page, response)
    expect(calls.filter(call => call.path === subject.list)).toHaveLength(0)
  })
}

test('R5 dictionary reset retains the route default after a user query supersedes initialization', async ({ page, componentHost }) => {
  const subject = subjects[0]
  let releaseInitial
  const calls = await openPage(page, componentHost, subject.view, async ({ path, url, reply }) => {
    if (path === subject.initial) {
      await new Promise(resolve => { releaseInitial = resolve })
      await reply({ data: subject.defaults })
    } else if (path === subject.list) {
      await reply({ rows: [{ ...subject.row, dictLabel: url.searchParams.get('dictType') === 'dict_A' ? '默认字典结果' : '用户查询结果' }], total: 1 })
    } else if (path === '/system/dict/type/optionselect') {
      await reply({ data: ['A', 'B'].map((id, index) => ({ dictId: index + 1, dictType: `dict_${id}`, dictName: `字典${id}` })) })
    } else return false
    return true
  }, { route: subject.route })
  await expect.poll(() => Boolean(releaseInitial)).toBe(true)
  await query(page, subject)
  await expect(page.getByRole('cell', { name: '用户查询结果', exact: true })).toBeVisible()
  const response = page.waitForResponse(response => response.url().includes(subject.initial))
  releaseInitial()
  await settleResponse(page, response)
  expect(calls.filter(call => call.path === subject.list).map(call => call.query.dictType)).toEqual(['dict_B'])
  await expect(page.getByRole('cell', { name: '用户查询结果', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await expect(page.getByRole('cell', { name: '默认字典结果', exact: true })).toBeVisible()
  expect(calls.filter(call => call.path === subject.list).map(call => call.query.dictType)).toEqual(['dict_B', 'dict_A'])
})
