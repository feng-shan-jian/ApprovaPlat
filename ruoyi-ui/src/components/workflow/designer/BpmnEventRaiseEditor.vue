<template>
  <div class="bpmn-event-raise-editor">
    <el-form-item label="事件类型" required>
      <el-segmented v-model="draft.eventType" :options="eventTypes" @change="handleTypeChange" />
    </el-form-item>
    <el-form-item label="业务编码" required>
      <el-select v-model="draft.eventCode" filterable @change="emitChange">
        <el-option
          v-for="option in currentOptions"
          :key="option.eventCodeId"
          :label="`${option.eventName} · ${option.eventCode}`"
          :value="option.eventCode"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="业务来源" required>
      <el-select v-model="draft.sourceType" @change="emitChange">
        <el-option v-for="option in sourceTypes" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
    </el-form-item>
    <el-form-item label="触发条件">
      <el-select v-model="draft.operator" @change="emitChange">
        <el-option v-for="option in operators" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
    </el-form-item>
    <el-form-item v-if="draft.operator !== 'ALWAYS'" label="条件变量" required>
      <el-input v-model="draft.conditionVariable" maxlength="128" @change="emitChange" />
    </el-form-item>
    <el-form-item v-if="['EQUALS', 'NOT_EQUALS'].includes(draft.operator)" label="比较值" required>
      <el-input v-model="draft.expectedValue" maxlength="256" @change="emitChange" />
    </el-form-item>
    <el-form-item label="消息变量">
      <el-input v-model="draft.messageVariable" maxlength="128" placeholder="可选标量变量，不保存异常堆栈" @change="emitChange" />
    </el-form-item>
    <el-alert type="warning" :closable="false" show-icon>
      <template #title>该节点必须附着唯一同编码边界；普通 Java 异常不会转换为 BPMN 业务事件。</template>
    </el-alert>
  </div>
</template>

<script setup name="BpmnEventRaiseEditor">
const props = defineProps({
  modelValue: { type: String, default: '{}' },
  errorOptions: { type: Array, default: () => [] },
  escalationOptions: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'change'])
const draft = reactive(createEmptyConfig())
const eventTypes = Object.freeze([
  { label: '业务错误', value: 'ERROR' },
  { label: '业务升级', value: 'ESCALATION' }
])
const sourceTypes = Object.freeze([
  { label: '服务任务', value: 'SERVICE_TASK' },
  { label: 'HTTP 连接器结果', value: 'HTTP' },
  { label: 'SQL 连接器结果', value: 'SQL' },
  { label: 'DMN 决策结果', value: 'DMN' },
  { label: '人工业务判断', value: 'MANUAL' }
])
const operators = Object.freeze([
  { label: '始终触发', value: 'ALWAYS' },
  { label: '等于', value: 'EQUALS' },
  { label: '不等于', value: 'NOT_EQUALS' },
  { label: '为真', value: 'TRUE' },
  { label: '为假', value: 'FALSE' },
  { label: '有值', value: 'PRESENT' },
  { label: '为空', value: 'EMPTY' }
])
const currentOptions = computed(() => draft.eventType === 'ERROR' ? props.errorOptions : props.escalationOptions)

/**
 * 创建与后端作者 Schema 一致的初始配置。
 * @returns {object} 字段完整的编辑状态。
 */
function createEmptyConfig() {
  return {
    eventType: 'ERROR', eventCode: '', sourceType: 'SERVICE_TASK', operator: 'ALWAYS',
    conditionVariable: '', expectedValue: '', messageVariable: ''
  }
}

/**
 * 从作者 BPMN 配置回读受控字段，忽略部署快照专用字段。
 * @param {string} value JSON 配置文本。
 * @returns {void} 解析失败时恢复稳定默认值。
 */
function loadConfig(value) {
  Object.assign(draft, createEmptyConfig())
  try {
    const parsed = JSON.parse(value || '{}')
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') return
    for (const key of Object.keys(draft)) {
      if (typeof parsed[key] === 'string') draft[key] = parsed[key]
    }
  } catch {
    // 非法原文不做本地猜测，下一次有效编辑生成规范配置。
  }
}

/** @returns {void} 切换类型后清理不属于新目录的旧编码并同步。 */
function handleTypeChange() {
  if (!currentOptions.value.some(option => option.eventCode === draft.eventCode)) draft.eventCode = ''
  emitChange()
}

/**
 * 输出字段顺序稳定、无空可选值的作者配置。
 * @returns {void} 更新 v-model 并要求父级写入 bpmn-js 命令栈。
 */
function emitChange() {
  const normalized = {
    eventType: draft.eventType,
    eventCode: draft.eventCode.trim(),
    sourceType: draft.sourceType,
    operator: draft.operator
  }
  if (draft.operator !== 'ALWAYS' && draft.conditionVariable.trim()) normalized.conditionVariable = draft.conditionVariable.trim()
  if (['EQUALS', 'NOT_EQUALS'].includes(draft.operator)) normalized.expectedValue = draft.expectedValue
  if (draft.messageVariable.trim()) normalized.messageVariable = draft.messageVariable.trim()
  const json = JSON.stringify(normalized)
  emit('update:modelValue', json)
  emit('change', json)
}

watch(() => props.modelValue, value => loadConfig(value), { immediate: true })
</script>

<style scoped>
.bpmn-event-raise-editor {
  display: grid;
  gap: 2px;
}

.bpmn-event-raise-editor :deep(.el-select),
.bpmn-event-raise-editor :deep(.el-segmented) {
  width: 100%;
}
</style>
