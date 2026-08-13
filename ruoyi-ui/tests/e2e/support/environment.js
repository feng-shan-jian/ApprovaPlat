const ROLE_ENV_SUFFIX = Object.freeze({
  workflow_admin: 'WORKFLOW_ADMIN',
  workflow_designer: 'WORKFLOW_DESIGNER',
  workflow_starter: 'WORKFLOW_STARTER',
  workflow_approver: 'WORKFLOW_APPROVER',
  workflow_auditor: 'WORKFLOW_AUDITOR'
})

export const WORKFLOW_ROLE_KEYS = Object.freeze(Object.keys(ROLE_ENV_SUFFIX))

// 本地验收账号统一密码，避免测试任务反复生成和重置随机复杂密码。
const WORKFLOW_TEST_ACCOUNT_PASSWORD = 'wang'

/**
 * 读取必填 E2E 环境变量，错误信息只包含变量名，绝不回显凭据值。
 * @param {string} name 环境变量名称。
 * @returns {string} 去除首尾空白后的环境变量值。
 */
function requireEnvironmentValue(name) {
  const value = process.env[name]?.trim()
  if (!value) {
    throw new Error(`缺少强制 E2E 环境变量：${name}`)
  }
  return value
}

/**
 * 加载只用于系统配置恢复和账号状态控制的本地系统管理员凭据。
 * @returns {Readonly<{roleKey:string,username:string,password:string,requiredRoles:string[]}>} 不写入报告的系统管理员登录配置。
 */
export function loadSystemAdminAccount() {
  return Object.freeze({
    roleKey: 'system_admin',
    username: requireEnvironmentValue('FLOWABLE_E2E_ADMIN_USERNAME'),
    password: requireEnvironmentValue('FLOWABLE_E2E_ADMIN_PASSWORD'),
    requiredRoles: Object.freeze(['admin'])
  })
}

/**
 * 加载五个预登记职责分离账号；用户名由环境注入，密码统一使用本地验收口令 wang。
 * @returns {Readonly<Record<string, Readonly<{roleKey: string, username: string, password: string}>>>} 按角色索引的只读登录凭据。
 */
export function loadWorkflowAccounts() {
  const registered = requireEnvironmentValue('FLOWABLE_RBAC_ACCOUNTS_REGISTERED')
  if (registered.toLowerCase() !== 'true') {
    throw new Error('FLOWABLE_RBAC_ACCOUNTS_REGISTERED 必须为 true，确认五账号已在首次使用前登记')
  }

  const accounts = Object.fromEntries(WORKFLOW_ROLE_KEYS.map(roleKey => {
    const suffix = ROLE_ENV_SUFFIX[roleKey]
    return [roleKey, Object.freeze({
      roleKey,
      username: requireEnvironmentValue(`FLOWABLE_RBAC_${suffix}_USERNAME`),
      password: WORKFLOW_TEST_ACCOUNT_PASSWORD
    })]
  }))
  const uniqueUsernames = new Set(Object.values(accounts).map(account => account.username))
  if (uniqueUsernames.size !== WORKFLOW_ROLE_KEYS.length) {
    throw new Error('五角色 E2E 用户名必须互不相同')
  }
  return Object.freeze(accounts)
}
