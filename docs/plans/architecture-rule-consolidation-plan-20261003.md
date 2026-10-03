# 全仓业务规则归一与职责收口：任务清单和执行方案

日期：2026-10-03。分析基线：`70b2c0a1357c56ab1c7e645ef1410046c5a303c4`。状态：**B00—B18、C01 及后续 4 项 P2 复核修复已全部完成并验收**。

本计划已执行，逐包提交、行为变化、最新 249 项后端测试及前端和真实浏览器验收、环境清理记录见[执行结果](architecture-rule-consolidation-execution-result-20261003.md)。执行结果文末另列 `49c963d` 审查后 4 项 P2 的复现、修复与重新验收。下文保留原执行合同和任务范围。

## 1. 当前判断

上一轮删除冗余与 N00—N09 职责整理已经完成。本轮继续完成模块化单体内部的规则归一、状态所有权和异常处理收口。保留现有 7 个 Maven 模块、HTTP 接口和数据库主体结构。

本次全仓扫描发现三类剩余工作：

1. **相同规则仍有多个所有者**：人工催办资格、已办列表撤回资格、生成器渲染步骤。
2. **状态更新路径不一致**：在线权限快照、分类编码、字典缓存、异步列表响应、Quartz 定义替换。
3. **失败合同与重复读取仍需收口**：协作投递超限异常、附件上传台账清理异常、邮件配置读取、协作频道锁定事实、扩展快照摘要。

行为问题与纯重构分开交付。需要改变错误行为的任务先建立复现用例；单纯迁移规则的任务保持原有合同。SQL 连接器另补真实事务和重放证据。

扫描范围、逐业务链结论及前端完整文件清单见[覆盖记录](D:/ruoyiflowable/docs/plans/architecture-rule-consolidation-coverage-20261003.md)。前序结果见[清理结果](D:/ruoyiflowable/docs/plans/architecture-cleanup-execution-result-20261002.md)和[边界整理结果](D:/ruoyiflowable/docs/plans/architecture-boundaries-execution-result-20261003.md)。

## 2. 执行合同

- 接口路径、请求响应结构、BPMN 表达式引用、部署资源名称、既有快照摘要、数据库字段和既有错误子码保持稳定。B01—B06、B09、B17 等明确列出的错误行为修正，以及必要的新错误分类，必须单独记录前后行为。
- 权限、状态迁移、revision/CAS、锁序、幂等和事务仍由后端持有。前端负责请求提交资格与交互状态。
- 保留 DMN、CallActivity、多实例、受控循环、多池协作、连接器、事件、SLA、通知和附件能力。
- 保留 T-08 附件绑定时再次检查有效期、I-14 认证探测原锁事务。两项已有反例证明删除条件不成立。
- 不恢复 EngineAdapter、TaskWriteHook；不新增 Maven 模块、消息总线、通用执行框架、全局业务 Context 或万能测试工厂。
- 使用现有所有者承接规则。新增文件须说明迁走了哪些旧逻辑；删除旧实现和旧调用才算完成迁移。
- 每项开始先在当前 HEAD 复核证据。任务之间通过明确文件范围协调；保留继承的未提交改动，不广泛暂存、不重置工作区。
- 验证脚本、日志、临时数据和临时文件完成后清理；正式回归测试纳入现有测试体系。数据库清理只针对本任务记录的 ID。

## 3. 总任务清单

优先级表示执行顺序与收益；“高置信度”表示源码证据明确，不表示已经做过运行时复现。共 19 个主任务：1 个基线包、15 个生产处理包、3 个验证与交付包；另有 1 个先取证再决策的 C01 调查任务。B06 细分为三个可分派子包。ID 固定，执行顺序按依赖和批次排列。

