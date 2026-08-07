<template>
  <aside class="designer-properties-panel">
    <div class="designer-properties-panel__title">
      <span>{{ title }}</span>
      <el-tag v-if="state.id" size="small" type="info">{{ state.id }}</el-tag>
    </div>

    <el-scrollbar v-if="selected" class="designer-properties-panel__scroll">
      <el-form label-position="top" size="small" class="designer-properties-panel__form">
        <el-collapse :model-value="['base', 'business', 'execution']">
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
              <CelExpressionEditor
                v-if="selectedExtensionType === 'CEL'"
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
  'extension-properties-change', 'sla-change'
])

// 表单来源值与后端部署快照的 source_type 契约一致。
const formSourceOptions = Object.freeze([
  { label: '正式模板', value: 'TEMPLATE' },
  { label: '内嵌表单', value: 'EMBEDDED' }
])

const hasBusinessSection = computed(() => Object.values(props.flags).some(Boolean))
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
</script>

<style scoped>
.designer-properties-panel {
  min-width: 0;
  overflow: hidden;
  border-left: 1px solid var(--el-border-color-light);
}

.designer-properties-panel__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 46px;
  padding: 0 14px;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.designer-properties-panel__title > span {
  flex: none;
  white-space: nowrap;
}

.designer-properties-panel__title .el-tag {
  flex: 0 1 150px;
  min-width: 0;
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.designer-properties-panel__title .el-tag :deep(.el-tag__content) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.designer-properties-panel__scroll {
  height: calc(100% - 46px);
}

.designer-properties-panel__form {
  padding: 0 14px 14px;
}

.designer-properties-panel__form :deep(.el-collapse) {
  border-top: 0;
}

.designer-properties-panel__form :deep(.el-collapse-item__header) {
  height: 42px;
  font-size: 13px;
  font-weight: 600;
}

.designer-properties-panel__form :deep(.el-select),
.designer-properties-panel__form :deep(.el-segmented),
.designer-properties-panel__form :deep(.el-input-number) {
  width: 100%;
}
</style>
