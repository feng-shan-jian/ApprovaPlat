import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { test, expect } from './fixtures/workflow.js'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'
import {
  buildSequentialLifecycleBpmn,
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createAndDeployWorkflowModel,
  createWorkflowCategory,
  expectRejectedWorkflowActionWithoutSideEffects,
  findAssignedWorkflowTask,
  findStartableWorkflowDefinition,
  findWorkflowTableRow,
  findWorkflowUserOption,
  getWorkflowDetail,
  openWorkflowRoleSessions,
  redactWorkflowSecrets,
  startWorkflowThroughUi
} from './support/workflow-fixture.js'

const MANAGE_ROUTE = '/workflow/instance'
const MANAGE_LIST_ENDPOINT = '/workflow/process/manageList'

/**
 * 计算真实文件内容的 SHA-256 十六进制摘要。
 * @param {Buffer|Uint8Array|string} content 待计算摘要的文件内容。
 * @returns {string} 64 位小写十六进制 SHA-256。
 */
function sha256Hex(content) {
  return createHash('sha256').update(content).digest('hex')
}

/**
 * 创建包含必填主题和可选私有附件字段的正式流程表单，并从列表回查持久化主键。
 * @param {import('@playwright/test').Page} page 流程设计者页面。
 * @param {string} formName 本轮唯一表单名称。
 * @param {{formId?: string}} resources finally 清理使用的正式资源登记簿。
 * @returns {Promise<string>} 服务端生成并已回查的正式表单主键。
 */
async function createAttachmentWorkflowForm(page, formName, resources) {
  if (!resources) throw new Error('附件表单创建前必须提供正式资源登记簿')
  const content = JSON.stringify({
    fields: [
      {
        type: 'text',
        placeholder: '请输入申请主题',
        style: { width: '100%' },
        clearable: true,
        __config__: {
          label: '申请主题', tag: 'el-input', tagIcon: 'input', span: 24,
          required: true, regList: [], layout: 'colFormItem'
        },
        __vModel__: 'requestTitle'
      },
      {
        accept: '.txt',
        limit: 1,
        disabled: false,
        fileSize: 1,
        sizeUnit: 'MB',
        __config__: {
          label: '证明附件', tag: 'el-upload', tagIcon: 'upload', span: 24,
          required: false, defaultValue: [], regList: [], layout: 'colFormItem'
        },
        __vModel__: 'proofFiles'
      }
    ],
    size: 'default', labelPosition: 'right', labelWidth: 100,
    gutter: 15, disabled: false, span: 24, formBtns: true
  })
  const created = await callWorkflowApi(page, 'POST', '/workflow/form', {
    data: { formName, content, remark: 'P3 实例运维与 TEMP 附件真实浏览器验收' }
  })
  const formId = String(created.data?.formId || '')
  // 表单成功落库后立即登记，保证任何后续断言失败都能按依赖顺序回收。
  if (formId) resources.formId = formId
  expect(formId, '附件表单创建必须返回正式主键').not.toBe('')
  const payload = await callWorkflowApi(page, 'GET', '/workflow/form/list', {
    query: { formName, pageNum: 1, pageSize: 20 }
  })
  const rows = (payload.rows || []).filter(row => row.formName === formName)
  expect(rows, '附件表单必须从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].formId), '表单创建与回查主键必须一致').toBe(formId)
  return formId
}

/**
 * 按真实筛选条件确认已删除实例不再出现在工作台列表中。
 * @param {import('@playwright/test').Page} page 流程管理员页面。
 * @param {string} processKey 流程定义标识筛选值。
 * @param {string} businessKey 已删除场景的唯一业务主键。
 * @returns {Promise<void>} 正式列表响应成功且匹配行数为零后结束。
 */
async function expectWorkflowTableRowAbsent(page, processKey, businessKey) {
  await page.goto(MANAGE_ROUTE)
  const processKeyInput = page.getByPlaceholder('请输入流程标识')
  await expect(processKeyInput).toBeVisible()
  await processKeyInput.fill(processKey)
  const responsePromise = page.waitForResponse(response => {
    if (!matchesEndpoint(response, MANAGE_LIST_ENDPOINT, 'GET')) return false
    return new URL(response.url()).searchParams.get('processKey') === processKey
  })
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, MANAGE_LIST_ENDPOINT)
  await expect(page.locator('.el-table__body tbody tr').filter({ hasText: businessKey }),
    '已删除流程历史不得继续出现在管理员工作台').toHaveCount(0)
}

