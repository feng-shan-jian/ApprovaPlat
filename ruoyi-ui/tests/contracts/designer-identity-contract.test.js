import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
import { designerIdentityContract, identityRequestTarget } from '../../src/components/workflow/designer/designerIdentityContract.js'
import { createFormParticipantDomain } from '../../src/components/workflow/designer/formParticipantDomain.js'

const matrix = [
  ['assignees', 'user', 'approval', true], ['candidateUsers', 'user', 'claim', true],
  ['candidateGroups', 'group', 'claim', true], ['candidateRoles', 'role', 'claim', true],
  ['activeUsers', 'user', '', true], ['activeRoles', 'role', '', true], ['activeDepts', 'dept', '', true],
  ['autoCopyUsers', 'user', 'copy', false], ['autoCopyGroups', 'group', 'copy', false]
]

test('designer identity pools accept exactly their type, qualification and supported operation', () => {
  for (const [target, type, capability, resolve] of matrix) {
    assert.deepEqual(designerIdentityContract(target), { type, capability, resolve })
    assert.equal(Object.isFrozen(designerIdentityContract(target)), true)
    for (const actualType of ['user', 'group', 'role', 'dept', '', 'USER']) {
      for (const actualCapability of ['approval', 'claim', 'copy', '', 'unknown']) {
        const request = { target, type: actualType, capability: actualCapability }
        const valid = actualType === type && actualCapability === capability
        assert.equal(identityRequestTarget(request), valid ? target : '')
        assert.equal(identityRequestTarget(request, 'resolve'), valid && resolve ? target : '')
      }
    }
    assert.equal(identityRequestTarget({ target, type }, 'unknown'), '')
  }
  for (const target of ['constructor', '__proto__', 'unknown', '', null, undefined]) {
    assert.equal(designerIdentityContract(target), undefined)
    assert.equal(identityRequestTarget({ target, type: 'user', capability: 'approval' }), '')
  }
  assert.equal(identityRequestTarget(undefined), '')
})

test('identity events retain independent debounce pools and only bounded supported resolutions', async t => {
  const events = []
  const previousWindow = globalThis.window
  globalThis.window = { setTimeout, clearTimeout }
  t.after(() => { if (previousWindow === undefined) delete globalThis.window; else globalThis.window = previousWindow })
  const domain = createFormParticipantDomain({ propertyState: {}, selectedBusinessObject: ref(null), formFieldOptions: ref([]),
    emit: (event, payload) => events.push({ event, payload }) })
  t.after(() => domain.disposeIdentitySearchTimers())
  for (const [target] of matrix) {
    domain.handlePanelIdentitySearch({ target, keyword: 'old' })
    domain.handlePanelIdentitySearch({ target, keyword: ' current ' })
  }
  domain.handlePanelIdentitySearch({ target: 'constructor', keyword: 'invalid' })
  await new Promise(resolve => setTimeout(resolve, 280))
  assert.deepEqual(events, matrix.map(([target, type, capability]) => ({ event: 'identity-search', payload: { target, type, capability, keyword: 'current' } })))
  events.length = 0
  const values = Array.from({ length: 200 }, (_, index) => String(index + 1))
  for (const [target, type, capability, resolve] of matrix) {
    domain.handlePanelIdentityResolve({ target, values: [...values, '1', ' 2 ', ''] })
    if (resolve) assert.deepEqual(events.pop(), { event: 'identity-resolve', payload: { target, type, capability, values } })
    domain.handlePanelIdentityResolve({ target, values: [...values, '201'] })
    domain.handlePanelIdentityResolve({ target, values: [] })
    assert.deepEqual(events, [])
  }
  domain.handlePanelIdentitySearch({ target: 'candidateUsers', keyword: 'disposed' })
  domain.disposeIdentitySearchTimers()
  await new Promise(resolve => setTimeout(resolve, 280))
  assert.deepEqual(events, [])
})
