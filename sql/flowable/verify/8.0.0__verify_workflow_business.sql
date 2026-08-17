-- 工作流 8.1.0 目标业务结构只读验收脚本。
-- 所有检查都应返回 PASS；本脚本不会创建、修改或删除数据。

SELECT
    'workflow_schema_table_counts' AS check_name,
    CASE
        WHEN COUNT(*) = 94
         AND SUM(LEFT(UPPER(TABLE_NAME), 4) <> 'ACT_'
                 AND LEFT(UPPER(TABLE_NAME), 4) <> 'FLW_'
                 AND LEFT(UPPER(TABLE_NAME), 3) <> 'WF_'
                 AND LEFT(UPPER(TABLE_NAME), 5) <> 'QRTZ_') = 20
         AND SUM(LEFT(UPPER(TABLE_NAME), 5) = 'QRTZ_') = 11
         AND SUM(LEFT(UPPER(TABLE_NAME), 4) IN ('ACT_', 'FLW_')) = 36
         AND SUM(LEFT(UPPER(TABLE_NAME), 3) = 'WF_') = 27
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
    SELECT expected.table_name
    FROM expected_dmn_tables expected
    LEFT JOIN information_schema.TABLES actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND UPPER(actual.TABLE_NAME) = expected.table_name
     AND actual.ENGINE = 'InnoDB'
    WHERE actual.TABLE_NAME IS NULL
)
SELECT
    'flowable_dmn_table_presence' AS check_name,
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
    UNION ALL SELECT 'wf_model_save_idempotency'
    UNION ALL SELECT 'wf_designer_preference'
    UNION ALL SELECT 'wf_participant_resolution_audit'
    UNION ALL SELECT 'wf_process_draft_audit'
    UNION ALL SELECT 'wf_connector_invocation'
    UNION ALL SELECT 'wf_notification_delivery_audit'
    UNION ALL SELECT 'wf_notification_urge_audit'
),
unexpected_retired_tables AS (
    SELECT retired.table_name
    FROM retired_workflow_tables retired
    JOIN information_schema.TABLES actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = retired.table_name
)
SELECT
    'workflow_retired_table_absence' AS check_name,
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
    SELECT deployment.ID_, deployment.KEY_, deployment.CATEGORY_, deployment.PARENT_DEPLOYMENT_ID_
    FROM ACT_RE_DEPLOYMENT deployment
    WHERE deployment.CATEGORY_ = 'APPROVAPLAT_WORKFLOW_ARTIFACTS'
       OR deployment.KEY_ LIKE 'approvaplat-artifacts:%'
),
artifact_issues AS (
    SELECT 'invalid_identity' AS issue_name, COUNT(*) AS issue_count
    FROM artifact_deployments artifact
    WHERE artifact.CATEGORY_ <> 'APPROVAPLAT_WORKFLOW_ARTIFACTS'
       OR artifact.PARENT_DEPLOYMENT_ID_ IS NULL
       OR artifact.KEY_ <> CONCAT('approvaplat-artifacts:', artifact.PARENT_DEPLOYMENT_ID_)
    UNION ALL
    SELECT 'missing_parent', COUNT(*)
    FROM artifact_deployments artifact
    LEFT JOIN ACT_RE_DEPLOYMENT parent ON parent.ID_ = artifact.PARENT_DEPLOYMENT_ID_
    WHERE parent.ID_ IS NULL
    UNION ALL
    SELECT 'duplicate_parent', COUNT(*)
    FROM (
        SELECT PARENT_DEPLOYMENT_ID_
        FROM artifact_deployments
        GROUP BY PARENT_DEPLOYMENT_ID_
        HAVING COUNT(*) <> 1
    ) duplicate_parent
    UNION ALL
    SELECT 'executable_definition', COUNT(*)
    FROM artifact_deployments artifact
    JOIN ACT_RE_PROCDEF definition ON definition.DEPLOYMENT_ID_ = artifact.ID_
    UNION ALL
    SELECT 'missing_resource', COUNT(*)
    FROM artifact_deployments artifact
    CROSS JOIN expected_artifact_resources expected
    LEFT JOIN ACT_GE_BYTEARRAY resource
      ON resource.DEPLOYMENT_ID_ = artifact.ID_
     AND resource.NAME_ = expected.resource_name
    WHERE resource.ID_ IS NULL
    UNION ALL
    SELECT 'unexpected_resource', COUNT(*)
    FROM ACT_GE_BYTEARRAY resource
    JOIN artifact_deployments artifact ON artifact.ID_ = resource.DEPLOYMENT_ID_
    LEFT JOIN expected_artifact_resources expected ON expected.resource_name = resource.NAME_
    WHERE expected.resource_name IS NULL
    UNION ALL
    SELECT 'invalid_json', COUNT(*)
    FROM ACT_GE_BYTEARRAY resource
    JOIN artifact_deployments artifact ON artifact.ID_ = resource.DEPLOYMENT_ID_
    JOIN expected_artifact_resources expected ON expected.resource_name = resource.NAME_
    WHERE resource.BYTES_ IS NULL
       OR JSON_VALID(CONVERT(resource.BYTES_ USING utf8mb4)) = 0
)
SELECT
    'workflow_deployment_artifact_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name), 'none')) AS detail