/**
 * 在 Element Plus 确认框中提交真实动作，并等待写接口和随后列表刷新均成功。
 * @param {import('@playwright/test').Page} page 当前职责角色页面。
 * @param {string} endpoint 本次真实写接口路径。
 * @param {'POST'|'DELETE'} method 本次写请求 HTTP 方法。
 * @param {string} listEndpoint 动作完成后页面重新加载的正式列表接口。
 * @param {() => Promise<unknown>} trigger 打开确认框的页面动作。
 * @returns {Promise<object>} 已确认业务码为 200 的写接口响应正文。
 */
async function confirmMessageBoxAction(page, endpoint, method, listEndpoint, trigger) {
  await trigger()
  const messageBox = page.locator('.el-message-box')
  const confirmButton = messageBox.getByRole('button', { name: '确定', exact: true })
  await expect(confirmButton, '实例动作必须经过确认框').toBeVisible()
  const actionResponsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, method))
  const refreshResponsePromise = page.waitForResponse(response => matchesEndpoint(response, listEndpoint, 'GET'))
  await confirmButton.click()
  const payload = await expectAjaxSuccess(await actionResponsePromise, endpoint)
  await expectAjaxSuccess(await refreshResponsePromise, listEndpoint)
  await expect(messageBox).toBeHidden()
  return payload
}

/**
 * 通过管理员工作台真实挂起或激活目标流程实例。
 * @param {import('@playwright/test').Page} page 流程管理员页面。
 * @param {string} processKey 流程定义标识。
 * @param {string} businessKey 场景唯一业务主键。
 * @param {'suspend'|'activate'} action 目标状态迁移动作。
 * @returns {Promise<void>} 状态接口和管理员列表刷新成功后结束。
 */
async function toggleInstanceStateThroughUi(page, processKey, businessKey, action) {
  const row = await findWorkflowTableRow(
    page, MANAGE_ROUTE, MANAGE_LIST_ENDPOINT, processKey, businessKey)
  const buttonType = action === 'suspend' ? 'warning' : 'success'
  const stateButton = row.locator(`button.el-button--${buttonType}`)
  await expect(stateButton, `${action === 'suspend' ? '运行中' : '已挂起'}实例必须提供状态动作`).toHaveCount(1)
  await confirmMessageBoxAction(
    page,
    '/workflow/instance/updateState',
    'POST',
    MANAGE_LIST_ENDPOINT,
    () => stateButton.click()
  )
}

/**
 * 通过我的流程或管理员工作台填写原因并提交取消或终止动作。
 * @param {import('@playwright/test').Page} page 当前职责角色页面。
 * @param {{route: string, listEndpoint: string, processKey: string, businessKey: string, buttonType: 'warning'|'danger', dialogName: string, endpoint: string, comment: string}} input 工作台、场景、按钮、弹窗和接口契约。
 * @returns {Promise<void>} 真实动作和随后列表刷新均成功后结束。
 */
async function submitInstanceActionThroughUi(page, input) {
  const row = await findWorkflowTableRow(
    page, input.route, input.listEndpoint, input.processKey, input.businessKey)
  const actionButton = row.locator(`button.el-button--${input.buttonType}`)
  await expect(actionButton, `${input.dialogName}必须提供唯一页面按钮`).toHaveCount(1)
  await actionButton.click()
  const dialog = page.getByRole('dialog', { name: input.dialogName })
  await expect(dialog).toBeVisible()
  await dialog.locator('textarea').fill(input.comment)
  const actionResponsePromise = page.waitForResponse(response => matchesEndpoint(response, input.endpoint, 'POST'))
  const refreshResponsePromise = page.waitForResponse(response => matchesEndpoint(response, input.listEndpoint, 'GET'))
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expectAjaxSuccess(await actionResponsePromise, input.endpoint)
  await expectAjaxSuccess(await refreshResponsePromise, input.listEndpoint)
  await expect(dialog).toBeHidden()
}