| ID | 任务 | 性质 | 优先级 | 依赖 | 状态 |
|---|---|---|---|---|---|
| B00 | 固定基线、范围与现有测试结果 | 执行准备 | 必先 | 无 | 已完成 |
| B01 | 统一授权变更后的在线身份快照 | 状态归属＋行为修正 | 高 | B00 | 已完成 |
| B02 | 将分类编码不可变规则落实到后端 | 规则归一＋行为修正 | 高 | B00 | 已完成 |
| B03 | 协作 Outbox 超限响应进入失败状态机 | 异常合同修正 | 高 | B00 | 已完成 |
| B04 | Quartz 定义构造与替换统一所有者 | 去重＋行为修正 | 高 | B00 | 已完成 |
| B05 | 字典缓存按 key 唯一、失效完整 | 删除冗余＋状态修正 | 中 | B00 | 已完成 |
| B06 | 全仓可重入列表与弹窗响应提交权 | 状态归属＋行为修正 | 中 | B00；相关页面编辑串行 | 已完成 |
| B07 | 已办查询脱离撤回写应用服务 | 职责迁移＋同次事实复用 | 中 | B00 | 已完成 |
| B08 | 人工催办复用认领资格 SQL | 业务规则去重 | 中 | B00 | 已完成 |
| B09 | 上传台账与正式附件清理异常分类对齐 | 异常合同归一 | 中 | B00 | 已完成 |
| B10 | 邮件可用性复用同次配置事实 | 局部去重＋死返回清理 | 低 | B00 | 已完成 |
| B11 | 协作入站复用已锁定的频道事实 | 局部去重 | 中 | B03 后同域串行 | 已完成 |
| B12 | 扩展快照摘要归入现有摘要所有者 | 职责迁移 | 低 | B00 | 已完成 |
| B13 | 生成器三种出口共用准备与渲染 | 局部业务步骤去重 | 中 | B00 | 已完成 |
| B14 | SQL 连接器事务与重放验收 | 正式合同测试；按失败修复 | 高 | B00 | 已完成 |
| B15 | 固定可复现的分层验收入口 | 文档与执行装配 | 中 | B00，可提前准备 | 已完成 |
| B16 | 全仓关闭检查与真实业务总验收 | 最终交付 | 必须 | 全部实施包及 B14/B15 | 已完成 |
| B17 | 正式表单作者变量规则归一 | 规则归一＋行为修正 | 高 | B00 | 已完成 |
| B18 | 自动抄送编辑与写入共用纯规则 | 前端业务规则去重 | 低 | B00 | 已完成 |
| C01 | 分类/表单删除与新引用创建交错取证 | 条件调查，不预定生产修改 | 中 | B00 | 已取证并修复 |

## 4. 逐项执行包

### B00：执行基线

1. 记录 HEAD、分支、工作区和暂存区；对照本文件路径重新核实问题仍存在。
2. 把前序 155 个后端测试、28 个前端契约测试和三条真实业务链当作历史证据。执行任务记录自己的环境与实际结果。
3. 固定可销毁的 `_it` 数据库、Redis、后端、前端以及合成账号；真实锁、重放和浏览器验收使用隔离环境。
4. 为各包记录“生产删除／生产迁移或新增／测试／文档”范围。无需为本步骤新增代码框架。

完成标准：基线与任务所有者清楚，环境条件和跳过项可核对。

### B01：统一授权变更后的在线身份快照

**证据与影响（高置信度，中至高影响）：**[SysRoleController:128](D:/ruoyiflowable/ruoyi-admin/src/main/java/com/ruoyi/web/controller/system/SysRoleController.java:128)只有角色编辑调用在线刷新；dataScope、changeStatus、用户授权/撤权入口没有同等路径。[TokenService:416](D:/ruoyiflowable/ruoyi-framework/src/main/java/com/ruoyi/framework/web/service/TokenService.java:416)使用缓存的 `LoginUser.user.roles` 重新计算权限，没有重新读取用户角色事实；[DataScopeAspect:81](D:/ruoyiflowable/ruoyi-framework/src/main/java/com/ruoyi/framework/aspectj/DataScopeAspect.java:81)继续消费这些角色状态与数据范围。不同入口会留下不同的新旧权限组合。

**范围：**上述文件、`SysPermissionService`、`SysLoginController.getInfo`、`SysUserController`、`SysMenuController` 的授权相关写入口、现有用户/角色/菜单查询。普通资料与菜单排序更新无需无差别刷新全部用户。

**做法：**

1. 先覆盖登录后撤销角色、停用角色、缩小数据范围、修改用户角色、修改菜单权限的场景；同时检查授予权限与多 Token 情况。
2. 由现有 framework 登录/权限服务与 TokenService 统一“读取当前用户和角色事实→计算权限→更新在线快照”。复用 `ISysUserService.selectUserById` 读取完整 user/roles，不使用受操作者数据范围限制的查询；移除仅基于旧 roles 刷新的分支。
3. 在成功提交后，授权变更入口确定受影响 userId 并调用同一刷新路径，更新这些用户的全部在线 Token；不能以旧缓存 roles 筛选而漏掉新授予角色的用户。system 不反向依赖 framework，flowable 不重新引入 framework。
4. 角色集合、各角色权限、status/dataScope、LoginUser.user/deptId 与总权限来自同次一致的身份事实。读取发现用户缺失、禁用或删除时，使其现有在线会话失效；当前 JWT 过滤器没有实时账户状态检查，不能假定这已由旧逻辑覆盖。
5. `/getInfo` 使用同一事实来源，不能只比较总权限 Set 决定是否更新。统一 TokenService 的缓存写入边界，续期和资料更新不得把旧身份事实写回覆盖新结果；使用必要的条件写或受控失效保护，并用竞争用例选择最小实现。
6. 保留 Token 协议、用户 1 规则、会话标识和既有到期策略；缓存刷新失败不能伪称数据库已回滚。生效范围是成功刷新后的后续请求，不中断已经进入业务的请求；刷新失败要显式记录并保留可重试入口。

