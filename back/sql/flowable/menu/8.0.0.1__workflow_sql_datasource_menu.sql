-- 为已安装环境增量加入 SQL 数据源页面和按钮权限；脚本可重复执行。

SET @wf_root_menu_id = (
    SELECT MIN(menu_id) FROM sys_menu
    WHERE parent_id = 0 AND path = 'workflow' AND menu_type = 'M'
);

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 'SQL 数据源', @wf_root_menu_id, 7, 'sqlDatasource',
       'workflow/sqlDatasource/index', NULL, 'WorkflowSqlDatasource',
       1, 0, 'C', '0', '0', 'workflow:sqlDatasource:list', 'database',
       'admin', NOW(), '', NULL, 'SQL 连接器数据源白名单与不可回退修订管理菜单'
WHERE @wf_root_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:sqlDatasource:list'
  );

SET @wf_sql_datasource_menu_id = (
    SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'workflow:sqlDatasource:list'
);

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT '数据源新增', @wf_sql_datasource_menu_id, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'workflow:sqlDatasource:add', '#',
       'admin', NOW(), '', NULL, '新增 SQL 连接器受控数据源'
WHERE @wf_sql_datasource_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:sqlDatasource:add'
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT '数据源修订', @wf_sql_datasource_menu_id, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'workflow:sqlDatasource:edit', '#',
       'admin', NOW(), '', NULL, '发布 SQL 数据源新修订或变更启用状态'
WHERE @wf_sql_datasource_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:sqlDatasource:edit'
  );

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_info.role_id, menu.menu_id
FROM sys_role role_info
JOIN sys_menu menu ON menu.perms IN (
    'workflow:sqlDatasource:list',
    'workflow:sqlDatasource:add',
    'workflow:sqlDatasource:edit'
)
WHERE role_info.role_key IN ('workflow_admin', 'workflow_designer')
  AND role_info.del_flag = '0';

SET @wf_root_menu_id = NULL;
SET @wf_sql_datasource_menu_id = NULL;
