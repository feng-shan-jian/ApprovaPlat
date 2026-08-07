-- Flowable 8 工作流菜单、按钮权限和职责分离角色。
-- 本脚本使用自然键幂等写入，不依赖参考工程中的 menu_id/role_id。
-- 脚本不会给任何现有用户分配角色；用户授权必须由管理员在系统界面中显式完成。

-- 先清理已经确认替换或改名的权限，并保留现有角色到规范权限的关联。
UPDATE sys_menu legacy
LEFT JOIN sys_menu canonical
       ON canonical.perms = 'workflow:deploy:state'
      AND canonical.menu_id <> legacy.menu_id
SET legacy.perms = 'workflow:deploy:state',
    legacy.update_by = 'admin',
    legacy.update_time = NOW(),
    legacy.remark = '部署激活/挂起状态管理'
WHERE legacy.perms = 'workflow:deploy:status'
  AND canonical.menu_id IS NULL;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_menu.role_id, canonical.menu_id
FROM sys_role_menu role_menu
JOIN sys_menu legacy ON legacy.menu_id = role_menu.menu_id
JOIN sys_menu canonical ON canonical.perms = 'workflow:deploy:state'
WHERE legacy.perms = 'workflow:deploy:status';

DELETE role_menu
FROM sys_role_menu role_menu
JOIN sys_menu legacy ON legacy.menu_id = role_menu.menu_id
WHERE legacy.perms IN ('workflow:deploy:status', 'workflow:model:import');

DELETE FROM sys_menu
WHERE perms IN ('workflow:deploy:status', 'workflow:model:import');

DROP TEMPORARY TABLE IF EXISTS tmp_workflow_menu_seed;
CREATE TEMPORARY TABLE tmp_workflow_menu_seed
(
    seed_key   VARCHAR(100) NOT NULL COMMENT '目录使用 path，页面和按钮使用 perms',
    parent_key VARCHAR(100)          COMMENT '父目录 path 或父页面 perms',
    menu_name  VARCHAR(50)  NOT NULL,
    order_num  INT          NOT NULL,
    path       VARCHAR(200) NOT NULL DEFAULT '',
    component  VARCHAR(255)          DEFAULT NULL,
    route_name VARCHAR(50)  NOT NULL DEFAULT '',
    menu_type  CHAR(1)      NOT NULL,
    perms      VARCHAR(100)          DEFAULT NULL,
    icon       VARCHAR(100) NOT NULL DEFAULT '#',
    remark     VARCHAR(500) NOT NULL DEFAULT '',
    PRIMARY KEY (seed_key)
) ENGINE = InnoDB;

-- 两个目录、十九个菜单页和六十五个真实按钮权限，共八十六条目标记录。
INSERT INTO tmp_workflow_menu_seed
    (seed_key, parent_key, menu_name, order_num, path, component, route_name,
     menu_type, perms, icon, remark)
