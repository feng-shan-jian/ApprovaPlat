import fs from 'node:fs'
import { test, expect } from '@playwright/test'
import { openRoleSession } from '../../support/role-session.js'
import { locateExportTable, readXlsx } from '../../support/xlsx.js'

const EXPORT_CASES = Object.freeze([
  exportCase('UI-EXPORT-001', '分类', 'workflow_designer', '/workflow/category', '/workflow/category/list', '/workflow/category/export',
    '请输入分类名称', 'categoryName', 'categoryName', 'workflow_category', ['分类ID', '分类名称', '分类编码', '备注']),
  exportCase('UI-EXPORT-002', '表单', 'workflow_designer', '/workflow/form', '/workflow/form/list', '/workflow/form/export',
    '请输入表单名称', 'formName', 'formName', 'workflow_form', ['表单ID', '表单名称', '备注']),
  exportCase('UI-EXPORT-003', '模型', 'workflow_designer', '/workflow/model', '/workflow/model/list', '/workflow/model/export',
    '请输入模型名称', 'modelName', 'modelName', 'workflow_model', ['模型ID', '模型Key', '模型名称', '分类编码', '流程分类', '模型版本', '模型描述', '创建时间']),
  exportCase('UI-EXPORT-004', '可发起流程', 'workflow_starter', '/office/create', '/workflow/process/list', '/workflow/process/startExport',
    '请输入流程名称', 'processName', 'processName', 'startable_processes', ['流程定义ID', '流程名称', '流程Key', '分类编码', '版本', '部署ID', '部署时间']),
  exportCase('UI-EXPORT-005', '我的流程', 'workflow_starter', '/office/own', '/workflow/process/ownList', '/workflow/process/ownExport',
    '请输入流程名称', 'processName', 'processName', 'owned_processes', ['流程实例ID', '流程名称', '分类编码', '流程版本', '提交时间', '完成时间', '流程状态', '耗时毫秒', '当前节点']),
  exportCase('UI-EXPORT-006', '实例管理', 'workflow_admin', '/workflow/extensions/instance', '/workflow/process/manageList', '/workflow/process/manageExport',
    '请输入流程名称', 'processName', 'processName', 'managed_processes', ['流程实例ID', '流程名称', '分类编码', '流程版本', '业务主键', '发起人ID', '发起人', '提交时间', '完成时间', '流程状态', '耗时毫秒', '当前节点']),
  exportCase('UI-EXPORT-007', '待办任务', 'workflow_approver', '/office/todo', '/workflow/process/todoList', '/workflow/process/todoExport',
    '请输入流程名称', 'processName', 'processName', 'todo_tasks', ['任务ID', '流程名称', '任务节点', '流程版本', '流程发起人', '接收时间', '到期时间']),
  exportCase('UI-EXPORT-008', '待签任务', 'workflow_approver', '/office/claim', '/workflow/process/claimList', '/workflow/process/claimExport',
    '请输入流程名称', 'processName', 'processName', 'claimable_tasks', ['任务ID', '流程名称', '任务节点', '流程版本', '流程发起人', '接收时间', '到期时间']),
  exportCase('UI-EXPORT-009', '已办任务', 'workflow_approver', '/office/finished', '/workflow/process/finishedList', '/workflow/process/finishedExport',
    '请输入流程名称', 'processName', 'processName', 'finished_tasks', ['任务ID', '流程名称', '任务节点', '流程版本', '流程发起人', '真实完成人ID', '接收时间', '完成时间', '耗时毫秒']),
  exportCase('UI-EXPORT-010', '抄送流程', 'workflow_starter', '/office/copy', '/workflow/process/copyList', '/workflow/process/copyExport',
    '请输入抄送标题', 'title', 'title', 'copied_processes', ['抄送ID', '抄送标题', '流程名称', '分类编码', '部署ID', '流程实例ID', '任务ID', '发起人ID', '发起人名称', '抄送时间'])
])

