<template>
  <div class="advanced-element-palette">
    <el-popover
      v-model:visible="visible"
      placement="right-start"
      :width="344"
      trigger="click"
      popper-class="advanced-element-palette__popover"
    >
      <template #reference>
        <span class="advanced-element-palette__reference">
          <el-tooltip content="高级流程元素" placement="right">
            <el-button
              class="advanced-element-palette__trigger"
              :disabled="disabled"
              circle
              aria-label="高级流程元素"
            >
              <el-icon><Plus /></el-icon>
            </el-button>
          </el-tooltip>
        </span>
      </template>

      <div class="advanced-element-palette__content" role="menu" aria-label="高级流程元素">
        <section v-for="group in groups" :key="group.key" class="advanced-element-palette__group">
          <h4>{{ group.label }}</h4>
          <div class="advanced-element-palette__grid">
            <button
              v-for="item in group.items"
              :key="item.key"
              type="button"
              class="advanced-element-palette__item"
              role="menuitem"
              @mousedown.prevent.stop="startCreate($event, item)"
            >
              <i :class="item.icon" aria-hidden="true" />
              <span>{{ item.label }}</span>
            </button>
          </div>
        </section>
      </div>
    </el-popover>
  </div>
</template>

<script setup name="AdvancedElementPalette">
import { Plus } from '@element-plus/icons-vue'
import { getTaskCapability } from './taskCapabilityMap.js'

