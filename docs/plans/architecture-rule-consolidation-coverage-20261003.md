# 全仓业务规则扫描覆盖记录

日期：2026-10-03。基线：`70b2c0a1357c56ab1c7e645ef1410046c5a303c4`。

执行任务和验收要求见[主计划](D:/ruoyiflowable/docs/plans/architecture-rule-consolidation-plan-20261003.md)。本记录用于核对覆盖面、保留理由和 B06 页面范围。

## 1. 仓库库存与审查方式

基线共有 **1,310 个 Git 跟踪文件**。其中生产 Java 771 个，Java 正式测试 29 个；前端 src 中 JS/Vue/JSON/CSS/SCSS 262 个，tests 下文件 11 个；Mapper XML 44 个，SQL 19 个，Velocity 模板 21 个。分类之间按路径/扩展名统计，不代表各项之和等于仓库总数。

| 范围 | 跟踪文件 | 生产 Java | 正式测试文件 | 主要审查内容 |
|---|---:|---:|---:|---|
| ruoyi-admin | 70 | 57 | 4 | HTTP 入口、权限、请求协议、配置装配、日志边界 |
| ruoyi-common | 105 | 104 | 0 | 共享 DTO、缓存/文件/安全/序列化工具及调用方 |
| ruoyi-flowable | 521 | 470 | 22 | 作者设计、部署冻结、运行任务、附件、身份、通知、集成与协作 |
| ruoyi-framework | 47 | 46 | 0 | 登录/Token/权限快照、数据范围、拦截器、AOP、配置 |
| ruoyi-generator | 38 | 13 | 0 | 导入/编辑、模板上下文、预览、ZIP、写文件、三类前端模板 |
| ruoyi-quartz | 20 | 17 | 0 | 定义校验、调度替换、运行、状态修改、任务日志 |
| ruoyi-system | 84 | 64 | 3 | 用户/角色/部门/菜单、字典/配置/公告、短信/OSS、身份权限 |
| ruoyi-ui | 385 | 0 | 11 | API、store、公共组件、设计器、各业务页面与浏览器合同 |
| sql、docker、根配置与文档 | 其余 | — | — | 初始化顺序、业务约束、部署拓扑、验收入口和历史清理结论 |

审查方式：

- 全仓文件枚举、模块依赖与业务入口库存；对全部 **1,024 个生产 Java/JS/Vue 文件**做统一文本重复筛查。
- 重复筛查以去除空白/注释后的连续 12 行、至少 350 字符作为候选窗口，再人工判断是否同一业务规则。该筛查不代表语义重复只有这些窗口。
- 分设计发布、运行任务、通知集成、系统基础设施四条线，沿入口→规则→状态读写→副作用→测试核对。对候选追踪真实调用方和必须保留的边界。
- 已执行当前字典 store action 的最小 Node VM 复现：同 key 写 old/new 后 get 返回 old，remove 一次后仍有 new 和一条残留。defineStore 仅作装配替身，此结果不等同于完整 Pinia/浏览器验收。
- 本轮未运行 Maven、完整前端测试、浏览器、数据库、外部服务或性能压测。上一阶段测试成绩只作为历史记录引用。
- DTO/getter、外部引擎建表快照、生成模板和样式按结构/调用/协议审查，不把全仓枚举表述为每个文件逐行正确性证明。真实环境和第三方兼容性仍由对应验收确认。

当前模块方向：`admin → framework/flowable/quartz/generator`；`framework → system`；`flowable → system`；`system/quartz/generator → common`。未发现 Maven 模块循环，上一阶段的 flowable→framework 依赖已删除。

## 2. 设计、目录、发布链

