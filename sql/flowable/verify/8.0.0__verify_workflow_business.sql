-- Flowable 8 工作流业务表只读验收脚本。
-- 所有检查都应返回 PASS；本脚本不会创建、修改或删除数据。

SELECT
    'workflow_schema_table_counts' AS check_name,
    CASE
        WHEN COUNT(*) = 101
         AND SUM(LEFT(UPPER(TABLE_NAME), 4) <> 'ACT_'
                 AND LEFT(UPPER(TABLE_NAME), 4) <> 'FLW_'
                 AND LEFT(UPPER(TABLE_NAME), 3) <> 'WF_'
                 AND LEFT(UPPER(TABLE_NAME), 5) <> 'QRTZ_') = 20
         AND SUM(LEFT(UPPER(TABLE_NAME), 5) = 'QRTZ_') = 11
         AND SUM(LEFT(UPPER(TABLE_NAME), 4) IN ('ACT_', 'FLW_')) = 36
         AND SUM(LEFT(UPPER(TABLE_NAME), 3) = 'WF_') = 34
        THEN 'PASS'
        ELSE 'FAIL'
    END AS result,
    CONCAT(
        'total=', COUNT(*),
        ', ruoyi=', SUM(LEFT(UPPER(TABLE_NAME), 4) <> 'ACT_'
            AND LEFT(UPPER(TABLE_NAME), 4) <> 'FLW_'
            AND LEFT(UPPER(TABLE_NAME), 3) <> 'WF_'
            AND LEFT(UPPER(TABLE_NAME), 5) <> 'QRTZ_'),
        ', quartz=', SUM(LEFT(UPPER(TABLE_NAME), 5) = 'QRTZ_'),
        ', flowable=', SUM(LEFT(UPPER(TABLE_NAME), 4) IN ('ACT_', 'FLW_')),
        ', workflow=', SUM(LEFT(UPPER(TABLE_NAME), 3) = 'WF_')
    ) AS detail
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_TYPE = 'BASE TABLE';

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

WITH retired_workflow_tables AS (
    SELECT 'wf_deploy_form' AS table_name
    UNION ALL SELECT 'wf_deploy_condition_rule'
    UNION ALL SELECT 'wf_deploy_controlled_loop'
    UNION ALL SELECT 'wf_deploy_participant_rule'
    UNION ALL SELECT 'wf_deploy_extension_snapshot'
    UNION ALL SELECT 'wf_deploy_dmn_snapshot'
    UNION ALL SELECT 'wf_deploy_call_activity'
    UNION ALL SELECT 'wf_deploy_task_sla'
    UNION ALL SELECT 'wf_task_sla_notification'
    UNION ALL SELECT 'wf_bpmn_event_notification'
),
unexpected_retired_tables AS (
    SELECT retired.table_name
    FROM retired_workflow_tables retired
    JOIN information_schema.TABLES actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = retired.table_name
)
SELECT 'workflow_retired_table_absence' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('unexpected=', COALESCE(GROUP_CONCAT(table_name ORDER BY table_name), 'none')) AS detail
FROM unexpected_retired_tables;

WITH expected_artifact_resources AS (
    SELECT 'approvaplat/manifest-v1.json' AS resource_name
    UNION ALL SELECT 'approvaplat/forms-v1.json'
    UNION ALL SELECT 'approvaplat/conditions-v1.json'
    UNION ALL SELECT 'approvaplat/controlled-loops-v1.json'
    UNION ALL SELECT 'approvaplat/participants-v1.json'
    UNION ALL SELECT 'approvaplat/extensions-v1.json'
    UNION ALL SELECT 'approvaplat/dmn-v1.json'
    UNION ALL SELECT 'approvaplat/call-activities-v1.json'
    UNION ALL SELECT 'approvaplat/task-sla-v1.json'
),
artifact_deployments AS (
    SELECT deployment.ID_, deployment.KEY_, deployment.CATEGORY_,
           deployment.PARENT_DEPLOYMENT_ID_
    FROM ACT_RE_DEPLOYMENT deployment
    WHERE deployment.CATEGORY_ = 'APPROVAPLAT_WORKFLOW_ARTIFACTS'
       OR deployment.KEY_ LIKE 'approvaplat-artifacts:%'
),
artifact_issues AS (
    SELECT 'artifact_deployment_invalid_identity' AS issue_name, COUNT(*) AS issue_count
    FROM artifact_deployments artifact
    WHERE artifact.CATEGORY_ IS NULL
       OR artifact.CATEGORY_ <> 'APPROVAPLAT_WORKFLOW_ARTIFACTS'
       OR artifact.PARENT_DEPLOYMENT_ID_ IS NULL
       OR artifact.KEY_ IS NULL
       OR artifact.KEY_ <> CONCAT('approvaplat-artifacts:', artifact.PARENT_DEPLOYMENT_ID_)

    UNION ALL

    SELECT 'artifact_deployment_missing_parent', COUNT(*)
    FROM artifact_deployments artifact
    LEFT JOIN ACT_RE_DEPLOYMENT parent
      ON parent.ID_ = artifact.PARENT_DEPLOYMENT_ID_
    WHERE parent.ID_ IS NULL

    UNION ALL

    SELECT 'artifact_deployment_duplicate_parent', COUNT(*)
    FROM (
        SELECT PARENT_DEPLOYMENT_ID_
        FROM artifact_deployments
        WHERE PARENT_DEPLOYMENT_ID_ IS NOT NULL
        GROUP BY PARENT_DEPLOYMENT_ID_
        HAVING COUNT(*) <> 1
    ) duplicate_parent

    UNION ALL

    SELECT 'artifact_deployment_executable_definition', COUNT(*)
    FROM artifact_deployments artifact
    JOIN ACT_RE_PROCDEF definition ON definition.DEPLOYMENT_ID_ = artifact.ID_

    UNION ALL

    SELECT 'artifact_resource_missing', COUNT(*)
    FROM artifact_deployments artifact
    CROSS JOIN expected_artifact_resources expected
    LEFT JOIN ACT_GE_BYTEARRAY resource
      ON resource.DEPLOYMENT_ID_ = artifact.ID_
     AND resource.NAME_ = expected.resource_name
    WHERE resource.ID_ IS NULL

    UNION ALL

    SELECT 'artifact_resource_duplicate', COUNT(*)
    FROM (
        SELECT resource.DEPLOYMENT_ID_, resource.NAME_
        FROM ACT_GE_BYTEARRAY resource
        JOIN artifact_deployments artifact ON artifact.ID_ = resource.DEPLOYMENT_ID_
        GROUP BY resource.DEPLOYMENT_ID_, resource.NAME_
        HAVING COUNT(*) <> 1
    ) duplicate_resource

    UNION ALL

    SELECT 'artifact_resource_unexpected', COUNT(*)
    FROM ACT_GE_BYTEARRAY resource
    JOIN artifact_deployments artifact ON artifact.ID_ = resource.DEPLOYMENT_ID_
    LEFT JOIN expected_artifact_resources expected ON expected.resource_name = resource.NAME_
    WHERE expected.resource_name IS NULL

    UNION ALL

    SELECT 'artifact_resource_invalid_json', COUNT(*)
    FROM ACT_GE_BYTEARRAY resource
    JOIN artifact_deployments artifact ON artifact.ID_ = resource.DEPLOYMENT_ID_
    JOIN expected_artifact_resources expected ON expected.resource_name = resource.NAME_
    WHERE CASE
        WHEN resource.BYTES_ IS NULL
          OR JSON_VALID(CONVERT(resource.BYTES_ USING utf8mb4)) = 0 THEN 1
        WHEN resource.NAME_ = 'approvaplat/manifest-v1.json' THEN
            COALESCE(JSON_EXTRACT(CONVERT(resource.BYTES_ USING utf8mb4),
                    '$.schemaVersion') = 1, 0) = 0
        ELSE COALESCE(JSON_TYPE(JSON_EXTRACT(CONVERT(resource.BYTES_ USING utf8mb4), '$'))
                <> 'ARRAY', 1)
    END = 1
)
SELECT 'workflow_deployment_artifact_integrity' AS check_name,
       CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
           CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
           ORDER BY issue_name SEPARATOR ','), 'none')) AS detail
