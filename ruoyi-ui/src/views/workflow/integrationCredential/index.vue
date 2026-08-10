<template>
  <div class="app-container integration-page">
    <header class="page-heading">
      <div>
        <h2>集成账号</h2>
        <p>管理运行事件 Token 的范围、变量白名单、到期与限流</p>
      </div>
      <el-button type="primary" icon="Plus" v-hasPermi="['workflow:integrationCredential:add']" @click="openCreate">
        新增账号
      </el-button>
    </header>

    <el-form inline class="page-filter">
      <el-form-item label="检索">
        <el-input v-model="keyword" clearable prefix-icon="Search" placeholder="账号名称或 Token 前缀" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="statusFilter" clearable placeholder="全部状态">
          <el-option label="有效" value="ACTIVE" />
          <el-option label="已到期" value="EXPIRED" />
          <el-option label="已吊销" value="REVOKED" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button icon="Refresh" @click="loadRows">刷新</el-button></el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="filteredRows" row-key="credentialId">
      <el-table-column label="账号" min-width="210">
        <template #default="scope">
          <div class="identity-cell">
            <strong>{{ scope.row.credentialName }}</strong>
            <code>{{ scope.row.tokenPrefix }}••••</code>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="范围" min-width="210">
        <template #default="scope">
          <el-tag v-for="item in scope.row.scopes" :key="item" size="small" type="info" class="scope-tag">{{ item }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="变量白名单" min-width="220" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.allowedVariables?.join(', ') || '不允许变量' }}</template>
      </el-table-column>
      <el-table-column prop="rateLimitPerMinute" label="每分钟" width="90" align="center" />
      <el-table-column label="版本" width="76" align="center">
        <template #default="scope"><strong>R{{ scope.row.revisionNo }}</strong></template>
      </el-table-column>
      <el-table-column label="状态" width="94" align="center">
        <template #default="scope">
          <el-tag :type="statusMeta(scope.row).type">{{ statusMeta(scope.row).label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="到期时间" width="172">
        <template #default="scope">{{ scope.row.expiresAt ? parseTime(scope.row.expiresAt) : '长期有效' }}</template>
      </el-table-column>
      <el-table-column label="最近使用" width="172">
        <template #default="scope">{{ scope.row.lastUsedAt ? parseTime(scope.row.lastUsedAt) : '尚未使用' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="116" fixed="right" align="center">
        <template #default="scope">
          <el-tooltip content="轮换 Token">
            <el-button link type="primary" icon="RefreshRight" aria-label="轮换 Token" :disabled="statusMeta(scope.row).value !== 'ACTIVE'" v-hasPermi="['workflow:integrationCredential:rotate']" @click="rotateToken(scope.row)" />
          </el-tooltip>
          <el-tooltip content="吊销账号">
            <el-button link type="danger" icon="CircleClose" aria-label="吊销账号" :disabled="statusMeta(scope.row).value !== 'ACTIVE'" v-hasPermi="['workflow:integrationCredential:revoke']" @click="revokeToken(scope.row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createOpen" title="新增集成账号" width="620px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="108px">
        <el-form-item label="账号名称" prop="credentialName"><el-input v-model="form.credentialName" maxlength="128" /></el-form-item>
        <el-form-item label="事件范围" prop="scopes">
          <el-checkbox-group v-model="form.scopes">
            <el-checkbox v-for="scope in availableScopes" :key="scope" :value="scope">{{ scope }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="变量白名单" prop="allowedVariablesText">
          <el-input v-model="form.allowedVariablesText" type="textarea" :rows="3" maxlength="4096" placeholder="逗号分隔，例如 approved, amount" />
        </el-form-item>
        <el-form-item label="每分钟上限" prop="rateLimitPerMinute">
          <el-input-number v-model="form.rateLimitPerMinute" :min="1" :max="10000" controls-position="right" />
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker v-model="form.expiresAt" type="datetime" placeholder="留空表示长期有效" :disabled-date="disablePastDate" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建账号</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretOpen" title="保存集成 Token" width="640px" append-to-body :close-on-click-modal="false">
      <el-alert title="Token 仅显示这一次，关闭后无法再次查看" type="warning" :closable="false" show-icon />
      <div class="secret-value">
        <code>{{ oneTimeToken }}</code>
        <el-tooltip content="复制 Token">
          <el-button icon="CopyDocument" aria-label="复制 Token" @click="copyToken" />
        </el-tooltip>
      </div>
      <template #footer><el-button type="primary" @click="secretOpen = false">我已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowIntegrationCredential">
import {
  createIntegrationCredential,
  listIntegrationCredentials,
  revokeIntegrationCredential,
  rotateIntegrationCredential
} from '@/api/workflow/integrationCredential'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const statusFilter = ref('')
const createOpen = ref(false)
const secretOpen = ref(false)
const oneTimeToken = ref('')
const formRef = ref(null)
const availableScopes = Object.freeze(['MESSAGE', 'SIGNAL', 'RECEIVE'])
const form = reactive(emptyForm())
const rules = {
  credentialName: [{ required: true, message: '账号名称不能为空', trigger: 'blur' }],
  scopes: [{ type: 'array', required: true, min: 1, message: '至少选择一个事件范围', trigger: 'change' }],
  allowedVariablesText: [{ validator: validateVariables, trigger: 'blur' }],
  rateLimitPerMinute: [{ required: true, message: '每分钟上限不能为空', trigger: 'change' }]
}
const filteredRows = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => {
    const matchesQuery = !query || row.credentialName.toLowerCase().includes(query) || row.tokenPrefix.toLowerCase().includes(query)
    return matchesQuery && (!statusFilter.value || statusMeta(row).value === statusFilter.value)
  })
})
let initialized = false

