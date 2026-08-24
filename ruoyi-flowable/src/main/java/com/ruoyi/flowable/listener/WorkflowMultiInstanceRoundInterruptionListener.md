# WorkflowMultiInstanceRoundInterruptionListener

## 作用

`WorkflowMultiInstanceRoundInterruptionListener` 是 Flowable 全局同步事件监听器，补齐用户任务
`complete` 和业务显式终止链之外的原生执行树中断。它通过
`WorkflowMultiInstanceRoundInterruptionConfiguration` 追加到既有全局监听器集合，不覆盖自然
完成状态监听器或 Flowable 自动配置监听器。

## 事件与判定

- `ACTIVITY_CANCELLED`、`MULTI_INSTANCE_ACTIVITY_CANCELLED`：从当前 `CommandContext` 读取
  `ExecutionEntity`，仅 `isMultiInstanceRoot=true` 时交给轮次服务。正常 ANY 完成删除的是
  sibling child，根判定为 false，因此不会误写 `TERMINATED`。如果当前根取消来自整组退回或
  重提，轮次服务只有在严格迁移协议和数据库正式轮次完全一致时才跳过异常终止 CAS。
- `PROCESS_CANCELLED`：仅处理 `isProcessInstanceType=true` 且 `isDeleted=true` 的内部流程实例
  取消，用于关闭 CallActivity/作用域中断后残余的 `RETURNED` 等开放轮次。业务显式
  `deleteProcessInstance` 派发事件时实例尚未标记 deleted，仍由既有
  `precheckTermination/terminatePrechecked` 链关闭，不会双写。
- 非中断边界不删除原 execution，也不会满足上述事件条件，轮次保持 `ACTIVE`。

全局 Flowable 也会为未受控的原生多实例 CallActivity、SubProcess 等派发取消事件。监听器和
轮次服务会先按 activity 类型与部署模型识别受控多实例 UserTask；非 UserTask 或未使用受控
handler 的节点直接保持原生引擎语义。

## 受控迁移协议

整组退回和重提不使用通知变量中的宽泛 `RETURN`/`RESUBMIT` 字符串作为中断放行条件。
`WorkflowMultiInstanceTransitionCoordinator` 在当前线程和当前 Flowable 命令内绑定一个不可嵌套的
`Scope`，上下文包含动作、轮次、部署、定义、实例、原轮次根、本次来源 execution、来源和目标
activity、来源任务、操作人、申请人任务、完整有序成员、ALL/ANY 模式和 revision。

监听器把不可变取消事件交给 `WorkflowMultiInstanceRoundTerminationService`；终止服务还会读取 `round_id` 对应正式行，逐项核对轮次身份、状态、冻结
快照、事件根和 Flowable 流程变量：RETURN 只允许原 `ACTIVE` 根迁出；REOPEN 只允许已经 CAS 为
`REOPENED` 的旧轮次所绑定申请人来源根迁出。任何字段漂移、重复取消或操作人不一致都会抛错，
不能用同名流程变量或另一轮次的 token 放行。

任务创建监听链同时参与协议闭环：首审批节点就是当前多实例节点时，RETURN 创建的临时单成员
申请人根不登记 `ACTIVE` 轮次；REOPEN 创建的新根则必须登记下一轮，并观察同一新根下全部冻结
成员任务。生命周期写链在迁移后校验取消、集合解析、临时任务或完整重建均已发生。`Scope` 使用
try-with-resources，在正常完成和异常回滚路径都会删除线程标记，禁止影响后续命令。

## 事务和失败边界

监听器继承 Flowable 的 fail-on-exception 语义。轮次服务先核对引擎、部署模型和正式快照，再以
`round_id + expected revision + source status` 单行 CAS 为 `TERMINATED`，生命周期时间由数据库
生成。任何缺行、字段漂移、非法生命周期或写后残留返回服务端数据异常；CAS 影响数不为一返回
`409`。受控迁移还要求上层完成 RETURNED/REOPENED CAS 和写后对账；异常会回滚同一命令内的
边界事件、任务、execution、变量、表单附件、抄送、审计、通知和业务轮次。
