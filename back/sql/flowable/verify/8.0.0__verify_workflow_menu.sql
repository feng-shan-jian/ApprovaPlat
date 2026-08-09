-- Flowable 8 工作流菜单、权限与职责分离角色只读验收脚本。
-- 所有检查都应返回 PASS；本脚本不会创建、修改或删除数据。

WITH workflow_menu AS (
    SELECT menu_id, menu_name, parent_id, path, component, route_name, menu_type, perms
    FROM sys_menu
    WHERE (menu_type = 'M' AND path IN ('workflow', 'office', 'extensions'))
       OR perms LIKE 'workflow:%'
)
SELECT
    'workflow_menu_count' AS check_name,
    CASE
        WHEN COUNT(*) = 87
         AND COUNT(DISTINCT CASE
                 WHEN menu_type = 'M' THEN CONCAT('path:', path)
                 ELSE CONCAT('perms:', perms)
             END) = 87
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT('rows=', COUNT(*), ', natural_keys=', COUNT(DISTINCT CASE
        WHEN menu_type = 'M' THEN CONCAT('path:', path)
        ELSE CONCAT('perms:', perms)
    END)) AS detail
FROM workflow_menu;

WITH workflow_directory AS (
    SELECT menu_id, parent_id, path
    FROM sys_menu
    WHERE menu_type = 'M' AND path IN ('workflow', 'office', 'extensions')
),
workflow_page AS (
    SELECT page.menu_id, page.parent_id, page.component, page.route_name, page.perms,
           directory.path AS parent_path
    FROM sys_menu page
    JOIN workflow_directory directory ON directory.menu_id = page.parent_id
    WHERE page.menu_type = 'C'
      AND page.perms LIKE 'workflow:%'
),
workflow_button AS (
    SELECT button.menu_id
    FROM sys_menu button
    JOIN workflow_page page ON page.menu_id = button.parent_id
    WHERE button.menu_type = 'F'
      AND button.perms LIKE 'workflow:%'
)
SELECT
    'workflow_menu_tree' AS check_name,
    CASE
        WHEN (SELECT COUNT(*) FROM workflow_directory) = 3
         AND (SELECT COUNT(*)
              FROM workflow_directory extension_directory
              JOIN workflow_directory workflow_directory_root
                ON workflow_directory_root.menu_id = extension_directory.parent_id
              WHERE extension_directory.path = 'extensions'
                AND workflow_directory_root.path = 'workflow') = 1
         AND (SELECT COUNT(*) FROM workflow_page) = 19
         AND (SELECT COUNT(*) FROM workflow_button) = 65
         AND (SELECT COUNT(*) FROM workflow_page
              WHERE parent_path = 'workflow'
                AND perms IN ('workflow:category:list', 'workflow:form:list',
                              'workflow:model:list', 'workflow:deploy:list')) = 4
         AND (SELECT COUNT(*) FROM workflow_page
              WHERE parent_path = 'extensions'
                AND perms IN (
                    'workflow:extension:list', 'workflow:connector:list',
                    'workflow:sqlDatasource:list', 'workflow:integrationCredential:list',
                    'workflow:dmn:list', 'workflow:runtimeEvent:list',
                    'workflow:collaboration:list', 'workflow:bpmnEvent:list',
                    'workflow:process:manageList'
                )) = 9
         AND (SELECT COUNT(*) FROM workflow_page WHERE parent_path = 'office') = 6
         AND (SELECT COUNT(*) FROM workflow_page
              WHERE component IS NULL OR component = '' OR route_name = '') = 0
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'directories=', (SELECT COUNT(*) FROM workflow_directory),
        ', extension_parent=', (SELECT COUNT(*)
                                 FROM workflow_directory extension_directory
                                 JOIN workflow_directory workflow_directory_root
                                   ON workflow_directory_root.menu_id = extension_directory.parent_id
                                 WHERE extension_directory.path = 'extensions'
                                   AND workflow_directory_root.path = 'workflow'),
        ', pages=', (SELECT COUNT(*) FROM workflow_page),
        ', buttons=', (SELECT COUNT(*) FROM workflow_button),
        ', common_management_pages=', (SELECT COUNT(*) FROM workflow_page
                                        WHERE parent_path = 'workflow'),
        ', extended_management_pages=', (SELECT COUNT(*) FROM workflow_page
                                          WHERE parent_path = 'extensions'),
        ', office_pages=', (SELECT COUNT(*) FROM workflow_page WHERE parent_path = 'office'),
        ', invalid_routes=', (SELECT COUNT(*) FROM workflow_page
                               WHERE component IS NULL OR component = '' OR route_name = '')
    ) AS detail;

-- 任一角色只要获得扩展流程页面，就必须同时拥有父目录，否则动态侧栏无法展示真实入口。
WITH extended_directory AS (
    SELECT menu_id
    FROM sys_menu
    WHERE menu_type = 'M' AND path = 'extensions'
),
assigned_extended_role AS (
    SELECT DISTINCT role_menu.role_id
    FROM sys_role_menu role_menu
    JOIN sys_menu page ON page.menu_id = role_menu.menu_id
    JOIN extended_directory directory ON directory.menu_id = page.parent_id
    WHERE page.menu_type = 'C'
),
missing_parent AS (
    SELECT assigned.role_id
    FROM assigned_extended_role assigned
    CROSS JOIN extended_directory directory
    LEFT JOIN sys_role_menu parent_assignment
      ON parent_assignment.role_id = assigned.role_id
     AND parent_assignment.menu_id = directory.menu_id
    WHERE parent_assignment.role_id IS NULL
)
SELECT
    'workflow_extended_management_role_visibility' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('missing_parent_assignments=', COUNT(*)) AS detail
FROM missing_parent;