**验收：**同一个登录会话在变更后，普通系统接口、数据范围和 `/getInfo` 结果一致；总权限不变而角色范围变化仍更新；禁用/删除账户后续请求被拒绝；撤权刷新与旧请求续期/资料写回竞争时旧权限不复活；数据库写失败不刷新。保留工作流实时权限复核。使用真实 Mapper/事务与受控缓存验证，再以隔离 Redis 验证跨请求与条件写结果。交付中明确本项修正了权限生效行为。

### B02：分类编码不可变规则归后端

**证据与影响（高置信度，中影响）：**[分类页面:42](D:/ruoyiflowable/ruoyi-ui/src/views/workflow/category/index.vue:42)编辑时禁用编码；`WfCategoryController.edit`、`WfCategoryServiceImpl.updateCategory` 和 Mapper 仍允许修改 code。模型、定义按分类编码字符串关联；[WorkflowModelService:836](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowModelService.java:836)继续按原编码校验分类，直接 API 改码会破坏后续关联。

**做法：**以现有产品编辑规则为准，由分类服务读取现有分类并以 409 拒绝改码；Mapper 更新移除 code 赋值，删除更新分支不再需要的编码唯一性检查，保留新增与数据库唯一键保护。请求字段保留，相同 code 的正常更新继续成功。分类名称、备注仍可编辑。删除前引用检查继续覆盖模型和已部署定义。

**验收：**创建→关联模型→尝试直接 API 改码被拒绝，原分类与模型引用不变；改名称/备注成功；不存在分类仍为 404、重复新增仍为 409、受引用删除合同不变；既有模型仍可保存/预检/发布。不迁移历史分类编码。本项新增后端业务校验，应记录错误响应合同。

### B03：协作 Outbox 的超限异常归入投递失败合同

**证据与影响（高置信度，高影响，待运行复现）：**[WorkflowHttpExecution:78](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/integration/WorkflowHttpExecution.java:78)不转换 `ResponseTooLargeException`；[OutboxService:226](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowCollaborationOutboxService.java:226)只转换 ServiceException/JacksonException；[Scheduler:59](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowCollaborationDeliveryScheduler.java:59)记录运行异常后退出本条。[Mapper:93](D:/ruoyiflowable/ruoyi-flowable/src/main/resources/mapper/flowable/WfCollaborationOutboxMapper.xml:93)允许过期 DELIVERING 再领取，超限响应可绕过正常失败/死信路径。

**做法：**

1. 用受控 HTTP 返回超过 64 KiB 的响应，配真实 MySQL 与实际 worker 领取/完成链，观察 attempt、lease、revision、重试和死信。
2. 在现有 HTTP/协作适配边界将预期超限异常转为脱敏、稳定的投递失败结果，由 `completeFailure` 统一完成状态迁移。
3. 同时覆盖连接超时、无效 JSON、非成功 HTTP、进程中断后的租约恢复。明确“已知投递失败”与“进程崩溃”的合同。
4. 如要调整过期领取与 maxAttempts 的关系，先给出崩溃恢复反例和测试；不能只在 SQL 增加上限过滤后把消息永远留在 DELIVERING。

**验收：**确定的超限失败最终进入既有重试/死信路径；消息顺序、幂等键、租约与 revision 不变；过期 worker 不能覆盖新 worker；补偿与取消仍有效。保持 BPMN HTTP 执行器自己的错误映射。

### B04：Quartz 定义先构造、再统一替换

**证据与影响（高置信度，中影响，待运行复现）：**[SysJobServiceImpl:236](D:/ruoyiflowable/ruoyi-quartz/src/main/java/com/ruoyi/quartz/service/impl/SysJobServiceImpl.java:236)先删除旧 Job，再调用 [ScheduleUtils:57](D:/ruoyiflowable/ruoyi-quartz/src/main/java/com/ruoyi/quartz/util/ScheduleUtils.java:57)构造新定义；后者又维护删除逻辑。非法 misfirePolicy 在构造阶段抛错，控制器仅预检 cron/调用目标，旧调度可能已经被移除。

