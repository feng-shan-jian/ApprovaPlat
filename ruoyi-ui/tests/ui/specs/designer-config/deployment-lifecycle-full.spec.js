import { test, expect } from '@playwright/test'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDeploymentPage } from '../../page-objects/deployment.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'

/**
 * 生成部署生命周期用例的唯一 ASCII 资产前缀。
 * @returns {string} 可用于流程 key、分类 code 和业务名称的稳定前缀。
 */
function deploymentPrefix() {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual')
    .replace(/[^A-Za-z0-9]/gu, '').slice(-16)
  return `E2E_UI_${runId}_deploy_${Date.now().toString(36)}`
}

/**
 * 转义只读 SQL 中由当前测试生成的字符串字面量。
 * @param {string} value 测试资产主键、流程 key 或实例主键。
 * @returns {string} 可安全放入单引号字面量的值。
 */
function sqlLiteral(value) {
  return String(value).replaceAll("'", "''")
}

/**
 * 读取指定部署及可选实例的跨表一致性快照。
 * @param {string} deploymentId Flowable 部署主键。
 * @param {string} processInstanceId 可选流程实例主键。
 * @returns {string[][]} 部署、定义、资源、模型、运行和历史引用计数。
 */
function deploymentSnapshot(deploymentId, processInstanceId = '') {
  const deployment = sqlLiteral(deploymentId)
  const instance = sqlLiteral(processInstanceId)
  return queryReadOnly(`SELECT
    (SELECT COUNT(*) FROM ACT_RE_DEPLOYMENT WHERE ID_='${deployment}'),
    (SELECT COUNT(*) FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_='${deployment}'),
    (SELECT COUNT(*) FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_='${deployment}'),
    (SELECT COUNT(*) FROM ACT_RE_MODEL WHERE DEPLOYMENT_ID_='${deployment}'),
    (SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE '${instance}'<>'' AND PROC_INST_ID_='${instance}'),
    (SELECT COUNT(*) FROM ACT_RU_TASK WHERE '${instance}'<>'' AND PROC_INST_ID_='${instance}'),
    (SELECT COUNT(*) FROM ACT_HI_PROCINST WHERE '${instance}'<>'' AND PROC_INST_ID_='${instance}'),
    (SELECT COUNT(*) FROM ACT_HI_TASKINST WHERE '${instance}'<>'' AND PROC_INST_ID_='${instance}')`)
}

/**
 * 读取部署、活动草稿和草稿审计的跨表一致性快照。
 * @param {string} deploymentId Flowable 部署主键。
 * @param {string} draftId 申请草稿 UUID。
 * @returns {{deployment:string[][],draft:string[][],audit:string[][]}} 删除门禁前后可直接比较的只读快照。
 */
function draftReferenceSnapshot(deploymentId, draftId) {
  const deployment = sqlLiteral(deploymentId)
  const draft = sqlLiteral(draftId)
  return {
    deployment: queryReadOnly(`SELECT
      (SELECT COUNT(*) FROM ACT_RE_DEPLOYMENT WHERE ID_='${deployment}'),
      (SELECT COUNT(*) FROM ACT_RE_PROCDEF WHERE DEPLOYMENT_ID_='${deployment}'),
      (SELECT COUNT(*) FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_='${deployment}'),
      (SELECT COUNT(*) FROM ACT_RE_MODEL WHERE DEPLOYMENT_ID_='${deployment}')`),
    draft: queryReadOnly(`SELECT process_definition_id,process_definition_key,
      process_definition_version,deployment_id,process_name,draft_status,revision_no,
      COALESCE(business_key,''),submitted_process_instance_id IS NULL,deleted_time IS NULL,
      form_snapshot_sha256
      FROM wf_process_draft WHERE draft_id='${draft}'`),
    audit: queryReadOnly(`SELECT action_type,COALESCE(from_status,''),to_status,
      COALESCE(from_revision,0),to_revision,process_instance_id IS NULL
      FROM wf_process_draft_audit WHERE draft_id='${draft}' ORDER BY audit_id`)
  }
}

/**
 * 从流程发起页通过真实按钮保存一份活动申请草稿。
 * @param {import('@playwright/test').Page} page 已登录流程发起人的页面。
 * @param {string} processName 唯一流程名称。
 * @param {string} businessKey 唯一业务主键。
 * @param {string} formValue 流程表单首个文本字段值。
 * @returns {Promise<{draftId:string,payload:object}>} 服务端生成的草稿 UUID 和成功响应。
 */
