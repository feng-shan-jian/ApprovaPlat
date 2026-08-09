<template>
  <aside class="designer-properties-panel">
    <div class="designer-properties-panel__title">
      <div class="designer-properties-panel__heading">
        <span class="designer-properties-panel__eyebrow">属性检查器</span>
        <strong>{{ title }}</strong>
      </div>
      <div class="designer-properties-panel__actions">
        <el-tooltip content="展开全部分区" placement="bottom">
          <el-button text circle icon="ArrowDownBold" aria-label="展开全部属性分区" @click="expandAllSections" />
        </el-tooltip>
        <el-tooltip content="收起全部分区" placement="bottom">
          <el-button text circle icon="ArrowUpBold" aria-label="收起全部属性分区" @click="collapseAllSections" />
        </el-tooltip>
        <el-tooltip content="收起属性面板" placement="bottom">
          <el-button text circle icon="Close" aria-label="收起属性面板" @click="emit('close')" />
        </el-tooltip>
      </div>
    </div>

    <div v-if="selected" class="designer-properties-panel__context" :title="state.id">
      <span class="designer-properties-panel__context-dot" />
      <span>当前元素</span>
      <code>{{ state.id || '未命名元素' }}</code>
    </div>

    <el-scrollbar v-if="selected" class="designer-properties-panel__scroll">
      <el-form label-position="top" size="small" class="designer-properties-panel__form">
        <el-collapse v-model="activeSections">
          <el-collapse-item title="基础信息" name="base">
            <el-form-item label="元素名称">
              <el-input v-model="state.name" maxlength="255" @change="emit('common-change')" />
            </el-form-item>
            <el-form-item label="元素标识">
              <el-input v-model="state.id" maxlength="128" @change="emit('id-change')" />
            </el-form-item>
            <el-form-item v-if="flags.process" label="版本标签">
              <el-input v-model="state.versionTag" maxlength="64" @change="emit('process-change')" />
            </el-form-item>
            <el-form-item v-if="flags.process" label="可执行流程">
              <el-switch v-model="state.executable" @change="emit('process-change')" />
            </el-form-item>
            <el-form-item v-if="flags.participant" label="绑定流程定义 key" required>
              <el-input v-model="state.processRef" maxlength="255" placeholder="必须是已存在且可执行的流程定义" @change="emit('participant-change')" />
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="state.documentation" type="textarea" :rows="3" maxlength="1000" @change="emit('documentation-change')" />
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item v-if="hasBusinessSection" title="业务配置" name="business">
            <template v-if="flags.formSupported">
              <el-form-item label="表单来源" required>
                <el-segmented v-model="state.formSource" :options="formSourceOptions" @change="emit('form-source-change')" />
              </el-form-item>
              <el-form-item
                v-if="state.formSource === 'TEMPLATE'"
                :label="flags.startEvent ? '发起表单' : '节点表单'"
                :required="flags.startEvent"
              >
                <el-select v-model="state.formKey" filterable clearable @change="emit('form-change')">
                  <el-option v-for="form in forms" :key="form.formId" :label="form.formName" :value="`key_${form.formId}`" />
                </el-select>
              </el-form-item>
              <EmbeddedFormFieldEditor
                v-else
                :fields="state.embeddedFields"
                :custom-field-options="formFieldOptions"
                :custom-field-loading="extensionLoading"
                @change="emit('embedded-form-change', $event)"
              />
            </template>

            <template v-if="flags.userTask">
              <el-form-item v-if="state.multiInstanceType === 'controlled'" label="签署规则">
                <el-segmented v-model="state.multiInstanceApprovalMode" :options="multiInstanceApprovalOptions" @change="emit('multi-instance-change')" />
              </el-form-item>
              <template v-if="state.multiInstanceType === 'controlled'">
                <el-form-item label="人员来源">
                  <el-segmented v-model="state.multiInstanceMemberSource" :options="multiInstanceMemberSourceOptions" @change="emit('multi-instance-change')" />
                </el-form-item>
                <el-form-item v-if="state.multiInstanceMemberSource === 'fixed'" label="固定办理人" required>
                  <el-select
                    v-model="state.fixedMultiInstanceUserIds"
                    multiple
                    filterable
                    remote
                    reserve-keyword
                    :remote-method="searchAssignees"
                    :loading="identityLoading"
                    placeholder="请选择会签或或签办理人"
                    @change="emit('multi-instance-change')"
                  >
                    <el-option v-for="user in identityOptions.assignees" :key="user.value" :label="user.label" :value="String(user.value)" />
                  </el-select>
                </el-form-item>
              </template>
              <template v-if="['sequential', 'parallel'].includes(state.multiInstanceType)">
                <el-form-item label="集合表达式">
                  <el-input v-model="state.collection" maxlength="256" @change="emit('multi-instance-change')" />
                </el-form-item>
                <el-form-item label="元素变量">
                  <el-input v-model="state.elementVariable" maxlength="128" @change="emit('multi-instance-change')" />
                </el-form-item>
                <el-form-item label="完成条件">
                  <el-input v-model="state.completionCondition" type="textarea" :rows="2" maxlength="512" @change="emit('multi-instance-change')" />
                </el-form-item>
              </template>
              <template v-if="state.multiInstanceType !== 'controlled'">
                <el-form-item label="办理方式">
                  <el-segmented v-model="state.assignmentType" :options="assignmentOptions" @change="emit('assignment-change')" />
                </el-form-item>
                <el-form-item v-if="state.assignmentType === 'assignee'" label="办理人">
                  <el-select v-model="state.assignee" filterable clearable remote reserve-keyword :remote-method="searchAssignees" :loading="identityLoading" @change="emit('assignment-change')">
                    <el-option v-for="user in identityOptions.assignees" :key="user.value" :label="user.label" :value="String(user.value)" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="state.assignmentType === 'users'" label="候选用户">
                  <el-select v-model="state.candidateUsers" multiple filterable remote reserve-keyword :remote-method="searchCandidateUsers" :loading="identityLoading" @change="emit('assignment-change')">
                    <el-option v-for="user in identityOptions.candidateUsers" :key="user.value" :label="user.label" :value="String(user.value)" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="state.assignmentType === 'groups'" label="候选角色或部门">
                  <el-select v-model="state.candidateGroups" multiple filterable remote reserve-keyword :remote-method="searchCandidateGroups" :loading="identityLoading" @change="emit('assignment-change')">
                    <el-option v-for="group in identityOptions.candidateGroups" :key="group.value" :label="group.label" :value="group.value" />
                  </el-select>
                </el-form-item>
              </template>
              <el-form-item label="到期时间">
                <el-input v-model="state.dueDate" maxlength="128" placeholder="ISO-8601 或受控表达式" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="优先级">
                <el-input v-model="state.priority" maxlength="128" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="任务分类">
                <el-input v-model="state.taskCategory" maxlength="128" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="跳过条件">
                <el-input v-model="state.skipExpression" maxlength="512" @change="emit('user-task-change')" />
              </el-form-item>
              <el-form-item label="任务局部变量">
                <el-switch v-model="state.localScope" @change="emit('user-task-change')" />
              </el-form-item>
              <UserTaskSlaEditor
                v-model="state.sla"
                :calendars="slaCalendarOptions"
                :escalation-options="escalationEventOptions"
                :assignee-options="identityOptions.assignees"
                :loading="slaLoading || eventCodeLoading"
                :identity-loading="identityLoading"
                @identity-search="searchSlaAssignees"
                @change="emit('sla-change', $event)"
              />
            </template>

            <template v-if="flags.serviceTaskLike">
              <el-form-item label="受控处理器" required>
                <el-select v-model="state.extensionKey" filterable :loading="extensionLoading" @change="emit('extension-selection-change')">
                  <el-option
                    v-for="option in extensionOptions"
                    :key="option.versionId"
                    :label="`${option.extensionName} · ${option.extensionType} · v${option.versionNo}`"
                    :value="option.extensionKey"
                  />
                </el-select>
              </el-form-item>
              <CollaborationMessageEditor
                v-if="selectedExtensionImplementation === 'COLLABORATION_OUTBOX_V1'"
                v-model="state.extensionConfig"
                :endpoints="connectorEndpoints"
                @change="emit('service-task-change')"
              />
              <CelExpressionEditor
                v-else-if="selectedExtensionType === 'CEL'"
                v-model="state.extensionConfig"
                @change="emit('service-task-change')"
              />
              <HttpConnectorEditor
                v-else-if="selectedExtensionType === 'HTTP'"
                v-model="state.extensionConfig"
                :endpoints="connectorEndpoints"
                @change="emit('service-task-change')"
              />
              <SqlConnectorEditor
                v-else-if="selectedExtensionType === 'SQL'"
                v-model="state.extensionConfig"
                :data-sources="sqlDataSources"
                @change="emit('service-task-change')"
              />
              <BpmnEventRaiseEditor
                v-else-if="selectedExtensionImplementation === 'RAISE_BPMN_EVENT'"
                v-model="state.extensionConfig"
                :error-options="errorEventOptions"
                :escalation-options="escalationEventOptions"
                @change="emit('service-task-change')"
              />
              <el-form-item v-else label="处理器配置" required>
                <el-input v-model="state.extensionConfig" type="textarea" :rows="5" maxlength="16384" @change="emit('service-task-change')" />
              </el-form-item>
            </template>

            <template v-if="flags.businessRuleTask">
              <el-form-item label="DMN 决策版本" required>
                <el-select v-model="state.dmnDecisionId" filterable :loading="dmnLoading" @change="emit('dmn-change')">
                  <el-option
                    v-for="decision in dmnOptions"
                    :key="decision.decisionId"
                    :label="`${decision.decisionName || decision.decisionKey} · ${decision.decisionKey} · v${decision.version}`"
                    :value="decision.decisionId"
                  />
                </el-select>
              </el-form-item>
            </template>

            <template v-if="flags.callActivity">
              <el-form-item label="被调用流程 key" required>
                <el-input v-model="state.calledElement" maxlength="255" @change="emit('call-activity-change')" />
              </el-form-item>
              <el-form-item label="业务键">
                <el-input v-model="state.businessKey" maxlength="255" @change="emit('call-activity-change')" />
              </el-form-item>
              <el-form-item label="实例名称">
                <el-input v-model="state.processInstanceName" maxlength="255" @change="emit('call-activity-change')" />
              </el-form-item>
            </template>

            <template v-if="flags.sequenceFlow">
              <el-form-item label="流转条件">
                <el-input v-model="state.conditionExpression" type="textarea" :rows="3" maxlength="1024" @change="emit('condition-change')" />
              </el-form-item>
            </template>

            <template v-if="flags.event">
              <el-form-item label="事件定义">
                <el-input :model-value="eventDefinitionLabel" readonly />
              </el-form-item>
              <el-form-item v-if="flags.businessReferenceEvent" label="业务编码" required>
                <el-select v-model="state.eventReference" filterable :loading="eventCodeLoading" @change="emit('event-change')">
                  <el-option
                    v-for="option in businessEventOptions"
                    :key="option.eventCodeId"
                    :label="`${option.eventName} · ${option.eventCode}`"
                    :value="option.eventCode"
                  />
                </el-select>
              </el-form-item>
              <el-form-item v-else-if="flags.referenceEvent" label="事件引用">
                <el-input v-model="state.eventReference" maxlength="128" placeholder="消息或信号的稳定 key" @change="emit('event-change')" />
              </el-form-item>
              <template v-if="flags.timerEvent">
                <el-form-item label="时间类型">
                  <el-select v-model="state.timerDefinitionType" @change="emit('event-change')">
                    <el-option label="指定时间" value="timeDate" />
                    <el-option label="持续时间" value="timeDuration" />
                    <el-option label="周期" value="timeCycle" />
                  </el-select>
                </el-form-item>
                <el-form-item label="时间表达式">
                  <el-input v-model="state.timerDefinition" maxlength="512" @change="emit('event-change')" />
                </el-form-item>
              </template>
              <el-form-item v-if="flags.boundaryEvent" label="中断附着活动">
                <el-switch
                  v-model="state.cancelActivity"
                  :disabled="state.eventDefinitionType === 'bpmn:ErrorEventDefinition'"
                  @change="emit('event-change')"
                />
                <div v-if="state.eventDefinitionType === 'bpmn:ErrorEventDefinition'" class="form-tip">
                  BPMN Error 固定中断当前活动；需要保留主路径时请使用非中断升级边界。
                </div>
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item v-if="flags.activity" title="执行配置" name="execution">
            <el-form-item label="循环方式">
              <el-select v-model="state.multiInstanceType" @change="handleLoopTypeChange">
                <el-option
                  v-for="option in activityLoopOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
            </el-form-item>
            <template v-if="state.multiInstanceType === 'standard'">
              <el-form-item label="最大循环次数">
                <el-input v-model="state.loopMaximum" maxlength="32" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="循环条件">
                <el-input v-model="state.loopCondition" type="textarea" :rows="2" maxlength="512" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="执行前检查条件">
                <el-switch v-model="state.testBefore" @change="emit('multi-instance-change')" />
              </el-form-item>
            </template>
            <template v-if="state.multiInstanceType === 'approvalLoop'">
              <el-alert
                type="info"
                show-icon
                :closable="false"
                title="任务首次正常进入；每次提交后按正式表单字段决定再次整改或退出。达到上限时拒绝继续整改，不会强制放行。配置完成后请点击应用。"
              />
              <el-form-item label="最大办理轮次" required>
                <el-input-number
                  v-model="state.controlledLoopMaxIterations"
                  :min="2"
                  :max="50"
                  controls-position="right"
                />
              </el-form-item>
              <el-form-item label="循环判断字段" required>
                <el-select
                  v-model="state.controlledLoopDecisionVariable"
                  filterable
                  placeholder="请选择当前节点表单字段"
                  @change="handleControlledLoopFieldChange"
                >
                  <el-option
                    v-for="field in controlledLoopFieldOptions"
                    :key="field.value"
                    :label="field.label"
                    :value="field.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="再次进入条件" required>
                <el-select
                  v-if="controlledLoopValueOptions.length"
                  v-model="state.controlledLoopRepeatValue"
                  filterable
                  :allow-create="!controlledLoopValueRestricted"
                  placeholder="字段等于此值时再次整改"
                >
                  <el-option v-for="item in controlledLoopValueOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
                <el-input
                  v-else
                  v-model="state.controlledLoopRepeatValue"
                  maxlength="128"
                  placeholder="字段等于此值时再次整改"
                />
              </el-form-item>
              <el-form-item label="退出条件" required>
                <el-select
                  v-if="controlledLoopValueOptions.length"
                  v-model="state.controlledLoopExitValue"
                  filterable
                  :allow-create="!controlledLoopValueRestricted"
                  placeholder="字段等于此值时退出循环"
                >
                  <el-option v-for="item in controlledLoopValueOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
                <el-input
                  v-else
                  v-model="state.controlledLoopExitValue"
                  maxlength="128"
                  placeholder="字段等于此值时退出循环"
                />
              </el-form-item>
              <el-alert
                v-if="!controlledLoopFieldOptions.length"
                type="warning"
                show-icon
                :closable="false"
                title="请先为当前用户任务配置包含判断字段的正式表单。"
              />
              <el-button
                type="primary"
                plain
                :disabled="!controlledLoopConfigurationReady"
                @click="emit('multi-instance-change')"
              >
                应用整改循环配置
              </el-button>
            </template>
            <template v-if="['sequential', 'parallel'].includes(state.multiInstanceType) && !flags.userTask">
              <el-form-item label="集合表达式">
                <el-input v-model="state.collection" maxlength="256" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="元素变量">
                <el-input v-model="state.elementVariable" maxlength="128" @change="emit('multi-instance-change')" />
              </el-form-item>
              <el-form-item label="完成条件">
                <el-input v-model="state.completionCondition" type="textarea" :rows="2" maxlength="512" @change="emit('multi-instance-change')" />
              </el-form-item>
            </template>
            <el-form-item label="进入前异步">
              <el-switch v-model="state.asyncBefore" @change="emit('activity-change')" />
            </el-form-item>
            <el-form-item label="离开后异步">
              <el-switch v-model="state.asyncAfter" @change="emit('activity-change')" />
            </el-form-item>
            <el-form-item label="排他作业">
              <el-switch v-model="state.exclusive" @change="emit('activity-change')" />
            </el-form-item>
            <el-form-item label="补偿活动">
              <el-switch v-model="state.forCompensation" @change="emit('activity-change')" />
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item v-if="flags.extensionPropertiesSupported" title="扩展属性" name="properties">
            <ExtensionPropertyEditor
              v-model="state.extensionProperties"
              @change="emit('extension-properties-change', $event)"
            />
          </el-collapse-item>

          <el-collapse-item v-if="flags.listenerSupported" title="业务监听器" name="listeners">
            <el-form-item label="执行监听器">
              <BusinessListenerEditor
                v-model="state.businessExecutionListeners"
                kind="EXECUTION"
                :options="listenerOptions"
                :loading="listenerLoading"
                @change="emit('business-execution-listener-change', $event)"
              />
            </el-form-item>
            <el-form-item v-if="flags.userTask" label="任务监听器">
              <BusinessListenerEditor
                v-model="state.businessTaskListeners"
                kind="TASK"
                :options="listenerOptions"
                :loading="listenerLoading"
                @change="emit('business-task-listener-change', $event)"
              />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
    </el-scrollbar>
    <el-empty v-else description="未选择流程元素" :image-size="64" />
  </aside>
