import { createHash } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import path from 'node:path'
import { DOMParser } from '@xmldom/xmldom'
import { unzipSync } from 'fflate'
import { test, expect } from './fixtures/workflow.js'
import { WORKFLOW_EXPORT_CONTRACTS } from './support/contracts.js'
import { captureResponse, expectAjaxSuccess, matchesEndpoint } from './support/http.js'

const EMPTY_FILTER = '__flowable_e2e_no_match__'
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

/**
 * 从 XLSX ZIP 中解析一份必需的 OOXML 文档。
 * @param {Record<string, Uint8Array>} archive fflate 解压后的文件映射。
 * @param {string} entryPath ZIP 内部 OOXML 路径。
 * @returns {Document} 已解析的 XML DOM。
 */
function parseXmlEntry(archive, entryPath) {
  const bytes = archive[entryPath]
  if (!bytes) throw new Error(`XLSX 缺少必需条目：${entryPath}`)
  const document = new DOMParser().parseFromString(new TextDecoder('utf-8').decode(bytes), 'application/xml')
  if (!document.documentElement || document.getElementsByTagName('parsererror').length) {
    throw new Error(`XLSX 条目不是合法 XML：${entryPath}`)
  }
  return document
}

/**
 * 通过 workbook relationship 解析第一个工作表的实际 ZIP 路径。
 * @param {Record<string, Uint8Array>} archive fflate 解压后的文件映射。
 * @param {Document} workbookDocument `xl/workbook.xml` DOM。
 * @returns {{sheetName: string, worksheetPath: string}} 首个工作表名称及 OOXML 路径。
 */
function resolveFirstWorksheet(archive, workbookDocument) {
  const sheet = workbookDocument.getElementsByTagName('sheet')[0]
  if (!sheet) throw new Error('XLSX workbook.xml 未声明工作表')
  const relationshipId = sheet.getAttribute('r:id')
  const relationships = parseXmlEntry(archive, 'xl/_rels/workbook.xml.rels')
  const relationship = Array.from(relationships.getElementsByTagName('Relationship'))
    .find(item => item.getAttribute('Id') === relationshipId)
  if (!relationship) throw new Error('XLSX 首个工作表缺少 relationship')
  const target = relationship.getAttribute('Target') || ''
  const worksheetPath = target.startsWith('/')
    ? target.slice(1)
    : path.posix.normalize(path.posix.join('xl', target))
  return { sheetName: sheet.getAttribute('name') || 'Sheet1', worksheetPath }
}

/**
 * 读取共享字符串表；富文本单元格由 DOM textContent 按显示顺序合并。
 * @param {Record<string, Uint8Array>} archive fflate 解压后的文件映射。
 * @returns {string[]} 共享字符串索引表，文件不存在时返回空数组。
 */
function readSharedStrings(archive) {
  if (!archive['xl/sharedStrings.xml']) return []
  const document = parseXmlEntry(archive, 'xl/sharedStrings.xml')
  return Array.from(document.getElementsByTagName('si')).map(item => String(item.textContent || ''))
}

/**
 * 把 A1 引用中的列字母转换为从零开始的数组下标。
 * @param {string} reference OOXML 单元格引用，例如 `A1` 或 `AA20`。
 * @returns {number} 从零开始的列下标。
 */
function columnIndex(reference) {
  const letters = reference.match(/^[A-Z]+/i)?.[0]?.toUpperCase()
  if (!letters) throw new Error(`非法 XLSX 单元格引用：${reference}`)
  return [...letters].reduce((value, letter) => value * 26 + letter.charCodeAt(0) - 64, 0) - 1
}

/**
 * 解码 OOXML 单元格的显示文本，支持共享字符串、inlineStr 和普通值。
 * @param {Element} cell OOXML `c` 单元格节点。
 * @param {string[]} sharedStrings 共享字符串索引表。
 * @returns {string} 去除首尾空白后的单元格文本。
 */
function readCellText(cell, sharedStrings) {
  const type = cell.getAttribute('t')
  if (type === 'inlineStr') return String(cell.getElementsByTagName('is')[0]?.textContent || '').trim()
  const rawValue = String(cell.getElementsByTagName('v')[0]?.textContent || '')
  if (type === 's') return String(sharedStrings[Number(rawValue)] || '').trim()
  return rawValue.trim()
}

