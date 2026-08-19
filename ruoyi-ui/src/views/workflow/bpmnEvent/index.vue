<template>
  <div class="app-container bpmn-event-page">
    <div class="page-heading">
      <div>
        <h2>错误、升级与审批 SLA</h2>
        <p>统一管理受控事件编码、业务日历、运行审计和站内通知。</p>
      </div>
      <el-button v-if="activeTab === 'codes'" type="primary" icon="Plus" v-hasPermi="['workflow:bpmnEvent:add']" @click="openCreate">新增编码</el-button>
      <el-button v-if="activeTab === 'calendars'" type="primary" icon="Plus" v-hasPermi="['workflow:sla:add']" @click="openCalendarCreate">新增日历</el-button>
    </div>

    <el-tabs v-model="activeTab" @tab-change="loadActiveTab">
      <el-tab-pane label="编码目录" name="codes">
        <el-table v-loading="loading.codes" :data="codes">
          <el-table-column label="类型" width="110">
            <template #default="scope">
              <el-tag :type="scope.row.eventType === 'ERROR' ? 'danger' : 'warning'">
                {{ scope.row.eventType === 'ERROR' ? '业务错误' : '业务升级' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="编码" prop="eventCode" min-width="210"><template #default="scope"><code>{{ scope.row.eventCode }}</code></template></el-table-column>
          <el-table-column label="名称" prop="eventName" min-width="180" />
          <el-table-column label="通知" width="110">
            <template #default="scope">{{ scope.row.notificationPolicy === 'INITIATOR' ? '通知发起人' : '不通知' }}</template>
          </el-table-column>
          <el-table-column label="说明" prop="remark" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="scope"><el-tag :type="scope.row.status === 'ENABLED' ? 'success' : 'info'">{{ scope.row.status === 'ENABLED' ? '已启用' : '已停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="scope">
              <el-button link type="primary" v-hasPermi="['workflow:bpmnEvent:edit']" @click="openEdit(scope.row)">编辑</el-button>
              <el-button link :type="scope.row.status === 'ENABLED' ? 'danger' : 'success'" v-hasPermi="['workflow:bpmnEvent:edit']" @click="toggleStatus(scope.row)">
                {{ scope.row.status === 'ENABLED' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="运行审计" name="audit">
        <el-form inline class="page-filter">
          <el-form-item label="检索"><el-input v-model="auditQuery.keyword" clearable placeholder="审计、实例、编码或节点" @keyup.enter="queryAudit" /></el-form-item>
          <el-form-item label="类型"><el-select v-model="auditQuery.eventType" clearable placeholder="全部类型"><el-option label="业务错误" value="ERROR" /><el-option label="业务升级" value="ESCALATION" /></el-select></el-form-item>
          <el-form-item label="状态"><el-select v-model="auditQuery.status" clearable placeholder="全部状态"><el-option label="已捕获" value="CAPTURED" /><el-option label="未匹配" value="UNMATCHED" /></el-select></el-form-item>
          <el-form-item label="来源"><el-input v-model="auditQuery.sourceType" clearable placeholder="来源类型" /></el-form-item>
          <el-form-item label="触发时间"><el-date-picker v-model="auditQuery.timeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
          <el-form-item><el-button type="primary" icon="Search" @click="queryAudit">查询</el-button><el-button icon="Refresh" @click="resetAuditQuery">重置</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading.audit" :data="auditRows">
          <el-table-column label="时间" width="170"><template #default="scope">{{ parseTime(scope.row.createTime) }}</template></el-table-column>
          <el-table-column label="类型" width="100" prop="eventType" />
          <el-table-column label="编码" min-width="190"><template #default="scope"><code>{{ scope.row.eventCode }}</code></template></el-table-column>
          <el-table-column label="来源" width="120" prop="sourceType" />
          <el-table-column label="实例" min-width="170" prop="processInstanceId" show-overflow-tooltip />
          <el-table-column label="产生节点" min-width="140" prop="sourceElementId" />
          <el-table-column label="捕获结果" width="105">
            <template #default="scope"><el-tag :type="scope.row.matchStatus === 'CAPTURED' ? 'success' : 'danger'">{{ scope.row.matchStatus }}</el-tag></template>
          </el-table-column>
          <el-table-column label="边界" min-width="130" prop="boundaryEventId" />
          <el-table-column label="摘要" min-width="180" prop="messageSummary" show-overflow-tooltip />
        </el-table>
        <pagination v-show="auditTotal > 0" :total="auditTotal" v-model:page="auditQuery.pageNum" v-model:limit="auditQuery.pageSize" @pagination="loadAudit" />
      </el-tab-pane>

      <el-tab-pane label="业务日历" name="calendars">
        <el-table v-loading="loading.calendars" :data="calendars">
          <el-table-column label="编码" min-width="180"><template #default="scope"><code>{{ scope.row.calendarKey }}</code></template></el-table-column>
          <el-table-column label="名称" min-width="160" prop="calendarName" />
          <el-table-column label="时区" min-width="150" prop="timezone" />
          <el-table-column label="工作日" min-width="180"><template #default="scope">{{ formatWorkingDays(scope.row.workingDays) }}</template></el-table-column>
          <el-table-column label="工作时段" width="150"><template #default="scope">{{ scope.row.workStart }} - {{ scope.row.workEnd }}</template></el-table-column>
          <el-table-column label="覆盖日期" width="100"><template #default="scope">{{ scope.row.days?.length || 0 }}</template></el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="scope"><el-tag :type="scope.row.status === 'ENABLED' ? 'success' : 'info'">{{ scope.row.status === 'ENABLED' ? '已启用' : '已停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="130" fixed="right">
            <template #default="scope">
              <el-button link type="primary" v-hasPermi="['workflow:sla:edit']" @click="openCalendarEdit(scope.row)">编辑</el-button>
              <el-button link :type="scope.row.status === 'ENABLED' ? 'danger' : 'success'" v-hasPermi="['workflow:sla:edit']" @click="toggleCalendarStatus(scope.row)">
                {{ scope.row.status === 'ENABLED' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="SLA 执行" name="slaExecutions">
        <el-form inline class="page-filter">
          <el-form-item label="检索"><el-input v-model="slaExecutionQuery.keyword" clearable placeholder="执行、实例、任务、节点或办理人" @keyup.enter="querySlaExecutions" /></el-form-item>
          <el-form-item label="状态"><el-select v-model="slaExecutionQuery.status" clearable placeholder="全部状态"><el-option label="进行中" value="ACTIVE" /><el-option label="已完成" value="COMPLETED" /><el-option label="已升级" value="ESCALATED" /></el-select></el-form-item>
          <el-form-item label="开始时间"><el-date-picker v-model="slaExecutionQuery.timeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
          <el-form-item><el-button type="primary" icon="Search" @click="querySlaExecutions">查询</el-button><el-button icon="Refresh" @click="resetSlaExecutionQuery">重置</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading.slaExecutions" :data="slaExecutions">
          <el-table-column label="实例" min-width="180" prop="processInstanceId" show-overflow-tooltip />
          <el-table-column label="任务" min-width="170" prop="taskId" show-overflow-tooltip />
          <el-table-column label="节点" min-width="140" prop="taskDefinitionKey" />
          <el-table-column label="状态" width="110" prop="status" />
          <el-table-column label="已提醒" width="90" prop="remindersSent" />
          <el-table-column label="首次提醒" width="170"><template #default="scope">{{ parseTime(scope.row.reminderDueAt) }}</template></el-table-column>
          <el-table-column label="升级时间" width="170"><template #default="scope">{{ parseTime(scope.row.escalationDueAt) }}</template></el-table-column>
        </el-table>
        <pagination v-show="slaExecutionTotal > 0" :total="slaExecutionTotal" v-model:page="slaExecutionQuery.pageNum" v-model:limit="slaExecutionQuery.pageSize" @pagination="loadSlaExecutions" />
      </el-tab-pane>

      <el-tab-pane label="SLA 审计" name="slaAudit">
        <el-form inline class="page-filter">
          <el-form-item label="检索"><el-input v-model="slaAuditQuery.keyword" clearable placeholder="审计、执行、实例、任务或操作人" @keyup.enter="querySlaAudit" /></el-form-item>
          <el-form-item label="动作"><el-select v-model="slaAuditQuery.actionType" clearable placeholder="全部动作"><el-option v-for="action in slaActionOptions" :key="action" :label="action" :value="action" /></el-select></el-form-item>
          <el-form-item label="动作时间"><el-date-picker v-model="slaAuditQuery.timeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" /></el-form-item>
          <el-form-item><el-button type="primary" icon="Search" @click="querySlaAudit">查询</el-button><el-button icon="Refresh" @click="resetSlaAuditQuery">重置</el-button></el-form-item>
        </el-form>
        <el-table v-loading="loading.slaAudit" :data="slaAuditRows">
          <el-table-column label="时间" width="170"><template #default="scope">{{ parseTime(scope.row.createTime) }}</template></el-table-column>
          <el-table-column label="动作" width="110" prop="actionType" />
          <el-table-column label="序号" width="70" prop="actionOrdinal" />
          <el-table-column label="实例" min-width="180" prop="processInstanceId" show-overflow-tooltip />
          <el-table-column label="任务" min-width="170" prop="taskId" show-overflow-tooltip />
          <el-table-column label="节点" min-width="140" prop="taskDefinitionKey" />
          <el-table-column label="操作人" width="110" prop="actorUserId" />
          <el-table-column label="摘要" min-width="220" prop="detail" show-overflow-tooltip />
        </el-table>
        <pagination v-show="slaAuditTotal > 0" :total="slaAuditTotal" v-model:page="slaAuditQuery.pageNum" v-model:limit="slaAuditQuery.pageSize" @pagination="loadSlaAudit" />
      </el-tab-pane>

    </el-tabs>

    <el-dialog v-model="dialogOpen" :title="form.eventCodeId ? '编辑事件编码' : '新增事件编码'" width="560px" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="事件类型" prop="eventType">
          <el-segmented v-model="form.eventType" :disabled="Boolean(form.eventCodeId)" :options="eventTypes" />
        </el-form-item>
        <el-form-item label="稳定编码" prop="eventCode">
          <el-input v-model="form.eventCode" :readonly="Boolean(form.eventCodeId)" maxlength="64" placeholder="APPROVAL_BUSINESS_ERROR" />
        </el-form-item>
        <el-form-item label="显示名称" prop="eventName"><el-input v-model="form.eventName" maxlength="128" /></el-form-item>
        <el-form-item label="通知策略" prop="notificationPolicy">
          <el-select v-model="form.notificationPolicy"><el-option label="不通知" value="NONE" /><el-option label="通知流程发起人" value="INITIATOR" /></el-select>
        </el-form-item>
        <el-form-item label="业务说明" prop="description"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogOpen = false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="calendarDialogOpen" :title="calendarForm.calendarId ? '编辑业务日历' : '新增业务日历'" width="760px" append-to-body>
      <el-form ref="calendarFormRef" :model="calendarForm" :rules="calendarRules" label-width="108px">
        <div class="calendar-form-grid">
          <el-form-item label="稳定编码" prop="calendarKey"><el-input v-model="calendarForm.calendarKey" :readonly="Boolean(calendarForm.calendarId)" maxlength="64" placeholder="DEFAULT" /></el-form-item>
          <el-form-item label="日历名称" prop="calendarName"><el-input v-model="calendarForm.calendarName" maxlength="128" /></el-form-item>
          <el-form-item label="IANA 时区" prop="timezone"><el-input v-model="calendarForm.timezone" maxlength="64" placeholder="Asia/Shanghai" /></el-form-item>
          <el-form-item label="工作时段" required class="calendar-time-range">
            <el-time-select v-model="calendarForm.workStart" start="00:00" step="00:30" end="23:30" placeholder="开始" />
            <span>至</span>
            <el-time-select v-model="calendarForm.workEnd" start="00:30" step="00:30" end="23:59" placeholder="结束" />
          </el-form-item>
        </div>
        <el-form-item label="每周工作日" prop="workingDays">
          <el-checkbox-group v-model="calendarForm.workingDays">
            <el-checkbox-button v-for="day in weekOptions" :key="day.value" :value="day.value">{{ day.label }}</el-checkbox-button>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="说明"><el-input v-model="calendarForm.description" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="日期覆盖">
          <div class="calendar-days">
            <div v-for="(day, index) in calendarForm.days" :key="day.rowKey" class="calendar-day-row">
              <el-date-picker v-model="day.calendarDate" type="date" value-format="YYYY-MM-DD" placeholder="日期" />
              <el-segmented v-model="day.workingDay" :options="dayTypeOptions" />
              <el-input v-model="day.dayName" maxlength="128" placeholder="节假日或补班说明" />
              <el-tooltip content="删除日期覆盖" placement="top"><el-button icon="Delete" circle aria-label="删除日期覆盖" @click="removeCalendarDay(index)" /></el-tooltip>
            </div>
            <el-button icon="Plus" :disabled="calendarForm.days.length >= 1000" @click="addCalendarDay">新增日期覆盖</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="calendarDialogOpen = false">取消</el-button><el-button type="primary" :loading="calendarSaving" @click="submitCalendar">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowBpmnEvent">
import {
  changeBpmnEventCodeStatus,
  createBpmnEventCode,
  listBpmnEventAudit,
  listBpmnEventCodes,
  updateBpmnEventCode
} from '@/api/workflow/bpmnEvent'
import {
  changeSlaCalendarStatus,
  createSlaCalendar,
  listSlaAudits,
  listSlaCalendars,
  listSlaExecutions,
  updateSlaCalendar
} from '@/api/workflow/sla'

const { proxy } = getCurrentInstance()
const activeTab = ref('codes')
const loading = reactive({
  codes: false,
  audit: false,
  calendars: false,
  slaExecutions: false,
  slaAudit: false
})
const saving = ref(false)
const codes = ref([])
const auditRows = ref([])
const auditTotal = ref(0)
const auditQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '', eventType: '', sourceType: '', timeRange: [] })
const dialogOpen = ref(false)
const formRef = ref(null)
const form = reactive(emptyForm())
const calendars = ref([])
const slaExecutions = ref([])
const slaExecutionTotal = ref(0)
const slaExecutionQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '', timeRange: [] })
const slaAuditRows = ref([])
const slaAuditTotal = ref(0)
const slaAuditQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '', actionType: '', timeRange: [] })
const calendarDialogOpen = ref(false)
const calendarSaving = ref(false)
const calendarFormRef = ref(null)
const calendarForm = reactive(emptyCalendarForm())
let nextCalendarDayKey = 1
const eventTypes = Object.freeze([{ label: '业务错误', value: 'ERROR' }, { label: '业务升级', value: 'ESCALATION' }])
const rules = {
  eventType: [{ required: true, message: '请选择事件类型', trigger: 'change' }],
  eventCode: [
    { required: true, message: '稳定编码不能为空', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_.-]{1,63}$/, message: '编码必须以大写字母开头，仅含大写字母、数字、点、横线或下划线', trigger: 'blur' }
  ],
  eventName: [{ required: true, message: '显示名称不能为空', trigger: 'blur' }]
}
const weekOptions = Object.freeze([
  { label: '周一', value: 1 }, { label: '周二', value: 2 }, { label: '周三', value: 3 },
  { label: '周四', value: 4 }, { label: '周五', value: 5 }, { label: '周六', value: 6 },
  { label: '周日', value: 7 }
])
const dayTypeOptions = Object.freeze([{ label: '节假日', value: false }, { label: '补班', value: true }])
const slaActionOptions = Object.freeze(['CREATE', 'ASSIGN', 'REMINDER', 'ESCALATE', 'COMPLETE', 'PAUSE', 'RESUME'])
const calendarRules = {
  calendarKey: [
    { required: true, message: '稳定编码不能为空', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_.-]{1,63}$/, message: '编码格式不合法', trigger: 'blur' }
  ],
  calendarName: [{ required: true, message: '日历名称不能为空', trigger: 'blur' }],
  timezone: [{ required: true, message: 'IANA 时区不能为空', trigger: 'blur' }],
  workingDays: [{ type: 'array', required: true, min: 1, message: '至少选择一个工作日', trigger: 'change' }]
}

