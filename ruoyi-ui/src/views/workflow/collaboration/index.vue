<template>
  <div class="app-container collaboration-page">
    <header class="page-heading">
      <h2>多池协作</h2>
      <el-button icon="Refresh" :loading="loading" @click="loadRows">刷新</el-button>
    </header>

    <el-tabs v-model="activeDirection" @tab-change="changeDirection">
      <el-tab-pane label="事务 Outbox" name="OUTBOUND" />
      <el-tab-pane label="入站消息" name="INBOUND" />
    </el-tabs>

    <el-form inline class="page-filter">
      <el-form-item label="检索">
        <el-input v-model="queryParams.keyword" clearable prefix-icon="Search" placeholder="消息、流程或关联键" @keyup.enter="handleQuery" />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="queryParams.status" clearable placeholder="全部状态">
          <el-option v-for="status in statusOptions" :key="status" :label="statusLabel(status)" :value="status" />
        </el-select>
      </el-form-item>
      <el-form-item label="创建时间">
        <el-date-picker v-model="queryParams.timeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" />
      </el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="handleQuery">查询</el-button><el-button icon="Refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="messageId">
      <el-table-column label="消息" min-width="220">
        <template #default="scope">
          <div class="message-cell"><strong>{{ scope.row.messageName }}</strong><code>{{ scope.row.messageId }}</code></div>
        </template>
      </el-table-column>
      <el-table-column label="流程关系" min-width="250">
        <template #default="scope">
          <span>{{ scope.row.sourceProcessDefinitionKey || '外部系统' }}</span>
          <el-icon class="flow-arrow"><Right /></el-icon>
          <span>{{ scope.row.targetProcessDefinitionKey }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="correlationKey" label="关联键" min-width="160" show-overflow-tooltip />
      <el-table-column prop="sequenceNo" label="序号" width="76" align="center" />
      <el-table-column label="状态" width="112">
        <template #default="scope"><el-tag :type="statusMeta(scope.row.status).type">{{ statusMeta(scope.row.status).label }}</el-tag></template>
      </el-table-column>
      <el-table-column label="尝试" width="96" align="center">
        <template #default="scope">{{ scope.row.attemptCount }}/{{ scope.row.maxAttempts }}</template>
      </el-table-column>
      <el-table-column label="最后错误" min-width="210" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.lastErrorCode || '无' }}<span v-if="scope.row.lastErrorSummary"> · {{ scope.row.lastErrorSummary }}</span></template>
      </el-table-column>
      <el-table-column label="创建时间" width="172">
        <template #default="scope">{{ parseTime(scope.row.createTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="136" fixed="right" align="center">
        <template #default="scope">
          <el-tooltip content="查看审计">
            <el-button link icon="View" aria-label="查看审计" v-hasPermi="['workflow:collaboration:audit']" @click="openAudit(scope.row)" />
          </el-tooltip>
          <el-tooltip v-if="canRetry(scope.row)" content="重新投递">
            <el-button link type="primary" icon="RefreshRight" aria-label="重新投递" v-hasPermi="['workflow:collaboration:retry']" @click="retryRow(scope.row)" />
          </el-tooltip>
          <el-tooltip v-if="canCancel(scope.row)" content="取消投递">
            <el-button link type="danger" icon="CircleClose" aria-label="取消投递" v-hasPermi="['workflow:collaboration:cancel']" @click="cancelRow(scope.row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadRows" />

    <el-drawer v-model="auditOpen" title="消息审计" size="560px">
      <el-timeline v-loading="auditLoading">
        <el-timeline-item v-for="item in audits" :key="item.auditId" :timestamp="parseTime(item.createTime)" placement="top">
          <div class="audit-item">
            <strong>{{ item.action }} · {{ item.toStatus }}</strong>
            <span>{{ item.actorType }} / {{ item.actorId }}</span>
            <span v-if="item.errorCode">{{ item.errorCode }} · {{ item.summary }}</span>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!auditLoading && audits.length === 0" description="暂无审计" />
    </el-drawer>
  </div>
</template>

<script setup name="WorkflowCollaboration">
import {
  cancelCollaborationOutbox,
  listCollaborationAudit,
  listCollaborationInbound,
  listCollaborationOutbox,
  retryCollaborationInbound,
  retryCollaborationOutbox
} from '@/api/workflow/collaboration'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const activeDirection = ref('OUTBOUND')
const rows = ref([])
const total = ref(0)
const queryParams = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '', timeRange: [] })
const auditOpen = ref(false)
const auditLoading = ref(false)
const audits = ref([])
let initialized = false
const statusOptions = computed(() => activeDirection.value === 'OUTBOUND'
  ? ['PENDING', 'DELIVERING', 'RETRYING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED']
  : ['RECEIVED', 'RETRYING', 'PROCESSED', 'DEAD_LETTER'])