**做法：**以现有 ScheduleUtils 为调度定义构造和替换的单一所有者，完整构造/校验并确定下一次执行时间、暂停状态后再产生 scheduler 副作用；Service 保留数据库事务和旧 jobGroup 事实。删除重复 check/delete 分支，保留改组时旧键清理。合法但已过期的 cron 仍按原合同移除旧调度、不新建调度。无需通用调度接口或分布式事务。

**验收：**真实 RAM Scheduler 中，非法策略/表达式不删除旧 Job；同组更新、跨组更新、暂停状态、过期 cron、并发策略、立即运行、删除保持原合同。注入 scheduler 操作失败并明确数据库与 scheduler 的恢复边界；不得把 `@Transactional` 当作内存调度器已参与事务的证据。

### B05：字典缓存统一按 key 管理

**证据与影响（高置信度，中影响，已做最小逻辑复现）：**[dict store:24](D:/ruoyiflowable/ruoyi-ui/src/store/modules/dict.js:24)每次 set 都 push，[removeDict:33](D:/ruoyiflowable/ruoyi-ui/src/store/modules/dict.js:33)只移除第一项；[useDict:16](D:/ruoyiflowable/ruoyi-ui/src/utils/dict.js:16)允许同 key 的多个未命中请求回填。实际调用当前 action 连续写两次再删除，仍残留一项。另有不可能成立的空 key 条件、吞错 catch、无调用的 initDict 和多余立即执行包装。

**做法：**

1. 保持现有对外 get/set/remove/clean 调用方式与数据形状，内部按 key 替换；删除时清除全部同名项。
2. 在现有字典 store 集中同 key 加载、在途请求复用与回填资格；失效/清空后，旧请求既不能恢复过期缓存，也不能向 `useDict` 的响应式结果提交旧值。请求失败后释放在途状态并允许重新加载。
3. 删除空 initDict、无意义 catch 和包装；`useDict` 保留响应式展示适配。
4. 核对字典类型改名/删除和字典数据增删改的缓存失效，旧 key 与新 key 都按实际变更处理。

**验收：**同 key 并发加载、覆盖写、删除、清空、失败重试、失效后旧请求晚到；字典页面修改后重新读取获得新值。测试直接覆盖现有 store/工具行为，无需完整后端。

### B06：全仓列表与弹窗响应提交权

**证据与影响（高置信度，中影响）：**[WorkflowProcessList:301](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/WorkflowProcessList.vue:301)、[草稿页:101](D:/ruoyiflowable/ruoyi-ui/src/views/workflow/work/draft.vue:101)和覆盖记录列出的目录/管理页面，多个查询入口无请求代次保护，旧响应直接写 rows/total/loading。[协作页面:179](D:/ruoyiflowable/ruoyi-ui/src/views/workflow/collaboration/index.vue:179)确认重试后重新读取 activeDirection，也可能操作与确认时不同的方向。

**子包：**

- **B06-A：**工作流公共列表、草稿、分类、模型、部署、DMN、扩展目录。
- **B06-B：**通知 Outbox、集成端点/数据源/凭据、协作、运行事件、BPMN 事件/SLA 管理。
- **B06-C：**系统用户/角色/组织/字典/公告/短信/OSS、监控列表与代码生成管理页面。

**做法：**以已存在的 form 列表局部 request sequence 为参照，为每个独立结果集确定唯一提交资格。开始请求时冻结查询与对象身份；只有当前请求可提交数据、错误状态和 loading；关闭、切对象、卸载时失效。多个页面共用一个小型请求代次工具即可，业务 rows、过滤、分页和状态仍留在各页面。不得搬入详情轮询/重试/缓存框架。

协作重试、审计弹窗等跨 await 操作应冻结方向和消息 ID，确认结束后验证操作仍属于同一对象；连接器凭据弹窗冻结 endpointId，防止 A 的慢响应覆盖 B 的凭据目标。已有保护的 form、模型历史、部署版本、通知策略、MailConfigDialog、详情观察器保留现有实现；只验证其合同，不重新改写。

**验收：**先发 A 后发 B，B 先成功/A 后成功；A 失败/B 成功；当前请求失败后 loading 收敛；关闭再打开不同对象；卸载后返回；切方向时确认旧协作操作。按子包选择代表页面做受控延迟浏览器测试，再逐页检查所有提交点已接入；完整页面范围见覆盖记录。

### B07：撤回资格脱离写应用装配

**证据与影响（高置信度，中影响）：**[WorkflowProcessTaskQueryService:64](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowProcessTaskQueryService.java:64)仍注入 [WorkflowTaskRevokeApplicationService:95](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/task/WorkflowTaskRevokeApplicationService.java:95)，已办行调用 `isProcessRevocable`，重复读取当前身份与历史任务。读取资格仍拉入撤回写链装配，未被上一轮退回/重提准备职责迁移覆盖。

