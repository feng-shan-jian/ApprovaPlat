package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WorkflowAttachmentContractTest
{
    /**
     * 验证正式附件表包含完整状态、关联、摘要约束和清理查询索引。
     * @return void，DDL 缺少任一生产约束时测试失败
     * @throws Exception 读取正式 SQL 失败
     */
    @Test
    void definesFormalAttachmentStateAndIntegrityConstraints() throws Exception
    {
        String ddl = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0__workflow_business.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(ddl).contains(
                "create table if not exists `wf_attachment_quota_guard`",
                "constraint `chk_wf_attachment_quota_guard_owner`",
                "check (`owner_user_id` >= 0)",
                "insert ignore into `wf_attachment_quota_guard` (`owner_user_id`)",
                "values (0)",
                "create table if not exists `wf_attachment`",
                "unique key `uk_wf_attachment_storage_key` (`storage_key`)",
                "key `idx_wf_attachment_owner_status_expire` (`owner_user_id`, `attachment_status`, `expire_time`)",
                "key `idx_wf_attachment_cleanup_due` (`attachment_status`, `cleanup_next_retry_time`, `expire_time`)",
                "key `idx_wf_attachment_instance_field` (`process_instance_id`, `field_name`, `attachment_status`)",
                "constraint `chk_wf_attachment_status`",
                "constraint `chk_wf_attachment_sha256`",
                "constraint `chk_wf_attachment_state_relation`",
                "constraint `chk_wf_attachment_cleanup_retry`",
                "`cleanup_retry_count` int",
                "not null default 0 comment '物理清理连续失败并已调度重试的次数'",
                "`cleanup_next_retry_time` datetime(3)",
                "`cleanup_last_error_code` varchar(64)",
                "`attachment_status` = 'bound'",
                "`process_instance_id` is not null",
                "`node_key` is not null",
                "`storage_deleted_time` is null")
                .doesNotContain("drop table");
    }

    /**
     * 验证已部署数据库的附件清理重试增量可重复执行且不会删除或重写历史数据。
     * @return void，增量缺少对象探测、正式约束或包含破坏性语句时测试失败
     * @throws Exception 读取正式增量 SQL 失败
     */
    @Test
    void definesIdempotentAttachmentCleanupRetryMigration() throws Exception
    {
        String migration = Files.readString(findProjectSql(
                "sql/flowable/business/8.0.0.3__workflow_attachment_cleanup_retry.sql"),
                StandardCharsets.UTF_8).toLowerCase();

        assertThat(migration).contains(
                "from information_schema.columns",
                "column_name = 'cleanup_retry_count'",
                "column_name = 'cleanup_next_retry_time'",
                "column_name = 'cleanup_last_error_code'",
                "column_type = 'int'",
                "datetime_precision = 3",
                "character_set_name = 'ascii'",
                "collation_name = 'ascii_bin'",
                "from information_schema.table_constraints tc",
                "join information_schema.check_constraints cc",
                "idx_wf_attachment_cleanup_due",
                "chk_wf_attachment_cleanup_retry",
                "prepare wf_attachment_retry_statement",
                "execute wf_attachment_retry_statement",
                "'do 0'")
                .doesNotContain("drop table", "drop column", "delete from",
                        "update `wf_attachment`");
    }

    /**
     * 验证附件验收 SQL 保持只读并覆盖所有者、实例、任务和状态数据关联。
     * @return void，验收脚本存在写操作或覆盖不足时测试失败
     * @throws Exception 读取正式验收 SQL 失败
     */
    @Test
    void verifiesAttachmentSchemaAndRelationsReadOnly() throws Exception
    {
        String verification = Files.readString(findProjectSql(
                "sql/flowable/verify/8.0.0__verify_workflow_business.sql"),
                StandardCharsets.UTF_8);
        Pattern mutation = Pattern.compile(
                "(?im)^\\s*(insert|update|delete|create|drop|alter|truncate|replace|call|set)\\b");

        assertThat(mutation.matcher(verification).find()).isFalse();
        assertThat(verification.toLowerCase()).contains(
                "wf_attachment_invalid_row",
                "wf_attachment_missing_owner",
                "wf_attachment_bound_missing_process_instance",
                "wf_attachment_bound_task_mismatch",
                "wf_attachment_bound_node_mismatch",
                "wf_attachment_quota_guard_invalid_owner",
                "wf_attachment_quota_guard_global_missing",
                "wf_attachment_invalid_cleanup_retry",
                "wf_attachment_cleanup_retry_columns",
                "wf_attachment_cleanup_retry_check_clause",
                "where owner_user_id < 0",
                "uk_wf_attachment_storage_key",
                "chk_wf_attachment_state_relation",
                "chk_wf_attachment_cleanup_retry");
    }

    /**
     * 使用真实 MyBatis XML 解析器验证全部附件语句可注册，并核对行锁及空集合防护 SQL。
     * @return void，Mapper XML 无法解析或动态 SQL 契约漂移时测试失败
     * @throws Exception 读取 Mapper 资源失败
     */
    @Test
    void parsesAttachmentMapperAndBuildsGuardedSql() throws Exception
    {
        Configuration configuration = new Configuration();
        String resource = "mapper/flowable/WfAttachmentMapper.xml";
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource))
        {
            assertThat(input).as("附件 Mapper XML 必须进入模块资源").isNotNull();
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }

        String namespace = WfAttachmentMapper.class.getName() + ".";
        assertThat(configuration.hasStatement(namespace + "insert")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "selectGlobalQuotaGuardForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace + "ensureOwnerQuotaGuard")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "selectOwnerQuotaGuardForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "selectTemporaryQuotaUsage")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "selectUndeletedTotalBytes")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectById")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectByIdsForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "countBoundByProcessInstanceIds")).isTrue();
        assertThat(configuration.hasStatement(namespace + "bindStartAttachment")).isTrue();
        assertThat(configuration.hasStatement(namespace + "bindTaskAttachment")).isTrue();
        assertThat(configuration.hasStatement(namespace + "markDeletedByOwner")).isTrue();
        assertThat(configuration.hasStatement(namespace + "countByStatus")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "countPendingStorageDeletion")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "countDeferredStorageDeletion")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectCleanupCandidates")).isTrue();
        assertThat(configuration.hasStatement(namespace + "markExpired")).isTrue();
        assertThat(configuration.hasStatement(namespace + "markStorageDeleted")).isTrue();
        assertThat(configuration.hasStatement(namespace + "scheduleCleanupRetry")).isTrue();
        var fileSizeMapping = configuration.getResultMap(
                namespace + "WfAttachmentResult").getConstructorResultMappings().stream()
                .filter(mapping -> "file_size".equals(mapping.getColumn()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("附件 file_size 构造映射不存在"));
        assertThat(fileSizeMapping.getJavaType()).isEqualTo(long.class);
        var cleanupRetryCountMapping = configuration.getResultMap(
                namespace + "WfAttachmentResult").getConstructorResultMappings().stream()
                .filter(mapping -> "cleanup_retry_count".equals(mapping.getColumn()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "附件 cleanup_retry_count 构造映射不存在"));
        assertThat(cleanupRetryCountMapping.getJavaType()).isEqualTo(int.class);
        var quotaMappings = configuration.getResultMap(
                namespace + "WorkflowAttachmentQuotaUsageResult")
                .getConstructorResultMappings();
        assertThat(quotaMappings).extracting(mapping -> mapping.getColumn())
                .containsExactly("temporary_count", "temporary_bytes");

        BoundSql globalQuotaLock = configuration.getMappedStatement(
                namespace + "selectGlobalQuotaGuardForUpdate").getBoundSql(Map.of());
        assertThat(normalizeSql(globalQuotaLock.getSql())).contains(
                "from wf_attachment_quota_guard",
                "where owner_user_id = 0",
                "for update");

        BoundSql ensureOwnerGuard = configuration.getMappedStatement(
                namespace + "ensureOwnerQuotaGuard").getBoundSql(
                        Map.of("ownerUserId", 7L));
        assertThat(normalizeSql(ensureOwnerGuard.getSql())).contains(
                "insert ignore into wf_attachment_quota_guard",
                "select ?",
                "where ? > 0");

        BoundSql quotaLock = configuration.getMappedStatement(
                namespace + "selectOwnerQuotaGuardForUpdate").getBoundSql(
                        Map.of("ownerUserId", 7L));
        assertThat(normalizeSql(quotaLock.getSql())).contains(
                "from wf_attachment_quota_guard",
                "where owner_user_id = ?",
                "owner_user_id > 0",
                "for update");

        BoundSql quotaUsage = configuration.getMappedStatement(
                namespace + "selectTemporaryQuotaUsage").getBoundSql(
                        Map.of("ownerUserId", 7L));
        assertThat(normalizeSql(quotaUsage.getSql())).contains(
                "attachment_status = 'temp'",
                "attachment_status in ('expired', 'deleted')",
                "storage_deleted_time is null");

        BoundSql globalUsage = configuration.getMappedStatement(
                namespace + "selectUndeletedTotalBytes").getBoundSql(Map.of());
        assertThat(normalizeSql(globalUsage.getSql())).contains(
                "from wf_attachment",
                "storage_deleted_time is null",
                "attachment_status in ('temp', 'bound', 'expired', 'deleted')");

        BoundSql statusCount = configuration.getMappedStatement(
                namespace + "countByStatus").getBoundSql(Map.of("status", "TEMP"));
        assertThat(normalizeSql(statusCount.getSql())).contains(
                "from wf_attachment", "where attachment_status = ?");

        BoundSql pendingCleanup = configuration.getMappedStatement(
                namespace + "countPendingStorageDeletion").getBoundSql(Map.of());
        assertThat(normalizeSql(pendingCleanup.getSql())).contains(
                "attachment_status in ('expired', 'deleted')",
                "storage_deleted_time is null");

        BoundSql deferredCleanup = configuration.getMappedStatement(
                namespace + "countDeferredStorageDeletion").getBoundSql(Map.of());
        assertThat(normalizeSql(deferredCleanup.getSql())).contains(
                "attachment_status in ('expired', 'deleted')",
                "storage_deleted_time is null",
                "cleanup_next_retry_time > current_timestamp(3)");

        BoundSql cleanupCandidates = configuration.getMappedStatement(
                namespace + "selectCleanupCandidates").getBoundSql(Map.of("limit", 100));
        assertThat(normalizeSql(cleanupCandidates.getSql())).contains(
                "cleanup_next_retry_time is null",
                "cleanup_next_retry_time <= current_timestamp(3)",
                "coalesce(cleanup_next_retry_time, expire_time)",
                "limit ?");

        BoundSql scheduleRetry = configuration.getMappedStatement(
                namespace + "scheduleCleanupRetry").getBoundSql(Map.of(
                        "attachmentId", "d9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "expectedRetryCount", 2,
                        "nextRetryTime", java.time.LocalDateTime.now(),
                        "errorCode", "attachment_storage_cleanup_failed"));
        assertThat(normalizeSql(scheduleRetry.getSql())).contains(
                "when cleanup_retry_count < 2147483647 then cleanup_retry_count + 1",
                "cleanup_next_retry_time = ?",
                "cleanup_last_error_code = ?",
                "cleanup_retry_count = ?");

        BoundSql locked = configuration.getMappedStatement(
                namespace + "selectByIdsForUpdate").getBoundSql(
                        Map.of("attachmentIds", List.of(
                                "d9428888-122b-4c6f-8f0c-9c3e1dbd3210")));
        assertThat(normalizeSql(locked.getSql())).contains(
                "where attachment_id in ( ? )", "order by attachment_id", "for update");

        BoundSql empty = configuration.getMappedStatement(
                namespace + "selectByIdsForUpdate").getBoundSql(
                        Map.of("attachmentIds", List.of()));
        assertThat(normalizeSql(empty.getSql())).contains("where 1 = 0", "for update");

        BoundSql boundCount = configuration.getMappedStatement(
                namespace + "countBoundByProcessInstanceIds").getBoundSql(
                        Map.of("processInstanceIds", List.of("instance-1", "instance-2")));
        assertThat(normalizeSql(boundCount.getSql())).contains(
                "from wf_attachment",
                "where attachment_status = 'bound'",
                "process_instance_id in ( ? , ? )");

        BoundSql emptyBoundCount = configuration.getMappedStatement(
                namespace + "countBoundByProcessInstanceIds").getBoundSql(
                        Map.of("processInstanceIds", List.of()));
        assertThat(normalizeSql(emptyBoundCount.getSql())).contains("where 1 = 0");

        BoundSql bind = configuration.getMappedStatement(
                namespace + "bindStartAttachment").getBoundSql(Map.of(
                        "attachmentId", "d9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "ownerUserId", 7L,
                        "fieldName", "files",
                        "processInstanceId", "instance-1",
                        "nodeKey", "start"));
        assertThat(normalizeSql(bind.getSql())).contains(
                "attachment_status = 'bound'",
                "node_key = ?",
                "owner_user_id = ?",
                "field_name = ?",
                "attachment_status = 'temp'",
                "expire_time > current_timestamp(3)");

        BoundSql bindTask = configuration.getMappedStatement(
                namespace + "bindTaskAttachment").getBoundSql(Map.of(
                        "attachmentId", "d9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "ownerUserId", 7L,
                        "fieldName", "files",
                        "processInstanceId", "instance-1",
                        "taskId", "task-1",
                        "nodeKey", "review"));
        assertThat(normalizeSql(bindTask.getSql())).contains(
                "process_instance_id = ?",
                "task_id = ?",
                "node_key = ?",
                "owner_user_id = ?",
                "attachment_status = 'temp'",
                "process_instance_id is null",
                "task_id is null",
                "node_key is null");
    }

    /**
     * 将动态 SQL 空白折叠为便于断言的单行小写文本。
     * @param sql String，MyBatis 生成 SQL
     * @return String，空白折叠后的小写 SQL
     */
    private String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
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
