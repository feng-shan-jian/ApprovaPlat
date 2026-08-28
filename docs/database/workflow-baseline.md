# 工作流数据库基线

## 适用范围

当前仓库定义首个正式数据库基线。空库直接安装 28 张 `wf_*` 表和平台单例 `sys_mail_config`；已经完成 8.0.0 基线的数据库按本文“现有基线升级”执行 8.0.1 前向迁移；开发库按完整基线重建。以上三条路径直接替换开发期回填、双写和影子表方案。

## 空库初始化顺序

先执行若依和 Quartz 基线：

1. `sql/ry_20260417.sql`
2. `sql/quartz.sql`

然后严格执行以下八个工作流基线文件：

1. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql`
2. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql`
3. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql`
4. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql`
5. `sql/flowable/business/8.0.0__workflow_model_version_guard.sql`
6. `sql/flowable/business/8.0.0__workflow_business.sql`
7. `sql/flowable/business/8.0.1__workflow_mail_config.sql`
8. `sql/flowable/menu/8.0.0__workflow_menu.sql`

所有 MySQL 客户端执行显式使用 `--default-character-set=utf8mb4`。Flowable 官方基础脚本包含破坏性初始化语句，执行目标固定为已经验证为空的 schema。

## 本地 Docker 开发环境

在仓库根目录执行 `docker compose up -d` 会启动仅绑定 `127.0.0.1` 的 MySQL 和 Redis。MySQL 首次创建 `approvaplat-mysql-data` Volume 时，通过官方 `/docker-entrypoint-initdb.d/` 机制调用 `docker/mysql/init.sh`；该脚本按上述顺序直接读取并执行 `sql/` 中的十个正式基线文件，不复制、不合并 SQL。

Docker 自动初始化只改变本地空库的执行入口，不改变 SQL 基线规则。

Flowable 继续保持 `database-schema-update: "false"`，应用启动不得创建或修复数据库结构。

## 现有基线升级

已经完成 8.0.0 九文件基线的数据库按以下顺序升级；Flowable create 脚本仅用于空 schema 初始化：

1. 完成整库备份并核对当前 schema 为 95 张正式基线表。
2. 执行 `sql/flowable/business/8.0.1__workflow_mail_config.sql`；该脚本创建空的 `sys_mail_config`，SMTP 账号和授权码随后通过正式业务入口保存。
3. 重新执行幂等的 `sql/flowable/menu/8.0.0__workflow_menu.sql`，写入 `workflow:notification:mailManage` 并按正式角色种子重建工作流菜单授权。
4. 核验 `sys_mail_config` 行数为零、`config_id=1` 单例约束存在，且邮件服务管理权限只授予 `workflow_admin`。
5. 执行 `mysqlcheck`，再由具备权限的管理员通过真实页面首次保存 SMTP 配置。

升级完成后，管理员通过受控业务入口重新配置 SMTP 账号和授权码；SQL 和部署示例只保存公开结构与字段说明。

## 开发库重建

开发库以可重建数据为准。结构变化时直接删除目标 schema，按上述十个初始化文件重建，然后执行 `mysqlcheck`；仓库只保留正式基线 SQL。

## 表基线

| 模块 | 表数 |
| --- | ---: |
| 若依 | 20 |
| Quartz | 11 |
| Flowable Common/Process/History/DMN | 36 |
| 工作流 `wf_*` | 28 |
| 平台邮件配置 `sys_mail_config` | 1 |
| 合计 | 96 |

28 张业务表：

- `wf_category`
- `wf_form`
- `wf_controlled_loop_execution`
- `wf_multi_instance_round`
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

平台邮件配置表：

- `sys_mail_config`

## 关键结构约束

- `ACT_RE_MODEL(KEY_, VERSION_, TENANT_ID_)` 保留唯一约束，模型并发由 Flowable revision 和自然版本唯一键保护。
- `wf_integration_credential` 保存固定限额、Token 摘要、scope、revision、轮换、吊销和最近使用时间；分钟窗口计数由运行时限流器维护。
- `wf_attachment_quota_guard.owner_user_id` 使用正数用户 ID；同用户配额计算使用对应用户行锁。
- `wf_multi_instance_round` 保存有序成员快照、轮次状态和审计关联；Flowable 变量是实时执行源。同实例同节点最多一条 `ACTIVE/RETURNED` 轮次，`ACT_*` 保持 Flowable 官方结构。整组退回以单行 CAS 执行 `ACTIVE -> RETURNED` 并保存来源任务、操作人、唯一申请人任务和数据库时间；重提通过 `applicant_task_id` 精确定位后以 CAS 执行 `RETURNED -> REOPENED`，新 root execution 另建下一轮 `ACTIVE`，成员快照来自服务端实时事实和冻结轮次。
- `wf_attachment.cleanup_claim_token/cleanup_lease_until` 必须同时为空或同时有效，领取中的附件必须尚未物理删除且处于 `EXPIRED/DELETED`。
- `wf_notification_inbox` 使用 `notification_key + recipient_user_id` 唯一约束，并保存 `source_type/source_id`；站内信由业务事务内 Writer 直接写入。
- `wf_notification_outbox` 只允许 `EMAIL`、`SMS`，仅承载外部投递副作用；普通策略仍可在 `wf_notification_policy.channels` 选择 `INBOX`。
- `sys_mail_config` 只允许 `config_id=1`。安装和迁移只创建空表且不得预置配置行，首次业务保存写入 revision 1，后续以 revision 条件更新。
- SMTP 授权码只以 AES-256-GCM 密文保存；加密子密钥从 RuoYi Token 密钥按固定用途派生，每次加密使用独立 12 字节随机 IV。数据库和查询接口只返回公开配置、授权码配置状态和 revision，不得返回授权码明文或 IV。
- `wf_task_sla_execution.status=COMPLETED` 表示 SLA 时钟已经关闭，不等同于审批业务通过；受控整组退回和重提必须在对应 `wf_task_sla_audit` 保存固定撤销详情，以区别正常任务完成。
- `wf_task_sla_audit.sla_execution_id` 使用 `ON DELETE CASCADE`，SLA execution 满足保留条件后由数据库同步删除审计，禁止孤立增长。
- 生命周期候选索引统一包含终态、终态时间和稳定主键；协作审计按 `message_id + direction` 随父记录同事务删除。
- 业务自然键、扩展版本、入站请求、outbox 和消息顺序继续由 MySQL 唯一键兜底。
- `wf_*` 与 `ACT_*` 的业务写入使用同一 Spring 事务；应用账号固定使用已经由正式 SQL 建立并核验的结构。
