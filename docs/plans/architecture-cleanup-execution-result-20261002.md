# 架构清理执行结果（2026-10-02）

执行基线：`172dcee4d996b6271c5436439ddfba3147850fba`。开始时只有用户提供的 `docs/plans/` 未跟踪，索引为空。执行范围为原计划 E00～E15，未提交、推送或部署。

## 结果

34 个直接实施条目已落实；按用户补充说明“所有的数据都是测试数据完全没用”，另完成 D-05、D-06 的旧协议兼容清理。I-14、T-08 已完成实验，删除条件不成立，保留生产实现。没有批量清理数据库、轮换凭据或导出真实密钥。

| 包 | 条目与处理 | 验证与保留合同 |
|---|---|---|
| E00 | I-01：六处日志注解禁用敏感请求或响应正文 | 实际控制器 AspectJ 代理、LogAspect 序列化及合成密钥；业务响应与审计元信息保留 |
| E01 | D-01/D-02/D-04/I-05：删除无用注入、包装、重载；DMN 查询一次；合并非 JAVA 实现键校验 | 实际 Flowable 编译/发布/执行；DMN 不存在、未引用、已引用、冻结子部署四路径；五类型扩展与版本门禁 |
| E02 | D-03/D-07/I-10/I-11/I-12：删除死 loading，复用 SLA 工厂，合并加载状态与归一化，首次加载一次 | 合约和浏览器验证请求复用、失败重试、乱序隔离、缓存/非缓存首次进入、回页及分页 |
| E03 | T-01～T-04：删除三类服务无用校验器依赖、DTO 已保证的兜底和活动状态重复判断 | 完成/退回/运行事实复用集成测试；权限、错误码和文本、非法元素、人员资格与 revision 顺序保留 |
| E04 | R-01/R-03：删除内部未消费参数、无调用重载与外层重复事务检查 | Supplier/Runnable 代理隔离级别、弱事务与裸目标拒绝、嵌套身份恢复；公开事务注解保留 |
| E05 | R-02：元数据及历史表单派生读取，删除镜像 ref 和父层深拷贝 | 轮询、隐藏、切任务、旧响应、输入保留及历史附件只读浏览器验证 |
| E06 | T-05～T-07：删除重复归属检查，共用草稿附件协调，引用和 ID 集合一次规范化 | 保存/移除/复用/新增/重复/越权/字段/到期/提交失败回滚；原锁序、Mapper SQL、事务及 CAS 不变 |
| E07 | I-02/I-03/I-04/I-06/I-07：删除 SLA 不可达分支、连接器死参数/字段、默认频道重载、运行事件重复值检查和审计闲置字段 | Flowable 既有相关测试；HTTP 64 KiB 有界读取保留，SQL AST tables 对照保留；hash、频道分区、白名单与审计顺序不变 |
| E08 | I-08/I-09：SMTP 安全摘要单源，邮件配置直接消费 PUT 响应 | 八类摘要与未知错误隐藏；异常链去环；浏览器保存无追加 GET、409 保留草稿、400 提示、凭据清空及首次加载失败恢复 |
| E09 | I-13：两个 Handler 复用内部校验对象，删除整配置 stringify→parse | canonical 输出、直接 execute/executeTask、保留变量、字段类型与 BPMN 必填；补 Float、Decimal、BigInteger 溢出和极大指数旧标量语义 |
| E10 | R-04/R-05：OSS 摘要复用于签名/台账，删除死大小参数及默认上传目录闭环 | 四个受控传输测试覆盖 HEAD/PUT/GET/DELETE、签名、补偿、实际大小及临时文件清理；上传/头像入口显式目录引用核对 |
| E11 | SYS-01/SYS-04：在线会话转换筛选单路径，缓存分类固定初始化 | 六个在线用户场景包含过滤组合、过期/无用户会话；精确匹配、顺序、权限与强退保持 |
| E12 | SYS-02：批量锁读、单次批删、提交后按类型失效缓存 | H2 与 MySQL 的成功、写后异常、外层回滚、缓存异常；由部分提交改为整体回滚；缓存故障仍按既有提交后语义传播 |
| E13 | SYS-03：公告服务一个事务删除阅读记录与公告，删除阅读服务中转 | H2 与 MySQL 的成功、重复删除、第二次写失败、阅读/首页查询；两类数据同时回滚 |
| E14 | I-14：调查完成，撤回短事务候选，保留原编排 | 真实 MySQL 与受控 HTTP 证明原长锁及候选重绑窗口；详见下文 |
| E15 | D-05/D-06 已清理；T-08 保留；历史日志已做本地只读调查 | 当前 v2、顶层 formId、损坏元数据拒绝与 BPMN/快照引用保护；真实 MySQL 到期反例；详见下文 |

## 条件项的证据与决定

