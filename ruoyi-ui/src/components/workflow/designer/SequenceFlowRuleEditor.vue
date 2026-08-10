<template>
  <section class="condition-editor">
    <div class="condition-editor__gateway">
      <div>
        <span class="condition-editor__eyebrow">{{ gatewayLabel }}</span>
        <strong>分支 {{ configuredCount }}/{{ gatewayBranches.length }} 已配置</strong>
      </div>
      <el-tag :type="hasDefault ? 'success' : 'danger'" size="small" effect="plain">
        {{ hasDefault ? '默认分支已设置' : '缺少默认分支' }}
      </el-tag>
    </div>

    <div v-if="gatewayBranches.length" class="condition-editor__branches" aria-label="网关分支配置状态">
      <span
        v-for="branch in gatewayBranches"
        :key="branch.id"
        :class="['condition-editor__branch', { 'is-current': branch.id === flowId }]"
      >
        <i :class="branch.configured ? 'is-ready' : 'is-missing'" />
        {{ branch.name || branch.id }}
        <small v-if="branch.default">默认</small>
      </span>
    </div>

    <el-alert
      v-if="!fieldOptions.length && !isDefault"
      type="warning"
      :closable="false"
      show-icon
      title="当前流程没有可用于判断的正式表单标量字段"
    />
    <el-alert
      v-else-if="gatewayContextConflicts.length"
      type="error"
      :closable="false"
      show-icon
      :title="`正式表单存在同名异构字段：${gatewayContextConflicts.join('、')}`"
    />

    <el-form-item label="分支名称" required>
      <el-input v-model="branchName" maxlength="100" show-word-limit placeholder="例如：金额超过 5000 元" />
    </el-form-item>

    <template v-if="isDefault">
      <div class="condition-editor__default">
        <el-icon><Flag /></el-icon>
        <div>
          <strong>默认分支</strong>
          <span>其他分支均未命中时进入此分支。</span>
        </div>
      </div>
      <el-button type="primary" :disabled="!branchName.trim()" @click="applyDefault">保存分支名称</el-button>
    </template>

    <template v-else>
      <div class="condition-editor__mode-row">
        <span>规则组之间</span>
        <el-segmented v-model="draft.combinator" :options="combinatorOptions" />
      </div>

      <article v-for="(group, groupIndex) in draft.groups" :key="group.key" class="condition-editor__group">
        <header>
          <strong>规则组 {{ groupIndex + 1 }}</strong>
          <div>
            <el-segmented v-model="group.combinator" :options="combinatorOptions" size="small" />
            <el-tooltip content="删除规则组" placement="top">
              <el-button
                circle
                text
                icon="Delete"
                :disabled="draft.groups.length === 1"
                :aria-label="`删除规则组 ${groupIndex + 1}`"
                @click="removeGroup(groupIndex)"
              />
            </el-tooltip>
          </div>
        </header>

        <div v-for="(rule, ruleIndex) in group.rules" :key="rule.key" class="condition-editor__rule">
          <el-select
            v-model="rule.field"
            filterable
            placeholder="选择正式表单字段"
            :aria-label="`规则组 ${groupIndex + 1} 字段 ${ruleIndex + 1}`"
            @change="changeField(rule)"
          >
            <el-option v-for="field in fieldOptions" :key="field.value" :label="field.label" :value="field.value">
              <span>{{ field.label }}</span>
              <el-tag size="small" effect="plain">{{ typeLabel(field.type) }}</el-tag>
            </el-option>
          </el-select>
          <div class="condition-editor__rule-value">
            <el-select v-model="rule.operator" :aria-label="`规则组 ${groupIndex + 1} 运算符 ${ruleIndex + 1}`">
              <el-option v-for="operator in operatorsFor(rule)" :key="operator.value" :label="operator.label" :value="operator.value" />
            </el-select>
            <el-select
              v-if="selectedField(rule)?.valueRestricted"
              v-model="rule.value"
              filterable
              :aria-label="`规则组 ${groupIndex + 1} 条件值 ${ruleIndex + 1}`"
            >
              <el-option v-for="option in selectedField(rule).values" :key="String(option.value)" :label="option.label" :value="typedOptionValue(selectedField(rule), option.value)" />
            </el-select>
            <el-input-number
              v-else-if="selectedField(rule)?.type === 'NUMBER'"
              v-model="rule.value"
              :controls="false"
              :precision="undefined"
              :aria-label="`规则组 ${groupIndex + 1} 数值 ${ruleIndex + 1}`"
            />
            <el-input
              v-else
              v-model="rule.value"
              maxlength="1024"
              :aria-label="`规则组 ${groupIndex + 1} 文本值 ${ruleIndex + 1}`"
              placeholder="输入比较值"
            />
            <el-tooltip content="删除规则" placement="top">
              <el-button
                circle
                text
                icon="Close"
                :disabled="group.rules.length === 1"
                :aria-label="`删除规则组 ${groupIndex + 1} 的第 ${ruleIndex + 1} 条规则`"
                @click="removeRule(group, ruleIndex)"
              />
            </el-tooltip>
          </div>
        </div>

        <el-button text type="primary" icon="Plus" :disabled="group.rules.length >= 8" @click="addRule(group)">添加规则</el-button>
      </article>

      <div class="condition-editor__actions">
        <el-button icon="Plus" :disabled="draft.groups.length >= 8" @click="addGroup">添加规则组</el-button>
        <el-button type="primary" icon="Check" :disabled="!canApply" @click="applyRule">应用规则</el-button>
      </div>
      <el-button class="condition-editor__default-action" text type="primary" icon="Flag" @click="emit('make-default')">设为默认分支</el-button>
    </template>
  </section>
