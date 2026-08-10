#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'
export LC_ALL=C

readonly GATE_USAGE_EXIT=64
# 首个正式版本只允许按固定顺序执行完整数据库基线，禁止夹带未发布的开发期迁移。
readonly -a GATE_REQUIRED_RELEASE_SQLS=(
  'flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql'
  'flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql'
  'flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql'
  'flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql'
  'flowable/business/8.0.0__workflow_model_version_guard.sql'
  'flowable/business/8.0.0__workflow_business.sql'
  'flowable/menu/8.0.0__workflow_menu.sql'
  'integration/8.0.0__sms_oss.sql'
)

# 清单校验后的路径到 SHA-256 映射；后续 SQL 顺序校验只允许引用此映射中的文件。
declare -A GATE_MANIFEST_HASHES=()
# 资产相对路径到设备、inode、owner、mode、链接数、大小和 mtime 的映射，用于末尾检测元数据漂移。
declare -A GATE_MANIFEST_METADATA=()
GATE_VALIDATED_MANIFEST_SHA256=''
GATE_VALIDATED_MANIFEST_ROOT=''
# 最近一次观察证据的窗口结束 epoch，供观察签字校验“先观察、后签字”。
GATE_OBSERVATION_WINDOW_END_EPOCH=0
# 生产版本实际切换时间 epoch，供发布签字和 24/72 小时观察窗口绑定同一次上线。
GATE_PRODUCTION_SWITCH_EPOCH=0
# 最近一次全新环境构建的开始/结束 epoch，供安装与彩排签字校验执行时序。
GATE_ENVIRONMENT_STARTED_EPOCH=0
GATE_ENVIRONMENT_FINISHED_EPOCH=0

# 说明：Bash 不支持注释语法中的类型系统；下列函数块统一以“字符串/数组”标明参数和返回语义。

#
# 输出错误并立即终止门禁。
# 参数：$*（字符串）不含敏感正文的错误说明。
# 返回：无；进程以状态码 1 退出。
#
gate_die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

#
# 输出命令用法。
# 参数：无。
# 返回：无；仅向标准错误输出帮助文本。
#
gate_usage() {
  cat >&2 <<'USAGE'
Usage:
  workflow-release-gate.sh preflight \
    --release-dir ABSOLUTE_DIR --release-id RELEASE_ID \
    --approved-git-commit GIT_SHA \
    --approved-manifest-sha256 SHA256 \
    [--previous-release-dir ABSOLUTE_DIR --previous-release-id RELEASE_ID \
     --approved-previous-git-commit GIT_SHA \
     --approved-previous-manifest-sha256 SHA256]

  workflow-release-gate.sh evidence \
    --evidence-dir ABSOLUTE_DIR --profile PROFILE \
    --release-dir ABSOLUTE_DIR --release-id RELEASE_ID \
    --approved-git-commit GIT_SHA \
    --approved-manifest-sha256 SHA256 \
    [--previous-release-dir ABSOLUTE_DIR --previous-release-id RELEASE_ID \
     --approved-previous-git-commit GIT_SHA \
     --approved-previous-manifest-sha256 SHA256] \
    [--rehearsal-one-evidence-dir ABSOLUTE_DIR \
     --rehearsal-two-evidence-dir ABSOLUTE_DIR]
  where PROFILE is fresh-install|rehearsal|production|production-24h|production-72h
  rehearsal and production profiles require a real previous release.
  production profiles also require both rehearsal evidence directories.
USAGE
}

#
# 检查运行门禁依赖的系统命令是否存在。
# 参数：$@（字符串数组）命令名称列表。
# 返回：0 表示全部存在；缺失时终止门禁。
#
gate_require_commands() {
  local command_name
  for command_name in "$@"; do
    command -v "$command_name" >/dev/null 2>&1 \
      || gate_die "required command is unavailable: $command_name"
  done
}

