-- 扩展部署表单快照以同时支持正式 wf_form 与 BPMN 内嵌 FormData。
-- 既有记录全部按 TEMPLATE 回填；只放宽 form_id 空值，不修改任何历史快照正文。

SET @wf_deploy_form_source_column_count :=
(
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wf_deploy_form'
       AND COLUMN_NAME = 'source_type'
);

SET @wf_deploy_form_source_column_ddl := IF(
    @wf_deploy_form_source_column_count = 0,
    'ALTER TABLE `wf_deploy_form` ADD COLUMN `source_type` VARCHAR(16) NOT NULL DEFAULT ''TEMPLATE'' COMMENT ''快照来源：TEMPLATE 正式模板、EMBEDDED BPMN 内嵌表单'' AFTER `deploy_id`',
    'DO 0'
);
PREPARE wf_deploy_form_source_column_statement FROM @wf_deploy_form_source_column_ddl;
EXECUTE wf_deploy_form_source_column_statement;
DEALLOCATE PREPARE wf_deploy_form_source_column_statement;

-- MODIFY 可重复执行，用于把升级前的 NOT NULL 模板主键安全放宽为内嵌表单可空。
ALTER TABLE `wf_deploy_form`
    MODIFY COLUMN `form_id` BIGINT NULL DEFAULT NULL
        COMMENT '快照来源表单主键；内嵌表单为空';

SET @wf_deploy_form_source_check_count :=
(
    SELECT COUNT(*)
      FROM information_schema.TABLE_CONSTRAINTS
     WHERE CONSTRAINT_SCHEMA = DATABASE()
       AND TABLE_NAME = 'wf_deploy_form'
       AND CONSTRAINT_NAME = 'chk_wf_deploy_form_source'
       AND CONSTRAINT_TYPE = 'CHECK'
);
SET @wf_deploy_form_source_check_ddl := IF(
    @wf_deploy_form_source_check_count = 0,
    'ALTER TABLE `wf_deploy_form` ADD CONSTRAINT `chk_wf_deploy_form_source` CHECK ((`source_type` = ''TEMPLATE'' AND `form_id` IS NOT NULL AND `form_id` > 0) OR (`source_type` = ''EMBEDDED'' AND `form_id` IS NULL))',
    'DO 0'
);
PREPARE wf_deploy_form_source_check_statement FROM @wf_deploy_form_source_check_ddl;
EXECUTE wf_deploy_form_source_check_statement;
DEALLOCATE PREPARE wf_deploy_form_source_check_statement;

SET @wf_deploy_form_source_column_count := NULL;
SET @wf_deploy_form_source_column_ddl := NULL;
SET @wf_deploy_form_source_check_count := NULL;
SET @wf_deploy_form_source_check_ddl := NULL;
