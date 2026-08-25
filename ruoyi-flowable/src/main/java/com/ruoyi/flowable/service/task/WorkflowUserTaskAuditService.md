# WorkflowUserTaskAuditService

## 作用

`WorkflowUserTaskAuditService` 是用户任务监听的领域边界。它使用若依正式用户、角色和菜单主数据核验 assignee/owner 的实时流程办理资格；对未分配任务还读取 Flowable candidate identity links，核验动态候选用户和候选组的完整认领资格。全部门禁通过后，通过 `TaskService.addComment(...)` 在当前引擎事务中保存结构化审计。

## 使用方式

该服务是 `WorkflowUserTaskListener` 的内部领域依赖，Controller 和客户端通过正式任务应用服务访问业务能力：

```java
auditService.recordAudit(
        "assignment",
        "task-42",
        "instance-21",
        "expense:3:12001",
        "approveTask",
        "7",
        "8");
```

## 身份约束

- assignee 和 owner 可未设置；一旦设置，必须是无前导零、空白或符号的规范正整数若依用户 ID。
- 两者在一次正式身份查询中去重校验，用户必须同时满足 `sys_user.status='0'`、`del_flag='0'`，并实时具备待办列表、任务详情和审批三项直接办理权限。
- 唯一例外是服务端“退回”动作写入受控任务局部变量后，将首审批任务临时分配给原发起人；此时原发起人只需保持有效，执行退回的操作人仍必须具备完整审批资格。重新提交会先删除该内部标记，再按普通审批规则恢复首审批办理配置。
- 任一用户不存在、停用、删除或办理权限已撤销时整次事件失败，comment 和任务状态随事务回滚到原值。
- `complete` 必须存在 Flowable 当前认证操作人，且该操作人会与 assignee/owner 一并重新核验为当前具备流程办理权限的用户；create/assignment 的引擎系统事件允许操作人为空。
- `create` 事件的 assignee 为空时，必须至少存在一个 candidate identity link；每个 `candidateUser` 均须具备 `claimList`、`claim` 及三项直接办理权限。
- 每个 `candidateGroup` 使用精确的 `ROLE<id>` 或 `DEPT<id>` 规范编码，且对应角色或部门至少有一名有效成员具备完整五项认领权限；多个组逐组独立通过校验。

## 审计结构

comment 类型固定为 `USER_TASK_LISTENER`，JSON schema 版本为 `1`。正文只包含：

- 固定 `action` 与批准 `event`；
- task、process instance、process definition 和 BPMN task key；
- 可选 actor、assignee 和 owner 的规范用户 ID。

正文使用固定审计字段白名单；表单值、流程变量、Token、密码、自由意见和可执行内容留在各自业务边界。comment 自身的时间、作者和引擎关联字段由 Flowable 正式历史表维护。

## 状态与事务

服务通过 `TaskService` 读取当前任务 identity links、受控退回任务局部变量并写入 comment。`processStatus` 由业务状态服务独占维护，returned、canceled、rejected、terminated 等显式状态保持原语义。Flowable 8 在执行 `create` 监听前已写入 candidate identity links；监听校验和 comment 使用同一个 Flowable/Spring 命令事务，任何异常都会回滚任务事件。

## 异常语义

- `400`：事件超出白名单，assignee/owner 非法或失去办理资格，或动态候选为空、非规范、停用、删除、缺少完整认领权限。
- `500`：任务关联主键缺失、认证完成操作人缺失、identity link 查询异常或身份主数据返回不一致。主数据异常保持服务端 `500` 分类。
