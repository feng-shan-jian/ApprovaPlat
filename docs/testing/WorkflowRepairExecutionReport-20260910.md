# 工作流修复与完整开发验收记录

日期：2026-09-10 至 2026-09-11，Asia/Shanghai。对应 [T01–T13 任务单](WorkflowDeepReviewAndRepairTasks-20260910.md)。用户已明确要求完整修复并授权登录开发环境；原审查文档的只读说明不限制本轮实施。

本报告替换上一版阶段性交付记录。T09 已完成可靠消息运行实现，T11 已消费原等待实例，T12 已走通登录后决策表业务路径，T13 已补齐多人竞争、重启、父子回滚、事务取消及真实 HTTP 补偿。改动保持未暂存、未提交、未推送。

## 任务验收结论

| 任务 | 状态 | 修复及验收事实 |
| --- | --- | --- |
| T01 结构化诊断 | passed | 返回 code、severity、elementId、message、processId；保留原四参数构造方式。原 graphics 定位到 DataObjectReference_0aevb5m，原 throws 定位到 Event_13trwmt；登录后点击诊断选中原图元。未知元素不可点击，诊断操作不修改作者 XML。 |
| T02 边界原子更新 | passed | 输入在写入前捕获，根定义、引用、中断语义进入同一可撤销命令。真实 bpmn-js 验证 ERROR/ESCALATION、定时表达式及消息/信号引用往返；实际 ERROR 精确捕获和非中断 ESCALATION 两路审批完成。 |
| T03 模板治理 | passed，采用新版本恢复 | qweqw 正式模板权限修复已执行；无效选项明确原因，绑定失败保留原配置。eqwe1 发布合法 V3，qweqwe12123 发布合法 V2，正式模板发起后均完成。三份旧部署快照保持不可变并隔离，存量实例处置单列如下。 |
| T04 调用目录隔离 | passed | 健康、坏快照、挂起及越权目录的分界由服务端控制；坏版本不再拖垮整批目录，系统性异常继续抛出。真实页面可选择健康子流程，固定/最新策略均经过父子实例及冻结版本核验。 |
| T05 注册表读取链 | passed | MyBatis 使用 primitive `_boolean`，真实 MySQL 4 项查询通过。页面注册表和已安装处理器分别加载；浏览器单独注入注册表失败时，处理器仍可查看。运行异常响应带定位编号，不包含 Java/SQL 细节。 |
| T06 数据对象 | passed | 提供语义变量名、类型和默认值，拒绝保留名称、非法类型/值及同作用域重名。编译各阶段保留原数据引用、关联、property、协作容器身份及 DI；原 graphics 全流程完成，MySQL 核对实际变量。 |
| T07 多池创作与隔离 | passed | 第二池可编辑本地 Process、发起范围、执行标志及开始表单；省略 executable 与引擎语义一致。原双池模型正式部署、分别发起，经原 MessageFlow 可靠投递后双方完成。A/B 字段及同名异构字段不串入目录。 |
| T08 调用权限 | passed | 后端消费本次保存/部署的权限化表单；同名字段权限取交集、类型冲突拒绝。直接提交隐藏输入、只读输出被拒绝且作者修订不变；保存后模板类型变化使部署整体拒绝，恢复模板后相同作者可部署。实际子 PROCESS_STARTED 失败时父子和草稿提交整笔回滚。 |
| T09 消息抛出 A/B | passed | 原消息抛出节点保留受控作者配置，执行副本以相同节点 ID 编译为事务 outbox。独立消息和原跨池消息使用各自固定服务端协议，目标、变量及端点被冻结。唯一目标消费、暂不可消费自动重试、重复幂等、冲突、失败审计和发送事务回滚均有证据。 |
| T10 执行器与定时恢复 | passed | 就绪依据实际普通执行器快照，默认环境不开启；本机显式使用 timers profile。两条原到期作业正式恢复，原实例全部完成。完整原 V1 结构的 PT5S 跨 JVM 重启自动续行；实际 HTTP 503 经普通执行器自动重试成功。 |
| T11 受控事件联调 | passed | 等待详情和联调面板复用正式 Token 事件接口。原 RECEIVE、MESSAGE、后续 SIGNAL 已消费并完成；验证相同请求重放、内容冲突、错误 Token、变量、事件范围、歧义和挂起拒绝。消息/信号网关两种胜出均取消另一分支。 |
| T12 业务决策表 | passed | UI 创建金额决策、试算、发布、绑定 BusinessRuleTask、保存部署，并从 UI 分别发起 500/2000，走不同审批路由后完成。新决策发布不改变旧流程冻结版本；新流程重新部署才引用新决策。零命中、UNIQUE 重叠、FIRST、非法类型、权限和引用删除门禁通过。 |
| T13 综合回归 | passed，开发环境范围 | 原完整流程三人会签、两人或签竞争、并行/包容汇聚及定时恢复完成；补充真实父子调用、两种事件子流程、精确错误/升级、事务取消及 HTTP 补偿。普通发起、撤回、退回/重提、终止和取消的正式回归通过。 |