**I-14：保留原事务。** 基线在 HTTP 响应闸门关闭时，并发端点更新等待超过 2 秒。候选将冻结放在短事务中、网络请求放到事务外，能释放行锁，且通过冻结后撤销/重绑/停用拒绝、轮换读取当前凭据、nonce/协议/范围错误与响应上限验证。独立审查后增加更精确的闸门：在 `requireManagedRuntimeEndpoint` 返回后暂停发送，再让另一个连接重绑端点。候选允许重绑先提交，随后仍使用旧引用对应的合成 Token 发出请求；不满足计划的发送时绑定保护条件。另发现候选会丢失原引擎边界对数据库并发异常的 409 翻译。已完整撤回该生产修改，正式测试保留绑定稳定性回归。未增加新的凭据快照协议或并发框架。

**T-08：保留两阶段检查。** 新增真实 MySQL 用例先在 TEMP 有效期内 prepare 并持有行锁，第二个连接的锁读等待；自然跨过 3 秒有效期后，第二个连接仍在等待，但 bind 必须返回 409。回滚后等待者读到 TEMP，测试精确删除合成 UUID 并确认无残留。行锁保证状态互斥，不能冻结有效期；绑定阶段 SHA 校验本来只执行一次。

**D-05/D-06：按最新数据要求清理。** 只读调查到的本地数据库是 `approvaplat_it`，调查时模型、部署、运行实例、历史实例均为 0。用户明确不要求保留现有测试历史，因此删除 v1 条件资源读取/CEL 兼容和元数据递归、数组、大小写、formKey 兼容。当前写入仍使用 schemaVersion 2 与顶层数值 formId；v1/未知 manifest、损坏 JSON、非法根节点或 formId 失败关闭。正式 BPMN `key_<formId>` 与部署快照引用保护保持。没有声称盘点了未提供连接的其他环境。

**历史日志：** 本地集成凭据响应 Token 字段、短信/OSS 配置密钥字段的审计日志匹配均为 0；没有导出正文、清空日志或轮换凭据。

## 验证

最终执行 `mvn -B -pl ruoyi-admin -am test` 成功（22:07:47，耗时 1 分 42 秒）：**68 个测试通过，0 失败、0 错误、0 跳过**。system 16、flowable 40、admin 12。实际运行了已配置 MySQL 的事务、运行事实复用、附件锁与有效期、连接器绑定竞态测试。

| 后端测试 | 数量 |
|---|---:|
| CredentialAuditLogTest / OnlineUserQueryTest | 6 / 6 |
| SystemDeletionTransactionTest / SysOssTransferTest | 12 / 4 |
| WorkflowConnectorCheckIntegrationTest | 7 |
| WorkflowModelCompilationIntegrationTest | 4 |
| WorkflowMailFailureClassifierTest / WorkflowNotificationSourceIntegrationTest | 2 / 2 |
| WorkflowProcessStartChainIntegrationTest | 13 |
| WorkflowRuntimeFactReuseIntegrationTest | 4 |
| WorkflowTaskCompletionContextIntegrationTest / WorkflowTaskReturnChainIntegrationTest | 5 / 3 |

前端已通过：

```powershell
npm --prefix ruoyi-ui run lint
npm --prefix ruoyi-ui run test:contracts
npm --prefix ruoyi-ui run build:prod
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm --prefix ruoyi-ui run test:e2e -- workflow-page-reuse.spec.js workflow-observation.spec.js
```

合约测试 10/10，Edge 浏览器测试 18/18。浏览器运行真实 Vue/Element Plus/HTTP 客户端，API 响应使用合成 stub；未执行已启动后端服务的完整登录业务验收。OSS 测试使用实际业务方法、H2 SQL 和受控 HTTP transport；没有连接真实外部 OSS、SMTP 或短信供应商。既有编译集成中的部分 SLA/通知依赖仍为 mock，不将其解释为完整异步 worker 或全量可靠消息重放验证。

首轮全量后端测试暴露一个新测试夹具顺序问题：故意损坏的制品被之后的 DMN 全量引用扫描识别。已将正常 DMN 删除验收前置，保留损坏资源失败断言。其余曾发现的新夹具 Java 17 API、MySQL 用户名长度问题已修正；没有删除或放宽业务断言。

## 完成度复核（2026-10-02 22:25）

按原计划逐项复查最终差异、生产与测试引用、调用方、Mapper XML 及验证报告；前端、工作流、系统三个范围另由独立审查复核。发现 I-09 还遗留写而不读的 `meta.configured`，已删除其声明、加载赋值和关闭复位，保留保存事件对外的 `configured` 数据。同时移除已修改 Java 文件中的两个未使用导入：生产文件的 `WfMultiInstanceRound` 与完成链测试的 Mockito `any`。

补删后重新通过前端 lint、10/10 合约测试及 `mvn -B -pl ruoyi-admin -am -DskipTests test`（22:25:54，生产与测试代码编译成功）。本轮没有重跑后端测试和浏览器测试；上文 68 个后端测试及 18 个浏览器测试为此前实际运行结果。

