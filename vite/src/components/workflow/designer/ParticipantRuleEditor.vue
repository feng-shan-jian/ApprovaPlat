<template>
  <section class="participant-rule-editor">
    <el-form-item :label="mode === 'start' ? '发起范围' : '办理人规则'" required>
      <el-select :model-value="modelValue.type" @change="changeType">
        <el-option
          v-for="option in ruleOptions"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>
    </el-form-item>

    <el-form-item v-if="targetConfiguration" :label="targetConfiguration.label" required>
      <el-select
        :model-value="selectedTargetValue"
        :multiple="targetConfiguration.multiple"
        filterable
        remote
        reserve-keyword
        :loading="loading"
        :placeholder="targetConfiguration.placeholder"
        :remote-method="searchTargets"
        @change="changeTargets"
      >
        <el-option
          v-for="option in targetConfiguration.options"
          :key="option.value"
          :label="option.label"
          :value="String(option.value)"
          :disabled="option.available === false"
        />
      </el-select>
    </el-form-item>

    <el-form-item v-if="modelValue.type === 'FORM_USER'" label="用户字段" required>
      <el-select
        :model-value="modelValue.formField"
        filterable
        placeholder="请选择当前任务正式表单字段"
        @change="changeFormField"
      >
        <el-option
          v-for="field in formFields"
          :key="field.value"
          :label="field.label"
          :value="field.value"
        />
      </el-select>
    </el-form-item>

    <div class="participant-rule-editor__result" aria-live="polite">
      <span class="participant-rule-editor__result-label">最终命中</span>
      <span>{{ ruleSummary }}</span>
      <span class="participant-rule-editor__policy">无匹配：阻止流转并记录审计</span>
    </div>
  </section>
</template>

