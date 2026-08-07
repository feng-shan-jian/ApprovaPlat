<template>
  <section class="cel-expression-editor" aria-label="CEL 表达式配置">
    <el-alert
      v-if="parseError"
      :title="parseError"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else-if="validationMessage"
      :title="validationMessage"
      type="warning"
      :closable="false"
      show-icon
    />

    <el-form-item label="表达式" required>
      <el-input
        v-model="draft.expression"
        type="textarea"
        :rows="4"
        maxlength="4096"
        show-word-limit
        placeholder="amount >= 1000.5 && approved"
        @change="commitDraft"
      />
    </el-form-item>

    <div class="cel-expression-editor__result">
      <el-form-item label="结果变量" required>
        <el-input
          v-model="draft.resultVariable"
          maxlength="128"
          placeholder="eligible"
          @change="commitDraft"
        />
      </el-form-item>
      <el-form-item label="结果类型" required>
        <el-select v-model="draft.resultType" @change="commitDraft">
          <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
    </div>

    <div class="cel-expression-editor__variables-heading">
      <span>输入变量 {{ draft.variables.length }}/32</span>
      <el-button
        type="primary"
        plain
        size="small"
        :icon="Plus"
        :disabled="draft.variables.length >= 32"
        @click="addVariable"
      >
        添加变量
      </el-button>
    </div>

    <el-empty v-if="!draft.variables.length" description="未声明输入变量" :image-size="44" />
    <div
      v-for="(variable, variableIndex) in draft.variables"
      :key="variableIndex"
      class="cel-expression-editor__variable-row"
    >
      <el-input
        v-model="variable.name"
        maxlength="128"
        :aria-label="`输入变量 ${variableIndex + 1} 名称`"
        placeholder="变量名"
        @change="commitDraft"
      />
      <el-select
        v-model="variable.type"
        :aria-label="`输入变量 ${variableIndex + 1} 类型`"
        @change="commitDraft"
      >
        <el-option v-for="option in typeOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-tooltip content="删除输入变量" placement="top">
        <el-button
          text
          type="danger"
          :icon="Delete"
          :aria-label="`删除输入变量 ${variableIndex + 1}`"
          @click="removeVariable(variableIndex)"
        />
      </el-tooltip>
    </div>
  </section>
</template>

<script setup name="CelExpressionEditor">
import { Delete, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  /** 作者 BPMN 中 approvaExtensionConfig 字段保存的 CEL 配置 JSON。 */
  modelValue: { type: String, default: '{}' }
})

const emit = defineEmits(['update:modelValue', 'change'])
const parseError = ref('')
const draft = reactive(createDefaultConfig())

// 类型编码与后端 WorkflowCelSandbox 的确定性标量白名单保持一致。
const typeOptions = Object.freeze([
  { label: '布尔值', value: 'BOOL' },
  { label: '整数', value: 'INT' },
  { label: '小数', value: 'DOUBLE' },
  { label: '文本', value: 'STRING' }
])
const allowedTypes = new Set(typeOptions.map(option => option.value))
const variableNamePattern = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/
const reservedVariables = new Set([
  'initiator', 'processStatus', 'processInstanceId', 'processDefinitionId',
  'deploymentId', 'startUserId', 'authenticatedUserId', 'businessKey',
  'assignee', 'nrOfInstances', 'nrOfActiveInstances', 'nrOfCompletedInstances',
  'loopCounter', '_FLOWABLE_SKIP_EXPRESSION_ENABLED'
])
const reservedPrefixes = Object.freeze([
  'wfMiUsers_', '_wfMiMembers_', '_wfMiRevision_', '_wfMiMode_', '__ruoyi_workflow_'
])

const validationMessage = computed(() => validateDraft(draft))

/**
 * 创建与服务端 CEL Schema 一致的安全初始配置。
 * @returns {{expression: string, resultVariable: string, resultType: string, variables: Array<object>}} 可独立编辑的初始配置。
 */
function createDefaultConfig() {
  return {
    expression: 'true',
    resultVariable: 'celResult',
    resultType: 'BOOL',
    variables: []
  }
}

/**
 * 判断变量名是否占用 Flowable 或平台内部命名空间。
 * @param {string} variableName 待检查的输入或结果变量名。
 * @returns {boolean} 属于保留变量或保留前缀时返回 true。
 */
function isReservedVariable(variableName) {
  return reservedVariables.has(variableName)
    || reservedPrefixes.some(prefix => variableName.startsWith(prefix))
}

/**
 * 校验当前草稿的客户端结构约束，服务端仍会执行 CEL 编译和同一安全门禁。
 * @param {object} config 当前响应式 CEL 配置草稿。
 * @returns {string} 第一条可展示的错误信息；通过时返回空字符串。
 */
function validateDraft(config) {
  if (!config.expression.trim()) return '表达式不能为空'
  if (!variableNamePattern.test(config.resultVariable) || isReservedVariable(config.resultVariable)) {
    return '结果变量名称不合法或属于保留变量'
  }
  if (!allowedTypes.has(config.resultType)) return '结果类型不受支持'
  if (config.variables.length > 32) return '输入变量不能超过 32 个'

  const names = new Set()
  for (const variable of config.variables) {
    if (!variableNamePattern.test(variable.name) || isReservedVariable(variable.name)) {
      return '输入变量名称不合法或属于保留变量'
    }
    if (!allowedTypes.has(variable.type)) return '输入变量类型不受支持'
    if (names.has(variable.name)) return `输入变量重复：${variable.name}`
    if (variable.name === config.resultVariable) return '结果变量不能覆盖输入变量'
    names.add(variable.name)
  }
  return ''
}

