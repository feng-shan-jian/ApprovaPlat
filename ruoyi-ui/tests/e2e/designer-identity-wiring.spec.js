import { test, expect, openPage } from './fixtures/page-host.js'

function bpmn(type, values) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:identity:contract">
<process id="identity_process" name="目录合同" isExecutable="true"><userTask id="review" name="审批"><extensionElements><flowable:properties>
<flowable:property name="vendor.unknown" value="preserve"/><flowable:property name="approva.assignment.ruleVersion" value="1"/>
<flowable:property name="approva.assignment.type" value="${type}"/><flowable:property name="approva.assignment.targetIds" value="${values.join(',')}"/>
<flowable:property name="approva.assignment.formField" value=""/><flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
</flowable:properties></extensionElements></userTask></process>
<bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="identity_process"><bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="200" y="120" width="100" height="80"/></bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>`
}

for (const [type, values, searchTypes] of [
  ['CANDIDATE_USERS', ['71'], ['user']], ['CANDIDATE_GROUPS', ['ROLE71', 'DEPT72'], ['role', 'dept']]
]) {
  test(`M13 real designer ${type}: resolves selected identities, searches qualified candidates and preserves undo`, async ({ page, componentHost }) => {
    const xml = bpmn(type, values)
    const calls = await openPage(page, componentHost, 'workflow/model/design', async ({ path, request, url, reply }) => {
      if (path === '/workflow/model/source') await reply({ data: { modelId: 'source', modelKey: 'identity_process', modelName: '目录合同', version: 1, revision: 1, bpmnXml: xml } })
      else if (path.endsWith('/design-draft')) await reply({ data: request.method() === 'GET' ? null : { revision: 1 } })
      else if (path.endsWith('/author-field-catalog')) await reply({ data: { nodes: [], processes: [], issues: [] } })
      else if (path.endsWith('/identity/options/resolve')) {
        const body = request.postDataJSON()
        await reply({ data: body.values.map(value => ({ value, label: `已选${value}`, type: body.type, available: true })) })
      } else if (path.endsWith('/identity/options')) {
        const kind = url.searchParams.get('type'), keyword = url.searchParams.get('keyword')
        await reply({ rows: keyword ? [{ value: kind === 'user' ? '73' : kind === 'role' ? 'ROLE73' : 'DEPT74', label: `新候选${kind}`, type: kind, available: true }] : [], total: keyword ? 1 : 0 })
      } else return false
      return true
    }, { route: '/workflow/model-design/source?focusElement=review' })
    await page.addStyleTag({ content: 'html,body,#app,main{height:100%;margin:0} .model-design-page{height:900px}' })
    const editor = page.locator('.participant-rule-editor').filter({ hasText: '办理人规则' })
    await expect(editor).toBeVisible()
    for (const value of values) await expect(editor.getByText(`已选${value}`, { exact: true })).toBeVisible()
    const resolves = calls.filter(call => call.path.endsWith('/identity/options/resolve')).map(call => call.body)
    expect(resolves).toEqual(searchTypes.map(kind => ({ type: kind, capability: 'claim', values: values.filter(value =>
      kind === 'user' || value.startsWith(kind.toUpperCase())) })))
    const selected = editor.locator('.el-select').last()
    await selected.locator('input').fill('新目录')
    await expect.poll(() => calls.filter(call => call.path.endsWith('/identity/options') && call.query.keyword === '新目录').length).toBe(searchTypes.length)
    expect(calls.filter(call => call.query.keyword === '新目录').map(call => call.query)).toEqual(searchTypes.map(kind => ({ type: kind, capability: 'claim', keyword: '新目录', pageNum: '1', pageSize: '50' })))
    await page.getByRole('option', { name: `新候选${searchTypes[0]}`, exact: true }).click()
    await page.keyboard.press('Escape')
    await expect(editor.getByText(`新候选${searchTypes[0]}`, { exact: true })).toBeVisible()
    const preview = async () => {
      await page.getByRole('button', { name: '预览流程源码', exact: true }).click()
      await page.getByRole('menuitem', { name: 'XML 预览', exact: true }).click()
      const dialog = page.getByRole('dialog', { name: 'XML 预览', exact: true })
      const result = await dialog.getByRole('textbox').inputValue()
      await dialog.getByRole('button', { name: '关闭', exact: true }).click()
      return result
    }
    const added = searchTypes[0] === 'user' ? '73' : 'ROLE73'
    expect(await preview()).toContain(`value="${[...values, added].join(',')}"`)
    // The existing participant action writes its platform properties and then
    // clears legacy Flowable identities. Preserve both command-stack entries.
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    expect(await preview()).toContain(`value="${values.join(',')}"`)
    await page.getByRole('button', { name: '重做', exact: true }).click()
    await page.getByRole('button', { name: '重做', exact: true }).click()
    const restored = await preview()
    expect(restored).toContain(`value="${[...values, added].join(',')}"`)
    expect(restored).toContain('name="vendor.unknown" value="preserve"')
    expect(await page.pageErrors()).toEqual([])
  })
}
