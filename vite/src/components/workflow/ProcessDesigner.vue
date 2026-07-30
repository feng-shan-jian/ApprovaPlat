<template>
  <div class="process-designer" :style="designerStyle">
    <header class="process-designer__toolbar">
      <div class="process-designer__toolbar-group">
        <el-tooltip content="撤销" placement="bottom">
          <el-button circle text icon="RefreshLeft" aria-label="撤销" :disabled="designerLocked || !canUndo" @click="undo" />
        </el-tooltip>
        <el-tooltip content="重做" placement="bottom">
          <el-button circle text icon="RefreshRight" aria-label="重做" :disabled="designerLocked || !canRedo" @click="redo" />
        </el-tooltip>
        <el-divider direction="vertical" />
        <el-tooltip content="缩小" placement="bottom">
          <el-button circle text icon="ZoomOut" aria-label="缩小流程图" :disabled="designerLocked" @click="zoomBy(0.85)" />
        </el-tooltip>
        <el-tooltip content="适应窗口" placement="bottom">
          <el-button circle text icon="FullScreen" aria-label="适应窗口" :disabled="designerLocked" @click="fitViewport" />
        </el-tooltip>
        <el-tooltip content="放大" placement="bottom">
          <el-button circle text icon="ZoomIn" aria-label="放大流程图" :disabled="designerLocked" @click="zoomBy(1.15)" />
        </el-tooltip>
      </div>
      <div class="process-designer__toolbar-group">
        <el-tooltip content="下载 BPMN" placement="bottom">
          <el-button circle text icon="Download" aria-label="下载 BPMN" :disabled="designerLocked" @click="downloadXml" />
        </el-tooltip>
        <el-button
          v-hasPermi="['workflow:model:save']"
          type="primary"
          icon="Check"
          :loading="designerLocked"
          @click="requestSave"
        >保存</el-button>
      </div>
    </header>

    <div
      ref="bodyRef"
      v-loading="designerLocked"
      class="process-designer__body"
      :inert="designerLocked"
      :aria-busy="designerLocked"
    >
      <div ref="canvasRef" class="process-designer__canvas" v-loading="loading" />
      <aside class="process-designer__properties">
        <div class="process-designer__properties-title">
          <span>{{ selectedTypeLabel }}</span>
          <el-tag v-if="propertyState.id" size="small" type="info">{{ propertyState.id }}</el-tag>
        </div>

        <el-scrollbar v-if="selectedElement" class="process-designer__properties-scroll">
          <el-form label-position="top" size="small" class="process-designer__form">
            <el-form-item label="元素名称">
              <el-input v-model="propertyState.name" maxlength="255" @change="updateCommonProperties" />
            </el-form-item>
            <el-form-item label="元素标识">
              <el-input v-model="propertyState.id" maxlength="128" @change="updateElementId" />
            </el-form-item>

            <template v-if="isProcess">
              <el-form-item label="可执行流程">
                <el-switch v-model="propertyState.executable" @change="updateProcessProperties" />
              </el-form-item>
            </template>

            <template v-if="isStartEvent || isUserTask">
              <el-form-item :label="isStartEvent ? '发起表单' : '节点表单'" :required="isStartEvent">
                <el-select v-model="propertyState.formKey" filterable clearable @change="updateFormKey">
                  <el-option v-for="form in forms" :key="form.formId" :label="form.formName" :value="`key_${form.formId}`" />
                </el-select>
              </el-form-item>
            </template>

            <template v-if="isUserTask">
              <el-form-item label="多实例">
                <el-segmented v-model="propertyState.multiInstanceType" :options="multiInstanceOptions" @change="updateMultiInstance" />
              </el-form-item>
              <el-form-item v-if="propertyState.multiInstanceType === 'controlled'" label="签署规则">
                <el-segmented v-model="propertyState.multiInstanceApprovalMode" :options="multiInstanceApprovalOptions" @change="updateMultiInstance" />
              </el-form-item>
              <template v-if="['sequential', 'parallel'].includes(propertyState.multiInstanceType)">
                <el-form-item label="集合表达式">
                  <el-input v-model="propertyState.collection" maxlength="256" @change="updateMultiInstance" />
                </el-form-item>
                <el-form-item label="元素变量">
                  <el-input v-model="propertyState.elementVariable" maxlength="128" @change="updateMultiInstance" />
                </el-form-item>
                <el-form-item label="完成条件">
                  <el-input v-model="propertyState.completionCondition" type="textarea" :rows="2" maxlength="512" @change="updateMultiInstance" />
                </el-form-item>
              </template>
              <template v-if="propertyState.multiInstanceType !== 'controlled'">
                <el-form-item label="办理方式">
                  <el-segmented v-model="propertyState.assignmentType" :options="assignmentOptions" @change="updateAssignment" />
                </el-form-item>
                <el-form-item v-if="propertyState.assignmentType === 'assignee'" label="办理人">
                <el-select
                  v-model="propertyState.assignee"
                  filterable
                  clearable
                  remote
                  reserve-keyword
                  :remote-method="searchAssignees"
                  :loading="identityLoading"
                  @change="updateAssignment"
                >
                  <el-option v-for="user in identityOptions.assignees" :key="user.value" :label="user.label" :value="String(user.value)" />
                </el-select>
                </el-form-item>
                <el-form-item v-if="propertyState.assignmentType === 'users'" label="候选用户">
                <el-select
                  v-model="propertyState.candidateUsers"
                  multiple
                  filterable
                  remote
                  reserve-keyword
                  :remote-method="searchCandidateUsers"
                  :loading="identityLoading"
                  @change="updateAssignment"
                >
                  <el-option v-for="user in identityOptions.candidateUsers" :key="user.value" :label="user.label" :value="String(user.value)" />
                </el-select>
                </el-form-item>
                <el-form-item v-if="propertyState.assignmentType === 'groups'" label="候选角色或部门">
                <el-select
                  v-model="propertyState.candidateGroups"
                  multiple
                  filterable
                  remote
                  reserve-keyword
                  :remote-method="searchCandidateGroups"
                  :loading="identityLoading"
                  @change="updateAssignment"
                >
                  <el-option v-for="group in identityOptions.candidateGroups" :key="group.value" :label="group.label" :value="group.value" />
                </el-select>
                </el-form-item>
              </template>
              <el-form-item label="到期时间">
                <el-input v-model="propertyState.dueDate" maxlength="128" @change="updateUserTaskProperties" />
              </el-form-item>
              <el-form-item label="优先级">
                <el-input v-model="propertyState.priority" maxlength="128" @change="updateUserTaskProperties" />
              </el-form-item>
            </template>

            <template v-if="isServiceTask">
              <el-form-item label="实现方式">
                <el-segmented v-model="propertyState.implementationType" :options="implementationOptions" @change="updateServiceTask" />
              </el-form-item>
              <el-form-item label="实现配置">
                <el-input v-model="propertyState.implementation" maxlength="255" @change="updateServiceTask" />
              </el-form-item>
            </template>

            <template v-if="isSequenceFlow">
              <el-form-item label="流转条件">
                <el-input v-model="propertyState.conditionExpression" type="textarea" :rows="3" maxlength="1024" @change="updateCondition" />
              </el-form-item>
            </template>

            <el-form-item label="说明">
              <el-input v-model="propertyState.documentation" type="textarea" :rows="3" maxlength="1000" @change="updateDocumentation" />
            </el-form-item>
          </el-form>
        </el-scrollbar>
        <el-empty v-else description="未选择流程元素" :image-size="64" />
      </aside>
    </div>
  </div>
