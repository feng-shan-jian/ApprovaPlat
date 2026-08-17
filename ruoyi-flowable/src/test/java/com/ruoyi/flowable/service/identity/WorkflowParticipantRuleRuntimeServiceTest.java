package com.ruoyi.flowable.service.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;

class WorkflowParticipantRuleRuntimeServiceTest
{
    private RepositoryService repositoryService;
    private RuntimeService runtimeService;
    private WorkflowDeploymentArtifactRepository artifactRepository;
    private WorkflowIdentityMapper identityMapper;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowParticipantResolutionMetrics metrics;
    private WorkflowParticipantRuleRuntimeService service;

    /**
     * 为每个测试创建真实服务对象并替换 Flowable 与数据库边界。
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        artifactRepository = mock(WorkflowDeploymentArtifactRepository.class);
        identityMapper = mock(WorkflowIdentityMapper.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        metrics = mock(WorkflowParticipantResolutionMetrics.class);
        service = new WorkflowParticipantRuleRuntimeService(repositoryService, runtimeService,
                artifactRepository,
                identityMapper, identityResolver, metrics);
    }

    /**
     * 验证指定父部门可按当前有效组织祖先链授权子部门用户发起。
     * @return 无返回值；只按直属部门判断时测试失败
     */
    @Test
    void allowsDepartmentScopeThroughActiveAncestorHierarchy()
    {
        ProcessDefinition definition = definition();
        WfDeployParticipantRule rule = rule("START", "START", "DEPTS", "100");
        when(artifactRepository.selectStartParticipantRule("deploy-1", "expense"))
                .thenReturn(rule);
        when(identityMapper.selectActiveScopeDeptIdsByUserId(7L))
                .thenReturn(List.of(100L, 110L));

        assertThat(service.canStartIfManaged(
                new WorkflowCurrentIdentity("7", Set.of("DEPT110")), definition)).isTrue();
        assertThat(service.assertCanStart(
                new WorkflowCurrentIdentity("7", Set.of("DEPT110")), definition)).isSameAs(rule);
        verifyNoInteractions(metrics);
    }

    /**
     * 验证历史部署没有新快照时返回未托管，由既有 starter identity link 继续授权。
     * @return 无返回值；历史定义被误拒绝时测试失败
     */
    @Test
    void leavesLegacyDeploymentToExistingStarterAuthorization()
    {
        ProcessDefinition definition = definition();
        when(artifactRepository.selectStartParticipantRule("deploy-1", "expense"))
                .thenReturn(null);

        WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("7", Set.of("ROLE3"));
        assertThat(service.canStartIfManaged(actor, definition)).isNull();
        assertThat(service.assertCanStart(actor, definition)).isNull();
        verifyNoInteractions(metrics);
    }

