# 业务链重构执行进度

本表记录 `workflow-validation-cleanup-plan.md` 第 8 节的实际实施与 review，不替代业务契约。起始基线为 `63e54d482cd92b82fd9e6ac0ce84f803ea031f96`。实现任务使用隔离 worktree；leader 独立审查并在主工作区验证后，精确提交完整单元，不推送。

主审任务：`01a0aa0a-8c53-7ba0-ae19-ae1a6e86a199`。

用户已于 2026-09-17 指定：无法删除的任务临时资源统一移到 `D:/ruoyiflowable-pending-delete-20260917`，按任务分目录保存，由用户在全部任务结束后统一删除；不纳入 Git。review 和验证通过后继续本地提交，无需再次确认此清理方式。

| 顺序 | 单元 | 覆盖证据 | 当前状态 |
| --- | --- | --- | --- |
| 1 | C13 参数／字典批量删除 | BS-041 | 已提交 `e17b5e04e1191a5703f0054f5a27d557c656abff` |
| 2 | C07 组织主数据与部门移动 | BS-034、045 | 已提交 `551c7a35940787fbb62a8fa4df27fb2c90c6ea86` |
| 3 | C12 唯一写入 | BS-044 | 已提交 `9dd6aeecadc1fcee3af9d920e30a44ea53ec42c8` |
| 4 | L01 附件上传门禁 | BS-030 | 已提交 `6181f0fe319266ce794ff4a4b67dc126a1bc2205` |
| 5 | L04 前端异步返回 | BS-040 | 已提交 `8ab3e1ef1e13ef6753573f205a473210e95e0a62` |
| 6 | L02 定时任务调用 | BS-038 | 已提交 `aafd662baa344f645fdda0dbb7f055f9ae5a4801` |
| 7 | C14 登录认证 | BS-037 | 已提交 `574c0197eae865e57a6de86cdbe182f28dfa58ec` |
| 8 | C01 作者输入与部署编译 | BS-016、017、018、019、025 | 已提交 `c24aa00d095ad388946c899878f776d807927f05` |
| 9 | C02 编辑、草稿与发起 | BS-021、031、036 | 已提交 `3794fa7696890706df4ac646cea852db59dd8239` |
| 10 | C04 分配与审计 | BS-033、035；BS-022 身份部分 | 已提交 `eda555b07ac3a77900ad860c4e527c961242d1a3` |
| 11 | C09 抄送与通知 | BS-029、032；BS-022 通知部分 | 已提交 `7c5a303e42064c3c70824692b04c4e5db275acbc` |
| 12 | C05 状态与退回重提 | BS-023、026 | 已提交 `8a2d6f369da18ade0a2735c2812593532568191e` |
| 13 | C03 详情、等待与变量 | BS-027、028、049 | 已提交 `644f1e4566dc0ddf47137e57865a4b7fadd17041` |
| 14 | C06 工作台列表 | BS-043、046 | 已提交 `9b5c60e397d96f1211469e32bcc035079e0dadef` |
| 15 | C08 扩展执行 | BS-020 | 已提交 `0fc302303e524bf658e0272bd66459ad9a8a25f3` |
| 16 | C10 条件路由 | BS-024 | 已提交 `22d6777021a27c4fe4741f2e42a7a53f7a51829b` |
| 17 | C11 SLA 时钟 | BS-048 | 已提交 `b7a40ac6eabcd3b296a71a1b4f2f188107a9d1f3` |
| 18 | L03 代码生成详情 | BS-039 | 已提交 `5b2719fc67223b93dad95c63f077afbddd2b1175` |
| 19 | L05 部门／菜单树 | BS-042 | 已提交 `950c3957c87ab7002bde52e162a1c1c99dcd88c9` |
| 20 | L06 未读通知分页 | BS-047 | 已提交 `5ef2fe237800e4936e109c896945b71a07173e30` |
| 21 | L07 图标格式 | A-001 | review 和主审验证通过，本提交完成 |

## C13

- 实现任务：`01a0aa0c-40c1-78d0-b95c-81f161e9b515`；worktree：`C:/Users/18221/.codex/worktrees/749d/ruoyiflowable`。
- 服务事务拥有目标集合、保护判断和批量删除；字典新增、转入、改名与删除使用相同类型锁，引用采用当前读。缓存只在外层事务提交后失效。
- 删除逐 ID 读取／删除、中途缓存操作和三个失去调用方的 Mapper／SQL。新增生产类型 0；无需数据迁移。
- 实现任务已通过 admin 全依赖构建及真实 MySQL 8.4.9 的 20 项正式测试，0 失败／错误／跳过。覆盖后项拒绝、真实写失败回滚、外层回滚、双方引用竞争、改名、旧 RR 快照和提交后缓存异常。
- Redis 使用受控替身；真实 Redis 故障恢复和浏览器交互未执行。恢复方式见 [参数和字典删除契约](../contracts/system-config-dict-deletion.md)。
- leader 已完整审查 13 文件差异，并用交付文件摘要核对集成内容；在主工作区执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it verify '-Dit.test=SysConfigDictDeletionMySqlIT' -B -ntp`，509 项默认测试及 20 项 MySQL 测试全部通过，0 失败／错误／跳过，admin 打包成功。BS-041 已完成；对应本文件首次加入的代码提交。

## C07

- 实现任务：`01a0aa1e-5780-7da0-8689-fb2a42b9babf`；worktree：`C:/Users/18221/.codex/worktrees/fc90/ruoyiflowable`。
- 起始基线为 C13 提交 `e17b5e04e1191a5703f0054f5a27d557c656abff`；任务已启动并要求完成后向主审任务发送交付消息，触发后续 review、集成验证与提交。
- 二次 review 已通过：有权完整对象直接参与详情与集合命令，部门关系写入在原服务事务中按固定锁顺序执行，删除旧的探测后重读、逐 ID 资格入口和分散关系写入。新增生产类型 0，无表结构或存储格式迁移。
- 首轮 review 发现的 R1／R2／R3 已经正式回归测试先复现再修复：9 个新增场景修订前有 7 个失败；修订后，同父级编辑使用锁定旧关系，本批新增账号的首次更新经真实数据范围查询，删除引用使用当前读。31 项 MySQL 测试覆盖三项修订及原有范围、移动、并发、回滚场景。
- leader 已审查完整 19 文件差异并逐文件核对集成内容；在主工作区执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it verify '-Dit.test=SysOrganizationScopeMySqlIT,SysConfigDictDeletionMySqlIT' -B -ntp`，509 项默认测试及 51 项 MySQL 测试全部通过，0 失败／错误／跳过，admin 打包成功。BS-034／045 已完成。
- 部门关系写入采用已有最小主键记录锁，串行化低频关系写入；浏览器和部署后的 HTTP 全链路未执行。业务与恢复边界见 [组织主数据契约](organization-data-scope-and-department-moves.md)。接下来进入第 3 项 C12。

## C12

- 实现任务：`01a0aa40-c75d-7572-98b2-31ebd9db4979`；worktree：`C:/Users/18221/.codex/worktrees/ae66/ruoyiflowable`。
- 起始基线为 C07 提交 `551c7a35940787fbb62a8fa4df27fb2c90c6ea86`；任务已确认运行。范围包括有效账号名与配置键的数据库唯一约束、写入冲突映射、空库基线、历史冲突只读清点、升级与恢复脚本及正式验证。
- 有效账号的可空生成列与配置键命名唯一索引维护实际写入原子性，具体冲突在写入处识别，各入口保留原有提示；删除无意义预查，保留导入分支所需的身份读取。配置新增／修改在提交后发布缓存；C07 数据范围与 C13 删除缓存边界保留。新增生产类型 2 个，分别表达两个真实业务约束冲突，没有通用查重框架。
- 同步空库显式列名种子、只读清点、升级／回退 SQL 和 [唯一写入契约](../database/system-unique-writes.md)。迁移验证覆盖原排序规则、重复执行、错误结构、真实第二张表 DDL 失败与部分状态恢复；不修改历史业务行。
- 实现任务只读核实常驻 MySQL 8.4.9 的业务库为 `ry-vue`（默认配置名称 `approvaplat` 不存在），有效账号 1、配置 9，两个冲突清单均为空，两表当时只有主键。业务库和共享测试库均未执行本单元 DDL；部署前仍须按契约停写、备份、重新清点、迁移和切换全部代码。
- leader 已独立审查完整 22 文件，按 SHA256 核对冻结交付与主工作区内容。主工作区在自建临时 MySQL 8.4.9 实例执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it '-Dit.test=SystemUniqueWriteMySqlIT,SystemUniqueKeysMigrationMySqlIT,SysOrganizationScopeMySqlIT,SysConfigDictDeletionMySqlIT' verify -B -ntp`，512 项默认测试及 75 项 MySQL 测试全部通过，0 失败／错误／跳过，admin 打包成功。MySQL 分别为 C12 写入 13、迁移 11、C07 回归 31、C13 回归 20 项。BS-044 代码与迁移交付已完成，业务库部署未执行。
- 临时实例测试行与迁移 schema 已清零并正常关闭；临时脚本、数据和日志清理。Redis 为内存替身，注册审计仅验证成功任务安排；真实 Redis、审计异步落库、浏览器和生产部署未验证。接下来进入第 4 项 L01。

## L01

- 实现任务：`01a0aa61-c4a1-7980-87fd-860dfb7a2ed4`；worktree：`C:/Users/18221/.codex/worktrees/50ae/ruoyiflowable`。
- 起始基线为 C12 提交 `9dd6aeecadc1fcee3af9d920e30a44ea53ec42c8`，任务已确认运行。删除 multipart 上传入口错误通用时间窗门禁与独占死引用，保留权限、文件约束、配额、账本、所有权与绑定语义。
- 要求以真实 HTTP multipart 或完整 MVC 拦截链证明同字段连续上传不同文件及失败重试，再回归真实上传账本、配额和归属约束。完成后冻结 diff 并发送交付消息，继续 leader review、主工作区验证与精确提交。
- 首轮交付仅删除生产 Controller 的注解及 import 两行，新增真实 loopback multipart／MySQL 正式测试、admin Failsafe 配置及契约说明。实现任务报告 HTTP 4、既有存储／H2 恢复 19、MySQL 恢复 12 项通过；恢复旧注解后两个核心 HTTP 场景均按预期失败。leader 已审查四文件，尚未集成到主工作区。
- 首轮 R1 已修复：新增 admin Failsafe 允许所选 IT 仅位于 flowable，flowable 原有零匹配门禁保留。实现任务先复现旧命令在 admin 阶段失败，再以同一命令证明修复通过；显式 HTTP 4 项全部执行，不存在测试名仍按预期在 flowable 失败。生产修复仍为纯删除两行，没有新生产类型或运行机制。
- leader 已二次 review 四文件并按 SHA256 核对集成内容。在主工作区的自建临时 MySQL 8.4.9 执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it '-Dit.test=WorkflowAttachmentUploadRecoveryMySqlIT,WfAttachmentMultipartMySqlIT' verify -B -ntp`，512 项默认测试及 16 项 MySQL 测试（恢复 12、HTTP 4）全部通过，0 失败／错误／跳过，admin 打包成功。默认测试中包含存储 9、H2 恢复 10 项，不重复相加。BS-030 已完成。
- HTTP 使用生产过滤器、防重与方法权限链、附件领域服务、Mapper、事务和真实文件，验证同字段五秒内不同文件及失败重试、权限／大小／配额拒绝和归属限制；绑定场景直接调用领域入口。Token／Redis、身份／菜单主数据及未调用的流程授权为替身，未验证真实登录、审计 AOP、Flowable 任务或浏览器。前端无改动。
- 仅在专用实例使用正式 SQL 的四张 InnoDB 附件相关表；验证后附件、账本、配额 guard、草稿行均为 0，实例正常关闭。临时脚本、数据库、日志和本项报告已清理，常驻业务库与共享测试库无修改。接下来进入第 5 项 L04。

## L04

