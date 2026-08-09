<template>
  <div class="process-designer" :class="designerClasses" :style="designerStyle">
    <DesignerToolbar
      :locked="designerLocked"
      :can-undo="canUndo"
      :can-redo="canRedo"
      :selection-count="selectionCount"
      :simulation-active="simulationActive"
      :properties-collapsed="appliedPreference.propertiesCollapsed"
      :issue-count="totalIssueCount"
      :validating="validating"
      @import="openImportPicker"
      @export="exportDiagram"
      @preview="openPreview"
      @clear="clearDiagram"
      @undo="undo"
      @redo="redo"
      @zoom="zoomBy"
      @fit="fitViewport"
      @align="alignSelection"
      @distribute="distributeSelection"
      @toggle-simulation="toggleSimulation"
      @validate="runServerValidation(true)"
      @settings="settingsVisible = true"
      @toggle-properties="toggleProperties"
      @save="requestSave"
    />
    <input ref="importInputRef" class="process-designer__file-input" type="file" accept=".bpmn,.xml,.bpmn20.xml,application/xml,text/xml" @change="handleImportFile" />

    <div
      ref="bodyRef"
      v-loading="designerLocked"
      class="process-designer__body"
      :class="{
        'process-designer__body--properties-collapsed': appliedPreference.propertiesCollapsed,
        'process-designer__body--compact-properties': compactPropertiesLayout,
        'process-designer__body--resizing': propertiesResizing
      }"
      :style="designerBodyStyle"
      :inert="designerLocked"
      :aria-busy="designerLocked"
    >
      <div ref="canvasRef" class="process-designer__canvas" tabindex="0" v-loading="loading" />
      <AdvancedElementPalette :disabled="designerLocked" @create="createAdvancedElement" />
      <div
        v-show="!appliedPreference.propertiesCollapsed"
        class="process-designer__properties-resizer"
        role="separator"
        aria-label="调整属性面板宽度"
        aria-orientation="vertical"
        :aria-valuemin="PROPERTIES_PANEL_MIN_WIDTH"
        :aria-valuemax="propertiesPanelMaxWidth"
        :aria-valuenow="Math.round(propertiesPanelWidth)"
        tabindex="0"
        @pointerdown="startPropertiesResize"
        @dblclick="resetPropertiesPanelWidth"
        @keydown="handlePropertiesResizeKeydown"
      >
        <span class="process-designer__properties-resizer-grip" />
      </div>
      <DesignerPropertiesPanel
        v-show="!appliedPreference.propertiesCollapsed"
        :selected="Boolean(selectedElement)"
        :title="selectedTypeLabel"
        :state="propertyState"
        :flags="propertyFlags"
        :forms="forms"
        :identity-options="identityOptions"
        :identity-loading="identityLoading"
        :assignment-options="assignmentOptions"
        :multi-instance-options="multiInstanceOptions"
        :multi-instance-approval-options="multiInstanceApprovalOptions"
        :controlled-loop-field-options="controlledLoopFieldOptions"
        :extension-options="extensionOptions"
        :form-field-options="formFieldOptions"
        :connector-endpoints="connectorEndpoints"
        :sql-data-sources="sqlDataSources"
        :extension-loading="extensionLoading"
        :dmn-options="dmnOptions"
        :dmn-loading="dmnLoading"
        :listener-options="businessListenerOptions"
        :listener-loading="extensionLoading"
        :error-event-options="errorEventOptions"
        :escalation-event-options="escalationEventOptions"
        :event-code-loading="eventCodeLoading"
        :sla-calendar-options="slaCalendarOptions"
        :sla-loading="slaLoading"
        @common-change="updateCommonProperties"
        @id-change="updateElementId"
        @process-change="updateProcessProperties"
        @participant-change="updateParticipantProperties"
        @form-source-change="updateFormSource"
        @form-change="updateFormKey"
        @embedded-form-change="updateEmbeddedForm"
        @assignment-change="updateAssignment"
        @user-task-change="updateUserTaskProperties"
        @extension-selection-change="updateExtensionSelection"
        @service-task-change="updateServiceTask"
        @condition-change="updateCondition"
        @documentation-change="updateDocumentation"
        @multi-instance-change="updateMultiInstance"
        @activity-change="updateActivityProperties"
        @call-activity-change="updateCallActivityProperties"
        @event-change="updateEventProperties"
        @dmn-change="updateDmnDecision"
        @business-execution-listener-change="updateBusinessExecutionListeners"
        @business-task-listener-change="updateBusinessTaskListeners"
        @extension-properties-change="updateExtensionProperties"
        @sla-change="updateSlaProperties"
        @identity-search="handlePanelIdentitySearch"
        @close="toggleProperties"
      />
    </div>

    <DesignerSettingsDrawer
      v-model="settingsVisible"
      :preference="appliedPreference"
      :saving="preferenceSaving"
      @save="requestPreferenceSave"
    />

    <el-dialog v-model="previewVisible" :title="previewTitle" width="min(920px, 86vw)" append-to-body>
      <el-input class="process-designer__source" :model-value="previewContent" type="textarea" :rows="24" readonly resize="none" />
      <template #footer>
        <el-button icon="DocumentCopy" @click="copyPreview">复制</el-button>
        <el-button type="primary" @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="validationVisible" title="流程校验" width="min(720px, 82vw)" append-to-body>
      <el-result v-if="!validationIssues.length" icon="success" title="校验通过" />
      <el-table v-else :data="validationIssues" max-height="460">
        <el-table-column label="级别" width="88">
          <template #default="scope">
            <el-tag size="small" :type="scope.row.severity === 'ERROR' ? 'danger' : 'warning'">{{ scope.row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" width="190" />
        <el-table-column prop="elementId" label="元素" width="150" show-overflow-tooltip />
        <el-table-column prop="message" label="问题" min-width="260" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup name="ProcessDesigner">
import Modeler from 'bpmn-js/lib/Modeler'
import minimapModule from 'diagram-js-minimap'
import gridSnappingModule from 'bpmn-js/lib/features/grid-snapping'
import lintModule from 'bpmn-js-bpmnlint'
import tokenSimulationModule from 'bpmn-js-token-simulation'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import 'diagram-js-minimap/assets/diagram-js-minimap.css'
import 'bpmn-js-bpmnlint/dist/assets/css/bpmn-js-bpmnlint.css'
import 'bpmn-js-token-simulation/assets/css/bpmn-js-token-simulation.css'
import Download from '@/plugins/download'
import { validateModelBpmn } from '@/api/workflow/model'
import { listCelExtensionOptions, listFormFieldExtensionOptions, listHttpExtensionOptions, listJavaExtensionOptions, listSqlExtensionOptions } from '@/api/workflow/extension'
import { listConnectorEndpointOptions } from '@/api/workflow/connector'
import { listSqlDataSourceOptions } from '@/api/workflow/sqlDatasource'
import { listDmnDecisionOptions } from '@/api/workflow/dmn'
import { listBpmnEventCodeOptions } from '@/api/workflow/bpmnEvent'
import { listEnabledSlaCalendars } from '@/api/workflow/sla'
import flowableModdle from './bpmn/flowableModdle'
import { normalizeSequenceFlowReferences } from './bpmnGraphXml'
import { normalizeTaskListenerXml } from './taskListenerXml'
import DesignerToolbar from './designer/DesignerToolbar.vue'
import DesignerSettingsDrawer from './designer/DesignerSettingsDrawer.vue'
import AdvancedElementPalette from './designer/AdvancedElementPalette.vue'
import DesignerPropertiesPanel from './designer/DesignerPropertiesPanel.vue'
import bpmnlintConfig from './designer/bpmnlintConfig'

// 受控多实例的技术属性由设计器固定写入，页面不向设计者开放任意方法或变量名。
const CONTROLLED_MULTI_INSTANCE_COLLECTION = '${multiInstanceHandler.getUserIds(execution)}'
const FIXED_MULTI_INSTANCE_COLLECTION_PATTERN = /^\$\{multiInstanceHandler\.getFixedUserIds\(execution, '([1-9]\d*(?:,[1-9]\d*)*)'\)\}$/
const CONTROLLED_MULTI_INSTANCE_ASSIGNEE = '${assignee}'
const CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE = 'assignee'
const CONTROLLED_MULTI_INSTANCE_ALL_CONDITION = '${nrOfCompletedInstances == nrOfInstances}'
const CONTROLLED_MULTI_INSTANCE_ANY_CONDITION = '${nrOfCompletedInstances > 0}'
// 业务监听器的作者 XML 只允许固定调度 Bean；后端部署时会冻结版本并剥离字段。
const BUSINESS_LISTENER_DELEGATE_EXPRESSION = '${workflowBusinessListener}'
// 受控整改循环只保存固定属性；运行路由、轮次和审计均由后端部署编译与任务完成服务维护。
const CONTROLLED_LOOP_PROPERTY_PREFIX = 'approva.controlledLoop.'
const CONTROLLED_LOOP_PROPERTIES = Object.freeze({
  enabled: `${CONTROLLED_LOOP_PROPERTY_PREFIX}enabled`,
  decisionVariable: `${CONTROLLED_LOOP_PROPERTY_PREFIX}decisionVariable`,
  repeatValue: `${CONTROLLED_LOOP_PROPERTY_PREFIX}repeatValue`,
  exitValue: `${CONTROLLED_LOOP_PROPERTY_PREFIX}exitValue`,
  maxIterations: `${CONTROLLED_LOOP_PROPERTY_PREFIX}maxIterations`
})
const CONTROLLED_LOOP_PROPERTY_NAMES = new Set(Object.values(CONTROLLED_LOOP_PROPERTIES))

// 内嵌字段类型、变量和日期格式与后端 WorkflowEmbeddedFormConverter 使用同一安全边界。
const EMBEDDED_FORM_TYPES = Object.freeze(['string', 'long', 'integer', 'boolean', 'date', 'enum'])
const EMBEDDED_FORM_VARIABLE_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/
const EMBEDDED_FORM_DATE_PATTERN = /^[A-Za-z0-9 /:._-]{1,64}$/
const EMBEDDED_FORM_RESERVED_VARIABLES = new Set([
  'initiator', 'processStatus', 'processInstanceId', 'processDefinitionId',
  'deploymentId', 'startUserId', 'authenticatedUserId', 'businessKey',
  'assignee', 'nrOfInstances', 'nrOfActiveInstances', 'nrOfCompletedInstances',
  'loopCounter', '_FLOWABLE_SKIP_EXPRESSION_ENABLED'
])
const EMBEDDED_FORM_RESERVED_PREFIXES = Object.freeze([
  'wfMiUsers_', '_wfMiMembers_', '_wfMiRevision_', '_wfMiMode_', '__ruoyi_workflow_'
])
const EXTENSION_PROPERTY_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/
// SLA 作者属性由结构化面板独占维护，通用扩展属性编辑器不能覆盖这些保留字段。
const SLA_PROPERTY_NAMES = Object.freeze({
  enabled: 'approva.sla.enabled',
  calendarKey: 'approva.sla.calendarKey',
  reminderMinutes: 'approva.sla.reminderMinutes',
  reminderRepeatMinutes: 'approva.sla.reminderRepeatMinutes',
  maxReminders: 'approva.sla.maxReminders',
  escalationMinutes: 'approva.sla.escalationMinutes',
  escalationUserId: 'approva.sla.escalationUserId',
  escalationEventCode: 'approva.sla.escalationEventCode'
})
const SLA_PROPERTY_NAME_SET = new Set(Object.values(SLA_PROPERTY_NAMES))

// 作者 BPMN 只保存稳定键和配置；部署版本、实现和校验和均由后端注册表冻结。
const EXTENSION_DELEGATE_EXPRESSION = '${workflowExtensionDelegate}'
const EXTENSION_KEY_FIELD = 'approvaExtensionKey'
const EXTENSION_CONFIG_FIELD = 'approvaExtensionConfig'
const CEL_DEFAULT_CONFIG = Object.freeze({
  expression: 'true',
  resultVariable: 'celResult',
  resultType: 'BOOL',
  variables: []
})

const props = defineProps({
  /** 设计器当前 BPMN XML。 */
  modelValue: { type: String, default: '' },
  /** 模型元数据，用于没有 XML 时生成首个可编辑流程。 */
  model: { type: Object, default: () => ({}) },
  /** 可引用的正式表单列表。 */
  forms: { type: Array, default: () => [] },
  /** 服务端按直接办理和完整候选认领资格隔离的正式身份选项。 */
  identityOptions: {
    type: Object,
    default: () => ({ assignees: [], candidateUsers: [], candidateGroups: [] })
  },
  /** 设计器稳定高度。 */
  height: { type: String, default: 'calc(100vh - 128px)' },
  /** 保存请求是否正在执行。 */
  saving: { type: Boolean, default: false },
  /** 页面是否正在查询正式用户、角色或部门主数据。 */
  identityLoading: { type: Boolean, default: false },
  /** 服务端回读的正式设计器偏好。 */
  preference: {
    type: Object,
    default: () => ({
      theme: 'SYSTEM',
      gridEnabled: true,
      minimapEnabled: true,
      lintEnabled: true,
      tokenSimulationEnabled: false,
      propertiesCollapsed: false
    })
  },
  /** 设计器偏好是否正在写入正式数据库。 */
  preferenceSaving: { type: Boolean, default: false }
})

const emit = defineEmits([
  'update:modelValue', 'change', 'save', 'error', 'identity-search', 'preference-save'
])
const canvasRef = ref(null)
const bodyRef = ref(null)
const importInputRef = ref(null)
const loading = ref(false)
// savePreparing 覆盖保存前 XML 序列化窗口，避免父页面 saving 回写前产生重复保存命令。
const savePreparing = ref(false)
const canUndo = ref(false)
const canRedo = ref(false)
const selectionCount = ref(0)
const settingsVisible = ref(false)
const previewVisible = ref(false)
const previewTitle = ref('')
const previewContent = ref('')
const validationVisible = ref(false)
const validationIssues = ref([])
const clientLintIssues = ref([])
const validating = ref(false)
const simulationActive = ref(false)
const systemDark = ref(false)
const selectedElement = shallowRef(null)
const lastExportedXml = ref('')
const propertyState = reactive(createEmptyPropertyState())
const designerStyle = computed(() => ({ height: props.height }))
// propertiesPanelWidth 表示当前会话中属性检查器的可视宽度，不使用浏览器本地状态冒充正式偏好。
const propertiesPanelWidth = ref(368)
const propertiesResizing = ref(false)
const designerBodyWidth = ref(0)
const PROPERTIES_PANEL_MIN_WIDTH = 320
const PROPERTIES_PANEL_DEFAULT_WIDTH = 368
const PROPERTIES_PANEL_MAX_WIDTH = 520
const PROPERTIES_COMPACT_BREAKPOINT = 960
const MINIMUM_INLINE_CANVAS_WIDTH = 520
const compactPropertiesLayout = computed(() => (
  designerBodyWidth.value > 0 && designerBodyWidth.value < PROPERTIES_COMPACT_BREAKPOINT
))
const propertiesPanelMaxWidth = computed(() => {
  if (compactPropertiesLayout.value) {
    return Math.max(PROPERTIES_PANEL_MIN_WIDTH, Math.min(
      PROPERTIES_PANEL_MAX_WIDTH,
      designerBodyWidth.value - 24
    ))
  }
  return Math.max(PROPERTIES_PANEL_MIN_WIDTH, Math.min(
    PROPERTIES_PANEL_MAX_WIDTH,
    designerBodyWidth.value - MINIMUM_INLINE_CANVAS_WIDTH
  ))
})
const designerBodyStyle = computed(() => ({
  '--designer-properties-width': `${Math.round(propertiesPanelWidth.value)}px`
}))
const designerLocked = computed(() => props.saving || savePreparing.value)
const appliedPreference = computed(() => normalizePreference(props.preference))
const totalIssueCount = computed(() => validationIssues.value.length + clientLintIssues.value.length)
const designerClasses = computed(() => ({
  'process-designer--dark': appliedPreference.value.theme === 'DARK'
    || (appliedPreference.value.theme === 'SYSTEM' && systemDark.value),
  'process-designer--grid': appliedPreference.value.gridEnabled
}))
const selectedBusinessObject = computed(() => selectedElement.value?.businessObject)
const isProcess = computed(() => isType('bpmn:Process'))
const isStartEvent = computed(() => isType('bpmn:StartEvent'))
const isUserTask = computed(() => isType('bpmn:UserTask'))
const isServiceTask = computed(() => isType('bpmn:ServiceTask'))
const isBusinessRuleTask = computed(() => isType('bpmn:BusinessRuleTask'))
const isSequenceFlow = computed(() => isType('bpmn:SequenceFlow'))
const isParticipant = computed(() => isType('bpmn:Participant'))
const propertyFlags = computed(() => {
  const eventDefinitionType = propertyState.eventDefinitionType
  return Object.freeze({
    process: isProcess.value,
    participant: isParticipant.value,
    startEvent: isStartEvent.value,
    userTask: isUserTask.value,
    serviceTaskLike: isType('bpmn:ServiceTask') || isType('bpmn:SendTask'),
    businessRuleTask: isBusinessRuleTask.value,
    formSupported: isStartEvent.value || isUserTask.value,
    sequenceFlow: isSequenceFlow.value,
    callActivity: isType('bpmn:CallActivity'),
    activity: isType('bpmn:Activity'),
    event: isType('bpmn:Event'),
    referenceEvent: [
      'bpmn:MessageEventDefinition',
      'bpmn:SignalEventDefinition',
      'bpmn:ErrorEventDefinition',
      'bpmn:EscalationEventDefinition'
    ].includes(eventDefinitionType),
    businessReferenceEvent: [
      'bpmn:ErrorEventDefinition',
      'bpmn:EscalationEventDefinition'
    ].includes(eventDefinitionType),
    timerEvent: eventDefinitionType === 'bpmn:TimerEventDefinition',
    boundaryEvent: isType('bpmn:BoundaryEvent'),
    listenerSupported: isProcess.value || isType('bpmn:FlowNode'),
    extensionPropertiesSupported: isProcess.value || isType('bpmn:FlowElement')
  })
})
const selectedTypeLabel = computed(() => typeLabel(selectedBusinessObject.value?.$type))
const assignmentOptions = [
  { label: '办理人', value: 'assignee' },
  { label: '用户', value: 'users' },
  { label: '角色/部门', value: 'groups' }
]
  // none/sequential/parallel 对应标准 BPMN 多实例；controlled 提供受控动态和固定成员两种来源。
const multiInstanceOptions = [
  { label: '无', value: 'none' },
  { label: '标准循环（仅往返）', value: 'standard' },
  { label: '整改循环（受控）', value: 'approvalLoop' },
  { label: '串行', value: 'sequential' },
  { label: '并行', value: 'parallel' },
  { label: '动态', value: 'controlled' }
]
// all/any 分别映射全员完成与任一完成条件，决定动态多实例的会签或或签终止语义。
const multiInstanceApprovalOptions = [
  { label: '会签', value: 'all' },
  { label: '或签', value: 'any' }
]
const extensionOptions = ref([])
// 监听器仅允许选择后端明确安装的 JAVA 处理器，CEL/HTTP/SQL 不进入生命周期回调。
const businessListenerOptions = computed(() => extensionOptions.value.filter(option => option.extensionType === 'JAVA'))
// formFieldOptions 只包含后端 FORM_FIELD 注册表选项，不接受本地组件名或模板。
const formFieldOptions = ref([])
// connectorEndpoints 只保存后端白名单元数据，接口从不返回认证密钥正文。
const connectorEndpoints = ref([])
// sqlDataSources 只保存数据源环境引用和白名单元数据，不包含连接凭据正文。
const sqlDataSources = ref([])
const extensionLoading = ref(false)
// dmnOptions 只包含服务端过滤后的来源决策最新版本，每项仍以精确 decisionId 作为作者引用。
const dmnOptions = ref([])
const dmnLoading = ref(false)
// 错误与升级目录来自正式数据库，作者 XML 只保存稳定编码。
const errorEventOptions = ref([])
const escalationEventOptions = ref([])
const eventCodeLoading = ref(false)
// 字段目录来自当前节点正式模板或内嵌表单，设计者不能输入任意流程变量作为循环条件。
const controlledLoopFieldOptions = computed(() => resolveControlledLoopFieldOptions())
// slaCalendarOptions 只包含后端返回的启用日历，作者 XML 不接受自由输入日历键。
const slaCalendarOptions = ref([])
const slaLoading = ref(false)
let modeler
let changeTimer
let systemThemeQuery
let bodyResizeObserver
let canvasResizeFrame
let resizeStartClientX = 0
let resizeStartWidth = PROPERTIES_PANEL_DEFAULT_WIDTH
let previousDocumentCursor = ''
let previousDocumentUserSelect = ''
const identitySearchTimers = new Map()
let importing = false

// 三类检索目标分别冻结后端身份类型和能力，防止切换办理方式时降级为通用目录。
const IDENTITY_SEARCH_CONTRACTS = Object.freeze({
  assignees: Object.freeze({ type: 'user', capability: 'approval' }),
  candidateUsers: Object.freeze({ type: 'user', capability: 'claim' }),
  candidateGroups: Object.freeze({ type: 'group', capability: 'claim' })
})

/**
 * 规范化服务端设计器偏好，拒绝未知主题并为旧版本缺失字段提供服务端契约默认值。
 * @param {object|undefined} preference 页面从正式偏好接口回读的数据。
 * @returns {object} 字段完整、可直接应用到 Modeler 的只读偏好值。
 */
function normalizePreference(preference) {
  return Object.freeze({
    theme: ['LIGHT', 'DARK', 'SYSTEM'].includes(preference?.theme) ? preference.theme : 'SYSTEM',
    gridEnabled: preference?.gridEnabled !== false,
    minimapEnabled: preference?.minimapEnabled !== false,
    lintEnabled: preference?.lintEnabled !== false,
    tokenSimulationEnabled: preference?.tokenSimulationEnabled === true,
    propertiesCollapsed: preference?.propertiesCollapsed === true
  })
}

/**
 * 创建属性面板的稳定初始状态。
 * @returns {object} 不携带上一元素值的新状态对象。
 */
function createEmptyPropertyState() {
  return {
    id: '',
    name: '',
    processRef: '',
    executable: true,
    versionTag: '',
    formSource: 'TEMPLATE',
    formKey: '',
    embeddedFields: [],
    assignmentType: 'assignee',
    assignee: '',
    candidateUsers: [],
    candidateGroups: [],
    dueDate: '',
    priority: '',
    taskCategory: '',
    skipExpression: '',
    localScope: false,
    sla: createDefaultSlaConfig(),
    extensionKey: '',
    extensionConfig: '{}',
    dmnDecisionId: '',
    calledElement: '',
    businessKey: '',
    processInstanceName: '',
    conditionExpression: '',
    documentation: '',
    asyncBefore: false,
    asyncAfter: false,
    exclusive: true,
    forCompensation: false,
    eventDefinitionType: '',
    eventReference: '',
    timerDefinitionType: 'timeDuration',
    timerDefinition: '',
    cancelActivity: true,
    multiInstanceType: 'none',
    multiInstanceApprovalMode: 'all',
    multiInstanceMemberSource: 'dynamic',
    fixedMultiInstanceUserIds: [],
    collection: '',
    elementVariable: '',
    completionCondition: '',
    loopMaximum: '',
    loopCondition: '',
    testBefore: false,
    controlledLoopMaxIterations: 3,
    controlledLoopDecisionVariable: '',
    controlledLoopRepeatValue: '',
    controlledLoopExitValue: '',
    businessExecutionListeners: [],
    businessTaskListeners: [],
    extensionProperties: []
  }
}

/**
 * 安全转义生成初始 BPMN XML 时使用的属性文本。
 * @param {unknown} value 待写入 XML 属性的值。
 * @returns {string} 已转义 XML 文本。
 */
function escapeXml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
}

/**
 * 在模型尚无编辑器源码时生成可直接编辑的最小可执行流程。
 * @returns {string} 包含开始、用户任务和结束节点的 BPMN 2.0 XML。
 */
function createInitialXml() {
  const processId = String(props.model.modelKey || 'workflow_process').replace(/[^A-Za-z0-9_.-]/g, '_')
  const processName = escapeXml(props.model.modelName || '新流程')
  const formId = props.model.formId
  const formAttribute = formId ? ` flowable:formKey="key_${escapeXml(formId)}"` : ''
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  xmlns:flowable="http://flowable.org/bpmn"
  targetNamespace="http://ruoyi.example/workflow">
  <process id="${escapeXml(processId)}" name="${processName}" isExecutable="true">
    <startEvent id="start" name="提交申请"${formAttribute}/>
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="审批">
      <extensionElements>
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}"/>
        <flowable:taskListener event="assignment" delegateExpression="\${userTaskListener}"/>
        <flowable:taskListener event="complete" delegateExpression="\${userTaskListener}"/>
      </extensionElements>
    </userTask>
    <sequenceFlow id="flow_review_end" sourceRef="review" targetRef="end"/>
    <endEvent id="end" name="结束"/>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_${escapeXml(processId)}">
    <bpmndi:BPMNPlane id="BPMNPlane_${escapeXml(processId)}" bpmnElement="${escapeXml(processId)}">
      <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="160" y="172" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="review_di" bpmnElement="review"><dc:Bounds x="270" y="150" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="450" y="172" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNEdge id="flow_start_review_di" bpmnElement="flow_start_review"><di:waypoint x="196" y="190"/><di:waypoint x="270" y="190"/></bpmndi:BPMNEdge>
      <bpmndi:BPMNEdge id="flow_review_end_di" bpmnElement="flow_review_end"><di:waypoint x="370" y="190"/><di:waypoint x="450" y="190"/></bpmndi:BPMNEdge>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 按办理身份目标对远程检索进行独立防抖，避免资格不同的请求互相覆盖。
 * @param {'assignees'|'candidateUsers'|'candidateGroups'} target 直接办理人或候选身份选项池。
 * @param {string} keyword 用户输入的名称关键字。
 * @returns {void} 到达防抖窗口后通过事件交由页面请求真实后端。
 */
function scheduleIdentitySearch(target, keyword) {
  const previousTimer = identitySearchTimers.get(target)
  window.clearTimeout(previousTimer)
  const timer = window.setTimeout(() => {
    identitySearchTimers.delete(target)
    // directoryContract 是当前选项池不可降级的服务端资格契约。
    const directoryContract = IDENTITY_SEARCH_CONTRACTS[target]
    if (!directoryContract) return
    emit('identity-search', {
      ...directoryContract,
      keyword: String(keyword || '').trim()
    })
  }, 250)
  identitySearchTimers.set(target, timer)
}

/**
 * 接收属性面板的身份检索请求并转入受控资格目录。
 * @param {{target:string, keyword:string}|undefined} request 属性面板给出的目标池和检索词。
 * @returns {void} 未知目标会被拒绝，不向页面发出降级目录请求。
 */
function handlePanelIdentitySearch(request) {
  const target = request?.target
  if (!IDENTITY_SEARCH_CONTRACTS[target]) return
  scheduleIdentitySearch(target, request?.keyword)
}

/**
 * 初始化 bpmn-js Modeler、Flowable moddle 和事件监听。
 * @returns {void} Modeler 生命周期由组件管理。
 */
function createModeler() {
  if (modeler || !canvasRef.value) return
  modeler = new Modeler({
    container: canvasRef.value,
    linting: { bpmnlint: bpmnlintConfig },
    gridSnapping: { active: appliedPreference.value.gridEnabled },
    additionalModules: [minimapModule, gridSnappingModule, lintModule, tokenSimulationModule],
    moddleExtensions: { flowable: flowableModdle }
  })
  const eventBus = modeler.get('eventBus')
  eventBus.on('selection.changed', event => {
    selectionCount.value = event.newSelection?.length || 0
    selectElement(event.newSelection?.[0])
  })
  eventBus.on('element.changed', event => {
    if (event.element === selectedElement.value) loadPropertyState(event.element)
  })
  eventBus.on('commandStack.changed', handleCommandStackChanged)
  eventBus.on('linting.completed', event => {
    clientLintIssues.value = Object.values(event.issues || {}).flat()
  })
  eventBus.on('tokenSimulation.toggleMode', event => {
    simulationActive.value = Boolean(event.active)
  })
}

/**
 * 导入外部 XML；失败时清空旧图并向页面报告错误。
 * @param {string} xml BPMN XML，为空时创建初始流程。
 * @returns {Promise<void>} 导入完成后更新撤销状态和 v-model。
 */
async function importXml(xml) {
  createModeler()
  if (!modeler) return
  importing = true
  loading.value = true
  try {
    const rawSource = xml?.trim() ? xml : createInitialXml()
    // 旧模型可能只有 sequenceFlow.sourceRef/targetRef，必须补齐 FlowNode 反向引用后再交给 Lint 和命令栈。
    const source = normalizeSequenceFlowReferences(rawSource)
    await modeler.importXML(source)
    applyDesignerPreference()
    refreshControlledLoopOverlays()
    fitViewport()
    const processElement = modeler.get('elementRegistry').getAll().find(element => element.type === 'bpmn:Process')
    selectElement(processElement)
    await emitXmlChange(false)
  } catch (error) {
    modeler.clear()
    emit('error', error)
  } finally {
    importing = false
    loading.value = false
    updateCommandState()
  }
}

/**
 * 修复 keep-alive 页签中仍保留的旧版顺序流内存引用。
 * @returns {Promise<void>} 当前画布缺少 incoming/outgoing 时以完整 XML 重建；正常画布不产生变更。
 */
async function repairCachedSequenceFlowReferences() {
  if (!modeler || importing || designerLocked.value) return
  try {
    // cachedXml 是当前画布的完整快照，包含尚未保存的用户编辑，不能回退为服务端旧 XML。
    const { xml: cachedXml } = await modeler.saveXML({ format: true })
    const normalizedXml = normalizeSequenceFlowReferences(cachedXml)
    if (normalizedXml === cachedXml) return
    await importXml(normalizedXml)
  } catch (error) {
    emit('error', error)
  }
}

/**
 * 处理命令栈变化，更新撤销按钮并节流导出 XML。
 * @returns {void} 无返回值。
 */
function handleCommandStackChanged() {
  updateCommandState()
  window.requestAnimationFrame(refreshControlledLoopOverlays)
  if (importing) return
  // 任何建模变更都会使上一次服务端诊断失效，禁止显示过期的“已通过”结果。
  validationIssues.value = []
  window.clearTimeout(changeTimer)
  changeTimer = window.setTimeout(() => emitXmlChange(true), 180)
}

/**
 * 更新撤销与重做按钮状态。
 * @returns {void} 无返回值。
 */
function updateCommandState() {
  if (!modeler) return
  const commandStack = modeler.get('commandStack')
  canUndo.value = commandStack.canUndo()
  canRedo.value = commandStack.canRedo()
}

/**
 * 导出当前画布 XML 并同步 v-model，保留设计器原始建模状态。
 * @param {boolean} notifyChange 是否同时触发 change 事件。
 * @returns {Promise<string>} 格式化后的 BPMN XML。
 */
async function emitXmlChange(notifyChange) {
  if (!modeler) return ''
  const { xml } = await modeler.saveXML({ format: true })
  lastExportedXml.value = xml
  emit('update:modelValue', xml)
  if (notifyChange) emit('change', xml)
  return xml
}

/**
 * 导出可持久化 XML，并自动补齐后台任务身份审计监听器。
 * @returns {Promise<string>} 已满足后端保存门禁的 BPMN XML。
 */
async function emitPersistedXml() {
  const rawXml = await emitXmlChange(false)
  const persistedXml = normalizeTaskListenerXml(rawXml)
  if (persistedXml !== rawXml) {
    // 持久化快照必须回写给父页面，避免保存请求和页面状态使用不同 XML。
    lastExportedXml.value = persistedXml
    emit('update:modelValue', persistedXml)
  }
  return persistedXml
}

/**
 * 选择画布元素并加载对应属性。
 * @param {object|undefined} element bpmn-js 图形元素。
 * @returns {void} 无返回值。
 */
function selectElement(element) {
  selectedElement.value = element || null
  loadPropertyState(element)
}

/**
 * 从业务对象读取受控属性到侧栏状态。
 * @param {object|undefined} element bpmn-js 图形元素。
 * @returns {void} 不存在元素时重置侧栏。
 */
function loadPropertyState(element) {
  Object.assign(propertyState, createEmptyPropertyState())
  const businessObject = element?.businessObject
  if (!businessObject) return
  propertyState.id = businessObject.id || ''
  propertyState.name = businessObject.name || ''
  propertyState.executable = businessObject.isExecutable !== false
  propertyState.versionTag = businessObject.get('flowable:versionTag') || ''
  propertyState.formKey = businessObject.get('flowable:formKey') || ''
  propertyState.embeddedFields = readEmbeddedFormFields(businessObject)
  propertyState.formSource = propertyState.embeddedFields.length ? 'EMBEDDED' : 'TEMPLATE'
  propertyState.assignee = businessObject.get('flowable:assignee') || ''
  propertyState.candidateUsers = splitValues(businessObject.get('flowable:candidateUsers'))
  propertyState.candidateGroups = splitValues(businessObject.get('flowable:candidateGroups'))
  propertyState.assignmentType = propertyState.candidateGroups.length
    ? 'groups'
    : propertyState.candidateUsers.length ? 'users' : 'assignee'
  propertyState.dueDate = businessObject.get('flowable:dueDate') || ''
  propertyState.priority = businessObject.get('flowable:priority') || ''
  propertyState.taskCategory = businessObject.get('flowable:category') || ''
  propertyState.skipExpression = businessObject.get('flowable:skipExpression') || ''
  propertyState.localScope = businessObject.get('flowable:localScope') === true
  propertyState.documentation = businessObject.documentation?.[0]?.text || ''
  propertyState.processRef = businessObject.processRef || ''
  propertyState.conditionExpression = businessObject.conditionExpression?.body || ''
  const serviceExtension = readServiceTaskExtension(businessObject)
  propertyState.extensionKey = serviceExtension.extensionKey
  propertyState.extensionConfig = serviceExtension.extensionConfig
  propertyState.businessExecutionListeners = readBusinessListeners(businessObject, 'flowable:ExecutionListener')
  propertyState.businessTaskListeners = readBusinessListeners(businessObject, 'flowable:TaskListener')
  const extensionProperties = readExtensionProperties(businessObject)
  propertyState.sla = readSlaConfig(extensionProperties)
  // 通用属性面板排除循环和 SLA 两组平台保留字段，避免绕过结构化校验。
  propertyState.extensionProperties = extensionProperties.filter(item => !SLA_PROPERTY_NAME_SET.has(item.name))
  const controlledLoop = readControlledLoop(businessObject)
  if (controlledLoop) {
    propertyState.multiInstanceType = 'approvalLoop'
    propertyState.controlledLoopMaxIterations = controlledLoop.maxIterations
    propertyState.controlledLoopDecisionVariable = controlledLoop.decisionVariable
    propertyState.controlledLoopRepeatValue = controlledLoop.repeatValue
    propertyState.controlledLoopExitValue = controlledLoop.exitValue
    return
  }
  propertyState.dmnDecisionId = businessObject.get('flowable:rules') || ''
  propertyState.calledElement = businessObject.calledElement || ''
  propertyState.businessKey = businessObject.get('flowable:businessKey') || ''
  propertyState.processInstanceName = businessObject.get('flowable:processInstanceName') || ''
  propertyState.asyncBefore = businessObject.get('flowable:async') === true
  propertyState.asyncAfter = businessObject.get('flowable:asyncLeave') === true
  propertyState.exclusive = businessObject.get('flowable:exclusive') !== false
  propertyState.forCompensation = businessObject.isForCompensation === true
  propertyState.cancelActivity = businessObject.cancelActivity !== false
  loadEventPropertyState(businessObject)
  const loop = businessObject.loopCharacteristics
  if (loop) {
    if (loop.$type === 'bpmn:StandardLoopCharacteristics') {
      propertyState.multiInstanceType = 'standard'
      propertyState.loopMaximum = loop.loopMaximum == null ? '' : String(loop.loopMaximum)
      propertyState.loopCondition = loop.loopCondition?.body || ''
      propertyState.testBefore = loop.testBefore === true
      return
    }
    propertyState.collection = loop.get('flowable:collection') || ''
    propertyState.elementVariable = loop.get('flowable:elementVariable') || ''
    propertyState.completionCondition = loop.completionCondition?.body || ''
    if (isControlledMultiInstanceLoop(loop)) {
      propertyState.multiInstanceType = 'controlled'
      propertyState.multiInstanceMemberSource = isFixedMultiInstanceLoop(loop) ? 'fixed' : 'dynamic'
      propertyState.fixedMultiInstanceUserIds = readFixedMultiInstanceUserIds(loop)
      propertyState.multiInstanceApprovalMode = propertyState.completionCondition === CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
        ? 'any'
        : 'all'
    } else {
      propertyState.multiInstanceType = loop.isSequential ? 'sequential' : 'parallel'
    }
  }
}

/**
 * 从当前元素读取受限的 Flowable 通用扩展属性。
 * @param {object} businessObject 当前 BPMN 流程或流程元素业务对象。
 * @returns {Array<{name:string,value:string}>} 保持 XML 顺序的名值列表。
 */
function readExtensionProperties(businessObject) {
  const containers = (businessObject?.extensionElements?.values || [])
    .filter(value => value?.$type === 'flowable:Properties')
  return containers.flatMap(container => (container.values || [])
    .filter(property => !CONTROLLED_LOOP_PROPERTY_NAMES.has(property.name))
    .map(property => ({ name: property.name || '', value: property.value || '' })))
}

/**
 * 在画布用户任务右上角绘制受控整改循环标识和最大轮次，避免配置只藏在属性面板中。
 * @returns {void} Modeler 未初始化时不执行，普通任务不产生覆盖层。
 */
function refreshControlledLoopOverlays() {
  if (!modeler) return
  const overlays = modeler.get('overlays')
  overlays.remove({ type: 'controlled-loop' })
  for (const element of modeler.get('elementRegistry').getAll()) {
    if (element?.type !== 'bpmn:UserTask') continue
    const config = readControlledLoop(element.businessObject)
    if (!config) continue
    const badge = document.createElement('span')
    badge.className = 'process-designer__controlled-loop-badge'
    badge.textContent = `整改循环 · 最多 ${config.maxIterations} 轮`
    overlays.add(element, 'controlled-loop', {
      position: { top: -12, right: -12 },
      html: badge,
      show: { minZoom: 0.5 }
    })
  }
}

/**
 * 从用户任务固定 Flowable 属性回读受控整改循环配置。
 * @param {object} businessObject 当前 BPMN 用户任务业务对象。
 * @returns {{decisionVariable:string,repeatValue:string,exitValue:string,maxIterations:number}|null} 完整配置；未启用时为空。
 */
function readControlledLoop(businessObject) {
  const properties = (businessObject?.extensionElements?.values || [])
    .filter(value => value?.$type === 'flowable:Properties')
    .flatMap(container => container.values || [])
  const values = Object.fromEntries(properties
    .filter(property => CONTROLLED_LOOP_PROPERTY_NAMES.has(property.name))
    .map(property => [property.name, String(property.value ?? '')]))
  if (!Object.keys(values).length) return null
  if (values[CONTROLLED_LOOP_PROPERTIES.enabled] !== 'true') return null
  const maxIterations = Number(values[CONTROLLED_LOOP_PROPERTIES.maxIterations])
  return {
    decisionVariable: values[CONTROLLED_LOOP_PROPERTIES.decisionVariable] || '',
    repeatValue: values[CONTROLLED_LOOP_PROPERTIES.repeatValue] || '',
    exitValue: values[CONTROLLED_LOOP_PROPERTIES.exitValue] || '',
    maxIterations: Number.isInteger(maxIterations) ? maxIterations : 3
  }
}

/**
 * 从当前节点正式模板或内嵌 FormData 提取可作为循环判断条件的字段和值目录。
 * @returns {Array<{value:string,label:string,values:Array<{value:string,label:string}>,valueRestricted:boolean}>} 去重后的字段选项。
 */
function resolveControlledLoopFieldOptions() {
  if (!isUserTask.value) return []
  if (propertyState.formSource === 'EMBEDDED') {
    return propertyState.embeddedFields
      .filter(field => field.writable !== false && field.variable)
      .map(field => ({
        value: field.variable,
        label: field.name ? `${field.name}（${field.variable}）` : field.variable,
        values: field.type === 'boolean'
          ? [{ value: 'true', label: '是' }, { value: 'false', label: '否' }]
          : (field.values || []).map(item => ({ value: String(item.id), label: item.name || item.id })),
        valueRestricted: field.type === 'boolean' || field.type === 'enum'
      }))
  }
  const formId = Number(String(propertyState.formKey || '').replace(/^key_/, ''))
  const form = props.forms.find(item => Number(item.formId) === formId)
  if (!form?.content) return []
  try {
    const root = JSON.parse(form.content)
    const result = []
    const seen = new Set()
    const visit = fields => {
      for (const field of Array.isArray(fields) ? fields : []) {
        const variable = String(field?.__vModel__ || '').trim()
        const kind = resolveTemplateControlledLoopKind(field)
        if (variable && kind && field?.__config__?.workflowWritable !== false && !seen.has(variable)) {
          seen.add(variable)
          const options = Array.isArray(field?.__slot__?.options)
            ? field.__slot__.options.map(item => ({
                value: String(item?.value ?? item?.label ?? ''),
                label: String(item?.label ?? item?.value ?? '')
              })).filter(item => item.value)
            : []
          result.push({
            value: variable,
            label: field?.__config__?.label ? `${field.__config__.label}（${variable}）` : variable,
            values: kind === 'BOOLEAN'
              ? [{ value: 'true', label: '是' }, { value: 'false', label: '否' }]
              : options,
            valueRestricted: kind === 'BOOLEAN' || field?.__config__?.workflowEnum === true
          })
        }
        visit(field?.__config__?.children)
      }
    }
    visit(root.fields)
    return result
  } catch {
    return []
  }
}

/**
 * 将正式模板组件收窄为后端循环条件允许的可写标量种类。
 * @param {object} field 使用 __config__ 的正式模板字段。
 * @returns {'TEXT'|'NUMBER'|'BOOLEAN'|'SCALAR'|null} 标量种类；集合、附件、表格和范围字段返回空。
 */
function resolveTemplateControlledLoopKind(field) {
  const tag = String(field?.__config__?.tag || '')
  if (['el-input', 'tinymce', 'el-color-picker'].includes(tag)) return 'TEXT'
  if (['el-input-number', 'el-rate'].includes(tag)) return 'NUMBER'
  if (tag === 'el-slider') return field?.range === true ? null : 'NUMBER'
  if (tag === 'el-switch') return 'BOOLEAN'
  if (tag === 'el-radio-group') return 'SCALAR'
  if (tag === 'el-select') return field?.multiple === true ? null : 'SCALAR'
  if (['el-time-picker', 'el-date-picker'].includes(tag)) {
    const temporalType = String(field?.type || '').toLowerCase()
    return field?.['is-range'] === true || temporalType.includes('range') ? null : 'TEXT'
  }
  return null
}

/**
 * 从 ServiceTask 的受控 Flowable Field 回读作者扩展键和配置。
 * @param {object} businessObject 当前 BPMN 业务对象。
 * @returns {{extensionKey: string, extensionConfig: string}} 稳定作者配置；缺失时使用空键和空对象。
 */
function readServiceTaskExtension(businessObject) {
  const result = { extensionKey: '', extensionConfig: '{}' }
  for (const value of businessObject?.extensionElements?.values || []) {
    if (value?.$type !== 'flowable:Field') continue
    if (value.name === EXTENSION_KEY_FIELD) result.extensionKey = value.stringValue || ''
    if (value.name === EXTENSION_CONFIG_FIELD) result.extensionConfig = value.stringValue || '{}'
  }
  return result
}

/**
 * 从 moddle 监听器集合回读固定业务监听器，系统审计和未知实现不会进入可编辑状态。
 * @param {object} businessObject 当前 BPMN 元素业务对象。
 * @param {string} listenerType flowable:ExecutionListener 或 flowable:TaskListener。
 * @returns {Array<object>} 包含 event、extensionKey 和 config 的业务监听器数组。
 */
function readBusinessListeners(businessObject, listenerType) {
  return (businessObject?.extensionElements?.values || [])
    .filter(listener => listener?.$type === listenerType)
    .filter(listener => listener?.delegateExpression === BUSINESS_LISTENER_DELEGATE_EXPRESSION)
    .map(listener => {
      const fields = Array.isArray(listener.fields) ? listener.fields : []
      const keyField = fields.find(field => field?.name === EXTENSION_KEY_FIELD)
      const configField = fields.find(field => field?.name === EXTENSION_CONFIG_FIELD)
      return {
        event: listener.event || '',
        extensionKey: keyField?.stringValue || '',
        config: configField?.stringValue || '{}'
      }
    })
}

/**
 * 从当前 BPMN 元素的 extensionElements 回读 Flowable FormData。
 * @param {object} businessObject StartEvent 或 UserTask 的 moddle 业务对象。
 * @returns {Array<object>} 可供字段编辑器使用的确定性字段列表。
 */
function readEmbeddedFormFields(businessObject) {
  const extensionValues = businessObject?.extensionElements?.values || []
  return extensionValues
    .filter(value => value?.$type === 'flowable:FormProperty')
    .map(property => ({
      id: property.id || '',
      variable: property.variable || '',
      name: property.name || property.id || property.variable || '',
      type: String(property.type || 'string').toLowerCase(),
      required: property.required === true,
      readable: property.readable !== false,
      writable: property.writable !== false,
      datePattern: property.datePattern || '',
      values: (property.values || []).map(value => ({
        id: value.id || '',
        name: value.name || value.id || ''
      }))
    }))
}

/**
 * 判断循环配置是否引用平台受控多实例 handler；完整结构仍由保存门禁单独核验。
 * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
 * @returns {boolean} 集合表达式精确引用动态或固定人员 handler 时返回 true。
 */
function isControlledMultiInstanceLoop(loop) {
  const collection = String(loop?.get?.('flowable:collection') || '').trim()
  return collection === CONTROLLED_MULTI_INSTANCE_COLLECTION
    || FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(collection)
}

/**
 * 判断受控多实例是否使用设计时固定人员来源。
 * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
 * @returns {boolean} 集合表达式命中固定人员 handler 时返回 true。
 */
function isFixedMultiInstanceLoop(loop) {
  return Boolean(loop && FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(
    String(loop.get?.('flowable:collection') || '').trim()
  ))
}

/**
 * 从固定人员 handler 表达式读取会签或或签成员，并保留设计时选择顺序。
 * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
 * @returns {string[]} 已去重的用户主键数组；表达式不符合受控契约时返回空数组。
 */
function readFixedMultiInstanceUserIds(loop) {
  const collection = String(loop?.get?.('flowable:collection') || '').trim()
  const match = collection.match(FIXED_MULTI_INSTANCE_COLLECTION_PATTERN)
  return match ? splitValues(match[1]) : []
}

/**
 * 将设计时选定的固定成员转换为后端白名单接受的 Flowable EL 表达式。
 * @param {string[]} userIds 已去重且为正整数的固定办理人主键。
 * @returns {string} 仅含数字用户主键的受控固定成员集合表达式。
 */
function fixedMultiInstanceCollectionExpression(userIds) {
  return `\${multiInstanceHandler.getFixedUserIds(execution, '${userIds.join(',')}')}`
}

/**
 * 将 Flowable 逗号分隔身份列表转换为去重数组。
 * @param {unknown} value Flowable 属性值。
 * @returns {string[]} 去除空值后的身份数组。
 */
function splitValues(value) {
  return [...new Set(String(value || '').split(',').map(item => item.trim()).filter(Boolean))]
}

/**
 * 判断当前业务对象是否为指定 BPMN 类型。
 * @param {string} type BPMN 类型名。
 * @returns {boolean} 类型匹配时为 true。
 */
function isType(type) {
  return Boolean(selectedBusinessObject.value?.$instanceOf(type))
}

/**
 * 将 BPMN 类型转换为中文属性面板标题。
 * @param {string|undefined} type BPMN 类型。
 * @returns {string} 可读类型名称。
 */
function typeLabel(type) {
  const labels = {
    'bpmn:Process': '流程属性',
    'bpmn:Participant': '池 / 参与者',
    'bpmn:StartEvent': '开始节点',
    'bpmn:EndEvent': '结束节点',
    'bpmn:UserTask': '用户任务',
    'bpmn:ServiceTask': '服务任务',
    'bpmn:ManualTask': '手工任务',
    'bpmn:ReceiveTask': '接收任务',
    'bpmn:SendTask': '发送任务',
    'bpmn:BusinessRuleTask': '业务规则任务',
    'bpmn:CallActivity': '调用活动',
    'bpmn:ExclusiveGateway': '排他网关',
    'bpmn:ParallelGateway': '并行网关',
    'bpmn:InclusiveGateway': '包容网关',
    'bpmn:EventBasedGateway': '事件网关',
    'bpmn:ComplexGateway': '复杂网关',
    'bpmn:SequenceFlow': '顺序流',
    'bpmn:SubProcess': '子流程',
    'bpmn:Transaction': '事务子流程',
    'bpmn:IntermediateCatchEvent': '捕获事件',
    'bpmn:IntermediateThrowEvent': '抛出事件',
    'bpmn:BoundaryEvent': '边界事件'
  }
  return labels[type] || '元素属性'
}

/**
 * 更新当前元素的 bpmn-js 属性并形成可撤销命令。
 * @param {object} changes 待更新属性映射。
 * @returns {void} 未选中元素时不执行。
 */
function updateProperties(changes) {
  if (designerLocked.value || !modeler || !selectedElement.value) return
  modeler.get('modeling').updateProperties(selectedElement.value, changes)
}

/**
 * 更新元素名称。
 * @returns {void} 无返回值。
 */
function updateCommonProperties() {
  updateProperties({ name: propertyState.name.trim() || undefined })
}

/**
 * 校验并更新 BPMN 元素标识。
 * @returns {void} 非法标识会回滚为业务对象原值并报告错误。
 */
function updateElementId() {
  const id = propertyState.id.trim()
  if (!/^[A-Za-z_][A-Za-z0-9_.-]{0,127}$/.test(id)) {
    propertyState.id = selectedBusinessObject.value?.id || ''
    emit('error', new Error('元素标识格式不合法'))
    return
  }
  updateProperties({ id })
}

/**
 * 更新流程可执行状态。
 * @returns {void} 无返回值。
 */
function updateProcessProperties() {
  updateProperties({
    isExecutable: Boolean(propertyState.executable),
    'flowable:versionTag': propertyState.versionTag.trim() || undefined
  })
}

/**
 * 更新 Participant 绑定的可执行流程定义 key；空值由后端协作部署门禁拒绝。
 * @returns {void} 通过 bpmn-js 命令栈保存池与流程定义的真实关系。
 */
function updateParticipantProperties() {
  updateProperties({ processRef: propertyState.processRef.trim() || undefined })
}

/**
 * 切换正式模板或内嵌 FormData，并在一条命令中清理另一来源。
 * @returns {void} 来源非法或当前元素不支持表单时恢复 BPMN 原值。
 */
function updateFormSource() {
  if (!['TEMPLATE', 'EMBEDDED'].includes(propertyState.formSource) || !propertyFlags.value.formSupported) {
    loadPropertyState(selectedElement.value)
    return
  }
  if (propertyState.formSource === 'EMBEDDED' && !propertyState.embeddedFields.length) {
    // 空 FormData 无法在 XML 中表达来源；切换时创建首个合法字段，后续可继续编辑或删除。
    propertyState.embeddedFields = [{
      id: 'field1', variable: '', name: '字段 1', type: 'string', required: false,
      readable: true, writable: true, datePattern: '', values: []
    }]
  }
  syncFormDefinition()
}

/**
 * 更新开始节点或用户任务的正式表单键，并确保不存在内嵌字段。
 * @returns {void} 当前来源不是正式模板时不执行。
 */
function updateFormKey() {
  if (propertyState.formSource !== 'TEMPLATE') return
  syncFormDefinition()
}

/**
 * 接收字段编辑器的完整值，执行与后端一致的即时门禁后写入 BPMN。
 * @param {Array<object>} fields 用户编辑后的完整内嵌字段列表。
 * @returns {void} 校验失败时恢复当前 BPMN 值并触发 error。
 */
function updateEmbeddedForm(fields) {
  try {
    validateEmbeddedFormFields(fields)
    propertyState.embeddedFields = fields.map(field => ({
      ...field,
      values: (field.values || []).map(value => ({ ...value }))
    }))
    propertyState.formSource = 'EMBEDDED'
    syncFormDefinition()
  } catch (error) {
    loadPropertyState(selectedElement.value)
    emit('error', error)
  }
}

/**
 * 校验内嵌字段数量、变量、类型、日期格式和静态枚举完整性。
 * @param {Array<object>} fields 待写入 BPMN 的内嵌字段列表。
 * @returns {void} 任一字段违反后端协议时抛出业务错误。
 */
function validateEmbeddedFormFields(fields) {
  if (!Array.isArray(fields) || !fields.length || fields.length > 500) {
    throw new Error('内嵌表单必须包含 1 至 500 个字段')
  }
  const fieldIds = new Set()
  const variables = new Set()
  for (const field of fields) {
    const fieldId = String(field?.id || '')
    if (!EMBEDDED_FORM_VARIABLE_PATTERN.test(fieldId) || fieldIds.has(fieldId)) {
      throw new Error('内嵌表单字段标识非法或重复')
    }
    fieldIds.add(fieldId)
    const configuredVariable = String(field?.variable || '')
    const variable = configuredVariable || fieldId
    const reserved = EMBEDDED_FORM_RESERVED_VARIABLES.has(variable)
      || EMBEDDED_FORM_RESERVED_PREFIXES.some(prefix => variable.startsWith(prefix))
    if ((configuredVariable && configuredVariable !== configuredVariable.trim())
      || !EMBEDDED_FORM_VARIABLE_PATTERN.test(variable) || reserved || variables.has(variable)) {
      throw new Error('内嵌表单变量名非法、重复或属于保留变量')
    }
    variables.add(variable)
    if (!String(field.name || '').trim() || String(field.name).trim().length > 255) {
      throw new Error('内嵌表单字段名称不能为空且不能超过 255 个字符')
    }
    const customType = String(field.type || '').startsWith('custom:')
      && formFieldOptions.value.some(option => `custom:${option.extensionKey}` === field.type)
    if (!EMBEDDED_FORM_TYPES.includes(field.type) && !customType) {
      throw new Error(`内嵌表单字段类型不受支持: ${field.type || ''}`)
    }
    if (field.type === 'date' && !EMBEDDED_FORM_DATE_PATTERN.test(String(field.datePattern || '').trim())) {
      throw new Error('内嵌表单日期格式不合法')
    }
    if (field.type !== 'enum') continue
    const values = field.values || []
    if (!values.length || values.length > 500) throw new Error('内嵌枚举字段必须配置 1 至 500 个静态选项')
    const optionIds = new Set()
    for (const value of values) {
      const id = String(value?.id || '')
      const name = String(value?.name || '').trim()
      if (!id || id !== id.trim() || id.length > 255 || optionIds.has(id) || !name || name.length > 255) {
        throw new Error('内嵌枚举选项非法或重复')
      }
      optionIds.add(id)
    }
  }
}

/**
 * 把一个已校验字段转换为 Flowable FormProperty moddle 对象。
 * @param {object} field 字段编辑器中的确定性字段值。
 * @returns {object} 可放入 bpmn:ExtensionElements.values 的 FormProperty。
 */
function createEmbeddedFormProperty(field) {
  const moddle = modeler.get('moddle')
  const values = field.type === 'enum'
    ? field.values.map(value => moddle.create('flowable:Value', {
      id: value.id,
      name: value.name.trim()
    }))
    : []
  return moddle.create('flowable:FormProperty', {
    id: field.id,
    variable: field.variable?.trim() || undefined,
    name: field.name.trim(),
    type: field.type,
    required: Boolean(field.required && field.writable),
    readable: Boolean(field.readable),
    writable: Boolean(field.writable),
    datePattern: field.type === 'date' ? field.datePattern.trim() : undefined,
    values
  })
}

/**
 * 同步当前表单定义，保留审计监听器等非表单扩展并原子互斥两种来源。
 * @returns {void} 未选中可配置元素或设计器锁定时不执行。
 */
function syncFormDefinition() {
  if (designerLocked.value || !modeler || !selectedElement.value || !propertyFlags.value.formSupported) return
  const businessObject = selectedBusinessObject.value
  const preservedValues = (businessObject.extensionElements?.values || [])
    .filter(value => value?.$type !== 'flowable:FormProperty')
  const formValues = propertyState.formSource === 'EMBEDDED'
    ? propertyState.embeddedFields.map(createEmbeddedFormProperty)
    : []
  const extensionValues = [...formValues, ...preservedValues]
  const extensionElements = extensionValues.length
    ? modeler.get('moddle').create('bpmn:ExtensionElements', { values: extensionValues })
    : undefined
  modeler.get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
    'flowable:formKey': propertyState.formSource === 'TEMPLATE'
      ? propertyState.formKey || undefined
      : undefined,
    extensionElements
  })
}