VALUES
    ('workflow', NULL, '流程管理', 4, 'workflow', NULL, 'Workflow',
     'M', NULL, 'guide', '流程设计与部署管理目录'),
    ('office', NULL, '办公管理', 5, 'office', NULL, 'WorkflowOffice',
     'M', NULL, 'people', '流程发起与办理目录'),

    ('workflow:category:list', 'workflow', '流程分类', 1, 'category',
     'workflow/category/index', 'WorkflowCategory', 'C', 'workflow:category:list',
     'tree-table', '流程分类菜单'),
    ('workflow:form:list', 'workflow', '表单配置', 2, 'form',
     'workflow/form/index', 'WorkflowForm', 'C', 'workflow:form:list',
     'form', '流程表单配置菜单'),
    ('workflow:model:list', 'workflow', '流程模型', 3, 'model',
     'workflow/model/index', 'WorkflowModel', 'C', 'workflow:model:list',
     'component', '流程模型菜单'),
    ('workflow:deploy:list', 'workflow', '部署管理', 4, 'deploy',
     'workflow/deploy/index', 'WorkflowDeploy', 'C', 'workflow:deploy:list',
     'server', '流程部署管理菜单'),
    ('workflow:extension:list', 'workflow', '扩展注册表', 5, 'extension',
     'workflow/extension/index', 'WorkflowExtension', 'C', 'workflow:extension:list',
     'connection', 'BPMN 受控扩展目录与不可变版本管理菜单'),
    ('workflow:connector:list', 'workflow', '连接端点', 6, 'connector',
     'workflow/connector/index', 'WorkflowConnector', 'C', 'workflow:connector:list',
     'link', 'HTTP 连接器端点白名单与不可回退修订管理菜单'),
    ('workflow:sqlDatasource:list', 'workflow', 'SQL 数据源', 7, 'sqlDatasource',
     'workflow/sqlDatasource/index', 'WorkflowSqlDatasource', 'C', 'workflow:sqlDatasource:list',
     'database', 'SQL 连接器数据源白名单与不可回退修订管理菜单'),
    ('workflow:dmn:list', 'workflow', 'DMN 决策', 8, 'dmn',
     'workflow/dmn/index', 'WorkflowDmn', 'C', 'workflow:dmn:list',
     'fork', 'Flowable 官方 DMN 决策版本管理菜单'),
    ('workflow:integrationCredential:list', 'workflow', '集成账号', 9, 'integrationCredential',
     'workflow/integrationCredential/index', 'WorkflowIntegrationCredential', 'C',
     'workflow:integrationCredential:list', 'key', '集成 Token 范围、轮换、吊销和限流管理菜单'),
    ('workflow:runtimeEvent:list', 'workflow', '运行事件', 10, 'runtimeEvent',
     'workflow/runtimeEvent/index', 'WorkflowRuntimeEvent', 'C', 'workflow:runtimeEvent:list',
     'list', '消息、信号和 ReceiveTask 运行事件脱敏审计菜单'),
    ('workflow:collaboration:list', 'workflow', '多池协作', 11, 'collaboration',
     'workflow/collaboration/index', 'WorkflowCollaboration', 'C',
     'workflow:collaboration:list', 'connection', 'Participant/MessageFlow 入站、outbox、死信和补偿管理菜单'),
    ('workflow:bpmnEvent:list', 'workflow', '错误与升级', 12, 'bpmnEvent',
     'workflow/bpmnEvent/index', 'WorkflowBpmnEvent', 'C', 'workflow:bpmnEvent:list',
     'warning', 'BPMN 业务错误与升级编码、运行审计和通知管理菜单'),
    ('workflow:process:manageList', 'workflow', '实例运维', 13, 'instance',
     'workflow/work/manage', 'WorkflowManage', 'C', 'workflow:process:manageList',
     'list', '流程管理员跨用户实例运维菜单'),
    ('workflow:process:startList', 'office', '新建流程', 1, 'create',
     'workflow/work/index', 'WorkflowCreate', 'C', 'workflow:process:startList',
     'guide', '当前用户可发起流程菜单'),
    ('workflow:process:ownList', 'office', '我的流程', 2, 'own',
     'workflow/work/own', 'WorkflowOwn', 'C', 'workflow:process:ownList',
     'cascader', '当前用户发起流程菜单'),
    ('workflow:process:todoList', 'office', '待办任务', 3, 'todo',
     'workflow/work/todo', 'WorkflowTodo', 'C', 'workflow:process:todoList',
     'time-range', '当前用户待办任务菜单'),
    ('workflow:process:claimList', 'office', '待签任务', 4, 'claim',
     'workflow/work/claim', 'WorkflowClaim', 'C', 'workflow:process:claimList',
     'checkbox', '当前用户待签任务菜单'),
    ('workflow:process:finishedList', 'office', '已办任务', 5, 'finished',
     'workflow/work/finished', 'WorkflowFinished', 'C', 'workflow:process:finishedList',
     'checkbox', '当前用户已办任务菜单'),
    ('workflow:process:copyList', 'office', '抄送我的', 6, 'copy',
     'workflow/work/copy', 'WorkflowCopy', 'C', 'workflow:process:copyList',
     'message', '当前用户抄送记录菜单'),

    ('workflow:category:query', 'workflow:category:list', '分类查询', 1, '', NULL, '',
     'F', 'workflow:category:query', '#', '查询流程分类详情'),
    ('workflow:category:add', 'workflow:category:list', '分类新增', 2, '', NULL, '',
     'F', 'workflow:category:add', '#', '新增流程分类'),
    ('workflow:category:edit', 'workflow:category:list', '分类编辑', 3, '', NULL, '',
     'F', 'workflow:category:edit', '#', '编辑流程分类'),
    ('workflow:category:remove', 'workflow:category:list', '分类删除', 4, '', NULL, '',
     'F', 'workflow:category:remove', '#', '受引用检查保护的分类删除'),
    ('workflow:category:export', 'workflow:category:list', '分类导出', 5, '', NULL, '',
     'F', 'workflow:category:export', '#', '有界导出流程分类'),

    ('workflow:form:query', 'workflow:form:list', '表单查询', 1, '', NULL, '',
     'F', 'workflow:form:query', '#', '查询流程表单模板'),
    ('workflow:form:add', 'workflow:form:list', '表单新增', 2, '', NULL, '',
     'F', 'workflow:form:add', '#', '新增流程表单模板'),
    ('workflow:form:edit', 'workflow:form:list', '表单修改', 3, '', NULL, '',
     'F', 'workflow:form:edit', '#', '修改当前流程表单模板'),
    ('workflow:form:remove', 'workflow:form:list', '表单删除', 4, '', NULL, '',
     'F', 'workflow:form:remove', '#', '受引用检查保护的表单删除'),
    ('workflow:form:export', 'workflow:form:list', '表单导出', 5, '', NULL, '',
     'F', 'workflow:form:export', '#', '有界导出流程表单元数据'),

    ('workflow:model:query', 'workflow:model:list', '模型查询', 1, '', NULL, '',
     'F', 'workflow:model:query', '#', '查询模型元数据和 BPMN'),
    ('workflow:model:add', 'workflow:model:list', '模型新增', 2, '', NULL, '',
     'F', 'workflow:model:add', '#', '新增流程模型'),
    ('workflow:model:edit', 'workflow:model:list', '模型修改', 3, '', NULL, '',
     'F', 'workflow:model:edit', '#', '修改模型元数据'),
    ('workflow:model:remove', 'workflow:model:list', '模型删除', 4, '', NULL, '',
     'F', 'workflow:model:remove', '#', '受部署引用检查保护的模型删除'),
    ('workflow:model:export', 'workflow:model:list', '模型导出', 5, '', NULL, '',
     'F', 'workflow:model:export', '#', '有界导出流程模型元数据'),
    ('workflow:model:designer', 'workflow:model:list', '模型设计', 6, '', NULL, '',
     'F', 'workflow:model:designer', '#', '进入 BPMN 设计器'),
    ('workflow:model:save', 'workflow:model:list', '模型保存', 7, '', NULL, '',
     'F', 'workflow:model:save', '#', '原子保存并管理模型版本'),
    ('workflow:model:deploy', 'workflow:model:list', '流程部署', 8, '', NULL, '',
     'F', 'workflow:model:deploy', '#', '部署模型并固化表单快照'),

    ('workflow:extension:add', 'workflow:extension:list', '扩展新增', 1, '', NULL, '',
     'F', 'workflow:extension:add', '#', '新增受控 BPMN 扩展目录'),
    ('workflow:extension:edit', 'workflow:extension:list', '扩展状态', 2, '', NULL, '',
     'F', 'workflow:extension:edit', '#', '启用或停用扩展目录'),
    ('workflow:extension:version:add', 'workflow:extension:list', '扩展版本发布', 3, '', NULL, '',
     'F', 'workflow:extension:version:add', '#', '从服务端已安装处理器发布不可变扩展版本'),
    ('workflow:extension:remove', 'workflow:extension:list', '扩展删除', 4, '', NULL, '',
     'F', 'workflow:extension:remove', '#', '删除未被部署快照引用的停用扩展目录'),

    ('workflow:connector:add', 'workflow:connector:list', '端点新增', 1, '', NULL, '',
     'F', 'workflow:connector:add', '#', '新增 HTTP 连接器端点白名单'),
    ('workflow:connector:edit', 'workflow:connector:list', '端点修订', 2, '', NULL, '',
     'F', 'workflow:connector:edit', '#', '发布 HTTP 端点新修订或变更启用状态'),

    ('workflow:sqlDatasource:add', 'workflow:sqlDatasource:list', '数据源新增', 1, '', NULL, '',
     'F', 'workflow:sqlDatasource:add', '#', '新增 SQL 连接器受控数据源'),
    ('workflow:sqlDatasource:edit', 'workflow:sqlDatasource:list', '数据源修订', 2, '', NULL, '',
     'F', 'workflow:sqlDatasource:edit', '#', '发布 SQL 数据源新修订或变更启用状态'),

    ('workflow:dmn:add', 'workflow:dmn:list', 'DMN 部署', 1, '', NULL, '',
     'F', 'workflow:dmn:add', '#', '部署经过 XML 安全门禁的官方 DMN 决策'),
    ('workflow:dmn:remove', 'workflow:dmn:list', 'DMN 删除', 2, '', NULL, '',
     'F', 'workflow:dmn:remove', '#', '删除未被流程冻结快照引用的 DMN 来源部署'),

    ('workflow:integrationCredential:add', 'workflow:integrationCredential:list',
     '集成账号新增', 1, '', NULL, '', 'F', 'workflow:integrationCredential:add', '#',
     '创建仅返回一次明文 Token 的工作流集成账号'),
    ('workflow:integrationCredential:rotate', 'workflow:integrationCredential:list',
     '集成 Token 轮换', 2, '', NULL, '', 'F', 'workflow:integrationCredential:rotate', '#',
     '原子轮换 Token 并立即使旧 Token 失效'),
    ('workflow:integrationCredential:revoke', 'workflow:integrationCredential:list',
     '集成账号吊销', 3, '', NULL, '', 'F', 'workflow:integrationCredential:revoke', '#',
     '永久吊销 Token 并保留历史运行事件审计'),

    ('workflow:collaboration:audit', 'workflow:collaboration:list',
     '协作消息审计', 1, '', NULL, '', 'F', 'workflow:collaboration:audit', '#',
     '查询单条入站或出站消息的逐次状态审计'),
    ('workflow:collaboration:retry', 'workflow:collaboration:list',
     '协作消息补偿', 2, '', NULL, '', 'F', 'workflow:collaboration:retry', '#',
     '为入站或出站死信重新开启有界重试周期'),
    ('workflow:collaboration:cancel', 'workflow:collaboration:list',
     '协作消息取消', 3, '', NULL, '', 'F', 'workflow:collaboration:cancel', '#',
     '取消尚未送达且没有外部成功副作用的 outbox'),
    ('workflow:bpmnEvent:add', 'workflow:bpmnEvent:list', '事件编码新增', 1, '', NULL, '',
     'F', 'workflow:bpmnEvent:add', '#', '新增 BPMN 业务错误或升级稳定编码'),
    ('workflow:bpmnEvent:edit', 'workflow:bpmnEvent:list', '事件编码维护', 2, '', NULL, '',
     'F', 'workflow:bpmnEvent:edit', '#', '维护名称通知策略并启停编码目录'),
    ('workflow:bpmnEvent:audit', 'workflow:bpmnEvent:list', '事件运行审计', 3, '', NULL, '',
     'F', 'workflow:bpmnEvent:audit', '#', '查询 BPMN 错误与升级专用运行审计'),
    ('workflow:bpmnEvent:notification', 'workflow:bpmnEvent:list', '事件通知查询', 4, '', NULL, '',
     'F', 'workflow:bpmnEvent:notification', '#', '查询并处理当前用户 BPMN 事件通知'),
    ('workflow:sla:list', 'workflow:bpmnEvent:list', 'SLA 日历查询', 5, '', NULL, '',
     'F', 'workflow:sla:list', '#', '查询审批 SLA 正式业务日历'),
    ('workflow:sla:add', 'workflow:bpmnEvent:list', 'SLA 日历新增', 6, '', NULL, '',
     'F', 'workflow:sla:add', '#', '新增审批 SLA 正式业务日历'),
    ('workflow:sla:edit', 'workflow:bpmnEvent:list', 'SLA 日历维护', 7, '', NULL, '',
     'F', 'workflow:sla:edit', '#', '修改和启停审批 SLA 业务日历'),
    ('workflow:sla:audit', 'workflow:bpmnEvent:list', 'SLA 运行审计', 8, '', NULL, '',
     'F', 'workflow:sla:audit', '#', '查询审批 SLA 执行状态和不可变审计'),
    ('workflow:sla:notification', 'workflow:bpmnEvent:list', 'SLA 通知查询', 9, '', NULL, '',
     'F', 'workflow:sla:notification', '#', '查询并处理当前用户审批 SLA 通知'),

    ('workflow:deploy:query', 'workflow:deploy:list', '部署查询', 1, '', NULL, '',
     'F', 'workflow:deploy:query', '#', '查询部署版本和 BPMN'),
    ('workflow:deploy:remove', 'workflow:deploy:list', '部署删除', 2, '', NULL, '',
     'F', 'workflow:deploy:remove', '#', '受实例引用检查保护的部署删除'),
    ('workflow:deploy:state', 'workflow:deploy:list', '部署状态', 3, '', NULL, '',
     'F', 'workflow:deploy:state', '#', '激活或挂起流程定义'),

    ('workflow:process:manageExport', 'workflow:process:manageList', '实例运维导出', 1, '', NULL, '',
     'F', 'workflow:process:manageExport', '#', '导出管理员筛选范围内的跨用户流程实例'),

    ('workflow:process:start', 'workflow:process:startList', '发起流程', 1, '', NULL, '',
     'F', 'workflow:process:start', '#', '按部署表单快照发起流程'),
    ('workflow:process:startExport', 'workflow:process:startList', '新建流程导出', 2, '', NULL, '',
     'F', 'workflow:process:startExport', '#', '导出可发起流程定义'),
    ('workflow:attachment:upload', 'workflow:process:startList', '附件上传', 3, '', NULL, '',
     'F', 'workflow:attachment:upload', '#', '上传当前用户临时工作流附件'),
    ('workflow:attachment:query', 'workflow:process:startList', '附件读取', 4, '', NULL, '',
     'F', 'workflow:attachment:query', '#', '按附件或流程对象授权查询和下载附件'),
    ('workflow:attachment:remove', 'workflow:process:startList', '附件删除', 5, '', NULL, '',
     'F', 'workflow:attachment:remove', '#', '仅所有者删除未绑定临时附件'),
    ('workflow:process:query', 'workflow:process:ownList', '流程详情', 1, '', NULL, '',
     'F', 'workflow:process:query', '#', '叠加对象授权的流程详情、变量和流程图'),
    ('workflow:process:remove', 'workflow:process:ownList', '流程删除', 2, '', NULL, '',
     'F', 'workflow:process:remove', '#', '受控删除已结束流程实例'),
    ('workflow:process:cancel', 'workflow:process:ownList', '流程取消', 3, '', NULL, '',
     'F', 'workflow:process:cancel', '#', '发起人取消运行流程'),
    ('workflow:process:ownExport', 'workflow:process:ownList', '我的流程导出', 4, '', NULL, '',
     'F', 'workflow:process:ownExport', '#', '导出当前用户发起流程'),
    ('workflow:process:state', 'workflow:process:ownList', '实例状态管理', 5, '', NULL, '',
     'F', 'workflow:process:state', '#', '仅流程管理员激活或挂起运行实例'),
    ('workflow:process:terminate', 'workflow:process:ownList', '实例终止', 6, '', NULL, '',
     'F', 'workflow:process:terminate', '#', '仅流程管理员终止运行实例'),
    ('workflow:process:approval', 'workflow:process:todoList', '流程办理', 1, '', NULL, '',
     'F', 'workflow:process:approval', '#', '完成、驳回、退回、委派和转办任务'),
    ('workflow:process:todoExport', 'workflow:process:todoList', '待办流程导出', 2, '', NULL, '',
     'F', 'workflow:process:todoExport', '#', '导出当前用户待办任务'),
    ('workflow:process:claim', 'workflow:process:claimList', '流程签收', 1, '', NULL, '',
     'F', 'workflow:process:claim', '#', '认领和取消认领候选任务'),
    ('workflow:process:claimExport', 'workflow:process:claimList', '待签流程导出', 2, '', NULL, '',
     'F', 'workflow:process:claimExport', '#', '导出当前用户待签任务'),
    ('workflow:process:revoke', 'workflow:process:finishedList', '流程撤回', 1, '', NULL, '',
     'F', 'workflow:process:revoke', '#', '按执行树约束撤回本人已办任务'),
    ('workflow:process:finishedExport', 'workflow:process:finishedList', '已办流程导出', 2, '', NULL, '',
     'F', 'workflow:process:finishedExport', '#', '导出当前用户已办任务'),
    ('workflow:process:copyExport', 'workflow:process:copyList', '抄送流程导出', 1, '', NULL, '',
     'F', 'workflow:process:copyExport', '#', '导出抄送给当前用户的流程');

