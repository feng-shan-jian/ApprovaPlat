# WorkflowMultiInstanceService

## 作用

`WorkflowMultiInstanceService` 提供受控动态并行多实例的状态查询、加签和减签。
服务只接受部署 BPMN 中固定的 `${assignee}`、`assignee` 元素变量、
`${multiInstanceHandler.getUserIds(execution)}` 集合表达式以及 ALL/ANY 完成条件。
任务完成链还通过本服务校验 `expectedRevision` 并执行 revision CAS；普通任务直接返回空计划。

## 接入方式

- 查询：`GET /workflow/task/multiInstance/{taskId}`。
- 调整：`POST /workflow/task/multiInstance/adjust`。
- 两个入口都复用 `workflow:process:approval` 权限；领域层还要求当前用户是
  `taskId` 对应活动任务的真实 assignee。
- 完成：由 `WorkflowTaskLifecycleService` 传入唯一 BPMN 上下文中已经定位的当前
  `UserTask`；轮次服务仍独立读取部署定义，核对部署、活动和根 execution 的正式关联。

## 调整请求

加签示例：

```json
{
  "taskId": "task-current",
  "action": "ADD",
  "expectedRevision": 0,
  "comment": "增加财务复核人",
  "userIds": [12, 19],
  "targetTaskId": null
}
```

减签示例：

```json
{
  "taskId": "task-current",
  "action": "REMOVE",
  "expectedRevision": 1,
  "comment": "移除重复审批节点",
  "userIds": [],
  "targetTaskId": "task-sibling"
}
```

## 返回状态

返回 `mode`、`activityId`、`revision` 和有序 `members`。成员包含 `userId`、
`name`、`activeTaskId`、`executionId`、`active`、`removable`。页面必须在每次
成功或 `409` 后重新查询，不能用本地数组推测引擎状态。

## 关键设计

- handler 在多实例创建命令内校验正式启用用户并初始化成员快照、模式和 revision。
- 动态加签在 revision、execution、变量和 comment 写入前实时核对每个目标用户：用户必须
  启用、未删除，并拥有 `workflow:process:approval` 权限；用户 `1` 保持若依超级管理员语义。
  页面候选目录使用 `GET /workflow/identity/options?type=user&capability=approval`，但服务端
  写命令仍独立重查，防止直接 API 或过期页面绕过授权。
- 状态查询和调整等独立入口仍先由任务的 `processDefinitionId` 读取流程定义 key，
  再从部署模型精确选择对应 `Process` 并递归定位 `SubProcess` 中的任务；同一 BPMN
  部署包含多个 Process 时，不使用第一个或 `mainProcess` 猜测任务归属。
- 完成入口复用生命周期服务已校验的 `Task` 和 `UserTask`；两类入口随后进入同一个
  私有上下文装载方法，统一实时读取多实例根 execution、活动兄弟任务、成员快照、
  completion mode、revision 和 Flowable 三项计数，并与当前唯一 `ACTIVE` 正式轮次逐项
  对账。两种 `isSupportedControlledTask` 入口最终复用同一 `UserTask` 模型规则，畸形
  受控模型继续返回原有冲突。
- 加签和减签只调用 Flowable 8 的 `addMultiInstanceExecution` 与
  `deleteMultiInstanceExecution` 公共 API，不直接修改 `nrOf*` 变量。
- revision 变量更新依赖 Flowable 持久化 revision 形成 CAS。加减签固定按“引擎
  revision、业务轮次 CAS、有序成员变量、execution 动作”执行；业务 CAS 输家不会继续
  写成员变量或 execution。完成链在 `TaskService.complete` 前同步引擎和轮次 revision，
  并写 task-local 预留标记；complete 监听器只核验该版本，整组结束时以相同 revision
  把轮次转为 `COMPLETED`。相同 revision 的并发请求只有一个事务可以提交。
- 动态 `ADD`、`REMOVE` 和 `COMPLETE` 的失败方若命中 Flowable 乐观锁、Spring
  事务提交并发异常、MySQL 死锁/锁等待，或 CAS 后目标 task/execution 被并发删除，
  返回 `409` 并携带稳定子码 `WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT`。调用方据此
  重新查询服务端状态，不得在本地递增 revision 后盲目重试。
- 完成命令后继续核对多实例根、活动 task/execution、冻结成员、模式和三项引擎计数；
  正式轮次的完成状态只由同步 completion listener 的 Mapper CAS 确认，外层不再回读
  刚更新的轮次。流程直接结束时仍核对已结束历史和旧根消失。
- 每次成功调整写入结构化 Flowable comment，若依 Controller 同时记录操作日志。

## 错误语义

- `400`：字段组合、用户集合、重复用户不合法，或目标用户不存在、停用、删除、缺少
  `workflow:process:approval` 权限；资格失败时返回
  `所选用户不存在、已停用或无流程办理权限` 且零写副作用。
- `403`：当前用户不是授权任务的真实 assignee。
- `404`：任务不存在且当前用户没有可识别的历史对象。
- `409`：revision 过期、任务过期、模型不支持或 execution 状态不安全。只有客户端
  `expectedRevision` 与服务端不一致或异常链证明为真实动态并发 loser 时携带
  `WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT`；减签自身、仅剩一个活动成员、委派/owner、
  模型不支持等普通业务 `409` 不携带该子码。
- `500`：流程定义 key、部署模型、所属 Process、服务端成员快照、引擎计数、正式轮次或
  task/execution 关联不一致。
