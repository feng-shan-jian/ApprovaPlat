import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { flattenFormFields, normalizeFormTemplate } from '../../src/components/workflow/form/formTemplate.js'

const { createRenderer, h, nextTick } = Vue
const element = (tag = '') => ({ tag, children: [], props: {}, parent: null })
function remove(child) {
  if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1)
  child.parent = null
}
const renderer = createRenderer({
  createElement: element, createText: () => element(), createComment: () => element(),
  setText() {}, setElementText() {}, patchProp: (node, key, before, value) => { node.props[key] = value },
  parentNode: child => child.parent,
  nextSibling: child => child.parent?.children[child.parent.children.indexOf(child) + 1] || null,
  remove,
  insert(child, parent, anchor) {
    remove(child)
    parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, child)
    child.parent = parent
  }
})

function component(path, modules) {
  const source = readFileSync(new URL(path, import.meta.url), 'utf8')
  const script = compileScript(parse(source).descriptor, { id: path, inlineTemplate: true })
  const bindings = { ...Object.fromEntries(Object.entries(Vue).filter(([name]) => /^\w+$/.test(name))), ...modules }
  const body = script.content.replace(/^import\s+([\s\S]*?)\s+from ['"]([^'"]+)['"];?\r?\n/gm, (all, names, source) => {
    if (source === 'vue') {
      for (const entry of names.replace(/[{}]/g, '').split(',')) {
        const [name, alias = name] = entry.trim().split(/\s+as\s+/)
        bindings[alias] = Vue[name]
      }
    }
    return ''
  }).replace('export default', 'return')
  return new Function(...Object.keys(bindings), body)(...Object.values(bindings))
}

const Field = component('../../src/components/workflow/form/WorkflowFormField.vue', {
  Editor: { render: () => null }, WorkflowAttachmentUpload: {
    props: ['modelValue'], emits: ['update:modelValue', 'busy-change'],
    setup: (props, { emit }) => () => h('attachment', {
      value: props.modelValue, change: value => emit('update:modelValue', value),
      busy: value => emit('busy-change', value)
    })
  }
})
const Form = component('../../src/components/workflow/ProcessFormRenderer.vue', {
  WorkflowFormField: Field, flattenFormFields, normalizeFormTemplate
})
const field = (name, extra = {}) => ({
  layout: 'colFormItem', tag: 'el-input', vModel: name, label: name,
  workflowHidden: false, workflowReadable: true, workflowWritable: true, required: true, ...extra
})
const row = (children, extra = {}) => ({
  layout: 'rowFormItem', workflowHidden: false, workflowReadable: true,
  workflowWritable: true, children, ...extra
})

async function mount(t, content, values, readonly = false) {
  let rules
  const container = element()
  const state = Vue.reactive({ content, values, readonly })
  const updates = []
  const changes = []
  const formRef = Vue.ref()
  const app = renderer.createApp({ render: () => h(Form, {
    ref: formRef, content: state.content, modelValue: state.values, readonly: state.readonly,
    'onUpdate:modelValue': value => { updates.push(value); state.values = value },
    onChange: value => changes.push(value)
  }) })
  app.component('WorkflowFormField', Field)
  for (const name of ['el-row', 'el-col', 'el-form-item', 'el-option', 'el-select', 'el-cascader',
    'el-radio', 'el-radio-group', 'el-checkbox', 'el-checkbox-group', 'el-table', 'el-table-column', 'el-button']) app.component(name, {
    setup: (props, { slots, attrs }) => () => h(name, attrs, slots.default?.())
  })
  app.component('el-form', {
    props: ['rules', 'model'],
    setup(props, { expose, slots }) {
      expose({ validate: async () => {
        rules = props.rules
        return Object.keys(rules).every(name => Boolean(props.model[name]))
      } })
      return () => h('form', null, slots.default?.())
    }
  })
  app.component('el-input', {
    props: ['modelValue', 'readonly', 'disabled'], emits: ['update:modelValue'],
    setup: (props, { emit }) => () => h('input', {
      ...props, change: value => emit('update:modelValue', value)
    })
  })
  app.mount(container)
  t.after(() => app.unmount())
  await nextTick()
  function find(tag) {
    const found = []
    function visit(node) {
      if (node.tag === tag) found.push(node)
      node.children.forEach(visit)
    }
    visit(container)
    return found
  }
  return { exposed: formRef.value, inputs: find('input'), find, state, updates, changes, rules: () => rules }
}