</template>

<script setup name="SequenceFlowRuleEditor">
import { Flag } from '@element-plus/icons-vue'

const props = defineProps({
  flowId: { type: String, required: true },
  name: { type: String, default: '' },
  config: { type: Object, default: null },
  isDefault: { type: Boolean, default: false },
  gatewayType: { type: String, default: '' },
  gatewayBranches: { type: Array, default: () => [] },
  fieldConflicts: { type: Array, default: () => [] },
  fieldOptions: { type: Array, default: () => [] }
})

const emit = defineEmits(['apply', 'make-default'])
const combinatorOptions = Object.freeze([
  { label: '全部满足', value: 'AND' },
  { label: '任一满足', value: 'OR' }
])
const equalityOperators = Object.freeze([
  { label: '等于', value: 'EQ' },
  { label: '不等于', value: 'NE' }
])
const numberOperators = Object.freeze([
  ...equalityOperators,
  { label: '大于', value: 'GT' },
  { label: '大于等于', value: 'GTE' },
  { label: '小于', value: 'LT' },
  { label: '小于等于', value: 'LTE' }
])
const textOperators = Object.freeze([
  ...equalityOperators,
  { label: '包含', value: 'CONTAINS' },
  { label: '开头是', value: 'STARTS_WITH' },
  { label: '结尾是', value: 'ENDS_WITH' }
])

const branchName = ref('')
const draft = reactive(createDraft())
const gatewayLabel = computed(() => props.gatewayType === 'INCLUSIVE' ? '包容网关' : '排他网关')
const configuredCount = computed(() => props.gatewayBranches.filter(branch => branch.configured).length)
const hasDefault = computed(() => props.gatewayBranches.some(branch => branch.default))
const gatewayContextConflicts = computed(() => props.fieldConflicts)
const canApply = computed(() => Boolean(branchName.value.trim())
  && props.fieldOptions.length > 0
  && draft.groups.length > 0
  && draft.groups.every(group => group.rules.length > 0 && group.rules.every(rule => {
    const field = selectedField(rule)
    return Boolean(field && rule.operator && rule.value !== '' && rule.value !== null && rule.value !== undefined)
  })))

/**
 * 创建不会共享引用的空规则。
 * @returns {{key:string,field:string,operator:string,value:unknown}} 可直接编辑的原子规则。
 */
function createRule() {
  const field = props.fieldOptions[0]
  return {
    key: crypto.randomUUID(),
    field: field?.value || '',
    operator: operatorsForField(field)[0]?.value || 'EQ',
    value: defaultValue(field)
  }
}

