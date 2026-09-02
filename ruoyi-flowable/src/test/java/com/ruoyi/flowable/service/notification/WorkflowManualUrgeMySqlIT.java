package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.mysql.cj.jdbc.MysqlDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.domain.vo.WorkflowManualUrgeView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.runtime.WorkflowNotificationMetrics;
import com.ruoyi.flowable.runtime.WorkflowRedisAtomicOperations;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 在显式隔离的 approvaplat_it MySQL 上验证人工催办能力投影与正式写入共用同一准备规则。
 *
 * 测试不创建或修改表结构；每个用例使用唯一部署、用户和策略，并在结束时按业务主键清理。
 */
class WorkflowManualUrgeMySqlIT
{
    private static final String REQUIRED_DATABASE = "approvaplat_it";
    private static final String LOCK_NAME = "approvaplat_workflow_manual_urge_capability_it";
    private static final String URGE_PERMISSION = "workflow:notification:urge";
    private static final String URGE_ANY_PERMISSION = "workflow:notification:urge:any";

    private static MysqlDataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private JdbcTemplate trackedUrgeJdbcTemplate;
    private DataSourceTransactionManager transactionManager;
    private Connection isolationLockConnection;
    private ProcessEngine processEngine;
    private WorkflowAuthenticationContext authenticationContext;
    private WorkflowManualUrgeService manualUrgeService;
    private WorkflowRedisAtomicOperations redisAtomicOperations;
    private SimpleMeterRegistry meterRegistry;
    private AtomicReference<String> currentActor;
    private Set<String> basePermissionUsers;
    private Set<String> anyPermissionUsers;

    /** 当前用例唯一标识，同时参与部署、策略和用户自然键。 */
    private String fixtureToken;
    private String fixtureUserName;
    private String rootProcessKey;
    private String childProcessKey;
    private String noTaskProcessKey;
    private String noRecipientProcessKey;
    private String deploymentId;
    private Long policyId;
    private long applicantUserId;
    private long adminUserId;
    private long ordinaryUserId;
    private long recipientUserId;
    private long missingRecipientUserId;