</template>

<script setup name="ProcessDesigner">
import Modeler from 'bpmn-js/lib/Modeler'
import minimapModule from 'diagram-js-minimap'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import 'diagram-js-minimap/assets/diagram-js-minimap.css'
import Download from '@/plugins/download'
import flowableModdle from './bpmn/flowableModdle'
import { normalizeTaskListenerXml } from './taskListenerXml'

// 动态多实例的技术属性由设计器固定写入，页面不向设计者开放任意方法或变量名。
const CONTROLLED_MULTI_INSTANCE_COLLECTION = '${multiInstanceHandler.getUserIds(execution)}'
const CONTROLLED_MULTI_INSTANCE_ASSIGNEE = '${assignee}'
const CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE = 'assignee'
const CONTROLLED_MULTI_INSTANCE_ALL_CONDITION = '${nrOfCompletedInstances == nrOfInstances}'
const CONTROLLED_MULTI_INSTANCE_ANY_CONDITION = '${nrOfCompletedInstances > 0}'

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
  identityLoading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change', 'save', 'error', 'identity-search'])
const canvasRef = ref(null)
const bodyRef = ref(null)
const loading = ref(false)
// savePreparing 覆盖保存前 XML 序列化窗口，避免父页面 saving 回写前产生重复保存命令。
const savePreparing = ref(false)
const canUndo = ref(false)
const canRedo = ref(false)
const selectedElement = shallowRef(null)
const lastExportedXml = ref('')
const propertyState = reactive(createEmptyPropertyState())
const designerStyle = computed(() => ({ height: props.height }))
const designerLocked = computed(() => props.saving || savePreparing.value)
const selectedBusinessObject = computed(() => selectedElement.value?.businessObject)
const isProcess = computed(() => isType('bpmn:Process'))
const isStartEvent = computed(() => isType('bpmn:StartEvent'))
const isUserTask = computed(() => isType('bpmn:UserTask'))
const isServiceTask = computed(() => isType('bpmn:ServiceTask'))
const isSequenceFlow = computed(() => isType('bpmn:SequenceFlow'))
const selectedTypeLabel = computed(() => typeLabel(selectedBusinessObject.value?.$type))
const assignmentOptions = [
  { label: '办理人', value: 'assignee' },
  { label: '用户', value: 'users' },
  { label: '角色/部门', value: 'groups' }
]
// none/sequential/parallel 对应标准 BPMN 多实例；controlled 冻结为 multiInstanceHandler 动态成员契约。
const multiInstanceOptions = [
  { label: '无', value: 'none' },
  { label: '串行', value: 'sequential' },
  { label: '并行', value: 'parallel' },
  { label: '动态', value: 'controlled' }
]
// all/any 分别映射全员完成与任一完成条件，决定动态多实例的会签或或签终止语义。
const multiInstanceApprovalOptions = [
  { label: '会签', value: 'all' },
  { label: '或签', value: 'any' }
]
const implementationOptions = [
  { label: 'Java 类', value: 'class' },
  { label: 'Spring Bean', value: 'delegateExpression' }
]
let modeler
let changeTimer
const identitySearchTimers = new Map()
let importing = false

