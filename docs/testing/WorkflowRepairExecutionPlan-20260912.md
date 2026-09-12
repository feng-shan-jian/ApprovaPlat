# 工作流完整修复执行计划（2026-09-12）

状态：待执行。本次交付为计划，不表示已修复、已重测或已发布。

主要依据：[2026-09-11 原生前端复测报告](/D:/ruoyiflowable/docs/testing/ComputerUseWorkflowRetestReport-20260911.md)。配套交付：[用户体验与优化执行计划](/D:/ruoyiflowable/docs/testing/WorkflowUXOptimizationExecutionPlan-20260912.md)。

## 1. 目标与完成边界

本计划修复本轮六项问题，并补齐用户正常使用所需的设计恢复、配置闭环和准确运行状态。最终重新执行 A—G 原组合，逐项记录创建、保存、部署、运行结果。

1. C 保留调用活动与数据存储输出关联后可保存、部署和运行；明确数据图元与变量映射各自的语义。
2. G 消息转信号时清理不兼容配置，撤销/重做一致；独立消息抛出另行完成真实投递验收。
3. F 跨页面配置资源不丢设计；多池发起范围显示与正式配置一致；完整 MessageFlow 自动投递成功。
4. 输出映射提供明确的字段限制及受控“系统回填、用户只读”能力。
5. 发布错误能定位、能解释、能指导处理；运行状态能够区分完成、等待和竞争取消。

此前[修复执行报告](/D:/ruoyiflowable/docs/testing/WorkflowRepairExecutionReport-20260910.md)已有可靠消息、事务等能力的专项记录，应复用现有实现和有价值的正式测试。本轮原生前端复测暴露的路径问题仍须修复；旧报告的通过结果不能替代本次验收。不得重新实现一套平行流程引擎。

这是一份针对已发现问题及其相邻回归边界的完整计划，不是整个产品所有 BPMN 变体、HTTP/SQL/CEL 集成和生产拓扑的穷举认证。

## 2. 执行约束

- 执行前重新读取当前代码及工作区差异。保护既有未提交和已暂存内容；本计划不授权提交、推送或生产发布。
- Windows 使用 PowerShell 7；复杂脚本写成文件运行；不混用 Windows/WSL 的依赖环境。
- 默认保持现有接口和数据结构。必须增加协议时同步控制器、DTO/VO、调用方、数据库迁移、测试、文档及版本切换策略，不能只改前端。
- 权限、流程版本、端点状态和运行事实由服务端核验；前端展示及便捷操作不替代服务器门禁。
- 不以删节点、删 MessageFlow、放宽权限、隐藏按钮、客户端推断或 localStorage 保存冒充能力修复完成。
- 已部署模型及表单快照保持不可变；修复通过新模型/部署版本生效，存量实例处理单列。
- 验证新增的临时脚本、原始日志、测试数据、一次性令牌和调试文件完成后删除；正式回归测试、脱敏报告和必要截图可保留。

## 3. 任务总表与顺序

负责人列表示实施角色，不预设具体人员；人日为净工作量估计，包含定向验证，不是承诺工期。

| 任务 | 优先级 | 工作内容 | 负责人 | 依赖 | 估计人日 |
| --- | --- | --- | --- | --- | --- |
| R00 | 前置 | 基线、最小复现、测试资源准备 | 后端/测试 | 无 | 1–2 |
| R01 | P1 | 编辑保护与服务端设计草稿 | 前后端 | R00 | 3–5 |
| R02 | P1 | 事件类型转换原子清理 | 前端，后端校验配合 | R00 | 1–2 |
| R03 | P2 | 多池发起范围真实回读 | 前后端 | R00 | 0.5–1 |
| R04 | P1 | 调用活动输出关联部署修复 | 后端 | R00 | 2–4 |
| R05 | P1 产品闭环 | 端点就绪、受管凭据与可靠消息闭环 | 前后端/运维 | R00；页面往返依赖 R01 | 4–7 |
| R06 | P2 能力补齐 | 字段冲突诊断及系统回填权限 | 前后端 | R00 | 3–5 |
| R07 | P1/P2 | 统一诊断与无副作用发布预检 | 后端/前端 | R03–R06 的协议确定 | 2–3 |
| R08 | P2 | 运行状态及等待事实投影 | 后端/前端 | R00 | 2–3 |
| R09 | 完成门禁 | 分层回归、原生用户验收、清理交付 | 测试/前后端 | R01–R08 | 3–5 |

