<template>
  <div class="app-container runtime-page">
    <header class="page-heading">
      <div>
        <h2>运行事件</h2>
        <p>消息、信号和 ReceiveTask 的处理与幂等审计</p>
      </div>
      <div class="summary"><strong>{{ total }}</strong><span>符合条件</span></div>
    </header>

    <el-form inline class="page-filter">
      <el-form-item label="检索"><el-input v-model="queryParams.keyword" clearable prefix-icon="Search" placeholder="requestId、事件或关联值" @keyup.enter="handleQuery" /></el-form-item>
      <el-form-item label="类型">
        <el-select v-model="queryParams.eventType" clearable placeholder="全部类型">
          <el-option label="Message" value="MESSAGE" /><el-option label="Signal" value="SIGNAL" /><el-option label="ReceiveTask" value="RECEIVE" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="queryParams.status" clearable placeholder="全部状态">
          <el-option label="已处理" value="PROCESSED" /><el-option label="失败" value="FAILED" /><el-option label="处理中" value="RECEIVED" />
        </el-select>
      </el-form-item>
      <el-form-item label="来源">
        <el-select v-model="queryParams.sourceType" clearable placeholder="全部来源">
          <el-option label="流程实例" value="PROCESS_INSTANCE" /><el-option label="业务标识" value="BUSINESS_KEY" />
        </el-select>
      </el-form-item>
      <el-form-item label="请求时间">
        <el-date-picker v-model="queryParams.timeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" />
      </el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="handleQuery">查询</el-button><el-button icon="Refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" row-key="requestId">
      <el-table-column label="请求" min-width="230">
        <template #default="scope"><div class="request-cell"><code>{{ scope.row.requestId }}</code><span>凭据 #{{ scope.row.credentialId }}</span></div></template>
      </el-table-column>
      <el-table-column label="类型" width="110" align="center"><template #default="scope"><el-tag size="small" type="info">{{ scope.row.eventType }}</el-tag></template></el-table-column>
      <el-table-column prop="eventName" label="事件 / 节点" min-width="190" show-overflow-tooltip />
      <el-table-column label="关联" min-width="250" show-overflow-tooltip><template #default="scope"><span class="correlation-type">{{ scope.row.correlationType }}</span> {{ scope.row.correlationValue }}</template></el-table-column>
      <el-table-column label="结果" min-width="230"><template #default="scope"><div class="result-cell"><strong>{{ scope.row.resultCode || '处理中' }}</strong><span>{{ scope.row.resultSummary || '等待完成' }}</span></div></template></el-table-column>
      <el-table-column label="状态" width="92" align="center"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="完成时间" width="172"><template #default="scope">{{ scope.row.completeTime ? parseTime(scope.row.completeTime) : '未完成' }}</template></el-table-column>
    </el-table>
    <pagination v-show="total > 0" :total="total" v-model:page="queryParams.pageNum" v-model:limit="queryParams.pageSize" @pagination="loadRows" />
  </div>
</template>

<script setup name="WorkflowRuntimeEvent">
import { listRuntimeEvents } from '@/api/workflow/runtimeEvent'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const queryParams = reactive({ pageNum: 1, pageSize: 20, keyword: '', eventType: '', status: '', sourceType: '', timeRange: [] })
let initialized = false

/**
 * 从正式后端加载当前筛选页的脱敏运行事件台账。
 * @returns {Promise<void>} 请求完成后同步替换 rows 和 total。
 */
async function loadRows() {
  loading.value = true
  try {
    const response = await listRuntimeEvents(buildQuery())
    rows.value = Array.isArray(response.rows) ? response.rows : []
    total.value = Number(response.total || 0)
  } finally {
    loading.value = false
  }
}

/**
 * 构造后端分页筛选参数，时间范围拆成明确上下界。
 * @returns {object} 不包含页面本地 timeRange 字段的请求参数。
 */
function buildQuery() {
  const [beginTime, endTime] = queryParams.timeRange || []
  return {
    pageNum: queryParams.pageNum, pageSize: queryParams.pageSize,
    keyword: queryParams.keyword.trim() || undefined,
    eventType: queryParams.eventType || undefined, status: queryParams.status || undefined,
    sourceType: queryParams.sourceType || undefined, beginTime, endTime
  }
}

/** @returns {void} 回到第一页并按当前筛选查询。 */
function handleQuery() { queryParams.pageNum = 1; loadRows() }

/** @returns {void} 恢复默认筛选和分页后重新查询。 */
function resetQuery() {
  Object.assign(queryParams, { pageNum: 1, pageSize: 20, keyword: '', eventType: '', status: '', sourceType: '', timeRange: [] })
  loadRows()
}

/**
 * 将正式状态转换为中文标签。
 * @param {string} status PROCESSED、FAILED 或 RECEIVED。
 * @returns {string} 表格状态文案。
 */
function statusLabel(status) {
  return { PROCESSED: '已处理', FAILED: '失败', RECEIVED: '处理中' }[status] || status
}

/**
 * 将正式状态转换为 Element Plus 标签类型。
 * @param {string} status PROCESSED、FAILED 或 RECEIVED。
 * @returns {string} 标签语义类型。
 */
function statusType(status) {
  return { PROCESSED: 'success', FAILED: 'danger', RECEIVED: 'warning' }[status] || 'info'
}

onMounted(async () => { await loadRows(); initialized = true })
onActivated(() => { if (initialized) loadRows() })
</script>

<style scoped>
.runtime-page { color: var(--el-text-color-primary); }
.page-heading { display: flex; align-items: flex-end; justify-content: space-between; min-height: 68px; margin: -4px 0 20px; padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-light); }
.page-heading h2 { margin: 0; font-size: 22px; letter-spacing: 0; }
.page-heading p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.summary { display: grid; justify-items: end; color: var(--el-text-color-secondary); font-size: 12px; }
.summary strong { color: var(--el-text-color-primary); font-family: Consolas, monospace; font-size: 24px; }
.page-filter :deep(.el-input) { width: 280px; }
.page-filter :deep(.el-select) { width: 145px; }
.page-filter :deep(.el-date-editor) { width: 360px; }
.request-cell, .result-cell { display: grid; gap: 4px; }
.request-cell code { font-family: Consolas, monospace; font-size: 12px; }
.request-cell span, .result-cell span { color: var(--el-text-color-secondary); font-size: 12px; }
.correlation-type { margin-right: 6px; color: var(--el-text-color-secondary); font-family: Consolas, monospace; font-size: 11px; }
</style>
