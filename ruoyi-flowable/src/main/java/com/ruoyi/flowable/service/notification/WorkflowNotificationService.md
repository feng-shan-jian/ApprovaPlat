# WorkflowNotificationService

## 组件简介

`WorkflowNotificationService` 接收 Flowable 任务生命周期、流程终态、抄送和人工催办事实，构造上下文后交给 `WorkflowNotificationPlanner` 与 `WorkflowNotificationWriter`；Writer 统一持久化通知表。

## 使用方式

- 普通审批事件在调用方当前 Flowable 写事务中调用 `onTaskEvent`、`onStableTaskAction` 或 `onProcessResult`。
- 抄送服务在同一事务完成 `wf_copy` 写入后，按 `(copy_event_id,user_id)` 一次批量读取完整 active 事实，再交给 `onCopiesCreated` 规划；逻辑删除重放保持删除状态并继续主业务。
- 人工催办调用 `registerManualUrge`，原因在规划阶段与模板正文一次拼接。
- 受控状态迁移在删除任务前调用 `onTasksWithdrawn(processInstanceId, taskIds)`；任务集合必须非空、全部仍活动且属于同一实例，否则整笔写事务失败。

## 关键设计

- Planner 批量解析策略、接收人、用户状态、通道偏好和文案，返回不可变 `NotificationPlan`。
- Writer 在同一事务内直接写 `wf_notification_inbox`；EMAIL、SMS 才写入可靠 Outbox。
- `onTasksWithdrawn` 针对精确任务集合登记 `beforeCommit` 回调，将仍为 `PENDING`、`RETRYING` 或 `DELIVERING` 的 `MANUAL_URGE` 外部 Outbox 通过 revision CAS 转为 `CANCELLED`；Flowable、轮次和表单写入全部成功后执行取消，事务回滚时 outbox 保持原状态。
- SLA、BPMN 必达通知绕过普通偏好，直接调用 Writer 的 `writeRequiredInbox`。
- 抄送来源键固定为 `COPY:{copyEventId}:{userId}`，由业务事件和接收人确定。

## 最小接入示例

```java
notificationService.onProcessResult("PROCESS_COMPLETED", definitionId, instanceId);
```
