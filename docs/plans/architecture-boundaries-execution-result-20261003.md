# 业务职责归位与测试装配收敛：执行结果

日期：2026-10-03。基线：`7508665bb7514af28bb899f868a87ddb1f1c0e17`。

对应计划：[architecture-boundaries-next-stage-plan-20261003.md](D:/ruoyiflowable/docs/plans/architecture-boundaries-next-stage-plan-20261003.md)。本次在原有七个 Maven 模块内实施，不提交或推送 Git 变更。

状态：**N00～N09 已完成**。全部代码已合入当前工作区，最终后端、前端与三条真实业务链通过，临时环境和产物已清理。

## 1. 职责与依赖

| 包 | 迁移后的所有者 | 删除的旧依赖与实现 | 保留的合同 |
|---|---|---|---|
| N00 | 草稿、部署资源、扩展版本的独立正式测试 | 组合发布测试中的独立 helper、调用及专属装配 | 组合测试继续验证真实发布执行、编译/读取次数、冻结引用、原子回滚 |
| N01 | 现有 `WorkflowAttachmentCleanupCoordinator` | Coordinator → AttachmentService；AttachmentService 中清理协议旧实现 | 手动 NOT_SUPPORTED、批次 REQUIRES_NEW、IO 在事务外、token/retry CAS、T-08 |
| N02 | `WorkflowAuthorFieldCatalogService` 与包内 `WorkflowCallActivityFields` | Controller → ModelService 的目录入口；运行引用服务中的纯字段投影 | 模板按次缓存、节点分别应用权限、部分错误、类型冲突、交并集规则 |
| N03 | `WorkflowPublicationArtifactsService` | ModelService 直接装配全套编译器及准备/冻结旧实现 | 主部署、DMN、快照、模型关联及旧定义处理保持同一发布事务 |
| N04 | `WorkflowTaskReturnPreparation`、`WorkflowApplicationResubmitPreparation` | 详情 → 两个写应用服务；写服务内重复资格入口与准备实现 | 400/403/404/409 能力转 false、500 继续抛出、退回/重提写事务保持 |
| N05 | NotificationService 冻结事实；Planner 选择策略并规划 | Planner → RuntimeService/HistoryService/TaskService/IdentityResolver/Authentication | 无策略不读取候选人与发起人、actor 审计独立、手工 null 身份、原写入事务 |
| N06 | system `SysPermissionEvaluator`；admin 邮件密钥装配 | flowable → framework Maven 边；业务 Cipher → TokenService | Web ss/DataScope 上下文、实时权限交集、旧密文和错误分类 |
| N07 | 相邻 `useWorkflowDetailObservation.js` | detail.vue 的独立请求槽、轮询、序列与计时器路径 | 单请求、任务身份/代次隔离、隐藏/缓存恢复、动作 busy 刷新、MI 草稿保留 |
| N08 | `service.integration` 的目录/密钥能力与 `WorkflowHttpExecution` | Outbox/Check → HTTP BPMN 适配器；HTTP/SQL 运行器 → 目录装配；Registry → 运行适配器；两套协作 POST 和三处认证规则收敛为各一套 | I-14、发送时受管绑定检查、ENV/NONE 冻结、HTTP/Outbox 分别重试 |

生产状态所有者不变：草稿、任务迁移、多实例 revision、通知与协作 Outbox、部署资源仍由原服务和表负责。新准备类仅持有本次准备结果；前端控制器只拥有观察调度，不拥有后端资格或状态迁移。

主要修改路径：

- `ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/`：作者目录、字段规则、制品准备、ModelService、CallActivityReferenceService 及扩展装配。
- 同层 `attachment/`、`task/`、`notification/`、`process/`：分别为清理归位、准备规则、事实冻结、详情/协作调用方。
- 新位置 `service/integration/` 接收旧 `service/model/` 中的 EndpointService、SqlDataSourceService、ConnectorCredentialService、IntegrationCredentialBindingService、ConnectorCheckService，以及旧 `extension/` 中的 ManagedConnectorSecrets、ConnectorSecretResolver、SqlSecretResolver。以上类名均带 `Workflow` 前缀；八个旧路径全部删除。
- `ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/`：HTTP/SQL 运行适配器、SQL 模板校验器、扩展 Delegate 和协作 Handler。
- `ruoyi-system/src/main/java/com/ruoyi/system/service/SysPermissionEvaluator.java`、`ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/PermissionService.java`、`ruoyi-admin/src/main/java/com/ruoyi/web/config/WorkflowMailCredentialConfiguration.java`，以及六个直接 Controller 引用。
- `ruoyi-ui/src/views/workflow/work/detail.vue`、相邻 `useWorkflowDetailObservation.js`；真实验收修复另涉及 `start.vue` 与 `src/api/workflow/attachment.js`。
- 新测试全部放入相应 Maven 模块的 `src/test/` 和前端 `tests/contracts/`、`tests/e2e/`；不新增通用装配工厂。

