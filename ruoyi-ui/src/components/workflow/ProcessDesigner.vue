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
        :task-capability="selectedTaskCapability"
        :forms="forms"
        :identity-options="identityOptions"
        :identity-loading="identityLoading"
        :assignment-options="assignmentOptions"
        :multi-instance-options="multiInstanceOptions"
        :multi-instance-approval-options="multiInstanceApprovalOptions"
        :controlled-loop-field-options="controlledLoopFieldOptions"
        :participant-form-field-options="participantFormFieldOptions"
        :condition-field-options="conditionFieldOptions"
        :condition-context="conditionContext"
        :auto-copy-trigger-options="autoCopyTriggerOptions"
        :auto-copy-form-field-options="autoCopyFormFieldOptions"
        :extension-options="extensionOptions"
        :form-field-options="formFieldOptions"
        :connector-endpoints="connectorEndpoints"
        :sql-data-sources="sqlDataSources"
        :extension-loading="extensionLoading"
        :dmn-options="dmnOptions"
        :dmn-loading="dmnLoading"
        :call-activity-options="callActivityOptions"
        :call-activity-loading="callActivityLoading"
        :call-activity-parent-fields="callActivityParentFields"
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
        @form-permission-change="updateFormPermissions"
        @assignment-change="updateAssignment"
        @participant-rule-change="updateParticipantRule"
        @user-task-change="updateUserTaskProperties"
        @extension-selection-change="updateControlledTaskSelection"
        @controlled-task-config-update="updateControlledTaskConfig"
        @controlled-task-change="updateControlledTask"
        @condition-rule-change="updateConditionRule"
        @condition-default-change="makeConditionDefault"
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
        @auto-copy-change="updateAutoCopyRules"
        @identity-search="handlePanelIdentitySearch"
        @identity-resolve="handlePanelIdentityResolve"
        @close="toggleProperties"
      />
    </div>

    <DesignerSettingsDrawer
      v-model="settingsVisible"
      :preference="appliedPreference"
      :saving="preferenceSaving"
      @save="requestPreferenceSave"
      @reset="requestPreferenceReset"
    />

    <el-dialog v-model="previewVisible" :title="previewTitle" width="min(920px, 86vw)" append-to-body>
      <el-input class="process-designer__source" :model-value="previewContent" type="textarea" :rows="24" readonly resize="none" />
      <template #footer>
        <el-button icon="DocumentCopy" @click="copyPreview">复制</el-button>
        <el-button type="primary" @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="validationVisible" title="流程校验" width="min(720px, 82vw)" append-to-body>
      <el-result v-if="validationPassed && !validationIssues.length" icon="success" title="校验通过" />
      <el-table v-else-if="validationIssues.length" :data="validationIssues" max-height="460">
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
import tokenSimulationModule from 'bpmn-js-token-simulation'
import { ElMessage, ElMessageBox } from 'element-plus'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import 'diagram-js-minimap/assets/diagram-js-minimap.css'
import 'bpmn-js-token-simulation/assets/css/bpmn-js-token-simulation.css'
import Download from '@/plugins/download'
import { listCallActivityOptions, validateModelBpmn } from '@/api/workflow/model'
import {
  listCelExtensionOptions,
  listFormFieldExtensionOptions,
  listHttpExtensionOptions,
  listJavaExtensionOptions,
  listSqlExtensionOptions
} from '@/api/workflow/extension'
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
import taskCapabilityReplaceMenuModule from './designer/taskCapabilityReplaceMenu.js'
import { getTaskCapability } from './designer/taskCapabilityMap.js'
import {
  createExtensionEventSlaDomain,
  createDefaultSlaConfig,
  isExtensionEventSlaProperty
} from './designer/extensionEventSlaDomain'
import {
  createFormParticipantDomain,
  isFormParticipantProperty
} from './designer/formParticipantDomain'
import {
  createRoutingCallActivityDomain,
  isRoutingCallActivityProperty
} from './designer/routingCallActivityDomain'

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
    default: () => ({
      assignees: [], candidateUsers: [], candidateGroups: [], candidateRoles: [],
      activeUsers: [], activeRoles: [], activeDepts: [], autoCopyUsers: [], autoCopyGroups: []
    })
  },
  /** 设计器稳定高度。 */
  height: { type: String, default: 'calc(100vh - 128px)' },
  /** 保存请求是否正在执行。 */
  saving: { type: Boolean, default: false },
  /** 页面是否正在查询正式用户、角色或部门主数据。 */
  identityLoading: { type: Boolean, default: false },
  /** 页面按当前用户从浏览器存储回读的设计器偏好。 */
  preference: {
    type: Object,
    default: () => ({
      theme: 'SYSTEM',
      gridEnabled: true,
      minimapEnabled: true,
      tokenSimulationEnabled: false,
      propertiesCollapsed: false
    })
  },
  /** 设计器偏好是否正在写入当前用户浏览器存储。 */
  preferenceSaving: { type: Boolean, default: false }
})