</template>

<script setup name="DesignerPropertiesPanel">
import EmbeddedFormFieldEditor from './EmbeddedFormFieldEditor.vue'
import CelExpressionEditor from './CelExpressionEditor.vue'
import HttpConnectorEditor from './HttpConnectorEditor.vue'
import SqlConnectorEditor from './SqlConnectorEditor.vue'
import BpmnEventRaiseEditor from './BpmnEventRaiseEditor.vue'
import BusinessListenerEditor from './BusinessListenerEditor.vue'
import ExtensionPropertyEditor from './ExtensionPropertyEditor.vue'
import CollaborationMessageEditor from './CollaborationMessageEditor.vue'
import UserTaskSlaEditor from './UserTaskSlaEditor.vue'

const props = defineProps({
  selected: { type: Boolean, default: false },
  title: { type: String, default: '元素属性' },
  state: { type: Object, required: true },
  flags: { type: Object, required: true },
  forms: { type: Array, default: () => [] },
  identityOptions: { type: Object, default: () => ({ assignees: [], candidateUsers: [], candidateGroups: [] }) },
  identityLoading: { type: Boolean, default: false },
  assignmentOptions: { type: Array, default: () => [] },
  multiInstanceOptions: { type: Array, default: () => [] },
  multiInstanceApprovalOptions: { type: Array, default: () => [] },
  controlledLoopFieldOptions: { type: Array, default: () => [] },
  extensionOptions: { type: Array, default: () => [] },
  formFieldOptions: { type: Array, default: () => [] },
  connectorEndpoints: { type: Array, default: () => [] },
  sqlDataSources: { type: Array, default: () => [] },
  extensionLoading: { type: Boolean, default: false },
  dmnOptions: { type: Array, default: () => [] },
  dmnLoading: { type: Boolean, default: false },
  listenerOptions: { type: Array, default: () => [] },
  listenerLoading: { type: Boolean, default: false },
  errorEventOptions: { type: Array, default: () => [] },
  escalationEventOptions: { type: Array, default: () => [] },
  eventCodeLoading: { type: Boolean, default: false },
  slaCalendarOptions: { type: Array, default: () => [] },
  slaLoading: { type: Boolean, default: false }
})

