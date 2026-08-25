# WorkflowIdentityResolver

## 组件简介与作用

`WorkflowIdentityResolver` 通过模块自有 `WorkflowIdentityMapper` 读取若依正式主数据，将用户、角色和部门转换为 Flowable 可使用的规范身份。有效身份结果仅包含启用且有效的用户、角色和部门；创建真实办理人或候选认领人时，还必须分别通过 `approval` 或 `claim` 实时资格校验。

身份真源固定为 `sys_user`、`sys_role`、`sys_dept` 及正式关联表，解析结果直接供 Flowable 任务身份使用。

## 接入与使用方式

- 当前用户执行任务基础命令时调用 [WorkflowProcessEngineAdapter](../engine/WorkflowProcessEngineAdapter.md)。模型发布、流程发起、动态多实例和复杂生命周期等专用领域服务调用 `WorkflowEngineOperations.writeAsCurrentUser(...)`，在同一引擎写事务内解析身份并立即完成权限判断和命令。
- `resolveActiveUserIds(...)` 适用于候选人预览、通知接收人或业务校验等需要把 BPMN candidate 展开为有效若依用户的场景。调用方仍须根据具体业务做数据范围和通知权限校验。
- `resolveApprovalEligibleUserIds(...)` 适用于动态加签、多实例成员、单一 assignee、委派/转办目标和委派 owner 等直接办理路径。调用方在任何 Flowable 写入前调用，完整批次通过后继续。
- `resolveClaimEligibleUserIds(...)` 适用于写入 `candidateUser` 或执行 claim 的候选认领路径。候选人同时具备办理任务、访问待签列表和执行认领的权限；整批权限完整时继续。
- `resolveClaimEligibleCandidateGroups(...)` 适用于动态表达式解析后的 `candidateGroup`。它会逐个保留具备完整认领成员的角色或部门，调用方必须将返回集合与请求集合精确比较。
- `resolveActiveUserIds(...)` 证明身份主数据有效；任务分配继续组合 `approval` 或 `claim` 专用资格结果。

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

普通用户的权限按其全部启用、有效且属于普通角色的权限并集计算，五项权限可以来自不同角色；关联菜单本身也必须启用。用户 `1` 保持若依超级管理员语义，其他用户始终按普通角色集合授权。`claim` 是 `approval` 的严格超集：具备三项直接办理权限的用户可以成为 assignee，具备完整五项权限的用户还可以成为候选认领人并认领任务。

## 身份格式

| 类型 | 格式 | 示例 |
| --- | --- | --- |
| 用户 | 正整数 `sys_user.user_id` 字符串 | `7` |
| 角色候选组 | `ROLE<roleId>`，无分隔符且区分大小写 | `ROLE2` |
| 部门候选组 | `DEPT<deptId>`，无分隔符且区分大小写 | `DEPT3` |

身份编码使用正整数十进制用户主键或规范 `ROLE/DEPT` 前缀；其余格式整批返回稳定错误。

## 关键设计与权限约束

- `resolveCurrentIdentity()` 每次都重新查询 `sys_user.status = '0'` 且 `del_flag = '0'`；用户被停用或删除后，缓存登录会话的任务命令返回 `403`。
- 当前候选组仅包含该用户仍有效的角色和当前有效部门；停用或删除的角色、部门会在实时解析时过滤。
- Adapter 认领任务时，将当前不可变身份中的用户 ID、`ROLE<id>` 和 `DEPT<id>` 与任务的 `CANDIDATE` IdentityLink 精确匹配，候选事实始终来自事务内实时身份。
- `resolveActiveUserIds(...)` 先严格解析全部候选身份，再访问数据库；任一格式非法时，整批返回 `400`。
- `resolveApprovalEligibleUserIds(...)` 与 `resolveClaimEligibleUserIds(...)` 均同时校验用户启用、有效状态和实时 RBAC；前者要求完整三项直接办理权限，后者要求完整五项认领及后续办理权限。资格判断固定使用权限集合，与角色名和 `role_key` 解耦。
- 候选角色或部门的配置资格由身份目录和 BPMN 校验链确认：主数据有效且至少有一名有效成员具备完整 `claim` 资格；运行时动态组由 `resolveClaimEligibleCandidateGroups(...)` 逐组复核，每个组独立满足该条件。
- 直接候选用户按输入首次出现顺序保留；角色和部门展开结果按 Mapper 稳定顺序追加；最终使用不可修改的去重集合。
- Mapper 结果为 `null`、包含 `null`、零或负数时视为正式主数据异常并以 `500` 停止处理，只有规范身份会提交给 Flowable。
- 事务由调用方建立：任务 Adapter 和专用领域服务由 `WorkflowEngineOperations` 在同一事务内调用；其他需要一致主数据视图的调用方建立合适的只读事务。

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

任务认领直接使用 Adapter，使身份复核、候选校验和引擎命令处于同一事务：

```java
processEngineAdapter.claimTaskForCurrentUser(taskId);
```
