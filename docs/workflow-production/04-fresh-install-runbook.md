# Flowable 8 全新安装运行手册

## 1. 适用范围与完成标准

本手册用于在全新、空白的 MySQL schema 中部署 ApprovaPlat。执行结束后应同时满足：

- MySQL、Redis、Java、Nginx 和持久化目录达到生产前置条件。
- 目标 schema 按固定顺序建立 69 张正式表。
- 应用运行账号只拥有目标 schema 的 <code>SELECT</code>、<code>INSERT</code>、<code>UPDATE</code>、<code>DELETE</code> 权限。
- Flowable 保持 <code>database-schema-update=false</code>，由发布账号执行正式 SQL。
- 应用通过 systemd 启动，Nginx、后端、MySQL、Redis 和附件目录均通过检查。
- 三组只读数据库验收全部返回 <code>PASS</code>，安装证据已归档且不含凭据。

本文命令以 Ubuntu/WSL、MySQL 8、Redis 6+ 和仓库现有部署路径为基线。生产主机采用其他发行版时，只调整服务名称和绝对路径，表结构顺序、账号职责及验收门禁保持不变。

## 2. 固定变量

由执行人在受控终端设置本次安装变量：

~~~bash
set -euo pipefail

export REPO_ROOT=/mnt/d/ruoyiflowable
export SQL_ROOT="$REPO_ROOT/back/sql"
export FLOWABLE_SQL_ROOT="$SQL_ROOT/flowable"
export DB_SCHEMA=ry-vue
export MYSQL_ADMIN_CNF=/etc/ruoyi/mysql-admin.cnf
export MYSQL_RELEASE_CNF=/etc/ruoyi/mysql-release.cnf
export MYSQL_VERIFY_CNF=/etc/ruoyi/mysql-verify.cnf
export MYSQL_BACKUP_CNF=/etc/ruoyi/mysql-backup.cnf
export RELEASE_ROOT=/var/lib/ruoyi/releases
export RELEASE_ID='<APPROVED_RELEASE_ID>'
export APPROVED_GIT_COMMIT='<APPROVED_40_CHARACTER_GIT_COMMIT>'
export APPROVED_MANIFEST_SHA256='<APPROVED_64_CHARACTER_MANIFEST_SHA256>'
export APPROVED_BUILD_TIMESTAMP_UTC='<APPROVED_UTC_BUILD_TIMESTAMP>'
# 首次安装设置为 NONE；P6 彩排必须在此处改为真实上一版本及其批准锚点。
export PREVIOUS_RELEASE_ID='<NONE_OR_APPROVED_PREVIOUS_RELEASE_ID>'
export APPROVED_PREVIOUS_GIT_COMMIT='<NONE_OR_APPROVED_PREVIOUS_GIT_COMMIT>'
export APPROVED_PREVIOUS_MANIFEST_SHA256='<NONE_OR_APPROVED_PREVIOUS_MANIFEST_SHA256>'
export RELEASE_DIR="$RELEASE_ROOT/$RELEASE_ID"
export PREVIOUS_RELEASE_DIR=''
export ACTIVE_RELEASE_LINK=/var/lib/ruoyi/current
export RUOYI_PROFILE=/var/lib/ruoyi/uploadPath
export ATTACHMENT_ROOT="$RUOYI_PROFILE/workflow-attachments"
export RUOYI_LOG_ROOT=/var/lib/ruoyi/logs
export NGINX_WORKER_USER=www-data
export RUOYI_SERVER_NAME='<APPROVED_FQDN>'
export RUOYI_TLS_CERTIFICATE='<ABSOLUTE_CERTIFICATE_PATH>'
export RUOYI_TLS_CERTIFICATE_KEY='<ABSOLUTE_PRIVATE_KEY_PATH>'
export EVIDENCE_ROOT=/var/lib/ruoyi/install-evidence/$(date +%Y%m%d-%H%M%S)
export EVIDENCE_RUN_ID='<APPROVED_UNIQUE_INSTALL_RUN_ID>'
export ENVIRONMENT_ID='<APPROVED_ENVIRONMENT_ASSET_ID>'
export DATABASE_IDENTITY='<APPROVED_DATABASE_ASSET_ID>'
export REDIS_IDENTITY='<APPROVED_REDIS_ASSET_ID>'
export ATTACHMENT_STORAGE_IDENTITY='<APPROVED_ATTACHMENT_STORAGE_ASSET_ID>'
export OPERATOR="$(id -un)"
export OPERATOR_GROUP="$(id -gn)"
export INSTALL_STARTED_AT="$(date '+%Y-%m-%dT%H:%M:%S%:z')"

