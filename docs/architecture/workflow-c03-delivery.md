# C03 授权对象、详情、等待与变量投影交付

基线：`8a2d6f369da18ade0a2735c2812593532568191e`。工作区：`C:/Users/18221/.codex/worktrees/cc52/ruoyiflowable`。初始 Git 工作区干净。仅实施 C03（BS-027、BS-028、BS-049），未暂存、提交或推送。未修改主工作区、用户原有审查计划/覆盖台账及 leader 的进度文件。

## 职责与删除

- 详情和观察入口在读取事务内解析当前身份、授权实例；任务授权消费同次实例和身份，退回申请定位取得的 Task 直接继续任务授权。观察直接把已有实例交给等待投影，独立等待入口仍按当前用户重新授权。
- 现有 AccessSnapshot 保留本次引擎 Task/ProcessInstance，使用 `@JsonIgnore` 排除 HTTP JSON。它们是当前读取的业务对象引用，不是新授权凭据、通用 Context 或跨请求缓存；历史对象的运行引用为空。前端原有字段不变。
- 多实例能力、退回/重提能力、人工催办能力直接消费当前身份和运行对象。独立按 ID 能力入口及写入入口仍重新取得身份、权限和运行对象。多实例 RuntimeSnapshotReader 消费 Task；整组退回/重提、下一办理人读取调用方同步切换。
- 保留执行树、订阅、作业、成员、轮次、申请人局部标记、接收人和事务锁等不同业务事实的读取；保留实际写入后的重新读取。删除重复按任务 ID 装载及已无调用方的 `requireRunning(String)`、`requireReturnedApplicant(String,...)`、`requireProcessStatus`。
- WorkflowTaskRuntimeReader 对运行对象一次读取后判断挂起状态；只有运行对象缺失时查询历史，继续区分 404 与 409。
- 当前变量只按授权根/任务局部作用域、实际可读字段查询一份 Mapper 元数据。删除 Flowable 全作用域历史变量查询、双份 Map、逐项 ID/名称/类型/作用域交叉核验。Mapper 取得 LONG_/DOUBLE_ 及有界标量 TEXT_；元数据不选择 Blob 正文。空可读字段不查询。
- 大正文仍先验证类型、存储形态、单项和累计字节限额，再用同一授权作用域、字段和已确定主键读取。保留 String ObjectInputFilter、受控 JSON 解码、深度/节点/字段/响应字节限制。拒绝任意对象反序列化。
- DetailView 使用明确的 NextUserAssignmentPolicy 枚举，Jackson 输出原策略字符串；删除 DTO 对动作能力、returnedApplication、表单 taskId、重提能力的第二次业务推导。保留不可变集合复制。规则没有搬到另一个 DTO 工厂。

代码量仅作审计辅助：19 个生产文件 +432/-440；6 个既有测试文件 +169/-82；2 个新增正式测试文件共328行。职责迁移和行为判据见上下文，不以净行数判断重构质量。

生产类新增/删除：没有新增生产类型或删除整个生产文件；为传递已有事实增加窄方法参数/读取分支。新增两份正式变量回归文件（H2 与复用相同判据的 MySQL IT），删除两项仅验证 DTO 矛盾构造的旧测试，用服务业务链和 JSON 输出验收取代。

## 语义与证据

### 对象授权、状态、历史与回滚

- 正式 Flowable/H2 详情/观察链实际部署、启动、转办、增加/删除候选、挂起/激活及完成。验证未经授权不得触发变量 Mapper；观察成功只解析一次当前身份、只授权一次实例；独立详情/观察/等待随后请求重新核验。
- 候选撤销后活动任务详情拒绝；Flowable 保留的参与历史仍允许实例审计，按原有契约保留。另以正式对象授权服务和抄送关系 Mapper 的撤权结果验证等待/无当前任务详情拒绝。此处身份主数据及抄送 Mapper 是测试替身，Flowable 实例/任务、关系判定及业务投影为真实执行。
- 无当前任务、普通/委派/历史/挂起、退回申请、受控循环及 CallActivity 父子关系由正式既有详情和流程测试覆盖；ALL/ANY 通过正式生产多实例装配验证已有对象能力与独立入口一致。转办后旧办理人 403，挂起/完成后新请求 409；写入失败后的引擎、轮次和审计回滚由既有多实例体系验证。
- 读取保留 `WorkflowEngineOperations` 的 REPEATABLE_READ。新增变量事务判据在真实引擎变量更新后，从另一线程提交变更：同一读取事务仍见旧值，下次事务见新值；显式回滚真实引擎写入后值不变。H2 和 MySQL 执行同一判据。

