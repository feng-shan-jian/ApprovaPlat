#!/usr/bin/env bash

set -euo pipefail
IFS=$'\n\t'
export LC_ALL=C

readonly GATE_USAGE_EXIT=64
# 当前开发期最终结构只支持空库安装；SMS/OSS 属于可选集成资产，不进入核心表数门禁。
readonly -a GATE_REQUIRED_FRESH_INSTALL_SQLS=(
  'flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql'
  'flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql'
  'flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql'
  'flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql'
  'flowable/business/8.0.0__workflow_model_version_guard.sql'
  'flowable/business/8.0.0__workflow_business.sql'
  'flowable/menu/8.0.0__workflow_menu.sql'
)
# 清单校验后的路径到 SHA-256 映射；后续 SQL 顺序校验只允许引用此映射中的文件。
declare -A GATE_MANIFEST_HASHES=()
# 资产相对路径到设备、inode、owner、mode、链接数、大小和 mtime 的映射，用于末尾检测元数据漂移。
declare -A GATE_MANIFEST_METADATA=()
GATE_VALIDATED_MANIFEST_SHA256=''
GATE_VALIDATED_MANIFEST_ROOT=''
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
  # Cookie 值必须符合真实凭据形态，避免把 `Admin-Token="+token` 或压缩后的 `rememberMe=L` 误判为泄漏。
  authentication_pattern='(Authorization|Proxy-Authorization)[[:space:]]*:[[:space:]]*(Basic|Bearer)[[:space:]]+[A-Za-z0-9+/._=-]{8,}|(Cookie|Set-Cookie)[[:space:]]*:[[:space:]]*[A-Za-z0-9%._~+/=-]+|(JSESSIONID|rememberMe|Admin-Token)=[A-Za-z0-9%._~+/=-]{8,}'

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
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql
        sql/flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql
        sql/flowable/business/8.0.0__workflow_model_version_guard.sql
        sql/flowable/business/8.0.0__workflow_business.sql
        sql/flowable/menu/8.0.0__workflow_menu.sql
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
    gate_validate_sql_order "$release_dir" "$label" 'release-order.txt' \
      'GATE_REQUIRED_FRESH_INSTALL_SQLS' 'core fresh-install'
    gate_validate_release_configuration "$release_dir" "$label"
  fi
  gate_validate_sensitive_content "$release_dir" "$label"
  gate_revalidate_manifest "$release_dir" 'SHA256SUMS' "$label"
}

#
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
    *) gate_usage; exit "$GATE_USAGE_EXIT" ;;
  esac
}

main "$@"
