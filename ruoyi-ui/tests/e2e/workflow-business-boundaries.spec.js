import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { connect } from 'node:net'
import { prepareNotificationCycle } from './fixtures/workflow-notification-cycle.js'

// N09：真实登录、设计器、部署、草稿附件、任务与数据库；API只用于夹具准备、读取证据和精确清理。
// 要求可销毁的隔离 _it 库，不能把本套件指向业务库。密码只从进程环境读取。
test.describe.configure({ mode: 'serial' })
test.use({ actionTimeout: 15000 })
test.skip(!process.env.WORKFLOW_MYSQL_TEST_URL || !process.env.WORKFLOW_E2E_USERNAME,
  '真实业务链需要显式提供隔离 MySQL 和合成登录账号')
const runKey = `n09_${Date.now()}`
const models = [], instances = [], drafts = [], attachments = [], notificationCycles = []
let adminPage, adminContext, category, form, actor, previousCaptcha, previousEmail, initiatorId
const mysqlUri = new URL((process.env.WORKFLOW_MYSQL_TEST_URL || 'jdbc:mysql://localhost/invalid').replace(/^jdbc:/, ''))
const database = mysqlUri.pathname.slice(1)
const quote = value => value == null ? 'NULL' : value === '' ? "''"
  : `CONVERT(0x${Buffer.from(String(value)).toString('hex')} USING utf8mb4) COLLATE utf8mb4_unicode_ci`

function sql(query) {
  if (!database.endsWith('_it')) throw new Error('真实业务浏览器测试必须使用独立 _it 数据库')
  return execFileSync(process.env.WORKFLOW_MYSQL_CLIENT || (process.platform === 'win32'
    ? 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe' : 'mysql'), [
    '-h', mysqlUri.hostname, '-P', mysqlUri.port || '3306', '-u', process.env.WORKFLOW_MYSQL_TEST_USERNAME,
    `--database=${database}`, '--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4', '-e', query
  ], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.WORKFLOW_MYSQL_TEST_PASSWORD } }).trim()
}

async function clearCaptchaCache() {
  const parts = ['DEL', 'sys_config:sys.account.captchaEnabled']
  await new Promise((resolve, reject) => {
    const socket = connect({ host: '127.0.0.1', port: Number(process.env.WORKFLOW_E2E_REDIS_PORT || 6379) })
    socket.setTimeout(3000, () => socket.destroy(new Error('隔离Redis连接超时')))
    socket.on('error', reject)
    socket.on('connect', () => socket.write(`*${parts.length}\r\n${parts.map(part => `$${Buffer.byteLength(part)}\r\n${part}\r\n`).join('')}`))
    socket.once('data', data => { socket.end(); data[0] === 45 ? reject(new Error('验证码缓存清理失败')) : resolve() })
  })
}

async function api(page, path, method = 'GET', body, code = 200) {
  const result = await page.evaluate(async ({ path, method, body }) => {
    const cookie = document.cookie.split('; ').find(value => value.startsWith('Admin-Token='))
    const token = decodeURIComponent(cookie.slice('Admin-Token='.length))
    const response = await fetch(`/dev-api${path}`, {
      method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      ...(body ? { body: JSON.stringify(body) } : {})
    })
    return response.json()
  }, { path, method, body })
  expect(result.code, `${method} ${path}: ${result.msg}`).toBe(code)
  return result
}

async function login(page, account = { userName: process.env.WORKFLOW_E2E_USERNAME, password: process.env.WORKFLOW_E2E_PASSWORD }) {
  expect(Boolean(account.userName && account.password), '必须提供合成测试账号').toBe(true)
  await page.goto('/login?redirect=/index')
  await page.getByRole('textbox', { name: '账号' }).fill(account.userName)
  await page.getByRole('textbox', { name: '密码' }).fill(account.password)
  const [response] = await Promise.all([
    page.waitForResponse(response => new URL(response.url()).pathname === '/dev-api/login'
      && response.request().method() === 'POST', { timeout: 15000 }),
    page.getByRole('button', { name: '登录' }).click()
  ])
  const result = await response.json()
  expect(result.code, '真实登录接口必须成功').toBe(200)
  await expect(page).not.toHaveURL(/\/login(?:\?|$)/)
}

