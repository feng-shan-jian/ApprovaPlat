package com.ruoyi.flowable.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * WorkflowProcessAccessService 的对象授权单元测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowProcessAccessServiceTest
{
    @Mock
    private HistoryService historyService;

    @Mock
    private RuntimeService runtimeService;

    @Mock
    private TaskService taskService;

    @Mock
    private WorkflowEngineOperations engineOperations;

    @Mock
    private WorkflowIdentityResolver identityResolver;

    @Mock
    private WfCopyMapper copyMapper;

    @Mock
    private PermissionService permissionService;

    @Mock
    private HistoricProcessInstanceQuery processQuery;

    @Mock
    private ProcessInstanceQuery runtimeProcessQuery;

    @Mock
    private HistoricTaskInstanceQuery historicTaskQuery;

    @Mock
    private TaskQuery taskQuery;

    @Mock
    private HistoricProcessInstance processInstance;

    @Mock
    private ProcessInstance runtimeProcessInstance;

    @Mock
    private Task task;

    private WorkflowProcessAccessService accessService;

    /**
     * 为每个场景建立可执行 Supplier 的引擎边界与默认无授权关系查询。
     *
     * @return 无返回值，每个测试获得独立 Mockito 状态
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        SecurityContextHolder.clearContext();
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of("ROLE2", "DEPT3")));
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(processQuery);
        when(processQuery.processInstanceId(any())).thenReturn(processQuery);
        when(processQuery.involvedUser(any())).thenReturn(processQuery);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(runtimeProcessQuery);
        when(runtimeProcessQuery.processInstanceId(any())).thenReturn(runtimeProcessQuery);
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(taskQuery.taskId(any())).thenReturn(taskQuery);
        when(taskQuery.processInstanceId(any())).thenReturn(taskQuery);
        when(taskQuery.active()).thenReturn(taskQuery);
        when(taskQuery.taskAssignee(any())).thenReturn(taskQuery);
        when(taskQuery.taskUnassigned()).thenReturn(taskQuery);
        when(taskQuery.taskCandidateUser(any())).thenReturn(taskQuery);
        when(taskQuery.taskCandidateGroupIn(any())).thenReturn(taskQuery);
        when(historyService.createHistoricTaskInstanceQuery()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.processInstanceId(any())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.finished()).thenReturn(historicTaskQuery);
        when(historicTaskQuery.taskCompletedBy(any())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.taskAssignee(any())).thenReturn(historicTaskQuery);
        when(historicTaskQuery.taskId(any())).thenReturn(historicTaskQuery);
        accessService = new WorkflowProcessAccessService(historyService, runtimeService,
                taskService, engineOperations, identityResolver, copyMapper, permissionService);
    }

    /**
     * 清理线程级认证上下文，避免管理员权限场景污染后续对象授权测试。
     *
     * @return 无返回值，每个测试结束后移除当前线程 Authentication
     */
    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
    }

    /**
     * 验证空实例 ID 在触发任何引擎或业务表查询前返回稳定 400。
     *
     * @return 无返回值，断言参数门禁和零副作用
     */
    @Test
    void rejectsBlankInstanceIdBeforeReadingData()
    {
        assertThatThrownBy(() -> accessService.requireReadableInstance(" "))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(identityResolver, never()).resolveCurrentIdentity();
        verify(historyService, never()).createHistoricProcessInstanceQuery();
    }

    /**
     * 验证流程发起人可读取实例并获得由服务端历史记录构建的不可变快照。
     *
     * @return 无返回值，断言发起人授权和字段映射
     */
    @Test
    void allowsInitiatorAndReturnsServerSnapshot()
    {
        Date startTime = Date.from(Instant.parse("2026-07-25T10:15:30Z"));
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processInstance.getId()).thenReturn("instance-1");
        when(processInstance.getProcessDefinitionId()).thenReturn("definition-1");
        when(processInstance.getDeploymentId()).thenReturn("deployment-1");
        when(processInstance.getBusinessKey()).thenReturn("business-1");
        when(processInstance.getStartUserId()).thenReturn("7");
        when(processInstance.getStartTime()).thenReturn(startTime);
        when(processInstance.getBusinessStatus()).thenReturn("terminated");
        when(processInstance.getState()).thenReturn("COMPLETED");

        WorkflowProcessAccessSnapshot snapshot = accessService.requireReadableInstance("instance-1");

        assertThat(snapshot.processInstanceId()).isEqualTo("instance-1");
        assertThat(snapshot.processDefinitionId()).isEqualTo("definition-1");
        assertThat(snapshot.startTime()).isEqualTo(startTime.toInstant());
        assertThat(snapshot.businessStatus()).isEqualTo("terminated");
        assertThat(snapshot.state()).isEqualTo("COMPLETED");
        verify(copyMapper, never()).countActiveByInstanceAndUser(any(), any());
    }

    /**
     * 验证缺少历史参与关系的挂起任务办理人仍可读取实例，且快照返回实时挂起状态。
     *
     * @return 无返回值，读授权错误排除挂起任务或详情误报 RUNNING 时测试失败
     */
    @Test
    void allowsSuspendedAssigneeWithoutHistoricParticipantFallback()
    {
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processQuery.count()).thenReturn(0L);
        when(processInstance.getId()).thenReturn("instance-suspended-assignee");
        when(processInstance.getStartUserId()).thenReturn("9");
        when(processInstance.getState()).thenReturn("RUNNING");
        when(taskQuery.count()).thenReturn(1L);
        when(runtimeProcessQuery.singleResult()).thenReturn(runtimeProcessInstance);
        when(runtimeProcessInstance.getId()).thenReturn("instance-suspended-assignee");
        when(runtimeProcessInstance.isSuspended()).thenReturn(true);

        WorkflowProcessAccessSnapshot snapshot = accessService
                .requireReadableInstance("instance-suspended-assignee");

        assertThat(snapshot.state()).isEqualTo("suspended");
        verify(taskQuery).taskAssignee("7");
        verify(taskQuery, never()).active();
        verify(taskQuery, never()).taskCandidateUser("7");
    }

    /**
     * 验证挂起实例的候选组成员仍可读取实例，且授权继续绑定真实候选组和实例条件。
     *
     * @return 无返回值，挂起过滤导致候选组详情权限丢失时测试失败
     */
    @Test
    void allowsSuspendedCandidateGroupReadingInstance()
    {
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processQuery.count()).thenReturn(0L);
        when(processInstance.getId()).thenReturn("instance-suspended-group");
        when(processInstance.getStartUserId()).thenReturn("9");
        when(processInstance.getState()).thenReturn("RUNNING");
        when(taskQuery.count()).thenReturn(0L, 0L, 1L);
        when(runtimeProcessQuery.singleResult()).thenReturn(runtimeProcessInstance);
        when(runtimeProcessInstance.getId()).thenReturn("instance-suspended-group");
        when(runtimeProcessInstance.isSuspended()).thenReturn(true);

        WorkflowProcessAccessSnapshot snapshot = accessService
                .requireReadableInstance("instance-suspended-group");

        assertThat(snapshot.state()).isEqualTo("suspended");
        verify(taskQuery, atLeastOnce()).processInstanceId("instance-suspended-group");
        verify(taskQuery).taskCandidateUser("7");
        verify(taskQuery).taskCandidateGroupIn(Set.of("ROLE2", "DEPT3"));
        verify(taskQuery, never()).active();
    }

    /**
     * 验证主数据核验通过的目标框架超级管理员可执行受控实例审计读取。
     *
     * @return 无返回值，断言管理员不依赖伪造参与关系
     */
    @Test
    void allowsVerifiedSuperAdministrator()
    {
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processInstance.getId()).thenReturn("instance-admin");
        when(processInstance.getStartUserId()).thenReturn("9");

        WorkflowProcessAccessSnapshot snapshot = accessService.requireReadableInstance("instance-admin");

        assertThat(snapshot.processInstanceId()).isEqualTo("instance-admin");
        verify(copyMapper, never()).countActiveByInstanceAndUser(any(), any());
    }

    /**
     * 验证无发起、参与、办理、候选或抄送关系的用户被稳定拒绝。
     *
     * @return 无返回值，断言对象级 403 不会退化为菜单级放行
     */
    @Test
    void deniesUnrelatedUser()
    {
        stubUnrelatedProcess("instance-2");
        when(copyMapper.countActiveByInstanceAndUser("instance-2", 7L)).thenReturn(0L);

        assertThatThrownBy(() -> accessService.requireReadableInstance("instance-2"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * 验证正式 wf_copy 中的当前有效收件记录能够授予实例读取权限。
     *
     * @return 无返回值，断言抄送授权只使用当前登录用户 ID
     */
    @Test
    void allowsActiveCopyRecipient()
    {
        stubUnrelatedProcess("instance-3");
        when(copyMapper.countActiveByInstanceAndUser("instance-3", 7L)).thenReturn(1L);

        WorkflowProcessAccessSnapshot snapshot = accessService.requireReadableInstance("instance-3");

        assertThat(snapshot.processInstanceId()).isEqualTo("instance-3");
        verify(copyMapper).countActiveByInstanceAndUser("instance-3", 7L);
    }

    /**
     * 验证任务转办或委派后，Flowable 记录的真实完成人仍可读取所属流程实例。
     *
     * @return 无返回值，断言 completedBy 优先于任务结束时 assignee 的真实办理授权
     */
    @Test
    void allowsRealCompleterAfterReassignment()
    {
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processQuery.count()).thenReturn(0L);
        when(processInstance.getId()).thenReturn("instance-completed");
        when(processInstance.getStartUserId()).thenReturn("9");
        when(taskQuery.count()).thenReturn(0L);
        when(historicTaskQuery.count()).thenReturn(1L);

        WorkflowProcessAccessSnapshot snapshot =
                accessService.requireReadableInstance("instance-completed");

        assertThat(snapshot.processInstanceId()).isEqualTo("instance-completed");
        verify(historicTaskQuery).taskCompletedBy("7");
        verify(historicTaskQuery, never()).taskAssignee("7");
        verify(copyMapper, never()).countActiveByInstanceAndUser(any(), any());
    }

    /**
     * 验证任务读取使用任务记录中的真实实例 ID，而不是调用方可伪造的实例关联。
     *
     * @return 无返回值，断言活动办理人可读取真实任务快照
     */
    @Test
    void resolvesTaskInstanceRelationshipFromEngine()
    {
        when(taskQuery.singleResult()).thenReturn(task);
        when(task.getId()).thenReturn("task-1");
        when(task.getProcessInstanceId()).thenReturn("real-instance");
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getAssignee()).thenReturn("7");
        when(task.getClaimedBy()).thenReturn("7");
        when(task.getClaimTime()).thenReturn(Date.from(
                Instant.parse("2026-07-25T10:20:00Z")));
        when(taskQuery.count()).thenReturn(1L);
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processInstance.getId()).thenReturn("real-instance");
        when(processInstance.getStartUserId()).thenReturn("9");

        WorkflowTaskAccessSnapshot snapshot = accessService.requireReadableTask("task-1");

        assertThat(snapshot.processInstanceId()).isEqualTo("real-instance");
        assertThat(snapshot.assignee()).isEqualTo("7");
        assertThat(snapshot.claimedBy()).isEqualTo("7");
        assertThat(snapshot.claimTime()).isEqualTo(
                Instant.parse("2026-07-25T10:20:00Z"));
        // 同一历史查询 mock 还会参与对象授权，本断言只验证任务关联始终来自引擎中的真实实例 ID。
        verify(processQuery, atLeastOnce()).processInstanceId("real-instance");
    }

    /**
     * 验证流程发起人不能凭实例参与关系读取已分配给他人的活动任务当前变量。
     *
     * @return 无返回值，断言活动任务在读取任何候选关系前稳定返回 403
     */
    @Test
    void deniesInitiatorReadingAnotherAssigneesActiveTask()
    {
        stubActiveTask("task-private", "instance-private", "8", "7");

        assertThatThrownBy(() -> accessService.requireReadableTask("task-private"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN));

        verify(taskQuery, never()).taskCandidateUser("7");
        verify(copyMapper, never()).countActiveByInstanceAndUser(any(), any());
    }

    /**
     * 验证未认领运行时任务（包含挂起）允许真实直接候选用户读取当前表单和安全变量。
     *
     * @return 无返回值，断言精确 taskId、实例和候选用户查询共同生效且不排除挂起任务
     */
    @Test
    void allowsDirectCandidateReadingUnassignedActiveTask()
    {
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of()));
        stubActiveTask("task-direct", "instance-direct", null, "9");
        when(taskQuery.count()).thenReturn(1L);

        WorkflowTaskAccessSnapshot snapshot = accessService.requireReadableTask("task-direct");

        assertThat(snapshot.taskId()).isEqualTo("task-direct");
        assertThat(snapshot.active()).isTrue();
        verify(taskQuery).taskCandidateUser("7");
        verify(taskQuery).taskUnassigned();
        verify(taskQuery, never()).taskCandidateGroupIn(any());
        verify(taskQuery, never()).active();
    }

    /**
     * 验证未认领运行时任务（包含挂起）允许当前用户有效角色或部门候选组读取当前表单。
     *
     * @return 无返回值，断言候选用户与候选组在同一精确任务查询中按并集授权
     */
    @Test
    void allowsCandidateGroupReadingUnassignedActiveTask()
    {
        stubActiveTask("task-group", "instance-group", null, "9");
        when(taskQuery.count()).thenReturn(1L);

        WorkflowTaskAccessSnapshot snapshot = accessService.requireReadableTask("task-group");

        assertThat(snapshot.taskId()).isEqualTo("task-group");
        verify(taskQuery).taskCandidateUser("7");
        verify(taskQuery).taskCandidateGroupIn(Set.of("ROLE2", "DEPT3"));
        verify(taskQuery, never()).active();
    }

    /**
     * 验证发起人如果不是未认领任务候选身份，仍不能读取活动任务当前表单。
     *
     * @return 无返回值，断言实例发起关系不会旁路精确任务候选授权
     */
    @Test
    void deniesInitiatorReadingUnassignedTaskWithoutCandidateIdentity()
    {
        stubActiveTask("task-unrelated", "instance-owned", null, "7");
        when(taskQuery.count()).thenReturn(0L);

        assertThatThrownBy(() -> accessService.requireReadableTask("task-unrelated"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * 验证经过认证且持有流程状态管理权限的流程管理员可跨实例读取活动任务。
     *
     * @return 无返回值，断言权限必须来自与 WorkflowCurrentIdentity 同一用户的认证主体
     */
    @Test
    void allowsExplicitWorkflowAdministratorReadingActiveTask()
    {
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("9", Set.of()));
        authenticate(9L, Set.of("workflow:process:state"));
        when(permissionService.hasPermi("workflow:process:state")).thenReturn(true);
        stubActiveTask("task-admin", "instance-admin-task", "8", "7");

        WorkflowProcessAccessSnapshot instance =
                accessService.requireReadableInstance("instance-admin-task");
        WorkflowTaskAccessSnapshot snapshot = accessService.requireReadableTask("task-admin");

        assertThat(instance.processInstanceId()).isEqualTo("instance-admin-task");
        assertThat(snapshot.taskId()).isEqualTo("task-admin");
        assertThat(snapshot.assignee()).isEqualTo("8");
    }

    /**
     * 验证 Token 仍含 state 但正式角色菜单已撤权时，不得继续跨实例读取他人任务。
     *
     * @return 无返回值，实时权限拒绝未生效或对象关系被旧 Token 绕过时测试失败
     */
    @Test
    void rejectsRevokedAdministrativeReadFromExistingToken()
    {
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("9", Set.of()));
        authenticate(9L, Set.of("workflow:process:state", "workflow:process:query"));
        when(permissionService.hasPermi("workflow:process:state")).thenReturn(false);
        stubActiveTask("task-revoked-admin", "instance-revoked-admin", "8", "7");

        assertThatThrownBy(() -> accessService.requireReadableTask("task-revoked-admin"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * 验证其他用户的管理员权限不能与当前工作流身份拼接形成越权。
     *
     * @return 无返回值，断言认证主体 ID 不一致时仍稳定返回 403
     */
    @Test
    void rejectsAdministrativePermissionFromDifferentAuthenticatedUser()
    {
        authenticate(9L, Set.of("workflow:process:state"));
        stubActiveTask("task-mismatched-admin", "instance-mismatched-admin", "8", "7");

        assertThatThrownBy(() -> accessService.requireReadableTask("task-mismatched-admin"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    /**
     * 配置一条真实活动任务及其所属历史实例关系。
     *
     * @param taskId String，活动任务主键
     * @param instanceId String，任务所属流程实例主键
     * @param assignee String，当前办理人；null 表示尚未认领
     * @param startUserId String，流程实例发起人主键
     * @return 无返回值，后续测试可单独配置候选查询结果
     */
    private void stubActiveTask(String taskId, String instanceId, String assignee,
            String startUserId)
    {
        when(taskQuery.singleResult()).thenReturn(task);
        when(task.getId()).thenReturn(taskId);
        when(task.getProcessInstanceId()).thenReturn(instanceId);
        when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getAssignee()).thenReturn(assignee);
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processInstance.getId()).thenReturn(instanceId);
        when(processInstance.getStartUserId()).thenReturn(startUserId);
    }

    /**
     * 将指定用户和权限写入 Spring Security 当前线程认证上下文。
     *
     * @param userId Long，认证用户主键
     * @param permissions Set&lt;String&gt;，服务端已加载的权限集合
     * @return 无返回值，认证主体仅在当前测试线程有效
     */
    private void authenticate(Long userId, Set<String> permissions)
    {
        SysUser user = new SysUser();
        user.setUserId(userId);
        LoginUser loginUser = new LoginUser(userId, null, user, permissions);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    /**
     * 配置一个存在但与当前用户没有任何已知关系的历史流程实例。
     *
     * @param instanceId String，测试实例 ID
     * @return 无返回值，后续场景可单独配置抄送结果
     */
    private void stubUnrelatedProcess(String instanceId)
    {
        when(processQuery.singleResult()).thenReturn(processInstance);
        when(processQuery.count()).thenReturn(0L);
        when(processInstance.getId()).thenReturn(instanceId);
        when(processInstance.getStartUserId()).thenReturn("9");
        when(taskQuery.count()).thenReturn(0L);
        when(historicTaskQuery.count()).thenReturn(0L);
    }
}
