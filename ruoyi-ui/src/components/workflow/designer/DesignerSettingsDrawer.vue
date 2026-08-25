<template>
  <el-drawer
    :model-value="modelValue"
    title="设计器设置"
    size="360px"
    append-to-body
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-form label-position="left" label-width="170px" class="designer-settings">
      <el-form-item label="主题">
        <el-segmented v-model="draft.theme" :options="themeOptions" />
      </el-form-item>
      <el-form-item label="网格与吸附">
        <el-switch v-model="draft.gridEnabled" />
      </el-form-item>
      <el-form-item label="小地图">
        <el-switch v-model="draft.minimapEnabled" />
      </el-form-item>
      <el-form-item label="Token 流程模拟">
        <el-switch v-model="draft.tokenSimulationEnabled" />
      </el-form-item>
      <el-form-item label="折叠属性面板">
        <el-switch v-model="draft.propertiesCollapsed" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="restoreDefaults">恢复默认</el-button>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存设置</el-button>
    </template>
  </el-drawer>
</template>

<script setup name="DesignerSettingsDrawer">
const props = defineProps({
  /** 抽屉显示状态。 */
  modelValue: { type: Boolean, default: false },
  /** 当前用户本地存储回读的完整设计器偏好。 */
  preference: { type: Object, required: true },
  /** 偏好保存请求是否进行中。 */
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'save', 'reset'])
const draft = reactive(createDraft(props.preference))
const themeOptions = [
  { label: '跟随系统', value: 'SYSTEM' },
  { label: '浅色', value: 'LIGHT' },
  { label: '深色', value: 'DARK' }
]

/**
 * 从只读当前用户偏好创建可编辑草稿，避免取消抽屉时污染已应用状态。
 * @param {object} preference 当前用户浏览器存储回读的完整偏好。
 * @returns {object} 字段完整且布尔值规范化的草稿。
 */
function createDraft(preference) {
  return {
    theme: ['LIGHT', 'DARK', 'SYSTEM'].includes(preference?.theme) ? preference.theme : 'SYSTEM',
    gridEnabled: preference?.gridEnabled !== false,
    minimapEnabled: preference?.minimapEnabled !== false,
    tokenSimulationEnabled: preference?.tokenSimulationEnabled === true,
    propertiesCollapsed: preference?.propertiesCollapsed === true
  }
}

/**
 * 提交字段完整的偏好草稿，由页面写入当前用户浏览器存储。
 * @returns {void} 保存结果通过 preference Prop 回写。
 */
function submit() {
  emit('save', { ...draft })
}

/**
 * 请求页面仅删除当前用户偏好键并恢复默认值。
 * @returns {void} 默认值通过 preference Prop 回写后刷新草稿。
 */
function restoreDefaults() {
  emit('reset')
}

watch(() => props.preference, value => Object.assign(draft, createDraft(value)), { deep: true })
watch(() => props.modelValue, visible => {
  if (visible) Object.assign(draft, createDraft(props.preference))
})
</script>

<style scoped lang="scss">
.designer-settings :deep(.el-form-item) {
  min-height: 42px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.designer-settings :deep(.el-form-item__content) {
  justify-content: flex-end;
}

.designer-settings :deep(.el-segmented) {
  width: 100%;
}
</style>
