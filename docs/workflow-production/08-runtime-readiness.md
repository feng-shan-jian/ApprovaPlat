# 工作流生产运行就绪与监控

## 1. 适用范围

本文冻结 P5 运行时门禁、executor 拓扑、附件持久卷、清理互斥、Actuator、Prometheus
指标和外部验收边界。代码级门禁只能阻止已知错误配置，不能替代真实容量测试、多节点验收、
告警送达、备份恢复或 72 小时长稳。

生产覆盖配置位于 `deployment/config/application.yml`，变量模板位于
`deployment/config/ruoyi.env.example`。生产必须使用受限环境文件注入真实值，禁止把密码、
Token、Cookie 或 MySQL 客户端文件写入发布包和证据目录。

## 2. 强制配置

| 变量 | 作用 | 生产约束 |
| --- | --- | --- |
| `FLOWABLE_RUNTIME_DEPLOYMENT_TOPOLOGY` | 应用拓扑 | 仅允许 `SINGLE_NODE` 或 `MULTI_NODE` |
| `FLOWABLE_RUNTIME_EXECUTOR_TOPOLOGY` | executor 拓扑 | 初始为 `DISABLED`；启用必须与真实 executor 开关和批准拓扑一致 |
| `FLOWABLE_RUNTIME_NODE_ID` | 稳定节点标识 | executor 启用或多节点时必填，3 至 64 位稳定 ASCII |
| `FLOWABLE_RUNTIME_APPROVAL_REFERENCE` | executor/多节点审批引用 | executor 启用或多节点时必填，禁止占位值 |
| `FLOWABLE_RUNTIME_CAPACITY_APPROVAL_REFERENCE` | 容量评审引用 | 生产始终必填，独立于拓扑审批 |
| `FLOWABLE_ASYNC_EXECUTOR_ACTIVATE` | 普通 executor 实际开关 | 必须与 executor 拓扑一致 |
| `FLOWABLE_ASYNC_HISTORY_EXECUTOR_ACTIVATE` | 历史 executor 实际开关 | 必须与 executor 拓扑一致 |
| `FLOWABLE_ATTACHMENT_STORAGE_MODE` | 附件卷类型 | 多节点必须为 `SHARED_FILESYSTEM` |
| `FLOWABLE_ATTACHMENT_STORAGE_ID` | 共享卷身份 | 共享模式必须与运维预置 `.storage-id` 完全一致 |
| `FLOWABLE_ATTACHMENT_CLEANUP_LOCK_MODE` | 清理互斥模式 | 多节点必须为 `MYSQL_ADVISORY` |
| `FLOWABLE_ATTACHMENT_CLEANUP_LOCK_NAME` | MySQL named lock 名 | 所有节点一致，1 至 64 位稳定 ASCII |
| `FLOWABLE_RUNTIME_METRICS_REFRESH_INITIAL_DELAY` | 首次采集等待 | 默认 `PT10S`，允许 0 至 10 分钟 |
| `FLOWABLE_RUNTIME_METRICS_REFRESH_INTERVAL` | 固定采集间隔 | 默认 `PT1M`，允许 30 秒至 10 分钟 |
| `FLOWABLE_RUNTIME_METRICS_SNAPSHOT_MAX_AGE` | readiness 最大快照年龄 | 默认 `PT3M`，必须严格大于采集间隔 |
| `RUOYI_MANAGEMENT_PORT` | 独立管理端口 | 默认 `18080`，只绑定 `127.0.0.1` |

附件 `max-size`、TEMP 数量/字节、全局未删除字节、磁盘低水位、清理批量和指数退避参数
必须来自本次容量评审。单用户 TEMP 字节不能小于单文件上限，全局容量不能小于单用户 TEMP
容量。

`flowable.runtime.production-gate-enabled` 在生产覆盖中固定为 `true`。任何 schema 自动升级、
executor 开关与拓扑漂移、多节点本地附件目录、共享卷标识缺失、清理锁非法、容量审批缺失或
快照时序倒置都会阻止应用启动。

## 3. executor 拓扑

首次生产启动保持两个 executor 关闭，`FLOWABLE_RUNTIME_EXECUTOR_TOPOLOGY=DISABLED`。
只有 P5-01 容量结果、节点数量、唯一执行方案、排空步骤和变更审批全部冻结后，才允许切换：

- 单节点只能使用 `SINGLE_NODE`，并填写稳定 node id 与真实审批引用。
- 多节点只能使用 `DATABASE_LOCKED_MULTI_NODE`；必须用真实 timer、async、history job 验证
  不重复执行、故障接管和排空恢复。
