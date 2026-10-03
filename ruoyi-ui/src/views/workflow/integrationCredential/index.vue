<template>
  <div class="app-container integration-page">
    <header class="page-heading">
      <div>
        <h2>集成账号</h2>
        <p>管理运行事件 Token 的范围、变量白名单、到期与限流</p>
      </div>
      <el-button type="primary" icon="Plus" :disabled="tokenBlocked" v-hasPermi="['workflow:integrationCredential:add']" @click="openCreate">
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
          <el-tag :type="statusMeta(scope.row.status).type">{{ statusMeta(scope.row.status).label }}</el-tag>
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
            <el-button link type="primary" icon="RefreshRight" aria-label="轮换 Token" :disabled="tokenBlocked || scope.row.rotateAllowed !== true" v-hasPermi="['workflow:integrationCredential:rotate']" @click="rotateToken(scope.row)" />
          </el-tooltip>
          <el-tooltip content="吊销账号">
            <el-button link type="danger" icon="CircleClose" aria-label="吊销账号" :disabled="tokenBlocked || scope.row.revokeAllowed !== true" v-hasPermi="['workflow:integrationCredential:revoke']" @click="revokeToken(scope.row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createOpen" title="新增集成账号" width="620px" append-to-body :close-on-click-modal="!tokenWriting" :close-on-press-escape="!tokenWriting" :show-close="!tokenWriting">
      <el-form ref="formRef" v-loading="createLoading" :model="form" :rules="rules" label-width="108px" :disabled="!createOptionsReady || tokenBlocked">
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
        <el-form-item label="绑定连接端点" v-if="canBindConnector">
          <el-select v-model="bindEndpointId" clearable filterable placeholder="可选：新令牌直接绑定到端点">
            <el-option v-for="endpoint in endpoints" :key="endpoint.endpointId" :value="endpoint.endpointId" :label="endpoint.endpointName" :disabled="endpoint.status !== 'ENABLED'" />
          </el-select>
          <p v-if="bindEndpointId">创建与绑定同时完成；令牌由服务器保管，绑定失败不会创建账号。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="tokenWriting" @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!createOptionsReady || tokenBlocked" @click="submitCreate">创建账号</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretOpen" title="保存集成 Token" width="640px" append-to-body :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false">
      <el-alert title="Token 仅显示这一次，关闭后无法再次查看" type="warning" :closable="false" show-icon />
      <p>账号：{{ tokenAccount.credentialName }}（ID：{{ tokenAccount.credentialId }}）</p>
      <div class="secret-value">
        <code>{{ oneTimeToken }}</code>
        <el-tooltip content="复制 Token">
          <el-button icon="CopyDocument" aria-label="复制 Token" @click="copyToken" />
        </el-tooltip>
      </div>
      <template #footer><el-button type="primary" @click="acknowledgeToken">我已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowIntegrationCredential">
import { useRequestSequence } from '@/utils/requestSequence'
const createRequest = useRequestSequence()
const createSubmissionRequest = useRequestSequence()
const rotationRequest = useRequestSequence()
const listRequest = useRequestSequence()
import {
  createIntegrationCredential,
  listIntegrationCredentials,
  revokeIntegrationCredential,
  rotateIntegrationCredential
} from '@/api/workflow/integrationCredential'
import { listConnectorEndpoints, createAndBindIntegrationCredential, getConnectorCredential } from '@/api/workflow/connector'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const statusFilter = ref('')
const createOpen = ref(false)
const createLoading = ref(false)
const createOptionsReady = ref(false)
const secretOpen = ref(false)
const oneTimeToken = ref('')
const tokenAccount = ref({ credentialId: '', credentialName: '' })
const tokenBusy = ref(false)
const tokenWriting = ref(false)
const tokenBlocked = computed(() => tokenBusy.value || secretOpen.value)
const endpoints = ref([])
const bindEndpointId = ref(null)
const canBindConnector = computed(() => proxy.$auth.hasPermi('workflow:connector:edit') && proxy.$auth.hasPermi('workflow:connector:list'))
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
    return matchesQuery && (!statusFilter.value || row.status === statusFilter.value)
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
  const isCurrent = listRequest.begin()
  loading.value = true
  try {
    const response = await listIntegrationCredentials({ isCurrentRequest: isCurrent })
    if (!isCurrent()) return
    rows.value = response.data
  } catch {
    // The HTTP client reports current failures; superseded requests cannot change this view.
  } finally {
    if (isCurrent()) loading.value = false
  }
}

/**
 * 将后端权威状态映射为表格标签展示元数据。
 * @param {string} status 后端返回的账号状态。
 * @returns {{label:string,type:string}} 状态对应的中文标签和颜色。
 */
function statusMeta(status) {
  if (status === 'ACTIVE') return { label: '有效', type: 'success' }
  if (status === 'EXPIRED') return { label: '已到期', type: 'warning' }
  if (status === 'REVOKED') return { label: '已吊销', type: 'danger' }
}

