import { test, expect } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { DOMParser } from '@xmldom/xmldom'
import { WorkflowConfigurationPage } from '../../page-objects/configuration.js'
import { WorkflowDesignerPage } from '../../page-objects/designer.js'
import { WorkflowIntegrationPage } from '../../page-objects/integration.js'
import { WorkflowWorkbenchPage } from '../../page-objects/workbench.js'
import { queryReadOnly } from '../../support/database.js'
import { openRoleSession } from '../../support/role-session.js'
import { expectAjaxSuccess, matchesEndpoint } from '../../../e2e/support/http.js'

/**
 * 生成满足测试资产命名约束且适用于稳定键的唯一前缀。
 * @param {import('@playwright/test').TestInfo} testInfo 当前 Playwright 用例信息。
 * @param {string} domain 当前配置功能域缩写。
 * @returns {string} 以 `E2E_UI_` 开头的 ASCII 唯一前缀。
 */
function assetPrefix(testInfo, domain) {
  const runId = String(process.env.FLOWABLE_E2E_RUN_ID || 'manual').replace(/[^A-Za-z0-9]/gu, '').slice(-20)
  const clock = Date.now().toString(36)
  return `E2E_UI_${runId}_${domain}_${testInfo.workerIndex}_${clock}`
}

/**
 * 返回当前 Element Plus 表格中包含唯一测试标识的行。
 * @param {import('@playwright/test').Page} page 当前配置页面。
 * @param {string} value 测试资产名称或稳定键。
 * @returns {import('@playwright/test').Locator} 唯一业务行定位器。
 */
function tableRow(page, value) {
  return page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: value })
}

/**
 * 点击 Element Plus 确认框的确定按钮。
 * @param {import('@playwright/test').Page} page 当前浏览器页面。
 * @returns {Promise<void>} 确认框关闭后结束。
 */
async function confirmDialog(page) {
  const messageBox = page.locator('.el-message-box')
  await expect(messageBox).toBeVisible()
  await messageBox.getByRole('button', { name: '确定', exact: true }).click()
  await expect(messageBox).toBeHidden()
}

/**
 * 在 Element Plus 对话框中选择单值下拉选项。
 * @param {import('@playwright/test').Page} page 当前浏览器页面。
 * @param {import('@playwright/test').Locator} dialog 当前业务对话框。
 * @param {string} label 表单项可见标签。
 * @param {string|RegExp} option 目标选项名称。
 * @returns {Promise<void>} 选项写入后结束。
 */
async function selectDialogOption(page, dialog, label, option) {
  const formLabel = dialog.locator('.el-form-item__label').filter({ hasText: label })
  await expect(formLabel, `${label} 标签必须唯一`).toHaveCount(1)
  await formLabel.locator('..').locator('.el-select').click()
  await page.getByRole('option', { name: option, exact: typeof option === 'string' }).click()
}

/**
 * 通过 DMN 管理页上传并部署一份正式来源决策。
 * @param {import('@playwright/test').Page} page 已完成流程管理员登录的浏览器页面。
 * @param {{resourceName:string,category:string,dmnXml:string}} deployment 资源名、分类和标准 DMN XML。
 * @returns {Promise<string>} 后端创建的 Flowable DMN 部署主键。
 */
async function deployDmnFromUi(page, deployment) {
  await page.getByRole('button', { name: '部署 DMN', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '部署 DMN 决策' })
  await dialog.locator('input[type="file"]').setInputFiles({
    name: deployment.resourceName,
    mimeType: 'application/xml',
    buffer: Buffer.from(deployment.dmnXml, 'utf8')
  })
  await expect(dialog.getByLabel('资源名')).toHaveValue(deployment.resourceName)
  await dialog.getByLabel('分类').fill(deployment.category)
  const responsePromise = page.waitForResponse(response => matchesEndpoint(response, '/workflow/dmn', 'POST'))
  await dialog.getByRole('button', { name: '部署', exact: true }).click()
  const payload = await expectAjaxSuccess(await responsePromise, '/workflow/dmn')
  await expect(page.getByText('DMN 决策部署成功', { exact: true })).toBeVisible()
  const deploymentId = String(payload.data?.deploymentId || '')
  expect(deploymentId, 'DMN 部署响应必须返回正式 deploymentId').not.toBe('')
  return deploymentId
}

/**
 * 返回 DMN 管理表中指定决策官方版本的唯一来源行。
 * @param {import('@playwright/test').Page} page 当前 DMN 管理页面。
 * @param {string} decisionKey 决策稳定 key。
 * @param {number} version Flowable 官方版本号。
 * @returns {import('@playwright/test').Locator} 同时匹配 key 和精确版本标签的表格行。
 */
function dmnVersionRow(page, decisionKey, version) {
  const versionTag = page.locator('.el-tag').filter({ hasText: new RegExp(`^v${version}$`, 'u') })
  return tableRow(page, decisionKey).filter({ has: versionTag })
}

/**
 * 生成固定返回单一字符串结果的标准 DMN 1.3 决策表。
 * @param {string} decisionKey 跨来源版本保持不变的官方决策 key。
 * @param {string} decisionName 用户在管理页和设计器中看到的决策名称。
 * @param {string} versionMarker 仅用于保证本版本 XML 元素标识唯一的短标记。
 * @param {string} routeValue 本版本写入流程变量 `route` 的结果。
 * @returns {string} 可由 Flowable DMN Engine 正式部署的 XML。
 */