### Flowable 8 实际存储解释

核对本机实际依赖 `flowable-variable-service-8.0.0-sources.jar` 中各内置类型的 `getValue`/`setValue`，不依靠伪造映射：

| 类型 | Flowable 8 标量列解释 | 输出 |
| --- | --- | --- |
| null | null | JSON null |
| short / integer / long | LONG_ 的 shortValue/intValue/Long | 原有整数 JSON |
| boolean | LONG_ == 1，null 保留 null | 布尔或 null |
| double | DOUBLE_ | 原有数值 JSON |
| date / instant | LONG_ 毫秒构造 Date/Instant | 原有 ISO 文本 |
| uuid | TEXT_ 经 UUID.fromString | UUID 文本 |
| bigdecimal / biginteger | 非空 TEXT_ 构造 BigDecimal/BigInteger；空文本为 null | 原有数值 JSON |
| string | 受限行内 TEXT_；有 Blob 时进入受控正文解码 | 原有字符串 JSON |

真实引擎写入后验证全部标量值和响应字节计量，含中文/转义、short/int/long 边界、大整数和小数。Flowable 8 默认 `json` 同时使用 TEXT_/Blob；`longJson` 是引擎保留的历史读取类型，测试临时调整引擎自带类型优先级后通过真实 `setVariable` 写出历史类型，再还原类型顺序。旧 `string` 标签 Blob 由引擎先写真实 longString 字节，再仅替换历史类型标签验证兼容，不手造正文或生命周期状态。

其余正式用例覆盖 longString、行内/Blob JSON、longJson、任务局部、并行任务、子 execution 遮蔽、不可读大正文、空可读字段、历史局部值、超限单项/累计正文、64 KiB 输出文本限额、serializable 跳过及恶意标签下 ObjectInputFilter 拒绝。单项/累计超限时断言正文 Mapper 未调用，并验证元数据 SQL 不选择 BYTES_。

### 隔离旧实现反证

从固定基线 `git archive` 建立独立临时副本，只加入同一正式变量测试并适配旧构造器/旧元数据接口。比对归档内 **1430** 个受控文件，旧生产代码全部未变。

- 归档 SHA256：`06c8cb71bdd7dc1601d404349b626e12a04a8a14d0bbda1102b853295e073c55`。
- 判据：真实引擎写入 2,001 个不可读字段和 1 个可读字段，只读取可读字段。
- 旧实现：1 项执行，因“流程变量数量超过安全上限”失败；新实现：同一判据通过。证明移除的是实际全作用域误拦截，不只是 mock 查询次数变化。
- 独立副本未写回当前交付版本，交付前纳入临时资源清理。

## 验证命令与结果

使用 `C:/ProgramData/chocolatey/lib/maven/apache-maven-3.9.16/bin/mvn.cmd`、本机 Java 17.0.10；时间为 Asia/Shanghai。未改 profile、Enforcer、Surefire/Failsafe 门禁。

| 验证 | 命令（省略 Maven 绝对路径） | 结果与完成时间 |
| --- | --- | --- |
| 全模块编译 | `mvn -pl ruoyi-admin -am test-compile -B -ntp` | PASS，2026-09-17 18:56:08，23.602 秒 |
| 最终默认完整验证 | `mvn -pl ruoyi-admin -am clean verify -B -ntp` | PASS，603 项，失败/错误/跳过均为 0；19:21:53，3 分 16 秒 |
| 最终 MySQL 完整 profile | `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it verify -B -ntp` | PASS，Surefire 603 项、Failsafe 197 项，失败/错误/跳过均为 0；19:22:25 至 19:35:07，约 12 分 41 秒 |
| 强化隔离/回滚判据 | `mvn -pl ruoyi-flowable -am -Pworkflow-mysql-it verify -Dtest=WorkflowProcessVariableProjectionIntegrationTest#preservesRepeatableReadAndRealEngineRollback -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=WorkflowProcessVariableProjectionMySqlIT#preservesRepeatableReadAndRealEngineRollback -B -ntp` | PASS，H2 1 项及 MySQL 1 项，失败/错误/跳过均为 0；19:36:03 至 19:36:55 |
| 固定旧基线反证 | `mvn -pl ruoyi-flowable -am test -Dtest=WorkflowProcessVariableProjectionIntegrationTest#readsAllowedFieldsWhenUnrelatedVariablesExceedTheOldScopeLimit -Dsurefire.failIfNoSpecifiedTests=false -B -ntp` | 预期 FAIL，1 项错误，“流程变量数量超过安全上限”；19:11:11，25.693 秒 |