-- 目录按 path、页面和按钮按 perms 写入，保证重复执行不会新增重复记录。
INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT seed.menu_name, 0, seed.order_num, seed.path, seed.component, NULL, seed.route_name,
       1, 0, seed.menu_type, '0', '0', seed.perms, seed.icon,
       'admin', NOW(), '', NULL, seed.remark
FROM tmp_workflow_menu_seed seed
WHERE seed.menu_type = 'M'
  AND NOT EXISTS (
      SELECT 1
      FROM sys_menu menu
      WHERE menu.menu_type = 'M'
        AND menu.path = seed.seed_key
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT seed.menu_name, parent.menu_id, seed.order_num, seed.path, seed.component, NULL,
       seed.route_name, 1, 0, seed.menu_type, '0', '0', seed.perms, seed.icon,
       'admin', NOW(), '', NULL, seed.remark
FROM tmp_workflow_menu_seed seed
JOIN sys_menu parent
  ON parent.menu_type = 'M'
 AND parent.path = seed.parent_key
WHERE seed.menu_type = 'C'
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu menu WHERE menu.perms = seed.seed_key
  );

INSERT INTO sys_menu
    (menu_name, parent_id, order_num, path, component, `query`, route_name,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_by, create_time, update_by, update_time, remark)
SELECT seed.menu_name, parent.menu_id, seed.order_num, seed.path, seed.component, NULL,
       seed.route_name, 1, 0, seed.menu_type, '0', '0', seed.perms, seed.icon,
       'admin', NOW(), '', NULL, seed.remark
