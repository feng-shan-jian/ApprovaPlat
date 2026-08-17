package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.flowable.service.retention.WorkflowDataRetentionCleaner;
import com.ruoyi.flowable.service.retention.WorkflowDataRetentionCoordinator;
import com.ruoyi.flowable.service.retention.WorkflowDataRetentionDomain;

/**
 * 使用隔离 MySQL schema 验证十一个工作流父领域的真实保留期、父子关系、批次和多节点领取语义。
 */
@SpringBootTest(
        classes = RuoYiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "flowable.it.expected-schema=${FLOWABLE_IT_EXPECTED_SCHEMA}",
            "token.secret=cmV0ZW50aW9uLWl0LXJlYWwtaXNvbGF0ZWQtbXlzcWwtc2NoZW1hLXJldGVudGlvbi1pdC1yZWFsLWlzb2xhdGVkLW15c3FsLXNjaGVtYQ==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false",
            "flowable.notification.worker-enabled=false",
            "flowable.collaboration.worker-initial-delay=PT6H",
            "flowable.collaboration.worker-fixed-delay=PT6H",
            "flowable.attachment.cleanup-initial-delay=PT6H",
            "flowable.attachment.cleanup-fixed-delay=PT6H",
            "flowable.data-retention.batch-size=2",
            "flowable.data-retention.notification-outbox-retention=P3650D",
            "flowable.data-retention.runtime-event-retention=P3650D",
            "flowable.data-retention.process-draft-retention=P3650D",
            "flowable.data-retention.collaboration-retention=P3650D",
            "flowable.data-retention.attachment-metadata-retention=P3650D",
            "flowable.data-retention.notification-inbox-retention=P3650D",
            "flowable.data-retention.bpmn-event-audit-retention=P3650D",
            "flowable.data-retention.task-sla-retention=P3650D",
            "flowable.data-retention.copy-retention=P3650D",
            "flowable.data-retention.controlled-loop-retention=P3650D",
            "flowable.data-retention.initial-delay=PT6H",
            "flowable.data-retention.fixed-delay=PT6H"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class WorkflowDataRetentionMySqlIT
{
    /** 所有测试业务字段使用的唯一前缀，清理时禁止扩大到其他数据。 */
    private static final String PREFIX = "workflow-retention-it-";
    /** 使用属性允许的最长保留期，避免共享 IT schema 的近期数据进入清理候选。 */
    private static final int RETENTION_DAYS = 3650;
    /** 终态测试数据固定早于最长保留边界一天。 */
    private static final int EXPIRED_DAYS = RETENTION_DAYS + 1;
    /** 边界内测试数据固定晚于最长保留边界一天。 */
    private static final int BOUNDARY_DAYS = RETENTION_DAYS - 1;

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final WorkflowDataRetentionCoordinator coordinator;
    /** Spring 注入的十一个真实父领域清理器，用于读取每个模拟节点的无序并发结果。 */
    private final List<WorkflowDataRetentionCleaner> cleaners;
    private final MeterRegistry meterRegistry;
    private final String expectedSchema;
    /** 当前测试方法独占的业务前缀后缀。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private Long actorUserId;
    private Long credentialId;
    private Long endpointId;
    private String channelId;

    /**
     * 创建真实 MySQL 数据保留集成测试。
     * @param jdbc JdbcTemplate，应用正式动态主数据源 JDBC 入口
     * @param dataSource DataSource，核对隔离 schema 的动态主数据源
     * @param coordinator WorkflowDataRetentionCoordinator，正式统一保留调度入口
     * @param cleaners List&lt;WorkflowDataRetentionCleaner&gt;，十一个真实事务清理器
     * @param meterRegistry MeterRegistry，读取真实低基数指标增量
     * @param expectedSchema String，测试命令显式传入的隔离 schema
     * @return 无返回值，构造后由 Spring 测试容器管理
     */
    @Autowired
    WorkflowDataRetentionMySqlIT(JdbcTemplate jdbc,
            @Qualifier("dynamicDataSource") DataSource dataSource,
            WorkflowDataRetentionCoordinator coordinator,
            List<WorkflowDataRetentionCleaner> cleaners,
            MeterRegistry meterRegistry,
            @Value("${flowable.it.expected-schema}") String expectedSchema)
    {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.coordinator = coordinator;
        this.cleaners = List.copyOf(cleaners);
        this.meterRegistry = meterRegistry;
        this.expectedSchema = expectedSchema;
    }

    /**
     * 在写入前核对隔离 schema、超长保留窗口内无外部候选并创建真实外键前置数据。
     * @return void，无返回值；连接或其他测试数据可能被清理时立即失败
     * @throws SQLException 获取当前 JDBC catalog 失败
     */
    @BeforeEach
    void setUp() throws SQLException
    {
        String normalizedSchema = expectedSchema.toLowerCase(Locale.ROOT);
        assertThat(normalizedSchema.startsWith("wf_arch_it_")
                || normalizedSchema.endsWith("_flowable_it")).isTrue();
        try (Connection connection = dataSource.getConnection())
        {
            assertThat(connection.getCatalog()).isEqualTo(expectedSchema);
        }
        assertThat(jdbc.queryForObject("select database()", String.class)).isEqualTo(expectedSchema);
        assertNoForeignRetentionCandidates();

        actorUserId = jdbc.queryForObject(
                "select min(user_id) from sys_user where status='0' and del_flag='0'",
                Long.class);
        assertThat(actorUserId).isPositive();
        insertCredential();
        insertConnectorEndpoint();
        insertCollaborationChannel();
    }

    /**
     * 按外键逆序只清理当前测试前缀，并核对十一个父领域及子审计无残留。
     * @return void，无返回值；清理范围扩大或测试数据残留时测试失败
     */
    @AfterEach
    void tearDown()
    {
        jdbc.update("delete from wf_task_sla_execution where task_definition_key like ?", runPrefix() + "%");
        jdbc.update("delete from wf_bpmn_event_audit where event_name like ?", runPrefix() + "%");
        jdbc.update("delete from wf_copy where copy_event_id like ?", runPrefix() + "%");
        jdbc.update("delete from wf_controlled_loop_execution where activity_id like ?", runPrefix() + "%");
        jdbc.update("delete from wf_notification_inbox where source_id like ?", runPrefix() + "%");
        jdbc.update("delete from wf_notification_outbox where source_id like ?", runPrefix() + "%");
        jdbc.update("delete from wf_attachment where storage_key like ?", runPrefix() + "%");
        jdbc.update("delete from wf_process_draft where process_definition_key like ?", runPrefix() + "%");
        if (channelId != null)
        {
            jdbc.update("delete from wf_collaboration_message_audit where actor_id like ?", auditActorPrefix() + "%");
            jdbc.update("delete from wf_collaboration_message where channel_id=?", channelId);
            jdbc.update("delete from wf_collaboration_outbox where channel_id=?", channelId);
            jdbc.update("delete from wf_collaboration_channel where channel_id=?", channelId);
        }
        if (credentialId != null)
        {
            jdbc.update("delete from wf_runtime_event_request where credential_id=?", credentialId);
            jdbc.update("delete from wf_integration_credential where credential_id=?", credentialId);
        }
        if (endpointId != null)
        {
            jdbc.update("delete from wf_connector_endpoint where endpoint_id=?", endpointId);
        }
        jdbc.update("delete from ACT_HI_PROCINST where BUSINESS_KEY_ like ?", runPrefix() + "%");
        assertThat(countRunRows()).isZero();
    }

    /**
     * 验证全部父领域只删除过期允许终态，首轮受批次二限制，第二轮删除余下一条。
     * @return void，边界数据、受保护状态、批次上限或指标任一不一致时测试失败
     */
    @Test
    void deletesOnlyExpiredTerminalRowsWithinBatchLimit()
    {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        insertAllDomainFixtures(now, 3, true);
        MetricSnapshot before = metricSnapshot();

        coordinator.runScheduledBatch();

        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            assertThat(countExpiredEligible(domain, now)).as(domain + " 首轮只剩一条过期终态").isEqualTo(1);
            assertThat(metricDelta(before, domain, "deleted")).as(domain + " 首轮删除指标").isEqualTo(2D);
        }
        assertBoundaryAndProtectedRowsRemain(now);

        coordinator.runScheduledBatch();

        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            assertThat(countExpiredEligible(domain, now)).as(domain + " 第二轮清空过期终态").isZero();
            assertThat(metricDelta(before, domain, "scanned")).isEqualTo(3D);
            assertThat(metricDelta(before, domain, "claimed")).isEqualTo(3D);
            assertThat(metricDelta(before, domain, "deleted")).isEqualTo(3D);
            assertThat(metricDelta(before, domain, "failed")).isZero();
        }
        assertThat(executionDelta(before, "completed")).isEqualTo(2D);
        assertThat(executionDelta(before, "failed")).isZero();
        assertBoundaryAndProtectedRowsRemain(now);
    }

    /**
     * 验证两个模拟节点并发调用真实清理器时，同一批记录只删除一次且允许按行分片领取。
     * @return void，并发重复删除、漏删或事务异常时测试失败
     * @throws Exception 线程同步、执行或结果等待失败
     */
    @Test
    void concurrentCoordinatorsDoNotDuplicateDeletionSideEffects() throws Exception
    {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        insertAllDomainFixtures(now, 2, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Map<WorkflowDataRetentionDomain, Integer> firstResult;
        Map<WorkflowDataRetentionDomain, Integer> secondResult;
        try
        {
            Future<Map<WorkflowDataRetentionDomain, Integer>> first = executor.submit(
                    () -> runCleanersAfterBarrier(ready, start, now));
            Future<Map<WorkflowDataRetentionDomain, Integer>> second = executor.submit(
                    () -> runCleanersAfterBarrier(ready, start, now));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResult = first.get(30, TimeUnit.SECONDS);
            secondResult = second.get(30, TimeUnit.SECONDS);
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            assertThat(countExpiredEligible(domain, now)).as(domain + " 并发后无漏删").isZero();
            int firstDeleted = firstResult.get(domain);
            int secondDeleted = secondResult.get(domain);
            assertThat(firstDeleted + secondDeleted).as(domain + " 两节点删除总数").isEqualTo(2);
            // MySQL SKIP LOCKED 允许两个节点分别领取一行，也允许单节点领取完整批次；总数正确即无重复副作用。
            assertThat(firstDeleted).as(domain + " 第一节点删除数").isBetween(0, 2);
            assertThat(secondDeleted).as(domain + " 第二节点删除数").isBetween(0, 2);
        }
    }

    /**
     * 等待两个模拟节点就绪后依次调用全部真实父领域清理器并收集删除数。
     * @param ready CountDownLatch，节点就绪门闩
     * @param start CountDownLatch，统一起跑门闩
     * @param executionTime LocalDateTime，两个节点共用的保留边界基准
     * @return Map&lt;WorkflowDataRetentionDomain, Integer&gt;，当前节点各域实际删除数
     */
    private Map<WorkflowDataRetentionDomain, Integer> runCleanersAfterBarrier(
            CountDownLatch ready, CountDownLatch start, LocalDateTime executionTime)
    {
        ready.countDown();
        try
        {
            if (!start.await(10, TimeUnit.SECONDS))
            {
                throw new IllegalStateException("数据保留并发起跑超时");
            }
            Map<WorkflowDataRetentionDomain, Integer> deletedByDomain =
                    new EnumMap<>(WorkflowDataRetentionDomain.class);
            for (WorkflowDataRetentionCleaner cleaner : cleaners)
            {
                deletedByDomain.put(cleaner.domain(), cleaner.cleanBatch(executionTime).deleted());
            }
            return Map.copyOf(deletedByDomain);
        }
        catch (InterruptedException failure)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("数据保留并发线程被中断", failure);
        }
    }

    /**
     * 为十一个父领域插入过期终态，并按需插入边界和受保护状态。
     * @param now LocalDateTime，本测试统一时间基准
     * @param expiredCount int，每域过期允许终态数量
     * @param includeBoundaryAndProtected boolean，是否插入未到期和受保护记录
     * @return void，无返回值；所有数据通过真实表约束写入
     */
    private void insertAllDomainFixtures(LocalDateTime now, int expiredCount,
            boolean includeBoundaryAndProtected)
    {
        LocalDateTime expiredTime = now.minusDays(EXPIRED_DAYS);
        LocalDateTime boundaryTime = now.minusDays(BOUNDARY_DAYS);
        for (int index = 0; index < expiredCount; index++)
        {
            String suffix = "expired-" + index;
            String processInstanceId = insertHistoricProcess(suffix, expiredTime, true);
            insertRuntimeEvent("expired-" + index, index % 2 == 0 ? "PROCESSED" : "FAILED", expiredTime);
            insertDraft("expired-" + index, index % 2 == 0 ? "SUBMITTED" : "DELETED", expiredTime);
            String inboundMessageId = insertCollaborationMessage(
                    "expired-" + index, "PROCESSED", expiredTime, index + 1L);
            insertCollaborationAudit(inboundMessageId, "INBOUND", "expired-in-" + index);
            String outboundMessageId = insertCollaborationOutbox(
                    "expired-" + index, index % 2 == 0 ? "PROCESSED" : "CANCELLED",
                    expiredTime, index + 1L);
            insertCollaborationAudit(outboundMessageId, "OUTBOUND", "expired-out-" + index);
            insertAttachment("expired-" + index, expiredTime, true);
            long outboxId = insertNotificationOutbox("expired-" + index,
                    index % 2 == 0 ? "PROCESSED" : "CANCELLED", expiredTime);
            insertNotificationInbox("expired-" + index, outboxId, "READ", expiredTime);
            insertBpmnEventAudit(suffix, processInstanceId, expiredTime);
            insertTaskSla(suffix, index % 2 == 0 ? "COMPLETED" : "ESCALATED", expiredTime);
            insertCopy(suffix, processInstanceId,
                    index % 2 == 0 ? "1" : "0", index % 2 == 0 ? "0" : "2", expiredTime);
            insertControlledLoop(suffix, processInstanceId, expiredTime);
        }
        if (!includeBoundaryAndProtected)
        {
            return;
        }

        String boundaryProcessId = insertHistoricProcess("boundary", boundaryTime, true);
        String activeProcessId = insertHistoricProcess("protected-active", expiredTime, false);
        String unreadProcessId = insertHistoricProcess("protected-unread", expiredTime, true);
        insertRuntimeEvent("boundary", "PROCESSED", boundaryTime);
        insertRuntimeEvent("protected", "RECEIVED", null);
        insertDraft("boundary", "SUBMITTED", boundaryTime);
        insertDraft("protected", "ACTIVE", null);
        String boundaryInbound = insertCollaborationMessage("boundary", "PROCESSED", boundaryTime, 100L);
        insertCollaborationAudit(boundaryInbound, "INBOUND", "boundary-in");
        String protectedInbound = insertCollaborationMessage("protected", "DEAD_LETTER", expiredTime, 101L);
        insertCollaborationAudit(protectedInbound, "INBOUND", "protected-in");
        String boundaryOutbound = insertCollaborationOutbox("boundary", "PROCESSED", boundaryTime, 100L);
        insertCollaborationAudit(boundaryOutbound, "OUTBOUND", "boundary-out");
        String protectedDeadOutbound = insertCollaborationOutbox(
                "protected-dead", "DEAD_LETTER", expiredTime, 101L);
        insertCollaborationAudit(protectedDeadOutbound, "OUTBOUND", "protected-dead-out");
        String protectedPendingOutbound = insertCollaborationOutbox(
                "protected-pending", "PENDING", null, 102L);
        insertCollaborationAudit(protectedPendingOutbound, "OUTBOUND", "protected-pending-out");
        insertAttachment("boundary", boundaryTime, true);
        insertAttachment("protected", expiredTime, false);
        long boundaryOutbox = insertNotificationOutbox("boundary", "PROCESSED", boundaryTime);
        insertNotificationOutbox("protected-dead", "DEAD_LETTER", expiredTime);
        long pendingOutbox = insertNotificationOutbox("protected-pending", "PENDING", null);
        insertNotificationInbox("boundary", boundaryOutbox, "READ", boundaryTime);
        insertNotificationInbox("protected", pendingOutbox, "UNREAD", null);
        insertBpmnEventAudit("boundary", boundaryProcessId, boundaryTime);
        insertBpmnEventAudit("protected-active", activeProcessId, expiredTime);
        insertTaskSla("boundary", "COMPLETED", boundaryTime);
        insertTaskSla("protected-active", "ACTIVE", expiredTime);
        insertCopy("boundary", boundaryProcessId, "1", "0", boundaryTime);
        insertCopy("protected-active", activeProcessId, "1", "0", expiredTime);
        insertCopy("protected-unread", unreadProcessId, "0", "0", expiredTime);
        insertControlledLoop("boundary", boundaryProcessId, boundaryTime);
        insertControlledLoop("protected-active", activeProcessId, expiredTime);
    }

    /**
     * 写入运行事件请求。
     * @param suffix String，测试业务后缀
     * @param status String，RECEIVED、PROCESSED 或 FAILED
     * @param completeTime LocalDateTime，终态完成时间；处理中为空
     * @return String，新增入站消息主键
     */
    private void insertRuntimeEvent(String suffix, String status, LocalDateTime completeTime)
    {
        boolean completed = completeTime != null;
        jdbc.update("insert into wf_runtime_event_request "
                + "(request_id,credential_id,event_type,event_name,correlation_type,correlation_value,"
                + "variables_sha256,status,result_code,result_summary,create_time,complete_time) "
                + "values (?,?, 'MESSAGE',?,'BUSINESS_KEY',?, ?,?,?,?, ?,?)",
                UUID.randomUUID().toString(), credentialId, runPrefix() + suffix,
                runPrefix() + suffix, sha256(runPrefix() + "runtime-" + suffix), status,
                completed ? "RETENTION_IT" : null, completed ? "retention fixture" : null,
                completed ? completeTime.minusMinutes(1) : LocalDateTime.now().minusMinutes(1),
                completeTime);
    }

    /**
     * 写入符合真实 JSON、时间和生命周期约束的流程草稿。
     * @param suffix String，测试业务后缀
     * @param status String，ACTIVE、SUBMITTED 或 DELETED
     * @param terminalTime LocalDateTime，终态时间；ACTIVE 时为空
     * @return void，无返回值
     */
    private void insertDraft(String suffix, String status, LocalDateTime terminalTime)
    {
        String draftId = UUID.randomUUID().toString();
        LocalDateTime createTime = terminalTime == null
                ? LocalDateTime.now().minusMinutes(2) : terminalTime.minusMinutes(2);
        jdbc.update("insert into wf_process_draft "
                + "(draft_id,owner_user_id,process_definition_id,process_definition_key,"
                + "process_definition_version,deployment_id,process_name,source_type,form_id,form_key,"
                + "start_node_key,form_name,node_name,snapshot_create_time,form_snapshot,"
                + "form_snapshot_sha256,start_multi_instance_assignments,form_values,"
                + "multi_instance_user_ids,draft_status,revision_no,submitted_process_instance_id,"
                + "submitted_time,deleted_time,create_time,update_time) "
                + "values (?,?,?,?,1,?,?,'EMBEDDED',null,'startForm','start','保留期表单','开始',"
                + "?,'{}',?,'[]','{}','{}',?,1,?,?,?,?,?)",
                draftId, actorUserId, technicalId("definition", ""), runPrefix() + suffix,
                technicalId("deployment", ""), "保留期流程", createTime.minusMinutes(1),
                sha256("{}"), status,
                "SUBMITTED".equals(status) ? technicalId("draft-instance", suffix) : null,
                "SUBMITTED".equals(status) ? terminalTime : null,
                "DELETED".equals(status) ? terminalTime : null,
                createTime, terminalTime == null ? createTime : terminalTime);
    }

    /**
     * 写入入站协作消息。
     * @param suffix String，测试业务后缀
     * @param status String，PROCESSED 或受保护状态
     * @param completeTime LocalDateTime，终态时间；处理中为空
     * @param sequence long，当前通道唯一序号
     * @return void，无返回值
     */
    private String insertCollaborationMessage(String suffix, String status,
            LocalDateTime completeTime, long sequence)
    {
        String messageId = UUID.randomUUID().toString();
        jdbc.update("insert into wf_collaboration_message "
                + "(message_id,credential_id,actor_user_id,channel_id,sequence_no,message_name,"
                + "source_process_definition_key,target_process_definition_key,correlation_key,"
                + "variables_json,payload_sha256,status,attempt_count,max_attempts,create_time,complete_time) "
                + "values (?,?,?,?,?,?,?, ?,?,'{}',?,?,0,5,?,?)",
                messageId, credentialId, String.valueOf(actorUserId), channelId,
                sequence, runPrefix() + suffix, runPrefix() + "source", runPrefix() + "target",
                runPrefix() + "correlation", sha256(runPrefix() + "message-" + suffix), status,
                completeTime == null ? LocalDateTime.now().minusMinutes(1) : completeTime.minusMinutes(1),
                completeTime);
        return messageId;
    }

    /**
     * 写入出站协作 outbox。
     * @param suffix String，测试业务后缀
     * @param status String，允许终态或受保护状态
     * @param completeTime LocalDateTime，终态时间；处理中为空
     * @param sequence long，当前通道唯一序号
     * @return String，新增出站消息主键
     */
    private String insertCollaborationOutbox(String suffix, String status,
            LocalDateTime completeTime, long sequence)
    {
        String messageId = UUID.randomUUID().toString();
        jdbc.update("insert into wf_collaboration_outbox "
                + "(message_id,channel_id,sequence_no,source_process_definition_key,"
                + "source_process_instance_id,source_execution_id,source_element_id,message_name,"
                + "target_process_definition_key,correlation_key,endpoint_id,endpoint_revision,"
                + "request_path,delivery_config_json,variables_json,payload_sha256,status,"
                + "attempt_count,max_attempts,next_attempt_time,create_time,complete_time) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,1,'/retention','{}','{}',?,?,0,5,?,?,?)",
                messageId, channelId, sequence, runPrefix() + "source",
                technicalId("collab-instance", suffix), technicalId("collab-execution", suffix),
                runPrefix() + "element-" + suffix, runPrefix() + suffix, runPrefix() + "target",
                runPrefix() + "correlation", endpointId, sha256(runPrefix() + "outbox-" + suffix),
                status, LocalDateTime.now().plusHours(1),
                completeTime == null ? LocalDateTime.now().minusMinutes(1) : completeTime.minusMinutes(1),
                completeTime);
        return messageId;
    }

    /**
     * 写入协作父记录对应的方向审计，用于验证父子同事务清理。
     * @param messageId String，入站或出站父记录主键
     * @param direction String，INBOUND 或 OUTBOUND
     * @param suffix String，当前测试唯一审计后缀
     * @return void，无返回值
     */
    private void insertCollaborationAudit(String messageId, String direction, String suffix)
    {
        jdbc.update("insert into wf_collaboration_message_audit "
                + "(message_id,direction,action,actor_type,actor_id,to_status,attempt_no,summary,create_time) "
                + "values (?,?,?,'SYSTEM',?,'PROCESSED',0,'保留期父子一致性审计',current_timestamp(3))",
                messageId, direction, "INBOUND".equals(direction) ? "RECEIVE" : "DELIVER",
                auditActorPrefix() + suffix);
    }

    /**
     * 写入 Flowable 历史流程实例终态或活动态事实。
     * @param suffix String，当前测试流程后缀
     * @param referenceTime LocalDateTime，流程结束或活动状态参考时间
     * @param ended boolean，是否写入 END_TIME_ 终态事实
     * @return String，稳定流程实例主键
     */
    private String insertHistoricProcess(String suffix, LocalDateTime referenceTime, boolean ended)
    {
        String processInstanceId = technicalId("history-process", suffix);
        jdbc.update("insert into ACT_HI_PROCINST "
                + "(ID_,PROC_INST_ID_,BUSINESS_KEY_,PROC_DEF_ID_,START_TIME_,END_TIME_,DURATION_,TENANT_ID_,STATE_) "
                + "values (?,?,?,?,?,?,?,?,?)",
                processInstanceId, processInstanceId, runPrefix() + suffix,
                technicalId("history-definition", suffix), referenceTime.minusHours(1),
                ended ? referenceTime : null, ended ? 3600000L : null, "",
                ended ? "COMPLETED" : "ACTIVE");
        return processInstanceId;
    }

    /**
     * 写入 BPMN 事件运行审计。
     * @param suffix String，当前测试业务后缀
     * @param processInstanceId String，对应 Flowable 历史流程实例主键
     * @param createTime LocalDateTime，事件触发时间
     * @return void，无返回值
     */
    private void insertBpmnEventAudit(String suffix, String processInstanceId,
            LocalDateTime createTime)
    {
        jdbc.update("insert into wf_bpmn_event_audit "
                + "(idempotency_key,deployment_id,process_instance_id,process_definition_id,execution_id,"
                + "source_element_id,source_type,event_type,event_code,event_name,match_status,"
                + "initiator_user_id,create_time) values (?,?,?,?,?,?,'MANUAL','ERROR',?,?,'UNMATCHED',?,?)",
                sha256(runPrefix() + "bpmn-event-" + suffix), technicalId("bpmn-deployment", suffix),
                processInstanceId, technicalId("bpmn-definition", suffix),
                technicalId("bpmn-execution", suffix), runPrefix() + "event-element-" + suffix,
                "RETENTION_" + suffix.toUpperCase(Locale.ROOT).replace('-', '_'),
                runPrefix() + suffix, String.valueOf(actorUserId), createTime);
    }

    /**
     * 写入 SLA 执行及其一条真实子审计。
     * @param suffix String，当前测试业务后缀
     * @param status String，ACTIVE、COMPLETED 或 ESCALATED
     * @param updateTime LocalDateTime，最后状态时间
     * @return void，无返回值
     */
    private void insertTaskSla(String suffix, String status, LocalDateTime updateTime)
    {
        jdbc.update("insert into wf_task_sla_execution "
                + "(deployment_id,process_instance_id,process_definition_id,task_id,task_definition_key,"
                + "assignee_user_id,status,started_at,reminder_due_at,escalation_due_at,reminders_sent,"
                + "paused_millis,revision,update_time) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                technicalId("sla-deployment", suffix), technicalId("sla-instance", suffix),
                technicalId("sla-definition", suffix), technicalId("sla-task", suffix),
                runPrefix() + suffix, String.valueOf(actorUserId), status, updateTime.minusHours(3),
                updateTime.minusHours(2), updateTime.minusHours(1), 0, 0L, 0, updateTime);
        Long executionId = jdbc.queryForObject(
                "select sla_execution_id from wf_task_sla_execution where task_id=?",
                Long.class, technicalId("sla-task", suffix));
        jdbc.update("insert into wf_task_sla_audit "
                + "(sla_execution_id,action_type,action_ordinal,actor_user_id,detail,create_time) "
                + "values (?,'CREATE',0,?,'保留期级联审计',?)",
                executionId, String.valueOf(actorUserId), updateTime.minusHours(3));
    }

    /**
     * 写入流程抄送记录。
     * @param suffix String，当前测试业务后缀
     * @param processInstanceId String，对应 Flowable 历史流程实例主键
     * @param readStatus String，0 未读或 1 已读
     * @param delFlag String，0 有效或 2 逻辑删除
     * @param createTime LocalDateTime，抄送创建时间
     * @return void，无返回值
     */
    private void insertCopy(String suffix, String processInstanceId, String readStatus,
            String delFlag, LocalDateTime createTime)
    {
        jdbc.update("insert into wf_copy "
                + "(copy_event_id,title,process_id,process_name,category_id,deployment_id,instance_id,"
                + "user_id,originator_id,originator_name,source_type,trigger_type,read_status,read_time,"
                + "create_by,create_time,del_flag) values (?,?,?,?,?,?,?,?,?,?,'AUTO','PROCESS_COMPLETED',?,?,?,?,?)",
                runPrefix() + suffix, "保留期抄送", technicalId("copy-definition", suffix),
                "保留期流程", "retention", technicalId("copy-deployment", suffix), processInstanceId,
                actorUserId, actorUserId, "保留期用户", readStatus,
                "1".equals(readStatus) ? createTime.plusMinutes(1) : null,
                String.valueOf(actorUserId), createTime, delFlag);
    }

    /**
     * 写入受控循环执行记录。
     * @param suffix String，当前测试业务后缀
     * @param processInstanceId String，对应 Flowable 历史流程实例主键
     * @param createTime LocalDateTime，本轮完成时间
     * @return void，无返回值
     */
    private void insertControlledLoop(String suffix, String processInstanceId,
            LocalDateTime createTime)
    {
        jdbc.update("insert into wf_controlled_loop_execution "
                + "(deploy_id,process_definition_id,process_instance_id,activity_id,task_id,iteration_no,"
                + "actor_user_id,decision_value,outcome,create_time) values (?,?,?,?,?,1,?,'EXIT','EXIT',?)",
                technicalId("loop-deployment", suffix), technicalId("loop-definition", suffix),
                processInstanceId, runPrefix() + suffix, technicalId("loop-task", suffix),
                String.valueOf(actorUserId), createTime);
    }

    /**
     * 写入附件元数据，是否完成物理删除决定其能否进入保留清理。
     * @param suffix String，测试业务后缀
     * @param storageDeletedTime LocalDateTime，测试物理删除时间
     * @param storageDeleted boolean，是否记录物理删除完成事实
     * @return void，无返回值
     */
    private void insertAttachment(String suffix, LocalDateTime storageDeletedTime,
            boolean storageDeleted)
    {
        jdbc.update("insert into wf_attachment "
                + "(attachment_id,owner_user_id,field_name,original_name,storage_key,content_type,"
                + "file_size,sha256,attachment_status,expire_time,storage_deleted_time,create_time,update_time) "
                + "values (?,?,?,?,?,'text/plain',1,?,'DELETED',?,?,?,?)",
                UUID.randomUUID().toString(), actorUserId, "retentionFile", suffix + ".txt",
                runPrefix() + "attachment-" + suffix, sha256(suffix),
                storageDeletedTime.minusMinutes(1), storageDeleted ? storageDeletedTime : null,
                storageDeletedTime.minusMinutes(2), storageDeletedTime);
    }

    /**
     * 写入通知 outbox。
     * @param suffix String，测试业务后缀
     * @param status String，允许终态或受保护状态
     * @param processedTime LocalDateTime，终态处理时间；待处理为空
     * @return long，新增 outbox 主键
     */
    private long insertNotificationOutbox(String suffix, String status,
            LocalDateTime processedTime)
    {
        String idempotencyKey = sha256(runPrefix() + "notification-outbox-" + suffix);
        jdbc.update("insert into wf_notification_outbox "
                + "(idempotency_key,source_type,source_id,event_type,channel,recipient_user_id,"
                + "process_definition_key,process_instance_id,title,content,route_path,status,"
                + "attempt_count,total_attempt_count,max_attempts,next_attempt_at,revision,"
                + "create_time,processed_time) "
                + "values (?,'APPROVAL',?,'TASK_COMPLETED','INBOX',?,?,?,'保留期通知','测试正文',"
                + "'/workflow/retention',?,0,0,5,?,0,?,?)",
                idempotencyKey, runPrefix() + suffix, actorUserId, runPrefix() + "definition",
                technicalId("notification-instance", suffix), status, LocalDateTime.now().plusHours(1),
                processedTime == null ? LocalDateTime.now().minusMinutes(1) : processedTime.minusMinutes(1),
                processedTime);
        return jdbc.queryForObject(
                "select outbox_id from wf_notification_outbox where idempotency_key=?",
                Long.class, idempotencyKey);
    }

    /**
     * 写入通知 inbox。
     * @param suffix String，测试业务后缀
     * @param outboxId long，软关联的真实 outbox 主键
     * @param readStatus String，READ 或 UNREAD
     * @param readTime LocalDateTime，已读时间；未读为空
     * @return void，无返回值
     */
    private void insertNotificationInbox(String suffix, long outboxId,
            String readStatus, LocalDateTime readTime)
    {
        jdbc.update("insert into wf_notification_inbox "
                + "(outbox_id,notification_key,source_type,source_id,recipient_user_id,event_type,"
                + "title,content,process_instance_id,route_path,read_status,create_time,read_time) "
                + "values (?,?,'APPROVAL',?,?,'TASK_COMPLETED','保留期站内信','测试正文',?,"
                + "'/workflow/retention',?,?,?)",
                outboxId, sha256(runPrefix() + "notification-inbox-" + suffix),
                runPrefix() + suffix, actorUserId, technicalId("notification-instance", suffix),
                readStatus, readTime == null ? LocalDateTime.now().minusMinutes(1) : readTime.minusMinutes(1),
                readTime);
    }

    /**
     * 插入运行事件和协作消息共用的真实集成凭据。
     * @return void，无返回值；主键保存到 credentialId
     */
    private void insertCredential()
    {
        String tokenPrefix = runId.substring(0, 12);
        jdbc.update("insert into wf_integration_credential "
                + "(credential_name,token_prefix,token_hash,scopes,allowed_variables,"
                + "rate_limit_per_minute,revision_no,create_by,create_time) "
                + "values (?,?,?,'MESSAGE,RECEIVE,SIGNAL','',100,1,?,current_timestamp(3))",
                runPrefix() + "credential", tokenPrefix, sha256(runPrefix() + "token"),
                String.valueOf(actorUserId));
        credentialId = jdbc.queryForObject(
                "select credential_id from wf_integration_credential where token_prefix=?",
                Long.class, tokenPrefix);
    }

    /**
     * 插入协作 outbox 外键所需的真实 HTTP connector endpoint。
     * @return void，无返回值；主键保存到 endpointId
     */
    private void insertConnectorEndpoint()
    {
        String endpointKey = "retention-" + runId;
        jdbc.update("insert into wf_connector_endpoint "
                + "(endpoint_key,endpoint_name,base_url,allowed_methods,path_prefix,auth_type,"
                + "connect_timeout_ms,request_timeout_ms,network_scope,revision_no,status,checksum,"
                + "create_by,create_time) values (?,?,'https://example.invalid','POST','/retention',"
                + "'NONE',1000,5000,'PUBLIC',1,'ENABLED',?,?,current_timestamp(3))",
                endpointKey, "保留期测试端点", sha256(endpointKey), String.valueOf(actorUserId));
        endpointId = jdbc.queryForObject(
                "select endpoint_id from wf_connector_endpoint where endpoint_key=?",
                Long.class, endpointKey);
    }

    /**
     * 插入入站和出站协作消息共用的严格顺序通道。
     * @return void，无返回值；通道主键保存到 channelId
     */
    private void insertCollaborationChannel()
    {
        channelId = sha256(runPrefix() + "channel");
        jdbc.update("insert into wf_collaboration_channel "
                + "(channel_id,target_process_definition_key,correlation_type,correlation_value,"
                + "outbound_sequence,inbound_sequence,revision_no,create_time) "
                + "values (?,?,'BUSINESS_KEY',?,0,0,0,current_timestamp(3))",
                channelId, runPrefix() + "target", runPrefix() + "correlation");
    }

    /**
     * 核对首轮和第二轮后边界终态及受保护状态仍存在。
     * @param now LocalDateTime，本测试统一时间基准
     * @return void，无返回值；任一保护条件失效时测试失败
     */
    private void assertBoundaryAndProtectedRowsRemain(LocalDateTime now)
    {
        LocalDateTime cutoff = now.minusDays(RETENTION_DAYS);
        assertThat(jdbc.queryForObject("select count(*) from wf_runtime_event_request "
                + "where credential_id=? and status='RECEIVED'", Integer.class, credentialId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_process_draft where process_definition_key like ? "
                + "and draft_status='ACTIVE'", Integer.class, runPrefix() + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message where channel_id=? "
                + "and status='DEAD_LETTER'", Integer.class, channelId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_outbox where channel_id=? "
                + "and status in ('PENDING','DEAD_LETTER')", Integer.class, channelId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from wf_attachment where storage_key like ? "
                + "and storage_deleted_time is null", Integer.class, runPrefix() + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_outbox where source_id like ? "
                + "and status in ('PENDING','DEAD_LETTER')", Integer.class, runPrefix() + "%")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_inbox where source_id like ? "
                + "and read_status='UNREAD'", Integer.class, runPrefix() + "%")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_bpmn_event_audit where event_name=?",
                Integer.class, runPrefix() + "protected-active")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_task_sla_execution "
                + "where task_definition_key=? and status='ACTIVE'",
                Integer.class, runPrefix() + "protected-active")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_copy where copy_event_id in (?,?)",
                Integer.class, runPrefix() + "protected-active",
                runPrefix() + "protected-unread")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from wf_controlled_loop_execution where activity_id=?",
                Integer.class, runPrefix() + "protected-active")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message_audit "
                + "where actor_id like ? and actor_id not like ?",
                Integer.class, auditActorPrefix() + "%", auditActorPrefix() + "expired-%")).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from wf_task_sla_audit audit "
                + "join wf_task_sla_execution execution on execution.sla_execution_id=audit.sla_execution_id "
                + "where execution.task_definition_key in (?,?)", Integer.class,
                runPrefix() + "boundary", runPrefix() + "protected-active")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from wf_collaboration_message_audit audit where "
                + "audit.actor_id like ? and ((audit.direction='INBOUND' and not exists "
                + "(select 1 from wf_collaboration_message parent where parent.message_id=audit.message_id)) "
                + "or (audit.direction='OUTBOUND' and not exists "
                + "(select 1 from wf_collaboration_outbox parent where parent.message_id=audit.message_id)))",
                Integer.class, auditActorPrefix() + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from wf_task_sla_audit audit left join "
                + "wf_task_sla_execution execution on execution.sla_execution_id=audit.sla_execution_id "
                + "where execution.sla_execution_id is null", Integer.class)).isZero();
        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            assertThat(countBoundaryEligible(domain, cutoff)).as(domain + " 边界内终态保留").isEqualTo(1);
        }
    }

    /**
     * 统计当前测试域内仍超过截止时间的允许终态。
     * @param domain WorkflowDataRetentionDomain，固定数据域
     * @param now LocalDateTime，本测试统一时间基准
     * @return int，当前仍应清理的过期记录数
     */
    private int countExpiredEligible(WorkflowDataRetentionDomain domain, LocalDateTime now)
    {
        LocalDateTime cutoff = now.minusDays(RETENTION_DAYS);
        return countEligible(domain, "<=", cutoff);
    }

    /**
     * 统计当前测试域内晚于截止时间的允许终态。
     * @param domain WorkflowDataRetentionDomain，固定数据域
     * @param cutoff LocalDateTime，保留截止时间
     * @return int，边界内终态记录数
     */
    private int countBoundaryEligible(WorkflowDataRetentionDomain domain, LocalDateTime cutoff)
    {
        return countEligible(domain, ">", cutoff);
    }

    /**
     * 按固定运算符统计七域允许终态，运算符只由本类常量调用方提供。
     * @param domain WorkflowDataRetentionDomain，固定数据域
     * @param operator String，仅允许 &lt;= 或 &gt;
     * @param cutoff LocalDateTime，统计时间边界
     * @return int，符合领域白名单和测试前缀的记录数
     */
    private int countEligible(WorkflowDataRetentionDomain domain, String operator,
            LocalDateTime cutoff)
    {
        assertThat(operator).isIn("<=", ">");
        return switch (domain)
        {
            case NOTIFICATION_OUTBOX -> jdbc.queryForObject(
                    "select count(*) from wf_notification_outbox where source_id like ? "
                            + "and status in ('PROCESSED','CANCELLED') and processed_time "
                            + operator + " ?", Integer.class, runPrefix() + "%", cutoff);
            case RUNTIME_EVENT -> jdbc.queryForObject(
                    "select count(*) from wf_runtime_event_request where credential_id=? "
                            + "and status in ('PROCESSED','FAILED') and complete_time "
                            + operator + " ?", Integer.class, credentialId, cutoff);
            case PROCESS_DRAFT -> jdbc.queryForObject(
                    "select count(*) from wf_process_draft where process_definition_key like ? and "
                            + "((draft_status='SUBMITTED' and submitted_time " + operator + " ?) or "
                            + "(draft_status='DELETED' and deleted_time " + operator + " ?))",
                    Integer.class, runPrefix() + "%", cutoff, cutoff);
            case COLLABORATION_MESSAGE -> jdbc.queryForObject(
                    "select count(*) from wf_collaboration_message where channel_id=? "
                            + "and status='PROCESSED' and complete_time " + operator + " ?",
                    Integer.class, channelId, cutoff);
            case COLLABORATION_OUTBOX -> jdbc.queryForObject(
                    "select count(*) from wf_collaboration_outbox where channel_id=? "
                            + "and status in ('PROCESSED','CANCELLED') and complete_time "
                            + operator + " ?", Integer.class, channelId, cutoff);
            case ATTACHMENT_METADATA -> jdbc.queryForObject(
                    "select count(*) from wf_attachment where storage_key like ? "
                            + "and storage_deleted_time is not null and storage_deleted_time "
                            + operator + " ?", Integer.class, runPrefix() + "%", cutoff);
            case NOTIFICATION_INBOX -> jdbc.queryForObject(
                    "select count(*) from wf_notification_inbox where source_id like ? "
                            + "and read_status='READ' and read_time " + operator + " ?",
                    Integer.class, runPrefix() + "%", cutoff);
            case BPMN_EVENT_AUDIT -> jdbc.queryForObject(
                    "select count(*) from wf_bpmn_event_audit audit join ACT_HI_PROCINST history "
                            + "on history.PROC_INST_ID_=audit.process_instance_id and history.END_TIME_ is not null "
                            + "where audit.event_name like ? and audit.create_time " + operator + " ?",
                    Integer.class, runPrefix() + "%", cutoff);
            case TASK_SLA_EXECUTION -> jdbc.queryForObject(
                    "select count(*) from wf_task_sla_execution where task_definition_key like ? "
                            + "and status in ('COMPLETED','ESCALATED') and update_time " + operator + " ?",
                    Integer.class, runPrefix() + "%", cutoff);
            case COPY -> jdbc.queryForObject(
                    "select count(*) from wf_copy copy_row join ACT_HI_PROCINST history "
                            + "on history.PROC_INST_ID_=copy_row.instance_id and history.END_TIME_ is not null "
                            + "where copy_row.copy_event_id like ? and (copy_row.read_status='1' "
                            + "or copy_row.del_flag='2') and copy_row.create_time " + operator + " ?",
                    Integer.class, runPrefix() + "%", cutoff);
            case CONTROLLED_LOOP_EXECUTION -> jdbc.queryForObject(
                    "select count(*) from wf_controlled_loop_execution loop_row join ACT_HI_PROCINST history "
                            + "on history.PROC_INST_ID_=loop_row.process_instance_id and history.END_TIME_ is not null "
                            + "where loop_row.activity_id like ? and loop_row.create_time " + operator + " ?",
                    Integer.class, runPrefix() + "%", cutoff);
        };
    }

    /**
     * 捕获统一保留指标的当前累计值，后续断言只比较本测试增量。
     * @return MetricSnapshot，执行轮次和七域四类 item Counter 快照
     */
    private MetricSnapshot metricSnapshot()
    {
        java.util.Map<String, Double> values = new java.util.HashMap<>();
        for (WorkflowDataRetentionDomain domain : WorkflowDataRetentionDomain.values())
        {
            for (String result : java.util.List.of("scanned", "claimed", "deleted", "failed"))
            {
                values.put(domain.metricTag() + ":" + result, itemMetric(domain, result));
            }
        }
        values.put("execution:completed", executionMetric("completed"));
        values.put("execution:failed", executionMetric("failed"));
        return new MetricSnapshot(java.util.Map.copyOf(values));
    }

    /**
     * 计算单域指标相对快照的增量。
     * @param before MetricSnapshot，执行前快照
     * @param domain WorkflowDataRetentionDomain，固定数据域
     * @param result String，scanned、claimed、deleted 或 failed
     * @return double，本测试产生的 Counter 增量
     */
    private double metricDelta(MetricSnapshot before, WorkflowDataRetentionDomain domain,
            String result)
    {
        String key = domain.metricTag() + ":" + result;
        return itemMetric(domain, result) - before.values().get(key);
    }

    /**
     * 计算整轮执行指标相对快照的增量。
     * @param before MetricSnapshot，执行前快照
     * @param result String，completed 或 failed
     * @return double，本测试产生的 Counter 增量
     */
    private double executionDelta(MetricSnapshot before, String result)
    {
        return executionMetric(result) - before.values().get("execution:" + result);
    }

    /** 查询单域 item Counter。 @param domain WorkflowDataRetentionDomain，固定域 @param result String，固定结果 @return double，累计值 */
    private double itemMetric(WorkflowDataRetentionDomain domain, String result)
    {
        return meterRegistry.get("workflow.data.retention.items")
                .tags("domain", domain.metricTag(), "result", result).counter().count();
    }

    /** 查询整轮执行 Counter。 @param result String，completed 或 failed @return double，累计值 */
    private double executionMetric(String result)
    {
        return meterRegistry.get("workflow.data.retention.executions")
                .tag("result", result).counter().count();
    }

    /**
     * 确认共享隔离 schema 中不存在超过十年窗口的外部可清理终态。
     *
     * 目标表允许保留其他测试的近期正式数据；只有可能被本轮协调器命中的超期终态才阻止执行，
     * 从而保证全局协调器实际删除范围仍只包含本次 runId 构造的数据。
     * @return void，无返回值；发现任一外部清理候选时测试失败
     */
    private void assertNoForeignRetentionCandidates()
    {
        // 预留本测试装配和两轮执行时间，避免外部记录在 setUp 后刚好跨过保留边界。
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS).plusMinutes(5);
        int candidates = jdbc.queryForObject(
                "select count(*) from wf_notification_outbox "
                        + "where status in ('PROCESSED','CANCELLED') and processed_time<=?",
                Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_runtime_event_request "
                        + "where status in ('PROCESSED','FAILED') and complete_time<=?",
                Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_process_draft where "
                        + "(draft_status='SUBMITTED' and submitted_time<=?) or "
                        + "(draft_status='DELETED' and deleted_time<=?)",
                Integer.class, cutoff, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_collaboration_message "
                        + "where status='PROCESSED' and complete_time<=?",
                Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_collaboration_outbox "
                        + "where status in ('PROCESSED','CANCELLED') and complete_time<=?",
                Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_attachment "
                        + "where storage_deleted_time is not null and storage_deleted_time<=?",
                Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_notification_inbox "
                        + "where read_status='READ' and read_time<=?",
                Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_bpmn_event_audit audit where audit.create_time<=? and exists "
                        + "(select 1 from ACT_HI_PROCINST history where history.PROC_INST_ID_=audit.process_instance_id "
                        + "and history.END_TIME_ is not null)", Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_task_sla_execution where status in ('COMPLETED','ESCALATED') "
                        + "and update_time<=?", Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_copy copy_row where (copy_row.read_status='1' or copy_row.del_flag='2') "
                        + "and copy_row.create_time<=? and exists (select 1 from ACT_HI_PROCINST history "
                        + "where history.PROC_INST_ID_=copy_row.instance_id and history.END_TIME_ is not null)",
                Integer.class, cutoff);
        candidates += jdbc.queryForObject(
                "select count(*) from wf_controlled_loop_execution loop_row where loop_row.create_time<=? "
                        + "and exists (select 1 from ACT_HI_PROCINST history "
                        + "where history.PROC_INST_ID_=loop_row.process_instance_id and history.END_TIME_ is not null)",
                Integer.class, cutoff);
        assertThat(candidates).as("共享 IT schema 不得存在本测试窗口外的清理候选").isZero();
    }

    /**
     * 统计当前测试前缀在七个目标域及前置表中的残留记录。
     * @return int，当前测试仍残留的记录总数
     */
    private int countRunRows()
    {
        int count = jdbc.queryForObject("select count(*) from wf_notification_inbox where source_id like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_bpmn_event_audit where event_name like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_task_sla_execution where task_definition_key like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_copy where copy_event_id like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_controlled_loop_execution where activity_id like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_collaboration_message_audit where actor_id like ?",
                Integer.class, auditActorPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from ACT_HI_PROCINST where BUSINESS_KEY_ like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_notification_outbox where source_id like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_attachment where storage_key like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_process_draft where process_definition_key like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_integration_credential where credential_name like ?",
                Integer.class, runPrefix() + "%");
        count += jdbc.queryForObject("select count(*) from wf_connector_endpoint where endpoint_key=?",
                Integer.class, "retention-" + runId);
        return count;
    }

    /** 生成当前方法唯一业务前缀。 @return String，固定前缀与随机 runId */
    private String runPrefix()
    {
        return PREFIX + runId + "-";
    }

    /**
     * 生成符合协作审计 actor_id 长度约束的当前测试隔离前缀。
     * @return String，32 位随机运行标识和分隔符组成的审计前缀
     */
    private String auditActorPrefix()
    {
        return runId + "-";
    }

    /**
     * 为长度上限为 64 的 Flowable 技术主键生成稳定隔离值。
     * @param purpose String，技术主键用途
     * @param suffix String，同用途下的记录后缀
     * @return String，固定 64 位小写 SHA-256
     */
    private String technicalId(String purpose, String suffix)
    {
        return sha256(runId + ":" + purpose + ":" + suffix);
    }

    /**
     * 计算稳定小写 SHA-256。
     * @param value String，待摘要文本
     * @return String，64 位小写十六进制摘要
     */
    private String sha256(String value)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception failure)
        {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }

    /**
     * 保存一次指标累计值快照。
     * @param values java.util.Map&lt;String, Double&gt;，固定指标键到累计值
     */
    private record MetricSnapshot(java.util.Map<String, Double> values)
    {
    }
}
