import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'
import { multiInstanceAuthorProperties } from '../../src/components/workflow/designer/multiInstancePropertyContract.js'

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

const TASK_RULE_PROPERTY_NAMES = new Set([
  'approva.assignment.ruleVersion', 'approva.assignment.type',
  'approva.assignment.targetIds', 'approva.assignment.formField',
  'approva.assignment.noMatchPolicy'
])

const SINGLE_INSTANCE_AUTHOR_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:multi-instance-contract">
  <process id="participantContract" isExecutable="true">
    <userTask id="review" name="审批">
      <extensionElements>
        <flowable:properties>
          <flowable:property name="approva.assignment.ruleVersion" value="1" />
          <flowable:property name="approva.assignment.type" value="FIXED_USER" />
          <flowable:property name="approva.assignment.targetIds" value="11" />
          <flowable:property name="approva.assignment.formField" value="" />
          <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL" />
          <flowable:property name="approva.sla.enabled" value="false" />
          <flowable:property name="approva.autoCopyRules" value="{&quot;version&quot;:1,&quot;rules&quot;:[]}" />
          <flowable:property name="tenant.audit.tag" value="finance" />
        </flowable:properties>
      </extensionElements>
    </userTask>
  </process>
</definitions>`

const EXISTING_STATIC_AUTHOR_XML = SINGLE_INSTANCE_AUTHOR_XML.replace(
  '</extensionElements>\n    </userTask>',
  `</extensionElements>
      <multiInstanceLoopCharacteristics isSequential="true"
        flowable:collection="legacyReviewers" flowable:elementVariable="reviewer" />
    </userTask>`
)

/**
 * 读取用户任务 Flowable Property，供真实 moddle 往返后核对属性集合。
 * @param {object} task bpmn:UserTask 业务对象。
 * @returns {Array<{name:string,value:string}>} 保持 XML 顺序的属性名值列表。
 */
function readTaskProperties(task) {
  return (task.extensionElements?.values || [])
    .filter(value => value?.$type === 'flowable:Properties')
    .flatMap(container => (container.values || []).map(property => ({
      name: String(property.name || ''),
      value: String(property.value ?? '')
    })))
}

/**
 * 应用与设计器 multiInstanceExtensions 相同的属性命令载荷，保留非 Properties 扩展。
 * @param {BpmnModdle} moddle 当前真实 BPMN moddle。
 * @param {object} task bpmn:UserTask 业务对象。
 * @returns {void} 任务扩展元素被替换为不含单实例规则的完整集合。
 */
function applyMultiInstanceExtensionCommand(moddle, task) {
  const retained = multiInstanceAuthorProperties(readTaskProperties(task), TASK_RULE_PROPERTY_NAMES)
  const preserved = (task.extensionElements?.values || [])
    .filter(value => value?.$type !== 'flowable:Properties')
  const properties = moddle.create('flowable:Properties', {
    values: retained.map(property => moddle.create('flowable:Property', property))
  })
  task.extensionElements = moddle.create('bpmn:ExtensionElements', {
    values: [...preserved, properties]
  })
}

/**
 * 创建或原位更新多实例循环，覆盖 ANY、ALL、串行、并行和已有静态循环分支。
 * @param {BpmnModdle} moddle 当前真实 BPMN moddle。
 * @param {object} task bpmn:UserTask 业务对象。
 * @param {'any'|'all'|'sequential'|'parallel'|'existing'} mode 待模拟的设计器命令分支。
 * @returns {void} 任务获得目标循环配置，已有静态循环保持同一 moddle 对象。
 */
function applyMultiInstanceLoopCommand(moddle, task, mode) {
  const existing = task.loopCharacteristics
  if (mode === 'existing') {
    existing.isSequential = false
    existing.set('flowable:collection', 'updatedReviewers')
    return
  }
  const controlled = mode === 'any' || mode === 'all'
  const condition = controlled
    ? mode === 'any' ? '${nrOfCompletedInstances > 0}' : '${nrOfCompletedInstances == nrOfInstances}'
    : ''
  const loop = moddle.create('bpmn:MultiInstanceLoopCharacteristics', {
    isSequential: mode === 'sequential',
    completionCondition: condition
      ? moddle.create('bpmn:FormalExpression', { body: condition })
      : undefined
  })
  loop.set('flowable:collection', controlled
    ? '${multiInstanceHandler.getUserIds(execution)}'
    : 'reviewers')
  loop.set('flowable:elementVariable', controlled ? 'assignee' : 'reviewer')
  task.loopCharacteristics = loop
}

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
  assert.doesNotMatch(editorSource, /multiInstanceHandler|fixedMultiInstanceUserIds/)
})

/**
 * 验证单实例办理人规则在所有多实例命令分支中被永久清理，其他平台属性完整保留。
 * @returns {Promise<void>} 任一模式重开或切回单实例后恢复旧规则时测试失败。
 */
test('多实例命令往返不会恢复旧单实例参与者规则', async () => {
  assert.match(designerSource,
    /updateExistingStaticMultiInstance\([\s\S]*?multiInstanceExtensions\)/)
  assert.match(designerSource,
    /const changes = \{ loopCharacteristics: loop, extensionElements: multiInstanceExtensions \}/)

  const scenarios = [
    { mode: 'any', xml: SINGLE_INSTANCE_AUTHOR_XML },
    { mode: 'all', xml: SINGLE_INSTANCE_AUTHOR_XML },
    { mode: 'sequential', xml: SINGLE_INSTANCE_AUTHOR_XML },
    { mode: 'parallel', xml: SINGLE_INSTANCE_AUTHOR_XML },
    { mode: 'existing', xml: EXISTING_STATIC_AUTHOR_XML }
  ]
  for (const scenario of scenarios) {
    const moddle = new BpmnModdle({ flowable: flowableModdle })
    const parsed = await moddle.fromXML(scenario.xml)
    assert.deepEqual(parsed.warnings, [], `${scenario.mode} 作者 XML 应无解析告警`)
    const process = parsed.rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
    const task = process.flowElements.find(element => element.$type === 'bpmn:UserTask')
    const originalLoop = task.loopCharacteristics

    applyMultiInstanceExtensionCommand(moddle, task)
    applyMultiInstanceLoopCommand(moddle, task, scenario.mode)
    if (scenario.mode === 'existing') assert.equal(task.loopCharacteristics, originalLoop)

    const serialized = await moddle.toXML(parsed.rootElement, { format: true })
    const reopened = await moddle.fromXML(serialized.xml)
    assert.deepEqual(reopened.warnings, [], `${scenario.mode} 重开 XML 应无解析告警`)
    const reopenedTask = reopened.rootElement.rootElements
      .find(element => element.$type === 'bpmn:Process').flowElements
      .find(element => element.$type === 'bpmn:UserTask')
    const names = readTaskProperties(reopenedTask).map(property => property.name)
    assert.equal(names.some(name => TASK_RULE_PROPERTY_NAMES.has(name)), false)
    assert.deepEqual(names, [
      'approva.sla.enabled', 'approva.autoCopyRules', 'tenant.audit.tag'
    ])
    assert.ok(reopenedTask.loopCharacteristics)
    assert.equal(reopenedTask.loopCharacteristics.$type, 'bpmn:MultiInstanceLoopCharacteristics')
    if (scenario.mode === 'any') {
      assert.equal(reopenedTask.loopCharacteristics.completionCondition.body,
        '${nrOfCompletedInstances > 0}')
    }
    if (scenario.mode === 'all') {
      assert.equal(reopenedTask.loopCharacteristics.completionCondition.body,
        '${nrOfCompletedInstances == nrOfInstances}')
    }
    if (scenario.mode === 'sequential') assert.equal(reopenedTask.loopCharacteristics.isSequential, true)
    if (scenario.mode === 'parallel' || scenario.mode === 'existing') {
      assert.equal(reopenedTask.loopCharacteristics.isSequential, false)
    }

    // 切回单实例只移除循环，不重新注入已清理的办理人属性。
    reopenedTask.loopCharacteristics = undefined
    const singleAgain = await moddle.toXML(reopened.rootElement, { format: true })
    const reopenedSingle = await moddle.fromXML(singleAgain.xml)
    const reopenedSingleTask = reopenedSingle.rootElement.rootElements
      .find(element => element.$type === 'bpmn:Process').flowElements
      .find(element => element.$type === 'bpmn:UserTask')
    assert.equal(readTaskProperties(reopenedSingleTask)
      .some(property => TASK_RULE_PROPERTY_NAMES.has(property.name)), false)
  }
})
