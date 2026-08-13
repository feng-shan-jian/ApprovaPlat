import { createHash } from 'node:crypto'
import { existsSync, lstatSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { test, expect } from '@playwright/test'
import { loginThroughUi, logoutThroughUi } from '../../../e2e/fixtures/workflow.js'
import { loadWorkflowAccounts } from '../../../e2e/support/environment.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { restartTestBackend } from '../../support/backend-control.js'
import { queryReadOnly } from '../../support/database.js'
import {
  clearFaultEvidence,
  readFaultEvidence,
  requireFaultRuntime,
  resetFaultModes,
  setAttachmentStorageMode,
  setDependencyMode,
  setFaultModes
} from '../../support/fault-control.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成依赖故障用例的唯一测试资产。
 * @param {string} caseId 可追踪用例编号。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,draftId:string,processInstanceId:string,attachmentIds:string[]}} 本轮资产登记。
 */
function scenarioAssets(caseId) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual')
    .replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_${caseId.replaceAll('-', '')}_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    modelName: `${prefix}_故障恢复审批`,
    modelKey: `${prefix}_model`,
    draftId: '',
    processInstanceId: '',
    attachmentIds: []
  }
}

/**
 * 生成外部 HTTP 连接器故障场景的端点和运行变量。
 * @param {string} caseId 可追踪用例编号。
 * @returns {ReturnType<typeof scenarioAssets> & {endpointName:string,endpointKey:string,requestPath:string,statusVariable:string}} HTTP 测试资产。
 */
function httpScenarioAssets(caseId) {
  const assets = scenarioAssets(caseId)
  return {
    ...assets,
    modelName: `${assets.prefix}_HTTP故障流程`,
    endpointName: `${assets.prefix}_HTTP端点`,
    endpointKey: `${assets.prefix}_endpoint`,
    requestPath: '/e2e/events',
    statusVariable: 'httpStatus',
    endpointDisabled: false,
    recoveryProcessInstanceId: ''
  }
}

/**
 * 转义由测试自身生成的唯一标识，供单条只读 SQL 安全使用。
 * @param {string} value 测试生成的草稿、实例或业务标识。
 * @returns {string} MySQL 字符串字面量正文。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 将页面或接口错误压缩为不含令牌、口令和超长正文的证据文本。
 * @param {unknown} value 原始错误文本。
 * @returns {string} 最多 800 字符的脱敏摘要。
 */
function safeText(value) {
  return String(value || '')
    .replace(/Bearer\s+\S+/giu, 'Bearer [REDACTED]')
    .replace(/([?&](?:password|token|authorization)=)[^&\s]+/giu, '$1[REDACTED]')
    .replace(/((?:password|token|authorization)\s*[:=]\s*)[^,;\s]+/giu, '$1[REDACTED]')
    .trim().slice(0, 800)
}

/**
 * 判断 Playwright Request 是否命中指定真实后端入口。
 * @param {import('@playwright/test').Request} request 浏览器发出的真实请求。
 * @param {string} endpoint 不含 `/dev-api` 前缀的后端路径。
 * @param {string} method 期望 HTTP 方法。
 * @returns {boolean} true 表示请求路径和方法同时匹配。
 */
function matchesRequestEndpoint(request, endpoint, method = 'GET') {
  const pathname = new URL(request.url()).pathname
  return pathname.endsWith(endpoint) && request.method() === method
}

/**
 * 在真实重新登录期间暂停 trace，防止登录请求、口令和新 Token 进入失败证据。
 * @param {import('@playwright/test').BrowserContext} context 当前职责角色浏览器上下文。
 * @param {import('@playwright/test').Page} page 用于重新登录的真实页面。
 * @param {{roleKey:string,username:string,password:string,requiredRoles?:string[]}} account 当前职责角色账号。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 登录完成且重新启动脱敏 trace 后结束。
 */
async function loginWithTraceSuppressed(context, page, account, testInfo) {
  await context.tracing.stop()
  try {
    await loginThroughUi(page, account)
  } finally {
    // 即使登录断言失败也恢复空白 trace 会话，确保统一清理可以安全停止并关闭上下文。
    await context.tracing.start({
      screenshots: false,
      snapshots: false,
      sources: false,
      title: `${testInfo.title}-workflow_starter-recovered`
    })
  }
}

/**
 * 通过真实配置页和 BPMN 设计器创建可发起的一级审批。
 * @param {import('@playwright/test').Page} page 设计者真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前用例资产。
 * @returns {Promise<void>} 分类、表单、模型、审批人规则和部署全部完成后结束。
 */
async function createFaultRecoveryModel(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({
    name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
  })
  await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
  await configuration.createModel({
    name: assets.modelName,
    key: assets.modelKey,
    categoryName: assets.categoryName,
    formName: assets.formName,
    description: `${assets.prefix} 真实依赖故障恢复`
  })
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.configureCandidateRole('流程审批人', '故障恢复审批')
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 通过真实配置页和 BPMN 设计器创建固定单一审批人的一级审批。
 * @param {import('@playwright/test').Page} page 设计者真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前 SMTP 用例资产。
 * @returns {Promise<void>} 分类、表单、模型、固定用户规则和部署全部完成后结束。
 */
async function createSmtpFaultRecoveryModel(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({
    name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
  })
  await configuration.createTextForm({ name: assets.formName, remark: assets.prefix })
  await configuration.createModel({
    name: assets.modelName,
    key: assets.modelKey,
    categoryName: assets.categoryName,
    formName: assets.formName,
    description: `${assets.prefix} SMTP 故障恢复`
  })
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.configureTaskParticipantRuleForElement({
    elementId: 'review',
    taskName: 'SMTP故障审批',
    ruleLabel: '固定用户',
    targetFieldLabel: '固定办理人',
    targetName: 'UI流程审批人'
  })
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 通过正式分类、表单、模型和 BPMN 设计器创建 HTTP ServiceTask 流程。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {ReturnType<typeof httpScenarioAssets>} assets 当前 HTTP 用例资产。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} HTTP 节点完成结构化配置、校验、保存和部署后结束。
 */
async function createHttpFaultModel(page, assets, testInfo) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({
    name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
  })
  await configuration.createTextForm({ name: assets.formName, remark: `${assets.prefix} HTTP故障` })
  await configuration.createModel({
    name: assets.modelName,
    key: assets.modelKey,
    categoryName: assets.categoryName,
    formName: assets.formName,
    description: `${assets.prefix} 外部HTTP故障恢复`
  })
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.replaceTaskWithServiceTask('review')
  await designer.configureHttpConnectorService({
    elementId: 'review',
    stableElementId: 'httpTask',
    taskName: '发送外部事件',
    endpointName: assets.endpointName,
    endpointRevision: 1,
    path: assets.requestPath,
    bodyVariable: '',
    statusVariable: assets.statusVariable
  }, testInfo)
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 通过真实配置页和 BPMN 设计器创建包含附件字段的一级审批。
 * @param {import('@playwright/test').Page} page 设计者真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前用例资产。
 * @returns {Promise<void>} 附件表单、模型、审批人规则和部署全部完成后结束。
 */
async function createAttachmentFaultModel(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({
    name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
  })
  await configuration.createTextAttachmentForm({
    name: assets.formName,
    remark: `${assets.prefix} 附件存储故障恢复`,
    textFieldName: 'requestTitle',
    textLabel: '申请主题',
    textPlaceholder: '请输入申请主题',
    attachmentFieldName: 'proofFiles',
    attachmentLabel: '证明附件'
  })
  await configuration.createModel({
    name: assets.modelName,
    key: assets.modelKey,
    categoryName: assets.categoryName,
    formName: assets.formName,
    description: `${assets.prefix} 附件存储故障恢复`
  })
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.configureCandidateRole('流程审批人', '附件存储故障审批')
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 从可发起列表进入包含附件字段的正式发起页。
 * @param {import('@playwright/test').Page} page 发起人真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前用例资产。
 * @returns {Promise<void>} 文本和唯一附件输入均完成渲染后结束。
 */
async function openAttachmentStartPage(page, assets) {
  const row = await new WorkflowWorkbenchPage(page)
    .filterRow('/office/create', '请输入流程名称', assets.modelName)
  await row.locator('button').first().click()
  await expect(page).toHaveURL(/\/workflow\/process-start\//u)
  await expect(page.getByPlaceholder('请输入申请主题')).toBeVisible()
  await expect(page.locator('.workflow-attachment-upload input[type="file"]'),
    '部署表单必须渲染唯一附件选择入口').toHaveCount(1)
}

/**
 * 通过浏览器文件选择入口上传内存文件，并保留真实 HTTP 与业务响应。
 * @param {import('@playwright/test').Page} page 当前正式发起页面。
 * @param {string} name 用户选择的文件名。
 * @param {Buffer} content 用户选择的文件字节。
 * @returns {Promise<{httpStatus:number,payload:object}>} 上传接口的真实状态码和 AjaxResult。
 */
async function uploadAttachmentAndObserve(page, name, content) {
  const input = page.locator('.workflow-attachment-upload input[type="file"]')
  await expect(input).toHaveCount(1)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/attachment', 'POST'))
  await input.setInputFiles({ name, mimeType: 'text/plain', buffer: content })
  const response = await responsePromise
  return {
    httpStatus: response.status(),
    payload: await response.json().catch(() => ({}))
  }
}

/**
 * 生成不含路径、身份或正文的附件上传网络证据。
 * @param {{httpStatus:number,payload:object}} outcome 附件上传真实响应。
 * @returns {{httpStatus:number,code:number|null,subCode:string,msg:string,attachmentId:string,status:string,fieldName:string,originalName:string,fileSize:number|null,sha256:string}} 脱敏附件响应摘要。
 */
function safeAttachmentAjaxSummary(outcome) {
  const data = outcome?.payload?.data || {}
  return {
    httpStatus: Number(outcome?.httpStatus || 0),
    code: Number.isFinite(Number(outcome?.payload?.code)) ? Number(outcome.payload.code) : null,
    subCode: safeText(outcome?.payload?.subCode || data.subCode),
    msg: safeText(outcome?.payload?.msg),
    attachmentId: safeText(data.attachmentId),
    status: safeText(data.status),
    fieldName: safeText(data.fieldName),
    originalName: safeText(data.originalName),
    fileSize: Number.isFinite(Number(data.fileSize)) ? Number(data.fileSize) : null,
    sha256: safeText(data.sha256)
  }
}

/**
 * 解析当前 runId 的隔离附件根，拒绝读取开发 profile 或符号链接目录。
 * @returns {string} 当前测试运行的 workflow-attachments 绝对路径。
 */
function resolveAttachmentStorageRoot() {
  const outputRootValue = process.env.FLOWABLE_E2E_OUTPUT_ROOT?.trim()
  const profileRootValue = process.env.FLOWABLE_E2E_PROFILE_ROOT?.trim()
  if (!outputRootValue || !profileRootValue) throw new Error('缺少附件故障隔离目录配置')
  const outputRoot = path.resolve(outputRootValue)
  const profileRoot = path.resolve(profileRootValue)
  const expectedProfileRoot = path.resolve(outputRoot, 'runtime', 'profile')
  if (profileRoot.toLowerCase() !== expectedProfileRoot.toLowerCase()) {
    throw new Error('附件故障 profile 不属于当前 runId 输出目录')
  }
  const storageRoot = path.resolve(profileRoot, 'workflow-attachments')
  if (!existsSync(storageRoot)) throw new Error('隔离附件根尚未由测试后端初始化')
  const status = lstatSync(storageRoot)
  if (!status.isDirectory() || status.isSymbolicLink()) {
    throw new Error('隔离附件根不是普通目录')
  }
  return storageRoot
}

/**
 * 只读汇总隔离附件根中的普通文件数量和字节数，拒绝跟随任何链接。
 * @param {string} storageRoot 已通过边界校验的隔离附件根。
 * @returns {{fileCount:number,totalBytes:number}} 不包含文件名、路径或正文的物理存储摘要。
 */
function attachmentFileSummary(storageRoot) {
  const pendingDirectories = [storageRoot]
  let fileCount = 0
  let totalBytes = 0
  while (pendingDirectories.length) {
    const directory = pendingDirectories.pop()
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const entryPath = path.join(directory, entry.name)
      if (entry.isSymbolicLink()) throw new Error('隔离附件目录包含禁止跟随的链接')
      if (entry.isDirectory()) {
        pendingDirectories.push(entryPath)
      } else if (entry.isFile()) {
        fileCount += 1
        totalBytes += statSync(entryPath).size
      } else {
        throw new Error('隔离附件目录包含非普通文件对象')
      }
    }
  }
  return { fileCount, totalBytes }
}

/**
 * 将数据库存储键解析到当前隔离附件根，并拒绝任何目录穿越。
 * @param {string} storageRoot 当前 runId 隔离附件根。
 * @param {string} storageKey 服务端生成的相对存储键。
 * @returns {string} 仍位于隔离根内部的附件文件绝对路径。
 */
function resolveStoredAttachmentPath(storageRoot, storageKey) {
  const filePath = path.resolve(storageRoot, ...String(storageKey).split('/'))
  if (!filePath.toLowerCase().startsWith(`${storageRoot.toLowerCase()}${path.sep}`)) {
    throw new Error('附件存储键越出当前隔离根')
  }
  return filePath
}

/**
 * 从上传列表点击文件名完成真实浏览器下载并核对原始字节。
 * @param {import('@playwright/test').Page} page 当前附件所有者页面。
 * @param {string} attachmentId 服务端附件 UUID。
 * @param {string} fileName 用户上传的原始文件名。
 * @param {Buffer} expectedBytes 期望下载的原始字节。
 * @returns {Promise<string>} 浏览器下载内容的 SHA-256。
 */
async function downloadAttachmentThroughUi(page, attachmentId, fileName, expectedBytes) {
  const item = page.locator('.workflow-attachment-upload .el-upload-list__item.is-success')
    .filter({ hasText: fileName })
  await expect(item, '恢复后的附件必须唯一成功回显').toHaveCount(1)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, `/workflow/attachment/${attachmentId}/content`, 'GET'))
  const downloadPromise = page.waitForEvent('download')
  await item.locator('.el-upload-list__item-name').click()
  const [response, download] = await Promise.all([responsePromise, downloadPromise])
  expect(response.status(), '恢复后的附件下载 HTTP 状态').toBe(200)
  expect(download.suggestedFilename()).toBe(fileName)
  const downloadPath = await download.path()
  expect(downloadPath, '浏览器下载必须产生可读取文件').not.toBeNull()
  const downloaded = readFileSync(downloadPath)
  expect(downloaded.equals(expectedBytes), '恢复后下载字节必须与上传字节一致').toBe(true)
  return createHash('sha256').update(downloaded).digest('hex')
}

