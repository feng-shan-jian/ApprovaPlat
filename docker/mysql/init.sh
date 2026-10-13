#!/usr/bin/env bash
set -Eeuo pipefail

# SQL_ROOT 表示容器内只读挂载的正式数据库基线根目录。
readonly SQL_ROOT="/opt/approvaplat/sql"

# Docker 与本地隔离验收只消费这一份空库清单。
while IFS= read -r relative_path || [[ -n "${relative_path}" ]]; do
  relative_path="${relative_path%$'\r'}"
  [[ -z "${relative_path}" || "${relative_path}" == \#* ]] && continue
  # sql_file 表示本轮要导入的正式 SQL；缺失时立即失败，防止产生部分初始化数据库。
  sql_file="${SQL_ROOT}/${relative_path}"
  if [[ ! -f "${sql_file}" ]]; then
    printf '数据库基线文件不存在：%s\n' "${sql_file}" >&2
    exit 1
  fi

  printf '正在执行数据库基线：%s\n' "${relative_path}"
  mysql \
    --protocol=socket \
    --user=root \
    --password="${MYSQL_ROOT_PASSWORD}" \
    --database="${MYSQL_DATABASE}" \
    --default-character-set=utf8mb4 \
    < "${sql_file}"
done < "${SQL_ROOT}/baseline-files.txt"

printf 'ApprovaPlat 数据库基线初始化完成。\n'