/**
 * 从管理员工作台删除一个终态且无附件引用的流程历史。
 * @param {import('@playwright/test').Page} page 流程管理员页面。
 * @param {string} processKey 流程定义标识。
 * @param {string} businessKey 场景唯一业务主键。
 * @param {string} processInstanceId 待删除正式流程实例主键。
 * @returns {Promise<void>} 删除接口和管理员列表刷新均成功后结束。
 */
async function deleteHistoryThroughUi(page, processKey, businessKey, processInstanceId) {
  const row = await findWorkflowTableRow(
    page, MANAGE_ROUTE, MANAGE_LIST_ENDPOINT, processKey, businessKey)
  const deleteButton = row.locator('button.el-button--danger')
  await expect(deleteButton, '终态实例必须提供唯一历史删除按钮').toHaveCount(1)
  const endpoint = `/workflow/process/instance/${encodeURIComponent(processInstanceId)}`
  await confirmMessageBoxAction(
    page, endpoint, 'DELETE', MANAGE_LIST_ENDPOINT, () => deleteButton.click())
}

/**
 * 通过真实发起页面创建并立即登记一个无附件业务实例。
 * @param {import('@playwright/test').Page} page 流程发起人页面。
 * @param {{definitionId: string, deploymentId: string}} definition 已部署流程定义关系。
 * @param {string} formName 页面必须展示的部署表单名称。
 * @param {string} runId 本轮测试唯一标识。
 * @param {string} scenario 场景短标识。
 * @param {{draftFixtures: Array<object>, processInstanceIds: string[]}} resources finally 清理使用的资源登记簿。
 * @returns {Promise<{processInstanceId: string, businessKey: string}>} 正式实例主键与唯一业务主键。
 */
async function startTrackedScenario(page, definition, formName, runId, scenario, resources) {
  const businessKey = `BUS-${runId}-${scenario}`
  const processInstanceId = await startWorkflowThroughUi(
    page,
    definition,
    formName,
    businessKey,
    `实例附件验收-${scenario}-${runId}`,
    resources
  )
  return { processInstanceId, businessKey }
}

/**
 * 同时核对工作台中文状态和详情 API 的稳定状态及终止时间。
 * @param {import('@playwright/test').Page} page 具备目标实例读取权限的页面。
 * @param {string} route 工作台前端路由。
 * @param {string} listEndpoint 工作台正式列表接口。
 * @param {string} processKey 流程定义标识。
 * @param {string} businessKey 场景唯一业务主键。
 * @param {string} processInstanceId 正式流程实例主键。
 * @param {{status: string, label: string, ended: boolean, taskId?: string}} expected 稳定状态、中文标签和终态约束。
 * @returns {Promise<any>} 已通过列表和详情一致性断言的正式详情对象。
 */
async function expectInstanceState(page, route, listEndpoint, processKey, businessKey, processInstanceId, expected) {
  const row = await findWorkflowTableRow(page, route, listEndpoint, processKey, businessKey)
  await expect(row, '工作台必须回显目标稳定状态').toContainText(expected.label)
  const detail = await getWorkflowDetail(page, processInstanceId, expected.taskId || '')
  expect(detail.processStatus, '列表与详情的流程状态必须一致').toBe(expected.status)
  if (expected.ended) expect(detail.endTime, '终态流程必须持久化结束时间').not.toBeNull()
  else expect(detail.endTime, '运行态或挂起态流程不得提前写入结束时间').toBeNull()
  return detail
}

