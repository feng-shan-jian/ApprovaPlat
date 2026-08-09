<template>
  <div class="auto-copy-rule-editor">
    <div v-for="(rule, ruleIndex) in draft" :key="rule.rowKey" class="auto-copy-rule-editor__rule">
      <div class="auto-copy-rule-editor__rule-header">
        <strong>规则 {{ ruleIndex + 1 }}</strong>
        <el-tooltip content="删除自动抄送规则" placement="top">
          <el-button :icon="Delete" circle aria-label="删除自动抄送规则" @click="removeRule(ruleIndex)" />
        </el-tooltip>
      </div>

      <el-form-item label="触发时机" required>
        <el-select v-model="rule.trigger" @change="markDirty">
          <el-option
            v-for="option in triggerOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>

      <div class="auto-copy-rule-editor__recipients">
        <div class="auto-copy-rule-editor__recipient-title">
          <span>接收人来源</span>
          <el-button
            text
            type="primary"
            :icon="Plus"
            :disabled="rule.recipients.length >= maxSources"
            @click="addRecipient(rule)"
          >新增来源</el-button>
        </div>

        <div
          v-for="(recipient, recipientIndex) in rule.recipients"
          :key="recipient.rowKey"
          class="auto-copy-rule-editor__recipient"
        >
          <el-select v-model="recipient.type" aria-label="接收人来源类型" @change="changeRecipientType(recipient)">
            <el-option v-for="option in recipientTypeOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>

          <el-select
            v-if="recipient.type === 'USER'"
            v-model="recipient.values"
            multiple
            filterable
            remote
            reserve-keyword
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="2"
            :multiple-limit="maxValues"
            :remote-method="searchUsers"
            :loading="identityLoading"
            placeholder="选择固定用户"
            @change="markDirty"
          >
            <el-option v-for="option in userOptions" :key="option.value" :label="option.label" :value="String(option.value)" />
          </el-select>

          <el-select
            v-else-if="recipient.type === 'GROUP'"
            v-model="recipient.values"
            multiple
            filterable
            remote
            reserve-keyword
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="2"
            :multiple-limit="maxValues"
            :remote-method="searchGroups"
            :loading="identityLoading"
            placeholder="选择角色或部门"
            @change="markDirty"
          >
            <el-option v-for="option in groupOptions" :key="option.value" :label="option.label" :value="String(option.value)" />
          </el-select>

          <el-select
            v-else-if="recipient.type === 'FORM_USER_FIELD'"
            v-model="recipient.values"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            :max-collapse-tags="2"
            :multiple-limit="maxValues"
            placeholder="选择表单用户字段"
            @change="markDirty"
          >
            <el-option v-for="option in formFieldOptions" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>

          <el-input v-else model-value="流程发起人" disabled />

          <el-tooltip content="删除接收人来源" placement="top">
            <el-button :icon="Delete" circle aria-label="删除接收人来源" @click="removeRecipient(rule, recipientIndex)" />
          </el-tooltip>
        </div>
        <el-empty v-if="!rule.recipients.length" description="尚未配置接收人来源" :image-size="42" />
      </div>
    </div>

    <el-alert v-if="validationMessage" type="warning" show-icon :closable="false" :title="validationMessage" />
    <div class="auto-copy-rule-editor__actions">
      <el-button :icon="Plus" :disabled="draft.length >= maxRules" @click="addRule">新增规则</el-button>
      <el-button type="primary" plain :disabled="!dirty || Boolean(validationMessage)" @click="applyRules">
        应用自动抄送规则
      </el-button>
    </div>
  </div>
</template>

<script setup name="AutoCopyRuleEditor">
import { Delete, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  /** 已写入当前 BPMN 元素的自动抄送规则。 */
  modelValue: { type: Array, default: () => [] },
  /** 当前 BPMN 元素允许选择的生命周期触发时机。 */
  triggerOptions: { type: Array, default: () => [] },
  /** 正式启用用户目录选项。 */
  userOptions: { type: Array, default: () => [] },
  /** 正式启用角色和部门目录选项。 */
  groupOptions: { type: Array, default: () => [] },
  /** 当前流程正式表单中允许作为用户主键来源的标量字段。 */
  formFieldOptions: { type: Array, default: () => [] },
  /** 正式身份目录是否正在加载。 */
  identityLoading: { type: Boolean, default: false },
  /** 单个元素允许配置的规则数量。 */
  maxRules: { type: Number, default: 10 },
  /** 单条规则允许配置的接收人来源数量。 */
  maxSources: { type: Number, default: 20 },
  /** 单个来源允许配置的固定值数量。 */
  maxValues: { type: Number, default: 100 }
})

