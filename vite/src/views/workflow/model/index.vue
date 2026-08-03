<template>
  <div class="app-container workflow-page">
    <el-form ref="queryRef" :model="queryParams" inline v-show="showSearch" label-width="72px">
      <el-form-item label="模型名称" prop="modelName">
        <el-input v-model="queryParams.modelName" clearable placeholder="请输入模型名称" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="模型标识" prop="modelKey">
        <el-input v-model="queryParams.modelKey" clearable placeholder="请输入模型标识" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="流程分类" prop="category">
        <el-select v-model="queryParams.category" clearable filterable placeholder="全部分类" style="width: 180px">
          <el-option v-for="item in categoryOptions" :key="item.code" :label="item.categoryName" :value="item.code" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Plus" v-hasPermi="['workflow:model:add']" @click="openCreate">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="Edit" :disabled="selectedIds.length !== 1" v-hasPermi="['workflow:model:edit']" @click="openEdit()">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="Delete" :disabled="!selectedIds.length" v-hasPermi="['workflow:model:remove']" @click="removeModels()">删除</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="warning" plain icon="Download" v-hasPermi="['workflow:model:export']" @click="exportModels">导出</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="loadList" />
    </el-row>

    <el-table v-loading="loading" :data="rows" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="52" align="center" :selectable="canSelectModel" />
      <el-table-column label="模型名称" prop="modelName" min-width="190" show-overflow-tooltip />
      <el-table-column label="模型标识" prop="modelKey" min-width="180" show-overflow-tooltip />
      <el-table-column label="分类" min-width="130" show-overflow-tooltip>
        <template #default="scope">{{ categoryName(scope.row.category) }}</template>
      </el-table-column>
      <el-table-column label="版本" prop="version" width="82" align="center">
        <template #default="scope"><el-tag size="small" type="info">V{{ scope.row.version }}</el-tag></template>
      </el-table-column>
      <el-table-column label="表单方式" width="110" align="center">
        <template #default="scope">{{ formTypeLabel(scope.row.formType) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.deployed ? 'success' : 'info'">{{ scope.row.deployed ? '已部署' : '未部署' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="scope">{{ parseTime(scope.row.lastUpdateTime || scope.row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="238" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip content="设计流程" placement="top">
            <el-button link type="primary" icon="EditPen" v-hasPermi="['workflow:model:designer']" @click="openDesigner(scope.row)" />
          </el-tooltip>
          <el-tooltip content="修改元数据" placement="top">
            <el-button link type="primary" icon="Edit" :disabled="scope.row.deployed" v-hasPermi="['workflow:model:edit']" @click="openEdit(scope.row)" />
          </el-tooltip>
          <el-tooltip content="版本历史" placement="top">
            <el-button link type="primary" icon="Clock" v-hasPermi="['workflow:model:list']" @click="openHistory(scope.row)" />
          </el-tooltip>
          <el-tooltip content="部署" placement="top">
            <el-button link type="success" icon="Promotion" :disabled="scope.row.deployed" v-hasPermi="['workflow:model:deploy']" @click="deploy(scope.row)" />
          </el-tooltip>
          <el-tooltip content="删除" placement="top">
            <el-button link type="danger" icon="Delete" :disabled="scope.row.deployed" v-hasPermi="['workflow:model:remove']" @click="removeModels(scope.row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadList" />

    <el-dialog v-model="dialogOpen" :title="form.modelId ? '修改流程模型' : '新增流程模型'" width="620px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="模型名称" prop="modelName">
          <el-input v-model="form.modelName" maxlength="255" show-word-limit />
        </el-form-item>
        <el-form-item label="模型标识" prop="modelKey">
          <el-input v-model="form.modelKey" maxlength="128" :disabled="Boolean(form.modelId)" />
        </el-form-item>
        <el-form-item label="流程分类" prop="category">
          <el-select v-model="form.category" filterable style="width: 100%">
            <el-option v-for="item in categoryOptions" :key="item.code" :label="item.categoryName" :value="item.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="表单方式" prop="formType">
          <el-radio-group v-model="form.formType">
            <el-radio-button :value="0">流程表单</el-radio-button>
            <el-radio-button :value="1">外置表单</el-radio-button>
            <el-radio-button :value="2">节点表单</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.formType === 0" label="流程表单" prop="formId">
          <el-select v-model="form.formId" filterable style="width: 100%" placeholder="请选择发起表单">
            <el-option v-for="item in formOptions" :key="item.formId" :label="item.formName" :value="item.formId" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="historyOpen" :title="`${historyModelName} - 版本历史`" width="880px" append-to-body>
      <el-table v-loading="historyLoading" :data="historyRows">
        <el-table-column label="版本" prop="version" width="86" align="center" />
        <el-table-column label="模型名称" prop="modelName" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="96" align="center">
          <template #default="scope"><el-tag :type="scope.row.deployed ? 'success' : 'info'">{{ scope.row.deployed ? '已部署' : '未部署' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="scope">{{ parseTime(scope.row.lastUpdateTime || scope.row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="128" align="center">
          <template #default="scope">
            <el-tooltip content="查看或复制设计" placement="top"><el-button link type="primary" icon="View" v-hasPermi="['workflow:model:designer']" @click="openDesigner(scope.row)" /></el-tooltip>
            <el-tooltip content="设为最新版本" placement="top"><el-button link type="success" icon="Top" v-hasPermi="['workflow:model:save']" @click="promote(scope.row)" /></el-tooltip>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="historyTotal > 0" :total="historyTotal" v-model:page="historyQuery.pageNum" v-model:limit="historyQuery.pageSize" @pagination="loadHistory" />
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowModel">
import { listAllCategories } from '@/api/workflow/category'
import { listForms } from '@/api/workflow/form'
import {
  createModel,
  deleteModels,
  deployModel,
  getModel,
  listModelHistory,
  listModels,
  promoteModel,
  updateModel
} from '@/api/workflow/model'

