<template>
  <div class="app-container workflow-page">
    <el-form ref="queryRef" :model="queryParams" inline v-show="showSearch" label-width="72px">
      <el-form-item label="分类名称" prop="categoryName">
        <el-input v-model="queryParams.categoryName" clearable placeholder="请输入分类名称" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="分类编码" prop="code">
        <el-input v-model="queryParams.code" clearable placeholder="请输入分类编码" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" v-hasPermi="['workflow:category:add']" @click="openCreate">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="selectedIds.length !== 1" v-hasPermi="['workflow:category:edit']" @click="openEdit()">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="danger" plain icon="Delete" :disabled="!selectedIds.length" v-hasPermi="['workflow:category:remove']" @click="removeCategories()">删除</el-button></el-col>
      <el-col :span="1.5"><el-button type="warning" plain icon="Download" v-hasPermi="['workflow:category:export']" @click="exportCategories">导出</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="loadList" />
    </el-row>

    <el-table v-loading="loading" :data="rows" @selection-change="selection => selectedIds = selection.map(item => item.categoryId)">
      <el-table-column type="selection" width="52" align="center" />
      <el-table-column label="分类名称" prop="categoryName" min-width="180" show-overflow-tooltip />
      <el-table-column label="分类编码" prop="code" min-width="180" show-overflow-tooltip />
      <el-table-column label="备注" prop="remark" min-width="220" show-overflow-tooltip />
      <el-table-column label="创建时间" prop="createTime" width="170"><template #default="scope">{{ parseTime(scope.row.createTime) }}</template></el-table-column>
      <el-table-column label="操作" width="120" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip content="修改" placement="top"><el-button link type="primary" icon="Edit" v-hasPermi="['workflow:category:edit']" @click="openEdit(scope.row)" /></el-tooltip>
          <el-tooltip content="删除" placement="top"><el-button link type="danger" icon="Delete" v-hasPermi="['workflow:category:remove']" @click="removeCategories(scope.row)" /></el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadList" />

    <el-dialog v-model="dialogOpen" :title="form.categoryId ? '修改流程分类' : '新增流程分类'" width="520px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="分类名称" prop="categoryName"><el-input v-model="form.categoryName" maxlength="64" show-word-limit /></el-form-item>
        <el-form-item label="分类编码" prop="code"><el-input v-model="form.code" maxlength="64" :disabled="Boolean(form.categoryId)" /></el-form-item>
        <el-form-item label="备注" prop="remark"><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowCategory">
import { createCategory, deleteCategories, getCategory, listCategories, updateCategory } from '@/api/workflow/category'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const showSearch = ref(true)
const dialogOpen = ref(false)
const rows = ref([])
const total = ref(0)
const selectedIds = ref([])
const queryParams = reactive({ pageNum: 1, pageSize: 10, categoryName: '', code: '' })
const form = reactive(createEmptyForm())
const rules = {
  categoryName: [{ required: true, message: '分类名称不能为空', trigger: 'blur' }],
  code: [
    { required: true, message: '分类编码不能为空', trigger: 'blur' },
    { pattern: /^[A-Za-z_][A-Za-z0-9_.-]{0,63}$/, message: '分类编码格式不合法', trigger: 'blur' }
  ]
}
const formRef = ref(null)

/**
 * 创建空分类编辑模型。
 * @returns {object} 新增分类初始值。
 */
function createEmptyForm() {
  return { categoryId: undefined, categoryName: '', code: '', remark: '' }
}

/**
 * 分页加载未删除流程分类。
 * @returns {Promise<void>} 列表加载完成后更新 rows 和 total。
 */
async function loadList() {
  loading.value = true
  try {
    const response = await listCategories(queryParams)
    rows.value = response.rows
    total.value = response.total
  } finally {
    loading.value = false
  }
}

/**
 * 按当前条件从第一页查询。
 * @returns {void} 无返回值。
 */
function handleQuery() {
  queryParams.pageNum = 1
  loadList()
}

/**
 * 重置搜索条件并重新查询。
 * @returns {void} 无返回值。
 */
function resetQuery() {
  proxy.resetForm('queryRef')
  handleQuery()
}

/**
 * 打开新增分类对话框。
 * @returns {void} 无返回值。
 */
function openCreate() {
  Object.assign(form, createEmptyForm())
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 加载真实分类详情并打开修改对话框。
 * @param {object|undefined} row 表格行；为空时使用单选主键。
 * @returns {Promise<void>} 详情加载完成后打开对话框。
 */
async function openEdit(row) {
  const categoryId = row?.categoryId || selectedIds.value[0]
  const response = await getCategory(categoryId)
  Object.assign(form, createEmptyForm(), response.data)
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 新增或修改流程分类。
 * @returns {Promise<void>} 后端成功后关闭对话框并刷新列表。
 */
async function submitForm() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload = { ...form, categoryName: form.categoryName.trim(), code: form.code.trim(), remark: form.remark?.trim() || undefined }
    if (form.categoryId) await updateCategory(payload)
    else await createCategory(payload)
    proxy.$modal.msgSuccess('流程分类保存成功')
    dialogOpen.value = false
    await loadList()
  } finally {
    saving.value = false
  }
}

/**
 * 经引用检查后删除单条或批量分类。
 * @param {object|undefined} row 表格行；为空时使用勾选主键。
 * @returns {Promise<void>} 删除成功后刷新列表。
 */
async function removeCategories(row) {
  const ids = row?.categoryId ? [row.categoryId] : selectedIds.value
  await proxy.$modal.confirm(`确认删除选中的 ${ids.length} 个流程分类吗？`)
  await deleteCategories(ids)
  proxy.$modal.msgSuccess('流程分类删除成功')
  await loadList()
}

/**
 * 导出当前过滤条件内的有界分类列表。
 * @returns {void} 下载由全局真实接口方法处理。
 */
function exportCategories() {
  proxy.download('/workflow/category/export', { ...queryParams }, `workflow_category_${Date.now()}.xlsx`)
}

loadList()
</script>

<style scoped>
.workflow-page :deep(.el-form--inline .el-form-item) { margin-right: 18px; }
</style>