FROM artifact_issues;

WITH expected_tables AS (
    SELECT 'wf_category' AS table_name
    UNION ALL SELECT 'wf_form'
    UNION ALL SELECT 'wf_controlled_loop_execution'
    UNION ALL SELECT 'wf_bpmn_extension'
    UNION ALL SELECT 'wf_bpmn_extension_version'
    UNION ALL SELECT 'wf_business_calendar'
    UNION ALL SELECT 'wf_business_calendar_day'
    UNION ALL SELECT 'wf_task_sla_execution'
    UNION ALL SELECT 'wf_task_sla_audit'
    UNION ALL SELECT 'wf_connector_endpoint'
    UNION ALL SELECT 'wf_sql_datasource'
    UNION ALL SELECT 'wf_integration_credential'
    UNION ALL SELECT 'wf_runtime_event_request'
    UNION ALL SELECT 'wf_collaboration_channel'
    UNION ALL SELECT 'wf_collaboration_message'
    UNION ALL SELECT 'wf_collaboration_outbox'
    UNION ALL SELECT 'wf_collaboration_message_audit'
    UNION ALL SELECT 'wf_copy'
    UNION ALL SELECT 'wf_process_draft'
    UNION ALL SELECT 'wf_attachment_quota_guard'
    UNION ALL SELECT 'wf_attachment'
    UNION ALL SELECT 'wf_bpmn_event_code'
    UNION ALL SELECT 'wf_bpmn_event_audit'
    UNION ALL SELECT 'wf_notification_policy'
    UNION ALL SELECT 'wf_notification_preference'
    UNION ALL SELECT 'wf_notification_outbox'
    UNION ALL SELECT 'wf_notification_inbox'
),
actual_tables AS (
    SELECT TABLE_NAME AS table_name, ENGINE, TABLE_COLLATION
    FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME LIKE 'wf\_%'
),
table_issues AS (
    SELECT 'missing' AS issue_type, expected.table_name
    FROM expected_tables expected
    LEFT JOIN actual_tables actual ON actual.table_name = expected.table_name
    WHERE actual.table_name IS NULL
    UNION ALL
    SELECT 'unexpected', actual.table_name
    FROM actual_tables actual
    LEFT JOIN expected_tables expected ON expected.table_name = actual.table_name
    WHERE expected.table_name IS NULL
    UNION ALL
    SELECT 'invalid_engine_or_collation', actual.table_name
    FROM actual_tables actual
    WHERE actual.ENGINE <> 'InnoDB' OR actual.TABLE_COLLATION <> 'utf8mb4_unicode_ci'
)
SELECT
    'workflow_business_tables' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', COALESCE(GROUP_CONCAT(CONCAT(issue_type, ':', table_name)
        ORDER BY issue_type, table_name), 'none')) AS detail
