# WorkflowNotificationPlanner

## 组件简介

`WorkflowNotificationPlanner` 将审批事件上下文转换为不可变 `NotificationPlan`。它负责策略优先级、接收人解析、有效用户与偏好过滤、模板渲染和催办正文生成；通知表写入由 `WorkflowNotificationWriter` 统一执行。

## 使用方式

- 单个任务或流程事件调用 `plan(NotificationRequest)`。
- 同一事务内的多个事件调用 `plan(Collection<NotificationRequest>)`，用户状态和偏好只批量查询一次。
- 抄送调用 `planCopies(copies, definitions)`；调用方按 `processId` 去重查询 `definitions` 并提供完整 `wf_copy` 事实，Planner 负责纯规划。

## 公开方法

- `plan(NotificationRequest)`：返回单个事件的不可变写入计划。
- `plan(Collection<NotificationRequest>)`：按请求、接收人和固定通道顺序生成批量计划。
- `planCopies(Collection<WfCopy>, Map<String, ProcessDefinition>)`：使用 `COPY:{copyEventId}:{userId}` 作为自然来源键生成抄送计划。

## 关键设计

- 策略优先级固定为 `NODE > PROCESS > DEFAULT`。
- 普通通知仅保留有效用户，并按 `wf_notification_preference` 过滤 `INBOX`、`EMAIL`、`SMS`。
- 接收人和通道使用保持插入顺序的不可变集合，保证批量 SQL 顺序稳定。
- 催办正文先渲染模板、再追加原因，最后统一按字段上限截断一次。

## 最小接入示例

```java
NotificationPlan plan = planner.plan(request);
WorkflowNotificationWriter.WriteResult result = writer.write(plan);
```
