# 下一阶段：业务职责归位与测试装配收敛

日期：2026-10-03。分析基线：`7508665bb7514af28bb899f868a87ddb1f1c0e17`。

状态：**N00～N09 及本轮收尾已完成**。实际变更、断言迁移、最终回归和真实业务链证据见[执行结果](D:/ruoyiflowable/docs/plans/architecture-boundaries-execution-result-20261003.md)，三项收尾修正及 155 个后端测试、3 条真实链的复验证据见其第 7 节。以下保留规划时的分析与执行合同。

## 1. 核心判断

下一阶段应先完成模块化单体的职责整理。继续保持一个应用进程和现有事务边界，把规则、状态和执行过程交给明确的所有者，让局部业务可以独立理解和验证。

上一阶段已完成 36 个清理条目；另外 2 项经实验确认应保留。当前最明显的问题集中在模型服务、通知规划、附件清理、详情资格判断及前端刷新编排。草稿发起、任务完成、部署快照仓库等已有合理边界，不应因类较大或构造参数较多就重新拆分。

本阶段包含 **8 个生产职责调整包、1 个测试拆分包、1 个最终验收包**。每个生产包都必须迁走并删除旧实现，实际减少依赖。新增类只是承载迁移后的职责，不作为完成指标。

前序依据：

- [第一阶段清理计划](D:/ruoyiflowable/docs/plans/architecture-cleanup-execution-plan-20261002.md)
- [第一阶段执行结果](D:/ruoyiflowable/docs/plans/architecture-cleanup-execution-result-20261002.md)

本轮判断来自当前源码、调用方、Mapper、测试装配和第一阶段验证记录。没有重新运行后端测试、浏览器、服务或数据库。

## 2. 当前结构与阶段目标

### 2.1 Maven 结构

现有 7 个 Maven 模块没有发现模块依赖循环。主要依赖为：

```text
ruoyi-admin → ruoyi-framework、ruoyi-flowable、ruoyi-quartz、ruoyi-generator
ruoyi-flowable → ruoyi-framework、ruoyi-system
ruoyi-framework → ruoyi-system
ruoyi-system / ruoyi-quartz / ruoyi-generator → ruoyi-common
```

本阶段保留 7 个模块，目标删除 `ruoyi-flowable → ruoyi-framework` 这条依赖。业务包之间存在设计、运行方向混杂的引用，但不能据此认定存在 Spring Bean 循环依赖。

### 2.2 需要改变的依赖

| 当前关系 | 完成后关系 | 可验证的收益 |
|---|---|---|
| 作者字段目录 → 模型命令总服务 → CallActivity 运行相关服务 | 作者字段目录 → 表单来源解释、纯字段规则 | 字段规则无需启动流程或 DMN 引擎 |
| 模型 CRUD/版本服务直接装配全部编译器 | 模型命令 → 制品准备；制品准备 → 各编译器 | 元数据和版本测试不再直接装配各领域编译器 |
| 详情 → 退回、重提写应用服务 | 详情与命令共同调用准备规则 | 资格判断无需装配审计、附件、抄送、通知 |
| 附件 Coordinator → 上传/权限/绑定总服务 | 附件业务服务 → 已有 Coordinator → 清理资源 | 定时清理独立于上传和对象授权装配 |
| 通知 Planner 读取 Task、引擎、线程身份 | 通知入口冻结事实 → Planner | 规划测试不再需要 Flowable 三类 Service |
| 工作流 → Web 权限服务、TokenService | 工作流 → 系统权限判定；admin 注入派生密钥 | 删除一条 Maven 依赖，保留权限和密文合同 |
| 页面多个入口共同修改请求槽、计时器和序列 | 一个详情观察控制器负责调度与响应提交资格 | 请求竞争可脱离整页组件验证 |
| 协作 Outbox → BPMN HTTP 执行器 → 模型目录服务 | Outbox、BPMN 适配器 → 共享 HTTP 执行能力 | 发送协议无需装配模型发布与任务引擎 |

### 2.3 实施约束

- HTTP 路径、请求响应、数据库结构、部署资源名称与格式、BPMN 表达式引用、调度入口保持稳定。
- 对象授权、人员资格、状态迁移、revision/CAS、错误码和事务仍由后端负责。
- 保留 DMN、子流程、多实例、连接器、事件、可靠协作、SLA、通知等现有能力。
- 不引入新的 Maven 模块、消息总线、通用执行器、全局 Context、接口/实现/门面套层或万能测试工厂。
- 不恢复已经删除的 EngineAdapter、TaskWriteHook 和历史兼容分支。
- **T-08 附件绑定时再次核验有效期、I-14 连接器认证探测原锁事务必须保留。** 新计划不得重新以“重复检查”或“缩短事务”为由删除。
- 生产变更、测试迁移、必要新增、纯删除分别统计；不设置行数、类数或构造参数数量的硬指标。

## 3. 各业务链的处理结论

这张表用于确定本阶段范围；“保留”表示当前证据不支持新增职责拆分，不表示未来不会出现维护问题。

