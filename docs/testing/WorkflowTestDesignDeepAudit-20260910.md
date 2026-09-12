# 工作流测试设计彻底审查：第二轮（2026-09-10）

相同问题仍然存在，上一轮的审查不够深入。本轮检查了仓库内 **78 个测试入口文件：71 个 Java、7 个 JavaScript**，范围扩展到 `ruoyi-admin` 的异常处理和请求元数据测试，并追查相关事务、引擎、Mapper、SMTP 支撑和生产调用链。

本轮删除 **7 个用例、1 条循环自证断言及失去调用方的辅助代码**。7 个测试文件共删 328 行、增加 3 行收窄测试范围的注释，净减 **325 行**；其中删除一个完整文件。没有新增常驻回归用例，没有修改业务源码，没有提交。

逐文件的处置、保留价值、mock 依赖和证据边界见 [78 文件审查清单](../../output/test-design-audit-20260910/review-ledger.csv)。[本轮增删明细](../../output/test-design-audit-20260910/task-delta.json) 按本轮开工快照计算，与上一轮删除量分开。

## 两个能实际重现的“写错了仍然绿”

### 1. 放宽清理条件后，两个弱测试仍然通过

在 `output` 下复制生产 SQL、清理器配置和原有测试，单独编译执行。仅把轮次清理的两处 `history.END_TIME_<=?` 改成 `(history.END_TIME_<=? or 1=1)`；仓库中的生产源码不变。

| 既有断言 | 正常 SQL | 错误 SQL |
| --- | --- | --- |
| 搜索 SQL 是否包含时间条件、`limit` 等字符串 | passed | **passed，漏检** |
| 以生产 SQL 常量同时配置 mock 和预期的成功路径 | passed | **passed，漏检** |
| 真实 MySQL 中放入过期、近期、运行中实例并执行清理器 | passed | **failed：期望处理 2 条，实际处理 4 条** |

SQL 字符串测试没有执行查询；mock 成功路径也没有执行查询，而且预期会跟着错误的生产常量一起变化。它们不能提供所宣称的清理范围保护。真实 MySQL 测试按独立业务样本检查实际处理数量与保留记录，因此保留。

证据：[四次执行结果](../../output/test-design-audit-20260910/oracle-summary.jsonl)、[错误 SQL 下弱测试仍通过](../../output/test-design-audit-20260910/oracle-mutant-weak.log)、[真实数据库捕获错误](../../output/test-design-audit-20260910/oracle-mutant-mysql.log)。实验只允许显式配置的 `approvaplat_it`，沿用既有测试的非本测试轮次拒绝门禁及按前缀清理；未修改数据库结构。完整 MySQL 套件随后又按正常源码运行。

### 2. 四类面板合成三类后，面板测试仍然通过

在副本中把 `TASK_PANEL_TYPES.SEND` 改为与 `SERVICE` 相同，再执行原测试的原始断言。

| 副本 | 实际不同业务面板数 | “四类业务任务使用独立面板”测试 |
| --- | ---: | --- |
| 正常 | 4 | passed |
| 错误 | 3 | **passed，漏检** |

测试两边都读取生产常量，没有检查独立性，也没有渲染面板。生产 `DesignerPropertiesPanel.vue` 按这些类型做 `v-if / v-else-if`，类型重合会使发送任务命中服务任务分支。

证据：[面板故障注入结果](../../output/test-design-audit-20260910/panel-oracle-summary.jsonl)。该实验同样只改副本。

## 已执行的删除

| 编号 | 删除内容 | 依据及保留边界 |
| --- | --- | --- |
| TD-01 | `WorkflowDataRetentionContractTest.roundSqlIsBoundedAndRequiresExpiredFinishedProcess` | 错误 SQL 实验漏检。保留真实 MySQL 清理范围测试及危险配置拒绝。 |
| TD-02 | `WorkflowMultiInstanceRoundRetentionCleanerTest.deletesExactlyClaimedExpiredFinishedProcessRounds` | 生产常量同时充当输入和预期，错误 SQL 实验漏检。保留 `rejectsDeleteCountMismatch`：它独立注入“领取 2 条、只删除 1 条”，能检出部分删除被错误接受。 |
| TD-03 | JS“任务能力表不可变且四类业务任务使用独立面板” | 常量别名实验漏检。保留真实菜单过滤与自定义 moddle 解析；独立面板应由浏览器断言证明。 |
| TD-04 | 整个 `workflow-call-activity-moddle.test.js` | 成功夹具包含 `flowable:businessKey="child-expense-key"`，而 `WorkflowCallActivityReferenceService.validateInstanceProperties` 明确拒绝它。测试仅做第三方 XML 往返，也没有证明标题中的版本选择/冻结行为。保留上一轮实际目录和公开引用校验回归；完整创作链仍须单独验收。 |
| TD-05 | `WorkflowHttpProtocolContractTest` 的 `copyQueryContainsOnlyPageFilters`、`manualUrgeResponseContainsOnlyRecipientCount` | 只读取 record 组件列表，没有经过请求绑定或响应序列化；新增 getter/序列化配置造成的实际协议问题不会由它们发现。保留真正检查 JSON、敏感字段和矛盾状态拒绝的用例。 |
| TD-06 | `WorkflowMailConfigMySqlIT.exposesExpectedTableColumnsAndChecks` 及四个专用辅助结构/方法 | 复制列结构和 CHECK 文本，只检查约束名称/字符串，没有用非法数据验证约束是否拦截；保留同 revision 的真实 MySQL 并发写、写后结果和精确清理。CHECK 的独立拒绝覆盖依旧是缺口，不能把删除解释为它已验证。 |
| TD-07 | SLA 持久化结果与 `slaConfigToProperties(createDefaultSlaConfig())` 自比的断言 | 预期直接调用被测路径使用的同一转换器，转换器写错时两边可同步错误。保留独立默认值、非法日历拒绝、边界值、字段往返和无关属性保留断言。 |

