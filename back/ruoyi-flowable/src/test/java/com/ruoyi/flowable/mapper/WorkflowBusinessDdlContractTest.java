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
                "create table if not exists `wf_copy`",
                "create table if not exists `wf_attachment_quota_guard`",
                "create table if not exists `wf_attachment`")
                .doesNotContain("drop table");
        assertThat(ddl).contains(
                "generated always as",
                "case when `del_flag` = '0' then `code` else null end",
                "unique key `uk_wf_category_active_code` (`active_code`)",
                "constraint `chk_wf_form_content_json` check (json_valid(`content`))",
                "constraint `chk_wf_deploy_form_content_json` check (json_valid(`content`))",
                "unique key `uk_wf_copy_event_user` (`copy_event_id`, `user_id`)",
                "constraint `chk_wf_attachment_quota_guard_owner` check (`owner_user_id` >= 0)",
                "insert ignore into `wf_attachment_quota_guard` (`owner_user_id`)",
                "values (0)",
                "key `idx_wf_deploy_form_form_id` (`form_id`)",
                "key `idx_wf_copy_user_status_time` (`user_id`, `del_flag`, `create_time`)");
    }

    /**
     * 验证业务表验收脚本只读，并覆盖表、列、生成列、索引、约束和数据关联。
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
                "workflow_business_tables",
                "workflow_business_columns",
                "wf_category_active_code",
                "workflow_business_indexes",
                "workflow_business_checks",
                "workflow_business_data_integrity",
                "wf_deploy_form_missing_source_form",
                "wf_deploy_form_missing_deployment",
                "wf_copy_missing_process_instance",
                "wf_copy_missing_recipient",
                "wf_attachment_quota_guard_invalid_owner",
                "wf_attachment_quota_guard_global_missing");
    }

    /**
     * 验证附件配额 guard 增量脚本只执行一次幂等建表并预置固定全局锁行。
     * @return void，增量脚本不幂等、缺少固定全局行或包含危险语句时测试失败
     * @throws Exception 读取正式增量 SQL 文件失败
     */
    @Test
    void keepsAttachmentQuotaGuardMigrationIdempotentAndNonDestructive() throws Exception
    {
        String migration = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0.1__workflow_attachment_quota_guard.sql"),
                StandardCharsets.UTF_8);
        String normalized = migration.toLowerCase();

        Pattern createStatement = Pattern.compile("(?im)^\\s*create\\s+");
        Pattern forbiddenMutation = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");
        Pattern globalGuardSeed = Pattern.compile(
                "(?is)insert\\s+ignore\\s+into\\s+`wf_attachment_quota_guard`\\s*"
                        + "\\(\\s*`owner_user_id`\\s*\\)\\s*values\\s*\\(\\s*0\\s*\\)");
        long createCount = createStatement.matcher(migration).results().count();
        long globalGuardSeedCount = globalGuardSeed.matcher(migration).results().count();

        assertThat(createCount).isEqualTo(1L);
        assertThat(globalGuardSeedCount).isEqualTo(1L);
        assertThat(forbiddenMutation.matcher(migration).find()).isFalse();
        assertThat(normalized).contains(
                "create table if not exists `wf_attachment_quota_guard`",
                "primary key (`owner_user_id`)",
                "constraint `chk_wf_attachment_quota_guard_owner` check (`owner_user_id` >= 0)",
                "insert ignore into `wf_attachment_quota_guard` (`owner_user_id`)",
                "values (0)");
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
