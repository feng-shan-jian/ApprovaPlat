<template>
  <div class="decision-editor">
    <header class="decision-editor__heading">
      <div><span class="decision-editor__eyebrow">业务决策表</span><h2>把审批规则写成表格</h2><p>定义输入和结果，逐行填写条件，再用真实决策引擎试算。</p></div>
      <el-tag effect="plain">{{ form.rules.length }} 条规则</el-tag>
    </header>
    <el-form label-position="top" :disabled="busy">
      <div class="decision-editor__identity">
        <el-form-item label="业务名称" required><el-input v-model="form.name" placeholder="例如：采购审批级别" maxlength="128" /></el-form-item>
        <el-form-item label="决策标识" required><el-input v-model="form.key" placeholder="例如：purchaseApproval" maxlength="128" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="form.category" maxlength="255" /></el-form-item>
      </div>
      <section class="decision-editor__section">
        <h3><span>01</span> 定义输入与结果</h3>
        <div class="decision-editor__columns">
          <div v-for="side in ['inputs', 'outputs']" :key="side">
            <div class="decision-editor__section-heading"><strong>{{ side === 'inputs' ? '输入列' : '输出列' }}</strong><el-button size="small" :disabled="form[side].length >= 8" @click="addColumn(side)">添加列</el-button></div>
            <div v-for="(column, index) in form[side]" :key="index" class="decision-editor__column">
              <el-input v-model="column.label" :aria-label="`${side === 'inputs' ? '输入' : '输出'}列 ${index + 1} 名称`" placeholder="名称" maxlength="128" />
              <el-input v-model="column.key" :aria-label="`${side === 'inputs' ? '输入' : '输出'}列 ${index + 1} 变量名`" placeholder="变量名" maxlength="128" />
              <el-select v-model="column.type" :aria-label="`列 ${index + 1} 类型`"><el-option v-for="type in types" :key="type.value" v-bind="type" /></el-select>
              <el-button link type="danger" :disabled="form[side].length === 1" :aria-label="`删除${side === 'inputs' ? '输入' : '输出'}列 ${index + 1}`" @click="removeColumn(side, index)">删除</el-button>
            </div>
          </div>
        </div>
      </section>
      <section class="decision-editor__section">
        <div class="decision-editor__section-heading"><h3><span>02</span> 编写规则</h3><el-button :disabled="form.rules.length >= 100" @click="addRule">添加规则</el-button></div>
        <el-form-item label="命中策略">
          <el-radio-group v-model="form.hitPolicy" @change="form.useDefault = false"><el-radio-button value="UNIQUE">仅允许命中一条</el-radio-button><el-radio-button value="FIRST">按顺序取首条</el-radio-button></el-radio-group>
        </el-form-item>
        <p class="decision-editor__hint">同一行的条件须全部满足。文本和布尔值支持相等、不等；数值还支持大小比较。“任意”表示不限制此输入。</p>
        <el-table :data="form.rules" border :row-class-name="() => 'decision-editor__rule'">
          <el-table-column label="#" width="52"><template #default="{ $index }">{{ $index + 1 }}</template></el-table-column>
          <el-table-column v-for="(column, index) in form.inputs" :key="`in-${index}`" :label="column.label || '输入条件'" min-width="235">
            <template #default="{ row, $index }">
              <div class="decision-editor__condition">
                <el-select v-model="row.conditions[index].operator" :aria-label="`规则 ${$index + 1} ${column.label} 条件`">
                  <el-option v-for="operator in operatorsFor(column.type)" :key="operator.value" v-bind="operator" />
                </el-select>
                <el-input v-if="row.conditions[index].operator !== 'ANY'" v-model="row.conditions[index].value" :aria-label="`规则 ${$index + 1} ${column.label} 值`" :placeholder="valueHint(column.type)" />
              </div>
            </template>
          </el-table-column>
          <el-table-column v-for="(column, index) in form.outputs" :key="`out-${index}`" :label="`${column.label || '输出'} →`" min-width="170" class-name="decision-editor__output">
            <template #default="{ row, $index }"><el-input v-model="row.results[index]" :aria-label="`规则 ${$index + 1} ${column.label} 结果`" :placeholder="valueHint(column.type)" /></template>
          </el-table-column>
          <el-table-column label="顺序 / 删除" width="150" fixed="right"><template #default="{ $index }">
            <el-button link :disabled="$index === 0" :aria-label="`上移规则 ${$index + 1}`" @click="moveRule($index, -1)">↑</el-button>
            <el-button link :disabled="$index === form.rules.length - 1" :aria-label="`下移规则 ${$index + 1}`" @click="moveRule($index, 1)">↓</el-button>
            <el-button link type="danger" :disabled="form.rules.length === 1" @click="form.rules.splice($index, 1)">删除</el-button>
          </template></el-table-column>
        </el-table>
        <div v-if="form.hitPolicy === 'FIRST'" class="decision-editor__default">
          <el-checkbox v-model="form.useDefault">没有命中时返回默认结果</el-checkbox>
          <el-input v-for="(column, index) in form.useDefault ? form.outputs : []" :key="index" v-model="form.defaultResult[index]" :aria-label="`${column.label} 默认结果`" :placeholder="`${column.label}：${valueHint(column.type)}`" />
        </div>
      </section>
      <section class="decision-editor__section">
        <h3><span>03</span> 试算</h3>
        <div class="decision-editor__trial">
          <el-form-item v-for="(column, index) in form.inputs" :key="index" :label="column.label"><el-input v-model="testValues[index]" :aria-label="`试算 ${column.label}`" :placeholder="valueHint(column.type)" /></el-form-item>
          <el-button :loading="evaluating" @click="evaluate">运行试算</el-button>
        </div>
        <el-alert v-if="error" type="error" :title="error" :closable="false" />
        <div v-if="result !== null" class="decision-editor__result" aria-live="polite">
          <strong>{{ result.length ? '命中结果' : '未命中任何规则' }}</strong>
          <span v-if="result.length">{{ result[0] }}</span><span v-else>调整条件，或在“按顺序取首条”策略下配置默认结果。</span>
        </div>
      </section>
    </el-form>
    <footer class="decision-editor__footer"><p>发布后生成新决策版本；既有流程继续使用部署时冻结的版本。</p><el-button type="primary" :loading="publishing" :disabled="evaluating" @click="publish">发布决策表</el-button></footer>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { evaluateDecisionTable, publishDecisionTable } from '@/api/workflow/dmn'

