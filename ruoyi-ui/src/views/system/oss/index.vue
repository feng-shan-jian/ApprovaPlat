<template>
  <div class="app-container storage-page">
    <header class="storage-page__header">
      <div><h2>对象存储</h2><span>S3 兼容配置与对象台账</span></div>
      <div class="storage-page__actions">
        <el-tooltip content="刷新" placement="top"><el-button circle text icon="Refresh" aria-label="刷新" :loading="loading" @click="loadActiveTab" /></el-tooltip>
        <el-button v-if="activeTab === 'configs'" v-hasPermi="['system:oss:add']" type="primary" icon="Plus" @click="openConfig()">新增配置</el-button>
        <el-upload v-else v-hasPermi="['system:oss:upload']" :show-file-list="false" :http-request="uploadObject" :before-upload="beforeUpload" :disabled="uploading">
          <el-button type="primary" icon="Upload" :loading="uploading">上传对象</el-button>
        </el-upload>
      </div>
    </header>

    <el-tabs v-model="activeTab" @tab-change="loadActiveTab">
      <el-tab-pane label="存储配置" name="configs">
        <el-table v-loading="loading" :data="configs" row-key="configId">
          <el-table-column prop="configName" label="配置名称" min-width="140" />
          <el-table-column prop="endpoint" label="Endpoint" min-width="220" show-overflow-tooltip />
          <el-table-column prop="bucketName" label="Bucket" min-width="150" show-overflow-tooltip />
          <el-table-column prop="region" label="地域" width="120" />
          <el-table-column label="策略" width="100"><template #default="{ row }"><el-tag size="small" effect="plain" :type="row.accessPolicy === 'PUBLIC' ? 'warning' : undefined">{{ row.accessPolicy === 'PUBLIC' ? '公开' : '私有' }}</el-tag></template></el-table-column>
          <el-table-column label="密钥" width="90"><template #default="{ row }"><el-tag :type="row.secretConfigured ? 'success' : 'danger'" size="small">{{ row.secretConfigured ? '已配置' : '缺失' }}</el-tag></template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === '0' ? 'success' : 'info'" size="small">{{ row.status === '0' ? '启用' : '停用' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="210" align="center" fixed="right">
            <template #default="{ row }">
              <el-tooltip content="测试连接" placement="top"><el-button v-hasPermi="['system:oss:test']" circle text icon="Connection" aria-label="测试连接" @click="testConnection(row)" /></el-tooltip>
              <el-tooltip content="编辑" placement="top"><el-button v-hasPermi="['system:oss:edit']" circle text icon="Edit" aria-label="编辑" @click="openConfig(row)" /></el-tooltip>
              <el-tooltip v-if="row.status !== '0'" content="启用" placement="top"><el-button v-hasPermi="['system:oss:edit']" circle text type="success" icon="CircleCheck" aria-label="启用" @click="activate(row)" /></el-tooltip>
              <el-tooltip v-if="row.status !== '0'" content="删除" placement="top"><el-button v-hasPermi="['system:oss:remove']" circle text type="danger" icon="Delete" aria-label="删除" @click="removeConfig(row)" /></el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="对象台账" name="objects">
        <el-table v-loading="loading" :data="objects" row-key="objectId">
          <el-table-column prop="objectId" label="ID" width="86" />
          <el-table-column prop="originalName" label="文件名" min-width="210" show-overflow-tooltip />
          <el-table-column prop="contentType" label="类型" min-width="150" show-overflow-tooltip />
          <el-table-column label="大小" width="110"><template #default="{ row }">{{ formatSize(row.fileSize) }}</template></el-table-column>
          <el-table-column prop="sha256" label="SHA-256" min-width="190" show-overflow-tooltip />
          <el-table-column label="策略" width="90"><template #default="{ row }">{{ row.accessPolicy === 'PUBLIC' ? '公开' : '私有' }}</template></el-table-column>
          <el-table-column label="状态" width="128"><template #default="{ row }"><el-tag size="small" :type="objectStatusType(row.status)">{{ objectStatusLabel(row.status) }}</el-tag></template></el-table-column>
          <el-table-column prop="lastError" label="删除失败" min-width="150" show-overflow-tooltip />
          <el-table-column prop="createTime" label="上传时间" width="168" />
          <el-table-column label="操作" width="112" align="center" fixed="right">
            <template #default="{ row }">
              <el-tooltip v-if="row.status === 'ACTIVE'" content="下载" placement="top"><el-button v-hasPermi="['system:oss:download']" circle text icon="Download" aria-label="下载" @click="downloadObject(row)" /></el-tooltip>
              <el-tooltip v-if="['ACTIVE','DELETE_PENDING','DELETE_FAILED'].includes(row.status)" :content="row.status === 'ACTIVE' ? '删除' : '重试删除'" placement="top"><el-button v-hasPermi="['system:oss:remove']" circle text type="danger" icon="Delete" aria-label="删除" @click="removeObject(row)" /></el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialog.visible" :title="dialog.form.configId ? '编辑 OSS 配置' : '新增 OSS 配置'" width="720px" append-to-body>
      <el-form ref="formRef" :model="dialog.form" :rules="rules" label-width="112px">
        <div class="storage-page__form-grid">
          <el-form-item label="配置名称" prop="configName"><el-input v-model="dialog.form.configName" maxlength="64" /></el-form-item>
          <el-form-item label="Endpoint" prop="endpoint"><el-input v-model="dialog.form.endpoint" maxlength="255" placeholder="https://s3.example.com" /></el-form-item>
          <el-form-item label="地域" prop="region"><el-input v-model="dialog.form.region" maxlength="64" placeholder="us-east-1" /></el-form-item>
          <el-form-item label="Bucket" prop="bucketName"><el-input v-model="dialog.form.bucketName" maxlength="128" /></el-form-item>
          <el-form-item label="AccessKey" prop="accessKey"><el-input v-model="dialog.form.accessKey" maxlength="128" autocomplete="off" /></el-form-item>
          <el-form-item label="SecretKey" prop="secretKey"><el-input v-model="dialog.form.secretKey" type="password" show-password maxlength="256" autocomplete="new-password" :placeholder="dialog.form.configId ? '留空保留原密钥' : ''" /></el-form-item>
          <el-form-item label="对象前缀" prop="prefix"><el-input v-model="dialog.form.prefix" maxlength="128" /></el-form-item>
          <el-form-item label="寻址模式" prop="pathStyle"><el-segmented v-model="dialog.form.pathStyle" :options="pathOptions" /></el-form-item>
          <el-form-item label="访问策略" prop="accessPolicy"><el-segmented v-model="dialog.form.accessPolicy" :options="policyOptions" /></el-form-item>
          <el-form-item v-if="dialog.form.accessPolicy === 'PUBLIC'" label="公开域名" prop="domain"><el-input v-model="dialog.form.domain" maxlength="255" placeholder="https://files.example.com" /></el-form-item>
        </div>
        <el-form-item label="备注" prop="remark"><el-input v-model="dialog.form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog.visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="SystemOss">
