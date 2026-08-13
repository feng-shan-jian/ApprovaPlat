# 审批引擎 2026-08-11 至 2026-08-13 UI 测试报告

## 结论

本期最终复测已完整结束，但未达到发布验收通过条件。

- 最终批次：`20260813_ui_retest02`
- 执行时间：2026-08-13 13:48:47 至 14:49:14（Asia/Shanghai）
- 计划用例：110
- 通过：77
- 失败：25
- 阻塞：7
- 未执行：1
- 待定：0
- 已执行通过率：75.49%（77 / 102）
- 计划用例通过率：70.00%（77 / 110）
- 批次状态：`failed`；总控退出码为 1，原因是存在最终失败用例，不是批次中断

本报告以最终批次 `run-state.json` 的 110 项聚合状态为唯一计数口径。Playwright 的 `results.json` 和 `junit.xml` 在分阶段执行时只保留最后一个分片，因此不用于全量统计。原始 HTML、JSON、JUnit、数据库备份、trace、截图和日志属于本机运行副产物，汇总后已从工作区删除，不进入 Git 历史。

## 测试环境

| 项目 | 实际环境 |
| --- | --- |
| 浏览器 | Chromium，主视口 1440x960；布局补充视口 1366x768、1920x1080 |
| 前端 | Vue 3 / Vite，`127.0.0.1:1024` |
| 后端 | Spring Boot / Flowable，`127.0.0.1:8080` |
| 数据与基础服务 | 真实 MySQL、Redis；故障场景使用受控本机 SMTP、HTTP 和附件目录 |
| 自动化 | `@playwright/test 1.62.0`，单 worker，零自动重试 |
| 操作边界 | 业务写入通过真实 UI 和正式 API；SQL 仅用于只读核验和零副作用证明 |

测试结束后已确认：开发前端和后端恢复正常监听，后端恢复为 `--flowable.async-executor-activate=false`；故障服务端口 `2525`、`13306`、`16379`、`18081`、`18082` 均已释放。

## 通过用例

以下 77 项均在最终批次中标记为 `passed`。

| 功能域 | 数量 | 通过用例 | 已验证内容 |
| --- | ---: | --- | --- |
| 串行审批 | 2 | UI-APPROVAL-002、UI-APPROVAL-003 | 二级、三级串行审批的真实建模和运行 |
| 办理人来源 | 2 | UI-ASSIGN-002、UI-ASSIGN-003 | 候选用户、候选部门分配 |
| 附件 | 2 | UI-ATTACH-001、UI-ATTACH-003 | TEMP/DRAFT/BOUND、下载哈希、权限、草稿删除后的异步清理 |
| 认证 | 3 | UI-AUTH-001、UI-AUTH-002、UI-AUTH-003 | 错误登录、账号停用恢复、验证码门禁 |
| 高级 BPMN | 2 | UI-BPMN-002、UI-BPMN-003 | CallActivity 版本冻结与变量映射；ReceiveTask、Message、Signal 运行协议 |
| 扩展配置 | 3 | UI-CONFIG-001、UI-CONFIG-002、UI-CONFIG-003 | 扩展注册表、HTTP 端点、SQL 数据源的版本和启停 |
| 部署生命周期 | 2 | UI-DEPLOY-001、UI-DEPLOY-002 | 发布版本、挂起激活、运行/历史/草稿引用删除门禁 |
| 设计器 | 1 | UI-DESIGNER-001 | 导入、Lint、属性、导出、预览、偏好、模拟、保存和重开 |
| DMN | 3 | UI-DMN-001、UI-DMN-002、UI-DMN-003 | 校验部署、多版本冻结、任务转换后的属性清理和撤销重做 |
| 草稿 | 3 | UI-DRAFT-001、UI-DRAFT-002、UI-DRAFT-003 | 跨登录恢复、CAS、版本过期拒绝、并发提交唯一实例 |
| 事件配置 | 1 | UI-EVENT-001 | BPMN 事件编码与业务日历的新增、编辑和启停 |
| XLSX 导出 | 10 | UI-EXPORT-001 至 UI-EXPORT-010 | 分类、表单、模型、可发起流程、我的流程、实例、待办、待签、已办、抄送导出 |
| 故障恢复 | 8 | UI-FAULT-004、UI-FAULT-005、UI-FAULT-011 至 UI-FAULT-016 | SMTP 拒绝/超时补偿；离线、超时、双击、会话失效、刷新、后退和陈旧任务 |
| 表单数据 | 1 | UI-FORM-001 | 必填、金额、日期、枚举校验和持久化 |
| 集成凭据 | 2 | UI-INTEGRATION-001、UI-INTEGRATION-002 | 创建、轮换、脱敏、吊销及运行事件限流 |
| 布局 | 1 | UI-LAYOUT-001 | 三种桌面视口的关键页面可见和可操作性 |
| 审批生命周期 | 7 | UI-LIFECYCLE-001、UI-LIFECYCLE-002、UI-LIFECYCLE-004 至 UI-LIFECYCLE-008 | 认领、驳回、挂起恢复、终止删除、委派、转办、退回重提、撤回 |
| 多实例 | 7 | UI-MI-001 至 UI-MI-007 | ALL/ANY、动态选人、加签减签、角色/部门展开、revision 冲突 |
| 通知 | 3 | UI-NOTIFY-001、UI-NOTIFY-003、UI-NOTIFY-004 | 通知策略、任务到达站内信、已读、催办与重复催办拒绝 |
| 节点字段权限 | 2 | UI-PERM-001、UI-PERM-002 | 隐藏/只读/可写/必填以及并行旧快照字段合并 |
| RBAC | 5 | UI-RBAC-001 至 UI-RBAC-005 | 五职责角色、21 个页面和 75 个按钮权限 |
| 条件路由 | 6 | UI-ROUTE-001 至 UI-ROUTE-005、UI-ROUTE-007 | 排他、包容、并行、事件网关、安全失败与类型异常回滚 |
| SLA | 1 | UI-SLA-002 | 挂起冻结计时、恢复平移和继续提醒 |
| **合计** | **77** |  |  |