function taskAssignment(processInstanceId) {
  const tasks = sql(`SELECT JSON_OBJECT('taskId',ID_,'assignee',ASSIGNEE_,'owner',OWNER_)
    FROM ACT_RU_TASK WHERE PROC_INST_ID_=${quote(processInstanceId)}`).split('\n').filter(Boolean).map(JSON.parse)
  expect(tasks, '身份核对必须定位本实例唯一活动任务').toHaveLength(1)
  const task = tasks[0]
  const candidates = sql(`SELECT JSON_OBJECT('userId',USER_ID_,'groupId',GROUP_ID_)
    FROM ACT_RU_IDENTITYLINK WHERE TASK_ID_=${quote(task.taskId)} AND TYPE_='candidate'`)
    .split('\n').filter(Boolean).map(JSON.parse)
  return { ...task,
    candidateUserIds: candidates.filter(link => link.userId !== null).map(link => link.userId).sort(),
    candidateGroupIds: candidates.filter(link => link.groupId !== null).map(link => link.groupId).sort() }
}

async function responseFor(page, path, action, method = 'POST') {
  const pending = page.waitForResponse(response => new URL(response.url()).pathname === `/dev-api${path}` && response.request().method() === method)
  await action()
  const result = await (await pending).json()
  expect(result.code, `${path}: ${result.msg}`).toBe(200)
  return result.data
}

async function select(page, scope, label, option) {
  await scope.locator('.el-form-item').filter({ has: page.getByText(label, { exact: true }) }).locator('.el-select').click()
  await page.getByRole('option', { name: option, exact: true }).click()
}

test.beforeAll(async ({ browser }) => {
  test.setTimeout(120000)
  previousCaptcha = sql("SELECT config_value FROM sys_config WHERE config_key='sys.account.captchaEnabled'")
  sql("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'")
  await clearCaptchaCache()
  adminContext = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  adminPage = await adminContext.newPage()
  await login(adminPage)
  initiatorId = Number((await api(adminPage, '/getInfo')).user.userId)
  previousEmail = JSON.parse(sql(`SELECT JSON_OBJECT('email',email) FROM sys_user WHERE user_id=${initiatorId}`)).email
  sql(`UPDATE sys_user SET email=${quote(`${runKey}_initiator@example.invalid`)} WHERE user_id=${initiatorId}`)
  category = { code: runKey, categoryName: `${runKey}分类` }
  await api(adminPage, '/workflow/category', 'POST', category)
  category.categoryId = (await api(adminPage, `/workflow/category/list?code=${runKey}`)).rows.find(item => item.code === runKey).categoryId
  const content = JSON.stringify({ fields: [
    { vModel: 'requestTitle', label: '申请标题', tag: 'el-input', layout: 'colFormItem', required: true, workflowHidden: false, workflowReadable: true, workflowWritable: true },
    { vModel: 'evidence', label: '申请附件', tag: 'el-upload', layout: 'colFormItem', limit: 3, workflowHidden: false, workflowReadable: true, workflowWritable: true }
  ] })
  form = { formName: `${runKey}正式表单`, content }
  form.formId = (await api(adminPage, '/workflow/form', 'POST', form)).data.formId
  const approverRoleId = Number(sql("SELECT role_id FROM sys_role WHERE role_key='workflow_approver' AND status='0' AND del_flag='0'"))
  expect(Number.isSafeInteger(approverRoleId) && approverRoleId > 0, '需要正式流程审批人角色').toBe(true)
  actor = { userName: `${runKey}_m`, nickName: `${runKey}会签成员`, email: `${runKey}@example.invalid`,
    password: 'N09-member!82', status: '0', sex: '0', deptId: 100, roleIds: [approverRoleId], postIds: [] }
  await api(adminPage, '/system/user', 'POST', actor)
  actor.userId = (await api(adminPage, `/system/user/list?userName=${actor.userName}`)).rows.find(row => row.userName === actor.userName).userId
  expect((await api(adminPage, `/workflow/identity/options?type=user&capability=approval&keyword=${actor.userName}`)).rows
    .some(row => String(row.value) === String(actor.userId)), '合成成员必须具备正式审批角色').toBe(true)
  expect((await api(adminPage, `/workflow/identity/options?type=user&capability=claim&keyword=${actor.userName}`)).rows
    .some(row => String(row.value) === String(actor.userId)), '合成审批人必须具备完整候选认领资格').toBe(true)
})

