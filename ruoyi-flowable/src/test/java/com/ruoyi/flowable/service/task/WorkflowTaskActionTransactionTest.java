package com.ruoyi.flowable.service.task;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowNotificationProperties;
import com.ruoyi.flowable.domain.dto.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.WorkflowNotificationMetrics;
import com.ruoyi.flowable.service.notification.*;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.system.mapper.SysUserMapper;

/** Task actions use the real engine, identity Mapper, copy Mapper, Planner and Inbox Writer. */
public class WorkflowTaskActionTransactionTest
{
    private WorkflowFlowableEngineTestSupport engine;
    private JdbcTemplate jdbc;
    private WorkflowEngineOperations operations;
    private WorkflowTaskActionService actions;
    private TaskService tasks;
    private WfCopyMapper copies;
    private WorkflowNotificationWriter writer;
    private WorkflowIdentityResolver identities;
    private String deployment;
    private String instance;
    private String processKey;
    // H2 casts CHAR without length to one character; multi-digit identity SQL is exercised on MySQL.
    private final long base = System.getenv("WORKFLOW_MYSQL_TEST_URL") == null ? 4L
            : 500_000_000L + ThreadLocalRandom.current().nextLong(100_000_000L);

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        if (url == null || url.isBlank())
        {
            engine = WorkflowFlowableEngineTestSupport.start("task-action", Map.of());
            engine.jdbcTemplate().execute("SET MODE MySQL");
            WorkflowH2SchemaMapperSupport.executeSchema(engine.dataSource(), "db/h2/wf_task_action.sql");
        }
        else
        {
            WorkflowMysqlTargetGuard.verify(url);
            engine = WorkflowFlowableEngineTestSupport.start(new DriverManagerDataSource(url,
                    System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD")), Map.of());
        }
        jdbc = engine.jdbcTemplate();
        tasks = spy(engine.processEngine().getTaskService());
        identities = new WorkflowIdentityResolver(mapper(WorkflowIdentityMapper.class), new WorkflowIdentityCodec());
        operations = engine.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                engine.processEngine().getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
        copies = spy(mapper(WfCopyMapper.class));
        var outbox = engine.transactionalProxy(new WorkflowNotificationOutboxService(jdbc,
                new WorkflowNotificationProperties(), mock(WorkflowNotificationMetrics.class), identities));
        writer = spy(new WorkflowNotificationWriter(jdbc, outbox));
        var notifications = new WorkflowNotificationService(jdbc, engine.processEngine().getRepositoryService(),
                engine.processEngine().getRuntimeService(), tasks, engine.processEngine().getHistoryService(), identities,
                new WorkflowNotificationPlanner(jdbc), engine.transactionalProxy(writer), outbox, copies);
        var users = mock(SysUserMapper.class);
        when(users.selectUserById(anyLong())).thenAnswer(call -> {
            var user = new SysUser(); user.setUserId(call.getArgument(0)); user.setNickName("Task starter"); return user;
        });
        var taskCopies = new WorkflowTaskCopyService(new WorkflowUserSelectionValidator(identities), copies,
                mapper(WorkflowRuntimeTaskMapper.class), engine.processEngine().getRepositoryService(),
                engine.processEngine().getRuntimeService(), users, notifications);
        actions = new WorkflowTaskActionService(engine.processEngine().getRepositoryService(),
                engine.processEngine().getRuntimeService(), tasks, operations, identities, notifications,
                new WorkflowTaskUnclaimPolicy(), taskCopies);
        seedIdentity();
        authenticate(1);
    }

    @AfterEach
    void close()
    {
        SecurityContextHolder.clearContext();
        if (engine == null) return;
        try
        {
            if (deployment != null)
            {
                jdbc.update("delete from wf_notification_inbox where process_instance_id=?", instance);
                jdbc.update("delete from wf_notification_outbox where process_instance_id=?", instance);
                jdbc.update("delete from wf_copy where instance_id=?", instance);
                engine.processEngine().getRepositoryService().deleteDeployment(deployment, true);
                jdbc.update("delete from wf_notification_policy where process_definition_key=?", processKey);
            }
            jdbc.update("delete from sys_user_role where user_id between ? and ?", base + 1, base + 4);
            jdbc.update("delete from sys_role_menu where role_id between ? and ?", base + 21, base + 24);
            jdbc.update("delete from sys_role where role_id between ? and ?", base + 21, base + 24);
            jdbc.update("delete from sys_menu where menu_id between ? and ?", base + 41, base + 45);
            jdbc.update("delete from sys_user where user_id between ? and ?", base + 1, base + 4);
            jdbc.update("delete from sys_dept where dept_id=?", base);
        }
        finally { engine.close(); }
    }

    @Test
    void claimUnclaimKeepsCandidateLinksAuditAndInboxThenAllowsAnotherCandidate()
    {
        Task task = start(false, "");
        Set<String> before = candidateLinks(task);
        authenticate(3);
        error(() -> actions.claim(new WorkflowTaskClaimRequest(task.getId())), 403);
        assertNoEffects(task);
        authenticate(1);
        actions.claim(new WorkflowTaskClaimRequest(task.getId()));
        assertState(task, 1, null, null);
        assertThat(current(task).getClaimedBy()).isEqualTo(id(1));
        assertThat(current(task).getClaimTime()).isNotNull();
        assertAudit(task, "CLAIM", 1, null);
        assertInbox(task, "TASK_CLAIMED", 1);
        authenticate(3);
        error(() -> actions.unclaim(new WorkflowTaskUnclaimRequest(task.getId())), 403);
        authenticate(1);
        actions.unclaim(new WorkflowTaskUnclaimRequest(task.getId()));
        assertState(task, null, null, null);
        assertThat(current(task).getClaimedBy()).isNull();
        assertThat(current(task).getClaimTime()).isNull();
        assertThat(candidateLinks(task)).isEqualTo(before);
        assertInbox(task, "TASK_UNCLAIMED", 1, 2);
        authenticate(2);
        actions.claim(new WorkflowTaskClaimRequest(task.getId()));
        assertState(task, 2, null, null);
        assertThat(auditCount(task)).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE", "DEPT" })
    void groupCandidateUsesCurrentRoleOrDepartmentMembership(String group)
    {
        Task task = start(false, group + ("ROLE".equals(group) ? base + 22 : base));
        authenticate(2);
        actions.claim(new WorkflowTaskClaimRequest(task.getId()));
        assertState(task, 2, null, null);
        assertThat(candidateLinks(task)).contains("group:" + group + ("ROLE".equals(group) ? base + 22 : base));
        assertInbox(task, "TASK_CLAIMED", 2);
    }

    @Test
    void delegateAndResolvePreserveOwnerAndRestrictPreviousParticipantActions()
    {
        Task task = start(true, "");
        Set<String> before = candidateLinks(task);
        actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 2, " delegate ", List.of(base + 4)));
        assertState(task, 2, 1, DelegationState.PENDING);
        assertAudit(task, "DELEGATE", 1, 2);
        assertCopy(task, "MANUAL_DELEGATE", 1);
        assertInbox(task, "TASK_DELEGATED", 2);
        assertInbox(task, "COPY_CREATED", 4);
        error(() -> actions.resolve(new WorkflowTaskResolveRequest(task.getId(), "owner cannot resolve", List.of())), 403);
        authenticate(2);
        error(() -> actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 3, "nested", List.of())), 409);
        error(() -> actions.transfer(new WorkflowTaskTransferRequest(task.getId(), base + 3, "pending", List.of())), 409);
        actions.resolve(new WorkflowTaskResolveRequest(task.getId(), " resolved ", List.of(base + 4)));
        assertState(task, 1, 1, DelegationState.RESOLVED);
        assertAudit(task, "RESOLVE", 2, 1);
        assertCopy(task, "MANUAL_RESOLVE", 2);
        assertInbox(task, "TASK_DELEGATION_RESOLVED", 1);
        error(() -> actions.resolve(new WorkflowTaskResolveRequest(task.getId(), "late", List.of())), 403);
        assertThat(candidateLinks(task)).isEqualTo(before);
    }

    @Test
    void transferClearsClaimOriginAndRoundTripDoesNotRestoreUnclaimAbility()
    {
        Task task = start(false, "");
        Set<String> before = candidateLinks(task);
        actions.claim(new WorkflowTaskClaimRequest(task.getId()));
        actions.transfer(new WorkflowTaskTransferRequest(task.getId(), base + 3, "transfer", List.of(base + 4)));
        assertState(task, 3, null, null);
        assertThat(current(task).getClaimTime()).isNull();
        assertThat(current(task).getClaimedBy()).isNull();
        assertAudit(task, "TRANSFER", 1, 3);
        assertCopy(task, "MANUAL_TRANSFER", 1);
        assertInbox(task, "TASK_TRANSFERRED", 3);
        error(() -> actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 2, "old actor", List.of())), 403);
        error(() -> actions.unclaim(new WorkflowTaskUnclaimRequest(task.getId())), 403);
        authenticate(3);
        actions.transfer(new WorkflowTaskTransferRequest(task.getId(), base + 1, "back", List.of()));
        authenticate(1);
        error(() -> actions.unclaim(new WorkflowTaskUnclaimRequest(task.getId())), 409);
        assertThat(candidateLinks(task)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = { "DISABLED_TARGET", "REVOKED_TARGET", "DISABLED_ACTOR", "REVOKED_ACTOR", "REVOKED_OWNER" })
    void staleSessionCannotIgnoreCurrentIdentityAndOwnerEligibility(String change)
    {
        Task task = start(true, "");
        if ("REVOKED_OWNER".equals(change))
        {
            actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 2, "delegate", List.of()));
            revoke(1); authenticate(2);
            error(() -> actions.resolve(new WorkflowTaskResolveRequest(task.getId(), "resolve", List.of())), 409);
            assertState(task, 2, 1, DelegationState.PENDING);
            assertThat(auditCount(task)).isOne();
            return;
        }
        int changedUser = change.endsWith("ACTOR") ? 1 : 2;
        if (change.startsWith("DISABLED")) jdbc.update("update sys_user set status='1' where user_id=?", base + changedUser);
        else revoke(changedUser);
        int code = "DISABLED_ACTOR".equals(change) ? 403 : 400;
        error(() -> actions.transfer(new WorkflowTaskTransferRequest(task.getId(), base + 2, "move", List.of(base + 4))), code);
        assertState(task, 1, null, null);
        assertNoEffects(task);
    }

    @Test
    void missingSuspendedCompletedDirectAssignmentAndInvalidOpinionKeepDistinctFailures()
    {
        Task task = start(true, "");
        error(() -> actions.claim(new WorkflowTaskClaimRequest("missing")), 404);
        error(() -> actions.unclaim(new WorkflowTaskUnclaimRequest(task.getId())), 409);
        for (String comment : List.of(" ", "x".repeat(501)))
            error(() -> actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 2, comment, List.of())), 400);
        error(() -> actions.transfer(new WorkflowTaskTransferRequest(task.getId(), base + 1, "self", List.of())), 400);
        engine.processEngine().getRuntimeService().suspendProcessInstanceById(instance);
        error(() -> actions.transfer(new WorkflowTaskTransferRequest(task.getId(), base + 2, "suspended", List.of())), 409);
        engine.processEngine().getRuntimeService().activateProcessInstanceById(instance);
        assertNoEffects(task);
        tasks.complete(task.getId());
        error(() -> actions.claim(new WorkflowTaskClaimRequest(task.getId())), 404);
    }

    static Stream<Arguments> failures()
    {
        return Stream.of("CLAIM", "UNCLAIM", "DELEGATE", "RESOLVE", "TRANSFER")
                .flatMap(action -> (Set.of("CLAIM", "UNCLAIM").contains(action) ? Stream.of("INBOX") : Stream.of("COPY", "INBOX"))
                        .map(stage -> Arguments.of(action, stage)));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void databaseFailureRollsBackEngineAuditCopyAndNotification(String action, String stage)
    {
        Task task = start(false, "");
        if (!"CLAIM".equals(action)) actions.claim(new WorkflowTaskClaimRequest(task.getId()));
        if ("RESOLVE".equals(action))
        {
            actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 2, "prepare", List.of()));
            authenticate(2);
        }
        Task before = current(task);
        Set<String> links = candidateLinks(task);
        int comments = auditCount(task);
        int inbox = count("wf_notification_inbox", "process_instance_id");
        if ("COPY".equals(stage)) doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("after copy persistence"); }).when(copies).insertBatch(anyList());
        else doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("after Inbox persistence"); }).when(writer).write(any());
        assertThatThrownBy(() -> invoke(action, task)).hasMessageContaining("after ");
        Task after = current(task);
        assertThat(after.getAssignee()).isEqualTo(before.getAssignee());
        assertThat(after.getOwner()).isEqualTo(before.getOwner());
        assertThat(after.getDelegationState()).isEqualTo(before.getDelegationState());
        assertThat(after.getClaimedBy()).isEqualTo(before.getClaimedBy());
        assertThat(after.getClaimTime()).isEqualTo(before.getClaimTime());
        assertThat(candidateLinks(task)).isEqualTo(links);
        assertThat(auditCount(task)).isEqualTo(comments);
        assertThat(count("wf_copy", "instance_id")).isZero();
        assertThat(count("wf_notification_inbox", "process_instance_id")).isEqualTo(inbox);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
    void mysqlConcurrentClaimHasOneWinnerAndOneCompleteSetOfEffects() throws Exception
    {
        Task task = start(false, "");
        var barrier = new CyclicBarrier(2);
        doAnswer(call -> { barrier.await(10, TimeUnit.SECONDS); return call.callRealMethod(); }).when(tasks).claim(eq(task.getId()), anyString());
        var pool = Executors.newFixedThreadPool(2);
        try
        {
            var first = pool.submit(() -> claimResult(task, 1));
            var second = pool.submit(() -> claimResult(task, 2));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
            assertThat(auditCount(task)).isOne();
            assertThat(count("wf_notification_inbox", "process_instance_id")).isOne();
            assertThat(jdbc.queryForObject("select cast(recipient_user_id as char) from wf_notification_inbox where process_instance_id=?", String.class, instance))
                    .isEqualTo(current(task).getAssignee());
        }
        finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
    void mysqlCommittedRevocationWinsBeforeConcurrentActionAdmission() throws Exception
    {
        Task task = start(true, "");
        var changed = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try
        {
            var rejected = pool.submit(() -> {
                authenticate(1); // Keep a session issued before the other transaction revokes the target.
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                error(() -> actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 2, "race", List.of(base + 4))), 400);
                SecurityContextHolder.clearContext();
                return true;
            });
            engine.transactionTemplate().executeWithoutResult(status -> revoke(2));
            changed.countDown();
            assertThat(rejected.get(20, TimeUnit.SECONDS)).isTrue();
            assertState(task, 1, null, null);
            assertNoEffects(task);
        }
        finally { changed.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    private int claimResult(Task task, int user)
    {
        authenticate(user);
        try { actions.claim(new WorkflowTaskClaimRequest(task.getId())); return 200; }
        catch (ServiceException failure) { return failure.getCode(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void invoke(String action, Task task)
    {
        switch (action)
        {
            case "CLAIM" -> actions.claim(new WorkflowTaskClaimRequest(task.getId()));
            case "UNCLAIM" -> actions.unclaim(new WorkflowTaskUnclaimRequest(task.getId()));
            case "DELEGATE" -> actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), base + 2, "delegate", List.of(base + 4)));
            case "RESOLVE" -> actions.resolve(new WorkflowTaskResolveRequest(task.getId(), "resolve", List.of(base + 4)));
            case "TRANSFER" -> actions.transfer(new WorkflowTaskTransferRequest(task.getId(), base + 3, "transfer", List.of(base + 4)));
            default -> throw new AssertionError(action);
        }
    }

    private Task start(boolean assigned, String group)
    {
        processKey = "action_" + UUID.randomUUID().toString().replace("-", "");
        String assignment = assigned ? "flowable:assignee='" + id(1) + "'" : "";
        String candidates = group.isEmpty() ? "flowable:candidateUsers='" + id(1) + "," + id(2) + "'" : "flowable:candidateGroups='" + group + "'";
        deployment = engine.processEngine().getRepositoryService().createDeployment().addString("action.bpmn20.xml", """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="action">
            <process id="%s" name="Task action" isExecutable="true"><startEvent id="start"/>
            <userTask id="review" name="Review" %s %s/><endEvent id="end"/>
            <sequenceFlow id="a" sourceRef="start" targetRef="review"/><sequenceFlow id="b" sourceRef="review" targetRef="end"/>
            </process></definitions>
            """.formatted(processKey, assignment, candidates)).deploy().getId();
        for (String event : List.of("TASK_CLAIMED", "TASK_UNCLAIMED", "TASK_DELEGATED", "TASK_DELEGATION_RESOLVED", "TASK_TRANSFERRED", "COPY_CREATED"))
            jdbc.update("insert into wf_notification_policy(scope_type,process_definition_key,event_type,recipient_rules,channels,title_template,content_template,max_attempts,status) values ('PROCESS',?,?,'TASK_RECIPIENT','INBOX','Task','Action',3,'ENABLED')", processKey, event);
        instance = operations.writeAsCurrentUser(actor -> engine.processEngine().getRuntimeService().startProcessInstanceByKey(processKey)).getId();
        return tasks.createTaskQuery().processInstanceId(instance).singleResult();
    }

    private void seedIdentity()
    {
        jdbc.update("insert into sys_dept(dept_id,dept_name,status,del_flag) values (?,'Task department','0','0')", base);
        List<String> permissions = List.of("todoList", "query", "approval", "claimList", "claim");
        for (int p = 1; p <= permissions.size(); p++) jdbc.update("insert into sys_menu(menu_id,menu_name,perms,menu_type,status) values (?,'Task contract',?,'F','0')", base + 40 + p, "workflow:process:" + permissions.get(p - 1));
        for (int user = 1; user <= 4; user++)
        {
            jdbc.update("insert into sys_user(user_id,dept_id,user_name,nick_name,status,del_flag) values (?,?,?,?,'0','0')", base + user, base, "action-" + (base + user), "Task " + user);
            jdbc.update("insert into sys_role(role_id,role_name,role_key,role_sort,status,del_flag) values (?,'Task role',?,1,'0','0')", base + 20 + user, "action-" + (base + user));
            jdbc.update("insert into sys_user_role(user_id,role_id) values (?,?)", base + user, base + 20 + user);
            for (int p = 1; p <= permissions.size(); p++) jdbc.update("insert into sys_role_menu(role_id,menu_id) values (?,?)", base + 20 + user, base + 40 + p);
        }
    }

    private void revoke(int user) { jdbc.update("delete from sys_role_menu where role_id=? and menu_id=?", base + 20 + user, base + 43); }
    private String id(int user) { return Long.toString(base + user); }
    private void authenticate(int user)
    {
        var identity = new SysUser(); identity.setUserId(base + user);
        var login = new LoginUser(base + user, base, identity, Set.of("workflow:process:approval"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }
    private <T> T mapper(Class<T> type) { return WorkflowH2SchemaMapperSupport.createSpringMapper(engine.dataSource(), "task-action", type, "mapper/flowable/" + type.getSimpleName() + ".xml"); }
    private Task current(Task task) { return tasks.createTaskQuery().taskId(task.getId()).singleResult(); }
    private Set<String> candidateLinks(Task task)
    {
        return tasks.getIdentityLinksForTask(task.getId()).stream().filter(link -> "candidate".equals(link.getType()))
                .map(link -> link.getUserId() == null ? "group:" + link.getGroupId() : "user:" + link.getUserId()).collect(java.util.stream.Collectors.toSet());
    }
    private void assertState(Task task, Integer assignee, Integer owner, DelegationState delegation)
    {
        Task stored = current(task);
        assertThat(stored.getAssignee()).isEqualTo(assignee == null ? null : id(assignee));
        assertThat(stored.getOwner()).isEqualTo(owner == null ? null : id(owner));
        assertThat(stored.getDelegationState()).isEqualTo(delegation);
    }
    private void assertAudit(Task task, String action, int actor, Integer target)
    {
        var comment = tasks.getTaskComments(task.getId(), action).stream().findFirst().orElseThrow();
        assertThat(comment.getUserId()).isEqualTo(id(actor));
        var json = tools.jackson.databind.json.JsonMapper.shared().readTree(comment.getFullMessage());
        assertThat(json.get("actorUserId").asText()).isEqualTo(id(actor));
        if (target != null) assertThat(json.get("targetUserId").asText()).isEqualTo(id(target));
    }
    private void assertCopy(Task task, String trigger, int actor)
    {
        var rows = jdbc.queryForList("select user_id, create_by, task_id, source_type from wf_copy where instance_id=? and trigger_type=?", instance, trigger);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("user_id")).longValue()).isEqualTo(base + 4);
        assertThat(rows.get(0)).containsEntry("create_by", id(actor)).containsEntry("task_id", task.getId()).containsEntry("source_type", "MANUAL");
    }
    private void assertInbox(Task task, String event, Integer... recipients)
    {
        assertThat(jdbc.queryForList("select recipient_user_id from wf_notification_inbox where process_instance_id=? and event_type=?", Long.class, instance, event))
                .containsExactlyInAnyOrder(java.util.Arrays.stream(recipients).map(user -> base + user).toArray(Long[]::new));
    }
    private int auditCount(Task task) { return jdbc.queryForObject("select count(*) from ACT_HI_COMMENT where TASK_ID_=? and TYPE_ in ('CLAIM','UNCLAIM','DELEGATE','RESOLVE','TRANSFER')", Integer.class, task.getId()); }
    private int count(String table, String column) { return jdbc.queryForObject("select count(*) from " + table + " where " + column + "=?", Integer.class, instance); }
    private void assertNoEffects(Task task) { assertThat(auditCount(task)).isZero(); assertThat(count("wf_copy", "instance_id")).isZero(); assertThat(count("wf_notification_inbox", "process_instance_id")).isZero(); }
    private static void error(Runnable action, int code) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(code)); }
    public static String sha2(String value, int bits) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-" + bits).digest(value.getBytes(StandardCharsets.UTF_8))); }
}