- 实现任务：`01a0aa7e-4991-7351-b388-84d80e608d8b`；worktree：`C:/Users/18221/.codex/worktrees/3af1/ruoyiflowable`。
- 起始基线为 L01 提交 `6181f0fe319266ce794ff4a4b67dc126a1bc2205`，任务已确认运行。范围为路由 action 返回实际异步链，以及标签同步操作保留 Promise 契约并覆盖存在／不存在的结束路径。
- 要求保留路由结果、标签／缓存／持久化与既有登录恢复策略，以生产模块行为测试反证挂起并验证修复，再执行 contracts、lint、生产构建。交付后继续 leader review、主工作区验证与提交。
- 路由返回网络、转换、注册和状态写入的同一 Promise 链；标签使用 async 返回原有浅副本／字段形态，左右缺失目标返回当前视图，组合动作等待子动作并传播异常。删除 13 处手写 Promise 包装，两处生产文件净删 26 行；没有新生产类型或包装框架。路由守卫、user store、tab 插件、TagsView 与页面离开策略无修改。
- 实现任务先运行 23 项正式行为测试：原代码 11 通过、12 失败，复现缺失标签挂起及错误脱离返回链；修复后全部通过。独立探针也确认原路由 action 外层 pending 且原错误发生未处理 rejection，探针已删除。
- leader 已审查完整五文件与直接调用方，并按 SHA256 核对主工作区集成。主工作区执行 `node --test tests/contracts/router-tags-async.test.js` 23 项通过、`npm run test:contracts` 124 项通过（包含前述 23 项）、`npm run lint` 和 `npm run build:prod` 均成功，构建转换 3159 模块。生产构建使用任务临时输出目录，保留原有 dist；验证日志与临时构建产物已清理。BS-040 已完成。
- 行为测试使用真实 Pinia、Vue Router 内存导航和生产 store／API／守卫／tab／cache；网络、登录会话、消息 UI 和 Storage 边界为替身。未运行浏览器、真实登录后端或浏览器真实配额故障；本项无后端和数据库改动。Storage 失败会传播，已完成的内存修改不回滚，详见 [路由与标签契约](../contracts/frontend-route-tags-async.md)。接下来进入第 6 项 L02。

## L02

- 实现任务：`01a0aa8c-e4e4-7592-9f50-fe2819393d91`；worktree：`C:/Users/18221/.codex/worktrees/1e6c/ruoyiflowable`。
- 起始基线为 L04 提交 `8ab3e1ef1e13ef6753573f205a473210e95e0a62`，任务已确认运行。解析实际 Bean／类／方法与字面量参数，目标授权在保存及执行链保留；删除新增／修改对整段字符串的黑名单。
- 要求保留 Cron、既有调用格式、参数类型和授权范围，证明 URL 字面量可保存与真实 Quartz 执行，非法目标在运行前拒绝。交付后继续 leader review、主工作区验证与提交。
- 首轮交付为 14 文件，统一目标解析与实际调度授权、删除全文黑名单及独占死代码，增加真实 Spring／Quartz／H2 行为测试。实现任务报告 9 项新增与 4 项 Cron 定向测试通过，最终 521 项默认测试通过；主工作区尚未集成。
- R1：新语法拒绝原先可解析的带连字符 Bean 名及包含同类引号的字符串。leader 已运行基线生产解析器，确认 `ry-task` 作为 Bean 名、`O'Brien` 和包含两类引号的字符串均保留原值；已要求通过正式 Spring／Quartz 反证并保持合法旧格式，不扩大目标权限。
- R2：保存校验捕获全部 `BeansException`，把合法 Bean 的创建／依赖故障吞为无效输入；已要求仅识别实际输入拒绝并保留系统异常 cause，使用真实懒加载 Bean 失败验证。两项修订和二次 review 前不提交。
- 二次 review 已确认 Bean 名及内部引号兼容、R2 的真实懒加载系统故障传播已修复；实现任务正式反证 5 项修订前全部失败，并使用基线生产类驱动同一真实 Quartz 测试证明旧格式及故障行为。修订后定向 18 项、默认 526 项通过。
- R1 仍未关闭：修订新增的内部“引号后接右括号”扫描会拒绝基线可解析的普通字符串 `count('x')`。leader 再次运行基线解析器确认该原值以及类似调用文本都只是一个 String，已退回删除内容扫描并改用“只调用一个授权方法、字符串不求值”的实际业务证明；不再按字面量内容猜测表达式。主工作区继续未集成、未提交 L02。
- 最终 review 已关闭 R1／R2：字面量不再扫描禁用内容；Bean／实际类型／所选方法的授权与参数解析分离，保存与每次运行均限制实际调用目标。正式测试证明包含两类引号、括号及看似第二次调用的文本原样传给同一个已授权方法，实际只调用一次；真实懒加载创建／依赖故障保留原因。目标授权拒绝包名前缀伪装、Object 或未授权父类方法，Spring 代理调用仍经过切面。
- 统一既有 JobInvokeUtil 的解析与执行，删除 Controller 全文黑名单、ScheduleUtils 旧白名单及独占常量／工具函数；新增生产类型仅为 JobInvokeUtil 私有 Invocation record，持有当次已解析实际目标、方法和参数，不是通用校验框架。外部字段、五种字面量类型、Bean／类调用规则、Cron 和调度流程保留，无存储格式或数据库迁移。
- leader 已审查完整 14 文件并按冻结 SHA256 核对主工作区集成，在主工作区执行 `mvn -pl ruoyi-admin -am verify -B -ntp`，527 项默认测试全部通过，0 失败／错误／跳过，8 个 reactor 模块成功；包含新增 JobInvocationTest 15 项与原 CronPreviewTest 4 项。验证完成于 2026-09-16 23:02:59 +08:00。BS-038 已完成。
- 测试使用真实 Spring 方法权限、生产 Controller／事务服务／XML Mapper、H2 任务与日志数据、Quartz RAMJobStore 及实际触发线程，覆盖保存、修改、自动／立即运行、权限拒绝、代理和运行失败日志。菜单服务与记录参数的任务夹具为受控边界；未执行 MySQL、Quartz JDBCJobStore／集群／重启恢复、完整应用 JWT／审计切面或浏览器。临时验证脚本、日志及本项报告清理，已有目标目录和用户文件保留。详见 [定时任务调用契约](../contracts/quartz-invocation.md)。接下来进入第 7 项 C14。

## C14

- 实现任务：`01a0aac0-610f-7280-bb84-0c703f851e5f`；worktree：`C:/Users/18221/.codex/worktrees/a482/ruoyiflowable`。
- 起始基线为 L02 提交 `aafd662baa344f645fdda0dbb7f055f9ae5a4801`，已确认任务运行。范围为同一次认证生命周期的一次真实 BCrypt 比对、实际锁定与重试结果维护，以及用户详情装载与原始凭据上下文清理。
- 要求保留登录异常策略、账号状态、原重试键值及 TTL、管理员解锁和独立请求隔离；正式验证使用生产认证链及真实 Redis 兼容服务证明并发与有效期。交付后冻结并发送消息，继续 leader 独立 review、主工作区验证及精确提交。
- 首轮交付为 8 项：既有 SysPasswordService 承担 DaoAuthenticationProvider 的重试与成功清理扩展点，用户装载移除密码检查，删除无其他用途的 AuthenticationContextHolder，Redis 失败写入用同一 Lua 累加并续期。实现任务报告原链的两项正式反证均失败（成功两次 BCrypt、4 个并发失败仅累计 1）；修复后真实 Memurai／生产序列化与认证链 17 项、默认 527 项通过。leader 已完整读审生产改动、正式测试及实际 Spring Security 7.0.5 来源，暂未发现认证实现阻塞问题；主工作区未集成。
- R1：新增 LoginAuthenticationRedisIT 会被既有 MySQL profile 的默认 `**/*IT.java` 扫描，意外要求 Redis 环境；两个 profile 的无 ID execution 合并，插件级 includes 又将组合运行限定为 Redis 测试，漏掉 admin 的既有 MySQL 附件测试。leader 用本地 Failsafe 字节码确认默认选择，并实际生成两个 profile 的有效 POM，确认仅剩一个执行及 Redis include。已要求隔离选择与执行、保留原定向和零匹配行为，证明各自单独及组合执行均正确后重新冻结。本项尚未提交。
- 二次 review 已关闭 R1：两个 profile 使用独立 execution，选择参数、Redis 报告及 summary 独立；Redis 标签由该组选择、由 MySQL 组排除，包含宽泛 `it.test=*IT`。实现任务先真实复现 MySQL-only 意外依赖 Redis、组合运行漏掉附件测试，再证明两组分别运行及组合 22 个场景恰为两组并集、无重复；缺少 Redis 时组合仍失败、两个零匹配门禁仍失败，原 flowable-only 定向恢复 12 项仍通过。
- 更正首轮证据：原独立请求夹具的新密码长 21 位，超过现有 20 位限制；前置 BaseException 在 Spring 关闭后延迟取消息，导致报告器漏记该失败，首轮报告的 17 项不能证明该场景。实现任务先固化异常消息，使原输入明确报 1 项失败，再改为合法长度；生产长度限制未改。最终 Redis 正式场景为 18 项，包含独立请求密码变更的旧密码拒绝、新密码成功及计数清理，主工作区也核实该测试身份实际存在。
- 认证由既有 SysPasswordService 的 DaoAuthenticationProvider 扩展点承担；删除用户装载中的密码比对、旧独立 matches、原 Redis 读后写计数、清理前 hasKey 和无其他用途的 AuthenticationContextHolder。新增生产类型 0。当前认证只执行一次真实 BCrypt，锁定前置检查、输入账号键、Integer 格式、TTL、错误提示与管理员解锁接口保留。失败 INCR／EXPIRE 原子执行，在途成功与失败按 Redis 写入先后生效，无跨请求复用结论或全局认证锁，无数据迁移。
- leader 已审查完整 8 项并按最终 SHA256 核对集成，主工作区在自建 MySQL 8.4.9 与 Memurai／Redis 兼容实例执行 `mvn -pl ruoyi-admin -am '-Pworkflow-mysql-it,login-redis-it' '-Dit.test=WorkflowAttachmentUploadRecoveryMySqlIT,WfAttachmentMultipartMySqlIT' verify -B -ntp`，527 项默认、18 项 Redis 认证、16 项 MySQL（恢复 12、HTTP 4）全部通过，0 失败／错误／跳过，8 个模块成功。逐项核对三个 IT 的 XML 测试身份与独立 summary，实际各执行一次；完成于 2026-09-17 00:03:08 +08:00。BS-037 已完成。
- C14 使用生产 SecurityConfig／Manager／Provider、用户详情及登录服务、BCrypt、RedisConfig／Fastjson 和真实 Redis 命令；身份／权限／配置查询、Token 输出及审计调度为替身。未执行完整 HTTP 登录、真实 JWT、异步审计落库、浏览器、Redis 集群／网络响应丢失。MySQL／HTTP 沿用正式附件测试的既有替身边界，不表示真实登录已验收。四张临时附件表与 Redis 键均清零，自建服务已关闭；临时脚本、数据、日志及本项报告清理，用户文件与常驻数据保留。契约见 [登录密码认证](../contracts/login-password-authentication.md)。接下来进入第 8 项 C01。

## C01