SELECT
    'workflow_retired_permissions' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('legacy_rows=', COUNT(*)) AS detail
FROM sys_menu
WHERE perms IN ('workflow:model:import', 'workflow:deploy:status');

WITH expected_role AS (
    SELECT 'workflow_admin' AS role_key
    UNION ALL SELECT 'workflow_designer'
    UNION ALL SELECT 'workflow_starter'
    UNION ALL SELECT 'workflow_approver'
    UNION ALL SELECT 'workflow_auditor'
),
actual_role AS (
    SELECT role_key, COUNT(*) AS role_count
    FROM sys_role
    WHERE role_key IN (SELECT role_key FROM expected_role)
      AND status = '0'
      AND del_flag = '0'
    GROUP BY role_key
)
SELECT
    'workflow_roles' AS check_name,
    CASE
        WHEN COUNT(actual.role_key) = 5
         AND SUM(actual.role_count = 1) = 5
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'active_roles=', COUNT(actual.role_key),
        ', duplicate_roles=', COALESCE(SUM(actual.role_count > 1), 0)
    ) AS detail
FROM expected_role expected
LEFT JOIN actual_role actual ON actual.role_key = expected.role_key;

WITH workflow_menu AS (
    SELECT menu_id
    FROM sys_menu
    WHERE (menu_type = 'M' AND path IN ('workflow', 'office', 'extensions'))
       OR perms LIKE 'workflow:%'
),
workflow_admin AS (
    SELECT MIN(role_id) AS role_id
    FROM sys_role
    WHERE role_key = 'workflow_admin' AND status = '0' AND del_flag = '0'
)
SELECT
    'workflow_admin_menu_scope' AS check_name,
    CASE
        WHEN (SELECT COUNT(*) FROM workflow_menu) = 87
         AND COUNT(*) = 87
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT('assigned=', COUNT(*), ', expected=87') AS detail
FROM sys_role_menu role_menu
JOIN workflow_admin role_info ON role_info.role_id = role_menu.role_id
JOIN workflow_menu menu_info ON menu_info.menu_id = role_menu.menu_id;

WITH restricted_role AS (
    SELECT role_id, role_key
    FROM sys_role
    WHERE role_key IN ('workflow_designer', 'workflow_starter',
                       'workflow_approver', 'workflow_auditor')
      AND status = '0'
      AND del_flag = '0'
),
restricted_assignment AS (
    SELECT role_info.role_key, menu.perms
    FROM restricted_role role_info
    JOIN sys_role_menu role_menu ON role_menu.role_id = role_info.role_id
    JOIN sys_menu menu ON menu.menu_id = role_menu.menu_id
    WHERE menu.perms IN ('workflow:process:manageList',
                         'workflow:process:manageExport',
                         'workflow:process:remove',
                         'workflow:process:state', 'workflow:process:terminate')
),
workflow_admin AS (
    SELECT MIN(role_id) AS role_id
    FROM sys_role
    WHERE role_key = 'workflow_admin' AND status = '0' AND del_flag = '0'
),
admin_management_assignment AS (
    SELECT COUNT(DISTINCT menu.perms) AS permission_count
    FROM workflow_admin role_info
    JOIN sys_role_menu role_menu ON role_menu.role_id = role_info.role_id
    JOIN sys_menu menu ON menu.menu_id = role_menu.menu_id
    WHERE menu.perms IN ('workflow:process:manageList',
                         'workflow:process:manageExport')
)
SELECT
    'workflow_admin_only_instance_management' AS check_name,
    CASE
        WHEN COUNT(*) = 0
         AND (SELECT permission_count FROM admin_management_assignment) = 2
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'unauthorized_assignments=', COUNT(*),
        ', roles=', COALESCE(GROUP_CONCAT(role_key ORDER BY role_key SEPARATOR ','), 'none'),
        ', admin_management_permissions=',
        (SELECT permission_count FROM admin_management_assignment)
    ) AS detail
FROM restricted_assignment;

WITH audit_write_permissions AS (
    SELECT menu.perms
    FROM sys_role role_info
    JOIN sys_role_menu role_menu ON role_menu.role_id = role_info.role_id
    JOIN sys_menu menu ON menu.menu_id = role_menu.menu_id
    WHERE role_info.role_key = 'workflow_auditor'
      AND role_info.status = '0'
      AND role_info.del_flag = '0'
      AND menu.perms IN (
          'workflow:category:add', 'workflow:category:edit', 'workflow:category:remove',
          'workflow:form:add', 'workflow:form:edit', 'workflow:form:remove',
          'workflow:model:add', 'workflow:model:edit', 'workflow:model:remove',
          'workflow:model:save', 'workflow:model:deploy',
           'workflow:extension:add', 'workflow:extension:edit',
           'workflow:extension:version:add', 'workflow:extension:remove',
           'workflow:connector:add', 'workflow:connector:edit',
          'workflow:dmn:add', 'workflow:dmn:remove',
          'workflow:integrationCredential:add',
          'workflow:integrationCredential:rotate',
          'workflow:integrationCredential:revoke',
          'workflow:collaboration:retry', 'workflow:collaboration:cancel',
          'workflow:deploy:remove', 'workflow:deploy:state',
          'workflow:process:start', 'workflow:process:remove',
          'workflow:process:cancel', 'workflow:process:approval',
          'workflow:process:claim', 'workflow:process:revoke',
          'workflow:process:state', 'workflow:process:terminate',
          'workflow:attachment:upload', 'workflow:attachment:remove'
      )
)
SELECT
    'workflow_auditor_read_only' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'write_permissions=', COUNT(*),
        ', values=', COALESCE(GROUP_CONCAT(perms ORDER BY perms SEPARATOR ','), 'none')
    ) AS detail
FROM audit_write_permissions;
