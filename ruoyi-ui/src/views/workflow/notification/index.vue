<template>
  <div class="app-container notification-admin">
    <div class="notification-admin__header">
      <div>
        <h2>审批通知</h2>
        <span>流程策略与可靠投递运维</span>
      </div>
      <div class="notification-admin__actions">
        <el-button circle text icon="Refresh" aria-label="刷新" :loading="loading" @click="loadActiveTab" />
        <el-button v-if="activeTab === 'policies'" type="primary" icon="Plus" @click="openPolicy()">新增策略</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="loadActiveTab">
      <el-tab-pane label="通知策略" name="policies">
        <el-table v-loading="loading" :data="policies" row-key="policyId">
          <el-table-column label="作用域" width="110">
            <template #default="{ row }"><el-tag size="small" :type="scopeType(row.scopeType)">{{ scopeLabel(row.scopeType) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="eventType" label="事件" min-width="190" />
          <el-table-column label="流程 / 节点" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">{{ [row.processDefinitionKey, row.taskDefinitionKey].filter(Boolean).join(' / ') || '全局默认' }}</template>
          </el-table-column>
          <el-table-column prop="recipientRules" label="接收人" min-width="150" />
          <el-table-column prop="channels" label="通道" width="130" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }"><el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center" fixed="right">
            <template #default="{ row }">
              <el-tooltip content="编辑策略" placement="top"><el-button circle text icon="Edit" aria-label="编辑策略" @click="openPolicy(row)" /></el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="投递运维" name="outbox">
        <el-form inline class="notification-admin__filter">
          <el-form-item label="检索">
            <el-input v-model="outboxQuery.keyword" clearable prefix-icon="Search" placeholder="ID、来源、流程、任务或错误码" @keyup.enter="queryOutbox" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="outboxQuery.status" clearable placeholder="全部状态">
              <el-option v-for="status in outboxStatusOptions" :key="status" :label="statusLabel(status)" :value="status" />
            </el-select>
          </el-form-item>
          <el-form-item label="来源">
            <el-select v-model="outboxQuery.sourceType" clearable placeholder="全部来源">
              <el-option label="审批" value="APPROVAL" />
              <el-option label="SLA" value="SLA" />
              <el-option label="BPMN 事件" value="BPMN_EVENT" />
            </el-select>
          </el-form-item>
          <el-form-item label="事件">
            <el-select v-model="outboxQuery.eventType" clearable filterable placeholder="全部事件">
              <el-option v-for="event in eventOptions" :key="event" :label="event" :value="event" />
            </el-select>
          </el-form-item>
          <el-form-item label="通道">
            <el-select v-model="outboxQuery.channel" clearable placeholder="全部通道">
              <el-option label="邮件" value="EMAIL" />
              <el-option label="短信" value="SMS" />
            </el-select>
          </el-form-item>
          <el-form-item label="创建时间">
            <el-date-picker v-model="outboxQuery.timeRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" icon="Search" @click="queryOutbox">查询</el-button>
            <el-button icon="Refresh" @click="resetOutboxQuery">重置</el-button>
          </el-form-item>
        </el-form>
        <el-table v-loading="loading" :data="outbox" row-key="outboxId">
          <el-table-column prop="outboxId" label="ID" width="88" />
          <el-table-column prop="sourceType" label="来源" width="110" />
          <el-table-column prop="sourceId" label="来源标识" min-width="180" show-overflow-tooltip />
          <el-table-column prop="eventType" label="事件" min-width="180" />
          <el-table-column prop="channel" label="通道" width="90" />
          <el-table-column prop="recipientUserId" label="接收人 ID" width="110" />
          <el-table-column prop="processInstanceId" label="流程实例" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="尝试" width="80">
            <template #default="{ row }">{{ row.attemptCount }} / {{ row.maxAttempts }}</template>
          </el-table-column>
          <el-table-column prop="lastErrorSummary" label="最近失败" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="80" align="center" fixed="right">
            <template #default="{ row }">
              <el-tooltip v-if="row.status === 'DEAD_LETTER'" content="补偿重试" placement="top">
                <el-button circle text type="primary" icon="RefreshRight" aria-label="补偿重试" @click="compensate(row)" />
              </el-tooltip>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>
        <pagination v-show="outboxTotal > 0" :total="outboxTotal" v-model:page="outboxQuery.pageNum" v-model:limit="outboxQuery.pageSize" @pagination="loadOutbox" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="policyDialog.visible" :title="policyDialog.form.policyId ? '编辑通知策略' : '新增通知策略'" width="680px" append-to-body>
      <el-form ref="policyFormRef" :model="policyDialog.form" :rules="policyRules" label-width="110px">
        <div class="notification-admin__form-grid">
          <el-form-item label="作用域" prop="scopeType">
            <el-select v-model="policyDialog.form.scopeType" @change="normalizeScope">
              <el-option label="全局默认" value="DEFAULT" />
              <el-option label="指定流程" value="PROCESS" />
              <el-option label="指定节点" value="NODE" />
            </el-select>
          </el-form-item>
          <el-form-item label="事件" prop="eventType">
            <el-select v-model="policyDialog.form.eventType" filterable>
              <el-option v-for="event in eventOptions" :key="event" :label="event" :value="event" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="policyDialog.form.scopeType !== 'DEFAULT'" label="流程 key" prop="processDefinitionKey">
            <el-input v-model="policyDialog.form.processDefinitionKey" maxlength="255" />
          </el-form-item>
          <el-form-item v-if="policyDialog.form.scopeType === 'NODE'" label="节点 key" prop="taskDefinitionKey">
            <el-input v-model="policyDialog.form.taskDefinitionKey" maxlength="255" />
          </el-form-item>
          <el-form-item label="接收人" prop="recipientRuleList">
            <el-select v-model="policyDialog.form.recipientRuleList" multiple>
              <el-option label="当前待办接收人" value="TASK_RECIPIENT" />
              <el-option label="流程发起人" value="INITIATOR" />
              <el-option label="当前操作人" value="ACTOR" />
            </el-select>
          </el-form-item>
          <el-form-item label="通道" prop="channelList">
            <el-checkbox-group v-model="policyDialog.form.channelList">
              <el-checkbox value="INBOX">站内</el-checkbox>
              <el-checkbox value="EMAIL">邮件</el-checkbox>
              <el-checkbox value="SMS">短信</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item v-if="policyDialog.form.channelList.includes('SMS')" label="短信模板 ID" prop="smsTemplateId">
            <el-input v-model="policyDialog.form.smsTemplateId" maxlength="64" />
          </el-form-item>
          <el-form-item label="最大尝试" prop="maxAttempts">
            <el-input-number v-model="policyDialog.form.maxAttempts" :min="1" :max="20" controls-position="right" />
          </el-form-item>
          <el-form-item label="启用状态" prop="status">
            <el-switch v-model="policyDialog.form.status" active-value="ENABLED" inactive-value="DISABLED" />
          </el-form-item>
        </div>
        <el-form-item label="标题模板" prop="titleTemplate">
          <el-input v-model="policyDialog.form.titleTemplate" maxlength="160" show-word-limit />
        </el-form-item>
        <el-form-item label="正文模板" prop="contentTemplate">
          <el-input v-model="policyDialog.form.contentTemplate" type="textarea" :rows="4" maxlength="700" show-word-limit />
        </el-form-item>
        <div class="notification-admin__variables">
          <button v-for="variable in templateVariables" :key="variable" type="button" @click="appendVariable(variable)">{{ templateToken(variable) }}</button>
        </div>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="policyDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitPolicy">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowNotification">
