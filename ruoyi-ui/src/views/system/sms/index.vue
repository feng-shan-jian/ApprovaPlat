<template>
  <div class="app-container channel-page">
    <header class="channel-page__header">
      <div>
        <h2>短信管理</h2>
        <span>供应商配置与发送审计</span>
      </div>
      <div class="channel-page__actions">
        <el-tooltip content="刷新" placement="top">
          <el-button circle text icon="Refresh" aria-label="刷新" :loading="loading" @click="loadActiveTab" />
        </el-tooltip>
        <el-button v-if="activeTab === 'configs'" v-hasPermi="['system:sms:add']" type="primary" icon="Plus" @click="openConfig()">新增配置</el-button>
        <el-button v-if="activeTab === 'logs'" v-hasPermi="['system:sms:send']" type="primary" icon="Promotion" @click="openSend">测试发送</el-button>
      </div>
    </header>

    <el-tabs v-model="activeTab" @tab-change="loadActiveTab">
      <el-tab-pane label="供应商配置" name="configs">
        <el-table v-loading="loading" :data="configs" row-key="configId">
          <el-table-column prop="configName" label="配置名称" min-width="150" />
          <el-table-column label="供应商" width="110">
            <template #default="{ row }"><el-tag effect="plain">{{ providerLabel(row.provider) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="signName" label="短信签名" min-width="140" />
          <el-table-column prop="accessKeyId" label="AccessKey ID" min-width="190" show-overflow-tooltip />
          <el-table-column label="密钥" width="90">
            <template #default="{ row }"><el-tag :type="row.secretConfigured ? 'success' : 'danger'" size="small">{{ row.secretConfigured ? '已配置' : '缺失' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><el-tag :type="row.status === '0' ? 'success' : 'info'" size="small">{{ row.status === '0' ? '启用' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="168" align="center" fixed="right">
            <template #default="{ row }">
              <el-tooltip content="编辑" placement="top"><el-button v-hasPermi="['system:sms:edit']" circle text icon="Edit" aria-label="编辑" @click="openConfig(row)" /></el-tooltip>
              <el-tooltip v-if="row.status !== '0'" content="启用" placement="top"><el-button v-hasPermi="['system:sms:edit']" circle text type="success" icon="CircleCheck" aria-label="启用" @click="activate(row)" /></el-tooltip>
              <el-tooltip v-if="row.status !== '0'" content="删除" placement="top"><el-button v-hasPermi="['system:sms:remove']" circle text type="danger" icon="Delete" aria-label="删除" @click="removeConfig(row)" /></el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="发送审计" name="logs">
        <el-table v-loading="loading" :data="logs" row-key="logId">
          <el-table-column prop="logId" label="ID" width="86" />
          <el-table-column prop="sourceType" label="来源" width="120" />
          <el-table-column prop="provider" label="供应商" width="100"><template #default="{ row }">{{ providerLabel(row.provider) }}</template></el-table-column>
          <el-table-column prop="recipientMasked" label="接收人" min-width="180" show-overflow-tooltip />
          <el-table-column prop="templateId" label="模板 ID" min-width="150" show-overflow-tooltip />
          <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag size="small" :type="logType(row.status)">{{ logLabel(row.status) }}</el-tag></template></el-table-column>
          <el-table-column prop="providerRequestId" label="请求号" min-width="180" show-overflow-tooltip />
          <el-table-column prop="errorSummary" label="失败摘要" min-width="170" show-overflow-tooltip />
          <el-table-column prop="createTime" label="创建时间" width="168" />
        </el-table>
        <pagination
          v-show="logTotal > 0"
          :total="logTotal"
          v-model:page="logQueryParams.pageNum"
          v-model:limit="logQueryParams.pageSize"
          @pagination="loadLogs"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="configDialog.visible" :title="configDialog.form.configId ? '编辑短信配置' : '新增短信配置'" width="640px" append-to-body>
      <el-form ref="configFormRef" :model="configDialog.form" :rules="configRules" label-width="112px">
        <div class="channel-page__form-grid">
          <el-form-item label="配置名称" prop="configName"><el-input v-model="configDialog.form.configName" maxlength="64" /></el-form-item>
          <el-form-item label="供应商" prop="provider"><el-select v-model="configDialog.form.provider"><el-option label="阿里云" value="ALIYUN" /><el-option label="腾讯云" value="TENCENT" /></el-select></el-form-item>
          <el-form-item label="AccessKey ID" prop="accessKeyId"><el-input v-model="configDialog.form.accessKeyId" maxlength="128" autocomplete="off" /></el-form-item>
          <el-form-item label="AccessKey Secret" prop="accessKeySecret"><el-input v-model="configDialog.form.accessKeySecret" type="password" show-password maxlength="256" autocomplete="new-password" :placeholder="configDialog.form.configId ? '留空保留原密钥' : ''" /></el-form-item>
          <el-form-item label="短信签名" prop="signName"><el-input v-model="configDialog.form.signName" maxlength="64" /></el-form-item>
          <el-form-item v-if="configDialog.form.provider === 'TENCENT'" label="应用 ID" prop="sdkAppId"><el-input v-model="configDialog.form.sdkAppId" maxlength="64" /></el-form-item>
          <el-form-item v-if="configDialog.form.provider === 'TENCENT'" label="地域" prop="region"><el-input v-model="configDialog.form.region" maxlength="64" placeholder="ap-guangzhou" /></el-form-item>
        </div>
        <el-form-item label="备注" prop="remark"><el-input v-model="configDialog.form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="configDialog.visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="sendDialog.visible" title="测试发送" width="600px" append-to-body>
      <el-form ref="sendFormRef" :model="sendDialog.form" :rules="sendRules" label-width="96px">
        <el-form-item label="手机号" prop="phones"><el-input v-model="sendDialog.form.phones" maxlength="400" /></el-form-item>
        <el-form-item label="模板 ID" prop="templateId"><el-input v-model="sendDialog.form.templateId" maxlength="64" /></el-form-item>
        <el-form-item label="模板参数">
          <div class="parameter-list">
            <div v-for="(item, index) in sendDialog.form.parameters" :key="index" class="parameter-list__row">
              <el-input v-model="item.key" placeholder="变量名或序号" maxlength="64" />
              <el-input v-model="item.value" placeholder="参数值" maxlength="128" />
              <el-tooltip content="删除参数" placement="top"><el-button circle text icon="Delete" aria-label="删除参数" @click="removeParameter(index)" /></el-tooltip>
            </div>
            <el-button text type="primary" icon="Plus" @click="addParameter">添加参数</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="sendDialog.visible = false">取消</el-button><el-button type="primary" :loading="sending" @click="submitSend">发送</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="SystemSms">
