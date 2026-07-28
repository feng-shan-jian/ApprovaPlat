<template>
  <div class="app-container workflow-page">
    <el-form ref="queryRef" :model="queryParams" inline v-show="showSearch" label-width="72px">
      <el-form-item label="流程名称" prop="processName">
        <el-input v-model="queryParams.processName" clearable placeholder="请输入流程名称" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="流程标识" prop="processKey">
        <el-input v-model="queryParams.processKey" clearable placeholder="请输入流程标识" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="流程分类" prop="category">
        <el-select v-model="queryParams.category" clearable filterable placeholder="全部分类" style="width: 180px">
          <el-option v-for="item in categoryOptions" :key="item.code" :label="item.categoryName" :value="item.code" />
        </el-select>
      </el-form-item>
      <el-form-item label="定义状态" prop="state">
        <el-select v-model="queryParams.state" clearable placeholder="全部状态" style="width: 130px">
          <el-option label="已激活" value="active" />
          <el-option label="已挂起" value="suspended" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="danger" plain icon="Delete" :disabled="!selectedIds.length" v-hasPermi="['workflow:deploy:remove']" @click="removeDeployments()">删除</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="loadList" />
    </el-row>

    <el-table v-loading="loading" :data="rows" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="52" align="center" />
      <el-table-column label="流程名称" prop="processName" min-width="190" show-overflow-tooltip />
      <el-table-column label="流程标识" prop="processKey" min-width="180" show-overflow-tooltip />
      <el-table-column label="分类" min-width="130" show-overflow-tooltip>
        <template #default="scope">{{ categoryName(scope.row.category) }}</template>
      </el-table-column>
      <el-table-column label="版本" prop="version" width="82" align="center">
        <template #default="scope"><el-tag size="small" type="info">V{{ scope.row.version }}</el-tag></template>
      </el-table-column>
      <el-table-column label="表单" prop="formName" min-width="150" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.formName || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96" align="center">
        <template #default="scope"><el-tag :type="scope.row.suspended ? 'warning' : 'success'">{{ scope.row.suspended ? '已挂起' : '已激活' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="部署时间" width="170">
        <template #default="scope">{{ parseTime(scope.row.deploymentTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="176" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip content="查看流程图" placement="top"><el-button link type="primary" icon="View" v-hasPermi="['workflow:deploy:query']" @click="previewDefinition(scope.row)" /></el-tooltip>
          <el-tooltip content="发布版本" placement="top"><el-button link type="primary" icon="Clock" v-hasPermi="['workflow:deploy:list']" @click="openVersions(scope.row)" /></el-tooltip>
          <el-tooltip :content="scope.row.suspended ? '激活' : '挂起'" placement="top"><el-button link :type="scope.row.suspended ? 'success' : 'warning'" :icon="scope.row.suspended ? 'VideoPlay' : 'VideoPause'" v-hasPermi="['workflow:deploy:state']" @click="toggleState(scope.row)" /></el-tooltip>
          <el-tooltip content="删除" placement="top"><el-button link type="danger" icon="Delete" v-hasPermi="['workflow:deploy:remove']" @click="removeDeployments(scope.row)" /></el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadList" />

    <el-dialog v-model="viewerOpen" :title="viewerTitle" width="920px" append-to-body destroy-on-close>
      <ProcessViewer v-if="viewerOpen" :xml="viewerXml" :file-name="viewerFileName" height="520px" @error="showViewerError" />
    </el-dialog>

    <el-dialog v-model="versionsOpen" :title="`${versionProcessName} - 发布版本`" width="920px" append-to-body>
      <el-table v-loading="versionsLoading" :data="versionRows">
        <el-table-column label="版本" prop="version" width="86" align="center" />
        <el-table-column label="流程名称" prop="processName" min-width="190" show-overflow-tooltip />
        <el-table-column label="表单" prop="formName" min-width="150" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.formName || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="96" align="center">
          <template #default="scope"><el-tag :type="scope.row.suspended ? 'warning' : 'success'">{{ scope.row.suspended ? '已挂起' : '已激活' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="部署时间" width="170"><template #default="scope">{{ parseTime(scope.row.deploymentTime) }}</template></el-table-column>
        <el-table-column label="操作" width="128" align="center">
          <template #default="scope">
            <el-tooltip content="查看流程图" placement="top"><el-button link type="primary" icon="View" v-hasPermi="['workflow:deploy:query']" @click="previewDefinition(scope.row)" /></el-tooltip>
            <el-tooltip :content="scope.row.suspended ? '激活' : '挂起'" placement="top"><el-button link :type="scope.row.suspended ? 'success' : 'warning'" :icon="scope.row.suspended ? 'VideoPlay' : 'VideoPause'" v-hasPermi="['workflow:deploy:state']" @click="toggleVersionState(scope.row)" /></el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="versionTotal > 0" :total="versionTotal" v-model:page="versionQuery.pageNum" v-model:limit="versionQuery.pageSize" @pagination="loadVersions" />
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowDeploy">
import { listAllCategories } from '@/api/workflow/category'
import {
  changeDeploymentState,
  deleteDeployments,
  getDeploymentBpmnXml,
  listDeployments,
  listPublishedVersions
} from '@/api/workflow/deploy'
import ProcessViewer from '@/components/workflow/ProcessViewer.vue'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const showSearch = ref(true)
const rows = ref([])
const total = ref(0)
const selectedIds = ref([])
const categoryOptions = ref([])
const viewerOpen = ref(false)
const viewerTitle = ref('流程图')
const viewerFileName = ref('workflow')
const viewerXml = ref('')
const versionsOpen = ref(false)
const versionsLoading = ref(false)
const versionRows = ref([])
const versionTotal = ref(0)
const versionProcessName = ref('')
const queryParams = reactive({ pageNum: 1, pageSize: 10, processName: '', processKey: '', category: '', state: '' })
const versionQuery = reactive({ pageNum: 1, pageSize: 10, processKey: '' })

