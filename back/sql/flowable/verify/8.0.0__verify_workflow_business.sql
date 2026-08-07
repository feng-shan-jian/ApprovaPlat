-- Flowable 8 工作流业务表只读验收脚本。
-- 所有检查都应返回 PASS；本脚本不会创建、修改或删除数据。

WITH expected_dmn_tables AS (
    SELECT 'ACT_DMN_DEPLOYMENT' AS table_name
    UNION ALL SELECT 'ACT_DMN_DEPLOYMENT_RESOURCE'
    UNION ALL SELECT 'ACT_DMN_DECISION'
    UNION ALL SELECT 'ACT_DMN_HI_DECISION_EXECUTION'
),
missing_dmn_tables AS (
    SELECT e.table_name
    FROM expected_dmn_tables e
    LEFT JOIN information_schema.TABLES t
      ON t.TABLE_SCHEMA = DATABASE()
     AND UPPER(t.TABLE_NAME) = e.table_name
     AND t.ENGINE = 'InnoDB'
    WHERE t.TABLE_NAME IS NULL
)
SELECT 'flowable_dmn_table_presence' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('missing_or_invalid=', COALESCE(GROUP_CONCAT(table_name ORDER BY table_name), 'none')) AS detail
FROM missing_dmn_tables;

WITH expected_extension_tables AS (
    SELECT 'wf_bpmn_extension' AS table_name
    UNION ALL SELECT 'wf_bpmn_extension_version'
    UNION ALL SELECT 'wf_deploy_extension_snapshot'
    UNION ALL SELECT 'wf_connector_endpoint'
    UNION ALL SELECT 'wf_connector_invocation'
    UNION ALL SELECT 'wf_sql_datasource'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot'
),
missing_extension_tables AS (
    SELECT expected.table_name
    FROM expected_extension_tables expected
    LEFT JOIN information_schema.TABLES actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.ENGINE = 'InnoDB'
     AND actual.TABLE_COLLATION = 'utf8mb4_unicode_ci'
    WHERE actual.TABLE_NAME IS NULL
)
SELECT 'workflow_connector_table_presence' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('missing_or_invalid=', COALESCE(GROUP_CONCAT(table_name ORDER BY table_name), 'none')) AS detail
FROM missing_extension_tables;

WITH expected_connector_columns AS (
    SELECT 'wf_sql_datasource' AS table_name, 'datasource_key' AS column_name
    UNION ALL SELECT 'wf_sql_datasource', 'connection_type'
    UNION ALL SELECT 'wf_sql_datasource', 'allowed_tables'
    UNION ALL SELECT 'wf_sql_datasource', 'revision_no'
    UNION ALL SELECT 'wf_sql_datasource', 'checksum'
    UNION ALL SELECT 'wf_connector_invocation', 'connector_type'
    UNION ALL SELECT 'wf_connector_invocation', 'target_key'
    UNION ALL SELECT 'wf_connector_invocation', 'target_revision'
    UNION ALL SELECT 'wf_connector_invocation', 'idempotency_key'
    UNION ALL SELECT 'wf_connector_invocation', 'operation'
    UNION ALL SELECT 'wf_connector_invocation', 'target_summary'
    UNION ALL SELECT 'wf_connector_invocation', 'result_code'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'deploy_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'process_key'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'element_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'source_decision_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'decision_key'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'decision_version'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'source_deployment_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'resource_checksum'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'frozen_deployment_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'frozen_decision_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'snapshot_checksum'
),
missing_connector_columns AS (
    SELECT expected.table_name, expected.column_name
    FROM expected_connector_columns expected
    LEFT JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.COLUMN_NAME = expected.column_name
    WHERE actual.COLUMN_NAME IS NULL
)
SELECT 'workflow_connector_columns' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('missing=', COALESCE(GROUP_CONCAT(CONCAT(table_name, '.', column_name)
           ORDER BY table_name, column_name), 'none')) AS detail
FROM missing_connector_columns;