/** @returns {object} 新增表单的稳定初始值。 */
function emptyForm() {
  return { eventCodeId: null, eventType: 'ERROR', eventCode: '', eventName: '', notificationPolicy: 'INITIATOR', description: '' }
}

/**
 * 创建字段完整的业务日历表单。
 * @returns {object} 默认使用上海时区和周一至周五工作时段的新表单。
 */
function emptyCalendarForm() {
  return {
    calendarId: null,
    calendarKey: '',
    calendarName: '',
    timezone: 'Asia/Shanghai',
    workingDays: [1, 2, 3, 4, 5],
    workStart: '09:00',
    workEnd: '18:00',
    description: '',
    days: []
  }
}

/**
 * 按当前页签调用对应真实后端接口加载数据。
 * @returns {Promise<void>} 当前页签数据完成回显后结束。
 */
async function loadActiveTab() {
  if (activeTab.value === 'codes') return loadCodes()
  if (activeTab.value === 'audit') return loadAudit()
  if (activeTab.value === 'calendars') return loadCalendars()
  if (activeTab.value === 'slaExecutions') return loadSlaExecutions()
  return loadSlaAudit()
}

/** @returns {Promise<void>} 加载正式编码目录。 */
async function loadCodes() {
  loading.codes = true
  try { codes.value = (await listBpmnEventCodes()).data || [] } finally { loading.codes = false }
}

