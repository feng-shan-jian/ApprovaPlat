import { getTaskCapability } from './taskCapabilityMap.js'

const MANUAL_TASK_REPLACE_ACTION = 'replace-with-manual-task'
const AFTER_DEFAULT_REPLACE_PROVIDER = 500

/**
 * 从 bpmn-js“更改元素”条目中移除平台禁止作为转换目标的 ManualTask。
 * @param {Record<string, object>} entries bpmn-js 默认转换菜单条目。
 * @returns {Record<string, object>} 保留原条目对象、仅删除禁用目标的新映射。
 */
export function filterTaskReplaceEntries(entries) {
  const filtered = { ...(entries || {}) }
  const manualCapability = getTaskCapability('bpmn:ManualTask')
  if (manualCapability?.conversionAllowed === false) {
    delete filtered[MANUAL_TASK_REPLACE_ACTION]
  }
  return filtered
}

/**
 * 在 bpmn-js 默认 ReplaceMenuProvider 之后应用平台任务转换能力。
 * @param {object} popupMenu bpmn-js PopupMenu 服务。
 * @returns {void} 注册后由 PopupMenu 在每次打开转换菜单时调用。
 */
function TaskCapabilityReplaceMenuFilter(popupMenu) {
  popupMenu.registerProvider('bpmn-replace', AFTER_DEFAULT_REPLACE_PROVIDER, this)
}

TaskCapabilityReplaceMenuFilter.$inject = ['popupMenu']

/**
 * 返回转换菜单的纯函数更新器，确保默认 bpmn-js 条目先生成再按平台能力过滤。
 * @returns {(entries: Record<string, object>) => Record<string, object>} 转换条目更新器。
 */
TaskCapabilityReplaceMenuFilter.prototype.getPopupMenuEntries = function() {
  return entries => filterTaskReplaceEntries(entries)
}

export default {
  __init__: ['taskCapabilityReplaceMenuFilter'],
  taskCapabilityReplaceMenuFilter: ['type', TaskCapabilityReplaceMenuFilter]
}