test.afterAll(async () => {
  test.setTimeout(120000)
  const errors = []
  const cleanup = async action => { try { await action() } catch (error) { errors.push(error.message) } }
  for (const cycle of notificationCycles) await cleanup(() => cycle.close())
  if (adminPage) {
    // 活动草稿先走正式删除释放附件，再删除部署；终态草稿按记录主键清理。
    for (const draftId of drafts) await cleanup(async () => {
      const state = sql(`SELECT draft_status,revision_no FROM wf_process_draft WHERE draft_id=${quote(draftId)}`).split('\t')
      if (state[0] === 'ACTIVE') await api(adminPage,
        `/workflow/process/draft/${draftId}?expectedVersion=${Number(state[1])}`, 'DELETE')
    })
    for (const instance of instances) await cleanup(async () => {
      const detail = (await api(adminPage, `/workflow/process/observation?processInstanceId=${instance}`)).data.detail
      if (!['completed', 'canceled', 'rejected', 'terminated'].includes(detail.processStatus)) {
        await api(adminPage, '/workflow/instance/terminate', 'POST', { instanceId: instance, reason: `${runKey}精确清理` })
      }
      // 正式历史删除必须保留 BOUND 审计附件。本隔离夹具只释放自己记录的附件，随后仍由正式API清理私有文件。
      if (attachments.length) sql(`UPDATE wf_attachment SET attachment_status='DELETED',process_instance_id=NULL,
        task_id=NULL,node_key=NULL,bound_time=NULL,update_time=NOW(3)
        WHERE attachment_id IN (${attachments.map(quote).join(',')}) AND process_instance_id=${quote(instance)} AND attachment_status='BOUND'`)
      await api(adminPage, '/workflow/process/instances/delete', 'POST', { instanceIds: [instance] })
    })
    for (const attachmentId of attachments) await cleanup(async () => {
      await api(adminPage, `/workflow/attachment/${attachmentId}`, 'DELETE')
      await expect.poll(() => sql(`SELECT COUNT(*) FROM wf_attachment WHERE attachment_id=${quote(attachmentId)} AND storage_deleted_time IS NOT NULL`),
        { message: '本次附件私有文件必须实际清理', timeout: 30000 }).toBe('1')
      sql(`DELETE FROM wf_attachment WHERE attachment_id=${quote(attachmentId)} AND storage_deleted_time IS NOT NULL`)
    })
    for (const item of models.toReversed()) {
      if (item.deploymentId) await cleanup(() => api(adminPage, `/workflow/deploy/${item.deploymentId}`, 'DELETE'))
      await cleanup(() => api(adminPage, `/workflow/model/${item.modelId}`, 'DELETE'))
    }
    if (form?.formId) await cleanup(() => api(adminPage, `/workflow/form/${form.formId}`, 'DELETE'))
    if (category?.categoryId) await cleanup(() => api(adminPage, `/workflow/category/${category.categoryId}`, 'DELETE'))
    if (actor?.userId) await cleanup(() => api(adminPage, `/system/user/${actor.userId}`, 'DELETE'))
  }
  // 公共API按正常保留策略留下终态草稿；本套件只清除它自己记录的草稿主键。
  if (drafts.length) await cleanup(async () => {
    const ids = drafts.map(quote).join(',')
    sql(`DELETE FROM wf_process_draft WHERE draft_id IN (${ids}) AND draft_status IN ('SUBMITTED','DELETED')`)
  })
  for (const cycle of notificationCycles) await cleanup(async () => {
    if (cycle.policyId) sql(`DELETE FROM wf_notification_policy WHERE policy_id=${Number(cycle.policyId)}`)
    if (cycle.mailCreated) {
      sql(`DELETE FROM sys_mail_config WHERE config_id=1 AND revision=${Number(cycle.mailRevision)}`)
      expect(sql('SELECT COUNT(*) FROM sys_mail_config WHERE config_id=1'), '本次邮件配置必须清理').toBe('0')
    }
  })
  if (instances.length) await cleanup(async () => {
    const ids = instances.map(quote).join(',')
    sql(`DELETE FROM wf_notification_outbox WHERE process_instance_id IN (${ids}); DELETE FROM wf_notification_inbox WHERE process_instance_id IN (${ids})`)
  })
  if (previousCaptcha !== undefined) await cleanup(async () => {
    sql(`UPDATE sys_config SET config_value=${quote(previousCaptcha)} WHERE config_key='sys.account.captchaEnabled'`)
    expect(sql("SELECT config_value FROM sys_config WHERE config_key='sys.account.captchaEnabled'")).toBe(previousCaptcha)
    await clearCaptchaCache()
  })
  if (initiatorId) await cleanup(async () => {
    sql(`UPDATE sys_user SET email=${quote(previousEmail)} WHERE user_id=${initiatorId}`)
    expect(JSON.parse(sql(`SELECT JSON_OBJECT('email',email) FROM sys_user WHERE user_id=${initiatorId}`)).email).toBe(previousEmail)
  })
  await adminContext?.close()
  expect(errors, '本次合成数据或单键配置必须精确清理').toEqual([])
})