import { saveAs } from 'file-saver'
import { activateOssConfig, addOssConfig, deleteOssConfig, deleteOssObject, downloadOssObject, listOssConfigs, listOssObjects, testOssConfig, updateOssConfig, uploadOssObject } from '@/api/system/oss'

const { proxy } = getCurrentInstance()
const activeTab = ref('configs')
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)
const configs = ref([])
const objects = ref([])
const formRef = ref(null)
const dialog = reactive({ visible: false, form: emptyConfig() })
const pathOptions = [{ label: '路径风格', value: 'Y' }, { label: '虚拟主机', value: 'N' }]
const policyOptions = [{ label: '私有', value: 'PRIVATE' }, { label: '公开', value: 'PUBLIC' }]
const rules = {
  configName: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
  endpoint: [{ required: true, message: '请输入 Endpoint', trigger: 'blur' }],
  region: [{ required: true, message: '请输入地域', trigger: 'blur' }],
  bucketName: [{ required: true, message: '请输入 Bucket', trigger: 'blur' }],
  accessKey: [{ required: true, message: '请输入 AccessKey', trigger: 'blur' }],
  secretKey: [{ validator: validateSecret, trigger: 'blur' }],
  pathStyle: [{ required: true, message: '请选择寻址模式', trigger: 'change' }],
  accessPolicy: [{ required: true, message: '请选择访问策略', trigger: 'change' }],
  domain: [{ validator: validateDomain, trigger: 'blur' }]
}

