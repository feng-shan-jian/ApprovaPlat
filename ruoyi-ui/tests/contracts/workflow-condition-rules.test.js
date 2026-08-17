import assert from 'node:assert/strict'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'
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
