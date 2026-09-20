import { listForms } from '@/api/workflow/form'

// 只共享分页装载；每次调用返回独立目录，任何一页失败都交由页面处理。
export async function loadModelFormOptions() {
  const firstPage = await listForms({ pageNum: 1, pageSize: 50 })
  const forms = [...(firstPage.rows || [])]
  const pageCount = Math.ceil((firstPage.total || 0) / 50)
  for (let pageNum = 2; pageNum <= pageCount; pageNum += 1) {
    const page = await listForms({ pageNum, pageSize: 50 })
    forms.push(...(page.rows || []))
  }
  return forms
}