- 实现任务：`01a0aaf8-8990-7750-99bf-7d21c6899b2b`；worktree：`C:/Users/18221/.codex/worktrees/823b/ruoyiflowable`。
- 起始基线为 C14 提交 `574c0197eae865e57a6de86cdbe182f28dfa58ec`，任务已确认运行。第 8.2 的作者输入、表单解释、现有领域准备与部署执行模型按一个完整单元切换，前端保存和运行时表单消费者同步调整。
- 要求保留所有现有审批能力、作者与执行模型隔离、权限与事务边界和持久化协议，删除无效重复准备及 XML 往返；以真实组合模型保存→部署→发起→运行和失败回滚验证。交付后冻结并通知主审，继续 leader review、主工作区验证与本地精确提交。
- leader 已审查完整 50 项交付及直接消费者，按冻结 SHA256 核对主工作区。普通保存只提交当前作者输入一次；目录独立装载、保留其他成功结果并允许刷新。模板按来源解析一次，节点权限各自产生投影，系统输出与任务办理消费当次根节点／字段。执行模型与作者隔离，原有八组编译步骤直接修改同一模型并返回实际制品，统一输出保留数据引用、根定义、协作身份、DI 和系统输出权限；保存不再构造并丢弃 DMN／SLA 执行产物。
- 删除 7 个中间 XML 部署容器、WorkflowFrozenFormContent、仅转发的旧解析／输出接口、重复资格准备和前端全图业务复查。生产 33 文件 +466/-1502，正式测试 15 文件及业务文档 2 文件同步切换；新增内容为实际业务结果与必要转换保真，没有通用 Context 或模式控制流程。持久化制品和对外接口保持不变，无数据库迁移。原 lockDeployableModel 没有独立版本组锁，本次保留实际事务内读取与模型 REV 写入并改为真实命名，不能表述为实现了新的跨模型组锁。
- 主工作区 `mvn -pl ruoyi-admin -am verify -B -ntp` 于 2026-09-17 01:30:03 +08:00 成功，530 项默认测试（flowable 488、admin 42）0 失败／错误／跳过，逐个 XML 汇总核实。前端 contracts 127 项、lint、生产构建通过（3159 模块，19.23 秒）；独立 Vite／真实 Edge 执行 `workflow-designer-regressions.spec.js` 14 项通过（37.5 秒），截图已查看。构建输出使用临时目录，保留主工作区已有 dist 和依赖。
- 新组合测试使用实际 ModelService 保存、无写入预检、部署与 Spring 事务／Flowable 8／H2／DMN：部署制品写入后故障整体回滚，成功后运行 DMN、条件分支、参与者、CallActivity in/out、两个 SLA 定时器创建和取消，覆盖双执行池、共享表单不同权限、标准引用与作者原文不变。发起／完成使用实际引擎 API；模板、身份目录、通知及 SLA 台账为受控替身，不表示完整 WorkflowProcessService／HTTP 用户链验收。解析和 prepare 次数仅辅助证明重复步骤消失。
- 实现任务另报告在专用 MySQL 8.4.9 完整运行默认 530 与 IT 125 项、正式 Redis 脚本默认 530 与 Redis 18 项全部通过；leader 本项未重跑这两组外部存储测试。MySQL 首次迁移权限不足后已补齐专用实例权限并完整重跑，常驻库未改动。一个旧扩展删除测试改用真实引擎时钟明确先后，消除同毫秒随机 ID 排序，不修改生产排序或弱化断言。未执行登录后的整站浏览器保存→部署→发起链，也未证明组合模型在 MySQL 上完整运行。
- 实现任务的递归清理曾被自动审批拒绝；leader 核实四个临时目录归属、无链接及服务停止后，以逐个已检查绝对文件路径删除 27760 个文件并清除空目录，清理阻断已解除。主审 Vite 也已停止，主审临时脚本、报告、日志和输出在提交前清理；原有两份未跟踪计划／覆盖文档哈希不变。BS-016、017、018、019、025 完成，接下来进入第 9 项 C02。

## C02

- 实现任务：`01a0ab49-4e80-7470-8f68-73518f22b0b5`；worktree：`C:/Users/18221/.codex/worktrees/d041/ruoyiflowable`。
- 起始基线为 C01 提交 `c24aa00d095ad388946c899878f776d807927f05`，任务已确认运行。范围为父级表单值所有权和一次编辑一次事件、草稿创建／保存直接使用当次定义与权限化表单、当前页定义事实归并、真实版本绑定与发起判断，删除返回阶段完整装载及加载器同源自证。
- 要求保留当前授权、revision 竞争、部分草稿值、附件绑定与回滚、完整响应 JSON 和未知系统错误传播；验证真实组件编辑、浏览器草稿保存／加载及正式事务链。交付后冻结并通知主审，继续 leader 独立 review、主工作区验证和本地精确提交。
- 首轮交付为 18 个正式文件，父级拥有表单值、写入响应直接投影、Mapper 窄列与数据库生成值、列表批量定义／权限事实及真实草稿绑定判断同切。实现任务报告默认 535、定向 MySQL 草稿 3、前端契约 129、组件浏览器 17、登录草稿浏览器 1 项通过，lint／build 成功；这些尚未作为 leader 主工作区验收。临时资源由实现任务清理，完整交付已冻结。
- R1：新增 StartFormLoad.parseFormRoot 使用共享 JsonMapper，未保留原严格重复键解码。leader 实际运行生产字段校验器，合法字段两条路径均通过；同一字段出现 workflowWritable:false 后再重复 workflowWritable:true 时，旧字符串入口返回 500 快照结构异常，新解码→根节点路径采用后值并接受提交。已要求在单次持久化解码边界恢复重复键拒绝与原必要解码约束，以实际表单装载及草稿链证明拒绝和回滚；不得恢复全量模板审计或重复解析。主工作区未集成、未提交 C02，等待修订及二次 review。
- 二次 review 已确认 R1 修复：作者 validate 与持久化读取共用原严格 JSON 解码配置，保留 UTF-8 字节、嵌套、重复键及单一根约束；字段遍历在实际消费处检查权限和结构并维持 500 原因链，未重新接入全量作者模板审计。实现任务先以真实 Flowable 资源和草稿记录反证创建／保存／提交三个路径都错误接受，再证明三个路径拒绝且完整草稿、附件、运行／历史实例不变；恢复同一资源后可继续编辑并经真实引擎提交。另有 10 项持久化结构与解码边界回归，未把前置拒绝当成启动后回滚。
- 最终交付 19 个正式文件已按 SHA256 集成，生产 9 文件 +245/-264。两个新增 Mapper record 仅投影实际 SQL 摘要与数据库生成版本／时间；表单默认值展示和编辑归父级，草稿写响应复用本次事实，列表按本页不同定义／版本批量取权限并按每份草稿绑定判断。真实提交仍独立授权、锁定和 CAS，删除同源关系自证及重复全文／摘要／元数据对账；对外 JSON、数据库格式和提交幂等语义保留，无迁移或通用校验框架。
- leader 主工作区执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it '-Dit.test=WorkflowProcessDraftStartMySqlIT' verify -B -ntp`，于 2026-09-17 02:40:54 +08:00 成功：默认 548 项（flowable 506、admin 42）及真实 MySQL 草稿 3 项，0 失败／错误／跳过。逐份 XML 核实 StartChain 48 项中 R1 三种写入口与十种解码／权限负例实际执行，MySQL 实际执行部署锁超时、保存 CAS 竞争和提交串行化三个测试。admin 无该定向 IT，既有 flowable-only 选择契约保持。
- 主工作区前端 contracts 129 项、lint、生产构建（3159 模块，16.59 秒）及真实 Edge 设计器／表单组件回归 17 项（37.3 秒）通过。组件覆盖顶层／嵌套编辑、外部重置、数组、权限、必填及附件在途和 UUID 投影，外部目录／上传 HTTP 为夹具。实现任务首轮另在独占完整应用、MySQL、Redis 上完成真实登录页面草稿保存／刷新／恢复，及认证 HTTP 的过期 revision 和两个提交返回同一真实实例；R1 后没有重跑该登录整站用例，不把早期登录证据表述为最终主工作区整站验收。
- 正式 StartChain 使用真实 Flowable/H2、Spring 事务、Mapper、私有附件存储和生产草稿／发起服务，定义锁及身份／外部协作者为受控边界；MySQL 三项使用真实 InnoDB 和生产 Mapper／事务，实例启动与附件服务为夹具。主审自建 MySQL 8.4.9 只加载正式基线的部署、草稿两张表，测试后两表均为 0 并正常关闭；初次本地 root 连接因自建实例的主机名配置拒绝，在运行测试前修正，未修改业务库。Vite 已停止，任务临时数据、脚本、报告、日志和构建输出在提交前清理，原有用户文件与主工作区依赖／dist 保留。BS-021、031、036 完成，接下来进入第 10 项 C04；契约见 [表单编辑、草稿与发起](../contracts/workflow-draft-editing.md)。

## C04

- 实现任务：`01a0ab88-c7d3-72d3-842c-62699cd2af71`；worktree：`C:/Users/18221/.codex/worktrees/772e/ruoyiflowable`。
- 起始基线为 C02 提交 `3794fa7696890706df4ac646cea852db59dd8239`，任务已确认运行。范围为先确定实际分配方式再取所需人员资格、分配最终事实直接进入监听与审计，以及身份选择系统异常保留原始原因。
- 要求覆盖普通单人／候选／动态多实例、SLA、委派转办、退回非审批申请人等真实来源和嵌套 assignment，保留不同运行时刻的资格判断、操作人权限与审计同事务回滚；不引入票据、ThreadLocal 缓存或通用跳过开关。交付后冻结并通知主审，继续 leader 独立 review、主工作区验证和本地精确提交。
- leader 已审查全部 20 项冻结交付及分配／完成调用方，按 SHA256 逐文件集成。普通单人、多人候选和动态多实例先决定实际方式，再执行其所需的选择边界和资格查询；审计直接记录分配结果，删除身份规范化、候选链接与资格复查及普通后继写入后的同集合对账。多实例实际后继／成员快照检查保留。
- 资格责任迁入真实来源：普通参与者保持到达时解析；SLA 升级在定时器实际创建时检查冻结办理人；委派／转办／解决委派检查操作者和目标；退回时分别检查审批操作者与活动申请人，后续恢复重新检查原办理人、owner 或候选；完成入口检查当前操作者和 owner。非审批申请人仍可接管待修改任务。身份选择与参与者系统查询故障保留原始原因，真实业务拒绝保持既有分类；未修改通知／邮件的 C09 异常路径。
- 生产 8 文件 +107/-364，新增生产类型 0；正式测试 11 文件（其中新增 3 个）和业务契约 1 文件同步调整。删除的是重复审计／选择步骤，新增的是被删除审计原先独占的实际分配检查；没有通用校验上下文、缓存或跳过参数。监听表达式、USER_TASK_LISTENER、schemaVersion 1、审计字段和外部请求／数据格式保持，无数据库迁移。契约见 [任务分配、身份选择和审计](../contracts/workflow-behavior.md)。
- leader 主工作区执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it '-Dit.test=WorkflowAssignmentMySqlIT' verify -B -ntp`，于 2026-09-17 03:39:35 +08:00 成功（2 分 19 秒）：默认 582 项（flowable 536、admin 46）及真实 MySQL 5 项全部通过，0 失败／错误／跳过。逐份新 XML 与 testcase 数量核实，重点为候选／下一办理人／SLA 21 项、退回／恢复／委派真实监听 12 项、身份系统故障 7 项和安全 Controller 响应 4 项。
- H2 使用真实 Flowable 创建、同步 assignment、完成及 Spring 事务／审计，目录及通知等外围协作者为受控边界；多实例测试支撑已由空审计改为真实审计。MySQL 5 项使用正式 RBAC Mapper、真实引擎和 REPEATABLE_READ 事务，验证审批资格但无 claim 权限的单人分配、多人候选、真实 SQL 故障、真实审计唯一键失败及撤权，失败时来源任务／历史／审计整体不变。Controller 测试使用实际控制器、动作与选择服务、全局异常处理，证明系统错误返回安全 500/errorId 且没有后续写入；这不是登录后的运行中 HTTP／浏览器验收。本项无前端变更，未执行浏览器测试。
- 主审专用 MySQL 8.4.9 在端口 33328 加载正式基线 14 个 SQL，未接触常驻业务库；结束后测试用户、角色、部署、运行任务和审计均为 0，PID 54556 正常关闭。实现任务报告其专用 MySQL、临时脚本／数据／日志与编译输出均已清理；主审临时实例、脚本和本轮报告在提交前清理，原有两份未跟踪文档摘要不变，保留原有依赖与构建目录。BS-033、035 和 BS-022 身份部分完成；BS-022 整体仍待 C09 通知／邮件部分验收。

## C09