**做法：**把撤回资格及其只读准备事实交给具体的撤回 Preparation，查询与写命令共用，参照现有 Return/Resubmit Preparation。列表传递本次已经获取的 actor/历史任务事实；`writeAsCurrentUser`、审计、concurrencyExecutor、`lockAndRevalidateRevokeTasks` 与引擎迁移继续归写应用服务。删除查询对写应用的注入和应用内旧只读准备实现。

**验收：**可撤回/不可撤回、已有下游处理、已结束/取消、非参与者、分页多行场景；每页身份只解析一次并复用历史任务，导出不增加资格计算；保持列表对既有 400/403/404/409 的 false 投影、500 抛出及命令错误语义，false 不使外层事务 rollback-only；并发审批后撤回拒绝。资格测试不装配审计、通知、附件写入，写命令仍验证事务回滚。

### B08：人工催办复用认领资格 SQL

**证据与影响（高置信度，中影响）：**[WorkflowManualUrgeService:390](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowManualUrgeService.java:390)重复 [WorkflowIdentityMapper.xml:59](D:/ruoyiflowable/ruoyi-flowable/src/main/resources/mapper/flowable/WorkflowIdentityMapper.xml:59) 的有效用户、角色、菜单、授权关系等认领资格判定。资格变化需同步修改多个实现。

**做法：**将候选查询放进现有 IdentityMapper，复用已有资格 SQL 片段；删除 Service 内拼接的同义资格 SQL。继续区分已分配处理人、未分配候选集合、只读预览和正式催办。

**验收：**多角色权限并集、停用/删除关系、撤权竞争、用户 1 特例、候选组与直接候选人。保留 2001 条上限、排序、任务排他锁与身份共享锁，以及读取/写入模式。真实 MySQL 验证锁语义；不替换成无锁普通 resolver。

### B09：附件上传台账清理的异常合同对齐

**证据与影响（高置信度，中影响）：**[WorkflowAttachmentUploadJournal:106](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentUploadJournal.java:106)捕获全部 RuntimeException 当作存储重试；重试登记自身失败会覆盖原始异常。已有 [WorkflowAttachmentCleanupCoordinator:99](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/attachment/WorkflowAttachmentCleanupCoordinator.java:99)区分存储操作异常并保留原始原因。路径完整性/程序错误和可重试 IO 目前被不同入口不同处理。

**做法：**仅 `WorkflowAttachmentStorageOperationException` 进入重试登记，完整性失败和其他程序异常继续抛出。重试登记失败时保留原异常及 suppressed，不引入新的清理状态机。两条路径遵循同一异常合同，保留各自业务状态和事务。

**验收：**IO 删除失败、路径/普通文件检查失败、重试登记失败、租约漂移、物理删除成功但完成登记失败。保留 Journal 的 REQUIRES_NEW、事务外 IO、lease token、上传预留台账及正式附件锁序；重新跑附件过期绑定反例。本项不声称仅靠异常冒泡即可解决所有租约恢复重试。

### B10：通知域同次事实与无用返回清理

**证据与影响（高置信度，低影响）：**[WorkflowMailConfigService:249](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowMailConfigService.java:249)先查配置，再调用 `currentSender` 重查配置，两次之间无外部副作用。[WorkflowNotificationOutboxService:291](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/notification/WorkflowNotificationOutboxService.java:291) 的 deadLetterOneExhausted boolean 返回值没有消费者。

**做法：**在现有 senderMonitor 内一次读取配置，由同一行事实处理可用性和 revision 缓存；公开 currentSender 行为不变。删除死返回值及多余分支，不合并通知与协作的 Outbox 状态机。

**验收：**无配置仍为不可用；损坏密文仍报错；相同 revision 复用 sender；更新 revision 后重建；旧配置投递不被意外替换。保留配置更新返回、密钥派生和原有死信状态迁移。

### B11：协作入站复用频道锁定事实

**证据与影响（高置信度，中影响）：**[WorkflowCollaborationMessageService:217](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/process/WorkflowCollaborationMessageService.java:217)的新消息分支锁定频道后丢弃返回对象，在同一事务写接收消息/审计后于 228 行再次 lockInbound；中间没有频道游标变更或事务切换。

**做法：**保存首次锁定的频道对象，新消息/已有消息分支汇合时使用各自已经取得的受锁事实。删除第二次 insertIfAbsent/selectForUpdate；旧消息分支仍取得一次有效锁。重放后重新进入的事务重新读取。