/**
 * 分页加载每个流程标识的最新部署定义。
 * @returns {Promise<void>} 查询完成后更新列表和总数。
 */
async function loadList() {
  loading.value = true
  try {
    const response = await listDeployments(queryParams)
    rows.value = response.rows || []
    total.value = response.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 加载有效流程分类选项。
 * @returns {Promise<void>} 选项加载后更新分类筛选器。
 */
async function loadCategories() {
  const response = await listAllCategories()
  categoryOptions.value = response.data || []
}

/**
 * 获取分类编码对应的显示名称。
 * @param {string} code 工作流分类编码。
 * @returns {string} 分类名称，找不到时回显编码。
 */
function categoryName(code) {
  return categoryOptions.value.find(item => item.code === code)?.categoryName || code || '-'
}

/**
 * 记录待批量删除的部署主键。
 * @param {Array<object>} selection 当前表格选中行。
 * @returns {void} 更新部署主键列表。
 */
function handleSelectionChange(selection) {
  selectedIds.value = selection.map(item => item.deploymentId)
}

/**
 * 从第一页执行当前部署条件查询。
 * @returns {void} 无返回值。
 */
function handleQuery() {
  queryParams.pageNum = 1
  loadList()
}

/**
 * 重置查询条件并刷新部署列表。
 * @returns {void} 无返回值。
 */
function resetQuery() {
  proxy.resetForm('queryRef')
  handleQuery()
}

/**
 * 查询服务端重新校验后的 BPMN XML 并打开只读流程图。
 * @param {object} row 部署定义行。
 * @returns {Promise<void>} XML 返回后打开查看器。
 */
async function previewDefinition(row) {
  const response = await getDeploymentBpmnXml(row.definitionId)
  viewerTitle.value = `${row.processName} V${row.version} - 流程图`
  viewerFileName.value = `${row.processKey}_v${row.version}`
  viewerXml.value = response.data || ''
  viewerOpen.value = true
}

/**
 * 打开流程标识的全部发布版本并加载第一页。
 * @param {object} row 最新部署定义行。
 * @returns {Promise<void>} 版本列表加载完成后显示对话框。
 */
async function openVersions(row) {
  versionQuery.processKey = row.processKey
  versionQuery.pageNum = 1
  versionProcessName.value = row.processName
  versionsOpen.value = true
  await loadVersions()
}

/**
 * 分页加载当前流程标识的所有已发布版本。
 * @returns {Promise<void>} 更新版本列表和总数。
 */
async function loadVersions() {
  versionsLoading.value = true
  try {
    const response = await listPublishedVersions(versionQuery)
    versionRows.value = response.rows || []
    versionTotal.value = response.total || 0
  } finally {
    versionsLoading.value = false
  }
}

/**
 * 激活或挂起最新流程定义及其运行实例。
 * @param {object} row 部署定义行。
 * @returns {Promise<void>} 状态变更成功后刷新主列表。
 */
async function toggleState(row) {
  const nextState = row.suspended ? 'active' : 'suspended'
  const action = row.suspended ? '激活' : '挂起'
  await proxy.$modal.confirm(`确认${action}流程“${row.processName}”V${row.version}及其运行实例吗？`)
  await changeDeploymentState(row.definitionId, nextState)
  proxy.$modal.msgSuccess(`流程定义已${action}`)
  await loadList()
}

/**
 * 在版本对话框中激活或挂起指定定义。
 * @param {object} row 发布版本行。
 * @returns {Promise<void>} 成功后同步刷新版本和最新定义列表。
 */
async function toggleVersionState(row) {
  const nextState = row.suspended ? 'active' : 'suspended'
  const action = row.suspended ? '激活' : '挂起'
  await proxy.$modal.confirm(`确认${action} V${row.version} 及其运行实例吗？`)
  await changeDeploymentState(row.definitionId, nextState)
  proxy.$modal.msgSuccess(`流程定义已${action}`)
  await Promise.all([loadVersions(), loadList()])
}

/**
 * 删除没有运行或历史实例引用的部署。
 * @param {object|undefined} row 部署行；为空时使用勾选主键。
 * @returns {Promise<void>} 后端引用检查通过后刷新列表。
 */
async function removeDeployments(row) {
  const ids = row?.deploymentId ? [row.deploymentId] : selectedIds.value
  await proxy.$modal.confirm(`确认删除选中的 ${ids.length} 个流程部署吗？`)
  await deleteDeployments(ids)
  proxy.$modal.msgSuccess('流程部署删除成功')
  await loadList()
}

/**
 * 显示流程图组件返回的导入或导出错误。
 * @param {Error} error 查看器错误对象。
 * @returns {void} 无返回值。
 */
function showViewerError(error) {
  proxy.$modal.msgError(error?.message || '流程图加载失败')
}

Promise.all([loadCategories(), loadList()])
</script>

<style scoped>
.workflow-page :deep(.el-form--inline .el-form-item) { margin-right: 18px; }
</style>
