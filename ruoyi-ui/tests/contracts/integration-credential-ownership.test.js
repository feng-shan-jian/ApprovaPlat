import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'
import { createRenderer, h, ref, reactive, computed, watch, nextTick, getCurrentInstance, onMounted, onActivated, onBeforeUnmount } from 'vue'
import { useRequestSequence } from '../../src/utils/requestSequence.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

async function page(t) {
  const calls = { read: [], bind: [], create: [], rotate: [], confirm: [] }, messages = [], leave = [], events = new Map()
  const request = name => (...args) => {
    const pending = deferred()
    calls[name].push({ ...pending, args })
    return pending.promise
  }
  const dependencies = {
    ref, reactive, computed, watch, nextTick, getCurrentInstance, onMounted, onActivated, onBeforeUnmount, useRequestSequence,
    onBeforeRouteLeave: guard => leave.push(guard),
    window: { addEventListener: (event, handler) => events.set(event, handler), removeEventListener: event => events.delete(event) },
    listIntegrationCredentials: async () => ({ data: [] }),
    listConnectorEndpoints: async () => ({ data: [] }),
    getConnectorCredential: request('read'), createAndBindIntegrationCredential: request('bind'),
    createIntegrationCredential: request('create'), rotateIntegrationCredential: request('rotate'),
    revokeIntegrationCredential: async () => ({})
  }
  const script = fs.readFileSync(new URL('../../src/views/workflow/integrationCredential/index.vue', import.meta.url), 'utf8')
    .match(/<script setup[^>]*>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gm, '')
  const setup = new Function(...Object.keys(dependencies), `${script}; return {
    openCreate, submitCreate, rotateToken, form, formRef, bindEndpointId, createOpen, secretOpen, oneTimeToken,
    acknowledgeToken: typeof acknowledgeToken === 'function' ? acknowledgeToken : undefined,
    tokenAccount: typeof tokenAccount === 'undefined' ? undefined : tokenAccount
  }`)
  const renderer = createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, parentNode() {}, nextSibling() {}, patchProp() {} })
  let state
  const app = renderer.createApp({ setup() { state = setup(...Object.values(dependencies)); return () => h('div') } })
  app.config.globalProperties.$auth = { hasPermi: () => true }
  app.config.globalProperties.$modal = { confirm: request('confirm'), msgSuccess: value => messages.push(value), msgWarning: value => messages.push(value) }
  app.mount({})
  let mounted = true
  const unmount = () => { if (mounted) { app.unmount(); mounted = false } }
  t.after(unmount)
  state.formRef.value = { validate: async () => true, clearValidate() {} }
  await state.openCreate()
  state.form.credentialName = '账号A'
  return { ...state, calls, messages, leave, events, unmount }
}

const endpoint = { data: { endpointRevision: 7, credential: { revision: 3 } } }
const account = id => ({ credentialId: id, credentialName: `账号${id}`, rotateAllowed: true })
const secret = id => ({ data: { credentialId: id, token: `synthetic-token-${id}`, credential: account(id) } })

test('create binding cannot cross a changed endpoint, reopened dialog or unmounted component after reading metadata', async t => {
  for (const change of ['endpoint', 'endpoint-roundtrip', 'reopen', 'unmount']) {
    const state = await page(t)
    state.bindEndpointId.value = 1
    const pending = state.submitCreate()
    await Promise.resolve()
    await Promise.resolve()
    assert.equal(state.calls.read.length, 1)
    if (change === 'endpoint') state.bindEndpointId.value = 2
    if (change === 'endpoint-roundtrip') { state.bindEndpointId.value = 2; state.bindEndpointId.value = 1 }
    if (change === 'reopen') { state.createOpen.value = false; await state.openCreate(); state.bindEndpointId.value = 1 }
    if (change === 'unmount') state.unmount()
    state.calls.read[0].resolve(endpoint)
    await Promise.resolve()
    assert.equal(state.calls.bind.length, 0, `${change} must invalidate the original pre-write ownership`)
    await pending
  }
})

test('closing or leaving while form validation is pending cannot dispatch a create request', async t => {
  for (const change of ['close', 'leave', 'unmount']) {
    const state = await page(t), validation = deferred()
    state.formRef.value.validate = () => validation.promise
    const pending = state.submitCreate()
    if (change === 'close') state.createOpen.value = false
    if (change === 'leave') assert.equal(state.leave[0](), true)
    if (change === 'unmount') state.unmount()
    validation.resolve(true)
    await pending
    assert.equal(state.calls.create.length, 0, `${change} must revoke validation's pre-write ownership`)
  }
})

