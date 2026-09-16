import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'

function editor(t) {
  const source = readFileSync(new URL('../../src/components/workflow/DecisionTableEditor.vue', import.meta.url), 'utf8')
  const script = compileScript(parse(source).descriptor, { id: 'decision-table-contract' }).content
    .replace(/^import[^\n]+\n/gm, '').replace('export default', 'return')
  const calls = []
  const api = async data => {
    calls.push(JSON.parse(JSON.stringify(data)))
    return { data: [{ result: '9007199254740993' }] }
  }
  const component = new Function('computed', 'reactive', 'ref', 'watch', 'evaluateDecisionTable', 'publishDecisionTable', script)(
    computed, reactive, ref, watch, api, api)
  const scope = effectScope()
  t.after(() => scope.stop())
  const state = scope.run(() => component.setup({}, { expose() {}, emit() {} }))
  Object.assign(state.form, {
    key: 'exact', name: '精确决策', hitPolicy: 'FIRST',
    inputs: [{ key: 'amount', label: '金额', type: 'number' }],
    outputs: [{ key: 'result', label: '结果', type: 'number' }],
    rules: [{ conditions: [{ operator: 'EQ', value: '9007199254740993' }], results: ['9999999999.12345678'] }],
    useDefault: true, defaultResult: ['-9999999999.12345678']
  })
  state.testValues.value = [' 9007199254740993 ']
  return { state, calls }
}

test('条件、输出、默认及试算值跨 JSON 请求保持十进制文本，试算结果不转 Number', async t => {
  const { state, calls } = editor(t)
  await state.evaluate()
  assert.equal(calls[0].rules[0].conditions[0].value, '9007199254740993')
  assert.equal(calls[0].rules[0].results[0], '9999999999.12345678')
  assert.equal(calls[0].defaultResult[0], '-9999999999.12345678')
  assert.equal(calls[0].testValues.amount, '9007199254740993')
  assert.equal(state.result.value[0].result, '9007199254740993')
  await state.publish()
  assert.deepEqual(calls[1], { ...calls[0], testValues: null })
})

test('空值在输入处提示，合法性与精度由后端判定；其他列类型保持原协议', async t => {
  const { state, calls } = editor(t)
  state.testValues.value = [' ']
  await nextTick()
  await state.evaluate()
  assert.equal(calls.length, 0)
  assert.match(state.error.value, /有效数值/)
  assert.equal(state.typedValue({ type: 'boolean' }, 'false'), false)
  assert.equal(state.typedValue({ type: 'string' }, '9007199254740993'), '9007199254740993')
  assert.equal(state.typedValue({ type: 'number' }, '1e-9'), '1e-9')
})

test('试算 API 显式选择精确文本响应，发布 API 保持原路径与正文', async () => {
  const source = readFileSync(new URL('../../src/api/workflow/dmn.js', import.meta.url), 'utf8')
    .replace(/^import[^\n]+\n/gm, '').replace(/export function /g, 'function ')
  const calls = []
  const api = new Function('request', `${source}\nreturn { evaluateDecisionTable, publishDecisionTable }`)(
    config => { calls.push(config); return Promise.resolve({ data: [] }) })
  const body = { rules: [] }
  await api.evaluateDecisionTable(body)
  await api.publishDecisionTable(body)
  assert.deepEqual(calls[0], { url: '/workflow/dmn/table/evaluate', method: 'post', params: { numberFormat: 'decimal-string' }, data: body })
  assert.deepEqual(calls[1], { url: '/workflow/dmn/table', method: 'post', data: body })
})