| 业务链 | 当前主要所有者 | 本阶段处理 |
|---|---|---|
| 分类、表单模板、基础模型元数据 | 各目录服务与 ModelService | 保留 CRUD；模型编译装配交 N03 |
| 模型设计草稿、所有者与双修订 | ModelDesignDraftService | 生产边界保留；独立合同测试迁出，N00 |
| 作者字段目录、模板解释、CallActivity 父字段 | ModelService、CallActivityReferenceService | N02 |
| 预检、发布、编译、冻结资源 | ModelService、各领域编译器 | N03 |
| 部署资源持久化与按次惰性读取 | DeploymentArtifactRepository | 保留单一所有者，不再拆 codec/store/cache |
| 条件、受控循环、参与者、DMN、日历编译 | 各领域编译器 | 保留；通过 N03 统一制品准备入口 |
| 草稿保存、附件协调、提交幂等 | ProcessDraftService | 保留草稿状态所有权与锁序 |
| 实例启动、部署复核、附件绑定 | ProcessStartService | 保留；通过 N09 验证真实发起链 |
| 完成、拒绝、认领、转办、委派 | CompletionApplicationService、TaskActionService | 保留可直接阅读的事务编排 |
| 退回、申请人修改、重新提交 | Return/Resubmit ApplicationService | N04，只迁共享准备规则 |
| 多实例轮次、动态加减签、并发迁移 | MultiInstance 相关服务 | 保留 revision 与迁移所有权 |
| 详情授权、运行事实、历史和表单投影 | ProcessDetailService 及投影组件 | N04 去除写服务依赖；已有投影保留 |
| 表单字段校验、提交快照和展示 | StartVariableValidator、SubmissionSnapshotCodec、FormDetailProjection | 保留三种不同合同，不另造统一表单服务 |
| 上传、附件权限、绑定与对象存储 | AttachmentService、Storage、UploadJournal | 上传绑定保留；清理职责归位 N01 |
| 通知策略、接收人事实和通道规划 | NotificationService、Planner | N05 |
| 通知写入、已读、Outbox 投递状态 | Writer、InboxService、OutboxService | 保留各自写入范围，N05 不改投递状态机 |
| SLA、必达站内信、普通运行事件账本 | 各自运行服务 | 保留；必达通知不转成可关闭的普通策略 |
| 可靠协作的出站、游标、接收去重 | CollaborationOutbox/Channel/Message | 保留状态机；共同 HTTP 执行能力归位 N08 |
| HTTP/SQL 目录、凭据、部署冻结与运行发送 | model/process/extension 中的集成相关类 | N08 |
| 详情页轮询、手动刷新、缓存恢复和切任务 | detail.vue | N07 |
| 设计器各领域编辑、撤销重做、目录页面 | designer 各 domain、各管理页 | 保留第一阶段收敛结果，不整体改写 |
| 用户、角色、部门、菜单与权限 | system；Web 权限目前在 framework | 主数据归属保留；共享权限判定 N06 |
| 邮件配置和凭据加解密 | MailConfigService、MailCredentialCipher | 配置所有权保留；密钥装配 N06 |
| 短信、OSS、公告、字典、缓存、在线用户 | system/framework 各现有服务 | 保留第一阶段结果；不新增 SmsFacade 等包装 |
| Quartz、代码生成、登录基础设施 | quartz/generator/framework | 保持现有职责，本阶段不追加功能重构 |

数据归属保持单一：系统域写用户、角色、部门、菜单；工作流身份查询读取这些主数据，不复制第二份组织状态。`sys_mail_config` 目前由工作流邮件配置服务写入，不能因表名前缀再造一个写入者。部署快照是 Flowable 部署资源，不将 Java 快照记录误当成独立业务表。

## 4. 任务清单和顺序

所有任务初始状态均为“未开始”。优先级表示实施顺序和收益，不等同于线上故障严重度。以下主要职责问题的证据置信度均为高；N08 的最终文件迁移范围需要先完成包内调用图确认。

| ID | 任务 | 优先级 | 前置条件 | 独立验收结果 |
|---|---|---|---|---|
| N00 | 拆出被组合测试包住的独立合同 | 高 | 无 | 草稿、资源、DMN 删除等合同可独立运行 |
| N01 | 附件清理归回现有 Coordinator | 高 | 无；先补清理协议覆盖 | 清理不依赖上传/授权总服务 |
| N02 | 作者字段目录和纯字段规则独立 | 高 | N00 | 目录真实规则测试无需流程引擎 |
| N03 | 制品准备与模型命令分离 | 高 | N02 | 模型命令不再直接装配全套编译器 |
| N04 | 退回/重提准备规则脱离写编排 | 高 | 无 | 详情不依赖两个写应用服务 |
| N05 | 通知入口冻结事实、Planner 去引擎依赖 | 高 | 无 | Planner 不再读引擎和线程身份 |
| N06 | 工作流移除 framework 直接依赖 | 中高 | 无；先验证权限及密文合同 | Maven 依赖边实际删除 |
| N07 | 详情观察状态交给单一所有者 | 中高 | 无；保留现有浏览器反例 | 所有刷新入口共享同一请求调度 |
| N08 | 集成目录、冻结与共享 HTTP 执行归位 | 中 | N03、N06；避开 N05 测试装配修改 | Outbox 不再依赖 BPMN 执行器 |
| N09 | 汇总边界和真实业务链验收 | 必须 | N00～N08 | 最终版本合同、并发与真实界面证据齐全 |