test('TEMP 附件与实例运维动作具备真实 UI、对象授权、状态门禁、持久化和可清理闭环', async ({ browser }) => {
  test.setTimeout(600_000)
  const runId = `p3inst_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
  // 资源登记簿只记录真实持久化主键；TEMP 删除后仍保留 ID 供 finally 幂等重试。
  const resources = {
    attachmentIds: [],
    draftFixtures: [],
    processInstanceIds: [],
    deploymentIds: [],
    modelIds: [],
    formId: '',
    categoryId: ''
  }
  let sessions = []
  let pages = {}
  let primaryError = null

  try {
    ({ sessions, pages } = await openWorkflowRoleSessions(browser, {
      designer: 'workflow_designer',
      starter: 'workflow_starter',
      approver: 'workflow_approver',
      admin: 'workflow_admin',
      auditor: 'workflow_auditor'
    }))

    const approver = await findWorkflowUserOption(pages.designer, 'workflow_approver', true)
    const admin = await findWorkflowUserOption(pages.designer, 'workflow_admin', true)
    expect(approver, '审批角色必须具备流程办理资格').not.toBeNull()
    expect(admin, '流程管理员必须具备流程办理资格').not.toBeNull()

    const categoryName = `P3实例附件-${runId}`
    const categoryCode = `p3inst_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    const formName = `P3实例附件表单-${runId}`
    const processKey = `p3inst_seq_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    resources.categoryId = await createWorkflowCategory(
      pages.designer, categoryName, categoryCode, resources)
    resources.formId = await createAttachmentWorkflowForm(pages.designer, formName, resources)
    const model = await createAndDeployWorkflowModel(pages.designer, {
      processKey,
      processName: `P3实例附件审批-${runId}`,
      categoryCode,
      formId: resources.formId,
      bpmnXml: buildSequentialLifecycleBpmn({
        processKey,
        processName: `P3实例附件审批-${runId}`,
        formId: resources.formId,
        approverUserId: approver.value,
        adminUserId: admin.value
      }),
      resourceRegistry: resources
    })
    const definition = await findStartableWorkflowDefinition(pages.starter, processKey)
    expect(definition.deploymentId, '可发起定义必须来自本轮真实部署').toBe(model.deploymentId)

    // TEMP 附件链在任何流程发起前完成，禁止把不可清理的 BOUND 审计附件带入本用例。
    await pages.starter.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
    await expect(pages.starter.getByRole('heading', { name: formName })).toBeVisible()
    const attachmentName = `proof-${runId}.txt`
    const attachmentBytes = Buffer.from(`workflow temporary attachment ${runId}\n`, 'utf8')
    const expectedSha256 = sha256Hex(attachmentBytes)
    const fileInput = pages.starter.locator('.workflow-attachment-upload input[type="file"]')
    await expect(fileInput, '真实发起表单必须渲染唯一附件选择入口').toHaveCount(1)
    const uploadResponsePromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, '/workflow/attachment', 'POST'))
    await fileInput.setInputFiles({
      name: attachmentName,
      mimeType: 'text/plain',
      buffer: attachmentBytes
    })
    const uploadPayload = await expectAjaxSuccess(
      await uploadResponsePromise, '/workflow/attachment')
    const attachmentId = String(uploadPayload.data?.attachmentId || '')
    // 上传 POST 返回正式主键后立即登记，后续元数据或页面断言失败也能幂等回收。
    if (attachmentId) resources.attachmentIds.push(attachmentId)
    expect(attachmentId, 'TEMP 附件上传必须返回正式 UUID').toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
    const attachment = uploadPayload.data
    expect(attachment.fieldName).toBe('proofFiles')
    expect(attachment.originalName).toBe(attachmentName)
    expect(Number(attachment.fileSize)).toBe(attachmentBytes.length)
    expect(attachment.sha256).toBe(expectedSha256)
    expect(attachment.status).toBe('TEMP')
    expect(attachment.processInstanceId).toBeNull()
    expect(attachment.taskId).toBeNull()
    expect(attachment.nodeKey).toBeNull()

    const uploadItem = pages.starter.locator('.workflow-attachment-upload .el-upload-list__item')
      .filter({ hasText: attachmentName })
    await expect(uploadItem, '上传成功后必须从真实表单附件列表回显').toHaveCount(1)
    const downloadResponsePromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, `/workflow/attachment/${attachmentId}/content`, 'GET'))
    const browserDownloadPromise = pages.starter.waitForEvent('download')
    await uploadItem.locator('.el-upload-list__item-name').click()
    const downloadResponse = await downloadResponsePromise
    const browserDownload = await browserDownloadPromise
    expect(downloadResponse.status(), '附件下载 HTTP 状态').toBe(200)
    expect(await downloadResponse.headerValue('content-type')).toContain('application/octet-stream')
    expect(await downloadResponse.headerValue('etag')).toBe(`"${expectedSha256}"`)
    expect(browserDownload.suggestedFilename(), '浏览器下载文件名必须保留安全原名').toBe(attachmentName)
    const downloadPath = await browserDownload.path()
    expect(downloadPath, '浏览器下载必须产生可读取的受控临时文件').not.toBeNull()
    const downloadedBytes = await readFile(downloadPath)
    expect(downloadedBytes.equals(attachmentBytes), '浏览器下载字节必须与上传内容一致').toBe(true)
    expect(sha256Hex(downloadedBytes), '浏览器下载摘要必须与服务端 ETag 一致').toBe(expectedSha256)

    // 先冻结所有者可读元数据，再由审计角色直接调用受保护下载接口验证对象越权零副作用。
    const ownerMetadataBefore = (await callWorkflowApi(
      pages.starter, 'GET', `/workflow/attachment/${encodeURIComponent(attachmentId)}`)).data
    await callWorkflowApi(
      pages.auditor,
      'GET',
      `/workflow/attachment/${encodeURIComponent(attachmentId)}/content`,
      { expectedCode: 403 }
    )
    const ownerMetadataAfter = (await callWorkflowApi(
      pages.starter, 'GET', `/workflow/attachment/${encodeURIComponent(attachmentId)}`)).data
    expect(ownerMetadataAfter, '越权下载拒绝不得改变任何附件正式元数据').toEqual(ownerMetadataBefore)

    // TEMP 移除必须由真实上传列表触发 DELETE，只有服务端物理清理成功后页面才可移除引用。
    const deleteAttachmentResponsePromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, `/workflow/attachment/${attachmentId}`, 'DELETE'))
    await uploadItem.hover()
    const closeButton = uploadItem.locator('.el-icon--close')
    await expect(closeButton, 'TEMP 附件列表项必须提供移除入口').toHaveCount(1)
    await closeButton.click()
    await expectAjaxSuccess(
      await deleteAttachmentResponsePromise, `/workflow/attachment/${attachmentId}`)
    await expect(uploadItem, '后端删除成功后 TEMP 附件必须从表单列表消失').toHaveCount(0)
    await callWorkflowApi(
      pages.starter,
      'GET',
      `/workflow/attachment/${encodeURIComponent(attachmentId)}`,
      { expectedCode: 404 }
    )
    await callWorkflowApi(
      pages.starter,
      'GET',
      `/workflow/attachment/${encodeURIComponent(attachmentId)}/content`,
      { expectedCode: 404 }
    )

    // 场景一：无附件实例通过管理员页面挂起，挂起期间真实完成请求必须 409 且全状态零副作用。
    const stateScenario = await startTrackedScenario(
      pages.starter, definition, formName, runId, 'state', resources)
    const stateTask = await findAssignedWorkflowTask(
      pages.approver, processKey, 'reviewA', stateScenario.processInstanceId)
    await toggleInstanceStateThroughUi(
      pages.admin, processKey, stateScenario.businessKey, 'suspend')
    await expectInstanceState(
      pages.admin,
      MANAGE_ROUTE,
      MANAGE_LIST_ENDPOINT,
      processKey,
      stateScenario.businessKey,
      stateScenario.processInstanceId,
      { status: 'suspended', label: '已挂起', ended: false, taskId: stateTask.taskId }
    )
    const copyReadablePages = [pages.starter, pages.approver, pages.auditor, pages.admin]
    await expectRejectedWorkflowActionWithoutSideEffects(
      pages.admin,
      stateScenario.processInstanceId,
      stateTask.taskId,
      409,
      expectedCode => callWorkflowApi(pages.approver, 'POST', '/workflow/task/complete', {
        data: {
          taskId: stateTask.taskId,
          comment: '挂起实例禁止完成任务',
          variables: {},
          copyUserIds: [],
          nextUserIds: []
        },
        expectedCode
      }),
      copyReadablePages
    )
    await toggleInstanceStateThroughUi(
      pages.admin, processKey, stateScenario.businessKey, 'activate')
    await expectInstanceState(
      pages.admin,
      MANAGE_ROUTE,
      MANAGE_LIST_ENDPOINT,
      processKey,
      stateScenario.businessKey,
      stateScenario.processInstanceId,
      { status: 'running', label: '运行中', ended: false, taskId: stateTask.taskId }
    )

    // 场景二：发起人从我的流程页面填写原因并取消自己的无附件实例。
    const canceledScenario = await startTrackedScenario(
      pages.starter, definition, formName, runId, 'cancel', resources)
    await submitInstanceActionThroughUi(pages.starter, {
      route: '/office/own',
      listEndpoint: '/workflow/process/ownList',
      processKey,
      businessKey: canceledScenario.businessKey,
      buttonType: 'warning',
      dialogName: '取消流程',
      endpoint: '/workflow/task/stopProcess',
      comment: '发起人取消实例附件验收流程'
    })
    await expectInstanceState(
      pages.starter,
      '/office/own',
      '/workflow/process/ownList',
      processKey,
      canceledScenario.businessKey,
      canceledScenario.processInstanceId,
      { status: 'canceled', label: '已取消', ended: true }
    )

    // 场景三：管理员从实例运维页面填写原因并终止另一个无附件实例。
    const terminatedScenario = await startTrackedScenario(
      pages.starter, definition, formName, runId, 'terminate', resources)
    await submitInstanceActionThroughUi(pages.admin, {
      route: MANAGE_ROUTE,
      listEndpoint: MANAGE_LIST_ENDPOINT,
      processKey,
      businessKey: terminatedScenario.businessKey,
      buttonType: 'danger',
      dialogName: '终止流程实例',
      endpoint: '/workflow/instance/terminate',
      comment: '管理员终止实例附件验收流程'
    })
    await expectInstanceState(
      pages.admin,
      MANAGE_ROUTE,
      MANAGE_LIST_ENDPOINT,
      processKey,
      terminatedScenario.businessKey,
      terminatedScenario.processInstanceId,
      { status: 'terminated', label: '已终止', ended: true }
    )

    // 终态历史仅在真实 DELETE 成功后移出登记簿，随后同时核对详情 404 与列表消失。
    for (const scenario of [canceledScenario, terminatedScenario]) {
      await deleteHistoryThroughUi(
        pages.admin, processKey, scenario.businessKey, scenario.processInstanceId)
      const registryIndex = resources.processInstanceIds.indexOf(scenario.processInstanceId)
      expect(registryIndex, '历史删除前实例必须存在于清理登记簿').toBeGreaterThanOrEqual(0)
      resources.processInstanceIds.splice(registryIndex, 1)
      await callWorkflowApi(pages.admin, 'GET', '/workflow/process/detail', {
        query: { procInsId: scenario.processInstanceId },
        expectedCode: 404
      })
      await expectWorkflowTableRowAbsent(pages.admin, processKey, scenario.businessKey)
    }
  } catch (error) {
    primaryError = error
  } finally {
    const cleanupErrors = []
    // 附件先于流程夹具清理；成功删除的 TEMP 主键也重试一次，验证正式幂等删除契约。
    for (const attachmentId of [...resources.attachmentIds].reverse()) {
      if (!pages.starter) {
        cleanupErrors.push(`附件 ${attachmentId}: 缺少发起人会话`)
        continue
      }
      try {
        await callWorkflowApi(
          pages.starter, 'DELETE', `/workflow/attachment/${encodeURIComponent(attachmentId)}`)
      } catch (error) {
        cleanupErrors.push(`附件 ${attachmentId}: ${redactWorkflowSecrets(error)}`)
      }
    }
    cleanupErrors.push(...await cleanupWorkflowResources(pages, resources))
    const logoutErrors = await closeWorkflowRoleSessions(sessions)
    const finalErrors = [...cleanupErrors, ...logoutErrors]
    if (primaryError) {
      if (finalErrors.length) primaryError.message += `；清理失败：${finalErrors.join(' | ')}`
      throw primaryError
    }
    expect(finalErrors, 'TEMP 附件、正式业务夹具和 Redis 登录态必须全部清理').toEqual([])
  }
})