/**
 * 从当前配置创建面板草稿，未知或缺失结构回退为空规则组。
 * @returns {{combinator:string,groups:Array<object>}} 深拷贝规则草稿。
 */
function createDraft() {
  const source = props.config?.default === false && Array.isArray(props.config.groups)
    ? props.config
    : { combinator: 'AND', groups: [{ combinator: 'AND', rules: [] }] }
  return {
    combinator: source.combinator === 'OR' ? 'OR' : 'AND',
    groups: source.groups.map(group => ({
      key: crypto.randomUUID(),
      combinator: group.combinator === 'OR' ? 'OR' : 'AND',
      // props.config 会被 Vue 包装成 Proxy，只显式复制后端契约允许的三个 JSON 标量。
      rules: (group.rules || []).map(rule => ({
        key: crypto.randomUUID(),
        field: String(rule?.field || ''),
        operator: String(rule?.operator || ''),
        value: ['string', 'number', 'boolean'].includes(typeof rule?.value) ? rule.value : ''
      }))
    })).map(group => ({ ...group, rules: group.rules.length ? group.rules : [createRule()] }))
  }
}

/**
 * 根据字段类型返回后端允许的运算符目录。
 * @param {object|undefined} field 条件字段元数据。
 * @returns {Array<{label:string,value:string}>} 受控运算符集合。
 */
function operatorsForField(field) {
  if (field?.type === 'NUMBER') return numberOperators
  if (field?.type === 'BOOLEAN' || field?.valueRestricted) return equalityOperators
  return textOperators
}

/**
 * 返回单条规则当前字段允许的运算符。
 * @param {object} rule 原子规则草稿。
 * @returns {Array<{label:string,value:string}>} 运算符集合。
 */
function operatorsFor(rule) {
  return operatorsForField(selectedField(rule))
}

/**
 * 从服务端同源字段目录解析规则选择的字段。
 * @param {object} rule 原子规则草稿。
 * @returns {object|undefined} 字段元数据。
 */
function selectedField(rule) {
  return props.fieldOptions.find(field => field.value === rule.field)
}

/**
 * 切换字段时同步重置运算符和值类型，防止沿用旧字段的非法值。
 * @param {object} rule 原子规则草稿。
 * @returns {void} 直接更新当前草稿。
 */
function changeField(rule) {
  const field = selectedField(rule)
  rule.operator = operatorsForField(field)[0]?.value || 'EQ'
  rule.value = defaultValue(field)
}

/**
 * 按字段类型生成可立即编辑的初始值。
 * @param {object|undefined} field 字段元数据。
 * @returns {string|number|boolean} 类型化默认值。
 */
function defaultValue(field) {
  if (field?.valueRestricted && field.values?.length) return typedOptionValue(field, field.values[0].value)
  if (field?.type === 'BOOLEAN') return true
  if (field?.type === 'NUMBER') return 0
  return ''
}

/**
 * 将枚举显示值转换为后端字段类型要求的 JSON 标量。
 * @param {object} field 字段元数据。
 * @param {unknown} value 枚举原始值。
 * @returns {string|boolean} 类型化枚举值。
 */
function typedOptionValue(field, value) {
  if (field?.type === 'BOOLEAN') return value === true || String(value) === 'true'
  return String(value)
}

/** @param {string} type 字段类型编码。 @returns {string} 中文类型名称。 */
function typeLabel(type) {
  return ({ NUMBER: '数值', BOOLEAN: '布尔', SCALAR: '枚举', TEXT: '文本' })[type] || type
}

/** @param {object} group 规则组。 @returns {void} 在服务端上限内追加原子规则。 */
function addRule(group) {
  if (group.rules.length < 8) group.rules.push(createRule())
}

/** @param {object} group 规则组。 @param {number} index 规则索引。 @returns {void} 至少保留一条规则。 */
function removeRule(group, index) {
  if (group.rules.length > 1) group.rules.splice(index, 1)
}

/** @returns {void} 在服务端上限内追加规则组。 */
function addGroup() {
  if (draft.groups.length < 8) draft.groups.push({ key: crypto.randomUUID(), combinator: 'AND', rules: [createRule()] })
}

/** @param {number} index 规则组索引。 @returns {void} 至少保留一个规则组。 */
function removeGroup(index) {
  if (draft.groups.length > 1) draft.groups.splice(index, 1)
}

