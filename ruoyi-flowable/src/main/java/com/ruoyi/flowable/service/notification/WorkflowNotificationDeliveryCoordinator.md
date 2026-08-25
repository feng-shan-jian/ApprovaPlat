# WorkflowNotificationDeliveryCoordinator

## 组件简介

`WorkflowNotificationDeliveryCoordinator` 编排一次通知领取、事务外渠道副作用和投递结果提交。该类自身不声明数据库事务。

## 使用方式

worker 先调用 `claimNext(workerId)` 获取已经提交的不可变领取快照，再调用 `deliverClaimed(row, workerId)`。协调器根据 `channel` 选择 `EMAIL` 或 `SMS` 外部通道 Strategy；站内信不进入 worker。

## 参数与返回

- `batchSize()` 返回 worker 单轮有界批次大小。
- `claimNext(String workerId)` 返回 `WorkflowNotificationOutboxRecord`，无到期记录时返回 `null`。
- `deliverClaimed(WorkflowNotificationOutboxRecord row, String workerId)` 无返回值；租约漂移、未知通道、事务边界错误或通道未分类异常时向 worker 抛出。

## 关键设计

- `claimNext` 和 `completeDelivery` 的短事务由 `WorkflowNotificationOutboxService` 独占。
- 渠道调用前显式确认当前线程没有活动数据库事务。
- EMAIL/SMS 通道仍自行把其预期失败转换为稳定、脱敏结果；协调器不再兜底改写通道未处理的运行时异常。
- 未知运行时异常由 worker 记录完整原因，记录保持 `DELIVERING`，并在租约过期后由领取边界恢复，避免把程序缺陷伪装成普通重试结果。
- 两个外部通道必须完整且唯一注册，INBOX 由业务事务内 Writer 直接写入。

## 最小接入示例

```java
WorkflowNotificationOutboxRecord row = coordinator.claimNext(workerId);
if (row != null) {
    coordinator.deliverClaimed(row, workerId);
}
```
