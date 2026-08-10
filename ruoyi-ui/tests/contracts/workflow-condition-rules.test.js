import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'

const designerSource = readFileSync(
  new URL('../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')
const propertiesPanelSource = readFileSync(
  new URL('../../src/components/workflow/designer/DesignerPropertiesPanel.vue', import.meta.url), 'utf8')
const ruleEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/SequenceFlowRuleEditor.vue', import.meta.url), 'utf8')
const ruleEditorDoc = readFileSync(
  new URL('../../src/components/workflow/designer/SequenceFlowRuleEditor.md', import.meta.url), 'utf8')
const processStartSource = readFileSync(
  new URL('../../src/views/workflow/work/start.vue', import.meta.url), 'utf8')

/**
 * 验证普通设计者只能通过正式表单字段、受控运算符和值控件配置条件分支。
 * @returns {void} 出现任意表达式入口或缺少类型化规则控件时断言失败。
 */
test('条件分支编辑器不暴露任意 EL 并按字段类型提供受控规则', () => {
  assert.match(propertiesPanelSource, /<SequenceFlowRuleEditor[\s\S]*?:field-options="conditionFieldOptions"/)
  assert.doesNotMatch(propertiesPanelSource, /v-model="state\.conditionExpression"/)
  assert.doesNotMatch(ruleEditorSource, /conditionExpression|textarea|contenteditable|localStorage|sessionStorage/)
  assert.match(ruleEditorSource, /value: 'GT'[\s\S]*?value: 'GTE'[\s\S]*?value: 'LT'[\s\S]*?value: 'LTE'/)
  assert.match(ruleEditorSource, /value: 'CONTAINS'[\s\S]*?value: 'STARTS_WITH'[\s\S]*?value: 'ENDS_WITH'/)
  assert.match(ruleEditorSource, /<el-select[\s\S]*?v-if="selectedField\(rule\)\?\.valueRestricted"/)
  assert.match(ruleEditorSource, /<el-input-number[\s\S]*?v-else-if="selectedField\(rule\)\?\.type === 'NUMBER'"/)
  assert.match(ruleEditorSource, /field\?\.type === 'BOOLEAN'[\s\S]*?return true/)
  assert.match(ruleEditorDoc, /组件不生成或接收任意 EL/)
})

/**
 * 验证规则组、分支名称和唯一默认分支都写入 bpmn-js 的真实命令栈。
 * @returns {void} 配置只停留在组件本地状态或默认分支不唯一时断言失败。
 */
test('条件规则通过作者 BPMN 属性持久化并维护唯一默认分支', () => {
  assert.match(ruleEditorSource, /combinator: draft\.combinator[\s\S]*?groups: draft\.groups\.map/)
  assert.match(ruleEditorSource, /combinator: group\.combinator[\s\S]*?rules: group\.rules\.map/)
  assert.match(ruleEditorSource, /emit\('apply',[\s\S]*?name: branchName\.value\.trim\(\)[\s\S]*?version: 1/)
  assert.match(ruleEditorSource, /emit\('make-default'\)/)
  assert.match(designerSource, /CONDITION_RULE_PROPERTY = 'approva\.conditionRule\.config'/)
  assert.match(designerSource, /function persistConditionConfig\([\s\S]*?JSON\.stringify\(config\)[\s\S]*?conditionExpression: undefined/)
  assert.match(designerSource, /function makeConditionDefault\([\s\S]*?source\.default[\s\S]*?default: selectedBusinessObject\.value/)
  assert.match(designerSource, /oldDefaultElement[\s\S]*?persistConditionConfig\(oldDefaultElement[\s\S]*?persistConditionConfig\(selectedElement\.value/)
})

/**
 * 使用真实 bpmn-moddle 验证版本化规则、分支名称和网关默认引用可无损往返。
 * @returns {Promise<void>} 受控属性或默认引用在保存重开时丢失则断言失败。
 */
test('条件分支作者 XML 执行真实无表达式往返', async () => {
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const encodedRule = '{&quot;version&quot;:1,&quot;default&quot;:false,&quot;combinator&quot;:&quot;AND&quot;,&quot;groups&quot;:[{&quot;combinator&quot;:&quot;OR&quot;,&quot;rules&quot;:[{&quot;field&quot;:&quot;amount&quot;,&quot;operator&quot;:&quot;GTE&quot;,&quot;value&quot;:5000},{&quot;field&quot;:&quot;urgent&quot;,&quot;operator&quot;:&quot;EQ&quot;,&quot;value&quot;:true}]}]}'
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/condition-contract">
  <process id="conditionContract" isExecutable="true">
    <exclusiveGateway id="route" default="flowDefault" />
    <userTask id="review" />
    <userTask id="fallback" />
    <sequenceFlow id="flowRule" name="大额或紧急" sourceRef="route" targetRef="review">
      <extensionElements><flowable:properties><flowable:property name="approva.conditionRule.config" value="${encodedRule}" /></flowable:properties></extensionElements>
    </sequenceFlow>
    <sequenceFlow id="flowDefault" name="普通申请" sourceRef="route" targetRef="fallback">
      <extensionElements><flowable:properties><flowable:property name="approva.conditionRule.config" value="{&quot;version&quot;:1,&quot;default&quot;:true}" /></flowable:properties></extensionElements>
    </sequenceFlow>
  </process>
</definitions>`
  const { rootElement, warnings } = await moddle.fromXML(source)
  assert.deepEqual(warnings, [])
  const process = rootElement.rootElements.find(element => element.$type === 'bpmn:Process')
  const gateway = process.flowElements.find(element => element.id === 'route')
  const ruleFlow = process.flowElements.find(element => element.id === 'flowRule')
  const property = ruleFlow.extensionElements.values[0].values[0]
  assert.equal(gateway.default.id, 'flowDefault')
  assert.equal(ruleFlow.name, '大额或紧急')
  assert.deepEqual(JSON.parse(property.value).groups[0].rules.map(rule => rule.field), ['amount', 'urgent'])
  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /exclusiveGateway id="route" default="flowDefault"/)
  assert.match(xml, /approva\.conditionRule\.config/)
  assert.doesNotMatch(xml, /conditionExpression|\$\{|#\{/)
})

/**
 * 验证条件字段目录复用正式表单快照的稳定字段键和类型映射。
 * @returns {void} 使用自由变量输入或绕过正式表单字段目录时断言失败。
 */
test('条件字段目录来自正式模板或内嵌表单稳定字段键', () => {
  assert.match(designerSource, /function describeFormalFormFields\([\s\S]*?readEmbeddedFormFields\(businessObject\)/)
  assert.match(designerSource, /form\.content[\s\S]*?__vModel__[\s\S]*?workflowWritable !== false/)
  assert.match(designerSource, /string: 'TEXT'[\s\S]*?long: 'NUMBER'[\s\S]*?boolean: 'BOOLEAN'[\s\S]*?enum: 'SCALAR'/)
  assert.match(designerSource, /function resolveConditionFieldCatalog\([\s\S]*?fieldsByName[\s\S]*?conflicts/)
  assert.doesNotMatch(ruleEditorSource, /allow-create|remote-method|自定义变量|变量表达式/)
})

/**
 * 验证发起页在异步表单校验前建立提交互斥，防止快速双击创建重复实例。
 * @returns {void} 互斥标志设置晚于首个 await 或缺少 finally 复位时断言失败。
 */
test('条件流程发起入口在异步校验前阻止重复提交', () => {
  assert.match(processStartSource, /async function submitDraft\(\) \{[\s\S]*?writing\.value[\s\S]*?actionType\.value = 'submit'[\s\S]*?await formRendererRef\.value\.validate/)
  assert.match(processStartSource, /async function submitDraft\(\) \{[\s\S]*?await submitProcessDraft\([\s\S]*?finally \{[\s\S]*?actionType\.value = ''/)
})