建议实际推进顺序：R00 → R01 的离开保护 → R02/R03 → R04；随后完成 R01 草稿、R05/R06/R08，汇入 R07，最后 R09。各独立任务可由团队分工实施；共享文件修改需要明确归属和集成顺序。

不能为等待非阻塞体验细化而拖延 P1 修复；也不能在仅完成离开提示时将整个 R01 标为完成。

## 4. 逐项实施任务

### R00：建立当前基线和可复现用例

执行动作：

1. 记录 HEAD、暂存/未暂存/未跟踪文件清单及相关文件校验值；标注继承失败和本轮新增失败。
2. 确认正在运行的前后端版本、数据库及 timers profile，防止源码已改但服务仍运行旧构建。
3. 从原报告证据重建 C/G/F 最小模型。正式测试夹具使用稳定节点名与隔离前缀，移除账号、令牌和环境绑定信息。
4. 对 C 重现采集请求标识、服务端异常栈、作者 XML、各编译阶段输入/输出及最终执行 XML。若原日志不存在，使用新的同构复现关联日志，不猜测根因。
5. 准备隔离 MySQL、三个不同审批身份、两个或签身份、设计人员及集成管理员身份、可自动调度的测试服务、受控接收端点。凭据不写入报告或命令输出。
6. 确认关键已有测试实际启动了 Flowable/数据库/HTTP 哪一层，不能以类名中的 Business 或 Integration 推定覆盖。

验收：每项缺陷都有可复现步骤、原始失败行为及预期行为；测试环境缺口列为 blocked，不计为功能失败。输出正式场景清单，不修改业务状态来制造结果。

### R01：编辑保护与设计草稿

代码入口：[设计页面](/D:/ruoyiflowable/ruoyi-ui/src/views/workflow/model/design.vue)、[设计器](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/ProcessDesigner.vue)、[标签管理](/D:/ruoyiflowable/ruoyi-ui/src/layout/components/TagsView/index.vue)、[模型服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowModelService.java)。按需新增专用模型设计草稿服务、Mapper、DTO、增量 SQL 和正式测试，命名在实施时统一。

执行动作：

1. 先建立“当前作者内容/最后成功保存内容/保存中内容”的独立状态；只有服务端确认的对应修订可以清除未保存标记，晚到响应不得覆盖新编辑。
2. 覆盖返回、侧栏导航、标签关闭/关闭其他、浏览器刷新关闭、会话过期。站内使用继续编辑/保留草稿后离开/明确放弃；浏览器关闭使用原生保护，不能承诺关闭事件中异步保存必达。
3. 从当前真实画布同步导出后再判断离开或保存，覆盖现有 180ms 节流尚未触发的快速操作。
4. 增加服务端模型设计草稿：允许业务配置未完成，但仍验证 XML 结构、大小、归属及权限。草稿不可部署，不覆盖已验证模型源，不复用“发起申请草稿”的提交状态机。
5. 草稿绑定模型/版本/作者身份，带基准模型修订及草稿修订 CAS；两个标签或两个用户冲突时保留待提交内容并展示差异选择，禁止最后写入者静默覆盖。
6. 提供草稿恢复、保留/放弃和保存失败重试；恢复时核对模型是否已更新、删除或失去权限。保存生成新模型版本 ID 时正确迁移关联，不能把旧草稿加载到新版本。
7. 导入失败保留原画布；导入成功标为未保存；保持画布位置和选中节点。恢复草稿不伪称恢复了跨会话撤销栈。

