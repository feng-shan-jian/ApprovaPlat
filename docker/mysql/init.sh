#!/usr/bin/env bash
set -Eeuo pipefail

# SQL_ROOT 表示容器内只读挂载的正式数据库基线根目录。
readonly SQL_ROOT="/opt/approvaplat/sql"

# BASELINE_FILES 表示空库必须依次执行的正式基线文件。
readonly -a BASELINE_FILES=(
  "ry_20260417.sql"
  "quartz.sql"
  "flowable/mysql/8.0.0/create/flowable.mysql.create.common.sql"
  "flowable/mysql/8.0.0/create/flowable.mysql.create.engine.sql"
  "flowable/mysql/8.0.0/create/flowable.mysql.create.history.sql"
  "flowable/mysql/8.0.0/create/flowable.mysql.create.dmn.sql"
  "flowable/business/8.0.0__workflow_model_version_guard.sql"
  "flowable/business/8.0.0__workflow_business.sql"
  "flowable/business/8.0.1__workflow_mail_config.sql"
  "flowable/business/8.0.2__workflow_model_design_draft.sql"
  "flowable/business/8.0.3__workflow_managed_connector_secret.sql"
  "flowable/business/8.0.4__workflow_attachment_upload.sql"
  "flowable/menu/8.0.0__workflow_menu.sql"
  "integration/8.0.0__sms_oss.sql"
)

for relative_path in "${BASELINE_FILES[@]}"; do
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
done

printf 'ApprovaPlat 数据库基线初始化完成。\n'
