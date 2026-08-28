<template>
  <el-dialog
    :model-value="modelValue"
    title="配置邮件服务"
    width="620px"
    append-to-body
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    :before-close="beforeClose"
    @update:model-value="updateVisible"
    @closed="resetDialogState"
  >
    <div v-loading="loading" class="mail-config-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="mail-config-dialog__grid">
          <el-form-item label="SMTP 服务器" prop="smtpHost">
            <el-input v-model="form.smtpHost" maxlength="255" placeholder="例如 smtp.example.com" autocomplete="off" />
          </el-form-item>
          <el-form-item label="端口" prop="smtpPort">
            <el-input-number v-model="form.smtpPort" :min="1" :max="65535" :precision="0" controls-position="right" />
          </el-form-item>
          <el-form-item label="加密方式" prop="encryptionMode">
            <el-select v-model="form.encryptionMode" placeholder="请选择加密方式">
              <el-option label="STARTTLS" value="STARTTLS" />
              <el-option label="SSL / TLS" value="SSL" />
              <el-option label="不加密" value="NONE" />
            </el-select>
          </el-form-item>
          <el-form-item label="登录账号" prop="username">
            <el-input v-model="form.username" maxlength="255" placeholder="notify@example.com" autocomplete="username" />
          </el-form-item>
          <el-form-item class="mail-config-dialog__full" label="授权码或密码" prop="credential">
            <el-input
              v-model="form.credential"
              type="password"
              maxlength="1024"
              :placeholder="credentialPlaceholder"
              autocomplete="new-password"
            />
            <span class="mail-config-dialog__field-note">
              {{ credentialHint }}
            </span>
          </el-form-item>
          <el-form-item label="发件邮箱" prop="fromAddress">
            <el-input v-model="form.fromAddress" maxlength="255" placeholder="notify@example.com" autocomplete="off" />
          </el-form-item>
          <el-form-item label="发件人名称" prop="senderName">
            <el-input v-model="form.senderName" maxlength="255" placeholder="ApprovaPlat 审批通知" autocomplete="off" />
          </el-form-item>
        </div>
      </el-form>

      <section class="mail-config-dialog__test" aria-label="测试邮件">
        <el-form ref="testFormRef" :model="testForm" :rules="testRules" @submit.prevent>
          <el-form-item prop="testRecipient">
            <div class="mail-config-dialog__test-row">
              <el-input
                v-model="testForm.testRecipient"
                maxlength="255"
                placeholder="输入测试收件邮箱"
                autocomplete="off"
                @keyup.enter="sendTestMail"
              />
              <el-button :loading="testing" :disabled="saving || loading" @click="sendTestMail">发送测试邮件</el-button>
            </div>
          </el-form-item>
        </el-form>
        <el-alert
          v-if="testResult.message"
          :title="testResult.message"
          :type="testResult.type"
          :closable="false"
          show-icon
        />
        <p>测试成功只表示当前输入可用，不代表配置已经保存。</p>
      </section>
    </div>

    <template #footer>
      <el-button :disabled="busy" @click="closeDialog">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="testing || loading" @click="saveConfig">保存配置</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import {
  getWorkflowNotificationMailConfig,
  saveWorkflowNotificationMailConfig,
  testWorkflowNotificationMailConfig
} from '@/api/workflow/notification'
import {
  createEmptyMailAuthenticationIdentity,
  createEmptyMailConfigForm,
  hasMailAuthenticationIdentityChanged,
  normalizeMailAuthenticationIdentity,
  normalizeMailConfigResponse
} from './notificationAdminRules.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'saved'])
const { proxy } = getCurrentInstance()