/** @returns {Promise<void>} 加载当前筛选页的专用运行审计。 */
async function loadAudit() {
  loading.audit = true
  try {
    const response = await listBpmnEventAudit(buildPagedQuery(auditQuery))
    auditRows.value = Array.isArray(response.rows) ? response.rows : []
    auditTotal.value = Number(response.total || 0)
  } finally { loading.audit = false }
}


/**
 * 从 AjaxResult 或 TableDataInfo 响应中提取正式行集合。
 * @param {object|undefined} response 后端列表接口响应。
 * @returns {Array<object>} 不存在合法集合时返回空数组。
 */
function responseRows(response) {
  if (Array.isArray(response?.data)) return response.data
  if (Array.isArray(response?.rows)) return response.rows
  return []
}

/**
 * 将页面分页筛选对象转换为后端查询参数。
 * @param {object} query 包含 pageNum、pageSize、timeRange 和领域字段的页面状态。
 * @returns {object} 拆分 beginTime/endTime 且去除空白字段的请求参数。
 */
function buildPagedQuery(query) {
  const [beginTime, endTime] = query.timeRange || []
  return Object.fromEntries(Object.entries({ ...query, timeRange: undefined, beginTime, endTime })
    .filter(([, value]) => value !== '' && value !== undefined && value !== null))
}

