import axios from 'axios'
import { ElNotification , ElMessageBox, ElMessage, ElLoading } from 'element-plus'
import { getToken } from '@/utils/auth'
import errorCode from '@/utils/errorCode'
import { tansParams, blobValidate } from '@/utils/ruoyi'
import cache from '@/plugins/cache'
import { saveAs } from 'file-saver'
import useUserStore from '@/store/modules/user'

let downloadLoadingInstance
// 是否显示重新登录
export let isRelogin = { show: false }

/**
 * 创建保留后端稳定业务码和安全子码的前端错误，供页面区分状态与具体并发分支。
 * @param {number|string} code 后端 AjaxResult 返回的业务状态码。
 * @param {string} message 经统一错误码表规范后的用户提示。
 * @param {unknown} subCode 后端可选的稳定机器子码，只接受大写字母、数字和下划线。
 * @returns {Error & {code: number, subCode?: string}} 不携带响应正文且可按 code/subCode 精确处理的错误。
 */
function createBusinessError(code, message, subCode) {
  const error = new Error(message)
  error.name = 'BusinessError'
  error.code = Number(code)
  // 子码只保留有限机器字符，禁止把任意响应正文或诊断信息带入页面错误对象。
  const normalizedSubCode = typeof subCode === 'string' ? subCode.trim() : ''
  if (/^[A-Z][A-Z0-9_]{0,63}$/.test(normalizedSubCode)) error.subCode = normalizedSubCode
  return error
}

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'
// 创建axios实例
const service = axios.create({
  // axios中请求配置有baseURL选项，表示请求URL公共部分
  baseURL: import.meta.env.VITE_APP_BASE_API,
  // 超时
  timeout: 10000
})

// request拦截器
service.interceptors.request.use(config => {
  // 是否需要设置 token
  const isToken = (config.headers || {}).isToken === false
  // 是否需要防止数据重复提交
  const isRepeatSubmit = (config.headers || {}).repeatSubmit === false
  // 间隔时间(ms)，小于此时间视为重复提交
  const interval = (config.headers || {}).interval || 1000
  if (getToken() && !isToken) {
    config.headers['Authorization'] = 'Bearer ' + getToken() // 让每个请求携带自定义token 请根据实际情况自行修改
  }
  // get请求映射params参数
  if (config.method === 'get' && config.params) {
    let url = config.url + '?' + tansParams(config.params)
    url = url.slice(0, -1)
    config.params = {}
    config.url = url
  }
  if (!isRepeatSubmit && (config.method === 'post' || config.method === 'put')) {
    const requestObj = {
      url: config.url,
      data: typeof config.data === 'object' ? JSON.stringify(config.data) : config.data,
      time: new Date().getTime()
    }
    const requestSize = Object.keys(JSON.stringify(requestObj)).length // 请求数据大小
    const limitSize = 5 * 1024 * 1024 // 限制存放数据5M
    if (requestSize >= limitSize) {
      console.warn(`[${config.url}]: ` + '请求数据大小超出允许的5M限制，无法进行防重复提交验证。')
      return config
    }
    const sessionObj = cache.session.getJSON('sessionObj')
    if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
      cache.session.setJSON('sessionObj', requestObj)
    } else {
      const s_url = sessionObj.url                // 请求地址
      const s_data = sessionObj.data              // 请求数据
      const s_time = sessionObj.time              // 请求时间
      if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
        const message = '数据正在处理，请勿重复提交'
        console.warn(`[${s_url}]: ` + message)
        return Promise.reject(new Error(message))
      } else {
        cache.session.setJSON('sessionObj', requestObj)
      }
    }
  }
  return config
}, error => {
    // 请求构造失败时只保留可展示语义和“是否由页面提示”，严禁继续传播含 SMTP 表单的 Axios config。
    const safeError = new Error('请求发送失败')
    safeError.name = 'RequestError'
    safeError.suppressErrorMessage = error?.config?.suppressErrorMessage === true
    return Promise.reject(safeError)
})

/**
 * 统一解析 Axios 响应；成功时返回 AjaxResult，失败时保留稳定业务码并拒绝 Promise。
 * @param {import('axios').AxiosResponse} res 后端 HTTP 响应。
 * @returns {Promise<object>|object} 成功业务数据，或包含业务码的拒绝 Promise。
 */
