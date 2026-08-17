import fs from 'node:fs'
import { test, expect } from '@playwright/test'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成设计器用例的唯一测试资产前缀。
 * @returns {string} 适用于模型键和分类编码的 ASCII 前缀。
 */
function designerPrefix() {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-20)
  return `E2E_UI_${runId}_designer_${Date.now().toString(36)}`
}

/**
 * 构造仅用于“导入功能”测试的标准 BPMN XML。
 * @param {{processKey:string,processName:string}} input 流程键和名称。
 * @returns {string} 含稳定 DI 坐标的 BPMN 2.0 XML。
 */
function buildImportBpmn({ processKey, processName }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="https://approvaplat.local/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" />
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="review" />
    <userTask id="review" name="流程审批" />
    <sequenceFlow id="flow_review_end" sourceRef="review" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="100" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_review" bpmnElement="review"><omgdc:Bounds x="240" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="430" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_review" bpmnElement="flow_start_review"><omgdi:waypoint x="136" y="190" /><omgdi:waypoint x="240" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_review_end" bpmnElement="flow_review_end"><omgdi:waypoint x="340" y="190" /><omgdi:waypoint x="430" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 通过工具栏下载指定设计器文件并读取正文。
 * @param {import('@playwright/test').Page} page 当前模型设计器页面。
 * @param {'导出 BPMN'|'导出 XML'|'导出 SVG'} menuLabel 导出菜单项。
 * @returns {Promise<{filename:string,content:string}>} 浏览器下载文件名和 UTF-8 正文。
 */
async function downloadDesignerFile(page, menuLabel) {
  await page.getByRole('button', { name: '导出流程' }).click()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('menuitem', { name: menuLabel, exact: true }).click()
  const download = await downloadPromise
  const downloadPath = await download.path()
  if (!downloadPath) throw new Error(`${menuLabel} 下载未生成本地文件`)
  return { filename: download.suggestedFilename(), content: fs.readFileSync(downloadPath, 'utf8') }
}

/**
 * 打开设计器源码预览并返回只读文本框正文。
 * @param {import('@playwright/test').Page} page 当前模型设计器页面。
 * @param {'XML 预览'|'JSON 预览'} menuLabel 预览菜单项。
 * @returns {Promise<string>} 页面真实序列化的源码。
 */
async function readDesignerPreview(page, menuLabel) {
  await page.getByRole('button', { name: '预览流程源码' }).click()
  await page.getByRole('menuitem', { name: menuLabel, exact: true }).click()
  const dialog = page.getByRole('dialog', { name: menuLabel })
  const content = await dialog.getByRole('textbox').inputValue()
  await dialog.getByRole('button', { name: '关闭此对话框' }).click()
  return content
}

test('@full [UI-DESIGNER-001] 设计器通过UI完成导入、属性配置、导出、预览、偏好、模拟、保存和重开', async ({ browser }, testInfo) => {
  const prefix = designerPrefix()
  const assets = {
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`, modelName: `${prefix}_模型`, modelKey: `${prefix}_model`
  }
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let failed = true
  let originalPreferenceEntries
  try {
    const page = designer.page
    originalPreferenceEntries = await page.evaluate(() => Object.fromEntries(
      Object.keys(localStorage)
        .filter(key => key.startsWith('workflow:designer:preference:v1:'))
        .map(key => [key, localStorage.getItem(key)])))
    const configuration = new WorkflowConfigurationPage(page)
    await configuration.createCategory({ name: assets.categoryName, code: assets.categoryCode, remark: prefix })
    await configuration.createTextForm({ name: assets.formName, remark: prefix })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} 设计器全量回归`
    })
    await configuration.openDesigner(assets.modelKey)

    const validXml = buildImportBpmn({ processKey: assets.modelKey, processName: assets.modelName })
    await page.locator('input.process-designer__file-input').setInputFiles({
      name: `${assets.modelKey}.bpmn`, mimeType: 'application/xml', buffer: Buffer.from(validXml, 'utf8')
    })
    await expect(page.locator('[data-element-id="orphan"]')).toHaveCount(0)
    const designerPage = new WorkflowDesignerPage(page)
    await designerPage.configureStartForm(assets.formName)
    await designerPage.configureCandidateRole('流程审批人', '设计器审批')

    const bpmnDownload = await downloadDesignerFile(page, '导出 BPMN')
    expect(bpmnDownload.filename).toBe(`${assets.modelKey}.bpmn20.xml`)
    expect(bpmnDownload.content).toContain(`<process id="${assets.modelKey}"`)
    const xmlDownload = await downloadDesignerFile(page, '导出 XML')
    expect(xmlDownload.content).toContain('name="approva.assignment.type" value="CANDIDATE_GROUPS"')
    expect(xmlDownload.content).toMatch(/name="approva\.assignment\.targetIds" value="ROLE\d+"/u)
    const svgDownload = await downloadDesignerFile(page, '导出 SVG')
    expect(svgDownload.content).toMatch(/<svg[\s>]/u)
    expect(await readDesignerPreview(page, 'XML 预览')).toContain(`<process id="${assets.modelKey}"`)
    expect(JSON.parse(await readDesignerPreview(page, 'JSON 预览')).name).toBe('definitions')

    const taskNameInput = page.getByRole('textbox', { name: '元素名称' })
    const editedTaskName = `${prefix}_审批节点`
    await taskNameInput.fill(editedTaskName)
    await taskNameInput.press('Tab')
    await page.getByRole('button', { name: '撤销' }).click()
    await expect(taskNameInput).toHaveValue('设计器审批')
    await page.getByRole('button', { name: '重做' }).click()
    await expect(taskNameInput).toHaveValue(editedTaskName)
    await designerPage.validateAndSave()

    await designerPage.returnToModels()
    await configuration.openDesigner(assets.modelKey)
    const reopenedXml = await readDesignerPreview(page, 'XML 预览')
    expect(reopenedXml).toContain(editedTaskName)
    expect(reopenedXml).toContain('name="approva.assignment.type" value="CANDIDATE_GROUPS"')

    await page.getByRole('button', { name: '设计器设置' }).click()
    let settings = page.getByRole('dialog', { name: '设计器设置' })
    const checkedTheme = settings.locator('input[type="radio"]:checked')
    await expect(checkedTheme, '主题分段控件必须存在唯一选中项').toHaveCount(1)
    const originalThemeLabel = (await checkedTheme.locator('xpath=ancestor::label[1]').innerText()).trim()
    const targetThemeLabel = originalThemeLabel === '深色' ? '浅色' : '深色'
    const minimapSwitch = settings.getByRole('switch', { name: '小地图' })
    const originalMinimap = await minimapSwitch.isChecked()
    await settings.getByText(targetThemeLabel, { exact: true }).click()
    await settings.locator('.el-form-item').filter({ hasText: '小地图' }).locator('.el-switch').click()
    await settings.getByRole('button', { name: '保存设置' }).click()
    const targetTheme = targetThemeLabel === '深色' ? 'DARK' : 'LIGHT'
    await expect.poll(async () => page.evaluate(({ theme, minimap }) => Object.keys(localStorage)
      .filter(key => key.startsWith('workflow:designer:preference:v1:'))
      .find(key => {
        const value = JSON.parse(localStorage.getItem(key))
        return value.theme === theme && value.minimapEnabled === minimap
      }) || '', { theme: targetTheme, minimap: !originalMinimap })).not.toBe('')
    const currentPreferenceKey = await page.evaluate(({ theme, minimap }) => Object.keys(localStorage)
      .filter(key => key.startsWith('workflow:designer:preference:v1:'))
      .find(key => {
        const value = JSON.parse(localStorage.getItem(key))
        return value.theme === theme && value.minimapEnabled === minimap
      }), { theme: targetTheme, minimap: !originalMinimap })
    await page.reload()
    await expect(page.locator('.process-designer')).toBeVisible()
    if (targetThemeLabel === '深色') await expect(page.locator('.process-designer')).toHaveClass(/process-designer--dark/u)
    else await expect(page.locator('.process-designer')).not.toHaveClass(/process-designer--dark/u)

    const simulationButton = page.getByRole('button', { name: 'Token 流程模拟' })
    await simulationButton.click()
    await expect(simulationButton).toHaveClass(/is-active/u)
    await expect.poll(async () => page.evaluate(key =>
      JSON.parse(localStorage.getItem(key)).tokenSimulationEnabled, currentPreferenceKey))
      .toBe(true)
    await simulationButton.click()
    await expect(simulationButton).not.toHaveClass(/is-active/u)
    await expect.poll(async () => page.evaluate(key =>
      JSON.parse(localStorage.getItem(key)).tokenSimulationEnabled, currentPreferenceKey))
      .toBe(false)

    // 恢复默认只能删除当前用户键，不能清除同源浏览器内其他用户的偏好。
    const otherUserKey = 'workflow:designer:preference:v1:e2e-other-user'
    await page.evaluate(key => localStorage.setItem(key, JSON.stringify({
      schemaVersion: 1,
      theme: 'LIGHT',
      gridEnabled: true,
      minimapEnabled: true,
      tokenSimulationEnabled: false,
      propertiesCollapsed: false
    })), otherUserKey)
    await page.getByRole('button', { name: '设计器设置' }).click()
    settings = page.getByRole('dialog', { name: '设计器设置' })
    await settings.getByRole('button', { name: '恢复默认' }).click()
    await expect.poll(async () => page.evaluate(key => localStorage.getItem(key), currentPreferenceKey))
      .toBeNull()
    expect(await page.evaluate(key => localStorage.getItem(key), otherUserKey)).not.toBeNull()
    failed = false
  } finally {
    if (originalPreferenceEntries) {
      await designer.page.evaluate(entries => {
        Object.keys(localStorage)
          .filter(key => key.startsWith('workflow:designer:preference:v1:'))
          .forEach(key => localStorage.removeItem(key))
        Object.entries(entries).forEach(([key, value]) => localStorage.setItem(key, value))
      }, originalPreferenceEntries).catch(() => undefined)
    }
    await designer.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json' })
  }
})
