<template>
  <div class="collaboration-editor">
    <el-form-item label="接收端点" required>
      <el-select v-model="draft.endpointKey" filterable @change="emitChange">
        <el-option v-for="endpoint in endpoints" :key="endpoint.endpointId" :label="endpoint.endpointName" :value="endpoint.endpointKey" />
      </el-select>
    </el-form-item>
    <el-form-item label="接收路径" required>
      <el-input v-model="draft.path" maxlength="512" @change="emitChange" />
    </el-form-item>
    <el-form-item label="消息名称" required>
      <el-input v-model="draft.messageName" maxlength="255" @change="emitChange" />
    </el-form-item>
    <el-form-item label="目标流程 key" required>
      <el-input v-model="draft.targetProcessDefinitionKey" maxlength="255" @change="emitChange" />
    </el-form-item>
    <el-form-item label="关联变量">
      <el-input v-model="draft.correlationVariable" maxlength="128" placeholder="留空使用流程业务键" @change="emitChange" />
    </el-form-item>
    <el-form-item label="投递变量">
      <el-select v-model="draft.variableNames" multiple filterable allow-create default-first-option @change="emitChange" />
    </el-form-item>
    <el-form-item label="最大尝试次数" required>
      <el-input-number v-model="draft.maxAttempts" :min="1" :max="20" controls-position="right" @change="emitChange" />
    </el-form-item>
  </div>
</template>

<script setup name="CollaborationMessageEditor">
const props = defineProps({
  modelValue: { type: String, default: '{}' },
  endpoints: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'change'])
const draft = reactive(emptyConfig())

/** @returns {object} 与后端作者配置 Schema 一致的默认值。 */
function emptyConfig() {
  return {
    endpointKey: props.endpoints[0]?.endpointKey || '',
    path: '/workflow/runtime-event/collaboration/message',
    messageName: '',
    targetProcessDefinitionKey: '',
    correlationVariable: '',
    variableNames: [],
    maxAttempts: 5
  }
}

/** @param {string} value JSON 配置文本。 @returns {object} 字段完整的安全编辑状态。 */
function parseConfig(value) {
  try {
    const parsed = JSON.parse(value || '{}')
    const base = emptyConfig()
    return {
      endpointKey: typeof parsed.endpointKey === 'string' ? parsed.endpointKey : base.endpointKey,
      path: typeof parsed.path === 'string' ? parsed.path : base.path,
      messageName: typeof parsed.messageName === 'string' ? parsed.messageName : '',
      targetProcessDefinitionKey: typeof parsed.targetProcessDefinitionKey === 'string' ? parsed.targetProcessDefinitionKey : '',
      correlationVariable: typeof parsed.correlationVariable === 'string' ? parsed.correlationVariable : '',
      variableNames: Array.isArray(parsed.variableNames) ? parsed.variableNames.filter(item => typeof item === 'string') : [],
      maxAttempts: Number.isInteger(parsed.maxAttempts) ? parsed.maxAttempts : 5
    }
  } catch {
    return emptyConfig()
  }
}

/** @returns {void} 去除可选空字段后输出稳定作者 JSON。 */
function emitChange() {
  const normalized = {
    endpointKey: draft.endpointKey.trim(),
    path: draft.path.trim(),
    messageName: draft.messageName.trim(),
    targetProcessDefinitionKey: draft.targetProcessDefinitionKey.trim(),
    variableNames: [...new Set(draft.variableNames.map(item => String(item).trim()).filter(Boolean))].sort(),
    maxAttempts: draft.maxAttempts
  }
  if (draft.correlationVariable.trim()) normalized.correlationVariable = draft.correlationVariable.trim()
  const value = JSON.stringify(normalized)
  emit('update:modelValue', value)
  emit('change', value)
}

watch(() => props.modelValue, value => Object.assign(draft, parseConfig(value)), { immediate: true })
</script>

<style scoped>
.collaboration-editor { width: 100%; }
.collaboration-editor :deep(.el-input-number), .collaboration-editor :deep(.el-select) { width: 100%; }
</style>