FROM artifact_issues;

WITH expected_extension_tables AS (
    SELECT 'wf_bpmn_extension' AS table_name
    UNION ALL SELECT 'wf_bpmn_extension_version'
    UNION ALL SELECT 'wf_connector_endpoint'
    UNION ALL SELECT 'wf_connector_invocation'
    UNION ALL SELECT 'wf_sql_datasource'
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
    UNION ALL SELECT 'wf_copy'
    UNION ALL SELECT 'wf_model_save_idempotency'
    UNION ALL SELECT 'wf_designer_preference'
    UNION ALL SELECT 'wf_process_draft'
    UNION ALL SELECT 'wf_process_draft_audit'
    UNION ALL SELECT 'wf_attachment_quota_guard'
    UNION ALL SELECT 'wf_attachment'
    UNION ALL SELECT 'wf_controlled_loop_execution'
),
actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wf_category', 'wf_form', 'wf_copy',
                         'wf_model_save_idempotency', 'wf_designer_preference',
                         'wf_process_draft', 'wf_process_draft_audit',
                         'wf_attachment_quota_guard',
                         'wf_attachment', 'wf_controlled_loop_execution')
)
SELECT
    'workflow_business_tables' AS check_name,
    CASE
        WHEN COUNT(a.table_name) = 10
         AND SUM(a.ENGINE = 'InnoDB') = 10
         AND SUM(a.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 10
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
    UNION ALL SELECT 'wf_copy', 'copy_id'
    UNION ALL SELECT 'wf_copy', 'copy_event_id'
    UNION ALL SELECT 'wf_copy', 'deployment_id'
    UNION ALL SELECT 'wf_copy', 'instance_id'
    UNION ALL SELECT 'wf_copy', 'task_id'
    UNION ALL SELECT 'wf_copy', 'user_id'
    UNION ALL SELECT 'wf_copy', 'originator_id'
    UNION ALL SELECT 'wf_copy', 'source_type'
    UNION ALL SELECT 'wf_copy', 'trigger_type'
    UNION ALL SELECT 'wf_copy', 'trigger_node_id'
    UNION ALL SELECT 'wf_copy', 'trigger_node_name'
    UNION ALL SELECT 'wf_copy', 'read_status'
    UNION ALL SELECT 'wf_copy', 'read_time'
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
    UNION ALL SELECT 'wf_process_draft', 'draft_id'
    UNION ALL SELECT 'wf_process_draft', 'owner_user_id'
    UNION ALL SELECT 'wf_process_draft', 'process_definition_id'
    UNION ALL SELECT 'wf_process_draft', 'process_definition_key'
    UNION ALL SELECT 'wf_process_draft', 'process_definition_version'
    UNION ALL SELECT 'wf_process_draft', 'deployment_id'
    UNION ALL SELECT 'wf_process_draft', 'process_name'
    UNION ALL SELECT 'wf_process_draft', 'source_type'
    UNION ALL SELECT 'wf_process_draft', 'form_id'
    UNION ALL SELECT 'wf_process_draft', 'form_key'
    UNION ALL SELECT 'wf_process_draft', 'start_node_key'
    UNION ALL SELECT 'wf_process_draft', 'form_name'
    UNION ALL SELECT 'wf_process_draft', 'node_name'
    UNION ALL SELECT 'wf_process_draft', 'snapshot_create_time'
    UNION ALL SELECT 'wf_process_draft', 'form_snapshot'
    UNION ALL SELECT 'wf_process_draft', 'form_snapshot_sha256'
    UNION ALL SELECT 'wf_process_draft', 'form_values'
    UNION ALL SELECT 'wf_process_draft', 'business_key'
    UNION ALL SELECT 'wf_process_draft', 'draft_status'
    UNION ALL SELECT 'wf_process_draft', 'revision_no'
    UNION ALL SELECT 'wf_process_draft', 'submitted_process_instance_id'
    UNION ALL SELECT 'wf_process_draft', 'submitted_time'
    UNION ALL SELECT 'wf_process_draft', 'deleted_time'
    UNION ALL SELECT 'wf_process_draft', 'create_time'
    UNION ALL SELECT 'wf_process_draft', 'update_time'
    UNION ALL SELECT 'wf_process_draft_audit', 'audit_id'
    UNION ALL SELECT 'wf_process_draft_audit', 'draft_id'
    UNION ALL SELECT 'wf_process_draft_audit', 'owner_user_id'
    UNION ALL SELECT 'wf_process_draft_audit', 'action_type'
    UNION ALL SELECT 'wf_process_draft_audit', 'from_status'
    UNION ALL SELECT 'wf_process_draft_audit', 'to_status'
    UNION ALL SELECT 'wf_process_draft_audit', 'from_revision'
    UNION ALL SELECT 'wf_process_draft_audit', 'to_revision'
    UNION ALL SELECT 'wf_process_draft_audit', 'process_instance_id'
    UNION ALL SELECT 'wf_process_draft_audit', 'detail_json'
    UNION ALL SELECT 'wf_process_draft_audit', 'create_time'
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
    UNION ALL SELECT 'wf_attachment', 'draft_id'
    UNION ALL SELECT 'wf_attachment', 'process_instance_id'
    UNION ALL SELECT 'wf_attachment', 'task_id'
    UNION ALL SELECT 'wf_attachment', 'node_key'
    UNION ALL SELECT 'wf_attachment', 'bound_time'
    UNION ALL SELECT 'wf_attachment', 'storage_deleted_time'
    UNION ALL SELECT 'wf_attachment', 'cleanup_retry_count'
    UNION ALL SELECT 'wf_attachment', 'cleanup_next_retry_time'
    UNION ALL SELECT 'wf_attachment', 'cleanup_last_error_code'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'execution_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'deploy_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'process_definition_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'process_instance_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'activity_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'task_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'iteration_no'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'actor_user_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'decision_value'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'outcome'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'create_time'
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

WITH expected_draft_columns AS (
    SELECT 'wf_process_draft' AS table_name, 'draft_id' AS column_name
    UNION ALL SELECT 'wf_process_draft', 'form_snapshot'
    UNION ALL SELECT 'wf_process_draft', 'start_multi_instance_assignments'
    UNION ALL SELECT 'wf_process_draft', 'form_values'
    UNION ALL SELECT 'wf_process_draft', 'multi_instance_user_ids'
    UNION ALL SELECT 'wf_process_draft', 'snapshot_create_time'
    UNION ALL SELECT 'wf_process_draft', 'draft_status'
    UNION ALL SELECT 'wf_process_draft', 'revision_no'
    UNION ALL SELECT 'wf_process_draft_audit', 'draft_id'
    UNION ALL SELECT 'wf_process_draft_audit', 'detail_json'
    UNION ALL SELECT 'wf_attachment', 'draft_id'
),
valid_draft_columns AS (
    SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND (
          (TABLE_NAME = 'wf_process_draft' AND COLUMN_NAME = 'draft_id'
           AND DATA_TYPE = 'char' AND CHARACTER_MAXIMUM_LENGTH = 36
           AND CHARACTER_SET_NAME = 'ascii' AND COLLATION_NAME = 'ascii_bin'
           AND IS_NULLABLE = 'NO')
          OR (TABLE_NAME = 'wf_process_draft' AND COLUMN_NAME IN
              ('form_snapshot', 'start_multi_instance_assignments',
               'form_values', 'multi_instance_user_ids')
              AND DATA_TYPE = 'longtext' AND CHARACTER_SET_NAME = 'utf8mb4'
              AND COLLATION_NAME = 'utf8mb4_unicode_ci' AND IS_NULLABLE = 'NO')
          OR (TABLE_NAME = 'wf_process_draft' AND COLUMN_NAME = 'snapshot_create_time'
              AND DATA_TYPE = 'datetime' AND DATETIME_PRECISION = 3 AND IS_NULLABLE = 'NO')
          OR (TABLE_NAME = 'wf_process_draft' AND COLUMN_NAME = 'draft_status'
              AND DATA_TYPE = 'varchar' AND CHARACTER_MAXIMUM_LENGTH = 16
              AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = 'ACTIVE')
          OR (TABLE_NAME = 'wf_process_draft' AND COLUMN_NAME = 'revision_no'
              AND COLUMN_TYPE = 'bigint' AND IS_NULLABLE = 'NO' AND COLUMN_DEFAULT = '1')
          OR (TABLE_NAME = 'wf_process_draft_audit' AND COLUMN_NAME = 'draft_id'
              AND DATA_TYPE = 'char' AND CHARACTER_MAXIMUM_LENGTH = 36
              AND CHARACTER_SET_NAME = 'ascii' AND COLLATION_NAME = 'ascii_bin'
              AND IS_NULLABLE = 'NO')
          OR (TABLE_NAME = 'wf_process_draft_audit' AND COLUMN_NAME = 'detail_json'
              AND DATA_TYPE = 'longtext' AND CHARACTER_SET_NAME = 'utf8mb4'
              AND COLLATION_NAME = 'utf8mb4_unicode_ci' AND IS_NULLABLE = 'YES')
          OR (TABLE_NAME = 'wf_attachment' AND COLUMN_NAME = 'draft_id'
              AND DATA_TYPE = 'char' AND CHARACTER_MAXIMUM_LENGTH = 36
              AND CHARACTER_SET_NAME = 'ascii' AND COLLATION_NAME = 'ascii_bin'
              AND IS_NULLABLE = 'YES')
      )
),
invalid_draft_columns AS (
    SELECT expected.table_name, expected.column_name
    FROM expected_draft_columns expected
    LEFT JOIN valid_draft_columns actual
      ON actual.table_name = expected.table_name
     AND actual.column_name = expected.column_name
    WHERE actual.column_name IS NULL
)
SELECT
    'workflow_draft_column_types' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('invalid=', COALESCE(GROUP_CONCAT(
        CONCAT(table_name, '.', column_name) ORDER BY table_name, column_name), 'none')) AS detail
FROM invalid_draft_columns;

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
      AND TABLE_NAME IN ('wf_category', 'wf_form', 'wf_copy',
                         'wf_model_save_idempotency', 'wf_designer_preference',
                         'wf_process_draft', 'wf_process_draft_audit',
                         'wf_attachment_quota_guard',
                         'wf_attachment', 'wf_controlled_loop_execution')
    GROUP BY TABLE_NAME, INDEX_NAME
),
expected_indexes AS (
    SELECT 'wf_category' AS table_name, 'PRIMARY' AS index_name, 0 AS non_unique,
           'category_id' AS columns_in_order
    UNION ALL SELECT 'wf_category', 'uk_wf_category_active_code', 0, 'active_code'
    UNION ALL SELECT 'wf_form', 'PRIMARY', 0, 'form_id'
    UNION ALL SELECT 'wf_form', 'idx_wf_form_name', 1, 'form_name'
    UNION ALL SELECT 'wf_copy', 'PRIMARY', 0, 'copy_id'
    UNION ALL SELECT 'wf_copy', 'uk_wf_copy_event_user', 0, 'copy_event_id,user_id'
    UNION ALL SELECT 'wf_copy', 'idx_wf_copy_user_status_time', 1, 'user_id,del_flag,read_status,create_time'
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
    UNION ALL SELECT 'wf_process_draft', 'PRIMARY', 0, 'draft_id'
    UNION ALL SELECT 'wf_process_draft', 'uk_wf_process_draft_instance', 0,
                     'submitted_process_instance_id'
    UNION ALL SELECT 'wf_process_draft', 'idx_wf_process_draft_owner_status_time', 1,
                     'owner_user_id,draft_status,update_time,draft_id'
    UNION ALL SELECT 'wf_process_draft', 'idx_wf_process_draft_owner_process_time', 1,
                     'owner_user_id,process_definition_key,update_time,draft_id'
    UNION ALL SELECT 'wf_process_draft', 'idx_wf_process_draft_definition_version', 1,
                     'process_definition_key,process_definition_version,draft_status'
    UNION ALL SELECT 'wf_process_draft_audit', 'PRIMARY', 0, 'audit_id'
    UNION ALL SELECT 'wf_process_draft_audit', 'uk_wf_process_draft_audit_revision', 0,
                     'draft_id,to_revision'
    UNION ALL SELECT 'wf_process_draft_audit', 'idx_wf_process_draft_audit_time', 1,
                     'draft_id,create_time,audit_id'
    UNION ALL SELECT 'wf_attachment_quota_guard', 'PRIMARY', 0, 'owner_user_id'
    UNION ALL SELECT 'wf_attachment', 'PRIMARY', 0, 'attachment_id'
    UNION ALL SELECT 'wf_attachment', 'uk_wf_attachment_storage_key', 0, 'storage_key'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_owner_status_expire', 1,
                     'owner_user_id,attachment_status,expire_time'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_status_expire', 1,
                     'attachment_status,expire_time'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_cleanup_due', 1,
                     'attachment_status,cleanup_next_retry_time,expire_time'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_draft_field', 1,
                     'draft_id,field_name,attachment_status'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_instance_field', 1,
                     'process_instance_id,field_name,attachment_status'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'PRIMARY', 0, 'execution_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'uk_wf_controlled_loop_task', 0,
                     'task_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'uk_wf_controlled_loop_iteration', 0,
                     'process_instance_id,activity_id,iteration_no'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'idx_wf_controlled_loop_instance_time', 1,
                     'process_instance_id,create_time'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'idx_wf_controlled_loop_deploy', 1,
                     'deploy_id,activity_id'
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
    UNION ALL SELECT 'wf_copy', 'chk_wf_copy_del_flag'
    UNION ALL SELECT 'wf_copy', 'chk_wf_copy_source_type'
    UNION ALL SELECT 'wf_copy', 'chk_wf_copy_trigger_type'
    UNION ALL SELECT 'wf_copy', 'chk_wf_copy_read_state'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_request_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_user_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_source_id'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_payload_sha256'
    UNION ALL SELECT 'wf_model_save_idempotency', 'chk_wf_model_save_completion'
    UNION ALL SELECT 'wf_designer_preference', 'chk_wf_designer_preference_theme'
    UNION ALL SELECT 'wf_designer_preference', 'chk_wf_designer_preference_flags'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_id'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_owner'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_definition'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_source'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_snapshot_json'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_assignment_json'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_values_json'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_members_json'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_snapshot_hash'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_revision'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_status'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_lifecycle'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_times'
    UNION ALL SELECT 'wf_process_draft_audit', 'chk_wf_process_draft_audit_owner'
    UNION ALL SELECT 'wf_process_draft_audit', 'chk_wf_process_draft_audit_action'
    UNION ALL SELECT 'wf_process_draft_audit', 'chk_wf_process_draft_audit_status'
    UNION ALL SELECT 'wf_process_draft_audit', 'chk_wf_process_draft_audit_detail'
    UNION ALL SELECT 'wf_process_draft_audit', 'chk_wf_process_draft_audit_transition'
    UNION ALL SELECT 'wf_attachment_quota_guard',
                     'chk_wf_attachment_quota_guard_owner'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_status'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_file_size'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_sha256'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_state_relation'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_storage_deleted'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_cleanup_retry'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'chk_wf_controlled_loop_iteration_no'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'chk_wf_controlled_loop_actor'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'chk_wf_controlled_loop_outcome'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'chk_wf_controlled_loop_decision_value'
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
    UNION ALL
    SELECT 'wf_process_draft_audit', 'fk_wf_process_draft_audit_draft',
           'draft_id', 'wf_process_draft', 'draft_id', 'RESTRICT', 'RESTRICT'
    UNION ALL
    SELECT 'wf_attachment', 'fk_wf_attachment_draft',
           'draft_id', 'wf_process_draft', 'draft_id', 'RESTRICT', 'RESTRICT'
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

    SELECT 'wf_copy_invalid_del_flag', COUNT(*)
    FROM wf_copy
    WHERE del_flag NOT IN ('0', '2')

    UNION ALL

    SELECT 'wf_copy_invalid_source_or_read_state', COUNT(*)
    FROM wf_copy
    WHERE source_type NOT IN ('MANUAL', 'AUTO', 'MANUAL_AUTO')
       OR trigger_type NOT IN ('MANUAL_COMPLETE', 'MANUAL_REJECT', 'MANUAL_RETURN',
           'MANUAL_DELEGATE', 'MANUAL_RESOLVE', 'MANUAL_TRANSFER', 'NODE_ARRIVED',
           'NODE_COMPLETED', 'PROCESS_COMPLETED')
       OR read_status NOT IN ('0', '1')
       OR ((read_status = '0') <> (read_time IS NULL))

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

    SELECT 'wf_process_draft_invalid_row', COUNT(*)
    FROM wf_process_draft d
    WHERE d.draft_id NOT REGEXP
              '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       OR d.owner_user_id <= 0
       OR d.process_definition_version <= 0
       OR d.draft_status NOT IN ('ACTIVE', 'SUBMITTED', 'DELETED')
       OR d.revision_no <= 0
       OR JSON_VALID(d.form_snapshot) = 0
       OR JSON_VALID(d.start_multi_instance_assignments) = 0
       OR JSON_TYPE(d.start_multi_instance_assignments) <> 'ARRAY'
       OR JSON_VALID(d.form_values) = 0
       OR JSON_VALID(d.multi_instance_user_ids) = 0
       OR JSON_TYPE(d.multi_instance_user_ids) <> 'OBJECT'
       OR d.form_snapshot_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR d.form_snapshot_sha256 <> LOWER(SHA2(d.form_snapshot, 256))
       OR (d.source_type = 'TEMPLATE' AND (d.form_id IS NULL OR d.form_id <= 0))
       OR (d.source_type = 'EMBEDDED' AND d.form_id IS NOT NULL)
       OR d.source_type NOT IN ('TEMPLATE', 'EMBEDDED')
       OR d.snapshot_create_time > d.create_time
       OR d.update_time < d.create_time

    UNION ALL

    SELECT 'wf_process_draft_missing_owner', COUNT(*)
    FROM wf_process_draft d
    LEFT JOIN sys_user u ON u.user_id = d.owner_user_id
    WHERE u.user_id IS NULL

    UNION ALL

    SELECT 'wf_process_draft_submitted_history_mismatch', COUNT(*)
    FROM wf_process_draft d
    LEFT JOIN ACT_HI_PROCINST p ON p.PROC_INST_ID_ = d.submitted_process_instance_id
    LEFT JOIN ACT_RE_PROCDEF definition ON definition.ID_ = p.PROC_DEF_ID_
    WHERE d.draft_status = 'SUBMITTED'
      AND (p.PROC_INST_ID_ IS NULL
           OR p.PROC_DEF_ID_ <> d.process_definition_id
           OR definition.ID_ IS NULL
           OR definition.DEPLOYMENT_ID_ <> d.deployment_id
           OR p.START_USER_ID_ <> CAST(d.owner_user_id AS CHAR))

    UNION ALL

    SELECT 'wf_process_draft_audit_invalid_chain', COUNT(*)
    FROM (
        SELECT d.draft_id
        FROM wf_process_draft d
        LEFT JOIN wf_process_draft_audit a ON a.draft_id = d.draft_id
        GROUP BY d.draft_id, d.owner_user_id, d.revision_no,
                 d.draft_status, d.submitted_process_instance_id
        HAVING COUNT(a.audit_id) = 0
            OR MIN(a.to_revision) <> 1
            OR MAX(a.to_revision) <> d.revision_no
            OR COUNT(a.audit_id) <> MAX(a.to_revision)
            OR SUM(a.owner_user_id <> d.owner_user_id) > 0
            OR SUM(a.to_revision = 1 AND a.action_type <> 'CREATED') > 0
            OR SUM(a.to_revision = d.revision_no
                   AND a.to_status <> d.draft_status) > 0
            OR SUM(a.action_type = 'SUBMITTED')
               <> CASE WHEN d.draft_status = 'SUBMITTED' THEN 1 ELSE 0 END
            OR SUM(a.action_type = 'SUBMITTED'
                   AND NOT (a.process_instance_id
                            <=> d.submitted_process_instance_id)) > 0

        UNION

        SELECT current_audit.draft_id
        FROM wf_process_draft_audit current_audit
        LEFT JOIN wf_process_draft_audit previous_audit
          ON previous_audit.draft_id = current_audit.draft_id
         AND previous_audit.to_revision = current_audit.to_revision - 1
        WHERE current_audit.to_revision > 1
          AND (previous_audit.audit_id IS NULL
               OR current_audit.from_revision <> previous_audit.to_revision
               OR current_audit.from_status <> previous_audit.to_status)
    ) invalid_draft_audit

    UNION ALL

    SELECT 'wf_attachment_invalid_row', COUNT(*)
    FROM wf_attachment a
    WHERE a.attachment_status NOT IN ('TEMP', 'DRAFT', 'BOUND', 'EXPIRED', 'DELETED')
       OR a.file_size <= 0
       OR a.sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR (a.attachment_status = 'BOUND'
           AND (a.draft_id IS NOT NULL OR a.process_instance_id IS NULL
                OR a.node_key IS NULL OR a.bound_time IS NULL
                OR a.storage_deleted_time IS NOT NULL))
       OR (a.attachment_status = 'DRAFT'
           AND (a.draft_id IS NULL OR a.process_instance_id IS NOT NULL
                OR a.task_id IS NOT NULL OR a.node_key IS NOT NULL
                OR a.bound_time IS NOT NULL OR a.storage_deleted_time IS NOT NULL))
       OR (a.attachment_status IN ('TEMP', 'EXPIRED', 'DELETED')
           AND (a.draft_id IS NOT NULL OR a.process_instance_id IS NOT NULL OR a.task_id IS NOT NULL
                OR a.node_key IS NOT NULL OR a.bound_time IS NOT NULL))
       OR (a.storage_deleted_time IS NOT NULL
           AND a.attachment_status NOT IN ('EXPIRED', 'DELETED'))

    UNION ALL

    SELECT 'wf_attachment_missing_owner', COUNT(*)
    FROM wf_attachment a
    LEFT JOIN sys_user u ON u.user_id = a.owner_user_id
    WHERE u.user_id IS NULL

    UNION ALL

    SELECT 'wf_attachment_draft_relation_mismatch', COUNT(*)
    FROM wf_attachment a
    LEFT JOIN wf_process_draft d ON d.draft_id = a.draft_id
    WHERE a.attachment_status = 'DRAFT'
      AND (d.draft_id IS NULL OR d.owner_user_id <> a.owner_user_id
           OR d.draft_status <> 'ACTIVE')

    UNION ALL

    SELECT 'wf_attachment_invalid_cleanup_retry', COUNT(*)
    FROM wf_attachment a
    WHERE a.cleanup_retry_count < 0
       OR ((a.cleanup_next_retry_time IS NULL)
           <> (a.cleanup_last_error_code IS NULL))
       OR (a.attachment_status IN ('TEMP', 'DRAFT', 'BOUND')
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

    UNION ALL

    SELECT 'wf_controlled_loop_execution_missing_artifact', COUNT(*)
    FROM wf_controlled_loop_execution e
    LEFT JOIN ACT_RE_PROCDEF p ON p.ID_ = e.process_definition_id
    LEFT JOIN ACT_RE_DEPLOYMENT artifact
      ON artifact.PARENT_DEPLOYMENT_ID_ = e.deploy_id
     AND artifact.CATEGORY_ = 'APPROVAPLAT_WORKFLOW_ARTIFACTS'
    LEFT JOIN ACT_GE_BYTEARRAY resource
      ON resource.DEPLOYMENT_ID_ = artifact.ID_
     AND resource.NAME_ = 'approvaplat/controlled-loops-v1.json'
    WHERE p.ID_ IS NULL
       OR p.DEPLOYMENT_ID_ <> e.deploy_id
       OR artifact.ID_ IS NULL
       OR resource.ID_ IS NULL

    UNION ALL

    SELECT 'wf_controlled_loop_execution_missing_history', COUNT(*)
    FROM wf_controlled_loop_execution e
    LEFT JOIN ACT_HI_PROCINST p ON p.PROC_INST_ID_ = e.process_instance_id
    LEFT JOIN ACT_HI_TASKINST t ON t.ID_ = e.task_id
    WHERE p.PROC_INST_ID_ IS NULL
       OR p.PROC_DEF_ID_ <> e.process_definition_id
       OR t.ID_ IS NULL
       OR t.PROC_INST_ID_ <> e.process_instance_id
       OR t.TASK_DEF_KEY_ <> e.activity_id
       OR t.END_TIME_ IS NULL
       OR t.ASSIGNEE_ <> e.actor_user_id

    UNION ALL

    SELECT 'wf_controlled_loop_execution_iteration_gap', COUNT(*)
    FROM (
        SELECT process_instance_id, activity_id,
               COUNT(*) AS row_count,
               MIN(iteration_no) AS first_iteration,
               MAX(iteration_no) AS last_iteration
        FROM wf_controlled_loop_execution
        GROUP BY process_instance_id, activity_id
        HAVING first_iteration <> 1 OR row_count <> last_iteration
    ) invalid_iteration

    UNION ALL

    SELECT 'wf_controlled_loop_execution_after_exit', COUNT(*)
    FROM wf_controlled_loop_execution exit_round
    JOIN wf_controlled_loop_execution later_round
      ON later_round.process_instance_id = exit_round.process_instance_id
     AND later_round.activity_id = exit_round.activity_id
     AND later_round.iteration_no > exit_round.iteration_no
    WHERE exit_round.outcome = 'EXIT'
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

WITH expected_tables AS (
    SELECT 'wf_participant_resolution_audit' AS table_name
),
actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_participant_resolution_audit'
)
SELECT 'workflow_participant_rule_tables' AS check_name,
       CASE WHEN COUNT(a.table_name) = 1
              AND SUM(a.ENGINE = 'InnoDB') = 1
              AND SUM(a.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 1
            THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('present=', COUNT(a.table_name), ', missing=', COALESCE(
           GROUP_CONCAT(CASE WHEN a.table_name IS NULL THEN e.table_name END
               ORDER BY e.table_name), 'none')) AS detail
FROM expected_tables e
LEFT JOIN actual_tables a ON a.table_name = e.table_name;

WITH expected_columns AS (
    SELECT 'wf_participant_resolution_audit' AS table_name, 'audit_id' AS column_name
    UNION ALL SELECT 'wf_participant_resolution_audit', 'event_type'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'deploy_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'process_definition_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'process_instance_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'task_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'activity_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'rule_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'initiator_user_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'actor_user_id'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'resolved_user_ids'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'resolved_group_ids'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'result_code'
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
SELECT 'workflow_participant_rule_columns' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('missing=', COALESCE(GROUP_CONCAT(CONCAT(table_name, '.', column_name)
           ORDER BY table_name, column_name), 'none')) AS detail
FROM missing_columns;

WITH expected_indexes AS (
    SELECT 'wf_participant_resolution_audit' AS table_name,
           'idx_wf_participant_audit_instance' AS index_name
    UNION ALL SELECT 'wf_participant_resolution_audit', 'idx_wf_participant_audit_task'
    UNION ALL SELECT 'wf_participant_resolution_audit', 'idx_wf_participant_audit_rule_time'
),
missing_indexes AS (
    SELECT e.table_name, e.index_name
    FROM expected_indexes e
    LEFT JOIN information_schema.STATISTICS s
      ON s.TABLE_SCHEMA = DATABASE()
     AND s.TABLE_NAME = e.table_name
     AND s.INDEX_NAME = e.index_name
    WHERE s.INDEX_NAME IS NULL
)
SELECT 'workflow_participant_rule_indexes' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('missing=', COALESCE(GROUP_CONCAT(CONCAT(table_name, '.', index_name)
           ORDER BY table_name, index_name), 'none')) AS detail
FROM missing_indexes;

WITH expected_checks AS (
    SELECT 'chk_wf_participant_audit_event' AS constraint_name
    UNION ALL SELECT 'chk_wf_participant_audit_result'
    UNION ALL SELECT 'chk_wf_participant_audit_relation'
    UNION ALL SELECT 'chk_wf_participant_audit_rule'
    UNION ALL SELECT 'chk_wf_participant_audit_initiator'
    UNION ALL SELECT 'chk_wf_participant_audit_actor'
),
missing_checks AS (
    SELECT e.constraint_name
    FROM expected_checks e
    LEFT JOIN information_schema.TABLE_CONSTRAINTS c
      ON c.CONSTRAINT_SCHEMA = DATABASE()
     AND c.CONSTRAINT_NAME = e.constraint_name
     AND c.CONSTRAINT_TYPE = 'CHECK'
    WHERE c.CONSTRAINT_NAME IS NULL
)
SELECT 'workflow_participant_rule_checks' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('missing=', COALESCE(GROUP_CONCAT(constraint_name ORDER BY constraint_name), 'none')) AS detail
FROM missing_checks;

SELECT 'workflow_participant_audit_retention_foreign_keys' AS check_name,
       CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('unexpected=', COALESCE(GROUP_CONCAT(CONSTRAINT_NAME ORDER BY CONSTRAINT_NAME), 'none')) AS detail
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'wf_participant_resolution_audit'
  AND REFERENCED_TABLE_NAME IS NOT NULL;

WITH integrity_issues AS (
    SELECT 'participant_audit_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_participant_resolution_audit a
    WHERE a.rule_id <= 0
       OR a.initiator_user_id NOT REGEXP '^[1-9][0-9]{0,18}$'
       OR (a.actor_user_id IS NOT NULL
           AND a.actor_user_id NOT REGEXP '^[1-9][0-9]{0,18}$')
       OR (a.event_type = 'START' AND
           (a.task_id IS NOT NULL OR a.activity_id <> ''
            OR (a.result_code = 'ALLOWED' AND a.process_instance_id IS NULL)
            OR (a.result_code = 'DENIED' AND a.process_instance_id IS NOT NULL)))
       OR (a.event_type = 'TASK' AND
           (a.task_id IS NULL OR a.process_instance_id IS NULL OR a.activity_id = ''))
)
SELECT 'workflow_participant_rule_data_integrity' AS check_name,
       CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(
           GROUP_CONCAT(CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
               ORDER BY issue_name), 'none')) AS detail
FROM integrity_issues;

WITH expected_notification_tables AS (
    SELECT 'wf_notification_policy' AS table_name
    UNION ALL SELECT 'wf_notification_preference'
    UNION ALL SELECT 'wf_notification_outbox'
    UNION ALL SELECT 'wf_notification_inbox'
    UNION ALL SELECT 'wf_notification_delivery_audit'
    UNION ALL SELECT 'wf_notification_urge_audit'
), actual_notification_tables AS (
    SELECT table_name, engine, table_collation
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name IN (SELECT table_name FROM expected_notification_tables)
)
SELECT
    'workflow_notification_tables' AS check_name,
    CASE WHEN COUNT(actual.table_name) = 6
              AND SUM(actual.engine = 'InnoDB') = 6
              AND SUM(actual.table_collation = 'utf8mb4_unicode_ci') = 6
         THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('present=', COUNT(actual.table_name), ', missing=', COALESCE(
        GROUP_CONCAT(CASE WHEN actual.table_name IS NULL THEN expected.table_name END
            ORDER BY expected.table_name SEPARATOR ','), 'none')) AS detail
FROM expected_notification_tables expected
LEFT JOIN actual_notification_tables actual ON actual.table_name = expected.table_name;

WITH notification_issues AS (
    SELECT 'policy_invalid' AS issue_name, COUNT(*) AS issue_count
    FROM wf_notification_policy
    WHERE scope_type NOT IN ('DEFAULT', 'PROCESS', 'NODE')
       OR status NOT IN ('ENABLED', 'DISABLED')
       OR channels NOT IN ('INBOX', 'EMAIL', 'SMS', 'INBOX,EMAIL', 'INBOX,SMS',
                           'EMAIL,SMS', 'INBOX,EMAIL,SMS')
       OR ((channels LIKE '%SMS%' AND (sms_template_id IS NULL OR sms_template_id = ''))
           OR (channels NOT LIKE '%SMS%' AND sms_template_id IS NOT NULL))
       OR max_attempts NOT BETWEEN 1 AND 20
       OR (scope_type = 'DEFAULT' AND (process_definition_key IS NOT NULL OR task_definition_key IS NOT NULL))
       OR (scope_type = 'PROCESS' AND (process_definition_key IS NULL OR task_definition_key IS NOT NULL))
       OR (scope_type = 'NODE' AND (process_definition_key IS NULL OR task_definition_key IS NULL))

    UNION ALL
    SELECT 'outbox_invalid', COUNT(*)
    FROM wf_notification_outbox o
    LEFT JOIN sys_user u ON u.user_id = o.recipient_user_id
    WHERE u.user_id IS NULL OR o.idempotency_key NOT REGEXP '^[0-9a-f]{64}$'
       OR o.source_type NOT IN ('APPROVAL', 'SLA', 'BPMN_EVENT')
       OR o.source_id = ''
       OR o.channel NOT IN ('INBOX', 'EMAIL', 'SMS')
       OR ((o.channel = 'SMS' AND (o.sms_template_id IS NULL OR o.sms_template_id = ''))
           OR (o.channel <> 'SMS' AND o.sms_template_id IS NOT NULL))
       OR o.status NOT IN ('PENDING', 'RETRYING', 'DELIVERING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED')
       OR o.attempt_count > o.max_attempts
       OR o.delivery_cycle < 1
       OR o.total_attempt_count < o.attempt_count
       OR (o.status = 'DELIVERING' AND (o.lease_owner IS NULL OR o.lease_expires_at IS NULL))
       OR (o.status <> 'DELIVERING' AND (o.lease_owner IS NOT NULL OR o.lease_expires_at IS NOT NULL))

    UNION ALL
    SELECT 'inbox_invalid', COUNT(*)
    FROM wf_notification_inbox i
    LEFT JOIN wf_notification_outbox o ON o.outbox_id = i.outbox_id
    WHERE o.outbox_id IS NULL OR o.channel <> 'INBOX' OR o.recipient_user_id <> i.recipient_user_id
       OR i.read_status NOT IN ('UNREAD', 'READ')
       OR (i.read_status = 'UNREAD' AND i.read_time IS NOT NULL)
       OR (i.read_status = 'READ' AND i.read_time IS NULL)

    UNION ALL
    SELECT 'delivery_sequence_invalid', COUNT(*)
    FROM wf_notification_delivery_audit a
    LEFT JOIN wf_notification_outbox o ON o.outbox_id = a.outbox_id
    WHERE o.outbox_id IS NULL
       OR a.delivery_cycle < 1
       OR a.total_attempt_no < a.attempt_no
       OR a.delivery_cycle > o.delivery_cycle
       OR a.total_attempt_no > o.total_attempt_count
       OR (a.delivery_cycle = o.delivery_cycle AND a.attempt_no > o.attempt_count)

    UNION ALL
    SELECT 'sla_notification_source_invalid', COUNT(*)
    FROM wf_notification_outbox o
    LEFT JOIN wf_task_sla_audit a
      ON o.source_id = CAST(a.audit_id AS CHAR CHARACTER SET ascii) COLLATE ascii_bin
    LEFT JOIN wf_notification_inbox i ON i.outbox_id = o.outbox_id
    WHERE o.source_type = 'SLA'
      AND (a.audit_id IS NULL OR i.notification_id IS NULL
           OR o.channel <> 'INBOX' OR o.status <> 'PROCESSED'
           OR o.event_type <> a.action_type
           OR a.action_type NOT IN ('REMINDER', 'ESCALATE'))

    UNION ALL
    SELECT 'bpmn_event_notification_source_invalid', COUNT(*)
    FROM wf_notification_outbox o
    LEFT JOIN wf_bpmn_event_audit a
      ON o.source_id = CAST(a.audit_id AS CHAR CHARACTER SET ascii) COLLATE ascii_bin
    LEFT JOIN wf_notification_inbox i ON i.outbox_id = o.outbox_id
    WHERE o.source_type = 'BPMN_EVENT'
      AND (a.audit_id IS NULL OR i.notification_id IS NULL
           OR o.channel <> 'INBOX' OR o.status <> 'PROCESSED'
           OR o.event_type <> a.event_type)
)
SELECT 'workflow_notification_integrity' AS check_name,
       CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
       CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
           CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
           ORDER BY issue_name SEPARATOR ','), 'none')) AS detail