/**
 * 提交规范的非默认分支配置和分支名称。
 * @returns {void} 未通过本地完整性校验时按钮保持禁用。
 */
function applyRule() {
  if (!canApply.value) return
  emit('apply', {
    name: branchName.value.trim(),
    config: {
      version: 1,
      default: false,
      combinator: draft.combinator,
      groups: draft.groups.map(group => ({
        combinator: group.combinator,
        rules: group.rules.map(({ field, operator, value }) => ({ field, operator, value }))
      }))
    }
  })
}

/** @returns {void} 默认分支仅更新名称并保持固定默认配置。 */
function applyDefault() {
  if (branchName.value.trim()) emit('apply', { name: branchName.value.trim(), config: { version: 1, default: true } })
}

/**
 * 重新载入选中分支时完整替换草稿，避免跨分支残留规则。
 * @returns {void} 草稿和名称与作者 BPMN 保持一致。
 */
function resetDraft() {
  branchName.value = props.name || ''
  const next = createDraft()
  draft.combinator = next.combinator
  draft.groups.splice(0, draft.groups.length, ...next.groups)
}

watch(() => [props.flowId, props.name, props.config], resetDraft, { immediate: true, deep: true })
</script>

<style scoped>
.condition-editor { display: grid; gap: 14px; }
.condition-editor__gateway { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 10px; background: var(--el-fill-color-light); border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.condition-editor__gateway > div { display: grid; gap: 2px; min-width: 0; }
.condition-editor__gateway strong { color: var(--el-text-color-primary); font-size: 12px; }
.condition-editor__eyebrow { color: var(--el-color-primary); font-size: 10px; font-weight: 700; }
.condition-editor__branches { display: flex; flex-wrap: wrap; gap: 6px; }
.condition-editor__branch { display: inline-flex; align-items: center; max-width: 100%; gap: 5px; padding: 4px 7px; overflow: hidden; color: var(--el-text-color-secondary); font-size: 11px; border: 1px solid var(--el-border-color-lighter); border-radius: 4px; text-overflow: ellipsis; white-space: nowrap; }
.condition-editor__branch.is-current { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); }
.condition-editor__branch i { width: 6px; height: 6px; flex: none; border-radius: 50%; }
.condition-editor__branch i.is-ready { background: var(--el-color-success); }
.condition-editor__branch i.is-missing { background: var(--el-color-danger); }
.condition-editor__branch small { color: var(--el-color-success); }
.condition-editor__mode-row, .condition-editor__group header, .condition-editor__group header > div, .condition-editor__actions { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.condition-editor__mode-row span { color: var(--el-text-color-secondary); font-size: 12px; font-weight: 600; }
.condition-editor__group { display: grid; gap: 10px; padding: 10px; border: 1px solid var(--el-border-color-lighter); border-left: 3px solid var(--el-color-primary); border-radius: 6px; }
.condition-editor__group header strong { font-size: 12px; }
.condition-editor__rule { display: grid; gap: 7px; padding-top: 9px; border-top: 1px dashed var(--el-border-color-lighter); }
.condition-editor__rule-value { display: grid; grid-template-columns: minmax(92px, 0.8fr) minmax(0, 1.2fr) 28px; gap: 6px; align-items: center; }
.condition-editor__rule :deep(.el-select), .condition-editor__rule :deep(.el-input-number) { width: 100%; }
.condition-editor__default { display: flex; align-items: center; gap: 10px; padding: 13px; color: var(--el-color-success); background: var(--el-color-success-light-9); border: 1px solid var(--el-color-success-light-7); border-radius: 6px; }
.condition-editor__default .el-icon { font-size: 20px; }
.condition-editor__default > div { display: grid; gap: 2px; }
.condition-editor__default span { color: var(--el-text-color-secondary); font-size: 11px; }
.condition-editor__actions > * { flex: 1; }
.condition-editor__default-action { justify-self: start; }
@media (max-width: 420px) { .condition-editor__rule-value { grid-template-columns: 1fr 28px; } .condition-editor__rule-value > :first-child { grid-column: 1 / -1; } }
</style>