/**
 * 按办理方式互斥写入办理人、候选用户或候选组。
 * @returns {void} 无返回值。
 */
function updateAssignment() {
  if (propertyState.multiInstanceType === 'controlled') return
  // 当前办理方式是尚未选择具体身份时唯一的编辑态，不能依赖 BPMN 空属性反推。
  const assignmentType = propertyState.assignmentType
  updateProperties({
    'flowable:assignee': assignmentType === 'assignee' ? propertyState.assignee || undefined : undefined,
    'flowable:candidateUsers': assignmentType === 'users' ? propertyState.candidateUsers.join(',') || undefined : undefined,
    'flowable:candidateGroups': assignmentType === 'groups' ? propertyState.candidateGroups.join(',') || undefined : undefined
  })
  // bpmn-js 会同步触发属性回读；候选值为空时需恢复用户显式选择的办理方式。
  propertyState.assignmentType = assignmentType
}

/**
 * 更新用户任务到期时间和优先级表达式。
 * @returns {void} 无返回值。
 */
function updateUserTaskProperties() {
  updateProperties({
    'flowable:dueDate': propertyState.dueDate.trim() || undefined,
    'flowable:priority': propertyState.priority.trim() || undefined,
    'flowable:category': propertyState.taskCategory.trim() || undefined,
    'flowable:skipExpression': propertyState.skipExpression.trim() || undefined,
    'flowable:localScope': Boolean(propertyState.localScope)
  })
}

