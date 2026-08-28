# WorkflowAttachmentService

## 作用

`WorkflowAttachmentService` 是工作流私有附件的唯一领域入口，负责临时上传、对象级读取授权、表单变量安全投影、流程节点绑定、删除和到期清理。附件正文位于私有目录，`wf_attachment` 保存正式元数据与首次提交归属。

## 生命周期

| 状态 | 允许操作 | 归属约束 |
| --- | --- | --- |
| `TEMP` | 上传者读取、下载、删除；保存草稿或任务完成时绑定业务对象 | 必须属于当前用户、未过期、字段一致，且草稿/流程/任务/节点均为空 |
| `BOUND` | 具备实例读取权限的用户读取、下载；后续任务可引用 | 必须有实例、节点和绑定时间；任务节点附件同时保存首次任务 ID |
| `EXPIRED` | 清理任务重试物理删除 | 业务读取和绑定入口返回状态冲突 |
| `DELETED` | 清理任务重试物理删除 | 业务读取和绑定入口返回资源已清理 |

`BOUND` 附件是流程审计证据，保持永久绑定状态。后续表单移除附件只覆盖本次 Flowable 变量，历史附件行持续保留原提交事实。

## 上传配额

每个用户默认最多保留 100 个 TEMP 附件，仍占用私有磁盘的 TEMP 累计上限为 512 MiB，单文件上限为 50 MiB。附件卷必须保留至少 1 GiB 可用空间。对应配置为 `flowable.attachment.max-temporary-count`、`max-temporary-bytes`、`max-size` 和 `min-free-bytes`。Spring multipart 同步限制为 50 MiB 单文件和 55 MiB 单请求，额外 5 MiB 用于 multipart 边界与字段开销。

上传事务幂等创建并以 `FOR UPDATE` 锁定当前 `owner_user_id` 的 `wf_attachment_quota_guard` 行，再读取该用户仍占用磁盘的临时附件数量与字节数。相同用户的并发上传严格串行核算，不同用户使用不同主键行，可以并行提交，不存在 `owner_user_id = 0` 全局 guard。上传入口使用 `READ_COMMITTED`，获得用户行锁后的聚合会读取前一同用户事务已经提交的最新版本。用户统计包含 `TEMP`、`DRAFT`，以及尚未完成物理删除的 `EXPIRED` / `DELETED`；附件转为 `BOUND` 后释放用户临时配额。

multipart 声明大小用于落盘前快速门禁。持有用户行锁期间还会确认“待写字节 + 磁盘低水位”可用，文件写入后再使用服务端实际字节复核用户配额和低水位；任一复核、数据库写入或事务提交失败都会补偿删除刚写入的私有文件。用户 guard 在用户生命周期内持续存在，以稳定行锁序列化配额更新。

## 清理领取与租约

清理调度使用 MySQL `FOR UPDATE SKIP LOCKED` 直接替换 `GET_LOCK`。每轮先在短 `REQUIRES_NEW` 事务中领取有界候选，将同一批次 UUID 写入 `cleanup_claim_token`，并将 `cleanup_lease_until` 设置为 `flowable.attachment.cleanup-lease-duration`，默认 5 分钟。到期 `TEMP` 在领取更新中同步迁移为 `EXPIRED`。有效租约由当前节点独占；租约过期后其他节点写入新 token 重领，原执行者后续完成或重试更新因 token 漂移返回 0 行，新所有者状态保持有效。

对象存储删除位于数据库事务之外。删除成功后使用新的短事务按 token 写入 `storage_deleted_time` 并清空租约；删除失败时使用新的短事务按 token 写入指数退避时间、稳定错误码并释放租约。清理 token 和租约只服务于执行中协调，完成或进入重试后立即清空；附件元数据及 `storage_deleted_time` 按流程审计保留策略继续保留。

## 表单绑定

开始表单草稿调用：

```java
attachmentService.reconcileDraftAttachments(
        actorUserId, draftId, attachmentIdsByField);
Map<String, Object> projected = attachmentService.prepareDraftSubmissionVariables(
        actorUserId, draftId, normalizedVariables, attachmentIdsByField);
attachmentService.bindDraftStartAttachments(
        actorUserId, draftId, processInstanceId, startNodeKey, attachmentIdsByField);
```

任务表单调用：

```java
Map<String, Object> projected = attachmentService.prepareTaskVariables(
        actorUserId, processInstanceId, normalizedVariables, attachmentIdsByField);
attachmentService.bindTaskAttachments(
        actorUserId, processInstanceId, taskId, nodeKey, attachmentIdsByField);
```

任务场景允许复用同一流程实例、同一表单字段的 `BOUND` 附件，并永久保留其首次任务和节点归属。跨实例 `BOUND` 返回 `403`，跨字段返回 `400`，其他用户的 `TEMP` 返回 `403`，不存在或已清理附件返回 `404`，过期或状态竞争返回 `409`。

草稿创建和保存继续使用 `reconcileDraftAttachments(...)` 完成附件对账。正式提交改用 `prepareDraftSubmissionVariables(...)`：先按草稿锁定当前 `DRAFT` 集合，再对集合外新增 UUID 执行一次补充批量锁定，因此支持草稿创建后上传、提交时首次引用的 `TEMP` 附件；每个附件只进行一次归属、状态、字段和物理文件完整性校验。同一批锁定实体完成 `TEMP -> DRAFT`、移除项 `DELETED` 处理及安全元数据投影，直接替换先对账再重新查询的重复路径。真实实例创建后由 `bindDraftStartAttachments(...)` 完成 `DRAFT -> BOUND`，并与实例和草稿状态更新共用外层事务。

## 安全投影

进入 Flowable 变量的每个附件只包含：`attachmentId`、`fieldName`、`originalName`、`contentType`、`fileSize`、`sha256`。`storageKey`、`ownerUserId` 和磁盘路径仅在服务端持久化边界使用，下载通过授权 API 返回文件流。

API 元数据可以返回 `processInstanceId`、`taskId`、`nodeKey`，用于前端核对附件确实属于当前实例和表单节点；临时附件的三个字段均为空。

## 事务约束

临时上传的身份校验、用户 guard 行锁、用户配额查询、私有文件写入及附件元数据插入由同一个 Spring `READ_COMMITTED` 事务管理。数据库事务管理元数据，文件系统写入由事务回滚补偿覆盖；代理外直接调用或元数据写入异常时立即删除本次文件。定时清理把数据库领取、对象删除、完成或重试拆成短事务、事务外 IO、短事务三个阶段。

prepare 阶段使用 `SELECT ... FOR UPDATE` 锁定稳定排序的附件行。草稿提交或任务完成在附件状态迁移前重新读取完整物理正文并核对数据库记录的大小和 SHA-256；正文摘要变化时返回绑定冲突。上述操作在 `WorkflowEngineOperations.writeAsCurrentUser(...)` 的同一事务中依次完成投影、意见、附件条件更新和 Flowable 状态变更。任一附件缺失、摘要不一致或绑定失败都会回滚前序附件、comment、变量、任务完成或草稿提交。

下载通过存储边界一次打开文件，在同一通道上完成路径、大小和 SHA-256 校验，复位后直接把该通道交给响应流，保持校验对象与响应对象一致。

单次表单最多引用 100 个附件，同一 UUID 在整份表单中保持唯一字段归属。
