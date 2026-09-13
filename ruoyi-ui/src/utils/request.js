import axios from 'axios'
import { ElNotification , ElMessageBox, ElMessage, ElLoading } from 'element-plus'
import { getToken } from '@/utils/auth'
import errorCode from '@/utils/errorCode'
import { tansParams, blobValidate } from '@/utils/ruoyi'
import { saveAs } from 'file-saver'
import useUserStore from '@/store/modules/user'
import {
  createBusinessError,
  createHttpBusinessError,
  createSafeRequestError
} from '@/utils/requestError'

let downloadLoadingInstance
// 是否显示重新登录
export let isRelogin = { show: false }

// 清除旧版持久化的敏感请求正文；浏览器禁用存储时仍允许正常请求。
try {
  sessionStorage.removeItem('sessionObj')
} catch {
  // 存储不可访问时不读取、不复制，也不输出其中的历史数据。
}

// 仅由各自正在执行的 adapter 持有，完成或失败后立即释放。
const pendingSubmissions = new Set()

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
    const adapter = axios.getAdapter(config.adapter)
    config.adapter = async request => {
      // Axios 已完成请求转换和预先取消检查；FormData 固定字段快照并保留文件身份，
      // 避免 JSON.stringify 丢失文件内容，也不因后续 append 改变在途请求描述。
      const body = request.data instanceof FormData ? Array.from(request.data.entries()).flat() : [request.data]
      const submission = [request.method, service.getUri(request), request.headers.getContentType(),
        request.headers.get('Authorization'), ...body]
      for (const pending of pendingSubmissions) {
        if (pending.length === submission.length && pending.every((value, index) => value === submission[index])) {
          throw createSafeRequestError(request, '数据正在处理，请勿重复提交', true)
        }
      }
      pendingSubmissions.add(submission)
      try {
        return await adapter(request)
      } finally {
        // 只释放当前 owner；重复请求在占位前拒绝，不能误释放首个请求。
        pendingSubmissions.delete(submission)
      }
    }
  }
  return config
}, error => {
    // 请求构造失败时只保留可展示语义和“是否由页面提示”，严禁继续传播含 SMTP 表单的 Axios config。
    return Promise.reject(createSafeRequestError(error, '请求发送失败', true))
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
    const suppressErrorMessage = error?.suppressErrorMessage === true || error?.config?.suppressErrorMessage === true
    const businessError = createHttpBusinessError(responseData, error?.response?.status, errorCode)
    if (businessError) {
      // 真实非 2xx 响应仍使用后端安全 AjaxResult，并丢弃可能携带请求体的 AxiosError。
      if (!suppressErrorMessage) ElMessage({ message: businessError.message, type: 'error', duration: 5 * 1000 })
      return Promise.reject(businessError)
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
    return Promise.reject(createSafeRequestError(error, message))
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
