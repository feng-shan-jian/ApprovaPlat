/** 设计器偏好本地存储协议版本。 */
export const DESIGNER_PREFERENCE_SCHEMA_VERSION = 1

/** 当前版本设计器偏好的稳定默认值。 */
export const DEFAULT_DESIGNER_PREFERENCE = Object.freeze({
  theme: 'SYSTEM',
  gridEnabled: true,
  minimapEnabled: true,
  tokenSimulationEnabled: false,
  propertiesCollapsed: false
})

/**
 * 生成当前用户专属的设计器偏好存储键。
 * @param {string|number} userId 当前登录用户主键。
 * @returns {string} 按协议版本和用户隔离的 localStorage 键。
 */
export function designerPreferenceStorageKey(userId) {
  const normalizedUserId = String(userId ?? '').trim()
  if (!normalizedUserId) throw new TypeError('设计器偏好缺少当前用户主键')
  return `workflow:designer:preference:v1:${normalizedUserId}`
}

/**
 * 按白名单规范化设计器偏好，未知字段不会进入浏览器持久化。
 * @param {object|undefined|null} preference 待保存或恢复的偏好对象。
 * @returns {object} 仅包含六个公开偏好字段的新对象。
 */
export function normalizeDesignerPreference(preference) {
  const defaults = DEFAULT_DESIGNER_PREFERENCE
  return {
    theme: ['LIGHT', 'DARK', 'SYSTEM'].includes(preference?.theme)
      ? preference.theme : defaults.theme,
    gridEnabled: typeof preference?.gridEnabled === 'boolean'
      ? preference.gridEnabled : defaults.gridEnabled,
    minimapEnabled: typeof preference?.minimapEnabled === 'boolean'
      ? preference.minimapEnabled : defaults.minimapEnabled,
    tokenSimulationEnabled: typeof preference?.tokenSimulationEnabled === 'boolean'
      ? preference.tokenSimulationEnabled : defaults.tokenSimulationEnabled,
    propertiesCollapsed: typeof preference?.propertiesCollapsed === 'boolean'
      ? preference.propertiesCollapsed : defaults.propertiesCollapsed
  }
}

/**
 * 保存当前用户的完整设计器偏好。
 * @param {string|number} userId 当前登录用户主键。
 * @param {object} preference 设计器提交的完整偏好。
 * @param {Storage} storage 浏览器 localStorage，测试可传入同协议存储实现。
 * @returns {object} 已按白名单持久化的偏好。
 */
export function saveDesignerPreference(userId, preference, storage = globalThis.localStorage) {
  const normalized = normalizeDesignerPreference(preference)
  storage.setItem(designerPreferenceStorageKey(userId), JSON.stringify({
    schemaVersion: DESIGNER_PREFERENCE_SCHEMA_VERSION,
    ...normalized
  }))
  return normalized
}

/**
 * 加载当前用户偏好；损坏 JSON、旧协议或非法字段会恢复并覆盖为默认值。
 * @param {string|number} userId 当前登录用户主键。
 * @param {Storage} storage 浏览器 localStorage，测试可传入同协议存储实现。
 * @returns {object} 当前协议下字段完整的偏好。
 */
export function loadDesignerPreference(userId, storage = globalThis.localStorage) {
  const key = designerPreferenceStorageKey(userId)
  const stored = storage.getItem(key)
  if (stored == null) return { ...DEFAULT_DESIGNER_PREFERENCE }

  try {
    const parsed = JSON.parse(stored)
    if (!parsed || parsed.schemaVersion !== DESIGNER_PREFERENCE_SCHEMA_VERSION) {
      throw new TypeError('设计器偏好协议版本不匹配')
    }
    // 每次读取均覆盖为当前白名单结构，同时完成同版本新增默认字段的轻量迁移。
    return saveDesignerPreference(userId, parsed, storage)
  } catch {
    return saveDesignerPreference(userId, DEFAULT_DESIGNER_PREFERENCE, storage)
  }
}

/**
 * 恢复当前用户默认偏好，只删除该用户当前协议版本的存储键。
 * @param {string|number} userId 当前登录用户主键。
 * @param {Storage} storage 浏览器 localStorage，测试可传入同协议存储实现。
 * @returns {object} 可立即应用的默认偏好副本。
 */
export function resetDesignerPreference(userId, storage = globalThis.localStorage) {
  storage.removeItem(designerPreferenceStorageKey(userId))
  return { ...DEFAULT_DESIGNER_PREFERENCE }
}
