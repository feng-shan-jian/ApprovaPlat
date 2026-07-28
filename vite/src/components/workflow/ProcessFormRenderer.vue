<template>
  <el-form
    ref="formRef"
    class="workflow-form-renderer"
    :model="formModel"
    :rules="rules"
    :label-position="template.config.labelPosition"
    :label-width="`${template.config.labelWidth}px`"
    :size="template.config.size"
    :disabled="template.config.disabled"
  >
    <el-row :gutter="template.config.gutter">
      <WorkflowFormField
        v-for="(field, index) in template.fields"
        :key="field.variable || `${field.tag}-${index}`"
        :field="field"
        :value="formModel[field.variable]"
        :form-model="formModel"
        :readonly="readonly"
        :gutter="template.config.gutter"
        @update:value="value => updateValue(field.variable, value)"
        @busy-change="updateBusyState"
        @error="error => $emit('error', error)"
      />
    </el-row>
  </el-form>
</template>

<script setup name="ProcessFormRenderer">
import WorkflowFormField from './form/WorkflowFormField.vue'
import { flattenFormFields, normalizeFormTemplate } from './form/formTemplate'

const props = defineProps({
  /** 部署时固化的表单 JSON 快照。 */
  content: { type: [String, Object], required: true },
  /** 表单当前值；上传字段可包含后端安全附件元数据。 */
  modelValue: { type: Object, default: () => ({}) },
  /** 只读模式用于历史详情，不允许修改或删除附件。 */
  readonly: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change', 'error'])
const formRef = ref(null)
const formModel = reactive({})
const busyFields = reactive(new Set())
const template = computed(() => normalizeFormTemplate(props.content))
const flatFields = computed(() => flattenFormFields(template.value.fields))
const rules = computed(() => Object.fromEntries(flatFields.value
  .filter(field => field.required)
  .map(field => [field.variable, [{ required: true, message: `${field.label || field.variable}不能为空`, trigger: 'change' }]])))
let syncing = false

/**
 * 使用父级值和模板默认值重建当前表单模型。
 * @returns {void} 无返回值。
 */
function rebuildModel() {
  syncing = true
  const next = {}
  flatFields.value.forEach(field => {
    next[field.variable] = Object.prototype.hasOwnProperty.call(props.modelValue, field.variable)
      ? cloneValue(props.modelValue[field.variable])
      : cloneValue(field.defaultValue)
  })
  Object.keys(formModel).forEach(key => delete formModel[key])
  Object.assign(formModel, next)
  nextTick(() => { syncing = false })
}

/**
 * 复制 JSON 表单值，避免父子组件共享可变数组或对象。
 * @param {unknown} value 原始表单值。
 * @returns {unknown} JSON 副本或 undefined。
 */
function cloneValue(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value))
}

/**
 * 更新单个业务字段并同步父级。
 * @param {string} variable 表单变量名。
 * @param {unknown} value 新字段值。
 * @returns {void} 无返回值。
 */
function updateValue(variable, value) {
  if (!variable || props.readonly) return
  formModel[variable] = value
  emitModelChange()
}

/**
 * 将内部模型同步给父级；上传字段保留安全元数据供页面回显。
 * @returns {void} 无返回值。
 */
function emitModelChange() {
  if (syncing) return
  const value = cloneValue(formModel)
  emit('update:modelValue', value)
  emit('change', value)
}

/**
 * 记录各附件字段是否存在未完成请求。
 * @param {string} fieldName 上传字段变量名。
 * @param {boolean} busy 是否正在上传。
 * @returns {void} 无返回值。
 */
function updateBusyState(fieldName, busy) {
  if (busy) busyFields.add(fieldName)
  else busyFields.delete(fieldName)
}

/**
 * 执行 Element Plus 表单校验并阻止附件上传中提交。
 * @returns {Promise<boolean>} 所有即时门禁通过时为 true。
 */
async function validate() {
  if (busyFields.size) throw new Error('附件仍在上传中')
  if (!formRef.value) return true
  return formRef.value.validate()
}

/**
 * 获取可提交给后端变量校验器的深拷贝，上传字段只保留 UUID 数组。
 * @returns {object} 与部署快照字段白名单一致的流程变量。
 */
function getValues() {
  const values = cloneValue(formModel)
  flatFields.value.filter(field => field.tag === 'el-upload').forEach(field => {
    values[field.variable] = (Array.isArray(formModel[field.variable]) ? formModel[field.variable] : [])
      .map(item => typeof item === 'string' ? item : item?.attachmentId)
      .filter(Boolean)
  })
  return values
}

/**
 * 清除校验并恢复模板默认值。
 * @returns {void} 无返回值。
 */
function reset() {
  emit('update:modelValue', {})
  rebuildModel()
  formRef.value?.clearValidate()
}

watch(() => props.content, rebuildModel)
watch(() => props.modelValue, rebuildModel, { deep: true })
watch(formModel, emitModelChange, { deep: true })
onMounted(rebuildModel)

defineExpose({ validate, getValues, reset })
</script>

<style scoped>
.workflow-form-renderer {
  width: 100%;
  min-width: 0;
}

.workflow-form-renderer :deep(.el-form-item__content) {
  min-width: 0;
}

@media (max-width: 767px) {
  .workflow-form-renderer :deep(.el-form-item) {
    display: block;
  }

  .workflow-form-renderer :deep(.el-form-item__label) {
    width: 100% !important;
    height: auto;
    justify-content: flex-start;
    padding: 0 0 6px;
    text-align: left;
  }

  .workflow-form-renderer :deep(.el-form-item__content) {
    width: 100%;
    margin-left: 0 !important;
  }
}
</style>