WITH expected_tables AS (
    SELECT 'wf_category' AS table_name
    UNION ALL SELECT 'wf_form'
    UNION ALL SELECT 'wf_deploy_form'
    UNION ALL SELECT 'wf_copy'
    UNION ALL SELECT 'wf_model_save_idempotency'
    UNION ALL SELECT 'wf_designer_preference'
    UNION ALL SELECT 'wf_attachment_quota_guard'
    UNION ALL SELECT 'wf_attachment'
),
actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wf_category', 'wf_form', 'wf_deploy_form', 'wf_copy',
                         'wf_model_save_idempotency', 'wf_designer_preference',
                         'wf_attachment_quota_guard',
                         'wf_attachment')
)
SELECT
    'workflow_business_tables' AS check_name,
    CASE
        WHEN COUNT(a.table_name) = 8
         AND SUM(a.ENGINE = 'InnoDB') = 8
         AND SUM(a.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 8
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'present=', COUNT(a.table_name),
        ', missing=', COALESCE(
            GROUP_CONCAT(CASE WHEN a.table_name IS NULL THEN e.table_name END
                ORDER BY e.table_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM expected_tables e
LEFT JOIN actual_tables a ON a.table_name = e.table_name;

WITH expected_columns AS (
    SELECT 'wf_category' AS table_name, 'category_id' AS column_name
    UNION ALL SELECT 'wf_category', 'code'
    UNION ALL SELECT 'wf_category', 'active_code'
    UNION ALL SELECT 'wf_category', 'del_flag'
    UNION ALL SELECT 'wf_form', 'form_id'
    UNION ALL SELECT 'wf_form', 'content'
    UNION ALL SELECT 'wf_form', 'del_flag'
    UNION ALL SELECT 'wf_deploy_form', 'deploy_id'
    UNION ALL SELECT 'wf_deploy_form', 'source_type'
    UNION ALL SELECT 'wf_deploy_form', 'form_id'
    UNION ALL SELECT 'wf_deploy_form', 'form_key'
    UNION ALL SELECT 'wf_deploy_form', 'node_key'
    UNION ALL SELECT 'wf_deploy_form', 'content'
    UNION ALL SELECT 'wf_deploy_form', 'del_flag'
    UNION ALL SELECT 'wf_copy', 'copy_id'
    UNION ALL SELECT 'wf_copy', 'copy_event_id'
    UNION ALL SELECT 'wf_copy', 'deployment_id'
    UNION ALL SELECT 'wf_copy', 'instance_id'
    UNION ALL SELECT 'wf_copy', 'task_id'
    UNION ALL SELECT 'wf_copy', 'user_id'
    UNION ALL SELECT 'wf_copy', 'originator_id'
    UNION ALL SELECT 'wf_copy', 'del_flag'
    UNION ALL SELECT 'wf_model_save_idempotency', 'request_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'user_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'source_model_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'payload_sha256'
    UNION ALL SELECT 'wf_model_save_idempotency', 'saved_model_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'create_time'
    UNION ALL SELECT 'wf_model_save_idempotency', 'complete_time'
    UNION ALL SELECT 'wf_designer_preference', 'user_id'
    UNION ALL SELECT 'wf_designer_preference', 'theme'
    UNION ALL SELECT 'wf_designer_preference', 'grid_enabled'
    UNION ALL SELECT 'wf_designer_preference', 'minimap_enabled'
    UNION ALL SELECT 'wf_designer_preference', 'lint_enabled'
    UNION ALL SELECT 'wf_designer_preference', 'token_simulation_enabled'
    UNION ALL SELECT 'wf_designer_preference', 'properties_collapsed'
    UNION ALL SELECT 'wf_designer_preference', 'create_time'
    UNION ALL SELECT 'wf_designer_preference', 'update_time'
    UNION ALL SELECT 'wf_attachment_quota_guard', 'owner_user_id'
    UNION ALL SELECT 'wf_attachment_quota_guard', 'create_time'
    UNION ALL SELECT 'wf_attachment', 'attachment_id'
    UNION ALL SELECT 'wf_attachment', 'owner_user_id'
    UNION ALL SELECT 'wf_attachment', 'field_name'
    UNION ALL SELECT 'wf_attachment', 'original_name'
    UNION ALL SELECT 'wf_attachment', 'storage_key'
    UNION ALL SELECT 'wf_attachment', 'content_type'
    UNION ALL SELECT 'wf_attachment', 'file_size'
    UNION ALL SELECT 'wf_attachment', 'sha256'
    UNION ALL SELECT 'wf_attachment', 'attachment_status'
    UNION ALL SELECT 'wf_attachment', 'expire_time'
    UNION ALL SELECT 'wf_attachment', 'process_instance_id'
    UNION ALL SELECT 'wf_attachment', 'task_id'
    UNION ALL SELECT 'wf_attachment', 'node_key'
    UNION ALL SELECT 'wf_attachment', 'bound_time'
    UNION ALL SELECT 'wf_attachment', 'storage_deleted_time'
    UNION ALL SELECT 'wf_attachment', 'cleanup_retry_count'
    UNION ALL SELECT 'wf_attachment', 'cleanup_next_retry_time'
    UNION ALL SELECT 'wf_attachment', 'cleanup_last_error_code'
),
missing_columns AS (
    SELECT e.table_name, e.column_name
    FROM expected_columns e
    LEFT JOIN information_schema.COLUMNS c
      ON c.TABLE_SCHEMA = DATABASE()
     AND c.TABLE_NAME = e.table_name
     AND c.COLUMN_NAME = e.column_name
     AND (
         e.table_name <> 'wf_model_save_idempotency'
         OR
         CASE e.column_name
             WHEN 'request_id' THEN
                 c.DATA_TYPE = 'char'
                 AND c.CHARACTER_MAXIMUM_LENGTH = 36
                 AND c.CHARACTER_SET_NAME = 'ascii'
                 AND c.COLLATION_NAME = 'ascii_bin'
                 AND c.IS_NULLABLE = 'NO'
                 AND c.COLUMN_DEFAULT IS NULL
                 AND c.EXTRA = ''
                 AND c.GENERATION_EXPRESSION = ''
             WHEN 'user_id' THEN
                 c.DATA_TYPE = 'varchar'
                 AND c.CHARACTER_MAXIMUM_LENGTH = 64
                 AND c.CHARACTER_SET_NAME = 'ascii'
                 AND c.COLLATION_NAME = 'ascii_bin'
                 AND c.IS_NULLABLE = 'NO'
                 AND c.COLUMN_DEFAULT IS NULL
                 AND c.EXTRA = ''
                 AND c.GENERATION_EXPRESSION = ''
             WHEN 'source_model_id' THEN
                 c.DATA_TYPE = 'varchar'
                 AND c.CHARACTER_MAXIMUM_LENGTH = 64
                 AND c.CHARACTER_SET_NAME = 'ascii'
                 AND c.COLLATION_NAME = 'ascii_bin'
                 AND c.IS_NULLABLE = 'NO'
                 AND c.COLUMN_DEFAULT IS NULL
                 AND c.EXTRA = ''
                 AND c.GENERATION_EXPRESSION = ''
             WHEN 'payload_sha256' THEN
                 c.DATA_TYPE = 'char'
                 AND c.CHARACTER_MAXIMUM_LENGTH = 64
                 AND c.CHARACTER_SET_NAME = 'ascii'
                 AND c.COLLATION_NAME = 'ascii_bin'
                 AND c.IS_NULLABLE = 'NO'
                 AND c.COLUMN_DEFAULT IS NULL
                 AND c.EXTRA = ''
                 AND c.GENERATION_EXPRESSION = ''
             WHEN 'saved_model_id' THEN
                 c.DATA_TYPE = 'varchar'
                 AND c.CHARACTER_MAXIMUM_LENGTH = 64
                 AND c.CHARACTER_SET_NAME = 'ascii'
                 AND c.COLLATION_NAME = 'ascii_bin'
                 AND c.IS_NULLABLE = 'YES'
                 AND c.COLUMN_DEFAULT IS NULL
                 AND c.EXTRA = ''
                 AND c.GENERATION_EXPRESSION = ''
             WHEN 'create_time' THEN
                 c.DATA_TYPE = 'datetime'
                 AND c.DATETIME_PRECISION = 3
                 AND c.IS_NULLABLE = 'NO'
                 AND LOWER(c.COLUMN_DEFAULT) = 'current_timestamp(3)'
                 AND c.GENERATION_EXPRESSION = ''
             WHEN 'complete_time' THEN
                 c.DATA_TYPE = 'datetime'
                 AND c.DATETIME_PRECISION = 3
                 AND c.IS_NULLABLE = 'YES'
                 AND c.COLUMN_DEFAULT IS NULL
                 AND c.EXTRA = ''
                 AND c.GENERATION_EXPRESSION = ''
             ELSE FALSE
         END
     )
    WHERE c.COLUMN_NAME IS NULL
)
SELECT
    'workflow_business_columns' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'missing=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', column_name)
                ORDER BY table_name, column_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM missing_columns;

WITH expected_retry_columns AS (
    SELECT 'cleanup_retry_count' AS column_name
    UNION ALL SELECT 'cleanup_next_retry_time'
    UNION ALL SELECT 'cleanup_last_error_code'
),
valid_retry_columns AS (
    SELECT COLUMN_NAME AS column_name
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_attachment'
      AND (
          (COLUMN_NAME = 'cleanup_retry_count'
           AND COLUMN_TYPE = 'int'
           AND IS_NULLABLE = 'NO'
           AND COLUMN_DEFAULT = '0'
           AND EXTRA = ''
           AND GENERATION_EXPRESSION = '')
          OR
          (COLUMN_NAME = 'cleanup_next_retry_time'
           AND DATA_TYPE = 'datetime'
           AND DATETIME_PRECISION = 3
           AND IS_NULLABLE = 'YES'
           AND COLUMN_DEFAULT IS NULL
           AND EXTRA = ''
           AND GENERATION_EXPRESSION = '')
          OR
          (COLUMN_NAME = 'cleanup_last_error_code'
           AND DATA_TYPE = 'varchar'
           AND CHARACTER_MAXIMUM_LENGTH = 64
           AND CHARACTER_SET_NAME = 'ascii'
           AND COLLATION_NAME = 'ascii_bin'
           AND IS_NULLABLE = 'YES'
           AND COLUMN_DEFAULT IS NULL
           AND EXTRA = ''
           AND GENERATION_EXPRESSION = '')
      )
),
invalid_retry_columns AS (
    SELECT e.column_name
    FROM expected_retry_columns e
    LEFT JOIN valid_retry_columns v ON v.column_name = e.column_name
    WHERE v.column_name IS NULL
)
SELECT
    'wf_attachment_cleanup_retry_columns' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'invalid=', COALESCE(
            GROUP_CONCAT(column_name ORDER BY column_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM invalid_retry_columns;

SELECT
    'wf_category_active_code' AS check_name,
    CASE
        WHEN COUNT(*) = 1
         AND MAX(UPPER(EXTRA) LIKE '%STORED GENERATED%') = 1
         AND MAX(LOWER(GENERATION_EXPRESSION) LIKE '%del_flag%') = 1
         AND MAX(LOWER(GENERATION_EXPRESSION) LIKE '%code%') = 1
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'columns=', COUNT(*),
        ', extra=', COALESCE(MAX(EXTRA), 'missing'),
        ', expression=', COALESCE(MAX(GENERATION_EXPRESSION), 'missing')
    ) AS detail
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'wf_category'
  AND COLUMN_NAME = 'active_code';

WITH actual_indexes AS (
    SELECT
        TABLE_NAME AS table_name,
        INDEX_NAME AS index_name,
        MIN(NON_UNIQUE) AS min_non_unique,
        MAX(NON_UNIQUE) AS max_non_unique,
        COUNT(*) AS index_column_count,
        GROUP_CONCAT(
            COALESCE(COLUMN_NAME, '<expression>')
            ORDER BY SEQ_IN_INDEX SEPARATOR ','
        ) AS columns_in_order,
        SUM(SUB_PART IS NULL) AS full_column_count,
        SUM(COLLATION = 'A') AS ascending_column_count,
        SUM(INDEX_TYPE = 'BTREE') AS btree_column_count,
        SUM(IS_VISIBLE = 'YES') AS visible_column_count
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wf_category', 'wf_form', 'wf_deploy_form', 'wf_copy',
                         'wf_model_save_idempotency', 'wf_designer_preference',
                         'wf_attachment_quota_guard',
                         'wf_attachment')
    GROUP BY TABLE_NAME, INDEX_NAME
),
expected_indexes AS (
    SELECT 'wf_category' AS table_name, 'PRIMARY' AS index_name, 0 AS non_unique,
           'category_id' AS columns_in_order
    UNION ALL SELECT 'wf_category', 'uk_wf_category_active_code', 0, 'active_code'
    UNION ALL SELECT 'wf_form', 'PRIMARY', 0, 'form_id'
    UNION ALL SELECT 'wf_form', 'idx_wf_form_name', 1, 'form_name'
    UNION ALL SELECT 'wf_deploy_form', 'PRIMARY', 0, 'deploy_id,form_key,node_key'
    UNION ALL SELECT 'wf_deploy_form', 'idx_wf_deploy_form_form_id', 1, 'form_id'
    UNION ALL SELECT 'wf_copy', 'PRIMARY', 0, 'copy_id'
    UNION ALL SELECT 'wf_copy', 'uk_wf_copy_event_user', 0, 'copy_event_id,user_id'
    UNION ALL SELECT 'wf_copy', 'idx_wf_copy_user_status_time', 1, 'user_id,del_flag,create_time'
    UNION ALL SELECT 'wf_copy', 'idx_wf_copy_instance', 1, 'instance_id,del_flag'
    UNION ALL SELECT 'wf_copy', 'idx_wf_copy_task', 1, 'task_id,del_flag'
    UNION ALL SELECT 'wf_copy', 'idx_wf_copy_deployment', 1, 'deployment_id,del_flag'
    UNION ALL SELECT 'wf_model_save_idempotency', 'PRIMARY', 0, 'request_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'idx_wf_model_save_user_time', 1,
                     'user_id,create_time'
    UNION ALL SELECT 'wf_model_save_idempotency', 'idx_wf_model_save_source_time', 1,
                     'source_model_id,create_time'
    UNION ALL SELECT 'wf_model_save_idempotency', 'idx_wf_model_save_saved_model', 1,
                     'saved_model_id'
    UNION ALL SELECT 'wf_designer_preference', 'PRIMARY', 0, 'user_id'
    UNION ALL SELECT 'wf_attachment_quota_guard', 'PRIMARY', 0, 'owner_user_id'
    UNION ALL SELECT 'wf_attachment', 'PRIMARY', 0, 'attachment_id'
    UNION ALL SELECT 'wf_attachment', 'uk_wf_attachment_storage_key', 0, 'storage_key'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_owner_status_expire', 1,
                     'owner_user_id,attachment_status,expire_time'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_status_expire', 1,
                     'attachment_status,expire_time'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_cleanup_due', 1,
                     'attachment_status,cleanup_next_retry_time,expire_time'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_instance_field', 1,
                     'process_instance_id,field_name,attachment_status'
),
index_issues AS (
    SELECT e.table_name, e.index_name
    FROM expected_indexes e
    LEFT JOIN actual_indexes a
     ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.min_non_unique = e.non_unique
     AND a.max_non_unique = e.non_unique
     AND a.index_column_count =
         1 + CHAR_LENGTH(e.columns_in_order)
             - CHAR_LENGTH(REPLACE(e.columns_in_order, ',', ''))
     AND a.columns_in_order = e.columns_in_order
     AND a.full_column_count = a.index_column_count
     AND a.ascending_column_count = a.index_column_count
     AND a.btree_column_count = a.index_column_count
     AND a.visible_column_count = a.index_column_count
    WHERE a.index_name IS NULL
)
SELECT
    'workflow_business_indexes' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'issues=', COUNT(*),
        ', indexes=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', index_name)
                ORDER BY table_name, index_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM index_issues;

WITH expected_checks AS (
    SELECT 'wf_category' AS table_name, 'chk_wf_category_del_flag' AS constraint_name
    UNION ALL SELECT 'wf_form', 'chk_wf_form_content_json'
    UNION ALL SELECT 'wf_form', 'chk_wf_form_del_flag'
    UNION ALL SELECT 'wf_deploy_form', 'chk_wf_deploy_form_content_json'
    UNION ALL SELECT 'wf_deploy_form', 'chk_wf_deploy_form_source'
    UNION ALL SELECT 'wf_deploy_form', 'chk_wf_deploy_form_del_flag'
    UNION ALL SELECT 'wf_copy', 'chk_wf_copy_del_flag'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_request_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_user_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_source_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_payload_sha256'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_completion'
    UNION ALL SELECT 'wf_designer_preference', 'chk_wf_designer_preference_theme'
    UNION ALL SELECT 'wf_designer_preference', 'chk_wf_designer_preference_flags'
    UNION ALL SELECT 'wf_attachment_quota_guard',
                     'chk_wf_attachment_quota_guard_owner'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_status'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_file_size'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_sha256'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_state_relation'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_storage_deleted'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_cleanup_retry'
),
missing_checks AS (
    SELECT e.table_name, e.constraint_name
    FROM expected_checks e
    LEFT JOIN information_schema.TABLE_CONSTRAINTS c
      ON c.CONSTRAINT_SCHEMA = DATABASE()
     AND c.TABLE_NAME = e.table_name
     AND c.CONSTRAINT_NAME = e.constraint_name
     AND c.CONSTRAINT_TYPE = 'CHECK'
     AND c.ENFORCED = 'YES'
    WHERE c.CONSTRAINT_NAME IS NULL
)
SELECT
    'workflow_business_checks' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'missing_or_unenforced=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', constraint_name)
                ORDER BY table_name, constraint_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM missing_checks;