const emit = defineEmits([
  'common-change', 'id-change', 'process-change', 'form-source-change', 'form-change',
  'embedded-form-change', 'assignment-change',
  'user-task-change', 'extension-selection-change', 'service-task-change', 'condition-change', 'documentation-change',
  'multi-instance-change', 'activity-change', 'call-activity-change', 'event-change', 'dmn-change',
  'identity-search', 'business-execution-listener-change', 'business-task-listener-change',
  'extension-properties-change', 'sla-change', 'close'
])

// 表单来源值与后端部署快照的 source_type 契约一致。
const formSourceOptions = Object.freeze([
  { label: '正式模板', value: 'TEMPLATE' },
  { label: '内嵌表单', value: 'EMBEDDED' }
])
// 会签和或签共用多实例语义，人员来源决定是否在前驱任务完成时要求动态选人。
const multiInstanceMemberSourceOptions = Object.freeze([
  { label: '动态选择', value: 'dynamic' },
  { label: '固定人员', value: 'fixed' }
])

const hasBusinessSection = computed(() => Object.values(props.flags).some(Boolean))
const activeSections = ref(['base', 'business'])
const availableSections = computed(() => [
  'base',
  ...(hasBusinessSection.value ? ['business'] : []),
  ...(props.flags.activity ? ['execution'] : []),
  ...(props.flags.extensionPropertiesSupported ? ['properties'] : []),
  ...(props.flags.listenerSupported ? ['listeners'] : [])
])
const selectedExtensionType = computed(() => props.extensionOptions.find(option => (
  option.extensionKey === props.state.extensionKey
))?.extensionType || '')
const selectedExtensionImplementation = computed(() => props.extensionOptions.find(option => (
  option.extensionKey === props.state.extensionKey
))?.implementationKey || '')
const businessEventOptions = computed(() => (
  props.state.eventDefinitionType === 'bpmn:ErrorEventDefinition'
    ? props.errorEventOptions
    : props.escalationEventOptions
))
// 动态多实例只能用于 UserTask；其他活动仍可配置标准串行或并行多实例。
const activityLoopOptions = computed(() => props.multiInstanceOptions.filter(option => (
  !['controlled', 'approvalLoop'].includes(option.value) || props.flags.userTask
)))
// 条件值选项跟随当前正式表单字段；自由文本字段仍允许输入受限标量值。
const controlledLoopValueOptions = computed(() => props.controlledLoopFieldOptions.find(option => (
  option.value === props.state.controlledLoopDecisionVariable
))?.values || [])
// 静态枚举字段只能选择正式表单给出的值；自由文本和普通标量字段仍由后端执行类型与长度校验。
const controlledLoopValueRestricted = computed(() => props.controlledLoopFieldOptions.find(option => (
  option.value === props.state.controlledLoopDecisionVariable
))?.valueRestricted === true)
// 应用按钮只在五项受控属性均完整时开放，避免半成品配置进入 BPMN 命令栈或被保存。
const controlledLoopConfigurationReady = computed(() => {
  const maxIterations = Number(props.state.controlledLoopMaxIterations)
  const decisionVariable = String(props.state.controlledLoopDecisionVariable || '').trim()
  const repeatValue = String(props.state.controlledLoopRepeatValue || '').trim()
  const exitValue = String(props.state.controlledLoopExitValue || '').trim()
  return Number.isInteger(maxIterations)
    && maxIterations >= 2
    && maxIterations <= 50
    && Boolean(decisionVariable)
    && Boolean(repeatValue)
    && Boolean(exitValue)
    && repeatValue !== exitValue
})
const eventDefinitionLabel = computed(() => ({
  'bpmn:MessageEventDefinition': '消息',
  'bpmn:SignalEventDefinition': '信号',
  'bpmn:TimerEventDefinition': '定时器',
  'bpmn:ErrorEventDefinition': '错误',
  'bpmn:EscalationEventDefinition': '升级',
  'bpmn:CompensateEventDefinition': '补偿'
})[props.state.eventDefinitionType] || '无')