FROM notification_issues;

SELECT
    'workflow_sla_tables' AS check_name,
    CASE WHEN COUNT(*) = 4 AND SUM(ENGINE = 'InnoDB') = 4 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('found=', COUNT(*), ', innodb=', SUM(ENGINE = 'InnoDB'), ', expected=4') AS detail
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('wf_business_calendar', 'wf_business_calendar_day',
                     'wf_task_sla_execution', 'wf_task_sla_audit');

SELECT
    'workflow_sla_constraints' AS check_name,
    CASE WHEN COUNT(*) = 18 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('found=', COUNT(*), ', expected=18') AS detail
FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE()
  AND table_name IN ('wf_business_calendar', 'wf_business_calendar_day',
                     'wf_task_sla_execution', 'wf_task_sla_audit')
  AND constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'CHECK', 'FOREIGN KEY');

SELECT
    'workflow_sla_foreign_keys' AS check_name,
    CASE WHEN COUNT(*) = 2 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('found=', COUNT(*), ', expected=2') AS detail
FROM information_schema.referential_constraints
WHERE constraint_schema = DATABASE()
  AND constraint_name IN ('fk_wf_business_calendar_day_calendar',
                          'fk_wf_task_sla_audit_execution');

WITH sla_issues AS
(
    SELECT 'sla_calendar_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_business_calendar c
    WHERE c.calendar_key NOT REGEXP '^[A-Z][A-Z0-9_.-]{1,63}$'
       OR c.status NOT IN ('ENABLED', 'DISABLED')
       OR c.work_start >= c.work_end
       OR c.working_days NOT REGEXP '^[1-7](,[1-7]){0,6}$'

    UNION ALL

    SELECT 'sla_execution_invalid_row', COUNT(*)
    FROM wf_task_sla_execution e
    LEFT JOIN ACT_RE_PROCDEF definition ON definition.ID_ = e.process_definition_id
    LEFT JOIN ACT_RE_DEPLOYMENT artifact
      ON artifact.PARENT_DEPLOYMENT_ID_ = e.deployment_id
     AND artifact.CATEGORY_ = 'APPROVAPLAT_WORKFLOW_ARTIFACTS'
    LEFT JOIN ACT_GE_BYTEARRAY resource
      ON resource.DEPLOYMENT_ID_ = artifact.ID_
     AND resource.NAME_ = 'approvaplat/task-sla-v1.json'
    WHERE definition.ID_ IS NULL OR definition.DEPLOYMENT_ID_ <> e.deployment_id
       OR artifact.ID_ IS NULL OR resource.ID_ IS NULL
       OR e.status NOT IN ('ACTIVE', 'COMPLETED', 'ESCALATED')
       OR e.started_at > e.reminder_due_at
       OR e.reminder_due_at >= e.escalation_due_at
       OR e.reminders_sent < 0 OR e.paused_millis < 0 OR e.revision < 0

    UNION ALL

    SELECT 'sla_audit_invalid_row', COUNT(*)
    FROM wf_task_sla_audit a
    LEFT JOIN wf_task_sla_execution e ON e.sla_execution_id = a.sla_execution_id
    WHERE e.sla_execution_id IS NULL
       OR a.action_type NOT IN ('CREATE', 'ASSIGN', 'REMINDER', 'ESCALATE',
                                'COMPLETE', 'PAUSE', 'RESUME')
       OR a.action_ordinal < 0
)
SELECT
    'workflow_sla_data_integrity' AS check_name,
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
FROM sla_issues;

