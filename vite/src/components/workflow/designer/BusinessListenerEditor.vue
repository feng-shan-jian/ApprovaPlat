<template>
  <div class="business-listener-editor">
    <div v-if="!draft.length" class="business-listener-editor__empty">尚未配置业务监听器</div>
    <div v-for="(listener, index) in draft" :key="listener.clientId" class="business-listener-editor__row">
      <div class="business-listener-editor__row-head">
        <el-select v-model="listener.event" size="small" @change="emitChange">
          <el-option v-for="event in eventOptions" :key="event" :label="event" :value="event" />
        </el-select>
        <el-button
          text
          type="danger"
          :icon="Delete"
          :aria-label="`删除第 ${index + 1} 条业务监听器`"
          @click="removeListener(index)"
        />
      </div>
      <el-select
        v-model="listener.extensionKey"
        class="business-listener-editor__extension"
        size="small"
        filterable
        clearable
        :loading="loading"
        placeholder="选择受控 Java 处理器"
        @change="emitChange"
      >
        <el-option
          v-for="option in options"
          :key="option.versionId || option.extensionKey"
          :label="`${option.extensionName || option.name} · v${option.versionNo || '-'}`"
          :value="option.extensionKey"
        />
      </el-select>
      <el-input
        v-model="listener.config"
        type="textarea"
        :rows="2"
        maxlength="16384"
        resize="none"
        placeholder="JSON 配置对象"
        @change="emitChange"
      />
    </div>
    <el-button class="business-listener-editor__add" text :icon="Plus" @click="addListener">新增业务监听器</el-button>
  </div>
</template>

<script setup name="BusinessListenerEditor">
import { Delete, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  kind: { type: String, default: 'EXECUTION' },
  options: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change'])
const draft = ref([])
const eventOptions = computed(() => props.kind === 'TASK'
  ? ['create', 'assignment', 'complete', 'delete']
  : ['start', 'end', 'take'])

/**
 * 把父组件的受控监听器快照复制为可编辑草稿，避免直接修改 BPMN 回读对象。
 * @param {Array<object>} listeners 父组件提供的监听器配置数组。
 * @returns {void} 更新本地编辑草稿。
 */
function syncDraft(listeners) {
  draft.value = (Array.isArray(listeners) ? listeners : []).map((listener, index) => ({
    clientId: listener.clientId || `${Date.now()}_${index}_${Math.random().toString(36).slice(2)}`,
    event: eventOptions.value.includes(listener.event) ? listener.event : eventOptions.value[0],
    extensionKey: listener.extensionKey || '',
    config: listener.config || '{}'
  }))
}

/**
 * 新增一条默认配置的业务监听器。
 * @returns {void} 将新增行写入父组件的 bpmn-js 命令栈。
 */
function addListener() {
  draft.value.push({
    clientId: `${Date.now()}_${Math.random().toString(36).slice(2)}`,
    event: eventOptions.value[0],
    extensionKey: '',
    config: '{}'
  })
  emitChange()
}

/**
 * 删除指定业务监听器并通知父组件。
 * @param {number} index 待删除的本地草稿索引。
 * @returns {void} 删除后发出不含客户端临时标识的配置数组。
 */
function removeListener(index) {
  draft.value.splice(index, 1)
  emitChange()
}

/**
 * 向父组件提交监听器的稳定字段，过滤仅用于 Vue 列表渲染的临时标识。
 * @returns {void} 同时发出 v-model 更新和 change 事件。
 */
function emitChange() {
  const value = draft.value.map(({ clientId, ...listener }) => ({ ...listener }))
  emit('update:modelValue', value)
  emit('change', value)
}

watch(() => props.modelValue, syncDraft, { deep: true, immediate: true })
watch(() => props.kind, () => syncDraft(props.modelValue))
</script>

<style scoped>
.business-listener-editor {
  display: grid;
  gap: 8px;
}

.business-listener-editor__empty {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.business-listener-editor__row {
  display: grid;
  gap: 6px;
  padding: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-extra-light);
}

.business-listener-editor__row-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  gap: 4px;
  align-items: center;
}

.business-listener-editor__extension,
.business-listener-editor__add {
  width: 100%;
}
</style>
