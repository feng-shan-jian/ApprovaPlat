# WorkflowProcessDraftService

## 作用

`WorkflowProcessDraftService` 负责本人申请草稿的创建、查询、CAS 保存、删除、附件对账和正式提交。`wf_process_draft` 保存当前状态与 revision，用户操作审计统一写入 Controller 对应的 `sys_oper_log`；该结构直接替换独立草稿流水。草稿正文、成员选择和附件均写入正式业务表，服务端数据库是唯一草稿状态来源。

## 创建与部署删除并发契约

创建草稿在统一 `REPEATABLE_READ` 写事务内按以下顺序执行：

1. 重新查询客户端选择的真实流程定义并取得 `deploymentId`。
2. 对 `ACT_RE_DEPLOYMENT.ID_` 执行 `SELECT ... FOR UPDATE`，确认部署仍存在并持锁到事务结束。
3. 在部署锁保护下读取不可变部署表单快照，校验草稿字段和发起成员。
4. 写入 `wf_process_draft` 当前状态和附件关系。

`WorkflowDeploymentService` 删除部署时使用同一个 Mapper 和同一部署主键先取得行锁，再检查 ACTIVE 草稿。锁顺序统一为 `ACT_RE_DEPLOYMENT` 后 `wf_process_draft`：

- 创建先取得锁并提交时，删除等待后通过草稿当前读发现 ACTIVE 引用并返回 409。
- 删除先取得锁并提交时，创建等待后发现部署行已消失，返回 `DRAFT_DEFINITION_UNAVAILABLE`，草稿和附件保持原状态。

## 状态约束

- `ACTIVE` 草稿允许本人编辑、删除或提交，并阻止其部署被删除。
- `SUBMITTED` 和 `DELETED` 是终态，部署删除引用检查只统计活动草稿。
- 创建锁失败、版本竞争、快照失效和附件状态变化均抛出稳定业务异常，由外层事务回滚全部副作用。

四个写入口都在 `writeAsCurrentUser(...)` 事务代理调用之外捕获异常，因此既覆盖事务回调内失败，也覆盖代理提交阶段才暴露的死锁、锁等待超时和乐观锁失败。新建草稿只补充 `DRAFT_CREATE_CONCURRENCY_CONFLICT`；保存、删除和提交已有草稿的 revision/CAS 并发失败使用 `DRAFT_VERSION_CONFLICT`。异常已经携带定义、附件、权限或其他领域 `subCode` 时原样透传，不会被草稿并发分类覆盖，也不会自动重试。

ACTIVE 草稿正式提交先普通读取本人草稿以取得不可变 `deploymentId`，再依次锁定
`ACT_RE_DEPLOYMENT` 和 `wf_process_draft`，并重新核验所有者、状态、版本、定义和部署关系。
部署删除采用同一首锁，提交和删除始终遵循 `ACT_RE_DEPLOYMENT -> wf_process_draft` 锁序。已提交草稿的
重复请求返回原实例并保持单一实例结果；该幂等读取只依赖已持久化提交记录。

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

## 稳定冲突与能力投影

| 子码 | 严格语义 |
| --- | --- |
| `DRAFT_CREATE_CONCURRENCY_CONFLICT` | 新建草稿事务的真实可重试并发失败 |
| `DRAFT_VERSION_CONFLICT` | 已有草稿 revision/CAS 冲突，不用于新建或附件冲突 |
| `DRAFT_STATE_CONFLICT` | 草稿已进入不允许当前写操作的终态 |
| `DRAFT_DEFINITION_VERSION_EXPIRED` | 草稿绑定定义已不是最新版 |
| `DRAFT_DEFINITION_UNAVAILABLE` | 草稿绑定定义已删除、挂起或当前不可启动 |
| `DRAFT_SNAPSHOT_MISMATCH` | 草稿冻结表单关系、正文或校验和漂移 |

列表和详情的 `editable`、`submittable`、`statusReason` 只消费草稿状态、业务状态码和稳定 `subCode`。`statusReason` 是展示文案，不参与服务端分支；定义异常消息文本改变不会改变能力投影。只读预览授权拒绝继续保持无业务子码的通用 `403`。

`WorkflowProcessStartChainIntegrationTest` 使用真实 Flowable 8、H2、正式业务 Mapper 和 Spring 事务验证旧 revision、定义/快照/附件失败回滚、提交 CAS 孤儿实例防护和重复提交幂等。`WorkflowProcessDraftStartMySqlIT` 仅在 `workflow-mysql-it` profile 和显式隔离 MySQL 8 环境下，通过第二事务持有 `ACT_RE_DEPLOYMENT` 行锁验证真实锁等待失败；普通 H2 测试不能替代该 MySQL 并发验收。