async function createDraftThroughUi(page, processName, businessKey, formValue) {
  const row = await new WorkflowWorkbenchPage(page)
    .filterRow('/office/create', '请输入流程名称', processName)
  await row.locator('button').first().click()
  await expect(page).toHaveURL(/\/workflow\/process-start\//u)
  await page.getByPlaceholder('可选').fill(businessKey)
  const formInput = page.locator('.workflow-form-renderer input:not([type="file"])').first()
  await expect(formInput, '流程发起页必须渲染正式部署表单').toBeVisible()
  await formInput.fill(formValue)

  const responsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/process/draft', 'POST'))
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  const payload = await expectAjaxSuccess(await responsePromise, '/workflow/process/draft')
  const draftId = String(payload?.data?.draftId || payload?.data?.id || '')
  expect(draftId, '草稿写入必须返回正式 UUID').toMatch(/^[0-9a-f-]{36}$/iu)
  return { draftId, payload }
}

/**
 * 从本人草稿列表筛选并真实删除指定活动草稿。
 * @param {import('@playwright/test').Page} page 已登录流程发起人的页面。
 * @param {string} processName 唯一流程名称。
 * @param {string} draftId 待删除草稿 UUID。
 * @param {number} expectedRevision 当前列表应携带的乐观锁版本。
 * @returns {Promise<{remove:object,list:object}>} 草稿删除和列表刷新成功响应。
 */
async function deleteDraftThroughList(page, processName, draftId, expectedRevision) {
  const initialResponsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/process/draft/list', 'GET'))
  await page.goto('/office/draft')
  await expectAjaxSuccess(await initialResponsePromise, '/workflow/process/draft/list 初始化查询')
  const input = page.getByPlaceholder('请输入流程名称')
  await input.fill(processName)
  const queryForm = input.locator('xpath=ancestor::form[1]')
  const filteredResponsePromise = page.waitForResponse(response => {
    if (!matchesEndpoint(response, '/workflow/process/draft/list', 'GET')) return false
    return new URL(response.url()).searchParams.get('processName') === processName
  })
  await queryForm.getByRole('button', { name: '搜索', exact: true }).click()
  const filtered = await expectAjaxSuccess(
    await filteredResponsePromise, '/workflow/process/draft/list 筛选查询')
  expect(filtered.rows, '草稿列表接口必须只返回目标活动草稿').toHaveLength(1)
  expect(String(filtered.rows[0]?.draftId || filtered.rows[0]?.id || '')).toBe(draftId)
  expect(Number(filtered.rows[0]?.revisionNo)).toBe(expectedRevision)

  const row = page.locator('.workflow-draft-list .el-table__body-wrapper tbody tr')
    .filter({ hasText: processName })
  await expect(row, '本人草稿列表必须唯一回显目标草稿').toHaveCount(1)
  const endpoint = `/workflow/process/draft/${draftId}`
  const removeResponsePromise = page.waitForResponse(response => {
    if (!matchesEndpoint(response, endpoint, 'DELETE')) return false
    return Number(new URL(response.url()).searchParams.get('expectedVersion')) === expectedRevision
  })
  const listResponsePromise = page.waitForResponse(response => matchesEndpoint(
    response, '/workflow/process/draft/list', 'GET'))
  await row.locator('button.el-button--danger').click()
  await page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click()
  const remove = await expectAjaxSuccess(await removeResponsePromise, endpoint)
  await expect(page.getByText('草稿删除成功', { exact: true })).toBeVisible()
  const list = await expectAjaxSuccess(await listResponsePromise, '/workflow/process/draft/list 删除刷新')
  await expect(row).toHaveCount(0)
  expect(list.rows, '删除后当前筛选结果必须为空').toHaveLength(0)
  return { remove, list }
}

/**
 * 核对流程定义及其运行实例已经同步进入指定挂起状态。
 * @param {string} definitionId Flowable 流程定义主键。
 * @param {string} processInstanceId Flowable 流程实例主键。
 * @param {1|2} expectedState Flowable 激活状态 1 或挂起状态 2。
 * @returns {void} 定义、执行树和活动任务状态一致时正常返回。
 */
function expectSuspensionState(definitionId, processInstanceId, expectedState) {
  const definition = sqlLiteral(definitionId)
  const instance = sqlLiteral(processInstanceId)
  const state = String(expectedState)
  expect(queryReadOnly(
    `SELECT SUSPENSION_STATE_ FROM ACT_RE_PROCDEF WHERE ID_='${definition}'`
  ), '流程定义挂起状态必须唯一').toEqual([[state]])
  expect(queryReadOnly(
    `SELECT DISTINCT SUSPENSION_STATE_ FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${instance}' ORDER BY SUSPENSION_STATE_`
  ), '运行执行树必须同步定义状态').toEqual([[state]])
  expect(queryReadOnly(
    `SELECT DISTINCT SUSPENSION_STATE_ FROM ACT_RU_TASK WHERE PROC_INST_ID_='${instance}' ORDER BY SUSPENSION_STATE_`
  ), '活动审批任务必须同步定义状态').toEqual([[state]])
}

test('@full [UI-DEPLOY-001] 发布版本、挂起激活及引用保护删除形成真实部署生命周期', async ({ browser }, testInfo) => {
  test.setTimeout(420_000)
  const prefix = deploymentPrefix()
  const assets = {
    prefix,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_category`,
    formName: `${prefix}_表单`,
    referencedModelName: `${prefix}_引用流程`,
    referencedModelKey: `${prefix}_referenced`,
    disposableModelName: `${prefix}_可删除流程`,
    disposableModelKey: `${prefix}_disposable`,
    referencedDefinitions: [],
    disposableDefinition: null,
    processInstanceId: ''
  }
  const evidence = { list: [], versions: [], states: [], conflicts: [], deletion: null }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let administrator
  let approver
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: prefix
    })
    await configuration.createTextForm({ name: assets.formName, remark: prefix })

    // 引用流程先由真实画布发布 V1，再从已部署版本修改并保存为 V2。
    await configuration.createModel({
      name: assets.referencedModelName,
      key: assets.referencedModelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} 部署引用保护流程`
    })
    await configuration.openDesigner(assets.referencedModelKey)
    let designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRole('流程审批人', `${prefix}_V1审批`)
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.referencedModelKey)

    await configuration.openDesigner(assets.referencedModelKey)
    designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRole('流程审批人', `${prefix}_V2审批`)
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.referencedModelKey)

    const referencedKey = sqlLiteral(assets.referencedModelKey)
    assets.referencedDefinitions = queryReadOnly(
      `SELECT VERSION_,ID_,DEPLOYMENT_ID_,SUSPENSION_STATE_ FROM ACT_RE_PROCDEF WHERE KEY_='${referencedKey}' ORDER BY VERSION_`
    ).map(row => ({ version: Number(row[0]), definitionId: row[1], deploymentId: row[2], suspensionState: Number(row[3]) }))
    expect(assets.referencedDefinitions, '真实保存和部署必须形成 V1/V2 两个不可变定义').toHaveLength(2)
    expect(assets.referencedDefinitions.map(item => item.version)).toEqual([1, 2])
    // 新版本发布后旧定义只停止承接新实例，最新版保持激活，这是产品的版本冻结契约。
    expect(assets.referencedDefinitions.map(item => item.suspensionState)).toEqual([2, 1])

    // 第二个流程只完成建模与部署，不创建草稿或实例，用于验证非级联物理删除成功路径。
    await configuration.createModel({
      name: assets.disposableModelName,
      key: assets.disposableModelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} 无引用可删除部署`
    })
    await configuration.openDesigner(assets.disposableModelKey)
    designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRole('流程审批人', `${prefix}_未发起审批`)
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.disposableModelKey)
    const disposableKey = sqlLiteral(assets.disposableModelKey)
    const disposableRows = queryReadOnly(
      `SELECT VERSION_,ID_,DEPLOYMENT_ID_,SUSPENSION_STATE_ FROM ACT_RE_PROCDEF WHERE KEY_='${disposableKey}'`
    )
    expect(disposableRows, '未引用流程必须形成唯一部署定义').toHaveLength(1)
    assets.disposableDefinition = {
      version: Number(disposableRows[0][0]), definitionId: disposableRows[0][1],
      deploymentId: disposableRows[0][2], suspensionState: Number(disposableRows[0][3])
    }

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.referencedModelName, `${prefix}_运行实例`)
    const latestDefinition = assets.referencedDefinitions[1]
    const instanceId = sqlLiteral(assets.processInstanceId)
    expect(queryReadOnly(
      `SELECT h.PROC_DEF_ID_,p.DEPLOYMENT_ID_,h.END_TIME_ IS NULL FROM ACT_HI_PROCINST h JOIN ACT_RE_PROCDEF p ON p.ID_=h.PROC_DEF_ID_ WHERE h.PROC_INST_ID_='${instanceId}'`
    ), '发起页必须使用最新 V2 定义并形成运行实例').toEqual([
      [latestDefinition.definitionId, latestDefinition.deploymentId, '1']
    ])

    administrator = await openRoleSession(browser, 'workflow_admin', testInfo)
    const deployments = new WorkflowDeploymentPage(administrator.page)
    const listPayload = await deployments.openAndFilter(assets.referencedModelKey)
    evidence.list.push({
      processKey: assets.referencedModelKey,
      version: listPayload.rows[0].version,
      definitionId: listPayload.rows[0].definitionId,
      deploymentId: listPayload.rows[0].deploymentId,
      suspended: listPayload.rows[0].suspended
    })
    expect(Number(listPayload.rows[0].version)).toBe(2)
    expect(listPayload.rows[0].definitionId).toBe(latestDefinition.definitionId)
    expect(listPayload.rows[0].deploymentId).toBe(latestDefinition.deploymentId)

    const initialVersions = await deployments.openVersions(assets.referencedModelKey, [
      { version: 2, status: '已激活' }, { version: 1, status: '已挂起' }
    ])
    evidence.versions.push(initialVersions.rows.map(row => ({
      version: row.version, definitionId: row.definitionId, deploymentId: row.deploymentId,
      suspended: row.suspended
    })))
    await deployments.closeVersions()

    evidence.states.push({ target: 'suspended', response: await deployments.toggleLatestState(
      assets.referencedModelKey, '已挂起'
    ) })
    expectSuspensionState(latestDefinition.definitionId, assets.processInstanceId, 2)
    const suspendedVersions = await deployments.openVersions(assets.referencedModelKey, [
      { version: 2, status: '已挂起' }, { version: 1, status: '已挂起' }
    ])
    evidence.versions.push(suspendedVersions.rows.map(row => ({
      version: row.version, definitionId: row.definitionId, suspended: row.suspended
    })))
    await deployments.closeVersions()

    evidence.states.push({ target: 'active', response: await deployments.toggleLatestState(
      assets.referencedModelKey, '已激活'
    ) })
    expectSuspensionState(latestDefinition.definitionId, assets.processInstanceId, 1)

    const runtimeSnapshot = deploymentSnapshot(latestDefinition.deploymentId, assets.processInstanceId)
    const runtimeConflict = await deployments.deleteLatestExpectConflict(
      assets.referencedModelKey, latestDefinition.deploymentId, '部署仍有运行中的流程实例'
    )
    expect(deploymentSnapshot(latestDefinition.deploymentId, assets.processInstanceId),
      '运行实例删除拒绝不得改写部署、模型、资源、运行或历史数据').toEqual(runtimeSnapshot)
    evidence.conflicts.push({ type: 'runtime', payload: runtimeConflict, snapshot: runtimeSnapshot })

    approver = await openRoleSession(browser, 'workflow_approver', testInfo)
    const approverWorkbench = new WorkflowWorkbenchPage(approver.page)
    await approverWorkbench.claimProcess(assets.referencedModelName)
    await approverWorkbench.approveProcess(assets.referencedModelName, `${prefix}_完成后验证历史保护`)
    expect(queryReadOnly(
      `SELECT END_TIME_ IS NOT NULL,COALESCE(DELETE_REASON_,'') FROM ACT_HI_PROCINST WHERE PROC_INST_ID_='${instanceId}'`
    ), '审批完成后必须形成自然结束历史实例').toEqual([['1', '']])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_='${instanceId}'`
    ), '审批完成后运行执行树必须清空').toEqual([['0']])

    const historicSnapshot = deploymentSnapshot(latestDefinition.deploymentId, assets.processInstanceId)
    const historicConflict = await deployments.deleteLatestExpectConflict(
      assets.referencedModelKey, latestDefinition.deploymentId, '部署仍有流程历史记录'
    )
    expect(deploymentSnapshot(latestDefinition.deploymentId, assets.processInstanceId),
      '历史实例删除拒绝不得改写部署、模型、资源或历史数据').toEqual(historicSnapshot)
    evidence.conflicts.push({ type: 'history', payload: historicConflict, snapshot: historicSnapshot })

    const disposableBefore = deploymentSnapshot(assets.disposableDefinition.deploymentId)
    expect(disposableBefore[0][0], '删除前部署必须真实存在').toBe('1')
    expect(disposableBefore[0][1], '删除前流程定义必须真实存在').toBe('1')
    expect(Number(disposableBefore[0][2]), '部署必须至少包含一份 BPMN 资源').toBeGreaterThan(0)
    expect(disposableBefore[0][3], '删除前作者模型必须关联当前部署').toBe('1')
    const disposableList = await deployments.openAndFilter(assets.disposableModelKey)
    expect(disposableList.rows[0].deploymentId).toBe(assets.disposableDefinition.deploymentId)
    evidence.deletion = await deployments.deleteLatest(
      assets.disposableModelKey, assets.disposableDefinition.deploymentId
    )
    expect(deploymentSnapshot(assets.disposableDefinition.deploymentId),
      '删除成功后部署、定义、部署资源和模型关联必须全部解除').toEqual([
      ['0', '0', '0', '0', '0', '0', '0', '0']
    ])
    expect(queryReadOnly(
      `SELECT VERSION_,COALESCE(DEPLOYMENT_ID_,'') FROM ACT_RE_MODEL WHERE KEY_='${disposableKey}' ORDER BY VERSION_ DESC`
    ), '部署删除必须保留作者模型并清空部署关联').toEqual([['1', '']])

    failed = false
  } finally {
    await testInfo.attach('deployment-lifecycle-evidence.json', {
      body: Buffer.from(JSON.stringify(evidence, null, 2)), contentType: 'application/json'
    })
    await Promise.allSettled([
      approver?.close(failed), administrator?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})

test('@full [UI-DEPLOY-002] 活动草稿阻止部署删除且草稿删除后解除门禁', async ({ browser }, testInfo) => {
  test.setTimeout(300_000)
  const prefix = deploymentPrefix()
  const assets = {
    prefix,
    categoryName: `${prefix}_草稿分类`,
    categoryCode: `${prefix}_draft_category`,
    formName: `${prefix}_草稿表单`,
    modelName: `${prefix}_草稿引用流程`,
    modelKey: `${prefix}_draft_reference`,
    businessKey: `${prefix}_业务主键`,
    definitionId: '',
    deploymentId: '',
    draftId: ''
  }
  const evidence = { creation: null, conflict: null, deletion: null }
  await testInfo.attach('asset-plan.json', {
    body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
  })

  const designer = await openRoleSession(browser, 'workflow_designer', testInfo)
  let starter
  let administrator
  let failed = true
  try {
    const configuration = new WorkflowConfigurationPage(designer.page)
    await configuration.createCategory({
      name: assets.categoryName, code: assets.categoryCode, remark: prefix
    })
    await configuration.createTextForm({
      name: assets.formName, remark: `${prefix} 活动草稿部署引用门禁`
    })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} 活动草稿部署引用门禁`
    })
    await configuration.openDesigner(assets.modelKey)
    const designerPage = new WorkflowDesignerPage(designer.page)
    await designerPage.configureCandidateRole('流程审批人', `${prefix}_草稿审批`)
    await designerPage.validateAndSave()
    await designerPage.returnToModels()
    await configuration.deployModel(assets.modelKey)

    const modelKey = sqlLiteral(assets.modelKey)
    const definitions = queryReadOnly(
      `SELECT ID_,DEPLOYMENT_ID_,VERSION_,SUSPENSION_STATE_ FROM ACT_RE_PROCDEF WHERE KEY_='${modelKey}'`
    )
    expect(definitions, '真实画布保存和部署必须形成唯一活动定义').toHaveLength(1)
    assets.definitionId = definitions[0][0]
    assets.deploymentId = definitions[0][1]
    expect(definitions[0].slice(2)).toEqual(['1', '1'])

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    const created = await createDraftThroughUi(
      starter.page, assets.modelName, assets.businessKey, `${prefix}_草稿正文`)
    assets.draftId = created.draftId
    const activeSnapshot = draftReferenceSnapshot(assets.deploymentId, assets.draftId)
    expect(activeSnapshot.deployment[0][0], '草稿创建后部署必须存在').toBe('1')
    expect(activeSnapshot.deployment[0][1], '草稿必须绑定唯一流程定义所在部署').toBe('1')
    expect(Number(activeSnapshot.deployment[0][2]), '部署必须包含 BPMN 资源').toBeGreaterThan(0)
    expect(activeSnapshot.deployment[0][3], '作者模型必须关联当前部署').toBe('1')
    expect(activeSnapshot.draft).toHaveLength(1)
    expect(activeSnapshot.draft[0].slice(0, 9)).toEqual([
      assets.definitionId, assets.modelKey, '1', assets.deploymentId, assets.modelName,
      'ACTIVE', '1', assets.businessKey, '1'
    ])
    expect(activeSnapshot.draft[0][9], '活动草稿不得提前登记删除时间').toBe('1')
    expect(activeSnapshot.draft[0][10], '草稿必须冻结非空表单快照摘要').toMatch(/^[0-9a-f]{64}$/u)
    expect(activeSnapshot.audit).toEqual([['CREATED', '', 'ACTIVE', '0', '1', '1']])
    evidence.creation = { payload: created.payload, snapshot: activeSnapshot }

    administrator = await openRoleSession(browser, 'workflow_admin', testInfo)
    const deployments = new WorkflowDeploymentPage(administrator.page)
    const listPayload = await deployments.openAndFilter(assets.modelKey)
    expect(listPayload.rows[0]?.definitionId).toBe(assets.definitionId)
    expect(listPayload.rows[0]?.deploymentId).toBe(assets.deploymentId)
    const conflict = await deployments.deleteLatestExpectConflict(
      assets.modelKey, assets.deploymentId, '部署仍有未提交申请草稿，不能删除')
    const rejectedSnapshot = draftReferenceSnapshot(assets.deploymentId, assets.draftId)
    expect(rejectedSnapshot,
      '活动草稿删除拒绝不得改写部署、定义、资源、模型关联、草稿或审计').toEqual(activeSnapshot)
    evidence.conflict = { payload: conflict, before: activeSnapshot, after: rejectedSnapshot }

    // 只有草稿所有者从正式列表完成状态迁移后，部署删除门禁才允许解除。
    evidence.deletion = {
      draft: await deleteDraftThroughList(starter.page, assets.modelName, assets.draftId, 1)
    }
    const deletedDraftSnapshot = draftReferenceSnapshot(assets.deploymentId, assets.draftId)
    expect(deletedDraftSnapshot.deployment, '删除草稿不得删除或改写流程部署').toEqual(activeSnapshot.deployment)
    expect(deletedDraftSnapshot.draft[0].slice(0, 9)).toEqual([
      assets.definitionId, assets.modelKey, '1', assets.deploymentId, assets.modelName,
      'DELETED', '2', assets.businessKey, '1'
    ])
    expect(deletedDraftSnapshot.draft[0][9], '删除草稿必须登记删除时间').toBe('0')
    expect(deletedDraftSnapshot.audit).toEqual([
      ['CREATED', '', 'ACTIVE', '0', '1', '1'],
      ['DELETED', 'ACTIVE', 'DELETED', '1', '2', '1']
    ])

    evidence.deletion.deployment = await deployments.deleteLatest(
      assets.modelKey, assets.deploymentId)
    const finalSnapshot = draftReferenceSnapshot(assets.deploymentId, assets.draftId)
    expect(finalSnapshot.deployment,
      '门禁解除后部署、定义、部署资源和模型关联必须全部清理').toEqual([['0', '0', '0', '0']])
    expect(finalSnapshot.draft, '部署删除必须保留已删除草稿的不可变快照').toEqual(
      deletedDraftSnapshot.draft)
    expect(finalSnapshot.audit, '部署删除必须保留草稿不可变审计').toEqual(
      deletedDraftSnapshot.audit)
    expect(queryReadOnly(
      `SELECT VERSION_,COALESCE(DEPLOYMENT_ID_,'') FROM ACT_RE_MODEL WHERE KEY_='${modelKey}'`
    ), '部署删除必须保留作者模型并清空部署关联').toEqual([['1', '']])
    evidence.deletion.after = finalSnapshot

    failed = false
  } finally {
    await testInfo.attach('deployment-draft-reference-evidence.json', {
      body: Buffer.from(JSON.stringify(evidence, null, 2)), contentType: 'application/json'
    })
    await Promise.allSettled([
      administrator?.close(failed), starter?.close(failed), designer.close(failed)
    ])
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json'
    })
  }
})