test('create captures the submitted account contents before asynchronous validation and endpoint metadata', async t => {
  const state = await page(t), validation = deferred()
  state.bindEndpointId.value = 1
  state.formRef.value.validate = () => validation.promise
  const pending = state.submitCreate()
  state.form.credentialName = 'later edit'
  state.form.scopes.push('SIGNAL')
  validation.resolve(true)
  await Promise.resolve()
  await Promise.resolve()
  state.calls.read[0].resolve(endpoint)
  await Promise.resolve()
  assert.equal(state.calls.bind.length, 1)
  assert.equal(state.calls.bind[0].args[0], 1)
  assert.deepEqual(state.calls.bind[0].args[1].credential, { credentialName: '账号A', scopes: ['MESSAGE'], allowedVariables: [], rateLimitPerMinute: 60, expiresAt: null })
  state.calls.bind[0].resolve({ data: { credentialId: 1 } })
  await pending
})

test('one-time token operations serialize confirmation, creation and rotation until the user acknowledges the identified account', async t => {
  const state = await page(t)
  state.createOpen.value = false
  const first = state.rotateToken(account(1))
  const overlapping = state.rotateToken(account(2))
  assert.equal(state.calls.confirm.length, 1, 'a second rotation must not open while the first confirmation owns the token slot')
  await overlapping
  state.calls.confirm[0].resolve()
  await Promise.resolve()
  assert.equal(state.calls.rotate.length, 1)
  assert.equal(state.leave[0](), false, 'navigation must wait for an already dispatched token response')
  const unload = { prevented: false, preventDefault() { this.prevented = true } }
  state.events.get('beforeunload')(unload)
  assert.equal(unload.prevented, true)
  await state.openCreate()
  await state.submitCreate()
  assert.equal(state.calls.create.length, 0, 'creation must share the same slot as rotation')
  state.calls.rotate[0].resolve(secret(1))
  await first
  assert.equal(state.oneTimeToken.value, 'synthetic-token-1')
  assert.deepEqual(state.tokenAccount.value, { credentialId: 1, credentialName: '账号1' })
  await state.rotateToken(account(2))
  await state.submitCreate()
  assert.equal(state.calls.confirm.length, 1)
  assert.equal(state.calls.create.length, 0)
  assert.equal(state.oneTimeToken.value, 'synthetic-token-1', 'a successful unacknowledged token must remain available')
  assert.equal(state.leave[0](), false, 'an unacknowledged token must not disappear on navigation')
  state.acknowledgeToken()
  assert.equal(state.oneTimeToken.value, '')
  assert.equal(state.leave[0](), true)
  // Reopen because leaving also revokes the previous form's pending submission rights.
  await state.openCreate()
  state.form.credentialName = '账号2'
  const second = state.submitCreate()
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(state.calls.create.length, 1)
  state.calls.create[0].resolve(secret(2))
  await second
  assert.equal(state.oneTimeToken.value, 'synthetic-token-2')
  assert.equal(state.tokenAccount.value.credentialId, 2)
})

test('rotation freezes the confirmed account and canceled or failed operations release the token slot', async t => {
  const state = await page(t)
  state.createOpen.value = false
  const canceled = state.rotateToken(account(1))
  state.calls.confirm[0].reject(new Error('cancel'))
  await canceled
  const row = account(1)
  const failed = state.rotateToken(row)
  row.credentialId = 2
  row.credentialName = 'changed row'
  state.calls.confirm[1].resolve()
  await Promise.resolve()
  assert.equal(state.calls.rotate[0].args[0], 1)
  state.calls.rotate[0].reject(new Error('controlled write failure'))
  await assert.rejects(failed, /controlled write failure/)
  const retry = state.rotateToken(account(2))
  assert.equal(state.calls.confirm.length, 3)
  state.calls.confirm[2].resolve()
  await Promise.resolve()
  state.calls.rotate[1].resolve(secret(2))
  await retry
  assert.equal(state.oneTimeToken.value, 'synthetic-token-2')
})

test('an unmounted rotation confirmation cannot dispatch a token write', async t => {
  const state = await page(t)
  const pending = state.rotateToken(account(1))
  state.unmount()
  state.calls.confirm[0].resolve()
  await pending
  assert.equal(state.calls.rotate.length, 0)
  assert.equal(state.events.has('beforeunload'), false)
})
