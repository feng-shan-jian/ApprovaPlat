import { expect, test } from '@playwright/test'

/**
 * 使用当前登录 Cookie 调用真实工作流接口，供浏览器 fixture 建立和精确清理模型。
 * @param {import('@playwright/test').Page} page 已完成登录的真实页面。
 * @param {string} path 以 /workflow 开头的后端 API 路径。
 * @param {{method?:string,body?:object}} options HTTP 方法和可选 JSON 请求体。
 * @returns {Promise<object>} code=200 的 AjaxResult；HTTP 或业务失败会终止测试。
 */
async function callWorkflowApi(page, path, options = {}) {
  return page.evaluate(async ({ apiPath, method, body }) => {
    const tokenCookie = document.cookie
      .split('; ')
      .find(item => item.startsWith('Admin-Token='))
    const token = tokenCookie ? decodeURIComponent(tokenCookie.slice('Admin-Token='.length)) : ''
    if (!token) throw new Error('浏览器登录令牌不存在')
    const response = await fetch(`/dev-api${apiPath}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body ? { 'Content-Type': 'application/json;charset=utf-8' } : {})
      },
      ...(body ? { body: JSON.stringify(body) } : {})
    })
    const result = await response.json()
    if (!response.ok || Number(result?.code) !== 200) {
      throw new Error(`工作流测试接口失败: HTTP ${response.status}, code=${result?.code}`)
    }
    return result
  }, {
    apiPath: path,
    method: options.method || 'GET',
    body: options.body
  })
}

/**
 * 生成包含正式起草字段、受控发起范围、参与者规则和固定监听器的可保存 BPMN。
 * @param {string} modelKey 本次隔离模型的稳定流程标识。
 * @param {string} processName 导入后应在画布和属性面板显示的流程名称。
 * @returns {string} 可由 bpmn-js 导入并通过服务端保存门禁的 BPMN 2.0 XML。
 */
function createImportXml(modelKey, processName) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  xmlns:flowable="http://flowable.org/bpmn"
  targetNamespace="urn:approvaplat:e2e">
  <process id="${modelKey}" name="${processName}" isExecutable="true">
    <extensionElements>
      <flowable:properties>
        <flowable:property name="approva.startScope.ruleVersion" value="1"/>
        <flowable:property name="approva.startScope.type" value="PUBLIC"/>
        <flowable:property name="approva.startScope.targetIds" value=""/>
        <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
      </flowable:properties>
    </extensionElements>
    <startEvent id="start" name="提交申请">
      <extensionElements>
        <flowable:formProperty id="requestTitle" name="申请标题" type="string"
          required="true" readable="true" writable="true"/>
      </extensionElements>
    </startEvent>
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="审批">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="approva.assignment.ruleVersion" value="1"/>
          <flowable:property name="approva.assignment.type" value="STARTER"/>
          <flowable:property name="approva.assignment.targetIds" value=""/>
          <flowable:property name="approva.assignment.formField" value=""/>
          <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
        </flowable:properties>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}"/>
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}"/>
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}"/>
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_review_end" sourceRef="review" targetRef="end"/>
    <endEvent id="end" name="结束"/>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_${modelKey}">
    <bpmndi:BPMNPlane id="BPMNPlane_${modelKey}" bpmnElement="${modelKey}">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="160" y="172" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="270" y="150" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="450" y="172" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_start_review_di" bpmnElement="flow_start_review"><di:waypoint x="196" y="190"/><di:waypoint x="270" y="190"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_review_end_di" bpmnElement="flow_review_end"><di:waypoint x="370" y="190"/><di:waypoint x="450" y="190"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 生成包含 BS-008 六类任务的历史兼容 BPMN，受控扩展和 DMN 由真实页面目录配置。
 * @param {string} modelKey 本次隔离模型的稳定流程标识。
 * @param {string} processName 模型保存与重载后应保留的流程名称。
 * @returns {string} 包含 Service、Send、Receive、BusinessRule、Manual 和 UserTask 的 BPMN XML。
 */
function createTaskCapabilityXml(modelKey, processName) {
  const taskIds = ['service_task', 'send_task', 'receive_task', 'business_rule_task', 'manual_task', 'user_task']
  const elementIds = ['start', ...taskIds, 'end']
  const flows = elementIds.slice(0, -1).map((source, index) => (
    `<sequenceFlow id="flow_${index + 1}" sourceRef="${source}" targetRef="${elementIds[index + 1]}"/>`
  )).join('\n    ')
  const shapes = elementIds.map((id, index) => {
    const x = 120 + index * 150
    const event = id === 'start' || id === 'end'
    return `<bpmndi:BPMNShape id="${id}_di" bpmnElement="${id}"><dc:Bounds x="${x}" y="172" width="${event ? 36 : 100}" height="${event ? 36 : 80}"/></bpmndi:BPMNShape>`
  }).join('\n      ')
  const edges = elementIds.slice(0, -1).map((source, index) => {
    const sourceEvent = source === 'start'
    const sourceX = 120 + index * 150 + (sourceEvent ? 36 : 100)
    const targetX = 120 + (index + 1) * 150
    const sourceY = sourceEvent ? 190 : 212
    const targetEvent = elementIds[index + 1] === 'end'
    const targetY = targetEvent ? 190 : 212
    return `<bpmndi:BPMNEdge id="flow_${index + 1}_di" bpmnElement="flow_${index + 1}"><di:waypoint x="${sourceX}" y="${sourceY}"/><di:waypoint x="${targetX}" y="${targetY}"/></bpmndi:BPMNEdge>`
  }).join('\n      ')

  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  xmlns:flowable="http://flowable.org/bpmn"
  targetNamespace="urn:approvaplat:bs008">
  <process id="${modelKey}" name="${processName}" isExecutable="true">
    <extensionElements>
      <flowable:properties>
        <flowable:property name="approva.startScope.ruleVersion" value="1"/>
        <flowable:property name="approva.startScope.type" value="PUBLIC"/>
        <flowable:property name="approva.startScope.targetIds" value=""/>
        <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
      </flowable:properties>
    </extensionElements>
    <startEvent id="start" name="提交申请">
      <extensionElements>
        <flowable:formProperty id="requestTitle" name="申请标题" type="string"
          required="true" readable="true" writable="true"/>
      </extensionElements>
    </startEvent>
    <serviceTask id="service_task" name="受控服务"/>
    <sendTask id="send_task" name="受控发送"/>
    <receiveTask id="receive_task" name="外部回调"/>
    <businessRuleTask id="business_rule_task" name="精确决策"/>
    <manualTask id="manual_task" name="历史手工记录"/>
    <userTask id="user_task" name="人工审批">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="approva.assignment.ruleVersion" value="1"/>
          <flowable:property name="approva.assignment.type" value="STARTER"/>
          <flowable:property name="approva.assignment.targetIds" value=""/>
          <flowable:property name="approva.assignment.formField" value=""/>
          <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
        </flowable:properties>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}"/>
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}"/>
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}"/>
      </extensionElements>
    </userTask>
    ${flows}
    <endEvent id="end" name="结束"/>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_${modelKey}">
    <bpmndi:BPMNPlane id="BPMNPlane_${modelKey}" bpmnElement="${modelKey}">
      ${shapes}
      ${edges}
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 生成可通过平台正式 DMN 部署接口发布的隔离决策。
 * @param {string} decisionKey 本次验收专用的稳定决策 key。
 * @returns {string} 包含一个可执行 FIRST 决策表的 DMN 1.3 XML。
 */
function createAcceptanceDmnXml(decisionKey) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
  id="definitions_${decisionKey}"
  name="BS008任务能力验收决策"
  namespace="urn:approvaplat:bs008:dmn">
  <decision id="${decisionKey}" name="BS008任务能力验收决策">
    <decisionTable id="table_${decisionKey}" hitPolicy="FIRST">
      <input id="input_${decisionKey}" label="申请金额">
        <inputExpression id="expression_${decisionKey}" typeRef="number">
          <text>amount</text>
        </inputExpression>
      </input>
      <output id="output_${decisionKey}" label="审批结果" name="approved" typeRef="boolean"/>
      <rule id="rule_${decisionKey}">
        <inputEntry id="input_entry_${decisionKey}"><text>-</text></inputEntry>
        <outputEntry id="output_entry_${decisionKey}"><text>true</text></outputEntry>
      </rule>
    </decisionTable>
  </decision>
</definitions>`
}

/**
 * 在真实 bpmn-js 画布中选择指定 BPMN 元素并等待属性面板完成回读。
 * @param {import('@playwright/test').Page} page 当前真实设计页。
 * @param {string} elementId BPMN 元素稳定标识。
 * @returns {Promise<void>} 元素不存在或面板没有同步时测试失败。
 */
async function selectDiagramElement(page, elementId) {
  // 小比例全图下 context pad 可能覆盖相邻节点；先点空白画布关闭上一节点的浮层。
  await page.locator('.process-designer__canvas').click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.djs-context-pad.open')).toHaveCount(0)
  const element = page.locator(`.djs-element[data-element-id="${elementId}"]`)
  await expect(element).toBeVisible()
  // 命中 bpmn-js 专用透明交互层，避免图标或文本子节点吞掉 SVG 选择事件。
  await element.locator('.djs-hit').first().click()
  await expect(page.locator('.designer-properties-panel__context code')).toHaveText(elementId)
}

/**
 * 从当前任务面板的 Element Plus 下拉框选择一个真实后端目录项。
 * @param {import('@playwright/test').Page} page 当前真实设计页。
 * @param {string} fieldLabel 表单项业务标签。
 * @param {string} optionLabel 服务端目录项在页面中的完整显示文本。
 * @returns {Promise<void>} 目录未加载或选项不可选择时测试失败。
 */
async function chooseTaskOption(page, fieldLabel, optionLabel) {
  const formItem = page.locator('.designer-properties-panel .el-form-item').filter({ hasText: fieldLabel }).first()
  await expect(formItem).toBeVisible()
  await formItem.locator('.el-select').click()
  const option = page.locator('.el-select-dropdown:visible .el-select-dropdown__item').filter({ hasText: optionLabel }).first()
  await expect(option).toBeVisible()
  await option.click()
}

/**
 * 通过真实登录、后端模型、文件导入和 bpmn-js 命令栈验证设计器完整保存回归路径。
 * @returns {Promise<void>} 导入、撤销、服务端校验、保存、重载或清理任一失败时测试失败。
 */
test('真实导入、编辑、撤销、保存并重载 BPMN 模型', async ({ page }) => {
  const username = String(process.env.WORKFLOW_E2E_USERNAME || '').trim()
  const password = String(process.env.WORKFLOW_E2E_PASSWORD || '')
  test.skip(!username || !password, '需要 WORKFLOW_E2E_USERNAME 和 WORKFLOW_E2E_PASSWORD')

  const unique = `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const modelKey = `bs003_designer_e2e_${unique}`
  const importName = 'BS003浏览器导入'
  const transientName = 'BS003浏览器撤销'
  const savedName = 'BS003浏览器重载'
  let modelId = ''
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto('/login?redirect=/workflow/model')
  await page.getByRole('textbox', { name: '账号' }).fill(username)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await Promise.all([
    page.waitForURL(url => url.pathname === '/workflow/model'),
    page.getByRole('button', { name: '登录' }).click()
  ])

  try {
    const categoryResult = await callWorkflowApi(page, '/workflow/category/listAll')
    const category = String(categoryResult.data?.[0]?.code || '').trim()
    expect(category, '真实环境至少需要一个启用的流程分类').not.toBe('')
    const createResult = await callWorkflowApi(page, '/workflow/model', {
      method: 'POST',
      body: {
        modelName: 'BS003设计器浏览器回归',
        modelKey,
        category,
        description: 'ProcessDesigner 领域重构真实浏览器回归',
        formType: 2
      }
    })
    modelId = String(createResult.data?.modelId || '').trim()
    expect(modelId, '真实创建接口必须返回模型主键').not.toBe('')

    await page.goto(`/workflow/model-design/${encodeURIComponent(modelId)}`)
    const nameInput = page.getByRole('textbox', { name: '元素名称' })
    await expect(nameInput).toBeVisible()
    await page.locator('input[type="file"]').setInputFiles({
      name: `${modelKey}.bpmn20.xml`,
      mimeType: 'application/xml',
      buffer: Buffer.from(createImportXml(modelKey, importName), 'utf8')
    })
    await expect(nameInput).toHaveValue(importName)

    await nameInput.fill(transientName)
    await nameInput.press('Tab')
    const undoButton = page.getByRole('button', { name: '撤销' })
    await expect(undoButton).toBeEnabled()
    await undoButton.click()
    await expect(nameInput).toHaveValue(importName)
    await expect(page.getByRole('button', { name: '重做' })).toBeEnabled()

    await nameInput.fill(savedName)
    await nameInput.press('Tab')
    const validateResponse = page.waitForResponse(response => (
      response.url().endsWith('/dev-api/workflow/model/validate') && response.request().method() === 'POST'
    ))
    const saveResponse = page.waitForResponse(response => (
      response.url().endsWith('/dev-api/workflow/model/save') && response.request().method() === 'POST'
    ))
    await page.getByRole('button', { name: '保存', exact: true }).click()
    const [validated, saved] = await Promise.all([validateResponse, saveResponse])
    expect(validated.status()).toBe(200)
    expect((await validated.json()).code).toBe(200)
    expect(saved.status()).toBe(200)
    expect((await saved.json()).code).toBe(200)
    await expect(page.getByRole('heading', { name: savedName })).toBeVisible()

    await page.reload()
    await expect(page.getByRole('heading', { name: savedName })).toBeVisible()
    await expect(page.getByRole('textbox', { name: '元素名称' })).toHaveValue(savedName)
    expect(pageErrors).toEqual([])
  } finally {
    if (modelId) {
      await callWorkflowApi(page, `/workflow/model/${encodeURIComponent(modelId)}`, { method: 'DELETE' })
    }
  }
})

/**
 * 使用真实账号、正式扩展和 DMN 目录、后端保存及数据库模型资源验收 BS-008 任务能力闭环。
 * @returns {Promise<void>} 入口隐藏、面板映射、命令栈、服务端校验、保存重载或清理任一失败时测试失败。
 */
test('BS-008 六类任务能力、历史兼容和四类业务面板真实闭环', async ({ page }) => {
  test.setTimeout(120_000)
  const username = String(process.env.WORKFLOW_E2E_USERNAME || '').trim()
  const password = String(process.env.WORKFLOW_E2E_PASSWORD || '')
  test.skip(!username || !password, '需要 WORKFLOW_E2E_USERNAME 和 WORKFLOW_E2E_PASSWORD')

  const unique = `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  const modelKey = `bs008_task_capability_${unique}`
  const decisionKey = `bs008_decision_${unique}`
  const processName = 'BS008任务能力真实验收'
  let modelId = ''
  let dmnDeploymentId = ''
  const pageErrors = []
  page.on('pageerror', error => pageErrors.push(error.message))

  await page.goto('/login?redirect=/workflow/model')
  await page.getByRole('textbox', { name: '账号' }).fill(username)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await Promise.all([
    page.waitForURL(url => url.pathname === '/workflow/model'),
    page.getByRole('button', { name: '登录' }).click()
  ])

  try {
    const [categoryResult, celResult, dmnResult] = await Promise.all([
      callWorkflowApi(page, '/workflow/category/listAll'),
      callWorkflowApi(page, '/workflow/extension/options/cel'),
      callWorkflowApi(page, '/workflow/dmn/options')
    ])
    const category = String(categoryResult.data?.[0]?.code || '').trim()
    const celOption = Array.isArray(celResult.data) ? celResult.data[0] : null
    let dmnOption = Array.isArray(dmnResult.data) ? dmnResult.data[0] : null
    expect(category, '真实环境至少需要一个启用的流程分类').not.toBe('')
    expect(celOption, '真实环境至少需要一个已启用 CEL 扩展版本').toBeTruthy()

    // 空目录环境通过同一正式管理 API 建立隔离来源版本，避免伪造前端选项或绕过服务端目录。
    if (!dmnOption) {
      const deploymentResult = await callWorkflowApi(page, '/workflow/dmn', {
        method: 'POST',
        body: {
          resourceName: `${decisionKey}.dmn`,
          category: 'BS008_E2E',
          dmnXml: createAcceptanceDmnXml(decisionKey)
        }
      })
      dmnDeploymentId = String(deploymentResult.data?.deploymentId || '').trim()
      expect(dmnDeploymentId, '正式 DMN 部署接口必须返回部署主键').not.toBe('')
      const refreshedDmnResult = await callWorkflowApi(page, '/workflow/dmn/options')
      dmnOption = Array.isArray(refreshedDmnResult.data)
        ? refreshedDmnResult.data.find(option => option.deploymentId === dmnDeploymentId)
        : null
    }
    expect(dmnOption, '真实环境必须返回本次可引用的精确 DMN 来源版本').toBeTruthy()
    const celLabel = `${celOption.extensionName} · ${celOption.extensionType} · v${celOption.versionNo}`
    const dmnLabel = `${dmnOption.decisionName || dmnOption.decisionKey} · ${dmnOption.decisionKey} · v${dmnOption.version}`

    const createResult = await callWorkflowApi(page, '/workflow/model', {
      method: 'POST',
      body: {
        modelName: 'BS008任务能力浏览器验收',
        modelKey,
        category,
        description: '任务能力入口、面板、保存和重载真实闭环',
        formType: 2
      }
    })
    modelId = String(createResult.data?.modelId || '').trim()
    expect(modelId, '真实创建接口必须返回模型主键').not.toBe('')

    await page.goto(`/workflow/model-design/${encodeURIComponent(modelId)}`)
    await expect(page.getByRole('textbox', { name: '元素名称' })).toBeVisible()
    await page.locator('input[type="file"]').setInputFiles({
      name: `${modelKey}.bpmn20.xml`,
      mimeType: 'application/xml',
      buffer: Buffer.from(createTaskCapabilityXml(modelKey, processName), 'utf8')
    })
    await expect(page.getByRole('textbox', { name: '元素名称' })).toHaveValue(processName)

    // 高级创建目录明确暴露四类任务且完全隐藏 ManualTask。
    const advancedPaletteButton = page.getByRole('button', { name: '高级流程元素' })
    await advancedPaletteButton.click()
    await expect(page.getByRole('menuitem', { name: '服务任务' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '发送任务' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '接收任务' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '业务规则任务' })).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '手工任务' })).toHaveCount(0)
    await advancedPaletteButton.click()
    await expect(page.locator('.advanced-element-palette__popover')).toBeHidden()

    await selectDiagramElement(page, 'manual_task')
    await expect(page.getByText('仅为历史 BPMN 兼容保留；该元素不会生成平台待办')).toBeVisible()

    await selectDiagramElement(page, 'service_task')
    await expect(page.getByText('进入节点时执行服务端正式扩展目录中的受控处理器')).toBeVisible()
    await chooseTaskOption(page, '受控处理器', celLabel)
    await expect(page.locator('.cel-expression-editor')).toBeVisible()

    // bpmn-js 默认转换菜单保留其他类型，但 ManualTask 目标必须被能力过滤器删除。
    const replaceButton = page.locator('.djs-context-pad .bpmn-icon-screw-wrench')
    await expect(replaceButton).toBeVisible()
    await replaceButton.click()
    await expect(page.locator('.djs-popup [data-id="replace-with-manual-task"]')).toHaveCount(0)
    await expect(page.locator('.djs-popup [data-id="replace-with-user-task"]')).toBeVisible()
    await page.locator('.process-designer__canvas').click({ position: { x: 10, y: 10 } })
    await expect(page.locator('.djs-popup')).toBeHidden()

    await selectDiagramElement(page, 'send_task')
    await expect(page.getByText('连接 MessageFlow 时由后端强制核验事务 outbox 约束')).toBeVisible()
    await expect(page.getByText('设计器不根据连线猜测能力')).toBeVisible()
    await chooseTaskOption(page, '受控处理器', celLabel)
    const undoButton = page.getByRole('button', { name: '撤销' })
    const redoButton = page.getByRole('button', { name: '重做' })
    await expect(undoButton).toBeEnabled()
    await undoButton.click()
    await expect(redoButton).toBeEnabled()
    await redoButton.click()

    await selectDiagramElement(page, 'receive_task')
    await expect(page.getByText('POST /workflow/runtime-event/receive')).toBeVisible()
    await expect(page.locator('.task-runtime-contract code').filter({ hasText: 'receive_task' })).toBeVisible()
    await expect(page.getByText('processInstanceId')).toBeVisible()
    await expect(page.getByText('X-Integration-Token')).toBeVisible()

    await selectDiagramElement(page, 'business_rule_task')
    await expect(page.getByText('执行作者选择的精确 DMN 来源版本')).toBeVisible()
    await chooseTaskOption(page, 'DMN 决策版本', dmnLabel)

    // UserTask 的既有表单、审批人、会签/或签、SLA 和自动抄送入口保持可见。
    await selectDiagramElement(page, 'user_task')
    await expect(page.getByText('表单来源')).toBeVisible()
    await expect(page.getByRole('heading', { name: '审批人设置' })).toBeVisible()
    const approvalPanel = page.locator('.user-task-approval')
    await expect(approvalPanel.getByText('普通审批', { exact: true }).first()).toBeVisible()
    await expect(approvalPanel.getByText('会签', { exact: true })).toBeVisible()
    await expect(approvalPanel.getByText('或签', { exact: true })).toBeVisible()
    await expect(page.getByText('审批 SLA')).toBeVisible()
    await expect(page.getByText('自动抄送', { exact: true })).toBeVisible()

    const validateResponse = page.waitForResponse(response => (
      response.url().endsWith('/dev-api/workflow/model/validate') && response.request().method() === 'POST'
    ))
    const saveResponse = page.waitForResponse(response => (
      response.url().endsWith('/dev-api/workflow/model/save') && response.request().method() === 'POST'
    ))
    await page.getByRole('button', { name: '保存', exact: true }).click()
    const [validated, saved] = await Promise.all([validateResponse, saveResponse])
    expect(validated.status()).toBe(200)
    expect((await validated.json()).code).toBe(200)
    expect(saved.status()).toBe(200)
    const savedBody = await saved.json()
    expect(savedBody.code).toBe(200)
    modelId = String(savedBody.data?.modelId || modelId)

    await page.reload()
    await expect(page.getByRole('heading', { name: processName })).toBeVisible()
    await selectDiagramElement(page, 'manual_task')
    await expect(page.getByText('不会生成平台待办')).toBeVisible()
    await selectDiagramElement(page, 'receive_task')
    await expect(page.getByText('POST /workflow/runtime-event/receive')).toBeVisible()

    // 从后端重新读取模型资源，证明配置与历史 ManualTask 已进入真实持久化并可重载。
    const storedResult = await callWorkflowApi(page, `/workflow/model/bpmnXml/${encodeURIComponent(modelId)}`)
    const storedXml = String(storedResult.data || '')
    expect(storedXml).toContain('<manualTask id="manual_task"')
    expect(storedXml).toContain('<receiveTask id="receive_task"')
    expect(storedXml).toContain(`flowable:rules="${dmnOption.decisionId}"`)
    expect(storedXml.match(/name="approvaExtensionKey"/g) || []).toHaveLength(2)
    expect(storedXml.match(/delegateExpression="\${userTaskListener}"/g) || []).toHaveLength(3)
    expect(pageErrors).toEqual([])
  } finally {
    if (modelId) {
      await callWorkflowApi(page, `/workflow/model/${encodeURIComponent(modelId)}`, { method: 'DELETE' })
    }
    if (dmnDeploymentId) {
      await callWorkflowApi(page, `/workflow/dmn/${encodeURIComponent(dmnDeploymentId)}`, { method: 'DELETE' })
    }
  }
})
