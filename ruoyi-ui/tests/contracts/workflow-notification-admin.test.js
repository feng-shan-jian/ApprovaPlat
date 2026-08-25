import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

/**
 * 读取契约测试目标源码，并统一 Windows 与 Unix 换行，避免源码区间定位受检出策略影响。
 * @param {string} relativePath 相对于当前测试文件的源码路径。
 * @returns {string} 使用 LF 换行的源码文本。
 */
function readSource(relativePath) {
  return readFileSync(new URL(relativePath, import.meta.url), 'utf8').replace(/\r\n?/g, '\n')
}

const API_SOURCE = readSource('../../src/api/workflow/notification.js')
const REQUEST_SOURCE = readSource('../../src/utils/request.js')
const PAGE_SOURCE = readSource('../../src/views/workflow/notification/index.vue')
const MAIL_DIALOG_SOURCE = readSource('../../src/views/workflow/notification/MailConfigDialog.vue')

/**
 * 提取一个导出 API 函数的源码区间，避免其他函数中的安全配置造成误判。
 * @param {string} source API 源码。
 * @param {string} functionName 目标导出函数名。
 * @returns {string} 当前函数至下一个导出函数前的源码。
 */
function exportedFunctionSource(source, functionName) {
  const start = source.indexOf(`export function ${functionName}`)
  assert.notEqual(start, -1, `缺少 API 函数 ${functionName}`)
  const next = source.indexOf('\nexport function ', start + 1)
  return source.slice(start, next === -1 ? source.length : next)
}

