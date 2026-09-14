# 工作流修复执行报告（2026-09-12）

本报告记录 2026-09-12 的工作流修复及本地验收，是[待执行体验优化计划](WorkflowUXOptimizationExecutionPlan-20260912.md)的技术前置依据。R01–R08 的修复内容见第 2 节，A—G 原组合的本地保存、部署和运行结果及 R09 的分层结果见下文。报告交付时变更未暂存、未提交、未推送，留待 Review；没有生产发布。历史结果不代表当前工作区已重新通过验收。

## 1. 基线与范围

- 初始 HEAD：`ca162921e1bc1143799587b60107584ab1181bc0`；工作区初始无 Git 差异。继承的 `output/computeruse-*` 证据保持原样。
- Windows / PowerShell 7；本地前端 `127.0.0.1:1024`，后端 `127.0.0.1:8080`；Flowable 8、MySQL 8.4、Redis，实际后端启用 `druid,timers`。
- 正式用户链在本地开发库 `ry-vue` 执行；MySQL 集成测试使用独立 `approvaplat_it`。三个临时审批身份分别承担 ALL、ANY、并行审批和范围负向验证。
- 原组合从既有作者证据恢复，资源键、测试身份和业务配置在本次隔离前缀下重新配置。C 使用既有正式表单模板，没有修改或删除该模板。API 准备资源、原生导入编辑和实际办理在场景表中分开记录。

## 2. 修复内容与根因

| 任务 | 修复及当前结果 | 正式代码与测试入口 |
| --- | --- | --- |
| R01 | 当前编辑、正在保存、最后确认内容分离；同步导出覆盖最后一次键入；站内三选项离开保护、标签关闭保护、浏览器离开警告；独立服务端草稿、作者隔离、模型基准及 CAS、墓碑防 ABA、冲突对比、新版本关联。修复正式保存成功后未取消自动保存定时器、重新生成重复草稿的问题 | `model/design.vue`、`pageLeaveGuards.js`、`WorkflowModelDesignDraftService`；`WorkflowModelDesignDraftBusinessTest`、MySQL 存储测试、真实浏览器草稿/失效会话测试 |
| R02 | 替换事件类型和删除不兼容的 `approva.messageThrow.config` 属于同一命令；单次撤销/重做恢复类型、配置及引用。保留共享根定义、其他扩展和原节点；导入坏配置明确诊断，可撤销修复 | `ProcessDesigner.vue`、`extensionEventSlaDomain.js`、设计器正式 E2E |
| R03 | 只读取完整发起范围协议；缺协议显示未配置。编辑期间允许先选择 USERS 再选成员；正式保存仍严格核验。每个池独立写入和回读，服务端执行最终范围门禁 | `participantRuleDomain.js`、`ParticipantRuleEditor.vue`、领域合同及 F 原生/API 验证 |
| R04 | 原 C 的带名称 `dataOutputAssociation` 在 Flowable XSD 校验阶段失败：关联线不接受未限定命名空间的 `name` 属性。作者 XML 保留名称、引用、DI 和两条关联；只在执行副本移除该显示属性。补充引用和作用域诊断，继续使用受控调用映射完成变量传递 | `WorkflowDataAssociationContract`、`WorkflowBpmnXmlPreserver`、`WorkflowDataObjectBusinessTest` |
| R05 | 新增显式 `MANAGED_V1_` 密钥 provider、AES-256-GCM 存储、原子绑定/轮换/撤销，旧 ENV 解析保留且不回退。集成账号创建与绑定在同一事务提交；界面仅回读元数据。配置检查、专用认证探测和真实业务投递分别给出结果；受管端点投递时重新核验启停和绑定 | `WorkflowManagedConnectorSecrets`、`WorkflowConnectorCredentialService`、`WorkflowIntegrationCredentialBindingService`、连接端点/集成账号页面；真实 MySQL、HTTP 和 F/G |
| R06 | 增加默认不授予的系统回填资格；内嵌字段 `systemWritable` 与正式模板 `workflowSystemWritable` 同步进入目录和冻结快照。源可读、目标类型、隐藏及跨流程限制继续执行；父流程只读字段只有显式授权的调用输出可写。修复前端以带业务名称的方向文本判断系统输出，导致已授权字段仍误拒绝的问题 | `WorkflowSystemOutputContract`、`WorkflowSystemOutputListener`、调用编译/表单快照、`routingCallActivityDomain.js`；真实父子启动和失败回滚测试 |
| R07 | 保存校验与发布预检分离；预检运行真实编译链并返回作者/编译资源摘要，不创建部署或正式快照；正式部署在事务内重新核验。诊断保留流程、元素、字段路径、处理建议。端点停用的实测遗漏已补齐：现在返回 `r12_native_design / Activity_10q0xm0 / extension.config`，同时保留原 400/403/409 语义。未知系统异常沿用服务端定位编号 | `WorkflowModelService.preflight`、`WorkflowExtensionDeploymentService`、`WorkflowBpmnValidationIssue`、`WorkflowModelServiceUpdateIntegrationTest` |
| R08 | 按历史活动实例保留状态，区分完成、等待、事件竞争取消、已知取消原因及未知。等待、详情及轨迹在同一读取事务投影；页面可见时轮询，隐藏/离开停止，失败退避，旧响应不能覆盖新事实或正在填写的表单。修复查看器图例被画布挤出的问题 | `WorkflowProcessHistoryProjection`、`WorkflowProcessObservationService`、`ProcessViewer.vue`、详情页；真实双向事件竞争测试 |

