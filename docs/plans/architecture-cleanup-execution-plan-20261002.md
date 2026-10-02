# 全仓业务链清理清单与执行计划

> 用途：将本文交给新的执行任务，指定一个 `E00`～`E15` 执行包即可分派。\
> 项目：`D:\ruoyiflowable`\
> 审查日期：2026-10-02\
> 审查基线：`172dcee4d996b6271c5436439ddfba3147850fba`\
> 文档状态：已执行 E00～E15；36 项已实施，I-14、T-08 经并发实验保留原实现。原始静态审查与本次实施分别记载，详见 [执行结果](architecture-cleanup-execution-result-20261002.md)。

## 1. 目标与核心判断

先删除冗余、减少状态，再整理模块边界。当前问题集中在重复状态、无用的装配依赖、重复查询与转换，以及少数不合理的事务边界。清理完成前不推进微服务拆分。

本清单共 **38 项**：**34 项可以进入实施，4 项先取得历史数据或并发证据**。部署快照、权限边界、幂等、行锁及消息可靠性机制中，已有明确职责的部分继续保留。

本次采用业务入口覆盖与调用链静态追踪。历史数据是否存在、真实数据库锁行为、外部服务交互效果，按文中的调查和验收要求另行证明。行号用于定位审查基线，实施前应重新定位符号。

### 1.1 条目分类

| 标记 | 含义 | 执行要求 |
|---|---|---|
| A | 已确认可以直接删除 | 先复核调用或控制流，再删除；保持行为 |
| B | 复用现有实现后删除重复部分 | 收敛状态、查询或转换，不新增通用框架 |
| F | 清理并修正现有错误行为 | 单独说明行为变化，验证失败路径 |
| C | 需要先补充证据 | 调查或实验通过后才能删除、迁移或调整事务 |

A、B、F 项的静态证据置信度高。C 项缺少的证据在对应条目中列明。

### 1.2 所有执行任务的共同约束

1. 只执行指定执行包，不顺带重写其他业务链。
2. 开始时检查当前 HEAD、工作区变更及相关调用方；保留其他任务已有修改。
3. 以实际工作区为根目录。本文绝对路径指向审查工作区，独立 worktree 中按相同仓库内路径定位。
4. 保持公开接口、请求与响应、数据库结构、错误码、业务能力及调用方式稳定。F 项按列出的行为变化验收；确需额外改变合同时，明确列出调用方、迁移、测试和文档范围。
5. 删除前复核生产、测试、配置、反射、模板、数据库驱动入口，不能只凭 Java 或 JavaScript 的文本引用数判断。
6. 不新增兼容包装、通用工厂、策略框架、通用消息引擎或新的状态管理体系来完成局部去重。
7. 不为低影响死代码删除搭建新的测试体系。非平凡状态、事务和失败行为变更，优先扩展现有正式测试；确有缺口时增加局部正式测试。
8. 本文不授权清理历史业务数据、数据库表或日志数据。C 项先输出证据和精确处置范围。
9. Windows 默认使用 PowerShell 7；不使用 Bash heredoc；不混用 Windows/WSL 的 node_modules 和 Python 虚拟环境。
10. 临时验证脚本、数据、日志默认在完成前删除；正式测试可以保留。不擅自提交、推送或部署。

### 1.3 直接分派模板

复制下面的提示词，将 `EXX` 替换为执行包编号：

```text
阅读 D:\ruoyiflowable\docs\plans\architecture-cleanup-execution-plan-20261002.md，执行其中的 EXX 包。

先复核对应条目在当前版本仍成立，并检查现有工作区变更。仅修改该包列出的生产文件、直接受影响的调用方和必要正式测试，保留其他任务的修改。

按文档的删除项、保留约束和验收标准实施，不扩展为架构重写，不增加兼容包装或通用框架。C 类条目先做规定的调查或实验，未满足删除条件前不删除。

交付时逐项报告：删除或收敛了什么、保留了什么、运行了哪些验证及结果、哪些验证未执行。若条目在当前代码已不成立，给出具体证据。清理临时产物，不提交、不推送、不部署。
```

## 2. 全量业务链覆盖记录

覆盖 7 个 Maven 模块及前端，沿入口、服务、数据库或引擎、前端调用和测试装配追踪。没有充分删除依据的链路也保留在覆盖表中。

| 业务链 | 已审查范围 | 结论 |
|---|---|---|
| 登录与认证 | 登录、注册、验证码、密码重试、JWT、认证审计 | 未确认可删除机制；不同校验承担不同边界 |
| 用户与个人中心 | CRUD、导入导出、密码、角色、头像、个人资料 | 未发现可整体删除的服务层 |
| 组织与权限 | 角色、菜单、部门、岗位、数据权限、动态路由 | 保留权限、引用检查和事务内复核 |
| 字典与参数 | 字典类型、字典数据、参数、缓存更新 | SYS-02 |
| 公告与阅读 | 公告维护、首页展示、阅读记录、读者查询 | SYS-03 |
| 在线用户与监控 | 会话、强退、缓存、服务信息、操作日志、登录日志 | SYS-01、SYS-04 |
| 通用文件 | 上传、下载、头像、路径校验 | R-05 |
| OSS 与短信 | 配置、凭据、签名、上传台账、补偿、短信供应商 | I-01、R-04 |
| 分类与表单 | 分类 CRUD、表单 CRUD、模板验证、预览、引用检查 | D-07；D-06 待调查 |
| 模型与设计草稿 | 模型管理、历史、XML、个人草稿、版本 CAS | 草稿隔离、所有权及版本保护保留 |
| BPMN 设计器 | 字段目录、参与者、条件、循环、子流程、扩展、事件、SLA | D-03、I-10、I-11、I-12 |
| 校验与发布 | 保存、预检、编译、部署、冻结资源、部署删除 | D-01、D-04、I-05；D-05 待调查 |
| DMN 与子流程 | 决策表、试算、发布、冻结引用、CallActivity 映射及循环检查 | D-02；引用和版本保护保留 |
| 申请与发起 | 草稿保存、提交、重复提交、已提交结果恢复 | 附件链可收敛；提交锁和幂等保留 |
| 任务办理 | 完成、退回、重提、认领、取消认领、委派、转办、解决、拒绝、撤回、终止 | T-01、T-02、T-03 |
| 多实例与抄送 | 增减签、轮次、受控跳转、人工及自动抄送 | T-04；轮次状态和幂等键保留 |
| 工作流附件 | 上传、归属、草稿协调、提交绑定、下载、删除、清理 | T-05、T-06、T-07；T-08 待实验 |
| 查询与详情 | 可发起、待办、待签、已办、抄送、实例、详情、历史、导出 | R-01、R-02 |
| 实例生命周期 | 挂起、恢复、终止、历史删除、子流程关系 | 未确认可删除的状态保护 |
| 通知 | 产生、策略、偏好、收件箱、催办、邮件、短信、投递、补偿 | I-08、I-09 |
| 集成凭据 | 创建、轮换、吊销、白名单、限流、绑定、受管秘密 | I-01 |
| 可靠协作消息 | 发送、接收、频道、序号、重放、租约、补偿、运维 | I-04 |
| 运行事件与等待状态 | MESSAGE、SIGNAL、RECEIVE、等待查询、请求账本、审计 | I-06 |
| BPMN 事件 | Error、Escalation、事件目录、执行、审计 | I-07、I-13 |
| SLA 与业务日历 | 提醒、升级、暂停恢复、计时、日历、审计 | I-02、I-10 |
| HTTP、SQL 与扩展执行 | 端点、数据源、配置冻结、调用、Java/CEL 扩展 | I-03、I-13；I-14 待实验 |
| 定时任务 | CRUD、调度、反射调用、执行日志 | 配置入口仍有效，未确认整类删除项 |
| 代码生成与表单构建 | 生成配置、模板分派、导入、预览、下载、表单构建 | Vue2、Vue3、TS 模板均有真实入口 |
| 公共运行设施 | 请求处理、AOP、缓存、引擎事务、健康指标、数据留存、SQL 初始化 | R-03；未找到支持整层删除的证据 |