没有因为“使用 mock”“用例通过”或“代码行数多”就删除测试。若真实业务分支仍能拒绝非法输入、识别写入冲突、保护数据或避免副作用，它具有独立价值。测试的层级需要与其实际证据一致。

## 保留测试中的边界与缺口

| 测试类型 | 实际保护 | 不能据此宣称 |
| --- | --- | --- |
| 多实例退回、重提、竞争、回滚集成测试 | 生产任务入口、真实 Flowable/H2、轮次数据及事务副作用；并发结果严格检查一成功、一 `ServiceException/409` | 共享启动驱动预置了申请人、运行状态和开始表单快照，不证明浏览器作者配置与正式发起服务都正确 |
| `WorkflowProcessHistoryRoundDeletionTest` | 删除顺序、精确实例集合、计数漂移拒绝、旧载荷拒绝 | 夹具手工标记事务活跃，History/Mapper 为替身；不证明真实数据库回滚或引擎级联删除 |
| 表单投影、权限、通知等局部服务测试 | 独立合法/非法预期，类型转换、脱敏、拒绝后的无副作用 | 不证明目录 SQL、MyBatis 构造、界面选择或完整投递链可用 |
| 解析次数、批量查询次数测试 | 局部重复解析和 N+1 回归；同时存在输出内容或拒绝断言 | 不证明页面可达或产品性能达标 |
| `WorkflowSchedulingConfigurationTest` | Spring 条件装配和线程池配置 | Flowable 异步执行器真正消费定时作业 |
| 本地 SMTP / 文件存储测试 | 真实协议收包、真实文件和摘要、篡改与清理 | 外部 SMTP、生产 TLS、正式部署环境可用 |
| 新增设计器组件浏览器回归 | Vue、Element Plus、bpmn-js 真实交互与导出 XML | 外部目录由 HTTP 夹具提供，不证明登录、服务器保存、部署和运行 |
| 登录设计器用例 | 有条件时验证真实导入、编辑、校验、保存、重载 | 仍没有覆盖完整复杂长流程从创作到运行完成；本轮未执行 |

还检查了吞异常、无断言返回、条件跳过、并发结果与清理。没有发现可以直接按“空测试”删除的用例；并发捕获的异常会被后续严格检查。SMTP 关闭连接时忽略 IO 异常属于本地服务生命周期。符号链接测试在不支持创建链接的系统上可能 `skipped`，这一项只能记为未执行；本次实际运行零跳过。

上一轮新增的 19 项缺陷回归没有被删除或放宽。本轮仍检出其中的后端与 MySQL 失败；浏览器源码保持相同，本轮不重复计入其历史执行结果。

## 删除后的实际验证

| 本轮执行 | 总数 | passed | failed | error | skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `ruoyi-flowable` 默认测试 | 331 | 326 | 4 | 1 | 0 |
| 独立 MySQL 套件 | 19 | 15 | 0 | 4 | 0 |
| `ruoyi-admin` 三个测试类 | 13 | 13 | 0 | 0 | 0 |
| 前端契约 | 15 | 15 | 0 | 0 | 0 |

默认后端由上一轮 335 项降为 331 项，MySQL 从 20 项降为 19 项，前端契约从 17 项降为 15 项。剩余的 5 个后端失败/异常仍来自调用活动目录、字段权限和字段范围；4 个 MySQL 异常仍来自扩展注册表的 `Boolean/boolean` 构造不匹配，没有产生新的失败，也没有通过删除失败用例让套件变绿。

浏览器两份 spec 本轮经过代码审查，未修改、未重跑。上一轮组件层 1 passed / 5 failed、登录层缺账号未执行的结果仅作为历史证据，不冒充本轮通过。

日志：[后端全量](../../output/test-design-audit-20260910/backend-all.log)、[MySQL 全量](../../output/test-design-audit-20260910/mysql-all.log)、[admin](../../output/test-design-audit-20260910/admin-tests.log)、[前端契约](../../output/test-design-audit-20260910/frontend-contracts.log)、[ESLint](../../output/test-design-audit-20260910/frontend-lint.log)。

本轮结论是：测试数量确实掩盖了部分无效保护和大量未验证的上层路径。保留的局部测试有具体作用；它们不构成“整个流程平台已验证”的依据。完整的登录创作 → 保存 → 部署 → 发起 → 复杂运行链、应用定时器就绪、正式消息投递、多池/DMN 仍不能标为通过。

原有业务代码、原始 UI 报告、上轮新增的缺陷测试均保留，见 [文件快照核对](../../output/test-design-audit-20260910/preservation-check.json) 与 [执行结果汇总](../../output/test-design-audit-20260910/verification-summary.json)。临时故障注入代码和被删用例副本仅放在忽略的 `output` 审查目录，未进入 Maven/npm 测试入口。最终改动未暂存、未提交、未推送。