通用生命周期还发现并修复一个真实断点：退回时，Flowable 将旧任务以 `Change activity to childReview` 结束，详情投影却把它当成已完成提交，报“历史任务缺少正式表单提交快照”。`WorkflowProcessFormDetailProjection` 现在跳过没有提交快照的已取消任务，正常完成任务缺少快照仍明确报错。新增真实 Flowable 状态迁移测试，随后在同一本地 MySQL 实例上验证退回详情、重提、挂起拒绝、恢复和完成。

修复前反例在本轮执行：R02 命令栈用例失败；R03 得到错误的 PUBLIC 默认值；R04 带名称关联的三个变体中两个发生 XSD 错误；R08 双向事件竞争把输方算作完成。修复后相应用例通过。普通、不带名称的 R04 旧用例原本通过，未将其误记为缺陷复现。

## 3. A—G 验收台账

“原生”指真实 Edge 窗口中的鼠标、键盘和文件选择器操作；Playwright 组件或登录 API 不算原生输入。作者结构、实例完整 ID、真实活动和顺序流分别见 [作者结构](evidence/workflow-repair-20260912/author-structure.json) 与 [运行事实](evidence/workflow-repair-20260912/runtime-facts.json)。

| 场景 | 创建/编辑 | 正式保存、部署 | 发起与运行 | 结果 |
| --- | --- | --- | --- | --- |
| A 500 / 3000 | API 准备隔离模型，原生导入原长流程 | 原生保存/部署；实数校验 23 节点、25 顺序流、11 用户任务定义 | 两次原生发起；首审/领取/金额审批和等待页原生操作；三个真实身份经 API 完成 ALL/ANY、并行及收尾。500/3000 联合覆盖全部 11 个任务定义 | passed |
| A 相邻边界 | 原作者节点、顺序流不删减；零匹配单独建条件变体 | 默认路由变体经正式 API 保存/部署 | ALL 前两人结束仍等第三人；ANY 两人并发为一成功一 409，只有一组后继；并行分别财务先/法务先；500 命中两条包容分支，3000 命中一条。金额 0 变体实际经过 `Flow_1cd23js`，不生成税务/采购任务 | passed（API + MySQL） |
| B 错误 | 原生导入、选择错误编码；编码撤销/重做另由真实 bpmn-js 验证 | 原生保存/部署 | 原生发起与出口审批；错误出口执行，正常出口不执行 | passed |
| B 非中断升级 | 原生导入保留非中断语义 | 原生保存/部署 | 原生发起，出现两个待办；完成第一个后另一个仍在，再分别完成 | passed |
| C | API 准备子定义/DMN；父模型原生创建、导入、调用目录及系统输出配置 | 父模型原生保存/部署；原生导出仍保留 `dataIn` 和 `dataOut` 的名称及引用 | 500、3000 均原生发起；子收到 7，经原生审批改为 8、9；父只读显示 8、9 及低额/高额 DMN 结果，原生完成父审批 | passed |
| D | 原生导入 RECEIVE + 消息/信号竞争 | 原生保存/部署 | 第一方向原生发起、运行事件控制台 RECEIVE 恢复、消息命中后原生完成审批；反方向 API 触发信号，原生查看取消图例。两方向输方明确竞争取消；同请求重放幂等、改内容 409 | passed |
| E 原组合正常路径 | 原生导入补偿、事务、服务组合 | 原生保存/部署 | 原生发起和完成，服务输出 `SERVICE_OK`、补偿输出 `COMP_OK` | passed |
| E 事务异常 | 正式 H2 用例建立真实事务/补偿模型 | 真实 Flowable 部署 | cancel end 后两项补偿；补偿失败保留 timer job，成功的另一项补偿不会提前开放出口；通过 ManagementService 重试成功后才生成出口任务 | passed（Flowable/H2）；原生异常路径未执行 |
| F | 原生导入原复杂双池图；配置页往返、草稿恢复；原生配置公开/指定用户两池、创建并绑定端点凭据 | 原生保存/部署，原 MessageFlow 保留 | 接收池与发送池原生发起；实际 outbox→HTTP→入站→接收池继续；双方完成。越界身份强制发起被 403 拒绝 | passed |
| F 故障恢复 | 正式接口轮换集成令牌、停用/恢复端点，未直接改消息状态 | 使用原部署冻结协议 | 接收者迟到、认证失效、端点停用均产生真实重试并恢复；同 ID 重放不增任务，内容冲突拒绝 | passed（API + 后台调度 + HTTP + MySQL） |
| G 消息转信号 | 原节点原生消息→信号→撤销→重做，专有配置同步恢复/清理 | 原生保存/刷新回读/部署，原节点和 MessageFlow 保留 | 原生发起、审批结束；信号事件正常执行，关联可靠消息由原受控发送任务完成 | passed |
| G 独立消息抛出 | 单独模型原生导入并配置独立抛出 | 原生保存/部署 | 原生发起；实际 POINT_TO_POINT 入站消费，接收实例继续；两方原生完成 | passed |