## 2. 迁移、必要新增与删除

统计按职责与实现单元区分。方法搬家和目录迁移不计为纯删除，测试拆分不计为生产代码精简。

| 包 | 职责迁移 | 必要新增 | 纯删除 | 正式测试变化 |
|---|---|---|---|---|
| N00 | 原独立合同从组合测试迁出 | 独立最小装配 | 原 helper 调用和独占装配 | 见下表的逐项对应 |
| N01 | 8 个清理方法及其状态协议归回现有 Coordinator | 清理协议和 MySQL 竞争覆盖 | 重复错误常量、Coordinator 对总服务的装配边；方法主体计作迁移 | 新增 Coordinator 9 例、附件 MySQL 3 例；T-08 从启动链迁出 |
| N02/N03 | 21 个方法、来源/准备记录及冻结段归位 | 2 个具体 service、1 个包内字段规则类 | ModelService 目录转发和直接编译装配；构造依赖 17 → 8 | 字段目录 4 例、版本 1 例；原组合 4 例继续执行 |
| N04 | 两类资格、准备记录、来源与历史校验迁出写编排 | 2 个具体准备类 | 详情旧写服务依赖及原资格包装 | 新增 5 例，含真实 Spring 事务代理与 H2 提交证明 |
| N05 | 候选人/发起人读取迁至入口 | 策略一次选择后复用、值事实输入 | Planner 的引擎字段、Task 上下文与身份枚举 | 新增 Planner 4 例、入口 8 例；真实 MySQL 提交/回滚取消协议 |
| N06 | 共享权限规则移至 system；派生密钥装配移至 admin | evaluator 与配置 Bean | flowable 的 framework 依赖及 Cipher TokenService 装配 | system 8 例、Web/DataScope 17 例、密文/装配 4 例 |
| N07 | 页面观察调度移至相邻控制器 | 可注入时钟与请求/提交回调 | 0 个业务能力；页面不再残留旧调度状态 | 新增控制器 18 例；页面回归覆盖增至 22 例 |
| N08 | 8 类移至 integration；HTTP/SQL 冻结归目录；共享发送归 HttpExecution；SQL 作者校验归现有 Validator | 1 个具体 HTTP 组件 | 6 条旧装配依赖；重复协作 POST 和认证规则；旧路径删除 8 项均属迁移，纯删除生产类为 0 | 受控 HTTP 共 20 例（收尾增加 10 例）；既有 MySQL 7、版本 1、编译 4 例 |

最终代码范围按路径计为 84 项：49 个现存生产路径、8 个删除的迁移旧路径、26 个正式测试/资源路径和 1 个 Maven 配置。八次移动各有新旧路径，不能把这 8 个删除路径算作业务代码削减。文档另为新增本执行报告、更新用户已有未跟踪计划的状态；原计划正文保留。

收尾后的代码清单摘要 SHA-256：`a3e044438b6ac340799e6b526c128b6d917aa3e8f820819b44e47ee54882b904`。口径为本次修改/新增/删除的 `ruoyi-*` 路径排序，每行 `相对路径 空格 当前文件SHA256`，删除文件用 `DELETED`，UTF-8/LF 且末尾带 LF；排除文档和临时运行文件。首轮摘要为 `e582aec8149feffeb5953ff106fd6403b0b8a42616f61976449f63af7c389101`。

N00 的原断言对应：

