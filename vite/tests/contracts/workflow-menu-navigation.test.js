import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const routerSource = readFileSync(new URL('../../src/router/index.js', import.meta.url), 'utf8')
const detailSource = readFileSync(new URL('../../src/views/workflow/work/detail.vue', import.meta.url), 'utf8')

// 低频页面的旧地址必须继续可访问，避免菜单整理影响已保存的链接和页签。
const legacyRouteRedirects = [
  ['/workflow/sqlDatasource', '/workflow/extensions/sqlDatasource'],
  ['/workflow/integrationCredential', '/workflow/extensions/integrationCredential'],
  ['/workflow/dmn', '/workflow/extensions/dmn'],
  ['/workflow/runtimeEvent', '/workflow/extensions/runtimeEvent'],
  ['/workflow/collaboration', '/workflow/extensions/collaboration'],
  ['/workflow/bpmnEvent', '/workflow/extensions/bpmnEvent'],
  ['/workflow/instance', '/workflow/extensions/instance']
]

/**
 * 验证低频工作流页面都迁移到扩展流程管理二级目录，并保留旧地址重定向。
 * @returns {void} 菜单整理或兼容入口缺失时测试失败。
 */
test('扩展流程管理保留低频页面的旧地址兼容', () => {
  assert.match(routerSource, /workflowExtendedManagementRedirects/)
  for (const [legacyPath, targetPath] of legacyRouteRedirects) {
    const routePattern = new RegExp(
      `path:\\s*'${legacyPath}'[\\s\\S]*?redirect:\\s*'${targetPath}'`
    )
    assert.match(routerSource, routePattern, `${legacyPath} 缺少兼容重定向`)
  }
})

/**
 * 验证流程详情页返回实例运维时使用整理后的规范地址。
 * @returns {void} 返回入口仍指向旧地址时测试失败。
 */
test('流程详情返回实例运维使用扩展流程管理地址', () => {
  assert.match(detailSource, /manage:\s*'\/workflow\/extensions\/instance'/)
})
