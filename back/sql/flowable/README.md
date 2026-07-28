# Flowable 8 数据库脚本

## 目标基线

- Java：17
- Spring Boot：4.0.6
- Flowable：8.0.0
- Flowable schema：8.0.0.0
- 数据库：MySQL 8
- 启用模块：Process Engine、Common、History
- 关闭模块：Flowable IDM、Event Registry、CMMN、DMN、REST/UI

项目使用若依 `sys_user`、`sys_role`、`sys_dept` 作为唯一身份目录。应用配置固定使用
`flowable.database-schema-update: "false"`，正式结构只能通过本目录中经过审计的 SQL 创建或变更。
所有 `mysql` 客户端执行（包括 Windows）必须显式使用
`--default-character-set=utf8mb4`，避免中文注释被客户端字符集损坏。版本化 DDL 账号除
`CREATE`/`ALTER`/`INDEX` 等变更权限外，必须对目标 schema 具有 `SELECT`；脚本通过
`information_schema` 判定幂等状态，元数据不可见时不得继续发布。

## 全新初始化顺序

先执行根项目的若依基础脚本和 Quartz 脚本，再依次执行：

1. `mysql/8.0.0/create/flowable.mysql.create.common.sql`
2. `mysql/8.0.0/create/flowable.mysql.create.engine.sql`
3. `mysql/8.0.0/create/flowable.mysql.create.history.sql`
4. `business/8.0.0.2__workflow_model_version_guard.sql`
5. `business/8.0.0__workflow_business.sql`
6. `business/8.0.0.3__workflow_attachment_cleanup_retry.sql`
7. `menu/8.0.0__workflow_menu.sql`
8. `verify/8.0.0__verify.sql`
9. `verify/8.0.0__verify_workflow_business.sql`
10. `verify/8.0.0__verify_workflow_menu.sql`

其中第 6 步在全新安装中连续执行两次，第二次只验证精确元数据契约幂等；两次都必须使用
`--default-character-set=utf8mb4` 并保留独立执行日志。

完整目标数据库固定为 69 张正式表：

| 模块 | 表数 |
| --- | ---: |
| 若依基础表 | 20 |
| Quartz | 11 |
| Flowable Process/Common/History | 32 |
| 项目 `wf_*` 业务表 | 6 |

菜单脚本按自然键幂等维护 2 个目录、11 个页面、40 个按钮，共 53 条记录；同时维护
`workflow_admin`、`workflow_designer`、`workflow_starter`、`workflow_approver`、
`workflow_auditor` 5 个职责分离角色。脚本不会自动为用户分配角色。

## 六张业务表

| 表 | 作用 |
| --- | --- |
| `wf_category` | 流程分类 |
| `wf_form` | 可编辑表单模板 |
| `wf_deploy_form` | 部署时不可变表单快照 |
| `wf_copy` | 流程抄送记录 |
| `wf_attachment_quota_guard` | 附件全局与用户容量串行门禁 |
| `wf_attachment` | 附件元数据、绑定状态和存储生命周期 |

附件文件保存在服务端私有目录，数据库只保存受控相对路径和业务元数据。下载必须同时通过 URL 权限与流程对象授权。

## 只读验收

三组 `verify` 脚本都只能执行查询，所有结果必须为 `PASS`：

- `8.0.0__verify.sql`：验证 Flowable 8.0.0.0 属性、模型版本唯一约束、32 张表精确白名单、关闭模块和 deadletter。
- `8.0.0__verify_workflow_business.sql`：验证六张业务表的列、索引、约束和软引用。
- `8.0.0__verify_workflow_menu.sql`：验证 53 条菜单/按钮、5 个角色、树结构和职责分离。

`CREATE TABLE IF NOT EXISTS` 不能证明已存在表结构正确，因此每次部署都必须执行三组只读验收。

## 五表版本增量

已部署五表版本的目标数据库按以下顺序增加附件配额门禁：