/** @returns {Promise<void>} 按当前方向和筛选刷新真实分页台账。 */
async function loadRows() {
  loading.value = true
  try {
    const loader = activeDirection.value === 'OUTBOUND' ? listCollaborationOutbox : listCollaborationInbound
    const response = await loader(buildQuery())
    rows.value = Array.isArray(response.rows) ? response.rows : []
    total.value = Number(response.total || 0)
  } finally {
    loading.value = false
  }
}

/**
 * 构造当前方向的分页筛选参数。
 * @returns {object} 后端可直接绑定的查询参数。
 */
function buildQuery() {
  const [beginTime, endTime] = queryParams.timeRange || []
  return {
    pageNum: queryParams.pageNum, pageSize: queryParams.pageSize,
    keyword: queryParams.keyword.trim() || undefined,
    status: queryParams.status || undefined, beginTime, endTime
  }
}

/** @returns {void} 切换方向后清空不兼容状态并从第一页读取。 */
function changeDirection() {
  queryParams.pageNum = 1
  queryParams.status = ''
  loadRows()
}

/** @returns {void} 回到第一页并按当前筛选查询。 */
function handleQuery() { queryParams.pageNum = 1; loadRows() }

/** @returns {void} 恢复默认筛选和分页后重新查询。 */
function resetQuery() {
  Object.assign(queryParams, { pageNum: 1, pageSize: 20, keyword: '', status: '', timeRange: [] })
  loadRows()
}

/** @param {string} status 状态编码。 @returns {{label:string,type:string}} 标签元数据。 */
function statusMeta(status) {
  return ({
    PENDING: { label: '待投递', type: 'info' }, DELIVERING: { label: '投递中', type: 'primary' },
    RETRYING: { label: '待重试', type: 'warning' }, PROCESSED: { label: '已完成', type: 'success' },
    DEAD_LETTER: { label: '死信', type: 'danger' }, CANCELLED: { label: '已取消', type: 'info' },
    RECEIVED: { label: '已接收', type: 'info' }
  })[status] || { label: status || '未知', type: 'info' }
}

/** @param {string} status 状态编码。 @returns {string} 下拉框文案。 */
function statusLabel(status) { return statusMeta(status).label }

/** @param {object} row 消息行。 @returns {boolean} 当前行是否允许人工补偿。 */
function canRetry(row) { return ['RETRYING', 'DEAD_LETTER'].includes(row.status) }

/** @param {object} row 消息行。 @returns {boolean} 当前出站行是否允许取消。 */
function canCancel(row) { return activeDirection.value === 'OUTBOUND' && ['PENDING', 'RETRYING', 'DEAD_LETTER'].includes(row.status) }

/** @param {object} row 消息行。 @returns {Promise<void>} 打开并加载逐次审计。 */
async function openAudit(row) {
  auditOpen.value = true
  auditLoading.value = true
  audits.value = []
  try {
    const response = await listCollaborationAudit(row.messageId)
    audits.value = Array.isArray(response.data) ? response.data : []
  } finally {
    auditLoading.value = false
  }
}

/** @param {object} row 消息行。 @returns {Promise<void>} 确认后执行受权限保护的人工补偿。 */
async function retryRow(row) {
  await proxy.$modal.confirm(`确认重新投递消息“${row.messageName}”吗？`)
  if (activeDirection.value === 'OUTBOUND') await retryCollaborationOutbox(row.messageId)
  else await retryCollaborationInbound(row.messageId)
  proxy.$modal.msgSuccess('消息已进入有界重试')
  await loadRows()
}

/** @param {object} row 出站消息行。 @returns {Promise<void>} 确认后取消未送达消息。 */
async function cancelRow(row) {
  await proxy.$modal.confirm(`确认取消消息“${row.messageName}”吗？已送达消息不会提供该操作。`)
  await cancelCollaborationOutbox(row.messageId)
  proxy.$modal.msgSuccess('消息已取消')
  await loadRows()
}

onMounted(async () => { await loadRows(); initialized = true })
onActivated(() => { if (initialized) loadRows() })
</script>

<style scoped>
.collaboration-page { min-width: 0; }
.page-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-heading h2 { margin: 0; font-size: 20px; letter-spacing: 0; }
.page-filter { margin: 12px 0 4px; }
.page-filter :deep(.el-input) { width: 280px; }
.page-filter :deep(.el-select) { width: 160px; }
.page-filter :deep(.el-date-editor) { width: 360px; }
.message-cell, .audit-item { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.message-cell code { overflow: hidden; color: var(--el-text-color-secondary); text-overflow: ellipsis; }
.flow-arrow { margin: 0 8px; color: var(--el-text-color-secondary); vertical-align: middle; }
.audit-item span { color: var(--el-text-color-secondary); }
</style>
