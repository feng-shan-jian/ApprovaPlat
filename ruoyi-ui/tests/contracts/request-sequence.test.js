import assert from 'node:assert/strict'
import test from 'node:test'
import { createRenderer, h } from 'vue'
import { useRequestSequence } from '../../src/utils/requestSequence.js'

test('Vue component unmount invalidates pending ownership and result sets stay independent', () => {
  const renderer = createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, parentNode() {}, nextSibling() {}, patchProp() {} })
  let first, second
  const app = renderer.createApp({ setup() { first = useRequestSequence(); second = useRequestSequence(); return () => h('div') } })
  app.mount({})
  const old = first.begin(), current = first.begin(), independent = second.begin()
  assert.equal(old(), false)
  assert.equal(current(), true)
  assert.equal(independent(), true)
  first.invalidate()
  assert.equal(current(), false)
  assert.equal(independent(), true)
  app.unmount()
  assert.equal(independent(), false)
})

test('a disposed request owner cannot be reacquired by mutation continuations', () => {
  const renderer = createRenderer({ createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
    insert() {}, remove() {}, setText() {}, setElementText() {}, parentNode() {}, nextSibling() {}, patchProp() {} })
  let owner
  const app = renderer.createApp({ setup() { owner = useRequestSequence(); return () => h('div') } })
  app.mount({})
  const before = owner.begin()
  owner.invalidate()
  assert.equal(before(), false)
  assert.equal(owner.begin()(), true)
  app.unmount()
  assert.equal(owner.begin()(), false)
  owner.invalidate()
  assert.equal(owner.begin()(), false)
})
