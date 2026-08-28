<template>
  <div class="app-container notification-admin">
    <header class="notification-admin__header">
      <h2>审批通知</h2>
      <div class="notification-admin__actions">
        <el-button
          v-if="activeTab"
          circle
          text
          icon="Refresh"
          aria-label="刷新"
          :loading="activeLoading"
          @click="loadActiveTab"
        />
        <el-button v-if="canMailManage" icon="Message" @click="openMailConfig">邮件服务</el-button>
        <el-button
          v-if="canManagePolicy && activeTab === 'policies'"
          type="primary"
          icon="Plus"
          @click="openPolicy()"
        >
          新增策略
        </el-button>
      </div>
    </header>

    <el-tabs v-if="canManagePolicy || canAuditOutbox" v-model="activeTab" class="notification-admin__tabs" @tab-change="handleTabChange">
      <el-tab-pane v-if="canManagePolicy" label="通知策略" name="policies">
        <div class="notification-admin__toolbar">
          <el-input
            v-model="policyQuery.keyword"
            clearable
            prefix-icon="Search"
            placeholder="搜索通知场景、流程或节点"
          />
          <el-select v-model="policyQuery.scopeType" clearable placeholder="全部作用范围">
            <el-option v-for="scope in scopeOptions" :key="scope.value" :label="scope.label" :value="scope.value" />
          </el-select>
          <el-select v-model="policyQuery.channel" clearable placeholder="全部通知方式">
            <el-option v-for="channel in channelOptions" :key="channel.value" :label="channel.label" :value="channel.value" />
          </el-select>
        </div>

        <el-table v-loading="policyLoading" :data="visiblePolicies" row-key="policyId" class="notification-admin__table">
          <el-table-column label="作用范围" width="112">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="scopeType(row.scopeType)">{{ scopeLabel(row.scopeType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="通知场景" min-width="210">
            <template #default="{ row }">
              <div class="notification-admin__primary-cell">
                <strong>{{ eventLabel(row.eventType) }}</strong>
                <small>{{ row.eventType }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="适用流程 / 节点" min-width="250" show-overflow-tooltip>
            <template #default="{ row }">{{ policyTargetLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="通知对象" min-width="180">
            <template #default="{ row }">{{ recipientLabels(row.recipientRules).join('、') }}</template>
          </el-table-column>
          <el-table-column label="通知方式" min-width="150">
            <template #default="{ row }">
              <el-tag
                v-for="channel in csvValues(row.channels)"
                :key="channel"
                size="small"
                effect="plain"
                :type="channelTagType(channel)"
                class="notification-admin__tag"
              >
                {{ channelLabel(channel) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="92">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">
                {{ row.status === 'ENABLED' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="76" align="center" fixed="right">
            <template #default="{ row }">
              <el-tooltip content="编辑策略" placement="top">
                <el-button circle text icon="Edit" aria-label="编辑策略" @click="openPolicy(row)" />
              </el-tooltip>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无符合条件的通知策略" :image-size="76" /></template>
        </el-table>

        <div v-if="filteredPolicies.length > policyQuery.pageSize" class="notification-admin__pagination">
          <el-pagination
            v-model:current-page="policyQuery.pageNum"
            v-model:page-size="policyQuery.pageSize"
            :page-sizes="[10, 20, 50]"
            :total="filteredPolicies.length"
            layout="total, sizes, prev, pager, next"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="canAuditOutbox" label="投递运维" name="outbox">
        <div class="notification-admin__toolbar notification-admin__toolbar--outbox">
          <el-input
            v-model="outboxQuery.keyword"
            clearable
            prefix-icon="Search"
            placeholder="搜索流程或投递编号"
            @keyup.enter="queryOutbox"
          />
          <el-select v-model="outboxQuery.eventType" clearable filterable placeholder="全部通知场景">
            <el-option v-for="event in eventOptions" :key="event.value" :label="event.label" :value="event.value" />
          </el-select>
          <el-select v-model="outboxQuery.status" clearable placeholder="全部投递状态">
            <el-option v-for="status in outboxStatusOptions" :key="status" :label="statusLabel(status)" :value="status" />
          </el-select>
          <el-select v-model="outboxQuery.channel" clearable placeholder="全部通知方式">
            <el-option label="邮件" value="EMAIL" />
            <el-option label="短信" value="SMS" />
          </el-select>
          <el-button type="primary" icon="Search" @click="queryOutbox">查询</el-button>
          <el-button icon="Refresh" @click="resetOutboxQuery">重置</el-button>
        </div>

        <el-table v-loading="outboxLoading" :data="outbox" row-key="outboxId" class="notification-admin__table">
          <el-table-column label="通知场景" min-width="190">
            <template #default="{ row }">
              <div class="notification-admin__primary-cell">
                <strong>{{ eventLabel(row.eventType) }}</strong>
                <small>#{{ row.outboxId }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="流程" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.processInstanceId || row.sourceId || '-' }}</template>
          </el-table-column>
          <el-table-column label="通知对象" width="132">
            <template #default="{ row }">{{ outboxRecipientLabel(row) }}</template>
          </el-table-column>
          <el-table-column label="通知方式" width="104">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="channelTagType(row.channel)">{{ channelLabel(row.channel) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="投递状态" width="112">
            <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="尝试次数" width="92" align="center">
            <template #default="{ row }">{{ row.attemptCount }} / {{ row.maxAttempts }}</template>
          </el-table-column>
          <el-table-column label="发生时间" width="170">
            <template #default="{ row }">{{ parseTime(row.createTime) || '-' }}</template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">{{ row.lastErrorSummary || '-' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="82" align="center" fixed="right">
            <template #default="{ row }">
              <el-tooltip v-if="canCompensate(row)" content="补偿重试" placement="top">
                <el-button
                  circle
                  text
                  type="primary"
                  icon="RefreshRight"
                  aria-label="补偿重试"
                  :loading="isCompensating(row.outboxId)"
                  :disabled="isCompensating(row.outboxId)"
                  @click="compensate(row)"
                />
              </el-tooltip>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无符合条件的投递记录" :image-size="76" /></template>
        </el-table>
        <pagination
          v-show="outboxTotal > 0"
          :total="outboxTotal"
          v-model:page="outboxQuery.pageNum"
          v-model:limit="outboxQuery.pageSize"
          @pagination="loadOutbox"
        />
      </el-tab-pane>
    </el-tabs>

    <el-empty v-else description="当前账号没有审批通知管理权限" />

    <el-dialog
      v-model="policyDialog.visible"
      :title="policyDialog.form.policyId ? '编辑通知策略' : '新增通知策略'"
      width="680px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="policyFormRef" :model="policyDialog.form" :rules="policyRules" label-position="top">
        <el-alert
          title="节点规则优先于流程规则，流程规则优先于全局规则。"
          type="info"
          :closable="false"
          show-icon
          class="notification-admin__dialog-tip"
        />

        <el-form-item label="作用范围" prop="scopeType">
          <el-radio-group v-model="policyDialog.form.scopeType" @change="handleScopeChange">
            <el-radio-button v-for="scope in scopeOptions" :key="scope.value" :value="scope.value">{{ scope.dialogLabel }}</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <div class="notification-admin__form-grid">
          <el-form-item v-if="policyDialog.form.scopeType !== 'DEFAULT'" label="选择流程" prop="processDefinitionKey">
            <el-select
              v-model="policyDialog.form.processDefinitionKey"
              filterable
              placeholder="请选择流程"
              :loading="policyLoading"
              @change="handleProcessChange"
            >
              <el-option
                v-for="process in processOptions"
                :key="process.processDefinitionKey"
                :label="processOptionLabel(process)"
                :value="process.processDefinitionKey"
              />
            </el-select>
          </el-form-item>
          <el-form-item v-if="policyDialog.form.scopeType === 'NODE'" label="选择节点" prop="taskDefinitionKey">
            <el-select
              v-model="policyDialog.form.taskDefinitionKey"
              filterable
              placeholder="请选择节点"
              :loading="nodeLoading"
              :disabled="!policyDialog.form.processDefinitionKey"
            >
              <el-option
                v-for="node in currentNodeOptions"
                :key="node.taskDefinitionKey"
                :label="node.taskName"
                :value="node.taskDefinitionKey"
              />
            </el-select>
            <el-alert
              v-if="currentNodeLoadError"
              :title="currentNodeLoadError"
              type="error"
              :closable="false"
              show-icon
              class="notification-admin__node-error"
            />
          </el-form-item>
          <el-form-item label="通知场景" prop="eventType">
            <el-select v-model="policyDialog.form.eventType" filterable @change="handleEventChange">
              <el-option v-for="event in eventOptions" :key="event.value" :label="event.label" :value="event.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用状态" prop="status">
            <el-select v-model="policyDialog.form.status" @change="validatePolicyChannels">
              <el-option label="启用" value="ENABLED" />
              <el-option label="停用" value="DISABLED" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="通知对象" prop="recipientRuleList">
          <el-checkbox-group v-model="policyDialog.form.recipientRuleList">
            <el-checkbox v-for="recipient in recipientOptions" :key="recipient.value" :value="recipient.value">{{ recipient.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="通知方式" prop="channelList">
          <el-checkbox-group v-model="policyDialog.form.channelList" @change="validatePolicyChannels">
            <el-checkbox v-for="channel in channelOptions" :key="channel.value" :value="channel.value">{{ channel.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <div v-if="emailUnavailable" class="notification-admin__mail-guard">
          <el-alert title="邮件服务尚未配置，启用邮件通知前必须先完成配置。" type="warning" :closable="false" show-icon />
          <el-button v-if="canMailManage" link type="primary" @click="openMailConfig">配置邮件服务</el-button>
          <span v-else>请联系具有邮件服务管理权限的管理员。</span>
        </div>

        <el-form-item v-if="policyDialog.form.channelList.includes('SMS')" label="短信模板 ID" prop="smsTemplateId">
          <el-input v-model="policyDialog.form.smsTemplateId" maxlength="64" placeholder="请输入供应商审核模板 ID" />
        </el-form-item>

        <el-collapse v-model="advancedPanels" class="notification-admin__advanced">
          <el-collapse-item title="自定义通知内容（可选）" name="content">
            <el-form-item label="通知标题" prop="titleTemplate">
              <el-input v-model="policyDialog.form.titleTemplate" maxlength="160" show-word-limit />
            </el-form-item>
            <el-form-item label="通知正文" prop="contentTemplate">
              <el-input v-model="policyDialog.form.contentTemplate" type="textarea" :rows="4" maxlength="700" show-word-limit />
            </el-form-item>
            <div class="notification-admin__variables">
              <button
                v-for="variable in templateVariables"
                :key="variable.value"
                type="button"
                @click="appendVariable(variable.value)"
              >
                {{ variable.label }}
              </button>
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button :disabled="policySaving" @click="policyDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="policySaving" @click="submitPolicy">保存</el-button>
      </template>
    </el-dialog>

    <MailConfigDialog v-if="canMailManage" v-model="mailConfigVisible" @saved="handleMailConfigSaved" />
  </div>
</template>

<script setup name="WorkflowNotification">
import {
  compensateWorkflowNotification,
  listWorkflowNotificationNodes,
  listWorkflowNotificationOutbox,
  listWorkflowNotificationPolicies,
  listWorkflowNotificationProcesses,
  saveWorkflowNotificationPolicy
} from '@/api/workflow/notification'
import MailConfigDialog from './MailConfigDialog.vue'
import {
  WORKFLOW_NOTIFICATION_PERMISSIONS,
  canCompensateNotificationOutbox,
  nodeCatalogValidationError,
  processCatalogValidationError,
  resolveNotificationInitialTab
} from './notificationAdminRules.js'

const { proxy } = getCurrentInstance()

const scopeOptions = Object.freeze([
  { value: 'DEFAULT', label: '全局', dialogLabel: '所有流程' },
  { value: 'PROCESS', label: '指定流程', dialogLabel: '指定流程' },
  { value: 'NODE', label: '指定节点', dialogLabel: '指定节点' }
])
const eventOptions = Object.freeze([
  { value: 'TASK_ARRIVED', label: '出现新待办' },
  { value: 'TASK_CLAIMED', label: '任务被认领' },
  { value: 'TASK_UNCLAIMED', label: '任务被释放' },
  { value: 'TASK_DELEGATED', label: '任务被委派' },
  { value: 'TASK_DELEGATION_RESOLVED', label: '委派任务已归还' },
  { value: 'TASK_TRANSFERRED', label: '任务被转办' },
  { value: 'TASK_RETURNED', label: '申请被退回修改' },
  { value: 'TASK_RESUBMITTED', label: '申请重新提交' },
  { value: 'TASK_COMPLETED', label: '审批节点完成' },
  { value: 'PROCESS_COMPLETED', label: '流程审批完成' },
  { value: 'PROCESS_CANCELED', label: '流程被取消' },
  { value: 'PROCESS_REJECTED', label: '流程被驳回' },
  { value: 'PROCESS_TERMINATED', label: '流程被管理员终止' },
  { value: 'MANUAL_URGE', label: '收到人工催办' },
  { value: 'COPY_CREATED', label: '收到流程抄送' }
])
const recipientOptions = Object.freeze([
  { value: 'TASK_RECIPIENT', label: '当前办理人' },
  { value: 'INITIATOR', label: '流程发起人' },
  { value: 'ACTOR', label: '当前操作人' }
])
const channelOptions = Object.freeze([
  { value: 'INBOX', label: '站内信' },
  { value: 'EMAIL', label: '邮件' },
  { value: 'SMS', label: '短信' }
])
const templateVariables = Object.freeze([
  { value: 'processName', label: '流程名称' },
  { value: 'processDefinitionKey', label: '流程标识' },
  { value: 'processInstanceId', label: '流程编号' },
  { value: 'taskName', label: '节点名称' },
  { value: 'taskDefinitionKey', label: '节点标识' },
  { value: 'eventType', label: '通知场景' }
])
const defaultTemplates = Object.freeze({
  TASK_ARRIVED: ['新待办：{{taskName}}', '流程“{{processName}}”有新的待办任务“{{taskName}}”。'],
  TASK_CLAIMED: ['任务已认领：{{taskName}}', '您已成为流程“{{processName}}”任务“{{taskName}}”的办理人。'],
  TASK_UNCLAIMED: ['任务已释放：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已重新进入待认领状态。'],
  TASK_DELEGATED: ['任务已委派：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已委派给您。'],
  TASK_DELEGATION_RESOLVED: ['委派任务已归还：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已归还原办理人。'],
  TASK_TRANSFERRED: ['任务已转办：{{taskName}}', '流程“{{processName}}”任务“{{taskName}}”已转办给您。'],
  TASK_RETURNED: ['申请已退回修改', '流程“{{processName}}”已退回，请修改后重新提交。'],
  TASK_RESUBMITTED: ['申请已重新提交：{{taskName}}', '流程“{{processName}}”已修改并重新提交，请继续处理“{{taskName}}”。'],
  TASK_COMPLETED: ['审批节点已完成：{{taskName}}', '流程“{{processName}}”的节点“{{taskName}}”已完成。'],
  PROCESS_COMPLETED: ['流程已完成：{{processName}}', '您的流程“{{processName}}”已完成。'],
  PROCESS_CANCELED: ['流程已取消：{{processName}}', '流程“{{processName}}”已取消。'],
  PROCESS_REJECTED: ['流程已驳回：{{processName}}', '流程“{{processName}}”已驳回并结束。'],
  PROCESS_TERMINATED: ['流程已终止：{{processName}}', '流程“{{processName}}”已由管理员终止。'],
  MANUAL_URGE: ['审批催办：{{taskName}}', '发起人提醒您处理流程“{{processName}}”的待办“{{taskName}}”。'],
  COPY_CREATED: ['流程抄送：{{processName}}', '您收到流程“{{processName}}”的审批抄送。']
})
const outboxStatusOptions = Object.freeze(['PENDING', 'RETRYING', 'DELIVERING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED'])

// 四个权限分别约束页面读取和业务入口，避免仅隐藏按钮却继续发出无权请求。
const canManagePolicy = computed(() => proxy.$auth.hasPermi(WORKFLOW_NOTIFICATION_PERMISSIONS.manage))
const canAuditOutbox = computed(() => proxy.$auth.hasPermi(WORKFLOW_NOTIFICATION_PERMISSIONS.audit))
const canRetryOutbox = computed(() => proxy.$auth.hasPermi(WORKFLOW_NOTIFICATION_PERMISSIONS.retry))
const canMailManage = computed(() => proxy.$auth.hasPermi(WORKFLOW_NOTIFICATION_PERMISSIONS.mailManage))

const activeTab = ref(resolveInitialTab())
const policyLoading = ref(false)
const outboxLoading = ref(false)
const policySaving = ref(false)
const nodeLoading = ref(false)
const policies = ref([])
const processOptions = ref([])
// nodeCatalogs 以流程定义 key 隔离真实节点目录，切换流程时不会复用其他流程节点。
const nodeCatalogs = ref({})
// nodeCatalogErrors 单独记录目录读取失败，禁止把失败结果伪装成“流程没有节点”。
const nodeCatalogErrors = ref({})
const mailChannelAvailable = ref(false)
const mailConfigVisible = ref(false)
const policyFormRef = ref(null)
const advancedPanels = ref([])
const policyDialog = reactive({ visible: false, form: createEmptyPolicy() })
const policyQuery = reactive({ keyword: '', scopeType: '', channel: '', pageNum: 1, pageSize: 10 })
const outbox = ref([])
const outboxTotal = ref(0)
const outboxQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '', status: '', eventType: '', channel: '' })
// compensatingIds 是行级运行集合，阻止同一死信被当前页面并发重复提交。
const compensatingIds = ref(new Set())
const activeLoading = computed(() => activeTab.value === 'policies' ? policyLoading.value : outboxLoading.value)
const currentNodeOptions = computed(() => nodeCatalogs.value[policyDialog.form.processDefinitionKey] || [])
const currentNodeLoadError = computed(() => nodeCatalogErrors.value[policyDialog.form.processDefinitionKey] || '')
const emailUnavailable = computed(() => policyDialog.form.channelList.includes('EMAIL') && !mailChannelAvailable.value)
const filteredPolicies = computed(() => {
  const keyword = policyQuery.keyword.trim().toLowerCase()
  return policies.value.filter(row => {
    const matchesKeyword = !keyword || policySearchText(row).includes(keyword)
    const matchesScope = !policyQuery.scopeType || row.scopeType === policyQuery.scopeType
    const matchesChannel = !policyQuery.channel || csvValues(row.channels).includes(policyQuery.channel)
    return matchesKeyword && matchesScope && matchesChannel
  })
})
const visiblePolicies = computed(() => {
  const offset = (policyQuery.pageNum - 1) * policyQuery.pageSize
  return filteredPolicies.value.slice(offset, offset + policyQuery.pageSize)
})
let policyLoadSequence = 0
let nodeLoadSequence = 0
let pageInitialized = false

const policyRules = {
  scopeType: [{ required: true, message: '请选择作用范围', trigger: 'change' }],
  processDefinitionKey: [{ validator: validateProcessSelection, trigger: 'change' }],
  taskDefinitionKey: [{ validator: validateNodeSelection, trigger: 'change' }],
  eventType: [{ required: true, message: '请选择通知场景', trigger: 'change' }],
  recipientRuleList: [{ type: 'array', required: true, min: 1, message: '请选择通知对象', trigger: 'change' }],
  channelList: [{ validator: validateChannels, trigger: 'change' }],
  smsTemplateId: [{ validator: validateSmsTemplate, trigger: 'blur' }],
  titleTemplate: [{ required: true, message: '请输入通知标题', trigger: 'blur' }],
  contentTemplate: [{ required: true, message: '请输入通知正文', trigger: 'blur' }],
  status: [{ required: true, message: '请选择启用状态', trigger: 'change' }]
}

/**
 * 根据当前用户实际权限选择首个可读取页签。
 * @returns {'policies'|'outbox'|''} 策略、投递或无可读页签。
 */
function resolveInitialTab() {
  return resolveNotificationInitialTab(permission => proxy.$auth.hasPermi(permission))
}

/**
 * 创建新增策略的完整服务端请求草稿。
 * @returns {object} 使用正式默认模板、站内信和六次尝试的新增表单。
 */
function createEmptyPolicy() {
  const [titleTemplate, contentTemplate] = defaultTemplates.TASK_ARRIVED
  return {
    policyId: null,
    scopeType: 'DEFAULT',
    processDefinitionKey: '',
    taskDefinitionKey: '',
    eventType: 'TASK_ARRIVED',
    recipientRuleList: ['TASK_RECIPIENT'],
    channelList: ['INBOX'],
    smsTemplateId: '',
    titleTemplate,
    contentTemplate,
    maxAttempts: 6,
    status: 'ENABLED',
    expectedRevision: null
  }
}

/**
 * 按当前页签调用唯一有权访问的正式查询接口。
 * @returns {Promise<void>} 无可读页签时不产生网络请求。
 */
async function loadActiveTab() {
  if (activeTab.value === 'policies' && canManagePolicy.value) {
    await loadPolicies()
    return
  }
  if (activeTab.value === 'outbox' && canAuditOutbox.value) await loadOutbox()
}

/**
 * 处理页签切换，仅加载目标页签对应的授权数据。
 * @param {string} tabName Element Plus 页签名称。
 * @returns {Promise<void>} 权限不匹配时不请求后端。
 */
async function handleTabChange(tabName) {
  if ((tabName === 'policies' && !canManagePolicy.value) || (tabName === 'outbox' && !canAuditOutbox.value)) return
  await loadActiveTab()
}

/**
 * 原子加载策略、授权流程目录和策略涉及的真实节点目录。
 * @returns {Promise<void>} 旧请求结果不会覆盖较新的策略和目录快照。
 */
async function loadPolicies() {
  if (!canManagePolicy.value) return
  const sequence = ++policyLoadSequence
  policyLoading.value = true
  try {
    const [policyResponse, processResponse] = await Promise.all([
      listWorkflowNotificationPolicies(),
      listWorkflowNotificationProcesses()
    ])
    const nextPolicies = Array.isArray(policyResponse.data) ? policyResponse.data : []
    const nextProcesses = normalizeProcessCatalog(processResponse.data)
    const nodeProcessKeys = [...new Set(nextPolicies
      .filter(row => row.scopeType === 'NODE' && typeof row.processDefinitionKey === 'string')
      .map(row => row.processDefinitionKey))]
    const nodeEntries = await Promise.all(nodeProcessKeys.map(async processKey => {
      try {
        const response = await listWorkflowNotificationNodes(processKey)
        return { processKey, nodes: normalizeNodeCatalog(response.data), errorMessage: '' }
      } catch (error) {
        return {
          processKey,
          nodes: null,
          errorMessage: requestErrorMessage(error, '节点目录加载失败，请稍后重试')
        }
      }
    }))
    if (sequence !== policyLoadSequence) return
    const nextNodeCatalogs = {}
    const nextNodeCatalogErrors = {}
    nodeEntries.forEach(entry => {
      if (entry.nodes) nextNodeCatalogs[entry.processKey] = entry.nodes
      else nextNodeCatalogErrors[entry.processKey] = entry.errorMessage
    })
    policies.value = nextPolicies
    processOptions.value = nextProcesses
    nodeCatalogs.value = nextNodeCatalogs
    nodeCatalogErrors.value = nextNodeCatalogErrors
    mailChannelAvailable.value = policyResponse.mailChannelAvailable === true
    const lastPage = Math.max(1, Math.ceil(filteredPolicies.value.length / policyQuery.pageSize))
    policyQuery.pageNum = Math.min(policyQuery.pageNum, lastPage)
    const failedEntries = nodeEntries.filter(entry => entry.nodes === null)
    if (failedEntries.length > 0) {
      const suffix = failedEntries.length > 1 ? `（另有 ${failedEntries.length - 1} 个流程读取失败）` : ''
      proxy.$modal.msgError(`节点目录加载失败：${failedEntries[0].errorMessage}${suffix}`)
    }
  } finally {
    if (sequence === policyLoadSequence) policyLoading.value = false
  }
}

/**
 * 规范化流程目录，只接受约定字段并去除重复流程定义 key。
 * @param {unknown} data 后端流程目录响应 data。
 * @returns {Array<object>} 可直接用于只读选择器的流程目录。
 */
function normalizeProcessCatalog(data) {
  if (!Array.isArray(data)) return []
  const unique = new Map()
  data.forEach(item => {
    const processDefinitionKey = typeof item?.processDefinitionKey === 'string' ? item.processDefinitionKey.trim() : ''
    const processName = typeof item?.processName === 'string' ? item.processName.trim() : ''
    if (!processDefinitionKey || !processName || unique.has(processDefinitionKey)) return
    unique.set(processDefinitionKey, { processDefinitionKey, processName, version: Number(item.version) || 0 })
  })
  return [...unique.values()]
}

/**
 * 规范化用户任务节点目录，只接受约定字段并去除重复节点 key。
 * @param {unknown} data 后端节点目录响应 data。
 * @returns {Array<object>} 当前流程可选择的真实节点目录。
 */
function normalizeNodeCatalog(data) {
  if (!Array.isArray(data)) return []
  const unique = new Map()
  data.forEach(item => {
    const taskDefinitionKey = typeof item?.taskDefinitionKey === 'string' ? item.taskDefinitionKey.trim() : ''
    const taskName = typeof item?.taskName === 'string' ? item.taskName.trim() : ''
    if (!taskDefinitionKey || !taskName || unique.has(taskDefinitionKey)) return
    unique.set(taskDefinitionKey, { taskDefinitionKey, taskName })
  })
  return [...unique.values()]
}

/**
 * 从第一页按当前筛选查询正式通知 outbox。
 * @returns {void} 查询结果由 loadOutbox 写入当前分页。
 */
function queryOutbox() {
  outboxQuery.pageNum = 1
  loadOutbox()
}

/**
 * 恢复投递运维默认筛选并重新查询正式数据。
 * @returns {void} 清空搜索、场景、状态和通知方式。
 */
function resetOutboxQuery() {
  Object.assign(outboxQuery, { pageNum: 1, pageSize: 20, keyword: '', status: '', eventType: '', channel: '' })
  loadOutbox()
}

/**
 * 按当前筛选和页码读取脱敏 outbox。
 * @returns {Promise<void>} 无 audit 权限时不调用接口。
 */
async function loadOutbox() {
  if (!canAuditOutbox.value) return
  outboxLoading.value = true
  try {
    const response = await listWorkflowNotificationOutbox({
      pageNum: outboxQuery.pageNum,
      pageSize: outboxQuery.pageSize,
      keyword: outboxQuery.keyword.trim() || undefined,
      status: outboxQuery.status || undefined,
      eventType: outboxQuery.eventType || undefined,
      channel: outboxQuery.channel || undefined
    })
    outbox.value = Array.isArray(response.rows) ? response.rows : []
    outboxTotal.value = Number(response.total || 0)
  } finally {
    outboxLoading.value = false
  }
}

/**
 * 打开新增或编辑策略弹窗，并在节点策略下刷新对应真实节点目录。
 * @param {object|null} row 当前策略行；空值表示新增。
 * @returns {Promise<void>} 表单只接收服务端策略和目录值，不提供手工 key 输入。
 */
async function openPolicy(row = null) {
  if (!canManagePolicy.value) return
  policyDialog.form = row ? policyFormFromRow(row) : createEmptyPolicy()
  advancedPanels.value = []
  policyDialog.visible = true
  if (policyDialog.form.scopeType === 'NODE' && policyDialog.form.processDefinitionKey) {
    await loadDialogNodes(policyDialog.form.processDefinitionKey)
  }
  await nextTick()
  policyFormRef.value?.clearValidate()
}

/**
 * 将策略列表行转换为独立编辑表单并冻结读取时 revision。
 * @param {object} row 服务端策略行。
 * @returns {object} 不会直接改写列表快照的编辑草稿。
 */
function policyFormFromRow(row) {
  return {
    policyId: row.policyId,
    scopeType: row.scopeType,
    processDefinitionKey: row.processDefinitionKey || '',
    taskDefinitionKey: row.taskDefinitionKey || '',
    eventType: row.eventType,
    recipientRuleList: csvValues(row.recipientRules),
    channelList: csvValues(row.channels),
    smsTemplateId: row.smsTemplateId || '',
    titleTemplate: row.titleTemplate || '',
    contentTemplate: row.contentTemplate || '',
    maxAttempts: Number(row.maxAttempts) || 6,
    status: row.status,
    expectedRevision: Number(row.revision)
  }
}

/**
 * 作用范围变化时清除已经不再具有业务含义的流程和节点选择。
 * @returns {void} 全局范围不保留流程，非节点范围不保留节点。
 */
function handleScopeChange() {
  if (policyDialog.form.scopeType === 'DEFAULT') policyDialog.form.processDefinitionKey = ''
  if (policyDialog.form.scopeType !== 'NODE') policyDialog.form.taskDefinitionKey = ''
  if (policyDialog.form.scopeType === 'NODE' && policyDialog.form.processDefinitionKey) {
    loadDialogNodes(policyDialog.form.processDefinitionKey)
  }
  nextTick(() => policyFormRef.value?.clearValidate(['processDefinitionKey', 'taskDefinitionKey']))
}

/**
 * 流程变化时清除旧流程节点，并为节点策略加载新目录。
 * @param {string} processDefinitionKey 新选择的真实流程定义 key。
 * @returns {Promise<void>} 节点范围下加载当前流程真实用户任务。
 */
async function handleProcessChange(processDefinitionKey) {
  policyDialog.form.taskDefinitionKey = ''
  if (policyDialog.form.scopeType === 'NODE' && processDefinitionKey) await loadDialogNodes(processDefinitionKey)
  policyFormRef.value?.clearValidate(['processDefinitionKey', 'taskDefinitionKey'])
}

/**
 * 为策略弹窗刷新指定流程的真实节点目录，并淘汰切换流程前的旧响应。
 * @param {string} processDefinitionKey 当前选择的流程定义 key。
 * @returns {Promise<void>} 当前选择未变化时更新节点选项。
 */
async function loadDialogNodes(processDefinitionKey) {
  const sequence = ++nodeLoadSequence
  nodeLoading.value = true
  try {
    const response = await listWorkflowNotificationNodes(processDefinitionKey)
    if (sequence !== nodeLoadSequence || policyDialog.form.processDefinitionKey !== processDefinitionKey) return
    nodeCatalogs.value = { ...nodeCatalogs.value, [processDefinitionKey]: normalizeNodeCatalog(response.data) }
    const nextErrors = { ...nodeCatalogErrors.value }
    delete nextErrors[processDefinitionKey]
    nodeCatalogErrors.value = nextErrors
  } catch (error) {
    if (sequence !== nodeLoadSequence || policyDialog.form.processDefinitionKey !== processDefinitionKey) return
    const errorMessage = requestErrorMessage(error, '节点目录加载失败，请稍后重试')
    // 失败时移除可能陈旧的目录，并以独立错误状态阻止管理员误把失败理解为真实空目录。
    const nextCatalogs = { ...nodeCatalogs.value }
    delete nextCatalogs[processDefinitionKey]
    nodeCatalogs.value = nextCatalogs
    nodeCatalogErrors.value = { ...nodeCatalogErrors.value, [processDefinitionKey]: errorMessage }
    proxy.$modal.msgError(`节点目录加载失败：${errorMessage}`)
  } finally {
    if (sequence === nodeLoadSequence) nodeLoading.value = false
  }
}

/**
 * 新增策略切换通知场景时同步替换尚未自定义的默认标题和正文。
 * @param {string} eventType 新选择的通知场景。
 * @returns {void} 编辑已有策略时保留管理员原始模板。
 */
function handleEventChange(eventType) {
  if (policyDialog.form.policyId || !defaultTemplates[eventType]) return
  const [titleTemplate, contentTemplate] = defaultTemplates[eventType]
  policyDialog.form.titleTemplate = titleTemplate
  policyDialog.form.contentTemplate = contentTemplate
}

/**
 * 将服务端白名单变量插入正文末尾，界面只展示业务中文名称。
 * @param {string} variable 服务端允许的模板变量。
 * @returns {void} 保留已有正文并追加精确双花括号标记。
 */
function appendVariable(variable) {
  const separator = policyDialog.form.contentTemplate ? ' ' : ''
  policyDialog.form.contentTemplate += `${separator}{{${variable}}}`
}

/**
 * 校验流程范围必须选择当前授权目录中的真实流程。
 * @param {object} rule Element Plus 校验规则。
 * @param {string} value 当前流程定义 key。
 * @param {Function} callback 校验结果回调。
 * @returns {void} 全局范围或合法目录项通过校验。
 */
function validateProcessSelection(rule, value, callback) {
  const errorMessage = processCatalogValidationError(
    policyDialog.form.scopeType,
    value,
    processOptions.value
  )
  callback(errorMessage ? new Error(errorMessage) : undefined)
}

/**
 * 校验节点范围必须选择当前流程目录中的真实节点。
 * @param {object} rule Element Plus 校验规则。
 * @param {string} value 当前节点定义 key。
 * @param {Function} callback 校验结果回调。
 * @returns {void} 非节点范围或合法节点通过校验。
 */
function validateNodeSelection(rule, value, callback) {
  const errorMessage = nodeCatalogValidationError(
    policyDialog.form.scopeType,
    value,
    currentNodeOptions.value,
    currentNodeLoadError.value
  )
  callback(errorMessage ? new Error(errorMessage) : undefined)
}

/**
 * 校验通知方式非空，并在邮件服务不可用时禁止启用邮件策略。
 * @param {object} rule Element Plus 校验规则。
 * @param {string[]} value 当前通知方式集合。
 * @param {Function} callback 校验结果回调。
 * @returns {void} 停用邮件草稿可保存，启用策略必须具有可用 SMTP。
 */
function validateChannels(rule, value, callback) {
  if (!Array.isArray(value) || value.length === 0) {
    callback(new Error('请选择通知方式'))
    return
  }
  if (value.includes('EMAIL') && policyDialog.form.status === 'ENABLED' && !mailChannelAvailable.value) {
    callback(new Error('邮件服务尚未配置，不能启用邮件通知'))
    return
  }
  callback()
}

/**
 * 校验短信通知必须同时配置供应商模板 ID。
 * @param {object} rule Element Plus 校验规则。
 * @param {string} value 当前短信模板 ID。
 * @param {Function} callback 校验结果回调。
 * @returns {void} 未选择短信或模板非空时通过。
 */
function validateSmsTemplate(rule, value, callback) {
  if (policyDialog.form.channelList.includes('SMS') && !String(value || '').trim()) {
    callback(new Error('请输入短信模板 ID'))
    return
  }
  callback()
}

/**
 * 通知方式或启用状态变化后立即复核邮件服务门禁。
 * @returns {void} 更新 channelList 字段错误提示。
 */
function validatePolicyChannels() {
  policyFormRef.value?.validateField('channelList').catch(() => false)
}

/**
 * 以服务端 revision 保存新增或编辑策略，不发送前端目录显示字段。
 * @returns {Promise<void>} 成功后刷新正式列表，409 时由用户确认是否加载新版本。
 */
async function submitPolicy() {
  if (!canManagePolicy.value) return
  const valid = await policyFormRef.value.validate().catch(() => false)
  if (!valid) return
  policySaving.value = true
  try {
    await saveWorkflowNotificationPolicy({
      policyId: policyDialog.form.policyId,
      scopeType: policyDialog.form.scopeType,
      processDefinitionKey: policyDialog.form.processDefinitionKey || null,
      taskDefinitionKey: policyDialog.form.taskDefinitionKey || null,
      eventType: policyDialog.form.eventType,
      recipientRules: recipientOptions.map(item => item.value).filter(item => policyDialog.form.recipientRuleList.includes(item)).join(','),
      channels: channelOptions.map(item => item.value).filter(item => policyDialog.form.channelList.includes(item)).join(','),
      smsTemplateId: policyDialog.form.channelList.includes('SMS') ? policyDialog.form.smsTemplateId.trim() : null,
      titleTemplate: policyDialog.form.titleTemplate,
      contentTemplate: policyDialog.form.contentTemplate,
      maxAttempts: Number(policyDialog.form.maxAttempts),
      status: policyDialog.form.status,
      expectedRevision: policyDialog.form.policyId ? Number(policyDialog.form.expectedRevision) : null
    })
    policyDialog.visible = false
    proxy.$modal.msgSuccess('通知策略已保存')
    await loadPolicies()
  } catch (error) {
    const subCode = responseSubCode(error)
    if (subCode === 'NOTIFICATION_POLICY_REVISION_CONFLICT') {
      await promptPolicyReloadAfterConflict()
    } else if (subCode === 'SMTP_NOT_CONFIGURED') {
      await handleMailChannelUnavailable()
    } else if (subCode === 'NOTIFICATION_POLICY_DUPLICATE') {
      proxy.$modal.msgWarning(requestErrorMessage(error, '相同作用域和场景的通知策略已存在'))
      await loadPolicies()
    } else {
      proxy.$modal.msgError(requestErrorMessage(error, '通知策略保存失败'))
    }
  } finally {
    policySaving.value = false
  }
}

/**
 * 策略并发冲突后由用户确认是否舍弃草稿并加载正式最新版本。
 * @returns {Promise<void>} 更新场景重开最新策略，新增冲突则回到刷新后的列表。
 */
async function promptPolicyReloadAfterConflict() {
  try {
    await proxy.$modal.confirm('通知策略已被其他管理员修改，是否重新加载最新配置？')
    const policyId = policyDialog.form.policyId
    await loadPolicies()
    if (!policyId) {
      policyDialog.visible = false
      return
    }
    const latest = policies.value.find(item => String(item.policyId) === String(policyId))
    if (!latest) {
      policyDialog.visible = false
      proxy.$modal.msgWarning('该通知策略已不可用，列表已刷新')
      return
    }
    await openPolicy(latest)
  } catch {
    // 用户取消后保留当前草稿；服务端 revision 会继续拒绝任何陈旧覆盖。
  }
}

/**
 * 从统一业务错误或真实 HTTP 错误响应读取受限稳定子码。
 * @param {unknown} error BusinessError 或 Axios 错误。
 * @returns {string} 合法大写机器子码，缺失或非法时返回空字符串。
 */
function responseSubCode(error) {
  const value = typeof error?.subCode === 'string'
    ? error.subCode
    : error?.response?.data?.subCode
  const normalized = typeof value === 'string' ? value.trim() : ''
  return /^[A-Z][A-Z0-9_]{0,63}$/.test(normalized) ? normalized : ''
}

/**
 * 提取后端允许展示的短错误消息，真实 HTTP 错误优先读取安全 AjaxResult.msg。
 * @param {unknown} error BusinessError 或 Axios 错误。
 * @param {string} fallback 后端没有稳定提示时的兜底消息。
 * @returns {string} 最多 180 个字符且不拼接请求体的用户提示。
 */
function requestErrorMessage(error, fallback) {
  const responseMessage = typeof error?.response?.data?.msg === 'string'
    ? error.response.data.msg.trim()
    : ''
  const businessMessage = typeof error?.message === 'string' ? error.message.trim() : ''
  return (responseMessage || businessMessage || fallback).slice(0, 180)
}

/**
 * 处理保存期间 SMTP 配置失效的竞争窗口，并按当前用户权限提供明确下一步。
 * @returns {Promise<void>} 更新邮件可用状态；有权限且确认时打开正式 SMTP 配置弹窗。
 */
async function handleMailChannelUnavailable() {
  mailChannelAvailable.value = false
  validatePolicyChannels()
  if (!canMailManage.value) {
    proxy.$modal.msgWarning('邮件服务尚未配置，请联系具有邮件服务管理权限的管理员')
    return
  }
  try {
    await proxy.$modal.confirm('邮件服务尚未配置，是否立即配置邮件服务？')
    openMailConfig()
  } catch {
    // 用户取消后保留当前策略草稿，邮件通道仍保持不可启用状态。
  }
}

/**
 * 打开 SMTP 配置弹窗，入口和接口调用均受 mailManage 权限约束。
 * @returns {void} 无权限时不改变弹窗状态。
 */
function openMailConfig() {
  if (!canMailManage.value) return
  mailConfigVisible.value = true
}

/**
 * SMTP 保存并回读成功后同步策略邮件可用状态并清理旧校验错误。
 * @param {{configured:boolean,revision:number}} result 邮件配置弹窗回读后的脱敏状态。
 * @returns {void} 只同步布尔可用状态，不接收任何凭据字段。
 */
function handleMailConfigSaved(result) {
  mailChannelAvailable.value = result?.configured === true
  policyFormRef.value?.clearValidate('channelList')
}

/**
 * 经确认补偿当前死信，并以行级状态阻止浏览器重复提交。
 * @param {object} row 当前脱敏 outbox 行。
 * @returns {Promise<void>} 接受补偿后只提示重新入队并刷新真实状态。
 */
async function compensate(row) {
  if (!canCompensate(row) || isCompensating(row.outboxId)) return
  await proxy.$modal.confirm(`确认重新投递通知 ${row.outboxId} 吗？`)
  setCompensating(row.outboxId, true)
  try {
    await compensateWorkflowNotification(row.outboxId)
    proxy.$modal.msgSuccess('死信已重新进入投递队列')
    await loadOutbox()
  } catch (error) {
    if (responseSubCode(error) === 'NOTIFICATION_OUTBOX_STATE_CONFLICT') {
      proxy.$modal.msgWarning('投递记录状态已变化，列表已刷新')
      await loadOutbox()
    } else {
      proxy.$modal.msgError(requestErrorMessage(error, '通知补偿失败'))
    }
  } finally {
    setCompensating(row.outboxId, false)
  }
}

/**
 * 以服务端状态投影判断当前 outbox 是否允许发起补偿。
 * @param {object} row 当前脱敏 outbox 行。
 * @returns {boolean} 服务端明确允许且当前状态仍为死信时返回 true。
 */
function canCompensate(row) {
  return canCompensateNotificationOutbox(canRetryOutbox.value, row)
}

/**
 * 展示服务端关联的通知对象名称，历史缺失用户时回退到稳定用户编号。
 * @param {object} row 当前脱敏 outbox 行。
 * @returns {string} 用户名称或用户编号。
 */
function outboxRecipientLabel(row) {
  const recipientName = typeof row?.recipientName === 'string' ? row.recipientName.trim() : ''
  return recipientName || `用户 #${row?.recipientUserId ?? '-'}`
}

/**
 * 更新一条 outbox 的页面级补偿运行状态。
 * @param {number|string} outboxId outbox 主键。
 * @param {boolean} active 是否正在补偿。
 * @returns {void} 以新 Set 触发 Vue 响应式更新。
 */
function setCompensating(outboxId, active) {
  const next = new Set(compensatingIds.value)
  if (active) next.add(String(outboxId))
  else next.delete(String(outboxId))
  compensatingIds.value = next
}

/**
 * 判断指定 outbox 是否已有补偿请求正在执行。
 * @param {number|string} outboxId outbox 主键。
 * @returns {boolean} 当前页面已锁定该行时返回 true。
 */
function isCompensating(outboxId) {
  return compensatingIds.value.has(String(outboxId))
}

/**
 * 构造策略搜索使用的业务文本，不把浏览器状态作为正式数据源。
 * @param {object} row 服务端策略行。
 * @returns {string} 小写场景、范围、流程、节点、通知对象和方式组合。
 */
function policySearchText(row) {
  return [
    eventLabel(row.eventType), row.eventType, scopeLabel(row.scopeType), policyTargetLabel(row),
    recipientLabels(row.recipientRules).join(' '), csvValues(row.channels).map(channelLabel).join(' ')
  ].join(' ').toLowerCase()
}

/**
 * 生成策略目标的业务名称，失效目录仅作明确告警而不提供手工保存回退。
 * @param {object} row 服务端策略行。
 * @returns {string} 所有流程、流程名称或流程与节点名称。
 */
function policyTargetLabel(row) {
  if (row.scopeType === 'DEFAULT') return '所有流程'
  const process = processOptions.value.find(item => item.processDefinitionKey === row.processDefinitionKey)
  const processName = process?.processName || `已失效流程（${row.processDefinitionKey || '-'}）`
  if (row.scopeType !== 'NODE') return processName
  if (nodeCatalogErrors.value[row.processDefinitionKey]) return `${processName} · 节点目录加载失败`
  const node = (nodeCatalogs.value[row.processDefinitionKey] || []).find(item => item.taskDefinitionKey === row.taskDefinitionKey)
  return `${processName} · ${node?.taskName || `已失效节点（${row.taskDefinitionKey || '-'}）`}`
}

/**
 * 生成流程选择器的名称和版本标签。
 * @param {object} process 流程目录项。
 * @returns {string} 流程业务名称及可选版本。
 */
function processOptionLabel(process) {
  return process.version > 0 ? `${process.processName} · V${process.version}` : process.processName
}

/**
 * 将服务端 CSV 转换为去空白且保持顺序的值数组。
 * @param {unknown} value 服务端固定枚举 CSV。
 * @returns {string[]} 非空枚举数组。
 */
function csvValues(value) {
  return String(value || '').split(',').map(item => item.trim()).filter(Boolean)
}

/**
 * 将通知对象枚举转换为业务中文名称。
 * @param {unknown} value 通知对象 CSV。
 * @returns {string[]} 对应中文名称，未知值保留原值供异常识别。
 */
function recipientLabels(value) {
  return csvValues(value).map(item => recipientOptions.find(option => option.value === item)?.label || item)
}

/**
 * 获取通知场景业务名称。
 * @param {string} value 服务端事件枚举。
 * @returns {string} 中文场景或未知原值。
 */
function eventLabel(value) {
  return eventOptions.find(item => item.value === value)?.label || value || '-'
}

/**
 * 获取作用范围业务名称。
 * @param {string} value 服务端作用范围枚举。
 * @returns {string} 中文作用范围或未知原值。
 */
function scopeLabel(value) {
  return scopeOptions.find(item => item.value === value)?.label || value || '-'
}

/**
 * 获取通知方式业务名称。
 * @param {string} value 服务端通知方式枚举。
 * @returns {string} 中文通知方式或未知原值。
 */
function channelLabel(value) {
  return channelOptions.find(item => item.value === value)?.label || value || '-'
}

/**
 * 获取作用范围对应的 Element Plus 标签类型。
 * @param {string} value 服务端作用范围枚举。
 * @returns {string} 标签视觉类型。
 */
function scopeType(value) {
  return ({ DEFAULT: 'info', PROCESS: 'primary', NODE: 'warning' }[value] || 'info')
}

/**
 * 获取通知方式对应的 Element Plus 标签类型。
 * @param {string} value 服务端通知方式枚举。
 * @returns {string} 标签视觉类型。
 */
function channelTagType(value) {
  return ({ EMAIL: 'primary', SMS: 'warning', INBOX: 'success' }[value] || 'info')
}

/**
 * 获取投递状态业务名称。
 * @param {string} value 服务端 outbox 状态。
 * @returns {string} 中文投递状态或未知原值。
 */
function statusLabel(value) {
  return ({ PENDING: '待投递', RETRYING: '重试中', DELIVERING: '投递中', PROCESSED: '已送达', DEAD_LETTER: '死信', CANCELLED: '已取消' }[value] || value)
}

/**
 * 获取投递状态对应的 Element Plus 标签类型。
 * @param {string} value 服务端 outbox 状态。
 * @returns {string} 标签视觉类型。
 */
function statusType(value) {
  return ({ PROCESSED: 'success', DEAD_LETTER: 'danger', RETRYING: 'warning', DELIVERING: 'primary' }[value] || 'info')
}

watch(
  () => [policyQuery.keyword, policyQuery.scopeType, policyQuery.channel],
  () => { policyQuery.pageNum = 1 }
)

onMounted(async () => {
  await loadActiveTab()
  pageInitialized = true
})
onActivated(() => {
  if (pageInitialized) loadActiveTab()
})
</script>

<style scoped lang="scss">
.notification-admin {
  --notification-accent: #2f7f70;
  --notification-accent-soft: #edf7f4;
  --notification-line: #dfe7e4;
  color: var(--el-text-color-primary);
}
.notification-admin__header { display: flex; min-height: 54px; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 4px; }
.notification-admin__header h2 { margin: 0; font-size: 20px; font-weight: 650; letter-spacing: .01em; }
.notification-admin__actions { display: flex; align-items: center; gap: 8px; }
.notification-admin__tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.notification-admin__tabs :deep(.el-tabs__item) { height: 42px; font-size: 13px; font-weight: 600; }
.notification-admin__tabs :deep(.el-tabs__active-bar) { background-color: var(--notification-accent); }
.notification-admin__tabs :deep(.el-tabs__item.is-active),
.notification-admin__tabs :deep(.el-tabs__item:hover) { color: var(--notification-accent); }
.notification-admin__toolbar { display: flex; align-items: center; gap: 9px; margin-bottom: 12px; }
.notification-admin__toolbar :deep(.el-input) { width: 270px; }
.notification-admin__toolbar :deep(.el-select) { width: 160px; }
.notification-admin__toolbar--outbox :deep(.el-input) { width: 230px; }
.notification-admin__toolbar--outbox :deep(.el-select) { width: 150px; }
.notification-admin__table { border: 1px solid var(--notification-line); border-radius: 5px; }
.notification-admin__table :deep(th.el-table__cell) { height: 46px; background: #eef3f1; color: #5d6c68; font-size: 12px; }
.notification-admin__table :deep(td.el-table__cell) { height: 54px; font-size: 12px; }
.notification-admin__primary-cell { display: grid; min-width: 0; gap: 3px; }
.notification-admin__primary-cell strong { overflow: hidden; color: #243632; font-size: 12px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.notification-admin__primary-cell small { overflow: hidden; color: var(--el-text-color-placeholder); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.notification-admin__tag { margin: 2px 5px 2px 0; }
.notification-admin__pagination { display: flex; justify-content: flex-end; padding-top: 14px; }
.notification-admin__dialog-tip { margin-bottom: 18px; border-left: 3px solid var(--notification-accent); }
.notification-admin__form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.notification-admin__form-grid :deep(.el-select) { width: 100%; }
.notification-admin__node-error { width: 100%; margin-top: 8px; }
.notification-admin__mail-guard { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 10px; margin: -2px 0 16px; }
.notification-admin__mail-guard > span { color: var(--el-color-warning-dark-2); font-size: 12px; }
.notification-admin__advanced { margin-top: 4px; border-top: 1px solid var(--notification-line); }
.notification-admin__advanced :deep(.el-collapse-item__header) { color: #526560; font-size: 13px; font-weight: 650; }
.notification-admin__variables { display: flex; flex-wrap: wrap; gap: 6px; padding-left: 0; }
.notification-admin__variables button { height: 27px; padding: 0 9px; border: 1px solid #cfdfda; border-radius: 4px; background: var(--notification-accent-soft); color: var(--notification-accent); cursor: pointer; font-size: 11px; }
.notification-admin__variables button:hover { border-color: var(--notification-accent); background: #e2f2ed; }

@media (max-width: 900px) {
  .notification-admin__header { align-items: flex-start; flex-direction: column; }
  .notification-admin__actions { width: 100%; justify-content: flex-end; }
  .notification-admin__toolbar { align-items: stretch; flex-direction: column; }
  .notification-admin__toolbar :deep(.el-input),
  .notification-admin__toolbar :deep(.el-select) { width: 100%; }
  .notification-admin__form-grid { grid-template-columns: 1fr; }
  .notification-admin__mail-guard { grid-template-columns: 1fr; }
}
</style>
