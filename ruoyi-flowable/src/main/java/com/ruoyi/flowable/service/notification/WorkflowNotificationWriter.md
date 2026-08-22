# WorkflowNotificationWriter

## 组件简介

`WorkflowNotificationWriter` 是通知表 SQL 的唯一写入入口。普通计划中的 `INBOX` 在调用方业务事务内直写 `wf_notification_inbox`，`EMAIL` 和 `SMS` 写入可靠 `wf_notification_outbox`。

## 使用方式

- 普通审批通知在已有 Spring/Flowable 写事务中调用 `write(plan)`。
- SLA、BPMN 必达站内通知调用 `writeRequiredInbox(notification)`，只校验用户有效状态，不读取 inbox 偏好。
- 两个入口都要求当前存在非只读事务，否则拒绝写入。

## 公开方法

- `write(NotificationPlan)`：批量参数化写入 inbox 和外部 Outbox，返回首次新增通道记录数及实际可登记接收人。
- `writeRequiredInbox(WorkflowInboxNotification)`：幂等写入必达站内信，并通过数据库生成键返回 `notification_id`。

## 关键设计

- inbox 使用 `notification_key + recipient_user_id` 唯一约束，Outbox 使用 `idempotency_key` 唯一约束。
- 幂等冲突只捕获数据库 `DuplicateKeyException`，不读取并比较标题、正文或路由等整行事实；其他数据库异常继续向上抛出并回滚。
- Writer 通过普通 INSERT 的 generated key 判断首次新增；只有首次新增才累计通道数量并记录一次 `ENQUEUE`。
- 普通 inbox 与外部 Outbox 共享调用方事务，任一数据库错误都会整体回滚。
- Writer 不分派外部通道；提交后的 EMAIL、SMS 由 worker 和投递协调器处理。

## 最小接入示例

```java
WorkflowNotificationWriter.WriteResult result = writer.write(plan);
```