const emit = defineEmits(['update:modelValue', 'change', 'identity-search'])
const draft = ref([])
const dirty = ref(false)
let nextRowKey = 1
let nextRuleSequence = 1

// 来源枚举与后端 WorkflowAutoCopyRuleContract.RecipientType 保持一致。
const recipientTypeOptions = Object.freeze([
  { label: '固定用户', value: 'USER' },
  { label: '角色或部门', value: 'GROUP' },
  { label: '流程发起人', value: 'INITIATOR' },
  { label: '表单用户字段', value: 'FORM_USER_FIELD' }
])

const validationMessage = computed(() => validateDraft())

/**
 * 将父组件的正式规则复制为可独立编辑的草稿，避免半成品配置进入 BPMN 命令栈。
 * @param {Array<object>} rules 已通过设计器校验并写入当前元素的规则。
 * @returns {void} 重建草稿并清除未应用标记。
 */
function syncDraft(rules) {
  draft.value = (Array.isArray(rules) ? rules : []).map(rule => ({
    rowKey: nextRowKey++,
    id: String(rule?.id || ''),
    trigger: String(rule?.trigger || ''),
    recipients: (Array.isArray(rule?.recipients) ? rule.recipients : []).map(recipient => ({
      rowKey: nextRowKey++,
      type: String(recipient?.type || 'USER'),
      values: (Array.isArray(recipient?.values) ? recipient.values : []).map(String)
    }))
  }))
  dirty.value = false
}

/**
 * 生成符合后端规则主键白名单的稳定标识。
 * @returns {string} 以 auto_copy_ 开头且长度不超过 64 的规则标识。
 */
function createRuleId() {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return `auto_copy_${globalThis.crypto.randomUUID()}`.slice(0, 64)
  }
  return `auto_copy_${Date.now().toString(36)}_${nextRuleSequence++}`
}

/**
 * 新增一条带默认触发时机和固定用户来源的规则草稿。
 * @returns {void} 达到后端规则数量上限时不修改草稿。
 */
function addRule() {
  if (draft.value.length >= props.maxRules) return
  draft.value.push({
    rowKey: nextRowKey++,
    id: createRuleId(),
    trigger: props.triggerOptions[0]?.value || '',
    recipients: [{ rowKey: nextRowKey++, type: 'USER', values: [] }]
  })
  markDirty()
}

/**
 * 删除指定自动抄送规则草稿。
 * @param {number} index 规则在当前草稿中的数组下标。
 * @returns {void} 下标合法时删除规则并等待设计者显式应用。
 */
function removeRule(index) {
  if (index < 0 || index >= draft.value.length) return
  draft.value.splice(index, 1)
  markDirty()
}

/**
 * 为指定规则新增一个固定用户接收来源。
 * @param {object} rule 当前正在编辑的自动抄送规则草稿。
 * @returns {void} 达到单规则来源上限时不修改草稿。
 */
function addRecipient(rule) {
  if (!rule || rule.recipients.length >= props.maxSources) return
  rule.recipients.push({ rowKey: nextRowKey++, type: 'USER', values: [] })
  markDirty()
}

/**
 * 删除规则中的指定接收人来源。
 * @param {object} rule 当前正在编辑的规则草稿。
 * @param {number} index 来源在规则中的数组下标。
 * @returns {void} 下标合法时删除来源并等待显式应用。
 */
function removeRecipient(rule, index) {
  if (!rule || index < 0 || index >= rule.recipients.length) return
  rule.recipients.splice(index, 1)
  markDirty()
}

/**
 * 切换来源类型时清空旧类型值，防止用户、组或变量主键跨业务类型复用。
 * @param {object} recipient 当前接收人来源草稿。
 * @returns {void} 发起人和其他来源统一重置为空值集合。
 */
function changeRecipientType(recipient) {
  recipient.values = []
  markDirty()
}

/**
 * 标记当前草稿尚未写入 BPMN。
 * @returns {void} 仅更新组件编辑状态。
 */
function markDirty() {
  dirty.value = true
}

/**
 * 请求父组件从正式启用用户目录远程检索固定接收人。
 * @param {string} keyword 设计者输入的用户名称或账号关键字。
 * @returns {void} 通过 identity-search 事件交由页面调用真实 API。
 */
function searchUsers(keyword) {
  emit('identity-search', { target: 'autoCopyUsers', keyword })
}

/**
 * 请求父组件从正式启用角色和部门目录远程检索固定接收组。
 * @param {string} keyword 设计者输入的角色、部门名称或编码关键字。
 * @returns {void} 通过 identity-search 事件交由页面调用真实 API。
 */
function searchGroups(keyword) {
  emit('identity-search', { target: 'autoCopyGroups', keyword })
}

