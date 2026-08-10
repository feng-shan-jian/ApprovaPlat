# WorkflowProcessEngineAdapter

## 组件简介与作用

`WorkflowProcessEngineAdapter` 是 P3 任务业务 Service 调用 Flowable 8 的受控入口。它只使用 Flowable 公共 API，封装活动实例/任务查询，以及任务认领、取消认领、委派、解决委派、转办和完成，并把运行时对象转换为模块自有的不可变快照。

该组件实现引擎级身份、候选人、办理人、委派和挂起状态约束。它不替代 P3 的菜单/接口权限、业务对象归属、业务状态、幂等、表单校验及业务表一致性检查。

## 接入与使用方式

P3 Service 通过构造器注入 Adapter。需要同时修改业务表和 Flowable 表时，P3 Service 应使用同一事务管理器开启外层事务，先完成业务权限和状态校验，再调用 Adapter；Adapter 会加入该事务，并在同一事务内重新核验当前身份、校验任务状态和执行引擎命令。

禁止绕过 Adapter 直接调用 `TaskService` 的 claim、unclaim、delegate、resolve、setAssignee 或 complete 命令。直接调用会跳过候选人、当前办理人、认领来源、目标用户主数据、委派态、挂起态和稳定异常映射。

Adapter 不提供原始 BPMN 部署或按 definitionId 直接发起方法。模型发布必须走 `WorkflowModelService` 的校验、版本和部署表单链；流程发起必须走 `WorkflowProcessStartService`，统一执行 starter 授权、变量 schema、附件绑定、发起快照、`initiator` 和 `processStatus`。测试 fixture 可在测试源码中直接调用 Flowable 公共 API，但不得把该能力重新暴露为生产 Bean 方法。

## 公开方法

| 方法 | 入参 | 返回值 | 主要约束 |
| --- | --- | --- | --- |
| `findActiveProcessInstance(String processInstanceId)` | 非空流程实例 ID | `Optional<WorkflowProcessInstanceSnapshot>` | 只查活动实例；不存在或非活动时返回空，不做业务对象权限校验 |
| `findActiveTask(String taskId)` | 非空任务 ID | `Optional<WorkflowTaskSnapshot>` | 只查活动任务；不存在或非活动时返回空，不做业务对象权限校验 |
| `claimTaskForCurrentUser(String taskId)` | 非空任务 ID | 无 | 仅当前实时具备完整 `claim` 资格的有效用户认领活动实例中的未认领、非委派候选任务；状态和认领审计 comment 原子写入 |
| `unclaimTaskForCurrentUser(String taskId)` | 非空任务 ID | 无 | 仅活动实例中 assignee/claimedBy 均为当前用户、claimTime 存在且 owner/delegation 均为空的真实认领任务；状态和审计 comment 原子写入 |
| `delegateTaskForCurrentUser(String taskId, String targetUserId[, String opinion])` | 非空任务 ID、非空目标用户 ID、可选受控意见 | 无 | 仅当前 assignee 可委派普通任务；受控动态多实例返回 `409`；目标用户须存在、启用且实时具备完整 `approval` 资格，三参数重载要求意见非空且不超过 500 字符 |
| `resolveTaskForCurrentUser(String taskId, String opinion[, WorkflowTaskWriteHook])` | 非空任务 ID、真实办理意见和可选事务钩子 | 无 | 仅当前 PENDING 受托人可解决委派；owner 必须为规范格式、仍有效、实时具备完整 `approval` 资格且不能与受托人相同；意见、状态、审计和业务写入原子提交 |
| `transferTaskForCurrentUser(String taskId, String targetUserId[, String opinion])` | 非空任务 ID、非空目标用户 ID、可选受控意见 | 无 | 仅当前 assignee 可转办普通任务；受控动态多实例返回 `409`；目标用户须存在、启用且实时具备完整 `approval` 资格；转办会终结原 claim 来源并清空 `claimedBy/claimTime`，三参数重载要求意见非空且不超过 500 字符 |
| `completeTask(String taskId, Map<String, Object> variables)` | 非空任务 ID、可空变量 | 无 | 仅活动、未挂起且当前用户为 assignee；显式把当前用户写入 Flowable 8 历史任务 `completedBy`；`PENDING` 委派必须先 resolve；受控动态多实例必须改走 `WorkflowTaskLifecycleService.completeTask` 的 `expectedRevision` 链 |