- 实现任务：`01a0abbf-44b7-7fb2-9222-0c49d50b7ef1`；worktree：`C:/Users/18221/.codex/worktrees/aa82/ruoyiflowable`。
- 起始基线为 C04 提交 `eda555b07ac3a77900ad860c4e527c961242d1a3`，任务已确认运行。范围为先匹配当前事件规则再取实际变量／接收人、规则结果直达抄送、通知批次复用包括无策略在内的查询事实，以及通知身份／偏好／策略和邮件配置保留真实系统原因。
- 要求以真实 Flowable 事件、抄送与通知同事务、自然键去重、后续批次配置变化、MySQL 偏好竞争和安全 HTTP 异常处理验证完整链；保留 C04 的分配职责、抄送独立资格、阅读状态和 outbox 领取／重试边界。交付后冻结并通知主审，继续 leader review、主工作区验证和精确本地提交。
- leader 已审查完整 10 文件交付和直接调用方，逐文件 SHA256 核对集成。当前任务／流程事件先匹配本元素规则，assignment 直接结束；仅匹配表单来源时按实际字段读变量，流程完成只查询所需历史字段。删除其他节点全量巡检、监听调用前全部变量求值及同规则存在性／执行二次解析。Planner 在本批 Map 明确记录无策略，新批次重新取配置；手工／自动抄送实际自然键与读取状态、原优先级和 outbox 边界保持。
- 生产 5 文件 +70/-101，新增生产类型 0；正式测试 5 文件（新增 3 个），未增加业务文档或更改持久化协议。通知／邮件当前身份不再将系统故障转为 401；首次偏好插入仅真实唯一键冲突转为 409并保留 cause，策略系统 SQL 异常传播到现有安全边界，邮件保留 MAIL_CONFIG_PERSISTENCE_FAILED 和原始 cause。真实登录无效、CAS、策略唯一键和 SMTP 网络错误语义保持。仅内部自动抄送入口改为接收现有 DelegateTask，唯一真实调用方同步切换；HTTP、BPMN 监听和审计结构不变。
- 实现任务报告反证：把 5 个生产文件暂时恢复 C04 基线后，新增 MySQL 24 项出现 16 失败／1 错误，Planner 8 项出现 1 失败，安全 HTTP 5 项出现 3 失败；随后逐字节还原修订文件，完整默认 589 和相关 MySQL 36 项通过。此反证由实现任务执行，leader 已审查正式用例和故障注入位置，没有另行重跑旧代码。
- leader 主工作区执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it '-Dit.test=WorkflowAutomaticCopyMySqlIT,WorkflowNotificationConfigMySqlIT,WorkflowNotificationPipelineMySqlIT,WorkflowManualUrgeMySqlIT,WorkflowMailConfigMySqlIT,WorkflowAssignmentMySqlIT' verify -B -ntp`，于 2026-09-17 12:41:04 +08:00 成功（2 分 46 秒）。默认 589 项（flowable 538、admin 51）和 MySQL 36 项（13+11+3+3+1+5）全部通过，0 失败／错误／跳过；逐份新 XML、suite 和 testcase 数量已核实，无 Surefire 测试选择过滤或 profile 门禁改动。
- MySQL 自动抄送 13 项使用真实 Flowable 事件、生产参与者／身份／抄送 Mapper、固定监听、审计和通知写入，外围 SLA／多实例轮次与指标为受控边界。同一事件链证明无匹配不读抄送变量、固定来源不读变量、字段按需读取、无策略每范围只查一次且不读偏好、下一批次看到策略变化；抄送／通知实际 SQL 写入后故障使整个事务回滚，手工自动自然键合并为 MANUAL_AUTO，重放不增加通知且保留已读状态。损坏规则反证是在已部署模型缓存注入，正式保存／部署仍拒绝非法规则，未声称非法模型可正常部署。
- 配置 MySQL 11 项覆盖真实 Mapper／SQL、Spring 事务、首次偏好和过期 CAS、唯一键竞争与系统故障，首次插入并发使用 READ_COMMITTED 精确复现唯一键冲突，不把 REPEATABLE_READ 可能的死锁当成唯一键。另有本地 SMTP／投递重试／事务管道 3、人工催办 3、邮件 CAS 1、C04 分配 5 项回归；没有向真实收件人发信。MockMvc 5 项证明真实控制器／配置服务／全局处理返回安全系统错误，真实无登录仍为 401；未执行运行中应用 HTTP、浏览器或独立 Redis profile，无前端变更。
- 主审专用 MySQL 8.4.9（33329/approvaplat_it）加载正式 14 份基线 SQL；最终测试身份、部署、运行任务、历史审计、抄送、inbox/outbox、偏好与邮件配置均为 0，PID 64456 正常关闭。未更改常驻业务库和原有两份未跟踪文档。实现任务遗漏的 aa82/ruoyi-admin/logs/sys-error.log、sys-info.log、sys-user.log 删除曾被自动审批以 blocked by policy 拒绝；用户随后明确要求集中迁移并继续提交。leader 已核实源／目标绝对路径，将三个文件移到 `D:/ruoyiflowable-pending-delete-20260917/C09-admin-logs`，逐文件哈希一致，原目录已不存在，实现 worktree 无剩余忽略输出；待用户最终统一删除，不进入 Git。主审自身临时实例、脚本、日志和本轮报告已清理，共 488 个文件，临时目录已不存在。交付内容与已验证版本未变，无须因移动日志重跑测试；BS-029、032 及 BS-022 通知／邮件部分完成，结合 C04 已完成 BS-022 全部范围。

## C05

- 实现任务：`01a0addf-0c49-75c3-bb41-a7d528197820`；worktree：`C:/Users/18221/.codex/worktrees/3264/ruoyiflowable`。
- 起始基线为 C09 提交 `7c5a303e42064c3c70824692b04c4e5db275acbc`，HEAD 已核对且任务已运行。先只读清点已部署 BPMN、表达式／监听、运行与历史数据以及可证明的外部消费者，明确旧 processStatus 协议转换与恢复范围，再统一由 businessStatus 维护可写业务状态；对外 processStatus 继续由业务状态及引擎生命周期投影。
- RETURN／REOPEN 回调直接消费既有计划，删除 17 字段拆散重建和同源组合自证；保留轮次历史、真实回调匹配、作用域释放、CAS／事务。要求完整切换发起、完成、父子流程、普通／整组退回重提和各终态，使用真实生命周期、旧协议／迁移数据及隔离 MySQL 证据验证，不在常驻业务库执行迁移。
- 用户新的集中待删规则已传达：无法删除的本任务临时文件在停止归属服务并核对绝对路径后，移入 `D:/ruoyiflowable-pending-delete-20260917/C05-<用途>`，不再因这一清理方式请求确认。正式交付冻结后通知 leader，继续独立 review、主工作区验证和本地精确提交。
- leader 已审查全部 38 项交付、直接调用方与正式测试，核对冻结 SHA256 后集成：生产 14 文件 +235/-685（删除 2 个类型，新增 1 个只返回正式轮次的封闭计划接口），正式测试 21 文件、业务契约及切换文档 2 文件、离线迁移 SQL 1 文件。删除当前 processStatus 变量双写与对账，根发起、实际 CallActivity 启动、退回重提、整树终止和自然完成统一维护 businessStatus；API 投影、动作审计、表单及轮次历史的独立语义保持。
- RETURN／REOPEN Scope 直接持有原正式计划和真实回调观测，不再展开 17 字段再重建上下文或复制取消凭据；取消回调仍校验实例、定义、节点、execution、操作者，生命周期核对持久化轮次与实际根成员／revision。保留嵌套拒绝、同线程作用域关闭、CAS、异常回滚和合法重试；C04 分配职责与 C09 自动抄送／通知事务边界保持。
- 实现任务只读清点本机业务库：31 定义／31 模型，部署 341 份和模型源 31 份资源、281 个工作流／模型文本字段无旧名称依赖；4 个活动和 35 个历史实例的双份状态一致，46 条旧变量历史变更仍保留。共享 approvaplat_it 同样只读。此证据不覆盖未连接外部客户端；上线仍须停写、重查消费者、完整备份／恢复演练并执行迁移。本次未迁移常驻或共享库。
- 迁移 SQL 拒绝存储形状、来源基数、状态冲突和引擎生命周期异常，拒绝部署／模型／草稿的旧名称依赖；转换和旧变量三表删除处于单事务，不改 Flowable 表结构或增加持久状态。自然完成优先及显式终态语义保持。旧变量行 ID、revision 和变更时间线的删除不可逆，回退必须恢复完整离线备份并配套旧代码；不能仅回退代码。具体操作与外部消费者边界见 [业务状态切换](../database/workflow-business-status-cutover.md)。
- 实现任务最终 clean verify 为默认 592 项；完整 MySQL profile 的 178 项通过后补入连续双轮退回和正式终态场景，补跑 10 项 MySQL 通过。leader 随后在主工作区对最终全部文件执行无过滤完整 profile，避免将先后两版结果拼接成最终完整验收。旧 C09 代码的真实发起／完成存储反证与私有库完整 dump→迁移→恢复演练由实现任务执行，leader 已审查其正式判据，不声称另行重跑。
- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it -B -ntp`，于 2026-09-17 14:56:46 +08:00 成功（8 分 25 秒）。核对本轮新生成的 118 份 XML 及实际 testcase：默认 592 项（flowable 541、admin 51），MySQL／HTTP 集成 188 项（flowable 184、admin 4），全部 0 失败／错误／跳过；没有测试选择过滤、跳过参数或 profile 变更。
- 重点覆盖迁移 11 项、完成自动抄送／通知 14 项、整组退回／重提／回滚／并发与退回终止 10 项、连续退回 4 项和正式取消／驳回／管理员终止 6 项 MySQL。真实 Flowable/H2 覆盖根发起、同步完成、CallActivity PROCESS_STARTED 与正式 in/out、普通及 ALL/ANY 退回和挂起恢复；MySQL 使用正式 14 份基线、Mapper 和实际 InnoDB 事务。多实例目录及通知协作者仍有 mock，实际通知 SQL 同事务回滚由 AutomaticCopyMySqlIT 证明。admin 4 项为真实 multipart HTTP 既有回归，不是本次 C05 登录业务浏览器验收；本项没有执行浏览器或独立 Redis profile。
- 主审专用 MySQL 8.4.9 位于 33330/approvaplat_it；C05 每例随机 schema 全部已销毁，基线部署、运行任务／execution、历史实例、抄送和通知 inbox/outbox 均为 0。PID 20676 和启动器 71052 已正常退出，端口释放。初始化首次因临时 ini 中 Windows 路径反斜杠转义失败，在加载任何数据前改为正斜杠重建，未影响常驻库或验证结果。最终 36 个存在文件的 SHA256 与冻结交付全部一致，两个源文件删除，原有两份未跟踪文档摘要保持。
- leader 已重新核验实现任务的集中迁移清单：9 个目录、1853 个文件 SHA256 全部相符，原路径均不存在，内容位于待删目录的 C05-build-outputs、C05-admin-logs、C05-temporary-resources。实现任务专用 MySQL 已停止；它们是等待用户统一删除的本地资源，不是已物理删除，也不纳入 Git。
- 主审自己的临时数据库、脚本、日志和本轮报告已清理，共删除 537 个文件，无须迁移，临时根目录已不存在。原有主工作区依赖、构建目录及较早日志保留。BS-023、026 的代码与隔离验证完成；现存业务数据的上线切换仍受上述迁移条件约束。下一单元为 C03。
- 暂存检查发现三个新增文件末尾多余空行，leader 仅移除 MigrationMySqlIT、MultiInstanceMySqlHarness 和迁移 SQL 的尾部空行；执行内容未变，无须重复上述行为验证。最终 cached diff 检查通过，只包含本单元 38 项和进度台账。

## C03