| 原组合测试合同 | 现正式用例 |
|---|---|
| `verifyDesignDraftIsolation` | `WorkflowModelDesignDraftIntegrationTest.isolatesOwnersAndEnforcesBothRevisionsWithoutReadingPublishedSource` |
| `assertDmnDeletionBoundaries` | `WorkflowDeploymentResourceContractTest.enforcesDmnDeletionContractsFromPersistedReferences` |
| `assertResourceReadBoundaries`、`assertLazyDeploymentRead` | `WorkflowDeploymentResourceContractTest.boundsStreamsAndReadsOnlyRequestedResourcesWithoutDmnEngine` |
| 扩展版本 helper | `WorkflowExtensionVersionContractTest.createsCanonicalVersionsForEverySupportedExtensionType` |

N08 调用图确认：Controller 仍进入原目录/凭据/绑定/探测服务；发布服务进入目录的 `freezeConfig`，不装配运行器；HTTP BPMN 适配器和协作 Outbox 各调用一次 `WorkflowHttpExecution`。受管认证在实际发送时经 SecretResolver → EndpointMapper 的 `selectByIdForUpdate` → 当前秘密解析；ENV/NONE 不增加当前目录状态查询。探测仍由原 `writeAsCurrentUser` 事务持锁完成 nonce 请求与响应核验。SQL 的 PRIMARY 事务和 EXTERNAL 幂等 INSERT 分工未改变。

独立只读复核覆盖 N01/N04/N05/N06/N08 的调用与事务合同，未发现新增可触发回归；最终合并仍以实际测试结果为准。

## 3. 已执行的分层验证

| 范围 | 结果 | 证据边界 |
|---|---|---|
| N00/N02/N03 | 13 例通过 | 独立字段/草稿/资源/扩展/版本合同及原组合真实执行 |
| N01 | 24 例通过 | Coordinator 9、附件 MySQL 3、启动链 12；T-08 自然越过有效期后绑定返回 409 |
| N04/N05 合并专项 | 27 例通过，0 skipped | 准备 5、退回链与运行事实、规划 4、入口 8、通知源 MySQL 3 |
| N06 | 29 例通过，0 skipped | system、admin 的权限/DataScope/历史密文兼容；另启动链 12、运行事实 4 通过 |
| N07 | Node 合同 28、浏览器 22 通过 | 浏览器为 API stub 页面回归，不计入真实业务链 |
| N08 | 22 例通过，0 skipped | 真实 MySQL I-14/重绑/撤销/轮换；独立受控 HTTP 方法、ENV/NONE、64 KiB、单一重试归属 |
| N00～N06 reactor package | 通过 | 包含 admin 装配；这是阶段编译，不能代替 N08 合入后的最终回归 |

## 4. N09 首轮验收（本次收尾复验见第 7 节）

合并后执行 `mvn -B clean -pl ruoyi-admin -am test`，01:27:02 完成：**145 个测试通过，failure/error/skipped 均为 0**。按 Surefire XML 汇总，system 24、flowable 88、admin 33；其他模块没有测试。必需的附件 MySQL 3、连接器 MySQL 7、通知源 MySQL 3 及启动链 12 均执行。随后 `-DskipTests package` 于 01:28:14 成功，真实页面验收使用该最终后端产物。

合并后的前端 lint、28 个合同测试、生产构建、22 个 stub 浏览器回归已通过。草稿恢复修复合入后再次通过 lint、28 个合同测试和生产构建；新增 3 个草稿附件浏览器合同全部通过，受影响的页面复用 9 例也复跑通过。共计 25 个受控接口浏览器合同，不重复累计复跑次数。

用于真实验收的后端 jar SHA-256：`79325142dcd57cceee82d7805b3aed2f206764b6e662e0bf44412cd83160a65c`。工作区未创建新提交，因此本报告以基线 HEAD、最终文件、正式用例及运行标识关联证据。

真实环境使用本机 MySQL、独立 Redis 进程、真实 Spring Boot 后端与 Vite 前端。原测试库存在较旧的协作表结构，首次启动暴露缺列错误后停止该后端；未修改原测试库结构。另建本次专用验收库，按仓库初始化脚本的 14 个 SQL 文件建立，避免让环境版本偏差影响最终证据。

三条链：

1. 登录、设计器字段目录、保存、预检、发布、草稿附件、发起、办理与历史详情，检查部署冻结隔离。
2. 退回、申请人修改及附件、重提与恢复办理配置，检查 capability 与正式动作一致。首轮使用同人申请与审批，尚不能证明不同人员身份恢复；此覆盖缺口在第 7 节修复。
3. 动态多实例及 revision 冲突、隐藏恢复、通知失败和补偿投递，检查刷新隔离和终态停止。

