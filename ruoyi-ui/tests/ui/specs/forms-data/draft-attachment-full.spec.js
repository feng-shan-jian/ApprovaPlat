import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { expect, test } from '@playwright/test'
import { loginThroughUi, logoutThroughUi } from '../../../e2e/fixtures/workflow.js'
import { loadWorkflowAccounts } from '../../../e2e/support/environment.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

const workflowAccounts = loadWorkflowAccounts()

/**
 * 生成草稿和附件用例的唯一测试资产清单。
 * @param {string} caseId 当前可追踪用例编号。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,processInstanceId:string,draftIds:string[],attachmentIds:string[]}} 本轮 UI 资产登记。
 */
function scenarioAssets(caseId) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-14)
  const prefix = `E2E_UI_${runId}_${caseId.replaceAll('-', '')}_${Date.now().toString(36)}`
  return {
    prefix,
    categoryName: `${prefix}_分类`, categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`, modelName: `${prefix}_草稿附件`,
    modelKey: `${prefix}_model`, processInstanceId: '', draftIds: [], attachmentIds: []
  }
}

/**
 * 转义只读 SQL 中由测试自身生成的唯一标识。
 * @param {string} value 测试运行生成的主键或名称。
 * @returns {string} 可作为 MySQL 字符串字面量正文的值。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 从 AjaxResult 中读取并校验草稿 UUID。
 * @param {object} payload 草稿创建或更新响应正文。
 * @returns {string} 服务端生成的草稿主键。
 */
function requireDraftId(payload) {
  const draftId = String(payload?.data?.draftId || payload?.data?.id || '')
  expect(draftId, '草稿写入必须返回正式 UUID').toMatch(/^[0-9a-f-]{36}$/iu)
  return draftId
}

/**
 * 通过真实 UI 创建并部署包含必填文本、私有附件和候选角色任务的流程。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前用例资产。
 * @returns {Promise<void>} 表单、模型、BPMN 配置和部署全部完成后结束。
 */
async function createDraftAttachmentModel(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({
    name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
  })
  await configuration.createTextAttachmentForm({
    name: assets.formName,
    remark: `${assets.prefix} 草稿附件真实UI`,
    textFieldName: 'requestTitle',
    textLabel: '申请主题',
    textPlaceholder: '请输入申请主题',
    attachmentFieldName: 'proofFiles',
    attachmentLabel: '证明附件'
  })
  await configuration.createModel({
    name: assets.modelName, key: assets.modelKey,
    categoryName: assets.categoryName, formName: assets.formName,
    description: `${assets.prefix} 草稿附件真实UI`
  })
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.configureCandidateRole('流程审批人', '附件审批')
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 通过真实 UI 创建并部署仅含必填文本字段的草稿生命周期流程。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前用例资产。
 * @returns {Promise<void>} 表单、模型、BPMN 配置和部署全部完成后结束。
 */
async function createDraftTextModel(page, assets) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.createCategory({
    name: assets.categoryName, code: assets.categoryCode, remark: assets.prefix
  })
  await configuration.createTextForm({
    name: assets.formName,
    remark: `${assets.prefix} 草稿生命周期真实UI`
  })
  await configuration.createModel({
    name: assets.modelName, key: assets.modelKey,
    categoryName: assets.categoryName, formName: assets.formName,
    description: `${assets.prefix} 草稿生命周期真实UI`
  })
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.configureCandidateRole('流程审批人', '草稿审批')
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 定位流程表单渲染器中的首个真实文本输入框。
 * @param {import('@playwright/test').Page} page 发起或草稿详情页面。
 * @returns {import('@playwright/test').Locator} 不包含附件原生 file input 的文本输入框。
 */
function processTextInput(page) {
  return page.locator('.workflow-form-renderer input:not([type="file"])').first()
}

/**
 * 从新建流程列表通过真实按钮进入指定部署的发起页面。
 * @param {import('@playwright/test').Page} page 发起人页面。
 * @param {string} processName 唯一流程名称。
 * @returns {Promise<void>} 部署表单完整显示后结束。
 */
async function openProcessStart(page, processName) {
  const workbench = new WorkflowWorkbenchPage(page)
  const row = await workbench.filterRow('/office/create', '请输入流程名称', processName)
  await row.locator('button').first().click()
  await expect(page).toHaveURL(/\/workflow\/process-start\//u)
  await expect(processTextInput(page)).toBeVisible()
}

/**
 * 通过草稿列表的继续编辑图标恢复指定业务主键草稿。
 * @param {import('@playwright/test').Page} page 发起人页面。
 * @param {string} businessKey 唯一业务主键。
 * @param {string} draftId 期望恢复的草稿 UUID。
 * @returns {Promise<void>} 草稿详情和不可变表单快照完成回显后结束。
 */
async function continueDraftThroughList(page, businessKey, draftId) {
  await page.goto('/office/draft')
  const row = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: businessKey })
  await expect(row, '本人草稿必须从真实列表回显').toHaveCount(1)
  await row.locator('button.el-button--primary').click()
  await expect(page).toHaveURL(new RegExp(`/workflow/process-draft/${draftId}(?:[/?]|$)`, 'u'))
  await expect(page.getByPlaceholder('可选')).toHaveValue(businessKey)
}

/**
 * 通过当前页面保存草稿并返回已核验的 AjaxResult。
 * @param {import('@playwright/test').Page} page 新建或继续编辑草稿页面。
 * @param {string} endpoint 本次 POST 或 PUT 的正式业务入口。
 * @param {'POST'|'PUT'} method 写请求方法。
 * @returns {Promise<object>} 业务码为 200 的草稿响应正文。
 */
async function saveDraftThroughUi(page, endpoint, method) {
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, method))
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  return expectAjaxSuccess(await responsePromise, endpoint)
}

/**
 * 读取草稿、不可变定义、Flowable 实例任务和附件的只读一致性快照。
 * @param {string} draftId 当前正式草稿 UUID。
 * @param {string} businessKey 当前用例唯一业务主键。
 * @param {string} processKey 当前 UI 建模流程的稳定 key。
 * @returns {{draftRows:string[][],auditRows:string[][],definitionRows:string[][],historyProcessRows:string[][],runtimeProcessCount:number,runtimeTaskCount:number,historyTaskRows:string[][],attachmentRows:string[][]}} 不包含表单正文和凭据的业务快照。
 */
function draftSubmissionSnapshot(draftId, businessKey, processKey) {
  const escapedDraftId = sqlLiteral(draftId)
  const escapedBusinessKey = sqlLiteral(businessKey)
  const escapedProcessKey = sqlLiteral(processKey)
  const runtimeProcessRows = queryReadOnly(
    `SELECT COUNT(DISTINCT execution.PROC_INST_ID_) FROM ACT_RU_EXECUTION execution INNER JOIN ACT_HI_PROCINST history ON history.PROC_INST_ID_=execution.PROC_INST_ID_ WHERE history.BUSINESS_KEY_='${escapedBusinessKey}'`
  )
  const runtimeTaskRows = queryReadOnly(
    `SELECT COUNT(*) FROM ACT_RU_TASK task INNER JOIN ACT_HI_PROCINST history ON history.PROC_INST_ID_=task.PROC_INST_ID_ WHERE history.BUSINESS_KEY_='${escapedBusinessKey}'`
  )
  return {
    draftRows: queryReadOnly(
      `SELECT process_definition_id,process_definition_version,deployment_id,draft_status,revision_no,COALESCE(submitted_process_instance_id,''),COALESCE(business_key,''),SHA2(form_values,256),submitted_time IS NULL,deleted_time IS NULL FROM wf_process_draft WHERE draft_id='${escapedDraftId}'`
    ),
    auditRows: queryReadOnly(
      `SELECT action_type,COALESCE(from_status,''),to_status,COALESCE(from_revision,0),to_revision,COALESCE(process_instance_id,'') FROM wf_process_draft_audit WHERE draft_id='${escapedDraftId}' ORDER BY audit_id`
    ),
    definitionRows: queryReadOnly(
      `SELECT VERSION_,ID_,DEPLOYMENT_ID_,SUSPENSION_STATE_ FROM ACT_RE_PROCDEF WHERE KEY_='${escapedProcessKey}' ORDER BY VERSION_`
    ),
    historyProcessRows: queryReadOnly(
      `SELECT PROC_INST_ID_,PROC_DEF_ID_,COALESCE(BUSINESS_KEY_,''),END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='${escapedBusinessKey}' ORDER BY START_TIME_,PROC_INST_ID_`
    ),
    runtimeProcessCount: Number(runtimeProcessRows[0]?.[0] || 0),
    runtimeTaskCount: Number(runtimeTaskRows[0]?.[0] || 0),
    historyTaskRows: queryReadOnly(
      `SELECT task.TASK_DEF_KEY_,task.NAME_,task.END_TIME_ IS NOT NULL,COALESCE(task.DELETE_REASON_,'') FROM ACT_HI_TASKINST task INNER JOIN ACT_HI_PROCINST history ON history.PROC_INST_ID_=task.PROC_INST_ID_ WHERE history.BUSINESS_KEY_='${escapedBusinessKey}' ORDER BY task.START_TIME_,task.ID_`
    ),
    attachmentRows: queryReadOnly(
      `SELECT attachment_status,COUNT(*) FROM wf_attachment WHERE draft_id='${escapedDraftId}' OR process_instance_id IN (SELECT PROC_INST_ID_ FROM ACT_HI_PROCINST WHERE BUSINESS_KEY_='${escapedBusinessKey}') GROUP BY attachment_status ORDER BY attachment_status`
    )
  }
}

/**
 * 从已部署模型的真实设计器保存并发布下一版本。
 * @param {import('@playwright/test').Page} page 流程设计者真实登录页面。
 * @param {ReturnType<typeof scenarioAssets>} assets 当前用例资产。
 * @param {string} taskName 新版本审批任务名称。
 * @returns {Promise<void>} 新模型版本保存、返回列表并通过 UI 部署完成后结束。
 */
async function publishNextModelVersionThroughUi(page, assets, taskName) {
  const configuration = new WorkflowConfigurationPage(page)
  await configuration.openDesigner(assets.modelKey)
  const designer = new WorkflowDesignerPage(page)
  await designer.configureCandidateRole('流程审批人', taskName)
  await designer.validateAndSave()
  await designer.returnToModels()
  await configuration.deployModel(assets.modelKey)
}

/**
 * 监听指定草稿的真实提交响应并执行用户可见的正式提交动作。
 * @param {import('@playwright/test').Page} page 已打开草稿详情的真实浏览器标签。
 * @param {string} draftId 当前正式草稿 UUID。
 * @param {{force?:boolean}} options 点击是否需要越过页面当前禁用态，仅用于验证已打开旧页面的服务端竞态门禁。
 * @returns {Promise<{httpStatus:number,payload:object,requestExpectedVersion:number|null}>} HTTP 状态、AjaxResult 和请求携带的草稿版本。
 */
async function submitDraftAndObserve(page, draftId, options = {}) {
  const endpoint = `/workflow/process/draft/${draftId}/submit`
  const requestPromise = page.waitForRequest(request => (
    request.method() === 'POST' && new URL(request.url()).pathname.endsWith(endpoint)
  ))
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, endpoint, 'POST'))
    .then(async response => ({
      // 成功提交会立即导航，必须在 Chromium 释放响应正文前完成读取。
      httpStatus: response.status(),
      payload: await response.json()
    }))
  const button = page.getByRole('button', { name: '正式提交', exact: true })
  if (!options.force) await expect(button).toBeEnabled()
  await button.click({ force: options.force === true })
  const [request, response] = await Promise.all([requestPromise, responsePromise])
  const requestPayload = request.postDataJSON()
  return {
    httpStatus: response.httpStatus,
    payload: response.payload,
    requestExpectedVersion: Number.isFinite(Number(requestPayload?.expectedVersion))
      ? Number(requestPayload.expectedVersion) : null
  }
}

/**
 * 从草稿提交 AjaxResult 中提取稳定且可安全进入报告的字段。
 * @param {{httpStatus:number,payload:object,requestExpectedVersion:number|null}} outcome 单次真实提交结果。
 * @returns {{httpStatus:number,code:number|null,subCode:string,msg:string,requestExpectedVersion:number|null,draftId:string,processInstanceId:string,processDefinitionId:string,revisionNo:number|null}} 脱敏提交摘要。
 */
function draftSubmitSummary(outcome) {
  const payload = outcome?.payload || {}
  const data = payload.data || {}
  return {
    httpStatus: Number(outcome?.httpStatus || 0),
    code: Number.isFinite(Number(payload.code)) ? Number(payload.code) : null,
    subCode: String(payload.subCode || data.subCode || ''),
    msg: String(payload.msg || '').slice(0, 500),
    requestExpectedVersion: outcome.requestExpectedVersion,
    draftId: String(data.draftId || data.id || ''),
    processInstanceId: String(data.processInstanceId || data.procInsId || ''),
    processDefinitionId: String(data.processDefinitionId || ''),
    revisionNo: Number.isFinite(Number(data.revisionNo)) ? Number(data.revisionNo) : null
  }
}

/**
 * 核对提交后只产生一个草稿终态、一个 Flowable 实例和一个活动审批任务。
 * @param {ReturnType<typeof draftSubmissionSnapshot>} snapshot 提交后的只读业务快照。
 * @param {{draftId:string,processInstanceId:string,businessKey:string,definitionId:string,deploymentId:string}} expected 当前用例期望的正式主键关系。
 * @returns {void} 任一状态、数量或定义关系不一致时抛出断言失败。
 */
function expectUniqueSubmittedDraft(snapshot, expected) {
  expect(snapshot.draftRows).toHaveLength(1)
  expect(snapshot.draftRows[0].slice(0, 7)).toEqual([
    expected.definitionId, '1', expected.deploymentId, 'SUBMITTED', '2',
    expected.processInstanceId, expected.businessKey
  ])
  expect(snapshot.draftRows[0][8], '提交后必须登记 submitted_time').toBe('0')
  expect(snapshot.draftRows[0][9], '提交后不得登记 deleted_time').toBe('1')
  expect(snapshot.auditRows).toEqual([
    ['CREATED', '', 'ACTIVE', '0', '1', ''],
    ['SUBMITTED', 'ACTIVE', 'SUBMITTED', '1', '2', expected.processInstanceId]
  ])
  expect(snapshot.historyProcessRows).toHaveLength(1)
  expect(snapshot.historyProcessRows[0].slice(0, 4)).toEqual([
    expected.processInstanceId, expected.definitionId, expected.businessKey, '0'
  ])
  expect(snapshot.runtimeProcessCount, '并发提交只能形成一个运行实例').toBe(1)
  expect(snapshot.runtimeTaskCount, '并发提交只能形成一个活动审批任务').toBe(1)
  expect(snapshot.historyTaskRows).toHaveLength(1)
  expect(snapshot.attachmentRows).toHaveLength(0)
}

/**
 * 在两个真实浏览器标签的请求离开 Chromium 前建立一次性并发栅栏。
 * @param {import('@playwright/test').Page[]} pages 同一发起人上下文中的两个真实标签。
 * @param {string} draftId 当前正式草稿 UUID。
 * @returns {Promise<{stop:()=>Promise<void>,evidence:()=>{arrivals:number,arrivalDeltaMs:number|null}}>} 释放路由和读取并发到达摘要的方法。
 */
async function installConcurrentSubmitBarrier(pages, draftId) {
  const pattern = new RegExp(`/workflow/process/draft/${draftId}/submit(?:\\?.*)?$`, 'u')
  let arrivals = 0
  const arrivalTimes = []
  let release
  let released = false
  const gate = new Promise(resolve => { release = resolve })
  const releaseGate = () => {
    if (released) return
    released = true
    release()
  }
  // 防止任一标签没有发出请求时无限等待；超时释放后仍由 arrivals 断言明确失败。
  const timeout = setTimeout(releaseGate, 10_000)
  const handlers = pages.map(() => async route => {
    arrivals += 1
    arrivalTimes.push(Date.now())
    if (arrivals === pages.length) releaseGate()
    await gate
    // 栅栏只同步两个真实请求，不修改 URL、请求体、请求头或后端响应。
    await route.continue()
  })
  for (let index = 0; index < pages.length; index += 1) {
    await pages[index].route(pattern, handlers[index])
  }
  return {
    /**
     * 移除并发栅栏并确保任何等待中的真实请求得到释放。
     * @returns {Promise<void>} 两个页面路由均恢复后结束。
     */
    async stop() {
      clearTimeout(timeout)
      releaseGate()
      await Promise.allSettled(pages.map((page, index) => page.unroute(pattern, handlers[index])))
    },
    /**
     * 返回不包含请求正文的并发到达时间摘要。
     * @returns {{arrivals:number,arrivalDeltaMs:number|null}} 请求数量及首尾到达毫秒差。
     */
    evidence() {
      return {
        arrivals,
        arrivalDeltaMs: arrivalTimes.length === pages.length
          ? Math.max(...arrivalTimes) - Math.min(...arrivalTimes) : null
      }
    }
  }
}

/**
 * 上传浏览器内存文件并返回服务端安全附件元数据。
 * @param {import('@playwright/test').Page} page 当前表单页面。
 * @param {string} name 文件名。
 * @param {Buffer} content 文件字节。
 * @returns {Promise<object>} TEMP 附件安全元数据。
 */
async function uploadAttachmentThroughUi(page, name, content) {
  const { payload } = await uploadAttachmentResponseThroughUi(page, name, content)
  if (payload?.code !== 200) {
    const safeFailure = {
      code: payload?.code ?? null,
      subCode: payload?.subCode ?? payload?.data?.subCode ?? null,
      msg: String(payload?.msg || '').slice(0, 500)
    }
    throw new Error(`/workflow/attachment 业务失败: ${JSON.stringify(safeFailure)}`)
  }
  expect(payload.data?.attachmentId, '附件上传必须返回正式 UUID').toMatch(/^[0-9a-f-]{36}$/iu)
  // Element Plus 会短暂同时保留 ready 和 success 过渡节点，后续删除前必须等待列表稳定为唯一成功项。
  const uploadedItems = page.locator('.workflow-attachment-upload .el-upload-list__item')
    .filter({ hasText: name })
  await expect(uploadedItems.locator('xpath=self::*[contains(@class,"is-success")]'),
    '附件响应成功后页面必须稳定回显唯一成功项').toHaveCount(1)
  await expect(uploadedItems, '附件上传过渡动画结束后不得保留重复列表项').toHaveCount(1)
  return payload.data
}

/**
 * 上传浏览器内存文件并保留真实 HTTP 响应和 AjaxResult，供失败分支完成页面与数据库取证。
 * @param {import('@playwright/test').Page} page 当前表单页面。
 * @param {string} name 文件名。
 * @param {Buffer} content 文件字节。
 * @returns {Promise<{response:import('@playwright/test').Response,payload:object}>} 真实上传响应及其 JSON 正文。
 */
async function uploadAttachmentResponseThroughUi(page, name, content) {
  const input = page.locator('.workflow-attachment-upload input[type="file"]')
  await expect(input, '部署表单必须渲染唯一附件选择入口').toHaveCount(1)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/attachment', 'POST'))
  await input.setInputFiles({ name, mimeType: 'text/plain', buffer: content })
  // 单独保留上传响应的安全业务字段，便于区分测试同步问题、前端问题和后端业务异常。
  const response = await responsePromise
  expect(response.status(), '/workflow/attachment HTTP 状态').toBe(200)
  const payload = await response.json()
  return { response, payload }
}

/**
 * 从附件列表点击文件名完成浏览器下载并核对原始字节和 SHA-256。
 * @param {import('@playwright/test').Page} page 当前有权读取附件的页面。
 * @param {string} attachmentId 附件 UUID。
 * @param {string} fileName 原始安全文件名。
 * @param {Buffer} expectedBytes 期望下载字节。
 * @returns {Promise<string>} 浏览器下载内容的 SHA-256。
 */
async function downloadAttachmentThroughUi(page, attachmentId, fileName, expectedBytes) {
  const editableItem = page.locator('.workflow-attachment-upload .el-upload-list__item')
    .filter({ hasText: fileName })
  const readonlyButton = page.getByRole('button', { name: `下载附件 ${fileName}`, exact: true })
  const editableCount = await editableItem.count()
  const readonlyCount = await readonlyButton.count()
  expect(editableCount + readonlyCount, '附件必须从可编辑或只读表单列表唯一回显').toBe(1)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, `/workflow/attachment/${attachmentId}/content`, 'GET'))
  const downloadPromise = page.waitForEvent('download')
  if (editableCount === 1) await editableItem.locator('.el-upload-list__item-name').click()
  else await readonlyButton.click()
  const response = await responsePromise
  const download = await downloadPromise
  expect(response.status(), '附件下载 HTTP 状态').toBe(200)
  expect(download.suggestedFilename()).toBe(fileName)
  const downloadPath = await download.path()
  expect(downloadPath, '浏览器下载必须产生可读取的临时文件').not.toBeNull()
  const downloadedBytes = await readFile(downloadPath)
  expect(downloadedBytes.equals(expectedBytes), '下载字节必须与上传内容一致').toBe(true)
  return createHash('sha256').update(downloadedBytes).digest('hex')
}

/**
 * 根据数据库安全存储键解析附件物理路径，并拒绝越出工作流私有目录。
 * @param {string} storageKey 数据库中的相对存储键。
 * @returns {string} 工作流私有附件的绝对路径。
 */
function attachmentStoragePath(storageKey) {
  const profileRoot = path.resolve(process.env.FLOWABLE_E2E_PROFILE_ROOT || 'D:/approvaplat/uploadPath')
  const storageRoot = path.resolve(profileRoot, 'workflow-attachments')
  const filePath = path.resolve(storageRoot, ...String(storageKey).split('/'))
  if (!filePath.startsWith(`${storageRoot}${path.sep}`)) throw new Error('附件存储键越出私有目录')
  return filePath
}

/**
 * 等待草稿删除后的异步物理清理完成。
 * @param {string} attachmentId 附件 UUID。
 * @param {string} filePath 删除前已校验的私有文件绝对路径。
 * @returns {Promise<void>} 元数据记录清理时间且物理文件消失后结束。
 */
async function expectDraftAttachmentCleanup(attachmentId, filePath) {
  const escapedId = sqlLiteral(attachmentId)
  await expect.poll(() => queryReadOnly(
    `SELECT attachment_status, storage_deleted_time IS NOT NULL FROM wf_attachment WHERE attachment_id = '${escapedId}'`
  ), { timeout: 30_000, intervals: [500, 1000, 2000] }).toEqual([['DELETED', '1']])
  expect(existsSync(filePath), '草稿删除后私有附件物理文件必须清理').toBe(false)
}

test('@full [UI-DRAFT-001] 草稿跨登录恢复、双标签CAS冲突和UI删除保持一致', async ({ browser }, testInfo) => {
  const assets = scenarioAssets('UI-DRAFT-001')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let stalePage
  let failed = true
  try {
    await createDraftAttachmentModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    await openProcessStart(starter.page, assets.modelName)
    const businessKey = `${assets.prefix}_业务主键`
    await starter.page.getByPlaceholder('可选').fill(businessKey)
    const created = await saveDraftThroughUi(starter.page, '/workflow/process/draft', 'POST')
    const draftId = requireDraftId(created)
    assets.draftIds.push(draftId)
    expect(Number(created.data?.revisionNo)).toBe(1)
    expect(queryReadOnly(
      `SELECT draft_status, revision_no, business_key, COALESCE(JSON_UNQUOTE(JSON_EXTRACT(form_values, '$.requestTitle')), '') FROM wf_process_draft WHERE draft_id = '${sqlLiteral(draftId)}'`
    )).toEqual([['ACTIVE', '1', businessKey, '']])

    await starter.page.reload()
    await expect(starter.page.getByPlaceholder('可选')).toHaveValue(businessKey)
    await logoutThroughUi(starter.page, 'workflow_starter')
    // 注销路由会并行刷新一次验证码；等待它完成后再注册下一次登录监听，避免误捕获旧响应。
    await starter.page.waitForLoadState('networkidle')
    await loginThroughUi(starter.page, workflowAccounts.workflow_starter)
    await continueDraftThroughList(starter.page, businessKey, draftId)

    stalePage = await starter.page.context().newPage()
    await stalePage.goto(`/workflow/process-draft/${draftId}`)
    await expect(stalePage.getByPlaceholder('可选')).toHaveValue(businessKey)
    const serverValue = `${assets.prefix}_服务器版本`
    await starter.page.getByPlaceholder('请输入申请主题').fill(serverValue)
    const saved = await saveDraftThroughUi(
      starter.page, `/workflow/process/draft/${draftId}`, 'PUT')
    expect(Number(saved.data?.revisionNo)).toBe(2)

    await stalePage.getByPlaceholder('请输入申请主题').fill(`${assets.prefix}_陈旧输入`)
    const conflictPromise = stalePage.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/draft/${draftId}`, 'PUT'))
    await stalePage.getByRole('button', { name: '保存草稿', exact: true }).click()
    const conflictResponse = await conflictPromise
    const conflictPayload = await conflictResponse.json()
    expect(conflictPayload?.code, '陈旧标签页保存必须返回 CAS 冲突').toBe(409)
    await expect(stalePage.getByText(
      '草稿已在其他页面更新，当前输入尚未覆盖服务端数据', { exact: true })).toBeVisible()
    expect(queryReadOnly(
      `SELECT revision_no, JSON_UNQUOTE(JSON_EXTRACT(form_values, '$.requestTitle')) FROM wf_process_draft WHERE draft_id = '${sqlLiteral(draftId)}'`
    )).toEqual([['2', serverValue]])

    await stalePage.getByRole('button', { name: '重新加载服务器版本', exact: true }).click()
    await stalePage.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expect(stalePage.getByPlaceholder('请输入申请主题')).toHaveValue(serverValue)
    await expect(stalePage.getByText(
      '草稿已在其他页面更新，当前输入尚未覆盖服务端数据', { exact: true })).toHaveCount(0)

    const deletePromise = stalePage.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/draft/${draftId}`, 'DELETE'))
    await stalePage.getByRole('button', { name: '删除草稿', exact: true }).click()
    await stalePage.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await deletePromise, `/workflow/process/draft/${draftId}`)
    await expect(stalePage).toHaveURL(/\/office\/draft(?:\?|$)/u)
    expect(queryReadOnly(
      `SELECT draft_status, revision_no, deleted_time IS NOT NULL FROM wf_process_draft WHERE draft_id = '${sqlLiteral(draftId)}'`
    )).toEqual([['DELETED', '3', '1']])
    expect(queryReadOnly(
      `SELECT action_type, to_status, to_revision FROM wf_process_draft_audit WHERE draft_id = '${sqlLiteral(draftId)}' ORDER BY audit_id`
    )).toEqual([
      ['CREATED', 'ACTIVE', '1'], ['SAVED', 'ACTIVE', '2'], ['DELETED', 'DELETED', '3']
    ])
    failed = false
  } finally {
    await stalePage?.close().catch(() => {})
    await Promise.allSettled([starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-DRAFT-002] V1草稿在UI发布V2后提交拒绝且零业务副作用', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  const assets = scenarioAssets('UI-DRAFT-002')
  const businessKey = `${assets.prefix}_版本过期业务主键`
  const subject = `${assets.prefix}_V1草稿正文`
  const evidence = { beforePublish: null, afterPublish: null, rejected: null, refreshed: null }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, businessKey }, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let failed = true
  try {
    await createDraftTextModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    await openProcessStart(starter.page, assets.modelName)
    await starter.page.getByPlaceholder('可选').fill(businessKey)
    await processTextInput(starter.page).fill(subject)
    const created = await saveDraftThroughUi(starter.page, '/workflow/process/draft', 'POST')
    const draftId = requireDraftId(created)
    assets.draftIds.push(draftId)
    await expect(starter.page).toHaveURL(
      new RegExp(`/workflow/process-draft/${draftId}(?:[/?]|$)`, 'u'))

    const beforePublish = draftSubmissionSnapshot(draftId, businessKey, assets.modelKey)
    expect(beforePublish.draftRows).toHaveLength(1)
    expect(beforePublish.draftRows[0].slice(1, 7)).toEqual([
      '1', beforePublish.draftRows[0][2], 'ACTIVE', '1', '', businessKey
    ])
    expect(beforePublish.auditRows).toEqual([['CREATED', '', 'ACTIVE', '0', '1', '']])
    expect(beforePublish.definitionRows).toHaveLength(1)
    expect(beforePublish.definitionRows[0][0]).toBe('1')
    expect(beforePublish.historyProcessRows).toHaveLength(0)
    evidence.beforePublish = beforePublish

    // 保留发起人的旧草稿页面不刷新，由设计者从正式设计器保存并部署 V2。
    await publishNextModelVersionThroughUi(
      designer.page, assets, `${assets.prefix}_V2审批`)
    const afterPublish = draftSubmissionSnapshot(draftId, businessKey, assets.modelKey)
    expect(afterPublish.definitionRows.map(row => [row[0], row[3]])).toEqual([
      ['1', '2'], ['2', '1']
    ])
    expect(afterPublish.draftRows, '发布 V2 不得主动改写 V1 活动草稿').toEqual(beforePublish.draftRows)
    expect(afterPublish.auditRows, '发布 V2 不得追加草稿业务审计').toEqual(beforePublish.auditRows)
    expect(afterPublish.historyProcessRows).toHaveLength(0)
    evidence.afterPublish = afterPublish

    const rejectedOutcome = await submitDraftAndObserve(starter.page, draftId)
    const rejectedSummary = draftSubmitSummary(rejectedOutcome)
    expect(rejectedSummary.httpStatus, '业务冲突仍通过统一 AjaxResult HTTP 边界返回').toBe(200)
    expect(rejectedSummary.code).toBe(409)
    expect(rejectedSummary.subCode).toBe('DRAFT_DEFINITION_VERSION_EXPIRED')
    expect(rejectedSummary.msg).toMatch(/流程定义.*(最新|版本).*(变化|失效)/u)
    expect(rejectedSummary.requestExpectedVersion, '旧页面必须携带原始 revision 1 提交').toBe(1)
    await expect(starter.page.getByText(rejectedSummary.msg, { exact: true }).last()).toBeVisible()
    await expect(starter.page.getByText('流程定义版本已过期', { exact: true })).toHaveCount(0)
    const lockedButton = starter.page.getByRole('button', { name: '正式提交', exact: true })
    await expect(lockedButton, '定义类 409 后旧页面必须锁定继续提交').toBeDisabled()
    await expect(starter.page.getByRole('button', { name: '保存草稿', exact: true }),
      '定义类 409 后旧页面必须锁定继续保存').toBeDisabled()
    await expect(starter.page.getByPlaceholder('可选')).toBeDisabled()
    await expect(processTextInput(starter.page)).toBeDisabled()

    const afterReject = draftSubmissionSnapshot(draftId, businessKey, assets.modelKey)
    expect(afterReject.draftRows, '过期定义提交拒绝不得改写草稿状态、版本或正文摘要')
      .toEqual(afterPublish.draftRows)
    expect(afterReject.auditRows, '过期定义提交拒绝不得追加业务审计')
      .toEqual(afterPublish.auditRows)
    expect(afterReject.historyProcessRows, '过期定义提交拒绝不得创建 Flowable 历史实例')
      .toHaveLength(0)
    expect(afterReject.runtimeProcessCount).toBe(0)
    expect(afterReject.runtimeTaskCount).toBe(0)
    expect(afterReject.historyTaskRows).toHaveLength(0)
    expect(afterReject.attachmentRows).toHaveLength(0)
    evidence.rejected = { summary: rejectedSummary, before: afterPublish, after: afterReject }

    // 服务端实时投影还必须在真实列表和刷新后的详情页明确显示不可提交状态。
    await starter.page.goto('/office/draft')
    const row = starter.page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: businessKey })
    await expect(row).toHaveCount(1)
    await expect(row.getByText('V1', { exact: true })).toBeVisible()
    await expect(row.getByText('不可提交', { exact: true })).toBeVisible()
    await row.locator('button.el-button--primary').click()
    await expect(starter.page).toHaveURL(
      new RegExp(`/workflow/process-draft/${draftId}(?:[/?]|$)`, 'u'))
    await expect(starter.page.getByText('流程定义版本已过期', { exact: true })).toBeVisible()
    await expect(starter.page.getByRole('button', { name: '正式提交', exact: true })).toBeDisabled()
    const afterRefresh = draftSubmissionSnapshot(draftId, businessKey, assets.modelKey)
    expect(afterRefresh, '列表和详情刷新只能读取可用性投影，不得产生写副作用').toEqual(afterReject)
    evidence.refreshed = afterRefresh

    failed = false
  } finally {
    await testInfo.attach('draft-version-expired-evidence.json', {
      body: Buffer.from(JSON.stringify(evidence, null, 2)), contentType: 'application/json'
    })
    await Promise.allSettled([starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-DRAFT-003] 两个真实标签并发提交同一草稿复用唯一实例', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  const assets = scenarioAssets('UI-DRAFT-003')
  const businessKey = `${assets.prefix}_并发提交业务主键`
  const subject = `${assets.prefix}_并发提交正文`
  const evidence = { before: null, concurrency: null, submitted: null, completed: null }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify({ ...assets, businessKey }, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let secondPage
  let barrier
  let failed = true
  try {
    await createDraftTextModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    await openProcessStart(starter.page, assets.modelName)
    await starter.page.getByPlaceholder('可选').fill(businessKey)
    await processTextInput(starter.page).fill(subject)
    const created = await saveDraftThroughUi(starter.page, '/workflow/process/draft', 'POST')
    const draftId = requireDraftId(created)
    assets.draftIds.push(draftId)
    const definitionId = String(created.data?.processDefinitionId || '')
    const deploymentId = String(created.data?.deploymentId || '')
    expect(definitionId, '草稿必须返回精确定义主键').not.toBe('')
    expect(deploymentId, '草稿必须返回精确部署主键').not.toBe('')

    secondPage = await starter.page.context().newPage()
    await secondPage.goto(`/workflow/process-draft/${draftId}`)
    await expect(secondPage.getByPlaceholder('可选')).toHaveValue(businessKey)
    await expect(processTextInput(secondPage)).toHaveValue(subject)
    await expect(starter.page.getByRole('button', { name: '正式提交', exact: true })).toBeEnabled()
    await expect(secondPage.getByRole('button', { name: '正式提交', exact: true })).toBeEnabled()

    const before = draftSubmissionSnapshot(draftId, businessKey, assets.modelKey)
    expect(before.draftRows).toHaveLength(1)
    expect(before.draftRows[0].slice(0, 7)).toEqual([
      definitionId, '1', deploymentId, 'ACTIVE', '1', '', businessKey
    ])
    expect(before.auditRows).toEqual([['CREATED', '', 'ACTIVE', '0', '1', '']])
    expect(before.historyProcessRows).toHaveLength(0)
    evidence.before = before

    barrier = await installConcurrentSubmitBarrier([starter.page, secondPage], draftId)
    const [firstOutcome, secondOutcome] = await Promise.all([
      submitDraftAndObserve(starter.page, draftId),
      submitDraftAndObserve(secondPage, draftId)
    ])
    const summaries = [draftSubmitSummary(firstOutcome), draftSubmitSummary(secondOutcome)]
    const barrierEvidence = barrier.evidence()
    await barrier.stop()
    barrier = null
    expect(barrierEvidence.arrivals, '两个真实标签都必须在栅栏释放前发出正式请求').toBe(2)
    expect(barrierEvidence.arrivalDeltaMs, '两个标签必须形成实际重叠的并发窗口').not.toBeNull()
    for (const summary of summaries) {
      expect(summary.httpStatus).toBe(200)
      expect(summary.code).toBe(200)
      expect(summary.requestExpectedVersion, '两个标签都必须持有提交前 revision 1').toBe(1)
      expect(summary.draftId).toBe(draftId)
      expect(summary.processInstanceId).not.toBe('')
      expect(summary.processDefinitionId).toBe(definitionId)
      expect(summary.revisionNo).toBe(2)
    }
    expect(new Set(summaries.map(item => item.processInstanceId)).size,
      '两个并发响应必须复用同一流程实例').toBe(1)
    assets.processInstanceId = summaries[0].processInstanceId
    await expect(starter.page).toHaveURL(
      new RegExp(`/workflow/process-detail/${assets.processInstanceId}(?:[/?]|$)`, 'u'))
    await expect(secondPage).toHaveURL(
      new RegExp(`/workflow/process-detail/${assets.processInstanceId}(?:[/?]|$)`, 'u'))

    const submitted = draftSubmissionSnapshot(draftId, businessKey, assets.modelKey)
    expectUniqueSubmittedDraft(submitted, {
      draftId, processInstanceId: assets.processInstanceId, businessKey, definitionId, deploymentId
    })
    evidence.concurrency = { barrier: barrierEvidence, responses: summaries }
    evidence.submitted = submitted

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const workbench = new WorkflowWorkbenchPage(approver.page)
    await workbench.claimProcess(assets.modelName)
    await workbench.approveProcess(assets.modelName, `${assets.prefix}_并发幂等审批通过`)
    const completed = draftSubmissionSnapshot(draftId, businessKey, assets.modelKey)
    expect(completed.draftRows, '审批完成不得再次推进草稿 revision').toEqual(submitted.draftRows)
    expect(completed.auditRows, '审批完成不得追加草稿状态迁移').toEqual(submitted.auditRows)
    expect(completed.historyProcessRows).toHaveLength(1)
    expect(completed.historyProcessRows[0].slice(0, 5)).toEqual([
      assets.processInstanceId, definitionId, businessKey, '1', ''
    ])
    expect(completed.runtimeProcessCount).toBe(0)
    expect(completed.runtimeTaskCount).toBe(0)
    expect(completed.historyTaskRows).toHaveLength(1)
    expect(completed.historyTaskRows[0][2], '唯一审批任务必须自然完成').toBe('1')
    expect(completed.historyTaskRows[0][3], '自然完成任务不得带删除原因').toBe('')
    evidence.completed = completed

    failed = false
  } finally {
    await barrier?.stop().catch(() => {})
    await secondPage?.close().catch(() => {})
    await testInfo.attach('draft-concurrent-submit-evidence.json', {
      body: Buffer.from(JSON.stringify(evidence, null, 2)), contentType: 'application/json'
    })
    await Promise.allSettled([
      approver?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-ATTACH-001] 附件TEMP、DRAFT、BOUND、下载哈希、对象权限和TEMP清理保持一致', async ({ browser }, testInfo) => {
  test.setTimeout(150_000)
  const assets = scenarioAssets('UI-ATTACH-001')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let approver
  let auditor
  let failed = true
  try {
    await createDraftAttachmentModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    await openProcessStart(starter.page, assets.modelName)
    const attachmentName = `${assets.prefix}_证明.txt`
    const attachmentBytes = Buffer.from(`workflow ui attachment ${assets.prefix}\n`, 'utf8')
    const expectedSha256 = createHash('sha256').update(attachmentBytes).digest('hex')
    const uploaded = await uploadAttachmentThroughUi(starter.page, attachmentName, attachmentBytes)
    const attachmentId = String(uploaded.attachmentId)
    assets.attachmentIds.push(attachmentId)
    expect(uploaded.status).toBe('TEMP')
    expect(uploaded.fieldName).toBe('proofFiles')
    expect(uploaded.sha256).toBe(expectedSha256)
    const tempRows = queryReadOnly(
      `SELECT attachment_status, sha256, storage_key, file_size FROM wf_attachment WHERE attachment_id = '${sqlLiteral(attachmentId)}'`
    )
    expect(tempRows).toHaveLength(1)
    expect(tempRows[0][0]).toBe('TEMP')
    expect(tempRows[0][1]).toBe(expectedSha256)
    expect(Number(tempRows[0][3])).toBe(attachmentBytes.length)
    const boundFilePath = attachmentStoragePath(tempRows[0][2])
    expect(existsSync(boundFilePath), 'TEMP 私有附件物理文件必须存在').toBe(true)
    expect(await downloadAttachmentThroughUi(
      starter.page, attachmentId, attachmentName, attachmentBytes)).toBe(expectedSha256)

    const businessKey = `${assets.prefix}_附件业务`
    const subject = `${assets.prefix}_附件申请`
    await starter.page.getByPlaceholder('可选').fill(businessKey)
    await starter.page.getByPlaceholder('请输入申请主题').fill(subject)
    const created = await saveDraftThroughUi(starter.page, '/workflow/process/draft', 'POST')
    const draftId = requireDraftId(created)
    assets.draftIds.push(draftId)
    expect(queryReadOnly(
      `SELECT attachment_status, draft_id, process_instance_id IS NULL FROM wf_attachment WHERE attachment_id = '${sqlLiteral(attachmentId)}'`
    )).toEqual([['DRAFT', draftId, '1']])

    await logoutThroughUi(starter.page, 'workflow_starter')
    // 跨登录恢复必须使用新一轮验证码响应，不能把注销页尚未完成的请求当作登录证据。
    await starter.page.waitForLoadState('networkidle')
    await loginThroughUi(starter.page, workflowAccounts.workflow_starter)
    await continueDraftThroughList(starter.page, businessKey, draftId)
    await expect(starter.page.locator('.workflow-attachment-upload .el-upload-list__item')
      .filter({ hasText: attachmentName })).toHaveCount(1)
    expect(await downloadAttachmentThroughUi(
      starter.page, attachmentId, attachmentName, attachmentBytes)).toBe(expectedSha256)

    const submitPromise = starter.page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/draft/${draftId}/submit`, 'POST'))
    await starter.page.getByRole('button', { name: '正式提交', exact: true }).click()
    const submitted = await expectAjaxSuccess(
      await submitPromise, `/workflow/process/draft/${draftId}/submit`)
    assets.processInstanceId = String(submitted.data?.processInstanceId || submitted.data?.id || '')
    expect(assets.processInstanceId, '正式提交必须返回 Flowable 实例主键').not.toBe('')
    await expect(starter.page).toHaveURL(
      new RegExp(`/workflow/process-detail/${assets.processInstanceId}(?:[/?]|$)`, 'u'))
    expect(queryReadOnly(
      `SELECT attachment_status, COALESCE(draft_id, ''), process_instance_id, storage_deleted_time IS NULL FROM wf_attachment WHERE attachment_id = '${sqlLiteral(attachmentId)}'`
    )).toEqual([['BOUND', '', assets.processInstanceId, '1']])

    auditor = await openRoleSession(browser, 'workflow_auditor', testInfo)
    const beforeUnauthorized = queryReadOnly(
      `SELECT attachment_status, process_instance_id, update_time FROM wf_attachment WHERE attachment_id = '${sqlLiteral(attachmentId)}'`
    )
    const deniedPromise = auditor.page.waitForResponse(response => matchesEndpoint(
      response, '/workflow/process/detail', 'GET'))
    await auditor.page.goto(`/workflow/process-detail/${assets.processInstanceId}`)
    const deniedPayload = await (await deniedPromise).json()
    expect(deniedPayload?.code, '无对象关系的审计账号直接打开详情必须被拒绝').toBe(403)
    expect(queryReadOnly(
      `SELECT attachment_status, process_instance_id, update_time FROM wf_attachment WHERE attachment_id = '${sqlLiteral(attachmentId)}'`
    ), '越权页面访问不得改变附件元数据').toEqual(beforeUnauthorized)

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.modelName)
    const todoRow = await approverWorkbench.filterRow('/office/todo', '请输入流程名称', assets.modelName)
    await todoRow.locator('button').first().click()
    await expect(approver.page).toHaveURL(/\/workflow\/process-detail\//u)
    // 开始表单附件属于历史提交快照；当前审批节点未绑定独立表单时需切换历史表单页签查看。
    await approver.page.getByRole('tab', { name: /历史表单/u }).click()
    expect(await downloadAttachmentThroughUi(
      approver.page, attachmentId, attachmentName, attachmentBytes)).toBe(expectedSha256)
    await approverWorkbench.approveProcess(assets.modelName, `${assets.prefix}_附件核验通过`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL FROM ACT_HI_PROCINST WHERE PROC_INST_ID_ = '${sqlLiteral(assets.processInstanceId)}'`
    )).toEqual([['1']])

    await openProcessStart(starter.page, assets.modelName)
    const temporaryName = `${assets.prefix}_临时删除.txt`
    const temporary = await uploadAttachmentThroughUi(
      starter.page, temporaryName, Buffer.from(`temporary ${assets.prefix}\n`, 'utf8'))
    const temporaryId = String(temporary.attachmentId)
    assets.attachmentIds.push(temporaryId)
    const temporaryStorage = queryReadOnly(
      `SELECT storage_key FROM wf_attachment WHERE attachment_id = '${sqlLiteral(temporaryId)}'`
    )
    expect(temporaryStorage).toHaveLength(1)
    const temporaryFilePath = attachmentStoragePath(temporaryStorage[0][0])
    const temporaryItem = starter.page.locator('.workflow-attachment-upload .el-upload-list__item')
      .filter({ hasText: temporaryName })
    const temporaryDeletePromise = starter.page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/attachment/${temporaryId}`, 'DELETE'))
    await temporaryItem.hover()
    await temporaryItem.locator('.el-icon--close').click()
    await expectAjaxSuccess(await temporaryDeletePromise, `/workflow/attachment/${temporaryId}`)
    await expect(temporaryItem, 'TEMP 删除成功后页面必须移除旧附件引用').toHaveCount(0)
    expect(queryReadOnly(
      `SELECT attachment_status, storage_deleted_time IS NOT NULL FROM wf_attachment WHERE attachment_id = '${sqlLiteral(temporaryId)}'`
    )).toEqual([['DELETED', '1']])
    expect(existsSync(temporaryFilePath), 'TEMP 删除后私有物理文件必须立即清理').toBe(false)
    failed = false
  } finally {
    await Promise.allSettled([
      auditor?.close(failed), approver?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-ATTACH-002] 同一字段删除TEMP附件后可立即上传不同文件', async ({ browser }, testInfo) => {
  const assets = scenarioAssets('UI-ATTACH-002')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let failed = true
  try {
    await createDraftAttachmentModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    await openProcessStart(starter.page, assets.modelName)
    const firstName = `${assets.prefix}_先上传后删除.txt`
    const first = await uploadAttachmentThroughUi(
      starter.page, firstName, Buffer.from(`first ${assets.prefix}\n`, 'utf8'))
    const firstId = String(first.attachmentId)
    assets.attachmentIds.push(firstId)
    const firstItem = starter.page.locator('.workflow-attachment-upload .el-upload-list__item')
      .filter({ hasText: firstName })
    const deletePromise = starter.page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/attachment/${firstId}`, 'DELETE'))
    await firstItem.hover()
    await firstItem.locator('.el-icon--close').click()
    await expectAjaxSuccess(await deletePromise, `/workflow/attachment/${firstId}`)
    await expect(firstItem, '删除完成后页面必须移除第一个附件').toHaveCount(0)

    const secondName = `${assets.prefix}_立即上传的不同文件.txt`
    let secondRequestCount = 0
    const countSecondUpload = request => {
      const pathname = new URL(request.url()).pathname
      if (pathname.endsWith('/workflow/attachment') && request.method() === 'POST') secondRequestCount += 1
    }
    starter.page.on('request', countSecondUpload)
    const { payload } = await uploadAttachmentResponseThroughUi(
      starter.page, secondName, Buffer.from(`second ${assets.prefix}\n`, 'utf8'))
    starter.page.off('request', countSecondUpload)
    const secondRows = queryReadOnly(
      `SELECT attachment_id, attachment_status FROM wf_attachment WHERE original_name = '${sqlLiteral(secondName)}'`
    )
    const errorMessages = starter.page.getByText(String(payload?.msg || ''), { exact: true })
    const visibleErrorCount = payload?.code === 200 ? 0 : await errorMessages.count()
    const evidence = {
      secondRequestCount,
      visibleErrorCount,
      response: {
        code: payload?.code ?? null,
        subCode: payload?.subCode ?? payload?.data?.subCode ?? null,
        msg: String(payload?.msg || '').slice(0, 500)
      },
      secondAttachmentRows: secondRows
    }
    await testInfo.attach('repeat-submit-evidence.json', {
      body: Buffer.from(JSON.stringify(evidence, null, 2)), contentType: 'application/json'
    })
    expect(secondRequestCount, '第二个不同文件只能由前端发送一次上传请求').toBe(1)
    if (payload?.code !== 200) {
      await expect(errorMessages.first(), '后端拒绝上传时页面必须至少回显一次错误').toBeVisible()
      expect(secondRows, '被拒绝的上传不得产生附件元数据').toHaveLength(0)
    }
    expect(payload?.code, '删除一个 TEMP 文件后，用户应可立即上传另一个不同文件').toBe(200)
    failed = false
  } finally {
    await Promise.allSettled([starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-ATTACH-003] 删除草稿后附件元数据和物理文件异步清理', async ({ browser }, testInfo) => {
  test.setTimeout(90_000)
  const assets = scenarioAssets('UI-ATTACH-003')
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })
  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let failed = true
  try {
    await createDraftAttachmentModel(designer.page, assets)
    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    await openProcessStart(starter.page, assets.modelName)
    const attachmentName = `${assets.prefix}_随草稿删除.txt`
    const attachment = await uploadAttachmentThroughUi(
      starter.page, attachmentName, Buffer.from(`draft delete ${assets.prefix}\n`, 'utf8'))
    const attachmentId = String(attachment.attachmentId)
    assets.attachmentIds.push(attachmentId)
    const storageRows = queryReadOnly(
      `SELECT storage_key FROM wf_attachment WHERE attachment_id = '${sqlLiteral(attachmentId)}'`
    )
    expect(storageRows).toHaveLength(1)
    const filePath = attachmentStoragePath(storageRows[0][0])
    expect(existsSync(filePath), '草稿保存前 TEMP 附件物理文件必须存在').toBe(true)
    await starter.page.getByPlaceholder('请输入申请主题').fill(`${assets.prefix}_待删除草稿`)
    const created = await saveDraftThroughUi(starter.page, '/workflow/process/draft', 'POST')
    const draftId = requireDraftId(created)
    assets.draftIds.push(draftId)
    expect(queryReadOnly(
      `SELECT attachment_status, draft_id FROM wf_attachment WHERE attachment_id = '${sqlLiteral(attachmentId)}'`
    )).toEqual([['DRAFT', draftId]])
    const deletePromise = starter.page.waitForResponse(response => matchesEndpoint(
      response, `/workflow/process/draft/${draftId}`, 'DELETE'))
    await starter.page.getByRole('button', { name: '删除草稿', exact: true }).click()
    await starter.page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
    await expectAjaxSuccess(await deletePromise, `/workflow/process/draft/${draftId}`)
    expect(queryReadOnly(
      `SELECT draft_status FROM wf_process_draft WHERE draft_id = '${sqlLiteral(draftId)}'`
    )).toEqual([['DELETED']])
    await expectDraftAttachmentCleanup(attachmentId, filePath)
    failed = false
  } finally {
    await Promise.allSettled([starter?.close(failed), designer.close(failed)])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
