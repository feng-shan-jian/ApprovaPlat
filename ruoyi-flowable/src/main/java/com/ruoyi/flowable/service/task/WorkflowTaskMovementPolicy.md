# WorkflowTaskMovementPolicy

## 作用

`WorkflowTaskMovementPolicy` 只负责分析已部署的 BPMN 模型，不直接写 Flowable 数据。它为驳回、退回和撤回提供保守的串行迁移门禁，防止状态变更命令穿越并行、多实例、子流程或跨作用域边界。

## 规则

- 状态迁移端点必须是主流程中的普通用户任务或结束节点。
- 普通退回来源仍必须是主流程普通同步用户任务。整组退回只额外允许使用平台受控 handler、无异步/补偿/边界事件的主流程并行多实例用户任务，并由正式轮次服务证明成员、模式、revision 和根 execution。
- 任一路径出现并行、包容、复杂或事件网关时，整条迁移关系不可用；排他网关可保留串行语义。
- 受控多实例来源可以是首审批节点本身，也可以位于普通首审批之后。首审批到来源的安全串行路径允许连续出现多个平台受控同步 ALL/ANY 节点，例如“普通 → ALL → ANY”或“普通 → ALL → ALL → ANY”，并返回这些节点的不可变 `ControlledReturnPathPlan` 供轮次服务逐项对账。
- 任一路径出现子流程、调用活动、服务/脚本活动、并行或包容网关、事件边界、补偿、异步或跨作用域节点时，整条迁移关系不可用。
- 静态多实例、损坏的 SequenceFlow、循环/回边以及超过遍历预算的图同样失败关闭；路径安全不依赖运行时条件猜测。
- 退回目标只能由服务端从实例真实历史确定为首个审批节点，客户端不能查询或提交目标节点。
- 驳回接口不接收结束节点，因此 BPMN 只能存在一个主流程结束节点，且当前任务到该节点的路径必须安全。
- 撤回只允许来源用户任务的直接同步普通用户任务后继；并行网关必须形成可证明的纯分支，且每个分支只有一个无条件普通用户任务。分析结果以不可变 `RevokeMovementPlan` 返回。
- BPMN 图遍历有硬上限，异常模型以状态冲突拒绝，不执行猜测式迁移。

## 接入示例

```java
UserTask current = movementPolicy.requireMainProcessReturnSource(
        bpmnModel, processDefinition.getKey(), task.getTaskDefinitionKey());
movementPolicy.requireSafeDirectReturnPath(process, firstApprovalTask, current);

UserTask controlledCurrent = movementPolicy.requireMainProcessControlledReturnSource(
        bpmnModel, processDefinition.getKey(), task.getTaskDefinitionKey());
WorkflowTaskMovementPolicy.ControlledReturnPathPlan controlledPath =
        movementPolicy.requireSafeControlledReturnPath(
        process, firstApprovalTask, controlledCurrent);

WorkflowTaskMovementPolicy.RevokeMovementPlan revokePlan =
        movementPolicy.requireSafeRevokeMovement(process, completedUserTask);
```

调用方必须在同一事务内重新查询任务、流程实例、历史和执行树，并在真正执行 `changeState()` 前由服务端确定首审批节点，再使用与来源类型对应的策略校验路径。该类只证明 BPMN 拓扑可安全重放；受控多实例成员、计数、轮次和并发事实仍由轮次服务负责。客户端不参与目标选择。
