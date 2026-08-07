# BPMN 设计器与 Flowable 8 能力台账

## 状态定义

| 状态 | 含义 |
| --- | --- |
| `implemented` | 已接入真实前端、后端、持久化和运行链路，并有自动化证据。 |
| `round-trip-only` | 允许导入、编辑和导出，但 Flowable 8 不提供可执行模型；服务端校验明确禁止部署。 |
| `blocked` | 代码与本机门禁已完成，但需要目标生产基础设施、审批或授权，当前不能宣称生产完成。 |
| `excluded` | 明确排除的开发调试、赞助、商业或未开源能力。 |

## Phase 1：建模工作区

| 能力 | 状态 | 真实实现与证据 |
| --- | --- | --- |
| 桌面工具栏、Palette、ContextPad、画布、属性栏 | `implemented` | bpmn-js Modeler 与自有桌面布局；1440×900、1920×1080 截图无重叠。 |
| BPMN/XML 导入、XML/BPMN/SVG 导出 | `implemented` | 2 MiB 文件门禁、失败不覆盖画布、作者 XML 保存与回显、下载正文检查。 |
| XML/JSON 预览、清空、撤销/重做、缩放、居中 | `implemented` | XML 原文和 DOM 结构 JSON；Modeler 命令栈与清空确认。 |
| 对齐、等距分布、网格与吸附、小地图 | `implemented` | diagram-js 命令与 minimap 真实开关，偏好刷新回显。 |
| 浅色、深色、跟随系统主题、快捷键 | `implemented` | 设计器局部主题、Ctrl/Cmd+S 服务端门禁和真实刷新验证。 |
| BPMN Lint、Token 流程模拟 | `implemented` | 自有规则包、断连节点问题、真实模拟切换和偏好持久化。 |
| 设计器偏好持久化 | `implemented` | `wf_designer_preference` 当前用户隔离 GET/PUT 事务 upsert。 |
| 服务端显式校验 | `implemented` | `POST /workflow/model/validate` 与保存/部署复用同一 BPMN、表单、权限门禁。 |

Phase 1 证据：前端契约 33/33、生产构建 3116 modules、完整 Playwright 23/23；第二轮后端清洁门禁 958 项、0 失败、0 错误、0 跳过。

## Phase 2：元素与属性

| 能力 | 状态 | 真实实现与约束 |
| --- | --- | --- |
| 用户、手工、服务、接收、发送、业务规则任务 | `implemented` | Palette、属性面板和 Flowable 运行分别覆盖；BusinessRuleTask 只引用冻结 DMN。 |
| 顺序流、排他/并行/包容/事件网关 | `implemented` | Flowable 8 真实网关和事件网关集成测试通过。 |
| 开始/结束、中间消息/信号/定时器、错误、升级、补偿、边界事件 | `implemented` | 事件定义编辑、消息/信号/Timer/补偿真实执行；不支持的组合由服务端报告。 |
| 子流程、事件子流程、事务 | `implemented` | 子流程、事务取消与补偿、非中断事件子流程真实 Flowable 测试通过。 |
| 调用活动 | `implemented` | 精确子定义引用、部署冻结和被引用部署删除保护真实 MySQL 测试通过。 |
| 池、泳道、消息流、关联、分组、注释、数据对象/存储 | `implemented` | 高级 Palette、显式泳道命令及 XML 往返；非执行图形不伪造运行效果。 |
| ComplexGateway | `round-trip-only` | 可导入、编辑、导出；校验返回 `BPMN_ELEMENT_NOT_EXECUTABLE` 并禁止部署。 |
| 基础信息、版本、说明、条件、办理人、时间、优先级、跳过条件 | `implemented` | 属性面板与身份目录受服务端职责和对象授权约束。 |
| 循环与多实例 | `implemented` | 所有活动支持静态串行/并行多实例 XML；UserTask 支持受控动态 ANY/ALL，Flowable 真实并发、竞态和审计通过。 |
| 标准循环 | `round-trip-only` | 可编辑和导出；Flowable 8 无可执行模型，保存/部署校验明确禁止部署。 |
| 异步作业、监听器、扩展属性 | `implemented` | 异步前后、排他作业、补偿、固定三条系统审计监听器、受控业务监听器和最多 32 个扩展属性。 |
| 正式模板与内嵌 FormData | `implemented` | `wf_form` 与 BPMN FormData 互斥编辑，统一渲染协议并冻结 `wf_deploy_form` 快照。 |

Phase 2 证据：`WorkflowAdvancedBpmnMySqlIT` 4/4、调用活动 1/1、多实例 8/8、运行事件 8/8、发送任务 1/1、嵌入表单/监听器集成通过；前端真实设计器场景 1/1。

## Phase 3：扩展、连接器与 DMN