FROM tmp_workflow_menu_seed seed
JOIN sys_menu parent ON parent.perms = seed.parent_key
WHERE seed.menu_type = 'F'
  AND NOT EXISTS (
      SELECT 1 FROM sys_menu menu WHERE menu.perms = seed.seed_key
  );

DROP TEMPORARY TABLE IF EXISTS tmp_workflow_menu_actual;
CREATE TEMPORARY TABLE tmp_workflow_menu_actual
(
    seed_key VARCHAR(100) NOT NULL,
    menu_id  BIGINT       NOT NULL,
    PRIMARY KEY (seed_key),
    UNIQUE KEY uk_tmp_workflow_menu_id (menu_id)
) ENGINE = InnoDB;

INSERT INTO tmp_workflow_menu_actual (seed_key, menu_id)
SELECT seed.seed_key, MIN(menu.menu_id)
FROM tmp_workflow_menu_seed seed
JOIN sys_menu menu
  ON (seed.menu_type = 'M' AND menu.menu_type = 'M' AND menu.path = seed.seed_key)
  OR (seed.menu_type <> 'M' AND menu.perms = seed.seed_key)
GROUP BY seed.seed_key;

-- 将已存在的同自然键记录归一到目标父子关系和目标路由字段。
UPDATE sys_menu menu
JOIN tmp_workflow_menu_seed seed
  ON seed.menu_type = 'M'
 AND menu.menu_type = 'M'
 AND menu.path = seed.seed_key
