import request from '@/utils/request'
import axios from 'axios'

/**
 * 分页查询脱敏运行事件台账。
 * @param {object} query 分页、状态、类型、来源和时间筛选。
 * @returns {Promise<object>} 若依 rows/total 分页结果。
 */
export function listRuntimeEvents(query) {
  return request({ url: '/workflow/runtime-event-audit/list', method: 'get', params: query })
}

/** 外部凭据独立鉴权；不写请求缓存，也不把凭据错误解释为后台登录失效。 */
export async function submitRuntimeEvent(type, token, data) {
  if (!['MESSAGE', 'SIGNAL', 'RECEIVE'].includes(type)) throw new Error('运行事件类型不支持')
  try {
    const response = await axios.post(`${import.meta.env.VITE_APP_BASE_API}/workflow/runtime-event/${type.toLowerCase()}`, data, {
      headers: { 'X-Integration-Token': token }, timeout: 15000, validateStatus: () => true
    })
    if (response.status !== 200 || response.data?.code !== 200) {
      throw new Error(response.data?.msg || '事件请求未被接受')
    }
    return response.data.data
  } catch (failure) {
    // 仅向调用方传播文本，Axios config 中的凭据不进入错误展示或日志。
    throw new Error(axios.isAxiosError(failure) ? '事件请求未完成，可保留请求标识核查或重试' : failure.message)
  }
}
