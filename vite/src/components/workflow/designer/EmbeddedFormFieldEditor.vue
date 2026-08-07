<template>
  <section class="embedded-form-editor" aria-label="内嵌表单字段">
    <div class="embedded-form-editor__toolbar">
      <span>字段 {{ draftFields.length }}/500</span>
      <el-button type="primary" plain size="small" :icon="Plus" :disabled="draftFields.length >= 500" @click="addField">
        添加字段
      </el-button>
    </div>

    <el-empty v-if="!draftFields.length" description="尚未配置内嵌字段" :image-size="48" />
    <div v-for="(field, fieldIndex) in draftFields" :key="fieldIndex" class="embedded-form-editor__field">
      <div class="embedded-form-editor__field-heading">
        <strong>字段 {{ fieldIndex + 1 }}</strong>
        <el-tooltip content="删除字段" placement="top">
          <el-button
            text
            type="danger"
            :icon="Delete"
            :aria-label="`删除字段 ${fieldIndex + 1}`"
            @click="removeField(fieldIndex)"
          />
        </el-tooltip>
      </div>
      <el-form-item label="字段标识" required>
        <el-input
          v-model="field.id"
          maxlength="128"
          placeholder="例如 requestReasonField"
          @change="commitDraft"
        />
      </el-form-item>
      <el-form-item label="变量名">
        <el-input
          v-model="field.variable"
          maxlength="128"
          placeholder="为空时使用字段标识"
          @change="commitDraft"
        />
      </el-form-item>
      <el-form-item label="字段名称" required>
        <el-input
          v-model="field.name"
          maxlength="255"
          placeholder="例如 申请原因"
          @change="commitDraft"
        />
      </el-form-item>
      <el-form-item label="字段类型" required>
        <el-select :model-value="field.type" :loading="customFieldLoading" @change="changeFieldType(fieldIndex, $event)">
          <el-option v-for="option in fieldTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="field.type === 'date'" label="日期格式" required>
        <el-input
          v-model="field.datePattern"
          maxlength="64"
          placeholder="yyyy-MM-dd"
          @change="commitDraft"
        />
      </el-form-item>
      <div class="embedded-form-editor__switches">
        <el-checkbox :model-value="field.readable" @change="updateField(fieldIndex, { readable: $event })">可读</el-checkbox>
        <el-checkbox :model-value="field.writable" @change="changeWritable(fieldIndex, $event)">可写</el-checkbox>
        <el-checkbox
          :model-value="field.required"
          :disabled="!field.writable"
          @change="updateField(fieldIndex, { required: $event })"
        >必填</el-checkbox>
      </div>

      <div v-if="field.type === 'enum'" class="embedded-form-editor__enum">
        <div class="embedded-form-editor__enum-heading">
          <span>静态选项 {{ field.values.length }}/500</span>
          <el-tooltip content="添加枚举选项" placement="top">
            <el-button
              text
              type="primary"
              :icon="Plus"
              :disabled="field.values.length >= 500"
              :aria-label="`为字段 ${fieldIndex + 1} 添加枚举选项`"
              @click="addEnumValue(fieldIndex)"
            />
          </el-tooltip>
        </div>
        <div v-for="(option, optionIndex) in field.values" :key="optionIndex" class="embedded-form-editor__enum-row">
          <el-input
            v-model="option.id"
            maxlength="255"
            :aria-label="`字段 ${fieldIndex + 1} 选项值 ${optionIndex + 1}`"
            placeholder="选项值"
            @change="commitDraft"
          />
          <el-input
            v-model="option.name"
            maxlength="255"
            :aria-label="`字段 ${fieldIndex + 1} 选项名称 ${optionIndex + 1}`"
            placeholder="显示名称"
            @change="commitDraft"
          />
          <el-tooltip content="删除枚举选项" placement="top">
            <el-button
              text
              type="danger"
              :icon="Delete"
              :aria-label="`删除字段 ${fieldIndex + 1} 的选项 ${optionIndex + 1}`"
              @click="removeEnumValue(fieldIndex, optionIndex)"
            />
          </el-tooltip>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup name="EmbeddedFormFieldEditor">
import { Delete, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  /** 从 BPMN extensionElements 回读的内嵌表单字段。 */
  fields: { type: Array, default: () => [] },
  /** 正式扩展注册表返回的 FORM_FIELD 最新版。 */
  customFieldOptions: { type: Array, default: () => [] },
  /** 自定义字段目录请求是否仍在执行。 */
  customFieldLoading: { type: Boolean, default: false }
})

const emit = defineEmits(['change'])
const draftFields = ref([])

// 类型列表与后端 WorkflowEmbeddedFormConverter 白名单保持一致。
const builtInFieldTypeOptions = Object.freeze([
  { label: '文本', value: 'string' },
  { label: '长整数', value: 'long' },
  { label: '整数', value: 'integer' },
  { label: '布尔值', value: 'boolean' },
  { label: '日期', value: 'date' },
  { label: '枚举', value: 'enum' }
])
const fieldTypeOptions = computed(() => [
  ...builtInFieldTypeOptions,
  ...props.customFieldOptions.map(option => ({
    label: `${option.extensionName}（自定义）`,
    value: `custom:${option.extensionKey}`
  }))
])

/**
 * 深拷贝当前字段编辑值，防止子组件直接修改父组件响应式状态。
 * @param {Array<object>} fields 待复制的字段列表。
 * @returns {Array<object>} 可独立修改的字段和枚举值列表。
 */