import { activateSmsConfig, addSmsConfig, deleteSmsConfig, listSmsConfigs, listSmsLogs, sendSmsTest, updateSmsConfig } from '@/api/system/sms'

const { proxy } = getCurrentInstance()
const activeTab = ref('configs')
const loading = ref(false)
const saving = ref(false)
const sending = ref(false)
const configs = ref([])
const logs = ref([])
const logTotal = ref(0)
const logQueryParams = reactive({ pageNum: 1, pageSize: 10 })
const configFormRef = ref(null)
const sendFormRef = ref(null)
const configDialog = reactive({ visible: false, form: emptyConfig() })
const sendDialog = reactive({ visible: false, form: emptySend() })
const configRules = {
  configName: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  accessKeyId: [{ required: true, message: '请输入 AccessKey ID', trigger: 'blur' }],
  accessKeySecret: [{ validator: validateSecret, trigger: 'blur' }],
  signName: [{ required: true, message: '请输入短信签名', trigger: 'blur' }],
  sdkAppId: [{ validator: validateTencentField, trigger: 'blur' }],
  region: [{ validator: validateTencentField, trigger: 'blur' }]
}
const sendRules = {
  phones: [{ required: true, message: '请输入手机号', trigger: 'blur' }],
  templateId: [{ required: true, message: '请输入模板 ID', trigger: 'blur' }]
}

/**
 * 创建一份不携带历史密钥的短信供应商配置默认值。
 * @returns {object} 可直接用于新增或编辑弹窗的独立表单对象。
 */
