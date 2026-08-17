# WorkflowNotificationRegistrar

## 组件简介

`WorkflowNotificationRegistrar` 负责把已经通过权限和状态校验的流程业务事件转换为通知策略、接收人和不可变 outbox/inbox 事实。登记必须加入调用方当前的 Flowable 写事务，使业务状态与通知事实原子提交。

## 使用方式

- 任务创建、指派、完成和稳定任务动作调用 `onTaskEvent`、`onStableTaskEvent` 或 `onStableTaskAction`。
- 流程完成、取消、驳回和终止调用 `onProcessResult`。
- 抄送创建调用 `onCopiesCreated` 或 `onCopyCreated`。
- SLA、BPMN 事件调用 `publishSynchronousInbox`。
- 人工催办只由 `WorkflowManualUrgeService` 构造 `WorkflowManualUrgeRegistration` 后调用 `registerManualUrge`。

## 参数与返回

该服务没有前端 props、emits 或公开组件方法。公开 Java 方法接收 Flowable 主键、冻结业务快照或独立领域记录，返回实际新增 outbox 数量、接收人集合或同步 inbox 主键。参数错误、幂等事实漂移和缺少写事务时抛出稳定 `ServiceException`。

## 关键设计

- Registrar 不负责策略 CRUD、worker 领取、渠道调用、重试、死信或管理分页。
- 普通审批事件先匹配启用策略，再解析正式用户目录和候选身份。
- 幂等键关联的持久化事实必须与本次冻结事实完全一致，禁止把冲突重放当作成功。
- 模板仅渲染白名单变量，策略字段校验由 `WorkflowNotificationPolicyService` 独占。

## 最小接入示例

```java
notificationRegistrar.onProcessResult(
        "PROCESS_COMPLETED", processDefinitionId, processInstanceId);
```
