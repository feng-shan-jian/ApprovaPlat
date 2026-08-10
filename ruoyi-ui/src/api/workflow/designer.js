import request from '@/utils/request'

/**
 * 查询当前正式用户的设计器偏好。
 * @returns {Promise<object>} data 为服务端偏好或服务端默认值。
 */
export function getDesignerPreference() {
  return request({ url: '/workflow/designer/preference', method: 'get' })
}

/**
 * 原子保存当前正式用户的完整设计器偏好。
 * @param {object} data 主题、网格、小地图、Lint、模拟和属性面板状态。
 * @returns {Promise<object>} data 为数据库回读后的真实偏好。
 */
export function saveDesignerPreference(data) {
  return request({ url: '/workflow/designer/preference', method: 'put', data })
}
