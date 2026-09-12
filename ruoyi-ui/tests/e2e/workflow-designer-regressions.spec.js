import { expect, test } from '@playwright/test'
import { BpmnModdle } from 'bpmn-moddle'
import flowable from '../../src/components/workflow/bpmn/flowableModdle.js'

test.use({ screenshot: 'only-on-failure' })

test.afterEach(async ({ page }, testInfo) => {
  if (page.isClosed()) return
  const state = await page.evaluate(() => window.designerRegression && ({
    xml: window.designerRegression.xml,
    errors: window.designerRegression.errors
  }))
  if (!state) return
  await testInfo.attach('author-bpmn', { body: state.xml, contentType: 'application/xml' })
  await testInfo.attach('designer-errors', { body: JSON.stringify(state.errors), contentType: 'application/json' })
  if (testInfo.status === 'passed') {
    const screenshot = testInfo.outputPath('verified-ui.png')
    await page.screenshot({ path: screenshot })
    await testInfo.attach('verified-ui', { path: screenshot, contentType: 'image/png' })
  }
})

const boundaryXml = type => `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
 xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
 xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:test:designer">
 <process id="designerRegression" isExecutable="true">
  <startEvent id="start" flowable:formKey="key_7"/>
  <userTask id="review" name="审批"/>
  <boundaryEvent id="boundary" attachedToRef="review" cancelActivity="true"><${type}/></boundaryEvent>
  <dataObject id="payload"/>
  <dataObjectReference id="dataRef" dataObjectRef="payload"/>
 </process>
 <bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="designerRegression">
  <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="130" y="180" width="36" height="36"/></bpmndi:BPMNShape>
  <bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="260" y="160" width="100" height="80"/></bpmndi:BPMNShape>
  <bpmndi:BPMNShape id="boundary_di" bpmnElement="boundary"><dc:Bounds x="300" y="222" width="36" height="36"/></bpmndi:BPMNShape>
  <bpmndi:BPMNShape id="data_di" bpmnElement="dataRef"><dc:Bounds x="450" y="160" width="36" height="50"/></bpmndi:BPMNShape>
 </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`

const forms = [
  { formId: 7, formName: '原有效模板', content: JSON.stringify({ fields: [{
    tag: 'el-input', vModel: 'reason', label: '事由', layout: 'colFormItem',
    workflowHidden: false, workflowReadable: true, workflowWritable: true
  }] }) },
  { formId: 8, formName: '缺少权限的旧模板', content: JSON.stringify({ fields: [{
    tag: 'el-input-number', vModel: 'amount', label: '金额', layout: 'colFormItem'
  }] }) },
  { formId: 9, formName: '另一有效模板', content: JSON.stringify({ fields: [{
    tag: 'el-input', vModel: 'description', label: '说明', layout: 'colFormItem',
    workflowHidden: false, workflowReadable: true, workflowWritable: true
  }] }) }
]

async function mount(page, type, xml = boundaryXml(type)) {
  await page.route('**/dev-api/**', route => {
    const data = route.request().url().includes('/bpmn-event/') ? [
      { eventCodeId: 1, eventType: 'ERROR', eventCode: 'QA_ERROR', eventName: '测试错误' },
      { eventCodeId: 2, eventType: 'ESCALATION', eventCode: 'QA_ESCALATION', eventName: '测试升级' }
    ] : route.request().url().includes('/extension/options/java') ? [{
      extensionKey: 'approva.collaboration-outbox', extensionName: '跨参与方可靠消息',
      extensionType: 'JAVA', implementationKey: 'COLLABORATION_OUTBOX_V1', versionId: 1, versionNo: 1
    }] : route.request().url().includes('/connector/options') ? [{
      endpointId: 1, endpointKey: 'receiver', endpointName: '验收接收端'
    }] : []
    return route.fulfill({ json: { code: 200, data } })
  })
  await page.goto('/tests/e2e/fixtures/designer-regression.html')
  await page.waitForFunction(() => typeof window.mountDesignerRegression === 'function')
  await page.evaluate(input => window.mountDesignerRegression(input), { xml, forms })
  await expect(page.locator('[data-element-id="boundary"] .djs-visual')).toBeVisible()
}

