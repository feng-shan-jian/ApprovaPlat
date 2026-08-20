# WorkflowNotificationDeliveryCoordinator

## 组件简介

`WorkflowNotificationDeliveryCoordinator` 编排一次通知领取、事务外渠道副作用和投递结果提交。该类自身不声明数据库事务。

## 使用方式

worker 先调用 `claimNext(workerId)` 获取已经提交的不可变领取快照，再调用 `deliverClaimed(row, workerId)`。协调器根据 `channel` 选择 `EMAIL` 或 `SMS` 外部通道 Strategy；站内信不进入 worker。

## 参数与返回

- `batchSize()` 返回 worker 单轮有界批次大小。
- `claimNext(String workerId)` 返回 `WorkflowNotificationOutboxRecord`，无到期记录时返回 `null`。
- `deliverClaimed(WorkflowNotificationOutboxRecord row, String workerId)` 无返回值；租约漂移、未知通道或事务边界错误时抛出稳定异常。

## 关键设计

- `claimNext` 和 `completeDelivery` 的短事务由 `WorkflowNotificationOutboxService` 独占。
- 渠道调用前显式确认当前线程没有活动数据库事务。
- 渠道未处理异常转换为脱敏 `DELIVERY_INTERNAL_ERROR`，随后仍提交一次失败结果，避免长期停留 `DELIVERING`。
- 两个外部通道必须完整且唯一注册，INBOX 由业务事务内 Writer 直接写入。

## 最小接入示例

```java
WorkflowNotificationOutboxRecord row = coordinator.claimNext(workerId);
if (row != null) {
    coordinator.deliverClaimed(row, workerId);
}
```