没有把预期拒绝记为测试失败；没有把早期失败尝试记为运行通过。下文列出的旧版本隔离、图形存储边界和外部服务环境边界属于明确的处置/能力范围。

## 运行证据与覆盖层次

完整记录：[执行账本](evidence/workflow-repair-20260911/execution-ledger.json)、[代表性完整链路及精确标识](evidence/workflow-repair-20260911/coverage.json)、[清理前 MySQL 事实](evidence/workflow-repair-20260911/database-before-cleanup.jsonl)。账本 ISO 时间为 UTC，数据库时间为 Asia/Shanghai。

| 能力 | 可见及实际交互 | 创建、保存、部署 | 运行及终态 |
| --- | --- | --- | --- |
| 诊断、数据对象、边界、多池 | 登录页面及真实浏览器控件；命令栈专项回归 | 原始报告 XML 导入/正式模型 API 创建，业务控件修改后保存重开并部署 | MySQL 变量、历史活动、事件审计及终态交叉核对 |
| 调用活动 | 页面加载真实目录、回读固定引用与映射 | 正式模型保存/部署接口；模板权限拒绝直接提交 | 正式 PROCESS_STARTED、父子关系、冻结定义和 out 参数核对 |
| 可靠消息 | 原事件保留，设计器受控发送配置可编辑 | 作者事件编译为固定处理器，同 ID 可追踪 | 出站/入站台账、逐次审计、订阅消费及下游完成 |
| RECEIVE / MESSAGE / SIGNAL | 登录后联调面板填写目标及凭据，界面显示消费结果 | 使用正式受控接口与集成凭据 | 原等待实例恢复；负向矩阵和事件网关通过正式 API 验证 |
| DMN | UI 创建、试算、发布、绑定；UI 发起两组金额 | 后端独立校验及精确决策冻结 | BusinessRuleTask 实际执行、分支和终态核对 |
| 多人、回滚、补偿与重试 | 采用原完整模型/独立边界夹具 | 正式模型、草稿、提交、领取和审批接口 | 真实 Flowable/MySQL、并发请求及独立 HTTP 服务状态 |

浏览器专项回归使用生产组件、真实 Modeler 和命令栈，目录数据为夹具；它没有替代登录后验收。完整矩阵中批量审批、负向请求和并发竞争采用正式 API，不将这些操作说成逐个鼠标点击。工作流标识、业务键、版本、开始/完成时间可在覆盖账本查到。

关键截图：

- [原数据对象诊断及流程归属](evidence/workflow-repair-20260911/diagnostic-report-DataObjectReference_0aevb5m.png)、[原消息抛出诊断](evidence/workflow-repair-20260911/diagnostic-report-Event_13trwmt.png)。
- [原数据对象界面回读](evidence/workflow-repair-20260911/original-data.png)、[真实子流程目录与固定版本](evidence/workflow-repair-20260911/call-mapping.png)。
- [DMN 试算](evidence/workflow-repair-20260911/dmn-trial.png)、[节点绑定](evidence/workflow-repair-20260911/dmn-bound.png)、[审批完成](evidence/workflow-repair-20260911/dmn-completed.png)。
- [原 RECEIVE 消费](evidence/workflow-repair-20260911/original-receive.png)、[原 SIGNAL 消费](evidence/workflow-repair-20260911/original-signal.png)。
- [注册表正常加载](evidence/workflow-repair-20260911/registry.png)、[注册表失败时处理器仍可查看](evidence/workflow-repair-20260911/registry-isolated-failure.png)。

### 原实例恢复

以下均为原报告实例，保留历史，没有删除后重建或直接写终态：