A 的原包容条件为 `amount > 0` 或 `amount <= 2000`，覆盖所有金额，原图的默认路径不能由金额直接触发。零匹配变体只给第二条件增加 `amount > 0`，明确作为相邻边界，不声称原 500/3000 模型走过默认路径。定时器由后端执行器自动获取并推进；PT5S 表示到期条件，实测调度晚于 5 秒，页面没有宣称精确 5 秒完成。

对应截图：

- [A 等待自动推进](evidence/workflow-repair-20260912/native-a-timer-auto-advanced.png)、[A 500 完成](evidence/workflow-repair-20260912/native-a-500-completed-graph.png)、[A 3000 完成](evidence/workflow-repair-20260912/native-a-3000-completed-graph.png)。
- [B 错误出口](evidence/workflow-repair-20260912/native-b-error-route.png)、[非中断双待办](evidence/workflow-repair-20260912/native-b-escalation-dual.png)。
- [C 两条关联与正式模型](evidence/workflow-repair-20260912/native-c-export-completed.png)、[子流程收到 7](evidence/workflow-repair-20260912/native-c-child-received7.png)、[父只读 8](evidence/workflow-repair-20260912/native-c-parent-500-readonly8.png)、[父只读 9](evidence/workflow-repair-20260912/native-c-parent-3000-readonly9.png)、[原生导出 XML](evidence/workflow-repair-20260912/c-native-export.bpmn20.xml)。
- [D 消息胜出及取消说明](evidence/workflow-repair-20260912/native-d-cancel-explanation-visible.png)、[D 信号胜出](evidence/workflow-repair-20260912/native-d-signal-race-cancelled.png)、[E 正常组合完成](evidence/workflow-repair-20260912/native-e-normal-completed.png)。
- [F 草稿恢复](evidence/workflow-repair-20260912/native-f-restored-complex.png)、[认证检查](evidence/workflow-repair-20260912/native-auth-check.png)、[接收方消费](evidence/workflow-repair-20260912/native-f-receiver-consumed.png)、[发送方完成](evidence/workflow-repair-20260912/native-f-sender-completed.png)。
- [G 撤销恢复消息配置](evidence/workflow-repair-20260912/native-g-undo-config-restored.png)、[重做信号](evidence/workflow-repair-20260912/native-g-redo-signal.png)、[最终刷新保留信号](evidence/workflow-repair-20260912/native-g-formal-refresh-retained.png)、[独立消息接收完成](evidence/workflow-repair-20260912/native-g-message-receiver-completed.png)。
- [退回详情修复](evidence/workflow-repair-20260912/native-lifecycle-returned-fixed.png)。原生截图保留实际桌面，顶部音乐字幕是用户桌面叠层。