const emit = defineEmits([
  'update:modelValue', 'change', 'save', 'error', 'identity-search', 'identity-resolve',
  'preference-save', 'preference-reset'
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
// validationPassed 表示最近一次真实服务端报告是否明确允许保存，不能由空问题列表推断。
const validationPassed = ref(false)
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
/**
 * 判断当前设计器主体是否需要把属性面板切换为工作区内浮层。
 * @returns {boolean} 主体已有有效宽度且小于 960px 时返回 true。
 */
const compactPropertiesLayout = computed(() => (
  designerBodyWidth.value > 0 && designerBodyWidth.value < PROPERTIES_COMPACT_BREAKPOINT
))
/**
 * 计算当前布局下属性面板允许使用的最大宽度。
 * @returns {number} 兼顾画布可用空间和窄屏边距后的像素上限。
 */
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
/**
 * 把当前属性面板宽度转换为设计器主体使用的 CSS 自定义属性。
 * @returns {Record<string, string>} 包含属性面板像素宽度的内联样式对象。
 */
const designerBodyStyle = computed(() => ({
  '--designer-properties-width': `${Math.round(propertiesPanelWidth.value)}px`
}))
const designerLocked = computed(() => props.saving || savePreparing.value)
const appliedPreference = computed(() => normalizePreference(props.preference))
const totalIssueCount = computed(() => validationIssues.value.length)
const designerClasses = computed(() => ({
  'process-designer--dark': appliedPreference.value.theme === 'DARK'
    || (appliedPreference.value.theme === 'SYSTEM' && systemDark.value),
  'process-designer--grid': appliedPreference.value.gridEnabled
}))
const selectedBusinessObject = computed(() => selectedElement.value?.businessObject)
const isProcess = computed(() => isType('bpmn:Process'))
const isStartEvent = computed(() => isType('bpmn:StartEvent'))
const isUserTask = computed(() => isType('bpmn:UserTask'))
const isSequenceFlow = computed(() => isType('bpmn:SequenceFlow'))
const isParticipant = computed(() => isType('bpmn:Participant'))
// 当前任务能力只按标准 BPMN 类型读取；非任务元素返回 null，由非任务能力独立决定业务分区。
const selectedTaskCapability = computed(() => getTaskCapability(selectedBusinessObject.value?.$type))
const propertyFlags = computed(() => {
  const eventDefinitionType = propertyState.eventDefinitionType
  return Object.freeze({
    process: isProcess.value,
    participant: isParticipant.value,
    startEvent: isStartEvent.value,
    formSupported: isStartEvent.value || isUserTask.value,
    sequenceFlow: isSequenceFlow.value,
    conditionGatewayFlow: isConditionGatewayFlow(),
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
let modeler
let changeTimer
let systemThemeQuery
let bodyResizeObserver
let canvasResizeFrame
// lifecycleListenersBound 表示当前缓存设计页是否仍持有全局交互与尺寸监听。
let lifecycleListenersBound = false
let resizeStartClientX = 0
let resizeStartWidth = PROPERTIES_PANEL_DEFAULT_WIDTH
let previousDocumentCursor = ''
let previousDocumentUserSelect = ''
let importing = false

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
    formPermissionDefault: 'EDITABLE',
    formPermissionFields: [],
    assignmentType: 'assignee',
    assignee: '',
    candidateUsers: [],
    candidateGroups: [],
    participantRule: { type: '', targetIds: [], formField: '' },
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
    callDefinitionId: '',
    callVersionPolicy: 'LATEST_ACTIVE',
    callBusinessKeyPolicy: 'INHERIT',
    callInheritVariables: false,
    callInMappings: [],
    callOutMappings: [],
    callOutputScope: 'PARENT',
    processInstanceName: '',
    conditionExpression: '',
    conditionRule: null,
    conditionDefault: false,
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
    configuredMultiInstanceIdentityIds: [],
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
    autoCopyRules: [],
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
    <extensionElements>
      <flowable:properties>
        <flowable:property name="approva.startScope.ruleVersion" value="1"/>
        <flowable:property name="approva.startScope.type" value="PUBLIC"/>
        <flowable:property name="approva.startScope.targetIds" value=""/>
        <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
      </flowable:properties>
    </extensionElements>
    <startEvent id="start" name="提交申请"${formAttribute}/>
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="review"/>
    <userTask id="review" name="审批">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="approva.assignment.ruleVersion" value="1"/>
          <flowable:property name="approva.assignment.type" value="STARTER"/>
          <flowable:property name="approva.assignment.targetIds" value=""/>
          <flowable:property name="approva.assignment.formField" value=""/>
          <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
        </flowable:properties>
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
 * 初始化 bpmn-js Modeler、Flowable moddle 和事件监听。
 * @returns {void} Modeler 生命周期由组件管理。
 */
function createModeler() {
  if (modeler || !canvasRef.value) return
  modeler = new Modeler({
    container: canvasRef.value,
    gridSnapping: { active: appliedPreference.value.gridEnabled },
    additionalModules: [
      minimapModule,
      gridSnappingModule,
      tokenSimulationModule,
      taskCapabilityReplaceMenuModule
    ],
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
  eventBus.on('commandStack.shape.replace.postExecute', handleShapeReplace)
  eventBus.on('commandStack.changed', handleCommandStackChanged)
  eventBus.on('tokenSimulation.toggleMode', event => {
    simulationActive.value = Boolean(event.active)
  })
}

/**
 * 在 bpmn-js 更改元素命令内清理不属于目标任务类型的 UserTask 作者状态。
 * @param {{context?:{oldShape?:object,newShape?:object}}|undefined} event shape.replace 命令事件，包含替换前后图形。
 * @returns {void} 仅 UserTask 转为其他任务时追加同一撤销单元内的属性净化命令。
 */
function handleShapeReplace(event) {
  const oldBusinessObject = event?.context?.oldShape?.businessObject
  const newShape = event?.context?.newShape
  const newBusinessObject = newShape?.businessObject
  if (!oldBusinessObject?.$instanceOf?.('bpmn:UserTask')
    || newBusinessObject?.$instanceOf?.('bpmn:UserTask')) return

  const extensionElements = createNonUserTaskExtensionElements(newBusinessObject)
  // 替换库会复制 Activity 公共属性和全部 extensionElements；这里必须把 UserTask 私有状态作为同组子命令清除。
  const changes = { extensionElements }
  if (readControlledLoop(oldBusinessObject)
    || isControlledMultiInstanceLoop(oldBusinessObject.loopCharacteristics)) {
    // 受控整改和受控会签依赖 UserTask 运行时；普通 BPMN 循环仍是其他 Activity 的合法公共属性。
    changes.loopCharacteristics = undefined
  }
  modeler.get('modeling').updateProperties(newShape, changes)
}

/**
 * 从替换后的任务扩展中移除只允许 UserTask 使用的监听器、表单和平台受控属性。
 * @param {object} businessObject 替换后目标任务的 BPMN 业务对象。
 * @returns {object|undefined} 保留通用执行监听器和普通扩展属性的全新 ExtensionElements；无内容时返回 undefined。
 */
function createNonUserTaskExtensionElements(businessObject) {
  const extensionValues = []
  const moddle = modeler.get('moddle')
  for (const value of businessObject?.extensionElements?.values || []) {
    if (value?.$type === 'flowable:TaskListener' || value?.$type === 'flowable:FormProperty') continue
    if (value?.$type !== 'flowable:Properties') {
      extensionValues.push(value)
      continue
    }
    const properties = (value.values || []).filter(property => !isUserTaskOnlyProperty(property?.name))
    if (properties.length) {
      extensionValues.push(moddle.create('flowable:Properties', { values: properties }))
    }
  }
  return extensionValues.length
    ? moddle.create('bpmn:ExtensionElements', { values: extensionValues })
    : undefined
}

/**
 * 判断 Flowable Property 是否只能出现在 UserTask 作者模型上。
 * @param {unknown} name Flowable Property 的 name 属性。
 * @returns {boolean} 办理规则、SLA、自动抄送、受控循环或多实例身份属性返回 true。
 */
function isUserTaskOnlyProperty(name) {
  return isFormParticipantProperty(name) || isExtensionEventSlaProperty(name)
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
    // 旧模型可能只有 sequenceFlow.sourceRef/targetRef，必须补齐 FlowNode 反向引用后再交给命令栈。
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
  validationPassed.value = false
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
  loadFormPermissionState(businessObject)
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
  // Participant.processRef 是 bpmn:Process 引用，属性面板只展示其稳定 id，禁止把 moddle 对象写入输入框。
  propertyState.processRef = businessObject.processRef?.id || ''
  propertyState.conditionExpression = businessObject.conditionExpression?.body || ''
  propertyState.conditionRule = readConditionRule(businessObject)
  propertyState.conditionDefault = isDefaultConditionFlow(element)
  const controlledTaskExtension = readControlledTaskExtension(businessObject)
  propertyState.extensionKey = controlledTaskExtension.extensionKey
  propertyState.extensionConfig = controlledTaskExtension.extensionConfig
  propertyState.businessExecutionListeners = readBusinessListeners(businessObject, 'flowable:ExecutionListener')
  propertyState.businessTaskListeners = readBusinessListeners(businessObject, 'flowable:TaskListener')
  const extensionProperties = readExtensionProperties(businessObject)
  propertyState.participantRule = readParticipantRule(businessObject)
  propertyState.sla = readSlaConfig(extensionProperties)
  propertyState.autoCopyRules = readAutoCopyRules(extensionProperties)
  // 三个领域的协议属性由各自结构化编辑器维护，通用属性面板只能展示普通扩展字段。
  propertyState.extensionProperties = extensionProperties.filter(item => (
    !isExtensionEventSlaProperty(item.name)
  ))
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
  const calledElementType = businessObject.get('flowable:calledElementType') || 'key'
  propertyState.callVersionPolicy = calledElementType === 'id' ? 'FIXED' : 'LATEST_ACTIVE'
  propertyState.callDefinitionId = resolveCallDefinitionId(propertyState.calledElement, calledElementType)
  propertyState.callBusinessKeyPolicy = businessObject.get('flowable:inheritBusinessKey') === true ? 'INHERIT' : 'NONE'
  propertyState.callInheritVariables = businessObject.get('flowable:inheritVariables') === true
  propertyState.callOutputScope = businessObject.get('flowable:useLocalScopeForOutParameters') === true ? 'LOCAL' : 'PARENT'
  propertyState.callInMappings = readCallMappings(businessObject, 'flowable:In')
  propertyState.callOutMappings = readCallMappings(businessObject, 'flowable:Out')
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
      const configuredIdentity = readConfiguredMultiInstanceIdentity(businessObject)
      propertyState.multiInstanceMemberSource = isConfiguredMultiInstanceLoop(loop)
        ? (configuredIdentity?.source || '')
        : isFixedMultiInstanceLoop(loop)
          ? 'user'
          : isStartMultiInstanceLoop(loop) ? 'start' : 'dynamic'
      // 旧固定用户表达式只在回读时兼容；设计者下一次修改会迁移为统一受控属性。
      propertyState.configuredMultiInstanceIdentityIds = isFixedMultiInstanceLoop(loop)
        ? readFixedMultiInstanceUserIds(loop)
        : (configuredIdentity?.selectionValues || [])
      propertyState.multiInstanceApprovalMode = controlledMultiInstanceApprovalMode(loop)
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
    .filter(property => !isFormParticipantProperty(property.name)
      && !isRoutingCallActivityProperty(property.name))
    .map(property => ({ name: property.name || '', value: property.value || '' })))
}

/**
 * 同步结构化受控任务编辑器的 JSON 草稿，实际 BPMN 写入仍由 change 事件统一执行。
 * @param {string} config ServiceTask 或 SendTask 的受控处理器配置 JSON。
 * @returns {void} 仅更新当前属性面板状态。
 */
function updateControlledTaskConfig(config) {
  propertyState.extensionConfig = String(config ?? '')
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
 * 更新 Participant 绑定的可执行 Process 标识。
 * @returns {void} 通过 bpmn-js 命令栈更新真实 Process 引用；非法或重复标识会恢复原值并报错。
 */
function updateParticipantProperties() {
  if (designerLocked.value || !modeler || !selectedElement.value) return
  const participant = selectedBusinessObject.value
  const processKey = propertyState.processRef.trim()
  const currentProcess = participant?.processRef
  if (!processKey) {
    updateProperties({ processRef: undefined })
    return
  }
  if (!/^[A-Za-z_][A-Za-z0-9_.-]{0,127}$/.test(processKey)) {
    propertyState.processRef = currentProcess?.id || ''
    emit('error', new Error('Participant 流程定义 key 格式不合法'))
    return
  }

  const definitions = findDefinitions(participant)
  const duplicateProcess = definitions?.rootElements?.find(root =>
    root?.$type === 'bpmn:Process' && root !== currentProcess && root.id === processKey
  )
  if (duplicateProcess) {
    propertyState.processRef = currentProcess?.id || ''
    emit('error', new Error(`Participant 流程定义 key 已存在: ${processKey}`))
    return
  }

  const modeling = modeler.get('modeling')
  if (currentProcess) {
    // processRef 是 IDREF，必须修改被引用 Process 的 id，直接写字符串会序列化为 processRef="undefined"。
    modeling.updateModdleProperties(selectedElement.value, currentProcess, {
      id: processKey
    })
    return
  }
  if (!definitions) {
    emit('error', new Error('Participant 缺少 BPMN Definitions，无法建立流程引用'))
    return
  }

  // 异常导入可能产生无 Process 的 Participant；补建的空池先保持不可执行，避免保存门禁要求它提前具备完整执行图。
  const process = modeler.get('moddle').create('bpmn:Process', { id: processKey, isExecutable: false })
  modeling.updateModdleProperties(selectedElement.value, definitions, {
    rootElements: [...(definitions.rootElements || []), process]
  })
  updateProperties({ processRef: process })
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
 * 创建、更新或删除 BPMN 文档说明。
 * @returns {void} 无返回值。
 */
function updateDocumentation() {
  const text = propertyState.documentation.trim()
  const moddle = modeler.get('moddle')
  updateProperties({ documentation: text ? [moddle.create('bpmn:Documentation', { text })] : [] })
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
 * @param {string} sourceXml 可选的已冻结保存快照；为空时从当前画布序列化。
 * @returns {Promise<boolean>} true 表示通过保存和部署共同门禁。
 */
async function runServerValidation(showResult, sourceXml = '') {
  if (props.saving) return false
  validating.value = true
  try {
    const xml = sourceXml || await emitPersistedXml()
    const response = await validateModelBpmn(xml)
    const report = response.data || {}
    const issues = Array.isArray(report.issues) ? [...report.issues] : []
    const hasError = issues.some(issue => issue.severity === 'ERROR')
    validationPassed.value = report.valid === true && !hasError
    if (!validationPassed.value && !hasError) {
      // 服务端未明确返回可保存结论时失败关闭，避免空 issues 被页面误显示为校验通过。
      issues.push({
        code: 'BPMN_VALIDATION_INCOMPLETE',
        severity: 'ERROR',
        elementId: null,
        message: '服务端未返回可保存的流程校验结论'
      })
    }
    validationIssues.value = issues
    if (showResult) validationVisible.value = true
    return validationPassed.value
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
 * 请求页面把字段完整的偏好写入当前用户浏览器存储。
 * @param {object} preference 设计器设置抽屉提交的完整偏好。
 * @returns {void} 真实结果由 preference Prop 回写后应用。
 */
function requestPreferenceSave(preference) {
  emit('preference-save', normalizePreference(preference))
}

/**
 * 请求页面删除当前用户偏好键并恢复当前协议默认值。
 * @returns {void} 默认值由页面回写 preference Prop 后统一应用。
 */
function requestPreferenceReset() {
  emit('preference-reset')
}

/**
 * 切换属性面板折叠状态，并要求页面持久化完整偏好。
 * @returns {void} 页面完成本地持久化前不改变已应用状态。
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
 * 为当前激活的设计页绑定全局交互和主体尺寸监听。
 * @returns {void} 已绑定时直接返回，避免 keep-alive 初次挂载重复注册。
 */
function bindDesignerLifecycleListeners() {
  if (lifecycleListenersBound) return
  if (systemThemeQuery) {
    handleSystemTheme(systemThemeQuery)
    systemThemeQuery.addEventListener('change', handleSystemTheme)
  }
  window.addEventListener('keydown', handleDesignerShortcut)
  window.addEventListener('pointermove', handlePropertiesResizeMove)
  window.addEventListener('pointerup', stopPropertiesResize)
  window.addEventListener('pointercancel', stopPropertiesResize)
  if (typeof ResizeObserver !== 'undefined') {
    bodyResizeObserver = new ResizeObserver(handleDesignerBodyResize)
    if (bodyRef.value) bodyResizeObserver.observe(bodyRef.value)
  }
  lifecycleListenersBound = true
}

/**
 * 释放缓存设计页持有的全局交互和主体尺寸监听。
 * @returns {void} 同时终止拖拽并取消待执行画布帧，避免离页后污染其他页面。
 */
function unbindDesignerLifecycleListeners() {
  window.removeEventListener('keydown', handleDesignerShortcut)
  window.removeEventListener('pointermove', handlePropertiesResizeMove)
  window.removeEventListener('pointerup', stopPropertiesResize)
  window.removeEventListener('pointercancel', stopPropertiesResize)
  systemThemeQuery?.removeEventListener('change', handleSystemTheme)
  stopPropertiesResize()
  if (canvasResizeFrame) window.cancelAnimationFrame(canvasResizeFrame)
  canvasResizeFrame = undefined
  bodyResizeObserver?.disconnect()
  bodyResizeObserver = undefined
  lifecycleListenersBound = false
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
 * @returns {void} 当前用户浏览器偏好写入后由监听器进入或退出模拟。
 */
function toggleSimulation() {
  requestPreferenceSave({
    ...appliedPreference.value,
    tokenSimulationEnabled: !appliedPreference.value.tokenSimulationEnabled
  })
}

/**
 * 把当前用户浏览器偏好同步到网格、小地图和 Token 模拟服务。
 * @returns {void} Modeler 尚未初始化时只保留 Prop 状态。
 */
function applyDesignerPreference() {
  if (!modeler) return
  const preference = appliedPreference.value
  modeler.get('gridSnapping').setActive(preference.gridEnabled)
  modeler.get('minimap').toggle(preference.minimapEnabled)
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
 * 对同一份冻结 XML 依次执行本地结构门禁、服务端门禁并交给页面真实保存。
 * @returns {Promise<void>} 校验失败时仅触发 error，不触发 save。
 */
async function requestSave() {
  if (designerLocked.value) return
  savePreparing.value = true
  // 清除最后一次建模命令留下的延迟导出，保证本次保存窗口内只有一个 XML 序列化任务。
  window.clearTimeout(changeTimer)
  try {
    const pendingMultiInstanceError = validatePendingMultiInstanceSelection()
    if (pendingMultiInstanceError) throw new Error(pendingMultiInstanceError)
    const error = validateDiagram()
    if (error) throw new Error(error)
    // 保存窗口只序列化一次，服务端校验与正式保存必须使用同一内容快照。
    const persistedXml = await emitPersistedXml()
    const serverValid = await runServerValidation(false, persistedXml)
    if (!serverValid) {
      validationVisible.value = true
      return
    }
    emit('save', persistedXml)
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
  // Definitions 是 BPMN 语义模型的权威来源；Collaboration 画布不会把 Process 注册为可见根图形。
  const definitions = modeler.getDefinitions()
  const processes = (definitions?.rootElements || [])
    .filter(element => element.$type === 'bpmn:Process')
  if (!processes.some(process => process.isExecutable)) return '至少需要一个可执行流程'
  for (const process of processes) {
    // 协作图允许存在仅表达参与者边界的非执行池；只有正式可执行流程需要申请入口和发起范围。
    if (!process.isExecutable) continue
    const flowElements = process.flowElements || []
    const startEvents = flowElements.filter(item => item.$type === 'bpmn:StartEvent')
    if (startEvents.length !== 1) return '每个流程必须且只能包含一个开始节点'
    const startEvent = startEvents[0]
    if (!startEvent.get('flowable:formKey') && !hasEmbeddedFormFields(startEvent)) {
      return '开始节点必须配置发起表单'
    }
    const startScopeError = validateParticipantProperties(process, true)
    if (startScopeError) return startScopeError
    try {
      validateAutoCopyRulesForElement(process, ['PROCESS_COMPLETED'])
    } catch (error) {
      return error.message
    }
  }
  const flowElements = processes.flatMap(collectProcessFlowElements)
  const userTasks = flowElements.filter(element => element.$type === 'bpmn:UserTask')
  for (const task of userTasks) {
    const loopError = validateUserTaskMultiInstance(task)
    if (loopError) return loopError
    const participantError = validateParticipantProperties(task, false)
    if (participantError) return participantError
    const slaConfig = readSlaConfig(readExtensionProperties(task))
    if (slaConfig.enabled) {
      try {
        normalizeAndValidateSlaConfig(slaConfig)
      } catch (error) {
        return error.message
      }
    }
    try {
      validateAutoCopyRulesForElement(task, ['NODE_ARRIVED', 'NODE_COMPLETED'])
    } catch (error) {
      return error.message
    }
  }
  const callActivities = flowElements.filter(element => element.$type === 'bpmn:CallActivity')
  for (const callActivity of callActivities) {
    const callError = validateCallActivityConfiguration(callActivity)
    if (callError) return callError
  }
  const businessBoundaries = flowElements.filter(element => element.$type === 'bpmn:BoundaryEvent')
  for (const boundary of businessBoundaries) {
    const definition = boundary.eventDefinitions?.[0]
    const businessType = definition?.$type
    if (!['bpmn:ErrorEventDefinition', 'bpmn:EscalationEventDefinition'].includes(businessType)) continue
    const allowed = businessType === 'bpmn:ErrorEventDefinition' ? errorEventOptions.value : escalationEventOptions.value
    if (!allowed.some(option => option.eventCode === readEventReference(definition))) {
      return '错误或升级边界必须选择已启用的正式业务编码'
    }
    if (businessType === 'bpmn:ErrorEventDefinition' && boundary.cancelActivity === false) {
      return '错误边界必须使用中断语义'
    }
  }
  return ''
}

/**
 * 递归读取一个 Process 的全部流程元素，确保子流程中的任务和边界事件也进入保存门禁。
 *
 * @param {object} process BPMN Process 业务对象。
 * @returns {object[]} 按模型顺序展开的全部顶层及嵌套流程元素。
 */
function collectProcessFlowElements(process) {
  const result = []
  const visit = elements => {
    for (const element of elements || []) {
      result.push(element)
      if (Array.isArray(element.flowElements)) visit(element.flowElements)
    }
  }
  visit(process?.flowElements)
  return result
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

// 三个领域模块共享同一个选择视图，但只能通过主组件提供的 bpmn-js 命令栈入口写回模型。
let formParticipantDomain
let routingCallActivityDomain
const extensionEventSlaDomain = createExtensionEventSlaDomain({
  buildPropertiesExtensionElements,
  designerLocked,
  emit,
  getModeler: () => modeler,
  isForeignProtectedPropertyName: name => (
    isFormParticipantProperty(name) || isRoutingCallActivityProperty(name)
  ),
  isType,
  listBpmnEventCodeOptions,
  listCelExtensionOptions,
  listConnectorEndpointOptions,
  listEnabledSlaCalendars,
  listFormFieldExtensionOptions,
  listHttpExtensionOptions,
  listJavaExtensionOptions,
  listSqlDataSourceOptions,
  listSqlExtensionOptions,
  loadPropertyState,
  persistExtensionProperties,
  propertyFlags,
  propertyState,
  readAllFlowableProperties,
  readExtensionProperties,
  resolveUserIdFieldCatalog: businessObject => (
    formParticipantDomain.resolveUserIdFieldCatalog(businessObject)
  ),
  selectedBusinessObject,
  selectedElement,
  updateProperties
})
formParticipantDomain = createFormParticipantDomain({
  buildPropertiesExtensionElements,
  designerLocked,
  emit,
  formFieldOptions: extensionEventSlaDomain.formFieldOptions,
  getModeler: () => modeler,
  isProcess,
  isUserTask,
  loadPropertyState,
  persistExtensionProperties,
  propertyFlags,
  propertyState,
  props,
  readAllFlowableProperties,
  selectedBusinessObject,
  selectedElement,
  updateProperties
})
routingCallActivityDomain = createRoutingCallActivityDomain({
  buildPropertiesExtensionElements,
  describeFormalFormFields: formParticipantDomain.describeFormalFormFields,
  designerLocked,
  emit,
  getModeler: () => modeler,
  listCallActivityOptions,
  listDmnDecisionOptions,
  loadPropertyState,
  propertyFlags,
  propertyState,
  props,
  readAllFlowableProperties,
  readEmbeddedFormFields: formParticipantDomain.readEmbeddedFormFields,
  selectedBusinessObject,
  selectedElement,
  updateProperties
})

const {
  assignmentOptions,
  multiInstanceOptions,
  multiInstanceApprovalOptions,
  controlledLoopFieldOptions,
  participantFormFieldOptions,
  handlePanelIdentitySearch,
  handlePanelIdentityResolve,
  readParticipantRule,
  readControlledLoop,
  readEmbeddedFormFields,
  isControlledMultiInstanceLoop,
  controlledMultiInstanceApprovalMode,
  isStartMultiInstanceLoop,
  isConfiguredMultiInstanceLoop,
  isFixedMultiInstanceLoop,
  readFixedMultiInstanceUserIds,
  readConfiguredMultiInstanceIdentity,
  splitValues,
  loadFormPermissionState,
  updateFormSource,
  updateFormKey,
  updateEmbeddedForm,
  updateFormPermissions,
  updateAssignment,
  updateParticipantRule,
  updateUserTaskProperties,
  updateMultiInstance,
  validateParticipantProperties,
  hasEmbeddedFormFields,
  validateUserTaskMultiInstance,
  disposeIdentitySearchTimers,
  validatePendingMultiInstanceSelection
} = formParticipantDomain
const {
  dmnOptions,
  dmnLoading,
  callActivityOptions,
  callActivityLoading,
  conditionFieldOptions,
  conditionContext,
  callActivityParentFields,
  readConditionRule,
  isDefaultConditionFlow,
  isConditionGatewayFlow,
  updateDmnDecision,
  loadDmnOptions,
  loadCallActivityOptions,
  resolveCallDefinitionId,
  readCallMappings,
  updateCallActivityProperties,
  updateConditionRule,
  makeConditionDefault,
  validateCallActivityConfiguration
} = routingCallActivityDomain
const {
  extensionOptions,
  businessListenerOptions,
  formFieldOptions,
  connectorEndpoints,
  sqlDataSources,
  extensionLoading,
  errorEventOptions,
  escalationEventOptions,
  eventCodeLoading,
  slaCalendarOptions,
  slaLoading,
  autoCopyTriggerOptions,
  autoCopyFormFieldOptions,
  readAutoCopyRules,
  readControlledTaskExtension,
  readBusinessListeners,
  updateControlledTask,
  updateControlledTaskSelection,
  loadExtensionOptions,
  updateBusinessExecutionListeners,
  updateBusinessTaskListeners,
  updateExtensionProperties,
  validateAutoCopyRulesForElement,
  updateAutoCopyRules,
  findDefinitions,
  updateEventProperties,
  loadEventPropertyState,
  readSlaConfig,
  updateSlaProperties,
  normalizeAndValidateSlaConfig,
  readEventReference
} = extensionEventSlaDomain

watch(() => props.modelValue, value => {
  if (value && value !== lastExportedXml.value && modeler) importXml(value)
})
/**
 * 在服务端偏好回写后同步设计器插件与布局尺寸。
 * @returns {void} 关闭设置抽屉并安排一次画布视口重算。
 */
watch(() => props.preference, () => {
  applyDesignerPreference()
  settingsVisible.value = false
  scheduleCanvasResize()
}, { deep: true })
watch(designerLocked, handleDesignerLock)
onActivated(() => {
  bindDesignerLifecycleListeners()
  repairCachedSequenceFlowReferences()
})
onDeactivated(unbindDesignerLifecycleListeners)

/**
 * 初始化系统主题、快捷键、属性面板尺寸监听和设计器正式目录。
 * @returns {void} 组件挂载完成后启动监听并导入当前 BPMN XML。
 */
onMounted(() => {
  systemThemeQuery = window.matchMedia('(prefers-color-scheme: dark)')
  bindDesignerLifecycleListeners()
  loadExtensionOptions()
  loadDmnOptions()
  loadCallActivityOptions()
  importXml(props.modelValue)
})
/**
 * 释放属性面板拖拽、尺寸观察器、身份检索计时器和 bpmn-js 实例。
 * @returns {void} 组件卸载前恢复全局页面状态并移除全部本组件监听。
 */
onBeforeUnmount(() => {
  window.clearTimeout(changeTimer)
  unbindDesignerLifecycleListeners()
  disposeIdentitySearchTimers()
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