## 3. 清理清单：34 项可进入实施

### 3.1 凭据日志

#### I-01：删除敏感凭据的额外日志持久化

- **分类：F；严重度：高；执行包：E00。**
- **证据：**[集成凭据创建与轮换](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/workflow/WfIntegrationCredentialController.java:61)使用默认 `@Log`，响应包含一次性明文 Token；[LogAspect](D:/ruoyiflowable/ruoyi-framework/src/main/java/com/ruoyi/framework/aspectj/LogAspect.java:164)会序列化响应。短信、OSS 配置请求也含有未被现有密码排除器覆盖的密钥字段，入口在 [SysSmsController](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysSmsController.java:67) 和 [SysOssController](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysOssController.java:73)。
- **删除/修改：**集成凭据创建、轮换设置 `isSaveResponseData=false`；短信、OSS 配置新增、修改设置 `isSaveRequestData=false`。删除额外日志正文持久化，不重写全局日志框架。
- **保留：**操作人、时间、动作、结果、凭据业务存储和一次性业务响应。
- **验收：**用合成密钥经过实际 AOP 序列化路径，确认日志对象没有敏感正文、审计元信息仍完整、客户端仍收到正确业务响应。
- **数据边界：**历史日志是否已含密钥尚未查证；清退和轮换属于 E15 的调查结果，不在此包批量执行。

### 3.2 设计、表单与发布

#### D-01：删除部署服务的无用引擎依赖和纯转发方法

- **分类：A；严重度：低；执行包：E01。**
- **证据：**[WorkflowDeploymentService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDeploymentService.java:49)的 `RuntimeService`、`HistoryService` 只有字段、构造参数及赋值；实际引用检查通过锁 Mapper 完成。`safeDmnSnapshots` 只转发一次仓储调用。
- **删除：**两个依赖的字段、构造参数、赋值、import；内联 `safeDmnSnapshots` 唯一调用并删除包装方法；同步删除现有测试构造参数。
- **保留：**部署行锁、运行和历史实例引用、申请草稿引用、CallActivity 引用、非级联删除和事务回滚。
- **测试装配：**[WorkflowModelCompilationIntegrationTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowModelCompilationIntegrationTest.java:403)。

#### D-02：DMN 删除只查询一次部署

- **分类：B；严重度：低；执行包：E01。**
- **证据：**[WorkflowDmnDecisionService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDmnDecisionService.java:120)在同一回调中连续执行两次相同部署查询。
- **删除/修改：**保存第一次查询结果并复用，删除第二次查询。
- **保留：**不存在返回 404、引用冲突返回 409、冻结子部署拒绝独立删除、原事务边界。
- **验收：**不存在、普通无引用来源、已冻结来源、父流程拥有的冻结子部署四条路径。

#### D-03：删除字段目录只写不读的 loading

- **分类：A；严重度：低；执行包：E02。**
- **证据：**[designerFormFieldCatalog.js](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/designerFormFieldCatalog.js:21)的 `loading` 没有生产或测试消费者。
- **删除：**ref、赋值、仅用于复位的 `finally` 和返回字段。
- **保留：**请求复用、版本号、错误状态、有效快照和过期响应隔离。
- **验收：**字段目录加载、失败后重试、连续编辑时的乱序响应。

#### D-04：非 JAVA 扩展的相同参数判断只保留一次

- **分类：B；严重度：低；执行包：E01。**
- **证据：**[WorkflowExtensionRegistryService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowExtensionRegistryService.java:221)在 CEL、HTTP、SQL、FORM_FIELD 四条分支重复拒绝 `implementationKey`。
- **删除/修改：**在类型已验证后执行一次非 JAVA 检查，删除四份重复分支。
- **保留：**JAVA 处理器资格、各类型 Schema、版本锁、版本单调性、校验和及历史版本不可修改。
- **验收：**五种类型正常创建；四种非 JAVA 类型携带实现键仍返回原错误。

#### D-07：消除表单列表首次进入的重复请求

- **分类：B；严重度：中；执行包：E02。**
- **证据：**[表单列表](D:/ruoyiflowable/ruoyi-ui/src/views/workflow/form/index.vue:131)同时在 setup 和 `onActivated` 加载，实际路由使用 KeepAlive。
- **删除/修改：**沿用仓库已有首次加载处理方式，统一首次装载与后续激活，删除重复初始调度；不新增通用生命周期组件。
- **保留：**非缓存首次加载、重新激活刷新、查询、分页。
- **验收：**首次进入一次请求，回页一次刷新，分页一次请求；延迟响应不污染当前结果。

#### I-05：删除扩展快照无调用重载