/** @returns {void} BPMN 审计回到第一页并查询。 */
function queryAudit() { auditQuery.pageNum = 1; loadAudit() }

/** @returns {void} 恢复 BPMN 审计默认筛选。 */
function resetAuditQuery() {
  Object.assign(auditQuery, { pageNum: 1, pageSize: 20, keyword: '', status: '', eventType: '', sourceType: '', timeRange: [] })
  loadAudit()
}

/**
 * 加载全部正式业务日历供管理和状态切换。
 * @returns {Promise<void>} 请求结束后解除页签加载状态。
 */
async function loadCalendars() {
  loading.calendars = true
  try { calendars.value = responseRows(await listSlaCalendars()) } finally { loading.calendars = false }
}

/**
 * 加载当前权限范围内的 SLA 执行状态。
 * @returns {Promise<void>} 请求结束后解除页签加载状态。
 */
async function loadSlaExecutions() {
  loading.slaExecutions = true
  try {
    const response = await listSlaExecutions(buildPagedQuery(slaExecutionQuery))
    slaExecutions.value = Array.isArray(response.rows) ? response.rows : []
    slaExecutionTotal.value = Number(response.total || 0)
  } finally { loading.slaExecutions = false }
}

/**
 * 加载正式 SLA 生命周期及触发审计。
 * @returns {Promise<void>} 请求结束后解除页签加载状态。
 */
