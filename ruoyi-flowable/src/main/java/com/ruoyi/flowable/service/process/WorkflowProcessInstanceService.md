# WorkflowProcessInstanceService

## 作用

`WorkflowProcessInstanceService` 统一承载 Flowable 8 流程实例的状态切换、发起人取消、管理员终止和已结束历史删除。所有写动作均通过 `WorkflowEngineOperations.writeAsCurrentUser` 在同一 Spring 事务及 Flowable 认证用户上下文中完成。

## 对外方法

| 方法 | 权限与对象约束 | 持久化结果 |
| --- | --- | --- |
| `updateState(request)` | 必须拥有 `workflow:process:state`；实例必须存在且仍在运行 | 使用 Flowable 公共 API 激活或挂起；相同状态返回 `changed=false` |
| `terminate(request)` | `workflow:process:terminate` 按管理员终止，写 `terminated`；`workflow:process:cancel` 仅允许根实例真实发起人取消本人业务树，写 `canceled` | 子实例请求提升到根实例，更新根历史变量、写类型 `6` 的结构化 comment、级联删除完整运行树并保留结束历史 |
| `deleteCompletedHistory(ids)` | 必须拥有 `workflow:process:remove`；整批实例及子流程必须全部结束，且不存在 `BOUND` 附件 | 同事务逻辑删除 `wf_copy`、物理删除 `wf_controlled_loop_execution` 与 Flowable 历史，随后复核无残留 |

## 请求示例

状态切换：

```json
{
  "instanceId": "25001",
  "state": "suspended"
}
```

发起人取消或管理员终止：

```json
{
  "instanceId": "25001",
  "reason": "申请内容已失效"
}
```

## 状态与审计

- 发起人只有同时命中真实 `START_USER_ID_` 和 `workflow:process:cancel` 才能取消，结果为 `processStatus=canceled`。
- 具备 `workflow:process:terminate` 的流程管理员执行终止，结果为 `processStatus=terminated`，不会因为恰好也是发起人而降级成取消。
- CallActivity 子实例 ID 只作为定位信息；服务端校验 `rootProcessInstanceId`、`superExecutionId` 和完整执行树后，始终以根实例执行对象授权、状态写入和级联删除，禁止单独删除子实例。
- 结构化 comment 固定包含 `action`、`actorUserId`、`processStatus`、`reason`、`wasSuspended`、`requestedInstanceId`、`rootInstanceId` 和 `processTreeInstanceCount`，字段结构不接受客户端控制。
- 挂起根实例会在同一事务内临时激活，以便写入变量和 comment，随后立即终止；任何一步失败都会整体回滚。
- 写后门禁同时核对冻结的根/子实例、根 execution 树和所有活动 task 均无残留，并复核根历史终态、历史变量和审计 comment；任一漂移触发事务回滚。
- 已结束、重复终止或状态竞争返回 `409`；不存在返回 `404`；对象越权返回 `403`。

## 历史删除一致性

删除入口先对整批实例执行完整预检，再展开调用活动产生的所有子流程。运行中的根实例或子流程都会阻止整批删除。删除图中任一实例存在 `BOUND` 附件时，在抄送或 Flowable 写入前返回 `409`，保留附件及其历史审计链；后续只有在单独批准附件保留期和合规清理策略后才能改变该规则。`wf_copy`、`wf_controlled_loop_execution` 与 Flowable 历史的预检数量、实际删除数量和最终残留数量必须完全一致，否则事务回滚，避免循环审计成为无实例或无部署配置的孤儿。单批最多接收 100 个目标，展开后的历史图最多 1000 个实例。
