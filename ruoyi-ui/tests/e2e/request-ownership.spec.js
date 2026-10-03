import { expect, test } from '@playwright/test'

// Real Vue pages and Axios with controlled HTTP timing; no backend persistence claim.
async function application(page, handler) {
  await page.context().addCookies([{ name: 'Admin-Token', value: 'request-ownership-session', url: test.info().project.use.baseURL }])
  const calls = []
  await page.route('**/dev-api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace('/dev-api', '')
    const reply = data => route.fulfill({ json: { code: 200, ...data } })
    calls.push({ path, method: request.method(), query: Object.fromEntries(url.searchParams), body: request.postDataJSON() })
    if (path === '/getInfo') return reply({ user: { userId: 1, userName: 'ownership-review' }, roles: ['admin'], permissions: ['*:*:*'] })
    if (path === '/getRouters') return reply({ data: [
      { path: '/workflow', component: 'Layout', meta: { title: '工作流' }, children: [
        ['category', '流程分类'], ['collaboration', '多池协作'], ['connector', '连接端点']
      ].map(([name, title]) => ({ path: name === 'collaboration' ? 'extensions/collaboration' : name, component: `workflow/${name}/index`, name: `Review${name}`, meta: { title, noCache: true } })) },
      { path: '/system', component: 'Layout', meta: { title: '系统管理' }, children: [
        ['user', '用户管理'], ['dict', '字典管理']
      ].map(([name, title]) => ({ path: name, component: `system/${name}/index`, name: `Review${name}`, meta: { title, noCache: true } })) }
    ] })
    if (await handler({ route, path, url, request, reply })) return
    if (path.startsWith('/system/config/configKey/')) return reply({ msg: 'password' })
    return reply({ rows: [], total: 0, data: [] })
  })
  return calls
}

async function settled(page, response) {
  await (await response).finished()
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}

test('A: category latest result survives late success, late failure, current failure and unmount', async ({ page }) => {
  const gates = new Map()
  await application(page, async ({ path, url, reply }) => {
    if (path !== '/workflow/category/list') return false
    const name = url.searchParams.get('categoryName') || '初始分类'
    if (name.startsWith('延迟')) await new Promise(resolve => gates.set(name, resolve))
    await reply(name.includes('失败') ? { code: 500, msg: name } : { rows: [{ categoryId: 1, categoryName: name, code: 'test' }], total: 1 })
    return true
  })
  await page.goto('/workflow/category')
  await expect(page.getByRole('cell', { name: '初始分类', exact: true })).toBeVisible()
  const query = async name => {
    await page.getByPlaceholder('请输入分类名称').fill(name)
    await page.getByRole('button', { name: '搜索', exact: true }).click()
  }
  for (const old of ['延迟旧值', '延迟失败']) {
    await query(old)
    await expect.poll(() => gates.has(old)).toBe(true)
    await query('当前分类')
    await expect(page.getByRole('cell', { name: '当前分类', exact: true })).toBeVisible()
    const response = page.waitForResponse(response => new URL(response.url()).searchParams.get('categoryName') === old)
    gates.get(old)()
    await settled(page, response)
    await expect(page.getByText(old, { exact: true })).toHaveCount(0)
    await expect(page.getByRole('cell', { name: '当前分类', exact: true })).toBeVisible()
  }
  await query('当前失败')
  await expect(page.getByText('当前失败', { exact: true })).toBeVisible()
  await expect(page.locator('.el-table .el-loading-mask')).toBeHidden()
  await query('延迟卸载失败')
  await expect.poll(() => gates.has('延迟卸载失败')).toBe(true)
  await page.getByRole('menuitem', { name: '连接端点', exact: true }).click()
  await expect(page).toHaveURL(/\/workflow\/connector$/)
  const response = page.waitForResponse(response => new URL(response.url()).searchParams.get('categoryName') === '延迟卸载失败')
  gates.get('延迟卸载失败')()
  await settled(page, response)
  await expect(page.getByText('延迟卸载失败', { exact: true })).toHaveCount(0)
})