WITH expected_foreign_keys AS (
    SELECT 'wf_designer_preference' AS table_name,
           'fk_wf_designer_preference_user' AS constraint_name,
           'user_id' AS column_name,
           'sys_user' AS referenced_table_name,
           'user_id' AS referenced_column_name,
           'RESTRICT' AS update_rule,
           'CASCADE' AS delete_rule
),
missing_foreign_keys AS (
    SELECT e.table_name, e.constraint_name
    FROM expected_foreign_keys e
    LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS r
      ON r.CONSTRAINT_SCHEMA = DATABASE()
     AND r.TABLE_NAME = e.table_name
     AND r.CONSTRAINT_NAME = e.constraint_name
     AND r.REFERENCED_TABLE_NAME = e.referenced_table_name
     AND r.UPDATE_RULE = e.update_rule
     AND r.DELETE_RULE = e.delete_rule
    LEFT JOIN information_schema.KEY_COLUMN_USAGE k
      ON k.CONSTRAINT_SCHEMA = DATABASE()
     AND k.TABLE_NAME = e.table_name
     AND k.CONSTRAINT_NAME = e.constraint_name
     AND k.COLUMN_NAME = e.column_name
     AND k.REFERENCED_TABLE_NAME = e.referenced_table_name
     AND k.REFERENCED_COLUMN_NAME = e.referenced_column_name
    WHERE r.CONSTRAINT_NAME IS NULL OR k.CONSTRAINT_NAME IS NULL
)
SELECT
    'workflow_business_foreign_keys' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'missing_or_invalid=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', constraint_name)
                ORDER BY table_name, constraint_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM missing_foreign_keys;

-- 对 MySQL 8.0/8.4 自动补入的字符集引导符、反引号、空白和冗余括号做稳定化，其余词法序列必须精确一致。
WITH actual_retry_checks AS (
    SELECT
        tc.ENFORCED AS enforced,
        SHA2(
            REGEXP_REPLACE(
                LOWER(cc.CHECK_CLAUSE),
                '_utf8mb4|_ascii|[[:space:]`()]',
                ''
            ),
            256
        ) AS canonical_sha256
    FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
     AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
    WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'wf_attachment'
      AND tc.CONSTRAINT_NAME = 'chk_wf_attachment_cleanup_retry'
      AND tc.CONSTRAINT_TYPE = 'CHECK'
)
SELECT
    'wf_attachment_cleanup_retry_check_clause' AS check_name,
    CASE
        WHEN COUNT(*) = 1
         AND MAX(enforced) = 'YES'
         AND MAX(canonical_sha256) =
             'f44964aab58ce25fe25af57c9fc1269e3981d3104545530997241be965b189c6'
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'constraints=', COUNT(*),
        ', enforced=', COALESCE(MAX(enforced), 'missing'),
        ', canonical_sha256=', COALESCE(MAX(canonical_sha256), 'missing')
    ) AS detail