#
# 校验发布版本标识，阻止占位符、路径字符和不稳定空值进入发布路径。
# 参数：$1（字符串）版本标识；$2（字符串）字段名称；$3（字符串）是否允许 NONE。
# 返回：0 表示版本标识合法；非法时终止门禁。
#
gate_validate_release_id() {
  local release_id="$1"
  local field_name="$2"
  local allow_none="$3"

  if [[ "$allow_none" == 'true' && "$release_id" == 'NONE' ]]; then
    return 0
  fi
  [[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
    || gate_die "$field_name is invalid"
  [[ "$release_id" != *'<'* && "$release_id" != *'>'* ]] \
    || gate_die "$field_name contains a placeholder"
}

#
# 校验 Git commit 为小写 40 位 SHA，并拒绝调用方用格式正确但未批准的占位值绕过。
# 参数：$1（字符串）commit；$2（字符串）字段业务名称。
# 返回：0 表示 commit 可作为外部批准锚点；非法时终止门禁。
#
gate_validate_git_commit() {
  local git_commit="$1"
  local label="$2"

  [[ "$git_commit" =~ ^[0-9a-f]{40}$ ]] \
    || gate_die "$label must be a lowercase 40-character SHA"
  [[ "$git_commit" != '0000000000000000000000000000000000000000' ]] \
    || gate_die "$label must not be an all-zero placeholder"
}

#
# 校验 SHA-256 外部批准摘要，阻止空值、占位符或非规范大小写进入发布锚点。
# 参数：$1（字符串）SHA-256；$2（字符串）字段业务名称。
# 返回：0 表示摘要可用于绑定不可变发布资产；非法时终止门禁。
#
gate_validate_sha256() {
  local digest="$1"
  local label="$2"

  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] \
    || gate_die "$label must be a lowercase 64-character SHA-256"
  [[ "$digest" != '0000000000000000000000000000000000000000000000000000000000000000' ]] \
    || gate_die "$label must not be an all-zero placeholder"
}

#
# 将绝对目录解析为无符号链接、无路径折叠的真实目录，避免校验对象逃逸。
# 参数：$1（字符串）待校验目录；$2（字符串）目录业务名称。
# 返回：标准输出返回规范化绝对路径；非法时终止门禁。
#
gate_canonical_directory() {
  local directory="$1"
  local label="$2"
  local canonical

  [[ "$directory" == /* ]] || gate_die "$label must be an absolute path"
  [[ -d "$directory" ]] || gate_die "$label does not exist or is not a directory"
  [[ ! -L "$directory" ]] || gate_die "$label must not be a symbolic link"
  canonical="$(realpath -e -- "$directory")"
  [[ "$directory" == "$canonical" ]] \
    || gate_die "$label must already be a canonical path without symlink traversal"
  printf '%s\n' "$canonical"
}

#
# 校验清单路径为受控相对路径，禁止绝对路径、反斜线、空段和父目录跳转。
# 参数：$1（字符串）相对路径；$2（字符串）路径业务名称。
# 返回：0 表示路径可安全拼接到受控根目录；非法时终止门禁。
#
gate_validate_relative_path() {
  local relative_path="$1"
  local label="$2"
  local segment
  local -a segments=()

  [[ -n "$relative_path" ]] || gate_die "$label is empty"
  [[ "$relative_path" != /* ]] || gate_die "$label must be relative"
  [[ "$relative_path" != *'\\'* ]] || gate_die "$label contains a backslash"
  [[ "$relative_path" != *$'\r'* && "$relative_path" != *$'\n'* ]] \
    || gate_die "$label contains a control character"
  [[ "$relative_path" =~ ^[A-Za-z0-9._@+/-]+$ ]] \
    || gate_die "$label contains an unsupported character"

  IFS='/' read -r -a segments <<< "$relative_path"
  for segment in "${segments[@]}"; do
    [[ -n "$segment" && "$segment" != '.' && "$segment" != '..' ]] \
      || gate_die "$label contains an unsafe path segment"
  done
}

#
# 校验目录树只包含普通目录和普通文件，并拒绝任意层级的符号链接。
# 参数：$1（字符串）规范化根目录；$2（字符串）目录业务名称。
# 返回：0 表示目录树类型安全；异常时终止门禁。
#
gate_validate_tree_types() {
  local root="$1"
  local label="$2"
  local unsafe_entry

  unsafe_entry="$(find "$root" -type l -print -quit)"
  [[ -z "$unsafe_entry" ]] || gate_die "$label contains a symbolic link"
  unsafe_entry="$(find "$root" ! -type d ! -type f -print -quit)"
  [[ -z "$unsafe_entry" ]] || gate_die "$label contains a non-regular filesystem entry"
}

#
# 校验指定文件存在且为非空普通文件，供正式发布资产和证据复用。
# 参数：$1（字符串）根目录；$2（字符串）相对路径；$3（字符串）文件业务名称。
# 返回：0 表示文件存在、非符号链接且非空；异常时终止门禁。
#
gate_require_nonempty_file() {
  local root="$1"
  local relative_path="$2"
  local label="$3"
  local target="$root/$relative_path"

  gate_validate_relative_path "$relative_path" "$label path"
  [[ -f "$target" && ! -L "$target" && -s "$target" ]] \
    || gate_die "$label is missing, empty, or not a regular file"
}

#
# 校验指定文件存在并为普通文件；允许该证据文件为空以表达“零差异/零泄漏”。
# 参数：$1（字符串）根目录；$2（字符串）相对路径；$3（字符串）文件业务名称。
# 返回：0 表示文件存在且类型安全；异常时终止门禁。
#
gate_require_file() {
  local root="$1"
  local relative_path="$2"
  local label="$3"
  local target="$root/$relative_path"

  gate_validate_relative_path "$relative_path" "$label path"
  [[ -f "$target" && ! -L "$target" ]] \
    || gate_die "$label is missing or not a regular file"
}

#
# 快照目录树元数据，并拒绝可被非 owner 修改或通过硬链接从目录外替换的资产。
# 参数：$1（字符串）规范化根目录；$2（字符串）目录业务名称。
# 返回：0 表示 owner、mode、链接与文件类型满足不可变发布要求；同时刷新元数据映射。
#
gate_snapshot_tree_metadata() {
  local root="$1"
  local label="$2"
  local root_uid=''
  local relative_path
  local metadata
  local entry_uid
  local entry_mode
  local entry_links
  local entry_type
  local permission_bits

  GATE_MANIFEST_METADATA=()

  while IFS= read -r -d '' relative_path \
    && IFS= read -r -d '' metadata; do
    if [[ -z "$relative_path" ]]; then
      relative_path='.'
    else
      gate_validate_relative_path "$relative_path" "$label metadata entry"
    fi
    IFS=':' read -r _ _ entry_uid _ entry_mode entry_links _ _ entry_type <<< "$metadata"
    if [[ "$relative_path" == '.' ]]; then
      root_uid="$entry_uid"
    fi
    [[ -n "$root_uid" ]] || gate_die "$label root metadata cannot be inspected"
    [[ "$entry_uid" == "$root_uid" ]] \
      || gate_die "$label contains an asset owned by a different user"
    [[ "$entry_mode" =~ ^[0-7]{3,4}$ ]] \
      || gate_die "$label contains an asset with an unsupported mode"
    permission_bits=$((8#$entry_mode))
    (( (permission_bits & 0022) == 0 )) \
      || gate_die "$label contains a group-writable or world-writable asset"
    if [[ "$entry_type" == 'f' ]]; then
      [[ "$entry_links" -eq 1 ]] \
        || gate_die "$label contains a hard-linked file"
    fi
    GATE_MANIFEST_METADATA["$relative_path"]="$metadata"
  done < <(find "$root" -printf '%P\0%D:%i:%U:%G:%m:%n:%s:%T@:%y\0')
}

#
# 在全部业务语义检查完成后重验清单中的每个文件及目录元数据，缩小校验与回执之间的竞态窗口。
# 参数：$1（字符串）规范化根目录；$2（字符串）清单相对路径；$3（字符串）目录业务名称。
# 返回：0 表示资产集合、内容摘要、owner、mode、链接和时间元数据均未发生变化。
#
gate_revalidate_manifest() {
  local root="$1"
  local manifest_relative="$2"
  local label="$3"
  local manifest="$root/$manifest_relative"
  local file
  local relative_path
  local actual_manifest_sha256
  local actual_metadata
  local actual_file_count=0
  local actual_entry_count=0

  [[ "$root" == "$GATE_VALIDATED_MANIFEST_ROOT" ]] \
    || gate_die "$label manifest root changed before final validation"
  gate_validate_tree_types "$root" "$label"

  (
    cd "$root"
    sha256sum -c -- "$manifest_relative" >/dev/null
  ) || gate_die "$label file changed after manifest validation"
  while IFS= read -r -d '' file; do
    relative_path="${file#"$root/"}"
    [[ "$relative_path" == "$manifest_relative" ]] && continue
    gate_validate_relative_path "$relative_path" "$label final file"
    [[ -n "${GATE_MANIFEST_HASHES[$relative_path]+present}" ]] \
      || gate_die "$label gained a file after manifest validation"
    actual_file_count=$((actual_file_count + 1))
  done < <(find "$root" -type f -print0)
  [[ "$actual_file_count" -eq "${#GATE_MANIFEST_HASHES[@]}" ]] \
    || gate_die "$label file set changed after manifest validation"

  actual_manifest_sha256="$(sha256sum -- "$manifest")"
  actual_manifest_sha256="${actual_manifest_sha256%% *}"
  [[ "$actual_manifest_sha256" == "$GATE_VALIDATED_MANIFEST_SHA256" ]] \
    || gate_die "$label hash manifest changed after validation"

  while IFS= read -r -d '' relative_path \
    && IFS= read -r -d '' actual_metadata; do
    if [[ -z "$relative_path" ]]; then
      relative_path='.'
    else
      gate_validate_relative_path "$relative_path" "$label final metadata entry"
    fi
    [[ -n "${GATE_MANIFEST_METADATA[$relative_path]+present}" ]] \
      || gate_die "$label asset set changed after metadata validation"
    [[ "$actual_metadata" == "${GATE_MANIFEST_METADATA[$relative_path]}" ]] \
      || gate_die "$label asset metadata changed after validation"
    actual_entry_count=$((actual_entry_count + 1))
  done < <(find "$root" -printf '%P\0%D:%i:%U:%G:%m:%n:%s:%T@:%y\0')
  [[ "$actual_entry_count" -eq "${#GATE_MANIFEST_METADATA[@]}" ]] \
    || gate_die "$label asset set changed after metadata validation"
}

#
# 严格校验 SHA-256 清单：逐项验哈希、拒绝重复/额外项，并确保覆盖目录内全部普通文件。
# 参数：$1（字符串）规范化根目录；$2（字符串）清单相对路径；$3（字符串）目录业务名称。
# 返回：0 表示完整性通过；同时刷新内容摘要、元数据快照和规范化根目录。
#
gate_validate_manifest() {
  local root="$1"
  local manifest_relative="$2"
  local label="$3"
  local manifest="$root/$manifest_relative"
  local initial_manifest_sha256
  local final_manifest_sha256
  local line=''
  local line_number=0
  local expected_sha256
  local actual_sha256
  local relative_with_prefix
  local relative_path
  local target
  local file
  local actual_file_count=0
  local manifest_line_pattern='^([0-9A-Fa-f]{64})[[:space:]]([ *])(\./[A-Za-z0-9._@+/-]+)$'

  gate_validate_tree_types "$root" "$label"
  gate_require_nonempty_file "$root" "$manifest_relative" "$label hash manifest"
  initial_manifest_sha256="$(sha256sum -- "$manifest")"
  initial_manifest_sha256="${initial_manifest_sha256%% *}"
  GATE_MANIFEST_HASHES=()

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    [[ -n "$line" ]] || gate_die "$label hash manifest contains a blank line"
    if [[ ! "$line" =~ $manifest_line_pattern ]]; then
      gate_die "$label hash manifest has invalid syntax at line $line_number"
    fi

    expected_sha256="${BASH_REMATCH[1],,}"
    relative_with_prefix="${BASH_REMATCH[3]}"
    relative_path="${relative_with_prefix#./}"
    gate_validate_relative_path "$relative_path" "$label manifest entry"
    [[ "$relative_path" != "$manifest_relative" ]] \
      || gate_die "$label hash manifest must not contain itself"
    [[ -z "${GATE_MANIFEST_HASHES[$relative_path]+present}" ]] \
      || gate_die "$label hash manifest contains a duplicate path"

    target="$root/$relative_path"
    [[ -f "$target" && ! -L "$target" ]] \
      || gate_die "$label hash manifest references a missing or unsafe file"
    GATE_MANIFEST_HASHES["$relative_path"]="$expected_sha256"
  done < "$manifest"

  while IFS= read -r -d '' file; do
    relative_path="${file#"$root/"}"
    [[ "$relative_path" == "$manifest_relative" ]] && continue
    gate_validate_relative_path "$relative_path" "$label file"
    [[ -n "${GATE_MANIFEST_HASHES[$relative_path]+present}" ]] \
      || gate_die "$label contains a file missing from the hash manifest"
    actual_file_count=$((actual_file_count + 1))
  done < <(find "$root" -type f -print0)

  [[ "$actual_file_count" -eq "${#GATE_MANIFEST_HASHES[@]}" ]] \
    || gate_die "$label hash manifest does not exactly cover the directory"
  (
    cd "$root"
    sha256sum -c -- "$manifest_relative" >/dev/null
  ) || gate_die "$label file digest does not match the hash manifest"
  final_manifest_sha256="$(sha256sum -- "$manifest")"
  final_manifest_sha256="${final_manifest_sha256%% *}"
  [[ "$final_manifest_sha256" == "$initial_manifest_sha256" ]] \
    || gate_die "$label hash manifest changed during validation"
  GATE_VALIDATED_MANIFEST_SHA256="$final_manifest_sha256"
  GATE_VALIDATED_MANIFEST_ROOT="$root"
  gate_snapshot_tree_metadata "$root" "$label"
}

#
# 解析并校验发布元数据，确保发布包标识、源码提交和上一版本关系可追踪。
# 参数：$1（字符串）发布目录；$2（字符串）期望版本；$3（字符串）期望上一版本或 ANY；
#       $4（字符串）批准 Git commit；$5（字符串）标签。
# 返回：0 表示元数据完整且关系正确；异常时终止门禁。
#
gate_validate_release_metadata() {
  local release_dir="$1"
  local expected_release_id="$2"
  local expected_previous_release_id="$3"
  local expected_git_commit="$4"
  local label="$5"
  local metadata_file="$release_dir/RELEASE-METADATA"
  local line=''
  local line_number=0
  local key
  local value
  local normalized_timestamp
  local -A metadata=()
  local -a required_keys=(
    format_version
    release_id
    git_commit
    previous_release_id
    built_at_utc
  )

  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    [[ "$line" =~ ^([a-z_]+)=(.*)$ ]] \
      || gate_die "$label metadata has invalid syntax at line $line_number"
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    case "$key" in
      format_version|release_id|git_commit|previous_release_id|built_at_utc) ;;
      *) gate_die "$label metadata contains an unknown key" ;;
    esac
    [[ -z "${metadata[$key]+present}" ]] \
      || gate_die "$label metadata contains a duplicate key"
    metadata["$key"]="$value"
  done < "$metadata_file"

  for key in "${required_keys[@]}"; do
    [[ -n "${metadata[$key]+present}" ]] \
      || gate_die "$label metadata is missing a required key"
  done
  [[ "${#metadata[@]}" -eq "${#required_keys[@]}" ]] \
    || gate_die "$label metadata field count is invalid"
  [[ "${metadata[format_version]}" == '1' ]] \
    || gate_die "$label metadata format version is unsupported"
  gate_validate_release_id "${metadata[release_id]}" "$label metadata release_id" false
  [[ "${metadata[release_id]}" == "$expected_release_id" ]] \
    || gate_die "$label metadata release_id does not match the approved release"
  gate_validate_git_commit "${metadata[git_commit]}" "$label metadata git_commit"
  [[ "${metadata[git_commit]}" == "$expected_git_commit" ]] \
    || gate_die "$label metadata git_commit does not match the approved commit"
  gate_validate_release_id \
    "${metadata[previous_release_id]}" \
    "$label metadata previous_release_id" \
    true
  [[ "${metadata[previous_release_id]}" != "${metadata[release_id]}" ]] \
    || gate_die "$label metadata cannot reference itself as the previous release"
  if [[ "$expected_previous_release_id" != 'ANY' ]]; then
    [[ "${metadata[previous_release_id]}" == "$expected_previous_release_id" ]] \
      || gate_die "$label metadata previous_release_id does not match the approved rollback source"
  fi
  [[ "${metadata[built_at_utc]}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] \
    || gate_die "$label metadata built_at_utc is invalid"
  normalized_timestamp="$(date -u -d "${metadata[built_at_utc]}" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)" \
    || gate_die "$label metadata built_at_utc cannot be parsed"
  [[ "$normalized_timestamp" == "${metadata[built_at_utc]}" ]] \
    || gate_die "$label metadata built_at_utc is not normalized"
}

#
# 读取已通过严格校验的发布元数据字段，避免调用方重复实现键值解析。
# 参数：$1（字符串）发布目录；$2（字符串）字段名。
# 返回：标准输出返回字段值；字段缺失或重复时终止门禁。
#
gate_read_release_metadata_value() {
  local release_dir="$1"
  local expected_key="$2"
  local line=''
  local key
  local value
  local matched_value=''
  local match_count=0

  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    key="${line%%=*}"
    value="${line#*=}"
    if [[ "$key" == "$expected_key" ]]; then
      matched_value="$value"
      match_count=$((match_count + 1))
    fi
  done < "$release_dir/RELEASE-METADATA"

  [[ "$match_count" -eq 1 ]] \
    || gate_die "release metadata field $expected_key is missing or duplicated"
  printf '%s\n' "$matched_value"
}

#
# 校验首个正式版本的完整 SQL 基线执行顺序。
# 参数：$1（字符串）发布目录；$2（字符串）目录业务名称。
# 返回：0 表示顺序文件非空、仅含固定基线脚本，且全部进入哈希清单。
#
gate_validate_release_order() {
  local release_dir="$1"
  local label="$2"
  local order_file="$release_dir/sql/release-order.txt"
  local line=''
  local line_number=0
  local sql_path
  local manifest_path
  local -A ordered_sql=()

  [[ -f "$order_file" && ! -L "$order_file" && -s "$order_file" ]] \
    || gate_die "$label release-order.txt is missing, empty, or unsafe"
  while IFS= read -r line || [[ -n "$line" ]]; do
    line_number=$((line_number + 1))
    line="${line%$'\r'}"
    [[ -n "$line" ]] || gate_die "$label release-order.txt contains a blank line"
    sql_path="$line"
    gate_validate_relative_path "$sql_path" "$label release-order entry"
    [[ "$line_number" -le "${#GATE_REQUIRED_RELEASE_SQLS[@]}" ]] \
      || gate_die "$label release-order.txt contains an unapproved SQL asset"
    [[ "$sql_path" == "${GATE_REQUIRED_RELEASE_SQLS[$((line_number - 1))]}" ]] \
      || gate_die "$label release-order.txt does not match the approved baseline order"
    [[ "$sql_path" == *.sql ]] \
      || gate_die "$label release-order entry must end in .sql"
    [[ -z "${ordered_sql[$sql_path]+present}" ]] \
      || gate_die "$label release-order.txt contains a duplicate SQL path"
    manifest_path="sql/$sql_path"
    [[ -f "$release_dir/$manifest_path" && ! -L "$release_dir/$manifest_path" \
       && -s "$release_dir/$manifest_path" ]] \
      || gate_die "$label release-order entry does not resolve to a nonempty SQL file"
    [[ -n "${GATE_MANIFEST_HASHES[$manifest_path]+present}" ]] \
      || gate_die "$label release-order SQL is absent from the hash manifest"
    ordered_sql["$sql_path"]=1
  done < "$order_file"
  [[ "$line_number" -eq "${#GATE_REQUIRED_RELEASE_SQLS[@]}" ]] \
    || gate_die "$label release-order.txt must contain exactly the eight approved baseline SQL files"
}

#
# 从简单 YAML 映射中解析唯一标量，避免仅凭关键词存在性接受错误层级或重复配置。
# 参数：$1（字符串）YAML 文件；$2（字符串）点分路径；$3（字符串）期望值；$4（字符串）配置名称。
# 返回：0 表示目标路径只出现一次且值完全一致；异常时终止门禁。
#
gate_require_yaml_scalar() {
  local file="$1"
  local target_path="$2"
  local expected_value="$3"
  local label="$4"
  local actual_values

  actual_values="$(awk -v target="$target_path" '
    function trim(value) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      return value
    }
    {
      raw = $0
      sub(/\r$/, "", raw)
      if (index(raw, "\t") > 0) {
        exit 2
      }
      if (raw ~ /^[ ]*(#|$)/) {
        next
      }
      match(raw, /^ */)
      indent = RLENGTH
      content = substr(raw, indent + 1)
      if (content !~ /^[A-Za-z0-9_-]+[ ]*:/) {
        next
      }
      key = content
      sub(/[ ]*:.*$/, "", key)
      key = trim(key)
      value = content
      sub(/^[^:]+:[ ]*/, "", value)
      sub(/[ ]+#.*$/, "", value)
      value = trim(value)

      while (depth > 0 && indent <= levels[depth]) {
        delete keys[depth]
        delete levels[depth]
        depth--
      }
      full_path = ""
      for (position = 1; position <= depth; position++) {
        full_path = full_path (full_path == "" ? "" : ".") keys[position]
      }
      full_path = full_path (full_path == "" ? "" : ".") key
      if (value == "") {
        depth++
        keys[depth] = key
        levels[depth] = indent
      } else if (full_path == target) {
        print value
      }
    }
  ' "$file")" || gate_die "$label YAML cannot be parsed safely"
  [[ "$actual_values" == "$expected_value" ]] \
    || gate_die "$label must equal the approved production value"
}

#
# 校验环境示例中的非敏感固定值只出现一次，防止应用默认值与运维变量发生漂移。
# 参数：$1（字符串）环境示例文件；$2（字符串）变量名；$3（字符串）期望值；$4（字符串）配置名称。
# 返回：0 表示变量存在一次且值精确匹配；异常时终止门禁。
#
gate_require_env_assignment() {
  local file="$1"
  local key="$2"
  local expected_value="$3"
  local label="$4"
  local actual_values
  local expected_assignment

  actual_values="$(awk -F '=' -v target="$key" '
    {
      sub(/\r$/, "", $0)
      if ($1 == target) {
        count++
        value = substr($0, length($1) + 2)
      }
    }
    END {
      printf "%d\t%s", count, value
    }
  ' "$file")" || gate_die "$label cannot be parsed"
  expected_assignment=$'1\t'"$expected_value"
  [[ "$actual_values" == "$expected_assignment" ]] \
    || gate_die "$label must equal the approved production value"
}

#
# 解析 Nginx location 块，确保两个 Actuator 公网路径都唯一且只返回 404。
# 参数：$1（字符串）Nginx 配置文件；$2（字符串）配置名称。
# 返回：0 表示精确路径和前缀路径均拒绝代理，且管理端口从未作为上游暴露。
#
gate_validate_nginx_actuator_denial() {
  local file="$1"
  local label="$2"

  awk '
    function trim(value) {
      sub(/^[[:space:]]+/, "", value)
      sub(/[[:space:]]+$/, "", value)
      return value
    }
    function brace_delta(value, copy, opens, closes) {
      copy = value
      opens = gsub(/{/, "", copy)
      copy = value
      closes = gsub(/}/, "", copy)
      return opens - closes
    }
    function finish_block() {
      if (active == 1 && exact_denied != 1) {
        invalid = 1
      }
      if (active == 2 && prefix_denied != 1) {
        invalid = 1
      }
      active = 0
      depth = 0
    }
    {
      line = $0
      sub(/\r$/, "", line)
      sub(/[[:space:]]+#.*$/, "", line)
      line = trim(line)
      if (line == "") {
        next
      }
      if (line ~ /^proxy_pass[[:space:]]/ &&
          line ~ /(18080|RUOYI_MANAGEMENT_PORT|\/actuator)/) {
        invalid = 1
      }
      if (line ~ /^location[[:space:]].*actuator/) {
        actuator_locations++
        if (line == "location = /prod-api/actuator {") {
          exact_count++
          active = 1
          exact_denied = 0
        } else if (line == "location ^~ /prod-api/actuator/ {") {
          prefix_count++
          active = 2
          prefix_denied = 0
        } else {
          invalid = 1
          active = 0
        }
        depth = brace_delta(line)
        next
      }
      if (active > 0) {
        if (line == "return 404;") {
          if (active == 1) exact_denied++
          if (active == 2) prefix_denied++
        }
        if (line ~ /^proxy_pass[[:space:]]/) {
          invalid = 1
        }
        depth += brace_delta(line)
        if (depth == 0) {
          finish_block()
        }
      }
    }
    END {
      if (active > 0) finish_block()
      if (invalid || actuator_locations != 2 || exact_count != 1 || prefix_count != 1) {
        exit 1
      }
    }
  ' "$file" || gate_die "$label does not enforce the public Actuator deny contract"
}

#
# 校验生产配置的运行门禁、回环管理端口、指标快照时效和 Nginx 拒绝规则。
# 参数：$1（字符串）发布目录；$2（字符串）目录业务名称。
# 返回：0 表示关键生产配置均位于正确层级且与批准值完全一致。
#
gate_validate_release_configuration() {
  local release_dir="$1"
  local label="$2"
  local application_file="$release_dir/deployment/config/application.yml"
  local druid_file="$release_dir/deployment/config/application-druid.yml"
  local environment_file="$release_dir/deployment/config/ruoyi.env.example"
  local nginx_file="$release_dir/deployment/nginx/ruoyi.conf"

  gate_require_yaml_scalar "$application_file" 'server.address' '127.0.0.1' \
    "$label backend bind address"
  gate_require_yaml_scalar "$application_file" 'management.server.address' '127.0.0.1' \
    "$label management bind address"
  gate_require_yaml_scalar "$application_file" 'management.server.port' \
    '${RUOYI_MANAGEMENT_PORT:18080}' "$label management port"
  gate_require_yaml_scalar "$application_file" \
    'management.endpoints.web.exposure.include' 'health,prometheus' \
    "$label management endpoint exposure"
  gate_require_yaml_scalar "$application_file" \
    'flowable.database-schema-update' '"false"' "$label Flowable schema policy"
  gate_require_yaml_scalar "$application_file" \
    'flowable.variable-json-mapper' 'jackson' "$label Flowable JSON mapper"
  gate_require_yaml_scalar "$application_file" \
    'flowable.runtime.production-gate-enabled' 'true' "$label production runtime gate"
  gate_require_yaml_scalar "$application_file" \
    'flowable.runtime.metrics-snapshot-max-age' \
    '${FLOWABLE_RUNTIME_METRICS_SNAPSHOT_MAX_AGE:PT3M}' \
    "$label metrics snapshot max age"
  gate_require_yaml_scalar "$application_file" 'token.secret' '${RUOYI_TOKEN_SECRET:}' \
    "$label token secret injection"
  gate_require_yaml_scalar "$application_file" 'token.secret-file.enabled' 'true' \
    "$label token secret file enablement"
  gate_require_yaml_scalar "$application_file" 'token.secret-file.path' \
    '${RUOYI_TOKEN_SECRET_FILE:/var/lib/ruoyi-secrets/token-secret}' \
    "$label token secret file path"
  gate_require_yaml_scalar "$druid_file" 'spring.datasource.druid.master.password' \
    '${RUOYI_DB_PASSWORD}' "$label datasource password injection"
  gate_require_env_assignment "$environment_file" 'RUOYI_MANAGEMENT_PORT' '18080' \
    "$label management environment port"
  gate_require_env_assignment "$environment_file" \
    'FLOWABLE_RUNTIME_METRICS_SNAPSHOT_MAX_AGE' 'PT3M' \
    "$label metrics max-age environment value"
  gate_require_env_assignment "$environment_file" 'RUOYI_TOKEN_SECRET' '' \
    "$label token secret example"
  gate_require_env_assignment "$environment_file" 'RUOYI_TOKEN_SECRET_FILE' \
    '/var/lib/ruoyi-secrets/token-secret' "$label token secret file example"
  gate_require_env_assignment "$environment_file" 'DRUID_MONITOR_PASSWORD' '' \
    "$label Druid monitor password example"
  gate_validate_nginx_actuator_denial "$nginx_file" "$label Nginx configuration"
}

#
# 扫描目录内全部普通文件（包括二进制字节），拒绝凭据文件、认证头、Cookie 和非空敏感赋值。
# 参数：$1（字符串）规范化根目录；$2（字符串）目录业务名称。
# 返回：0 表示未发现 token、Druid、Basic/Bearer、Cookie 或环境凭据泄漏。
#
gate_validate_sensitive_content() {
  local root="$1"
  local label="$2"
  local file
  local basename_value
  local sensitive_assignment_pattern
  local authentication_pattern
  local sensitive_file

  sensitive_assignment_pattern='(RUOYI_TOKEN_SECRET|RUOYI_DB_PASSWORD|SPRING_DATA_REDIS_PASSWORD|DRUID_MONITOR_PASSWORD)[[:space:]]*=[[:space:]]*[^[:space:]#$]|(^|[^A-Za-z0-9_])(token[._-]?secret|druid[_a-z.-]*password)[[:space:]]*[:=][[:space:]]*[^[:space:]#$]'
  authentication_pattern='(Authorization|Proxy-Authorization)[[:space:]]*:[[:space:]]*(Basic|Bearer)[[:space:]]+[A-Za-z0-9+/._=-]{8,}|(Cookie|Set-Cookie)[[:space:]]*:[[:space:]]*[^[:space:]]+|(JSESSIONID|rememberMe|Admin-Token)=[^[:space:];]+'

  while IFS= read -r -d '' file; do
    basename_value="${file##*/}"
    case "$basename_value" in
      accounts.local.md|ruoyi.env|.env|.env.*|*.cnf|*.p12|*.jks|*.key|*.pem)
        gate_die "$label contains a forbidden credential file"
        ;;
    esac
  done < <(find "$root" -type f -print0)
  sensitive_file="$(find "$root" -type f \
    -exec grep -aEil -- "$sensitive_assignment_pattern|$authentication_pattern" {} + \
    | head -n 1 || true)"
  [[ -z "$sensitive_file" ]] || gate_die "$label contains a sensitive value pattern"
}

#
# 校验单个不可变发布包的必需资产、全量哈希和元数据；当前包额外校验生产配置与增量 SQL。
# 参数：$1（字符串）发布目录；$2（字符串）版本标识；$3（字符串）期望上一版本或 ANY；
#       $4（字符串）批准 Git commit；$5（字符串）包角色 current|rollback；$6（字符串）标签。
# 返回：标准输出不返回业务数据；通过全局变量提供本包清单 SHA-256。
#
gate_validate_release_bundle() {
  local release_dir="$1"
  local release_id="$2"
  local expected_previous_release_id="$3"
  local expected_git_commit="$4"
  local bundle_role="$5"
  local label="$6"
  local required_asset
  local -a required_assets=(
    ruoyi-admin.jar
    frontend/index.html
    RELEASE-METADATA
  )

  case "$bundle_role" in
    current)
      required_assets+=(
        deployment/README.md
        deployment/config/application.yml
        deployment/config/application-druid.yml
        deployment/config/ruoyi.env.example
        deployment/nginx/ruoyi.conf
        deployment/systemd/ruoyi-backend.service
        deployment/scripts/workflow-release-gate.sh
        deployment/scripts/tests/workflow-release-gate-test.sh
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql
        sql/flowable/business/8.0.0__workflow_model_version_guard.sql
        sql/flowable/business/8.0.0__workflow_business.sql
        sql/flowable/menu/8.0.0__workflow_menu.sql
        sql/integration/8.0.0__sms_oss.sql
        sql/flowable/verify/8.0.0__verify.sql
        sql/flowable/verify/8.0.0__verify_workflow_business.sql
        sql/flowable/verify/8.0.0__verify_workflow_menu.sql
      )
      ;;
    rollback) ;;
    *) gate_die "$label has an unsupported release bundle role" ;;
  esac

  [[ "$(basename -- "$release_dir")" == "$release_id" ]] \
    || gate_die "$label directory basename does not match its approved release ID"
  for required_asset in "${required_assets[@]}"; do
    gate_require_nonempty_file "$release_dir" "$required_asset" "$label asset $required_asset"
  done
  gate_validate_manifest "$release_dir" 'SHA256SUMS' "$label"
  gate_validate_release_metadata \
    "$release_dir" "$release_id" "$expected_previous_release_id" \
    "$expected_git_commit" "$label"
  if [[ "$bundle_role" == 'current' ]]; then
    gate_require_file "$release_dir" 'sql/release-order.txt' "$label SQL release order"
    gate_validate_release_order "$release_dir" "$label"
    gate_validate_release_configuration "$release_dir" "$label"
  fi
  gate_validate_sensitive_content "$release_dir" "$label"
  gate_revalidate_manifest "$release_dir" 'SHA256SUMS' "$label"
}

