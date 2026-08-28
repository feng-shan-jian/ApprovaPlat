package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.spec.SecretKeySpec;
import jakarta.mail.internet.MimeMessage;
import com.mysql.cj.jdbc.MysqlDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.domain.dto.WorkflowMailConfigRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMailConfigView;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.runtime.WorkflowNotificationMetrics;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceRoundLifecycleService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;
import com.ruoyi.flowable.service.task.WorkflowUserTaskAuditService;
import com.ruoyi.flowable.testsupport.LocalSmtpTestServer;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.system.service.integration.SysSmsService;

/**
 * 在显式隔离的 approvaplat_it MySQL 上验证审批通知从规划、写入、领取到真实 SMTP 的完整生产管道。
 *
 * 测试不创建或修改表结构；每条业务夹具均使用本次随机身份，SMTP 单例修改前完整备份并在结束时原样恢复。
 */
class WorkflowNotificationPipelineMySqlIT
{
    private static final String REQUIRED_DATABASE = "approvaplat_it";
    private static final String LOCK_NAME = "approvaplat_workflow_notification_pipeline_it";
    private static final String EVENT_TYPE = "TASK_ARRIVED";
    private static final String SMTP_HOST = "127.0.0.1";

    private static MysqlDataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;
    private Connection isolationLockConnection;
    private WorkflowNotificationPlanner planner;
    private WorkflowNotificationWriter writer;
    private WorkflowNotificationOutboxService outboxService;
    private WorkflowNotificationWorker worker;
    private WorkflowMailConfigService mailConfigService;
    private SimpleMeterRegistry meterRegistry;
    /** 规划与 SMTP 配置共用的正式身份边界；测试只固定当前独占用户。 */
    private WorkflowIdentityResolver identityResolver;
    /** 当前真实 Flowable 事件用例独占的引擎，teardown 必须关闭。 */
    private ProcessEngine flowableProcessEngine;
    /** 当前真实 Flowable 事件用例部署主键，teardown 按主键级联清理。 */
    private String flowableDeploymentId;
    /** 当前真实 Flowable 事件用例实例主键，用于精确回读通知事实。 */
    private String flowableProcessInstanceId;

    /** 当前用例唯一自然键片段，所有清理条件均由此派生。 */
    private String fixtureToken;
    /** 当前用例独占的正式用户主键。 */
    private long testUserId;
    private String testUserName;
    private String recipientEmail;
    private String smtpUsername;
    private String smtpCredential;
    private String smtpSenderName;
    private String processDefinitionKey;
    private String taskDefinitionKey;
    private String processInstanceId;
    private String taskId;
    private String deliveredSourceId;
    private String deadLetterSourceId;
    /** 当前用例插入的策略主键；尚未插入时为空。 */
    private Long policyId;
    /** 用例开始前 SMTP 单例的完整数据库快照；未配置时为空。 */
    private MailConfigBackup originalMailConfig;
    /** 标记生产保存入口是否已被调用，清理时只按本用例公开字段识别测试配置。 */
    private boolean configMutationAttempted;