const emit = defineEmits(['published'])
const types = [{ label: '文本', value: 'string' }, { label: '数值', value: 'number' }, { label: '布尔值', value: 'boolean' }]
const operators = [{ label: '等于', value: 'EQ' }, { label: '不等于', value: 'NE' }, { label: '小于', value: 'LT' }, { label: '不大于', value: 'LE' }, { label: '大于', value: 'GT' }, { label: '不小于', value: 'GE' }, { label: '任意', value: 'ANY' }]
const form = reactive({ key: '', name: '', category: '', hitPolicy: 'UNIQUE',
  inputs: [{ key: 'amount', label: '金额', type: 'number' }], outputs: [{ key: 'approvalLevel', label: '审批级别', type: 'string' }],
  rules: [{ conditions: [{ operator: 'LE', value: '1000' }], results: ['部门审批'] }, { conditions: [{ operator: 'GT', value: '1000' }], results: ['经理审批'] }],
  useDefault: false, defaultResult: [''] })
const testValues = ref(['500'])
const result = ref(null)
const error = ref('')
const evaluating = ref(false)
const publishing = ref(false)
const busy = computed(() => evaluating.value || publishing.value)
watch([form, testValues], () => { result.value = null; error.value = '' }, { deep: true })

function operatorsFor(type) { return operators.filter(operator => type === 'number' || ['EQ', 'NE', 'ANY'].includes(operator.value)) }
function valueHint(type) { return type === 'number' ? '数值' : type === 'boolean' ? 'true 或 false' : '普通文本' }
function addColumn(side) {
  form[side].push({ key: '', label: '', type: 'string' })
  form.rules.forEach(rule => side === 'inputs' ? rule.conditions.push({ operator: 'ANY', value: '' }) : rule.results.push(''))
  if (side === 'inputs') testValues.value.push('')
  else form.defaultResult.push('')
}
function removeColumn(side, index) {
  form[side].splice(index, 1)
  form.rules.forEach(rule => (side === 'inputs' ? rule.conditions : rule.results).splice(index, 1))
  if (side === 'inputs') testValues.value.splice(index, 1)
  else form.defaultResult.splice(index, 1)
}
function addRule() { form.rules.push({ conditions: form.inputs.map(() => ({ operator: 'ANY', value: '' })), results: form.outputs.map(() => '') }) }
function moveRule(index, offset) { const [rule] = form.rules.splice(index, 1); form.rules.splice(index + offset, 0, rule) }
function typedValue(column, value) {
  if (column.type === 'number') {
    if (!String(value).trim() || !Number.isFinite(Number(value))) throw new Error(`${column.label}请填写有效数值`)
    return Number(value)
  }
  if (column.type === 'boolean') {
    if (!['true', 'false'].includes(String(value))) throw new Error(`${column.label}请填写 true 或 false`)
    return value === 'true'
  }
  return String(value ?? '')
}
function request(includeTestValues) {
  return { key: form.key, name: form.name, category: form.category, hitPolicy: form.hitPolicy,
    inputs: form.inputs, outputs: form.outputs,
    rules: form.rules.map(rule => ({ conditions: rule.conditions.map((condition, i) => ({ operator: condition.operator,
      value: condition.operator === 'ANY' ? null : typedValue(form.inputs[i], condition.value) })),
    results: rule.results.map((value, i) => typedValue(form.outputs[i], value)) })),
    defaultResult: form.useDefault ? form.defaultResult.map((value, i) => typedValue(form.outputs[i], value)) : null,
    testValues: includeTestValues ? Object.fromEntries(form.inputs.map((column, i) => [column.key, typedValue(column, testValues.value[i])])) : null }
}
async function evaluate() {
  evaluating.value = true; error.value = ''; result.value = null
  try { result.value = (await evaluateDecisionTable(request(true))).data }
  catch (failure) { error.value = failure.message || '试算失败，请检查规则和输入' }
  finally { evaluating.value = false }
}
async function publish() {
  publishing.value = true; error.value = ''
  try { const response = await publishDecisionTable(request(false)); emit('published', response.data) }
  catch (failure) { error.value = failure.message || '发布失败，请检查决策表' }
  finally { publishing.value = false }
}
</script>