验收：

- 未配置端点的复杂双池模型能够保存为草稿；跳转资源页再返回、刷新、重新登录后可恢复节点、连线、属性。
- 最后一次键入后立即离开不丢变化；取消离开完整保留当前图。
- 保存失败不显示已保存；两个标签同时保存产生明确冲突，内容不被覆盖。
- 越权读取/修改草稿被拒绝；已部署版本不被草稿覆盖；浏览器缓存不能作为唯一恢复依据。

### R02：事件转换与扩展配置的原子清理

代码入口：[类型替换处理](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/ProcessDesigner.vue:528)、[事件领域](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/extensionEventSlaDomain.js)、[原子命令模块](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/bpmn/atomicPropertiesModule.js)、[消息抛出合同](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowMessageThrowContract.java)。

执行动作：

1. 建立事件类型与平台专有配置的兼容表；首批覆盖消息/信号、错误/升级、定时器及边界中断语义。
2. 在类型转换的同一命令单元内移除不兼容的受控配置；保留节点 ID、名称、连线及兼容的文档/扩展，不使用“清空全部 extensionElements”。
3. 引用根事件定义时保护其他节点共享引用；不能删除仍被使用的消息/信号/错误定义。
4. 清理前捕获输入；转换、撤销、重做均让画布、面板和 XML 一致。导入存量坏配置时提供明确诊断及可撤销修复入口，不在读取时静默修改。
5. 保持服务端拒绝信号节点携带消息专用配置的门禁。

验收：复现 G1，在原节点上完成消息→信号→撤销→重做→保存→刷新回读→部署→运行；无需删节点。直接提交不兼容配置仍被拒绝。B 的错误/升级编码和非中断双待办继续通过。

### R03：多池发起范围与导入一致性

代码入口：[参与者规则回读](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/participantRuleDomain.js:67)、[规则编辑器](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/ParticipantRuleEditor.vue)、[表单与参与者领域](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/formParticipantDomain.js)、[后端发起规则合同](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowParticipantRuleBpmnContract.java)。

执行动作：

1. 导入缺少完整范围协议时显示“未配置”；删除仅在显示层回退为 PUBLIC 的行为。
2. 如新建模型默认公开，初始化时写入完整协议并在页面明确显示；导入旧模型不自动扩大范围。
3. 每个可执行根流程分别验证类型、目标、版本和无匹配策略；错误携带所属流程，选中某池不应编辑另一个池。
4. 用户一次选择即写入完整协议，保存后重新读取值与界面一致。

验收：同一双池模型分别设置公开/指定用户；保存刷新后各自一致，运行时越界用户不能发起。缺配置时定位第二池；不再需要先改其他值再改回公开。

### R04：调用活动数据输出关联部署修复

代码入口：[BPMN 服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowBpmnService.java)、[XML 保留逻辑](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowBpmnXmlPreserver.java)、[调用活动编译](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowCallActivityReferenceService.java)、[部署链](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowModelService.java:647)。

执行动作：

1. 先用 R00 证据区分转换器丢引用、编译器破坏结构、最终引擎解析失败或平台不支持的执行语义，再确定最小修改点。
2. 对照检查 sourceRef/targetRef、ioSpecification、数据对象/存储引用、作用域及图形 DI 的一致性；同时覆盖子流程嵌套，不能只检查 XML 字符串存在。
3. 首选保留原作者关联并修复执行编译兼容。作者导入/保存/回读/导出必须保留原结构；若执行副本需要受控转换，记录明确的转换规则并验证行为等价。
4. 数据存储图元不能被解释为自动写数据库；正式数据传递继续由受控变量映射/处理器完成。若业务要求关联线执行读写，另立明确合同与受控实现，不能凭图形宣称已支持。
5. 对确实不支持的组合提前给出结构化能力限制；这只能关闭“通用系统错误”问题，原组合能力仍标 partial，不能标完整修复。
6. 部署失败必须完整回滚新部署、版本状态及相关快照；前置检查不得留下部署副作用。