test('B: collaboration direction and reopened audit own their latest responses', async ({ page }) => {
  let releaseOutbox, releaseAudit
  await application(page, async ({ path, reply }) => {
    if (path === '/workflow/collaboration/outbox') {
      await new Promise(resolve => { releaseOutbox = resolve })
      await reply({ rows: [{ messageId: 'old', messageName: '旧出站消息', status: 'PENDING' }], total: 1 })
    } else if (path === '/workflow/collaboration/inbound') {
      await reply({ rows: ['A', 'B'].map(id => ({ messageId: id, messageName: `入站${id}`, status: 'PROCESSED' })), total: 2 })
    } else if (path.endsWith('/A/audit')) {
      await new Promise(resolve => { releaseAudit = resolve })
      await reply({ data: [{ auditId: 1, action: '旧审计A', toStatus: 'PROCESSED' }] })
    } else if (path.endsWith('/B/audit')) {
      await reply({ data: [{ auditId: 2, action: '当前审计B', toStatus: 'PROCESSED' }] })
    } else return false
    return true
  })
  await page.goto('/workflow/extensions/collaboration')
  await expect.poll(() => Boolean(releaseOutbox)).toBe(true)
  await page.getByRole('tab', { name: '入站消息', exact: true }).click()
  await expect(page.getByText('入站B', { exact: true })).toBeVisible()
  const oldResponse = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/outbox'))
  releaseOutbox()
  await settled(page, oldResponse)
  await expect(page.getByText('旧出站消息', { exact: true })).toHaveCount(0)
  await page.getByRole('row').filter({ hasText: '入站A' }).getByRole('button', { name: '查看审计', exact: true }).click()
  await expect.poll(() => Boolean(releaseAudit)).toBe(true)
  const drawer = page.getByRole('dialog', { name: '消息审计' })
  await drawer.getByRole('button', { name: /close|关闭/i }).click()
  await expect(drawer).toBeHidden()
  await page.getByRole('row').filter({ hasText: '入站B' }).getByRole('button', { name: '查看审计', exact: true }).click()
  await expect(drawer.getByText('当前审计B · PROCESSED', { exact: true })).toBeVisible()
  const auditResponse = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/A/audit'))
  releaseAudit()
  await settled(page, auditResponse)
  await expect(drawer.getByText('旧审计A · PROCESSED', { exact: true })).toHaveCount(0)
})