[[ "$RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
[[ "$APPROVED_GIT_COMMIT" =~ ^[0-9a-f]{40}$ ]]
[[ "$APPROVED_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$APPROVED_BUILD_TIMESTAMP_UTC" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]
test "$(date -u -d "$APPROVED_BUILD_TIMESTAMP_UTC" '+%Y-%m-%dT%H:%M:%SZ')" = \
  "$APPROVED_BUILD_TIMESTAMP_UTC"
test "$(git -C "$REPO_ROOT" rev-parse HEAD)" = "$APPROVED_GIT_COMMIT"
for identity_value in \
  "$EVIDENCE_RUN_ID" "$ENVIRONMENT_ID" "$DATABASE_IDENTITY" \
  "$REDIS_IDENTITY" "$ATTACHMENT_STORAGE_IDENTITY"
do
  [[ "$identity_value" =~ ^[A-Za-z0-9][A-Za-z0-9._:@/-]{2,127}$ ]]
  case "${identity_value^^}" in
    NONE|UNKNOWN|TODO|TBD|MOCK|EXAMPLE|PLACEHOLDER|*PLACEHOLDER*) exit 1 ;;
  esac
done

case "$PREVIOUS_RELEASE_ID" in
  NONE)
    test "$APPROVED_PREVIOUS_GIT_COMMIT" = 'NONE'
    test "$APPROVED_PREVIOUS_MANIFEST_SHA256" = 'NONE'
    ;;
  *)
    [[ "$PREVIOUS_RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
    test "$PREVIOUS_RELEASE_ID" != "$RELEASE_ID"
    [[ "$APPROVED_PREVIOUS_GIT_COMMIT" =~ ^[0-9a-f]{40}$ ]]
    [[ "$APPROVED_PREVIOUS_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
    test "$APPROVED_PREVIOUS_GIT_COMMIT" != "$APPROVED_GIT_COMMIT"
    export PREVIOUS_RELEASE_DIR="$RELEASE_ROOT/$PREVIOUS_RELEASE_ID"
    ;;
esac

sudo install -d -m 0700 -o "$OPERATOR" -g "$OPERATOR_GROUP" \
  "$EVIDENCE_ROOT"

# TLS 三项均为必填发布参数；占位符、空值、非绝对证书路径立即终止安装。
[[ "$RUOYI_SERVER_NAME" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]
for tls_path in "$RUOYI_TLS_CERTIFICATE" "$RUOYI_TLS_CERTIFICATE_KEY"
do
  [[ "$tls_path" =~ ^/[A-Za-z0-9._/+:-]+$ ]]
  sudo test -f "$tls_path"
  sudo test -r "$tls_path"
done

# 证书必须处于有效期内、覆盖批准域名，并与未加密私钥匹配。
sudo openssl x509 -in "$RUOYI_TLS_CERTIFICATE" -noout -checkend 0
sudo openssl x509 -in "$RUOYI_TLS_CERTIFICATE" \
  -noout -checkhost "$RUOYI_SERVER_NAME"
CERTIFICATE_PUBLIC_KEY_SHA256="$(
  sudo openssl x509 -in "$RUOYI_TLS_CERTIFICATE" -pubkey -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | sha256sum | cut -d' ' -f1
)"
PRIVATE_KEY_PUBLIC_KEY_SHA256="$(
  sudo openssl pkey -in "$RUOYI_TLS_CERTIFICATE_KEY" -passin pass: \
    -pubout -outform DER 2>/dev/null \
    | sha256sum | cut -d' ' -f1
)"
test -n "$CERTIFICATE_PUBLIC_KEY_SHA256"
test "$CERTIFICATE_PUBLIC_KEY_SHA256" = "$PRIVATE_KEY_PUBLIC_KEY_SHA256"
unset CERTIFICATE_PUBLIC_KEY_SHA256 PRIVATE_KEY_PUBLIC_KEY_SHA256
~~~

<code>DB_SCHEMA</code> 固定为 <code>ry-vue</code>，与生产数据源模板一致。首次独立安装保持 <code>PREVIOUS_RELEASE_ID=NONE</code>；P6 发布/回滚彩排必须在执行本手册前显式设置真实上一版本 ID、目录及两项批准锚点，内层 <code>fresh-install</code> 回执必须与彩排根目录绑定同一 current/previous 关系。批准 commit 和清单摘要来自发布单或制品库，不得用命令现场计算后自称“已批准”。所有 MySQL 客户端文件均采用 <code>[client]</code> 格式，由 root 管理并设置为 <code>0600</code>；文档、命令行和发布证据只记录文件路径，不记录密码内容。

## 3. 环境前置门禁

### 3.1 软件与系统资源

执行并保存版本输出：

~~~bash
java -version 2>&1 | tee "$EVIDENCE_ROOT/java-version.txt"
mysql --version | tee "$EVIDENCE_ROOT/mysql-version.txt"
redis-server --version | tee "$EVIDENCE_ROOT/redis-version.txt"
nginx -v 2>&1 | tee "$EVIDENCE_ROOT/nginx-version.txt"
NGINX_VERSION="$(nginx -v 2>&1 \
  | sed -E 's#^nginx version: nginx/([0-9]+([.][0-9]+){1,3}).*$#\1#')"
dpkg --compare-versions "$NGINX_VERSION" ge 1.24.0
unset NGINX_VERSION
envsubst --version | tee "$EVIDENCE_ROOT/envsubst-version.txt"
openssl version | tee "$EVIDENCE_ROOT/openssl-version.txt"
node --version | tee "$EVIDENCE_ROOT/node-version.txt"
npm --version | tee "$EVIDENCE_ROOT/npm-version.txt"
~~~

门禁要求：

- Java 为 17。
- Nginx 为 1.24 或更高版本；最终渲染配置还必须通过第 8 节的 <code>nginx -t</code>。
- MySQL 为 8.x，默认字符集为 <code>utf8mb4</code>，默认排序规则为 <code>utf8mb4_unicode_ci</code>，时区与业务约定一致。
- Redis 为 6 或更高版本，并启用持久化、受控认证和 <code>noeviction</code> 策略。
- 主机时间同步正常，磁盘容量覆盖数据库、日志、发布资产、备份和附件容量上限。
- 生产端口只向批准网段开放；MySQL、Redis 和 Java 服务优先绑定本机或受控内网地址。

### 3.2 服务账号与目录

~~~bash
getent group ruoyi >/dev/null || sudo groupadd --system ruoyi
id ruoyi >/dev/null 2>&1 || \
  sudo useradd --system --gid ruoyi --home /var/lib/ruoyi \
  --shell /usr/sbin/nologin ruoyi
id "$NGINX_WORKER_USER" >/dev/null 2>&1

sudo install -d -m 0750 -o root -g ruoyi /etc/ruoyi
sudo install -d -m 0755 -o root -g root /var/lib/ruoyi
sudo install -d -m 0700 -o ruoyi -g ruoyi "$RUOYI_PROFILE"
sudo install -d -m 0700 -o ruoyi -g ruoyi "$ATTACHMENT_ROOT"
sudo install -d -m 0700 -o ruoyi -g ruoyi "$RUOYI_LOG_ROOT"
sudo install -d -m 0755 -o root -g root "$RELEASE_ROOT"
sudo -u ruoyi test -r "$ATTACHMENT_ROOT"
sudo -u ruoyi test -w "$ATTACHMENT_ROOT"
sudo -u ruoyi test -w "$RUOYI_LOG_ROOT"
df -h "$RUOYI_PROFILE" | tee "$EVIDENCE_ROOT/profile-filesystem.txt"
~~~

<code>${RUOYI_PROFILE}/workflow-attachments</code> 是工作流附件私有目录。Nginx 不直接映射该目录，应用以 <code>ruoyi</code> 用户读写，备份任务以受控账号只读访问。

## 4. 凭据记录强制规则

任何开发、联调、集成测试或发布彩排中创建、重置或轮换的账号与随机密码，都必须在同一操作阶段记录到仓库根目录的 <code>testcount/accounts.local.md</code>。记录完成后才能继续使用该账号。

执行前先确认本地文件存在且受 Git 忽略：

~~~bash
cd "$REPO_ROOT"
test -f testcount/accounts.local.md || \
  install -m 0600 /dev/null testcount/accounts.local.md
git check-ignore -v testcount/accounts.local.md \
  | tee "$EVIDENCE_ROOT/accounts-ignore-check.txt"
git status --short | tee "$EVIDENCE_ROOT/git-status-before-install.txt"
~~~

每条本机记录使用以下字段；占位符由执行人在本机忽略文件中填写，本文不保存任何真实密码：

~~~markdown
## <系统或服务名称>

- 创建/重置时间：<Asia/Shanghai 时间>
- 环境与主机：<环境、主机、端口或连接目标>
- 账号：<账号>
- 密码：<随机密码，仅写入 accounts.local.md>
- 权限范围：<角色、数据库授权或可访问资源>
- 用途：<联调、集成测试或发布彩排>
- 状态：<启用、已轮换、已禁用或已删除>
- 备注：<关联发布、轮换时间或清理计划>
~~~

生产凭据同步进入批准的密钥系统或 root 可读环境文件。<code>accounts.local.md</code> 只用于当前工作机的受控记录，不进入 Git、构建产物、发布包、证据归档、终端日志或聊天记录。账号轮换、锁定、禁用或删除后，同步更新其状态。

## 5. MySQL 初始化

### 5.1 四类数据库职责

数据库管理员在安全 DBA 会话中创建账号并从密钥系统注入随机密码。四个账号与随机密码都必须先按第 4 节写入 <code>testcount/accounts.local.md</code> 并同步批准的密钥系统，再执行创建；示例中的密码文本仅为占位符：

~~~sql
CREATE USER 'ruoyi_release'@'127.0.0.1'
  IDENTIFIED BY '<RELEASE_SECRET_FROM_SECRET_STORE>' ACCOUNT LOCK;
CREATE USER 'ruoyi_app'@'127.0.0.1'
  IDENTIFIED BY '<APP_SECRET_FROM_SECRET_STORE>';
CREATE USER 'ruoyi_verify'@'127.0.0.1'
  IDENTIFIED BY '<VERIFY_SECRET_FROM_SECRET_STORE>';
CREATE USER 'ruoyi_backup'@'127.0.0.1'
  IDENTIFIED BY '<BACKUP_SECRET_FROM_SECRET_STORE>' ACCOUNT LOCK;

GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX,
      REFERENCES, CREATE TEMPORARY TABLES
ON `ry-vue`.* TO 'ruoyi_release'@'127.0.0.1';

GRANT SELECT, INSERT, UPDATE, DELETE
ON `ry-vue`.* TO 'ruoyi_app'@'127.0.0.1';

GRANT SELECT
ON `ry-vue`.* TO 'ruoyi_verify'@'127.0.0.1';

GRANT SELECT, SHOW VIEW, TRIGGER
ON `ry-vue`.* TO 'ruoyi_backup'@'127.0.0.1';
~~~

| 账号 | 用途 | 常态 |
| --- | --- | --- |
| <code>ruoyi_release</code> | 创建 schema 对象、执行版本化 SQL 和菜单种子 | 仅发布窗口解锁，执行后锁定 |
| <code>ruoyi_app</code> | Java、Flowable 和 Quartz 正式运行 | 持续启用，仅目标 schema DML |
| <code>ruoyi_verify</code> | 执行只读 SQL 和发布核查 | 按验收窗口启用或由只读密钥托管 |
| <code>ruoyi_backup</code> | 一致性逻辑备份 | 仅备份窗口解锁，仅目标 schema 的 SELECT、SHOW VIEW、TRIGGER |

账号主机范围应与真实连接源一致。数据库位于独立主机时，使用批准的应用网段或固定地址，并在 JDBC URL 中启用证书校验。

发布、验证、备份和 DBA 客户端文件使用以下结构，分别写入 <code>MYSQL_RELEASE_CNF</code>、<code>MYSQL_VERIFY_CNF</code>、<code>MYSQL_BACKUP_CNF</code>、<code>MYSQL_ADMIN_CNF</code> 指定路径，并由 root 设置为 <code>0600</code>。真实值只从密钥系统写入主机文件：

~~~ini
[client]
host=127.0.0.1
port=3306
protocol=TCP
user=<ROLE_SPECIFIC_DATABASE_ACCOUNT>
password=<SECRET_FROM_SECRET_STORE>
default-character-set=utf8mb4
~~~

<code>ruoyi_release</code> 对目标 schema 的 <code>SELECT</code> 是幂等 DDL 元数据可见性权限，不是应用运行授权。若删除该权限，<code>information_schema</code> 可能无法呈现已存在对象，发布必须停止，禁止将重复对象错误当作幂等成功。所有执行版本化 SQL 和只读验收的 Linux/Windows <code>mysql</code> 命令都显式传入 <code>--default-character-set=utf8mb4</code>，不依赖操作系统默认字符集。

### 5.2 创建空 schema 并验证执行边界

~~~bash
sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --default-character-set=utf8mb4 <<'SQL'
CREATE DATABASE IF NOT EXISTS `ry-vue`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
SQL

TABLE_COUNT="$(
  sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
    --default-character-set=utf8mb4 \
    --batch --skip-column-names --execute="
      SELECT COUNT(*)
      FROM information_schema.TABLES
      WHERE TABLE_SCHEMA = 'ry-vue';"
)"
test "$TABLE_COUNT" = "0"
printf 'empty_schema_table_count=%s\n' "$TABLE_COUNT" \
  | tee "$EVIDENCE_ROOT/empty-schema-check.txt"
~~~

空 schema 门禁必须返回 <code>0</code>。本节只使用 DBA 和只读验收账号，<code>ruoyi_release</code> 保持锁定；基础脚本含显式 <code>DROP TABLE IF EXISTS</code>，因此只在本节确认的空 schema 中执行。已有结构统一进入版本化发布流程。

### 5.3 固定 69 表 SQL 顺序

所有命令使用发布账号，并将标准输出与错误输出保存到证据目录：

~~~bash
# 发布账号只在本代码块的正式建表/迁移窗口解锁；任何命令失败或终端退出都会再次锁定。
RELEASE_ACCOUNT_RELOCK_REQUIRED=0
relock_release_account() {
  local original_status="$?"
  trap - EXIT
  if test "$RELEASE_ACCOUNT_RELOCK_REQUIRED" = '1'; then
    if ! sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
      --execute="ALTER USER 'ruoyi_release'@'127.0.0.1' ACCOUNT LOCK;"
    then
      printf 'failed to re-lock ruoyi_release\n' >&2
      exit 1
    fi
  fi
  exit "$original_status"
}
trap relock_release_account EXIT

test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="SELECT ACCOUNT_LOCKED FROM mysql.user
    WHERE User = 'ruoyi_release' AND Host = '127.0.0.1';")" = 'Y'
sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="ALTER USER 'ruoyi_release'@'127.0.0.1' ACCOUNT UNLOCK;"
RELEASE_ACCOUNT_RELOCK_REQUIRED=1
test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="SELECT ACCOUNT_LOCKED FROM mysql.user
    WHERE User = 'ruoyi_release' AND Host = '127.0.0.1';")" = 'N'

run_sql() {
  local sequence="$1"
  local sql_file="$2"
  local log_file="$EVIDENCE_ROOT/${sequence}-$(basename "$sql_file").log"

  test -r "$sql_file"
  sudo mysql --defaults-extra-file="$MYSQL_RELEASE_CNF" --protocol=TCP \
    --default-character-set=utf8mb4 \
    --database="$DB_SCHEMA" --show-warnings < "$sql_file" \
    > "$log_file" 2>&1
}

run_sql 01 "$SQL_ROOT/ry_20260417.sql"
run_sql 02 "$SQL_ROOT/quartz.sql"
run_sql 03 "$FLOWABLE_SQL_ROOT/mysql/8.0.0/create/flowable.mysql.create.common.sql"
run_sql 04 "$FLOWABLE_SQL_ROOT/mysql/8.0.0/create/flowable.mysql.create.engine.sql"
run_sql 05 "$FLOWABLE_SQL_ROOT/mysql/8.0.0/create/flowable.mysql.create.history.sql"
run_sql 06 "$FLOWABLE_SQL_ROOT/business/8.0.0.2__workflow_model_version_guard.sql"
run_sql 07 "$FLOWABLE_SQL_ROOT/business/8.0.0__workflow_business.sql"
run_sql 08 "$FLOWABLE_SQL_ROOT/business/8.0.0.3__workflow_attachment_cleanup_retry.sql"
run_sql 09 "$FLOWABLE_SQL_ROOT/business/8.0.0.3__workflow_attachment_cleanup_retry.sql"
run_sql 10 "$FLOWABLE_SQL_ROOT/menu/8.0.0__workflow_menu.sql"

sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="ALTER USER 'ruoyi_release'@'127.0.0.1' ACCOUNT LOCK;"
test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="SELECT ACCOUNT_LOCKED FROM mysql.user
    WHERE User = 'ruoyi_release' AND Host = '127.0.0.1';")" = 'Y'
RELEASE_ACCOUNT_RELOCK_REQUIRED=0
trap - EXIT
unset RELEASE_ACCOUNT_RELOCK_REQUIRED
~~~

| 顺序 | 模块 | 表数 |
| ---: | --- | ---: |
| 1 | 若依基础表 | 20 |
| 2 | Quartz | 11 |
| 3-5 | Flowable Common、Process、History | 32 |
| 6 | 模型版本唯一约束 | 0 |
| 7 | <code>wf_*</code> 正式业务表 | 6 |
| 8-9 | 附件清理重试结构幂等契约 | 0 |
| 10 | 菜单与角色种子 | 0 |
| 合计 | 正式表 | 69 |

<code>8.0.0.2__workflow_model_version_guard.sql</code> 幂等增加 <code>ACT_RE_MODEL(KEY_, VERSION_, TENANT_ID_)</code> 唯一约束；若已有重复版本组会明确拒绝执行，必须先完成数据治理，不能静默删除或覆盖模型。<code>8.0.0__workflow_business.sql</code> 已包含 <code>wf_attachment_quota_guard</code>。<code>8.0.0.3__workflow_attachment_cleanup_retry.sql</code> 在基础建表后连续执行两次，第二次用于证明精确元数据契约幂等，两次日志都必须保留。<code>business/8.0.0.1__workflow_attachment_quota_guard.sql</code> 用于已部署五表版本的增量升级，不参与全新 69 表初始化。

### 5.4 表数与账号权限门禁

~~~bash
sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --default-character-set=utf8mb4 \
  --database="$DB_SCHEMA" --batch --raw --execute="
SELECT
    COUNT(*) AS total_tables,
    COUNT(*)
      - SUM(TABLE_NAME LIKE 'QRTZ\\_%')
      - SUM(TABLE_NAME LIKE 'ACT\\_%' OR TABLE_NAME LIKE 'FLW\\_%')
      - SUM(TABLE_NAME LIKE 'wf\\_%') AS ruoyi_tables,
    SUM(TABLE_NAME LIKE 'QRTZ\\_%') AS quartz_tables,
    SUM(TABLE_NAME LIKE 'ACT\\_%' OR TABLE_NAME LIKE 'FLW\\_%') AS flowable_tables,
    SUM(TABLE_NAME LIKE 'wf\\_%') AS workflow_tables
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'BASE TABLE';" \
  | tee "$EVIDENCE_ROOT/table-counts.tsv"

# 把 information_schema 的真实授权归一为固定五列：职责、目标 schema 权限、
# 非 USAGE 全局权限数、越界 schema/对象权限数、可转授权权限数。
write_mysql_grant_evidence() {
  local account_role="$1"
  local account_name="$2"
  local target_file="$3"

  case "$account_role:$account_name" in
    app:ruoyi_app|backup:ruoyi_backup) ;;
    *) printf 'unsupported grant evidence account\n' >&2; return 1 ;;
  esac
  sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
    --batch --skip-column-names --raw --execute="
WITH account_scope AS (
  SELECT CONCAT(CHAR(39), '$account_name', CHAR(39), '@',
                CHAR(39), '127.0.0.1', CHAR(39)) AS grantee_name
)
SELECT
  '$account_role',
  COALESCE((
    SELECT GROUP_CONCAT(PRIVILEGE_TYPE ORDER BY PRIVILEGE_TYPE SEPARATOR ',')
    FROM information_schema.SCHEMA_PRIVILEGES
    WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
      AND TABLE_SCHEMA = '$DB_SCHEMA'
  ), ''),
  (SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES
   WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
     AND PRIVILEGE_TYPE <> 'USAGE'),
  (SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES
   WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
     AND TABLE_SCHEMA <> '$DB_SCHEMA')
  + (SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES
     WHERE GRANTEE = (SELECT grantee_name FROM account_scope))
  + (SELECT COUNT(*) FROM information_schema.COLUMN_PRIVILEGES
     WHERE GRANTEE = (SELECT grantee_name FROM account_scope))
  + (SELECT COUNT(*) FROM information_schema.ROUTINE_PRIVILEGES
     WHERE GRANTEE = (SELECT grantee_name FROM account_scope)),
  (SELECT COUNT(*) FROM information_schema.USER_PRIVILEGES
   WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
     AND IS_GRANTABLE = 'YES')
  + (SELECT COUNT(*) FROM information_schema.SCHEMA_PRIVILEGES
     WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
       AND IS_GRANTABLE = 'YES')
  + (SELECT COUNT(*) FROM information_schema.TABLE_PRIVILEGES
     WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
       AND IS_GRANTABLE = 'YES')
  + (SELECT COUNT(*) FROM information_schema.COLUMN_PRIVILEGES
     WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
       AND IS_GRANTABLE = 'YES')
  + (SELECT COUNT(*) FROM information_schema.ROUTINE_PRIVILEGES
     WHERE GRANTEE = (SELECT grantee_name FROM account_scope)
       AND IS_GRANTABLE = 'YES');" \
    | tee "$target_file"
}