async function loadSlaAudit() {
  loading.slaAudit = true
  try {
    const response = await listSlaAudits(buildPagedQuery(slaAuditQuery))
    slaAuditRows.value = Array.isArray(response.rows) ? response.rows : []
    slaAuditTotal.value = Number(response.total || 0)
  } finally { loading.slaAudit = false }
}

/** @returns {void} SLA 执行回到第一页并查询。 */
function querySlaExecutions() { slaExecutionQuery.pageNum = 1; loadSlaExecutions() }

/** @returns {void} 恢复 SLA 执行默认筛选。 */
function resetSlaExecutionQuery() {
  Object.assign(slaExecutionQuery, { pageNum: 1, pageSize: 20, keyword: '', status: '', timeRange: [] })
  loadSlaExecutions()
}

/** @returns {void} SLA 审计回到第一页并查询。 */
function querySlaAudit() { slaAuditQuery.pageNum = 1; loadSlaAudit() }

/** @returns {void} 恢复 SLA 审计默认筛选。 */
function resetSlaAuditQuery() {
  Object.assign(slaAuditQuery, { pageNum: 1, pageSize: 20, keyword: '', actionType: '', timeRange: [] })
  loadSlaAudit()
}


/**
 * 将 ISO 工作周序号转换为稳定中文摘要。
 * @param {string|number[]|undefined} workingDays 后端逗号串或表单数字集合。
 * @returns {string} 去重排序后的工作日中文文本。
 */
