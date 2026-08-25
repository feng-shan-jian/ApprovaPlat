<template>
  <div class="app-container workflow-process-list">
    <el-form ref="queryRef" :model="queryParams" inline v-show="showSearch" label-width="72px">
      <el-form-item v-if="isCopy" label="抄送标题" prop="title">
        <el-input v-model="queryParams.title" clearable placeholder="请输入抄送标题" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item v-if="mode === 'manage'" label="实例主键" prop="processInstanceId">
        <el-input v-model="queryParams.processInstanceId" clearable placeholder="请输入流程实例主键" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="流程名称" prop="processName">
        <el-input v-model="queryParams.processName" clearable placeholder="请输入流程名称" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item v-if="!isCopy" label="流程标识" prop="processKey">
        <el-input v-model="queryParams.processKey" clearable placeholder="请输入流程标识" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item v-if="isTaskMode" label="任务名称" prop="taskName">
        <el-input v-model="queryParams.taskName" clearable placeholder="请输入任务名称" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item v-if="mode === 'own' || mode === 'manage'" label="业务主键" prop="businessKey">
        <el-input v-model="queryParams.businessKey" clearable placeholder="请输入业务主键" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item v-if="mode === 'manage'" label="发起人" prop="startUserId">
        <el-select
          v-model="queryParams.startUserId"
          clearable
          filterable
          remote
          reserve-keyword
          :remote-method="searchManagedUsers"
          :loading="managedUserLoading"
          placeholder="搜索有效用户"
          style="width: 210px"
        >
          <el-option v-for="user in managedUserOptions" :key="user.value" :label="user.label" :value="String(user.value)" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="isCopy" label="流程发起人" prop="originatorName">
        <el-input v-model="queryParams.originatorName" clearable placeholder="请输入流程发起人" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item v-if="isCopy" label="阅读状态" prop="readStatus">
        <el-select v-model="queryParams.readStatus" placeholder="全部状态" style="width: 140px">
          <el-option label="全部" value="" />
          <el-option label="未读" value="0" />
          <el-option label="已读" value="1" />
        </el-select>
      </el-form-item>
      <el-form-item label="流程分类" :prop="isCopy ? 'categoryId' : 'category'">
        <el-select v-model="queryParams[isCopy ? 'categoryId' : 'category']" clearable filterable placeholder="全部分类" style="width: 180px">
          <el-option v-for="item in categoryOptions" :key="item.code" :label="item.categoryName" :value="item.code" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="hasDateRange" :label="dateRangeLabel" prop="dateRange">
        <el-date-picker
          v-model="queryParams.dateRange"
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
      <el-col :span="1.5">
        <el-button type="warning" plain icon="Download" v-hasPermi="[config.exportPermission]" @click="exportRows">导出</el-button>
      </el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="loadList" />
    </el-row>

    <el-table v-loading="loading" :data="rows">
      <el-table-column v-if="isCopy" label="抄送标题" prop="title" min-width="190" show-overflow-tooltip />
      <el-table-column label="流程名称" prop="processName" min-width="190" show-overflow-tooltip />
      <el-table-column v-if="!isCopy" label="流程标识" prop="processKey" min-width="170" show-overflow-tooltip />
      <el-table-column v-if="isTaskMode" label="任务名称" prop="taskName" min-width="150" show-overflow-tooltip />
      <el-table-column label="分类" min-width="120" show-overflow-tooltip>
        <template #default="scope">{{ categoryName(scope.row.category || scope.row.categoryId) }}</template>
      </el-table-column>
      <el-table-column v-if="mode === 'start'" label="版本" prop="version" width="78" align="center">
        <template #default="scope">V{{ scope.row.version }}</template>
      </el-table-column>
      <el-table-column v-if="mode === 'own' || mode === 'manage' || isTaskMode" label="业务主键" prop="businessKey" min-width="140" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.businessKey || '-' }}</template>
      </el-table-column>
      <el-table-column v-if="mode === 'own' || mode === 'manage'" label="当前环节" min-width="150" show-overflow-tooltip>
        <template #default="scope">{{ (scope.row.currentTaskNames || []).join('、') || '-' }}</template>
      </el-table-column>
      <el-table-column v-if="mode === 'own' || mode === 'manage'" label="状态" width="98" align="center">
        <template #default="scope"><el-tag :type="statusType(scope.row.processStatus)">{{ statusLabel(scope.row.processStatus) }}</el-tag></template>
      </el-table-column>
      <el-table-column v-if="mode === 'manage' || mode === 'todo' || mode === 'claim' || mode === 'finished'" label="发起人" prop="startUserName" min-width="120" show-overflow-tooltip />
      <el-table-column v-if="isCopy" label="流程发起人" prop="originatorName" min-width="120" show-overflow-tooltip />
      <el-table-column v-if="isCopy" label="来源" width="104" align="center">
        <template #default="scope">{{ copySourceLabel(scope.row.sourceType) }}</template>
      </el-table-column>
      <el-table-column v-if="isCopy" label="触发时机" min-width="180" show-overflow-tooltip>
        <template #default="scope">{{ copyTriggerText(scope.row) }}</template>
      </el-table-column>
      <el-table-column v-if="isCopy" label="阅读状态" width="100" align="center">
        <template #default="scope">
          <el-tag :type="scope.row.readStatus === '1' ? 'info' : 'warning'">
            {{ copyReadStatusLabel(scope.row.readStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="isCopy" label="首次阅读时间" width="170">
        <template #default="scope">{{ scope.row.readTime ? parseTime(scope.row.readTime) : '-' }}</template>
      </el-table-column>
      <el-table-column :label="timeColumnLabel" :prop="timeColumnProp" width="170">
        <template #default="scope">{{ parseTime(scope.row[timeColumnProp]) }}</template>
      </el-table-column>
      <el-table-column v-if="mode === 'finished' || mode === 'manage'" label="耗时" width="110" align="right">
        <template #default="scope">{{ durationText(scope.row.durationMillis) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="actionColumnWidth" align="center" fixed="right">
        <template #default="scope">
          <el-tooltip v-if="mode === 'start'" content="发起流程" placement="top">
            <el-button link type="primary" icon="Promotion" v-hasPermi="['workflow:process:start']" @click="openStart(scope.row)" />
          </el-tooltip>
          <el-tooltip v-if="mode !== 'start'" content="流程详情" placement="top">
            <el-button
              link
              type="primary"
              icon="View"
              v-hasPermi="['workflow:process:query']"
              :loading="isCopyOpening(scope.row)"
              :disabled="isCopyOpening(scope.row)"
              @click="openDetail(scope.row)"
            />
          </el-tooltip>
          <el-tooltip v-if="mode === 'claim'" content="认领任务" placement="top">
            <el-button link type="success" icon="Select" v-hasPermi="['workflow:process:claim']" @click="claim(scope.row)" />
          </el-tooltip>
          <el-tooltip v-if="mode === 'todo' && canUnclaim(scope.row)" content="取消认领" placement="top">
            <el-button link type="warning" icon="RefreshLeft" v-hasPermi="['workflow:process:claim']" @click="unclaim(scope.row)" />
          </el-tooltip>
          <el-tooltip v-if="mode === 'finished' && scope.row.revocable === true" content="撤回" placement="top">
            <el-button link type="warning" icon="RefreshLeft" v-hasPermi="['workflow:process:revoke']" @click="openAction('revoke', scope.row)" />
          </el-tooltip>
          <template v-if="mode === 'own'">
            <el-tooltip v-if="isActiveProcess(scope.row)" content="取消流程" placement="top">
              <el-button link type="warning" icon="CircleClose" v-hasPermi="['workflow:process:cancel']" @click="openAction('cancel', scope.row)" />
            </el-tooltip>
          </template>
          <template v-if="mode === 'own' || mode === 'manage'">
            <el-tooltip v-if="supportsInstanceStateToggle(scope.row)" :content="scope.row.processStatus === 'suspended' ? '激活实例' : '挂起实例'" placement="top">
              <el-button link :type="scope.row.processStatus === 'suspended' ? 'success' : 'warning'" :icon="scope.row.processStatus === 'suspended' ? 'VideoPlay' : 'VideoPause'" v-hasPermi="['workflow:process:state']" @click="toggleInstanceState(scope.row)" />
            </el-tooltip>
            <el-tooltip v-if="isActiveProcess(scope.row)" content="终止实例" placement="top">
              <el-button link type="danger" icon="SwitchButton" v-hasPermi="['workflow:process:terminate']" @click="openAction('terminate', scope.row)" />
            </el-tooltip>
            <el-tooltip v-if="!isActiveProcess(scope.row)" content="删除历史" placement="top">
              <el-button link type="danger" icon="Delete" v-hasPermi="['workflow:process:remove']" @click="removeHistory(scope.row)" />
            </el-tooltip>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadList" />

    <el-dialog
      v-model="actionDialog.open"
      :title="actionDialogTitle"
      width="520px"
      append-to-body
      :show-close="!actionExecuting"
      :close-on-click-modal="!actionExecuting"
      :close-on-press-escape="!actionExecuting"
    >
      <el-form ref="actionFormRef" :model="actionDialog" :rules="actionRules" label-width="88px">
        <el-form-item :label="actionCommentLabel" prop="comment">
          <el-input v-model="actionDialog.comment" type="textarea" :rows="4" maxlength="500" show-word-limit />
        </el-form-item>
        <el-alert
          v-if="actionDialog.error"
          type="warning"
          :title="actionDialog.error"
          show-icon
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button :disabled="actionExecuting" @click="actionDialog.open = false">取消</el-button>
        <el-button
          :type="actionDialog.type === 'terminate' ? 'danger' : 'primary'"
          :loading="actionExecuting"
          :disabled="actionDialog.stale"
          @click="submitAction"
        >确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowProcessList">
import { listAllCategories } from '@/api/workflow/category'
import { listIdentityOptions } from '@/api/workflow/identity'
import { changeProcessInstanceState, terminateProcessInstance } from '@/api/workflow/instance'
import {
  deleteProcessInstances,
  listAssignedTasks,
  listClaimableTasks,
  listCompletedTasks,
  listCopiedProcesses,
  markCopyRead,
  listManagedProcesses,
  listOwnedProcesses,
  listStartableProcesses
} from '@/api/workflow/process'
import { cancelProcess, claimTask, revokeTask, unclaimTask } from '@/api/workflow/task'

const props = defineProps({
  /** 列表业务模式，决定真实查询、字段、权限和动作。 */
  mode: {
    type: String,
    required: true,
    validator: value => ['start', 'own', 'manage', 'todo', 'claim', 'finished', 'copy'].includes(value)
  }
})

// 将七种工作台模式固定映射到真实查询接口、导出入口、权限码和下载文件名，禁止由路由参数拼接服务端地址。
const CONFIG = {
  start: { api: listStartableProcesses, exportUrl: '/workflow/process/startExport', exportPermission: 'workflow:process:startExport', file: 'startable_processes' },
  own: { api: listOwnedProcesses, exportUrl: '/workflow/process/ownExport', exportPermission: 'workflow:process:ownExport', file: 'owned_processes' },
  manage: { api: listManagedProcesses, exportUrl: '/workflow/process/manageExport', exportPermission: 'workflow:process:manageExport', file: 'managed_processes' },
  todo: { api: listAssignedTasks, exportUrl: '/workflow/process/todoExport', exportPermission: 'workflow:process:todoExport', file: 'todo_tasks' },
  claim: { api: listClaimableTasks, exportUrl: '/workflow/process/claimExport', exportPermission: 'workflow:process:claimExport', file: 'claimable_tasks' },
  finished: { api: listCompletedTasks, exportUrl: '/workflow/process/finishedExport', exportPermission: 'workflow:process:finishedExport', file: 'finished_tasks' },
  copy: { api: listCopiedProcesses, exportUrl: '/workflow/process/copyExport', exportPermission: 'workflow:process:copyExport', file: 'copied_processes' }
}

const router = useRouter()
const { proxy } = getCurrentInstance()
const config = computed(() => CONFIG[props.mode])
const loading = ref(false)
const showSearch = ref(true)
const rows = ref([])
// openingCopyIds 表示正在由服务端执行对象授权和首次已读原子更新的记录，禁止同一行并发跳转。
const openingCopyIds = ref([])
const total = ref(0)
const categoryOptions = ref([])
const managedUserOptions = ref([])
const managedUserLoading = ref(false)
// 远程身份检索序列号用于丢弃晚到响应，避免旧关键字结果覆盖当前筛选条件。
let managedUserSearchSequence = 0
const queryRef = ref(null)
const actionFormRef = ref(null)
const actionExecuting = ref(false)
const queryParams = reactive(createQuery())
// stale 表示服务端对象已经变化；保留用户原因供核对，但禁止继续提交旧行快照。
const actionDialog = reactive({ open: false, type: '', row: null, comment: '', error: '', stale: false })
const actionRules = { comment: [{ validator: validateActionComment, trigger: 'blur' }] }
const isCopy = computed(() => props.mode === 'copy')
const isTaskMode = computed(() => ['todo', 'claim', 'finished'].includes(props.mode))
const hasDateRange = computed(() => ['own', 'manage', 'todo', 'claim', 'finished'].includes(props.mode))
const dateRangeLabel = computed(() => ['own', 'manage'].includes(props.mode) ? '发起时间' : props.mode === 'finished' ? '完成时间' : '创建时间')
const timeColumnProp = computed(() => ({ start: 'deploymentTime', own: 'startTime', manage: 'startTime', todo: 'createTime', claim: 'createTime', finished: 'finishTime', copy: 'createTime' })[props.mode])
const timeColumnLabel = computed(() => ({ start: '部署时间', own: '发起时间', manage: '发起时间', todo: '创建时间', claim: '创建时间', finished: '完成时间', copy: '抄送时间' })[props.mode])
const actionColumnWidth = computed(() => ['own', 'manage'].includes(props.mode) ? 176 : props.mode === 'start' || props.mode === 'copy' ? 82 : 112)
const actionDialogTitle = computed(() => ({ cancel: '取消流程', terminate: '终止流程实例', revoke: '撤回已办任务' })[actionDialog.type] || '流程操作')
const actionCommentLabel = computed(() => actionDialog.type === 'terminate' ? '终止原因' : actionDialog.type === 'revoke' ? '撤回原因' : '取消原因')

/**
 * 按当前模式创建后端可接受的查询对象。
 * @returns {object} 包含分页、条件和页面日期范围的稳定对象。
 */
function createQuery() {
  const common = { pageNum: 1, pageSize: 10, processName: '' }
  if (props.mode === 'copy') return { ...common, title: '', originatorName: '', categoryId: '', readStatus: '' }
  const query = { ...common, processKey: '', category: '' }
  if (props.mode === 'own') return { ...query, businessKey: '', dateRange: [] }
  if (props.mode === 'manage') return { ...query, processInstanceId: '', businessKey: '', startUserId: '', dateRange: [] }
  if (['todo', 'claim', 'finished'].includes(props.mode)) return { ...query, taskName: '', dateRange: [] }
  return query
}

/**
 * 将页面查询转换为各后端 DTO 的精确字段，日期范围不会作为未知参数提交。
 * @returns {object} 可直接用于列表和导出接口的查询参数。
 */
function buildQuery() {
  const query = { ...queryParams }
  const dateRange = Array.isArray(query.dateRange) ? query.dateRange : []
  delete query.dateRange
  if (props.mode === 'own' || props.mode === 'manage') [query.startedAfter, query.startedBefore] = dateRange
  if (props.mode === 'todo' || props.mode === 'claim') [query.createdAfter, query.createdBefore] = dateRange
  if (props.mode === 'finished') [query.completedAfter, query.completedBefore] = dateRange
  return Object.fromEntries(Object.entries(query).filter(([, value]) => value !== '' && value !== undefined && value !== null))
}

/**
 * 分页加载当前模式下登录用户有权读取的真实数据。
 * @returns {Promise<void>} 查询结束后更新 rows 和 total。
 */
async function loadList() {
  loading.value = true
  try {
    const response = await config.value.api(buildQuery())
    rows.value = response.rows || []
    total.value = response.total || 0
  } finally {
    loading.value = false
  }
}

/**
 * 加载分类名称映射，不改变后端返回的分类编码。
 * @returns {Promise<void>} 选项加载后更新筛选和表格显示。
 */
async function loadCategories() {
  const response = await listAllCategories()
  categoryOptions.value = response.data || []
}

/**
 * 从后端有效身份目录检索流程发起人，过期响应不得覆盖较新的筛选结果。
 * @param {string} keyword 管理员输入的用户关键字。
 * @returns {Promise<void>} 当前检索仍为最新请求时更新用户选项。
 */
async function searchManagedUsers(keyword) {
  if (props.mode !== 'manage') return
  const sequence = ++managedUserSearchSequence
  managedUserLoading.value = true
  try {
    const response = await listIdentityOptions({ type: 'user', keyword: keyword?.trim() || undefined, pageNum: 1, pageSize: 20 })
    if (sequence === managedUserSearchSequence) managedUserOptions.value = response.rows || []
  } finally {
    if (sequence === managedUserSearchSequence) managedUserLoading.value = false
  }
}

/**
 * 获取分类编码对应的中文名称。
 * @param {string} code 流程分类编码。
 * @returns {string} 分类名称，未匹配时回显编码。
 */
function categoryName(code) {
  return categoryOptions.value.find(item => item.code === code)?.categoryName || code || '-'
}

/**
 * 从第一页执行当前筛选条件查询。
 * @returns {void} 无返回值。
 */
function handleQuery() {
  queryParams.pageNum = 1
  loadList()
}

/**
 * 重建当前模式查询条件并重新加载列表。
 * @returns {void} 无返回值。
 */
function resetQuery() {
  Object.assign(queryParams, createQuery())
  queryRef.value?.clearValidate()
  loadList()
}

/**
 * 进入部署快照表单驱动的流程发起页。
 * @param {object} row 可发起流程定义行。
 * @returns {void} 通过受控路由传递定义和部署关系。
 */
function openStart(row) {
  router.push({ name: 'WorkflowProcessStart', params: { definitionId: row.definitionId }, query: { deploymentId: row.deploymentId } })
}

/**
 * 进入对象授权的流程详情或任务办理页。
 * @param {object} row 流程实例、任务或抄送记录行。
 * @returns {Promise<void>} 抄送模式先等待后端原子已读和对象授权成功，再传递实例及可选任务主键。
 */
async function openDetail(row) {
  const instanceId = row.processInstanceId || row.instanceId
  // 抄送接收人只有流程实例级只读权限，不能把活动 taskId 带入详情页扩大到任务表单权限。
  const taskId = isCopy.value ? undefined : (row.taskId || undefined)
  if (!instanceId) {
    proxy.$modal.msgError('流程实例主键不能为空')
    return
  }
  const copyId = String(row.copyId || '').trim()
  if (isCopy.value) {
    if (!/^[1-9]\d*$/.test(copyId)) {
      proxy.$modal.msgError('抄送记录主键不合法')
      return
    }
    if (openingCopyIds.value.includes(copyId)) return
    openingCopyIds.value = [...openingCopyIds.value, copyId]
    try {
      // 无论列表快照是否已读，都由后端幂等接口复核接收人权限并保留首次阅读时间。
      await markCopyRead(copyId)
    } finally {
      openingCopyIds.value = openingCopyIds.value.filter(id => id !== copyId)
    }
  }
  await router.push({
    name: 'WorkflowProcessDetail',
    params: { instanceId },
    query: { source: props.mode, ...(taskId ? { taskId } : {}) }
  })
}

/**
 * 判断指定抄送行是否正在等待服务端首次阅读确认。
 * @param {object} row 当前抄送记录行。
 * @returns {boolean} 当前 copyId 已占用读取锁时返回 true。
 */
function isCopyOpening(row) {
  return isCopy.value && openingCopyIds.value.includes(String(row?.copyId || '').trim())
}

/**
 * 将抄送生成来源转换为稳定中文展示。
 * @param {string} sourceType 后端 MANUAL、AUTO 或合并去重后的 MANUAL_AUTO 来源枚举。
 * @returns {string} 用户可理解的来源名称；未知值返回短横线。
 */
function copySourceLabel(sourceType) {
  return ({ MANUAL: '手工抄送', AUTO: '自动抄送', MANUAL_AUTO: '手工 + 自动' })[sourceType] || '-'
}

/**
 * 拼接自动抄送触发时机和触发节点快照，手工抄送沿用动作触发名称。
 * @param {object} row 当前抄送记录行。
 * @returns {string} 触发类型中文名称及可选节点名称。
 */
function copyTriggerText(row) {
  const manualAction = String(row?.triggerType || '').replace(/^MANUAL_/, '')
  const trigger = ({
    COMPLETE: '办理完成',
    REJECT: '驳回',
    RETURN: '退回',
    DELEGATE: '委派',
    RESOLVE: '委派办结',
    TRANSFER: '转办',
    NODE_ARRIVED: '节点到达',
    NODE_COMPLETED: '节点完成',
    PROCESS_COMPLETED: '流程完成'
  })[manualAction] || row?.triggerType || '-'
  const node = row?.triggerNodeName || row?.triggerNodeId
  return node ? `${trigger} · ${node}` : trigger
}

/**
 * 将后端抄送阅读枚举转换为稳定中文文案。
 * @param {string} readStatus 0 未读、1 已读。
 * @returns {string} 对应阅读状态；未知值返回短横线。
 */
function copyReadStatusLabel(readStatus) {
  return ({ 0: '未读', 1: '已读' })[readStatus] || '-'
}

/**
 * 认领当前用户或其有效角色、部门的候选任务。
 * @param {object} row 待签任务行。
 * @returns {Promise<void>} 认领成功后刷新待签列表。
 */
async function claim(row) {
  await proxy.$modal.confirm(`确认认领任务“${row.taskName}”吗？`)
  await runListMutation(() => claimTask(row.taskId), '任务认领成功')
}

/**
 * 取消本人对候选任务的认领，直接指派任务会由后端拒绝。
 * @param {object} row 当前待办任务行。
 * @returns {Promise<void>} 取消认领成功后刷新待办列表。
 */
async function unclaim(row) {
  await proxy.$modal.confirm(`确认取消认领任务“${row.taskName}”吗？`)
  await runListMutation(() => unclaimTask(row.taskId), '已取消任务认领')
}

/**
 * 判断待办是否由当前办理人通过 Flowable claim 真实认领。
 * @param {object} row 当前用户待办任务行。
 * @returns {boolean} 认领人与办理人一致、认领时间存在且无委派 owner 时返回 true。
 */
function canUnclaim(row) {
  return Boolean(row?.claimTime)
    && String(row?.claimedById || '') === String(row?.assigneeId || '')
    && !row?.ownerId
}

/**
 * 判断流程实例是否仍保留活动执行树，可执行取消或管理员终止。
 * @param {object} row 我的流程或管理员运维实例行。
 * @returns {boolean} running、returned 或 suspended 状态返回 true。
 */
function isActiveProcess(row) {
  return ['running', 'returned', 'suspended'].includes(String(row.processStatus || '').toLowerCase())
}

/**
 * 判断流程实例是否允许在激活和挂起状态间切换。
 * @param {object} row 管理员运维实例行。
 * @returns {boolean} 仅 running 或 suspended 状态返回 true。
 */
function supportsInstanceStateToggle(row) {
  return ['running', 'suspended'].includes(String(row.processStatus || '').toLowerCase())
}

/**
 * 打开需要填写业务原因的流程动作对话框。
 * @param {'cancel'|'terminate'|'revoke'} type 动作类型。
 * @param {object} row 当前操作行。
 * @returns {void} 清空上一次原因后显示对话框。
 */
function openAction(type, row) {
  if (actionExecuting.value) return
  if (!['cancel', 'terminate', 'revoke'].includes(type)) {
    proxy.$modal.msgWarning('流程动作不合法')
    return
  }
  if (type === 'revoke' && row?.revocable !== true) {
    proxy.$modal.msgWarning('当前已办任务不可撤回')
    return
  }
  Object.assign(actionDialog, { open: true, type, row, comment: '', error: '', stale: false })
  nextTick(() => actionFormRef.value?.clearValidate())
}

/**
 * 校验取消、终止和撤回原因的真实文本边界，拒绝仅包含空白字符的输入。
 * @param {object} _rule Element Plus 表单规则上下文，本校验不读取该对象。
 * @param {unknown} value 当前原因输入值。
 * @param {(error?: Error) => void} callback 异步校验结果回调。
 * @returns {void} 合法时无参数回调，非法时返回稳定中文错误。
 */
function validateActionComment(_rule, value, callback) {
  const comment = String(value || '').trim()
  if (!comment) {
    callback(new Error('操作原因不能为空'))
    return
  }
  if (comment.length > 500) {
    callback(new Error('操作原因不能超过500个字符'))
    return
  }
  callback()
}

/**
 * 提交取消、管理员终止或已办撤回动作。
 * @returns {Promise<void>} 后端原子动作成功后关闭对话框并刷新列表。
 */
async function submitAction() {
  if (actionExecuting.value) return
  if (actionDialog.stale) {
    actionDialog.error = '服务端状态已刷新，请关闭窗口后重新选择操作对象'
    return
  }
  const type = actionDialog.type
  if (!['cancel', 'terminate', 'revoke'].includes(type)) {
    actionDialog.error = '流程动作不合法'
    return
  }
  if (type === 'revoke' && actionDialog.row?.revocable !== true) {
    actionDialog.error = '当前已办任务不可撤回，请刷新列表'
    return
  }
  // 校验 Promise 完成前即占用动作锁，阻止快速双击并发提交同一状态迁移命令。
  actionExecuting.value = true
  actionDialog.error = ''
  try {
    const valid = await actionFormRef.value.validate().catch(() => false)
    if (!valid) return
    const row = actionDialog.row
    const comment = actionDialog.comment.trim()
    if (type === 'cancel') await cancelProcess({ processInstanceId: row.processInstanceId, comment })
    else if (type === 'terminate') await terminateProcessInstance({ instanceId: row.processInstanceId, reason: comment })
    else await revokeTask({ processInstanceId: row.processInstanceId, taskId: row.taskId, comment })
    proxy.$modal.msgSuccess('流程操作成功')
    actionDialog.open = false
    await loadList()
  } catch (error) {
    // 失败保留真实动作参数供用户修正或重试，统一请求层仍负责全局错误通知。
    const stale = await refreshListAfterActionConflict(error)
    actionDialog.stale = stale
    const message = requestErrorMessage(error, '流程操作失败')
    actionDialog.error = stale ? `${message}；服务端状态已刷新，请关闭窗口后重新选择` : message
  } finally {
    actionExecuting.value = false
  }
}

/**
 * 提取不超过页面展示边界的稳定请求错误，避免把未知响应正文直接写入操作弹窗。
 * @param {unknown} error Axios 错误、统一业务错误或普通 Error。
 * @param {string} fallback 服务端未提供安全消息时的兜底文案。
 * @returns {string} 可供操作弹窗展示的短错误文本。
 */
function requestErrorMessage(error, fallback) {
  const responseMessage = String(error?.response?.data?.msg || '').trim()
  const errorMessage = typeof error?.message === 'string' ? error.message.trim() : ''
  return (responseMessage || errorMessage || fallback).slice(0, 200)
}

/**
 * 判断写动作失败是否说明当前页面对象快照已经失效。
 * @param {unknown} error 统一 BusinessError 或 Axios 传输异常。
 * @returns {boolean} 权限对象消失、资源不存在或状态冲突时返回 true。
 */
function isStaleActionError(error) {
  const code = Number(error?.code || error?.response?.data?.code || error?.response?.status)
  return [403, 404, 409].includes(code)
}

/**
 * 状态冲突后回读当前工作台，避免继续展示可重复点击的过期任务或实例。
 * @param {unknown} error 当前写动作返回的业务异常。
 * @returns {Promise<boolean>} 已识别并尝试刷新过期快照时返回 true。
 */
async function refreshListAfterActionConflict(error) {
  if (!isStaleActionError(error)) return false
  // 原始业务错误已由请求层提示；刷新失败也由请求层提示，不能用第二个异常覆盖动作语义。
  await loadList().catch(() => undefined)
  return true
}

/**
 * 执行无需保留表单输入的列表写动作，并统一处理成功刷新与冲突刷新。
 * @param {() => Promise<unknown>} mutation 认领、取消认领、状态切换或删除的真实 API 调用。
 * @param {string} successMessage 写动作成功后的用户提示。
 * @returns {Promise<boolean>} 写动作成功时返回 true，失败并完成必要刷新时返回 false。
 */
async function runListMutation(mutation, successMessage) {
  try {
    await mutation()
  } catch (error) {
    const stale = await refreshListAfterActionConflict(error)
    if (!stale) throw error
    return false
  }
  proxy.$modal.msgSuccess(successMessage)
  await loadList()
  return true
}

/**
 * 由流程管理员在活动和挂起状态间切换实例。
 * @param {object} row 我的流程或管理员运维实例行。
 * @returns {Promise<void>} 状态切换成功后刷新列表。
 */
async function toggleInstanceState(row) {
  const state = row.processStatus === 'suspended' ? 'active' : 'suspended'
  const action = state === 'active' ? '激活' : '挂起'
  await proxy.$modal.confirm(`确认${action}流程实例吗？`)
  await runListMutation(
    () => changeProcessInstanceState({ instanceId: row.processInstanceId, state }),
    `流程实例已${action}`
  )
}

/**
 * 删除已结束且没有正式业务引用的流程历史。
 * @param {object} row 已结束流程实例行。
 * @returns {Promise<void>} 服务端状态和引用检查通过后刷新列表。
 */
async function removeHistory(row) {
  await proxy.$modal.confirm(`确认删除流程“${row.processName}”的历史记录吗？`)
  await runListMutation(() => deleteProcessInstances(row.processInstanceId), '流程历史删除成功')
}

/**
 * 导出当前模式和筛选条件下的有界对象授权数据。
 * @returns {void} 下载由全局真实接口方法处理。
 */
function exportRows() {
  proxy.download(config.value.exportUrl, buildQuery(), `${config.value.file}_${Date.now()}.xlsx`)
}

/**
 * 获取后端流程状态的中文标签。
 * @param {string} status 稳定流程状态编码。
 * @returns {string} 中文状态名称或原状态。
 */
function statusLabel(status) {
  return ({ running: '运行中', returned: '待修改', suspended: '已挂起', completed: '已完成', rejected: '已驳回', terminated: '已终止', canceled: '已取消' })[status] || status || '-'
}

/**
 * 获取流程状态对应的 Element Plus 标签类型。
 * @param {string} status 稳定流程状态编码。
 * @returns {string} 标签视觉类型。
 */
function statusType(status) {
  return ({ running: 'primary', returned: 'warning', suspended: 'warning', completed: 'success', rejected: 'danger', terminated: 'danger', canceled: 'info' })[status] || 'info'
}

/**
 * 将毫秒耗时转换为紧凑可读文本。
 * @param {number|null|undefined} value 毫秒耗时。
 * @returns {string} 天、小时、分钟或秒文本。
 */
function durationText(value) {
  if (value === null || value === undefined) return '-'
  const seconds = Math.max(0, Math.floor(Number(value) / 1000))
  if (seconds >= 86400) return `${Math.floor(seconds / 86400)}天`
  if (seconds >= 3600) return `${Math.floor(seconds / 3600)}小时`
  if (seconds >= 60) return `${Math.floor(seconds / 60)}分钟`
  return `${seconds}秒`
}

// hasActivatedOnce 表示 KeepAlive 首次 activated 已经发生；首次挂载只登记，不重复发起列表查询。
let hasActivatedOnce = false
// initialListLoaded 表示首次分类和业务列表均已结束，供后续重新激活决定立即刷新或延迟刷新。
let initialListLoaded = false
// pendingActivatedRefresh 表示初始化尚未结束时发生了第二次及后续激活，结束后必须补做一次真实查询。
let pendingActivatedRefresh = false
Promise.all([
  loadCategories(),
  loadList(),
  ...(props.mode === 'manage' ? [searchManagedUsers('')] : [])
]).finally(() => {
  initialListLoaded = true
  if (pendingActivatedRefresh) {
    pendingActivatedRefresh = false
    loadList()
  }
})

/**
 * 从详情页返回并重新激活缓存列表时回读服务端状态。
 * @returns {Promise<void>} 首次激活直接返回；后续激活刷新当前分页或登记延迟刷新。
 */
async function refreshActivatedList() {
  if (!hasActivatedOnce) {
    hasActivatedOnce = true
    return
  }
  if (!initialListLoaded) {
    pendingActivatedRefresh = true
    return
  }
  await loadList()
}

onActivated(refreshActivatedList)
</script>

<style scoped>
.workflow-process-list :deep(.el-form--inline .el-form-item) { margin-right: 18px; }
</style>