/**
 * 解析父组件回读的 JSON，并拒绝服务端 Schema 之外的字段和类型。
 * @param {string} modelValue 作者 XML 中的扩展配置 JSON。
 * @returns {{expression: string, resultVariable: string, resultType: string, variables: Array<object>}} 可安全装载的编辑配置。
 */
function parseConfiguration(modelValue) {
  const parsed = JSON.parse(modelValue || '{}')
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
    throw new Error('CEL 配置必须是 JSON 对象')
  }
  const allowedFields = new Set(['expression', 'resultVariable', 'resultType', 'variables'])
  if (Object.keys(parsed).some(field => !allowedFields.has(field))) {
    throw new Error('CEL 配置包含未允许字段')
  }
  if (typeof parsed.expression !== 'string'
    || typeof parsed.resultVariable !== 'string'
    || typeof parsed.resultType !== 'string'
    || !Array.isArray(parsed.variables)) {
    throw new Error('CEL 配置字段缺失或类型错误')
  }
  if (parsed.variables.length > 32) throw new Error('CEL 输入变量不能超过 32 个')
  const variables = parsed.variables.map(variable => {
    if (!variable || Array.isArray(variable) || typeof variable !== 'object'
      || Object.keys(variable).some(field => !['name', 'type'].includes(field))
      || typeof variable.name !== 'string' || typeof variable.type !== 'string') {
      throw new Error('CEL 输入变量声明格式错误')
    }
    return { name: variable.name, type: variable.type }
  })
  return {
    expression: parsed.expression,
    resultVariable: parsed.resultVariable,
    resultType: parsed.resultType,
    variables
  }
}

/**
 * 将编辑草稿收敛为字段顺序和变量顺序确定的配置对象。
 * @returns {{expression: string, resultVariable: string, resultType: string, variables: Array<object>}} 可写入作者 BPMN 的规范配置。
 */
function normalizeDraft() {
  return {
    expression: draft.expression.trim(),
    resultVariable: draft.resultVariable.trim(),
    resultType: draft.resultType,
    variables: draft.variables
      .map(variable => ({ name: variable.name.trim(), type: variable.type }))
      .sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0)
  }
}

/**
 * 提交完整规范 JSON，让父组件通过 bpmn-js 命令栈写入作者 XML。
 * @returns {void} 同步发出 v-model 更新和 change 事件。
 */
function commitDraft() {
  parseError.value = ''
  const normalized = normalizeDraft()
  Object.assign(draft, normalized)
  const configJson = JSON.stringify(normalized)
  emit('update:modelValue', configJson)
  emit('change', configJson)
}

/**
 * 生成当前配置中未占用的默认输入变量名。
 * @returns {string} 形如 input1 的合法且不重复变量名。
 */
function nextVariableName() {
  const occupied = new Set(draft.variables.map(variable => variable.name))
  let sequence = draft.variables.length + 1
  while (occupied.has(`input${sequence}`)) sequence += 1
  return `input${sequence}`
}

/**
 * 新增一个默认布尔输入变量并立即提交完整配置。
 * @returns {void} 达到 32 个变量上限时不修改草稿。
 */
function addVariable() {
  if (draft.variables.length >= 32) return
  draft.variables.push({ name: nextVariableName(), type: 'BOOL' })
  commitDraft()
}

/**
 * 删除指定输入变量并提交剩余配置。
 * @param {number} variableIndex 输入变量在草稿列表中的索引。
 * @returns {void} 索引不存在时不修改草稿。
 */
function removeVariable(variableIndex) {
  if (!draft.variables[variableIndex]) return
  draft.variables.splice(variableIndex, 1)
  commitDraft()
}

watch(() => props.modelValue, modelValue => {
  try {
    const parsed = parseConfiguration(modelValue)
    Object.assign(draft, parsed)
    parseError.value = ''
  } catch (error) {
    // 非法作者配置只进入显式错误态，不自动覆盖原 XML；用户完成一次编辑后才提交新配置。
    Object.assign(draft, createDefaultConfig())
    parseError.value = error instanceof Error ? error.message : 'CEL 配置无法解析'
  }
}, { immediate: true })
</script>

<style scoped>
.cel-expression-editor {
  display: grid;
  gap: 10px;
  width: 100%;
}

.cel-expression-editor__result {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(104px, 0.7fr);
  gap: 8px;
}

.cel-expression-editor__variables-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 32px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.cel-expression-editor__variable-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 104px 28px;
  gap: 6px;
  align-items: center;
}

.cel-expression-editor :deep(.el-form-item) {
  margin-bottom: 8px;
}

.cel-expression-editor :deep(.el-empty) {
  padding: 10px 0;
}

.cel-expression-editor :deep(.el-alert) {
  align-items: flex-start;
}

.cel-expression-editor :deep(.el-alert__title) {
  overflow-wrap: anywhere;
  line-height: 18px;
}
</style>
