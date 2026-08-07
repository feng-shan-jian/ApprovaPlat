-- 将既有 HTTP 专用调用台账升级为 HTTP/SQL 共用语义；只重命名列并新增类型，不修改业务记录。

SET @wf_has_old_endpoint_key = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND COLUMN_NAME = 'endpoint_key'
);
SET @wf_sql = IF(@wf_has_old_endpoint_key = 1,
    'ALTER TABLE `wf_connector_invocation` RENAME COLUMN `endpoint_key` TO `target_key`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_old_endpoint_revision = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND COLUMN_NAME = 'endpoint_revision'
);
SET @wf_has_old_revision_check = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND CONSTRAINT_NAME = 'chk_wf_connector_invocation_revision'
      AND @wf_has_old_endpoint_revision = 1
);
SET @wf_sql = IF(@wf_has_old_revision_check = 1,
    'ALTER TABLE `wf_connector_invocation` DROP CHECK `chk_wf_connector_invocation_revision`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;
SET @wf_sql = IF(@wf_has_old_endpoint_revision = 1,
    'ALTER TABLE `wf_connector_invocation` RENAME COLUMN `endpoint_revision` TO `target_revision`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_revision_check = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND CONSTRAINT_NAME = 'chk_wf_connector_invocation_revision'
);
SET @wf_sql = IF(@wf_has_revision_check = 0,
    'ALTER TABLE `wf_connector_invocation` ADD CONSTRAINT `chk_wf_connector_invocation_revision` CHECK (`target_revision` > 0)',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_old_request_method = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND COLUMN_NAME = 'request_method'
);
SET @wf_sql = IF(@wf_has_old_request_method = 1,
    'ALTER TABLE `wf_connector_invocation` RENAME COLUMN `request_method` TO `operation`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_old_request_path = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND COLUMN_NAME = 'request_path'
);
SET @wf_sql = IF(@wf_has_old_request_path = 1,
    'ALTER TABLE `wf_connector_invocation` RENAME COLUMN `request_path` TO `target_summary`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_old_http_status = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND COLUMN_NAME = 'http_status'
);
SET @wf_has_old_status_check = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND CONSTRAINT_NAME = 'chk_wf_connector_invocation_http_status'
      AND @wf_has_old_http_status = 1
);
SET @wf_sql = IF(@wf_has_old_status_check = 1,
    'ALTER TABLE `wf_connector_invocation` DROP CHECK `chk_wf_connector_invocation_http_status`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;
SET @wf_sql = IF(@wf_has_old_http_status = 1,
    'ALTER TABLE `wf_connector_invocation` RENAME COLUMN `http_status` TO `result_code`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_result_check = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND CONSTRAINT_NAME = 'chk_wf_connector_invocation_result_code'
);
SET @wf_sql = IF(@wf_has_result_check = 0,
    'ALTER TABLE `wf_connector_invocation` ADD CONSTRAINT `chk_wf_connector_invocation_result_code` CHECK (`result_code` IS NULL OR `result_code` BETWEEN 0 AND 99999)',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_connector_type = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND COLUMN_NAME = 'connector_type'
);
SET @wf_sql = IF(@wf_has_connector_type = 0,
    'ALTER TABLE `wf_connector_invocation` ADD COLUMN `connector_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''HTTP'' COMMENT ''HTTP 或 SQL'' AFTER `element_id`',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_has_type_check = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'wf_connector_invocation'
      AND CONSTRAINT_NAME = 'chk_wf_connector_invocation_type'
);
SET @wf_sql = IF(@wf_has_type_check = 0,
    'ALTER TABLE `wf_connector_invocation` ADD CONSTRAINT `chk_wf_connector_invocation_type` CHECK (`connector_type` IN (''HTTP'', ''SQL''))',
    'SELECT 1');
PREPARE wf_stmt FROM @wf_sql;
EXECUTE wf_stmt;
DEALLOCATE PREPARE wf_stmt;

SET @wf_sql = NULL;
