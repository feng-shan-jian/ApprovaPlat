# WorkflowProcessEngineAdapter

## 组件简介与作用

`WorkflowProcessEngineAdapter` 是 P3 任务动作 Service 调用 Flowable 8 的受控入口。它只使用 Flowable 公共 API，封装任务认领、取消认领、委派、解决委派和转办。

该组件实现引擎级身份、候选人、办理人、委派和挂起状态约束。P3 Service 负责菜单/接口权限、业务对象归属、业务状态、幂等、表单校验及业务表一致性检查。任务完成统一走 `WorkflowTaskLifecycleService.completeTask(...)`，执行表单、附件、抄送、动态下一办理人和动态多实例 revision 链。

## 接入与使用方式

P3 Service 通过构造器注入 Adapter。需要同时修改业务表和 Flowable 表时，P3 Service 应使用同一事务管理器开启外层事务，先完成业务权限和状态校验，再调用 Adapter；Adapter 会加入该事务，并在同一事务内重新核验当前身份、校验任务状态和执行引擎命令。

Adapter 是 `TaskService` claim、unclaim、delegate、resolve 和 setAssignee 命令的唯一生产入口，并统一执行候选人、当前办理人、认领来源、目标用户主数据、委派态、挂起态和稳定异常映射。任务完成由 `WorkflowTaskLifecycleService.completeTask(...)` 独占，覆盖完整表单、附件、通知与审计链。

模型发布通过 `WorkflowModelService` 的校验、版本和部署表单链；人工发起先持久化草稿，再由 `WorkflowProcessDraftService.submit(...)` 在同一事务中调用 `WorkflowProcessStartService.startDraft(...)`，统一执行 starter 授权、变量 schema、附件绑定、发起快照、`initiator` 和 `processStatus`。Adapter 只承担任务基础动作，测试 fixture 可在测试源码中直接组合 Flowable 公共 API。

## 公开方法

| 方法 | 入参 | 返回值 | 主要约束 |
| --- | --- | --- | --- |
| `claimTaskForCurrentUser(String taskId)` | 非空任务 ID | 无 | 仅当前实时具备完整 `claim` 资格的有效用户认领活动实例中的未认领、非委派候选任务；状态和认领审计 comment 原子写入 |
| `unclaimTaskForCurrentUser(String taskId)` | 非空任务 ID | 无 | 仅活动实例中 assignee/claimedBy 均为当前用户、claimTime 存在且 owner/delegation 均为空的真实认领任务；状态和审计 comment 原子写入 |
| `delegateTaskForCurrentUser(String taskId, String targetUserId, String opinion, WorkflowTaskWriteHook writeHook)` | 非空任务 ID、目标用户 ID、受控意见和业务写入钩子 | 无 | 仅当前 assignee 可委派普通任务；受控动态多实例返回 `409`；目标用户须存在、启用且实时具备完整 `approval` 资格；意见、状态、审计和业务写入原子提交 |
| `resolveTaskForCurrentUser(String taskId, String opinion, WorkflowTaskWriteHook writeHook)` | 非空任务 ID、真实办理意见和业务写入钩子 | 无 | 仅当前 PENDING 受托人可解决委派；owner 必须为规范格式、仍有效、实时具备完整 `approval` 资格且与受托人不同；意见、状态、审计和业务写入原子提交 |
| `transferTaskForCurrentUser(String taskId, String targetUserId, String opinion, WorkflowTaskWriteHook writeHook)` | 非空任务 ID、目标用户 ID、受控意见和业务写入钩子 | 无 | 仅当前 assignee 可转办普通任务；受控动态多实例返回 `409`；目标用户须存在、启用且实时具备完整 `approval` 资格；转办会终结原 claim 来源并清空 `claimedBy/claimTime`；意见、状态、审计和业务写入原子提交 |

## 任务命令状态与权限矩阵

Adapter 区分两类实时资格。直接办理资格 `approval` 要求 `workflow:process:todoList`、`workflow:process:query`、`workflow:process:approval`；候选认领资格 `claim` 还要求 `workflow:process:claimList` 和 `workflow:process:claim`。普通用户可从全部有效角色聚合这些权限，用户 `1` 保持超级管理员语义。委派/转办目标及委派 owner 会被直接写为办理人，因此校验 `approval`；claim 会把候选人变为正式 assignee，因此当前用户必须通过更严格的 `claim` 校验。