function constantRouteDmnXml(decisionKey, decisionName, versionMarker, routeValue) {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions_${decisionKey}_${versionMarker}" name="${decisionName}" namespace="https://approvaplat.local/dmn/${decisionKey}">
  <decision id="${decisionKey}" name="${decisionName}">
    <decisionTable id="table_${decisionKey}_${versionMarker}" hitPolicy="FIRST">
      <input id="input_${versionMarker}"><inputExpression id="expr_${versionMarker}" typeRef="string"><text>"constant"</text></inputExpression></input>
      <output id="output_${versionMarker}" name="route" typeRef="string" />
      <rule id="rule_${versionMarker}"><inputEntry id="entry_${versionMarker}"><text>-</text></inputEntry><outputEntry id="result_${versionMarker}"><text>"${routeValue}"</text></outputEntry></rule>
    </decisionTable>
  </decision>
</definitions>`
}

test('@full [UI-CONFIG-001] 扩展注册表通过UI完成目录、版本、启停和受约束删除', async ({ browser }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'ext')
  const assets = { extensionName: `${prefix}_扩展`, extensionKey: `${prefix.toLowerCase()}.handler` }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/extension')
    await page.getByRole('button', { name: '新增目录', exact: true }).click()
    const createDialog = page.getByRole('dialog', { name: '新增扩展目录' })
    await createDialog.getByLabel('目录名称').fill(assets.extensionName)
    await createDialog.getByLabel('稳定键').fill(assets.extensionKey)
    await createDialog.getByLabel('业务说明').fill(`${prefix} UI配置回归`)
    await createDialog.getByRole('button', { name: '保存目录', exact: true }).click()
    await expect(page.getByText('扩展目录创建成功', { exact: true })).toBeVisible()

    await page.getByPlaceholder('名称或稳定键').fill(assets.extensionKey)
    await page.getByRole('button', { name: '查询', exact: true }).click()
    const row = tableRow(page, assets.extensionKey)
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('未发布')
    await expect(row).toContainText('已启用')

    await row.getByRole('button', { name: '发布新版本' }).click()
    const versionDialog = page.getByRole('dialog', { name: '发布不可变版本' })
    await selectDialogOption(page, versionDialog, '处理器', /设置流程变量/u)
    await versionDialog.getByRole('button', { name: '发布版本', exact: true }).click()
    await confirmDialog(page)
    await expect(row).toContainText('V1')
    await expect(row).toContainText('SET_VARIABLE')

    await row.getByRole('button', { name: '停用目录' }).click()
    await confirmDialog(page)
    await expect(row).toContainText('已停用')
    await row.getByRole('button', { name: '删除目录' }).click()
    await confirmDialog(page)
    await expect(page.getByText('扩展目录已删除', { exact: true })).toBeVisible()
    await expect(row).toHaveCount(0)
    failed = false
  } finally {
    await admin.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json' })
  }
})

test('@full [UI-CONFIG-002] HTTP连接端点通过UI完成新建、不可回退修订和启停', async ({ browser }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'http')
  const assets = { endpointName: `${prefix}_端点`, endpointKey: `${prefix.toLowerCase()}.endpoint` }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/connector')
    await page.getByRole('button', { name: '新增端点', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新增连接端点' })
    await dialog.getByLabel('端点名称').fill(assets.endpointName)
    await dialog.getByLabel('稳定键').fill(assets.endpointKey)
    await dialog.getByLabel('基础 URL').fill('https://example.com')
    await dialog.getByLabel('路径前缀').fill('/workflow')
    await dialog.getByRole('button', { name: '保存端点', exact: true }).click()
    await expect(page.getByText('连接端点创建成功', { exact: true })).toBeVisible()

    await page.getByPlaceholder('名称或稳定键').fill(assets.endpointKey)
    const row = tableRow(page, assets.endpointKey)
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('R1')
    await row.getByRole('button', { name: '发布新修订' }).click()
    const revisionDialog = page.getByRole('dialog', { name: '发布端点新修订' })
    await revisionDialog.getByLabel('路径前缀').fill('/workflow/v2')
    await revisionDialog.getByRole('button', { name: '发布修订', exact: true }).click()
    await confirmDialog(page)
    await expect(page.getByText('端点新修订已发布', { exact: true })).toBeVisible()
    await expect(row).toContainText('R2')
    await expect(row).toContainText('/workflow/v2')

    await row.getByRole('button', { name: '停用端点' }).click()
    await confirmDialog(page)
    await expect(page.getByText('端点已停用', { exact: true })).toBeVisible()
    await expect(row).toContainText('已停用')
    failed = false
  } finally {
    await admin.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify({ ...assets, finalState: 'DISABLED', revision: 2 }, null, 2)), contentType: 'application/json' })
  }
})

test('@full [UI-CONFIG-003] SQL数据源通过UI完成主库白名单、修订和停用', async ({ browser }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'sql')
  const assets = { dataSourceName: `${prefix}_数据源`, dataSourceKey: `${prefix.toLowerCase()}.source` }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/sqlDatasource')
    await page.getByRole('button', { name: '新增数据源', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新增 SQL 数据源' })
    await dialog.getByLabel('显示名称').fill(assets.dataSourceName)
    await dialog.getByLabel('稳定键').fill(assets.dataSourceKey)
    await dialog.getByLabel('授权表').fill('wf_category')
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('数据源创建成功', { exact: true })).toBeVisible()
    const row = tableRow(page, assets.dataSourceKey)
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('R1')

    await row.getByRole('button', { name: '发布新修订' }).click()
    const revisionDialog = page.getByRole('dialog', { name: '发布数据源新修订' })
    await revisionDialog.getByLabel('授权表').fill('wf_category\nwf_form')
    await revisionDialog.getByRole('button', { name: '发布修订', exact: true }).click()
    await expect(page.getByText('数据源修订已发布', { exact: true })).toBeVisible()
    await expect(row).toContainText('R2')
    await expect(row).toContainText('wf_form')
    await row.getByRole('button', { name: '停用', exact: true }).click()
    await confirmDialog(page)
    await expect(row).toContainText('已停用')
    failed = false
  } finally {
    await admin.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify({ ...assets, finalState: 'DISABLED', revision: 2 }, null, 2)), contentType: 'application/json' })
  }
})

test('@full [UI-INTEGRATION-001] 集成Token通过UI完成创建、轮换、脱敏回显和吊销', async ({ browser }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'token')
  const assets = { credentialName: `${prefix}_集成账号` }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/integrationCredential')
    await page.getByRole('button', { name: '新增账号', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新增集成账号' })
    await dialog.getByLabel('账号名称').fill(assets.credentialName)
    await dialog.getByLabel('变量白名单').fill('approved, amount')
    await dialog.getByRole('button', { name: '创建账号', exact: true }).click()
    const secretDialog = page.getByRole('dialog', { name: '保存集成 Token' })
    await expect(secretDialog.locator('code')).not.toHaveText('')
    await secretDialog.getByRole('button', { name: '我已保存', exact: true }).click()

    await page.getByPlaceholder('账号名称或 Token 前缀').fill(assets.credentialName)
    const row = tableRow(page, assets.credentialName)
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('R1')
    await expect(row).toContainText('有效')
    await expect(row).not.toContainText(/Bearer|eyJ/u)
    await row.getByRole('button', { name: '轮换 Token' }).click()
    await confirmDialog(page)
    await expect(secretDialog.locator('code')).not.toHaveText('')
    await secretDialog.getByRole('button', { name: '我已保存', exact: true }).click()
    await expect(row).toContainText('R2')
    await row.getByRole('button', { name: '吊销账号' }).click()
    await confirmDialog(page)
    await expect(page.getByText('集成账号已吊销', { exact: true })).toBeVisible()
    await expect(row).toContainText('已吊销')
    failed = false
  } finally {
    await admin.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify({ ...assets, finalState: 'REVOKED', revision: 2 }, null, 2)), contentType: 'application/json' })
  }
})

test('@full [UI-INTEGRATION-002] 集成Token每分钟限流由正式运行事件协议触发并保持零旁路副作用', async ({ browser, request }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'ratelimit')
  const assets = {
    credentialName: `${prefix}_限流账号`,
    credentialId: '',
    firstRequestId: randomUUID(),
    limitedRequestId: randomUUID(),
    correlationId: `E2E_UI_NOT_FOUND_${Date.now().toString(36)}`
  }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  const integration = new WorkflowIntegrationPage(admin.page)
  let credential = null
  let failed = true
  try {
    credential = await integration.createCredential({
      name: assets.credentialName,
      scopes: ['RECEIVE'],
      allowedVariables: [],
      rateLimitPerMinute: 1
    })
    assets.credentialId = credential.credentialId
    const protocolUrl = new URL(
      '/dev-api/workflow/runtime-event/receive', testInfo.project.use.baseURL).toString()
    const publish = async requestId => {
      const response = await request.post(protocolUrl, {
        headers: { 'X-Integration-Token': credential.token },
        data: {
          requestId,
          eventName: 'E2E_UI_MISSING_RECEIVE',
          processInstanceId: assets.correlationId,
          businessKey: null,
          variables: {}
        }
      })
      return { httpStatus: response.status(), payload: await response.json() }
    }

    const first = await publish(assets.firstRequestId)
    expect(first.httpStatus, '运行事件业务失败必须保持统一 HTTP 200').toBe(200)
    expect(first.payload?.code, '第一请求必须在消耗额度后进入实例匹配').toBe(409)
    expect(first.payload?.subCode).toBe('RUNTIME_EVENT_INSTANCE_NOT_FOUND')
    const limited = await publish(assets.limitedRequestId)
    expect(limited.httpStatus, '限流拒绝必须保持统一 HTTP 200').toBe(200)
    expect(limited.payload?.code, '第二请求必须触发每分钟限流').toBe(429)
    expect(limited.payload?.subCode).toBe('INTEGRATION_RATE_LIMITED')
    expect(String(limited.payload?.msg || '')).toContain('请求频率超过限制')

    const escapedCredentialId = String(assets.credentialId).replaceAll("'", "''")
    expect(queryReadOnly(
      `SELECT rate_limit_per_minute, last_used_at IS NOT NULL, revoked_at IS NULL FROM wf_integration_credential WHERE credential_id = '${escapedCredentialId}'`
    ), '限流必须只在 Redis 消费且凭据最近使用时间已降频落库').toEqual([['1', '1', '1']])
    expect(queryReadOnly(
      `SELECT event_type, event_name, correlation_value, status, result_code FROM wf_runtime_event_request WHERE request_id = '${assets.firstRequestId.replaceAll("'", "''")}'`
    ), '第一请求必须形成脱敏失败审计').toEqual([[
      'RECEIVE', 'E2E_UI_MISSING_RECEIVE', assets.correlationId,
      'FAILED', 'RUNTIME_EVENT_INSTANCE_NOT_FOUND'
    ]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM wf_runtime_event_request WHERE request_id = '${assets.limitedRequestId.replaceAll("'", "''")}'`
    ), '限流拒绝发生在运行事件台账创建前，不得产生第二条业务审计').toEqual([['0']])

    await integration.expectRuntimeEventAudit({
      requestId: assets.firstRequestId,
      eventName: 'E2E_UI_MISSING_RECEIVE',
      status: '失败',
      resultCode: 'RUNTIME_EVENT_INSTANCE_NOT_FOUND'
    })
    failed = false
  } finally {
    let recoveryError = null
    try {
      if (credential) {
        await integration.revokeCredential(assets.credentialName)
        credential.token = ''
        expect(queryReadOnly(
          `SELECT revoked_at IS NOT NULL, last_used_at IS NOT NULL FROM wf_integration_credential WHERE credential_id = '${String(assets.credentialId).replaceAll("'", "''")}'`
        ), '凭据必须经 UI 吊销且数据库不保存高频限流计数').toEqual([['1', '1']])
      }
    } catch (error) {
      failed = true
      recoveryError = error
    } finally {
      await admin.close(failed)
    }
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({ ...assets, finalState: 'REVOKED' }, null, 2)),
      contentType: 'application/json'
    })
    if (recoveryError) throw recoveryError
  }
})