/**
 * 从可发起流程列表进入真实发起页并填写本轮业务字段。
 * @param {import('@playwright/test').Page} page 发起人真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前用例资产。
 * @param {string} businessKey 唯一业务主键。
 * @param {string} subject 唯一表单值。
 * @returns {Promise<import('@playwright/test').Locator>} 已填写的正式表单输入框。
 */
async function openAndFillStartPage(page, assets, businessKey, subject) {
  const row = await new WorkflowWorkbenchPage(page)
    .filterRow('/office/create', '请输入流程名称', assets.modelName)
  await row.locator('button').first().click()
  await expect(page).toHaveURL(/\/workflow\/process-start\//u)
  await page.getByPlaceholder('可选').fill(businessKey)
  const formInput = page.locator('.workflow-form-renderer input:not([type="file"])').first()
  await expect(formInput).toBeVisible()
  await formInput.fill(subject)
  return formInput
}

/**
 * 通过真实“保存草稿”按钮建立后续故障提交所需的正式草稿。
 * @param {import('@playwright/test').Page} page 当前发起页面。
 * @returns {Promise<string>} 服务端生成的草稿 UUID。
 */
async function saveDraftThroughUi(page) {
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/process/draft', 'POST'))
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const payload = await expectAjaxSuccess(await responsePromise, '/workflow/process/draft')
  const draftId = String(payload?.data?.draftId || payload?.data?.id || '')
  expect(draftId, '故障窗口前必须建立正式草稿 UUID').toMatch(/^[0-9a-f-]{36}$/iu)
  await expect(page).toHaveURL(new RegExp(`/workflow/process-draft/${draftId}(?:[/?]|$)`, 'u'))
  return draftId
}

/**
 * 读取草稿、Flowable、附件和业务审计的只读一致性快照。
 * @param {string} draftId 当前正式草稿 UUID。
 * @param {string} businessKey 当前唯一业务主键。
 * @returns {{draftRows:string[][],draftAuditRows:string[][],historyProcessRows:string[][],runtimeProcessCount:number,runtimeTaskCount:number,historyTaskRows:string[][],attachmentRows:string[][]}} 不包含表单正文的业务快照。
 */
function readBusinessSnapshot(draftId, businessKey) {
  const escapedDraftId = sqlLiteral(draftId)
  const escapedBusinessKey = sqlLiteral(businessKey)
  const draftRows = queryReadOnly(
    `SELECT draft_status, revision_no, COALESCE(submitted_process_instance_id, ''), COALESCE(business_key, ''), SHA2(form_values, 256), DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s.%f') FROM wf_process_draft WHERE draft_id = '${escapedDraftId}'`
  )
  const draftAuditRows = queryReadOnly(
    `SELECT action_type, COALESCE(from_status, ''), to_status, COALESCE(from_revision, 0), to_revision, COALESCE(process_instance_id, '') FROM wf_process_draft_audit WHERE draft_id = '${escapedDraftId}' ORDER BY audit_id`
  )
  const historyProcessRows = queryReadOnly(
    `SELECT PROC_INST_ID_, PROC_DEF_ID_, COALESCE(BUSINESS_KEY_, ''), END_TIME_ IS NOT NULL, COALESCE(DELETE_REASON_, '') FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_ = '${escapedBusinessKey}' ORDER BY START_TIME_, PROC_INST_ID_`
  )
  const runtimeProcessRows = queryReadOnly(
    `SELECT COUNT(DISTINCT execution.PROC_INST_ID_) FROM ACT_RU_EXECUTION execution INNER JOIN ACT_HI_PROCINST history ON history.PROC_INST_ID_ = execution.PROC_INST_ID_ WHERE history.BUSINESS_KEY_ = '${escapedBusinessKey}'`
  )
  const runtimeTaskRows = queryReadOnly(
    `SELECT COUNT(*) FROM ACT_RU_TASK task INNER JOIN ACT_HI_PROCINST history ON history.PROC_INST_ID_ = task.PROC_INST_ID_ WHERE history.BUSINESS_KEY_ = '${escapedBusinessKey}'`
  )
  const historyTaskRows = queryReadOnly(
    `SELECT task.TASK_DEF_KEY_, task.NAME_, task.END_TIME_ IS NOT NULL, COALESCE(task.DELETE_REASON_, '') FROM ACT_HI_TASKINST task INNER JOIN ACT_HI_PROCINST history ON history.PROC_INST_ID_ = task.PROC_INST_ID_ WHERE history.BUSINESS_KEY_ = '${escapedBusinessKey}' ORDER BY task.START_TIME_, task.ID_`
  )
  const attachmentRows = queryReadOnly(
    `SELECT attachment_status, COUNT(*) FROM wf_attachment WHERE draft_id = '${escapedDraftId}' OR process_instance_id IN (SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_ = '${escapedBusinessKey}') GROUP BY attachment_status ORDER BY attachment_status`
  )
  return {
    draftRows,
    draftAuditRows,
    historyProcessRows,
    runtimeProcessCount: Number(runtimeProcessRows[0]?.[0] || 0),
    runtimeTaskCount: Number(runtimeTaskRows[0]?.[0] || 0),
    historyTaskRows,
    attachmentRows
  }
}

/**
 * 通过真实 UI 建立浏览器故障场景共用的模型、发起草稿和只读业务快照。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @param {string} caseId 可追踪用例编号。
 * @param {{designer:object|null,starter:object|null,approver:object|null}} sessions 调用方持有的角色会话登记。
 * @returns {Promise<{assets:ReturnType<typeof scenarioAssets>,businessKey:string,subject:string,formInput:import('@playwright/test').Locator,before:ReturnType<typeof readBusinessSnapshot>}>} 已保存正式草稿及其故障前快照。
 */
async function prepareBrowserDraftScenario(browser, testInfo, caseId, sessions) {
  const assets = scenarioAssets(caseId)
  const businessKey = `${assets.prefix}_业务主键`
  const subject = `${assets.prefix}_浏览器韧性申请`
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, businessKey }, null, 2)),
    contentType: 'application/json'
  })
  sessions.designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  await createFaultRecoveryModel(sessions.designer.page, assets)
  sessions.starter = await openRoleSession(browser, 'workflow_starter', testInfo)
  const formInput = await openAndFillStartPage(sessions.starter.page, assets, businessKey, subject)
  assets.draftId = await saveDraftThroughUi(sessions.starter.page)
  const before = readBusinessSnapshot(assets.draftId, businessKey)
  expect(before.draftRows).toHaveLength(1)
  expect(before.draftRows[0].slice(0, 4)).toEqual(['ACTIVE', '1', '', businessKey])
  expect(before.draftAuditRows.map(row => row[0])).toEqual(['CREATED'])
  expect(before.historyProcessRows).toHaveLength(0)
  return { assets, businessKey, subject, formInput, before }
}

/**
 * 核对恢复提交只生成一份正式草稿终态、一份 Flowable 实例和一份活动任务。
 * @param {ReturnType<typeof readBusinessSnapshot>} snapshot 恢复提交后的数据库只读快照。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前场景资产。
 * @param {string} businessKey 当前唯一业务主键。
 * @returns {void} 任一唯一性或状态不一致时通过 Playwright 断言失败。
 */
function expectUniqueSubmittedSnapshot(snapshot, assets, businessKey) {
  expect(snapshot.draftRows).toHaveLength(1)
  expect(snapshot.draftRows[0].slice(0, 4))
    .toEqual(['SUBMITTED', '2', assets.processInstanceId, businessKey])
  expect(snapshot.draftAuditRows.map(row => row[0])).toEqual(['CREATED', 'SUBMITTED'])
  expect(snapshot.historyProcessRows).toHaveLength(1)
  expect(snapshot.historyProcessRows[0][0]).toBe(assets.processInstanceId)
  expect(snapshot.runtimeProcessCount, '恢复提交只能产生一个运行实例').toBe(1)
  expect(snapshot.runtimeTaskCount, '恢复提交只能产生一个活动审批任务').toBe(1)
  expect(snapshot.historyTaskRows).toHaveLength(1)
  expect(snapshot.attachmentRows).toHaveLength(0)
}

/**
 * 由真实审批人认领并完成恢复后的唯一任务，核对实例与运行任务全部收口。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @param {{designer:object|null,starter:object|null,approver:object|null}} sessions 调用方持有的角色会话登记。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前场景资产。
 * @param {string} businessKey 当前唯一业务主键。
 * @returns {Promise<ReturnType<typeof readBusinessSnapshot>>} 审批完成后的业务快照。
 */
async function completeRecoveredBrowserProcess(browser, testInfo, sessions, assets, businessKey) {
  sessions.approver = await openRoleSession(browser, 'workflow_approver', testInfo)
  const workbench = new WorkflowWorkbenchPage(sessions.approver.page)
  await workbench.claimProcess(assets.modelName)
  await workbench.approveProcess(assets.modelName, `${assets.prefix}_浏览器故障恢复通过`)
  const completed = readBusinessSnapshot(assets.draftId, businessKey)
  expect(completed.historyProcessRows).toHaveLength(1)
  expect(completed.historyProcessRows[0][3], '恢复后的唯一流程实例必须自然结束').toBe('1')
  expect(completed.runtimeProcessCount).toBe(0)
  expect(completed.runtimeTaskCount).toBe(0)
  expect(completed.historyTaskRows).toHaveLength(1)
  return completed
}

/**
 * 读取已认领任务在 Flowable 运行、历史、变量和结构化 comment 中的副作用摘要。
 * @param {string} processInstanceId 正式流程实例主键。
 * @param {string} taskId 陈旧页面绑定的 Flowable 任务主键。
 * @returns {{runtimeExecutionCount:number,runtimeTaskRows:string[][],historyProcessRows:string[][],historyTaskRows:string[][],commentRows:string[][],historicVariableCount:number}} 不含意见正文的任务状态快照。
 */