- 实现任务：`01a0ae2a-7969-77a0-84a9-af69ef01fe77`；worktree：`C:/Users/18221/.codex/worktrees/cc52/ruoyiflowable`。
- 起始 HEAD 已核对为 C05 提交 `8a2d6f369da18ade0a2735c2812593532568191e`，任务已运行。详情、能力、观察与等待在本次请求内直接消费现有身份和已授权实例／任务，独立 HTTP 入口及后续请求继续独立授权；不新增通用授权 Context、票据或跨请求缓存。
- 活动表单按可读字段及真实作用域读取单份元数据和必要有界标量，大正文仍在物化前限额并按需读取；删除重复引擎元数据、双 Map 对账和 DTO 业务再推导，保持 Flowable 8 类型解释、公开 JSON、权限、响应容量及读取事务。
- 要求正式 Flowable/H2 和隔离 MySQL 验证越权、转办／完成／挂起后的跨请求资格、并行／历史／退回场景及实际标量、JSON、longJson、字符串 Blob、任务局部与子执行变量。不得改动常驻或共享库，不提前实施列表、路由或日历任务。完成后冻结交付并通知 leader，继续独立 review、主工作区验证和精确本地提交；无法删除的 C03 自有临时资源继续按用户规则移入集中待删目录。
- leader 已逐项审查并按冻结 SHA256 集成 28 文件：生产 19 文件 +432/-440，既有正式测试 6 文件 +169/-82，新增 H2／MySQL 正式测试 2 文件共 328 行，交付报告 1 文件。没有新增生产类型、数据库模式或存储格式。现有 AccessSnapshot 保留本次 Task／ProcessInstance 供内部消费者直接使用，JsonIgnore 阻止进入公开 JSON；独立入口仍重新授权，实际写入后的重新读取、引擎回调和事务保持。
- 详情／观察直接传递身份、实例和任务给等待、多实例、退回／重提及催办能力，删除只传 ID 后重进公共装载入口的路径。DTO 删除第二份动作／退回状态推导，NextUserAssignmentPolicy 使用既有枚举并序列化为原字符串。变量 Mapper 只读取可读字段和真实作用域的一份元数据及有界标量；Blob 保留限额先于正文读取、类型白名单、受控解码和响应容量。leader 已核对实际 Flowable 8 源码中标量物理列解释及所有直接调用方。
- 实现任务报告默认完整 603 项、MySQL 完整 197 项通过，随后只强化读取事务的直接 JDBC REPEATABLE_READ 判据并补跑 H2／MySQL 各 1 项。leader 随后对最终版本执行完整 profile，不累加补测为新的完整数量。固定旧基线真实引擎中 2001 个不可读变量导致旧实现误拦截的反证由实现任务执行；leader 已审查相同正式测试判据，没有另行运行旧副本。
- leader 发现实现任务清理清单遗漏 3 个 ruoyi-admin/logs 忽略日志（667354／86430／0 字节）。精确删除被自动审批以 blocked by policy 拒绝后，按用户授权移到 `D:/ruoyiflowable-pending-delete-20260917/C03-admin-logs`；文件摘要一致、源目录不存在、实现任务 33383 已停止。交付报告已修正，不再把这些日志表述为已删除；待用户最终统一删除，不进入 Git。
- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it -B -ntp`，于 2026-09-17 20:01:36 +08:00 成功（13 分 01 秒）。核对新生成的 120 份 XML 及实际 testcase：默认 603 项（flowable 552、admin 51），MySQL／HTTP 集成 197 项（flowable 193、admin 4），全部 0 失败／错误／跳过，没有测试选择过滤或构建门禁变更。
- 主审重点核对详情／观察 27 项、等待 2 项、HTTP JSON 协议 9 项、多实例能力 6 项，以及相同变量投影判据的 H2 9 项和 MySQL 9 项。变量实际由引擎写入，再通过生产 Mapper 解码；覆盖原始标量、JSON／longJson、字符串 Blob、根／任务／子 execution 作用域、空可读字段、超限正文先拒绝、任意对象反序列化拒绝、直接 JDBC 可重复读及真实引擎回滚。详情对象授权与 Flowable 任务变化为真实执行，身份目录／抄送 Mapper、部分能力协作者及该详情测试的事务包装为替身；读取事务另由变量 H2／MySQL 场景证明，不将该详情装配称为完整应用事务验收。
- 既有 C05 迁移 11 项、C09 自动抄送 14 项、C04 分配 5 项以及多实例连续退回／并发／重提／回滚／终态回归同步通过。admin 4 项是既有 multipart 真实 HTTP 测试；C03 详情／观察／等待的运行中 HTTP 和浏览器 not executed，未运行独立 Redis profile。本项无前端变更，外部字段和数据库存储格式保持。
- 主审独占 MySQL 8.4.9 位于 33332/approvaplat_it，使用正式 14 份基线；已存在的 33331 监听保持不动，未触及常驻或共享库。最终测试随机 schema 均销毁，部署、运行任务／execution、历史实例、抄送与通知 inbox/outbox 均为 0。PID 48044 和启动器 82484 正常退出，33332 已释放。27 个代码／测试文件最终 SHA256 与冻结交付一致，leader 只更正交付报告清理事实；两份用户原有未跟踪文档摘要保持。
- 主审临时数据库、脚本、日志和本轮报告已清理，共删除 542 个文件，无须迁移，临时根目录不存在。保留主工作区原有依赖、构建目录和较早日志。最终提交限于 28 项交付及本台账；BS-027、028、049 完成，下一单元为 C06。

## C06

- 实现任务：`01a0af41-b14b-79e1-a2e4-260cfb186252`；worktree：`C:/Users/18221/.codex/worktrees/ff6f/ruoyiflowable`。
- 起始 HEAD 已核对为 C03 提交 `644f1e4566dc0ddf47137e57865a4b7fadd17041`，任务已确认运行。先取当前页，再按页内所需 ID 批量读取定义、部署、运行事实与原始昵称，直接装配列表；删除逐用户完整主数据装载和 UserNameCache，不新增 PageContext、通用 Repository 或跨请求缓存。
- 要求保留各列表的公开 JSON、筛选、排序、分页 total、权限与跨对象关联检查。昵称兼容旧 Long.valueOf 解析、原始昵称空白和原输入 ID 回退；既有多实例姓名 SQL 的 trim／账号回退语义不同，不能直接复用其显示结果或修改多实例契约。删除同源查询结果自证时逐项核对，保留真实缺失关系、输入边界与不同事实的关联校验。
- 使用真实 Flowable/H2 和独占 MySQL 验证空页、越界页、时间／分类／挂起过滤、当前权限、缺失关联、重复及多用户、旧 ID、原始昵称、下一请求改名和数据库故障，并执行完整默认测试及适当 MySQL 回归。完成后冻结交付并通知 leader，继续独立 review、主工作区验证和本地精确提交；任务自有临时资源先正常清理，无法删除的内容按用户规则移入集中待删目录，逐项核对源路径及摘要。
- leader 已审查全部 13 文件及列表／导出直接调用方：生产 7 文件 +127/-275，既有正式测试 3 文件、新增 H2／MySQL 正式测试 2 文件、现行契约文档 1 文件。没有新增生产类型或存储格式。实例页统一提取本页 ID 并复用已有 InstancePageFacts；任务页复用 TaskContext／CompletedTaskPage，删除重复任务索引。工作台仅通过新增原始昵称 SQL 读取 user_id、nick_name；多实例原有 SQL 保持。
- 删除 checkedCount／checkedRows、分类／接收人／候选分配谓词和批量主键的同源自证，保留真实缺失定义／历史实例／部署、输入分页与时间边界、不同事实之间的定义关联、当前页多取一条及单实例 200 任务的容量限制。leader 已核对 Flowable 8 实际 JAR 的 HistoricProcessInstance.xml：历史实例 deploymentId 来自相同 ACT_RE_PROCDEF 关联，删除其重复比较不影响请求 deploymentId 与授权对象的独立关系检查。各入口的 Controller 权限和每次请求身份解析保持。
- 正式测试覆盖完整行 JSON、总数／排序／第二页／空页／越界、分类与挂起恢复、旧格式身份及原始昵称回退、改名和物理删除后的新请求、真实 SQL 故障、抄送事件姓名与接收人隔离，以及 201 个真实并行任务的两种容量拒绝。MySQL 追加用例使用正式身份 Mapper 和同一个旧登录会话，验证撤销／恢复权限及停用账号立即影响后续列表请求；其余场景仍有身份与撤回能力替身。查询调用范围证明仅按当前页批量读取，不作为耗时性能基准。
- 实现任务最终 clean verify 默认 615 项通过；完整 MySQL profile 为 207 项，之后追加真实权限测试并单跑 1 项，再次完成默认构建。leader 已按冻结 SHA256 集成并正在主工作区执行包含最终补测的完整 profile，不拼接前后版本为同一次全量结果。部分主工作区原文件仅与新 worktree 的 CRLF/LF 不同，已同时核对规范换行 SHA256 及 HEAD Git blob，确认未覆盖额外修改。
- leader 已复核实现任务 7 个 target、ruoyi-admin/logs 及临时数据库目录均不存在，git status --ignored 无忽略遗留。实现任务报告独占 MySQL 33337 已正常关闭，清理 1823 文件且无待删迁移；主审验证使用另外的独占 33338，常驻业务库和共享测试库保持不动。
- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it -B -ntp`，于 2026-09-17 21:15:49 +08:00 成功（16 分 14 秒）。核对本轮新生成 122 份 XML 及实际 testcase：默认 615 项（flowable 564、admin 51），MySQL／HTTP 集成 208 项（flowable 204、admin 4），全部 0 失败／错误／跳过，没有测试筛选或构建门禁变更。最终新增的真实权限专项已包含在这次完整 208 项中。
- 本单元核心工作台 H2 10 项和 MySQL 11 项均通过；既有可发起列表 8、实例列表 5、任务上下文 4、昵称投影 3 项默认测试，以及 C03 变量投影 H2／MySQL 各 9 项、C05 迁移 11 项和分配／抄送／多实例／系统范围回归同步通过。admin 4 项为既有真实 multipart HTTP 回归，不是 C06 工作台 HTTP 验收；工作台运行中 HTTP、浏览器和前端验证 not executed，本项无前端变更。没有另行运行独立 Redis profile、旧实现隔离反证或耗时性能基准。
- 主审专用 MySQL 8.4.9 位于 33338/approvaplat_it，加载正式 14 份基线；最终随机 schema、部署、运行任务／execution、历史实例、抄送、通知 inbox/outbox 均为 0。监听 PID 59360 和启动器 1104 正常退出，端口释放。主审临时数据库、脚本、日志和本轮报告已删除，共 546 个文件（263483372 字节），临时根目录不存在，无须迁移；保留主工作区原有依赖和构建目录。
- 最终 13 个交付文件 SHA256 与冻结版本完全一致，两份用户原有未跟踪文档摘要保持。review 未发现需返工事项；本次精确提交仅含 13 项交付和本台账。C06 无数据迁移，回退恢复本单元代码即可；BS-043、046 完成，下一单元为 C08。

## C08