/**
 * 创建集成账号表单默认值。
 * @returns {object} 不包含 Token 或哈希的可编辑请求模型。
 */
function emptyForm() {
  return { credentialName: '', scopes: ['MESSAGE'], allowedVariablesText: '', rateLimitPerMinute: 60, expiresAt: null }
}

/**
 * 从正式后端加载脱敏账号清单。
 * @returns {Promise<void>} 请求结束后刷新表格。
 */
async function loadRows() {
  loading.value = true
  try {
    const response = await listIntegrationCredentials()
    rows.value = Array.isArray(response.data) ? response.data : []
  } finally {
    loading.value = false
  }
}

/**
 * 根据吊销和到期时间计算当前账号状态。
 * @param {object} row 后端脱敏账号行。
 * @returns {{value:string,label:string,type:string}} 表格筛选和标签共用的状态元数据。
 */
function statusMeta(row) {
  if (row.revokedAt) return { value: 'REVOKED', label: '已吊销', type: 'danger' }
  if (row.expiresAt && new Date(row.expiresAt).getTime() <= Date.now()) return { value: 'EXPIRED', label: '已到期', type: 'warning' }
  return { value: 'ACTIVE', label: '有效', type: 'success' }
}

/**
 * 打开创建窗口并清除上一轮敏感状态。
 * @returns {void} 表单和校验恢复默认值。
 */
function openCreate() {
  Object.assign(form, emptyForm())
  createOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 校验逗号分隔变量名不重复且符合服务端白名单格式。
 * @param {object} rule Element Plus 校验规则。
 * @param {string} value 用户输入的变量文本。
 * @param {Function} callback 校验完成回调。
 * @returns {void} 非法时通过 callback 返回错误。
 */
function validateVariables(rule, value, callback) {
  const variables = parseVariables(value)
  if (variables.length > 128 || variables.some(item => !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(item)) || new Set(variables).size !== variables.length) {
    callback(new Error('变量名必须唯一，并使用英文、数字或下划线'))
    return
  }
  callback()
}

/**
 * 把逗号或换行分隔文本转换为去空白的变量数组。
 * @param {string} value 变量白名单文本。
 * @returns {string[]} 保持输入顺序的非空变量名。
 */
function parseVariables(value) {
  return String(value || '').split(/[,\n]/).map(item => item.trim()).filter(Boolean)
}

/**
 * 创建正式账号并立即展示只返回一次的 Token。
 * @returns {Promise<void>} 创建成功后刷新数据库清单。
 */
async function submitCreate() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const response = await createIntegrationCredential({
      credentialName: form.credentialName.trim(),
      scopes: [...form.scopes],
      allowedVariables: parseVariables(form.allowedVariablesText),
      rateLimitPerMinute: form.rateLimitPerMinute,
      expiresAt: form.expiresAt ? form.expiresAt.toISOString() : null
    })
    oneTimeToken.value = response.data.token
    createOpen.value = false
    secretOpen.value = true
    await loadRows()
  } finally {
    saving.value = false
  }
}