/**
 * 校验草稿的数量、触发时机、来源类型、固定值格式和重复来源。
 * @returns {string} 首个稳定错误提示；空串表示草稿可安全提交给父设计器。
 */
function validateDraft() {
  if (draft.value.length > props.maxRules) return `单个元素最多允许 ${props.maxRules} 条自动抄送规则`
  const allowedTriggers = new Set(props.triggerOptions.map(option => option.value))
  const ruleIds = new Set()
  for (const rule of draft.value) {
    if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/.test(rule.id) || ruleIds.has(rule.id)) {
      return '自动抄送规则主键不合法或重复'
    }
    ruleIds.add(rule.id)
    if (!allowedTriggers.has(rule.trigger)) return '自动抄送触发时机与当前元素不匹配'
    if (!rule.recipients.length || rule.recipients.length > props.maxSources) {
      return `每条规则必须配置 1 至 ${props.maxSources} 个接收人来源`
    }
    const sourceKeys = new Set()
    for (const recipient of rule.recipients) {
      if (!recipientTypeOptions.some(option => option.value === recipient.type)) {
        return '自动抄送接收人来源不受支持'
      }
      const values = recipient.values.map(String)
      if (recipient.type === 'INITIATOR') {
        if (values.length) return '流程发起人来源不能携带额外值'
      } else if (!values.length || values.length > props.maxValues) {
        return `每个接收人来源必须选择 1 至 ${props.maxValues} 个值`
      }
      if (new Set(values).size !== values.length) return '同一接收人来源不能包含重复值'
      if (recipient.type === 'USER' && values.some(value => !/^[1-9]\d{0,18}$/.test(value))) {
        return '固定用户来源包含非法用户主键'
      }
      if (recipient.type === 'GROUP' && values.some(value => !/^(?:ROLE|DEPT)[1-9]\d{0,18}$/.test(value))) {
        return '角色或部门来源包含非法身份编码'
      }
      if (recipient.type === 'FORM_USER_FIELD' && values.some(value => !/^[A-Za-z][A-Za-z0-9_.]{0,63}$/.test(value))) {
        return '表单用户字段来源包含非法变量名'
      }
      const sourceKey = `${recipient.type}:${values.join(',')}`
      if (sourceKeys.has(sourceKey)) return '同一规则不能重复配置相同接收人来源'
      sourceKeys.add(sourceKey)
    }
  }
  return ''
}

/**
 * 将校验通过的完整草稿应用到父设计器，由父组件原子写入 Flowable 扩展属性。
 * @returns {void} 规则为空时提交空数组以删除当前元素的自动抄送属性。
 */
function applyRules() {
  if (validationMessage.value) return
  const rules = draft.value.map(rule => ({
    id: rule.id,
    trigger: rule.trigger,
    recipients: rule.recipients.map(recipient => ({
      type: recipient.type,
      values: recipient.values.map(String)
    }))
  }))
  emit('update:modelValue', rules)
  emit('change', rules)
  dirty.value = false
}

watch(() => props.modelValue, syncDraft, { immediate: true, deep: true })
</script>

<style scoped>
.auto-copy-rule-editor {
  display: grid;
  gap: 10px;
  width: 100%;
}

.auto-copy-rule-editor__rule {
  display: grid;
  gap: 10px;
  padding: 10px;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.auto-copy-rule-editor__rule-header,
.auto-copy-rule-editor__recipient-title,
.auto-copy-rule-editor__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.auto-copy-rule-editor__rule-header strong,
.auto-copy-rule-editor__recipient-title span {
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-weight: 650;
}

.auto-copy-rule-editor__rule-header :deep(.el-button),
.auto-copy-rule-editor__recipient :deep(.el-button) {
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 4px;
}

.auto-copy-rule-editor__recipients {
  display: grid;
  gap: 8px;
}

.auto-copy-rule-editor__recipient {
  display: grid;
  grid-template-columns: minmax(112px, 0.75fr) minmax(150px, 1.25fr) 30px;
  align-items: center;
  gap: 6px;
}

.auto-copy-rule-editor__actions {
  flex-wrap: wrap;
}

.auto-copy-rule-editor :deep(.el-form-item) {
  margin-bottom: 0;
}

.auto-copy-rule-editor :deep(.el-select),
.auto-copy-rule-editor :deep(.el-input) {
  width: 100%;
}

@media (max-width: 520px) {
  .auto-copy-rule-editor__recipient {
    grid-template-columns: minmax(0, 1fr) 30px;
  }

  .auto-copy-rule-editor__recipient > :first-child {
    grid-column: 1 / -1;
  }
}
</style>
