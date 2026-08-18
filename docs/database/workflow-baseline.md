# 工作流数据库基线

## 适用范围

当前仓库定义开发期最终数据库结构，只支持从空库直接安装 27 张 `wf_*` 表。仓库不交付旧结构升级脚本、回填、双写或影子表；开发库结构变化时直接删除并按本基线重建。

## 空库初始化顺序

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

## 开发库重建

开发库数据不属于保留资产。结构变化时直接删除目标 schema，按上述九个初始化文件重建，然后执行 `mysqlcheck`。不得创建开发库 dump、旧 SQL 副本、回填脚本或兼容迁移。

## 表基线

| 模块 | 表数 |
| --- | ---: |
| 若依 | 20 |
| Quartz | 11 |
| Flowable Common/Process/History/DMN | 36 |
| 工作流 `wf_*` | 27 |
| 合计 | 94 |

27 张业务表：

- `wf_category`
- `wf_form`
- `wf_controlled_loop_execution`
- `wf_bpmn_extension`
- `wf_bpmn_extension_version`
- `wf_business_calendar`
- `wf_business_calendar_day`
- `wf_task_sla_execution`
- `wf_task_sla_audit`
- `wf_connector_endpoint`
- `wf_sql_datasource`
- `wf_integration_credential`
- `wf_runtime_event_request`
- `wf_collaboration_channel`
- `wf_collaboration_message`
- `wf_collaboration_outbox`
- `wf_collaboration_message_audit`
- `wf_copy`
- `wf_process_draft`
- `wf_attachment_quota_guard`
- `wf_attachment`
- `wf_bpmn_event_code`
- `wf_bpmn_event_audit`
- `wf_notification_policy`
- `wf_notification_preference`
- `wf_notification_outbox`
- `wf_notification_inbox`

本版本删除：

- `wf_model_save_idempotency`
- `wf_designer_preference`
- `wf_participant_resolution_audit`
- `wf_process_draft_audit`
- `wf_connector_invocation`
- `wf_notification_delivery_audit`
- `wf_notification_urge_audit`

## 关键结构约束

- `ACT_RE_MODEL(KEY_, VERSION_, TENANT_ID_)` 保留唯一约束，模型并发由内容摘要、Flowable revision 和自然版本唯一键保护。
- `wf_integration_credential` 只保存固定限额、Token 摘要、scope、revision、轮换、吊销和最近使用时间，不保存分钟窗口计数。
- `wf_attachment_quota_guard.owner_user_id` 只允许正数用户 ID；同用户配额计算使用行锁，不存在用户 0 全局锁行。
- `wf_attachment.cleanup_claim_token/cleanup_lease_until` 必须同时为空或同时有效，领取中的附件必须尚未物理删除且处于 `EXPIRED/DELETED`。
- `wf_notification_inbox` 使用 `notification_key + recipient_user_id` 唯一约束，并保存 `source_type/source_id`；`outbox_id` 仅为创建时软关联，不再建立 outbox 外键。
- `wf_task_sla_audit.sla_execution_id` 使用 `ON DELETE CASCADE`，SLA execution 满足保留条件后由数据库同步删除审计，禁止孤立增长。
- 生命周期候选索引统一包含终态、终态时间和稳定主键；协作审计按 `message_id + direction` 随父记录同事务删除。
- 业务自然键、扩展版本、入站请求、outbox 和消息顺序继续由 MySQL 唯一键兜底。
- `wf_*` 与 `ACT_*` 的业务写入使用同一 Spring 事务；应用账号不得依赖自动建表修复缺失结构。
