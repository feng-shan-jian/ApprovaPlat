package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.TaskService;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationConstants;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/**
 * 聚焦生产用户任务监听器在发起人退回和重提 assignment 中的身份、标记与调用时序。
 */
class WorkflowReturnedApplicantListenerContractTest
{
    /** 测试任务的稳定主键。 */
    private static final String TASK_ID = "task-1";

    /** 测试流程实例的稳定主键。 */
    private static final String PROCESS_INSTANCE_ID = "process-1";

    /** 测试流程定义的稳定主键。 */
    private static final String PROCESS_DEFINITION_ID = "definition-1";

    /** 测试首审批节点的稳定 key。 */
    private static final String ACTIVITY_ID = "firstApproval";

    /** 不具备审批资格但处于启用状态的流程发起人。 */
    private static final String APPLICANT_ID = "100";

    /** 执行退回且具备审批资格的认证用户。 */
    private static final String RETURN_ACTOR_ID = "201";

    /** 重提时恢复的原首审批办理人。 */
    private static final String RESTORED_ASSIGNEE_ID = "301";

    private TaskService taskService;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;
    private WorkflowAutomaticCopyService automaticCopyService;
    private WorkflowTaskSlaRuntimeService slaRuntimeService;
    private WorkflowNotificationService notificationService;
    private WorkflowMultiInstanceRoundService multiInstanceRoundService;
    private DelegateTask delegateTask;
    private WorkflowUserTaskListener listener;

    /**
     * 使用真实生产监听器和审计服务，并用明确 mock 隔离无关业务协作者及外部身份目录。
     *
     * @return void，无返回值；所有构造器依赖均为显式对象，不传入 null
     */
    @BeforeEach
    void setUp()
    {
        taskService = mock(TaskService.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        participantRuleRuntimeService =
                mock(WorkflowParticipantRuleRuntimeService.class);
        automaticCopyService = mock(WorkflowAutomaticCopyService.class);
        slaRuntimeService = mock(WorkflowTaskSlaRuntimeService.class);
        notificationService = mock(WorkflowNotificationService.class);
        multiInstanceRoundService = mock(WorkflowMultiInstanceRoundService.class);
        delegateTask = mock(DelegateTask.class);
        WorkflowUserTaskAuditService auditService = new WorkflowUserTaskAuditService(
                taskService, identityResolver, new WorkflowIdentityCodec());
        listener = new WorkflowUserTaskListener(auditService, slaRuntimeService,
                participantRuleRuntimeService, automaticCopyService,
                notificationService, multiInstanceRoundService);
    }

    /**
     * 清理 Flowable 静态认证身份，避免测试间共享线程导致身份串扰。
     *
     * @return void，无返回值
     */
    @AfterEach
    void clearAuthentication()
    {
        Authentication.setAuthenticatedUserId(null);
    }

    /**
     * 验证 RETURN assignment 先读取发起人任务标记、校验认证退回人，再执行审计及旁路协作者。
     *
     * @return void，标记、身份、RETURN 上下文或生产监听器调用顺序漂移时失败
     */
    @Test
    void acceptsReturnedApplicantOnlyAfterMarkerAndAuthenticatedActorAreVisible()
    {
        Map<String, Object> returnVariables = Map.of(
                WorkflowNotificationConstants.CONTROLLED_TRANSITION_VARIABLE,
                "RETURN");
        configureAssignment(APPLICANT_ID, returnVariables);
        when(taskService.getVariableLocal(TASK_ID,
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE))
                .thenReturn(APPLICANT_ID);
        when(identityResolver.resolveActiveUserIds(
                List.of(APPLICANT_ID), List.of()))
                .thenReturn(Set.of(APPLICANT_ID));
        when(identityResolver.resolveApprovalEligibleUserIds(
                Set.of(RETURN_ACTOR_ID)))
                .thenReturn(Set.of(RETURN_ACTOR_ID));
        Authentication.setAuthenticatedUserId(RETURN_ACTOR_ID);

        listener.notify(delegateTask);

        ArgumentCaptor<String> auditJson = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(taskService, identityResolver,
                automaticCopyService, slaRuntimeService, notificationService);
        order.verify(taskService).getVariableLocal(TASK_ID,
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE);
        order.verify(identityResolver).resolveActiveUserIds(
                List.of(APPLICANT_ID), List.of());
        order.verify(identityResolver).resolveApprovalEligibleUserIds(
                Set.of(RETURN_ACTOR_ID));
        order.verify(taskService).addComment(eq(TASK_ID),
                eq(PROCESS_INSTANCE_ID),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE), auditJson.capture());
        order.verify(automaticCopyService).onTaskEvent(
                TaskListener.EVENTNAME_ASSIGNMENT, TASK_ID, PROCESS_INSTANCE_ID,
                PROCESS_DEFINITION_ID, ACTIVITY_ID, "首审批",
                returnVariables);
        order.verify(slaRuntimeService).onTaskEvent(
                TaskListener.EVENTNAME_ASSIGNMENT, TASK_ID, PROCESS_INSTANCE_ID,
                PROCESS_DEFINITION_ID, ACTIVITY_ID, APPLICANT_ID);
        order.verify(notificationService).onTaskEvent(
                TaskListener.EVENTNAME_ASSIGNMENT, TASK_ID, PROCESS_INSTANCE_ID,
                PROCESS_DEFINITION_ID, ACTIVITY_ID, "首审批", APPLICANT_ID);
        assertThat(auditJson.getValue())
                .contains("\"actorUserId\":\"" + RETURN_ACTOR_ID + "\"")
                .contains("\"assigneeUserId\":\"" + APPLICANT_ID + "\"");
        verifyNoInteractions(participantRuleRuntimeService);
    }

