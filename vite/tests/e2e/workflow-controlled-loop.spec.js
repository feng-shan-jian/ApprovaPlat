import { randomUUID } from 'node:crypto'
import { test, expect } from './fixtures/workflow.js'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createWorkflowCategory,
  expectWorkflowAudit,
  findAssignedWorkflowTask,
  findCompletedWorkflowTask,
  findStartableWorkflowDefinition,
  findWorkflowUserOption,
  getWorkflowDetail,
  openWorkflowRoleSession,
  startWorkflowThroughUi
} from './support/workflow-fixture.js'

test.describe.configure({ mode: 'serial' })

/**
 * 创建同时服务于发起和整改任务的正式表单，并从正式列表回查数据库主键。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {string} formName 本次运行唯一表单名称。
 * @param {{formId?: string}} resources finally 清理使用的正式资源登记簿。
 * @returns {Promise<string>} 已持久化且可回查的正式表单主键。
 */
async function createControlledLoopForm(page, formName, resources) {
  const content = JSON.stringify({
    fields: [
      {
        type: 'text',
        placeholder: '请输入申请主题',
        clearable: true,
        __config__: {
          label: '申请主题', tag: 'el-input', span: 24,
          required: true, regList: [], layout: 'colFormItem'
        },
        __vModel__: 'requestTitle'
      },
      {
        placeholder: '请选择审批结论',
        clearable: true,
        filterable: false,
        multiple: false,
        __config__: {
          label: '审批结论', tag: 'el-select', span: 24,
          required: false, workflowWritable: true, workflowEnum: true,
          regList: [], layout: 'colFormItem'
        },
        __slot__: {
          options: [
            { label: '继续整改', value: 'RECTIFY' },
            { label: '整改通过', value: 'PASS' }
          ]
        },
        __vModel__: 'reviewResult'
      },
      {
        type: 'textarea',
        rows: 3,
        placeholder: '请输入本轮整改说明',
        maxlength: 300,
        'show-word-limit': true,
        __config__: {
          label: '整改说明', tag: 'el-input', span: 24,
          required: false, workflowWritable: true,
          regList: [], layout: 'colFormItem'
        },
        __vModel__: 'rectifyNote'
      }
    ],
    size: 'default', labelPosition: 'right', labelWidth: 100,
    gutter: 15, disabled: false, span: 24, formBtns: true
  })
  const created = await callWorkflowApi(page, 'POST', '/workflow/form', {
    data: { formName, content, remark: 'P0 受控整改循环真实浏览器验收' }
  })
  const formId = String(created.data?.formId || '')
  // 创建响应一旦返回主键就立即登记，后续回查失败也必须清理正式数据。
  if (formId) resources.formId = formId
  expect(formId, '受控循环正式表单必须返回主键').not.toBe('')
  const listed = await callWorkflowApi(page, 'GET', '/workflow/form/list', {
    query: { formName, pageNum: 1, pageSize: 20 }
  })
  const rows = (listed.rows || []).filter(row => row.formName === formName)
  expect(rows, '受控循环表单必须从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].formId)).toBe(formId)
  return formId
}

/**
 * 生成尚未包含循环属性的可执行作者 BPMN，受控条件只能由后续可见设计器控件写入。
 * @param {{processKey: string, processName: string, formId: string, approverUserId: string}} input 流程、表单和办理人正式主键。
 * @returns {string} 带完整 BPMN DI、局部任务表单和系统监听器的 UTF-8 XML。
 */
function buildControlledLoopDesignerBpmn({ processKey, processName, formId, approverUserId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://approvaplat.example/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="rectifyReview" />
    <userTask id="rectifyReview" name="整改审批" flowable:assignee="${approverUserId}" flowable:formKey="key_${formId}" flowable:localScope="true">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_review_end" sourceRef="rectifyReview" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="100" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_review" bpmnElement="rectifyReview"><omgdc:Bounds x="260" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="520" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_review" bpmnElement="flow_start_review"><omgdi:waypoint x="136" y="190" /><omgdi:waypoint x="260" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_review_end" bpmnElement="flow_review_end"><omgdi:waypoint x="360" y="190" /><omgdi:waypoint x="520" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 从设计器真实 XML 预览对话框读取当前作者模型并关闭对话框。
 * @param {import('@playwright/test').Page} page 已打开模型的设计者页面。
 * @returns {Promise<string>} bpmn-js 当前序列化的完整 XML。
 */
async function readDesignerXml(page) {
  await page.getByRole('button', { name: '预览流程源码' }).click()
  await page.getByRole('menuitem', { name: 'XML 预览' }).click()
  const dialog = page.getByRole('dialog', { name: 'XML 预览' })
  await expect(dialog).toBeVisible()
  const xml = await dialog.getByRole('textbox').inputValue()
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  return xml
}

/**
 * 打开属性面板中的 Element Plus 单选框，按可见文案选择目标值并等待回显稳定。
 * @param {import('@playwright/test').Locator} properties 当前设计器属性面板。
 * @param {string} fieldLabel 表单项可访问名称。
 * @param {string} optionLabel 必须从真实下拉菜单选择并显示的目标文案。
 * @returns {Promise<void>} 组件完成一次真实鼠标选择和 Vue 重渲染后结束。
 */
async function selectDesignerComboboxOption(properties, fieldLabel, optionLabel) {
  const formItem = properties.locator('.el-form-item').filter({ hasText: fieldLabel })
  const combobox = properties.getByRole('combobox', { name: fieldLabel })
  // 点击稳定的选择器容器，兼容普通下拉与同时渲染输入层、占位层的 filterable 下拉。
  await formItem.locator('.el-select__wrapper').click()
  await expect(combobox).toHaveAttribute('aria-expanded', 'true')
  const listboxId = await combobox.getAttribute('aria-controls')
  expect(listboxId, `${fieldLabel} 必须关联唯一真实 listbox`).toBeTruthy()
  await properties.page().locator(`[id="${listboxId}"]`)
    .getByRole('option', { name: optionLabel, exact: true }).click()
  await expect(formItem).toContainText(optionLabel)
}

/**
 * 通过可见属性面板配置受控循环的判断字段、进入值、退出值和最大轮次。
 * @param {import('@playwright/test').Page} page 已选中整改用户任务的设计者页面。
 * @returns {Promise<void>} 五项固定作者属性写入 bpmn-js 命令栈且画布标识可见后结束。
 */
async function configureControlledLoopThroughDesigner(page) {
  const properties = page.locator('.designer-properties-panel')
  await selectDesignerComboboxOption(properties, '循环方式', '整改循环（受控）')
  await expect(properties.getByText('最大办理轮次', { exact: true })).toBeVisible()

  const maxIterations = properties.locator('.el-form-item')
    .filter({ hasText: '最大办理轮次' }).getByRole('spinbutton')
  await maxIterations.fill('3')

  await selectDesignerComboboxOption(properties, '循环判断字段', '审批结论（reviewResult）')

  await selectDesignerComboboxOption(properties, '再次进入条件', '继续整改')

  await selectDesignerComboboxOption(properties, '退出条件', '整改通过')

  const applyButton = properties.getByRole('button', { name: '应用整改循环配置' })
  await expect(applyButton).toBeEnabled()
  await applyButton.click()
  await expect(page.getByText('整改循环 · 最多 3 轮', { exact: true })).toBeVisible()
}

/**
 * 打开真实任务详情，填写正式表单并通过动作对话框完成一轮受控循环。
 * @param {import('@playwright/test').Page} page 当前真实审批人页面。
 * @param {string} processInstanceId 流程实例主键。
 * @param {string} taskId 本轮活动任务主键。
 * @param {{decisionLabel: '继续整改'|'整改通过', note: string, opinion: string, requestTitle?: string, inheritedRequestTitle?: string, inheritedNote?: string}} input 本轮表单值、意见和可选继承断言。
 * @returns {Promise<void>} `/workflow/task/complete` 成功且动作弹窗关闭后结束。
 */
async function completeControlledLoopRound(page, processInstanceId, taskId, input) {
  await page.goto(`/workflow/process-detail/${encodeURIComponent(processInstanceId)}?taskId=${encodeURIComponent(taskId)}`)
  await expect(page.getByText(processInstanceId, { exact: true }).first()).toBeVisible()
  const formPanel = page.getByRole('tabpanel', { name: '办理表单' })
  const titleInput = formPanel.getByPlaceholder('请输入申请主题')
  const noteInput = formPanel.getByPlaceholder('请输入本轮整改说明')
  if (input.inheritedRequestTitle !== undefined) {
    await expect(titleInput, '新一轮任务必须继承上一轮申请主题').toHaveValue(input.inheritedRequestTitle)
  }
  if (input.inheritedNote !== undefined) {
    await expect(noteInput, '新一轮任务必须继承上一轮正式表单快照').toHaveValue(input.inheritedNote)
  }
  if (input.requestTitle !== undefined) await titleInput.fill(input.requestTitle)
  const decisionItem = formPanel.locator('.el-form-item').filter({ hasText: '审批结论' })
  await decisionItem.locator('.el-select__wrapper').click()
  await page.locator('.el-select-dropdown:visible')
    .getByText(input.decisionLabel, { exact: true }).click()
  await noteInput.fill(input.note)

  await page.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '通过任务' })
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.el-form-item').filter({ hasText: /下一办理人|会签办理人|或签办理人/ }),
    '受控循环节点的下一办理人由部署快照固定，不允许客户端改写').toHaveCount(0)
  await dialog.getByPlaceholder('请输入审批意见').fill(input.opinion)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/task/complete', 'POST'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/workflow/task/complete')
  await expect(dialog).toBeHidden()
}