FROM table_issues;

WITH expected_columns AS (
    SELECT 'wf_category' AS table_name, 'category_id' AS column_name
    UNION ALL SELECT 'wf_category', 'active_code'
    UNION ALL SELECT 'wf_form', 'form_id'
    UNION ALL SELECT 'wf_form', 'content'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'process_instance_id'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'task_id'
    UNION ALL SELECT 'wf_bpmn_extension', 'extension_key'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'config_schema'
    UNION ALL SELECT 'wf_business_calendar', 'calendar_key'
    UNION ALL SELECT 'wf_business_calendar_day', 'calendar_date'
    UNION ALL SELECT 'wf_task_sla_execution', 'task_id'
    UNION ALL SELECT 'wf_task_sla_execution', 'status'
    UNION ALL SELECT 'wf_task_sla_audit', 'action_ordinal'
    UNION ALL SELECT 'wf_connector_endpoint', 'endpoint_key'
    UNION ALL SELECT 'wf_connector_endpoint', 'revision_no'
    UNION ALL SELECT 'wf_sql_datasource', 'datasource_key'
    UNION ALL SELECT 'wf_sql_datasource', 'allowed_tables'
    UNION ALL SELECT 'wf_integration_credential', 'token_hash'
    UNION ALL SELECT 'wf_integration_credential', 'rate_limit_per_minute'
    UNION ALL SELECT 'wf_integration_credential', 'revision_no'
    UNION ALL SELECT 'wf_integration_credential', 'last_used_at'
    UNION ALL SELECT 'wf_runtime_event_request', 'request_id'
    UNION ALL SELECT 'wf_runtime_event_request', 'credential_id'
    UNION ALL SELECT 'wf_collaboration_channel', 'channel_id'
    UNION ALL SELECT 'wf_collaboration_message', 'message_id'
    UNION ALL SELECT 'wf_collaboration_outbox', 'message_id'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'audit_id'
    UNION ALL SELECT 'wf_copy', 'copy_event_id'
    UNION ALL SELECT 'wf_process_draft', 'draft_id'
    UNION ALL SELECT 'wf_process_draft', 'revision_no'
    UNION ALL SELECT 'wf_attachment_quota_guard', 'owner_user_id'
    UNION ALL SELECT 'wf_attachment', 'attachment_id'
    UNION ALL SELECT 'wf_attachment', 'cleanup_claim_token'
    UNION ALL SELECT 'wf_attachment', 'cleanup_lease_until'
    UNION ALL SELECT 'wf_bpmn_event_code', 'event_code'
    UNION ALL SELECT 'wf_bpmn_event_audit', 'idempotency_key'
    UNION ALL SELECT 'wf_notification_policy', 'scope_key'
    UNION ALL SELECT 'wf_notification_preference', 'user_id'
    UNION ALL SELECT 'wf_notification_outbox', 'idempotency_key'
    UNION ALL SELECT 'wf_notification_outbox', 'source_type'
    UNION ALL SELECT 'wf_notification_outbox', 'source_id'
    UNION ALL SELECT 'wf_notification_inbox', 'notification_key'
    UNION ALL SELECT 'wf_notification_inbox', 'source_type'
    UNION ALL SELECT 'wf_notification_inbox', 'source_id'
),
missing_columns AS (
    SELECT expected.table_name, expected.column_name
    FROM expected_columns expected
    LEFT JOIN information_schema.COLUMNS actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.COLUMN_NAME = expected.column_name
    WHERE actual.COLUMN_NAME IS NULL
),
forbidden_columns AS (
    SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_integration_credential'
      AND COLUMN_NAME IN ('rate_window_start', 'rate_window_count')
)
SELECT
    'workflow_business_columns' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', COALESCE(GROUP_CONCAT(CONCAT(issue_type, ':', table_name, '.', column_name)
        ORDER BY issue_type, table_name, column_name), 'none')) AS detail