## 失败用例

以下 25 项在最终批次中真实执行并标记为 `failed`。其中 22 项稳定指向产品缺陷，1 项归因待复核，1 项指向测试同步问题，1 项指向测试重启脚本问题；两项测试侧失败和一项待复核失败均不能作为对应产品功能通过证据。

| 归因 | 严重度 | 失败用例 | 最终现象 |
| --- | --- | --- | --- |
| 待复核 | 未定 | UI-APPROVAL-001 | 真实设计器流程中未观察到“流程模型保存成功”，尚不能区分产品保存失败与页面同步问题 |
| 后端 | P2 | UI-ASSIGN-001、UI-ASSIGN-004、UI-ASSIGN-005、UI-ASSIGN-006 | 已完成任务的历史办理人为空，固定用户、直属上级、部门负责人及表单用户字段链路均受影响 |
| 后端 | P2 | UI-ATTACH-002、UI-FAULT-003 | multipart 失败或删除 TEMP 附件后，上传不同文件仍被重复提交门禁误拒绝 |
| 前端 | P2 | UI-FORM-002 | 计数器的最小值、最大值和步长配置入口不可见 |
| 前端 | P2 | UI-FORM-003 | 文本最大长度被保存为 JSON 字符串，导致模型校验部署失败 |
| 前端 | P1 | UI-ROUTE-006 | 已绑定正式表单后，受控整改循环仍无法选择判断字段 |
| 前端 / RBAC | P2 | UI-NOTIFY-002 | 通知偏好入口依赖系统中不存在的权限码，正常授权用户不可达 |
| 前端 / RBAC | P2 | UI-SLA-003 | SLA 通知专用权限没有真实可达页面入口 |
| 前端 | P1 | UI-BPMN-004、UI-BPMN-005、UI-BPMN-006 | Error、Escalation 引用及 Timer 表达式在作者模型中丢失，合法边界事件无法保存部署 |
| 前端 | P1 | UI-COLLAB-000 | Participant 的流程引用被写成 `processRef="undefined"` |
| 前端 | P1 | UI-FAULT-007 | HTTP 受控处理器选择被异步属性同步清空，无法建立 HTTP ServiceTask 运行前置 |
| 后端 | P1 | UI-SLA-001 | SLA 升级人工任务完成后执行状态和 COMPLETE 审计未收口 |
| 后端 | P1 | UI-BPMN-001、UI-BPMN-007、UI-BPMN-008 | 容器内部 StartEvent 被误计为流程级开始事件，子流程、事件子流程和事务模型无法进入运行态 |
| 后端 | P1 | UI-FAULT-001 | MySQL 不可用时页面泄露数据库驱动异常、SQL、Mapper 和本机路径 |
| 后端 / 前端联动 | P2 | UI-FAULT-002 | Redis 不可用被误报为真实会话过期，错误要求用户重新登录 |
| 测试同步 | 不适用 | UI-LIFECYCLE-003 | 页面导航后才读取响应体，Chromium 已释放响应，触发 `Network.getResponseBody` 错误 |
| 测试基础设施 | 不适用 | UI-FAULT-006 | 通知租约场景调用后端重启脚本时 PowerShell 返回退出码 1，本批次未取得可判定的产品结果 |
| **合计** |  | **25** |  |

## 阻塞用例

以下 7 项没有绕过产品前置，按依赖关系标记为 `blocked`。

| 阻塞原因 | 阻塞用例 | 影响 |
| --- | --- | --- |
| Participant 无法通过 UI 保存合法 `processRef` | UI-COLLAB-001、UI-COLLAB-002、UI-COLLAB-003、UI-COLLAB-004 | 多池顺序、幂等、失败重试/死信补偿及源实例取消无法建立真实运行前置 |
| HTTP 受控处理器选择被清空 | UI-FAULT-008、UI-FAULT-009、UI-FAULT-010 | HTTP 超时、断连和重复响应无法建立真实运行前置 |
| **合计** | **7** |  |

## 未执行用例

| 用例 | 状态 | 原因 |
| --- | --- | --- |
| UI-NOTIFY-005 | `not executed` | 当前环境未配置真实短信通道；按测试规范不得用 mock 冒充真实短信成功投递 |

## 补充验证

| 验证项 | 结果 |
| --- | --- |
| 前端 contracts | 84 passed |
| UI JavaScript 语法检查 | 72 files passed |
| `WorkflowBpmnServiceTest` | 40 passed |
| `WorkflowProcessStatusNormalizerTest` | 5 passed |
| `WorkflowMultiInstanceHandlerTest` | 10 passed |
| `WorkflowMultiInstanceIT` | `blocked`：缺少 `FLOWABLE_IT_JDBC_URL`，未使用假数据库绕过真实集成测试门禁 |

## 发布判断与后续入口

当前结论为不通过。P1 缺陷涉及受控循环、高级 BPMN、协作、HTTP 任务、SLA 状态一致性、容器开始事件识别及数据库异常信息泄露，应在进入发布候选前修复并对相关失败和阻塞用例定向复测。测试同步和重启脚本问题也需修正后重跑对应用例，不能把本期失败直接归为产品通过。

复测入口：

```powershell
pwsh -NoProfile -File .\deployment\scripts\run-ui-tests.ps1 -Phase all -RunId <new-run-id>
```

复测后仍应以新批次 `run-state.json` 聚合计数，并继续保持运行输出不进入 Git 历史。