**验收：**新消息、同内容重放、同 ID 不同内容、并发序列、乱序等待/补偿、不同频道并发。必须保持“先频道锁→消息状态/游标更新”的原锁序、唯一约束和完整性检查；不得缓存到下次请求。

### B12：扩展快照摘要归位

**证据与影响（高置信度，低影响）：**运行 [WorkflowExtensionDelegate:72](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowExtensionDelegate.java:72)、[WorkflowBusinessListener:112](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/extension/WorkflowBusinessListener.java:112) 调用设计服务的 [snapshotChecksum:579](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/model/WorkflowExtensionDeploymentService.java:579)。已有 `extension/WorkflowExtensionChecksum` 可持有该协议算法。

**做法：**迁移 snapshotChecksum 到现有摘要类，同步发布/Delegate/Listener/测试调用，删除旧静态入口。类型常量目前没有合适的现存协议所有者，本轮保留 Registry 常量引用，不另建类型框架。

**验收：**用固定旧快照确认字段顺序、长度前缀、null 编码、canonical JSON 和摘要逐字节一致；部署制品与运行读取继续互认。不修改资源 schema 或重算数据库历史值。

### B13：生成器准备与渲染步骤归一

**证据与影响（高置信度，中影响）：**[GenTableServiceImpl:208](D:/ruoyiflowable/ruoyi-generator/src/main/java/com/ruoyi/generator/service/GenTableServiceImpl.java:208)、[252](D:/ruoyiflowable/ruoyi-generator/src/main/java/com/ruoyi/generator/service/GenTableServiceImpl.java:252)、[370](D:/ruoyiflowable/ruoyi-generator/src/main/java/com/ruoyi/generator/service/GenTableServiceImpl.java:370) 分别重复主子表/主键准备、Velocity context、模板清单及 merge。新增模板上下文规则需维护三条路径。

**做法：**在现有 Service 内提取共享准备和单模板渲染步骤；预览、写文件、ZIP 保留各自出口。原重复代码删除，无需 Renderer 接口或生成器工厂。

**验收：**同一配置预览与 ZIP 对应条目内容一致；自定义路径继续仅写既有允许的模板；单表/树表/主子表、详情页、Vue2/Vue3/TypeScript 模板全部保留；批量 TypeScript index-bak 合并仍正确。使用真实模板及小型元数据夹具，比较内容和条目名，不比较含时间戳的 ZIP 原始字节。

### B14：SQL 事务与重放合同

**证据（高置信度，验证空缺）：**[现有编译测试:232](D:/ruoyiflowable/ruoyi-flowable/src/test/java/com/ruoyi/flowable/service/model/WorkflowModelCompilationIntegrationTest.java:232)覆盖 PRIMARY 成功写入和保留变量；当前未找到 PRIMARY 下游失败回滚、EXTERNAL 已提交后 Job 失败重试的直接断言。

必须交付四组场景：

1. PRIMARY 真实 Spring 事务写入后下游异常，行不存在；成功提交作对照。
2. EXTERNAL 写入已提交后 Flowable Job 失败，重试同一 Job 仍只有一行业务数据、原值不被改写、流程最终完成。
3. 相同执行身份并发重放只写一行；不同 executionId 正常写另一行。
4. 外库模板 UPDATE/DELETE、缺系统幂等参数、重复键分支改业务列、不允许的结果变量在执行前拒绝。已有覆盖复用，不重复造测试。

PRIMARY 连接参与可先用 H2；EXTERNAL 使用真实 MySQL、唯一索引、独立 DataSource 与真实 Flowable Job 事务。同一 MySQL 实例上的独立测试库即可。复用已有 engine support，小流程验证，不通过完整模型发布准备所有局部用例。若失败，按复现修正并单独说明；本计划未断言这些行为已经有 bug。

### B15：可复现的验收入口

**证据与影响：**[CONTRIBUTING:22](D:/ruoyiflowable/CONTRIBUTING.md:22)的覆盖描述尚未完整反映 N09 真实业务链；[真实业务 spec:12](D:/ruoyiflowable/ruoyi-ui/tests/e2e/workflow-business-boundaries.spec.js:12)缺环境会 skip，Playwright 配置不启动服务。执行结果依赖人工装配和识别跳过项。

**做法：**更新现有 CONTRIBUTING，提供本地快速检查、MySQL 合同、受控响应浏览器、真实业务浏览器四种明确入口。必要时增加一个薄 PowerShell 入口，检查环境、调用已有命令、汇总真实执行/失败/跳过；不复制测试内部业务夹具和 14 个 SQL 初始化顺序，不新增全自动环境管理框架。

