import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'

const modelApiSource = readFileSync(
  new URL('../../src/api/workflow/model.js', import.meta.url), 'utf8')
const processDesignerSource = readFileSync(
  new URL('../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')
const callActivityReferenceServiceSource = readFileSync(
  new URL('../../../ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowCallActivityReferenceService.java', import.meta.url), 'utf8')

const AUTHOR_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:test">
  <process id="parent" isExecutable="true">
    <callActivity id="Call_Child" name="调用已发布子流程" calledElement="childKey"
      flowable:calledElementType="key" flowable:businessKey="child-expense-key"
      flowable:inheritBusinessKey="true" flowable:inheritVariables="true"
      flowable:useLocalScopeForOutParameters="true"
      flowable:processInstanceName="费用复核子流程">
      <extensionElements>
        <flowable:in source="amount" target="requestAmount" />
        <flowable:out source="reviewResult" target="childResult" />
      </extensionElements>
    </callActivity>
  </process>
</definitions>`

/**
 * 通过真实 bpmn-moddle 解析和再序列化调用活动，验证设计器配置不是源码字符串假契约。
 * @returns {Promise<void>} 原生 in/out、版本和变量策略任一丢失时测试失败。
 */
test('CallActivity 标准引用、原生映射和版本策略可完整往返', async () => {
  const callActivityType = flowableModdle.types.find(type => type.name === 'CallActivity')
  assert.ok(callActivityType)
  assert.equal(callActivityType.properties.some(property => property.name === 'calledElement'), false)

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const { rootElement, warnings } = await moddle.fromXML(AUTHOR_XML)
  assert.deepEqual(warnings, [])
  const callActivity = rootElement.rootElements[0].flowElements[0]
  assert.equal(callActivity.$type, 'bpmn:CallActivity')
  assert.equal(callActivity.calledElement, 'childKey')
  assert.equal(callActivity.get('flowable:calledElementType'), 'key')
  assert.equal(callActivity.get('flowable:businessKey'), 'child-expense-key')
  assert.equal(callActivity.get('flowable:processInstanceName'), '费用复核子流程')
  assert.equal(callActivity.get('flowable:inheritBusinessKey'), true)
  assert.equal(callActivity.get('flowable:inheritVariables'), true)
  assert.equal(callActivity.get('flowable:useLocalScopeForOutParameters'), true)
  assert.deepEqual(
    callActivity.extensionElements.values.map(value => ({
      type: value.$type,
      source: value.source,
      target: value.target
    })),
    [
      { type: 'flowable:In', source: 'amount', target: 'requestAmount' },
      { type: 'flowable:Out', source: 'reviewResult', target: 'childResult' }
    ]
  )

  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /<(?:bpmn:)?callActivity[^>]*calledElement="childKey"/)
  assert.match(xml, /<(?:bpmn:)?callActivity[^>]*flowable:calledElementType="key"/)
  assert.match(xml, /<(?:bpmn:)?callActivity[^>]*flowable:businessKey="child-expense-key"/)
  assert.match(xml, /<(?:bpmn:)?callActivity[^>]*flowable:processInstanceName="费用复核子流程"/)
  assert.match(xml, /<(?:bpmn:)?callActivity[^>]*flowable:inheritBusinessKey="true"/)
  assert.match(xml, /<(?:bpmn:)?callActivity[^>]*flowable:inheritVariables="true"/)
  assert.match(xml, /<(?:bpmn:)?callActivity[^>]*flowable:useLocalScopeForOutParameters="true"/)
  assert.match(xml, /<flowable:in source="amount" target="requestAmount"\s*\/>/)
  assert.match(xml, /<flowable:out source="reviewResult" target="childResult"\s*\/>/)
  const reopened = await moddle.fromXML(xml)
  const reopenedCall = reopened.rootElement.rootElements[0].flowElements[0]
  assert.equal(reopenedCall.calledElement, 'childKey')
  assert.equal(reopenedCall.get('flowable:calledElementType'), 'key')
  assert.equal(reopenedCall.get('flowable:businessKey'), 'child-expense-key')
  assert.equal(reopenedCall.get('flowable:processInstanceName'), '费用复核子流程')
  assert.equal(reopenedCall.get('flowable:inheritBusinessKey'), true)
  assert.equal(reopenedCall.get('flowable:inheritVariables'), true)
  assert.equal(reopenedCall.get('flowable:useLocalScopeForOutParameters'), true)
  assert.equal(reopenedCall.extensionElements.values[0].$type, 'flowable:In')
  assert.equal(reopenedCall.extensionElements.values[1].$type, 'flowable:Out')
  assert.match(processDesignerSource, /propertyState\.calledElement\s*=\s*businessObject\.calledElement/)
  assert.doesNotMatch(processDesignerSource, /get\(['"]flowable:calledElement['"]\)/)
})

/**
 * 验证设计器只从服务端授权目录查询子流程。
 * @returns {void} 回退到模型子路由或客户端定义入口时测试失败。
 */
test('CallActivity 使用独立授权 catalog API', () => {
  assert.match(modelApiSource, /url:\s*['"]\/workflow\/call-activity\/catalog['"]/)
  assert.doesNotMatch(modelApiSource, /\/workflow\/model\/callActivity\/options/)
})

/**
 * 验证 moddle 保真的 businessKey 仍由后端平台策略在部署前拒绝。
 * @returns {void} 客户端保真被误当作平台允许或后端门禁丢失时测试失败。
 */
test('CallActivity businessKey 保真但平台部署策略拒绝', () => {
  assert.match(callActivityReferenceServiceSource,
    /if \(hasText\(callActivity\.getBusinessKey\(\)\)\)[\s\S]*?new ServiceException\("调用活动业务键只能选择继承父流程或不设置", HttpStatus\.BAD_REQUEST\)/)
})

/**
 * 验证共享设计器命令在编辑多实例时保留兼容的平台作者属性并原子写入指定身份属性。
 * @returns {void} 兼容属性可能被删除或指定身份未随循环原子更新时测试失败。
 */
test('共享设计器编辑保留兼容的平台属性集合', () => {
  assert.match(processDesignerSource,
    /preservedPlatformProperties = readAllFlowableProperties[\s\S]*?SLA_PROPERTY_NAME_SET\.has\(item\.name\)[\s\S]*?AUTO_COPY_PROPERTY_NAME/)
  assert.match(processDesignerSource,
    /editableWithPlatformProperties = multiInstanceAuthorProperties\([\s\S]*?\.\.\.editableProperties[\s\S]*?\.\.\.preservedPlatformProperties[\s\S]*?PARTICIPANT_RULE_PROPERTY_NAMES/)
  assert.match(processDesignerSource,
    /multiInstanceExtensions = buildPropertiesExtensionElements\([\s\S]*?editableWithPlatformProperties,[\s\S]*?configuredIdentity \? configuredMultiInstancePropertyItems\(configuredIdentity\) : \[\]\)/)
})