FROM (
    SELECT 'missing' AS issue_type, table_name, column_name FROM missing_columns
    UNION ALL
    SELECT 'forbidden' AS issue_type, table_name, column_name FROM forbidden_columns
) column_issues;

WITH expected_indexes AS (
    SELECT 'wf_category' AS table_name, 'uk_wf_category_active_code' AS index_name
    UNION ALL SELECT 'wf_bpmn_extension', 'uk_wf_bpmn_extension_key'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'uk_wf_bpmn_extension_version'
    UNION ALL SELECT 'wf_task_sla_execution', 'uk_wf_task_sla_execution_task'
    UNION ALL SELECT 'wf_task_sla_execution', 'idx_wf_task_sla_execution_retention'
    UNION ALL SELECT 'wf_connector_endpoint', 'uk_wf_connector_endpoint_key'
    UNION ALL SELECT 'wf_sql_datasource', 'uk_wf_sql_datasource_key'
    UNION ALL SELECT 'wf_integration_credential', 'uk_wf_integration_token_prefix'
    UNION ALL SELECT 'wf_runtime_event_request', 'idx_wf_runtime_event_status'
    UNION ALL SELECT 'wf_runtime_event_request', 'idx_wf_runtime_event_retention'
    UNION ALL SELECT 'wf_collaboration_message', 'uk_wf_collab_message_sequence'
    UNION ALL SELECT 'wf_collaboration_message', 'idx_wf_collab_message_retention'
    UNION ALL SELECT 'wf_collaboration_outbox', 'idx_wf_collab_outbox_due'
    UNION ALL SELECT 'wf_collaboration_outbox', 'idx_wf_collab_outbox_retention'
    UNION ALL SELECT 'wf_collaboration_message_audit', 'idx_wf_collab_audit_message'
    UNION ALL SELECT 'wf_copy', 'uk_wf_copy_event_user'
    UNION ALL SELECT 'wf_copy', 'idx_wf_copy_retention'
    UNION ALL SELECT 'wf_process_draft', 'idx_wf_process_draft_owner_status_time'
    UNION ALL SELECT 'wf_process_draft', 'idx_wf_process_draft_submitted_retention'
    UNION ALL SELECT 'wf_process_draft', 'idx_wf_process_draft_deleted_retention'
    UNION ALL SELECT 'wf_attachment', 'uk_wf_attachment_storage_key'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_cleanup_due'
    UNION ALL SELECT 'wf_attachment', 'idx_wf_attachment_metadata_retention'
    UNION ALL SELECT 'wf_bpmn_event_audit', 'uk_wf_bpmn_event_audit_idempotency'
    UNION ALL SELECT 'wf_bpmn_event_audit', 'idx_wf_bpmn_event_audit_retention'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'idx_wf_controlled_loop_retention'
    UNION ALL SELECT 'wf_notification_policy', 'uk_wf_notification_policy_scope'
    UNION ALL SELECT 'wf_notification_outbox', 'idx_wf_notification_outbox_due'
    UNION ALL SELECT 'wf_notification_outbox', 'idx_wf_notification_outbox_retention'
    UNION ALL SELECT 'wf_notification_inbox', 'uk_wf_notification_inbox_notification'
    UNION ALL SELECT 'wf_notification_inbox', 'idx_wf_notification_inbox_source'
    UNION ALL SELECT 'wf_notification_inbox', 'idx_wf_notification_inbox_retention'
),
missing_indexes AS (
    SELECT expected.table_name, expected.index_name
    FROM expected_indexes expected
    LEFT JOIN information_schema.STATISTICS actual
      ON actual.TABLE_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.INDEX_NAME = expected.index_name
    WHERE actual.INDEX_NAME IS NULL
)
SELECT
    'workflow_business_indexes' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('missing=', COALESCE(GROUP_CONCAT(CONCAT(table_name, '.', index_name)
        ORDER BY table_name, index_name), 'none')) AS detail
