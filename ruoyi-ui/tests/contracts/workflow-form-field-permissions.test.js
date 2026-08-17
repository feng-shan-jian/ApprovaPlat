import assert from 'node:assert/strict'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'
import { flattenFormFields, normalizeFormTemplate } from '../../src/components/workflow/form/formTemplate.js'
/**
 * 验证正式模板节点权限使用 Flowable FormProperty 完成真实 XML 往返。
 * @returns {Promise<void>} 默认策略、字段变量或四态标志丢失时测试失败。
 */
test('正式模板节点字段权限完成 BPMN XML 往返', async () => {
  const moddle = new BpmnModdle({ flowable: flowableModdle })
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://approvaplat.example/field-permissions">
  <process id="permissionContract" isExecutable="true">
    <startEvent id="start" flowable:formKey="key_1">
      <extensionElements>
        <flowable:formProperty id="approva_permission_default" type="string" readable="true" writable="true" required="false" />
        <flowable:formProperty id="approva_permission_field_1" type="string" variable="secret" readable="false" writable="false" required="false" />
        <flowable:formProperty id="approva_permission_field_2" type="string" variable="amount" readable="true" writable="true" required="true" />
      </extensionElements>
    </startEvent>
  </process>
</definitions>`

  const { rootElement } = await moddle.fromXML(source)
  const start = rootElement.rootElements[0].flowElements[0]
  const properties = start.extensionElements.values
  assert.equal(start.get('flowable:formKey'), 'key_1')
  assert.equal(properties[0].id, 'approva_permission_default')
  assert.equal(properties[1].variable, 'secret')
  assert.equal(properties[1].readable, false)
  assert.equal(properties[2].required, true)

  const { xml } = await moddle.toXML(rootElement, { format: true })
  assert.match(xml, /approva_permission_default/)
  assert.match(xml, /variable="secret"[^>]+readable="false"[^>]+writable="false"/)
  assert.match(xml, /variable="amount"[^>]+required="true"/)
})

/**
 * 验证部署快照规范化后隐藏字段不可见、只读字段不可写、提交集合只包含可写字段。
 * @returns {void} 前端展示或提交契约退化时测试失败。
 */
test('运行表单执行隐藏只读与可写字段契约', () => {
  const template = normalizeFormTemplate({ fields: [
    { __vModel__: 'hidden', __config__: { layout: 'colFormItem', tag: 'el-input', workflowHidden: true, workflowReadable: false, workflowWritable: false } },
    { __vModel__: 'readonly', __config__: { layout: 'colFormItem', tag: 'el-input', workflowReadable: true, workflowWritable: false } },
    { __vModel__: 'editable', __config__: { layout: 'colFormItem', tag: 'el-input', workflowReadable: true, workflowWritable: true } },
    { __vModel__: 'required', __config__: { layout: 'colFormItem', tag: 'el-input', workflowReadable: true, workflowWritable: true, required: true } }
  ] })
  const fields = flattenFormFields(template.fields)

  assert.deepEqual(fields.filter(field => !field.hidden && field.readable).map(field => field.variable),
    ['readonly', 'editable', 'required'])
  assert.deepEqual(fields.filter(field => field.writable).map(field => field.variable),
    ['editable', 'required'])
})