- **分类：A；严重度：低；执行包：E01。**
- **证据：**[WorkflowExtensionDeploymentService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowExtensionDeploymentService.java:276)的两参数 `snapshotsForDeployment` 没有调用方。生产调用使用三参数版本。
- **删除：**两参数重载及对应说明。
- **保留：**三参数版本以及调用方提供的附加扩展快照；当前模型发布调用传入 `formFieldSnapshots`，即表单字段扩展快照。

#### I-10：SLA 默认配置只维护一个工厂

- **分类：B；严重度：低；执行包：E02。**
- **证据：**[UserTaskSlaEditor](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/UserTaskSlaEditor.vue:92)重复实现 [userTaskSlaDomain](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/userTaskSlaDomain.js:17)已有的八字段默认配置。
- **删除/修改：**编辑器 import 现有工厂，删除本地实现，替换调用。
- **保留：**每次创建独立对象，默认字段和值不变。

#### I-11：三个同步 loading 合并为一个状态

- **分类：B；严重度：低；执行包：E02。**
- **证据：**[extensionEventSlaDomain](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/extensionEventSlaDomain.js:98)的三个 loading 只会同步开启、同步关闭，没有独立生命周期。
- **删除/修改：**底层保留一个 ref，三个现有出口引用同一 ref，删除重复存储及赋值。
- **保留：**并行资源加载、各资源独立错误、请求版本和旧响应隔离。
- **验收：**三个出口行为一致；单资源失败不清空其他资源；旧请求不覆盖新请求。

#### I-12：删除 HTTP 编辑器重复归一化

- **分类：A；严重度：低；执行包：E02。**
- **证据：**[HttpConnectorEditor](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/HttpConnectorEditor.vue:97)处理 `bodyVariable` 后立即调用 `emitChange()`，后者再次执行相同处理。
- **删除：**前一处重复处理。
- **保留：**`emitChange()` 中的统一归一化，覆盖切换端点及方法两条路径。

### 3.3 申请、任务、附件与流程查询

#### T-01：删除退回、重提服务的无用校验器注入

- **分类：A；严重度：低；执行包：E03。**
- **证据：**[WorkflowTaskReturnApplicationService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskReturnApplicationService.java:31)和 [WorkflowApplicationResubmitApplicationService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowApplicationResubmitApplicationService.java:44)没有使用 `requestValidator`。
- **删除：**字段、构造参数、赋值及 import，同步减少现有退回链及运行事实复用测试的构造装配。
- **保留：**退回、重提的权限、状态、流程变量及事务行为。

#### T-02：删除完成任务的不可达空值分支和无价值依赖

- **分类：A；严重度：低；执行包：E03。**
- **证据：**[WorkflowTaskCompletionApplicationService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskCompletionApplicationService.java:246)的变量已由 DTO 规范化为非空不可变 Map。该服务对校验器的唯一使用是创建固定错误。
- **删除/修改：**删除空值兜底；保持原错误码及文本后内联错误创建，删除该项注入；同步调整相关测试构造。
- **保留：**其他服务仍使用的校验器类、任务完成权限、路由及提交契约。

#### T-03：删除任务操作中已被前置条件证明的检查

- **分类：A；严重度：低；执行包：E03。**
- **证据：**[WorkflowTaskActionService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskActionService.java:149)对 `String.valueOf(Long)` 再检查非空文本没有作用；要求活动实例后再次计算实例存在且未挂起也恒为真。
- **删除：**上述局部重复判断。
- **保留：**DTO 的非空和正数约束、实时人员资格、所有权、委派状态、认领及取消认领规则。

#### T-04：删除多实例调整重复列表规范化

- **分类：A；严重度：低；执行包：E03。**
- **证据：**[WorkflowMultiInstanceService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowMultiInstanceService.java:345)再次处理 DTO 已规范化的不可变列表。
- **删除：**空列表兜底、重复复制及已失效的 null 分支。
- **保留：**非法元素、动作与字段组合、人员资格、revision 优先检查及 CAS。

#### T-05：删除同一同步链内重复附件归属检查

- **分类：A；严重度：低；执行包：E06。**
- **证据：**[WorkflowAttachmentService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentService.java:961)的 `assertPersistedDraftAttachment` 唯一调用方已验证记录、用户和字段，期间没有状态变化。
- **删除：**重复检查以及 `ownerUserId`、`fieldName` 等仅服务这些检查的参数。
- **保留：**DRAFT 状态、草稿 ID、生命周期校验；其他 TEMP 绑定路径的独立归属检查不在删除范围。

#### T-06：草稿保存和提交复用附件协调实现

- **分类：B；严重度：中；执行包：E06。**
- **证据：**[WorkflowAttachmentService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentService.java:394)的 `reconcileDraftAttachments` 和 `prepareDraftSubmissionVariables` 重复实现 TEMP→DRAFT、移除→DELETED、归属检查等循环。
- **删除/修改：**在现有服务内共享协调结果，删除两套相同处理。必要新增仅限内部私有结果或方法，不新增框架。
- **保留：**保存与正式提交的校验强度差异、锁顺序、当前状态读取、提交变量投影及回滚。
- **验收：**保存、移除、复用 DRAFT、添加 TEMP、重复字段、越权、过期、提交失败回滚；涉及锁行为时使用真实 MySQL。

#### T-07：附件引用去重结果只计算一次

- **分类：B；严重度：低；执行包：E06。**
- **证据：**[WorkflowAttachmentService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentService.java:1117)的 `checkedReferences` 已计算唯一 ID，部分调用方紧接着再次计算。
- **删除/修改：**复用规范化引用和 ID 集合，删除重复遍历。
- **保留：**只调用 `checkedReferences` 的绑定路径仍执行重复和数量校验，不得机械删除第一次计算。

#### R-01：删除查询和状态规范化的无用参数

- **分类：A；严重度：低；执行包：E04。**
- **证据：**[WorkflowProcessDefinitionQueryService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessDefinitionQueryService.java:463)的 `isDefinitionStartable` 不使用 actor；[WorkflowProcessStatusNormalizer](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessStatusNormalizer.java:41)不使用 `deleteReason`。
- **删除：**内部参数、调用实参和对应说明。同步修改详情、实例查询、历史投影等直接调用方。
- **保留：**对外数据和历史记录中的删除原因字段、可发起资格及批量事实校验。

#### R-02：详情页删除镜像状态和重复深拷贝