test.afterEach(async () => {
  await test.info().attach('business-chain-identifiers', { contentType: 'application/json',
    body: Buffer.from(JSON.stringify({ runKey, models, instances, drafts, attachments,
      notificationCycles: notificationCycles.map(({ policyId, mailCreated, outboxIds, evidence }) => ({ policyId, mailCreated, outboxIds, evidence })) }, null, 2)) })
})

async function createAndPublish(page, kind) {
  const item = { key: `${runKey}_${kind}`, name: `${runKey}_${kind}` }
  await page.goto('/workflow/model')
  await page.getByRole('button', { name: '新增', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '新增流程模型' })
  await dialog.locator('.el-form-item').filter({ has: page.getByText('模型名称', { exact: true }) }).getByRole('textbox').fill(item.name)
  await dialog.locator('.el-form-item').filter({ has: page.getByText('模型标识', { exact: true }) }).getByRole('textbox').fill(item.key)
  await select(page, dialog, '流程分类', category.categoryName)
  await select(page, dialog, '流程表单', form.formName)
  const created = await responseFor(page, '/workflow/model', () => dialog.getByRole('button', { name: '保存', exact: true }).click())
  item.modelId = created.modelId
  models.push(item)
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: item.name, exact: true }) })
  await row.getByRole('button').first().click()
  await expect(page).toHaveURL(new RegExp(`/workflow/model-design/${item.modelId}`))
  const catalogResponses = []
  page.on('response', async response => {
    if (new URL(response.url()).pathname === '/dev-api/workflow/model/author-field-catalog') catalogResponses.push(await response.json())
  })
  await page.locator('input[type="file"]').setInputFiles({ name: `${item.key}.bpmn`, mimeType: 'text/xml', buffer: Buffer.from(bpmn(item.key, kind, form.formId)) })
  await expect(page.locator('.djs-element[data-element-id="review"]')).toBeVisible()
  await page.locator('.djs-element[data-element-id="start"]').click()
  await expect.poll(() => catalogResponses.filter(response => response.code === 200).length).toBeGreaterThan(0)
  await responseFor(page, '/workflow/model/save', () => page.getByRole('button', { name: '保存', exact: true }).click())
  await expect(page.getByText('流程设计保存成功', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '服务端校验', exact: true }).click()
  const validation = page.getByRole('dialog', { name: '正式保存校验' })
  await expect(validation.getByText('校验通过', { exact: true })).toBeVisible()
  await responseFor(page, '/workflow/model/preflight', () => validation.getByRole('button', { name: '运行发布预检' }).click())
  const preflight = page.getByRole('dialog', { name: '发布预检' })
  await expect(preflight.getByText('校验通过', { exact: true })).toBeVisible()
  await preflight.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: '返回模型列表', exact: true }).click()
  const published = await responseFor(page, '/workflow/model/deploy', async () => {
    await page.getByRole('row').filter({ has: page.getByRole('cell', { name: item.name, exact: true }) }).getByRole('button').nth(3).click()
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  })
  item.deploymentId = published.deploymentId
  item.definitionId = (await api(page, `/workflow/process/list?processKey=${item.key}`)).rows[0].definitionId
  return item
}

