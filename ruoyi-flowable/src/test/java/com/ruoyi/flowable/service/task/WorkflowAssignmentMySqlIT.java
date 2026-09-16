package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.*;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.TaskService;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 正式 MySQL RBAC Mapper、Flowable 监听与同一事务；只使用随机夹具，不创建或迁移表。 */
class WorkflowAssignmentMySqlIT
{
    private ProcessEngine engine;
    private TaskService tasks;
    private WorkflowIdentityMapper identityMapper;
    private WorkflowIdentityResolver identities;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private String deploymentId;
    private String processKey;
    private long base;

    @BeforeEach
    void setUp() throws Exception
    {
        var dataSource = WorkflowMySqlITSupport.createDataSource();
        WorkflowMySqlITSupport.verifyIsolatedBaseline(dataSource, "任务分配事务 IT", null,
                List.of("sys_user", "sys_role", "sys_role_menu", "act_ru_task", "act_hi_comment"));
        jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var binding = new WorkflowMultiInstanceEngineHarness.LateBindingTaskListener();
        var configuration = new SpringProcessEngineConfiguration();
        configuration.setDataSource(dataSource); configuration.setTransactionManager(manager);
        configuration.setDatabaseSchemaUpdate("false"); configuration.setHistory("full");
        configuration.setDisableIdmEngine(true); configuration.setDisableEventRegistry(true);
        configuration.setAsyncExecutorActivate(false); configuration.setBeans(Map.of("userTaskListener", binding));
        engine = configuration.buildProcessEngine();
        tasks = spy(engine.getTaskService());
        identityMapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "assignment-mysql",
                WorkflowIdentityMapper.class, "mapper/flowable/WorkflowIdentityMapper.xml"));
        identities = new WorkflowIdentityResolver(identityMapper, new WorkflowIdentityCodec());
        var artifacts = new WorkflowDeploymentArtifactRepository(engine.getRepositoryService());
        var participants = new WorkflowParticipantRuleRuntimeService(engine.getRepositoryService(), engine.getRuntimeService(),
                artifacts, identityMapper, identities, mock(WorkflowParticipantResolutionMetrics.class));
        binding.bind(new WorkflowUserTaskListener(new WorkflowUserTaskAuditService(tasks, new WorkflowIdentityCodec()),
                mock(WorkflowTaskSlaRuntimeService.class), participants, mock(WorkflowAutomaticCopyService.class),
                mock(WorkflowNotificationService.class), mock(WorkflowMultiInstanceRoundLifecycleService.class)));
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        base = 4_000_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000L) * 10;
        processKey = "assignmentMysql" + token;
        for (int offset = 0; offset < 3; offset++)
        {
            jdbc.update("insert into sys_user(user_id,user_name,nick_name,status,del_flag) values(?,?,?,'0','0')",
                    base + offset, "c04_" + token + offset, "C04 test");
            jdbc.update("insert into sys_user_role(user_id,role_id) values(?,?)", base + offset, offset == 0 ? base : base + 1);
        }
        for (int offset = 0; offset < 2; offset++)
        {
            jdbc.update("insert into sys_role(role_id,role_name,role_key,role_sort,status,del_flag) values(?,?,?,1,'0','0')",
                    base + offset, "C04 " + token + offset, "c04_" + token + offset);
            jdbc.update("insert into sys_role_menu(role_id,menu_id) select ?,menu_id from sys_menu where perms in "
                    + "('workflow:process:todoList','workflow:process:query','workflow:process:approval')", base + offset);
        }
        jdbc.update("insert into sys_role_menu(role_id,menu_id) select ?,menu_id from sys_menu where perms in "
                + "('workflow:process:claimList','workflow:process:claim')", base + 1);
        String listeners = "<extensionElements><flowable:taskListener event=\"create\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"assignment\" delegateExpression=\"${userTaskListener}\"/>"
                + "<flowable:taskListener event=\"complete\" delegateExpression=\"${userTaskListener}\"/></extensionElements>";
        String xml = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"test\">"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\"><startEvent id=\"start\"/><sequenceFlow sourceRef=\"start\" targetRef=\"review\"/>"
                + "<userTask id=\"review\">" + listeners + "</userTask><sequenceFlow sourceRef=\"review\" targetRef=\"next\"/>"
                + "<userTask id=\"next\">" + listeners + "</userTask><sequenceFlow sourceRef=\"next\" targetRef=\"end\"/><endEvent id=\"end\"/></process></definitions>";
        deploymentId = engine.getRepositoryService().createDeployment().addString("assignment.bpmn20.xml", xml).deploy().getId();
        var rules = new ArrayList<WfDeployParticipantRule>();
        for (String id : List.of("review", "next"))
        {
            var rule = new WfDeployParticipantRule();
            rule.setProcessKey(processKey); rule.setActivityId(id); rule.setActivityName(id); rule.setRuleScope("TASK");
            rule.setAssignmentMode("ASSIGNEE"); rule.setRuleType("FIXED_USER"); rule.setTargetIds(Long.toString(base));
            rule.setNoMatchPolicy("FAIL"); rule.setRuleVersion(1); rule.setChecksum(id); rule.setCreateBy(Long.toString(base));
            rules.add(rule);
        }
        artifacts.persist(deploymentId, new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), rules,
                List.of(), List.of(), List.of(), List.of()));
    }

    @AfterEach
    void tearDown()
    {
        Authentication.setAuthenticatedUserId(null);
        try
        {
            if (engine != null && deploymentId != null)
            {
                new WorkflowDeploymentArtifactRepository(engine.getRepositoryService()).delete(deploymentId);
                engine.getRepositoryService().deleteDeployment(deploymentId, true);
                assertThat(jdbc.queryForObject("select count(*) from ACT_RE_DEPLOYMENT where ID_=? or PARENT_DEPLOYMENT_ID_=?",
                        Long.class, deploymentId, deploymentId)).isZero();
            }
            if (jdbc != null && base != 0)
            {
                jdbc.update("delete from sys_user_role where user_id in (?,?,?)", base, base + 1, base + 2);
                jdbc.update("delete from sys_role_menu where role_id in (?,?)", base, base + 1);
                jdbc.update("delete from sys_role where role_id in (?,?)", base, base + 1);
                jdbc.update("delete from sys_user where user_id in (?,?,?)", base, base + 1, base + 2);
                assertThat(jdbc.queryForObject("select count(*) from sys_user where user_id in (?,?,?)", Long.class,
                        base, base + 1, base + 2)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from sys_role where role_id in (?,?)", Long.class,
                        base, base + 1)).isZero();
            }
        }
        finally { if (engine != null) engine.close(); }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void assignsWithRealApprovalAndClaimRbac(boolean candidate)
    {
        assertThat(identities.resolveApprovalEligibleUserIds(List.of(Long.toString(base)))).containsExactly(Long.toString(base));
        assertThat(identities.resolveClaimEligibleUserIds(List.of(Long.toString(base)))).isEmpty();
        Task source = start();
        complete(source, candidate ? List.of(base + 2, base + 1) : List.of(base));
        Task next = tasks.createTaskQuery().processInstanceId(source.getProcessInstanceId()).singleResult();
        assertThat(next.getAssignee()).isEqualTo(candidate ? null : Long.toString(base));
        if (candidate) assertThat(tasks.getIdentityLinksForTask(next.getId()).stream()
                .filter(link -> "candidate".equals(link.getType())).map(link -> link.getUserId()).toList())
                .containsExactlyInAnyOrder(Long.toString(base + 2), Long.toString(base + 1));
        assertThat(tasks.getTaskComments(next.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "database", "audit", "revoked" })
    void failedAssignmentRollsBackEngineAndAudit(String failure)
    {
        Task source = start();
        int before = tasks.getTaskComments(source.getId()).size();
        if ("database".equals(failure)) doAnswer(i -> jdbc.queryForList("select id from c04_missing_identity_table", Long.class))
                .when(identityMapper).selectApprovalEligibleUserIdsByUserIds(List.of(base + 1));
        if ("revoked".equals(failure)) jdbc.update("update sys_user set status='1' where user_id=?", base + 1);
        if ("audit".equals(failure)) doAnswer(i ->
        {
            Object result = i.callRealMethod();
            if (((String) i.getArgument(3)).contains("USER_TASK_ASSIGNMENT"))
            {
                String id = "c04_" + base;
                jdbc.update("insert into ACT_HI_COMMENT(ID_) values(?)", id);
                jdbc.update("insert into ACT_HI_COMMENT(ID_) values(?)", id);
            }
            return result;
        }).when(tasks).addComment(anyString(), anyString(), eq(WorkflowUserTaskAuditService.COMMENT_TYPE), anyString());
        var failureAssertion = assertThatThrownBy(() -> complete(source, List.of(base + 1)));
        if ("revoked".equals(failure)) failureAssertion.isInstanceOf(com.ruoyi.common.exception.ServiceException.class)
                .hasMessage("所选用户不存在、已停用或无流程办理权限");
        else failureAssertion.isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(tasks.createTaskQuery().taskId(source.getId()).count()).isOne();
        assertThat(tasks.createTaskQuery().processInstanceId(source.getProcessInstanceId()).count()).isOne();
        assertThat(tasks.getTaskComments(source.getId())).hasSize(before);
        assertThat(engine.getHistoryService().createHistoricTaskInstanceQuery().processInstanceId(source.getProcessInstanceId()).count()).isOne();
        assertThat(jdbc.queryForObject("select count(*) from ACT_HI_COMMENT where ID_=?", Long.class, "c04_" + base)).isZero();
    }

    private Task start()
    {
        Authentication.setAuthenticatedUserId(Long.toString(base));
        String id = engine.getRuntimeService().startProcessInstanceByKey(processKey, Map.of("initiator", Long.toString(base))).getId();
        return tasks.createTaskQuery().processInstanceId(id).singleResult();
    }

    private void complete(Task source, List<Long> users)
    {
        transaction.executeWithoutResult(status ->
        {
            var process = engine.getRepositoryService().getBpmnModel(source.getProcessDefinitionId()).getMainProcess();
            var next = new WorkflowNextTaskAssignmentService(new WorkflowUserSelectionValidator(identities), tasks,
                    engine.getRuntimeService(), new WorkflowMultiInstanceRuntimeSnapshotReader(engine.getRepositoryService(), engine.getRuntimeService(), tasks));
            var plan = next.prepare(source, process, (org.flowable.bpmn.model.UserTask) process.getFlowElement("review"), users);
            tasks.complete(source.getId());
            next.apply(plan);
        });
    }
}