建议首批执行 N00、N01、N07，得到独立测试和两个集中范围的职责调整；随后执行 N02、N04、N05、N06；N03 接在 N02 后；最后执行 N08、N09。

并行任务使用独立 checkout。表中“无前置条件”只表示没有逻辑前置依赖，不表示文件一定没有交集。启动前登记实际文件清单：ModelCompilation 测试相关的 N00/N02/N03 串行；通知入口构造签名、任务链夹具、详情事实测试可能被 N01/N04/N05/N06 同时触及，合并时逐个更新并复跑受影响测试。不要在同一个脏工作区让多个任务同时改这些夹具。

## 5. 各任务的执行合同

### N00 — 独立合同从组合发布测试中迁出

**问题与证据。** [WorkflowModelCompilationIntegrationTest.java:285](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowModelCompilationIntegrationTest.java:285) 的组合测试装配引擎、DMN、各编译器和运行服务，随后还调用草稿所有者/CAS/DTD、DMN 删除、扩展版本及资源损坏读取等独立断言。这些断言只有完整发布和运行前置成功后才能执行。草稿 helper 的调用和主体分别在该文件 388、761 行，另一批独立断言调用在 454 行。

**执行。**

1. 按业务合同迁入正式独立测试，建立“原断言 → 新用例”的对应清单。允许合并同一合同的场景，不要求一个 helper 一个文件。
2. 草稿用最小模型、H2 和真实事务；DMN 删除仅装配引用门禁需要的部署资源；扩展版本仅装配 registry/mapper/必要 handler；资源损坏与惰性读取使用最小 RepositoryService 夹具。
3. 原组合测试保留跨编译器交互、真实执行、冻结引用、读取次数、编译次数与原子回滚断言。
4. 删除已迁出的 helper 调用及其独占装配，不复制保留两份。

**文件范围。** 上述测试、必要的新增正式测试；复用 [WorkflowFlowableEngineTestSupport.java:20](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/testsupport/WorkflowFlowableEngineTestSupport.java:20) 的基础引擎/事务支持。生产代码不变；不增加默认装配所有业务的测试工厂。

**验收。** 草稿和资源合同可单独运行；草稿测试没有 DMN/SLA/扩展编译器装配；原组合测试仍证明发布后真实运行和回滚。测试总数变化不作为收益，记录各合同所需协作者和可独立执行的命令。

### N01 — 附件清理归回现有 Coordinator

**问题与证据。** [WorkflowAttachmentService.java:332](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentService.java:332) 编排手动清理，[WorkflowAttachmentCleanupCoordinator.java:55](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentCleanupCoordinator.java:55) 编排批量清理；token、lease、retry CAS 实现又在 AttachmentService 的 620～780 行。清理所有权分散，Scheduler 间接依赖上传、权限和绑定服务。当前未找到 Coordinator 清理协议的正式测试。

**目标。** AttachmentService 保留 owner/状态校验和 TEMP→DELETED；已有 Coordinator 负责领取、物理删除、成功回写与重试。UploadJournal 保留未完成上传日志回收。

**迁移、删除和新增。**

- 将清理实现及手动清理编排迁入 Coordinator，使其直接依赖 Mapper、Storage、Properties、UploadJournal 和事务管理器。
- AttachmentService 调用 Coordinator；删除 Coordinator 对 AttachmentService 的依赖和原服务中已迁移的方法。
- 共用物理删除和 token 回写实现，保留手动失败抛出；批量仅在存储删除失败且重试记录成功后继续，重试落库失败及其他状态损坏仍按原行为抛出。
- 不新增 CleanupService、Worker 或 Repository 接口。

**保留合同。** 手动删除入口保持 NOT_SUPPORTED，原逻辑删除、领取和回写按既有自动提交语义完成；定时批次保持领取/回写的 REQUIRES_NEW 短事务。存储 IO 不持有数据库事务，后续清理失败不能回滚已经提交的逻辑删除。过期 token 不覆盖新租约；重试计数使用 CAS；并发完成和重复完成仍幂等。不能为了统一调用给整个 Coordinator 加一个事务。

**文件范围。** 上述两个生产类、直接构造调用方、正式附件测试。调度协议、表结构、上传与绑定算法不变。

**验收。** 先补手动成功/重复成功、删除失败与重试持久化、重试落库成功后批次继续、重试落库失败仍抛出、重新领取后旧 token 回写、并发完成、IO 事务状态测试，再迁移。MySQL 验证领取竞争、SKIP LOCKED 和 token CAS；H2 只承担业务分支。将 [WorkflowProcessStartChainIntegrationTest.java:367](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessStartChainIntegrationTest.java:367) 的 T-08 真实 MySQL 反例迁入最小附件夹具，删除无关流程启动装配，保留自然跨过有效期后 bind 返回 409 的断言。绑定时机和 MI revision 顺序不改。

### N02 — 作者字段目录和纯字段规则独立

**问题与证据。** [WfModelController.java:217](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/workflow/WfModelController.java:217) 的目录入口进入 [WorkflowModelService.java:1038](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowModelService.java:1038)，后者调用 [WorkflowCallActivityReferenceService.java:541](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowCallActivityReferenceService.java:541) 的纯字段投影，却需要跨过带引擎、身份、运行参与者依赖的服务。