/**
 * 创建一份不携带历史密钥的 OSS 配置表单默认值。
 * @returns {object} 可直接用于新增或编辑弹窗的独立表单对象。
 */
function emptyConfig() {
  return { configId: null, configName: '', endpoint: '', region: 'us-east-1', bucketName: '', accessKey: '', secretKey: '', domain: '', prefix: '', pathStyle: 'Y', accessPolicy: 'PRIVATE', remark: '' }
}

/**
 * 校验新增配置必须提交 SecretKey，编辑配置允许留空以保留原密钥。
 * @param {unknown} rule Element Plus 校验规则。
 * @param {string} value 当前 SecretKey 输入值。
 * @param {Function} callback Element Plus 校验结果回调。
 * @returns {void} 通过或拒绝当前字段校验。
 */
function validateSecret(rule, value, callback) {
  dialog.form.configId || value ? callback() : callback(new Error('请输入 SecretKey'))
}

/**
 * 校验公开访问策略必须配置可回显对象的公开域名。
 * @param {unknown} rule Element Plus 校验规则。
 * @param {string} value 当前公开域名输入值。
 * @param {Function} callback Element Plus 校验结果回调。
 * @returns {void} 通过或拒绝当前字段校验。
 */
function validateDomain(rule, value, callback) {
  dialog.form.accessPolicy !== 'PUBLIC' || value ? callback() : callback(new Error('公开策略必须配置域名'))
}

/**
 * 按当前页签从正式 API 查询 OSS 配置或对象台账。
 * @returns {Promise<void>} 查询结束后更新页面数据并解除加载状态。
 */
async function loadActiveTab() {
  loading.value = true
  try {
    if (activeTab.value === 'configs') configs.value = (await listOssConfigs()).data || []
    else objects.value = (await listOssObjects()).data || []
  } finally {
    loading.value = false
  }
}

/**
 * 打开 OSS 新增或编辑弹窗，编辑时主动清空服务端脱敏密钥占位。
 * @param {object|null} row 已有配置行，新增时为空。
 * @returns {void} 初始化独立表单并清除上一次校验结果。
 */
function openConfig(row) {
  dialog.form = row ? { ...emptyConfig(), ...row, secretKey: '' } : emptyConfig()
  dialog.visible = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 校验并通过正式 API 新增或更新 OSS 配置。
 * @returns {Promise<void>} 校验失败时停留在弹窗；保存成功后刷新配置列表。
 */
async function saveConfig() {
  // valid 表示整张配置表单是否通过校验，用户输错时不产生未处理 Promise。
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    // api 是依据 configId 选择出的正式新增或更新接口。
    const api = dialog.form.configId ? updateOssConfig : addOssConfig
    await api({ ...dialog.form })
    proxy.$modal.msgSuccess('保存成功')
    dialog.visible = false
    await loadActiveTab()
  } finally {
    saving.value = false
  }
}

/**
 * 调用服务端 HeadBucket 能力测试指定 OSS 配置的真实连通性。
 * @param {object} row 待测试的 OSS 配置行。
 * @returns {Promise<void>} 展示服务端返回的连接结果。
 */
async function testConnection(row) {
  const response = await testOssConfig(row.configId)
  response.data?.success ? proxy.$modal.msgSuccess('存储桶连接正常') : proxy.$modal.msgError(response.data?.message || '连接失败')
}

/**
 * 经用户确认后启用指定 OSS 配置，并由服务端保证唯一启用约束。
 * @param {object} row 待启用的 OSS 配置行。
 * @returns {Promise<void>} 启用成功后刷新配置列表。
 */
async function activate(row) {
  await proxy.$modal.confirm(`确认启用“${row.configName}”吗？`)
  await activateOssConfig(row.configId)
  proxy.$modal.msgSuccess('已启用')
  await loadActiveTab()
}