## 4. 可靠投递及生命周期证据

| 场景 | messageId | 真实结果 |
| --- | --- | --- |
| F 正常 | `06d8b8af-be26-37c2-b8d1-8bbaf60de43d` | outbox/入站各一次 PROCESSED |
| F 接收者迟到 | `fa0680a3-b21e-3fee-a24b-993547fc07ae` | 入站 `INSTANCE_NOT_FOUND` 重试，随后第 5 次消费成功 |
| F 认证失效 | `b2a47606-7c67-32d0-8f52-8602000c505d` | outbox `REMOTE_REJECTED`，当时无入站；凭据轮换恢复后第 5 次成功 |
| F 端点停用 | `947e0660-706d-3f1c-8e9c-852378f7b3c5` | 停用期间重试且无入站，恢复后第 6 次发送、第 1 次入站成功 |
| G 原组合关联发送 | `8567e1f1-5004-3926-8773-dd6fe79fb339` | 原 MessageFlow 对应接收池消费一次 |
| G 独立抛出 | `f5609a7c-47f9-3851-b996-46af0c3db3bf` | 独立点对点协议，两端一次成功 |

完整关联与分阶段状态见 [可靠消息记录](evidence/workflow-repair-20260912/f-delivery-evidence.jsonl)、[D 运行事件](evidence/workflow-repair-20260912/matrix-events.jsonl)、[A 并发办理记录](evidence/workflow-repair-20260912/a-evidence.jsonl)。认证探测只回显受控协议和随机 nonce，不触发业务消息。

通用生命周期实测：`d841de6f-ae78-11f1-bc40-005056c00001` 退回、重提、挂起后审批 409、恢复后完成；`1ab955d9-ae7b-11f1-a81e-005056c00001` 在下一任务未处理时撤回到原首审，再由管理员终止。移除临时身份的工作流角色并重新登录后，任务办理、模型读取、端点认证检查均 403。该身份后续为清理恢复原角色。具有管理员终止权限的发起人调用终止接口得到 `terminated`，未将其误记为普通发起人取消。

见 [生命周期记录](evidence/workflow-repair-20260912/lifecycle-evidence.jsonl)、[撤回/终止/RBAC](evidence/workflow-repair-20260912/final-boundaries.jsonl)、[范围拒绝](evidence/workflow-repair-20260912/scope-denied.json)、[端点停用定位](evidence/workflow-repair-20260912/endpoint-disabled-preflight.json)。

## 5. 分层验证结果