**目标及最小内部 API。** 拟新增具体 `WorkflowAuthorFieldCatalogService.catalog(xml)`，Controller 直接调用；模板来源解释提供包内 `resolveForms(references)`，供保存和发布复用。拟新增包内 `WorkflowCallActivityFields` 承接真实纯字段提取、合并和视图转换。

**迁移和删除。**

- 从 ModelService 迁出目录入口、格式化、现有 `loadSnapshotSources` 及来源记录；不保留转发入口。
- 从 CallActivityReferenceService 迁出 `parentFieldCatalogs`、字段提取/合并、`toVariableViews` 及 `FieldSpec`。目标定义解析、授权、冻结引用和 IO 映射校验留在原服务。
- 保存、目录、发布只保留一个模板解析实现；目录按节点处理错误并共享本次模板缓存，严格保存/发布仍执行各自门禁。
- 条件字段索引已有轻量实现，继续使用，不新增规则接口。

**保留合同。** 未完成的作者图可查目录；单节点错误不抹掉其他节点结果；目录不抢发布锁、不部署 DMN；同模板一次请求只读和解析一次，不同节点分别应用权限；CallActivity 同名同型字段权限取交集、必填取并集，异型冲突语义不变；VO、顺序、错误粒度不变。

**文件范围。** 上述 Controller、ModelService、CallActivityReferenceService、两个拟新增具体类及相关正式测试。新类位于现有 model 业务包，不扩大目录迁移。

**验收。** 新增真实目录规则测试，覆盖部分坏节点、重复模板、节点权限差异、同名异型、标量和子流程映射字段。无需 ProcessEngine、DMN、运行参与者、SLA 或扩展执行器。当前前端目录 stub 不能替代这些后端语义测试。

### N03 — 发布制品准备与模型命令分离

**问题与证据。** [WorkflowModelService.java:747](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowModelService.java:747) 拥有完整编译顺序，683 行起组装八类冻结资源，1153 行起校验跨域引用，同时还负责模型 CRUD、版本和元数据。当前已复用准备结果，不再以“重复编译”为问题依据。

**目标。** 拟新增具体 `WorkflowPublicationArtifactsService`，只承接作者引用校验、制品准备和已有 deploymentId 下的资源冻结。内部 API 控制为：

```text
validateAuthorReferences(document, identity)
prepare(document, identity) -> PreparedDeployment
freezeArtifacts(deploymentId, prepared, identity) -> WorkflowDeploymentArtifacts
```

**迁移和删除。** 迁出 `prepareDeployment`、准备结果、`validateSaveReferences`、字段/循环 schema 构建、`buildSnapshots` 及资源组装；复用 N02 的表单来源解释。删除 ModelService 对各编译器的直接字段/构造依赖和旧实现。准备结果只表示本次调用产物，不建立通用 Context 或编译阶段注册器。

ModelService 保留修订/类别/来源归属检查、事务入口、主部署创建、模型部署关系、旧定义生命周期处理。精确冻结目标定义对旧定义停用的约束也保留于相应生命周期编排。[WorkflowDeploymentArtifactRepository.java:93](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDeploymentArtifactRepository.java:93) 继续唯一持有 manifest、资源持久化和操作内惰性读取。

**保留合同。** [deployModel:638](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowModelService.java:638) 的同一事务覆盖 BPMN、DMN、八类快照、模型关联及旧定义停用；预检仍锁定版本并生成相同内存快照和执行制品，但不创建 BPMN/DMN/资源子部署、不持久化冻结资源、不改修订；作者 XML 与执行模型分离；每类编译一次；资源格式、checksum 和冻结版本不变。不增加异步发布、REQUIRES_NEW 或事件总线。

**文件范围。** ModelService、新制品服务、N02 包内调用点、直接装配与模型正式测试。必须在 N02 后串行执行。

**验收。** 模型元数据/版本测试不再直接装配条件、循环、参与者、DMN、SLA 和监听器编译器；保留组合测试中的一次读取/一次编译、精确子流程和真实执行；在资源写入后注入失败，主部署、DMN 与模型关联全部回滚。旧定义停用不冻结历史运行实例，被父流程冻结引用的子定义保持可调用。

### N04 — 退回/重提准备规则脱离写应用服务

**问题与证据。** [WorkflowProcessDetailService.java:308](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessDetailService.java:308) 为 capability 调用退回和重提写服务。规则分别位于 [WorkflowTaskReturnApplicationService.java:212](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskReturnApplicationService.java:212) 和 [WorkflowApplicationResubmitApplicationService.java:140](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowApplicationResubmitApplicationService.java:140)。[WorkflowRuntimeFactReuseIntegrationTest.java:107](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowRuntimeFactReuseIntegrationTest.java:107) 必须先装配两个完整命令服务才能装配详情。

**目标。** 最多新增两个具体准备组件，分别承接退回资格/安全路径/执行计划，以及申请人待修改资格/原办理配置/重开计划/开始表单与旧快照关联。详情和命令使用同一准备实现。

