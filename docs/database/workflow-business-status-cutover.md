# 流程业务状态单一来源与 C05 切换

适用基线：`7c5a303e42064c3c70824692b04c4e5db275acbc`（C09），Flowable 8 / MySQL 8。本次改变持久化协议，不改变 HTTP 字段。迁移文件是 [8.0.5__retire_process_status.sql](../../sql/flowable/migration/8.0.5__retire_process_status.sql)，不加入新建空库的14个基线脚本；空库没有旧数据需要转换。

## 当前状态与历史事实

唯一可写业务状态为 Flowable 实例的 `businessStatus`。根发起在 `WorkflowProcessStartService` 的 builder 中建立 running；CallActivity 子实例在正式 `PROCESS_STARTED` 监听中独立建立 running 和开始表单快照。退回/重提写 returned/running；取消、驳回、终止在原事务中为整棵执行树的每个实例写各自业务终态。自然完成事件发生在运行时清理之后，直接把当前命令中的历史实例 businessStatus 从 running 改为 completed，不修改历史变量存储槽。

API `processStatus` 继续由原规范器从 businessStatus 和真实引擎活动、挂起、结束事实投影：canceled/rejected/terminated 优先；引擎已自然完成且有结束时间时投影 completed；returned 优先于引擎 running；普通活动实例的挂起由真实引擎状态决定。本次保留原优先级，不重新解释返回字段。结构化动作审计中的 processStatus 记录动作时刻，不能作为当前状态源；任务和轮次快照同样保留。

完成通知、自动抄送仍与完成状态同事务提交/回滚；子流程只收敛自身状态和取消自身催办，不发根结果通知。重复完成事件看到已完成/明确业务终态后不产生第二次业务副作用。C09 的先匹配抄送规则、按需读取历史字段和批次内空通知策略缓存保持。

迁移命令的 RETURN/REOPEN Scope 分别直接持有原正式计划和该动作的真实运行观测；取消回调通过 `MultiInstanceTransitionPlan` 这一封闭的两类型接口交还同一个计划对象。旧17字段 TransitionContext、拆散重建、Cancellation 副本及重复动作枚举删除。新接口只让既有两份计划可以作为取消回调结果，不新增字段、生命周期状态或构造自证。ThreadLocal 只绑定本次命令 Scope；禁止嵌套、同线程关闭、异常释放、实际来源/操作者匹配、成员与 revision、轮次 CAS 和数据库事务保持。

## 已核实消费者与范围

2026-09-17，只读连接本机 MySQL 8.4.9：

| 范围 | 数量与结果 |
| --- | --- |
| 常驻业务库 `127.0.0.1:3306/ry-vue` | 31 个定义、31 个模型 |
| 部署字节资源 | 341 份，旧变量名称命中 0 |
| 模型源字节资源 | 31 份，命中 0 |
| 工作流表及模型元信息文本字段 | 281 列（含设计草稿、部署制品和配置），命中 0 |
| 数据库视图、过程、触发器定义 | 可见定义中命中 0 |
| 活动实例/旧运行变量 | 4/4，均 running，根作用域字符串 |
| 历史实例/旧历史变量 | 35/35：28 completed、4 running、2 canceled、1 rejected；两份值一致 |
| 旧变量变更明细 | 46 条，仍需随旧协议退役 |
| 环境变量指向的共享 `approvaplat_it` | 0 定义/模型/实例/旧变量；279 个文本字段命中 0；只读核实，未执行迁移或测试写入 |

仓库全文检索覆盖 Java、Mapper、SQL、BPMN、模型属性、CEL、前后端及公开 API 引用。旧写入集中在根发起、子实例启动、退回重提、终止、完成监听；均已切换。详情/列表/能力原本使用 businessStatus，保留原投影。CEL、开始变量门禁、表单字段、可见变量投影及前端设计器的 processStatus 保留名限制继续保留，防止用户字段重新占用已退役名称。未增加旧变量派生出口，因为本次可见运行消费者不需要它。

把正式迁移脚本的五组阻断谓词转为只读 SELECT（候选状态使用派生查询，不创建临时表）后，在上述两个库的依赖、存储形状、基数/归属、状态冲突和引擎关系阻断计数均为0。本任务未在它们创建迁移过程或修改业务行；该检查也不替代停写前的再次清点。

