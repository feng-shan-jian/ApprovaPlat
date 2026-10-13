import { test as base, expect } from '@playwright/test'
import { fileURLToPath } from 'node:url'
import { createComponentHost } from './fixtures/component-host.js'
import { pageHarness, openPage, settleResponse } from './fixtures/page-host.js'

const test = base.extend({
  sessionHost: [async ({ browserName: _browserName }, use) => {
    const host = await createComponentHost(pageHarness, undefined, {
      '@/components/workflow/ProcessDesigner.vue': fileURLToPath(new URL('./fixtures/EditingCanvas.vue', import.meta.url))
    })
    try { await use(host) } finally { await host.close() }
  }, { scope: 'worker' }]
})

async function session(page, host, handler = async () => false, draft = null) {
  const calls = await openPage(page, host, 'workflow/model/design', async context => {
    if (await handler(context)) return true
    const { path, request, reply } = context
    if (path === '/workflow/model/source') await reply({ data: { modelId: 'source', modelName: '编辑协议', version: 1, revision: 7, bpmnXml: '正式原稿' } })
    else if (path.endsWith('/design-draft')) await reply({ data: request.method() === 'GET' ? draft : { revision: request.method() === 'DELETE' ? 0 : 1 } })
    else return false
    return true
  }, { route: '/workflow/model-design/source' })
  await expect(page.getByRole('textbox', { name: '画布内容' })).toHaveValue('正式原稿')
  return calls
}

test('C01: an inflight auto draft blocks formal submission and keeps model CAS distinct from draft CAS', async ({ page, sessionHost }) => {
  let releaseDraft
  const calls = await session(page, sessionHost, async ({ path, request, reply }) => {
    if (!path.endsWith('/design-draft') || request.method() !== 'PUT') return false
    await new Promise(resolve => { releaseDraft = resolve })
    await reply({ data: { revision: 4 } })
    return true
  })
  await page.getByRole('textbox', { name: '画布内容' }).fill('自动草稿')
  await expect.poll(() => Boolean(releaseDraft)).toBe(true)
  await page.getByRole('button', { name: '提交正式画布' }).click()
  expect(calls.filter(call => call.path === '/workflow/model/save')).toHaveLength(0)
  expect(calls.find(call => call.method === 'PUT').body).toEqual({ baseModelRevision: 7, expectedRevision: 0, bpmnXml: '自动草稿' })
  releaseDraft()
  await expect(page.getByRole('button', { name: '保留设计草稿' })).not.toHaveClass(/is-loading/)
  await page.getByRole('textbox', { name: '画布内容' }).fill('后续草稿')
  await page.getByRole('button', { name: '保留设计草稿' }).click()
  await expect.poll(() => calls.filter(call => call.method === 'PUT').length).toBe(2)
  expect(calls.filter(call => call.method === 'PUT')[1].body.expectedRevision).toBe(4)
  releaseDraft()
})

test('C01: late formal save and metadata failure retain later editing and the next revision', async ({ page, sessionHost }) => {
  let releaseSave, metadataReads = 0
  const calls = await session(page, sessionHost, async ({ path, reply }) => {
    if (path === '/workflow/model/source' && ++metadataReads > 1) await reply({ code: 500, msg: '受控元数据回读失败' })
    else if (path === '/workflow/model/save') {
      await new Promise(resolve => { releaseSave = resolve })
      await reply({ data: { modelId: 'source', version: 1, revision: 8 } })
    } else return false
    return true
  })
  const canvas = page.getByRole('textbox', { name: '画布内容' })
  await canvas.fill('提交修订')
  await page.getByRole('button', { name: '提交正式画布' }).click()
  await expect.poll(() => Boolean(releaseSave)).toBe(true)
  await canvas.fill('保存之后的新编辑')
  const response = page.waitForResponse(response => response.url().endsWith('/workflow/model/save'))
  releaseSave()
  await settleResponse(page, response)
  await expect(page.getByText('模型已保存，版本信息回读失败，请刷新后核对', { exact: true })).toBeVisible()
  await expect(canvas).toHaveValue('保存之后的新编辑')
  await expect(page.getByText('有未正式保存的修改', { exact: true })).toBeVisible()
  expect(calls.find(call => call.path === '/workflow/model/save').body).toEqual({ modelId: 'source', expectedRevision: 7, bpmnXml: '提交修订' })
  await page.getByRole('button', { name: '保留设计草稿' }).click()
  await expect.poll(() => calls.some(call => call.method === 'PUT')).toBe(true)
  expect(calls.find(call => call.method === 'PUT').body).toEqual({ baseModelRevision: 8, expectedRevision: 0, bpmnXml: '保存之后的新编辑' })
})

