package com.ruoyi.flowable.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

class WorkflowProcessEngineAdapterTest
{
    private static final String TASK_ID = "task-1";

    private RepositoryService repositoryService;

    private RuntimeService runtimeService;

    private TaskService taskService;

    private WorkflowIdentityResolver identityResolver;

    private WorkflowNotificationService notificationService;

    private WorkflowProcessEngineAdapter adapter;

    /**
     * 为每个测试创建仅依赖 Flowable 公共 API 的适配器和服务替身。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        // 单元测试使用公共 API 替身，显式绑定生产写边界要求的可重复读事务特征。
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        taskService = mock(TaskService.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        notificationService = mock(WorkflowNotificationService.class);
        IdentityService identityService = mock(IdentityService.class);
        WorkflowAuthenticationContext authenticationContext = new WorkflowAuthenticationContext(
                identityService, new WorkflowIdentityCodec());
        WorkflowEngineOperations engineOperations = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        adapter = new WorkflowProcessEngineAdapter(
                repositoryService, runtimeService, taskService, engineOperations, identityResolver);
        adapter.setNotificationService(notificationService);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2", "DEPT3")));
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("7")))
                .thenReturn(Set.of("7"));
        when(identityResolver.resolveClaimEligibleUserIds(List.of("7")))
                .thenReturn(Set.of("7"));
    }

    /**
     * 清理模拟事务特征，避免隔离级别在线程复用时泄漏。
     *
     * @return 无返回值
     */
    @AfterEach
    void clearTransactionCharacteristics()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证活动流程和任务查询使用 active 条件，并只向业务层返回不可变模块快照。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void findsActiveProcessAndTaskAsImmutableSnapshots()
    {
        ProcessInstanceQuery processQuery = mock(ProcessInstanceQuery.class);
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processQuery);
        when(processQuery.processInstanceId("process-1")).thenReturn(processQuery);
        when(processQuery.active()).thenReturn(processQuery);
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processInstance.getId()).thenReturn("process-1");
        when(processInstance.getProcessDefinitionId()).thenReturn("definition-1");
        when(processInstance.getBusinessKey()).thenReturn("expense:42");

        TaskQuery taskQuery = mock(TaskQuery.class);
        Task task = mock(Task.class);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(TASK_ID)).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.singleResult()).thenReturn(task);
        when(task.getId()).thenReturn(TASK_ID);
        when(task.getName()).thenReturn("部门审批");
        when(task.getProcessInstanceId()).thenReturn("process-1");
        when(task.getTaskDefinitionKey()).thenReturn("deptApprove");
        when(task.getAssignee()).thenReturn("7");
        when(task.getClaimedBy()).thenReturn("7");
        Instant claimTime = Instant.parse("2026-07-25T08:30:00Z");
        when(task.getClaimTime()).thenReturn(Date.from(claimTime));
        when(task.getOwner()).thenReturn("6");
        when(task.getDelegationState()).thenReturn(DelegationState.RESOLVED);

        assertThat(adapter.findActiveProcessInstance("process-1"))
                .isEqualTo(Optional.of(new WorkflowProcessInstanceSnapshot(
                        "process-1", "definition-1", "expense:42", false)));
        assertThat(adapter.findActiveTask(TASK_ID))
                .isEqualTo(Optional.of(new WorkflowTaskSnapshot(TASK_ID, "部门审批", "process-1",
                        "deptApprove", "7", "7", claimTime, "6", "RESOLVED", false)));
        verify(processQuery).active();
        verify(taskQuery).active();
        verifyNoInteractions(identityResolver);
    }

    /**
     * 验证当前用户作为直接 candidate 时可以认领活动且未认领的任务。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void claimsTaskForDirectCandidateUser()
    {
        stubActiveTask(TASK_ID, null, null);
        IdentityLink directCandidate = identityLink(IdentityLinkType.CANDIDATE, "7", null);
        when(taskService.getIdentityLinksForTask(TASK_ID))
                .thenReturn(List.of(directCandidate));

        adapter.claimTaskForCurrentUser(TASK_ID);

        verify(taskService).claim(TASK_ID, "7");
        assertAuditComment("1", "CLAIM", null, "用户认领任务");
        verify(notificationService).onStableTaskAction("TASK_CLAIMED", TASK_ID);
    }

    /**
     * 验证当前用户缺少完整认领资格时，在读取 Flowable 任务前即以 403 拒绝认领。
     *
     * @return 无返回值；资格门禁失效或引擎被提前访问时测试失败
     */
    @Test
    void rejectsClaimBeforeEngineAccessWhenCurrentUserIsNotClaimEligible()
    {
        when(identityResolver.resolveClaimEligibleUserIds(List.of("7")))
                .thenReturn(Set.of());

        assertBusinessError(() -> adapter.claimTaskForCurrentUser(TASK_ID),
                HttpStatus.FORBIDDEN, WorkflowExceptionTranslator.FORBIDDEN_MESSAGE);

        // claim 资格是写命令的最前置门禁，失败后不得泄露任务存在性或触碰候选身份。
        verifyNoInteractions(taskService, runtimeService, repositoryService);
    }

    /**
     * 验证当前用户所属 ROLE 或 DEPT candidate 组均可以授权认领。
     *
     * @param candidateGroup String，任务声明的候选组标识
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = { "ROLE2", "DEPT3" })
    void claimsTaskForCurrentCandidateGroup(String candidateGroup)
    {
        stubActiveTask(TASK_ID, null, null);
        IdentityLink groupCandidate = identityLink(IdentityLinkType.CANDIDATE, null, candidateGroup);
        when(taskService.getIdentityLinksForTask(TASK_ID))
                .thenReturn(List.of(groupCandidate));

        adapter.claimTaskForCurrentUser(TASK_ID);

        verify(taskService).claim(TASK_ID, "7");
    }

    /**
     * 验证 participant 身份、其他直接用户和其他候选组均不能冒充 candidate 认领任务。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsClaimForNonCandidateWithForbiddenStatus()
    {
        stubActiveTask(TASK_ID, null, null);
        IdentityLink participant = identityLink(IdentityLinkType.PARTICIPANT, "7", null);
        IdentityLink otherUser = identityLink(IdentityLinkType.CANDIDATE, "8", null);
        IdentityLink otherGroup = identityLink(IdentityLinkType.CANDIDATE, null, "ROLE9");
        when(taskService.getIdentityLinksForTask(TASK_ID))
                .thenReturn(List.of(participant, otherUser, otherGroup));

        assertBusinessError(() -> adapter.claimTaskForCurrentUser(TASK_ID),
                HttpStatus.FORBIDDEN, WorkflowExceptionTranslator.FORBIDDEN_MESSAGE);

        verify(taskService, never()).claim(any(), any());
    }

    /**
     * 验证只要任务已有 assignee，即使是当前用户也不能重复走认领命令。
     *
     * @param assignee String，已有办理人或非法空白办理人状态
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = { "7", "8", " " })
    void rejectsClaimForAlreadyAssignedTask(String assignee)
    {
        stubActiveTask(TASK_ID, assignee, null);

        assertBusinessError(() -> adapter.claimTaskForCurrentUser(TASK_ID),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).getIdentityLinksForTask(TASK_ID);
        verify(taskService, never()).claim(any(), any());
    }

    /**
     * 验证带委派状态的异常未认领任务不会绕过标准委派流转后被认领。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsClaimForDelegatedTask()
    {
        stubActiveTask(TASK_ID, null, DelegationState.PENDING);

        assertBusinessError(() -> adapter.claimTaskForCurrentUser(TASK_ID),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).getIdentityLinksForTask(TASK_ID);
        verify(taskService, never()).claim(any(), any());
    }

    /**
     * 验证当前 assignee 可以取消普通活动任务的认领。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void unclaimsTaskOnlyForCurrentAssignee()
    {
        Task task = stubActiveTask(TASK_ID, "7", null);
        when(task.getClaimedBy()).thenReturn("7");
        when(task.getClaimTime()).thenReturn(new Date());

        adapter.unclaimTaskForCurrentUser(TASK_ID);

        verify(taskService).unclaim(TASK_ID);
        assertAuditComment("1", "UNCLAIM", null, "用户取消认领任务");
        verify(notificationService).onStableTaskAction("TASK_UNCLAIMED", TASK_ID);
    }

    /**
     * 验证未认领任务或由其他用户办理的任务均拒绝当前用户取消认领。
     *
     * @param assignee String，null 表示未认领，其他值表示其他办理人
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "8" })
    void rejectsUnclaimForNonAssignee(String assignee)
    {
        stubActiveTask(TASK_ID, assignee, null);

        assertBusinessError(() -> adapter.unclaimTaskForCurrentUser(TASK_ID),
                HttpStatus.FORBIDDEN, WorkflowExceptionTranslator.FORBIDDEN_MESSAGE);

        verify(taskService, never()).unclaim(any());
    }

    /**
     * 验证任何委派态都禁止取消认领，避免破坏 owner 与 assignee 委派关系。
     *
     * @param delegationState DelegationState，PENDING 或 RESOLVED 委派状态
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @EnumSource(DelegationState.class)
    void rejectsUnclaimForDelegatedTask(DelegationState delegationState)
    {
        stubActiveTask(TASK_ID, "7", delegationState);

        assertBusinessError(() -> adapter.unclaimTaskForCurrentUser(TASK_ID),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).unclaim(any());
    }

    /**
     * 验证静态指派任务缺少真实认领元数据时不能被取消认领。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsUnclaimForStaticallyAssignedTask()
    {
        stubActiveTask(TASK_ID, "7", null);

        assertBusinessError(() -> adapter.unclaimTaskForCurrentUser(TASK_ID),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).unclaim(any());
    }

    /**
     * 验证 claimedBy 与当前办理人不一致或认领时间缺失时拒绝取消认领。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsUnclaimForInvalidClaimMetadata()
    {
        Task task = stubActiveTask(TASK_ID, "7", null);
        when(task.getClaimedBy()).thenReturn("8");
        when(task.getClaimTime()).thenReturn(new Date());

        assertBusinessError(() -> adapter.unclaimTaskForCurrentUser(TASK_ID),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);
        verify(taskService, never()).unclaim(any());

        when(task.getClaimedBy()).thenReturn("7");
        when(task.getClaimTime()).thenReturn(null);
        assertBusinessError(() -> adapter.unclaimTaskForCurrentUser(TASK_ID),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);
        verify(taskService, never()).unclaim(any());
    }

    /**
     * 验证带 owner 的任务即使无委派枚举也不能按普通认领任务取消认领。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsUnclaimForTaskWithOwner()
    {
        Task task = stubActiveTask(TASK_ID, "7", null);
        when(task.getOwner()).thenReturn("6");
        when(task.getClaimedBy()).thenReturn("7");
        when(task.getClaimTime()).thenReturn(new Date());

        assertBusinessError(() -> adapter.unclaimTaskForCurrentUser(TASK_ID),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).unclaim(any());
    }

    /**
     * 验证当前办理人可以把普通活动任务委派给正式主数据中的有效用户。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void delegatesTaskToActiveTargetUser()
    {
        stubActiveTask(TASK_ID, "7", null);
        stubActiveUser("008", "8");

        adapter.delegateTaskForCurrentUser(TASK_ID, "008", "  请协助处理  ");

        verify(taskService).delegateTask(TASK_ID, "8");
        assertAuditComment("4", "DELEGATE", "8", "请协助处理");
        verify(notificationService).onStableTaskAction("TASK_DELEGATED", TASK_ID);
    }

    /**
     * 验证非当前办理人不能委派任务，且失败前不会解析目标用户。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsDelegateForNonAssignee()
    {
        stubActiveTask(TASK_ID, "8", null);

        assertBusinessError(() -> adapter.delegateTaskForCurrentUser(TASK_ID, "9"),
                HttpStatus.FORBIDDEN, WorkflowExceptionTranslator.FORBIDDEN_MESSAGE);

        verify(identityResolver, never()).resolveActiveUserIds(any(), any());
        verify(taskService, never()).delegateTask(any(), any());
    }

    /**
     * 验证已有 owner 或任一委派状态的任务不能再次委派。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsDelegateForExistingOwnerOrDelegation()
    {
        Task task = stubActiveTask(TASK_ID, "7", null);
        when(task.getOwner()).thenReturn("6");

        assertBusinessError(() -> adapter.delegateTaskForCurrentUser(TASK_ID, "8"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        when(task.getOwner()).thenReturn(null);
        when(task.getDelegationState()).thenReturn(DelegationState.PENDING);
        assertBusinessError(() -> adapter.delegateTaskForCurrentUser(TASK_ID, "8"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).delegateTask(any(), any());
    }

    /**
     * 验证目标用户不存在、已停用或与当前办理人相同时拒绝委派。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsDelegateForUnavailableOrSameTargetUser()
    {
        stubActiveTask(TASK_ID, "7", null);
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("9")))
                .thenReturn(Set.of());

        assertBusinessError(() -> adapter.delegateTaskForCurrentUser(TASK_ID, "9"),
                HttpStatus.BAD_REQUEST, WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);

        stubActiveUser("007", "7");
        assertBusinessError(() -> adapter.delegateTaskForCurrentUser(TASK_ID, "007"),
                HttpStatus.BAD_REQUEST, WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);

        verify(taskService, never()).delegateTask(any(), any());
    }

    /**
     * 验证当前 PENDING 受托人可以在 owner 仍有效时解决委派。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void resolvesPendingDelegationForCurrentDelegate()
    {
        Task task = stubActiveTask(TASK_ID, "7", DelegationState.PENDING);
        when(task.getOwner()).thenReturn("8");
        stubActiveUser("8", "8");

        adapter.resolveTaskForCurrentUser(TASK_ID, "已完成合同核验");

        verify(taskService).resolveTask(TASK_ID);
        assertAuditComment("4", "RESOLVE", "8", "已完成合同核验");
        verify(notificationService).onStableTaskAction("TASK_DELEGATION_RESOLVED", TASK_ID);
    }

    /**
     * 验证非当前受托人不能解决 PENDING 委派。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsResolveForNonAssignee()
    {
        Task task = stubActiveTask(TASK_ID, "8", DelegationState.PENDING);
        when(task.getOwner()).thenReturn("7");

        assertBusinessError(() -> adapter.resolveTaskForCurrentUser(TASK_ID, "办结意见"),
                HttpStatus.FORBIDDEN, WorkflowExceptionTranslator.FORBIDDEN_MESSAGE);

        verify(identityResolver, never()).resolveActiveUserIds(any(), any());
        verify(taskService, never()).resolveTask(any());
    }

    /**
     * 验证非 PENDING 状态不能调用解决委派命令。
     *
     * @param delegationState String，可为空或 RESOLVED 的非法 resolve 前置状态名称
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "RESOLVED" })
    void rejectsResolveOutsidePendingState(String delegationState)
    {
        DelegationState state = delegationState == null ? null : DelegationState.valueOf(delegationState);
        Task task = stubActiveTask(TASK_ID, "7", state);
        when(task.getOwner()).thenReturn("8");

        assertBusinessError(() -> adapter.resolveTaskForCurrentUser(TASK_ID, "办结意见"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).resolveTask(any());
    }

    /**
     * 验证 owner 缺失、失效、格式不规范或与受托人相同时拒绝解决委派。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsResolveForInvalidOwnerState()
    {
        Task task = stubActiveTask(TASK_ID, "7", DelegationState.PENDING);

        assertBusinessError(() -> adapter.resolveTaskForCurrentUser(TASK_ID, "办结意见"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        when(task.getOwner()).thenReturn("8");
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("8"))).thenReturn(Set.of());
        assertBusinessError(() -> adapter.resolveTaskForCurrentUser(TASK_ID, "办结意见"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        when(task.getOwner()).thenReturn("008");
        stubActiveUser("008", "8");
        assertBusinessError(() -> adapter.resolveTaskForCurrentUser(TASK_ID, "办结意见"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        when(task.getOwner()).thenReturn("invalid-owner");
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("invalid-owner")))
                .thenThrow(new ServiceException("工作流用户标识无效", HttpStatus.BAD_REQUEST));
        assertBusinessError(() -> adapter.resolveTaskForCurrentUser(TASK_ID, "办结意见"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        when(task.getOwner()).thenReturn("7");
        stubActiveUser("7", "7");
        assertBusinessError(() -> adapter.resolveTaskForCurrentUser(TASK_ID, "办结意见"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).resolveTask(any());
    }

    /**
     * 验证当前办理人可以把普通活动任务转办给正式主数据中的有效用户。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void transfersTaskToActiveTargetUser()
    {
        stubActiveTask(TASK_ID, "7", null);
        stubActiveUser("008", "8");

        adapter.transferTaskForCurrentUser(TASK_ID, "008", "  调整办理人  ");

        verify(taskService).setAssignee(TASK_ID, "8");
        assertAuditComment("5", "TRANSFER", "8", "调整办理人");
        verify(notificationService).onStableTaskAction("TASK_TRANSFERRED", TASK_ID);
    }

    /**
     * 验证非当前办理人不能转办任务。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsTransferForNonAssignee()
    {
        stubActiveTask(TASK_ID, "8", null);

        assertBusinessError(() -> adapter.transferTaskForCurrentUser(TASK_ID, "9"),
                HttpStatus.FORBIDDEN, WorkflowExceptionTranslator.FORBIDDEN_MESSAGE);

        verify(identityResolver, never()).resolveActiveUserIds(any(), any());
        verify(taskService, never()).setAssignee(any(), any());
    }

    /**
     * 验证已有 owner 或任一委派状态的任务不能转办。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsTransferForExistingOwnerOrDelegation()
    {
        Task task = stubActiveTask(TASK_ID, "7", null);
        when(task.getOwner()).thenReturn("6");

        assertBusinessError(() -> adapter.transferTaskForCurrentUser(TASK_ID, "8"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        when(task.getOwner()).thenReturn(null);
        when(task.getDelegationState()).thenReturn(DelegationState.RESOLVED);
        assertBusinessError(() -> adapter.transferTaskForCurrentUser(TASK_ID, "8"),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).setAssignee(any(), any());
    }

    /**
     * 验证目标用户不存在、已停用或与当前办理人相同时拒绝转办。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsTransferForUnavailableOrSameTargetUser()
    {
        stubActiveTask(TASK_ID, "7", null);
        when(identityResolver.resolveApprovalEligibleUserIds(List.of("9")))
                .thenReturn(Set.of());

        assertBusinessError(() -> adapter.transferTaskForCurrentUser(TASK_ID, "9"),
                HttpStatus.BAD_REQUEST, WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);

        stubActiveUser("007", "7");
        assertBusinessError(() -> adapter.transferTaskForCurrentUser(TASK_ID, "007"),
                HttpStatus.BAD_REQUEST, WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);

        verify(taskService, never()).setAssignee(any(), any());
    }

    /**
     * 验证委派和转办的空目标参数在进入身份查询与 Flowable 前按 400 拒绝。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsBlankTargetUserBeforeCallingEngine()
    {
        assertBusinessError(() -> adapter.delegateTaskForCurrentUser(TASK_ID, " "),
                HttpStatus.BAD_REQUEST, WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);
        assertBusinessError(() -> adapter.transferTaskForCurrentUser(TASK_ID, " "),
                HttpStatus.BAD_REQUEST, WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);

        verifyNoInteractions(taskService, identityResolver, repositoryService, runtimeService);
    }

    /**
     * 验证转办期间的 Flowable 乐观锁异常转换为稳定 409 并保留原始 cause。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void translatesConcurrentTransferConflict()
    {
        stubActiveTask(TASK_ID, "7", null);
        stubActiveUser("8", "8");
        FlowableOptimisticLockingException source =
                new FlowableOptimisticLockingException("concurrent transfer");
        doThrow(source).when(taskService).setAssignee(TASK_ID, "8");

        assertThatThrownBy(() -> adapter.transferTaskForCurrentUser(TASK_ID, "8"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo(WorkflowExceptionTranslator.CONFLICT_MESSAGE);
                    assertThat(exception.getCause()).isSameAs(source);
                });
        verify(taskService, never()).addComment(any(), any(), any(), any());
    }

    /**
     * 验证当前 assignee 可以完成普通活动任务并提交真实流程变量。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void completesTaskOnlyForCurrentAssignee()
    {
        stubActiveTask(TASK_ID, "7", null);
        Map<String, Object> variables = Map.of("approved", true);

        adapter.completeTask(TASK_ID, variables);

        verify(taskService).complete(TASK_ID, "7", variables);
    }

    /**
     * 验证已 resolve 的委派任务仍仅允许当前 assignee 按 Flowable 标准语义完成。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void completesResolvedDelegationForCurrentAssignee()
    {
        stubActiveTask(TASK_ID, "7", DelegationState.RESOLVED);

        adapter.completeTask(TASK_ID, null);

        verify(taskService).complete(TASK_ID, "7", Map.of());
    }

    /**
     * 验证既有静态多实例不参加动态 revision 契约，低层普通完成路径保持兼容。
     *
     * @return 无返回值；静态集合被误判为受控 handler 或任务未完成时测试失败
     */
    @Test
    void completesStaticMultiInstanceForCurrentAssignee()
    {
        stubActiveTask(TASK_ID, "7", null);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(taskModel(staticMultiInstanceLoop()));

        adapter.completeTask(TASK_ID, Map.of());

        verify(taskService).complete(TASK_ID, "7", Map.of());
    }

    /**
     * 验证嵌套 SubProcess 中的普通 UserTask 仍可通过低层适配器完成。
     *
     * @return 无返回值；递归模型查找误报 500 或任务未完成时测试失败
     */
    @Test
    void completesOrdinaryTaskInsideSubProcess()
    {
        stubActiveTask(TASK_ID, "7", null);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(nestedTaskModel());

        adapter.completeTask(TASK_ID, Map.of());

        verify(taskService).complete(TASK_ID, "7", Map.of());
    }

    /**
     * 验证正式动态多实例不能通过低层 completeTask 绕过 expectedRevision 与结构化审计。
     *
     * @return 无返回值；受控动态任务被直接完成或未返回稳定 409 时测试失败
     */
    @Test
    void rejectsControlledDynamicMultiInstanceCompletionBypass()
    {
        stubActiveTask(TASK_ID, "7", null);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(taskModel(dynamicMultiInstanceLoop(false)));

        assertBusinessError(() -> adapter.completeTask(TASK_ID, Map.of()),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).complete(any(), any(String.class), anyMap());
    }

    /**
     * 验证 collectionString 命中受控 handler 的畸形模型同样不能降级为普通完成。
     *
     * @return 无返回值；畸形受控候选绕过低层门禁时测试失败
     */
    @Test
    void rejectsMalformedControlledCollectionCompletionBypass()
    {
        stubActiveTask(TASK_ID, "7", null);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(taskModel(dynamicMultiInstanceLoop(true)));

        assertBusinessError(() -> adapter.completeTask(TASK_ID, Map.of()),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).complete(any(), any(String.class), anyMap());
    }

    /**
     * 验证受控动态多实例不能通过委派或转办改写办理人并破坏正式成员快照。
     *
     * @param command TaskCommand，待验证的委派或转办低层命令
     * @return 无返回值；任一命令改写任务或写入审计时测试失败
     */
    @ParameterizedTest
    @EnumSource(value = TaskCommand.class, names = { "DELEGATE", "TRANSFER" })
    void rejectsControlledDynamicMultiInstanceAssigneeMutation(TaskCommand command)
    {
        stubActiveTask(TASK_ID, "7", null);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(taskModel(dynamicMultiInstanceLoop(false)));

        assertBusinessError(() -> execute(command),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verifyNoTaskCommandExecuted();
        verify(identityResolver, never()).resolveActiveUserIds(any(), any());
    }

    /**
     * 验证多 Process 部署必须按定义 key 识别第二个 Process 中的受控动态节点。
     *
     * @param command TaskCommand，可能绕过正式动态状态机的委派、转办或完成命令
     * @return 无返回值；首个普通任务诱饵导致任一低层写命令通过时测试失败
     */
    @ParameterizedTest
    @EnumSource(value = TaskCommand.class, names = { "DELEGATE", "TRANSFER", "COMPLETE" })
    void rejectsControlledDynamicMutationInSecondaryProcess(TaskCommand command)
    {
        stubActiveTask(TASK_ID, "7", null);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(secondaryProcessTaskModel(dynamicMultiInstanceLoop(false)));

        assertBusinessError(() -> execute(command),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verifyNoTaskCommandExecuted();
        verify(identityResolver, never()).resolveActiveUserIds(any(), any());
    }

    /**
     * 验证嵌套 SubProcess 中的受控候选仍被低层完成门禁拒绝。
     *
     * @return 无返回值；递归查找遗漏动态节点并直接完成任务时测试失败
     */
    @Test
    void rejectsControlledDynamicMutationInsideSubProcess()
    {
        stubActiveTask(TASK_ID, "7", null);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(nestedTaskModel(dynamicMultiInstanceLoop(false)));

        assertBusinessError(() -> adapter.completeTask(TASK_ID, Map.of()),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verifyNoTaskCommandExecuted();
    }

    /**
     * 验证活动任务引用的流程定义被并发删除时，低层写命令返回数据一致性 500 且零副作用。
     *
     * @return 无返回值；异常被误映射为 404、原始 cause 丢失或任务被完成时测试失败
     */
    @Test
    void mapsMissingProcessDefinitionToDataErrorWithoutWrites()
    {
        stubActiveTask(TASK_ID, "7", null);
        FlowableObjectNotFoundException missingDefinition =
                new FlowableObjectNotFoundException("definition missing",
                        ProcessDefinition.class);
        when(repositoryService.getProcessDefinition("definition-1"))
                .thenThrow(missingDefinition);

        assertThatThrownBy(() -> adapter.completeTask(TASK_ID, Map.of()))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getCause()).isSameAs(missingDefinition);
                });

        verifyNoTaskCommandExecuted();
    }

    /**
     * 验证未认领任务或其他用户任务不能由当前用户完成。
     *
     * @param assignee String，null 表示未认领，其他值表示其他办理人
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "8" })
    void rejectsCompleteForNonAssignee(String assignee)
    {
        stubActiveTask(TASK_ID, assignee, null);

        assertBusinessError(() -> adapter.completeTask(TASK_ID, Map.of()),
                HttpStatus.FORBIDDEN, WorkflowExceptionTranslator.FORBIDDEN_MESSAGE);

        verify(taskService, never()).complete(any(), any(String.class), anyMap());
    }

    /**
     * 验证 PENDING 委派必须先 resolve，不能直接 complete 跳过委派回退语义。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void rejectsCompleteForPendingDelegation()
    {
        stubActiveTask(TASK_ID, "7", DelegationState.PENDING);

        assertBusinessError(() -> adapter.completeTask(TASK_ID, Map.of()),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verify(taskService, never()).complete(any(), any(String.class), anyMap());
    }

    /**
     * 验证六个任务命令遇到不存在任务时均返回 404，且绝不执行写命令。
     *
     * @param command TaskCommand，待验证的任务写命令
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @EnumSource(TaskCommand.class)
    void rejectsMissingTaskWithNotFoundStatus(TaskCommand command)
    {
        stubMissingTask(TASK_ID);

        assertBusinessError(() -> execute(command),
                HttpStatus.NOT_FOUND, WorkflowExceptionTranslator.OBJECT_NOT_FOUND_MESSAGE);

        verifyNoTaskCommandExecuted();
    }

    /**
     * 验证六个任务命令遇到挂起任务时均返回 409，且绝不执行写命令。
     *
     * @param command TaskCommand，待验证的任务写命令
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @EnumSource(TaskCommand.class)
    void rejectsSuspendedTaskWithConflictStatus(TaskCommand command)
    {
        stubSuspendedTask(TASK_ID);

        assertBusinessError(() -> execute(command),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verifyNoTaskCommandExecuted();
    }

    /**
     * 验证并发认领冲突由适配边界翻译为稳定 409，且保留原始引擎 cause。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void translatesConcurrentClaimConflict()
    {
        stubActiveTask(TASK_ID, null, null);
        IdentityLink directCandidate = identityLink(IdentityLinkType.CANDIDATE, "7", null);
        when(taskService.getIdentityLinksForTask(TASK_ID))
                .thenReturn(List.of(directCandidate));
        FlowableTaskAlreadyClaimedException source =
                new FlowableTaskAlreadyClaimedException(TASK_ID, "other-user");
        doThrow(source).when(taskService).claim(TASK_ID, "7");

        assertThatThrownBy(() -> adapter.claimTaskForCurrentUser(TASK_ID))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo(WorkflowExceptionTranslator.CONFLICT_MESSAGE);
                    assertThat(exception.getCause()).isSameAs(source);
                });
        verify(taskService, never()).addComment(any(), any(), any(), any());
    }

    /**
     * 验证四个对外任务动作在所属流程实例非活动时统一返回 409，且不产生状态或审计副作用。
     *
     * @param command AuditedTaskCommand，待验证的认领、取消认领、委派或转办命令
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @EnumSource(AuditedTaskCommand.class)
    void rejectsAuditedActionWhenProcessInstanceIsNotActive(AuditedTaskCommand command)
    {
        stubActiveTask(TASK_ID, "7", null);
        stubInactiveProcessInstance();

        assertBusinessError(() -> execute(command),
                HttpStatus.CONFLICT, WorkflowExceptionTranslator.CONFLICT_MESSAGE);

        verifyNoTaskCommandExecuted();
    }

    /**
     * 验证正式身份核验失败时不会读取或修改 Flowable 任务。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void stopsBeforeEngineWhenCurrentIdentityIsRejected()
    {
        ServiceException rejected = new ServiceException("当前用户不可参与工作流", HttpStatus.FORBIDDEN);
        when(identityResolver.resolveCurrentIdentity()).thenThrow(rejected);

        assertThatThrownBy(() -> adapter.claimTaskForCurrentUser(TASK_ID)).isSameAs(rejected);

        verifyNoInteractions(taskService);
    }

    /**
     * 验证所有任务命令的空任务 ID 在进入身份查询和 Flowable 服务前按稳定 400 拒绝。
     *
     * @param command TaskCommand，待验证参数边界的任务命令
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @EnumSource(TaskCommand.class)
    void rejectsBlankTaskIdBeforeCallingEngine(TaskCommand command)
    {
        assertBusinessError(() -> execute(command, " "),
                HttpStatus.BAD_REQUEST, WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);

        verifyNoInteractions(taskService, identityResolver, repositoryService, runtimeService);
    }

    /**
     * 构造一个活动任务及其公开 Query API 返回链。
     *
     * @param taskId String，任务 ID
     * @param assignee String，可为空的当前办理人
     * @param delegationState DelegationState，可为空的委派状态
     * @return Task，已配置状态的任务替身
     */
    private Task stubActiveTask(String taskId, String assignee, DelegationState delegationState)
    {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn("process-1");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getTaskDefinitionKey()).thenReturn("approveTask");
        when(task.getAssignee()).thenReturn(assignee);
        when(task.getDelegationState()).thenReturn(delegationState);
        when(task.isSuspended()).thenReturn(false);

        TaskQuery activeQuery = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(activeQuery);
        when(activeQuery.taskId(taskId)).thenReturn(activeQuery);
        when(activeQuery.active()).thenReturn(activeQuery);
        when(activeQuery.singleResult()).thenReturn(task);

        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.isSuspended()).thenReturn(false);
        ProcessInstanceQuery processQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processQuery);
        when(processQuery.processInstanceId("process-1")).thenReturn(processQuery);
        when(processQuery.active()).thenReturn(processQuery);
        when(processQuery.singleResult()).thenReturn(processInstance);
        ProcessDefinition processDefinition = mock(ProcessDefinition.class);
        when(processDefinition.getKey()).thenReturn("process");
        when(repositoryService.getProcessDefinition("definition-1"))
                .thenReturn(processDefinition);
        when(repositoryService.getBpmnModel("definition-1"))
                .thenReturn(taskModel(null));
        return task;
    }

    /**
     * 创建主流程中只包含 approveTask 的部署模型。
     *
     * @param loop MultiInstanceLoopCharacteristics，可空的多实例循环配置
     * @return BpmnModel，任务已挂入主流程并可供完成门禁分类
     */
    private BpmnModel taskModel(MultiInstanceLoopCharacteristics loop)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process");
        process.setExecutable(true);
        model.addProcess(process);
        UserTask userTask = new UserTask();
        userTask.setId("approveTask");
        userTask.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        userTask.setLoopCharacteristics(loop);
        process.addFlowElement(userTask);
        return model;
    }

    /**
     * 创建嵌套 SubProcess 中包含 approveTask 的普通任务模型。
     *
     * @return BpmnModel，递归查找可定位且不参与动态 handler 契约的部署模型
     */
    private BpmnModel nestedTaskModel()
    {
        return nestedTaskModel(null);
    }

    /**
     * 创建嵌套 SubProcess 中包含 approveTask 的任务模型。
     *
     * @param loop MultiInstanceLoopCharacteristics，可空的多实例循环配置
     * @return BpmnModel，递归查找可定位普通任务或受控动态候选
     */
    private BpmnModel nestedTaskModel(MultiInstanceLoopCharacteristics loop)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process");
        process.setExecutable(true);
        model.addProcess(process);
        SubProcess subProcess = new SubProcess();
        subProcess.setId("approvalSubProcess");
        process.addFlowElement(subProcess);
        UserTask userTask = new UserTask();
        userTask.setId("approveTask");
        userTask.setLoopCharacteristics(loop);
        subProcess.addFlowElement(userTask);
        return model;
    }

    /**
     * 创建首个 Process 为普通任务诱饵、第二个定义 Process 为受控任务的模型。
     *
     * @param loop MultiInstanceLoopCharacteristics，定义 Process 中任务的循环配置
     * @return BpmnModel，用于验证适配器严格按 ProcessDefinition key 选择 Process
     */
    private BpmnModel secondaryProcessTaskModel(MultiInstanceLoopCharacteristics loop)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process decoy = new org.flowable.bpmn.model.Process();
        decoy.setId("decoy");
        decoy.setExecutable(true);
        model.addProcess(decoy);
        UserTask decoyTask = new UserTask();
        decoyTask.setId("approveTask");
        decoy.addFlowElement(decoyTask);

        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process");
        process.setExecutable(true);
        model.addProcess(process);
        UserTask userTask = new UserTask();
        userTask.setId("approveTask");
        userTask.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        userTask.setLoopCharacteristics(loop);
        process.addFlowElement(userTask);
        return model;
    }

    /**
     * 创建不命中受控 handler 的既有静态并行多实例循环。
     *
     * @return MultiInstanceLoopCharacteristics，集合来自普通流程变量的静态配置
     */
    private MultiInstanceLoopCharacteristics staticMultiInstanceLoop()
    {
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        loop.setInputDataItem("${staticApproverIds}");
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        return loop;
    }

    /**
     * 创建命中受控 handler 的多实例候选，分别覆盖正式 inputDataItem 与畸形 collectionString。
     *
     * @param collectionString boolean，true 时把固定表达式写入 collectionString
     * @return MultiInstanceLoopCharacteristics，低层完成必须拒绝的受控候选
     */
    private MultiInstanceLoopCharacteristics dynamicMultiInstanceLoop(boolean collectionString)
    {
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        if (collectionString)
        {
            loop.setCollectionString(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        }
        else
        {
            loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        }
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(
                WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        return loop;
    }

    /**
     * 把当前任务关联的流程实例查询改为无活动结果，用于验证实例状态门禁先于动作写入。
     *
     * @return 无返回值；活动实例查询替身直接注册到 RuntimeService
     */
    private void stubInactiveProcessInstance()
    {
        ProcessInstanceQuery processQuery = mock(ProcessInstanceQuery.class);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processQuery);
        when(processQuery.processInstanceId("process-1")).thenReturn(processQuery);
        when(processQuery.active()).thenReturn(processQuery);
        when(processQuery.singleResult()).thenReturn(null);
    }

    /**
     * 配置正式主数据解析器返回一个规范化后的有效目标用户。
     *
     * @param requestedUserId String，命令提交的原始目标用户 ID
     * @param normalizedUserId String，正式主数据确认后的规范用户 ID
     * @return 无返回值；解析结果直接注册到身份解析器替身
     */
    private void stubActiveUser(String requestedUserId, String normalizedUserId)
    {
        when(identityResolver.resolveActiveUserIds(List.of(requestedUserId), List.of()))
                .thenReturn(Set.of(normalizedUserId));
        when(identityResolver.resolveApprovalEligibleUserIds(List.of(requestedUserId)))
                .thenReturn(Set.of(normalizedUserId));
    }

    /**
     * 构造活动查询和主键查询均无结果的不存在任务场景。
     *
     * @param taskId String，不存在的任务 ID
     * @return 无返回值；查询替身直接注册到 TaskService
     */
    private void stubMissingTask(String taskId)
    {
        TaskQuery activeQuery = mock(TaskQuery.class);
        TaskQuery existingQuery = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(activeQuery, existingQuery);
        when(activeQuery.taskId(taskId)).thenReturn(activeQuery);
        when(activeQuery.active()).thenReturn(activeQuery);
        when(activeQuery.singleResult()).thenReturn(null);
        when(existingQuery.taskId(taskId)).thenReturn(existingQuery);
        when(existingQuery.singleResult()).thenReturn(null);
    }

    /**
     * 构造 active 查询无结果但主键查询仍存在的挂起任务场景。
     *
     * @param taskId String，挂起任务 ID
     * @return 无返回值；查询替身直接注册到 TaskService
     */
    private void stubSuspendedTask(String taskId)
    {
        Task suspendedTask = mock(Task.class);
        when(suspendedTask.isSuspended()).thenReturn(true);
        TaskQuery activeQuery = mock(TaskQuery.class);
        TaskQuery existingQuery = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(activeQuery, existingQuery);
        when(activeQuery.taskId(taskId)).thenReturn(activeQuery);
        when(activeQuery.active()).thenReturn(activeQuery);
        when(activeQuery.singleResult()).thenReturn(null);
        when(existingQuery.taskId(taskId)).thenReturn(existingQuery);
        when(existingQuery.singleResult()).thenReturn(suspendedTask);
    }

    /**
     * 构造 Flowable 公共身份关联对象，用于验证 candidate 类型、用户和组匹配。
     *
     * @param type String，身份关联类型
     * @param userId String，可为空的候选用户 ID
     * @param groupId String，可为空的候选组 ID
     * @return IdentityLink，按入参返回身份字段的公共 API 替身
     */
    private IdentityLink identityLink(String type, String userId, String groupId)
    {
        IdentityLink identityLink = mock(IdentityLink.class);
        when(identityLink.getType()).thenReturn(type);
        when(identityLink.getUserId()).thenReturn(userId);
        when(identityLink.getGroupId()).thenReturn(groupId);
        return identityLink;
    }

    /**
     * 执行指定任务写命令，用于复用不存在和挂起状态的错误矩阵。
     *
     * @param command TaskCommand，待执行的任务命令
     * @return 无返回值；适配器异常原样向测试传播
     */
    private void execute(TaskCommand command)
    {
        execute(command, TASK_ID);
    }

    /**
     * 使用指定任务 ID 执行任务命令，用于复用必填参数错误矩阵。
     *
     * @param command TaskCommand，待执行的任务命令
     * @param taskId String，传给适配器的任务 ID
     * @return 无返回值；适配器异常原样向测试传播
     */
    private void execute(TaskCommand command, String taskId)
    {
        switch (command)
        {
            case CLAIM -> adapter.claimTaskForCurrentUser(taskId);
            case UNCLAIM -> adapter.unclaimTaskForCurrentUser(taskId);
            case DELEGATE -> adapter.delegateTaskForCurrentUser(taskId, "8");
            case RESOLVE -> adapter.resolveTaskForCurrentUser(taskId, "办结意见");
            case TRANSFER -> adapter.transferTaskForCurrentUser(taskId, "8");
            case COMPLETE -> adapter.completeTask(taskId, Map.of());
        }
    }

    /**
     * 执行一个会写入审计 comment 的对外任务动作，用于复用所属实例状态错误矩阵。
     *
     * @param command AuditedTaskCommand，认领、取消认领、委派或转办命令
     * @return 无返回值；适配器异常原样向测试传播
     */
    private void execute(AuditedTaskCommand command)
    {
        switch (command)
        {
            case CLAIM -> adapter.claimTaskForCurrentUser(TASK_ID);
            case UNCLAIM -> adapter.unclaimTaskForCurrentUser(TASK_ID);
            case DELEGATE -> adapter.delegateTaskForCurrentUser(TASK_ID, "8", "委派意见");
            case TRANSFER -> adapter.transferTaskForCurrentUser(TASK_ID, "8", "转办意见");
        }
    }

    /**
     * 解析并核对 Flowable comment 中由服务端生成的审计 JSON，确保客户端不能伪造动作和操作人。
     *
     * @param commentType String，与旧系统兼容的 comment 类型
     * @param action String，服务端固定动作编码
     * @param targetUserId String，目标用户主键；认领和取消认领时为空
     * @param opinion String，期望写入的业务意见正文
     * @return 无返回值；comment 缺失、JSON 非法或字段不匹配时测试失败
     */
    private void assertAuditComment(String commentType, String action, String targetUserId, String opinion)
    {
        ArgumentCaptor<String> auditCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).addComment(eq(TASK_ID), eq("process-1"), eq(commentType), auditCaptor.capture());
        try
        {
            JsonNode audit = JsonMapper.shared().readTree(auditCaptor.getValue());
            assertThat(audit.path("action").asText()).isEqualTo(action);
            assertThat(audit.path("actorUserId").asText()).isEqualTo("7");
            assertThat(audit.path("opinion").asText()).isEqualTo(opinion);
            if (targetUserId == null)
            {
                assertThat(audit.has("targetUserId")).isFalse();
                assertThat(audit.size()).isEqualTo(3);
            }
            else
            {
                assertThat(audit.path("targetUserId").asText()).isEqualTo(targetUserId);
                assertThat(audit.size()).isEqualTo(4);
            }
        }
        catch (Exception exception)
        {
            throw new AssertionError("审计 comment 必须是合法 JSON", exception);
        }
    }

    /**
     * 断言适配器抛出指定稳定 HTTP 状态和用户提示。
     *
     * @param action ThrowingCallable，预期失败的适配器调用
     * @param expectedCode int，预期 HTTP 状态码
     * @param expectedMessage String，预期稳定业务提示
     * @return 无返回值；状态或提示不匹配时测试失败
     */
    private void assertBusinessError(ThrowingCallable action, int expectedCode, String expectedMessage)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(expectedCode);
            assertThat(exception.getMessage()).isEqualTo(expectedMessage);
        });
    }

    /**
     * 验证所有任务写命令均未执行，确保失败分支没有业务副作用。
     *
     * @return 无返回值；发现任一任务写调用时测试失败
     */
    private void verifyNoTaskCommandExecuted()
    {
        verify(taskService, never()).claim(any(), any());
        verify(taskService, never()).unclaim(any());
        verify(taskService, never()).delegateTask(any(), any());
        verify(taskService, never()).resolveTask(any());
        verify(taskService, never()).setAssignee(any(), any());
        verify(taskService, never()).complete(any(), any(String.class), anyMap());
        verify(taskService, never()).addComment(any(), any(), any(), any());
    }

    /** 需要统一验证所属流程实例状态的对外审计任务命令。 */
    private enum AuditedTaskCommand
    {
        CLAIM,
        UNCLAIM,
        DELEGATE,
        TRANSFER
    }

    /** 需要统一验证状态边界的任务写命令。 */
    private enum TaskCommand
    {
        CLAIM,
        UNCLAIM,
        DELEGATE,
        RESOLVE,
        TRANSFER,
        COMPLETE
    }
}