service.interceptors.response.use(res => {
    // 未设置状态码则默认成功状态
    const code = res.data.code || 200
    // 局部业务流程会按稳定 subCode 输出更准确提示，统一层只负责构造安全错误对象。
    const suppressErrorMessage = res.config?.suppressErrorMessage === true
    // 获取错误信息
    const msg = errorCode[code] || res.data.msg || errorCode['default']
    // 二进制数据则直接返回
    if (res.request.responseType ===  'blob' || res.request.responseType ===  'arraybuffer') {
      return res.data
    }
    if (code === 401) {
      if (!isRelogin.show) {
        isRelogin.show = true
        ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', { confirmButtonText: '重新登录', cancelButtonText: '取消', type: 'warning' }).then(() => {
          isRelogin.show = false
          useUserStore().logOut().then(() => {
            location.href = '/index'
          })
      }).catch(() => {
        isRelogin.show = false
      })
    }
      return Promise.reject('无效的会话，或者会话已过期，请重新登录。')
    } else if (code === 500) {
      if (!suppressErrorMessage) ElMessage({ message: msg, type: 'error' })
      return Promise.reject(createBusinessError(code, msg, res.data.subCode))
    } else if (code === 601) {
      if (!suppressErrorMessage) ElMessage({ message: msg, type: 'warning' })
      return Promise.reject(createBusinessError(code, msg, res.data.subCode))
    } else if (code !== 200) {
      if (!suppressErrorMessage) ElNotification.error({ title: msg })
      return Promise.reject(createBusinessError(code, msg, res.data.subCode))
    } else {
      return  Promise.resolve(res.data)
    }
  },
  error => {
    const responseData = error?.response?.data
    const responseCode = Number(responseData?.code || error?.response?.status)
    const suppressErrorMessage = error?.suppressErrorMessage === true || error?.config?.suppressErrorMessage === true
    if (responseData && Number.isFinite(responseCode)) {
      // 真实非 2xx 响应仍使用后端安全 AjaxResult，并丢弃可能携带请求体的 AxiosError。
      const backendMessage = typeof responseData.msg === 'string' ? responseData.msg.trim() : ''
      const message = (backendMessage || errorCode[responseCode] || errorCode.default).slice(0, 180)
      if (!suppressErrorMessage) ElMessage({ message, type: 'error', duration: 5 * 1000 })
      return Promise.reject(createBusinessError(responseCode, message, responseData.subCode))
    }
    let message = typeof error?.message === 'string' ? error.message : errorCode.default
    if (message == "Network Error") {
      message = "后端接口连接异常"
    } else if (message.includes("timeout")) {
      message = "系统接口请求超时"
    } else if (message.includes("Request failed with status code")) {
      message = "系统接口" + message.slice(-3) + "异常"
    }
    if (!suppressErrorMessage) ElMessage({ message, type: 'error', duration: 5 * 1000 })
    // 不把包含原请求配置的 AxiosError 传给页面，避免授权码经控制台或组件意外输出。
    const safeError = new Error(message)
    safeError.name = 'RequestError'
    return Promise.reject(safeError)
  }
)

// 通用下载方法
export function download(url, params, filename, config) {
  downloadLoadingInstance = ElLoading.service({ text: "正在下载数据，请稍候", background: "rgba(0, 0, 0, 0.7)", })
  return service.post(url, params, {
    transformRequest: [(params) => { return tansParams(params) }],
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    responseType: 'blob',
    ...config
  }).then(async (data) => {
    const isBlob = blobValidate(data)
    if (isBlob) {
      const blob = new Blob([data])
      saveAs(blob, filename)
    } else {
      const resText = await data.text()
      const rspObj = JSON.parse(resText)
      const errMsg = errorCode[rspObj.code] || rspObj.msg || errorCode['default']
      ElMessage.error(errMsg)
    }
    downloadLoadingInstance.close()
  }).catch((r) => {
    console.error(r)
    ElMessage.error('下载文件出现错误，请联系管理员！')
    downloadLoadingInstance.close()
  })
}

export default service