以上仅证明本次仓库和可连接数据库的可见范围。未检查其他服务器、未连接租户、外部脚本/报表、第三方 Flowable REST/数据库直连客户端、加密或程序动态拼接表达式。无本应用运行中 HTTP 服务可供本次外部访问观察。源码和数据库文本零命中不能证明所有外部系统不存在依赖。上线负责人必须核对集成清单；若仍有旧变量消费者，停留在旧版本直至完成下面的依赖处理，不能让新旧版本同时写入。

## 数据选择与失败处理

迁移使用单连接临时表，只服务一次离线转换，成功或失败后销毁，不创建持久化状态表。

1. 每个历史实例汇总历史/运行 businessStatus 和旧历史/运行变量。已存在的非空值必须完全一致；缺失值可从唯一一致值补齐。来源全部缺失、大小写/拼写非正式值、任何两值冲突均失败，不根据四者排列推测权威。
2. 接受 running、returned、completed、canceled、rejected、terminated。active 实例只能是 running/returned 且无结束时间。显式业务终态保留；无活动实例时必须有结束时间。历史自然 completed 引擎事实可将旧 running 转为 completed，保持原 API 优先级。挂起是引擎事实，不额外写 suspended 业务状态。
3. 对旧变量要求根 execution 作用域、字符串、唯一记录、标准存储槽。任务局部/其他 scope、重复记录、孤儿记录、未知类型/值、字节数组或其他存储槽非空，直接报 `C05_STORAGE` 或 `C05_CARDINALITY`。不清理未知 blob，不抹除无法判断的业务数据。
4. 删除 `ACT_RU_VARIABLE`、`ACT_HI_VARINST` 和 `ACT_HI_DETAIL` 中符合旧协议的 processStatus 记录。更新需要补齐的 `ACT_RU_EXECUTION.BUSINESS_STATUS_`（同时增 revision）和 `ACT_HI_PROCINST.BUSINESS_STATUS_`。不更改 Flowable 官方表结构、部署资源、任务、评论、轮次、通知和其他变量。
5. 整个数据转换处于一个事务。所有前置检查均在修改业务行前完成，SQL 错误回滚。创建/删除临时管理过程本身是 DDL；失败时可留下 `c05_retire_process_status` 管理过程，重跑脚本会先删除它。业务数据不会部分转换。

冲突处理必须由数据所有者逐实例给出结论：在受控导出中列出实例 ID、四份状态、engine state/结束时间，并关联动作审计、任务/轮次事实。确认真实状态后，在停机备份下对精确主键进行经 review 的修正或恢复缺失历史，再重跑脚本；不能批量选“某列优先”。本文不提供猜测性覆盖 SQL。异常取消原因也不能自动猜出 canceled/rejected/terminated。

## 部署/表达式依赖的处理

脚本发现部署字节、模型源或设计草稿含旧名称时，以 `C05_DEPENDENCY` 失败，不原地改写不可变 BPMN/部署制品。针对实际表达式/监听依赖：

- 将模型改为明确的业务字段、正式引擎事实或已有 API processStatus 投影，并通过正式保存/部署生成新版本；业务条件如何替换需按原表达式语义审查，不做字符串替换。
- 旧实例在旧版本完成并清理旧依赖版本后再全量切换，或者另行完成有业务验收的 Flowable 实例迁移。本脚本不自动移动活动实例。
- 要保留旧定义用于查看时，先归档其原始部署/模型备份和审计关联，依照正式保留策略处理；不能为绕过检查删除业务历史。无法处理的旧定义意味着尚未满足切换条件。
- 外部读取改为正式公开 API 投影。不存在授权的通用原生 Flowable 变量 API 兼容承诺；不能通过恢复变量镜像、改变量名、定时同步或长期双写兼容。

## 执行、恢复及不可逆范围

必须停止所有应用节点、Flowable 异步执行器/定时任务、消息消费者及外部数据库写入。确认没有在途事务，并完成上述消费者清单与备份恢复演练。迁移不要使用 `mysql --force`。以下 PowerShell 使用受控客户端配置文件（仅该管理员可读，包含连接凭据），不把密码放在命令行。实际库名、备份目录和配置路径由部署负责人明确指定。

```powershell
& 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysqldump.exe' --defaults-extra-file=C:/secure/c05-client.cnf --single-transaction --routines --events --triggers --hex-blob --set-gtid-purged=OFF --databases <target_database> --result-file=C:/backup/c05-before.sql
if ($LASTEXITCODE -ne 0) { throw 'C05 backup failed' }
Get-FileHash -LiteralPath C:/backup/c05-before.sql -Algorithm SHA256
& 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe' --defaults-extra-file=C:/secure/c05-client.cnf --database=<target_database> --execute='source C:/release/sql/flowable/migration/8.0.5__retire_process_status.sql'
if ($LASTEXITCODE -ne 0) { throw 'C05 migration failed; keep writers stopped' }
```