完整 profile 内新增变量 MySQL IT 为 9/9；既有 C05 迁移 11/11、多实例连续退回 4/4、并发/重提/退回/回滚/退回后终止各 2/2、终态 6/6 均通过。197 项还包括原有系统和附件 HTTP 集成验证，不能全部归为 C03 新增覆盖。完整 profile 的 XML 计数在补测前已独立提取，补测不重复累加到完整验证总数。

最终默认验证后仅修正三行多实例 JavaDoc，并强化既有事务测试的 JDBC 隔离级别及直接 SQL 断言；生产运行逻辑没有再变。强化判据的定向 H2/MySQL 补测确认 REPEATABLE_READ，另一线程提交后同事务通过直接 JDBC 仍读到旧值，排除 MyBatis 会话缓存造成假阳性。调试中的测试装配/断言错误均已修复后重跑，没有删去失败场景或放宽构建门禁。

MySQL 使用本任务专有 `127.0.0.1:33383`，监听子进程 PID 72428、启动父进程 53208，配置位于本 worktree `.c03-tmp/mysql/my.ini`。启动使用 Hidden 窗口并核对 PID/父子关系/命令行与配置路径。创建本实例专有 approvaplat_it 基线及测试随机 schema；所有路径使用正斜杠。14 份初始化文件严格来自 `docker/mysql/init.sh`。运行时仅在该命令进程设置专有凭据及 `WORKFLOW_MYSQL_TEST_ALLOW_SCHEMA_DDL=true`，未使用共享环境变量默认数据库、3306/ry-vue 或常驻业务应用，也未执行常驻旧状态迁移。

C03 详情/观察/等待的真实 HTTP 端到端和浏览器：not executed。C03 没有前端变更，未加跑无关前端测试。默认 H2/MockMvc、MySQL Mapper/事务及 profile 自带附件上传真实 HTTP 测试分开计数；附件 HTTP 通过不作为 C03 详情 HTTP 或浏览器验收。

## 调用方与隐式引用检索

全仓检索 `getOptionalState`、`readTask`、`prepareGroupReturn`、`prepareGroupReopen`、`requireReadableTask`、`requireRunning`、`requireReturnedApplicant`、`WorkflowCurrentVariableMetadataRow`、`WorkflowProcessVariableProjection`、`new WorkflowProcessDetailView` 和 `nextUserAssignmentPolicy`；同时检查 main resources 的 Spring/MyBatis XML/yml、Controller、前端 JS/Vue 和正式行为文档。直接调用方已经迁移；未发现方法名字符串/反射/配置隐式消费者。Mapper namespace、方法名及作用域/字段谓词保留，DTO JSON 契约正式测试通过。独立按 ID 读取入口的身份检查和写后重读不作为重复查询删除。

没有涉及 C06、C08、C10、C11 的列表、扩展执行、路由或日历。没有添加授权票据、跨请求缓存、通用 Context、迁移脚本或兼容性双写。没有改变数据库模式或现存变量存储格式。回滚本变更仅需恢复本白名单代码与测试；不涉及数据库数据回滚。该回滚说明是恢复方案，不宣称执行过 Git 回滚；已经执行的真实引擎事务回滚见上述测试。

## 冻结白名单与原始 SHA256

原始 SHA256 由固定 HEAD 通过 `git cat-file --filters HEAD:path` 恢复 checkout 字节计算（仓库 core.autocrlf=true）；初始工作区干净，另以未修改文件核对恢复字节与实际 checkout 相同。NEW 表示基线没有该文件。没有暂存内容，leader 可以据此限定 review/提交范围。运行时临时文件不属于白名单。

