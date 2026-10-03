import { expect, test } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { connect } from 'node:net'

test.describe.configure({ mode: 'serial' })
const prefix = `b16_${Date.now()}`
let admin, context, previousCaptcha
const users = [], roles = [], menus = [], categories = [], forms = [], models = []
const databaseUrl = new URL((process.env.WORKFLOW_MYSQL_TEST_URL || 'jdbc:mysql://127.0.0.1/unconfigured').replace(/^jdbc:/, ''))
const database = databaseUrl.pathname.slice(1)
const quote = value => `CONVERT(0x${Buffer.from(String(value)).toString('hex')} USING utf8mb4) COLLATE utf8mb4_unicode_ci`

function sql(query) {
  if (!database.endsWith('_it')) throw new Error('规则真实验收需要已初始化的独立 _it 库')
  return execFileSync(process.env.WORKFLOW_MYSQL_CLIENT || (process.platform === 'win32'
    ? 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe' : 'mysql'), [
    '-h', databaseUrl.hostname, '-P', databaseUrl.port || '3306', '-u', process.env.WORKFLOW_MYSQL_TEST_USERNAME,
    `--database=${database}`, '--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4', '-e', query
  ], { encoding: 'utf8', env: { ...process.env, MYSQL_PWD: process.env.WORKFLOW_MYSQL_TEST_PASSWORD } }).trim()
}

async function clearCaptchaCache() {
  await new Promise((resolve, reject) => {
    const socket = connect({ host: '127.0.0.1', port: Number(process.env.WORKFLOW_E2E_REDIS_PORT) })
    socket.setTimeout(3000, () => socket.destroy(new Error('Redis timed out')))
    socket.on('error', reject)
    socket.on('connect', () => socket.write('*2\r\n$3\r\nDEL\r\n$37\r\nsys_config:sys.account.captchaEnabled\r\n'))
    socket.once('data', data => { socket.end(); data[0] === 45 ? reject(new Error('Redis DEL failed')) : resolve() })
  })
}

