# WorkflowUserTaskListener

## 作用

`WorkflowUserTaskListener` 是 BPMN `delegateExpression="${userTaskListener}"` 的唯一受控 Spring Bean。它负责事件白名单和固定领域服务编排：`create` 先由 `WorkflowParticipantRuleRuntimeService` 按部署快照及实时组织解析任务参与者，再把不可变 `WorkflowTaskEventSnapshot` 交给 `WorkflowMultiInstanceRoundLifecycleService` 创建、核对或跳过受控多实例正式轮次，最后由 `WorkflowUserTaskAuditService` 校验最终身份并记录任务审计。

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
| `create` | 普通进入时按部署规则和实时有效组织解析；受控多实例创建首轮或核对同根复用；RETURN 临时申请人任务不登记正式轮次，REOPEN 只按旧轮冻结快照创建下一轮；最后校验身份并写创建审计 |
| `assignment` | 校验变更后的 assignee/owner（如有）并写分配审计，不读取或改写多实例轮次 |
| `complete` | 受控多实例核验 task-local 预留 revision 和真实根计数，必要时关闭轮次；随后校验身份并写完成审计 |

监听器自身不解析作者 BPMN、不执行表达式或用户内容。参与者服务只读取部署快照、`initiator` 和规则明确选择的表单用户字段，访问正式 `sys_user/sys_role/sys_dept`，不调用外部网络，也不写 `processStatus` 或任何业务终态。

整组退回和重提期间，监听链只通过窄化的 `WorkflowMultiInstanceTransitionObserver` 观察 `WorkflowMultiInstanceTransitionCoordinator` 在当前 Flowable 命令内绑定的轮次、根 execution、activity、来源任务、操作人、完整有序成员、ALL/ANY 模式和 revision。通知变量中的 `RETURN`/`RESUBMIT` 仅用于通知抑制，不是创建或中断轮次的授权依据。首审批节点本身就是受控多实例节点时，退回产生的单成员申请人根必须携带与协议一致的局部标记且跳过 `ACTIVE` 轮次登记；重提创建的新根必须具有新 root execution，并按冻结快照登记 `round_no + 1` 的 `ACTIVE` 轮次。

## 失败边界

未知事件或缺失 `DelegateTask` 会立即抛出 Flowable 参数异常并回滚当前引擎命令。领域身份、轮次关联、CAS 或 comment 写入失败也由同一事务回滚，不能留下任务、execution、变量和业务轮次的半状态。

## 最小接入示例

部署前由 BPMN 安全校验确认 listener 的 `event` 和 `delegateExpression` 精确匹配上述白名单。运行时无需向 Bean 注入任何字段，也不能通过流程变量选择方法或动作。
