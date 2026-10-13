# 当前架构与维护边界

ApprovaPlat 是七个 Maven 模块组成的单体应用，配合 Vue 前端、MySQL 和 Redis。模块划分用于约束职责与依赖；流程发布、办理及业务记录仍在同一应用中维护事务。本文替代已完成的阶段实施计划，运行与验收命令统一见 [CONTRIBUTING](../CONTRIBUTING.md)。

## 模块职责

| 模块 | 职责 |
|---|---|
| ruoyi-admin | HTTP 控制器、应用配置和服务装配 |
| ruoyi-framework | 登录、Token、权限快照、安全过滤器及基础设施装配 |
| ruoyi-system | 用户、角色、菜单、字典、公告与系统集成配置 |
| ruoyi-flowable | 流程设计、发布制品、任务与实例生命周期、附件、通知及集成 |
| ruoyi-quartz | 系统定时任务定义、校验及调度 |
| ruoyi-generator | 表结构解释、模板准备和代码生成 |
| ruoyi-common | 跨模块通用协议、工具和基础类型 |
| ruoyi-ui | Vue 页面、设计器、请求状态和浏览器交互 |

system/common 不依赖 flowable，flowable 不依赖 framework/admin；完整内部模块图必须无环。MySQL 测试目标守卫放在 common 的测试目录，通过 `target-guard` 测试制品共享，不进入正式应用制品。

## 业务职责和不可删除的约束

| 业务链 | 当前所有者与维护约束 |
|---|---|
| 在线权限 | `SysPermissionService` 读取当前身份，`TokenService` 通过 Redis 条件写刷新。普通续期不回写旧请求的权限；授权变更在数据库提交后刷新受影响会话。 |
| 字典、公告与角色菜单 | 批量业务写入保留事务；字典缓存按类型在提交后失效。角色授权与菜单删除共用菜单锁，防止关联写入已删除菜单。 |
| 作者字段与模型 | `WorkflowAuthorFieldCatalogService` 解释权限化表单目录；`WorkflowModelService` 持有模型版本命令。设计编辑状态由页面统一维护，不为减少单文件行数拆散所有权。 |
| 发布与冻结 | `WorkflowPublicationArtifactsService` 准备执行模型和八类业务快照，`WorkflowDeploymentArtifactRepository` 持久化资源子部署。BPMN、快照和版本状态原子提交；当前资源协议为 v2。 |
| 资源删除与模型恢复 | 模型保存、导入、部署恢复和资源删除共用引用锁；表单引用覆盖 XML 与元数据。历史缺失元数据仅在限定的恢复事务内修复，后续失败一并回滚。 |
| 任务与申请生命周期 | `WorkflowTaskReturnPreparation`、`WorkflowTaskRevokePreparation`、`WorkflowApplicationResubmitPreparation` 负责读准备，写应用服务负责命令。权限、revision、锁序及实例/附件/通知共同回滚保持在命令边界。 |
| 详情查询 | `WorkflowProcessDetailService` 复用当次已授权事实；独立下载、写入等公开入口仍自行核验对象权限。详情投影不依赖写应用服务。 |
| 附件与文件 | 上传登记、数据库元数据和文件存储有不同失败边界。清理协调器使用租约/token 条件写；绑定阶段必须重新核验有效期，行锁不能阻止时间流逝。 |
| 通知与协作 | 通知 Planner 消费已冻结事实，不读取引擎和线程身份。Outbox/Worker 保留幂等、租约及有界重试；协作消费整体回滚完成后，独立事务登记失败，不能在 rollback-only 事务中报告成功。 |
| HTTP、SQL 与扩展 | HTTP 发送实现集中在 `WorkflowHttpExecution`，重试由调用方负责。端点探测保留发送期间的绑定锁；外部 SQL 提交无法随主库回滚，调用方维持幂等重放。冻结摘要由 `WorkflowExtensionChecksum` 管理。 |
| DMN、子流程、SLA 与事件 | 保留精确定义/版本引用、父子实例传播、timer、会签轮次及 runtime event 幂等账本。运行时消费冻结快照，作者输入校验不能机械替换旧快照解码。 |
| 前端异步状态 | `requestSequence` 控制列表/弹窗结果提交权，卸载后终止提交；字典在途请求按 key 归属。详情观察由相邻 `useWorkflowDetailObservation` 管理；设计器身份选项合同统一在 `designerIdentityContract`。 |

`bin/verify-architecture.ps1` 检查 Maven 图及八个指定 Java 类型的编译依赖；`bin/verify-frontend-architecture.mjs` 检查身份合同和测试夹具边界。它们不能证明所有反射依赖、运行时装配或全部业务行为。

## 验证与脚本归属

| 正式入口 | 用途 |
|---|---|
| `bin/verify-rules.ps1` | Quick、MySQL、BrowserControlled、BrowserReal 四种验证；浏览器范围由 `verification-suites.json` 登记 |
| `bin/verification-common.ps1` | 源码指纹、SQL 清单、数据库身份和进程核验等共享实现 |
| `bin/workflow-test-environment.ps1` | 登记开发目标、启动本轮服务、核对服务产物及清理本轮进程 |
| `bin/verification-target.test.ps1` | 无数据库连接的目标与进程安全门回归 |
| `bin/migrate-workflow-artifacts-v2.ps1` | 已有 v1 部署制品的离线迁移；操作步骤见[迁移指南](workflow-artifact-v2-migration.md) |
| `bin/migrate-workflow-artifacts-v2.test.ps1` | 转换合同与可选真实 MySQL 原子性/回滚回归 |
| `docker/mysql/init.sh` | 仅为空数据卷按 `sql/baseline-files.txt` 顺序初始化 |

以上入口都有正式调用方、回归或维护文档，属于可复用工具。审计、增量升级、修复和回滚 SQL 不属于临时文件，也不能机械加入空库初始化清单。

当前开发目标为 `127.0.0.1:13306/approvaplat`。完整 MySQL/BrowserReal 验证含全局消费者和保留清理，须满足贡献指南中的登记、显式选择、空活动队列和保留域前置条件；不得为运行测试清除正在使用的业务数据。

## 文件保留规则

- 保留生产源码、正式测试及夹具、锁文件、SQL、README 图片和可复用维护指南。
- 保留本地配置凭据和 `output/database-backups/` 中的数据恢复材料，均不纳入 Git。
- 运行日志、Surefire/Playwright 输出、一次性脚本及失败截图核对后清理；完成的阶段计划由当前说明和精简验收记录替代。
- 依赖目录、IDE 配置及正常构建产物有明确用途，不依据“被 Git 忽略”就认定为垃圾。

历史结果和未执行边界见[验收记录](verification-history.md)。