/**
 * 将工作表 DOM 转换为按行列对齐的文本矩阵，保留中间空单元格。
 * @param {Document} worksheetDocument 工作表 XML DOM。
 * @param {string[]} sharedStrings 共享字符串索引表。
 * @returns {string[][]} 从第一条实际 row 开始的单元格文本矩阵。
 */
function readWorksheetRows(worksheetDocument, sharedStrings) {
  return Array.from(worksheetDocument.getElementsByTagName('row')).map(row => {
    const values = []
    for (const cell of Array.from(row.getElementsByTagName('c'))) {
      values[columnIndex(cell.getAttribute('r') || '')] = readCellText(cell, sharedStrings)
    }
    return Array.from({ length: values.length }, (_, index) => values[index] || '')
  })
}

/**
 * 解析真实下载的 XLSX，并冻结表头、数据行数与筛选列值。
 * @param {string} filePath Playwright 测试结果目录中的下载文件。
 * @param {ReadonlyArray<string>} expectedHeaders 后端导出视图冻结的列名及顺序。
 * @param {string} filterHeader 本次筛选对应的导出列名。
 * @returns {Promise<{sheetName: string, headers: string[], dataRowCount: number, filteredValues: string[]}>} 不含整行业务数据的解析结果。
 */
async function inspectWorkbook(filePath, expectedHeaders, filterHeader) {
  const archive = unzipSync(new Uint8Array(await readFile(filePath)))
  const workbookDocument = parseXmlEntry(archive, 'xl/workbook.xml')
  const { sheetName, worksheetPath } = resolveFirstWorksheet(archive, workbookDocument)
  const rows = readWorksheetRows(parseXmlEntry(archive, worksheetPath), readSharedStrings(archive))
  const headerRowIndex = rows.slice(0, 10).findIndex(values => expectedHeaders.every(header => values.includes(header)))
  expect(headerRowIndex, 'XLSX 前十行必须包含冻结表头').toBeGreaterThanOrEqual(0)
  const headers = rows[headerRowIndex]
  expect(headers, 'XLSX 列名及顺序').toEqual([...expectedHeaders])
  const filterColumn = headers.indexOf(filterHeader)
  expect(filterColumn, `${filterHeader} 列必须存在`).toBeGreaterThanOrEqual(0)

  let dataRowCount = 0
  const filteredValues = []
  for (const row of rows.slice(headerRowIndex + 1)) {
    const hasData = row.some(value => value !== '')
    if (!hasData) continue
    dataRowCount += 1
    filteredValues.push(row[filterColumn] || '')
  }
  return { sheetName, headers, dataRowCount, filteredValues }
}

/**
 * 从页面首行选择可复验过滤值；空数据环境使用必不命中的稳定值验证零行导出。
 * @param {object} listPayload 列表 AjaxResult。
 * @param {string} filterField 页面与 DTO 共用的筛选字段。
 * @returns {{filterValue: string, sourceHadRows: boolean}} 过滤值及是否来源于真实首行。
 */
function chooseFilter(listPayload, filterField) {
  const firstValue = listPayload.rows?.[0]?.[filterField]
  const normalized = firstValue === null || firstValue === undefined ? '' : String(firstValue).trim()
  return normalized
    ? { filterValue: normalized, sourceHadRows: true }
    : { filterValue: EMPTY_FILTER, sourceHadRows: false }
}

/**
 * 执行一个真实表格导出并核对查询、权限范围、文件协议、SHA-256 与 XLSX 内容。
 * @param {import('@playwright/test').Page} page 已按最小权限角色登录的页面。
 * @param {import('@playwright/test').TestInfo} testInfo 当前测试证据目录。
 * @param {object} contract 导出契约。
 * @returns {Promise<void>} 下载及结构化对账全部通过后结束。
 */