- **分类：B；严重度：中；执行包：E05。**
- **证据：**[detail.vue](D:/ruoyiflowable/ruoyi-ui/src/views/workflow/work/detail.vue:552)同时保存 `detail.formDetail`、`applicationForm`、`nodeTaskForm`，后两者只在加载和清空时同步。历史表单 `hydratedValues` 又进行深拷贝，而只读 [ProcessFormRenderer](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/ProcessFormRenderer.vue:53)已逐字段复制。
- **删除/修改：**将表单元数据及历史表单改为带空值默认的派生读取，删除镜像 ref、同步赋值、父层 map 和多余深拷贝。
- **保留：**用户编辑的 `taskFormValues`、`applicationFormValues`，任务上下文、刷新版本、多实例 revision 和只读渲染器的防修改行为。
- **验收：**定时刷新、隐藏页面、乱序响应、切换任务、同任务输入保留和历史附件只读展示。

#### R-03：引擎事务入口只保留一次权威检查

- **分类：A；严重度：低；执行包：E04。**
- **证据：**[WorkflowEngineOperations](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/engine/WorkflowEngineOperations.java:109)两个重载检查隔离级别后，同步调用的 Function 重载再次检查；`writeAsUser(String, Runnable)` 无调用。
- **删除：**外层两次重复检查、无调用的 Runnable 重载。
- **保留：**各公开入口的事务注解、空 action 检查、Function 中权威检查；有真实调用的 Supplier 重载及身份上下文恢复。

#### I-02：删除 SLA 不可达分支及无用参数

- **分类：A；严重度：低；执行包：E07。**
- **证据：**[WorkflowTaskSlaRuntimeService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskSlaRuntimeService.java:293)已排除所有非 ACTIVE 状态，后续 ESCALATED 判断不可达；`requireNotification` 有未使用参数。
- **删除：**后面的不可达判断、未使用参数及实参。
- **保留：**早期 ACTIVE 检查、提醒序号、防重、行锁、revision CAS、MANDATORY 事务、审计和站内信原子性；其他调用使用的 Mapper 状态范围不变。

### 3.4 连接器、运行事件与协作

#### I-03：删除连接器未使用的输入和中间数据

- **分类：A；严重度：低；执行包：E07。**
- **证据：**[WorkflowHttpConnector](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowHttpConnector.java:267)和 [WorkflowSqlConnector](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowSqlConnector.java:154)的执行方法不使用 snapshot 参数；HTTP 响应局部变量无消费者；SQL 私有 `FrozenConfig.tables` 构造后不读取。
- **删除：**snapshot 参数和 Delegate 实参、闲置局部变量绑定、私有 record 的闲置成员和构造参数。
- **保留：**HTTP `readBounded()` 调用、响应大小限制和关闭；冻结 JSON 的 tables 字段、AST 一致性检查、外层快照 checksum/schema 校验。

#### I-04：删除协作频道三个无调用默认重载

- **分类：A；严重度：低；执行包：E07。**
- **证据：**[WorkflowCollaborationChannelService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowCollaborationChannelService.java:33)实际调用均显式传递 deliveryMode。
- **删除：**默认 COLLABORATION 的三个无调用重载。
- **保留：**显式模式入口、频道哈希算法以及 COLLABORATION/POINT_TO_POINT 的历史分区。

#### I-06：运行事件变量只进行一次值校验

- **分类：A；严重度：低；执行包：E07。**
- **证据：**[WorkflowRuntimeEventService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowRuntimeEventService.java:94)规范化和请求哈希已验证类型、长度、有限数，随后对同一不可变集合再次检查。
- **删除：**第二次 `scalarText` 校验，后续方法职责收敛为变量白名单检查。
- **保留：**第一次规范化、请求 hash、凭据变量白名单、值错误在认证前及白名单错误在认证后的现有顺序、独立审计。

#### I-07：删除 BPMN 事件内部记录的闲置字段

- **分类：A；严重度：低；执行包：E07。**
- **证据：**[WorkflowBpmnEventAuditService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowBpmnEventAuditService.java:103)内部 RuntimeEvent record 的 `processDefinitionKey` 从未读取。
- **删除：**该内部成员、运行服务唯一构造点实参和说明。
- **保留：**数据库字段、HTTP VO、定义查询的其他用途、事件匹配、独立审计和通知。

#### I-13：删除 Java Handler 的 JSON 字符串往返

- **分类：B；严重度：中；执行包：E09。**
- **证据：**[WorkflowSetVariableJavaHandler](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowSetVariableJavaHandler.java:147)和 [WorkflowRaiseBpmnEventHandler](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowRaiseBpmnEventHandler.java:160)先规范化成 JSON 字符串，再解析回节点。
- **删除/修改：**各 Handler 内部共享解析及验证结果，删除运行时 stringify→parse 往返。
- **保留：**公开 `validateAndNormalizeConfig` 的返回格式、直接 execute/executeTask 的验证、保留变量保护、类型及必填字段检查。
- **验收：**合法 JSON、错误类型、保留变量、Error/Escalation 必填字段、canonical 输出一致性；不引入统一 prepared-config 框架。

### 3.5 通知

#### I-08：SMTP 安全错误摘要只维护一份

- **分类：B；严重度：中；执行包：E08。**
- **证据：**[WorkflowMailFailureClassifier](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowMailFailureClassifier.java:219)和 [WorkflowNotificationAdminService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationAdminService.java:213)重复维护八类 SMTP 错误映射。
- **删除/修改：**在现有 classifier 提供按 code 取安全摘要的入口，删除管理服务八份重复映射。
- **保留：**非 SMTP 错误、未知错误隐藏、用户提示与运维摘要的差异、异常因果链遍历及去环。
- **验收：**所有原 SMTP code 的摘要一致，未知错误不暴露敏感详情。

#### I-09：邮件配置保存直接使用保存响应

- **分类：B；严重度：中；执行包：E08。**
- **证据：**[MailConfigDialog](D:/ruoyiflowable/ruoyi-ui/src/views/workflow/notification/MailConfigDialog.vue:254)丢弃 PUT 最新配置，再 GET 一次，并维护“保存成功但刷新失败”等额外状态。特定 MAIL_CREDENTIAL_REENTRY_REQUIRED 分支判断 409，而后端实际返回 400。
- **删除/修改：**使用保存响应中的脱敏配置和 revision；删除额外 GET、refreshRequired/revision:null 分支、不可达的特定 409 分支及 API 中对应注释。
- **保留：**首次打开加载、真实 revision 冲突、草稿保留、finally 清空明文凭据、测试未保存配置的独立路径。
- **验收：**保存无需追加 GET 即可完成；真实 409 保留草稿；后端安全错误正常展示；初次加载失败行为明确。

