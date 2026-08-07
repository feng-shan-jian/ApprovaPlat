<template>
  <div class="app-container extension-registry">
    <header class="registry-heading">
      <div>
        <h2>扩展注册表</h2>
        <p>目录状态与不可变版本</p>
      </div>
      <div class="registry-summary" aria-label="扩展目录汇总">
        <span><strong>{{ rows.length }}</strong> 目录</span>
        <span><strong>{{ enabledCount }}</strong> 已启用</span>
        <span><strong>{{ versionedCount }}</strong> 已发布</span>
      </div>
    </header>

    <el-form :model="filters" inline class="registry-filter" label-width="72px">
      <el-form-item label="检索">
        <el-input
          v-model="filters.keyword"
          clearable
          placeholder="名称或稳定键"
          prefix-icon="Search"
          @keyup.enter="applyFilters"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-segmented v-model="filters.status" :options="statusFilters" @change="applyFilters" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="applyFilters">查询</el-button>
        <el-button icon="Refresh" @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8 registry-actions">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="Plus"
          v-hasPermi="['workflow:extension:add']"
          @click="openCreate"
        >新增目录</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button plain icon="Cpu" @click="handlerDialogOpen = true">已安装处理器</el-button>
      </el-col>
      <right-toolbar :show-search="false" @queryTable="loadRegistry" />
    </el-row>

    <el-table v-loading="loading" :data="filteredRows" row-key="extensionId">
      <el-table-column label="扩展目录" min-width="240">
        <template #default="scope">
          <div class="extension-identity">
            <strong>{{ scope.row.extensionName }}</strong>
            <code>{{ scope.row.extensionKey }}</code>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类型" prop="extensionType" width="92" align="center">
        <template #default="scope"><el-tag size="small" type="info">{{ scope.row.extensionType }}</el-tag></template>
      </el-table-column>
      <el-table-column label="当前版本" width="112" align="center">
        <template #default="scope">
          <span v-if="scope.row.versionNo" class="version-label">V{{ scope.row.versionNo }}</span>
          <span v-else class="muted">未发布</span>
        </template>
      </el-table-column>
      <el-table-column label="处理器" prop="implementationKey" min-width="150" show-overflow-tooltip>
        <template #default="scope"><code v-if="scope.row.implementationKey">{{ scope.row.implementationKey }}</code><span v-else class="muted">-</span></template>
      </el-table-column>
      <el-table-column label="说明" prop="description" min-width="220" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.description || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="98" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.status === 'ENABLED' ? 'success' : 'info'">
            {{ scope.row.status === 'ENABLED' ? '已启用' : '已停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="scope">{{ parseTime(scope.row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="168" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip content="发布新版本" placement="top">
            <el-button
              link
              type="primary"
              icon="UploadFilled"
              aria-label="发布新版本"
              v-hasPermi="['workflow:extension:version:add']"
              @click="openVersion(scope.row)"
            />
          </el-tooltip>
          <el-tooltip :content="scope.row.status === 'ENABLED' ? '停用目录' : '启用目录'" placement="top">
            <el-button
              link
              :type="scope.row.status === 'ENABLED' ? 'danger' : 'success'"
              :icon="scope.row.status === 'ENABLED' ? 'VideoPause' : 'VideoPlay'"
              :aria-label="scope.row.status === 'ENABLED' ? '停用目录' : '启用目录'"
              v-hasPermi="['workflow:extension:edit']"
              @click="toggleStatus(scope.row)"
            />
          </el-tooltip>
          <el-tooltip :content="scope.row.status === 'ENABLED' ? '停用后可删除' : '删除目录'" placement="top">
            <el-button
              link
              type="danger"
              icon="Delete"
              :disabled="scope.row.status === 'ENABLED'"
              aria-label="删除目录"
              v-hasPermi="['workflow:extension:remove']"
              @click="removeExtension(scope.row)"
            />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && filteredRows.length === 0" description="没有匹配的扩展目录" :image-size="72" />

    <el-dialog v-model="createDialogOpen" title="新增扩展目录" width="560px" append-to-body>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="92px">
        <el-form-item label="目录名称" prop="extensionName">
          <el-input v-model="createForm.extensionName" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="稳定键" prop="extensionKey">
          <el-input v-model="createForm.extensionKey" maxlength="128" placeholder="approva.example-handler" />
        </el-form-item>
        <el-form-item label="扩展类型" prop="extensionType">
          <el-select v-model="createForm.extensionType" style="width: 100%">
            <el-option label="Java 受控处理器" value="JAVA" />
            <el-option label="CEL 安全表达式" value="CEL" />
            <el-option label="HTTP 受控连接器" value="HTTP" />
            <el-option label="SQL 受控连接器" value="SQL" />
            <el-option label="自定义表单字段" value="FORM_FIELD" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务说明" prop="description">
          <el-input v-model="createForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">保存目录</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionDialogOpen" title="发布不可变版本" width="560px" append-to-body>
      <el-form ref="versionFormRef" :model="versionForm" :rules="versionRules" label-width="92px">
        <el-form-item label="扩展目录">
          <el-input :model-value="versionTarget?.extensionName" readonly />
        </el-form-item>
        <el-form-item label="当前版本">
          <el-input :model-value="versionTarget?.versionNo ? `V${versionTarget.versionNo}` : '尚未发布'" readonly />
        </el-form-item>
        <el-form-item v-if="versionTarget?.extensionType === 'JAVA'" label="处理器" prop="implementationKey">
          <el-select v-model="versionForm.implementationKey" style="width: 100%" placeholder="选择服务端已安装处理器">
            <el-option
              v-for="handler in handlers"
              :key="handler.implementationKey"
              :label="handler.name"
              :value="handler.implementationKey"
            >
              <span>{{ handler.name }}</span>
              <code class="handler-key">{{ handler.implementationKey }}</code>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item v-else-if="versionTarget?.extensionType === 'CEL'" label="表达式引擎">
          <el-input model-value="CEL 安全表达式 V1" readonly />
        </el-form-item>
        <el-form-item v-else label="固定实现">
          <el-input :model-value="fixedImplementationLabel" readonly />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="versionDialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitVersion">发布版本</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="handlerDialogOpen" title="服务端已安装处理器" width="720px" append-to-body>
      <el-table :data="handlers" max-height="420">
        <el-table-column label="处理器名称" prop="name" min-width="160" />
        <el-table-column label="稳定键" prop="implementationKey" min-width="160">
          <template #default="scope"><code>{{ scope.row.implementationKey }}</code></template>
        </el-table-column>
        <el-table-column label="配置 Schema" min-width="280" show-overflow-tooltip>
          <template #default="scope"><code>{{ scope.row.configSchema }}</code></template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowExtension">
import {
  changeWorkflowExtensionStatus,
  createWorkflowExtension,
  createWorkflowExtensionVersion,
  listInstalledJavaHandlers,
  listWorkflowExtensions,
  removeWorkflowExtension
} from '@/api/workflow/extension'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const filteredRows = ref([])
const handlers = ref([])
const createDialogOpen = ref(false)
const versionDialogOpen = ref(false)
const handlerDialogOpen = ref(false)
const versionTarget = ref(null)
const createFormRef = ref(null)
const versionFormRef = ref(null)
const filters = reactive({ keyword: '', status: 'ALL' })
const createForm = reactive(createEmptyExtension())
const versionForm = reactive({ implementationKey: '' })
const statusFilters = Object.freeze([
  { label: '全部', value: 'ALL' },
  { label: '已启用', value: 'ENABLED' },
  { label: '已停用', value: 'DISABLED' }
])
const createRules = {
  extensionName: [{ required: true, message: '目录名称不能为空', trigger: 'blur' }],
  extensionKey: [
    { required: true, message: '稳定键不能为空', trigger: 'blur' },
    { pattern: /^[A-Za-z][A-Za-z0-9_.-]{0,127}$/, message: '稳定键格式不合法', trigger: 'blur' }
  ]
}
const versionRules = {
  implementationKey: [{ required: true, message: '请选择已安装处理器', trigger: 'change' }]
}
const enabledCount = computed(() => rows.value.filter(row => row.status === 'ENABLED').length)
const versionedCount = computed(() => rows.value.filter(row => Boolean(row.versionNo)).length)
const fixedImplementationLabel = computed(() => ({
  HTTP: 'HTTP 受控连接器 V1',
  SQL: 'SQL 受控连接器 V1',
  FORM_FIELD: '多行文本字段 V1'
})[versionTarget.value?.extensionType] || '-')
let pageInitialized = false

/**
 * 创建新增目录的稳定初始模型。
 * @returns {object} 只包含后端允许字段的新增请求模型。
 */
function createEmptyExtension() {
  return { extensionName: '', extensionKey: '', extensionType: 'JAVA', description: '' }
}

/**
 * 并行加载全部目录和服务端已安装处理器，并重新应用当前筛选条件。
 * @returns {Promise<void>} 两个真实接口完成后更新页面状态。
 */
async function loadRegistry() {
  loading.value = true
  try {
    const [registryResponse, handlerResponse] = await Promise.all([
      listWorkflowExtensions(),
      listInstalledJavaHandlers()
    ])
    rows.value = registryResponse.data || []
    handlers.value = handlerResponse.data || []
    applyFilters()
  } finally {
    loading.value = false
  }
}

/**
 * 使用名称、稳定键和目录状态过滤服务端回读清单。
 * @returns {void} 更新当前表格行，不修改正式目录数据。
 */
function applyFilters() {
  const keyword = filters.keyword.trim().toLowerCase()
  filteredRows.value = rows.value.filter(row => {
    const statusMatched = filters.status === 'ALL' || row.status === filters.status
    const keywordMatched = !keyword || row.extensionName.toLowerCase().includes(keyword) || row.extensionKey.toLowerCase().includes(keyword)
    return statusMatched && keywordMatched
  })
}

/**
 * 清空筛选条件并恢复全部服务端目录。
 * @returns {void} 更新筛选和表格状态。
 */
function resetFilters() {
  filters.keyword = ''
  filters.status = 'ALL'
  applyFilters()
}

/**
 * 打开新增目录对话框并清理上次校验状态。
 * @returns {void} 显示空白新增表单。
 */
function openCreate() {
  Object.assign(createForm, createEmptyExtension())
  createDialogOpen.value = true
  nextTick(() => createFormRef.value?.clearValidate())
}

/**
 * 通过真实后端创建扩展目录并刷新数据库清单。
 * @returns {Promise<void>} 创建成功后关闭对话框并显示新目录。
 */
async function submitCreate() {
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await createWorkflowExtension({
      extensionName: createForm.extensionName.trim(),
      extensionKey: createForm.extensionKey.trim(),
      extensionType: createForm.extensionType,
      description: createForm.description.trim() || undefined
    })
    proxy.$modal.msgSuccess('扩展目录创建成功')
    createDialogOpen.value = false
    await loadRegistry()
  } finally {
    saving.value = false
  }
}

/**
 * 打开不可变版本发布对话框，默认选择目录当前处理器或唯一已安装处理器。
 * @param {object} row 当前扩展目录行。
 * @returns {void} 设置发布目标并显示版本表单。
 */
function openVersion(row) {
  versionTarget.value = row
  const fixedImplementations = {
    CEL: 'CEL_EXPRESSION_V1',
    HTTP: 'HTTP_CONNECTOR_V1',
    SQL: 'SQL_CONNECTOR_V1',
    FORM_FIELD: 'FORM_FIELD_TEXTAREA_V1'
  }
  versionForm.implementationKey = fixedImplementations[row.extensionType]
    || row.implementationKey || (handlers.value.length === 1 ? handlers.value[0].implementationKey : '')
  versionDialogOpen.value = true
  nextTick(() => versionFormRef.value?.clearValidate())
}

/**
 * 发布目录下一个不可变版本并刷新管理清单。
 * @returns {Promise<void>} 后端生成版本和校验和后关闭对话框。
 */
async function submitVersion() {
  const valid = await versionFormRef.value.validate().catch(() => false)
  if (!valid || !versionTarget.value) return
  await proxy.$modal.confirm(`确认发布“${versionTarget.value.extensionName}”的新版本吗？`)
  saving.value = true
  try {
    await createWorkflowExtensionVersion(versionTarget.value.extensionId, {
      implementationKey: versionForm.implementationKey
    })
    proxy.$modal.msgSuccess('不可变版本发布成功')
    versionDialogOpen.value = false
    await loadRegistry()
  } finally {
    saving.value = false
  }
}

/**
 * 经确认后切换目录状态；停用只影响后续设计和部署，不修改历史快照。
 * @param {object} row 当前扩展目录行。
 * @returns {Promise<void>} 状态机执行成功后刷新数据库清单。
 */
async function toggleStatus(row) {
  const enabled = row.status !== 'ENABLED'
  const action = enabled ? '启用' : '停用'
  await proxy.$modal.confirm(`确认${action}扩展目录“${row.extensionName}”吗？`)
  await changeWorkflowExtensionStatus(row.extensionId, enabled)
  proxy.$modal.msgSuccess(`扩展目录已${action}`)
  await loadRegistry()
}

/**
 * 删除已停用且未被部署快照引用的目录，并从真实数据库重新加载清单。
 * @param {object} row 当前扩展目录行。
 * @returns {Promise<void>} 后端完成受约束删除后刷新页面。
 */
async function removeExtension(row) {
  if (row.status === 'ENABLED') {
    proxy.$modal.msgWarning('请先停用扩展目录再删除')
    return
  }
  await proxy.$modal.confirm(`确认删除扩展目录“${row.extensionName}”及其未部署版本吗？`)
  await removeWorkflowExtension(row.extensionId)
  proxy.$modal.msgSuccess('扩展目录已删除')
  await loadRegistry()
}

onMounted(async () => {
  await loadRegistry()
  pageInitialized = true
})

onActivated(() => {
  if (pageInitialized) loadRegistry()
})
</script>

<style scoped>
.extension-registry {
  --registry-ink: #1e2933;
  --registry-muted: #6b7785;
  --registry-line: #dfe4e8;
  color: var(--registry-ink);
}

.registry-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  min-height: 68px;
  margin: -4px 0 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--registry-line);
}

.registry-heading h2 {
  margin: 0;
  font-family: "Microsoft YaHei UI", "PingFang SC", sans-serif;
  font-size: 22px;
  line-height: 1.25;
  letter-spacing: 0;
}

.registry-heading p {
  margin: 5px 0 0;
  color: var(--registry-muted);
  font-size: 13px;
}

.registry-summary {
  display: flex;
  gap: 24px;
  color: var(--registry-muted);
  font-size: 13px;
}

.registry-summary strong {
  margin-right: 4px;
  color: var(--registry-ink);
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 18px;
  font-weight: 650;
}

.registry-filter {
  padding: 2px 0 4px;
}

.registry-filter :deep(.el-input) {
  width: 240px;
}

.registry-actions {
  align-items: center;
}

.extension-identity {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.extension-identity strong {
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

code,
.version-label {
  font-family: "JetBrains Mono", Consolas, monospace;
  font-size: 12px;
  letter-spacing: 0;
}

.extension-identity code,
.muted {
  color: var(--registry-muted);
}

.handler-key {
  float: right;
  margin-left: 18px;
  color: var(--registry-muted);
}

@media (max-width: 1100px) {
  .registry-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .registry-summary {
    gap: 16px;
  }
}
</style>