验收矩阵：

| 用例 | 必须观察到的结果 |
| --- | --- |
| 原 C，两条关联均保留 | 原生创建/保存/部署成功；父子完成，7→8 回传正确；导出仍有两条原关联 |
| 仅输入关联 | 可部署运行；关联是否执行数据传递单独判定，不用默认 500 冒充传输证据 |
| 仅调用活动输出关联 | 可部署运行，无需删除该线 |
| F 事件子流程→存储输出关联 | 保持原有可部署行为 |
| 错误引用/跨作用域非法引用 | 保存或发布前明确定位；失败没有新增正式版本/部署残留 |

### R05：端点与凭据的可视化配置闭环

代码入口：[端点页面](/D:/ruoyiflowable/ruoyi-ui/src/views/workflow/connector/index.vue)、[消息配置](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/CollaborationMessageEditor.vue)、[端点控制器](/D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/workflow/WfConnectorController.java)、[端点服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowConnectorEndpointService.java)、[密钥解析器](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowConnectorSecretResolver.java)、[集成凭据服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowIntegrationCredentialService.java)。

已核查事实：现有解析器只读固定前缀的进程环境变量；前端填写一个别名不会把令牌注入服务进程。因此本任务不能仅增加一个输入框或下拉框。

执行动作：

1. 在 R00 确定当前部署可用的受管密钥存储和服务身份接入方式；缺失时将其作为本任务的基础设施子项交付。先完成存储、读取、轮换、撤销的验证，再发布管理入口。
2. 保留现有 ENV 引用解析及既有端点行为；为新增受管凭据提供明确 provider/引用协议。禁止同一引用在环境与受管存储间静默回退。
3. 提供授权管理员的凭据录入/绑定/轮换及状态检查。前端不回读密钥正文；BPMN、业务端点表、审计和日志只记录引用及必要元数据。新集成令牌可在创建时绑定，历史哈希记录不能“恢复明文”。
4. 凭据存储失败、绑定失败或轮换中断必须可重试并有明确补偿状态，不能产生“端点已就绪、凭据实际不可读”的半成品。
5. 服务端提供经权限过滤的端点可用性及原因：未配置、停用、认证未就绪、权限不足、加载失败；不得泄露未授权资源名称。选择目录与实际发布/投递均重新核验。
6. 提供分层检查：配置/密钥读取检查、受控网络认证检查、隔离流程业务投递验证。不能把 HTTP 可达或 200 当成业务消息消费成功；检查不得偷偷推进真实业务实例。
7. 端点向导完成后回到原设计节点并刷新选项，沿用既有可靠消息/outbox/入站协议，不新建平行消息通道。

验收：

- 有权限管理员经产品界面完成凭据绑定和端点配置；设计人员能选择授权且就绪的端点，重启后仍可解析。
- F 保留原 MessageFlow，真实发送→outbox→入站消费→第二池继续，双方完成；G 独立消息抛出单独完成。
- 迟到接收者自动重试成功；同 messageId 重放不新增任务，改变内容发生冲突；认证失效/端点停用有准确失败及恢复路径。
- 发送事务失败时实例、outbox、序号和审计按正式事务合同回滚；同名无关流程不被消费。
- 多服务节点场景必须验证所有执行节点的密钥解析与轮换；只在本机通过时明确交付范围，不宣称集群就绪。

### R06：字段冲突与“系统回填、用户只读”

代码入口：[调用领域](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/routingCallActivityDomain.js)、[表单权限领域](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/formParticipantDomain.js)、[模板协议](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/form/formTemplate.js)、[调用变量 VO](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/domain/vo/WorkflowCallActivityVariableView.java)、[调用服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowCallActivityReferenceService.java)、[条件部署服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowConditionDeploymentService.java)。