| 文件（相对本 worktree） | 原始 SHA256 | 冻结 SHA256 |
| --- | --- | --- |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/authorization/WorkflowProcessAccessService.java` | `d1c629d01f2e01406a8094fa109675d457d2fb36a928cf81a20230beb52cbdd1` | `e35ec5f6017d2ecf89069743f5205b8ee179a64dc52acc913d6cd0b6c1924016` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/authorization/WorkflowProcessAccessSnapshot.java` | `ef5674b58e655b5bd9f7d0e0257b7ba26376665dfc91d08454dfe76e87310abc` | `7677d5c510e18ab7410d32b4dd9f55b7902192e025faa33cefafb379f43626df` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/authorization/WorkflowTaskAccessSnapshot.java` | `d4cced517955355cb2850d73deeddb5af9a7c1eca2832140da0d84312c095cdd` | `03fc1dd2ed2fb275b4e45fcdb5e025f129518d68e8dd374c6e0e1844de720b10` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/domain/WorkflowCurrentVariableMetadataRow.java` | `7b7317e9e921cec47bbea32573854a2fd3aff25651952e404585bb5e3ae75256` | `a1194376d1669a8fb58952169e1b43c2d66a4478fbc8dd4473feedf399ad3636` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/domain/vo/WorkflowProcessDetailView.java` | `a79697157d820753843a5532e8d8d16d033f118f65ee40cb3a719492b6960b21` | `6d6fba218dc2add8bdddbdc05801963e4124ec5d682729ace639566cbc897ccc` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowManualUrgeService.java` | `913ff54dfaa06dec38c1d8907d870cb60a2d1320f9b921d8fd42808955c57fca` | `475f4dddcf88e93988ddb85088eba41317edd77277aa93547bd78a300d2fa546` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessDetailService.java` | `20e744d0a42f9d7ae59c416c9258e0a3ab1cae69d7eaf8cedeb288f9260adf51` | `88fdce71ce9c562d44bb8f12a1cd6211e0f5b15ee6f4a65842fa68bd579fe0e4` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessObservationService.java` | `ba1278647a51bd560c637417539079f7136f1be1d42c98a06183cbd849e1bc33` | `e54e4aa1e930d028c1145d7d0ba585b0424822ef42720e896ed87ddf1033a41e` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessVariableProjection.java` | `6ceb0f1f8103896115daf85538a7e197d6d70f243e4d1740ada2ea7cc99771c6` | `b5ba1a86ffe5d0aea0099d5c26472c458f04af13aa4a0048b06183f5a5946fb1` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessWaitingService.java` | `8d1a139ea3061da5d5563c6dcd187074e0ca8115c8d4d474ba4d9b3f81a90522` | `cacbfc48e47143131249662d15da5203045b3b8d3bbc6f3e8e0cd6630792e716` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowApplicationResubmitApplicationService.java` | `f88d1f59f9a827dcd526b6d18d34e4fa12177070c12551f8e6a5b164333d4f72` | `315864bb9e89f7a5adc5e238d70efa76bddad727b331aa55499aa49262073558` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowMultiInstanceGroupTransitionService.java` | `53f7e1161763bb57bfa56471c2e003313088fe3048d0709ef76a8364585e8f32` | `f1103eb3036eec220d42b53adca37b57ee3801f420273bcb12679641380b1086` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowMultiInstanceRuntimeSnapshotReader.java` | `b2f717a66f7c8bcd87cc3848436d76644b418f5f2ef6c7c0d034194b3330ec8e` | `3ac759f3f48078d015ca75900a668cd13a1dd02e041494f57f2f4e084b202b98` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowMultiInstanceService.java` | `841fb53411b0cf0930bcfca65bb79bdcbf1478c3c442baf4fd086cbc7c16a80c` | `92ee73ad6604457b21b75714ec0e4506596d7da71bcf948a7a61676be65d6bdf` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowNextTaskAssignmentService.java` | `951e48c3e114d16c791f8d9fe1a0bf291e2b0c392288c352ee195d3d3efd5e56` | `5b0645d12bde0590e5733a9c3ba550cabfbcb00a5f7050265a5d3fc648fbf8c6` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowReturnedTaskStateService.java` | `eab103c4093acfc496de2752efd993c033c1d480d9667ae4dafa30d16f5696a9` | `878a55c4758eb763c081ea7b35961fa1fb5b79c77a9ccd692a55abc2e913e339` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskReturnApplicationService.java` | `9cd77411cbe5d2f62a5a0d75789df11927a561d2a117e56b50c31a6378e5ccbe` | `6f00ba7df2b220717eaa0c4ce193ea5d5cbed585c938c50e1b370a2e7466fe19` |
| `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskRuntimeReader.java` | `79d9db3995b8d5efed24d6c11705073f161e18028f759771347259eea6c247ec` | `ef77229c780373ee7f538d66cd3a1d6da4946dd014a5adcbb03cc5bc5640e2eb` |
| `ruoyi-flowable/src/main/resources/mapper/flowable/WorkflowHistoricVariableMapper.xml` | `54cfa7e191b37b7ba98e9c49ed5c822b3bdd2a9fa43b961b5ac68614bc1ca1a7` | `c1ad05b4b4c5616b32dfbd0b7bd67b32465378a9d3c8231a88e49e7cd0e3357c` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/domain/WorkflowHttpProtocolContractTest.java` | `93d44788266edbd2b5b032ea0b9442582857669063020f3d7ab3c0b3626f47fb` | `bb08ba79bbf0a671c0527d848798c60796886933159b974966a5593441b6ab36` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessDetailServiceIntegrationTest.java` | `09a94fa6e74b81a41a98b2f7cc82d7f0dd4fb6b42ad7e7373ab3d8bc1fefdab7` | `506374e03c2dd4b869f5e19a59213e4044d5b5361419bd84ddf92738cedc1624` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessViewerBusinessTest.java` | `380c4a430e6cab27120ddf60582305cc839de405e96de4aa351c4cac94b85813` | `41545cc81b1e5251fd9c32d6c9f6023144f9db805b393c7cf6a47eb753af2cba` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessWaitingBusinessTest.java` | `11085adae8b29c9985a392ce105995e197cb7febe0c5682c7815a25c4872300a` | `1e3814eeea953dbed33b78e722cf8329e76df237e452e457f483d941ba66854e` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/task/WorkflowCandidateTaskCreationIntegrationTest.java` | `c23328589b804b14eeb96f64324c243d045a52fc97fcf886752051ee67878508` | `22665ef1d3358c1c802baa4d0052e4da22a341a18dbd4c42937e1f044563bcdd` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/task/WorkflowMultiInstanceCapabilityIntegrationTest.java` | `23205da6faf1a5eb20fdce0da9e67fd677cde24b239a477d10049aa7e36bc6f6` | `5dccd8b7be5acc60e4529503d74423b4b59b1ffdf0fb8e83cad038aa9ff6d431` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessVariableProjectionIntegrationTest.java` | `NEW` | `e70834dbb08d673a48329ac24d5de15c1618457da062493b06a392d9446eb270` |
| `ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessVariableProjectionMySqlIT.java` | `NEW` | `2dc1ef420e3bf21c597ad26d1f9bc7c69fab9f3552a75becc7f80517a7ba116a` |
| `docs/architecture/workflow-c03-delivery.md` | `NEW` | 由交付消息另行给出，避免自引用哈希 |

