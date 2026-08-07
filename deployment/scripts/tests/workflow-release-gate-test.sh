#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'
export LC_ALL=C

readonly TEST_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly GATE_SCRIPT="$(realpath -e -- "$TEST_SCRIPT_DIR/../workflow-release-gate.sh")"
readonly TEST_ROOT="$(mktemp -d /tmp/workflow-release-gate-test.XXXXXXXX)"
readonly TEST_REHEARSAL_ONE_DIR="$TEST_ROOT/rehearsal-round-1"
readonly TEST_REHEARSAL_TWO_DIR="$TEST_ROOT/rehearsal-round-2"
# 当前版本与上一版本必须绑定不同的批准 commit，避免回滚包由当前资产伪造。
readonly TEST_CURRENT_GIT_COMMIT='0123456789abcdef0123456789abcdef01234567'
readonly TEST_PREVIOUS_GIT_COMMIT='fedcba9876543210fedcba9876543210fedcba98'

TEST_COUNT=0
TEST_FAILURE_COUNT=0
# 两轮标准彩排的正式 PASS 回执；仅用于重复构造生产证据夹具，生产门禁仍会现场重验。
TEST_REHEARSAL_ONE_RECEIPT=''
TEST_REHEARSAL_TWO_RECEIPT=''
# 当前/上一标准发布包的批准锚点只计算一次，避免数百次重验重复启动摘要子进程。
TEST_FRESH_GIT_COMMIT=''
TEST_FRESH_MANIFEST_SHA256=''
TEST_CURRENT_MANIFEST_SHA256=''
TEST_PREVIOUS_MANIFEST_SHA256=''

#
# 删除本测试创建的唯一临时目录，且在删除前再次校验固定前缀，避免路径误判。
# 参数：无。
# 返回：0 表示临时目录已清理；路径不符合测试前缀时拒绝删除。
#
cleanup_test_root() {
  case "$TEST_ROOT" in
    /tmp/workflow-release-gate-test.*)
      rm -rf -- "$TEST_ROOT"
      ;;
    *)
      printf 'refusing to remove unexpected test path: %s\n' "$TEST_ROOT" >&2
      return 1
      ;;
  esac
}
trap cleanup_test_root EXIT

#
# 为发布包重新生成严格覆盖全部普通文件的 SHA-256 清单。
# 参数：$1（字符串）发布包绝对目录。
# 返回：0 表示 SHA256SUMS 已按稳定路径顺序生成。
#
refresh_bundle_manifest() {
  local bundle_dir="$1"

  (
    cd "$bundle_dir"
    find . -type f ! -name SHA256SUMS -print0 \
      | sort -z \
      | xargs -0 sha256sum \
      > SHA256SUMS
  )
}

#
# 创建满足正式目录契约的最小发布包测试夹具，内容仅用于验证门禁本身。
# 参数：$1（字符串）发布包目录；$2（字符串）版本标识；$3（字符串）上一版本标识或 NONE；
#       $4（可选字符串）该发布包绑定的批准 Git commit。
# 返回：0 表示夹具及其哈希清单创建完成。
#
create_bundle() {
  local bundle_dir="$1"
  local release_id="$2"
  local previous_release_id="$3"
  local git_commit="${4:-$TEST_CURRENT_GIT_COMMIT}"

  mkdir -p \
    "$bundle_dir/frontend" \
    "$bundle_dir/sql" \
    "$bundle_dir/sql/flowable/business" \
    "$bundle_dir/sql/flowable/menu" \
    "$bundle_dir/sql/flowable/mysql/8.0.0/create" \
    "$bundle_dir/sql/flowable/verify" \
    "$bundle_dir/deployment/config" \
    "$bundle_dir/deployment/nginx" \
    "$bundle_dir/deployment/systemd" \
    "$bundle_dir/deployment/scripts/tests"
  printf 'test jar asset\n' > "$bundle_dir/ruoyi-admin.jar"
  printf '<!doctype html><title>test</title>\n' > "$bundle_dir/frontend/index.html"
  printf '# Deployment\n\nToken secret files are generated and persisted by the service.\n' \
    > "$bundle_dir/deployment/README.md"
  {
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql\n'
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql\n'
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql\n'
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql\n'
    printf 'flowable/business/8.0.0__workflow_model_version_guard.sql\n'
    printf 'flowable/business/8.0.0__workflow_business.sql\n'
    printf 'flowable/menu/8.0.0__workflow_menu.sql\n'
  } > "$bundle_dir/sql/release-order.txt"
  printf 'CREATE TABLE ACT_GE_PROPERTY (NAME_ VARCHAR(64));\n' \
    > "$bundle_dir/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql"
  printf 'CREATE TABLE ACT_RE_MODEL (ID_ VARCHAR(64));\n' \
    > "$bundle_dir/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql"
  printf 'CREATE TABLE ACT_HI_PROCINST (ID_ VARCHAR(64));\n' \
    > "$bundle_dir/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql"
  printf 'CREATE TABLE ACT_DMN_DECISION (ID_ VARCHAR(64));\n' \
    > "$bundle_dir/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql"
  printf 'ALTER TABLE ACT_RE_MODEL ADD UNIQUE (KEY_, VERSION_, TENANT_ID_);\n' \
    > "$bundle_dir/sql/flowable/business/8.0.0__workflow_model_version_guard.sql"
  printf 'CREATE TABLE wf_category (category_id BIGINT PRIMARY KEY);\n' \
    > "$bundle_dir/sql/flowable/business/8.0.0__workflow_business.sql"
  printf 'INSERT INTO sys_menu (menu_name) VALUES (''workflow'');\n' \
    > "$bundle_dir/sql/flowable/menu/8.0.0__workflow_menu.sql"
  {
    printf 'server:\n'
    printf '  address: 127.0.0.1\n'
    printf 'management:\n'
    printf '  server:\n'
    printf '    address: 127.0.0.1\n'
    printf '    port: ${RUOYI_MANAGEMENT_PORT:18080}\n'
    printf '  endpoints:\n'
    printf '    web:\n'
    printf '      exposure:\n'
    printf '        include: health,prometheus\n'
    printf 'flowable:\n'
    printf '  database-schema-update: "false"\n'
    printf '  variable-json-mapper: jackson\n'
    printf '  runtime:\n'
    printf '    production-gate-enabled: true\n'
    printf '    metrics-snapshot-max-age: ${FLOWABLE_RUNTIME_METRICS_SNAPSHOT_MAX_AGE:PT3M}\n'
    printf 'token:\n'
    printf '  secret: ${RUOYI_TOKEN_SECRET:}\n'
    printf '  secret-file:\n'
    printf '    enabled: true\n'
    printf '    path: ${RUOYI_TOKEN_SECRET_FILE:/var/lib/ruoyi-secrets/token-secret}\n'
  } > "$bundle_dir/deployment/config/application.yml"
  {
    printf 'spring:\n'
    printf '  datasource:\n'
    printf '    druid:\n'
    printf '      master:\n'
    printf '        password: ${RUOYI_DB_PASSWORD}\n'
  } > "$bundle_dir/deployment/config/application-druid.yml"
  {
    printf 'RUOYI_MANAGEMENT_PORT=18080\n'
    printf 'FLOWABLE_RUNTIME_METRICS_SNAPSHOT_MAX_AGE=PT3M\n'
    printf '%s%s\n' 'RUOYI_TOKEN_' 'SECRET='
    printf 'RUOYI_TOKEN_SECRET_FILE=/var/lib/ruoyi-secrets/token-secret\n'
    printf '%s%s\n' 'DRUID_MONITOR_' 'PASSWORD='
  } > "$bundle_dir/deployment/config/ruoyi.env.example"
  {
    printf 'server {\n'
    printf '    location = /prod-api/actuator {\n'
    printf '        return 404;\n'
    printf '    }\n'
    printf '    location ^~ /prod-api/actuator/ {\n'
    printf '        return 404;\n'
    printf '    }\n'
    printf '    location /prod-api/ {\n'
    printf '        proxy_pass http://127.0.0.1:8080/;\n'
    printf '    }\n'
    printf '}\n'
  } > "$bundle_dir/deployment/nginx/ruoyi.conf"
  printf '[Service]\nExecStart=/bin/true\n' \
    > "$bundle_dir/deployment/systemd/ruoyi-backend.service"
  printf '#!/usr/bin/env bash\nexit 0\n' \
    > "$bundle_dir/deployment/scripts/workflow-release-gate.sh"
  printf '#!/usr/bin/env bash\nexit 0\n' \
    > "$bundle_dir/deployment/scripts/tests/workflow-release-gate-test.sh"
  printf 'SELECT "PASS";\n' > "$bundle_dir/sql/flowable/verify/8.0.0__verify.sql"
  printf 'SELECT "PASS";\n' \
    > "$bundle_dir/sql/flowable/verify/8.0.0__verify_workflow_business.sql"
  printf 'SELECT "PASS";\n' \
    > "$bundle_dir/sql/flowable/verify/8.0.0__verify_workflow_menu.sql"
  {
    printf 'format_version=1\n'
    printf 'release_id=%s\n' "$release_id"
    printf 'git_commit=%s\n' "$git_commit"
    printf 'previous_release_id=%s\n' "$previous_release_id"
    printf 'built_at_utc=2019-12-31T00:00:00Z\n'
  } > "$bundle_dir/RELEASE-METADATA"
  refresh_bundle_manifest "$bundle_dir"
}

#
# 读取测试发布包中唯一的 Git commit，用于构造门禁的外部批准锚点。
# 参数：$1（字符串）发布包绝对目录。
# 返回：标准输出返回 RELEASE-METADATA 中的 40 位 commit；缺失或重复时返回非零。
#
bundle_git_commit() {
  local bundle_dir="$1"

  awk -F '=' '
    $1 == "git_commit" { count++; value = $2 }
    END {
      if (count != 1) exit 1
      print value
    }
  ' "$bundle_dir/RELEASE-METADATA"
}

#
# 计算测试发布包 SHA256SUMS 文件的外部批准摘要。
# 参数：$1（字符串）发布包绝对目录。
# 返回：标准输出返回小写 64 位 SHA-256。
#
bundle_manifest_sha256() {
  local bundle_dir="$1"
  local digest

  digest="$(sha256sum -- "$bundle_dir/SHA256SUMS")"
  printf '%s\n' "${digest%% *}"
}

#
# 返回标准发布包的缓存批准锚点；其他一次性夹具仍从各自元数据和清单现场读取。
# 参数：$1（字符串）发布包目录；$2（字符串）锚点类型 git|manifest。
# 返回：标准输出返回缓存值或现场计算值。
#
approved_bundle_anchor() {
  local bundle_dir="$1"
  local anchor_type="$2"

  case "$bundle_dir:$anchor_type" in
    "$TEST_ROOT/fresh-1:git") printf '%s\n' "$TEST_FRESH_GIT_COMMIT" ;;
    "$TEST_ROOT/fresh-1:manifest") printf '%s\n' "$TEST_FRESH_MANIFEST_SHA256" ;;
    "$TEST_ROOT/current-1:git") printf '%s\n' "$TEST_CURRENT_GIT_COMMIT" ;;
    "$TEST_ROOT/current-1:manifest") printf '%s\n' "$TEST_CURRENT_MANIFEST_SHA256" ;;
    "$TEST_ROOT/previous-1:git") printf '%s\n' "$TEST_PREVIOUS_GIT_COMMIT" ;;
    "$TEST_ROOT/previous-1:manifest") printf '%s\n' "$TEST_PREVIOUS_MANIFEST_SHA256" ;;
    *:git) bundle_git_commit "$bundle_dir" ;;
    *:manifest) bundle_manifest_sha256 "$bundle_dir" ;;
    *) return 1 ;;
  esac
}