async function startThroughDraft(page, model, suffix) {
  await page.goto(`/workflow/process-start/${encodeURIComponent(model.definitionId)}?deploymentId=${model.deploymentId}`)
  await page.getByRole('textbox', { name: '申请标题', exact: true }).fill(`${runKey}_${suffix}`)
  const uploaded = await responseFor(page, '/workflow/attachment', () => page.locator('input[type="file"]').setInputFiles({
    name: `${runKey}_${suffix}.txt`, mimeType: 'text/plain', buffer: Buffer.from(`N09 synthetic evidence ${suffix}`)
  }))
  attachments.push(uploaded.attachmentId)
  const draft = await responseFor(page, '/workflow/process/draft', () => page.getByRole('button', { name: '保存草稿', exact: true }).click())
  drafts.push(draft.draftId)
  Object.assign(model, { draftId: draft.draftId, attachmentIds: [uploaded.attachmentId] })
  await page.reload()
  await expect(page.getByRole('textbox', { name: '申请标题', exact: true })).toHaveValue(`${runKey}_${suffix}`)
  await expect(page.getByText(`${runKey}_${suffix}.txt`, { exact: true })).toBeVisible()
  const started = await responseFor(page, `/workflow/process/draft/${draft.draftId}/submit`, () => page.getByRole('button', { name: '正式提交', exact: true }).click())
  instances.push(started.processInstanceId)
  model.processInstanceId = started.processInstanceId
  await expect(page).toHaveURL(new RegExp(`/workflow/process-detail/${started.processInstanceId}`))
  model.taskIds = { original: taskAssignment(started.processInstanceId).taskId }
  expect(model.taskIds.original).toBeTruthy()
  return started.processInstanceId
}

async function openTodo(page, model) {
  await page.getByRole('menubar').getByRole('link', { name: '待办任务', exact: true }).click()
  await page.getByPlaceholder('请输入流程名称').fill(model.name)
  await responseFor(page, '/workflow/process/todoList', () => page.getByRole('button', { name: '搜索', exact: true }).click(), 'GET')
  const row = page.getByRole('row').filter({ hasText: model.name })
  await row.getByRole('button').first().click()
  await expect(page).toHaveURL(new RegExp(`/workflow/process-detail/${model.processInstanceId}.*taskId=`))
}

async function claimThroughPage(page, model) {
  await page.goto('/office/claim')
  await page.getByPlaceholder('请输入流程名称').fill(model.name)
  await responseFor(page, '/workflow/process/claimList', () => page.getByRole('button', { name: '搜索', exact: true }).click(), 'GET')
  const row = page.getByRole('row').filter({ hasText: model.name })
  await responseFor(page, '/workflow/task/claim', async () => {
    await row.getByRole('button').nth(1).click()
    await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
  })
  await openTodo(page, model)
  await expect(page.getByRole('button', { name: '通过', exact: true })).toBeVisible()
}

async function approve(page, comment) {
  await page.getByRole('button', { name: '通过', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '通过任务' })
  await dialog.getByPlaceholder('请输入审批意见').fill(comment)
  await responseFor(page, '/workflow/task/complete', () => dialog.getByRole('button', { name: '确认', exact: true }).click())
  await expect(page.locator('.workflow-detail__meta').getByText('已完成', { exact: true })).toBeVisible()
  const model = models.find(item => page.url().includes(item.processInstanceId))
  const detail = (await api(page, `/workflow/process/observation?processInstanceId=${model.processInstanceId}`)).data.detail
  expect(detail.processStatus).toBe('completed')
  model.resultStatus = detail.processStatus
}