## 清理与冻结

关停前再次核对独立 MySQL 的端口、数据目录和进程归属；使用本实例 mysqladmin 正常关停，确认 PID 72428 / 53208 均退出，33383 不再监听。最终 schema 仅有本任务 approvaplat_it 及 MySQL 系统库，测试临时 schema 已由正式夹具清理。

实现任务在当前 worktree 内校验绝对路径后，使用 PowerShell `Remove-Item -LiteralPath -Recurse -Force` 正常删除以下资源，表列目录全部直接删除成功。leader 随后检查发现该清单遗漏了 `ruoyi-admin/logs` 的三个忽略日志，补充处理记录见下文。

| 已删除目录（相对本 worktree） | 删除前文件数 | 删除前字节数 |
| --- | ---: | ---: |
| ruoyi-common/target | 124 | 563172 |
| ruoyi-system/target | 98 | 536743 |
| ruoyi-framework/target | 61 | 332291 |
| ruoyi-quartz/target | 25 | 105679 |
| ruoyi-generator/target | 41 | 346938 |
| ruoyi-flowable/target | 1109 | 16216591 |
| ruoyi-admin/target | 126 | 114430396 |
| .c03-tmp | 2936 | 275920110 |

`.c03-tmp` 包含独立数据库/随机凭据、旧基线归档和反证副本、临时脚本、测试日志及计数/哈希中间文件；证据结论已归入本交付文档。新增两份正式测试及本交付报告按任务要求保留。没有清理其他任务资源或系统全局临时目录。

leader 核实遗漏日志均创建于 2026-09-17 19:17:10，大小分别为 sys-error.log 667354 字节、sys-info.log 86430 字节、sys-user.log 0 字节。精确路径删除被自动审批以 `blocked by policy` 拒绝；按用户已授权的集中待删规则，确认 33383 不再监听、源目录只有这三个文件且目标未存在后，将目录移到 `D:/ruoyiflowable-pending-delete-20260917/C03-admin-logs`。三个文件 SHA256 均一致，原目录不存在，等待用户最后统一删除，不纳入 Git。这是迁移，不是已物理删除。

最终冻结范围为表列 28 个文件（19 个生产文件、8 个正式测试文件、1 份报告）。HEAD 保持上述固定基线，Git 暂存区为空，`git diff --check` 通过，27 个代码/测试文件与表列冻结 SHA256 一致；报告自身 SHA256 随 leader 交付消息给出。后续由 leader 独立审查并决定限定路径本地提交；本任务未暂存、未提交、未推送。
