# 工作流数据库基线

## 适用范围

当前仓库定义 ApprovaPlat 的首个正式数据库版本。不存在需要兼容的已部署旧数据库，因此首个版本只支持空 schema 全新安装，不支持从开发期 `.1`～`.14` 迁移链升级。

## 初始化顺序

先执行若依和 Quartz 基线：

1. `back/sql/ry_20260417.sql`
2. `back/sql/quartz.sql`

然后严格执行以下七个工作流基线文件：

1. `back/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql`
2. `back/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql`
3. `back/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql`
4. `back/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql`
5. `back/sql/flowable/business/8.0.0__workflow_model_version_guard.sql`
6. `back/sql/flowable/business/8.0.0__workflow_business.sql`
7. `back/sql/flowable/menu/8.0.0__workflow_menu.sql`

所有 MySQL 客户端执行必须显式使用 `--default-character-set=utf8mb4`。Flowable 官方基础脚本包含破坏性初始化语句，只能在已经验证为空的目标 schema 中执行。

## 表与菜单基线

| 模块 | 表数 |
| --- | ---: |
| 若依 | 20 |
| Quartz | 11 |
| Flowable Common/Process/History/DMN | 36 |
| ApprovaPlat `wf_*` | 22 |
| 合计 | 89 |

22 张业务表：

- `wf_category`
- `wf_form`
- `wf_deploy_form`
- `wf_deploy_controlled_loop`
- `wf_controlled_loop_execution`
- `wf_copy`
- `wf_model_save_idempotency`
- `wf_attachment_quota_guard`
- `wf_attachment`
- `wf_designer_preference`
- `wf_bpmn_extension`
- `wf_bpmn_extension_version`
- `wf_deploy_extension_snapshot`
- `wf_deploy_dmn_snapshot`
- `wf_connector_endpoint`
- `wf_connector_invocation`
- `wf_sql_datasource`
- `wf_integration_credential`
- `wf_runtime_event_request`
- `wf_bpmn_event_code`
- `wf_bpmn_event_audit`
- `wf_bpmn_event_notification`

菜单基线为 2 个目录、18 个页面、57 个按钮，共 77 条记录，并维护五个职责分离角色。菜单脚本不会自动给用户分配角色。

## 结构约束

- `ACT_RE_MODEL(KEY_, VERSION_, TENANT_ID_)` 必须具有唯一约束，防止模型版本并发冲突。
- 业务自然键、幂等请求、连接器调用和扩展版本必须由数据库唯一键提供最终一致性保护。
- JSON 字段、状态字段、所有者、版本号和生命周期字段必须具有必要的 CHECK、索引或外键约束。
- `wf_*` 与 `ACT_*` 的业务写入必须使用同一 Spring 事务；数据库账号不能用应用自动建表弥补缺失结构。

## 只读验收

初始化完成后依次执行：

1. `back/sql/flowable/verify/8.0.0__verify.sql`
2. `back/sql/flowable/verify/8.0.0__verify_workflow_business.sql`
3. `back/sql/flowable/verify/8.0.0__verify_workflow_menu.sql`

三组脚本共定义 40 项只读检查，所有结果都必须为 `PASS`。表数检查固定核对总表数 89、分项表数 `20/11/36/22`，受控循环以及 BPMN 错误和升级正式表均归入 ApprovaPlat `wf_*`；还必须核对菜单 77 条以及应用账号只拥有目标 schema 的最小 DML 权限。

静态契约测试和发布门禁自测不能代替真实 MySQL 空库安装。正式发布前必须保存真实执行日志、表清单、约束结果、`mysqlcheck`、备份恢复和三组验收输出。

## 后续正式迁移

首个基线发布后：

- 已发布基线不可修改。
- 每次数据库变化新增唯一、顺序明确的版本化迁移。
- 迁移必须只处理两个正式版本之间的差异，不兼容本地开发历史。
- 同步更新只读验收、契约测试、发布顺序和升级/回滚/重新升级证据。
- 应用回滚原则上不执行反向 DDL，新结构必须在批准的兼容窗口内支持上一应用版本。