其中 `<target_database>` 是必须替换的占位符。用明确值运行前，先 `SELECT DATABASE(), VERSION()` 核实目标。完整备份同时保留应用版本、配置、部署模型/制品和受管理附件文件，防止恢复数据库后引用错位。恢复演练应在隔离库上使用备份中的同名 schema，或由 DBA 明确重映射库名；不要把带 `--databases` 的备份误导入常驻同名库。

切换后核验三张旧变量表零记录、businessStatus 无空/非法值、活动引擎与历史记录一致，再只启动新版本。验证发起、同步/人工完成、父子 flowable:in/out、退回重提、挂起恢复和终止。确认外部集成后恢复执行器。

这是一项有损转换：旧变量行 ID、revision、创建/更新时间及逐次变量变更明细被删除；不能从当前 businessStatus 重建历史时间线。业务终态、正式动作评论、表单和轮次快照保留。单纯回退代码会让旧代码遇到缺失变量并失败。恢复必须再次停写，使用切换前完整备份恢复数据库/匹配的文件，并切回原版本；切换后的新增业务写入需另行导出、审查和重放，不能声称自动保留。若无法接受该恢复窗口，不执行本迁移。

在已确认恢复目标、停写并保全切换后数据后，数据库恢复命令如下。备份中的 schema 名决定实际恢复目标；恢复结束仍须验证数据和文件对应关系后才启动匹配旧版本。

```powershell
& 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe' --defaults-extra-file=C:/secure/c05-client.cnf --execute='source C:/backup/c05-before.sql'
if ($LASTEXITCODE -ne 0) { throw 'C05 restore failed; keep writers stopped' }
```

## 验证证据

- 2026-09-17 从不可变 C09 提交提取原始 StartService、CallActivityStartListener、CompletionStatusListener、InstanceService，单独编译并优先装入隔离 H2 类路径。通过正式草稿创建/提交/真实人工完成及同步 start→end：原发起实际生成 running/running；原完成实际生成 completed/completed；历史旧变量是根作用域 string，其他存储槽为空。新契约“旧变量不存在”的判据在旧实现为红。这校验迁移夹具所依据的旧存储形状，并非把手工写状态视为旧生命周期。
- `WorkflowBusinessStatusMigrationMySqlIT` 明确区分旧存储夹具与生命周期：真实 Flowable 创建旧字符串存储，正式迁移补齐权威、移除变量；精确恢复修改列/旧行后与备份一致，再迁移并由新完成监听完成实际任务。冲突、来源缺失、类型/作用域/重复/引擎关系异常和部署/模型/草稿依赖全部证明零数据修改。生产恢复仍要求完整离线备份。
- 同日另在任务专用 MySQL 8.4.9 的随机 schema 实际执行完整 mysqldump→正式迁移→mysql source 恢复。旧运行变量计数 1→0→1，全部27条数据 INSERT 语句逐字一致；全库逻辑 SQL 仅相差 MySQL 恢复后补出的冗余列级 CHARACTER SET 声明，去掉这些声明后完全一致。不把逻辑一致表述为原始 dump 字节一致，也不据此证明未连接生产库已完成恢复演练。
- `WorkflowMultiInstanceGroup*MySqlIT` 复用同一正式服务链和正式 Mapper，在随机专用库重放整组退回、重提、连续退回、回滚及真实竞争；不改变 profile、Enforcer、Failsafe 门禁。每个私有库按正式14个 SQL 初始化并销毁。
- 新的私有 schema 测试遵循既有 C12 的 `WORKFLOW_MYSQL_TEST_ALLOW_SCHEMA_DDL=true` 显式门禁；只能配合经核实的专用实例和三个 `WORKFLOW_MYSQL_TEST_*` 连接环境变量运行。它不修改所连接基线库的结构，迁移只在随机专用 schema 执行。
- 完成抄送/通知 MySQL 回归在实际持久化后注入 SQL 故障，验证 businessStatus、引擎、抄送、通知同事务回滚和合法重试；重复实际完成事件不重复写入。
- 精确命令、最终数量与未执行层随本次冻结交付报告提供。H2 和 MySQL 服务链不等于运行中 HTTP 或浏览器验收。