- 实现任务：`01a0af83-ffc5-7ea2-9f87-2818fa3b07e6`；worktree：`C:/Users/18221/.codex/worktrees/cc49/ruoyiflowable`。
- 起始 HEAD 已核对为 C06 提交 `9b5c60e397d96f1211469e32bcc035079e0dadef`，初始工作区干净，任务已确认运行。Delegate 直接调用所选执行器，SQL／HTTP／独立 CEL 在当次执行内消费已有解析或编译结果，删除前置重复准备及同源规范化比较；同步追溯协作 outbox 等独立调用方，不新增通用 PreparedContext 或提前实施条件路由／SLA。
- 保留冻结协议、checksum、版本／Schema 漂移、实际不同来源关系、Java Handler 契约，以及副作用前的 SQL AST／表／参数、HTTP 端点／凭据／容量、CEL 沙箱及事务／幂等／重试约束。要求真实 Flowable、受控 HTTP 和隔离 SQL 证明执行与拒绝边界，不在生产双执行比较；各层未执行项单独说明。
- 完成后冻结精确白名单和 SHA256，向 leader 发送交付以继续独立 review、主工作区验证和本地提交。临时资源优先清理；无法删除的本任务自有内容按既定授权迁入集中待删目录，不再请求重复批准。
- leader 已独立审查全部 8 项交付及直接／隐式调用方：生产 5 文件 +26/-61，新增正式 H2／MySQL 测试 2 文件共 539 行，现行业务契约 1 行。没有新增生产类型或持久协议。Delegate 删除三条内置分支的前置准备与同源配置比较；SQL 外库幂等校验在已有 validator 中直接消费同一 AST，两个实际调用方同步，无二次解析或新包装层。
- CEL 正式 Delegate 路径仍传播原前置编译产生的具体配置异常，成功的唯一编译产物直接求值；运行变量、类型、求值错误与结果写入顺序保持，evaluateBoolean 未改。HTTP 执行实现未修改，仅更新保留方法的用途注释。已登记 Java Handler 的规范化协议保留；协作 Handler 登记 outbox 与后台 postFrozenJson 投递分别在各自生命周期校验，不重复进入 Delegate 的内置 HTTP 执行链。
- leader 已审查真实作者 BPMN→扩展编译→Flowable 部署→正式制品读取→serviceTask→实际执行器的正向路径，以及损坏冻结资源的拒绝路径。测试证明主库写入随同一 Flowable Job command 的后续异常回滚，EXTERNAL 另一个真实连接的提交不会随引擎回滚且重试保留同一唯一键行；HTTP 每尝试一次请求并保持失败重试 key，拒绝非法端点／凭据／容量、重定向和损坏摘要。CEL 的单次编译计数为辅助，实际求值和拒绝后无输出为业务判据。
- outbox 测试使用生产 Mapper 与真实事务，验证登记回滚、相同活动发生次重复登记、载荷变更冲突、领取后另一 worker 无可领取行、503→RETRYING→再次领取→PROCESSED、旧租约完成拒绝及投递配置损坏的失败审计；不是并发线程竞争测试。目录、密钥来源及扩展注册表使用隔离替身，外部调用本身为真实本机 HTTP／JDBC；没有声称实际第三方系统或完整应用权限目录验收。
- 实现任务最终 clean verify 的默认 625 项（flowable 574、admin 51）及本项 MySQL 13 项一次通过；其他 MySQL 套件未在该次定向运行。leader 按原始 HEAD blob 和冻结磁盘 SHA256 核对后集成，正在主工作区运行全部默认测试及扩展执行、协作 outbox 能力、扩展注册管理三个受影响 MySQL 套件。review 暂未发现需要返工事项，待主审验证通过再提交。
- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it '-Dit.test=WorkflowExtensionExecutionMySqlIT,WfCollaborationOutboxCapabilityMapperMySqlIT,WfBpmnExtensionManagementMySqlIT' -B -ntp`，于 2026-09-17 22:08:14 +08:00 成功（6 分 37 秒）。核对本轮 97 份 XML 及实际 testcase：默认 625 项（flowable 574、admin 51）、MySQL 24 项（扩展执行 13、注册管理 10、outbox 能力 1），全部 0 失败／错误／跳过。未修改任何构建门禁；这是受影响 MySQL 套件的定向验证，不是完整 MySQL profile。
- 本项真实执行测试 H2 10 项、MySQL 13 项通过，后者使用独立随机 schema、实际外部 JDBC 连接及要求 X-Integration-Token 的本机 HTTP 服务。默认回归含独立 CEL 6、HTTP 8、消息抛出 7 项。没有运行真实第三方 HTTP／业务数据库、完整登录或浏览器、生产身份与秘密目录，也未独立诱发 HTTP 超时；超时实现未修改，不声称额外验收。本项无前端或存储格式变更。
- 主库 SQL 随引擎事务回滚，外库提交不随引擎回滚，现有唯一键／no-op 契约保持；HTTP 和协作仍是至少一次发送，稳定 key 不证明远端已经去重。冻结字段、checksum、实现键／Schema 和 Java Handler 协议保持，不需要本单元数据迁移；回退本单元代码不涉及数据库格式切换。C05 常驻业务数据的迁移仍未执行。
- 主审独占 MySQL 8.4.9 位于 33339/approvaplat_it，按正式 14 份基线初始化。最终随机 schema、部署、运行任务／execution、历史实例、协作 outbox／channel／audit 和测试注册表行均为 0；PID 67848（监听）与 68024（启动器）正常退出，端口释放。主审临时数据库、脚本、日志和本轮报告已删除，共 494 文件（235056423 字节），临时根目录不存在，无须迁移，保留主工作区原有依赖和构建目录。
- leader 已复核实现任务 7 个 target、ruoyi-admin/logs 和私有临时目录均不存在，git status --ignored 无忽略遗留；实现任务报告独占 33338 已关闭且没有待删迁移。最终源工作区与主工作区的 8 个交付文件摘要完全相符，两份用户原有文档摘要保持。review 和主审验证通过，精确提交仅含 8 项交付及本台账；BS-020 完成，下一项为 C10。

## C10

- 实现任务：`01a0afb4-8931-7df2-997b-9f7687430ff4`；worktree：`C:/Users/18221/.codex/worktrees/6083/ruoyiflowable`。
- 起始 HEAD 已核对为 C08 提交 `0fc302303e524bf658e0272bd66459ad9a8a25f3`，初始工作区干净，任务已确认运行。条件编译和运行直接使用现有结构化规则，删除仅用于布尔组合的 CEL 配置／声明／编译和同义数据；保留独立 CEL 扩展、DMN 及协作，不新增解释器注册器或另一套部署流水线。
- 要求保留精确原子比较、默认流、排他网关多分支歧义拒绝、包容网关多分支及实际参与字段错误；按正式 AND/OR 允许短路，明确未访问字段与真实缺失字段的区别。现有 conditions-v1.json 与摘要包含 celConfigJson，读写格式必须在本单元同步处理，以旧基线实际生成的制品验证兼容，不能拿新 helper 重造旧数据或保留两个运行解释器。
- 使用真实作者编译、部署资源和 Flowable 实际任务路径及事务验证，补充适当独占 MySQL 与恢复契约。完成后冻结白名单和摘要通知 leader，继续独立 review、主工作区验证和本地提交；任务临时资源按既有删除／集中迁移规则处理。

- leader 已独立审查全部 31 项冻结交付：生产 5 文件 +128/-129，既有 CEL 测试删除专用 evaluateBoolean 用例，新增条件路由 H2／MySQL 正式测试 2 文件，旧部署原始 fixture 22 文件，现行业务契约 1 文件。编译器只冻结结构化规则，路由器直接短路计算两层 AND/OR，原子比较实现保持；所有非默认分支仍参与排他歧义判断，包容多分支和命令内 transient 缓存保持。独立 CEL 扩展的执行、登记及委托路径仍在，未修改 DMN。
- 摘要校验收敛到制品读取边界。新部署统一使用 manifest schemaVersion 2 和 conditions-v2.json；旧 schemaVersion 1 在读取时按原字段和原 celConfigJson 验证旧摘要，再交给相同结构化路由器，不重新编译或执行旧 CEL，也不回写旧制品或伪造新摘要。运行读取仍先选择当前流程／网关的规则，未知版本、缺失资源和损坏摘要拒绝；其他领域资源名与内容协议保持。
- leader 核对旧 fixture 生成器源码和实现任务执行记录：在生产代码修改前，基线编译器 prepare、真实 Flowable 部署及正式 persist 生成原始资源，生成测试于 2026-09-17 22:13:54 +08:00 成功，随后才修改生产代码；生成器已删除。兼容测试直接装载冻结字节，.gitattributes 禁止 Git 对这些 JSON／BPMN 进行换行转换，旧 CEL 字节损坏必须触发原摘要拒绝。
- 正式测试走 WorkflowModelService 保存／预检／部署、制品读取、Flowable 发起与真实任务路径，覆盖 AND/OR 真值组合与短路、精确大数和小数、文本／布尔／标量、默认流、排他歧义、包容多分支、缺失及非法实际字段、命令内缓存与下次命令重算、发起／完成失败的事务回滚、旧部署路由与格式损坏。MySQL 追加引擎重启后继续既有实例及部署删除。身份、类别、表单目录和无关任务监听器为受控替身，不等于完整登录应用或浏览器验收。
- 实现任务最终 clean verify 的默认 650 项（flowable 599、admin 51）及定向 MySQL 40 项（条件路由 27、扩展执行 13）通过，0 失败／错误／跳过。leader 按原始 HEAD blob 和冻结磁盘 SHA256 核对并集成全部 31 文件，因 manifest 版本影响所有领域资源读取者，主工作区正在执行不带测试筛选的完整 workflow-mysql-it profile。实现任务 git status --ignored 无忽略遗留，报告私有 MySQL 33341 已关闭；主审使用独占 33342，常驻业务库和共享测试库保持不动。
- 恢复限制已写入现行契约：没有 v2 部署时可以只回退代码；一旦写入 v2 部署，旧版本不能读取，须保留 v2 读取能力修复，或停写后将数据库和应用一并恢复至发布前一致备份。不能改版本号冒充旧格式、补造 CEL 或重定向在途实例。旧部署仅兼容读取，无在线数据迁移；C05 常驻业务数据迁移仍未执行。

- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it -B -ntp`，于 2026-09-17 23:24:38 +08:00 成功（21 分 37 秒）。逐一核对本轮 126 份 XML 及实际 testcase：默认 650 项（flowable 599、admin 51），完整 MySQL／HTTP 集成 248 项（flowable 244、admin 4），全部 0 失败／错误／跳过，无测试选择过滤或构建门禁变更。这次结果包含全部最终冻结文件，不拼接不同版本的验证。
- 条件路由 H2 26 项、MySQL 27 项全部通过；后者包括真实引擎关闭／重新启动后继续既有实例并完成任务。独立 CEL 5 项、扩展执行 H2 10／MySQL 13 项、组合模型编译、状态迁移 11、变量投影 9、工作台 11、自动抄送 14、多实例连续退回／并发／回滚／终态及系统组织／唯一写入等受影响回归同步通过。admin 4 项是既有 multipart 真实 HTTP 测试；C10 运行中 HTTP、浏览器及真实生产身份目录 not executed，本项无前端变更，未另行运行独立 Redis profile。

- 主审独占 MySQL 8.4.9 位于 33342/approvaplat_it，加载正式 14 份基线。最终随机及其他额外 schema、部署、运行任务／execution、历史实例、抄送、通知 inbox/outbox、SLA 执行及协作 channel/message/outbox/audit 均为 0；监听 PID 68056 与启动器 79248 正常退出，端口释放。主审临时数据库、脚本、日志及本轮报告共 555 文件（283236028 字节）已删除，临时根目录不存在，没有新增待删迁移；主工作区原有依赖、构建目录和较早日志保持。
- leader 已复核实现任务 7 个 target、ruoyi-admin/logs、.c10-temp 均不存在，33341 端口已释放。最终 31 项交付在源工作区和主工作区的 SHA256 全部与冻结版本一致，两份用户原有未跟踪文档摘要保持。review 和完整主审验证通过，精确提交仅含 31 项交付及本台账；BS-024 完成，下一项为 C11。

## C11

- 实现任务：`01a0affa-eedf-7800-9d93-1d053f705bc1`；worktree：`C:/Users/18221/.codex/worktrees/aff0/ruoyiflowable`。
- 起始 HEAD 已核对为 C10 提交 `22d6777021a27c4fe4741f2e42a7a53f7a51829b`，初始工作区干净，任务已确认运行。一个 SLA 建立操作取得并解析一份日历，直接计算同一起点的提醒和升级时间，删除重复查询、丢弃解析结果及日期索引；独立单到期点入口与部署校验同步追溯，不新增通用日历框架或跨请求缓存。
- 保留停用日历只阻止新部署、旧部署建立后续时钟仍可使用当前日历的语义；已有时钟不因更新回写，后续任务读取新配置。要求真实 H2／MySQL、Flowable 任务和定时作业覆盖假日／补班、时区及夏令时、两个分钟数、故障和事务回滚，区分目录替身与真实执行，不修改 C10 新旧制品协议或提前实施其他单元。
- 完成后冻结精确白名单及 SHA256，通知 leader 继续独立 review、主工作区验证和精确本地提交。临时资源正常清理，无法删除的本任务自有内容按用户授权迁入集中待删目录；不得推送或触及常驻／共享服务。

