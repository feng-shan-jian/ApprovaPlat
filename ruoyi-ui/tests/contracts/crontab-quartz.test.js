import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

function setupComponent(t, filename, props, emit = () => {}, dependencies = {}) {
  const source = readFileSync(new URL(`../../src/components/Crontab/${filename}.vue`, import.meta.url), 'utf8')
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: `cron-${filename}` }).content
  const mounted = []
  const disposed = []
  // 只替换模块装配，运行正式 SFC setup 与真实 Vue 响应式调度。
  const imports = Object.fromEntries([...script.matchAll(/^import (\w+) from .+$/gm)].map(match => [match[1], {}]))
  const bindings = { ref: Vue.ref, computed: Vue.computed, watch: Vue.watch, nextTick: Vue.nextTick, ...imports, ...dependencies, onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => disposed.push(callback) }
  const component = new Function(...Object.keys(bindings), script.replace(/^import .+$/gm, '').replace('export default', 'return'))(...Object.values(bindings))
  const scope = Vue.effectScope()
  const state = scope.run(() => component.setup(props, { expose() {}, emit }))
  const mount = () => mounted.splice(0).forEach(callback => callback())
  const dispose = () => { disposed.splice(0).forEach(callback => callback()); scope.stop() }
  t.after(dispose)
  return { state, source, dispose, mount }
}

const check = (value, min, max) => Math.max(min, Math.min(max, Math.floor(value)))

function cronEditor(t, expression) {
  const emitted = []
  const props = Vue.reactive({ expression, hideComponent: [] })
  const parent = setupComponent(t, 'index', props, (...args) => emitted.push(args))
  const children = {}
  for (const field of ['day', 'week', 'year', 'second', 'min', 'hour', 'month']) {
    // 按当前宿主装配；独立提交执行原四组件，合并工作区执行既有共用组件。
    const child = !['day', 'week', 'year'].includes(field) && parent.source.includes('import NumericField ') ? 'NumericField' : field
    children[field] = setupComponent(t, child, Vue.reactive({ cron: parent.state.crontabValueObj, check, field }), (_event, ...args) => parent.state.updateCrontabValue(...args))
    children[field].mount()
  }
  parent.mount()
  return { ...parent, props, emitted, children }
}

function weekEditor(t, initial = '?') {
  const cron = Vue.reactive({ second: '0', min: '0', hour: '9', day: '?', month: '*', week: initial, year: '2030' })
  const events = []
  const editor = setupComponent(t, 'week', { cron, check }, (event, name, value, from) => {
    events.push([event, name, value, from])
    cron[name] = value
  })
  return { ...editor, cron, events }
}

test('星期导入只回填选择，名称、第五周、范围、列表、最后一周与复杂原式均不写回', async t => {
  const { state, cron, events } = weekEditor(t)
  for (const value of ['MON', 'SUN', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'MON-FRI', 'SUN-SAT', 'FRI-MON', 'MON,WED,FRI', '2,4,6', '2#5', 'MON#5', '6L', 'FRIL', 'L', '2/2', '2-4,6', '*', '?']) {
    cron.week = value
    await Vue.nextTick()
    assert.equal(cron.week, value, `导入 ${value} 应保持原式`)
    assert.equal(cron.day, '?')
    assert.deepEqual(events, [])
  }
  cron.week = 'MON'
  await Vue.nextTick()
  assert.deepEqual(state.checkboxList.value, [2])
  cron.week = '2#5'
  await Vue.nextTick()
  assert.equal(state.average01.value, 5)
})

test('所有正式字段 watcher 回填后，重开并直接确定仍返回完整原表达式', async t => {
  for (const expression of ['0 0 9 ? * MON 2030', '0 0 9 ? * 2#5 2030', '0 0 9 ? * SUN-SAT 2030', '0 0 9 ? * 6L 2030', '5 10 8 ? JAN,JUN MON 2030', '* * 9 LW * ? 2080', '0']) {
    for (let opening = 0; opening < 2; opening++) {
      const parent = cronEditor(t, expression)
      await Vue.nextTick()
      await Vue.nextTick()
      parent.state.submitFill()
      assert.deepEqual(parent.emitted, [['fill', expression], ['hide']])
      parent.state.updateCrontabValue('second', '10')
      assert.equal(parent.state.crontabValueString.value.split(' ')[0], '10')
    }
  }
})

test('首次用户编辑、重置与连续导入采用最新输入，并保留未编辑的月份原式', async t => {
  const editor = cronEditor(t, '5 10 8 ? JAN,JUN MON 2030')
  await flush()
  const week = editor.children.week.state
  week.radioValue.value = 4
  week.average01.value = 5
  week.average02.value = 2
  week.onRadioChange()
  await flush()
  assert.equal(editor.state.crontabValueString.value, '5 10 8 ? JAN,JUN 2#5 2030')
  editor.props.expression = '0 0 9 ? JAN TUE 2030'
  await Vue.nextTick()
  editor.props.expression = '0 0 10 ? JUN FRI 2030'
  await flush()
  assert.equal(editor.state.crontabValueString.value, editor.props.expression)
  assert.equal(editor.state.crontabValueObj.value.month, 'JUN')
  editor.state.clearCron()
  await flush()
  assert.equal(editor.state.crontabValueString.value, '* * * * * ?')
  week.radioValue.value = 4
  week.average01.value = 5
  week.average02.value = 2
  week.onRadioChange()
  await flush()
  assert.equal(editor.state.crontabValueString.value, '* * * ? * 2#5')
})