test('@full [UI-DMN-001] DMN通过UI上传、服务端校验、部署回显和删除', async ({ browser }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'dmn')
  const decisionKey = `${prefix.toLowerCase()}_decision`
  const resourceName = `${decisionKey}.dmn`
  const dmnXml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions_${decisionKey}" name="${decisionKey}" namespace="https://approvaplat.local/dmn">
  <decision id="${decisionKey}" name="${prefix}_决策">
    <decisionTable id="table_${decisionKey}" hitPolicy="FIRST">
      <input id="input_${decisionKey}"><inputExpression id="expr_${decisionKey}" typeRef="string"><text>applicant</text></inputExpression></input>
      <output id="output_${decisionKey}" name="approved" typeRef="boolean" />
      <rule id="rule_${decisionKey}"><inputEntry id="entry_${decisionKey}"><text>-</text></inputEntry><outputEntry id="result_${decisionKey}"><text>true</text></outputEntry></rule>
    </decisionTable>
  </decision>
</definitions>`
  const assets = { decisionKey, resourceName }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/dmn')
    await page.getByRole('button', { name: '部署 DMN', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '部署 DMN 决策' })
    await dialog.locator('input[type="file"]').setInputFiles({ name: resourceName, mimeType: 'application/xml', buffer: Buffer.from(dmnXml, 'utf8') })
    await expect(dialog.getByLabel('资源名')).toHaveValue(resourceName)
    await dialog.getByLabel('分类').fill(prefix)
    await dialog.getByRole('button', { name: '部署', exact: true }).click()
    await expect(page.getByText('DMN 决策部署成功', { exact: true })).toBeVisible()
    const row = tableRow(page, decisionKey)
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('v1')
    await row.getByRole('button', { name: '删除该部署' }).click()
    await confirmDialog(page)
    await expect(page.getByText('DMN 部署已删除', { exact: true })).toBeVisible()
    await expect(row).toHaveCount(0)
    failed = false
  } finally {
    await admin.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify(assets, null, 2)), contentType: 'application/json' })
  }
})

test('@full [UI-DMN-002] DMN多版本通过UI冻结精确来源并保护被引用部署', async ({ browser }, testInfo) => {
  test.setTimeout(240_000)
  const prefix = assetPrefix(testInfo, 'dmnfreeze')
  const decisionKey = `${prefix.toLowerCase()}_decision`
  const decisionName = `${prefix}_冻结决策`
  const routeV1 = `${prefix}_V1`
  const routeV2 = `${prefix}_V2`
  const assets = {
    prefix,
    decisionKey,
    decisionName,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_CAT`.replace(/[^A-Za-z0-9_]/gu, '_').slice(0, 64),
    formName: `${prefix}_表单`,
    modelName: `${prefix}_流程`,
    modelKey: `${prefix.toLowerCase()}_process`,
    decisionTaskId: 'decisionTask',
    routeV1,
    routeV2,
    v1ResourceName: `${decisionKey}_v1.dmn`,
    v2ResourceName: `${decisionKey}_v2.dmn`,
    sourceV1DeploymentId: '',
    sourceV2DeploymentId: '',
    sourceV1DecisionId: '',
    sourceV2DecisionId: '',
    sourceV2Version: 0,
    processDeploymentId: '',
    artifactDeploymentId: '',
    frozenDeploymentId: '',
    frozenDecisionId: '',
    processInstanceId: ''
  }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let starter = null
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/dmn')
    assets.sourceV1DeploymentId = await deployDmnFromUi(page, {
      resourceName: assets.v1ResourceName,
      category: prefix,
      dmnXml: constantRouteDmnXml(decisionKey, decisionName, 'v1', routeV1)
    })
    const v1Row = dmnVersionRow(page, decisionKey, 1)
    await expect(v1Row, '首次 UI 部署必须回显唯一来源 v1').toHaveCount(1)
    const v1DecisionRows = queryReadOnly(
      `SELECT ID_ FROM ACT_DMN_DECISION WHERE DEPLOYMENT_ID_ = '${assets.sourceV1DeploymentId.replaceAll("'", "''")}' AND KEY_ = '${decisionKey.replaceAll("'", "''")}'`
    )
    expect(v1DecisionRows, '来源 v1 部署必须产生唯一官方决策').toHaveLength(1)
    assets.sourceV1DecisionId = v1DecisionRows[0][0]

    const configuration = new WorkflowConfigurationPage(page)
    await configuration.createCategory({
      name: assets.categoryName,
      code: assets.categoryCode,
      remark: `${prefix} DMN 冻结回归`
    })
    await configuration.createTextForm({ name: assets.formName, remark: `${prefix} DMN 冻结回归` })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} DMN v1 冻结`
    })
    await configuration.openDesigner(assets.modelKey)
    const processDesigner = new WorkflowDesignerPage(page)
    await processDesigner.deleteElement('review')
    await processDesigner.createAdvancedElement({
      paletteLabel: '业务规则任务',
      sourceElementId: 'start',
      stableElementId: assets.decisionTaskId,
      elementName: `${prefix}_DMN决策`,
      offsetX: 170,
      offsetY: 0,
      expectedLocalName: 'businessRuleTask'
    })
    await processDesigner.ensureSequenceFlow('start', assets.decisionTaskId)
    await processDesigner.ensureSequenceFlow(assets.decisionTaskId, 'end')
    const optionLabel = `${decisionName} · ${decisionKey} · v1`
    const authorXml = await processDesigner.configureDmnDecision({
      elementId: assets.decisionTaskId,
      optionLabel,
      decisionId: assets.sourceV1DecisionId
    })
    await testInfo.attach('dmn-author-v1.xml', { body: Buffer.from(authorXml), contentType: 'application/xml' })
    await processDesigner.validateAndSave()
    await processDesigner.returnToModels()
    await configuration.deployModel(assets.modelKey)

    const escapedProcessKey = assets.modelKey.replaceAll("'", "''")
    const deploymentRows = queryReadOnly(
      `SELECT DEPLOYMENT_ID_ FROM ACT_RE_PROCDEF WHERE KEY_ = '${escapedProcessKey}' ORDER BY VERSION_ DESC LIMIT 1`
    )
    expect(deploymentRows, '流程 UI 部署必须产生唯一最新定义').toHaveLength(1)
    assets.processDeploymentId = deploymentRows[0][0]
    const escapedProcessDeploymentId = assets.processDeploymentId.replaceAll("'", "''")
    const artifactRows = queryReadOnly(
      `SELECT d.ID_,CONVERT(b.BYTES_ USING utf8mb4) FROM ACT_RE_DEPLOYMENT d JOIN ACT_GE_BYTEARRAY b ON b.DEPLOYMENT_ID_ = d.ID_ WHERE d.PARENT_DEPLOYMENT_ID_ = '${escapedProcessDeploymentId}' AND d.CATEGORY_ = 'APPROVAPLAT_WORKFLOW_ARTIFACTS' AND d.KEY_ = 'approvaplat-artifacts:${escapedProcessDeploymentId}' AND b.NAME_ = 'approvaplat/dmn-v1.json'`
    )
    expect(artifactRows, '流程部署必须创建唯一业务制品子部署和 DMN JSON 资源').toHaveLength(1)
    assets.artifactDeploymentId = artifactRows[0][0]
    const dmnSnapshots = JSON.parse(artifactRows[0][1])
    expect(dmnSnapshots, '流程部署必须持久化一份完整 DMN 冻结快照').toHaveLength(1)
    const [dmnSnapshot] = dmnSnapshots
    expect(dmnSnapshot).toMatchObject({
      deployId: assets.processDeploymentId,
      processKey: assets.modelKey,
      elementId: assets.decisionTaskId,
      sourceDecisionId: assets.sourceV1DecisionId,
      decisionKey,
      decisionVersion: 1,
      sourceDeploymentId: assets.sourceV1DeploymentId,
      resourceName: assets.v1ResourceName
    })
    expect(dmnSnapshot.resourceChecksum).toMatch(/^[0-9a-f]{64}$/u)
    expect(dmnSnapshot.snapshotChecksum).toMatch(/^[0-9a-f]{64}$/u)
    assets.frozenDeploymentId = dmnSnapshot.frozenDeploymentId
    assets.frozenDecisionId = dmnSnapshot.frozenDecisionId
    expect(assets.frozenDeploymentId).not.toBe('')
    expect(assets.frozenDecisionId).not.toBe('')
    expect(assets.frozenDeploymentId).not.toBe(assets.sourceV1DeploymentId)
    expect(queryReadOnly(
      `SELECT PARENT_DEPLOYMENT_ID_ FROM ACT_DMN_DEPLOYMENT WHERE ID_ = '${assets.frozenDeploymentId.replaceAll("'", "''")}'`
    ), '冻结 DMN 子部署必须归属于流程部署').toEqual([[assets.processDeploymentId]])

    await page.goto('/workflow/extensions/dmn')
    assets.sourceV2DeploymentId = await deployDmnFromUi(page, {
      resourceName: assets.v2ResourceName,
      category: prefix,
      dmnXml: constantRouteDmnXml(decisionKey, decisionName, 'v2', routeV2)
    })
    const v2DecisionRows = queryReadOnly(
      `SELECT ID_,VERSION_ FROM ACT_DMN_DECISION WHERE DEPLOYMENT_ID_ = '${assets.sourceV2DeploymentId.replaceAll("'", "''")}' AND KEY_ = '${decisionKey.replaceAll("'", "''")}'`
    )
    expect(v2DecisionRows, '来源 v2 部署必须产生唯一官方决策').toHaveLength(1)
    assets.sourceV2DecisionId = v2DecisionRows[0][0]
    assets.sourceV2Version = Number(v2DecisionRows[0][1])
    expect(assets.sourceV2Version, '第二次来源发布版本必须严格晚于来源 v1').toBeGreaterThan(1)
    await expect(dmnVersionRow(page, decisionKey, 1), '来源 v1 必须继续出现在完整管理目录').toHaveCount(1)
    await expect(dmnVersionRow(page, decisionKey, assets.sourceV2Version),
      '同 key 第二次来源发布必须按官方版本回显').toHaveCount(1)
    const optionsResponsePromise = page.waitForResponse(response => matchesEndpoint(response, '/workflow/dmn/options', 'GET'))
    await configuration.openDesigner(assets.modelKey)
    const optionsPayload = await expectAjaxSuccess(await optionsResponsePromise, '/workflow/dmn/options')
    const selectedOptions = (Array.isArray(optionsPayload.data) ? optionsPayload.data : [])
      .filter(option => option.decisionKey === decisionKey)
    expect(selectedOptions, '设计器默认目录每个 key 只能暴露最新来源版本').toHaveLength(1)
    expect(selectedOptions[0]?.decisionId).toBe(assets.sourceV2DecisionId)
    expect(selectedOptions[0]?.version).toBe(assets.sourceV2Version)
    const reopenedDesigner = new WorkflowDesignerPage(page)
    await reopenedDesigner.selectCanvasShape(assets.decisionTaskId)
    const reopenedXml = await reopenedDesigner.readDesignerXml()
    const reopenedDocument = new DOMParser().parseFromString(reopenedXml, 'application/xml')
    const reopenedTask = [...reopenedDocument.getElementsByTagNameNS('*', 'businessRuleTask')]
      .find(element => element.getAttribute('id') === assets.decisionTaskId)
    expect(reopenedTask?.getAttributeNS('http://flowable.org/bpmn', 'rules'),
      '来源目录升级后已保存作者模型必须继续引用原精确 v1').toBe(assets.sourceV1DecisionId)

    starter = await openRoleSession(browser, 'workflow_starter', testInfo)
    assets.processInstanceId = await new WorkflowWorkbenchPage(starter.page)
      .startProcess(assets.modelName, `${prefix}_申请内容`)
    const escapedInstanceId = assets.processInstanceId.replaceAll("'", "''")
    expect(queryReadOnly(
      `SELECT TEXT_ FROM ACT_HI_VARINST WHERE PROC_INST_ID_ = '${escapedInstanceId}' AND NAME_ = 'route'`
    ), '旧流程定义执行结果必须来自被冻结的 v1，而不是最新来源 v2').toEqual([[routeV1]])
    expect(queryReadOnly(
      `SELECT COUNT(*) FROM ACT_RU_EXECUTION WHERE PROC_INST_ID_ = '${escapedInstanceId}'`
    ), '同步 DMN 任务和结束事件完成后不得遗留运行执行树').toEqual([['0']])

    await page.goto('/workflow/extensions/dmn')
    const escapedArtifactDeploymentId = assets.artifactDeploymentId.replaceAll("'", "''")
    const escapedFrozenDeploymentId = assets.frozenDeploymentId.replaceAll("'", "''")
    const escapedFrozenDecisionId = assets.frozenDecisionId.replaceAll("'", "''")
    const sourceV1Before = queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT WHERE ID_ = '${assets.sourceV1DeploymentId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DECISION WHERE ID_ = '${assets.sourceV1DecisionId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT_RESOURCE WHERE DEPLOYMENT_ID_ = '${assets.sourceV1DeploymentId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT WHERE ID_ = '${escapedFrozenDeploymentId}' AND PARENT_DEPLOYMENT_ID_ = '${escapedProcessDeploymentId}'),(SELECT COUNT(*) FROM ACT_DMN_DECISION WHERE ID_ = '${escapedFrozenDecisionId}' AND DEPLOYMENT_ID_ = '${escapedFrozenDeploymentId}'),(SELECT COUNT(*) FROM ACT_RE_DEPLOYMENT WHERE ID_ = '${escapedArtifactDeploymentId}' AND PARENT_DEPLOYMENT_ID_ = '${escapedProcessDeploymentId}'),(SELECT COUNT(*) FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_ = '${escapedArtifactDeploymentId}' AND NAME_ = 'approvaplat/dmn-v1.json'),(SELECT SHA2(BYTES_,256) FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_ = '${escapedArtifactDeploymentId}' AND NAME_ = 'approvaplat/dmn-v1.json')`
    )
    const v1DeleteResponsePromise = page.waitForResponse(response => (
      matchesEndpoint(response, `/workflow/dmn/${assets.sourceV1DeploymentId}`, 'DELETE')
    ))
    await dmnVersionRow(page, decisionKey, 1).getByRole('button', { name: '删除该部署' }).click()
    await confirmDialog(page)
    const v1DeleteResponse = await v1DeleteResponsePromise
    expect(v1DeleteResponse.status(), '来源部署引用冲突必须保持统一 AjaxResult HTTP 200').toBe(200)
    const v1DeletePayload = await v1DeleteResponse.json()
    expect(v1DeletePayload?.code).toBe(409)
    expect(String(v1DeletePayload?.msg || '')).toContain('DMN 部署已被流程版本冻结引用')
    await expect(page.getByText('DMN 部署已被流程版本冻结引用', { exact: true })).toBeVisible()
    await expect(dmnVersionRow(page, decisionKey, 1), '拒绝删除后来源 v1 必须继续留在页面').toHaveCount(1)
    expect(queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT WHERE ID_ = '${assets.sourceV1DeploymentId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DECISION WHERE ID_ = '${assets.sourceV1DecisionId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT_RESOURCE WHERE DEPLOYMENT_ID_ = '${assets.sourceV1DeploymentId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT WHERE ID_ = '${escapedFrozenDeploymentId}' AND PARENT_DEPLOYMENT_ID_ = '${escapedProcessDeploymentId}'),(SELECT COUNT(*) FROM ACT_DMN_DECISION WHERE ID_ = '${escapedFrozenDecisionId}' AND DEPLOYMENT_ID_ = '${escapedFrozenDeploymentId}'),(SELECT COUNT(*) FROM ACT_RE_DEPLOYMENT WHERE ID_ = '${escapedArtifactDeploymentId}' AND PARENT_DEPLOYMENT_ID_ = '${escapedProcessDeploymentId}'),(SELECT COUNT(*) FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_ = '${escapedArtifactDeploymentId}' AND NAME_ = 'approvaplat/dmn-v1.json'),(SELECT SHA2(BYTES_,256) FROM ACT_GE_BYTEARRAY WHERE DEPLOYMENT_ID_ = '${escapedArtifactDeploymentId}' AND NAME_ = 'approvaplat/dmn-v1.json')`
    ), '被拒绝的来源 v1 删除必须在 DMN、资源和快照层零副作用').toEqual(sourceV1Before)

    const v2DeleteResponsePromise = page.waitForResponse(response => (
      matchesEndpoint(response, `/workflow/dmn/${assets.sourceV2DeploymentId}`, 'DELETE')
    ))
    await dmnVersionRow(page, decisionKey, assets.sourceV2Version)
      .getByRole('button', { name: '删除该部署' }).click()
    await confirmDialog(page)
    await expectAjaxSuccess(await v2DeleteResponsePromise, `/workflow/dmn/${assets.sourceV2DeploymentId}`)
    await expect(page.getByText('DMN 部署已删除', { exact: true })).toBeVisible()
    await expect(dmnVersionRow(page, decisionKey, assets.sourceV2Version),
      '未引用的第二次来源发布必须通过 UI 删除').toHaveCount(0)
    expect(queryReadOnly(
      `SELECT (SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT WHERE ID_ = '${assets.sourceV2DeploymentId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DECISION WHERE ID_ = '${assets.sourceV2DecisionId.replaceAll("'", "''")}'),(SELECT COUNT(*) FROM ACT_DMN_DEPLOYMENT_RESOURCE WHERE DEPLOYMENT_ID_ = '${assets.sourceV2DeploymentId.replaceAll("'", "''")}')`
    ), '未引用来源 v2 删除后部署、决策和资源必须同步清理').toEqual([['0', '0', '0']])

    await testInfo.attach('dmn-freeze-evidence.json', {
      body: Buffer.from(JSON.stringify({
        sourceV1: { deploymentId: assets.sourceV1DeploymentId, decisionId: assets.sourceV1DecisionId },
        sourceV2: { deploymentId: assets.sourceV2DeploymentId, decisionId: assets.sourceV2DecisionId },
        process: { deploymentId: assets.processDeploymentId, instanceId: assets.processInstanceId },
        frozen: { deploymentId: assets.frozenDeploymentId, decisionId: assets.frozenDecisionId },
        runtimeRoute: routeV1,
        v1DeleteCode: v1DeletePayload.code,
        v2Deleted: true
      }, null, 2)),
      contentType: 'application/json'
    })
    failed = false
  } finally {
    let recoveryError = null
    try {
      if (failed && assets.sourceV2DeploymentId) {
        const cleanupVersionRows = assets.sourceV2Version > 0 ? [[String(assets.sourceV2Version)]] : queryReadOnly(
          `SELECT VERSION_ FROM ACT_DMN_DECISION WHERE DEPLOYMENT_ID_ = '${assets.sourceV2DeploymentId.replaceAll("'", "''")}' AND KEY_ = '${decisionKey.replaceAll("'", "''")}'`
        )
        await admin.page.goto('/workflow/extensions/dmn')
        const row = cleanupVersionRows.length === 1
          ? dmnVersionRow(admin.page, decisionKey, Number(cleanupVersionRows[0][0]))
          : admin.page.locator('.never-match-dmn-cleanup')
        if (await row.count() === 1) {
          await row.getByRole('button', { name: '删除该部署' }).click()
          await confirmDialog(admin.page)
        }
      }
      if (failed && assets.sourceV1DeploymentId && !assets.processDeploymentId) {
        await admin.page.goto('/workflow/extensions/dmn')
        const row = dmnVersionRow(admin.page, decisionKey, 1)
        if (await row.count() === 1) {
          await row.getByRole('button', { name: '删除该部署' }).click()
          await confirmDialog(admin.page)
        }
      }
    } catch (error) {
      recoveryError = error
    } finally {
      await Promise.allSettled([starter?.close(failed), admin.close(failed)].filter(Boolean))
    }
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({
        ...assets,
        finalState: failed ? 'INCOMPLETE' : 'V1_REFERENCED_V2_DELETED'
      }, null, 2)),
      contentType: 'application/json'
    })
    if (recoveryError) throw recoveryError
  }
})