SET menu.menu_name = seed.menu_name,
    menu.parent_id = 0,
    menu.order_num = seed.order_num,
    menu.component = seed.component,
    menu.`query` = NULL,
    menu.route_name = seed.route_name,
    menu.is_frame = 1,
    menu.is_cache = 0,
    menu.visible = '0',
    menu.status = '0',
    menu.perms = '',
    menu.icon = seed.icon,
    menu.update_by = 'admin',
    menu.update_time = NOW(),
    menu.remark = seed.remark;

UPDATE sys_menu menu
JOIN tmp_workflow_menu_seed seed
  ON seed.menu_type <> 'M'
 AND menu.perms = seed.seed_key
JOIN tmp_workflow_menu_actual parent ON parent.seed_key = seed.parent_key
SET menu.menu_name = seed.menu_name,
    menu.parent_id = parent.menu_id,
    menu.order_num = seed.order_num,
    menu.path = seed.path,
    menu.component = seed.component,
    menu.`query` = NULL,
    menu.route_name = seed.route_name,
    menu.is_frame = 1,
    menu.is_cache = 0,
    menu.menu_type = seed.menu_type,
    menu.visible = '0',
    menu.status = '0',
    menu.icon = seed.icon,
    menu.update_by = 'admin',
    menu.update_time = NOW(),
    menu.remark = seed.remark;

