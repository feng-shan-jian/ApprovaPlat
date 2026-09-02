# WorkflowManualUrgeService

## 组件简介

`WorkflowManualUrgeService` 是人工催办的唯一业务入口，负责实时权限、根流程状态、CallActivity 运行树、活动任务、接收人和 Redis 冷却校验，并在同一写事务直写站内信、登记 EMAIL/SMS 外部 Outbox。详情投影通过同一服务的只读能力入口读取真实可执行性，不再复制催办规则。

## 使用方式

Controller 将 `WorkflowManualUrgeRequest` 直接传入 `urge(request)`。调用用户必须具备 `workflow:notification:urge`，并且是流程发起人或同时具备 `workflow:notification:urge:any`。

`WorkflowProcessDetailService` 在完成对象授权并确认详情属于运行中的根流程后调用 `isUrgeAllowed(processInstanceId)`。已结束、挂起和 CallActivity 子流程详情不会进入该查询。

## 参数与返回

- `urge(WorkflowManualUrgeRequest request)` 返回 `WorkflowManualUrgeView`，HTTP 响应仅包含本次实际接收人数 `recipientCount`。
- `isUrgeAllowed(String processInstanceId)` 返回原始 `boolean`；403、409 等预期不可执行状态投影为 `false`，数据损坏类 500 原样抛出。
- `acquireCooldown(String actorUserId, String processInstanceId)` 建立用户与根流程维度的原子冷却键，供正式催办流程和依赖故障测试复用。

非法流程状态、无活动任务、无有效接收人、越权、冷却冲突和 Redis 不可用分别返回稳定业务异常，响应与真实登记结果保持一致。

## 关键设计

- 能力查询和正式催办复用唯一无副作用准备逻辑，统一核验基础权限、发起人/跨实例权限、完整运行树、活动任务、接收人及数量限制。
- 能力查询只使用普通 `SELECT`，不获取数据库行锁、Redis 冷却锁，不登记通知、Outbox、审计或指标。
- 正式催办在写事务内使用 `FOR UPDATE` / `FOR SHARE` 重新冻结根运行树、活动任务和接收人，随后保持通知登记、冷却和成功指标顺序。
- 冷却键使用 Redis 原子 `SET NX EX`；依赖不可用返回 503，已有冷却返回 429。
- 服务通过 `WorkflowNotificationService.registerManualUrge` 统一登记通知事实。
- 催办事件键仅在服务内部生成，并作为每个任务通知 `sourceId` 的稳定前缀参与幂等登记。
- CallActivity 子任务使用实际子流程、任务和节点身份生成通知。

## 最小接入示例

```java
WorkflowManualUrgeView result = manualUrgeService.urge(
        new WorkflowManualUrgeRequest(processInstanceId, "请尽快处理"));
```