FROM missing_indexes;

WITH expected_checks AS (
    SELECT 'wf_category' AS table_name, 'chk_wf_category_del_flag' AS constraint_name
    UNION ALL SELECT 'wf_form', 'chk_wf_form_content_json'
    UNION ALL SELECT 'wf_controlled_loop_execution', 'chk_wf_controlled_loop_outcome'
    UNION ALL SELECT 'wf_bpmn_extension_version', 'chk_wf_bpmn_extension_checksum'
    UNION ALL SELECT 'wf_task_sla_execution', 'chk_wf_task_sla_execution_status'
    UNION ALL SELECT 'wf_connector_endpoint', 'chk_wf_connector_endpoint_checksum'
    UNION ALL SELECT 'wf_sql_datasource', 'chk_wf_sql_datasource_checksum'
    UNION ALL SELECT 'wf_integration_credential', 'chk_wf_integration_rate_limit'
    UNION ALL SELECT 'wf_runtime_event_request', 'chk_wf_runtime_event_completion'
    UNION ALL SELECT 'wf_collaboration_message', 'chk_wf_collab_completion'
    UNION ALL SELECT 'wf_collaboration_outbox', 'chk_wf_collab_outbox_lease'
    UNION ALL SELECT 'wf_copy', 'chk_wf_copy_read_state'
    UNION ALL SELECT 'wf_process_draft', 'chk_wf_process_draft_lifecycle'
    UNION ALL SELECT 'wf_attachment_quota_guard', 'chk_wf_attachment_quota_guard_owner'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_state_relation'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_cleanup_retry'
    UNION ALL SELECT 'wf_attachment', 'chk_wf_attachment_cleanup_lease'
    UNION ALL SELECT 'wf_bpmn_event_code', 'chk_wf_bpmn_event_code_type'
    UNION ALL SELECT 'wf_bpmn_event_audit', 'chk_wf_bpmn_event_audit_match'
    UNION ALL SELECT 'wf_notification_policy', 'chk_wf_notification_policy_scope'
    UNION ALL SELECT 'wf_notification_outbox', 'chk_wf_notification_outbox_sequence'
    UNION ALL SELECT 'wf_notification_inbox', 'chk_wf_notification_inbox_hash'
    UNION ALL SELECT 'wf_notification_inbox', 'chk_wf_notification_inbox_source'
    UNION ALL SELECT 'wf_notification_inbox', 'chk_wf_notification_inbox_read'
),
missing_checks AS (
    SELECT expected.table_name, expected.constraint_name
    FROM expected_checks expected
    LEFT JOIN information_schema.TABLE_CONSTRAINTS actual
      ON actual.CONSTRAINT_SCHEMA = DATABASE()
     AND actual.TABLE_NAME = expected.table_name
     AND actual.CONSTRAINT_NAME = expected.constraint_name
     AND actual.CONSTRAINT_TYPE = 'CHECK'
     AND actual.ENFORCED = 'YES'
    WHERE actual.CONSTRAINT_NAME IS NULL
),
forbidden_checks AS (
    SELECT TABLE_NAME AS table_name, CONSTRAINT_NAME AS constraint_name
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_integration_credential'
      AND CONSTRAINT_NAME = 'chk_wf_integration_rate_window'
)
SELECT
    'workflow_business_checks' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', COALESCE(GROUP_CONCAT(CONCAT(issue_type, ':', table_name, '.', constraint_name)
        ORDER BY issue_type, table_name, constraint_name), 'none')) AS detail
FROM (
    SELECT 'missing' AS issue_type, table_name, constraint_name FROM missing_checks
    UNION ALL
    SELECT 'forbidden' AS issue_type, table_name, constraint_name FROM forbidden_checks
) check_issues;