/**
 * 在切换 BPMN 元素时恢复高频属性分区，并移除当前元素不支持的旧分区。
 * @param {[string, string[]]} current 当前元素标识和可用分区名称。
 * @param {[string, string[]]|undefined} previous 上一个元素标识和可用分区名称。
 * @returns {void} 新元素默认展开基础与业务配置，同一元素只清理失效分区。
 */
function syncActiveSections(current, previous) {
  const [elementId, sections] = current
  const [previousElementId] = previous || []
  if (elementId !== previousElementId) {
    activeSections.value = sections.filter(name => ['base', 'business'].includes(name))
    return
  }
  activeSections.value = activeSections.value.filter(name => sections.includes(name))
}

/**
 * 展开当前元素支持的全部属性分区。
 * @returns {void} 仅展开模板中实际存在的分区。
 */
function expandAllSections() {
  activeSections.value = [...availableSections.value]
}

/**
 * 收起全部属性分区，为画布和长表单提供更快的浏览切换。
 * @returns {void} 保留元素上下文标题，不隐藏属性面板。
 */
function collapseAllSections() {
  activeSections.value = []
}

/**
 * 请求父组件查询直接办理人目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 无返回值。
 */
function searchAssignees(keyword) {
  emit('identity-search', { target: 'assignees', keyword })
}

