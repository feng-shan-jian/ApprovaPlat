// 仅解释服务端状态的文案与颜色，不推断权限、动作或生命周期。
const PROCESS_STATUS = {
  running: { label: '进行中', type: 'primary' },
  returned: { label: '待修改', type: 'warning' },
  suspended: { label: '已挂起', type: 'warning' },
  completed: { label: '已完成', type: 'success' },
  rejected: { label: '已驳回', type: 'danger' },
  terminated: { label: '已终止', type: 'danger' },
  canceled: { label: '已取消', type: 'info' }
}

export function processStatusMeta(status, emptyLabel = '未知') {
  return Object.hasOwn(PROCESS_STATUS, status)
    ? PROCESS_STATUS[status]
    : { label: status || emptyLabel, type: 'info' }
}
