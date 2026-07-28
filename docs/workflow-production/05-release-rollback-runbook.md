# Flowable 8 发布与版本回滚运行手册

## 1. 目标与发布门禁

本手册用于 ApprovaPlat 的预生产彩排、生产发布、灰度放量、应用版本回滚和 24/72 小时观察。发布始终使用已验证的固定资产；数据库采用向前兼容变更，应用回滚时保持已发布 schema 不变。

进入发布窗口前必须具备：

- 已批准的发布单、影响范围、负责人、复核人、窗口和业务通知。
- 已冻结的 Git commit、JAR、前端包、版本化 SQL、配置模板和 SHA-256 清单。
- 后端 <code>mvn clean verify -Pflowable-it</code> 与前端 <code>npm run build:prod</code> 成功证据。
- 五角色权限、核心流程、并发、性能、长稳和故障门禁达到本次发布批准值。
- 本版本与上一生产版本都能运行在发布后的数据库结构上。
- 两轮独立发布与回滚彩排结果一致，证据完整。
- MySQL、Redis、附件卷、监控、告警和备份任务处于正常状态。

任何门禁未满足时保持当前生产版本，发布单维持 <code>pending</code>，待门禁闭合后重新申请窗口。

## 2. 发布变量与目录

~~~bash
set -euo pipefail

export REPO_ROOT=/mnt/d/ruoyiflowable
export RELEASE_ID='<YYYYMMDD-HHMM-GIT_SHORT_SHA>'
export PREVIOUS_RELEASE_ID='<PREVIOUS_RELEASE_ID>'
export APPROVED_GIT_COMMIT='<APPROVED_40_CHARACTER_GIT_COMMIT>'
export APPROVED_MANIFEST_SHA256='<APPROVED_64_CHARACTER_MANIFEST_SHA256>'
export APPROVED_PREVIOUS_GIT_COMMIT='<APPROVED_PREVIOUS_40_CHARACTER_GIT_COMMIT>'
export APPROVED_PREVIOUS_MANIFEST_SHA256='<APPROVED_PREVIOUS_64_CHARACTER_MANIFEST_SHA256>'
export APPROVED_BUILD_TIMESTAMP_UTC='<APPROVED_UTC_BUILD_TIMESTAMP>'
export RELEASE_ROOT=/var/lib/ruoyi/releases
export RELEASE_DIR="$RELEASE_ROOT/$RELEASE_ID"
export PREVIOUS_RELEASE_DIR="$RELEASE_ROOT/$PREVIOUS_RELEASE_ID"
export ACTIVE_RELEASE_LINK=/var/lib/ruoyi/current
export RUOYI_PROFILE=/var/lib/ruoyi/uploadPath
export ATTACHMENT_ROOT="$RUOYI_PROFILE/workflow-attachments"
export EVIDENCE_RUN_ID='<YYYYMMDD-HHMM-ENVIRONMENT-OR-REHEARSAL-ROUND>'
export EVIDENCE_DIR="/var/lib/ruoyi/release-evidence/$RELEASE_ID/$EVIDENCE_RUN_ID"
export MYSQL_ADMIN_CNF=/etc/ruoyi/mysql-admin.cnf
export MYSQL_BACKUP_CNF=/etc/ruoyi/mysql-backup.cnf
export MYSQL_RELEASE_CNF=/etc/ruoyi/mysql-release.cnf
export MYSQL_VERIFY_CNF=/etc/ruoyi/mysql-verify.cnf
export DB_SCHEMA=ry-vue
export RELEASE_ORDER_SOURCE='<APPROVED_RELEASE_ORDER_FILE>'
export RUOYI_SERVER_NAME='<APPROVED_FQDN>'
export REDIS_HEALTH_HOST='<APPROVED_REDIS_HOST>'
export REDIS_HEALTH_PORT='<APPROVED_REDIS_PORT>'
export REDIS_HEALTH_USERNAME='<APPROVED_REDIS_HEALTH_USER>'
export REDIS_HEALTH_TLS='false'
export REDIS_HEALTH_CA=''
export NGINX_WORKER_USER=www-data
export RUOYI_MANAGEMENT_PORT=18080
export OPERATOR="$(id -un)"
export OPERATOR_GROUP="$(id -gn)"