// 三类检索目标分别冻结后端身份类型和能力，防止切换办理方式时降级为通用目录。
const IDENTITY_SEARCH_CONTRACTS = Object.freeze({
  assignees: Object.freeze({ type: 'user', capability: 'approval' }),
  candidateUsers: Object.freeze({ type: 'user', capability: 'claim' }),
  candidateGroups: Object.freeze({ type: 'group', capability: 'claim' })
})

/**
 * 创建属性面板的稳定初始状态。
 * @returns {object} 不携带上一元素值的新状态对象。
 */
function createEmptyPropertyState() {
  return {
    id: '',
    name: '',
    executable: true,
    formKey: '',
    assignmentType: 'assignee',
    assignee: '',
    candidateUsers: [],
    candidateGroups: [],
    dueDate: '',
    priority: '',
    implementationType: 'class',
    implementation: '',
    conditionExpression: '',
    documentation: '',
    multiInstanceType: 'none',
    multiInstanceApprovalMode: 'all',
    collection: '',
    elementVariable: '',
    completionCondition: ''
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
 * 检索具备直接任务办理资格的启用用户。
 * @param {string} keyword 用户姓名、账号或编号关键字。
 * @returns {void} 请求通过 identity-search 事件交由页面执行。
 */
function searchAssignees(keyword) {
  scheduleIdentitySearch('assignees', keyword)
}

/**
 * 检索具备完整认领和后续办理资格的候选用户。
 * @param {string} keyword 用户姓名、账号或编号关键字。
 * @returns {void} 请求通过 identity-search 事件交由页面执行。
 */
function searchCandidateUsers(keyword) {
  scheduleIdentitySearch('candidateUsers', keyword)
}

/**
 * 检索至少包含一名完整可认领办理成员的候选角色和部门。
 * @param {string} keyword 角色或部门名称、编码关键字。
 * @returns {void} 请求通过 identity-search 事件交由页面执行。
 */
function searchCandidateGroups(keyword) {
  scheduleIdentitySearch('candidateGroups', keyword)
}

/**
 * 初始化 bpmn-js Modeler、Flowable moddle 和事件监听。
 * @returns {void} Modeler 生命周期由组件管理。
 */
function createModeler() {
  if (modeler || !canvasRef.value) return
  modeler = new Modeler({
    container: canvasRef.value,
    additionalModules: [minimapModule],
    moddleExtensions: { flowable: flowableModdle }
  })
  const eventBus = modeler.get('eventBus')
  eventBus.on('selection.changed', event => selectElement(event.newSelection?.[0]))
  eventBus.on('element.changed', event => {
    if (event.element === selectedElement.value) loadPropertyState(event.element)
  })
  eventBus.on('commandStack.changed', handleCommandStackChanged)
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
    const source = xml?.trim() ? xml : createInitialXml()
    await modeler.importXML(source)
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
 * 处理命令栈变化，更新撤销按钮并节流导出 XML。
 * @returns {void} 无返回值。
 */
function handleCommandStackChanged() {
  updateCommandState()
  if (importing) return
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
  propertyState.formKey = businessObject.get('flowable:formKey') || ''
  propertyState.assignee = businessObject.get('flowable:assignee') || ''
  propertyState.candidateUsers = splitValues(businessObject.get('flowable:candidateUsers'))
  propertyState.candidateGroups = splitValues(businessObject.get('flowable:candidateGroups'))
  propertyState.assignmentType = propertyState.candidateGroups.length
    ? 'groups'
    : propertyState.candidateUsers.length ? 'users' : 'assignee'
  propertyState.dueDate = businessObject.get('flowable:dueDate') || ''
  propertyState.priority = businessObject.get('flowable:priority') || ''
  propertyState.documentation = businessObject.documentation?.[0]?.text || ''
  propertyState.conditionExpression = businessObject.conditionExpression?.body || ''
  const delegateExpression = businessObject.get('flowable:delegateExpression') || ''
  propertyState.implementationType = delegateExpression ? 'delegateExpression' : 'class'
  propertyState.implementation = delegateExpression || businessObject.get('flowable:class') || ''
  const loop = businessObject.loopCharacteristics
  if (loop) {
    propertyState.collection = loop.get('flowable:collection') || ''
    propertyState.elementVariable = loop.get('flowable:elementVariable') || ''
    propertyState.completionCondition = loop.completionCondition?.body || ''
    if (isControlledMultiInstanceLoop(loop)) {
      propertyState.multiInstanceType = 'controlled'
      propertyState.multiInstanceApprovalMode = propertyState.completionCondition === CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
        ? 'any'
        : 'all'
    } else {
      propertyState.multiInstanceType = loop.isSequential ? 'sequential' : 'parallel'
    }
  }
}

/**
 * 判断循环配置是否引用生产动态多实例 handler；完整结构仍由保存门禁单独核验。
 * @param {object|undefined} loop bpmn-js 多实例循环业务对象。
 * @returns {boolean} 集合表达式精确引用固定 handler 时返回 true。
 */
function isControlledMultiInstanceLoop(loop) {
  return Boolean(loop && String(loop.get?.('flowable:collection') || '').trim() === CONTROLLED_MULTI_INSTANCE_COLLECTION)
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
    'bpmn:StartEvent': '开始节点',
    'bpmn:EndEvent': '结束节点',
    'bpmn:UserTask': '用户任务',
    'bpmn:ServiceTask': '服务任务',
    'bpmn:ExclusiveGateway': '排他网关',
    'bpmn:ParallelGateway': '并行网关',
    'bpmn:SequenceFlow': '顺序流',
    'bpmn:SubProcess': '子流程'
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
  updateProperties({ isExecutable: Boolean(propertyState.executable) })
}

/**
 * 更新开始节点或用户任务的正式表单键。
 * @returns {void} 无返回值。
 */
function updateFormKey() {
  updateProperties({ 'flowable:formKey': propertyState.formKey || undefined })
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
    'flowable:priority': propertyState.priority.trim() || undefined
  })
}

/**
 * 更新服务任务受控 Java 类或 Spring Bean 实现。
 * @returns {void} 互斥清理另一种实现属性。
 */
function updateServiceTask() {
  const implementation = propertyState.implementation.trim() || undefined
  updateProperties({
    'flowable:class': propertyState.implementationType === 'class' ? implementation : undefined,
    'flowable:delegateExpression': propertyState.implementationType === 'delegateExpression' ? implementation : undefined,
    'flowable:expression': undefined
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
 * 创建、更新或删除用户任务多实例配置；动态模式一次写入完整固定技术契约。
 * @returns {void} 无返回值。
 */
function updateMultiInstance() {
  const existingLoop = selectedBusinessObject.value?.loopCharacteristics
  const wasControlled = isControlledMultiInstanceLoop(existingLoop)
  if (propertyState.multiInstanceType === 'none') {
    const changes = { loopCharacteristics: undefined }
    if (wasControlled) resetControlledAssignment(changes)
    updateProperties(changes)
    return
  }
  const moddle = modeler.get('moddle')
  const controlled = propertyState.multiInstanceType === 'controlled'
  const leavingControlled = !controlled && wasControlled
  let collection = controlled
    ? CONTROLLED_MULTI_INSTANCE_COLLECTION
    : propertyState.collection.trim()
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
  const changes = { loopCharacteristics: loop }
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
  if (!designerLocked.value && modeler) modeler.get('canvas').zoom('fit-viewport')
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
 * 导出可被后端再次保存或部署的 BPMN XML 文件。
 * @returns {Promise<void>} 导出失败时触发 error。
 */
async function downloadXml() {
  if (designerLocked.value) return
  try {
    const xml = await emitPersistedXml()
    const name = props.model.modelKey || 'workflow'
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
    if (!startEvents[0].get('flowable:formKey')) return '开始节点必须配置发起表单'
  }
  const userTasks = registry.filter(element => element.type === 'bpmn:UserTask')
  for (const element of userTasks) {
    const task = element.businessObject
    const loopError = validateUserTaskMultiInstance(task)
    if (loopError) return loopError
  }
  return ''
}

/**
 * 校验用户任务的动态 handler 只以固定并行会签/或签组合出现，并阻断近似方法名。
 * @param {object} task bpmn-js 用户任务业务对象。
 * @returns {string} 空串表示通过，否则返回稳定业务错误。
 */
function validateUserTaskMultiInstance(task) {
  const loop = task.loopCharacteristics
  if (!loop) return ''
  const collection = String(loop.get?.('flowable:collection') || '').trim()
  if (!collection.includes('multiInstanceHandler')) return ''
  const condition = String(loop.completionCondition?.body || '').trim()
  const approvedCondition = [
    CONTROLLED_MULTI_INSTANCE_ALL_CONDITION,
    CONTROLLED_MULTI_INSTANCE_ANY_CONDITION
  ].includes(condition)
  const parentIsMainProcess = task.$parent?.$type === 'bpmn:Process'
  const hasBoundaryEvents = Array.isArray(task.boundaryEventRefs) && task.boundaryEventRefs.length > 0
  if (collection !== CONTROLLED_MULTI_INSTANCE_COLLECTION
    || loop.isSequential
    || loop.get('flowable:elementVariable') !== CONTROLLED_MULTI_INSTANCE_ELEMENT_VARIABLE
    || task.get('flowable:assignee') !== CONTROLLED_MULTI_INSTANCE_ASSIGNEE
    || task.get('flowable:candidateUsers')
    || task.get('flowable:candidateGroups')
    || loop.loopCardinality
    || !approvedCondition
    || task.isForCompensation
    || hasBoundaryEvents
    || !parentIsMainProcess) {
    return '动态多实例配置不符合受控会签或或签契约'
  }
  return ''
}

watch(() => props.modelValue, value => {
  if (value && value !== lastExportedXml.value && modeler) importXml(value)
})
watch(designerLocked, handleDesignerLock)

onMounted(() => importXml(props.modelValue))
onBeforeUnmount(() => {
  window.clearTimeout(changeTimer)
  identitySearchTimers.forEach(timer => window.clearTimeout(timer))
  identitySearchTimers.clear()
  if (modeler) modeler.destroy()
  modeler = undefined
})

defineExpose({ requestSave, downloadXml, fitViewport, getXml: () => emitPersistedXml() })
</script>

<style scoped lang="scss">
.process-designer {
  display: grid;
  grid-template-rows: 48px minmax(0, 1fr);
  min-height: 640px;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}

.process-designer__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px 0 8px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.process-designer__toolbar-group {
  display: flex;
  align-items: center;
  gap: 2px;
}

.process-designer__body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  min-height: 0;
}

.process-designer__canvas {
  min-width: 0;
  min-height: 0;
  background-color: #fbfcfd;
  background-image: linear-gradient(#eef1f4 1px, transparent 1px), linear-gradient(90deg, #eef1f4 1px, transparent 1px);
  background-size: 20px 20px;
}

.process-designer__properties {
  min-width: 0;
  overflow: hidden;
  border-left: 1px solid var(--el-border-color-light);
}

.process-designer__properties-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 46px;
  padding: 0 14px;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.process-designer__properties-title .el-tag {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.process-designer__properties-scroll {
  height: calc(100% - 46px);
}

.process-designer__form {
  padding: 14px;
}

.process-designer__form :deep(.el-select),
.process-designer__form :deep(.el-segmented) {
  width: 100%;
}

:deep(.djs-minimap) {
  top: auto;
  right: 10px;
  bottom: 10px;
  border-color: var(--el-border-color-light);
}

@media (max-width: 900px) {
  .process-designer {
    height: auto !important;
  }

  .process-designer__body {
    grid-template-columns: 1fr;
    grid-template-rows: 560px 360px;
  }

  .process-designer__properties {
    border-top: 1px solid var(--el-border-color-light);
    border-left: 0;
  }
}
</style>
