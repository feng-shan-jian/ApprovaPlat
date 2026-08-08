package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowBusinessDdlContractTest
{
    /**
     * 验证正式 DDL 具备幂等创建、真实唯一约束、JSON 约束、索引且不包含删表语句。
     * @return void，DDL 契约不完整时测试失败
     * @throws Exception 读取正式 DDL 文件失败
     */
    @Test
    void definesProductionBusinessConstraintsAndIndexes() throws Exception
    {
        String ddl = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains(
                "create table if not exists `wf_category`",
                "create table if not exists `wf_form`",
                "create table if not exists `wf_deploy_form`",
                "create table if not exists `wf_deploy_condition_rule`",
                "create table if not exists `wf_deploy_controlled_loop`",
                "create table if not exists `wf_deploy_participant_rule`",
                "create table if not exists `wf_participant_resolution_audit`",
                "create table if not exists `wf_controlled_loop_execution`",
                "create table if not exists `wf_copy`",
                "create table if not exists `wf_model_save_idempotency`",
                "create table if not exists `wf_designer_preference`",
                "create table if not exists `wf_bpmn_extension`",
                "create table if not exists `wf_bpmn_extension_version`",
                "create table if not exists `wf_deploy_extension_snapshot`",
                "create table if not exists `wf_deploy_dmn_snapshot`",
                "create table if not exists `wf_deploy_call_activity`",
                "create table if not exists `wf_connector_endpoint`",
                "create table if not exists `wf_connector_invocation`",
                "create table if not exists `wf_integration_credential`",
                "create table if not exists `wf_runtime_event_request`",
                "create table if not exists `wf_collaboration_channel`",
                "create table if not exists `wf_collaboration_message`",
                "create table if not exists `wf_collaboration_outbox`",
                "create table if not exists `wf_collaboration_message_audit`",
                "create table if not exists `wf_bpmn_event_code`",
                "create table if not exists `wf_bpmn_event_audit`",
                "create table if not exists `wf_bpmn_event_notification`",
                "create table if not exists `wf_business_calendar`",
                "create table if not exists `wf_business_calendar_day`",
                "create table if not exists `wf_deploy_task_sla`",
                "create table if not exists `wf_task_sla_execution`",
                "create table if not exists `wf_task_sla_audit`",
                "create table if not exists `wf_task_sla_notification`",
                "create table if not exists `wf_process_draft`",
                "create table if not exists `wf_process_draft_audit`",
                "create table if not exists `wf_attachment_quota_guard`",
                "create table if not exists `wf_attachment`")
                .doesNotContain("drop table");
        assertThat(ddl).contains(
                "generated always as",
                "case when `del_flag` = '0' then `code` else null end",
                "unique key `uk_wf_category_active_code` (`active_code`)",
                "constraint `chk_wf_form_content_json` check (json_valid(`content`))",
                "constraint `chk_wf_deploy_form_content_json` check (json_valid(`content`))",
                "constraint `chk_wf_deploy_form_source` check",
                "`source_type` varchar(16) not null default 'template'",
                "(`source_type` = 'embedded' and `form_id` is null)",
                "unique key `uk_wf_copy_event_user` (`copy_event_id`, `user_id`)",
                "constraint `chk_wf_attachment_quota_guard_owner` check (`owner_user_id` >= 0)",
                "unique key `uk_wf_process_draft_instance` (`submitted_process_instance_id`)",
                "key `idx_wf_process_draft_owner_status_time`",
                "constraint `chk_wf_process_draft_snapshot_json` check (json_valid(`form_snapshot`))",
                "constraint `chk_wf_process_draft_assignment_json` check",
                "constraint `chk_wf_process_draft_values_json` check (json_valid(`form_values`))",
                "constraint `chk_wf_process_draft_members_json` check",
                "constraint `chk_wf_process_draft_lifecycle` check",
                "unique key `uk_wf_process_draft_audit_revision` (`draft_id`, `to_revision`)",
                "constraint `fk_wf_process_draft_audit_draft` foreign key (`draft_id`)",
                "constraint `chk_wf_process_draft_audit_transition` check",
                "key `idx_wf_attachment_draft_field` (`draft_id`, `field_name`, `attachment_status`)",
                "constraint `fk_wf_attachment_draft` foreign key (`draft_id`)",
                "`attachment_status` in ('temp', 'draft', 'bound', 'expired', 'deleted')",
                "insert ignore into `wf_attachment_quota_guard` (`owner_user_id`)",
                "values (0)",
                "key `idx_wf_deploy_form_form_id` (`form_id`)",
                "unique key `uk_wf_deploy_condition_flow` (`deploy_id`, `process_key`, `gateway_id`, `flow_id`)",
                "key `idx_wf_deploy_condition_runtime` (`deploy_id`, `process_key`, `gateway_token`)",
                "constraint `chk_wf_deploy_condition_default` check",
                "unique key `uk_wf_deploy_controlled_loop_node` (`deploy_id`, `process_key`, `activity_id`)",
                "unique key `uk_wf_controlled_loop_task` (`task_id`)",
                "unique key `uk_wf_controlled_loop_iteration`",
                "(`process_instance_id`, `activity_id`, `iteration_no`)",
                "constraint `chk_wf_deploy_controlled_loop_iterations` check (`max_iterations` between 2 and 50)",
                "constraint `chk_wf_deploy_controlled_loop_route` check",
                "constraint `chk_wf_deploy_controlled_loop_iteration_var` check",
                "constraint `chk_wf_controlled_loop_iteration_no` check (`iteration_no` between 1 and 50)",
                "constraint `chk_wf_controlled_loop_actor` check",
                "constraint `chk_wf_controlled_loop_outcome` check (`outcome` in ('repeat', 'exit'))",
                "key `idx_wf_copy_user_status_time` (`user_id`, `del_flag`, `create_time`)");
        assertThat(ddl).contains(
                "unique key `uk_wf_deploy_participant_rule_node`",
                "constraint `chk_wf_deploy_participant_rule_relation` check",
                "constraint `chk_wf_deploy_participant_rule_targets` check",
                "constraint `chk_wf_deploy_participant_rule_policy` check (`no_match_policy` = 'fail')",
                "constraint `chk_wf_participant_audit_relation` check",
                "constraint `chk_wf_participant_audit_actor` check")
                .doesNotContain("fk_wf_participant", "foreign key (`rule_id`)");
        assertThat(ddl).contains(
                "unique key `uk_wf_business_calendar_key` (`calendar_key`)",
                "constraint `chk_wf_deploy_task_sla_escalation` check",
                "unique key `uk_wf_task_sla_execution_task` (`task_id`)",
                "unique key `uk_wf_task_sla_audit_action`",
                "unique key `uk_wf_task_sla_notification`",
                "constraint `fk_wf_task_sla_notification_audit`");
        assertThat(ddl).contains(
                "unique key `uk_wf_deploy_call_element`",
                "key `idx_wf_deploy_call_target`",
                "key `idx_wf_deploy_call_target_deploy`",
                "constraint `chk_wf_deploy_call_version_policy`",
                "constraint `chk_wf_deploy_call_propagation`",
                "constraint `chk_wf_deploy_call_input_json`",
                "constraint `chk_wf_deploy_call_output_json`",
                "constraint `chk_wf_deploy_call_checksum`");
    }

    /**
     * 验证业务表验收脚本只读，并覆盖核心与扩展表、列、索引、约束和数据关联。
     * @return void，验收脚本包含写操作或缺少关键门禁时测试失败
     * @throws Exception 读取正式验收 SQL 文件失败
     */
    @Test
    void keepsProductionBusinessVerificationReadOnlyAndComplete() throws Exception
    {
        String verification = Files.readString(findProjectSql(
                "sql/flowable/verify/8.0.0__verify_workflow_business.sql"),
                StandardCharsets.UTF_8);
        String normalized = verification.toLowerCase();

        Pattern mutation = Pattern.compile(
                "(?im)^\\s*(insert|update|delete|create|drop|alter|truncate|replace|call|set)\\b");
        assertThat(mutation.matcher(verification).find()).isFalse();
        assertThat(normalized).contains(
                "workflow_schema_table_counts",
                "total=",
                "ruoyi=",
                "quartz=",
                "flowable=",
                "workflow=",
                "workflow_business_tables",
                "workflow_business_columns",
                "wf_category_active_code",
                "workflow_business_indexes",
                "workflow_business_checks",
                "workflow_business_data_integrity",
                "workflow_draft_column_types",
                "chk_wf_deploy_form_source",
                "source_type",
                "wf_deploy_form_missing_source_form",
                "wf_deploy_form_missing_deployment",
                "wf_copy_missing_process_instance",
                "wf_copy_missing_recipient",
                "wf_model_save_invalid_row",
                "wf_model_save_incomplete_record",
                "wf_model_save_missing_user",
                "workflow_business_foreign_keys",
                "wf_designer_preference_invalid_row",
                "wf_designer_preference_missing_user",
                "wf_attachment_quota_guard_invalid_owner",
                "wf_attachment_quota_guard_global_missing",
                "wf_process_draft_invalid_row",
                "wf_process_draft_missing_owner",
                "wf_process_draft_submitted_history_mismatch",
                "wf_process_draft_audit_invalid_chain",
                "wf_attachment_draft_relation_mismatch",
                "wf_controlled_loop_missing_deployment",
                "wf_controlled_loop_execution_missing_snapshot",
                "wf_controlled_loop_execution_missing_history",
                "wf_controlled_loop_execution_invalid_result",
                "wf_controlled_loop_execution_iteration_gap",
                "wf_controlled_loop_execution_after_exit",
                "workflow_extension_tables",
                "workflow_extension_columns",
                "workflow_extension_indexes",
                "workflow_extension_checks",
                "workflow_extension_foreign_keys",
                "workflow_extension_data_integrity",
                "fk_wf_bpmn_extension_version_extension",
                "fk_wf_deploy_extension_version",
                "deploy_extension_snapshot_mismatch",
                "connector_endpoint_invalid_row",
                "connector_invocation_invalid_state",
                "workflow_call_activity_snapshot_columns",
                "workflow_call_activity_snapshot_indexes",
                "workflow_call_activity_snapshot_checks",
                "workflow_call_activity_snapshot_integrity",
                "wf_deploy_call_activity",
                "act_re_procdef");
        assertThat(normalized).contains(
                "workflow_participant_rule_tables",
                "workflow_participant_rule_columns",
                "workflow_participant_rule_indexes",
                "workflow_participant_rule_checks",
                "workflow_participant_audit_retention_foreign_keys",
                "workflow_participant_rule_data_integrity",
                "participant_rule_missing_deployment",
                "participant_rule_missing_start_scope",
                "participant_audit_invalid_row");
        assertThat(normalized).contains(
                "workflow_runtime_integration_tables",
                "workflow_runtime_integration_columns",
                "workflow_runtime_integration_indexes",
                "workflow_runtime_integration_checks",
                "workflow_runtime_integration_foreign_keys",
                "workflow_runtime_integration_data_integrity",
                "integration_credential_invalid_row",
                "runtime_event_invalid_row",
                "runtime_event_missing_credential");
        assertThat(normalized).contains(
                "workflow_bpmn_event_tables",
                "workflow_bpmn_event_constraints",
                "workflow_bpmn_event_data_integrity",
                "event_code_invalid_row",
                "event_audit_invalid_row",
                "event_notification_invalid_row",
                "workflow_sla_tables",
                "workflow_sla_constraints",
                "workflow_sla_foreign_keys",
                "workflow_sla_data_integrity",
                "sla_calendar_invalid_row",
                "sla_snapshot_invalid_row",
                "sla_execution_invalid_row",
                "sla_audit_invalid_row",
                "sla_notification_invalid_row");
    }

    /**
     * 验证首个正式业务基线包含运行事件表，不保存 Token 正文且不含破坏性语句。
     * @return void，Token、幂等或外键契约缺失时测试失败
     * @throws Exception 读取正式基线 SQL 失败
     */
    @Test
    void keepsRuntimeIntegrationInFormalBaseline() throws Exception
    {
        String baseline = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8);
        String normalized = baseline.toLowerCase();
        Pattern destructiveMutation = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");

        assertThat(destructiveMutation.matcher(baseline).find()).isFalse();
        assertThat(normalized).contains(
                "create table if not exists `wf_integration_credential`",
                "create table if not exists `wf_runtime_event_request`",
                "`token_prefix`",
                "`token_hash`",
                "unique key `uk_wf_integration_token_prefix`",
                "constraint `fk_wf_runtime_event_credential`",
                "constraint `chk_wf_runtime_event_completion`")
                .contains(
                        "unique key `uk_wf_collab_message_sequence`",
                        "unique key `uk_wf_collab_outbox_sequence`",
                        "key `idx_wf_collab_outbox_due`",
                        "constraint `fk_wf_collab_outbox_endpoint`",
                        "constraint `chk_wf_collab_outbox_lease`",
                        "'collaboration_outbox_v1'")
                .doesNotContain("token_plaintext", "access_token", "secret_token");
    }

    /**
     * 验证首个正式业务基线支持模板和内嵌双表单快照，并建立来源一致性约束。
     * @return void，基线缺少幂等门禁或包含数据删除时测试失败
     * @throws Exception 读取正式基线 SQL 文件失败
     */
    @Test
    void keepsEmbeddedFormSnapshotInFormalBaseline() throws Exception
    {
        String baseline = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8);
        String normalized = baseline.toLowerCase();

        Pattern destructiveMutation = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|truncate|replace)\\b");
        assertThat(destructiveMutation.matcher(baseline).find()).isFalse();
        assertThat(normalized).contains(
                "create table if not exists `wf_deploy_form`",
                "`form_id`",
                "`source_type` varchar(16) not null default 'template'",
                "constraint `chk_wf_deploy_form_source` check",
                "`source_type` = 'template'",
                "`source_type` = 'embedded'");
    }

    /**
     * 验证首个正式业务基线直接创建附件配额 guard 并预置固定全局锁行。
     * @return void，基线缺少固定全局行或包含危险语句时测试失败
     * @throws Exception 读取正式基线 SQL 文件失败
     */
    @Test
    void keepsAttachmentQuotaGuardInFormalBaseline() throws Exception
    {
        String baseline = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8);
        String normalized = baseline.toLowerCase();

        Pattern createStatement = Pattern.compile("(?im)^\\s*create\\s+");
        Pattern forbiddenMutation = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");
        Pattern globalGuardSeed = Pattern.compile(
                "(?is)insert\\s+ignore\\s+into\\s+`wf_attachment_quota_guard`\\s*"
                        + "\\(\\s*`owner_user_id`\\s*\\)\\s*values\\s*\\(\\s*0\\s*\\)");
        long createCount = createStatement.matcher(baseline).results().count();
        long globalGuardSeedCount = globalGuardSeed.matcher(baseline).results().count();

        assertThat(createCount).isGreaterThanOrEqualTo(1L);
        assertThat(globalGuardSeedCount).isEqualTo(1L);
        assertThat(forbiddenMutation.matcher(baseline).find()).isFalse();
        assertThat(normalized).contains(
                "create table if not exists `wf_attachment_quota_guard`",
                "primary key (`owner_user_id`)",
                "constraint `chk_wf_attachment_quota_guard_owner` check (`owner_user_id` >= 0)",
                "insert ignore into `wf_attachment_quota_guard` (`owner_user_id`)",
                "values (0)");
    }

    /**
     * 验证首个正式基线在 Flowable 官方表之后建立模型版本唯一约束。
     * @return void，初始化门禁缺少幂等探测、唯一约束或包含数据删除时测试失败
     * @throws Exception 读取模型完整性基线 SQL 失败
     */
    @Test
    void definesModelVersionGuardInFormalBaseline() throws Exception
    {
        String guard = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_model_version_guard.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(guard).contains(
                "from information_schema.statistics",
                "table_name = 'act_re_model'",
                "non_unique = 0",
                "'key_,version_,tenant_id_'",
                "alter table `act_re_model` add constraint `act_uniq_model` unique (`key_`, `version_`, `tenant_id_`)",
                "prepare wf_model_guard_statement",
                "execute wf_model_guard_statement")
                .doesNotContain("drop table", "delete from", "update `act_re_model`");
    }

    /**
     * 从 Maven 模块或聚合工程工作目录向上定位正式 SQL 文件。
     * @param moduleRelativePath String，以 back 或当前后端模块为基准的 SQL 相对路径
     * @return Path，正式 SQL 的绝对路径
     */
    private Path findProjectSql(String moduleRelativePath)
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path candidate = current.resolve(moduleRelativePath);
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
            candidate = current.resolve("back").resolve(moduleRelativePath);
            if (Files.isRegularFile(candidate))
            {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("未找到正式工作流 SQL: " + moduleRelativePath);
    }
}
