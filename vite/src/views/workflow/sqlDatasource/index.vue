<template>
  <div class="app-container sql-datasource-page">
    <div class="sql-datasource-page__heading">
      <div>
        <h2>SQL 数据源</h2>
        <p>管理主库事务连接与外库环境引用、表白名单和不可回退修订</p>
      </div>
      <div class="sql-datasource-page__summary">
        <span><strong>{{ rows.length }}</strong> 个目录</span>
        <span><strong>{{ enabledCount }}</strong> 个启用</span>
      </div>
    </div>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" v-hasPermi="['workflow:sqlDatasource:add']" @click="openCreate">新增数据源</el-button>
      </el-col>
      <right-toolbar :show-search="false" @queryTable="loadRows" />
    </el-row>

    <el-table v-loading="loading" :data="rows" row-key="dataSourceId">
      <el-table-column label="数据源" min-width="230">
        <template #default="scope">
          <div class="sql-datasource-page__identity">
            <strong>{{ scope.row.dataSourceName }}</strong>
            <code>{{ scope.row.dataSourceKey }}</code>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="连接" width="110" align="center">
        <template #default="scope"><el-tag size="small" type="info">{{ scope.row.connectionType }}</el-tag></template>
      </el-table-column>
      <el-table-column label="修订" width="86" align="center">
        <template #default="scope">R{{ scope.row.revisionNo }}</template>
      </el-table-column>
      <el-table-column label="授权表" min-width="250" show-overflow-tooltip>
        <template #default="scope"><code>{{ scope.row.allowedTables.join(', ') }}</code></template>
      </el-table-column>
      <el-table-column label="执行超时" width="100" align="center">
        <template #default="scope">{{ scope.row.queryTimeoutSeconds }} 秒</template>
      </el-table-column>
      <el-table-column label="状态" width="96" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ENABLED' ? 'success' : 'info'">{{ scope.row.status === 'ENABLED' ? '已启用' : '已停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="116" fixed="right" align="center">
        <template #default="scope">
          <el-tooltip content="发布新修订" placement="top">
            <el-button link type="primary" icon="Edit" aria-label="发布新修订" v-hasPermi="['workflow:sqlDatasource:edit']" @click="openEdit(scope.row)" />
          </el-tooltip>
          <el-tooltip :content="scope.row.status === 'ENABLED' ? '停用' : '启用'" placement="top">
            <el-button link :type="scope.row.status === 'ENABLED' ? 'danger' : 'success'" :icon="scope.row.status === 'ENABLED' ? 'VideoPause' : 'VideoPlay'" :aria-label="scope.row.status === 'ENABLED' ? '停用' : '启用'" v-hasPermi="['workflow:sqlDatasource:edit']" @click="toggleStatus(scope.row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" :title="editingRow ? '发布数据源新修订' : '新增 SQL 数据源'" width="680px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="112px">
        <el-form-item label="显示名称" prop="dataSourceName"><el-input v-model="form.dataSourceName" maxlength="128" /></el-form-item>
        <el-form-item label="稳定键" prop="dataSourceKey"><el-input v-model="form.dataSourceKey" :disabled="Boolean(editingRow)" maxlength="128" /></el-form-item>
        <el-form-item label="连接类型" prop="connectionType">
          <el-segmented v-model="form.connectionType" :options="connectionOptions" />
        </el-form-item>
        <template v-if="form.connectionType === 'EXTERNAL'">
          <el-form-item label="JDBC URL 引用" prop="jdbcUrlRef"><el-input v-model="form.jdbcUrlRef" maxlength="128" /></el-form-item>
          <el-form-item label="用户名引用" prop="usernameRef"><el-input v-model="form.usernameRef" maxlength="128" /></el-form-item>
          <el-form-item label="密码引用" prop="passwordRef"><el-input v-model="form.passwordRef" maxlength="128" /></el-form-item>
        </template>
        <el-form-item label="授权表" prop="allowedTablesText">
          <el-input v-model="form.allowedTablesText" type="textarea" :rows="3" maxlength="8192" placeholder="每行一个表名，例如 wf_business_status" />
        </el-form-item>
        <el-form-item label="建连超时"><el-input-number v-model="form.connectTimeoutMs" :min="100" :max="10000" controls-position="right" /></el-form-item>
        <el-form-item label="执行超时"><el-input-number v-model="form.queryTimeoutSeconds" :min="1" :max="300" controls-position="right" /><span class="sql-datasource-page__unit">秒</span></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ editingRow ? '发布修订' : '保存' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowSqlDatasource">