function formatWorkingDays(workingDays) {
  const values = Array.isArray(workingDays)
    ? workingDays
    : String(workingDays || '').split(',').map(Number)
  const labels = new Map(weekOptions.map(item => [item.value, item.label]))
  return [...new Set(values)].sort((left, right) => left - right)
    .map(value => labels.get(Number(value))).filter(Boolean).join('、')
}

/**
 * 将业务日历日期覆盖转换为带稳定行键的编辑值。
 * @param {Array<object>|undefined} days 后端返回的节假日和补班日集合。
 * @returns {Array<object>} 可直接用于动态表单的深复制集合。
 */
function editableCalendarDays(days) {
  return (Array.isArray(days) ? days : []).map(day => ({
    rowKey: nextCalendarDayKey++,
    calendarDate: String(day.calendarDate || ''),
    workingDay: day.workingDay === true,
    dayName: String(day.dayName || '')
  }))
}

/**
 * 打开空白业务日历新增表单。
 * @returns {void} 清除上次编辑状态和校验提示。
 */
function openCalendarCreate() {
  Object.assign(calendarForm, emptyCalendarForm())
  calendarDialogOpen.value = true
  nextTick(() => calendarFormRef.value?.clearValidate())
}

/**
 * 打开业务日历编辑表单并回显全部日期覆盖。
 * @param {object} row 正式业务日历行。
 * @returns {void} 稳定编码保持只读。
 */
function openCalendarEdit(row) {
  const workingDays = Array.isArray(row.workingDays)
    ? row.workingDays.map(Number)
    : String(row.workingDays || '').split(',').map(Number).filter(Number.isInteger)
  Object.assign(calendarForm, {
    calendarId: row.calendarId,
    calendarKey: row.calendarKey,
    calendarName: row.calendarName,
    timezone: row.timezone,
    workingDays,
    workStart: row.workStart,
    workEnd: row.workEnd,
    description: row.description || row.remark || '',
    days: editableCalendarDays(row.days)
  })
  calendarDialogOpen.value = true
  nextTick(() => calendarFormRef.value?.clearValidate())
}

/**
 * 新增一条空日期覆盖编辑行。
 * @returns {void} 达到后端一千条上限时不修改表单。
 */
function addCalendarDay() {
  if (calendarForm.days.length >= 1000) return
  calendarForm.days.push({
    rowKey: nextCalendarDayKey++, calendarDate: '', workingDay: false, dayName: ''
  })
}

/**
 * 删除指定日期覆盖编辑行。
 * @param {number} index 日期覆盖数组下标。
 * @returns {void} 下标无效时不修改表单。
 */
function removeCalendarDay(index) {
  if (index < 0 || index >= calendarForm.days.length) return
  calendarForm.days.splice(index, 1)
}

/**
 * 校验日期覆盖唯一性、工作时段和必填值并构造正式请求。
 * @returns {object} 与 WorkflowBusinessCalendarRequest 一致的完整请求体。
 */
function buildCalendarPayload() {
  if (!calendarForm.workStart || !calendarForm.workEnd || calendarForm.workStart >= calendarForm.workEnd) {
    throw new Error('工作结束时间必须晚于开始时间')
  }
  const dates = new Set()
  const days = calendarForm.days.map(day => {
    const calendarDate = String(day.calendarDate || '')
    if (!calendarDate || dates.has(calendarDate)) throw new Error('日期覆盖不能为空或重复')
    dates.add(calendarDate)
    return {
      calendarDate,
      workingDay: day.workingDay === true,
      dayName: String(day.dayName || '').trim() || undefined
    }
  })
  return {
    calendarKey: calendarForm.calendarKey.trim(),
    calendarName: calendarForm.calendarName.trim(),
    timezone: calendarForm.timezone.trim(),
    workingDays: [...new Set(calendarForm.workingDays.map(Number))].sort((left, right) => left - right),
    workStart: calendarForm.workStart,
    workEnd: calendarForm.workEnd,
    description: calendarForm.description.trim() || undefined,
    days
  }
}