test('R06/R09 服务端诊断可定位画布元素，未知元素不可点击', async ({ page }) => {
  await mount(page, 'timerEventDefinition')
  const before = await page.evaluate(() => window.designerRegression.xml)
  await page.route('**/workflow/model/validate', route => route.fulfill({ json: {
    code: 200, data: { valid: false, issues: [
      { code: 'BPMN_DATA_OBJECT_INVALID', severity: 'ERROR', elementId: 'dataRef', processId: 'designerRegression', message: '数据对象缺少语义名称' },
      { code: 'UNKNOWN', severity: 'WARNING', elementId: 'missingElement', message: '已不存在的图元' }
    ] }
  } }))
  await page.getByRole('button', { name: '服务端校验', exact: true }).click()
  await expect(page.getByText('所属流程：designerRegression', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '定位元素 missingElement', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '定位元素 dataRef', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '流程校验' })).not.toBeVisible()
  await expect(page.locator('.djs-element.selected[data-element-id="dataRef"]')).toBeVisible()
  expect(await page.evaluate(() => window.designerRegression.xml)).toBe(before)
})

test('R06 消息抛出保持原图元，业务配置可回读和撤销', async ({ page }) => {
  const xml = boundaryXml('messageEventDefinition')
    .replace('<process id=', '<message id="notice" name="notice"/><process id=')
    .replace('<boundaryEvent id="boundary" attachedToRef="review" cancelActivity="true"><messageEventDefinition/></boundaryEvent>',
      '<intermediateThrowEvent id="boundary"><messageEventDefinition messageRef="notice"/></intermediateThrowEvent>')
  await mount(page, 'messageEventDefinition', xml)
  await page.locator('[data-element-id="boundary"] .djs-hit').click()
  await page.getByRole('button', { name: '展开全部属性分区' }).click()
  await page.locator('.el-form-item').filter({ has: page.locator('label').filter({ hasText: /^受控处理器$/ }) }).locator('.el-select').click()
  await page.getByRole('option', { name: '跨参与方可靠消息 · JAVA · v1', exact: true }).click()
  const target = page.locator('.collaboration-editor').getByRole('textbox', { name: '*目标流程 key', exact: true })
  await target.fill('receiverProcess')
  await target.press('Tab')
  const readConfig = async () => {
    const current = await page.evaluate(() => window.designerRegression.xml)
    const { rootElement } = await new BpmnModdle({ flowable }).fromXML(current)
    const event = rootElement.rootElements.find(element => element.$type === 'bpmn:Process').flowElements.find(element => element.id === 'boundary')
    const property = event.extensionElements?.values.find(value => value.$type === 'flowable:Properties')?.values.find(value => value.name === 'approva.messageThrow.config')
    return { type: event.$type, message: event.eventDefinitions[0].messageRef.name, config: property && JSON.parse(property.value) }
  }
  await expect.poll(async () => (await readConfig()).config?.targetProcessDefinitionKey).toBe('receiverProcess')
  expect(await readConfig()).toMatchObject({ type: 'bpmn:IntermediateThrowEvent', message: 'notice', config: { endpointKey: 'receiver', messageName: 'notice' } })
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect.poll(async () => (await readConfig()).config?.targetProcessDefinitionKey).toBe('')
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await page.locator('[data-element-id="review"] .djs-hit').click()
  await page.locator('[data-element-id="boundary"] .djs-hit').click()
  await expect(target).toHaveValue('receiverProcess')
  expect(await page.evaluate(() => window.designerRegression.errors)).toEqual([])
})

async function readBoundary(page) {
  const xml = await page.evaluate(() => window.designerRegression.xml)
  const { rootElement } = await new BpmnModdle({ flowable }).fromXML(xml)
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const event = process.flowElements.find(element => element.id === 'boundary')
  const definition = event.eventDefinitions[0]
  return {
    xml,
    key: definition.errorRef?.errorCode || definition.escalationRef?.escalationCode || '',
    timer: definition.timeDuration?.body || '',
    roots: rootElement.rootElements.filter(element => ['bpmn:Error', 'bpmn:Escalation'].includes(element.$type)).length
  }
}

function startFormSelect(page) {
  return page.locator('.el-form-item').filter({ has: page.locator('label').filter({ hasText: /^发起表单$/ }) }).locator('.el-select')
}