    /**
     * 验证 RESUBMIT assignment 发生前发起人例外标记已经移除，且原办理人恢复时仍携带重提上下文。
     *
     * @return void，标记清理、认证身份、RESUBMIT 上下文或调用顺序漂移时失败
     */
    @Test
    void restoresOriginalAssigneeOnlyAfterReturnMarkerIsRemovedForResubmit()
    {
        Map<String, Object> resubmitVariables = Map.of(
                WorkflowNotificationConstants.CONTROLLED_TRANSITION_VARIABLE,
                "RESUBMIT");
        configureAssignment(RESTORED_ASSIGNEE_ID, resubmitVariables);
        when(taskService.getVariableLocal(TASK_ID,
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE))
                .thenReturn(null);
        when(identityResolver.resolveApprovalEligibleUserIds(
                Set.of(RESTORED_ASSIGNEE_ID)))
                .thenReturn(Set.of(RESTORED_ASSIGNEE_ID));
        Authentication.setAuthenticatedUserId(APPLICANT_ID);

        listener.notify(delegateTask);

        ArgumentCaptor<String> auditJson = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(taskService, identityResolver,
                automaticCopyService, slaRuntimeService, notificationService);
        order.verify(taskService).getVariableLocal(TASK_ID,
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE);
        order.verify(identityResolver).resolveApprovalEligibleUserIds(
                Set.of(RESTORED_ASSIGNEE_ID));
        order.verify(taskService).addComment(eq(TASK_ID),
                eq(PROCESS_INSTANCE_ID),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE), auditJson.capture());
        order.verify(automaticCopyService).onTaskEvent(
                TaskListener.EVENTNAME_ASSIGNMENT, TASK_ID, PROCESS_INSTANCE_ID,
                PROCESS_DEFINITION_ID, ACTIVITY_ID, "首审批",
                resubmitVariables);
        order.verify(slaRuntimeService).onTaskEvent(
                TaskListener.EVENTNAME_ASSIGNMENT, TASK_ID, PROCESS_INSTANCE_ID,
                PROCESS_DEFINITION_ID, ACTIVITY_ID, RESTORED_ASSIGNEE_ID);
        order.verify(notificationService).onTaskEvent(
                TaskListener.EVENTNAME_ASSIGNMENT, TASK_ID, PROCESS_INSTANCE_ID,
                PROCESS_DEFINITION_ID, ACTIVITY_ID, "首审批", RESTORED_ASSIGNEE_ID);
        assertThat(auditJson.getValue())
                .contains("\"actorUserId\":\"" + APPLICANT_ID + "\"")
                .contains("\"assigneeUserId\":\""
                        + RESTORED_ASSIGNEE_ID + "\"");
        verify(identityResolver, never()).resolveActiveUserIds(
                List.of(APPLICANT_ID), List.of());
        verifyNoInteractions(participantRuleRuntimeService);
    }

    /**
     * 验证没有任务局部发起人退回标记时，普通 assignment 规则拒绝把任务改派给无审批资格发起人。
     *
     * @return void，非法改派未被拒绝或产生任何旁路副作用时失败
     */
    @Test
    void rejectsApplicantAssignmentWithoutReturnedApplicantMarker()
    {
        configureAssignment(APPLICANT_ID, Map.of(
                WorkflowNotificationConstants.CONTROLLED_TRANSITION_VARIABLE,
                "RETURN"));
        when(taskService.getVariableLocal(TASK_ID,
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE))
                .thenReturn(null);
        when(identityResolver.resolveApprovalEligibleUserIds(Set.of(APPLICANT_ID)))
                .thenReturn(Set.of());
        Authentication.setAuthenticatedUserId(RETURN_ACTOR_ID);

        assertThatThrownBy(() -> listener.notify(delegateTask))
                .isInstanceOf(ServiceException.class)
                .hasMessage("用户任务办理身份无效");

        verify(taskService, never()).addComment(eq(TASK_ID),
                eq(PROCESS_INSTANCE_ID),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE),
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(participantRuleRuntimeService, automaticCopyService,
                slaRuntimeService, notificationService);
    }

    /**
     * 验证即使任务局部标记正确，缺少 Flowable 认证操作人也不能使用发起人退回例外。
     *
     * @return void，匿名改派未被拒绝或产生任何旁路副作用时失败
     */
    @Test
    void rejectsReturnedApplicantMarkerWithoutAuthenticatedActor()
    {
        configureAssignment(APPLICANT_ID, Map.of(
                WorkflowNotificationConstants.CONTROLLED_TRANSITION_VARIABLE,
                "RETURN"));
        when(taskService.getVariableLocal(TASK_ID,
                WorkflowTaskLifecycleService.RETURN_APPLICANT_VARIABLE))
                .thenReturn(APPLICANT_ID);
        Authentication.setAuthenticatedUserId(null);

        assertThatThrownBy(() -> listener.notify(delegateTask))
                .isInstanceOf(ServiceException.class)
                .hasMessage("用户任务监听数据异常");

        verify(identityResolver, never()).resolveActiveUserIds(
                List.of(APPLICANT_ID), List.of());
        verify(taskService, never()).addComment(eq(TASK_ID),
                eq(PROCESS_INSTANCE_ID),
                eq(WorkflowUserTaskAuditService.COMMENT_TYPE),
                org.mockito.ArgumentMatchers.anyString());
        verifyNoInteractions(participantRuleRuntimeService, automaticCopyService,
                slaRuntimeService, notificationService);
    }

    /**
     * 为生产监听器准备一个完整 assignment 事件上下文。
     *
     * @param assignee String，当前 assignment 事件办理人
     * @param variables Map&lt;String,Object&gt;，监听时可见的流程变量快照
     * @return void，无返回值
     */
    private void configureAssignment(String assignee, Map<String, Object> variables)
    {
        when(delegateTask.getEventName())
                .thenReturn(TaskListener.EVENTNAME_ASSIGNMENT);
        when(delegateTask.getId()).thenReturn(TASK_ID);
        when(delegateTask.getProcessInstanceId()).thenReturn(PROCESS_INSTANCE_ID);
        when(delegateTask.getProcessDefinitionId()).thenReturn(PROCESS_DEFINITION_ID);
        when(delegateTask.getTaskDefinitionKey()).thenReturn(ACTIVITY_ID);
        when(delegateTask.getName()).thenReturn("首审批");
        when(delegateTask.getAssignee()).thenReturn(assignee);
        when(delegateTask.getOwner()).thenReturn(null);
        when(delegateTask.getVariables()).thenReturn(variables);
    }
}
