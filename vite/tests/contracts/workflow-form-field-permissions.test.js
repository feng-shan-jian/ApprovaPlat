import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { BpmnModdle } from 'bpmn-moddle'
import flowableModdle from '../../src/components/workflow/bpmn/flowableModdle.js'
import { flattenFormFields, normalizeFormTemplate } from '../../src/components/workflow/form/formTemplate.js'

const designerSource = readFileSync(
  new URL('../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')
const permissionEditorSource = readFileSync(
  new URL('../../src/components/workflow/designer/FormFieldPermissionEditor.vue', import.meta.url), 'utf8')
const rendererSource = readFileSync(
  new URL('../../src/components/workflow/ProcessFormRenderer.vue', import.meta.url), 'utf8')
const fieldSource = readFileSync(
  new URL('../../src/components/workflow/form/WorkflowFormField.vue', import.meta.url), 'utf8')
const propertiesPanelSource = readFileSync(
  new URL('../../src/components/workflow/designer/DesignerPropertiesPanel.vue', import.meta.url), 'utf8')

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
  assert.match(rendererSource, /visibleFields[\s\S]*?!field\.hidden && field\.readable/)
  assert.match(rendererSource, /writableFields[\s\S]*?field\.writable/)
  assert.match(rendererSource, /function getValues\(\)[\s\S]*?writableFields\.value\.forEach/)
  assert.match(fieldSource, /v-if="!field\.hidden && field\.readable/)
  assert.match(fieldSource, /effectiveReadonly[\s\S]*?field\.writable === false/)
})

/**
 * 验证设计器字段目录、批量默认和逐字段权限共用正式 BPMN 保存路径。
 * @returns {void} 配置仅停留在组件本地状态或缺少模型回读时测试失败。
 */
test('设计器字段权限连接正式模板目录与 BPMN 保存回读', () => {
  assert.match(permissionEditorSource, /function applyBatchMode\(\)[\s\S]*?emitPolicy\(/)
  assert.match(permissionEditorSource, /emit\('change',[\s\S]*?defaultMode[\s\S]*?fields/)
  assert.match(designerSource, /function readEmbeddedFormFields\(businessObject\)[\s\S]*?flowable:formKey[\s\S]*?if \(formKey\)[\s\S]*?return \[\]/)
  assert.match(designerSource, /function resolveFormPermissionSourceFields\(\)[\s\S]*?props\.forms\.find/)
  assert.match(designerSource, /function resolveFormPermissionSourceFields\(\)[\s\S]*?formSource !== 'TEMPLATE'[\s\S]*?return \[\]/)
  assert.match(propertiesPanelSource, /state\.formSource === 'TEMPLATE' && state\.formKey[\s\S]*?FormFieldPermissionEditor/)
  assert.match(designerSource, /function readTemplatePermissionPolicy\(businessObject\)/)
  assert.match(designerSource, /FORM_PERMISSION_DEFAULT_ID = 'approva_permission_default'/)
  assert.match(designerSource, /function createTemplatePermissionProperties\(\)[\s\S]*?FORM_PERMISSION_DEFAULT_ID/)
  assert.match(designerSource, /syncFormDefinition[\s\S]*?createTemplatePermissionProperties\(\)/)
})