test('N09-1 登录设计目录、保存预检发布、附件草稿发起办理和历史详情', async ({ page }) => {
  test.setTimeout(180000)
  await login(page)
  const model = await createAndPublish(page, 'complete')
  await api(page, '/workflow/form', 'PUT', { ...form, content: form.content.replace('申请标题', '未发布的新标题') })
  await startThroughDraft(page, model, '完整链')
  await openTodo(page, model)
  await api(page, '/workflow/form', 'PUT', form)
  await approve(page, 'N09完整链审批通过')
  await page.reload()
  await expect(page.locator('.workflow-detail__meta').getByText('已完成', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: `下载附件 ${runKey}_完整链.txt`, exact: true }).first()).toBeEnabled()
  await test.info().attach('N09-1-history', { body: await page.screenshot(), contentType: 'image/png' })
})

test('N09-2 不同申请人与审批人退回重提、恢复任务身份和候选集合', async ({ page, browser }) => {
  test.setTimeout(180000)
  await login(page)
  const model = await createAndPublish(page, 'returned')
  await startThroughDraft(page, model, '退回链')
  const candidateAssignment = { assignee: null, owner: null, candidateUserIds: [String(actor.userId)], candidateGroupIds: [] }
  model.assignments = { initial: taskAssignment(model.processInstanceId) }
  expect(model.assignments.initial).toEqual({ taskId: model.taskIds.original, ...candidateAssignment })
  const approverContext = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  try {
    const approverPage = await approverContext.newPage()
    await login(approverPage, actor)
    const applicantUserId = Number((await api(page, '/getInfo')).user.userId)
    const approverUserId = Number((await api(approverPage, '/getInfo')).user.userId)
    expect(applicantUserId).toBe(initiatorId)
    expect(approverUserId).toBe(actor.userId)
    expect(approverUserId).not.toBe(applicantUserId)
    model.participants = { applicantUserId, approverUserId }

    await claimThroughPage(approverPage, model)
    model.assignments.claimed = taskAssignment(model.processInstanceId)
    expect(model.assignments.claimed).toEqual({ taskId: model.taskIds.original, ...candidateAssignment, assignee: String(approverUserId) })
    await expect(approverPage.getByRole('button', { name: '重新提交', exact: true })).toHaveCount(0)
    await approverPage.getByRole('button', { name: '退回', exact: true }).click()
    const dialog = approverPage.getByRole('dialog', { name: '退回任务' })
    await dialog.getByPlaceholder('请输入退回原因').fill('N09申请补充')
    await responseFor(approverPage, '/workflow/task/return', () => dialog.getByRole('button', { name: '确认', exact: true }).click())
    await expect(approverPage).toHaveURL(/\/office\/todo/)
    await openTodo(page, model)
    await expect(page.getByRole('button', { name: '重新提交', exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: '通过', exact: true })).toHaveCount(0)
    model.taskIds.edit = new URL(page.url()).searchParams.get('taskId')
    expect(model.taskIds.edit).not.toBe(model.taskIds.original)
    model.assignments.returned = taskAssignment(model.processInstanceId)
    expect(model.assignments.returned).toEqual({ taskId: model.taskIds.edit, assignee: String(applicantUserId), owner: null,
      candidateUserIds: [], candidateGroupIds: [] })
    await page.getByRole('textbox', { name: '申请标题', exact: true }).fill(`${runKey}_修改后重提`)
    const uploaded = await responseFor(page, '/workflow/attachment', () => page.locator('input[type="file"]').setInputFiles({
      name: `${runKey}_resubmit.txt`, mimeType: 'text/plain', buffer: Buffer.from('N09 resubmit evidence')
    }))
    attachments.push(uploaded.attachmentId)
    model.attachmentIds.push(uploaded.attachmentId)
    await responseFor(page, '/workflow/task/resubmit', async () => {
      await page.getByRole('button', { name: '重新提交', exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: '确定', exact: true }).click()
    })
    await expect(page).toHaveURL(/\/office\/todo/)
    model.assignments.resubmitted = taskAssignment(model.processInstanceId)
    model.taskIds.resumed = model.assignments.resubmitted.taskId
    expect(model.taskIds.resumed, '普通重提在申请人修改任务上恢复原审批配置').toBe(model.taskIds.edit)
    expect(model.assignments.resubmitted).toEqual({ ...model.assignments.initial, taskId: model.taskIds.edit })

    await claimThroughPage(approverPage, model)
    expect(new URL(approverPage.url()).searchParams.get('taskId')).toBe(model.taskIds.edit)
    model.assignments.reclaimed = taskAssignment(model.processInstanceId)
    expect(model.assignments.reclaimed).toEqual({ ...model.assignments.claimed, taskId: model.taskIds.edit })
    await expect(approverPage.getByRole('button', { name: '重新提交', exact: true })).toHaveCount(0)
    await expect(approverPage.getByRole('textbox', { name: '申请标题', exact: true })).toHaveValue(`${runKey}_修改后重提`)
    await approve(approverPage, 'N09不同审批人重提完成')
    await test.info().attach('N09-2-return-resubmit', { body: await approverPage.screenshot(), contentType: 'image/png' })
  } finally {
    await approverContext.close()
  }
})

