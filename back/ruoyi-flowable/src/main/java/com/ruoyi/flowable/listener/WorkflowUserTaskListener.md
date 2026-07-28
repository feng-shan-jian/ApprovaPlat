# WorkflowUserTaskListener

## 作用

`WorkflowUserTaskListener` 是 BPMN `delegateExpression="${userTaskListener}"` 的唯一受控 Spring Bean。它只负责事件白名单和任务固有元数据转发，所有身份校验与审计写入由 `WorkflowUserTaskAuditService` 完成。

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
| `create` | 校验初始 assignee/owner（如有）并写创建审计 |
| `assignment` | 校验变更后的 assignee/owner（如有）并写分配审计 |
| `complete` | 校验最终 assignee/owner，要求存在 Flowable 当前认证操作人并写完成审计 |

监听器不读取流程变量或 BPMN 自定义字段，不访问 Spring 容器、不调用外部网络、不执行用户内容，也不写 `processStatus` 或任何业务终态。

## 失败边界

未知事件或缺失 `DelegateTask` 会立即抛出 Flowable 参数异常并回滚当前引擎命令。领域身份、关联或 comment 写入失败也由同一事务回滚，不能留下无审计的任务状态变化。

## 最小接入示例

部署前由 BPMN 安全校验确认 listener 的 `event` 和 `delegateExpression` 精确匹配上述白名单。运行时无需向 Bean 注入任何字段，也不能通过流程变量选择方法或动作。