/**
 * 核对详情中唯一受控循环节点的配置、轮次和逐轮审计投影。
 * @param {any} detail `/workflow/process/detail` 返回的正式 data 对象。
 * @param {{active: boolean, completed: number, current: number, outcomes: string[]}} expected 活动状态与轮次结果期望。
 * @returns {void} 状态完全一致时无返回；漂移时断言失败。
 */
function expectControlledLoopState(detail, expected) {
  expect(detail.controlledLoopStates, '详情必须回显唯一受控循环状态').toHaveLength(1)
  const state = detail.controlledLoopStates[0]
  expect(state).toMatchObject({
    activityId: 'rectifyReview',
    activityName: '整改审批',
    decisionVariable: 'reviewResult',
    repeatValue: 'RECTIFY',
    exitValue: 'PASS',
    maxIterations: 3,
    completedIterations: expected.completed,
    currentIteration: expected.current,
    active: expected.active
  })
  expect((state.rounds || []).map(round => round.outcome)).toEqual(expected.outcomes)
  expect((state.rounds || []).map(round => round.iteration))
    .toEqual(expected.outcomes.map((_, index) => index + 1))
}

test('设计器配置的受控整改循环可真实重复办理、退出并回显审计', async ({ browser }) => {
  test.setTimeout(120_000)
  const sessions = []
  const pages = {}
  const resources = {
    categoryId: '', formId: '', modelIds: [], deploymentIds: [], processInstanceIds: []
  }
  try {
    for (const roleKey of ['workflow_designer', 'workflow_starter', 'workflow_approver', 'workflow_admin']) {
      const session = await openWorkflowRoleSession(browser, roleKey)
      sessions.push(session)
      pages[roleKey.replace('workflow_', '')] = session.page
    }

    const approver = await findWorkflowUserOption(pages.designer, 'workflow_approver')
    expect(approver, '受控循环必须取得真实审批办理人').toBeTruthy()
    const suffix = randomUUID().replaceAll('-', '').slice(0, 12)
    const categoryCode = `controlled_loop_${suffix}`
    const processKey = `controlledLoopE2e${suffix}`
    const processName = `受控整改循环-${suffix}`
    const formName = `受控整改表单-${suffix}`
    await createWorkflowCategory(
      pages.designer, `受控整改分类-${suffix}`, categoryCode, resources)
    const formId = await createControlledLoopForm(pages.designer, formName, resources)

    const created = await callWorkflowApi(pages.designer, 'POST', '/workflow/model', {
      data: {
        modelName: processName,
        modelKey: processKey,
        category: categoryCode,
        description: '受控重复审批与整改循环真实浏览器验收',
        formType: 0,
        formId: Number(formId)
      }
    })
    const modelId = String(created.data?.modelId || '')
    expect(modelId, '受控循环模型创建必须返回正式主键').not.toBe('')
    resources.modelIds.push(modelId)

    const modelPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, `/workflow/model/${modelId}`, 'GET'))
    await pages.designer.goto(`/workflow/model-design/${modelId}`)
    await expectAjaxSuccess(await modelPromise, `/workflow/model/${modelId}`)
    const authorXml = buildControlledLoopDesignerBpmn({
      processKey,
      processName,
      formId,
      approverUserId: String(approver.value)
    })
    await pages.designer.locator('input.process-designer__file-input').setInputFiles({
      name: `${processKey}.bpmn`,
      mimeType: 'application/xml',
      buffer: Buffer.from(authorXml, 'utf8')
    })
    await pages.designer.locator('[data-element-id="rectifyReview"]').click()
    await configureControlledLoopThroughDesigner(pages.designer)

    const configuredXml = await readDesignerXml(pages.designer)
    expect(configuredXml).toContain('name="approva.controlledLoop.enabled" value="true"')
    expect(configuredXml).toContain('name="approva.controlledLoop.decisionVariable" value="reviewResult"')
    expect(configuredXml).toContain('name="approva.controlledLoop.repeatValue" value="RECTIFY"')
    expect(configuredXml).toContain('name="approva.controlledLoop.exitValue" value="PASS"')
    expect(configuredXml).toContain('name="approva.controlledLoop.maxIterations" value="3"')
    expect(configuredXml).not.toContain('standardLoopCharacteristics')

    const explicitValidationPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/validate', 'POST'))
    await pages.designer.getByRole('button', { name: '服务端校验' }).click()
    const validation = await expectAjaxSuccess(
      await explicitValidationPromise, '/workflow/model/validate')
    expect(validation.data?.valid, JSON.stringify(validation.data?.issues || [])).toBe(true)
    const validationDialog = pages.designer.getByRole('dialog', { name: '流程校验' })
    await expect(validationDialog.getByText('校验通过', { exact: true })).toBeVisible()
    await expect(validationDialog).not.toContainText('BPMN_ELEMENT_NOT_EXECUTABLE')
    await validationDialog.getByRole('button', { name: '关闭此对话框' }).click()

    const saveValidationPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/validate', 'POST'))
    const savePromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, '/workflow/model/save', 'POST'))
    await pages.designer.getByRole('button', { name: '保存', exact: true }).click()
    const saveValidation = await expectAjaxSuccess(
      await saveValidationPromise, '/workflow/model/validate')
    expect(saveValidation.data?.valid, JSON.stringify(saveValidation.data?.issues || [])).toBe(true)
    await expectAjaxSuccess(await savePromise, '/workflow/model/save')

    const reopenPromise = pages.designer.waitForResponse(response => matchesEndpoint(
      response, `/workflow/model/bpmnXml/${modelId}`, 'GET'))
    await pages.designer.goto(`/workflow/model-design/${modelId}`)
    expect((await reopenPromise).status(), '重开设计器必须真实读取保存后的作者 BPMN').toBe(200)
    await pages.designer.locator('[data-element-id="rectifyReview"]').click()
    const properties = pages.designer.locator('.designer-properties-panel')
    await expect(properties.locator('.el-form-item').filter({ hasText: '循环方式' }))
      .toContainText('整改循环（受控）')
    await expect(properties.locator('.el-form-item').filter({ hasText: '最大办理轮次' })
      .getByRole('spinbutton')).toHaveValue('3')
    await expect(properties.locator('.el-form-item').filter({ hasText: '循环判断字段' }))
      .toContainText('审批结论（reviewResult）')

    const deployed = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/deploy', {
      query: { modelId }
    })
    const deploymentId = String(deployed.data?.deploymentId || '')
    expect(deploymentId, '受控循环部署必须返回正式主键').not.toBe('')
    resources.deploymentIds.push(deploymentId)

    const definition = await findStartableWorkflowDefinition(pages.starter, processKey)
    expect(definition.deploymentId).toBe(deploymentId)
    const processInstanceId = await startWorkflowThroughUi(
      pages.starter,
      definition,
      formName,
      `CONTROLLED-LOOP-${suffix}`,
      `整改申请-${suffix}`,
      resources.processInstanceIds
    )

    const firstTask = await findAssignedWorkflowTask(
      pages.approver, processKey, 'rectifyReview', processInstanceId)
    const firstOpinion = '第一轮确认需要继续整改'
    const firstNote = '第一轮材料缺少风险说明'
    const requestTitle = `整改申请-${suffix}`
    await completeControlledLoopRound(pages.approver, processInstanceId, firstTask.taskId, {
      decisionLabel: '继续整改', note: firstNote, opinion: firstOpinion, requestTitle
    })
    const completedFirst = await findCompletedWorkflowTask(
      pages.approver, processKey, 'rectifyReview', processInstanceId)
    expect(String(completedFirst.taskId)).toBe(String(firstTask.taskId))
    await expectWorkflowAudit(pages.admin, processInstanceId, {
      taskId: firstTask.taskId,
      type: '1',
      action: 'COMPLETE',
      actorUserId: approver.value,
      opinion: firstOpinion
    })

    const secondTask = await findAssignedWorkflowTask(
      pages.approver, processKey, 'rectifyReview', processInstanceId)
    expect(String(secondTask.taskId), '再次进入必须生成新的真实任务主键')
      .not.toBe(String(firstTask.taskId))
    const secondRoundDetail = await getWorkflowDetail(
      pages.approver, processInstanceId, secondTask.taskId)
    expect(secondRoundDetail.processStatus).toBe('running')
    expect(secondRoundDetail.currentTaskForm?.taskLocal).toBe(true)
    expect(secondRoundDetail.currentTaskForm?.values).toMatchObject({
      reviewResult: 'RECTIFY',
      rectifyNote: firstNote
    })
    expectControlledLoopState(secondRoundDetail, {
      active: true, completed: 1, current: 2, outcomes: ['REPEAT']
    })

    await pages.approver.goto(`/workflow/process-detail/${encodeURIComponent(processInstanceId)}?taskId=${encodeURIComponent(secondTask.taskId)}`)
    const loopSection = pages.approver.locator('.workflow-detail__controlled-loops')
    await expect(loopSection.getByText('第 2 / 3 轮办理中', { exact: true })).toBeVisible()
    await expect(loopSection.getByText('再次整改', { exact: true })).toBeVisible()
    await expect(loopSection.getByText('RECTIFY', { exact: true })).toBeVisible()

    const secondOpinion = '第二轮整改完整，同意退出循环'
    const secondNote = '风险说明已补齐并复核通过'
    await completeControlledLoopRound(pages.approver, processInstanceId, secondTask.taskId, {
      decisionLabel: '整改通过', note: secondNote, opinion: secondOpinion,
      inheritedRequestTitle: requestTitle, inheritedNote: firstNote
    })
    await expectWorkflowAudit(pages.admin, processInstanceId, {
      taskId: secondTask.taskId,
      type: '1',
      action: 'COMPLETE',
      actorUserId: approver.value,
      opinion: secondOpinion
    })

    const completedDetail = await getWorkflowDetail(
      pages.admin, processInstanceId, secondTask.taskId)
    expect(completedDetail.processStatus).toBe('completed')
    expect(completedDetail.endTime).not.toBeNull()
    expectControlledLoopState(completedDetail, {
      active: false, completed: 2, current: 2, outcomes: ['REPEAT', 'EXIT']
    })
    const historicTaskIds = (completedDetail.historyProcNodeList || [])
      .map(node => String(node.taskId || ''))
    expect(historicTaskIds).toEqual(expect.arrayContaining([
      String(firstTask.taskId), String(secondTask.taskId)
    ]))
    const finished = await callWorkflowApi(
      pages.approver, 'GET', '/workflow/process/finishedList', {
        query: { processKey, pageNum: 1, pageSize: 100 }
      })
    const completedRounds = (finished.rows || []).filter(row => (
      row.taskDefinitionKey === 'rectifyReview'
      && String(row.processInstanceId) === processInstanceId
    ))
    expect(completedRounds, '退出后已办列表必须保留两轮不同任务').toHaveLength(2)
    expect(new Set(completedRounds.map(row => String(row.taskId))).size).toBe(2)

    await pages.approver.goto(`/workflow/process-detail/${encodeURIComponent(processInstanceId)}?taskId=${encodeURIComponent(secondTask.taskId)}`)
    const completedLoopSection = pages.approver.locator('.workflow-detail__controlled-loops')
    await expect(completedLoopSection.getByText('已完成 2 / 3 轮', { exact: true })).toBeVisible()
    await expect(completedLoopSection.getByText('再次整改', { exact: true })).toBeVisible()
    await expect(completedLoopSection.getByText('退出循环', { exact: true })).toBeVisible()
    await expect(completedLoopSection.getByText('RECTIFY', { exact: true })).toBeVisible()
    await expect(completedLoopSection.getByText('PASS', { exact: true })).toBeVisible()
  } finally {
    const cleanupErrors = pages.designer
      ? await cleanupWorkflowResources(
          { admin: pages.admin, designer: pages.designer }, resources)
      : []
    const sessionErrors = await closeWorkflowRoleSessions(sessions)
    expect([...cleanupErrors, ...sessionErrors],
      '受控循环 E2E 必须清理全部正式数据和真实登录会话').toEqual([])
  }
})