- leader 已审查全部 7 个文件及日历 Mapper、单点 Flowable 业务日历、部署校验与 SLA 创建／定时器直接调用方。生产 2 文件 +61/-30，正式测试及夹具 +471/-4，文档 +1；没有独立生产类或框架。服务内 private CalendarSchedule 保存实际解析结果，SlaDueDates 只返回本次被消费的两个 Instant；运行服务按同一起点、同一结果写入 UTC 到期点，删除第二次日历装载以及 calculate 内的重复解析和日期索引构建。
- 保留原分钟上限 2635200、扫描上限 3660 及原秒级／自然日推进算法，未改变快照、接口、数据库格式、停用处理、重复提醒或升级协议。CalendarSchedule 不逃出服务，集合只用于当前计算；独立 resolveDueAt 和部署 requireEnabled 保持。Mapper 的一次完整日历装载包含既有主记录和日期覆盖查询，不能把一次服务装载夸大为整个引擎命令只有一条 SQL。两个提醒加一个升级的场景实际为 SLA 执行记录读取 1 次、三个独立 Flowable 定时入口各读取 1 次，无跨入口缓存。
- 新增 H2／MySQL 各 22 项复用相同业务判据：通过真实模型保存／预检／部署、认证上下文及 Spring 事务中的 Flowable 发起、正式任务监听器、SLA Mapper／runtime／timer delegate 和通知 Writer，覆盖窗口／跨周、补班／假日、上海时区、纽约春秋夏令时、两个分钟数、停用与更新后续任务、既有时钟和冻结快照不变。时间预期为硬编码 UTC 判据；getter 和 Mapper 计数仅辅助证明解析／装载不重复。
- 定时器测试使用受控引擎时钟及真实 ManagementService 作业执行，证明首次／重复提醒、幂等调用、生成升级任务并完成、审计和必达站内信一致；无效接收人及真实通知表故障分别使提醒／升级回滚，并验证原生失败重试 timer retries=2 和恢复后成功。创建审计故障、后续任务日历损坏也证明任务／执行记录／历史／审计回滚。运行查询在解析 catch 之外，真实 SQL 故障保留 SQLException 原因；生产 Mapper 未启用惰性日期集合装载。
- 身份／类别／表单目录及无关普通通知、抄送、多实例轮次协作者为受控替身，SLA 通知接收人有效性与写入使用真实 SQL。H2 夹具保留必要关系和唯一约束，仅提供相应方言函数／表结构；MySQL 使用正式 14 份基线及原约束。没有声明完整登录、运行中 HTTP／浏览器、外部邮件或自动异步线程持续调度验收，未另跑独立 Redis profile。本项无前端或数据格式迁移，回退本单元代码不回写已存在时钟；C10 制品兼容和恢复限制保持。
- 实现任务最终 clean verify 的默认 672 项和受影响 MySQL 64 项通过，0 失败／错误／跳过；此前按多实例源文件名筛选漏掉实际测试类，已改为真实类名重新完整执行这次最终命令，未将前后结果拼接。leader 已按原始 HEAD blob 和最终磁盘 SHA256 集成全部 7 文件，正在主工作区重新执行全部默认测试及相同受影响 MySQL 套件；没有修改构建门禁。本项选择定向 MySQL 验证，不宣称完整 profile 或 admin HTTP IT。

- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it '-Dit.test=WorkflowTaskSlaCalendarMySqlIT,WorkflowAssignmentMySqlIT,WorkflowNotificationPipelineMySqlIT,WorkflowAutomaticCopyMySqlIT,WorkflowMultiInstanceGroup*MySqlIT,WorkflowMultiInstanceReturnedTerminationMySqlIT,WorkflowMultiInstanceContinuousReturnMySqlIT,WorkflowProcessTerminalStateMySqlIT' -B -ntp`，于 2026-09-18 00:19:13 +08:00 成功（11 分 41 秒）。核对本轮新生成的 107 份 XML 及实际 testcase：默认 672 项（flowable 621、admin 51），受影响 MySQL 64 项，全部 0 失败／错误／跳过；admin 本次没有匹配的 IT，未将其计入 HTTP 验收，没有修改原有门禁。这是完整默认及定向 MySQL 验证，不是完整 MySQL profile。
- 本项 SLA 日历 H2 22／MySQL 22 项均通过；既有候选任务创建 21、条件路由及旧 v1 兼容 26、独立 CEL 5 项默认测试同步通过。MySQL 同时通过分配 5、通知管线 3、自动抄送 14、连续退回 4、并发／重提／退回／回滚各 2、退回后终止 2、流程终态 6 项。真实作业执行和事务判据通过，浏览器／运行中 HTTP、实际外部邮件及自动异步线程持续运行未执行；没有增加这些层级的通过声明。

- 主审独占 MySQL 8.4.9 位于 33344/approvaplat_it，使用正式 14 份基线。最终额外 schema、模型／部署、运行任务／execution、timer／job／deadletter、历史实例／comment、SLA execution／audit、通知 inbox／outbox 和抄送均为 0；监听 PID 81560 与启动器 77684 正常退出，端口释放。本轮主审临时数据库、脚本、日志及报告共 515 文件（250880802 字节）已删除，临时根目录不存在，无新增待删迁移，原有主工作区依赖及构建目录保持。
- leader 复核实现任务 7 个 target、ruoyi-admin/logs 和 .c11-temp 均不存在，33343 端口已释放；实现任务报告删除 1889 文件且没有待删迁移。最终 7 个文件在源工作区和主工作区的 SHA256 均与冻结交付一致，两份用户原有未跟踪文档摘要保持。review 和主审验证通过，精确提交限于 7 项交付和本台账；BS-048 完成，下一项为 L03。

## L03

- 实现任务：`01a0b02c-e641-7f21-9f31-94c675504eef`；worktree：`C:/Users/18221/.codex/worktrees/e580/ruoyiflowable`。
- 起始 HEAD 已核对为 C11 提交 `b7a40ac6eabcd3b296a71a1b4f2f188107a9d1f3`，初始工作区干净，任务已确认运行。补齐现有生成器联表的完整列投影，详情 rows 直接使用本次表对象的 columns，删除详情重复列查询及确无调用的 selectTableByName SQL；保留子表目录 columns、真实按名称查询及独立 /column 入口，不增加缓存或通用元数据层。
- 要求核对父表与列的同名主键／审计字段映射、零列 LEFT JOIN、列顺序、完整 info/rows/tables JSON，以及普通表／树表／主子表编辑保存和真实预览／生成结果。正式 SQL／MyBatis 与独占 MySQL 验证为主要依据，调用计数仅辅助；不修改权限、公开接口、其他单元或构建门禁。
- 完成后冻结白名单和摘要通知 leader，继续独立 review、主审验证与精确本地提交。临时资源按既有正常清理／集中迁移规则处理，不触及常驻及共享服务，不推送。

- leader 已审查全部 5 个文件及生成器直接调用方、原独立列投影和两个编辑组件。生产 2 文件 +24/-14，正式测试 563 行、文档 61 行，无新应用类或存储格式。getInfo 删除单独列读取，rows 直接使用本次表对象的 columns；三条联表查询使用同一完整列片段，并以继承原表映射的局部 resultMap 设置 c_ 前缀及 column_id 非空条件，完整保留列元数据且不从父表创造空列对象。
- 列的 tableId、审计及原有公开字段都来自 c，父表不会接收列审计。三条联表原本未投影的父表审计仍为空，目录原本未投影的 genType/genPath 保持；必要变化是 info.columns、目录及按名称读取的列审计被补齐，已写入交付文档。非联表列表／数据库目录继续使用原 resultMap，保留原顺序和同 sort 值之间未指定的次序。不改变不存在记录、权限、保存或生成入口的既有策略。
- 已确认孤立 selectTableByName 无剩余运行调用；真实 selectGenTableByName、ColumnService／Mapper 和独立列入口仍在。前端 rows 编辑排序并作为 columns 保存、info.columns 提供树字段、tables[].columns 提供子表外键选择的调用契约保持。目录查询有独立用途，详情仍查询当前表和完整目录两次，不宣称整个页面只查询一次。
- 正式 H2 8／MySQL 9 项通过生产 SQL、MyBatis、事务代理服务、真实 Controller 的 MockMvc JSON 和 Velocity，使用独立夹具断言全部字段、类型／排序／审计及选项。覆盖零列、多表重名列和相同 sort、父子审计隔离、独立列接口、非联表列表与 information_schema 目录，以及普通／树／主子表编辑保存重读后具体 Domain／Mapper／Vue 和内存 ZIP 内容。没有以两份同源结果比较替代独立判据，SQL 调用计数仅辅助证明重复读取消失。
- 实现任务最终 clean verify 默认 680 项通过，之后定向 MySQL 构建再次执行默认 680 项并通过 MySQL 13 项（本项 9、既有轮次 Mapper 4），均 0 失败／错误／跳过。leader 按 HEAD 原始 blob 和最终磁盘 SHA256 集成全部 5 文件，使用独占 MySQL 33346 和正式 14 份基线重新执行全部默认及相同定向 IT；不修改任何门禁。测试不代表浏览器、完整登录／权限过滤器／HTTP 服务链、自定义磁盘生成或生成项目编译运行，这些未执行；本项无前端修改和数据迁移。

- 首轮主审默认 680／定向 MySQL 13 项通过后，最终复核发现测试夹具随机 ID 可落入 Integer 范围，而 JSON Map 断言使用 Long 期望，存在偶发类型断言失败。leader 用当前 Fastjson2 2.0.62 验证 101 被解析为 Integer、2147483648 为 Long，已要求实现任务只调整随机 ID 区间，不修改生产逻辑；提交暂停，修订集成后重新执行完整主审命令，首轮结果不冒充最终修订验证。

- 夹具修订只增加 `1L << 40` 起始偏移，保留 40 位随机来源及原间隔；生成 ID 始终超过 Integer 上限，仍在 Long 范围内。leader 已用实际 AssertJ 3.27.7 复现原 containsEntry 对小 ID 的失败，并独立核对修订仅一行、其余 4 个文件摘要不变；修订集成后 5 文件摘要再次一致。实现任务未重建环境，由 leader 在原独占 33346 重新执行完整最终命令，所有新旧本轮报告均纳入清理范围。

- leader 对最终修订执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it '-Dit.test=GenTableDetailMySqlIT,WfMultiInstanceRoundMapperMySqlIT' -B -ntp`，于 2026-09-18 01:01:28 +08:00 成功（3 分 11 秒）。核对最终新生成 99 份 XML 及实际 testcase：默认 680 项（flowable 621、admin 59），定向 MySQL 13 项（生成器 9、既有轮次 Mapper 4），全部 0 失败／错误／跳过，门禁保持。本项 H2 8／MySQL 9 项均包含修订后的 ID 夹具；默认仍包含 C10 26 项及 C11 22 项。最终结果单独成立，不拼接首轮或实现任务修订前的数据。

- 主审独占 MySQL 8.4.9 位于 33346/approvaplat_it，正式 14 份基线。最终额外 schema、生成器两表、L03 临时物理表、轮次夹具及部署／任务／execution／历史实例均为 0；监听 PID 77692 与启动器 38596 正常退出，端口释放。两轮主审拥有的报告合并清点，本次临时数据库、脚本、JSON 类型复现文件、日志和报告共 507 文件（226901972 字节）已删除，临时根目录不存在，没有新增待删迁移；主工作区原有依赖及构建目录保持。
- leader 已复核实现任务 7 个 target、ruoyi-admin/logs 和 .l03-validation 均不存在、33345 端口释放，夹具修订后未重新生成构建产物。最终 5 项交付在源工作区和主工作区均与更新后的冻结 SHA256 一致，两份用户原有未跟踪文档保持。review 修订与最终主审验证通过，精确提交限于 5 项交付及本台账；BS-039 完成，下一项为 L05。

## L05

- 实现任务：`01a0b057-0c6c-7f61-85dc-7e50c6f987d1`；worktree：`C:/Users/18221/.codex/worktrees/196c/ruoyiflowable`。
- 起始 HEAD 已核对为 L03 提交 `5b2719fc67223b93dad95c63f077afbddd2b1175`，初始工作区干净，任务已确认运行。部门／菜单树在本次调用内按 parentId 分组，并使用当前 ID 集合判断编辑树根节点，删除重复全表预扫描和失去调用者的辅助代码；不增加通用树框架或缓存。
- 编辑树保留父节点不在当前输入时的根规则，菜单授权路由只从明确 parentId 开始；保留根／同层顺序、权限裁剪、空列表及无根 fallback，并防止复用对象时遗留旧 children。C07 部门事务及数据范围、菜单 SQL 权限／按钮／链接策略保持；不扩展为循环合法性框架。
- 要求正式服务及 TreeSelect／路由输出判据、完整默认 Maven 和受影响组织权限 MySQL 回归。完成后冻结白名单和摘要，由 leader 独立 review、主审验证与精确本地提交；临时资源继续正常删除，无法删除的自有内容集中迁移到用户指定待删目录，不推送。

