package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

import java.util.*;
import java.sql.Connection;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.*;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.*;
import com.ruoyi.flowable.service.notification.*;
import com.ruoyi.flowable.service.process.WorkflowProcessCompletionStatusListener;
import com.ruoyi.flowable.testsupport.*;
import com.ruoyi.system.mapper.SysUserMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.*;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 MySQL Mapper、Flowable 生命周期及 Spring 事务中的分配 → 抄送 → 通知回归。 */
class WorkflowAutomaticCopyMySqlIT
{
    private ProcessEngine engine;
    private JdbcTemplate jdbc;
    private JdbcTemplate plannerJdbc;
    private TransactionTemplate transaction;
    private WorkflowIdentityResolver identities;
    private WorkflowIdentityMapper identityMapper;
    private WfCopyMapper copies;
    private WorkflowNotificationService notifications;
    private WorkflowNotificationWriter writer;
    private WorkflowTaskCopyService manualCopies;
    private RuntimeService copyRuntime;
    private HistoryService copyHistory;
    private SimpleMeterRegistry registry;
    private Connection lock;
    private List<Map<String, Object>> defaultPolicies;
    private final List<DelegateTask> observedTasks = new ArrayList<>();
    private final List<String> deployments = new ArrayList<>();
    private String processKey;
    private long base;