/**
 * 将服务任务扩展键和 JSON 配置写入作者 XML，并固定为系统调度器。
 * @returns {void} 校验失败时恢复当前 BPMN 值并触发 error。
 */
function updateServiceTask() {
  try {
    const extensionKey = propertyState.extensionKey.trim()
    const configText = propertyState.extensionConfig.trim() || '{}'
    const config = JSON.parse(configText)
    if (!config || Array.isArray(config) || typeof config !== 'object') {
      throw new Error('处理器配置必须是 JSON 对象')
    }
    const businessObject = selectedBusinessObject.value
    const preservedValues = (businessObject.extensionElements?.values || []).filter(value => (
      value?.$type !== 'flowable:Field'
      || ![EXTENSION_KEY_FIELD, EXTENSION_CONFIG_FIELD].includes(value.name)
    ))
    const moddle = modeler.get('moddle')
    const extensionValues = extensionKey
      ? [
          moddle.create('flowable:Field', { name: EXTENSION_KEY_FIELD, stringValue: extensionKey }),
          moddle.create('flowable:Field', { name: EXTENSION_CONFIG_FIELD, stringValue: JSON.stringify(config) }),
          ...preservedValues
        ]
      : preservedValues
    const extensionElements = extensionValues.length
      ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
      : undefined
    modeler.get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
      'flowable:class': undefined,
      'flowable:delegateExpression': extensionKey ? EXTENSION_DELEGATE_EXPRESSION : undefined,
      'flowable:expression': undefined,
      'flowable:resultVariable': undefined,
      extensionElements
    })
  } catch (error) {
    loadPropertyState(selectedElement.value)
    emit('error', error)
  }
}