#
# 使用发布包自身冻结的 commit 与清单摘要执行正式 preflight，减少测试夹具重复拼接参数。
# 参数：$1/$2（字符串）当前发布目录/ID；$3/$4（可选字符串）上一发布目录/ID。
# 返回：透传 preflight 的退出状态与标准输出。
#
run_preflight_gate() {
  local release_dir="$1"
  local release_id="$2"
  local previous_release_dir="${3:-}"
  local previous_release_id="${4:-NONE}"
  local -a arguments=(
    --release-dir "$release_dir"
    --release-id "$release_id"
    --approved-git-commit "$(approved_bundle_anchor "$release_dir" git)"
    --approved-manifest-sha256 "$(approved_bundle_anchor "$release_dir" manifest)"
  )

  if [[ "$previous_release_id" != 'NONE' || -n "$previous_release_dir" ]]; then
    arguments+=(
      --previous-release-dir "$previous_release_dir"
      --previous-release-id "$previous_release_id"
      --approved-previous-git-commit "$(approved_bundle_anchor "$previous_release_dir" git)"
      --approved-previous-manifest-sha256 "$(approved_bundle_anchor "$previous_release_dir" manifest)"
    )
  fi
  "$GATE_SCRIPT" preflight "${arguments[@]}"
}

#
# 为证据目录重新生成严格覆盖全部普通文件的 SHA-256 清单。
# 参数：$1（字符串）证据目录；$2（字符串）发布目录；$3（字符串）发布 ID。
# 返回：0 表示 EVIDENCE-SHA256SUMS 已按稳定路径顺序生成。
#
refresh_evidence_manifest() {
  local evidence_dir="$1"

  (
    cd "$evidence_dir"
    find . -type f ! -path './EVIDENCE-SHA256SUMS' -print0 \
      | sort -z \
      | xargs -0 sha256sum \
      > EVIDENCE-SHA256SUMS
  )
}

#
# 在修改嵌套全新安装夹具后，依次刷新内层和整轮彩排清单，保持两级归档均可验证。
# 参数：$1（字符串）彩排证据目录。
# 返回：0 表示内层与父级 EVIDENCE-SHA256SUMS 均已更新。
#
refresh_rehearsal_manifests() {
  local rehearsal_dir="$1"

  refresh_evidence_manifest "$rehearsal_dir/fresh-install"
  refresh_evidence_manifest "$rehearsal_dir"
}

#
# 返回与正式只读 SQL 计算结果一致的 detail 字段，防止成功夹具依赖任意占位文本。
# 参数：$1（字符串）正式检查项名称。
# 返回：标准输出返回该检查项满足生产阈值时的结构化明细。
#
database_check_detail() {
  local check_name="$1"

  case "$check_name" in
    schema_versions)
      printf 'common.schema.version=8.0.0.0, schema.version=8.0.0.0'
      ;;
    model_version_unique_constraint)
      printf 'matching_indexes=1, indexes=ACT_UNIQ_MODEL_VERSION'
      ;;
    model_version_duplicate_groups) printf 'duplicate_groups=0' ;;
    missing_required_tables|unexpected_flowable_objects|disabled_module_objects)
      printf 'issues=0, objects=none'
      ;;
    deadletter_jobs) printf 'actual=0' ;;
    flowable_dmn_table_presence|workflow_connector_table_presence)
      printf 'missing_or_invalid=none'
      ;;
    workflow_connector_columns) printf 'missing=none' ;;
    workflow_business_tables) printf 'present=8, missing=none' ;;
    workflow_business_columns) printf 'missing=none' ;;
    wf_attachment_cleanup_retry_columns) printf 'invalid=none' ;;
    wf_category_active_code)
      printf 'columns=1, extra=STORED GENERATED, expression=if(del_flag=0,code,NULL)'
      ;;
    workflow_business_indexes) printf 'issues=0, indexes=none' ;;
    workflow_business_checks) printf 'missing_or_unenforced=none' ;;
    workflow_business_foreign_keys) printf 'missing_or_invalid=none' ;;
    wf_attachment_cleanup_retry_check_clause)
      printf 'constraints=1, enforced=YES, canonical_sha256=%064d' 1
      ;;
    workflow_business_data_integrity) printf 'issues=0, detail=none' ;;
    workflow_runtime_integration_tables) printf 'present=2, missing=none' ;;
    workflow_runtime_integration_columns|workflow_runtime_integration_indexes|workflow_extension_columns)
      printf 'missing=none'
      ;;
    workflow_runtime_integration_checks|workflow_extension_checks)
      printf 'missing_or_unenforced=none'
      ;;
    workflow_runtime_integration_foreign_keys) printf 'matching=1, expected=1' ;;
    workflow_runtime_integration_data_integrity|workflow_extension_data_integrity)
      printf 'issues=0, detail=none'
      ;;
    workflow_extension_tables) printf 'present=7, missing=none' ;;
    workflow_extension_indexes) printf 'issues=0, indexes=none' ;;
    workflow_extension_foreign_keys) printf 'missing_or_invalid=none' ;;
    workflow_menu_count) printf 'rows=72, natural_keys=72' ;;
    workflow_menu_tree) printf 'directories=2, pages=17, buttons=53, invalid_routes=0' ;;
    workflow_retired_permissions) printf 'legacy_rows=0' ;;
    workflow_roles) printf 'active_roles=5, duplicate_roles=0' ;;
    workflow_admin_menu_scope) printf 'assigned=72, expected=72' ;;
    workflow_admin_only_instance_management)
      printf 'unauthorized_assignments=0, roles=none, admin_management_permissions=2'
      ;;
    workflow_auditor_read_only) printf 'write_permissions=0, values=none' ;;
    *) return 1 ;;
  esac
}

#
# 写入三套正式只读 SQL 的固定结构和 38 个完整检查项，模拟 mysql --batch --raw 原始输出。
# 参数：$1（字符串）目标 TSV 文件。
# 返回：0 表示数据库验收夹具已按正式脚本顺序写入。
#
write_database_verify() {
  local target_file="$1"
  local check_name
  local verify_file
  local detail
  local -a engine_checks=(
    schema_versions model_version_unique_constraint model_version_duplicate_groups
    missing_required_tables unexpected_flowable_objects disabled_module_objects
    deadletter_jobs
  )
  local -a business_checks=(
    flowable_dmn_table_presence workflow_connector_table_presence workflow_connector_columns
    workflow_business_tables workflow_business_columns
    wf_attachment_cleanup_retry_columns wf_category_active_code
    workflow_business_indexes workflow_business_checks workflow_business_foreign_keys
    wf_attachment_cleanup_retry_check_clause workflow_business_data_integrity
    workflow_runtime_integration_tables workflow_runtime_integration_columns
    workflow_runtime_integration_indexes workflow_runtime_integration_checks
    workflow_runtime_integration_foreign_keys workflow_runtime_integration_data_integrity
    workflow_extension_tables workflow_extension_columns workflow_extension_indexes
    workflow_extension_checks workflow_extension_foreign_keys workflow_extension_data_integrity
  )
  local -a menu_checks=(
    workflow_menu_count workflow_menu_tree workflow_retired_permissions
    workflow_roles workflow_admin_menu_scope
    workflow_admin_only_instance_management workflow_auditor_read_only
  )

  : > "$target_file"
  for verify_file in \
    '8.0.0__verify.sql' \
    '8.0.0__verify_workflow_business.sql' \
    '8.0.0__verify_workflow_menu.sql'; do
    printf 'FILE\t%s\n' "$verify_file" >> "$target_file"
    printf 'check_name\tresult\tdetail\n' >> "$target_file"
    case "$verify_file" in
      8.0.0__verify.sql)
        for check_name in "${engine_checks[@]}"; do
          detail="$(database_check_detail "$check_name")"
          printf '%s\tPASS\t%s\n' "$check_name" "$detail" >> "$target_file"
        done
        ;;
      8.0.0__verify_workflow_business.sql)
        for check_name in "${business_checks[@]}"; do
          detail="$(database_check_detail "$check_name")"
          printf '%s\tPASS\t%s\n' "$check_name" "$detail" >> "$target_file"
        done
        ;;
      8.0.0__verify_workflow_menu.sql)
        for check_name in "${menu_checks[@]}"; do
          detail="$(database_check_detail "$check_name")"
          printf '%s\tPASS\t%s\n' "$check_name" "$detail" >> "$target_file"
        done
        ;;
    esac
  done
}

#
# 创建可通过 fresh-install profile 的完整证据夹具，覆盖关键 PASS、零差异和双人签字语义。
# 参数：$1（字符串）证据目录；$2/$3（字符串）当前目录/ID；$4/$5（字符串）上一版本目录/ID。
# 返回：0 表示证据夹具及完整性清单创建完成。
#
create_fresh_install_evidence() {
  local evidence_dir="$1"
  local release_dir="$2"
  local release_id="$3"
  local previous_release_dir="${4:-}"
  local previous_release_id="${5:-NONE}"
  local evidence_file
  local table_number
  local -a preflight_arguments=(
    --release-dir "$release_dir"
    --release-id "$release_id"
    --approved-git-commit "$(approved_bundle_anchor "$release_dir" git)"
    --approved-manifest-sha256 "$(approved_bundle_anchor "$release_dir" manifest)"
  )
  local -a generic_nonempty_files=(
    java-version.txt mysql-version.txt redis-version.txt nginx-version.txt
    envsubst-version.txt openssl-version.txt node-version.txt npm-version.txt
    profile-filesystem.txt accounts-ignore-check.txt
    app-account-grants.txt backup-account-grants.txt
    backup-smoke/schema-sha256.txt backup-smoke/mysqlcheck.txt
    redis-persistence.txt redis-memory-policy.txt
    backend-sha256.txt frontend-sha256.txt release-gate-test.log
    admin-bootstrap-startup.log backend-after-bootstrap.txt
    backend-health.txt backend-liveness.json backend-readiness.json
    prometheus-scrape.txt nginx-health.txt flowable-jobs.tsv
  )

  mkdir -p "$evidence_dir/backup-smoke"
  for evidence_file in "${generic_nonempty_files[@]}"; do
    printf 'evidence\n' > "$evidence_dir/$evidence_file"
  done
  printf '%064d  backup-smoke/schema.sql\n' 1 \
    > "$evidence_dir/backup-smoke/schema-sha256.txt"
  : > "$evidence_dir/backup-smoke/mysqlcheck.txt"
  for ((table_number = 1; table_number <= 84; table_number++)); do
    printf 'ry_vue_backup_verify.table_%02d OK\n' "$table_number" \
      >> "$evidence_dir/backup-smoke/mysqlcheck.txt"
  done
  printf 'empty_schema_table_count=0\n' > "$evidence_dir/empty-schema-check.txt"
  printf '84\t20\t11\t36\t17\n' > "$evidence_dir/table-counts.tsv"
  printf 'PONG\n' > "$evidence_dir/redis-ping.txt"
  if [[ "$previous_release_id" != 'NONE' || -n "$previous_release_dir" ]]; then
    preflight_arguments+=(
      --previous-release-dir "$previous_release_dir"
      --previous-release-id "$previous_release_id"
      --approved-previous-git-commit "$(approved_bundle_anchor "$previous_release_dir" git)"
      --approved-previous-manifest-sha256 "$(approved_bundle_anchor "$previous_release_dir" manifest)"
    )
  fi
  "$GATE_SCRIPT" preflight "${preflight_arguments[@]}" \
    > "$evidence_dir/release-preflight.txt"
  printf '1\n' > "$evidence_dir/mysql-connectivity.txt"
  printf 'PONG\n' > "$evidence_dir/redis-connectivity.txt"
  printf 'app\tDELETE,INSERT,SELECT,UPDATE\t0\t0\t0\n' \
    > "$evidence_dir/app-account-grants.txt"
  printf 'backup\tSELECT,SHOW VIEW,TRIGGER\t0\t0\t0\n' \
    > "$evidence_dir/backup-account-grants.txt"
  {
    printf 'aof_enabled=1\n'
    printf 'aof_last_bgrewrite_status=ok\n'
    printf 'aof_last_write_status=ok\n'
  } > "$evidence_dir/redis-persistence.txt"
  printf 'maxmemory-policy=noeviction\n' > "$evidence_dir/redis-memory-policy.txt"
  write_runtime_health_evidence \
    "$evidence_dir/backend-liveness.json" \
    "$evidence_dir/backend-readiness.json" \
    "$evidence_dir/prometheus-scrape.txt"
  printf 'active\n' > "$evidence_dir/nginx-service-status.txt"
  printf 'PASS\n' > "$evidence_dir/admin-bootstrap-precheck.txt"
  printf 'PASS\n' > "$evidence_dir/admin-bootstrap-state.txt"
  printf 'active\nactive\n' > "$evidence_dir/service-status.txt"
  printf 'PASS\n' > "$evidence_dir/admin-real-login.txt"
  write_database_verify "$evidence_dir/database-verify.tsv"
  {
    printf 'role\tname\tsigned_at\tresult\n'
    printf 'operator\toperator-a\t2020-01-05T08:00:00+08:00\tPASS\n'
    printf 'reviewer\treviewer-b\t2020-01-05T08:01:00+08:00\tPASS\n'
  } > "$evidence_dir/install-signoff.tsv"
  write_environment_identity \
    "$evidence_dir/environment-identity.tsv" "$(basename -- "$evidence_dir")" \
    '2020-01-05T07:00:00+08:00' '2020-01-05T07:30:00+08:00'
  : > "$evidence_dir/git-status-before-install.txt"
  : > "$evidence_dir/attachment-root-symlinks.txt"
  : > "$evidence_dir/attachment-restore-diff.txt"
  : > "$evidence_dir/sensitive-file-list.txt"
  refresh_evidence_manifest "$evidence_dir"
}