DROP TEMPORARY TABLE IF EXISTS tmp_workflow_role_seed;
CREATE TEMPORARY TABLE tmp_workflow_role_seed
(
    role_key  VARCHAR(100) NOT NULL,
    role_name VARCHAR(30)  NOT NULL,
    role_sort INT          NOT NULL,
    remark    VARCHAR(500) NOT NULL,
    PRIMARY KEY (role_key)
) ENGINE = InnoDB;

INSERT INTO tmp_workflow_role_seed (role_key, role_name, role_sort, remark)
VALUES
    ('workflow_admin', '流程管理员', 20, '流程配置、实例运维和全部工作流权限'),
    ('workflow_designer', '流程设计者', 21, '分类、表单、模型和部署设计权限'),
    ('workflow_starter', '流程发起人', 22, '发起、查看和管理本人流程权限'),
    ('workflow_approver', '流程审批人', 23, '认领、办理和查看本人参与流程权限'),
    ('workflow_auditor', '流程审计查看者', 24, '只读查看对象授权范围内的流程权限');

INSERT INTO sys_role
    (role_name, role_key, role_sort, data_scope, menu_check_strictly,
     dept_check_strictly, status, del_flag, create_by, create_time,
     update_by, update_time, remark)
SELECT seed.role_name, seed.role_key, seed.role_sort, '1', 1, 1, '0', '0',
       'admin', NOW(), '', NULL, seed.remark