test('用户编辑允许第 5 周、七个星期与跨周范围，并保持日周互斥', async t => {
  const { state, cron, source } = weekEditor(t, '2#5')
  await Vue.nextTick()
  assert.match(source, /v-model='average01'[^>]*:max="5"/)
  assert.doesNotMatch(source, /multiple-limit="6"/)
  state.radioValue.value = 4
  state.average01.value = 5
  state.average02.value = 3
  state.onRadioChange()
  await Vue.nextTick()
  assert.equal(cron.week, '3#5')
  state.average01.value = 99
  state.onRadioChange()
  assert.equal(cron.week, '3#5')
  state.radioValue.value = 3
  state.cycle01.value = 6
  state.cycle02.value = 2
  state.onRadioChange()
  assert.equal(cron.week, '6-2')
  state.radioValue.value = 6
  state.checkboxList.value = [1, 2, 3, 4, 5, 6, 7]
  state.onRadioChange()
  assert.equal(cron.week, '1,2,3,4,5,6,7')
  state.checkboxList.value = []
  state.onRadioChange()
  assert.equal(cron.week, '1')
  state.radioValue.value = 2
  state.onRadioChange()
  assert.equal(cron.week, '?')
  assert.equal(cron.day, '*')
  state.radioValue.value = 1
  state.onRadioChange()
  assert.equal(cron.day, '?')
})

function preview(t, expression = '0 0 9 ? * MON 2030') {
  const requests = []
  const props = Vue.reactive({ ex: expression })
  const component = setupComponent(t, 'result', props, undefined, {
    previewCron: expression => new Promise((resolve, reject) => requests.push({ expression, resolve, reject }))
  })
  return { ...component, props, requests }
}

const response = (executionTimes = ['2030-01-07 09:00:00 +08:00']) => ({ data: { timeZone: 'Asia/Shanghai', executionTimes } })
const flush = async () => { await Promise.resolve(); await Vue.nextTick() }

test('预览显示服务端时区和时间，零结果与非法表达式均结束加载', async t => {
  const { state, props, requests } = preview(t)
  assert.equal(state.loading.value, true)
  assert.equal(requests[0].expression, props.ex)
  requests[0].resolve(response())
  await flush()
  assert.deepEqual(state.resultList.value, response().data.executionTimes)
  assert.equal(state.timeZone.value, 'Asia/Shanghai')
  assert.equal(state.loading.value, false)
  props.ex = '0 0 9 ? * MON 2000'
  await Vue.nextTick()
  requests[1].resolve(response([]))
  await flush()
  assert.deepEqual(state.resultList.value, [])
  assert.equal(state.loading.value, false)
  props.ex = '0'
  await Vue.nextTick()
  requests[2].reject(new Error('Cron表达式不正确'))
  await flush()
  assert.match(state.error.value, /Cron表达式不正确/)
  assert.equal(state.loading.value, false)
  assert.deepEqual(state.resultList.value, [])
})

test('网络异常、乱序及卸载后响应不能污染当前预览或提前解除加载', async t => {
  const { state, props, requests, dispose } = preview(t)
  props.ex = '0 0 9 ? * 2#5 2030'
  await Vue.nextTick()
  requests[0].resolve(response(['旧结果']))
  await flush()
  assert.equal(state.loading.value, true)
  assert.deepEqual(state.resultList.value, [])
  requests[1].reject(new Error('Network Error'))
  await flush()
  assert.match(state.error.value, /预览失败/)
  assert.equal(state.loading.value, false)
  props.ex = '0 0 10 ? * MON 2030'
  await Vue.nextTick()
  props.ex = '0 0 11 ? * MON 2030'
  await Vue.nextTick()
  requests[3].resolve(response())
  await flush()
  requests[2].reject(new Error('过期失败'))
  await flush()
  assert.equal(state.error.value, '')
  assert.deepEqual(state.resultList.value, response().data.executionTimes)
  props.ex = '0 0 12 ? * MON 2030'
  await Vue.nextTick()
  dispose()
  requests[4].resolve(response(['卸载后结果']))
  await flush()
  assert.deepEqual(state.resultList.value, [])
})

test('Cron 预览 API 只发送原表达式，不传任务执行参数、浏览器时区或查询次数', async () => {
  const source = readFileSync(new URL('../../src/api/monitor/job.js', import.meta.url), 'utf8')
  const calls = []
  const api = new Function('request', source.replace(/^import .+$/gm, '').replaceAll('export function ', 'function ') + '\nreturn { previewCron }')(config => { calls.push(config); return Promise.resolve(response()) })
  const expression = '0 0 9 ? * MON 2030'
  await api.previewCron(expression)
  assert.deepEqual(calls, [{ url: '/monitor/job/cron/preview', method: 'get', params: { cronExpression: expression } }])
})