1. 停止工作流写入并创建经过校验的整库备份。
2. 执行 `business/8.0.0.1__workflow_attachment_quota_guard.sql`。
3. 再次执行同一增量脚本，确认幂等且不产生额外变化。
4. 执行 `verify/8.0.0__verify_workflow_business.sql`，确认全部结果为 `PASS`。
5. 核对附件容量、反向代理请求体和实际挂载卷后恢复服务。

禁止使用手工 `ALTER`、应用自动建表或临时表替代正式增量脚本。

## 模型版本并发门禁增量

所有已存在的 Flowable 8 目标库都必须在恢复模型写入前执行
`business/8.0.0.2__workflow_model_version_guard.sql`。脚本会先检查
`ACT_RE_MODEL` 是否存在以及是否已有重复的 `(KEY_, VERSION_, TENANT_ID_)` 版本组；只有数据无歧义时才会幂等建立唯一约束。发现重复版本组时脚本会直接失败，必须先由发布负责人审计处理，禁止自动删除或覆盖模型。

执行后运行 `verify/8.0.0__verify.sql`，确认 `model_version_unique_constraint` 和
`model_version_duplicate_groups` 均返回 `PASS`。

## 附件清理持久化重试增量

所有缺少附件清理重试字段的已部署目标库，必须在升级应用前执行
`business/8.0.0.3__workflow_attachment_cleanup_retry.sql`。脚本幂等增加重试次数、
下次候选时间、稳定错误码、到期索引和状态约束；历史行初始化为零次重试且不改变附件状态。

1. 停止工作流写入和附件清理调度，完成数据库与附件卷一致性备份。
2. 执行增量脚本两次，第二次不得产生额外结构变化。
3. 执行 `verify/8.0.0__verify_workflow_business.sql`，全部结果必须为 `PASS`。
4. 核对清理重试指标、MySQL advisory lock 和附件卷后再恢复调度及写流量。

若同名列、索引或约束已经存在但结构不一致，脚本或只读验收会明确失败；禁止自动删除、
重建或覆盖历史附件记录。
清理列还会校验 `EXTRA` 和 `GENERATION_EXPRESSION` 为空；到期索引精确冻结列数、
列顺序、`SUB_PART`、`COLLATION`、`INDEX_TYPE` 和 `IS_VISIBLE`。状态约束对 MySQL 8.0/8.4
表示差异做稳定化后，必须精确匹配固定 SHA-256；同名弱约束不会被当作已安装。

## 官方脚本来源

三个 Flowable 建表脚本从 Maven Central 的 Flowable 8.0.0 官方 JAR 原样提取：

| 文件 | Maven 构件 | JAR 内路径 |
| --- | --- | --- |
| `flowable.mysql.create.common.sql` | `flowable-engine-common:8.0.0` | `org/flowable/common/db/create/flowable.mysql.create.common.sql` |
| `flowable.mysql.create.engine.sql` | `flowable-engine:8.0.0` | `org/flowable/db/create/flowable.mysql.create.engine.sql` |
| `flowable.mysql.create.history.sql` | `flowable-engine:8.0.0` | `org/flowable/db/create/flowable.mysql.create.history.sql` |

发布冻结时必须记录上述文件、业务 DDL、菜单 SQL、应用 JAR 和前端产物的 SHA-256。任何文件发生变化，都必须重新执行契约测试、完整后端门禁和三组数据库验收。

## 生产规则

- 生产始终保持 `database-schema-update=false`。
- 数据库账号按应用运行、发布 DDL、只读验收三类职责拆分；发布 DDL 账号保留目标 schema `SELECT` 以读取完整元数据。
- 正式 SQL 只能按固定顺序执行，并保存执行时间、操作者、文件哈希和验收输出。
- executor 初始关闭启动应用，结构和配置验收通过后按批准拓扑启用。
- 六类 job 和 deadletter 必须接入监控，异常增长立即停止放量。
- 附件目录必须使用独立持久卷，并纳入容量、备份和完整性检查。