test('B: closing slow connector A and opening B keeps credentials and write target B', async ({ page }) => {
  let releaseA
  const calls = await application(page, async ({ path, request, reply }) => {
    if (path === '/workflow/connector/list') {
      await reply({ data: [1, 2].map(id => ({ endpointId: id, endpointName: `端点${id}`, endpointKey: `ep${id}`, allowedMethods: 'GET', authType: 'BEARER', status: 'ENABLED' })) })
    } else if (/\/workflow\/connector\/[12]\/credential$/.test(path)) {
      const id = Number(path.split('/')[3])
      if (request.method() === 'GET' && id === 1) await new Promise(resolve => { releaseA = resolve })
      await reply({ data: { endpointRevision: id, credential: { revision: id, status: 'ACTIVE', providerReady: true } } })
    } else return false
    return true
  })
  await page.goto('/workflow/connector')
  await page.getByRole('row').filter({ hasText: '端点1' }).getByRole('button', { name: '凭据', exact: true }).click()
  await expect.poll(() => Boolean(releaseA)).toBe(true)
  const dialog = page.getByRole('dialog', { name: '受管连接凭据' })
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).toBeHidden()
  await page.getByRole('row').filter({ hasText: '端点2' }).getByRole('button', { name: '凭据', exact: true }).click()
  await expect(dialog.getByText('当前状态：ACTIVE · 凭据修订 2', { exact: true })).toBeVisible()
  const response = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/connector/1/credential'))
  releaseA()
  await settled(page, response)
  await expect(dialog.getByText('当前状态：ACTIVE · 凭据修订 2', { exact: true })).toBeVisible()
  await dialog.locator('input[type="password"]').fill('synthetic-credential')
  await dialog.getByRole('button', { name: '绑定 / 轮换', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(calls.find(call => call.method === 'PUT')).toMatchObject({ path: '/workflow/connector/2/credential', body: { endpointRevision: 2, credentialRevision: 2 } })
})

test('C: system user list rejects late success and releases loading after current failure', async ({ page }) => {
  let releaseOld
  await application(page, async ({ path, url, reply }) => {
    if (path !== '/system/user/list') return false
    const name = url.searchParams.get('userName') || '初始用户'
    if (name === '旧用户') await new Promise(resolve => { releaseOld = resolve })
    await reply(name === '失败用户' ? { code: 500, msg: '当前用户查询失败' } : { rows: [{ userId: 2, userName: name, nickName: name, status: '0', dept: {} }], total: 1 })
    return true
  })
  await page.goto('/system/user')
  const query = async name => {
    await page.getByPlaceholder('请输入用户名称').fill(name)
    await page.getByRole('button', { name: '搜索', exact: true }).click()
  }
  await expect(page.getByRole('cell', { name: '初始用户', exact: true }).first()).toBeVisible()
  await query('旧用户')
  await expect.poll(() => Boolean(releaseOld)).toBe(true)
  await query('新用户')
  await expect(page.getByRole('cell', { name: '新用户', exact: true }).first()).toBeVisible()
  const response = page.waitForResponse(response => new URL(response.url()).searchParams.get('userName') === '旧用户')
  releaseOld()
  await settled(page, response)
  await expect(page.getByRole('cell', { name: '旧用户', exact: true })).toHaveCount(0)
  await query('失败用户')
  await expect(page.getByText('当前用户查询失败', { exact: true })).toBeVisible()
  await expect(page.locator('.el-table .el-loading-mask')).toBeHidden()
})

test('C: dictionary drawer cannot commit the closed dictionary into a new target', async ({ page }) => {
  let releaseOld
  await application(page, async ({ path, url, reply }) => {
    if (path === '/system/dict/type/list') {
      await reply({ rows: [1, 2].map(id => ({ dictId: id, dictName: `字典${id}`, dictType: `dict_${id}`, status: '0' })), total: 2 })
    } else if (path === '/system/dict/data/list') {
      const type = url.searchParams.get('dictType')
      if (type === 'dict_1') await new Promise(resolve => { releaseOld = resolve })
      await reply({ rows: [{ dictCode: 1, dictLabel: `条目_${type}`, dictValue: '1', status: '0' }], total: 1 })
    } else return false
    return true
  })
  await page.goto('/system/dict')
  await page.getByText('dict_1', { exact: true }).click()
  await expect.poll(() => Boolean(releaseOld)).toBe(true)
  const drawer = page.getByRole('dialog')
  await drawer.getByRole('button', { name: /close|关闭/i }).click()
  await expect(drawer).toBeHidden()
  await page.getByText('dict_2', { exact: true }).click()
  await expect(drawer.getByText('条目_dict_2', { exact: true })).toBeVisible()
  const response = page.waitForResponse(response => new URL(response.url()).searchParams.get('dictType') === 'dict_1')
  releaseOld()
  await settled(page, response)
  await expect(drawer.getByText('条目_dict_1', { exact: true })).toHaveCount(0)
  await expect(drawer.getByText('条目_dict_2', { exact: true })).toBeVisible()
})

test('B18: designer validates missing fields early and preserves atomic undo, redo and unknown properties', async ({ page }) => {
  const rules = JSON.stringify({ version: 1, rules: [{ id: 'copy', trigger: 'NODE_COMPLETED', recipients: [{ type: 'FORM_USER_FIELD', values: ['retiredOwner'] }] }] }).replaceAll('"', '&quot;')
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:autocopy:test">
<process id="copy_process" name="抄送模型" isExecutable="true"><userTask id="review" name="审批"><extensionElements><flowable:properties><flowable:property name="vendor.unknown" value="preserve"/><flowable:property name="approva.autoCopyRules" value="${rules}"/></flowable:properties></extensionElements></userTask></process>
<bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="copy_process"><bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="200" y="120" width="100" height="80"/></bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>`
  await application(page, async ({ path, reply }) => {
    if (path === '/workflow/model/auto-copy') await reply({ data: { modelId: 'auto-copy', modelKey: 'copy_process', modelName: '抄送模型', version: 1, revision: 1, bpmnXml: xml } })
    else if (path.endsWith('/design-draft')) await reply({ data: null })
    else if (path.includes('author-fields')) await reply({ data: { nodes: [], processes: [], issues: [] } })
    else return false
    return true
  })
  await page.goto('/workflow/model-design/auto-copy?focusElement=review')
  await expect(page.getByRole('button', { name: '应用自动抄送规则', exact: true })).toBeVisible()
  await expect(page.getByText('自动抄送表单用户字段必须来自当前元素可见的正式标量字段', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '应用自动抄送规则', exact: true })).toBeDisabled()
  await page.locator('.auto-copy-rule-editor__recipient .el-select').first().click()
  await page.getByRole('option', { name: '流程发起人', exact: true }).click()
  await page.getByRole('button', { name: '应用自动抄送规则', exact: true }).click()
  const preview = async () => {
    await page.getByRole('button', { name: '预览流程源码', exact: true }).click()
    await page.getByRole('menuitem', { name: 'XML 预览', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: 'XML 预览', exact: true })
    const value = await dialog.getByRole('textbox').inputValue()
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(dialog).toBeHidden()
    return value
  }
  expect(await preview()).toContain('INITIATOR')
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  expect(await preview()).toContain('retiredOwner')
  await page.getByRole('button', { name: '重做', exact: true }).click()
  expect(await preview()).toContain('INITIATOR')
  await page.getByRole('button', { name: '删除自动抄送规则', exact: true }).click()
  await page.getByRole('button', { name: '应用自动抄送规则', exact: true }).click()
  const cleared = await preview()
  expect(cleared).not.toContain('approva.autoCopyRules')
  expect(cleared).toContain('name="vendor.unknown" value="preserve"')
})
