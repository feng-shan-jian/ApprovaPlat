<template>
  <el-row v-if="!field.hidden && field.readable && field.layout === 'rowFormItem'" :gutter="gutter" class="workflow-form-row">
    <WorkflowFormField
      v-for="(child, index) in field.children"
      :key="child.variable || `${child.tag}-${index}`"
      :field="child"
      :value="formModel[child.variable]"
      :form-model="formModel"
      :readonly="readonly"
      :gutter="gutter"
      @update:value="value => updateChild(child.variable, value)"
      @busy-change="forwardBusyState"
      @error="error => $emit('error', error)"
    />
  </el-row>

  <el-col v-else-if="!field.hidden && field.readable" v-bind="columnProps">
    <el-form-item :label="field.label" :prop="field.variable" :label-width="field.labelWidth ? `${field.labelWidth}px` : undefined">
      <template v-if="field.tag === 'el-select'">
        <el-select v-bind="controlProps" :model-value="value" @update:model-value="updateValue">
          <el-option v-for="option in field.options" :key="String(option.value)" :label="option.label" :value="option.value" />
        </el-select>
      </template>
      <template v-else-if="field.tag === 'el-cascader'">
        <el-cascader v-bind="controlProps" :options="field.options" :model-value="value" @update:model-value="updateValue" />
      </template>
      <template v-else-if="field.tag === 'el-radio-group'">
        <el-radio-group v-bind="controlProps" :model-value="value" @update:model-value="updateValue">
          <el-radio v-for="option in field.options" :key="String(option.value)" :value="option.value">{{ option.label }}</el-radio>
        </el-radio-group>
      </template>
      <template v-else-if="field.tag === 'el-checkbox-group'">
        <el-checkbox-group v-bind="controlProps" :model-value="value" @update:model-value="updateValue">
          <el-checkbox v-for="option in field.options" :key="String(option.value)" :value="option.value">{{ option.label }}</el-checkbox>
        </el-checkbox-group>
      </template>
      <template v-else-if="field.tag === 'tinymce'">
        <Editor
          :model-value="value || ''"
          :min-height="field.props.height || 180"
          :read-only="effectiveReadonly"
          type="base64"
          @update:model-value="updateValue"
        />
      </template>
      <template v-else-if="field.tag === 'el-upload'">
        <WorkflowAttachmentUpload
          :field-name="field.variable"
          :model-value="Array.isArray(value) ? value : []"
          :disabled="effectiveReadonly || Boolean(field.props.disabled)"
          :limit="field.props.limit || 10"
          :accept="field.props.accept || ''"
          :max-size-mb="field.props.fileSize || 50"
          @update:model-value="updateValue"
          @busy-change="busy => $emit('busy-change', field.variable, busy)"
          @error="error => $emit('error', error)"
        />
      </template>
      <template v-else-if="field.tag === 'el-table'">
        <el-table :data="Array.isArray(value) ? value : []" border :stripe="field.props.stripe !== false">
          <el-table-column
            v-for="column in tableColumns"
            :key="column.variable || column.label"
            :prop="column.variable"
            :label="column.label"
            show-overflow-tooltip
          />
        </el-table>
      </template>
      <template v-else-if="field.tag === 'el-button'">
        <el-button disabled>{{ field.label }}</el-button>
      </template>
      <component
        :is="field.tag"
        v-else
        v-bind="controlProps"
        :model-value="value"
        @update:model-value="updateValue"
      />
    </el-form-item>
  </el-col>
</template>

<script setup name="WorkflowFormField">
import Editor from '@/components/Editor'
import WorkflowAttachmentUpload from '../WorkflowAttachmentUpload.vue'

const props = defineProps({
  field: { type: Object, required: true },
  value: { default: undefined },
  formModel: { type: Object, required: true },
  readonly: { type: Boolean, default: false },
  gutter: { type: Number, default: 16 }
})

const emit = defineEmits(['update:value', 'busy-change', 'error'])
const tableColumns = computed(() => props.field.children.filter(child => child.tag === 'el-table-column'))
// effectiveReadonly 合并页面历史模式和部署快照节点权限；disabled 只负责控件表现。
const effectiveReadonly = computed(() => props.readonly || props.field.writable === false)
const columnProps = computed(() => ({
  span: props.field.span,
  xs: 24,
  sm: Math.max(12, props.field.span),
  md: props.field.span,
  lg: props.field.span,
  xl: props.field.span
}))
const controlProps = computed(() => ({
  ...props.field.props,
  disabled: effectiveReadonly.value || Boolean(props.field.props.disabled),
  readonly: effectiveReadonly.value || Boolean(props.field.props.readonly),
  style: { width: '100%' }
}))

/**
 * 将当前控件值提交给父表单模型。
 * @param {unknown} value 新控件值。
 * @returns {void} 无返回值。
 */
function updateValue(value) {
  if (!effectiveReadonly.value) emit('update:value', value)
}

/**
 * 更新行容器中子字段的共享表单模型。
 * @param {string} variable 子字段变量名。
 * @param {unknown} value 子字段新值。
 * @returns {void} 无返回值。
 */
function updateChild(variable, value) {
  if (variable && !effectiveReadonly.value) props.formModel[variable] = value
}

/**
 * 透传嵌套字段的附件忙碌状态，保留真实附件字段名和布尔状态。
 * @param {string} fieldName 实际触发上传或删除请求的附件字段变量名。
 * @param {boolean} busy 该附件字段是否仍有未完成的写请求。
 * @returns {void} 无返回值。
 */
function forwardBusyState(fieldName, busy) {
  emit('busy-change', fieldName, Boolean(busy))
}
</script>

<style scoped>
.workflow-form-row {
  width: 100%;
}
</style>
