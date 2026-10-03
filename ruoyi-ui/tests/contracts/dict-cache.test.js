import assert from 'node:assert/strict'
import test from 'node:test'
import fs from 'node:fs'
import vm from 'node:vm'
import { createPinia, defineStore } from 'pinia'
import { ref, toRefs } from 'vue'

function fixture() {
  const requests = []
  const getDicts = key => new Promise((resolve, reject) => requests.push({ key, resolve, reject }))
  const context = vm.createContext({ defineStore, getDicts, WeakMap, Map, Promise, ref, toRefs })
  const source = fs.readFileSync(new URL('../../src/store/modules/dict.js', import.meta.url), 'utf8')
    .replace(/^import .*\r?\n/gm, '').replace('export default useDictStore', 'globalThis.useStore = useDictStore')
  vm.runInContext(source, context)
  const store = context.useStore(createPinia())
  context.useDictStore = () => store
  const adapter = fs.readFileSync(new URL('../../src/utils/dict.js', import.meta.url), 'utf8')
    .replace(/^import .*\r?\n/gm, '').replace('export function useDict', 'function useDict')
  vm.runInContext(`${adapter}\nglobalThis.read = useDict`, context)
  return { store, requests, read: context.read }
}

const flush = () => new Promise(resolve => setImmediate(resolve))
const response = label => ({ data: [{ dictLabel: label, dictValue: '1' }] })

test('dictionary replaces a key and removes all inherited duplicates', () => {
  const { store } = fixture()
  store.setDict('status', ['old'])
  store.setDict('status', ['new'])
  assert.deepEqual([...store.getDict('status')], ['new'])
  assert.equal(store.dict.length, 1)
  store.dict.push({ key: 'status', value: ['legacy'] })
  assert.equal(store.removeDict('status'), true)
  assert.equal(store.dict.length, 0)
  for (const key of ['', null, undefined]) store.setDict(key, [])
  assert.equal(store.dict.length, 0)
})

test('dictionary shares inflight loads, rejects stale backfill into cache and reactive consumers', async () => {
  const { store, requests, read } = fixture()
  const first = read('status').status
  const second = read('status').status
  assert.equal(requests.length, 1)
  store.removeDict('status')
  const current = read('status').status
  assert.equal(requests.length, 2)
  requests[1].resolve(response('new'))
  await flush()
  requests[0].resolve(response('old'))
  await flush()
  assert.equal(current.value[0].label, 'new')
  assert.equal(store.getDict('status')[0].label, 'new')
  assert.deepEqual([...first.value], [])
  assert.deepEqual([...second.value], [])
})

test('dictionary clearing invalidates all pending keys and failed loads can retry', async () => {
  const { store, requests, read } = fixture()
  const old = read('status').status
  store.cleanDict()
  requests[0].resolve(response('expired'))
  await flush()
  assert.deepEqual([...old.value], [])
  assert.equal(store.getDict('status'), undefined)
  const failed = store.loadDict('status')
  requests[1].reject(new Error('unavailable'))
  await assert.rejects(failed, /unavailable/)
  const retry = store.loadDict('status')
  requests[2].resolve(response('retry'))
  await retry
  assert.equal(store.getDict('status')[0].label, 'retry')
})

test('explicit dictionary replacement revokes inflight loading and independent keys still complete', async () => {
  const { store, requests, read } = fixture()
  const first = read('a').a, independent = read('b').b
  store.setDict('a', [{ label: 'explicit', value: '1' }])
  requests[0].resolve(response('obsolete'))
  requests[1].resolve(response('independent'))
  await flush()
  assert.deepEqual([...first.value], [])
  assert.equal(store.getDict('a')[0].label, 'explicit')
  assert.equal(independent.value[0].label, 'independent')
})

for (const [page, update, add, id] of [['index.vue', 'updateType', 'addType', 'dictId'], ['data.vue', 'updateData', 'addData', 'dictCode']]) {
  test(`dictionary ${page} invalidates original and submitted types after success despite later form changes`, async () => {
    const source = fs.readFileSync(new URL(`../../src/views/system/dict/${page}`, import.meta.url), 'utf8')
    const body = source.match(/function submitForm\(\) \{[\s\S]*?\n\}/)[0]
    let finish
    const pending = new Promise(resolve => { finish = resolve })
    const invalidated = [], writes = []
    const form = { value: { [id]: 1, dictType: 'new_type' } }
    const dependencies = {
      form, originalDictType: { value: 'old_type' }, open: { value: true }, getList() {},
      proxy: { $refs: { dictRef: { validate: callback => callback(true) }, dataRef: { validate: callback => callback(true) } }, $modal: { msgSuccess() {} } },
      useDictStore: () => ({ removeDict: key => invalidated.push(key) }),
      [update]: data => { writes.push(data); return pending }, [add]: () => { throw new Error('unexpected insert') }
    }
    const submit = new Function(...Object.keys(dependencies), `${body}; return submitForm`)(...Object.values(dependencies))
    submit()
    form.value.dictType = 'later_edit'
    assert.deepEqual(invalidated, [])
    finish()
    await flush()
    assert.equal(writes[0].dictType, 'new_type')
    assert.deepEqual(invalidated, ['old_type', 'new_type'])
  })
}
