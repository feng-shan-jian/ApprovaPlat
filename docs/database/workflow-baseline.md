# 工作流数据库基线

## 适用范围

本文列出的空库基线安装 29 张 `wf_*` 表和平台单例 `sys_mail_config`；已经完成 8.0.0 基线的数据库按本文“现有基线升级”执行前向迁移；开发库按完整基线重建。以上三条路径直接替换开发期回填、双写和影子表方案。

## 空库初始化顺序

先执行若依和 Quartz 基线：

1. `sql/ry_20260417.sql`
2. `sql/quartz.sql`

然后严格执行以下九个工作流基线文件：

1. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql`
2. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql`
3. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql`
4. `sql/flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql`
5. `sql/flowable/business/8.0.0__workflow_model_version_guard.sql`
6. `sql/flowable/business/8.0.0__workflow_business.sql`
7. `sql/flowable/business/8.0.1__workflow_mail_config.sql`
8. `sql/flowable/business/8.0.4__workflow_attachment_upload.sql`
9. `sql/flowable/menu/8.0.0__workflow_menu.sql`

所有 MySQL 客户端执行显式使用 `--default-character-set=utf8mb4`。Flowable 官方基础脚本包含破坏性初始化语句，执行目标固定为已经验证为空的 schema。

## 本地 Docker 开发环境

在仓库根目录执行 `docker compose up -d` 会启动仅绑定 `127.0.0.1` 的 MySQL 和 Redis。MySQL 首次创建 `approvaplat-mysql-data` Volume 时，通过官方 `/docker-entrypoint-initdb.d/` 机制调用 `docker/mysql/init.sh`；该脚本按上述顺序直接读取并执行 `sql/` 中的十一个正式基线文件，不复制、不合并 SQL。

Docker 自动初始化只改变本地空库的执行入口，不改变 SQL 基线规则。

Flowable 继续保持 `database-schema-update: "false"`，应用启动不得创建或修复数据库结构。

## 现有基线升级

启用附件上传持久恢复前，额外执行 `sql/flowable/business/8.0.4__workflow_attachment_upload.sql`，核验空的 `wf_attachment_upload`、`storage_key` 的 160 字符 ASCII 二进制主键，以及 `(next_retry_time, storage_key)` 清理索引。无需改写现有附件元数据或文件。已部署的其他功能迁移继续按其各自契约执行，本项只新增上传清理表。

回退应用版本时保留 `wf_attachment_upload`，不要删除待回收记录。停用旧上传入口后，先由支持该账本的版本处理积压并确认没有活动上传、登记行数为零，再决定是否移除空表。历史无元数据、无上传登记的孤儿不在自动迁移回收范围内。

## 开发库重建

开发库以可重建数据为准。结构变化时直接删除目标 schema，按上述十一个初始化文件重建，然后执行 `mysqlcheck`；仓库只保留正式基线 SQL。

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