/**
 * 切换受控扩展类型时建立与服务端 Schema 一致的初始配置。
 * @returns {void} 更新编辑状态并通过命令栈写入作者 BPMN。
 */
function updateExtensionSelection() {
  const selectedOption = extensionOptions.value.find(option => (
    option.extensionKey === propertyState.extensionKey
  ))
  if (selectedOption?.extensionType === 'CEL') {
    propertyState.extensionConfig = JSON.stringify(CEL_DEFAULT_CONFIG)
  } else if (selectedOption?.extensionType === 'HTTP') {
    const endpoint = connectorEndpoints.value[0]
    // HTTP 外部副作用必须交给 Flowable Job 重试；该技术约束由系统自动维护。
    propertyState.asyncBefore = true
    updateProperties({ 'flowable:async': true })
    propertyState.extensionConfig = JSON.stringify({
      endpointKey: endpoint?.endpointKey || '',
      method: String(endpoint?.allowedMethods || '').split(',').filter(Boolean)[0] || '',
      path: endpoint?.pathPrefix || '/'
    })
  } else if (selectedOption?.implementationKey === 'COLLABORATION_OUTBOX_V1') {
    const endpoint = connectorEndpoints.value[0]
    propertyState.extensionConfig = JSON.stringify({
      endpointKey: endpoint?.endpointKey || '',
      path: '/workflow/runtime-event/collaboration/message',
      messageName: '',
      targetProcessDefinitionKey: '',
      variableNames: [],
      maxAttempts: 5
    })
  } else if (selectedOption?.extensionType === 'SQL') {
    const source = sqlDataSources.value[0]
    propertyState.extensionConfig = JSON.stringify({
      dataSourceKey: source?.dataSourceKey || '',
      sql: '',
      parameters: {},
      maxRows: 100
    })
  } else if (selectedOption?.implementationKey === 'RAISE_BPMN_EVENT') {
    propertyState.extensionConfig = JSON.stringify({
      eventType: 'ERROR',
      eventCode: errorEventOptions.value[0]?.eventCode || '',
      sourceType: 'SERVICE_TASK',
      operator: 'ALWAYS'
    })
  } else {
    propertyState.extensionConfig = '{}'
  }
  updateServiceTask()
}

/**
 * 将业务规则任务绑定到一个服务端目录中的精确 DMN 来源版本。
 * @returns {void} 非 BusinessRuleTask、未知 decisionId 或多值引用会恢复当前 BPMN 状态。
 */
function updateDmnDecision() {
  if (!propertyFlags.value.businessRuleTask) return
  const decisionId = String(propertyState.dmnDecisionId || '').trim()
  const selected = dmnOptions.value.find(option => option.decisionId === decisionId)
  if (!selected || decisionId.includes(',')) {
    loadPropertyState(selectedElement.value)
    emit('error', new Error('请选择一个正式 DMN 决策精确版本'))
    return
  }
  updateProperties({
    'flowable:rules': decisionId,
    'flowable:class': undefined,
    'flowable:ruleVariablesInput': undefined,
    'flowable:exclude': false
  })
}

/**
 * 从正式后端加载每个 DMN key 的最新来源版本供设计器选择。
 * @returns {Promise<void>} 失败时清空选项并向页面上报，不使用本地回退目录。
 */
async function loadDmnOptions() {
  dmnLoading.value = true
  try {
    const response = await listDmnDecisionOptions()
    dmnOptions.value = Array.isArray(response?.data) ? response.data : []
  } catch (error) {
    dmnOptions.value = []
    emit('error', error)
  } finally {
    dmnLoading.value = false
  }
}

/**
 * 从正式后端加载可选择的 Java、CEL、HTTP 扩展和 HTTP 端点白名单。
 * @returns {Promise<void>} 请求完成后更新扩展选项；失败时不提供本地伪造回退。
 */
async function loadExtensionOptions() {
  extensionLoading.value = true
  eventCodeLoading.value = true
  slaLoading.value = true
  try {
    const [javaResponse, celResponse, httpResponse, sqlResponse, formFieldResponse, endpointResponse, sqlSourceResponse, errorCodeResponse, escalationCodeResponse, calendarResponse] = await Promise.all([
      listJavaExtensionOptions(),
      listCelExtensionOptions(),
      listHttpExtensionOptions(),
      listSqlExtensionOptions(),
      listFormFieldExtensionOptions(),
      listConnectorEndpointOptions(),
      listSqlDataSourceOptions(),
      listBpmnEventCodeOptions('ERROR'),
      listBpmnEventCodeOptions('ESCALATION'),
      listEnabledSlaCalendars()
    ])
    extensionOptions.value = [
      ...(Array.isArray(javaResponse?.data) ? javaResponse.data : []),
      ...(Array.isArray(celResponse?.data) ? celResponse.data : []),
      ...(Array.isArray(httpResponse?.data) ? httpResponse.data : []),
      ...(Array.isArray(sqlResponse?.data) ? sqlResponse.data : [])
    ]
    formFieldOptions.value = Array.isArray(formFieldResponse?.data) ? formFieldResponse.data : []
    connectorEndpoints.value = Array.isArray(endpointResponse?.data) ? endpointResponse.data : []
    sqlDataSources.value = Array.isArray(sqlSourceResponse?.data) ? sqlSourceResponse.data : []
    errorEventOptions.value = Array.isArray(errorCodeResponse?.data) ? errorCodeResponse.data : []
    escalationEventOptions.value = Array.isArray(escalationCodeResponse?.data) ? escalationCodeResponse.data : []
    slaCalendarOptions.value = Array.isArray(calendarResponse?.data) ? calendarResponse.data : []
  } catch (error) {
    extensionOptions.value = []
    formFieldOptions.value = []
    connectorEndpoints.value = []
    sqlDataSources.value = []
    errorEventOptions.value = []
    escalationEventOptions.value = []
    slaCalendarOptions.value = []
    emit('error', error)
  } finally {
    extensionLoading.value = false
    eventCodeLoading.value = false
    slaLoading.value = false
  }
}