FROM actual_retry_checks;

WITH integrity_issues AS (
    SELECT 'wf_category_invalid_del_flag' AS issue_name, COUNT(*) AS issue_count
    FROM wf_category
    WHERE del_flag NOT IN ('0', '2')

    UNION ALL

    SELECT 'wf_category_duplicate_active_code', COUNT(*)
    FROM (
        SELECT code
        FROM wf_category
        WHERE del_flag = '0'
        GROUP BY code
        HAVING COUNT(*) > 1
    ) duplicate_category

    UNION ALL

    SELECT 'wf_form_invalid_row', COUNT(*)
    FROM wf_form
    WHERE del_flag NOT IN ('0', '2') OR JSON_VALID(content) = 0

    UNION ALL

    SELECT 'wf_deploy_form_invalid_row', COUNT(*)
    FROM wf_deploy_form
    WHERE del_flag NOT IN ('0', '2')
       OR JSON_VALID(content) = 0
       OR (source_type = 'TEMPLATE' AND (form_id IS NULL OR form_id <= 0))
       OR (source_type = 'EMBEDDED' AND form_id IS NOT NULL)
       OR source_type NOT IN ('TEMPLATE', 'EMBEDDED')

    UNION ALL

    SELECT 'wf_deploy_form_missing_source_form', COUNT(*)
    FROM wf_deploy_form d
    LEFT JOIN wf_form f ON f.form_id = d.form_id
    WHERE d.del_flag = '0'
      AND d.source_type = 'TEMPLATE'
      AND (f.form_id IS NULL OR f.del_flag <> '0')

    UNION ALL

    SELECT 'wf_deploy_form_missing_deployment', COUNT(*)
    FROM wf_deploy_form d
    LEFT JOIN ACT_RE_DEPLOYMENT p ON p.ID_ = d.deploy_id
    WHERE d.del_flag = '0' AND p.ID_ IS NULL

    UNION ALL

    SELECT 'wf_copy_invalid_del_flag', COUNT(*)
    FROM wf_copy
    WHERE del_flag NOT IN ('0', '2')

    UNION ALL

    SELECT 'wf_copy_duplicate_event_recipient', COUNT(*)
    FROM (
        SELECT copy_event_id, user_id
        FROM wf_copy
        GROUP BY copy_event_id, user_id
        HAVING COUNT(*) > 1
    ) duplicate_copy

    UNION ALL

    SELECT 'wf_copy_missing_process_instance', COUNT(*)
    FROM wf_copy c
    LEFT JOIN ACT_HI_PROCINST p ON p.PROC_INST_ID_ = c.instance_id
    WHERE c.del_flag = '0' AND p.PROC_INST_ID_ IS NULL

    UNION ALL

    SELECT 'wf_copy_missing_recipient', COUNT(*)
    FROM wf_copy c
    LEFT JOIN sys_user u ON u.user_id = c.user_id
    WHERE c.del_flag = '0' AND u.user_id IS NULL

    UNION ALL

    SELECT 'wf_model_save_invalid_row', COUNT(*)
    FROM wf_model_save_idempotency r
    WHERE r.request_id NOT REGEXP
              '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       OR r.user_id NOT REGEXP '^[1-9][0-9]{0,18}$'
       OR CHAR_LENGTH(r.source_model_id) NOT BETWEEN 1 AND 64
       OR r.payload_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR ((r.saved_model_id IS NULL) <> (r.complete_time IS NULL))
       OR (r.saved_model_id IS NOT NULL
           AND (CHAR_LENGTH(r.saved_model_id) NOT BETWEEN 1 AND 64
                OR r.complete_time < r.create_time))

    UNION ALL

    SELECT 'wf_model_save_incomplete_record', COUNT(*)
    FROM wf_model_save_idempotency
    WHERE saved_model_id IS NULL OR complete_time IS NULL

    UNION ALL

    SELECT 'wf_model_save_missing_user', COUNT(*)
    FROM wf_model_save_idempotency r
    LEFT JOIN sys_user u ON u.user_id = CAST(r.user_id AS UNSIGNED)
    WHERE u.user_id IS NULL

    UNION ALL

    SELECT 'wf_attachment_quota_guard_invalid_owner', COUNT(*)
    FROM wf_attachment_quota_guard
    WHERE owner_user_id < 0

    UNION ALL

    SELECT 'wf_designer_preference_invalid_row', COUNT(*)
    FROM wf_designer_preference p
    WHERE p.theme NOT IN ('LIGHT', 'DARK', 'SYSTEM')
       OR p.grid_enabled NOT IN (0, 1)
       OR p.minimap_enabled NOT IN (0, 1)
       OR p.lint_enabled NOT IN (0, 1)
       OR p.token_simulation_enabled NOT IN (0, 1)
       OR p.properties_collapsed NOT IN (0, 1)
       OR p.update_time < p.create_time

    UNION ALL

    SELECT 'wf_designer_preference_missing_user', COUNT(*)
    FROM wf_designer_preference p
    LEFT JOIN sys_user u ON u.user_id = p.user_id
    WHERE u.user_id IS NULL

    UNION ALL

    SELECT 'wf_attachment_quota_guard_global_missing',
           CASE WHEN EXISTS (
               SELECT 1
               FROM wf_attachment_quota_guard
               WHERE owner_user_id = 0
           ) THEN 0 ELSE 1 END

    UNION ALL

    SELECT 'wf_attachment_invalid_row', COUNT(*)
    FROM wf_attachment a
    WHERE a.attachment_status NOT IN ('TEMP', 'BOUND', 'EXPIRED', 'DELETED')
       OR a.file_size <= 0
       OR a.sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR (a.attachment_status = 'BOUND'
           AND (a.process_instance_id IS NULL OR a.node_key IS NULL OR a.bound_time IS NULL
                OR a.storage_deleted_time IS NOT NULL))
       OR (a.attachment_status IN ('TEMP', 'EXPIRED', 'DELETED')
           AND (a.process_instance_id IS NOT NULL OR a.task_id IS NOT NULL
                OR a.node_key IS NOT NULL OR a.bound_time IS NOT NULL))
       OR (a.storage_deleted_time IS NOT NULL
           AND a.attachment_status NOT IN ('EXPIRED', 'DELETED'))

    UNION ALL

    SELECT 'wf_attachment_missing_owner', COUNT(*)
    FROM wf_attachment a
    LEFT JOIN sys_user u ON u.user_id = a.owner_user_id
    WHERE u.user_id IS NULL

    UNION ALL

    SELECT 'wf_attachment_invalid_cleanup_retry', COUNT(*)
    FROM wf_attachment a
    WHERE a.cleanup_retry_count < 0
       OR ((a.cleanup_next_retry_time IS NULL)
           <> (a.cleanup_last_error_code IS NULL))
       OR (a.attachment_status IN ('TEMP', 'BOUND')
           AND a.cleanup_retry_count <> 0)
       OR (a.cleanup_next_retry_time IS NOT NULL
           AND (a.storage_deleted_time IS NOT NULL
                OR a.attachment_status NOT IN ('EXPIRED', 'DELETED')
                OR a.cleanup_retry_count = 0
                OR a.cleanup_last_error_code NOT REGEXP '^[a-z][a-z0-9_]{0,63}$'))

    UNION ALL

    SELECT 'wf_attachment_bound_missing_process_instance', COUNT(*)
    FROM wf_attachment a
    LEFT JOIN ACT_HI_PROCINST p ON p.PROC_INST_ID_ = a.process_instance_id
    WHERE a.attachment_status = 'BOUND' AND p.PROC_INST_ID_ IS NULL

    UNION ALL

    SELECT 'wf_attachment_bound_task_mismatch', COUNT(*)
    FROM wf_attachment a
    LEFT JOIN ACT_HI_TASKINST t ON t.ID_ = a.task_id
    WHERE a.attachment_status = 'BOUND'
      AND a.task_id IS NOT NULL
      AND (t.ID_ IS NULL OR t.PROC_INST_ID_ <> a.process_instance_id
           OR t.TASK_DEF_KEY_ <> a.node_key)

    UNION ALL

    SELECT 'wf_attachment_bound_node_mismatch', COUNT(*)
    FROM wf_attachment a
    LEFT JOIN ACT_HI_ACTINST h
      ON h.PROC_INST_ID_ = a.process_instance_id
     AND h.ACT_ID_ = a.node_key
    WHERE a.attachment_status = 'BOUND' AND h.ID_ IS NULL
)
SELECT
    'workflow_business_data_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'issues=', SUM(issue_count),
        ', detail=', COALESCE(
            GROUP_CONCAT(
                CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
                ORDER BY issue_name SEPARATOR ','
            ),
            'none'
        )
    ) AS detail
