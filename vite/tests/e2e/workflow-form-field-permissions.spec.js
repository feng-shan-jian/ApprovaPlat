import { test, expect } from './fixtures/workflow.js'
import { randomUUID } from 'node:crypto'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createWorkflowCategory,
  findAssignedWorkflowTask,
  findCompletedWorkflowTask,
  findStartableWorkflowDefinition,
  findWorkflowUserOption,
  getWorkflowDetail,
  openWorkflowRoleSession
} from './support/workflow-fixture.js'

/**
 * 创建包含标量和附件字段的正式表单，供节点权限、类型和生命周期共同验收。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {string} formName 本轮唯一表单名称。
 * @param {{formId?: string}} resources finally 阶段使用的正式资源登记簿。
 * @returns {Promise<{formId:string,content:string}>} 正式表单主键和原始模板 JSON。
 */
async function createPermissionForm(page, formName, resources) {
  const content = JSON.stringify({
    fields: [
      {
        type: 'text', placeholder: '请输入申请主题', clearable: true,
        __config__: { label: '申请主题', tag: 'el-input', span: 24, required: false, regList: [], layout: 'colFormItem' },
        __vModel__: 'requestTitle'
      },
      {
        min: 0, max: 1000000, precision: 2, step: 1,
        __config__: { label: '申请金额', tag: 'el-input-number', span: 24, required: false, regList: [], layout: 'colFormItem' },
        __vModel__: 'amount'
      },
      {
        type: 'textarea', rows: 3, placeholder: '请输入内部说明', maxlength: 300,
        __config__: { label: '内部说明', tag: 'el-input', span: 24, required: false, regList: [], layout: 'colFormItem' },
        __vModel__: 'internalNote'
      },
      {
        type: 'text', placeholder: '只读编码',
        __config__: { label: '只读编码', tag: 'el-input', span: 24, required: false, regList: [], layout: 'colFormItem' },
        __vModel__: 'readonlyCode'
      },
      {
        accept: '.txt', limit: 1, disabled: false, fileSize: 1, sizeUnit: 'MB',
        __config__: { label: '证明附件', tag: 'el-upload', span: 24, required: false, defaultValue: [], regList: [], layout: 'colFormItem' },
        __vModel__: 'proofFiles'
      }
    ],
    size: 'default', labelPosition: 'right', labelWidth: 100,
    gutter: 15, disabled: false, span: 24, formBtns: true
  })
  const created = await callWorkflowApi(page, 'POST', '/workflow/form', {
    data: { formName, content, remark: '节点字段权限真实浏览器验收' }
  })
  const formId = String(created.data?.formId || '')
  if (formId) resources.formId = formId
  expect(formId, '字段权限表单创建必须返回正式主键').not.toBe('')
  return { formId, content }
}

/**
 * 将四态业务权限转换为受控 Flowable FormProperty XML。
 * @param {string} id 权限描述稳定主键。
 * @param {string|undefined} variable 模板字段变量；批量默认策略为空。
 * @param {'HIDDEN'|'READONLY'|'EDITABLE'|'REQUIRED'} mode 节点字段权限模式。
 * @returns {string} 可被部署编译器严格解析的 XML 片段。
 */
function permissionProperty(id, variable, mode) {
  const flags = {
    HIDDEN: ['false', 'false', 'false'],
    READONLY: ['true', 'false', 'false'],
    EDITABLE: ['true', 'true', 'false'],
    REQUIRED: ['true', 'true', 'true']
  }[mode]
  if (!flags) throw new Error(`未知字段权限：${mode}`)
  const variableAttribute = variable ? ` variable="${variable}"` : ''
  return `<flowable:formProperty id="${id}" type="string"${variableAttribute} readable="${flags[0]}" writable="${flags[1]}" required="${flags[2]}" />`
}

/**
 * 为一个节点生成批量默认策略及完整逐字段权限，确保模板字段目录没有隐式缺口。
 * @param {Record<string, 'HIDDEN'|'READONLY'|'EDITABLE'|'REQUIRED'>} permissions 按变量索引的权限映射。
 * @param {'HIDDEN'|'READONLY'|'EDITABLE'|'REQUIRED'} defaultMode 模板后续新增字段采用的默认策略。
 * @returns {string} 节点 extensionElements 内的权限描述 XML。
 */
function permissionProperties(permissions, defaultMode = 'EDITABLE') {
  return [
    permissionProperty('approva_permission_default', undefined, defaultMode),
    ...Object.entries(permissions).map(([variable, mode], index) => (
      permissionProperty(`approva_permission_field_${index + 1}`, variable, mode)
    ))
  ].join('\n        ')
}

/**
 * 生成串行审批 BPMN，开始节点和两个 UserTask 都绑定同一正式表单但使用不同权限快照。
 * @param {{processKey:string,processName:string,formId:string,approverUserId:string,adminUserId:string}} input 流程和办理人正式主键。
 * @returns {string} 可保存、重开和部署的完整 BPMN XML。
 */
