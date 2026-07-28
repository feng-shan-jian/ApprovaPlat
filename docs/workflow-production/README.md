# Flowable 8 审批平台生产集成

## 目标

本目录用于指导 ApprovaPlat 在 Java 17、Spring Boot 4、Vue 3 和 Flowable 8 技术栈上完成审批平台建设。参考工程
`D:\ruoyioldflowable\RuoYi-Flowable-Plus-0.8.X` 只用于识别功能、交互和 BPMN 约定；目标实现以当前工程的安全、事务、数据和运维标准为准。

完成标准不是“页面存在”或“接口返回成功”，而是每项能力都具备真实入口、真实 API、真实权限、真实状态流转、正式数据持久化、审计追踪、异常处理和可重复验收证据。

## 文档索引

1. [01-technical-solution.md](01-technical-solution.md)：生产架构、领域边界、数据、安全和运维设计。
2. [02-feature-mapping.md](02-feature-mapping.md)：参考功能与目标实现的逐项对照。
3. [03-execution-plan.md](03-execution-plan.md)：可直接执行的阶段任务、门禁和完成定义。
4. [04-fresh-install-runbook.md](04-fresh-install-runbook.md)：MySQL、Redis、应用账号和 69 表基线初始化。
5. [05-release-rollback-runbook.md](05-release-rollback-runbook.md)：发布彩排、生产发布、观察和版本回滚。
6. [../../back/sql/flowable/README.md](../../back/sql/flowable/README.md)：Flowable 8 正式 SQL 与只读验收顺序。
7. [06-compatibility-contract.md](06-compatibility-contract.md)：动态多实例、监听器、按 key 发起和复杂执行树的冻结业务契约。
8. [07-rbac-http-test.md](07-rbac-http-test.md)：五角色直接 HTTP 权限、对象授权、非法状态与零副作用矩阵。
9. [08-runtime-readiness.md](08-runtime-readiness.md)：生产拓扑、共享存储、清理锁、健康、指标、告警和外部门禁。

## 当前状态

| 阶段 | 状态 | 当前结论 |
| --- | --- | --- |
| P0 功能范围与验收矩阵 | `in_progress` | 功能数量与复杂兼容业务契约已冻结；容量/SLO、生产拓扑、证据责任人与发布窗口仍待外部批准。 |
| P1 Flowable 8 引擎基线 | `completed` | Flowable 8.0.0、共享事务、Jackson 2 变量、timer、六类 job、重启与回滚验证通过。 |
| P2 后端业务闭环 | `in_progress` | 动态多实例 8/8、生命周期审计契约 4/4 及复杂执行树真实引擎 IT 已通过；70 个入口的全部非法状态、异常和扩展竞态组合仍需闭合。 |
| P3 Vue 3 业务闭环 | `in_progress` | 候选版本 6 个 Chromium spec 已一次性 20/20 通过，覆盖核心动作、七工作台、十导出、附件和五角色页面权限；P3-03 要求连续两次通过，当前仅完成第一次。 |
| P4 综合质量验收 | `in_progress` | 五角色 350/350 真实 HTTP 矩阵、候选 JAR 与前端生产构建及一次完整 Chromium 候选回归已通过；扩展并发、故障注入、权限强化和安全审计当前 `not executed`。 |
| P5 生产非功能与运维 | `not executed` | 已有启动门禁、运行快照、清理锁和共享卷探针代码；性能、多节点、告警、备份恢复和 72 小时长稳当前未执行。 |
| P6 发布与观察 | `not executed` | 已冻结双彩排与回滚契约；两轮真实彩排、生产切换和 24/72 小时观察当前未执行。 |

## 已确认基线