**验收：**真实验收模式缺库/账号/服务即失败而不是绿色 skip；报告记录 HEAD、运行模式、数量和未执行项；使用新任务按文档从准备环境到精确清理完成一轮。密码从环境读取，不写入日志或文档。

### B16：最终关闭验收

1. 对所有实施包及 B14/B15 逐项登记已完成/有反例保留/实际阻塞，附最终路径、提交与验证证据；C01 记录实验结果与决策。发现反例时保留原实现并说明，不靠标记完成消除问题。
2. 检查已迁移规则只有一个所有者，旧实现/包装/注入确实消失；检查没有重新增加 flowable→framework 依赖。
3. 执行完整后端、前端静态/契约/构建，运行受控浏览器和真实三链；补本轮新增权限、分类、协作失败和 SQL 重放验收。
4. 分别统计生产删除、迁移/必要新增、正式测试与文档。清理临时脚本、数据、日志和仅本任务启动的进程。
5. 输出执行结果 Markdown。按包独立提交，最终 review 再确认提交范围；推送按用户明确指令执行。

### B17：作者表单变量规则在现有 Validator 收口

**证据与影响（高置信度，中影响）：**[WorkflowFormTemplateValidator:72](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/WorkflowFormTemplateValidator.java:72)校验组件/布局/权限元数据，却未统一检查变量名是否合法、是否为系统保留名、同模板是否重复；extractVariableNames 用 Set 静默去重。[WfFormServiceImpl:201](D:/ruoyiflowable/ruoyi-flowable/src/main/java/com/ruoyi/flowable/service/impl/WfFormServiceImpl.java:201)依赖该校验保存；后续字段目录、节点权限快照和发布才会拒绝部分非法模板。[表单生成器:390](D:/ruoyiflowable/ruoyi-ui/src/views/tool/build/index.vue:390)另外手写正则和重复检测，未统一业务保留名合同。

**做法：**

1. 在现有模板 Validator 的作者输入入口复用 `WorkflowVariableNames.isBusinessVariable`，全模板一次遍历检查正式字段变量与重复。无变量的布局/按钮等组件继续按现有组件协议处理。
2. 模板新增/修改、`WfFormServiceImpl.inspectProtocol` 的 protocolValid 判定、实时作者字段目录、模型初始表单权限使用明确的作者校验入口，非法新输入在保存阶段给出可定位的 400；存量非法作者模板只展示协议问题，不改写正文。
3. 前端生成器复用已有 `designer/workflowVariableNames.js`，删除手写同义正则；直接验证实际要序列化的变量名，不能 trim 后通过却持久化原字符串。保留前端及时提示，后端做最终判定。
4. 保留 `decode` 与部署快照读取的原有合同，不把作者编辑阶段新增校验重新施加到所有历史实例。发现存量非法模板先列明 ID 与问题，不自动修名或删除。

**验收：**合法标量/容器、嵌套重复名、非文本/前后空格/非法字符、保留变量、缺省变量和最大长度；同一模板内重复拒绝，不同节点/不同模板同名仍按现有语义允许；模板编辑、字段目录、预检/发布给出一致结果；历史部署快照仍可读取运行。字段权限、附件和自定义 FORM_FIELD 扩展版本保护不变。

### B18：自动抄送前端规则只有一个实现

**证据与影响（高置信度，低至中影响）：**[AutoCopyRuleEditor:275](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/AutoCopyRuleEditor.vue:275)的 validateDraft 与 [extensionEventSlaDomain:416](D:/ruoyiflowable/ruoyi-ui/src/components/workflow/designer/extensionEventSlaDomain.js:416)的 normalizeAndValidateAutoCopyRules 重复数量、规则 ID、来源和值校验；编辑器缺正式表单字段成员检查，写入时才报错。

**做法：**在现有 designer 域提取一份不依赖 modeler/Vue 的纯规范化规则，显式传入触发白名单、字段白名单与约束。编辑器消费校验结果用于提示，写入域消费规范化值并进行一次原子 modeling command；删除两处旧规则实现。保持读取历史配置时关闭上下文检查的模式、结构校验及错误定位；版本 1、10/20/100 数量上限、8192 长度限制和组件 props/events 不变。

**验收：**固定用户/组/发起人/表单字段，重复 ID/来源、数量边界、不同元素触发范围、字段不在目录；编辑器与写入结果一致；空数组仍表示删除属性；撤销/重做、未知扩展保留及失败回读行为不变。复用前端正式契约与设计器浏览器测试。

### C01：资源删除与新引用创建交错取证