最终同轮执行于北京时间 **2026-10-03 01:54:55** 开始，耗时 **136.6 秒**：**3 passed，0 failed，0 skipped，0 flaky**。运行标识：`n09_1790963696027`。三条实例最终均为 `completed`。

| 链 | 流程实例 ID | 实测关键结果 |
|---|---|---|
| N09-1 | `68f53296-be8a-11f1-9a43-005056c00001` | 作者字段目录、保存/预检/发布、修改设计时表单后部署仍保持原标签、附件草稿重载、待办办理和历史附件展示通过 |
| N09-2 | `70b154cf-be8a-11f1-9a43-005056c00001` | 退回进入申请人修改、补附件、重提恢复审批配置、能力按钮与实际动作一致，最后办理完成 |
| N09-3 | `7ded16ad-be8a-11f1-9a43-005056c00001` | MI revision 0→加签 1→减签 2；旧版本完成返回 409/`WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT`，意见保留；隐藏恢复后 revision 2 且表单草稿保留 |

通知策略 `19`、Outbox `3`：真实 `SMTP_CONNECT_FAILED` 触发五次 `RETRYING`，第六次进入 `DEAD_LETTER`（cycle 1 / attempt 6 / total 6）；页面补偿后变为 `PROCESSED`（cycle 2 / attempt 1 / total 7）。回环 SMTP 只接受 1 封，跨 3 秒 worker 周期状态和计数稳定，页面显示“已送达”且无补偿按钮。幂等头为 `e4d69ad9cb933e3d15dd143ed21a8a3d6ddfd7e5751ab9e714d569dfd1524df1`。

传输采用受控本机 SMTP，未连接真实邮件供应商。三条主链没有伪造 HTTP 业务响应；隐藏恢复在真实页面触发 `document.hidden/visibilitychange`，响应乱序的反例由 N07 的受控浏览器合同覆盖。

清理前记录的资源标识如下：

```text
N09-1
model       656ea3d6-be8a-11f1-9a43-005056c00001
deployment  67deb978-be8a-11f1-9a43-005056c00001
draft       9459ee6f-3683-4e97-baeb-6fa37c34c360
attachment  4e05aea6-60ba-4a88-81ec-19cfe21d4d3b
task        68f559b4-be8a-11f1-9a43-005056c00001

N09-2
model       6cf55e2f-be8a-11f1-9a43-005056c00001
deployment  6f6fad01-be8a-11f1-9a43-005056c00001
draft       12f55017-11e0-4b3e-a8fc-ead31c7e14ff
attachments bca78bbe-e131-42c3-958b-2f96b3c2b9b9, 0194bdb9-adcb-4eb7-8d75-0b0e7ac2f2f7
task A      70b17bed-be8a-11f1-9a43-005056c00001
task B      71d05974-be8a-11f1-9a43-005056c00001

N09-3
model       76780fad-be8a-11f1-9a43-005056c00001
deployment  7947f88f-be8a-11f1-9a43-005056c00001
draft       0f7e3eb5-8b03-4589-9135-5d3314357e99
attachment  b45a12f4-cbb0-417d-8b2d-fb384f3d141d
task        7dee0130-be8a-11f1-9a43-005056c00001
```

两份最终真实 E2E 源码 SHA-256 与执行时一致：`workflow-business-boundaries.spec.js` 为 `d8fd67b430152abae21e5796a107e4c3eea75226e21ff53260efa904328665c4`；`fixtures/workflow-notification-cycle.js` 为 `965cf8a0e2040626b76a81aae7d2a7872530d403155a66a33c5f343dfc8a3f86`。合入主目录后再次通过全前端 lint。

预跑发现草稿附件的界面恢复缺陷：保存和读取仍为正确的 UUID 数组，但上传组件需要附件元数据，恢复后显示空文件名。`ProcessFormRenderer.getValues` 已支持 UUID 字符串，未发现存储丢失。修复限定为发起页通过现有授权元数据 GET 恢复展示；数据库、后端 DTO 和提交 UUID 合同保持。3 个新增正式浏览器合同验证可读字段回显、保存和重载、DRAFT 只移除引用不直接删除，以及 403 时不开放残缺表单。