import {
  compensateWorkflowNotification,
  listWorkflowNotificationOutbox,
  listWorkflowNotificationPolicies,
  saveWorkflowNotificationPolicy
} from '@/api/workflow/notification'

const { proxy } = getCurrentInstance()
const activeTab = ref('policies')
const loading = ref(false)
const saving = ref(false)
const policies = ref([])
const outbox = ref([])
const outboxTotal = ref(0)
const outboxQuery = reactive({
  pageNum: 1, pageSize: 20, keyword: '', status: '', sourceType: '', eventType: '', channel: '', timeRange: []
})
const outboxStatusOptions = ['PENDING', 'RETRYING', 'DELIVERING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED']
const policyFormRef = ref(null)
const eventOptions = [
  'TASK_ARRIVED', 'TASK_CLAIMED', 'TASK_UNCLAIMED', 'TASK_DELEGATED',
  'TASK_DELEGATION_RESOLVED', 'TASK_TRANSFERRED', 'TASK_RETURNED', 'TASK_RESUBMITTED',
  'TASK_COMPLETED', 'PROCESS_COMPLETED', 'PROCESS_CANCELED', 'PROCESS_REJECTED',
  'PROCESS_TERMINATED', 'MANUAL_URGE', 'COPY_CREATED'
]
const templateVariables = ['processName', 'processDefinitionKey', 'processInstanceId', 'taskName', 'taskDefinitionKey', 'eventType']
const policyDialog = reactive({ visible: false, form: emptyPolicy() })
const policyRules = {
  scopeType: [{ required: true, message: '请选择作用域', trigger: 'change' }],
  eventType: [{ required: true, message: '请选择事件', trigger: 'change' }],
  processDefinitionKey: [{ required: true, message: '请输入流程 key', trigger: 'blur' }],
  taskDefinitionKey: [{ required: true, message: '请输入节点 key', trigger: 'blur' }],
  recipientRuleList: [{ type: 'array', required: true, min: 1, message: '请选择接收人', trigger: 'change' }],
  channelList: [{ type: 'array', required: true, min: 1, message: '请选择通道', trigger: 'change' }],
  smsTemplateId: [{ validator: validateSmsTemplate, trigger: 'blur' }],
  titleTemplate: [{ required: true, message: '请输入标题模板', trigger: 'blur' }],
  contentTemplate: [{ required: true, message: '请输入正文模板', trigger: 'blur' }]
}

