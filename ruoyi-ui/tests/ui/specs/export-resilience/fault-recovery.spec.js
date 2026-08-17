import { test, expect } from '@playwright/test'
import { loginThroughUi, logoutThroughUi } from '../../../e2e/fixtures/workflow.js'
import { loadWorkflowAccounts } from '../../../e2e/support/environment.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成依赖故障用例的唯一测试资产。
 * @param {string} caseId 可追踪用例编号。
 * @returns {{prefix:string,categoryName:string,categoryCode:string,formName:string,modelName:string,modelKey:string,draftId:string,processInstanceId:string}} 本轮资产登记。
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
    processInstanceId: ''
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
 * 读取草稿当前状态、Flowable 和附件的只读一致性快照。
 * @param {string} draftId 当前正式草稿 UUID。
 * @param {string} businessKey 当前唯一业务主键。
 * @returns {{draftRows:string[][],historyProcessRows:string[][],runtimeProcessCount:number,runtimeTaskCount:number,historyTaskRows:string[][],attachmentRows:string[][]}} 不包含表单正文的业务快照。
 */
function readBusinessSnapshot(draftId, businessKey) {
  const escapedDraftId = sqlLiteral(draftId)
  const escapedBusinessKey = sqlLiteral(businessKey)
  const draftRows = queryReadOnly(
    `SELECT draft_status, revision_no, COALESCE(submitted_process_instance_id, ''), COALESCE(business_key, ''), SHA2(form_values, 256), DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s.%f') FROM wf_process_draft WHERE draft_id = '${escapedDraftId}'`
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