执行动作：

1. 先分开“缺字段、源不可读、目标不可写、类型冲突、重复目标”诊断；展示冲突字段所属流程、节点、模板与实际约束。
2. 确定正式变量定义与表单展示权限的关系；同名变量必须有一致的类型/约束，不能用改名字掩盖未修复的业务合同。
3. 增加显式受控系统输出资格，例如 systemWritable，名称实施时固定。默认不授予；现有 readable/writable 语义保持，旧模型不自动获得权限。
4. 允许已授权的调用输出写入明确声明的系统结果字段，审批表单保持只读；源字段读取、类型/约束、所属流程隔离仍需验证，不能绕过隐藏字段限制。
5. 同步正式模板、内嵌表单、目录、保存/部署验证、不可变快照和运行写回；提交表单时拒绝伪造系统字段。缺一层不开放入口。
6. 旧部署继续使用原快照；新能力通过显式配置和新部署生效。测试原先“只读输出拒绝”仍在未授权场景成立。

验收：7 传入子流程，子改 8 后父显示 8 且用户不可编辑；直接构造请求修改该系统字段被拒绝。覆盖输入与输出两个方向、隐藏源、错误类型、同部署其他池字段、旧版本快照和并发提交。

### R07：结构化诊断与发布预检

代码入口：[模型控制器](/D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/workflow/WfModelController.java)、[模型服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowModelService.java)、[诊断 VO](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/domain/vo/WorkflowBpmnValidationIssue.java)、[异常处理](/D:/ruoyiflowable/ruoyi-framework/src/main/java/com/ruoyi/framework/web/exception/GlobalExceptionHandler.java)、[设计器校验界面](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/ProcessDesigner.vue:149)。

执行动作：

1. 复用现有 code/severity/elementId/message/processId，按兼容方式增加必要的字段路径、关联元素、业务名称和处理建议；旧构造方式和消费方同步核查。
2. 区分草稿保存、正式保存校验和发布预检。预检覆盖实际部署编译所需的引用与兼容条件，但不得创建部署或正式快照。
3. 若现有 prepare 混合写入副作用，先拆出纯验证/编译阶段；不直接把整段部署事务拿来当预检。
4. 预检结果关联作者内容摘要/修订和所检查的资源版本；编辑或资源变化后失效。正式部署在锁/事务内重新核验，不能信任旧预检票据。
5. 未预期异常保留服务端关联日志，客户端显示追踪编号与可行下一步，不返回原始 Java/SQL 堆栈。
6. 前端问题面板按流程/节点显示，点击定位具体属性；现有元素定位能力继续复用。

验收：C 部署问题、G 残留、F 缺范围/端点、C 字段冲突都能定位；预检无副作用；预检后端点停用或模板变更时正式发布正确拒绝且无部分部署。

### R08：运行轨迹和等待状态准确表达

代码入口：[历史投影](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessHistoryProjection.java:636)、[查看器 VO](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/domain/vo/WorkflowProcessViewerView.java)、[等待服务](/D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessWaitingService.java)、[查看器](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/ProcessViewer.vue)、[等待面板](/D:/ruoyiflowable/ruoyi-ui/src/components/workflow/ProcessWaitingEvents.vue)。

执行动作：