/** @returns {object} 新策略表单默认值。 */
function emptyPolicy() {
  return {
    policyId: null, scopeType: 'DEFAULT', processDefinitionKey: '', taskDefinitionKey: '',
    eventType: 'TASK_ARRIVED', recipientRuleList: ['TASK_RECIPIENT'], channelList: ['INBOX'],
    smsTemplateId: '',
    titleTemplate: '新待办：{{taskName}}', contentTemplate: '流程“{{processName}}”有新的待办任务“{{taskName}}”。',
    maxAttempts: 6, status: 'ENABLED', expectedRevision: null
  }
}

/** @returns {Promise<void>} 按当前页签查询正式策略或脱敏 outbox。 */
async function loadActiveTab() {
  loading.value = true
  try {
    if (activeTab.value === 'policies') policies.value = (await listWorkflowNotificationPolicies()).data || []
    else await loadOutbox(false)
  } finally {
    loading.value = false
  }
}

/**
 * 按当前筛选和页码读取正式通知 outbox。
 * @param {boolean} manageLoading 是否由本函数独立维护加载状态。
 * @returns {Promise<void>} 成功后同步当前页 rows 和 total。
 */
async function loadOutbox(manageLoading = true) {
  if (manageLoading) loading.value = true
  try {
    const response = await listWorkflowNotificationOutbox(buildOutboxQuery())
    outbox.value = Array.isArray(response.rows) ? response.rows : []
    outboxTotal.value = Number(response.total || 0)
  } finally {
    if (manageLoading) loading.value = false
  }
}

/**
 * 构造通知 outbox 分页筛选参数。
 * @returns {object} 后端可直接绑定的分页和领域筛选条件。
 */
function buildOutboxQuery() {
  const [beginTime, endTime] = outboxQuery.timeRange || []
  return {
    pageNum: outboxQuery.pageNum,
    pageSize: outboxQuery.pageSize,
    keyword: outboxQuery.keyword.trim() || undefined,
    status: outboxQuery.status || undefined,
    sourceType: outboxQuery.sourceType || undefined,
    eventType: outboxQuery.eventType || undefined,
    channel: outboxQuery.channel || undefined,
    beginTime,
    endTime
  }
}

/** @returns {void} 从第一页按当前筛选查询 outbox。 */
function queryOutbox() { outboxQuery.pageNum = 1; loadOutbox() }

/** @returns {void} 恢复默认分页与筛选并重新查询 outbox。 */
function resetOutboxQuery() {
  Object.assign(outboxQuery, {
    pageNum: 1, pageSize: 20, keyword: '', status: '', sourceType: '', eventType: '', channel: '', timeRange: []
  })
  loadOutbox()
}

/**
 * 打开新增或编辑策略弹窗。
 * @param {object|null} row 已有策略行，新增时为空。
 * @returns {void} 复制为独立表单，避免修改列表快照。
 */
function openPolicy(row = null) {
  policyDialog.form = row ? {
    ...row,
    processDefinitionKey: row.processDefinitionKey || '',
    taskDefinitionKey: row.taskDefinitionKey || '',
    recipientRuleList: String(row.recipientRules || '').split(',').filter(Boolean),
    channelList: String(row.channels || '').split(',').filter(Boolean),
    smsTemplateId: row.smsTemplateId || '',
    expectedRevision: Number(row.revision)
  } : emptyPolicy()
  policyDialog.visible = true
  nextTick(() => policyFormRef.value?.clearValidate())
}

/**
 * 确保短信通道与供应商模板 ID 同时出现。
 * @param {unknown} rule Element Plus 校验规则。
 * @param {string} value 模板 ID。
 * @param {Function} callback 校验回调。
 * @returns {void} 校验结果。
 */
function validateSmsTemplate(rule, value, callback) {
  policyDialog.form.channelList.includes('SMS') && !value ? callback(new Error('请输入短信模板 ID')) : callback()
}

/** @returns {void} 作用域收窄时清除不再适用的流程或节点 key。 */
function normalizeScope() {
  if (policyDialog.form.scopeType === 'DEFAULT') policyDialog.form.processDefinitionKey = ''
  if (policyDialog.form.scopeType !== 'NODE') policyDialog.form.taskDefinitionKey = ''
}

/**
 * 将白名单变量插入正文模板末尾。
 * @param {string} variable 服务端允许的变量名。
 * @returns {void} 无返回值。
 */
