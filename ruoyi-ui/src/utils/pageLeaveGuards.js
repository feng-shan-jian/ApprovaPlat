// 仅保存当前内存中的离开回调；业务内容持久化由页面的服务端草稿负责。
const guards = new Map()

export function registerPageLeaveGuard(path, guard) {
  guards.set(path, guard)
  return () => { if (guards.get(path) === guard) guards.delete(path) }
}

export async function confirmPageLeave(paths) {
  for (const path of new Set(paths)) {
    if (guards.has(path) && !await guards.get(path)()) return false
  }
  return true
}
