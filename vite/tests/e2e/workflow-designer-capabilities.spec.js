import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { expect, test } from '@playwright/test'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createWorkflowCategory,
  createWorkflowForm,
  findWorkflowUserOption,
  openWorkflowRoleSession
} from './support/workflow-fixture.js'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'

test.describe.configure({ mode: 'serial' })

/**
 * 生成用于真实设计器往返的最小可执行 BPMN，并可加入一个故意断连的节点触发客户端 Lint。
 * @param {{processKey: string, processName: string, formId: string, approverUserId: string, disconnected?: boolean, embedded?: boolean, advanced?: boolean}} input 流程标识、名称、正式表单主键、办理用户主键、内嵌表单及高级注释开关。
 * @returns {string} 包含完整 BPMN DI 坐标的 UTF-8 XML 正文。
 */
function buildDesignerBpmn({
  processKey,
  processName,
  formId,
  approverUserId,
  disconnected = false,
  embedded = false,
  advanced = false
}) {
  const startEvent = embedded
    ? `<startEvent id="start" name="提交申请">
        <extensionElements>
          <flowable:formProperty id="requestReason" name="申请原因" type="string" readable="true" writable="true" required="true" />
          <flowable:formProperty id="requestDate" name="申请日期" type="date" variable="requestDateValue" datePattern="yyyy-MM-dd" readable="true" writable="true" required="true" />
        </extensionElements>
        <outgoing>flow_start_review</outgoing>
      </startEvent>`
    : `<startEvent id="start" name="提交申请" flowable:formKey="key_${formId}"><outgoing>flow_start_review</outgoing></startEvent>`
  const disconnectedTask = disconnected
    ? '<userTask id="orphanTask" name="孤立节点" />'
    : ''
  const disconnectedShape = disconnected
    ? '<bpmndi:BPMNShape id="shape_orphan" bpmnElement="orphanTask"><omgdc:Bounds x="260" y="270" width="100" height="80" /></bpmndi:BPMNShape>'
    : ''
  const advancedElements = advanced
    ? '<textAnnotation id="designNote"><text>内嵌表单高级往返</text></textAnnotation><association id="association_review_note" sourceRef="review" targetRef="designNote" />'
    : ''
  const advancedDi = advanced
    ? '<bpmndi:BPMNShape id="shape_design_note" bpmnElement="designNote"><omgdc:Bounds x="410" y="290" width="150" height="50" /></bpmndi:BPMNShape><bpmndi:BPMNEdge id="edge_association_review_note" bpmnElement="association_review_note"><omgdi:waypoint x="330" y="230" /><omgdi:waypoint x="410" y="315" /></bpmndi:BPMNEdge>'
    : ''
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://approvaplat.example/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    ${startEvent}
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="review" />
    <userTask id="review" name="审批处理" flowable:assignee="${approverUserId}"><incoming>flow_start_review</incoming><outgoing>flow_review_end</outgoing></userTask>
    <sequenceFlow id="flow_review_end" sourceRef="review" targetRef="end" />
    <endEvent id="end" name="结束"><incoming>flow_review_end</incoming></endEvent>
    ${advancedElements}
    ${disconnectedTask}
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="100" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_review" bpmnElement="review"><omgdc:Bounds x="260" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="520" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      ${disconnectedShape}
      ${advancedDi}
      <bpmndi:BPMNEdge id="edge_start_review" bpmnElement="flow_start_review"><omgdi:waypoint x="136" y="190" /><omgdi:waypoint x="260" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_review_end" bpmnElement="flow_review_end"><omgdi:waypoint x="360" y="190" /><omgdi:waypoint x="520" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 从工具栏下载指定格式并读取浏览器真实落盘内容。
 * @param {import('@playwright/test').Page} page 已打开设计器的真实浏览器页面。
 * @param {'导出 BPMN'|'导出 XML'|'导出 SVG'} menuLabel 导出菜单文案。
 * @returns {Promise<{filename: string, content: string}>} 浏览器建议文件名和实际下载正文。
 */
async function downloadDesignerFile(page, menuLabel) {
  await page.getByRole('button', { name: '导出流程' }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('menuitem', { name: menuLabel }).click()
  const download = await downloadPromise
  const downloadPath = await download.path()
  expect(downloadPath, `${menuLabel} 必须产生真实下载文件`).toBeTruthy()
  return {
    filename: download.suggestedFilename(),
    content: await readFile(downloadPath, 'utf8')
  }
}

/**
 * 打开 XML 或 JSON 源码预览并返回只读文本框中的完整内容。
 * @param {import('@playwright/test').Page} page 已打开设计器的真实浏览器页面。
 * @param {'XML 预览'|'JSON 预览'} menuLabel 预览菜单文案。
 * @returns {Promise<string>} 页面真实渲染的预览正文。
 */
async function readDesignerPreview(page, menuLabel) {
  await page.getByRole('button', { name: '预览流程源码' }).click()
  await page.getByRole('menuitem', { name: menuLabel }).click()
  const dialog = page.getByRole('dialog', { name: menuLabel })
  await expect(dialog).toBeVisible()
  const content = await dialog.getByRole('textbox').inputValue()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  return content
}

/**
 * 在指定桌面视口核验设计器稳定区域全部位于可视工作区内，并生成当前实现截图。
 * @param {import('@playwright/test').Page} page 已打开真实模型的设计器页面。
 * @param {{width: number, height: number}} viewport 目标桌面视口尺寸。
 * @returns {Promise<void>} 几何边界、区域互斥和截图写入全部完成后结束。
 */
async function assertDesignerViewport(page, viewport) {
  await page.setViewportSize(viewport)
  const pageHeaderTitle = page.locator('.model-design-page__identity h2')
  const designer = page.locator('.process-designer')
  const toolbar = page.locator('.designer-toolbar')
  const body = page.locator('.process-designer__body')
  const canvas = page.locator('.process-designer__canvas')
  const palette = canvas.locator('.djs-palette')
  const propertiesResizer = page.locator('.process-designer__properties-resizer')
  const properties = page.locator('.designer-properties-panel')
  const minimap = canvas.locator('.djs-minimap')
  await expect(designer).toBeVisible()
  await expect(toolbar).toBeVisible()
  await expect(palette).toBeVisible()
  await expect(propertiesResizer).toBeVisible()
  await expect(properties).toBeVisible()
  await expect(minimap).toBeVisible()

  expect(await page.evaluate(() => ({ x: window.scrollX, y: window.scrollY })),
    `${viewport.width}x${viewport.height} 设计页不得产生文档级滚动`).toEqual({ x: 0, y: 0 })
  const pageHeaderTitleBox = await pageHeaderTitle.boundingBox()
  expect(pageHeaderTitleBox, '模型标题必须具有稳定尺寸').toBeTruthy()
  expect(pageHeaderTitleBox.height, '模型标题必须保持单行').toBeLessThanOrEqual(24)

  const [designerBox, toolbarBox, bodyBox, canvasBox, paletteBox, propertiesResizerBox, propertiesBox, minimapBox] =
    await Promise.all([designer, toolbar, body, canvas, palette, propertiesResizer, properties, minimap]
      .map(locator => locator.boundingBox()))
  for (const [label, box] of Object.entries({
    designerBox, toolbarBox, bodyBox, canvasBox, paletteBox, propertiesResizerBox, propertiesBox, minimapBox
  })) {
    expect(box, `${viewport.width}x${viewport.height} ${label} 必须具有稳定尺寸`).toBeTruthy()
    expect(box.width, `${viewport.width}x${viewport.height} ${label} 宽度必须为正`).toBeGreaterThan(0)
    expect(box.height, `${viewport.width}x${viewport.height} ${label} 高度必须为正`).toBeGreaterThan(0)
  }

  const right = box => box.x + box.width
  const bottom = box => box.y + box.height
  expect(designerBox.x).toBeGreaterThanOrEqual(0)
  expect(right(designerBox)).toBeLessThanOrEqual(viewport.width + 1)
  expect(bottom(designerBox)).toBeLessThanOrEqual(viewport.height + 1)
  expect(toolbarBox.x).toBeGreaterThanOrEqual(designerBox.x)
  expect(right(toolbarBox)).toBeLessThanOrEqual(right(designerBox) + 1)
  expect(bodyBox.y).toBeGreaterThanOrEqual(bottom(toolbarBox) - 1)
  const compactProperties = await body.evaluate(element => (
    element.classList.contains('process-designer__body--compact-properties')
  ))
  if (compactProperties) {
    // 紧凑视口使用工作区内浮层，画布保持完整宽度，属性面板不得越出设计器主体。
    expect(right(canvasBox)).toBeLessThanOrEqual(right(bodyBox) + 1)
    expect(propertiesBox.x).toBeGreaterThanOrEqual(bodyBox.x + 11)
  } else {
    expect(right(canvasBox)).toBeLessThanOrEqual(propertiesResizerBox.x + 1)
    expect(right(propertiesResizerBox)).toBeLessThanOrEqual(propertiesBox.x + 1)
  }
  expect(propertiesBox.y).toBeGreaterThanOrEqual(bodyBox.y - 1)
  expect(right(propertiesBox)).toBeLessThanOrEqual(right(bodyBox) + 1)
  expect(bottom(propertiesBox)).toBeLessThanOrEqual(bottom(bodyBox) + 1)
  for (const [label, box] of Object.entries({ paletteBox, minimapBox })) {
    expect(box.x, `${label} 左边界必须位于画布内`).toBeGreaterThanOrEqual(canvasBox.x - 1)
    expect(box.y, `${label} 上边界必须位于画布内`).toBeGreaterThanOrEqual(canvasBox.y - 1)
    expect(right(box), `${label} 右边界必须位于画布内`).toBeLessThanOrEqual(right(canvasBox) + 1)
    expect(bottom(box), `${label} 下边界必须位于画布内`).toBeLessThanOrEqual(bottom(canvasBox) + 1)
  }

  const toolbarButtonBoxes = await toolbar.locator('button:visible').evaluateAll(buttons =>
    buttons.map(button => {
      const bounds = button.getBoundingClientRect()
      return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height }
    }))
  expect(toolbarButtonBoxes.length).toBeGreaterThan(0)
  for (const buttonBox of toolbarButtonBoxes) {
    expect(buttonBox.x).toBeGreaterThanOrEqual(toolbarBox.x)
    expect(right(buttonBox)).toBeLessThanOrEqual(right(toolbarBox) + 1)
    expect(buttonBox.y).toBeGreaterThanOrEqual(toolbarBox.y)
    expect(bottom(buttonBox)).toBeLessThanOrEqual(bottom(toolbarBox) + 1)
  }

  await page.screenshot({
    path: `output/playwright/bpmn-designer-current-${viewport.width}x${viewport.height}.png`,
    fullPage: false
  })
}

test('设计器通过真实页面完成导入导出、校验、偏好、模拟、保存和重开', async ({ browser }) => {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  const processKey = `designer_phase1_${suffix}`
  const processName = `设计器验收_${suffix}`
  const editedProcessName = `${processName}_已编辑`
  const resources = { modelIds: [], deploymentIds: [] }
  const sessions = []
  let designerSession
  let originalPreference

  try {
    designerSession = await openWorkflowRoleSession(browser, 'workflow_designer')
    sessions.push(designerSession)
    const page = designerSession.page
    originalPreference = (await callWorkflowApi(
      page, 'GET', '/workflow/designer/preference')).data
    const approver = await findWorkflowUserOption(page, 'workflow_approver')
    expect(approver, '设计器回归必须取得具备真实办理资格的用户').toBeTruthy()

    const categoryCode = `designer_${suffix}`
    await createWorkflowCategory(page, `设计器分类_${suffix}`, categoryCode, resources)
    const formId = await createWorkflowForm(page, `设计器表单_${suffix}`, resources)
    const created = await callWorkflowApi(page, 'POST', '/workflow/model', {
      data: {
        modelName: processName,
        modelKey: processKey,
        category: categoryCode,
        description: 'BPMN 设计器 Phase 1 真实浏览器回归',
        formType: 0,
        formId: Number(formId)
      }
    })
    const modelId = String(created.data?.modelId || '')
    expect(modelId, '设计器验收模型创建必须返回正式主键').not.toBe('')
    resources.modelIds.push(modelId)

    const modelResponsePromise = page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/model/${modelId}`, 'GET'))
    await page.goto(`/workflow/model-design/${modelId}`)
    await expectAjaxSuccess(await modelResponsePromise, `/workflow/model/${modelId}`)
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeVisible()

    // 先导入断连节点并展开 Lint，证明问题来自真实 BPMN 模型而不是固定提示。
    const invalidXml = buildDesignerBpmn({
      processKey, processName, formId, approverUserId: String(approver.value), disconnected: true
    })
    await page.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}-invalid.bpmn`,
      mimeType: 'application/xml',
      buffer: Buffer.from(invalidXml, 'utf8')
    })
    const lintButton = page.locator('.bjsl-button-error')
    await expect(lintButton).toBeVisible()
    await lintButton.click()
    await expect(page.locator('.bjsl-button-inactive')).toBeVisible()
    await page.locator('.bjsl-button-inactive').click()
    await expect(lintButton).toBeVisible()
    const lintOverlay = page.locator('.bjsl-overlay').first()
    await expect(lintOverlay).toBeVisible()
    await expect(lintOverlay.locator('.bjsl-issues')).not.toHaveText('')

    // 再导入可执行版本，后续所有保存和导出均使用同一份真实 Modeler 状态。
    const validXml = buildDesignerBpmn({
      processKey, processName, formId, approverUserId: String(approver.value)
    })
    await page.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}.bpmn`,
      mimeType: 'application/xml',
      buffer: Buffer.from(validXml, 'utf8')
    })
    await expect(page.locator('.djs-label').filter({ hasText: '孤立节点' })).toHaveCount(0)
    await expect(page.locator('.djs-label').filter({ hasText: '提交申请' }).first()).toBeVisible()
    await expect(page.locator('.bjsl-button-error')).toHaveCount(0)

    // 两个正式目标视口都执行 DOM 几何断言，截图只是复核材料而不是唯一通过依据。
    await assertDesignerViewport(page, { width: 1024, height: 768 })
    await assertDesignerViewport(page, { width: 1366, height: 768 })
    await assertDesignerViewport(page, { width: 1440, height: 900 })
    await assertDesignerViewport(page, { width: 1920, height: 1080 })

    // 分隔条同时验证键盘可访问性和 bpmn-js 画布随宽度变化的真实重算。
    const propertiesResizer = page.locator('.process-designer__properties-resizer')
    const resizablePropertiesPanel = page.locator('.designer-properties-panel')
    const initialPropertiesWidth = (await resizablePropertiesPanel.boundingBox()).width
    await propertiesResizer.focus()
    await propertiesResizer.press('ArrowLeft')
    await expect.poll(async () => (await resizablePropertiesPanel.boundingBox()).width)
      .toBeGreaterThan(initialPropertiesWidth)
    await propertiesResizer.press('Home')

    const bpmnDownload = await downloadDesignerFile(page, '导出 BPMN')
    expect(bpmnDownload.filename).toBe(`${processKey}.bpmn20.xml`)
    expect(bpmnDownload.content).toContain(`<process id="${processKey}"`)
    const xmlDownload = await downloadDesignerFile(page, '导出 XML')
    expect(xmlDownload.filename).toBe(`${processKey}.xml`)
    expect(xmlDownload.content).toContain(`flowable:formKey="key_${formId}"`)
    const svgDownload = await downloadDesignerFile(page, '导出 SVG')
    expect(svgDownload.filename).toBe(`${processKey}.svg`)
    expect(svgDownload.content).toMatch(/<svg[\s>]/)

    const xmlPreview = await readDesignerPreview(page, 'XML 预览')
    expect(xmlPreview).toContain(`<process id="${processKey}"`)
    const jsonPreview = await readDesignerPreview(page, 'JSON 预览')
    const parsedPreview = JSON.parse(jsonPreview)
    expect(parsedPreview.name).toBe('definitions')

    const processNameInput = page.getByRole('textbox', { name: '元素名称' })
    await processNameInput.fill(editedProcessName)
    await processNameInput.press('Tab')
    const undoButton = page.getByRole('button', { name: '撤销' })
    const redoButton = page.getByRole('button', { name: '重做' })
    await expect(undoButton).toBeEnabled()
    await undoButton.click()
    await expect(processNameInput).toHaveValue(processName)
    await redoButton.click()
    await expect(processNameInput).toHaveValue(editedProcessName)

    // 在同一真实模型中导入内嵌 FormData 和标准注释/关联，随后只通过属性面板完成编辑。
    const embeddedXml = buildDesignerBpmn({
      processKey,
      processName: editedProcessName,
      formId,
      approverUserId: String(approver.value),
      embedded: true,
      advanced: true
    })
    await page.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}-embedded.bpmn`,
      mimeType: 'application/xml',
      buffer: Buffer.from(embeddedXml, 'utf8')
    })
    await page.locator('[data-element-id="start"]').click()
    const propertiesPanel = page.locator('.designer-properties-panel')
    await expect(propertiesPanel.getByText('内嵌表单', { exact: true })).toBeVisible()
    await expect(propertiesPanel.locator('.embedded-form-editor__field')).toHaveCount(2)

    // 来源切换必须是一条可撤销命令；撤销后 formKey 和 FormData 一并恢复，不能形成冲突 XML。
    await propertiesPanel.getByText('正式模板', { exact: true }).click()
    await expect(propertiesPanel.locator('.embedded-form-editor')).toHaveCount(0)
    await undoButton.click()
    await expect(propertiesPanel.locator('.embedded-form-editor__field')).toHaveCount(2)

    const fieldIdInputs = propertiesPanel.getByRole('textbox', { name: '字段标识' })
    await fieldIdInputs.first().fill('requestSummary')
    await fieldIdInputs.first().press('Tab')
    const fieldNameInputs = propertiesPanel.getByRole('textbox', { name: '字段名称' })
    await fieldNameInputs.first().fill('申请摘要')
    await fieldNameInputs.first().press('Tab')
    const variableNameInputs = propertiesPanel.getByRole('textbox', { name: '变量名' })
    await variableNameInputs.nth(1).fill('approvalDecision')
    await variableNameInputs.nth(1).press('Tab')

    // 把第二个日期字段改为静态枚举，并补齐两个确定性选项。
    const embeddedFieldRows = propertiesPanel.locator('.embedded-form-editor__field')
    const secondFieldType = embeddedFieldRows.nth(1).getByRole('combobox', { name: '字段类型' })
    await secondFieldType.press('Enter')
    await secondFieldType.press('ArrowDown')
    await secondFieldType.press('Enter')
    const firstOptionId = propertiesPanel.getByRole('textbox', { name: '字段 2 选项值 1' })
    await firstOptionId.fill('APPROVE')
    await firstOptionId.press('Tab')
    const firstOptionName = propertiesPanel.getByRole('textbox', { name: '字段 2 选项名称 1' })
    await firstOptionName.fill('同意')
    await firstOptionName.press('Tab')
    await propertiesPanel.getByRole('button', { name: '为字段 2 添加枚举选项' }).click()
    const secondOptionId = propertiesPanel.getByRole('textbox', { name: '字段 2 选项值 2' })
    await secondOptionId.fill('REJECT')
    await secondOptionId.press('Tab')
    const secondOptionName = propertiesPanel.getByRole('textbox', { name: '字段 2 选项名称 2' })
    await secondOptionName.fill('拒绝')
    await secondOptionName.press('Tab')

    // 新增字段并改为 long，证明 UI 不只是回显导入 XML。
    await propertiesPanel.getByRole('button', { name: '添加字段', exact: true }).click()
    await expect(propertiesPanel.locator('.embedded-form-editor__field')).toHaveCount(3)
    const addedFieldId = propertiesPanel.getByRole('textbox', { name: '字段标识' }).nth(2)
    await addedFieldId.fill('budgetAmount')
    await addedFieldId.press('Tab')
    const addedName = propertiesPanel.getByRole('textbox', { name: '字段名称' }).nth(2)
    await addedName.fill('预算金额')
    await addedName.press('Tab')
    const addedFieldType = embeddedFieldRows.nth(2).getByRole('combobox', { name: '字段类型' })
    await addedFieldType.press('Enter')
    await addedFieldType.press('ArrowDown')
    await addedFieldType.press('Enter')

    // UserTask 使用活动级循环面板写入标准循环，并通过通用属性编辑器写入受限业务元数据。
    await page.locator('[data-element-id="review"]').click()
    const loopType = propertiesPanel.getByRole('combobox', { name: '循环方式' })
    await loopType.press('Enter')
    await loopType.press('ArrowDown')
    await loopType.press('Enter')
    const loopMaximum = propertiesPanel.getByRole('textbox', { name: '最大循环次数' })
    await loopMaximum.fill('3')
    await loopMaximum.press('Tab')
    await propertiesPanel.getByText('扩展属性', { exact: true }).click()
    await propertiesPanel.getByRole('button', { name: '新增属性' }).click()
    const extensionPropertyName = propertiesPanel.getByPlaceholder('属性名')
    await extensionPropertyName.fill('business.owner')
    await extensionPropertyName.press('Tab')
    const extensionPropertyValue = propertiesPanel.getByPlaceholder('属性值')
    await extensionPropertyValue.fill('finance')
    await extensionPropertyValue.press('Tab')

    const editedEmbeddedPreview = await readDesignerPreview(page, 'XML 预览')
    expect(editedEmbeddedPreview).not.toContain('flowable:formKey=')
    expect(editedEmbeddedPreview).toContain('flowable:formProperty id="requestSummary"')
    expect(editedEmbeddedPreview).toContain('flowable:formProperty id="requestDate"')
    expect(editedEmbeddedPreview).toContain('variable="approvalDecision"')
    expect(editedEmbeddedPreview).toContain('type="enum"')
    expect(editedEmbeddedPreview).toContain('flowable:value id="APPROVE" name="同意"')
    expect(editedEmbeddedPreview).toContain('flowable:value id="REJECT" name="拒绝"')
    expect(editedEmbeddedPreview).toContain('flowable:formProperty id="budgetAmount" name="预算金额" type="long"')
    expect(editedEmbeddedPreview).toContain('<textAnnotation id="designNote">')
    expect(editedEmbeddedPreview).toContain('association id="association_review_note"')
    expect(editedEmbeddedPreview).toContain('<standardLoopCharacteristics loopMaximum="3"')
    expect(editedEmbeddedPreview).toContain('flowable:property name="business.owner" value="finance"')

    const explicitValidationPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/validate', 'POST'))
    await page.getByRole('button', { name: '服务端校验' }).click()
    const explicitValidationPayload = await expectAjaxSuccess(
      await explicitValidationPromise, '/workflow/model/validate')
    expect(explicitValidationPayload.data?.valid,
      JSON.stringify(explicitValidationPayload.data?.issues || [])).toBe(true)
    const validationDialog = page.getByRole('dialog', { name: '流程校验' })
    await expect(validationDialog).toContainText('BPMN_ELEMENT_NOT_EXECUTABLE')
    await expect(validationDialog).toContainText('标准循环')
    await validationDialog.getByRole('button', { name: '关闭此对话框' }).click()

    const validationPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/validate', 'POST'))
    const savePromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/save', 'POST'))
    await page.getByRole('button', { name: '保存', exact: true }).click()
    const validationPayload = await expectAjaxSuccess(
      await validationPromise, '/workflow/model/validate')
    expect(validationPayload.data?.valid).toBe(true)
    const savePayload = await expectAjaxSuccess(await savePromise, '/workflow/model/save')
    const savedModelId = String(savePayload.data?.modelId || '')
    expect(savedModelId).toBe(modelId)

    const reopenResponsePromise = page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/model/bpmnXml/${modelId}`, 'GET'))
    const reopenNavigationPromise = page.goto(`/workflow/model-design/${modelId}`)
    const reopenResponse = await reopenResponsePromise
    expect(reopenResponse.status(), '重开设计器必须真实读取 BPMN 接口').toBe(200)
    await reopenNavigationPromise
    const reopenedXml = await readDesignerPreview(page, 'XML 预览')
    expect(reopenedXml).toContain(`name="${editedProcessName}"`)
    expect(reopenedXml).not.toContain('flowable:formKey=')
    expect(reopenedXml).toContain('flowable:formProperty id="requestSummary"')
    expect(reopenedXml).toContain('variable="approvalDecision"')
    expect(reopenedXml).toContain('flowable:value id="APPROVE" name="同意"')
    expect(reopenedXml).toContain('flowable:value id="REJECT" name="拒绝"')
    expect(reopenedXml).toContain('flowable:formProperty id="budgetAmount" name="预算金额" type="long"')
    expect(reopenedXml).toContain('<textAnnotation id="designNote">')
    expect(reopenedXml).toContain('association id="association_review_note"')
    expect(reopenedXml).toContain('<standardLoopCharacteristics loopMaximum="3"')
    expect(reopenedXml).toContain('flowable:property name="business.owner" value="finance"')

    const rejectedDeployment = await callWorkflowApi(
      page, 'POST', '/workflow/model/deploy', {
        query: { modelId },
        expectedCode: 400
      })
    expect(rejectedDeployment.subCode).toBe('BPMN_ELEMENT_NOT_EXECUTABLE')
    const modelAfterRejectedDeploy = await callWorkflowApi(
      page, 'GET', `/workflow/model/${encodeURIComponent(modelId)}`)
    expect(modelAfterRejectedDeploy.data?.deployed,
      '标准循环部署拒绝后模型不得产生部署副作用').not.toBe(true)

    // 偏好必须由 PUT 写入数据库并在刷新后的新 Modeler 实例中恢复。
    await page.getByRole('button', { name: '设计器设置' }).click()
    const settingsDialog = page.getByRole('dialog', { name: '设计器设置' })
    await settingsDialog.getByText('深色', { exact: true }).click()
    const minimapSwitch = settingsDialog.getByRole('switch', { name: '小地图' })
    if (await minimapSwitch.isChecked()) {
      await settingsDialog.locator('.el-form-item').filter({ hasText: '小地图' })
        .locator('.el-switch').click()
    }
    const preferencePutPromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/designer/preference', 'PUT'))
    await settingsDialog.getByRole('button', { name: '保存设置' }).click()
    await expectAjaxSuccess(await preferencePutPromise, '/workflow/designer/preference')
    await page.reload()
    await expect(page.locator('.process-designer')).toHaveClass(/process-designer--dark/)
    await expect(page.locator('.djs-minimap')).not.toHaveClass(/open/)

    // Token 模拟开关同样持久化，但只改变设计器模式，不写入流程业务状态。
    const simulationButton = page.getByRole('button', { name: 'Token 流程模拟' })
    const simulationEnablePromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/designer/preference', 'PUT'))
    await simulationButton.click()
    await expectAjaxSuccess(await simulationEnablePromise, '/workflow/designer/preference')
    await expect(simulationButton).toHaveClass(/is-active/)
    const simulationDisablePromise = page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/designer/preference', 'PUT'))
    await simulationButton.click()
    await expectAjaxSuccess(await simulationDisablePromise, '/workflow/designer/preference')
    await expect(simulationButton).not.toHaveClass(/is-active/)
  } finally {
    if (designerSession && originalPreference) {
      await callWorkflowApi(
        designerSession.page, 'PUT', '/workflow/designer/preference', { data: originalPreference })
        .catch(() => undefined)
    }
    const cleanupErrors = designerSession
      ? await cleanupWorkflowResources({ designer: designerSession.page }, resources)
      : []
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    expect([...cleanupErrors, ...sessionErrors], '设计器回归必须完整清理正式数据和浏览器会话').toEqual([])
  }
})