### 3.6 文件与存储

#### R-04：OSS 临时文件摘要只计算一次

- **分类：B；严重度：中；执行包：E10。**
- **证据：**[SysOssService](D:/ruoyiflowable/ruoyi-system/src/main/java/com/ruoyi/system/service/integration/SysOssService.java:345)对同一未修改临时文件计算两次 SHA，分别用于台账及签名；`execute` 的 contentLength 参数未读取。
- **删除/修改：**一次计算摘要，在内部供签名和台账复用；删除死参数及各调用实参。
- **保留：**实际大小校验、空载荷摘要、签名、访问策略、上传补偿及 finally 临时文件清理。
- **验收：**签名与台账摘要一致；HEAD/PUT/GET/DELETE 及补偿路径仍正确，失败不遗留临时文件。

#### R-05：删除通用上传工具默认目录闭环

- **分类：A；严重度：低；执行包：E10。**
- **证据：**[FileUploadUtils](D:/ruoyiflowable/ruoyi-common/src/main/java/com/ruoyi/common/utils/file/FileUploadUtils.java:39)默认目录字段、getter、单参数 upload 仅内部互相引用；实际上传调用显式传目录。
- **删除：**该字段、getter、单参数重载和相关闲置 import。
- **保留：**其他上传重载、通用上传、头像及下载路径工具；实施前再次核对配置、模板和反射字符串。

### 3.7 系统管理

#### SYS-01：在线用户转换和筛选收敛为一条路径

- **分类：F；严重度：中；执行包：E11。**
- **证据：**[SysUserOnlineController](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/monitor/SysUserOnlineController.java:47)按 IP、用户名组合分四条路径；[SysUserOnlineServiceImpl](D:/ruoyiflowable/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysUserOnlineServiceImpl.java:25)又维护三个筛选方法。
- **删除/修改：**安全转换一次，再按给定条件筛选；删除三个重复服务方法、对应接口声明、分支调度及末尾补救式去空值。
- **保留：**精确匹配、返回顺序、权限和强退。
- **行为修正：**缓存读取过程中会话过期时，不再在筛选路径发生空引用。
- **验收：**无条件、仅 IP、仅用户名、二者组合，以及缓存过期五类场景。

#### SYS-02：字典批删统一事务并按类型失效缓存

- **分类：F；严重度：中；执行包：E12。**
- **证据：**[SysDictDataServiceImpl](D:/ruoyiflowable/ruoyi-system/src/main/java/com/ruoyi/system/service/impl/SysDictDataServiceImpl.java:60)逐条查询、删除、重查字典类型和写缓存，缺少整体事务；仓库已有批删 Mapper SQL。
- **删除/修改：**批量读取所选记录，按类型去重，在一个事务执行批删，提交后每种类型失效一次；删除逐条重载和缓存重写循环，无消费者的单条删除入口可在复核后删除。
- **必要新增：**若无可复用入口，仅增加批量查所选记录的方法和 SQL；复用已有提交后缓存失效方式。
- **保留：**公开接口、权限、字典内容语义。
- **行为修正：**失败时从可能部分提交改为整体回滚。
- **验收：**同类型、跨类型、删除中途失败、事务回滚、缓存失效异常；未提交时不得发布已删除状态。

#### SYS-03：公告与阅读记录由一个服务事务删除

- **分类：F；严重度：中；执行包：E13。**
- **证据：**[SysNoticeController](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysNoticeController.java:147)先删阅读记录再调用另一服务删公告，二者不在同一事务，数据库没有替代这层原子性的级联删除。
- **删除/修改：**将两次写入归并到现有公告服务的一个事务；控制器只调用一次；复核无其他调用后删除多余阅读服务中转方法及接口声明，保留实际 Mapper 删除能力。
- **保留：**公告及阅读模块其余职责、公开接口和权限。
- **验收：**第二次写入失败时两类数据均回滚；成功删除、重复删除、首页和读者查询正常。

#### SYS-04：缓存分类固定数据只初始化一次

- **分类：A；严重度：低；执行包：E11。**
- **证据：**[CacheController](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/monitor/CacheController.java:36)静态可变列表在实例初始化块追加固定分类，重复实例化会重复追加。
- **删除/修改：**改为一次性固定列表，删除实例初始化块。
- **保留：**名称、顺序、内容和缓存操作接口。无需为该简单修改另建测试体系。

## 4. 条件清单：4 项先补证据

### D-05：旧版部署条件快照兼容

- **分类：C；潜在影响：中；执行包：E15。**
- **位置：**[WorkflowDeploymentArtifactRepository](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowDeploymentArtifactRepository.java:276)。
- **事实：**当前写入版本为 2，但读取支持 v1 `conditions-v1.json`；已有正式兼容测试。
- **调查：**盘点所有环境部署 manifest 版本、旧资源数量、运行实例、历史实例、父流程冻结引用。
- **删除条件：**证明无 v1 存量，或另行批准并完成迁移后，删除兼容资源名、读取分支和对应测试。
- **交付：**各环境数量、引用链和决定；未满足条件前不删代码。不能用当前写入为 v2 推断历史不存在。

### D-06：模型元数据的通用递归兼容解析

- **分类：C；潜在影响：中；执行包：E15。**
- **位置：**[FlowableWorkflowReferenceChecker](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/impl/FlowableWorkflowReferenceChecker.java:221)。
- **事实：**引用检查支持根数组、嵌套、大小写变体、formId/formKey 数组值；当前模型正式写入仅生成受控顶层字段。
- **调查：**全量盘点 `ACT_RE_MODEL.META_INFO_` 形态，确认外部写入入口及历史引用。
- **删除条件：**全量符合当前协议后，才收敛为顶层 formId 读取并删递归兼容；不以模型列表能打开代替全量证明。
- **保留：**损坏元数据阻止删除、BPMN 及部署快照引用保护。

### T-08：任务附件准备与绑定的重复锁读