FROM integrity_issues;

SELECT
    'workflow_bpmn_event_tables' AS check_name,
    CASE WHEN COUNT(*) = 3 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('found=', COUNT(*), ', expected=3') AS detail
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('wf_bpmn_event_code', 'wf_bpmn_event_audit',
                     'wf_bpmn_event_notification');

SELECT
    'workflow_bpmn_event_constraints' AS check_name,
    CASE WHEN COUNT(*) >= 12 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('found=', COUNT(*), ', expected_at_least=12') AS detail
FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE()
  AND table_name IN ('wf_bpmn_event_code', 'wf_bpmn_event_audit',
                     'wf_bpmn_event_notification')
  AND constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'CHECK', 'FOREIGN KEY');

WITH event_issues AS
(
    SELECT 'event_code_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_bpmn_event_code
    WHERE event_type NOT IN ('ERROR', 'ESCALATION')
       OR event_code NOT REGEXP '^[A-Z][A-Z0-9_.-]{1,63}$'
       OR notification_policy NOT IN ('NONE', 'INITIATOR')
       OR status NOT IN ('ENABLED', 'DISABLED')

    UNION ALL

    SELECT 'event_audit_invalid_row', COUNT(*)
    FROM wf_bpmn_event_audit
    WHERE idempotency_key NOT REGEXP '^[0-9a-f]{64}$'
       OR source_type NOT IN ('SERVICE_TASK', 'HTTP', 'SQL', 'DMN', 'MANUAL')
       OR event_type NOT IN ('ERROR', 'ESCALATION')
       OR match_status NOT IN ('CAPTURED', 'UNMATCHED')
       OR (match_status = 'CAPTURED' AND (boundary_event_id IS NULL OR interrupting IS NULL))
       OR (match_status = 'UNMATCHED' AND (boundary_event_id IS NOT NULL OR interrupting IS NOT NULL))

    UNION ALL

    SELECT 'event_notification_invalid_row', COUNT(*)
    FROM wf_bpmn_event_notification n
    LEFT JOIN wf_bpmn_event_audit a ON a.audit_id = n.audit_id
    LEFT JOIN sys_user u ON CAST(u.user_id AS CHAR) = n.recipient_user_id
    WHERE a.audit_id IS NULL OR u.user_id IS NULL
       OR n.read_status NOT IN ('UNREAD', 'READ')
       OR (n.read_status = 'UNREAD' AND n.read_time IS NOT NULL)
       OR (n.read_status = 'READ' AND n.read_time IS NULL)
)
SELECT
    'workflow_bpmn_event_data_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count)) AS detail
FROM event_issues;

