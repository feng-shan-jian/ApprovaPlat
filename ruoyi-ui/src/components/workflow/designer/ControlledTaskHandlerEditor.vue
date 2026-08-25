<template>
  <el-form-item label="受控处理器" required>
    <el-select
      :model-value="state.extensionKey"
      filterable
      :loading="loading"
      @change="selectExtension"
    >
      <el-option
        v-for="option in options"
        :key="option.versionId"
        :label="`${option.extensionName} · ${option.extensionType} · v${option.versionNo}`"
        :value="option.extensionKey"
      />
    </el-select>
  </el-form-item>
  <CollaborationMessageEditor
    v-if="selectedImplementation === 'COLLABORATION_OUTBOX_V1'"
    :model-value="state.extensionConfig"
    :endpoints="connectorEndpoints"
    @update:model-value="updateConfig"
    @change="emit('change')"
  />
  <CelExpressionEditor
    v-else-if="selectedType === 'CEL'"
    :model-value="state.extensionConfig"
    @update:model-value="updateConfig"
    @change="emit('change')"
  />
  <HttpConnectorEditor
    v-else-if="selectedType === 'HTTP'"
    :model-value="state.extensionConfig"
    :endpoints="connectorEndpoints"
    @update:model-value="updateConfig"
    @change="emit('change')"
  />
  <SqlConnectorEditor
    v-else-if="selectedType === 'SQL'"
    :model-value="state.extensionConfig"
    :data-sources="sqlDataSources"
    @update:model-value="updateConfig"
    @change="emit('change')"
  />
  <BpmnEventRaiseEditor
    v-else-if="selectedImplementation === 'RAISE_BPMN_EVENT'"
    :model-value="state.extensionConfig"
    :error-options="errorEventOptions"
    :escalation-options="escalationEventOptions"
    @update:model-value="updateConfig"
    @change="emit('change')"
  />
  <el-form-item v-else label="处理器 JSON 配置" required>
    <el-input
      :model-value="state.extensionConfig"
      type="textarea"
      :rows="5"
      maxlength="16384"
      @update:model-value="updateConfig"
      @change="emit('change')"
    />
  </el-form-item>
</template>

<script setup name="ControlledTaskHandlerEditor">
import BpmnEventRaiseEditor from './BpmnEventRaiseEditor.vue'
import CelExpressionEditor from './CelExpressionEditor.vue'
import CollaborationMessageEditor from './CollaborationMessageEditor.vue'
import HttpConnectorEditor from './HttpConnectorEditor.vue'
import SqlConnectorEditor from './SqlConnectorEditor.vue'

const props = defineProps({
  /** 父面板持有的受控扩展键与 JSON 配置状态。 */
  state: { type: Object, required: true },
  /** 服务端正式扩展目录的已启用精确版本。 */
  options: { type: Array, default: () => [] },
  /** 服务端正式 HTTP 端点目录，不包含密钥正文。 */
  connectorEndpoints: { type: Array, default: () => [] },
  /** 服务端正式 SQL 数据源目录，不包含连接凭据。 */
  sqlDataSources: { type: Array, default: () => [] },
  /** 服务端正式错误事件编码目录。 */
  errorEventOptions: { type: Array, default: () => [] },
  /** 服务端正式升级事件编码目录。 */
  escalationEventOptions: { type: Array, default: () => [] },
  /** 正式目录是否正在加载。 */
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['selection-change', 'config-update', 'change'])

// 编辑器类型只读取服务端目录元数据，实际处理器合法性和 MessageFlow outbox 约束仍由后端权威校验。
const selectedOption = computed(() => (
  props.options.find(option => option.extensionKey === props.state.extensionKey)
))
const selectedType = computed(() => selectedOption.value?.extensionType || '')
const selectedImplementation = computed(() => selectedOption.value?.implementationKey || '')

/**
 * 同步正式目录选择并通知父组件建立与服务端 Schema 对应的初始配置。
 * @param {string} extensionKey 服务端正式扩展目录的稳定键。
 * @returns {void} 状态更新后由父组件通过 bpmn-js 命令栈持久化。
 */
function selectExtension(extensionKey) {
  emit('selection-change', extensionKey)
}

/**
 * 同步结构化子编辑器返回的 JSON 文本。
 * @param {string} config 服务端受控处理器配置 JSON。
 * @returns {void} 仅更新父级响应式草稿，change 事件负责写入命令栈。
 */
function updateConfig(config) {
  emit('config-update', config)
}
</script>
