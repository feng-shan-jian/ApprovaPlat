package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.dto.*;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.WorkflowUserTaskListener;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/** 真实分配命令与 Flowable 嵌套监听共同证明退回例外、恢复及委派转办的资格和审计。 */
class WorkflowReturnedApplicantListenerContractTest
{
    private WorkflowFlowableEngineTestSupport harness;
    private TaskService tasks;
    private WorkflowIdentityResolver identities;
    private WorkflowReturnedTaskStateService returned;
    private WorkflowEngineOperations operations;
    private WorkflowTaskActionService actions;
    private WorkflowDeploymentArtifactRepository artifacts;
    private String deploymentId;
    private final AtomicReference<String> actor = new AtomicReference<>("301");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp()
    {
        var binding = new WorkflowMultiInstanceEngineHarness.LateBindingTaskListener();
        harness = WorkflowFlowableEngineTestSupport.start("returned-listener", Map.of("userTaskListener", binding));
        var engine = harness.processEngine();
        tasks = spy(engine.getTaskService());
        identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenAnswer(i -> new WorkflowCurrentIdentity(actor.get(), Set.of()));
        when(identities.resolveApprovalEligibleUserIds(anyCollection())).thenAnswer(i ->
        {
            var ids = new LinkedHashSet<>((Collection<String>) i.getArgument(0));
            ids.remove("100");
            return ids;
        });
        when(identities.resolveActiveUserIds(anyCollection(), anyCollection())).thenAnswer(i ->
                new LinkedHashSet<>((Collection<String>) i.getArgument(0)));
        operations = harness.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(engine.getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identities));
        artifacts = new WorkflowDeploymentArtifactRepository(engine.getRepositoryService());
        var participants = new WorkflowParticipantRuleRuntimeService(engine.getRepositoryService(), engine.getRuntimeService(),
                artifacts, mock(WorkflowIdentityMapper.class), identities, mock(WorkflowParticipantResolutionMetrics.class));
        binding.bind(new WorkflowUserTaskListener(new WorkflowUserTaskAuditService(tasks, new WorkflowIdentityCodec()),
                mock(WorkflowTaskSlaRuntimeService.class), participants, mock(WorkflowAutomaticCopyService.class),
                mock(WorkflowNotificationService.class), mock(WorkflowMultiInstanceRoundLifecycleService.class)));
        returned = new WorkflowReturnedTaskStateService(new WorkflowReturnedAssignmentCodec(), engine.getRuntimeService(), tasks, identities);
        var copies = mock(WorkflowTaskCopyService.class);
        when(copies.prepare(any(), any(), any(), any())).thenReturn(WorkflowTaskCopyService.CopyPlan.empty());
        actions = new WorkflowTaskActionService(engine.getRepositoryService(), engine.getRuntimeService(), tasks, operations,
                identities, mock(WorkflowNotificationService.class), new WorkflowTaskUnclaimPolicy(), copies);
        deploymentId = engine.getRepositoryService().createDeployment()
                .addClasspathResource("bpmn/workflow-candidate-task-creation.bpmn20.xml").deploy().getId();
        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        rule.setProcessKey("candidateTaskCreation"); rule.setActivityId("review"); rule.setActivityName("review");
        rule.setRuleScope("TASK"); rule.setAssignmentMode("ASSIGNEE"); rule.setRuleType("FIXED_USER");
        rule.setTargetIds("301"); rule.setNoMatchPolicy("FAIL"); rule.setRuleVersion(1); rule.setChecksum("returned"); rule.setCreateBy("100");
        artifacts.persist(deploymentId, new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(rule),
                List.of(), List.of(), List.of(), List.of()));
    }

    @AfterEach
    void tearDown()
    {
        Authentication.setAuthenticatedUserId(null);
        if (harness != null) harness.close();
    }

    @Test
    void returnsToNonApprovalApplicantAndRestoresOriginalAssigneeWithRealAudit()
    {
        Task task = start();
        operations.writeAsCurrentUser(() -> returned.enterReturned(task.getId(), task.getProcessInstanceId(), "100"));
        assertThat(reload(task).getAssignee()).isEqualTo("100");
        assertAudit(task, "301", "100");
        actor.set("100");
        operations.writeAsCurrentUser(() -> returned.restoreOrdinary(task.getId(), task.getProcessInstanceId(),
                returned.requireOrdinaryAssignment(task.getId())));
        assertThat(reload(task).getAssignee()).isEqualTo("301");
        assertThat(tasks.getVariableLocal(task.getId(), WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE)).isNull();
        assertAudit(task, "100", "301");
    }

    @ParameterizedTest
    @ValueSource(strings = { "applicant-disabled", "actor-ineligible", "anonymous", "database", "audit" })
    void rejectedReturnLeavesAssignmentMarkersAndAuditUnchanged(String failure)
    {
        Task task = start();
        int before = tasks.getTaskComments(task.getId()).size();
        if ("applicant-disabled".equals(failure)) when(identities.resolveActiveUserIds(List.of("100"), List.of())).thenReturn(Set.of());
        if ("actor-ineligible".equals(failure)) when(identities.resolveApprovalEligibleUserIds(List.of("301"))).thenReturn(Set.of());
        if ("database".equals(failure)) when(identities.resolveActiveUserIds(List.of("100"), List.of()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("identity database unavailable"));
        if ("audit".equals(failure)) doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit storage unavailable"))
                .when(tasks).addComment(anyString(), anyString(), eq(WorkflowUserTaskAuditService.COMMENT_TYPE), anyString());
        var assertion = assertThatThrownBy(() ->
        {
            if ("anonymous".equals(failure)) harness.transactionTemplate().executeWithoutResult(s ->
                    returned.enterReturned(task.getId(), task.getProcessInstanceId(), "100"));
            else operations.writeAsCurrentUser(() -> returned.enterReturned(task.getId(), task.getProcessInstanceId(), "100"));
        });
        if (failure.endsWith("disabled") || failure.endsWith("ineligible")) assertion.isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(400));
        assertThat(reload(task).getAssignee()).isEqualTo("301");
        assertThat(tasks.getVariableLocal(task.getId(), WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE)).isNull();
        assertThat(tasks.getVariableLocal(task.getId(), WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE)).isNull();
        assertThat(tasks.getTaskComments(task.getId())).hasSize(before);
    }

    @Test
    void revokedOriginalAssigneeCannotBeRestoredOnLaterRequest()
    {
        Task task = start();
        operations.writeAsCurrentUser(() -> returned.enterReturned(task.getId(), task.getProcessInstanceId(), "100"));
        when(identities.resolveApprovalEligibleUserIds(anyCollection())).thenReturn(Set.of());
        actor.set("100");
        int before = tasks.getTaskComments(task.getId()).size();
        assertThatThrownBy(() -> operations.writeAsCurrentUser(() -> returned.restoreOrdinary(task.getId(), task.getProcessInstanceId(),
                returned.requireOrdinaryAssignment(task.getId())))).isInstanceOf(ServiceException.class).hasMessage("用户任务办理身份无效");
        assertThat(reload(task).getAssignee()).isEqualTo("100");
        assertThat(tasks.getVariableLocal(task.getId(), WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE)).isEqualTo("100");
        assertThat(tasks.getTaskComments(task.getId())).hasSize(before);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void restoresCandidateGroupOnlyWhileItCanStillBeClaimed(boolean revoked)
    {
        var rule = artifacts.selectTaskParticipantRule(deploymentId, "candidateTaskCreation", "review");
        rule.setAssignmentMode("CANDIDATE"); rule.setRuleType("CANDIDATE_GROUPS"); rule.setTargetIds("ROLE2");
        // 使用新的真实部署与制品，不改写已部署的不可变规则。
        String candidateDeployment = harness.processEngine().getRepositoryService().createDeployment()
                .addClasspathResource("bpmn/workflow-candidate-task-creation.bpmn20.xml").deploy().getId();
        artifacts.persist(candidateDeployment, new WorkflowDeploymentArtifacts(List.of(), List.of(), List.of(), List.of(rule),
                List.of(), List.of(), List.of(), List.of()));
        when(identities.resolveClaimEligibleCandidateGroups(anyCollection())).thenReturn(Set.of("ROLE2"));
        Task task = start();
        assertThat(task.getAssignee()).isNull();
        operations.writeAsCurrentUser(() -> returned.enterReturned(task.getId(), task.getProcessInstanceId(), "100"));
        actor.set("100");
        if (revoked) when(identities.resolveClaimEligibleCandidateGroups(anyCollection())).thenReturn(Set.of());
        Runnable restore = () -> operations.writeAsCurrentUser(() -> returned.restoreOrdinary(task.getId(), task.getProcessInstanceId(),
                returned.requireOrdinaryAssignment(task.getId())));
        if (revoked)
        {
            assertThatThrownBy(restore::run).isInstanceOf(ServiceException.class).hasMessage("用户任务办理身份无效");
            assertThat(reload(task).getAssignee()).isEqualTo("100");
        }
        else
        {
            restore.run();
            assertThat(reload(task).getAssignee()).isNull();
            assertThat(tasks.getIdentityLinksForTask(task.getId())).anySatisfy(link -> assertThat(link.getGroupId()).isEqualTo("ROLE2"));
            assertThat(tasks.getTaskComments(task.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE)).anySatisfy(comment ->
                    assertThat(comment.getFullMessage()).contains("USER_TASK_ASSIGNMENT", "\"actorUserId\":\"100\"")
                            .doesNotContain("assigneeUserId"));
        }
    }

    @Test
    void delegatesResolvesAndTransfersWithRealAssignmentAudit()
    {
        Task task = start();
        actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), 302L, "委派", List.of()));
        assertThat(reload(task).getOwner()).isEqualTo("301");
        assertThat(reload(task).getAssignee()).isEqualTo("302");
        assertAudit(task, "301", "302");
        actor.set("302");
        actions.resolve(new WorkflowTaskResolveRequest(task.getId(), "办结", List.of()));
        assertThat(reload(task).getAssignee()).isEqualTo("301");
        assertAudit(task, "302", "301");
        Task ordinary = start();
        actor.set("301");
        actions.transfer(new WorkflowTaskTransferRequest(ordinary.getId(), 302L, "转办", List.of()));
        assertThat(reload(ordinary).getAssignee()).isEqualTo("302");
        assertAudit(ordinary, "301", "302");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void delegateAndTransferRejectNonApprovalApplicant(boolean transfer)
    {
        Task task = start();
        int before = tasks.getTaskComments(task.getId()).size();
        assertThatThrownBy(() ->
        {
            if (transfer) actions.transfer(new WorkflowTaskTransferRequest(task.getId(), 100L, "非法转办", List.of()));
            else actions.delegate(new WorkflowTaskDelegateRequest(task.getId(), 100L, "非法委派", List.of()));
        }).isInstanceOf(ServiceException.class).hasMessage("工作流请求参数不合法");
        assertThat(reload(task).getAssignee()).isEqualTo("301");
        assertThat(reload(task).getOwner()).isNull();
        assertThat(tasks.getTaskComments(task.getId())).hasSize(before);
    }

    private Task start()
    {
        Authentication.setAuthenticatedUserId("100");
        try
        {
            String id = harness.processEngine().getRuntimeService().createProcessInstanceBuilder()
                    .processDefinitionKey("candidateTaskCreation").businessStatus("running")
                    .variables(Map.of("initiator", "100", "processStatus", "running")).start().getId();
            return tasks.createTaskQuery().processInstanceId(id).singleResult();
        }
        finally { Authentication.setAuthenticatedUserId(null); }
    }

    private Task reload(Task task) { return tasks.createTaskQuery().taskId(task.getId()).singleResult(); }

    private void assertAudit(Task task, String actorId, String assignee)
    {
        assertThat(tasks.getTaskComments(task.getId(), WorkflowUserTaskAuditService.COMMENT_TYPE)).anySatisfy(comment ->
                assertThat(comment.getFullMessage()).contains("\"actorUserId\":\"" + actorId + "\"",
                        "\"assigneeUserId\":\"" + assignee + "\"", "USER_TASK_ASSIGNMENT"));
    }
}