| 子链 | 入口/所有者 | 本轮结论与保留合同 |
|---|---|---|
| 分类创建、查询、修改、删除 | WfCategoryController、WfCategoryServiceImpl、Mapper | B02；编码为引用身份，名称/备注可编辑；保留引用删除检查 |
| 表单模板 CRUD、protocolValid | WfFormServiceImpl、WorkflowFormTemplateValidator | B17；作者变量规则提前归一，decode 与作者校验保持区别 |
| 表单生成、变量编辑、保存 | tool/build、workflowVariableNames | B17；平面字段 schema、容器、动态选项和组件协议保留 |
| 模型元数据、版本、导入导出 | WorkflowModelService、WfModelController | N03 已收敛制品装配；保留模型版本并发与元数据合同 |
| 设计草稿、所有者、双 revision | WorkflowModelDesignDraftService | N00 已独立测试；不重做草稿状态机 |
| 作者字段目录与父字段合并 | WorkflowAuthorFieldCatalogService、WorkflowCallActivityFields | N02 已完成；保留请求内模板复用、权限交集/必填并集 |
| 模型初始表单权限与节点快照 | WorkflowNodeFormPermissionSnapshot、作者目录 | B17；保持模板内唯一与跨节点同名的不同合同 |
| 预检、发布、编译制品准备 | WorkflowPublicationArtifactsService、ModelService | N03 已完成；保留同一发布事务、固定版本和失败回滚 |
| 制品保存、manifest、惰性读取 | WorkflowDeploymentArtifactRepository | 保留一次请求的惰性缓存、schema/checksum/损坏拒绝；不拆 codec/store 层 |
| BPMN 条件与受控循环编译 | Condition/ControlledLoop 编译器 | 保留 AST/变量规则与运行路由；稳定分派无需策略工厂 |
| 参与者、候选人/组、节点权限 | Participant 编译、NodePermission | 保留后端资格与字段写权限；前端搜索只作交互 |
| CallActivity 引用/冻结/IO/字段 | CallActivityReferenceService、CallActivityFields | 已分离纯字段规则；保留目标授权、部署引用和冻结绑定 |
| DMN 目录、编译、删除保护 | WorkflowDmnDecisionService、WorkflowDecisionTableService、WorkflowDecisionTableExecutor | 保留冻结决策、入出参合同、引用资源扫描和独立删除测试；制品归 DeploymentArtifactRepository |
| 扩展版本目录、Java/CEL/FORM_FIELD | RegistryService、ExtensionDeploymentService | B12 只迁摘要；真实多实现注册、版本固定和漂移校验保留 |
| 自动抄送设计编辑与写入 | AutoCopyRuleEditor、extensionEventSlaDomain | B18；空数组删除、上下文校验模式、一次 modeling 命令保留 |
| 多池与协作 BPMN 编译 | Collaboration 编译域、设计器 | 保留每池消息/信道协议与子图关系；发送状态机在运行域 |
| 事件代码、日历、SLA 作者配置 | BpmnEvent/Calendar/Sla 编译与目录服务 | 稳定 key 已有后端不可变规则；前端列表纳入 B06-B |
| 部署列表、版本、挂起/删除 | Deploy 服务、部署制品仓库 | 保留实例/子定义引用保护；版本弹窗已有请求序号，不重写 |
| 设计器选择、撤销重做、属性域 | ProcessDesigner、designer/* | 既有域边界保留；自动抄送规则 B18，其他不同 BPMN 合同不机械合并 |

## 3. 发起、任务、实例与附件链

| 子链 | 入口/所有者 | 本轮结论与保留合同 |
|---|---|---|
| 可发起目录、表单/BPMN 读取 | ProcessDefinitionQueryService | 保留定义/部署/租户/快照一致性 |
| 草稿创建/保存/删除/提交 | ProcessDraftService | 保留部署锁→草稿锁、revision、两次 SUBMITTED 检查和幂等返回 |
| 实际发起 | ProcessStartService | 保留表单→附件 prepare→引擎启动→附件 bind 的顺序 |
| 待办/待签/已办/我发起/运维/抄送 | ProcessInstanceQueryService、ProcessTaskQueryService | B07 处理已办撤回能力；后端批量投影保留；前端 B06-A |
| 列表导出 | 各 QueryService 导出入口 | 不把页面 capability 计算引入导出；参数与授权保留 |
| 实例详情、表单历史与附件投影 | ProcessDetailService、各 Projection | N04 已消除退回/重提写依赖；保留已完成的事实复用 |
| 认领/取消认领 | TaskActionService | 保留候选关系、领取来源、身份资格、审计顺序 |
| 委派/完成委派/转办 | TaskActionService | owner/delegation/目标资格/通知语义不同，保留具体动作 |
| 审批完成 | TaskCompletionApplicationService | 表单、附件、抄送、并发/MI/循环、审计与引擎事务顺序保留 |
| 驳回与发起人取消 | Rejection/Cancel ApplicationService | 共用实例终止核心保留；任务审计与发起人权限保持区别 |
| 普通退回与重提 | Return/Resubmit ApplicationService、Preparation | N04 已完成；读 capability 降级与写锁后重验保留 |
| 已办撤回 | TaskRevokeApplicationService | B07；untouched 后继判断、迁移计划、锁后复核保留 |
| 动态加签/减签/完成 | MultiInstanceService | round/revision/成员与引擎事实是不同状态；保留 CAS |
| MI 整组退回/重开/终止 | GroupTransition、RoundLifecycle、Termination | 调用栈 ThreadLocal 协调与持久状态不同；保留重放与执行树核对 |
| 受控整改循环 | ControlledLoopService | 引擎路由变量与持久审计分别服务运行/历史，均保留 |
| 实例挂起/激活/终止 | ProcessInstanceService | 保留子树顺序、SLA 暂停恢复和 MI 预检 |
| 根/子实例历史删除 | ProcessInstanceService | 附件阻止、抄送/循环/轮次清理、引擎历史原子删除保持 |
| 手工/自动抄送运行 | TaskCopyService、AutomaticCopyService | 动作 revision 与生命周期事件幂等不同；prepare/persist 分工保留 |
| 身份目录与对象授权 | IdentityDirectory/Resolver、ProcessAccess | 审批/领取/抄送、实例可读/任务可写为不同合同；B08 仅合并同义资格片段 |
| 上传、元数据、下载、临时删除 | AttachmentService/Controller/Storage | 对象授权、quota、路径/文件完整性、逻辑删除保留 |
| 草稿附件协调与正式绑定 | AttachmentService、ProcessDraft/Start | T-08 有效期二次检查保留，行锁不会冻结时间 |
| 正式附件清理 | CleanupCoordinator | N01 已归位；租约、短事务、IO 边界和元数据保护保留 |
| 未完成上传台账清理 | UploadJournal | B09；保留 reserve 先提交、绑定竞争、claim token 与正式对象保护 |
| SLA 提醒/升级/暂停/恢复 | TaskSlaRuntimeService | timer ordinal/revision、逻辑时钟和 required inbox 保留 |
| 外部运行事件、内部 BPMN 事件 | RuntimeEventService、BpmnEventRuntimeService | 外部凭据/请求幂等与内部事件触发不同；不造统一执行器 |
| 等待状态、事件审计 | WaitingService、RuntimeEventAudit、BpmnAudit | 读取/审计各自持有事实，列表请求归 B06-B |
| 前端发起、附件和详情观察 | start、AttachmentUpload、useWorkflowDetailObservation | 串行上传、未提交输入、隐藏暂停、epoch/request slot 保留；不重做 N07 |

## 4. 通知、集成、协作链

| 子链 | 当前结论 |
|---|---|
| 事件生成→策略选择→冻结事实→Planner | N05 已完成；保留先选策略再按需读取事实 |
| Writer→Inbox/Outbox | 保留同事务、去重键；SLA/事件必达站内信独立合同 |
| 站内信、已读、未读数、偏好 | 归属与读状态过滤保留，未确认新后端删除项 |
| 策略、节点目录、邮件配置 | B10 只收口配置同次读取；revision/白名单/条件加载保留 |
| EMAIL/SMS 通道 | 邮件分类已统一，供应商协议与永久失败语义不同，分别保留 |
| 通知领取、投递、完成、补偿、取消 | 保留事务外发送、CAS、cycle/total attempt、耗尽租约回收；B10 删除死返回 |
| 人工催办 | B08；人数上限、正式锁和既有处理人分支保留 |
| HTTP BPMN 适配与共享执行 | N08 已完成；B03 处理协作异常映射；BPMN Job 持有重试 |
| SQL PRIMARY/EXTERNAL | B14 补事务与重放证据；禁止把两者假定为同一事务 |
| CEL 编译与执行 | 已确认 Delegate→Sandbox 单次 compile；旧双编译问题不再立项 |
| Java 扩展与 Listener | B12；多实现注册与版本漂移保护保留 |
| HTTP/SQL 目录与部署冻结 | N08 已完成；环境引用、端点冻结与方法/路径边界保留 |
| 受管秘密创建/轮换/吊销/绑定 | 现有唯一所有者保留；一次性 Token、密文与日志边界保持 |
| 认证探测 | I-14 原事务保留，nonce/绑定/status/协议有真实竞态反例 |
| 入站凭据、scope、限流 | revision/分钟限流与最后使用时间节流用途明确，保留 |
| 协作出站 enqueue/claim/deliver/complete | B03；按活动发生次构造消息身份、频道顺序和租约保留 |
| 协作补偿、取消、保留期清理 | 已有显式状态机保留；B03 验证失败耗尽闭环 |
| 协作接收、去重、匹配、触发、乱序重试 | B11 同事务锁事实复用；唯一约束与接收副作用一次仍需真实验收 |
| 集成/通知/协作管理页面 | B06-B；已有策略/邮件配置弹窗防竞态机制保留 |

## 5. 系统、基础设施与工具链

| 子链 | 本轮结论 |
|---|---|
| 登录、验证码、密码重试、注册 | 现有入口与身份校验保留；B01 针对已登录快照更新 |
| Token、鉴权表达式、数据范围 | B01；保持 ss Web 上下文与 system 纯权限判定分工 |
| 用户 CRUD、角色分配、状态、资料 | B01 覆盖身份授权变化；资料响应/密码/头像协议保留 |
| 角色 CRUD、菜单授权、数据范围、成员 | B01；多个入口调用统一身份刷新规则 |
| 部门树、岗位、菜单树/路由/排序 | 主数据仍归 system；前端请求 B06-C；不把树/岗位统一为泛化目录服务 |
| 配置 CRUD 与缓存 | 已有提交后缓存更新保留；与字典缓存不造通用事务缓存框架 |
| 字典类型、数据、服务端缓存 | 后端事务与提交后失效保留；前端缓存 B05、列表 B06-C |
| 公告、已读批量操作、读者列表 | 第一轮批量化与读状态边界保留；列表 B06-C |
| 短信配置、发送、供应商签名、审计 | 有实际多供应商协议；现有 send 单一入口保留，局部 normalized 重复不足以造工具层 |
| OSS 配置、上传、对象账本、下载、补偿 | 流式/暂存、远端副作用、数据库账本分别有用途；第一轮复制清理保留 |
| 通用上传/公开下载与私有工作流附件 | 信任边界不同，公共文件工具与工作流对象授权分别保留 |
| 上传/图片/Excel/富文本组件 | FileUpload/ImageUpload 已共用 settlement；不同接口与序列化合同保留 |
| HTTP 请求、错误、重复提交 | 在途请求 owner、脱敏错误与 finally 释放已完成；不恢复 sessionObj 历史正文 |
| 在线用户、缓存、服务器、日志监控 | 保留第一轮在线查询/缓存约定；有交互的列表 B06-C；单次快照无需请求调度框架 |
| Quartz 任务管理与日志 | B04 归一定义替换；日志/反射调用白名单/并发执行不同职责保留 |
| 生成器表导入、同步、配置编辑 | 现有元数据与模板能力保留；列表 B06-C |
| 预览、ZIP、自定义路径生成 | B13；三种出口仅共用准备/渲染，不合并 IO 行为 |
| 生成模板 Vue2/Vue3/TypeScript | 全部在 UI 可选且资源存在，保留 21 个模板 |
| Maven、前端依赖、构建与配置 | 模块方向已修正；不借清理做依赖升级，锁文件保留 |
| SQL 基线、修复脚本、Docker 初始化 | 已核对 14 文件初始化顺序、19 个 SQL 库存及现有约束调用；不删除历史迁移/引擎建表快照 |
| 开发/真实回归装配 | B15；区分受控浏览器、真实业务链与目标部署验收 |

## 6. B06 完整页面清单

下表是执行范围。每个方法要核对 rows/total/loading/error 与目标 ID 的提交点；同一页独立页签/弹窗应使用各自的请求身份，不能一个全局序号互相取消。

路径均相对 `D:/ruoyiflowable/ruoyi-ui/src/`。行号为当前基线定位。

| 子包 | 文件 | 当前入口/位置 |
|---|---|---|
| A | components/workflow/WorkflowProcessList.vue | loadList:301；共享七种列表模式 |
| A | views/workflow/work/draft.vue | loadList:101 |
| A | views/workflow/category/index.vue | loadList:87 |
| A | views/workflow/model/index.vue | loadList:218；历史弹窗已有保护 |
| A | views/workflow/deploy/index.vue | loadList:137；版本弹窗已有保护 |
| A | views/workflow/dmn/index.vue | loadRows:114 |
| A | views/workflow/extension/index.vue | loadRegistry:276；两路 allSettled 的 rows/handlers/error/loading |
| B | views/workflow/notification/index.vue | loadOutbox:589；策略和节点目录已有保护 |
| B | views/workflow/connector/index.vue | loadEndpoints:243、openCredential:146；凭据弹窗需冻结 endpointId |
| B | views/workflow/sqlDatasource/index.vue | loadRows:118 |
| B | views/workflow/integrationCredential/index.vue | loadRows:165、openCreate:190 |
| B | views/workflow/collaboration/index.vue | loadRows:111、openAudit:166、retryRow:179；方向切换:137 |
| B | views/workflow/runtimeEvent/index.vue | loadRows:70 |
| B | views/workflow/bpmnEvent/index.vue | loadCodes:290、loadAudit:296、loadCalendars:330、loadSlaExecutions:339、loadSlaAudit:352 |
| C | views/system/user/index.vue | getList:246 |
| C | views/system/role/index.vue | getList:300 |
| C | views/system/role/authUser.vue | getList:118 |
| C | views/system/role/selectUser.vue | getList:106；重复打开选人目标 |
| C | views/system/dept/index.vue | getList:190；树与原排序事实同次提交 |
| C | views/system/menu/index.vue | getList:343；主树/下拉树分开持有请求身份 |
| C | views/system/post/index.vue | getList:182 |
| C | views/system/config/index.vue | getList:203 |
| C | views/system/dict/index.vue | getList:222 |
| C | views/system/dict/data.vue | getList:243 |
| C | views/system/dict/detail.vue | loadData:99；抽屉 row 的 dictType |
| C | views/system/notice/index.vue | getList:199 |
| C | views/system/notice/ReadUsers.vue | getList:76；noticeId 与检索参数 |
| C | views/system/sms/index.vue | loadActiveTab:172、loadLogs:186 |
| C | views/system/oss/index.vue | loadActiveTab:148、loadObjects:162 |
| C | views/monitor/online/index.vue | getList:77 |
| C | views/monitor/operlog/index.vue | getList:188 |
| C | views/monitor/logininfor/index.vue | getList:156 |
| C | views/monitor/job/index.vue | getList:282 |
| C | views/monitor/job/log.vue | getList:172 |
| C | views/monitor/cache/list.vue | getCacheNames、getCacheKeys、handleCacheValue；缓存名称/键的级联选择 |
| C | views/tool/gen/index.vue | getList:199 |
| C | views/tool/gen/importTable.vue | getList:88 |

共 **37 个文件**。这里不要求将 37 页改成同一个页面组件；统一的是异步结果的提交资格。

已具备保护并保留：form 列表的 listRequest；model 历史 historyRequestId；deploy 版本 versionRequestId；notification 策略/节点目录的请求序号；MailConfigDialog 的关闭/卸载失效；设计页按 target 的 identityRequestVersion；详情观察控制器及多实例刷新序号。

`start.vue` 没有相同 epoch，但路由按 path 建立组件且 noCache，目前缺少同实例重入反例，不纳入机械改造。服务器/缓存概览单次初始化与可反复筛选列表区别处理。

## 7. 明确保留与证据条件

### 7.1 重复筛查中排除的表面相似

- CollaborationMessage/Outbox 和 Job/JobLog 的 DTO 字段：状态对象用途不同。
- 已授权/待授权用户选择表格，操作日志/登录日志字段布局，配置/岗位与字典列表的局部模板：页面合同不同；只处理 B06 的真实请求状态问题。
- SysSmsService/SysOssService 的短字符串 normalized 方法：局部约束不同，提取通用工具的收益不足。
- 通知、协作、附件、Flowable Job 重试：分别保留明确所有者，不合并持久状态机。
- 事务前后、锁前后、时间跨越后的复核：有状态变化窗口的保留，尤其 T-08、I-14。

### 7.2 需要证据后决定的事项

| 候选 | 当前处理 | 进入实施的条件 |
|---|---|---|
| 分类/表单引用检查与并发删除 | 主计划 C01 专门取证，生产实现先保留 | 两个真实事务证明可提交悬空引用，再确定最小锁或约束；不能见两次查询就删检查/增锁 |
| SLA 主状态机专项测试不完整 | 不据此新增生产重构 | 未来修改 SLA 或准备目标部署时先补暂停/恢复、ordinal/revision、升级与完成竞争合同 |
| 通用任务不存在时 404/409 不同 | 保留入口既有错误合同 | 有公开接口一致化需求及调用方迁移方案后再统一 |
| IdentityDirectory 不可达 copy 分支疑点 | 当前 resolve DTO 只允许 approval/claim，保留现状 | 找到正式可达调用或可验证死路径后再列删除项 |
| 生成 ZIP 的 IOException 处理 | B13 保持当前出口合同，不夹带错误策略改造 | 出现可复现的不完整包场景，再单独给出对外失败语义与测试 |

以上条件项不视作已确认生产清理任务。C01 是明确调查包，其余保留触发条件；19 个主任务包的范围保持可核对。执行中若获得反例，新增稳定编号、证据与验收后单独交付。

## 8. 本次分析产物

- 主计划：完整任务、具体范围、保留合同、验收、顺序和派发模板。
- 本记录：全仓库存、逐业务链结论、37 个前端文件范围、保留和条件项。
- 本轮代码、测试、配置和数据库均未修改；没有重新宣称历史测试为当前执行结果。
