# WorkflowMultiInstanceTransitionCoordinator

## 作用

`WorkflowMultiInstanceTransitionCoordinator` 管理一次 Flowable 命令内的 RETURN/REOPEN 迁移协议。它把整组计划转换为私有不可变上下文，通过 `WorkflowMultiInstanceTransitionObserver` 向 Handler 和监听器暴露窄观察能力，并由无返回值完成命令统一检查观察链。

## 状态机

- RETURN：解析临时成员集合，观察原根取消；首审批为同一多实例节点时再观察唯一临时申请人根与任务。
- REOPEN：解析完整冻结成员，按来源结构观察申请人临时根取消，并观察唯一新根下完整成员任务数量。
- Scope 绑定单线程、单层级和单次关闭生命周期；任一身份、节点、根、操作人、成员、模式或 revision 漂移都会失败关闭。

`requireReturnCompleted` / `requireReopenCompleted` 正常返回即表示协议完整，观察缺失直接抛出既有异常；该异常或正常返回就是调用方的最终结果。

## ThreadLocal 边界

`ThreadLocal` 仅为 Coordinator 私有实现细节，参数对象、事务、线程和请求各自保持隔离。唯一持有 Scope 的 `WorkflowMultiInstanceGroupTransitionService` 使用 try-with-resources 保证所有退出路径调用 `remove()`。
