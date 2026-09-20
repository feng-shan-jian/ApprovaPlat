import { expect, test } from '@playwright/test'

// 前端调用方验收：真实 Vue、Element Plus、BPMN 画布及 HTTP 客户端，受控响应不证明后端权限或持久化。
const xml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" targetNamespace="urn:reuse:test">
<process id="reuse" name="正式模型" isExecutable="true"><startEvent id="start" name="开始"/></process>
<bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="reuse"><bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="100" y="100" width="36" height="36"/></bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>`
const user = (value, label) => ({ type: 'user', value: String(value), label })
const template = JSON.stringify({ fields: [] })

async function application(page, handler) {
  await page.context().addCookies([{ name: 'Admin-Token', value: 'synthetic-frontend-session', url: test.info().project.use.baseURL }])
  const calls = []
  await page.route('**/dev-api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/dev-api', '')
    calls.push({ path, query: Object.fromEntries(url.searchParams), method: request.method(), body: request.postDataJSON() })
    const reply = data => route.fulfill({ json: { code: 200, ...data } })
    if (path === '/getInfo') return reply({ user: { userId: 1, userName: 'frontend-review' }, roles: ['admin'], permissions: ['*:*:*'] })
    if (path === '/getRouters') return reply({ data: [{ path: '/workflow', component: 'Layout', children: [{ path: 'model', component: 'workflow/model/index', name: 'WorkflowModel', meta: { title: '流程模型' } }] }] })
    if (await handler?.({ path, request, url, reply, route })) return
    if (path === '/workflow/category/listAll') return reply({ data: [{ code: 'test', categoryName: '验收分类' }] })
    if (path.startsWith('/workflow/process/bpmnXml/')) return reply({ data: xml })
    return reply({ data: [], rows: [], total: 0 })
  })
  return calls
}

test('设计页与草稿比较使用同次模型的 XML 和 revision，冲突保留画布', async ({ page }) => {
  let revision = 7
  const calls = await application(page, async ({ path, request, reply }) => {
    if (path === '/workflow/model/model-a') {
      await reply({ data: { modelId: 'model-a', modelKey: 'reuse', modelName: '正式模型', version: 1, revision, bpmnXml: xml.replace('正式模型', `正式模型${revision}`) } })
    } else if (path === '/workflow/model/bpmnXml/model-a') {
      await reply({ data: xml.replace('正式模型', `正式模型${revision}`) })
    } else if (path.endsWith('/design-draft')) {
      await reply(request.method() === 'GET'
        ? { data: { revision: 2, baseModelRevision: 7, bpmnXml: xml.replace('正式模型', '本人草稿') } }
        : { code: 409, msg: '草稿版本冲突' })
    } else if (path === '/workflow/model/save') {
      await reply({ code: 409, msg: '模型修订冲突' })
    } else return false
    return true
  })
  await page.goto('/workflow/model-design/model-a')
  const comparison = page.getByRole('dialog', { name: '设计草稿恢复与冲突处理' })
  await expect(comparison).toBeVisible()
  await expect(comparison.getByRole('textbox').first()).toHaveValue(/正式模型7/)
  expect(calls.filter(call => call.path === '/workflow/model/model-a')).toHaveLength(1)
  expect(calls.filter(call => call.path.includes('/bpmnXml/'))).toHaveLength(0)
  await comparison.getByRole('button', { name: '恢复服务端草稿' }).click()
  await expect(page.getByRole('button', { name: '保存', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect(page.getByText('模型修订冲突', { exact: true })).toBeVisible()
  expect(calls.find(call => call.path === '/workflow/model/save').body.expectedRevision).toBe(7)
  await expect(page.getByText('有未正式保存的修改')).toBeVisible()
  await expect(page.getByText('草稿版本冲突', { exact: true }).first()).toBeVisible()
  revision = 8
  await page.getByRole('button', { name: '比较远端草稿' }).click()
  await expect(comparison.getByRole('textbox').nth(0)).toHaveValue(/本人草稿/)
  await expect(comparison.getByRole('textbox').nth(2)).toHaveValue(/正式模型8/)
  expect(calls.filter(call => call.path === '/workflow/model/model-a')).toHaveLength(2)
  expect(calls.filter(call => call.path.includes('/bpmnXml/'))).toHaveLength(0)
})

test('模型弹窗完整分页并在第二页失败后阻止打开，重试恢复', async ({ page }) => {
  let total = 0
  let failPage = 0
  const calls = await application(page, async ({ path, url, reply }) => {
    if (path !== '/workflow/form/list') return false
    const pageNum = Number(url.searchParams.get('pageNum'))
    if (failPage === pageNum) await reply({ code: 500, msg: '表单第二页读取失败' })
    else await reply({ total, rows: Array.from({ length: Math.max(0, Math.min(50, total - (pageNum - 1) * 50)) }, (_, i) => {
      const formId = (pageNum - 1) * 50 + i + 1
      return { formId, formName: `模板${formId}`, content: template }
    }) })
    return true
  })
  await page.goto('/workflow/model')
  await expect(page.getByRole('button', { name: '新增', exact: true })).toBeVisible()
  for (const count of [0, 1, 50, 51, 121]) {
    total = count
    const from = calls.length
    await page.getByRole('button', { name: '新增', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新增流程模型' })
    await expect(dialog).toBeVisible()
    const formSelect = dialog.locator('.el-form-item').filter({ has: page.getByText('流程表单', { exact: true }) }).locator('.el-select')
    await formSelect.click()
    await expect(page.locator('.el-select-dropdown:visible .el-select-dropdown__item')).toHaveCount(count)
    const requests = calls.slice(from).filter(call => call.path === '/workflow/form/list')
    expect(requests.map(call => [call.query.pageNum, call.query.pageSize])).toEqual(Array.from({ length: Math.max(1, Math.ceil(count / 50)) }, (_, i) => [String(i + 1), '50']))
    if (count) await expect(page.locator('.el-select-dropdown:visible .el-select-dropdown__item').last()).toHaveText(`模板${count}`)
    await page.keyboard.press('Escape')
    await dialog.getByRole('button', { name: '取消', exact: true }).click()
  }
  failPage = 2
  await page.getByRole('button', { name: '新增', exact: true }).click()
  await expect(page.getByText('表单第二页读取失败', { exact: true })).toBeVisible()
  await expect(page.getByRole('dialog')).toBeHidden()
  failPage = 0
  await page.getByRole('button', { name: '新增', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '新增流程模型' })).toBeVisible()
})

test('设计器目录失败时不挂载画布，重试后可选第二页正式模板', async ({ page }) => {
  let failSecondPage = true
  await application(page, async ({ path, url, reply }) => {
    if (path === '/workflow/model/model-a') {
      await reply({ data: { modelId: 'model-a', modelKey: 'reuse', modelName: '正式模型', version: 1, revision: 7, bpmnXml: xml } })
    } else if (path.endsWith('/design-draft')) {
      await reply({ data: null })
    } else if (path === '/workflow/form/list') {
      const pageNum = Number(url.searchParams.get('pageNum'))
      if (pageNum === 2 && failSecondPage) await reply({ code: 500, msg: '设计器表单目录读取失败' })
      else await reply({ total: 51, rows: Array.from({ length: pageNum === 1 ? 50 : 1 }, (_, i) => {
        const formId = (pageNum - 1) * 50 + i + 1
        return { formId, formName: `模板${formId}`, content: template }
      }) })
    } else return false
    return true
  })
  await page.goto('/workflow/model-design/model-a')
  await expect(page.getByText('设计器表单目录读取失败', { exact: true })).toBeVisible()
  await expect(page.locator('.djs-container')).toHaveCount(0)
  failSecondPage = false
  await page.reload()
  await page.locator('.djs-element[data-element-id="start"]').click()
  await page.getByText('正式模板', { exact: true }).click()
  const formSelect = page.locator('.el-form-item').filter({ has: page.getByText('发起表单', { exact: true }) }).locator('.el-select')
  await formSelect.click()
  await expect(page.locator('.el-select-dropdown:visible .el-select-dropdown__item')).toHaveCount(51)
  await page.getByRole('option', { name: '模板51', exact: true }).click()
  await expect(formSelect).toContainText('模板51')
})

test('发起页恢复多个节点选人，64 字符截断、慢响应丢弃和失败重试保持已选标签', async ({ page }) => {
  let releaseSlow
  const calls = await application(page, async ({ path, url, reply }) => {
    if (path === '/workflow/process/draft/draft-a') {
      await reply({ data: {
        draftId: 'draft-a', revisionNo: 3, processDefinitionId: 'definition-a', deploymentId: 'deployment-a', editable: true, submittable: true,
        formSnapshot: { content: template, startMultiInstanceAssignments: ['first', 'second'].map(activityId => ({ activityId, activityName: activityId, mode: 'ALL', minUsers: 1, maxUsers: 5 })) },
        variables: {}, multiInstanceUserIds: { first: [1], second: [2] }
      } })
    } else if (path === '/workflow/identity/options') {
      const keyword = url.searchParams.get('keyword')
      if (keyword === 'slow') await new Promise(resolve => { releaseSlow = resolve })
      await reply(keyword === 'fail' ? { code: 500, msg: '选人读取失败' } : { rows: !keyword ? [user(1, '张一'), user(2, '李二')] : [user(3, keyword)] })
    } else return false
    return true
  })
  await page.goto('/workflow/process-draft/draft-a')
  const assignments = page.locator('.process-start-page__assignments')
  await expect(assignments).toContainText('张一')
  await expect(assignments).toContainText('李二')
  const search = assignments.getByRole('combobox').first()
  await search.fill('x'.repeat(64))
  await expect.poll(() => calls.filter(call => call.query.keyword === 'x'.repeat(64)).length).toBe(1)
  await search.fill('x'.repeat(65))
  await expect.poll(() => calls.filter(call => call.query.keyword === 'x'.repeat(64)).length).toBe(2)
  await search.fill('slow')
  await expect.poll(() => Boolean(releaseSlow)).toBe(true)
  await search.fill('fast')
  await expect(page.getByRole('option', { name: 'fast', exact: true })).toBeVisible()
  releaseSlow()
  await expect(page.getByRole('option', { name: 'slow', exact: true })).toHaveCount(0)
  await search.fill('fail')
  await expect(page.getByText('选人读取失败', { exact: true })).toBeVisible()
  await search.fill('retry')
  await expect(page.getByRole('option', { name: 'retry', exact: true })).toBeVisible()
  await expect(assignments).toContainText('张一')
  await expect(assignments).toContainText('李二')
  expect(calls.filter(call => call.path === '/workflow/identity/options').every(call => call.query.pageSize === '50')).toBe(true)
  await page.reload()
  await expect(assignments).toContainText('张一')
  await expect(assignments).toContainText('李二')
})