| 检查 | 本轮最终结果 | 证明边界 |
| --- | --- | --- |
| `mvn -B -pl ruoyi-admin -am test` | passed，398 项：flowable 385、admin 13；0 failed / 0 skipped；15:40:39 结束 | 包含真实 Flowable/H2、纯合同及 mock 依赖测试，不把 398 全称为完整系统测试 |
| `mvn -B -pl ruoyi-flowable -am -Pworkflow-mysql-it -Dtest=WorkflowModelDesignDraftBusinessTest,WorkflowManagedConnectorSecretsBusinessTest -Dsurefire.failIfNoSpecifiedTests=false verify` | passed，failsafe 22 项，0 failed / 0 skipped | 独立 MySQL，CAS/事务/消息/HTTP/保留策略；后续详情投影和诊断补丁另经全量 H2及开发库真实 API 验证 |
| `npm run test:contracts` | passed，15 项 | 前端领域合同 |
| `npm run test:e2e:designer` | passed，17 项，39.5 秒 | 真实 bpmn-js 与登录产品；包含双标签 CAS、不抢焦点、正式保存后不重建草稿、服务端注销模拟真实会话失效并重新登录恢复 |
| `npm run lint` | passed，0 errors，78 个既有 warnings | 未为本任务扩展无关 lint 清理 |
| `npm run build:prod` | passed | 最终前端产品代码；其后只增加会话回归测试 |
| `mvn -B -pl ruoyi-admin -am -DskipTests package` | passed | 最终后端构建；重启后核验带节点位置的端点诊断和受管凭据认证 |
| SQL 迁移 | passed | 两个新协议 SQL 在已有开发库、隔离测试库重复执行；新建空库安装这两个表后删除验证库 |
| 原生 A—G 关键路径 | passed，43 张必要截图 | 操作层次见台账；API 辅助办理、H2 异常路径单列 |

关键失败恢复和防伪并未用写 Flowable 状态或改 SQL 快照制造成功。C 的系统字段伪造、隐藏源、非法目标、子启动回滚、数据关联输入/输出独立变体，以及 E 的失败补偿继续由正式真实引擎测试承载。

## 6. 接口、迁移与部署说明

现有正式保存、部署、任务办理、`formDetail` 等调用方式保留；新增接口如下：

| 接口 | 合同 |
| --- | --- |
| `GET/PUT/DELETE /workflow/model/{modelId}/design-draft` | 本人模型草稿。PUT 为 `baseModelRevision / expectedRevision / bpmnXml`；DELETE 要求当前 `expectedRevision`，清空内容并提升墓碑修订 |
| `POST /workflow/model/preflight` | 沿用 `modelId / expectedRevision / bpmnXml`，返回摘要、有效性及逐元素诊断；不是可绕过最终检查的部署票据 |
| `GET/PUT/DELETE /workflow/connector/{endpointId}/credential` | GET 仅元数据；绑定与轮换需端点修订、凭据修订及输入密钥；DELETE 撤销后密文为 NULL |
| `POST /workflow/connector/{endpointId}/integration-credential` | 需端点编辑和集成账号新增两项权限；创建令牌、加密与绑定原子完成，不回读绑定的令牌正文 |
| `POST /workflow/connector/{endpointId}/check` | 分别报告配置就绪和受控消息认证结果；不能代替业务投递验收 |
| `POST /workflow/runtime-event/auth-check` | 仅受控集成令牌及 MESSAGE 权限可认证；固定协议 `APPROVA_MESSAGE_AUTH_V1` |
| `GET /workflow/process/observation` | 同一读取事务返回 `observedAt / detail / waiting`；页面丢弃过期请求结果 |

上线顺序：

