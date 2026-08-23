# WorkflowMultiInstanceRoundInterruptionListener

## 作用

`WorkflowMultiInstanceRoundInterruptionListener` 是 Flowable 全局同步事件监听器，补齐用户任务
`complete` 和业务显式终止链之外的原生执行树中断。它通过
`WorkflowMultiInstanceRoundInterruptionConfiguration` 追加到既有全局监听器集合，不覆盖自然
完成状态监听器或 Flowable 自动配置监听器。

## 事件与判定

- `ACTIVITY_CANCELLED`、`MULTI_INSTANCE_ACTIVITY_CANCELLED`：从当前 `CommandContext` 读取
  `ExecutionEntity`，仅 `isMultiInstanceRoot=true` 时交给轮次服务。正常 ANY 完成删除的是
  sibling child，根判定为 false，因此不会误写 `TERMINATED`。
- `PROCESS_CANCELLED`：仅处理 `isProcessInstanceType=true` 且 `isDeleted=true` 的内部流程实例
  取消，用于关闭 CallActivity/作用域中断后残余的 `RETURNED` 等开放轮次。业务显式
  `deleteProcessInstance` 派发事件时实例尚未标记 deleted，仍由既有
  `precheckTermination/terminatePrechecked` 链关闭，不会双写。
- 非中断边界不删除原 execution，也不会满足上述事件条件，轮次保持 `ACTIVE`。

全局 Flowable 也会为未受控的原生多实例 CallActivity、SubProcess 等派发取消事件。监听器和
轮次服务会先按 activity 类型与部署模型识别受控多实例 UserTask；非 UserTask 或未使用受控
handler 的节点直接保持原生引擎语义。

## 事务和失败边界

监听器继承 Flowable 的 fail-on-exception 语义。轮次服务先核对引擎、部署模型和正式快照，再以
`round_id + expected revision + source status` 单行 CAS 为 `TERMINATED`，生命周期时间由数据库
生成。任何缺行、字段漂移、非法生命周期或写后残留返回服务端数据异常；CAS 影响数不为一返回
`409`。异常会回滚同一命令内的边界事件、任务、execution、变量和业务轮次。