for (const contract of EXPORT_CASES) {
  test(`@full [${contract.caseId}] ${contract.label}通过真实页面筛选并导出完整XLSX`, async ({ browser }, testInfo) => {
    const session = await openRoleSession(browser, contract.roleKey, testInfo)
    let failed = true
    try {
      const { page } = session
      const initialResponsePromise = waitForBusinessResponse(page, contract.listEndpoint, 'GET')
      await page.goto(contract.path)
      const initialPayload = await responseJson(await initialResponsePromise, `${contract.label}列表`)
      await expect(page.locator('.app-container .el-table').first()).toBeVisible()

      const firstRow = Array.isArray(initialPayload.rows) ? initialPayload.rows[0] : null
      const filterValue = String(firstRow?.[contract.sourceField] || `E2E_UI_NO_MATCH_${testInfo.workerIndex}_${Date.now()}`)
      const filterInput = page.getByPlaceholder(contract.filterPlaceholder)
      await filterInput.fill(filterValue)
      const queryForm = filterInput.locator('xpath=ancestor::form[1]')
      const filteredResponsePromise = waitForBusinessResponse(page, contract.listEndpoint, 'GET')
      await queryForm.getByRole('button', { name: '搜索', exact: true }).click()
      const filteredPayload = await responseJson(await filteredResponsePromise, `${contract.label}筛选列表`)
      const expectedRows = Number(filteredPayload.total || 0)
      expect(Array.isArray(filteredPayload.rows), `${contract.label}列表 rows 必须为数组`).toBe(true)
      expect(filteredPayload.rows.every(row => String(row?.[contract.sourceField] || '').includes(filterValue)), `${contract.label}列表筛选结果`).toBe(true)

      let exportRequest = null
      const requestListener = request => {
        if (request.method() === 'POST' && new URL(request.url()).pathname.endsWith(contract.exportEndpoint)) exportRequest = request
      }
      page.on('request', requestListener)
      try {
        const exportResponsePromise = waitForBusinessResponse(page, contract.exportEndpoint, 'POST')
        const downloadPromise = page.waitForEvent('download')
        await page.getByRole('button', { name: '导出', exact: true }).click()
        const [exportResponse, download] = await Promise.all([exportResponsePromise, downloadPromise])
        expect(exportResponse.ok(), `${contract.label}导出接口必须成功`).toBe(true)
        expect(exportResponse.headers()['content-type'] || '', `${contract.label}导出 MIME`).toContain('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
        expect(exportRequest, `${contract.label}必须由页面按钮发起真实导出请求`).not.toBeNull()
        const requestFields = new URLSearchParams(exportRequest.postData() || '')
        expect(requestFields.get(contract.filterField), `${contract.label}导出必须沿用页面筛选值`).toBe(filterValue)

        const suggestedFilename = download.suggestedFilename()
        expect(suggestedFilename).toMatch(new RegExp(`^${contract.filenamePrefix}_[0-9]+\\.xlsx$`, 'u'))
        const downloadPath = testInfo.outputPath(suggestedFilename)
        await download.saveAs(downloadPath)
        expect(fs.statSync(downloadPath).size, `${contract.label}XLSX 不能为空`).toBeGreaterThan(0)

        const workbook = readXlsx(downloadPath)
        const table = locateExportTable(workbook.rows, contract.headers)
        expect(table.headers).toEqual(contract.headers)
        expect(table.dataRows, `${contract.label}导出行数必须与同筛选条件列表 total 一致`).toHaveLength(expectedRows)
        if (expectedRows > 0) {
          const filterColumnIndex = contract.headers.indexOf(contract.filterHeader)
          expect(filterColumnIndex, `${contract.label}筛选列必须存在于导出表头`).toBeGreaterThanOrEqual(0)
          expect(table.dataRows.every(row => String(row[filterColumnIndex] || '').includes(filterValue)), `${contract.label}XLSX 数据范围`).toBe(true)
        }
        expect(workbook.sha256).toMatch(/^[a-f0-9]{64}$/u)
        await testInfo.attach(`${contract.caseId}-xlsx`, { path: downloadPath, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
        await testInfo.attach(`${contract.caseId}-summary.json`, {
          body: Buffer.from(JSON.stringify({
            label: contract.label,
            filterField: contract.filterField,
            filterValue,
            expectedRows,
            exportedRows: table.dataRows.length,
            filename: suggestedFilename,
            mime: exportResponse.headers()['content-type'],
            sha256: workbook.sha256,
            zipEntries: workbook.entries
          }, null, 2)),
          contentType: 'application/json'
        })
      } finally {
        page.off('request', requestListener)
      }
      failed = false
    } finally {
      await session.close(failed)
    }
  })
}

/**
 * 创建不可变的页面导出测试契约。
 * @param {string} caseId 稳定测试编号。
 * @param {string} label 导出业务名称。
 * @param {string} roleKey 执行页面操作的职责角色。
 * @param {string} path 前端真实页面路由。
 * @param {string} listEndpoint 页面列表端点。
 * @param {string} exportEndpoint 页面导出端点。
 * @param {string} filterPlaceholder 页面筛选输入框占位文本。
 * @param {string} filterField 导出请求筛选字段。
 * @param {string} sourceField 列表响应中提取筛选值的字段。
 * @param {string} filenamePrefix 前端生成的下载文件名前缀。
 * @param {string[]} headers 导出 DTO 定义的有序表头。
 * @returns {Readonly<object>} 十类导出的稳定契约。
 */
function exportCase(caseId, label, roleKey, path, listEndpoint, exportEndpoint, filterPlaceholder, filterField, sourceField, filenamePrefix, headers) {
  const filterHeader = ({ categoryName: '分类名称', formName: '表单名称', modelName: '模型名称', processName: '流程名称', title: '抄送标题' })[filterField]
  return Object.freeze({ caseId, label, roleKey, path, listEndpoint, exportEndpoint, filterPlaceholder, filterField, sourceField, filenamePrefix, headers: Object.freeze(headers), filterHeader })
}

/**
 * 等待页面产生指定真实业务响应。
 * @param {import('@playwright/test').Page} page 当前浏览器页面。
 * @param {string} endpoint 后端端点后缀。
 * @param {string} method HTTP 方法。
 * @returns {Promise<import('@playwright/test').Response>} 唯一匹配的业务响应。
 */
function waitForBusinessResponse(page, endpoint, method) {
  return page.waitForResponse(response => response.request().method() === method && new URL(response.url()).pathname.endsWith(endpoint))
}

/**
 * 校验列表响应成功并解析 JSON 数据。
 * @param {import('@playwright/test').Response} response 页面真实列表响应。
 * @param {string} label 错误消息中的业务名称。
 * @returns {Promise<object>} 后端分页响应。
 */
async function responseJson(response, label) {
  expect(response.ok(), `${label}接口必须成功`).toBe(true)
  const payload = await response.json()
  expect(Number(payload.code), `${label}业务码`).toBe(200)
  return payload
}
