<template>
  <div class="app-container bpmn-event-page">
    <div class="page-heading">
      <div>
        <h2>错误与升级边界</h2>
        <p>统一管理正式编码、运行捕获审计和发起人站内通知。</p>
      </div>
      <el-button type="primary" icon="Plus" v-hasPermi="['workflow:bpmnEvent:add']" @click="openCreate">新增编码</el-button>
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
      </el-tab-pane>

      <el-tab-pane label="我的通知" name="notifications">
        <el-table v-loading="loading.notifications" :data="notifications">
          <el-table-column label="时间" width="170"><template #default="scope">{{ parseTime(scope.row.createTime) }}</template></el-table-column>
          <el-table-column label="标题" min-width="220" prop="title" />
          <el-table-column label="内容" min-width="300" prop="content" show-overflow-tooltip />
          <el-table-column label="状态" width="90">
            <template #default="scope"><el-tag :type="scope.row.readStatus === 'READ' ? 'info' : 'warning'">{{ scope.row.readStatus === 'READ' ? '已读' : '未读' }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="88">
            <template #default="scope"><el-button v-if="scope.row.readStatus === 'UNREAD'" link type="primary" @click="markRead(scope.row)">标为已读</el-button></template>
          </el-table-column>
        </el-table>
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
  </div>
</template>

<script setup name="WorkflowBpmnEvent">
import {
  changeBpmnEventCodeStatus,
  createBpmnEventCode,
  listBpmnEventAudit,
  listBpmnEventCodes,
  listMyBpmnEventNotifications,
  markBpmnEventNotificationRead,
  updateBpmnEventCode
} from '@/api/workflow/bpmnEvent'

const { proxy } = getCurrentInstance()
const activeTab = ref('codes')
const loading = reactive({ codes: false, audit: false, notifications: false })
const saving = ref(false)
const codes = ref([])
const auditRows = ref([])
const notifications = ref([])
const dialogOpen = ref(false)
const formRef = ref(null)
const form = reactive(emptyForm())
const eventTypes = Object.freeze([{ label: '业务错误', value: 'ERROR' }, { label: '业务升级', value: 'ESCALATION' }])
const rules = {
  eventType: [{ required: true, message: '请选择事件类型', trigger: 'change' }],
  eventCode: [
    { required: true, message: '稳定编码不能为空', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_.-]{1,63}$/, message: '编码必须以大写字母开头，仅含大写字母、数字、点、横线或下划线', trigger: 'blur' }
  ],
  eventName: [{ required: true, message: '显示名称不能为空', trigger: 'blur' }]
}

/** @returns {object} 新增表单的稳定初始值。 */
function emptyForm() {
  return { eventCodeId: null, eventType: 'ERROR', eventCode: '', eventName: '', notificationPolicy: 'INITIATOR', description: '' }
}

/** @returns {Promise<void>} 按当前页签调用真实后端加载数据。 */
async function loadActiveTab() {
  if (activeTab.value === 'codes') return loadCodes()
  if (activeTab.value === 'audit') return loadAudit()
  return loadNotifications()
}

/** @returns {Promise<void>} 加载正式编码目录。 */
async function loadCodes() {
  loading.codes = true
  try { codes.value = (await listBpmnEventCodes()).data || [] } finally { loading.codes = false }
}

/** @returns {Promise<void>} 加载专用运行审计。 */
async function loadAudit() {
  loading.audit = true
  try { auditRows.value = (await listBpmnEventAudit()).data || [] } finally { loading.audit = false }
}

/** @returns {Promise<void>} 加载当前用户站内通知。 */
async function loadNotifications() {
  loading.notifications = true
  try { notifications.value = (await listMyBpmnEventNotifications()).data || [] } finally { loading.notifications = false }
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

/** @param {object} row 当前用户通知；@returns {Promise<void>} 服务端鉴权后标记已读。 */
async function markRead(row) {
  await markBpmnEventNotificationRead(row.notificationId)
  await loadNotifications()
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
.bpmn-event-page :deep(.el-select), .bpmn-event-page :deep(.el-segmented) { width: 100%; }
</style>