test('嵌套隐藏字段不进入真实表单提交和必填校验，原始快照不变', async t => {
  const content = { fields: [field('title'), row([row([field('secret')])], {
    workflowHidden: true, workflowReadable: false, workflowWritable: false
  })] }
  const before = JSON.stringify(content)
  const form = await mount(t, content, { title: 'Visible', secret: 'Private' })
  assert.equal(form.inputs.length, 1)
  assert.deepEqual(form.exposed.getValues(), { title: 'Visible' })
  assert.equal(await form.exposed.validate(), true)
  assert.deepEqual(Object.keys(form.rules()), ['title'])
  assert.equal(JSON.stringify(content), before)
})

test('只读祖先下真实输入控件禁用，事件不能改变原值，也不要求补填', async t => {
  const content = { fields: [field('editable'), row([row([field('locked')])], { workflowWritable: false })] }
  const form = await mount(t, content, { editable: 'Before', locked: 'Original' })
  assert.equal(form.inputs[1].props.disabled, true)
  assert.equal(form.inputs[1].props.readonly, true)
  form.inputs[1].props.change('Forged')
  form.inputs[0].props.change('After')
  await nextTick()
  assert.equal(form.inputs[1].props.modelValue, 'Original')
  assert.deepEqual(form.exposed.getValues(), { editable: 'After' })
  assert.equal(await form.exposed.validate(), true)
  assert.deepEqual(Object.keys(form.rules()), ['editable'])
})

test('历史只读模式传递到多层后代，不能通过子组件事件改值', async t => {
  const form = await mount(t, { fields: [row([row([field('value')])])] }, { value: 'History' }, true)
  assert.equal(form.inputs[0].props.disabled, true)
  form.inputs[0].props.change('Forged')
  await nextTick()
  assert.equal(form.inputs[0].props.modelValue, 'History')
})

test('附件提交过滤沿用有效权限，仍把可写附件元数据转换为 UUID', async t => {
  const attachment = name => field(name, { tag: 'el-upload', required: false })
  const content = { fields: [attachment('files'), row([attachment('lockedFiles')], { workflowWritable: false })] }
  const form = await mount(t, content, { files: [{ attachmentId: 'uuid' }], lockedFiles: [{ attachmentId: 'private' }] })
  assert.deepEqual(form.exposed.getValues(), { files: ['uuid'] })
})

test('一次顶层或嵌套编辑只发送一对事件，父级回写与外部重置保持静默', async t => {
  const form = await mount(t, { fields: [field('title'), row([row([field('nested')])])] }, { title: 'Before' })
  assert.equal(form.updates.length, 0)
  for (const [index, value] of [[0, 'After'], [1, 'Nested']]) {
    form.inputs[index].props.change(value)
    await nextTick()
    await nextTick()
  }
  assert.equal(form.updates.length, 2)
  assert.equal(form.changes.length, 2)
  assert.deepEqual(form.exposed.getValues(), { title: 'After', nested: 'Nested' })
  form.state.values = { title: 'Loaded', nested: 'Recovered' }
  await nextTick()
  assert.equal(form.inputs[0].props.modelValue, 'Loaded')
  form.state.values.nested = 'Reset in place'
  await nextTick()
  assert.equal(form.inputs[1].props.modelValue, 'Reset in place')
  form.state.content = { fields: [field('other', { defaultValue: 'Default' })] }
  form.state.values = {}
  await nextTick()
  assert.deepEqual(form.exposed.getValues(), { other: 'Default' })
  assert.equal(form.updates.length, 2)
  assert.equal(form.changes.length, 2)
})

test('数组编辑不修改父值旧引用，嵌套附件在途门禁和 UUID 投影保留', async t => {
  const before = { items: ['old'], files: [] }
  const form = await mount(t, { fields: [field('items'), row([row([field('files', { tag: 'el-upload' })])])] }, before)
  form.inputs[0].props.change(['one', 'two'])
  await nextTick()
  const upload = form.find('attachment')[0]
  upload.props.busy(true)
  await assert.rejects(form.exposed.ensureAttachmentsIdle(), /附件仍在上传中/)
  upload.props.change([{ attachmentId: 'new-uuid', originalName: 'proof.pdf' }])
  upload.props.busy(false)
  await nextTick()
  assert.equal(await form.exposed.ensureAttachmentsIdle(), true)
  assert.deepEqual(before, { items: ['old'], files: [] })
  assert.deepEqual(form.exposed.getValues(), { items: ['one', 'two'], files: ['new-uuid'] })
  assert.equal(form.state.values.files[0].originalName, 'proof.pdf')
  assert.equal(form.updates.length, 2)
  assert.equal(form.changes.length, 2)
})
