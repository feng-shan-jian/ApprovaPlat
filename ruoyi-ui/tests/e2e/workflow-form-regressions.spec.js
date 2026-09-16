import { expect, test } from '@playwright/test'

const field = (name, extra = {}) => ({
  tag: 'el-input', vModel: name, label: name, placeholder: name,
  workflowHidden: false, workflowReadable: true, workflowWritable: true, ...extra
})
const row = children => ({ layout: 'rowFormItem', workflowHidden: false,
  workflowReadable: true, workflowWritable: true, children })
async function mount(page, fields, values = {}) {
  await page.goto('/tests/e2e/fixtures/form-regression.html')
  await page.waitForFunction(() => Boolean(window.mountFormRegression))
  await page.evaluate(args => window.mountFormRegression(args), { content: { fields }, values })
}
const events = page => page.evaluate(() => ({
  updates: window.formRegression.updates.length, changes: window.formRegression.changes.length
}))

test('真实输入与嵌套控件一次编辑一次事件，外部重置和切换不回流', async ({ page }) => {
  await mount(page, [field('title'), row([row([field('nested')])])], { title: 'Before' })
  await expect(page.getByPlaceholder('title')).toHaveValue('Before')
  expect(await events(page)).toEqual({ updates: 0, changes: 0 })
  await page.getByPlaceholder('title').fill('After')
  await page.getByPlaceholder('nested').fill('Nested')
  expect(await events(page)).toEqual({ updates: 2, changes: 2 })
  await page.evaluate(() => { window.formRegression.values = { title: 'Loaded', nested: 'Recovered' } })
  await expect(page.getByPlaceholder('nested')).toHaveValue('Recovered')
  await page.evaluate(() => { window.formRegression.values.nested = 'Reset' })
  await expect(page.getByPlaceholder('nested')).toHaveValue('Reset')
  await page.evaluate(content => { window.formRegression.content = content; window.formRegression.values = {} },
    { fields: [field('next', { defaultValue: 'Default' })] })
  await expect(page.getByPlaceholder('next')).toHaveValue('Default')
  expect(await events(page)).toEqual({ updates: 2, changes: 2 })
})

test('真实数组控件、隐藏只读权限和必填反馈保持', async ({ page }) => {
  await mount(page, [field('title', { required: true }),
    row([field('choices', { tag: 'el-checkbox-group', options: [{ label: '甲', value: 'a' }, { label: '乙', value: 'b' }] })]),
    field('locked', { workflowWritable: false }),
    field('secret', { workflowHidden: true, workflowReadable: false, workflowWritable: false })],
  { choices: [], locked: 'Read only', secret: 'Hidden' })
  expect(await page.evaluate(async () => { try { await window.formControls.validate(); return true } catch { return false } })).toBe(false)
  await expect(page.getByText('title不能为空')).toBeVisible()
  await expect(page.getByPlaceholder('locked')).toBeDisabled()
  await expect(page.getByPlaceholder('secret')).toHaveCount(0)
  await page.getByPlaceholder('title').fill('Ready')
  await page.getByText('甲', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '甲' })).toBeChecked()
  await page.getByText('乙', { exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '乙' })).toBeChecked()
  expect(await page.evaluate(() => window.formControls.getValues())).toEqual({ title: 'Ready', choices: ['a', 'b'] })
  expect(await events(page)).toEqual({ updates: 3, changes: 3 })
  expect(await page.evaluate(() => window.formControls.validate())).toBe(true)
})

test('真实附件控件上传在途阻止提交，完成仅一次事件并投影 UUID', async ({ page }) => {
  let finish
  const gate = new Promise(resolve => { finish = resolve })
  const id = 'da97b3cd-c257-452d-9c1b-e773cc203fc7'
  await page.route('**/dev-api/workflow/attachment', async route => {
    await gate
    await route.fulfill({ json: { code: 200, data: { attachmentId: id, originalName: 'proof.txt', status: 'TEMP' } } })
  })
  await mount(page, [row([row([field('files', { tag: 'el-upload' })])])], { files: [] })
  const request = page.waitForRequest('**/dev-api/workflow/attachment')
  await page.locator('input[type=file]').setInputFiles({ name: 'proof.txt', mimeType: 'text/plain', buffer: Buffer.from('proof') })
  await request
  expect(await page.evaluate(async () => { try { await window.formControls.ensureAttachmentsIdle(); return '' } catch (error) { return error.message } })).toBe('附件仍在上传中')
  finish()
  await expect(page.locator('.el-upload-list__item')).toHaveCount(1)
  await expect(page.getByText('proof.txt')).toBeVisible()
  expect(await events(page)).toEqual({ updates: 1, changes: 1 })
  expect(await page.evaluate(() => window.formControls.getValues())).toEqual({ files: [id] })
  expect(await page.evaluate(() => window.formControls.ensureAttachmentsIdle())).toBe(true)
  expect(await page.evaluate(() => window.formRegression.errors)).toEqual([])
})
