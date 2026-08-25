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