1. 按现有业务迁移流程先执行 [8.0.2 草稿表](../../sql/flowable/business/8.0.2__workflow_model_design_draft.sql)、[8.0.3 受管密钥表](../../sql/flowable/business/8.0.3__workflow_managed_connector_secret.sql)。`CREATE TABLE IF NOT EXISTS` 的重复执行验证不等于任意旧表漂移自动修复；先核对实际表结构。
2. 为需要受管凭据的后端服务身份配置 `WORKFLOW_CONNECTOR_MASTER_KEY`：32 随机字节的 Base64，由运维秘密管理注入，不能写入 Git、BPMN、业务端点表或日志。所有执行该库任务的节点必须配置相同主密钥并有受控备份。缺失时 provider 明确不可用；旧 ENV provider 不受影响。
3. 发布本次兼容后端与前端；管理员创建/绑定凭据并执行认证检查，再用隔离业务流确认消费。正常凭据轮换通过产品 CAS 接口完成；主密钥不支持随意替换，不能把换环境变量当作存量密文迁移。
4. 数据关联及系统回填新能力通过新模型/新部署生效，既有部署和表单快照不可变。本轮没有批量改写存量实例。

回退：保留草稿表和密文备份，不以删表丢弃草稿。存在 `MANAGED_V1_` 绑定、系统回填快照或使用新执行协议的存量实例时，继续由兼容版本处理；先停止新引用并处理活动实例/绑定，再回退到已确认兼容的版本。没有验证新旧前后端任意混搭，发布应同步切换。

## 7. 清理与保留范围

通过正式 API 删除本轮 26 个已结束实例历史、11 个流程部署、14 个模型及其设计草稿、2 个 DMN 源部署、3 个测试用户和 1 个测试角色。无活动申请草稿。原生与 API 验收完成后，只有本轮故障恢复中已明确终止的两个实例为 terminated，其余实例已完成。

端点 3 已停用；受管凭据已撤销并清空密文；集成凭据 9、10 已撤销。端点、已提交申请、通知、可靠消息和操作审计的元数据按正式保留规则保留，不强删审计。详情见 [清理读回记录](evidence/workflow-repair-20260912/cleanup.jsonl)。清理只针对本轮精确 ID，既有表单、分类、用户数据及旧报告证据不纳入删除。

一次性登录令牌、测试密码、DPAPI 测试主密钥、原始日志、桌面输入脚本及一次性验收 XML 的专属临时目录和本轮 Playwright 临时输出已删除。正式测试代码、必要截图、脱敏结构与运行证据保留。数据库精确读回均为零，交付文件未发现本轮明文秘密；1512 个未修改的既有跟踪文件校验值与开始时一致。

本地后端已用最终 JAR 恢复正常 `druid,timers` 启动并通过健康读回，服务不再加载验收主密钥；受管凭据新配置需按上一节提供正式运维主密钥。已关闭本轮两个无痕窗口及普通窗口中的一个验收标签，恢复缩放 90%，保留原有浏览器标签。见 [最终验证](evidence/workflow-repair-20260912/verification.json)、[数据库清理](evidence/workflow-repair-20260912/cleanup-database-readback.json)、[本地服务状态](evidence/workflow-repair-20260912/final-local-state.json)。

## 8. 未执行与限制

- **not executed：** 多服务节点的主密钥一致性、跨节点轮换和故障接管；本次只证明单机后端重启后仍能解析、轮换及投递，不声明集群就绪。
- **not executed：** E 取消/失败补偿的原生页面路径和自然时间到期的失败重试；本轮该异常路径通过真实 Flowable/H2及 ManagementService 重试验证，不将其写成浏览器自动恢复通过。
- **not executed：** 全系统空库首次安装、生产迁移及回退演练、新旧前后端混搭；空库只验证新增协议 SQL。生产主密钥托管和备份需要部署环境提供。
- **状态表达限制：** 无可识别取消原因的历史活动显示 UNKNOWN；ANY 中被引擎删除的个别活动保留未知事实，不伪装为完成。原生活动实例明细和最终业务终态均保留在证据中。
- **数据图元边界：** C 的两条关联保留、可部署并与受控父子传参共存；数据存储图形不代表自动写数据库。

本报告记录本地可验证交付；上述未执行项不能计为通过，也不代表生产发布已经完成。
