import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compileScript, parse } from '@vue/compiler-sfc'
import { computed, effectScope, nextTick, reactive, ref, watch } from 'vue'

const source = readFileSync(new URL('../../src/components/Crontab/NumericField.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const script = compileScript(descriptor, { id: 'cron-contract' }).content
// 执行生产组件的 setup 和真实 Vue watcher，避免复制一套测试专用的 Cron 算法。
const component = new Function('ref', 'computed', 'watch', script.replace('export default', 'return'))(ref, computed, watch)

function editor(t, field) {
  const cron = reactive({ second: '*', min: '*', hour: '*', day: '*', month: '*', week: '?', year: '' })
  const events = []
  const scope = effectScope()
  const state = scope.run(() => component.setup({
    field, cron,
    check: (value, min, max) => Math.max(min, Math.min(max, Math.floor(value)))
  }, {
    expose() {},
    emit(event, name, value, from) {
      assert.equal(event, 'update')
      events.push([name, value, from])
      cron[name] = value
    }
  }))
  t.after(() => scope.stop())
  return { state, cron, events }
}

for (const [field, min, max] of [['second', 0, 59], ['min', 0, 59], ['hour', 0, 23], ['month', 1, 12]]) {
  test(`${field} 导入、区间、步长、指定值清空与重置保持同一表达式契约`, async t => {
    const { state, cron } = editor(t, field)
    cron[field] = `${min + 1}-${max - 1}`
    await nextTick()
    assert.equal(state.radioValue.value, 2)
    assert.equal(state.cycle01.value, min + 1)
    assert.equal(state.cycle02.value, max - 1)
    state.cycle01.value = -9
    state.cycle02.value = 999
    await nextTick()
    assert.equal(cron[field], `${min}-${max}`)

    cron[field] = `${min + 1}/3`
    await nextTick()
    assert.equal(state.radioValue.value, 3)
    state.average01.value = max - 1
    state.average02.value = 999
    await nextTick()
    assert.equal(cron[field], `${max - 1}/1`)

    cron[field] = `${min + 3},${min + 1},${min + 3}`
    await nextTick()
    assert.equal(cron[field], `${min + 3},${min + 1}`)
    state.checkboxList.value = []
    await nextTick()
    assert.equal(cron[field], `${min + 3}`)
    cron[field] = '*'
    await nextTick()
    assert.equal(state.radioValue.value, 1)
    assert.equal(cron[field], '*')
    assert.deepEqual(state.options.map(option => option.value), Array.from({ length: max - min + 1 }, (_, index) => min + index))
  })
}

test('只有小时编辑联动通配分秒，已经指定的分秒保持不变', async t => {
  const hour = editor(t, 'hour')
  hour.cron.hour = '6'
  await nextTick()
  assert.equal(hour.cron.min, '0')
  assert.equal(hour.cron.second, '0')
  assert.deepEqual(hour.events.slice(0, 2), [['min', '0', 'hour'], ['second', '0', 'hour']])
  hour.cron.min = '15'
  hour.cron.second = '30'
  hour.cron.hour = '8'
  await nextTick()
  assert.equal(hour.cron.min, '15')
  assert.equal(hour.cron.second, '30')
  Object.assign(hour.cron, { second: '*', min: '*', hour: '*' })
  await nextTick()
  assert.equal(hour.cron.min, '0')
  assert.equal(hour.cron.second, '0')
  const minute = editor(t, 'min')
  minute.cron.min = '5'
  await nextTick()
  assert.equal(minute.cron.second, '*')
  const month = editor(t, 'month')
  assert.equal(month.state.options[0].label, '一月')
  assert.equal(month.state.options[11].label, '十二月')
})