write_mysql_grant_evidence \
  app ruoyi_app "$EVIDENCE_ROOT/app-account-grants.txt"
write_mysql_grant_evidence \
  backup ruoyi_backup "$EVIDENCE_ROOT/backup-account-grants.txt"
grep -Fqx $'app\tDELETE,INSERT,SELECT,UPDATE\t0\t0\t0' \
  "$EVIDENCE_ROOT/app-account-grants.txt"
grep -Fqx $'backup\tSELECT,SHOW VIEW,TRIGGER\t0\t0\t0' \
  "$EVIDENCE_ROOT/backup-account-grants.txt"
~~~

表数必须为 <code>69 / 20 / 11 / 32 / 6</code>。应用账号授权输出只允许目标 schema 的四项 DML 权限；备份账号只允许 <code>SELECT</code>、<code>SHOW VIEW</code>、<code>TRIGGER</code>，两者均不包含全局权限、授权管理或其他 schema 权限。

### 5.5 最小权限备份与可读性门禁

使用与正式发布相同的参数执行一次真实逻辑备份，再恢复到本次安装专用的隔离 schema。备份账号不使用 <code>SHOW_ROUTINE</code>、<code>EVENT</code>、<code>PROCESS</code>、<code>LOCK TABLES</code>、<code>RELOAD</code> 或全局权限：

~~~bash
BACKUP_SMOKE_ROOT="$EVIDENCE_ROOT/backup-smoke"
BACKUP_VERIFY_SCHEMA="ry_vue_backup_verify_${RELEASE_ID//[^A-Za-z0-9]/_}"
BACKUP_SMOKE_SQL="$BACKUP_SMOKE_ROOT/schema.sql"
BACKUP_VERIFY_SCHEMA_CREATED=0
BACKUP_ACCOUNT_RELOCK_REQUIRED=0
install -d -m 0700 "$BACKUP_SMOKE_ROOT"

cleanup_backup_smoke() {
  local original_status="$?"
  local cleanup_failed=0
  trap - EXIT
  rm -f -- "$BACKUP_SMOKE_SQL"
  if test "$BACKUP_VERIFY_SCHEMA_CREATED" = '1'; then
    sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
      --execute="DROP DATABASE IF EXISTS \`$BACKUP_VERIFY_SCHEMA\`;" \
      || cleanup_failed=1
  fi
  if test "$BACKUP_ACCOUNT_RELOCK_REQUIRED" = '1'; then
    sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
      --execute="ALTER USER 'ruoyi_backup'@'127.0.0.1' ACCOUNT LOCK;" \
      || cleanup_failed=1
  fi
  test "$cleanup_failed" = '0' || exit 1
  exit "$original_status"
}
trap cleanup_backup_smoke EXIT

test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="
    SELECT COUNT(*) FROM information_schema.SCHEMATA
    WHERE SCHEMA_NAME = '$BACKUP_VERIFY_SCHEMA';")" = '0'
sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="CREATE DATABASE \`$BACKUP_VERIFY_SCHEMA\`
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
BACKUP_VERIFY_SCHEMA_CREATED=1

