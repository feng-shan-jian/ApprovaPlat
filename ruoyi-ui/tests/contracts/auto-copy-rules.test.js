import assert from 'node:assert/strict'
import test from 'node:test'
import { ref } from 'vue'
import { createExtensionEventSlaDomain } from '../../src/components/workflow/designer/extensionEventSlaDomain.js'
import { normalizeAutoCopyRules } from '../../src/components/workflow/designer/autoCopyRules.js'

test('auto-copy writer rejects fields absent from the formal field catalog', () => {
  const rules = [{ id: 'copy', trigger: 'NODE_COMPLETED', recipients: [{ type: 'FORM_USER_FIELD', values: ['missing'] }] }]
  const domain = createExtensionEventSlaDomain({ formFieldOptions: ref([]) })
  assert.throws(() => domain.normalizeAndValidateAutoCopyRules(rules, { allowedTriggers: ['NODE_COMPLETED'], allowedFormFields: ['owner'] }), /正式标量字段/)

})

const context = { allowedTriggers: ['NODE_ARRIVED', 'NODE_COMPLETED'], allowedFormFields: ['owner'] }
const rule = (type = 'USER', values = ['1']) => ({ id: 'copy', trigger: 'NODE_COMPLETED', recipients: [{ type, values }] })

test('auto-copy shared normalization preserves all sources, structural errors, limits and history mode', () => {
  for (const source of [['USER', [1]], ['GROUP', ['ROLE1', 'DEPT2']], ['INITIATOR', []], ['FORM_USER_FIELD', ['owner']]]) {
    const input = [rule(...source)]
    const normalized = normalizeAutoCopyRules(input, context)
    assert.deepEqual(normalized[0].recipients[0].values, source[1].map(String))
    assert.notEqual(normalized, input)
  }
  assert.deepEqual(normalizeAutoCopyRules([], context), [])
  assert.throws(() => normalizeAutoCopyRules([], { ...context, allowEmpty: false }), /数量/)
  assert.throws(() => normalizeAutoCopyRules([rule(), rule()], context), /主键/)
  assert.throws(() => normalizeAutoCopyRules([{ ...rule(), recipients: [rule().recipients[0], rule().recipients[0]] }], context), /重复配置/)
  for (const source of [['USER', ['0']], ['GROUP', ['USER1']], ['INITIATOR', ['1']], ['FORM_USER_FIELD', ['owner ']], ['USER', ['1', '1']]]) {
    assert.throws(() => normalizeAutoCopyRules([rule(...source)], context))
  }
  assert.equal(normalizeAutoCopyRules(Array.from({ length: 10 }, (_, i) => ({ ...rule(), id: `copy${i}` })), context).length, 10)
  assert.throws(() => normalizeAutoCopyRules(Array.from({ length: 11 }, (_, i) => ({ ...rule(), id: `copy${i}` })), context), /数量/)
  assert.equal(normalizeAutoCopyRules([{ ...rule(), recipients: Array.from({ length: 20 }, (_, i) => ({ type: 'USER', values: [String(i + 1)] })) }], context)[0].recipients.length, 20)
  assert.throws(() => normalizeAutoCopyRules([{ ...rule(), recipients: Array.from({ length: 21 }, (_, i) => ({ type: 'USER', values: [String(i + 1)] })) }], context), /数量/)
  assert.equal(normalizeAutoCopyRules([rule('USER', Array.from({ length: 100 }, (_, i) => String(i + 1)))], context)[0].recipients[0].values.length, 100)
  assert.throws(() => normalizeAutoCopyRules([rule('USER', Array.from({ length: 101 }, (_, i) => String(i + 1)))], context), /有界数组/)
  assert.throws(() => normalizeAutoCopyRules([rule()], { ...context, allowedTriggers: ['PROCESS_COMPLETED'] }), /触发时机/)
  const historical = [rule('FORM_USER_FIELD', ['retired'])]
  assert.deepEqual(normalizeAutoCopyRules(historical, { validatePlacement: false, validateFormFields: false }), historical)
  assert.throws(() => normalizeAutoCopyRules([{ ...rule(), trigger: 'UNKNOWN' }], { validatePlacement: false }), /触发时机/)
  const length = JSON.stringify({ version: 1, rules: [rule()] }).length
  assert.deepEqual(normalizeAutoCopyRules([rule()], { ...context, maxLength: length }), [rule()])
  assert.throws(() => normalizeAutoCopyRules([rule()], { ...context, maxLength: length - 1 }), /字符/)
})

test('auto-copy writer makes one property update, preserves unknown properties, removes empty and rereads on invalid input', () => {
  const properties = [{ name: 'vendor.unknown', value: 'preserve' }, { name: 'approva.autoCopyRules', value: 'old' }]
  const writes = [], failures = [], rereads = []
  const businessObject = { $type: 'bpmn:UserTask' }
  const propertyState = {}
  const domain = createExtensionEventSlaDomain({
    designerLocked: ref(false), getModeler: () => ({}), selectedElement: ref({ id: 'task' }),
    selectedBusinessObject: ref(businessObject), propertyFlags: ref({ process: false }),
    isType: type => type === 'bpmn:UserTask', formFieldOptions: ref([]),
    formFieldCatalog: { nodeFields: () => ({ userIdFields: [{ value: 'owner' }] }) }, propertyState,
    readAllFlowableProperties: () => properties,
    persistExtensionProperties: value => writes.push(value),
    emit: (_event, error) => failures.push(error.message), loadPropertyState: target => rereads.push(target)
  })
  domain.updateAutoCopyRules([rule()])
  assert.equal(writes.length, 1)
  assert.deepEqual(writes[0][0], properties[0])
  assert.deepEqual(JSON.parse(writes[0][1].value), { version: 1, rules: [rule()] })
  domain.updateAutoCopyRules([rule('FORM_USER_FIELD', ['missing'])])
  assert.equal(writes.length, 1)
  assert.equal(failures.length, 1)
  assert.equal(rereads.length, 1)
  domain.updateAutoCopyRules([])
  assert.equal(writes.length, 2)
  assert.deepEqual(writes[1], [properties[0]])
})
