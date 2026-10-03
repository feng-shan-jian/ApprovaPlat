import { expect, test } from '@playwright/test'

// 使用真实页面、表单和附件组件验证 UUID 恢复协议；对象授权与持久化由真实业务整链另行覆盖。
const attachmentId = '9b99ec62-232a-4339-ba2c-21b75e949518'
const fileName = '草稿审批依据.txt'
const field = (tag, vModel, label, extra = {}) => ({
  tag, vModel, label, workflowHidden: false, workflowReadable: true, workflowWritable: true, ...extra
})

async function draftPage(page, { metadataFailure = false } = {}) {
  await page.context().addCookies([{ name: 'Admin-Token', value: 'synthetic-frontend-session', url: test.info().project.use.baseURL }])
  const calls = []
  const draft = {
    draftId: 'attachment-draft', revisionNo: 1, status: 'DRAFT', editable: true, submittable: true,
    processDefinitionId: 'definition-a', deploymentId: 'deployment-a', businessKey: 'draft-attachment',
    variables: { title: '附件恢复合同', evidence: [attachmentId], hiddenEvidence: ['hidden-id'] },
    multiInstanceUserIds: {}, formSnapshot: {
      formName: '附件草稿', content: JSON.stringify({ fields: [
        field('el-input', 'title', '申请标题'),
        field('el-upload', 'evidence', '审批依据'),
        field('el-upload', 'hiddenEvidence', '隐藏附件', {
          workflowHidden: true, workflowReadable: false, workflowWritable: false
        })
      ] })
    }
  }
  await page.route('**/dev-api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/dev-api', '')
    calls.push({ path, method: request.method(), body: request.postDataJSON() })
    const reply = data => route.fulfill({ json: { code: 200, ...data } })
    if (path === '/getInfo') return reply({ user: { userId: 1, userName: 'frontend-review' }, roles: ['admin'], permissions: ['*:*:*'] })
    if (path === '/getRouters') return reply({ data: [] })
    if (path === '/workflow/process/draft/attachment-draft') {
      if (request.method() === 'PUT') {
        const payload = request.postDataJSON()
        Object.assign(draft, payload, { revisionNo: draft.revisionNo + 1 })
      }
      return reply({ data: draft })
    }
    if (path === `/workflow/attachment/${attachmentId}`) {
      return reply(metadataFailure ? { code: 403, msg: '无权读取此附件' } : { data: {
        attachmentId, originalName: fileName, status: 'DRAFT', fieldName: 'evidence', fileSize: 12
      } })
    }
    // 流程图读取失败不影响已授权草稿快照的查看和编辑。
    if (path.startsWith('/workflow/process/bpmnXml/')) return reply({ code: 404, msg: '流程图不可用' })
    return reply({ data: [], rows: [], total: 0 })
  })
  await page.goto('/workflow/process-draft/attachment-draft')
  return calls
}

test('草稿 UUID 恢复文件名，保存仍提交 UUID 且重载可恢复', async ({ page }) => {
  const calls = await draftPage(page)
  await expect(page.getByText(fileName, { exact: true })).toBeVisible()
  expect(calls.filter(call => call.path.startsWith('/workflow/attachment/')))
    .toEqual([{ path: `/workflow/attachment/${attachmentId}`, method: 'GET', body: null }])
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.method === 'PUT').length).toBe(1)
  expect(calls.find(call => call.method === 'PUT').body).toMatchObject({
    expectedVersion: 1, variables: { title: '附件恢复合同', evidence: [attachmentId] }
  })
  expect(calls.find(call => call.method === 'PUT').body.variables).not.toHaveProperty('hiddenEvidence')
  await page.reload()
  await expect(page.getByText(fileName, { exact: true })).toBeVisible()
})

test('恢复后的 DRAFT 附件只移除表单引用，由保存提交空 UUID 数组', async ({ page }) => {
  const calls = await draftPage(page)
  const item = page.locator('.el-upload-list__item').filter({ hasText: fileName })
  await expect(item).toBeVisible()
  await item.hover()
  await item.locator('.el-icon--close').click()
  await expect(page.getByText(fileName, { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await expect.poll(() => calls.filter(call => call.method === 'PUT').length).toBe(1)
  expect(calls.find(call => call.method === 'PUT').body.variables.evidence).toEqual([])
  expect(calls.some(call => call.method === 'DELETE')).toBe(false)
})

test('附件授权查询失败时不开放缺少附件的编辑表单或写出空值', async ({ page }) => {
  const calls = await draftPage(page, { metadataFailure: true })
  await expect(page.getByText('当前操作没有权限', { exact: true }).first()).toBeVisible()
  await expect(page.locator('.workflow-form-renderer')).toHaveCount(0)
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  expect(calls.some(call => call.method === 'PUT')).toBe(false)
})