**迁移和删除。** 迁出已有无副作用准备方法与计划 record；详情直接调用准备组件，删除原 ApplicationService 的 capability 入口和已迁移实现。写事务入口、写入异常翻译、审计、Flowable 迁移、附件、抄送和通知仍留在命令服务。重提 `prepareFormPlan` 中的客户端补丁、附件锁定与本次提交投影留在写链。capability 读入口必须保留已有 readWithServiceExceptionHandler：在事务代理内部完成允许的异常降级，不能改成详情在代理外 catch 后返回 false，导致共享事务已被标记为 rollback-only。

**保留合同。** ReturnedTaskStateService 继续拥有 returned 状态及原办理配置；MI GroupTransition 继续拥有组迁移。准备组件复用详情已读取的 BPMN、历史、activeTasks，不按 ID 重查整条对象链。查询“不允许”和命令异常行为、400/403/409/500 及检查顺序保持；关联损坏的 500 不吞成 capability=false。

**文件范围。** 上述三个生产服务、最多两个新具体组件、直接构造它们的正式测试。其他任务动作、MI 内核、表单字段规则和通知实现不变。

**验收。** 准备规则资格矩阵不装配审计、附件、抄送、通知；现有 [WorkflowTaskReturnChainIntegrationTest.java](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/task/WorkflowTaskReturnChainIntegrationTest.java) 保留普通退回/重提、配置恢复、并行拒绝无副作用、同毫秒历史排序；事实复用测试保留读次数、详情/动作一致性，以及 capability=false 后共享详情事务可正常完成的断言。无需宣称已消除 MI 的全部传递依赖。

### N05 — 通知事实在入口冻结，Planner 去除引擎依赖

**问题与证据。** [WorkflowNotificationService.java:113](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationService.java:113) 仍向 Planner 传 Task；[WorkflowNotificationPlanner.java:49](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationPlanner.java:49) 注入 RuntimeService、HistoryService、TaskService、IdentityResolver，312/344/419 行分别现场读取候选、发起人和身份。请求记录尚未真正冻结全部事实。

**目标。** 复用现有 NotificationRequest 及四个命名工厂，只传事件、实例/定义/任务标识与名称、taskRecipientUserIds、actorUserId、initiatorUserId、routePath、contentSuffix 等值。Planner 仍负责策略/偏好读取和规划，不把整个类描述成无数据库依赖的纯函数。

**执行顺序必须保留。** 校验并规范事件请求 → 选择本批启用策略 → 按需冻结事实 → 使用同一策略快照规划。无策略也要保留原事件类型、sourceId、定义/实例标识和路由校验。使用现有 Policy/PolicyKey，由 Planner 校验并选择本批策略，NotificationService 冻结命中策略需要的事实，再由 Planner 使用同一批策略完成规划。不得提前无条件读全部候选或再次查询策略，否则无通知策略也可能因陈旧候选或人数上限阻断主业务。

审计 actor 与 ACTOR 收件人规则独立：成立的事件仍按现有 resolvedActor 语义冻结操作者，策略未选择 ACTOR 也必须保留审计字段。人工催办和抄送的显式空身份不回退到线程身份。

**迁移和删除。** 引擎查询与身份解析集中回已有 NotificationService；删除 Planner 四个引擎/身份协作者、Task、IdentityContext、Authentication 和延迟事实查询分支。人工催办、抄送保持已有冻结方式和批量读取。不新增 EventBus、Provider 或另一套命令体系。

**文件范围。** 上述两个生产类、直接构造点和通知正式测试。Writer、Outbox、邮件/短信通道与数据库不改。

**验收。** 非法请求且无策略仍返回原错误，合法请求无策略时零候选/发起人读取；仅 TASK_RECIPIENT 策略仍保存正确 actor；已认领、未认领、任务完成后已查不到 Task、退回重提、转办 revision 的接收人及 sourceId 不变；人工催办/抄送显式空身份不回退线程身份；禁用用户过滤保留；抄送一次定义读取和一次偏好读取保留。Planner 测试不装配 Flowable Service；[WorkflowNotificationSourceIntegrationTest.java](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/notification/WorkflowNotificationSourceIntegrationTest.java) 及入口集成测试继续验证事务回滚、事实时机和 beforeCommit 取消未投递催办。

### N06 — 移除 flowable 对 framework 的直接依赖

**问题与证据。** 当前直接 framework 导入集中于四个权限消费者和邮件 Cipher。权限规则在 [PermissionService.java:42](D:/ruoyiflowable/ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/PermissionService.java:42)，Cipher 在 [WorkflowMailCredentialCipher.java:41](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowMailCredentialCipher.java:41) 依赖整个 TokenService。

**权限子项。**

1. 在 system 新增一个具体权限判定服务，复用 ISysMenuService，承接 Token 权限与必要实时权限的共同判定；不新增 Interface/Impl/Facade 链。
2. framework 的 `ss` 保留 Web PermissionContext 绑定及原角色判断，委托同一权限规则；工作流四个消费者直接调用系统判定服务。
3. 先追踪四个调用之后是否存在 DataScope 消费。若旧请求上下文副作用影响结果，应在既有 Web 入口保留等价绑定及其顺序，并补对应路径验证，再删除依赖。不能把未证明无用的副作用直接删掉，也不能把 Servlet/PermissionContextHolder 搬入 system。
4. 规则保持 Token 快照与实时 workflow 权限的交集：DB 新授予但 Token 没有仍拒绝；Token 尚有但 DB 已撤权立即拒绝。保持超级管理员、通配符、有效角色菜单、空值/trim、hasAny 分隔规则；非 workflow 权限不追加实时查询。使用当前 LoginUser，不以 Flowable 代执行身份替换。
5. `ss` 仍在有效登录信息检查之后、最终权限布尔判定之前绑定上下文；不得与登录时权限快照组装的 SysPermissionService 混为一项职责。