function cloneFields(fields) {
  return fields.map(field => ({
    ...field,
    values: (field.values || []).map(option => ({ ...option }))
  }))
}

/**
 * 提交当前输入草稿，父组件通过 BPMN 命令栈完成正式写入和校验。
 * @returns {void} 发出一份与组件草稿隔离的完整字段列表。
 */
function commitDraft() {
  emit('change', cloneFields(draftFields.value))
}

/**
 * 生成当前表单中未占用的默认变量标识。
 * @returns {string} 形如 field1 的合法且不重复变量名。
 */
function nextFieldId() {
  const occupied = new Set(draftFields.value.map(field => field.id))
  let sequence = draftFields.value.length + 1
  while (occupied.has(`field${sequence}`)) sequence += 1
  return `field${sequence}`
}

/**
 * 新增一个可立即继续编辑的文本字段。
 * @returns {void} 通过 change 事件提交完整字段列表。
 */
function addField() {
  if (draftFields.value.length >= 500) return
  const id = nextFieldId()
  draftFields.value.push({
    id,
    variable: '',
    name: `字段 ${draftFields.value.length + 1}`,
    type: 'string',
    required: false,
    readable: true,
    writable: true,
    datePattern: '',
    values: []
  })
  commitDraft()
}

/**
 * 合并指定字段的编辑补丁。
 * @param {number} fieldIndex 字段在表单中的索引。
 * @param {object} patch 待写入字段的属性集合。
 * @returns {void} 索引不存在时不发出变更。
 */
function updateField(fieldIndex, patch) {
  const field = draftFields.value[fieldIndex]
  if (!field) return
  Object.assign(field, patch)
  commitDraft()
}

/**
 * 切换字段类型并补齐该类型安全的默认配置。
 * @param {number} fieldIndex 字段在表单中的索引。
 * @param {string} type 六种内置类型或正式注册表返回的 custom: 类型。
 * @returns {void} 类型不在白名单时不发出变更。
 */
function changeFieldType(fieldIndex, type) {
  if (!fieldTypeOptions.value.some(option => option.value === type)) return
  const field = draftFields.value[fieldIndex]
  if (!field) return
  field.type = type
  field.datePattern = type === 'date' ? field.datePattern || 'yyyy-MM-dd' : ''
  field.values = type === 'enum'
    ? field.values.length ? field.values : [{ id: 'OPTION_1', name: '选项 1' }]
    : []
  commitDraft()
}

/**
 * 更新字段可写状态；不可写字段不能保持必填，避免界面生成无法提交的约束。
 * @param {number} fieldIndex 字段在表单中的索引。
 * @param {boolean} writable 字段是否允许当前环节写入。
 * @returns {void} 通过 change 事件提交完整字段列表。
 */
function changeWritable(fieldIndex, writable) {
  updateField(fieldIndex, { writable: Boolean(writable), ...(!writable ? { required: false } : {}) })
}

/**
 * 删除指定内嵌字段。
 * @param {number} fieldIndex 字段在表单中的索引。
 * @returns {void} 索引不存在时不发出变更。
 */
function removeField(fieldIndex) {
  if (!draftFields.value[fieldIndex]) return
  draftFields.value.splice(fieldIndex, 1)
  commitDraft()
}

/**
 * 为枚举字段新增一个不重复的默认静态选项。
 * @param {number} fieldIndex 枚举字段索引。
 * @returns {void} 非枚举、索引不存在或达到上限时不发出变更。
 */
function addEnumValue(fieldIndex) {
  const field = draftFields.value[fieldIndex]
  if (!field || field.type !== 'enum' || field.values.length >= 500) return
  const occupied = new Set(field.values.map(option => option.id))
  let sequence = field.values.length + 1
  while (occupied.has(`OPTION_${sequence}`)) sequence += 1
  field.values.push({ id: `OPTION_${sequence}`, name: `选项 ${sequence}` })
  commitDraft()
}

/**
 * 删除指定枚举静态选项。
 * @param {number} fieldIndex 枚举字段索引。
 * @param {number} optionIndex 枚举选项索引。
 * @returns {void} 字段或选项不存在时不发出变更。
 */
function removeEnumValue(fieldIndex, optionIndex) {
  const values = draftFields.value[fieldIndex]?.values
  if (!values?.[optionIndex]) return
  values.splice(optionIndex, 1)
  commitDraft()
}

watch(() => props.fields, fields => {
  // 草稿只承接当前输入过程；每次 BPMN 命令完成后以父组件正式回读值为准。
  draftFields.value = cloneFields(fields || [])
}, { deep: true, immediate: true })
</script>

<style scoped>
.embedded-form-editor {
  display: grid;
  gap: 10px;
  width: 100%;
}

.embedded-form-editor__toolbar,
.embedded-form-editor__field-heading,
.embedded-form-editor__enum-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.embedded-form-editor__toolbar,
.embedded-form-editor__enum-heading {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.embedded-form-editor__field {
  padding: 10px 0 4px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.embedded-form-editor__field-heading {
  min-height: 32px;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
  font-size: 13px;
}

.embedded-form-editor__switches {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin: 2px 0 12px;
}

.embedded-form-editor__enum {
  display: grid;
  gap: 8px;
  padding: 8px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.embedded-form-editor__enum-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 28px;
  gap: 6px;
  align-items: center;
}

.embedded-form-editor :deep(.el-form-item) {
  margin-bottom: 10px;
}

.embedded-form-editor :deep(.el-empty) {
  padding: 14px 0;
}
</style>