test('对照：有效模板可以切换，导出并重新选择后保留新模板', async ({ page }) => {
  await mount(page, 'timerEventDefinition')
  await page.locator('[data-element-id="start"] .djs-hit').click()
  await page.getByRole('button', { name: '展开全部属性分区' }).click()
  const select = startFormSelect(page)
  await expect(select).toContainText('原有效模板')
  const fields = page.getByRole('region', { name: '节点字段权限', exact: true })
  await expect(fields.locator('code')).toHaveText(['reason'])
  await select.click()
  await page.getByRole('option', { name: '另一有效模板', exact: true }).click()
  await expect.poll(async () => {
    const xml = await page.evaluate(() => window.designerRegression.xml)
    const { rootElement } = await new BpmnModdle({ flowable }).fromXML(xml)
    return rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
      .flowElements.find(element => element.id === 'start').get('flowable:formKey')
  }).toBe('key_9')
  await page.locator('[data-element-id="review"] .djs-hit').click()
  await page.locator('[data-element-id="start"] .djs-hit').click()
  await expect(select).toContainText('另一有效模板')
  await expect(fields.locator('code')).toHaveText(['description'])
})

for (const [type, key, label] of [
  ['errorEventDefinition', 'QA_ERROR', '测试错误 · QA_ERROR'],
  ['escalationEventDefinition', 'QA_ESCALATION', '测试升级 · QA_ESCALATION']
]) {
  test(`R07/R08 ${type} 选择编码后写入 XML，单次撤销和重做完整恢复`, async ({ page }) => {
    await mount(page, type)
    await page.locator('[data-element-id="boundary"] .djs-hit').click()
    await page.getByRole('button', { name: '展开全部属性分区' }).click()
    await page.getByLabel('业务编码', { exact: true }).click()
    await page.getByRole('option', { name: label, exact: true }).click()
    await page.getByRole('textbox', { name: '元素名称', exact: true }).focus()
    await expect.poll(async () => (await readBoundary(page)).key).toBe(key)
    await page.getByRole('button', { name: '撤销', exact: true }).click()
    await expect.poll(async () => (await readBoundary(page)).key).toBe('')
    expect((await readBoundary(page)).roots).toBe(0)
    await page.getByRole('button', { name: '重做', exact: true }).click()
    await expect.poll(async () => (await readBoundary(page)).key).toBe(key)
  })
}

test('R09 画布显示名称与实际数据变量名分别写入，数据配置单次撤销和重做', async ({ page }) => {
  await mount(page, 'timerEventDefinition')
  await page.locator('[data-element-id="dataRef"] .djs-hit').click()
  await page.getByRole('button', { name: '展开全部属性分区' }).click()
  const name = page.getByRole('textbox', { name: '元素名称', exact: true })
  await name.fill('采购明细')
  await name.press('Tab')
  const variable = page.getByRole('textbox', { name: '数据变量名', exact: true })
  await variable.fill('purchaseItems')
  await variable.press('Tab')
  await expect.poll(async () => {
    const xml = await page.evaluate(() => window.designerRegression.xml)
    const { rootElement } = await new BpmnModdle({ flowable }).fromXML(xml)
    const reference = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
      .flowElements.find(element => element.id === 'dataRef')
    return { label: reference.name, semanticName: reference.dataObjectRef.name }
  }).toEqual({ label: '采购明细', semanticName: 'purchaseItems' })
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await expect(variable).toHaveValue('')
  await page.getByRole('button', { name: '重做', exact: true }).click()
  await expect(variable).toHaveValue('purchaseItems')
  const type = page.locator('.el-form-item').filter({ has: page.getByText('数据类型', { exact: true }) }).locator('.el-select')
  await type.click()
  await page.getByRole('option', { name: '整数', exact: true }).click()
  const value = page.getByRole('textbox', { name: '默认值', exact: true })
  await value.fill('17')
  await value.press('Tab')
  await expect.poll(async () => {
    const xml = await page.evaluate(() => window.designerRegression.xml)
    const { rootElement } = await new BpmnModdle({ flowable }).fromXML(xml)
    const object = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
      .flowElements.find(element => element.id === 'dataRef').dataObjectRef
    return { type: object.itemSubjectRef, value: object.extensionElements?.values?.[0]?.body }
  }).toEqual({ type: 'xsd:int', value: '17' })
})