36 个实施条目已清理完成，未发现其他计划内旧实现或无用包装残留。I-14、T-08 按前述实验结论保留；它们的删除条件不成立。真实后端完整业务验收及外部供应商联调仍未执行，覆盖范围见上文。

## 文件与清理

55 个生产文件增加 273 行、删除 679 行，净减少 **406 行**；测试与文档单独计入清单。增加的正式测试用于日志正文、事务失败、受控传输、连接器竞态及前端状态；没有新增通用框架。

`git diff --check` 通过，索引仍为空。ConnectorCheckService 的候选生产修改已完全撤回，与基线无差异。本次合成端点与受管秘密查询残留均为 0；系统事务与附件夹具按唯一标识回滚或精确清理。临时调查脚本和 `.architecture-*.log` 已删除，本次 Vite 已停止；正常构建目录保留。

以下路径相对仓库根目录；既有计划文档保留并更新进度。

```text
docs/plans/architecture-cleanup-execution-plan-20261002.md
docs/plans/architecture-cleanup-execution-result-20261002.md
ruoyi-admin/pom.xml
ruoyi-admin/src/main/java/com/ruoyi/web/controller/monitor/CacheController.java
ruoyi-admin/src/main/java/com/ruoyi/web/controller/monitor/SysUserOnlineController.java
ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysNoticeController.java
ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysOssController.java
ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysSmsController.java
ruoyi-admin/src/main/java/com/ruoyi/web/controller/workflow/WfIntegrationCredentialController.java
ruoyi-admin/src/test/java/com/ruoyi/web/controller/CredentialAuditLogTest.java
ruoyi-admin/src/test/java/com/ruoyi/web/controller/OnlineUserQueryTest.java
ruoyi-common/src/main/java/com/ruoyi/common/utils/file/FileUploadUtils.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/engine/WorkflowEngineOperations.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowExtensionDelegate.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowHttpConnector.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowRaiseBpmnEventHandler.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowSetVariableJavaHandler.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowSqlConnector.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/impl/FlowableWorkflowReferenceChecker.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDeploymentArtifactRepository.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDeploymentService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDmnDecisionService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowExtensionDeploymentService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowExtensionRegistryService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowMailFailureClassifier.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationAdminService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowBpmnEventAuditService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowBpmnEventRuntimeService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowCollaborationChannelService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessDefinitionQueryService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessDetailService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessHistoryProjection.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessInstanceQueryService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessStatusNormalizer.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowRuntimeEventService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowApplicationResubmitApplicationService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowMultiInstanceService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskActionService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskCompletionApplicationService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskReturnApplicationService.java
ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskSlaRuntimeService.java
ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowConnectorCheckIntegrationTest.java
ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowModelCompilationIntegrationTest.java
ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/notification/WorkflowMailFailureClassifierTest.java
ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessStartChainIntegrationTest.java
ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowRuntimeFactReuseIntegrationTest.java
ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/task/WorkflowTaskCompletionContextIntegrationTest.java
ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/task/WorkflowTaskReturnChainIntegrationTest.java
ruoyi-system/pom.xml
ruoyi-system/src/main/java/com/ruoyi/system/mapper/SysDictDataMapper.java
ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysDictDataServiceImpl.java
ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysNoticeReadServiceImpl.java
ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysNoticeServiceImpl.java
ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysUserOnlineServiceImpl.java
ruoyi-system/src/main/java/com/ruoyi/system/service/integration/SysOssService.java
ruoyi-system/src/main/java/com/ruoyi/system/service/ISysNoticeReadService.java
ruoyi-system/src/main/java/com/ruoyi/system/service/ISysUserOnlineService.java
ruoyi-system/src/main/resources/mapper/system/SysDictDataMapper.xml
ruoyi-system/src/test/java/com/ruoyi/system/service/impl/SystemDeletionTransactionTest.java
ruoyi-system/src/test/java/com/ruoyi/system/service/integration/SysOssTransferTest.java
ruoyi-ui/src/api/workflow/notification.js
ruoyi-ui/src/components/workflow/designer/designerFormFieldCatalog.js
ruoyi-ui/src/components/workflow/designer/extensionEventSlaDomain.js
ruoyi-ui/src/components/workflow/designer/HttpConnectorEditor.vue
ruoyi-ui/src/components/workflow/designer/UserTaskSlaEditor.vue
ruoyi-ui/src/views/workflow/form/index.vue
ruoyi-ui/src/views/workflow/notification/index.vue
ruoyi-ui/src/views/workflow/notification/MailConfigDialog.vue
ruoyi-ui/src/views/workflow/work/detail.vue
ruoyi-ui/tests/contracts/designer-resource-state.test.js
ruoyi-ui/tests/e2e/workflow-observation.spec.js
ruoyi-ui/tests/e2e/workflow-page-reuse.spec.js
```