function buildSequentialPermissionBpmn(input) {
  const allFields = ['requestTitle', 'amount', 'internalNote', 'readonlyCode', 'proofFiles']
  const start = Object.fromEntries(allFields.map(variable => [variable, 'EDITABLE']))
  Object.assign(start, { requestTitle: 'REQUIRED', amount: 'HIDDEN', internalNote: 'HIDDEN', readonlyCode: 'READONLY' })
  const reviewA = {
    requestTitle: 'READONLY', amount: 'REQUIRED', internalNote: 'EDITABLE',
    readonlyCode: 'HIDDEN', proofFiles: 'READONLY'
  }
  const reviewB = {
    requestTitle: 'HIDDEN', amount: 'READONLY', internalNote: 'READONLY',
    readonlyCode: 'HIDDEN', proofFiles: 'EDITABLE'
  }
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://ruoyi.example/workflow">
  <process id="${input.processKey}" name="${input.processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${input.formId}"><extensionElements>
        ${permissionProperties(start)}
    </extensionElements></startEvent>
    <sequenceFlow id="flow_start_a" sourceRef="start" targetRef="reviewA" />
    <userTask id="reviewA" name="资料审核" flowable:formKey="key_${input.formId}" flowable:assignee="${input.approverUserId}"><extensionElements>
        ${permissionProperties(reviewA)}
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
    </extensionElements></userTask>
    <sequenceFlow id="flow_a_b" sourceRef="reviewA" targetRef="reviewB" />
    <userTask id="reviewB" name="财务复核" flowable:formKey="key_${input.formId}" flowable:assignee="${input.adminUserId}"><extensionElements>
        ${permissionProperties(reviewB)}
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
    </extensionElements></userTask>
    <sequenceFlow id="flow_b_end" sourceRef="reviewB" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${input.processKey}"><bpmndi:BPMNPlane id="plane_${input.processKey}" bpmnElement="${input.processKey}">
    <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="80" y="172" width="36" height="36" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_a" bpmnElement="reviewA"><omgdc:Bounds x="200" y="150" width="100" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_b" bpmnElement="reviewB"><omgdc:Bounds x="390" y="150" width="100" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="590" y="172" width="36" height="36" /></bpmndi:BPMNShape>
    <bpmndi:BPMNEdge id="edge_start_a" bpmnElement="flow_start_a"><omgdi:waypoint x="116" y="190" /><omgdi:waypoint x="200" y="190" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edge_a_b" bpmnElement="flow_a_b"><omgdi:waypoint x="300" y="190" /><omgdi:waypoint x="390" y="190" /></bpmndi:BPMNEdge>
    <bpmndi:BPMNEdge id="edge_b_end" bpmnElement="flow_b_end"><omgdi:waypoint x="490" y="190" /><omgdi:waypoint x="590" y="190" /></bpmndi:BPMNEdge>
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 在 Element Plus 下拉框中按无障碍名称选择指定权限。
 * @param {import('@playwright/test').Page} page 设计器页面。
 * @param {string} ariaLabel 批量或字段权限选择器名称。
 * @param {string} optionLabel 隐藏、只读、可编辑或必填。
 * @returns {Promise<void>} 选择事件已提交到 bpmn-js 命令栈后结束。
 */
async function selectPermission(page, ariaLabel, optionLabel) {
  const selector = page.getByRole('combobox', { name: ariaLabel })
  // Element Plus 2.13 的展示层会覆盖 readonly input，必须点击同一选择器的可交互 wrapper。
  await selector.locator('xpath=ancestor::div[contains(@class,"el-select__wrapper")][1]').click()
  await expect(page.locator('.el-select-dropdown:visible').last()).toBeVisible()
  const optionIndex = ['隐藏', '只读', '可编辑', '必填'].indexOf(optionLabel)
  if (optionIndex < 0) throw new Error(`未知字段权限选项：${optionLabel}`)
  // 使用组件公开键盘契约，避免属性面板底部的传送下拉项超出物理视口。
  await selector.press('Home')
  for (let index = 0; index < optionIndex; index += 1) await selector.press('ArrowDown')
  await selector.press('Enter')
}

/**
 * 打开对象授权后的任务详情，并等待当前节点表单完成真实加载。
 * @param {import('@playwright/test').Page} page 当前办理人页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} taskId 当前任务主键。
 * @returns {Promise<void>} 办理表单标签和实例主键均可见后结束。
 */
async function openTaskDetail(page, processInstanceId, taskId) {
  await page.goto(`/workflow/process-detail/${encodeURIComponent(processInstanceId)}?taskId=${encodeURIComponent(taskId)}`)
  await expect(page.getByText(processInstanceId, { exact: true }).first()).toBeVisible()
  await page.getByRole('tab', { name: '办理表单' }).click()
}

/**
 * 定位当前活动节点的办理表单，避免同页历史只读快照中的同名字段干扰操作。
 * @param {import('@playwright/test').Page} page 已打开流程详情的办理人页面。
 * @returns {import('@playwright/test').Locator} 当前办理表单唯一可见容器。
 */
function currentTaskFormPanel(page) {
  return page.getByRole('tabpanel', { name: '办理表单' })
}

/**
 * 校验当前 E2E 已显式绑定可销毁的字段权限独占 schema。
 * @returns {string} 由验收编排器负责在后端停止后销毁的 schema 标识。
 */
function requireDisposableFieldPermissionSchema() {
  const schema = String(process.env.FLOWABLE_E2E_DISPOSABLE_SCHEMA || '').trim()
  if (!/^ry_vue_codex_fieldperm_[a-z0-9_]+$/.test(schema)) {
    throw new Error('绑定附件验收只能运行在 ry_vue_codex_fieldperm_* 独占可销毁 schema')
  }
  return schema
}

/**
 * 生成两个并行 UserTask 分别编辑不同字段的 BPMN，用于验证并发补丁不互相覆盖。
 * @param {{processKey:string,processName:string,formId:string,approverUserId:string,adminUserId:string}} input 流程、表单和真实办理人主键。
 * @returns {string} 可由正式设计器保存和部署的完整 BPMN XML。
 */
function buildParallelPermissionBpmn(input) {
  const start = permissionProperties({
    requestTitle: 'REQUIRED', amount: 'EDITABLE', internalNote: 'EDITABLE',
    readonlyCode: 'HIDDEN', proofFiles: 'HIDDEN'
  })
  const amountTask = permissionProperties({
    requestTitle: 'READONLY', amount: 'EDITABLE', internalNote: 'READONLY',
    readonlyCode: 'HIDDEN', proofFiles: 'HIDDEN'
  })
  const noteTask = permissionProperties({
    requestTitle: 'READONLY', amount: 'READONLY', internalNote: 'EDITABLE',
    readonlyCode: 'HIDDEN', proofFiles: 'HIDDEN'
  })
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://ruoyi.example/workflow">
  <process id="${input.processKey}" name="${input.processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${input.formId}"><extensionElements>${start}</extensionElements></startEvent>
    <sequenceFlow id="flow_split" sourceRef="start" targetRef="split" />
    <parallelGateway id="split" name="并行编辑" />
    <sequenceFlow id="flow_amount" sourceRef="split" targetRef="amountTask" />
    <sequenceFlow id="flow_note" sourceRef="split" targetRef="noteTask" />
    <userTask id="amountTask" name="金额并行编辑" flowable:formKey="key_${input.formId}" flowable:assignee="${input.approverUserId}"><extensionElements>${amountTask}</extensionElements></userTask>
    <userTask id="noteTask" name="说明并行编辑" flowable:formKey="key_${input.formId}" flowable:assignee="${input.adminUserId}"><extensionElements>${noteTask}</extensionElements></userTask>
    <sequenceFlow id="flow_amount_join" sourceRef="amountTask" targetRef="join" />
    <sequenceFlow id="flow_note_join" sourceRef="noteTask" targetRef="join" />
    <parallelGateway id="join" name="汇合" />
    <sequenceFlow id="flow_end" sourceRef="join" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${input.processKey}"><bpmndi:BPMNPlane id="plane_${input.processKey}" bpmnElement="${input.processKey}">
    <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="60" y="172" width="36" height="36" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_split" bpmnElement="split"><omgdc:Bounds x="150" y="165" width="50" height="50" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_amount" bpmnElement="amountTask"><omgdc:Bounds x="260" y="90" width="100" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_note" bpmnElement="noteTask"><omgdc:Bounds x="260" y="230" width="100" height="80" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_join" bpmnElement="join"><omgdc:Bounds x="430" y="165" width="50" height="50" /></bpmndi:BPMNShape>
    <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="550" y="172" width="36" height="36" /></bpmndi:BPMNShape>
  </bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
</definitions>`
}

test('节点字段权限通过真实设计器、部署快照、审批、退回和附件形成一致性闭环', async ({ browser }) => {
  test.setTimeout(600_000)
  requireDisposableFieldPermissionSchema()
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  const processKey = `field_permission_${suffix}`
  const processName = `字段权限验收_${suffix}`
  const formName = `字段权限表单_${suffix}`
  const resources = {
    attachmentIds: [], processInstanceIds: [], deploymentIds: [], modelIds: [],
    formId: '', categoryId: ''
  }
  const sessions = []
  const pages = {}
  let primaryError = null

  try {
    for (const roleKey of ['workflow_designer', 'workflow_starter', 'workflow_approver', 'workflow_admin']) {
      const session = await openWorkflowRoleSession(browser, roleKey)
      sessions.push(session)
      pages[roleKey] = session.page
    }
    const designer = pages.workflow_designer
    const starter = pages.workflow_starter
    const approverPage = pages.workflow_approver
    const adminPage = pages.workflow_admin
    const approver = await findWorkflowUserOption(designer, 'workflow_approver')
    const admin = await findWorkflowUserOption(designer, 'workflow_admin')
    expect(approver).toBeTruthy()
    expect(admin).toBeTruthy()

    const categoryCode = `field_permission_${suffix}`
    await createWorkflowCategory(designer, `字段权限分类_${suffix}`, categoryCode, resources)
    const form = await createPermissionForm(designer, formName, resources)
    const created = await callWorkflowApi(designer, 'POST', '/workflow/model', {
      data: {
        modelName: processName, modelKey: processKey, category: categoryCode,
        description: '节点字段权限真实浏览器验收', formType: 0, formId: Number(form.formId)
      }
    })
    const modelId = String(created.data?.modelId || '')
    expect(modelId).not.toBe('')
    resources.modelIds.push(modelId)

    // 设计器真实导入后逐节点回读，并实际执行一次批量默认和逐字段配置。
    await designer.goto(`/workflow/model-design/${encodeURIComponent(modelId)}`)
    const bpmnXml = buildSequentialPermissionBpmn({
      processKey, processName, formId: form.formId,
      approverUserId: String(approver.value), adminUserId: String(admin.value)
    })
    await designer.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}.bpmn`, mimeType: 'application/xml', buffer: Buffer.from(bpmnXml, 'utf8')
    })
    await designer.locator('.djs-element[data-element-id="start"]').click()
    await expect(designer.getByRole('region', { name: '节点字段权限' })).toContainText('5 个字段')
    await selectPermission(designer, '批量默认字段权限', '可编辑')
    await designer.getByRole('region', { name: '节点字段权限' }).getByRole('button', { name: '应用' }).click()
    await selectPermission(designer, '申请主题字段权限', '必填')
    await selectPermission(designer, '申请金额字段权限', '隐藏')
    await selectPermission(designer, '内部说明字段权限', '隐藏')
    await selectPermission(designer, '只读编码字段权限', '只读')

    for (const [nodeId, expectedField, expectedMode] of [
      ['reviewA', '申请金额字段权限', '必填'],
      ['reviewB', '申请主题字段权限', '隐藏']
    ]) {
      await designer.locator(`.djs-element[data-element-id="${nodeId}"]`).click()
      await expect(designer.getByRole('combobox', { name: expectedField })).toBeVisible()
      await selectPermission(designer, expectedField, expectedMode)
    }
    const savePromise = designer.waitForResponse(response => matchesEndpoint(response, '/workflow/model/save', 'POST'))
    await designer.getByRole('button', { name: '保存', exact: true }).click()
    await expectAjaxSuccess(await savePromise, '/workflow/model/save')

    // 重开必须从正式模型 XML 恢复权限，而不是依赖页面内存或本地草稿。
    await designer.goto(`/workflow/model-design/${encodeURIComponent(modelId)}`)
    await designer.locator('.djs-element[data-element-id="reviewA"]').click()
    await expect(designer.getByRole('combobox', { name: '申请金额字段权限' })).toBeVisible()
    const reopenedXml = (await callWorkflowApi(
      designer, 'GET', `/workflow/model/bpmnXml/${encodeURIComponent(modelId)}`)).data
    expect(reopenedXml).toContain('approva_permission_default')
    expect(reopenedXml).toContain('approva_permission_field_')

    const deployed = await callWorkflowApi(designer, 'POST', '/workflow/model/deploy', {
      query: { modelId }
    })
    const deploymentId = String(deployed.data?.deploymentId || '')
    expect(deploymentId).not.toBe('')
    resources.deploymentIds.push(deploymentId)
    const definition = await findStartableWorkflowDefinition(starter, processKey)

    // 页面导航前先用同一发起人会话读取正式接口，明确区分部署快照缺失与页面渲染失败。
    const startFormSnapshot = await callWorkflowApi(starter, 'GET', '/workflow/process/getProcessForm', {
      query: { definitionId: definition.definitionId, deployId: definition.deploymentId }
    })
    expect(startFormSnapshot.data).toMatchObject({ formName, nodeKey: 'start' })
    expect(startFormSnapshot.data?.content).toContain('requestTitle')

    // 发起页按开始节点快照隐藏字段、锁定只读字段，并绑定 TEMP 附件后正式提交。
    const startFormResponse = starter.waitForResponse(response => matchesEndpoint(
      response, '/workflow/process/getProcessForm', 'GET'))
    await starter.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
    await expectAjaxSuccess(await startFormResponse, '/workflow/process/getProcessForm')
    await expect(starter.getByRole('heading', { name: formName })).toBeVisible()
    await expect(starter.getByPlaceholder('请输入内部说明')).toHaveCount(0)
    await expect(starter.getByRole('spinbutton')).toHaveCount(0)
    await expect(starter.getByPlaceholder('只读编码')).toBeDisabled()
    await starter.getByPlaceholder('可选').fill(`BUS-${suffix}`)
    await starter.getByPlaceholder('请输入申请主题').fill(`字段权限申请-${suffix}`)
    const fileInput = starter.locator('.workflow-attachment-upload input[type="file"]')
    const uploadPromise = starter.waitForResponse(response => matchesEndpoint(response, '/workflow/attachment', 'POST'))
    await fileInput.setInputFiles({ name: `permission-${suffix}.txt`, mimeType: 'text/plain', buffer: Buffer.from('field permission attachment', 'utf8') })
    const upload = await expectAjaxSuccess(await uploadPromise, '/workflow/attachment')
    const attachmentId = String(upload.data?.attachmentId || '')
    if (attachmentId) resources.attachmentIds.push(attachmentId)
    expect(attachmentId).toMatch(/^[0-9a-f-]{36}$/i)

    // 抓包等价的直接请求不得写入隐藏、只读或未知字段，且失败不能产生实例。
    await callWorkflowApi(starter, 'POST', `/workflow/process/start/${encodeURIComponent(definition.definitionId)}`, {
      data: {
        businessKey: `DENIED-${suffix}`,
        variables: { requestTitle: '篡改发起', amount: 9, readonlyCode: 'tampered', proofFiles: [attachmentId] }
      },
      expectedCode: 400
    })
    const startPromise = starter.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/start/${encodeURIComponent(definition.definitionId)}`, 'POST'))
    await starter.getByRole('button', { name: '提交申请', exact: true }).click()
    const started = await expectAjaxSuccess(await startPromise, `/workflow/process/start/${definition.definitionId}`)
    const processInstanceId = String(started.data?.id || started.data?.processInstanceId || '')
    expect(processInstanceId).not.toBe('')
    resources.processInstanceIds.push(processInstanceId)

    // 一级任务只展示可读字段；必填、隐藏和只读规则同时由页面与服务端执行。
    const reviewA = await findAssignedWorkflowTask(approverPage, processKey, 'reviewA', processInstanceId)
    await openTaskDetail(approverPage, processInstanceId, reviewA.taskId)
    const reviewAForm = currentTaskFormPanel(approverPage)
    await expect(reviewAForm.getByPlaceholder('请输入申请主题')).toBeDisabled()
    await expect(reviewAForm.getByPlaceholder('只读编码')).toHaveCount(0)
    await expect(reviewAForm.getByPlaceholder('请输入内部说明')).toBeEditable()
    await expect(reviewAForm.getByRole('button', { name: '上传附件' })).toBeDisabled()
    await callWorkflowApi(approverPage, 'POST', '/workflow/task/complete', {
      data: {
        taskId: reviewA.taskId, comment: '篡改字段必须拒绝', copyUserIds: [], nextUserIds: [],
        variables: { requestTitle: '非法覆盖', readonlyCode: '非法隐藏', amount: 100 }
      },
      expectedCode: 400
    })
    await reviewAForm.getByRole('spinbutton').fill('128.50')
    await reviewAForm.getByPlaceholder('请输入内部说明').fill(`一级补充-${suffix}`)
    await approverPage.getByRole('button', { name: '通过', exact: true }).click()
    const completeDialog = approverPage.getByRole('dialog', { name: '通过任务' })
    await completeDialog.getByPlaceholder('请输入审批意见').fill('一级字段权限通过')
    const completePromise = approverPage.waitForResponse(response => matchesEndpoint(response, '/workflow/task/complete', 'POST'))
    await completeDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await completePromise, '/workflow/task/complete')

    const reviewB = await findAssignedWorkflowTask(adminPage, processKey, 'reviewB', processInstanceId)
    const currentDetail = await getWorkflowDetail(adminPage, processInstanceId, reviewB.taskId)
    expect(currentDetail.currentTaskForm.values).toMatchObject({ amount: 128.5, internalNote: `一级补充-${suffix}` })
    expect(currentDetail.currentTaskForm.values).not.toHaveProperty('requestTitle')
    const historicDetail = await getWorkflowDetail(adminPage, processInstanceId, reviewA.taskId)
    expect(historicDetail.currentTaskForm.values).toMatchObject({
      requestTitle: `字段权限申请-${suffix}`, amount: 128.5, internalNote: `一级补充-${suffix}`
    })
    expect(historicDetail.currentTaskForm.values).not.toHaveProperty('readonlyCode')

    // 模板后续修改不得改变已部署实例的节点快照和字段目录。
    const changedTemplate = JSON.parse(form.content)
    changedTemplate.fields.push({
      type: 'text', placeholder: '部署后新增字段',
      __config__: { label: '部署后新增字段', tag: 'el-input', span: 24, required: false, regList: [], layout: 'colFormItem' },
      __vModel__: 'postDeployField'
    })
    await callWorkflowApi(designer, 'PUT', '/workflow/form', {
      data: { formId: Number(form.formId), formName, content: JSON.stringify(changedTemplate), remark: '部署后模板修改隔离验证' }
    })
    const afterTemplateChange = await getWorkflowDetail(adminPage, processInstanceId, reviewB.taskId)
    expect(afterTemplateChange.currentTaskForm.content).not.toContain('postDeployField')

    // 二级退回后发起人只按开始节点权限修改；未提交的金额、内部说明和附件不得被空值覆盖。
    await openTaskDetail(adminPage, processInstanceId, reviewB.taskId)
    await expect(currentTaskFormPanel(adminPage).getByPlaceholder('请输入申请主题')).toHaveCount(0)
    await adminPage.getByRole('button', { name: '退回', exact: true }).click()
    const returnDialog = adminPage.getByRole('dialog', { name: '退回任务' })
    await returnDialog.getByPlaceholder('请输入退回原因').fill('退回补充申请主题')
    const returnPromise = adminPage.waitForResponse(response => matchesEndpoint(response, '/workflow/task/return', 'POST'))
    await returnDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await returnPromise, '/workflow/task/return')

    const returned = await getWorkflowDetail(starter, processInstanceId)
    expect(returned.processStatus).toBe('returned')
    await starter.goto(`/workflow/process-detail/${encodeURIComponent(processInstanceId)}?source=own`)
    await starter.getByRole('tab', { name: '办理表单' }).click()
    const resubmitForm = currentTaskFormPanel(starter)
    await expect(resubmitForm.getByRole('spinbutton')).toHaveCount(0)
    await expect(resubmitForm.getByPlaceholder('请输入内部说明')).toHaveCount(0)
    await resubmitForm.getByPlaceholder('请输入申请主题').fill(`重新提交-${suffix}`)
    await starter.getByRole('button', { name: '重新提交', exact: true }).click()
    const resubmitPromise = starter.waitForResponse(response => matchesEndpoint(response, '/workflow/task/resubmit', 'POST'))
    await starter.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await resubmitPromise, '/workflow/task/resubmit')
    const newReviewA = await findAssignedWorkflowTask(approverPage, processKey, 'reviewA', processInstanceId)
    const resubmitted = await getWorkflowDetail(approverPage, processInstanceId, newReviewA.taskId)
    expect(resubmitted.currentTaskForm.values).toMatchObject({
      requestTitle: `重新提交-${suffix}`, amount: 128.5, internalNote: `一级补充-${suffix}`
    })
    expect(resubmitted.currentTaskForm.values.proofFiles).toHaveLength(1)
    expect(resubmitted.currentTaskForm.values.proofFiles[0]).toMatchObject({
      attachmentId, fieldName: 'proofFiles', originalName: `permission-${suffix}.txt`
    })
    expect(resubmitted.currentTaskForm.values.proofFiles[0].sha256).toMatch(/^[0-9a-f]{64}$/)

    // 陈旧任务再次写入必须拒绝；活动任务和持久化表单值保持不变。
    await callWorkflowApi(adminPage, 'POST', '/workflow/task/complete', {
      data: { taskId: reviewB.taskId, comment: '陈旧任务非法完成', variables: {}, copyUserIds: [], nextUserIds: [] },
      expectedCode: 409
    })
    const afterDenied = await getWorkflowDetail(approverPage, processInstanceId, newReviewA.taskId)
    expect(afterDenied.currentTask.taskId).toBe(String(newReviewA.taskId))
    expect(afterDenied.currentTaskForm.values).toEqual(resubmitted.currentTaskForm.values)

    // BOUND 附件属于历史审计证据，正式 API 必须拒绝单独删除；整轮数据由独占 schema 销毁。
    await callWorkflowApi(starter, 'DELETE',
      `/workflow/attachment/${encodeURIComponent(attachmentId)}`, { expectedCode: 409 })
    const afterAttachmentDeleteDenied = await getWorkflowDetail(
      approverPage, processInstanceId, newReviewA.taskId)
    expect(afterAttachmentDeleteDenied.currentTaskForm.values).toEqual(resubmitted.currentTaskForm.values)
  } catch (error) {
    primaryError = error
  } finally {
    const stateErrors = []
    // 先终止仍活动实例并注销 Redis 会话；绑定附件和历史由外层独占 schema 原子销毁。
    for (const processInstanceId of [...resources.processInstanceIds].reverse()) {
      if (!pages.workflow_admin) {
        stateErrors.push(`流程 ${processInstanceId}: 缺少管理员会话`)
        continue
      }
      try {
        const detail = await getWorkflowDetail(pages.workflow_admin, processInstanceId)
        if (detail.processStatus === 'suspended') {
          await callWorkflowApi(pages.workflow_admin, 'POST', '/workflow/instance/updateState', {
            data: { instanceId: processInstanceId, state: 'ACTIVE' }
          })
        }
        if (['running', 'returned', 'suspended'].includes(detail.processStatus)) {
          await callWorkflowApi(pages.workflow_admin, 'POST', '/workflow/instance/terminate', {
            data: { instanceId: processInstanceId, reason: '字段权限 E2E 清理' }
          })
        }
      } catch (error) {
        stateErrors.push(`流程 ${processInstanceId}: ${String(error.message || error)}`)
      }
    }
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    const finalErrors = [...stateErrors, ...sessionErrors]
    if (primaryError) throw primaryError
    expect(finalErrors, '字段权限 E2E 的实例状态、Redis 登录态和浏览器错误必须全部回收').toEqual([])
  }
})

test('并行任务按各自节点权限并发编辑时不丢失其他字段补丁', async ({ browser }) => {
  test.setTimeout(300_000)
  requireDisposableFieldPermissionSchema()
  const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
  const processKey = `field_permission_parallel_${suffix}`
  const processName = `字段权限并行验收_${suffix}`
  const formName = `字段权限并行表单_${suffix}`
  const resources = {
    attachmentIds: [], processInstanceIds: [], deploymentIds: [], modelIds: [],
    formId: '', categoryId: ''
  }
  const sessions = []
  const pages = {}
  let primaryError = null

  try {
    for (const roleKey of ['workflow_designer', 'workflow_starter', 'workflow_approver', 'workflow_admin']) {
      const session = await openWorkflowRoleSession(browser, roleKey)
      sessions.push(session)
      pages[roleKey] = session.page
    }
    const designer = pages.workflow_designer
    const starter = pages.workflow_starter
    const approverPage = pages.workflow_approver
    const adminPage = pages.workflow_admin
    const approver = await findWorkflowUserOption(designer, 'workflow_approver')
    const admin = await findWorkflowUserOption(designer, 'workflow_admin')
    const categoryCode = `field_permission_parallel_${suffix}`
    await createWorkflowCategory(designer, `字段权限并行分类_${suffix}`, categoryCode, resources)
    const form = await createPermissionForm(designer, formName, resources)
    const created = await callWorkflowApi(designer, 'POST', '/workflow/model', {
      data: {
        modelName: processName, modelKey: processKey, category: categoryCode,
        description: '并行节点字段权限并发一致性验收', formType: 0, formId: Number(form.formId)
      }
    })
    const modelId = String(created.data?.modelId || '')
    resources.modelIds.push(modelId)
    await designer.goto(`/workflow/model-design/${encodeURIComponent(modelId)}`)
    const bpmnXml = buildParallelPermissionBpmn({
      processKey, processName, formId: form.formId,
      approverUserId: String(approver.value), adminUserId: String(admin.value)
    })
    await designer.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}.bpmn`, mimeType: 'application/xml', buffer: Buffer.from(bpmnXml, 'utf8')
    })
    const savePromise = designer.waitForResponse(response => matchesEndpoint(response, '/workflow/model/save', 'POST'))
    await designer.getByRole('button', { name: '保存', exact: true }).click()
    await expectAjaxSuccess(await savePromise, '/workflow/model/save')
    const deployed = await callWorkflowApi(designer, 'POST', '/workflow/model/deploy', {
      query: { modelId }
    })
    resources.deploymentIds.push(String(deployed.data?.deploymentId || ''))
    const definition = await findStartableWorkflowDefinition(starter, processKey)
    const started = await callWorkflowApi(starter, 'POST',
      `/workflow/process/start/${encodeURIComponent(definition.definitionId)}`, {
        data: {
          businessKey: `PARALLEL-${suffix}`,
          variables: {
            requestTitle: `并行申请-${suffix}`, amount: 10, internalNote: `初始说明-${suffix}`
          }
        }
      })
    const processInstanceId = String(started.data?.id || started.data?.processInstanceId || '')
    resources.processInstanceIds.push(processInstanceId)

    const amountTask = await findAssignedWorkflowTask(
      approverPage, processKey, 'amountTask', processInstanceId)
    const noteTask = await findAssignedWorkflowTask(
      adminPage, processKey, 'noteTask', processInstanceId)
    await Promise.all([
      openTaskDetail(approverPage, processInstanceId, amountTask.taskId),
      openTaskDetail(adminPage, processInstanceId, noteTask.taskId)
    ])
    const amountForm = currentTaskFormPanel(approverPage)
    const noteForm = currentTaskFormPanel(adminPage)
    await expect(amountForm.getByRole('spinbutton')).toBeEditable()
    await expect(amountForm.getByPlaceholder('请输入内部说明')).toBeDisabled()
    await expect(noteForm.getByRole('spinbutton')).toBeDisabled()
    await expect(noteForm.getByPlaceholder('请输入内部说明')).toBeEditable()
    await amountForm.getByRole('spinbutton').fill('256.75')
    await noteForm.getByPlaceholder('请输入内部说明').fill(`并行说明-${suffix}`)

    // 两个真实浏览器会话同时提交相邻并行任务，后端必须通过补丁语义保留另一字段。
    await Promise.all([
      approverPage.getByRole('button', { name: '通过', exact: true }).click(),
      adminPage.getByRole('button', { name: '通过', exact: true }).click()
    ])
    const amountDialog = approverPage.getByRole('dialog', { name: '通过任务' })
    const noteDialog = adminPage.getByRole('dialog', { name: '通过任务' })
    await amountDialog.getByPlaceholder('请输入审批意见').fill('并行金额完成')
    await noteDialog.getByPlaceholder('请输入审批意见').fill('并行说明完成')
    const amountResponse = approverPage.waitForResponse(
      response => matchesEndpoint(response, '/workflow/task/complete', 'POST'))
    const noteResponse = adminPage.waitForResponse(
      response => matchesEndpoint(response, '/workflow/task/complete', 'POST'))
    await Promise.all([
      amountDialog.getByRole('button', { name: '确认', exact: true }).click(),
      noteDialog.getByRole('button', { name: '确认', exact: true }).click()
    ])
    const concurrentResponses = await Promise.all([amountResponse, noteResponse])
    const concurrentPayloads = await Promise.all(concurrentResponses.map(async response => {
      expect(response.status(), '/workflow/task/complete 并发 HTTP 状态').toBe(200)
      return response.json()
    }))
    expect(concurrentPayloads.map(payload => payload?.code).sort((left, right) => left - right),
      '并行汇合的乐观锁必须形成一次成功和一次可重试冲突').toEqual([200, 409])

    const concurrentActions = [
      {
        page: approverPage, task: amountTask, nodeKey: 'amountTask', payload: concurrentPayloads[0],
        fieldName: 'amount', initialValue: 10, updatedValue: 256.75,
        otherFieldName: 'internalNote', otherUpdatedValue: `并行说明-${suffix}`
      },
      {
        page: adminPage, task: noteTask, nodeKey: 'noteTask', payload: concurrentPayloads[1],
        fieldName: 'internalNote', initialValue: `初始说明-${suffix}`, updatedValue: `并行说明-${suffix}`,
        otherFieldName: 'amount', otherUpdatedValue: 256.75
      }
    ]
    const rejectedAction = concurrentActions.find(action => action.payload?.code === 409)
    expect(rejectedAction, '并发冲突侧必须可定位').toBeTruthy()
    const retryTask = await findAssignedWorkflowTask(
      rejectedAction.page, processKey, rejectedAction.nodeKey, processInstanceId)
    expect(String(retryTask.taskId)).toBe(String(rejectedAction.task.taskId))
    const afterConflict = await getWorkflowDetail(
      rejectedAction.page, processInstanceId, rejectedAction.task.taskId)
    expect(afterConflict.currentTaskForm.values[rejectedAction.fieldName],
      '409 一侧字段不得产生部分写入').toBe(rejectedAction.initialValue)
    expect(afterConflict.currentTaskForm.values[rejectedAction.otherFieldName],
      '成功侧字段必须已持久化且对并行节点可读').toBe(rejectedAction.otherUpdatedValue)

    // 冲突任务保持活动并可由原办理人重试，重试仍只提交本节点可写字段。
    await callWorkflowApi(rejectedAction.page, 'POST', '/workflow/task/complete', {
      data: {
        taskId: rejectedAction.task.taskId,
        comment: '并发冲突后重试', copyUserIds: [], nextUserIds: [],
        variables: { [rejectedAction.fieldName]: rejectedAction.updatedValue }
      }
    })

    const completedAmount = await findCompletedWorkflowTask(
      approverPage, processKey, 'amountTask', processInstanceId)
    const completedNote = await findCompletedWorkflowTask(
      adminPage, processKey, 'noteTask', processInstanceId)
    const snapshots = await Promise.all([
      getWorkflowDetail(approverPage, processInstanceId, completedAmount.taskId),
      getWorkflowDetail(adminPage, processInstanceId, completedNote.taskId)
    ])
    const preserved = snapshots.some(detail => detail.currentTaskForm?.values?.amount === 256.75
      && detail.currentTaskForm?.values?.internalNote === `并行说明-${suffix}`)
    expect(preserved, '后完成的并行节点快照必须同时包含两个互不覆盖的字段补丁').toBe(true)
  } catch (error) {
    primaryError = error
  } finally {
    const cleanupErrors = await cleanupWorkflowResources({
      admin: pages.workflow_admin,
      designer: pages.workflow_designer
    }, resources)
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    if (primaryError) throw primaryError
    expect([...cleanupErrors, ...sessionErrors], '并行权限 E2E 资源和会话必须全部清理').toEqual([])
  }
})