test('SMTP 与真实流程目录 API 使用唯一正式路径', () => {
  assert.match(API_SOURCE, /\/workflow\/notification\/catalog\/processes'/)
  assert.match(API_SOURCE, /catalog\/processes\/\$\{encodeURIComponent\(processDefinitionKey\)\}\/nodes/)
  assert.match(API_SOURCE, /\/workflow\/notification\/mail-config'/)
  assert.match(API_SOURCE, /\/workflow\/notification\/mail-config\/test'/)
})

test('SMTP 保存和测试请求不会进入会话重复提交缓存', () => {
  const saveSource = exportedFunctionSource(API_SOURCE, 'saveWorkflowNotificationMailConfig')
  const testSource = exportedFunctionSource(API_SOURCE, 'testWorkflowNotificationMailConfig')

  assert.match(saveSource, /headers:\s*\{\s*repeatSubmit:\s*false\s*\}/)
  assert.match(testSource, /headers:\s*\{\s*repeatSubmit:\s*false\s*\}/)
  assert.match(testSource, /timeout:\s*30000/)
  assert.doesNotMatch(API_SOURCE + PAGE_SOURCE + MAIL_DIALOG_SOURCE, /(?:localStorage|sessionStorage)\s*\.|cache\.session/)
})

test('局部业务错误在 AjaxResult 和真实 HTTP 失败路径都只提示一次', () => {
  for (const functionName of [
    'saveWorkflowNotificationPolicy',
    'listWorkflowNotificationNodes',
    'getWorkflowNotificationMailConfig',
    'saveWorkflowNotificationMailConfig',
    'testWorkflowNotificationMailConfig',
    'compensateWorkflowNotification'
  ]) {
    assert.match(exportedFunctionSource(API_SOURCE, functionName), /suppressErrorMessage:\s*true/)
  }

  assert.match(REQUEST_SOURCE, /const suppressErrorMessage = res\.config\?\.suppressErrorMessage === true/)
  assert.match(REQUEST_SOURCE, /error\?\.suppressErrorMessage === true \|\| error\?\.config\?\.suppressErrorMessage === true/)
  assert.ok((REQUEST_SOURCE.match(/if \(!suppressErrorMessage\)/g) || []).length >= 5)
  assert.match(REQUEST_SOURCE, /if \(!suppressErrorMessage\) ElNotification\.error/)
})

test('请求拦截器不记录或传播可能包含 SMTP 授权码的 Axios 错误', () => {
  assert.doesNotMatch(REQUEST_SOURCE, /console\.(?:log|error|warn)\(error\)/)
  assert.match(REQUEST_SOURCE, /const safeError = new Error\('请求发送失败'\)[\s\S]*?return Promise\.reject\(safeError\)/)
  assert.doesNotMatch(REQUEST_SOURCE, /safeError\.(?:config|request|data)\s*=/)
})

test('真实非 2xx 响应优先保留后端安全提示和稳定子码', () => {
  assert.match(REQUEST_SOURCE, /const message = \(backendMessage \|\| errorCode\[responseCode\] \|\| errorCode\.default\)\.slice\(0, 180\)/)
  assert.match(REQUEST_SOURCE, /createBusinessError\(responseCode, message, responseData\.subCode\)/)
})

test('SMTP 弹窗从不回显或映射服务端敏感字段', () => {
  assert.match(MAIL_DIALOG_SOURCE, /autocomplete="new-password"/)
  assert.match(MAIL_DIALOG_SOURCE, /credential:\s*''/)
  assert.match(MAIL_DIALOG_SOURCE, /finally\s*\{[\s\S]*?clearCredential\(\)/)
  assert.match(MAIL_DIALOG_SOURCE, /response\.data\?\.success\s*!==\s*true/)
  assert.match(MAIL_DIALOG_SOURCE, /testRecipient:\s*testForm\.testRecipient\.trim\(\)/)
  assert.doesNotMatch(MAIL_DIALOG_SOURCE, /credentialCiphertext|credentialIv/)
})

test('用户新授权码测试后保留到保存并在离开弹窗时清除', () => {
  const sendStart = MAIL_DIALOG_SOURCE.indexOf('async function sendTestMail()')
  const sendEnd = MAIL_DIALOG_SOURCE.indexOf('\nfunction isRevisionConflict', sendStart)
  const sendSource = MAIL_DIALOG_SOURCE.slice(sendStart, sendEnd)
  assert.doesNotMatch(sendSource, /clearCredential\(\)/)
  assert.match(MAIL_DIALOG_SOURCE, /async function saveConfig\(\)[\s\S]*?finally\s*\{[\s\S]*?clearCredential\(\)/)
  assert.match(MAIL_DIALOG_SOURCE, /function closeDialog\(\)[\s\S]*?clearCredential\(\)/)
})

test('真实 HTTP 错误按稳定子码和安全后端提示精确处理', () => {
  assert.match(MAIL_DIALOG_SOURCE, /MAIL_CONFIG_REVISION_CONFLICT/)
  assert.match(MAIL_DIALOG_SOURCE, /MAIL_CREDENTIAL_REENTRY_REQUIRED/)
  assert.match(MAIL_DIALOG_SOURCE, /error\?\.response\?\.data\?\.msg/)
  assert.doesNotMatch(MAIL_DIALOG_SOURCE, /Number\(error\?\.response\?\.status\)\s*===\s*409/)
  assert.match(PAGE_SOURCE, /NOTIFICATION_POLICY_REVISION_CONFLICT/)
  assert.match(PAGE_SOURCE, /NOTIFICATION_POLICY_DUPLICATE/)
  assert.match(PAGE_SOURCE, /NOTIFICATION_OUTBOX_STATE_CONFLICT/)
  assert.match(PAGE_SOURCE, /SMTP_NOT_CONFIGURED/)
  assert.match(PAGE_SOURCE, /请联系具有邮件服务管理权限的管理员/)
})

test('旧 SMTP 授权码只沿用于后端回读的同一认证身份', () => {
  const identityStart = MAIL_DIALOG_SOURCE.indexOf('function normalizeAuthenticationIdentity')
  const identityEnd = MAIL_DIALOG_SOURCE.indexOf('\n\n/**', identityStart)
  const identitySource = MAIL_DIALOG_SOURCE.slice(identityStart, identityEnd)

  assert.notEqual(identityStart, -1)
  assert.match(identitySource, /smtpHost/)
  assert.match(identitySource, /smtpPort/)
  assert.match(identitySource, /encryptionMode/)
  assert.match(identitySource, /username/)
  assert.doesNotMatch(identitySource, /fromAddress|senderName|credential/)
  assert.match(MAIL_DIALOG_SOURCE, /authenticationIdentityChanged\.value[\s\S]*?重新填写授权码或密码/)
  assert.match(MAIL_DIALOG_SOURCE, /Object\.assign\(loadedAuthenticationIdentity, normalizeAuthenticationIdentity\(normalized\.form\)\)/)
})

test('通知页面按四类权限控制页签、请求和入口', () => {
  for (const permission of [
    'workflow:notification:manage',
    'workflow:notification:audit',
    'workflow:notification:retry',
    'workflow:notification:mailManage'
  ]) {
    assert.match(PAGE_SOURCE, new RegExp(permission.replaceAll(':', '\\:')))
  }
  assert.match(PAGE_SOURCE, /if \(!canManagePolicy\.value\) return/)
  assert.match(PAGE_SOURCE, /if \(!canAuditOutbox\.value\) return/)
  assert.match(PAGE_SOURCE, /canRetryOutbox && canCompensate\(row\)/)
  assert.match(PAGE_SOURCE, /row\?\.canCompensate === true \|\| Number\(row\?\.canCompensate\) === 1/)
  assert.match(PAGE_SOURCE, /outboxRecipientLabel\(row\)/)
  assert.match(PAGE_SOURCE, /v-if="canMailManage"[^>]*icon="Message"/)
})

test('策略只接受授权目录选择且邮件可用状态来自后端', () => {
  assert.match(PAGE_SOURCE, /policyResponse\.mailChannelAvailable === true/)
  assert.match(PAGE_SOURCE, /listWorkflowNotificationProcesses\(\)/)
  assert.match(PAGE_SOURCE, /listWorkflowNotificationNodes\(processDefinitionKey\)/)
  assert.match(PAGE_SOURCE, /请选择当前有权管理的真实流程/)
  assert.match(PAGE_SOURCE, /请选择当前流程中的真实节点/)
  assert.doesNotMatch(PAGE_SOURCE, /label="流程 key"|label="节点 key"/i)
  assert.doesNotMatch(PAGE_SOURCE, /<el-input[^>]*v-model="policyDialog\.form\.(?:processDefinitionKey|taskDefinitionKey)"/)
})

test('节点目录读取失败具有独立错误状态且会阻止保存', () => {
  assert.match(PAGE_SOURCE, /const nodeCatalogErrors = ref\(\{\}\)/)
  assert.match(PAGE_SOURCE, /nodes: null,[\s\S]*?requestErrorMessage\(error, '节点目录加载失败，请稍后重试'\)/)
  assert.match(PAGE_SOURCE, /v-if="currentNodeLoadError"/)
  assert.match(PAGE_SOURCE, /if \(currentNodeLoadError\.value\)[\s\S]*?节点目录加载失败，请重新加载后再保存/)
  assert.doesNotMatch(PAGE_SOURCE, /catch\s*\{\s*return \[processKey, \[\]\]\s*\}/)
})

test('SMTP PUT 成功但 GET 回读失败时不会误报保存失败', () => {
  assert.match(MAIL_DIALOG_SOURCE, /await saveWorkflowNotificationMailConfig\(buildConfigPayload\(\)\)[\s\S]*?loadConfig\(\{ notifyFailure: false \}\)/)
  assert.match(MAIL_DIALOG_SOURCE, /邮件服务配置已保存，但刷新最新配置失败，请重新打开邮件服务或刷新页面后确认/)
  assert.match(MAIL_DIALOG_SOURCE, /emit\('saved', \{ configured: true, revision: null, refreshRequired: true \}\)/)
})

test('页面遵守已确认原型删改和补偿语义', () => {
  assert.match(PAGE_SOURCE, /<h2>审批通知<\/h2>/)
  assert.match(PAGE_SOURCE, /发生时间/)
  assert.match(PAGE_SOURCE, /失败原因/)
  assert.match(PAGE_SOURCE, /死信已重新进入投递队列/)
  assert.doesNotMatch(PAGE_SOURCE, /流程策略与可靠投递运维|邮件服务正常/)
  assert.doesNotMatch(MAIL_DIALOG_SOURCE, /service-state|已启用.*状态|服务介绍/)
})
