<template>
  <div class="app-container connector-endpoints">
    <header class="connector-heading">
      <div>
        <h2>连接端点</h2>
        <p>HTTP 白名单与不可回退修订</p>
      </div>
      <el-button type="primary" icon="Plus" v-hasPermi="['workflow:connector:add']" @click="openCreate">
        新增端点
      </el-button>
    </header>

    <el-form inline class="connector-filter">
      <el-form-item label="检索">
        <el-input v-model="keyword" clearable prefix-icon="Search" placeholder="名称或稳定键" />
      </el-form-item>
      <el-form-item>
        <el-button icon="Refresh" @click="loadEndpoints">刷新</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="filteredRows" row-key="endpointId">
      <el-table-column label="端点" min-width="220">
        <template #default="scope">
          <div class="endpoint-identity">
            <strong>{{ scope.row.endpointName }}</strong>
            <code>{{ scope.row.endpointKey }}</code>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="基础 URL" prop="baseUrl" min-width="250" show-overflow-tooltip />
      <el-table-column label="路径范围" prop="pathPrefix" min-width="160" show-overflow-tooltip />
      <el-table-column label="方法" prop="allowedMethods" width="150" />
      <el-table-column label="认证" width="110" align="center">
        <template #default="scope"><el-tag size="small" type="info">{{ scope.row.authType }}</el-tag></template>
      </el-table-column>
      <el-table-column label="修订" width="82" align="center">
        <template #default="scope"><strong>R{{ scope.row.revisionNo }}</strong></template>
      </el-table-column>
      <el-table-column label="状态" width="92" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ENABLED' ? 'success' : 'info'">
            {{ scope.row.status === 'ENABLED' ? '已启用' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="116" fixed="right" align="center">
        <template #default="scope">
          <el-tooltip content="发布新修订">
            <el-button link type="primary" icon="Edit" aria-label="发布新修订" v-hasPermi="['workflow:connector:edit']" @click="openEdit(scope.row)" />
          </el-tooltip>
          <el-tooltip :content="scope.row.status === 'ENABLED' ? '停用端点' : '启用端点'">
            <el-button
              link
              :type="scope.row.status === 'ENABLED' ? 'danger' : 'success'"
              :icon="scope.row.status === 'ENABLED' ? 'VideoPause' : 'VideoPlay'"
              :aria-label="scope.row.status === 'ENABLED' ? '停用端点' : '启用端点'"
              v-hasPermi="['workflow:connector:edit']"
              @click="toggleStatus(scope.row)"
            />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="editingId ? '发布端点新修订' : '新增连接端点'" width="680px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="端点名称" prop="endpointName">
          <el-input v-model="form.endpointName" maxlength="128" />
        </el-form-item>
        <el-form-item label="稳定键" prop="endpointKey">
          <el-input v-model="form.endpointKey" :readonly="Boolean(editingId)" maxlength="128" />
        </el-form-item>
        <el-form-item label="基础 URL" prop="baseUrl">
          <el-input v-model="form.baseUrl" maxlength="1024" placeholder="https://api.example.com" />
        </el-form-item>
        <el-form-item label="允许方法" prop="allowedMethods">
          <el-checkbox-group v-model="form.allowedMethods">
            <el-checkbox v-for="method in methods" :key="method" :value="method">{{ method }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="路径前缀" prop="pathPrefix">
          <el-input v-model="form.pathPrefix" maxlength="512" placeholder="/workflow" />
        </el-form-item>
        <el-form-item label="网络范围" prop="networkScope">
          <el-segmented v-model="form.networkScope" :options="networkOptions" />
        </el-form-item>
        <el-form-item label="认证类型" prop="authType">
          <el-select v-model="form.authType" @change="normalizeAuthFields">
            <el-option label="无认证" value="NONE" />
            <el-option label="Bearer Token" value="BEARER" />
            <el-option label="API Key" value="API_KEY" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.authType !== 'NONE'" label="密钥引用" prop="secretRef">
          <el-input v-model="form.secretRef" maxlength="128" placeholder="WORKFLOW_CONNECTOR_SECRET_FINANCE" />
        </el-form-item>
        <el-form-item v-if="form.authType === 'API_KEY'" label="认证请求头" prop="apiKeyHeader">
          <el-input v-model="form.apiKeyHeader" maxlength="128" placeholder="X-API-Key" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="连接超时" prop="connectTimeoutMs">
              <el-input-number v-model="form.connectTimeoutMs" :min="100" :max="10000" :step="100" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="请求超时" prop="requestTimeoutMs">
              <el-input-number v-model="form.requestTimeoutMs" :min="500" :max="120000" :step="500" controls-position="right" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ editingId ? '发布修订' : '保存端点' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowConnector">
import {
  changeConnectorEndpointStatus,
  createConnectorEndpoint,
  listConnectorEndpoints,
  updateConnectorEndpoint
} from '@/api/workflow/connector'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const keyword = ref('')
const dialogOpen = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const form = reactive(emptyForm())
const methods = Object.freeze(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'])
const networkOptions = Object.freeze([
  { label: '公网', value: 'PUBLIC' },
  { label: '内网', value: 'PRIVATE' }
])
const rules = {
  endpointName: [{ required: true, message: '端点名称不能为空', trigger: 'blur' }],
  endpointKey: [
    { required: true, message: '稳定键不能为空', trigger: 'blur' },
    { pattern: /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/, message: '稳定键格式不合法', trigger: 'blur' }
  ],
  baseUrl: [{ required: true, message: '基础 URL 不能为空', trigger: 'blur' }],
  allowedMethods: [{ type: 'array', required: true, min: 1, message: '至少选择一个方法', trigger: 'change' }],
  pathPrefix: [{ required: true, pattern: /^\//, message: '路径前缀必须以 / 开头', trigger: 'blur' }],
  secretRef: [{ pattern: /^WORKFLOW_CONNECTOR_SECRET_[A-Z0-9_]{1,96}$/, message: '密钥引用格式不合法', trigger: 'blur' }]
}
const filteredRows = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => !query || row.endpointName.toLowerCase().includes(query) || row.endpointKey.toLowerCase().includes(query))
})
let initialized = false

/**
 * 创建端点表单默认值。
 * @returns {object} 字段完整且不包含密钥正文的请求模型。
 */
function emptyForm() {
  return {
    endpointKey: '', endpointName: '', baseUrl: '', allowedMethods: ['POST'], pathPrefix: '/',
    authType: 'NONE', secretRef: '', apiKeyHeader: '', connectTimeoutMs: 3000,
    requestTimeoutMs: 10000, networkScope: 'PUBLIC'
  }
}

/**
 * 从真实后端加载端点和当前修订。
 * @returns {Promise<void>} 请求完成后刷新表格。
 */
async function loadEndpoints() {
  loading.value = true
  try {
    const response = await listConnectorEndpoints()
    rows.value = response.data || []
  } finally {
    loading.value = false
  }
}

/**
 * 打开新增端点对话框。
 * @returns {void} 清理上一次编辑和校验状态。
 */
function openCreate() {
  editingId.value = null
  Object.assign(form, emptyForm())
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 使用当前端点修订打开编辑对话框。
 * @param {object} row 当前端点行。
 * @returns {void} 只回读后端允许字段。
 */
function openEdit(row) {
  editingId.value = row.endpointId
  Object.assign(form, {
    endpointKey: row.endpointKey,
    endpointName: row.endpointName,
    baseUrl: row.baseUrl,
    allowedMethods: row.allowedMethods.split(',').filter(Boolean),
    pathPrefix: row.pathPrefix,
    authType: row.authType,
    secretRef: row.secretRef || '',
    apiKeyHeader: row.apiKeyHeader || '',
    connectTimeoutMs: row.connectTimeoutMs,
    requestTimeoutMs: row.requestTimeoutMs,
    networkScope: row.networkScope
  })
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 切换认证类型时清理不再适用的字段，防止旧密钥引用误提交。
 * @returns {void} 只修改当前表单草稿。
 */
function normalizeAuthFields() {
  if (form.authType === 'NONE') {
    form.secretRef = ''
    form.apiKeyHeader = ''
  } else if (form.authType === 'BEARER') {
    form.apiKeyHeader = ''
  }
}

/**
 * 新增端点或发布下一修订，并从数据库重新加载结果。
 * @returns {Promise<void>} 后端事务成功后关闭对话框。
 */
async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const payload = {
    ...form,
    endpointKey: form.endpointKey.trim(),
    endpointName: form.endpointName.trim(),
    baseUrl: form.baseUrl.trim(),
    pathPrefix: form.pathPrefix.trim(),
    secretRef: form.secretRef.trim() || undefined,
    apiKeyHeader: form.apiKeyHeader.trim() || undefined,
    allowedMethods: [...form.allowedMethods]
  }
  saving.value = true
  try {
    if (editingId.value) {
      await proxy.$modal.confirm('确认发布端点的新修订吗？已有部署继续使用原冻结配置。')
      await updateConnectorEndpoint(editingId.value, payload)
      proxy.$modal.msgSuccess('端点新修订已发布')
    } else {
      await createConnectorEndpoint(payload)
      proxy.$modal.msgSuccess('连接端点创建成功')
    }
    dialogOpen.value = false
    await loadEndpoints()
  } finally {
    saving.value = false
  }
}

/**
 * 经确认后启用或停用端点，历史部署不受影响。
 * @param {object} row 当前端点行。
 * @returns {Promise<void>} 状态变更后刷新真实清单。
 */
async function toggleStatus(row) {
  const enabled = row.status !== 'ENABLED'
  const action = enabled ? '启用' : '停用'
  await proxy.$modal.confirm(`确认${action}端点“${row.endpointName}”吗？`)
  await changeConnectorEndpointStatus(row.endpointId, enabled)
  proxy.$modal.msgSuccess(`端点已${action}`)
  await loadEndpoints()
}

onMounted(async () => {
  await loadEndpoints()
  initialized = true
})
onActivated(() => {
  if (initialized) loadEndpoints()
})
</script>

<style scoped>
.connector-endpoints {
  color: #1e2933;
}

.connector-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  min-height: 68px;
  margin: -4px 0 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid #dfe4e8;
}

.connector-heading h2 {
  margin: 0;
  font-size: 22px;
  letter-spacing: 0;
}

.connector-heading p {
  margin: 5px 0 0;
  color: #6b7785;
  font-size: 13px;
}

.connector-filter :deep(.el-input) {
  width: 240px;
}

.endpoint-identity {
  display: grid;
  gap: 4px;
}

.endpoint-identity code {
  color: #6b7785;
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
  letter-spacing: 0;
}

.connector-endpoints :deep(.el-select),
.connector-endpoints :deep(.el-input-number) {
  width: 100%;
}
</style>
