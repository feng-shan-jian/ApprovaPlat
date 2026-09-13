import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setImmediate, setTimeout } from 'node:timers/promises'
import test from 'node:test'
import axios from 'axios'
import errorCode from '../../src/utils/errorCode.js'
import { blobValidate, parseStrEmpty, tansParams } from '../../src/utils/ruoyi.js'
import { createBusinessError, createHttpBusinessError, createSafeRequestError } from '../../src/utils/requestError.js'

const duplicateMessage = '数据正在处理，请勿重复提交'
const secret = 'synthetic-credential-for-contract-test'
const source = path => readFileSync(new URL(path, import.meta.url), 'utf8')
const withoutImports = text => text.replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gm, '')

function storage(initial = {}) {
  const values = new Map(Object.entries(initial))
  const writes = []
  return {
    values,
    writes,
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { writes.push([key, value]); values.set(key, String(value)) },
    removeItem: key => values.delete(key)
  }
}

// 执行生产模块及真实 Axios 请求链，仅替换浏览器 UI、Storage 与网络 adapter。
function client(t, initialSession = {}, storageAvailable = true) {
  const sessionStorage = storage(initialSession)
  if (!storageAvailable) sessionStorage.removeItem = () => { throw new Error('Storage disabled') }
  const localStorage = storage({ preference: 'keep-local' })
  const cache = new Function('sessionStorage', 'localStorage',
    source('../../src/plugins/cache.js').replace('export default', 'return'))(sessionStorage, localStorage)
  const messages = []
  const logs = []
  const downloads = []
  let loadingClosed = 0
  const dependencies = {
    axios, errorCode, tansParams, blobValidate, cache, sessionStorage, localStorage,
    createBusinessError, createHttpBusinessError, createSafeRequestError,
    getToken: () => 'synthetic-token',
    ElMessage: Object.assign(value => messages.push(value), { error: value => messages.push(value) }),
    ElNotification: { error: value => messages.push(value) },
    ElMessageBox: { confirm: () => Promise.reject(new Error('dismiss')) },
    ElLoading: { service: () => ({ close: () => loadingClosed++ }) },
    saveAs: (blob, filename) => downloads.push({ blob, filename }),
    useUserStore: () => ({ logOut: () => Promise.resolve() }),
    console: { warn: (...args) => logs.push(args), error: (...args) => logs.push(args) }
  }
  const moduleSource = withoutImports(source('../../src/utils/request.js'))
    .replace('import.meta.env.VITE_APP_BASE_API', "'/dev-api'")
    .replace(/export (let|function) /g, '$1 ')
    .replace('export default service', 'return { service, download }')
  const { service, download } = new Function(...Object.keys(dependencies), moduleSource)(...Object.values(dependencies))
  const calls = []
  service.defaults.adapter = config => new Promise((resolve, reject) => {
    const onCancel = reason => reject(axios.isCancel(reason) ? reason : new axios.CanceledError('canceled', config))
    config.signal?.addEventListener('abort', onCancel, { once: true })
    config.cancelToken?.subscribe(onCancel)
    const cleanup = () => {
      config.signal?.removeEventListener('abort', onCancel)
      config.cancelToken?.unsubscribe(onCancel)
    }
    calls.push({
      config,
      resolve: (data = { code: 200 }) => {
        cleanup()
        resolve({ data, status: 200, headers: {}, config, request: { responseType: config.responseType } })
      },
      reject: error => { cleanup(); reject(error) }
    })
  })
  t.after(() => calls.forEach(call => call.resolve()))
  const send = async config => {
    const result = service(config).then(data => ({ data }), error => ({ error }))
    await setImmediate()
    return { result }
  }
  const api = (path, names) => new Function('request', 'parseStrEmpty',
    withoutImports(source(path)).replace(/export function /g, 'function ') + `\nreturn { ${names.join(', ')} }`)(service, parseStrEmpty)
  return { service, download, calls, send, api, sessionStorage, localStorage, messages, logs, downloads,
    get loadingClosed() { return loadingClosed } }
}