    /**
     * 使用显式环境变量连接 MySQL，并只读核验目标 schema 精确为 approvaplat_it 且通知基线完整。
     *
     * @return void，核验成功后保存共享数据源
     * @throws SQLException JDBC 连接或元数据核验失败时报告
     */
    @BeforeAll
    static void setUpDataSource() throws SQLException
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource,
                "通知完整链路 IT", REQUIRED_DATABASE,
                List.of("sys_user", "sys_mail_config", "wf_notification_policy",
                        "wf_notification_preference", "wf_notification_inbox",
                        "wf_notification_outbox"));
    }

    /**
     * 为当前用例生成唯一夹具身份、取得串行测试锁、备份 SMTP 单例并装配生产通知对象。
     *
     * @return void，生产对象与事务代理装配完成后供测试使用
     * @throws Exception 取得 MySQL 锁或读取 SMTP 快照失败时报告
     */
    @BeforeEach
    void setUpPipeline() throws Exception
    {
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        initializeFixtureIdentity();
        acquireIsolationLock();
        assertNoForeignClaimableOutbox();
        originalMailConfig = loadMailConfig();
        assembleProductionPipeline();
    }

    /**
     * 删除当前用例的精确业务行、恢复原 SMTP 单例并回读零残留，最后释放 MySQL 串行锁。
     *
     * @return void，任何清理或恢复不完整均使测试失败
     * @throws Exception SQL 清理或锁释放失败时报告
     */
    @AfterEach
    void tearDownPipeline() throws Exception
    {
        try
        {
            cleanupFlowableFixture();
            cleanupBusinessFixtures();
            restoreMailConfig();
            assertFixtureRowsRemoved();
            assertMailConfigRestored();
        }
        finally
        {
            releaseIsolationLock();
            if (meterRegistry != null) meterRegistry.close();
        }
    }

    /**
     * 验证真实 TASK_ARRIVED 请求经生产规划器和 Writer 原子写入，再由 Worker 领取、SMTP 投递并提交成功；
     * 同时验证两个管理员线程并发补偿同一死信时只有一次 CAS 成功。
     *
     * @return void，任一生产状态、MIME、幂等、敏感信息或并发合同漂移时失败
     * @throws Exception SMTP、并发 Future 或 MIME 解析失败时报告
     */
    @Test
    void deliversRealApprovalNotificationAndSerializesDeadLetterCompensation()
            throws Exception
    {
        insertTestUser();
        insertNodePolicy(EVENT_TYPE);

        try (LocalSmtpTestServer receiver =
                new LocalSmtpTestServer(LocalSmtpTestServer.Behavior.ACCEPT,
                        smtpUsername, smtpCredential))
        {
            saveTestMailConfiguration(receiver.port());
            NotificationPlan deliveredPlan = planTaskEvent(deliveredSourceId);

            WorkflowNotificationWriter.WriteResult firstWrite = write(deliveredPlan);
            WorkflowNotificationWriter.WriteResult duplicateWrite = write(deliveredPlan);

            assertThat(deliveredPlan.notifications()).singleElement().satisfies(notification ->
            {
                assertThat(notification.channels()).containsExactly("INBOX", "EMAIL");
                assertThat(notification.recipientUserId()).isEqualTo(
                        String.valueOf(testUserId));
            });
            assertThat(firstWrite.channelRecordCount()).isEqualTo(2);
            assertThat(duplicateWrite.channelRecordCount()).isZero();
            assertThat(countInbox(deliveredSourceId)).isEqualTo(1);
            assertThat(countOutbox(deliveredSourceId)).isEqualTo(1);

            OutboxState pending = loadOutbox(deliveredSourceId);
            assertThat(pending.status()).isEqualTo("PENDING");
            assertThat(countClaimableOutbox()).isEqualTo(1);

            // Worker 的 batchSize 固定为 1，唯一到期行经生产 SKIP LOCKED/CAS 领取后同步完成真实 SMTP 副作用。
            worker.deliverBatch();

            LocalSmtpTestServer.ReceivedMessage received = receiver.awaitMessage();
            MimeMessage mime = received.parse();
            OutboxState processed = loadOutbox(deliveredSourceId);
            assertThat(processed.outboxId()).isEqualTo(pending.outboxId());
            assertThat(processed.status()).isEqualTo("PROCESSED");
            assertThat(processed.deliveryCycle()).isEqualTo(1);
            assertThat(processed.attemptCount()).isEqualTo(1);
            assertThat(processed.totalAttemptCount()).isEqualTo(1);
            assertThat(processed.revision()).isEqualTo(2);
            assertThat(processed.processedTime()).isNotNull();
            assertNull(processed.lastErrorCode());
            assertNull(processed.lastErrorSummary());
            assertThat(mime.getSubject()).isEqualTo("Pipeline 审批待处理");
            assertThat(String.valueOf(mime.getContent())).contains("Pipeline 审批节点");
            assertThat(mime.getHeader("X-ApprovaPlat-Idempotency-Key", null))
                    .isEqualTo(pending.idempotencyKey());
            assertThat(mime.getHeader("Message-ID", null))
                    .isEqualTo("<" + pending.idempotencyKey()
                            + "@approvaplat.notification>");
            assertThat(received.recipient()).contains(recipientEmail);
            assertThat(received.rawMessage()).doesNotContain(smtpCredential);
            assertThat(meterRegistry.get("workflow.notification.delivery.transitions")
                    .tag("action", "DELIVER").counter().count()).isEqualTo(1.0d);
            assertStoredCredentialIsEncrypted();
        }

        NotificationPlan deadLetterPlan = planTaskEvent(deadLetterSourceId);
        assertThat(write(deadLetterPlan).channelRecordCount()).isEqualTo(2);
        OutboxState deadLetterPending = loadOutbox(deadLetterSourceId);
        forceDeadLetter(deadLetterPending.outboxId());

        List<CompensationOutcome> outcomes = compensateConcurrently(
                deadLetterPending.outboxId());
        assertThat(outcomes).containsExactlyInAnyOrder(
                CompensationOutcome.SUCCESS, CompensationOutcome.CONFLICT);

        OutboxState compensated = loadOutbox(deadLetterSourceId);
        assertThat(compensated.status()).isEqualTo("RETRYING");
        assertThat(compensated.deliveryCycle()).isEqualTo(2);
        assertThat(compensated.attemptCount()).isZero();
        assertThat(compensated.totalAttemptCount()).isEqualTo(3);
        assertThat(compensated.revision()).isEqualTo(1);
        assertNull(compensated.processedTime());
        assertNull(compensated.lastErrorCode());
        assertNull(compensated.lastErrorSummary());
        assertThat(meterRegistry.get("workflow.notification.delivery.transitions")
                .tag("action", "COMPENSATE").counter().count()).isEqualTo(1.0d);
    }

    /**
     * 验证真实 Flowable 用户任务完成事件只能经生产监听器进入 Planner、Inbox 和 EMAIL Outbox；
     * 随后由本地 SMTP 明确拒绝认证时，邮件记录进入重试，但已提交的站内信仍保持未读可见。
     *
     * @return void，事件监听、事务写入、SMTP 失败隔离或夹具边界漂移时测试失败
     * @throws Exception Flowable 引擎、SMTP 协议或数据库操作失败时报告
     */
    @Test
    void routesRealFlowableTaskCompletionAndKeepsInboxWhenSmtpFails()
            throws Exception
    {
        insertTestUser();
        insertNodePolicy("TASK_COMPLETED");

        try (LocalSmtpTestServer receiver =
                new LocalSmtpTestServer(
                        LocalSmtpTestServer.Behavior.REJECT_AUTHENTICATION,
                        smtpUsername, smtpCredential))
        {
            saveTestMailConfiguration(receiver.port());
            ForwardingTaskListener listenerBridge = startNotificationFlowableEngine();
            listenerBridge.setDelegate(productionUserTaskListener());

            Authentication.setAuthenticatedUserId(String.valueOf(testUserId));
            try
            {
                flowableProcessInstanceId = flowableProcessEngine.getRuntimeService()
                        .startProcessInstanceByKey(processDefinitionKey,
                                Map.of("approverUserId", String.valueOf(testUserId)))
                        .getProcessInstanceId();
                Task approvalTask = flowableProcessEngine.getTaskService().createTaskQuery()
                        .processInstanceId(flowableProcessInstanceId).singleResult();
                assertNotNull(approvalTask, "真实 Flowable 审批任务未创建");
                assertEquals(taskDefinitionKey, approvalTask.getTaskDefinitionKey());

                // 只调用 Flowable TaskService，通知事实必须由 BPMN 上的生产监听器自动生成。
                flowableProcessEngine.getTaskService().complete(approvalTask.getId());
                assertNull(flowableProcessEngine.getRuntimeService().createProcessInstanceQuery()
                        .processInstanceId(flowableProcessInstanceId).singleResult());

                String sourceId = "TASK:" + approvalTask.getId() + ":complete:0";
                assertThat(countInbox(sourceId)).isEqualTo(1);
                assertThat(countOutbox(sourceId)).isEqualTo(1);
                assertThat(jdbcTemplate.queryForMap(
                        "select source_type,event_type,read_status from wf_notification_inbox "
                        + "where source_id=? and recipient_user_id=?",
                        sourceId, testUserId))
                        .containsEntry("source_type", "APPROVAL")
                        .containsEntry("event_type", "TASK_COMPLETED")
                        .containsEntry("read_status", "UNREAD");
                assertThat(loadOutbox(sourceId).status()).isEqualTo("PENDING");

                // Worker 在 Flowable 事务提交后执行真实 TCP/AUTH，SMTP 失败不能回滚独立 Inbox 事实。
                worker.deliverBatch();

                OutboxState failed = loadOutbox(sourceId);
                assertThat(failed.status()).isEqualTo("RETRYING");
                assertThat(failed.attemptCount()).isEqualTo(1);
                assertThat(failed.totalAttemptCount()).isEqualTo(1);
                assertThat(failed.lastErrorCode()).isEqualTo("SMTP_AUTH_FAILED");
                assertThat(failed.lastErrorSummary()).isEqualTo("SMTP 认证失败")
                        .doesNotContain(smtpUsername, smtpCredential, recipientEmail,
                                SMTP_HOST);
                assertThat(countInbox(sourceId)).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        "select read_status from wf_notification_inbox "
                        + "where source_id=? and recipient_user_id=?",
                        String.class, sourceId, testUserId)).isEqualTo("UNREAD");
            }
            finally
            {
                Authentication.setAuthenticatedUserId(null);
            }
        }
    }

    /**
     * 生成本次测试的用户、流程、任务、来源、SMTP 账号和敏感授权码，避免与任何现有行重合。
     *
     * @return void，所有字段写入当前测试实例
     */
    private void initializeFixtureIdentity()
    {
        UUID unique = UUID.randomUUID();
        fixtureToken = unique.toString().replace("-", "").substring(0, 12);
        testUserId = 8_000_000_000_000_000_000L
                + Long.remainderUnsigned(unique.getMostSignificantBits(),
                        100_000_000_000_000_000L);
        testUserName = "notif_it_" + fixtureToken;
        recipientEmail = "recipient-" + fixtureToken + "@example.test";
        smtpUsername = "smtp-" + fixtureToken + "@example.test";
        smtpCredential = "pipeline-it-secret-" + fixtureToken;
        smtpSenderName = "Pipeline IT " + fixtureToken;
        processDefinitionKey = "pipeline_it_process_" + fixtureToken;
        taskDefinitionKey = "pipeline_it_task_" + fixtureToken;
        processInstanceId = "pipeline-instance-" + fixtureToken;
        taskId = "pipeline-task-" + fixtureToken;
        deliveredSourceId = "PIPELINE:" + fixtureToken + ":DELIVER";
        deadLetterSourceId = "PIPELINE:" + fixtureToken + ":DEAD";
    }

    /**
     * 装配真实生产 Planner、Writer、OutboxService、Coordinator、Worker 和动态邮件发送服务。
     *
     * @return void，所有带 @Transactional 的生产服务均通过同一真实 MySQL 事务管理器代理
     */
    private void assembleProductionPipeline()
    {
        identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenReturn(
                new WorkflowCurrentIdentity(String.valueOf(testUserId), Set.of()));
        when(identityResolver.resolveActiveUserIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Set.of(String.valueOf(testUserId)));

        WorkflowNotificationProperties notificationProperties =
                new WorkflowNotificationProperties();
        notificationProperties.setBatchSize(1);
        meterRegistry = new SimpleMeterRegistry();
        WorkflowNotificationMetrics metrics = new WorkflowNotificationMetrics(meterRegistry);
        WorkflowNotificationOutboxService outboxTarget =
                new WorkflowNotificationOutboxService(jdbcTemplate,
                        notificationProperties, metrics, identityResolver);
        outboxService = WorkflowMySqlITSupport.transactionalProxy(
                outboxTarget, transactionManager);

        planner = new WorkflowNotificationPlanner(jdbcTemplate,
                mock(RuntimeService.class), mock(HistoryService.class),
                mock(TaskService.class), identityResolver);
        writer = WorkflowMySqlITSupport.transactionalProxy(
                new WorkflowNotificationWriter(jdbcTemplate, outboxService),
                transactionManager);

        byte[] purposeKey = new byte[32];
        Arrays.fill(purposeKey, (byte) 0x5A);
        WorkflowMailFailureClassifier failureClassifier =
                new WorkflowMailFailureClassifier();
        mailConfigService = WorkflowMySqlITSupport.transactionalProxy(
                new WorkflowMailConfigService(jdbcTemplate,
                        new WorkflowMailCredentialCipher(
                                new SecretKeySpec(purposeKey, "AES"),
                                new SecureRandom()),
                        failureClassifier, identityResolver),
                transactionManager);
        Arrays.fill(purposeKey, (byte) 0);

        MailNotificationChannel mailChannel = new MailNotificationChannel(
                mailConfigService, failureClassifier);
        SmsNotificationChannel smsChannel = new SmsNotificationChannel(
                mock(SysSmsService.class));
        WorkflowNotificationDeliveryCoordinator coordinator =
                new WorkflowNotificationDeliveryCoordinator(outboxService,
                        List.of(mailChannel, smsChannel));
        worker = new WorkflowNotificationWorker(coordinator);
    }

    /**
     * 在隔离 MySQL Flowable schema 上启动真实引擎，并部署仅含一个审批任务的测试流程。
     *
     * @return ForwardingTaskListener，部署表达式持有且尚待绑定生产监听器的转发入口
     */
    private ForwardingTaskListener startNotificationFlowableEngine()
    {
        ForwardingTaskListener listenerBridge = new ForwardingTaskListener();
        SpringProcessEngineConfiguration configuration =
                new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        configuration.setDatabaseSchemaUpdate("false");
        configuration.setHistory("full");
        configuration.setDisableIdmEngine(true);
        configuration.setDisableEventRegistry(true);
        configuration.setAsyncExecutorActivate(false);
        configuration.setBeans(Map.of("notificationEventListenerBridge", listenerBridge));
        flowableProcessEngine = configuration.buildProcessEngine();

        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="https://approvaplat.test/notification">
                  <process id="%s" name="真实审批通知事件" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="toApproval" sourceRef="start" targetRef="%s"/>
                    <userTask id="%s" name="真实审批节点"
                              flowable:assignee="${approverUserId}">
                      <extensionElements>
                        <flowable:taskListener event="create"
                            delegateExpression="${notificationEventListenerBridge}"/>
                        <flowable:taskListener event="complete"
                            delegateExpression="${notificationEventListenerBridge}"/>
                      </extensionElements>
                    </userTask>
                    <sequenceFlow id="toEnd" sourceRef="%s" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(processDefinitionKey, taskDefinitionKey,
                        taskDefinitionKey, taskDefinitionKey);
        flowableDeploymentId = flowableProcessEngine.getRepositoryService()
                .createDeployment()
                .name("notification-event-it-" + fixtureToken)
                .addString("notification-event-it-" + fixtureToken + ".bpmn20.xml", bpmn)
                .deploy().getId();
        return listenerBridge;
    }

    /**
     * 使用真实引擎服务装配生产 WorkflowUserTaskListener；非通知领域协作者保持无副作用，
     * 使本测试只观测用户任务事件到通知规划与持久化的正式调用链。
     *
     * @return WorkflowUserTaskListener，绑定真实 Flowable 服务和生产 Planner/Writer 的监听器
     */
    private WorkflowUserTaskListener productionUserTaskListener()
    {
        WorkflowNotificationPlanner flowablePlanner = new WorkflowNotificationPlanner(
                jdbcTemplate, flowableProcessEngine.getRuntimeService(),
                flowableProcessEngine.getHistoryService(),
                flowableProcessEngine.getTaskService(), identityResolver);
        WorkflowNotificationService notificationService =
                new WorkflowNotificationService(jdbcTemplate,
                        flowableProcessEngine.getRepositoryService(),
                        flowableProcessEngine.getRuntimeService(),
                        flowableProcessEngine.getTaskService(), flowablePlanner, writer,
                        outboxService, mock(WfCopyMapper.class));
        return new WorkflowUserTaskListener(
                mock(WorkflowUserTaskAuditService.class),
                mock(WorkflowTaskSlaRuntimeService.class),
                mock(WorkflowParticipantRuleRuntimeService.class),
                mock(WorkflowAutomaticCopyService.class), notificationService,
                mock(WorkflowMultiInstanceRoundLifecycleService.class));
    }

    /**
     * 插入当前测试独占且邮件启用的真实 sys_user 行。
     *
     * @return void，成功时用户可被生产 Planner 和 claim SQL 解析
     */
    private void insertTestUser()
    {
        assertEquals(0, count("select count(*) from sys_user where user_id=? or user_name=?",
                testUserId, testUserName));
        int inserted = jdbcTemplate.update("insert into sys_user "
                + "(user_id,user_name,nick_name,email,status,del_flag,create_by,create_time,remark) "
                + "values (?,?,?,?,'0','0',?,current_timestamp(3),?)",
                testUserId, testUserName, "通知管道IT", recipientEmail,
                testUserName, "Workflow notification pipeline IT fixture");
        assertEquals(1, inserted);
    }

    /**
     * 插入当前唯一流程与节点上的指定审批事件策略，通道固定为 INBOX、EMAIL。
     *
     * @param eventType String，TASK_ARRIVED 或 TASK_COMPLETED
     * @return void，生成的策略主键保存到 policyId 供精确清理
     */
    private void insertNodePolicy(String eventType)
    {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection ->
        {
            var statement = connection.prepareStatement(
                    "insert into wf_notification_policy "
                    + "(scope_type,process_definition_key,task_definition_key,event_type,"
                    + "recipient_rules,channels,sms_template_id,title_template,content_template,"
                    + "max_attempts,status,revision,create_by,create_time) "
                    + "values ('NODE',?,?,?,'TASK_RECIPIENT','INBOX,EMAIL',null,?,?,3,"
                    + "'ENABLED',0,?,current_timestamp(3))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, processDefinitionKey);
            statement.setString(2, taskDefinitionKey);
            statement.setString(3, eventType);
            statement.setString(4, "{{processName}}待处理");
            statement.setString(5, "请处理{{taskName}}，来源{{processDefinitionKey}}");
            statement.setString(6, testUserName);
            return statement;
        }, keyHolder);
        assertEquals(1, inserted);
        assertNotNull(keyHolder.getKey(), "通知策略主键读取失败");
        policyId = keyHolder.getKey().longValue();
    }

    /**
     * 通过生产 SMTP 保存入口将本地接收器配置加密写入 sys_mail_config。
     *
     * @param smtpPort int，本次本地真实 TCP SMTP 监听端口
     * @return void，保存后 revision 必须在原版本基础上增长
     */
    private void saveTestMailConfiguration(int smtpPort)
    {
        long expectedRevision = originalMailConfig == null
                ? 0L : originalMailConfig.revision();
        configMutationAttempted = true;
        WorkflowMailConfigView saved = mailConfigService.save(
                new WorkflowMailConfigRequest(SMTP_HOST, smtpPort, "NONE",
                        smtpUsername, smtpCredential, smtpUsername,
                        smtpSenderName, expectedRevision));
        assertTrue(saved.configured());
        assertTrue(saved.credentialConfigured());
        assertEquals(expectedRevision + 1L, saved.revision());
    }

    /**
     * 构造一个真实 TASK_ARRIVED 规划请求，接收人来自当前测试用户且不触发模拟的 Flowable 查询。
     *
     * @param sourceId String，当前业务事实的唯一来源键
     * @return WorkflowNotificationPlanner.NotificationRequest，可提交给生产 Planner 的冻结事实
     */
    private WorkflowNotificationPlanner.NotificationRequest notificationRequest(
            String sourceId)
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getKey()).thenReturn(processDefinitionKey);
        when(definition.getName()).thenReturn("Pipeline 审批");
        return WorkflowNotificationPlanner.NotificationRequest.taskEvent(EVENT_TYPE,
                sourceId, definition, processInstanceId, taskId, taskDefinitionKey,
                "Pipeline 审批节点", null, Set.of(String.valueOf(testUserId)),
                "/workflow/process-detail/" + processInstanceId
                        + "?source=todo&taskId=" + taskId);
    }

    /**
     * 在与生产任务监听一致的 Flowable 认证上下文中生成任务通知计划。
     *
     * @param sourceId String，当前业务事实的唯一来源键
     * @return NotificationPlan，已冻结操作者审计字段的生产规划结果
     */
    private NotificationPlan planTaskEvent(String sourceId)
    {
        Authentication.setAuthenticatedUserId(String.valueOf(testUserId));
        try
        {
            return planner.plan(notificationRequest(sourceId));
        }
        finally
        {
            Authentication.setAuthenticatedUserId(null);
        }
    }

    /**
     * 在真实可写事务中调用带 MANDATORY 合同的生产 Writer。
     *
     * @param plan NotificationPlan，生产 Planner 生成的不可变计划
     * @return WorkflowNotificationWriter.WriteResult，实际新增通道记录数量
     */
    private WorkflowNotificationWriter.WriteResult write(NotificationPlan plan)
    {
        WorkflowNotificationWriter.WriteResult result = transactionTemplate.execute(
                status -> writer.write(plan));
        assertNotNull(result);
        return result;
    }

    /**
     * 将测试第二条 outbox 精确转换为合法 DEAD_LETTER，作为并发管理员补偿起点。
     *
     * @param outboxId long，当前测试独占的 outbox 主键
     * @return void，状态和有界尝试计数均通过真实 MySQL CHECK
     */
    private void forceDeadLetter(long outboxId)
    {
        int updated = jdbcTemplate.update("update wf_notification_outbox set "
                + "status='DEAD_LETTER',attempt_count=max_attempts,"
                + "total_attempt_count=max_attempts,processed_time=current_timestamp(3),"
                + "last_error_code='SMTP_AUTH_FAILED',last_error_summary='SMTP 认证失败' "
                + "where outbox_id=? and source_id=? and recipient_user_id=? "
                + "and status='PENDING'", outboxId, deadLetterSourceId, testUserId);
        assertEquals(1, updated);
    }

    /**
     * 使用两个真实线程同时调用生产补偿事务，记录一次成功和一次 409 冲突。
     *
     * @param outboxId long，已经处于 DEAD_LETTER 的测试 outbox 主键
     * @return List&lt;CompensationOutcome&gt;，两个并发事务各自的稳定结果
     * @throws Exception 线程同步、Future 或执行器关闭失败时报告
     */
    private List<CompensationOutcome> compensateConcurrently(long outboxId)
            throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try
        {
            Future<CompensationOutcome> first = executor.submit(
                    () -> compensateAfterBarrier(outboxId, ready, start));
            Future<CompensationOutcome> second = executor.submit(
                    () -> compensateAfterBarrier(outboxId, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS), "补偿并发线程未全部就绪");
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
        }
        finally
        {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS),
                    "补偿并发线程未按时结束");
        }
    }

    /**
     * 在公共起跑门后执行一次补偿，并把唯一允许的 409 转换为测试结果枚举。
     *
     * @param outboxId long，当前死信主键
     * @param ready CountDownLatch，线程就绪计数器
     * @param start CountDownLatch，统一起跑门
     * @return CompensationOutcome，成功或乐观冲突
     * @throws InterruptedException 等待起跑门被中断时报告
     */
    private CompensationOutcome compensateAfterBarrier(long outboxId,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException
    {
        ready.countDown();
        start.await();
        try
        {
            outboxService.compensate(outboxId);
            return CompensationOutcome.SUCCESS;
        }
        catch (ServiceException exception)
        {
            assertEquals(HttpStatus.CONFLICT, exception.getCode());
            assertEquals("当前通知状态不允许补偿", exception.getMessage());
            return CompensationOutcome.CONFLICT;
        }
    }

    /**
     * 回读当前来源键唯一 EMAIL outbox 的完整状态字段。
     *
     * @param sourceId String，当前测试精确来源键
     * @return OutboxState，真实 MySQL 状态快照
     */
    private OutboxState loadOutbox(String sourceId)
    {
        List<OutboxState> rows = jdbcTemplate.query(
                "select outbox_id,idempotency_key,status,delivery_cycle,attempt_count,"
                + "total_attempt_count,revision,processed_time,last_error_code,"
                + "last_error_summary from wf_notification_outbox where source_id=? "
                + "and recipient_user_id=? and channel='EMAIL'",
                (result, rowNumber) -> new OutboxState(result.getLong("outbox_id"),
                        result.getString("idempotency_key"), result.getString("status"),
                        result.getInt("delivery_cycle"), result.getInt("attempt_count"),
                        result.getInt("total_attempt_count"), result.getInt("revision"),
                        timestamp(result, "processed_time"),
                        result.getString("last_error_code"),
                        result.getString("last_error_summary")),
                sourceId, testUserId);
        assertThat(rows).singleElement();
        return rows.get(0);
    }

    /**
     * 将可空 JDBC Timestamp 转换为 LocalDateTime。
     *
     * @param result ResultSet，当前 outbox 查询结果
     * @param column String，时间列名
     * @return LocalDateTime，可空数据库时间
     * @throws SQLException JDBC 读取失败时报告
     */
    private LocalDateTime timestamp(ResultSet result, String column) throws SQLException
    {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /**
     * 断言生产 SMTP 保存入口只在数据库留下非明文密文和 12 字节随机 IV。
     *
     * @return void，授权码明文或无效 IV 落库时失败
     */
    private void assertStoredCredentialIsEncrypted()
    {
        var stored = jdbcTemplate.queryForMap("select credential_ciphertext,credential_iv "
                + "from sys_mail_config where config_id=1");
        String ciphertext = String.valueOf(stored.get("credential_ciphertext"));
        assertThat(ciphertext).isNotEqualTo(smtpCredential).doesNotContain(smtpCredential);
        assertThat((byte[]) stored.get("credential_iv")).hasSize(12);
    }

    /**
     * 查询当前测试来源键的 inbox 行数。
     *
     * @param sourceId String，当前测试精确来源键
     * @return int，真实 MySQL inbox 数量
     */
    private int countInbox(String sourceId)
    {
        return count("select count(*) from wf_notification_inbox "
                + "where source_id=? and recipient_user_id=?", sourceId, testUserId);
    }

    /**
     * 查询当前测试来源键的 EMAIL outbox 行数。
     *
     * @param sourceId String，当前测试精确来源键
     * @return int，真实 MySQL outbox 数量
     */
    private int countOutbox(String sourceId)
    {
        return count("select count(*) from wf_notification_outbox "
                + "where source_id=? and recipient_user_id=? and channel='EMAIL'",
                sourceId, testUserId);
    }

    /**
     * 查询生产 claimNext 当前可领取的全库记录数，执行 Worker 前必须只存在本测试一条。
     *
     * @return int，满足 PENDING、RETRYING 或过期 DELIVERING 条件的记录数
     */
    private int countClaimableOutbox()
    {
        return count("select count(*) from wf_notification_outbox where "
                + "(((status in ('PENDING','RETRYING') and next_attempt_at<=current_timestamp(3)) "
                + "or (status='DELIVERING' and lease_expires_at<current_timestamp(3))) "
                + "and attempt_count<max_attempts)");
    }

    /**
     * 在 Worker 执行前拒绝专用库中存在任何外来可领取通知，避免生产查询触碰其他业务行。
     *
     * @return void，存在外来到期记录时立即失败关闭
     */
    private void assertNoForeignClaimableOutbox()
    {
        assertEquals(0, countClaimableOutbox(),
                "approvaplat_it 存在外来可领取通知，拒绝运行生产 Worker IT");
    }

    /**
     * 执行必须返回非空 Integer 的参数化计数查询。
     *
     * @param sql String，聚合 count SQL
     * @param args Object[]，按顺序绑定的精确参数
     * @return int，数据库真实计数
     */
    private int count(String sql, Object... args)
    {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        assertNotNull(value);
        return value;
    }

    /**
     * 完整读取 config_id=1 的公开字段、密文、IV 和审计时间，供测试后原样恢复。
     *
     * @return MailConfigBackup，未配置时为 null
     */
    private MailConfigBackup loadMailConfig()
    {
        List<MailConfigBackup> rows = jdbcTemplate.query(
                "select config_id,smtp_host,smtp_port,encryption_mode,username,"
                + "credential_ciphertext,credential_iv,from_address,sender_name,"
                + "revision,create_by,create_time,update_by,"
                + "update_time from sys_mail_config where config_id=1",
                (result, rowNumber) -> new MailConfigBackup(
                        result.getLong("config_id"), result.getString("smtp_host"),
                        result.getInt("smtp_port"), result.getString("encryption_mode"),
                        result.getString("username"),
                        result.getString("credential_ciphertext"),
                        result.getBytes("credential_iv"),
                        result.getString("from_address"),
                        result.getString("sender_name"), result.getLong("revision"),
                        result.getString("create_by"), timestamp(result, "create_time"),
                        result.getString("update_by"), timestamp(result, "update_time")));
        assertThat(rows).hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 级联删除当前测试的唯一 Flowable 部署并关闭独占引擎，避免运行时、历史和部署夹具残留。
     *
     * @return void，未启动引擎时幂等返回；清理失败会保留为测试失败
     */
    private void cleanupFlowableFixture()
    {
        if (flowableProcessEngine == null) return;
        RuntimeException failure = null;
        try
        {
            if (flowableDeploymentId != null
                    && flowableProcessEngine.getRepositoryService()
                            .createDeploymentQuery().deploymentId(flowableDeploymentId)
                            .count() == 1)
            {
                flowableProcessEngine.getRepositoryService()
                        .deleteDeployment(flowableDeploymentId, true);
            }
            if (flowableDeploymentId != null)
            {
                assertEquals(0L, flowableProcessEngine.getRepositoryService()
                        .createDeploymentQuery().deploymentId(flowableDeploymentId).count(),
                        "Flowable 测试部署未清理");
            }
            if (flowableProcessInstanceId != null)
            {
                assertEquals(0L, flowableProcessEngine.getRuntimeService()
                        .createProcessInstanceQuery()
                        .processInstanceId(flowableProcessInstanceId).count(),
                        "Flowable 运行实例未清理");
                assertEquals(0L, flowableProcessEngine.getHistoryService()
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(flowableProcessInstanceId).count(),
                        "Flowable 历史实例未清理");
            }
        }
        catch (RuntimeException exception)
        {
            failure = exception;
        }
        finally
        {
            try
            {
                flowableProcessEngine.close();
            }
            catch (RuntimeException closeException)
            {
                if (failure == null) failure = closeException;
                else failure.addSuppressed(closeException);
            }
            flowableProcessEngine = null;
        }
        if (failure != null) throw failure;
    }

    /**
     * 按当前测试独占用户精确删除全部通知、策略、偏好和用户，不使用无条件 DELETE、TRUNCATE 或前缀扫描。
     *
     * @return void，删除顺序遵循通知外键到用户的依赖关系
     */
    private void cleanupBusinessFixtures()
    {
        if (jdbcTemplate == null || fixtureToken == null) return;
        jdbcTemplate.update("delete from wf_notification_outbox where recipient_user_id=?",
                testUserId);
        jdbcTemplate.update("delete from wf_notification_inbox where recipient_user_id=?",
                testUserId);
        jdbcTemplate.update("delete from wf_notification_preference where user_id=?",
                testUserId);
        if (policyId != null)
        {
            jdbcTemplate.update("delete from wf_notification_policy where policy_id=? "
                    + "and process_definition_key=? and task_definition_key=?",
                    policyId, processDefinitionKey, taskDefinitionKey);
        }
        jdbcTemplate.update("delete from sys_user where user_id=? and user_name=?",
                testUserId, testUserName);
    }

    /**
     * 仅删除带本测试随机 SMTP 账号与发件人名称的单例，再完整插回任务开始前的原记录。
     *
     * @return void，若单例已被外部改写则拒绝覆盖并使测试失败
     */
    private void restoreMailConfig()
    {
        if (!configMutationAttempted) return;
        int deleted = jdbcTemplate.update("delete from sys_mail_config where config_id=1 "
                + "and username=? and sender_name=?", smtpUsername, smtpSenderName);
        assertTrue(deleted == 0 || deleted == 1, "SMTP 测试配置清理命中异常");
        if (deleted == 0)
        {
            // 保存入口在写库前失败时原快照仍在；若内容不同则说明存在外部并发修改，禁止覆盖。
            assertMailConfigRestored();
            return;
        }
        if (originalMailConfig == null) return;
        int restored = jdbcTemplate.update("insert into sys_mail_config "
                + "(config_id,smtp_host,smtp_port,encryption_mode,username,"
                + "credential_ciphertext,credential_iv,from_address,sender_name,"
                + "revision,create_by,create_time,update_by,update_time) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                originalMailConfig.configId(), originalMailConfig.smtpHost(),
                originalMailConfig.smtpPort(), originalMailConfig.encryptionMode(),
                originalMailConfig.username(), originalMailConfig.credentialCiphertext(),
                originalMailConfig.credentialIv(), originalMailConfig.fromAddress(),
                originalMailConfig.senderName(),
                originalMailConfig.revision(), originalMailConfig.createBy(),
                originalMailConfig.createTime(), originalMailConfig.updateBy(),
                originalMailConfig.updateTime());
        assertEquals(1, restored);
    }

    /**
     * 回读当前随机身份对应的全部业务表，确保测试结束后没有残留行。
     *
     * @return void，任一测试行未清理时失败
     */
    private void assertFixtureRowsRemoved()
    {
        if (jdbcTemplate == null || fixtureToken == null) return;
        assertEquals(0, count("select count(*) from wf_notification_outbox where "
                + "recipient_user_id=?", testUserId));
        assertEquals(0, count("select count(*) from wf_notification_inbox where "
                + "recipient_user_id=?", testUserId));
        assertEquals(0, count("select count(*) from wf_notification_policy where "
                + "process_definition_key=? and task_definition_key=?",
                processDefinitionKey, taskDefinitionKey));
        assertEquals(0, count("select count(*) from sys_user where user_id=? "
                + "or user_name=?", testUserId, testUserName));
    }

    /**
     * 逐字段比较当前 SMTP 单例与测试开始前快照，byte[] IV 使用内容比较而非引用比较。
     *
     * @return void，未配置状态或任一公开、密文、审计字段漂移时失败
     */
    private void assertMailConfigRestored()
    {
        if (jdbcTemplate == null) return;
        MailConfigBackup current = loadMailConfig();
        if (originalMailConfig == null)
        {
            assertNull(current, "测试前未配置 SMTP，结束后必须仍未配置");
            return;
        }
        assertNotNull(current, "测试前 SMTP 配置未恢复");
        assertEquals(originalMailConfig.configId(), current.configId());
        assertEquals(originalMailConfig.smtpHost(), current.smtpHost());
        assertEquals(originalMailConfig.smtpPort(), current.smtpPort());
        assertEquals(originalMailConfig.encryptionMode(), current.encryptionMode());
        assertEquals(originalMailConfig.username(), current.username());
        assertEquals(originalMailConfig.credentialCiphertext(),
                current.credentialCiphertext());
        assertArrayEquals(originalMailConfig.credentialIv(), current.credentialIv());
        assertEquals(originalMailConfig.fromAddress(), current.fromAddress());
        assertEquals(originalMailConfig.senderName(), current.senderName());
        assertEquals(originalMailConfig.revision(), current.revision());
        assertEquals(originalMailConfig.createBy(), current.createBy());
        assertEquals(originalMailConfig.createTime(), current.createTime());
        assertEquals(originalMailConfig.updateBy(), current.updateBy());
        assertEquals(originalMailConfig.updateTime(), current.updateTime());
    }

    /**
     * 使用 MySQL 命名锁串行化本测试类的 SMTP 单例备份与恢复窗口。
     *
     * @return void，成功时锁由 isolationLockConnection 持有
     * @throws SQLException 获取连接或命名锁失败时报告
     */
    private void acquireIsolationLock() throws SQLException
    {
        isolationLockConnection = dataSource.getConnection();
        try (Statement statement = isolationLockConnection.createStatement();
                ResultSet result = statement.executeQuery(
                        "select get_lock('" + LOCK_NAME + "',10)"))
        {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1), "未能取得通知管道 IT 串行锁");
        }
    }

    /**
     * 释放当前连接持有的 MySQL 命名锁并关闭专用连接。
     *
     * @return void，无锁或连接已关闭时幂等返回
     * @throws SQLException 锁释放或连接关闭失败时报告
     */
    private void releaseIsolationLock() throws SQLException
    {
        if (isolationLockConnection == null) return;
        try (Statement statement = isolationLockConnection.createStatement();
                ResultSet result = statement.executeQuery(
                        "select release_lock('" + LOCK_NAME + "')"))
        {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1), "通知管道 IT 串行锁释放失败");
        }
        finally
        {
            isolationLockConnection.close();
            isolationLockConnection = null;
        }
    }

    /**
     * BPMN 表达式在引擎启动前需要稳定 Bean 身份，因此先注册本转发器，部署后再原子绑定生产监听器。
     */
    private static final class ForwardingTaskListener implements TaskListener
    {
        /** 实际处理 Flowable 事件的生产监听器；部署完成前保持为空。 */
        private volatile TaskListener delegate;

        /**
         * 绑定当前测试唯一生产监听器。
         *
         * @param delegate TaskListener，完整装配的 WorkflowUserTaskListener
         * @return void，重复绑定或空监听器视为测试装配错误
         */
        private void setDelegate(TaskListener delegate)
        {
            if (delegate == null || this.delegate != null)
            {
                throw new IllegalStateException("Flowable 通知监听器绑定状态异常");
            }
            this.delegate = delegate;
        }

        /**
         * 转发真实 Flowable 任务事件，不伪造 DelegateTask 或直接调用通知服务。
         *
         * @param delegateTask DelegateTask，真实引擎当前命令中的任务事件
         * @return void，尚未绑定生产监听器时拒绝继续引擎事务
         */
        @Override
        public void notify(DelegateTask delegateTask)
        {
            TaskListener current = delegate;
            if (current == null)
            {
                throw new FlowableException("Flowable 通知监听器尚未完成装配");
            }
            current.notify(delegateTask);
        }
    }

    /** 真实 outbox 状态快照，不含收件地址或正文。 */
    private record OutboxState(long outboxId, String idempotencyKey, String status,
            int deliveryCycle, int attemptCount, int totalAttemptCount, int revision,
            LocalDateTime processedTime, String lastErrorCode, String lastErrorSummary) { }

    /** SMTP 单例完整备份，仅存在测试内存中且从不输出密文内容。 */
    private record MailConfigBackup(long configId, String smtpHost, int smtpPort,
            String encryptionMode, String username, String credentialCiphertext,
            byte[] credentialIv, String fromAddress, String senderName, long revision,
            String createBy, LocalDateTime createTime,
            String updateBy, LocalDateTime updateTime)
    {
        /**
         * 防御性复制数据库 IV，避免恢复前被可变数组修改。
         *
         * @return void，record 构造完成后保存独立数组
         */
        private MailConfigBackup
        {
            credentialIv = credentialIv == null ? null
                    : Arrays.copyOf(credentialIv, credentialIv.length);
        }

        /**
         * 返回数据库 IV 的防御性副本。
         *
         * @return byte[]，可空的独立 IV 数组
         */
        @Override
        public byte[] credentialIv()
        {
            return credentialIv == null ? null
                    : Arrays.copyOf(credentialIv, credentialIv.length);
        }
    }

    /** 两个管理员并发补偿允许出现的完整结果集合。 */
    private enum CompensationOutcome
    {
        SUCCESS,
        CONFLICT
    }

}