- **分类：C；潜在影响：中；执行包：E15。**
- **位置：**[WorkflowAttachmentService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentService.java:864)。
- **事实：**准备与绑定存在重复引用处理和锁读，但两阶段的时效与状态合同尚需证明。
- **实验：**证明附件状态、有效期、引用集合不可变性和锁持有范围允许复用，使用真实 MySQL 验证并发。
- **删除条件：**复用结果不会越过必要的状态更新或时效校验，才能另立实施变更。
- **保留：**SHA 校验目前只发生在绑定阶段，不属于重复 checksum。

### I-14：连接器认证探测持锁等待 HTTP

- **分类：C；潜在影响：中；执行包：E14。**
- **位置：**[WorkflowConnectorCheckService](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowConnectorCheckService.java:25)。
- **事实：**写事务内锁端点、再次冻结端点，再同步 HTTP 探测；没有需要与远端响应原子提交的业务写入。
- **候选调整：**短事务完成授权和冻结，事务外发送探测，删除包住网络等待的旧编排。
- **前置证明：**发送阶段继续复核受管绑定、吊销、重绑及当前凭据；不能机械删除注解。
- **验收：**可控慢 HTTP 下不长时间占用端点行锁；凭据撤销或重绑后不使用失效凭据；nonce、范围和协议错误正确拒绝；不创建或推进业务实例。
- **停止条件：**无法证明发送时撤销检查仍成立，保留原实现并报告证据缺口。

## 5. 明确保留的机制

| 机制 | 保留依据与执行边界 |
|---|---|
| 草稿提交前后读取、行锁、revision、幂等结果 | 分别处理授权、并发修改、重复请求及已提交结果恢复 |
| 部署删除的二次引用检查 | 使用当前读和行锁，承担并发删除保护 |
| 作者模型、运行模型、冻结快照 | 分别承担编辑、执行和历史版本稳定性；保存、预检和发布已有共享准备过程 |
| 用户编辑草稿 | 避免轮询覆盖输入，不能全部变成详情对象派生状态 |
| 多实例引擎变量、轮次记录、受控转移状态 | 服务引擎执行、业务轮次和转移过程，不是同一事实的无意义复制 |
| 通知 Outbox 与协作 Outbox | 顺序、补偿资格、业务撤销和目标关联不同 |
| 普通运行事件与可靠协作消息 | 输入协议、账本、序号和重放合同不同 |
| 外部 SQL 与主库 SQL | 事务范围和幂等要求不同 |
| 工作流附件、通用文件、OSS | 归属、生命周期、授权和存储协议不同 |
| 手工与自动抄送 | 来源、冻结内容和幂等键不同 |
| 邮件凭据与受管连接器秘密 | 密钥来源、格式及存量密文不同，合并需要迁移证明 |
| 数据权限代理、事务内引用复核 | AOP 和实时授权有真实作用，不能以跨层调用为由删除 |
| 生成器模板、调度任务、初始化 SQL | 配置、模板、反射和历史环境仍可能是正式消费者 |
| 数据留存和投递状态机 | 保留未读、未终结、租约及诊断合同，不按表数量整体删除 |

## 6. 执行包与验收

每个执行包的生产文件白名单由其条目的位置链接确定。直接调用方和测试的增补范围按下表限定，超出时先说明原因，不能自行扩大为整链重写。

| 执行包 | 条目 | 直接调用方及测试范围补充 | 核心验收 |
|---|---|---|---|
| E00 | I-01 | 三类凭据控制器；必要的正式 AOP 日志验证 | 合成密钥不进日志，业务响应和审计元信息完整 |
| E01 | D-01、D-02、D-04、I-05 | 现有模型编译集成测试；必要的直接调用点 | 减少依赖、查询、重载；DMN 删除、扩展版本及快照合同保持 |
| E02 | D-03、D-07、I-10、I-11、I-12 | 相关设计器内部消费者；现有页面复用测试 | 首次只请求一次；乱序安全；默认对象独立；资源失败隔离 |
| E03 | T-01、T-02、T-03、T-04 | 完成、退回、运行事实复用集成测试；必要的局部正式操作测试 | 减少装配；办理错误语义、资格检查及多实例输入合同保持 |
| E04 | R-01、R-03 | 状态规范化的详情、实例查询、历史投影调用点；现有相关集成测试 | 删除参数和重载；事务注解、隔离级别和身份上下文有效 |
| E05 | R-02 | 详情模板；现有观察测试；仅必要时修改只读渲染器调用 | 刷新、切任务、乱序不覆盖输入；历史表单保持只读 |
| E06 | T-05、T-06、T-07 | 现有流程发起集成测试；必要的附件边界测试 | 保存、移除、复用、提交、回滚和并发归属正确 |
| E07 | I-02、I-03、I-04、I-06、I-07 | ExtensionDelegate、BpmnEventRuntimeService 等直接调用点；相关正式测试 | 响应上限、变量白名单、请求 hash、频道键、SLA 防重不变 |
| E08 | I-08、I-09 | 通知 API 注释；必要的弹窗及错误映射正式测试 | SMTP 摘要一致；保存不追加 GET；冲突保留草稿；清空凭据 |
| E09 | I-13 | 现有模型编译集成测试及 Handler 局部正式测试 | 删除 JSON 往返；canonical 输出及直接执行验证保持 |
| E10 | R-04、R-05 | OSS 私有调用点；通用上传、头像入口仅验证；必要的签名测试 | 一次摘要；签名、台账、补偿、临时文件清理正确 |
| E11 | SYS-01、SYS-04 | ISysUserOnlineService 接口；必要的查询局部正式测试 | 四种筛选组合一致；缓存过期不报错；分类不重复 |
| E12 | SYS-02 | 字典 Mapper 及 SQL；现有缓存失效方式；局部正式事务测试 | 批删原子性、提交后按类型失效、失败回滚 |
| E13 | SYS-03 | 公告服务、阅读服务及接口；现有 Mapper；局部正式事务测试 | 公告与阅读记录共同提交或回滚 |
| E14 | I-14 | CheckService、HttpConnector、端点和绑定服务的必要调用边界；正式并发验证 | 先证实短事务与撤销检查，再调整；慢 HTTP 不长持锁 |
| E15 | D-05、D-06、T-08；I-01 的历史数据附项 | 只读盘点、实验和证据记录；初始阶段不删除代码或数据 | 得到精确存量、引用、并发结论及后续可删范围 |

### 6.1 E00：敏感日志清理

- 优先执行，先阻止新增敏感正文日志。
- 范围是注解与直接验证，不引入全局新脱敏体系。
- 历史日志调查交给 E15；不得自动清空日志或轮换凭据。

