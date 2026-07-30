import assert from 'node:assert/strict'
import test from 'node:test'
import { loadWorkflowAccounts, WORKFLOW_ROLE_KEYS } from '../e2e/support/environment.js'

/** 五角色用户名环境变量后缀，与正式测试环境契约保持一致。 */
const roleSuffixes = ['ADMIN', 'DESIGNER', 'STARTER', 'APPROVER', 'AUDITOR']

/**
 * 验证五角色测试账号统一使用 wang，且不会消费历史密码环境变量。
 * @returns {void} 账号规则符合要求时正常结束。
 */
test('工作流测试账号固定使用 wang', () => {
  const previousValues = new Map()
  const registeredKey = 'FLOWABLE_RBAC_ACCOUNTS_REGISTERED'
  previousValues.set(registeredKey, process.env[registeredKey])

  try {
    process.env[registeredKey] = 'true'
    roleSuffixes.forEach((suffix, index) => {
      const usernameKey = `FLOWABLE_RBAC_WORKFLOW_${suffix}_USERNAME`
      const legacyPasswordKey = `FLOWABLE_RBAC_WORKFLOW_${suffix}_PASSWORD`
      previousValues.set(usernameKey, process.env[usernameKey])
      previousValues.set(legacyPasswordKey, process.env[legacyPasswordKey])
      process.env[usernameKey] = `workflow_test_${index}`
      process.env[legacyPasswordKey] = `legacy-password-${index}`
    })

    const accounts = loadWorkflowAccounts()
    assert.deepEqual(Object.keys(accounts), [...WORKFLOW_ROLE_KEYS])
    assert.deepEqual(new Set(Object.values(accounts).map(account => account.password)), new Set(['wang']))
  } finally {
    // 恢复进程环境，避免合同测试污染同一 Node.js 进程中的其他用例。
    for (const [key, value] of previousValues) {
      if (value === undefined) delete process.env[key]
      else process.env[key] = value
    }
  }
})
