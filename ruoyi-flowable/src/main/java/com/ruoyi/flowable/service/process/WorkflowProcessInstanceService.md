# WorkflowProcessInstanceService

## 作用

`WorkflowProcessInstanceService` 统一承载 Flowable 8 流程实例的状态切换、发起人取消、管理员终止和已结束历史删除。所有写动作均通过 `WorkflowEngineOperations.writeAsCurrentUser` 在同一 Spring 事务及 Flowable 认证用户上下文中完成。`terminateRootProcessInstance` 是取消、驳回和管理员终止唯一的根实例终止写入口。

## 对外方法

| 方法 | 权限与对象约束 | 持久化结果 |
| --- | --- | --- |
| `updateState(request)` | 必须拥有 `workflow:process:state`；实例必须存在且仍在运行 | 使用 Flowable 公共 API 激活或挂起；相同状态返回 `changed=false` |
| `terminate(request)` | `workflow:process:terminate` 按管理员终止，写 `terminated`；`workflow:process:cancel` 仅允许根实例真实发起人取消本人业务树，写 `canceled` | 授权后调用统一根终止写入口；子实例请求提升到根实例，更新双状态、写类型 `6` 的结构化 comment、级联删除完整运行树并保留结束历史 |
| `terminateRootProcessInstance(...)` | 仅供当前 Flowable 写事务内的取消、驳回和管理员终止调用 | 一次确认根/执行树，必要时按根优先顺序临时激活完整挂起执行树；双写状态取得引擎写锁后严格对账并非锁冻结轮次，只删根，随后锁定完全相同的 `ACTIVE/RETURNED` 集合、关闭为 `TERMINATED` 并执行后置对账 |
| `deleteCompletedHistory(ids)` | 必须拥有 `workflow:process:remove`；整批实例及子流程必须全部结束，且不存在 `BOUND` 附件 | 先预检 `wf_copy`、`wf_controlled_loop_execution`、`wf_multi_instance_round` 数量，再同事务精确删除业务记录与 Flowable 历史，随后复核全部链路零残留 |

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
- 挂起根及 CallActivity 子流程会按根优先顺序在同一事务内临时激活，以便根和子流程任务都能写入变量及 comment，随后立即终止；不调用 SLA 恢复，任何一步失败都会整体回滚。
- 运行实例异常结束固定遵循 `Flowable→wf_multi_instance_round` 锁序：先冻结并校验完整树主键，完成 comment 和双状态写入取得 Flowable 写锁；执行树仍存在时用普通 SELECT 冻结开放轮次，并从 execution 图识别全部活动受控多实例根，逐项核对部署、定义、实例、节点、根、模式、有序成员、revision 和 `ACTIVE`。通知及根删除返回后才 `FOR UPDATE` 锁定业务行，锁定集合、`sourceStatus` 和全部冻结事实必须完全一致，再批量写 `TERMINATED + terminate_time`。最后使用 locking/current-read 复核无 `ACTIVE/RETURNED` 残留，避免 RR 旧 read-view 或预检后 phantom 绕过门禁。该顺序不会与加签、减签、完成链的引擎优先锁序反转；任一 Mapper 或后置校验失败仍回滚 Flowable 删除。
- `TERMINATED` 是轮次专用异常终态，不冒充 ALL/ANY 正常完成的 `COMPLETED`。从 `ACTIVE` 关闭时退回字段保持全空；从 `RETURNED` 关闭时完整保留退回任务、操作人、申请人任务和 `return_time`，仅新增数据库时钟产生的 `terminate_time`。
- 写后门禁使用 `processInstanceIds` 批量核对冻结的根/子实例，另行核对根 execution 树和所有活动 task 均无残留，并复核根历史双状态、历史变量和可选审计 comment；任一漂移触发事务回滚。
- 已结束、重复终止或状态竞争返回 `409`；不存在返回 `404`；对象越权返回 `403`。

## 历史删除一致性

删除入口先对整批实例执行完整预检，再展开调用活动产生的所有子流程。运行中的根实例或子流程都会阻止整批删除。删除图中任一实例存在 `BOUND` 附件时，在抄送或 Flowable 写入前返回 `409`，保留附件及其历史审计链；后续只有在单独批准附件保留期和合规清理策略后才能改变该规则。

`wf_copy`、`wf_controlled_loop_execution` 和 `wf_multi_instance_round` 都先在当前事务快照中统计数量，再按同一实例集合执行删除；任一实际影响行数与预检数量不一致都会返回 `409` 并回滚。业务记录精确删除后才允许删除 Flowable 历史，随后再次核对 Flowable 历史、有效抄送、受控循环和多实例轮次均为零，避免轮次快照或循环审计成为无实例孤儿。单批最多接收 100 个目标，展开后的历史图最多 1000 个实例。