#
# 校验文件仅包含指定的单行结果，用于连接性、状态和零差异证据。
# 参数：$1（字符串）文件；$2（字符串）期望整行；$3（字符串）证据名称。
# 返回：0 表示文件恰好一行且完全匹配；异常时终止门禁。
#
gate_require_exact_line() {
  local file="$1"
  local expected="$2"
  local label="$3"

  [[ "$(wc -l < "$file")" -eq 1 ]] \
    || gate_die "$label must contain exactly one line"
  grep -Fqx -- "$expected" "$file" \
    || gate_die "$label does not contain the required result"
}

#
# 使用标准 JSON 解析器验证 Actuator 健康响应，拒绝仅包含 UP 文本但不是合法响应对象的证据。
# 参数：$1（字符串）健康响应 JSON 文件；$2（字符串）证据名称。
# 返回：0 表示根对象的 status 精确为 UP；异常时终止门禁。
#
gate_validate_health_response() {
  local file="$1"
  local label="$2"

  if ! python3 - "$file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as source:
    payload = json.load(source)
if not isinstance(payload, dict) or payload.get("status") != "UP":
    raise SystemExit(1)
PY
  then
    gate_die "$label is not a valid Actuator UP response"
  fi
}

#
# 验证 Prometheus 抓取同时包含成功运行快照和未降级清理锁，避免空抓取或旧页面冒充可观测性。
# 参数：$1（字符串）Prometheus 文本快照；$2（字符串）证据名称。
# 返回：0 表示两个低基数运行门禁指标各出现一次且值分别为 1 和 0。
#
gate_validate_prometheus_snapshot() {
  local file="$1"
  local label="$2"

  awk '
    $1 == "workflow_runtime_metrics_snapshot_available" {
      snapshot_count++
      if (NF != 2 || $2 !~ /^1([.]0+)?$/) invalid = 1
    }
    $1 == "workflow_attachment_cleanup_lock_degraded" {
      degraded_count++
      if (NF != 2 || $2 !~ /^0([.]0+)?$/) invalid = 1
    }
    END {
      exit(!invalid && snapshot_count == 1 && degraded_count == 1 ? 0 : 1)
    }
  ' "$file" || gate_die "$label does not prove a ready metrics snapshot and healthy cleanup lock"
}

#
# 校验 MySQL 最小权限汇总，拒绝全局、其他 schema、对象级或可转授权权限混入正式账号。
# 参数：$1（字符串）权限证据文件；$2（字符串）账号职责 app|backup；$3（字符串）证据名称。
# 返回：0 表示权限集合、可转授权计数和越界权限计数完全符合冻结合同。
#
gate_validate_mysql_grants() {
  local file="$1"
  local account_role="$2"
  local label="$3"
  local expected

  case "$account_role" in
    app) expected=$'app\tDELETE,INSERT,SELECT,UPDATE\t0\t0\t0' ;;
    backup) expected=$'backup\tSELECT,SHOW VIEW,TRIGGER\t0\t0\t0' ;;
    *) gate_die "$label has an unsupported MySQL account role" ;;
  esac
  gate_require_exact_line "$file" "$expected" "$label"
}

#
# 校验 Redis AOF 已启用且最近重写和写入状态正常，禁止只凭 INFO 文件非空关闭持久化门禁。
# 参数：$1（字符串）规范化 Redis persistence 证据；$2（字符串）证据名称。
# 返回：0 表示三项固定状态按唯一顺序全部通过。
#
gate_validate_redis_persistence() {
  local file="$1"
  local label="$2"
  local expected=$'aof_enabled=1\naof_last_bgrewrite_status=ok\naof_last_write_status=ok'
  local actual

  actual="$(tr -d '\r' < "$file")"
  [[ "$actual" == "$expected" ]] \
    || gate_die "$label does not prove healthy AOF persistence"
}

#
# 校验 Redis 内存淘汰策略为 noeviction，防止流程缓存键在容量压力下被静默逐出。
# 参数：$1（字符串）规范化策略证据；$2（字符串）证据名称。
# 返回：0 表示策略精确为冻结生产值。
#
gate_validate_redis_memory_policy() {
  local file="$1"
  local label="$2"

  gate_require_exact_line "$file" 'maxmemory-policy=noeviction' "$label"
}

#
# 校验 ISO-8601 时间不仅格式正确，而且能被系统时间库解析为真实日期与时区。
# 参数：$1（字符串）ISO-8601 时间；$2（字符串）字段业务名称。
# 返回：0 表示时间有效；异常时终止门禁。
#
gate_validate_timestamp() {
  local timestamp="$1"
  local label="$2"

  [[ "$timestamp" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})$ ]] \
    || gate_die "$label has an invalid timestamp format"
  date -d "$timestamp" >/dev/null 2>&1 \
    || gate_die "$label has an impossible timestamp"
}

#
# 校验全新环境身份 TSV，冻结一次安装的运行、基础设施身份与严格先后时间。
# 参数：$1（字符串）身份 TSV 文件；$2（字符串）证据名称。
# 返回：0 表示唯一数据行合法；同时记录本轮开始/结束 epoch 供签字和双轮时序校验。
#
gate_validate_environment_identity() {
  local file="$1"
  local label="$2"
  local header=''
  local run_id
  local environment_id
  local started_at
  local finished_at
  local database_identity
  local redis_identity
  local attachment_storage_identity
  local extra
  local identity_value
  local normalized_identity
  local current_epoch

  [[ "$(wc -l < "$file")" -eq 2 ]] \
    || gate_die "$label must contain one header and one environment row"
  IFS= read -r header < "$file" || gate_die "$label is empty"
  header="${header%$'\r'}"
  [[ "$header" == $'run_id\tenvironment_id\tstarted_at\tfinished_at\tdatabase_identity\tredis_identity\tattachment_storage_identity' ]] \
    || gate_die "$label header is invalid"
  IFS=$'\t' read -r run_id environment_id started_at finished_at \
    database_identity redis_identity attachment_storage_identity extra \
    < <(tail -n +2 "$file")
  attachment_storage_identity="${attachment_storage_identity%$'\r'}"
  [[ -n "$run_id" && -n "$environment_id" && -n "$started_at" \
     && -n "$finished_at" && -n "$database_identity" && -n "$redis_identity" \
     && -n "$attachment_storage_identity" && -z "${extra:-}" ]] \
    || gate_die "$label contains an incomplete environment row"

  # 身份值只允许可审计标识字符，并拒绝常见占位值；不得在该文件中写连接串或凭据。
  for identity_value in \
    "$run_id" "$environment_id" "$database_identity" \
    "$redis_identity" "$attachment_storage_identity"; do
    [[ "$identity_value" =~ ^[A-Za-z0-9][A-Za-z0-9._:@/-]{2,127}$ ]] \
      || gate_die "$label contains an unsafe environment identifier"
    normalized_identity="${identity_value^^}"
    case "$normalized_identity" in
      NONE|UNKNOWN|TODO|TBD|MOCK|EXAMPLE|PLACEHOLDER|*PLACEHOLDER*)
        gate_die "$label contains a placeholder environment identifier"
        ;;
    esac
  done

  gate_validate_timestamp "$started_at" "$label start"
  gate_validate_timestamp "$finished_at" "$label finish"
  GATE_ENVIRONMENT_STARTED_EPOCH="$(date -d "$started_at" '+%s')"
  GATE_ENVIRONMENT_FINISHED_EPOCH="$(date -d "$finished_at" '+%s')"
  current_epoch="$(date '+%s')" || gate_die "$label cannot read the current time"
  [[ "$GATE_ENVIRONMENT_STARTED_EPOCH" -lt "$GATE_ENVIRONMENT_FINISHED_EPOCH" ]] \
    || gate_die "$label environment finish must be later than its start"
  [[ "$GATE_ENVIRONMENT_FINISHED_EPOCH" -le "$current_epoch" ]] \
    || gate_die "$label environment run finishes in the future"
}