function readStaleTaskSnapshot(processInstanceId, taskId) {
  const instance = sqlLiteral(processInstanceId)
  const task = sqlLiteral(taskId)
  return {
    runtimeExecutionCount: Number(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${instance}'`
    )[0]?.[0] || 0),
    runtimeTaskRows: queryReadOnly(
      `SELECT ID_, COALESCE(ASSIGNEE_, ''), COALESCE(OWNER_, '') FROM ACT_RU_TASK WHERE PROC_INST_ID_='${instance}' ORDER BY ID_`
    ),
    historyProcessRows: queryReadOnly(
      `SELECT PROC_INST_ID_, END_TIME_ IS NOT NULL, COALESCE(DELETE_REASON_, '') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${instance}'`
    ),
    historyTaskRows: queryReadOnly(
      `SELECT ID_, END_TIME_ IS NOT NULL, COALESCE(DELETE_REASON_, ''), COALESCE(ASSIGNEE_, '') FROM ACT_HI_TASKINST WHERE ID_='${task}'`
    ),
    commentRows: queryReadOnly(
      `SELECT TYPE_, COUNT(*) FROM ACT_HI_COMMENT WHERE PROC_INST_ID_='${instance}' GROUP BY TYPE_ ORDER BY TYPE_`
    ),
    historicVariableCount: Number(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_VARINST WHERE PROC_INST_ID_='${instance}'`
    )[0]?.[0] || 0)
  }
}

/**
 * 执行浏览器离线提交、零业务副作用、恢复后唯一实例和真实审批闭环。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 离线与恢复阶段全部核验后结束。
 */
async function runBrowserOfflineScenario(browser, testInfo) {
  requireFaultRuntime()
  const sessions = { designer: null, starter: null, approver: null }
  let offline = false
  let failed = true
  let prepared
  try {
    prepared = await prepareBrowserDraftScenario(browser, testInfo, 'UI-FAULT-011', sessions)
    const { assets, businessKey, subject, formInput, before } = prepared
    const context = sessions.starter.page.context()
    await context.setOffline(true)
    offline = true
    const outcome = await clickSubmitAndObserve(sessions.starter.page, assets.draftId)
    const feedback = await visibleFailureFeedback(sessions.starter.page)
    const afterFailure = readBusinessSnapshot(assets.draftId, businessKey)
    await testInfo.attach('browser-offline-evidence.json', {
      body: Buffer.from(JSON.stringify({ outcome, feedback, before, afterFailure }, null, 2)),
      contentType: 'application/json'
    })
    expect(outcome.kind, '浏览器离线必须形成真实网络失败').toBe('requestfailed')
    expect(afterFailure, '离线提交不得改变草稿、实例、任务、附件和审计').toEqual(before)
    await expect(formInput).toHaveValue(subject)
    expect(safeText([...feedback, outcome.failureText].join(' | ')), '离线失败必须提供可见反馈')
      .toMatch(/后端接口连接异常|网络|ERR_INTERNET_DISCONNECTED|失败/iu)

    await context.setOffline(false)
    offline = false
    const recovered = await submitAfterRecovery(sessions.starter.page, assets.draftId)
    assets.processInstanceId = recovered.processInstanceId
    const afterRecovery = readBusinessSnapshot(assets.draftId, businessKey)
    expectUniqueSubmittedSnapshot(afterRecovery, assets, businessKey)
    const completed = await completeRecoveredBrowserProcess(
      browser, testInfo, sessions, assets, businessKey)
    await testInfo.attach('browser-offline-recovery.json', {
      body: Buffer.from(JSON.stringify({ attempts: recovered.attempts, afterRecovery, completed }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    if (offline && sessions.starter) await sessions.starter.page.context().setOffline(false).catch(() => {})
    await Promise.allSettled([
      sessions.approver?.close(failed), sessions.starter?.close(failed), sessions.designer?.close(failed)
    ])
    if (prepared) {
      await testInfo.attach('asset-result.json', {
        body: Buffer.from(JSON.stringify(prepared.assets, null, 2)), contentType: 'application/json'
      })
    }
  }
}

/**
 * 执行 Axios 正式超时窗口、零业务副作用及取消网络故障后的唯一恢复提交。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 超时和恢复审批链路全部核验后结束。
 */
async function runBrowserTimeoutScenario(browser, testInfo) {
  requireFaultRuntime()
  const sessions = { designer: null, starter: null, approver: null }
  const submitPattern = /\/workflow\/process\/draft\/[^/?]+\/submit(?:\?.*)?$/u
  let routeActive = false
  let failed = true
  let prepared
  const timeoutRoute = async route => {
    // 保留真实请求并超过前端 10 秒 Axios 超时，不返回 mock 响应也不让请求到达业务服务。
    await new Promise(resolve => setTimeout(resolve, 10_750))
    await route.abort('timedout').catch(() => {})
  }
  try {
    prepared = await prepareBrowserDraftScenario(browser, testInfo, 'UI-FAULT-012', sessions)
    const { assets, businessKey, subject, formInput, before } = prepared
    await sessions.starter.page.route(submitPattern, timeoutRoute)
    routeActive = true
    const outcome = await clickSubmitAndObserve(sessions.starter.page, assets.draftId)
    const feedback = await visibleFailureFeedback(sessions.starter.page)
    const afterFailure = readBusinessSnapshot(assets.draftId, businessKey)
    await testInfo.attach('browser-timeout-evidence.json', {
      body: Buffer.from(JSON.stringify({ outcome, feedback, before, afterFailure }, null, 2)),
      contentType: 'application/json'
    })
    expect(outcome.kind, '浏览器请求超时必须形成网络失败').toBe('requestfailed')
    expect(afterFailure, '请求超时不得改变草稿、实例、任务、附件和审计').toEqual(before)
    await expect(formInput).toHaveValue(subject)
    expect(safeText([...feedback, outcome.failureText].join(' | ')), '请求超时必须提供可见反馈')
      .toMatch(/请求超时|timeout|TIMED_OUT|失败/iu)

    await sessions.starter.page.unroute(submitPattern, timeoutRoute)
    routeActive = false
    const recovered = await submitAfterRecovery(sessions.starter.page, assets.draftId)
    assets.processInstanceId = recovered.processInstanceId
    const afterRecovery = readBusinessSnapshot(assets.draftId, businessKey)
    expectUniqueSubmittedSnapshot(afterRecovery, assets, businessKey)
    const completed = await completeRecoveredBrowserProcess(
      browser, testInfo, sessions, assets, businessKey)
    await testInfo.attach('browser-timeout-recovery.json', {
      body: Buffer.from(JSON.stringify({ attempts: recovered.attempts, afterRecovery, completed }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    if (routeActive && sessions.starter) {
      await sessions.starter.page.unroute(submitPattern, timeoutRoute).catch(() => {})
    }
    await Promise.allSettled([
      sessions.approver?.close(failed), sessions.starter?.close(failed), sessions.designer?.close(failed)
    ])
    if (prepared) {
      await testInfo.attach('asset-result.json', {
        body: Buffer.from(JSON.stringify(prepared.assets, null, 2)), contentType: 'application/json'
      })
    }
  }
}

/**
 * 使用真实鼠标双击正式提交按钮，验证页面写锁和后端幂等共同保持单请求单实例。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 双击请求数量、唯一实例和审批闭环全部核验后结束。
 */
async function runDoubleSubmitScenario(browser, testInfo) {
  requireFaultRuntime()
  const sessions = { designer: null, starter: null, approver: null }
  let failed = true
  let prepared
  try {
    prepared = await prepareBrowserDraftScenario(browser, testInfo, 'UI-FAULT-013', sessions)
    const { assets, businessKey } = prepared
    const page = sessions.starter.page
    const endpoint = `/workflow/process/draft/${assets.draftId}/submit`
    const requests = []
    const responses = []
    const requestListener = request => {
      if (matchesRequestEndpoint(request, endpoint, 'POST')) {
        requests.push({ method: request.method(), path: endpoint })
      }
    }
    const responseListener = response => {
      if (matchesEndpoint(response, endpoint, 'POST')) responses.push({ status: response.status(), path: endpoint })
    }
    page.on('request', requestListener)
    page.on('response', responseListener)
    try {
      const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, 'POST'))
      const button = page.getByRole('button', { name: '正式提交', exact: true })
      const box = await button.boundingBox()
      expect(box, '正式提交按钮必须具有可点击边界').not.toBeNull()
      await page.mouse.dblclick(box.x + box.width / 2, box.y + box.height / 2, { delay: 20 })
      const payload = await expectAjaxSuccess(await responsePromise, endpoint)
      assets.processInstanceId = safeAjaxSummary(payload).processInstanceId
      expect(assets.processInstanceId, '双击提交成功响应必须包含实例主键').not.toBe('')
      await expect(page).toHaveURL(new RegExp(`/workflow/process-detail/${assets.processInstanceId}(?:[/?]|$)`, 'u'))
      await page.waitForTimeout(500)
    } finally {
      page.off('request', requestListener)
      page.off('response', responseListener)
    }
    const afterSubmit = readBusinessSnapshot(assets.draftId, businessKey)
    await testInfo.attach('double-submit-evidence.json', {
      body: Buffer.from(JSON.stringify({ requests, responses, afterSubmit }, null, 2)),
      contentType: 'application/json'
    })
    expect(requests, '真实双击不得穿透页面写锁形成第二个提交请求').toHaveLength(1)
    expect(responses).toHaveLength(1)
    expectUniqueSubmittedSnapshot(afterSubmit, assets, businessKey)
    await completeRecoveredBrowserProcess(browser, testInfo, sessions, assets, businessKey)
    failed = false
  } finally {
    await Promise.allSettled([
      sessions.approver?.close(failed), sessions.starter?.close(failed), sessions.designer?.close(failed)
    ])
    if (prepared) {
      await testInfo.attach('asset-result.json', {
        body: Buffer.from(JSON.stringify(prepared.assets, null, 2)), contentType: 'application/json'
      })
    }
  }
}

/**
 * 通过同一浏览器上下文另一标签真实注销使当前草稿会话失效，再重新登录并恢复提交。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 会话失效零副作用、输入保留和恢复审批全部核验后结束。
 */
async function runSessionExpiryScenario(browser, testInfo) {
  requireFaultRuntime()
  const sessions = { designer: null, starter: null, approver: null }
  const account = loadWorkflowAccounts().workflow_starter
  let authPage
  let loggedOut = false
  let failed = true
  let prepared
  try {
    prepared = await prepareBrowserDraftScenario(browser, testInfo, 'UI-FAULT-014', sessions)
    const { assets, businessKey, subject, formInput, before } = prepared
    authPage = await sessions.starter.page.context().newPage()
    await logoutThroughUi(authPage, 'workflow_starter')
    loggedOut = true
    const outcome = await clickSubmitAndObserve(sessions.starter.page, assets.draftId)
    const feedback = await visibleFailureFeedback(sessions.starter.page)
    const afterFailure = readBusinessSnapshot(assets.draftId, businessKey)
    const sessionDialogVisible = await sessions.starter.page.locator('.el-message-box:visible')
      .filter({ hasText: /登录状态已过期|重新登录/u }).isVisible().catch(() => false)
    await testInfo.attach('session-expiry-evidence.json', {
      body: Buffer.from(JSON.stringify({ outcome, feedback, sessionDialogVisible, before, afterFailure }, null, 2)),
      contentType: 'application/json'
    })
    expect(outcome.payload?.code, '真实注销后的旧页面提交必须返回 401').toBe(401)
    expect(sessionDialogVisible, '会话失效必须显示可操作的重新登录提示').toBe(true)
    expect(afterFailure, '会话失效提交不得改变草稿、实例、任务、附件和审计').toEqual(before)
    await expect(formInput).toHaveValue(subject)
    await dismissSessionExpiredDialog(sessions.starter.page)

    await loginWithTraceSuppressed(sessions.starter.page.context(), authPage, account, testInfo)
    loggedOut = false
    const recovered = await submitAfterRecovery(sessions.starter.page, assets.draftId)
    assets.processInstanceId = recovered.processInstanceId
    const afterRecovery = readBusinessSnapshot(assets.draftId, businessKey)
    expectUniqueSubmittedSnapshot(afterRecovery, assets, businessKey)
    await completeRecoveredBrowserProcess(browser, testInfo, sessions, assets, businessKey)
    failed = false
  } finally {
    if (loggedOut && authPage) {
      await loginWithTraceSuppressed(sessions.starter.page.context(), authPage, account, testInfo)
        .then(() => { loggedOut = false })
        .catch(() => {})
    }
    await authPage?.close().catch(() => {})
    await Promise.allSettled([
      sessions.approver?.close(failed), sessions.starter?.close(failed), sessions.designer?.close(failed)
    ])
    if (prepared) {
      await testInfo.attach('asset-result.json', {
        body: Buffer.from(JSON.stringify(prepared.assets, null, 2)), contentType: 'application/json'
      })
    }
  }
}

/**
 * 验证正式草稿在浏览器刷新和后退恢复后仍由服务端快照稳定回显并可唯一提交。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 刷新、后退、提交和审批闭环全部核验后结束。
 */
async function runRefreshBackScenario(browser, testInfo) {
  requireFaultRuntime()
  const sessions = { designer: null, starter: null, approver: null }
  let failed = true
  let prepared
  try {
    prepared = await prepareBrowserDraftScenario(browser, testInfo, 'UI-FAULT-015', sessions)
    const { assets, businessKey, subject, before } = prepared
    const page = sessions.starter.page
    const draftEndpoint = `/workflow/process/draft/${assets.draftId}`
    // 导航可能在响应事件后立即释放 Network response body，必须在响应到达时同步启动正文读取。
    const reloadPayload = page.waitForResponse(response => matchesEndpoint(response, draftEndpoint, 'GET'))
      .then(response => expectAjaxSuccess(response, draftEndpoint))
    await page.reload({ waitUntil: 'domcontentloaded' })
    await reloadPayload
    await expect(page.getByPlaceholder('可选')).toHaveValue(businessKey)
    await expect(page.locator('.workflow-form-renderer input:not([type="file"])').first()).toHaveValue(subject)
    const afterReload = readBusinessSnapshot(assets.draftId, businessKey)
    expect(afterReload, '刷新只允许重新读取服务端草稿，不得产生写副作用').toEqual(before)

    await page.goto('/office/draft')
    await expect(page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: businessKey })).toHaveCount(1)
    const backPayload = page.waitForResponse(response => matchesEndpoint(response, draftEndpoint, 'GET'))
      .then(response => expectAjaxSuccess(response, draftEndpoint))
    await page.goBack({ waitUntil: 'domcontentloaded' })
    await backPayload
    await expect(page).toHaveURL(new RegExp(`/workflow/process-draft/${assets.draftId}(?:[/?]|$)`, 'u'))
    await expect(page.getByPlaceholder('可选')).toHaveValue(businessKey)
    await expect(page.locator('.workflow-form-renderer input:not([type="file"])').first()).toHaveValue(subject)
    const afterBack = readBusinessSnapshot(assets.draftId, businessKey)
    expect(afterBack, '浏览器后退恢复只允许读取原草稿，不得产生写副作用').toEqual(before)
    await testInfo.attach('refresh-back-evidence.json', {
      body: Buffer.from(JSON.stringify({ before, afterReload, afterBack }, null, 2)),
      contentType: 'application/json'
    })

    const recovered = await submitAfterRecovery(page, assets.draftId)
    assets.processInstanceId = recovered.processInstanceId
    const afterRecovery = readBusinessSnapshot(assets.draftId, businessKey)
    expectUniqueSubmittedSnapshot(afterRecovery, assets, businessKey)
    await completeRecoveredBrowserProcess(browser, testInfo, sessions, assets, businessKey)
    failed = false
  } finally {
    await Promise.allSettled([
      sessions.approver?.close(failed), sessions.starter?.close(failed), sessions.designer?.close(failed)
    ])
    if (prepared) {
      await testInfo.attach('asset-result.json', {
        body: Buffer.from(JSON.stringify(prepared.assets, null, 2)), contentType: 'application/json'
      })
    }
  }
}

/**
 * 在两个真实标签同时打开同一审批任务，首标签完成后验证陈旧标签被拒绝且无重复副作用。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 首次完成、陈旧拒绝和 Flowable 前后快照一致后结束。
 */
async function runStaleTaskScenario(browser, testInfo) {
  requireFaultRuntime()
  const assets = scenarioAssets('UI-FAULT-016')
  const sessions = { designer: null, starter: null, approver: null }
  let stalePage
  let failed = true
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  try {
    sessions.designer = await openRoleSession(browser, 'workflow_designer', testInfo)
    await createFaultRecoveryModel(sessions.designer.page, assets)
    sessions.starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(sessions.starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_陈旧任务申请`)
    sessions.approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const workbench = new WorkflowWorkbenchPage(sessions.approver.page)
    await workbench.claimProcess(assets.modelName)
    const taskRows = queryReadOnly(
      `SELECT ID_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${sqlLiteral(assets.processInstanceId)}'`
    )
    expect(taskRows, '陈旧任务场景必须只有一个已认领活动任务').toHaveLength(1)
    const taskId = taskRows[0][0]

    const todoRow = await workbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await todoRow.locator('button').first().click()
    await expect(sessions.approver.page).toHaveURL(/\/workflow\/process-detail\//u)
    const detailUrl = sessions.approver.page.url()
    stalePage = await sessions.approver.page.context().newPage()
    await stalePage.goto(detailUrl)
    await expect(stalePage.getByRole('button', { name: '通过', exact: true })).toBeVisible()

    await sessions.approver.page.getByRole('button', { name: '通过', exact: true }).click()
    const firstDialog = sessions.approver.page.getByRole('dialog', { name: '通过任务' })
    await firstDialog.getByLabel('办理意见').fill(`${assets.prefix}_首标签通过`)
    await stalePage.getByRole('button', { name: '通过', exact: true }).click()
    const staleDialog = stalePage.getByRole('dialog', { name: '通过任务' })
    await staleDialog.getByLabel('办理意见').fill(`${assets.prefix}_陈旧标签重复通过`)

    const firstResponsePromise = sessions.approver.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await firstDialog.getByRole('button', { name: '确认', exact: true }).click()
    await expectAjaxSuccess(await firstResponsePromise, '/workflow/task/complete')
    await expect(sessions.approver.page.getByText('通过任务成功', { exact: true })).toBeVisible()
    const afterFirst = readStaleTaskSnapshot(assets.processInstanceId, taskId)
    expect(afterFirst.runtimeTaskRows).toHaveLength(0)
    expect(afterFirst.historyProcessRows).toEqual([[assets.processInstanceId, '1', '']])
    expect(afterFirst.historyTaskRows[0]?.[1], '首标签完成后历史任务必须结束').toBe('1')

    const staleResponsePromise = stalePage.waitForResponse(response => matchesEndpoint(
      response, '/workflow/task/complete', 'POST'))
    await staleDialog.getByRole('button', { name: '确认', exact: true }).click()
    const staleResponse = await staleResponsePromise
    const stalePayload = await staleResponse.json().catch(() => ({}))
    const staleSummary = safeAjaxSummary(stalePayload)
    expect(staleSummary.code, '陈旧任务完成请求不得返回业务成功').not.toBe(200)
    await expect(staleDialog.locator('.el-alert'), '陈旧任务拒绝必须在原办理窗口显示错误语义').toBeVisible()
    const visibleError = safeText(await staleDialog.locator('.el-alert').textContent())
    const afterStale = readStaleTaskSnapshot(assets.processInstanceId, taskId)
    expect(afterStale, '陈旧任务拒绝后 Flowable 历史、变量和 comment 不得产生重复副作用')
      .toEqual(afterFirst)
    await testInfo.attach('stale-task-evidence.json', {
      body: Buffer.from(JSON.stringify({ taskId, staleSummary, visibleError, afterFirst, afterStale }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await stalePage?.close().catch(() => {})
    await Promise.allSettled([
      sessions.approver?.close(failed), sessions.starter?.close(failed), sessions.designer?.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

/**
 * 读取提交接口的成功或失败运维审计数量，不把运维失败日志误算为业务副作用。
 * @param {string} draftId 当前正式草稿 UUID。
 * @returns {string[][]} 按成功或失败状态聚合的运维审计行。
 */
function readOperationAuditSummary(draftId) {
  const url = sqlLiteral(`/workflow/process/draft/${draftId}/submit`)
  return queryReadOnly(
    `SELECT status, COUNT(*) FROM sys_oper_log WHERE oper_url = '${url}' GROUP BY status ORDER BY status`
  )
}

/**
 * 只保留 AjaxResult 中可公开的状态、子码、消息和实例主键。
 * @param {object} payload 后端响应 JSON。
 * @returns {{code:number|null,subCode:string,msg:string,processInstanceId:string}} 脱敏网络摘要。
 */
function safeAjaxSummary(payload) {
  return {
    code: Number.isFinite(Number(payload?.code)) ? Number(payload.code) : null,
    subCode: safeText(payload?.subCode || payload?.data?.subCode),
    msg: safeText(payload?.msg),
    processInstanceId: safeText(
      payload?.data?.processInstanceId || payload?.data?.id || payload?.data?.procInsId
      || payload?.data?.processInstance?.id || payload?.data?.processInstance?.processInstanceId
    )
  }
}

/**
 * 通过审批人个人中心真实保存 SMTP 测试所需的正式用户资料。
 * @param {import('@playwright/test').Page} page 审批人真实登录页面。
 * @param {{email:string,phone:string}} profile 本轮唯一邮箱和合法测试手机号。
 * @returns {Promise<void>} 资料保存接口成功并完成页面回显后结束。
 */
async function saveNotificationRecipientProfile(page, profile) {
  await page.goto('/user/profile')
  const profileForm = page.locator('.profile-form')
  await expect(profileForm).toBeVisible()
  const nickname = profileForm.locator('.el-form-item').filter({ hasText: '用户昵称' }).locator('input')
  if (!(await nickname.inputValue()).trim()) await nickname.fill('UI流程审批人')
  await profileForm.locator('.el-form-item').filter({ hasText: '手机号码' }).locator('input').fill(profile.phone)
  await profileForm.locator('.el-form-item').filter({ hasText: '邮箱' }).locator('input').fill(profile.email)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, '/system/user/profile', 'PUT'))
  await profileForm.getByRole('button', { name: '保存资料', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/system/user/profile')
  await expect(page.getByText('个人资料已更新', { exact: true })).toBeVisible()
  await expect(profileForm.locator('.el-form-item').filter({ hasText: '邮箱' }).locator('input'))
    .toHaveValue(profile.email)
}

/**
 * 在通知策略弹窗中选择一个单值选项。
 * @param {import('@playwright/test').Page} page 当前管理员页面。
 * @param {import('@playwright/test').Locator} dialog 通知策略弹窗。
 * @param {string} label 表单项标签。
 * @param {string} option 目标选项文案。
 * @returns {Promise<void>} 用户可见选项完成点击后结束。
 */
async function selectNotificationPolicyOption(page, dialog, label, option) {
  const matchingLabels = dialog.locator('.el-form-item__label').filter({ hasText: label })
  await expect(matchingLabels, `${label} 标签必须唯一`).toHaveCount(1)
  await matchingLabels.first().locator('..').locator('.el-select').click()
  await page.getByRole('option', { name: option, exact: true }).click()
}

/**
 * 通过通知管理页创建只含 EMAIL 通道且单次尝试的流程级策略。
 * @param {import('@playwright/test').Page} page 工作流管理员真实登录页面。
 * @param {{processKey:string,title:string,content:string,maxAttempts?:number}} policy 本轮流程 key、模板和最大尝试次数。
 * @returns {Promise<void>} 正式策略保存并在列表唯一回显后结束。
 */
async function createEmailFailurePolicy(page, policy) {
  await page.goto('/workflow/notification')
  await page.getByRole('button', { name: '新增策略', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增通知策略' })
  await selectNotificationPolicyOption(page, dialog, '作用域', '指定流程')
  await selectNotificationPolicyOption(page, dialog, '事件', 'TASK_ARRIVED')
  await dialog.getByLabel('流程 key').fill(policy.processKey)
  const inboxCheckbox = dialog.getByRole('checkbox', { name: '站内', exact: true })
  const emailCheckbox = dialog.getByRole('checkbox', { name: '邮件', exact: true })
  await dialog.locator('.el-checkbox').filter({ hasText: '站内' }).click()
  await expect(inboxCheckbox).not.toBeChecked()
  await dialog.locator('.el-checkbox').filter({ hasText: '邮件' }).click()
  await expect(emailCheckbox).toBeChecked()
  const attemptsInput = dialog.locator('.el-form-item').filter({ hasText: '最大尝试' }).locator('input')
  await attemptsInput.fill(String(policy.maxAttempts || 1))
  await attemptsInput.press('Tab')
  await dialog.getByLabel('标题模板').fill(policy.title)
  await dialog.getByLabel('正文模板').fill(policy.content)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/notification/policies', 'PUT'))
  await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, '/workflow/notification/policies')
  await expect(page.getByText('通知策略已保存', { exact: true })).toBeVisible()
  const row = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: policy.processKey })
  await expect(row, 'SMTP 故障策略必须唯一回显').toHaveCount(1)
  await expect(row).toContainText('TASK_ARRIVED')
  await expect(row).toContainText('EMAIL')
  await expect(row).toContainText('启用')
}

/**
 * 等待本轮 EMAIL outbox 进入目标状态，并读取脱敏数据库快照。
 * @param {string} processInstanceId 正式流程实例主键。
 * @param {'DEAD_LETTER'|'PROCESSED'} targetStatus 预期可靠投递状态。
 * @returns {Promise<string[][]>} outbox 状态、尝试次数、错误摘要和租约字段快照。
 */
async function waitForEmailOutbox(processInstanceId, targetStatus) {
  const instanceId = sqlLiteral(processInstanceId)
  await expect.poll(() => queryReadOnly(
    `SELECT COUNT(*) FROM wf_notification_outbox WHERE process_instance_id='${instanceId}' AND event_type='TASK_ARRIVED' AND channel='EMAIL' AND status='${targetStatus}'`
  )[0]?.[0] || '0', {
    message: `EMAIL outbox 必须进入 ${targetStatus}`,
    timeout: 30_000,
    intervals: [250, 500, 1000]
  }).toBe('1')
  return queryReadOnly(
    `SELECT outbox_id,status,delivery_cycle,attempt_count,total_attempt_count,max_attempts,COALESCE(last_error_code,''),COALESCE(last_error_summary,''),processed_time IS NOT NULL,COALESCE(lease_owner,''),lease_expires_at IS NULL FROM wf_notification_outbox WHERE process_instance_id='${instanceId}' AND event_type='TASK_ARRIVED' AND channel='EMAIL'`
  )
}

/**
 * 从投递运维页真实点击指定 EMAIL 死信的补偿重试按钮。
 * @param {import('@playwright/test').Page} page 工作流管理员真实登录页面。
 * @param {string} processInstanceId 需要补偿的流程实例主键。
 * @param {string} outboxId 死信 outbox 主键。
 * @returns {Promise<void>} 补偿接口成功且页面给出正式成功反馈后结束。
 */
async function compensateEmailDeadLetterThroughUi(page, processInstanceId, outboxId) {
  await page.goto('/workflow/notification')
  await page.getByRole('tab', { name: '投递运维', exact: true }).click()
  const row = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: processInstanceId })
    .filter({ hasText: 'EMAIL' })
  await expect(row, '投递运维页必须唯一显示本轮 EMAIL 死信').toHaveCount(1)
  await expect(row).toContainText('死信')
  await expect(row).toContainText('SMTP 投递失败')
  const endpoint = `/workflow/notification/outbox/${outboxId}/compensate`
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, 'POST'))
  await row.getByRole('button', { name: '补偿重试' }).click()
  const confirmation = page.locator('.el-message-box')
  await expect(confirmation).toContainText(`确认重新投递通知 ${outboxId} 吗？`)
  await confirmation.getByRole('button', { name: '确定', exact: true }).click()
  await expectAjaxSuccess(await responsePromise, endpoint)
  await expect(page.getByText('死信已重新进入投递队列', { exact: true })).toBeVisible()
}

/**
 * 转义动态敏感值，供测试证据泄漏检查使用。
 * @param {string} value 邮箱、手机号或业务前缀。
 * @returns {string} 可安全放入 RegExp 的字面文本。
 */
function regexLiteral(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}

/**
 * 执行 SMTP 拒绝或超时、死信、UI 补偿和恢复唯一投递的完整真实链路。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @param {'reject'|'timeout'} smtpMode 需要模拟的 SMTP 网络行为。
 * @param {string} caseId 稳定故障用例编号。
 * @returns {Promise<void>} 邮件最终唯一成功投递、审计闭环并恢复所有辅助模式后结束。
 */
async function runSmtpRecoveryScenario(browser, testInfo, smtpMode, caseId) {
  requireFaultRuntime()
  const assets = scenarioAssets(caseId)
  const uniqueSuffix = assets.prefix.toLowerCase().replaceAll('_', '').slice(-24)
  const profile = {
    email: `${uniqueSuffix}@example.test`,
    phone: `139${String(Date.now()).slice(-8)}`
  }
  const policy = {
    processKey: assets.modelKey,
    title: `${assets.prefix}_邮件通知`,
    content: `${assets.prefix} {{processName}} {{taskName}}`
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, smtpMode }, null, 2)),
    contentType: 'application/json'
  })

  let designer
  let approver
  let admin
  let starter
  let failed = true
  try {
    await resetFaultModes()
    await clearFaultEvidence()
    designer = await openRoleSession(browser, 'workflow_designer', testInfo)
    await createSmtpFaultRecoveryModel(designer.page, assets)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await saveNotificationRecipientProfile(approver.page, profile)
    const userRows = queryReadOnly(
      `SELECT user_id,email='${sqlLiteral(profile.email)}',phonenumber='${sqlLiteral(profile.phone)}',status,del_flag FROM sys_user WHERE user_name='e2e_ui_wf_approver'`
    )
    expect(userRows, '审批人正式资料必须唯一持久化').toHaveLength(1)
    expect(userRows[0].slice(1), '邮箱、手机号和账号状态必须满足真实邮件投递前置')
      .toEqual(['1', '1', '0', '0'])

    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    await createEmailFailurePolicy(admin.page, policy)
    await setFaultModes({ smtpMode })
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_SMTP故障申请`)

    const deadLetterRows = await waitForEmailOutbox(assets.processInstanceId, 'DEAD_LETTER')
    expect(deadLetterRows, 'SMTP 故障只允许形成一条 EMAIL outbox').toHaveLength(1)
    const outboxId = deadLetterRows[0][0]
    expect(deadLetterRows[0].slice(1), '单次 SMTP 故障必须形成已释放租约的脱敏死信').toEqual([
      'DEAD_LETTER', '1', '1', '1', '1', 'SMTP_DELIVERY_FAILED', 'SMTP 投递失败', '1', '', '1'
    ])
    const deadLetterAudit = queryReadOnly(
      `SELECT action_type,delivery_cycle,attempt_no,total_attempt_no,COALESCE(from_status,''),to_status,actor_type,COALESCE(error_code,''),detail FROM wf_notification_delivery_audit WHERE outbox_id=${Number(outboxId)} ORDER BY audit_id`
    )
    expect(deadLetterAudit.map(row => row[0]), '故障周期必须包含登记、领取和死信审计')
      .toEqual(['ENQUEUE', 'CLAIM', 'DEAD_LETTER'])
    const privateEvidencePattern = new RegExp([
      regexLiteral(profile.email), regexLiteral(profile.phone), regexLiteral(assets.prefix)
    ].join('|'), 'iu')
    expect(JSON.stringify(deadLetterAudit), '投递审计不得包含邮箱、手机号或业务模板正文')
      .not.toMatch(privateEvidencePattern)

    const failureEvidence = await readFaultEvidence()
    expect(failureEvidence.messages, '拒绝或超时不得被辅助 SMTP 记为已接收消息').toHaveLength(0)
    expect(failureEvidence.faultTransitions).toContainEqual(expect.objectContaining({
      dependency: 'smtp', mode: smtpMode
    }))
    await testInfo.attach('smtp-failure-evidence.json', {
      body: Buffer.from(JSON.stringify({
        smtpMode, outbox: deadLetterRows, audit: deadLetterAudit, helper: failureEvidence
      }, null, 2)),
      contentType: 'application/json'
    })

    await setFaultModes({ smtpMode: 'accept' })
    await compensateEmailDeadLetterThroughUi(admin.page, assets.processInstanceId, outboxId)
    const processedRows = await waitForEmailOutbox(assets.processInstanceId, 'PROCESSED')
    expect(processedRows[0].slice(1), '补偿周期必须清除错误并提交唯一成功投递').toEqual([
      'PROCESSED', '2', '1', '2', '1', '', '', '1', '', '1'
    ])
    const finalAudit = queryReadOnly(
      `SELECT action_type,delivery_cycle,attempt_no,total_attempt_no,COALESCE(from_status,''),to_status,actor_type,COALESCE(error_code,''),detail FROM wf_notification_delivery_audit WHERE outbox_id=${Number(outboxId)} ORDER BY audit_id`
    )
    expect(finalAudit.map(row => row[0]), '补偿后必须形成第二周期领取和成功投递审计')
      .toEqual(['ENQUEUE', 'CLAIM', 'DEAD_LETTER', 'COMPENSATE', 'CLAIM', 'DELIVER'])
    expect(JSON.stringify(finalAudit), '最终投递审计仍不得包含邮箱、手机号或业务模板正文')
      .not.toMatch(privateEvidencePattern)
    const recoveryEvidence = await readFaultEvidence()
    expect(recoveryEvidence.messages, '补偿后 SMTP 只允许接收一封邮件').toHaveLength(1)
    expect(Number(recoveryEvidence.messages[0].bytes), 'SMTP 接收证据必须只保留正数字节数')
      .toBeGreaterThan(0)
    expect(recoveryEvidence.faultTransitions).toContainEqual(expect.objectContaining({
      dependency: 'smtp', mode: 'accept'
    }))
    await testInfo.attach('smtp-recovery-evidence.json', {
      body: Buffer.from(JSON.stringify({
        smtpMode, outbox: processedRows, audit: finalAudit, helper: recoveryEvidence
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await resetFaultModes()
    await Promise.allSettled([
      starter?.close(failed), admin?.close(failed), approver?.close(failed), designer?.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({ ...assets, smtpMode }, null, 2)),
      contentType: 'application/json'
    })
  }
}

/**
 * 等待本轮 EMAIL outbox 被当前后端 worker 领取，并返回不含通知正文的租约快照。
 * @param {string} processInstanceId 正式流程实例主键。
 * @returns {Promise<string[][]>} 唯一 outbox 的状态、次数、租约持有者和到期状态。
 */
async function waitForDeliveringEmailOutbox(processInstanceId) {
  const instanceId = sqlLiteral(processInstanceId)
  await expect.poll(() => queryReadOnly(
    `SELECT COUNT(*) FROM wf_notification_outbox WHERE process_instance_id='${instanceId}' AND event_type='TASK_ARRIVED' AND channel='EMAIL' AND status='DELIVERING' AND lease_owner IS NOT NULL AND lease_expires_at>current_timestamp(3)`
  )[0]?.[0] || '0', {
    message: 'EMAIL outbox 必须由旧后端 worker 领取有效租约',
    timeout: 30_000,
    intervals: [100, 200, 300]
  }).toBe('1')
  return queryReadOnly(
    `SELECT outbox_id,status,delivery_cycle,attempt_count,total_attempt_count,max_attempts,lease_owner,lease_expires_at>current_timestamp(3),revision FROM wf_notification_outbox WHERE process_instance_id='${instanceId}' AND event_type='TASK_ARRIVED' AND channel='EMAIL'`
  )
}

/**
 * 通过投递运维页面刷新并核对本轮通知已由恢复后的 worker 唯一送达。
 * @param {import('@playwright/test').Page} page 工作流管理员真实登录页面。
 * @param {string} processInstanceId 正式流程实例主键。
 * @returns {Promise<void>} 页面唯一行回显已送达后结束。
 */
async function expectEmailDeliveredThroughUi(page, processInstanceId) {
  await page.goto('/workflow/notification')
  await page.getByRole('tab', { name: '投递运维', exact: true }).click()
  const row = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: processInstanceId })
    .filter({ hasText: 'EMAIL' })
  await expect(row, '投递运维页必须唯一显示本轮 EMAIL outbox').toHaveCount(1)
  await expect(row).toContainText('已送达')
  await expect(row).toContainText('2 / 3')
  await expect(row.getByRole('button', { name: '补偿重试' })).toHaveCount(0)
}

/**
 * 执行通知租约期间后端崩溃、新 worker 接管和唯一邮件送达的完整真实链路。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 新 worker 接管过期租约、邮件只接收一次且审批闭环完成后结束。
 */
async function runNotificationLeaseRestartScenario(browser, testInfo) {
  requireFaultRuntime()
  const assets = scenarioAssets('UI-FAULT-006')
  const uniqueSuffix = assets.prefix.toLowerCase().replaceAll('_', '').slice(-24)
  const profile = {
    email: `${uniqueSuffix}@example.test`,
    phone: `139${String(Date.now()).slice(-8)}`
  }
  const policy = {
    processKey: assets.modelKey,
    title: `${assets.prefix}_租约接管通知`,
    content: `${assets.prefix} {{processName}} {{taskName}}`
  }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, scenario: 'notification-lease-restart' }, null, 2)),
    contentType: 'application/json'
  })

  let designer
  let approver
  let admin
  let starter
  let failed = true
  try {
    await resetFaultModes()
    await clearFaultEvidence()
    designer = await openRoleSession(browser, 'workflow_designer', testInfo)
    await createSmtpFaultRecoveryModel(designer.page, assets)
    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    await saveNotificationRecipientProfile(approver.page, profile)
    admin = await openRoleSession(browser, 'workflow_admin', testInfo)
    await createEmailFailurePolicy(admin.page, { ...policy, maxAttempts: 3 })

    // timeout 模式让旧 worker 在持有数据库租约时阻塞于真实 SMTP socket，避免测试伪造 DELIVERING 状态。
    await setFaultModes({ smtpMode: 'timeout' })
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_通知租约重启申请`)
    const deliveringRows = await waitForDeliveringEmailOutbox(assets.processInstanceId)
    expect(deliveringRows, '租约重启场景只能形成一条 EMAIL outbox').toHaveLength(1)
    expect(deliveringRows[0].slice(1, 6), '旧 worker 的首次投递必须处于有效租约内')
      .toEqual(['DELIVERING', '1', '1', '1', '3'])
    expect(deliveringRows[0][6], '旧 worker 标识必须存在').toMatch(/^notification-/u)
    expect(deliveringRows[0][7], '旧 worker 租约必须尚未过期').toBe('1')
    const firstWorkerId = deliveringRows[0][6]
    await expect.poll(async () => Number((await readFaultEvidence()).activeConnections?.smtp || 0), {
      message: '辅助 SMTP 必须观察到旧 worker 的真实阻塞连接',
      timeout: 10_000,
      intervals: [100, 200]
    }).toBeGreaterThan(0)

    // 新连接先恢复 accept，已有 timeout socket 仍保持阻塞，随后强制终止旧后端模拟进程崩溃。
    await setFaultModes({ smtpMode: 'accept' })
    const restartEvidence = restartTestBackend()
    expect(restartEvidence.oldProcessId).not.toBe(restartEvidence.newProcessId)
    const processedRows = await waitForEmailOutbox(assets.processInstanceId, 'PROCESSED')
    expect(processedRows[0].slice(1), '新 worker 必须在第二次尝试接管并清理租约').toEqual([
      'PROCESSED', '1', '2', '2', '3', '', '', '1', '', '1'
    ])
    const outboxId = processedRows[0][0]
    const finalAudit = queryReadOnly(
      `SELECT action_type,delivery_cycle,attempt_no,total_attempt_no,COALESCE(from_status,''),to_status,actor_type,actor_id,COALESCE(error_code,''),detail FROM wf_notification_delivery_audit WHERE outbox_id=${Number(outboxId)} ORDER BY audit_id`
    )
    expect(finalAudit.map(row => row[0]), '租约接管必须保留旧领取并由新 worker 再领取后送达')
      .toEqual(['ENQUEUE', 'CLAIM', 'CLAIM', 'DELIVER'])
    const claimWorkers = finalAudit.filter(row => row[0] === 'CLAIM').map(row => row[7])
    expect(claimWorkers).toHaveLength(2)
    expect(claimWorkers[0]).toBe(firstWorkerId)
    expect(claimWorkers[1]).toMatch(/^notification-/u)
    expect(claimWorkers[1]).not.toBe(firstWorkerId)
    expect(finalAudit.map(row => row.slice(0, 7)), '两次领取必须保持同周期递增的尝试序号').toEqual([
      ['ENQUEUE', '1', '0', '0', '', 'PENDING', 'SYSTEM'],
      ['CLAIM', '1', '1', '1', 'PENDING', 'DELIVERING', 'SYSTEM'],
      ['CLAIM', '1', '2', '2', 'DELIVERING', 'DELIVERING', 'SYSTEM'],
      ['DELIVER', '1', '2', '2', 'DELIVERING', 'PROCESSED', 'SYSTEM']
    ])
    const privateEvidencePattern = new RegExp([
      regexLiteral(profile.email), regexLiteral(profile.phone), regexLiteral(assets.prefix)
    ].join('|'), 'iu')
    expect(JSON.stringify(finalAudit), '租约接管审计不得包含邮箱、手机号或模板正文')
      .not.toMatch(privateEvidencePattern)
    const helperEvidence = await readFaultEvidence()
    expect(helperEvidence.messages, '后端重启恢复后 SMTP 只允许接收一封邮件').toHaveLength(1)
    expect(Number(helperEvidence.messages[0].bytes)).toBeGreaterThan(0)
    expect(helperEvidence.faultTransitions).toEqual(expect.arrayContaining([
      expect.objectContaining({ dependency: 'smtp', mode: 'timeout' }),
      expect.objectContaining({ dependency: 'smtp', mode: 'accept' })
    ]))
    await expectEmailDeliveredThroughUi(admin.page, assets.processInstanceId)

    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_租约恢复后审批通过`)
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${sqlLiteral(assets.processInstanceId)}'`
    )[0]?.[0]).toBe('0')
    await testInfo.attach('notification-lease-restart-evidence.json', {
      body: Buffer.from(JSON.stringify({
        restart: restartEvidence,
        beforeRestart: deliveringRows,
        afterRecovery: processedRows,
        audit: finalAudit,
        helper: helperEvidence
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await resetFaultModes()
    await Promise.allSettled([
      starter?.close(failed), admin?.close(failed), approver?.close(failed), designer?.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({ ...assets, scenario: 'notification-lease-restart' }, null, 2)),
      contentType: 'application/json'
    })
  }
}

/**
 * 查询当前实例唯一 HTTP 调用台账，返回不含业务正文的运行字段。
 * @param {string} processInstanceId 正式流程实例主键。
 * @returns {string[][]} 调用主键、状态、次数、结果、错误、租约和稳定幂等键。
 */
function httpInvocationRows(processInstanceId) {
  return queryReadOnly(
    `SELECT invocation_id,status,attempt_count,COALESCE(result_code,''),COALESCE(error_code,''),claim_token IS NULL,lease_expires_at IS NULL,target_revision,idempotency_key FROM wf_connector_invocation WHERE process_instance_id='${sqlLiteral(processInstanceId)}' AND connector_type='HTTP'`
  )
}

/**
 * 查询实例在四类 Flowable 运行作业表中的当前位置。
 * @param {string} processInstanceId 正式流程实例主键。
 * @returns {Array<{table:string,count:number,retries:string,dueDate:string,elementId:string,exception:string}>} 作业状态摘要。
 */
function httpJobSummary(processInstanceId) {
  const instanceId = sqlLiteral(processInstanceId)
  const tables = [
    ['ACT_RU_JOB', 'active', 'COALESCE(RETRIES_,\'\')'],
    ['ACT_RU_TIMER_JOB', 'timer', 'COALESCE(RETRIES_,\'\')'],
    ['ACT_RU_SUSPENDED_JOB', 'suspended', 'COALESCE(RETRIES_,\'\')'],
    // Flowable 8 的 dead-letter 表不保留 RETRIES_ 列，终态由所在表本身证明。
    ['ACT_RU_DEADLETTER_JOB', 'dead-letter', "''"]
  ]
  return tables.map(([table, label, retriesExpression]) => {
    const rows = queryReadOnly(
      `SELECT ${retriesExpression},COALESCE(DATE_FORMAT(DUEDATE_,'%Y-%m-%dT%H:%i:%s.%f'),''),COALESCE(ELEMENT_ID_,''),LEFT(COALESCE(EXCEPTION_MSG_,''),240) FROM ${table} WHERE PROCESS_INSTANCE_ID_='${instanceId}'`
    )
    return {
      table: label,
      count: rows.length,
      retries: rows[0]?.[0] || '',
      dueDate: rows[0]?.[1] || '',
      elementId: rows[0]?.[2] || '',
      exception: safeText(rows[0]?.[3] || '')
    }
  })
}

/**
 * 等待 HTTP 连接器至少完成指定次数，并返回唯一台账。
 * @param {string} processInstanceId 正式流程实例主键。
 * @param {number} attempts 期望最小外部调用次数。
 * @param {number} timeout 最大等待毫秒数。
 * @returns {Promise<string[][]>} 达到次数门限后的唯一调用台账。
 */
async function waitForHttpAttempts(processInstanceId, attempts, timeout = 45_000) {
  await expect.poll(() => Number(httpInvocationRows(processInstanceId)[0]?.[2] || 0), {
    message: `HTTP 连接器必须完成至少 ${attempts} 次正式尝试`,
    timeout,
    intervals: [100, 250, 500, 1_000]
  }).toBeGreaterThanOrEqual(attempts)
  const rows = httpInvocationRows(processInstanceId)
  expect(rows, '每个 HTTP ServiceTask 执行只允许形成一条幂等台账').toHaveLength(1)
  return rows
}

/**
 * 等待实例进入唯一 Flowable dead-letter 作业并返回作业摘要。
 * @param {string} processInstanceId 正式流程实例主键。
 * @param {number} timeout 最大等待毫秒数。
 * @returns {Promise<ReturnType<typeof httpJobSummary>>} 作业终态摘要。
 */
async function waitForHttpDeadLetter(processInstanceId, timeout = 60_000) {
  await expect.poll(() => Number(queryReadOnly(
    `SELECT COUNT(*) FROM ACT_RU_DEADLETTER_JOB WHERE PROCESS_INSTANCE_ID_='${sqlLiteral(processInstanceId)}'`
  )[0]?.[0] || 0), {
    message: '永久 HTTP 故障必须在默认重试耗尽后进入唯一 dead-letter',
    timeout,
    intervals: [250, 500, 1_000]
  }).toBe(1)
  return httpJobSummary(processInstanceId)
}

/**
 * 等待 HTTP 流程实例自然结束，并证明全部运行作业与执行树均清空。
 * @param {string} processInstanceId 正式流程实例主键。
 * @param {number} timeout 最大等待毫秒数。
 * @returns {Promise<void>} 历史结束时间存在且运行执行、四类作业全部为零后结束。
 */
async function waitForHttpProcessCompletion(processInstanceId, timeout = 60_000) {
  const instanceId = sqlLiteral(processInstanceId)
  await expect.poll(() => queryReadOnly(
    `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${instanceId}'`
  )[0]?.[0] || '0', {
    message: 'HTTP 连接器成功后流程实例必须自然结束',
    timeout,
    intervals: [100, 250, 500, 1_000]
  }).toBe('1')
  expect(queryReadOnly(
    `SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${instanceId}'`
  )[0]?.[0]).toBe('0')
  expect(httpJobSummary(processInstanceId).reduce((total, row) => total + row.count, 0),
    'HTTP 流程结束后不得残留任何运行作业').toBe(0)
}

/**
 * 从本机 HTTP 辅助服务读取本实例请求，并核对请求元数据没有敏感正文。
 * @param {number} expectedCount 期望外部请求总数。
 * @returns {Promise<object[]>} 只含方法、路径、字节数、模式和幂等键的请求证据。
 */
async function expectHttpRequestEvidence(expectedCount) {
  await expect.poll(async () => (await readFaultEvidence()).requests.length, {
    message: `辅助 HTTP 服务必须观察到 ${expectedCount} 次正式请求`,
    timeout: 15_000,
    intervals: [100, 250, 500]
  }).toBe(expectedCount)
  const requests = (await readFaultEvidence()).requests
  expect(requests).toHaveLength(expectedCount)
  for (const request of requests) {
    expect(Object.keys(request).sort()).toEqual([
      'bodyBytes', 'idempotencyKey', 'method', 'mode', 'path', 'receivedAt'
    ])
    expect(request.method).toBe('POST')
    expect(request.path).toBe('/e2e/events')
    expect(request.idempotencyKey).toMatch(/^[0-9a-f]{64}$/u)
  }
  return requests
}

/**
 * 执行 HTTP 端点和模型的 UI 前置，并返回四个职责会话。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @param {ReturnType<typeof httpScenarioAssets>} assets 当前 HTTP 用例资产。
 * @param {{admin:object|null,designer:object|null,starter:object|null}} sessions 由调用方持有的会话登记，前置中途失败时仍可清理。
 * @returns {Promise<void>} 已完成端点、部署并建立发起人会话后结束。
 */
async function prepareHttpFaultScenario(browser, testInfo, assets, sessions) {
  sessions.admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  const endpointConfiguration = new WorkflowConfigurationPage(sessions.admin.page)
  await endpointConfiguration.createHttpEndpoint({
    name: assets.endpointName,
    key: assets.endpointKey,
    baseUrl: 'http://127.0.0.1:18082',
    pathPrefix: '/e2e',
    connectTimeoutMs: 500,
    requestTimeoutMs: 1_000
  })
  sessions.designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  await createHttpFaultModel(sessions.designer.page, assets, testInfo)
  sessions.starter = await openRoleSession(browser, 'workflow_starter', testInfo)
}

/**
 * 执行临时 5xx 在 Flowable 默认重试周期内恢复的完整 UI 链路。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 同一实例复用幂等键、第二次成功且自然结束后结束。
 */
async function runHttpTransientRecoveryScenario(browser, testInfo) {
  requireFaultRuntime()
  const assets = httpScenarioAssets('UI-FAULT-007')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, scenario: 'http-5xx-transient-recovery' }, null, 2)),
    contentType: 'application/json'
  })
  const sessions = { admin: null, designer: null, starter: null }
  let failed = true
  try {
    await resetFaultModes()
    await clearFaultEvidence()
    await prepareHttpFaultScenario(browser, testInfo, assets, sessions)
    await setFaultModes({ httpMode: 'server-error' })
    assets.processInstanceId = await new WorkflowWorkbenchPage(sessions.starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_临时5xx`)
    const firstRows = await waitForHttpAttempts(assets.processInstanceId, 1)
    expect(firstRows[0].slice(1, 8), '首次 5xx 必须形成已释放租约的失败台账').toEqual([
      'FAILED', '1', '500', 'HTTP_STATUS', '1', '1', '1'
    ])
    await setFaultModes({ httpMode: 'ok' })
    await waitForHttpProcessCompletion(assets.processInstanceId)
    const finalRows = httpInvocationRows(assets.processInstanceId)
    expect(finalRows[0].slice(1, 8), '同一实例恢复后必须提交第二次尝试和成功终态').toEqual([
      'SUCCESS', '2', '200', '', '1', '1', '1'
    ])
    const requests = await expectHttpRequestEvidence(2)
    expect(new Set(requests.map(request => request.idempotencyKey)).size,
      'Flowable 重试必须复用同一稳定幂等键').toBe(1)
    expect(requests.map(request => request.mode)).toEqual(['server-error', 'ok'])
    await testInfo.attach('http-transient-recovery-evidence.json', {
      body: Buffer.from(JSON.stringify({ invocation: finalRows, jobs: httpJobSummary(assets.processInstanceId), requests }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await resetFaultModes()
    if (sessions?.admin) {
      await new WorkflowConfigurationPage(sessions.admin.page).disableHttpEndpoint(assets.endpointKey)
      assets.endpointDisabled = true
    }
    await Promise.allSettled([
      sessions?.starter?.close(failed), sessions?.designer?.close(failed), sessions?.admin?.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

/**
 * 执行永久 HTTP 故障进入 dead-letter，并在辅助服务恢复后通过新 UI 实例验证端点恢复。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @param {'timeout'|'disconnect'} mode 外部服务网络故障模式。
 * @param {'TIMEOUT'|'IO_ERROR'} expectedError 连接器稳定错误码。
 * @param {string} caseId 可追踪用例编号。
 * @returns {Promise<void>} 原实例保持 dead-letter、新实例唯一成功后结束。
 */
async function runHttpPermanentFaultScenario(browser, testInfo, mode, expectedError, caseId) {
  requireFaultRuntime()
  const assets = httpScenarioAssets(caseId)
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, mode, scenario: 'http-dead-letter-new-instance-recovery' }, null, 2)),
    contentType: 'application/json'
  })
  const sessions = { admin: null, designer: null, starter: null }
  let failed = true
  try {
    await resetFaultModes()
    await clearFaultEvidence()
    await prepareHttpFaultScenario(browser, testInfo, assets, sessions)
    await setFaultModes({ httpMode: mode })
    assets.processInstanceId = await new WorkflowWorkbenchPage(sessions.starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_${mode}`)
    const deadLetterJobs = await waitForHttpDeadLetter(assets.processInstanceId)
    const failedRows = httpInvocationRows(assets.processInstanceId)
    expect(failedRows).toHaveLength(1)
    expect(failedRows[0][1]).toBe('FAILED')
    expect(Number(failedRows[0][2]), '默认 Flowable 作业必须完成至少一次重试').toBeGreaterThan(1)
    expect(failedRows[0][3]).toBe('')
    expect(failedRows[0][4]).toBe(expectedError)
    expect(failedRows[0].slice(5, 8)).toEqual(['1', '1', '1'])
    expect(deadLetterJobs.find(job => job.table === 'dead-letter')).toEqual(expect.objectContaining({
      count: 1,
      elementId: 'httpTask'
    }))
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${sqlLiteral(assets.processInstanceId)}'`
    )[0]?.[0], 'dead-letter 原实例必须保留活动执行树供运维处理').not.toBe('0')
    const failedRequests = await expectHttpRequestEvidence(Number(failedRows[0][2]))
    expect(new Set(failedRequests.map(request => request.idempotencyKey)).size,
      '永久故障重试必须保持同一幂等键').toBe(1)
    expect(failedRequests.every(request => request.mode === mode)).toBe(true)

    await setFaultModes({ httpMode: 'ok' })
    await clearFaultEvidence()
    assets.recoveryProcessInstanceId = await new WorkflowWorkbenchPage(sessions.starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_恢复新实例`)
    await waitForHttpProcessCompletion(assets.recoveryProcessInstanceId)
    const recoveryRows = httpInvocationRows(assets.recoveryProcessInstanceId)
    expect(recoveryRows[0].slice(1, 8), '恢复后的新实例必须首次唯一成功').toEqual([
      'SUCCESS', '1', '200', '', '1', '1', '1'
    ])
    const recoveryRequests = await expectHttpRequestEvidence(1)
    expect(recoveryRequests[0].mode).toBe('ok')
    expect(recoveryRequests[0].idempotencyKey).not.toBe(failedRows[0][8])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_DEADLETTER_JOB WHERE PROCESS_INSTANCE_ID_='${sqlLiteral(assets.processInstanceId)}'`
    )[0]?.[0], '新实例恢复不得隐式修改原 dead-letter').toBe('1')
    await testInfo.attach(`http-${mode}-dead-letter-evidence.json`, {
      body: Buffer.from(JSON.stringify({
        failedInvocation: failedRows,
        failedJobs: deadLetterJobs,
        failedRequests,
        recoveryInvocation: recoveryRows,
        recoveryRequests
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await resetFaultModes()
    if (sessions?.admin) {
      await new WorkflowConfigurationPage(sessions.admin.page).disableHttpEndpoint(assets.endpointKey)
      assets.endpointDisabled = true
    }
    await Promise.allSettled([
      sessions?.starter?.close(failed), sessions?.designer?.close(failed), sessions?.admin?.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

/**
 * 验证外部服务重复返回相同业务事件标识时，连接器仍只产生一次调用台账和一次流程终态。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 实例。
 * @param {import('@playwright/test').TestInfo} testInfo 当前用例证据上下文。
 * @returns {Promise<void>} 单请求、单台账、单历史实例和零运行残留后结束。
 */
async function runHttpDuplicateResponseScenario(browser, testInfo) {
  requireFaultRuntime()
  const assets = httpScenarioAssets('UI-FAULT-010')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, scenario: 'http-duplicate-response-idempotency' }, null, 2)),
    contentType: 'application/json'
  })
  const sessions = { admin: null, designer: null, starter: null }
  let failed = true
  try {
    await resetFaultModes()
    await clearFaultEvidence()
    await prepareHttpFaultScenario(browser, testInfo, assets, sessions)
    await setFaultModes({ httpMode: 'duplicate' })
    assets.processInstanceId = await new WorkflowWorkbenchPage(sessions.starter.page)
      .startProcess(assets.modelName, `${assets.prefix}_重复响应`)
    await waitForHttpProcessCompletion(assets.processInstanceId)
    const rows = httpInvocationRows(assets.processInstanceId)
    expect(rows[0].slice(1, 8), '重复业务响应的单实例必须首次成功且租约清理').toEqual([
      'SUCCESS', '1', '200', '', '1', '1', '1'
    ])
    const requests = await expectHttpRequestEvidence(1)
    expect(requests[0].mode).toBe('duplicate')
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${sqlLiteral(assets.processInstanceId)}'`
    )[0]?.[0]).toBe('1')
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM wf_connector_invocation WHERE process_instance_id='${sqlLiteral(assets.processInstanceId)}'`
    )[0]?.[0]).toBe('1')
    await testInfo.attach('http-duplicate-response-evidence.json', {
      body: Buffer.from(JSON.stringify({ invocation: rows, requests }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    await resetFaultModes()
    if (sessions?.admin) {
      await new WorkflowConfigurationPage(sessions.admin.page).disableHttpEndpoint(assets.endpointKey)
      assets.endpointDisabled = true
    }
    await Promise.allSettled([
      sessions?.starter?.close(failed), sessions?.designer?.close(failed), sessions?.admin?.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

/**
 * 点击一次正式提交并观察真实 HTTP 响应或浏览器网络失败。
 * @param {import('@playwright/test').Page} page 当前草稿编辑页。
 * @param {string} draftId 当前正式草稿 UUID。
 * @param {'pointer'|'keyboard'} activation 真实用户使用鼠标或键盘激活按钮的方式。
 * @returns {Promise<{kind:'response'|'requestfailed'|'timeout',httpStatus:number|null,payload:ReturnType<typeof safeAjaxSummary>|null,failureText:string}>} 单次提交网络摘要。
 */
async function clickSubmitAndObserve(page, draftId, activation = 'pointer') {
  expect(['pointer', 'keyboard'], '正式提交只允许鼠标或键盘两种真实激活方式').toContain(activation)
  const endpoint = `/workflow/process/draft/${draftId}/submit`
  let responseListener
  let failedListener
  let timeoutHandle
  const observation = new Promise(resolve => {
    let settled = false
    const finish = result => {
      if (settled) return
      settled = true
      clearTimeout(timeoutHandle)
      page.off('response', responseListener)
      page.off('requestfailed', failedListener)
      resolve(result)
    }
    responseListener = async response => {
      if (!matchesEndpoint(response, endpoint, 'POST')) return
      const payload = await response.json().catch(() => ({}))
      finish({
        kind: 'response',
        httpStatus: response.status(),
        payload: safeAjaxSummary(payload),
        failureText: ''
      })
    }
    failedListener = request => {
      const pathname = new URL(request.url()).pathname
      if (request.method() !== 'POST' || !pathname.endsWith(endpoint)) return
      finish({
        kind: 'requestfailed',
        httpStatus: null,
        payload: null,
        failureText: safeText(request.failure()?.errorText)
      })
    }
    page.on('response', responseListener)
    page.on('requestfailed', failedListener)
    timeoutHandle = setTimeout(() => finish({
      kind: 'timeout', httpStatus: null, payload: null, failureText: '20 秒内未观察到提交响应'
    }), 20_000)
  })

  const submitButton = page.getByRole('button', { name: '正式提交', exact: true })
  await expect(submitButton).toBeEnabled()
  if (activation === 'keyboard') {
    // 后端泄漏的超长异常会让 Element Plus Toast 覆盖按钮；键盘激活仍是用户可执行的正式入口。
    await submitButton.press('Enter')
  } else {
    await submitButton.click()
  }
  const outcome = await observation
  await expect(submitButton, '失败请求结束后正式提交按钮必须恢复可操作').toBeEnabled({ timeout: 25_000 })
  return outcome
}

/**
 * 收集当前页面用户真正可见的错误提示，不读取隐藏 DOM 或浏览器存储。
 * @param {import('@playwright/test').Page} page 当前草稿编辑页。
 * @returns {Promise<string[]>} 去重后的可见提示文本。
 */
async function visibleFailureFeedback(page) {
  const texts = await page.locator(
    '.el-message:visible .el-message__content, .el-notification:visible, .el-message-box:visible'
  ).allTextContents()
  return [...new Set(texts.map(safeText).filter(Boolean))]
}

/**
 * 仅截取错误提示组件作为缺陷证据，避免页面头部账号信息进入截图。
 * @param {import('@playwright/test').Page} page 当前故障页面。
 * @param {import('@playwright/test').TestInfo} testInfo 当前证据上下文。
 * @param {string} attachmentName 截图附件名。
 * @returns {Promise<void>} 可见提示存在时附加局部 PNG。
 */
async function attachFailureFeedback(page, testInfo, attachmentName) {
  const feedback = page.locator('.el-message-box:visible, .el-message:visible, .el-notification:visible').first()
  if (!await feedback.isVisible().catch(() => false)) return
  await testInfo.attach(attachmentName, {
    body: await feedback.screenshot(), contentType: 'image/png'
  })
}

/**
 * 点击会话过期弹窗的取消按钮，明确选择留在原页面继续恢复。
 * @param {import('@playwright/test').Page} page 当前草稿编辑页。
 * @returns {Promise<boolean>} 页面存在并关闭会话弹窗时返回 true。
 */
async function dismissSessionExpiredDialog(page) {
  const dialog = page.locator('.el-message-box:visible')
    .filter({ hasText: /登录状态已过期|重新登录/u })
  if (!await dialog.isVisible().catch(() => false)) return false
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(dialog).toBeHidden()
  return true
}

/**
 * 依赖恢复后从同一页面最多进行三次真实按钮重试，成功和丢响应均由草稿幂等契约收口。
 * @param {import('@playwright/test').Page} page 当前草稿编辑页。
 * @param {string} draftId 当前正式草稿 UUID。
 * @returns {Promise<{processInstanceId:string,attempts:object[]}>} 唯一实例主键和脱敏尝试摘要。
 */
async function submitAfterRecovery(page, draftId) {
  const attempts = []
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    if (/\/workflow\/process-detail\//u.test(new URL(page.url()).pathname)) break
    const outcome = await clickSubmitAndObserve(page, draftId, 'keyboard')
    attempts.push({ attempt, ...outcome })
    if (outcome.payload?.code === 200) {
      const processInstanceId = outcome.payload.processInstanceId
      expect(processInstanceId, '恢复后的成功响应必须包含唯一实例主键').not.toBe('')
      await expect(page).toHaveURL(
        new RegExp(`/workflow/process-detail/${processInstanceId}(?:[/?]|$)`, 'u'))
      return { processInstanceId, attempts }
    }
    await dismissSessionExpiredDialog(page)
    await page.waitForTimeout(750)
  }
  throw new Error(`依赖恢复后三次真实提交均未成功：${JSON.stringify(attempts)}`)
}

/**
 * 执行单个数据库或缓存断连、零副作用、恢复重试和后续审批闭环。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试证据上下文。
 * @param {'mysql'|'redis'} dependency 需要切断的后端正式依赖。
 * @param {string} caseId 可追踪用例编号。
 * @returns {Promise<void>} 故障、恢复、唯一实例和审批完成全部核验后结束。
 */
async function runDependencyRecoveryScenario(browser, testInfo, dependency, caseId) {
  requireFaultRuntime()
  const assets = scenarioAssets(caseId)
  const businessKey = `${assets.prefix}_业务主键`
  const subject = `${assets.prefix}_申请内容`
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  await resetFaultModes()
  await clearFaultEvidence()
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let faultApplied = false
  let failed = true
  try {
    await createFaultRecoveryModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const formInput = await openAndFillStartPage(starter.page, assets, businessKey, subject)
    assets.draftId = await saveDraftThroughUi(starter.page)
    const beforeFailure = readBusinessSnapshot(assets.draftId, businessKey)
    expect(beforeFailure.draftRows).toHaveLength(1)
    expect(beforeFailure.draftRows[0].slice(0, 4)).toEqual(['ACTIVE', '1', '', businessKey])
    expect(beforeFailure.draftAuditRows.map(row => row[0])).toEqual(['CREATED'])
    expect(beforeFailure.historyProcessRows).toHaveLength(0)

    await setDependencyMode(dependency, 'disconnect')
    faultApplied = true
    const failedOutcome = await clickSubmitAndObserve(starter.page, assets.draftId)
    await starter.page.waitForTimeout(200)
    const feedback = await visibleFailureFeedback(starter.page)
    await attachFailureFeedback(starter.page, testInfo, `${dependency}-failure-feedback.png`)
    const afterFailure = readBusinessSnapshot(assets.draftId, businessKey)
    const faultEvidence = await readFaultEvidence()
    await testInfo.attach(`${dependency}-failure-evidence.json`, {
      body: Buffer.from(JSON.stringify({
        dependency,
        network: failedOutcome,
        visibleFeedback: feedback,
        beforeFailure,
        afterFailure,
        faultTransitions: faultEvidence.faultTransitions || [],
        operationAudit: readOperationAuditSummary(assets.draftId)
      }, null, 2)),
      contentType: 'application/json'
    })

    expect.soft(failedOutcome.payload?.code !== 200 || failedOutcome.kind !== 'response',
      `${dependency} 断连时正式提交不得返回业务成功`).toBe(true)
    expect.soft(afterFailure, `${dependency} 断连提交不得改变草稿、Flowable、附件和业务审计`)
      .toEqual(beforeFailure)
    await expect(starter.page).toHaveURL(
      new RegExp(`/workflow/process-draft/${assets.draftId}(?:[/?]|$)`, 'u'))
    await expect(formInput, '依赖失败后用户输入必须保留在同一页面').toHaveValue(subject)
    await expect(starter.page.getByPlaceholder('可选')).toHaveValue(businessKey)

    const combinedFeedback = safeText([
      failedOutcome.payload?.msg, failedOutcome.failureText, ...feedback
    ].filter(Boolean).join(' | '))
    expect.soft(combinedFeedback, `${dependency} 断连后页面必须提供可见错误反馈`).not.toBe('')
    expect.soft(combinedFeedback, `${dependency} 故障提示不得泄露底层连接器、地址或异常类`)
      .not.toMatch(/Communications link failure|SQLException|JDBC|Druid|com\.mysql|Connection refused|127\.0\.0\.1|13306|RedisConnection|Lettuce|io\.lettuce|nested exception|java\./iu)
    if (dependency === 'mysql') {
      expect.soft(combinedFeedback, 'MySQL 暂时不可用时应向用户说明可稍后重试')
        .toMatch(/暂时|稍后|重试|不可用|系统接口|后端接口|异常/u)
    } else {
      expect.soft(combinedFeedback, 'Redis 暂时不可用不得被误报为用户会话真实过期')
        .not.toMatch(/登录状态已过期|会话.*过期|重新登录/u)
    }
    await dismissSessionExpiredDialog(starter.page)

    await setDependencyMode(dependency, 'ok')
    faultApplied = false
    await expect(formInput, '依赖恢复前后表单值必须保持不变').toHaveValue(subject)
    const recovered = await submitAfterRecovery(starter.page, assets.draftId)
    assets.processInstanceId = recovered.processInstanceId
    const afterRecovery = readBusinessSnapshot(assets.draftId, businessKey)
    await testInfo.attach(`${dependency}-recovery-evidence.json`, {
      body: Buffer.from(JSON.stringify({
        dependency,
        attempts: recovered.attempts,
        afterRecovery,
        operationAudit: readOperationAuditSummary(assets.draftId),
        faultEvidence: await readFaultEvidence()
      }, null, 2)),
      contentType: 'application/json'
    })
    expect(afterRecovery.draftRows).toHaveLength(1)
    expect(afterRecovery.draftRows[0].slice(0, 4))
      .toEqual(['SUBMITTED', '2', assets.processInstanceId, businessKey])
    expect(afterRecovery.draftAuditRows.map(row => row[0])).toEqual(['CREATED', 'SUBMITTED'])
    expect(afterRecovery.historyProcessRows).toHaveLength(1)
    expect(afterRecovery.historyProcessRows[0][0]).toBe(assets.processInstanceId)
    expect(afterRecovery.runtimeProcessCount, '恢复重试只能创建一个运行实例').toBe(1)
    expect(afterRecovery.runtimeTaskCount, '恢复后只能创建一个审批任务').toBe(1)
    expect(afterRecovery.historyTaskRows).toHaveLength(1)
    expect(afterRecovery.attachmentRows, '无附件场景不得产生附件元数据').toHaveLength(0)

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_恢复后审批通过`)
    const completed = readBusinessSnapshot(assets.draftId, businessKey)
    expect(completed.historyProcessRows).toHaveLength(1)
    expect(completed.historyProcessRows[0][3], '恢复后的唯一流程实例必须正常结束').toBe('1')
    expect(completed.runtimeProcessCount).toBe(0)
    expect(completed.runtimeTaskCount).toBe(0)
    expect(completed.historyTaskRows).toHaveLength(1)
    failed = false
  } finally {
    if (faultApplied) await setDependencyMode(dependency, 'ok').catch(() => {})
    await resetFaultModes().catch(() => {})
    const preserveEvidence = failed || testInfo.errors.length > 0
    await Promise.allSettled([
      approver?.close(preserveEvidence),
      starter?.close(preserveEvidence),
      designer.close(preserveEvidence)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

/**
 * 执行附件根不可写、零元数据与零文件残留、ACL 恢复及上传下载删除闭环。
 * @param {import('@playwright/test').Browser} browser Playwright Chromium 浏览器。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试证据上下文。
 * @returns {Promise<void>} 故障和恢复阶段的页面、数据库及物理存储核验完成后结束。
 */
async function runAttachmentStorageRecoveryScenario(browser, testInfo) {
  requireFaultRuntime()
  const assets = scenarioAssets('UI-FAULT-003')
  const failedName = `${assets.prefix}_不可写.txt`
  const failedBytes = Buffer.from(`attachment storage denied ${assets.prefix}\n`, 'utf8')
  const immediateRetryName = `${assets.prefix}_立即恢复上传.txt`
  const recoveredName = `${assets.prefix}_等待后恢复上传.txt`
  const recoveredBytes = Buffer.from(`attachment storage recovered ${assets.prefix}\n`, 'utf8')
  const expectedSha256 = createHash('sha256').update(recoveredBytes).digest('hex')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  await resetFaultModes()
  await clearFaultEvidence()
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let attachmentFaultApplied = false
  let failed = true
  try {
    await createAttachmentFaultModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    await openAttachmentStartPage(starter.page, assets)
    const storageRoot = resolveAttachmentStorageRoot()
    const beforeFileSummary = attachmentFileSummary(storageRoot)
    const beforeRows = queryReadOnly(
      `SELECT attachment_id, attachment_status FROM wf_attachment WHERE original_name IN ('${sqlLiteral(failedName)}', '${sqlLiteral(immediateRetryName)}', '${sqlLiteral(recoveredName)}') ORDER BY attachment_id`
    )
    expect(beforeRows, '当前唯一文件名在故障前不得已有附件元数据').toHaveLength(0)

    await setAttachmentStorageMode('read-only')
    attachmentFaultApplied = true
    const denied = await uploadAttachmentAndObserve(starter.page, failedName, failedBytes)
    await starter.page.waitForTimeout(300)
    const deniedSummary = safeAttachmentAjaxSummary(denied)
    const feedback = await visibleFailureFeedback(starter.page)
    await attachFailureFeedback(starter.page, testInfo, 'attachment-storage-failure-feedback.png')
    const afterDeniedRows = queryReadOnly(
      `SELECT attachment_id, attachment_status FROM wf_attachment WHERE original_name IN ('${sqlLiteral(failedName)}', '${sqlLiteral(immediateRetryName)}', '${sqlLiteral(recoveredName)}') ORDER BY attachment_id`
    )
    const failureEvidence = await readFaultEvidence()
    // Windows deny 写入 ACL 也可能让 Node 的目录枚举返回 EPERM；先恢复权限，再只读核验故障窗口残留。
    await setAttachmentStorageMode('writable')
    attachmentFaultApplied = false
    const afterDeniedFileSummary = attachmentFileSummary(storageRoot)
    await testInfo.attach('attachment-storage-failure-evidence.json', {
      body: Buffer.from(JSON.stringify({
        network: deniedSummary,
        visibleFeedback: feedback,
        beforeAttachmentRows: beforeRows,
        afterAttachmentRows: afterDeniedRows,
        beforeFileSummary,
        afterFileSummary: afterDeniedFileSummary,
        faultTransitions: failureEvidence.faultTransitions || []
      }, null, 2)),
      contentType: 'application/json'
    })

    expect.soft(deniedSummary.code, '附件根不可写时上传不得返回业务成功').not.toBe(200)
    expect.soft(afterDeniedRows, '附件根不可写时不得写入 wf_attachment 元数据')
      .toEqual(beforeRows)
    expect.soft(afterDeniedFileSummary, '附件根不可写时不得遗留临时或正式物理文件')
      .toEqual(beforeFileSummary)
    await expect(starter.page).toHaveURL(/\/workflow\/process-start\//u)
    const failedSuccessItem = starter.page
      .locator('.workflow-attachment-upload .el-upload-list__item.is-success')
      .filter({ hasText: failedName })
    await expect(failedSuccessItem, '失败附件不得伪装成成功上传项').toHaveCount(0)

    const combinedFeedback = safeText([deniedSummary.msg, ...feedback].filter(Boolean).join(' | '))
    expect.soft(combinedFeedback, '附件存储故障必须向用户提供可见错误反馈').not.toBe('')
    expect.soft(combinedFeedback, '附件存储故障提示不得泄露物理路径、ACL、Java 类或系统身份')
      .not.toMatch(/[A-Z]:\\|workflow-attachments|AccessDeniedException|FileSystemException|java\.|sun\.nio|icacls|S-1-5-/iu)
    expect.soft(combinedFeedback, '附件存储不可写时应提供稳定且可理解的失败语义')
      .toMatch(/附件|上传|写入|存储|失败|异常|重试/u)

    const immediateRetry = await uploadAttachmentAndObserve(
      starter.page, immediateRetryName, recoveredBytes)
    const immediateRetrySummary = safeAttachmentAjaxSummary(immediateRetry)
    const immediateRetryRows = queryReadOnly(
      `SELECT attachment_id, attachment_status FROM wf_attachment WHERE original_name = '${sqlLiteral(immediateRetryName)}'`
    )
    await testInfo.attach('attachment-storage-immediate-retry-evidence.json', {
      body: Buffer.from(JSON.stringify({
        network: immediateRetrySummary,
        attachmentRows: immediateRetryRows,
        visibleFeedback: await visibleFailureFeedback(starter.page)
      }, null, 2)),
      contentType: 'application/json'
    })
    expect.soft(immediateRetrySummary.code,
      '附件 ACL 恢复后不同文件的首次真实上传不应被失败请求占用的重复提交窗口拒绝')
      .toBe(200)
    if (immediateRetrySummary.code !== 200) {
      expect.soft(immediateRetrySummary.msg, '既有重复提交缺陷必须返回可识别的稳定提示')
        .toMatch(/不允许重复提交|稍候再试/u)
      expect(immediateRetryRows, '被重复提交门禁误拒绝时不得写入附件元数据').toHaveLength(0)
      // 用户留在同一页面等待正式 5 秒门禁过期，再选择第三个不同文件继续业务恢复。
      await starter.page.waitForTimeout(5_250)
    }

    const recovered = immediateRetrySummary.code === 200
      ? immediateRetry
      : await uploadAttachmentAndObserve(starter.page, recoveredName, recoveredBytes)
    const recoveredSummary = safeAttachmentAjaxSummary(recovered)
    const successfulName = immediateRetrySummary.code === 200 ? immediateRetryName : recoveredName
    expect(recoveredSummary.code, '重复提交门禁结束后的真实上传必须成功').toBe(200)
    expect(recoveredSummary.attachmentId, '恢复上传必须返回正式附件 UUID')
      .toMatch(/^[0-9a-f-]{36}$/iu)
    assets.attachmentIds.push(recoveredSummary.attachmentId)
    expect(recoveredSummary.status).toBe('TEMP')
    expect(recoveredSummary.fieldName).toBe('proofFiles')
    expect(recoveredSummary.originalName).toBe(successfulName)
    expect(recoveredSummary.fileSize).toBe(recoveredBytes.length)
    expect(recoveredSummary.sha256).toBe(expectedSha256)

    const recoveredRows = queryReadOnly(
      `SELECT attachment_id, attachment_status, field_name, original_name, file_size, sha256, storage_key, storage_deleted_time IS NULL FROM wf_attachment WHERE original_name IN ('${sqlLiteral(failedName)}', '${sqlLiteral(immediateRetryName)}', '${sqlLiteral(recoveredName)}') ORDER BY attachment_id`
    )
    expect(recoveredRows, '恢复后只能产生一条 TEMP 附件元数据').toHaveLength(1)
    expect(recoveredRows[0].slice(0, 6)).toEqual([
      recoveredSummary.attachmentId, 'TEMP', 'proofFiles', successfulName,
      String(recoveredBytes.length), expectedSha256
    ])
    expect(recoveredRows[0][7], '恢复附件物理文件必须尚未删除').toBe('1')
    const storedPath = resolveStoredAttachmentPath(storageRoot, recoveredRows[0][6])
    expect(existsSync(storedPath), '恢复后 TEMP 附件物理文件必须存在').toBe(true)
    expect(createHash('sha256').update(readFileSync(storedPath)).digest('hex')).toBe(expectedSha256)
    expect(await downloadAttachmentThroughUi(
      starter.page, recoveredSummary.attachmentId, successfulName, recoveredBytes))
      .toBe(expectedSha256)

    const recoveredItem = starter.page
      .locator('.workflow-attachment-upload .el-upload-list__item.is-success')
      .filter({ hasText: successfulName })
    const deletePromise = starter.page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/attachment/${recoveredSummary.attachmentId}`, 'DELETE'))
    await recoveredItem.hover()
    await recoveredItem.locator('.el-icon--close').click()
    await expectAjaxSuccess(await deletePromise,
      `/workflow/attachment/${recoveredSummary.attachmentId}`)
    await expect(recoveredItem, 'UI 删除成功后必须移除恢复附件项').toHaveCount(0)
    expect(queryReadOnly(
      `SELECT attachment_status, storage_deleted_time IS NOT NULL FROM wf_attachment WHERE attachment_id = '${sqlLiteral(recoveredSummary.attachmentId)}'`
    )).toEqual([['DELETED', '1']])
    expect(existsSync(storedPath), 'UI 删除后恢复附件物理文件必须清理').toBe(false)
    const finalFileSummary = attachmentFileSummary(storageRoot)
    expect(finalFileSummary, '恢复上传并删除后隔离目录不得新增物理残留')
      .toEqual(beforeFileSummary)

    await testInfo.attach('attachment-storage-recovery-evidence.json', {
      body: Buffer.from(JSON.stringify({
        network: recoveredSummary,
        immediateRetry: immediateRetrySummary,
        attachmentRows: queryReadOnly(
          `SELECT attachment_id, attachment_status, storage_deleted_time IS NOT NULL FROM wf_attachment WHERE original_name IN ('${sqlLiteral(failedName)}', '${sqlLiteral(immediateRetryName)}', '${sqlLiteral(recoveredName)}') ORDER BY attachment_id`
        ),
        beforeFileSummary,
        finalFileSummary,
        downloadedSha256: expectedSha256,
        faultEvidence: await readFaultEvidence()
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    if (attachmentFaultApplied) {
      // ACL 恢复失败必须抛出，由总控再次兜底并阻止继续扩大环境变更。
      await setAttachmentStorageMode('writable')
    }
    await resetFaultModes()
    const preserveEvidence = failed || testInfo.errors.length > 0
    await Promise.allSettled([
      starter?.close(preserveEvidence), designer.close(preserveEvidence)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
}

test('@fault [UI-FAULT-001] MySQL断连提交零副作用且恢复后保持唯一实例', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runDependencyRecoveryScenario(browser, testInfo, 'mysql', 'UI-FAULT-001')
})

test('@fault [UI-FAULT-002] Redis断连保留页面输入且恢复后保持唯一实例', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runDependencyRecoveryScenario(browser, testInfo, 'redis', 'UI-FAULT-002')
})

test('@fault [UI-FAULT-003] 附件目录不可写零残留且恢复后上传下载删除一致', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runAttachmentStorageRecoveryScenario(browser, testInfo)
})

test('@fault [UI-FAULT-004] SMTP拒绝进入脱敏死信且UI补偿后唯一送达', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  await runSmtpRecoveryScenario(browser, testInfo, 'reject', 'UI-FAULT-004')
})

test('@fault [UI-FAULT-005] SMTP超时进入脱敏死信且UI补偿后唯一送达', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  await runSmtpRecoveryScenario(browser, testInfo, 'timeout', 'UI-FAULT-005')
})

test('@fault [UI-FAULT-006] 通知租约期间后端重启后新worker接管且唯一送达', async ({ browser }, testInfo) => {
  test.setTimeout(360_000)
  await runNotificationLeaseRestartScenario(browser, testInfo)
})

test('@fault [UI-FAULT-007] 外部HTTP临时5xx在默认重试内恢复且幂等键稳定', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  await runHttpTransientRecoveryScenario(browser, testInfo)
})

const httpAuthorBlocked = {
  annotation: {
    type: 'blocked',
    description: 'DEF-UI-010 阻断：HTTP 受控处理器选择被异步属性同步回读清空，无法通过 UI 创建运行前置'
  }
}

test.skip('@fault [UI-FAULT-008] 外部HTTP超时进入死信且新实例恢复不改原终态', httpAuthorBlocked,
  async () => {})

test.skip('@fault [UI-FAULT-009] 外部HTTP断连进入死信且新实例恢复不改原终态', httpAuthorBlocked,
  async () => {})

test.skip('@fault [UI-FAULT-010] 外部HTTP重复响应保持单台账和单流程副作用', httpAuthorBlocked,
  async () => {})

test('@fault [UI-FAULT-011] 浏览器离线提交零副作用且恢复后保持唯一实例', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runBrowserOfflineScenario(browser, testInfo)
})

test('@fault [UI-FAULT-012] 请求超时提交零副作用且恢复后保持唯一实例', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  await runBrowserTimeoutScenario(browser, testInfo)
})

test('@fault [UI-FAULT-013] 双击正式提交保持单请求单实例', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runDoubleSubmitScenario(browser, testInfo)
})

test('@fault [UI-FAULT-014] 真实会话失效后重新登录恢复原草稿', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runSessionExpiryScenario(browser, testInfo)
})

test('@fault [UI-FAULT-015] 浏览器刷新和后退恢复正式草稿且无写副作用', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runRefreshBackScenario(browser, testInfo)
})

test('@fault [UI-FAULT-016] 多标签陈旧任务操作被无副作用拒绝', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  await runStaleTaskScenario(browser, testInfo)
})