**密钥装配子项。** admin 新增一个配置 Bean（拟放入现有 `com.ruoyi.web.config`），调用现有 TokenService.deriveAes256Key，再使用 Cipher 已有 SecretKey/SecureRandom 构造器。开放必要构造入口，删除 Cipher 的 TokenService 构造依赖、requireTokenService 和重复组件注册；不新增通用 SecretKeyProvider 或公开根密钥 Bean。

保持派生前缀 `ruoyi-purpose-key:v1:`、purpose `approvaplat-mail-credential`、AAD `approvaplat-mail-credential:v1`、12 字节 IV、128 位 tag、编码格式及错误语义。无密文迁移。

**文件范围。** [ruoyi-flowable/pom.xml](D:/ruoyiflowable/ruoyi-flowable/pom.xml)、PermissionService、Cipher、新 system 具体服务、新 admin 配置、四个直接消费者：[ProcessAccessService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/authorization/WorkflowProcessAccessService.java)、[ProcessInstanceService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessInstanceService.java)、[NotificationCatalogService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationCatalogService.java)、[ManualUrgeService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowManualUrgeService.java)，以及必要装配/权限/密文正式测试。

**验收。** 使用合成根密钥固化旧密文向量，验证旧加密→新解密、新格式输出与旧协议互通，错误 key/AAD/IV/tag 被拒绝；不保留第二套生产加解密实现。权限矩阵和相关 DataScope 路径通过；完整启动确认 `ss` Bean 名不变、Cipher 无重复注册；然后删除 framework Maven 依赖，确认 flowable 主代码及必要测试无 framework 直接引用、编译装配通过。不能用反射查 Bean 或把依赖藏入工具类完成表面断边。

### N07 — 详情观察状态交给一个所有者

**问题与证据。** [detail.vue:548](D:/ruoyiflowable/ruoyi-ui/src/views/workflow/work/detail.vue:548) 维护观察序列、计时器、失败数、活动标志、请求槽；828 行 loadDetail、935 行 refreshObservation、981 行暂停、994 行可见性、1790 行路由变化和 1799 行激活逻辑共同修改这些状态。其行为已有测试，但验证调度必须连带整页表单和弹窗。

**目标。** 一个工作流详情专用观察控制器负责轻量运行事实/完整详情请求的串行、调度、响应过期判定、可见性/路由失效和刷新选择。手动刷新、动作后详情刷新、定时刷新、切任务和重新激活都进入同一所有者。MI 冲突修订读取仍属于动作恢复，不并入普通轮询。

**迁移和删除。** 拟新增相邻的 `useWorkflowDetailObservation.js` 或同等具体控制器，接收流程/任务标识、blocked 计算值及必要的事实提交/失效函数；迁走原页面调度实现和状态。页面保留表单草稿、弹窗冻结上下文、业务动作和展示。不要传整个 pageContext 或堆叠大量 refs，不新建通用 Poller、事件总线或全局 store。

**保留合同。** 3 秒轮询、最多 30 秒退避和约 30 秒完整复核；同一观察通道无请求重叠；路由/任务/激活身份一致才提交响应。后台观察、周期全量和普通手动观察受 blocked 约束；动作成功、任务失效和指定冲突恢复保留强制全量及 preserveMultiInstanceDraft 语义。当前动作会在 actionBusy/multiInstanceBusy 尚为 true 时 await loadDetail，不能统一按 blocked 丢弃或等待自身 finally 清 busy。

403/404 清理并停止；挂起继续观察；轻量响应发现终态先让旧能力失效并触发全量，全量失败继续退避，成功提交终态详情后才停止。隐藏、缓存失活和卸载均失效；同任务编辑保留。观察 epoch、详情上下文和 MI revision 含义不同，不能为了“一套状态”盲目合并所有计数器。

**文件范围。** detail.vue、一个新具体控制器、现有 [workflow-observation.spec.js](D:/ruoyiflowable/ruoyi-ui/tests/e2e/workflow-observation.spec.js)、[workflow-page-reuse.spec.js](D:/ruoyiflowable/ruoyi-ui/tests/e2e/workflow-page-reuse.spec.js) 和必要正式控制器测试。后台 API 和其他页面不变。

**验收。** 定时、退避、响应乱序和阻塞矩阵可不挂载整页 Element Plus 组件验证；浏览器保留切任务旧响应、隐藏恢复、缓存首次进入、动作后刷新、编辑保留、403/404，以及轻量发现终态后全量失败继续重试的真实页面断言。额外覆盖 busy=true 时的动作后刷新与 MI 冲突保留草稿刷新，确保没有等待自身结束的死锁。旧 loadDetail 与新控制器不得各自保留一个请求槽或独立轮询路径。