/**
 * 更新活动的 Flowable 异步作业属性和标准补偿标志。
 * @returns {void} 所有字段通过 bpmn-js 命令栈写入并支持撤销、重做。
 */
function updateActivityProperties() {
  updateProperties({
    'flowable:async': Boolean(propertyState.asyncBefore),
    'flowable:asyncLeave': Boolean(propertyState.asyncAfter),
    'flowable:exclusive': Boolean(propertyState.exclusive),
    isForCompensation: Boolean(propertyState.forCompensation)
  })
}

/**
 * 更新当前 FlowNode 的受控执行监听器。
 * @param {Array<object>} listeners 属性面板提交的事件、扩展键和 JSON 配置数组。
 * @returns {void} 配置合法时通过 moddle 命令栈写入，异常时恢复当前 BPMN 状态。
 */
function updateBusinessExecutionListeners(listeners) {
  updateBusinessListeners('EXECUTION', listeners)
}

/**
 * 更新当前 UserTask 的受控任务监听器，同时保留系统身份审计监听器。
 * @param {Array<object>} listeners 属性面板提交的事件、扩展键和 JSON 配置数组。
 * @returns {void} 配置合法时通过 moddle 命令栈写入，异常时恢复当前 BPMN 状态。
 */
function updateBusinessTaskListeners(listeners) {
  updateBusinessListeners('TASK', listeners)
}

/**
 * 校验并写入当前流程或元素的 Flowable 通用扩展属性。
 * @param {Array<{name:string,value:string}>} properties 属性编辑器提交的完整名值列表。
 * @returns {void} 非法名称、重复名称、超长值或超量输入会恢复当前 XML 并上报错误。
 */
function updateExtensionProperties(properties) {
  if (designerLocked.value || !modeler || !selectedElement.value) return
  try {
    if (!Array.isArray(properties) || properties.length > 32) {
      throw new Error('单个元素最多允许 32 个扩展属性')
    }
    const normalized = properties.map(item => ({
      name: String(item?.name || '').trim(),
      value: String(item?.value || '')
    }))
    const names = new Set()
    for (const item of normalized) {
      if (!EXTENSION_PROPERTY_NAME_PATTERN.test(item.name) || item.name.startsWith('approva.')) {
        throw new Error('扩展属性名必须为合法非保留标识')
      }
      if (item.value.length > 1024) throw new Error('扩展属性值不能超过 1024 个字符')
      if (names.has(item.name)) throw new Error('同一元素的扩展属性名不能重复')
      names.add(item.name)
    }
    const businessObject = selectedBusinessObject.value
    const platformProperties = readAllFlowableProperties(businessObject)
      .filter(item => CONTROLLED_LOOP_PROPERTY_NAMES.has(item.name)
        || SLA_PROPERTY_NAME_SET.has(item.name))
    const extensionElements = buildPropertiesExtensionElements(
      businessObject, normalized, platformProperties)
    modeler.get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
      extensionElements
    })
    propertyState.extensionProperties = normalized
  } catch (error) {
    loadPropertyState(selectedElement.value)
    emit('error', error)
  }
}

/**
 * 读取当前元素全部 Flowable 通用属性，包含平台受控属性。
 * @param {object} businessObject 当前 BPMN 流程或元素业务对象。
 * @returns {Array<{name:string,value:string}>} 保持 XML 顺序的属性列表。
 */
function readAllFlowableProperties(businessObject) {
  return (businessObject?.extensionElements?.values || [])
    .filter(value => value?.$type === 'flowable:Properties')
    .flatMap(container => (container.values || []).map(property => ({
      name: String(property.name || ''),
      value: String(property.value ?? '')
    })))
}

/**
 * 合并普通扩展属性与受控循环固定属性，同时保留表单、监听器和其他扩展元素。
 * @param {object} businessObject 当前 BPMN 元素业务对象。
 * @param {Array<{name:string,value:string}>} editableProperties 用户可编辑的非保留属性。
 * @param {Array<{name:string,value:string}>} controlledProperties 平台生成的受控循环属性。
 * @returns {object|undefined} 可写入业务对象的 bpmn:ExtensionElements。
 */
function buildPropertiesExtensionElements(businessObject, editableProperties, controlledProperties) {
  const moddle = modeler.get('moddle')
  const preservedValues = (businessObject.extensionElements?.values || [])
    .filter(value => value?.$type !== 'flowable:Properties')
  const combined = [...editableProperties, ...controlledProperties]
  const propertyContainer = combined.length
    ? moddle.create('flowable:Properties', {
        values: combined.map(item => moddle.create('flowable:Property', item))
      })
    : undefined
  const extensionValues = propertyContainer ? [...preservedValues, propertyContainer] : preservedValues
  return extensionValues.length
    ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
    : undefined
}

/**
 * 将属性面板状态转换为后端白名单要求的完整受控循环固定属性。
 * @returns {Array<{name:string,value:string}>} 按稳定顺序生成的五项平台属性。
 */
function controlledLoopPropertyItems() {
  return [
    { name: CONTROLLED_LOOP_PROPERTIES.enabled, value: 'true' },
    { name: CONTROLLED_LOOP_PROPERTIES.decisionVariable, value: propertyState.controlledLoopDecisionVariable.trim() },
    { name: CONTROLLED_LOOP_PROPERTIES.repeatValue, value: propertyState.controlledLoopRepeatValue.trim() },
    { name: CONTROLLED_LOOP_PROPERTIES.exitValue, value: propertyState.controlledLoopExitValue.trim() },
    { name: CONTROLLED_LOOP_PROPERTIES.maxIterations, value: String(propertyState.controlledLoopMaxIterations) }
  ]
}

/**
 * 校验并写入固定 Bean 业务监听器，禁止重复事件、未知目录和非对象 JSON 配置。
 * @param {'EXECUTION'|'TASK'} kind 监听器种类，决定 moddle 类型和目标属性。
 * @param {Array<object>} listeners 待写入的业务监听器编辑值。
 * @returns {void} 无返回值；失败时向页面上报稳定错误并回读原状态。
 */
function updateBusinessListeners(kind, listeners) {
  if (designerLocked.value || !modeler || !selectedElement.value) return
  try {
    const allowedEvents = kind === 'TASK'
      ? new Set(['create', 'assignment', 'complete', 'delete'])
      : new Set(['start', 'end', 'take'])
    const seenEvents = new Set()
    const moddle = modeler.get('moddle')
    const generated = (Array.isArray(listeners) ? listeners : []).map(listener => {
      const event = String(listener?.event || '').trim()
      const extensionKey = String(listener?.extensionKey || '').trim()
      if (!allowedEvents.has(event) || !seenEvents.add(event)) {
        throw new Error('同一元素的业务监听器事件必须合法且唯一')
      }
      const option = businessListenerOptions.value.find(item => item.extensionKey === extensionKey)
      if (!option) throw new Error('请选择已启用的 Java 业务监听处理器')
      const config = JSON.parse(String(listener?.config || '{}'))
      if (!config || Array.isArray(config) || typeof config !== 'object') {
        throw new Error('业务监听器配置必须是 JSON 对象')
      }
      return moddle.create(kind === 'TASK' ? 'flowable:TaskListener' : 'flowable:ExecutionListener', {
        event,
        delegateExpression: BUSINESS_LISTENER_DELEGATE_EXPRESSION,
        fields: [
          moddle.create('flowable:Field', { name: EXTENSION_KEY_FIELD, stringValue: extensionKey }),
          moddle.create('flowable:Field', { name: EXTENSION_CONFIG_FIELD, stringValue: JSON.stringify(config) })
        ]
      })
    })
    const businessObject = selectedBusinessObject.value
    const listenerType = kind === 'TASK' ? 'flowable:TaskListener' : 'flowable:ExecutionListener'
    const preserved = (businessObject.extensionElements?.values || []).filter(value => (
      value?.$type !== listenerType
      || value?.delegateExpression !== BUSINESS_LISTENER_DELEGATE_EXPRESSION
    ))
    const extensionValues = [...preserved, ...generated]
    const extensionElements = extensionValues.length
      ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
      : undefined
    modeler.get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
      extensionElements
    })
  } catch (error) {
    loadPropertyState(selectedElement.value)
    emit('error', error)
  }
}

/**
 * 更新调用活动的标准流程引用和 Flowable 实例关联属性。
 * @returns {void} 空值会删除对应属性，部署门禁负责校验必填引用是否存在。
 */
function updateCallActivityProperties() {
  updateProperties({
    calledElement: propertyState.calledElement.trim() || undefined,
    'flowable:businessKey': propertyState.businessKey.trim() || undefined,
    'flowable:processInstanceName': propertyState.processInstanceName.trim() || undefined
  })
}

/**
 * 返回引用型事件的根元素类型、引用属性和业务键字段。
 * @param {string} eventDefinitionType BPMN 事件定义类型。
 * @returns {object|undefined} 引用配置；非引用型事件返回 undefined。
 */
function eventReferenceConfig(eventDefinitionType) {
  return {
    'bpmn:MessageEventDefinition': {
      rootType: 'bpmn:Message', referenceProperty: 'messageRef', keyProperty: 'name', idPrefix: 'Message'
    },
    'bpmn:SignalEventDefinition': {
      rootType: 'bpmn:Signal', referenceProperty: 'signalRef', keyProperty: 'name', idPrefix: 'Signal'
    },
    'bpmn:ErrorEventDefinition': {
      rootType: 'bpmn:Error', referenceProperty: 'errorRef', keyProperty: 'errorCode', idPrefix: 'Error'
    },
    'bpmn:EscalationEventDefinition': {
      rootType: 'bpmn:Escalation', referenceProperty: 'escalationRef', keyProperty: 'escalationCode', idPrefix: 'Escalation'
    }
  }[eventDefinitionType]
}

/**
 * 沿 moddle 父级查找 Definitions，供事件引用登记为 BPMN 根元素。
 * @param {object|undefined} businessObject 当前事件业务对象。
 * @returns {object|undefined} BPMN Definitions；找不到时返回 undefined。
 */
function findDefinitions(businessObject) {
  let current = businessObject
  while (current && current.$type !== 'bpmn:Definitions') current = current.$parent
  return current
}

/**
 * 将业务键转换为稳定且合法的 BPMN 根元素标识片段。
 * @param {string} value 消息、信号、错误或升级的业务键。
 * @returns {string} 可用于 BPMN id 的非空片段。
 */
function eventReferenceIdPart(value) {
  const normalized = value.replace(/[^A-Za-z0-9_.-]/g, '_').replace(/^[^A-Za-z_]+/, '')
  return normalized || 'Reference'
}

/**
 * 查找或创建消息、信号、错误、升级根元素，并通过命令栈登记到 Definitions。
 * @param {object} eventDefinition 当前事件定义。
 * @param {object} config 引用类型配置。
 * @param {string} key 用户维护的稳定业务键。
 * @returns {object|undefined} 可写入事件定义的根元素；空键返回 undefined。
 */
function resolveEventRootReference(eventDefinition, config, key) {
  if (!key) return undefined
  const definitions = findDefinitions(eventDefinition)
  if (!definitions) return undefined
  const roots = Array.isArray(definitions.rootElements) ? definitions.rootElements : []
  const existing = roots.find(root => root.$type === config.rootType
    && (root[config.keyProperty] === key || root.name === key || root.id === key))
  if (existing) return existing
  const reference = modeler.get('moddle').create(config.rootType, {
    id: `${config.idPrefix}_${eventReferenceIdPart(key)}`,
    [config.keyProperty]: key,
    ...(config.keyProperty === 'name' ? {} : { name: key })
  })
  modeler.get('modeling').updateModdleProperties(selectedElement.value, definitions, {
    rootElements: [...roots, reference]
  })
  return reference
}

/**
 * 更新当前事件定义的根引用、定时表达式和边界中断语义。
 * @returns {void} 事件内部对象使用 updateModdleProperties，确保 XML 与撤销栈一致。
 */
function updateEventProperties() {
  if (designerLocked.value || !modeler || !selectedElement.value) return
  const businessObject = selectedBusinessObject.value
  const eventDefinition = businessObject?.eventDefinitions?.[0]
  if (isType('bpmn:BoundaryEvent')) {
    updateProperties({ cancelActivity: Boolean(propertyState.cancelActivity) })
  }
  if (!eventDefinition) return
  const modeling = modeler.get('modeling')
  const config = eventReferenceConfig(eventDefinition.$type)
  if (config) {
    const key = propertyState.eventReference.trim()
    const reference = resolveEventRootReference(eventDefinition, config, key)
    modeling.updateModdleProperties(selectedElement.value, eventDefinition, {
      [config.referenceProperty]: reference
    })
    return
  }
  if (eventDefinition.$type !== 'bpmn:TimerEventDefinition') return
  const timerType = ['timeDate', 'timeDuration', 'timeCycle'].includes(propertyState.timerDefinitionType)
    ? propertyState.timerDefinitionType
    : 'timeDuration'
  const expression = propertyState.timerDefinition.trim()
  const formalExpression = expression
    ? modeler.get('moddle').create('bpmn:FormalExpression', { body: expression })
    : undefined
  modeling.updateModdleProperties(selectedElement.value, eventDefinition, {
    timeDate: timerType === 'timeDate' ? formalExpression : undefined,
    timeDuration: timerType === 'timeDuration' ? formalExpression : undefined,
    timeCycle: timerType === 'timeCycle' ? formalExpression : undefined
  })
}

/**
 * 创建、更新或删除顺序流条件表达式。
 * @returns {void} 无返回值。
 */
function updateCondition() {
  const expression = propertyState.conditionExpression.trim()
  const moddle = modeler.get('moddle')
  updateProperties({
    conditionExpression: expression
      ? moddle.create('bpmn:FormalExpression', { body: expression })
      : undefined
  })
}

/**
 * 创建、更新或删除 BPMN 文档说明。
 * @returns {void} 无返回值。
 */
function updateDocumentation() {
  const text = propertyState.documentation.trim()
  const moddle = modeler.get('moddle')
  updateProperties({ documentation: text ? [moddle.create('bpmn:Documentation', { text })] : [] })
}

/**
 * 创建、更新或删除活动循环配置；受控模式一次写入完整的动态或固定人员技术契约。
 * @returns {void} 无返回值。
 */
