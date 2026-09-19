# 流程业务状态单一来源与 C05 切换

适用基线：`7c5a303e42064c3c70824692b04c4e5db275acbc`（C09），Flowable 8 / MySQL 8。本次改变持久化协议，不改变 HTTP 字段。迁移文件是 [8.0.5__retire_process_status.sql](../../sql/flowable/migration/8.0.5__retire_process_status.sql)，不加入新建空库的14个基线脚本；空库没有旧数据需要转换。

## 当前状态与历史事实

唯一可写业务状态为 Flowable 实例的 `businessStatus`。根发起在 `WorkflowProcessStartService` 的 builder 中建立 running；CallActivity 子实例在正式 `PROCESS_STARTED` 监听中独立建立 running 和开始表单快照。退回/重提写 returned/running；取消、驳回、终止在原事务中为整棵执行树的每个实例写各自业务终态。自然完成事件发生在运行时清理之后，直接把当前命令中的历史实例 businessStatus 从 running 改为 completed，不修改历史变量存储槽。

API `processStatus` 继续由原规范器从 businessStatus 和真实引擎活动、挂起、结束事实投影：canceled/rejected/terminated 优先；引擎已自然完成且有结束时间时投影 completed；returned 优先于引擎 running；普通活动实例的挂起由真实引擎状态决定。本次保留原优先级，不重新解释返回字段。结构化动作审计中的 processStatus 记录动作时刻，不能作为当前状态源；任务和轮次快照同样保留。

完成通知、自动抄送仍与完成状态同事务提交/回滚；子流程只收敛自身状态和取消自身催办，不发根结果通知。重复完成事件看到已完成/明确业务终态后不产生第二次业务副作用。C09 的先匹配抄送规则、按需读取历史字段和批次内空通知策略缓存保持。

迁移命令的 RETURN/REOPEN Scope 分别直接持有原正式计划和该动作的真实运行观测；取消回调通过 `MultiInstanceTransitionPlan` 这一封闭的两类型接口交还同一个计划对象。旧17字段 TransitionContext、拆散重建、Cancellation 副本及重复动作枚举删除。新接口只让既有两份计划可以作为取消回调结果，不新增字段、生命周期状态或构造自证。ThreadLocal 只绑定本次命令 Scope；禁止嵌套、同线程关闭、异常释放、实际来源/操作者匹配、成员与 revision、轮次 CAS 和数据库事务保持。

## 迁移前的依赖清点

上线前重新清点部署资源、模型、设计草稿、数据库对象及外部集成是否仍消费旧 `processStatus` 变量。历史本机检查结果不代表目标环境；存在旧消费者时先完成依赖处理，不允许新旧应用同时写入。

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
