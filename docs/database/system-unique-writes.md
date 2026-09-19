# 有效账号与参数键的唯一写入

## 数据库契约

`sys_user.uk_sys_user_active_name` 对虚拟列 `active_user_name` 建唯一索引。该列只在 `del_flag='0'` 时返回 `user_name`，其他行返回 NULL。因此停用但未删除账号仍占用名称；同名账号可以反复创建、软删除，保留多份删除历史。恢复已删除账号到有效状态时同样受此约束。不能替换为 `(user_name, del_flag)` 唯一索引。

`sys_config.uk_sys_config_key` 对完整 `config_key` 建唯一索引。两种名称都使用原列字符集、排序规则及其大小写、重音、尾空格比较语义；不进行 `lower`、`trim` 或应用层归一化。升级脚本从现有 `user_name` 列读取字符集和排序规则创建派生列，不改变原列。配置输入的非空等校验保留；历史 NULL 配置键继续遵循 SQL UNIQUE 的 NULL 语义，不由本次升级清洗。手机号、邮箱的规则不变。

空库入口仍为 `sql/ry_20260417.sql`，Docker 初始化顺序不变。账号种子及配置种子改成显式列名，所有生产 Mapper 原本已经显式列名且读取明确字段；派生列不进入 Java DTO/API。外部直连 SQL 若使用无列名 INSERT 或位置式 `SELECT *`，上线前应改成原业务列的显式列表。