1. 区分真实完成、当前等待、事件竞争取消、终止/撤回和未知历史状态。不能仅凭 endTime 存在就当作业务完成。
2. 使用真实活动/订阅/任务及取消原因；必要时补充受控运行审计。历史缺证据时显示未知，禁止根据前端颜色或猜测路径反推事实。
3. 重复进入同一 BPMN 节点时保留活动实例/轮次语义，避免历史完成覆盖当前待办；新增投影协议同步 DTO/VO、构造调用方和测试。
4. 等待状态按 TIMER/MESSAGE/SIGNAL/RECEIVE 分别说明，纠正泛化的“待领取”。普通用户看到业务名称和下一步，技术标识按权限展开。
5. 复用现有状态查询实现受控刷新：页内可见时轮询、操作成功后立即更新、失败退避、离开停止、恢复可见时刷新；响应带序号/版本，旧响应不覆盖新状态。
6. 等待、当前任务、轨迹和列表在同一轮刷新保持一致；没有证据时不得承诺精确 5 秒完成。

验收：D 消息胜出时信号明确显示竞争取消，反向同样；真正经过的顺序流正确。A 定时器自动续行后无需人工刷新出现后继；ALL/ANY、循环重入、退回重提、挂起/终止不会显示矛盾状态。

## 5. R09 分层验证及验收场景

### 5.1 验证顺序

1. 定向回归先证明原缺陷，再验证修复；关键用例至少一次在修复前失败或在隔离回退该修复时失败。不能用生产实现本身计算唯一预期值。
2. 真实 bpmn-js 浏览器组件验证命令栈、导入、撤销/重做；此层目录夹具不算服务器保存/部署证据。
3. 真实 Flowable/H2 验证编译、部署、开始、父子传参、事件竞争及事务行为。
4. 隔离 MySQL 验证 CAS、快照、回滚、outbox/入站幂等及精确清理；需要外部调用的场景使用受控真实 HTTP 接收器。
5. 登录真实产品执行正式 API/浏览器链路，最后用原生鼠标键盘完成 A—G 关键用户路径并留截图。API/数据库证据辅助核实，不替代原生操作。

### 5.2 必须重跑的业务矩阵

| 场景 | 完成门禁 |
| --- | --- |
| A 500/3000 原长流程 | 保留 23 节点/25 顺序流，11 个任务定义均有适当到达证据，定时器后台自动推进 |
| A 相邻边界 | 三成员 ALL 等待全部；两成员 ANY 并发只有一个有效后继；并行不同办理顺序、包容单/多分支及零匹配默认路由 |
| B | 错误精确出口，正常出口不执行；非中断升级两项待办分别完成；编码切换和撤销后仍保留 |
| C | 两条关联保留；500/3000 均正式运行对应 DMN 结果；7→8/9 回传；只读系统结果、防伪提交与子启动失败回滚 |
| D | RECEIVE 恢复、消息胜出、信号胜出、同请求重放、内容冲突、竞争取消显示 |
| E | 服务输出和补偿输出准确；事务正常、取消补偿、补偿失败重试/恢复，不以正常路径代替异常验收 |
| F | 两池范围各自生效；原 MessageFlow 自动投递；配置端点往返不丢图；接收方迟到/认证失效及恢复 |
| G | 原事件消息→信号无需重建；独立消息抛出真实消费，与跨池协议分别验证 |
| 通用生命周期 | 退回/重提、撤回、终止、挂起恢复；任务/模型/端点无权限用户被拒绝；失败不遗留部分正式状态 |
| 设计恢复 | 导入失败、快速离开、刷新、登录过期、两个标签 CAS 冲突、模型新版本及草稿恢复 |

其中不能运行的场景明确列 blocked/not executed，完整修复状态保持未完成。不得把“预期拒绝按合同发生”计为功能失败。

### 5.3 现有测试承载位置与命令

优先扩展以下正式用例：[数据对象](/D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowDataObjectBusinessTest.java)、[调用活动](/D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowCallActivityBusinessRegressionTest.java)、[消息抛出](/D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowMessageThrowBusinessTest.java)、[可靠消息](/D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowReliableMessageBusinessTest.java)、[等待事件](/D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessWaitingBusinessTest.java)、[详情集成](/D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/process/WorkflowProcessDetailServiceIntegrationTest.java)。新增草稿/受管凭据用例须进入正式测试体系；新增真实 MySQL 用例采用现有 *IT.java/profile，不把 H2 结果改名为 MySQL。