| 命令 | 允许状态 | 权限/身份条件 | 失败结果 |
| --- | --- | --- | --- |
| 认领 | active、assignee/delegation 均为空 | 当前用户实时具备完整五项 `claim` 资格，且是 direct candidate 或任务声明的有效 `ROLE<id>`/`DEPT<id>` candidate 组成员 | 已认领、委派或挂起为 `409`；缺少任一认领/后续办理权限或非 candidate 为 `403` |
| 取消认领 | active、owner/delegation 均为空，claimedBy 与 assignee 均为当前用户且 claimTime 存在 | 当前用户必须是本次真实 claim 的办理人 | 静态指派、转办或认领元数据不完整为 `409`；其他 assignee 为 `403` |
| 委派 | active、owner/delegation 均为空，且不是受控动态多实例 | 当前 assignee；目标用户经正式主数据实时确认具备流程办理权限且不是本人 | 受控 handler 候选、已有 owner/delegation 或挂起为 `409`；非 assignee 为 `403`；无效、无办理权限或本人目标为 `400` |
| 解决委派 | active、delegation=`PENDING` | 当前 delegate assignee；owner 规范、仍有效且实时具备流程办理权限 | 非 PENDING、owner 缺失/失效/无办理权限/非规范/自环或挂起为 `409`；非 assignee 为 `403` |
| 转办 | active、owner/delegation 均为空，且不是受控动态多实例 | 当前 assignee；目标用户经正式主数据实时确认具备流程办理权限且不是本人；原认领来源在同一事务内终结 | 受控 handler 候选、已有 owner/delegation 或挂起为 `409`；非 assignee 为 `403`；无效、无办理权限或本人目标为 `400` |
五类命令遇到运行时任务不存在均返回 `404`；Flowable 乐观锁、并发认领和非法引擎状态统一返回 `409`。当前用户已停用或删除时在任务访问前返回 `403`，任务保持原状态。认领还会在读取任务前实时核验完整 `claim` 资格；权限撤回后，历史页面提交返回 `403`，assignee 和审计保持原值。

委派和转办会先由任务的 `processDefinitionId` 读取流程定义 key，精确选择部署模型中的对应 `Process`，再递归读取 `SubProcess` 并同时检查多实例 `inputDataItem` 与 `collectionString`。同一 BPMN 部署包含多个 Process 时按任务的流程定义 key 精确定位归属 Process。任一字段命中固定 `${multiInstanceHandler.getUserIds(execution)}` 时都返回 `409`，包括完整动态模型和声明 handler 但白名单残缺的畸形模型。动态任务通过 `WorkflowTaskLifecycleService.completeTask` 提交服务端状态对应的 `expectedRevision`，以保证 COMPLETE 与 ADD/REMOVE 争抢同一 revision、记录前后版本并由同一事务回滚附件、抄送、表单快照和任务状态；动态成员任务的 assignee 始终由多实例运行态维护。

认领、取消认领、委派、解决委派和转办还会校验任务所属流程实例处于活动态。五个动作在同一个 `WorkflowEngineOperations.writeAsCurrentUser` 身份上下文和事务中先执行状态命令，再写入结构化 JSON comment；任一步或事务提交失败都会整体回滚。审计中的 `actorUserId` 来自事务内可信身份，`targetUserId` 来自正式启用用户解析结果，流程实例主键来自任务真实关联；客户端请求只提交业务动作参数。

候选任务转办前若存在 `claimedBy` 或 `claimTime`，Adapter 会先通过 Flowable 公共 `unclaim` 命令清除认领来源，再写入新的 assignee。该清理与转办、审计和抄送共享事务；A 认领后经 A -> B -> A 多次转办时，最初的取消认领资格保持终结。

候选组使用当前有效主数据解析出的 `ROLE<roleId>` 和 `DEPT<deptId>`。认领授权只匹配 `IdentityLinkType.CANDIDATE`，其余 IdentityLink 类型保持各自审计语义。

## 异常语义

| HTTP 状态 | 典型原因 |
| --- | --- |
| `400` | 必填字符串为空、委派/转办目标无效或为本人、Flowable 参数非法 |
| `403` | 当前用户主数据无效、candidate 或 assignee 身份校验失败、引擎权限校验失败 |
| `404` | 任务或其他 Flowable 对象不存在/已删除 |
| `409` | 挂起或非法任务状态、静态指派任务取消认领、owner/delegation 不合法、重复/并发命令、乐观锁冲突 |
| `500` | 流程定义 key、部署模型、所属 Process 或任务节点元数据不一致，未分类的 Flowable 引擎失败或身份主数据异常 |

异常由 [WorkflowExceptionTranslator](WorkflowExceptionTranslator.md) 转换为稳定消息，原始引擎异常只保留在 `cause` 中供服务端日志追踪。

## 最小接入示例

```java
@Service
public class TaskClaimService
{
    private final WorkflowProcessEngineAdapter processEngineAdapter;

    public TaskClaimService(WorkflowProcessEngineAdapter processEngineAdapter)
    {
        this.processEngineAdapter = processEngineAdapter;
    }

    public void claim(String taskId)
    {
        processEngineAdapter.claimTaskForCurrentUser(taskId);
    }
}
```

任务完成由 `WorkflowTaskLifecycleService` 承担，并提交正式六参数请求：

```java
taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
        taskId, comment, variables, copyUserIds, nextUserIds, expectedRevision));
```

委派、解决委派和转办由 `WorkflowTaskActionService` 准备真实抄送计划，再调用必须携带 `WorkflowTaskWriteHook` 的 Adapter 入口。PENDING 受托人通过解决委派入口提交：

```java
processEngineAdapter.resolveTaskForCurrentUser(taskId, comment, (actor, task) -> {
    WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
            WorkflowTaskCopyAction.RESOLVE, task, actor, copyUserIds);
    return () -> taskCopyService.persist(copyPlan);
});
```