| 原场景 | 实例 ID | 本轮结果 |
| --- | --- | --- |
| RECEIVE Activity_0k93idv | 5019d008-ac61-11f1-8ac1-005056c00001 | UI 请求 7f2e53a3-73d3-4452-b1de-bc4fb9d36529 消费，09-11 08:24:31 完成 |
| MESSAGE / 后续 SIGNAL | 54cefe9d-ac62-11f1-8ac1-005056c00001 | 可靠消息 4969ea71-9c4e-3fb8-8945-aabc9c219b87 消费；SIGNAL 请求 c1001525-4d1f-4884-bea7-02958a9a6458；08:24:45 完成 |
| 原 B 定时器 | 859fbc04-ac5d-11f1-8ac1-005056c00001 | 到期作业由普通执行器于 09-10 19:59:47 消费，继续审批，09-11 08:24:31 完成 |
| 原 U1 定时器 | 00ad967d-abe6-11f1-9cc9-005056c00001 | 同次恢复正式调度，09-11 08:24:46 完成 |

全新原完整结构实例 `28ca4039-ad78-11f1-93ff-005056c00001`：三人会签依次完成时仍等待余下成员；两人或签并发结果为一个成功、一个拒绝，只有一组下游。PT5S 于 08:38:31 建立、08:38:36 到期，期间关闭 JVM，08:38:56 恢复后由执行器消费；08:39:34 全流程完成。没有使用 executeJob/trigger 代替该重启验收。

### 消息与跨池

原 `Event_13trwmt` 在作者 XML 中仍为消息抛出，执行副本改为受控任务，使用同一节点 ID。新增固定 `/workflow/runtime-event/message/reliable` 入口，原 `/workflow/runtime-event/collaboration/message` 继续检查 Participant/MessageFlow 声明，客户端不能传入模式来放宽原入口。

- 消息 `4969ea71-9c4e-3fb8-8945-aabc9c219b87`：出站/入站均 PROCESSED，唯一消费原消息实例。
- 消息 `50b7dc2d-251a-39df-b533-cacf2caa2d55`：原抛出节点产生 outbox；接收方暂未存在，入站自动 RETRYING → 第 2 次 PROCESSED，随后双方完成。
- 原双池消息 `85d08eb0-8507-3a4b-bff4-db7d37157012`：原 MessageFlow `Flow_1yc3g2l` 与发送节点 `Activity_10q0xm0` 保留，发送实例 `0ee41510-ad7a-11f1-ac0a-005056c00001`、接收实例 `0ed60b40-ad7a-11f1-ac0a-005056c00001` 均完成。
- 同名无关等待实例没有被消费；相同 messageId 重放、改变内容冲突、发送事务失败时 outbox/序号/审计/实例一起回滚均有专项证据。

早期把独立流程消息配置到跨池入口，入站正确进入 DEAD_LETTER / COLLAB_MESSAGE_FLOW_NOT_DECLARED；此失败不作为成功证据。最终增加明确的独立消息模式，并修正数据库通道唯一索引，使两种协议各自分配序号。

### 调用、变量与事务

父正式模板 `field102=7` 经原生 in 映射进入真实子流程，子审批前快照为 `amount=7`，服务执行后父实际变量 `field102=42`（LONG）。发布子 V2 后，已部署固定策略和“发布时最新版”父流程仍使用冻结 V1；重新部署最新版策略才调用子 V2，并写回 `84`。父审批与子审批分别完成。

子开始表单要求 amount ≥ 100 时，父输入 7 的正式草稿提交在子 PROCESS_STARTED 校验阶段拒绝。MySQL 同时核对父子定义的运行和历史实例均为 0，草稿仍 ACTIVE、revisionNo=1、可编辑，未提交。没有直接写快照或 processStatus。

原 graphics 实例 `e1a28b50-ad79-11f1-ac0a-005056c00001` 完成后，MySQL 记录根作用域 `applicationData=DATA_UI_CONFIRMED`（STRING）、`probe_result=SERVICE_OK`、`comp_result=COMP_OK`。标准关联和图形保留；数据存储图元、数据关联本身不执行数据库读写。

### 事务取消与外部副作用

真实 MySQL/HTTP 首次验收暴露 Flowable 8 原生取消边界的生命周期缺陷：补偿排入 agenda 后父快照执行过早删除，异步补偿引用已删除父执行，触发外键异常。新增 `WorkflowCompensationLifecycle` 及配置接入，只适配事务取消补偿路径：保留快照与等待执行，所有补偿分支成功后离开取消边界；失败继续使用原生作业重试。其他行为调用原实现。

