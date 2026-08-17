import { test, expect } from '@playwright/test'
import { openRoleSession } from '../../support/role-session.js'
import { queryReadOnly } from '../../support/database.js'

test('@full [UI-NOTIFY-002] 审批通知用户可通过消息中心维护个人通知偏好', async ({ browser }, testInfo) => {
  const session = await openRoleSession(browser, 'workflow_approver', testInfo)
  let failed = true
  try {
    const { page } = session
    const preferenceResponses = []
    page.on('response', response => {
      if (new URL(response.url()).pathname.endsWith('/workflow/notification/preference')) {
        preferenceResponses.push({ method: response.request().method(), status: response.status() })
      }
    })

    await page.goto('/office/todo')
    const noticeTrigger = page.locator('.notice-trigger')
    await expect(noticeTrigger).toBeVisible()
    await noticeTrigger.hover()
    const noticePopover = page.locator('.notice-popover')
    await expect(noticePopover).toBeVisible()

    const permissionRows = queryReadOnly(
      "SELECT COUNT(*) FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.role_id JOIN sys_user u ON u.user_id=ur.user_id JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE u.user_name='e2e_ui_wf_approver' AND m.perms='workflow:notification:preference'"
    )
    await testInfo.attach('preference-access.json', {
      body: Buffer.from(JSON.stringify({
        assignedPreferencePermissionCount: Number(permissionRows[0]?.[0] || 0),
        preferenceResponses
      }, null, 2)),
      contentType: 'application/json'
    })

    // 后端偏好接口复用审批通知查询权限，前端必须提供真实入口，不能要求数据库中不存在的额外权限。
    await expect(noticePopover.getByRole('button', { name: '通知偏好', exact: true })).toBeVisible()
    failed = false
  } finally {
    await session.close(failed)
  }
})

test('@full [UI-SLA-003] 审批人可从真实页面查看并处理本人 SLA 通知', async ({ browser }, testInfo) => {
  const session = await openRoleSession(browser, 'workflow_approver', testInfo)
  let failed = true
  try {
    const { page } = session
    const notificationRequests = []
    page.on('request', request => {
      if (new URL(request.url()).pathname.endsWith('/workflow/sla/notifications')) {
        notificationRequests.push(request.method())
      }
    })

    await page.goto('/office/todo')
    await page.getByRole('menuitem', { name: '流程管理', exact: true }).click()
    await page.getByRole('menuitem', { name: '扩展流程管理', exact: true }).click()
    const workflowSidebarLink = page.locator('.sidebar-container a[href="/workflow/extensions/bpmnEvent"]')
    await expect(workflowSidebarLink).toBeVisible()
    const menuCount = await workflowSidebarLink.count()
    await workflowSidebarLink.click()
    await expect(page).toHaveURL(/\/workflow\/extensions\/bpmnEvent$/u)
    const slaNotificationTab = page.getByRole('tab', { name: 'SLA 通知', exact: true })
    await expect(slaNotificationTab).toBeVisible()
    const notificationResponse = page.waitForResponse(response =>
      new URL(response.url()).pathname.endsWith('/workflow/sla/notifications')
        && response.request().method() === 'GET'
        && response.status() === 200)
    await slaNotificationTab.click()
    await notificationResponse
    const finalPath = new URL(page.url()).pathname
    const pageHeading = await page.locator('.app-container h2').first().textContent().catch(() => '')
    const notFoundCount = await page.locator('.wscn-http404-container').count()
    const slaNotificationTabCount = await slaNotificationTab.count()
    const permissionRows = queryReadOnly(
      "SELECT SUM(m.perms='workflow:sla:notification'),SUM(m.perms='workflow:bpmnEvent:list') FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE r.role_key='workflow_approver'"
    )
    const accessEvidence = {
      assignedSlaNotificationPermissionCount: Number(permissionRows[0]?.[0] || 0),
      assignedPagePermissionCount: Number(permissionRows[0]?.[1] || 0),
      menuCount,
      finalPath,
      pageHeading: String(pageHeading || '').trim(),
      notFoundCount,
      slaNotificationTabCount,
      notificationRequests
    }
    await testInfo.attach('sla-notification-access.json', {
      body: Buffer.from(JSON.stringify(accessEvidence, null, 2)),
      contentType: 'application/json'
    })

    // SLA 通知按钮权限必须对应一个真实可达页面，否则接收人无法使用专用查询和已读接口。
    expect(accessEvidence, 'SLA 通知专用权限必须对应真实可达且会发起本人查询的页面').toMatchObject({
      assignedSlaNotificationPermissionCount: 1,
      assignedPagePermissionCount: 1,
      menuCount: 1,
      finalPath: '/workflow/extensions/bpmnEvent',
      pageHeading: '错误、升级与审批 SLA',
      notFoundCount: 0,
      slaNotificationTabCount: 1,
      notificationRequests: expect.arrayContaining(['GET'])
    })
    failed = false
  } finally {
    await session.close(failed)
  }
})