- readiness 会比较配置期望值与最近成功快照中的两个 executor 实际状态，任一漂移立即
  `DOWN`。

拓扑枚举和启动校验不证明集群已经通过验收。未经真实多节点故障、滚动重启和 72 小时长稳，
不得启用生产多节点 executor。

## 4. 附件持久卷探针

共享模式由运维在批准挂载点预置
`${RUOYI_PROFILE}/workflow-attachments/.storage-id`。应用先只读核对标识，再执行任何目录创建
或探针写入，且绝不自动创建或覆盖该文件。

启动探针执行以下真实链路：

1. 固定私有根身份，拒绝 symlink、junction 和特殊文件。
2. 创建并核验 `.tmp` 与当前 UTC `yyyy/MM/dd` 正式目录。
3. 在 `.tmp` 创建唯一普通文件，设置私有权限并写入固定非敏感正文。
4. 从 `.tmp` 移动到正式日期目录，以同一打开通道有界回读正文。
5. 分别删除 source 与 target；首个清理失败不阻止第二项清理，主操作异常不会被清理异常覆盖。
6. 复核目录身份和磁盘低水位，任一步失败都会阻止生产启动。

共享模式强制文件系统提供 `SecureDirectoryStream`，使用根、临时目录和正式目录句柄完成相对
操作，以抵抗 symlink、junction 和父目录 ABA；能力不足时生产启动失败。仅单节点本地卷允许
使用词法路径回退，并在写入、移动、回读和清理边界前后复核完整目录身份链。单节点探针只证明
当前节点的挂载能力；共享语义仍必须由节点 A 上传、节点 B 绑定/下载、节点 C 清理的真实测试
证明。

## 5. 清理互斥与降级

MySQL named lock 使用专用 JDBC 会话，正式附件清理在独立 `REQUIRES_NEW` 事务中执行。
内部事务完成提交或回滚后，才由同一专用会话执行 `RELEASE_LOCK`。

- `GET_LOCK=0` 是唯一可确定的竞争失败分支，不查询候选、不修改数据库、不触碰文件。
- `GET_LOCK=1` 才能进入正式清理事务。
- GET_LOCK 非 0/1、空结果、读取异常或 ResultSet/Statement 关闭异常均代表锁状态不确定；物理
  会话必须 `abort` 后再关闭。
- RELEASE_LOCK 非 1、读取异常或资源关闭异常同样必须 `abort`。
- 任一获取或释放不确定结果都会将本进程永久标记为 degraded，readiness 保持 `DOWN` 直至
  完成故障处置并重启进程；禁止通过后续一次成功清理自动清除。

物理删除 I/O 失败按数据库持久化的指数退避重试；Mapper、数据完整性、目录安全和程序异常会
回滚并中止整批，不能被吞成普通重试。真实 Druid 连接池和 MySQL 断网/半关闭故障仍需单独注入
验证会话确实被淘汰。

## 6. 健康端点

生产只在 `127.0.0.1:${RUOYI_MANAGEMENT_PORT}` 暴露 `health,prometheus`：

- liveness：仅 `livenessState,ping`，数据库、Redis、NFS 或业务积压不得触发进程重启循环。
- readiness：`readinessState,db,redis,workflowRuntime`，任一依赖不满足接流条件即摘流。
- Nginx 对 `/prod-api/actuator` 和 `/prod-api/actuator/` 显式返回 404。
- health `show-details=never`，公网业务端口不承载管理端点。

`workflowRuntime` 不直接访问数据库、文件系统或 Flowable executor，而是一次读取定时采集的
原子快照以及进程内锁降级状态。实际采集运行在唯一 daemon worker；Spring scheduler 只提交
任务，上一轮未结束时跳过，不形成无界队列。NFS hard mount 即使永久阻塞，也不会占住健康请求
或附件清理调度线程；快照超过最大年龄后 readiness 自动 `DOWN`。

节点内验收至少执行：

~~~bash
curl --fail --silent --show-error \
  "http://127.0.0.1:${RUOYI_MANAGEMENT_PORT}/actuator/health/liveness"
curl --fail --silent --show-error \
  "http://127.0.0.1:${RUOYI_MANAGEMENT_PORT}/actuator/health/readiness"
curl --fail --silent --show-error \
  "http://127.0.0.1:${RUOYI_MANAGEMENT_PORT}/actuator/prometheus" >/dev/null
test "$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "https://${RUOYI_SERVER_NAME}/prod-api/actuator/health")" = '404'
~~~

## 7. 指标与告警

固定低基数 Micrometer 指标如下：