test('R07 相邻边界定时器 PT5S 经失焦和换选后仍保留', async ({ page }) => {
  await mount(page, 'timerEventDefinition')
  await page.locator('[data-element-id="boundary"] .djs-hit').click()
  await page.getByRole('button', { name: '展开全部属性分区' }).click()
  const input = page.getByRole('textbox', { name: '时间表达式', exact: true })
  await input.fill('PT5S')
  await input.press('Tab')
  await expect.poll(async () => (await readBoundary(page)).timer).toBe('PT5S')
  await page.locator('[data-element-id="review"] .djs-hit').click()
  await page.locator('[data-element-id="boundary"] .djs-hit').click()
  await expect(input).toHaveValue('PT5S')
})

test('R10 从第二池编辑本地流程，独立配置执行标志及开始表单', async ({ page }) => {
  await mount(page, 'timerEventDefinition')
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
    <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn"
      xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" targetNamespace="urn:pool-edit">
      <collaboration id="collab"><participant id="poolA" processRef="processA"/><participant id="poolB" processRef="processB"/></collaboration>
      <process id="processA" isExecutable="true"><startEvent id="startA" flowable:formKey="key_7"/></process>
      <process id="processB"><startEvent id="startB" flowable:formKey="key_7"/></process>
      <bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="collab">
        <bpmndi:BPMNShape id="a" bpmnElement="poolA" isHorizontal="true"><dc:Bounds x="80" y="100" width="500" height="160"/></bpmndi:BPMNShape>
        <bpmndi:BPMNShape id="b" bpmnElement="poolB" isHorizontal="true"><dc:Bounds x="80" y="300" width="500" height="160"/></bpmndi:BPMNShape>
        <bpmndi:BPMNShape id="sa" bpmnElement="startA"><dc:Bounds x="160" y="160" width="36" height="36"/></bpmndi:BPMNShape>
        <bpmndi:BPMNShape id="sb" bpmnElement="startB"><dc:Bounds x="160" y="360" width="36" height="36"/></bpmndi:BPMNShape>
      </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
    </definitions>`
  await page.evaluate(value => { window.designerRegression.xml = value }, xml)
  await page.locator('[data-element-id="poolB"] .djs-hit-all').click({ position: { x: 15, y: 70 } })
  await page.getByRole('button', { name: '编辑所属流程', exact: true }).click()
  await page.getByRole('button', { name: '展开全部属性分区' }).click()
  const executable = page.getByRole('switch', { name: '可执行流程' })
  await expect(executable).toBeChecked()
  await page.locator('.el-switch').filter({ has: executable }).click()
  await page.getByRole('textbox', { name: '版本标签', exact: true }).fill('second-pool-v1')
  await page.getByRole('textbox', { name: '版本标签', exact: true }).press('Tab')
  await page.getByRole('button', { name: '配置开始表单', exact: true }).click()
  await startFormSelect(page).click()
  await page.getByRole('option', { name: '另一有效模板', exact: true }).click()
  await expect.poll(async () => {
    const source = await page.evaluate(() => window.designerRegression.xml)
    const { rootElement } = await new BpmnModdle({ flowable }).fromXML(source)
    const a = rootElement.rootElements.find(element => element.id === 'processA')
    const b = rootElement.rootElements.find(element => element.id === 'processB')
    return { a: a.isExecutable, aForm: a.flowElements[0].get('flowable:formKey'),
      b: b.isExecutable, bForm: b.flowElements[0].get('flowable:formKey'), version: b.get('flowable:versionTag') }
  }).toEqual({ a: true, aForm: 'key_7', b: false, bForm: 'key_9', version: 'second-pool-v1' })
})

test('R11 拒绝非法模板时恢复原选择，保留原 BPMN 与字段', async ({ page }) => {
  await mount(page, 'timerEventDefinition')
  await page.locator('[data-element-id="start"] .djs-hit').click()
  await page.getByRole('button', { name: '展开全部属性分区' }).click()
  const select = startFormSelect(page)
  await expect(select).toContainText('原有效模板')
  const fields = page.getByRole('region', { name: '节点字段权限', exact: true })
  await expect(fields.locator('code')).toHaveText(['reason'])
  const before = await page.evaluate(() => window.designerRegression.xml)
  await select.click()
  const invalidOption = page.getByRole('option', { name: '缺少权限的旧模板', exact: true })
  if (await invalidOption.getAttribute('aria-disabled') === 'true') {
    await page.keyboard.press('Escape')
  } else {
    await invalidOption.click()
  }
  await expect.soft(select).toContainText('原有效模板')
  expect(await page.evaluate(() => window.designerRegression.xml)).toBe(before)
  await expect(fields.locator('code')).toHaveText(['reason'])
})
