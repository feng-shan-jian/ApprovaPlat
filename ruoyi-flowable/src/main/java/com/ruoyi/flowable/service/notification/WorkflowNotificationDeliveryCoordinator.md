# WorkflowNotificationDeliveryCoordinator

## 组件简介

`WorkflowNotificationDeliveryCoordinator` 编排一次通知领取、事务外渠道副作用和投递结果提交。数据库短事务全部由 `WorkflowNotificationOutboxService` 承担，协调器始终运行在事务外投递边界。

## 使用方式

worker 先调用 `claimNext(workerId)` 获取已经提交的不可变领取快照，再调用 `deliverClaimed(row, workerId)`。协调器根据 `channel` 选择 `EMAIL` 或 `SMS` 外部通道 Strategy；站内信由业务事务内 Writer 直接写入。

## 参数与返回

- `batchSize()` 返回 worker 单轮有界批次大小。
- `claimNext(String workerId)` 返回 `WorkflowNotificationOutboxRecord`；`null` 表示当前到期记录集合为空。
- `deliverClaimed(WorkflowNotificationOutboxRecord row, String workerId)` 执行一次已领取投递；租约漂移、未知通道、事务边界错误或通道未分类异常时向 worker 抛出。

## 关键设计

- `claimNext` 和 `completeDelivery` 的短事务由 `WorkflowNotificationOutboxService` 独占。
- 渠道调用前显式确认当前线程的数据库事务状态为 inactive。
- EMAIL/SMS 通道自行把预期失败转换为稳定、脱敏结果；协调器把通道未分类的运行时异常原样传播给 worker。
- worker 记录未知运行时异常的完整原因，记录保持 `DELIVERING`，租约过期后由领取边界恢复为可重新领取状态，从而区分程序缺陷与普通业务重试。
- 两个外部通道必须完整且唯一注册，INBOX 由业务事务内 Writer 直接写入。

## 最小接入示例

```java
WorkflowNotificationOutboxRecord row = coordinator.claimNext(workerId);
if (row != null) {
    coordinator.deliverClaimed(row, workerId);
}
```