test('N09-3 动态会签双页面revision冲突、隐藏恢复和通知失败后补偿', async ({ page, context }) => {
  test.setTimeout(300000)
  await login(page)
  const model = await createAndPublish(page, 'mi')
  let notificationCycle
  try {
    notificationCycle = await prepareNotificationCycle(adminPage, model, runKey)
    notificationCycles.push(notificationCycle)
  } catch (error) {
    if (error.notificationCycle) notificationCycles.push(error.notificationCycle)
    throw error
  }
  const instanceId = await startThroughDraft(page, model, '会签链')
  await openTodo(page, model)
  const before = (await api(page, `/workflow/process/observation?processInstanceId=${instanceId}&taskId=${model.taskIds.original}`)).data.detail.multiInstanceState.revision
  model.multiInstance = { initialRevision: before }
  await page.getByRole('button', { name: '通过', exact: true }).click()
  const approval = page.getByRole('dialog', { name: '通过任务' })
  await approval.getByPlaceholder('请输入审批意见').fill('N09冲突保留意见')
  const otherPage = await context.newPage()
  try {
    await otherPage.goto(page.url())
    await otherPage.locator('.workflow-detail__actions').getByRole('button', { name: '加签', exact: true }).click()
    const add = otherPage.getByRole('dialog', { name: '增加会签成员' })
    await add.getByRole('combobox').fill(actor.userName)
    await otherPage.getByRole('option').filter({ hasText: actor.nickName }).click()
    await otherPage.keyboard.press('Escape')
    await add.getByPlaceholder('请输入加签原因').fill('N09第二页面增加成员')
    await responseFor(otherPage, '/workflow/task/multiInstance/adjust', () => add.getByRole('button', { name: '确认', exact: true }).click())
    await expect(otherPage.locator('.workflow-detail__multi-instance').getByText(`版本 ${before + 1}`, { exact: true })).toBeVisible()
    const staleResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/dev-api/workflow/task/complete')
    await approval.getByRole('button', { name: '确认', exact: true }).click()
    const conflict = await (await staleResponse).json()
    expect(conflict).toMatchObject({ code: 409, subCode: 'WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT' })
    await expect(approval.getByPlaceholder('请输入审批意见')).toHaveValue('N09冲突保留意见')
    await expect(approval.getByText('会签成员已发生变化，已刷新最新版本。请核对成员后再次点击确认提交。')).toBeVisible()
    Object.assign(model.multiInstance, { addedRevision: before + 1, conflictCode: conflict.subCode, opinionPreserved: true })
    await approval.getByRole('button', { name: '取消', exact: true }).click()
    const member = otherPage.locator('.workflow-detail__multi-instance').getByRole('row').filter({ hasText: actor.nickName })
    await member.getByRole('button', { name: `移除 ${actor.nickName}`, exact: true }).click()
    const remove = otherPage.getByRole('dialog', { name: '移除会签成员' })
    await remove.getByPlaceholder('请输入减签原因').fill('N09移除新增成员后保留原任务')
    await responseFor(otherPage, '/workflow/task/multiInstance/adjust', () => remove.getByRole('button', { name: '确认', exact: true }).click())
    await page.getByRole('textbox', { name: '申请标题', exact: true }).fill(`${runKey}_隐藏恢复草稿`)
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, value: true })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await expect(page.locator('.workflow-detail__actions')).toHaveCount(0)
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { configurable: true, value: false })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await expect(page.locator('.workflow-detail__multi-instance').getByText(`版本 ${before + 2}`, { exact: true })).toBeVisible()
    await expect(page.getByRole('textbox', { name: '申请标题', exact: true })).toHaveValue(`${runKey}_隐藏恢复草稿`)
    Object.assign(model.multiInstance, { removedRevision: before + 2, visibilityDraftPreserved: true })
    await approve(page, 'N09核对修订后通过')
    await test.info().attach('N09-3-multi-instance', { body: await page.screenshot(), contentType: 'image/png' })
    await notificationCycle.finish(instanceId)
  } finally {
    await otherPage.close()
  }
})

