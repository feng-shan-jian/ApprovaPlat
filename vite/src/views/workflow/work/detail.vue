<template>
  <div class="app-container workflow-detail" v-loading="loading">
    <div class="workflow-detail__header">
      <div class="workflow-detail__identity">
        <el-button circle text icon="ArrowLeft" aria-label="返回" @click="closePage" />
        <div class="workflow-detail__title">
          <h2>{{ detail.processName || '流程详情' }}</h2>
          <div class="workflow-detail__meta">
            <el-tag :type="statusMeta.type" size="small">{{ statusMeta.label }}</el-tag>
            <span>版本 {{ detail.version || '-' }}</span>
            <span v-if="detail.businessKey">业务主键：{{ detail.businessKey }}</span>
          </div>
        </div>
      </div>
      <div v-if="ready && !loading && hasAnyTaskAction" class="workflow-detail__actions">
        <el-button
          v-if="canAdjustMultiInstance"
          icon="Plus"
          :disabled="remainingMultiInstanceCapacity === 0 || multiInstanceBusy || multiInstanceDialogClosing || actionBusy"
          @click="openMultiInstanceDialog('ADD')"
        >加签</el-button>
        <el-button v-if="canUnclaim" :loading="actionBusy" @click="confirmUnclaim">取消认领</el-button>
        <el-button v-if="canResolve" type="primary" :loading="actionBusy" @click="openActionDialog('resolve')">完成委派</el-button>
        <el-button v-if="canManageTask" @click="openUserAction('delegate')">委派</el-button>
        <el-button v-if="canManageTask" @click="openUserAction('transfer')">转办</el-button>
        <el-button v-if="canReturnTask" type="danger" plain @click="openReturnDialog">退回</el-button>
        <el-button v-if="canMoveTask" type="danger" plain @click="openActionDialog('reject')">驳回</el-button>
        <el-button v-if="canResubmit" type="primary" :loading="actionBusy" @click="confirmResubmit">重新提交</el-button>
        <el-button v-if="canComplete" type="primary" @click="openActionDialog('complete')">通过</el-button>
      </div>
    </div>

    <el-descriptions v-if="ready" class="workflow-detail__summary" :column="summaryColumns" border>
      <el-descriptions-item label="流程实例">{{ detail.processInstanceId }}</el-descriptions-item>
      <el-descriptions-item label="发起人">{{ detail.startUserName || detail.startUserId || '-' }}</el-descriptions-item>
      <el-descriptions-item label="发起时间">{{ formatDate(detail.startTime) }}</el-descriptions-item>
      <el-descriptions-item label="结束时间">{{ formatDate(detail.endTime) }}</el-descriptions-item>
      <el-descriptions-item label="当前任务">{{ detail.currentTask?.taskName || '-' }}</el-descriptions-item>
      <el-descriptions-item label="流程耗时">{{ formatDuration(detail.durationMillis) }}</el-descriptions-item>
    </el-descriptions>

    <section v-if="controlledLoopStates.length" class="workflow-detail__controlled-loops" aria-labelledby="controlled-loop-title">
      <div class="workflow-detail__controlled-loop-heading">
        <div>
          <h3 id="controlled-loop-title">整改循环</h3>
          <span>循环条件、最大轮次和每次办理结果均来自服务端部署快照与正式审计记录。</span>
        </div>
      </div>
      <article v-for="loop in controlledLoopStates" :key="loop.activityId" class="workflow-detail__controlled-loop-card">
        <div class="workflow-detail__controlled-loop-summary">
          <div>
            <strong>{{ loop.activityName || loop.activityId }}</strong>
            <span>字段 {{ loop.decisionVariable }}：等于“{{ loop.repeatValue }}”再次整改，等于“{{ loop.exitValue }}”退出</span>
          </div>
          <div class="workflow-detail__controlled-loop-tags">
            <el-tag v-if="loop.active" type="warning">第 {{ loop.currentIteration }} / {{ loop.maxIterations }} 轮办理中</el-tag>
            <el-tag v-else type="info">已完成 {{ loop.completedIterations }} / {{ loop.maxIterations }} 轮</el-tag>
          </div>
        </div>
        <el-table v-if="loop.rounds?.length" :data="loop.rounds" size="small" max-height="280">
          <el-table-column prop="iteration" label="轮次" width="72" />
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.outcome === 'REPEAT' ? 'warning' : 'success'">
                {{ row.outcome === 'REPEAT' ? '再次整改' : '退出循环' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="decisionValue" label="判断值" min-width="140" show-overflow-tooltip />
          <el-table-column label="办理人" min-width="120">
            <template #default="{ row }">{{ controlledLoopActorName(row) }}</template>
          </el-table-column>
          <el-table-column label="完成时间" min-width="180">
            <template #default="{ row }">{{ formatDate(row.completedAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="尚未完成首轮办理" :image-size="56" />
      </article>
    </section>

    <section v-if="multiInstanceState" class="workflow-detail__multi-instance" aria-labelledby="multi-instance-title">
      <div class="workflow-detail__multi-instance-heading">
        <div>
          <div class="workflow-detail__multi-instance-title-row">
            <h3 id="multi-instance-title">会签成员</h3>
            <el-tag size="small" :type="multiInstanceState.mode === 'ALL' ? 'primary' : 'warning'">
              {{ multiInstanceState.mode === 'ALL' ? '全部通过' : '任一通过' }}
            </el-tag>
            <el-tag size="small" type="info">版本 {{ multiInstanceState.revision }}</el-tag>
          </div>
          <span>活动 {{ activeMultiInstanceCount }} 人，已完成 {{ completedMultiInstanceCount }} 人</span>
        </div>
        <div class="workflow-detail__multi-instance-tools">
          <el-tooltip content="刷新成员状态" placement="top">
            <el-button
              circle
              text
              icon="Refresh"
              aria-label="刷新成员状态"
              :loading="multiInstanceRefreshing"
              @click="refreshMultiInstanceState"
            />
          </el-tooltip>
          <el-button
            v-if="canAdjustMultiInstance"
            type="primary"
            icon="Plus"
            :disabled="remainingMultiInstanceCapacity === 0 || multiInstanceBusy || multiInstanceDialogClosing || actionBusy"
            @click="openMultiInstanceDialog('ADD')"
          >加签</el-button>
        </div>
      </div>
      <el-alert
        v-if="multiInstanceError"
        class="workflow-detail__multi-instance-error"
        type="warning"
        :title="multiInstanceError"
        show-icon
        :closable="false"
      />
      <el-table :data="multiInstanceMembers" row-key="userId" size="small" max-height="360">
        <el-table-column label="成员" min-width="180">
          <template #default="{ row }">
            <div class="workflow-detail__member-name">
              <strong>{{ row.name || `用户 ${row.userId}` }}</strong>
              <span>ID {{ row.userId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.active ? 'primary' : 'success'">
              {{ row.active ? '办理中' : '已完成' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="任务" min-width="180">
          <template #default="{ row }">{{ row.activeTaskId || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="canAdjustMultiInstance" label="操作" width="72" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip v-if="row.removable" content="减签" placement="top">
              <el-button
                circle
                text
                type="danger"
                icon="Minus"
                :aria-label="`移除 ${row.name || row.userId}`"
                :disabled="multiInstanceBusy || multiInstanceDialogClosing"
                @click="openMultiInstanceDialog('REMOVE', row)"
              />
            </el-tooltip>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-tabs v-if="ready" v-model="activeTab" class="workflow-detail__tabs">
      <el-tab-pane label="办理表单" name="taskForm">
        <div v-if="detail.currentTaskForm" class="workflow-detail__section">
          <div class="workflow-detail__section-title">
            <div>
              <h3>{{ detail.currentTaskForm.formName || detail.currentTaskForm.nodeName || '当前任务表单' }}</h3>
              <span>{{ detail.currentTaskForm.nodeName || detail.currentTask?.taskName }}</span>
            </div>
            <el-tag v-if="!canComplete && !canResubmit" type="info">只读</el-tag>
          </div>
          <ProcessFormRenderer
            ref="taskFormRef"
            v-model="taskFormValues"
            :content="detail.currentTaskForm.content"
            :readonly="!canComplete && !canResubmit"
            @error="showComponentError"
          />
        </div>
        <el-empty v-else description="当前没有可展示的任务表单" :image-size="80" />
      </el-tab-pane>

      <el-tab-pane label="流程图" name="diagram" lazy>
        <ProcessViewer
          :xml="detail.bpmnXml"
          :state="detail.flowViewer"
          :file-name="diagramFileName"
          height="560px"
          @error="showComponentError"
        />
      </el-tab-pane>

      <el-tab-pane :label="`历史表单 (${historyForms.length})`" name="historyForms">
        <div v-if="historyForms.length" class="workflow-detail__history-forms">
          <section v-for="(form, index) in historyForms" :key="formKey(form, index)" class="workflow-detail__history-form">
            <div class="workflow-detail__section-title">
              <div>
                <h3>{{ form.formName || form.nodeName || '历史表单' }}</h3>
                <span>{{ form.nodeName || form.nodeKey || '-' }} · {{ formatDate(form.snapshotTime) }}</span>
              </div>
              <el-tag size="small" type="info">只读快照</el-tag>
            </div>
            <ProcessFormRenderer
              :content="form.content"
              :model-value="form.hydratedValues"
              readonly
              @error="showComponentError"
            />
          </section>
        </div>
        <el-empty v-else description="暂无历史表单" :image-size="80" />
      </el-tab-pane>

      <el-tab-pane :label="`流转记录 (${timeline.length})`" name="timeline">
        <el-timeline v-if="timeline.length" class="workflow-detail__timeline">
          <el-timeline-item
            v-for="(node, index) in timeline"
            :key="node.taskId || `${node.activityId}-${index}`"
            :timestamp="timelineTimestamp(node)"
            :type="timelineType(node)"
            :hollow="!node.endTime"
            placement="top"
          >
            <div class="workflow-detail__timeline-node">
              <div class="workflow-detail__timeline-heading">
                <strong>{{ node.activityName || activityTypeName(node.activityType) }}</strong>
                <el-tag v-if="!node.endTime" size="small">处理中</el-tag>
              </div>
              <div class="workflow-detail__timeline-meta">
                <span v-if="node.assigneeName || node.assigneeId">办理人：{{ node.assigneeName || node.assigneeId }}</span>
                <span v-if="node.completedByName && node.completedByName !== node.assigneeName">完成人：{{ node.completedByName }}</span>
                <span v-if="node.durationMillis != null">耗时：{{ formatDuration(node.durationMillis) }}</span>
                <span v-if="candidateText(node)">候选：{{ candidateText(node) }}</span>
              </div>
              <div v-if="node.comments?.length" class="workflow-detail__comments">
                <div v-for="comment in node.comments" :key="comment.commentId" class="workflow-detail__comment">
                  <el-tag size="small" :type="commentType(comment.type)">{{ comment.typeName || '意见' }}</el-tag>
                  <span v-if="comment.opinion" class="workflow-detail__comment-message">{{ comment.opinion }}</span>
                  <span class="workflow-detail__comment-time">
                    {{ comment.userId ? `用户 ${comment.userId} · ` : '' }}{{ formatDate(comment.time) }}
                  </span>
                </div>
              </div>
              <div v-if="node.deleteReason" class="workflow-detail__delete-reason">流转说明：{{ node.deleteReason }}</div>
            </div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无流转记录" :image-size="80" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="actionDialog.visible"
      :title="actionDialogTitle"
      width="min(560px, calc(100vw - 32px))"
      append-to-body
      :show-close="!actionBusy"
      :close-on-click-modal="!actionBusy"
      :close-on-press-escape="!actionBusy"
      @closed="handleActionDialogClosed"
    >
      <el-form ref="actionFormRef" :model="actionDialog" label-width="96px">
        <el-form-item v-if="isUserAction" label="目标用户" required>
          <el-select
            v-model="actionDialog.userId"
            filterable
            remote
            clearable
            :remote-method="searchApprovalUsers"
            :loading="approvalUserLoading"
            placeholder="输入姓名或账号检索"
            style="width: 100%"
          >
            <el-option v-for="user in approvalUserOptions" :key="user.value" :label="user.label" :value="user.value" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="actionDialog.type === 'complete' && nextUserSelectionEnabled"
          :label="nextUserSelectionLabel"
          :required="nextUserSelectionRequired"
        >
          <el-select
            v-model="actionDialog.nextUserIds"
            class="workflow-detail__user-select"
            multiple
            filterable
            remote
            clearable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="3"
            :multiple-limit="100"
            :reserve-keyword="false"
            :remote-method="searchApprovalUsers"
            :loading="approvalUserLoading"
            :placeholder="nextUserSelectionPlaceholder"
          >
            <el-option v-for="user in approvalUserOptions" :key="user.value" :label="user.label" :value="user.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="supportsCopyAction" label="抄送人">
          <el-select
            v-model="actionDialog.copyUserIds"
            class="workflow-detail__user-select"
            multiple
            filterable
            remote
            clearable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="3"
            :multiple-limit="100"
            :reserve-keyword="false"
            :remote-method="searchCopyUsers"
            :loading="copyUserLoading"
            placeholder="选择抄送人（可选）"
          >
            <el-option v-for="user in copyUserOptions" :key="user.value" :label="user.label" :value="user.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="办理意见" required>
          <el-input
            v-model="actionDialog.comment"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :placeholder="actionCommentPlaceholder"
          />
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
        <el-button :disabled="actionBusy" @click="actionDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="actionBusy" @click="submitAction">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="multiInstanceDialog.visible"
      :title="multiInstanceDialog.action === 'ADD' ? '增加会签成员' : '移除会签成员'"
      width="min(560px, calc(100vw - 32px))"
      append-to-body
      :show-close="!multiInstanceBusy"
      :close-on-click-modal="!multiInstanceBusy"
      :close-on-press-escape="!multiInstanceBusy"
      @close="handleMultiInstanceDialogClose"
      @closed="handleMultiInstanceDialogClosed"
    >
      <el-form :model="multiInstanceDialog" label-width="96px">
        <el-form-item v-if="multiInstanceDialog.action === 'ADD'" label="新增成员" required>
          <el-select
            v-model="multiInstanceDialog.userIds"
            class="workflow-detail__user-select"
            multiple
            filterable
            remote
            clearable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="3"
            :multiple-limit="multiInstanceSelectionLimit"
            :reserve-keyword="false"
            :remote-method="searchMultiInstanceUsers"
            :loading="multiInstanceUserLoading"
            placeholder="输入姓名或账号检索"
          >
            <el-option
              v-for="user in multiInstanceUserOptions"
              :key="user.value"
              :label="user.label"
              :value="user.value"
              :disabled="multiInstanceMemberIds.has(user.value)"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="移除成员" required>
          <div class="workflow-detail__remove-target">
            <strong>{{ multiInstanceDialog.targetName || '-' }}</strong>
            <span>任务 {{ multiInstanceDialog.targetTaskId || '-' }}</span>
          </div>
        </el-form-item>
        <el-form-item label="调整意见" required>
          <el-input
            v-model="multiInstanceDialog.comment"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :placeholder="multiInstanceDialog.action === 'ADD' ? '请输入加签原因' : '请输入减签原因'"
          />
        </el-form-item>
        <el-alert
          v-if="multiInstanceDialog.error"
          type="warning"
          :title="multiInstanceDialog.error"
          show-icon
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button :disabled="multiInstanceBusy" @click="multiInstanceDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="multiInstanceBusy" @click="submitMultiInstanceAdjustment">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowProcessDetail">
import { getProcessDetail } from '@/api/workflow/process'
import {
  adjustMultiInstance,
  completeTask,
  delegateTask,
  getMultiInstanceState,
  resubmitApplication,
  rejectTask,
  resolveTask,
  returnTask,
  transferTask,
  unclaimTask
} from '@/api/workflow/task'
import { getWorkflowAttachment } from '@/api/workflow/attachment'
import { listApprovalUserOptions, listIdentityOptions } from '@/api/workflow/identity'
import ProcessFormRenderer from '@/components/workflow/ProcessFormRenderer.vue'
import ProcessViewer from '@/components/workflow/ProcessViewer.vue'
import { flattenFormFields, normalizeFormTemplate } from '@/components/workflow/form/formTemplate'
import useUserStore from '@/store/modules/user'
import { useWindowSize } from '@vueuse/core'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { proxy } = getCurrentInstance()
const { width: viewportWidth } = useWindowSize()
const loading = ref(false)
const ready = ref(false)
const actionBusy = ref(false)
const activeTab = ref('taskForm')
const detail = reactive({})
const taskFormValues = ref({})
const historyForms = ref([])
const taskFormRef = ref(null)
const actionFormRef = ref(null)
// 审批办理对象与普通抄送对象使用独立目录状态，禁止通用启用用户混入任务分配请求。
const approvalUserOptions = ref([])
const approvalUserLoading = ref(false)
const copyUserOptions = ref([])
const copyUserLoading = ref(false)
// 动态多实例动作锁、状态刷新锁和稳定错误分别约束重复提交、快照覆盖及页面失败回显。
const multiInstanceBusy = ref(false)
const multiInstanceRefreshing = ref(false)
const multiInstanceError = ref('')
// 关闭期锁覆盖 Element Plus leave 至 closed 的完整区间，禁止旧弹窗回调清空下一轮真实业务草稿。
const multiInstanceDialogClosing = ref(false)
// 动态加签身份检索使用独立加载态和候选列表，不与普通办理人的远程检索状态串扰。
const multiInstanceUserLoading = ref(false)
const multiInstanceUserOptions = ref([])
let approvalUserSearchSequence = 0
let copyUserSearchSequence = 0
let detailLoadSequence = 0
// 详情页由页签缓存；首次请求完成后，重新进入同一路由也必须重新读取运行时轨迹。
let detailPageInitialized = false
// 加签身份检索与成员快照各自使用递增序号，禁止旧结果覆盖当前任务 revision。
let multiInstanceUserSearchSequence = 0
let multiInstanceRefreshSequence = 0
// 审批对象和抄送对象分别缓存服务端已验证选项，远程结果切换后仍能稳定显示已选标签。
const approvalUserOptionCache = new Map()
const verifiedApprovalUserIds = new Set()
const copyUserOptionCache = new Map()
const verifiedCopyUserIds = new Set()
// 动态加签使用独立的身份目录缓存，仅负责远程检索后的稳定标签展示。
const multiInstanceUserOptionCache = new Map()
// 下一办理人策略只接受服务端冻结枚举；旧接口缺少显式策略时对非必填能力失败关闭。
const NEXT_USER_ASSIGNMENT_POLICIES = Object.freeze([
  'DISABLED',
  'OPTIONAL',
  'REQUIRED_ALL',
  'REQUIRED_ANY'
])
// 历史意见上限与详情 API 的单条 UTF-8 正文门禁一致，兼容已持久化的旧流程长意见。
const MAX_TIMELINE_OPINION_BYTES = 8 * 1024
// 只有该机器子码表示 expectedRevision 失配；其他 409 可能是任务、权限或执行树变化。
const MULTI_INSTANCE_REVISION_CONFLICT_SUBCODE = 'WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT'

// 办理弹窗只保存服务端允许的动作参数，提交时再按动作类型构造精确请求体。
const actionDialog = reactive({
  visible: false,
  type: '',
  comment: '',
  userId: '',
  copyUserIds: [],
  nextUserIds: [],
  // expectedRevision 是完成弹窗打开时冻结的动态多实例版本；普通或静态任务固定为空。
  expectedRevision: null,
  // boundProcessInstanceId、boundTaskId 和 boundDetailSequence 共同标识弹窗打开时的授权任务快照。
  boundProcessInstanceId: '',
  boundTaskId: '',
  boundDetailSequence: -1,
  error: ''
})

// 调整弹窗冻结打开时的动作和目标；revision 始终在提交瞬间从服务端详情快照读取。
const multiInstanceDialog = reactive({
  visible: false,
  action: 'ADD',
  comment: '',
  userIds: [],
  targetTaskId: '',
  targetName: '',
  error: ''
})

// 将服务端稳定流程状态映射为用户可见标签和 Element Plus 语义色。
const statusMeta = computed(() => ({
  running: { label: '进行中', type: 'primary' },
  returned: { label: '待修改', type: 'warning' },
  suspended: { label: '已挂起', type: 'warning' },
  completed: { label: '已完成', type: 'success' },
  rejected: { label: '已驳回', type: 'danger' },
  terminated: { label: '已终止', type: 'danger' },
  canceled: { label: '已取消', type: 'info' }
}[detail.processStatus] || { label: detail.processStatus || '未知', type: 'info' }))
const summaryColumns = computed(() => viewportWidth.value < 768 ? 1 : 3)
const timeline = computed(() => Array.isArray(detail.historyProcNodeList) ? detail.historyProcNodeList : [])
// 受控循环状态完全采用详情 API 的部署快照和逐轮审计，页面不根据历史任务数量自行推演。
const controlledLoopStates = computed(() => Array.isArray(detail.controlledLoopStates)
  ? detail.controlledLoopStates
  : [])
// 循环审计以 taskId 对齐正式历史任务显示名称；历史投影缺失时才回退到稳定用户主键。
const controlledLoopActorNames = computed(() => new Map(timeline.value
  .filter(node => node?.taskId)
  .map(node => [String(node.taskId), node.completedByName || node.assigneeName || ''])))
const diagramFileName = computed(() => `workflow_${safeFileName(detail.processKey || processInstanceId() || 'process')}`)
const currentUserId = computed(() => String(userStore.id || ''))
// 动态多实例状态完全来自详情 API 的正式快照；revision、成员状态和计数不在浏览器自行推演。
const multiInstanceState = computed(() => detail.multiInstanceState || null)
const multiInstanceMembers = computed(() => Array.isArray(multiInstanceState.value?.members)
  ? multiInstanceState.value.members
  : [])
const activeMultiInstanceCount = computed(() => multiInstanceMembers.value.filter(member => member.active).length)
const completedMultiInstanceCount = computed(() => multiInstanceMembers.value.length - activeMultiInstanceCount.value)
// 成员 ID 集合仅用于提前提示重复加签；后端仍会在同一事务内重新校验身份、revision 和活动 execution。
const multiInstanceMemberIds = computed(() => new Set(multiInstanceMembers.value.map(member => String(member.userId))))
// 页面按正式 100 人上限约束选择器容量，不能代替后端并发写入后的最终容量校验。
const remainingMultiInstanceCapacity = computed(() => Math.max(0, 100 - multiInstanceMembers.value.length))
const multiInstanceSelectionLimit = computed(() => Math.max(1, remainingMultiInstanceCapacity.value))
// 下一办理人显示、必填和模式完全采用详情 API 的部署模型策略，页面不自行解析 BPMN 或试错探测。
const nextUserAssignmentPolicy = computed(() => String(detail.nextUserAssignmentPolicy || 'DISABLED'))
const nextUserSelectionEnabled = computed(() => nextUserAssignmentPolicy.value !== 'DISABLED')
const nextUserSelectionRequired = computed(() => nextUserAssignmentPolicy.value.startsWith('REQUIRED_'))
const nextUserSelectionMode = computed(() => ({
  REQUIRED_ALL: 'ALL',
  REQUIRED_ANY: 'ANY'
}[nextUserAssignmentPolicy.value] || null))
const nextUserSelectionLabel = computed(() => ({
  ALL: '会签办理人',
  ANY: '或签办理人'
}[nextUserSelectionMode.value] || '下一办理人'))
const nextUserSelectionPlaceholder = computed(() => nextUserSelectionRequired.value
  ? `请选择${nextUserSelectionLabel.value}`
  : '选择下一办理人（可选）')
// 任务操作必须同时满足活动任务存在、当前登录用户就是 assignee，不能仅凭页面路由推断所有权。
const currentTaskOwned = computed(() => Boolean(
  detail.currentTask?.active
  && currentUserId.value
  && String(detail.currentTask.assignee || '') === currentUserId.value
))
// Flowable 委派状态与 owner 共同组成委派上下文；PENDING 只能办理委派办结，不能执行普通完成或节点移动。
const delegationState = computed(() => String(detail.currentTask?.delegationState || '').trim().toUpperCase())
const hasDelegationContext = computed(() => Boolean(delegationState.value || detail.currentTask?.owner))
const pendingDelegation = computed(() => delegationState.value === 'PENDING')
// 普通办理能力同时受对象所有权、流程运行态和后端同名权限约束。
const canOperateTask = computed(() => currentTaskOwned.value
  && detail.processStatus === 'running'
  && hasPermission('workflow:process:approval'))
// 完成、节点移动和管理动作根据委派上下文进一步收窄，页面门禁与后端状态校验保持一致。
const canComplete = computed(() => canOperateTask.value && !pendingDelegation.value)
// 退回修改任务只对原发起人开放，并使用流程发起权限而不是审批权限。
const canResubmit = computed(() => currentTaskOwned.value
  && detail.processStatus === 'returned'
  && String(detail.startUserId || '') === currentUserId.value
  && hasPermission('workflow:process:start'))
const canMoveTask = computed(() => canOperateTask.value && !hasDelegationContext.value)
// 退回能力由后端复用正式动作准备链投影，前端不再把缺少动态多实例状态误判为普通安全任务。
const canReturnTask = computed(() => canMoveTask.value && detail.returnAllowed === true)
// 受控动态多实例的 assignee 必须与服务端成员快照一致，禁止页面开放委派或转办改写办理人。
const canManageTask = computed(() => canMoveTask.value && !multiInstanceState.value)
// 动态成员调整沿用普通节点移动的办理人、运行态、权限和委派门禁，并要求详情明确返回受控状态。
const canAdjustMultiInstance = computed(() => canMoveTask.value && Boolean(multiInstanceState.value))
// 取消认领只允许当前用户亲自认领且没有委派关系的运行中任务。
const canUnclaim = computed(() => currentTaskOwned.value
  && detail.processStatus === 'running'
  && !hasDelegationContext.value
  && String(detail.currentTask?.claimedBy || '') === currentUserId.value
  && Boolean(detail.currentTask?.claimTime)
  && hasPermission('workflow:process:claim'))
// 委派办结只对当前受托人持有的 PENDING 任务开放。
const canResolve = computed(() => currentTaskOwned.value
  && detail.processStatus === 'running'
  && pendingDelegation.value
  && hasPermission('workflow:process:approval'))
const hasAnyTaskAction = computed(() => canComplete.value || canResubmit.value || canMoveTask.value || canManageTask.value || canUnclaim.value || canResolve.value)
// 用户选择与抄送字段按动作白名单显示，未列入的动作不会向后端发送额外身份参数。
const isUserAction = computed(() => ['delegate', 'transfer'].includes(actionDialog.type))
const supportsCopyAction = computed(() => ['complete', 'reject', 'return', 'delegate', 'resolve', 'transfer'].includes(actionDialog.type))
const actionDialogTitle = computed(() => ({
  complete: '通过任务',
  reject: '驳回任务',
  return: '退回任务',
  delegate: '委派任务',
  resolve: '完成委派',
  transfer: '转办任务'
}[actionDialog.type] || '办理任务'))
const actionCommentPlaceholder = computed(() => ({
  complete: '请输入审批意见',
  reject: '请输入驳回原因',
  return: '请输入退回原因',
  delegate: '请输入委派意见',
  resolve: '请输入委派事项的真实办理意见',
  transfer: '请输入转办意见'
}[actionDialog.type] || '请输入办理意见'))

/**
 * 从兼容的新旧隐藏路由参数中读取流程实例主键。
 * @returns {string} 去除首尾空白后的流程实例主键。
 */
function processInstanceId() {
  return String(route.params.instanceId || route.params.processInstanceId || route.params.procInsId || route.params.id
    || route.query.procInsId || route.query.processInstanceId || '').trim()
}

/**
 * 从路由中读取可选任务主键，后端会再次核验任务与实例关系。
 * @returns {string} 去除首尾空白后的任务主键，未传入时为空字符串。
 */
function routeTaskId() {
  return String(route.query.taskId || route.params.taskId || '').trim()
}

/**
 * 冻结当前授权详情对应的流程、任务和加载序号，供异步动作绑定业务对象。
 * @returns {{processInstanceId: string, taskId: string, detailSequence: number}|null} 当前任务上下文；详情关系不完整时返回 null。
 */
function freezeCurrentTaskContext() {
  const currentProcessInstanceId = String(detail.processInstanceId || '').trim()
  const currentTaskId = String(detail.currentTask?.taskId || '').trim()
  if (!currentProcessInstanceId || currentProcessInstanceId.length > 64
    || !currentTaskId || currentTaskId.length > 64) return null
  return Object.freeze({
    processInstanceId: currentProcessInstanceId,
    taskId: currentTaskId,
    detailSequence: detailLoadSequence
  })
}

/**
 * 核对冻结上下文仍对应当前路由和最新授权详情，淘汰跨路由或刷新后晚到的异步结果。
 * @param {{processInstanceId: string, taskId: string, detailSequence: number}|null} context 动作开始时冻结的任务上下文。
 * @returns {boolean} 路由、详情、活动任务和加载序号仍完全一致时返回 true。
 */
function isCurrentTaskContext(context) {
  if (!context || context.detailSequence !== detailLoadSequence || !ready.value || loading.value) return false
  const currentRouteTaskId = routeTaskId()
  return processInstanceId() === context.processInstanceId
    && String(detail.processInstanceId || '').trim() === context.processInstanceId
    && String(detail.currentTask?.taskId || '').trim() === context.taskId
    && (!currentRouteTaskId || currentRouteTaskId === context.taskId)
}

/**
 * 将普通动作弹窗绑定到已冻结任务，提交时不得改读可变的当前详情主键。
 * @param {{processInstanceId: string, taskId: string, detailSequence: number}} context 已通过当前详情校验的任务上下文。
 * @returns {void} 无返回值。
 */
function bindActionDialogTaskContext(context) {
  actionDialog.boundProcessInstanceId = context.processInstanceId
  actionDialog.boundTaskId = context.taskId
  actionDialog.boundDetailSequence = context.detailSequence
}

/**
 * 读取普通动作弹窗冻结的任务上下文，并同时复核弹窗未被另一轮打开覆盖。
 * @returns {{processInstanceId: string, taskId: string, detailSequence: number}|null} 完整弹窗上下文；字段缺失或已过期时返回 null。
 */
function currentActionDialogTaskContext() {
  const context = {
    processInstanceId: String(actionDialog.boundProcessInstanceId || '').trim(),
    taskId: String(actionDialog.boundTaskId || '').trim(),
    detailSequence: actionDialog.boundDetailSequence
  }
  if (!context.processInstanceId || !context.taskId || !Number.isInteger(context.detailSequence)) return null
  return isCurrentTaskContext(context) ? context : null
}

/**
 * 规范服务端下一办理人策略，并兼容旧版必填 ALL/ANY 字段。
 * @param {object} payload 流程详情 API 返回的原始聚合对象。
 * @returns {'DISABLED'|'OPTIONAL'|'REQUIRED_ALL'|'REQUIRED_ANY'} 可直接驱动字段显示和校验的冻结策略。
 */
function normalizeNextUserAssignmentPolicy(payload) {
  const legacyMode = payload.nextUserSelectionMode == null
    ? null
    : String(payload.nextUserSelectionMode).toUpperCase()
  const legacyRequired = payload.nextUserSelectionRequired === true
  if (legacyRequired !== ['ALL', 'ANY'].includes(legacyMode)) {
    throw new Error('动态下一办理人兼容能力不完整')
  }

  const rawPolicy = payload.nextUserAssignmentPolicy == null
    ? ''
    : String(payload.nextUserAssignmentPolicy).trim().toUpperCase()
  if (!rawPolicy) {
    // 旧后端只能证明动态多实例后继为必填；无法证明普通后继可选时必须隐藏字段，避免错误承诺能力。
    return legacyRequired ? `REQUIRED_${legacyMode}` : 'DISABLED'
  }
  if (!NEXT_USER_ASSIGNMENT_POLICIES.includes(rawPolicy)) {
    throw new Error('下一办理人策略不合法')
  }

  const expectedLegacyRequired = rawPolicy.startsWith('REQUIRED_')
  const expectedLegacyMode = expectedLegacyRequired ? rawPolicy.slice('REQUIRED_'.length) : null
  if (legacyRequired !== expectedLegacyRequired || legacyMode !== expectedLegacyMode) {
    throw new Error('下一办理人策略与兼容字段不一致')
  }
  return rawPolicy
}

/**
 * 校验详情路由主键的必填和长度边界，避免向后端发送明显非法请求。
 * @returns {boolean} 参数满足接口边界时返回 true。
 */
function validateRouteParams() {
  const instanceId = processInstanceId()
  const taskId = routeTaskId()
  if (!instanceId || instanceId.length > 64) {
    proxy.$modal.msgError('流程实例主键不合法')
    return false
  }
  if (taskId.length > 64) {
    proxy.$modal.msgError('任务主键不合法')
    return false
  }
  return true
}

/**
 * 清空当前详情及其临时动作状态；并发刷新可暂存动态调整草稿，但不会保留旧权限结论。
 * @param {boolean} preserveMultiInstanceDraft 是否暂存动态调整输入等待新详情重新校验。
 * @returns {void} 无返回值。
 */
function clearDetailState(preserveMultiInstanceDraft = false) {
  Object.keys(detail).forEach(key => delete detail[key])
  taskFormValues.value = {}
  historyForms.value = []
  activeTab.value = 'taskForm'
  actionDialog.visible = false
  resetActionDialog()
  if (!preserveMultiInstanceDraft) {
    // 详情重载只触发关闭；动作、目标和目录缓存必须等 closed 后再清理，避免关闭动画中改写弹窗身份。
    multiInstanceDialog.visible = false
    multiInstanceError.value = ''
  }
}

/**
 * 加载后端授权聚合详情，并在开放页面前完成全部附件安全元数据水合。
 * @param {boolean} preserveMultiInstanceDraft 409 后是否保留动态调整输入并按新状态复核。
 * @returns {Promise<void>} 详情及附件元数据均加载成功后将页面置为可用状态。
 */
async function loadDetail(preserveMultiInstanceDraft = false) {
  // 序号用于淘汰路由快速切换时晚到的旧响应，防止旧权限快照重新写回页面。
  const requestSequence = ++detailLoadSequence
  if (!validateRouteParams()) {
    clearDetailState()
    ready.value = false
    loading.value = false
    return
  }
  const expectedProcessInstanceId = processInstanceId()
  loading.value = true
  ready.value = false
  clearDetailState(preserveMultiInstanceDraft)
  try {
    const response = await getProcessDetail(expectedProcessInstanceId, routeTaskId() || undefined)
    if (requestSequence !== detailLoadSequence || processInstanceId() !== expectedProcessInstanceId) return
    const payload = response.data || {}
    if (String(payload.processInstanceId || '').trim() !== expectedProcessInstanceId) {
      throw new Error('流程详情实例关系不一致')
    }
    const nextUserPolicy = normalizeNextUserAssignmentPolicy(payload)
    payload.nextUserAssignmentPolicy = nextUserPolicy
    payload.nextUserSelectionRequired = nextUserPolicy.startsWith('REQUIRED_')
    payload.nextUserSelectionMode = payload.nextUserSelectionRequired
      ? nextUserPolicy.slice('REQUIRED_'.length)
      : null
    // 时间线进入页面状态前即移除原始 message/audit，只保留后端明确投影的用户可见 opinion。
    payload.historyProcNodeList = normalizeTimelineComments(payload.historyProcNodeList)
    payload.multiInstanceState = payload.multiInstanceState == null
      ? null
      : normalizeMultiInstanceState(payload.multiInstanceState)
    // 旧响应或非严格布尔值一律失败关闭，避免页面开放后端必定拒绝的复杂执行树动作。
    payload.returnAllowed = payload.returnAllowed === true
    const attachmentCache = new Map()
    const currentValues = payload.currentTaskForm
      ? await hydrateFormValues(payload.currentTaskForm, attachmentCache, expectedProcessInstanceId)
      : {}
    const historySnapshots = (payload.processFormList || []).filter(form => form.taskId !== payload.currentTask?.taskId)
    const hydratedHistory = await Promise.all(historySnapshots.map(async form => ({
      ...form,
      hydratedValues: await hydrateFormValues(form, attachmentCache, expectedProcessInstanceId)
    })))
    if (requestSequence !== detailLoadSequence || processInstanceId() !== expectedProcessInstanceId) return
    Object.assign(detail, payload)
    taskFormValues.value = currentValues
    historyForms.value = hydratedHistory
    activeTab.value = payload.currentTaskForm ? 'taskForm' : 'diagram'
    ready.value = true
    if (preserveMultiInstanceDraft) reconcileMultiInstanceDraft()
  } catch (error) {
    if (requestSequence === detailLoadSequence) {
      clearDetailState()
      showComponentError(error, '流程详情加载失败')
    }
  } finally {
    if (requestSequence === detailLoadSequence) loading.value = false
  }
}

/**
 * 按部署表单 schema 识别上传字段，并把 UUID 数组替换为授权接口返回的安全元数据。
 * @param {object} form 后端返回的不可变表单快照及白名单字段值。
 * @param {Map<string, Promise<object>>} attachmentCache 本次详情内附件元数据请求缓存。
 * @param {string} expectedProcessInstanceId 当前路由和详情共同核验后的流程实例主键。
 * @returns {Promise<object>} 可交给表单渲染器回显的深复制字段值。
 */
async function hydrateFormValues(form, attachmentCache, expectedProcessInstanceId) {
  const values = cloneJson(form?.values || {})
  const template = normalizeFormTemplate(form?.content)
  const uploadFields = flattenFormFields(template.fields).filter(field => field.tag === 'el-upload')
  for (const field of uploadFields) {
    const sourceItems = Array.isArray(values[field.variable]) ? values[field.variable] : []
    values[field.variable] = await Promise.all(sourceItems.map(item => hydrateAttachment(
      item, attachmentCache, field.variable, expectedProcessInstanceId
    )))
  }
  return values
}

/**
 * 通过真实附件元数据接口水合单个 UUID，禁止拼接静态地址或暴露存储路径。
 * @param {string|object} item 表单值中的附件 UUID 或已水合安全元数据。
 * @param {Map<string, Promise<object>>} attachmentCache 本次详情内附件元数据请求缓存。
 * @param {string} fieldName 部署表单中的附件字段变量名。
 * @param {string} expectedProcessInstanceId 当前详情可信的流程实例主键。
 * @returns {Promise<object>} 后端对象授权后返回的附件安全元数据。
 */
async function hydrateAttachment(item, attachmentCache, fieldName, expectedProcessInstanceId) {
  const attachmentId = typeof item === 'string' ? item.trim() : String(item?.attachmentId || '').trim()
  if (!validAttachmentId(attachmentId)) throw new Error('流程附件标识不合法')
  if (!attachmentCache.has(attachmentId)) {
    attachmentCache.set(attachmentId, getWorkflowAttachment(attachmentId).then(response => {
      const metadata = response.data
      if (!metadata || metadata.attachmentId !== attachmentId) {
        throw new Error('流程附件元数据关系不一致')
      }
      return metadata
    }))
  }
  const metadata = await attachmentCache.get(attachmentId)
  const metadataProcessInstanceId = String(metadata.processInstanceId || '').trim()
  if (!metadataProcessInstanceId || metadataProcessInstanceId !== expectedProcessInstanceId) {
    throw new Error('流程附件与流程实例关系不一致')
  }
  if (metadata.fieldName !== fieldName || metadata.status !== 'BOUND') {
    throw new Error('流程附件与表单字段关系不一致')
  }
  return metadata
}

/**
 * 校验附件主键必须为标准 UUID，避免对明显异常标识发起元数据请求。
 * @param {unknown} value 待校验的附件主键。
 * @returns {boolean} 值为标准 UUID 字符串时返回 true。
 */
function validAttachmentId(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
}

/**
 * 打开通过、驳回或委派办结对话框，并按字段用途加载审批资格或通用抄送目录。
 * @param {'complete'|'reject'|'resolve'} type 任务动作类型。
 * @returns {Promise<void>} 门禁通过时显示动作对话框并完成首批用户检索。
 */
async function openActionDialog(type) {
  if (!assertActionAllowed(type)) return
  const taskContext = freezeCurrentTaskContext()
  if (!isCurrentTaskContext(taskContext)) return denyAction('当前任务状态已变化，请刷新后重试')
  actionDialog.type = type
  bindActionDialogTaskContext(taskContext)
  // 动态多实例完成必须以用户开始填写时看到的成员版本参与 CAS，后续页面刷新不能静默改写它。
  actionDialog.expectedRevision = type === 'complete' && multiInstanceState.value
    ? multiInstanceState.value.revision
    : null
  actionDialog.error = ''
  actionDialog.visible = true
  await loadActionIdentityOptions(type)
}

/**
 * 打开直接退回发起人的确认对话框，目标由后端根据流程发起人和真实首审批历史确定。
 * @returns {Promise<void>} 当前任务上下文有效时显示退回对话框。
 */
async function openReturnDialog() {
  if (!assertActionAllowed('return')) return
  const returnContext = freezeCurrentTaskContext()
  if (!isCurrentTaskContext(returnContext)) return denyAction('当前任务状态已变化，请刷新后重试')
  actionDialog.type = 'return'
  bindActionDialogTaskContext(returnContext)
  actionDialog.visible = true
  await loadActionIdentityOptions('return')
}

/**
 * 校验并覆盖保存当前原申请表单，不要求申请人填写任何审批意见。
 * @returns {Promise<void>} 用户确认且后端同事务恢复首审批配置后返回来源列表。
 */
async function confirmResubmit() {
  if (!assertActionAllowed('resubmit')) return
  const taskContext = freezeCurrentTaskContext()
  if (!isCurrentTaskContext(taskContext)) return denyAction('当前任务状态已变化，请刷新后重试')
  if (!detail.currentTaskForm || !taskFormRef.value) return denyAction('原申请表单尚未就绪')

  actionBusy.value = true
  try {
    const valid = await taskFormRef.value.validate().catch(error => {
      showComponentError(error)
      return false
    })
    if (!valid) return
    const variables = taskFormRef.value.getValues()
    await proxy.$modal.confirm('确认使用当前表单内容重新提交吗？')
    // 表单校验和用户确认均为异步步骤，写入前必须再次确认仍是同一退回任务。
    if (!isCurrentTaskContext(taskContext) || !canResubmit.value) {
      return denyAction('当前任务已切换，本次修改未提交')
    }
    await resubmitApplication({ taskId: taskContext.taskId, variables })
    proxy.$modal.msgSuccess('重新提交成功')
    await closePage()
  } finally {
    actionBusy.value = false
  }
}

/**
 * 打开委派或转办对话框并加载首批审批资格用户和通用抄送用户。
 * @param {'delegate'|'transfer'} type 用户任务动作类型。
 * @returns {Promise<void>} 门禁通过后展示远程用户选择器。
 */
async function openUserAction(type) {
  if (!assertActionAllowed(type)) return
  const taskContext = freezeCurrentTaskContext()
  if (!isCurrentTaskContext(taskContext)) return denyAction('当前任务状态已变化，请刷新后重试')
  actionDialog.type = type
  bindActionDialogTaskContext(taskContext)
  actionDialog.visible = true
  await loadActionIdentityOptions(type)
}

/**
 * 按动作字段用途并行加载审批办理对象和普通抄送对象，避免两类资格互相污染。
 * @param {'complete'|'reject'|'return'|'delegate'|'resolve'|'transfer'} type 当前动作类型。
 * @returns {Promise<void>} 当前动作需要的真实身份目录均完成后结束。
 */
async function loadActionIdentityOptions(type) {
  const requests = []
  if ((type === 'complete' && nextUserSelectionEnabled.value) || ['delegate', 'transfer'].includes(type)) {
    requests.push(searchApprovalUsers(''))
  }
  if (['complete', 'reject', 'return', 'delegate', 'resolve', 'transfer'].includes(type)) {
    requests.push(searchCopyUsers(''))
  }
  await Promise.all(requests)
}

/**
 * 远程检索后端 RBAC 确认具备审批办理资格的启用用户。
 * @param {string} keyword 用户输入的姓名或账号关键词。
 * @returns {Promise<void>} 最新请求完成后合并审批选项和已选标签，过期响应被丢弃。
 */
async function searchApprovalUsers(keyword) {
  const sequence = ++approvalUserSearchSequence
  approvalUserLoading.value = true
  try {
    const response = await listApprovalUserOptions({
      keyword: String(keyword || '').trim().slice(0, 64),
      pageNum: 1,
      pageSize: 30
    })
    if (sequence === approvalUserSearchSequence) {
      const safeOptions = (Array.isArray(response.rows) ? response.rows : [])
        .map(normalizeUserOption)
        .filter(Boolean)
      safeOptions.forEach(option => {
        approvalUserOptionCache.set(option.value, option)
        verifiedApprovalUserIds.add(option.value)
      })
      refreshApprovalUserOptions(safeOptions)
    }
  } finally {
    if (sequence === approvalUserSearchSequence) approvalUserLoading.value = false
  }
}

/**
 * 远程检索普通启用用户作为抄送接收人，不把该结果用于任何任务分配字段。
 * @param {string} keyword 用户输入的姓名或账号关键词。
 * @returns {Promise<void>} 最新请求完成后合并抄送选项和已选标签，过期响应被丢弃。
 */
async function searchCopyUsers(keyword) {
  const sequence = ++copyUserSearchSequence
  copyUserLoading.value = true
  try {
    const response = await listIdentityOptions({
      type: 'user',
      keyword: String(keyword || '').trim().slice(0, 64),
      pageNum: 1,
      pageSize: 30
    })
    if (sequence === copyUserSearchSequence) {
      const safeOptions = (Array.isArray(response.rows) ? response.rows : [])
        .map(normalizeUserOption)
        .filter(Boolean)
      safeOptions.forEach(option => {
        copyUserOptionCache.set(option.value, option)
        verifiedCopyUserIds.add(option.value)
      })
      refreshCopyUserOptions(safeOptions)
    }
  } finally {
    if (sequence === copyUserSearchSequence) copyUserLoading.value = false
  }
}

/**
 * 打开动态加签或减签弹窗，并冻结当前服务端成员目标。
 * @param {'ADD'|'REMOVE'} action 动态成员调整动作。
 * @param {object|null} member REMOVE 时由服务端状态投影返回的目标成员。
 * @returns {Promise<void>} 门禁通过后打开弹窗；ADD 同时加载首批审批资格用户。
 */
async function openMultiInstanceDialog(action, member = null) {
  if (!assertMultiInstanceAdjustmentAllowed(action, member)) return
  resetMultiInstanceDialog()
  multiInstanceDialog.action = action
  if (action === 'REMOVE') {
    multiInstanceDialog.targetTaskId = member.activeTaskId
    multiInstanceDialog.targetName = member.name || `用户 ${member.userId}`
  }
  multiInstanceDialog.visible = true
  if (action === 'ADD') await searchMultiInstanceUsers('')
}

/**
 * 从正式审批资格目录远程检索加签用户，并隔离淘汰晚到响应。
 * @param {string} keyword 用户输入的姓名或账号关键词。
 * @returns {Promise<void>} 最新请求完成后更新加签选择器选项。
 */
async function searchMultiInstanceUsers(keyword) {
  const sequence = ++multiInstanceUserSearchSequence
  multiInstanceUserLoading.value = true
  try {
    const response = await listApprovalUserOptions({
      keyword: String(keyword || '').trim().slice(0, 64),
      pageNum: 1,
      pageSize: 30
    })
    if (sequence !== multiInstanceUserSearchSequence) return
    const safeOptions = (Array.isArray(response.rows) ? response.rows : [])
      .map(normalizeUserOption)
      .filter(Boolean)
    safeOptions.forEach(option => {
      multiInstanceUserOptionCache.set(option.value, option)
    })
    refreshMultiInstanceUserOptions(safeOptions)
  } finally {
    if (sequence === multiInstanceUserSearchSequence) multiInstanceUserLoading.value = false
  }
}

/**
 * 合并最新身份目录结果与当前已选加签用户，保证远程检索切换后标签稳定。
 * @param {Array<{value: string, label: string, type: 'user'}>} searchResults 最新安全用户选项。
 * @returns {void} 加签选项更新为去重后的稳定集合。
 */
function refreshMultiInstanceUserOptions(searchResults) {
  const mergedOptions = new Map()
  multiInstanceDialog.userIds.map(String).forEach(value => {
    const cachedOption = multiInstanceUserOptionCache.get(value)
    if (cachedOption) mergedOptions.set(value, cachedOption)
  })
  searchResults.forEach(option => mergedOptions.set(option.value, option))
  multiInstanceUserOptions.value = Array.from(mergedOptions.values())
}

/**
 * 响应成员区手动刷新，按页面动作门禁查询当前动态多实例状态。
 * @returns {Promise<void>} 查询成功后替换成员快照；失败时保留原快照并展示错误。
 */
async function refreshMultiInstanceState() {
  if (!canAdjustMultiInstance.value || actionBusy.value
    || multiInstanceRefreshing.value || multiInstanceBusy.value) return
  const result = await requestMultiInstanceState(detail.currentTask.taskId, false)
  if (result.state) return
  multiInstanceError.value = requestErrorMessage(result.error, '会签成员状态刷新失败')
  if (isConflictResponse(result.error)) await loadDetail(multiInstanceDialog.visible)
}

/**
 * 通过真实 API 查询指定任务的动态多实例状态；完成冲突可绕过页面 busy 门禁强制执行。
 * @param {string} taskId 发生完成冲突的 Flowable 活动任务主键。
 * @param {boolean} force 是否忽略 actionBusy、普通刷新进行中等界面门禁并发起新查询。
 * @returns {Promise<{state: object|null, error: unknown|null}>} 成功返回规范化状态，失败返回原始请求错误。
 */
async function requestMultiInstanceState(taskId, force) {
  if (!force && (actionBusy.value || multiInstanceRefreshing.value || multiInstanceBusy.value)) {
    return { state: null, error: null }
  }
  const normalizedTaskId = String(taskId || '').trim()
  if (!normalizedTaskId || normalizedTaskId.length > 64) {
    return { state: null, error: new Error('当前任务主键不合法') }
  }
  // 强制刷新会递增序号并淘汰先前手动刷新，避免晚到旧 revision 覆盖冲突后的新快照。
  const requestSequence = ++multiInstanceRefreshSequence
  multiInstanceRefreshing.value = true
  if (!force) multiInstanceError.value = ''
  try {
    const response = await getMultiInstanceState(normalizedTaskId)
    const state = normalizeMultiInstanceState(response.data)
    if (requestSequence === multiInstanceRefreshSequence
      && String(detail.currentTask?.taskId || '') === normalizedTaskId) {
      detail.multiInstanceState = state
    }
    return { state, error: null }
  } catch (error) {
    return { state: null, error }
  } finally {
    if (requestSequence === multiInstanceRefreshSequence) multiInstanceRefreshing.value = false
  }
}

/**
 * 处理动态多实例完成的 revision 冲突，刷新服务端成员版本且不改写用户办理草稿。
 * @param {string} taskId 提交完成请求时冻结的 Flowable 活动任务主键。
 * @returns {Promise<void>} 状态仍活动时更新 revision 并等待显式重提；任务失效时关闭弹窗并刷新详情。
 */
async function handleDynamicCompletionConflict(taskId) {
  const result = await requestMultiInstanceState(taskId, true)
  if (result.state) {
    if (String(detail.currentTask?.taskId || '') === taskId) {
      // 只替换服务端成员快照和下一次 CAS 版本，不重建表单或弹窗，因而意见、附件及用户选择保持原值。
      detail.multiInstanceState = result.state
      actionDialog.expectedRevision = result.state.revision
      actionDialog.error = '会签成员已发生变化，已刷新最新版本。请核对成员后再次点击确认提交。'
      proxy.$modal.msgWarning('会签成员已发生变化，办理草稿已保留，请核对后重新提交')
      return
    }
    // 路由或详情已经切换到其他任务时，当前弹窗不能再绑定旧任务，按任务失效路径完整刷新。
    actionDialog.visible = false
    await loadDetail()
    return
  }
  if (isTaskUnavailableResponse(result.error)) {
    // 动态状态接口对已完成历史任务返回 409；此时原任务不能重提，必须退出弹窗并重建整页授权快照。
    actionDialog.visible = false
    proxy.$modal.msgWarning('当前任务已发生变化，已关闭办理窗口并刷新详情')
    await loadDetail()
    return
  }
  actionDialog.error = '最新会签成员状态刷新失败，办理草稿仍已保留，请稍后再次提交。'
  proxy.$modal.msgError(requestErrorMessage(result.error, '会签成员状态刷新失败'))
}

/**
 * 提交动态加签或减签命令，携带当前服务端 revision 形成乐观并发控制。
 * @returns {Promise<void>} 写入成功后重新加载聚合详情；冲突时刷新真实服务端状态。
 */
async function submitMultiInstanceAdjustment() {
  const action = multiInstanceDialog.action
  if (!assertMultiInstanceAdjustmentAllowed(action, null, true)) return
  const request = {
    taskId: detail.currentTask.taskId,
    action,
    expectedRevision: multiInstanceState.value.revision,
    comment: multiInstanceDialog.comment.trim(),
    userIds: action === 'ADD' ? numericUserIds(multiInstanceDialog.userIds) : [],
    targetTaskId: action === 'REMOVE' ? multiInstanceDialog.targetTaskId : null
  }
  multiInstanceBusy.value = true
  multiInstanceDialog.error = ''
  try {
    const response = await adjustMultiInstance(request)
    detail.multiInstanceState = normalizeMultiInstanceState(response.data)
    proxy.$modal.msgSuccess(action === 'ADD' ? '加签成功' : '减签成功')
    multiInstanceDialog.visible = false
    await loadDetail()
  } catch (error) {
    if (isMultiInstanceRevisionConflict(error)) {
      multiInstanceDialog.error = '会签成员状态已变化，已刷新最新结果，请核对后重试'
      proxy.$modal.msgWarning('会签成员状态已变化，已为你刷新最新结果')
      await loadDetail(true)
    } else {
      multiInstanceDialog.error = requestErrorMessage(error, '会签成员调整失败')
    }
  } finally {
    multiInstanceBusy.value = false
  }
}

/**
 * 校验动态调整所需的对象权限、状态、revision、成员目标和用户目录来源。
 * @param {'ADD'|'REMOVE'} action 待执行的动态成员动作。
 * @param {object|null} member 打开 REMOVE 弹窗时的服务端成员对象。
 * @param {boolean} validateInput 是否同时校验弹窗意见和已选目标。
 * @returns {boolean} 当前页面快照允许发起真实 API 时返回 true。
 */
function assertMultiInstanceAdjustmentAllowed(action, member = null, validateInput = false) {
  if (!['ADD', 'REMOVE'].includes(action)) return denyAction('会签调整动作不合法')
  if (!ready.value || loading.value) return denyAction('流程详情尚未就绪')
  if (!canAdjustMultiInstance.value) return denyAction('当前任务不允许调整会签成员')
  if (multiInstanceDialogClosing.value) return denyAction('会签调整窗口正在关闭，请稍后重试')
  if (actionBusy.value || multiInstanceBusy.value) return denyAction('任务正在处理中，请勿重复提交')
  if (!Number.isInteger(multiInstanceState.value.revision)
    || multiInstanceState.value.revision < 0
    || multiInstanceState.value.revision > 2147483647) {
    return denyAction('会签成员版本不合法，请刷新后重试')
  }
  if (action === 'ADD' && remainingMultiInstanceCapacity.value < 1) {
    return denyAction('会签成员已达到100人上限')
  }
  if (member) {
    const currentMember = multiInstanceMembers.value.find(item => item.activeTaskId === member.activeTaskId)
    if (!currentMember?.active || !currentMember.removable) return denyAction('该成员当前不能减签')
  }
  if (!validateInput) return true
  const comment = multiInstanceDialog.comment.trim()
  if (!comment || comment.length > 500) return denyAction('调整意见不能为空且不能超过500个字符')
  if (action === 'ADD') {
    const values = multiInstanceDialog.userIds
    if (!Array.isArray(values) || values.length < 1 || values.length > remainingMultiInstanceCapacity.value) {
      return denyAction(`请选择1至${remainingMultiInstanceCapacity.value}名新增成员`)
    }
    const normalizedIds = values.map(String)
    if (new Set(normalizedIds).size !== normalizedIds.length) return denyAction('新增成员不能重复选择')
    if (values.some(value => !positiveUserId(value))) return denyAction('新增成员包含非法用户主键')
    if (normalizedIds.some(value => multiInstanceMemberIds.value.has(value))) return denyAction('不能重复添加现有会签成员')
    // 选择器只负责收集合法主键；用户启用状态和审批资格由后端在写事务中按实时 RBAC 再次校验。
    return true
  }
  const target = multiInstanceMembers.value.find(item => item.activeTaskId === multiInstanceDialog.targetTaskId)
  if (!target?.active || !target.removable) return denyAction('减签目标状态已变化，请刷新后重试')
  return true
}

/**
 * 校验并规范服务端动态多实例状态，避免畸形或失配投影进入页面权限门禁。
 * @param {unknown} value 详情接口或调整接口返回的原始状态。
 * @returns {object} 字段边界、成员唯一性和活动任务关系均合法的状态副本。
 */
function normalizeMultiInstanceState(value) {
  if (!value || !['ALL', 'ANY'].includes(value.mode)) throw new Error('会签模式数据不合法')
  const activityId = String(value.activityId || '').trim()
  if (!activityId || activityId.length > 64) throw new Error('会签活动标识不合法')
  if (!Number.isInteger(value.revision) || value.revision < 0 || value.revision > 2147483647) {
    throw new Error('会签成员版本不合法')
  }
  if (!Array.isArray(value.members) || value.members.length < 1 || value.members.length > 100) {
    throw new Error('会签成员数量不合法')
  }
  const userIds = new Set()
  const activeTaskIds = new Set()
  const members = value.members.map(member => {
    if (!positiveUserId(member?.userId)) throw new Error('会签成员主键不合法')
    const userId = Number(member.userId)
    if (userIds.has(userId)) throw new Error('会签成员数据重复')
    userIds.add(userId)
    const name = String(member.name || '').trim()
    if (name.length > 200 || typeof member.active !== 'boolean' || typeof member.removable !== 'boolean') {
      throw new Error('会签成员状态不合法')
    }
    const activeTaskId = member.activeTaskId == null ? null : String(member.activeTaskId).trim()
    const executionId = member.executionId == null ? null : String(member.executionId).trim()
    if (member.active) {
      if (!activeTaskId || activeTaskId.length > 64 || !executionId || executionId.length > 64) {
        throw new Error('会签活动任务关系不合法')
      }
      if (activeTaskIds.has(activeTaskId)) throw new Error('会签活动任务数据重复')
      activeTaskIds.add(activeTaskId)
    } else if (activeTaskId || executionId || member.removable) {
      throw new Error('已完成会签成员状态不合法')
    }
    return { userId, name, activeTaskId, executionId, active: member.active, removable: member.removable }
  })
  return { mode: value.mode, activityId, revision: value.revision, members }
}

/**
 * 清理流程时间线意见投影，禁止原始审计 JSON 进入页面状态或用户可见 DOM。
 * @param {unknown} value 后端返回的历史活动列表。
 * @returns {object[]} 仅保留安全 opinion 的历史活动副本。
 */
function normalizeTimelineComments(value) {
  if (!Array.isArray(value)) throw new Error('流程时间线数据不合法')
  return value.map(node => {
    if (!node || typeof node !== 'object') throw new Error('流程时间线节点不合法')
    const comments = Array.isArray(node.comments) ? node.comments : []
    return {
      ...node,
      comments: comments.map(comment => {
        if (!comment || typeof comment !== 'object') throw new Error('流程审批意见投影不合法')
        if (comment.opinion != null && typeof comment.opinion !== 'string') {
          throw new Error('流程审批意见正文不合法')
        }
        const opinion = String(comment.opinion || '').trim()
        if (new TextEncoder().encode(opinion).byteLength > MAX_TIMELINE_OPINION_BYTES) {
          throw new Error('流程审批意见正文超过安全上限')
        }
        const visibleComment = { ...comment, opinion }
        // message 和 audit 是内部审计载体，即使后端兼容返回也不能保存在用户页面状态中。
        delete visibleComment.message
        delete visibleComment.audit
        return visibleComment
      })
    }
  })
}

/**
 * 判断请求失败是否为服务端 revision 或执行树变化导致的 HTTP 409。
 * @param {unknown} error Axios 错误或统一请求拦截器返回值。
 * @returns {boolean} 响应状态或业务状态为 409 时返回 true。
 */
function isConflictResponse(error) {
  return Number(error?.code) === 409
    || Number(error?.response?.status) === 409
    || Number(error?.response?.data?.code) === 409
}

/**
 * 判断失败是否为服务端明确标识的动态多实例 revision 冲突。
 * @param {unknown} error 统一 BusinessError 或保留 Axios response 的传输错误。
 * @returns {boolean} 业务码为 409 且 subCode 精确匹配冻结值时返回 true。
 */
function isMultiInstanceRevisionConflict(error) {
  const subCode = String(error?.subCode || error?.response?.data?.subCode || '').trim()
  return isConflictResponse(error) && subCode === MULTI_INSTANCE_REVISION_CONFLICT_SUBCODE
}

/**
 * 判断冲突后的强制查询是否说明原活动任务已不能继续办理。
 * @param {unknown} error getMultiInstanceState 返回的统一业务错误或 Axios 错误。
 * @returns {boolean} 403/404/409 表示对象权限、存在性或活动状态已经变化。
 */
function isTaskUnavailableResponse(error) {
  const code = Number(error?.code ?? error?.response?.data?.code ?? error?.response?.status)
  return [403, 404, 409].includes(code)
}

/**
 * 从请求错误中提取不含敏感响应正文的稳定用户提示。
 * @param {unknown} error Axios 错误或普通 Error。
 * @param {string} fallback 无可用消息时的兜底文案。
 * @returns {string} 可展示的短错误消息。
 */
function requestErrorMessage(error, fallback) {
  const responseMessage = String(error?.response?.data?.msg || '').trim()
  const errorMessage = typeof error?.message === 'string' ? error.message.trim() : ''
  return (responseMessage || errorMessage || fallback).slice(0, 200)
}

/**
 * 详情并发刷新后按最新成员快照复核弹窗草稿，只保留仍可合法重试的输入。
 * @returns {void} 草稿合法时保持弹窗；目标失效时关闭并在成员区显示稳定提示。
 */
function reconcileMultiInstanceDraft() {
  if (!multiInstanceDialog.visible) return
  if (!canAdjustMultiInstance.value) {
    multiInstanceDialog.visible = false
    resetMultiInstanceDialog()
    multiInstanceError.value = '当前任务状态已变化，不能继续调整会签成员'
    return
  }
  if (multiInstanceDialog.action === 'ADD') {
    const originalIds = multiInstanceDialog.userIds.map(String)
    const retainedIds = originalIds
      .filter(userId => positiveUserId(userId) && !multiInstanceMemberIds.value.has(userId))
      .slice(0, remainingMultiInstanceCapacity.value)
    multiInstanceDialog.userIds = retainedIds
    refreshMultiInstanceUserOptions([])
    if (retainedIds.length !== originalIds.length) {
      multiInstanceDialog.error = '部分成员已被其他操作加入或达到人数上限，请重新核对'
    }
    return
  }
  const target = multiInstanceMembers.value.find(
    member => member.activeTaskId === multiInstanceDialog.targetTaskId)
  if (!target?.active || !target.removable) {
    multiInstanceDialog.error = '减签目标状态已变化，请选择最新可移除成员'
  }
}

/**
 * 将身份目录选项规范为页面可用的最小安全结构，拒绝非用户、非法主键和空标签。
 * @param {unknown} option 身份目录返回的原始选项。
 * @returns {{value: string, label: string, type: 'user'}|null} 安全用户选项或 null。
 */
function normalizeUserOption(option) {
  if (option?.type !== 'user' || !positiveUserId(option.value)) return null
  const value = String(option.value)
  const label = String(option.label || '').trim()
  if (!label || label.length > 200) return null
  return { value, label, type: 'user' }
}

/**
 * 合并审批资格检索结果与当前目标/下一办理人，避免关键词变化导致标签退化为裸 ID。
 * @param {Array<{value: string, label: string, type: 'user'}>} searchResults 最新审批资格检索结果。
 * @returns {void} approvalUserOptions 更新为去重后的稳定展示集合。
 */
function refreshApprovalUserOptions(searchResults) {
  const mergedOptions = new Map()
  selectedApprovalUserValues().forEach(value => {
    const cachedOption = approvalUserOptionCache.get(value)
    if (cachedOption) mergedOptions.set(value, cachedOption)
  })
  searchResults.forEach(option => mergedOptions.set(option.value, option))
  approvalUserOptions.value = Array.from(mergedOptions.values())
}

/**
 * 汇总目标用户和下一办理人的当前选择值，供审批资格标签缓存按需保留。
 * @returns {string[]} 去重后的规范字符串用户主键。
 */
function selectedApprovalUserValues() {
  const values = [actionDialog.userId, ...actionDialog.nextUserIds]
    .filter(positiveUserId)
    .map(String)
  return Array.from(new Set(values))
}

/**
 * 合并普通用户检索结果与当前抄送选择，远程搜索切换时保留稳定标签。
 * @param {Array<{value: string, label: string, type: 'user'}>} searchResults 最新通用用户检索结果。
 * @returns {void} copyUserOptions 更新为去重后的稳定展示集合。
 */
function refreshCopyUserOptions(searchResults) {
  const mergedOptions = new Map()
  actionDialog.copyUserIds.map(String).forEach(value => {
    const cachedOption = copyUserOptionCache.get(value)
    if (cachedOption) mergedOptions.set(value, cachedOption)
  })
  searchResults.forEach(option => mergedOptions.set(option.value, option))
  copyUserOptions.value = Array.from(mergedOptions.values())
}

/**
 * 提交当前对话框动作；完成任务时先校验真实部署表单并提取附件 UUID。
 * @returns {Promise<void>} 后端事务成功后刷新授权详情并关闭对话框。
 */
async function submitAction() {
  const type = actionDialog.type
  if (!['complete', 'reject', 'return', 'delegate', 'resolve', 'transfer'].includes(type)) {
    return denyAction('任务动作不合法')
  }
  // actionContext 是弹窗打开时绑定的不可变业务对象；不能在提交时改读可能已切换的详情任务。
  const actionContext = currentActionDialogTaskContext()
  if (!actionContext) {
    actionDialog.visible = false
    return denyAction('当前任务已切换，请重新打开办理窗口')
  }
  if (!assertActionAllowed(type, true)) return
  // 先占用页面动作锁再执行异步表单校验，避免双击在 loading 渲染前并发穿透为两次真实请求。
  actionBusy.value = true
  actionDialog.error = ''
  const taskId = actionContext.taskId
  const comment = actionDialog.comment.trim()
  const copyUserIds = numericUserIds(actionDialog.copyUserIds)
  const nextUserIds = type === 'complete' && nextUserSelectionEnabled.value
    ? numericUserIds(actionDialog.nextUserIds)
    : []
  const expectedRevision = actionDialog.expectedRevision
  const actionTitle = actionDialogTitle.value
  try {
    let variables = {}
    if (type === 'complete' && detail.currentTaskForm) {
      if (!taskFormRef.value) {
        actionDialog.error = '任务表单尚未就绪'
        return
      }
      const valid = await taskFormRef.value.validate().catch(error => {
        showComponentError(error)
        return false
      })
      if (!valid) return
      variables = taskFormRef.value.getValues()
    }
    // 表单校验包含异步步骤，真正调用写接口前必须再次核对路由和详情仍绑定原任务。
    if (!currentActionDialogTaskContext()
      || actionDialog.boundProcessInstanceId !== actionContext.processInstanceId
      || actionDialog.boundTaskId !== actionContext.taskId
      || actionDialog.boundDetailSequence !== actionContext.detailSequence) {
      actionDialog.visible = false
      return denyAction('当前任务已切换，本次操作未提交')
    }
    if (type === 'complete') {
      const request = { taskId, comment, variables, copyUserIds, nextUserIds }
      // 只有详情明确投影动态多实例状态时才发送 expectedRevision，保持普通和静态任务原有请求契约。
      if (expectedRevision !== null) request.expectedRevision = expectedRevision
      await completeTask(request)
    }
    if (type === 'reject') await rejectTask({ taskId, comment, copyUserIds })
    if (type === 'return') await returnTask({ taskId, comment, copyUserIds })
    if (type === 'delegate') {
      await delegateTask({ taskId, userId: Number(actionDialog.userId), comment, copyUserIds })
    }
    if (type === 'resolve') await resolveTask({ taskId, comment, copyUserIds })
    if (type === 'transfer') {
      await transferTask({ taskId, userId: Number(actionDialog.userId), comment, copyUserIds })
    }
    proxy.$modal.msgSuccess(`${actionTitle}成功`)
    actionDialog.visible = false
    if (['delegate', 'resolve', 'transfer', 'return'].includes(type)) {
      // 委派、办结或转办后当前用户立即失去活动任务对象权限，直接返回来源列表。
      await closePage()
      return
    }
    await loadDetail()
  } catch (error) {
    if (type === 'complete' && expectedRevision !== null && isMultiInstanceRevisionConflict(error)) {
      await handleDynamicCompletionConflict(taskId)
      return
    }
    if (type === 'resolve' && isTaskUnavailableResponse(error)) {
      // 其他会话已办结或对象权限已变化时，当前受托人不再拥有可重试对象，立即退出过期详情。
      proxy.$modal.msgWarning(requestErrorMessage(error, '委派任务状态已变化，已返回来源列表'))
      actionDialog.visible = false
      await closePage()
      return
    }
    // 失败时保留意见、表单和身份选择；用户可根据后端稳定错误修正或关闭窗口重新加载状态。
    actionDialog.error = requestErrorMessage(error, `${actionTitle}失败`)
  } finally {
    actionBusy.value = false
  }
}

/**
 * 取消当前用户对活动任务的真实认领，并由后端复核候选关系和委派状态。
 * @returns {Promise<void>} 用户确认且后端成功后刷新流程详情。
 */
async function confirmUnclaim() {
  if (!canUnclaim.value || actionBusy.value || multiInstanceBusy.value) {
    return denyAction('当前任务不允许取消认领')
  }
  // taskContext 固定用户点击时确认的任务，确认框等待期间不得跟随路由切换到其他任务。
  const taskContext = freezeCurrentTaskContext()
  if (!isCurrentTaskContext(taskContext)) return denyAction('当前任务状态已变化，请刷新后重试')
  actionBusy.value = true
  try {
    await proxy.$modal.confirm('确认取消认领当前任务吗？')
    if (!isCurrentTaskContext(taskContext) || !canUnclaim.value) {
      return denyAction('当前任务已切换，本次取消认领未提交')
    }
    await unclaimTask(taskContext.taskId)
    proxy.$modal.msgSuccess('取消认领成功')
    await loadDetail()
  } finally {
    actionBusy.value = false
  }
}

/**
 * 在动作发起前即时校验权限、流程状态、任务归属和动作参数，后端仍执行最终一致性校验。
 * @param {string} type 待执行的任务动作类型。
 * @param {boolean} validateInput 是否同时校验对话框输入。
 * @returns {boolean} 当前页面快照允许继续发起请求时返回 true。
 */
function assertActionAllowed(type, validateInput = false) {
  if (!['complete', 'reject', 'return', 'delegate', 'resolve', 'transfer', 'unclaim', 'resubmit'].includes(type)) {
    return denyAction('任务动作不合法')
  }
  const requiredPermission = type === 'unclaim' ? 'workflow:process:claim'
    : type === 'resubmit' ? 'workflow:process:start' : 'workflow:process:approval'
  if (!ready.value || loading.value) return denyAction('流程详情尚未就绪')
  if (!hasPermission(requiredPermission)) return denyAction('没有执行该操作的权限')
  if (actionBusy.value || multiInstanceBusy.value) return denyAction('任务正在处理中，请勿重复提交')
  const expectedStatus = type === 'resubmit' ? 'returned' : 'running'
  if (detail.processStatus !== expectedStatus || !detail.currentTask?.active) return denyAction('当前任务已不处于可办理状态')
  if (!detail.currentTask.taskId || String(detail.currentTask.taskId).length > 64) return denyAction('当前任务主键不合法')
  if (!currentTaskOwned.value) return denyAction('当前任务不属于登录用户')
  if (type === 'resubmit' && !canResubmit.value) return denyAction('当前申请不允许重新提交')
  if (type === 'complete' && pendingDelegation.value) {
    return denyAction('待处理委派任务不能直接完成')
  }
  if (type === 'resolve' && !canResolve.value) {
    return denyAction('当前任务不允许完成委派')
  }
  if (['reject', 'return', 'delegate', 'transfer', 'unclaim'].includes(type) && hasDelegationContext.value) {
    return denyAction('委派状态下不能执行该操作')
  }
  if (type === 'return' && detail.returnAllowed !== true) {
    return denyAction('当前任务结构或状态不支持退回，请使用驳回或继续办理')
  }
  if (!validateInput) return true
  const comment = actionDialog.comment.trim()
  if (!comment || comment.length > 500) return denyAction('办理意见不能为空且不能超过500个字符')
  if (type === 'complete') {
    const revision = actionDialog.expectedRevision
    const hasDynamicState = Boolean(multiInstanceState.value)
    if (hasDynamicState !== (revision !== null)) {
      return denyAction('会签成员状态已变化，请关闭办理窗口后重试')
    }
    if (revision !== null && (!Number.isInteger(revision) || revision < 0 || revision > 2147483647)) {
      return denyAction('会签成员版本不合法，请刷新后重试')
    }
  }
  if (['delegate', 'transfer'].includes(type)) {
    if (!positiveUserId(actionDialog.userId)) return denyAction('请选择有效的目标用户')
    if (String(actionDialog.userId) === currentUserId.value) return denyAction('目标用户不能是当前办理人')
    if (!verifiedApprovalUserIds.has(String(actionDialog.userId))) {
      return denyAction('目标用户不在当前审批资格检索结果中')
    }
  }
  if (supportsCopyAction.value
    && !validSelectedUsers(actionDialog.copyUserIds, '抄送人', verifiedCopyUserIds)) return false
  if (type === 'complete' && nextUserSelectionRequired.value && actionDialog.nextUserIds.length === 0) {
    return denyAction(`${nextUserSelectionLabel.value}不能为空`)
  }
  if (type === 'complete' && !nextUserSelectionEnabled.value && actionDialog.nextUserIds.length > 0) {
    return denyAction('当前任务不允许指定下一办理人')
  }
  if (type === 'complete' && nextUserSelectionEnabled.value
    && !validSelectedUsers(actionDialog.nextUserIds, nextUserSelectionLabel.value, verifiedApprovalUserIds)) return false
  return true
}

/**
 * 校验多选用户数量、唯一性、主键格式及指定服务端目录来源，阻止篡改值进入真实动作 API。
 * @param {unknown} values 多选组件当前用户主键集合。
 * @param {string} fieldLabel 用于稳定错误提示的字段名称。
 * @param {Set<string>} verifiedIds 当前字段对应目录已确认的用户主键集合。
 * @returns {boolean} 集合可安全转换并提交时返回 true。
 */
function validSelectedUsers(values, fieldLabel, verifiedIds) {
  if (!Array.isArray(values)) return denyAction(`${fieldLabel}选择不合法`)
  if (values.length > 100) return denyAction(`${fieldLabel}不能超过100人`)
  const normalizedIds = values.map(String)
  if (new Set(normalizedIds).size !== normalizedIds.length) return denyAction(`${fieldLabel}不能重复选择`)
  if (values.some(value => !positiveUserId(value))) return denyAction(`${fieldLabel}包含非法用户主键`)
  if (normalizedIds.some(value => !verifiedIds.has(value))) {
    return denyAction(`${fieldLabel}包含未经有效检索确认的用户`)
  }
  return true
}

/**
 * 将已经通过安全门禁的字符串用户主键转换为后端 DTO 所需的 JSON 数字数组。
 * @param {Array<string|number>} values 已通过 validSelectedUsers 校验的用户主键集合。
 * @returns {number[]} 保持选择顺序的 JavaScript 安全正整数数组。
 */
function numericUserIds(values) {
  return values.map(value => Number(value))
}

/**
 * 显示前端即时门禁提示并终止当前动作。
 * @param {string} message 面向用户的稳定门禁提示。
 * @returns {false} 固定返回 false，便于动作校验直接返回。
 */
function denyAction(message) {
  proxy.$modal.msgWarning(message)
  return false
}

/**
 * 判断当前登录用户是否拥有指定操作权限，兼容超级权限标识。
 * @param {string} permission 若依权限字符。
 * @returns {boolean} 权限集合包含目标或超级权限时返回 true。
 */
function hasPermission(permission) {
  return (userStore.permissions || []).some(item => item === '*:*:*' || item === permission)
}

/**
 * 判断身份目录值能否安全转换为后端要求的正整数用户主键。
 * @param {unknown} value 身份目录返回值或选择器当前值。
 * @returns {boolean} 值为 JavaScript 安全正整数时返回 true。
 */
function positiveUserId(value) {
  if (typeof value === 'number') return Number.isSafeInteger(value) && value > 0
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return false
  const number = Number(value)
  return Number.isSafeInteger(number) && number > 0
}

/**
 * 清空动作对话框中的临时状态，防止不同动作复用旧意见或目标。
 * @returns {void} 无返回值。
 */
function resetActionDialog() {
  actionDialog.type = ''
  actionDialog.comment = ''
  actionDialog.userId = ''
  actionDialog.copyUserIds = []
  actionDialog.nextUserIds = []
  actionDialog.expectedRevision = null
  actionDialog.boundProcessInstanceId = ''
  actionDialog.boundTaskId = ''
  actionDialog.boundDetailSequence = -1
  actionDialog.error = ''
  approvalUserOptions.value = []
  approvalUserOptionCache.clear()
  verifiedApprovalUserIds.clear()
  approvalUserSearchSequence++
  approvalUserLoading.value = false
  copyUserOptions.value = []
  copyUserOptionCache.clear()
  verifiedCopyUserIds.clear()
  copyUserSearchSequence++
  copyUserLoading.value = false
  actionFormRef.value?.clearValidate()
}

/**
 * 处理普通动作弹窗的关闭完成事件，避免旧关闭动画清空随后打开的新任务草稿。
 * @returns {void} 弹窗仍关闭时清理动作、任务绑定和身份缓存；已重新打开时保持新草稿。
 */
function handleActionDialogClosed() {
  if (actionDialog.visible) return
  resetActionDialog()
}

/**
 * 标记动态调整弹窗进入关闭期，阻止 leave 动画结束前再次打开并写入新草稿。
 * @returns {void} 无返回值；关闭期由 closed 事件统一解除。
 */
function handleMultiInstanceDialogClose() {
  multiInstanceDialogClosing.value = true
}

/**
 * 处理动态调整弹窗的关闭完成事件，解除关闭期并清理已关闭弹窗的业务草稿。
 * @returns {void} 弹窗仍关闭时清理成员、意见和目录缓存；已重新打开时只解除旧关闭锁。
 */
function handleMultiInstanceDialogClosed() {
  multiInstanceDialogClosing.value = false
  if (multiInstanceDialog.visible) return
  resetMultiInstanceDialog()
}

/**
 * 清空动态调整弹窗及其独立身份目录缓存，防止旧 revision 对应的目标被再次提交。
 * @returns {void} 无返回值。
 */
function resetMultiInstanceDialog() {
  multiInstanceDialog.action = 'ADD'
  multiInstanceDialog.comment = ''
  multiInstanceDialog.userIds = []
  multiInstanceDialog.targetTaskId = ''
  multiInstanceDialog.targetName = ''
  multiInstanceDialog.error = ''
  multiInstanceUserOptions.value = []
  multiInstanceUserOptionCache.clear()
  multiInstanceUserSearchSequence++
  multiInstanceUserLoading.value = false
}

/**
 * 深复制后端返回的 JSON 值，避免编辑当前表单时修改原始详情对象。
 * @param {unknown} value 待复制的安全 JSON 值。
 * @returns {unknown} 与原值脱离引用的 JSON 副本。
 */
function cloneJson(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value))
}

/**
 * 生成历史表单循环的稳定键，兼容开始节点没有任务主键的情况。
 * @param {object} form 历史表单快照。
 * @param {number} index 当前列表索引。
 * @returns {string} 页面内稳定且可读的表单键。
 */
function formKey(form, index) {
  return form.taskId || `${form.activityId || form.nodeKey || 'form'}-${form.snapshotTime || index}`
}

/**
 * 格式化后端 ISO 时间，空值统一显示短横线。
 * @param {string|null|undefined} value 后端时间值。
 * @returns {string} 本地时区日期时间或短横线。
 */
function formatDate(value) {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false })
}

/**
 * 将非负毫秒时长格式化为天、小时、分钟和秒。
 * @param {number|null|undefined} value 后端返回的毫秒时长。
 * @returns {string} 紧凑时长文本或短横线。
 */
function formatDuration(value) {
  if (value == null || !Number.isFinite(Number(value)) || Number(value) < 0) return '-'
  let seconds = Math.floor(Number(value) / 1000)
  const days = Math.floor(seconds / 86400)
  seconds %= 86400
  const hours = Math.floor(seconds / 3600)
  seconds %= 3600
  const minutes = Math.floor(seconds / 60)
  seconds %= 60
  return [days && `${days}天`, hours && `${hours}小时`, minutes && `${minutes}分钟`, `${seconds}秒`].filter(Boolean).join(' ')
}

/**
 * 解析循环轮次真实办理人名称，并在历史任务投影缺失时显示稳定用户主键。
 * @param {{taskId?:string,actorUserId?:string}} round 服务端循环轮次审计视图。
 * @returns {string} 历史任务中的完成人名称，或带“用户”前缀的主键回退文本。
 */
function controlledLoopActorName(round) {
  const displayName = controlledLoopActorNames.value.get(String(round?.taskId || ''))
  return displayName || `用户 ${round?.actorUserId || '-'}`
}

/**
 * 组合时间线节点的起止时间，运行中节点只展示开始时间。
 * @param {object} node 后端时间线活动视图。
 * @returns {string} 节点时间范围文本。
 */
function timelineTimestamp(node) {
  const start = formatDate(node.startTime)
  return node.endTime ? `${start} 至 ${formatDate(node.endTime)}` : `${start} 开始`
}

/**
 * 根据节点意见和完成状态选择 Element Plus 时间线颜色类型。
 * @param {object} node 后端时间线活动视图。
 * @returns {string} Element Plus 支持的时间线类型。
 */
function timelineType(node) {
  if (node.comments?.some(comment => comment.type === '3')) return 'danger'
  if (node.comments?.some(comment => comment.type === '2')) return 'warning'
  return node.endTime ? 'success' : 'primary'
}

/**
 * 将 Flowable 活动类型转换为稳定中文名称。
 * @param {string} type Flowable 活动类型。
 * @returns {string} 活动中文名称或原始类型。
 */
function activityTypeName(type) {
  return ({ startEvent: '流程开始', endEvent: '流程结束', userTask: '用户任务' }[type] || type || '流程节点')
}

/**
 * 组合候选用户和候选组的安全身份投影。
 * @param {object} node 后端时间线活动视图。
 * @returns {string} 逗号分隔的候选身份文本。
 */
function candidateText(node) {
  return (node.candidates || [])
    .map(item => `${item.identityType === 'user' ? '用户' : '组'}:${item.identityId}`)
    .join('，')
}

/**
 * 根据服务端意见类型选择标签颜色，不推断未定义业务状态。
 * @param {string} type 服务端意见类型编码。
 * @returns {string} Element Plus 标签类型。
 */
function commentType(type) {
  if (type === '1') return 'success'
  if (type === '2') return 'warning'
  if (['3', '6'].includes(type)) return 'danger'
  return 'info'
}

/**
 * 清理流程标识中的文件名非法字符，供 Viewer 导出 SVG 使用。
 * @param {string} value 流程标识或实例主键。
 * @returns {string} 可安全作为下载文件名前缀的文本。
 */
function safeFileName(value) {
  return String(value).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 120)
}

/**
 * 显示表单、流程图或附件处理错误，优先使用后端统一错误消息。
 * @param {Error|object} error 组件或请求返回的错误对象。
 * @param {string} fallback 未提供具体消息时的兜底文案。
 * @returns {void} 无返回值。
 */
function showComponentError(error, fallback = '流程数据处理失败') {
  proxy.$modal.msgError(error?.message || fallback)
}

/**
 * 优先关闭当前页签；无页签上下文时回退浏览器历史。
 * @returns {void} 无返回值。
 */
async function closePage() {
  const sourcePath = {
    start: '/office/create',
    own: '/office/own',
    todo: '/office/todo',
    claim: '/office/claim',
    finished: '/office/finished',
    copy: '/office/copy',
    manage: '/workflow/extensions/instance'
  }[String(route.query.source || '')]
  if (sourcePath && proxy.$tab?.closeOpenPage) {
    await proxy.$tab.closeOpenPage({ path: sourcePath })
    return
  }
  if (proxy.$tab?.closePage) {
    await proxy.$tab.closePage()
    return
  }
  if (window.history.length > 1) router.back()
  else await router.replace(sourcePath || '/office/own')
}

watch(() => route.fullPath, () => loadDetail())
const initialDetailLoad = loadDetail().finally(() => {
  detailPageInitialized = true
})

/**
 * 页签重新激活时重新查询实例详情，使退回、重提和审批后的流程图状态立即一致。
 * @returns {Promise<void>} 首次加载后从真实详情接口刷新页面状态。
 */
onActivated(async () => {
  if (!detailPageInitialized) return initialDetailLoad
  await loadDetail()
})
</script>

<style scoped lang="scss">
.workflow-detail {
  padding-top: 12px;
}

.workflow-detail__header {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.workflow-detail__identity,
.workflow-detail__actions,
.workflow-detail__meta,
.workflow-detail__timeline-heading,
.workflow-detail__timeline-meta {
  display: flex;
  align-items: center;
}

.workflow-detail__identity {
  min-width: 0;
  gap: 8px;
}

.workflow-detail__title {
  min-width: 0;
}

.workflow-detail__title h2 {
  margin: 0 0 4px;
  overflow: hidden;
  font-size: 18px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workflow-detail__meta,
.workflow-detail__timeline-meta {
  flex-wrap: wrap;
  gap: 8px 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-detail__actions {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.workflow-detail__actions .el-button + .el-button {
  margin-left: 0;
}

.workflow-detail__summary {
  margin-top: 16px;
}

.workflow-detail__tabs {
  margin-top: 12px;
}

.workflow-detail__controlled-loops {
  margin-top: 16px;
  padding: 16px;
  background: linear-gradient(135deg, var(--el-fill-color-lighter), var(--el-bg-color));
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
}

.workflow-detail__controlled-loop-heading h3 {
  margin: 0 0 4px;
  font-size: 16px;
}

.workflow-detail__controlled-loop-heading span,
.workflow-detail__controlled-loop-summary span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-detail__controlled-loop-card {
  margin-top: 12px;
  padding: 14px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.workflow-detail__controlled-loop-summary,
.workflow-detail__controlled-loop-tags {
  display: flex;
  align-items: center;
  gap: 8px;
}

.workflow-detail__controlled-loop-summary {
  justify-content: space-between;
  margin-bottom: 12px;
}

.workflow-detail__controlled-loop-summary > div:first-child {
  display: grid;
  gap: 4px;
}

.workflow-detail__multi-instance {
  margin-top: 18px;
  padding: 16px 0 18px;
  border-top: 1px solid var(--el-border-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
}

.workflow-detail__multi-instance-heading,
.workflow-detail__multi-instance-title-row,
.workflow-detail__multi-instance-tools {
  display: flex;
  align-items: center;
}

.workflow-detail__multi-instance-heading {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.workflow-detail__multi-instance-title-row {
  flex-wrap: wrap;
  gap: 8px;
}

.workflow-detail__multi-instance-title-row h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.workflow-detail__multi-instance-heading > div > span,
.workflow-detail__member-name span,
.workflow-detail__remove-target span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-detail__multi-instance-tools {
  flex: 0 0 auto;
  gap: 8px;
}

.workflow-detail__multi-instance-error {
  margin-bottom: 12px;
}

.workflow-detail__member-name,
.workflow-detail__remove-target {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.workflow-detail__member-name strong,
.workflow-detail__remove-target strong,
.workflow-detail__remove-target span {
  overflow-wrap: anywhere;
}

.workflow-detail__user-select {
  width: 100%;
}

.workflow-detail__section,
.workflow-detail__history-form {
  max-width: 1080px;
  padding: 8px 4px 28px;
}

.workflow-detail__history-form + .workflow-detail__history-form {
  padding-top: 24px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.workflow-detail__section-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.workflow-detail__section-title h3 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
}

.workflow-detail__section-title span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-detail__timeline {
  max-width: 1080px;
  padding: 14px 8px 20px;
}

.workflow-detail__timeline-node {
  padding: 2px 0 10px;
}

.workflow-detail__timeline-heading {
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.workflow-detail__comments {
  margin-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.workflow-detail__comment {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  gap: 10px;
  padding: 10px 0;
}

.workflow-detail__comment + .workflow-detail__comment {
  border-top: 1px dashed var(--el-border-color-lighter);
}

.workflow-detail__comment-message {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.workflow-detail__comment-time,
.workflow-detail__delete-reason {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-detail__delete-reason {
  margin-top: 8px;
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .workflow-detail__header {
    align-items: flex-start;
    flex-direction: column;
  }

  .workflow-detail__actions {
    width: 100%;
    justify-content: flex-start;
  }
}

@media (max-width: 600px) {
  .workflow-detail__multi-instance-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .workflow-detail__multi-instance-tools {
    width: 100%;
    justify-content: space-between;
  }

  .workflow-detail__comment {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .workflow-detail__comment-time {
    grid-column: 2;
  }
}
</style>