defineProps({
  /** 是否禁止开始新的建模命令。 */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['create'])
const visible = ref(false)

// 任务入口保留自身展示元数据，并以唯一能力表决定能否创建，避免菜单与属性面板各自维护可用类型。
const taskItems = [
  paletteItem('service-task', '服务任务', 'bpmn:ServiceTask', 'bpmn-icon-service-task'),
  paletteItem('send-task', '发送任务', 'bpmn:SendTask', 'bpmn-icon-send-task'),
  paletteItem('receive-task', '接收任务', 'bpmn:ReceiveTask', 'bpmn-icon-receive-task'),
  paletteItem('business-rule-task', '业务规则任务', 'bpmn:BusinessRuleTask', 'bpmn-icon-business-rule-task')
].filter(item => getTaskCapability(item.type)?.creationAllowed)

// 元素定义只描述标准 BPMN 类型和创建提示，真实业务对象由父级 Modeler 服务创建。
const groups = Object.freeze([
  {
    key: 'tasks',
    label: '任务与活动',
    items: [
      ...taskItems,
      paletteItem('call-activity', '调用活动', 'bpmn:CallActivity', 'bpmn-icon-call-activity'),
      paletteItem('sub-process', '展开子流程', 'bpmn:SubProcess', 'bpmn-icon-subprocess-expanded', { withStartEvent: true }),
      paletteItem('event-sub-process', '事件子流程', 'bpmn:SubProcess', 'bpmn-icon-event-subprocess-expanded', { withStartEvent: true, triggeredByEvent: true }),
      paletteItem('transaction', '事务', 'bpmn:Transaction', 'bpmn-icon-transaction', { withStartEvent: true })
    ]
  },
  {
    key: 'gateways',
    label: '网关',
    items: [
      paletteItem('parallel-gateway', '并行网关', 'bpmn:ParallelGateway', 'bpmn-icon-gateway-parallel'),
      paletteItem('inclusive-gateway', '包容网关', 'bpmn:InclusiveGateway', 'bpmn-icon-gateway-or'),
      paletteItem('event-gateway', '事件网关', 'bpmn:EventBasedGateway', 'bpmn-icon-gateway-eventbased')
    ]
  },
  {
    key: 'events',
    label: '事件',
    items: [
      paletteItem('catch-message', '消息捕获', 'bpmn:IntermediateCatchEvent', 'bpmn-icon-intermediate-event-catch-message', { eventDefinitionType: 'bpmn:MessageEventDefinition' }),
      paletteItem('catch-signal', '信号捕获', 'bpmn:IntermediateCatchEvent', 'bpmn-icon-intermediate-event-catch-signal', { eventDefinitionType: 'bpmn:SignalEventDefinition' }),
      paletteItem('catch-timer', '定时捕获', 'bpmn:IntermediateCatchEvent', 'bpmn-icon-intermediate-event-catch-timer', { eventDefinitionType: 'bpmn:TimerEventDefinition' }),
      paletteItem('throw-message', '消息抛出', 'bpmn:IntermediateThrowEvent', 'bpmn-icon-intermediate-event-throw-message', { eventDefinitionType: 'bpmn:MessageEventDefinition' }),
      paletteItem('throw-signal', '信号抛出', 'bpmn:IntermediateThrowEvent', 'bpmn-icon-intermediate-event-throw-signal', { eventDefinitionType: 'bpmn:SignalEventDefinition' }),
      paletteItem('boundary-error', '错误边界', 'bpmn:BoundaryEvent', 'bpmn-icon-intermediate-event-catch-error', { eventDefinitionType: 'bpmn:ErrorEventDefinition', cancelActivity: true }),
      paletteItem('boundary-escalation', '升级边界', 'bpmn:BoundaryEvent', 'bpmn-icon-intermediate-event-catch-escalation', { eventDefinitionType: 'bpmn:EscalationEventDefinition', cancelActivity: true }),
      paletteItem('boundary-compensation', '补偿边界', 'bpmn:BoundaryEvent', 'bpmn-icon-intermediate-event-catch-compensation', { eventDefinitionType: 'bpmn:CompensateEventDefinition', cancelActivity: false })
    ]
  },
  {
    key: 'collaboration',
    label: '协作与数据',
    items: [
      paletteItem('participant', '池 / 参与者', 'bpmn:Participant', 'bpmn-icon-participant', { participant: true }),
      paletteItem('lane', '新增泳道', '', 'bpmn-icon-lane', { action: 'add-lane' }),
      paletteItem('data-object', '数据对象', 'bpmn:DataObjectReference', 'bpmn-icon-data-object'),
      paletteItem('data-store', '数据存储', 'bpmn:DataStoreReference', 'bpmn-icon-data-store'),
      paletteItem('group', '分组', 'bpmn:Group', 'bpmn-icon-group'),
      paletteItem('annotation', '文本注释', 'bpmn:TextAnnotation', 'bpmn-icon-text-annotation'),
      paletteItem('connect', '协作消息流 / 关联', '', 'bpmn-icon-connection-multi', { action: 'global-connect' })
    ]
  }
])

/**
 * 创建不可变的 Palette 元素定义。
 * @param {string} key 元素稳定键。
 * @param {string} label 用户可见名称。
 * @param {string} type 标准 BPMN moddle 类型；工具动作允许为空。
 * @param {string} icon bpmn-js 字体图标类名。
 * @param {object} options 传递给 Modeler 元素工厂的受控创建提示。
 * @returns {object} 可供菜单渲染和父组件执行的不可变定义。
 */
function paletteItem(key, label, type, icon, options = {}) {
  return Object.freeze({ key, label, type, icon, ...options })
}

/**
 * 把真实鼠标事件和受控元素定义交给父级 Modeler，并立即收起菜单。
 * @param {MouseEvent} event 菜单项按下事件，作为 bpmn-js 拖放创建起点。
 * @param {object} item 当前选择的不可变元素定义。
 * @returns {void} 无返回值。
 */
function startCreate(event, item) {
  emit('create', item, event)
  visible.value = false
}
</script>

<style scoped>
.advanced-element-palette {
  position: absolute;
  top: 60px;
  left: 74px;
  z-index: 12;
}

.advanced-element-palette__reference {
  display: inline-flex;
}

.advanced-element-palette__trigger {
  width: 32px;
  height: 32px;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgb(15 23 42 / 16%);
}

.advanced-element-palette__content {
  max-height: min(680px, calc(100vh - 190px));
  overflow-y: auto;
}

.advanced-element-palette__group + .advanced-element-palette__group {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.advanced-element-palette__group h4 {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
}

.advanced-element-palette__grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px;
}

.advanced-element-palette__item {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr);
  align-items: center;
  min-height: 34px;
  padding: 4px 8px;
  color: var(--el-text-color-regular);
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: grab;
}

.advanced-element-palette__item:hover,
.advanced-element-palette__item:focus-visible {
  color: var(--el-color-primary);
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color);
  outline: none;
}

.advanced-element-palette__item i {
  font-size: 18px;
}

.advanced-element-palette__item span {
  min-width: 0;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
