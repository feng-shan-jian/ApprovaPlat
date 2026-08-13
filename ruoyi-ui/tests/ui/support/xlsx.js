import crypto from 'node:crypto'
import fs from 'node:fs'
import { DOMParser } from '@xmldom/xmldom'
import { unzipSync, strFromU8 } from 'fflate'

/**
 * 读取 XLSX 下载文件并返回首个工作表的可见文本矩阵。
 * @param {string} filePath XLSX 文件绝对路径。
 * @returns {{sha256:string,entries:string[],rows:string[][]}} 文件摘要、ZIP 条目和工作表行。
 */
export function readXlsx(filePath) {
  const content = fs.readFileSync(filePath)
  const archive = unzipSync(new Uint8Array(content))
  const entries = Object.keys(archive).sort()
  for (const requiredEntry of ['[Content_Types].xml', 'xl/workbook.xml']) {
    if (!archive[requiredEntry]) throw new Error(`XLSX 缺少必要 OOXML 条目：${requiredEntry}`)
  }

  const sharedStrings = parseSharedStrings(archive['xl/sharedStrings.xml'])
  const worksheetPath = entries.find(entry => /^xl\/worksheets\/sheet\d+\.xml$/u.test(entry))
  if (!worksheetPath) throw new Error('XLSX 缺少工作表 XML')
  return {
    sha256: crypto.createHash('sha256').update(content).digest('hex'),
    entries,
    rows: parseWorksheet(archive[worksheetPath], sharedStrings)
  }
}

/**
 * 从工作表中定位指定表头，并返回表头之后的非空数据行。
 * @param {string[][]} rows 工作表可见文本矩阵。
 * @param {string[]} expectedHeaders 业务导出 DTO 定义的有序表头。
 * @returns {{headerRowIndex:number,headers:string[],dataRows:string[][]}} 表头位置和规范化数据行。
 */
export function locateExportTable(rows, expectedHeaders) {
  const headerRowIndex = rows.findIndex(row => expectedHeaders.every((header, index) => row[index] === header))
  if (headerRowIndex < 0) {
    throw new Error(`XLSX 未找到预期表头：${expectedHeaders.join(' | ')}`)
  }
  const dataRows = rows.slice(headerRowIndex + 1)
    .map(row => row.slice(0, expectedHeaders.length))
    .filter(row => row.some(value => value !== ''))
  return { headerRowIndex, headers: rows[headerRowIndex].slice(0, expectedHeaders.length), dataRows }
}

/**
 * 解析 OOXML 共享字符串表。
 * @param {Uint8Array|undefined} xmlBytes `xl/sharedStrings.xml` 原始字节。
 * @returns {string[]} 按共享字符串索引排列的文本。
 */
function parseSharedStrings(xmlBytes) {
  if (!xmlBytes) return []
  const document = parseXml(xmlBytes, '共享字符串')
  return Array.from(document.getElementsByTagName('si')).map(item => nodeText(item))
}

/**
 * 解析首个 OOXML 工作表，按单元格引用补齐稀疏列。
 * @param {Uint8Array} xmlBytes 工作表 XML 原始字节。
 * @param {string[]} sharedStrings 共享字符串目录。
 * @returns {string[][]} 每行按列号排列的可见文本。
 */
function parseWorksheet(xmlBytes, sharedStrings) {
  const document = parseXml(xmlBytes, '工作表')
  return Array.from(document.getElementsByTagName('row')).map(rowNode => {
    const row = []
    for (const cell of Array.from(rowNode.getElementsByTagName('c'))) {
      const columnIndex = cellColumnIndex(cell.getAttribute('r'))
      const type = cell.getAttribute('t')
      const valueNode = cell.getElementsByTagName('v')[0]
      const rawValue = valueNode ? nodeText(valueNode) : ''
      if (type === 's') row[columnIndex] = sharedStrings[Number(rawValue)] ?? ''
      else if (type === 'inlineStr') row[columnIndex] = nodeText(cell.getElementsByTagName('is')[0])
      else row[columnIndex] = rawValue
    }
    return Array.from({ length: row.length }, (_, index) => row[index] ?? '')
  })
}

/**
 * 将 XML 字节解析为 DOM，并拒绝解析器错误。
 * @param {Uint8Array} xmlBytes XML 原始字节。
 * @param {string} label 错误消息中的业务名称。
 * @returns {Document} 可供结构化读取的 XML 文档。
 */
function parseXml(xmlBytes, label) {
  const errors = []
  const document = new DOMParser({
    onError: (level, message) => errors.push(`${level}: ${message}`)
  }).parseFromString(strFromU8(xmlBytes), 'application/xml')
  if (errors.length || document.getElementsByTagName('parsererror').length) {
    throw new Error(`${label} XML 解析失败：${errors[0] || 'parsererror'}`)
  }
  return document
}

/**
 * 读取 XML 节点及其后代文本，兼容富文本共享字符串。
 * @param {Node|undefined} node XML 节点。
 * @returns {string} 拼接后的可见文本。
 */
function nodeText(node) {
  if (!node) return ''
  if (node.nodeType === 3 || node.nodeType === 4) return node.nodeValue || ''
  return Array.from(node.childNodes || []).map(child => nodeText(child)).join('')
}

/**
 * 将 Excel 单元格引用转换为从零开始的列号。
 * @param {string} reference 例如 `A1`、`BC12` 的单元格引用。
 * @returns {number} 从零开始的列号。
 */
function cellColumnIndex(reference) {
  const letters = String(reference || '').match(/^[A-Z]+/u)?.[0]
  if (!letters) throw new Error(`无效 XLSX 单元格引用：${reference}`)
  return [...letters].reduce((value, letter) => value * 26 + letter.charCodeAt(0) - 64, 0) - 1
}