test('@full [UI-DMN-003] 用户任务通过更改元素转换DMN任务时清理专属属性并保持撤销重做一致', async ({ browser }, testInfo) => {
  test.setTimeout(180_000)
  const prefix = assetPrefix(testInfo, 'dmnreplace')
  const decisionKey = `${prefix.toLowerCase()}_decision`
  const decisionName = `${prefix}_转换决策`
  const assets = {
    prefix,
    decisionKey,
    decisionName,
    resourceName: `${decisionKey}.dmn`,
    categoryName: `${prefix}_分类`,
    categoryCode: `${prefix}_CAT`.replace(/[^A-Za-z0-9_]/gu, '_').slice(0, 64),
    formName: `${prefix}_表单`,
    modelName: `${prefix}_流程`,
    modelKey: `${prefix.toLowerCase()}_process`,
    taskId: 'review',
    sourceDeploymentId: '',
    sourceDecisionId: ''
  }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/dmn')
    assets.sourceDeploymentId = await deployDmnFromUi(page, {
      resourceName: assets.resourceName,
      category: prefix,
      dmnXml: constantRouteDmnXml(decisionKey, decisionName, 'replace', `${prefix}_ROUTE`)
    })
    const decisionRows = queryReadOnly(
      `SELECT ID_ FROM ACT_DMN_DECISION WHERE DEPLOYMENT_ID_ = '${assets.sourceDeploymentId.replaceAll("'", "''")}' AND KEY_ = '${decisionKey.replaceAll("'", "''")}'`
    )
    expect(decisionRows, 'UI 部署必须产生唯一正式 DMN 决策').toHaveLength(1)
    assets.sourceDecisionId = decisionRows[0][0]

    const configuration = new WorkflowConfigurationPage(page)
    await configuration.createCategory({
      name: assets.categoryName,
      code: assets.categoryCode,
      remark: `${prefix} UserTask 转换回归`
    })
    await configuration.createTextForm({ name: assets.formName, remark: `${prefix} UserTask 转换回归` })
    await configuration.createModel({
      name: assets.modelName,
      key: assets.modelKey,
      categoryName: assets.categoryName,
      formName: assets.formName,
      description: `${prefix} UserTask 转 BusinessRuleTask`
    })
    await configuration.openDesigner(assets.modelKey)
    const designer = new WorkflowDesignerPage(page)
    await designer.configureCandidateRole('流程审批人', `${prefix}_待转换审批`)
    const userTaskXml = await designer.readDesignerXml()
    expect(userTaskXml, '转换前用户任务必须包含办理规则作者属性').toContain('approva.assignment.type')
    expect(userTaskXml, '转换前用户任务必须存在于作者 BPMN').toContain('<userTask')

    await designer.replaceTaskWithBusinessRuleTask(assets.taskId)
    const convertedXml = await designer.readDesignerXml()
    expect(convertedXml, '转换后必须生成 BusinessRuleTask').toContain(`<businessRuleTask id="${assets.taskId}"`)
    expect(convertedXml, '转换后不得残留 UserTask 办理规则').not.toContain('approva.assignment.')
    expect(convertedXml, '转换后不得残留 UserTask 审计监听器').not.toContain('${userTaskListener}')
    expect(convertedXml, '转换后不得残留 UserTask 表单属性').not.toContain('flowable:formProperty')

    await designer.undo()
    const undoneXml = await designer.readDesignerXml()
    expect(undoneXml, '撤销必须原子恢复原 UserTask').toContain(`<userTask id="${assets.taskId}"`)
    expect(undoneXml, '撤销必须恢复原办理规则作者属性').toContain('approva.assignment.type')

    await designer.redo()
    const redoneXml = await designer.readDesignerXml()
    expect(redoneXml, '重做必须再次生成 BusinessRuleTask').toContain(`<businessRuleTask id="${assets.taskId}"`)
    expect(redoneXml, '重做不得恢复 UserTask 办理规则').not.toContain('approva.assignment.')
    expect(redoneXml, '重做不得恢复 UserTask 审计监听器').not.toContain('${userTaskListener}')

    const optionLabel = `${decisionName} · ${decisionKey} · v1`
    const authorXml = await designer.configureDmnDecision({
      elementId: assets.taskId,
      optionLabel,
      decisionId: assets.sourceDecisionId
    })
    await testInfo.attach('dmn-user-task-replace-author.xml', {
      body: Buffer.from(authorXml),
      contentType: 'application/xml'
    })
    await designer.validateAndSave()
    failed = false
  } finally {
    let recoveryError = null
    try {
      if (assets.sourceDeploymentId) {
        await admin.page.goto('/workflow/extensions/dmn')
        const row = dmnVersionRow(admin.page, decisionKey, 1)
        if (await row.count() === 1) {
          await row.getByRole('button', { name: '删除该部署' }).click()
          await confirmDialog(admin.page)
        }
      }
    } catch (error) {
      recoveryError = error
    } finally {
      await admin.close(failed)
    }
    await testInfo.attach('asset-result.json', {
      body: Buffer.from(JSON.stringify({
        ...assets,
        finalState: failed ? 'INCOMPLETE' : 'MODEL_SAVED_SOURCE_DMN_DELETED'
      }, null, 2)),
      contentType: 'application/json'
    })
    if (recoveryError) throw recoveryError
  }
})