#
# 校验全新安装备份已真实生成并在 111 张恢复表上完成 mysqlcheck。
# 参数：$1（字符串）全新安装证据目录；$2（字符串）证据名称。
# 返回：0 表示备份摘要格式正确，且 mysqlcheck 恰好记录 111 个 OK 结果。
#
gate_validate_backup_restore() {
  local evidence_dir="$1"
  local label="$2"
  local schema_sha_file="$evidence_dir/backup-smoke/schema-sha256.txt"
  local mysqlcheck_file="$evidence_dir/backup-smoke/mysqlcheck.txt"

  [[ "$(wc -l < "$schema_sha_file")" -eq 1 ]] \
    || gate_die "$label backup digest must contain exactly one line"
  grep -Eq '^[0-9a-f]{64}[[:space:]]{2}.*schema[.]sql$' "$schema_sha_file" \
    || gate_die "$label backup digest is invalid"
  awk '
    NF != 2 || $NF != "OK" || seen[$1]++ { invalid = 1 }
    NF > 0 { rows++ }
    END { exit(!invalid && rows == 111 ? 0 : 1) }
  ' "$mysqlcheck_file" \
    || gate_die "$label mysqlcheck must contain exactly 111 OK table results"
}

#
# 校验生产切换 TSV，将发布 ID、发布清单摘要、真实切换时间和原始来源绑定为同一事件。
# 参数：$1（字符串）切换 TSV；$2（字符串）证据目录；$3（字符串）期望发布 ID；
#       $4（字符串）期望发布清单 SHA-256；$5（整数）发布包构建时间 epoch。
# 返回：0 表示切换发生在构建之后且不晚于当前时间；同时记录切换 epoch。
#
gate_validate_production_switch() {
  local file="$1"
  local evidence_dir="$2"
  local expected_release_id="$3"
  local expected_manifest_sha256="$4"
  local built_at_epoch="$5"
  local header=''
  local release_id
  local release_manifest_sha256
  local switched_at
  local source_file
  local source_sha256
  local extra
  local current_epoch

  [[ "$(wc -l < "$file")" -eq 2 ]] \
    || gate_die 'production-switch.tsv must contain one header and one switch row'
  IFS= read -r header < "$file" || gate_die 'production-switch.tsv is empty'
  header="${header%$'\r'}"
  [[ "$header" == $'release_id\trelease_manifest_sha256\tswitched_at\tsource_file\tsource_sha256' ]] \
    || gate_die 'production-switch.tsv header is invalid'
  IFS=$'\t' read -r release_id release_manifest_sha256 switched_at \
    source_file source_sha256 extra < <(tail -n +2 "$file")
  source_sha256="${source_sha256%$'\r'}"
  [[ -n "$release_id" && -n "$release_manifest_sha256" && -n "$switched_at" \
     && -n "$source_file" && -n "$source_sha256" && -z "${extra:-}" ]] \
    || gate_die 'production-switch.tsv contains an incomplete switch row'
  [[ "$release_id" == "$expected_release_id" ]] \
    || gate_die 'production switch release ID does not match the approved release'
  [[ "$release_manifest_sha256" == "$expected_manifest_sha256" ]] \
    || gate_die 'production switch manifest digest does not match the approved release'
  [[ "$source_file" == 'production-switch-source.txt' ]] \
    || gate_die 'production switch source path is not the frozen path'
  gate_require_manifest_source \
    "$evidence_dir" "$source_file" "$source_sha256" 'production switch'
  gate_validate_timestamp "$switched_at" 'production switch time'
  GATE_PRODUCTION_SWITCH_EPOCH="$(date -d "$switched_at" '+%s')"
  current_epoch="$(date '+%s')" || gate_die 'production switch cannot read the current time'
  [[ "$GATE_PRODUCTION_SWITCH_EPOCH" -ge "$built_at_epoch" ]] \
    || gate_die 'production switch occurred before the release was built'
  [[ "$GATE_PRODUCTION_SWITCH_EPOCH" -le "$current_epoch" ]] \
    || gate_die 'production switch occurs in the future'
}

#
# 校验服务状态证据中的每一行均为 active，避免部分节点或服务失败被忽略。
# 参数：$1（字符串）服务状态文件；$2（字符串）证据名称。
# 返回：0 表示至少一行且所有行均为 active；异常时终止门禁。
#
gate_validate_active_services() {
  local file="$1"
  local label="$2"

  [[ -s "$file" ]] || gate_die "$label is empty"
  if grep -Evqx 'active' "$file"; then
    gate_die "$label contains a non-active service state"
  fi
}

#
# 校验数据库验收 TSV 的三段正式脚本、固定检查项、表头和逐项 PASS 结果。
# 参数：$1（字符串）验收结果文件；$2（字符串）证据名称。
# 返回：0 表示 58 个正式检查恰好各出现一次且全部通过；异常时终止门禁。
#
gate_validate_database_result() {
  local file="$1"
  local label="$2"

  awk -F '\t' '
    function detail_is_valid(check_name, detail) {
      if (check_name == "schema_versions")
        return detail == "common.schema.version=8.0.0.0, schema.version=8.0.0.0"
      if (check_name == "model_version_unique_constraint")
        return detail ~ /^matching_indexes=1, indexes=[A-Za-z0-9_]+$/
      if (check_name == "model_version_duplicate_groups")
        return detail == "duplicate_groups=0"
      if (check_name == "flowable_table_inventory")
        return detail == "missing=0, missing_objects=none, unexpected=0, unexpected_objects=none, disabled=0, disabled_objects=none"
      if (check_name == "deadletter_jobs")
        return detail == "actual=0"
      if (check_name == "flowable_dmn_table_presence" ||
          check_name == "workflow_connector_table_presence")
        return detail == "missing_or_invalid=none"
      if (check_name == "workflow_schema_table_counts")
        return detail == "total=111, ruoyi=20, quartz=11, flowable=36, workflow=44"
      if (check_name == "workflow_connector_columns" ||
          check_name == "workflow_call_activity_snapshot_columns" ||
          check_name == "workflow_call_activity_snapshot_indexes" ||
          check_name == "workflow_call_activity_snapshot_checks" ||
          check_name == "workflow_participant_rule_columns" ||
          check_name == "workflow_participant_rule_indexes" ||
          check_name == "workflow_participant_rule_checks")
        return detail == "missing=none"
      if (check_name == "workflow_call_activity_snapshot_integrity")
        return detail == "invalid_rows=0"
      if (check_name == "workflow_business_tables")
        return detail == "present=13, missing=none"
      if (check_name == "workflow_business_columns")
        return detail == "missing=none"
      if (check_name == "wf_attachment_cleanup_retry_columns")
        return detail == "invalid=none"
      if (check_name == "workflow_draft_column_types")
        return detail == "invalid=none"
      if (check_name == "wf_category_active_code")
        return detail ~ /^columns=1, extra=STORED GENERATED, expression=.+$/
      if (check_name == "workflow_business_indexes")
        return detail == "issues=0, indexes=none"
      if (check_name == "workflow_business_checks")
        return detail == "missing_or_unenforced=none"
      if (check_name == "workflow_business_foreign_keys")
        return detail == "missing_or_invalid=none"
      if (check_name == "wf_attachment_cleanup_retry_check_clause")
        return detail ~ /^constraints=1, enforced=YES, canonical_sha256=[0-9a-f]{64}$/
      if (check_name == "workflow_business_data_integrity")
        return detail == "issues=0, detail=none"
      if (check_name == "workflow_participant_rule_tables")
        return detail == "present=2, missing=none"
      if (check_name == "workflow_participant_audit_retention_foreign_keys")
        return detail == "unexpected=none"
      if (check_name == "workflow_participant_rule_data_integrity")
        return detail == "issues=0, detail=none"
      if (check_name == "workflow_notification_tables")
        return detail == "present=6, missing=none"
      if (check_name == "workflow_notification_integrity")
        return detail == "issues=0, detail=none"
      if (check_name == "workflow_runtime_integration_tables")
         return detail == "present=6, missing=none"
      if (check_name == "workflow_runtime_integration_columns" ||
          check_name == "workflow_runtime_integration_indexes" ||
          check_name == "workflow_extension_columns")
        return detail == "missing=none"
      if (check_name == "workflow_runtime_integration_checks" ||
          check_name == "workflow_extension_checks")
        return detail == "missing_or_unenforced=none"
      if (check_name == "workflow_runtime_integration_foreign_keys")
         return detail == "matching=5, expected=5"
      if (check_name == "workflow_runtime_integration_data_integrity" ||
          check_name == "workflow_extension_data_integrity")
        return detail == "issues=0, detail=none"
      if (check_name == "workflow_extension_tables")
        return detail == "present=7, missing=none"
      if (check_name == "workflow_extension_indexes")
        return detail == "issues=0, indexes=none"
      if (check_name == "workflow_extension_foreign_keys")
        return detail == "missing_or_invalid=none"
      if (check_name == "workflow_sla_tables")
        return detail == "found=6, innodb=6, expected=6"
      if (check_name == "workflow_sla_constraints")
        return detail == "found=28, expected=28"
      if (check_name == "workflow_sla_foreign_keys")
        return detail == "found=3, expected=3"
      if (check_name == "workflow_sla_data_integrity")
        return detail == "issues=0, detail=none"
      if (check_name == "workflow_bpmn_event_tables")
        return detail == "found=3, expected=3"
      if (check_name == "workflow_bpmn_event_constraints") {
        split(detail, values, /[=, ]+/)
        return detail ~ /^found=[0-9]+, expected_at_least=12$/ &&
               values[2] + 0 >= 12 && values[4] + 0 == 12
      }
      if (check_name == "workflow_bpmn_event_data_integrity")
        return detail == "issues=0"
      if (check_name == "workflow_menu_count")
        return detail == "rows=98, natural_keys=98"
      if (check_name == "workflow_menu_tree")
        return detail == "directories=2, pages=21, buttons=75, invalid_routes=0"
      if (check_name == "workflow_retired_permissions")
        return detail == "legacy_rows=0"
      if (check_name == "workflow_roles")
        return detail == "active_roles=5, duplicate_roles=0, assignments=workflow_admin:98,workflow_approver:18,workflow_auditor:24,workflow_designer:47,workflow_starter:21"
      if (check_name == "workflow_admin_menu_scope")
        return detail == "assigned=98, expected=98"
      if (check_name == "workflow_draft_role_scope")
        return detail == "permissions=5, starter=5, unauthorized=0"
      if (check_name == "workflow_admin_only_instance_management")
        return detail == "unauthorized_assignments=0, roles=none, admin_management_permissions=2"
      if (check_name == "workflow_auditor_read_only")
        return detail == "write_permissions=0, values=none"
      return 0
    }
    BEGIN {
      expected_file[1] = "8.0.0__verify.sql"
      expected_file[2] = "8.0.0__verify_workflow_business.sql"
      expected_file[3] = "8.0.0__verify_workflow_menu.sql"

      required["1|schema_versions"] = 1
      required["1|model_version_unique_constraint"] = 1
      required["1|model_version_duplicate_groups"] = 1
      required["1|flowable_table_inventory"] = 1
      required["1|deadletter_jobs"] = 1

      required["2|flowable_dmn_table_presence"] = 1
      required["2|workflow_schema_table_counts"] = 1
      required["2|workflow_connector_table_presence"] = 1
      required["2|workflow_connector_columns"] = 1
      required["2|workflow_call_activity_snapshot_columns"] = 1
      required["2|workflow_call_activity_snapshot_indexes"] = 1
      required["2|workflow_call_activity_snapshot_checks"] = 1
      required["2|workflow_call_activity_snapshot_integrity"] = 1
      required["2|workflow_business_tables"] = 1
      required["2|workflow_business_columns"] = 1
      required["2|wf_attachment_cleanup_retry_columns"] = 1
      required["2|workflow_draft_column_types"] = 1
      required["2|wf_category_active_code"] = 1
      required["2|workflow_business_indexes"] = 1
      required["2|workflow_business_checks"] = 1
      required["2|workflow_business_foreign_keys"] = 1
      required["2|wf_attachment_cleanup_retry_check_clause"] = 1
      required["2|workflow_business_data_integrity"] = 1
      required["2|workflow_participant_rule_tables"] = 1
      required["2|workflow_participant_rule_columns"] = 1
      required["2|workflow_participant_rule_indexes"] = 1
      required["2|workflow_participant_rule_checks"] = 1
      required["2|workflow_participant_audit_retention_foreign_keys"] = 1
      required["2|workflow_participant_rule_data_integrity"] = 1
      required["2|workflow_notification_tables"] = 1
      required["2|workflow_notification_integrity"] = 1
      required["2|workflow_runtime_integration_tables"] = 1
      required["2|workflow_runtime_integration_columns"] = 1
      required["2|workflow_runtime_integration_indexes"] = 1
      required["2|workflow_runtime_integration_checks"] = 1
      required["2|workflow_runtime_integration_foreign_keys"] = 1
      required["2|workflow_runtime_integration_data_integrity"] = 1
      required["2|workflow_extension_tables"] = 1
      required["2|workflow_extension_columns"] = 1
      required["2|workflow_extension_indexes"] = 1
      required["2|workflow_extension_checks"] = 1
      required["2|workflow_extension_foreign_keys"] = 1
      required["2|workflow_extension_data_integrity"] = 1
      required["2|workflow_sla_tables"] = 1
      required["2|workflow_sla_constraints"] = 1
      required["2|workflow_sla_foreign_keys"] = 1
      required["2|workflow_sla_data_integrity"] = 1
      required["2|workflow_bpmn_event_tables"] = 1
      required["2|workflow_bpmn_event_constraints"] = 1
      required["2|workflow_bpmn_event_data_integrity"] = 1

      required["3|workflow_menu_count"] = 1
      required["3|workflow_menu_tree"] = 1
      required["3|workflow_retired_permissions"] = 1
      required["3|workflow_roles"] = 1
      required["3|workflow_admin_menu_scope"] = 1
      required["3|workflow_draft_role_scope"] = 1
      required["3|workflow_admin_only_instance_management"] = 1
      required["3|workflow_auditor_read_only"] = 1
    }
    {
      sub(/\r$/, "", $NF)
      if ($1 == "FILE") {
        if (NF != 2) exit 1
        section++
        if (section > 3 || $2 != expected_file[section]) exit 1
        header_seen = 0
        next
      }
      if ($1 == "check_name" && $2 == "result" && $3 == "detail" && NF == 3) {
        if (section < 1) exit 1
        header_seen = 1
        next
      }
      if (section < 1 || !header_seen || NF != 3 || $2 != "PASS" || $3 == "") {
        exit 1
      }
      key = section "|" $1
      if (!(key in required) || seen[key]++) exit 1
      if ($1 ~ /[<>]/ || $3 ~ /[<>]/) exit 1
      if (!detail_is_valid($1, $3)) exit 1
      passed++
    }
    END {
      if (section != 3 || passed != 58) exit 1
      for (key in required) {
        if (seen[key] != 1) exit 1
      }
    }
  ' "$file" || gate_die "$label does not match the complete database verification contract"
}

