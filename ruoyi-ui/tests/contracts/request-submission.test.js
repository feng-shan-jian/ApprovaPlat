import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { setImmediate } from 'node:timers/promises'
import test from 'node:test'
import axios from 'axios'
import errorCode from '../../src/utils/errorCode.js'
import { tansParams } from '../../src/utils/ruoyi.js'
import { createBusinessError, createHttpBusinessError, createSafeRequestError } from '../../src/utils/requestError.js'

const duplicateMessage = '数据正在处理，请勿重复提交'
const secret = 'synthetic-credential-for-contract-test'
const source = path => readFileSync(new URL(path, import.meta.url), 'utf8')
const withoutImports = text => text.replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gm, '')

function storage() {
  const values = new Map()
  const writes = []
  return {
    writes,
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { writes.push([key, value]); values.set(key, String(value)) },
    removeItem: key => values.delete(key)
  }
}

// 执行生产模块及真实 Axios 请求链，仅替换浏览器 UI、Storage 与网络 adapter。
function client(t) {
  const sessionStorage = storage()
  const messages = []
  const confirmations = []
  const logs = []
  const dependencies = {
    axios, errorCode, tansParams, sessionStorage,
    createBusinessError, createHttpBusinessError, createSafeRequestError,
    getToken: () => 'synthetic-token',
    ElMessage: Object.assign(value => messages.push(value), { error: value => messages.push(value) }),
    ElNotification: { error: value => messages.push(value) },
    ElMessageBox: { confirm: (...args) => { confirmations.push(args); return Promise.reject(new Error('dismiss')) } },
    useUserStore: () => ({ logOut: () => Promise.resolve() }),
    console: { warn: (...args) => logs.push(args), error: (...args) => logs.push(args) }
  }
  const moduleSource = withoutImports(source('../../src/utils/request.js'))
    .replace('import.meta.env.VITE_APP_BASE_API', "'/dev-api'")
    .replace(/export (let|function) /g, '$1 ')
    .replace('export default service', 'return service')
  const service = new Function(...Object.keys(dependencies), moduleSource)(...Object.values(dependencies))
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
  return { service, calls, send, sessionStorage, messages, confirmations, logs }
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

test('obsolete list business and network errors never display after a newer request owns the page', async t => {
  const state = client(t)
  let current = true
  const staleBusiness = await state.send({ url: '/list', method: 'get', isCurrentRequest: () => current })
  const staleNetwork = await state.send({ url: '/list', method: 'get', isCurrentRequest: () => current })
  current = false
  state.calls[0].resolve({ code: 500, msg: '过期查询失败' })
  state.calls[1].reject(new axios.AxiosError('Network Error', undefined, state.calls[1].config))
  assert.ok((await staleBusiness.result).error)
  assert.ok((await staleNetwork.result).error)
  assert.deepEqual(state.messages, [])
  const latest = await state.send({ url: '/list', method: 'get', isCurrentRequest: () => true })
  state.calls[2].resolve({ code: 500, msg: '当前查询失败' })
  assert.ok((await latest.result).error)
  assert.equal(state.messages.length, 1)
})

test('current session expiry prompts even for silent business requests, while obsolete expiry stays silent', async t => {
  for (const current of [undefined, true, false]) {
    for (const suppressErrorMessage of [true, false]) {
      const state = client(t)
      const operation = await state.send({ url: '/session', method: 'get', suppressErrorMessage,
        ...(current === undefined ? {} : { isCurrentRequest: () => current }) })
      state.calls[0].resolve({ code: 401 })
      assert.match((await operation.result).error, /会话已过期/)
      assert.equal(state.confirmations.length, current === false ? 0 : 1,
        `current=${current}, suppressErrorMessage=${suppressErrorMessage}`)
      assert.deepEqual(state.messages, [])
    }
  }
})

test('business silence still suppresses current non-authentication errors', async t => {
  for (const code of [500, 601, 403]) {
    const state = client(t)
    const operation = await state.send({ url: '/business', method: 'get', suppressErrorMessage: true, isCurrentRequest: () => true })
    state.calls[0].resolve({ code, msg: '页面负责展示' })
    assert.ok((await operation.result).error)
    assert.deepEqual(state.messages, [])
    assert.deepEqual(state.confirmations, [])
  }
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
