import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { parse as parseVue } from '@vue/compiler-sfc'
import { parse } from '@babel/parser'
import { createDesignerFormFieldCatalog } from '../../src/components/workflow/designer/designerFormFieldCatalog.js'

// 执行组件原函数和真实异步目录；只替换浏览器/Modeler 端口，不重写导出或失效逻辑。
function designer(overrides = {}) {
  const source = parseVue(readFileSync(new URL('../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')).descriptor.scriptSetup.content
  const names = ['emitXmlChange', 'handleCommandStackChanged', 'importXml', 'runServerValidation']
  const functions = parse(source, { sourceType: 'module' }).program.body
    .filter(node => node.type === 'FunctionDeclaration' && names.includes(node.id.name))
    .map(node => source.slice(node.start, node.end)).join('\n')
  const calls = []
  const catalog = createDesignerFormFieldCatalog({ loadAuthorFields: async xml => {
    calls.push(xml); return { data: { nodes: [], processes: [], issues: [] } }
  } })
  const events = []
  const context = {
    modeler: null, authorRevision: 1, importing: false, changeTimer: null,
    formFieldCatalog: catalog, lastExportedXml: { value: 'initial' },
    validationIssues: { value: [] }, validationPassed: { value: false }, loading: { value: false },
    selectedElement: { value: null }, props: {},
    emit: (...event) => events.push(event), updateCommandState() {}, refreshControlledLoopOverlays() {}, createModeler() {},
    window: { requestAnimationFrame() {}, clearTimeout() {}, setTimeout() {} },
    ...overrides
  }
  const api = new Function('context', `with (context) { ${functions}; return { ${names.join(', ')} } }`)(context)
  context.emitXmlChange = api.emitXmlChange
  return { ...api, context, calls, events, catalog }
}

test('命令修改和卸载发生在异步 saveXML 期间时，旧导出不能重新加载目录或更新模型', async () => {
  let finish
  const current = designer({ modeler: { saveXML: () => new Promise(resolve => { finish = resolve }) } })
  const pending = current.emitXmlChange(true)
  current.handleCommandStackChanged()
  finish({ xml: 'old' })
  assert.equal(await pending, '')
  assert.deepEqual(current.calls, [])
  assert.deepEqual(current.events, [['editing']])
  const unmount = current.emitXmlChange(true)
  current.context.authorRevision += 1
  current.context.modeler = undefined
  current.catalog.invalidate()
  finish({ xml: 'after unmount' })
  assert.equal(await unmount, '')
  assert.deepEqual(current.calls, [])
})

test('有效导出加载相同 XML 的目录，导入在渲染前失败能恢复旧图目录', async () => {
  const current = designer({
    modeler: { saveXML: async () => ({ xml: 'current' }), get(service) {
      if (service === 'canvas') return { viewbox: () => ({}) }
      if (service === 'moddle') return { fromXML: async () => { throw new Error('bad XML') } }
      throw new Error(service)
    } },
    flushXml: async () => 'current'
  })
  assert.equal(await current.emitXmlChange(false), 'current')
  assert.deepEqual(current.calls, ['current'])
  await current.importXml('malformed')
  assert.deepEqual(current.calls, ['current', 'current'])
  assert.equal(current.context.loading.value, false)
  assert.equal(current.context.importing, false)
})

test('作者目录 API 使用有界 BPMN 正文，不创建模型或部署', async () => {
  const source = readFileSync(new URL('../../src/api/workflow/model.js', import.meta.url), 'utf8')
    .replace(/^import[^\n]+\n/gm, '').replace(/export function /g, 'function ')
  const calls = []
  const api = new Function('request', `${source}\nreturn { getAuthorFieldCatalog }`)(request => { calls.push(request); return Promise.resolve({ data: {} }) })
  await api.getAuthorFieldCatalog('<draft/>')
  assert.deepEqual(calls, [{ url: '/workflow/model/author-field-catalog', method: 'post',
    data: { bpmnXml: '<draft/>' }, headers: { repeatSubmit: false } }])
})

test('重新校验同一 XML 会刷新模板资格，目录在途不阻断服务端保存校验', async () => {
  const validated = []
  const current = designer({ validating: { value: false }, validationKind: { value: '' },
    validationVisible: { value: false }, validateModelBpmn: async xml => {
      validated.push(xml); return { data: { valid: true, issues: [] } }
    } })
  await current.catalog.refresh('current')
  assert.equal(await current.runServerValidation(false, 'current'), true)
  assert.deepEqual(current.calls, ['current', 'current'])
  assert.deepEqual(validated, ['current'])
})