function bpmn(key, kind, formId) {
  const permissions = '<flowable:formProperty id="approva_permission_default" type="string" readable="true" writable="true"/>'
    + ['requestTitle', 'evidence'].map(name => `<flowable:formProperty id="approva_permission_field_${name}" variable="${name}" type="string" readable="true" writable="true"/>`).join('')
  const properties = (values, fields = '') => `<extensionElements><flowable:properties>${Object.entries(values).map(([name, value]) => `<flowable:property name="${name}" value="${value}"/>`).join('')}</flowable:properties>${fields}</extensionElements>`
  const assignment = kind === 'mi'
    ? properties({ 'approva.multiInstance.identityType': 'USER', 'approva.multiInstance.identityIds': String(initiatorId) }, permissions)
      + '<multiInstanceLoopCharacteristics isSequential="false" flowable:collection="${multiInstanceHandler.getConfiguredUserIds(execution)}" flowable:elementVariable="assignee"><completionCondition>${nrOfCompletedInstances == nrOfInstances}</completionCondition></multiInstanceLoopCharacteristics>'
    : properties({ 'approva.assignment.ruleVersion': '1', 'approva.assignment.type': kind === 'returned' ? 'CANDIDATE_USERS' : 'STARTER',
      'approva.assignment.targetIds': kind === 'returned' ? String(actor.userId) : '', 'approva.assignment.formField': '', 'approva.assignment.noMatchPolicy': 'FAIL' }, permissions)
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" targetNamespace="urn:n09:e2e">
<process id="${key}" name="${key}" isExecutable="true">
${properties({ 'approva.startScope.ruleVersion': '1', 'approva.startScope.type': 'PUBLIC', 'approva.startScope.targetIds': '', 'approva.startScope.noMatchPolicy': 'FAIL' })}
<startEvent id="start" name="申请" flowable:formKey="key_${formId}"><extensionElements>${permissions}</extensionElements></startEvent>
<sequenceFlow id="toReview" sourceRef="start" targetRef="review"/>
<userTask id="review" name="审批" flowable:formKey="key_${formId}">${assignment}</userTask><sequenceFlow id="toEnd" sourceRef="review" targetRef="end"/><endEvent id="end"/>
</process>
<bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="${key}">
<bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="100" y="100" width="36" height="36"/></bpmndi:BPMNShape>
<bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="230" y="80" width="100" height="80"/></bpmndi:BPMNShape>
<bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="430" y="100" width="36" height="36"/></bpmndi:BPMNShape>
<bpmndi:BPMNEdge id="toReview_di" bpmnElement="toReview"><di:waypoint x="136" y="118"/><di:waypoint x="230" y="118"/></bpmndi:BPMNEdge>
<bpmndi:BPMNEdge id="toEnd_di" bpmnElement="toEnd"><di:waypoint x="330" y="118"/><di:waypoint x="430" y="118"/></bpmndi:BPMNEdge>
</bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>`
}