FROM tmp_workflow_role_seed seed
WHERE NOT EXISTS (
    SELECT 1 FROM sys_role role_info WHERE role_info.role_key = seed.role_key
);

UPDATE sys_role role_info
JOIN tmp_workflow_role_seed seed ON seed.role_key = role_info.role_key
SET role_info.role_name = seed.role_name,
    role_info.role_sort = seed.role_sort,
    role_info.status = '0',
    role_info.del_flag = '0',
    role_info.update_by = 'admin',
    role_info.update_time = NOW(),
    role_info.remark = seed.remark;

DROP TEMPORARY TABLE IF EXISTS tmp_workflow_role_actual;
CREATE TEMPORARY TABLE tmp_workflow_role_actual
(
    role_key VARCHAR(100) NOT NULL,
    role_id  BIGINT       NOT NULL,
    PRIMARY KEY (role_key),
    UNIQUE KEY uk_tmp_workflow_role_id (role_id)
) ENGINE = InnoDB;

INSERT INTO tmp_workflow_role_actual (role_key, role_id)
SELECT seed.role_key, MIN(role_info.role_id)
FROM tmp_workflow_role_seed seed
JOIN sys_role role_info ON role_info.role_key = seed.role_key
GROUP BY seed.role_key;

DROP TEMPORARY TABLE IF EXISTS tmp_workflow_role_menu_seed;
CREATE TEMPORARY TABLE tmp_workflow_role_menu_seed
(
    role_key VARCHAR(100) NOT NULL,
    seed_key VARCHAR(100) NOT NULL,
    PRIMARY KEY (role_key, seed_key)
) ENGINE = InnoDB;

-- 流程管理员拥有全部工作流入口和按钮，包括受控实例运维权限。
INSERT INTO tmp_workflow_role_menu_seed (role_key, seed_key)
SELECT 'workflow_admin', seed_key FROM tmp_workflow_menu_seed;

-- 流程设计者仅管理分类、表单、模型和部署写能力，并只读查看扩展注册表。
INSERT INTO tmp_workflow_role_menu_seed (role_key, seed_key)
SELECT 'workflow_designer', seed_key
FROM tmp_workflow_menu_seed
WHERE seed_key IN (
    'workflow',
    'workflow:category:list', 'workflow:category:query', 'workflow:category:add',
    'workflow:category:edit', 'workflow:category:remove', 'workflow:category:export',
    'workflow:form:list', 'workflow:form:query', 'workflow:form:add',
    'workflow:form:edit', 'workflow:form:remove', 'workflow:form:export',
    'workflow:model:list', 'workflow:model:query', 'workflow:model:add',
    'workflow:model:edit', 'workflow:model:remove', 'workflow:model:export',
    'workflow:model:designer', 'workflow:model:save', 'workflow:model:deploy',
    'workflow:deploy:list', 'workflow:deploy:query', 'workflow:deploy:remove',
    'workflow:deploy:state', 'workflow:extension:list', 'workflow:connector:list',
    'workflow:sqlDatasource:list', 'workflow:sqlDatasource:add', 'workflow:sqlDatasource:edit',
    'workflow:dmn:list', 'workflow:dmn:add', 'workflow:dmn:remove',
    'workflow:bpmnEvent:list', 'workflow:bpmnEvent:add', 'workflow:bpmnEvent:edit',
    'workflow:bpmnEvent:audit', 'workflow:bpmnEvent:notification',
    'workflow:sla:list', 'workflow:sla:add', 'workflow:sla:edit',
    'workflow:sla:audit', 'workflow:sla:notification'
);

