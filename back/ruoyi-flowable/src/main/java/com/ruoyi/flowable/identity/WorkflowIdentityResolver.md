# WorkflowIdentityResolver

## 组件简介与作用

`WorkflowIdentityResolver` 通过模块自有 `WorkflowIdentityMapper` 读取若依正式主数据，将用户、角色和部门转换为 Flowable 可使用的规范身份。停用或删除的用户、角色和部门不会进入有效身份结果；需要创建真实办理人或候选认领人时，还必须分别通过 `approval` 或 `claim` 实时资格校验。

该组件不创建 Flowable IDM 用户或组，不读取 Flowable IDM 表。身份真源始终是 `sys_user`、`sys_role`、`sys_dept` 及正式关联表。

## 接入与使用方式

- 当前用户执行任务基础命令时，不要在业务层单独调用 `resolveCurrentIdentity()`。应调用 [WorkflowProcessEngineAdapter](../engine/WorkflowProcessEngineAdapter.md)。模型发布、流程发起、动态多实例和复杂生命周期等专用领域服务则直接调用 `WorkflowEngineOperations.writeAsCurrentUser(...)`，在同一引擎写事务内解析身份并立即完成权限判断和命令。
- `resolveActiveUserIds(...)` 适用于候选人预览、通知接收人或业务校验等需要把 BPMN candidate 展开为有效若依用户的场景。调用方仍须根据具体业务做数据范围和通知权限校验。
- `resolveApprovalEligibleUserIds(...)` 适用于动态加签、多实例成员、单一 assignee、委派/转办目标和委派 owner 等直接办理路径。调用方应在任何 Flowable 写入前调用，并对不完整结果整批拒绝。
- `resolveClaimEligibleUserIds(...)` 适用于写入 `candidateUser` 或执行 claim 的候选认领路径。候选人除了能办理任务，还必须能访问待签列表并执行认领；调用方应对缺少任一权限的选择整批拒绝。
- `resolveClaimEligibleCandidateGroups(...)` 适用于动态表达式解析后的 `candidateGroup`。它会逐个过滤没有完整认领成员的角色或部门，调用方必须将返回集合与请求集合精确比较。
- `resolveActiveUserIds(...)` 只证明身份主数据有效，不证明用户具备 `approval` 或 `claim` 资格，不能单独作为任务分配授权依据。

## 公开方法

| 方法 | 入参 | 返回值 | 错误语义 |
| --- | --- | --- | --- |
| `resolveCurrentIdentity()` | 无；从 Spring Security 读取当前用户 ID | `WorkflowCurrentIdentity`，含规范用户 ID 和不可变候选组集合 | 当前用户不存在、停用或删除时为 `403`；身份格式非法为 `400`；主数据异常为 `500` |
| `resolveActiveUserIds(Collection<String> candidateUserIds, Collection<String> candidateGroups)` | 非 `null` 的直接候选用户集合和候选组集合；两个空集合合法 | `Set<String>`，去重、顺序稳定且不可修改的有效用户 ID | 集合为 `null` 或任一身份格式非法时为 `400`；Mapper 返回非法主键时为 `500` |
| `resolveApprovalEligibleUserIds(Collection<String> candidateUserIds)` | 非 `null` 的数字用户 ID 集合；空集合合法 | `Set<String>`，保持请求顺序且不可修改的实时审批资格用户 ID | 集合或身份格式非法时为 `400`；Mapper 返回非法主键时为 `500` |
| `resolveClaimEligibleUserIds(Collection<String> candidateUserIds)` | 非 `null` 的数字用户 ID 集合；空集合合法 | `Set<String>`，保持请求顺序且不可修改的实时认领资格用户 ID | 集合或身份格式非法时为 `400`；Mapper 返回非法主键时为 `500` |
| `resolveClaimEligibleCandidateGroups(Collection<String> candidateGroups)` | 非 `null` 的规范 `ROLE<id>` 或 `DEPT<id>` 集合；空集合合法 | `Set<String>`，保持请求顺序且不可修改、每组均有完整认领成员的候选组 | 集合或身份格式非法时为 `400`；Mapper 返回非法主键时为 `500` |

## 能力契约

