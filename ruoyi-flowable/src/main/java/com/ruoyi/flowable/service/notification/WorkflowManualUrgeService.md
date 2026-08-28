# WorkflowManualUrgeService

## 组件简介

`WorkflowManualUrgeService` 是人工催办的唯一业务入口，负责实时权限、根流程状态、CallActivity 运行树、活动任务、接收人和 Redis 冷却校验，并在同一写事务直写站内信、登记 EMAIL/SMS 外部 Outbox。

## 使用方式

Controller 将 `WorkflowManualUrgeRequest` 直接传入 `urge(request)`。调用用户必须是流程发起人或具备 `workflow:notification:urge:any` 权限。

## 参数与返回

- `urge(WorkflowManualUrgeRequest request)` 返回 `WorkflowManualUrgeView`，HTTP 响应仅包含本次实际接收人数 `recipientCount`。
- `acquireCooldown(String actorUserId, String processInstanceId)` 建立用户与根流程维度的原子冷却键，供正式催办流程和依赖故障测试复用。

非法流程状态、无活动任务、无有效接收人、越权、冷却冲突和 Redis 不可用分别返回稳定业务异常，响应与真实登记结果保持一致。

## 关键设计

- 先锁定根运行树和活动任务，再基于冻结事实解析候选人。
- 冷却键使用 Redis 原子 `SET NX EX`；依赖不可用返回 503，已有冷却返回 429。
- 服务通过 `WorkflowNotificationService.registerManualUrge` 统一登记通知事实。
- 催办事件键仅在服务内部生成，并作为每个任务通知 `sourceId` 的稳定前缀参与幂等登记。
- CallActivity 子任务使用实际子流程、任务和节点身份生成通知。

## 最小接入示例

```java
WorkflowManualUrgeView result = manualUrgeService.urge(
        new WorkflowManualUrgeRequest(processInstanceId, "请尽快处理"));
```