WITH expected_tables AS (
    SELECT 'wf_integration_credential' AS table_name
    UNION ALL SELECT 'wf_runtime_event_request'
), actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN (SELECT table_name FROM expected_tables)
)
SELECT
    'workflow_runtime_integration_tables' AS check_name,
    CASE WHEN COUNT(actual.table_name) = 2
              AND SUM(actual.ENGINE = 'InnoDB') = 2
              AND SUM(actual.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 2
         THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('present=', COUNT(actual.table_name), ', missing=', COALESCE(
        GROUP_CONCAT(CASE WHEN actual.table_name IS NULL THEN expected.table_name END
            ORDER BY expected.table_name SEPARATOR ','), 'none')) AS detail
FROM expected_tables expected
LEFT JOIN actual_tables actual ON actual.table_name = expected.table_name;

WITH expected_columns AS (
    SELECT 'wf_integration_credential' AS table_name, 'credential_id' AS column_name
    UNION ALL SELECT 'wf_integration_credential', 'credential_name'
    UNION ALL SELECT 'wf_integration_credential', 'token_prefix'
    UNION ALL SELECT 'wf_integration_credential', 'token_hash'
    UNION ALL SELECT 'wf_integration_credential', 'scopes'
    UNION ALL SELECT 'wf_integration_credential', 'allowed_variables'
    UNION ALL SELECT 'wf_integration_credential', 'rate_limit_per_minute'
    UNION ALL SELECT 'wf_integration_credential', 'rate_window_start'
    UNION ALL SELECT 'wf_integration_credential', 'rate_window_count'
    UNION ALL SELECT 'wf_integration_credential', 'expires_at'
    UNION ALL SELECT 'wf_integration_credential', 'revoked_at'
    UNION ALL SELECT 'wf_integration_credential', 'revision_no'
    UNION ALL SELECT 'wf_integration_credential', 'last_used_at'
    UNION ALL SELECT 'wf_runtime_event_request', 'request_id'
    UNION ALL SELECT 'wf_runtime_event_request', 'credential_id'
    UNION ALL SELECT 'wf_runtime_event_request', 'event_type'
    UNION ALL SELECT 'wf_runtime_event_request', 'event_name'
    UNION ALL SELECT 'wf_runtime_event_request', 'correlation_type'
    UNION ALL SELECT 'wf_runtime_event_request', 'correlation_value'
    UNION ALL SELECT 'wf_runtime_event_request', 'variables_sha256'
    UNION ALL SELECT 'wf_runtime_event_request', 'matched_process_instance_id'
    UNION ALL SELECT 'wf_runtime_event_request', 'matched_execution_id'
    UNION ALL SELECT 'wf_runtime_event_request', 'status'
    UNION ALL SELECT 'wf_runtime_event_request', 'result_code'
    UNION ALL SELECT 'wf_runtime_event_request', 'result_summary'
    UNION ALL SELECT 'wf_runtime_event_request', 'complete_time'
), missing_columns AS (
    SELECT expected.table_name, expected.column_name
    FROM expected_columns expected
    LEFT JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.COLUMN_NAME = expected.column_name
    WHERE actual.COLUMN_NAME IS NULL
)
SELECT
    'workflow_runtime_integration_columns' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('missing=', COALESCE(GROUP_CONCAT(CONCAT(table_name, '.', column_name)
        ORDER BY table_name, column_name SEPARATOR ','), 'none')) AS detail
FROM missing_columns;

WITH expected_indexes AS (
    SELECT 'wf_integration_credential' AS table_name,
           'uk_wf_integration_token_prefix' AS index_name
    UNION ALL SELECT 'wf_integration_credential', 'idx_wf_integration_active'
    UNION ALL SELECT 'wf_runtime_event_request', 'idx_wf_runtime_event_credential'
    UNION ALL SELECT 'wf_runtime_event_request', 'idx_wf_runtime_event_instance'
    UNION ALL SELECT 'wf_runtime_event_request', 'idx_wf_runtime_event_status'
), missing_indexes AS (
    SELECT expected.table_name, expected.index_name
    FROM expected_indexes expected
    LEFT JOIN information_schema.STATISTICS actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.INDEX_NAME = expected.index_name
    WHERE actual.INDEX_NAME IS NULL
)
SELECT
    'workflow_runtime_integration_indexes' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('missing=', COALESCE(GROUP_CONCAT(CONCAT(table_name, '.', index_name)
        ORDER BY table_name, index_name SEPARATOR ','), 'none')) AS detail
FROM missing_indexes;

WITH expected_checks AS (
    SELECT 'wf_integration_credential' AS table_name,
           'chk_wf_integration_token_hash' AS constraint_name
    UNION ALL SELECT 'wf_integration_credential', 'chk_wf_integration_scopes'
    UNION ALL SELECT 'wf_integration_credential', 'chk_wf_integration_variables'
    UNION ALL SELECT 'wf_integration_credential', 'chk_wf_integration_rate_limit'
    UNION ALL SELECT 'wf_integration_credential', 'chk_wf_integration_rate_window'
    UNION ALL SELECT 'wf_integration_credential', 'chk_wf_integration_expiry'
    UNION ALL SELECT 'wf_runtime_event_request', 'chk_wf_runtime_event_request_id'
    UNION ALL SELECT 'wf_runtime_event_request', 'chk_wf_runtime_event_type'
    UNION ALL SELECT 'wf_runtime_event_request', 'chk_wf_runtime_event_correlation'
    UNION ALL SELECT 'wf_runtime_event_request', 'chk_wf_runtime_event_variables_hash'
    UNION ALL SELECT 'wf_runtime_event_request', 'chk_wf_runtime_event_status'
    UNION ALL SELECT 'wf_runtime_event_request', 'chk_wf_runtime_event_completion'
), missing_checks AS (
    SELECT expected.table_name, expected.constraint_name
    FROM expected_checks expected
    LEFT JOIN information_schema.TABLE_CONSTRAINTS actual
      ON actual.CONSTRAINT_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.CONSTRAINT_NAME = expected.constraint_name
     AND actual.CONSTRAINT_TYPE = 'CHECK'
     AND actual.ENFORCED = 'YES'
    WHERE actual.CONSTRAINT_NAME IS NULL
)
SELECT
    'workflow_runtime_integration_checks' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('missing_or_unenforced=', COALESCE(GROUP_CONCAT(
        CONCAT(table_name, '.', constraint_name) ORDER BY table_name, constraint_name
        SEPARATOR ','), 'none')) AS detail
FROM missing_checks;

SELECT
    'workflow_runtime_integration_foreign_keys' AS check_name,
    CASE WHEN COUNT(*) = 1 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('matching=', COUNT(*), ', expected=1') AS detail
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND TABLE_NAME = 'wf_runtime_event_request'
  AND CONSTRAINT_NAME = 'fk_wf_runtime_event_credential'
  AND REFERENCED_TABLE_NAME = 'wf_integration_credential'
  AND UPDATE_RULE = 'RESTRICT' AND DELETE_RULE = 'RESTRICT';

WITH integrity_issues AS (
    SELECT 'integration_credential_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_integration_credential
    WHERE token_hash NOT REGEXP '^[0-9a-f]{64}$'
       OR scopes NOT REGEXP '^(MESSAGE|RECEIVE|SIGNAL)(,(MESSAGE|RECEIVE|SIGNAL))*$'
       OR rate_limit_per_minute NOT BETWEEN 1 AND 10000
       OR rate_window_count NOT BETWEEN 0 AND rate_limit_per_minute
       OR revision_no <= 0
       OR (expires_at IS NOT NULL AND expires_at <= create_time)
       OR (revoked_at IS NOT NULL AND revoked_at < create_time)

    UNION ALL

    SELECT 'runtime_event_invalid_row', COUNT(*)
    FROM wf_runtime_event_request
    WHERE variables_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR event_type NOT IN ('MESSAGE', 'SIGNAL', 'RECEIVE')
       OR correlation_type NOT IN ('PROCESS_INSTANCE', 'BUSINESS_KEY')
       OR status NOT IN ('RECEIVED', 'PROCESSED', 'FAILED')
       OR (status = 'RECEIVED' AND (complete_time IS NOT NULL OR result_code IS NOT NULL))
       OR (status IN ('PROCESSED', 'FAILED')
           AND (complete_time IS NULL OR result_code IS NULL OR result_summary IS NULL))

    UNION ALL

    SELECT 'runtime_event_missing_credential', COUNT(*)
    FROM wf_runtime_event_request request
    LEFT JOIN wf_integration_credential credential
      ON credential.credential_id = request.credential_id
    WHERE credential.credential_id IS NULL
)
SELECT
    'workflow_runtime_integration_data_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name SEPARATOR ','), 'none')) AS detail
FROM integrity_issues;

-- 扩展注册表、部署快照、HTTP/SQL 连接器和 DMN 冻结属于独立发布域，单独冻结完整结构。
WITH expected_tables AS (
    SELECT 'wf_bpmn_extension' AS table_name
    UNION ALL SELECT 'wf_bpmn_extension_version'
    UNION ALL SELECT 'wf_deploy_extension_snapshot'
    UNION ALL SELECT 'wf_connector_endpoint'
    UNION ALL SELECT 'wf_connector_invocation'
    UNION ALL SELECT 'wf_sql_datasource'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot'
),
actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wf_bpmn_extension', 'wf_bpmn_extension_version',
                         'wf_deploy_extension_snapshot', 'wf_connector_endpoint',
                         'wf_connector_invocation', 'wf_sql_datasource',
                         'wf_deploy_dmn_snapshot')
)
SELECT
    'workflow_extension_tables' AS check_name,
    CASE
        WHEN COUNT(a.table_name) = 7
         AND SUM(a.ENGINE = 'InnoDB') = 7
         AND SUM(a.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 7
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'present=', COUNT(a.table_name),
        ', missing=', COALESCE(
            GROUP_CONCAT(CASE WHEN a.table_name IS NULL THEN e.table_name END
                ORDER BY e.table_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM expected_tables e
LEFT JOIN actual_tables a ON a.table_name = e.table_name;

WITH expected_columns AS (
    SELECT 'wf_bpmn_extension' AS table_name, 'extension_id' AS column_name
    UNION ALL SELECT 'wf_bpmn_extension', 'extension_key'
    UNION ALL SELECT 'wf_bpmn_extension', 'extension_name'
    UNION ALL SELECT 'wf_bpmn_extension', 'extension_type'
    UNION ALL SELECT 'wf_bpmn_extension', 'status'
    UNION ALL SELECT 'wf_bpmn_extension', 'description'
    UNION ALL SELECT 'wf_bpmn_extension', 'create_by'
    UNION ALL SELECT 'wf_bpmn_extension', 'create_time'
    UNION ALL SELECT 'wf_bpmn_extension', 'update_by'
    UNION ALL SELECT 'wf_bpmn_extension', 'update_time'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'version_id'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'extension_id'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'version_no'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'implementation_key'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'config_schema'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'checksum'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'create_by'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'create_time'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'snapshot_id'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'deploy_id'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'process_key'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'element_id'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'extension_key'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'extension_version_id'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'version_no'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'extension_type'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'implementation_key'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'config_json'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'version_checksum'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'snapshot_checksum'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'create_by'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'create_time'
    UNION ALL SELECT 'wf_connector_endpoint', 'endpoint_id'
    UNION ALL SELECT 'wf_connector_endpoint', 'endpoint_key'
    UNION ALL SELECT 'wf_connector_endpoint', 'endpoint_name'
    UNION ALL SELECT 'wf_connector_endpoint', 'base_url'
    UNION ALL SELECT 'wf_connector_endpoint', 'allowed_methods'
    UNION ALL SELECT 'wf_connector_endpoint', 'path_prefix'
    UNION ALL SELECT 'wf_connector_endpoint', 'auth_type'
    UNION ALL SELECT 'wf_connector_endpoint', 'secret_ref'
    UNION ALL SELECT 'wf_connector_endpoint', 'api_key_header'
    UNION ALL SELECT 'wf_connector_endpoint', 'connect_timeout_ms'
    UNION ALL SELECT 'wf_connector_endpoint', 'request_timeout_ms'
    UNION ALL SELECT 'wf_connector_endpoint', 'network_scope'
    UNION ALL SELECT 'wf_connector_endpoint', 'revision_no'
    UNION ALL SELECT 'wf_connector_endpoint', 'status'
    UNION ALL SELECT 'wf_connector_endpoint', 'checksum'
    UNION ALL SELECT 'wf_connector_endpoint', 'create_by'
    UNION ALL SELECT 'wf_connector_endpoint', 'create_time'
    UNION ALL SELECT 'wf_connector_endpoint', 'update_by'
    UNION ALL SELECT 'wf_connector_endpoint', 'update_time'
    UNION ALL SELECT 'wf_connector_invocation', 'invocation_id'
    UNION ALL SELECT 'wf_connector_invocation', 'deployment_id'
    UNION ALL SELECT 'wf_connector_invocation', 'process_instance_id'
    UNION ALL SELECT 'wf_connector_invocation', 'execution_id'
    UNION ALL SELECT 'wf_connector_invocation', 'element_id'
    UNION ALL SELECT 'wf_connector_invocation', 'connector_type'
    UNION ALL SELECT 'wf_connector_invocation', 'target_key'
    UNION ALL SELECT 'wf_connector_invocation', 'target_revision'
    UNION ALL SELECT 'wf_connector_invocation', 'idempotency_key'
    UNION ALL SELECT 'wf_connector_invocation', 'operation'
    UNION ALL SELECT 'wf_connector_invocation', 'target_summary'
    UNION ALL SELECT 'wf_connector_invocation', 'status'
    UNION ALL SELECT 'wf_connector_invocation', 'attempt_count'
    UNION ALL SELECT 'wf_connector_invocation', 'duration_ms'
    UNION ALL SELECT 'wf_connector_invocation', 'result_code'
    UNION ALL SELECT 'wf_connector_invocation', 'result_summary'
    UNION ALL SELECT 'wf_connector_invocation', 'error_code'
    UNION ALL SELECT 'wf_connector_invocation', 'claim_token'
    UNION ALL SELECT 'wf_connector_invocation', 'lease_expires_at'
    UNION ALL SELECT 'wf_connector_invocation', 'create_time'
    UNION ALL SELECT 'wf_connector_invocation', 'update_time'
    UNION ALL SELECT 'wf_sql_datasource', 'datasource_id'
    UNION ALL SELECT 'wf_sql_datasource', 'datasource_key'
    UNION ALL SELECT 'wf_sql_datasource', 'datasource_name'
    UNION ALL SELECT 'wf_sql_datasource', 'connection_type'
    UNION ALL SELECT 'wf_sql_datasource', 'jdbc_url_ref'
    UNION ALL SELECT 'wf_sql_datasource', 'username_ref'
    UNION ALL SELECT 'wf_sql_datasource', 'password_ref'
    UNION ALL SELECT 'wf_sql_datasource', 'allowed_tables'
    UNION ALL SELECT 'wf_sql_datasource', 'connect_timeout_ms'
    UNION ALL SELECT 'wf_sql_datasource', 'query_timeout_seconds'
    UNION ALL SELECT 'wf_sql_datasource', 'revision_no'
    UNION ALL SELECT 'wf_sql_datasource', 'status'
    UNION ALL SELECT 'wf_sql_datasource', 'checksum'
    UNION ALL SELECT 'wf_sql_datasource', 'create_by'
    UNION ALL SELECT 'wf_sql_datasource', 'create_time'
    UNION ALL SELECT 'wf_sql_datasource', 'update_by'
    UNION ALL SELECT 'wf_sql_datasource', 'update_time'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'snapshot_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'deploy_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'process_key'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'element_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'source_decision_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'decision_key'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'decision_version'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'source_deployment_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'resource_name'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'resource_checksum'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'frozen_deployment_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'frozen_decision_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'snapshot_checksum'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'create_by'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'create_time'
),
missing_columns AS (
    SELECT e.table_name, e.column_name
    FROM expected_columns e
    LEFT JOIN information_schema.COLUMNS c
      ON c.TABLE_SCHEMA = DATABASE()
     AND c.TABLE_NAME = e.table_name
     AND c.COLUMN_NAME = e.column_name
    WHERE c.COLUMN_NAME IS NULL
)
SELECT
    'workflow_extension_columns' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'missing=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', column_name)
                ORDER BY table_name, column_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM missing_columns;

WITH actual_indexes AS (
    SELECT TABLE_NAME AS table_name, INDEX_NAME AS index_name,
           MIN(NON_UNIQUE) AS non_unique,
           GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',') AS columns_in_order
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wf_bpmn_extension', 'wf_bpmn_extension_version',
                         'wf_deploy_extension_snapshot', 'wf_connector_endpoint',
                         'wf_connector_invocation', 'wf_sql_datasource',
                         'wf_deploy_dmn_snapshot')
    GROUP BY TABLE_NAME, INDEX_NAME
),
expected_indexes AS (
    SELECT 'wf_bpmn_extension' AS table_name, 'PRIMARY' AS index_name,
           0 AS non_unique, 'extension_id' AS columns_in_order
    UNION ALL SELECT 'wf_bpmn_extension', 'uk_wf_bpmn_extension_key', 0, 'extension_key'
    UNION ALL SELECT 'wf_bpmn_extension', 'idx_wf_bpmn_extension_type_status', 1,
                     'extension_type,status'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'PRIMARY', 0, 'version_id'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'uk_wf_bpmn_extension_version', 0,
                     'extension_id,version_no'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'idx_wf_bpmn_extension_impl', 1,
                     'implementation_key'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'PRIMARY', 0, 'snapshot_id'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'uk_wf_deploy_extension_element', 0,
                     'deploy_id,process_key,element_id'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'idx_wf_deploy_extension_version', 1,
                     'extension_version_id'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'idx_wf_deploy_extension_key', 1,
                     'extension_key,version_no'
    UNION ALL SELECT 'wf_connector_endpoint', 'PRIMARY', 0, 'endpoint_id'
    UNION ALL SELECT 'wf_connector_endpoint', 'uk_wf_connector_endpoint_key', 0,
                     'endpoint_key'
    UNION ALL SELECT 'wf_connector_endpoint', 'idx_wf_connector_endpoint_status', 1,
                     'status,endpoint_name'
    UNION ALL SELECT 'wf_connector_invocation', 'PRIMARY', 0, 'invocation_id'
    UNION ALL SELECT 'wf_connector_invocation', 'uk_wf_connector_invocation_idempotency', 0,
                     'idempotency_key'
    UNION ALL SELECT 'wf_connector_invocation', 'idx_wf_connector_invocation_instance', 1,
                     'process_instance_id,element_id'
    UNION ALL SELECT 'wf_connector_invocation', 'idx_wf_connector_invocation_status', 1,
                     'status,update_time'
    UNION ALL SELECT 'wf_sql_datasource', 'PRIMARY', 0, 'datasource_id'
    UNION ALL SELECT 'wf_sql_datasource', 'uk_wf_sql_datasource_key', 0, 'datasource_key'
    UNION ALL SELECT 'wf_sql_datasource', 'idx_wf_sql_datasource_status', 1,
                     'status,datasource_name'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'PRIMARY', 0, 'snapshot_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'uk_wf_deploy_dmn_element', 0,
                     'deploy_id,process_key,element_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'idx_wf_deploy_dmn_source', 1,
                     'source_decision_id'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'idx_wf_deploy_dmn_frozen', 1,
                     'frozen_deployment_id'
),
index_issues AS (
    SELECT e.table_name, e.index_name
    FROM expected_indexes e
    LEFT JOIN actual_indexes a
      ON a.table_name = e.table_name
     AND a.index_name = e.index_name
     AND a.non_unique = e.non_unique
     AND a.columns_in_order = e.columns_in_order
    WHERE a.index_name IS NULL
)
SELECT
    'workflow_extension_indexes' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'issues=', COUNT(*),
        ', indexes=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', index_name)
                ORDER BY table_name, index_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM index_issues;

