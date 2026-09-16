package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowTaskClaimRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskDelegateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskResolveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskTransferRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskUnclaimRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WorkflowRuntimeTaskMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.system.mapper.SysUserMapper;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 使用真实 Flowable、Spring 事务和抄送 Mapper 验证五种任务动作及失败回滚。
 */
class WorkflowTaskActionServiceIntegrationTest
{
    /** 真实认领用户，也是默认服务端当前身份。 */
    private static final String CLAIM_USER_ID = "100";

    private WorkflowFlowableEngineTestSupport support;
    private ProcessEngine processEngine;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowTaskCopyService copyService;
    private WfCopyMapper copyMapper;
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private TaskService taskService;
    private AtomicReference<WorkflowCurrentIdentity> currentIdentity;
    private WorkflowNotificationService notificationService;
    private WorkflowTaskActionService service;

    /**
     * 创建真实内存 Flowable 引擎，并使用共享 Spring 事务装配任务动作服务。
     *
     * @return void，无返回值；每个测试使用独立 H2 数据库
     */
    @BeforeEach
    void setUp()
    {
        support = WorkflowFlowableEngineTestSupport.start("task-actions", Map.of());
        processEngine = support.processEngine();
        repositoryService = processEngine.getRepositoryService();
        runtimeService = processEngine.getRuntimeService();
        taskService = processEngine.getTaskService();

        currentIdentity = new AtomicReference<>(
                new WorkflowCurrentIdentity(CLAIM_USER_ID, Set.of()));
        identityResolver = mock(WorkflowIdentityResolver.class);
        when(identityResolver.resolveCurrentIdentity()).thenAnswer(invocation -> currentIdentity.get());
        when(identityResolver.resolveApprovalEligibleUserIds(anyCollection())).thenAnswer(invocation ->
                new LinkedHashSet<>((Collection<String>) invocation.getArgument(0)));
        when(identityResolver.resolveClaimEligibleUserIds(anyCollection())).thenReturn(Set.of(CLAIM_USER_ID));
        when(identityResolver.resolveActiveUserIds(anyCollection(), anyCollection())).thenAnswer(invocation ->
                new LinkedHashSet<>((Collection<String>) invocation.getArgument(0)));
        WorkflowEngineOperations engineOperations = support.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(processEngine.getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identityResolver));
        notificationService = mock(WorkflowNotificationService.class);
        WorkflowH2SchemaMapperSupport.executeSchema(support.dataSource(), "db/h2/wf_copy.sql");
        copyMapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), "task-copy",
                WfCopyMapper.class, "mapper/flowable/WfCopyMapper.xml"));
        WorkflowRuntimeTaskMapper revisions = WorkflowH2SchemaMapperSupport.createSpringMapper(
                support.dataSource(), "task-revision", WorkflowRuntimeTaskMapper.class,
                "mapper/flowable/WorkflowRuntimeTaskMapper.xml");
        copyService = new WorkflowTaskCopyService(new WorkflowUserSelectionValidator(identityResolver),
                copyMapper, revisions, repositoryService, runtimeService, mock(SysUserMapper.class), notificationService);
        service = new WorkflowTaskActionService(repositoryService, runtimeService,
                taskService, engineOperations, identityResolver, notificationService,
                new WorkflowTaskUnclaimPolicy(), copyService);
    }

    /**
     * 关闭真实 Flowable 引擎，避免测试之间共享运行时和历史状态。
     *
     * @return void，无返回值
     */
    @AfterEach
    void tearDown()
    {
        if (processEngine != null)
        {
            support.close();
        }
    }

    /**
     * 验证真实 claim 任务可以取消认领，并保持原审计 comment 与事件行为。
     *
     * @return void，状态、审计或 TASK_UNCLAIMED 事件任一缺失时测试失败
     */
    @Test
    void unclaimsClaimedTaskAndKeepsAuditAndEvent()
    {
        Task task = startTask("successfulUnclaim", "flowable:candidateUsers=\"100\"");
        taskService.claim(task.getId(), CLAIM_USER_ID);
        Task claimedTask = taskService.createTaskQuery().taskId(task.getId()).singleResult();
        assertThat(claimedTask.getClaimedBy()).isEqualTo(CLAIM_USER_ID);
        assertThat(claimedTask.getClaimTime()).isNotNull();

        service.unclaim(new WorkflowTaskUnclaimRequest(task.getId()));

        Task unclaimedTask = taskService.createTaskQuery().taskId(task.getId()).singleResult();
        assertThat(unclaimedTask.getAssignee()).isNull();
        assertThat(unclaimedTask.getClaimedBy()).isNull();
        assertThat(unclaimedTask.getClaimTime()).isNull();
        List<Comment> comments = taskService.getTaskComments(task.getId(), "UNCLAIM");
        assertThat(comments).singleElement().satisfies(comment ->
        {
            assertThat(comment.getType()).isEqualTo("UNCLAIM");
            assertThat(comment.getFullMessage()).contains("\"action\":\"UNCLAIM\"")
                    .contains("\"actorUserId\":\"100\"")
                    .contains("用户取消认领任务");
        });
        verify(notificationService).onStableTaskAction("TASK_UNCLAIMED", task.getId());
    }

    /**
     * 验证直接指派、委派和认领身份漂移继续返回稳定 409。
     *
     * @return void，任一非法认领来源被写入端接受时测试失败
     */
    @Test
    void rejectsDirectAssignmentDelegationAndClaimDrift()
    {
        Task directTask = startTask("directAssignment", "flowable:assignee=\"100\"");
        assertStatus(HttpStatus.CONFLICT,
                () -> service.unclaim(new WorkflowTaskUnclaimRequest(directTask.getId())));

        Task delegatedTask = startTask("delegatedAssignment", "flowable:assignee=\"100\"");
        taskService.delegateTask(delegatedTask.getId(), "200");
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.unclaim(new WorkflowTaskUnclaimRequest(delegatedTask.getId())));

        Task driftedTask = startTask("claimDrift", "flowable:candidateUsers=\"100\"");
        taskService.claim(driftedTask.getId(), CLAIM_USER_ID);
        taskService.setAssignee(driftedTask.getId(), "200");
        assertStatus(HttpStatus.CONFLICT,
                () -> service.unclaim(new WorkflowTaskUnclaimRequest(driftedTask.getId())));
        verifyNoInteractions(notificationService);
    }

    /**
     * 验证非当前 assignee 返回 403，任务不存在返回 404。
     *
     * @return void，原有身份与对象不存在异常合同发生漂移时测试失败
     */
    @Test
    void keepsForbiddenAndNotFoundContracts()
    {
        Task task = startTask("foreignAssignee", "flowable:candidateUsers=\"100\"");
        taskService.claim(task.getId(), CLAIM_USER_ID);
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));

        assertStatus(HttpStatus.FORBIDDEN,
                () -> service.unclaim(new WorkflowTaskUnclaimRequest(task.getId())));
        assertStatus(HttpStatus.NOT_FOUND,
                () -> service.unclaim(new WorkflowTaskUnclaimRequest("missing-task")));
        verifyNoInteractions(notificationService);
    }

    /**
     * 验证流程或任务挂起时写入端重新查询并返回 409。
     *
     * @return void，挂起状态绕过最终活动态门禁时测试失败
     */
    @Test
    void rejectsSuspendedProcessOrTask()
    {
        Task processSuspendedTask = startTask(
                "processSuspended", "flowable:candidateUsers=\"100\"");
        taskService.claim(processSuspendedTask.getId(), CLAIM_USER_ID);
        runtimeService.suspendProcessInstanceById(processSuspendedTask.getProcessInstanceId());
        assertStatus(HttpStatus.CONFLICT,
                () -> service.unclaim(new WorkflowTaskUnclaimRequest(processSuspendedTask.getId())));

        Task taskSuspended = startTask(
                "taskSuspended", "flowable:candidateUsers=\"100\"");
        taskService.claim(taskSuspended.getId(), CLAIM_USER_ID);
        taskService.suspendTask(taskSuspended.getId(), CLAIM_USER_ID);
        assertStatus(HttpStatus.CONFLICT,
                () -> service.unclaim(new WorkflowTaskUnclaimRequest(taskSuspended.getId())));
        verifyNoInteractions(notificationService);
    }

    @Test
    void claimsOnlyCurrentEligibleCandidatesAndRejectsRepeatedOrCompletedCommands()
    {
        Task task = startTask("claimAction", "flowable:candidateUsers=\"100\"");
        when(identityResolver.resolveClaimEligibleUserIds(anyCollection())).thenReturn(Set.of());
        assertStatus(HttpStatus.FORBIDDEN, () -> service.claim(new WorkflowTaskClaimRequest(task.getId())));
        assertThat(reload(task).getAssignee()).isNull();
        when(identityResolver.resolveClaimEligibleUserIds(anyCollection())).thenReturn(Set.of(CLAIM_USER_ID));
        service.claim(new WorkflowTaskClaimRequest(task.getId()));
        assertThat(reload(task).getAssignee()).isEqualTo(CLAIM_USER_ID);
        assertThat(taskService.getTaskComments(task.getId(), "CLAIM")).hasSize(1);
        assertStatus(HttpStatus.CONFLICT, () -> service.claim(new WorkflowTaskClaimRequest(task.getId())));
        service.unclaim(new WorkflowTaskUnclaimRequest(task.getId()));
        assertStatus(HttpStatus.FORBIDDEN, () -> service.unclaim(new WorkflowTaskUnclaimRequest(task.getId())));
        taskService.complete(task.getId());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.claim(new WorkflowTaskClaimRequest(task.getId())));
    }

    @Test
    void delegationAndResolutionKeepOwnerAuditAndRealCopyRows()
    {
        Task task = startTask("delegateAction", "flowable:assignee=\"100\"");
        service.delegate(new WorkflowTaskDelegateRequest(task.getId(), 200L, " 请协助 ", List.of(300L)));
        assertThat(reload(task).getAssignee()).isEqualTo("200");
        assertThat(reload(task).getOwner()).isEqualTo("100");
        assertThat(reload(task).getDelegationState()).isEqualTo(DelegationState.PENDING);
        assertStatus(HttpStatus.FORBIDDEN,
                () -> service.resolve(new WorkflowTaskResolveRequest(task.getId(), "已办", List.of())));
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));
        service.resolve(new WorkflowTaskResolveRequest(task.getId(), " 已核实 ", List.of(300L)));
        assertThat(reload(task).getAssignee()).isEqualTo("100");
        assertThat(reload(task).getDelegationState()).isEqualTo(DelegationState.RESOLVED);
        assertThat(taskService.getTaskComments(task.getId(), "DELEGATE")).singleElement()
                .satisfies(comment -> assertThat(comment.getFullMessage()).contains("请协助"));
        assertThat(taskService.getTaskComments(task.getId(), "RESOLVE")).singleElement()
                .satisfies(comment -> assertThat(comment.getFullMessage()).contains("已核实"));
        assertThat(copyCount()).isEqualTo(2);
        assertThat(support.jdbcTemplate().queryForList("select trigger_type from wf_copy order by copy_id", String.class))
                .containsExactly("MANUAL_DELEGATE", "MANUAL_RESOLVE");
        currentIdentity.set(new WorkflowCurrentIdentity("100", Set.of()));
        assertStatus(HttpStatus.CONFLICT,
                () -> service.resolve(new WorkflowTaskResolveRequest(task.getId(), "重复", List.of())));
    }

    @Test
    void transferClearsClaimOriginAndCannotReviveUnclaimWhenTransferredBack()
    {
        Task task = startTask("transferAction", "flowable:candidateUsers=\"100\"");
        service.claim(new WorkflowTaskClaimRequest(task.getId()));
        service.transfer(new WorkflowTaskTransferRequest(task.getId(), 200L, "转交", List.of(300L)));
        assertThat(reload(task).getAssignee()).isEqualTo("200");
        assertThat(reload(task).getClaimedBy()).isNull();
        assertThat(reload(task).getClaimTime()).isNull();
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));
        service.transfer(new WorkflowTaskTransferRequest(task.getId(), 100L, "转回", List.of()));
        currentIdentity.set(new WorkflowCurrentIdentity("100", Set.of()));
        assertStatus(HttpStatus.CONFLICT, () -> service.unclaim(new WorkflowTaskUnclaimRequest(task.getId())));
        assertThat(copyCount()).isEqualTo(1);
        assertThat(taskService.getTaskComments(task.getId(), "TRANSFER")).hasSize(2);
    }

    @Test
    void rejectsRevokedTargetOrOwnerBeforeMutatingTask()
    {
        Task task = startTask("revokedAssignment", "flowable:assignee=\"100\"");
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("200"))).thenReturn(Set.of());
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.delegate(new WorkflowTaskDelegateRequest(task.getId(), 200L, "委派", List.of())));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> service.transfer(new WorkflowTaskTransferRequest(task.getId(), 200L, "转交", List.of())));
        assertThat(reload(task).getAssignee()).isEqualTo("100");
        taskService.delegateTask(task.getId(), "200");
        currentIdentity.set(new WorkflowCurrentIdentity("200", Set.of()));
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("200"))).thenReturn(Set.of("200"));
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("100"))).thenReturn(Set.of());
        assertStatus(HttpStatus.CONFLICT,
                () -> service.resolve(new WorkflowTaskResolveRequest(task.getId(), "已办", List.of())));
        assertThat(reload(task).getDelegationState()).isEqualTo(DelegationState.PENDING);
        assertThat(copyCount()).isZero();
        verifyNoInteractions(notificationService);
    }

    @Test
    void copyFailureRollsBackDelegationAndAudit()
    {
        Task task = startTask("copyRollback", "flowable:assignee=\"100\"");
        IllegalStateException failure = new IllegalStateException("copy insert failed");
        doThrow(failure).when(copyMapper).insertBatch(anyList());
        assertThatThrownBy(() -> service.delegate(
                new WorkflowTaskDelegateRequest(task.getId(), 200L, "委派", List.of(300L)))).isSameAs(failure);
        assertThat(reload(task).getAssignee()).isEqualTo("100");
        assertThat(reload(task).getOwner()).isNull();
        assertThat(taskService.getTaskComments(task.getId())).isEmpty();
        assertThat(copyCount()).isZero();
        verifyNoInteractions(notificationService);
    }

    @Test
    void notificationFailureRollsBackTransferCopyAndClaimMetadata()
    {
        Task task = startTask("notificationRollback", "flowable:candidateUsers=\"100\"");
        service.claim(new WorkflowTaskClaimRequest(task.getId()));
        Task before = reload(task);
        IllegalStateException failure = new IllegalStateException("notification failed");
        doThrow(failure).when(notificationService).onStableTaskAction("TASK_TRANSFERRED", task.getId());
        assertThatThrownBy(() -> service.transfer(
                new WorkflowTaskTransferRequest(task.getId(), 200L, "转交", List.of(300L)))).isSameAs(failure);
        Task after = reload(task);
        assertThat(after.getAssignee()).isEqualTo(before.getAssignee());
        assertThat(after.getClaimedBy()).isEqualTo(before.getClaimedBy());
        assertThat(after.getClaimTime()).isEqualTo(before.getClaimTime());
        assertThat(taskService.getTaskComments(task.getId(), "TRANSFER")).isEmpty();
        assertThat(copyCount()).isZero();
        verify(notificationService).onCopiesCreated(anyList());
    }

    @Test
    void everyActionKeepsSuspendedConflictAndDeletedTaskNotFound()
    {
        Task task = startTask("actionLifecycle", "flowable:candidateGroups=\"ROLE9\"");
        currentIdentity.set(new WorkflowCurrentIdentity("100", Set.of("ROLE9")));
        service.claim(new WorkflowTaskClaimRequest(task.getId()));
        assertThat(reload(task).getAssignee()).isEqualTo("100");
        List<Runnable> actions = List.of(
                () -> service.claim(new WorkflowTaskClaimRequest(task.getId())),
                () -> service.unclaim(new WorkflowTaskUnclaimRequest(task.getId())),
                () -> service.delegate(new WorkflowTaskDelegateRequest(task.getId(), 200L, "委派", List.of())),
                () -> service.resolve(new WorkflowTaskResolveRequest(task.getId(), "办结", List.of())),
                () -> service.transfer(new WorkflowTaskTransferRequest(task.getId(), 200L, "转办", List.of())));
        runtimeService.suspendProcessInstanceById(task.getProcessInstanceId());
        actions.forEach(action -> assertStatus(HttpStatus.CONFLICT, action));
        runtimeService.activateProcessInstanceById(task.getProcessInstanceId());
        taskService.complete(task.getId());
        actions.forEach(action -> assertStatus(HttpStatus.NOT_FOUND, action));
    }

    private Task reload(Task task)
    {
        return taskService.createTaskQuery().taskId(task.getId()).singleResult();
    }

    private int copyCount()
    {
        return support.jdbcTemplate().queryForObject("select count(*) from wf_copy", Integer.class);
    }

    /**
     * 启动一个带受控任务分配属性的单任务流程并返回唯一任务。
     *
     * @param processKey String，唯一流程定义标识
     * @param assignment String，用户任务的 Flowable assignee 或 candidateUsers 属性
     * @return Task，真实运行时任务
     */
    private Task startTask(String processKey, String assignment)
    {
        repositoryService.createDeployment()
                .addString(processKey + ".bpmn20.xml", bpmn(processKey, assignment))
                .deploy();
        String processInstanceId;
        processEngine.getIdentityService().setAuthenticatedUserId(CLAIM_USER_ID);
        try
        {
            processInstanceId = runtimeService.startProcessInstanceByKey(processKey).getId();
        }
        finally
        {
            processEngine.getIdentityService().setAuthenticatedUserId(null);
        }
        Task task = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).singleResult();
        assertThat(task).isNotNull();
        return task;
    }

    /**
     * 断言任务动作抛出指定稳定 HTTP 业务码。
     *
     * @param expectedStatus int，期望的 HTTP 业务状态码
     * @param action Runnable，待执行的取消认领命令
     * @return void，未抛异常或状态码不一致时测试失败
     */
    private void assertStatus(int expectedStatus, Runnable action)
    {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(ServiceException.class);
        assertThat(((ServiceException) failure).getCode()).isEqualTo(expectedStatus);
    }

    /**
     * 生成可由 Flowable 8 部署执行的单任务 BPMN。
     *
     * @param processKey String，流程定义标识
     * @param assignment String，用户任务分配属性
     * @return String，UTF-8 BPMN XML
     */
    private String bpmn(String processKey, String assignment)
    {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:flowable="http://flowable.org/bpmn"
                  targetNamespace="urn:test:task-actions">
                  <process id="%s" name="取消认领写入" isExecutable="true">
                    <startEvent id="start"/>
                    <userTask id="approval" name="审批" %s/>
                    <endEvent id="end"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="approval"/>
                    <sequenceFlow id="flow2" sourceRef="approval" targetRef="end"/>
                  </process>
                </definitions>
                """.formatted(processKey, assignment);
    }
}