const formRef = ref(null)
const testFormRef = ref(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const form = reactive(createEmptyMailConfigForm())
const testForm = reactive({ testRecipient: '' })
// meta 只保存服务端脱敏状态和 CAS 版本，不接收凭据、密文或密钥信息。
const meta = reactive({ configured: false, credentialConfigured: false, revision: 0 })
// loadedAuthenticationIdentity 是最近一次后端回读的认证身份基线，不包含授权码或密文。
const loadedAuthenticationIdentity = reactive(createEmptyMailAuthenticationIdentity())
const testResult = reactive({ type: 'success', message: '' })
const busy = computed(() => loading.value || saving.value || testing.value)
const authenticationIdentityChanged = computed(() => {
  if (!meta.configured) return false
  return hasMailAuthenticationIdentityChanged(loadedAuthenticationIdentity, form)
})
const credentialPlaceholder = computed(() => {
  if (!meta.credentialConfigured) return '请输入授权码或密码'
  return authenticationIdentityChanged.value
    ? '认证连接已变更，请重新填写'
    : '已配置，留空则不修改'
})
const credentialHint = computed(() => {
  if (!meta.credentialConfigured) return '首次配置必须填写授权码或密码。'
  if (authenticationIdentityChanged.value) {
    return 'SMTP 服务器、端口、加密方式或登录账号已变更，必须重新填写授权码或密码。'
  }
  return '已配置，认证连接不变时留空则不修改。保存值不会再次回显。'
})
let loadSequence = 0

const rules = {
  smtpHost: [
    { required: true, message: '请输入 SMTP 服务器', trigger: 'blur' },
    { validator: validateTextField, trigger: 'blur' }
  ],
  smtpPort: [{ required: true, type: 'number', min: 1, max: 65535, message: '端口必须在 1 至 65535 之间', trigger: 'change' }],
  encryptionMode: [{ required: true, message: '请选择加密方式', trigger: 'change' }],
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    { type: 'email', message: '登录账号必须是有效邮箱', trigger: 'blur' }
  ],
  credential: [{ validator: validateCredential, trigger: 'blur' }],
  fromAddress: [
    { required: true, message: '请输入发件邮箱', trigger: 'blur' },
    { type: 'email', message: '发件邮箱格式不正确', trigger: 'blur' }
  ],
  senderName: [
    { required: true, message: '请输入发件人名称', trigger: 'blur' },
    { validator: validateTextField, trigger: 'blur' }
  ]
}
const testRules = {
  testRecipient: [
    { required: true, message: '请输入测试收件邮箱', trigger: 'blur' },
    { type: 'email', message: '测试收件邮箱格式不正确', trigger: 'blur' }
  ]
}

/**
 * 校验普通 SMTP 文本字段不为空且不含控制字符。
 * @param {object} rule Element Plus 校验规则。
 * @param {string} value 当前字段值。
 * @param {Function} callback 校验结果回调。
 * @returns {void} 合法时无错误返回。
 */
function validateTextField(rule, value, callback) {
  const normalized = String(value || '').trim()
  // hasControlCharacter 表示输入中存在日志、协议或表头不应接收的 ASCII 控制字符。
  const hasControlCharacter = [...normalized].some(character => {
    const codePoint = character.codePointAt(0)
    return codePoint < 32 || codePoint === 127
  })
  if (!normalized || hasControlCharacter) {
    callback(new Error('字段内容不合法'))
    return
  }
  callback()
}

/**
 * 根据正式配置状态校验授权码；已配置时空值表示保留原密文。
 * @param {object} rule Element Plus 校验规则。
 * @param {string} value 当前用户输入的授权码或密码。
 * @param {Function} callback 校验结果回调。
 * @returns {void} 首次配置缺少授权码时返回错误。
 */
function validateCredential(rule, value, callback) {
  if ((!meta.credentialConfigured || authenticationIdentityChanged.value) && !String(value || '').trim()) {
    callback(new Error(authenticationIdentityChanged.value
      ? '认证连接已变更，请重新填写授权码或密码'
      : '首次配置必须填写授权码或密码'))
    return
  }
  callback()
}

/**
 * 从正式后端重新读取脱敏 SMTP 配置和最新 revision。
 * @param {{notifyFailure?:boolean}} options 是否由本函数展示唯一的加载失败提示。
 * @returns {Promise<{loaded:boolean,error?:unknown}>} 当前弹窗仍打开且回读成功时 loaded 为 true。
 */
