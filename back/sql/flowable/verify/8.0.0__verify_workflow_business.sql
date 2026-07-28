-- Flowable 8 工作流业务表只读验收脚本。
-- 所有检查都应返回 PASS；本脚本不会创建、修改或删除数据。

WITH expected_tables AS (
    SELECT 'wf_category' AS table_name
    UNION ALL SELECT 'wf_form'
    UNION ALL SELECT 'wf_deploy_form'
    UNION ALL SELECT 'wf_copy'
    UNION ALL SELECT 'wf_attachment_quota_guard'
    UNION ALL SELECT 'wf_attachment'
),
actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wf_category', 'wf_form', 'wf_deploy_form', 'wf_copy',
                         'wf_attachment_quota_guard', 'wf_attachment')
)
SELECT
    'workflow_business_tables' AS check_name,
    CASE
        WHEN COUNT(a.table_name) = 6
         AND SUM(a.ENGINE = 'InnoDB') = 6
         AND SUM(a.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 6
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
                         'wf_attachment_quota_guard', 'wf_attachment')
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
    UNION ALL SELECT 'wf_deploy_form', 'chk_wf_deploy_form_del_flag'
    UNION ALL SELECT 'wf_copy', 'chk_wf_copy_del_flag'
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
    WHERE del_flag NOT IN ('0', '2') OR JSON_VALID(content) = 0

    UNION ALL

    SELECT 'wf_deploy_form_missing_source_form', COUNT(*)
    FROM wf_deploy_form d
    LEFT JOIN wf_form f ON f.form_id = d.form_id
    WHERE d.del_flag = '0' AND (f.form_id IS NULL OR f.del_flag <> '0')

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

    SELECT 'wf_attachment_quota_guard_invalid_owner', COUNT(*)
    FROM wf_attachment_quota_guard
    WHERE owner_user_id < 0

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
