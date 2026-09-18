# 业务链重构执行进度

起始基线 `63e54d482cd92b82fd9e6ac0ce84f803ea031f96`；提交记录是历史状态，当前验证范围以下方最近结果为准。C15 基线 `b025c2c`，实现完成后由 leader 独立 review、主审验证并精确本地提交，不推送。

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
| 11 | C09 抄送与通知 | BS-029、032；BS-022 通知部分 | 已提交 `7c5a303e42064c3c70824692b04c4e5db275acbc` |
| 12 | C05 状态与退回重提 | BS-023、026 | 已提交 `8a2d6f369da18ade0a2735c2812593532568191e` |
| 13 | C03 详情、等待与变量 | BS-027、028、049 | 已提交 `644f1e4566dc0ddf47137e57865a4b7fadd17041` |
| 14 | C06 工作台列表 | BS-043、046 | 已提交 `9b5c60e397d96f1211469e32bcc035079e0dadef` |
| 15 | C08 扩展执行 | BS-020 | 已提交 `0fc302303e524bf658e0272bd66459ad9a8a25f3` |
| 16 | C10 条件路由 | BS-024 | 已提交 `22d6777021a27c4fe4741f2e42a7a53f7a51829b` |
| 17 | C11 SLA 时钟 | BS-048 | 已提交 `b7a40ac6eabcd3b296a71a1b4f2f188107a9d1f3` |
| 18 | L03 代码生成详情 | BS-039 | 已提交 `5b2719fc67223b93dad95c63f077afbddd2b1175` |
| 19 | L05 部门／菜单树 | BS-042 | 已提交 `950c3957c87ab7002bde52e162a1c1c99dcd88c9` |
| 20 | L06 未读通知分页 | BS-047 | 已提交 `5ef2fe237800e4936e109c896945b71a07173e30` |
| 21 | L07 图标格式 | A-001 | 已提交 `b025c2c4a05d2a649ce50814f83316d7c00d2925` |
| 22 | C15 重构残留与交付膨胀 | 第 11 节 A/B/C 与配套整理 | 主审 review／复验通过，随本次提交交付 |

## C15 删除与保留判据

| 删除/调整 | 保留的实际失败判据与正式用例 |
| --- | --- |
| 部署展示复验、validateCompiledDeployment、COMPILED_DEPLOYMENT 及监听器/DMN 专用巡检 | LegacyRejection 保留非法作者拒绝与实际编译输出；DataObjectBusiness 保留真实部署、数据对象和 CallActivity 传值完成；ModelCompilation 保留保存→预检→部署→DMN/路由/子流程及写入失败回滚。Routing 的 deployedXmlReadsStoredResourceWithBoundedIoAndUtf8 检查真实展示、资源缺失、超限、UTF-8 和 I/O 错误；DetailIntegration 改为实际部署 XML 读取，保留对象授权/撤权判据。旧“读时缺内部监听器拒绝”行为废止。 |
| 条件运行摘要复算、重复规范化、生成网关/分支自证；删除 resourceJsonWhitespaceAndRuleKeyOrderDoNotInvalidateFrozenChecksum、corruptedLegacyCelStillFailsItsOriginalChecksum、missingBranchAssociationFailsClosed | 这些人工内部篡改用例对应已废止的完整性自证；不可解码资源和未知版本保留为 undecodableResourcesAndUnknownManifestFailClosed。RoutingIntegration/MySqlIT 继续用原始 v1 字节及真实 v2 部署证明精度、AND/OR 短路、默认流、排他歧义、包容多命中、重入、重启与事务回滚；摘要写入格式保留，历史资源不改写。 |
| 日历引用时丢弃日期索引、逐节点重读；运行重复工作周/时段关系审计 | CalendarIntegration/MySqlIT 的多节点作者保存/部署检查实际日历复用；日期覆盖仍供节点快照消费。实际 timer、UTC/DST、假日补班、停用/更新、通知失败重试、审计/任务回滚保留。重复工作周的人工破坏拒绝废止；不可解码字段、日历缺失、日期扫描界限和真实 SQL 故障仍拒绝。 |
| SysOrganizationScopeMySqlIT 的重复 SqlBoundary | 直接复用 SysConfigDictDeletionMySqlIT 的既有边界拦截器；两类独立竞争时序、数据范围、真实写失败及原子回滚用例全保留。SystemUniqueWrite 的写入/身份回调语义不同，未合并。 |
| 生成器跨表用例中的额外空表夹具；收件箱 SQL 排序字符串断言 | emptyLeftJoinNeverCreatesAColumnFromTheParentIdOrAudit 保留详情/目录/名称三个入口的空集合；跨表归属、列审计、编辑/模板与 MySQL information_schema 保留。收件箱真实页内容、顺序、隔离、状态/计数、分页边界与必要查询计量保留。H2/MySQL 子类本已共享实现，不以执行两次为由删覆盖。 |

