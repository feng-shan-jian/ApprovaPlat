import assert from 'node:assert/strict'
import test from 'node:test'
import { selectDictLabels } from '../../src/utils/ruoyi.js'

const DICTIONARY = Object.freeze([
  Object.freeze({ value: '1', label: '启用' }),
  Object.freeze({ value: '2', label: '停用' }),
  Object.freeze({ value: '2', label: '重复值不应再次输出' })
])

/**
 * 验证多值字典按输入顺序回显，未配置值继续使用原始文本。
 * @returns {void} 输出顺序、分隔符或兜底值不一致时测试失败。
 */
test('多值字典按顺序回显且保留未知值', () => {
  assert.equal(selectDictLabels(DICTIONARY, '1,2,9'), '启用,停用,9')
})

/**
 * 验证数组输入与自定义分隔符遵循同一公开工具契约。
 * @returns {void} 数组被错误拼接或拆分时测试失败。
 */
test('数组输入支持自定义分隔符', () => {
  assert.equal(selectDictLabels(DICTIONARY, ['1', '2'], ';'), '启用;停用')
})