MySQL 的虚拟列可建唯一索引，NULL 允许重复，参见 [MySQL 8.4 生成列索引](https://dev.mysql.com/doc/refman/8.4/en/create-table-secondary-indexes.html) 与 [CREATE TABLE](https://dev.mysql.com/doc/refman/8.4/en/create-table.html)。本单元最低要求 MySQL 8.0；正式演练使用 MySQL 8.4。

## 写入口与失败语义

- 管理新增、注册和导入的新账号均经 `SysUserServiceImpl.insertUserRecord` 执行生产 INSERT。只捕获该 INSERT 的冲突，岗位、角色关联的主键错误不会被误报为账号重名。管理新增的用户、岗位和角色仍在一个 Spring 事务中。
- `SysUserController`、`SysRegisterService`、导入循环分别恢复原有“新增用户…登录账号已存在”“保存用户…注册账号已存在”“账号…已存在”提示。注册冲突返回原有失败字符串，不安排成功审计。更新资料的 SQL 不写 `user_name`，因此删除无效账号改名查重，未引入改名功能。
- 导入保留 `selectActiveUserIdentityByName` 的最小身份查询，只用于区分创建和更新。C07 的真实 DataScope 更新资格、批次新建账号后续更新资格、部门权限和字段验证均保留。两个导入同时读到不存在时，实际 INSERT 仍只有一个成功，失败行报告已存在且不重试更新。导入继续原有逐行成功/失败汇总行为，不改成整个文件事务。
- 配置新增和修改各自具有事务。冲突在实际 INSERT/UPDATE 识别；原键读取只用于提交后处理缓存。失败或外层事务回滚不发布新缓存、不删除旧缓存；成功提交后更新缓存。提交后 Redis 故障不等于数据库回滚，沿用现有配置缓存刷新入口恢复。

仅 MySQL SQLState `23000`、错误码 `1062` 且错误末尾明确指向上述指定索引时，两个具体异常类型才标识名称冲突。这是各入口错误消息的分流，不增加统一查重/异常框架。主键、未知唯一键、权限、连接、其他 SQL 故障保留原异常及原因；非预期的服务器错误文本也不猜测为重名。

## 现有数据库升级

1. 在目标业务库执行只读 [清点脚本](../../sql/system/20260916__unique_keys_audit.sql)。核对版本、库名、列定义、全部现有索引，以及逐行列出的冲突成员 ID、名称、十六进制原值。此脚本使用原列排序规则分组；不使用可能截断的 GROUP_CONCAT，不输出密码、手机号、邮箱或配置值。
2. 冲突不为空时阻断迁移。交由业务负责人确定处理方式及备份，禁止迁移脚本自动删除、合并或改名。其他名字的相同业务唯一索引、同名但不同定义的列/索引也会阻断，须先审查。清点结果不是后续写入许可，最终唯一性由 MySQL 建索引和写入约束保证。
3. 停止所有应用实例及直连账号/配置写入口，备份两表的结构与数据。以具有 DDL、例程创建/执行权限的部署账号运行 [升级脚本](../../sql/system/20260916__unique_keys_upgrade.sql)，禁止 `--force`。应用运行账号不需要新增 DDL 权限。不在旧版本仍写入时切换。
4. 只接受退出码 0 且结果为 `C12 INSTALLED` 或 `C12 ALREADY_INSTALLED_VERIFIED`。后者表示已逐项核对生成表达式、字符集、排序规则和索引定义。存在列但缺索引、或只完成一张表时会补齐；不把“名称存在”当完成。错误退出、`C12 BLOCKED` 或没有完成结果均不得发布新应用。
5. 重跑只读清点，核实两索引及派生列，再发布本单元全部代码，刷新配置缓存后恢复写入。所有实例必须一起切换，避免旧代码和旧缓存参与写入。

PowerShell 7 从仓库根目录执行，例如（连接与认证由部署环境提供，不把密码写入命令行）：

```powershell
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -LiteralPath 'sql/system/20260916__unique_keys_audit.sql' -Raw |
    mysql --default-character-set=utf8mb4 --batch --database=目标库
if ($LASTEXITCODE -ne 0) { throw '清点失败' }
Get-Content -LiteralPath 'sql/system/20260916__unique_keys_upgrade.sql' -Raw |
    mysql --default-character-set=utf8mb4 --batch --database=目标库
if ($LASTEXITCODE -ne 0) { throw '升级失败，保持停写并核对部分 DDL' }
```

MySQL DDL 会隐式提交，两张表的升级不是同一原子事务。第二张表失败时，第一张表可能已完成。保持停写，重跑只读清点确定实际结构；修复阻断原因后重复升级，或按下述顺序回退。失败时可能留下本脚本同名辅助 procedure；重复升级/回退在开始时清理自己的 procedure，成功时删除。若放弃升级，可由部署账号在清点后单独 `DROP PROCEDURE IF EXISTS c12_upgrade_system_unique_keys` / `c12_rollback_system_unique_keys`，不涉及业务数据。

## 回退与恢复条件

1. 停止所有账号/配置写入，完成备份，准备恢复旧应用和其配置缓存。优先只回退应用并保留约束；本仓库旧 Mapper 使用显式列名可继续写入，但旧版本并发冲突可能显示系统错误，应保持停写直到版本选择明确。
2. 确需恢复旧数据库结构时，确认没有新版本应用依赖唯一性，也没有外部视图、生成列、SQL 消费者或后来新增的索引依赖 `active_user_name`。确认当前业务行均可由旧版本读取；原业务列没有格式迁移，不要求清除删除历史。本次迁移本身没有改写业务数据，无需还原业务行。不要用空库安装 SQL 覆盖现有数据库。
3. 执行 [回退脚本](../../sql/system/20260916__unique_keys_rollback.sql)：先核对全部已存在的本单元结构及额外索引依赖，再删除配置唯一索引，最后单次 ALTER 删除账号唯一索引和派生列。错误定义或额外索引依赖会在删除前阻断。退出码 0 且输出 `C12 ROLLED_BACK` / `C12 ALREADY_ROLLED_BACK_VERIFIED` 才算完成。部分回退失败时继续停写，排除原因后可重复执行。
4. 回退成功后刷新配置缓存、恢复旧应用及写入。旧应用仍有先查后写竞态；数据库回退期间不得宣称唯一性受保护。再次升级必须重新清点，回退期间新增的任何有效账号/配置键冲突都会阻断重新建索引。不可通过简单回滚二进制版本绕过这一数据条件。
