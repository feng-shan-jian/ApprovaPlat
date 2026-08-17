# 工作流发布与回切

## 发布资产

每个发布包必须冻结：

- `ruoyi-admin.jar` 和完整前端静态文件。
- `sql` 中最终空库基线与三组只读验收脚本。
- `deployment` 配置、systemd、Nginx 和发布门禁。
- `RELEASE-METADATA`、完整 `SHA256SUMS` 和 `sql/release-order.txt`。

发布目录必须不可变、无符号链接、无硬链接复用，并由外部批准的 Git commit 和清单 SHA-256 锚定。仓库和验收目录不得保存数据库 dump、Token、用户数据、流程变量正文、附件内容和外部响应正文。

## 数据库路径

当前开发期最终结构只支持空库安装：

1. 证明目标 schema 表数为 0。
2. 先执行若依与 Quartz 基线。
3. 严格按 `sql/release-order.txt` 执行 Flowable Common、Engine、History、DMN、模型版本唯一约束、工作流业务基线和工作流菜单。
4. 核对总表数 `94=20+11+36+27`。
5. 执行三组共 35 项只读验收和 `mysqlcheck`，所有结果必须通过。

部分初始化、旧结构或未知漂移不得续跑。开发环境直接删除目标 schema 后从空库重建，不生成备份、回填、双写或兼容迁移。`sql/integration/8.0.0__sms_oss.sql` 是可选集成资产，不得进入核心顺序文件和 `94/27` 表数门禁。

从第二个正式发布版本开始，数据库变更才允许新增从上一正式版本到当前版本的不可改写迁移。破坏性收缩必须在旧版本退出支持后单独发布，不能回写首个基线。

## 发布流程

1. 固定发布 ID、Git commit、构建时间和资产哈希。
2. 使用 `workflow-release-gate.sh preflight` 校验发布包、生产配置、SQL 顺序和批准锚点。
3. 在独立空 schema 完成数据库安装、三组 verify、`mysqlcheck`、后端集成测试和真实 API 烟测。
4. 执行后端全量测试、前端 lint、契约测试和生产构建，冻结结果摘要。
5. 摘除入口流量，原子切换 JAR、前端和配置符号链接。
6. 启动服务，核对运行制品哈希、liveness、readiness、Prometheus、MySQL、Redis 和 Flowable Job 状态。
7. 通过真实入口执行管理员登录、五角色允许/拒绝、流程发起办理、通知、协作、SLA、连接器和附件烟测后恢复流量。

发布数据库账号常态锁定，只在受控安装窗口短时解锁。应用运行账号仅拥有目标 schema 的 `SELECT`、`INSERT`、`UPDATE`、`DELETE`，不得依赖 `flowable.database-schema-update` 自动修复结构。

## 制品回切

应用制品切换失败时保持流量关闭，停止目标版本并原子回切上一不可变 JAR、前端和配置。回切前后必须核对实际运行哈希、健康状态、数据库 `94/27` 基线和核心业务烟测。

当前基线不提供数据库反向 SQL。若首次空库安装失败，废弃部分初始化 schema，修复发布资产后重新创建空 schema；不得在部分结果上续跑。数据库结构已被新版本业务写入改变时，不允许仅回切旧应用冒充恢复完成。

## 证据规则

正式验收 Markdown 只记录：

- 执行命令和开始/结束时间。
- 通过、失败、跳过数量。
- 最终表数、verify 和 `mysqlcheck` 摘要。
- 权限拒绝、非法状态、并发冲突、外部失败和幂等重试的脱敏状态对账。
- `blocked`、`not executed` 或 `failed` 项。

构建成功、静态搜索和截图不能代替真实 API、真实 MySQL、Redis、Flowable 和浏览器链路。
