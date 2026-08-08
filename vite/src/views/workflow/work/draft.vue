<template>
  <div class="app-container workflow-draft-list">
    <el-form ref="queryRef" :model="queryParams" inline v-show="showSearch" label-width="72px">
      <el-form-item label="流程名称" prop="processName">
        <el-input v-model="queryParams.processName" clearable placeholder="请输入流程名称" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="更新时间" prop="updatedRange">
        <el-date-picker
          v-model="queryParams.updatedRange"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ssZ"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 350px"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="Search" @click="handleQuery">搜索</el-button>
        <el-button icon="Refresh" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <right-toolbar v-model:showSearch="showSearch" @queryTable="loadList" />
    </el-row>

    <el-table v-loading="loading" :data="rows">
      <el-table-column label="流程名称" prop="processName" min-width="190" show-overflow-tooltip />
      <el-table-column label="流程版本" width="96" align="center">
        <template #default="scope">{{ processVersionText(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="业务主键" prop="businessKey" min-width="150" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.businessKey || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120" align="center">
        <template #default="scope">
          <el-tooltip :disabled="!scope.row.statusReason" :content="scope.row.statusReason" placement="top">
            <el-tag :type="draftStatusType(scope.row)">{{ draftStatusLabel(scope.row) }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="scope">{{ parseTime(scope.row.updatedTime || scope.row.updateTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip content="继续编辑" placement="top">
            <el-button link type="primary" icon="Edit" v-hasPermi="['workflow:process:draftQuery']" @click="continueDraft(scope.row)" />
          </el-tooltip>
          <el-tooltip content="删除草稿" placement="top">
            <el-button link type="danger" icon="Delete" v-hasPermi="['workflow:process:draftRemove']" @click="removeDraft(scope.row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadList" />
  </div>
</template>

<script setup name="WorkflowDraft">
import { deleteProcessDraft, listProcessDrafts } from '@/api/workflow/draft'

const router = useRouter()
const { proxy } = getCurrentInstance()
const queryRef = ref(null)
const loading = ref(false)
const showSearch = ref(true)
const rows = ref([])
const total = ref(0)
const queryParams = reactive(createQuery())

/**
 * 创建本人草稿列表的初始查询条件。
 * @returns {object} 分页、流程名称和更新时间范围查询模型。
 */
function createQuery() {
  return { pageNum: 1, pageSize: 10, processName: '', updatedRange: [] }
}

/**
 * 将页面更新时间范围转换为后端草稿查询 DTO 字段。
 * @returns {object} 不包含空值和页面临时字段的查询参数。
 */
function buildQuery() {
  const range = Array.isArray(queryParams.updatedRange) ? queryParams.updatedRange : []
  const query = {
    pageNum: queryParams.pageNum,
    pageSize: queryParams.pageSize,
    processName: queryParams.processName?.trim() || undefined,
    updatedAfter: range[0],
    updatedBefore: range[1]
  }
  return Object.fromEntries(Object.entries(query).filter(([, value]) => value !== undefined && value !== ''))
}

/**
 * 从后端加载当前登录用户自己的草稿分页。
 * @returns {Promise<void>} 查询完成后更新表格和总数。
 */
async function loadList() {
  loading.value = true
  try {
    const response = await listProcessDrafts(buildQuery())
    rows.value = response.rows || []
    total.value = response.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 从第一页执行当前草稿筛选。
 * @returns {void} 无返回值。
 */
function handleQuery() {
  queryParams.pageNum = 1
  loadList()
}

/**
 * 清空草稿筛选并重新读取服务端列表。
 * @returns {void} 无返回值。
 */
function resetQuery() {
  Object.assign(queryParams, createQuery())
  queryRef.value?.clearValidate()
  loadList()
}

/**
 * 读取服务端草稿主键，禁止使用流程定义主键代替对象授权标识。
 * @param {object} row 草稿列表行。
 * @returns {string} 草稿主键，不存在时为空字符串。
 */
function draftIdOf(row) {
  return String(row?.draftId || row?.id || '').trim()
}

/**
 * 进入按草稿对象授权的继续编辑页面。
 * @param {object} row 本人草稿列表行。
 * @returns {void} 无返回值。
 */
function continueDraft(row) {
  const draftId = draftIdOf(row)
  if (!draftId) {
    proxy.$modal.msgError('草稿主键不能为空')
    return
  }
  router.push({ name: 'WorkflowProcessDraftEdit', params: { draftId } })
}

/**
 * 按当前行乐观锁版本删除本人草稿并刷新列表。
 * @param {object} row 本人草稿列表行。
 * @returns {Promise<void>} 后端删除和附件引用清理成功后刷新列表。
 */
async function removeDraft(row) {
  const draftId = draftIdOf(row)
  if (!draftId || !Number.isInteger(Number(row?.revisionNo))) {
    proxy.$modal.msgError('草稿版本信息不完整，请刷新列表')
    return
  }
  await proxy.$modal.confirm(`确认删除草稿“${row.processName || draftId}”吗？`)
  try {
    await deleteProcessDraft(draftId, Number(row.revisionNo))
    proxy.$modal.msgSuccess('草稿删除成功')
  } catch (error) {
    if (Number(error?.code) === 409) proxy.$modal.msgWarning('草稿已被其他页面更新，列表已刷新')
  } finally {
    await loadList()
  }
}

/**
 * 格式化草稿绑定的流程定义版本。
 * @param {object} row 草稿列表行。
 * @returns {string} V 加版本号，未知时为短横线。
 */
function processVersionText(row) {
  const version = row?.version ?? row?.processDefinitionVersion ?? row?.definitionVersion
  return version === undefined || version === null ? '-' : `V${version}`
}

/**
 * 将服务端草稿稳定状态转换为中文标签。
 * @param {object} row 草稿列表行。
 * @returns {string} 可编辑、不可提交或已提交状态名称。
 */
function draftStatusLabel(row) {
  const status = String(row?.status || 'DRAFT').toUpperCase()
  if (status === 'SUBMITTED') return '已提交'
  if (status === 'DELETED') return '已删除'
  if (row?.submittable === false || ['EXPIRED', 'INVALID'].includes(status)) return '不可提交'
  return '草稿'
}

/**
 * 获取草稿状态对应的 Element Plus 标签类型。
 * @param {object} row 草稿列表行。
 * @returns {string} 标签类型。
 */
function draftStatusType(row) {
  const label = draftStatusLabel(row)
  if (label === '已提交') return 'success'
  if (label === '不可提交' || label === '已删除') return 'danger'
  return 'info'
}

loadList()
</script>

<style scoped>
.workflow-draft-list :deep(.el-tag) {
  max-width: 100%;
}
</style>