正式回归覆盖同步/异步两种情况，每种包含两个已完成业务动作与两个补偿分支；其中一个异步补偿先失败，另一分支完成后仍不提前离开边界，重试完成才推进。

修复后实际实例 `04b6c57e-ad7d-11f1-a718-005056c00001`：HTTP reserve 于 09:06:28.831 执行一次，compensate 于 09:06:28.891 执行一次，外部状态 COMPENSATED 后才到取消后审批，09:06:29.631 完成。独立 HTTP 503 用例由普通执行器自动重试，同一 Idempotency-Key 只应用一次外部业务。

外部副作用接收方是本轮独立 Node HTTP 验收服务，跨真实 HTTP 网络边界并维护独立业务状态；没有连接真实 ERP/支付系统。早期失败事务的占用已另行清理补偿，不混入修复后自动补偿证据。

清理时额外修复了终止详情读取：流程级 CANCEL/TERMINATE 审计正式使用 reason，任务级审计使用 opinion。读取按作用域验证，流程审计不冒充任务意见；修复后三个正式终止实例详情均成功读取，原审计正文不变。

## 正式模板及历史部署治理

qweqw / form_id=1 的 field102 保留原必填编辑含义，显式写入 hidden=false、readable=true、writable=true。正式 [修复 SQL](../../sql/flowable/repairs/20260910__qweqw_permissions.sql) 与 [回退 SQL](../../sql/flowable/repairs/20260910__qweqw_permissions_rollback.sql) 以内容 SHA-256 防覆盖；实际修复 1 行，回退仅做内容校验、未执行。

- 修复前：`eea8f5d7f46b8af9ed6c5d70224e90c6b08d4852d8a17c86935a75070af5086f`。
- 修复后：`9704de59e6dffe7633650d2aadc617155fb086409783382f859fafa4f81b9fbc`。
- eqwe1 合法 V3：`eqwe1:3:4331a128-ad79-11f1-bc18-005056c00001`；模板验收实例 `436c3933-ad79-11f1-bc18-005056c00001` 完成。
- qweqwe12123 合法 V2：`qweqwe12123:2:7146cf92-ad79-11f1-bc18-005056c00001`；模板验收实例 `715b901d-ad79-11f1-bc18-005056c00001` 完成。

旧作者带有历史编译器注入的 userTaskListener，新作者版本移除这些内部编译产物后重新走正式编译；不允许作者直接提交内部监听器。修复了“先校验旧非法作者而无法保存合法修订”的入口，同时保留新作者校验、版本并发门禁及旧版本不可变性。两个旧作者按换行归一后的内容摘要与修复前一致。

三份旧快照不补默认值、不覆盖历史资源，在目录中保持 UNAVAILABLE；新发起使用合法新版本。现存三个旧业务实例不属于本轮验收数据，保持其冻结版本与业务状态，不自动迁移或代为终止。没有父流程调用快照引用这些坏版本，因此无需切换既有调用依赖。

| 旧定义 | 现存实例 | 处理 |
| --- | --- | --- |
| eqwe1:1:e2370327-9eae-11f1-9e14-005056c00001 | 61604356-9eaf-11f1-9e14-005056c00001 | 旧快照隔离；当前新发起由 V3 接替 |
| eqwe1:2:f1e118f6-9f7f-11f1-b97d-005056c00001 | f9ff5841-9f7f-11f1-b97d-005056c00001 | 旧快照隔离；当前新发起由 V3 接替 |
| qweqwe12123:1:c6320e75-9fa7-11f1-83e1-005056c00001 | 2159c822-9fa8-11f1-83e1-005056c00001 | 旧快照隔离；当前新发起由 V2 接替 |

这是任务单允许的“修正作者并发布新版本”处置，不声称旧快照已经变为合法，也没有将当前模板覆盖运行中历史事实。

## 协议、迁移与环境

