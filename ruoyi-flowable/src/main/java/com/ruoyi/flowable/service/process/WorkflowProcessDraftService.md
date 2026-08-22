# WorkflowProcessDraftService

## 作用

`WorkflowProcessDraftService` 负责本人申请草稿的创建、查询、CAS 保存、删除、附件对账和正式提交。`wf_process_draft` 只保存当前状态与 revision，用户操作审计复用 Controller 上的 `sys_oper_log`，不再持久化无读取入口的草稿流水。草稿正文、成员选择和附件均写入正式业务表，不使用浏览器本地状态。

## 创建与部署删除并发契约

创建草稿在统一 `REPEATABLE_READ` 写事务内按以下顺序执行：

1. 重新查询客户端选择的真实流程定义并取得 `deploymentId`。
2. 对 `ACT_RE_DEPLOYMENT.ID_` 执行 `SELECT ... FOR UPDATE`，确认部署仍存在并持锁到事务结束。
3. 在部署锁保护下读取不可变部署表单快照，校验草稿字段和发起成员。
4. 写入 `wf_process_draft` 当前状态和附件关系。

`WorkflowDeploymentService` 删除部署时使用同一个 Mapper 和同一部署主键先取得行锁，再检查 ACTIVE 草稿。锁顺序统一为 `ACT_RE_DEPLOYMENT` 后 `wf_process_draft`：

- 创建先取得锁并提交时，删除等待后通过草稿当前读发现 ACTIVE 引用并返回 409。
- 删除先取得锁并提交时，创建等待后无法再次取得部署行，返回 `DRAFT_DEFINITION_UNAVAILABLE`，不会写入草稿或附件。

## 状态约束

- `ACTIVE` 草稿允许本人编辑、删除或提交，并阻止其部署被删除。
- `SUBMITTED` 和 `DELETED` 是终态，不再阻止部署删除。
- 创建锁失败、版本竞争、快照失效和附件状态变化均抛出稳定业务异常，由外层事务回滚全部副作用。

ACTIVE 草稿正式提交先普通读取本人草稿以取得不可变 `deploymentId`，再依次锁定
`ACT_RE_DEPLOYMENT` 和 `wf_process_draft`，并重新核验所有者、状态、版本、定义和部署关系。
部署删除采用同一首锁，因此不会出现提交持有草稿锁、删除持有部署锁的反向等待。已提交草稿的
重复请求只返回原实例，不再产生实例，也不要求已经允许删除的部署仍然存在。

## 正式提交单事务链

`submit(...)` 是草稿提交唯一的 `writeAsCurrentUser` 事务入口，锁和状态迁移顺序固定为：

```text
ACT_RE_DEPLOYMENT
  -> wf_process_draft
  -> wf_attachment
  -> Flowable 实例创建
  -> 草稿 SUBMITTED
```

部署行、草稿行和本次草稿附件都只锁定一次。提交入口只规范化一次 `businessKey` 和多实例人员选择；包级 `WorkflowProcessStartService.startDraft(...)` 只执行一次 `validateForStart`，同一份规范化变量同时用于引擎写入和 `markSubmitted`。引擎创建后的 `DRAFT -> BOUND` 与草稿 CAS 更新仍在同一事务内，任何异常都会回滚实例、草稿和附件。处于 `SUBMITTED` 的重复请求在部署锁之前直接返回原实例。
