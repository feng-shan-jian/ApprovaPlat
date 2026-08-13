import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..')

/**
 * 读取本机受控 MySQL 管理凭据，凭据只进入子进程环境且绝不返回给测试报告。
 * @returns {string} MySQL 密码。
 */
function loadMysqlPassword() {
  const environmentPassword = process.env.FLOWABLE_E2E_DB_PASSWORD?.trim()
  if (environmentPassword) return environmentPassword
  const credentialPath = path.join(repositoryRoot, 'mysql-root.txt')
  if (!fs.existsSync(credentialPath)) {
    throw new Error('缺少 FLOWABLE_E2E_DB_PASSWORD 或 mysql-root.txt，无法进行只读数据库核验')
  }
  const match = fs.readFileSync(credentialPath, 'utf8').match(/^Password:\s*(.+?)\s*$/mu)
  if (!match?.[1]) throw new Error('mysql-root.txt 未包含有效 Password 配置')
  return match[1].trim()
}

/**
 * 执行单条只读 SQL 并返回 TSV 行，禁止测试代码借此修改业务数据。
 * @param {string} sql 仅允许 SELECT、SHOW、EXPLAIN 或 WITH 查询。
 * @param {string} database 目标正式数据库名称。
 * @returns {string[][]} 按制表符拆分的查询结果行。
 */
export function queryReadOnly(sql, database = process.env.FLOWABLE_E2E_DB_NAME || 'ry-vue') {
  const statement = String(sql || '').trim()
  if (!/^(SELECT|SHOW|EXPLAIN|WITH)\b/iu.test(statement) || /;\s*\S/u.test(statement)) {
    throw new Error('UI 测试数据库助手只允许执行一条只读 SQL')
  }
  const command = process.platform === 'win32' ? 'mysql.exe' : 'mysql'
  const result = spawnSync(command, [
    '--host=127.0.0.1',
    '--user=root',
    '--default-character-set=utf8mb4',
    '--batch',
    '--skip-column-names',
    `--database=${database}`,
    `--execute=${statement}`
  ], {
    encoding: 'utf8',
    env: { ...process.env, MYSQL_PWD: loadMysqlPassword() },
    windowsHide: true
  })
  if (result.status !== 0) {
    throw new Error(`只读数据库核验失败：${String(result.stderr || '').trim()}`)
  }
  // 只移除行尾换行，保留首列或末列的空字符串，避免 NULL/空值导致 TSV 列错位。
  const output = String(result.stdout || '').replace(/(?:\r?\n)+$/u, '')
  if (!output) return []
  return output.split(/\r?\n/u).map(line => line.split('\t'))
}
