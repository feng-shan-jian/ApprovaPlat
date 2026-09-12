-- 先于包含独立可靠消息入口的应用版本执行。旧记录保持原跨池协议，不修改 BPMN 或历史业务状态。
-- 兼容全新基线已含此列；仅为缺少列的既有库新增列。
SET @workflow_message_mode_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'wf_collaboration_message' AND column_name = 'delivery_mode'
);
SET @workflow_message_mode_ddl = IF(@workflow_message_mode_exists = 0,
    'ALTER TABLE wf_collaboration_message ADD COLUMN delivery_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''COLLABORATION'' COMMENT ''COLLABORATION or POINT_TO_POINT'' AFTER message_id',
    'SELECT ''delivery_mode already exists'' AS migration_status');
PREPARE workflow_message_mode_statement FROM @workflow_message_mode_ddl;
EXECUTE workflow_message_mode_statement;
DEALLOCATE PREPARE workflow_message_mode_statement;

SET @workflow_channel_mode_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'wf_collaboration_channel' AND column_name = 'delivery_mode'
);
SET @workflow_channel_mode_ddl = IF(@workflow_channel_mode_exists = 0,
    'ALTER TABLE wf_collaboration_channel ADD COLUMN delivery_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''COLLABORATION'' AFTER channel_id, DROP INDEX uk_wf_collab_channel_target, ADD UNIQUE KEY uk_wf_collab_channel_target (delivery_mode,target_process_definition_key,correlation_type,correlation_value)',
    'SELECT ''channel delivery_mode already exists'' AS migration_status');
PREPARE workflow_channel_mode_statement FROM @workflow_channel_mode_ddl;
EXECUTE workflow_channel_mode_statement;
DEALLOCATE PREPARE workflow_channel_mode_statement;