**证据条件：**`WfFormServiceImpl:159`、`WfCategoryServiceImpl:162` 先查引用再删除；`WorkflowModelService:259/272` 先读资源再存模型。当前没有两事务交错的运行证据，不预定增加锁或修改事务。

**实验：**真实 MySQL/Flowable 装配中，以屏障控制“模型已读取有效资源、尚未保存→删除事务检查无引用并尝试提交→模型继续提交”，分类、表单分别运行；只用本任务合成资源并精确清理。

**决策：**如果现有事务机制阻止悬空引用，记录保证并关闭；如果两事务均可提交且留下失效引用，新增独立修复包，明确资源锁序与两侧调用方后实施。禁止直接删除引用检查或用全局锁掩盖问题。本调查可与 B02/B17 测试共用小型夹具。

## 5. 批次与并行方式

| 批次 | 内容 | 协作约束 |
|---|---|---|
| 0 | B00；B15 环境文档准备 | 固定共享基线，不启动生产改造 |
| 1 | B01、B02、B03、B04、B17；B14 测试、C01 取证 | 每项先复现。共享 MySQL 测试数据使用独立命名和 ID；重型真实环境验收串行 |
| 2 | B05、B07、B08、B09、B13 | 后端不同所有者可并行；身份权限与催办 Mapper 接口变动需要同步 |
| 3 | B06-A/B/C；B10、B11、B12、B18 | B06-A 等 B02 页面稳定，B06-C 等 B05；B06-B 等协作接口稳定。B18 与 B17 的 designer 公共规则改动协调 |
| 4 | B15 定稿、B16 | 汇总分支/提交后统一验证一次；只对新变更或失败重跑 |

B06 公共请求工具先由一个子包确定最小 API，再分派其他子包。三个子包不得分别造不同的请求管理器。后端任务保持独立提交，避免把跨域变更打成一次不可定位的大提交。

## 6. 验证命令与结果要求

PowerShell 7，在仓库根目录：

```powershell
git status --short
git diff --check
mvn -B -pl ruoyi-admin -am test
npm --prefix ruoyi-ui run lint
npm --prefix ruoyi-ui run test:contracts
npm --prefix ruoyi-ui run build:prod
```

最终构建按现有贡献指南执行 `mvn clean verify`。局部任务用自身相关测试先验证，完整套件由 B16 汇总执行，避免每次局部删除都重复重型环境装配。

浏览器分别记录受控 API 和真实后端结果：

```powershell
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm --prefix ruoyi-ui run test:e2e -- workflow-page-reuse.spec.js workflow-observation.spec.js
npm --prefix ruoyi-ui run test:e2e -- workflow-business-boundaries.spec.js
```

真实链还需 `WORKFLOW_MYSQL_TEST_URL/USERNAME/PASSWORD`、`WORKFLOW_E2E_USERNAME/PASSWORD`、正确的 `WORKFLOW_E2E_BASE_URL` 和隔离 Redis 参数，依 B15 文档检查。不得将 skip 计入通过数。测试报告只证明实际运行的环境和场景。

## 7. 不进入本轮实施的项目

- 微服务拆分、独立 worker 进程、消息中间件、数据库拆库、全仓 DDD 重写：留到目标部署的扩容/隔离需求有证据后决策。
- DTO 同形字段、CRUD 页面相似布局、短信/OSS 局部字符串校验：本轮不据此建立通用基类或工具层。
- Spring 单实现接口、AOP 代理、Mapper/XML 和协议适配类：有框架/事务/兼容职责的保留。
- 把通知、协作、附件、Flowable Job 合并为一个重试系统：状态、锁序、幂等对象和副作用不同，分别保留。
- 标准循环 XML、Vue2/TypeScript 生成模板、DMN、连接器等已有产品能力：保持可用。
- 多节点故障恢复、共享附件存储、长时间稳定性、容量和备份恢复：属于后续目标部署验收，不用本轮局部单测结论代替。

## 8. 新任务派发模板

> 执行 `docs/plans/architecture-rule-consolidation-plan-20261003.md` 的 Bxx（如为 B06，请指定 A/B/C）。先核对当前 HEAD、工作区和该项证据，阅读同名覆盖记录与相关前序结果。只实现该包和必要调用方/测试/文档，保留列明的权限、事务、锁、revision、幂等与公共协议。行为修正先给失败用例；职责迁移必须删掉原实现。运行适当验证并报告实际通过、跳过和未执行项。临时材料精确清理，交付变更清单与执行结果。不要扩张成通用框架或同时处理其他包。

B16 已给出[实际执行结果](architecture-rule-consolidation-execution-result-20261003.md)；原范围与验收合同保留供后续追溯。
