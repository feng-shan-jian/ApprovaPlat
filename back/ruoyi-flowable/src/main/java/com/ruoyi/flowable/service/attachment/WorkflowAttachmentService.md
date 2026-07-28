# WorkflowAttachmentService

## 作用

`WorkflowAttachmentService` 是工作流私有附件的唯一领域入口，负责临时上传、对象级读取授权、表单变量安全投影、流程节点绑定、删除和到期清理。附件正文位于私有目录，`wf_attachment` 保存正式元数据与首次提交归属。

## 生命周期

| 状态 | 允许操作 | 归属约束 |
| --- | --- | --- |
| `TEMP` | 上传者读取、下载、删除；发起或任务完成时绑定 | 必须属于当前用户、未过期、字段一致，且流程/任务/节点均为空 |
| `BOUND` | 具备实例读取权限的用户读取、下载；后续任务可引用 | 必须有实例、节点和绑定时间；任务节点附件同时保存首次任务 ID |
| `EXPIRED` | 清理任务重试物理删除 | 不允许业务读取或再次绑定 |
| `DELETED` | 清理任务重试物理删除 | 不允许业务读取或再次绑定 |

`BOUND` 附件是流程审计证据，不提供解绑和业务删除。后续表单移除附件只覆盖本次 Flowable 变量，不修改历史附件行。

## 上传配额

每个用户默认最多保留 100 个 TEMP 附件，仍占用私有磁盘的 TEMP 累计上限为 512 MiB，单文件上限为 50 MiB。全部尚未完成物理删除的附件共享 50 GiB 全局容量，附件卷必须保留至少 1 GiB 可用空间。对应配置为 `flowable.attachment.max-temporary-count`、`max-temporary-bytes`、`max-size`、`max-total-bytes` 和 `min-free-bytes`。Spring multipart 同步限制为 50 MiB 单文件和 55 MiB 单请求，额外 5 MiB 用于 multipart 边界与字段开销。

数据库迁移会幂等预置 `wf_attachment_quota_guard.owner_user_id = 0` 固定全局行。上传事务第一条配额语句直接以 `FOR UPDATE` 锁定该行，再在全局锁内幂等创建并锁定当前用户行，然后依次读取用户 TEMP 占用和全局占用；运行时不会通过 `INSERT IGNORE` 首次创建全局行，避免并发事务发生共享锁到排他锁的升级死锁。上传入口显式使用 `READ_COMMITTED`：身份解析虽然会在等待全局锁前查询用户、角色和部门，但获得锁后的每条配额聚合都会读取最新已提交版本，因此后续事务能够看到前一上传事务写入的 `wf_attachment`。全局统计包含 `TEMP`、`BOUND`、`EXPIRED`、`DELETED` 中 `storage_deleted_time IS NULL` 的全部字节；用户统计包含全部 `TEMP`，以及尚未完成物理删除的 `EXPIRED` / `DELETED`。固定锁顺序让同用户和跨用户并发都不会超卖容量，附件转为 `BOUND` 后只释放用户 TEMP 配额，不释放全局容量。

multipart 声明大小只用于落盘前快速拒绝。持有全局锁期间还会确认“待写字节 + 磁盘低水位”可用，文件写入后再使用服务端实际字节复核用户配额、全局容量和低水位；任一复核、数据库写入或事务提交失败都会补偿删除刚写入的私有文件。全局 guard 和用户 guard 在正常生命周期内保留，不以删除行的方式释放配额，避免删除与等待上传事务产生锁竞态。

## 表单绑定

开始表单调用：

```java
Map<String, Object> projected = attachmentService.prepareStartVariables(
        actorUserId, normalizedVariables, attachmentIdsByField);
attachmentService.bindStartAttachments(
        actorUserId, processInstanceId, startNodeKey, attachmentIdsByField);
```

任务表单调用：

```java
Map<String, Object> projected = attachmentService.prepareTaskVariables(
        actorUserId, processInstanceId, normalizedVariables, attachmentIdsByField);
attachmentService.bindTaskAttachments(
        actorUserId, processInstanceId, taskId, nodeKey, attachmentIdsByField);
```

任务场景允许复用同一流程实例、同一表单字段的 `BOUND` 附件，且不会覆盖其首次任务和节点归属。跨实例 `BOUND` 返回 `403`，跨字段返回 `400`，其他用户的 `TEMP` 返回 `403`，不存在或已清理附件返回 `404`，过期或状态竞争返回 `409`。

## 安全投影

进入 Flowable 变量的每个附件只包含：`attachmentId`、`fieldName`、`originalName`、`contentType`、`fileSize`、`sha256`。`storageKey`、`ownerUserId`、磁盘路径和静态下载 URL 永不进入变量或 API 视图。

API 元数据可以返回 `processInstanceId`、`taskId`、`nodeKey`，用于前端核对附件确实属于当前实例和表单节点；临时附件的三个字段均为空。

## 事务约束

临时上传的身份校验、全局和用户 guard 行锁、两级配额查询、私有文件写入及附件元数据插入由同一个 Spring `READ_COMMITTED` 事务管理。文件系统不参与数据库事务，因此服务会同时注册事务回滚补偿，并在代理外直接调用或元数据写入异常时立即删除本次文件。

prepare 阶段使用 `SELECT ... FOR UPDATE` 锁定稳定排序的附件行。发起或任务完成在附件状态迁移前重新读取完整物理正文并核对数据库记录的大小和 SHA-256；同长度正文替换也会拒绝绑定。上述操作必须在 `WorkflowEngineOperations.writeAsCurrentUser(...)` 的同一事务中依次完成投影、意见、附件条件更新和 Flowable 状态变更。任一附件缺失、摘要不一致或绑定失败都会回滚前序附件、comment、变量、任务完成或流程发起。

下载通过存储边界一次打开文件，在同一通道上完成路径、大小和 SHA-256 校验，复位后直接把该通道交给响应流，避免校验后由 Controller 再次按路径打开文件。

单次表单最多引用 100 个附件，同一 UUID 不能重复或跨字段引用。