test('@full [UI-EVENT-001] BPMN事件编码和业务日历通过UI完成新增、编辑与启停', async ({ browser }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'event')
  const eventCode = `${prefix.replace(/[^A-Za-z0-9]/gu, '_').toUpperCase()}_ERROR`.slice(0, 64)
  const calendarKey = `${prefix.replace(/[^A-Za-z0-9]/gu, '_').toUpperCase()}_CAL`.slice(0, 64)
  const assets = { eventCode, calendarKey, eventName: `${prefix}_事件`, calendarName: `${prefix}_日历` }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/extensions/bpmnEvent')
    await page.getByRole('button', { name: '新增编码', exact: true }).click()
    const eventDialog = page.getByRole('dialog', { name: '新增事件编码' })
    await eventDialog.getByLabel('稳定编码').fill(eventCode)
    await eventDialog.getByLabel('显示名称').fill(assets.eventName)
    await eventDialog.getByLabel('业务说明').fill(prefix)
    await eventDialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('BPMN 事件编码已保存', { exact: true })).toBeVisible()
    const eventRow = tableRow(page, eventCode)
    await expect(eventRow).toHaveCount(1)
    await eventRow.getByRole('button', { name: '编辑', exact: true }).click()
    const editDialog = page.getByRole('dialog', { name: '编辑事件编码' })
    await editDialog.getByLabel('显示名称').fill(`${assets.eventName}_已编辑`)
    await editDialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(eventRow).toContainText('已编辑')
    await eventRow.getByRole('button', { name: '停用', exact: true }).click()
    await confirmDialog(page)
    await expect(eventRow).toContainText('已停用')

    await page.getByRole('tab', { name: '业务日历' }).click()
    await page.getByRole('button', { name: '新增日历', exact: true }).click()
    const calendarDialog = page.getByRole('dialog', { name: '新增业务日历' })
    await calendarDialog.getByLabel('稳定编码').fill(calendarKey)
    await calendarDialog.getByLabel('日历名称').fill(assets.calendarName)
    await calendarDialog.getByRole('button', { name: '新增日期覆盖', exact: true }).click()
    await calendarDialog.getByPlaceholder('日期').fill('2026-12-31')
    await calendarDialog.getByPlaceholder('节假日或补班说明').fill('UI自动化节假日')
    await calendarDialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('业务日历已保存', { exact: true })).toBeVisible()
    const calendarRow = tableRow(page, calendarKey)
    await expect(calendarRow).toHaveCount(1)
    await expect(calendarRow).toContainText('1')
    await calendarRow.getByRole('button', { name: '编辑', exact: true }).click()
    const calendarEditDialog = page.getByRole('dialog', { name: '编辑业务日历' })
    await calendarEditDialog.getByLabel('日历名称').fill(`${assets.calendarName}_已编辑`)
    await calendarEditDialog.getByRole('button', { name: '保存', exact: true }).click()
    await calendarRow.getByRole('button', { name: '停用', exact: true }).click()
    await confirmDialog(page)
    await expect(calendarRow).toContainText('已停用')

    for (const tabName of ['运行审计', '我的通知', 'SLA 执行', 'SLA 审计', 'SLA 通知']) {
      await page.getByRole('tab', { name: tabName }).click()
      await expect(page.locator('.el-tab-pane:visible .el-table')).toBeVisible()
    }
    failed = false
  } finally {
    await admin.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify({ ...assets, finalState: 'DISABLED' }, null, 2)), contentType: 'application/json' })
  }
})