function emptyConfig() {
  return { configId: null, configName: '', provider: 'ALIYUN', accessKeyId: '', accessKeySecret: '', signName: '', sdkAppId: '', region: '', remark: '' }
}

/**
 * 创建短信测试发送表单，并保留一个初始模板参数行。
 * @returns {object} 可直接用于测试发送弹窗的独立表单对象。
 */
function emptySend() {
  return { phones: '', templateId: '', parameters: [{ key: '', value: '' }] }
}

/**
 * 校验新增配置必须提交 AccessKey Secret，编辑配置允许留空保留原密钥。
 * @param {unknown} rule Element Plus 校验规则。
 * @param {string} value 当前 AccessKey Secret 输入值。
 * @param {Function} callback Element Plus 校验结果回调。
 * @returns {void} 通过或拒绝当前字段校验。
 */
function validateSecret(rule, value, callback) {
  configDialog.form.configId || value ? callback() : callback(new Error('请输入 AccessKey Secret'))
}

/**
 * 仅在腾讯云供应商模式下校验 SDK AppId 和地域等专属字段。
 * @param {unknown} rule Element Plus 校验规则。
 * @param {string} value 当前腾讯云专属字段值。
 * @param {Function} callback Element Plus 校验结果回调。
 * @returns {void} 通过或拒绝当前字段校验。
 */
function validateTencentField(rule, value, callback) {
  configDialog.form.provider !== 'TENCENT' || value ? callback() : callback(new Error('腾讯云配置不能为空'))
}

/**
 * 按当前页签从正式 API 查询短信配置或发送审计日志。
 * @returns {Promise<void>} 查询结束后更新页面数据并解除加载状态。
 */
async function loadActiveTab() {
  loading.value = true
  try {
    if (activeTab.value === 'configs') configs.value = (await listSmsConfigs()).data || []
    else await loadLogs()
  } finally {
    loading.value = false
  }
}

/**
 * 按当前页码查询短信发送审计并同步总记录数。
 * @returns {Promise<void>} 查询完成后更新日志列表和分页总数。
 */
async function loadLogs() {
  const response = await listSmsLogs(logQueryParams)
  logs.value = response.rows || []
  logTotal.value = response.total || 0
}

/**
 * 打开短信配置新增或编辑弹窗，编辑时主动清空服务端脱敏密钥占位。
 * @param {object|null} row 已有配置行，新增时为空。
 * @returns {void} 初始化独立表单并清除上一次校验结果。
 */
function openConfig(row) {
  configDialog.form = row ? { ...emptyConfig(), ...row, accessKeySecret: '' } : emptyConfig()
  configDialog.visible = true
  nextTick(() => configFormRef.value?.clearValidate())
}

/**
 * 校验并通过正式 API 新增或更新短信供应商配置。
 * @returns {Promise<void>} 校验失败时停留在弹窗；保存成功后刷新配置列表。
 */
async function saveConfig() {
  // valid 表示整张供应商配置表单是否通过校验，用户输错时不产生未处理 Promise。
  const valid = await configFormRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    // api 是依据 configId 选择出的正式新增或更新接口。
    const api = configDialog.form.configId ? updateSmsConfig : addSmsConfig
    await api({ ...configDialog.form })
    proxy.$modal.msgSuccess('保存成功')
    configDialog.visible = false
    await loadActiveTab()
  } finally {
    saving.value = false
  }
}

/**
 * 经用户确认后启用指定短信配置，并由服务端保证唯一启用约束。
 * @param {object} row 待启用的短信供应商配置行。
 * @returns {Promise<void>} 启用成功后刷新配置列表。
 */
async function activate(row) {
  await proxy.$modal.confirm(`确认启用“${row.configName}”吗？`)
  await activateSmsConfig(row.configId)
  proxy.$modal.msgSuccess('已启用')
  await loadActiveTab()
}

/**
 * 经用户确认后删除停用的短信供应商配置。
 * @param {object} row 待删除的短信供应商配置行。
 * @returns {Promise<void>} 服务端删除成功后刷新配置列表。
 */
async function removeConfig(row) {
  await proxy.$modal.confirm(`确认删除“${row.configName}”吗？`)
  await deleteSmsConfig(row.configId)
  proxy.$modal.msgSuccess('删除成功')
  await loadActiveTab()
}