WITH expected_foreign_keys AS (
    SELECT 'fk_wf_bpmn_extension_version_extension' AS constraint_name, 'RESTRICT' AS delete_rule
    UNION ALL SELECT 'fk_wf_business_calendar_day_calendar', 'CASCADE'
    UNION ALL SELECT 'fk_wf_task_sla_audit_execution', 'CASCADE'
    UNION ALL SELECT 'fk_wf_runtime_event_credential', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_collab_credential', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_collab_channel', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_collab_outbox_channel', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_collab_outbox_endpoint', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_attachment_draft', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_notification_preference_user', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_notification_outbox_user', 'RESTRICT'
    UNION ALL SELECT 'fk_wf_notification_inbox_user', 'RESTRICT'
),
foreign_key_issues AS (
    SELECT 'missing' AS issue_type, expected.constraint_name
    FROM expected_foreign_keys expected
    LEFT JOIN information_schema.REFERENTIAL_CONSTRAINTS actual
      ON actual.CONSTRAINT_SCHEMA = DATABASE()
     AND actual.CONSTRAINT_NAME = expected.constraint_name
     AND actual.UPDATE_RULE = 'RESTRICT'
     AND actual.DELETE_RULE = expected.delete_rule
    WHERE actual.CONSTRAINT_NAME IS NULL
    UNION ALL
    SELECT 'forbidden_outbox_fk', CONSTRAINT_NAME
    FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'wf_notification_inbox'
      AND REFERENCED_TABLE_NAME = 'wf_notification_outbox'
)
SELECT
    'workflow_business_foreign_keys' AS check_name,
    CASE WHEN COUNT(*) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', COALESCE(GROUP_CONCAT(CONCAT(issue_type, ':', constraint_name)
        ORDER BY issue_type, constraint_name), 'none')) AS detail
FROM foreign_key_issues;

WITH integrity_issues AS (
    SELECT 'quota_guard_invalid_owner' AS issue_name, COUNT(*) AS issue_count
    FROM wf_attachment_quota_guard
    WHERE owner_user_id <= 0
    UNION ALL
    SELECT 'credential_invalid_row', COUNT(*)
    FROM wf_integration_credential
    WHERE token_hash NOT REGEXP '^[0-9a-f]{64}$'
       OR rate_limit_per_minute NOT BETWEEN 1 AND 10000
       OR revision_no <= 0
       OR (expires_at IS NOT NULL AND expires_at <= create_time)
       OR (revoked_at IS NOT NULL AND revoked_at < create_time)
    UNION ALL
    SELECT 'inbox_invalid_stable_association', COUNT(*)
    FROM wf_notification_inbox
    WHERE notification_key NOT REGEXP '^[0-9a-f]{64}$'
       OR source_type NOT IN ('APPROVAL', 'SLA', 'BPMN_EVENT')
       OR source_id = ''
    UNION ALL
    SELECT 'inbox_duplicate_recipient', COUNT(*)
    FROM (
        SELECT notification_key, recipient_user_id
        FROM wf_notification_inbox
        GROUP BY notification_key, recipient_user_id
        HAVING COUNT(*) > 1
    ) duplicate_inbox
    UNION ALL
    SELECT 'inbox_missing_user', COUNT(*)
    FROM wf_notification_inbox inbox
    LEFT JOIN sys_user user ON user.user_id = inbox.recipient_user_id
    WHERE user.user_id IS NULL
    UNION ALL
    SELECT 'attachment_invalid_cleanup_lease', COUNT(*)
    FROM wf_attachment
    WHERE (cleanup_claim_token IS NULL) <> (cleanup_lease_until IS NULL)
       OR (cleanup_claim_token IS NOT NULL
           AND (cleanup_claim_token NOT REGEXP
               '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
               OR storage_deleted_time IS NOT NULL
               OR attachment_status NOT IN ('EXPIRED', 'DELETED')))
)
SELECT
    'workflow_business_data_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name), 'none')) AS detail