[[ "$RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
[[ "$PREVIOUS_RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
[[ "$EVIDENCE_RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]
[[ "$APPROVED_GIT_COMMIT" =~ ^[0-9a-f]{40}$ ]]
[[ "$APPROVED_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$APPROVED_PREVIOUS_GIT_COMMIT" =~ ^[0-9a-f]{40}$ ]]
[[ "$APPROVED_PREVIOUS_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$APPROVED_BUILD_TIMESTAMP_UTC" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]]
test "$RELEASE_ID" != "$PREVIOUS_RELEASE_ID"
test "$APPROVED_GIT_COMMIT" != "$APPROVED_PREVIOUS_GIT_COMMIT"
test "$(git -C "$REPO_ROOT" rev-parse HEAD)" = "$APPROVED_GIT_COMMIT"
test "$(date -u -d "$APPROVED_BUILD_TIMESTAMP_UTC" '+%Y-%m-%dT%H:%M:%SZ')" = \
  "$APPROVED_BUILD_TIMESTAMP_UTC"
test -z "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=normal)"
case "$RELEASE_ORDER_SOURCE" in
  /*) ;;
  *) printf 'RELEASE_ORDER_SOURCE must be an absolute path\n' >&2; exit 1 ;;
esac
test -f "$RELEASE_ORDER_SOURCE"
test -r "$RELEASE_ORDER_SOURCE"
test -d "$PREVIOUS_RELEASE_DIR"
test ! -L "$PREVIOUS_RELEASE_DIR"
test "$(sha256sum "$PREVIOUS_RELEASE_DIR/SHA256SUMS" | awk '{print $1}')" = \
  "$APPROVED_PREVIOUS_MANIFEST_SHA256"
[[ "$RUOYI_SERVER_NAME" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]
case "$REDIS_HEALTH_HOST:$REDIS_HEALTH_USERNAME" in
  *'<'*|*'>'*|*' '*|*/*)
    printf 'Redis health variables are invalid\n' >&2
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

id -nG "$OPERATOR" | tr ' ' '\n' | grep -x ruoyi >/dev/null
id "$NGINX_WORKER_USER" >/dev/null 2>&1
sudo install -d -m 0755 -o root -g root /var/lib/ruoyi
sudo install -d -m 0755 -o root -g root "$RELEASE_ROOT"
sudo install -d -m 0755 -o root -g root \
  /var/lib/ruoyi/release-evidence \
  "/var/lib/ruoyi/release-evidence/$RELEASE_ID"
test ! -e "$RELEASE_DIR"
test ! -L "$RELEASE_DIR"
test ! -e "$EVIDENCE_DIR"
test ! -L "$EVIDENCE_DIR"
sudo install -d -m 0755 -o root -g root "$RELEASE_DIR"
sudo install -d -m 0700 -o "$OPERATOR" -g "$OPERATOR_GROUP" \
  "$EVIDENCE_DIR"
test "$(stat -c '%d' "$RELEASE_ROOT")" = \
  "$(stat -c '%d' "$(dirname "$ACTIVE_RELEASE_LINK")")"
~~~

<code>RELEASE_ID</code>、<code>APPROVED_GIT_COMMIT</code>、上一版本、观察阈值和灰度比例必须在发布单中冻结；构建仓库必须精确位于该 40 位 commit 且工作树为空。<code>EVIDENCE_RUN_ID</code> 唯一标识一次真实执行：两轮彩排和生产发布分别使用不同值，同一次生产发布的即时、24 小时和 72 小时阶段继续使用同一值并增量封存。命令示例中的占位符在执行前替换为批准值；变量门禁会拒绝原样占位符、不安全版本号、非绝对发布清单路径，以及不能在同一文件系统内原子切换的 <code>current</code> 布局。

## 3. 发布资产与完整性

### 3.1 构建资产

受控构建机执行：

~~~bash
cd "$REPO_ROOT/back"
mvn clean verify -Pflowable-it \
  | tee "$EVIDENCE_DIR/backend-verify.log"

cd "$REPO_ROOT/vite"
npm ci
npm run build:prod \
  | tee "$EVIDENCE_DIR/frontend-build.log"

cd "$REPO_ROOT"
bash deployment/scripts/tests/workflow-release-gate-test.sh \
  | tee "$EVIDENCE_DIR/release-gate-test.log"

{
  java -version 2>&1
  mvn -version
  node --version
  npm --version
} | tee "$EVIDENCE_DIR/build-tools.txt"

(
  cd "$REPO_ROOT"
  sha256sum vite/package-lock.json
  find back -name pom.xml -type f -print0 \
    | sort -z \
    | xargs -0 sha256sum
) > "$EVIDENCE_DIR/dependency-input-sha256.txt"
~~~

<code>flowable-it</code> 使用随机 <code>_flowable_it</code> schema、独立 Redis DB、仅具 DML 的应用测试账号，以及只具隔离 schema <code>ALTER</code> 权限的故障注入账号。构建前提供 <code>FLOWABLE_IT_*</code> 和 <code>FLOWABLE_IT_DDL_*</code> 环境变量，全部账号与随机密码先记录到 <code>testcount/accounts.local.md</code>；构建门禁不连接生产 <code>ry-vue</code>。

将以下资产复制到不可变发布目录：

~~~bash
test ! -e "$RELEASE_DIR/ruoyi-admin.jar"
test ! -e "$RELEASE_DIR/frontend"
test ! -e "$RELEASE_DIR/sql"
test ! -e "$RELEASE_DIR/deployment"

sudo install -m 0640 -o root -g ruoyi \
  "$REPO_ROOT/back/ruoyi-admin/target/ruoyi-admin.jar" \
  "$RELEASE_DIR/ruoyi-admin.jar"
sudo install -d -m 0755 -o root -g root "$RELEASE_DIR/frontend"
sudo cp -a "$REPO_ROOT/vite/dist/." "$RELEASE_DIR/frontend/"
sudo cp -a "$REPO_ROOT/back/sql" "$RELEASE_DIR/sql"
sudo cp -a "$REPO_ROOT/deployment" "$RELEASE_DIR/deployment"
test -r "$RELEASE_ORDER_SOURCE"
test "$(wc -l < "$RELEASE_ORDER_SOURCE")" -eq 1
test "$(cat "$RELEASE_ORDER_SOURCE")" = \
  'flowable/business/8.0.0.3__workflow_attachment_cleanup_retry.sql'
sudo install -m 0640 -o root -g ruoyi "$RELEASE_ORDER_SOURCE" \
  "$RELEASE_DIR/sql/release-order.txt"

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
  "$RELEASE_DIR/sql" \
  "$RELEASE_DIR/deployment" \
  "$RELEASE_DIR/RELEASE-METADATA"
sudo find "$RELEASE_DIR/sql" "$RELEASE_DIR/deployment" \
  -type d -exec chmod 0750 {} +
sudo find "$RELEASE_DIR/sql" "$RELEASE_DIR/deployment" \
  -type f -exec chmod 0640 {} +
sudo chmod 0640 "$RELEASE_DIR/RELEASE-METADATA"
sudo -u ruoyi test -r "$RELEASE_DIR/ruoyi-admin.jar"
sudo -u "$NGINX_WORKER_USER" test -r "$RELEASE_DIR/frontend/index.html"
if sudo -u "$NGINX_WORKER_USER" test -r "$RELEASE_DIR/ruoyi-admin.jar"; then
  printf 'Nginx worker must not read backend assets\n' >&2
  exit 1
fi
~~~

版本根与单个版本目录使用 <code>0755 root:root</code> 只提供路径穿越；前端目录和文件使用 <code>0755/0644 root:root</code>。JAR、SQL 和部署配置保持 <code>root:ruoyi</code> 的 <code>0640/0750</code>，Nginx worker 可以读取静态前端，但不能读取后端和发布资产。

当前候选发布包至少包含：

- <code>ruoyi-admin.jar</code>。
- 完整 <code>frontend/</code> 静态产物。
- 本次版本化 SQL 及三组只读验收脚本。
- <code>release-order.txt</code>；本次候选包必须唯一包含 <code>flowable/business/8.0.0.3__workflow_attachment_cleanup_retry.sql</code>，不接受空文件。
- <code>application.yml</code>、<code>application-druid.yml</code>、systemd 和 Nginx 模板。
- <code>RELEASE-METADATA</code>，固定记录格式版本、发布 ID、40 位 Git commit、上一版本 ID 和 UTC 构建时间。
- <code>deployment/scripts/workflow-release-gate.sh</code> 及其正式回归测试资产。
- 对应证据目录中的 Git commit、构建工具版本、依赖输入摘要、测试结果，以及批准发布单中的变更清单。
- 不含真实值的环境变量清单。

上一版本回滚包是与当前候选包分别冻结的不可变兼容制品，只要求保留上一版本原始批准的 <code>ruoyi-admin.jar</code>、完整 <code>frontend/</code>、记录上一版本真实 commit/构建时间的 <code>RELEASE-METADATA</code> 和严格覆盖该包全部文件的 <code>SHA256SUMS</code>。不得向历史包补入当前版本 SQL、配置、门禁脚本或测试资产，也不得因当前门禁升级而修改已上线目录。

发布包和证据目录中的相对路径只允许 ASCII 字母、数字、点、下划线、<code>@</code>、加号、连字符和正斜线；禁止空格、反斜线、绝对路径、<code>.</code>/<code>..</code> 路径段、换行、符号链接和特殊文件。截图等附加证据在归档前按该规则命名。

生成并复核哈希：

~~~bash
cd "$RELEASE_DIR"
find . -type f ! -name SHA256SUMS -print0 \
  | sort -z \
  | xargs -0 sha256sum \
  | sudo tee SHA256SUMS >/dev/null
sudo chown root:ruoyi SHA256SUMS
sudo chmod 0640 SHA256SUMS
(cd "$RELEASE_DIR" && sudo sha256sum -c SHA256SUMS) \
  | tee "$EVIDENCE_DIR/release-hash-check.txt"
test "$(sha256sum "$RELEASE_DIR/SHA256SUMS" | awk '{print $1}')" = \
  "$APPROVED_MANIFEST_SHA256"

# 只读门禁同时验证当前包、上一版本包、全量哈希、元数据关系和 SQL 顺序。
sudo bash \
  "$RELEASE_DIR/deployment/scripts/workflow-release-gate.sh" \
  preflight \
  --release-dir "$RELEASE_DIR" \
  --release-id "$RELEASE_ID" \
  --approved-git-commit "$APPROVED_GIT_COMMIT" \
  --approved-manifest-sha256 "$APPROVED_MANIFEST_SHA256" \
  --previous-release-dir "$PREVIOUS_RELEASE_DIR" \
  --previous-release-id "$PREVIOUS_RELEASE_ID" \
  --approved-previous-git-commit "$APPROVED_PREVIOUS_GIT_COMMIT" \
  --approved-previous-manifest-sha256 \
    "$APPROVED_PREVIOUS_MANIFEST_SHA256" \
  | tee "$EVIDENCE_DIR/release-preflight.txt"
git -C "$REPO_ROOT" rev-parse HEAD \
  | tee "$EVIDENCE_DIR/git-commit.txt"
unset GIT_COMMIT BUILD_TIMESTAMP_UTC
~~~

哈希清单在彩排、生产部署和回滚时使用同一份文件，任何差异都必须回到制品构建/批准流程。上一版本元数据中的 <code>release_id</code> 必须等于目录名，当前版本的 <code>previous_release_id</code> 必须精确指向批准的上一版本。历史线上目录缺少元数据或清单时，禁止原地补文件；应从原始冻结 JAR、前端和原始 Git commit 在新的受控发布根中重新封装 current/previous 配对制品，独立复核后更新本次 <code>RELEASE_ROOT</code> 与批准摘要。无法取得原始资产或批准记录时，本次发布保持 <code>blocked</code>。<code>preflight</code> 只读文件，不连接 MySQL/Redis、不启停服务、不切换 <code>current</code>；非零退出码立即停止发布。

## 4. 凭据与证据规则

发布彩排中创建或轮换的数据库、Redis、测试用户和随机密码，在生成后立即记录到 <code>testcount/accounts.local.md</code>，再继续后续操作。执行前后均确认该文件由 Git 忽略：

~~~bash
cd "$REPO_ROOT"
test -f testcount/accounts.local.md
git check-ignore -v testcount/accounts.local.md \
  | tee "$EVIDENCE_DIR/accounts-ignore-check.txt"
git status --short \
  | tee "$EVIDENCE_DIR/git-status.txt"
~~~

每条记录包含创建时间、环境、主机、账号、密码、权限范围、用途、状态和清理计划，格式遵循 <code>testcount/README.md</code>。生产凭据由密钥系统和 <code>/etc/ruoyi/ruoyi.env</code> 管理，文件权限固定为 <code>0600</code>。发布证据只记录密钥版本号、轮换时间和加载结果，不保存密码、Token、Cookie、客户端配置文件正文、环境文件正文或 <code>accounts.local.md</code>。

## 5. 两轮发布与回滚彩排

生产发布前，在两个独立的全新预生产环境或同一环境的两次独立重建中完成两轮彩排。<code>rehearsal</code> 及全部生产族 profile 必须传入真实、不可变的上一版本目录和非 <code>NONE</code> 版本 ID；只有首次安装使用的独立 <code>preflight</code>/<code>fresh-install</code> 门禁允许 <code>previous_release_id=NONE</code>。每轮均执行完整步骤：

1. 按 [04-fresh-install-runbook.md](04-fresh-install-runbook.md) 建立 69 表基线。
2. 校验发布包 SHA-256，安装 JAR、前端、配置和服务。
3. 执行版本化 SQL 与三组只读验收。
4. 使用五个职责角色完成登录、发起、认领、完成、取消、终止、附件和详情烟测。
5. 验证六类 Flowable job、deadletter、数据库连接池、Redis 和附件目录。
6. 切换到上一应用版本，执行回滚健康检查和核心只读/写入烟测。
7. 再切回本次版本，确认相同实例、历史、审计和附件保持一致。
8. 保存开始/结束时间、停机时长、执行人、命令输出、截图、数据库结果和哈希。

每轮证据根目录必须把本轮 [04-fresh-install-runbook.md](04-fresh-install-runbook.md) 的完整归档保存在 <code>fresh-install/</code> 下，而不是引用另一轮目录或复制生产数据库结果。内层至少保留空 schema 为 0、<code>69/20/11/32/6</code> 表数、管理员 bootstrap 前后状态与真实登录、69 张恢复表的 <code>mysqlcheck=OK</code>、三组数据库验收、附件恢复零差异、<code>install-signoff.tsv</code> 和与本轮根目录完全相同的 <code>release-preflight.txt</code>。父级 <code>EVIDENCE-SHA256SUMS</code> 必须覆盖内层全部文件及内层清单。

每轮在 <code>fresh-install/environment-identity.tsv</code> 写入且只写入一个环境身份数据行，表头固定如下：

~~~text
run_id	environment_id	started_at	finished_at	database_identity	redis_identity	attachment_storage_identity
~~~

身份字段使用不含凭据的受控标识，禁止 <code>NONE</code>、<code>UNKNOWN</code>、<code>TODO</code>、<code>MOCK</code> 或占位符。<code>started_at</code>/<code>finished_at</code> 使用带时区 ISO-8601 时间，必须满足开始早于结束、结束不晚于当前时间；第二轮开始必须严格晚于第一轮结束。两轮的 <code>run_id</code>、环境、数据库、Redis 和附件存储身份必须逐项不同，四名安装执行/复核人员也不得跨轮复用。安装、发布和彩排签字都不能早于本轮环境构建结束。

每轮回滚和再次切回目标版本时，使用固定证据名保存结果：

| 阶段 | 固定证据 |
| --- | --- |
| 本轮全新安装 | <code>fresh-install/</code> 下的完整正式安装证据、<code>environment-identity.tsv</code>、空的 <code>attachment-restore-diff.txt</code> 和内层 <code>EVIDENCE-SHA256SUMS</code> |
| 回滚到上一版本 | <code>rollback-source-hash-check.txt</code>、<code>rollback-services.txt</code>、<code>rollback-backend-health.txt</code>、<code>rollback-liveness.json</code>、<code>rollback-readiness.json</code>、<code>rollback-prometheus.txt</code>、<code>rollback-nginx-health.txt</code>、<code>rollback-database-verify.tsv</code>、<code>rollback-business-smoke.tsv</code>、<code>rollback-attachment-diff.txt</code> |
| 再次切回目标版本 | <code>reapply-services.txt</code>、<code>reapply-liveness.json</code>、<code>reapply-readiness.json</code>、<code>reapply-prometheus.txt</code>、<code>reapply-database-verify.tsv</code>、<code>reapply-business-smoke.tsv</code>、<code>reapply-attachment-diff.txt</code> |
| 本轮最终复核 | <code>release-signoff.tsv</code>、<code>rehearsal-signoff.tsv</code>、<code>EVIDENCE-SHA256SUMS</code> |

其中服务状态每行必须为 <code>active</code>，数据库结果不得出现 <code>FAIL</code>，五角色烟测采用第 10.3 节固定 TSV，根目录和 <code>fresh-install/</code> 下的附件差异文件必须为空。每轮完成第 14 节封存后执行：

~~~bash
sudo bash "$RELEASE_DIR/deployment/scripts/workflow-release-gate.sh" \
  evidence \
  --evidence-dir "$EVIDENCE_DIR" \
  --profile rehearsal \
  --release-dir "$RELEASE_DIR" \
  --release-id "$RELEASE_ID" \
  --approved-git-commit "$APPROVED_GIT_COMMIT" \
  --approved-manifest-sha256 "$APPROVED_MANIFEST_SHA256" \
  --previous-release-dir "$PREVIOUS_RELEASE_DIR" \
  --previous-release-id "$PREVIOUS_RELEASE_ID" \
  --approved-previous-git-commit "$APPROVED_PREVIOUS_GIT_COMMIT" \
  --approved-previous-manifest-sha256 \
    "$APPROVED_PREVIOUS_MANIFEST_SHA256"
~~~

两轮彩排必须产生物理独立、独立命名的证据目录；两个目录不能指向同一路径或同一设备/inode，也都不能与生产证据目录相同，符号链接不能用于规避该约束。生产门禁会针对本次传入的当前版本和上一版本目录，分别以 <code>rehearsal</code> profile 重新验证两轮归档，而不是信任归档中自报的 <code>PASS</code>。根目录及 <code>fresh-install/</code> 内的两份 <code>release-preflight.txt</code> 都必须与生产门禁现场重算的回执逐字一致，因此必须绑定完全相同的 <code>release_manifest_sha256</code> 和 <code>previous_manifest_sha256</code>；两轮证据清单及其 PASS 回执则必须互不相同，复制同一归档不能计作两轮。

两轮 <code>fresh-install/table-counts.tsv</code> 和 <code>fresh-install/database-verify.tsv</code> 必须逐字一致，业务状态和附件摘要也必须一致；回滚耗时都不得超过发布单阈值。任一身份复用、时间重叠、人员复用、结果差异、门禁失败或证据复用都使两轮同时无效，差异闭合后必须从全新环境重新完成两轮。仓库内测试或同一目录复制不能替代两轮真实彩排。本节只规定执行方法，不表示两轮彩排已经实际完成。

## 6. 生产窗口与发布前检查

### 6.1 冻结窗口参数

| 参数 | 发布单内容 |
| --- | --- |
| 发布开始/结束时间 | <code>&lt;Asia/Shanghai 时间&gt;</code> |
| 当前版本/目标版本 | <code>&lt;PREVIOUS_RELEASE_ID&gt; / &lt;RELEASE_ID&gt;</code> |
| 维护页或灰度策略 | <code>&lt;单节点维护窗口或多节点权重&gt;</code> |
| HTTP 错误率阈值 | <code>&lt;批准值&gt;</code> |
| P95/P99 阈值 | <code>&lt;批准值&gt;</code> |
| Flowable job/deadletter 阈值 | <code>&lt;批准值&gt;</code> |
| 数据库连接/锁等待阈值 | <code>&lt;批准值&gt;</code> |
| Redis 延迟/错误阈值 | <code>&lt;批准值&gt;</code> |
| 附件磁盘低水位 | <code>&lt;批准值&gt;</code> |
| 回滚决策人 | <code>&lt;姓名与联系方式&gt;</code> |

### 6.2 基线采集

~~~bash
# 真实 readiness 与 Prometheus 快照是放量前置条件；不得用主页 200 或手写 PASS 代替。
wait_runtime_health() {
  local endpoint_path="$1"
  local target_file="$2"
  local response_file
  local attempt

  response_file="$(mktemp)"
  for attempt in $(seq 1 60); do
    if curl -fsS --max-time 5 \
      "http://127.0.0.1:$RUOYI_MANAGEMENT_PORT$endpoint_path" \
      > "$response_file" \
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
      return 0
    fi
    sleep 5
  done
  rm -f "$response_file"
  printf 'runtime health did not become UP: %s\n' "$endpoint_path" >&2
  return 1
}

# 采集指定阶段的 liveness/readiness/Prometheus 原始响应并校验两个低基数门禁指标。
capture_runtime_gate() {
  local evidence_prefix="$1"

  wait_runtime_health \
    /actuator/health/liveness "${evidence_prefix}-liveness.json"
  wait_runtime_health \
    /actuator/health/readiness "${evidence_prefix}-readiness.json"
  curl -fsS --retry 12 --retry-all-errors --retry-delay 5 --max-time 5 \
    "http://127.0.0.1:$RUOYI_MANAGEMENT_PORT/actuator/prometheus" \
    > "${evidence_prefix}-prometheus.txt"
  awk '
    $1 == "workflow_runtime_metrics_snapshot_available" {
      snapshot_count++; if (NF != 2 || $2 !~ /^1([.]0+)?$/) invalid = 1
    }
    $1 == "workflow_attachment_cleanup_lock_degraded" {
      lock_count++; if (NF != 2 || $2 !~ /^0([.]0+)?$/) invalid = 1
    }
    END { exit(!invalid && snapshot_count == 1 && lock_count == 1 ? 0 : 1) }
  ' "${evidence_prefix}-prometheus.txt"
}

(cd "$RELEASE_DIR" && sudo sha256sum -c SHA256SUMS) \
  | tee "$EVIDENCE_DIR/predeploy-hash-check.txt"
sudo systemctl is-active nginx ruoyi-backend \
  | tee "$EVIDENCE_DIR/predeploy-services.txt"
df -h "$RUOYI_PROFILE" \
  | tee "$EVIDENCE_DIR/predeploy-filesystem.txt"
curl -fsS http://127.0.0.1:8080/ \
  | tee "$EVIDENCE_DIR/predeploy-backend-health.txt" >/dev/null
grep -Fq '后台管理框架' "$EVIDENCE_DIR/predeploy-backend-health.txt"
capture_runtime_gate "$EVIDENCE_DIR/predeploy"
for docs_path in /v3/api-docs /swagger-ui.html
do
  test "$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:8080$docs_path")" = '404'
done
curl -fsSI --resolve "$RUOYI_SERVER_NAME:443:127.0.0.1" \
  "https://$RUOYI_SERVER_NAME/" \
  | tee "$EVIDENCE_DIR/predeploy-nginx-health.txt"
~~~

使用只读账号采集实例、任务、六类 job、deadletter、附件状态和容量基线，并保存监控面板快照。基线与发布单一致后进入维护或灰度阶段。

## 7. 一致性备份

单节点基线采用维护窗口：Nginx 切换维护页或停止入口写流量，等待在途请求结束，再停止后端，使 MySQL 元数据与附件文件处于同一业务时间点。

~~~bash
sudo systemctl stop ruoyi-backend
sudo systemctl is-inactive ruoyi-backend

export BACKUP_DIR="/var/lib/ruoyi/backups/$RELEASE_ID"
test ! -e "$BACKUP_DIR"
test ! -L "$BACKUP_DIR"
sudo install -d -m 0700 -o root -g root "$BACKUP_DIR"

BACKUP_ACCOUNT_RELOCK_REQUIRED=0
relock_backup_account() {
  local original_status="$?"
  local relock_status=0
  trap - EXIT
  if test "$BACKUP_ACCOUNT_RELOCK_REQUIRED" = '1'; then
    sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
      --execute="ALTER USER 'ruoyi_backup'@'127.0.0.1' ACCOUNT LOCK;" \
      || relock_status=$?
  fi
  test "$relock_status" = '0' || exit 1
  exit "$original_status"
}
trap relock_backup_account EXIT
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
  "$DB_SCHEMA" \
  | sudo tee "$BACKUP_DIR/$DB_SCHEMA.sql" >/dev/null

sudo tar --xattrs --acls -C "$RUOYI_PROFILE" \
  -czf "$BACKUP_DIR/ruoyi-profile.tar.gz" .

sudo sha256sum \
  "$BACKUP_DIR/$DB_SCHEMA.sql" \
  "$BACKUP_DIR/ruoyi-profile.tar.gz" \
  | sudo tee "$BACKUP_DIR/SHA256SUMS" >/dev/null
sudo sha256sum -c "$BACKUP_DIR/SHA256SUMS" \
  | tee "$EVIDENCE_DIR/backup-hash-check.txt"
sudo test -s "$BACKUP_DIR/$DB_SCHEMA.sql"
sudo test -s "$BACKUP_DIR/ruoyi-profile.tar.gz"
sudo tar -tzf "$BACKUP_DIR/ruoyi-profile.tar.gz" >/dev/null
sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="ALTER USER 'ruoyi_backup'@'127.0.0.1' ACCOUNT LOCK;"
test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="SELECT ACCOUNT_LOCKED FROM mysql.user
    WHERE User = 'ruoyi_backup' AND Host = '127.0.0.1';")" = 'Y'
BACKUP_ACCOUNT_RELOCK_REQUIRED=0
trap - EXIT
unset BACKUP_ACCOUNT_RELOCK_REQUIRED
~~~

备份账号仅授予目标 schema 的 <code>SELECT</code>、<code>SHOW VIEW</code>、<code>TRIGGER</code>，并配合 <code>--single-transaction</code>、<code>--skip-lock-tables</code>、<code>--skip-add-locks</code>、<code>--set-gtid-purged=OFF</code>、<code>--no-tablespaces</code>、<code>--skip-routines</code> 和 <code>--skip-events</code> 使用。该命令不需要 <code>SHOW_ROUTINE</code>、<code>EVENT</code>、<code>PROCESS</code>、<code>LOCK TABLES</code>、<code>RELOAD</code> 或其他全局权限；若未来正式引入存储过程或数据库事件，必须单独审批备份范围与对应最小权限。备份目录按批准的保留期、加密和异地复制策略管理。发布窗口只在 SQL 文件、附件归档、SHA-256 和备份可用性检查全部通过后继续。

## 8. 数据库向前兼容发布

### 8.1 兼容规则

- 每次数据库变更都有唯一版本号、审查记录、哈希和只读验收。
- 先扩展后收缩：先增加兼容列、表或索引，再发布读取/写入新结构的应用。
- 新结构在 24/72 小时观察和上一版本支持期内兼容当前版本与上一版本。
- 应用版本回滚保持数据库在已发布版本，不执行反向 DDL。
- 删除、重命名、缩窄类型、改变字段语义或批量重写数据的变更拆分到独立版本，并在旧版本退出支持后执行。
- <code>flowable.database-schema-update=false</code> 始终生效。

### 8.2 执行版本化 SQL

数据库管理员在发布窗口短时解锁 <code>ruoyi_release</code>，只执行当前候选包清单中的增量 SQL；全新安装基础脚本不在版本发布中重复执行。当前包的 <code>release-order.txt</code> 只列本次批准的增量 SQL 相对路径，并固定为 <code>flowable/business/8.0.0.3__workflow_attachment_cleanup_retry.sql</code>。上一版本回滚包不执行 DDL，也不要求携带当前 SQL；数据库回滚保持向前兼容。

模型版本唯一约束属于 P1 既有基线，不是本次候选发布清单的可选项。发布前若三组只读验收发现目标 schema 缺少 <code>ACT_RE_MODEL(KEY_, VERSION_, TENANT_ID_)</code> 唯一约束，必须停止候选发布，在独立受控维护中按全新安装手册执行 <code>flowable/business/8.0.0.2__workflow_model_version_guard.sql</code> 并重新完成三组验收；基线恢复后，本次 <code>release-order.txt</code> 仍只能包含已冻结的 <code>flowable/business/8.0.0.3__workflow_attachment_cleanup_retry.sql</code>。禁止临时把 <code>.2</code> 加入候选包，或修改上一版本回滚包来绕过发布包门禁。
本版本使用附件清理持久化重试字段，<code>release-order.txt</code> 必须在应用切换前包含 <code>flowable/business/8.0.0.3__workflow_attachment_cleanup_retry.sql</code>。相同哈希的脚本必须在独立发布彩排中连续执行两次并保留两份日志；生产清单只列一次，禁止通过重复清单项规避幂等问题。

<code>ruoyi_release</code> 除变更权限外必须保留目标 schema 的 <code>SELECT</code>，使幂等脚本能看到完整的 <code>information_schema</code> 列、索引和 CHECK 元数据。发布前由 DBA 复核 <code>SHOW GRANTS</code>；只有 <code>ALTER</code>/<code>INDEX</code> 而无目标 schema <code>SELECT</code> 时必须停止，不得将元数据不可见造成的重复对象错误记为幂等结果。账号必须从已锁定状态开始，只在下面一个受保护事务中解锁；任何失败、信号或终端退出都先重锁，再保留原失败状态。

~~~bash
RELEASE_ACCOUNT_RELOCK_REQUIRED=0
relock_release_account() {
  local original_status="$?"
  local relock_status=0
  trap - EXIT
  if test "$RELEASE_ACCOUNT_RELOCK_REQUIRED" = '1'; then
    sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
      --execute="ALTER USER 'ruoyi_release'@'127.0.0.1' ACCOUNT LOCK;" \
      || relock_status=$?
    if test "$relock_status" -ne 0; then
      printf 'ruoyi_release re-lock failed; release window remains blocked\n' >&2
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

if test -s "$RELEASE_DIR/sql/release-order.txt"; then
  while IFS= read -r sql_file
  do
    test -n "$sql_file"
    test -r "$RELEASE_DIR/sql/$sql_file"
    sudo mysql --defaults-extra-file="$MYSQL_RELEASE_CNF" --protocol=TCP \
      --default-character-set=utf8mb4 \
      --database="$DB_SCHEMA" --show-warnings \
      < "$RELEASE_DIR/sql/$sql_file" \
      > "$EVIDENCE_DIR/sql-$(basename "$sql_file").log" 2>&1
  done < "$RELEASE_DIR/sql/release-order.txt"
fi

sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --execute="ALTER USER 'ruoyi_release'@'127.0.0.1' ACCOUNT LOCK;"
test "$(sudo mysql --defaults-extra-file="$MYSQL_ADMIN_CNF" --protocol=TCP \
  --batch --skip-column-names --execute="SELECT ACCOUNT_LOCKED FROM mysql.user
    WHERE User = 'ruoyi_release' AND Host = '127.0.0.1';")" = 'Y'
RELEASE_ACCOUNT_RELOCK_REQUIRED=0
trap - EXIT
unset RELEASE_ACCOUNT_RELOCK_REQUIRED
~~~

迁移事务成功并持久复核为锁定后，才把三组只读验收写入固定文件；验收失败保持发布停止，不再次解锁发布账号：

~~~bash
VERIFY_LOG="$EVIDENCE_DIR/database-verify.tsv"
: > "$VERIFY_LOG"
for verify_sql in \
  "$RELEASE_DIR/sql/flowable/verify/8.0.0__verify.sql" \
  "$RELEASE_DIR/sql/flowable/verify/8.0.0__verify_workflow_business.sql" \
  "$RELEASE_DIR/sql/flowable/verify/8.0.0__verify_workflow_menu.sql"
do
  test -r "$verify_sql"
  printf 'FILE\t%s\n' "$(basename "$verify_sql")" | tee -a "$VERIFY_LOG"
  sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
    --default-character-set=utf8mb4 \
    --database="$DB_SCHEMA" --batch --raw < "$verify_sql" \
    | tee -a "$VERIFY_LOG"
done
if grep -Eq '(^|[[:space:]])FAIL([[:space:]]|$)' "$VERIFY_LOG"; then
  printf 'database verification failed\n' >&2
  exit 1
fi
grep -Eq '(^|[[:space:]])PASS([[:space:]]|$)' "$VERIFY_LOG"
~~~

无论 Linux 还是 Windows，执行版本化 SQL 和只读验收都必须显式传入 <code>--default-character-set=utf8mb4</code>。Windows 命令不得依赖终端代码页，否则中文列注释可在 DDL 阶段被不可逆地转码。

## 9. 应用与前端切换

### 9.1 保存当前版本资产

确认上一版本目录已包含当前线上 JAR、前端文件、配置模板和 <code>SHA256SUMS</code>，并通过哈希检查：

~~~bash
test -r "$PREVIOUS_RELEASE_DIR/ruoyi-admin.jar"
test -r "$PREVIOUS_RELEASE_DIR/frontend/index.html"
(cd "$PREVIOUS_RELEASE_DIR" && sudo sha256sum -c SHA256SUMS) \
  | tee "$EVIDENCE_DIR/previous-release-hash-check.txt"
~~~

### 9.2 切换资产

后端与前端始终从同一个 <code>current</code> 符号链接读取。以下命令先确认当前目标就是已批准的上一版本，再在同一文件系统内原子切换链接；路径不符合预期时立即停止发布并保留现场：

~~~bash
test "$(readlink -f "$ACTIVE_RELEASE_LINK")" = "$PREVIOUS_RELEASE_DIR"
test ! -e "$RELEASE_ROOT/.current-$RELEASE_ID"
test ! -L "$RELEASE_ROOT/.current-$RELEASE_ID"
sudo ln -s "$RELEASE_DIR" "$RELEASE_ROOT/.current-$RELEASE_ID"
sudo mv -Tf "$RELEASE_ROOT/.current-$RELEASE_ID" "$ACTIVE_RELEASE_LINK"
test -L "$ACTIVE_RELEASE_LINK"
test "$(readlink -f "$ACTIVE_RELEASE_LINK")" = "$RELEASE_DIR"
sudo -u "$NGINX_WORKER_USER" test -r \
  "$ACTIVE_RELEASE_LINK/frontend/index.html"

sudo systemctl start ruoyi-backend
sudo systemctl is-active ruoyi-backend
sudo nginx -t
# 保持维护页/负载均衡摘流；第 10.2 节会在入口重载前统一冻结 readiness 和指标快照。

# 生产切换完成后立即冻结版本、发布清单、切换时间及现场来源；不记录凭据。
RELEASE_MANIFEST_SHA256="$(sha256sum "$RELEASE_DIR/SHA256SUMS" | awk '{print $1}')"
SWITCHED_AT="$(date '+%Y-%m-%dT%H:%M:%S%:z')"
{
  printf 'active_release=%s\n' "$(readlink -f "$ACTIVE_RELEASE_LINK")"
  printf 'release_id=%s\n' "$RELEASE_ID"
  printf 'release_manifest_sha256=%s\n' "$RELEASE_MANIFEST_SHA256"
  printf 'switched_at=%s\n' "$SWITCHED_AT"
} > "$EVIDENCE_DIR/production-switch-source.txt"
PRODUCTION_SWITCH_SOURCE_SHA256="$(
  sha256sum "$EVIDENCE_DIR/production-switch-source.txt" | awk '{print $1}'
)"
{
  printf 'release_id\trelease_manifest_sha256\tswitched_at\tsource_file\tsource_sha256\n'
  printf '%s\t%s\t%s\tproduction-switch-source.txt\t%s\n' \
    "$RELEASE_ID" "$RELEASE_MANIFEST_SHA256" "$SWITCHED_AT" \
    "$PRODUCTION_SWITCH_SOURCE_SHA256"
} > "$EVIDENCE_DIR/production-switch.tsv"
unset RELEASE_MANIFEST_SHA256 SWITCHED_AT PRODUCTION_SWITCH_SOURCE_SHA256
~~~

<code>production-switch.tsv</code> 必须恰好包含一条数据，版本 ID 和 <code>SHA256SUMS</code> 自身摘要必须属于本次发布，来源摘要必须指向同目录的非空 <code>production-switch-source.txt</code>。<code>switched_at</code> 不得早于 <code>RELEASE-METADATA.built_at_utc</code>、不得晚于当前时间；<code>release-signoff.tsv</code> 的两名签字人都不得在切换前签字。上一版本目录保持不可变，72 小时观察结束后再按批准的版本保留策略清理。

## 10. 灰度与健康检查

### 10.1 灰度策略

- 单节点部署：保持外部维护页，先由发布人员和业务验收账号完成全部烟测，再开放正式流量。
- 多节点部署：只升级一个 executor 关闭的节点，按批准的 <code>5% -> 25% -> 50% -> 100%</code> 或发布单权重逐级放量；每级完成观察后进入下一级。
- executor 按批准拓扑启用。多节点场景先确认 Flowable 锁协调、调度唯一性和 deadletter 告警，再放开异步流量。
- 所有节点使用同一受控数据库、Redis 和共享附件存储，配置版本与发布清单一致。

### 10.2 技术健康检查

~~~bash
sudo systemctl is-active nginx ruoyi-backend \
  | tee "$EVIDENCE_DIR/postdeploy-services.txt"
sudo journalctl -u ruoyi-backend --since '-10 minutes' --no-pager \
  | tee "$EVIDENCE_DIR/postdeploy-backend.log"
capture_runtime_gate "$EVIDENCE_DIR/postdeploy"
curl -fsS http://127.0.0.1:8080/ \
  | tee "$EVIDENCE_DIR/postdeploy-backend-health.txt" >/dev/null
grep -Fq '后台管理框架' "$EVIDENCE_DIR/postdeploy-backend-health.txt"
for docs_path in /v3/api-docs /swagger-ui.html
do
  test "$(curl -sS -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:8080$docs_path")" = '404'
done
sudo nginx -t
sudo systemctl reload nginx
curl -fsSI --resolve "$RUOYI_SERVER_NAME:443:127.0.0.1" \
  "https://$RUOYI_SERVER_NAME/" \
  | tee "$EVIDENCE_DIR/postdeploy-nginx-health.txt"
for public_docs_path in /prod-api/v3/api-docs /prod-api/swagger-ui.html
do
  test "$(curl -sS -o /dev/null -w '%{http_code}' \
    --resolve "$RUOYI_SERVER_NAME:443:127.0.0.1" \
    "https://$RUOYI_SERVER_NAME$public_docs_path")" = '404'
done
sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --connect-timeout=5 --batch --skip-column-names \
  --execute='SELECT 1;' \
  | tee "$EVIDENCE_DIR/postdeploy-mysql-connectivity.txt"
grep -qx '1' "$EVIDENCE_DIR/postdeploy-mysql-connectivity.txt"
redis-cli -h "$REDIS_HEALTH_HOST" -p "$REDIS_HEALTH_PORT" \
  --user "$REDIS_HEALTH_USERNAME" "${REDIS_TLS_ARGS[@]}" --askpass PING \
  | tee "$EVIDENCE_DIR/postdeploy-redis-ping.txt"
grep -qx 'PONG' "$EVIDENCE_DIR/postdeploy-redis-ping.txt"
~~~

MySQL 通过只读客户端文件检查本机或远程 TCP 连接，Redis 通过受控账号、目标主机与可选 CA 检查本机或远程 TLS 连接；两类命令均不输出密码。若换过终端，先重新执行第 2 节的非敏感变量与 <code>REDIS_TLS_ARGS</code> 初始化。随后执行三组数据库只读验收，并采集六类 job、deadletter、连接池、慢 SQL、锁等待、HTTP 错误率、P95/P99、Redis 延迟和附件磁盘水位。每项达到发布单阈值后继续放量。

### 10.3 真实业务烟测

使用受控验收账号完成：

1. <code>workflow_designer</code> 创建或打开已批准模型并核对部署版本。
2. <code>workflow_starter</code> 发起含普通字段和附件的流程。
3. <code>workflow_approver</code> 认领、查看详情并完成任务。
4. 发起人核对流程终态、历史、意见、轨迹、表单快照和附件下载。
5. <code>workflow_admin</code> 核对实例管理、job 与审计视图。
6. <code>workflow_auditor</code> 完成只读查看，并确认无写入口。

烟测证据记录流程定义 ID、实例 ID、任务 ID、操作人账号、传输层 HTTP 状态、<code>AjaxResult.code</code>、业务状态和数据库状态，不记录密码、Token、Cookie 或附件正文。

五个职责角色的最终动作结果同时汇总到 <code>business-smoke.tsv</code>。该文件不是人工填写的 <code>PASS</code> 汇总表，而是由真实动作时间、传输层状态、响应体业务码、业务状态、持久化摘要、审计引用和原始来源摘要组成的固定证据表。首行必须与下列 19 列表头完全一致：

~~~text
started_at	finished_at	role	action	method	object_ref	request_id	expected_transport_status	actual_transport_status	expected_business_code	actual_business_code	state_before	state_after	db_before_sha256	db_after_sha256	audit_ref	unexpected_side_effect_count	source_file	source_sha256
~~~

正文必须恰好包含下列六个固定动作各一行；缺失、重复或未知动作均使门禁失败：

| action | 固定角色 | method | 传输层 expected/actual | 业务码 expected/actual | 状态与持久化契约 |
| --- | --- | --- | --- | --- | --- |
| <code>instance_audit_view</code> | <code>workflow_admin</code> | <code>GET</code> | <code>200/200</code> | <code>200/200</code> | 只读，前后状态和数据库摘要必须相同 |
| <code>model_version_view</code> | <code>workflow_designer</code> | <code>GET</code> | <code>200/200</code> | <code>200/200</code> | 只读，前后状态和数据库摘要必须相同 |
| <code>process_start</code> | <code>workflow_starter</code> | <code>POST</code> | <code>200/200</code> | <code>200/200</code> | 写操作，前后状态和数据库摘要必须同时变化 |
| <code>task_complete</code> | <code>workflow_approver</code> | <code>POST</code> | <code>200/200</code> | <code>200/200</code> | 写操作，前后状态和数据库摘要必须同时变化 |
| <code>instance_detail</code> | <code>workflow_auditor</code> | <code>GET</code> | <code>200/200</code> | <code>200/200</code> | 只读，前后状态和数据库摘要必须相同 |
| <code>instance_terminate_denied</code> | <code>workflow_auditor</code> | <code>POST</code> | <code>200/200</code> | <code>403/403</code> | 拒绝操作，前后状态和数据库摘要必须相同 |

六个固定动作都是返回 <code>AjaxResult</code> 或 <code>TableDataInfo</code> 的 JSON 接口，必须同时填写并验证两层状态。拒绝动作采用本项目统一异常响应契约：传输层保持 HTTP 200，响应体 <code>code=403</code>；旧式“HTTP 403 且未记录业务码”的行必须失败。XML、XLSX、流程图和附件下载等无 <code>AjaxResult.code</code> 的二进制扩展烟测不写入这六行，另行记录传输层状态、Content-Type、大小和 SHA-256；门禁对 <code>source_file</code> 按二进制安全的普通文件摘要校验，因此文本或二进制原始来源均可追溯。

每行动作必须在 15 分钟内结束，使用可追踪的 <code>object_ref</code>、标准 UUID <code>request_id</code>、大写业务状态、64 位小写 SHA-256 数据库摘要，以及以 <code>sys_oper_log:</code>、<code>security_log:</code> 或 <code>trace:</code> 开头的真实 <code>audit_ref</code>；<code>unexpected_side_effect_count</code> 必须为 <code>0</code>。<code>source_file</code> 固定为“烟测文件名去掉 <code>.tsv</code> 后追加 <code>-sources/&lt;action&gt;.txt</code>”，<code>source_sha256</code> 必须与证据清单中的非空原始文件完全一致。回滚与再次切回分别使用相同契约的 <code>rollback-business-smoke.tsv</code> 和 <code>reapply-business-smoke.tsv</code>，并独立产生各自的 <code>*-sources/</code> 原始证据；不能复制目标版本结果，也不能保留占位符。

## 11. 应用版本回滚

### 11.1 触发条件

任一条件达到发布单阈值时，由回滚决策人启动版本回滚：

- 后端无法稳定启动或健康检查连续失败。
- HTTP 错误率、P95/P99、数据库连接或锁等待超过批准阈值。
- Redis 错误、Flowable job 积压或 deadletter 超过批准阈值。
- 页面、API、流程状态、历史、审计或附件出现不一致。
- 权限、越权、数据安全或附件完整性门禁失败。

### 11.2 回滚步骤

数据库结构保持在向前兼容版本。停止新流量，等待在途请求完成，切回上一版本 JAR 和前端资产：

~~~bash
sudo systemctl stop ruoyi-backend

(cd "$PREVIOUS_RELEASE_DIR" && sudo sha256sum -c SHA256SUMS) \
  | tee "$EVIDENCE_DIR/rollback-source-hash-check.txt"

test "$(readlink -f "$ACTIVE_RELEASE_LINK")" = "$RELEASE_DIR"
test ! -e "$RELEASE_ROOT/.rollback-$PREVIOUS_RELEASE_ID"
test ! -L "$RELEASE_ROOT/.rollback-$PREVIOUS_RELEASE_ID"
sudo ln -s "$PREVIOUS_RELEASE_DIR" \
  "$RELEASE_ROOT/.rollback-$PREVIOUS_RELEASE_ID"
sudo mv -Tf "$RELEASE_ROOT/.rollback-$PREVIOUS_RELEASE_ID" \
  "$ACTIVE_RELEASE_LINK"
test -L "$ACTIVE_RELEASE_LINK"
test "$(readlink -f "$ACTIVE_RELEASE_LINK")" = "$PREVIOUS_RELEASE_DIR"
sudo -u "$NGINX_WORKER_USER" test -r \
  "$ACTIVE_RELEASE_LINK/frontend/index.html"

sudo systemctl start ruoyi-backend
sudo systemctl is-active nginx ruoyi-backend \
  | tee "$EVIDENCE_DIR/rollback-services.txt"
capture_runtime_gate "$EVIDENCE_DIR/rollback"
sudo nginx -t
sudo systemctl reload nginx

curl -fsS http://127.0.0.1:8080/ \
  | tee "$EVIDENCE_DIR/rollback-backend-health.txt" >/dev/null
grep -Fq '后台管理框架' "$EVIDENCE_DIR/rollback-backend-health.txt"
curl -fsSI --resolve "$RUOYI_SERVER_NAME:443:127.0.0.1" \
  "https://$RUOYI_SERVER_NAME/" \
  | tee "$EVIDENCE_DIR/rollback-nginx-health.txt"
~~~

回滚后重复第 10 节的技术健康检查、三组只读 SQL、六类 job/deadletter、核心职责账号烟测和附件一致性检查，并分别写入第 5 节规定的 <code>rollback-*</code> 文件。只有回滚 readiness、Prometheus、数据库和业务证据全部通过，才可恢复流量，并在发布单中记录触发原因、决策时间、回滚耗时和当前版本。彩排还必须重新切回目标版本，并独立产生 <code>reapply-*</code> 文件；生产真实回滚未重新发布目标版本时，不得伪造这些文件，也不得使用 <code>rehearsal</code> profile 声称彩排完成。

彩排的再次切回必须使用原子 current 链接切换，并在恢复流量前重新采集 readiness 与 Prometheus：

~~~bash
sudo systemctl stop ruoyi-backend
test "$(readlink -f "$ACTIVE_RELEASE_LINK")" = "$PREVIOUS_RELEASE_DIR"
test ! -e "$RELEASE_ROOT/.reapply-$RELEASE_ID"
sudo ln -s "$RELEASE_DIR" "$RELEASE_ROOT/.reapply-$RELEASE_ID"
sudo mv -Tf "$RELEASE_ROOT/.reapply-$RELEASE_ID" "$ACTIVE_RELEASE_LINK"
test "$(readlink -f "$ACTIVE_RELEASE_LINK")" = "$RELEASE_DIR"
sudo systemctl start ruoyi-backend
capture_runtime_gate "$EVIDENCE_DIR/reapply"
sudo systemctl is-active nginx ruoyi-backend \
  | tee "$EVIDENCE_DIR/reapply-services.txt"
sudo nginx -t
sudo systemctl reload nginx
~~~

## 12. 附件一致性

发布、灰度和回滚均保持 <code>${RUOYI_PROFILE}/workflow-attachments</code> 独立于应用资产。单节点使用持久卷，多节点使用已批准的共享存储；所有版本读取相同 <code>storage_key</code> 语义。

在停止写流量的检查窗口生成数据库与文件系统清单：

~~~bash
sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --database="$DB_SCHEMA" --batch --skip-column-names --raw --execute="
SELECT storage_key, file_size, sha256
FROM wf_attachment
WHERE storage_deleted_time IS NULL
ORDER BY storage_key;" \
  > "$EVIDENCE_DIR/attachment-db.tsv"

sudo bash -c '
  attachment_root="$1"
  find "$attachment_root" -type f \
    ! -path "$attachment_root/.tmp/*" -print0 \
  | while IFS= read -r -d "" file
    do
      relative="${file#"$attachment_root/"}"
      size="$(stat -c '%s' "$file")"
      digest="$(sha256sum "$file" | cut -d' ' -f1)"
      printf '%s\t%s\t%s\n' "$relative" "$size" "$digest"
    done
  ' _ "$ATTACHMENT_ROOT" \
  | sort \
  > "$EVIDENCE_DIR/attachment-files.tsv"

diff -u \
  "$EVIDENCE_DIR/attachment-db.tsv" \
  "$EVIDENCE_DIR/attachment-files.tsv" \
  | tee "$EVIDENCE_DIR/attachment-diff.txt"
test ! -s "$EVIDENCE_DIR/attachment-diff.txt"

TMP_FILE_COUNT="$(
  sudo find "$ATTACHMENT_ROOT/.tmp" -type f -print 2>/dev/null \
    | wc -l
)"
test "$TMP_FILE_COUNT" = "0"

sudo mysql --defaults-extra-file="$MYSQL_VERIFY_CNF" --protocol=TCP \
  --database="$DB_SCHEMA" --batch --skip-column-names --raw --execute="
SELECT COUNT(*)
FROM wf_attachment_quota_guard
WHERE owner_user_id = 0;" \
  | tee "$EVIDENCE_DIR/attachment-global-guard.txt"
grep -qx '1' "$EVIDENCE_DIR/attachment-global-guard.txt"
~~~

同时确认 <code>.tmp</code> 无发布前遗留、<code>wf_attachment_quota_guard</code> 全局行存在、磁盘可用空间高于 <code>FLOWABLE_ATTACHMENT_MIN_FREE_BYTES</code>。清单完全一致后完成附件门禁。

## 13. 24/72 小时观察

### 13.1 观察频率

| 时间段 | 频率 | 责任人 |
| --- | --- | --- |
| 发布后 0-2 小时 | 每 15 分钟 | 发布负责人和应用值班 |
| 2-24 小时 | 每小时 | 应用值班 |
| 24-72 小时 | 每 4 小时及告警触发时 | 应用与数据库值班 |

### 13.2 观察指标

- Nginx/Java HTTP 成功率、4xx/5xx、P50/P95/P99 和超时。
- JVM 堆、GC、线程、文件句柄、重启次数和 OOM。
- Druid 活跃/等待连接、MySQL 慢 SQL、锁等待、事务回滚和磁盘容量。
- Redis 连接、延迟、内存、拒绝写入、持久化和 key 数量变化。
- 运行实例、活动任务、六类 job、deadletter、timer 执行延迟和重复执行。
- <code>wf_attachment</code> 各状态数量、附件总字节、清理失败和持久卷低水位。
- 登录、发起、认领、完成、取消、终止、详情、导出和附件下载的真实业务结果。

每次采样必须追加到受控监控系统，完整原始采样另存为来源证据；不得删除失败样本后重新封存。24 小时与 72 小时阶段分别生成 <code>observation-24h.tsv</code>、<code>observation-72h.tsv</code>，每个文件是对应完整窗口的七指标汇总表，首行必须与下列 10 列表头完全一致：

~~~text
window_started_at	window_ended_at	observed_at	metric	numerator	denominator	calculated_value	threshold	source_file	source_sha256
~~~

正文必须恰好包含下列七个指标各一行。<code>calculated_value</code> 和 <code>threshold</code> 均使用三位小数，阈值列必须写入表中的固定值：

| metric | numerator / denominator 与计算规则 | <code>threshold</code> 固定值 | 通过条件 |
| --- | --- | --- | --- |
| <code>http_success_rate</code> | 成功请求数 / 总请求数，总请求数至少 100；<code>calculated_value = numerator / denominator * 100</code> | <code>99.900</code> | <code>&gt;= 99.900</code> |
| <code>http_p95</code> | P95 原始值 / 样本数，样本数至少 100；计算值等于 P95 原始值 | <code>500.000</code> | <code>&lt;= 500.000</code> |
| <code>flowable_deadletter</code> | deadletter 数 / 采样数，采样数至少 1；计算值等于 deadletter 数 | <code>0.000</code> | <code>= 0.000</code> |
| <code>mysql_lock_wait</code> | 锁等待原始值 / 采样数，采样数至少 1；计算值等于锁等待原始值 | <code>1.000</code> | <code>&lt;= 1.000</code> |
| <code>redis_latency</code> | Redis 延迟原始值 / 样本数，样本数至少 100；计算值等于延迟原始值 | <code>20.000</code> | <code>&lt;= 20.000</code> |
| <code>attachment_free_bytes</code> | 附件卷可用字节数 / 采样数，采样数至少 1；计算值等于可用字节数 | <code>1073741824.000</code> | <code>&gt;= 1073741824.000</code> |
| <code>business_smoke</code> | 固定为六个必需动作全部通过：<code>6 / 6</code> | <code>100.000</code> | <code>= 100.000</code> |

同一汇总文件中每个指标只能出现一次，七行必须共享同一窗口。24 小时和 72 小时文件的 <code>window_started_at</code> 都必须精确等于 <code>production-switch.tsv.switched_at</code>：<code>observation-24h.tsv</code> 在该时间后精确 24 小时结束，<code>observation-72h.tsv</code> 在同一起点后精确 72 小时结束，不能在 24 小时门禁后重新起算 72 小时。<code>observed_at</code> 必须等于 <code>window_ended_at</code>；执行门禁时结束时间必须小于或等于当前时间，切换前样本、错版本窗口和尚未结束的未来窗口一律拒绝。<code>source_file</code> 固定为“观察文件名去掉 <code>.tsv</code> 后追加 <code>-sources/&lt;metric&gt;.txt</code>”，<code>source_sha256</code> 必须与证据清单中的非空原始来源完全一致；时间、计数、计算值、来源和摘要都必须来自真实窗口，不得保留占位符。

上述数值是待真实 P5 容量测试和容量审批完成前，由当前脚本冻结的最低发布门禁，不是经过容量验证得出的生产承诺，也不能替代真实压测、容量模型、故障场景或审批结论。发布单可以另行冻结更严格的运维阈值，但不能放宽这些最低值；若 P5 审批决定调整冻结契约，必须同步变更并重新验证脚本、测试和本手册。仅通过 TSV 结构门禁或仓库回归测试，不得声称性能已经验证。24 小时复核写入 <code>observation-24h-signoff.tsv</code>，72 小时复核写入 <code>observation-72h-signoff.tsv</code>，格式与第 14 节双人签字一致。观察签字时间不得早于对应窗口结束时间，所有发布、彩排和观察签字的 <code>signed_at</code> 都不得晚于门禁执行时的当前时间；未来签字一律拒绝。

24 小时门禁通过后，重新生成证据清单并以 <code>production-24h</code> profile 验证，保持发布版本并继续观察；72 小时完成后以 <code>production-72h</code> profile 验证。只有后者通过，才能关闭发布窗口、确认备份保留策略并安排旧资产清理。阈值触发时按第 11 节执行应用版本回滚。

## 14. 证据归档与最终签字

证据目录至少包含：

- 发布单、审批、窗口、执行人、复核人和时间线。
- Git commit、构建工具版本、JAR/前端/SQL/配置模板 SHA-256。
- 后端测试、前端构建、两轮彩排和回滚结果。
- 发布前基线、备份哈希与可用性检查、数据库变更日志。
- 三组只读 SQL、表数、六类 job/deadletter 和账号权限输出。
- 灰度各阶段健康检查、监控快照和真实业务烟测标识。
- 附件数据库/文件系统清单及一致性结果。
- 回滚决策与执行记录，或未触发回滚的观察结论。
- 24 小时、72 小时观察记录和最终业务签字。

发布、彩排和观察签字统一使用以下 TSV 结构。<code>release-signoff.tsv</code> 是发布/彩排基础签字；彩排另有 <code>rehearsal-signoff.tsv</code>，观察另有 <code>observation-24h-signoff.tsv</code> 和 <code>observation-72h-signoff.tsv</code>。每个文件都必须由不同的执行人与独立复核人真实签署，不得保留占位符：

~~~text
role	name	signed_at	result
operator	<真实执行人>	<ISO-8601 时间>	PASS
reviewer	<真实独立复核人>	<ISO-8601 时间>	PASS
~~~

证据 profile 与允许声明的阶段严格对应：

| profile | 额外必需证据 | 允许声明 |
| --- | --- | --- |
| <code>fresh-install</code> | 04 手册规定的独立全新安装证据；首次安装允许上一版本为 <code>NONE</code> | 单次全新安装门禁通过，不代表发布彩排 |
| <code>rehearsal</code> | 真实上一版本、<code>fresh-install/</code> 完整归档、环境身份、完整 <code>rollback-*</code>/<code>reapply-*</code> 和两份双人签字；不传双彩排目录参数 | 单轮真实发布/回滚彩排通过 |
| <code>production</code> | 真实上一版本、发布前后、备份、三组 SQL、五角色、附件、<code>production-switch.tsv</code> 及来源、基础双人签字和两轮彩排 PASS 回执；必须传两个彩排证据目录 | 生产切换与即时门禁通过，观察仍在进行 |
| <code>production-24h</code> | <code>production</code> 全部证据及 24 小时观察/签字；必须传两个彩排证据目录 | 24 小时门禁通过，继续观察 |
| <code>production-72h</code> | 前述全部证据及 72 小时观察/签字；必须传两个彩排证据目录 | 72 小时门禁通过，可申请关闭窗口 |

没有真实执行对应阶段时不得创建空壳文件或填写 <code>PASS</code>。门禁脚本只能校验证据结构、结果字段、哈希和敏感边界，不能替代发布审批、真实环境、监控采样或双人复核。

生产族 profile 的证据目录必须额外包含 <code>rehearsal-round-1-receipt.txt</code> 和 <code>rehearsal-round-2-receipt.txt</code>。这两个文件必须先由正式门禁针对两个既有彩排目录现场生成，再纳入 <code>EVIDENCE-SHA256SUMS</code>；不得手工填写、从别处复制或在生成生产证据清单后补写。<code>fresh-install</code> 和 <code>rehearsal</code> profile 不生成这两个文件，也不传 <code>--rehearsal-one-evidence-dir</code> 或 <code>--rehearsal-two-evidence-dir</code>。<code>rehearsal</code> 和生产族 profile 均拒绝缺失上一版本或 <code>previous_release_id=NONE</code>。

归档前先选择真实 profile 并准备门禁参数。只有生产族 profile 才设置两个绝对路径；以下命令会依次重跑两轮正式 <code>rehearsal</code> 门禁、写入两份单行回执，并仅为生产族 profile 追加双彩排参数：

~~~bash
# 按当前真实阶段选择且只选择一个 profile。
export EVIDENCE_PROFILE='<fresh-install_OR_rehearsal_OR_production_OR_production-24h_OR_production-72h>'
export REHEARSAL_ONE_EVIDENCE_DIR="${REHEARSAL_ONE_EVIDENCE_DIR:-}"
export REHEARSAL_TWO_EVIDENCE_DIR="${REHEARSAL_TWO_EVIDENCE_DIR:-}"

GATE_SCRIPT="$RELEASE_DIR/deployment/scripts/workflow-release-gate.sh"
RELEASE_GATE_ARGS=(
  --release-dir "$RELEASE_DIR"
  --release-id "$RELEASE_ID"
  --approved-git-commit "$APPROVED_GIT_COMMIT"
  --approved-manifest-sha256 "$APPROVED_MANIFEST_SHA256"
  --previous-release-dir "$PREVIOUS_RELEASE_DIR"
  --previous-release-id "$PREVIOUS_RELEASE_ID"
  --approved-previous-git-commit "$APPROVED_PREVIOUS_GIT_COMMIT"
  --approved-previous-manifest-sha256 "$APPROVED_PREVIOUS_MANIFEST_SHA256"
)
REHEARSAL_PAIR_ARGS=()

case "$EVIDENCE_PROFILE" in
  fresh-install|rehearsal)
    ;;
  production|production-24h|production-72h)
    case "$REHEARSAL_ONE_EVIDENCE_DIR" in
      /*) ;;
      *) printf 'REHEARSAL_ONE_EVIDENCE_DIR must be absolute\n' >&2; exit 1 ;;
    esac
    case "$REHEARSAL_TWO_EVIDENCE_DIR" in
      /*) ;;
      *) printf 'REHEARSAL_TWO_EVIDENCE_DIR must be absolute\n' >&2; exit 1 ;;
    esac

    REHEARSAL_ROUND_ONE_RECEIPT="$(
      sudo bash "$GATE_SCRIPT" evidence \
        --evidence-dir "$REHEARSAL_ONE_EVIDENCE_DIR" \
        --profile rehearsal \
        "${RELEASE_GATE_ARGS[@]}"
    )"
    REHEARSAL_ROUND_TWO_RECEIPT="$(
      sudo bash "$GATE_SCRIPT" evidence \
        --evidence-dir "$REHEARSAL_TWO_EVIDENCE_DIR" \
        --profile rehearsal \
        "${RELEASE_GATE_ARGS[@]}"
    )"
    printf '%s\n' "$REHEARSAL_ROUND_ONE_RECEIPT" \
      > "$EVIDENCE_DIR/rehearsal-round-1-receipt.txt"
    printf '%s\n' "$REHEARSAL_ROUND_TWO_RECEIPT" \
      > "$EVIDENCE_DIR/rehearsal-round-2-receipt.txt"
    unset REHEARSAL_ROUND_ONE_RECEIPT REHEARSAL_ROUND_TWO_RECEIPT

    REHEARSAL_PAIR_ARGS=(
      --rehearsal-one-evidence-dir "$REHEARSAL_ONE_EVIDENCE_DIR"
      --rehearsal-two-evidence-dir "$REHEARSAL_TWO_EVIDENCE_DIR"
    )
    ;;
  *) printf 'EVIDENCE_PROFILE is invalid\n' >&2; exit 1 ;;
esac

# 回执生成完毕后再扫描并冻结生产证据目录。
grep -IlER \
  'RUOYI_DB_PASSWORD=|SPRING_DATA_REDIS_PASSWORD=|Authorization:[[:space:]]*Bearer|Set-Cookie:' \
  "$EVIDENCE_DIR" \
  > "$EVIDENCE_DIR/sensitive-file-list.txt" \
  || test "$?" -eq 1
cat "$EVIDENCE_DIR/sensitive-file-list.txt"
test ! -s "$EVIDENCE_DIR/sensitive-file-list.txt"

(
  cd "$EVIDENCE_DIR"
  find . -type f \
    ! -path './evidence-file-list.txt' \
    ! -path './EVIDENCE-SHA256SUMS' -print \
    | sort \
    > evidence-file-list.txt
  find . -type f ! -path './EVIDENCE-SHA256SUMS' -print0 \
    | sort -z \
    | xargs -0 sha256sum \
    > EVIDENCE-SHA256SUMS
)
chmod -R go-w "$EVIDENCE_DIR"

sudo bash "$GATE_SCRIPT" \
  evidence \
  --evidence-dir "$EVIDENCE_DIR" \
  --profile "$EVIDENCE_PROFILE" \
  "${RELEASE_GATE_ARGS[@]}" \
  "${REHEARSAL_PAIR_ARGS[@]}"
~~~

执行生产族 profile 前，必须把 <code>REHEARSAL_ONE_EVIDENCE_DIR</code> 和 <code>REHEARSAL_TWO_EVIDENCE_DIR</code> 分别设置为两轮已封存彩排的绝对路径；<code>fresh-install</code> 和 <code>rehearsal</code> 保持二者为空。最终生产门禁会再次检查目录的规范路径和设备/inode、逐轮重跑 <code>rehearsal</code>，拒绝相同环境/数据库/Redis/附件身份、时间重叠或人员复用，比较两轮表数和数据库验收结果，比对相同的 current/previous <code>release-preflight</code> 回执，并逐字核对生产归档中的两份 PASS 回执。根清单只排除自身，因此必须包含 <code>fresh-install/EVIDENCE-SHA256SUMS</code>；按文件名排除全部同名清单会造成不完整归档并被门禁拒绝。门禁成功回执只包含 profile 和 <code>EVIDENCE-SHA256SUMS</code> 自身摘要，可写入批准的发布系统。

门禁完成后不得继续向目录追加文件；24/72 小时补充观察证据时，必须先按受控流程恢复写入、保留既有证据，重新执行两轮彩排回执生成、敏感扫描、文件清单、全量 SHA-256 和更高 profile 门禁。上述命令只定义并校验流程，不表示彩排、生产切换或 24/72 小时观察已经实际执行。

最终完成条件：目标版本在线、数据库与附件一致、所有阈值在批准范围、两轮独立彩排均通过、72 小时真实观察完成、<code>production-72h</code> 证据门禁通过且双人复核完成。任何一项未实际执行都必须保持 <code>blocked</code>、<code>not executed</code> 或 <code>failed</code>，不得声明生产发布完成。
