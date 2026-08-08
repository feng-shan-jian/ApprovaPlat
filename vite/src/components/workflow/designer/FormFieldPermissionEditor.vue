<template>
  <section class="form-field-permission-editor" aria-label="节点字段权限">
    <div class="form-field-permission-editor__toolbar">
      <el-select v-model="batchMode" aria-label="批量默认字段权限">
        <el-option v-for="option in permissionOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-tooltip content="将默认策略应用到全部字段" placement="top">
        <el-button icon="Finished" :disabled="disabled || !normalizedFields.length" @click="applyBatchMode">应用</el-button>
      </el-tooltip>
    </div>

    <div class="form-field-permission-editor__summary">
      <span>{{ normalizedFields.length }} 个字段</span>
      <span>默认 {{ permissionLabel(batchMode) }}</span>
    </div>

    <div v-if="normalizedFields.length" class="form-field-permission-editor__fields">
      <div v-for="field in normalizedFields" :key="field.variable" class="form-field-permission-editor__field">
        <div class="form-field-permission-editor__identity" :title="field.label || field.variable">
          <strong>{{ field.label || field.variable }}</strong>
          <code>{{ field.variable }}</code>
        </div>
        <el-select
          :model-value="field.mode"
          :disabled="disabled"
          :aria-label="`${field.label || field.variable}字段权限`"
          @change="mode => updateFieldMode(field.variable, mode)"
        >
          <el-option v-for="option in permissionOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </div>
    </div>
    <el-empty v-else description="绑定正式表单后配置字段权限" :image-size="54" />
  </section>
</template>

<script setup name="FormFieldPermissionEditor">
const permissionOptions = Object.freeze([
  { label: '隐藏', value: 'HIDDEN' },
  { label: '只读', value: 'READONLY' },
  { label: '可编辑', value: 'EDITABLE' },
  { label: '必填', value: 'REQUIRED' }
])
const permissionModes = new Set(permissionOptions.map(option => option.value))

const props = defineProps({
  /** 当前绑定正式表单中的字段及逐字段权限。 */
  fields: { type: Array, default: () => [] },
  /** 表单后续新增字段和批量应用使用的默认权限。 */
  defaultMode: { type: String, default: 'EDITABLE' },
  /** 保存或只读状态下禁止修改。 */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['change'])
const batchMode = ref(normalizeMode(props.defaultMode))
const normalizedFields = computed(() => (Array.isArray(props.fields) ? props.fields : [])
  .filter(field => field && String(field.variable || '').trim())
  .map(field => ({
    variable: String(field.variable).trim(),
    label: String(field.label || '').trim(),
    mode: normalizeMode(field.mode)
  })))

/**
 * 规范化外部权限值，未知值回退到可编辑，避免产生模型外的第五种权限状态。
 * @param {unknown} mode 外部传入或选择器返回的权限值。
 * @returns {'HIDDEN'|'READONLY'|'EDITABLE'|'REQUIRED'} 服务端协议允许的权限模式。
 */
function normalizeMode(mode) {
  const value = String(mode || '').trim().toUpperCase()
  return permissionModes.has(value) ? value : 'EDITABLE'
}

/**
 * 返回权限模式的中文名称，用于批量策略摘要。
 * @param {string} mode 权限模式。
 * @returns {string} 对应的中文名称。
 */
function permissionLabel(mode) {
  return permissionOptions.find(option => option.value === mode)?.label || '可编辑'
}

/**
 * 更新单个字段权限并提交完整有序策略，父组件据此原子写入 BPMN。
 * @param {string} variable 当前字段变量名。
 * @param {string} mode 新权限模式。
 * @returns {void} 无返回值。
 */
function updateFieldMode(variable, mode) {
  if (props.disabled) return
  emitPolicy(normalizedFields.value.map(field => ({
    ...field,
    mode: field.variable === variable ? normalizeMode(mode) : field.mode
  })))
}

/**
 * 将批量默认策略应用到当前全部字段，同时保存为模板后续新增字段的默认策略。
 * @returns {void} 无返回值。
 */
function applyBatchMode() {
  if (props.disabled) return
  const mode = normalizeMode(batchMode.value)
  emitPolicy(normalizedFields.value.map(field => ({ ...field, mode })))
}

/**
 * 对外提交字段完整策略和批量默认策略，避免父组件按行产生部分更新。
 * @param {Array<{variable:string,label:string,mode:string}>} fields 已规范化的完整字段权限。
 * @returns {void} 无返回值。
 */
function emitPolicy(fields) {
  emit('change', { defaultMode: normalizeMode(batchMode.value), fields })
}

watch(() => props.defaultMode, value => {
  batchMode.value = normalizeMode(value)
})
</script>

<style scoped>
.form-field-permission-editor {
  display: grid;
  gap: 8px;
  width: 100%;
}

.form-field-permission-editor__toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.form-field-permission-editor__summary {
  display: flex;
  justify-content: space-between;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.form-field-permission-editor__fields {
  display: grid;
  max-height: 320px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.form-field-permission-editor__field {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px;
  align-items: center;
  gap: 8px;
  min-height: 58px;
  padding: 8px 9px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.form-field-permission-editor__field:last-child {
  border-bottom: 0;
}

.form-field-permission-editor__identity {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.form-field-permission-editor__identity strong,
.form-field-permission-editor__identity code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.form-field-permission-editor__identity strong {
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 600;
}

.form-field-permission-editor__identity code {
  color: var(--el-text-color-secondary);
  font-family: Consolas, monospace;
  font-size: 10px;
}
</style>