其余预跑修正属于测试夹具：合成表单补齐 `workflowHidden` 合同；发起/退回后的实例只读页按实际入口经待办进入携 taskId 的办理页；邮件配置控件按实际表单标签定位；合成会签成员使用基线审批角色 101、部门 100，并经正式审批人员目录确认资格（非 user_id=1 的用户不会因 role_id=1 自动获得全部权限）；隔离库发起人原邮箱为空，先出现正确的 `RECIPIENT_INVALID`，随后仅为本次登录用户设置合成邮箱并在结束后恢复；清理 SQL 使用与表一致的 collation。含 BOUND 审计附件的历史删除原本就会拒绝，夹具仅在本次隔离库按记录的附件 ID 与实例 ID 释放引用，再走正式物理删除和历史接口；没有放宽生产删除合同。清理错误会使 `afterAll` 失败。

普通退回/重提的正确身份合同为：原审批任务 A 退回后产生申请人修改任务 B；重提恢复 B 的原审批配置，仍使用 B 的 taskId。验收曾加入 A/B/第三个 taskId 全部不同的错误预期，查对 `restoreOrdinary` 和真实记录后修正为 A≠B、重提仍为 B；生产行为未改。

## 5. 保留项与交付边界

- T-08 附件绑定时再次核验有效期保留；I-14 探测锁事务保留。
- 扩展运行代码引用设计服务 checksum/类型常量仍为后续候选，本次不另造包装。
- 人工催办的认领资格 SQL 重复仍为后续候选，本次不改变共享锁和顺序。
- 不新增 Maven 模块或生产数据库迁移，不改变 HTTP、部署资源和 BPMN 表达式合同。
- 最终 `afterAll` 全部通过；额外查询确认本轮模型、历史实例、草稿、附件、策略 19、Outbox 3、SMTP 配置均为 0，验证码恢复 `true`，发起人邮箱恢复为空。
- 已停止本次后端、两个 Vite 验收入口及独立 Redis，移除本次新建验收库 `approvaplat_arch_20261003_0109_it` 及其单库授权；原 `approvaplat_it` 仍存在，MySQL84 保持 Running，原 Memurai 服务保持 Stopped。
- 三个临时 checkout、依赖 Junction、脚本、额外日志、截图、Playwright 和 Surefire 临时输出均已清理。子任务的强制删除曾被自动审批以 `blocked by policy` 拦截；核验普通文件/目录及链接后，使用不带强制选项的分项清理成功，主目录依赖完整。正式测试源码、常规构建产物和本报告保留。
- Git HEAD 未改变，暂存区为空，未提交或推送。

## 6. 复跑入口

PowerShell 7，项目根目录 `D:\ruoyiflowable`：

```powershell
mvn -B clean -pl ruoyi-admin -am test
npm --prefix ruoyi-ui run lint
npm --prefix ruoyi-ui run test:contracts
npm --prefix ruoyi-ui run build:prod

$env:WORKFLOW_E2E_BASE_URL = 'http://127.0.0.1:1024'
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm --prefix ruoyi-ui run test:e2e -- workflow-page-reuse.spec.js workflow-observation.spec.js workflow-draft-attachments.spec.js

# 真实业务链：先启动使用隔离 _it 数据库的后端、Redis 和前端，再设置下述专用环境变量。
npm --prefix ruoyi-ui run test:e2e -- workflow-business-boundaries.spec.js --workers=1
```

真实业务链读取 `WORKFLOW_MYSQL_TEST_URL/USERNAME/PASSWORD`、`WORKFLOW_E2E_USERNAME/PASSWORD` 和 `WORKFLOW_E2E_REDIS_PORT`。数据库 URL 必须对应正在运行的后端隔离库，账号需有夹具清理权限；SMTP 初始配置为空。测试临时关闭同一隔离环境的验证码，结束后恢复原值，不在源码或报告写入实际密码。

## 7. 2026-10-03 收尾修正与复验

本轮完成用户指出的三个问题：