| 能力 | 状态 | 真实实现与约束 |
| --- | --- | --- |
| 扩展及版本注册表 | `implemented` | `wf_bpmn_extension`、`wf_bpmn_extension_version`、`wf_deploy_extension_snapshot`；部署冻结精确版本、配置和校验和。 |
| 已安装 Java 处理器 | `implemented` | 固定代码注册表，拒绝任意类名/Bean；版本发布和引用删除保护通过。 |
| CEL | `implemented` | 类型化白名单变量、确定性结果、无反射/文件/网络/进程/Bean；运行前复核快照。 |
| HTTP 连接器 | `implemented` | 端点白名单、外部密钥引用、稳定幂等键、异步重试、调用台账和 dead-letter。 |
| SQL 同库连接器 | `implemented` | JSqlParser 单条命名参数模板、受控表、Flowable 同库事务、提交/回滚真实验证。 |
| SQL 外库写入 | `implemented` | 外库至少一次、稳定幂等键、提交故障后重试且外部效果不重复；真实 MySQL 外库集成通过。 |
| Flowable DMN | `implemented` | 官方 DMN Engine；BusinessRuleTask 精确绑定，父部署冻结 DMN 子部署和快照。 |
| 自定义表单字段 | `implemented` | 受控字段注册表、Schema、渲染器和版本冻结；非法配置零副作用。 |
| 其他引擎私有扩展 | `round-trip-only` | 可导入/保存/导出并返回兼容报告 `BPMN_PRIVATE_EXTENSION_NOT_COMPATIBLE`，禁止部署。 |

证据：扩展/CEL/HTTP/SQL/DMN/字段服务和 Mapper 契约全通过；`WorkflowExternalSqlConnectorMySqlIT` 1/1、SQL 同库 2/2、DMN 1/1、扩展注册表真实页面 1/1。

## Phase 4：运行接口、凭据与 executor

| 能力 | 状态 | 真实实现与约束 |
| --- | --- | --- |
| 消息、信号、ReceiveTask 运行接口 | `implemented` | requestId、事件名、实例/业务键、白名单变量、歧义 409、幂等回放和零副作用拒绝。 |
| 集成凭据 | `implemented` | `wf_integration_credential` 只保存 Token 哈希/前缀，支持范围、到期、轮换、吊销、限流和审计。 |
| 运行事件请求台账 | `implemented` | `wf_runtime_event_request` 记录请求、匹配、处理结果、状态与幂等冲突。 |
| Timer、异步延续、重试 | `implemented` | 真实 Timer 等待、异步执行、重试和 dead-letter；Flowable Engine 27 项通过。 |
| 重启续跑、挂起/恢复、重复领取 | `implemented` | JSON 重启续跑、Timer 挂起恢复、并发认领和终态竞争真实验证。 |
| 系统/业务监听器与审计 | `implemented` | 三条系统任务审计监听器自动维护，业务监听器只能选择注册表，三份不可变快照真实执行。 |

## Phase 5：验收与发布边界

| 门禁 | 状态 | 证据/边界 |
| --- | --- | --- |
| 后端 Maven 全量清洁门禁 | `implemented` | 第二轮 `mvn clean verify -Pflowable-it` 共 958 项，0 失败、0 错误、0 跳过；业务集成 74 项全通过。 |
| 前端契约、生产构建、真实浏览器 | `implemented` | 契约 33/33、构建 3116 modules、Playwright 23/23、零重试、报告保密门禁通过。 |
| 五角色 HTTP/RBAC | `implemented` | 16 Controller、103 mapping、515 角色/入口单元及页面直接 URL 矩阵真实通过，拒绝零副作用。 |
| 数据库/Redis/Flowable 对账 | `implemented` | E2E 清理后测试前缀模型、部署、实例、扩展、端点和调用台账均为 0。 |
| 本机容量、备份恢复、应用回滚彩排 | `implemented` | k6 5 VU/20 迭代，420/420 checks、140/140 业务成功、HTTP 失败 0%、P95 81.65ms、P99 87.24ms；二进制安全 dump/restore 后 84/84 表及核心行数一致，恢复库 JAR 启动和 HTTP 200 通过；发布/回滚门禁脚本 102/102。 |
| 多节点生产拓扑、外部生产基础设施 | `blocked` | 当前机器没有目标集群、共享附件卷、生产监控和外部副作用环境，不能伪造生产证据。 |
| 生产授权、发布批准、24/72 小时长稳观察 | `blocked` | 需要业务负责人、复核人、发布窗口和真实生产观察，开发阶段不宣称上线。 |

## 明确排除

实现模式切换、EventBus 原始调试列表、赞助内容、纯示例模块和未开源商业 bpmport 能力均为 `excluded`；当前项目依法要求的第三方许可证/NOTICE 不因参考仓库归属声明而删除。

## 完成判定

开发阶段的前端、Flowable 8 执行、数据、权限、审计、异常和本机验收能力已对齐；仅生产拓扑、授权和长稳观察保持 `blocked`。任何发布说明都必须沿用本台账边界，不得把开发门禁通过写成生产上线。