| 指标 | 含义 |
| --- | --- |
| `workflow.process.instances.active` | `SUSPENSION_STATE_=1` 的根流程实例数 |
| `workflow.tasks.active` | `SUSPENSION_STATE_=1` 的活动任务数 |
| `workflow.jobs{type=...}` | executable、timer、suspended、deadletter、external_worker、history 六类队列 |
| `workflow.executor.active{type=...}` | 普通和历史 executor 实际状态 |
| `workflow.attachments{status=...}` | TEMP、BOUND、EXPIRED、DELETED 正式记录数 |
| `workflow.attachment.storage.bytes{state=...}` | 未物理删除登记字节与挂载点可用字节 |
| `workflow.attachment.cleanup.pending` | 终态但尚未物理删除记录数 |
| `workflow.attachment.cleanup.deferred` | 尚未到退避时间的清理记录数 |
| `workflow.attachment.cleanup.executions{result=...}` | 清理完成、未获锁跳过和调度失败次数 |
| `workflow.attachment.cleanup.items{result=...}` | 单条清理成功和失败次数 |
| `workflow.attachment.cleanup.lock.active` | 当前 JVM 是否持锁 |
| `workflow.attachment.cleanup.lock.degraded` | 获取或释放结果是否曾不确定 |
| `workflow.attachment.cleanup.lock.acquisition.failures` | 获取结果不确定累计次数 |
| `workflow.attachment.cleanup.lock.release.failures` | 释放结果不确定累计次数 |
| `workflow.runtime.metrics.snapshot.available` | 是否已有完整成功快照 |
| `workflow.runtime.metrics.snapshot.age.seconds` | 最近成功快照年龄 |
| `workflow.runtime.metrics.refresh.failures` | 快照刷新失败累计次数 |
| `workflow.runtime.metrics.refresh.inflight` | 唯一采集 worker 是否仍在执行 |

必须配置并真实触发以下告警：快照不可用/陈旧、采集长期 in-flight、锁 degraded、获取/释放失败
增长、deadletter 非零或超过批准阈值、job 持续积压、可用空间低于批准低水位、清理失败增长、
pending 排除 deferred 后持续增长、executor 状态漂移。延迟、容量和队列阈值必须来自 P5-01
实测和审批，本文不提供伪造默认阈值。

## 8. 当前外部门禁状态

| 门禁 | 状态 | 关闭条件 |
| --- | --- | --- |
| P4 扩展并发与权限强化 | `not executed` | 在批准规模完成动作竞态、幂等、对象授权和安全强化矩阵，且业务与审计对账一致 |
| P5-01 性能、容量和峰值 | `not executed` | P4-04 全量基线通过，并在生产同构或批准折算环境完成真实负载、数据对账和容量审批 |
| P5-02 executor 与 72 小时长稳 | `not executed` | P5-01 完成后，以批准拓扑持续 72 小时运行、故障接管、排空并完成 job/业务副作用对账 |
| P5-03 多节点共享附件链 | `not executed` | 真实共享卷和至少两个应用节点完成跨节点上传、绑定、下载、并发清理、备份恢复和 SHA-256 对账 |
| Druid + MySQL named lock 故障注入 | `not executed` | 对 GET_LOCK/RELEASE_LOCK 读取、关闭、断网及 abort 失败逐项注入并验证连接池物理会话淘汰 |
| P5-04 告警送达与恢复 | `not executed` | 监控平台真实配置全部告警，逐项触发并确认值班送达、抑制、恢复通知和时延 |
| 数据库与附件一致性恢复 | `not executed` | 从同一恢复点恢复到隔离环境，SQL、登录、详情和附件下载全部通过 |
| 两轮全新环境彩排与生产 24/72 小时观察 | `not executed` | P0-P5 全部门禁关闭后按发布手册真实执行、独立复核并归档证据 |

以上压力、扩展并发、故障注入、权限强化、监控、备份恢复、生产彩排和 72 小时观察均为主动
后置的 `not executed`，不是已通过，也不是本轮无法继续的环境阻断；它们不占当前业务候选开发
排期，但关闭前不得进入生产发布结论。

当前 Windows 全量回归中，`WorkflowAttachmentStorageTest` 的共享卷正向
`SecureDirectoryStream` 探针按平台条件跳过；同套件的能力不足 fail-closed 分支与单节点本地卷
探针已执行。该跳过项只能在 Linux 真实共享文件系统重新执行并结合跨节点链路关闭，因此计入
P5-03 `not executed`，不得作为已批准跳过项关闭 P4/P5 的生产文件系统门禁。

以上任一项未关闭时，P5/P6 必须保持 `in_progress`、`pending`、`blocked` 或
`not executed`，不得声明生产集成完成。
