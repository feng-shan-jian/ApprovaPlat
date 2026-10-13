package com.ruoyi.flowable.service.retention;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.config.WorkflowDataRetentionProperties;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** 十二域使用正式配置、SQL、Mapper 和 MySQL 事务；历史事实合成，不启动引擎。 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowDataRetentionMysqlTest
{
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2001, 1, 1, 0, 0);
    private static final LocalDateTime EXECUTION = CUTOFF.plusDays(1);
    private final long base = 1_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 500_000_000L);
    private final List<Row> owned = new ArrayList<>();
    private int sequence;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transaction;
    private WorkflowDataRetentionProperties properties;
    private final WorkflowDataRetentionConfiguration configuration = new WorkflowDataRetentionConfiguration();

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        dataSource = new DriverManagerDataSource(url, System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        for (String table : java.util.List.of("wf_notification_outbox", "wf_runtime_event_request",
                "wf_process_draft", "wf_collaboration_message", "wf_collaboration_outbox", "wf_attachment",
                "wf_notification_inbox", "wf_bpmn_event_audit", "wf_task_sla_execution", "wf_copy",
                "wf_controlled_loop_execution", "wf_multi_instance_round"))
        {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class))
                    .as("Shared development retention preflight: %s must be empty", table).isZero();
        }
        manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        properties = new WorkflowDataRetentionProperties();
        properties.setBatchSize(1);
        var day = Duration.ofDays(1);
        properties.setNotificationOutboxRetention(day);
        properties.setRuntimeEventRetention(day);
        properties.setProcessDraftRetention(day);
        properties.setCollaborationRetention(day);
        properties.setAttachmentMetadataRetention(day);
        properties.setNotificationInboxRetention(day);
        properties.setBpmnEventAuditRetention(day);
        properties.setTaskSlaRetention(day);
        properties.setCopyRetention(day);
        properties.setControlledLoopRetention(day);
        properties.setMultiInstanceRoundRetention(day);
        insert("sys_user", "user_id", base, "user_name", "retention" + base, "nick_name", "retention");
        insert("wf_integration_credential", "credential_id", base, "credential_name", "retention", "token_prefix", uuid().substring(0, 12),
                "token_hash", hash(), "scopes", "MESSAGE", "allowed_variables", "value", "rate_limit_per_minute", 1000, "create_by", "1");
        insert("wf_connector_endpoint", "endpoint_id", base, "endpoint_key", "retention" + base, "endpoint_name", "retention",
                "base_url", "http://127.0.0.1:1", "allowed_methods", "POST", "path_prefix", "/", "auth_type", "NONE",
                "connect_timeout_ms", 1000, "request_timeout_ms", 1000, "network_scope", "PRIVATE", "revision_no", 1, "checksum", hash());
    }

    @AfterEach
    void clean()
    {
        if (jdbc == null) return;
        var reverse = new ArrayList<>(owned);
        Collections.reverse(reverse);
        for (Row row : reverse)
        {
            jdbc.update("delete from " + row.table + " where " + row.key + "=?", row.id);
            assertThat(exists(row)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(WorkflowDataRetentionDomain.class)
    void eachDomainPreservesActiveAndTooRecentRowsAndHonorsInclusiveCutoffAndBatch(WorkflowDataRetentionDomain domain)
    {
        Row old = candidate(domain, CUTOFF.minusSeconds(1), true);
        Row boundary = candidate(domain, CUTOFF, true);
        Row recent = candidate(domain, CUTOFF.plusNanos(1_000_000), true);
        Row active = candidate(domain, CUTOFF.minusDays(100), false);
        var cleaner = cleaner(domain);
        assertThat(cleaner.cleanBatch(EXECUTION).deleted()).isOne();
        assertThat(exists(old)).isFalse();
        assertThat(exists(boundary)).isTrue();
        assertThat(cleaner.cleanBatch(EXECUTION).deleted()).isOne();
        assertThat(exists(boundary)).isFalse();
        assertThat(cleaner.cleanBatch(EXECUTION).deleted()).isZero();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(active)).isTrue();
        if (domain == WorkflowDataRetentionDomain.TASK_SLA_EXECUTION)
            assertThat(jdbc.queryForObject("select count(*) from wf_task_sla_audit where sla_execution_id=?", Long.class, old.id)).isZero();
        if (domain == WorkflowDataRetentionDomain.COLLABORATION_MESSAGE || domain == WorkflowDataRetentionDomain.COLLABORATION_OUTBOX)
            assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message_audit where message_id=?", Long.class, old.id)).isZero();
    }

    @ParameterizedTest
    @EnumSource(WorkflowDataRetentionDomain.class)
    void eachDomainSkipsAnotherTransactionsLockedRow(WorkflowDataRetentionDomain domain) throws Exception
    {
        Row locked = candidate(domain, CUTOFF.minusDays(1), true);
        Row available = candidate(domain, CUTOFF, true);
        var cleaner = cleaner(domain);
        var executor = Executors.newSingleThreadExecutor();
        try
        {
            transaction.executeWithoutResult(status -> {
                jdbc.queryForList("select " + locked.key + " from " + locked.table + " where " + locked.key + "=? for update", locked.id);
                var future = executor.submit(() -> cleaner.cleanBatch(EXECUTION));
                try { assertThat(future.get(5, TimeUnit.SECONDS).deleted()).isOne(); }
                catch (Exception failure) { throw new AssertionError("SKIP LOCKED must make progress for " + domain, failure); }
                assertThat(exists(locked)).isTrue();
            });
            assertThat(exists(available)).isFalse();
            assertThat(cleaner.cleanBatch(EXECUTION).deleted()).isOne();
            assertThat(exists(locked)).isFalse();
        }
        finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void statusRecheckFailureRestoresDependentAuditsAndParentInSameTransaction()
    {
        Row row = candidate(WorkflowDataRetentionDomain.COLLABORATION_MESSAGE, CUTOFF, true);
        var mapper = spy(mapper(WfCollaborationMessageMapper.class));
        doAnswer(call -> {
            jdbc.update("update wf_collaboration_message set status='RETRYING',complete_time=null where message_id=?", row.id);
            return call.callRealMethod();
        }).when(mapper).deleteRetentionByIds(anyList(), any());
        var cleaner = proxy(configuration.collaborationMessageRetentionCleaner(mapper, properties));
        assertThatThrownBy(() -> cleaner.cleanBatch(EXECUTION)).isInstanceOf(IllegalStateException.class).hasMessageContaining("领取行数不一致");
        assertThat(jdbc.queryForObject("select status from wf_collaboration_message where message_id=?", String.class, row.id)).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message_audit where message_id=?", Long.class, row.id)).isOne();
        assertThat(cleaner(WorkflowDataRetentionDomain.COLLABORATION_MESSAGE).cleanBatch(EXECUTION).deleted()).isOne();
    }

    @Test
    void unreadCopyOnEndedProcessAndPhysicallyUncleanedDeletedAttachmentAreRetained()
    {
        Row copy = candidate(WorkflowDataRetentionDomain.COPY, CUTOFF.minusDays(1), true);
        jdbc.update("update wf_copy set read_status='0',read_time=null where copy_id=?", copy.id);
        Row attachment = candidate(WorkflowDataRetentionDomain.ATTACHMENT_METADATA, CUTOFF.minusDays(1), true);
        jdbc.update("update wf_attachment set storage_deleted_time=null where attachment_id=?", attachment.id);
        assertThat(cleaner(WorkflowDataRetentionDomain.COPY).cleanBatch(EXECUTION).deleted()).isZero();
        assertThat(cleaner(WorkflowDataRetentionDomain.ATTACHMENT_METADATA).cleanBatch(EXECUTION).deleted()).isZero();
        assertThat(exists(copy)).isTrue();
        assertThat(exists(attachment)).isTrue();
    }

    @Test
    void alternateTerminalStatesAreDeletedButNotificationAndCollaborationDeadLettersRemain()
    {
        for (var domain : List.of(WorkflowDataRetentionDomain.NOTIFICATION_OUTBOX, WorkflowDataRetentionDomain.COLLABORATION_OUTBOX))
        {
            Row cancelled = candidate(domain, CUTOFF, true);
            Row dead = candidate(domain, CUTOFF, true);
            jdbc.update("update " + cancelled.table + " set status='CANCELLED' where " + cancelled.key + "=?", cancelled.id);
            jdbc.update("update " + dead.table + " set status='DEAD_LETTER' where " + dead.key + "=?", dead.id);
            assertThat(cleaner(domain).cleanBatch(EXECUTION).deleted()).isOne();
            assertThat(exists(cancelled)).isFalse();
            assertThat(cleaner(domain).cleanBatch(EXECUTION).deleted()).isZero();
            assertThat(exists(dead)).isTrue();
        }
        Row dead = candidate(WorkflowDataRetentionDomain.COLLABORATION_MESSAGE, CUTOFF, true);
        jdbc.update("update wf_collaboration_message set status='DEAD_LETTER' where message_id=?", dead.id);
        assertThat(cleaner(WorkflowDataRetentionDomain.COLLABORATION_MESSAGE).cleanBatch(EXECUTION).deleted()).isZero();
        assertThat(exists(dead)).isTrue();
        Row failed = candidate(WorkflowDataRetentionDomain.RUNTIME_EVENT, CUTOFF, true);
        jdbc.update("update wf_runtime_event_request set status='FAILED' where request_id=?", failed.id);
        assertThat(cleaner(WorkflowDataRetentionDomain.RUNTIME_EVENT).cleanBatch(EXECUTION).deleted()).isOne();
        assertThat(exists(failed)).isFalse();
        Row draft = candidate(WorkflowDataRetentionDomain.PROCESS_DRAFT, CUTOFF, true);
        jdbc.update("update wf_process_draft set draft_status='SUBMITTED',deleted_time=null,submitted_time=?,submitted_process_instance_id=? where draft_id=?", CUTOFF, uuid(), draft.id);
        assertThat(cleaner(WorkflowDataRetentionDomain.PROCESS_DRAFT).cleanBatch(EXECUTION).deleted()).isOne();
        assertThat(exists(draft)).isFalse();
        Row escalated = candidate(WorkflowDataRetentionDomain.TASK_SLA_EXECUTION, CUTOFF, true);
        jdbc.update("update wf_task_sla_execution set status='ESCALATED' where sla_execution_id=?", escalated.id);
        assertThat(cleaner(WorkflowDataRetentionDomain.TASK_SLA_EXECUTION).cleanBatch(EXECUTION).deleted()).isOne();
        assertThat(exists(escalated)).isFalse();
        Row copy = candidate(WorkflowDataRetentionDomain.COPY, CUTOFF, true);
        jdbc.update("update wf_copy set read_status='0',read_time=null,del_flag='2' where copy_id=?", copy.id);
        assertThat(cleaner(WorkflowDataRetentionDomain.COPY).cleanBatch(EXECUTION).deleted()).isOne();
        assertThat(exists(copy)).isFalse();
    }

    private WorkflowDataRetentionCleaner cleaner(WorkflowDataRetentionDomain domain)
    {
        return proxy(switch (domain) {
            case NOTIFICATION_OUTBOX -> configuration.notificationOutboxRetentionCleaner(jdbc, properties);
            case RUNTIME_EVENT -> configuration.runtimeEventRetentionCleaner(mapper(WfRuntimeEventRequestMapper.class), properties);
            case PROCESS_DRAFT -> configuration.processDraftRetentionCleaner(mapper(WfProcessDraftMapper.class), properties);
            case COLLABORATION_MESSAGE -> configuration.collaborationMessageRetentionCleaner(mapper(WfCollaborationMessageMapper.class), properties);
            case COLLABORATION_OUTBOX -> configuration.collaborationOutboxRetentionCleaner(mapper(WfCollaborationOutboxMapper.class), properties);
            case ATTACHMENT_METADATA -> configuration.attachmentMetadataRetentionCleaner(mapper(WfAttachmentMapper.class), properties);
            case NOTIFICATION_INBOX -> configuration.notificationInboxRetentionCleaner(jdbc, properties);
            case BPMN_EVENT_AUDIT -> configuration.bpmnEventAuditRetentionCleaner(jdbc, properties);
            case TASK_SLA_EXECUTION -> configuration.taskSlaRetentionCleaner(jdbc, properties);
            case COPY -> configuration.copyRetentionCleaner(jdbc, properties);
            case CONTROLLED_LOOP_EXECUTION -> configuration.controlledLoopRetentionCleaner(jdbc, properties);
            case MULTI_INSTANCE_ROUND -> configuration.multiInstanceRoundRetentionCleaner(jdbc, properties);
        });
    }

    private WorkflowDataRetentionCleaner proxy(WorkflowDataRetentionCleaner target)
    {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (WorkflowDataRetentionCleaner) factory.getProxy();
    }

    private <T> T mapper(Class<T> type)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "retention-" + type.getSimpleName(), type,
                "mapper/flowable/" + type.getSimpleName() + ".xml");
    }

    private Row candidate(WorkflowDataRetentionDomain domain, LocalDateTime time, boolean terminal)
    {
        String id = uuid();
        long number = base + ++sequence;
        String process = id;
        if (List.of(WorkflowDataRetentionDomain.BPMN_EVENT_AUDIT, WorkflowDataRetentionDomain.COPY,
                WorkflowDataRetentionDomain.CONTROLLED_LOOP_EXECUTION, WorkflowDataRetentionDomain.MULTI_INSTANCE_ROUND).contains(domain))
            insert("ACT_HI_PROCINST", "ID_", process, "PROC_INST_ID_", process, "PROC_DEF_ID_", "retention",
                    "START_TIME_", CUTOFF.minusYears(2), "END_TIME_", terminal ? time : null);
        return switch (domain) {
            case NOTIFICATION_OUTBOX -> insert("wf_notification_outbox", "outbox_id", number, "idempotency_key", hash(),
                    "source_type", "APPROVAL", "source_id", id, "event_type", "TASK_ARRIVED", "channel", "EMAIL", "recipient_user_id", base,
                    "process_definition_key", "retention", "process_instance_id", id, "title", "retention", "content", "body", "route_path", "/workflow",
                    "status", terminal ? "PROCESSED" : "PENDING", "max_attempts", 3, "processed_time", terminal ? time : null, "create_time", CUTOFF.minusYears(1));
            case RUNTIME_EVENT -> insert("wf_runtime_event_request", "request_id", id, "credential_id", base, "event_type", "MESSAGE", "event_name", "incoming",
                    "correlation_type", "PROCESS_INSTANCE", "correlation_value", id, "variables_sha256", hash(), "status", terminal ? "PROCESSED" : "RECEIVED",
                    "result_code", terminal ? "OK" : null, "result_summary", terminal ? "done" : null, "complete_time", terminal ? time : null, "create_time", CUTOFF.minusYears(1));
            case PROCESS_DRAFT -> insert("wf_process_draft", "draft_id", id, "owner_user_id", base, "process_definition_id", "definition", "process_definition_key", "retention",
                    "process_definition_version", 1, "deployment_id", "deployment", "process_name", "retention", "source_type", "EMBEDDED", "form_key", "start", "start_node_key", "start",
                    "form_name", "retention", "snapshot_create_time", CUTOFF.minusYears(1), "form_snapshot", "{}", "form_snapshot_sha256", hash(), "start_multi_instance_assignments", "[]",
                    "form_values", "{}", "multi_instance_user_ids", "{}", "draft_status", terminal ? "DELETED" : "ACTIVE", "deleted_time", terminal ? time : null, "create_time", CUTOFF.minusYears(1));
            case COLLABORATION_MESSAGE, COLLABORATION_OUTBOX -> collaboration(domain, id, number, time, terminal);
            case ATTACHMENT_METADATA -> insert("wf_attachment", "attachment_id", id, "owner_user_id", base, "field_name", "evidence", "original_name", "evidence.txt",
                    "storage_key", "2000/01/01/" + id.replace("-", "") + ".txt", "content_type", "text/plain", "file_size", 1, "sha256", hash(), "attachment_status", terminal ? "DELETED" : "TEMP",
                    "expire_time", CUTOFF.minusYears(1), "storage_deleted_time", terminal ? time : null, "create_time", CUTOFF.minusYears(1));
            case NOTIFICATION_INBOX -> insert("wf_notification_inbox", "notification_id", number, "notification_key", hash(), "source_type", "APPROVAL", "source_id", id,
                    "recipient_user_id", base, "event_type", "TASK_ARRIVED", "title", "retention", "content", "body", "process_instance_id", id, "route_path", "/workflow",
                    "read_status", terminal ? "READ" : "UNREAD", "read_time", terminal ? time : null, "create_time", CUTOFF.minusYears(1));
            case BPMN_EVENT_AUDIT -> insert("wf_bpmn_event_audit", "audit_id", number, "idempotency_key", hash(), "deployment_id", "deployment", "process_instance_id", process,
                    "process_definition_id", "definition", "execution_id", id, "source_element_id", "step", "source_type", "MANUAL", "event_type", "ERROR", "event_code", "TEST",
                    "event_name", "retention", "match_status", "UNMATCHED", "create_time", time);
            case TASK_SLA_EXECUTION -> sla(id, number, time, terminal);
            case COPY -> insert("wf_copy", "copy_id", number, "copy_event_id", id, "process_id", "definition", "deployment_id", "deployment", "instance_id", process,
                    "user_id", base, "originator_id", base, "read_status", "1", "read_time", CUTOFF.minusYears(1), "create_time", time);
            case CONTROLLED_LOOP_EXECUTION -> insert("wf_controlled_loop_execution", "execution_id", number, "deploy_id", "deployment", "process_definition_id", "definition",
                    "process_instance_id", process, "activity_id", "task", "task_id", id, "iteration_no", 1, "actor_user_id", "100", "decision_value", "yes", "outcome", "EXIT", "create_time", time);
            case MULTI_INSTANCE_ROUND -> insert("wf_multi_instance_round", "round_id", number, "deploy_id", "deployment", "process_definition_id", "definition",
                    "process_instance_id", process, "activity_id", "task", "root_execution_id", id, "round_no", 1, "mode", "ALL", "members_json", "[\"100\"]",
                    "round_status", terminal ? "COMPLETED" : "ACTIVE", "complete_time", terminal ? CUTOFF.minusYears(1) : null, "create_time", CUTOFF.minusYears(1));
        };
    }

    private Row collaboration(WorkflowDataRetentionDomain domain, String id, long number, LocalDateTime time, boolean terminal)
    {
        String channel = hash();
        insert("wf_collaboration_channel", "channel_id", channel, "target_process_definition_key", "retention", "correlation_type", "BUSINESS_KEY", "correlation_value", id);
        boolean inbound = domain == WorkflowDataRetentionDomain.COLLABORATION_MESSAGE;
        Row row = inbound
                ? insert("wf_collaboration_message", "message_id", id, "credential_id", base, "actor_user_id", "1", "channel_id", channel, "sequence_no", 1,
                    "message_name", "incoming", "target_process_definition_key", "retention", "correlation_key", id, "variables_json", "{}", "payload_sha256", hash(),
                    "status", terminal ? "PROCESSED" : "RETRYING", "complete_time", terminal ? time : null, "create_time", CUTOFF.minusYears(1))
                : insert("wf_collaboration_outbox", "message_id", id, "channel_id", channel, "sequence_no", 1, "source_process_definition_key", "source",
                    "source_process_instance_id", id, "source_execution_id", id, "source_element_id", "send", "message_name", "incoming", "target_process_definition_key", "retention",
                    "correlation_key", id, "endpoint_id", base, "endpoint_revision", 1, "request_path", "/", "delivery_config_json", "{}", "variables_json", "{}", "payload_sha256", hash(),
                    "status", terminal ? "PROCESSED" : "PENDING", "complete_time", terminal ? time : null, "create_time", CUTOFF.minusYears(1));
        insert("wf_collaboration_message_audit", "audit_id", number, "message_id", id, "direction", inbound ? "INBOUND" : "OUTBOUND",
                "action", "DELIVER", "actor_type", "SYSTEM", "actor_id", "retention", "to_status", terminal ? "PROCESSED" : "RETRYING", "attempt_no", 1);
        return row;
    }

    private Row sla(String id, long number, LocalDateTime time, boolean terminal)
    {
        Row row = insert("wf_task_sla_execution", "sla_execution_id", number, "deployment_id", "deployment", "process_instance_id", id, "process_definition_id", "definition",
                "task_id", id, "task_definition_key", "task", "status", terminal ? "COMPLETED" : "ACTIVE", "started_at", CUTOFF.minusYears(1),
                "reminder_due_at", CUTOFF.minusDays(2), "escalation_due_at", CUTOFF.minusDays(1), "update_time", time);
        insert("wf_task_sla_audit", "audit_id", number, "sla_execution_id", number, "action_type", "CREATE", "action_ordinal", 0, "detail", "retention");
        return row;
    }

    private Row insert(String table, String key, Object id, Object... fields)
    {
        var values = new LinkedHashMap<String, Object>();
        values.put(key, id);
        for (int i = 0; i < fields.length; i += 2) values.put((String) fields[i], fields[i + 1]);
        Row row = new Row(table, key, id);
        jdbc.update("insert into " + table + " (" + String.join(",", values.keySet()) + ") values (" +
                String.join(",", Collections.nCopies(values.size(), "?")) + ")", values.values().toArray());
        owned.add(row);
        return row;
    }

    private boolean exists(Row row)
    {
        return jdbc.queryForObject("select count(*) from " + row.table + " where " + row.key + "=?", Long.class, row.id) == 1;
    }

    private String uuid() { return UUID.randomUUID().toString(); }
    private String hash() { return uuid().replace("-", "").repeat(2); }
    private record Row(String table, String key, Object id) { }
}
