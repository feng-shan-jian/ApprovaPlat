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
      <div v-if="ready && !loading && (hasAnyTaskAction || canUrge)" class="workflow-detail__actions">
        <el-button v-if="canUrge" icon="Bell" :loading="urgeBusy" @click="openUrgeDialog">催办</el-button>
        <el-button
          v-if="multiInstanceState?.addAllowed === true && proxy.$auth.hasPermi('workflow:process:approval')"
          icon="Plus"
          :disabled="multiInstanceBusy || multiInstanceDialogClosing || actionBusy"
          @click="openMultiInstanceDialog('ADD')"
        >加签</el-button>
        <el-button v-if="canUnclaim" :loading="actionBusy" @click="confirmUnclaim">取消认领</el-button>
        <el-button v-if="canResolve" type="primary" :loading="actionBusy" @click="openActionDialog('resolve')">完成委派</el-button>
        <el-button v-if="canDelegate" @click="openActionDialog('delegate')">委派</el-button>
        <el-button v-if="canTransfer" @click="openActionDialog('transfer')">转办</el-button>
        <el-button v-if="canReturnTask" type="danger" plain @click="openActionDialog('return')">退回</el-button>
        <el-button v-if="canReject" type="danger" plain @click="openActionDialog('reject')">驳回</el-button>
        <el-button v-if="canResubmit" type="primary" :loading="actionBusy" @click="confirmResubmit">重新提交</el-button>
        <el-button v-if="canComplete" type="primary" @click="openActionDialog('complete')">通过</el-button>
      </div>
    </div>

    <el-descriptions v-if="ready" class="workflow-detail__summary" :column="summaryColumns" border>
      <el-descriptions-item label="流程实例">{{ detail.processInstanceId }}</el-descriptions-item>
      <el-descriptions-item label="发起人">{{ detail.startUserName || detail.startUserId || '-' }}</el-descriptions-item>
      <el-descriptions-item label="发起时间">{{ formatDate(detail.startTime) }}</el-descriptions-item>
      <el-descriptions-item label="结束时间">{{ formatDate(detail.endTime) }}</el-descriptions-item>
      <el-descriptions-item label="当前任务">{{ detail.currentTask?.active ? detail.currentTask.taskName : currentActivityNames || '-' }}</el-descriptions-item>
      <el-descriptions-item label="流程耗时">{{ formatDuration(detail.durationMillis) }}</el-descriptions-item>
    </el-descriptions>

    <el-alert v-if="observationError" :title="observationError" type="warning" :closable="false" />
    <ProcessWaitingEvents v-if="ready" :process-instance-id="detail.processInstanceId" :snapshot="waitingSnapshot" :activity-instances="detail.flowViewer?.activityInstances || []" :loading="observationBusy" @refresh="loadDetail(false, true)" />

    <section v-if="processRelations.length > 1" class="workflow-detail__relations" aria-labelledby="process-relations-title">
      <div class="workflow-detail__relations-heading">
        <div>
          <h3 id="process-relations-title">父子流程关系</h3>
          <span>关系与实际运行版本来自 Flowable 历史实例执行树。</span>
        </div>
        <el-tag size="small" type="info">{{ processRelations.length }} 个实例</el-tag>
      </div>
      <el-table :data="processRelations" row-key="processInstanceId" size="small" max-height="320">
        <el-table-column label="关系" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.current ? 'primary' : 'info'">
              {{ processRelationLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="流程" min-width="210">
          <template #default="{ row }">
            <div class="workflow-detail__relation-name">
              <strong>{{ row.processName || row.processKey }}</strong>
              <span>{{ row.processKey }} · v{{ row.version }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="processInstanceId" label="实例" min-width="190" show-overflow-tooltip />
        <el-table-column prop="parentProcessInstanceId" label="父实例" min-width="190" show-overflow-tooltip>
          <template #default="{ row }">{{ row.parentProcessInstanceId || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="processStatusMeta(row.processStatus).type">
              {{ processStatusMeta(row.processStatus).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" min-width="176">
          <template #default="{ row }">{{ formatDate(row.startTime) }}</template>
        </el-table-column>
      </el-table>
    </section>

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

    <section v-if="ready && multiInstanceState" class="workflow-detail__multi-instance" aria-labelledby="multi-instance-title">
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
            v-if="multiInstanceState?.addAllowed === true && proxy.$auth.hasPermi('workflow:process:approval')"
            type="primary"
            icon="Plus"
            :disabled="multiInstanceBusy || multiInstanceDialogClosing || actionBusy"
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
      <el-tab-pane v-if="applicationForm" :label="applicationFormTabLabel" name="applicationForm">
        <div class="workflow-detail__section">
          <div class="workflow-detail__section-title">
            <div>
              <h3>{{ applicationForm.formName || '申请表单' }}</h3>
              <span>{{ applicationForm.nodeName || applicationForm.nodeKey || '开始节点' }}</span>
            </div>
            <el-tag v-if="!canResubmit" type="info">只读</el-tag>
          </div>
          <ProcessFormRenderer
            v-if="canResubmit"
            ref="taskFormRef"
            v-model="taskFormValues"
            :content="applicationForm.content"
            :readonly="false"
            @error="showComponentError"
          />
          <ProcessFormRenderer
            v-else
            :content="applicationForm.content"
            :model-value="applicationFormValues"
            readonly
            @error="showComponentError"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane v-if="nodeTaskForm" label="本节点办理表单" name="nodeTaskForm">
        <div class="workflow-detail__section">
          <div class="workflow-detail__section-title">
            <div>
              <h3>{{ nodeTaskForm.formName || nodeTaskForm.nodeName || '本节点办理表单' }}</h3>
              <span>{{ nodeTaskForm.nodeName || detail.currentTask?.taskName }}</span>
            </div>
            <el-tag v-if="!canComplete" type="info">只读</el-tag>
          </div>
          <ProcessFormRenderer
            ref="taskFormRef"
            v-model="taskFormValues"
            :content="nodeTaskForm.content"
            :readonly="!canComplete"
            @error="showComponentError"
          />
        </div>
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
              :model-value="form.values || {}"
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
          :required="isNextUserSelectionRequired"
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
            :multiple-limit="remainingMultiInstanceCapacity"
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

    <el-dialog
      v-model="urgeDialog.visible"
      title="催办当前审批"
      width="min(480px, calc(100vw - 32px))"
      append-to-body
      :close-on-click-modal="!urgeBusy"
      :close-on-press-escape="!urgeBusy"
    >
      <el-form label-position="top">
        <el-form-item label="催办原因" required>
          <el-input
            v-model="urgeDialog.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="请输入需要当前办理人关注的业务原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="urgeBusy" @click="urgeDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="urgeBusy" @click="submitUrge">发送催办</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowProcessDetail">
import { getProcessObservation, getProcessRuntime } from '@/api/workflow/process'
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
import { urgeWorkflow } from '@/api/workflow/notification'
import { listApprovalUserOptions, listIdentityOptions } from '@/api/workflow/identity'
import ProcessFormRenderer from '@/components/workflow/ProcessFormRenderer.vue'
import ProcessViewer from '@/components/workflow/ProcessViewer.vue'
import ProcessWaitingEvents from '@/components/workflow/ProcessWaitingEvents.vue'
import { useWorkflowUserOptions } from '@/components/workflow/useWorkflowUserOptions.js'
import { processStatusMeta } from '@/components/workflow/workflowProcessStatus'
import { useWindowSize } from '@vueuse/core'
import { useWorkflowDetailObservation } from './useWorkflowDetailObservation.js'

const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()
const { width: viewportWidth } = useWindowSize()
const actionBusy = ref(false)
// 催办只记录通知与独立审计，不复用任务办理锁或伪装成审批状态动作。
const urgeBusy = ref(false)
const activeTab = ref('applicationForm')
const detail = reactive({})
const waitingSnapshot = ref(null)
const currentActivityNames = computed(() => [...new Set((detail.flowViewer?.activityInstances || [])
  .filter(activity => activity.state === 'WAITING').map(activity => activity.activityName || activity.activityId))].join('、'))
const taskFormValues = ref({})
// 申请表单和本节点表单必须保持独立语义；开始快照绝不进入历史节点列表。
const applicationForm = computed(() => detail.formDetail?.applicationForm || null)
const applicationFormValues = ref({})
const nodeTaskForm = computed(() => detail.formDetail?.nodeTaskForm || null)
const historyForms = computed(() => detail.formDetail?.historyForms || [])
const taskFormRef = ref(null)
const actionFormRef = ref(null)
// 审批办理对象与普通抄送对象使用独立目录状态，禁止通用启用用户混入任务分配请求。
// 动态多实例动作锁、状态刷新锁和稳定错误分别约束重复提交、快照覆盖及页面失败回显。
const multiInstanceBusy = ref(false)
const multiInstanceRefreshing = ref(false)
const multiInstanceError = ref('')
// 关闭期锁覆盖 Element Plus leave 至 closed 的完整区间，禁止旧弹窗回调清空下一轮真实业务草稿。
const multiInstanceDialogClosing = ref(false)
let multiInstanceRefreshSequence = 0
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

// 催办弹窗只持有原因；流程主键始终在提交时从当前授权详情重新读取。
const urgeDialog = reactive({ visible: false, reason: '' })

const { options: approvalUserOptions, loading: approvalUserLoading,
  search: searchApprovalUsers, reset: resetApprovalUsers } = useWorkflowUserOptions(
  listApprovalUserOptions, selectedApprovalUserValues)
const { options: copyUserOptions, loading: copyUserLoading,
  search: searchCopyUsers, reset: resetCopyUsers } = useWorkflowUserOptions(
  query => listIdentityOptions({ type: 'user', ...query }), () => actionDialog.copyUserIds)
const { options: multiInstanceUserOptions, loading: multiInstanceUserLoading,
  search: searchMultiInstanceUsers, reset: resetMultiInstanceUsers } = useWorkflowUserOptions(
  listApprovalUserOptions, () => multiInstanceDialog.userIds)


// 将服务端稳定流程状态映射为用户可见标签和 Element Plus 语义色。
const statusMeta = computed(() => processStatusMeta(detail.processStatus))
const summaryColumns = computed(() => viewportWidth.value < 768 ? 1 : 3)
const timeline = computed(() => Array.isArray(detail.historyProcNodeList) ? detail.historyProcNodeList : [])
// 父子关系仅信任详情 API 从 Flowable 历史执行树投影的结果，页面不从任务或 BPMN 猜测关系。
const processRelations = computed(() => Array.isArray(detail.processRelations) ? detail.processRelations : [])
// 受控循环状态完全采用详情 API 的部署快照和逐轮审计，页面不根据历史任务数量自行推演。
const controlledLoopStates = computed(() => Array.isArray(detail.controlledLoopStates)
  ? detail.controlledLoopStates
  : [])
// 循环审计以 taskId 对齐正式历史任务显示名称；历史投影缺失时才回退到稳定用户主键。
const controlledLoopActorNames = computed(() => new Map(timeline.value
  .filter(node => node?.taskId)
  .map(node => [String(node.taskId), node.completedByName || node.assigneeName || ''])))

/**
 * 生成父子流程关系节点的用户可见角色。
 * @param {object} relation Flowable 历史实例关系节点。
 * @returns {string} 当前流程、根流程、直接父流程或子流程。
 */
function processRelationLabel(relation) {
  if (relation?.current) return '当前流程'
  if (!relation?.parentProcessInstanceId) return '根流程'
  const current = processRelations.value.find(item => item.current)
  if (current?.parentProcessInstanceId === relation.processInstanceId) return '直接父流程'
  return '子流程'
}

const diagramFileName = computed(() => `workflow_${safeFileName(detail.processKey || processInstanceId() || 'process')}`)
// 动态多实例状态完全来自详情 API 的正式快照；revision、成员状态和计数不在浏览器自行推演。
const multiInstanceState = computed(() => detail.multiInstanceState || null)
const multiInstanceMembers = computed(() => Array.isArray(multiInstanceState.value?.members)
  ? multiInstanceState.value.members
  : [])
const activeMultiInstanceCount = computed(() => multiInstanceMembers.value.filter(member => member.active).length)
const completedMultiInstanceCount = computed(() => multiInstanceMembers.value.length - activeMultiInstanceCount.value)
// 成员 ID 集合仅用于提前提示重复加签；后端仍会在同一事务内重新校验身份、revision 和活动 execution。
const multiInstanceMemberIds = computed(() => new Set(multiInstanceMembers.value.map(member => String(member.userId))))
// 新增成员容量直接消费详情 API 的正式快照，后端在写事务内执行最终容量校验。
const remainingMultiInstanceCapacity = computed(() => multiInstanceState.value?.remainingCapacity)
// 下一办理人显示、必填和模式完全采用详情 API 的部署模型策略，页面不自行解析 BPMN 或试错探测。
const nextUserAssignmentPolicy = computed(() => String(detail.nextUserAssignmentPolicy || 'DISABLED'))
const nextUserSelectionEnabled = computed(() => nextUserAssignmentPolicy.value !== 'DISABLED')
const isNextUserSelectionRequired = computed(() => nextUserAssignmentPolicy.value.startsWith('REQUIRED_'))
const nextUserAssignmentMode = computed(() => ({
  REQUIRED_ALL: 'ALL',
  REQUIRED_ANY: 'ANY'
}[nextUserAssignmentPolicy.value] || null))
const nextUserSelectionLabel = computed(() => ({
  ALL: '会签办理人',
  ANY: '或签办理人'
}[nextUserAssignmentMode.value] || '下一办理人'))
const nextUserSelectionPlaceholder = computed(() => isNextUserSelectionRequired.value
  ? `请选择${nextUserSelectionLabel.value}`
  : '选择下一办理人（可选）')
// 任务动作只消费详情 API 的正式 capability；前端仅保留对应页面入口权限。
const canComplete = computed(() => detail.completeAllowed === true
  && proxy.$auth.hasPermi('workflow:process:approval'))
const canReject = computed(() => detail.rejectAllowed === true
  && proxy.$auth.hasPermi('workflow:process:approval'))
// 重提业务能力只消费详情 API 的服务端快照，前端仅叠加入口权限。
const canResubmit = computed(() => detail.resubmitAllowed === true
  && proxy.$auth.hasPermi('workflow:process:start'))
const applicationFormTabLabel = computed(() => canResubmit.value
  ? '修改申请表单'
  : '申请表单')
const canReturnTask = computed(() => detail.returnAllowed === true
  && proxy.$auth.hasPermi('workflow:process:approval'))
const canDelegate = computed(() => detail.delegateAllowed === true
  && proxy.$auth.hasPermi('workflow:process:approval'))
const canTransfer = computed(() => detail.transferAllowed === true
  && proxy.$auth.hasPermi('workflow:process:approval'))
// 动态成员调整只消费服务端正式能力，前端仅叠加审批入口权限。
const canAdjustMultiInstance = computed(() => multiInstanceState.value?.adjustmentAllowed === true
  && proxy.$auth.hasPermi('workflow:process:approval'))
// 取消认领业务能力只消费详情 API 的服务端快照，前端仅叠加入口权限。
const canUnclaim = computed(() => detail.currentTask?.unclaimAllowed === true
  && proxy.$auth.hasPermi('workflow:process:claim'))
const canResolve = computed(() => detail.resolveAllowed === true
  && proxy.$auth.hasPermi('workflow:process:approval'))
// 人工催办业务能力只消费详情 API 的服务端快照，前端仅叠加基础入口权限。
const canUrge = computed(() => detail.urgeAllowed === true
  && proxy.$auth.hasPermi('workflow:notification:urge'))
const hasAnyTaskAction = computed(() => canComplete.value || canReject.value || canResubmit.value
  || canReturnTask.value || canDelegate.value || canTransfer.value || canUnclaim.value || canResolve.value)
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
 * 从详情页唯一命名路由参数中读取流程实例主键。
 * @returns {string} 去除首尾空白后的流程实例主键。
 */
function processInstanceId() {
  return String(route.params.instanceId || '').trim()
}

/**
 * 从路由中读取可选任务主键，后端会再次核验任务与实例关系。
 * @returns {string} 去除首尾空白后的任务主键，未传入时为空字符串。
 */
function routeTaskId() {
  return String(route.query.taskId || '').trim()
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
    detailSequence: observation.detailSequence
  })
}

/**
 * 核对冻结上下文仍对应当前路由和最新授权详情，淘汰跨路由或刷新后晚到的异步结果。
 * @param {{processInstanceId: string, taskId: string, detailSequence: number}|null} context 动作开始时冻结的任务上下文。
 * @returns {boolean} 路由、详情、活动任务和加载序号仍完全一致时返回 true。
 */
function isCurrentTaskContext(context) {
  if (!context || context.detailSequence !== observation.detailSequence || !ready.value || loading.value) return false
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
 * 清空当前详情及其临时动作状态；并发刷新可暂存动态调整草稿，但不会保留旧权限结论。
 * @param {boolean} preserveMultiInstanceDraft 是否暂存动态调整输入等待新详情重新校验。
 * @returns {void} 无返回值。
 */
function clearDetailState(preserveMultiInstanceDraft = false) {
  Object.keys(detail).forEach(key => delete detail[key])
  taskFormValues.value = {}
  applicationFormValues.value = {}
  activeTab.value = 'applicationForm'
  actionDialog.visible = false
  resetActionDialog()
  if (!preserveMultiInstanceDraft) {
    // 详情重载只触发关闭；动作、目标和目录缓存必须等 closed 后再清理，避免关闭动画中改写弹窗身份。
    multiInstanceDialog.visible = false
    multiInstanceError.value = ''
  }
}

/** 完整事实仅重建已切换任务的表单；同任务背景复核保留输入与页签。 */
function commitObservedDetail({ detail: payload, waiting }, { background, preserveMultiInstanceDraft }) {
  const sameTask = background && detail.processInstanceId === payload.processInstanceId
    && detail.currentTask?.taskId === payload.currentTask?.taskId
    && detail.currentTask?.active === payload.currentTask?.active
    && detail.currentTask?.assignee === payload.currentTask?.assignee
  const formDetail = payload.formDetail
  const applicationValues = cloneJson(formDetail.applicationForm?.values || {})
  if (!sameTask) {
    clearDetailState(preserveMultiInstanceDraft)
    taskFormValues.value = formDetail.nodeTaskForm
      ? cloneJson(formDetail.nodeTaskForm.values || {})
      : formDetail.returnedApplication ? applicationValues : {}
    applicationFormValues.value = applicationValues
    activeTab.value = formDetail.nodeTaskForm ? 'nodeTaskForm' : 'applicationForm'
  }
  Object.assign(detail, payload)
  waitingSnapshot.value = waiting
  if (preserveMultiInstanceDraft) reconcileMultiInstanceDraft()
}

/** 观察失效同步淘汰动作恢复的旧结果；MI revision 读取保留独立动作序号。 */
function invalidateObservedDetail(reason, preserveMultiInstanceDraft = false) {
  if (reason === 'reload') {
    clearDetailState(preserveMultiInstanceDraft)
    return
  }
  multiInstanceRefreshSequence += 1
  multiInstanceRefreshing.value = false
  if (reason === 'denied') {
    clearDetailState()
    waitingSnapshot.value = null
  }
  actionDialog.visible = false
  multiInstanceDialog.visible = false
  urgeDialog.visible = false
}

const observation = useWorkflowDetailObservation({
  identity: () => ({ processInstanceId: processInstanceId(), taskId: routeTaskId() }),
  blocked: () => actionBusy.value || multiInstanceBusy.value || multiInstanceRefreshing.value || urgeBusy.value
    || actionDialog.visible || multiInstanceDialog.visible || urgeDialog.visible,
  requests: { detail: getProcessObservation, runtime: getProcessRuntime },
  facts: () => ({ detail, waiting: waitingSnapshot.value }),
  commitDetail: commitObservedDetail,
  commitRuntime: ({ runtime, waiting }) => {
    Object.assign(detail, runtime)
    waitingSnapshot.value = waiting
  },
  invalidate: invalidateObservedDetail,
  reportError: showComponentError
})
const { loading, ready, observationError, observationBusy, loadDetail } = observation

document.addEventListener('visibilitychange', observation.visibilityChanged)
onDeactivated(observation.deactivate)
onBeforeUnmount(() => {
  observation.deactivate()
  document.removeEventListener('visibilitychange', observation.visibilityChanged)
})

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
 * 校验并覆盖保存当前原申请表单，不要求申请人填写任何审批意见。
 * @returns {Promise<void>} 用户确认且后端同事务恢复首审批配置后返回来源列表。
 */
async function confirmResubmit() {
  if (!canResubmit.value || actionBusy.value || multiInstanceBusy.value) {
    return denyAction('当前申请不允许重新提交')
  }
  const taskContext = freezeCurrentTaskContext()
  if (!isCurrentTaskContext(taskContext)) return denyAction('当前任务状态已变化，请刷新后重试')
  if (!applicationForm.value || !taskFormRef.value) return denyAction('原申请表单尚未就绪')

  actionBusy.value = true
  try {
    const valid = await taskFormRef.value.validate().catch(error => {
      showComponentError(error)
      return false
    })
    if (!valid) return
    const variables = taskFormRef.value.getValues()
    await proxy.$modal.confirm('确认使用当前表单内容重新提交吗？')
    // 表单校验和用户确认均为异步步骤，写入前必须再次确认任务上下文和服务端能力快照仍然有效。
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
 * 成员区手动刷新重新授权并复核完整详情，保留同任务输入。
 * @returns {Promise<void>} 查询成功后替换成员快照；失败时保留原快照并展示错误。
 */
async function refreshMultiInstanceState() {
  await loadDetail(false, true)
}

/**
 * 完成冲突后读取新修订；只返回仍绑定当前任务且未过期的快照。
 * @param {string} taskId 发生完成冲突的 Flowable 活动任务主键。
 * @returns {Promise<{state: object|null, error: unknown|null}>} 成功返回规范化状态，失败返回原始请求错误。
 */
async function requestMultiInstanceState(taskId) {
  const normalizedTaskId = String(taskId || '').trim()
  if (!normalizedTaskId || normalizedTaskId.length > 64) {
    return { state: null, error: new Error('当前任务主键不合法') }
  }
  // 路由、隐藏和失权递增同一序号，旧修订不能恢复已失效的动作。
  const requestSequence = ++multiInstanceRefreshSequence
  multiInstanceRefreshing.value = true
  try {
    const response = await getMultiInstanceState(normalizedTaskId)
    const state = response.data
    if (requestSequence === multiInstanceRefreshSequence
      && String(detail.currentTask?.taskId || '') === normalizedTaskId) {
      return { state, error: null }
    }
    return { state: null, error: null }
  } catch (error) {
    if (requestSequence === multiInstanceRefreshSequence && [403, 404].includes(Number(error?.code))) observation.failed(error)
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
  const result = await requestMultiInstanceState(taskId)
  if ((!result.state && !result.error) || [403, 404].includes(Number(result.error?.code))) return
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
    await adjustMultiInstance(request)
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
 * 校验动态调整所需的服务端能力、成员目标和用户输入格式。
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
  if (action === 'ADD' && multiInstanceState.value.addAllowed !== true) {
    return denyAction('当前任务不允许增加会签成员')
  }
  if (action === 'ADD' && remainingMultiInstanceCapacity.value < 1) {
    return denyAction('会签成员已无剩余容量')
  }
  if (member) {
    const currentMember = multiInstanceMembers.value.find(item => item.activeTaskId === member.activeTaskId)
    if (currentMember?.removable !== true) return denyAction('该成员当前不能减签')
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
  if (target?.removable !== true) return denyAction('减签目标状态已变化，请刷新后重试')
  return true
}

/**
 * 判断统一请求错误的数字业务码是否为 409 状态冲突。
 * @param {unknown} error 统一请求拦截器返回值。
 * @returns {boolean} error.code 严格等于数字 409 时返回 true。
 */
function isConflictResponse(error) {
  return error?.code === 409
}

/**
 * 判断失败是否为服务端明确标识的动态多实例 revision 冲突。
 * @param {unknown} error 统一 BusinessError。
 * @returns {boolean} 业务码为 409 且 subCode 精确匹配冻结值时返回 true。
 */
function isMultiInstanceRevisionConflict(error) {
  return isConflictResponse(error) && error?.subCode === MULTI_INSTANCE_REVISION_CONFLICT_SUBCODE
}

/**
 * 判断冲突后的强制查询是否说明原活动任务已不能继续办理。
 * @param {unknown} error getMultiInstanceState 返回的统一业务错误。
 * @returns {boolean} 403/404/409 表示对象权限、存在性或活动状态已经变化。
 */
function isTaskUnavailableResponse(error) {
  return [403, 404, 409].includes(error?.code)
}

/**
 * 从请求错误中提取不含敏感响应正文的稳定用户提示。
 * @param {unknown} error 错误或普通 Error。
 * @param {string} fallback 无可用消息时的兜底文案。
 * @returns {string} 可展示的短错误消息。
 */
function requestErrorMessage(error, fallback) {
  const errorMessage = typeof error?.message === 'string' ? error.message.trim() : ''
  return (errorMessage || fallback).slice(0, 200)
}

/**
 * 详情并发刷新后按最新成员快照复核弹窗草稿，只保留仍可合法重试的输入。
 * @returns {void} 草稿合法时保持弹窗；加签能力或调整目标失效时关闭并显示稳定提示。
 */
function reconcileMultiInstanceDraft() {
  if (!multiInstanceDialog.visible) return
  if (multiInstanceDialog.action === 'ADD' && multiInstanceState.value?.addAllowed !== true) {
    multiInstanceDialog.visible = false
    resetMultiInstanceDialog()
    multiInstanceError.value = '当前任务状态已变化，不能继续增加会签成员'
    return
  }
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
    resetMultiInstanceUsers({ keepSelected: true })
    if (retainedIds.length !== originalIds.length) {
      multiInstanceDialog.error = '部分成员已被其他操作加入或达到人数上限，请重新核对'
    }
    return
  }
  const target = multiInstanceMembers.value.find(
    member => member.activeTaskId === multiInstanceDialog.targetTaskId)
  if (target?.removable !== true) {
    multiInstanceDialog.error = '减签目标状态已变化，请选择最新可移除成员'
  }
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
    if (type === 'complete' && nodeTaskForm.value) {
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
  if (!['complete', 'reject', 'return', 'delegate', 'resolve', 'transfer'].includes(type)) {
    return denyAction('任务动作不合法')
  }
  if (!ready.value || loading.value) return denyAction('流程详情尚未就绪')
  if (!proxy.$auth.hasPermi('workflow:process:approval')) return denyAction('没有执行该操作的权限')
  if (actionBusy.value || multiInstanceBusy.value) return denyAction('任务正在处理中，请勿重复提交')
  if (!detail.currentTask.taskId || String(detail.currentTask.taskId).length > 64) return denyAction('当前任务主键不合法')
  const capabilityAllowed = ({
    complete: canComplete.value,
    reject: canReject.value,
    return: canReturnTask.value,
    delegate: canDelegate.value,
    transfer: canTransfer.value,
    resolve: canResolve.value
  })[type] === true
  if (!capabilityAllowed) return denyAction('当前任务不允许执行该操作')
  if (!validateInput) return true
  const comment = actionDialog.comment.trim()
  if (!comment || comment.length > 500) return denyAction('办理意见不能为空且不能超过500个字符')
  if (type === 'complete') {
    const revision = actionDialog.expectedRevision
    const hasDynamicState = Boolean(multiInstanceState.value)
    if (hasDynamicState !== (revision !== null)) {
      return denyAction('会签成员状态已变化，请关闭办理窗口后重试')
    }
  }
  if (['delegate', 'transfer'].includes(type)) {
    if (!positiveUserId(actionDialog.userId)) return denyAction('请选择有效的目标用户')
  }
  if (supportsCopyAction.value
    && !validSelectedUsers(actionDialog.copyUserIds, '抄送人')) return false
  if (type === 'complete' && isNextUserSelectionRequired.value && actionDialog.nextUserIds.length === 0) {
    return denyAction(`${nextUserSelectionLabel.value}不能为空`)
  }
  if (type === 'complete' && !nextUserSelectionEnabled.value && actionDialog.nextUserIds.length > 0) {
    return denyAction('当前任务不允许指定下一办理人')
  }
  if (type === 'complete' && nextUserSelectionEnabled.value
    && !validSelectedUsers(actionDialog.nextUserIds, nextUserSelectionLabel.value)) return false
  return true
}

/**
 * 校验多选用户数量、唯一性和主键格式；最终业务资格由后端重新解析。
 * @param {unknown} values 多选组件当前用户主键集合。
 * @param {string} fieldLabel 用于稳定错误提示的字段名称。
 * @returns {boolean} 集合可安全转换并提交时返回 true。
 */
function validSelectedUsers(values, fieldLabel) {
  if (!Array.isArray(values)) return denyAction(`${fieldLabel}选择不合法`)
  if (values.length > 100) return denyAction(`${fieldLabel}不能超过100人`)
  const normalizedIds = values.map(String)
  if (new Set(normalizedIds).size !== normalizedIds.length) return denyAction(`${fieldLabel}不能重复选择`)
  if (values.some(value => !positiveUserId(value))) return denyAction(`${fieldLabel}包含非法用户主键`)
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
  resetApprovalUsers()
  resetCopyUsers()
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
  resetMultiInstanceUsers()
}

/**
 * 打开人工催办弹窗并清除上一流程遗留原因。
 * @returns {void} 无返回值。
 */
function openUrgeDialog() {
  if (!canUrge.value) return denyAction('当前流程不允许催办')
  urgeDialog.reason = ''
  urgeDialog.visible = true
}

/**
 * 向真实催办 API 提交当前流程与原因，成功后刷新详情确认流程状态未被改变。
 * @returns {Promise<void>} 完成后关闭弹窗并回显服务端接收人数。
 */
async function submitUrge() {
  const reason = urgeDialog.reason.trim()
  if (!reason || reason.length > 500) return denyAction('催办原因不能为空且不能超过500个字符')
  const instanceId = String(detail.processInstanceId || '').trim()
  if (!canUrge.value || !instanceId || instanceId !== processInstanceId()) {
    return denyAction('流程状态已变化，请刷新后重试')
  }
  urgeBusy.value = true
  try {
    const response = await urgeWorkflow({ processInstanceId: instanceId, reason })
    urgeDialog.visible = false
    proxy.$modal.msgSuccess(`催办已发送给 ${Number(response.data?.recipientCount || 0)} 名当前办理人`)
    await loadDetail()
  } finally {
    urgeBusy.value = false
  }
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
  if (node.comments?.some(comment => ['REJECT', 'TERMINATE'].includes(comment.type))) return 'danger'
  if (node.comments?.some(comment => comment.type === 'RETURN')) return 'warning'
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
  if (['COMPLETE', 'RESUBMIT', 'RESOLVE'].includes(type)) return 'success'
  if (type === 'RETURN') return 'warning'
  if (['REJECT', 'TERMINATE'].includes(type)) return 'danger'
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

watch(() => route.fullPath, () => {
  if (route.name === 'WorkflowProcessDetail') observation.routeChanged()
})
observation.start()
onActivated(observation.activate)
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

.workflow-detail__relations {
  margin-top: 18px;
  padding: 16px 0 18px;
  border-top: 1px solid var(--el-border-color-light);
  border-bottom: 1px solid var(--el-border-color-light);
}

.workflow-detail__relations-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.workflow-detail__relations-heading h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.workflow-detail__relations-heading span,
.workflow-detail__relation-name span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.workflow-detail__relation-name {
  display: grid;
  gap: 3px;
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