    /**
     * 验证未命中用户范围时记录稳定失败指标并返回 403 子码。
     * @return 无返回值；越权用户被允许或失败指标缺失时测试失败
     */
    @Test
    void rejectsUnauthorizedHumanStartWithStableFailureMetric()
    {
        ProcessDefinition definition = definition();
        WfDeployParticipantRule rule = rule("START", "START", "USERS", "9");
        when(artifactRepository.selectStartParticipantRule("deploy-1", "expense"))
                .thenReturn(rule);

        assertThatThrownBy(() -> service.assertCanStart(
                new WorkflowCurrentIdentity("7", Set.of()), definition))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(403);
                    assertThat(exception.getSubCode()).isEqualTo("PROCESS_START_SCOPE_DENIED");
                });
        verify(metrics).recordFailure("PROCESS_START_SCOPE_DENIED");
    }

    /**
     * 验证直属上级规则在任务创建事务内解析唯一实时办理人，成功事实只进入 Flowable。
     * @return 无返回值；未直接分配唯一上级或额外记录成功指标时测试失败
     */
    @Test
    void resolvesUniqueStarterManagerForCreatedTask()
    {
        DelegateTask task = taskContext();
        WfDeployParticipantRule rule = rule("TASK", "ASSIGNEE", "STARTER_MANAGER", "");
        when(artifactRepository.selectTaskParticipantRule("deploy-1", "expense", "review"))
                .thenReturn(rule);
        when(identityMapper.selectApprovalEligibleManagerUserIdsByUserId(7L))
                .thenReturn(List.of(11L));
        service.resolveCreatedTask(task);

        verify(task).setAssignee("11");
        verifyNoInteractions(metrics);
    }

    /**
     * 验证 CallActivity 子流程任务始终继承根流程服务端发起人，不信任可被输入映射覆盖的子流程变量。
     * @return 无返回值；子流程局部身份覆盖根流程身份时测试失败
     */
    @Test
    void resolvesCallActivityTaskAgainstTrustedRootInitiator()
    {
        DelegateTask task = taskContext("root-instance-1");
        when(task.getVariable("initiator")).thenReturn("999");
        WfDeployParticipantRule rule = rule("TASK", "ASSIGNEE", "STARTER_MANAGER", "");
        when(artifactRepository.selectTaskParticipantRule("deploy-1", "expense", "review"))
                .thenReturn(rule);
        when(identityMapper.selectApprovalEligibleManagerUserIdsByUserId(7L))
                .thenReturn(List.of(11L));
        service.resolveCreatedTask(task);

        verify(runtimeService).getVariable("root-instance-1", "initiator");
        verify(task).setAssignee("11");
        verify(identityMapper, never()).selectApprovalEligibleManagerUserIdsByUserId(999L);
    }

    /**
     * 验证直接办理规则出现多人冲突时不写办理人并以稳定策略失败。
     * @return 无返回值；多人结果被静默选一时测试失败
     */
    @Test
    void rejectsMultipleUsersForDirectAssignment()
    {
        DelegateTask task = taskContext();
        WfDeployParticipantRule rule = rule("TASK", "ASSIGNEE", "STARTER_MANAGER", "");
        when(artifactRepository.selectTaskParticipantRule("deploy-1", "expense", "review"))
                .thenReturn(rule);
        when(identityMapper.selectApprovalEligibleManagerUserIdsByUserId(7L))
                .thenReturn(List.of(11L, 12L));

        assertThatThrownBy(() -> service.resolveCreatedTask(task))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode()).isEqualTo("TASK_PARTICIPANT_NO_MATCH"));
        verify(task, never()).setAssignee(anyString());
        verify(metrics).recordFailure("TASK_PARTICIPANT_NO_MATCH");
    }

    /**
     * 验证表单数值字段的小数用户主键不会被截断，并记录稳定失败指标。
     * @return 无返回值；小数被分配给其他用户、原始异常泄漏或失败指标缺失时测试失败
     */
    @Test
    void recordsInvalidFormUserValueAsStableResolutionFailure()
    {
        DelegateTask task = taskContext();
        WfDeployParticipantRule rule = rule("TASK", "ASSIGNEE", "FORM_USER", "");
        rule.setFormField("approver");
        when(artifactRepository.selectTaskParticipantRule("deploy-1", "expense", "review"))
                .thenReturn(rule);
        when(task.getVariable("approver")).thenReturn(1.5D);

        assertThatThrownBy(() -> service.resolveCreatedTask(task))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(409);
                    assertThat(exception.getSubCode())
                            .isEqualTo("TASK_PARTICIPANT_RESOLUTION_FAILED");
                    assertThat(exception.getCause()).isInstanceOf(ServiceException.class);
                });
        verify(metrics).recordFailure("TASK_PARTICIPANT_RESOLUTION_FAILED");
        verify(task, never()).setAssignee(anyString());
    }

    /**
     * 构造带部署关系的流程定义替身。
     * @return ProcessDefinition，稳定定义上下文
     */
    private ProcessDefinition definition()
    {
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(definition.getId()).thenReturn("expense:1:4");
        when(definition.getDeploymentId()).thenReturn("deploy-1");
        when(definition.getKey()).thenReturn("expense");
        return definition;
    }

    /**
     * 构造任务创建监听器所需的真实定义查询上下文。
     * @return DelegateTask，字段完整的任务替身
     */
    private DelegateTask taskContext()
    {
        return taskContext("instance-1");
    }

    /**
     * 构造指定根实例关系的任务创建监听器上下文。
     * @param rootProcessInstanceId String，Flowable 执行树声明的根流程实例主键
     * @return DelegateTask，带可信根发起人变量的任务替身
     */
    private DelegateTask taskContext(String rootProcessInstanceId)
    {
        ProcessDefinition definition = definition();
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionId("expense:1:4")).thenReturn(query);
        when(query.singleResult()).thenReturn(definition);
        DelegateTask task = mock(DelegateTask.class);
        when(task.getProcessDefinitionId()).thenReturn("expense:1:4");
        when(task.getProcessInstanceId()).thenReturn("instance-1");
        when(task.getExecutionId()).thenReturn("execution-1");
        when(task.getTaskDefinitionKey()).thenReturn("review");
        when(task.getId()).thenReturn("task-1");
        when(task.getCandidates()).thenReturn(Set.of());
        Execution execution = mock(Execution.class);
        when(execution.getProcessInstanceId()).thenReturn("instance-1");
        when(execution.getRootProcessInstanceId()).thenReturn(rootProcessInstanceId);
        ExecutionQuery executionQuery = mock(ExecutionQuery.class);
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
        when(executionQuery.executionId("execution-1")).thenReturn(executionQuery);
        when(executionQuery.singleResult()).thenReturn(execution);
        when(runtimeService.getVariable(rootProcessInstanceId, "initiator")).thenReturn("7");
        return task;
    }

    /**
     * 构造字段完整的部署规则快照。
     * @param scope String，START 或 TASK
     * @param mode String，START、ASSIGNEE 或 CANDIDATE
     * @param type String，规则类型
     * @param targets String，规范目标文本
     * @return WfDeployParticipantRule，运行服务可直接使用的规则
     */
    private WfDeployParticipantRule rule(String scope, String mode, String type,
            String targets)
    {
        WfDeployParticipantRule rule = new WfDeployParticipantRule();
        rule.setRuleId(1L);
        rule.setDeployId("deploy-1");
        rule.setProcessKey("expense");
        rule.setActivityId("TASK".equals(scope) ? "review" : "");
        rule.setRuleScope(scope);
        rule.setAssignmentMode(mode);
        rule.setRuleType(type);
        rule.setTargetIds(targets);
        rule.setNoMatchPolicy("FAIL");
        rule.setRuleVersion(1);
        return rule;
    }
}