模型编译、路由、SLA 的引擎装配分别需要 DMN/Spring、条件表达式、真实日历/通知 Mapper；保留各自生命周期，不增加通用测试框架。路由和 SLA 的初始作者资源改为不同源码，确保保存走正式引用校验；非法规则同时检查保存、预检和部署拒绝。新增测试文件 0。三份单次报告删除，有效内容归入 [架构](workflow-platform.md)、[业务契约](../contracts/workflow-behavior.md)、[组织契约](organization-data-scope-and-department-moves.md)及[贡献说明](../../CONTRIBUTING.md)。

## 最近有效验证边界

2026-09-18，实现任务最终默认命令 `mvn -pl ruoyi-admin -am clean verify -B -ntp` 通过；逐份 XML 与 testcase 核对为 98 份 / 688 例，失败、错误、跳过均为 0。

同日 10:05，leader 在主分支独立执行 `mvn -pl ruoyi-admin -am -Pworkflow-mysql-it verify -Dit.test=WorkflowConditionRoutingMySqlIT,WorkflowTaskSlaCalendarMySqlIT,SysConfigDictDeletionMySqlIT,SysOrganizationScopeMySqlIT,WorkflowNotificationInboxMySqlIT,GenTableDetailMySqlIT -B -ntp`，一次完整通过。使用独占 MySQL 8.4.9、正式十四份基线，连接与 JVM 时区对齐为 Asia/Shanghai，保留原时间断言。逐份 XML／testcase 核对：默认 98 份／688 例，MySQL 6 份／114 例（条件路由 25、SLA 22、参数/字典删除 20、组织范围 33、收件箱 5、生成器 9）；失败、错误、跳过均为 0。重复执行默认用例不另算覆盖。

Flowable 8 的 ContinueProcessOperation 在 UserTask 行为（含 create 监听器）后执行边界 timer 行为；台账创建时没有可用 timer 到期事实。保留台账一次日历解释计算两个点，随后各实际 timer 独立计算；方法调用、实际 SQL 与日期计算分别计量，不将固定四次调用作为业务契约。不增加缓存、上下文、调度器、制品版本或表结构。

实现任务另以既有真实 timer 参数用例完成 H2 7 例及 MySQL 7 例计量，均无失败、错误或跳过。三 timer 场景中，任务创建阶段为 4 次 Mapper 调用、2 次日历主表 SELECT 与 2 次日期覆盖 SELECT、4 次解析、5 次日期计算；后续查询可命中既有事务缓存。这些是当前场景的观测值。主审核对 20 个 v1 原始 JSON/BPMN 资源与基线字节一致，改动文档的 14 个本地链接有效。

未执行范围：浏览器、完整登录/HTTP 权限链、真实 Redis/邮件、异步线程持续调度及生产发布；计时测试使用真实引擎/作业和受控时钟，目录及无关通知协作者仍为替身。无前端修改，不重复前端构建。

## 净变化

按 Git 跟踪路径计行；测试目录内夹具、脚本和说明计入测试，两份原有未跟踪用户文档均未修改或纳入统计。下表每格为新增／删除／净值。

| 类别 | b025c2c → 本次提交 | 63e54d4 → 本次提交 |
| --- | --- | --- |
| 生产实现 | +88／-446／-358 | +2902／-5329／-2427 |
| 测试／夹具 | +64／-81／-17 | +10927／-613／+10314 |
| 文档 | +58／-556／-498 | +529／-4／+525 |
| SQL | 0／0／0 | +332／-10／+322 |
| 构建配置 | 0／0／0 | +73／0／+73 |
| 全仓 | +210／-1083／-873 | +14763／-5956／+8807 |

本项净删，整轮全仓净下降尚未达成。生产旧分支、重复测试装配和三份过期报告已清理，未删除独有业务判据来抵消累计增长。

## 必要迁移与恢复

- 有效账号/配置键唯一约束仍按[唯一写入契约](../database/system-unique-writes.md)停写、备份、清点和执行迁移；保留部分 DDL 失败恢复说明，不能新旧应用混跑。
- 既有流程状态按[状态切换与恢复](../database/workflow-business-status-cutover.md)离线迁移，常驻业务库迁移与发布未在本任务执行。
- manifest v1/v2 条件兼容及恢复条件见[业务契约](../contracts/workflow-behavior.md)。存在 v2 部署后不得单独回退到 C10 之前的读端或局部改写资源；保留原始兼容夹具。C15 不改变持久格式；回退到 b025c2c 会恢复旧读取自证和重复计算，不回写已存在任务到期点。
- 参数/字典提交后缓存恢复见[删除契约](../contracts/system-config-dict-deletion.md)。本任务只清理自有临时资源；用户原有两份未跟踪计划/审查文档只读保留，不纳入 Git。
