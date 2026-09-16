import { expect, test } from '@playwright/test'

// 真实登录、HTTP、部署与持久化测试；不会用路由 mock 替代后端。
test('浏览器保存、重载、CAS 冲突和两个 HTTP 提交只创建一个实例', async ({ page }) => {
  const username = process.env.WORKFLOW_E2E_USERNAME
  const password = process.env.WORKFLOW_E2E_PASSWORD
  expect(Boolean(username && password), '缺少登录测试条件：WORKFLOW_E2E_USERNAME / WORKFLOW_E2E_PASSWORD').toBe(true)
  await page.goto('/login?redirect=/index')
  await page.getByRole('textbox', { name: '账号' }).fill(username)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  await Promise.all([page.waitForURL(url => url.pathname !== '/login'), page.getByRole('button', { name: '登录' }).click()])
  async function api(path, method = 'GET', body, expectedCode = 200) {
    const result = await page.evaluate(async ({ path, method, body }) => {
      const cookie = document.cookie.split('; ').find(value => value.startsWith('Admin-Token='))
      const token = decodeURIComponent(cookie.slice('Admin-Token='.length))
      const response = await fetch(`/dev-api${path}`, {
        method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        ...(body ? { body: JSON.stringify(body) } : {})
      })
      return response.json()
    }, { path, method, body })
    expect(result.code, `${method} ${path}: ${result.msg}`).toBe(expectedCode)
    return result
  }
  const key = `draft_e2e_${Date.now()}`
  const category = (await api('/workflow/category/listAll')).data[0].code
  let modelId, deploymentId, draftId, instanceId
  try {
    const model = (await api('/workflow/model', 'POST', { modelKey: key, modelName: '草稿整链回归', category, formType: 2 })).data
    modelId = model.modelId
    const revision = (await api(`/workflow/model/${modelId}`)).data.revision
    await api('/workflow/model/save', 'POST', { modelId, expectedRevision: revision, bpmnXml: bpmn(key) })
    deploymentId = (await api(`/workflow/model/deploy?modelId=${modelId}`, 'POST')).data.deploymentId
    const definition = (await api(`/workflow/process/list?processKey=${key}`)).rows[0]
    await page.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${deploymentId}`)
    const title = page.getByRole('textbox', { name: '申请标题' })
    await title.fill('第一次保存')
    const createdResponse = page.waitForResponse(response => response.url().endsWith('/workflow/process/draft') && response.request().method() === 'POST')
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    const created = (await (await createdResponse).json()).data
    draftId = created.draftId
    expect(created.variables).toEqual({ requestTitle: '第一次保存' })
    expect(created.revisionNo).toBe(1)
    await page.waitForURL(`**/workflow/process-draft/${draftId}`)
    await page.reload()
    await expect(title).toHaveValue('第一次保存')
    await title.fill('重载后保存')
    const savedResponse = page.waitForResponse(response => response.url().endsWith(`/workflow/process/draft/${draftId}`) && response.request().method() === 'PUT')
    await page.getByRole('button', { name: '保存草稿', exact: true }).click()
    const saved = (await (await savedResponse).json()).data
    expect(saved.revisionNo).toBe(2)
    expect((await api(`/workflow/process/draft/${draftId}`)).data).toEqual(saved)
    await page.reload()
    await expect(title).toHaveValue('重载后保存')
    await test.info().attach('draft-restored', { body: await page.screenshot(), contentType: 'image/png' })
    const stale = await api(`/workflow/process/draft/${draftId}`, 'PUT', {
      expectedVersion: 1, variables: { requestTitle: 'stale' }, multiInstanceUserIds: {}
    }, 409)
    expect(stale.subCode).toBe('DRAFT_VERSION_CONFLICT')
    const payload = { expectedVersion: 2, variables: { requestTitle: '重载后保存' }, multiInstanceUserIds: {} }
    const [first, second] = await Promise.all([
      api(`/workflow/process/draft/${draftId}/submit`, 'POST', payload),
      api(`/workflow/process/draft/${draftId}/submit`, 'POST', payload)
    ])
    instanceId = first.data.processInstanceId
    expect(instanceId).toBeTruthy()
    expect(second.data).toEqual(first.data)
    const stored = (await api(`/workflow/process/draft/${draftId}`)).data
    expect(stored.status).toBe('SUBMITTED')
    expect(stored.revisionNo).toBe(3)
    const instances = (await api(`/workflow/process/ownList?processKey=${key}`)).rows
    expect(instances.filter(row => row.processInstanceId === instanceId)).toHaveLength(1)
  } finally {
    if (instanceId) {
      await api('/workflow/instance/terminate', 'POST', { instanceId, reason: '草稿整链回归清理' })
      await api('/workflow/process/instances/delete', 'POST', { instanceIds: [instanceId] })
    } else if (draftId) {
      const draft = (await api(`/workflow/process/draft/${draftId}`)).data
      await api(`/workflow/process/draft/${draftId}?expectedVersion=${draft.revisionNo}`, 'DELETE')
    }
    if (deploymentId) await api(`/workflow/deploy/${deploymentId}`, 'DELETE')
    if (modelId) await api(`/workflow/model/${modelId}`, 'DELETE')
  }
})

function bpmn(key) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn"
 xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
 xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="urn:draft:e2e">
 <process id="${key}" name="草稿整链回归" isExecutable="true">
  <extensionElements><flowable:properties>
   <flowable:property name="approva.startScope.ruleVersion" value="1"/><flowable:property name="approva.startScope.type" value="PUBLIC"/>
   <flowable:property name="approva.startScope.targetIds" value=""/><flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
  </flowable:properties></extensionElements>
  <startEvent id="start" name="申请"><extensionElements><flowable:formProperty id="requestTitle" name="申请标题" type="string" required="true" readable="true" writable="true"/></extensionElements></startEvent>
  <sequenceFlow id="toReview" sourceRef="start" targetRef="review"/>
  <userTask id="review" name="审批"><extensionElements><flowable:properties>
   <flowable:property name="approva.assignment.ruleVersion" value="1"/><flowable:property name="approva.assignment.type" value="STARTER"/>
   <flowable:property name="approva.assignment.targetIds" value=""/><flowable:property name="approva.assignment.formField" value=""/>
   <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
  </flowable:properties></extensionElements></userTask>
  <sequenceFlow id="toEnd" sourceRef="review" targetRef="end"/><endEvent id="end"/>
 </process>
 <bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="${key}">
  <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="100" y="100" width="36" height="36"/></bpmndi:BPMNShape>
  <bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="230" y="80" width="100" height="80"/></bpmndi:BPMNShape>
  <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="430" y="100" width="36" height="36"/></bpmndi:BPMNShape>
  <bpmndi:BPMNEdge id="toReview_di" bpmnElement="toReview"><di:waypoint x="136" y="118"/><di:waypoint x="230" y="118"/></bpmndi:BPMNEdge>
  <bpmndi:BPMNEdge id="toEnd_di" bpmnElement="toEnd"><di:waypoint x="330" y="118"/><di:waypoint x="430" y="118"/></bpmndi:BPMNEdge>
 </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`
}