### N08 — 集成目录、冻结与共享 HTTP 执行归位

**问题与证据。** [WorkflowHttpConnector.java:118](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowHttpConnector.java:118) 依赖模型目录做冻结，572 行再调用运行时端点检查；[WorkflowSqlConnector.java:101](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowSqlConnector.java:101) 的目录依赖只服务部署冻结；[WorkflowCollaborationOutboxService.java:231](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowCollaborationOutboxService.java:231) 为发消息依赖整个 BPMN HTTP 执行器。[WorkflowIntegrationCredentialBindingService.java:29](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowIntegrationCredentialBindingService.java:29) 实际编排两类凭据的原子绑定，却归入模型包。

**目标。** 明确三项合同：目录维护及绑定；作者配置→既有无密钥正文冻结 JSON；冻结配置+幂等键+有界正文→状态码/有界响应。

**先确认、再迁移。** 在本包开始时列出 endpoint/dataSource/credential 的真实读写调用图及拟迁移路径，只移动具有上述职责的类。目录改名本身不算完成；运行时的受管绑定检查应能脱离目录 CRUD 装配。保持 Spring Bean 名、配置和表达式引用，不能按 model 整包搬迁。

**迁移、删除和新增。**

- 现有目录、受管秘密和原子绑定能力归入明确的集成业务位置，继续只有一个实现。
- 最多新增一个具体共享 HTTP 执行组件，承接被 BPMN 和 Outbox 共同使用的解析、认证、地址限制与有界 IO。
- BPMN 适配器保留 execution 变量取值/写回；协作调用层保留协议、错误分类与 Outbox 重试。删除搬走的方法以及 Outbox 对 BPMN 执行器的依赖。
- SQL 只处理部署目录与运行职责混放，不引入 HTTP/SQL 通用框架；不新增 transport interface、provider registry 或通用投递协调器。

**保留合同。** I-14 探测锁事务和原并发异常翻译保持；共享发送组件不私自开新事务或提前取凭据。受管凭据继续在原发送时点核验当前绑定、端点状态和秘密有效性，保留撤销/轮换行为；ENV 与 NONE 保持既有部署冻结行为，不新增当前目录状态门禁。冻结地址、方法、路径、网络范围、白名单、超时和 checksum 不变。HTTP 节点交给 Flowable Job 重试，协作交给 Outbox 重试。外部 SQL 幂等 INSERT 与 PRIMARY 事务保持原分工。协作 64 KiB、认证头、nonce 和错误码不变，普通 HTTP 方法范围不被协作 POST 限制收窄。

**文件范围。** 上述类、WorkflowCollaborationOutboxHandler，以及现有 model 包中的 WorkflowConnectorEndpointService、WorkflowSqlDataSourceService、WorkflowConnectorCredentialService 和 WorkflowIntegrationCredentialBindingService；直接 Controller/装配引用与正式测试随调用方同步。涉及 process 包 WorkflowIntegrationCredentialService 时只调整必要绑定调用，不迁移普通运行事件账本。开始时落实到精确路径清单，保持通知 Outbox 和数据库结构不变。

**验收。** 受控 HTTP 加真实端点/秘密配置可独立验证共享发送能力，无需完整模型发布或任务引擎。执行 [WorkflowConnectorCheckIntegrationTest.java](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowConnectorCheckIntegrationTest.java) 的真实 MySQL 重绑竞态；验证受管端点重绑/停用被拒绝，ENV/NONE 已发布节点不因新增目录检查改变行为；补两类调用者的单一重试归属验证。原目录 import 的删除必须对应装配依赖减少，不能仅将相同总服务换包名。

### N09 — 最终边界和真实业务链验收

**静态交付。** 对照第 2.2 节逐条确认依赖确已删除，旧方法和转发入口无残留；更新实际状态所有者、修改路径、测试断言迁移对应和执行结果。每包分别记录纯删除、职责迁移和必要新增，不把测试拆分计作生产精简。

**真实验收至少覆盖三条主链。**

1. 登录 → 设计器字段目录 → 保存 → 预检 → 发布 → 草稿及附件 → 发起 → 办理 → 历史详情。验证当前部署冻结事实与设计时修改隔离。
2. 办理 → 退回 → 申请人修改及附件 → 重提 → 恢复原办理配置。验证详情 capability 与正式动作一致、旧任务响应不能污染新任务。
3. 动态多实例操作与 revision 冲突 → 详情刷新/隐藏恢复 → 通知写入和投递失败后重试。验证跨请求状态、单一重试所有者及终态停止。

使用真实前后端、测试账号和合成业务数据，通过页面进入上述操作。受控 HTTP/邮件/短信传输可以验证失败协议，报告需标明是否接入真实外部供应商。已有 API stub 浏览器测试继续作为界面回归，不能替代这里的登录与后端业务链。测试数据按本次标识精确清理，不批量清库。

**完成标准。** N00～N08 的依赖目标和合同逐项满足；最终 revision 的正式回归无失败；MySQL 必需用例无跳过；三条真实主链留下可追溯结果；临时脚本、日志、调试文件和数据已处理。缺少数据库或真实业务环境时如实标为未完成，不用编译成功替代该项验收。