/**
 * 请求父组件查询候选认领用户目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 无返回值。
 */
function searchCandidateUsers(keyword) {
  emit('identity-search', { target: 'candidateUsers', keyword })
}

/**
 * 请求父组件查询候选角色或部门目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 无返回值。
 */
function searchCandidateGroups(keyword) {
  emit('identity-search', { target: 'candidateGroups', keyword })
}

/**
 * 切换循环判断字段时清空旧字段条件，避免不同字段的枚举值被静默复用。
 * @returns {void} 清理条件后保留面板草稿，等待设计者显式应用完整配置。
 */
function handleControlledLoopFieldChange() {
  props.state.controlledLoopRepeatValue = ''
  props.state.controlledLoopExitValue = ''
}

/**
 * 切换循环类型；受控整改循环先保留面板草稿，其他类型沿用即时写入命令栈。
 * @param {string} value 当前选中的循环类型。
 * @returns {void} 受控整改循环等待显式应用，其他类型立即通知父组件。
 */
function handleLoopTypeChange(value) {
  if (value !== 'approvalLoop') emit('multi-instance-change')
}

/**
 * 请求父组件查询 SLA 超时升级办理人目录。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 复用直接办理人的审批能力与权限边界。
 */
function searchSlaAssignees(keyword) {
  emit('identity-search', { target: 'assignees', keyword })
}

