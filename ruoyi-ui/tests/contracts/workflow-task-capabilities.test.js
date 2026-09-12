import assert from 'node:assert/strict'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'
import {
  getTaskCapability,
} from '../../src/components/workflow/designer/taskCapabilityMap.js'
import { filterTaskReplaceEntries } from '../../src/components/workflow/designer/taskCapabilityReplaceMenu.js'

const CONTROLLED_TASK_XML = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:approvaplat:task-capability">
  <process id="historicalTasks" isExecutable="true">
    <serviceTask id="service" flowable:delegateExpression="\${workflowExtensionDelegate}" />
    <sendTask id="send" flowable:delegateExpression="\${workflowExtensionDelegate}" />
  </process>
</definitions>`

/**
 * 验证单一能力表禁止创建 ManualTask，且 bpmn-js 转换菜单按该能力过滤。
 * @returns {void} ManualTask 创建或转换能力重新开放时测试失败。
 */
test('ManualTask 不属于正式能力协议并从转换目标过滤', () => {
  assert.equal(getTaskCapability('bpmn:ManualTask'), null)
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
 * 使用真实 bpmn-moddle 验证 ServiceTask、SendTask 独立扩展声明可往返。
 * @returns {Promise<void>} 受控任务类型或序列化内容丢失时测试失败。
 */
test('两类受控任务可真实解析并原样重载', async () => {
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const parsed = await moddle.fromXML(CONTROLLED_TASK_XML)
  assert.deepEqual(parsed.warnings, [])
  const flowElements = parsed.rootElement.rootElements[0].flowElements
  assert.equal(flowElements.find(element => element.id === 'service').$type, 'bpmn:ServiceTask')
  assert.equal(flowElements.find(element => element.id === 'send').$type, 'bpmn:SendTask')
  assert.equal(flowElements.find(element => element.id === 'service').get('flowable:delegateExpression'), '${workflowExtensionDelegate}')
  assert.equal(flowElements.find(element => element.id === 'send').get('flowable:delegateExpression'), '${workflowExtensionDelegate}')

  const { xml } = await moddle.toXML(parsed.rootElement, { format: true })
  const reopened = await moddle.fromXML(xml)
  assert.equal(reopened.rootElement.rootElements[0].flowElements.length, 2)
})
