import assert from 'node:assert/strict'
import test from 'node:test'
import {
  NOTIFICATION_API_PATHS,
  notificationMailConfigReadRequest,
  notificationMailConfigSaveRequest,
  notificationMailConfigTestRequest,
  notificationNodeCatalogRequest,
  notificationOutboxCompensationRequest,
  notificationPolicySaveRequest,
  notificationProcessCatalogRequest
} from '../../src/api/workflow/notificationRequestConfig.js'
import {
  createHttpBusinessError,
  createSafeRequestError
} from '../../src/utils/requestError.js'
import {
  WORKFLOW_NOTIFICATION_PERMISSIONS,
  canCompensateNotificationOutbox,
  hasMailAuthenticationIdentityChanged,
  nodeCatalogValidationError,
  normalizeMailAuthenticationIdentity,
  normalizeMailConfigResponse,
  processCatalogValidationError,
  resolveNotificationInitialTab
} from '../../src/views/workflow/notification/notificationAdminRules.js'

test('通知管理请求配置使用唯一正式路径和敏感请求边界', () => {
  const payload = { credential: 'only-in-memory' }
  assert.equal(Object.isFrozen(NOTIFICATION_API_PATHS), true)
  assert.deepEqual(notificationProcessCatalogRequest(), {
    url: '/workflow/notification/catalog/processes',
    method: 'get'
  })
  assert.deepEqual(notificationNodeCatalogRequest('expense/a b'), {
    url: '/workflow/notification/catalog/processes/expense%2Fa%20b/nodes',
    method: 'get',
    suppressErrorMessage: true
  })
  assert.deepEqual(notificationMailConfigReadRequest(), {
    url: '/workflow/notification/mail-config',
    method: 'get',
    suppressErrorMessage: true
  })
  assert.deepEqual(notificationMailConfigSaveRequest(payload), {
    url: '/workflow/notification/mail-config',
    method: 'put',
    data: payload,
    suppressErrorMessage: true,
    headers: { repeatSubmit: false }
  })
  assert.deepEqual(notificationMailConfigTestRequest(payload), {
    url: '/workflow/notification/mail-config/test',
    method: 'post',
    data: payload,
    suppressErrorMessage: true,
    headers: { repeatSubmit: false },
    timeout: 30000
  })
  assert.equal(notificationPolicySaveRequest(payload).suppressErrorMessage, true)
  assert.equal(notificationOutboxCompensationRequest(12).suppressErrorMessage, true)
})

test('请求错误只保留安全提示、稳定子码和局部提示控制位', () => {
  const original = {
    config: {
      suppressErrorMessage: true,
      data: { credential: 'must-not-escape' }
    },
    request: { raw: true }
  }
  const requestError = createSafeRequestError(original, '请求发送失败', true)
  assert.equal(requestError.name, 'RequestError')
  assert.equal(requestError.suppressErrorMessage, true)
  assert.equal(Object.hasOwn(requestError, 'config'), false)
  assert.equal(Object.hasOwn(requestError, 'request'), false)
  assert.equal(Object.hasOwn(requestError, 'data'), false)

  const businessError = createHttpBusinessError({
    code: 409,
    msg: 'A'.repeat(220),
    subCode: 'MAIL_CONFIG_REVISION_CONFLICT',
    credential: 'must-not-escape'
  }, 500, { default: '系统异常' })
  assert.equal(businessError.name, 'BusinessError')
  assert.equal(businessError.code, 409)
  assert.equal(businessError.message.length, 180)
  assert.equal(businessError.subCode, 'MAIL_CONFIG_REVISION_CONFLICT')
  assert.equal(Object.hasOwn(businessError, 'response'), false)
  assert.equal(Object.hasOwn(businessError, 'data'), false)

  const invalidSubCode = createHttpBusinessError({
    code: 400,
    msg: '安全提示',
    subCode: 'bad detail with spaces'
  }, 400, { default: '系统异常' })
  assert.equal(Object.hasOwn(invalidSubCode, 'subCode'), false)
})

test('SMTP 回读只投影公开字段且授权码归属只由认证身份决定', () => {
  const normalized = normalizeMailConfigResponse({
    configured: true,
    credentialConfigured: true,
    revision: 7,
    smtpHost: 'SMTP.EXAMPLE.COM',
    smtpPort: 587,
    encryptionMode: 'STARTTLS',
    username: 'mailer@example.com',
    fromAddress: 'sender@example.com',
    senderName: '审批通知',
    credential: 'plain-secret',
    credentialCiphertext: 'cipher-secret',
    credentialIv: 'iv-secret'
  })
  assert.deepEqual(Object.keys(normalized.form), [
    'smtpHost',
    'smtpPort',
    'encryptionMode',
    'username',
    'credential',
    'fromAddress',
    'senderName'
  ])
  assert.equal(normalized.form.credential, '')
  assert.equal(JSON.stringify(normalized).includes('secret'), false)

  const baseline = normalizeMailAuthenticationIdentity(normalized.form)
  assert.equal(hasMailAuthenticationIdentityChanged(baseline, {
    ...normalized.form,
    fromAddress: 'new-sender@example.com',
    senderName: '新名称',
    credential: 'new-secret'
  }), false)
  assert.equal(hasMailAuthenticationIdentityChanged(baseline, {
    ...normalized.form,
    smtpPort: 465
  }), true)
})

test('通知权限和死信补偿由不可变权限表、服务端投影和真实状态共同决定', () => {
  assert.equal(Object.isFrozen(WORKFLOW_NOTIFICATION_PERMISSIONS), true)
  assert.deepEqual(WORKFLOW_NOTIFICATION_PERMISSIONS, {
    manage: 'workflow:notification:manage',
    audit: 'workflow:notification:audit',
    retry: 'workflow:notification:retry',
    mailManage: 'workflow:notification:mailManage'
  })
  const auditOnly = new Set([WORKFLOW_NOTIFICATION_PERMISSIONS.audit])
  assert.equal(resolveNotificationInitialTab(permission => auditOnly.has(permission)), 'outbox')
  assert.equal(resolveNotificationInitialTab(() => false), '')

  const deadLetter = { status: 'DEAD_LETTER', canCompensate: true }
  assert.equal(canCompensateNotificationOutbox(true, deadLetter), true)
  assert.equal(canCompensateNotificationOutbox(false, deadLetter), false)
  assert.equal(canCompensateNotificationOutbox(true, { ...deadLetter, status: 'RETRYING' }), false)
  assert.equal(canCompensateNotificationOutbox(true, { ...deadLetter, canCompensate: false }), false)
})

test('策略流程和节点选择只接受授权目录且目录失败时保持失败关闭', () => {
  const processes = [{ processDefinitionKey: 'expense' }]
  const nodes = [{ taskDefinitionKey: 'managerReview' }]
  assert.equal(processCatalogValidationError('PROCESS', 'expense', processes), '')
  assert.notEqual(processCatalogValidationError('PROCESS', 'unknown', processes), '')
  assert.equal(nodeCatalogValidationError('NODE', 'managerReview', nodes, ''), '')
  assert.notEqual(nodeCatalogValidationError('NODE', 'unknown', nodes, ''), '')
  assert.notEqual(nodeCatalogValidationError('NODE', 'managerReview', nodes, 'load failed'), '')
})