#
# 校验来源文件路径与 SHA-256 已被当前证据清单冻结，禁止仅填写不可追溯的结论。
# 参数：$1（字符串）证据目录；$2（字符串）来源相对路径；$3（字符串）期望摘要；$4（字符串）证据名称。
# 返回：0 表示来源为非空普通文件且摘要与已验证清单完全一致；异常时终止门禁。
#
gate_require_manifest_source() {
  local evidence_dir="$1"
  local source_path="$2"
  local source_sha256="$3"
  local label="$4"

  gate_validate_relative_path "$source_path" "$label source path"
  [[ "$source_sha256" =~ ^[0-9a-f]{64}$ ]] \
    || gate_die "$label source digest is invalid"
  [[ -f "$evidence_dir/$source_path" && ! -L "$evidence_dir/$source_path" \
     && -s "$evidence_dir/$source_path" ]] \
    || gate_die "$label source file is missing, empty, or unsafe"
  [[ -n "${GATE_MANIFEST_HASHES[$source_path]+present}" \
     && "${GATE_MANIFEST_HASHES[$source_path]}" == "$source_sha256" ]] \
    || gate_die "$label source digest is not bound to the evidence manifest"
}

#
# 校验五职责角色真实业务烟测表，分别核对传输层 HTTP 状态与 AjaxResult 业务码。
# 参数：$1（字符串）烟测 TSV 文件；$2（字符串）证据名称；$3（字符串）证据根目录。
# 返回：0 表示六个固定动作各出现一次，传输/业务结果及副作用均符合业务契约。
#
gate_validate_business_smoke() {
  local file="$1"
  local label="$2"
  local evidence_dir="$3"
  local header=''
  local started_at
  local finished_at
  local role
  local action
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
  local unexpected_side_effect_count
  local source_file
  local source_sha256
  local extra
  local expected_role
  local expected_method
  local contract_transport_status
  local contract_business_code
  local mutation_contract
  local started_epoch
  local finished_epoch
  local source_prefix
  local required_action
  local -A seen_actions=()
  local -a required_actions=(
    instance_audit_view
    model_version_view
    process_start
    task_complete
    instance_detail
    instance_terminate_denied
  )

  IFS= read -r header < "$file" || gate_die "$label is empty"
  header="${header%$'\r'}"
  [[ "$header" == $'started_at\tfinished_at\trole\taction\tmethod\tobject_ref\trequest_id\texpected_transport_status\tactual_transport_status\texpected_business_code\tactual_business_code\tstate_before\tstate_after\tdb_before_sha256\tdb_after_sha256\taudit_ref\tunexpected_side_effect_count\tsource_file\tsource_sha256' ]] \
    || gate_die "$label header is invalid"
  source_prefix="$(basename -- "$file" .tsv)-sources/"
  while IFS=$'\t' read -r \
    started_at finished_at role action method object_ref request_id \
    expected_transport_status actual_transport_status \
    expected_business_code actual_business_code state_before state_after \
    db_before_sha256 db_after_sha256 audit_ref unexpected_side_effect_count \
    source_file source_sha256 extra; do
    source_sha256="${source_sha256%$'\r'}"
    [[ -n "$started_at" && -n "$finished_at" && -n "$role" && -n "$action" \
       && -n "$method" && -n "$object_ref" && -n "$request_id" \
       && -n "$state_before" && -n "$state_after" && -n "$audit_ref" \
       && -n "$source_file" && -n "$source_sha256" && -z "${extra:-}" ]] \
      || gate_die "$label contains an invalid row"
    case "$action" in
      instance_audit_view)
        expected_role='workflow_admin'; expected_method='GET'
        contract_transport_status='200'; contract_business_code='200'; mutation_contract='readonly'
        ;;
      model_version_view)
        expected_role='workflow_designer'; expected_method='GET'
        contract_transport_status='200'; contract_business_code='200'; mutation_contract='readonly'
        ;;
      process_start)
        expected_role='workflow_starter'; expected_method='POST'
        contract_transport_status='200'; contract_business_code='200'; mutation_contract='mutation'
        ;;
      task_complete)
        expected_role='workflow_approver'; expected_method='POST'
        contract_transport_status='200'; contract_business_code='200'; mutation_contract='mutation'
        ;;
      instance_detail)
        expected_role='workflow_auditor'; expected_method='GET'
        contract_transport_status='200'; contract_business_code='200'; mutation_contract='readonly'
        ;;
      instance_terminate_denied)
        expected_role='workflow_auditor'; expected_method='POST'
        contract_transport_status='200'; contract_business_code='403'; mutation_contract='readonly'
        ;;
      *) gate_die "$label contains an unknown business action" ;;
    esac
    [[ -z "${seen_actions[$action]+present}" ]] \
      || gate_die "$label contains a duplicate business action"
    [[ "$role" == "$expected_role" && "$method" == "$expected_method" \
       && "$expected_transport_status" == "$contract_transport_status" \
       && "$actual_transport_status" == "$contract_transport_status" \
       && "$expected_business_code" == "$contract_business_code" \
       && "$actual_business_code" == "$contract_business_code" ]] \
      || gate_die "$label violates an action role, method, transport, or business-code contract"
    gate_validate_timestamp "$started_at" "$label action start"
    gate_validate_timestamp "$finished_at" "$label action finish"
    started_epoch="$(date -d "$started_at" '+%s')"
    finished_epoch="$(date -d "$finished_at" '+%s')"
    [[ "$finished_epoch" -ge "$started_epoch" \
       && $((finished_epoch - started_epoch)) -le 900 ]] \
      || gate_die "$label action time window is invalid"
    [[ "$object_ref" =~ ^[a-z_]+:[A-Za-z0-9._-]+$ \
       && "$request_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ \
       && "$state_before" =~ ^[A-Z][A-Z0-9_]*$ \
       && "$state_after" =~ ^[A-Z][A-Z0-9_]*$ \
       && "$db_before_sha256" =~ ^[0-9a-f]{64}$ \
       && "$db_after_sha256" =~ ^[0-9a-f]{64}$ \
       && "$audit_ref" =~ ^(sys_oper_log|security_log|trace):[A-Za-z0-9._-]+$ \
       && "$unexpected_side_effect_count" == '0' ]] \
      || gate_die "$label contains an invalid object, state, audit, or side-effect assertion"
    if [[ "$mutation_contract" == 'mutation' ]]; then
      [[ "$state_before" != "$state_after" \
         && "$db_before_sha256" != "$db_after_sha256" ]] \
        || gate_die "$label mutating action has no persisted state transition"
    else
      [[ "$state_before" == "$state_after" \
         && "$db_before_sha256" == "$db_after_sha256" ]] \
        || gate_die "$label read or denied action produced a side effect"
    fi
    [[ "$source_file" == "$source_prefix$action.txt" ]] \
      || gate_die "$label action source path is not the frozen path"
    gate_require_manifest_source "$evidence_dir" "$source_file" "$source_sha256" \
      "$label action $action"
    seen_actions["$action"]=1
  done < <(tail -n +2 "$file")

  for required_action in "${required_actions[@]}"; do
    [[ -n "${seen_actions[$required_action]+present}" ]] \
      || gate_die "$label is missing required action $required_action"
  done
}

#
# 校验双人签字 TSV，强制执行人与复核人为不同人员、明确签署 PASS 且时间不在未来。
# 参数：$1（字符串）签字 TSV 文件；$2（字符串）证据名称；$3（可选整数）最早签字 epoch。
# 返回：0 表示双人签字格式和职责分离通过；异常时终止门禁。
#
gate_validate_signoff() {
  local file="$1"
  local label="$2"
  local not_before_epoch="${3:-0}"
  local header=''
  local role
  local name
  local signed_at
  local result
  local extra
  local operator_name=''
  local reviewer_name=''
  local signed_epoch
  local current_epoch
  local -A seen_roles=()

  current_epoch="$(date '+%s')" \
    || gate_die "$label cannot read the current time"

  IFS= read -r header < "$file" || gate_die "$label is empty"
  header="${header%$'\r'}"
  [[ "$header" == $'role\tname\tsigned_at\tresult' ]] \
    || gate_die "$label header is invalid"
  while IFS=$'\t' read -r role name signed_at result extra; do
    result="${result%$'\r'}"
    [[ -n "$role" && -n "$name" && -z "${extra:-}" ]] \
      || gate_die "$label contains an invalid row"
    [[ "$name" != *'<'* && "$name" != *'>'* ]] \
      || gate_die "$label contains a placeholder name"
    gate_validate_timestamp "$signed_at" "$label signoff"
    signed_epoch="$(date -d "$signed_at" '+%s')"
    [[ "$signed_epoch" -ge "$not_before_epoch" ]] \
      || gate_die "$label contains a signature created before evidence collection finished"
    [[ "$signed_epoch" -le "$current_epoch" ]] \
      || gate_die "$label contains a signature created in the future"
    [[ "$result" == 'PASS' ]] || gate_die "$label contains a non-PASS signature"
    [[ -z "${seen_roles[$role]+present}" ]] \
      || gate_die "$label contains a duplicate signoff role"
    case "$role" in
      operator) operator_name="$name" ;;
      reviewer) reviewer_name="$name" ;;
      *) gate_die "$label contains an unknown signoff role" ;;
    esac
    seen_roles["$role"]=1
  done < <(tail -n +2 "$file")

  [[ -n "$operator_name" && -n "$reviewer_name" ]] \
    || gate_die "$label requires both operator and reviewer"
  [[ "$operator_name" != "$reviewer_name" ]] \
    || gate_die "$label operator and reviewer must be different people"
}