WITH expected_checks AS (
    SELECT 'wf_bpmn_extension' AS table_name, 'chk_wf_bpmn_extension_key' AS constraint_name
    UNION ALL SELECT 'wf_bpmn_extension', 'chk_wf_bpmn_extension_type'
    UNION ALL SELECT 'wf_bpmn_extension', 'chk_wf_bpmn_extension_status'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'chk_wf_bpmn_extension_version_no'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'chk_wf_bpmn_extension_impl'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'chk_wf_bpmn_extension_checksum'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'chk_wf_deploy_extension_version_no'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'chk_wf_deploy_extension_type'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'chk_wf_deploy_extension_impl'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'chk_wf_deploy_extension_version_checksum'
    UNION ALL SELECT 'wf_deploy_extension_snapshot', 'chk_wf_deploy_extension_snapshot_checksum'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_key'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_auth'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_network'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_revision'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_status'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_connect_timeout'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_request_timeout'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_checksum'
    UNION ALL SELECT 'wf_connector_invocation', 'chk_wf_connector_invocation_revision'
    UNION ALL SELECT 'wf_connector_invocation', 'chk_wf_connector_invocation_type'
    UNION ALL SELECT 'wf_connector_invocation', 'chk_wf_connector_invocation_idempotency'
    UNION ALL SELECT 'wf_connector_invocation', 'chk_wf_connector_invocation_status'
    UNION ALL SELECT 'wf_connector_invocation', 'chk_wf_connector_invocation_attempt'
    UNION ALL SELECT 'wf_connector_invocation', 'chk_wf_connector_invocation_result_code'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_key'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_type'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_status'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_revision'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_connect_timeout'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_query_timeout'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_checksum'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'chk_wf_deploy_dmn_version'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'chk_wf_deploy_dmn_resource_checksum'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot', 'chk_wf_deploy_dmn_snapshot_checksum'
),
missing_checks AS (
    SELECT e.table_name, e.constraint_name
    FROM expected_checks e
    LEFT JOIN information_schema.TABLE_CONSTRAINTS c
      ON c.CONSTRAINT_SCHEMA = DATABASE()
     AND c.TABLE_NAME = e.table_name
     AND c.CONSTRAINT_NAME = e.constraint_name
     AND c.CONSTRAINT_TYPE = 'CHECK'
     AND c.ENFORCED = 'YES'
    WHERE c.CONSTRAINT_NAME IS NULL
)
SELECT
    'workflow_extension_checks' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'missing_or_unenforced=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', constraint_name)
                ORDER BY table_name, constraint_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM missing_checks;

