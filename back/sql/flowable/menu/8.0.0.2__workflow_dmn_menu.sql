-- 为已安装环境增量加入 DMN 决策页面和按钮权限；脚本可重复执行。

SET @wf_root_menu_id = (
    SELECT MIN(menu_id) FROM sys_menu
    WHERE parent_id = 0 AND path = 'workflow' AND menu_type = 'M'
);

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 'DMN 决策', @wf_root_menu_id, 8, 'dmn',
       'workflow/dmn/index', NULL, 'WorkflowDmn',
       1, 0, 'C', '0', '0', 'workflow:dmn:list', 'fork',
       'admin', NOW(), '', NULL, 'Flowable 官方 DMN 决策版本管理菜单'
WHERE @wf_root_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:dmn:list'
  );

SET @wf_dmn_menu_id = (
    SELECT MIN(menu_id) FROM sys_menu WHERE perms = 'workflow:dmn:list'
);

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 'DMN 部署', @wf_dmn_menu_id, 1, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'workflow:dmn:add', '#',
       'admin', NOW(), '', NULL, '部署经过 XML 安全门禁的官方 DMN 决策'
WHERE @wf_dmn_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:dmn:add'
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT 'DMN 删除', @wf_dmn_menu_id, 2, '', NULL, NULL, '',
       1, 0, 'F', '0', '0', 'workflow:dmn:remove', '#',
       'admin', NOW(), '', NULL, '删除未被流程冻结快照引用的 DMN 来源部署'
WHERE @wf_dmn_menu_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu WHERE perms = 'workflow:dmn:remove'
  );

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_info.role_id, menu.menu_id
FROM sys_role role_info
JOIN sys_menu menu ON menu.perms IN (
    'workflow:dmn:list', 'workflow:dmn:add', 'workflow:dmn:remove'
)
WHERE role_info.role_key IN ('workflow_admin', 'workflow_designer')
  AND role_info.del_flag = '0';

SET @wf_root_menu_id = NULL;
SET @wf_dmn_menu_id = NULL;