## 任务命令状态与权限矩阵

Adapter 区分两类实时资格。直接办理资格 `approval` 要求 `workflow:process:todoList`、`workflow:process:query`、`workflow:process:approval`；候选认领资格 `claim` 还要求 `workflow:process:claimList` 和 `workflow:process:claim`。普通用户可从全部有效角色聚合这些权限，用户 `1` 保持超级管理员语义。委派/转办目标及委派 owner 会被直接写为办理人，因此校验 `approval`；claim 会把候选人变为正式 assignee，因此当前用户必须通过更严格的 `claim` 校验。

| 命令 | 允许状态 | 权限/身份条件 | 明确拒绝状态 |
| --- | --- | --- | --- |
| 认领 | active、assignee/delegation 均为空 | 当前用户实时具备完整五项 `claim` 资格，且是 direct candidate 或任务声明的有效 `ROLE<id>`/`DEPT<id>` candidate 组成员 | 已认领、委派或挂起为 `409`；缺少任一认领/后续办理权限或非 candidate 为 `403` |
| 取消认领 | active、owner/delegation 均为空，claimedBy 与 assignee 均为当前用户且 claimTime 存在 | 当前用户必须是本次真实 claim 的办理人 | 静态指派、转办或认领元数据不完整为 `409`；其他 assignee 为 `403` |
| 委派 | active、owner/delegation 均为空，且不是受控动态多实例 | 当前 assignee；目标用户经正式主数据实时确认具备流程办理权限且不是本人 | 受控 handler 候选、已有 owner/delegation 或挂起为 `409`；非 assignee 为 `403`；无效、无办理权限或本人目标为 `400` |
| 解决委派 | active、delegation=`PENDING` | 当前 delegate assignee；owner 规范、仍有效且实时具备流程办理权限 | 非 PENDING、owner 缺失/失效/无办理权限/非规范/自环或挂起为 `409`；非 assignee 为 `403` |
| 转办 | active、owner/delegation 均为空，且不是受控动态多实例 | 当前 assignee；目标用户经正式主数据实时确认具备流程办理权限且不是本人；原认领来源在同一事务内终结 | 受控 handler 候选、已有 owner/delegation 或挂起为 `409`；非 assignee 为 `403`；无效、无办理权限或本人目标为 `400` |
| 完成 | active、delegation 为空或 `RESOLVED`，且不是受控动态多实例 | 当前 assignee | `PENDING`、挂起或受控 handler 候选为 `409`；非 assignee 为 `403` |

六类命令遇到运行时任务不存在均返回 `404`；Flowable 乐观锁、并发认领和非法引擎状态统一返回 `409`。当前用户已停用或删除时先返回 `403`，不读取或修改任务。认领还会在读取任务前实时核验完整 `claim` 资格，权限被撤回后即使旧页面仍显示任务也返回 `403`，不会产生 assignee 或审计写入。

低层委派、转办和 `completeTask` 会先由任务的 `processDefinitionId` 读取流程定义 key，精确选择部署模型中的对应 `Process`，再递归读取 `SubProcess` 并同时检查多实例 `inputDataItem` 与 `collectionString`。同一 BPMN 部署包含多个 Process 时不使用 `mainProcess` 猜测归属。任一字段命中固定 `${multiInstanceHandler.getUserIds(execution)}` 时都返回 `409`，包括完整动态模型和声明 handler 但白名单残缺的畸形模型。动态任务只能通过 `WorkflowTaskLifecycleService.completeTask` 提交服务端状态对应的 `expectedRevision`，以保证 COMPLETE 与 ADD/REMOVE 争抢同一 revision、记录前后版本并由同一事务回滚附件、抄送、表单快照和任务状态；委派和转办不得改写动态成员任务的 assignee。普通任务与不命中受控 handler 的既有静态多实例保持原完成语义。