async function rejected(operation, expected = duplicateMessage) {
  const outcome = await Promise.race([operation.result, setImmediate().then(() => null)])
  assert.ok(outcome, '应在 adapter 发出下一次请求前拒绝重复提交')
  const { error } = outcome
  assert.ok(error instanceof Error)
  assert.equal(error.message, expected)
  for (const key of ['config', 'request', 'response', 'data', 'cause']) assert.equal(Object.hasOwn(error, key), false)
  assert.equal(JSON.stringify(error).includes(secret), false)
  return error
}

test('扩展检查和删除的业务、HTTP、网络错误仅交给所属页面提示', async t => {
  const state = client(t)
  const api = state.api('../../src/api/workflow/extension.js', ['checkWorkflowExtensionDeletion', 'removeWorkflowExtension'])
  for (const operation of Object.values(api)) {
    for (const failure of ['business', 'http', 'network']) {
      const result = operation(7).then(() => null, error => error)
      await setImmediate()
      const call = state.calls.at(-1)
      assert.equal(call.config.suppressErrorMessage, true)
      if (failure === 'business') call.resolve({ code: 409, msg: '目录已被引用' })
      else call.reject(new axios.AxiosError('Network Error', undefined, call.config, null,
        failure === 'http' ? { status: 500, data: { code: 500, msg: '资源损坏' } } : undefined))
      assert.ok(await result instanceof Error)
      assert.deepEqual(state.messages, [])
    }
  }
})

test('启动立即清除旧请求缓存，真实密码、注册、解锁和密钥 API 不写任何 Storage', async t => {
  const state = client(t, { sessionObj: JSON.stringify({ data: secret }), preference: 'keep-session' })
  assert.equal(state.sessionStorage.getItem('sessionObj'), null)
  assert.equal(state.sessionStorage.getItem('preference'), 'keep-session')
  const login = state.api('../../src/api/login.js', ['login', 'register', 'unlockScreen'])
  const user = state.api('../../src/api/system/user.js', ['resetUserPwd', 'updateUserPwd'])
  const oss = state.api('../../src/api/system/oss.js', ['addOssConfig'])
  const sms = state.api('../../src/api/system/sms.js', ['updateSmsConfig'])
  const operations = [
    login.login('synthetic-user', secret, 'test-code', 'test-uuid'),
    login.register({ username: 'synthetic-user', password: secret }),
    login.unlockScreen(secret), user.resetUserPwd(123, secret), user.updateUserPwd(secret, secret),
    oss.addOssConfig({ accessKey: secret, secretKey: secret }),
    sms.updateSmsConfig({ accessKeyId: secret, accessKeySecret: secret })
  ]
  await setImmediate()
  assert.equal(state.calls.length, operations.length)
  assert.ok(state.calls.every(call => call.config.data.includes(secret)))
  state.calls.forEach(call => call.resolve())
  await Promise.all(operations)
  assert.deepEqual(state.sessionStorage.writes, [])
  assert.deepEqual(state.localStorage.writes, [])
  assert.deepEqual([...state.localStorage.values], [['preference', 'keep-local']])
  assert.equal(JSON.stringify([state.messages, state.logs]).includes(secret), false)
})

test('A/B/A 并发按各自 owner 防重，重复失败及 A 完成不能释放 B', async t => {
  const state = client(t)
  const a = { url: '/save', method: 'post', data: { password: secret } }
  const b = { ...a, data: { password: secret, id: 2 } }
  const firstA = await state.send(a)
  const firstB = await state.send(b)
  await rejected(await state.send(a))
  await rejected(await state.send(a))
  await rejected(await state.send(b))
  assert.equal(state.calls.length, 2)
  state.calls[0].resolve()
  assert.deepEqual(await firstA.result, { data: { code: 200 } })
  const nextA = await state.send(a)
  assert.equal(state.calls.length, 3)
  await rejected(await state.send(b))
  state.calls[1].resolve()
  state.calls[2].resolve()
  await Promise.all([firstB.result, nextA.result])
  assert.deepEqual(state.sessionStorage.writes, [])
  assert.equal(JSON.stringify([state.messages, state.logs]).includes(secret), false)
})

test('在途保护超过旧时间窗仍生效，完成后立即允许下一次提交', async t => {
  const state = client(t)
  const config = { url: '/slow-save', method: 'put', data: { id: 1 } }
  const first = await state.send(config)
  await setTimeout(1050)
  await rejected(await state.send(config))
  assert.equal(state.calls.length, 1)
  state.calls[0].resolve()
  await first.result
  const retry = await state.send(config)
  assert.equal(state.calls.length, 2)
  state.calls[1].resolve()
  assert.ok((await retry.result).data)
})

