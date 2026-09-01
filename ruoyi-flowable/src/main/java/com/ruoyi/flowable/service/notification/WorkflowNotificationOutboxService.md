# WorkflowNotificationOutboxService

## 组件简介

`WorkflowNotificationOutboxService` 是外部通知 outbox 状态迁移的唯一所有者，负责领取、成功完成、重试、死信、补偿和业务终态取消。站内信由 `WorkflowNotificationWriter` 在业务事务中直接持久化。

## 使用方式

- `claimNext(workerId)` 在 `REQUIRES_NEW` 短事务中领取到期记录并冻结邮件或短信目标地址。
- `completeDelivery(row, workerId, result)` 在 `REQUIRES_NEW` 短事务中按租约和 revision 条件提交结果。
- `compensate(outboxId)` 由授权管理入口重新开启死信投递周期。
- `schedulePendingUrgeCancellation` 与 `cancelPendingUrges` 用于流程终态取消尚未完成的催办。

## 参数与返回

领取返回不可变 `WorkflowNotificationOutboxRecord`；完成方法无返回值。状态、租约持有者和 revision 全部一致时提交结果，其余组合返回冲突并保留当前租约结果。

## 关键设计

- 允许 `PENDING/RETRYING -> DELIVERING`，以及 `DELIVERING -> PROCESSED/RETRYING/DEAD_LETTER`。
- 所有完成更新使用 `outbox_id + DELIVERING + lease_owner + revision` 条件。
- 重试使用有界次数和指数退避；永久失败或次数耗尽进入死信。
- 运维查询仅在 `status = DEAD_LETTER && delivery_cycle < 65535` 时返回布尔型 `canCompensate=true`；该字段只是读取快照。
- 最终补偿仍以相同状态和周期条件执行原子更新，成功后迁移为 `RETRYING`、递增 `delivery_cycle` 与 `revision`；条件不满足时返回 409 且不记录 `COMPENSATE`。
- 无事务投递协调器持有通知 Channel 并执行外部网络调用，本服务专注于 outbox 状态迁移。

## 最小接入示例

```java
WorkflowNotificationOutboxRecord row = outboxService.claimNext(workerId);
outboxService.completeDelivery(row, workerId,
        WorkflowNotificationDeliveryResult.delivered());
```