#
# 写入符合职责分离要求的双人签字测试证据。
# 参数：$1（字符串）目标文件；$2（字符串）执行人；$3（字符串）复核人；$4/$5（可选字符串）签字时间。
# 返回：0 表示 TSV 已写入。
#
write_signoff() {
  local target_file="$1"
  local operator_name="$2"
  local reviewer_name="$3"
  local operator_time="${4:-2020-01-05T08:00:00+08:00}"
  local reviewer_time="${5:-2020-01-05T08:01:00+08:00}"

  {
    printf 'role\tname\tsigned_at\tresult\n'
    printf 'operator\t%s\t%s\tPASS\n' "$operator_name" "$operator_time"
    printf 'reviewer\t%s\t%s\tPASS\n' "$reviewer_name" "$reviewer_time"
  } > "$target_file"
}

#
# 写入一轮全新安装的环境身份和严格时间区间，供双彩排独立性测试复用。
# 参数：$1（字符串）目标 TSV；$2（字符串）运行 ID；$3/$4（字符串）开始/结束时间。
# 返回：0 表示身份 TSV 已写入。
#
write_environment_identity() {
  local target_file="$1"
  local run_id="$2"
  local started_at="$3"
  local finished_at="$4"

  {
    printf 'run_id\tenvironment_id\tstarted_at\tfinished_at\tdatabase_identity\tredis_identity\tattachment_storage_identity\n'
    printf '%s\tenvironment-%s\t%s\t%s\tmysql-%s\tredis-%s\tattachment-%s\n' \
      "$run_id" "$run_id" "$started_at" "$finished_at" \
      "$run_id" "$run_id" "$run_id"
  } > "$target_file"
}

#
# 写入 Actuator liveness/readiness 与 Prometheus 的通过夹具，证明流量切换前已有真实运行快照。
# 参数：$1/$2（字符串）liveness/readiness JSON 文件；$3（字符串）Prometheus 文本文件。
# 返回：0 表示三份规范化证据均已写入。
#
write_runtime_health_evidence() {
  local liveness_file="$1"
  local readiness_file="$2"
  local prometheus_file="$3"

  printf '{"status":"UP"}\n' > "$liveness_file"
  printf '{"status":"UP"}\n' > "$readiness_file"
  {
    printf 'workflow_runtime_metrics_snapshot_available 1\n'
    printf 'workflow_attachment_cleanup_lock_degraded 0\n'
  } > "$prometheus_file"
}

#
# 写入生产切换事件及其原始来源摘要，将测试证据绑定到指定发布包清单。
# 参数：$1（字符串）生产证据目录；$2（字符串）发布目录；$3（字符串）发布 ID；
#       $4（可选字符串）切换时间。
# 返回：0 表示来源文件和 production-switch.tsv 已写入。
#
write_production_switch() {
  local evidence_dir="$1"
  local release_dir="$2"
  local release_id="$3"
  local switched_at="${4:-2020-01-01T12:00:00+08:00}"
  local release_manifest_sha256
  local source_sha256

  release_manifest_sha256="$(sha256sum -- "$release_dir/SHA256SUMS")"
  release_manifest_sha256="${release_manifest_sha256%% *}"
  {
    printf 'active_release=%s\n' "$release_dir"
    printf 'release_id=%s\n' "$release_id"
    printf 'release_manifest_sha256=%s\n' "$release_manifest_sha256"
    printf 'switched_at=%s\n' "$switched_at"
  } > "$evidence_dir/production-switch-source.txt"
  source_sha256="$(sha256sum -- "$evidence_dir/production-switch-source.txt")"
  source_sha256="${source_sha256%% *}"
  {
    printf 'release_id\trelease_manifest_sha256\tswitched_at\tsource_file\tsource_sha256\n'
    printf '%s\t%s\t%s\tproduction-switch-source.txt\t%s\n' \
      "$release_id" "$release_manifest_sha256" "$switched_at" "$source_sha256"
  } > "$evidence_dir/production-switch.tsv"
}

#
# 写入五职责角色六动作烟测及逐动作原始证据，独立记录传输状态与 AjaxResult 业务码。
# 参数：$1（字符串）目标文件；$2（字符串）admin 实际传输状态；$3（可选字符串）拒绝动作实际传输状态；$4（可选字符串）拒绝动作实际业务码。
# 返回：0 表示 TSV 与绑定来源文件均已写入。
#
write_business_smoke() {
  local target_file="$1"
  local admin_actual_transport_status="$2"
  local denied_actual_transport_status="${3:-200}"
  local denied_actual_business_code="${4:-403}"
  local evidence_dir
  local source_directory_name
  local source_directory
  local action
  local role
  local method
  local object_ref
  local request_id
  local expected_transport_status
  local actual_transport_status
  local expected_business_code
  local actual_business_code
  local state_before
  local state_after
  local db_before_sha256
  local db_after_sha256
  local audit_ref
  local source_file
  local source_sha256
  local sequence=0

  evidence_dir="$(dirname -- "$target_file")"
  source_directory_name="$(basename -- "$target_file" .tsv)-sources"
  source_directory="$evidence_dir/$source_directory_name"
  mkdir -p "$source_directory"

  {
    printf 'started_at\tfinished_at\trole\taction\tmethod\tobject_ref\trequest_id\texpected_transport_status\tactual_transport_status\texpected_business_code\tactual_business_code\tstate_before\tstate_after\tdb_before_sha256\tdb_after_sha256\taudit_ref\tunexpected_side_effect_count\tsource_file\tsource_sha256\n'
    for action in \
      instance_audit_view model_version_view process_start task_complete \
      instance_detail instance_terminate_denied; do
      sequence=$((sequence + 1))
      printf -v request_id '00000000-0000-4000-8000-%012d' "$sequence"
      printf -v db_before_sha256 '%064d' "$sequence"
      db_after_sha256="$db_before_sha256"
      expected_transport_status='200'
      actual_transport_status='200'
      expected_business_code='200'
      actual_business_code='200'
      case "$action" in
        instance_audit_view)
          role='workflow_admin'; method='GET'; object_ref='instance:100'
          actual_transport_status="$admin_actual_transport_status"
          state_before='RUNNING'; state_after='RUNNING'; audit_ref='trace:admin-view-1'
          ;;
        model_version_view)
          role='workflow_designer'; method='GET'; object_ref='model:200'
          state_before='MODEL_V1'; state_after='MODEL_V1'; audit_ref='trace:model-view-1'
          ;;
        process_start)
          role='workflow_starter'; method='POST'; object_ref='process:300'
          state_before='NOT_STARTED'; state_after='RUNNING'; audit_ref='sys_oper_log:3001'
          printf -v db_after_sha256 '%064d' 103
          ;;
        task_complete)
          role='workflow_approver'; method='POST'; object_ref='task:400'
          state_before='ACTIVE'; state_after='COMPLETED'; audit_ref='sys_oper_log:4001'
          printf -v db_after_sha256 '%064d' 104
          ;;
        instance_detail)
          role='workflow_auditor'; method='GET'; object_ref='instance:100'
          state_before='RUNNING'; state_after='RUNNING'; audit_ref='trace:auditor-view-1'
          ;;
        instance_terminate_denied)
          role='workflow_auditor'; method='POST'; object_ref='instance:100'
          expected_business_code='403'
          actual_transport_status="$denied_actual_transport_status"
          actual_business_code="$denied_actual_business_code"
          state_before='RUNNING'; state_after='RUNNING'; audit_ref='security_log:terminate-denied-1'
          ;;
      esac
      source_file="$source_directory_name/$action.txt"
      printf 'request=%s action=%s transport=%s business=%s before=%s after=%s audit=%s\n' \
        "$request_id" "$action" "$actual_transport_status" "$actual_business_code" \
        "$state_before" "$state_after" "$audit_ref" \
        > "$evidence_dir/$source_file"
      source_sha256="$(sha256sum -- "$evidence_dir/$source_file")"
      source_sha256="${source_sha256%% *}"
      printf '2026-07-27T08:%02d:00+08:00\t2026-07-27T08:%02d:05+08:00\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t0\t%s\t%s\n' \
        "$sequence" "$sequence" "$role" "$action" "$method" "$object_ref" "$request_id" \
        "$expected_transport_status" "$actual_transport_status" \
        "$expected_business_code" "$actual_business_code" "$state_before" "$state_after" \
        "$db_before_sha256" "$db_after_sha256" "$audit_ref" "$source_file" "$source_sha256"
    done
  } > "$target_file"
}