import { changeSqlDataSourceStatus, createSqlDataSource, listSqlDataSources, updateSqlDataSource } from '@/api/workflow/sqlDatasource'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const dialogOpen = ref(false)
const editingRow = ref(null)
const formRef = ref(null)
const form = reactive(emptyForm())
const enabledCount = computed(() => rows.value.filter(row => row.status === 'ENABLED').length)
const connectionOptions = Object.freeze([{ label: '主库', value: 'PRIMARY' }, { label: '外库', value: 'EXTERNAL' }])
const rules = {
  dataSourceName: [{ required: true, message: '显示名称不能为空', trigger: 'blur' }],
  dataSourceKey: [{ required: true, pattern: /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/, message: '稳定键格式不合法', trigger: 'blur' }],
  connectionType: [{ required: true, message: '连接类型不能为空', trigger: 'change' }],
  allowedTablesText: [{ required: true, message: '至少配置一张授权表', trigger: 'blur' }]
}
let pageInitialized = false

/**
 * 创建数据源表单初始值。
 * @returns {object} 可直接提交前规范化的页面模型。
 */
function emptyForm() {
  return { dataSourceName: '', dataSourceKey: '', connectionType: 'PRIMARY', jdbcUrlRef: '', usernameRef: '', passwordRef: '', allowedTablesText: '', connectTimeoutMs: 3000, queryTimeoutSeconds: 30 }
}

/**
 * 从真实后端刷新数据源目录。
 * @returns {Promise<void>} 请求完成后更新表格。
 */
async function loadRows() {
  loading.value = true
  try {
    const response = await listSqlDataSources()
    rows.value = response.data || []
  } finally {
    loading.value = false
  }
}

/**
 * 打开新增数据源表单。
 * @returns {void} 清理上一轮状态和校验。
 */
function openCreate() {
  editingRow.value = null
  Object.assign(form, emptyForm())
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 打开不可回退修订表单并回显正式目录字段。
 * @param {object} row 当前数据源目录行。
 * @returns {void} 不回显任何凭据正文，只有环境引用名。
 */
function openEdit(row) {
  editingRow.value = row
  Object.assign(form, {
    dataSourceName: row.dataSourceName,
    dataSourceKey: row.dataSourceKey,
    connectionType: row.connectionType,
    jdbcUrlRef: row.jdbcUrlRef || '',
    usernameRef: row.usernameRef || '',
    passwordRef: row.passwordRef || '',
    allowedTablesText: row.allowedTables.join('\n'),
    connectTimeoutMs: row.connectTimeoutMs,
    queryTimeoutSeconds: row.queryTimeoutSeconds
  })
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 规范页面模型并创建目录或发布新修订。
 * @returns {Promise<void>} 后端事务完成后刷新正式清单。
 */
async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const allowedTables = [...new Set(form.allowedTablesText.split(/[\n,]/).map(item => item.trim()).filter(Boolean))]
  if (!allowedTables.length) {
    proxy.$modal.msgWarning('至少配置一张授权表')
    return
  }
  const payload = {
    dataSourceName: form.dataSourceName.trim(),
    dataSourceKey: form.dataSourceKey.trim(),
    connectionType: form.connectionType,
    allowedTables,
    connectTimeoutMs: form.connectTimeoutMs,
    queryTimeoutSeconds: form.queryTimeoutSeconds
  }
  if (form.connectionType === 'EXTERNAL') {
    payload.jdbcUrlRef = form.jdbcUrlRef.trim()
    payload.usernameRef = form.usernameRef.trim()
    payload.passwordRef = form.passwordRef.trim()
  }
  saving.value = true
  try {
    if (editingRow.value) await updateSqlDataSource(editingRow.value.dataSourceId, payload)
    else await createSqlDataSource(payload)
    proxy.$modal.msgSuccess(editingRow.value ? '数据源修订已发布' : '数据源创建成功')
    dialogOpen.value = false
    await loadRows()
  } finally {
    saving.value = false
  }
}

/**
 * 经确认切换目录启停状态，历史部署快照不受影响。
 * @param {object} row 当前目录行。
 * @returns {Promise<void>} 更新成功后刷新真实清单。
 */
async function toggleStatus(row) {
  const enabled = row.status !== 'ENABLED'
  await proxy.$modal.confirm(`确认${enabled ? '启用' : '停用'}数据源“${row.dataSourceName}”吗？`)
  await changeSqlDataSourceStatus(row.dataSourceId, enabled)
  await loadRows()
}

onMounted(async () => {
  await loadRows()
  pageInitialized = true
})
onActivated(() => { if (pageInitialized) loadRows() })
</script>

<style scoped>
.sql-datasource-page__heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  min-height: 68px;
  margin: -4px 0 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.sql-datasource-page__heading h2 { margin: 0; font-size: 22px; letter-spacing: 0; }
.sql-datasource-page__heading p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.sql-datasource-page__summary { display: flex; gap: 24px; color: var(--el-text-color-secondary); font-size: 13px; }
.sql-datasource-page__summary strong { margin-right: 4px; color: var(--el-text-color-primary); font-family: Consolas, monospace; font-size: 18px; }
.sql-datasource-page__identity { display: grid; gap: 4px; }
.sql-datasource-page__identity code, .sql-datasource-page code { color: var(--el-text-color-secondary); font-family: Consolas, monospace; font-size: 12px; }
.sql-datasource-page__unit { margin-left: 8px; color: var(--el-text-color-secondary); }
</style>