#
# 校验 24/72 小时观察表，按原始计数重新计算七类指标，并绑定本次生产切换时间。
# 参数：$1（字符串）观察 TSV；$2（字符串）证据名称；$3（整数）窗口小时；
#       $4（字符串）证据根目录；$5（整数）期望窗口开始 epoch。
# 返回：0 表示窗口、公式、阈值、来源与全部指标均通过；同时记录窗口结束 epoch。
#
gate_validate_observation() {
  local file="$1"
  local label="$2"
  local expected_hours="$3"
  local evidence_dir="$4"
  local expected_window_start_epoch="$5"
  local header=''
  local window_started_at
  local window_ended_at
  local observed_at
  local metric
  local numerator
  local denominator
  local calculated_value
  local threshold
  local source_file
  local source_sha256
  local extra
  local window_start_epoch
  local window_end_epoch
  local observed_epoch
  local expected_duration
  local current_epoch
  local frozen_window_start=''
  local frozen_window_end=''
  local expected_calculated
  local source_prefix
  local required_metric
  local -A seen_metrics=()
  local -a required_metrics=(
    http_success_rate
    http_p95
    flowable_deadletter
    mysql_lock_wait
    redis_latency
    attachment_free_bytes
    business_smoke
  )

  IFS= read -r header < "$file" || gate_die "$label is empty"
  header="${header%$'\r'}"
  [[ "$header" == $'window_started_at\twindow_ended_at\tobserved_at\tmetric\tnumerator\tdenominator\tcalculated_value\tthreshold\tsource_file\tsource_sha256' ]] \
    || gate_die "$label header is invalid"
  [[ "$expected_hours" == '24' || "$expected_hours" == '72' ]] \
    || gate_die "$label expected window is unsupported"
  expected_duration=$((expected_hours * 3600))
  current_epoch="$(date '+%s')" \
    || gate_die "$label cannot read the current time"
  source_prefix="$(basename -- "$file" .tsv)-sources/"
  while IFS=$'\t' read -r window_started_at window_ended_at observed_at metric \
    numerator denominator calculated_value threshold source_file source_sha256 extra; do
    source_sha256="${source_sha256%$'\r'}"
    gate_validate_timestamp "$window_started_at" "$label window start"
    gate_validate_timestamp "$window_ended_at" "$label window end"
    gate_validate_timestamp "$observed_at" "$label observation"
    [[ -n "$metric" && -n "$numerator" && -n "$denominator" \
       && -n "$calculated_value" && -n "$threshold" && -n "$source_file" \
       && -n "$source_sha256" && -z "${extra:-}" ]] \
      || gate_die "$label contains an incomplete observation row"
    [[ "$numerator" =~ ^[0-9]+([.][0-9]+)?$ \
       && "$denominator" =~ ^[0-9]+([.][0-9]+)?$ \
       && "$calculated_value" =~ ^[0-9]+[.][0-9]{3}$ \
       && "$threshold" =~ ^[0-9]+[.][0-9]{3}$ ]] \
      || gate_die "$label contains a non-numeric metric value"
    window_start_epoch="$(date -d "$window_started_at" '+%s')"
    window_end_epoch="$(date -d "$window_ended_at" '+%s')"
    observed_epoch="$(date -d "$observed_at" '+%s')"
    [[ $((window_end_epoch - window_start_epoch)) -eq "$expected_duration" \
       && "$observed_epoch" -eq "$window_end_epoch" ]] \
      || gate_die "$label does not cover the exact approved observation window"
    [[ "$window_end_epoch" -le "$current_epoch" ]] \
      || gate_die "$label window has not finished at the current time"
    [[ "$window_start_epoch" -eq "$expected_window_start_epoch" ]] \
      || gate_die "$label does not start at the approved production switch"
    if [[ -z "$frozen_window_start" ]]; then
      frozen_window_start="$window_started_at"
      frozen_window_end="$window_ended_at"
    else
      [[ "$window_started_at" == "$frozen_window_start" \
         && "$window_ended_at" == "$frozen_window_end" ]] \
        || gate_die "$label mixes different observation windows"
    fi
    [[ -z "${seen_metrics[$metric]+present}" ]] \
      || gate_die "$label contains a duplicate metric"
    case "$metric" in
      http_success_rate)
        [[ "$numerator" =~ ^[0-9]+$ && "$denominator" =~ ^[0-9]+$ \
           && "$denominator" -ge 100 && "$numerator" -le "$denominator" \
           && "$threshold" == '99.900' ]] \
          || gate_die "$label HTTP success-rate inputs or threshold are invalid"
        expected_calculated="$(awk -v a="$numerator" -v b="$denominator" \
          'BEGIN { printf "%.3f", (a / b) * 100 }')"
        [[ "$calculated_value" == "$expected_calculated" ]] \
          || gate_die "$label HTTP success rate is not calculated from raw counts"
        awk -v value="$calculated_value" 'BEGIN { exit(value >= 99.900 ? 0 : 1) }' \
          || gate_die "$label HTTP success rate violates its threshold"
        ;;
      http_p95)
        [[ "$denominator" =~ ^[0-9]+$ && "$denominator" -ge 100 \
           && "$threshold" == '500.000' ]] \
          || gate_die "$label HTTP p95 sample count or threshold is invalid"
        expected_calculated="$(awk -v value="$numerator" 'BEGIN { printf "%.3f", value }')"
        [[ "$calculated_value" == "$expected_calculated" ]] \
          || gate_die "$label HTTP p95 is not calculated from its raw value"
        awk -v value="$calculated_value" 'BEGIN { exit(value <= 500.000 ? 0 : 1) }' \
          || gate_die "$label HTTP p95 violates its threshold"
        ;;
      flowable_deadletter)
        [[ "$numerator" =~ ^[0-9]+$ && "$denominator" =~ ^[0-9]+$ \
           && "$denominator" -ge 1 && "$threshold" == '0.000' ]] \
          || gate_die "$label deadletter inputs or threshold are invalid"
        expected_calculated="$(awk -v value="$numerator" 'BEGIN { printf "%.3f", value }')"
        [[ "$calculated_value" == "$expected_calculated" && "$calculated_value" == '0.000' ]] \
          || gate_die "$label deadletter count violates its threshold"
        ;;
      mysql_lock_wait)
        [[ "$denominator" =~ ^[0-9]+$ && "$denominator" -ge 1 \
           && "$threshold" == '1.000' ]] \
          || gate_die "$label MySQL lock-wait inputs or threshold are invalid"
        expected_calculated="$(awk -v value="$numerator" 'BEGIN { printf "%.3f", value }')"
        [[ "$calculated_value" == "$expected_calculated" ]] \
          || gate_die "$label MySQL lock-wait value is not calculated from raw data"
        awk -v value="$calculated_value" 'BEGIN { exit(value <= 1.000 ? 0 : 1) }' \
          || gate_die "$label MySQL lock wait violates its threshold"
        ;;
      redis_latency)
        [[ "$denominator" =~ ^[0-9]+$ && "$denominator" -ge 100 \
           && "$threshold" == '20.000' ]] \
          || gate_die "$label Redis latency sample count or threshold is invalid"
        expected_calculated="$(awk -v value="$numerator" 'BEGIN { printf "%.3f", value }')"
        [[ "$calculated_value" == "$expected_calculated" ]] \
          || gate_die "$label Redis latency is not calculated from raw data"
        awk -v value="$calculated_value" 'BEGIN { exit(value <= 20.000 ? 0 : 1) }' \
          || gate_die "$label Redis latency violates its threshold"
        ;;
      attachment_free_bytes)
        [[ "$denominator" =~ ^[0-9]+$ && "$denominator" -ge 1 \
           && "$threshold" == '1073741824.000' ]] \
          || gate_die "$label attachment capacity inputs or threshold are invalid"
        expected_calculated="$(awk -v value="$numerator" 'BEGIN { printf "%.3f", value }')"
        [[ "$calculated_value" == "$expected_calculated" ]] \
          || gate_die "$label attachment free bytes are not calculated from raw data"
        awk -v value="$calculated_value" 'BEGIN { exit(value >= 1073741824.000 ? 0 : 1) }' \
          || gate_die "$label attachment free bytes violate the production minimum"
        ;;
      business_smoke)
        [[ "$numerator" == '6' && "$denominator" == '6' \
           && "$calculated_value" == '100.000' && "$threshold" == '100.000' ]] \
          || gate_die "$label business smoke does not cover every required action"
        ;;
      *) gate_die "$label contains an unknown observation metric" ;;
    esac
    [[ "$source_file" == "$source_prefix$metric.txt" ]] \
      || gate_die "$label metric source path is not the frozen path"
    gate_require_manifest_source "$evidence_dir" "$source_file" "$source_sha256" \
      "$label metric $metric"
    seen_metrics["$metric"]=1
  done < <(tail -n +2 "$file")

  for required_metric in "${required_metrics[@]}"; do
    [[ -n "${seen_metrics[$required_metric]+present}" ]] \
      || gate_die "$label is missing metric $required_metric"
  done
  GATE_OBSERVATION_WINDOW_END_EPOCH="$(date -d "$frozen_window_end" '+%s')"
}

#
# 对证据目录执行与发布包相同的二进制敏感扫描；只报告目录级错误，不输出匹配正文。
# 参数：$1（字符串）证据目录；$2（字符串）目录业务名称。
# 返回：0 表示未发现凭据文件、认证头、Cookie 或非空敏感赋值；异常时终止门禁。
#
gate_validate_evidence_secrets() {
  local evidence_dir="$1"
  local label="$2"

  gate_validate_sensitive_content "$evidence_dir" "$label"
}

#
# 按阶段校验证据文件集合与关键结果，未达到对应阶段的目录不能通过更高阶段门禁。
# 参数：$1（字符串）证据目录；$2（字符串）阶段 profile；$3（字符串）发布 ID；
#       $4（字符串）发布清单 SHA-256；$5（整数）发布包构建时间 epoch。
# 返回：0 表示文件集合、关键结果、双人签字和敏感扫描均通过；异常时终止门禁。
#
gate_validate_evidence_profile() {
  local evidence_dir="$1"
  local profile="$2"
  local release_id="$3"
  local release_manifest_sha256="$4"
  local release_built_at_epoch="$5"
  local required_file
  local -a required_nonempty=()
  local -a required_existing=()
  local signoff_file=''
  local signoff_not_before_epoch=0

  case "$profile" in
    fresh-install)
      required_nonempty=(
        java-version.txt mysql-version.txt redis-version.txt nginx-version.txt
        envsubst-version.txt openssl-version.txt node-version.txt npm-version.txt
        profile-filesystem.txt accounts-ignore-check.txt empty-schema-check.txt
        table-counts.tsv app-account-grants.txt backup-account-grants.txt
        backup-smoke/schema-sha256.txt backup-smoke/mysqlcheck.txt
        redis-ping.txt redis-persistence.txt redis-memory-policy.txt
        backend-sha256.txt frontend-sha256.txt release-gate-test.log
        release-preflight.txt
        mysql-connectivity.txt redis-connectivity.txt nginx-service-status.txt
        admin-bootstrap-precheck.txt admin-bootstrap-startup.log
        admin-bootstrap-state.txt service-status.txt backend-after-bootstrap.txt
        admin-real-login.txt database-verify.tsv backend-health.txt
        backend-liveness.json backend-readiness.json prometheus-scrape.txt
        nginx-health.txt flowable-jobs.tsv install-signoff.tsv
        environment-identity.tsv
      )
      required_existing=(
        git-status-before-install.txt attachment-root-symlinks.txt
        attachment-restore-diff.txt sensitive-file-list.txt
      )
      signoff_file='install-signoff.tsv'
      ;;
    rehearsal|production|production-24h|production-72h)
      required_nonempty=(
        backend-verify.log frontend-build.log release-gate-test.log
        build-tools.txt dependency-input-sha256.txt
        release-hash-check.txt
        release-preflight.txt git-commit.txt accounts-ignore-check.txt
        predeploy-hash-check.txt predeploy-services.txt
        predeploy-filesystem.txt predeploy-backend-health.txt
        predeploy-liveness.json predeploy-readiness.json predeploy-prometheus.txt
        predeploy-nginx-health.txt backup-hash-check.txt database-verify.tsv
        previous-release-hash-check.txt postdeploy-services.txt
        postdeploy-backend.log postdeploy-backend-health.txt
        postdeploy-liveness.json postdeploy-readiness.json postdeploy-prometheus.txt
        postdeploy-nginx-health.txt postdeploy-mysql-connectivity.txt
        postdeploy-redis-ping.txt business-smoke.tsv
        attachment-global-guard.txt release-signoff.tsv
        evidence-file-list.txt
      )
      required_existing=(
        git-status.txt attachment-db.tsv attachment-files.tsv
        attachment-diff.txt sensitive-file-list.txt
      )
      signoff_file='release-signoff.tsv'
      ;;
    *) gate_die "unsupported evidence profile: $profile" ;;
  esac

  if [[ "$profile" == 'rehearsal' ]]; then
      required_nonempty+=(
        rollback-source-hash-check.txt rollback-services.txt
        rollback-backend-health.txt rollback-liveness.json
        rollback-readiness.json rollback-prometheus.txt rollback-nginx-health.txt
        rollback-database-verify.tsv rollback-business-smoke.tsv
        reapply-services.txt reapply-liveness.json reapply-readiness.json
        reapply-prometheus.txt reapply-database-verify.tsv
      reapply-business-smoke.tsv rehearsal-signoff.tsv
    )
    required_existing+=(rollback-attachment-diff.txt reapply-attachment-diff.txt)
  fi
  if [[ "$profile" == 'production' || "$profile" == 'production-24h' \
     || "$profile" == 'production-72h' ]]; then
    required_nonempty+=(
      rehearsal-round-1-receipt.txt rehearsal-round-2-receipt.txt
      production-switch.tsv production-switch-source.txt
    )
  fi
  if [[ "$profile" == 'production-24h' || "$profile" == 'production-72h' ]]; then
    required_nonempty+=(observation-24h.tsv observation-24h-signoff.tsv)
  fi
  if [[ "$profile" == 'production-72h' ]]; then
    required_nonempty+=(observation-72h.tsv observation-72h-signoff.tsv)
  fi

  for required_file in "${required_nonempty[@]}"; do
    gate_require_nonempty_file "$evidence_dir" "$required_file" \
      "evidence file $required_file"
  done
  for required_file in "${required_existing[@]}"; do
    gate_require_file "$evidence_dir" "$required_file" \
      "evidence file $required_file"
  done

  gate_validate_evidence_secrets "$evidence_dir" 'evidence directory'
  [[ ! -s "$evidence_dir/sensitive-file-list.txt" ]] \
    || gate_die 'sensitive-file-list.txt must be empty'
  if [[ "$profile" == 'production' || "$profile" == 'production-24h' \
     || "$profile" == 'production-72h' ]]; then
    gate_validate_production_switch \
      "$evidence_dir/production-switch.tsv" "$evidence_dir" \
      "$release_id" "$release_manifest_sha256" "$release_built_at_epoch"
    signoff_not_before_epoch="$GATE_PRODUCTION_SWITCH_EPOCH"
  fi
  if [[ "$profile" == 'fresh-install' ]]; then
    gate_validate_environment_identity \
      "$evidence_dir/environment-identity.tsv" 'fresh-install environment identity'
    signoff_not_before_epoch="$GATE_ENVIRONMENT_FINISHED_EPOCH"
  fi
  gate_validate_signoff \
    "$evidence_dir/$signoff_file" "$signoff_file" "$signoff_not_before_epoch"
  grep -Eq '^PASS release_preflight([[:space:]]|$)' "$evidence_dir/release-preflight.txt" \
    || gate_die 'release-preflight.txt does not contain a passing receipt'

  if [[ "$profile" == 'fresh-install' ]]; then
    gate_require_exact_line "$evidence_dir/empty-schema-check.txt" \
      'empty_schema_table_count=0' 'empty schema evidence'
    gate_require_exact_line "$evidence_dir/table-counts.tsv" \
      $'111\t20\t11\t36\t44' 'table-counts.tsv'
    gate_validate_backup_restore "$evidence_dir" 'fresh-install evidence'
    gate_require_exact_line "$evidence_dir/redis-ping.txt" 'PONG' 'Redis PING evidence'
    gate_require_exact_line "$evidence_dir/mysql-connectivity.txt" '1' 'MySQL connectivity evidence'
    gate_require_exact_line "$evidence_dir/redis-connectivity.txt" 'PONG' 'Redis connectivity evidence'
    gate_validate_active_services "$evidence_dir/nginx-service-status.txt" 'Nginx service evidence'
    gate_validate_active_services "$evidence_dir/service-status.txt" 'service status evidence'
    gate_require_exact_line "$evidence_dir/admin-bootstrap-precheck.txt" 'PASS' 'admin bootstrap precheck'
    gate_require_exact_line "$evidence_dir/admin-bootstrap-state.txt" 'PASS' 'admin bootstrap state'
    gate_require_exact_line "$evidence_dir/admin-real-login.txt" 'PASS' 'admin real login evidence'
    gate_validate_database_result "$evidence_dir/database-verify.tsv" 'database verification evidence'
    gate_validate_mysql_grants \
      "$evidence_dir/app-account-grants.txt" app 'application account grants'
    gate_validate_mysql_grants \
      "$evidence_dir/backup-account-grants.txt" backup 'backup account grants'
    gate_validate_redis_persistence \
      "$evidence_dir/redis-persistence.txt" 'Redis persistence evidence'
    gate_validate_redis_memory_policy \
      "$evidence_dir/redis-memory-policy.txt" 'Redis memory policy evidence'
    gate_validate_health_response \
      "$evidence_dir/backend-liveness.json" 'backend liveness evidence'
    gate_validate_health_response \
      "$evidence_dir/backend-readiness.json" 'backend readiness evidence'
    gate_validate_prometheus_snapshot \
      "$evidence_dir/prometheus-scrape.txt" 'Prometheus scrape evidence'
    [[ ! -s "$evidence_dir/attachment-root-symlinks.txt" ]] \
      || gate_die 'attachment-root-symlinks.txt must be empty'
    [[ ! -s "$evidence_dir/attachment-restore-diff.txt" ]] \
      || gate_die 'attachment-restore-diff.txt must be empty'
  else
    gate_validate_active_services "$evidence_dir/predeploy-services.txt" 'predeploy service evidence'
    gate_validate_active_services "$evidence_dir/postdeploy-services.txt" 'postdeploy service evidence'
    gate_require_exact_line "$evidence_dir/postdeploy-mysql-connectivity.txt" '1' \
      'postdeploy MySQL connectivity evidence'
    gate_require_exact_line "$evidence_dir/postdeploy-redis-ping.txt" 'PONG' \
      'postdeploy Redis connectivity evidence'
    gate_require_exact_line "$evidence_dir/attachment-global-guard.txt" '1' \
      'attachment global quota guard evidence'
    [[ ! -s "$evidence_dir/attachment-diff.txt" ]] \
      || gate_die 'attachment-diff.txt must be empty'
    gate_validate_database_result "$evidence_dir/database-verify.tsv" 'database verification evidence'
    gate_validate_business_smoke \
      "$evidence_dir/business-smoke.tsv" 'business-smoke.tsv' "$evidence_dir"
    gate_validate_health_response \
      "$evidence_dir/predeploy-liveness.json" 'predeploy liveness evidence'
    gate_validate_health_response \
      "$evidence_dir/predeploy-readiness.json" 'predeploy readiness evidence'
    gate_validate_prometheus_snapshot \
      "$evidence_dir/predeploy-prometheus.txt" 'predeploy Prometheus evidence'
    gate_validate_health_response \
      "$evidence_dir/postdeploy-liveness.json" 'postdeploy liveness evidence'
    gate_validate_health_response \
      "$evidence_dir/postdeploy-readiness.json" 'postdeploy readiness evidence'
    gate_validate_prometheus_snapshot \
      "$evidence_dir/postdeploy-prometheus.txt" 'postdeploy Prometheus evidence'
  fi

  if [[ "$profile" == 'rehearsal' ]]; then
    # 每轮彩排必须先在本轮目录内完成独立全新安装，不能复用根目录的升级结果冒充。
    gate_require_nonempty_file \
      "$evidence_dir/fresh-install" 'EVIDENCE-SHA256SUMS' \
      'fresh-install evidence manifest'
    # 内层清单校验放在子进程中，避免覆盖父级清单映射，后续仍可核验根目录业务来源。
    (
      gate_validate_manifest \
        "$evidence_dir/fresh-install" 'EVIDENCE-SHA256SUMS' \
        'fresh-install evidence directory'
      gate_revalidate_manifest \
        "$evidence_dir/fresh-install" 'EVIDENCE-SHA256SUMS' \
        'fresh-install evidence directory'
    )
    gate_validate_evidence_profile \
      "$evidence_dir/fresh-install" fresh-install \
      "$release_id" "$release_manifest_sha256" "$release_built_at_epoch"
    gate_validate_signoff \
      "$evidence_dir/release-signoff.tsv" \
      'release-signoff.tsv' "$GATE_ENVIRONMENT_FINISHED_EPOCH"
    gate_validate_active_services "$evidence_dir/rollback-services.txt" 'rollback service evidence'
    gate_validate_active_services "$evidence_dir/reapply-services.txt" 'reapply service evidence'
    gate_validate_database_result "$evidence_dir/rollback-database-verify.tsv" \
      'rollback database verification evidence'
    gate_validate_database_result "$evidence_dir/reapply-database-verify.tsv" \
      'reapply database verification evidence'
    gate_validate_business_smoke "$evidence_dir/rollback-business-smoke.tsv" \
      'rollback-business-smoke.tsv' "$evidence_dir"
    gate_validate_business_smoke "$evidence_dir/reapply-business-smoke.tsv" \
      'reapply-business-smoke.tsv' "$evidence_dir"
    gate_validate_health_response "$evidence_dir/rollback-liveness.json" \
      'rollback liveness evidence'
    gate_validate_health_response "$evidence_dir/rollback-readiness.json" \
      'rollback readiness evidence'
    gate_validate_prometheus_snapshot "$evidence_dir/rollback-prometheus.txt" \
      'rollback Prometheus evidence'
    gate_validate_health_response "$evidence_dir/reapply-liveness.json" \
      'reapply liveness evidence'
    gate_validate_health_response "$evidence_dir/reapply-readiness.json" \
      'reapply readiness evidence'
    gate_validate_prometheus_snapshot "$evidence_dir/reapply-prometheus.txt" \
      'reapply Prometheus evidence'
    [[ ! -s "$evidence_dir/rollback-attachment-diff.txt" \
       && ! -s "$evidence_dir/reapply-attachment-diff.txt" ]] \
      || gate_die 'rehearsal attachment diff evidence must be empty'
    gate_validate_signoff \
      "$evidence_dir/rehearsal-signoff.tsv" \
      'rehearsal-signoff.tsv' "$GATE_ENVIRONMENT_FINISHED_EPOCH"
  fi
  if [[ "$profile" == 'production-24h' || "$profile" == 'production-72h' ]]; then
    gate_validate_observation "$evidence_dir/observation-24h.tsv" \
      '24-hour observation evidence' 24 "$evidence_dir" "$GATE_PRODUCTION_SWITCH_EPOCH"
    gate_validate_signoff "$evidence_dir/observation-24h-signoff.tsv" \
      'observation-24h-signoff.tsv' "$GATE_OBSERVATION_WINDOW_END_EPOCH"
  fi
  if [[ "$profile" == 'production-72h' ]]; then
    gate_validate_observation "$evidence_dir/observation-72h.tsv" \
      '72-hour observation evidence' 72 "$evidence_dir" "$GATE_PRODUCTION_SWITCH_EPOCH"
    gate_validate_signoff "$evidence_dir/observation-72h-signoff.tsv" \
      'observation-72h-signoff.tsv' "$GATE_OBSERVATION_WINDOW_END_EPOCH"
  fi
}