## 6. 验证方法和可执行命令

先运行包内相关用例，再运行受影响链；全部合并后做一次完整验证。出现新修改、失败或未解释的行为时再扩大范围，避免每移动一个方法都重复全套环境装配。

第一阶段记录为后端 68 个通过、前端合同 10 个通过、stub 浏览器 18 个通过。最后一次小幅清理后重跑了 lint、合同和后端编译，没有重跑完整后端及浏览器。本阶段不能把这些旧结果标成新实现的通过证据。

以下在 `D:\ruoyiflowable` 的 PowerShell 7 执行。JDK/Maven、Windows Node 依赖、正式测试数据源使用已有项目环境；不与 WSL 共用 node_modules 或虚拟环境。

```powershell
# 单包示例；执行任务按实际合同替换测试类清单。
mvn -B -pl ruoyi-flowable -am '-Dtest=WorkflowTaskReturnChainIntegrationTest,WorkflowRuntimeFactReuseIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 最终后端验证。
mvn -B -pl ruoyi-admin -am test

# 前端验证。
npm --prefix ruoyi-ui run lint
npm --prefix ruoyi-ui run test:contracts
npm --prefix ruoyi-ui run build:prod

# 既有 E2E 配置不自动启动 Vite。先在单独终端运行：
npm --prefix ruoyi-ui run dev -- --host 127.0.0.1

# 确认实际端口后，在运行测试的终端设置：
$env:WORKFLOW_E2E_BASE_URL = 'http://127.0.0.1:1024'
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm --prefix ruoyi-ui run test:e2e -- workflow-page-reuse.spec.js workflow-observation.spec.js

git diff --check
git status --short
```

MySQL 测试读取 `WORKFLOW_MYSQL_TEST_URL`、`WORKFLOW_MYSQL_TEST_USERNAME`、`WORKFLOW_MYSQL_TEST_PASSWORD`；使用已有测试配置，不将密码写入计划、日志或命令输出。检查 Surefire XML 中的失败、错误和 skipped，环境缺失导致跳过不算通过。

新拆出的合同使用仓库现有 JUnit/Node 测试体系。对于锁、事务、Flowable 执行和 MySQL 方言保留相应真实基础设施；对于字段合并、通知规划和刷新调度使用限定输入验证。不要以全量 mock 降低表面的装配成本。

## 7. 暂不纳入主线的两个较小候选

这两项与八个职责包分开登记，防止后续遗漏，也不扩大每包实施范围。

| 候选 | 当前证据 | 后续处理条件 |
|---|---|---|
| 扩展运行代码引用设计服务的 checksum/类型常量 | ExtensionDelegate、BusinessListener 使用 ExtensionDeploymentService.snapshotChecksum 及 RegistryService 常量 | 可在扩展合同调整时迁入已有合同所有者；本阶段收益小，不为静态方法和常量再造服务或包装 |
| 人工催办中的认领资格 SQL 重复 | ManualUrgeService 381～396 行与 WorkflowIdentityMapper.xml 的 claimEligibilityPredicate 重复 | 可单列小任务，将 SQL 归入现有 Mapper；需保持普通读/共享锁、2001 上限和锁顺序，不直接替换成无锁 Resolver |

以上候选不是已清理项；N09 结果需保留其状态。今后新增候选仍需给出实际调用、应删除实现和收益，不因目录观感继续扩张范围。

## 8. 与后续微服务化的关系

本阶段完成后，首先应得到更容易维护和测试的单体。届时再根据独立部署、负载隔离或发布节奏的实际需求选择服务边界。

通知投递、附件后台清理和集成传输可以作为后续评估对象，但本计划仍让它们在原应用中运行。真正拆为服务前，还需独立的数据写入归属、跨进程授权与凭据合同、失败补偿、部署和端到端验证方案；不能只把 Java 服务改成远程调用。

审批启动、任务迁移、附件绑定和 Flowable 状态当前依赖同事务，应优先保留这一一致性边界。系统身份仍被工作流 Mapper 读取；删除 framework Maven 依赖也不会自动消除共享数据库依赖。

## 9. 可直接交给新任务的提示词

```text
请阅读 D:\ruoyiflowable\docs\plans\architecture-boundaries-next-stage-plan-20261003.md，
仅实施其中的 Nxx（将 Nxx 替换为一个具体任务编号）。

先确认前置任务、当前 HEAD、工作区已有变更和实际修改路径。
按该包的目标迁移职责，并删除旧实现；保留列明的事务、授权、状态、错误和对外合同。
不要顺手增加通用框架、修改其他业务链，或删掉 T-08/I-14 的保留机制。
遇到与计划相反的新证据，先说明证据并调整本包方案，不直接扩大范围。

执行对应正式测试；锁和事务用例使用计划规定的数据库；记录跳过和未执行项。
保留既有变更，只操作本包精确文件；临时验证文件和数据结束前清理。
交付实际删除/迁移/新增清单、已消除依赖、验收结果和剩余问题。
不要自动提交、推送或部署，完成后等待我单独安排。
```

多个任务的汇总结果由 N09 统一核对。开始实施前若已有其他会话变更，以最新代码重新定位证据和调用方，不能机械套用本计划的旧行号。