SELECT
    'workflow_bpmn_event_tables' AS check_name,
    CASE WHEN COUNT(*) = 2 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('found=', COUNT(*), ', expected=2') AS detail
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('wf_bpmn_event_code', 'wf_bpmn_event_audit');

SELECT
    'workflow_bpmn_event_constraints' AS check_name,
    CASE WHEN COUNT(*) = 13 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('found=', COUNT(*), ', expected=13') AS detail
FROM information_schema.table_constraints
WHERE constraint_schema = DATABASE()
  AND table_name IN ('wf_bpmn_event_code', 'wf_bpmn_event_audit')
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

    SELECT 'event_audit_missing_process_definition', COUNT(*)
    FROM wf_bpmn_event_audit audit
    LEFT JOIN ACT_RE_PROCDEF definition ON definition.ID_ = audit.process_definition_id
    WHERE definition.ID_ IS NULL OR definition.DEPLOYMENT_ID_ <> audit.deployment_id
)
SELECT
    'workflow_bpmn_event_data_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count)) AS detail
FROM event_issues;

WITH expected_tables AS (
    SELECT 'wf_integration_credential' AS table_name
    UNION ALL SELECT 'wf_runtime_event_request'
    UNION ALL SELECT 'wf_collaboration_channel'
    UNION ALL SELECT 'wf_collaboration_message'
    UNION ALL SELECT 'wf_collaboration_outbox'
    UNION ALL SELECT 'wf_collaboration_message_audit'
), actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN (SELECT table_name FROM expected_tables)
)
SELECT
    'workflow_runtime_integration_tables' AS check_name,
    CASE WHEN COUNT(actual.table_name) = 6
              AND SUM(actual.ENGINE = 'InnoDB') = 6
              AND SUM(actual.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 6
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
    UNION ALL SELECT 'wf_collaboration_channel', 'channel_id'
    UNION ALL SELECT 'wf_collaboration_channel', 'target_process_definition_key'
    UNION ALL SELECT 'wf_collaboration_channel', 'correlation_type'
    UNION ALL SELECT 'wf_collaboration_channel', 'correlation_value'
    UNION ALL SELECT 'wf_collaboration_channel', 'outbound_sequence'
    UNION ALL SELECT 'wf_collaboration_channel', 'inbound_sequence'
    UNION ALL SELECT 'wf_collaboration_channel', 'revision_no'
    UNION ALL SELECT 'wf_collaboration_message', 'message_id'
    UNION ALL SELECT 'wf_collaboration_message', 'credential_id'
    UNION ALL SELECT 'wf_collaboration_message', 'actor_user_id'
    UNION ALL SELECT 'wf_collaboration_message', 'channel_id'
    UNION ALL SELECT 'wf_collaboration_message', 'sequence_no'
    UNION ALL SELECT 'wf_collaboration_message', 'message_name'
    UNION ALL SELECT 'wf_collaboration_message', 'source_process_definition_key'
    UNION ALL SELECT 'wf_collaboration_message', 'target_process_definition_key'
    UNION ALL SELECT 'wf_collaboration_message', 'correlation_key'
    UNION ALL SELECT 'wf_collaboration_message', 'target_process_instance_id'
    UNION ALL SELECT 'wf_collaboration_message', 'matched_process_instance_id'
    UNION ALL SELECT 'wf_collaboration_message', 'target_execution_id'
    UNION ALL SELECT 'wf_collaboration_message', 'variables_json'
    UNION ALL SELECT 'wf_collaboration_message', 'payload_sha256'
    UNION ALL SELECT 'wf_collaboration_message', 'status'
    UNION ALL SELECT 'wf_collaboration_message', 'attempt_count'
    UNION ALL SELECT 'wf_collaboration_message', 'max_attempts'
    UNION ALL SELECT 'wf_collaboration_message', 'compensation_count'
    UNION ALL SELECT 'wf_collaboration_message', 'revision_no'
    UNION ALL SELECT 'wf_collaboration_message', 'last_error_code'
    UNION ALL SELECT 'wf_collaboration_message', 'last_error_summary'
    UNION ALL SELECT 'wf_collaboration_message', 'create_time'
    UNION ALL SELECT 'wf_collaboration_message', 'next_attempt_time'
    UNION ALL SELECT 'wf_collaboration_message', 'complete_time'
    UNION ALL SELECT 'wf_collaboration_outbox', 'message_id'
    UNION ALL SELECT 'wf_collaboration_outbox', 'channel_id'
    UNION ALL SELECT 'wf_collaboration_outbox', 'sequence_no'
    UNION ALL SELECT 'wf_collaboration_outbox', 'source_process_instance_id'
    UNION ALL SELECT 'wf_collaboration_outbox', 'source_execution_id'
    UNION ALL SELECT 'wf_collaboration_outbox', 'message_name'
    UNION ALL SELECT 'wf_collaboration_outbox', 'target_process_definition_key'
    UNION ALL SELECT 'wf_collaboration_outbox', 'correlation_key'
    UNION ALL SELECT 'wf_collaboration_outbox', 'endpoint_id'
    UNION ALL SELECT 'wf_collaboration_outbox', 'endpoint_revision'
    UNION ALL SELECT 'wf_collaboration_outbox', 'delivery_config_json'
    UNION ALL SELECT 'wf_collaboration_outbox', 'variables_json'
    UNION ALL SELECT 'wf_collaboration_outbox', 'payload_sha256'
    UNION ALL SELECT 'wf_collaboration_outbox', 'status'
    UNION ALL SELECT 'wf_collaboration_outbox', 'attempt_count'
    UNION ALL SELECT 'wf_collaboration_outbox', 'max_attempts'
    UNION ALL SELECT 'wf_collaboration_outbox', 'lease_owner'
    UNION ALL SELECT 'wf_collaboration_outbox', 'lease_until'
    UNION ALL SELECT 'wf_collaboration_outbox', 'next_attempt_time'
    UNION ALL SELECT 'wf_collaboration_outbox', 'complete_time'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'audit_id'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'message_id'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'direction'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'action'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'actor_type'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'actor_id'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'from_status'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'to_status'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'attempt_no'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'create_time'
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
    UNION ALL SELECT 'wf_collaboration_message', 'idx_wf_collab_target'
    UNION ALL SELECT 'wf_collaboration_message', 'idx_wf_collab_status'
    UNION ALL SELECT 'wf_collaboration_message', 'uk_wf_collab_message_sequence'
    UNION ALL SELECT 'wf_collaboration_channel', 'uk_wf_collab_channel_target'
    UNION ALL SELECT 'wf_collaboration_outbox', 'uk_wf_collab_outbox_sequence'
    UNION ALL SELECT 'wf_collaboration_outbox', 'uk_wf_collab_outbox_source'
    UNION ALL SELECT 'wf_collaboration_outbox', 'idx_wf_collab_outbox_due'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'idx_wf_collab_audit_message'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'idx_wf_collab_audit_status'
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
    UNION ALL SELECT 'wf_collaboration_message', 'chk_wf_collab_id'
    UNION ALL SELECT 'wf_collaboration_message', 'chk_wf_collab_correlation'
    UNION ALL SELECT 'wf_collaboration_message', 'chk_wf_collab_status'
    UNION ALL SELECT 'wf_collaboration_message', 'chk_wf_collab_attempts'
    UNION ALL SELECT 'wf_collaboration_message', 'chk_wf_collab_compensation'
    UNION ALL SELECT 'wf_collaboration_message', 'chk_wf_collab_completion'
    UNION ALL SELECT 'wf_collaboration_channel', 'chk_wf_collab_channel_id'
    UNION ALL SELECT 'wf_collaboration_channel', 'chk_wf_collab_channel_type'
    UNION ALL SELECT 'wf_collaboration_channel', 'chk_wf_collab_channel_sequence'
    UNION ALL SELECT 'wf_collaboration_outbox', 'chk_wf_collab_outbox_id'
    UNION ALL SELECT 'wf_collaboration_outbox', 'chk_wf_collab_outbox_hash'
    UNION ALL SELECT 'wf_collaboration_outbox', 'chk_wf_collab_outbox_status'
    UNION ALL SELECT 'wf_collaboration_outbox', 'chk_wf_collab_outbox_attempts'
    UNION ALL SELECT 'wf_collaboration_outbox', 'chk_wf_collab_outbox_lease'
    UNION ALL SELECT 'wf_collaboration_outbox', 'chk_wf_collab_outbox_completion'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'chk_wf_collab_audit_direction'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'chk_wf_collab_audit_actor'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'chk_wf_collab_audit_attempt'
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
    CASE WHEN COUNT(*) = 5 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('matching=', COUNT(*), ', expected=5') AS detail
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'wf_runtime_event_request'
        AND CONSTRAINT_NAME = 'fk_wf_runtime_event_credential')
       OR (TABLE_NAME = 'wf_collaboration_message'
        AND CONSTRAINT_NAME = 'fk_wf_collab_credential')
       OR (TABLE_NAME = 'wf_collaboration_message'
        AND CONSTRAINT_NAME = 'fk_wf_collab_channel')
       OR (TABLE_NAME = 'wf_collaboration_outbox'
        AND CONSTRAINT_NAME = 'fk_wf_collab_outbox_channel')
       OR (TABLE_NAME = 'wf_collaboration_outbox'
        AND CONSTRAINT_NAME = 'fk_wf_collab_outbox_endpoint'))
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

    UNION ALL

    SELECT 'collaboration_message_invalid_row', COUNT(*)
    FROM wf_collaboration_message
    WHERE payload_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR status NOT IN ('RECEIVED', 'RETRYING', 'PROCESSED', 'DEAD_LETTER')
       OR attempt_count NOT BETWEEN 0 AND max_attempts
       OR max_attempts NOT BETWEEN 1 AND 20
       OR sequence_no <= 0
       OR compensation_count < 0 OR revision_no < 0
       OR ((correlation_key IS NULL) = (target_process_instance_id IS NULL))
       OR (status IN ('PROCESSED', 'DEAD_LETTER') AND complete_time IS NULL)

    UNION ALL

    SELECT 'collaboration_message_missing_credential', COUNT(*)
    FROM wf_collaboration_message message
    LEFT JOIN wf_integration_credential credential
      ON credential.credential_id = message.credential_id
    WHERE credential.credential_id IS NULL

    UNION ALL

    SELECT 'collaboration_channel_invalid_row', COUNT(*)
    FROM wf_collaboration_channel
    WHERE channel_id NOT REGEXP '^[0-9a-f]{64}$'
       OR correlation_type NOT IN ('BUSINESS_KEY', 'PROCESS_INSTANCE')
       OR outbound_sequence < 0 OR inbound_sequence < 0 OR revision_no < 0

    UNION ALL

    SELECT 'collaboration_outbox_invalid_row', COUNT(*)
    FROM wf_collaboration_outbox
    WHERE payload_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR status NOT IN ('PENDING', 'DELIVERING', 'RETRYING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED')
       OR attempt_count NOT BETWEEN 0 AND max_attempts
       OR max_attempts NOT BETWEEN 1 AND 20 OR sequence_no <= 0
       OR ((lease_owner IS NULL) <> (lease_until IS NULL))
       OR (status IN ('PROCESSED', 'DEAD_LETTER', 'CANCELLED') AND complete_time IS NULL)

    UNION ALL

    SELECT 'collaboration_outbox_missing_reference', COUNT(*)
    FROM wf_collaboration_outbox outbox
    LEFT JOIN wf_collaboration_channel channel ON channel.channel_id = outbox.channel_id
    LEFT JOIN wf_connector_endpoint endpoint ON endpoint.endpoint_id = outbox.endpoint_id
    WHERE channel.channel_id IS NULL OR endpoint.endpoint_id IS NULL

    UNION ALL

    SELECT 'collaboration_audit_invalid_row', COUNT(*)
    FROM wf_collaboration_message_audit
    WHERE direction NOT IN ('INBOUND', 'OUTBOUND')
       OR actor_type NOT IN ('INTEGRATION', 'SYSTEM', 'USER')
       OR attempt_no < 0
)
SELECT
    'workflow_runtime_integration_data_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name SEPARATOR ','), 'none')) AS detail
