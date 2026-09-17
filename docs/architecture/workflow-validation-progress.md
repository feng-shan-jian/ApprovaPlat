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
| 11 | C09 抄送与通知 | BS-029、032；BS-022 通知部分 | leader review 与主工作区验证通过；按用户指定方式集中待删除日志，对应本次提交 |
| 12 | C05 状态与退回重提 | BS-023、026 | 待实施 |
| 13 | C03 详情、等待与变量 | BS-027、028、049 | 待实施 |
| 14 | C06 工作台列表 | BS-043、046 | 待实施 |
| 15 | C08 扩展执行 | BS-020 | 待实施 |
| 16 | C10 条件路由 | BS-024 | 待实施 |
| 17 | C11 SLA 时钟 | BS-048 | 待实施 |
| 18 | L03 代码生成详情 | BS-039 | 待实施 |
| 19 | L05 部门／菜单树 | BS-042 | 待实施 |
| 20 | L06 未读通知分页 | BS-047 | 待实施 |
| 21 | L07 图标格式 | A-001 | 待实施 |

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

## 基线验证

2026-09-16 主工作区实施前：`mvn test -B -ntp` 509 项通过，0 失败／错误／跳过；前端 101 项 contracts、lint、build:prod 均通过。基线结果只用于回归对照，不能作为其他待实施单元的验收。

原有计划与覆盖台账在本轮开始时均未跟踪，保留原内容；实际进展以本表与相应代码提交为准。