test('C01: new-version save carries later editing into that version using draft revision zero', async ({ page, sessionHost }) => {
  let releaseSave, versionDraft = null
  const calls = await session(page, sessionHost, async ({ path, request, reply }) => {
    if (path === '/workflow/model/save') {
      await new Promise(resolve => { releaseSave = resolve })
      await reply({ data: { modelId: 'new-version', version: 2, revision: 1 } })
    } else if (path === '/workflow/model/new-version') await reply({ data: { modelId: 'new-version', version: 2, revision: 1, bpmnXml: '已提交版本' } })
    else if (path === '/workflow/model/new-version/design-draft') {
      if (request.method() === 'PUT') versionDraft = { ...request.postDataJSON(), revision: 1 }
      await reply({ data: versionDraft })
    }
    else return false
    return true
  })
  const canvas = page.getByRole('textbox', { name: '画布内容' })
  await canvas.fill('已提交版本')
  await page.getByRole('button', { name: '提交正式画布' }).click()
  await expect.poll(() => Boolean(releaseSave)).toBe(true)
  await canvas.fill('新版本的后续编辑')
  releaseSave()
  await expect.poll(() => calls.some(call => call.path === '/workflow/model/new-version/design-draft' && call.method === 'PUT')).toBe(true)
  expect(calls.find(call => call.path === '/workflow/model/new-version/design-draft' && call.method === 'PUT').body)
    .toEqual({ baseModelRevision: 1, expectedRevision: 0, bpmnXml: '新版本的后续编辑' })
  const recovery = page.getByRole('dialog', { name: '设计草稿恢复与冲突处理', exact: true })
  await recovery.getByRole('button', { name: '恢复服务端草稿', exact: true }).click()
  await expect(canvas).toHaveValue('新版本的后续编辑')
  await canvas.fill('恢复后继续编辑')
  await page.getByRole('button', { name: '保留设计草稿', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.path === '/workflow/model/new-version/design-draft' && call.method === 'PUT').length).toBe(2)
  expect(calls.filter(call => call.path === '/workflow/model/new-version/design-draft' && call.method === 'PUT')[1].body)
    .toEqual({ baseModelRevision: 1, expectedRevision: 1, bpmnXml: '恢复后继续编辑' })
})

for (const choice of ['继续编辑', '放弃本次修改', '保留草稿后离开']) {
  test(`C01: leave choice ${choice} keeps the current canvas or completes its explicit draft action`, async ({ page, sessionHost }) => {
    const calls = await session(page, sessionHost)
    const canvas = page.getByRole('textbox', { name: '画布内容' })
    await canvas.fill('离页编辑')
    await page.getByRole('button', { name: '保留设计草稿' }).click()
    await expect(page.getByText('设计草稿已保留到服务器', { exact: true })).toBeVisible()
    await canvas.fill('草稿之后继续编辑')
    await page.getByRole('button', { name: '导航离页', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '保留当前设计', exact: true })
    await dialog.getByRole('button', { name: choice, exact: true }).click()
    if (choice === '继续编辑') {
      await expect(canvas).toHaveValue('草稿之后继续编辑')
      expect(calls.filter(call => call.method === 'DELETE')).toHaveLength(0)
    } else {
      await expect(page.getByText('已离开被测页面', { exact: true })).toBeVisible()
      if (choice === '放弃本次修改') expect(calls.find(call => call.method === 'DELETE').query.expectedRevision).toBe('1')
      else expect(calls.filter(call => call.method === 'PUT').at(-1).body).toEqual({ baseModelRevision: 7, expectedRevision: 1, bpmnXml: '草稿之后继续编辑' })
    }
  })
}