/**
 * 经确认后原子轮换 Token，并展示一次性新正文。
 * @param {object} row 当前有效账号行。
 * @returns {Promise<void>} 轮换成功后刷新修订号和时间。
 */
async function rotateToken(row) {
  await proxy.$modal.confirm(`确认轮换“${row.credentialName}”的 Token 吗？旧 Token 将立即失效。`)
  const response = await rotateIntegrationCredential(row.credentialId, { expiresAt: null })
  oneTimeToken.value = response.data.token
  secretOpen.value = true
  await loadRows()
}

/**
 * 经确认后永久吊销账号，历史审计记录继续保留。
 * @param {object} row 当前有效账号行。
 * @returns {Promise<void>} 吊销成功后刷新正式状态。
 */
async function revokeToken(row) {
  await proxy.$modal.confirm(`确认永久吊销“${row.credentialName}”吗？该操作不能撤销。`)
  await revokeIntegrationCredential(row.credentialId)
  proxy.$modal.msgSuccess('集成账号已吊销')
  await loadRows()
}

/**
 * 将一次性 Token 写入系统剪贴板。
 * @returns {Promise<void>} 复制失败时由页面给出明确提示。
 */
async function copyToken() {
  if (!navigator.clipboard?.writeText) {
    proxy.$modal.msgError('当前浏览器不支持安全剪贴板')
    return
  }
  await navigator.clipboard.writeText(oneTimeToken.value)
  proxy.$modal.msgSuccess('Token 已复制')
}

/**
 * 禁止选择今天以前的到期日期，精确一分钟门禁仍由服务端执行。
 * @param {Date} date 日期面板候选值。
 * @returns {boolean} 早于今天时返回 true。
 */
function disablePastDate(date) {
  return date.getTime() < new Date().setHours(0, 0, 0, 0)
}

onMounted(async () => { await loadRows(); initialized = true })
onActivated(() => { if (initialized) loadRows() })
</script>

<style scoped>
.integration-page { color: var(--el-text-color-primary); }
.page-heading { display: flex; align-items: flex-end; justify-content: space-between; min-height: 68px; margin: -4px 0 20px; padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-light); }
.page-heading h2 { margin: 0; font-size: 22px; letter-spacing: 0; }
.page-heading p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.page-filter :deep(.el-input) { width: 240px; }
.page-filter :deep(.el-select) { width: 150px; }
.identity-cell { display: grid; gap: 4px; }
.identity-cell code { color: var(--el-text-color-secondary); font-family: Consolas, monospace; font-size: 12px; }
.scope-tag { margin: 2px 4px 2px 0; }
.integration-page :deep(.el-input-number), .integration-page :deep(.el-date-editor) { width: 100%; }
.secret-value { display: grid; grid-template-columns: minmax(0, 1fr) 40px; gap: 10px; align-items: center; margin-top: 18px; }
.secret-value code { overflow-wrap: anywhere; padding: 14px; border: 1px solid var(--el-border-color); background: var(--el-fill-color-light); font-family: Consolas, monospace; font-size: 14px; line-height: 1.6; }
@media (max-width: 900px) { .page-heading { align-items: flex-start; gap: 14px; } }
</style>