function updateMultiInstance() {
  const existingLoop = selectedBusinessObject.value?.loopCharacteristics
  const wasControlled = isControlledMultiInstanceLoop(existingLoop)
  const wasApprovalLoop = Boolean(readControlledLoop(selectedBusinessObject.value))
  const editableProperties = propertyState.extensionProperties.map(item => ({
    name: String(item.name || '').trim(), value: String(item.value ?? '')
  }))
  // 循环命令栈必须同时携带现有 SLA 平台属性，避免修改循环时删除 UserTask 的超时闭环。
  const slaProperties = readAllFlowableProperties(selectedBusinessObject.value)
    .filter(item => SLA_PROPERTY_NAME_SET.has(item.name))
  const editableWithSla = [...editableProperties, ...slaProperties]
  const clearedControlledExtensions = wasApprovalLoop
    ? buildPropertiesExtensionElements(selectedBusinessObject.value, editableWithSla, [])
    : selectedBusinessObject.value?.extensionElements
  if (propertyState.multiInstanceType === 'approvalLoop') {
    try {
      if (!isUserTask.value) throw new Error('整改循环只能配置在用户任务上')
      const maxIterations = Number(propertyState.controlledLoopMaxIterations)
      const field = controlledLoopFieldOptions.value.find(option => (
        option.value === propertyState.controlledLoopDecisionVariable
      ))
      const repeatValue = propertyState.controlledLoopRepeatValue.trim()
      const exitValue = propertyState.controlledLoopExitValue.trim()
      if (!Number.isInteger(maxIterations) || maxIterations < 2 || maxIterations > 50) {
        throw new Error('最大办理轮次必须是 2 至 50 的整数')
      }
      if (!field) throw new Error('循环判断字段必须来自当前节点正式表单')
      if (!repeatValue || !exitValue || repeatValue.length > 128 || exitValue.length > 128) {
        throw new Error('再次进入和退出条件值必须填写且不能超过 128 个字符')
      }
      if (repeatValue === exitValue) throw new Error('再次进入和退出条件不能相同')
      const extensionElements = buildPropertiesExtensionElements(
        selectedBusinessObject.value, editableWithSla, controlledLoopPropertyItems())
      const changes = { loopCharacteristics: undefined, extensionElements }
      if (wasControlled) resetControlledAssignment(changes)
      updateProperties(changes)
      return
    } catch (error) {
      loadPropertyState(selectedElement.value)
      emit('error', error)
      return
    }
  }
  if (propertyState.multiInstanceType === 'none') {
    const changes = { loopCharacteristics: undefined, extensionElements: clearedControlledExtensions }
    if (wasControlled) resetControlledAssignment(changes)
    updateProperties(changes)
    return
  }
  const moddle = modeler.get('moddle')
  if (propertyState.multiInstanceType === 'standard') {
    const maximumText = propertyState.loopMaximum.trim()
    const maximum = maximumText ? Number(maximumText) : undefined
    if (maximumText && (!Number.isInteger(maximum) || maximum < 1 || maximum > 10000)) {
      loadPropertyState(selectedElement.value)
      emit('error', new Error('最大循环次数必须是 1 至 10000 的整数'))
      return
    }
    const loopCondition = propertyState.loopCondition.trim()
    const standardLoop = moddle.create('bpmn:StandardLoopCharacteristics', {
      testBefore: Boolean(propertyState.testBefore),
      loopMaximum: maximum,
      loopCondition: loopCondition
        ? moddle.create('bpmn:FormalExpression', { body: loopCondition })
        : undefined
    })
    const changes = { loopCharacteristics: standardLoop, extensionElements: clearedControlledExtensions }
    if (wasControlled) resetControlledAssignment(changes)
    updateProperties(changes)
    return
  }
  const controlled = propertyState.multiInstanceType === 'controlled'
  const fixedMemberSource = controlled && propertyState.multiInstanceMemberSource === 'fixed'
  const leavingControlled = !controlled && wasControlled
  let collection = controlled ? CONTROLLED_MULTI_INSTANCE_COLLECTION : propertyState.collection.trim()
  let elementVariable = controlled
    ? CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE
    : propertyState.elementVariable.trim()
  let condition = controlled
    ? propertyState.multiInstanceApprovalMode === 'any'
      ? CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
      : CONTROLLED_MULTI_INSTANCE_ALL_CONDITION
    : propertyState.completionCondition.trim()

  // 固定 handler 不能作为静态集合继续存在，否则属性回读会把刚切换的静态模式再次识别成动态模式。
  if (leavingControlled) {
    collection = ''
    elementVariable = ''
    condition = ''
    propertyState.collection = ''
    propertyState.elementVariable = ''
    propertyState.completionCondition = ''
  }

  if (fixedMemberSource) {
    const userIds = [...new Set(propertyState.fixedMultiInstanceUserIds
      .map(userId => String(userId || '').trim())
      .filter(userId => /^\d+$/.test(userId) && Number(userId) > 0))]
    if (userIds.length < 1 || userIds.length > 100) {
      loadPropertyState(selectedElement.value)
      emit('error', new Error('固定会签或或签办理人必须选择 1 至 100 名有效用户'))
      return
    }
    // 固定名单按属性面板顺序写入白名单表达式，运行时仍由后端核验用户存在、启用状态和多实例结构。
    propertyState.fixedMultiInstanceUserIds = userIds
    collection = fixedMultiInstanceCollectionExpression(userIds)
  }

  // 已导入的静态多实例可能带有后端允许但面板未编辑的标准属性，原位更新可保持其往返完整性。
  if (!controlled && !wasControlled
    && existingLoop?.$type === 'bpmn:MultiInstanceLoopCharacteristics') {
    updateExistingStaticMultiInstance(existingLoop, collection, elementVariable, condition)
    return
  }

  const loop = moddle.create('bpmn:MultiInstanceLoopCharacteristics', {
    isSequential: controlled ? false : propertyState.multiInstanceType === 'sequential',
    completionCondition: condition
      ? moddle.create('bpmn:FormalExpression', { body: condition })
      : undefined
  })
  loop.set('flowable:collection', collection || undefined)
  loop.set('flowable:elementVariable', elementVariable || undefined)
  const changes = { loopCharacteristics: loop, extensionElements: clearedControlledExtensions }
  if (controlled) {
    propertyState.assignmentType = 'assignee'
    propertyState.assignee = CONTROLLED_MULTI_INSTANCE_ASSIGNEE
    propertyState.candidateUsers = []
    propertyState.candidateGroups = []
    propertyState.collection = collection
    propertyState.elementVariable = elementVariable
    propertyState.completionCondition = condition
    Object.assign(changes, {
      'flowable:assignee': CONTROLLED_MULTI_INSTANCE_ASSIGNEE,
      'flowable:candidateUsers': undefined,
      'flowable:candidateGroups': undefined
    })
  } else if (wasControlled) {
    resetControlledAssignment(changes)
  }
  updateProperties(changes)
}

/**
 * 原位更新已导入的静态多实例核心字段，保留 loopCardinality、索引变量及标准数据引用等未编辑属性。
 * @param {object} loop 当前用户任务已有的 bpmn:MultiInstanceLoopCharacteristics。
 * @param {string} collection 静态集合表达式或变量名。
 * @param {string} elementVariable 单个实例使用的元素变量名。
 * @param {string} condition 可选完成条件表达式。
 * @returns {void} 通过 bpmn-js 命令栈更新，可由撤销操作恢复。
 */
function updateExistingStaticMultiInstance(loop, collection, elementVariable, condition) {
  if (designerLocked.value || !modeler || !selectedElement.value) return
  const moddle = modeler.get('moddle')
  modeler.get('modeling').updateModdleProperties(selectedElement.value, loop, {
    isSequential: propertyState.multiInstanceType === 'sequential',
    completionCondition: condition
      ? moddle.create('bpmn:FormalExpression', { body: condition })
      : undefined,
    'flowable:collection': collection || undefined,
    'flowable:elementVariable': elementVariable || undefined
  })
}

/**
 * 离开动态模式时清理仅对 handler 有意义的固定办理人，要求设计者重新选择静态身份。
 * @param {object} changes 本次 bpmn-js 属性更新映射。
 * @returns {void} 同步重置属性面板及待提交属性。
 */
function resetControlledAssignment(changes) {
  propertyState.assignmentType = 'assignee'
  propertyState.assignee = ''
  propertyState.candidateUsers = []
  propertyState.candidateGroups = []
  Object.assign(changes, {
    'flowable:assignee': undefined,
    'flowable:candidateUsers': undefined,
    'flowable:candidateGroups': undefined
  })
}

/**
 * 撤销最近一次设计命令。
 * @returns {void} 无可撤销命令时不执行。
 */
function undo() {
  if (!designerLocked.value && canUndo.value) modeler.get('commandStack').undo()
}

/**
 * 重做最近一次被撤销命令。
 * @returns {void} 无可重做命令时不执行。
 */
function redo() {
  if (!designerLocked.value && canRedo.value) modeler.get('commandStack').redo()
}

/**
 * 将完整流程适配到当前画布。
 * @returns {void} 无返回值。
 */
function fitViewport() {
  if (designerLocked.value || !modeler) return
  const canvas = modeler.get('canvas')
  canvas.zoom('fit-viewport')
  // 浏览器下一帧才能得到缩放后的真实矩形，再据此避开 Palette、小地图和画布边缘。
  window.requestAnimationFrame(() => reserveOverlaySafeArea(canvas))
}

/**
 * 使用 bpmn-js 元素工厂开始高级元素、展开容器或全局连接工具的真实建模命令。
 * @param {object} definition AdvancedElementPalette 提供的标准 BPMN 类型和受控创建提示。
 * @param {MouseEvent} event 菜单项鼠标事件，作为 Modeler 拖放创建起点。
 * @returns {void} 无返回值；设计器锁定或定义不合法时拒绝启动命令。
 */
function createAdvancedElement(definition, event) {
  if (designerLocked.value || !modeler || !definition || !event) return
  if (definition.action === 'global-connect') {
    modeler.get('globalConnect').start(event)
    return
  }
  if (definition.action === 'add-lane') {
    const selected = selectedElement.value
    if (!selected || !['bpmn:Participant', 'bpmn:Lane'].includes(selected.businessObject?.$type)) {
      emit('error', new Error('请先选择池或现有泳道'))
      return
    }
    modeler.get('modeling').addLane(selected, 'bottom')
    return
  }
  if (!/^bpmn:[A-Za-z]+$/.test(definition.type || '')) {
    emit('error', new Error('高级元素类型不合法'))
    return
  }

  const elementFactory = modeler.get('elementFactory')
  const create = modeler.get('create')
  if (definition.participant) {
    create.start(event, elementFactory.createParticipantShape())
    return
  }

  const options = {
    type: definition.type,
    isExpanded: definition.withStartEvent === true,
    triggeredByEvent: definition.triggeredByEvent === true,
    cancelActivity: definition.cancelActivity,
    eventDefinitionType: definition.eventDefinitionType
  }
  if (!definition.withStartEvent) {
    create.start(event, elementFactory.createShape(options))
    return
  }

  // 展开子流程、事件子流程和事务创建时同步放入开始事件，保证容器初始结构可继续建模。
  const container = elementFactory.createShape({ ...options, x: 0, y: 0 })
  const startEvent = elementFactory.createShape({
    type: 'bpmn:StartEvent',
    x: 40,
    y: 82,
    parent: container
  })
  create.start(event, [container, startEvent], { hints: { autoSelect: [container] } })
}

/**
 * 把流程内容缩放并居中到画布浮层之外的安全区域。
 * @param {import('diagram-js/lib/core/Canvas').default} canvas bpmn-js 当前画布服务。
 * @returns {void} 没有可见流程元素或组件已卸载时不调整视口。
 */
function reserveOverlaySafeArea(canvas) {
  if (!modeler || !canvasRef.value) return
  const canvasBounds = canvasRef.value.getBoundingClientRect()
  const paletteBounds = canvasRef.value.querySelector('.djs-palette')?.getBoundingClientRect()
  const minimapBounds = canvasRef.value.querySelector('.djs-minimap')?.getBoundingClientRect()
  // visibleBounds 表示当前缩放后全部图形和连线的屏幕矩形，用于判断是否仍会被浮层遮挡。
  const visibleBounds = [...canvasRef.value.querySelectorAll('.djs-element')]
    .map(element => element.getBoundingClientRect())
    .filter(bounds => bounds.width > 0 && bounds.height > 0)
  if (!visibleBounds.length) return

  const safeLeft = Math.max(canvasBounds.left + 20, (paletteBounds?.right || canvasBounds.left) + 20)
  const safeRight = canvasBounds.right - 20
  const safeTop = canvasBounds.top + 32
  const safeBottom = Math.min(canvasBounds.bottom - 32, (minimapBounds?.top || canvasBounds.bottom) - 20)
  let contentLeft = Math.min(...visibleBounds.map(bounds => bounds.left))
  let contentRight = Math.max(...visibleBounds.map(bounds => bounds.right))
  let contentTop = Math.min(...visibleBounds.map(bounds => bounds.top))
  let contentBottom = Math.max(...visibleBounds.map(bounds => bounds.bottom))
  const safeWidth = Math.max(1, safeRight - safeLeft)
  const safeHeight = Math.max(1, safeBottom - safeTop)
  const contentWidth = Math.max(1, contentRight - contentLeft)
  const contentHeight = Math.max(1, contentBottom - contentTop)
  const safeScale = Math.min(1, safeWidth / contentWidth, safeHeight / contentHeight)

  if (safeScale < 1) {
    canvas.zoom(canvas.zoom() * safeScale * 0.96)
    const resizedBounds = [...canvasRef.value.querySelectorAll('.djs-element')]
      .map(element => element.getBoundingClientRect())
      .filter(bounds => bounds.width > 0 && bounds.height > 0)
    contentLeft = Math.min(...resizedBounds.map(bounds => bounds.left))
    contentRight = Math.max(...resizedBounds.map(bounds => bounds.right))
    contentTop = Math.min(...resizedBounds.map(bounds => bounds.top))
    contentBottom = Math.max(...resizedBounds.map(bounds => bounds.bottom))
  }

  // scroll 使用屏幕像素平移，将内容中心对齐安全区域中心，左右端节点保持同时可见。
  canvas.scroll({
    dx: (safeLeft + safeRight - contentLeft - contentRight) / 2,
    dy: (safeTop + safeBottom - contentTop - contentBottom) / 2
  })
}

/**
 * 按倍率缩放当前画布。
 * @param {number} factor 正数缩放倍率。
 * @returns {void} 无返回值。
 */
function zoomBy(factor) {
  if (designerLocked.value || !modeler) return
  const canvas = modeler.get('canvas')
  canvas.zoom(Math.min(4, Math.max(0.2, canvas.zoom() * factor)))
}

/**
 * 打开系统文件选择器并清除上次选择，保证可连续导入同名 BPMN 文件。
 * @returns {void} 设计器锁定时不执行。
 */
function openImportPicker() {
  if (designerLocked.value || !importInputRef.value) return
  importInputRef.value.value = ''
  importInputRef.value.click()
}

/**
 * 读取不超过 2 MiB 的 XML/BPMN 文件并导入真实 Modeler。
 * @param {Event} event 文件输入框 change 事件。
 * @returns {Promise<void>} 导入完成后同步 XML；非法文件不修改当前画布。
 */
async function handleImportFile(event) {
  const file = event.target?.files?.[0]
  if (!file) return
  if (file.size <= 0 || file.size > 2 * 1024 * 1024) {
    emit('error', new Error('BPMN 文件大小必须在 2 MiB 以内'))
    return
  }
  try {
    const xml = await file.text()
    if (!xml.trim().startsWith('<?xml') && !xml.includes('<definitions')) {
      throw new Error('所选文件不是 BPMN 2.0 XML')
    }
    await importXml(xml)
  } catch (error) {
    emit('error', error)
  }
}

/**
 * 从当前事件定义读取引用或定时表达式，保证属性面板与作者 XML 保持一致。
 * @param {object} businessObject 当前选中 BPMN 元素的业务对象。
 * @returns {void} 非事件或无事件定义时保留空状态。
 */
function loadEventPropertyState(businessObject) {
  const eventDefinition = businessObject.eventDefinitions?.[0]
  if (!eventDefinition) return
  propertyState.eventDefinitionType = eventDefinition.$type || ''
  const referenceConfig = eventReferenceConfig(eventDefinition.$type)
  if (referenceConfig) {
    const reference = eventDefinition[referenceConfig.referenceProperty]
    propertyState.eventReference = reference?.[referenceConfig.keyProperty]
      || reference?.name
      || reference?.id
      || ''
    return
  }
  if (eventDefinition.$type !== 'bpmn:TimerEventDefinition') return
  const timerType = ['timeDate', 'timeDuration', 'timeCycle']
    .find(type => eventDefinition[type]) || 'timeDuration'
  propertyState.timerDefinitionType = timerType
  propertyState.timerDefinition = eventDefinition[timerType]?.body || ''
}

/**
 * 将画布恢复为只包含一个可执行流程的空 BPMN 文档。
 * @returns {Promise<void>} 用户确认后替换当前画布并进入可撤销的新设计状态。
 */