<style scoped>
.decision-editor { --decision-ink: #153e45; color: var(--el-text-color-primary); }
.decision-editor__heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 28px; }
.decision-editor__eyebrow { color: var(--decision-ink); font-size: 12px; letter-spacing: 2px; }
.decision-editor h2 { margin: 8px 0; font-size: 25px; font-weight: 650; }
.decision-editor p { color: var(--el-text-color-secondary); margin: 7px 0; line-height: 1.6; }
.decision-editor__identity { display: grid; grid-template-columns: 2fr 2fr 1fr; gap: 18px; }
.decision-editor__section { padding: 22px 0; border-top: 1px solid var(--el-border-color-light); }
.decision-editor h3 { margin: 0 0 18px; font-size: 16px; }
.decision-editor h3 span { color: #739397; font-size: 12px; margin-right: 12px; font-variant-numeric: tabular-nums; }
.decision-editor__columns { display: grid; grid-template-columns: 1fr 1fr; gap: 28px; }
.decision-editor__section-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.decision-editor__section-heading h3 { margin: 0; }
.decision-editor__column { display: grid; grid-template-columns: 1fr 1fr 104px 35px; gap: 8px; margin-top: 10px; }
.decision-editor__hint { font-size: 13px; padding-bottom: 12px; }
.decision-editor__condition { display: flex; gap: 8px; }
.decision-editor__condition .el-select { width: 100px; flex-shrink: 0; }
.decision-editor :deep(.decision-editor__output) { background: #f3f8f7; }
.decision-editor__trial { display: flex; flex-wrap: wrap; align-items: center; gap: 16px; }
.decision-editor__trial .el-form-item { width: 180px; }
.decision-editor__default { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 15px; }
.decision-editor__default .el-input { max-width: 180px; }
.decision-editor__result { padding: 18px 20px; border-left: 3px solid #38766c; background: #f3f8f7; display: flex; gap: 24px; margin-top: 10px; }
.decision-editor__footer { display: flex; align-items: center; justify-content: space-between; gap: 20px; border-top: 1px solid var(--el-border-color); padding-top: 18px; }
@media (max-width: 850px) { .decision-editor__identity, .decision-editor__columns { grid-template-columns: 1fr; } .decision-editor__footer { align-items: flex-start; flex-direction: column; } }
</style>