/**
 * 经用户确认后删除未被对象引用的停用 OSS 配置。
 * @param {object} row 待删除的 OSS 配置行。
 * @returns {Promise<void>} 服务端删除成功后刷新配置列表。
 */
async function removeConfig(row) {
  await proxy.$modal.confirm(`确认删除“${row.configName}”吗？`)
  await deleteOssConfig(row.configId)
  proxy.$modal.msgSuccess('删除成功')
  await loadActiveTab()
}

/**
 * 在上传请求发出前限制单个对象大小。
 * @param {File} file 浏览器选择的文件对象。
 * @returns {boolean} 文件不超过 50 MiB 时允许上传。
 */
function beforeUpload(file) {
  if (file.size > 50 * 1024 * 1024) {
    proxy.$modal.msgError('文件不能超过 50 MiB')
    return false
  }
  return true
}

/**
 * 将浏览器文件交给正式上传 API，并在成功后刷新对象台账。
 * @param {{file: File}} options Element Plus 提供的上传参数。
 * @returns {Promise<void>} 上传结束后恢复按钮状态。
 */
async function uploadObject(options) {
  uploading.value = true
  try {
    // data 是提交给 multipart/form-data 接口的正式文件载荷。
    const data = new FormData()
    data.append('file', options.file)
    await uploadOssObject(data)
    proxy.$modal.msgSuccess('上传成功')
    await loadActiveTab()
  } finally {
    uploading.value = false
  }
}

/**
 * 通过鉴权下载接口取得私有对象流并保存为原始文件名。
 * @param {object} row 待下载的对象台账行。
 * @returns {Promise<void>} 下载完成后触发浏览器保存。
 */
async function downloadObject(row) {
  // blob 是后端鉴权通过后返回的真实对象二进制内容。
  const blob = await downloadOssObject(row.objectId)
  saveAs(blob, row.originalName || `object-${row.objectId}`)
}

/**
 * 经用户确认后删除对象，失败状态下复用同一入口触发服务端重试。
 * @param {object} row 待删除或重试删除的对象台账行。
 * @returns {Promise<void>} 服务端完成状态流转后刷新对象台账。
 */
async function removeObject(row) {
  await proxy.$modal.confirm(`确认删除“${row.originalName}”吗？`)
  await deleteOssObject(row.objectId)
  proxy.$modal.msgSuccess('删除成功')
  await loadActiveTab()
}

/**
 * 将对象字节数格式化为适合表格展示的容量。
 * @param {number|string} bytes 对象字节数。
 * @returns {string} 使用 B、KiB 或 MiB 表示的容量文本。
 */
function formatSize(bytes) {
  const value = Number(bytes || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`
  return `${(value / 1024 / 1024).toFixed(1)} MiB`
}

/**
 * 将对象生命周期状态映射为中文标签。
 * @param {string} status 服务端对象状态枚举。
 * @returns {string} 对应的中文状态或原始未知值。
 */
function objectStatusLabel(status) {
  return ({ ACTIVE: '可用', DELETE_PENDING: '删除中', DELETE_FAILED: '删除失败', DELETED: '已删除' })[status] || status
}

/**
 * 将对象生命周期状态映射为 Element Plus 标签类型。
 * @param {string} status 服务端对象状态枚举。
 * @returns {string} 对应的标签类型。
 */
function objectStatusType(status) {
  return status === 'ACTIVE' ? 'success' : status === 'DELETE_FAILED' ? 'danger' : status === 'DELETE_PENDING' ? 'warning' : 'info'
}

onMounted(loadActiveTab)
</script>

<style scoped>
.storage-page__header { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--el-border-color-lighter); }
.storage-page__header h2 { margin: 0 0 4px; font-size: 18px; font-weight: 600; letter-spacing: 0; }
.storage-page__header span { color: var(--el-text-color-secondary); font-size: 12px; }
.storage-page__actions { display: flex; align-items: center; gap: 8px; }
.storage-page__form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.storage-page__form-grid :deep(.el-segmented), .storage-page__form-grid :deep(.el-select) { width: 100%; }
@media (max-width: 760px) { .storage-page__header { align-items: flex-start; flex-direction: column; padding-bottom: 12px; } .storage-page__form-grid { grid-template-columns: 1fr; } }
</style>
