<template>
  <div class="app-container workflow-page">
    <el-form ref="queryRef" :model="queryParams" inline v-show="showSearch" label-width="72px">
      <el-form-item label="表单名称" prop="formName"><el-input v-model="queryParams.formName" clearable placeholder="请输入表单名称" @keyup.enter="handleQuery" /></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button><el-button icon="Refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" v-hasPermi="['workflow:form:add']" @click="designForm()">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="selectedIds.length !== 1" v-hasPermi="['workflow:form:edit']" @click="designForm(selectedIds[0])">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="danger" plain icon="Delete" :disabled="!selectedIds.length" v-hasPermi="['workflow:form:remove']" @click="removeForms()">删除</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Download" v-hasPermi="['workflow:form:export']" @click="exportForms">导出</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="loadList" />
    </el-row>
    <el-table v-loading="loading" :data="rows" @selection-change="selection => selectedIds = selection.map(item => item.formId)">
      <el-table-column type="selection" width="52" align="center" />
      <el-table-column label="表单名称" prop="formName" min-width="220" show-overflow-tooltip />
      <el-table-column label="备注" prop="remark" min-width="260" show-overflow-tooltip />
      <el-table-column label="更新时间" prop="updateTime" width="170"><template #default="scope">{{ parseTime(scope.row.updateTime || scope.row.createTime) }}</template></el-table-column>
      <el-table-column label="操作" width="150" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip content="预览" placement="top"><el-button link type="primary" icon="View" v-hasPermi="['workflow:form:query']" @click="previewForm(scope.row)" /></el-tooltip>
          <el-tooltip content="修改" placement="top"><el-button link type="primary" icon="Edit" v-hasPermi="['workflow:form:edit']" @click="designForm(scope.row.formId)" /></el-tooltip>
          <el-tooltip content="删除" placement="top"><el-button link type="danger" icon="Delete" v-hasPermi="['workflow:form:remove']" @click="removeForms(scope.row)" /></el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadList" />

    <el-dialog v-model="previewOpen" :title="previewTitle" width="760px" append-to-body>
      <ProcessFormRenderer v-if="previewContent" :content="previewContent" readonly @error="showComponentError" />
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowForm">
import { deleteForms, getForm, listForms } from '@/api/workflow/form'
import ProcessFormRenderer from '@/components/workflow/ProcessFormRenderer.vue'

const router = useRouter()
const { proxy } = getCurrentInstance()
const loading = ref(false)
const showSearch = ref(true)
const rows = ref([])
const total = ref(0)
const selectedIds = ref([])
const previewOpen = ref(false)
const previewTitle = ref('表单预览')
const previewContent = ref('')
const queryParams = reactive({ pageNum: 1, pageSize: 10, formName: '' })

/**
 * 分页加载有效流程表单。
 * @returns {Promise<void>} 加载完成后更新列表。
 */
async function loadList() {
  loading.value = true
  try {
    const response = await listForms(queryParams)
    rows.value = response.rows
    total.value = response.total
  } finally {
    loading.value = false
  }
}

/**
 * 从第一页执行条件查询。
 * @returns {void} 无返回值。
 */
function handleQuery() { queryParams.pageNum = 1; loadList() }

/**
 * 重置查询条件并刷新列表。
 * @returns {void} 无返回值。
 */
function resetQuery() { proxy.resetForm('queryRef'); handleQuery() }

/**
 * 进入复用 Vue 3 生成器的工作流设计模式。
 * @param {number|string|undefined} formId 可选流程表单主键。
 * @returns {void} 无返回值。
 */
function designForm(formId) {
  router.push({ path: '/workflow/form-design', query: { workflow: '1', ...(formId ? { formId } : {}) } })
}

/**
 * 查询最新可编辑正文并只读预览。
 * @param {object} row 表单列表行。
 * @returns {Promise<void>} 正文加载后打开预览。
 */
async function previewForm(row) {
  const response = await getForm(row.formId)
  previewTitle.value = `${response.data.formName} - 预览`
  previewContent.value = response.data.content
  previewOpen.value = true
}

/**
 * 经模型和部署快照引用检查后删除表单。
 * @param {object|undefined} row 表格行；为空时使用勾选主键。
 * @returns {Promise<void>} 删除成功后刷新列表。
 */
async function removeForms(row) {
  const ids = row?.formId ? [row.formId] : selectedIds.value
  await proxy.$modal.confirm(`确认删除选中的 ${ids.length} 个流程表单吗？`)
  await deleteForms(ids)
  proxy.$modal.msgSuccess('流程表单删除成功')
  await loadList()
}

/**
 * 导出不含 JSON 正文的当前表单摘要。
 * @returns {void} 无返回值。
 */
function exportForms() { proxy.download('/workflow/form/export', { ...queryParams }, `workflow_form_${Date.now()}.xlsx`) }

/**
 * 显示公共组件返回的稳定错误。
 * @param {Error} error 组件错误。
 * @returns {void} 无返回值。
 */
function showComponentError(error) { proxy.$modal.msgError(error?.message || '表单预览失败') }

onActivated(loadList)
loadList()
</script>
