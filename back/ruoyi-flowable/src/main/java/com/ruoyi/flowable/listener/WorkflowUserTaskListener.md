# WorkflowUserTaskListener

## 作用

`WorkflowUserTaskListener` 是 BPMN `delegateExpression="${userTaskListener}"` 的唯一受控 Spring Bean。它负责事件白名单和固定领域服务编排：`create` 先由 `WorkflowParticipantRuleRuntimeService` 按部署快照及实时组织解析单实例任务参与者，再由 `WorkflowUserTaskAuditService` 校验最终身份并记录任务审计。

## BPMN 使用方式

```xml
<flowable:taskListener event="create"
                       delegateExpression="${userTaskListener}" />
<flowable:taskListener event="assignment"
                       delegateExpression="${userTaskListener}" />
<flowable:taskListener event="complete"
                       delegateExpression="${userTaskListener}" />
```

只允许 `create`、`assignment` 和 `complete`。`delete`、`update`、`timeout`、`all`、任意 class/expression、脚本及字段注入均不属于该 Bean 的运行契约。

## 事件语义

| 事件 | 行为 |
| --- | --- |
| `create` | 动态或静态规则按实时有效组织解析，校验最终 assignee/候选身份并写解析与创建审计 |
| `assignment` | 校验变更后的 assignee/owner（如有）并写分配审计 |
| `complete` | 校验最终 assignee/owner，要求存在 Flowable 当前认证操作人并写完成审计 |

监听器自身不解析作者 BPMN、不执行表达式或用户内容。参与者服务只读取部署快照、`initiator` 和规则明确选择的表单用户字段，访问正式 `sys_user/sys_role/sys_dept`，不调用外部网络，也不写 `processStatus` 或任何业务终态。

## 失败边界

未知事件或缺失 `DelegateTask` 会立即抛出 Flowable 参数异常并回滚当前引擎命令。领域身份、关联或 comment 写入失败也由同一事务回滚，不能留下无审计的任务状态变化。

## 最小接入示例

部署前由 BPMN 安全校验确认 listener 的 `event` 和 `delegateExpression` 精确匹配上述白名单。运行时无需向 Bean 注入任何字段，也不能通过流程变量选择方法或动作。
