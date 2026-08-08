import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const editorSource = readFileSync(new URL(
  '../../src/components/workflow/designer/ParticipantRuleEditor.vue', import.meta.url), 'utf8')
const designerSource = readFileSync(new URL(
  '../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')
const panelSource = readFileSync(new URL(
  '../../src/components/workflow/designer/DesignerPropertiesPanel.vue', import.meta.url), 'utf8')
const designPageSource = readFileSync(new URL(
  '../../src/views/workflow/model/design.vue', import.meta.url), 'utf8')
const identityApiSource = readFileSync(new URL(
  '../../src/api/workflow/identity.js', import.meta.url), 'utf8')

/**
 * 验证流程级四类发起范围和单实例八类办理规则均由受控选项提供。
 * @returns {void} 规则类型缺失或漂移时断言失败。
 */
test('参与者规则编辑器冻结四类发起范围和八类单实例规则', () => {
  for (const type of ['PUBLIC', 'USERS', 'ROLES', 'DEPTS']) {
    assert.match(editorSource, new RegExp(`value: '${type}'`))
  }
  for (const type of ['FIXED_USER', 'CANDIDATE_USERS', 'CANDIDATE_GROUPS', 'STARTER',
    'STARTER_MANAGER', 'DEPT_MANAGER', 'STARTER_DEPT_ROLE', 'FORM_USER']) {
    assert.match(editorSource, new RegExp(`value: '${type}'`))
  }
  assert.match(panelSource,
    /v-if="state\.multiInstanceType === 'none'"[\s\S]*?mode="task"/)
  assert.match(designerSource,
    /propertyState\.multiInstanceType !== 'none'[\s\S]*?return/)
})

/**
 * 验证页面只允许目录和正式表单字段选择，并明确展示最终命中和固定失败策略。
 * @returns {void} 出现自由 ID、表达式输入或策略弱化时断言失败。
 */
test('参与者规则不提供裸主键和表达式输入并固定无匹配失败', () => {
  assert.doesNotMatch(editorSource, /<el-input|allow-create|userId|group.*code|expression/i)
  assert.match(editorSource, /filterable[\s\S]*?remote[\s\S]*?:remote-method="searchTargets"/)
  assert.match(editorSource, /最终命中/)
  assert.match(editorSource, /无匹配：阻止流转并记录审计/)
  assert.match(designerSource, /startNoMatch: 'approva\.startScope\.noMatchPolicy'/)
  assert.match(designerSource, /taskNoMatch: 'approva\.assignment\.noMatchPolicy'/)
  assert.match(designerSource, /value: 'FAIL'/)
})

/**
 * 验证需要目标的规则可先切换类型再选择正式目录，且不完整编辑态不会绕过保存门禁。
 * @returns {void} 分步编辑被回滚或最终完整性校验缺失时断言失败。
 */
test('参与者规则支持分步选择且保存前仍强制完整', () => {
  assert.match(designerSource,
    /normalizeParticipantRule\(rule, processRule, true\)/)
  assert.match(designerSource,
    /function normalizeParticipantRule\(rule, processRule, allowIncomplete = false\)/)
  assert.match(designerSource,
    /validateParticipantProperties\(process\.businessObject, true\)/)
  assert.match(designerSource,
    /validateParticipantProperties\(task, false\)/)
})

/**
 * 验证远程分页外的已选对象先隐藏裸值，再通过正式 API 回显实时资格。
 * @returns {void} 回显链路缺失或页面可能显示裸值时断言失败。
 */
test('已选身份通过正式批量接口回显且不显示裸值', () => {
  assert.match(editorSource, /label: '正在核验已选对象'/)
  assert.match(editorSource, /emit\('identity-resolve', \{ target: pool, values: missingValues \}\)/)
  assert.match(identityApiSource,
    /function resolveIdentityOptions\(data\)[\s\S]*?\/workflow\/identity\/options\/resolve[\s\S]*?method: 'post'/)
  assert.match(designPageSource,
    /async function resolveSelectedIdentities\(request\)[\s\S]*?resolveIdentityOptions/)
  assert.match(designPageSource,
    /candidateGroups[\s\S]*?roleValues[\s\S]*?deptValues[\s\S]*?Promise\.all/)
})

/**
 * 验证作者属性包含完整版本字段，且切换多实例时会与本任务规则隔离。
 * @returns {void} 保存重开字段或多实例隔离漂移时断言失败。
 */
test('参与者作者属性完整持久化并与多实例成员来源隔离', () => {
  for (const property of [
    'approva.startScope.ruleVersion', 'approva.startScope.type',
    'approva.startScope.targetIds', 'approva.startScope.noMatchPolicy',
    'approva.assignment.ruleVersion', 'approva.assignment.type',
    'approva.assignment.targetIds', 'approva.assignment.formField',
    'approva.assignment.noMatchPolicy'
  ]) assert.match(designerSource, new RegExp(property.replaceAll('.', '\\.')))
  assert.match(designerSource,
    /participantProperties = readAllFlowableProperties[\s\S]*?PARTICIPANT_RULE_PROPERTY_NAMES/)
  assert.match(designerSource,
    /multiInstanceExtensions = buildPropertiesExtensionElements\([\s\S]*?editableWithSla, \[\]\)/)
  assert.doesNotMatch(editorSource, /multiInstanceHandler|fixedMultiInstanceUserIds/)
})
