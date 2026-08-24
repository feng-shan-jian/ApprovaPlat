# WorkflowMultiInstanceTransitionCoordinator

## 作用

`WorkflowMultiInstanceTransitionCoordinator` 管理一次 Flowable 命令内的 RETURN/REOPEN 迁移协议。它把整组计划转换为私有不可变上下文，通过 `WorkflowMultiInstanceTransitionObserver` 向 Handler 和监听器暴露窄观察能力，最后汇总为 `MultiInstanceTransitionResult`。

## 状态机

- RETURN：解析临时成员集合，观察原根取消；首审批为同一多实例节点时再观察唯一临时申请人根与任务。
- REOPEN：解析完整冻结成员，按来源结构观察旧临时根取消，并观察唯一新根下完整成员任务数量。
- Scope 禁止嵌套、跨线程、重复关闭或错误复用；任一身份、节点、根、操作人、成员、模式或 revision 漂移都会失败关闭。

## ThreadLocal 边界

`ThreadLocal` 仅为 Coordinator 私有实现细节，不进入参数对象，不跨事务、线程或请求传递。唯一持有 Scope 的 `WorkflowMultiInstanceGroupTransitionService` 使用 try-with-resources 保证所有退出路径调用 `remove()`。
