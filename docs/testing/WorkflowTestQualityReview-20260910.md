# 工作流测试质量审查与回归替换（2026-09-10）

后续已完成[第二轮彻底审查](WorkflowTestDesignDeepAudit-20260910.md)，又删除 7 个用例并以隔离故障注入验证了漏检。本文数字保留为第一轮执行记录，当前范围与结果以第二轮报告为准。

已阅读会话 **测试复杂流程设计器可达性**（`01a08373-a43e-7d40-ad74-21e40e5c4fe9`），并核对原始 UI 报告、复测报告和 9 月 10 日深度审查。原会话完成了 20 个有效实例，仍留下 12 条问题或阻塞记录；这不等于 12 条都已证明为引擎缺陷。

上一轮测试选错了主要验收边界。原生 Flowable 长流程通过，只说明手写模型在那套测试装配中可运行；用户实际经过的作者模型保存、受控编译、历史模板读取、设计器同步回读、管理页 Mapper 和调度配置没有被这组测试保护。即使旧报告标记了上层未执行，那组绿色数字仍不能回答“平台复杂流程是否可用”。

本次完成测试盘点、低价值用例删除、针对真实缺陷的回归替换和执行。新增 **19 个用例：5 个通过、14 个产品断言失败或查询异常，零跳过**。14 是用例执行数，对应 7 组问题，不能说成 14 个独立 bug。新增断言保留为红色，业务代码尚未修复这些问题。

## 为什么旧测试没有发现 UI 问题

| 旧测试的实际边界 | 漏掉的用户行为或故障 |
| --- | --- |
| 手写原生 BPMN，直接交给 Flowable 仓库部署 | 平台作者模型的会签身份校验、嵌套开始节点规则、受控编译等保存和发起问题 |
| 直接调用引擎完成任务和推进指定定时作业 | 业务任务服务的最终权限复核、应用中的异步执行器是否真的消费定时作业 |
| 用整齐的有效表单和部署资源作为输入 | 历史模板缺少权限元数据，以及一份坏快照使整个调用目录失败 |
| mock 的属性写入函数，没有真实 `element.changed` 同步回读 | 错误/升级编码、边界定时器输入在命令之间被重置 |
| DTO/序列化或内存对象验证，没有执行管理页查询 | MyBatis 把 `Boolean` 传给要求 `boolean` 的 record 构造器 |
| 登录凭据缺失时 `test.skip` | 两个上层用例未执行，整条浏览器命令仍可能成功退出 |

数据对象、消息抛出、多池等也有未覆盖的创作链路。把并行、分支、多实例在原生引擎里组合得更长，不会自动覆盖这些入口。

## 删除、替换与保留

盘点范围为当前 `ruoyi-flowable/src/test` 的 78 个 Java 测试/支撑文件（68 个含测试注解），以及 5 个前端契约文件、2 个浏览器 spec 和组件测试宿主。[文件与方法清单](../../output/test-quality-20260910/test-inventory.json) 和 [实际 Java 用例结果](../../output/test-quality-20260910/java-cases.json) 可逐项核对。测试注解数、mock 数量或文件名本身不作为删除理由。

| 处置 | 内容与理由 |
| --- | --- |
| 删除 4 个文件，549 行 | 上一轮未提交的 `WorkflowLongProcessContract.java`、H2/MySQL 两个入口、`long-process/procurement.bpmn20.xml`。它们重复执行原生引擎组合，绕过本次需要保护的产品入口；原来的 11 × 2 次运行退出平台验收口径。 |
| 删除 1 个 JS 用例及冗余断言，65 行 | 删除“按确定顺序初始化三个领域”的手工装配断言；删除 `Object.keys` 字段顺序和 moddle 描述对象形状断言。保留正式任务能力行为及真实 XML 往返解析。 |
| 改为验证真实响应 | 邮件配置测试从 record 字段反射改为检查实际序列化 JSON 的字段集合，并保留明文、密文、IV 不得泄漏的断言。 |
| 修正执行入口 | `test:e2e:designer` 同时发现新旧两个 spec；两个登录用例缺少凭据时明确失败并说明缺少条件。没有加入跳过、`test.fail` 或反向断言。 |
| 更正旧报告用途 | [旧三明治报告](WorkflowLongProcessSandwichReport-20260909.md) 保留历史证据，顶部明确撤回其作为平台验收依据的用途；已删除的测试命令不再是当前入口。 |