#
# 写入覆盖七类指标的原始计数、计算值、固定阈值和逐指标来源证据。
# 参数：$1（字符串）目标文件；$2（字符串）HTTP 成功请求数，可用于构造非法值用例；
#       $3/$4（可选字符串）窗口结束/开始时间，用于构造确定性的过去或未来窗口。
# 返回：0 表示观察 TSV 与绑定来源文件均已写入。
#
write_observation() {
  local target_file="$1"
  local http_success_numerator="$2"
  local evidence_dir
  local source_directory_name
  local source_directory
  local window_started_at
  local window_ended_at="${3:-}"
  local explicit_window_started_at="${4:-}"
  local metric
  local numerator
  local denominator
  local calculated_value
  local threshold
  local source_file
  local source_sha256

  evidence_dir="$(dirname -- "$target_file")"
  source_directory_name="$(basename -- "$target_file" .tsv)-sources"
  source_directory="$evidence_dir/$source_directory_name"
  mkdir -p "$source_directory"
  if [[ -n "$explicit_window_started_at" ]]; then
    window_started_at="$explicit_window_started_at"
  else
    case "$(basename -- "$target_file")" in
      observation-24h.tsv) window_started_at='2020-01-01T12:00:00+08:00' ;;
      observation-72h.tsv) window_started_at='2020-01-01T12:00:00+08:00' ;;
      *) window_started_at='2020-01-01T12:00:00+08:00' ;;
    esac
  fi
  if [[ -z "$window_ended_at" ]]; then
    case "$(basename -- "$target_file")" in
      observation-24h.tsv) window_ended_at='2020-01-02T12:00:00+08:00' ;;
      observation-72h.tsv) window_ended_at='2020-01-04T12:00:00+08:00' ;;
      *) window_ended_at='2020-01-02T12:00:00+08:00' ;;
    esac
  fi

  {
    printf 'window_started_at\twindow_ended_at\tobserved_at\tmetric\tnumerator\tdenominator\tcalculated_value\tthreshold\tsource_file\tsource_sha256\n'
    for metric in \
      http_success_rate http_p95 flowable_deadletter mysql_lock_wait \
      redis_latency attachment_free_bytes business_smoke; do
      case "$metric" in
        http_success_rate)
          numerator="$http_success_numerator"; denominator='10000'; threshold='99.900'
          if [[ "$numerator" =~ ^[0-9]+$ ]]; then
            calculated_value="$(awk -v a="$numerator" -v b="$denominator" \
              'BEGIN { printf "%.3f", (a / b) * 100 }')"
          else
            calculated_value="$numerator"
          fi
          ;;
        http_p95) numerator='100'; denominator='10000'; calculated_value='100.000'; threshold='500.000' ;;
        flowable_deadletter) numerator='0'; denominator='1440'; calculated_value='0.000'; threshold='0.000' ;;
        mysql_lock_wait) numerator='0'; denominator='1440'; calculated_value='0.000'; threshold='1.000' ;;
        redis_latency) numerator='1'; denominator='10000'; calculated_value='1.000'; threshold='20.000' ;;
        attachment_free_bytes)
          numerator='2000000000'; denominator='1440'; calculated_value='2000000000.000'
          threshold='1073741824.000'
          ;;
        business_smoke) numerator='6'; denominator='6'; calculated_value='100.000'; threshold='100.000' ;;
      esac
      source_file="$source_directory_name/$metric.txt"
      printf 'metric=%s numerator=%s denominator=%s window=%s/%s\n' \
        "$metric" "$numerator" "$denominator" "$window_started_at" "$window_ended_at" \
        > "$evidence_dir/$source_file"
      source_sha256="$(sha256sum -- "$evidence_dir/$source_file")"
      source_sha256="${source_sha256%% *}"
      printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$window_started_at" "$window_ended_at" "$window_ended_at" "$metric" \
        "$numerator" "$denominator" "$calculated_value" "$threshold" \
        "$source_file" "$source_sha256"
    done
  } > "$target_file"
}

#
# 创建 production 与 rehearsal 共用的发布证据夹具，覆盖包校验、备份、服务、数据库、五角色和附件一致性。
# 参数：$1（字符串）证据目录；$2/$3（字符串）当前发布目录/ID；
#       $4/$5（字符串）上一发布目录/ID。
# 返回：0 表示共用证据已写入；调用方仍需补充阶段专属证据并生成完整性清单。
#
create_release_evidence_base() {
  local evidence_dir="$1"
  local release_dir="$2"
  local release_id="$3"
  local previous_release_dir="$4"
  local previous_release_id="$5"
  local evidence_file
  local -a generic_nonempty_files=(
    backend-verify.log frontend-build.log release-gate-test.log
    build-tools.txt dependency-input-sha256.txt release-hash-check.txt
    git-commit.txt accounts-ignore-check.txt predeploy-hash-check.txt
    predeploy-filesystem.txt predeploy-backend-health.txt
    predeploy-liveness.json predeploy-readiness.json predeploy-prometheus.txt
    predeploy-nginx-health.txt backup-hash-check.txt
    previous-release-hash-check.txt postdeploy-backend.log
    postdeploy-backend-health.txt postdeploy-liveness.json
    postdeploy-readiness.json postdeploy-prometheus.txt postdeploy-nginx-health.txt
    evidence-file-list.txt
  )

  mkdir -p "$evidence_dir"
  for evidence_file in "${generic_nonempty_files[@]}"; do
    printf 'evidence\n' > "$evidence_dir/$evidence_file"
  done
  run_preflight_gate \
    "$release_dir" "$release_id" \
    "$previous_release_dir" "$previous_release_id" \
    > "$evidence_dir/release-preflight.txt"
  printf 'active\nactive\n' > "$evidence_dir/predeploy-services.txt"
  printf 'active\nactive\n' > "$evidence_dir/postdeploy-services.txt"
  printf '1\n' > "$evidence_dir/postdeploy-mysql-connectivity.txt"
  printf 'PONG\n' > "$evidence_dir/postdeploy-redis-ping.txt"
  write_runtime_health_evidence \
    "$evidence_dir/predeploy-liveness.json" \
    "$evidence_dir/predeploy-readiness.json" \
    "$evidence_dir/predeploy-prometheus.txt"
  write_runtime_health_evidence \
    "$evidence_dir/postdeploy-liveness.json" \
    "$evidence_dir/postdeploy-readiness.json" \
    "$evidence_dir/postdeploy-prometheus.txt"
  write_database_verify "$evidence_dir/database-verify.tsv"
  printf '1\n' > "$evidence_dir/attachment-global-guard.txt"
  write_business_smoke "$evidence_dir/business-smoke.tsv" '200'
  write_signoff "$evidence_dir/release-signoff.tsv" 'operator-a' 'reviewer-b'
  : > "$evidence_dir/git-status.txt"
  : > "$evidence_dir/attachment-db.tsv"
  : > "$evidence_dir/attachment-files.tsv"
  : > "$evidence_dir/attachment-diff.txt"
  : > "$evidence_dir/sensitive-file-list.txt"
}

#
# 创建可通过 rehearsal profile 的独立彩排证据夹具，包含完整回滚、重新发布和独立签字。
# 参数：$1（字符串）证据目录；$2/$3（字符串）当前发布目录/ID；
#       $4/$5（字符串）上一发布目录/ID；$6/$7（字符串）执行人/复核人；
#       $8/$9（字符串）执行人/复核人签署时间；$10/$11（字符串）环境开始/结束时间。
# 返回：0 表示彩排证据及完整性清单创建完成。
#
create_rehearsal_evidence() {
  local evidence_dir="$1"
  local release_dir="$2"
  local release_id="$3"
  local previous_release_dir="$4"
  local previous_release_id="$5"
  local operator_name="$6"
  local reviewer_name="$7"
  local operator_time="$8"
  local reviewer_time="$9"
  local environment_started_at="${10}"
  local environment_finished_at="${11}"
  local run_id

  create_release_evidence_base \
    "$evidence_dir" "$release_dir" "$release_id" \
    "$previous_release_dir" "$previous_release_id"
  run_id="$(basename -- "$evidence_dir")"
  create_fresh_install_evidence \
    "$evidence_dir/fresh-install" "$release_dir" "$release_id" \
    "$previous_release_dir" "$previous_release_id"
  write_environment_identity \
    "$evidence_dir/fresh-install/environment-identity.tsv" "$run_id" \
    "$environment_started_at" "$environment_finished_at"
  : > "$evidence_dir/fresh-install/attachment-restore-diff.txt"
  write_signoff "$evidence_dir/fresh-install/install-signoff.tsv" \
    "$operator_name" "$reviewer_name" "$operator_time" "$reviewer_time"
  refresh_evidence_manifest "$evidence_dir/fresh-install"
  write_signoff "$evidence_dir/release-signoff.tsv" \
    "$operator_name" "$reviewer_name" "$operator_time" "$reviewer_time"
  printf 'hash ok\n' > "$evidence_dir/rollback-source-hash-check.txt"
  printf 'active\nactive\n' > "$evidence_dir/rollback-services.txt"
  printf 'health ok\n' > "$evidence_dir/rollback-backend-health.txt"
  printf 'health ok\n' > "$evidence_dir/rollback-nginx-health.txt"
  write_runtime_health_evidence \
    "$evidence_dir/rollback-liveness.json" \
    "$evidence_dir/rollback-readiness.json" \
    "$evidence_dir/rollback-prometheus.txt"
  write_database_verify "$evidence_dir/rollback-database-verify.tsv"
  write_business_smoke "$evidence_dir/rollback-business-smoke.tsv" '200'
  : > "$evidence_dir/rollback-attachment-diff.txt"
  printf 'active\nactive\n' > "$evidence_dir/reapply-services.txt"
  write_runtime_health_evidence \
    "$evidence_dir/reapply-liveness.json" \
    "$evidence_dir/reapply-readiness.json" \
    "$evidence_dir/reapply-prometheus.txt"
  write_database_verify "$evidence_dir/reapply-database-verify.tsv"
  write_business_smoke "$evidence_dir/reapply-business-smoke.tsv" '200'
  : > "$evidence_dir/reapply-attachment-diff.txt"
  write_signoff "$evidence_dir/rehearsal-signoff.tsv" \
    "$operator_name" "$reviewer_name" "$operator_time" "$reviewer_time"
  refresh_evidence_manifest "$evidence_dir"
}

#
# 创建可通过 production profile 的发布证据夹具，并写入两轮彩排现场重算的 PASS 回执。
# 参数：$1（字符串）证据目录；$2/$3（字符串）当前发布目录/ID；
#       $4/$5（字符串）上一发布目录/ID。
# 返回：0 表示生产证据、双彩排回执及完整性清单创建完成。
#
create_production_evidence() {
  local evidence_dir="$1"
  local release_dir="$2"
  local release_id="$3"
  local previous_release_dir="$4"
  local previous_release_id="$5"

  create_release_evidence_base \
    "$evidence_dir" "$release_dir" "$release_id" \
    "$previous_release_dir" "$previous_release_id"
  [[ -n "$TEST_REHEARSAL_ONE_RECEIPT" \
     && -n "$TEST_REHEARSAL_TWO_RECEIPT" ]] \
    || { printf 'standard rehearsal receipts are not initialized\n' >&2; return 1; }
  printf '%s\n' "$TEST_REHEARSAL_ONE_RECEIPT" \
    > "$evidence_dir/rehearsal-round-1-receipt.txt"
  printf '%s\n' "$TEST_REHEARSAL_TWO_RECEIPT" \
    > "$evidence_dir/rehearsal-round-2-receipt.txt"
  write_production_switch "$evidence_dir" "$release_dir" "$release_id"
  refresh_evidence_manifest "$evidence_dir"
}

#
# 使用 fresh-install 发布包关系执行证据门禁，保证测试中的证据回执与真实包摘要绑定。
# 参数：$1（字符串）证据目录；$2（字符串）证据 profile。
# 返回：透传证据门禁退出状态与输出。
#
run_fresh_evidence_gate() {
  local evidence_dir="$1"
  local profile="$2"

  "$GATE_SCRIPT" evidence \
    --evidence-dir "$evidence_dir" \
    --profile "$profile" \
    --release-dir "$TEST_ROOT/fresh-1" \
    --release-id 'fresh-1' \
    --approved-git-commit "$(approved_bundle_anchor "$TEST_ROOT/fresh-1" git)" \
    --approved-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/fresh-1" manifest)"
}