/**
 * 打开短信测试发送弹窗并重置上一次输入与校验状态。
 * @returns {void} 初始化测试发送表单并显示弹窗。
 */
function openSend() {
  sendDialog.form = emptySend()
  sendDialog.visible = true
  nextTick(() => sendFormRef.value?.clearValidate())
}

/**
 * 在供应商上限内向测试发送表单追加一个模板参数行。
 * @returns {void} 参数不足 20 个时追加空参数行。
 */
function addParameter() {
  if (sendDialog.form.parameters.length < 20) sendDialog.form.parameters.push({ key: '', value: '' })
}

/**
 * 从测试发送表单删除指定模板参数行。
 * @param {number} index 待删除参数在表单数组中的下标。
 * @returns {void} 原地更新模板参数数组。
 */
function removeParameter(index) {
  sendDialog.form.parameters.splice(index, 1)
}

/**
 * 校验测试发送表单，调用真实短信供应商并进入脱敏审计页签。
 * @returns {Promise<void>} 校验失败时保留弹窗；供应商请求结束后刷新正式审计日志。
 */
async function submitSend() {
  // valid 表示测试发送表单是否通过校验，用户输错时不产生未处理 Promise。
  const valid = await sendFormRef.value.validate().catch(() => false)
  if (!valid) return
  // parameters 是去重后提交给后端的供应商模板参数映射。
  const parameters = {}
  for (const item of sendDialog.form.parameters) {
    if (!item.key) continue
    if (Object.hasOwn(parameters, item.key)) {
      proxy.$modal.msgError('模板参数名不能重复')
      return
    }
    parameters[item.key] = item.value || ''
  }
  sending.value = true
  try {
    const response = await sendSmsTest({ phones: sendDialog.form.phones, templateId: sendDialog.form.templateId, parameters })
    response.data?.success ? proxy.$modal.msgSuccess(`发送已受理，日志 ID ${response.data.logId}`) : proxy.$modal.msgError(response.data?.summary || '发送失败')
    sendDialog.visible = false
    activeTab.value = 'logs'
    await loadActiveTab()
  } finally {
    sending.value = false
  }
}

/**
 * 将短信供应商枚举映射为中文名称。
 * @param {string} provider 服务端供应商枚举。
 * @returns {string} 对应的中文供应商名称或原始未知值。
 */
function providerLabel(provider) {
  return provider === 'ALIYUN' ? '阿里云' : provider === 'TENCENT' ? '腾讯云' : provider || '-'
}

/**
 * 将短信审计状态映射为中文标签。
 * @param {string} status 服务端短信审计状态枚举。
 * @returns {string} 对应的中文状态或原始未知值。
 */
function logLabel(status) {
  return ({ PENDING: '处理中', DELIVERED: '已受理', FAILED: '失败' })[status] || status
}

/**
 * 将短信审计状态映射为 Element Plus 标签类型。
 * @param {string} status 服务端短信审计状态枚举。
 * @returns {string} 对应的标签类型。
 */
function logType(status) {
  return status === 'DELIVERED' ? 'success' : status === 'FAILED' ? 'danger' : 'warning'
}

onMounted(loadActiveTab)
</script>

<style scoped>
.channel-page__header { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--el-border-color-lighter); }
.channel-page__header h2 { margin: 0 0 4px; font-size: 18px; font-weight: 600; letter-spacing: 0; }
.channel-page__header span { color: var(--el-text-color-secondary); font-size: 12px; }
.channel-page__actions { display: flex; align-items: center; gap: 8px; }
.channel-page__form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.channel-page__form-grid :deep(.el-select) { width: 100%; }
.parameter-list { width: 100%; }
.parameter-list__row { display: grid; grid-template-columns: minmax(0, 0.8fr) minmax(0, 1.2fr) 32px; gap: 8px; margin-bottom: 8px; }
@media (max-width: 720px) { .channel-page__header { align-items: flex-start; flex-direction: column; padding-bottom: 12px; } .channel-page__form-grid { grid-template-columns: 1fr; } }
</style>