function appendVariable(variable) {
  const token = templateToken(variable)
  const separator = policyDialog.form.contentTemplate ? ' ' : ''
  policyDialog.form.contentTemplate += separator + token
}

/**
 * 生成模板变量的可见占位符。
 * @param {string} variable 服务端白名单变量名。
 * @returns {string} 双花括号模板标记。
 */
function templateToken(variable) {
  return `{{${variable}}}`
}

/**
 * 校验通知策略，并以服务端 revision 保存正式策略数据。
 * @returns {Promise<void>} 校验失败时停留在表单；保存成功后关闭弹窗并刷新策略列表。
 */
async function submitPolicy() {
  // valid 表示整张策略表单是否通过校验；用户输入错误属于正常分支，不应产生未处理 Promise。
  const valid = await policyFormRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await saveWorkflowNotificationPolicy({
      ...policyDialog.form,
      processDefinitionKey: policyDialog.form.processDefinitionKey || null,
      taskDefinitionKey: policyDialog.form.taskDefinitionKey || null,
      recipientRules: policyDialog.form.recipientRuleList.join(','),
      channels: ['INBOX', 'EMAIL', 'SMS'].filter(item => policyDialog.form.channelList.includes(item)).join(','),
      smsTemplateId: policyDialog.form.channelList.includes('SMS') ? policyDialog.form.smsTemplateId : null
    })
    policyDialog.visible = false
    proxy.$modal.msgSuccess('通知策略已保存')
    await loadActiveTab()
  } finally {
    saving.value = false
  }
}

/**
 * 补偿一条死信并刷新状态。
 * @param {object} row outbox 行。
 * @returns {Promise<void>} 服务端接受补偿后刷新列表。
 */
async function compensate(row) {
  await proxy.$modal.confirm(`确认重新投递通知 ${row.outboxId} 吗？`)
  await compensateWorkflowNotification(row.outboxId)
  proxy.$modal.msgSuccess('死信已重新进入投递队列')
  await loadActiveTab()
}

/** @param {string} scope 作用域。 @returns {string} 中文作用域。 */
function scopeLabel(scope) { return ({ DEFAULT: '全局', PROCESS: '流程', NODE: '节点' }[scope] || scope) }
/** @param {string} scope 作用域。 @returns {string} Element Plus 标签类型。 */
function scopeType(scope) { return ({ DEFAULT: 'info', PROCESS: 'primary', NODE: 'warning' }[scope] || 'info') }
/** @param {string} status outbox 状态。 @returns {string} 中文状态。 */
function statusLabel(status) { return ({ PENDING: '待投递', RETRYING: '重试中', DELIVERING: '投递中', PROCESSED: '已送达', DEAD_LETTER: '死信', CANCELLED: '已取消' }[status] || status) }
/** @param {string} status outbox 状态。 @returns {string} Element Plus 标签类型。 */
function statusType(status) { return ({ PROCESSED: 'success', DEAD_LETTER: 'danger', RETRYING: 'warning', DELIVERING: 'primary' }[status] || 'info') }

onMounted(loadActiveTab)
</script>

<style scoped lang="scss">
.notification-admin__header { display: flex; min-height: 58px; align-items: center; justify-content: space-between; gap: 16px; }
.notification-admin__header h2 { margin: 0 0 4px; font-size: 18px; font-weight: 600; }
.notification-admin__header span { color: var(--el-text-color-secondary); font-size: 12px; }
.notification-admin__actions { display: flex; align-items: center; gap: 8px; }
.notification-admin__form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.notification-admin__form-grid .el-select, .notification-admin__form-grid .el-input { width: 100%; }
.notification-admin__variables { display: flex; flex-wrap: wrap; gap: 6px; padding-left: 110px; }
.notification-admin__filter { margin: 12px 0 4px; }
.notification-admin__filter :deep(.el-input) { width: 260px; }
.notification-admin__filter :deep(.el-select) { width: 150px; }
.notification-admin__filter :deep(.el-date-editor) { width: 360px; }
.notification-admin__variables button { padding: 3px 7px; border: 1px solid var(--el-border-color); border-radius: 4px; background: var(--el-fill-color-light); color: var(--el-text-color-regular); font-size: 11px; cursor: pointer; }
.notification-admin__variables button:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); }
@media (max-width: 700px) {
  .notification-admin__header { align-items: flex-start; flex-direction: column; }
  .notification-admin__actions { width: 100%; justify-content: flex-end; }
  .notification-admin__form-grid { grid-template-columns: 1fr; }
  .notification-admin__variables { padding-left: 0; }
  .notification-admin__filter :deep(.el-input),
  .notification-admin__filter :deep(.el-select),
  .notification-admin__filter :deep(.el-date-editor) { width: 100%; }
}
</style>
