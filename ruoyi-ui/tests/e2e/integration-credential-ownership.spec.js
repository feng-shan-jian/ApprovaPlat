import { expect, test } from '@playwright/test'

// Real Vue forms and Axios; responses are controlled to expose ownership boundaries.
async function application(page, handler) {
  await page.context().addCookies([{ name: 'Admin-Token', value: 'synthetic-integration-session', url: test.info().project.use.baseURL }])
  const calls = []
  await page.route('**/dev-api/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace('/dev-api', '')
    const reply = data => route.fulfill({ json: { code: 200, ...data } })
    calls.push({ path, method: request.method(), body: request.postDataJSON() })
    if (path === '/getInfo') return reply({ user: { userId: 1, userName: 'integration-review' }, roles: ['admin'], permissions: ['*:*:*'] })
    if (path === '/getRouters') return reply({ data: [{ path: '/workflow', component: 'Layout', meta: { title: '工作流' }, children: [
      { path: 'extensions/integrationCredential', component: 'workflow/integrationCredential/index', name: 'ReviewIntegration', meta: { title: '集成账号', noCache: true } },
      { path: 'category', component: 'workflow/category/index', name: 'ReviewCategory', meta: { title: '流程分类', noCache: true } }
    ] }] })
    if (await handler({ path, request, reply })) return
    if (path === '/workflow/integration-credential/list') return reply({ data: [1, 2].map(id => ({ credentialId: id,
      credentialName: `账号${id}`, tokenPrefix: `prefix${id}`, scopes: ['MESSAGE'], allowedVariables: [], status: 'ACTIVE',
      revisionNo: 1, rateLimitPerMinute: 60, rotateAllowed: true, revokeAllowed: true })) })
    if (path === '/workflow/connector/list') return reply({ data: [1, 2].map(id => ({ endpointId: id, endpointName: `端点${id}`, status: 'ENABLED' })) })
    if (path.startsWith('/system/config/configKey/')) return reply({ msg: 'password' })
    return reply({ data: [], rows: [], total: 0 })
  })
  return calls
}

async function createForm(page, name, endpoint) {
  await page.getByRole('button', { name: '新增账号', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增集成账号', exact: true })
  await dialog.getByRole('textbox', { name: /^\*?\s*账号名称$/ }).fill(name)
  if (endpoint) {
    await dialog.locator('.el-form-item').filter({ has: page.getByText('绑定连接端点', { exact: true }) }).locator('.el-select').click()
    await page.getByRole('option', { name: endpoint, exact: true }).click()
  }
  return dialog
}

test('R3: closing a create dialog during endpoint metadata lookup prevents binding the retired target', async ({ page }) => {
  let releaseFirst
  const calls = await application(page, async ({ path, reply }) => {
    if (path === '/workflow/connector/1/credential') {
      await new Promise(resolve => { releaseFirst = resolve })
      await reply({ data: { endpointRevision: 11, credential: { revision: 12 } } })
    } else if (path === '/workflow/connector/2/credential') {
      await reply({ data: { endpointRevision: 21, credential: { revision: 22 } } })
    } else if (path === '/workflow/connector/2/integration-credential') {
      await reply({ data: { credentialId: 2 } })
    } else return false
    return true
  })
  await page.goto('/workflow/extensions/integrationCredential')
  const first = await createForm(page, '旧账号', '端点1')
  await first.getByRole('button', { name: '创建账号', exact: true }).click()
  await expect.poll(() => Boolean(releaseFirst)).toBe(true)
  await first.getByRole('button', { name: '取消', exact: true }).click()
  await expect(first).toBeHidden()
  await expect(page.getByRole('button', { name: '新增账号', exact: true })).toBeDisabled()
  releaseFirst()
  await expect(page.getByRole('button', { name: '新增账号', exact: true })).toBeEnabled()
  expect(calls.filter(call => call.path.endsWith('/integration-credential') && call.method === 'POST')).toHaveLength(0)
  const second = await createForm(page, '当前账号', '端点2')
  await second.getByRole('button', { name: '创建账号', exact: true }).click()
  await expect(second).toBeHidden()
  expect(calls.filter(call => call.path.endsWith('/integration-credential') && call.method === 'POST')).toEqual([
    { path: '/workflow/connector/2/integration-credential', method: 'POST', body: { endpointRevision: 21, credentialRevision: 22,
      credential: { credentialName: '当前账号', scopes: ['MESSAGE'], allowedVariables: [], rateLimitPerMinute: 60, expiresAt: null } } }
  ])
})

test('R4: token writes and unacknowledged results reserve one slot and show the corresponding account', async ({ page }) => {
  let releaseRotation
  const calls = await application(page, async ({ path, request, reply }) => {
    if (path === '/workflow/integration-credential/1/rotate') {
      await new Promise(resolve => { releaseRotation = resolve })
      await reply({ data: { credentialId: 1, token: 'synthetic-token-one', credential: { credentialName: '账号1' } } })
    } else if (path === '/workflow/integration-credential' && request.method() === 'POST') {
      await reply({ data: { credentialId: 3, token: 'synthetic-token-three', credential: { credentialName: '新账号3' } } })
    } else return false
    return true
  })
  await page.goto('/workflow/category')
  await page.getByRole('menuitem', { name: '集成账号', exact: true }).click()
  const firstRow = page.getByRole('row').filter({ hasText: '账号1' })
  const secondRow = page.getByRole('row').filter({ hasText: '账号2' })
  await firstRow.getByRole('button', { name: '轮换 Token', exact: true }).click()
  const confirmation = page.getByRole('dialog').filter({ hasText: '确认轮换“账号1”' })
  await expect(secondRow.getByRole('button', { name: '轮换 Token', exact: true })).toBeDisabled()
  await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => Boolean(releaseRotation)).toBe(true)
  await expect(page.getByRole('button', { name: '新增账号', exact: true })).toBeDisabled()
  await page.goBack()
  await expect(page).toHaveURL(/\/workflow\/extensions\/integrationCredential$/)
  releaseRotation()
  const tokenDialog = page.getByRole('dialog', { name: '保存集成 Token', exact: true })
  await expect(tokenDialog.getByText('synthetic-token-one', { exact: true })).toBeVisible()
  await expect(tokenDialog.getByText('账号：账号1（ID：1）', { exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(tokenDialog).toBeVisible()
  await expect(secondRow.getByRole('button', { name: '轮换 Token', exact: true })).toBeDisabled()
  await expect(secondRow.getByRole('button', { name: '吊销账号', exact: true })).toBeDisabled()
  expect(calls.filter(call => call.path.endsWith('/rotate'))).toHaveLength(1)
  await tokenDialog.getByRole('button', { name: '我已保存', exact: true }).click()
  await expect(tokenDialog).toBeHidden()
  await expect(page.getByRole('button', { name: '新增账号', exact: true })).toBeEnabled()
  const create = await createForm(page, '新账号3')
  await create.getByRole('button', { name: '创建账号', exact: true }).click()
  await expect(tokenDialog.getByText('synthetic-token-three', { exact: true })).toBeVisible()
  await expect(tokenDialog.getByText('账号：新账号3（ID：3）', { exact: true })).toBeVisible()
  await expect(tokenDialog.getByText('synthetic-token-one', { exact: true })).toHaveCount(0)
  await tokenDialog.getByRole('button', { name: '我已保存', exact: true }).click()
  await page.getByRole('menuitem', { name: '流程分类', exact: true }).click()
  await expect(page).toHaveURL(/\/workflow\/category$/)
})
