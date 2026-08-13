import { test, expect } from '@playwright/test'
import { DOMParser } from '@xmldom/xmldom'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成 Participant 真实 UI 探针使用的唯一测试资产前缀。
 * @param {import('@playwright/test').TestInfo} testInfo 当前 Playwright 用例信息。
 * @returns {string} 以 `E2E_UI_` 开头且可用于正式模型键的唯一前缀。
 */
function participantProbePrefix(testInfo) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-20)
  return `E2E_UI_${runId}_collab_probe_${testInfo.workerIndex}_${Date.now().toString(36)}`
}

/**
 * 从作者 BPMN 中提取 Participant 的非敏感结构证据。
 * @param {string} xml 设计器通过可见 XML 预览提供的作者 BPMN。
 * @returns {Array<{id:string,name:string,processRef:string}>} Participant 标识、名称和流程引用摘要。
 */
function participantEvidence(xml) {
  const document = new DOMParser().parseFromString(xml, 'application/xml')
  return [...document.getElementsByTagNameNS('*', 'participant')].map(participant => ({
    id: participant.getAttribute('id') || '',
    name: participant.getAttribute('name') || '',
    processRef: participant.getAttribute('processRef') || ''
  }))
}

test('@full [UI-COLLAB-000] Participant 通过真实画布创建并稳定保存流程引用', async ({ browser }, testInfo) => {
  const prefix = participantProbePrefix(testInfo)
  const assets = {
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_协作探针`,
    modelKey: `${prefix}_model`,
    participantId: 'sourcePool',
    participantName: `${prefix}_发送池`,
    participantProcessKey: `${prefix}_source_process`
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designerSession = await openRoleSession(browser, 'workflow_designer', testInfo)
  const pageErrors = []
  designerSession.page.on('pageerror', error => pageErrors.push(String(error?.message || error).slice(0, 500)))
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designerSession.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: `${prefix} Participant UI 探针`
    })
    await configuration.createTextForm({ name: assets.formName, remark: `${prefix} Participant UI 探针` })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} Participant UI 探针`
    })
    await configuration.openDesigner(assets.modelKey)

    const designer = new WorkflowDesignerPage(designerSession.page)
    const createdParticipantId = await designer.createAdvancedElement({
      paletteLabel: '池 / 参与者',
      sourceElementId: 'review',
      stableElementId: assets.participantId,
      elementName: assets.participantName,
      offsetX: 0,
      offsetY: 250,
      expectedLocalName: 'participant'
    })
    expect(createdParticipantId).toBe(assets.participantId)

    const processRefInput = designer.properties.getByLabel('绑定流程定义 key')
    await expect(processRefInput, 'Participant 必须提供唯一流程引用输入框').toHaveCount(1)
    const initialVisibleValue = await processRefInput.inputValue()
    const initialParticipantState = participantEvidence(await designer.readDesignerXml())
    await processRefInput.fill(assets.participantProcessKey)
    await processRefInput.press('Tab')
    await expect(processRefInput, 'Participant 流程引用必须在真实失焦提交后稳定回显')
      .toHaveValue(assets.participantProcessKey)
    const submittedVisibleValue = await processRefInput.inputValue()

    const authorXml = await designer.readDesignerXml()
    const beforeSave = participantEvidence(authorXml)
    await testInfo.attach('participant-author-evidence.json', {
      body: Buffer.from(JSON.stringify({
        initialVisibleValue,
        initialParticipantState,
        submittedVisibleValue,
        beforeSave,
        pageErrors
      }, null, 2)),
      contentType: 'application/json'
    })
    expect(beforeSave).toEqual([{
      id: assets.participantId,
      name: assets.participantName,
      processRef: assets.participantProcessKey
    }])
    expect(pageErrors, 'Participant 创建和流程引用提交不得产生页面 JavaScript 错误').toEqual([])

    await designer.validateAndSave()
    await designer.returnToModels()
    await configuration.openDesigner(assets.modelKey)
    const reopenedDesigner = new WorkflowDesignerPage(designerSession.page)
    await reopenedDesigner.selectCanvasShape(assets.participantId)
    await expect(reopenedDesigner.properties.getByLabel('绑定流程定义 key'),
      '保存重开后 Participant 流程引用必须保持字符串值')
      .toHaveValue(assets.participantProcessKey)
    expect(participantEvidence(await reopenedDesigner.readDesignerXml())).toEqual(beforeSave)
    expect(pageErrors, 'Participant 保存重开不得产生页面 JavaScript 错误').toEqual([])
    failed = false
  } finally {
    await designerSession.close(failed)
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({ ...assets, pageErrors }, null, 2)), contentType: 'application/json'
    })
  }
})

const participantBlocked = {
  annotation: {
    type: 'blocked',
    description: 'DEF-UI-009 阻断：Participant 无法通过 UI 保存合法 processRef，多池运行前置不可建立'
  }
}

test.skip('@full [UI-COLLAB-001] 多池协作按通道序号顺序投递并消费', participantBlocked,
  async () => {})

test.skip('@full [UI-COLLAB-002] 多池协作重复请求保持发送与接收幂等', participantBlocked,
  async () => {})

test.skip('@full [UI-COLLAB-003] 多池协作失败重试进入死信并由UI补偿', participantBlocked,
  async () => {})

test.skip('@full [UI-COLLAB-004] 多池协作源实例取消后停止后续投递', participantBlocked,
  async () => {})