### 6.2 E01：设计后端减法

- 先删除无用装配和重载，再收敛 DMN 查询及非 JAVA 规则。
- 对 DMN 四类删除路径补齐必要验证，不能只以编译通过作为完成。
- 部署删除的当前读和引用检查不动。

### 6.3 E02：设计前端收敛

- 删除未消费状态，复用已有默认值和加载方式。
- 沿用现有请求版本保护，不增加新的状态框架。
- 验收字段目录、表单缓存页和三类资源加载的实际交互。

### 6.4 E03：任务办理减法

- 先精简服务构造及对应测试装配，再删已证明冗余的判断。
- 检查完成、退回、重提、认领和转办的错误优先级未变化。
- 多实例元素、资格、revision 等实际边界继续保留。

### 6.5 E04：查询及引擎内部入口

- 只修改内部签名、重载和同步重复检查。
- 各公开事务入口继续经过正确代理，核心事务校验与身份恢复保留。
- 不删除对外或历史数据中的 deleteReason。

### 6.6 E05：详情页状态收敛

- 将元数据镜像变为派生值，删除历史表单父层重复拷贝。
- 用户编辑值继续拥有独立状态。
- 重点检查轮询过程中未提交输入，以及旧请求返回时的任务上下文。

### 6.7 E06：附件协调收敛

- T-05 的局部删除先完成，再进行 T-06、T-07 的同文件收敛。
- 保持保存、提交和绑定的不同合同，T-08 不自动纳入本包。
- 锁、状态迁移或事务范围若改变，必须执行真实 MySQL 验证。

### 6.8 E07：运行与集成内部减法

- 修改分散在不同文件，可在包内分成小变更。
- HTTP 只删除闲置变量绑定，必须继续读取有上限的响应体。
- 不改变频道键、请求 hash、事件变量白名单及 SLA 状态机。

### 6.9 E08：邮件配置与错误映射

- 复用现有 classifier，直接消费保存响应。
- 验证真实 409 冲突和凭据清空，不保留由额外 GET 引起的伪刷新失败状态。
- 用户提示和运维安全摘要仍为不同用途。

### 6.10 E09：Handler 解析收敛

- 各 Handler 内部复用解析结果，删除字符串往返。
- 不移动直接执行入口的信任边界，不新增 prepared-config 通用框架。
- 保留现有直接执行拒绝保留变量的测试。

### 6.11 E10：文件与 OSS 减法

- 同一临时文件的摘要只计算一次，签名和台账复用。
- 继续验证空载荷请求、上传失败补偿及文件清理。
- 通用上传的默认目录闭环删除后，实际上传及头像入口仍使用显式目录。

### 6.12 E11：在线用户与缓存分类

- 同步删除控制器分支、服务重复方法和接口声明。
- 缓存过期场景按安全转换后过滤处理。
- 固定分类只初始化一次，无需为简单数据声明另建测试框架。

### 6.13 E12：字典删除事务

- 单独实施，不能混入纯删除变更。
- 批量加载、批量删除、提交后按类型失效缓存。
- 明确记录“部分提交变为整体回滚”的行为修正，并验证缓存异常处理。

### 6.14 E13：公告删除事务

- 单独实施，将两次写入归并到现有公告服务。
- 在第二次删除制造失败，证明阅读记录也回滚。
- 无调用的服务中转可以删除，阅读模块其余职责不合并。

### 6.15 E14：认证探测事务

- 首先建立可控慢 HTTP 和真实数据库并发证据。
- 证明发送时撤销、重绑和凭据检查仍有效后，再移出长事务。
- 如果证据不成立，保持原实现，交付阻碍及可复现结果。

### 6.16 E15：历史和附件证据调查

- D-05、D-06 分别输出所有目标环境的存量与引用分布。
- T-08 输出两阶段状态、时效、锁持有及并发复用结论。
- 历史凭据日志只输出数量、范围和处置建议，不在报告中导出真实密钥。
- 对缺少环境访问的部分明确标记未调查，不用本地空库代替历史存量证明。
- 结果通过后另行分派精确删除、迁移或数据清退任务。

## 7. 执行顺序与并行边界

1. 先完成 E00。
2. E01～E04、E07、E11 可以按文件拆分并行，优先减少装配和死代码。
3. 随后完成 E05、E06、E08～E10、E12、E13，对状态收敛、协调及失败行为执行对应验收。
4. E14、E15 可以提前调查，满足证据条件后再实施。
5. 清理完成后再整理模块依赖方向和测试入口；本计划不创建微服务、消息中间件或新的远程调用层。

多个执行包会修改同一个测试文件，合并阶段必须串行复核：

- 模型编译集成测试可能被 E01、E07、E09 使用。
- 运行事实复用测试可能被 E03、E04、E07 使用。
- 附件服务被 E06 修改，E15 的 T-08 调查必须基于最新实现重新判断。
- I-03 与 I-14 均涉及 HTTP Connector，先协调基线，避免覆盖。
- E00 与 E10 分别涉及 OSS 控制器和服务，可并行，但日志验证需要确认最终端到端链。

同文件碰撞优先通过独立 worktree 和串行整合解决，不覆盖已有变更、不广泛暂存、不重置工作区。

## 8. 测试库存、命令与证据边界

### 8.1 可以复用的正式测试

后端：

- [WorkflowModelCompilationIntegrationTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowModelCompilationIntegrationTest.java)
- [WorkflowNotificationSourceIntegrationTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/notification/WorkflowNotificationSourceIntegrationTest.java)
- [WorkflowProcessStartChainIntegrationTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessStartChainIntegrationTest.java)
- [WorkflowRuntimeFactReuseIntegrationTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowRuntimeFactReuseIntegrationTest.java)
- [WorkflowTaskCompletionContextIntegrationTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/task/WorkflowTaskCompletionContextIntegrationTest.java)
- [WorkflowTaskReturnChainIntegrationTest](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/task/WorkflowTaskReturnChainIntegrationTest.java)

前端：

