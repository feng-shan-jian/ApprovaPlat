# 工作流数据库基线

## 适用范围

当前仓库定义 ApprovaPlat 的首个正式数据库版本。不存在需要兼容的已部署旧数据库，因此首个版本只支持空 schema 全新安装，不支持从开发期 `.1`～`.14` 迁移链升级。

## 初始化顺序

先执行若依和 Quartz 基线：

1. `sql/ry_20260417.sql`
2. `sql/quartz.sql`

然后严格执行以下七个工作流基线文件：

1. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql`
2. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql`
3. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql`
4. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql`
5. `sql/flowable/business/8.0.0__workflow_model_version_guard.sql`
6. `sql/flowable/business/8.0.0__workflow_business.sql`
7. `sql/flowable/menu/8.0.0__workflow_menu.sql`

所有 MySQL 客户端执行必须显式使用 `--default-character-set=utf8mb4`。Flowable 官方基础脚本包含破坏性初始化语句，只能在已经验证为空的目标 schema 中执行。

## 表与菜单基线

| 模块 | 表数 |
| --- | ---: |
| 若依 | 20 |
| Quartz | 11 |
| Flowable Common/Process/History/DMN | 36 |
| ApprovaPlat `wf_*` | 34 |
| 合计 | 101 |

34 张业务表：

- `wf_category`
- `wf_form`
- `wf_participant_resolution_audit`
- `wf_controlled_loop_execution`
- `wf_bpmn_extension`
- `wf_bpmn_extension_version`
- `wf_business_calendar`
- `wf_business_calendar_day`
- `wf_task_sla_execution`
- `wf_task_sla_audit`
- `wf_connector_endpoint`
- `wf_connector_invocation`
- `wf_sql_datasource`
- `wf_integration_credential`
- `wf_runtime_event_request`
- `wf_collaboration_channel`
- `wf_collaboration_message`
- `wf_collaboration_outbox`
- `wf_collaboration_message_audit`
- `wf_copy`
- `wf_model_save_idempotency`
- `wf_designer_preference`
- `wf_process_draft`
- `wf_process_draft_audit`
- `wf_attachment_quota_guard`
- `wf_attachment`
- `wf_bpmn_event_code`
- `wf_bpmn_event_audit`
- `wf_notification_policy`
- `wf_notification_preference`
- `wf_notification_outbox`
- `wf_notification_inbox`
- `wf_notification_delivery_audit`
- `wf_notification_urge_audit`

表单、条件、受控循环、参与者、扩展、DMN、调用活动和 SLA 共 8 类不可变部署快照，不再创建自定义快照表。每个可执行流程部署拥有一个 Flowable 业务制品子部署，固定保存 `manifest-v1.json` 以及 8 个分类 JSON 资源；子部署通过 `parentDeploymentId` 关联父部署，并与父部署共享发布事务和生命周期。

普通审批、SLA 和 BPMN 事件通知统一写入 `wf_notification_outbox`、`wf_notification_inbox` 与 `wf_notification_delivery_audit`。`source_type/source_id` 关联各自业务事实，不再维护 SLA 或 BPMN 事件专用通知表。

菜单基线为 3 个目录、21 个页面、75 个按钮，共 99 条记录，并维护五个职责分离角色。菜单脚本不会自动给用户分配角色。

## 结构约束

- `ACT_RE_MODEL(KEY_, VERSION_, TENANT_ID_)` 必须具有唯一约束，防止模型版本并发冲突。
- 业务自然键、幂等请求、连接器调用和扩展版本必须由数据库唯一键提供最终一致性保护。
- JSON 字段、状态字段、所有者、版本号和生命周期字段必须具有必要的 CHECK、索引或外键约束。
- `wf_*` 与 `ACT_*` 的业务写入必须使用同一 Spring 事务；数据库账号不能用应用自动建表弥补缺失结构。

## 只读验收

初始化完成后依次执行：

1. `sql/flowable/verify/8.0.0__verify.sql`
2. `sql/flowable/verify/8.0.0__verify_workflow_business.sql`
3. `sql/flowable/verify/8.0.0__verify_workflow_menu.sql`

三组脚本共定义 57 项只读检查，所有结果都必须为 `PASS`。表数检查固定核对总表数 101、分项表数 `20/11/36/34`；部署制品检查必须证明十张退役表不存在、每个制品子部署只关联一个父部署、九个固定资源完整且 JSON 有效，并且不产生可执行流程定义。通知检查必须证明审批、SLA 和 BPMN 事件共享统一通知模型且来源关联有效；还必须核对菜单 99 条以及应用账号只拥有目标 schema 的最小 DML 权限。

静态契约测试和发布门禁自测不能代替真实 MySQL 空库安装。正式发布前必须保存真实执行日志、表清单、约束结果、`mysqlcheck`、备份恢复和三组验收输出。

## 后续正式迁移

首个基线发布后：

- 已发布基线不可修改。
- 每次数据库变化新增唯一、顺序明确的版本化迁移。
- 迁移必须只处理两个正式版本之间的差异，不兼容本地开发历史。
- 同步更新只读验收、契约测试、发布顺序和升级/回滚/重新升级证据。
- 应用回滚原则上不执行反向 DDL，新结构必须在批准的兼容窗口内支持上一应用版本。