test('@full [UI-NOTIFY-001] 通知策略通过UI完成作用域、接收人、通道、模板和修订更新', async ({ browser }, testInfo) => {
  const prefix = assetPrefix(testInfo, 'notify')
  const assets = { processKey: `${prefix.toLowerCase()}_process`, title: `${prefix}_待办` }
  const admin = await openRoleSession(browser, 'workflow_admin', testInfo)
  let failed = true
  try {
    const page = admin.page
    await page.goto('/workflow/notification')
    await page.getByRole('button', { name: '新增策略', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '新增通知策略' })
    await selectDialogOption(page, dialog, '作用域', '指定流程')
    await dialog.getByLabel('流程 key').fill(assets.processKey)
    await dialog.getByLabel('标题模板').fill(assets.title)
    await dialog.getByLabel('正文模板').fill(`${prefix} {{processName}} {{taskName}}`)
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('通知策略已保存', { exact: true })).toBeVisible()
    const row = tableRow(page, assets.processKey)
    await expect(row).toHaveCount(1)
    await expect(row).toContainText('INBOX')
    await row.getByRole('button', { name: '编辑策略' }).click()
    const editDialog = page.getByRole('dialog', { name: '编辑通知策略' })
    await editDialog.getByLabel('正文模板').fill(`${prefix} 已修订 {{eventType}}`)
    await editDialog.locator('.el-switch').click()
    await editDialog.getByRole('button', { name: '保存', exact: true }).click()
    await expect(page.getByText('通知策略已保存', { exact: true })).toBeVisible()
    await expect(row).toContainText('停用')
    await page.getByRole('tab', { name: '投递运维' }).click()
    await expect(page.locator('.el-tab-pane:visible .el-table')).toBeVisible()
    failed = false
  } finally {
    await admin.close(failed)
    await testInfo.attach('asset-result.json', { body: Buffer.from(JSON.stringify({ ...assets, finalState: 'DISABLED' }, null, 2)), contentType: 'application/json' })
  }
})