#
# 使用当前/上一版本发布包关系执行证据门禁，保证生产和彩排证据绑定同一回滚源。
# 参数：$1（字符串）证据目录；$2（字符串）证据 profile。
# 返回：透传证据门禁退出状态与输出。
#
run_release_evidence_gate() {
  local evidence_dir="$1"
  local profile="$2"

  if [[ "$profile" == 'production' || "$profile" == 'production-24h' \
     || "$profile" == 'production-72h' ]]; then
    run_release_evidence_gate_with_pair \
      "$evidence_dir" "$profile" \
      "$TEST_REHEARSAL_ONE_DIR" "$TEST_REHEARSAL_TWO_DIR"
  else
    "$GATE_SCRIPT" evidence \
      --evidence-dir "$evidence_dir" \
      --profile "$profile" \
      --release-dir "$TEST_ROOT/current-1" \
      --release-id 'current-1' \
      --previous-release-dir "$TEST_ROOT/previous-1" \
      --previous-release-id 'previous-1' \
      --approved-git-commit "$(approved_bundle_anchor "$TEST_ROOT/current-1" git)" \
      --approved-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/current-1" manifest)" \
      --approved-previous-git-commit "$(approved_bundle_anchor "$TEST_ROOT/previous-1" git)" \
      --approved-previous-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/previous-1" manifest)"
  fi
}

#
# 使用调用方指定的两轮彩排目录执行生产证据门禁，供独立性和版本绑定反例复用。
# 参数：$1（字符串）生产证据目录；$2（字符串）生产 profile；
#       $3/$4（字符串）第一/第二轮彩排证据目录。
# 返回：透传证据门禁退出状态与输出。
#
run_release_evidence_gate_with_pair() {
  local evidence_dir="$1"
  local profile="$2"
  local rehearsal_one_dir="$3"
  local rehearsal_two_dir="$4"

  "$GATE_SCRIPT" evidence \
    --evidence-dir "$evidence_dir" \
    --profile "$profile" \
    --release-dir "$TEST_ROOT/current-1" \
    --release-id 'current-1' \
    --previous-release-dir "$TEST_ROOT/previous-1" \
    --previous-release-id 'previous-1' \
    --approved-git-commit "$(approved_bundle_anchor "$TEST_ROOT/current-1" git)" \
    --approved-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/current-1" manifest)" \
    --approved-previous-git-commit "$(approved_bundle_anchor "$TEST_ROOT/previous-1" git)" \
    --approved-previous-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/previous-1" manifest)" \
    --rehearsal-one-evidence-dir "$rehearsal_one_dir" \
    --rehearsal-two-evidence-dir "$rehearsal_two_dir"
}

#
# 断言命令成功，并把输出隔离到当前测试专用文件，避免测试日志互相污染。
# 参数：$1（字符串）用例名称；其余参数（字符串数组）待执行命令。
# 返回：始终为 0；失败计数由 TEST_FAILURE_COUNT 记录。
#
expect_success() {
  local test_name="$1"
  shift
  local output_file
  local error_file

  TEST_COUNT=$((TEST_COUNT + 1))
  output_file="$TEST_ROOT/test-$TEST_COUNT.out"
  error_file="$TEST_ROOT/test-$TEST_COUNT.err"
  if "$@" > "$output_file" 2> "$error_file"; then
    printf 'ok %d - %s\n' "$TEST_COUNT" "$test_name"
  else
    printf 'not ok %d - %s\n' "$TEST_COUNT" "$test_name"
    sed -n '1,5p' "$error_file" >&2
    TEST_FAILURE_COUNT=$((TEST_FAILURE_COUNT + 1))
  fi
  return 0
}

#
# 断言命令失败，用于证明非法路径、篡改、缺失证据和版本关系均会被拒绝。
# 参数：$1（字符串）用例名称；其余参数（字符串数组）待执行命令。
# 返回：始终为 0；意外成功计入 TEST_FAILURE_COUNT。
#
expect_failure() {
  local test_name="$1"
  shift
  local output_file
  local error_file

  TEST_COUNT=$((TEST_COUNT + 1))
  output_file="$TEST_ROOT/test-$TEST_COUNT.out"
  error_file="$TEST_ROOT/test-$TEST_COUNT.err"
  if "$@" > "$output_file" 2> "$error_file"; then
    printf 'not ok %d - %s unexpectedly passed\n' "$TEST_COUNT" "$test_name"
    TEST_FAILURE_COUNT=$((TEST_FAILURE_COUNT + 1))
  else
    printf 'ok %d - %s\n' "$TEST_COUNT" "$test_name"
  fi
  return 0
}