| capability | 必须聚合的启用菜单权限 | 适用的任务身份 |
| --- | --- | --- |
| `approval` | `workflow:process:todoList`、`workflow:process:query`、`workflow:process:approval` | 直接 assignee、动态多实例成员、委派/转办目标和委派 owner |
| `claim` | `workflow:process:claimList`、`workflow:process:claim`，以及 `approval` 的全部三项权限 | `candidateUser` 和执行 claim 的当前用户 |

普通用户的权限按其全部启用、未删除、非管理员角色的权限并集计算，五项权限不要求来自同一角色；关联菜单本身也必须启用。只有用户 `1` 保持若依超级管理员语义，其他用户的异常 `role_id=1` 关联不会授予菜单权限。`claim` 是 `approval` 的严格超集：仅具备三项直接办理权限的用户可以成为 assignee，但不能被写为候选认领人，也不能认领任务。

## 身份格式

| 类型 | 格式 | 示例 |
| --- | --- | --- |
| 用户 | 正整数 `sys_user.user_id` 字符串 | `7` |
| 角色候选组 | `ROLE<roleId>`，无分隔符且区分大小写 | `ROLE2` |
| 部门候选组 | `DEPT<deptId>`，无分隔符且区分大小写 | `DEPT3` |

空值、空白、前导零、正负号、小数、零、负数、未知前缀和 `long` 溢出均整体拒绝，不做宽松兼容。

## 关键设计与权限约束

- `resolveCurrentIdentity()` 每次都重新查询 `sys_user.status = '0'` 且 `del_flag = '0'`，阻止用户被停用或删除后继续使用旧登录会话流转任务。
- 当前候选组只包含该用户仍有效的角色和当前有效部门；角色、部门自身停用或删除后不会进入结果。
- Adapter 认领任务时，仅将当前不可变身份中的用户 ID、`ROLE<id>` 和 `DEPT<id>` 与任务的 `CANDIDATE` IdentityLink 匹配，不能用旧令牌角色或前端传入组冒充 candidate。
- `resolveActiveUserIds(...)` 先严格解析全部候选身份，再访问数据库；任一格式非法时不返回部分授权结果。
- `resolveApprovalEligibleUserIds(...)` 与 `resolveClaimEligibleUserIds(...)` 均同时校验用户启用、未删除和实时 RBAC；前者要求完整三项直接办理权限，后者要求完整五项认领及后续办理权限。角色名和 `role_key` 不参与资格判断。
- 候选角色或部门是否可配置，由身份目录和 BPMN 校验链确认主数据有效且至少有一名有效成员具备完整 `claim` 资格；运行时动态组由 `resolveClaimEligibleCandidateGroups(...)` 逐组复核，不能用多个组展开后的用户并集掩盖失效组。
- 直接候选用户按输入首次出现顺序保留；角色和部门展开结果按 Mapper 稳定顺序追加；最终使用不可修改的去重集合。
- Mapper 结果为 `null`、包含 `null`、零或负数时视为正式主数据异常并以 `500` 停止处理，损坏身份不会交给 Flowable。
- 身份解析器自身不声明事务。任务 Adapter 和专用领域服务均由 `WorkflowEngineOperations` 在同一事务内调用；其他需要一致主数据视图的调用方应建立合适的只读事务。

## 最小接入示例

展开候选人用于通知前的最小示例：

```java
Set<String> recipientUserIds = identityResolver.resolveActiveUserIds(
        List.of("7", "11"),
        List.of("ROLE2", "DEPT3"));
```

动态任务创建前解析审批资格的最小示例：

```java
Set<String> eligibleUserIds = identityResolver.resolveApprovalEligibleUserIds(
        List.of("7", "11"));
```

写入 `candidateUser` 或认领前解析完整认领资格：

```java
Set<String> claimableUserIds = identityResolver.resolveClaimEligibleUserIds(
        List.of("7", "11"));
```

动态候选组写入任务前逐组解析完整认领资格：

```java
Set<String> claimableGroups = identityResolver.resolveClaimEligibleCandidateGroups(
        List.of("ROLE2", "DEPT3"));
```

任务认领不应先自行展开候选人，而应直接使用 Adapter，使身份复核、候选校验和引擎命令处于同一事务：

```java
processEngineAdapter.claimTaskForCurrentUser(taskId);
```