/**
 * 打开创建窗口并清除上一轮敏感状态。
 * @returns {void} 表单和校验恢复默认值。
 */
async function openCreate() {
  const isCurrent = createRequest.begin()
  Object.assign(form, emptyForm())
  bindEndpointId.value = null
  endpoints.value = []
  createLoading.value = canBindConnector.value
  createOptionsReady.value = !canBindConnector.value
  createOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
  if (!canBindConnector.value) return
  try {
    const response = await listConnectorEndpoints({ isCurrentRequest: isCurrent })
    if (isCurrent()) {
      endpoints.value = response.data || []
      createOptionsReady.value = true
    }
  } catch {
    // Current endpoint failures are displayed by the HTTP client.
  } finally {
    if (isCurrent()) createLoading.value = false
  }
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
  if (!createOpen.value || !createOptionsReady.value || tokenBlocked.value) return
  const isCurrent = createSubmissionRequest.begin()
  const endpointId = bindEndpointId.value
  const credential = {
    credentialName: form.credentialName.trim(),
    scopes: [...form.scopes],
    allowedVariables: parseVariables(form.allowedVariablesText),
    rateLimitPerMinute: form.rateLimitPerMinute,
    expiresAt: form.expiresAt ? form.expiresAt.toISOString() : null
  }
  tokenBusy.value = true
  saving.value = true
  try {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid || !isCurrent()) return
    if (endpointId) {
      const state = (await getConnectorCredential(endpointId, { isCurrentRequest: isCurrent })).data
      if (!isCurrent()) return
      tokenWriting.value = true
      await createAndBindIntegrationCredential(endpointId, {
        endpointRevision: state.endpointRevision, credentialRevision: state.credential.revision, credential
      })
      proxy.$modal.msgSuccess('集成账号已创建，令牌已绑定到连接端点')
    } else {
      tokenWriting.value = true
      const response = await createIntegrationCredential(credential)
      showToken(response.data, credential)
    }
    if (isCurrent()) createOpen.value = false
    await loadRows()
  } finally {
    tokenWriting.value = false
    tokenBusy.value = false
    saving.value = false
  }
}

/**
 * 经确认后原子轮换 Token，并展示一次性新正文。
 * @param {object} row 当前账号行。
 * @returns {Promise<void>} 轮换成功后刷新修订号和时间。
 */
async function rotateToken(row) {
  if (row.rotateAllowed !== true || tokenBlocked.value) return
  const target = { credentialId: row.credentialId, credentialName: row.credentialName }
  const isCurrent = rotationRequest.begin()
  tokenBusy.value = true
  try {
    try {
      await proxy.$modal.confirm(`确认轮换“${target.credentialName}”的 Token 吗？旧 Token 将立即失效。`)
    } catch { return }
    if (!isCurrent() || row.rotateAllowed !== true) return
    tokenWriting.value = true
    const response = await rotateIntegrationCredential(target.credentialId, { expiresAt: null })
    showToken(response.data, target)
    await loadRows()
  } finally {
    tokenWriting.value = false
    tokenBusy.value = false
  }
}

function showToken(result, target) {
  tokenAccount.value = { credentialId: result.credentialId, credentialName: result.credential?.credentialName || target.credentialName }
  oneTimeToken.value = result.token
  secretOpen.value = true
}

function acknowledgeToken() {
  oneTimeToken.value = ''
  tokenAccount.value = { credentialId: '', credentialName: '' }
  secretOpen.value = false
}

/**
 * 经确认后永久吊销账号，历史审计记录继续保留。
 * @param {object} row 当前账号行。
 * @returns {Promise<void>} 吊销成功后刷新正式状态。
 */
async function revokeToken(row) {
  if (row.revokeAllowed !== true || tokenBlocked.value) return
  await proxy.$modal.confirm(`确认永久吊销“${row.credentialName}”吗？该操作不能撤销。`)
  if (row.revokeAllowed !== true || tokenBlocked.value) return
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

function warnBeforeUnload(event) {
  if (!tokenWriting.value && !secretOpen.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => {
  if (tokenWriting.value || secretOpen.value) {
    proxy.$modal.msgWarning('请先等待 Token 操作完成并保存 Token，再离开此页面')
    return false
  }
  createSubmissionRequest.invalidate()
  rotationRequest.invalidate()
  return true
})

onMounted(() => window.addEventListener('beforeunload', warnBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', warnBeforeUnload))

onMounted(async () => { await loadRows(); initialized = true })
onActivated(() => { if (initialized) loadRows() })
watch(createOpen, value => {
  if (!value) {
    createRequest.invalidate()
    createLoading.value = false
    createOptionsReady.value = false
  }
}, { flush: 'sync' })
watch([createOpen, bindEndpointId], () => createSubmissionRequest.invalidate(), { flush: 'sync' })
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
