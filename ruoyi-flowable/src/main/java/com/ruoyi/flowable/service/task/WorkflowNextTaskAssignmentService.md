# WorkflowNextTaskAssignmentService

## 作用

`WorkflowNextTaskAssignmentService` 在完成当前任务时，对受限且可确定的直接后继用户任务应用动态办理人。普通后继选择一人时写入 assignee，选择多人时写入 candidate user；受控并行多实例会在完成前写入节点专属 `wfMiUsers_<activityId>`，再由 `multiInstanceHandler` 创建真实 assignee 任务。

## 接入方式

完成链先装载唯一 BPMN 上下文并定位当前 `UserTask`，再调用 `prepare` 校验用户、当前执行树和同一 `Process` 内的 BPMN 拓扑；`TaskService.complete` 创建真实后继任务后调用 `apply`：

```java
AssignmentPlan plan = nextTaskAssignmentService.prepare(
        task,
        bpmnContext.process(),
        currentUserTask,
        List.of(12L, 13L));
taskService.complete(task.getId());
nextTaskAssignmentService.apply(plan);
```

`prepare` 使用调用方传入的已核验流程定义和 BPMN Model。两个调用必须处于同一写事务；`apply` 失败时，来源任务完成和已经产生的后继任务均必须回滚。

`nextUserIds` 中每个用户都必须处于启用、有效状态。服务端在访问执行树或写集合变量前先按正式 RBAC 数据校验直接办理资格；页面候选列表仅提供选择体验。普通后继选择多人时，还会在任何任务身份写入前追加完整认领资格校验。用户 `1` 按既有超级管理员契约视为合格。

## approval 与 claim 契约

| 分配结果 | 所需能力 | 必须具备的权限 |
| --- | --- | --- |
| 普通后继选择一人，写入唯一 assignee | `approval` | `workflow:process:todoList`、`workflow:process:query`、`workflow:process:approval` |
| 受控并行多实例，为每名成员创建 assignee 任务 | `approval` | 同上三项直接办理权限 |
| 普通后继选择多人，逐个写入 candidate user | `claim` | `approval` 三项权限，加 `workflow:process:claimList`、`workflow:process:claim` |

普通用户的权限可由全部有效角色并集满足，各项权限可以来自不同角色。`claim` 是 `approval` 的严格超集；普通多人候选计划只接收具备完整 `claim` 资格、能够查看并认领任务的用户。

任一用户缺少 `approval` 时整批返回 HTTP 400 `所选用户不存在、已停用或无流程办理权限`；普通多人计划中任一用户缺少 `claim` 时整批返回 HTTP 400 `所选候选用户不存在、已停用或无完整认领权限`。两种失败都发生在来源任务完成和身份写入前，流程变量、后继任务与审计保持原状态。

## 支持范围

- 实例当前只有来源任务一个活动任务。
- 来源节点为主流程单实例用户任务；动态多实例初始化前驱恰有一条入边，确保只产生一个来源任务。
- 目标可以是普通用户任务，也可以是完整满足动态多实例固定模型契约的主流程同步、不可跳过的并行用户任务。
- 来源节点只有一条无条件 sequence flow，且直接指向目标用户任务。
- 普通后继完成后必须恰有一个活动任务且节点 key 与计划一致；受控并行多实例必须恰有与成员数量相同的活动 assignee 任务，并通过 execution、成员快照、模式、revision 和 `nrOf*` 根计数对账。

条件分支、网关、事件、子流程边界、串行/自由表达式多实例、额外活动任务或监听器改变 owner 的场景返回 `409`；单一安全直接后继进入动态指定。

## 分配规则

- 单一 assignee 或受控多实例成员页面通过 `GET /workflow/identity/options?type=user&capability=approval` 分页读取合格用户；普通后继选择多名候选人时使用 `capability=claim`。目录用于交互，写命令在事务内再次执行实时资格校验。
- 选择一人：删除静态候选身份并设置唯一 assignee。
- 选择多人：删除静态候选身份、清空 assignee，并逐个写入 candidate user。
- 受控并行多实例：完成前写集合变量，完成后通过 `WorkflowMultiInstanceRuntimeSnapshotReader` 唯一解析真实 task/execution、成员快照、ALL/ANY 模式、revision=0 和 `nrOf*` 根计数。
- 写入后重新读取任务和 identity link；assignee、候选用户或候选组与计划不一致时抛错回滚。
- 未提交 `nextUserIds` 时仍使用完成链已经装载的正式部署模型；普通后继返回空计划并保留 BPMN 默认分配行为，唯一无条件直连受控动态多实例时在完成命令前返回 HTTP 400。