async function loadConfig(options = {}) {
  const notifyFailure = options.notifyFailure !== false
  const sequence = ++loadSequence
  loading.value = true
  testResult.message = ''
  try {
    const response = await getWorkflowNotificationMailConfig()
    if (sequence !== loadSequence || !props.modelValue) return { loaded: false }
    const normalized = normalizeMailConfigResponse(response.data)
    Object.assign(form, normalized.form)
    Object.assign(meta, normalized.meta)
    // 只有真实后端回读成功后才刷新认证身份基线，测试和未保存编辑绝不改变它。
    Object.assign(loadedAuthenticationIdentity, normalizeMailAuthenticationIdentity(normalized.form))
    await nextTick()
    formRef.value?.clearValidate()
    return { loaded: true }
  } catch (error) {
    if (sequence === loadSequence && props.modelValue && notifyFailure) {
      proxy.$modal.msgError(mailConfigErrorMessage(error, '邮件服务配置加载失败'))
    }
    return { loaded: false, error }
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

/**
 * 构造 SMTP 保存和测试共同使用的精确请求字段。
 * @returns {object} 不包含测试收件人的 SMTP 请求体。
 */
function buildConfigPayload() {
  return {
    smtpHost: form.smtpHost.trim(),
    smtpPort: Number(form.smtpPort),
    encryptionMode: form.encryptionMode,
    username: form.username.trim(),
    credential: form.credential,
    fromAddress: form.fromAddress.trim(),
    senderName: form.senderName.trim(),
    expectedRevision: Number(meta.revision)
  }
}

/**
 * 保存正式 SMTP 配置，成功后强制回读新 revision 再关闭弹窗。
 * @returns {Promise<void>} 写入冲突时保留草稿；写入成功但回读失败时明确要求重新加载。
 */
async function saveConfig() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  testResult.message = ''
  try {
    await saveWorkflowNotificationMailConfig(buildConfigPayload())
    clearCredential()
    const refreshResult = await loadConfig({ notifyFailure: false })
    if (!refreshResult.loaded) {
      // PUT 已经提交成功，后续 GET 失败不能再向管理员误报为“保存失败”。
      emit('saved', { configured: true, revision: null, refreshRequired: true })
      proxy.$modal.msgWarning('邮件服务配置已保存，但刷新最新配置失败，请重新打开邮件服务或刷新页面后确认')
      emit('update:modelValue', false)
      return
    }
    emit('saved', { configured: meta.configured, revision: meta.revision })
    proxy.$modal.msgSuccess('邮件服务配置已保存')
    emit('update:modelValue', false)
  } catch (error) {
    if (isRevisionConflict(error)) {
      await promptReloadAfterConflict()
    } else {
      proxy.$modal.msgError(mailConfigErrorMessage(error, '邮件服务配置保存失败'))
    }
  } finally {
    clearCredential()
    saving.value = false
  }
}

/**
 * 使用弹窗尚未保存的 SMTP 字段发送测试邮件，成功测试不会改变正式身份基线或清除待保存授权码。
 * @returns {Promise<void>} 成功时只确认本次测试送达，不改变正式配置状态。
 */
async function sendTestMail() {
  const [configValid, recipientValid] = await Promise.all([
    formRef.value.validate().catch(() => false),
    testFormRef.value.validate().catch(() => false)
  ])
  if (!configValid || !recipientValid) return
  testing.value = true
  testResult.message = ''
  try {
    const response = await testWorkflowNotificationMailConfig({
      ...buildConfigPayload(),
      testRecipient: testForm.testRecipient.trim()
    })
    if (response.data?.success !== true) {
      testResult.type = 'error'
      testResult.message = '测试邮件未确认发送成功，请检查当前配置'
      return
    }
    testResult.type = 'success'
    testResult.message = '测试邮件发送成功，当前配置尚未保存'
  } catch (error) {
    testResult.type = isRevisionConflict(error) ? 'warning' : 'error'
    testResult.message = mailConfigErrorMessage(error, '测试邮件发送失败')
    if (isRevisionConflict(error)) await promptReloadAfterConflict()
  } finally {
    testing.value = false
  }
}

/**
 * 判断请求失败是否为 SMTP 配置 revision 冲突，其他 HTTP 409 不得误导用户重新加载。
 * @param {unknown} error 统一 BusinessError 或 Axios 错误。
 * @returns {boolean} 后端稳定子码为 MAIL_CONFIG_REVISION_CONFLICT 时返回 true。
 */
function isRevisionConflict(error) {
  return responseSubCode(error) === 'MAIL_CONFIG_REVISION_CONFLICT'
}

/**
 * 从统一业务错误或真实 HTTP 错误响应读取受限稳定子码。
 * @param {unknown} error BusinessError 或 Axios 错误。
 * @returns {string} 合法大写机器子码，缺失或非法时返回空字符串。
 */
function responseSubCode(error) {
  const value = typeof error?.subCode === 'string'
    ? error.subCode
    : error?.response?.data?.subCode
  const normalized = typeof value === 'string' ? value.trim() : ''
  return /^[A-Z][A-Z0-9_]{0,63}$/.test(normalized) ? normalized : ''
}

/**
 * 按稳定子码返回 SMTP 配置错误提示，认证身份变化时始终引导用户重新输入授权码。
 * @param {unknown} error BusinessError 或 Axios 错误。
 * @param {string} fallback 无稳定错误文本时的兜底提示。
 * @returns {string} 不包含凭据或请求体的用户提示。
 */
function mailConfigErrorMessage(error, fallback) {
  if (responseSubCode(error) === 'MAIL_CREDENTIAL_REENTRY_REQUIRED') {
    return 'SMTP 认证连接已变更，请重新填写授权码或密码'
  }
  return requestErrorMessage(error, fallback)
}

/**
 * SMTP 配置冲突后请求用户确认，只有确认后才覆盖当前未保存草稿。
 * @returns {Promise<void>} 用户确认时重新读取正式配置，取消时保留当前非敏感输入。
 */
async function promptReloadAfterConflict() {
  try {
    await proxy.$modal.confirm('邮件配置已被其他管理员修改，是否重新加载最新配置？')
    await loadConfig()
  } catch {
    // 用户取消表示继续查看当前草稿；下一次保存仍会由服务端 revision 拒绝陈旧写入。
  }
}

/**
 * 从统一请求错误中提取经过后端分类的短提示，不拼接请求体或凭据。
 * @param {unknown} error BusinessError 或 Axios 错误。
 * @param {string} fallback 无稳定错误文本时的兜底提示。
 * @returns {string} 最多 180 个字符的用户提示。
 */
function requestErrorMessage(error, fallback) {
  const responseMessage = typeof error?.response?.data?.msg === 'string'
    ? error.response.data.msg.trim()
    : ''
  const businessMessage = typeof error?.message === 'string' ? error.message.trim() : ''
  const message = responseMessage || businessMessage
  return (message || fallback).slice(0, 180)
}

/**
 * 清除组件内存中的明文授权码，并同步清理字段校验状态。
 * @returns {void} 明文授权码变为空字符串。
 */
function clearCredential() {
  form.credential = ''
  formRef.value?.clearValidate('credential')
}

/**
 * 关闭弹窗；存在请求时拒绝关闭，避免响应落入已经销毁的表单状态。
 * @returns {void} 空闲时通知父页面关闭。
 */
function closeDialog() {
  if (busy.value) return
  clearCredential()
  testForm.testRecipient = ''
  emit('update:modelValue', false)
}

/**
 * 处理 Element Plus 弹窗关闭钩子，运行中请求必须先完成。
 * @param {Function} done Element Plus 提供的关闭确认回调。
 * @returns {void} 空闲时继续关闭，否则显示提示并保持弹窗。
 */
function beforeClose(done) {
  if (busy.value) {
    proxy.$modal.msgWarning('当前操作尚未完成，请稍候')
    return
  }
  clearCredential()
  done()
}

/**
 * 将 Element Plus 的可见状态变化转交父页面统一管理。
 * @param {boolean} visible 目标弹窗状态。
 * @returns {void} 关闭前清除敏感输入。
 */
function updateVisible(visible) {
  if (!visible) clearCredential()
  emit('update:modelValue', visible)
}

/**
 * 弹窗完全关闭后清除全部草稿、测试结果和服务端状态快照。
 * @returns {void} 下一次打开必须重新读取正式后端配置。
 */
function resetDialogState() {
  loadSequence += 1
  Object.assign(form, createEmptyMailConfigForm())
  Object.assign(meta, { configured: false, credentialConfigured: false, revision: 0 })
  Object.assign(loadedAuthenticationIdentity, createEmptyMailAuthenticationIdentity())
  testForm.testRecipient = ''
  testResult.type = 'success'
  testResult.message = ''
  loading.value = false
  formRef.value?.clearValidate()
  testFormRef.value?.clearValidate()
}

watch(() => props.modelValue, visible => {
  if (visible) {
    resetDialogState()
    loadConfig()
    return
  }
  clearCredential()
  testForm.testRecipient = ''
  loadSequence += 1
})
</script>

<style scoped lang="scss">
.mail-config-dialog { color: var(--el-text-color-primary); }
.mail-config-dialog__grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.mail-config-dialog__full { grid-column: 1 / -1; }
.mail-config-dialog :deep(.el-input-number),
.mail-config-dialog :deep(.el-select) { width: 100%; }
.mail-config-dialog__field-note { display: block; margin-top: 5px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.mail-config-dialog__test { margin-top: 4px; padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); }
.mail-config-dialog__test :deep(.el-form-item) { margin-bottom: 10px; }
.mail-config-dialog__test-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; width: 100%; }
.mail-config-dialog__test p { margin: 9px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.55; }
.mail-config-dialog__test :deep(.el-alert) { margin-top: 8px; }

@media (max-width: 700px) {
  .mail-config-dialog__grid { grid-template-columns: 1fr; }
  .mail-config-dialog__full { grid-column: auto; }
  .mail-config-dialog__test-row { grid-template-columns: 1fr; }
}
</style>
