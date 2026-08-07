# 工作流测试与验收

## 验收层级

工作流验收按以下层级执行，后一级不能被前一级替代：

1. 编译和静态契约：确认源码、SQL、菜单、权限矩阵和配置没有结构漂移。
2. 真实数据库：在隔离 MySQL 中从空 schema 安装 90 表（若依 20、Quartz 11、Flowable 36、ApprovaPlat `wf_*` 23），执行约束、备份恢复和三组共 37 项只读验收。
3. 真实服务 API：启动 Spring Boot、MySQL、Redis 和附件目录，通过真实登录与 HTTP 调用验证状态和副作用。
4. 浏览器 E2E：通过真实页面执行发起、审批动作、工作台、导出、附件和权限可见性。
5. 并发与故障：验证重复提交、竞态、事务回滚、连接器失败、executor、清理锁和存储异常。
6. 非功能与发布：验证性能、容量、多节点、长稳、监控、告警、备份、彩排和 24/72 小时观察。

任何未执行层级必须明确标记为 `not executed`，不能用构建成功、静态搜索或门禁 fixture 宣称真实业务已通过。

## 数据库契约测试

数据库基线相关测试位于 `back/ruoyi-flowable/src/test/java/com/ruoyi/flowable/mapper`，覆盖：

- 正式业务 DDL、附件、模型保存幂等和设计器偏好
- 扩展、连接器、DMN 和运行事件结构
- 菜单数量、树结构、职责角色和只读验收 SQL

定向执行：

```powershell
cd back
mvn -pl ruoyi-flowable -am `
  "-Dtest=WorkflowBusinessDdlContractTest,WorkflowAttachmentContractTest,WorkflowModelSaveDdlContractTest,WorkflowDesignerPreferenceDdlContractTest,WorkflowExtensionDdlContractTest,WorkflowMenuSqlContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

## RBAC 矩阵

`WorkflowRbacMatrixContractTest` 冻结 17 个登录用户 Controller、109 个入口和五角色 545 个权限单元。机器调用的 4 个运行事件入口使用独立集成 Token 测试，不计入登录用户矩阵。

## P1 多池协作验收

- 从设计器创建两个可执行 Participant，使用绑定 `COLLABORATION_OUTBOX_V1` 的 SendTask 和目标 Message Catch/ReceiveTask 建立 MessageFlow；保存、部署和重新打开后配置一致。
- 启动发送方和接收方实例，确认 SendTask 与 `wf_collaboration_outbox` 在同一事务提交；事务回滚时两者都不得留下部分结果。
- 使用冻结 HTTP 端点和环境密钥向真实协作 API 投递，核对出站、入站、通道游标、审计和 Flowable execution 状态一致。
- 同一关联键连续发送至少两条消息，注入重复、乱序、5xx、超时和 worker 重启；验证严格序号、幂等重放、指数退避、租约接管、死信和人工补偿。
- 使用流程管理员、审计角色和无关用户验证列表、审计、补偿和取消权限；拒绝请求不得修改 Flowable、outbox、入站台账或审计。
- 通过 Chromium 打开多池协作管理页，核对当前数据库状态、逐次审计、死信补偿及刷新后回显；浏览器缓存或本地状态不得成为事实来源。

静态矩阵执行：

```powershell
cd back
mvn -pl ruoyi-admin -am `
  "-Dtest=WorkflowRbacMatrixContractTest" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
```

真实 HTTP 集成测试必须使用隔离 MySQL、专用 Redis database、五个不同的非超级管理员账号和真实 `/login`。允许请求必须核对业务结果，拒绝请求必须核对传输状态、业务码以及数据库、Flowable、附件和审计零副作用。

## 发布门禁测试

在 WSL2 Ubuntu 中执行：

```bash
cd /mnt/d/ruoyiflowable
bash deployment/scripts/tests/workflow-release-gate-test.sh
```

该测试验证发布包完整性、SQL 顺序、配置红线、敏感信息、证据清单、安装、彩排、生产、回滚和观察反例。它使用受控 fixture 验证门禁逻辑，不等同于真实生产环境执行。

## 真实闭环判定

核心功能只有在页面、API、数据库、Flowable 状态、权限、审计和附件结果一致时才能关闭。写操作必须同时验证成功持久化和失败零副作用；导出必须解析实际文件内容；附件必须验证物理文件、元数据、授权和清理结果。

## P0-2 边界定时器与审批 SLA 验收

该能力与错误边界、升级边界共用 P0-2 关闭门禁。以下检查必须在真实服务、真实异步执行器和隔离 MySQL 上执行；静态测试、XML round-trip 或 Flowable job 存在只能证明底层机制，不能替代业务验收。

- 规则与部署：从设计器配置活动 SLA、边界定时器、提醒节点、自动催办和升级路径；后端拒绝未绑定日历、非法时长、越权接收人、未定义升级目标或不安全边界范围；部署后核对版本快照和校验和。
- 日历判定：验证工作日、周末、节假日、工作时段、跨时区、节假日覆盖和截止时间恰逢边界时刻的计算；数据库中的 `due_at`、日历游标和页面显示必须一致。
- 真实执行器：启动真实异步执行器，等待 timer/job 到期，核对提醒、催办、升级产生的任务/通知、Flowable 活动和正式 SLA 台账；禁止用手工调用服务方法代替。
- 暂停与恢复：分别在任务、实例挂起以及撤回、终止、驳回、补偿期间检查计时暂停/取消；恢复后按剩余时长继续，不能重复触发或跳过日历时段。
- 幂等与并发：并发运行多个 executor/重复轮询、节点重启和通知重试，确认同一 SLA 节点只有一个业务动作、一个审计结果和稳定的重试次数；重复请求返回可识别的幂等结果。
- 通知失败与回滚：让通知提供方返回超时、5xx 和部分失败，确认业务事务不留下“已通知”假状态，失败进入正式重试/死信或补偿记录；人工重试后状态、审计和页面一致。
- 权限隔离：用管理员、设计者、发起人、办理人、审计只读和无关用户分别验证 SLA 查看、修改、催办、升级、重试和审计权限；拒绝请求必须核对 HTTP 状态、业务码及数据库、Flowable、通知和审计零副作用。
- 浏览器验收：通过真实页面配置并发布 SLA，执行挂起/恢复、提醒、催办、升级和失败重试；刷新页面后显示必须来自 API/数据库当前状态，而非倒计时或本地缓存。
- 回滚与恢复：在通知、Flowable 命令、数据库提交和异步租约任一阶段注入失败，验证事务回滚或持久化补偿；重启服务后未完成动作可被安全接管，且不会重复升级。

如当前环境未提供隔离 MySQL、异步执行器、通知测试端点或浏览器入口，相关项目必须标记为 `not executed`，不得依据已有单元测试或构建结果宣称 P0-2 完成。
