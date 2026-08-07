package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class WorkflowModelSaveDdlContractTest
{
    /**
     * 验证首个正式基线包含完整的模型保存幂等表、字符集、索引与状态约束。
     *
     * @return void，正式基线缺少生产级幂等契约时测试失败
     * @throws Exception 正式 SQL 文件无法读取时测试失败
     */
    @Test
    void definesProductionIdempotencyTableInFormalBaseline() throws Exception
    {
        String baseline = readSql("sql/flowable/business/8.0.0__workflow_business.sql")
                .toLowerCase();
        assertThat(baseline).contains(
                    "create table if not exists `wf_model_save_idempotency`",
                    "`request_id`     char(36) character set ascii collate ascii_bin not null",
                    "`user_id`        varchar(64) character set ascii collate ascii_bin not null",
                    "`source_model_id` varchar(64) character set ascii collate ascii_bin not null",
                    "`payload_sha256` char(64) character set ascii collate ascii_bin not null",
                    "`saved_model_id` varchar(64) character set ascii collate ascii_bin default null",
                    "`create_time`    datetime(3) not null default current_timestamp(3)",
                    "`complete_time`  datetime(3)          default null",
                    "primary key (`request_id`)",
                    "key `idx_wf_model_save_user_time` (`user_id`, `create_time`)",
                    "key `idx_wf_model_save_source_time` (`source_model_id`, `create_time`)",
                    "key `idx_wf_model_save_saved_model` (`saved_model_id`)",
                    "`request_id` regexp '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'",
                    "constraint `chk_wf_model_save_payload_sha256` check",
                    "constraint `chk_wf_model_save_completion` check",
                    "engine = innodb",
                    "default charset = utf8mb4",
                    "collate = utf8mb4_unicode_ci");
    }

    /**
     * 验证首个正式业务基线不包含删除、覆盖或历史数据改写。
     *
     * @return void，基线包含破坏性语句时测试失败
     * @throws Exception 正式基线 SQL 文件无法读取时测试失败
     */
    @Test
    void keepsFormalBaselineNonDestructive() throws Exception
    {
        String baseline = readSql("sql/flowable/business/8.0.0__workflow_business.sql");
        Pattern forbiddenMutation = Pattern.compile(
                "(?im)^\\s*(drop|delete|update|alter|truncate|replace|call|set)\\b");

        assertThat(baseline.toLowerCase()).contains("create table if not exists `wf_model_save_idempotency`");
        assertThat(forbiddenMutation.matcher(baseline).find()).isFalse();
    }

    /**
     * 验证只读验收扩展到连接器发布域后，继续覆盖模型保存表的精确列、索引、约束和数据状态。
     *
     * @return void，验收脚本遗漏模型保存正式表契约或包含写操作时测试失败
     * @throws Exception 正式验收 SQL 文件无法读取时测试失败
     */
    @Test
    void preservesModelSaveVerificationAfterDesignerPreferenceGate() throws Exception
    {
        String verification = readSql(
                "sql/flowable/verify/8.0.0__verify_workflow_business.sql");
        String normalized = verification.toLowerCase();
        Pattern mutation = Pattern.compile(
                "(?im)^\\s*(insert|update|delete|create|drop|alter|truncate|replace|call|set)\\b");
        Pattern checkName = Pattern.compile("(?i)'[^']+'\\s+as\\s+check_name");

        assertThat(mutation.matcher(verification).find()).isFalse();
        assertThat(checkName.matcher(verification).results().count()).isEqualTo(25L);
        assertThat(normalized).contains(
                "union all select 'wf_model_save_idempotency'",
                "when count(a.table_name) = 10",
                "idx_wf_model_save_user_time",
                "idx_wf_model_save_source_time",
                "idx_wf_model_save_saved_model",
                "chk_wf_model_save_request_id",
                "chk_wf_model_save_user_id",
                "chk_wf_model_save_source_id",
                "chk_wf_model_save_payload_sha256",
                "chk_wf_model_save_completion",
                "wf_model_save_invalid_row",
                "wf_model_save_incomplete_record",
                "wf_model_save_missing_user");
    }

    /**
     * 从 Maven 模块或聚合工程工作目录向上定位并读取正式 SQL。
     *
     * @param moduleRelativePath String，以 back 或当前后端模块为基准的 SQL 相对路径
     * @return String，UTF-8 正式 SQL 文本
     * @throws Exception 正式 SQL 文件无法读取时测试失败
     */
    private String readSql(String moduleRelativePath) throws Exception
    {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null)
        {
            Path candidate = current.resolve(moduleRelativePath);
            if (Files.isRegularFile(candidate))
            {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            candidate = current.resolve("back").resolve(moduleRelativePath);
            if (Files.isRegularFile(candidate))
            {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("未找到正式工作流 SQL: " + moduleRelativePath);
    }
}