- 诊断增加可空 processId；模板目录增加 protocolValid/protocolIssue；调用目录增加 unavailableReason 及 UNAVAILABLE。现有字段和四参数诊断构造方式保留。
- 新增经对象授权的 GET `/workflow/process/waiting`，返回有界等待事实。联调仍以正式事件 Token、事件范围和变量白名单鉴权；关闭窗口即清除当前 Token，不写入 XML/本地缓存。
- DMN 新增 `/workflow/dmn/table` 与 `/workflow/dmn/table/evaluate`，沿用现有发布权限；支持受控列/规则及 FIRST/UNIQUE，后端独立拒绝越界或表达式。
- [可靠消息迁移](../../sql/flowable/repairs/20260911__reliable_message_mode.sql) 已在 ry-vue 执行并复跑验证幂等。新建库基线同步更新；旧记录默认为 COLLABORATION，不改业务 payload/状态。既有环境应先执行迁移再启动新版本应用。
- 本机后端已打包并以 `--spring.profiles.include=timers` 启动最新代码；默认 application.yml 不无条件启用执行器。最终普通、定时、挂起、死信作业均为 0。
- 补偿适配依赖当前 Flowable 8 引擎内部生命周期；以后升级引擎必须重跑同步、异步、多分支、失败重试和真实 MySQL 补偿用例。

## 检查结果

[后端逐套件及用例名称](evidence/workflow-repair-20260911/backend-test-results.json)、[浏览器结果](evidence/workflow-repair-20260911/designer-tests.txt)、[前端契约结果](evidence/workflow-repair-20260911/frontend-contracts.txt)、[检查汇总](evidence/workflow-repair-20260911/checks.json)。

| 检查 | 结果 |
| --- | --- |
| 19 个后端定向套件 | **155 passed，0 failed，0 errors，0 skipped**；其中真实 MySQL/MyBatis 4 项 |
| 前端 contracts | **15 passed** |
| 真实 Edge 设计器专项 | **9 passed，24.5 秒** |
| Java 17 后端打包 | passed |
| 前端生产构建、变更路径 ESLint | passed；保留原有构建体积告警 |
| 最终 git diff --check | passed |

后端套件包括模型更新 22、发起链 28、详情 25、普通退回 18、调用 10、数据对象 13、可靠消息/抛出 6、补偿 2、DMN 4、等待/就绪 3、或签/会签退回与终止 7、协作能力 6、撤回 6、实例树取消 1、真实 MySQL Mapper 4。没有宣称运行全仓全部后端测试。

复跑入口：

```powershell
mvn -q -pl ruoyi-flowable -am '-Dtest=WorkflowDataObjectBusinessTest,WorkflowMessageThrowBusinessTest,WorkflowReliableMessageBusinessTest,WorkflowTransactionCompensationBusinessTest,WorkflowCallActivityBusinessRegressionTest,WorkflowModelServiceUpdateIntegrationTest,WorkflowProcessStartChainIntegrationTest,WorkflowProcessDetailServiceIntegrationTest,WorkflowTaskReturnChainIntegrationTest,WorkflowMultiInstanceGroupReturnIntegrationTest,WorkflowMultiInstanceContinuousReturnIntegrationTest,WorkflowMultiInstanceReturnedTerminationIntegrationTest,WorkflowTaskRevokeApplicationServiceIntegrationTest,WorkflowProcessInstanceTerminationIntegrationTest,WorkflowDecisionTableBusinessTest,WorkflowProcessWaitingBusinessTest,WorkflowTimerReadinessTest,WorkflowCollaborationCapabilityProjectionTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
# 真实 MySQL Mapper 单独在 approvaplat_it 执行，配置 WORKFLOW_MYSQL_TEST_URL/USERNAME/PASSWORD。
npm --prefix ruoyi-ui run test:contracts
Set-Location ruoyi-ui
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npx playwright test tests/e2e/workflow-designer-regressions.spec.js --reporter=line
npm run build:prod
```

## 清理与交付状态

本轮自建测试数据以精确部署/实例清单清理：正式接口删除 37 条已结束流程历史、25 个测试部署及相应作者版本、DMN；模板 2/3 与账号 104/105 走正式逻辑删除。通过限定清单的事务删除剩余 34 条临时草稿、130 条临时站内通知，以及隔离夹具的事件/消息审计、消息、通道和凭据。未关闭外键校验。

[清理后核对](evidence/workflow-repair-20260911/cleanup-final.jsonl) 确认本轮测试的运行、历史、定义、模型、DMN、草稿、附件、通知、SLA、抄送、会签轮次和循环残留均为 0。原恢复实例、两个新合法业务版本、qweqw 正式模板保留。与原消息恢复相关的成功/失败台账和审计保留；关联凭据 1/2/3 全部吊销，端点 1 已停用。

临时探针、日志、生成模型输入和独立 HTTP 服务完成清理；正式回归、修复 SQL、报告及脱敏证据保留。未重置、恢复或暂存工作区已有的无关修改。当前修复 diff 留待 Review。
