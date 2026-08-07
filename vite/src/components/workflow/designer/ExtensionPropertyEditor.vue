<template>
  <div class="extension-property-editor">
    <div v-for="(item, index) in draft" :key="item.rowKey" class="extension-property-editor__row">
      <el-input
        v-model="item.name"
        maxlength="64"
        placeholder="属性名"
        @change="submit"
      />
      <el-input
        v-model="item.value"
        maxlength="1024"
        placeholder="属性值"
        @change="submit"
      />
      <el-tooltip content="删除扩展属性" placement="top">
        <el-button :icon="Delete" circle aria-label="删除扩展属性" @click="remove(index)" />
      </el-tooltip>
    </div>
    <el-button :icon="Plus" :disabled="draft.length >= maxItems" @click="add">新增属性</el-button>
  </div>
</template>

<script setup name="ExtensionPropertyEditor">
import { Delete, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  /** 当前元素的 Flowable 通用扩展属性。 */
  modelValue: { type: Array, default: () => [] },
  /** 单个 BPMN 元素允许的最大属性数。 */
  maxItems: { type: Number, default: 32 }
})

const emit = defineEmits(['update:modelValue', 'change'])
const nextRowKey = ref(1)
const draft = ref([])

/**
 * 将父组件属性复制为带稳定行键的编辑状态。
 * @param {Array<object>} value 属性名值列表。
 * @returns {void} 无返回值。
 */
function syncDraft(value) {
  draft.value = (Array.isArray(value) ? value : []).map(item => ({
    rowKey: nextRowKey.value++,
    name: String(item?.name || ''),
    value: String(item?.value || '')
  }))
}

watch(() => props.modelValue, syncDraft, { immediate: true, deep: true })

/**
 * 新增一条空扩展属性，数量上限由前后端共同约束。
 * @returns {void} 达到上限时不修改状态。
 */
function add() {
  if (draft.value.length >= props.maxItems) return
  draft.value.push({ rowKey: nextRowKey.value++, name: '', value: '' })
}

/**
 * 删除指定扩展属性并立即提交完整列表。
 * @param {number} index 待删除行的数组下标。
 * @returns {void} 下标有效时发出变更事件。
 */
function remove(index) {
  if (index < 0 || index >= draft.value.length) return
  draft.value.splice(index, 1)
  submit()
}

/**
 * 去除界面行键后向父组件提交完整属性快照。
 * @returns {void} 无返回值。
 */
function submit() {
  const value = draft.value.map(item => ({ name: item.name.trim(), value: item.value }))
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<style scoped>
.extension-property-editor {
  display: grid;
  gap: 8px;
  width: 100%;
}

.extension-property-editor__row {
  display: grid;
  grid-template-columns: minmax(88px, 0.8fr) minmax(120px, 1.2fr) 32px;
  gap: 6px;
  align-items: center;
}

.extension-property-editor__row :deep(.el-button) {
  width: 32px;
  height: 32px;
  border-radius: 4px;
}
</style>