test('请求方法、完整 URL、查询参数、正文和编码不同的操作可独立并发', async t => {
  const state = client(t)
  const base = { url: '/save', method: 'post', data: 'same' }
  const configs = [base, { ...base, method: 'put' }, { ...base, url: '/save-other' },
    { ...base, baseURL: '/another-api' }, { ...base, params: { id: 1 } },
    { ...base, params: { id: 2 } }, { ...base, data: 'different' },
    { ...base, headers: { 'Content-Type': 'text/plain' } },
    { ...base, headers: { isToken: false, Authorization: 'Bearer another-synthetic-user' } }]
  const operations = []
  for (const config of configs) operations.push(await state.send(config))
  assert.equal(state.calls.length, configs.length)
  for (const config of configs) await rejected(await state.send(config))
  state.calls.forEach(call => call.resolve())
  await Promise.all(operations.map(operation => operation.result))
})

test('multipart 区分同名同尺寸不同内容的文件及字段，重建表单复用同一文件仍防重', async t => {
  const state = client(t)
  const firstFile = new File(['AAA'], 'same.txt', { type: 'text/plain', lastModified: 1 })
  const otherFile = new File(['BBB'], 'same.txt', { type: 'text/plain', lastModified: 1 })
  const form = (file, category = 'one') => {
    const data = new FormData()
    data.append('file', file)
    data.append('category', category)
    return { url: '/upload', method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' } }
  }
  const config = form(firstFile)
  const first = await state.send(config)
  const other = await state.send(form(otherFile))
  const changedField = await state.send(form(firstFile, 'two'))
  assert.equal(state.calls.length, 3)
  assert.equal(state.calls[0].config.data, config.data)
  await rejected(await state.send(config))
  await rejected(await state.send(form(firstFile)))
  config.data.append('revision', '2')
  const changedForm = await state.send(config)
  assert.equal(state.calls.length, 4)
  await rejected(await state.send(form(firstFile)))
  state.calls.forEach(call => call.resolve())
  await Promise.all([first.result, other.result, changedField.result, changedForm.result])
})

test('存储禁用或旧缓存损坏不阻止请求，内存防重仍生效', async t => {
  for (const available of [true, false]) {
    const state = client(t, { sessionObj: '{invalid-json' }, available)
    const config = { url: '/save', method: 'post', data: { password: secret } }
    const first = await state.send(config)
    assert.equal(state.calls.length, 1)
    await rejected(await state.send(config))
    state.calls[0].resolve()
    assert.ok((await first.result).data)
    assert.deepEqual(state.sessionStorage.writes, [])
    assert.deepEqual(state.localStorage.writes, [])
  }
})

test('超过旧 5MB 限制的在途正文仍防重，敏感查询参数不进入日志或错误', async t => {
  const state = client(t)
  const config = { url: '/large-save', method: 'post', params: { credential: secret },
    data: { payload: 'x'.repeat(5 * 1024 * 1024) }, suppressErrorMessage: true }
  const first = await state.send(config)
  await rejected(await state.send(config))
  assert.equal(state.calls.length, 1)
  assert.deepEqual(state.messages, [])
  assert.deepEqual(state.logs, [])
  state.calls[0].resolve()
  assert.ok((await first.result).data)
})

test('repeatSubmit:false 保留并发放行，绕过请求结束不能解除受保护请求', async t => {
  const state = client(t)
  const config = { url: '/save', method: 'post', data: { password: secret } }
  const protectedRequest = await state.send(config)
  const bypass = { ...config, headers: { repeatSubmit: false, isToken: false } }
  const first = await state.send(bypass)
  const second = await state.send(bypass)
  assert.equal(state.calls.length, 3)
  assert.equal(state.calls[0].config.headers.Authorization, 'Bearer synthetic-token')
  assert.equal(state.calls[1].config.headers.Authorization, undefined)
  assert.equal(state.calls[1].config.headers.repeatSubmit, false)
  state.calls[1].resolve()
  state.calls[2].resolve()
  await Promise.all([first.result, second.result])
  await rejected(await state.send(config))
  state.calls[0].resolve()
  await protectedRequest.result
})

test('业务失败、HTTP 失败和网络失败后立即可重试，错误不带敏感请求配置', async t => {
  for (const failure of ['business', 'http', 'network']) {
    const state = client(t)
    const config = { url: '/secret-save', method: 'put', data: { credential: secret }, suppressErrorMessage: true }
    const failed = await state.send(config)
    const call = state.calls[0]
    if (failure === 'business') call.resolve({ code: 409, msg: '版本冲突', subCode: 'REVISION_CONFLICT' })
    if (failure === 'http') call.reject(new axios.AxiosError('Request failed with status code 409', 'ERR_BAD_REQUEST', call.config, {},
      { status: 409, data: { code: 409, msg: '版本冲突', subCode: 'REVISION_CONFLICT' }, headers: {} }))
    if (failure === 'network') call.reject(new axios.AxiosError('Network Error', 'ERR_NETWORK', call.config, {}))
    const error = await rejected(failed, failure === 'network' ? '后端接口连接异常' : '版本冲突')
    if (failure !== 'network') assert.equal(error.subCode, 'REVISION_CONFLICT')
    assert.deepEqual(state.messages, [])
    const retry = await state.send(config)
    assert.equal(state.calls.length, 2)
    state.calls[1].resolve()
    assert.ok((await retry.result).data)
  }
})

test('AbortSignal 和 CancelToken 的在途取消及预先取消均允许重新提交', async t => {
  for (const mechanism of ['signal', 'cancelToken']) {
    const state = client(t)
    const config = { url: '/cancel-save', method: 'post', data: { id: 1 } }
    const controller = mechanism === 'signal' ? new AbortController() : axios.CancelToken.source()
    const cancellation = mechanism === 'signal' ? { signal: controller.signal } : { cancelToken: controller.token }
    const canceled = await state.send({ ...config, ...cancellation })
    if (mechanism === 'signal') controller.abort()
    else controller.cancel('canceled')
    await rejected(canceled, 'canceled')
    await rejected(await state.send({ ...config, ...cancellation }), 'canceled')
    const retry = await state.send(config)
    assert.equal(state.calls.length, 2)
    await rejected(await state.send(config))
    state.calls[1].resolve()
    assert.ok((await retry.result).data)
  }
})

test('请求转换、响应转换和 adapter 同步异常不遗留锁，不依赖 error.config', async t => {
  for (const failure of ['request-transform', 'response-transform', 'adapter']) {
    const state = client(t)
    const config = { url: '/construction-save', method: 'put', data: { password: secret } }
    const throwing = () => { throw new Error('构造失败') }
    const overrides = failure === 'request-transform' ? { transformRequest: [throwing] }
      : failure === 'response-transform' ? { transformResponse: [throwing] } : { adapter: throwing }
    const failed = await state.send({ ...config, ...overrides })
    if (failure === 'response-transform') state.calls[0].resolve()
    await rejected(failed, '构造失败')
    const count = state.calls.length
    const retry = await state.send(config)
    assert.equal(state.calls.length, count + 1)
    state.calls.at(-1).resolve()
    assert.ok((await retry.result).data)
  }
})

test('GET 参数映射、鉴权和下载转换保持原调用方式', async t => {
  const state = client(t)
  const query = await state.send({ url: '/list', method: 'get', params: { name: 'a b', page: 1 } })
  assert.equal(state.calls[0].config.url, '/list?name=a%20b&page=1')
  assert.deepEqual(state.calls[0].config.params, {})
  state.calls[0].resolve({ code: 200, rows: [] })
  assert.deepEqual(await query.result, { data: { code: 200, rows: [] } })
  const download = state.download('/export', { id: 1 }, 'report.xlsx')
  await setImmediate()
  assert.equal(state.calls[1].config.data, 'id=1&')
  assert.equal(state.calls[1].config.headers.getContentType(), 'application/x-www-form-urlencoded')
  state.calls[1].resolve(new Blob(['report'], { type: 'application/octet-stream' }))
  await download
  assert.equal(state.downloads[0].filename, 'report.xlsx')
  assert.equal(await state.downloads[0].blob.text(), 'report')
  assert.equal(state.loadingClosed, 1)
})