#
# 重新执行两轮彩排证据门禁，并把物理、环境、时序、人员、结果和发布包摘要绑定到生产归档。
# 参数：$1（字符串）生产证据目录；$2/$3（字符串）两轮彩排证据目录；
#       $4/$5（字符串）当前发布目录/ID；$6/$7（字符串）上一发布目录/ID；
#       $8（字符串）生产门禁重新计算出的发布预检回执；
#       $9/$10（字符串）当前批准 commit/manifest；$11/$12（字符串）上一版本批准 commit/manifest。
# 返回：0 表示两轮彩排相互独立、顺序执行、结果一致且绑定同一发布包关系；异常时终止门禁。
#
gate_validate_rehearsal_pair() {
  local production_evidence_dir="$1"
  local rehearsal_one_evidence_dir="$2"
  local rehearsal_two_evidence_dir="$3"
  local release_dir="$4"
  local release_id="$5"
  local previous_release_dir="$6"
  local previous_release_id="$7"
  local preflight_receipt="$8"
  local approved_git_commit="$9"
  local approved_manifest_sha256="${10}"
  local approved_previous_git_commit="${11}"
  local approved_previous_manifest_sha256="${12}"
  local canonical_rehearsal_one_dir
  local canonical_rehearsal_two_dir
  local production_identity
  local rehearsal_one_identity
  local rehearsal_two_identity
  local rehearsal_one_receipt
  local rehearsal_two_receipt
  local rehearsal_one_run_id
  local rehearsal_one_environment_id
  local rehearsal_one_started_at
  local rehearsal_one_finished_at
  local rehearsal_one_database_identity
  local rehearsal_one_redis_identity
  local rehearsal_one_attachment_identity
  local rehearsal_two_run_id
  local rehearsal_two_environment_id
  local rehearsal_two_started_at
  local rehearsal_two_finished_at
  local rehearsal_two_database_identity
  local rehearsal_two_redis_identity
  local rehearsal_two_attachment_identity
  local ignored_extra
  local rehearsal_one_install_operator
  local rehearsal_one_install_reviewer
  local rehearsal_two_install_operator
  local rehearsal_two_install_reviewer
  local rehearsal_one_table_counts_sha256
  local rehearsal_two_table_counts_sha256
  local rehearsal_one_database_sha256
  local rehearsal_two_database_sha256
  local -a release_arguments=(
    --release-dir "$release_dir"
    --release-id "$release_id"
    --approved-git-commit "$approved_git_commit"
    --approved-manifest-sha256 "$approved_manifest_sha256"
  )

  canonical_rehearsal_one_dir="$(
    gate_canonical_directory "$rehearsal_one_evidence_dir" \
      'first rehearsal evidence directory'
  )"
  canonical_rehearsal_two_dir="$(
    gate_canonical_directory "$rehearsal_two_evidence_dir" \
      'second rehearsal evidence directory'
  )"

  # 路径比较和设备/inode 比较同时执行，避免同一物理归档通过别名重复计数。
  production_identity="$(stat -c '%d:%i' -- "$production_evidence_dir")"
  rehearsal_one_identity="$(stat -c '%d:%i' -- "$canonical_rehearsal_one_dir")"
  rehearsal_two_identity="$(stat -c '%d:%i' -- "$canonical_rehearsal_two_dir")"
  [[ "$canonical_rehearsal_one_dir" != "$canonical_rehearsal_two_dir" \
     && "$rehearsal_one_identity" != "$rehearsal_two_identity" ]] \
    || gate_die 'rehearsal evidence directories must be physically different'
  [[ "$canonical_rehearsal_one_dir" != "$production_evidence_dir" \
     && "$canonical_rehearsal_two_dir" != "$production_evidence_dir" \
     && "$rehearsal_one_identity" != "$production_identity" \
     && "$rehearsal_two_identity" != "$production_identity" ]] \
    || gate_die 'rehearsal evidence directories must differ from production evidence'

  if [[ "$previous_release_id" != 'NONE' || -n "$previous_release_dir" ]]; then
    release_arguments+=(
      --previous-release-dir "$previous_release_dir"
      --previous-release-id "$previous_release_id"
      --approved-previous-git-commit "$approved_previous_git_commit"
      --approved-previous-manifest-sha256 "$approved_previous_manifest_sha256"
    )
  fi

  # 不能信任归档中自报的 PASS；必须针对本次同一发布/回滚包现场重算两轮正式回执。
  rehearsal_one_receipt="$(
    gate_run_evidence \
      --evidence-dir "$canonical_rehearsal_one_dir" \
      --profile rehearsal \
      "${release_arguments[@]}"
  )"
  rehearsal_two_receipt="$(
    gate_run_evidence \
      --evidence-dir "$canonical_rehearsal_two_dir" \
      --profile rehearsal \
      "${release_arguments[@]}"
  )"

  # 逐项读取已由 rehearsal 子门禁验证的环境身份，生产门禁再证明两轮没有复用基础设施。
  IFS=$'\t' read -r \
    rehearsal_one_run_id rehearsal_one_environment_id rehearsal_one_started_at \
    rehearsal_one_finished_at rehearsal_one_database_identity \
    rehearsal_one_redis_identity rehearsal_one_attachment_identity ignored_extra \
    < <(tail -n +2 \
      "$canonical_rehearsal_one_dir/fresh-install/environment-identity.tsv")
  IFS=$'\t' read -r \
    rehearsal_two_run_id rehearsal_two_environment_id rehearsal_two_started_at \
    rehearsal_two_finished_at rehearsal_two_database_identity \
    rehearsal_two_redis_identity rehearsal_two_attachment_identity ignored_extra \
    < <(tail -n +2 \
      "$canonical_rehearsal_two_dir/fresh-install/environment-identity.tsv")
  rehearsal_one_attachment_identity="${rehearsal_one_attachment_identity%$'\r'}"
  rehearsal_two_attachment_identity="${rehearsal_two_attachment_identity%$'\r'}"
  [[ "$rehearsal_one_run_id" != "$rehearsal_two_run_id" \
     && "$rehearsal_one_environment_id" != "$rehearsal_two_environment_id" \
     && "$rehearsal_one_database_identity" != "$rehearsal_two_database_identity" \
     && "$rehearsal_one_redis_identity" != "$rehearsal_two_redis_identity" \
     && "$rehearsal_one_attachment_identity" != "$rehearsal_two_attachment_identity" ]] \
    || gate_die 'rehearsal runs must use different run and infrastructure identities'
  [[ "$(date -d "$rehearsal_two_started_at" '+%s')" \
     -gt "$(date -d "$rehearsal_one_finished_at" '+%s')" ]] \
    || gate_die 'second rehearsal must start after the first rehearsal finishes'

  rehearsal_one_install_operator="$(awk -F '\t' \
    '$1 == "operator" { print $2 }' \
    "$canonical_rehearsal_one_dir/fresh-install/install-signoff.tsv")"
  rehearsal_one_install_reviewer="$(awk -F '\t' \
    '$1 == "reviewer" { print $2 }' \
    "$canonical_rehearsal_one_dir/fresh-install/install-signoff.tsv")"
  rehearsal_two_install_operator="$(awk -F '\t' \
    '$1 == "operator" { print $2 }' \
    "$canonical_rehearsal_two_dir/fresh-install/install-signoff.tsv")"
  rehearsal_two_install_reviewer="$(awk -F '\t' \
    '$1 == "reviewer" { print $2 }' \
    "$canonical_rehearsal_two_dir/fresh-install/install-signoff.tsv")"
  [[ "$rehearsal_one_install_operator" != "$rehearsal_two_install_operator" \
     && "$rehearsal_one_install_operator" != "$rehearsal_two_install_reviewer" \
     && "$rehearsal_one_install_reviewer" != "$rehearsal_two_install_operator" \
     && "$rehearsal_one_install_reviewer" != "$rehearsal_two_install_reviewer" ]] \
    || gate_die 'rehearsal install operators and reviewers must be independent between rounds'

  # 固定结果必须逐字一致；环境身份必须不同，但不能以环境差异解释表数或数据库验收差异。
  rehearsal_one_table_counts_sha256="$(sha256sum -- \
    "$canonical_rehearsal_one_dir/fresh-install/table-counts.tsv")"
  rehearsal_one_table_counts_sha256="${rehearsal_one_table_counts_sha256%% *}"
  rehearsal_two_table_counts_sha256="$(sha256sum -- \
    "$canonical_rehearsal_two_dir/fresh-install/table-counts.tsv")"
  rehearsal_two_table_counts_sha256="${rehearsal_two_table_counts_sha256%% *}"
  rehearsal_one_database_sha256="$(sha256sum -- \
    "$canonical_rehearsal_one_dir/fresh-install/database-verify.tsv")"
  rehearsal_one_database_sha256="${rehearsal_one_database_sha256%% *}"
  rehearsal_two_database_sha256="$(sha256sum -- \
    "$canonical_rehearsal_two_dir/fresh-install/database-verify.tsv")"
  rehearsal_two_database_sha256="${rehearsal_two_database_sha256%% *}"
  [[ "$rehearsal_one_table_counts_sha256" == "$rehearsal_two_table_counts_sha256" \
     && "$rehearsal_one_database_sha256" == "$rehearsal_two_database_sha256" ]] \
    || gate_die 'rehearsal fresh-install table counts or database results differ'

  gate_require_exact_line \
    "$canonical_rehearsal_one_dir/release-preflight.txt" \
    "$preflight_receipt" 'first rehearsal release preflight receipt'
  gate_require_exact_line \
    "$canonical_rehearsal_two_dir/release-preflight.txt" \
    "$preflight_receipt" 'second rehearsal release preflight receipt'
  [[ "$rehearsal_one_receipt" != "$rehearsal_two_receipt" ]] \
    || gate_die 'independent rehearsal evidence manifests must differ'
  gate_require_exact_line \
    "$production_evidence_dir/rehearsal-round-1-receipt.txt" \
    "$rehearsal_one_receipt" 'first rehearsal PASS receipt'
  gate_require_exact_line \
    "$production_evidence_dir/rehearsal-round-2-receipt.txt" \
    "$rehearsal_two_receipt" 'second rehearsal PASS receipt'
}