    /**
     * 使用显式环境变量连接 MySQL，并只读核验人工催办依赖的正式表。
     *
     * @return void，隔离库和表结构通过门禁后保存共享数据源
     * @throws SQLException JDBC 环境读取失败时报告
     */
    @BeforeAll
    static void setUpDataSource() throws SQLException
    {
        dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource,
                "人工催办能力 IT", REQUIRED_DATABASE,
                List.of("sys_user", "wf_notification_policy",
                        "wf_notification_preference", "wf_notification_inbox",
                        "wf_notification_outbox", "ACT_RU_EXECUTION", "ACT_RU_TASK",
                        "ACT_RU_IDENTITYLINK", "ACT_HI_PROCINST"));
    }

    /**
     * 取得串行隔离锁，创建唯一用户和真实 Flowable 部署，并装配生产催办链路。
     *
     * @return void，用例所需生产对象和夹具全部准备完成
     * @throws Exception MySQL 锁、夹具或 Flowable 部署失败时报告
     */
    @BeforeEach
    void setUpFixture() throws Exception
    {
        jdbcTemplate = new JdbcTemplate(dataSource);
        trackedUrgeJdbcTemplate = org.mockito.Mockito.spy(new JdbcTemplate(dataSource));
        transactionManager = new DataSourceTransactionManager(dataSource);
        initializeFixtureIdentity();
        acquireIsolationLock();
        insertFixtureUsers();
        startFlowableEngineAndDeploy();
        assembleProductionService();
    }

    /**
     * 精确删除本用例通知、策略、Flowable 部署和用户，随后释放隔离锁。
     *
     * @return void，清理完成后隔离库不保留本用例业务事实
     * @throws Exception 清理或锁释放失败时报告
     */
    @AfterEach
    void tearDownFixture() throws Exception
    {
        try
        {
            cleanupBusinessFacts();
        }
        finally
        {
            if (processEngine != null)
            {
                processEngine.close();
            }
            if (meterRegistry != null)
            {
                meterRegistry.close();
            }
            releaseIsolationLock();
        }
    }

    /**
     * 验证发起人和跨实例管理员获得 true，普通无权用户与 CallActivity 子流程返回 false；
     * 同时证明所有能力查询没有锁定 SQL、通知、Redis、指标或数据库写入副作用。
     *
     * @return void，权限、根身份或零副作用合同漂移时测试失败
     */
    @Test
    void projectsOwnerAndAdministratorCapabilityWithoutReadSideEffects()
    {
        ProcessInstance root = startRootProcess();
        ProcessInstance child = requireChildProcess(root.getId());
        SideEffectSnapshot before = sideEffectSnapshot();
        clearInvocations(trackedUrgeJdbcTemplate, redisAtomicOperations);

        currentActor.set(Long.toString(applicantUserId));
        assertThat(manualUrgeService.isUrgeAllowed(root.getId())).isTrue();

        currentActor.set(Long.toString(adminUserId));
        assertThat(manualUrgeService.isUrgeAllowed(root.getId())).isTrue();

        currentActor.set(Long.toString(ordinaryUserId));
        assertThat(manualUrgeService.isUrgeAllowed(root.getId())).isFalse();
        // 即使普通用户拥有催办入口权限，只要不是发起人且没有 urge:any，仍必须失败关闭。
        basePermissionUsers = Set.of(Long.toString(applicantUserId),
                Long.toString(adminUserId), Long.toString(ordinaryUserId));
        assertThat(manualUrgeService.isUrgeAllowed(root.getId())).isFalse();

        currentActor.set(Long.toString(applicantUserId));
        assertThat(manualUrgeService.isUrgeAllowed(child.getId())).isFalse();

        assertThat(sideEffectSnapshot()).isEqualTo(before);
        assertReadQueriesContainNoLockClause();
        verify(redisAtomicOperations, never()).setIfAbsent(anyString(),
                org.mockito.ArgumentMatchers.any());
        assertThat(urgeMetric("accepted")).isZero();
        assertThat(urgeMetric("cooldown_rejected")).isZero();
        assertThat(urgeMetric("redis_unavailable")).isZero();
    }

    /**
     * 验证挂起、已结束、无活动任务和无有效接收人均稳定返回 false，损坏发起人数据保持 500。
     *
     * @return void，预期不可执行状态或数据损坏分类漂移时测试失败
     */
    @Test
    void distinguishesExpectedIneligibilityFromCorruptedRuntimeIdentity()
    {
        currentActor.set(Long.toString(applicantUserId));

        ProcessInstance suspendedRoot = startRootProcess();
        processEngine.getRuntimeService().suspendProcessInstanceById(suspendedRoot.getId());
        assertThat(manualUrgeService.isUrgeAllowed(suspendedRoot.getId())).isFalse();
        processEngine.getRuntimeService().activateProcessInstanceById(suspendedRoot.getId());
        ProcessInstance suspendedChild = requireChildProcess(suspendedRoot.getId());
        Task childTask = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(suspendedChild.getId()).singleResult();
        assertThat(childTask).isNotNull();
        processEngine.getTaskService().complete(childTask.getId());
        assertThat(manualUrgeService.isUrgeAllowed(suspendedRoot.getId())).isFalse();

        ProcessInstance noTask = authenticationContext.runAs(
                Long.toString(applicantUserId), () -> processEngine.getRuntimeService()
                        .startProcessInstanceByKey(noTaskProcessKey));
        assertThat(manualUrgeService.isUrgeAllowed(noTask.getId())).isFalse();

        ProcessInstance noRecipient = authenticationContext.runAs(
                Long.toString(applicantUserId), () -> processEngine.getRuntimeService()
                        .startProcessInstanceByKey(noRecipientProcessKey));
        assertThat(manualUrgeService.isUrgeAllowed(noRecipient.getId())).isFalse();

        ProcessInstance corrupted = startRootProcess();
        assertThat(jdbcTemplate.update(
                "update ACT_HI_PROCINST set START_USER_ID_=null where PROC_INST_ID_=?",
                corrupted.getId())).isEqualTo(1);
        assertThatThrownBy(() -> manualUrgeService.isUrgeAllowed(corrupted.getId()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR));

        verify(redisAtomicOperations, never()).setIfAbsent(anyString(),
                org.mockito.ArgumentMatchers.any());
        assertThat(notificationCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    /**
     * 验证正式催办在真实 MySQL 事务中登记站内通知，随后建立冷却；第二次冷却冲突回滚新通知。
     *
     * @return void，正式通知、接收人数、429 子码、事务回滚或指标顺序漂移时测试失败
     */
    @Test
    void registersRealNotificationAndKeepsCooldownGate()
    {
        insertManualUrgePolicy();
        ProcessInstance root = startRootProcess();
        ProcessInstance child = requireChildProcess(root.getId());
        Task task = processEngine.getTaskService().createTaskQuery()
                .processInstanceId(child.getId()).singleResult();
        assertThat(task).isNotNull();
        currentActor.set(Long.toString(applicantUserId));
        when(redisAtomicOperations.setIfAbsent(anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WorkflowRedisAtomicOperations.ExpiringSetResult(true, 300),
                        new WorkflowRedisAtomicOperations.ExpiringSetResult(false, 287));

        WorkflowManualUrgeView accepted = manualUrgeService.urge(
                new WorkflowManualUrgeRequest(root.getId(), "请尽快处理真实审批"));

        assertThat(accepted.recipientCount()).isEqualTo(1);
        assertThat(notificationCount(task.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select content from wf_notification_inbox where task_id=?",
                String.class, task.getId())).contains("请尽快处理真实审批");
        assertThat(urgeMetric("accepted")).isEqualTo(1.0d);

        assertThatThrownBy(() -> manualUrgeService.urge(
                new WorkflowManualUrgeRequest(root.getId(), "再次催办")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(exception.getSubCode())
                            .isEqualTo("WORKFLOW_URGE_COOLDOWN_ACTIVE");
                });

        // 第二次通知登记与冷却冲突处于同一写事务，429 必须回滚本次新通知。
        assertThat(notificationCount(task.getId())).isEqualTo(1);
        assertThat(urgeMetric("accepted")).isEqualTo(1.0d);
        assertThat(urgeMetric("cooldown_rejected")).isEqualTo(1.0d);
        verify(redisAtomicOperations, org.mockito.Mockito.times(2))
                .setIfAbsent("workflow:urge:cooldown:" + applicantUserId + ":" + root.getId(),
                        new WorkflowNotificationProperties().getUrgeInterval());
    }

    /**
     * 生成本次用例的唯一用户、流程和部署标识。
     *
     * @return void，所有夹具身份保存到当前测试实例
     */
    private void initializeFixtureIdentity()
    {
        UUID unique = UUID.randomUUID();
        fixtureToken = unique.toString().replace("-", "").substring(0, 12);
        fixtureUserName = "urge_it_" + fixtureToken;
        long offset = Long.remainderUnsigned(unique.getMostSignificantBits(),
                100_000_000_000_000_000L);
        applicantUserId = 7_000_000_000_000_000_000L + offset;
        adminUserId = applicantUserId + 1;
        ordinaryUserId = applicantUserId + 2;
        recipientUserId = applicantUserId + 3;
        missingRecipientUserId = applicantUserId + 4;
        rootProcessKey = "urge_root_" + fixtureToken;
        childProcessKey = "urge_child_" + fixtureToken;
        noTaskProcessKey = "urge_no_task_" + fixtureToken;
        noRecipientProcessKey = "urge_no_recipient_" + fixtureToken;
    }

    /**
     * 插入发起人、管理员、普通用户和真实接收人四个有效用户。
     *
     * @return void，四个用户均可被生产 SQL 解析，缺失接收人主键故意不插入
     */
    private void insertFixtureUsers()
    {
        insertUser(applicantUserId, "applicant");
        insertUser(adminUserId, "admin");
        insertUser(ordinaryUserId, "ordinary");
        insertUser(recipientUserId, "recipient");
    }

    /**
     * 插入单个有效且不与现有自然键冲突的测试用户。
     * @param userId long，测试用户主键
     * @param suffix String，用户名和昵称用途后缀
     * @return void，插入行数不是一时测试失败
     */
    private void insertUser(long userId, String suffix)
    {
        int inserted = jdbcTemplate.update("insert into sys_user "
                + "(user_id,user_name,nick_name,status,del_flag,create_by,create_time,remark) "
                + "values (?,?,?,'0','0',?,current_timestamp(3),?)",
                userId, fixtureUserName + "_" + suffix, "催办IT-" + suffix,
                fixtureUserName, "Workflow manual urge capability IT fixture");
        assertThat(inserted).isEqualTo(1);
    }

    /**
     * 在真实 MySQL Flowable schema 上启动引擎并部署根/子流程、无任务和无接收人流程。
     *
     * @return void，部署主键保存到当前测试实例
     */
    private void startFlowableEngineAndDeploy()
    {
        SpringProcessEngineConfiguration configuration =
                new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setTransactionManager(transactionManager);
        configuration.setDatabaseSchemaUpdate("false");
        configuration.setHistory("full");
        configuration.setDisableIdmEngine(true);
        configuration.setDisableEventRegistry(true);
        configuration.setAsyncExecutorActivate(false);
        // 独立 Spring 配置没有 ApplicationContext，显式空 beans 映射避免表达式解析访问空 BeanFactory。
        configuration.setBeans(Map.of());
        processEngine = configuration.buildProcessEngine();

        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="https://approvaplat.test/manual-urge">
                  <process id="%s" name="人工催办根流程" isExecutable="true">
                    <startEvent id="rootStart"/>
                    <callActivity id="callChild" calledElement="%s"/>
                    <endEvent id="rootEnd"/>
                    <sequenceFlow id="rootFlow1" sourceRef="rootStart" targetRef="callChild"/>
                    <sequenceFlow id="rootFlow2" sourceRef="callChild" targetRef="rootEnd"/>
                  </process>
                  <process id="%s" name="人工催办子流程" isExecutable="true">
                    <startEvent id="childStart"/>
                    <userTask id="childApproval" name="真实催办审批"
                      flowable:assignee="%s"/>
                    <endEvent id="childEnd"/>
                    <sequenceFlow id="childFlow1" sourceRef="childStart" targetRef="childApproval"/>
                    <sequenceFlow id="childFlow2" sourceRef="childApproval" targetRef="childEnd"/>
                  </process>
                  <process id="%s" name="无活动任务流程" isExecutable="true">
                    <startEvent id="waitStart"/>
                    <receiveTask id="waitSignal" name="等待外部信号"/>
                    <endEvent id="waitEnd"/>
                    <sequenceFlow id="waitFlow1" sourceRef="waitStart" targetRef="waitSignal"/>
                    <sequenceFlow id="waitFlow2" sourceRef="waitSignal" targetRef="waitEnd"/>
                  </process>
                  <process id="%s" name="无有效接收人流程" isExecutable="true">
                    <startEvent id="missingStart"/>
                    <userTask id="missingApproval" name="缺失接收人审批"
                      flowable:assignee="%s"/>
                    <endEvent id="missingEnd"/>
                    <sequenceFlow id="missingFlow1" sourceRef="missingStart" targetRef="missingApproval"/>
                    <sequenceFlow id="missingFlow2" sourceRef="missingApproval" targetRef="missingEnd"/>
                  </process>
                </definitions>
                """.formatted(rootProcessKey, childProcessKey, childProcessKey,
                        Long.toString(recipientUserId), noTaskProcessKey,
                        noRecipientProcessKey, Long.toString(missingRecipientUserId));
        deploymentId = processEngine.getRepositoryService().createDeployment()
                .name("manual-urge-it-" + fixtureToken)
                .addString("manual-urge-it-" + fixtureToken + ".bpmn20.xml", bpmn)
                .deploy().getId();
        authenticationContext = new WorkflowAuthenticationContext(
                processEngine.getIdentityService(), new WorkflowIdentityCodec());
    }

    /**
     * 使用真实事务、Flowable、Planner、Writer 和指标装配生产人工催办服务。
     *
     * @return void，只有 Redis 和 Web 权限上下文使用可观测测试替身
     */
    private void assembleProductionService()
    {
        currentActor = new AtomicReference<>(Long.toString(applicantUserId));
        basePermissionUsers = Set.of(Long.toString(applicantUserId),
                Long.toString(adminUserId));
        anyPermissionUsers = Set.of(Long.toString(adminUserId));
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(invocation ->
                new WorkflowCurrentIdentity(currentActor.get(), Set.of()));

        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.hasPermi(anyString())).thenAnswer(invocation ->
        {
            String permission = invocation.getArgument(0);
            if (URGE_PERMISSION.equals(permission))
            {
                return basePermissionUsers.contains(currentActor.get());
            }
            if (URGE_ANY_PERMISSION.equals(permission))
            {
                return anyPermissionUsers.contains(currentActor.get());
            }
            return false;
        });

        WorkflowEngineOperations engineOperations = WorkflowMySqlITSupport.transactionalProxy(
                new WorkflowEngineOperations(authenticationContext,
                        new WorkflowExceptionTranslator(), identityResolver),
                transactionManager);
        WorkflowNotificationProperties properties = new WorkflowNotificationProperties();
        meterRegistry = new SimpleMeterRegistry();
        WorkflowNotificationMetrics metrics = new WorkflowNotificationMetrics(meterRegistry);
        WorkflowNotificationOutboxService outboxService =
                new WorkflowNotificationOutboxService(jdbcTemplate, properties,
                        metrics, identityResolver);
        WorkflowNotificationWriter writer = new WorkflowNotificationWriter(
                jdbcTemplate, outboxService);
        WorkflowNotificationPlanner planner = new WorkflowNotificationPlanner(
                jdbcTemplate, processEngine.getRuntimeService(),
                processEngine.getHistoryService(), processEngine.getTaskService(),
                identityResolver);
        WorkflowNotificationService notificationService = new WorkflowNotificationService(
                jdbcTemplate, processEngine.getRepositoryService(),
                processEngine.getRuntimeService(), processEngine.getTaskService(),
                planner, writer, outboxService, mock(WfCopyMapper.class));
        redisAtomicOperations = mock(WorkflowRedisAtomicOperations.class);
        manualUrgeService = new WorkflowManualUrgeService(trackedUrgeJdbcTemplate,
                engineOperations, permissionService, notificationService,
                redisAtomicOperations, properties, metrics);
    }

    /**
     * 以真实 Flowable 发起人身份启动包含 CallActivity 子流程的根流程。
     *
     * @return ProcessInstance，停留在子流程审批任务的根实例
     */
    private ProcessInstance startRootProcess()
    {
        return authenticationContext.runAs(Long.toString(applicantUserId),
                () -> processEngine.getRuntimeService().startProcessInstanceByKey(
                        rootProcessKey));
    }

    /**
     * 查询指定根流程当前唯一 CallActivity 子实例。
     * @param rootProcessInstanceId String，真实根流程实例主键
     * @return ProcessInstance，唯一运行中的直接子流程
     */
    private ProcessInstance requireChildProcess(String rootProcessInstanceId)
    {
        ProcessInstance child = processEngine.getRuntimeService()
                .createProcessInstanceQuery()
                .superProcessInstanceId(rootProcessInstanceId).singleResult();
        assertThat(child).isNotNull();
        return child;
    }

    /**
     * 插入当前子流程节点唯一的 MANUAL_URGE 站内通知策略。
     *
     * @return void，策略主键保存用于精确清理
     */
    private void insertManualUrgePolicy()
    {
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        int inserted = jdbcTemplate.update(connection ->
        {
            var statement = connection.prepareStatement(
                    "insert into wf_notification_policy "
                    + "(scope_type,process_definition_key,task_definition_key,event_type,"
                    + "recipient_rules,channels,sms_template_id,title_template,content_template,"
                    + "max_attempts,status,revision,create_by,create_time) "
                    + "values ('NODE',?,?,'MANUAL_URGE','TASK_RECIPIENT','INBOX',null,"
                    + "'流程催办','请处理{{taskName}}',3,'ENABLED',0,?,current_timestamp(3))",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, childProcessKey);
            statement.setString(2, "childApproval");
            statement.setString(3, fixtureUserName);
            return statement;
        }, keyHolder);
        assertThat(inserted).isEqualTo(1);
        assertThat(keyHolder.getKey()).isNotNull();
        policyId = keyHolder.getKey().longValue();
    }

    /**
     * 捕获能力查询前后的数据库业务状态，用于证明查询没有任何写副作用。
     *
     * @return SideEffectSnapshot，通知、Outbox、审计和运行时 revision 快照
     */
    private SideEffectSnapshot sideEffectSnapshot()
    {
        return new SideEffectSnapshot(notificationCount(), outboxCount(), commentCount(),
                revisionSum("ACT_RU_EXECUTION"), revisionSum("ACT_RU_TASK"));
    }

    /**
     * 断言人工催办能力查询执行过的全部 SQL 不包含行锁子句。
     *
     * @return void，出现 FOR UPDATE 或 FOR SHARE 时测试失败
     */
    private void assertReadQueriesContainNoLockClause()
    {
        List<String> sqlStatements = mockingDetails(trackedUrgeJdbcTemplate)
                .getInvocations().stream()
                .flatMap(invocation -> List.of(invocation.getArguments()).stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::toLowerCase)
                .toList();
        assertThat(sqlStatements).isNotEmpty();
        assertThat(sqlStatements).noneMatch(sql -> sql.contains("for update")
                || sql.contains("for share"));
    }

    /**
     * 统计本部署流程实例产生的站内催办通知。
     * @return long，本部署当前通知行数
     */
    private long notificationCount()
    {
        return jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_inbox i "
                + "join ACT_HI_PROCINST p on p.PROC_INST_ID_=i.process_instance_id "
                + "join ACT_RE_PROCDEF d on d.ID_=p.PROC_DEF_ID_ where d.DEPLOYMENT_ID_=?",
                Long.class, deploymentId);
    }

    /**
     * 统计指定真实任务产生的站内催办通知。
     * @param taskId String，真实活动任务主键
     * @return long，该任务当前通知行数
     */
    private long notificationCount(String taskId)
    {
        return jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_inbox where task_id=?",
                Long.class, taskId);
    }

    /**
     * 统计本部署流程实例产生的外部通知 Outbox。
     * @return long，本部署当前 Outbox 行数
     */
    private long outboxCount()
    {
        return jdbcTemplate.queryForObject(
                "select count(*) from wf_notification_outbox where process_definition_key in (?,?,?,?)",
                Long.class, rootProcessKey, childProcessKey,
                noTaskProcessKey, noRecipientProcessKey);
    }

    /**
     * 统计本部署流程实例的 Flowable comment 审计行。
     * @return long，本部署当前 comment 行数
     */
    private long commentCount()
    {
        return jdbcTemplate.queryForObject(
                "select count(*) from ACT_HI_COMMENT c join ACT_HI_PROCINST p "
                + "on p.PROC_INST_ID_=c.PROC_INST_ID_ join ACT_RE_PROCDEF d "
                + "on d.ID_=p.PROC_DEF_ID_ where d.DEPLOYMENT_ID_=?",
                Long.class, deploymentId);
    }

    /**
     * 汇总本部署活动运行表的 revision，用于发现能力查询意外写入。
     * @param table String，仅允许 ACT_RU_EXECUTION 或 ACT_RU_TASK
     * @return long，本部署当前 revision 总和
     */
    private long revisionSum(String table)
    {
        if (!Set.of("ACT_RU_EXECUTION", "ACT_RU_TASK").contains(table))
        {
            throw new IllegalArgumentException("运行时 revision 表不受支持");
        }
        return jdbcTemplate.queryForObject("select coalesce(sum(r.REV_),0) from " + table
                + " r join ACT_RE_PROCDEF d on d.ID_=r.PROC_DEF_ID_ where d.DEPLOYMENT_ID_=?",
                Long.class, deploymentId);
    }

    /**
     * 读取指定人工催办固定结果指标。
     * @param result String，accepted、cooldown_rejected 或 redis_unavailable
     * @return double，当前指标累计值；尚未记录时为零
     */
    private double urgeMetric(String result)
    {
        return meterRegistry.find("workflow.notification.urge.requests")
                .tag("result", result).counter().count();
    }

    /**
     * 删除通知与策略后级联删除部署，最后删除四个测试用户并核对零残留。
     *
     * @return void，本用例写入的业务事实全部清除
     */
    private void cleanupBusinessFacts()
    {
        if (jdbcTemplate == null || fixtureToken == null)
        {
            return;
        }
        if (deploymentId != null)
        {
            jdbcTemplate.update("delete i from wf_notification_inbox i "
                    + "join ACT_HI_PROCINST p on p.PROC_INST_ID_=i.process_instance_id "
                    + "join ACT_RE_PROCDEF d on d.ID_=p.PROC_DEF_ID_ where d.DEPLOYMENT_ID_=?",
                    deploymentId);
            jdbcTemplate.update("delete from wf_notification_outbox "
                    + "where process_definition_key in (?,?,?,?)",
                    rootProcessKey, childProcessKey, noTaskProcessKey,
                    noRecipientProcessKey);
        }
        if (policyId != null)
        {
            jdbcTemplate.update("delete from wf_notification_policy where policy_id=?",
                    policyId);
        }
        if (processEngine != null && deploymentId != null
                && processEngine.getRepositoryService().createDeploymentQuery()
                        .deploymentId(deploymentId).count() == 1)
        {
            processEngine.getRepositoryService().deleteDeployment(deploymentId, true);
        }
        jdbcTemplate.update("delete from sys_user where user_id in (?,?,?,?)",
                applicantUserId, adminUserId, ordinaryUserId, recipientUserId);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_user where user_id in (?,?,?,?)",
                Long.class, applicantUserId, adminUserId,
                ordinaryUserId, recipientUserId)).isZero();
    }

    /**
     * 获取 MySQL 命名锁，避免同一能力场景并发清理彼此夹具。
     *
     * @return void，锁连接保持到 tearDown
     * @throws SQLException 连接或锁查询失败时报告
     */
    private void acquireIsolationLock() throws SQLException
    {
        isolationLockConnection = dataSource.getConnection();
        try (var statement = isolationLockConnection.prepareStatement(
                "select get_lock(?,30)"))
        {
            statement.setString(1, LOCK_NAME);
            try (ResultSet result = statement.executeQuery())
            {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    /**
     * 释放 MySQL 命名锁并关闭专用连接。
     *
     * @return void，未取得锁时安全跳过
     * @throws SQLException 释放或关闭连接失败时报告
     */
    private void releaseIsolationLock() throws SQLException
    {
        if (isolationLockConnection == null)
        {
            return;
        }
        try (var statement = isolationLockConnection.prepareStatement(
                "select release_lock(?)"))
        {
            statement.setString(1, LOCK_NAME);
            statement.executeQuery().close();
        }
        finally
        {
            isolationLockConnection.close();
            isolationLockConnection = null;
        }
    }

    /** 能力查询前后必须完全一致的数据库业务状态。 */
    private record SideEffectSnapshot(long notificationCount, long outboxCount,
            long commentCount, long executionRevisionSum, long taskRevisionSum) { }
}