test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="SELECT ACCOUNT_LOCKED FROM mysql.user
    WHERE User = 'ruoyi_backup' AND Host = '127.0.0.1';")" = 'Y'
sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="ALTER USER 'ruoyi_backup'@'127.0.0.1' ACCOUNT UNLOCK;"
BACKUP_ACCOUNT_RELOCK_REQUIRED=1

sudo mysqldump --defaults-extra-file="$MYSQL_BACKUP_CNF" \
  --protocol=TCP --single-transaction --skip-lock-tables \
  --skip-add-locks --set-gtid-purged=OFF \
  --skip-routines --skip-events --triggers \
  --hex-blob --no-tablespaces --default-character-set=utf8mb4 \
  "$DB_SCHEMA" > "$BACKUP_SMOKE_SQL"
test -s "$BACKUP_SMOKE_SQL"
sha256sum "$BACKUP_SMOKE_SQL" \
  | tee "$BACKUP_SMOKE_ROOT/schema-sha256.txt"

sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --database="$BACKUP_VERIFY_SCHEMA" < "$BACKUP_SMOKE_SQL"
test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --database="$BACKUP_VERIFY_SCHEMA" --batch --skip-column-names \
  --execute="SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE';")" = '69'
sudo mysqlcheck --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --check "$BACKUP_VERIFY_SCHEMA" \
  | tee "$BACKUP_SMOKE_ROOT/mysqlcheck.txt"

sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="DROP DATABASE \`$BACKUP_VERIFY_SCHEMA\`;"
BACKUP_VERIFY_SCHEMA_CREATED=0
rm -f "$BACKUP_SMOKE_SQL"
test ! -e "$BACKUP_SMOKE_SQL"
sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="ALTER USER 'ruoyi_backup'@'127.0.0.1' ACCOUNT LOCK;"
test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="SELECT ACCOUNT_LOCKED FROM mysql.user
    WHERE User = 'ruoyi_backup' AND Host = '127.0.0.1';")" = 'Y'
BACKUP_ACCOUNT_RELOCK_REQUIRED=0
trap - EXIT
unset BACKUP_VERIFY_SCHEMA BACKUP_SMOKE_SQL \
  BACKUP_VERIFY_SCHEMA_CREATED BACKUP_ACCOUNT_RELOCK_REQUIRED
~~~

恢复表数必须为 <code>69</code>，<code>mysqlcheck</code> 每张表均为 <code>OK</code>，临时 SQL 和隔离 schema 已删除。发布账号和备份账号分别由上述 <code>EXIT</code> 清理器兜底重锁，且成功路径均以 <code>mysql.user.ACCOUNT_LOCKED='Y'</code> 完成持久状态复核；后续只能在新的批准窗口短时解锁。

## 6. Redis 配置与验证

Redis 使用专用实例或专用逻辑库，绑定受控地址，启用 ACL、持久化和容量门禁。以下配置是自管 Redis 的基线；受控远程 Redis 通过服务控制面应用等效策略，不在应用主机执行 <code>systemctl</code>：

~~~text
bind 127.0.0.1
protected-mode yes
appendonly yes
appendfsync everysec
maxmemory-policy noeviction
~~~

自管本机实例先执行 <code>sudo systemctl enable --now redis-server</code>。随后设置非敏感连接变量；Redis 健康检查账号及其随机密码必须先记录到 <code>testcount/accounts.local.md</code>，密码只通过 <code>--askpass</code> 的隐藏提示输入：

~~~bash
export REDIS_HEALTH_HOST='<APPROVED_REDIS_HOST>'
export REDIS_HEALTH_PORT='<APPROVED_REDIS_PORT>'
export REDIS_HEALTH_USERNAME='<APPROVED_REDIS_HEALTH_USER>'
export REDIS_HEALTH_TLS='false'
export REDIS_HEALTH_CA=''

case "$REDIS_HEALTH_HOST:$REDIS_HEALTH_USERNAME" in
  *'<'*|*'>'*|:|*:)
    printf 'Redis health connection variables are invalid\n' >&2
    exit 1
    ;;
esac
[[ "$REDIS_HEALTH_PORT" =~ ^[0-9]+$ ]]
test "$REDIS_HEALTH_PORT" -ge 1
test "$REDIS_HEALTH_PORT" -le 65535