const router = useRouter()
const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const showSearch = ref(true)
const dialogOpen = ref(false)
const historyOpen = ref(false)
const historyLoading = ref(false)
const rows = ref([])
const total = ref(0)
const selectedIds = ref([])
const categoryOptions = ref([])
const formOptions = ref([])
const historyRows = ref([])
const historyTotal = ref(0)
const historyModelName = ref('')
const queryParams = reactive({ pageNum: 1, pageSize: 10, modelName: '', modelKey: '', category: '' })
const historyQuery = reactive({ pageNum: 1, pageSize: 10, modelKey: '' })
const form = reactive(createEmptyForm())
const formRef = ref(null)
// 页面由页签 keep-alive 缓存；首次请求完成后，每次重新进入都必须查询服务端最新模型状态。
let pageInitialized = false
const rules = {
  modelName: [{ required: true, message: '模型名称不能为空', trigger: 'blur' }],
  modelKey: [
    { required: true, message: '模型标识不能为空', trigger: 'blur' },
    { pattern: /^[A-Za-z_][A-Za-z0-9_.-]{0,127}$/, message: '模型标识格式不合法', trigger: 'blur' }
  ],
  category: [{ required: true, message: '流程分类不能为空', trigger: 'change' }],
  formType: [{ required: true, message: '表单方式不能为空', trigger: 'change' }],
  formId: [{ validator: validateFormId, trigger: 'change' }]
}

/**
 * 创建空模型编辑对象。
 * @returns {object} 新增模型的稳定初始值。
 */
function createEmptyForm() {
  return { modelId: undefined, modelName: '', modelKey: '', category: '', description: '', formType: 0, formId: undefined }
}

/**
 * 校验流程表单模式必须选择有效模板。
 * @param {object} rule Element Plus 校验规则对象。
 * @param {number|string|undefined} value 当前表单主键。
 * @param {Function} callback 校验结果回调。
 * @returns {void} 通过或返回业务错误。
 */
function validateFormId(rule, value, callback) {
  if (form.formType === 0 && !value) callback(new Error('流程表单不能为空'))
  else callback()
}

/**
 * 分页加载每个模型标识的最新版本。
 * @returns {Promise<void>} 查询完成后更新模型列表和总数。
 */