- 后端：Java 17、Spring Boot 4.0.6、Flowable 8.0.0、MyBatis 4.0.1。
- 前端：Vue 3.5、Element Plus、Pinia、Vite 6、bpmn-js 18.22.0。
- 数据库：若依 20 表 + Quartz 11 表 + Flowable 32 表 + `wf_*` 6 表，共 69 张正式表。
- 权限：2 个目录、11 个页面、40 个按钮，共 53 条菜单记录；5 个职责分离角色。
- 后端入口：9 个工作流 Controller、70 个方法级入口，全部具备 `@PreAuthorize`。
- 前端入口：9 个工作流 API 文件、60 个真实请求函数、14 个 Vue 页面。
- 公共组件：`ProcessDesigner`、`ProcessViewer`、`ProcessFormRenderer`、`WorkflowAttachmentUpload`、`WorkflowProcessList` 均有同名文档。
- 自动测试：候选 Surefire 报告为 `ruoyi-flowable` 607 项（0 失败、0 错误、1 项 Windows 条件跳过）和 `ruoyi-admin` 98/98。Failsafe 首次集中执行发现 5 项问题；修复后按受影响范围定向通过生命周期审计 4/4、RBAC IT 6/6，动态多实例 8/8、附件 4/4、业务 schema 5/5、兼容契约 5/5 报告保持通过。本候选修复后未重复运行完整 Maven/Failsafe。
- 权限验收：五角色对 70 个入口形成 350 个真实 HTTP 单元，166 个允许、184 个拒绝，`passed=350`、`failed=0`、`notExecuted=0`，fixture 清理后数据库、Flowable、附件和操作日志零残留副作用。
- 数据库验收：三组只读脚本共 22 项全部 `PASS`；隔离 schema 为 69 张正式表，模型版本唯一约束幂等且无重复版本组。
- 候选浏览器回归：2026-07-28 在 Chromium 单 worker、零重试配置下执行 6 个 spec，`20/20 passed`、`skipped=0`、`flaky=0`，总耗时 149.43 秒。
- 构建：候选 JAR 构建成功，测试按受影响定向报告复用并在打包阶段跳过；`npm run build:prod` 成功转换 2973 个模块。
- 候选数据清理：按本轮审计提取的 27 个资源 UUID 对账，模型、部署、定义、字节资源、运行/历史流程任务、变量、身份链、事件、job、`wf_deploy_form`、`wf_copy` 及活动表单/分类均为 0；仅保留表单与分类软删除墓碑各 5 条、相关操作审计 38 条，以及 1 条附件 `DELETED` 审计墓碑，附件物理文件不存在且无清理重试错误。
- 发布烟测：证据分别冻结传输层 HTTP 状态与 `AjaxResult.code`；拒绝动作固定为 `200/200` 与 `403/403`，文本或二进制原始来源均以 SHA-256 绑定证据清单。
- 既有发布门禁契约测试：97/97 通过，覆盖批准 commit/清单锚点、不可变回滚包、最小 MySQL 权限、Redis AOF/noeviction、Actuator/Prometheus 运行门禁，以及安装、恢复、彩排和观察证据的静态/反例约束；该结果不表示真实备份恢复、生产彩排或 24/72 小时观察已经执行。

## 生产红线

- `flowable.database-schema-update` 固定为 `"false"`。
- 应用数据库账号只获得目标 schema 的最小 DML 权限，DDL 与验收账号分离。
- 所有凭据只通过本地忽略文件或受控环境变量注入。
- URL 权限不能替代流程实例、任务、附件等对象授权。
- 跨 `wf_*` 和 `ACT_*` 的业务写入必须处于同一 Spring 事务。
- 不允许使用 Mock、浏览器本地存储、临时 JSON 或内存状态形成业务闭环。
- executor 只能在结构、权限、容量和监控门禁通过后按批准拓扑启用。
- 任何未完成的 E2E、性能、长稳、故障或发布门禁都必须保持 `in_progress`、`pending` 或 `not executed`。

## 当前最短执行路径

1. 当前业务候选代码与一次完整 Chromium 回归证据已冻结，本轮不再重复运行全量回归。
2. P3-03 连续两次通过门禁仍缺第二次独立执行，因此 P3 保持 `in_progress`，不得用本次一次通过直接关闭阶段。
3. 后续业务缺陷仍按“受影响编译/定向测试 + 一条真实 API 或浏览器主链”验证，不新增重复测试框架。
4. P4 扩展并发、故障注入、权限强化，以及 P5/P6 的性能、监控、备份恢复、彩排和 72 小时观察全部保持 `not executed`；未通过前不得宣称 P0-P6 生产集成完成。
