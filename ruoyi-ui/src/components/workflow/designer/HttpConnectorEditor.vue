<template>
  <div class="http-connector-editor">
    <el-form-item label="连接端点" required>
      <el-select v-model="draft.endpointKey" filterable @change="handleEndpointChange">
        <el-option
          v-for="endpoint in endpoints"
          :key="endpoint.endpointId"
          :label="`${endpoint.endpointName} · R${endpoint.revisionNo}`"
          :value="endpoint.endpointKey"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="请求方法" required>
      <el-select v-model="draft.method" @change="emitChange">
        <el-option v-for="method in allowedMethods" :key="method" :label="method" :value="method" />
      </el-select>
    </el-form-item>
    <el-form-item label="相对路径" required>
      <el-input
        v-model="draft.path"
        maxlength="512"
        :placeholder="selectedEndpoint?.pathPrefix || '/api'"
        @change="emitChange"
      />
    </el-form-item>
    <el-form-item v-if="supportsBody" label="正文变量">
      <el-input v-model="draft.bodyVariable" maxlength="128" @change="emitChange" />
    </el-form-item>
    <el-form-item label="状态变量">
      <el-input v-model="draft.statusVariable" maxlength="128" @change="emitChange" />
    </el-form-item>
    <el-alert
      v-if="selectedEndpoint"
      type="info"
      :closable="false"
      show-icon
      :title="`${selectedEndpoint.authType} · ${selectedEndpoint.networkScope} · ${selectedEndpoint.requestTimeoutMs} ms`"
    />
  </div>
</template>

<script setup name="HttpConnectorEditor">
const props = defineProps({
  modelValue: { type: String, default: '{}' },
  endpoints: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'change'])

const draft = reactive(createEmptyConfig())
const selectedEndpoint = computed(() => props.endpoints.find(endpoint => endpoint.endpointKey === draft.endpointKey))
const allowedMethods = computed(() => String(selectedEndpoint.value?.allowedMethods || '')
  .split(',').map(item => item.trim()).filter(Boolean))
const supportsBody = computed(() => !['GET', 'DELETE'].includes(draft.method))

/**
 * 创建 HTTP 节点配置初始值。
 * @returns {object} 与服务端作者 Schema 一致的可编辑字段。
 */
function createEmptyConfig() {
  return { endpointKey: '', method: '', path: '', bodyVariable: '', statusVariable: '' }
}

/**
 * 从父级 JSON 安全回读节点配置，未知字段不进入编辑状态。
 * @param {string} value 作者 BPMN 中的配置 JSON。
 * @returns {void} 解析失败时恢复空配置，最终由服务端拒绝非法原文。
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
    // 非法作者 JSON 不做本地猜测，用户下一次修改会生成规范结构。
  }
}

/**
 * 切换端点后把方法和路径收敛到当前白名单。
 * @returns {void} 通过父级命令栈写入完整节点配置。
 */
function handleEndpointChange() {
  const methods = allowedMethods.value
  if (!methods.includes(draft.method)) draft.method = methods[0] || ''
  const prefix = selectedEndpoint.value?.pathPrefix || '/'
  if (!draft.path || !(prefix === '/' || draft.path === prefix || draft.path.startsWith(`${prefix}/`))) {
    draft.path = prefix
  }
  if (!supportsBody.value) draft.bodyVariable = ''
  emitChange()
}

/**
 * 输出字段顺序稳定且不包含空可选值的 JSON。
 * @returns {void} 同时更新 v-model 并通知父级执行 bpmn-js 命令。
 */
function emitChange() {
  if (!supportsBody.value) draft.bodyVariable = ''
  const normalized = {
    endpointKey: draft.endpointKey.trim(),
    method: draft.method,
    path: draft.path.trim()
  }
  if (draft.bodyVariable.trim()) normalized.bodyVariable = draft.bodyVariable.trim()
  if (draft.statusVariable.trim()) normalized.statusVariable = draft.statusVariable.trim()
  const json = JSON.stringify(normalized)
  emit('update:modelValue', json)
  emit('change', json)
}

watch(() => props.modelValue, value => loadConfig(value), { immediate: true })
</script>

<style scoped>
.http-connector-editor {
  display: grid;
  gap: 2px;
}

.http-connector-editor :deep(.el-select) {
  width: 100%;
}

.http-connector-editor :deep(.el-alert) {
  margin-bottom: 10px;
  border-radius: 4px;
}
</style>
