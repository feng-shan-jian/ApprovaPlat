# WorkflowUserSelectionValidator

## 组件简介与作用

`WorkflowUserSelectionValidator` 是客户端提交用户集合进入 Flowable 写命令前的严格边界。它统一校验主键格式、数量、重复值、用户状态和实时 RBAC，只有当前可执行用户集合会进入正式任务写入。

该组件的请求格式固定为正数 `sys_user.user_id`；用户名、昵称和角色名由正式目录转换为主键后再提交。身份和权限真源由 `WorkflowIdentityResolver` 从若依正式主数据中解析。

## 接入与使用方式

- `requireActiveUserIds(...)` 适用于抄送、通知等只需证明用户存在且启用的场景；任务分配使用具备审批资格的专用校验入口。
- `requireApprovalEligibleUserIds(...)` 适用于直接 `assignee`、动态多实例成员、委派和转办目标。
- `requireClaimEligibleUserIds(...)` 适用于 `candidateUser` 或多人候选计划，要求用户能完整走通待签、认领、待办详情和审批。
- 调用位于 Flowable 写入之前，并对整批选择一次性校验；任一成员不合格时整批返回稳定错误。

## 公开方法

| 方法 | 入参 | 返回值 | 适用资格 |
| --- | --- | --- | --- |
| `requireActiveUserIds(List<Long>)` | 用户主键列表；`null` 或空列表合法 | 保持请求顺序的不可变规范用户 ID 列表 | 用户启用且未删除 |
| `requireApprovalEligibleUserIds(List<Long>)` | 直接办理用户主键列表 | 全部具备 `approval` 资格的不可变列表 | `todoList` + `query` + `approval` |
| `requireClaimEligibleUserIds(List<Long>)` | 候选认领用户主键列表 | 全部具备 `claim` 资格的不可变列表 | `claimList` + `claim` + 全部 `approval` 权限 |

## 参数与异常约束

- 单次最多选择 `100` 人；主键必须非空、大于零且保持唯一。
- 返回顺序与请求首次出现顺序一致，结果使用不可变列表。
- 格式、重复或数量非法返回 `400`。任一用户不存在、停用、删除或缺少相应资格时整批返回 `400`。
- 组件对外使用稳定通用消息，具体角色、菜单和失效账号细节仅写入受控服务端诊断信息。

## 关键设计

`approval` 和 `claim` 是不同的正式能力。多实例或单人后继任务会直接写入 `assignee`，使用 `approval`；普通后继任务选择多人时会生成候选用户，必须改用更严格的 `claim`。每次写命令前都从正式主数据实时重查权限，前端目录仅提供选择体验。

## 最小接入示例

```java
List<String> assigneeIds = userSelectionValidator
        .requireApprovalEligibleUserIds(requestedUserIds);

List<String> candidateUserIds = userSelectionValidator
        .requireClaimEligibleUserIds(requestedUserIds);
```
