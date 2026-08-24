# WorkflowMultiInstanceRoundTerminationService

## 作用

`WorkflowMultiInstanceRoundTerminationService` 只负责异常中断、流程取消和管理员终止下的开放轮次冻结、预检与 `TERMINATED` CAS。

## 使用方式

中断监听器传入不可变根取消或流程取消事件；`WorkflowProcessInstanceService` 在删除根流程前调用 `precheckTermination`，删除完成后调用 `terminatePrechecked`。计划包含本次流程树实例集合及按轮次主键索引的不可变开放轮次事实。

## 安全边界

受控整组 RETURN/REOPEN 的根取消必须先由窄观察协议确认，不能用通知变量放行。普通异常中断必须核对部署、实例、节点、根、成员、模式和 revision；管理员终止保留锁定及锁后快照复核。单行 CAS 或批量更新影响行数确认写入成功，不再回读全部轮次或重复 OPEN 查询。