-- 发起人只发起、查看、取消和导出自己的实例，不获得历史物理删除权限。
INSERT INTO tmp_workflow_role_menu_seed (role_key, seed_key)
SELECT 'workflow_starter', seed_key
FROM tmp_workflow_menu_seed
WHERE seed_key IN (
    'office', 'workflow:process:startList', 'workflow:process:ownList',
    'workflow:process:copyList', 'workflow:process:start',
    'workflow:process:startExport', 'workflow:process:query',
    'workflow:process:cancel',
    'workflow:process:ownExport', 'workflow:process:copyExport',
    'workflow:attachment:upload', 'workflow:attachment:query',
    'workflow:attachment:remove', 'workflow:sla:notification'
);

-- 审批人可认领、办理和撤回本人真实任务，不获得管理员终止/状态权限。
INSERT INTO tmp_workflow_role_menu_seed (role_key, seed_key)
SELECT 'workflow_approver', seed_key
FROM tmp_workflow_menu_seed
WHERE seed_key IN (
    'office', 'workflow:process:todoList', 'workflow:process:claimList',
    'workflow:process:finishedList', 'workflow:process:copyList',
    'workflow:process:query', 'workflow:process:approval',
    'workflow:process:todoExport', 'workflow:process:claim',
    'workflow:process:claimExport', 'workflow:process:revoke',
    'workflow:process:finishedExport', 'workflow:process:copyExport',
    'workflow:attachment:upload', 'workflow:attachment:query',
    'workflow:attachment:remove', 'workflow:sla:notification'
);

-- 审计角色只有列表、详情和导出权限；对象授权继续限制可见实例范围。
INSERT INTO tmp_workflow_role_menu_seed (role_key, seed_key)
SELECT 'workflow_auditor', seed_key
FROM tmp_workflow_menu_seed
WHERE seed_key IN (
    'workflow', 'workflow:runtimeEvent:list', 'workflow:collaboration:list',
    'workflow:collaboration:audit',
    'workflow:bpmnEvent:list',
    'workflow:bpmnEvent:audit', 'workflow:bpmnEvent:notification',
    'workflow:sla:list', 'workflow:sla:audit', 'workflow:sla:notification',
    'office', 'workflow:process:ownList', 'workflow:process:todoList',
    'workflow:process:claimList', 'workflow:process:finishedList',
    'workflow:process:copyList', 'workflow:process:query',
    'workflow:process:ownExport', 'workflow:process:todoExport',
    'workflow:process:claimExport', 'workflow:process:finishedExport',
    'workflow:process:copyExport', 'workflow:attachment:query'
);

-- 仅重建五个受管角色的工作流菜单关联，保留它们已有的非工作流菜单。
DELETE role_menu
FROM sys_role_menu role_menu
JOIN tmp_workflow_role_actual role_info ON role_info.role_id = role_menu.role_id
JOIN tmp_workflow_menu_actual menu_info ON menu_info.menu_id = role_menu.menu_id;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_info.role_id, menu_info.menu_id
FROM tmp_workflow_role_menu_seed seed
JOIN tmp_workflow_role_actual role_info ON role_info.role_key = seed.role_key
JOIN tmp_workflow_menu_actual menu_info ON menu_info.seed_key = seed.seed_key;

DROP TEMPORARY TABLE IF EXISTS tmp_workflow_role_menu_seed;
DROP TEMPORARY TABLE IF EXISTS tmp_workflow_role_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_workflow_role_seed;
DROP TEMPORARY TABLE IF EXISTS tmp_workflow_menu_actual;
DROP TEMPORARY TABLE IF EXISTS tmp_workflow_menu_seed;