#
# 执行发布包前置门禁，成对验证目标版本和批准的上一版本回滚源。
# 参数：$@（字符串数组）preflight 子命令参数。
# 返回：0 并输出不含敏感信息的 PASS 回执；参数错误返回 64，门禁失败返回 1。
#
gate_run_preflight() {
  local release_dir=''
  local release_id=''
  local previous_release_dir=''
  local previous_release_id='NONE'
  local approved_git_commit=''
  local approved_manifest_sha256=''
  local approved_previous_git_commit='NONE'
  local approved_previous_manifest_sha256='NONE'
  local current_manifest_sha256
  local previous_manifest_sha256='NONE'
  local canonical_release_dir
  local canonical_previous_release_dir
  local final_current_manifest_sha256
  local final_previous_manifest_sha256='NONE'

  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --release-dir|--release-id|--previous-release-dir|--previous-release-id|--approved-git-commit|--approved-manifest-sha256|--approved-previous-git-commit|--approved-previous-manifest-sha256)
        [[ "$#" -ge 2 ]] || { gate_usage; exit "$GATE_USAGE_EXIT"; }
        case "$1" in
          --release-dir) release_dir="$2" ;;
          --release-id) release_id="$2" ;;
          --previous-release-dir) previous_release_dir="$2" ;;
          --previous-release-id) previous_release_id="$2" ;;
          --approved-git-commit) approved_git_commit="$2" ;;
          --approved-manifest-sha256) approved_manifest_sha256="$2" ;;
          --approved-previous-git-commit) approved_previous_git_commit="$2" ;;
          --approved-previous-manifest-sha256) approved_previous_manifest_sha256="$2" ;;
        esac
        shift 2
        ;;
      *) gate_usage; exit "$GATE_USAGE_EXIT" ;;
    esac
  done

  [[ -n "$release_dir" && -n "$release_id" \
     && -n "$approved_git_commit" && -n "$approved_manifest_sha256" ]] \
    || { gate_usage; exit "$GATE_USAGE_EXIT"; }
  gate_validate_release_id "$release_id" 'release_id' false
  gate_validate_release_id "$previous_release_id" 'previous_release_id' true
  gate_validate_git_commit "$approved_git_commit" 'approved_git_commit'
  gate_validate_sha256 "$approved_manifest_sha256" 'approved_manifest_sha256'
  if [[ "$previous_release_id" == 'NONE' ]]; then
    [[ -z "$previous_release_dir" ]] \
      || gate_die 'previous release directory must be omitted when previous_release_id is NONE'
    [[ "$approved_previous_git_commit" == 'NONE' \
       && "$approved_previous_manifest_sha256" == 'NONE' ]] \
      || gate_die 'previous approval anchors must be omitted when previous_release_id is NONE'
  else
    [[ -n "$previous_release_dir" ]] \
      || gate_die 'previous release directory is required for a rollback-capable release'
    [[ "$release_id" != "$previous_release_id" ]] \
      || gate_die 'current and previous release IDs must differ'
    gate_validate_git_commit \
      "$approved_previous_git_commit" 'approved_previous_git_commit'
    gate_validate_sha256 \
      "$approved_previous_manifest_sha256" 'approved_previous_manifest_sha256'
    [[ "$approved_git_commit" != "$approved_previous_git_commit" ]] \
      || gate_die 'current and previous releases must use different approved commits'
  fi

  canonical_release_dir="$(gate_canonical_directory "$release_dir" 'release directory')"
  gate_validate_release_bundle \
    "$canonical_release_dir" "$release_id" "$previous_release_id" \
    "$approved_git_commit" current 'current release'
  current_manifest_sha256="$GATE_VALIDATED_MANIFEST_SHA256"
  [[ "$current_manifest_sha256" == "$approved_manifest_sha256" ]] \
    || gate_die 'current release manifest does not match the approved SHA-256'

  if [[ "$previous_release_id" != 'NONE' ]]; then
    canonical_previous_release_dir="$(
      gate_canonical_directory "$previous_release_dir" 'previous release directory'
    )"
    [[ "$canonical_release_dir" != "$canonical_previous_release_dir" ]] \
      || gate_die 'current and previous release directories must differ'
    [[ "$(dirname -- "$canonical_release_dir")" == \
       "$(dirname -- "$canonical_previous_release_dir")" ]] \
      || gate_die 'current and previous release directories must share one immutable release root'
    gate_validate_release_bundle \
      "$canonical_previous_release_dir" "$previous_release_id" 'ANY' \
      "$approved_previous_git_commit" rollback 'previous release'
    previous_manifest_sha256="$GATE_VALIDATED_MANIFEST_SHA256"
    [[ "$previous_manifest_sha256" == "$approved_previous_manifest_sha256" ]] \
      || gate_die 'previous release manifest does not match the approved SHA-256'
  fi

  # 在生成回执前重新完成两包全量校验，避免前序语义检查后资产或元数据发生漂移。
  gate_validate_release_bundle \
    "$canonical_release_dir" "$release_id" "$previous_release_id" \
    "$approved_git_commit" current 'current release final pass'
  final_current_manifest_sha256="$GATE_VALIDATED_MANIFEST_SHA256"
  [[ "$final_current_manifest_sha256" == "$current_manifest_sha256" ]] \
    || gate_die 'current release manifest changed between validation passes'
  if [[ "$previous_release_id" != 'NONE' ]]; then
    gate_validate_release_bundle \
      "$canonical_previous_release_dir" "$previous_release_id" 'ANY' \
      "$approved_previous_git_commit" rollback 'previous release final pass'
    final_previous_manifest_sha256="$GATE_VALIDATED_MANIFEST_SHA256"
    [[ "$final_previous_manifest_sha256" == "$previous_manifest_sha256" ]] \
      || gate_die 'previous release manifest changed between validation passes'
  fi

  printf 'PASS release_preflight release_id=%s release_git_commit=%s release_manifest_sha256=%s previous_release_id=%s previous_git_commit=%s previous_manifest_sha256=%s\n' \
    "$release_id" "$approved_git_commit" "$final_current_manifest_sha256" \
    "$previous_release_id" "$approved_previous_git_commit" "$final_previous_manifest_sha256"
}

#
# 执行证据归档门禁，验证阶段所需文件、全量摘要、关键结果和敏感信息边界。
# 参数：$@（字符串数组）evidence 子命令参数。
# 返回：0 并输出 PASS 回执；参数错误返回 64，门禁失败返回 1。
#
gate_run_evidence() {
  local evidence_dir=''
  local profile=''
  local release_dir=''
  local release_id=''
  local previous_release_dir=''
  local previous_release_id='NONE'
  local approved_git_commit=''
  local approved_manifest_sha256=''
  local approved_previous_git_commit='NONE'
  local approved_previous_manifest_sha256='NONE'
  local rehearsal_one_evidence_dir=''
  local rehearsal_two_evidence_dir=''
  local canonical_evidence_dir
  local canonical_release_dir
  local preflight_receipt
  local final_preflight_receipt
  local evidence_manifest_sha256
  local release_manifest_sha256
  local release_built_at_utc
  local release_built_at_epoch
  local -a preflight_arguments=()

  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      --evidence-dir|--profile|--release-dir|--release-id|--previous-release-dir|--previous-release-id|--approved-git-commit|--approved-manifest-sha256|--approved-previous-git-commit|--approved-previous-manifest-sha256|--rehearsal-one-evidence-dir|--rehearsal-two-evidence-dir)
        [[ "$#" -ge 2 ]] || { gate_usage; exit "$GATE_USAGE_EXIT"; }
        case "$1" in
          --evidence-dir) evidence_dir="$2" ;;
          --profile) profile="$2" ;;
          --release-dir) release_dir="$2" ;;
          --release-id) release_id="$2" ;;
          --previous-release-dir) previous_release_dir="$2" ;;
          --previous-release-id) previous_release_id="$2" ;;
          --approved-git-commit) approved_git_commit="$2" ;;
          --approved-manifest-sha256) approved_manifest_sha256="$2" ;;
          --approved-previous-git-commit) approved_previous_git_commit="$2" ;;
          --approved-previous-manifest-sha256) approved_previous_manifest_sha256="$2" ;;
          --rehearsal-one-evidence-dir) rehearsal_one_evidence_dir="$2" ;;
          --rehearsal-two-evidence-dir) rehearsal_two_evidence_dir="$2" ;;
        esac
        shift 2
        ;;
      *) gate_usage; exit "$GATE_USAGE_EXIT" ;;
    esac
  done
  [[ -n "$evidence_dir" && -n "$profile" \
     && -n "$release_dir" && -n "$release_id" \
     && -n "$approved_git_commit" && -n "$approved_manifest_sha256" ]] \
    || { gate_usage; exit "$GATE_USAGE_EXIT"; }
  case "$profile" in
    production|production-24h|production-72h)
      [[ -n "$rehearsal_one_evidence_dir" \
         && -n "$rehearsal_two_evidence_dir" ]] \
        || gate_die 'production evidence requires two rehearsal evidence directories'
      [[ "$previous_release_id" != 'NONE' && -n "$previous_release_dir" ]] \
        || gate_die 'production evidence requires a real previous release'
      ;;
    rehearsal)
      [[ -z "$rehearsal_one_evidence_dir" \
         && -z "$rehearsal_two_evidence_dir" ]] \
        || gate_die 'rehearsal evidence directory options are only valid for production profiles'
      [[ "$previous_release_id" != 'NONE' && -n "$previous_release_dir" ]] \
        || gate_die 'rehearsal evidence requires a real previous release'
      ;;
    fresh-install)
      [[ -z "$rehearsal_one_evidence_dir" \
         && -z "$rehearsal_two_evidence_dir" ]] \
        || gate_die 'rehearsal evidence directory options are only valid for production profiles'
      ;;
    *) gate_die "unsupported evidence profile: $profile" ;;
  esac

  preflight_arguments=(
    --release-dir "$release_dir"
    --release-id "$release_id"
    --approved-git-commit "$approved_git_commit"
    --approved-manifest-sha256 "$approved_manifest_sha256"
  )
  if [[ "$previous_release_id" != 'NONE' || -n "$previous_release_dir" ]]; then
    preflight_arguments+=(
      --previous-release-dir "$previous_release_dir"
      --previous-release-id "$previous_release_id"
      --approved-previous-git-commit "$approved_previous_git_commit"
      --approved-previous-manifest-sha256 "$approved_previous_manifest_sha256"
    )
  fi
  preflight_receipt="$(gate_run_preflight "${preflight_arguments[@]}")"
  canonical_release_dir="$(gate_canonical_directory "$release_dir" 'release directory')"
  release_manifest_sha256="$(sha256sum -- "$canonical_release_dir/SHA256SUMS")"
  release_manifest_sha256="${release_manifest_sha256%% *}"
  release_built_at_utc="$(
    gate_read_release_metadata_value "$canonical_release_dir" 'built_at_utc'
  )"
  release_built_at_epoch="$(date -d "$release_built_at_utc" '+%s')"
  canonical_evidence_dir="$(gate_canonical_directory "$evidence_dir" 'evidence directory')"
  gate_validate_manifest \
    "$canonical_evidence_dir" 'EVIDENCE-SHA256SUMS' 'evidence directory'
  evidence_manifest_sha256="$GATE_VALIDATED_MANIFEST_SHA256"
  gate_validate_evidence_profile \
    "$canonical_evidence_dir" "$profile" "$release_id" \
    "$release_manifest_sha256" "$release_built_at_epoch"
  gate_require_exact_line \
    "$canonical_evidence_dir/release-preflight.txt" \
    "$preflight_receipt" \
    'release preflight receipt'
  if [[ "$profile" == 'rehearsal' ]]; then
    gate_require_exact_line \
      "$canonical_evidence_dir/fresh-install/release-preflight.txt" \
      "$preflight_receipt" \
      'fresh-install release preflight receipt'
  fi
  if [[ "$profile" == 'production' || "$profile" == 'production-24h' \
     || "$profile" == 'production-72h' ]]; then
    gate_validate_rehearsal_pair \
      "$canonical_evidence_dir" \
      "$rehearsal_one_evidence_dir" "$rehearsal_two_evidence_dir" \
      "$release_dir" "$release_id" \
      "$previous_release_dir" "$previous_release_id" \
      "$preflight_receipt" \
      "$approved_git_commit" "$approved_manifest_sha256" \
      "$approved_previous_git_commit" "$approved_previous_manifest_sha256"
  fi
  gate_revalidate_manifest \
    "$canonical_evidence_dir" 'EVIDENCE-SHA256SUMS' 'evidence directory'

  # 发布包和证据先后各做第二次全量校验，且摘要必须与第一次完全相同。
  final_preflight_receipt="$(gate_run_preflight "${preflight_arguments[@]}")"
  [[ "$final_preflight_receipt" == "$preflight_receipt" ]] \
    || gate_die 'release preflight receipt changed during evidence validation'
  gate_validate_manifest \
    "$canonical_evidence_dir" 'EVIDENCE-SHA256SUMS' 'evidence directory final pass'
  [[ "$GATE_VALIDATED_MANIFEST_SHA256" == "$evidence_manifest_sha256" ]] \
    || gate_die 'evidence manifest changed between validation passes'
  gate_revalidate_manifest \
    "$canonical_evidence_dir" 'EVIDENCE-SHA256SUMS' 'evidence directory final pass'
  printf 'PASS evidence_gate profile=%s evidence_manifest_sha256=%s\n' \
    "$profile" "$evidence_manifest_sha256"
}

#
# 分派门禁子命令并统一初始化系统依赖。
# 参数：$@（字符串数组）完整命令行参数。
# 返回：透传子命令退出状态；无效用法返回 64。
#
main() {
  local command_name="${1:-}"
  [[ -n "$command_name" ]] || { gate_usage; exit "$GATE_USAGE_EXIT"; }
  shift
  gate_require_commands awk basename date dirname find grep head python3 realpath sha256sum stat tail tr wc

  case "$command_name" in
    preflight) gate_run_preflight "$@" ;;
    evidence) gate_run_evidence "$@" ;;
    *) gate_usage; exit "$GATE_USAGE_EXIT" ;;
  esac
}

main "$@"