以下测试保留，且本次重跑：真实保存/部署/发起链，任务完成、退回、连续退回、重提、撤回、终止，受控多实例成员和轮次一致性、竞争与回滚，数据库约束和 CAS，附件、通知、发起授权及 HTTP 协议。`WorkflowMultiInstanceEngineContractIntegrationTest` 的同节点/跨节点执行树迁移直接约束生产退回实现所依赖的 Flowable 行为，具有明确保护对象，保留。

严格表单解析、HTTP JSON 白名单、权限失败时禁止副作用等单元测试也保留。它们有清晰的局部职责；通过时不能被解释为 UI 或整条流程通过。`WorkflowSchedulingConfigurationTest` 只保护 Spring 调度基础设施装配，不证明 Flowable 定时器自动消费。

## 新回归及实际结果

| 会话/审查问题 | 新用例与真实执行入口 | 用例数 | 本次结果 |
| --- | --- | ---: | --- |
| R02 / F02 调用目录被坏版本拖垮 | `WorkflowCallActivityBusinessRegressionTest.oneInvalidSnapshotMustNotHideHealthyAuthorizedTargets`；真实 Flowable 部署仓库、正式部署制品、公开目录方法 | 1 | error：坏表单缺少节点权限标志导致整批目录抛错，健康目标不可用 |
| F09 父节点字段权限遗漏 | 同类的隐藏字段输入、HIDDEN/READONLY 输出；公开 `validateAuthorReferences` | 3 | failed：本应拒绝的映射全部被接受 |
| F10 同部署其他流程字段混入 | 同类的 `outputCatalogMustExcludeOtherProcessesInSameDeployment`；真实两流程部署及两个独立表单快照 | 1 | failed：实际输出包含 `childValue` 和 `otherPoolSecret` |
| 调用目录及权限对照 | 可编辑输入、只读输入、可编辑输出；另测坏目标最终校验仍拒绝 | 4 | passed，避免以禁止所有映射或放宽坏目标校验来修复红色用例 |
| R12 / F04 注册表查询失败 | `WfBpmnExtensionManagementMySqlIT`；真实 MySQL、真实 Mapper XML，覆盖启用/停用、系统/业务创建者、有无版本及最新版本选择 | 4 | error：`WorkflowExtensionManagementView(..., java.lang.Boolean)` 构造器不存在；均到达真实查询，测试数据事务回滚 |
| R07/R08 / F03 编码丢失 | 真实 `ProcessDesigner.vue`、bpmn-js 命令栈、下拉选择及导出 XML | 2 | failed：ERROR、ESCALATION 的导出编码为空；后续单次撤销/重做断言尚未到达 |
| F03 相邻边界定时器输入丢失 | 真实输入 `PT5S`、失焦、读取导出 XML | 1 | failed：导出时长为空；与 R01 已有作业未自动执行是两个问题 |
| R09 / F05 数据对象名称丢失 | 在画布命名数据对象引用，解析真正导出的 BPMN | 1 | failed：引用标签为“采购明细”，底层 `dataObject.name` 仍为空 |
| R11 / F01 无效模板切换不原子 | 从有效模板切到缺少权限元数据的旧模板，检查选择、XML 和字段 | 1 | failed：UI 显示旧模板名称，XML 仍绑定原模板，原字段仍在；界面与模型不一致 |
| 模板切换对照 | 真实选择另一有效模板，检查 XML、字段，以及切走再选回 | 1 | passed |

后端调用活动用例只替换当前用户身份目录和模板表读取；仓库、部署制品、发起范围判定、被测服务及事务代理真实执行。它们覆盖目录与引用校验边界，不宣称已经经过父模型保存、部署和子实例运行。

浏览器组件用例替换外部目录 HTTP 数据，完整加载正式 Vue 组件、Element Plus、bpmn-js 和导出机制。每例附 XML，失败附截图和 trace；这层不包含登录、服务器保存、部署或引擎运行。最初脚本误点 `.djs-visual` 被 bpmn-js 命中层拦截，已改为点击 `.djs-hit`；定位超时未计入产品失败统计。