async function api(page, path, method = 'GET', body, expected = 200) {
  const result = await page.evaluate(async ({ path, method, body }) => {
    const token = document.cookie.split('; ').find(value => value.startsWith('Admin-Token='))?.slice(12)
    const response = await fetch(`/dev-api${path}`, { method,
      headers: { Authorization: `Bearer ${decodeURIComponent(token || '')}`, 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
    return response.json()
  }, { path, method, body })
  expect(result.code, `${method} ${path}: ${result.msg}`).toBe(expected)
  return result
}

async function login(page, username, password) {
  await page.goto('/login?redirect=/index')
  await page.getByRole('textbox', { name: '账号' }).fill(username)
  await page.getByRole('textbox', { name: '密码' }).fill(password)
  const [response] = await Promise.all([
    page.waitForResponse(value => new URL(value.url()).pathname === '/dev-api/login' && value.request().method() === 'POST'),
    page.getByRole('button', { name: '登录' }).click()
  ])
  expect((await response.json()).code).toBe(200)
  await expect(page).toHaveURL(/\/index/)
}

test.beforeAll(async ({ browser }) => {
  expect(process.env.WORKFLOW_E2E_USERNAME && process.env.WORKFLOW_E2E_PASSWORD, '必须设置真实验收登录环境变量').toBeTruthy()
  previousCaptcha = sql("SELECT config_value FROM sys_config WHERE config_key='sys.account.captchaEnabled'")
  sql("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'")
  await clearCaptchaCache()
  context = await browser.newContext({ baseURL: test.info().project.use.baseURL })
  admin = await context.newPage()
  await login(admin, process.env.WORKFLOW_E2E_USERNAME, process.env.WORKFLOW_E2E_PASSWORD)
})

test.afterAll(async () => {
  const errors = []
  const clean = async operation => { try { await operation() } catch (error) { errors.push(error.message) } }
  for (const id of models) await clean(() => api(admin, `/workflow/model/${id}`, 'DELETE'))
  for (const id of forms) await clean(() => api(admin, `/workflow/form/${id}`, 'DELETE'))
  for (const id of categories) await clean(() => api(admin, `/workflow/category/${id}`, 'DELETE'))
  for (const id of users) await clean(async () => {
    if (sql(`SELECT del_flag FROM sys_user WHERE user_id=${id}`) === '0') await api(admin, `/system/user/${id}`, 'DELETE')
  })
  for (const id of roles) await clean(() => api(admin, `/system/role/${id}`, 'DELETE'))
  for (const id of menus) await clean(() => api(admin, `/system/menu/${id}`, 'DELETE'))
  if (previousCaptcha !== undefined) await clean(async () => {
    sql(`UPDATE sys_config SET config_value=${quote(previousCaptcha)} WHERE config_key='sys.account.captchaEnabled'`)
    await clearCaptchaCache()
  })
  await context?.close()
  expect(errors, '合成资源清理必须成功').toEqual([])
})

test('B01 两个真实登录会话的系统权限、数据范围和 getInfo 同步生效', async ({ browser }) => {
  test.setTimeout(120000)
  const menu = { menuName: `${prefix}权限`, parentId: 0, orderNum: 0, path: '', component: '', menuType: 'F',
    isFrame: '1', isCache: '0', visible: '0', status: '0', perms: 'system:user:list' }
  await api(admin, '/system/menu', 'POST', menu)
  menu.menuId = Number(sql(`SELECT menu_id FROM sys_menu WHERE menu_name=${quote(menu.menuName)}`)); menus.push(menu.menuId)
  const role = { roleName: `${prefix}角色`, roleKey: prefix, roleSort: 1, status: '0', dataScope: '1',
    menuCheckStrictly: true, deptCheckStrictly: true, menuIds: [menu.menuId], deptIds: [] }
  await api(admin, '/system/role', 'POST', role)
  role.roleId = Number(sql(`SELECT role_id FROM sys_role WHERE role_key=${quote(prefix)}`)); roles.push(role.roleId)
  const user = { userName: `${prefix}u`, nickName: '规则验收用户', password: 'Rule-test!28', status: '0', deptId: 100, roleIds: [role.roleId], postIds: [] }
  await api(admin, '/system/user', 'POST', user)
  user.userId = Number(sql(`SELECT user_id FROM sys_user WHERE user_name=${quote(user.userName)}`)); users.push(user.userId)
  const sessions = await Promise.all([browser.newContext({ baseURL: test.info().project.use.baseURL }),
    browser.newContext({ baseURL: test.info().project.use.baseURL })])
  try {
    const pages = await Promise.all(sessions.map(async session => {
      const page = await session.newPage(); await login(page, user.userName, user.password); return page
    }))
    for (const page of pages) expect((await api(page, '/system/user/list')).total).toBeGreaterThan(1)
    await api(admin, '/system/role/dataScope', 'PUT', { ...role, dataScope: '5' })
    for (const page of pages) {
      const list = await api(page, '/system/user/list'); expect(list.rows.map(row => row.userId)).toEqual([user.userId])
      const info = await api(page, '/getInfo'); expect(info.permissions).toContain('system:user:list')
      expect(info.user.roles.find(value => value.roleId === role.roleId).dataScope).toBe('5')
    }
    await api(admin, '/system/menu', 'PUT', { ...menu, perms: 'system:user:query' })
    for (const page of pages) {
      await api(page, '/system/user/list', 'GET', undefined, 403)
      expect((await api(page, '/getInfo')).permissions).toEqual(['system:user:query'])
    }
    await api(admin, '/system/menu', 'PUT', menu)
    await api(admin, '/system/role/changeStatus', 'PUT', { roleId: role.roleId, status: '1' })
    for (const page of pages) await api(page, '/system/user/list', 'GET', undefined, 403)
    await api(admin, '/system/role/changeStatus', 'PUT', { roleId: role.roleId, status: '0' })
    await api(admin, '/system/role/authUser/cancel', 'PUT', { roleId: role.roleId, userId: user.userId })
    for (const page of pages) await api(page, '/system/user/list', 'GET', undefined, 403)
    await api(admin, `/system/role/authUser/selectAll?roleId=${role.roleId}&userIds=${user.userId}`, 'PUT')
    for (const page of pages) expect((await api(page, '/system/user/list')).rows.map(row => row.userId)).toEqual([user.userId])
    await api(admin, '/system/user/changeStatus', 'PUT', { userId: user.userId, status: '1' })
    for (const page of pages) await api(page, '/getInfo', 'GET', undefined, 401)
    await api(admin, '/system/user/changeStatus', 'PUT', { userId: user.userId, status: '0' })
    await sessions[0].clearCookies()
    const renewed = await sessions[0].newPage(); await login(renewed, user.userName, user.password)
    await api(admin, `/system/user/${user.userId}`, 'DELETE')
    await api(renewed, '/getInfo', 'GET', undefined, 401)
  } finally { await Promise.all(sessions.map(session => session.close())) }
})

test('B02/B17 直接 API 改码被拒绝，非法作者变量在保存阶段被拒绝', async () => {
  const category = { code: `${prefix}_cat`, categoryName: `${prefix}分类` }
  await api(admin, '/workflow/category', 'POST', category)
  category.categoryId = (await api(admin, `/workflow/category/list?code=${category.code}`)).rows[0].categoryId
  categories.push(category.categoryId)
  const field = variable => ({ layout: 'colFormItem', tag: 'el-input', vModel: variable, workflowHidden: false, workflowReadable: true, workflowWritable: true })
  for (const variable of ['initiator', 'has space', ' name', 42])
    await api(admin, '/workflow/form', 'POST', { formName: `${prefix}非法`, content: JSON.stringify({ fields: [field(variable)] }) }, 400)
  await api(admin, '/workflow/form', 'POST', { formName: `${prefix}重复`, content: JSON.stringify({ fields: [field('name'), field('name')] }) }, 400)
  const form = { formName: `${prefix}合法`, content: JSON.stringify({ fields: [field('name')] }) }
  form.formId = (await api(admin, '/workflow/form', 'POST', form)).data.formId; forms.push(form.formId)
  const model = (await api(admin, '/workflow/model', 'POST', { modelName: `${prefix}模型`, modelKey: `${prefix}_model`,
    category: category.code, formType: 0, formId: form.formId })).data
  models.push(model.modelId)
  await api(admin, '/workflow/category', 'PUT', { ...category, code: `${category.code}_changed` }, 409)
  expect((await api(admin, `/workflow/category/${category.categoryId}`)).data.code).toBe(category.code)
  await api(admin, '/workflow/category', 'PUT', { ...category, categoryName: `${prefix}改名`, remark: 'still editable' })
  await api(admin, `/workflow/category/${category.categoryId}`, 'DELETE', undefined, 409)
  await api(admin, `/workflow/form/${form.formId}`, 'DELETE', undefined, 409)
})