WITH expected_foreign_keys AS (
    SELECT 'wf_bpmn_extension_version' AS table_name,
           'fk_wf_bpmn_extension_version_extension' AS constraint_name,
           'extension_id' AS column_name, 'wf_bpmn_extension' AS referenced_table_name,
           'extension_id' AS referenced_column_name
    UNION ALL
    SELECT 'wf_deploy_extension_snapshot', 'fk_wf_deploy_extension_version',
           'extension_version_id', 'wf_bpmn_extension_version', 'version_id'
),
missing_foreign_keys AS (
    SELECT e.table_name, e.constraint_name
    FROM expected_foreign_keys e
    LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS r
      ON r.CONSTRAINT_SCHEMA = DATABASE()
     AND r.TABLE_NAME = e.table_name
     AND r.CONSTRAINT_NAME = e.constraint_name
     AND r.REFERENCED_TABLE_NAME = e.referenced_table_name
     AND r.UPDATE_RULE = 'RESTRICT'
     AND r.DELETE_RULE = 'RESTRICT'
    LEFT JOIN information_schema.KEY_COLUMN_USAGE k
      ON k.CONSTRAINT_SCHEMA = DATABASE()
     AND k.TABLE_NAME = e.table_name
     AND k.CONSTRAINT_NAME = e.constraint_name
     AND k.COLUMN_NAME = e.column_name
     AND k.REFERENCED_TABLE_NAME = e.referenced_table_name
     AND k.REFERENCED_COLUMN_NAME = e.referenced_column_name
    WHERE r.CONSTRAINT_NAME IS NULL OR k.CONSTRAINT_NAME IS NULL
)
SELECT
    'workflow_extension_foreign_keys' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'missing_or_invalid=', COALESCE(
            GROUP_CONCAT(CONCAT(table_name, '.', constraint_name)
                ORDER BY table_name, constraint_name SEPARATOR ','),
            'none'
        )
    ) AS detail
FROM missing_foreign_keys;

WITH integrity_issues AS (
    SELECT 'extension_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_bpmn_extension e
    WHERE e.extension_key NOT REGEXP '^[A-Za-z][A-Za-z0-9_.-]{0,127}$'
       OR e.extension_type NOT IN ('JAVA', 'CEL', 'HTTP', 'SQL', 'DMN', 'FORM_FIELD')
       OR e.status NOT IN ('ENABLED', 'DISABLED')
       OR (e.update_time IS NOT NULL AND e.update_time < e.create_time)

    UNION ALL

    SELECT 'extension_version_invalid_row', COUNT(*)
    FROM wf_bpmn_extension_version v
    WHERE v.version_no <= 0
       OR v.implementation_key NOT REGEXP '^[A-Z][A-Z0-9_]{1,63}$'
       OR v.checksum NOT REGEXP '^[0-9a-f]{64}$'
       OR JSON_VALID(v.config_schema) = 0

    UNION ALL

    SELECT 'deploy_extension_snapshot_mismatch', COUNT(*)
    FROM wf_deploy_extension_snapshot s
    JOIN wf_bpmn_extension_version v ON v.version_id = s.extension_version_id
    JOIN wf_bpmn_extension e ON e.extension_id = v.extension_id
    LEFT JOIN ACT_RE_DEPLOYMENT d ON d.ID_ = s.deploy_id
    WHERE d.ID_ IS NULL
       OR s.extension_key <> e.extension_key
       OR s.version_no <> v.version_no
       OR s.extension_type <> e.extension_type
       OR s.implementation_key <> v.implementation_key
       OR s.version_checksum <> v.checksum
       OR JSON_VALID(s.config_json) = 0

    UNION ALL

    SELECT 'connector_endpoint_invalid_row', COUNT(*)
    FROM wf_connector_endpoint e
    WHERE e.revision_no <= 0
       OR e.status NOT IN ('ENABLED', 'DISABLED')
       OR e.network_scope NOT IN ('PUBLIC', 'PRIVATE')
       OR e.checksum NOT REGEXP '^[0-9a-f]{64}$'
       OR (e.auth_type = 'NONE' AND (e.secret_ref IS NOT NULL OR e.api_key_header IS NOT NULL))
       OR (e.auth_type = 'BEARER' AND (e.secret_ref IS NULL OR e.api_key_header IS NOT NULL))
       OR (e.auth_type = 'API_KEY' AND (e.secret_ref IS NULL OR e.api_key_header IS NULL))
       OR (e.update_time IS NOT NULL AND e.update_time < e.create_time)

    UNION ALL

    SELECT 'connector_invocation_invalid_state', COUNT(*)
    FROM wf_connector_invocation i
    WHERE (i.status = 'PENDING'
           AND (i.attempt_count <> 0 OR i.claim_token IS NOT NULL
                OR i.lease_expires_at IS NOT NULL OR i.duration_ms IS NOT NULL))
       OR (i.status = 'RUNNING'
           AND (i.attempt_count <= 0 OR i.claim_token IS NULL OR i.lease_expires_at IS NULL))
       OR (i.status = 'SUCCESS'
           AND (i.attempt_count <= 0 OR i.claim_token IS NOT NULL
                OR i.lease_expires_at IS NOT NULL OR i.duration_ms IS NULL
                OR i.result_code IS NULL OR i.result_summary IS NULL
                OR i.error_code IS NOT NULL))
       OR (i.status = 'FAILED'
           AND (i.attempt_count <= 0 OR i.claim_token IS NOT NULL
                OR i.lease_expires_at IS NOT NULL OR i.duration_ms IS NULL
                OR i.result_summary IS NULL OR i.error_code IS NULL))
       OR i.update_time < i.create_time

    UNION ALL

    SELECT 'deploy_dmn_snapshot_mismatch', COUNT(*)
    FROM wf_deploy_dmn_snapshot s
    LEFT JOIN ACT_RE_DEPLOYMENT process_deployment
      ON process_deployment.ID_ = s.deploy_id
    LEFT JOIN ACT_DMN_DEPLOYMENT frozen_deployment
      ON frozen_deployment.ID_ = s.frozen_deployment_id
    LEFT JOIN ACT_DMN_DECISION frozen_decision
      ON frozen_decision.ID_ = s.frozen_decision_id
     AND frozen_decision.DEPLOYMENT_ID_ = s.frozen_deployment_id
    WHERE process_deployment.ID_ IS NULL
       OR frozen_deployment.ID_ IS NULL
       OR frozen_decision.ID_ IS NULL
       OR s.decision_version <= 0
       OR s.resource_checksum NOT REGEXP '^[0-9a-f]{64}$'
       OR s.snapshot_checksum NOT REGEXP '^[0-9a-f]{64}$'
)
SELECT
    'workflow_extension_data_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT(
        'issues=', SUM(issue_count),
        ', detail=', COALESCE(
            GROUP_CONCAT(
                CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
                ORDER BY issue_name SEPARATOR ','
            ),
            'none'
        )
    ) AS detail
FROM integrity_issues;