- [request-submission.test.js](D:/ruoyiflowable/ruoyi-ui/tests/contracts/request-submission.test.js)
- [workflow-user-options.test.js](D:/ruoyiflowable/ruoyi-ui/tests/contracts/workflow-user-options.test.js)
- [workflow-page-reuse.spec.js](D:/ruoyiflowable/ruoyi-ui/tests/e2e/workflow-page-reuse.spec.js)
- [workflow-observation.spec.js](D:/ruoyiflowable/ruoyi-ui/tests/e2e/workflow-observation.spec.js)
- [workflow-drafts.spec.js](D:/ruoyiflowable/ruoyi-ui/tests/e2e/workflow-drafts.spec.js)
- [upload-settlement.spec.js](D:/ruoyiflowable/ruoyi-ui/tests/e2e/upload-settlement.spec.js)

### 8.2 已知覆盖缺口

- 现有通知来源集成测试 mock outbox，不能证明真实 SMTP worker。
- 模型编译测试覆盖配置和部署；部分 SLA 测试 mock runtime，不能证明真实提醒与升级并发。
- 运行事实复用测试不能替代可靠协作序号、租约及运行事件账本的完整重放验证。
- 字典与公告失败回滚、部分任务操作、多实例并发、真实邮件投递需要按改动补充局部正式测试。
- H2 通过不能证明 MySQL 行锁和竞争行为；前端 mock 通过不能证明后端事务。

### 8.3 基础检查命令

以下是后续实施命令，本次静态审查没有运行它们。工作目录为项目根目录。

```powershell
mvn -B -pl ruoyi-admin -am test
npm --prefix ruoyi-ui run lint
npm --prefix ruoyi-ui run test:contracts
npm --prefix ruoyi-ui run build:prod
```

对六个现有工作流集成测试进行定向回归：

```powershell
mvn -B -pl ruoyi-admin -am '-Dtest=WorkflowModelCompilationIntegrationTest,WorkflowNotificationSourceIntegrationTest,WorkflowProcessStartChainIntegrationTest,WorkflowRuntimeFactReuseIntegrationTest,WorkflowTaskCompletionContextIntegrationTest,WorkflowTaskReturnChainIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

真实 MySQL 测试使用仓库既有的 `WORKFLOW_MYSQL_TEST_URL`、`WORKFLOW_MYSQL_TEST_USERNAME`、`WORKFLOW_MYSQL_TEST_PASSWORD` 环境变量。不得在命令输出或交付报告中打印凭据。未配置导致的跳过必须记为未验证。

浏览器验收按仓库现有 Playwright 配置和启动方式执行，相关页面需使用真实应用服务。不要将只编译前端写成页面验收通过。

### 8.4 按改动选择验证

| 改动类型 | 最低有效证据 |
|---|---|
| 普通死代码、无用参数 | 引用复核、编译和现有相关测试 |
| 前端状态与加载收敛 | 首次加载、重新进入、乱序响应、输入保留等实际行为 |
| 事务和锁 | 真实 MySQL 下的失败回滚、并发及锁行为 |
| 外部调用 | 可控成功/失败/慢响应，签名、补偿和撤销竞态 |
| 历史兼容删除 | 全量存量、引用关系及必要迁移完成证据 |

## 9. 每个执行包的完成标准

- [ ] 当前代码仍符合条目证据；不成立的项已给出具体说明。
- [ ] 实施条目的指定旧实现已真正删除，没有保留一层无价值兼容包装。
- [ ] 调查条目已记录证据、删除条件是否满足及后续决定；证据不足时保留原实现，不将调查完成登记为删除完成。
- [ ] 明确记录减少的依赖、重复状态、查询、转换或中转方法。
- [ ] 公开接口和保留合同保持稳定；F 项行为修正已单独验证。
- [ ] 针对改动运行了必要验证，失败和跳过没有记为通过。
- [ ] 未混入其他任务修改，临时验证产物已清理。
- [ ] 已提供精确文件清单、验证命令、结果和未执行范围。

建议交付格式：

```text
执行包：EXX
实施完成条目：...
调查完成条目及是否满足删除条件：...
不成立或未完成条目及证据：...

删除：...
最小调整：...
必要新增：...
保留合同：...

验证命令与结果：...
未执行验证及原因：...
修改文件：...
临时产物清理：...
```

## 10. 进度登记

2026-10-02 完成登记。用户在执行中说明现有数据均为无用测试数据，D-05/D-06 据此移除旧格式兼容；I-14/T-08 仍按并发证据保留。调查完成不登记为删除完成。

| 执行包 | 当前状态 | 执行任务/变更记录 | 验证结论 |
|---|---|---|---|
| E00 | 已实施 | I-01 六处注解；正式 AOP 验证 | 6 个合成密钥日志用例通过 |
| E01 | 已实施 | D-01/D-02/D-04/I-05 | 模型编译、DMN 四路径、五类型扩展通过 |
| E02 | 已实施 | D-03/D-07/I-10/I-11/I-12 | 合约及 Edge 浏览器请求/状态测试通过 |
| E03 | 已实施 | T-01～T-04 | 完成、退回、运行事实复用测试通过；冗余判断静态等价复核 |
| E04 | 已实施 | R-01/R-03 | 事务隔离、身份恢复及查询集成测试通过 |
| E05 | 已实施 | R-02 | 轮询、切任务、输入保留及历史附件只读通过 |
| E06 | 已实施 | T-05～T-07 | 草稿协调、非法引用与失败回滚通过；原锁序和状态条件保持 |
| E07 | 已实施 | I-02/I-03/I-04/I-06/I-07 | 编译和相关工作流回归通过；HTTP 有界响应与 SQL AST 校验保持 |
| E08 | 已实施 | I-08/I-09 | 摘要、保存无追加 GET、409/400、凭据清空通过 |
| E09 | 已实施 | I-13 | canonical、直接执行、类型/保留变量、极端数值兼容通过 |
| E10 | 已实施 | R-04/R-05 | 4 个受控 OSS 传输用例通过，上传/头像显式目录引用核对 |
| E11 | 已实施 | SYS-01/SYS-04 | 在线会话过滤与过期测试，固定分类静态复核 |
| E12 | 已实施 | SYS-02 | H2/真实 MySQL 批删、回滚、提交后缓存失效验证 |
| E13 | 已实施 | SYS-03 | H2/真实 MySQL 联动删除、重复删除、失败回滚验证 |
| E14 | 调查完成，保留 | I-14 短事务候选已撤回 | MySQL 重绑闸门反例；原事务 7 个正式测试通过 |
| E15 | 清理及调查完成 | D-05/D-06 已删旧兼容；T-08 保留 | 当前协议/损坏数据测试通过；MySQL 证明持锁不能冻结有效期 |