1. **BS-001**：两套 `postFrozenJson` 合并到现有 `WorkflowHttpExecution`，三处协作认证规则合并为该组件内的一处实现，冻结端点复用同一规则。Check 与 Outbox 直接调用共享 POST。请求限制、固定 POST、认证头、超时/IO/中断/配置损坏错误码和中断位恢复保持；探测锁事务及 nonce 响应核验留在 Check，投递结果、租约、重试与补偿留在 Outbox。
2. **不同身份的真实重提**：申请人与审批人分别在独立浏览器上下文登录。退回链采用正式 `CANDIDATE_USERS` 配置，并由审批人通过待签页面认领。读取实际任务与身份关系表，逐阶段精确比对 assignee、owner、候选用户及候选组，候选用户集合非空。任务 A 退回产生 B，重提仍为 B，恢复原候选人后由原审批人再次认领并办结。
3. **可配置的会签发起人**：会签 `identityIds` 使用已读取的 `String(initiatorId)`，移除用户 1 常量。审批角色也按正式 `workflow_approver` 角色键查询。

新增 10 个正式传输合同测试，覆盖非法请求大小、非 POST、冻结/发送认证一致性、损坏 JSON 错误码、单次发送的超时/IO/中断转换。相关 32 个测试先通过，随后于 **10:53:38** 完成 `mvn -B clean -pl ruoyi-admin -am package`：**155 passed，0 failure，0 error，0 skipped**（system 24、flowable 98、admin 33）。真实页面使用此次构建的 jar，SHA-256 为 `3ce9b6cacf3f26e9a81d8b9440b9ffade1617850953dc2a713f20290b5e02e5f`。

前端全范围 ESLint 通过。首次不同账号复验发现旧合成账号的用户名与密码超过系统 20 字符登录上限；原夹具只使用该账号作为成员，未实际登录，因而未暴露。修正测试账号长度，并让登录辅助函数断言真实登录响应，避免错误登录一直等待页面跳转。生产登录规则未变。

最终三条真实链于北京时间 **11:01:04** 开始，同轮耗时 **129.0 秒**：**3 passed，0 failed，0 skipped，0 flaky**，包括 `afterAll` 的精确清理。运行标识为 `n09_1790996464813`，三条实例均完成：

| 链 | 流程实例 ID | 结果 |
|---|---|---|
| N09-1 | `b344df42-bed6-11f1-9988-005056c00001` | 草稿附件、设计发布冻结隔离、发起办理及历史详情通过 |
| N09-2 | `b95e4dfb-bed6-11f1-9988-005056c00001` | 申请人 1、审批人 101，不同账号完整退回重提通过 |
| N09-3 | `c7dc7afe-bed6-11f1-9988-005056c00001` | 动态会签、revision 冲突、隐藏恢复与通知失败补偿通过 |

N09-2 的任务 A 为 `b95e7519-bed6-11f1-9988-005056c00001`，任务 B 为 `bbf99266-bed6-11f1-9988-005056c00001`。下列集合来自实际数据库身份链接，全部阶段的 owner 均为 null、候选组均为 `[]`：

| 阶段 | 任务 | assignee | candidateUserIds |
|---|---|---|---|
| 原始待签 | A | null | `[101]` |
| 审批人认领 | A | 101 | `[101]` |
| 退回申请人修改 | B | 1 | `[]` |
| 申请人重新提交 | B | null | `[101]` |
| 原审批人再次认领并办理 | B | 101 | `[101]` |

本轮通知策略 16、Outbox 1 实测六次 `SMTP_CONNECT_FAILED` 后进入死信；页面补偿后 `PROCESSED`，cycle 2 / attempt 1 / total 7。受控回环 SMTP 接受 1 封且无服务端错误，补偿后状态和计数稳定。未连接外部邮件供应商。

本轮没有修改前端生产代码；前端生产构建、28 个 Node 合同和 25 个 stub 浏览器合同沿用第 4 节已执行结果，本轮新增证据是修改后真实业务套件和 ESLint。后端全量测试及打包则针对收尾后的最终源码重新执行。

**收尾清理完成**：11:09:39 核验两次运行的模型、部署、实例、任务、历史、草稿、附件、上传声明、通知与 SMTP 资源均无残留，验证码恢复 true、发起人邮箱恢复原值，私有上传文件为 0。正式表单/分类 API 产生的软删除墓碑随专库移除。已停止本次后端、前端及独立 Redis，8080/1024/16379 均无监听；撤销本次单库授权并删除 `approvaplat_arch_closeout_20261003_105312_it`，原 `approvaplat_it` 保留，MySQL84 仍 Running，Memurai 服务仍 Stopped。临时脚本、日志、截图、Playwright/Surefire 输出已清理，正式用例和构建产物保留。Git HEAD 仍为本报告基线，暂存区为空，未提交、未推送。
