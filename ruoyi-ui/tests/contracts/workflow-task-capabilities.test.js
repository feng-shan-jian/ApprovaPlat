import assert from 'node:assert/strict'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'
import {
  TASK_PANEL_TYPES,
  getTaskCapability,
  taskCapabilityMap
} from '../../src/components/workflow/designer/taskCapabilityMap.js'
import { filterTaskReplaceEntries } from '../../src/components/workflow/designer/taskCapabilityReplaceMenu.js'

const HISTORICAL_TASK_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:task-capability">
  <process id="historicalTasks" isExecutable="true">
    <serviceTask id="service" flowable:delegateExpression="\${workflowExtensionDelegate}" />
    <sendTask id="send" flowable:delegateExpression="\${workflowExtensionDelegate}" />
    <manualTask id="manual" name="历史手工记录" />
  </process>
</definitions>`

/**
 * 验证所有平台任务只通过不可变能力表表达创建、转换、面板和运行语义。
 * @returns {void} 字段扩张、任务遗漏或 ManualTask 新建能力回归时测试失败。
 */
test('任务能力表不可变且四类业务任务使用独立面板', () => {
  assert.equal(Object.isFrozen(taskCapabilityMap), true)
  assert.deepEqual(Object.keys(taskCapabilityMap), [
    'bpmn:UserTask',
    'bpmn:ServiceTask',
    'bpmn:SendTask',
    'bpmn:ReceiveTask',
    'bpmn:BusinessRuleTask',
    'bpmn:ManualTask'
  ])
  for (const capability of Object.values(taskCapabilityMap)) {
    assert.equal(Object.isFrozen(capability), true)
    assert.deepEqual(Object.keys(capability), [
      'taskType',
      'creationAllowed',
      'conversionAllowed',
      'panelType',
      'runtimeSemantics'
    ])
  }

  assert.equal(getTaskCapability('bpmn:ServiceTask').panelType, TASK_PANEL_TYPES.SERVICE)
  assert.equal(getTaskCapability('bpmn:SendTask').panelType, TASK_PANEL_TYPES.SEND)
  assert.equal(getTaskCapability('bpmn:ReceiveTask').panelType, TASK_PANEL_TYPES.RECEIVE)
  assert.equal(getTaskCapability('bpmn:BusinessRuleTask').panelType, TASK_PANEL_TYPES.BUSINESS_RULE)
  assert.equal(getTaskCapability('bpmn:ManualTask').creationAllowed, false)
  assert.equal(getTaskCapability('bpmn:ManualTask').conversionAllowed, false)
})

/**
 * 验证单一能力表禁止创建 ManualTask，且 bpmn-js 转换菜单按该能力过滤。
 * @returns {void} ManualTask 创建或转换能力重新开放时测试失败。
 */
test('ManualTask 单一能力事实禁止创建并从转换目标过滤', () => {
  assert.equal(getTaskCapability('bpmn:ManualTask').creationAllowed, false)
  const entries = {
    'replace-with-service-task': { label: 'Service Task' },
    'replace-with-manual-task': { label: 'Manual Task' },
    'replace-with-rule-task': { label: 'Business Rule Task' }
  }
  const filtered = filterTaskReplaceEntries(entries)
  assert.equal(filtered['replace-with-manual-task'], undefined)
  assert.ok(filtered['replace-with-service-task'])
  assert.ok(entries['replace-with-manual-task'], '过滤函数不能修改 bpmn-js 默认条目对象')
})

/**
 * 使用真实 bpmn-moddle 验证 ServiceTask、SendTask 独立扩展声明和历史 ManualTask 可往返。
 * @returns {Promise<void>} 历史导入、显示所需类型或序列化内容丢失时测试失败。
 */
test('历史 ManualTask 与两类受控任务可真实解析并原样重载', async () => {
  assert.equal(flowableModdle.types.some(type => type.name === 'ServiceTaskLike'), false)
  assert.deepEqual(
    flowableModdle.types.find(type => type.name === 'ServiceTaskImplementation').extends,
    ['bpmn:ServiceTask']
  )
  assert.deepEqual(
    flowableModdle.types.find(type => type.name === 'SendTaskImplementation').extends,
    ['bpmn:SendTask']
  )

  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const parsed = await moddle.fromXML(HISTORICAL_TASK_XML)
  assert.deepEqual(parsed.warnings, [])
  const flowElements = parsed.rootElement.rootElements[0].flowElements
  assert.equal(flowElements.find(element => element.id === 'service').$type, 'bpmn:ServiceTask')
  assert.equal(flowElements.find(element => element.id === 'send').$type, 'bpmn:SendTask')
  assert.equal(flowElements.find(element => element.id === 'manual').$type, 'bpmn:ManualTask')
  assert.equal(flowElements.find(element => element.id === 'service').get('flowable:delegateExpression'), '${workflowExtensionDelegate}')
  assert.equal(flowElements.find(element => element.id === 'send').get('flowable:delegateExpression'), '${workflowExtensionDelegate}')

  const { xml } = await moddle.toXML(parsed.rootElement, { format: true })
  assert.match(xml, /<(?:bpmn:)?manualTask id="manual" name="历史手工记录"\s*\/>/)
  const reopened = await moddle.fromXML(xml)
  assert.equal(
    reopened.rootElement.rootElements[0].flowElements.find(element => element.id === 'manual').$type,
    'bpmn:ManualTask'
  )
})