认领、取消认领、委派和转办还会校验任务所属流程实例处于活动态。四个动作在同一个 `WorkflowEngineOperations.writeAsCurrentUser` 身份上下文和事务中先执行状态命令，再写入结构化 JSON comment；任一步或事务提交失败都会整体回滚。审计中的 `actorUserId` 来自事务内可信身份，`targetUserId` 来自正式启用用户解析结果，流程实例主键来自任务真实关联，客户端不能提交或覆盖这些字段。

候选任务转办前若存在 `claimedBy` 或 `claimTime`，Adapter 会先通过 Flowable 公共 `unclaim` 命令清除认领来源，再写入新的 assignee。该清理与转办、审计和抄送共享事务，保证 A 认领后经 A -> B -> A 多次转办也不会重新获得取消认领资格。

候选组只接受当前有效主数据解析出的 `ROLE<roleId>` 和 `DEPT<deptId>`。只有 `IdentityLinkType.CANDIDATE` 参与认领授权，`participant` 等其他 IdentityLink 类型不能授权。

## 不可变快照

- `WorkflowProcessInstanceSnapshot` 只暴露实例 ID、流程定义 ID、业务主键和挂起标志。
- `WorkflowTaskSnapshot` 只暴露任务 ID、名称、流程实例 ID、任务定义键、assignee、claimedBy、不可变 `Instant` claimTime、owner、委派状态和挂起标志。
- claimedBy 与 claimTime 用于区分 Flowable claim 产生的任务和 BPMN 静态指派/转办任务；它们为空时不能调用取消认领。
- 两种快照均为模块自有 `record`，不把 Flowable 可变运行时对象泄露到业务层。调用方不能借助快照修改引擎状态，任何写入仍须调用 Adapter 命令。

## 异常语义

| HTTP 状态 | 典型原因 |
| --- | --- |
| `400` | 必填字符串为空、委派/转办目标无效或为本人、Flowable 参数非法 |
| `403` | 当前用户主数据无效、非 candidate 认领、非 assignee 执行任务命令、引擎权限拒绝 |
| `404` | 任务或其他 Flowable 对象不存在/已删除 |
| `409` | 挂起或非法任务状态、静态指派任务取消认领、owner/delegation 不合法、重复/并发命令、乐观锁冲突 |
| `500` | 流程定义 key、部署模型、所属 Process 或任务节点元数据不一致，未分类的 Flowable 引擎失败或身份主数据异常 |

异常由 [WorkflowExceptionTranslator](WorkflowExceptionTranslator.md) 转换为稳定消息，原始引擎异常只保留在 `cause` 中供服务端日志追踪。

## 最小接入示例

```java
@Service
public class ExpenseApprovalService
{
    private final WorkflowProcessEngineAdapter processEngineAdapter;

    public ExpenseApprovalService(WorkflowProcessEngineAdapter processEngineAdapter)
    {
        this.processEngineAdapter = processEngineAdapter;
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(Long expenseId, String taskId, Map<String, Object> variables)
    {
        // 真实实现必须先核验接口权限、报销单归属、可审批状态和重复提交。
        processEngineAdapter.completeTask(taskId, variables);
        // 随后在同一事务中更新正式业务表和审计记录；任一步失败则整体回滚。
    }
}
```

只读查询也必须先做业务对象权限判断：

```java
WorkflowTaskSnapshot task = processEngineAdapter.findActiveTask(taskId)
        .orElseThrow(() -> new ServiceException("工作流任务不存在", HttpStatus.NOT_FOUND));
```

委派办理采用两个明确命令，PENDING 受托人不能直接完成任务：

```java
processEngineAdapter.delegateTaskForCurrentUser(taskId, delegateUserId);
// 受托人办理后解决委派，Flowable 会把 assignee 还原为有效 owner。
processEngineAdapter.resolveTaskForCurrentUser(taskId, "已完成受托核验");
```

认领必须直接调用 Adapter，使完整 `claim` 资格、候选 IdentityLink、任务状态和审计处于同一事务：

```java
processEngineAdapter.claimTaskForCurrentUser(taskId);
```