async function clearDiagram() {
  if (designerLocked.value) return
  try {
    await ElMessageBox.confirm('清空后当前未保存的流程元素将丢失。', '清空画布', {
      type: 'warning',
      confirmButtonText: '清空',
      cancelButtonText: '取消'
    })
    const processId = String(props.model.modelKey || 'workflow_process').replace(/[^A-Za-z0-9_.-]/g, '_')
    const processName = escapeXml(props.model.modelName || '新流程')
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  xmlns:flowable="http://flowable.org/bpmn"
  targetNamespace="http://ruoyi.example/workflow">
  <process id="${escapeXml(processId)}" name="${processName}" isExecutable="true" />
  <bpmndi:BPMNDiagram id="BPMNDiagram_${escapeXml(processId)}">
    <bpmndi:BPMNPlane id="BPMNPlane_${escapeXml(processId)}" bpmnElement="${escapeXml(processId)}" />
  </bpmndi:BPMNDiagram>
</definitions>`
    await importXml(xml)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') emit('error', error)
  }
}

/**
 * 对当前选择的可移动图形执行 bpmn-js 原生命令栈对齐。
 * @param {'left'|'center'|'right'|'top'|'middle'|'bottom'} type 对齐方向。
 * @returns {void} 不足两个元素或设计器锁定时不执行。
 */
function alignSelection(type) {
  if (designerLocked.value || !modeler) return
  const elements = modeler.get('selection').get()
  if (elements.length >= 2) modeler.get('alignElements').trigger(elements, type)
}

/**
 * 对当前选择的可移动图形执行 bpmn-js 原生命令栈等距分布。
 * @param {'horizontal'|'vertical'} orientation 水平或垂直分布方向。
 * @returns {void} 不足三个元素或设计器锁定时不执行。
 */
function distributeSelection(orientation) {
  if (designerLocked.value || !modeler) return
  const elements = modeler.get('selection').get()
  if (elements.length >= 3) modeler.get('distributeElements').trigger(elements, orientation)
}

/**
 * 按指定格式导出 BPMN/XML 或当前画布 SVG。
 * @param {'bpmn'|'xml'|'svg'} format 导出格式。
 * @returns {Promise<void>} 下载失败时触发 error。
 */
async function exportDiagram(format) {
  if (designerLocked.value || !modeler) return
  try {
    const name = String(props.model.modelKey || 'workflow').replace(/[^A-Za-z0-9_.-]/g, '_')
    if (format === 'svg') {
      const { svg } = await modeler.saveSVG()
      Download.saveAs(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }), `${name}.svg`)
      return
    }
    const xml = await emitPersistedXml()
    const extension = format === 'xml' ? 'xml' : 'bpmn20.xml'
    Download.saveAs(new Blob([xml], { type: 'application/xml;charset=utf-8' }), `${name}.${extension}`)
  } catch (error) {
    emit('error', error)
  }
}

/**
 * 将 XML Element 递归转换为无循环的 JSON 预览结构。
 * @param {Element} element 当前 XML 元素。
 * @returns {object} 包含节点名、属性、文本和子节点的结构化对象。
 */
function xmlElementToJson(element) {
  const attributes = Object.fromEntries([...element.attributes].map(item => [item.name, item.value]))
  const children = [...element.children].map(xmlElementToJson)
  const directText = [...element.childNodes]
    .filter(node => node.nodeType === Node.TEXT_NODE)
    .map(node => node.textContent.trim())
    .filter(Boolean)
    .join(' ')
  const result = { name: element.tagName }
  if (Object.keys(attributes).length) result.attributes = attributes
  if (directText) result.text = directText
  if (children.length) result.children = children
  return result
}

/**
 * 打开 XML 或结构化 JSON 预览，不使用字符串替换伪造 BPMN 结构。
 * @param {'xml'|'json'} format 预览格式。
 * @returns {Promise<void>} 序列化或解析失败时触发 error。
 */
async function openPreview(format) {
  if (designerLocked.value) return
  try {
    const xml = await emitPersistedXml()
    previewTitle.value = format === 'json' ? 'JSON 预览' : 'XML 预览'
    if (format === 'json') {
      const documentNode = new DOMParser().parseFromString(xml, 'application/xml')
      if (documentNode.querySelector('parsererror')) throw new Error('BPMN XML 无法转换为 JSON')
      previewContent.value = JSON.stringify(xmlElementToJson(documentNode.documentElement), null, 2)
    } else {
      previewContent.value = xml
    }
    previewVisible.value = true
  } catch (error) {
    emit('error', error)
  }
}

/**
 * 把当前源码预览复制到系统剪贴板。
 * @returns {Promise<void>} 浏览器不允许剪贴板访问时报告错误。
 */
async function copyPreview() {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('当前浏览器不支持剪贴板写入')
    await navigator.clipboard.writeText(previewContent.value)
    ElMessage.success('已复制')
  } catch (error) {
    emit('error', error)
  }
}

/**
 * 调用无副作用服务端编译校验，并保存结构化诊断供用户定位。
 * @param {boolean} showResult 是否打开校验结果弹窗。
 * @returns {Promise<boolean>} true 表示通过保存和部署共同门禁。
 */
async function runServerValidation(showResult) {
  if (props.saving) return false
  validating.value = true
  try {
    const xml = await emitPersistedXml()
    const response = await validateModelBpmn(xml)
    const report = response.data || {}
    validationIssues.value = Array.isArray(report.issues) ? report.issues : []
    if (showResult) validationVisible.value = true
    return report.valid === true
      && validationIssues.value.every(issue => issue.severity !== 'ERROR')
  } finally {
    validating.value = false
  }
}

/**
 * 同步系统深色主题媒体查询，仅在偏好为 SYSTEM 时影响设计器外观。
 * @param {MediaQueryListEvent|MediaQueryList} event 系统颜色方案媒体查询结果。
 * @returns {void} 更新响应式主题状态。
 */
function handleSystemTheme(event) {
  systemDark.value = Boolean(event.matches)
}

/**
 * 处理设计器级快捷键，Ctrl/Cmd+S 走完整本地与服务端保存门禁。
 * @param {KeyboardEvent} event 浏览器键盘事件。
 * @returns {void} 焦点不在设计器主体或正在保存时不接管浏览器行为。
 */
function handleDesignerShortcut(event) {
  const activeElement = document.activeElement
  if (!bodyRef.value?.contains(activeElement)) return
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    requestSave()
  }
}

/**
 * 请求页面把字段完整的偏好写入正式数据库。
 * @param {object} preference 设计器设置抽屉提交的完整偏好。
 * @returns {void} 真实结果由 preference Prop 回写后应用。
 */
function requestPreferenceSave(preference) {
  emit('preference-save', normalizePreference(preference))
}

/**
 * 切换属性面板折叠状态，并要求页面持久化完整偏好。
 * @returns {void} 服务端成功前不改变已应用状态。
 */
function toggleProperties() {
  requestPreferenceSave({
    ...appliedPreference.value,
    propertiesCollapsed: !appliedPreference.value.propertiesCollapsed
  })
}

/**
 * 把属性面板宽度约束在当前工作区可用范围内，避免压缩画布或越出视口。
 * @param {number} width 用户拖拽或键盘操作得到的候选宽度。
 * @returns {number} 可安全应用到当前布局的属性面板宽度。
 */
function clampPropertiesPanelWidth(width) {
  return Math.min(
    propertiesPanelMaxWidth.value,
    Math.max(PROPERTIES_PANEL_MIN_WIDTH, Number(width) || PROPERTIES_PANEL_DEFAULT_WIDTH)
  )
}

/**
 * 在布局尺寸变化后通知 bpmn-js 重算画布视口，保证连线、命中区域和小地图同步。
 * @returns {void} 使用单帧节流合并连续拖拽产生的尺寸变化。
 */
function scheduleCanvasResize() {
  if (!modeler || canvasResizeFrame) return
  canvasResizeFrame = window.requestAnimationFrame(() => {
    canvasResizeFrame = undefined
    modeler?.get('canvas')?.resized()
  })
}

/**
 * 处理设计器主体 ResizeObserver 回调，并重新约束属性面板宽度。
 * @param {ResizeObserverEntry[]} entries 当前设计器主体的尺寸观察结果。
 * @returns {void} 更新布局宽度并安排画布重算。
 */
function handleDesignerBodyResize(entries) {
  const width = entries[0]?.contentRect?.width || bodyRef.value?.clientWidth || 0
  designerBodyWidth.value = width
  propertiesPanelWidth.value = clampPropertiesPanelWidth(propertiesPanelWidth.value)
  scheduleCanvasResize()
}

/**
 * 开始拖拽调整属性面板宽度，并临时锁定页面选区和光标。
 * @param {PointerEvent} event 分隔条的指针按下事件。
 * @returns {void} 保存拖拽起点，后续移动由窗口级事件持续接收。
 */
function startPropertiesResize(event) {
  if (designerLocked.value || appliedPreference.value.propertiesCollapsed || event.button !== 0) return
  event.preventDefault()
  resizeStartClientX = event.clientX
  resizeStartWidth = propertiesPanelWidth.value
  propertiesResizing.value = true
  previousDocumentCursor = document.documentElement.style.cursor
  previousDocumentUserSelect = document.documentElement.style.userSelect
  document.documentElement.style.cursor = 'col-resize'
  document.documentElement.style.userSelect = 'none'
}

/**
 * 根据指针水平位移实时调整右侧属性面板宽度。
 * @param {PointerEvent} event 窗口级指针移动事件。
 * @returns {void} 未处于拖拽状态时不产生任何布局变化。
 */
function handlePropertiesResizeMove(event) {
  if (!propertiesResizing.value) return
  propertiesPanelWidth.value = clampPropertiesPanelWidth(
    resizeStartWidth + resizeStartClientX - event.clientX
  )
  scheduleCanvasResize()
}

/**
 * 结束属性面板拖拽并恢复页面原有光标与文本选择行为。
 * @returns {void} 无论指针在何处释放都清理拖拽状态。
 */
function stopPropertiesResize() {
  if (!propertiesResizing.value) return
  propertiesResizing.value = false
  document.documentElement.style.cursor = previousDocumentCursor
  document.documentElement.style.userSelect = previousDocumentUserSelect
  scheduleCanvasResize()
}

/**
 * 使用键盘精确调整属性面板宽度，兼顾无鼠标操作与细粒度控制。
 * @param {KeyboardEvent} event 分隔条获得焦点后的键盘事件。
 * @returns {void} 左右方向键每次调整 16px，Home 恢复默认宽度。
 */
function handlePropertiesResizeKeydown(event) {
  const directions = { ArrowLeft: 16, ArrowRight: -16 }
  if (event.key === 'Home') {
    event.preventDefault()
    resetPropertiesPanelWidth()
    return
  }
  if (!Object.hasOwn(directions, event.key)) return
  event.preventDefault()
  propertiesPanelWidth.value = clampPropertiesPanelWidth(
    propertiesPanelWidth.value + directions[event.key]
  )
  scheduleCanvasResize()
}

/**
 * 将属性面板恢复为适合常规表单编辑的默认宽度。
 * @returns {void} 默认宽度仍受当前工作区上限约束。
 */
function resetPropertiesPanelWidth() {
  propertiesPanelWidth.value = clampPropertiesPanelWidth(PROPERTIES_PANEL_DEFAULT_WIDTH)
  scheduleCanvasResize()
}

/**
 * 切换 Token 流程模拟，并要求页面持久化完整偏好。
 * @returns {void} 服务端成功后由偏好监听器进入或退出模拟。
 */
function toggleSimulation() {
  requestPreferenceSave({
    ...appliedPreference.value,
    tokenSimulationEnabled: !appliedPreference.value.tokenSimulationEnabled
  })
}

/**
 * 把服务端偏好同步到网格、小地图、Lint 和 Token 模拟服务。
 * @returns {void} Modeler 尚未初始化时只保留 Prop 状态。
 */
function applyDesignerPreference() {
  if (!modeler) return
  const preference = appliedPreference.value
  modeler.get('gridSnapping').setActive(preference.gridEnabled)
  modeler.get('minimap').toggle(preference.minimapEnabled)
  modeler.get('linting').toggle(preference.lintEnabled)
  const toggleMode = modeler.get('toggleMode')
  if (simulationActive.value !== preference.tokenSimulationEnabled) {
    toggleMode.toggleMode(preference.tokenSimulationEnabled)
  }
}

/**
 * 导出可被后端再次保存或部署的 BPMN XML 文件。
 * @returns {Promise<void>} 导出失败时触发 error。
 */
async function downloadXml() {
  if (designerLocked.value) return
  try {
    // 公开下载方法必须在自身边界显式标准化内部审计监听器，避免调用方绕过通用导出分支。
    const xml = await emitPersistedXml()
    const name = String(props.model.modelKey || 'workflow').replace(/[^A-Za-z0-9_.-]/g, '_')
    Download.saveAs(new Blob([xml], { type: 'application/xml;charset=utf-8' }), `${name}.bpmn20.xml`)
  } catch (error) {
    emit('error', error)
  }
}

/**
 * 执行保存前本地结构门禁并把 XML 交给页面调用真实后端。
 * @returns {Promise<void>} 校验失败时仅触发 error，不触发 save。
 */
async function requestSave() {
  if (designerLocked.value) return
  savePreparing.value = true
  // 清除最后一次建模命令留下的延迟导出，保证本次保存窗口内只有一个 XML 序列化任务。
  window.clearTimeout(changeTimer)
  try {
    const error = validateDiagram()
    if (error) throw new Error(error)
    const serverValid = await runServerValidation(false)
    if (!serverValid) {
      validationVisible.value = true
      return
    }
    emit('save', await emitPersistedXml())
  } catch (error) {
    emit('error', error)
  } finally {
    savePreparing.value = false
  }
}

/**
 * 保存锁生效时移走属性输入焦点，配合 inert 阻止序列化后继续修改同一保存快照。
 * @param {boolean} locked 设计器是否处于 XML 序列化或后端保存阶段。
 * @returns {void} 当前焦点不在设计器主体内时不处理。
 */
function handleDesignerLock(locked) {
  const activeElement = document.activeElement
  if (locked && activeElement instanceof HTMLElement && bodyRef.value?.contains(activeElement)) {
    activeElement.blur()
  }
}

/**
 * 执行与后端关键规则一致的即时结构校验。
 * @returns {string} 空串表示通过，否则返回首个业务错误。
 */
function validateDiagram() {
  if (!modeler) return '流程设计器尚未初始化'
  const registry = modeler.get('elementRegistry')
  const processes = registry.filter(element => element.type === 'bpmn:Process')
  if (!processes.some(element => element.businessObject.isExecutable)) return '至少需要一个可执行流程'
  for (const process of processes) {
    const flowElements = process.businessObject.flowElements || []
    const startEvents = flowElements.filter(item => item.$type === 'bpmn:StartEvent')
    if (startEvents.length !== 1) return '每个流程必须且只能包含一个开始节点'
    const startEvent = startEvents[0]
    if (!startEvent.get('flowable:formKey') && !hasEmbeddedFormFields(startEvent)) {
      return '开始节点必须配置发起表单'
    }
  }
  const userTasks = registry.filter(element => element.type === 'bpmn:UserTask')
  for (const element of userTasks) {
    const task = element.businessObject
    const loopError = validateUserTaskMultiInstance(task)
    if (loopError) return loopError
    const slaConfig = readSlaConfig(readExtensionProperties(task))
    if (slaConfig.enabled) {
      try {
        normalizeAndValidateSlaConfig(slaConfig)
      } catch (error) {
        return error.message
      }
    }
  }
  const businessBoundaries = registry.filter(element => element.type === 'bpmn:BoundaryEvent')
  for (const element of businessBoundaries) {
    const definition = element.businessObject.eventDefinitions?.[0]
    const businessType = definition?.$type
    if (!['bpmn:ErrorEventDefinition', 'bpmn:EscalationEventDefinition'].includes(businessType)) continue
    const allowed = businessType === 'bpmn:ErrorEventDefinition' ? errorEventOptions.value : escalationEventOptions.value
    if (!allowed.some(option => option.eventCode === readEventReference(definition))) {
      return '错误或升级边界必须选择已启用的正式业务编码'
    }
    if (businessType === 'bpmn:ErrorEventDefinition' && element.businessObject.cancelActivity === false) {
      return '错误边界必须使用中断语义'
    }
  }
  return ''
}

/**
 * 从 Flowable 通用属性集合解析 UserTask SLA 作者配置。
 * @param {Array<{name:string,value:string}>} properties 当前元素全部扩展属性。
 * @returns {object} 字段完整的结构化 SLA 配置；旧模型没有属性时返回停用默认值。
 */
function readSlaConfig(properties) {
  const values = new Map((Array.isArray(properties) ? properties : [])
    .filter(item => SLA_PROPERTY_NAME_SET.has(item.name))
    .map(item => [item.name, String(item.value ?? '')]))
  const defaults = createDefaultSlaConfig()
  return {
    enabled: values.get(SLA_PROPERTY_NAMES.enabled) === 'true',
    calendarKey: values.get(SLA_PROPERTY_NAMES.calendarKey) || defaults.calendarKey,
    reminderMinutes: Number(values.get(SLA_PROPERTY_NAMES.reminderMinutes) || defaults.reminderMinutes),
    reminderRepeatMinutes: Number(values.get(SLA_PROPERTY_NAMES.reminderRepeatMinutes) || defaults.reminderRepeatMinutes),
    maxReminders: Number(values.get(SLA_PROPERTY_NAMES.maxReminders) || defaults.maxReminders),
    escalationMinutes: Number(values.get(SLA_PROPERTY_NAMES.escalationMinutes) || defaults.escalationMinutes),
    escalationUserId: values.get(SLA_PROPERTY_NAMES.escalationUserId) || defaults.escalationUserId,
    escalationEventCode: values.get(SLA_PROPERTY_NAMES.escalationEventCode) || defaults.escalationEventCode
  }
}

/**
 * 校验并写入当前 UserTask 的受控 SLA 属性。
 * @param {object} config SLA 编辑器提交的八个结构化作者字段。
 * @returns {void} 目录或跨字段约束不合法时恢复 BPMN 原值并向页面上报。
 */
function updateSlaProperties(config) {
  if (designerLocked.value || !modeler || !selectedElement.value || !propertyFlags.value.userTask) return
  try {
    const normalized = normalizeAndValidateSlaConfig(config)
    propertyState.sla = normalized
    // SLA 命令栈必须携带现有受控循环属性，避免切换超时策略破坏整改循环。
    const controlledProperties = readAllFlowableProperties(selectedBusinessObject.value)
      .filter(item => CONTROLLED_LOOP_PROPERTY_NAMES.has(item.name))
    persistExtensionProperties([
      ...propertyState.extensionProperties,
      ...controlledProperties,
      ...slaConfigToProperties(normalized)
    ])
  } catch (error) {
    loadPropertyState(selectedElement.value)
    emit('error', error)
  }
}

/**
 * 规范化并校验 SLA 数值、目录引用、提醒顺序和升级目标。
 * @param {object} config UserTask SLA 编辑值或从 XML 回读的配置。
 * @returns {object} 字段类型确定且可写入 XML 的 SLA 配置。
 */
function normalizeAndValidateSlaConfig(config) {
  const normalized = {
    enabled: config?.enabled === true,
    calendarKey: String(config?.calendarKey || '').trim(),
    reminderMinutes: Number(config?.reminderMinutes),
    reminderRepeatMinutes: Number(config?.reminderRepeatMinutes),
    maxReminders: Number(config?.maxReminders),
    escalationMinutes: Number(config?.escalationMinutes),
    escalationUserId: String(config?.escalationUserId || '').trim(),
    escalationEventCode: String(config?.escalationEventCode || '').trim()
  }
  if (!normalized.enabled) return { ...createDefaultSlaConfig(), ...normalized }
  if (!slaCalendarOptions.value.some(item => item.calendarKey === normalized.calendarKey)) {
    throw new Error('审批 SLA 必须选择已启用的正式业务日历')
  }
  if (!isBoundedSlaMinute(normalized.reminderMinutes)
    || !isBoundedSlaMinute(normalized.reminderRepeatMinutes)
    || !isBoundedSlaMinute(normalized.escalationMinutes)) {
    throw new Error('SLA 提醒与升级时间必须是 1 至 525600 的整数分钟')
  }
  if (!Number.isInteger(normalized.maxReminders) || normalized.maxReminders < 1 || normalized.maxReminders > 100) {
    throw new Error('SLA 最大提醒次数必须是 1 至 100 的整数')
  }
  const lastReminderMinutes = normalized.reminderMinutes
    + normalized.reminderRepeatMinutes * (normalized.maxReminders - 1)
  if (normalized.escalationMinutes <= lastReminderMinutes) {
    throw new Error('SLA 超时升级时间必须晚于最后一次提醒')
  }
  if (!normalized.escalationUserId && !normalized.escalationEventCode) {
    throw new Error('SLA 必须配置升级办理人或受控升级事件')
  }
  if (normalized.escalationEventCode && !escalationEventOptions.value
    .some(item => item.eventCode === normalized.escalationEventCode)) {
    throw new Error('SLA 超时升级只能引用已启用的正式升级编码')
  }
  return normalized
}

/**
 * 判断 SLA 工作分钟是否处于后端允许的一年上限内。
 * @param {number} value 待校验的提醒或升级工作分钟。
 * @returns {boolean} 值为 1 至 525600 的整数时返回 true。
 */
function isBoundedSlaMinute(value) {
  return Number.isInteger(value) && value >= 1 && value <= 525600
}

/**
 * 将结构化 SLA 配置转换为后端部署编译器约定的八个 Flowable 属性。
 * @param {object} config 已规范化的 SLA 配置。
 * @returns {Array<{name:string,value:string}>} 按固定顺序输出的属性名值列表。
 */
function slaConfigToProperties(config) {
  return Object.entries(SLA_PROPERTY_NAMES).map(([field, name]) => ({
    name,
    value: field === 'enabled' ? String(config.enabled === true) : String(config[field] ?? '')
  }))
}

/**
 * 将已经过调用方校验的完整扩展属性集合原子写入当前 BPMN 元素。
 * @param {Array<{name:string,value:string}>} properties 包含通用属性和受控 SLA 属性的完整集合。
 * @returns {void} 保留监听器、表单和服务任务字段等其他 extensionElements。
 */
function persistExtensionProperties(properties) {
  const businessObject = selectedBusinessObject.value
  const moddle = modeler.get('moddle')
  const preservedValues = (businessObject.extensionElements?.values || [])
    .filter(value => value?.$type !== 'flowable:Properties')
  const propertyContainer = properties.length
    ? moddle.create('flowable:Properties', {
        values: properties.map(item => moddle.create('flowable:Property', item))
      })
    : undefined
  const extensionValues = propertyContainer
    ? [...preservedValues, propertyContainer]
    : preservedValues
  const extensionElements = extensionValues.length
    ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
    : undefined
  modeler.get('modeling').updateModdleProperties(selectedElement.value, businessObject, {
    extensionElements
  })
}

/**
 * 创建字段完整的 UserTask SLA 默认配置。
 * @returns {object} 未启用且数值处于合法范围的作者配置。
 */
function createDefaultSlaConfig() {
  return {
    enabled: false,
    calendarKey: '',
    reminderMinutes: 60,
    reminderRepeatMinutes: 60,
    maxReminders: 1,
    escalationMinutes: 240,
    escalationUserId: '',
    escalationEventCode: ''
  }
}

/**
 * 从错误或升级事件定义读取最终作者编码。
 * @param {object} eventDefinition bpmn-js 事件定义对象。
 * @returns {string} 根引用中的稳定业务编码。
 */
function readEventReference(eventDefinition) {
  const config = eventReferenceConfig(eventDefinition?.$type)
  const reference = config ? eventDefinition?.[config.referenceProperty] : undefined
  return String(reference?.[config?.keyProperty] || reference?.name || '').trim()
}

/**
 * 判断 BPMN 元素是否包含至少一个 Flowable 内嵌表单字段。
 * @param {object} businessObject StartEvent 或 UserTask 的 moddle 业务对象。
 * @returns {boolean} extensionElements 中存在 FormProperty 时返回 true。
 */
function hasEmbeddedFormFields(businessObject) {
  return (businessObject?.extensionElements?.values || [])
    .some(value => value?.$type === 'flowable:FormProperty')
}

/**
 * 校验用户任务的受控 handler 只以固定并行会签/或签组合出现，并阻断近似方法名。
 * @param {object} task bpmn-js 用户任务业务对象。
 * @returns {string} 空串表示通过，否则返回稳定业务错误。
 */
function validateUserTaskMultiInstance(task) {
  const loop = task.loopCharacteristics
  if (!loop) return ''
  const collection = String(loop.get?.('flowable:collection') || '').trim()
  if (collection !== CONTROLLED_MULTI_INSTANCE_COLLECTION
    && !FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(collection)) return ''
  const condition = String(loop.completionCondition?.body || '').trim()
  const approvedCondition = [
    CONTROLLED_MULTI_INSTANCE_ALL_CONDITION,
    CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
  ].includes(condition)
  const parentIsMainProcess = task.$parent?.$type === 'bpmn:Process'
  const hasBoundaryEvents = Array.isArray(task.boundaryEventRefs) && task.boundaryEventRefs.length > 0
  if (loop.isSequential
    || loop.get('flowable:elementVariable') !== CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE
    || task.get('flowable:assignee') !== CONTROLLED_MULTI_INSTANCE_ASSIGNEE
    || task.get('flowable:candidateUsers')
    || task.get('flowable:candidateGroups')
    || loop.loopCardinality
    || !approvedCondition
    || task.isForCompensation
    || hasBoundaryEvents
    || !parentIsMainProcess) {
    return '受控多实例配置不符合会签或或签契约'
  }
  if (FIXED_MULTI_INSTANCE_COLLECTION_PATTERN.test(collection)) {
    const fixedUserIds = readFixedMultiInstanceUserIds(loop)
    if (!fixedUserIds.length || fixedUserIds.length > 100) {
      return '固定会签或或签必须预设 1 至 100 名有效办理人'
    }
  }
  return ''
}

watch(() => props.modelValue, value => {
  if (value && value !== lastExportedXml.value && modeler) importXml(value)
})
watch(() => props.preference, () => {
  applyDesignerPreference()
  settingsVisible.value = false
  scheduleCanvasResize()
}, { deep: true })
watch(designerLocked, handleDesignerLock)

onActivated(repairCachedSequenceFlowReferences)
onMounted(() => {
  systemThemeQuery = window.matchMedia('(prefers-color-scheme: dark)')
  handleSystemTheme(systemThemeQuery)
  systemThemeQuery.addEventListener('change', handleSystemTheme)
  window.addEventListener('keydown', handleDesignerShortcut)
  window.addEventListener('pointermove', handlePropertiesResizeMove)
  window.addEventListener('pointerup', stopPropertiesResize)
  window.addEventListener('pointercancel', stopPropertiesResize)
  bodyResizeObserver = new ResizeObserver(handleDesignerBodyResize)
  if (bodyRef.value) bodyResizeObserver.observe(bodyRef.value)
  loadExtensionOptions()
  loadDmnOptions()
  importXml(props.modelValue)
})
onBeforeUnmount(() => {
  window.clearTimeout(changeTimer)
  if (canvasResizeFrame) window.cancelAnimationFrame(canvasResizeFrame)
  window.removeEventListener('keydown', handleDesignerShortcut)
  window.removeEventListener('pointermove', handlePropertiesResizeMove)
  window.removeEventListener('pointerup', stopPropertiesResize)
  window.removeEventListener('pointercancel', stopPropertiesResize)
  stopPropertiesResize()
  bodyResizeObserver?.disconnect()
  bodyResizeObserver = undefined
  systemThemeQuery?.removeEventListener('change', handleSystemTheme)
  identitySearchTimers.forEach(timer => window.clearTimeout(timer))
  identitySearchTimers.clear()
  if (modeler) modeler.destroy()
  modeler = undefined
})

defineExpose({
  requestSave,
  downloadXml,
  exportDiagram,
  openPreview,
  runServerValidation,
  fitViewport,
  getXml: () => emitPersistedXml()
})
</script>

<style scoped lang="scss">
.process-designer {
  display: grid;
  grid-template-rows: 54px minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--app-border-strong, var(--el-border-color-light));
  border-radius: 10px;
  box-shadow: 0 14px 34px rgb(23 33 30 / 9%);
}

.process-designer--dark {
  --el-bg-color: #181a1f;
  --el-bg-color-overlay: #202329;
  --el-fill-color-light: #292d34;
  --el-border-color-light: #343941;
  --el-border-color-lighter: #2d3239;
  --el-text-color-primary: #e7e9ed;
  --el-text-color-regular: #c3c8d0;
  --el-text-color-secondary: #9299a4;
}

.process-designer__file-input {
  display: none;
}

.process-designer__body {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 8px var(--designer-properties-width);
  min-height: 0;
  overflow: hidden;
}

.process-designer__body--properties-collapsed {
  grid-template-columns: minmax(0, 1fr);
}

.process-designer__body--resizing {
  cursor: col-resize;
}

.process-designer__canvas {
  min-width: 0;
  min-height: 0;
  background-color: #f8faf9;
  outline: none;
}

.process-designer--grid .process-designer__canvas {
  background-image:
    linear-gradient(rgb(204 216 210 / 45%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(204 216 210 / 45%) 1px, transparent 1px);
  background-size: 20px 20px;
}

.process-designer__properties-resizer {
  position: relative;
  z-index: 24;
  display: grid;
  min-width: 8px;
  height: 100%;
  place-items: center;
  cursor: col-resize;
  background: var(--el-bg-color);
  border-left: 1px solid var(--el-border-color-lighter);
  outline: none;
  transition: background-color 160ms ease;
}

.process-designer__properties-resizer::before {
  position: absolute;
  inset: 0 -3px;
  content: '';
}

.process-designer__properties-resizer:hover,
.process-designer__properties-resizer:focus-visible,
.process-designer__body--resizing .process-designer__properties-resizer {
  background: color-mix(in srgb, var(--el-color-primary) 10%, var(--el-bg-color));
}

.process-designer__properties-resizer:focus-visible {
  box-shadow: inset 2px 0 0 var(--el-color-primary);
}

.process-designer__properties-resizer-grip {
  width: 3px;
  height: 42px;
  background: var(--el-border-color);
  border-radius: 999px;
  transition: height 160ms ease, background-color 160ms ease;
}

.process-designer__properties-resizer:hover .process-designer__properties-resizer-grip,
.process-designer__properties-resizer:focus-visible .process-designer__properties-resizer-grip,
.process-designer__body--resizing .process-designer__properties-resizer-grip {
  height: 64px;
  background: var(--el-color-primary);
}

.process-designer__body--compact-properties {
  grid-template-columns: minmax(0, 1fr);
}

.process-designer__body--compact-properties .designer-properties-panel {
  position: absolute;
  top: 12px;
  right: 12px;
  bottom: 12px;
  z-index: 23;
  width: min(var(--designer-properties-width), calc(100% - 24px));
  border: 1px solid var(--el-border-color);
  border-radius: 9px;
  box-shadow: 0 18px 42px rgb(15 23 42 / 22%);
}

.process-designer__body--compact-properties .process-designer__properties-resizer {
  position: absolute;
  top: 12px;
  right: calc(12px + min(var(--designer-properties-width), calc(100% - 24px)) - 4px);
  bottom: 12px;
  z-index: 25;
  width: 8px;
  height: auto;
  border-left: 0;
  border-radius: 9px 0 0 9px;
}

.process-designer--dark .process-designer__canvas {
  background-color: #15171b;
}

.process-designer--dark.process-designer--grid .process-designer__canvas {
  background-image: linear-gradient(#252930 1px, transparent 1px), linear-gradient(90deg, #252930 1px, transparent 1px);
}

.process-designer__source :deep(textarea) {
  font-family: Consolas, 'Cascadia Mono', monospace;
  font-size: 12px;
  line-height: 1.55;
}

:deep(.bts-toggle-mode) {
  display: none;
}

:deep(.process-designer__controlled-loop-badge) {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  color: #7c2d12;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  background: #ffedd5;
  border: 1px solid #fdba74;
  border-radius: 999px;
  box-shadow: 0 2px 6px rgb(124 45 18 / 14%);
}

.process-designer--dark :deep(.process-designer__controlled-loop-badge) {
  color: #fed7aa;
  background: #7c2d12;
  border-color: #c2410c;
}

.process-designer--dark :deep(.djs-palette),
.process-designer--dark :deep(.djs-context-pad),
.process-designer--dark :deep(.djs-popup),
.process-designer--dark :deep(.djs-minimap) {
  color: #d7dbe2;
  background: #202329;
  border-color: #343941;
}

.process-designer--dark :deep(.djs-element .djs-visual > :first-child) {
  fill: #202329 !important;
  stroke: #aeb5c0 !important;
}

.process-designer--dark :deep(.djs-label) {
  fill: #e7e9ed !important;
}

:deep(.djs-minimap) {
  top: auto;
  right: 10px;
  bottom: 10px;
  border-color: var(--el-border-color-light);
}

:deep(.djs-minimap .toggle) {
  display: grid;
  width: 30px;
  height: 30px;
  padding: 0;
  place-items: center;
  color: var(--el-text-color-regular);
  font-size: 0;
}

:deep(.djs-minimap .toggle::before) {
  content: "\00d7";
  font-size: 18px;
  line-height: 1;
}

:deep(.djs-minimap:not(.open) .toggle::before) {
  content: "+";
}

@media (prefers-reduced-motion: reduce) {
  .process-designer__properties-resizer,
  .process-designer__properties-resizer-grip {
    transition: none;
  }
}
</style>