async function verifyExport(page, testInfo, contract) {
  const initialResponse = await captureResponse(page, contract.listEndpoint, () => page.goto(contract.path))
  const initialPayload = await expectAjaxSuccess(initialResponse, contract.listEndpoint)
  const { filterValue, sourceHadRows } = chooseFilter(initialPayload, contract.filterField)

  await page.getByPlaceholder(contract.filterPlaceholder, { exact: true }).fill(filterValue)
  const filteredResponse = await captureResponse(
    page,
    contract.listEndpoint,
    () => page.getByRole('button', { name: '搜索', exact: true }).click()
  )
  const filteredPayload = await expectAjaxSuccess(filteredResponse, contract.listEndpoint)
  if (sourceHadRows) {
    expect(filteredPayload.total, '使用真实首行值筛选后至少保留一行').toBeGreaterThan(0)
    for (const row of filteredPayload.rows || []) {
      expect(String(row[contract.filterField] ?? ''), '列表行必须满足页面筛选').toContain(filterValue)
    }
  } else {
    expect(filteredPayload.total, '空数据环境的哨兵筛选必须保持零行').toBe(0)
  }

  const exportResponsePromise = page.waitForResponse(response => matchesEndpoint(response, contract.exportEndpoint, 'POST'))
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出', exact: true }).click()
  const exportResponse = await exportResponsePromise
  const download = await downloadPromise
  expect(exportResponse.status(), `${contract.exportEndpoint} HTTP 状态`).toBe(200)
  expect(exportResponse.headers()['content-type'] || '', '导出 MIME').toContain(XLSX_MIME)
  const submittedFilter = new URLSearchParams(exportResponse.request().postData() || '').get(contract.filterField)
  expect(submittedFilter, '导出请求必须复用页面筛选值').toBe(filterValue)

  const suggestedFilename = download.suggestedFilename()
  expect(suggestedFilename.startsWith(contract.filenamePrefix), '下载文件名前缀').toBe(true)
  expect(suggestedFilename.endsWith('.xlsx'), '下载扩展名').toBe(true)
  const filePath = testInfo.outputPath(`${contract.key}.xlsx`)
  await download.saveAs(filePath)
  expect(await download.failure(), '浏览器下载不得失败').toBeNull()
  const fileSize = (await stat(filePath)).size
  expect(fileSize, 'XLSX 文件大小').toBeGreaterThan(1_000)
  const sha256 = createHash('sha256').update(await readFile(filePath)).digest('hex')
  const workbookEvidence = await inspectWorkbook(filePath, contract.headers, contract.filterHeader)
  expect(workbookEvidence.dataRowCount, 'XLSX 数据行数必须与角色过滤后的 API total 一致').toBe(Number(filteredPayload.total || 0))
  for (const value of workbookEvidence.filteredValues) {
    expect(value, 'XLSX 行必须满足页面筛选').toContain(filterValue)
  }

  await testInfo.attach(`${contract.key}-export-evidence.json`, {
    body: Buffer.from(JSON.stringify({
      roleKey: contract.roleKey,
      endpoint: contract.exportEndpoint,
      filename: suggestedFilename,
      mime: exportResponse.headers()['content-type'],
      size: fileSize,
      sha256,
      sheetName: workbookEvidence.sheetName,
      headers: workbookEvidence.headers,
      rows: workbookEvidence.dataRowCount,
      filterSource: sourceHadRows ? 'first-real-row' : 'empty-dataset-sentinel'
    }, null, 2)),
    contentType: 'application/json'
  })
}

for (const contract of WORKFLOW_EXPORT_CONTRACTS) {
  test.describe(`${contract.key} 真实 XLSX 导出`, () => {
    test.use({ roleKey: contract.roleKey })

    /**
     * 使用当前导出契约的最小权限角色执行真实下载和 OOXML 对账。
     * @param {{workflowPage: import('@playwright/test').Page}} fixtures 已完成真实登录的 Playwright fixture。
     * @param {import('@playwright/test').TestInfo} testInfo 当前用例的受控证据目录。
     * @returns {Promise<void>} 当前导出全部门禁通过后结束。
     */
    test('查询条件、角色范围与下载内容一致', async ({ workflowPage }, testInfo) => {
      await verifyExport(workflowPage, testInfo, contract)
    })
  })
}