FROM integrity_issues;

SELECT
    'workflow_notification_tables' AS check_name,
    CASE WHEN COUNT(*) = 4 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('present=', COUNT(*), ', expected=4') AS detail
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wf_notification_policy', 'wf_notification_preference',
                     'wf_notification_outbox', 'wf_notification_inbox')
  AND ENGINE = 'InnoDB';

WITH notification_issues AS (
    SELECT 'outbox_invalid_state' AS issue_name, COUNT(*) AS issue_count
    FROM wf_notification_outbox
    WHERE status NOT IN ('PENDING', 'RETRYING', 'DELIVERING', 'PROCESSED', 'DEAD_LETTER', 'CANCELLED')
       OR attempt_count > max_attempts
       OR total_attempt_count < attempt_count
    UNION ALL
    SELECT 'inbox_outbox_source_mismatch', COUNT(*)
    FROM wf_notification_inbox inbox
    JOIN wf_notification_outbox outbox ON outbox.outbox_id = inbox.outbox_id
    WHERE inbox.source_type <> outbox.source_type
       OR inbox.source_id <> outbox.source_id
       OR inbox.notification_key <>
          SHA2(CONCAT_WS('|', outbox.source_type, outbox.source_id, outbox.event_type), 256)
)
SELECT
    'workflow_notification_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name), 'none')) AS detail
FROM notification_issues;

SELECT
    'workflow_attachment_lease_contract' AS check_name,
    CASE WHEN COUNT(*) = 5 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('matching=', COUNT(*), ', expected=5') AS detail
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'wf_attachment'
  AND COLUMN_NAME IN ('cleanup_retry_count', 'cleanup_next_retry_time',
                      'cleanup_last_error_code', 'cleanup_claim_token', 'cleanup_lease_until');

SELECT
    'workflow_runtime_integration_tables' AS check_name,
    CASE WHEN COUNT(*) = 6 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('present=', COUNT(*), ', expected=6') AS detail
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wf_integration_credential', 'wf_runtime_event_request',
                     'wf_collaboration_channel', 'wf_collaboration_message',
                     'wf_collaboration_outbox', 'wf_collaboration_message_audit')
  AND ENGINE = 'InnoDB';

WITH runtime_issues AS (
    SELECT 'runtime_event_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_runtime_event_request
    WHERE variables_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR status NOT IN ('RECEIVED', 'PROCESSED', 'FAILED')
    UNION ALL
    SELECT 'collaboration_message_invalid_row', COUNT(*)
    FROM wf_collaboration_message
    WHERE payload_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR attempt_count > max_attempts
    UNION ALL
    SELECT 'collaboration_outbox_invalid_row', COUNT(*)
    FROM wf_collaboration_outbox
    WHERE payload_sha256 NOT REGEXP '^[0-9a-f]{64}$'
       OR attempt_count > max_attempts
)
SELECT
    'workflow_runtime_integration_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name), 'none')) AS detail
FROM runtime_issues;

SELECT
    'workflow_extension_tables' AS check_name,
    CASE WHEN COUNT(*) = 4 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('present=', COUNT(*), ', expected=4') AS detail
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wf_bpmn_extension', 'wf_bpmn_extension_version',
                     'wf_connector_endpoint', 'wf_sql_datasource')
  AND ENGINE = 'InnoDB';

