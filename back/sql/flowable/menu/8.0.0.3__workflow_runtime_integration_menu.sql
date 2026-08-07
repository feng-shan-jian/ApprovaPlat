-- 为已安装环境增量加入集成账号和运行事件审计菜单；脚本可重复执行。

SET @wf_root_menu_id = (
    SELECT MIN(menu_id) FROM sys_menu
    WHERE parent_id = 0 AND path = 'workflow' AND menu_type = 'M'
);

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT '集成账号', @wf_root_menu_id, 9, 'integrationCredential',
       'workflow/integrationCredential/index', NULL, 'WorkflowIntegrationCredential',
       1, 0, 'C', '0', '0', 'workflow:integrationCredential:list', 'key',
       'admin', NOW(), '', NULL, '集成 Token 范围、轮换、吊销和限流管理菜单'
WHERE @wf_root_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:integrationCredential:list'
  );

SET @wf_integration_menu_id = (
    SELECT MIN(menu_id) FROM sys_menu
    WHERE perms = 'workflow:integrationCredential:list'
);

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT '集成账号新增', @wf_integration_menu_id, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'workflow:integrationCredential:add', '#',
       'admin', NOW(), '', NULL, '创建仅返回一次明文 Token 的工作流集成账号'
WHERE @wf_integration_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:integrationCredential:add'
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT '集成 Token 轮换', @wf_integration_menu_id, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'workflow:integrationCredential:rotate', '#',
       'admin', NOW(), '', NULL, '原子轮换 Token 并立即使旧 Token 失效'
WHERE @wf_integration_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:integrationCredential:rotate'
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT '集成账号吊销', @wf_integration_menu_id, 3, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'workflow:integrationCredential:revoke', '#',
       'admin', NOW(), '', NULL, '永久吊销 Token 并保留历史运行事件审计'
WHERE @wf_integration_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:integrationCredential:revoke'
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT '运行事件', @wf_root_menu_id, 10, 'runtimeEvent',
       'workflow/runtimeEvent/index', NULL, 'WorkflowRuntimeEvent',
       1, 0, 'C', '0', '0', 'workflow:runtimeEvent:list', 'list',
       'admin', NOW(), '', NULL, '消息、信号和 ReceiveTask 运行事件脱敏审计菜单'
WHERE @wf_root_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:runtimeEvent:list'
  );

-- 流程管理员拥有账号管理和运行审计，流程审计员只拥有运行事件只读入口。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_info.role_id, menu.menu_id
FROM sys_role role_info
JOIN sys_menu menu ON menu.perms IN (
    'workflow:integrationCredential:list',
    'workflow:integrationCredential:add',
    'workflow:integrationCredential:rotate',
    'workflow:integrationCredential:revoke',
    'workflow:runtimeEvent:list'
)
WHERE role_info.role_key = 'workflow_admin'
  AND role_info.del_flag = '0';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_info.role_id, menu.menu_id
FROM sys_role role_info
JOIN sys_menu menu ON menu.perms = 'workflow:runtimeEvent:list'
WHERE role_info.role_key = 'workflow_auditor'
  AND role_info.del_flag = '0';

SET @wf_root_menu_id = NULL;
SET @wf_integration_menu_id = NULL;
