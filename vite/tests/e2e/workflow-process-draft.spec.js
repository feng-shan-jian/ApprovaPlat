import { randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { rename, rm } from 'node:fs/promises'
import path from 'node:path'
import { expect, test } from '@playwright/test'
import { loginThroughUi, logoutThroughUi } from './fixtures/workflow.js'
import {
  callWorkflowApi,
  cleanupWorkflowResources,
  closeWorkflowRoleSessions,
  createAndDeployWorkflowModel,
  createWorkflowCategory,
  findStartableWorkflowDefinition,
  findWorkflowUserOption,
  openWorkflowRoleSession,
  workflowAccounts
} from './support/workflow-fixture.js'
import { expectAjaxSuccess, matchesEndpoint } from './support/http.js'

test.describe.configure({ mode: 'serial' })

/**
 * 创建包含必填正文、服务端只读字段和私有附件的正式开始表单。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {string} formName 本轮唯一表单名称。
 * @param {{formId?: string}} resources finally 清理登记簿。
 * @returns {Promise<string>} 已从正式列表回查的表单主键。
 */
async function createDraftWorkflowForm(page, formName, resources) {
  const content = JSON.stringify({
    fields: [
      {
        type: 'text', placeholder: '请输入申请主题', style: { width: '100%' }, clearable: true,
        __config__: {
          label: '申请主题', tag: 'el-input', tagIcon: 'input', span: 24,
          required: true, regList: [], layout: 'colFormItem'
        },
        __vModel__: 'requestTitle'
      },
      {
        type: 'text', disabled: true, style: { width: '100%' },
        __config__: {
          label: '系统只读字段', tag: 'el-input', tagIcon: 'input', span: 24,
          required: false, workflowWritable: false, regList: [], layout: 'colFormItem'
        },
        __vModel__: 'systemReadonly'
      },
      {
        accept: '.txt', limit: 1, disabled: false, fileSize: 1, sizeUnit: 'MB',
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
    data: { formName, content, remark: '申请草稿完整生命周期真实浏览器验收' }
  })
  const formId = String(created.data?.formId || '')
  if (formId) resources.formId = formId
  expect(formId, '草稿验收表单必须返回正式主键').not.toBe('')
  const listed = await callWorkflowApi(page, 'GET', '/workflow/form/list', {
    query: { formName, pageNum: 1, pageSize: 20 }
  })
  const rows = (listed.rows || []).filter(row => row.formName === formName)
  expect(rows, '草稿验收表单必须从正式列表唯一回查').toHaveLength(1)
  expect(String(rows[0].formId)).toBe(formId)
  return formId
}

/**
 * 生成由发起页面专用成员字段驱动的并行会签流程。
 * @param {{processKey: string, processName: string, formId: string}} input 流程标识、名称和正式表单主键。
 * @returns {string} 可保存部署且包含完整 DI 的 BPMN XML。
 */
function buildStartAssignmentBpmn({ processKey, processName, formId }) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC" xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="http://approvaplat.example/workflow">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}" />
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="starterReview" />
    <userTask id="starterReview" name="发起会签" flowable:assignee="\${assignee}">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}" />
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}" />
      </extensionElements>
      <multiInstanceLoopCharacteristics isSequential="false" flowable:collection="\${multiInstanceHandler.getStartUserIds(execution)}" flowable:elementVariable="assignee">
        <completionCondition xsi:type="tFormalExpression">\${nrOfCompletedInstances == nrOfInstances}</completionCondition>
      </multiInstanceLoopCharacteristics>
    </userTask>
    <sequenceFlow id="flow_review_end" sourceRef="starterReview" targetRef="end" />
    <endEvent id="end" name="结束" />
  </process>
  <bpmndi:BPMNDiagram id="diagram_${processKey}">
    <bpmndi:BPMNPlane id="plane_${processKey}" bpmnElement="${processKey}">
      <bpmndi:BPMNShape id="shape_start" bpmnElement="start"><omgdc:Bounds x="100" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_review" bpmnElement="starterReview"><omgdc:Bounds x="260" y="150" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="shape_end" bpmnElement="end"><omgdc:Bounds x="500" y="172" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="edge_start_review" bpmnElement="flow_start_review"><omgdi:waypoint x="136" y="190" /><omgdi:waypoint x="260" y="190" /></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="edge_review_end" bpmnElement="flow_review_end"><omgdi:waypoint x="360" y="190" /><omgdi:waypoint x="500" y="190" /></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 从草稿写响应中读取并校验服务端 UUID。
 * @param {any} payload 若依 AjaxResult 响应。
 * @returns {string} 草稿 UUID。
 */
function requireDraftId(payload) {
  const draftId = String(payload.data?.draftId || payload.data?.id || '')
  expect(draftId, '草稿写入必须返回正式 UUID').toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
  return draftId
}

/**
 * 在唯一白名单隔离库执行不含动态结构的 mysql 语句，凭据仅通过子进程环境传递。
 * @param {string} sql 已由调用方使用固定表名和转义字面量构造的测试清理 SQL。
 * @returns {string[]} mysql 按行返回的非敏感结果。
 */
function runDraftFixtureMysql(sql) {
  const jdbcUrl = String(process.env.FLOWABLE_RBAC_JDBC_URL || '')
  const connection = /^jdbc:mysql:\/\/(127\.0\.0\.1|localhost):(\d+)\/([^?]+)(?:\?.*)?$/.exec(jdbcUrl)
  if (!connection || connection[3] !== 'ry_vue_codex_flowable_it') {
    throw new Error('草稿 E2E 清理数据库不在唯一白名单 schema')
  }
  const username = String(process.env.FLOWABLE_RBAC_DB_USERNAME || '').trim()
  const password = String(process.env.FLOWABLE_RBAC_DB_PASSWORD || '')
  if (!username || !password) throw new Error('草稿 E2E 清理缺少数据库运行账号')
  const command = String(process.env.FLOWABLE_E2E_MYSQL_COMMAND || 'mysql').trim()
  const result = spawnSync(command, [
    `--host=${connection[1]}`,
    `--port=${connection[2]}`,
    `--user=${username}`,
    '--database=ry_vue_codex_flowable_it',
    '--default-character-set=utf8mb4',
    '--batch',
    '--skip-column-names'
  ], {
    env: { ...process.env, MYSQL_PWD: password },
    input: sql,
    encoding: 'utf8',
    shell: false
  })
  if (result.status !== 0) {
    throw new Error(`草稿 E2E 清理 SQL 执行失败：${String(result.stderr || '').trim()}`)
  }
  return String(result.stdout || '').split(/\r?\n/).filter(Boolean)
}

/**
 * 清除本轮已验证的 BOUND 附件 fixture，使既有“绑定附件阻止历史删除”契约保持不变。
 * @param {{attachmentId: string, processInstanceId: string}} fixture 本轮附件与真实实例关系。
 * @returns {Promise<void>} 私有文件和附件元数据均已清除；任一步不一致都会失败。
 */
async function purgeBoundAttachmentFixture(fixture) {
  const attachmentId = String(fixture?.attachmentId || '')
  const processInstanceId = String(fixture?.processInstanceId || '')
  if (!/^[0-9a-f-]{36}$/i.test(attachmentId)
      || !/^[A-Za-z0-9_-]{1,64}$/.test(processInstanceId)) {
    throw new Error('草稿 E2E 绑定附件清理主键不合法')
  }
  const escapeLiteral = value => value.replaceAll("'", "''")
  const rows = runDraftFixtureMysql(
    `SELECT storage_key, attachment_status, process_instance_id FROM wf_attachment `
      + `WHERE attachment_id='${escapeLiteral(attachmentId)}';\n`)
  if (rows.length !== 1) throw new Error('草稿 E2E 绑定附件元数据不唯一')
  const [storageKey, status, storedInstanceId] = rows[0].split('\t')
  if (status !== 'BOUND' || storedInstanceId !== processInstanceId
      || !/^\d{4}\/\d{2}\/\d{2}\/[0-9a-f]{32}(?:\.[a-z0-9]{1,10})?$/.test(storageKey)) {
    throw new Error('草稿 E2E 绑定附件关系或存储键不满足清理门禁')
  }

  const profileRoot = path.resolve(process.env.FLOWABLE_E2E_PROFILE_ROOT
    || 'D:/approvaplat/uploadPath')
  const storageRoot = path.resolve(profileRoot, 'workflow-attachments')
  const sourcePath = path.resolve(storageRoot, ...storageKey.split('/'))
  if (!sourcePath.startsWith(`${storageRoot}${path.sep}`)) {
    throw new Error('草稿 E2E 绑定附件路径越出私有存储根')
  }
  const quarantinePath = `${sourcePath}.e2e-cleanup-${attachmentId}`
  await rename(sourcePath, quarantinePath)
  try {
    const deleted = runDraftFixtureMysql(
      `DELETE FROM wf_attachment WHERE attachment_id='${escapeLiteral(attachmentId)}' `
        + `AND attachment_status='BOUND' `
        + `AND process_instance_id='${escapeLiteral(processInstanceId)}';\n`
        + `SELECT ROW_COUNT();\n`)
    if (deleted.at(-1) !== '1') throw new Error('草稿 E2E 绑定附件条件删除未命中唯一记录')
  } catch (error) {
    await rename(quarantinePath, sourcePath).catch(() => {})
    throw error
  }
  await rm(quarantinePath, { force: false })
}

test('草稿跨登录恢复、附件迁移、CAS、越权、过期和重复提交保持真实一致', async ({ browser }) => {
  test.setTimeout(180_000)
  const sessions = []
  const pages = {}
  const resources = {
    processInstanceIds: [], deploymentIds: [], modelIds: [], formId: '', categoryId: ''
  }
  let primaryError = null
  let staleDraftId = ''
  let staleDraftRevision = 0
  let boundAttachmentFixture = null

  try {
    const designerSession = await openWorkflowRoleSession(browser, 'workflow_designer')
    sessions.push(designerSession)
    pages.designer = designerSession.page
    const starterSession = await openWorkflowRoleSession(browser, 'workflow_starter')
    sessions.push(starterSession)
    pages.starter = starterSession.page
    const auditorSession = await openWorkflowRoleSession(browser, 'workflow_auditor')
    sessions.push(auditorSession)
    pages.auditor = auditorSession.page
    const adminSession = await openWorkflowRoleSession(browser, 'workflow_admin')
    sessions.push(adminSession)
    pages.admin = adminSession.page

    const approver = await findWorkflowUserOption(pages.designer, 'workflow_approver', true)
    expect(approver, '发起会签必须使用实时审批资格用户').not.toBeNull()
    const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
    const categoryCode = `draft_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    const processKey = `draft_lifecycle_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
    const processName = `申请草稿审批-${runId}`
    const formName = `申请草稿表单-${runId}`
    resources.categoryId = await createWorkflowCategory(
      pages.designer, `申请草稿-${runId}`, categoryCode, resources)
    resources.formId = await createDraftWorkflowForm(pages.designer, formName, resources)
    const bpmnXml = buildStartAssignmentBpmn({ processKey, processName, formId: resources.formId })
    const model = await createAndDeployWorkflowModel(pages.designer, {
      processKey, processName, categoryCode, formId: resources.formId, bpmnXml,
      resourceRegistry: resources
    })
    const definition = await findStartableWorkflowDefinition(pages.starter, processKey)
    expect(definition.deploymentId).toBe(model.deploymentId)
    const startForm = await callWorkflowApi(
      pages.starter, 'GET', '/workflow/process/getProcessForm', {
        query: { definitionId: definition.definitionId, deployId: definition.deploymentId }
      })
    expect(startForm.data?.startMultiInstanceAssignments,
      '部署表单 API 必须投影发起页面专用会签字段').toEqual([expect.objectContaining({
      activityId: 'starterReview', mode: 'ALL', minUsers: 1
    })])

    // 真实发起页允许缺少正式必填，但会持久化业务主键与发起会签成员。
    await pages.starter.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
    await expect(pages.starter.getByRole('heading', { name: formName })).toBeVisible()
    const assignmentSelect = pages.starter.locator('.process-start-page__assignments .el-select').first()
    await assignmentSelect.click()
    const option = pages.starter.locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: approver.label }).first()
    await expect(option, '发起会签下拉框必须显示实时审批资格用户').toBeVisible()
    await option.click()
    const initialBusinessKey = `DRAFT-${runId}`
    await pages.starter.getByPlaceholder('可选').fill(initialBusinessKey)
    const createDraftPromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, '/workflow/process/draft', 'POST'))
    await pages.starter.getByRole('button', { name: '保存草稿', exact: true }).click()
    const createdDraft = await expectAjaxSuccess(await createDraftPromise, '/workflow/process/draft')
    const draftId = requireDraftId(createdDraft)
    expect(Number(createdDraft.data?.revisionNo)).toBe(1)
    expect(createdDraft.data?.variables || {}).not.toHaveProperty('requestTitle')
    expect(createdDraft.data?.multiInstanceUserIds?.starterReview).toEqual([Number(approver.value)])
    await expect(pages.starter).toHaveURL(new RegExp(`/workflow/process-draft/${draftId}(?:[/?]|$)`))

    // 刷新和真实退出登录后，列表与继续编辑入口必须从 MySQL 恢复同一草稿。
    await pages.starter.reload()
    await expect(pages.starter.getByPlaceholder('可选')).toHaveValue(initialBusinessKey)
    await expect(pages.starter.locator('.process-start-page__assignments .el-select__selected-item')
      .filter({ hasText: approver.label })).toHaveCount(1)
    await logoutThroughUi(pages.starter, 'workflow_starter')
    await loginThroughUi(pages.starter, workflowAccounts.workflow_starter)
    const draftListPromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, '/workflow/process/draft/list', 'GET'))
    await pages.starter.goto('/office/draft')
    await expectAjaxSuccess(await draftListPromise, '/workflow/process/draft/list')
    const draftRow = pages.starter.locator('.el-table__body-wrapper tbody tr')
      .filter({ hasText: initialBusinessKey }).first()
    await expect(draftRow, '本人草稿必须从真实列表回显').toBeVisible()
    await draftRow.locator('button.el-button--primary').click()
    await expect(pages.starter).toHaveURL(new RegExp(`/workflow/process-draft/${draftId}(?:[/?]|$)`))
    await expect(pages.starter.getByPlaceholder('可选')).toHaveValue(initialBusinessKey)

    // 服务端只读字段不得通过草稿保存形成字段权限旁路，失败前后 revision 保持不变。
    await callWorkflowApi(pages.starter, 'PUT', `/workflow/process/draft/${draftId}`, {
      expectedCode: 400,
      data: {
        expectedVersion: 1, businessKey: initialBusinessKey,
        variables: { systemReadonly: 'forbidden' },
        multiInstanceUserIds: { starterReview: [Number(approver.value)] }
      }
    })
    const afterReadonlyReject = await callWorkflowApi(
      pages.starter, 'GET', `/workflow/process/draft/${draftId}`)
    expect(Number(afterReadonlyReject.data?.revisionNo)).toBe(1)

    // 上传真实私有文件并保存，附件从 TEMP 原子迁移到 DRAFT。
    const subject = `跨时段申请-${runId}`
    await pages.starter.getByPlaceholder('请输入申请主题').fill(subject)
    const attachmentName = `draft-proof-${runId}.txt`
    const fileInput = pages.starter.locator('.workflow-attachment-upload input[type="file"]')
    const uploadPromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, '/workflow/attachment', 'POST'))
    await fileInput.setInputFiles({
      name: attachmentName,
      mimeType: 'text/plain',
      buffer: Buffer.from(`draft lifecycle ${runId}\n`, 'utf8')
    })
    const uploaded = await expectAjaxSuccess(await uploadPromise, '/workflow/attachment')
    const attachmentId = String(uploaded.data?.attachmentId || '')
    expect(attachmentId).toMatch(/^[0-9a-f-]{36}$/i)
    expect(uploaded.data?.status).toBe('TEMP')
    const saveDraftPromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/draft/${draftId}`, 'PUT'))
    await pages.starter.getByRole('button', { name: '保存草稿', exact: true }).click()
    const savedDraft = await expectAjaxSuccess(
      await saveDraftPromise, `/workflow/process/draft/${draftId}`)
    expect(Number(savedDraft.data?.revisionNo)).toBe(2)
    expect(savedDraft.data?.variables?.requestTitle).toBe(subject)
    expect(savedDraft.data?.multiInstanceUserIds?.starterReview).toEqual([Number(approver.value)])
    const draftAttachment = await callWorkflowApi(
      pages.starter, 'GET', `/workflow/attachment/${attachmentId}`)
    expect(draftAttachment.data?.status).toBe('DRAFT')
    expect(draftAttachment.data?.draftId).toBeUndefined()

    // 陈旧 revision 必须稳定冲突，并且不得覆盖新字段或附件绑定。
    await callWorkflowApi(pages.starter, 'PUT', `/workflow/process/draft/${draftId}`, {
      expectedCode: 409,
      data: {
        expectedVersion: 1, businessKey: 'STALE-WRITE', variables: { requestTitle: 'stale' },
        multiInstanceUserIds: { starterReview: [Number(approver.value)] }
      }
    })
    const afterCasReject = await callWorkflowApi(
      pages.starter, 'GET', `/workflow/process/draft/${draftId}`)
    expect(Number(afterCasReject.data?.revisionNo)).toBe(2)
    expect(afterCasReject.data?.variables?.requestTitle).toBe(subject)

    // 其他职责账号无权读取本人草稿；拒绝后所有者数据保持一致。
    await callWorkflowApi(pages.auditor, 'GET', `/workflow/process/draft/${draftId}`, {
      expectedCode: 403
    })
    const afterUnauthorizedRead = await callWorkflowApi(
      pages.starter, 'GET', `/workflow/process/draft/${draftId}`)
    expect(afterUnauthorizedRead.data).toEqual(afterCasReject.data)

    // 正式必填失败不得创建实例、迁移附件或推进草稿 revision。
    await callWorkflowApi(pages.starter, 'POST', `/workflow/process/draft/${draftId}/submit`, {
      expectedCode: 400,
      data: {
        expectedVersion: 2, businessKey: initialBusinessKey, variables: { proofFiles: [attachmentId] },
        multiInstanceUserIds: { starterReview: [Number(approver.value)] }
      }
    })
    const beforeFormalSubmit = await callWorkflowApi(
      pages.starter, 'GET', `/workflow/process/draft/${draftId}`)
    expect(Number(beforeFormalSubmit.data?.revisionNo)).toBe(2)
    expect((await callWorkflowApi(pages.starter, 'GET', '/workflow/process/ownList', {
      query: { businessKey: initialBusinessKey, pageNum: 1, pageSize: 20 }
    })).total).toBe(0)

    const submitPromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/draft/${draftId}/submit`, 'POST'))
    await pages.starter.getByRole('button', { name: '正式提交', exact: true }).click()
    const submitted = await expectAjaxSuccess(
      await submitPromise, `/workflow/process/draft/${draftId}/submit`)
    const processInstanceId = String(submitted.data?.processInstanceId || submitted.data?.id || '')
    expect(processInstanceId, '正式提交必须返回真实 Flowable 实例主键').not.toBe('')
    resources.processInstanceIds.push(processInstanceId)
    await expect(pages.starter).toHaveURL(new RegExp(`/workflow/process-detail/${processInstanceId}(?:[/?]|$)`))

    // 网络重试使用同一提交契约只能返回原实例，工作台也只能出现一条实例。
    const repeated = await callWorkflowApi(
      pages.starter, 'POST', `/workflow/process/draft/${draftId}/submit`, {
        data: {
          expectedVersion: 2, businessKey: initialBusinessKey,
          variables: { requestTitle: subject, proofFiles: [attachmentId] },
          multiInstanceUserIds: { starterReview: [Number(approver.value)] }
        }
      })
    expect(String(repeated.data?.processInstanceId || repeated.data?.id || '')).toBe(processInstanceId)
    const owned = await callWorkflowApi(pages.starter, 'GET', '/workflow/process/ownList', {
      query: { businessKey: initialBusinessKey, pageNum: 1, pageSize: 20 }
    })
    expect(owned.total, '重复提交后只能存在一个实例').toBe(1)
    expect((owned.rows || []).filter(row => String(row.processInstanceId) === processInstanceId)).toHaveLength(1)
    const boundAttachment = await callWorkflowApi(
      pages.starter, 'GET', `/workflow/attachment/${attachmentId}`)
    expect(boundAttachment.data?.status).toBe('BOUND')
    expect(String(boundAttachment.data?.processInstanceId)).toBe(processInstanceId)
    boundAttachmentFixture = { attachmentId, processInstanceId }

    // 再从真实页面创建并删除草稿，删除后本人详情稳定返回 404。
    await pages.starter.goto(`/workflow/process-start/${encodeURIComponent(definition.definitionId)}?deploymentId=${encodeURIComponent(definition.deploymentId)}`)
    const deleteCreatePromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, '/workflow/process/draft', 'POST'))
    await pages.starter.getByRole('button', { name: '保存草稿', exact: true }).click()
    const deleteCandidate = await expectAjaxSuccess(await deleteCreatePromise, '/workflow/process/draft')
    const deleteDraftId = requireDraftId(deleteCandidate)
    const deletePromise = pages.starter.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/draft/${deleteDraftId}`, 'DELETE'))
    await pages.starter.getByRole('button', { name: '删除草稿', exact: true }).click()
    const confirmation = pages.starter.locator('.el-message-box')
    await confirmation.getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await deletePromise, `/workflow/process/draft/${deleteDraftId}`)
    await callWorkflowApi(pages.starter, 'GET', `/workflow/process/draft/${deleteDraftId}`, {
      expectedCode: 404
    })

    // V1 草稿创建后发布 V2，旧部署快照必须拒绝提交且不产生第二个实例。
    const staleCreated = await callWorkflowApi(pages.starter, 'POST', '/workflow/process/draft', {
      data: {
        processDefinitionId: definition.definitionId,
        businessKey: `STALE-${runId}`,
        variables: { requestTitle: `过期版本-${runId}` },
        multiInstanceUserIds: { starterReview: [Number(approver.value)] }
      }
    })
    staleDraftId = requireDraftId(staleCreated)
    staleDraftRevision = Number(staleCreated.data?.revisionNo)
    const savedVersionTwo = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/save', {
      data: { requestId: randomUUID(), modelId: model.modelId, bpmnXml, newVersion: true }
    })
    const versionTwoModelId = String(savedVersionTwo.data?.modelId || '')
    expect(versionTwoModelId, '另存新版本必须返回新的正式模型主键').not.toBe('')
    expect(versionTwoModelId).not.toBe(model.modelId)
    resources.modelIds.push(versionTwoModelId)
    const versionTwo = await callWorkflowApi(pages.designer, 'POST', '/workflow/model/deploy', {
      query: { modelId: versionTwoModelId }
    })
    const versionTwoDeploymentId = String(versionTwo.data?.deploymentId || '')
    expect(versionTwoDeploymentId).not.toBe('')
    resources.deploymentIds.push(versionTwoDeploymentId)
    await callWorkflowApi(
      pages.starter, 'POST', `/workflow/process/draft/${staleDraftId}/submit`, {
        expectedCode: 409,
        data: {
          expectedVersion: staleDraftRevision, businessKey: `STALE-${runId}`,
          variables: { requestTitle: `过期版本-${runId}` },
          multiInstanceUserIds: { starterReview: [Number(approver.value)] }
        }
      })
    const staleOwned = await callWorkflowApi(pages.starter, 'GET', '/workflow/process/ownList', {
      query: { businessKey: `STALE-${runId}`, pageNum: 1, pageSize: 20 }
    })
    expect(staleOwned.total, '过期版本提交失败不得产生部分实例').toBe(0)
    await callWorkflowApi(
      pages.starter, 'DELETE', `/workflow/process/draft/${staleDraftId}`, {
        query: { expectedVersion: staleDraftRevision }
      })
    staleDraftId = ''
  } catch (error) {
    primaryError = error
  } finally {
    if (staleDraftId && pages.starter && staleDraftRevision > 0) {
      await callWorkflowApi(
        pages.starter, 'DELETE', `/workflow/process/draft/${staleDraftId}`, {
          query: { expectedVersion: staleDraftRevision }
        }).catch(() => {})
    }
    const attachmentCleanupErrors = []
    if (boundAttachmentFixture) {
      await purgeBoundAttachmentFixture(boundAttachmentFixture).catch(error => {
        attachmentCleanupErrors.push(`清理绑定附件 fixture: ${String(error?.message || error)}`)
      })
    }
    const cleanupErrors = await cleanupWorkflowResources(pages, resources)
    const logoutErrors = await closeWorkflowRoleSessions(sessions)
    const finalErrors = [...attachmentCleanupErrors, ...cleanupErrors, ...logoutErrors]
    if (primaryError) {
      if (finalErrors.length) primaryError.message += `；清理失败：${finalErrors.join(' | ')}`
      throw primaryError
    }
    expect(finalErrors, '草稿 E2E 正式资源和登录态必须清理成功').toEqual([])
  }
})
