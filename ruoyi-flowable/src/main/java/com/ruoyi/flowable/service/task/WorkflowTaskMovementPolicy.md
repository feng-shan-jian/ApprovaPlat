# WorkflowTaskMovementPolicy

## 作用

`WorkflowTaskMovementPolicy` 只负责分析已部署的 BPMN 模型，不直接写 Flowable 数据。它为驳回、退回和撤回提供保守的串行迁移门禁，防止状态变更命令穿越并行、多实例、子流程或跨作用域边界。

## 规则

- 状态迁移端点必须是主流程中的普通用户任务或结束节点。
- 动态、静态、串行或并行多实例任务都不能作为退回来源；成员快照和 revision 未按轮次隔离前，禁止退回后再次进入同一活动。
- 任一路径出现并行、包容、复杂或事件网关时，整条迁移关系不可用；排他网关可保留串行语义。
- 任一路径出现多实例活动、子流程、调用活动或跨作用域节点时，整条迁移关系不可用。
- 退回目标只能由服务端从实例真实历史确定为首个审批节点，客户端不能查询或提交目标节点。
- 驳回接口不接收结束节点，因此 BPMN 只能存在一个主流程结束节点，且当前任务到该节点的路径必须安全。
- BPMN 图遍历有硬上限，异常模型以状态冲突拒绝，不执行猜测式迁移。

## 接入示例

```java
UserTask current = movementPolicy.requireMainProcessReturnSource(
        bpmnModel, processDefinition.getKey(), task.getTaskDefinitionKey());
movementPolicy.requireSafeDirectReturnPath(process, firstApprovalTask, current);
```

调用方必须在同一事务内重新查询任务、流程实例、历史和执行树，并在真正执行 `changeState()` 前由服务端确定首审批节点，再使用本策略校验直达路径。客户端不参与目标选择。
