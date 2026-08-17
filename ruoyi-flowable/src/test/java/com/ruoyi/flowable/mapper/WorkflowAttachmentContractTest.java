package com.ruoyi.flowable.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class WorkflowAttachmentContractTest
{
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
        assertThat(configuration.hasStatement(namespace + "ensureOwnerQuotaGuard")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "selectOwnerQuotaGuardForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "selectTemporaryQuotaUsage")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectById")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectByIdsForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "selectByDraftIdForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace + "bindDraftAttachment")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "markDraftAttachmentDeleted")).isTrue();
        assertThat(configuration.hasStatement(namespace
                + "bindDraftStartAttachment")).isTrue();
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
        assertThat(configuration.hasStatement(namespace
                + "selectCleanupCandidatesForUpdate")).isTrue();
        assertThat(configuration.hasStatement(namespace + "claimCleanupCandidates")).isTrue();
        assertThat(configuration.hasStatement(namespace + "claimDeletedAttachment")).isTrue();
        assertThat(configuration.hasStatement(namespace + "selectClaimedByToken")).isTrue();
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

        BoundSql ensureOwnerGuard = configuration.getMappedStatement(
                namespace + "ensureOwnerQuotaGuard").getBoundSql(
                        Map.of("ownerUserId", 7L));
        assertThat(normalizeSql(ensureOwnerGuard.getSql())).contains(
                "insert into wf_attachment_quota_guard",
                "select ?",
                "where ? > 0",
                "on duplicate key update owner_user_id = values(owner_user_id)");

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
                "attachment_status in ('temp', 'draft')",
                "attachment_status in ('expired', 'deleted')",
                "storage_deleted_time is null");

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
                namespace + "selectCleanupCandidatesForUpdate")
                .getBoundSql(Map.of("limit", 100));
        assertThat(normalizeSql(cleanupCandidates.getSql())).contains(
                "cleanup_next_retry_time is null",
                "cleanup_next_retry_time <= current_timestamp(3)",
                "cleanup_claim_token is null",
                "cleanup_lease_until <= current_timestamp(3)",
                "coalesce(cleanup_next_retry_time, expire_time)",
                "limit ?", "for update skip locked");

        BoundSql claimBatch = configuration.getMappedStatement(
                namespace + "claimCleanupCandidates").getBoundSql(Map.of(
                        "attachmentIds", List.of(
                                "d9428888-122b-4c6f-8f0c-9c3e1dbd3210"),
                        "claimToken", "b9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "leaseSeconds", 300L));
        assertThat(normalizeSql(claimBatch.getSql())).contains(
                "when attachment_status = 'temp' then 'expired'",
                "cleanup_claim_token = ?",
                "cleanup_lease_until = timestampadd(second, ?, current_timestamp(3))",
                "attachment_id in ( ? )");

        BoundSql scheduleRetry = configuration.getMappedStatement(
                namespace + "scheduleCleanupRetry").getBoundSql(Map.of(
                        "attachmentId", "d9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "claimToken", "b9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "expectedRetryCount", 2,
                        "nextRetryTime", java.time.LocalDateTime.now(),
                        "errorCode", "attachment_storage_cleanup_failed"));
        assertThat(normalizeSql(scheduleRetry.getSql())).contains(
                "when cleanup_retry_count < 2147483647 then cleanup_retry_count + 1",
                "cleanup_next_retry_time = ?",
                "cleanup_last_error_code = ?",
                "cleanup_claim_token = null",
                "cleanup_lease_until = null",
                "cleanup_claim_token = ?",
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

        BoundSql draftRows = configuration.getMappedStatement(
                namespace + "selectByDraftIdForUpdate").getBoundSql(Map.of(
                        "draftId", "53f4cb2f-7d69-4c77-bf93-2b38f266c618",
                        "ownerUserId", 7L));
        assertThat(normalizeSql(draftRows.getSql())).contains(
                "where draft_id = ? and owner_user_id = ?",
                "attachment_status = 'draft'",
                "order by attachment_id",
                "for update");

        BoundSql bindDraft = configuration.getMappedStatement(
                namespace + "bindDraftAttachment").getBoundSql(Map.of(
                        "attachmentId", "d9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "ownerUserId", 7L,
                        "fieldName", "files",
                        "draftId", "53f4cb2f-7d69-4c77-bf93-2b38f266c618"));
        assertThat(normalizeSql(bindDraft.getSql())).contains(
                "attachment_status = 'draft'",
                "draft_id = ?",
                "attachment_status = 'temp'",
                "expire_time > current_timestamp(3)",
                "draft_id is null");

        BoundSql deleteDraft = configuration.getMappedStatement(
                namespace + "markDraftAttachmentDeleted").getBoundSql(Map.of(
                        "attachmentId", "d9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "ownerUserId", 7L,
                        "draftId", "53f4cb2f-7d69-4c77-bf93-2b38f266c618"));
        assertThat(normalizeSql(deleteDraft.getSql())).contains(
                "attachment_status = 'deleted'",
                "draft_id = null",
                "draft_id = ?",
                "attachment_status = 'draft'");

        BoundSql submitDraft = configuration.getMappedStatement(
                namespace + "bindDraftStartAttachment").getBoundSql(Map.of(
                        "attachmentId", "d9428888-122b-4c6f-8f0c-9c3e1dbd3210",
                        "ownerUserId", 7L,
                        "fieldName", "files",
                        "draftId", "53f4cb2f-7d69-4c77-bf93-2b38f266c618",
                        "processInstanceId", "instance-1",
                        "nodeKey", "start"));
        assertThat(normalizeSql(submitDraft.getSql())).contains(
                "attachment_status = 'bound'",
                "draft_id = null",
                "process_instance_id = ?",
                "draft_id = ?",
                "attachment_status = 'draft'");

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

}