    @BeforeEach
    void setUp() throws Exception
    {
        var dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource, "自动抄送事务 IT", "approvaplat_it",
                List.of("sys_user", "wf_copy", "wf_notification_policy", "act_hi_comment"));
        jdbc = new JdbcTemplate(dataSource);
        lock = dataSource.getConnection();
        try (var statement = lock.createStatement(); var result = statement.executeQuery(
                "select get_lock('workflow_auto_copy_it',30)"))
        { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isOne(); }
        defaultPolicies = jdbc.queryForList("select policy_id,status from wf_notification_policy where scope_type='DEFAULT'");
        jdbc.update("update wf_notification_policy set status='DISABLED' where scope_type='DEFAULT'");
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        var binding = new WorkflowMultiInstanceEngineHarness.LateBindingTaskListener();
        var beans = new DefaultListableBeanFactory();
        var configuration = new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource); configuration.setTransactionManager(manager);
        configuration.setDatabaseSchemaUpdate("false"); configuration.setHistory("full");
        configuration.setDisableIdmEngine(true); configuration.setDisableEventRegistry(true);
        configuration.setAsyncExecutorActivate(false); configuration.setBeans(Map.of("userTaskListener", binding));
        configuration.setEventListeners(List.of(new WorkflowProcessCompletionStatusListener(
                beans.getBeanProvider(WorkflowAutomaticCopyService.class), beans.getBeanProvider(WorkflowNotificationService.class),
                beans.getBeanProvider(WorkflowNotificationOutboxService.class))));
        engine = configuration.buildProcessEngine();
        identityMapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "copy-identities",
                WorkflowIdentityMapper.class, "mapper/flowable/WorkflowIdentityMapper.xml"));
        identities = new WorkflowIdentityResolver(identityMapper, new WorkflowIdentityCodec());
        copies = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "auto-copy",
                WfCopyMapper.class, "mapper/flowable/WfCopyMapper.xml"));
        var userFactory = new org.mybatis.spring.SqlSessionFactoryBean();
        userFactory.setDataSource(dataSource);
        userFactory.setTypeAliasesPackage("com.ruoyi.common.core.domain.entity");
        userFactory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/system/SysUserMapper.xml"));
        var users = new org.mybatis.spring.SqlSessionTemplate(userFactory.getObject()).getMapper(SysUserMapper.class);
        registry = new SimpleMeterRegistry();
        var outbox = new WorkflowNotificationOutboxService(jdbc, new WorkflowNotificationProperties(),
                new WorkflowNotificationMetrics(registry), identities);
        plannerJdbc = spy(new JdbcTemplate(dataSource));
        var planner = new WorkflowNotificationPlanner(plannerJdbc, engine.getRuntimeService(), engine.getHistoryService(),
                engine.getTaskService(), identities);
        writer = spy(new WorkflowNotificationWriter(jdbc, outbox));
        notifications = new WorkflowNotificationService(jdbc, engine.getRepositoryService(), engine.getRuntimeService(),
                engine.getTaskService(), planner, writer, outbox, copies);
        var runtimeTaskMapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "copy-task-revision",
                WorkflowRuntimeTaskMapper.class, "mapper/flowable/WorkflowRuntimeTaskMapper.xml");
        manualCopies = new WorkflowTaskCopyService(new WorkflowUserSelectionValidator(identities), copies, runtimeTaskMapper,
                engine.getRepositoryService(), engine.getRuntimeService(), users, notifications);
        copyRuntime = spy(engine.getRuntimeService()); copyHistory = spy(engine.getHistoryService());
        var automatic = new WorkflowAutomaticCopyService(engine.getRepositoryService(), copyRuntime, copyHistory,
                identities, copies, users, notifications);
        beans.registerSingleton("copies", automatic); beans.registerSingleton("notifications", notifications);
        beans.registerSingleton("outbox", outbox);
        var artifacts = new WorkflowDeploymentArtifactRepository(engine.getRepositoryService());
        var participants = new WorkflowParticipantRuleRuntimeService(engine.getRepositoryService(), engine.getRuntimeService(),
                artifacts, identityMapper, identities, mock(WorkflowParticipantResolutionMetrics.class));
        var listener = new WorkflowUserTaskListener(new WorkflowUserTaskAuditService(engine.getTaskService(), new WorkflowIdentityCodec()),
                mock(WorkflowTaskSlaRuntimeService.class), participants, automatic, notifications,
                mock(WorkflowMultiInstanceRoundLifecycleService.class));
        var observedListener = spy(listener);
        doAnswer(invocation ->
        {
            DelegateTask task = invocation.getArgument(0);
            // 记录真实引擎事件的变量访问；所有调用继续作用于引擎提供的原始任务实体。
            DelegateTask observed = mock(DelegateTask.class, delegatesTo(task));
            observedTasks.add(observed);
            listener.notify(observed);
            return null;
        }).when(observedListener).notify(any());
        binding.bind(observedListener);
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        processKey = "autoCopy" + token;
        base = 20_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) * 10;
        for (int i = 0; i < 4; i++)
        {
            jdbc.update("insert into sys_user(user_id,user_name,nick_name,status,del_flag) values(?,?,?,'0','0')",
                    base + i, "c09_" + token + i, "Copy test " + i);
            jdbc.update("insert into sys_user_role(user_id,role_id) values(?,?)", base + i, i == 0 ? base : base + 1);
        }
        for (int i = 0; i < 2; i++) jdbc.update("insert into sys_role(role_id,role_name,role_key,role_sort,status,del_flag) values(?,?,?,1,'0','0')",
                base + i, "C09 " + token + i, "c09_" + token + i);
        grant(base, "workflow:process:todoList", "workflow:process:query", "workflow:process:approval");
        grant(base + 1, "workflow:process:copyList", "workflow:process:query");
    }

    @AfterEach
    void tearDown() throws Exception
    {
        Authentication.setAuthenticatedUserId(null);
        try
        {
            if (jdbc != null && base != 0)
            {
                jdbc.update("delete from wf_notification_outbox where process_definition_key=?", processKey);
                jdbc.update("delete from wf_notification_inbox where recipient_user_id between ? and ?", base, base + 3);
                jdbc.update("delete from wf_copy where user_id between ? and ?", base, base + 3);
                jdbc.update("delete from wf_notification_policy where process_definition_key=?", processKey);
                jdbc.update("delete from wf_notification_preference where user_id between ? and ?", base, base + 3);
                for (String deployment : deployments)
                {
                    new WorkflowDeploymentArtifactRepository(engine.getRepositoryService()).delete(deployment);
                    engine.getRepositoryService().deleteDeployment(deployment, true);
                }
                jdbc.update("delete from sys_user_role where user_id between ? and ?", base, base + 3);
                jdbc.update("delete from sys_role_menu where role_id in (?,?)", base, base + 1);
                jdbc.update("delete from sys_role where role_id in (?,?)", base, base + 1);
                jdbc.update("delete from sys_user where user_id between ? and ?", base, base + 3);
                assertThat(jdbc.queryForObject("select count(*) from wf_copy where user_id between ? and ?", Long.class, base, base + 3)).isZero();
            }
            if (defaultPolicies != null) for (var row : defaultPolicies)
                jdbc.update("update wf_notification_policy set status=? where policy_id=?", row.get("status"), row.get("policy_id"));
        }
        finally
        {
            if (engine != null) engine.close();
            if (registry != null) registry.close();
            if (lock != null) { try (var statement = lock.createStatement()) { statement.execute("select release_lock('workflow_auto_copy_it')"); } lock.close(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "none", "otherNode", "otherTrigger", "assignment" })
    void irrelevantEventsDoNotLoadCopyFacts(String scenario)
    {
        deploy("otherTrigger".equals(scenario) ? rule("NODE_COMPLETED", false) : "",
                "otherNode".equals(scenario) ? rule("NODE_ARRIVED", false) : "", "");
        Task task = start();
        assertNoVariableReads();
        verify(copyRuntime, never()).createProcessInstanceQuery();
        assertThat(copyCount()).isZero();
        if ("none".equals(scenario))
        {
            engine.getTaskService().complete(task.getId());
            engine.getTaskService().complete(currentTask(task.getProcessInstanceId()).getId());
            verify(copyHistory, never()).createHistoricProcessInstanceQuery();
            verify(copyHistory, never()).createHistoricVariableInstanceQuery();
            assertNoVariableReads();
        }
        if ("assignment".equals(scenario))
        {
            observedTasks.clear();
            engine.getTaskService().setAssignee(task.getId(), null);
            engine.getTaskService().setAssignee(task.getId(), Long.toString(base));
            assertThat(observedTasks).isNotEmpty();
            assertNoVariableReads();
            verify(copyRuntime, never()).createProcessInstanceQuery();
            assertThat(copyCount()).isZero();
        }
        assertThat(engine.getTaskService().getTaskComments(task.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE)).isNotEmpty();
    }

    @Test
    void copiesRealRecipientsWithoutPolicyThenSeesNextBatchConfiguration()
    {
        deploy(rule("NODE_ARRIVED", false), rule("NODE_ARRIVED", true), rule("PROCESS_COMPLETED", true));
        Task review = start();
        assertThat(review.getAssignee()).isEqualTo(Long.toString(base));
        assertThat(identities.resolveApprovalEligibleUserIds(List.of(Long.toString(base + 1)))).isEmpty();
        assertThat(copyCount()).isEqualTo(3);
        assertNoVariableReads();
        assertCopyPolicyQueries(1);
        verify(plannerJdbc, never()).queryForList(contains("from sys_user"), any(Object[].class));
        assertThat(notificationCount("wf_notification_inbox")).isZero();
        assertThat(notificationCount("wf_notification_outbox")).isZero();

        policy("ENABLED", "first batch enabled");
        clearInvocations(plannerJdbc); observedTasks.clear();
        engine.getTaskService().complete(review.getId());
        Task next = currentTask(review.getProcessInstanceId());
        assertCopyPolicyQueries(1);
        assertThat(copyCount()).isEqualTo(6);
        assertThat(notificationCount("wf_notification_inbox")).isEqualTo(3);
        assertThat(notificationCount("wf_notification_outbox")).isEqualTo(3);
        for (var task : observedTasks) verify(task, never()).getVariables();
        assertThat(observedTasks.stream().flatMap(task -> mockingDetails(task).getInvocations().stream())
                .filter(i -> i.getMethod().getName().equals("getVariables")))
                .singleElement().satisfies(i ->
                {
                    assertThat((Collection<String>) i.getArgument(0)).containsExactly("copyUsers");
                    assertThat((Boolean) i.getArgument(1)).isFalse();
                });

        jdbc.update("update wf_notification_policy set title_template='changed next batch' where process_definition_key=?", processKey);
        clearInvocations(plannerJdbc);
        engine.getTaskService().complete(next.getId());
        assertCopyPolicyQueries(1);
        assertThat(copyCount()).isEqualTo(9);
        assertThat(notificationCount("wf_notification_inbox")).isEqualTo(6);
        assertThat(notificationCount("wf_notification_outbox")).isEqualTo(6);
        assertThat(jdbc.queryForObject("select count(*) from wf_notification_inbox where recipient_user_id between ? and ? and title='changed next batch'",
                Long.class, base, base + 3)).isEqualTo(3);
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(review.getProcessInstanceId()).singleResult().getBusinessStatus()).isEqualTo("completed");
        verify(copyHistory, times(1)).createHistoricVariableInstanceQuery();
    }

    @Test
    void fixedCompletionDoesNotReadHistoricVariablesAndDisabledPolicyStaysEmpty()
    {
        deploy("", "", rule("PROCESS_COMPLETED", false)); policy("DISABLED", "disabled");
        Task first = start(); engine.getTaskService().complete(first.getId());
        engine.getTaskService().complete(currentTask(first.getProcessInstanceId()).getId());
        assertThat(copyCount()).isEqualTo(3); assertCopyPolicyQueries(1);
        assertNoVariableReads(); verify(copyHistory, never()).createHistoricVariableInstanceQuery();
        assertThat(notificationCount("wf_notification_inbox")).isZero();
        assertThat(notificationCount("wf_notification_outbox")).isZero();
    }

    @Test
    void unrelatedInjectedCorruptionDoesNotBlockCurrentNodeButCurrentCorruptionDoes()
    {
        deploy("", rule("NODE_ARRIVED", false), "");
        String definitionId = engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(processKey).singleResult().getId();
        UserTask nextModel = (UserTask) engine.getRepositoryService().getBpmnModel(definitionId).getMainProcess().getFlowElement("next");
        var property = nextModel.getExtensionElements().get("properties").get(0).getChildElements().get("property").get(0);
        String valid = property.getAttributeValue(null, "value");
        property.getAttributes().get("value").get(0).setValue(valid.replace("\"version\":1", "\"version\":99"));
        // 注入发生在已部署模型缓存；正式保存/部署使用的契约依然拒绝此输入。
        assertThatThrownBy(() -> WorkflowAutoCopyRuleContract.validatePlacement(nextModel,
                Set.of(WorkflowAutoCopyRuleContract.Trigger.NODE_ARRIVED, WorkflowAutoCopyRuleContract.Trigger.NODE_COMPLETED)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("版本不受支持");
        Task first = start(); assertNoVariableReads();
        assertThatThrownBy(() -> engine.getTaskService().complete(first.getId()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("版本不受支持");
        assertThat(engine.getTaskService().createTaskQuery().taskId(first.getId()).count()).isOne();
        assertThat(copyCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "copy", "notification", "recipient", "field", "limit" })
    void copyOrNotificationFailureRollsBackTaskAuditAndAllWrites(String failure)
    {
        deploy("", rule("NODE_ARRIVED", Set.of("field", "limit").contains(failure)), ""); policy("ENABLED", "rollback");
        Task first = start();
        int auditCount = engine.getTaskService().getTaskComments(first.getId()).size();
        if ("copy".equals(failure)) doAnswer(i ->
        {
            Object result = i.callRealMethod();
            assertThat(copyCount()).isEqualTo(3);
            jdbc.queryForList("select id from c09_missing_copy_table");
            return result;
        }).when(copies).insertBatchIdempotent(anyList());
        if ("notification".equals(failure)) doAnswer(i ->
        {
            Object result = i.callRealMethod();
            if (!((NotificationPlan) i.getArgument(0)).isEmpty())
            {
                assertThat(copyCount()).isEqualTo(3);
                assertThat(notificationCount("wf_notification_inbox")).isEqualTo(3);
                assertThat(notificationCount("wf_notification_outbox")).isEqualTo(3);
                jdbc.queryForList("select id from c09_missing_notification_table");
            }
            return result;
        }).when(writer).write(any());
        if ("recipient".equals(failure)) jdbc.update("update sys_user set status='1' where user_id=?", base + 2);
        if ("field".equals(failure)) engine.getRuntimeService().setVariable(first.getProcessInstanceId(), "copyUsers", "invalid-user");
        if ("limit".equals(failure)) engine.getRuntimeService().setVariable(first.getProcessInstanceId(), "copyUsers", new ArrayList<>(Collections.nCopies(1001, base + 1)));
        var failureAssertion = assertThatThrownBy(() -> engine.getTaskService().complete(first.getId()));
        if (Set.of("copy", "notification").contains(failure))
            failureAssertion.isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        else failureAssertion.isInstanceOf(ServiceException.class).hasMessage(switch (failure)
        {
            case "recipient" -> "自动抄送接收用户已失效或无对象可见权限";
            case "field" -> "表单用户字段值不合法";
            default -> "表单用户字段数量超过上限";
        });
        assertThat(engine.getTaskService().createTaskQuery().taskId(first.getId()).count()).isOne();
        assertThat(engine.getTaskService().getTaskComments(first.getId())).hasSize(auditCount);
        assertThat(engine.getHistoryService().createHistoricTaskInstanceQuery().processInstanceId(first.getProcessInstanceId()).count()).isOne();
        assertThat(copyCount()).isZero();
        assertThat(notificationCount("wf_notification_inbox")).isZero();
        assertThat(notificationCount("wf_notification_outbox")).isZero();
    }

    @Test
    void naturalKeyReplayMergesManualOriginAndPreservesReadState()
    {
        deploy(rule("NODE_COMPLETED", true), "", ""); policy("ENABLED", "idempotent");
        Task first = start();
        var manualPlan = transaction.execute(status ->
        {
            var plan = manualCopies.prepare(WorkflowTaskCopyAction.COMPLETE, first,
                    new WorkflowCurrentIdentity(Long.toString(base), Set.of()), List.of(base + 1, base + 2, base + 3));
            engine.getTaskService().complete(first.getId());
            manualCopies.persist(plan);
            return plan;
        });
        List<WfCopy> facts = jdbc.query("select copy_event_id,user_id from wf_copy where user_id between ? and ?",
                (rs, row) -> { WfCopy key = new WfCopy(); key.setCopyEventId(rs.getString(1)); key.setUserId(rs.getLong(2)); return key; }, base, base + 3);
        facts = copies.selectActiveByNaturalKeys(facts);
        assertThat(facts).hasSize(3);
        WfCopy read = facts.get(0);
        assertThat(copies.markRead(read.getCopyId(), read.getUserId(), Long.toString(read.getUserId()))).isOne();
        List<WfCopy> replay = facts;
        transaction.executeWithoutResult(status ->
        {
            manualCopies.persist(manualPlan);
            manualCopies.persist(manualPlan);
        });
        assertThat(copies.selectActiveByNaturalKeys(replay)).allSatisfy(copy -> assertThat(copy.getSourceType()).isEqualTo("MANUAL_AUTO"));
        assertThat(copies.selectActiveByNaturalKeys(List.of(read))).singleElement().satisfies(copy ->
        { assertThat(copy.getReadStatus()).isEqualTo("1"); assertThat(copy.getReadTime()).isNotNull(); });
        assertThat(copyCount()).isEqualTo(3);
        assertThat(notificationCount("wf_notification_inbox")).isEqualTo(3);
        assertThat(notificationCount("wf_notification_outbox")).isEqualTo(3);
    }

    private void grant(long role, String... permissions)
    {
        for (String permission : permissions) jdbc.update("insert into sys_role_menu(role_id,menu_id) select ?,menu_id from sys_menu where perms=?", role, permission);
    }

    private String rule(String trigger, boolean field)
    {
        String fixed = "{\"type\":\"USER\",\"values\":[\"" + (base + 1) + "\",\"" + (base + 2) + "\",\"" + (base + 3) + "\"]}";
        String source = field ? fixed + ",{\"type\":\"FORM_USER_FIELD\",\"values\":[\"copyUsers\"]}" : fixed;
        String json = "{\"version\":1,\"rules\":[{\"id\":\"copy\",\"trigger\":\"" + trigger + "\",\"recipients\":[" + source + "]}]}";
        return "<flowable:properties><flowable:property name=\"approva.autoCopyRules\" value=\"" + json.replace("\"", "&quot;") + "\"/></flowable:properties>";
    }

    private void deploy(String reviewRule, String nextRule, String processRule)
    {
        String listeners = "<flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"assignment\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"complete\" delegateExpression=\"${userTaskListener}\"/>";
        String xml = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"test\">"
                + "<process id=\"" + processKey + "\" name=\"Copy process\" isExecutable=\"true\"><extensionElements>" + processRule + "</extensionElements>"
                + "<startEvent id=\"start\"/><sequenceFlow id=\"s1\" sourceRef=\"start\" targetRef=\"review\"/>"
                + "<userTask id=\"review\" name=\"Review\"><extensionElements>" + reviewRule + listeners + "</extensionElements></userTask>"
                + "<sequenceFlow id=\"s2\" sourceRef=\"review\" targetRef=\"next\"/><userTask id=\"next\" name=\"Next\"><extensionElements>" + nextRule + listeners + "</extensionElements></userTask>"
                + "<sequenceFlow id=\"s3\" sourceRef=\"next\" targetRef=\"end\"/><endEvent id=\"end\"/></process></definitions>";
        String deployment = engine.getRepositoryService().createDeployment().addString("copy.bpmn20.xml", xml).deploy().getId();
        deployments.add(deployment);
        List<WfDeployParticipantRule> rules = new ArrayList<>();
        for (String node : List.of("review", "next"))
        {
            var rule = new WfDeployParticipantRule(); rule.setProcessKey(processKey); rule.setActivityId(node);
            rule.setActivityName(node); rule.setRuleScope("TASK"); rule.setAssignmentMode("ASSIGNEE");
            rule.setRuleType("FIXED_USER"); rule.setTargetIds(Long.toString(base)); rule.setNoMatchPolicy("FAIL");
            rule.setRuleVersion(1); rule.setChecksum(node); rule.setCreateBy(Long.toString(base)); rules.add(rule);
        }
        new WorkflowDeploymentArtifactRepository(engine.getRepositoryService()).persist(deployment,
                new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), rules, List.of(), List.of(), List.of(), List.of()));
    }

    private Task start()
    {
        Authentication.setAuthenticatedUserId(Long.toString(base));
        String id = engine.getRuntimeService().createProcessInstanceBuilder().processDefinitionKey(processKey).businessStatus("running")
                .variables(Map.of("initiator", Long.toString(base), "processStatus", "running", "copyUsers", List.of(base + 1, base + 1, base + 2, base + 3),
                        "unrelated", "must not be loaded for copy")).start().getId();
        return currentTask(id);
    }

    private Task currentTask(String id) { return engine.getTaskService().createTaskQuery().processInstanceId(id).singleResult(); }
    private long copyCount() { return jdbc.queryForObject("select count(*) from wf_copy where user_id between ? and ?", Long.class, base, base + 3); }
    private long notificationCount(String table) { return jdbc.queryForObject("select count(*) from " + table + " where recipient_user_id between ? and ?", Long.class, base, base + 3); }
    private void assertNoVariableReads()
    {
        for (DelegateTask task : observedTasks)
        {
            verify(task, never()).getVariables();
            verify(task, never()).getVariables(anyCollection());
            verify(task, never()).getVariables(anyCollection(), anyBoolean());
        }
    }
    private void assertCopyPolicyQueries(int count)
    {
        verify(plannerJdbc, times(count)).queryForList(contains("from wf_notification_policy"),
                eq("COPY_CREATED"), eq(processKey), isNull(), eq(processKey));
    }
    private void policy(String status, String title)
    {
        jdbc.update("insert into wf_notification_policy(scope_type,process_definition_key,event_type,recipient_rules,channels,title_template,content_template,max_attempts,status,revision) "
                + "values('PROCESS',?,'COPY_CREATED','TASK_RECIPIENT','INBOX,EMAIL',?,'Copy body',3,?,0)", processKey, title, status);
    }
}
