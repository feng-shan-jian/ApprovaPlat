import { test, expect, openPage, settleResponse } from './fixtures/page-host.js'

async function credentials(page, host, handler = async () => false, options = {}) {
  return openPage(page, host, 'workflow/integrationCredential/index', async context => {
    if (await handler(context)) return true
    if (context.path === '/workflow/integration-credential/list') {
      await context.reply({ data: [1, 2].map(id => ({ credentialId: id, credentialName: `账号${id}`, tokenPrefix: `prefix${id}`,
        scopes: ['MESSAGE'], status: 'ACTIVE', rotateAllowed: true, revokeAllowed: true, revisionNo: 1 })) })
    } else if (context.path === '/workflow/connector/list') {
      await context.reply({ data: [1, 2].map(id => ({ endpointId: id, endpointName: `端点${id}`, status: 'ENABLED' })) })
    } else return false
    return true
  }, options)
}

async function create(page, bind = false) {
  await page.getByRole('button', { name: '新增账号', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增集成账号', exact: true })
  await dialog.getByRole('textbox', { name: /^\*?\s*账号名称$/ }).fill('提交账号')
  if (bind) {
    await dialog.locator('.el-form-item').filter({ hasText: '绑定连接端点' }).locator('.el-select').click()
    await page.getByRole('option', { name: '端点1', exact: true }).click()
  }
  return dialog
}

for (const action of ['取消', '导航离页', '卸载被测页面']) {
  test(`R3: ${action} at the real validation await boundary prevents create`, async ({ page, componentHost }) => {
    const calls = await credentials(page, componentHost)
    const dialog = await create(page)
    // Both native clicks occur in one browser task, before Element Plus's
    // Promise validation continuation. No private Vue method or ref is read.
    await dialog.getByRole('button', { name: '创建账号', exact: true }).evaluate((button, label) => {
      button.click()
      ;[...document.querySelectorAll('button')].find(candidate => candidate.textContent.trim() === label).click()
    }, action)
    if (action === '导航离页') await expect(page.getByText('已离开被测页面', { exact: true })).toBeVisible()
    else await expect(dialog).toBeHidden()
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0)
  })
}

test('R3: the click freezes submitted values before real validation and delayed endpoint metadata', async ({ page, componentHost }) => {
  let releaseMetadata
  const calls = await credentials(page, componentHost, async ({ path, reply }) => {
    if (path === '/workflow/connector/1/credential') {
      await new Promise(resolve => { releaseMetadata = resolve })
      await reply({ data: { endpointRevision: 7, credential: { revision: 3 } } })
    } else if (path === '/workflow/connector/1/integration-credential') await reply({ data: { credentialId: 1 } })
    else return false
    return true
  })
  const dialog = await create(page, true)
  await dialog.getByRole('button', { name: '创建账号', exact: true }).evaluate(button => {
    button.click()
    const input = button.closest('.el-dialog').querySelector('input')
    input.value = '点击之后的编辑'
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await expect.poll(() => Boolean(releaseMetadata)).toBe(true)
  await expect(dialog.getByRole('textbox', { name: /^\*?\s*账号名称$/ })).toBeDisabled()
  await expect(dialog.locator('.el-select').getByRole('combobox')).toBeDisabled()
  releaseMetadata()
  await expect(dialog).toBeHidden()
  expect(calls.find(call => call.path.endsWith('/integration-credential') && call.method === 'POST')).toMatchObject({
    path: '/workflow/connector/1/integration-credential', body: { endpointRevision: 7, credentialRevision: 3,
      credential: { credentialName: '提交账号', scopes: ['MESSAGE'], allowedVariables: [], rateLimitPerMinute: 60, expiresAt: null } }
  })
})

for (const action of ['取消', '导航离页', '卸载被测页面']) {
  test(`R3: ${action} during metadata lookup revokes the pending binding`, async ({ page, componentHost }) => {
    let releaseMetadata
    const calls = await credentials(page, componentHost, async ({ path, reply }) => {
      if (path !== '/workflow/connector/1/credential') return false
      await new Promise(resolve => { releaseMetadata = resolve })
      await reply({ data: { endpointRevision: 7, credential: { revision: 3 } } })
      return true
    })
    const dialog = await create(page, true)
    await dialog.getByRole('button', { name: '创建账号', exact: true }).click()
    await expect.poll(() => Boolean(releaseMetadata)).toBe(true)
    await page.getByRole('button', { name: action, exact: true }).dispatchEvent('click')
    const response = page.waitForResponse(response => response.url().endsWith('/workflow/connector/1/credential'))
    releaseMetadata()
    await settleResponse(page, response)
    expect(calls.filter(call => call.method === 'POST')).toHaveLength(0)
    if (action === '取消') {
      await expect(page.getByRole('button', { name: '新增账号', exact: true })).toBeEnabled()
      const reopened = await create(page, true)
      await expect(reopened.getByRole('button', { name: '创建账号', exact: true })).toBeEnabled()
    }
  })
}

test('R3: a closed catalog cannot make the reopened create dialog ready', async ({ page, componentHost }) => {
  const gates = []
  await credentials(page, componentHost, async ({ path, reply }) => {
    if (path !== '/workflow/connector/list') return false
    const index = gates.length
    await new Promise(resolve => gates.push(resolve))
    await reply({ data: [{ endpointId: index + 1, endpointName: `目录${index + 1}`, status: 'ENABLED' }] })
    return true
  })
  await page.getByRole('button', { name: '新增账号', exact: true }).click()
  await expect.poll(() => gates.length).toBe(1)
  const dialog = page.getByRole('dialog', { name: '新增集成账号', exact: true })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(dialog).toBeHidden()
  await page.getByRole('button', { name: '新增账号', exact: true }).click()
  await expect.poll(() => gates.length).toBe(2)
  const old = page.waitForResponse(response => response.url().endsWith('/workflow/connector/list'))
  gates[0]()
  await settleResponse(page, old)
  await expect(dialog.getByRole('button', { name: '创建账号', exact: true })).toBeDisabled()
  await expect(dialog.locator('.el-loading-mask')).toBeVisible()
  gates[1]()
  await expect(dialog.getByRole('button', { name: '创建账号', exact: true })).toBeEnabled()
  await dialog.locator('.el-select').click()
  await expect(page.getByRole('option', { name: '目录2', exact: true })).toBeVisible()
  await expect(page.getByRole('option', { name: '目录1', exact: true })).toHaveCount(0)
})

test('R4: canceled and failed rotations release the token slot, successful rotation requires acknowledgment', async ({ page, componentHost }) => {
  let attempts = 0
  const calls = await credentials(page, componentHost, async ({ path, reply }) => {
    if (!path.endsWith('/rotate')) return false
    attempts++
    await reply(attempts === 1 ? { code: 500, msg: '受控轮换失败' } : { data: { credentialId: 2, token: 'synthetic-token-2', credential: { credentialName: '账号2' } } })
    return true
  })
  const rotate = id => page.getByRole('row').filter({ hasText: `账号${id}` }).getByRole('button', { name: '轮换 Token', exact: true })
  await rotate(1).click()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await expect(rotate(2)).toBeEnabled()
  expect(calls.filter(call => call.path.endsWith('/rotate'))).toHaveLength(0)
  await rotate(1).click()
  await page.getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByText('受控轮换失败', { exact: true })).toBeVisible()
  await expect(rotate(2)).toBeEnabled()
  await rotate(2).click()
  await page.getByRole('button', { name: '确定', exact: true }).click()
  const token = page.getByRole('dialog', { name: '保存集成 Token', exact: true })
  await expect(token.getByText('账号：账号2（ID：2）', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '新增账号', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '导航离页', exact: true }).dispatchEvent('click')
  await expect(token).toBeVisible()
  await token.getByRole('button', { name: '我已保存', exact: true }).click()
  await page.getByRole('button', { name: '导航离页', exact: true }).click()
  await expect(page.getByText('已离开被测页面', { exact: true })).toBeVisible()
})

test('R4: unmount during real confirmation revokes token write', async ({ page, componentHost }) => {
  const calls = await credentials(page, componentHost)
  await page.getByRole('row').filter({ hasText: '账号1' }).getByRole('button', { name: '轮换 Token', exact: true }).click()
  await page.getByRole('button', { name: '卸载被测页面', exact: true }).dispatchEvent('click')
  await page.getByRole('button', { name: '确定', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(calls.filter(call => call.path.endsWith('/rotate'))).toHaveLength(0)
})
