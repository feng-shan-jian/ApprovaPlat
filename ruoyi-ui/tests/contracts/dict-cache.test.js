import assert from 'node:assert/strict'
import test from 'node:test'
import { createPinia, setActivePinia } from 'pinia'
import { productionModules } from './fixtures/production-module.js'

async function fixture(t) {
  const requests = []
  const getDicts = key => new Promise((resolve, reject) => requests.push({ key, resolve, reject }))
  const load = await productionModules(t, { '@/api/system/dict/data': { getDicts } })
  const { default: useStore } = await load('store/modules/dict.js')
  const { useDict } = await load('utils/dict.js')
  const store = useStore(setActivePinia(createPinia()))
  return { store, requests, read: useDict }
}

const flush = () => new Promise(resolve => setImmediate(resolve))
const response = label => ({ data: [{ dictLabel: label, dictValue: '1' }] })

test('dictionary replaces a key and removes all inherited duplicates', async t => {
  const { store } = await fixture(t)
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

test('dictionary shares inflight loads, rejects stale backfill into cache and reactive consumers', async t => {
  const { store, requests, read } = await fixture(t)
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

test('dictionary clearing invalidates all pending keys and failed loads can retry', async t => {
  const { store, requests, read } = await fixture(t)
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

test('explicit dictionary replacement revokes inflight loading and independent keys still complete', async t => {
  const { store, requests, read } = await fixture(t)
  const first = read('a').a, independent = read('b').b
  store.setDict('a', [{ label: 'explicit', value: '1' }])
  requests[0].resolve(response('obsolete'))
  requests[1].resolve(response('independent'))
  await flush()
  assert.deepEqual([...first.value], [])
  assert.equal(store.getDict('a')[0].label, 'explicit')
  assert.equal(independent.value[0].label, 'independent')
})