WITH extension_issues AS (
    SELECT 'extension_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_bpmn_extension
    WHERE extension_type NOT IN ('JAVA', 'CEL', 'HTTP', 'SQL', 'DMN', 'FORM_FIELD')
       OR status NOT IN ('ENABLED', 'DISABLED')
    UNION ALL
    SELECT 'extension_version_invalid_row', COUNT(*)
    FROM wf_bpmn_extension_version
    WHERE version_no <= 0 OR checksum NOT REGEXP '^[0-9a-f]{64}$'
    UNION ALL
    SELECT 'sql_connector_version_drift', CASE WHEN COUNT(*) = 1 THEN 0 ELSE 1 END
    FROM wf_bpmn_extension extension_row
    JOIN wf_bpmn_extension_version version_row
      ON version_row.extension_id = extension_row.extension_id
    WHERE extension_row.extension_key = 'approva.sql-connector'
      AND extension_row.extension_type = 'SQL'
      AND version_row.version_no = 1
      AND version_row.implementation_key = 'SQL_CONNECTOR_V1'
      AND version_row.checksum =
          '262e474870a4b0dda95860efd908d21afc417aa38f83e90be0eb1a35a392c3c7'
      AND JSON_CONTAINS_PATH(version_row.config_schema, 'one', '$.properties.idempotencyColumn') = 1
    UNION ALL
    SELECT 'connector_endpoint_invalid_row', COUNT(*)
    FROM wf_connector_endpoint
    WHERE revision_no <= 0 OR checksum NOT REGEXP '^[0-9a-f]{64}$'
    UNION ALL
    SELECT 'sql_datasource_invalid_row', COUNT(*)
    FROM wf_sql_datasource
    WHERE revision_no <= 0 OR checksum NOT REGEXP '^[0-9a-f]{64}$'
)
SELECT
    'workflow_extension_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name), 'none')) AS detail
FROM extension_issues;

SELECT
    'workflow_sla_tables' AS check_name,
    CASE WHEN COUNT(*) = 4 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('present=', COUNT(*), ', expected=4') AS detail
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wf_business_calendar', 'wf_business_calendar_day',
                     'wf_task_sla_execution', 'wf_task_sla_audit')
  AND ENGINE = 'InnoDB';

WITH sla_issues AS (
    SELECT 'sla_calendar_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_business_calendar
    WHERE status NOT IN ('ENABLED', 'DISABLED') OR work_start >= work_end
    UNION ALL
    SELECT 'sla_execution_invalid_row', COUNT(*)
    FROM wf_task_sla_execution
    WHERE status NOT IN ('ACTIVE', 'COMPLETED', 'ESCALATED')
       OR reminders_sent < 0 OR paused_millis < 0 OR revision < 0
    UNION ALL
    SELECT 'sla_audit_missing_execution', COUNT(*)
    FROM wf_task_sla_audit audit
    LEFT JOIN wf_task_sla_execution execution
      ON execution.sla_execution_id = audit.sla_execution_id
    WHERE execution.sla_execution_id IS NULL
)
SELECT
    'workflow_sla_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name), 'none')) AS detail
FROM sla_issues;

SELECT
    'workflow_bpmn_event_tables' AS check_name,
    CASE WHEN COUNT(*) = 2 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('present=', COUNT(*), ', expected=2') AS detail
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('wf_bpmn_event_code', 'wf_bpmn_event_audit')
  AND ENGINE = 'InnoDB';

WITH event_issues AS (
    SELECT 'event_code_invalid_row' AS issue_name, COUNT(*) AS issue_count
    FROM wf_bpmn_event_code
    WHERE event_type NOT IN ('ERROR', 'ESCALATION')
       OR status NOT IN ('ENABLED', 'DISABLED')
    UNION ALL
    SELECT 'event_audit_invalid_row', COUNT(*)
    FROM wf_bpmn_event_audit
    WHERE idempotency_key NOT REGEXP '^[0-9a-f]{64}$'
       OR event_type NOT IN ('ERROR', 'ESCALATION')
)
SELECT
    'workflow_bpmn_event_integrity' AS check_name,
    CASE WHEN SUM(issue_count) = 0 THEN 'PASS' ELSE 'FAIL' END AS result,
    CONCAT('issues=', SUM(issue_count), ', detail=', COALESCE(GROUP_CONCAT(
        CASE WHEN issue_count > 0 THEN CONCAT(issue_name, ':', issue_count) END
        ORDER BY issue_name), 'none')) AS detail
FROM event_issues;