FROM integrity_issues;

-- 扩展注册表与 HTTP/SQL 连接器属于独立发布域，单独冻结完整结构。
WITH expected_tables AS (
    SELECT 'wf_bpmn_extension' AS table_name
    UNION ALL SELECT 'wf_bpmn_extension_version'
    UNION ALL SELECT 'wf_connector_endpoint'
    UNION ALL SELECT 'wf_connector_invocation'
    UNION ALL SELECT 'wf_sql_datasource'
),
actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME IN ('wf_bpmn_extension', 'wf_bpmn_extension_version',
                         'wf_connector_endpoint', 'wf_connector_invocation',
                         'wf_sql_datasource')
)
SELECT
    'workflow_extension_tables' AS check_name,
    CASE
        WHEN COUNT(a.table_name) = 5
         AND SUM(a.ENGINE = 'InnoDB') = 5
         AND SUM(a.TABLE_COLLATION = 'utf8mb4_unicode_ci') = 5
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
                         'wf_connector_endpoint', 'wf_connector_invocation',
                         'wf_sql_datasource')
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

    SELECT 'sql_datasource_invalid_row', COUNT(*)
    FROM wf_sql_datasource datasource
    WHERE datasource.revision_no <= 0
       OR datasource.status NOT IN ('ENABLED', 'DISABLED')
       OR datasource.connection_type <> 'MYSQL'
       OR datasource.checksum NOT REGEXP '^[0-9a-f]{64}$'
       OR JSON_VALID(datasource.allowed_tables) = 0
       OR (datasource.update_time IS NOT NULL
           AND datasource.update_time < datasource.create_time)
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