#
# 执行发布包与证据门禁的正反向回归矩阵。
# 参数：无。
# 返回：0 表示全部用例符合预期；存在失败时返回 1。
#
main() {
  local bundle_dir
  local current_dir
  local previous_dir
  local first_manifest_line
  local evidence_dir
  local binary_source
  local binary_source_sha256
  local rehearsal_variant_dir

  bundle_dir="$TEST_ROOT/fresh-1"
  create_bundle "$bundle_dir" 'fresh-1' 'NONE'
  TEST_FRESH_GIT_COMMIT="$(bundle_git_commit "$bundle_dir")"
  TEST_FRESH_MANIFEST_SHA256="$(bundle_manifest_sha256 "$bundle_dir")"
  expect_success 'valid fresh-install bundle passes' \
    run_preflight_gate "$bundle_dir" 'fresh-1'
  expect_failure 'missing approved release anchors fail closed' \
    "$GATE_SCRIPT" preflight \
    --release-dir "$bundle_dir" --release-id 'fresh-1'
  expect_failure 'wrong approved Git commit fails' \
    "$GATE_SCRIPT" preflight \
    --release-dir "$bundle_dir" --release-id 'fresh-1' \
    --approved-git-commit "$TEST_PREVIOUS_GIT_COMMIT" \
    --approved-manifest-sha256 "$(bundle_manifest_sha256 "$bundle_dir")"
  expect_failure 'wrong approved manifest SHA-256 fails' \
    "$GATE_SCRIPT" preflight \
    --release-dir "$bundle_dir" --release-id 'fresh-1' \
    --approved-git-commit "$TEST_CURRENT_GIT_COMMIT" \
    --approved-manifest-sha256 \
    '1111111111111111111111111111111111111111111111111111111111111111'

  previous_dir="$TEST_ROOT/previous-1"
  current_dir="$TEST_ROOT/current-1"
  create_bundle "$previous_dir" 'previous-1' 'NONE' "$TEST_PREVIOUS_GIT_COMMIT"
  # 不可变历史回滚包只保留原始可运行资产和冻结元数据，不补入当前版本 SQL/配置。
  rm -rf -- "$previous_dir/deployment" "$previous_dir/sql"
  refresh_bundle_manifest "$previous_dir"
  create_bundle "$current_dir" 'current-1' 'previous-1'
  TEST_PREVIOUS_MANIFEST_SHA256="$(bundle_manifest_sha256 "$previous_dir")"
  TEST_CURRENT_MANIFEST_SHA256="$(bundle_manifest_sha256 "$current_dir")"
  expect_success 'rollback bundle without current-only SQL and configuration passes' \
    run_preflight_gate \
    "$current_dir" 'current-1' "$previous_dir" 'previous-1'

  previous_dir="$TEST_ROOT/same-commit-previous"
  current_dir="$TEST_ROOT/same-commit-current"
  create_bundle "$previous_dir" 'same-commit-previous' 'NONE'
  create_bundle "$current_dir" 'same-commit-current' 'same-commit-previous'
  expect_failure 'current and previous releases with the same approved commit fail' \
    run_preflight_gate \
    "$current_dir" 'same-commit-current' "$previous_dir" 'same-commit-previous'
  previous_dir="$TEST_ROOT/previous-1"
  current_dir="$TEST_ROOT/current-1"

  # 两轮彩排使用独立目录、人员和时间，使生产门禁能证明两个归档摘要确实不同。
  create_rehearsal_evidence \
    "$TEST_REHEARSAL_ONE_DIR" "$current_dir" 'current-1' \
    "$previous_dir" 'previous-1' \
    'operator-round-1' 'reviewer-round-1' \
    '2019-12-31T10:05:00+08:00' '2019-12-31T10:06:00+08:00' \
    '2019-12-31T09:00:00+08:00' '2019-12-31T10:00:00+08:00'
  create_rehearsal_evidence \
    "$TEST_REHEARSAL_TWO_DIR" "$current_dir" 'current-1' \
    "$previous_dir" 'previous-1' \
    'operator-round-2' 'reviewer-round-2' \
    '2019-12-31T11:15:00+08:00' '2019-12-31T11:16:00+08:00' \
    '2019-12-31T10:10:00+08:00' '2019-12-31T11:10:00+08:00'
  # 夹具回执由正式 rehearsal 门禁计算一次；后续 production 用例仍逐次现场重验两轮目录。
  TEST_REHEARSAL_ONE_RECEIPT="$(
    run_release_evidence_gate "$TEST_REHEARSAL_ONE_DIR" rehearsal
  )"
  TEST_REHEARSAL_TWO_RECEIPT="$(
    run_release_evidence_gate "$TEST_REHEARSAL_TWO_DIR" rehearsal
  )"

  bundle_dir="$TEST_ROOT/real-gate-assets"
  create_bundle "$bundle_dir" 'real-gate-assets' 'NONE'
  cp -- "$GATE_SCRIPT" "$bundle_dir/deployment/scripts/workflow-release-gate.sh"
  cp -- "$TEST_SCRIPT_DIR/workflow-release-gate-test.sh" \
    "$bundle_dir/deployment/scripts/tests/workflow-release-gate-test.sh"
  refresh_bundle_manifest "$bundle_dir"
  expect_success 'bundle containing the real gate and negative-test assets passes secret scan' \
    run_preflight_gate "$bundle_dir" 'real-gate-assets'

  bundle_dir="$TEST_ROOT/missing-asset"
  create_bundle "$bundle_dir" 'missing-asset' 'NONE'
  rm -f -- "$bundle_dir/deployment/nginx/ruoyi.conf"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'missing required release asset fails' \
    run_preflight_gate "$bundle_dir" 'missing-asset'

  bundle_dir="$TEST_ROOT/tampered"
  create_bundle "$bundle_dir" 'tampered' 'NONE'
  printf 'tamper\n' >> "$bundle_dir/ruoyi-admin.jar"
  expect_failure 'content changed after manifest generation fails' \
    run_preflight_gate "$bundle_dir" 'tampered'

  bundle_dir="$TEST_ROOT/extra-manifest-entry"
  create_bundle "$bundle_dir" 'extra-manifest-entry' 'NONE'
  printf '%064d  ./missing.txt\n' 0 >> "$bundle_dir/SHA256SUMS"
  expect_failure 'manifest entry for a missing file fails' \
    run_preflight_gate "$bundle_dir" 'extra-manifest-entry'

  bundle_dir="$TEST_ROOT/duplicate-manifest-entry"
  create_bundle "$bundle_dir" 'duplicate-manifest-entry' 'NONE'
  first_manifest_line="$(head -n 1 "$bundle_dir/SHA256SUMS")"
  printf '%s\n' "$first_manifest_line" >> "$bundle_dir/SHA256SUMS"
  expect_failure 'duplicate manifest path fails' \
    run_preflight_gate "$bundle_dir" 'duplicate-manifest-entry'

  bundle_dir="$TEST_ROOT/absolute-sql"
  create_bundle "$bundle_dir" 'absolute-sql' 'NONE'
  printf '/outside.sql\n' > "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'absolute SQL order path fails' \
    run_preflight_gate "$bundle_dir" 'absolute-sql'

  bundle_dir="$TEST_ROOT/traversal-sql"
  create_bundle "$bundle_dir" 'traversal-sql' 'NONE'
  printf '../outside.sql\n' > "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'parent traversal SQL order path fails' \
    run_preflight_gate "$bundle_dir" 'traversal-sql'

  bundle_dir="$TEST_ROOT/duplicate-sql"
  create_bundle "$bundle_dir" 'duplicate-sql' 'NONE'
  mkdir -p "$bundle_dir/sql/flowable/business"
  printf 'SELECT 1;\n' > "$bundle_dir/sql/flowable/business/update.sql"
  printf 'flowable/business/update.sql\nflowable/business/update.sql\n' \
    > "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'duplicate SQL order entry fails' \
    run_preflight_gate "$bundle_dir" 'duplicate-sql'

  bundle_dir="$TEST_ROOT/empty-sql-order"
  create_bundle "$bundle_dir" 'empty-sql-order' 'NONE'
  : > "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'empty SQL release order fails' \
    run_preflight_gate "$bundle_dir" 'empty-sql-order'

  bundle_dir="$TEST_ROOT/extra-sql-order"
  create_bundle "$bundle_dir" 'extra-sql-order' 'NONE'
  printf 'SELECT 1;\n' > "$bundle_dir/sql/flowable/business/unapproved.sql"
  printf 'flowable/business/unapproved.sql\n' >> "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'additional unapproved SQL release order entry fails' \
    run_preflight_gate "$bundle_dir" 'extra-sql-order'

  bundle_dir="$TEST_ROOT/reversed-approved-sql-order"
  create_bundle "$bundle_dir" 'reversed-approved-sql-order' 'NONE'
  {
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql\n'
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql\n'
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql\n'
    printf 'flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql\n'
    printf 'flowable/business/8.0.0__workflow_model_version_guard.sql\n'
    printf 'flowable/business/8.0.0__workflow_business.sql\n'
    printf 'flowable/menu/8.0.0__workflow_menu.sql\n'
  } > "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'approved baseline SQL in reversed order fails' \
    run_preflight_gate "$bundle_dir" 'reversed-approved-sql-order'

  bundle_dir="$TEST_ROOT/missing-approved-baseline-order"
  create_bundle "$bundle_dir" 'missing-approved-baseline-order' 'NONE'
  grep -v 'flowable/menu/8.0.0__workflow_menu.sql' "$bundle_dir/sql/release-order.txt" \
    > "$bundle_dir/sql/release-order.txt.filtered"
  mv -- "$bundle_dir/sql/release-order.txt.filtered" "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'release order missing approved menu baseline fails' \
    run_preflight_gate "$bundle_dir" 'missing-approved-baseline-order'

  bundle_dir="$TEST_ROOT/missing-required-common-baseline"
  create_bundle "$bundle_dir" 'missing-required-common-baseline' 'NONE'
  rm -f -- "$bundle_dir/sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'missing approved Flowable common baseline fails' \
    run_preflight_gate "$bundle_dir" 'missing-required-common-baseline'

  bundle_dir="$TEST_ROOT/missing-required-business-baseline"
  create_bundle "$bundle_dir" 'missing-required-business-baseline' 'NONE'
  rm -f -- "$bundle_dir/sql/flowable/business/8.0.0__workflow_business.sql"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'missing approved workflow business baseline fails' \
    run_preflight_gate "$bundle_dir" 'missing-required-business-baseline'

  bundle_dir="$TEST_ROOT/missing-sql"
  create_bundle "$bundle_dir" 'missing-sql' 'NONE'
  printf 'flowable/business/missing.sql\n' > "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'missing ordered SQL file fails' \
    run_preflight_gate "$bundle_dir" 'missing-sql'

  bundle_dir="$TEST_ROOT/unhashed-sql"
  create_bundle "$bundle_dir" 'unhashed-sql' 'NONE'
  mkdir -p "$bundle_dir/sql/flowable/business"
  printf 'SELECT 1;\n' > "$bundle_dir/sql/flowable/business/update.sql"
  printf 'flowable/business/update.sql\n' > "$bundle_dir/sql/release-order.txt"
  refresh_bundle_manifest "$bundle_dir"
  awk '$2 != "./sql/flowable/business/update.sql"' "$bundle_dir/SHA256SUMS" \
    > "$bundle_dir/SHA256SUMS.filtered"
  mv -- "$bundle_dir/SHA256SUMS.filtered" "$bundle_dir/SHA256SUMS"
  expect_failure 'ordered SQL absent from hash manifest fails' \
    run_preflight_gate "$bundle_dir" 'unhashed-sql'

  bundle_dir="$TEST_ROOT/symlink-asset"
  create_bundle "$bundle_dir" 'symlink-asset' 'NONE'
  ln -s index.html "$bundle_dir/frontend/index-link.html"
  expect_failure 'symbolic link inside release bundle fails' \
    run_preflight_gate "$bundle_dir" 'symlink-asset'

  bundle_dir="$TEST_ROOT/hardlink-asset"
  create_bundle "$bundle_dir" 'hardlink-asset' 'NONE'
  ln "$bundle_dir/frontend/index.html" "$bundle_dir/frontend/index-hardlink.html"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'hard-linked release asset fails immutable metadata gate' \
    run_preflight_gate "$bundle_dir" 'hardlink-asset'

  bundle_dir="$TEST_ROOT/production-gate-disabled"
  create_bundle "$bundle_dir" 'production-gate-disabled' 'NONE'
  sed -i 's/production-gate-enabled: true/production-gate-enabled: false/' \
    "$bundle_dir/deployment/config/application.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'disabled production runtime gate fails' \
    run_preflight_gate "$bundle_dir" 'production-gate-disabled'

  bundle_dir="$TEST_ROOT/automatic-flowable-schema"
  create_bundle "$bundle_dir" 'automatic-flowable-schema' 'NONE'
  sed -i 's/database-schema-update: "false"/database-schema-update: "true"/' \
    "$bundle_dir/deployment/config/application.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'automatic Flowable schema update fails' \
    run_preflight_gate "$bundle_dir" 'automatic-flowable-schema'

  bundle_dir="$TEST_ROOT/deprecated-jackson2-mapper"
  create_bundle "$bundle_dir" 'deprecated-jackson2-mapper' 'NONE'
  sed -i 's/variable-json-mapper: jackson/variable-json-mapper: jackson2/' \
    "$bundle_dir/deployment/config/application.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'deprecated Flowable Jackson 2 mapper fails' \
    run_preflight_gate "$bundle_dir" 'deprecated-jackson2-mapper'

  bundle_dir="$TEST_ROOT/public-management-bind"
  create_bundle "$bundle_dir" 'public-management-bind' 'NONE'
  sed -i '0,/address: 127.0.0.1/{//b}; s/address: 127.0.0.1/address: 0.0.0.0/' \
    "$bundle_dir/deployment/config/application.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'public management address fails' \
    run_preflight_gate "$bundle_dir" 'public-management-bind'

  bundle_dir="$TEST_ROOT/stale-metrics-max-age"
  create_bundle "$bundle_dir" 'stale-metrics-max-age' 'NONE'
  sed -i 's/METRICS_SNAPSHOT_MAX_AGE:PT3M/METRICS_SNAPSHOT_MAX_AGE:PT10M/' \
    "$bundle_dir/deployment/config/application.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'unapproved metrics snapshot max age fails' \
    run_preflight_gate "$bundle_dir" 'stale-metrics-max-age'

  bundle_dir="$TEST_ROOT/hardcoded-token-secret"
  create_bundle "$bundle_dir" 'hardcoded-token-secret' 'NONE'
  sed -i 's/${RUOYI_TOKEN_SECRET:}/FORBIDDEN_TEST_SENTINEL/' \
    "$bundle_dir/deployment/config/application.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'hardcoded token secret configuration fails' \
    run_preflight_gate "$bundle_dir" 'hardcoded-token-secret'

  bundle_dir="$TEST_ROOT/token-secret-file-disabled"
  create_bundle "$bundle_dir" 'token-secret-file-disabled' 'NONE'
  sed -i 's/^    enabled: true$/    enabled: false/' \
    "$bundle_dir/deployment/config/application.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'disabled token secret file generation fails' \
    run_preflight_gate "$bundle_dir" 'token-secret-file-disabled'

  bundle_dir="$TEST_ROOT/token-secret-file-path-drift"
  create_bundle "$bundle_dir" 'token-secret-file-path-drift' 'NONE'
  sed -i 's@/var/lib/ruoyi-secrets/token-secret@/tmp/token-secret@g' \
    "$bundle_dir/deployment/config/application.yml" \
    "$bundle_dir/deployment/config/ruoyi.env.example"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'unapproved token secret file path fails' \
    run_preflight_gate "$bundle_dir" 'token-secret-file-path-drift'

  bundle_dir="$TEST_ROOT/hardcoded-druid-password"
  create_bundle "$bundle_dir" 'hardcoded-druid-password' 'NONE'
  sed -i 's/${RUOYI_DB_PASSWORD}/FORBIDDEN_TEST_SENTINEL/' \
    "$bundle_dir/deployment/config/application-druid.yml"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'hardcoded Druid datasource password fails' \
    run_preflight_gate "$bundle_dir" 'hardcoded-druid-password'

  bundle_dir="$TEST_ROOT/actuator-proxied"
  create_bundle "$bundle_dir" 'actuator-proxied' 'NONE'
  sed -i '0,/return 404;/{s@return 404;@proxy_pass http://127.0.0.1:18080;@}' \
    "$bundle_dir/deployment/nginx/ruoyi.conf"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'Actuator public proxy fails Nginx contract' \
    run_preflight_gate "$bundle_dir" 'actuator-proxied'

  bundle_dir="$TEST_ROOT/release-binary-secret"
  create_bundle "$bundle_dir" 'release-binary-secret' 'NONE'
  printf '%b%b' '\000RUOYI_TOKEN_' 'SECRET=FORBIDDEN_TEST_SENTINEL\000' \
    >> "$bundle_dir/ruoyi-admin.jar"
  refresh_bundle_manifest "$bundle_dir"
  expect_failure 'token secret bytes embedded in binary release asset fail' \
    run_preflight_gate "$bundle_dir" 'release-binary-secret'

  bundle_dir="$TEST_ROOT/same-release"
  create_bundle "$bundle_dir" 'same-release' 'NONE'
  expect_failure 'current and rollback release IDs cannot be identical' \
    run_preflight_gate \
    "$bundle_dir" 'same-release' "$bundle_dir" 'same-release'

  previous_dir="$TEST_ROOT/approved-previous"
  current_dir="$TEST_ROOT/relationship-mismatch"
  create_bundle "$previous_dir" 'approved-previous' 'NONE' "$TEST_PREVIOUS_GIT_COMMIT"
  create_bundle "$current_dir" 'relationship-mismatch' 'different-previous'
  expect_failure 'metadata rollback relationship mismatch fails' \
    run_preflight_gate \
    "$current_dir" 'relationship-mismatch' "$previous_dir" 'approved-previous'

  evidence_dir="$TEST_ROOT/evidence-valid"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  expect_success 'complete fresh-install evidence passes' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-overprivileged-app"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf 'app\tDELETE,INSERT,SELECT,UPDATE,ALTER\t0\t0\t0\n' \
    > "$evidence_dir/app-account-grants.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'overprivileged application MySQL grants fail' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-overprivileged-backup"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf 'backup\tINSERT,SELECT,SHOW VIEW,TRIGGER\t0\t0\t0\n' \
    > "$evidence_dir/backup-account-grants.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'overprivileged backup MySQL grants fail' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-aof-disabled"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  sed -i 's/aof_enabled=1/aof_enabled=0/' \
    "$evidence_dir/redis-persistence.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'disabled Redis AOF fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-aof-write-failed"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  sed -i 's/aof_last_write_status=ok/aof_last_write_status=err/' \
    "$evidence_dir/redis-persistence.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'failed Redis AOF write status fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-redis-eviction"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf 'maxmemory-policy=volatile-lru\n' \
    > "$evidence_dir/redis-memory-policy.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'Redis policy other than noeviction fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-readiness-down"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf '{"status":"DOWN"}\n' > "$evidence_dir/backend-readiness.json"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'Actuator readiness DOWN fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-missing-runtime-snapshot"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf 'workflow_attachment_cleanup_lock_degraded 0\n' \
    > "$evidence_dir/prometheus-scrape.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'Prometheus evidence without runtime snapshot fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-degraded-cleanup-lock"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  sed -i 's/workflow_attachment_cleanup_lock_degraded 0/workflow_attachment_cleanup_lock_degraded 1/' \
    "$evidence_dir/prometheus-scrape.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'Prometheus degraded cleanup lock fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-missing"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  rm -f -- "$evidence_dir/install-signoff.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'missing mandatory evidence fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-sensitive"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf '%s%s\n' 'RUOYI_DB_' 'PASSWORD=FORBIDDEN_TEST_SENTINEL' \
    > "$evidence_dir/leak.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'sensitive value in evidence archive fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-druid-secret"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf '%s%s\n' 'DRUID_MONITOR_' 'PASSWORD=FORBIDDEN_TEST_SENTINEL' \
    > "$evidence_dir/druid-output.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'Druid password assignment in evidence fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-basic-auth"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf '%b%b' '\000Authoriza' 'tion: Basic QUFBQUFBQUFB\000' \
    > "$evidence_dir/request.bin"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'Basic authorization bytes in binary evidence fail' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-cookie"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf '%b%b' '\000Set-Coo' 'kie: TEST_SESSION=FORBIDDEN_TEST_SENTINEL\000' \
    > "$evidence_dir/response.bin"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'Cookie bytes in binary evidence fail' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-env-file"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf 'SAFE_TEST_MARKER=true\n' > "$evidence_dir/.env"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'environment credential file in evidence fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-tampered"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  printf 'tamper\n' >> "$evidence_dir/database-verify.tsv"
  expect_failure 'evidence changed after manifest generation fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/evidence-symlink"
  create_fresh_install_evidence \
    "$evidence_dir" "$TEST_ROOT/fresh-1" 'fresh-1'
  ln -s database-verify.tsv "$evidence_dir/database-verify-link.tsv"
  expect_failure 'symbolic link inside evidence archive fails' \
    run_fresh_evidence_gate "$evidence_dir" fresh-install

  evidence_dir="$TEST_ROOT/rehearsal-missing-fresh-empty-schema"
  cp -a -- "$TEST_REHEARSAL_ONE_DIR" "$evidence_dir"
  rm -f -- "$evidence_dir/fresh-install/empty-schema-check.txt"
  refresh_rehearsal_manifests "$evidence_dir"
  expect_failure 'rehearsal missing namespaced empty-schema evidence fails' \
    run_release_evidence_gate "$evidence_dir" rehearsal

  evidence_dir="$TEST_ROOT/rehearsal-missing-fresh-manifest"
  cp -a -- "$TEST_REHEARSAL_ONE_DIR" "$evidence_dir"
  rm -f -- "$evidence_dir/fresh-install/EVIDENCE-SHA256SUMS"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'rehearsal missing namespaced fresh-install manifest fails' \
    run_release_evidence_gate "$evidence_dir" rehearsal

  evidence_dir="$TEST_ROOT/rehearsal-invalid-fresh-counts"
  cp -a -- "$TEST_REHEARSAL_ONE_DIR" "$evidence_dir"
  printf '83\t20\t11\t36\t16\n' \
    > "$evidence_dir/fresh-install/table-counts.tsv"
  refresh_rehearsal_manifests "$evidence_dir"
  expect_failure 'rehearsal with invalid namespaced table counts fails' \
    run_release_evidence_gate "$evidence_dir" rehearsal

  evidence_dir="$TEST_ROOT/rehearsal-missing-fresh-bootstrap"
  cp -a -- "$TEST_REHEARSAL_ONE_DIR" "$evidence_dir"
  rm -f -- "$evidence_dir/fresh-install/admin-bootstrap-state.txt"
  refresh_rehearsal_manifests "$evidence_dir"
  expect_failure 'rehearsal missing namespaced bootstrap evidence fails' \
    run_release_evidence_gate "$evidence_dir" rehearsal

  evidence_dir="$TEST_ROOT/rehearsal-invalid-fresh-restore"
  cp -a -- "$TEST_REHEARSAL_ONE_DIR" "$evidence_dir"
  printf 'ry_vue_backup_verify.table_01 OK\n' \
    > "$evidence_dir/fresh-install/backup-smoke/mysqlcheck.txt"
  refresh_rehearsal_manifests "$evidence_dir"
  expect_failure 'rehearsal without 84-table restore verification fails' \
    run_release_evidence_gate "$evidence_dir" rehearsal

  evidence_dir="$TEST_ROOT/rehearsal-fresh-attachment-diff"
  cp -a -- "$TEST_REHEARSAL_ONE_DIR" "$evidence_dir"
  printf 'missing attachment\n' \
    > "$evidence_dir/fresh-install/attachment-restore-diff.txt"
  refresh_rehearsal_manifests "$evidence_dir"
  expect_failure 'rehearsal with fresh-install attachment restore diff fails' \
    run_release_evidence_gate "$evidence_dir" rehearsal

  evidence_dir="$TEST_ROOT/evidence-production"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  expect_success 'production evidence with HTTP 200 and business 403 denial passes' \
    run_release_evidence_gate "$evidence_dir" production

  expect_failure 'production evidence without rehearsal directory inputs fails' \
    "$GATE_SCRIPT" evidence \
    --evidence-dir "$evidence_dir" \
    --profile production \
    --release-dir "$TEST_ROOT/current-1" \
    --release-id 'current-1' \
    --previous-release-dir "$TEST_ROOT/previous-1" \
    --previous-release-id 'previous-1' \
    --approved-git-commit "$(approved_bundle_anchor "$TEST_ROOT/current-1" git)" \
    --approved-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/current-1" manifest)" \
    --approved-previous-git-commit "$(approved_bundle_anchor "$TEST_ROOT/previous-1" git)" \
    --approved-previous-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/previous-1" manifest)"

  expect_failure 'production evidence without a real previous release fails' \
    "$GATE_SCRIPT" evidence \
    --evidence-dir "$evidence_dir" \
    --profile production \
    --release-dir "$TEST_ROOT/current-1" \
    --release-id 'current-1' \
    --approved-git-commit "$(approved_bundle_anchor "$TEST_ROOT/current-1" git)" \
    --approved-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/current-1" manifest)" \
    --rehearsal-one-evidence-dir "$TEST_REHEARSAL_ONE_DIR" \
    --rehearsal-two-evidence-dir "$TEST_REHEARSAL_TWO_DIR"

  expect_failure 'same physical rehearsal archive cannot count twice' \
    run_release_evidence_gate_with_pair \
    "$evidence_dir" production \
    "$TEST_REHEARSAL_ONE_DIR" "$TEST_REHEARSAL_ONE_DIR"

  expect_failure 'production archive cannot also count as a rehearsal archive' \
    run_release_evidence_gate_with_pair \
    "$evidence_dir" production \
    "$evidence_dir" "$TEST_REHEARSAL_TWO_DIR"

  bundle_dir="$TEST_ROOT/other-previous-1"
  create_bundle \
    "$bundle_dir" 'other-previous-1' 'NONE' "$TEST_PREVIOUS_GIT_COMMIT"
  current_dir="$TEST_ROOT/other-current-1"
  create_bundle "$current_dir" 'other-current-1' 'other-previous-1'
  create_rehearsal_evidence \
    "$TEST_ROOT/rehearsal-other-release" "$current_dir" 'other-current-1' \
    "$bundle_dir" 'other-previous-1' \
    'operator-other' 'reviewer-other' \
    '2019-12-31T12:25:00+08:00' '2019-12-31T12:26:00+08:00' \
    '2019-12-31T11:20:00+08:00' '2019-12-31T12:20:00+08:00'
  expect_failure 'rehearsal bound to another release manifest pair fails' \
    run_release_evidence_gate_with_pair \
    "$evidence_dir" production \
    "$TEST_REHEARSAL_ONE_DIR" "$TEST_ROOT/rehearsal-other-release"

  cp -a -- "$TEST_REHEARSAL_ONE_DIR" "$TEST_ROOT/rehearsal-round-1-copy"
  expect_failure 'copied identical rehearsal archive is not an independent run' \
    run_release_evidence_gate_with_pair \
    "$evidence_dir" production \
    "$TEST_REHEARSAL_ONE_DIR" "$TEST_ROOT/rehearsal-round-1-copy"

  rehearsal_variant_dir="$TEST_ROOT/rehearsal-reused-environment"
  cp -a -- "$TEST_REHEARSAL_TWO_DIR" "$rehearsal_variant_dir"
  awk -F '\t' 'BEGIN { OFS="\t" }
    NR == 2 {
      $1="rehearsal-round-1";
      $2="environment-rehearsal-round-1";
      $5="mysql-rehearsal-round-1";
      $6="redis-rehearsal-round-1";
      $7="attachment-rehearsal-round-1"
    }
    { print }
  ' "$rehearsal_variant_dir/fresh-install/environment-identity.tsv" \
    > "$rehearsal_variant_dir/fresh-install/environment-identity.filtered"
  mv -- "$rehearsal_variant_dir/fresh-install/environment-identity.filtered" \
    "$rehearsal_variant_dir/fresh-install/environment-identity.tsv"
  refresh_rehearsal_manifests "$rehearsal_variant_dir"
  expect_failure 'reused rehearsal infrastructure identities fail' \
    run_release_evidence_gate_with_pair \
    "$TEST_ROOT/evidence-production" production \
    "$TEST_REHEARSAL_ONE_DIR" "$rehearsal_variant_dir"

  rehearsal_variant_dir="$TEST_ROOT/rehearsal-overlapping-time"
  cp -a -- "$TEST_REHEARSAL_TWO_DIR" "$rehearsal_variant_dir"
  awk -F '\t' \
    'BEGIN { OFS="\t" } NR == 2 { $3="2019-12-31T09:30:00+08:00" } { print }' \
    "$rehearsal_variant_dir/fresh-install/environment-identity.tsv" \
    > "$rehearsal_variant_dir/fresh-install/environment-identity.filtered"
  mv -- "$rehearsal_variant_dir/fresh-install/environment-identity.filtered" \
    "$rehearsal_variant_dir/fresh-install/environment-identity.tsv"
  refresh_rehearsal_manifests "$rehearsal_variant_dir"
  expect_failure 'second rehearsal starting before first finishes fails' \
    run_release_evidence_gate_with_pair \
    "$TEST_ROOT/evidence-production" production \
    "$TEST_REHEARSAL_ONE_DIR" "$rehearsal_variant_dir"

  rehearsal_variant_dir="$TEST_ROOT/rehearsal-reused-install-people"
  cp -a -- "$TEST_REHEARSAL_TWO_DIR" "$rehearsal_variant_dir"
  write_signoff "$rehearsal_variant_dir/fresh-install/install-signoff.tsv" \
    'operator-round-1' 'reviewer-round-1' \
    '2019-12-31T11:15:00+08:00' '2019-12-31T11:16:00+08:00'
  refresh_rehearsal_manifests "$rehearsal_variant_dir"
  expect_failure 'reused rehearsal install operators and reviewers fail' \
    run_release_evidence_gate_with_pair \
    "$TEST_ROOT/evidence-production" production \
    "$TEST_REHEARSAL_ONE_DIR" "$rehearsal_variant_dir"

  rehearsal_variant_dir="$TEST_ROOT/rehearsal-different-database-result"
  cp -a -- "$TEST_REHEARSAL_TWO_DIR" "$rehearsal_variant_dir"
  awk -F '\t' 'BEGIN { OFS="\t" }
    $1 == "model_version_unique_constraint" {
      $3="matching_indexes=1, indexes=ACT_UNIQ_MODEL_VERSION_ALT"
    }
    { print }
  ' "$rehearsal_variant_dir/fresh-install/database-verify.tsv" \
    > "$rehearsal_variant_dir/fresh-install/database-verify.filtered"
  mv -- "$rehearsal_variant_dir/fresh-install/database-verify.filtered" \
    "$rehearsal_variant_dir/fresh-install/database-verify.tsv"
  refresh_rehearsal_manifests "$rehearsal_variant_dir"
  expect_failure 'different rehearsal fresh-install database results fail' \
    run_release_evidence_gate_with_pair \
    "$TEST_ROOT/evidence-production" production \
    "$TEST_REHEARSAL_ONE_DIR" "$rehearsal_variant_dir"

  evidence_dir="$TEST_ROOT/evidence-switch-release-mismatch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  awk -F '\t' 'BEGIN { OFS="\t" } NR == 2 { $1="other-release" } { print }' \
    "$evidence_dir/production-switch.tsv" > "$evidence_dir/production-switch.filtered"
  mv -- "$evidence_dir/production-switch.filtered" "$evidence_dir/production-switch.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'production switch bound to another release ID fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-switch-manifest-mismatch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  awk -F '\t' 'BEGIN { OFS="\t" } NR == 2 { $2=sprintf("%064d", 0) } { print }' \
    "$evidence_dir/production-switch.tsv" > "$evidence_dir/production-switch.filtered"
  mv -- "$evidence_dir/production-switch.filtered" "$evidence_dir/production-switch.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'production switch manifest mismatch fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-switch-source-mismatch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  printf 'changed source\n' >> "$evidence_dir/production-switch-source.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'production switch source digest mismatch fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-switch-before-build"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  awk -F '\t' \
    'BEGIN { OFS="\t" } NR == 2 { $3="2019-12-30T12:00:00+08:00" } { print }' \
    "$evidence_dir/production-switch.tsv" > "$evidence_dir/production-switch.filtered"
  mv -- "$evidence_dir/production-switch.filtered" "$evidence_dir/production-switch.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'production switch before release build fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-release-signoff-before-switch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_signoff "$evidence_dir/release-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-01T11:55:00+08:00' '2020-01-01T11:56:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'release signoff before production switch fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-tampered-rehearsal-receipt"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  printf 'PASS evidence_gate profile=rehearsal evidence_manifest_sha256=%064d\n' 0 \
    > "$evidence_dir/rehearsal-round-2-receipt.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'tampered stored rehearsal PASS receipt fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-binary-business-source"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  # 原始来源可能以抓包等二进制格式封装；只更新绑定摘要，验证门禁不依赖文本解析。
  binary_source="$evidence_dir/business-smoke-sources/instance_detail.txt"
  printf '%b' '\000\001\002BINARY-SMOKE-SOURCE\377' > "$binary_source"
  binary_source_sha256="$(sha256sum -- "$binary_source")"
  binary_source_sha256="${binary_source_sha256%% *}"
  awk -F '\t' -v digest="$binary_source_sha256" \
    'BEGIN { OFS="\t" } $4 == "instance_detail" { $19=digest } { print }' \
    "$evidence_dir/business-smoke.tsv" > "$evidence_dir/business-smoke.filtered"
  mv -- "$evidence_dir/business-smoke.filtered" "$evidence_dir/business-smoke.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_success 'binary-packaged business source remains manifest-compatible' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-wrong-release-receipt"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  run_preflight_gate "$TEST_ROOT/fresh-1" 'fresh-1' \
    > "$evidence_dir/release-preflight.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'evidence receipt from another release fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-http-mismatch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_business_smoke "$evidence_dir/business-smoke.tsv" '500'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'business smoke transport status mismatch fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-denied-transport-403"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_business_smoke "$evidence_dir/business-smoke.tsv" '200' '403' '403'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'denied action transport 403 instead of HTTP 200 fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-denied-business-200"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_business_smoke "$evidence_dir/business-smoke.tsv" '200' '200' '200'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'denied action AjaxResult business code 200 instead of 403 fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-fake-database-pass"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  printf 'check\tPASS\n' > "$evidence_dir/database-verify.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'handwritten database PASS without formal checks fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-missing-database-check"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  awk -F '\t' '$1 != "wf_attachment_cleanup_retry_check_clause"' \
    "$evidence_dir/database-verify.tsv" > "$evidence_dir/database-verify.filtered"
  mv -- "$evidence_dir/database-verify.filtered" "$evidence_dir/database-verify.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'database evidence missing one formal check fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-database-detail-mismatch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  awk -F '\t' 'BEGIN { OFS="\t" } $1 == "deadletter_jobs" { $3="actual=1" } { print }' \
    "$evidence_dir/database-verify.tsv" > "$evidence_dir/database-verify.filtered"
  mv -- "$evidence_dir/database-verify.filtered" "$evidence_dir/database-verify.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'database PASS with threshold-violating detail fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-fake-business-pass"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  printf 'role\taction\texpected_http_status\tactual_http_status\tbusiness_result\nworkflow_admin\tview\t200\t200\tPASS\n' \
    > "$evidence_dir/business-smoke.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'handwritten business PASS without action evidence fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-legacy-http-only-business-smoke"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  {
    printf 'started_at\tfinished_at\trole\taction\tmethod\tobject_ref\trequest_id\texpected_http_status\tactual_http_status\tstate_before\tstate_after\tdb_before_sha256\tdb_after_sha256\taudit_ref\tunexpected_side_effect_count\tsource_file\tsource_sha256\n'
    tail -n +2 "$evidence_dir/business-smoke.tsv" \
      | awk -F '\t' 'BEGIN { OFS="\t" } { print $1,$2,$3,$4,$5,$6,$7,$8,$9,$12,$13,$14,$15,$16,$17,$18,$19 }'
  } > "$evidence_dir/business-smoke.legacy"
  mv -- "$evidence_dir/business-smoke.legacy" "$evidence_dir/business-smoke.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'legacy HTTP-only business smoke contract fails closed' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-business-no-mutation"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  awk -F '\t' 'BEGIN { OFS="\t" } $4 == "process_start" { $13=$12; $15=$14 } { print }' \
    "$evidence_dir/business-smoke.tsv" > "$evidence_dir/business-smoke.filtered"
  mv -- "$evidence_dir/business-smoke.filtered" "$evidence_dir/business-smoke.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'successful write without persisted transition fails' \
    run_release_evidence_gate "$evidence_dir" production

  evidence_dir="$TEST_ROOT/evidence-business-source-mismatch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  printf 'changed source\n' >> \
    "$evidence_dir/business-smoke-sources/process_start.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'business action source digest mismatch fails' \
    run_release_evidence_gate "$evidence_dir" production

  expect_success 'complete rollback and reapply rehearsal evidence passes' \
    run_release_evidence_gate "$TEST_REHEARSAL_ONE_DIR" rehearsal

  expect_failure 'rehearsal evidence without a real previous release fails' \
    "$GATE_SCRIPT" evidence \
    --evidence-dir "$TEST_REHEARSAL_ONE_DIR" \
    --profile rehearsal \
    --release-dir "$TEST_ROOT/current-1" \
    --release-id 'current-1' \
    --approved-git-commit "$(approved_bundle_anchor "$TEST_ROOT/current-1" git)" \
    --approved-manifest-sha256 "$(approved_bundle_anchor "$TEST_ROOT/current-1" manifest)"

  evidence_dir="$TEST_ROOT/evidence-production-72h"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-02T12:05:00+08:00' '2020-01-02T12:06:00+08:00'
  write_observation "$evidence_dir/observation-72h.tsv" '9999'
  write_signoff "$evidence_dir/observation-72h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-04T12:05:00+08:00' '2020-01-04T12:06:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_success 'complete production 72-hour evidence passes' \
    run_release_evidence_gate "$evidence_dir" production-72h

  evidence_dir="$TEST_ROOT/evidence-observation-before-switch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation \
    "$evidence_dir/observation-24h.tsv" '9999' \
    '2020-01-01T12:00:00+08:00' '2019-12-31T12:00:00+08:00'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-01T12:05:00+08:00' '2020-01-01T12:06:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'observation window starting before production switch fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-different-starts"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-02T12:05:00+08:00' '2020-01-02T12:06:00+08:00'
  write_observation \
    "$evidence_dir/observation-72h.tsv" '9999' \
    '2020-01-03T12:00:00+08:00' '2019-12-31T12:00:00+08:00'
  write_signoff "$evidence_dir/observation-72h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-03T12:05:00+08:00' '2020-01-03T12:06:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure '24-hour and 72-hour observations with different starts fail' \
    run_release_evidence_gate "$evidence_dir" production-72h

  evidence_dir="$TEST_ROOT/evidence-observation-future-window"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation \
    "$evidence_dir/observation-24h.tsv" '9999' \
    '2999-01-02T12:00:00+08:00' '2999-01-01T12:00:00+08:00'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2999-01-02T12:05:00+08:00' '2999-01-02T12:06:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'observation window ending in the future fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-future-signoff"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2999-01-02T12:05:00+08:00' '2999-01-02T12:06:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'past observation with future signoff fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-bad-calculation"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-04T12:05:00+08:00' '2020-01-04T12:06:00+08:00'
  awk -F '\t' 'BEGIN { OFS="\t" } $4 == "http_success_rate" { $7="100.000" } { print }' \
    "$evidence_dir/observation-24h.tsv" > "$evidence_dir/observation-24h.filtered"
  mv -- "$evidence_dir/observation-24h.filtered" "$evidence_dir/observation-24h.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'observation calculated value inconsistent with raw counts fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-weak-threshold"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-04T12:05:00+08:00' '2020-01-04T12:06:00+08:00'
  awk -F '\t' 'BEGIN { OFS="\t" } $4 == "http_success_rate" { $8="90.000" } { print }' \
    "$evidence_dir/observation-24h.tsv" > "$evidence_dir/observation-24h.filtered"
  mv -- "$evidence_dir/observation-24h.filtered" "$evidence_dir/observation-24h.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'weakened observation threshold fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-short-window"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-04T12:05:00+08:00' '2020-01-04T12:06:00+08:00'
  sed -i 's/2020-01-01T12:00:00+08:00/2020-01-01T13:00:00+08:00/g' \
    "$evidence_dir/observation-24h.tsv"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'observation shorter than 24 hours fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-source-mismatch"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-04T12:05:00+08:00' '2020-01-04T12:06:00+08:00'
  printf 'changed source\n' >> \
    "$evidence_dir/observation-24h-sources/http_success_rate.txt"
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'observation source digest mismatch fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-early-signoff"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '9999'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-02T11:55:00+08:00' '2020-01-02T11:56:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'observation signed before window completion fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  evidence_dir="$TEST_ROOT/evidence-observation-placeholder"
  create_production_evidence \
    "$evidence_dir" "$TEST_ROOT/current-1" 'current-1' \
    "$TEST_ROOT/previous-1" 'previous-1'
  write_observation "$evidence_dir/observation-24h.tsv" '<actual-value>'
  write_signoff "$evidence_dir/observation-24h-signoff.tsv" \
    'operator-a' 'reviewer-b' \
    '2020-01-04T12:05:00+08:00' '2020-01-04T12:06:00+08:00'
  refresh_evidence_manifest "$evidence_dir"
  expect_failure 'observation placeholder fails' \
    run_release_evidence_gate "$evidence_dir" production-24h

  printf '1..%d\n' "$TEST_COUNT"
  if [[ "$TEST_FAILURE_COUNT" -ne 0 ]]; then
    printf '%d test(s) failed\n' "$TEST_FAILURE_COUNT" >&2
    return 1
  fi
  printf 'all %d workflow release gate tests passed\n' "$TEST_COUNT"
}

main "$@"