代表证据：[无效模板选择截图](../../output/test-quality-20260910/browser-results/workflow-designer-regressions-R11-拒绝非法模板时恢复原选择，保留原-BPMN-与字段/test-failed-1.png)、[数据对象命名截图](../../output/test-quality-20260910/browser-results/workflow-designer-regressions-R09-数据对象命名必须同步到实际被引用的语义对象/test-failed-1.png)、[浏览器结构化结果与附件索引](../../output/test-quality-20260910/browser-report.json)。

## 执行汇总与复现

| 测试层 | passed | failed | error | skipped | 缺少条件、业务步骤未执行 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 保留的后端业务回归 | 326 | 0 | 0 | 0 | 0 |
| 新增调用活动回归 | 4 | 4 | 1 | 0 | 0 |
| 保留的 MySQL 回归 | 16 | 0 | 0 | 0 | 0 |
| 新增 MySQL 注册表回归 | 0 | 0 | 4 | 0 | 0 |
| 前端局部契约 | 17 | 0 | 0 | 0 | 0 |
| 新增组件浏览器回归 | 1 | 5 | 0 | 0 | 0 |
| 原有登录浏览器用例 | 0 | 0 | 0 | 0 | 2 |

最后一行在 Playwright 运行器中表现为两个前置断言失败，原因是本会话环境没有配置测试账号；报告归类为缺少测试条件，不算产品 bug。完整浏览器命令结果为 **1 passed / 7 failed**，其中 5 个产品回归失败、2 个环境门禁失败。

后端全量运行 **335 项：330 passed / 4 failed / 1 error**；最终调整父流程测试图及邮件 JSON 断言后又定向验证这两个类。MySQL 独立执行 **20 项：16 passed / 4 error**。前端契约 17 项全通过，修改的 JS 文件 ESLint 通过。没有用忽略失败选项让命令返回成功。

从仓库根目录运行后端；MySQL 的三个 `WORKFLOW_MYSQL_TEST_*` 环境变量必须显式指向隔离库。本次为 `approvaplat_it`，不修改数据库结构或版本记录。

```powershell
mvn -B -pl ruoyi-flowable -am test
# 独立跑数据库层，防止上面的已知失败使 Maven 在到达 Failsafe 前结束。
mvn -B -pl ruoyi-flowable -am -Pworkflow-mysql-it '-Dtest=NoSurefireTestsForThisMySqlRun' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

浏览器在 `ruoyi-ui` 运行，前提是本仓库的 Vite 服务已经启动；完整登录条件见 [浏览器测试说明](../../ruoyi-ui/tests/e2e/README.md)。

```powershell
npm run test:contracts
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm run test:e2e:designer
```

详细日志：[后端全量](../../output/test-quality-20260910/backend-all.log)、[最终定向复验](../../output/test-quality-20260910/final-focused.log)、[MySQL 全量](../../output/test-quality-20260910/mysql-all.log)、[浏览器](../../output/test-quality-20260910/browser.log)、[前端契约](../../output/test-quality-20260910/frontend-contracts.log)。

## 尚未被本次测试关闭的范围

R01 的应用定时器自动消费、R03 的 DMN 准备/创作条件、R04 接收任务的正式触发、R05 的匹配消息投递、R06 中间消息抛出的能力与诊断、R10 第二池完整创作/保存/运行仍有覆盖缺口。本次未对这些路径给出通过结论，也未把手动执行 Flowable 作业当作自动调度证据。

这组新增回归精确覆盖已定位的业务和交互缺陷，不能替代修复后的登录保存 → 部署 → 发起 → 复杂长流程运行验收。后续修复必须让当前红色断言通过，并补齐对应运行层验证。

本次修改限定在测试、测试入口和报告。原有六个已修改文件及三份 UI/审查文档的 SHA-256 与开工基线一致，见 [基线核验](../../output/test-quality-20260910/baseline-check.json)。新的 MySQL 数据在真实事务内回滚并断言零残留；调用活动测试删除各自部署及制品。没有暂存、提交或推送。