以下命令本次未执行，实施时按顺序运行并单独记录结果：

```powershell
Set-Location -LiteralPath 'D:\ruoyiflowable'
mvn -B -pl ruoyi-flowable -am '-Dtest=WorkflowDataObjectBusinessTest,WorkflowCallActivityBusinessRegressionTest,WorkflowMessageThrowBusinessTest,WorkflowReliableMessageBusinessTest,WorkflowProcessWaitingBusinessTest,WorkflowProcessDetailServiceIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
mvn -B -pl ruoyi-admin -am test
mvn -B -pl ruoyi-flowable -am -Pworkflow-mysql-it verify
```

MySQL profile 必须显式提供 WORKFLOW_MYSQL_TEST_URL、WORKFLOW_MYSQL_TEST_USERNAME、WORKFLOW_MYSQL_TEST_PASSWORD，且 URL 指向隔离测试库。执行前核对当前 profile；不把业务库当测试库。

```powershell
Set-Location -LiteralPath 'D:\ruoyiflowable\ruoyi-ui'
npm run test:contracts
npm run test:e2e:designer
npm run lint
npm run build:prod
```

浏览器命令依据[现有说明](/D:/ruoyiflowable/ruoyi-ui/tests/e2e/README.md)，准备 WORKFLOW_E2E_BASE_URL、登录账号及 PLAYWRIGHT_CHANNEL。新增体验用例可命名 workflow-designer-ux.spec.js 以进入当前脚本的文件匹配范围。这里只表示承载位置，现有命令通过不自动证明上述新场景已被覆盖。

## 6. 协议、数据与回退要求

| 改动 | 迁移策略 | 回退要求 |
| --- | --- | --- |
| 模型设计草稿 | 独立业务存储，保留基准与 CAS 修订，不改已部署资源 | 可关闭新写入口；草稿可导出/读取，不通过删表丢失用户工作 |
| 受管凭据 | 显式新 provider，旧 ENV 引用保持 | 禁止发布回不识别新 provider 的旧代码；先停止新引用、处理绑定，再按兼容版本回退 |
| 系统回填权限 | 新能力默认不授予，旧快照不批量放宽 | 已部署使用该协议的实例必须由兼容代码继续处理；不能回退到未知字段被静默忽略的版本 |
| 诊断/轨迹投影 | 兼容扩展或显式版本化，同步所有构造和消费方 | 新旧前后端组合需验证；未知事实显示未知，不伪装完成 |

新增 SQL 使用项目现有增量目录及迁移约定，实施时确定唯一版本名；不只修改初始化 SQL。验证空库安装和已有库升级。不得直接写 Flowable 引擎表“修复”生命周期。

## 7. 完成定义与交付清单

- R01–R08 每项有具体 diff、回归用例、实测结果及证据；R04 如仅提前拒绝原组合，不允许标为完全修复。
- 本轮原模型关键组合完整保留，A—G 保存→部署→发起→运行链路有正式证据；故障恢复和权限负向另列。
- 结果分别写 passed / failed / blocked / not executed / partial，不用累计测试数量替代业务覆盖。
- 交付修复记录、场景矩阵、接口/迁移说明、必要脱敏截图及准确实例/请求关联。
- 清理本轮临时业务资源及本地临时文件，按精确 ID/绝对路径核对；业务审计按正式保留规则处理，不强删。
- 旧报告遗留临时文件不自动纳入本轮批量清理；若明确纳入，先辨明所有权与目标边界。遇审批拦截保留阻塞事实，不绕过。
- 核查继承修改仍完整，diff 中没有无关改动；变更留待 Review，未经单独授权不提交/推送。

推荐首先执行 R00 和 R01 的离开保护，再以 R02/R03 建立小范围回归闭环，随后解决 R04 的真实部署根因。