/**
 * 新增或修改正式业务日历并重新查询数据库回显。
 * @returns {Promise<void>} 校验失败或请求失败时保持对话框打开。
 */
async function submitCalendar() {
  if (!await calendarFormRef.value.validate().catch(() => false)) return
  let payload
  try {
    payload = buildCalendarPayload()
  } catch (error) {
    proxy.$modal.msgError(error.message)
    return
  }
  calendarSaving.value = true
  try {
    if (calendarForm.calendarId) await updateSlaCalendar(calendarForm.calendarId, payload)
    else await createSlaCalendar(payload)
    proxy.$modal.msgSuccess('业务日历已保存')
    calendarDialogOpen.value = false
    await loadCalendars()
  } finally {
    calendarSaving.value = false
  }
}

/**
 * 经用户确认后切换正式业务日历启停状态。
 * @param {object} row 目标业务日历行。
 * @returns {Promise<void>} 服务端修改成功后重新查询回显。
 */
async function toggleCalendarStatus(row) {
  const enabled = row.status !== 'ENABLED'
  await proxy.$modal.confirm(`确认${enabled ? '启用' : '停用'}“${row.calendarName}”吗？`)
  await changeSlaCalendarStatus(row.calendarId, enabled)
  await loadCalendars()
}


/** @returns {void} 打开空白新增表单。 */
function openCreate() {
  Object.assign(form, emptyForm())
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/** @param {object} row 目录行；@returns {void} 回显不可变编码和可维护元数据。 */
function openEdit(row) {
  Object.assign(form, {
    eventCodeId: row.eventCodeId, eventType: row.eventType, eventCode: row.eventCode,
    eventName: row.eventName, notificationPolicy: row.notificationPolicy, description: row.remark || ''
  })
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/** @returns {Promise<void>} 新增或修改真实目录并重新查询回显。 */
async function submit() {
  if (!await formRef.value.validate().catch(() => false)) return
  const payload = {
    eventType: form.eventType, eventCode: form.eventCode.trim(), eventName: form.eventName.trim(),
    notificationPolicy: form.notificationPolicy, description: form.description.trim() || undefined
  }
  saving.value = true
  try {
    if (form.eventCodeId) await updateBpmnEventCode(form.eventCodeId, payload)
    else await createBpmnEventCode(payload)
    proxy.$modal.msgSuccess('BPMN 事件编码已保存')
    dialogOpen.value = false
    await loadCodes()
  } finally { saving.value = false }
}

/** @param {object} row 目录行；@returns {Promise<void>} 经确认切换正式启停状态。 */
async function toggleStatus(row) {
  const enabled = row.status !== 'ENABLED'
  await proxy.$modal.confirm(`确认${enabled ? '启用' : '停用'}“${row.eventName}”吗？`)
  await changeBpmnEventCodeStatus(row.eventCodeId, enabled)
  await loadCodes()
}

onMounted(loadCodes)
onActivated(loadActiveTab)
</script>

<style scoped>
.page-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 12px;
}
.page-heading h2 { margin: 0; font-size: 22px; }
.page-heading p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
code { font-family: "JetBrains Mono", Consolas, monospace; font-size: 12px; }
.page-filter { margin: 8px 0 4px; }
.page-filter :deep(.el-input), .page-filter :deep(.el-select) { width: 220px; }
.page-filter :deep(.el-date-editor) { width: 360px; }
.bpmn-event-page :deep(.el-select), .bpmn-event-page :deep(.el-segmented) { width: 100%; }
.calendar-form-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 0 16px;
}
.calendar-time-range :deep(.el-form-item__content) {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  gap: 8px;
  align-items: center;
}
.calendar-days {
  display: grid;
  gap: 8px;
  width: 100%;
}
.calendar-day-row {
  display: grid;
  grid-template-columns: 150px 152px minmax(160px, 1fr) 32px;
  gap: 8px;
  align-items: center;
}
.calendar-day-row :deep(.el-button) {
  width: 32px;
  height: 32px;
  border-radius: 4px;
}
@media (max-width: 900px) {
  .calendar-form-grid { grid-template-columns: minmax(0, 1fr); }
  .calendar-day-row { grid-template-columns: minmax(0, 1fr); }
}
</style>