<script setup name="ParticipantRuleEditor">
const props = defineProps({
  /** start 编辑流程发起范围，task 编辑单实例 UserTask 办理规则。 */
  mode: { type: String, required: true },
  /** 受控规则值；targetIds 始终为正式目录选项值数组。 */
  modelValue: {
    type: Object,
    required: true
  },
  /** 正式用户、角色和部门目录选项池。 */
  identityOptions: { type: Object, default: () => ({}) },
  /** 当前 UserTask 正式表单中可保存用户主键的字段。 */
  formFields: { type: Array, default: () => [] },
  /** 正式目录是否正在查询。 */
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change', 'identity-search', 'identity-resolve'])

// 规则选项与后端 WorkflowParticipantRuleBpmnContract 保持一一对应。
const START_RULES = Object.freeze([
  { label: '公开', value: 'PUBLIC' },
  { label: '指定用户', value: 'USERS' },
  { label: '指定角色', value: 'ROLES' },
  { label: '指定部门', value: 'DEPTS' }
])
const TASK_RULES = Object.freeze([
  { label: '固定用户', value: 'FIXED_USER' },
  { label: '候选用户', value: 'CANDIDATE_USERS' },
  { label: '候选角色 / 部门', value: 'CANDIDATE_GROUPS' },
  { label: '发起人本人', value: 'STARTER' },
  { label: '发起人直属上级', value: 'STARTER_MANAGER' },
  { label: '指定部门负责人', value: 'DEPT_MANAGER' },
  { label: '发起人部门内指定角色', value: 'STARTER_DEPT_ROLE' },
  { label: '表单用户字段', value: 'FORM_USER' }
])

const ruleOptions = computed(() => props.mode === 'start' ? START_RULES : TASK_RULES)
const selectedTargetValue = computed(() => (
  targetConfiguration.value?.multiple
    ? props.modelValue.targetIds
    : (props.modelValue.targetIds?.[0] || '')
))

// 每种规则只绑定一个能力隔离的正式目录池，避免把普通启用用户冒充审批人。
const targetConfiguration = computed(() => {
  const configurations = {
    USERS: target('指定用户', 'activeUsers', true, '请选择允许发起的用户'),
    ROLES: target('指定角色', 'activeRoles', true, '请选择允许发起的角色'),
    DEPTS: target('指定部门', 'activeDepts', true, '请选择允许发起的部门'),
    FIXED_USER: target('固定办理人', 'assignees', false, '请选择唯一办理人'),
    CANDIDATE_USERS: target('候选用户', 'candidateUsers', true, '请选择可认领用户'),
    CANDIDATE_GROUPS: target('候选角色或部门', 'candidateGroups', true, '请选择可认领角色或部门'),
    DEPT_MANAGER: target('负责人所属部门', 'activeDepts', false, '请选择一个部门'),
    STARTER_DEPT_ROLE: target('部门内角色', 'candidateRoles', false, '请选择一个角色')
  }
  return configurations[props.modelValue.type] || null
})

const ruleSummary = computed(() => ({
  PUBLIC: '所有启用用户均可发起',
  USERS: '当前用户属于所选用户时可发起',
  ROLES: '当前用户具备任一所选有效角色时可发起',
  DEPTS: '当前用户属于任一所选有效部门时可发起',
  FIXED_USER: '任务创建时核验所选用户的实时审批资格并直接分配',
  CANDIDATE_USERS: '任务创建时保留仍具备完整认领资格的所选用户',
  CANDIDATE_GROUPS: '任务创建时保留仍存在合格认领成员的角色或部门',
  STARTER: '任务创建时由当前流程发起人本人办理',
  STARTER_MANAGER: '按发起人实时部门关系解析唯一直属上级',
  DEPT_MANAGER: '按所选部门实时解析唯一有效负责人',
  STARTER_DEPT_ROLE: '解析发起人当前部门内具备所选角色的全部合格用户',
  FORM_USER: '读取所选正式表单字段并核验其中用户的实时审批资格'
})[props.modelValue.type] || '请选择一条受控规则')

/**
 * 构造规则对应的正式目录选择配置。
 * @param {string} label 字段标签。
 * @param {string} pool 身份选项池名称，也是远程检索目标。
 * @param {boolean} multiple 是否允许多选。
 * @param {string} placeholder 空值提示。
 * @returns {object} 可供模板直接渲染的目录配置。
 */
function target(label, pool, multiple, placeholder) {
  const options = [...(props.identityOptions[pool] || [])]
  const loadedValues = new Set(options.map(option => String(option.value)))
  for (const value of props.modelValue.targetIds || []) {
    if (!loadedValues.has(String(value))) {
      // 异步回显完成前使用无主键占位，避免远程分页外的已选对象在页面暴露裸值。
      options.push({ value: String(value), label: '正在核验已选对象', available: false })
    }
  }
  return { label, pool, multiple, placeholder, options }
}

/**
 * 切换规则并原子清空旧规则不适用的目标和表单字段。
 * @param {string} type 后端白名单中的受控规则类型。
 * @returns {void} 通过 v-model 和 change 同步完整规则。
 */
function changeType(type) {
  publish({ type, targetIds: [], formField: '' })
}

/**
 * 更新当前规则的正式目录目标。
 * @param {string|string[]} values 单选值或多选值数组。
 * @returns {void} 规范为去重字符串数组后发布。
 */
function changeTargets(values) {
  const list = Array.isArray(values) ? values : (values ? [values] : [])
  publish({ ...props.modelValue, targetIds: [...new Set(list.map(String))] })
}

/**
 * 更新表单用户字段，字段只能来自父组件解析的正式表单 Schema。
 * @param {string} value 正式流程变量名。
 * @returns {void} 发布完整规则值。
 */
function changeFormField(value) {
  publish({ ...props.modelValue, formField: String(value || '') })
}

/**
 * 请求父级按当前规则固定的身份类型和能力检索正式目录。
 * @param {string} keyword 设计者输入的名称关键字。
 * @returns {void} 无返回值。
 */
function searchTargets(keyword) {
  if (!targetConfiguration.value) return
  emit('identity-search', {
    target: targetConfiguration.value.pool,
    keyword: String(keyword || '').trim()
  })
}

/**
 * 请求父级批量解析远程分页尚未覆盖的已选身份名称和实时资格。
 * @param {[string|undefined, string[]]} current 当前目录池和缺失受控值。
 * @returns {void} 没有缺失值或同一请求已发出时不重复调用。
 */
function resolveMissingTargets(current) {
  const [pool, missingValues] = current
  if (!pool || !missingValues.length) return
  const requestKey = `${pool}:${missingValues.join(',')}`
  if (requestKey === pendingResolutionKey) return
  pendingResolutionKey = requestKey
  emit('identity-resolve', { target: pool, values: missingValues })
}

let pendingResolutionKey = ''
watch(
  () => {
    const configuration = targetConfiguration.value
    if (!configuration) return [undefined, []]
    const loaded = new Set((props.identityOptions[configuration.pool] || [])
      .map(option => String(option.value)))
    const missing = [...new Set((props.modelValue.targetIds || []).map(String))]
      .filter(value => !loaded.has(value))
    if (!missing.length) pendingResolutionKey = ''
    return [configuration.pool, missing]
  },
  resolveMissingTargets,
  { immediate: true, deep: true }
)

/**
 * 发布字段完整且不共享引用的规则对象。
 * @param {object} value 下一版规则值。
 * @returns {void} 同时触发 v-model 更新和持久化命令。
 */
function publish(value) {
  const normalized = {
    type: String(value.type || ''),
    targetIds: Array.isArray(value.targetIds) ? [...value.targetIds] : [],
    formField: String(value.formField || '')
  }
  emit('update:modelValue', normalized)
  emit('change', normalized)
}
</script>

<style scoped>
.participant-rule-editor {
  padding: 10px;
  margin-bottom: 12px;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.participant-rule-editor :deep(.el-form-item:last-of-type) {
  margin-bottom: 10px;
}

.participant-rule-editor__result {
  display: grid;
  gap: 4px;
  padding-top: 9px;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.5;
  border-top: 1px solid var(--el-border-color-lighter);
}

.participant-rule-editor__result-label {
  color: var(--el-text-color-secondary);
  font-size: 11px;
  font-weight: 700;
}

.participant-rule-editor__policy {
  color: var(--el-color-danger);
  font-size: 11px;
}
</style>