- leader 已独立审查两份服务的全部生产 diff、10 项正式默认测试、MySQL 新增用例及说明，并追溯 Controller／Service、原 DataScope 代理、菜单 SQL 与 TreeSelect／Router 序列化和前端直接消费者。生产 2 文件 +46/-84，删除 4 个私有预扫描辅助方法及 2 个 Iterator 导入；没有跨类职责迁移，新状态仅为调用内 Map／Set，菜单两个入口复用局部分组函数，没有公共树框架或缓存。
- 输入对象和根／同层顺序保持，编辑树与指定父级路由分别维持原根策略。每次调用先重置本次输入对象的 children，叶节点和权限裁剪后的对象不带入旧子项；仅连接有效根的可达节点，原无根 fallback 仍返回原列表，不可达闭环保持未连接。正式查询提供唯一主键对象；没有新增循环合法性检查或改变 C07 写入事务。SQL、权限、组件转换、链接／按钮和公开签名均未修改，未作耗时加速声明。
- 默认测试以独立预期验证实际服务输出，包括空树、多根／多层、乱序输入但稳定同层顺序、孤立根、指定父级路由、无根回退、复用对象裁剪及 TreeSelect／Router 完整 JSON。MySQL 使用真实 DataScope、Spring 事务代理及正式 Mapper，新增 2 项部门权限树／菜单授权树验证，原 31 项组织权限、部门移动及并发事务用例保持；测试增量 312 行，说明 49 行。
- 实现任务最终完整默认 clean verify 690 项、定向组织权限 MySQL 33 项通过，均 0 失败／错误／跳过；两次结果分别记录。leader 已按原始 HEAD blob 和最终磁盘 SHA256 集成全部 5 文件，在独占 MySQL 33348、正式 14 份基线上重新执行完整默认及相同定向 MySQL。没有修改门禁；无前端变更，浏览器、完整登录及实际 HTTP、前端构建和性能基准未执行，不以服务 JSON 测试替代这些层级。

- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it '-Dit.test=SysOrganizationScopeMySqlIT' -B -ntp`，于 2026-09-18 01:37:01 +08:00 成功（3 分 22 秒）。逐份核对本轮新生成 99 份 XML 及实际 testcase：默认 690 项（flowable 631、admin 59），定向 MySQL 33 项，全部 0 失败／错误／跳过，testcase 节点也无失败／错误／跳过。默认包含本项树组装 10 项和 L03 生成器 8 项；MySQL 包含本项新增 2 项及原组织权限／部门移动 31 项。本结果为完整默认与定向 MySQL，admin 本次无匹配 IT，不宣称完整 MySQL profile 或真实 HTTP 验收。

- 主审独占 MySQL 8.4.9 位于 33348/approvaplat_it，正式 14 份基线；最终额外 schema 及本次组织权限／树组装自有部门、用户、角色、菜单夹具均为 0。监听 PID 81936 与启动器 37700 正常退出，端口释放。本次临时数据库、脚本、日志和报告共 499 文件（226990839 字节）已删除，临时根目录不存在，没有新增待删迁移；主工作区原有构建目录及依赖保留。
- leader 已复核实现任务 7 个 target、ruoyi-admin/logs、l05-focused.log 和 .l05-validation 均不存在，33347 端口释放；实现任务报告删除 1764 文件且无需待删迁移。最终 5 项交付在源工作区和主工作区均与冻结 SHA256 一致，两份用户原有未跟踪文档摘要保持。review 无阻断项，主审验证通过，精确提交仅含 5 项交付和本台账；BS-042 完成，下一项为 L06。本项无数据迁移，恢复两份服务及对应测试／说明即可撤回。

## L06

- 实现任务：`01a0b073-561c-7d33-8ba3-b6384f1744d1`；worktree：`C:/Users/18221/.codex/worktrees/f4f9/ruoyiflowable`。
- 起始 HEAD 已核对为 L05 提交 `950c3957c87ab7002bde52e162a1c1c99dcd88c9`，初始工作区干净，任务已确认运行。限定 WorkflowNotificationInboxService 的 UNREAD 分页 total 复用本次已取得的未读计数，ALL／READ 继续分别读取筛选总数及全局未读角标；不改变通用分页、事务、身份、已读写入口和公开字段，不增加缓存。
- 要求真实生产服务与 SQL 验证用户隔离、状态规范化、分页／空集／越界页及不同筛选的独立结果，查询次数仅辅助证明重复 SQL 消失。完整默认和适当定向 MySQL 通过后冻结白名单，交由 leader 独立 review、主审验证并精确提交；临时资源继续正常清理，无法删除时迁移到集中待删目录，不推送。

- leader 已审查完整 2 文件交付及收件箱服务、Controller、分页助手、结果对象和 HeaderNotice 消费链。生产只新增 4 行：UNREAD 的 countSupplier 返回本次已读取的 unreadCount，取消同参数的第二次 COUNT；ALL／READ 仍分别读取筛选总数与未读角标。没有物理删行或职责迁移，没有新缓存、字段、中间层、接口或存储格式；事务、校验顺序、已读入口与分页助手保持。
- 新增正式 MySQL 测试 224 行、5 个用例，使用正式基线表、真实身份 Mapper／Resolver、生产收件箱服务和 Spring 事务代理。随机三个用户、交错通知 ID 的独立预期验证各筛选 rows／total／unreadCount、用户隔离、倒序分页／尾页／越界页／空集、状态规范化、非法状态和分页边界；单条／全部已读提交后的新请求重新计数，跨用户操作返回 404。实际 JDBC 日志仅辅助证明 UNREAD 一次 count、ALL／READ 两次及物理分页，无 mock SQL 返回或通用探针框架。
- 实现任务最终默认 clean verify 690 项，随后定向 clean verify 再次独立执行默认 690 项和收件箱 MySQL 5 项，全部 0 失败／错误／跳过；没有拼接轮次或改变门禁。leader 已按原始 HEAD blob 和最终磁盘 SHA256 集成两文件，在独占 MySQL 33350、正式 14 份基线上重新执行完整默认及本项定向 IT。真实 HTTP／登录浏览器／UI 操作和完整 MySQL profile 未执行，不宣称通知写入管线或 SMTP 新验收。

- leader 主工作区执行 `mvn -pl ruoyi-admin -am verify -Pworkflow-mysql-it '-Dit.test=WorkflowNotificationInboxMySqlIT' -B -ntp`，于 2026-09-18 02:00:45 +08:00 成功（3 分 12 秒）。核对本轮新生成 99 份 XML 及实际 testcase：默认 690 项（flowable 631、admin 59），定向收件箱 MySQL 5 项，全部 0 失败／错误／跳过，逐 testcase 节点也无失败／错误／跳过。默认包含 L05 树组装 10 项及 L03 生成器 8 项；本次 admin 没有匹配 IT，不将其声明为真实 HTTP 验收。最终结果单轮独立成立，未改变门禁。

- 主审独占 MySQL 8.4.9 位于 33350/approvaplat_it，正式 14 份基线；最终额外 schema、收件箱记录及本项自有用户均为 0。监听 PID 77580 与启动器 34960 正常退出，端口释放；本轮临时数据库、脚本、日志及报告共 499 文件（226585315 字节）已删除，临时根目录不存在，没有新增待删迁移，主工作区原有构建目录和依赖保持。
- leader 已复核实现任务 7 个 target、ruoyi-admin/logs 及独占临时根目录不存在、33349 端口释放；实现任务报告删除 1908 文件且没有待删迁移。最终两项交付在源工作区和主工作区与冻结 SHA256 一致，两份用户原有未跟踪文档摘要保持。review 无阻断项，最终主审验证通过，精确提交仅含两项交付及本台账；BS-047 完成，下一项为 L07。无数据迁移，撤销生产 4 行与对应测试即可恢复。

## L07

- 实现任务：`01a0b088-d530-7591-ab64-794316621466`；worktree：`C:/Users/18221/.codex/worktrees/e06d/ruoyiflowable`。
- 起始 HEAD 已核对为 L06 提交 `5ef2fe237800e4936e109c896945b71a07173e30`，初始工作区干净，任务已确认运行。限定 button.svg 精确删除一个多余的闭合标签，保留路径、属性、名称和引用；不改资源加载系统或新增永久测试文件。
- 要求严格标准 XML 前失败／后成功证据、恰好删除 7 字节、生成器图标消费链及生产构建符号核对，并执行正式前端 contracts／lint／build:prod。本项无 Java 修改，不重复 Maven／MySQL；未执行浏览器不冒充可见故障或 UI 验收。冻结唯一文件后由 leader 独立 review、主审验证并精确提交，任务临时资源按既有删除／集中迁移规则处理。

- leader 已独立核对唯一 SVG 交付、生成器 tagIcon、构建页／属性面板、SvgIcon、生产插件与注册入口。原文件 2370 字节，最终 2363 字节；仅删除零基偏移 2357 的 7 字节 `</path>`，所有其余字节及无末尾换行状态保持。源路径仍为 1 条、1971 字符，SHA256 为 `2011c95c9914edb22ff6ec997306fef9b00d237a771f82b4cee7ced2d0cf5b37`。没有新增生产逻辑、职责迁移、正式测试或说明文件。
- leader 使用严格 .NET XmlReader／XmlDocument，忽略外部 DTD 且 XmlResolver=null，独立复现原文件标签不匹配（第 1 行位置 2360），修复后严格 XML 解析成功；不是 HTML 容错解析。按原始 HEAD blob 和冻结 SHA256 集成唯一文件，源工作区与主工作区最终摘要均为 `d974d982784004d732aa6e7ecc006ba48d12640f03ba4ca593bdf34504aa7ada`。
- 实现任务前端 contracts 129 项、lint、build:prod 全部通过。leader 主工作区再次运行 `npm run test:contracts`、`npm run lint`、`npm run build:prod -- --outDir C:/Users/18221/.codex/tmp/l07-leader-review-20260918/dist --emptyOutDir`，均退出 0：contracts 129 项通过，0 失败／取消／跳过／todo；lint 无诊断；Vite 6.4.3 转换 3159 模块，构建 21.31 秒，未见 warning/error。产物输出到独占临时目录，原有 dist 420 文件逐一 SHA256 核对保持。
- leader 扫描新产物 195 个 JS，唯一 icon-button 位于 index.html 直接引用的 `/static/js/index-Dtzyp8kk.js`；symbol 严格 XML 解析通过，viewBox 和单条非空路径保持。逐 token 核对路径的 38 个命令、311 个数值、349 个 token 全部与源文件一致；入口包 SHA256 为 `0f25868fadeda4b2151a0a07c41b6f351841f9f1888a28a6ff700c5b02b3055c`。本项没有 Java 变更，不重复 Maven／MySQL；浏览器与业务端到端未执行，不据构建成功推断此前发生可见图标故障。
- 实现任务自有 node_modules、dist 和 .l07-validation 均不存在，报告删除 29162 文件且无需集中迁移。leader 已删除独占脚本、日志、核验记录及产物 430 文件（9238291 字节），临时根目录不存在；主工作区原有 node_modules／dist 保留。两份用户原有未跟踪文档摘要保持；review 与主审验证通过，精确提交仅含 SVG 和本台账，A-001 完成。

## 收尾

21 个实施单元已依次完成实现任务交付、leader 独立 review、相应验证和精确本地提交，覆盖 BS-016～BS-049 共 34 项及 A-001；没有推送。当前最终后端源码最近一次完整默认验证为 690 项通过，L06 定向 MySQL 5 项通过；各其他单元的 MySQL／Redis／前端证据及边界分别保留在对应章节，不把不同轮次结果累计为一次全量验收。最后前端 contracts 129 项、lint、生产构建及按钮图标 XML／产物验证通过。

正式业务数据库迁移和生产部署未执行。C12 唯一约束及 C05 状态迁移仍按各自迁移说明处理；C10 新版制品部署后的恢复限制保持，不能把代码回退当作已部署 v2 制品的数据恢复。各项未执行的真实 HTTP／浏览器等层级以对应章节为准。

集中待删目录 `D:/ruoyiflowable-pending-delete-20260917` 最终保留 5 个任务分目录：C03-admin-logs、C05-admin-logs、C05-build-outputs、C05-temporary-resources、C09-admin-logs，共 1860 文件、401387851 字节，未纳入 Git；现场未发现引用该目录的 mysqld 进程。按用户要求保留这些已迁出的临时内容，交由用户最后统一删除。本轮后续单元没有新增迁移；用户原有两份未跟踪文档保持原样。

## 基线验证

2026-09-16 主工作区实施前：`mvn test -B -ntp` 509 项通过，0 失败／错误／跳过；前端 101 项 contracts、lint、build:prod 均通过。基线结果只用于回归对照，不能作为其他待实施单元的验收。

原有计划与覆盖台账在本轮开始时均未跟踪，保留原内容；实际进展以本表与相应代码提交为准。