REDIS_TLS_ARGS=()
case "$REDIS_HEALTH_TLS" in
  true)
    case "$REDIS_HEALTH_CA" in
      /*) ;;
      *) printf 'REDIS_HEALTH_CA must be absolute when TLS is enabled\n' >&2; exit 1 ;;
    esac
    test -r "$REDIS_HEALTH_CA"
    REDIS_TLS_ARGS=(--tls --cacert "$REDIS_HEALTH_CA")
    ;;
  false) ;;
  *) printf 'REDIS_HEALTH_TLS must be true or false\n' >&2; exit 1 ;;
esac

redis-cli -h "$REDIS_HEALTH_HOST" -p "$REDIS_HEALTH_PORT" \
  --user "$REDIS_HEALTH_USERNAME" "${REDIS_TLS_ARGS[@]}" --askpass PING \
  | tee "$EVIDENCE_ROOT/redis-ping.txt"
grep -qx 'PONG' "$EVIDENCE_ROOT/redis-ping.txt"

# 以下两项使用受控 Redis 运维账号；应用账号不授予 INFO 或 CONFIG 权限。
REDIS_PERSISTENCE_RAW="$(mktemp)"
REDIS_MEMORY_POLICY_RAW="$(mktemp)"
trap 'rm -f "${REDIS_PERSISTENCE_RAW:-}" "${REDIS_MEMORY_POLICY_RAW:-}"' EXIT
redis-cli --raw -h "$REDIS_HEALTH_HOST" -p "$REDIS_HEALTH_PORT" \
  --user "$REDIS_HEALTH_USERNAME" "${REDIS_TLS_ARGS[@]}" \
  --askpass INFO persistence > "$REDIS_PERSISTENCE_RAW"
awk -F ':' '
  {
    sub(/\r$/, "", $0)
    if ($1 == "aof_enabled" || $1 == "aof_last_bgrewrite_status" ||
        $1 == "aof_last_write_status") {
      if (seen[$1]++) invalid = 1
      value[$1] = $2
    }
  }
  END {
    if (invalid || !("aof_enabled" in value) ||
        !("aof_last_bgrewrite_status" in value) ||
        !("aof_last_write_status" in value)) exit 1
    printf "aof_enabled=%s\n", value["aof_enabled"]
    printf "aof_last_bgrewrite_status=%s\n", value["aof_last_bgrewrite_status"]
    printf "aof_last_write_status=%s\n", value["aof_last_write_status"]
  }
' "$REDIS_PERSISTENCE_RAW" | tee "$EVIDENCE_ROOT/redis-persistence.txt"

redis-cli --raw -h "$REDIS_HEALTH_HOST" -p "$REDIS_HEALTH_PORT" \
  --user "$REDIS_HEALTH_USERNAME" "${REDIS_TLS_ARGS[@]}" \
  --askpass CONFIG GET maxmemory-policy > "$REDIS_MEMORY_POLICY_RAW"
awk '
  { sub(/\r$/, "", $0) }
  NR == 1 && $0 != "maxmemory-policy" { invalid = 1 }
  NR == 2 { policy = $0 }
  END {
    if (invalid || NR != 2 || policy == "") exit 1
    printf "maxmemory-policy=%s\n", policy
  }
' "$REDIS_MEMORY_POLICY_RAW" | tee "$EVIDENCE_ROOT/redis-memory-policy.txt"
rm -f "$REDIS_PERSISTENCE_RAW" "$REDIS_MEMORY_POLICY_RAW"
trap - EXIT
unset REDIS_PERSISTENCE_RAW REDIS_MEMORY_POLICY_RAW

test "$(cat "$EVIDENCE_ROOT/redis-persistence.txt")" = \
  $'aof_enabled=1\naof_last_bgrewrite_status=ok\naof_last_write_status=ok'
grep -Fqx 'maxmemory-policy=noeviction' \
  "$EVIDENCE_ROOT/redis-memory-policy.txt"
~~~

验收结果必须逐字证明 <code>PONG</code>、<code>aof_enabled=1</code>、最近 AOF 重写/写入均为 <code>ok</code>，以及 <code>maxmemory-policy=noeviction</code>。托管服务不开放 <code>INFO</code> 或 <code>CONFIG</code> 时，从批准的服务控制面导出带签名或审计引用的原始状态，再由受控脚本转换为上述四行固定证据；禁止手写结果，也不得为通过检查而扩大应用账号权限。

## 7. 应用环境变量与配置

### 7.1 安装外部配置

~~~bash
sudo install -m 0640 -o root -g ruoyi \
  "$REPO_ROOT/deployment/config/application.yml" /etc/ruoyi/application.yml
sudo install -m 0640 -o root -g ruoyi \
  "$REPO_ROOT/deployment/config/application-druid.yml" \
  /etc/ruoyi/application-druid.yml
sudo install -m 0600 -o root -g root \
  "$REPO_ROOT/deployment/config/ruoyi.env.example" /etc/ruoyi/ruoyi.env
~~~

由密钥系统或 root 在 <code>/etc/ruoyi/ruoyi.env</code> 中设置以下变量。示例只展示变量名：

~~~dotenv
RUOYI_DB_URL=<JDBC_URL>
RUOYI_DB_USERNAME=<APP_DATABASE_ACCOUNT>
RUOYI_DB_PASSWORD=<APP_DATABASE_SECRET>
RUOYI_TOKEN_SECRET=<RANDOM_TOKEN_SECRET>
RUOYI_BOOTSTRAP_ADMIN_ENABLED=false
RUOYI_BOOTSTRAP_ADMIN_PASSWORD=
SPRING_DATA_REDIS_HOST=<REDIS_HOST>
SPRING_DATA_REDIS_PORT=<REDIS_PORT>
SPRING_DATA_REDIS_DATABASE=<REDIS_DATABASE>
SPRING_DATA_REDIS_USERNAME=<REDIS_ACL_USER>
SPRING_DATA_REDIS_PASSWORD=<REDIS_SECRET>
SPRING_DATA_REDIS_SSL_ENABLED=<true_OR_false>
DRUID_MONITOR_USERNAME=<RANDOM_MONITOR_ACCOUNT>
DRUID_MONITOR_PASSWORD=<RANDOM_MONITOR_SECRET>
DRUID_MONITOR_ENABLED=false
RUOYI_LOG_PATH=/var/lib/ruoyi/logs
RUOYI_PROFILE=/var/lib/ruoyi/uploadPath
RUOYI_MANAGEMENT_PORT=18080
FLOWABLE_RUNTIME_DEPLOYMENT_TOPOLOGY=<SINGLE_NODE_OR_MULTI_NODE>
FLOWABLE_RUNTIME_EXECUTOR_TOPOLOGY=<DISABLED_OR_SINGLE_NODE_OR_DATABASE_LOCKED_MULTI_NODE>
FLOWABLE_RUNTIME_NODE_ID=<APPROVED_STABLE_NODE_ID>
FLOWABLE_RUNTIME_APPROVAL_REFERENCE=<APPROVED_RUNTIME_TOPOLOGY_REFERENCE>
FLOWABLE_RUNTIME_CAPACITY_APPROVAL_REFERENCE=<APPROVED_CAPACITY_REVIEW_REFERENCE>
FLOWABLE_ASYNC_EXECUTOR_ACTIVATE=<true_OR_false>
FLOWABLE_ASYNC_HISTORY_EXECUTOR_ACTIVATE=<true_OR_false>
FLOWABLE_ATTACHMENT_STORAGE_MODE=<LOCAL_PERSISTENT_OR_SHARED_FILESYSTEM>
FLOWABLE_ATTACHMENT_STORAGE_ID=<APPROVED_SHARED_STORAGE_ID_OR_EMPTY_FOR_LOCAL>
FLOWABLE_ATTACHMENT_CLEANUP_LOCK_MODE=MYSQL_ADVISORY
FLOWABLE_ATTACHMENT_CLEANUP_LOCK_NAME=approvaplat:wf-attachment-cleanup
FLOWABLE_ATTACHMENT_MAX_SIZE=<BYTES>
FLOWABLE_ATTACHMENT_MAX_TEMPORARY_COUNT=<COUNT>
FLOWABLE_ATTACHMENT_MAX_TEMPORARY_BYTES=<BYTES>
FLOWABLE_ATTACHMENT_MAX_TOTAL_BYTES=<BYTES>
FLOWABLE_ATTACHMENT_MIN_FREE_BYTES=<BYTES>
FLOWABLE_ATTACHMENT_CLEANUP_BATCH_SIZE=<COUNT>
FLOWABLE_ATTACHMENT_CLEANUP_RETRY_INITIAL_DELAY=<ISO_8601_DURATION>
FLOWABLE_ATTACHMENT_CLEANUP_RETRY_MAX_DELAY=<ISO_8601_DURATION>
FLOWABLE_ATTACHMENT_CLEANUP_INITIAL_DELAY=<ISO_8601_DURATION>
FLOWABLE_ATTACHMENT_CLEANUP_FIXED_DELAY=<ISO_8601_DURATION>
FLOWABLE_RUNTIME_METRICS_REFRESH_INITIAL_DELAY=<ISO_8601_DURATION>
FLOWABLE_RUNTIME_METRICS_REFRESH_INTERVAL=<ISO_8601_DURATION>
FLOWABLE_RUNTIME_METRICS_SNAPSHOT_MAX_AGE=<ISO_8601_DURATION>
~~~

~~~bash
sudo chown root:root /etc/ruoyi/ruoyi.env
sudo chmod 0600 /etc/ruoyi/ruoyi.env
sudo test "$(stat -c '%a' /etc/ruoyi/ruoyi.env)" = "600"
~~~

安装门禁：

- <code>RUOYI_DB_USERNAME</code> 使用 <code>ruoyi_app</code> 等最小权限应用账号。
- 同机 MySQL 可使用仓库模板中的本机 JDBC URL；远程 MySQL 使用 <code>sslMode=VERIFY_IDENTITY</code> 和受控 CA。
- <code>RUOYI_TOKEN_SECRET</code> 使用至少 64 字节的独立高熵随机值，不与数据库或 Redis 密码复用。
- Redis 数据库索引由环境隔离，生产实例不与测试共享逻辑库；远程 Redis 设置 <code>SPRING_DATA_REDIS_SSL_ENABLED=true</code>，并把批准 CA 安装到 Java 系统信任库。
- Redis 应用 ACL 账号只授予应用实际使用的命令，不因运维检查增加 <code>INFO</code> 或 <code>CONFIG</code>；第 6 节健康检查的主机、端口和 TLS 模式必须与应用配置一致。
- 主环境文件中的 <code>RUOYI_BOOTSTRAP_ADMIN_ENABLED=false</code> 且 <code>RUOYI_BOOTSTRAP_ADMIN_PASSWORD</code> 为空；一次性初始化只通过下一节的临时 root 文件覆盖。
- <code>FLOWABLE_RUNTIME_CAPACITY_APPROVAL_REFERENCE</code> 必须是非空真实容量评审编号；空值、占位符或未闭合审批会由生产启动门禁拒绝。
- 首次启动固定使用 <code>SINGLE_NODE + DISABLED</code> 且两个 executor 开关均为 <code>false</code>。启用 executor 时，拓扑枚举、两个实际开关、稳定节点 ID 和审批引用必须一致；多节点还必须使用 <code>SHARED_FILESYSTEM</code>、真实共享存储 ID 和 <code>MYSQL_ADVISORY</code> 清理锁。
- 容量、清理退避和指标周期必须落在应用校验范围内，且 <code>FLOWABLE_RUNTIME_METRICS_SNAPSHOT_MAX_AGE</code> 严格大于刷新间隔。<code>flowable.database-schema-update</code> 始终保持 <code>false</code>。

## 8. 构建、安装与启动

发布构建应在受控构建机完成并通过完整门禁：

~~~bash
test -z "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal)"

cd "$REPO_ROOT/back"
mvn clean verify -Pflowable-it

cd "$REPO_ROOT/vite"
npm ci
npm run build:prod

cd "$REPO_ROOT"
bash deployment/scripts/tests/workflow-release-gate-test.sh \
  | tee "$EVIDENCE_ROOT/release-gate-test.log"
~~~

<code>flowable-it</code> 使用随机 <code>_flowable_it</code> schema、独立 Redis DB、仅具 DML 的应用测试账号，以及只具该隔离 schema <code>ALTER</code> 权限的故障注入账号。构建前必须提供 <code>FLOWABLE_IT_*</code> 与 <code>FLOWABLE_IT_DDL_*</code> 环境变量；两个账号及随机密码都先记录到 <code>testcount/accounts.local.md</code>。构建门禁不连接生产 <code>ry-vue</code>。

确认正式资产存在并计算源码构建产物哈希：

~~~bash
test -r "$REPO_ROOT/back/ruoyi-admin/target/ruoyi-admin.jar"
test -r "$REPO_ROOT/vite/dist/index.html"
sha256sum "$REPO_ROOT/back/ruoyi-admin/target/ruoyi-admin.jar" \
  | tee "$EVIDENCE_ROOT/backend-sha256.txt"
find "$REPO_ROOT/vite/dist" -type f -print0 \
  | sort -z | xargs -0 sha256sum \
  > "$EVIDENCE_ROOT/frontend-sha256.txt"
~~~

把已验证资产、正式 SQL、部署模板和门禁脚本安装到不可变版本目录。全新安装虽然已按第 5 节执行基线和幂等 <code>.3</code> 迁移，但本次冻结发布包仍必须保留唯一的 <code>.3</code> 增量顺序。这使同一候选包可用于存量 schema，并由幂等执行结果证明全新 schema 无元数据漂移；禁止为全新安装生成例外空清单：

~~~bash
test ! -e "$RELEASE_DIR"
test ! -L "$RELEASE_DIR"
sudo install -d -m 0755 -o root -g root "$RELEASE_DIR"
sudo install -m 0640 -o root -g ruoyi \
  "$REPO_ROOT/back/ruoyi-admin/target/ruoyi-admin.jar" \
  "$RELEASE_DIR/ruoyi-admin.jar"
sudo install -d -m 0755 -o root -g root "$RELEASE_DIR/frontend"
sudo cp -a "$REPO_ROOT/vite/dist/." "$RELEASE_DIR/frontend/"
sudo cp -a "$REPO_ROOT/back/sql" "$RELEASE_DIR/sql"
sudo cp -a "$REPO_ROOT/deployment" "$RELEASE_DIR/deployment"
printf '%s\n' 'flowable/business/8.0.0.3__workflow_attachment_cleanup_retry.sql' \
  | sudo tee "$RELEASE_DIR/sql/release-order.txt" >/dev/null
sudo chown root:ruoyi "$RELEASE_DIR/sql/release-order.txt"
sudo chmod 0640 "$RELEASE_DIR/sql/release-order.txt"

GIT_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD)"
BUILD_TIMESTAMP_UTC="$APPROVED_BUILD_TIMESTAMP_UTC"
[[ "$GIT_COMMIT" =~ ^[0-9a-f]{40}$ ]]
{
  printf 'format_version=1\n'
  printf 'release_id=%s\n' "$RELEASE_ID"
  printf 'git_commit=%s\n' "$GIT_COMMIT"
  printf 'previous_release_id=%s\n' "$PREVIOUS_RELEASE_ID"
  printf 'built_at_utc=%s\n' "$BUILD_TIMESTAMP_UTC"
} | sudo tee "$RELEASE_DIR/RELEASE-METADATA" >/dev/null

test -z "$(sudo find "$RELEASE_DIR" -type l -print -quit)"
sudo chown -R root:root "$RELEASE_DIR/frontend"
sudo find "$RELEASE_DIR/frontend" -type d -exec chmod 0755 {} +
sudo find "$RELEASE_DIR/frontend" -type f -exec chmod 0644 {} +
sudo chown -R root:ruoyi \
  "$RELEASE_DIR/ruoyi-admin.jar" \
  "$RELEASE_DIR/sql" \
  "$RELEASE_DIR/deployment" \
  "$RELEASE_DIR/RELEASE-METADATA"
sudo find "$RELEASE_DIR/sql" "$RELEASE_DIR/deployment" \
  -type d -exec chmod 0750 {} +
sudo find "$RELEASE_DIR/sql" "$RELEASE_DIR/deployment" \
  -type f -exec chmod 0640 {} +
sudo chmod 0640 "$RELEASE_DIR/ruoyi-admin.jar" \
  "$RELEASE_DIR/RELEASE-METADATA"

# SHA256SUMS 严格覆盖包内每个普通文件，自身不进入清单。
sudo bash -c '
  set -euo pipefail
  cd "$1"
  find . -type f ! -name SHA256SUMS -print0 \
    | sort -z \
    | xargs -0 sha256sum \
    > SHA256SUMS
' _ "$RELEASE_DIR"
sudo chown root:ruoyi "$RELEASE_DIR/SHA256SUMS"
sudo chmod 0640 "$RELEASE_DIR/SHA256SUMS"
test "$(sha256sum "$RELEASE_DIR/SHA256SUMS" | awk '{print $1}')" = \
  "$APPROVED_MANIFEST_SHA256"

# 只读门禁验证全部文件、版本元数据、批准锚点、固定增量清单和符号链接边界。
RELEASE_GATE_ARGS=(
  --release-dir "$RELEASE_DIR"
  --release-id "$RELEASE_ID"
  --approved-git-commit "$APPROVED_GIT_COMMIT"
  --approved-manifest-sha256 "$APPROVED_MANIFEST_SHA256"
)
if test "$PREVIOUS_RELEASE_ID" != 'NONE'; then
  RELEASE_GATE_ARGS+=(
    --previous-release-dir "$PREVIOUS_RELEASE_DIR"
    --previous-release-id "$PREVIOUS_RELEASE_ID"
    --approved-previous-git-commit "$APPROVED_PREVIOUS_GIT_COMMIT"
    --approved-previous-manifest-sha256 \
      "$APPROVED_PREVIOUS_MANIFEST_SHA256"
  )
fi
sudo bash \
  "$RELEASE_DIR/deployment/scripts/workflow-release-gate.sh" \
  preflight "${RELEASE_GATE_ARGS[@]}" \
  | tee "$EVIDENCE_ROOT/release-preflight.txt"

sudo -u ruoyi test -r "$RELEASE_DIR/ruoyi-admin.jar"
sudo -u "$NGINX_WORKER_USER" test -r "$RELEASE_DIR/frontend/index.html"
if sudo -u "$NGINX_WORKER_USER" test -r "$RELEASE_DIR/ruoyi-admin.jar"; then
  printf 'Nginx worker must not read backend assets\n' >&2
  exit 1
fi

test ! -e "$ACTIVE_RELEASE_LINK"
test ! -L "$ACTIVE_RELEASE_LINK"
test "$(stat -c '%d' "$RELEASE_ROOT")" = \
  "$(stat -c '%d' "$(dirname "$ACTIVE_RELEASE_LINK")")"
test ! -e "$RELEASE_ROOT/.current-$RELEASE_ID"
test ! -L "$RELEASE_ROOT/.current-$RELEASE_ID"
sudo ln -s "$RELEASE_DIR" "$RELEASE_ROOT/.current-$RELEASE_ID"
sudo mv -Tf "$RELEASE_ROOT/.current-$RELEASE_ID" "$ACTIVE_RELEASE_LINK"
test -L "$ACTIVE_RELEASE_LINK"
test "$(readlink -f "$ACTIVE_RELEASE_LINK")" = "$RELEASE_DIR"
sudo -u "$NGINX_WORKER_USER" test -r \
  "$ACTIVE_RELEASE_LINK/frontend/index.html"
unset GIT_COMMIT BUILD_TIMESTAMP_UTC
~~~

<code>/var/lib/ruoyi</code>、<code>releases</code> 与单个版本目录的 <code>0755</code> 仅用于路径穿越；前端目录和静态文件分别为 <code>0755/0644 root:root</code>。后端 JAR、SQL、部署模板、门禁脚本和元数据保持 <code>0640 root:ruoyi</code>，Nginx worker 的反向权限检查必须失败，因此不会获得后端资产读取权限。<code>workflow-release-gate.sh preflight</code> 是只读检查，不连接数据库、Redis，不启停服务，也不修改 <code>current</code> 链接；任何非零退出码立即中止安装。

渲染 TLS Nginx 模板、安装 systemd unit 并启动应用。<code>envsubst</code> 只替换列出的三个发布变量；渲染后还要逐项确认 Nginx 自身的运行时变量仍然存在：

~~~bash
sudo install -m 0644 \
  "$REPO_ROOT/deployment/systemd/ruoyi-backend.service" \
  /etc/systemd/system/ruoyi-backend.service

NGINX_RENDERED_CONFIG="$(mktemp)"
trap 'rm -f "$NGINX_RENDERED_CONFIG"' EXIT
envsubst '$RUOYI_SERVER_NAME $RUOYI_TLS_CERTIFICATE $RUOYI_TLS_CERTIFICATE_KEY' \
  < "$REPO_ROOT/deployment/nginx/ruoyi.conf" \
  > "$NGINX_RENDERED_CONFIG"
! grep -Eq '\$\{RUOYI_(SERVER_NAME|TLS_CERTIFICATE|TLS_CERTIFICATE_KEY)\}' \
  "$NGINX_RENDERED_CONFIG"
for nginx_variable in \
  '$host' '$request_uri' '$remote_addr' \
  '$proxy_add_x_forwarded_for' '$scheme' '$uri'
do
  grep -Fq "$nginx_variable" "$NGINX_RENDERED_CONFIG"
done
sudo install -m 0644 -o root -g root "$NGINX_RENDERED_CONFIG" \
  /etc/nginx/sites-available/ruoyi.conf
rm -f "$NGINX_RENDERED_CONFIG"
trap - EXIT
sudo ln -sfn /etc/nginx/sites-available/ruoyi.conf \
  /etc/nginx/sites-enabled/ruoyi.conf

sudo systemctl daemon-reload
sudo nginx -t
# 全新环境在最终 readiness 与 Prometheus 证据通过前不开放入口流量。
sudo systemctl stop nginx
sudo systemctl enable nginx

# 客户端文件与 --askpass 均不把密码放入命令行、证据或 shell 历史。
sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --connect-timeout=5 --batch --skip-column-names \
  --execute='SELECT 1;' \
  | tee "$EVIDENCE_ROOT/mysql-connectivity.txt"
grep -qx '1' "$EVIDENCE_ROOT/mysql-connectivity.txt"
redis-cli -h "$REDIS_HEALTH_HOST" -p "$REDIS_HEALTH_PORT" \
  --user "$REDIS_HEALTH_USERNAME" "${REDIS_TLS_ARGS[@]}" --askpass PING \
  | tee "$EVIDENCE_ROOT/redis-connectivity.txt"
grep -qx 'PONG' "$EVIDENCE_ROOT/redis-connectivity.txt"
~~~

MySQL 与 Redis 可以是本机服务，也可以是受控远程服务，因此 systemd unit 不声明对特定本机 unit 的依赖。若换过终端，执行启动段前先重新设置第 6 节的非敏感 Redis 变量和 TLS 参数。

### 8.1 一次性管理员初始化

基础 SQL 中固定的 <code>admin</code> 处于停用且待初始化状态。先确认门禁，查询结果只输出 <code>PASS/FAIL</code>，不输出密码字段：

~~~bash
sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --database="$DB_SCHEMA" --batch --skip-column-names --raw <<'SQL' \
  | tee "$EVIDENCE_ROOT/admin-bootstrap-precheck.txt"
SELECT CASE
  WHEN COUNT(*) = 1
   AND MIN(status = '1') = 1
   AND MIN(del_flag = '0') = 1
   AND MIN(password = '!RUOYI_BOOTSTRAP_REQUIRED!') = 1
  THEN 'PASS' ELSE 'FAIL'
END
FROM sys_user
WHERE user_id = 1 AND user_name = 'admin';
SQL
grep -qx 'PASS' "$EVIDENCE_ROOT/admin-bootstrap-precheck.txt"
~~~

生成满足 20-128 位复杂度门禁的随机密码后，必须先写入本机忽略文件并同步批准的密钥系统，再允许应用使用。以下命令不显示密码；执行期间禁止启用 <code>set -x</code>：

~~~bash
set +x
BOOTSTRAP_CREATED_AT="$(TZ=Asia/Shanghai date '+%F %T %Z')"
BOOTSTRAP_ADMIN_PASSWORD="Aa1!$(openssl rand -hex 24)"
test -f "$REPO_ROOT/testcount/accounts.local.md"
git -C "$REPO_ROOT" check-ignore -q testcount/accounts.local.md

{
  printf '\n## ApprovaPlat 初始管理员\n\n'
  printf -- '- 创建/重置时间：%s\n' "$BOOTSTRAP_CREATED_AT"
  printf -- '- 环境与主机：生产全新安装，%s\n' "$RUOYI_SERVER_NAME"
  printf -- '- 账号：admin\n'
  printf -- '- 密码：%s\n' "$BOOTSTRAP_ADMIN_PASSWORD"
  printf -- '- 权限范围：系统管理员\n'
  printf -- '- 用途：首次登录与后续受控轮换\n'
  printf -- '- 状态：待一次性初始化\n'
  printf -- '- 备注：发布 %s；不得进入 Git、日志或证据目录\n' "$RELEASE_ID"
} >> "$REPO_ROOT/testcount/accounts.local.md"
chmod 0600 "$REPO_ROOT/testcount/accounts.local.md"
~~~

确认同一密码已进入批准的密钥系统后，通过单独的 root 文件仅启动一次初始化器。临时文件在任何失败分支都会被清空并删除：

~~~bash
BOOTSTRAP_ENV_SOURCE="$(mktemp)"
trap 'rm -f "${BOOTSTRAP_ENV_SOURCE:-}"' EXIT
umask 077
printf 'RUOYI_BOOTSTRAP_ADMIN_ENABLED=true\n' \
  > "$BOOTSTRAP_ENV_SOURCE"
printf 'RUOYI_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$BOOTSTRAP_ADMIN_PASSWORD" \
  >> "$BOOTSTRAP_ENV_SOURCE"
sudo install -m 0600 -o root -g root "$BOOTSTRAP_ENV_SOURCE" \
  /etc/ruoyi/ruoyi-bootstrap.env
rm -f "$BOOTSTRAP_ENV_SOURCE"
unset BOOTSTRAP_ENV_SOURCE BOOTSTRAP_ADMIN_PASSWORD
trap - EXIT

sudo install -d -m 0755 -o root -g root \
  /etc/systemd/system/ruoyi-backend.service.d
printf '[Service]\nEnvironmentFile=/etc/ruoyi/ruoyi-bootstrap.env\n' \
  | sudo tee \
    /etc/systemd/system/ruoyi-backend.service.d/bootstrap-admin.conf \
    >/dev/null
sudo chmod 0644 \
  /etc/systemd/system/ruoyi-backend.service.d/bootstrap-admin.conf

trap 'sudo systemctl stop ruoyi-backend >/dev/null 2>&1 || true; sudo install -m 0600 /dev/null /etc/ruoyi/ruoyi-bootstrap.env; sudo rm -f /etc/ruoyi/ruoyi-bootstrap.env /etc/systemd/system/ruoyi-backend.service.d/bootstrap-admin.conf; sudo systemctl daemon-reload' EXIT
sudo systemctl daemon-reload
sudo systemctl enable --now ruoyi-backend
sudo systemctl is-active ruoyi-backend
sudo journalctl -u ruoyi-backend --since '-5 minutes' --no-pager \
  | tee "$EVIDENCE_ROOT/admin-bootstrap-startup.log"

sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --database="$DB_SCHEMA" --batch --skip-column-names --raw <<'SQL' \
  | tee "$EVIDENCE_ROOT/admin-bootstrap-state.txt"
SELECT CASE
  WHEN COUNT(*) = 1
   AND MIN(status = '0') = 1
   AND MIN(del_flag = '0') = 1
   AND MIN(CHAR_LENGTH(password) = 60) = 1
   AND MIN(LEFT(password, 4) IN ('$2a$', '$2b$', '$2y$')) = 1
  THEN 'PASS' ELSE 'FAIL'
END
FROM sys_user
WHERE user_id = 1 AND user_name = 'admin';
SQL
grep -qx 'PASS' "$EVIDENCE_ROOT/admin-bootstrap-state.txt"
~~~

状态与 BCrypt 格式门禁通过后立即停止服务，清空并删除临时明文文件和 drop-in；主环境文件必须仍是关闭且空密码状态：

~~~bash
sudo systemctl stop ruoyi-backend
sudo install -m 0600 /dev/null /etc/ruoyi/ruoyi-bootstrap.env
sudo rm -f /etc/ruoyi/ruoyi-bootstrap.env \
  /etc/systemd/system/ruoyi-backend.service.d/bootstrap-admin.conf
sudo grep -qx 'RUOYI_BOOTSTRAP_ADMIN_ENABLED=false' /etc/ruoyi/ruoyi.env
sudo grep -qx 'RUOYI_BOOTSTRAP_ADMIN_PASSWORD=' /etc/ruoyi/ruoyi.env
sudo systemctl daemon-reload
trap - EXIT

sudo systemctl start ruoyi-backend

# 轮询真实 Actuator JSON；只有 liveness/readiness 均为 UP 才保留最终响应。
wait_actuator_up() {
  local endpoint_path="$1"
  local target_file="$2"
  local response_file
  local attempt

  response_file="$(mktemp)"
  trap 'rm -f "${response_file:-}"' RETURN
  for attempt in $(seq 1 60); do
    if curl -fsS --max-time 5 \
      "http://127.0.0.1:18080$endpoint_path" > "$response_file" \
      && python3 - "$response_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    payload = json.load(source)
if not isinstance(payload, dict) or payload.get("status") != "UP":
    raise SystemExit(1)
PY
    then
      mv "$response_file" "$target_file"
      trap - RETURN
      return 0
    fi
    sleep 5
  done
  printf 'Actuator endpoint did not become UP: %s\n' "$endpoint_path" >&2
  return 1
}

wait_actuator_up \
  /actuator/health/liveness "$EVIDENCE_ROOT/backend-liveness.json"
wait_actuator_up \
  /actuator/health/readiness "$EVIDENCE_ROOT/backend-readiness.json"
curl -fsS --retry 12 --retry-all-errors --retry-delay 5 --max-time 5 \
  http://127.0.0.1:18080/actuator/prometheus \
  > "$EVIDENCE_ROOT/prometheus-scrape.txt"
awk '
  $1 == "workflow_runtime_metrics_snapshot_available" {
    snapshot_count++; if (NF != 2 || $2 !~ /^1([.]0+)?$/) invalid = 1
  }
  $1 == "workflow_attachment_cleanup_lock_degraded" {
    lock_count++; if (NF != 2 || $2 !~ /^0([.]0+)?$/) invalid = 1
  }
  END { exit(!invalid && snapshot_count == 1 && lock_count == 1 ? 0 : 1) }
' "$EVIDENCE_ROOT/prometheus-scrape.txt"

# 技术门禁通过后才启动 Nginx；生产负载均衡仍保持摘流，直至真实登录和只读验收完成。
sudo nginx -t
sudo systemctl start nginx
sudo systemctl is-active nginx \
  | tee "$EVIDENCE_ROOT/nginx-service-status.txt"
sudo systemctl is-active nginx ruoyi-backend \
  | tee "$EVIDENCE_ROOT/service-status.txt"
curl -fsS http://127.0.0.1:8080/ \
  | tee "$EVIDENCE_ROOT/backend-after-bootstrap.txt" >/dev/null
grep -Fq '后台管理框架' "$EVIDENCE_ROOT/backend-after-bootstrap.txt"

printf -- '- 状态更新：%s，已启用且一次性环境变量已移除\n' \
  "$(TZ=Asia/Shanghai date '+%F %T %Z')" \
  >> "$REPO_ROOT/testcount/accounts.local.md"
~~~

最后通过 <code>https://${RUOYI_SERVER_NAME}/</code> 的真实登录页，使用 <code>testcount/accounts.local.md</code> 中的 <code>admin</code> 随机密码和页面验证码完成一次全新登录，并打开个人信息页。登录后执行以下只读审计门禁；证据只包含 <code>PASS/FAIL</code>，不得保存密码、Token、Cookie、验证码或 BCrypt 哈希：

~~~bash
sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --database="$DB_SCHEMA" --batch --skip-column-names --raw <<'SQL' \
  | tee "$EVIDENCE_ROOT/admin-real-login.txt"
SELECT CASE WHEN EXISTS (
  SELECT 1
  FROM sys_logininfor login_record
  JOIN sys_user admin_user
    ON admin_user.user_id = 1
   AND admin_user.user_name = login_record.user_name
  WHERE login_record.user_name = 'admin'
    AND login_record.status = '0'
    AND login_record.login_time >= admin_user.pwd_update_date
) THEN 'PASS' ELSE 'FAIL' END;
SQL
grep -qx 'PASS' "$EVIDENCE_ROOT/admin-real-login.txt"
~~~

初始化器关闭后的重启、真实密码校验、验证码链路、Token 创建、登录审计和个人信息加载必须全部成功，才能继续只读验收。启动日志应确认应用完整启动，且不出现自动建表、数据库鉴权、Redis、附件目录或 Flowable schema 错误。

## 9. 只读验收

### 9.1 正式 SQL 验收

~~~bash
VERIFY_LOG="$EVIDENCE_ROOT/database-verify.tsv"
: > "$VERIFY_LOG"

for sql_file in \
  "$FLOWABLE_SQL_ROOT/verify/8.0.0__verify.sql" \
  "$FLOWABLE_SQL_ROOT/verify/8.0.0__verify_workflow_business.sql" \
  "$FLOWABLE_SQL_ROOT/verify/8.0.0__verify_workflow_menu.sql"
do
  printf 'FILE\t%s\n' "$(basename "$sql_file")" \
    | tee -a "$VERIFY_LOG"
  sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
    --database="$DB_SCHEMA" --batch --raw < "$sql_file" \
    | tee -a "$VERIFY_LOG"
done

if grep -Eq '(^|[[:space:]])FAIL([[:space:]]|$)' "$VERIFY_LOG"; then
  printf 'database verification failed\n' >&2
  exit 1
fi
~~~

三份脚本的每一项结果都必须为 <code>PASS</code>。

### 9.2 服务与应用只读检查

~~~bash
curl -fsS http://127.0.0.1:8080/ \
  | tee "$EVIDENCE_ROOT/backend-health.txt" >/dev/null
grep -Fq '后台管理框架' "$EVIDENCE_ROOT/backend-health.txt"
for docs_path in /v3/api-docs /swagger-ui.html
do
  test "$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:8080$docs_path")" = '404'
done
curl -fsSI --resolve "$RUOYI_SERVER_NAME:443:127.0.0.1" \
  "https://$RUOYI_SERVER_NAME/" \
  | tee "$EVIDENCE_ROOT/nginx-health.txt"
for public_docs_path in /prod-api/v3/api-docs /prod-api/swagger-ui.html
do
  test "$(curl -sS -o /dev/null -w '%{http_code}' \
    --resolve "$RUOYI_SERVER_NAME:443:127.0.0.1" \
    "https://$RUOYI_SERVER_NAME$public_docs_path")" = '404'
done
test "$(curl -sS -o /dev/null -w '%{http_code}' \
  --resolve "$RUOYI_SERVER_NAME:80:127.0.0.1" \
  "http://$RUOYI_SERVER_NAME/")" = '301'

sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --database="$DB_SCHEMA" --batch --raw --execute="
SELECT 'ACT_RU_JOB' AS table_name, COUNT(*) AS row_count FROM ACT_RU_JOB
UNION ALL SELECT 'ACT_RU_TIMER_JOB', COUNT(*) FROM ACT_RU_TIMER_JOB
UNION ALL SELECT 'ACT_RU_SUSPENDED_JOB', COUNT(*) FROM ACT_RU_SUSPENDED_JOB
UNION ALL SELECT 'ACT_RU_DEADLETTER_JOB', COUNT(*) FROM ACT_RU_DEADLETTER_JOB
UNION ALL SELECT 'ACT_RU_EXTERNAL_JOB', COUNT(*) FROM ACT_RU_EXTERNAL_JOB
UNION ALL SELECT 'ACT_RU_HISTORY_JOB', COUNT(*) FROM ACT_RU_HISTORY_JOB;" \
  | tee "$EVIDENCE_ROOT/flowable-jobs.tsv"

sudo -u ruoyi test -r "$ATTACHMENT_ROOT"
sudo -u ruoyi test -w "$ATTACHMENT_ROOT"
find "$ATTACHMENT_ROOT" -maxdepth 1 -type l -print \
  | tee "$EVIDENCE_ROOT/attachment-root-symlinks.txt"
test ! -s "$EVIDENCE_ROOT/attachment-root-symlinks.txt"

# 在隔离目录真实备份并恢复附件树，再比较每个文件的相对路径、大小和 SHA-256。
ATTACHMENT_RESTORE_ROOT="$(sudo mktemp -d /var/lib/ruoyi/attachment-restore.XXXXXXXX)"
ATTACHMENT_BACKUP_ARCHIVE="$EVIDENCE_ROOT/attachment-restore.tar"
case "$ATTACHMENT_RESTORE_ROOT" in
  /var/lib/ruoyi/attachment-restore.*) ;;
  *) printf 'unsafe attachment restore directory\n' >&2; exit 1 ;;
esac
cleanup_attachment_restore() {
  local original_status="$?"
  trap - EXIT
  sudo rm -rf -- "$ATTACHMENT_RESTORE_ROOT"
  rm -f -- "$ATTACHMENT_BACKUP_ARCHIVE"
  exit "$original_status"
}
trap cleanup_attachment_restore EXIT
sudo tar --xattrs --acls -cpf "$ATTACHMENT_BACKUP_ARCHIVE" \
  -C "$ATTACHMENT_ROOT" .
sudo tar --xattrs --acls -xpf "$ATTACHMENT_BACKUP_ARCHIVE" \
  -C "$ATTACHMENT_RESTORE_ROOT"

write_attachment_manifest() {
  local attachment_root="$1"
  local target_file="$2"
  local file
  local relative
  local size
  local digest
  local unsorted_file

  unsorted_file="$(mktemp)"
  trap 'rm -f "${unsorted_file:-}"' RETURN
  while IFS= read -r -d '' file; do
    relative="${file#"$attachment_root"/}"
    size="$(sudo stat -c '%s' "$file")"
    digest="$(sudo sha256sum "$file")"
    printf '%s\t%s\t%s\n' "$relative" "$size" "${digest%% *}" \
      >> "$unsorted_file"
  done < <(sudo find "$attachment_root" -type f -print0)
  sort "$unsorted_file" > "$target_file"
  rm -f "$unsorted_file"
  trap - RETURN
}
write_attachment_manifest \
  "$ATTACHMENT_ROOT" "$EVIDENCE_ROOT/attachment-restore-source.tsv"
write_attachment_manifest \
  "$ATTACHMENT_RESTORE_ROOT" "$EVIDENCE_ROOT/attachment-restore-restored.tsv"
if ! diff -u \
  "$EVIDENCE_ROOT/attachment-restore-source.tsv" \
  "$EVIDENCE_ROOT/attachment-restore-restored.tsv" \
  > "$EVIDENCE_ROOT/attachment-restore-diff.txt"
then
  cat "$EVIDENCE_ROOT/attachment-restore-diff.txt" >&2
  exit 1
fi
test ! -s "$EVIDENCE_ROOT/attachment-restore-diff.txt"
sudo rm -rf -- "$ATTACHMENT_RESTORE_ROOT"
rm -f -- "$ATTACHMENT_BACKUP_ARCHIVE"
trap - EXIT
unset ATTACHMENT_RESTORE_ROOT ATTACHMENT_BACKUP_ARCHIVE
~~~

最后使用已批准的职责账号执行只读登录、菜单加载、可发起列表、待办列表和实例详情检查。账号取自受控凭据记录；JSON 接口证据必须分别保存传输层 HTTP 状态与 <code>AjaxResult.code</code>，不能把响应体业务码冒充 HTTP 状态。流程图、导出和附件等二进制响应改为保存传输层状态、Content-Type、大小和 SHA-256，不伪造 <code>AjaxResult.code</code>。所有证据均不保存密码、Token、Cookie 或验证码。

## 10. 安装完成签字

归档前只输出可能含敏感内容的文件名，不输出匹配正文；扫描结果必须为空：

~~~bash
grep -IlER \
  'RUOYI_DB_PASSWORD=|SPRING_DATA_REDIS_PASSWORD=|Authorization:[[:space:]]*Bearer|Set-Cookie:' \
  "$EVIDENCE_ROOT" \
  > "$EVIDENCE_ROOT/sensitive-file-list.txt" \
  || test "$?" -eq 1
cat "$EVIDENCE_ROOT/sensitive-file-list.txt"
test ! -s "$EVIDENCE_ROOT/sensitive-file-list.txt"
~~~

执行人与独立复核人完成真实结果复核后，写入固定 TSV。两人必须不同，姓名和时间不得使用占位符：

~~~bash
INSTALL_FINISHED_AT="$(date '+%Y-%m-%dT%H:%M:%S%:z')"
test "$(date -d "$INSTALL_STARTED_AT" '+%s')" \
  -lt "$(date -d "$INSTALL_FINISHED_AT" '+%s')"
{
  printf 'run_id\tenvironment_id\tstarted_at\tfinished_at\tdatabase_identity\tredis_identity\tattachment_storage_identity\n'
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$EVIDENCE_RUN_ID" "$ENVIRONMENT_ID" \
    "$INSTALL_STARTED_AT" "$INSTALL_FINISHED_AT" \
    "$DATABASE_IDENTITY" "$REDIS_IDENTITY" \
    "$ATTACHMENT_STORAGE_IDENTITY"
} > "$EVIDENCE_ROOT/environment-identity.tsv"

export INSTALL_REVIEWER='<APPROVED_INDEPENDENT_REVIEWER>'
case "$INSTALL_REVIEWER" in
  ''|*'<'*|*'>'*) printf 'INSTALL_REVIEWER is invalid\n' >&2; exit 1 ;;
esac
test "$OPERATOR" != "$INSTALL_REVIEWER"
SIGNED_AT="$(TZ=Asia/Shanghai date '+%Y-%m-%dT%H:%M:%S+08:00')"
{
  printf 'role\tname\tsigned_at\tresult\n'
  printf 'operator\t%s\t%s\tPASS\n' "$OPERATOR" "$SIGNED_AT"
  printf 'reviewer\t%s\t%s\tPASS\n' "$INSTALL_REVIEWER" "$SIGNED_AT"
} > "$EVIDENCE_ROOT/install-signoff.tsv"
unset SIGNED_AT INSTALL_REVIEWER
~~~

最后生成覆盖证据目录全部普通文件的清单并执行 <code>fresh-install</code> 证据门禁。门禁同时验证 69 表结果、连接性、管理员真实登录、数据库 <code>PASS</code>、附件零符号链接、双人签字和敏感信息边界；清单不允许遗漏、重复、额外文件或符号链接：

~~~bash
(
  cd "$EVIDENCE_ROOT"
  find . -type f ! -name EVIDENCE-SHA256SUMS -print0 \
    | sort -z \
    | xargs -0 sha256sum \
    > EVIDENCE-SHA256SUMS
)
chmod -R go-w "$EVIDENCE_ROOT"
sudo bash "$RELEASE_DIR/deployment/scripts/workflow-release-gate.sh" \
  evidence \
  --evidence-dir "$EVIDENCE_ROOT" \
  --profile fresh-install \
  "${RELEASE_GATE_ARGS[@]}"
~~~

成功回执只包含 <code>profile</code> 和证据清单 SHA-256，可记录到批准的发布系统；不得在门禁完成后向证据目录追加文件。若需补证，先恢复写权限、补齐真实证据，再重新执行敏感扫描、生成全量清单和门禁，禁止手工修改清单绕过失败。

安装可以签字完成的条件：

1. 69 表数量与三个只读 SQL 验收全部通过。
2. 应用账号最小权限、发布账号常态锁定、验证账号只读已经确认。
3. MySQL、Redis、Nginx、后端和附件持久卷状态正常。
4. 后端直连和 Nginx 入口均返回成功，职责账号只读烟测通过。
5. JAR、前端文件、SQL、配置模板、门禁脚本和证据文件已由严格清单计算并验证 SHA-256。
6. <code>testcount/accounts.local.md</code> 仍被 Git 忽略，证据归档中不存在凭据。
7. 执行人、独立复核人、时间、环境、Git commit 和结论已写入安装记录，<code>fresh-install</code> 证据门禁返回 <code>PASS</code>。