async function loadList() {
  loading.value = true
  try {
    const response = await listModels(queryParams)
    rows.value = response.rows || []
    total.value = response.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 加载模型编辑需要的全部有效分类和表单选项。
 * @returns {Promise<void>} 基础选项加载完成后写入页面状态。
 */
async function loadOptions() {
  const [categoryResponse, firstFormPage] = await Promise.all([
    listAllCategories(),
    listForms({ pageNum: 1, pageSize: 50 })
  ])
  categoryOptions.value = categoryResponse.data || []
  const allForms = [...(firstFormPage.rows || [])]
  const pageCount = Math.ceil((firstFormPage.total || 0) / 50)
  for (let pageNum = 2; pageNum <= pageCount; pageNum += 1) {
    const page = await listForms({ pageNum, pageSize: 50 })
    allForms.push(...(page.rows || []))
  }
  formOptions.value = allForms
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
 * 获取表单模式对应的中文标签。
 * @param {number} type 后端表单模式编码。
 * @returns {string} 稳定显示标签。
 */
function formTypeLabel(type) {
  return ({ 0: '流程表单', 1: '外置表单', 2: '节点表单' })[type] || '-'
}

/**
 * 记录可批量删除的未部署模型选择。
 * @param {Array<object>} selection 当前表格选中行。
 * @returns {void} 更新已选择主键。
 */
function handleSelectionChange(selection) {
  selectedIds.value = selection.map(item => item.modelId)
}

/**
 * 限制已部署模型不能进入批量物理删除操作。
 * @param {object} row 模型列表行。
 * @returns {boolean} true 表示允许选择。
 */
function canSelectModel(row) {
  return !row.deployed
}

/**
 * 从第一页执行当前模型条件查询。
 * @returns {void} 无返回值。
 */
function handleQuery() {
  queryParams.pageNum = 1
  loadList()
}

/**
 * 重置查询条件并刷新列表。
 * @returns {void} 无返回值。
 */
function resetQuery() {
  proxy.resetForm('queryRef')
  handleQuery()
}

/**
 * 使用最新分类和表单选项打开新增模型对话框。
 * @returns {Promise<void>} 基础选项刷新成功后清空旧编辑状态并显示对话框。
 */
async function openCreate() {
  // 模型页会被页签缓存；打开对话框前重新查询，确保刚新增的分类无需刷新页面即可选择。
  await loadOptions()
  Object.assign(form, createEmptyForm())
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 查询真实模型详情并打开元数据修改对话框。
 * @param {object|undefined} row 模型行；为空时使用单选主键。
 * @returns {Promise<void>} 详情加载后显示对话框。
 */
async function openEdit(row) {
  const modelId = row?.modelId || selectedIds.value[0]
  // 详情与选项并行刷新，既保证分类数据最新，也避免串行请求拖慢编辑入口。
  const [response] = await Promise.all([getModel(modelId), loadOptions()])
  Object.assign(form, createEmptyForm(), response.data)
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 新增或修改未部署模型元数据。
 * @returns {Promise<void>} 后端成功后关闭对话框并刷新列表。
 */
async function submitForm() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = {
      ...form,
      modelName: form.modelName.trim(),
      modelKey: form.modelKey.trim(),
      category: form.category.trim(),
      description: form.description?.trim() || undefined,
      formId: form.formType === 0 ? form.formId : undefined
    }
    if (form.modelId) await updateModel(payload)
    else await createModel(payload)
    proxy.$modal.msgSuccess('流程模型保存成功')
    dialogOpen.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

/**
 * 进入受权限控制的 BPMN 模型设计页。
 * @param {object} row 模型列表或历史行。
 * @returns {void} 通过命名路由传递真实模型主键。
 */
function openDesigner(row) {
  historyOpen.value = false
  router.push({ name: 'WorkflowModelDesign', params: { modelId: row.modelId } })
}

/**
 * 打开指定模型标识的版本历史并加载第一页。
 * @param {object} row 最新模型行。
 * @returns {Promise<void>} 历史查询完成后显示对话框。
 */
async function openHistory(row) {
  historyQuery.modelKey = row.modelKey
  historyQuery.pageNum = 1
  historyModelName.value = row.modelName
  historyOpen.value = true
  await loadHistory()
}

/**
 * 分页查询当前模型标识的全部历史版本。
 * @returns {Promise<void>} 更新历史列表和总数。
 */
async function loadHistory() {
  historyLoading.value = true
  try {
    const response = await listModelHistory(historyQuery)
    historyRows.value = response.rows || []
    historyTotal.value = response.total || 0
  } finally {
    historyLoading.value = false
  }
}

/**
 * 将历史版本复制为新的最高模型版本。
 * @param {object} row 待提升的历史模型行。
 * @returns {Promise<void>} 成功后刷新历史和主列表。
 */
async function promote(row) {
  await proxy.$modal.confirm(`确认将 V${row.version} 复制为新的最新版本吗？`)
  await promoteModel(row.modelId)
  proxy.$modal.msgSuccess('已创建新的最新版本')
  await Promise.all([loadHistory(), loadList()])
}

/**
 * 部署未发布模型并固化全部节点表单快照。
 * @param {object} row 待部署模型行。
 * @returns {Promise<void>} 部署成功后刷新列表状态。
 */
async function deploy(row) {
  await proxy.$modal.confirm(`确认部署流程模型“${row.modelName}”V${row.version}吗？`)
  await deployModel(row.modelId)
  proxy.$modal.msgSuccess('流程模型部署成功')
  await loadList()
}

/**
 * 删除单条或已勾选的未部署模型。
 * @param {object|undefined} row 模型行；为空时使用勾选主键。
 * @returns {Promise<void>} 引用校验通过并删除后刷新列表。
 */
async function removeModels(row) {
  const ids = row?.modelId ? [row.modelId] : selectedIds.value
  await proxy.$modal.confirm(`确认删除选中的 ${ids.length} 个流程模型吗？`)
  await deleteModels(ids)
  proxy.$modal.msgSuccess('流程模型删除成功')
  await loadList()
}

/**
 * 导出当前过滤条件内的有界模型元数据。
 * @returns {void} 下载由全局真实接口方法处理。
 */
function exportModels() {
  proxy.download('/workflow/model/export', { ...queryParams }, `workflow_model_${Date.now()}.xlsx`)
}

const initialLoad = Promise.all([loadOptions(), loadList()]).finally(() => {
  pageInitialized = true
})

/**
 * 页签重新激活时刷新模型、分类和表单，避免跨页面更新后继续展示旧缓存。
 * @returns {Promise<void>} 首次加载后重新查询全部当前页面数据。
 */
onActivated(async () => {
  if (!pageInitialized) return initialLoad
  await Promise.all([loadOptions(), loadList()])
})
</script>

<style scoped>
.workflow-page :deep(.el-form--inline .el-form-item) { margin-right: 18px; }
</style>