watch(
  [() => props.state.id, availableSections],
  syncActiveSections,
  { immediate: true }
)
</script>

<style scoped>
.designer-properties-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background:
    linear-gradient(180deg, color-mix(in srgb, var(--el-bg-color) 92%, var(--el-color-primary) 8%), var(--el-bg-color) 118px);
}

.designer-properties-panel__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  min-height: 68px;
  gap: 10px;
  padding: 11px 10px 10px 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.designer-properties-panel__heading {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.designer-properties-panel__eyebrow {
  color: var(--el-color-primary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.16em;
  line-height: 1.4;
  text-transform: uppercase;
}

.designer-properties-panel__heading strong {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 650;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.designer-properties-panel__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 1px;
}

.designer-properties-panel__actions :deep(.el-button) {
  width: 28px;
  height: 28px;
  margin: 0;
  color: var(--el-text-color-secondary);
}

.designer-properties-panel__actions :deep(.el-button:hover),
.designer-properties-panel__actions :deep(.el-button:focus-visible) {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
}

.designer-properties-panel__context {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: center;
  flex: none;
  gap: 7px;
  min-height: 34px;
  padding: 7px 16px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  background: color-mix(in srgb, var(--el-fill-color-light) 72%, transparent);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.designer-properties-panel__context-dot {
  width: 7px;
  height: 7px;
  background: var(--el-color-success);
  border: 2px solid color-mix(in srgb, var(--el-color-success) 22%, transparent);
  border-radius: 50%;
  box-sizing: content-box;
}

.designer-properties-panel__context code {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.designer-properties-panel__scroll {
  flex: 1;
  min-height: 0;
}

.designer-properties-panel__form {
  padding: 10px 12px 18px;
}

.designer-properties-panel__form :deep(.el-collapse) {
  display: grid;
  gap: 8px;
  border-top: 0;
  border-bottom: 0;
}

.designer-properties-panel__form :deep(.el-collapse-item) {
  overflow: hidden;
  background: color-mix(in srgb, var(--el-bg-color) 94%, var(--el-fill-color-light));
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 7px;
  box-shadow: 0 1px 2px rgb(15 23 42 / 4%);
}

.designer-properties-panel__form :deep(.el-collapse-item__header) {
  height: 40px;
  padding: 0 12px;
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
  background: transparent;
  border-bottom: 0;
}

.designer-properties-panel__form :deep(.el-collapse-item__header.is-active) {
  color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 6%, transparent);
}

.designer-properties-panel__form :deep(.el-collapse-item__wrap) {
  background: transparent;
  border-bottom: 0;
}

.designer-properties-panel__form :deep(.el-collapse-item__content) {
  padding: 12px 12px 14px;
}

.designer-properties-panel__form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.designer-properties-panel__form :deep(.el-form-item:last-child) {
  margin-bottom: 0;
}

.designer-properties-panel__form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

.designer-properties-panel__form :deep(.el-select),
.designer-properties-panel__form :deep(.el-segmented),
.designer-properties-panel__form :deep(.el-input-number) {
  width: 100%;
}

.designer-properties-panel :deep(.el-empty) {
  flex: 1;
}

.designer-properties-panel :deep(.el-scrollbar__bar.is-vertical) {
  right: 3px;
}
</style>
