package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.flowable.engine.HistoryService;
import org.flowable.engine.IdentityService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.TaskQuery;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.flowable.variable.api.history.HistoricVariableInstanceQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowInstanceState;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceStateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowHistoryDeletionView;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceStateView;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceTerminateView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 流程实例状态、双权限终止和历史删除领域测试。
 */
class WorkflowProcessInstanceServiceTest
{
    private static final String INSTANCE_ID = "instance-1";

    private HistoryService historyService;

    private RuntimeService runtimeService;

    private TaskService taskService;

    private WfAttachmentMapper attachmentMapper;

    private WfCopyMapper copyMapper;

    private WfControlledLoopExecutionMapper controlledLoopExecutionMapper;

    private IdentityService identityService;

    private WorkflowIdentityResolver identityResolver;

    private PermissionService permissionService;

    private WorkflowProcessInstanceService service;

    /**
     * 为每个测试创建独立 Flowable 公共 API、认证上下文和领域服务替身。
     *
     * @return 无返回值，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        // 单元测试使用 Flowable 公共 API 替身，显式绑定生产写边界要求的可重复读事务特征。
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
        historyService = mock(HistoryService.class);
        runtimeService = mock(RuntimeService.class);
        taskService = mock(TaskService.class);
        attachmentMapper = mock(WfAttachmentMapper.class);
        copyMapper = mock(WfCopyMapper.class);
        controlledLoopExecutionMapper = mock(WfControlledLoopExecutionMapper.class);
        identityService = mock(IdentityService.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        permissionService = mock(PermissionService.class);
        WorkflowAuthenticationContext authenticationContext =
                new WorkflowAuthenticationContext(identityService, new WorkflowIdentityCodec());
        WorkflowEngineOperations engineOperations = new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
        service = new WorkflowProcessInstanceService(engineOperations, historyService,
                runtimeService, taskService, attachmentMapper, copyMapper,
                controlledLoopExecutionMapper, permissionService);
    }

    /**
     * 清理 Spring Security 与事务线程上下文，防止权限或事务特征污染其他测试。
     *
     * @return 无返回值，清理后当前线程不再保存登录用户或模拟事务状态
     */
    @AfterEach
    void tearDown()
    {
        SecurityContextHolder.clearContext();
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证流程管理员可终止其他用户的运行实例并写入 terminated 全链路审计。
     *
     * @return 无返回值，变量、comment、删除原因或最终复核不一致时测试失败
     */
    @Test
    void administratorTerminatesAnotherUsersInstanceWithAudit()
    {
        setCurrentUser("9", Set.of("workflow:process:terminate"));
        TerminationFixture fixture = stubTermination("7", false, "terminated");

        WorkflowInstanceTerminateView result = service.terminate(
                new WorkflowInstanceTerminateRequest(INSTANCE_ID, "业务已失效"));

        assertThat(result).isEqualTo(new WorkflowInstanceTerminateView(
                INSTANCE_ID, "terminated", "9", false));
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "terminated");
        verify(runtimeService).setVariable(INSTANCE_ID, "processStatus", "terminated");
        verify(runtimeService).deleteProcessInstance(eq(INSTANCE_ID),
                eq("terminated: 业务已失效"));
        verify(runtimeService, never()).activateProcessInstanceById(anyString());
        assertThat(fixture.auditMessage()).hasValueSatisfying(message -> assertThat(message)
                .contains("\"action\":\"TERMINATE\"", "\"actorUserId\":\"9\"",
                        "\"processStatus\":\"terminated\"",
                        "\"wasSuspended\":false"));
        verify(identityService).setAuthenticatedUserId("9");
        verify(identityService).setAuthenticatedUserId(null);
    }

    /**
     * 验证客户端传入 CallActivity 子实例时按根发起人授权，并只删除完整根业务树。
     *
     * @return 无返回值，子实例被单独删除、授权对象错误或写后存在运行残留时测试失败
     */
    @Test
    void childInstanceTerminationResolvesAndDeletesRootProcessTree()
    {
        String rootInstanceId = "root-1";
        String childInstanceId = "child-1";
        setCurrentUser("7", Set.of("workflow:process:cancel"));

        HistoricProcessInstance childHistoric = historic(childInstanceId, "99", false,
                rootInstanceId);
        HistoricProcessInstance rootHistoric = historic(rootInstanceId, "7", false, null);
        HistoricProcessInstance finishedRoot = historic(rootInstanceId, "7", true, null);
        when(finishedRoot.getDeleteReason()).thenReturn("canceled: reason");
        stubHistoricSequence(childHistoric, rootHistoric, finishedRoot);

        ProcessInstance childInstance = process(childInstanceId, rootInstanceId,
                "super-execution-1", false);
        ProcessInstance rootInstance = process(rootInstanceId, rootInstanceId, null, false);
        ProcessInstanceQuery childQuery = processQueryReturning(childInstanceId, childInstance);
        ProcessInstanceQuery rootQuery = processQueryReturning(rootInstanceId, rootInstance);
        ProcessInstanceQuery treeQuery = mock(ProcessInstanceQuery.class);
        when(treeQuery.processInstanceIds(any())).thenReturn(treeQuery);
        when(treeQuery.list()).thenReturn(List.of(rootInstance, childInstance));
        ProcessInstanceQuery remainingChild = processQueryCounting(childInstanceId, 0L);
        ProcessInstanceQuery remainingRoot = processQueryCounting(rootInstanceId, 0L);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(childQuery, rootQuery,
                treeQuery, remainingChild, remainingRoot);

        // execution 快照显式包含子实例，保证写后门禁会同时核对根和子运行数据。
        Execution childExecution = mock(Execution.class);
        when(childExecution.getProcessInstanceId()).thenReturn(childInstanceId);
        when(childExecution.getRootProcessInstanceId()).thenReturn(rootInstanceId);
        ExecutionQuery treeExecutionQuery = mock(ExecutionQuery.class);
        when(treeExecutionQuery.rootProcessInstanceId(rootInstanceId))
                .thenReturn(treeExecutionQuery);
        when(treeExecutionQuery.list()).thenReturn(List.of(childExecution));
        ExecutionQuery remainingExecutionQuery = mock(ExecutionQuery.class);
        when(remainingExecutionQuery.rootProcessInstanceId(rootInstanceId))
                .thenReturn(remainingExecutionQuery);
        when(remainingExecutionQuery.count()).thenReturn(0L);
        when(runtimeService.createExecutionQuery()).thenReturn(treeExecutionQuery,
                remainingExecutionQuery);

        HistoricVariableInstance variable = mock(HistoricVariableInstance.class);
        when(variable.getValue()).thenReturn("canceled");
        HistoricVariableInstanceQuery variableQuery = mock(HistoricVariableInstanceQuery.class);
        when(historyService.createHistoricVariableInstanceQuery()).thenReturn(variableQuery);
        when(variableQuery.processInstanceId(rootInstanceId)).thenReturn(variableQuery);
        when(variableQuery.variableName("processStatus")).thenReturn(variableQuery);
        when(variableQuery.singleResult()).thenReturn(variable);

        AtomicReference<String> auditMessage = new AtomicReference<>();
        Comment comment = mock(Comment.class);
        when(comment.getId()).thenReturn("comment-root");
        when(comment.getFullMessage()).thenAnswer(invocation -> auditMessage.get());
        when(taskService.addComment(eq(null), eq(rootInstanceId), eq("6"), anyString()))
                .thenAnswer(invocation ->
                {
                    auditMessage.set(invocation.getArgument(3));
                    return comment;
                });
        when(taskService.getProcessInstanceComments(rootInstanceId, "6"))
                .thenReturn(List.of(comment));
        TaskQuery remainingTaskQuery = mock(TaskQuery.class);
        when(remainingTaskQuery.processInstanceIdIn(anyCollection()))
                .thenReturn(remainingTaskQuery);
        when(remainingTaskQuery.count()).thenReturn(0L);
        when(taskService.createTaskQuery()).thenReturn(remainingTaskQuery);

        WorkflowInstanceTerminateView result = service.terminate(
                new WorkflowInstanceTerminateRequest(childInstanceId, "子流程入口取消整体"));

        assertThat(result.instanceId()).isEqualTo(rootInstanceId);
        assertThat(result.processStatus()).isEqualTo("canceled");
        verify(runtimeService).setVariable(rootInstanceId, "processStatus", "canceled");
        verify(runtimeService).updateBusinessStatus(rootInstanceId, "canceled");
        verify(runtimeService).deleteProcessInstance(rootInstanceId,
                "canceled: 子流程入口取消整体");
        verify(runtimeService, never()).deleteProcessInstance(eq(childInstanceId), anyString());
        assertThat(auditMessage.get()).contains(
                "\"requestedInstanceId\":\"child-1\"",
                "\"rootInstanceId\":\"root-1\"",
                "\"processTreeInstanceCount\":2");
    }

    /**
     * 验证只有 cancel 权限的真实发起人可取消本人运行实例并写入 canceled。
     *
     * @return 无返回值，发起人对象校验或最终状态错误时测试失败
     */
    @Test
    void starterCancelsOwnInstance()
    {
        setCurrentUser("7", Set.of("workflow:process:cancel"));
        TerminationFixture fixture = stubTermination("7", false, "canceled");

        WorkflowInstanceTerminateView result = service.terminate(
                new WorkflowInstanceTerminateRequest(INSTANCE_ID, "本人撤销"));

        assertThat(result.processStatus()).isEqualTo("canceled");
        verify(runtimeService).updateBusinessStatus(INSTANCE_ID, "canceled");
        verify(runtimeService).setVariable(INSTANCE_ID, "processStatus", "canceled");
        assertThat(fixture.auditMessage().get()).contains("\"action\":\"CANCEL\"");
    }

    /**
     * 验证 cancel 权限不能越权取消他人实例，且拒绝发生在任何运行数据写入之前。
     *
     * @return 无返回值，越权未返回 403 或发生引擎写入时测试失败
     */
    @Test
    void starterCannotCancelAnotherUsersInstance()
    {
        setCurrentUser("7", Set.of("workflow:process:cancel"));
        stubRunningLookup("8", false);

        assertBusinessError(() -> service.terminate(
                new WorkflowInstanceTerminateRequest(INSTANCE_ID, "越权请求")),
                HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        verify(runtimeService, never()).updateBusinessStatus(anyString(), anyString());
        verify(runtimeService, never()).deleteProcessInstance(anyString(), anyString());
        verifyNoInteractions(taskService);
    }

    /**
     * 验证旧 Token 同时保留 cancel 与 terminate 时，实时撤销 terminate 仍会拒绝跨实例终止。
     *
     * @return 无返回值，领域层信任旧 Token 或产生任一运行时写副作用时测试失败
     */
    @Test
    void rejectsRevokedTerminatePermissionEvenWhenCancelStillPassesControllerAnyPermission()
    {
        setCurrentUser("9", Set.of("workflow:process:cancel", "workflow:process:terminate"));
        when(permissionService.hasPermi("workflow:process:terminate")).thenReturn(false);
        when(permissionService.hasPermi("workflow:process:cancel")).thenReturn(true);
        stubRunningLookup("7", false);

        assertBusinessError(() -> service.terminate(
                new WorkflowInstanceTerminateRequest(INSTANCE_ID, "旧Token不得终止")),
                HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        verify(runtimeService, never()).updateBusinessStatus(anyString(), anyString());
        verify(runtimeService, never()).deleteProcessInstance(anyString(), anyString());
        verifyNoInteractions(taskService);
    }

    /**
     * 验证 terminate 权限按管理员语义优先，即使管理员恰好也是发起人也写 terminated。
     *
     * @return 无返回值，双权限分流顺序错误时测试失败
     */
    @Test
    void terminatePermissionDoesNotDowngradeAdministratorToCancel()
    {
        setCurrentUser("7", Set.of("workflow:process:cancel", "workflow:process:terminate"));
        stubTermination("7", false, "terminated");

        WorkflowInstanceTerminateView result = service.terminate(
                new WorkflowInstanceTerminateRequest(INSTANCE_ID, "管理员终止"));

        assertThat(result.processStatus()).isEqualTo("terminated");
        verify(runtimeService).setVariable(INSTANCE_ID, "processStatus", "terminated");
    }

    /**
     * 验证挂起实例在同一写链路中先受控激活，再写审计并终止。
     *
     * @return 无返回值，激活顺序或原状态审计缺失时测试失败
     */
    @Test
    void activatesSuspendedInstanceInsideTerminateTransaction()
    {
        setCurrentUser("9", Set.of("workflow:process:terminate"));
        TerminationFixture fixture = stubTermination("7", true, "terminated");

        WorkflowInstanceTerminateView result = service.terminate(
                new WorkflowInstanceTerminateRequest(INSTANCE_ID, "挂起流程终止"));

        assertThat(result.wasSuspended()).isTrue();
        verify(runtimeService).activateProcessInstanceById(INSTANCE_ID);
        assertThat(fixture.auditMessage().get()).contains("\"wasSuspended\":true");
    }

    /**
     * 验证流程管理员可挂起激活实例，并在写后重新核验真实状态。
     *
     * @return 无返回值，状态命令或 changed 结果错误时测试失败
     */
    @Test
    void administratorSuspendsRunningInstance()
    {
        setCurrentUser("9", Set.of("workflow:process:state"));
        HistoricProcessInstance historic = historic(INSTANCE_ID, "7", false, null);
        ProcessInstance active = process(false);
        ProcessInstance suspended = process(true);
        stubHistoricSequence(historic);
        stubStateTree(active, suspended);

        WorkflowInstanceStateView result = service.updateState(
                new WorkflowInstanceStateRequest(INSTANCE_ID,
                        WorkflowInstanceState.SUSPENDED));

        assertThat(result).isEqualTo(new WorkflowInstanceStateView(INSTANCE_ID,
                WorkflowInstanceState.SUSPENDED, true));
        verify(runtimeService).suspendProcessInstanceById(INSTANCE_ID);
    }

    /**
     * 验证目标状态与当前状态一致时幂等成功且不执行 Flowable 状态命令。
     *
     * @return 无返回值，幂等请求产生额外写入时测试失败
     */
    @Test
    void returnsUnchangedForSameInstanceState()
    {
        setCurrentUser("9", Set.of("workflow:process:state"));
        stubHistoricSequence(historic(INSTANCE_ID, "7", false, null));
        ProcessInstance suspended = process(true);
        stubStateTree(suspended);

        WorkflowInstanceStateView result = service.updateState(
                new WorkflowInstanceStateRequest(INSTANCE_ID,
                        WorkflowInstanceState.SUSPENDED));

        assertThat(result.changed()).isFalse();
        verify(runtimeService, never()).suspendProcessInstanceById(anyString());
        verify(runtimeService, never()).activateProcessInstanceById(anyString());
    }

    /**
     * 验证从 CallActivity 子实例发起挂起时按根对象定位并原子挂起完整执行树。
     *
     * @return 无返回值，子实例单独挂起、根授权边界丢失或写后状态未完整复核时测试失败
     */
    @Test
    void suspendsCompleteCallActivityTreeFromChildInstance()
    {
        String rootInstanceId = "root-state";
        String childInstanceId = "child-state";
        setCurrentUser("9", Set.of("workflow:process:state"));
        stubHistoricSequence(historic(childInstanceId, "7", false, rootInstanceId));

        ProcessInstance childActive = process(childInstanceId, rootInstanceId,
                "super-state", false);
        ProcessInstance rootActive = process(rootInstanceId, rootInstanceId, null, false);
        ProcessInstance childSuspended = process(childInstanceId, rootInstanceId,
                "super-state", true);
        ProcessInstance rootSuspended = process(rootInstanceId, rootInstanceId, null, true);
        ProcessInstanceQuery requestedQuery = processQueryReturning(childInstanceId, childActive);
        ProcessInstanceQuery rootQuery = processQueryReturning(rootInstanceId, rootActive);
        ProcessInstanceQuery treeBefore = processInstancesQuery(
                List.of(rootActive, childActive));
        ProcessInstanceQuery treeAfter = processInstancesQuery(
                List.of(rootSuspended, childSuspended));
        when(runtimeService.createProcessInstanceQuery()).thenReturn(
                requestedQuery, rootQuery, treeBefore, treeAfter);

        Execution childExecution = mock(Execution.class);
        when(childExecution.getProcessInstanceId()).thenReturn(childInstanceId);
        when(childExecution.getRootProcessInstanceId()).thenReturn(rootInstanceId);
        ExecutionQuery executionQuery = mock(ExecutionQuery.class);
        when(executionQuery.rootProcessInstanceId(rootInstanceId)).thenReturn(executionQuery);
        when(executionQuery.list()).thenReturn(List.of(childExecution));
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);

        WorkflowInstanceStateView result = service.updateState(
                new WorkflowInstanceStateRequest(childInstanceId,
                        WorkflowInstanceState.SUSPENDED));

        assertThat(result).isEqualTo(new WorkflowInstanceStateView(childInstanceId,
                WorkflowInstanceState.SUSPENDED, true));
        verify(runtimeService).suspendProcessInstanceById(childInstanceId);
        verify(runtimeService).suspendProcessInstanceById(rootInstanceId);
    }

    /**
     * 验证缺少 state 权限的直接领域调用在查询实例前返回 403。
     *
     * @return 无返回值，领域层权限可被绕过时测试失败
     */
    @Test
    void rejectsStateChangeWithoutPermission()
    {
        setCurrentUser("9", Set.of("workflow:process:cancel"));

        assertBusinessError(() -> service.updateState(new WorkflowInstanceStateRequest(
                INSTANCE_ID, WorkflowInstanceState.ACTIVE)), HttpStatus.FORBIDDEN);

        verifyNoInteractions(historyService, runtimeService, taskService, attachmentMapper,
                copyMapper);
    }

    /**
     * 验证历史删除先完整预检根实例和子流程，再原子清理抄送与历史数据。
     *
     * @return 无返回值，子流程展开、删除根或最终计数不一致时测试失败
     */
    @Test
    void deletesFinishedRootChildrenAndCopiesAtomically()
    {
        setCurrentUser("9", Set.of("workflow:process:remove"));
        HistoricProcessInstance root = historic("root-1", "7", true, null);
        HistoricProcessInstance child = historic("child-1", "7", true, "root-1");
        HistoricProcessInstanceQuery findRoot = queryReturning(root);
        HistoricProcessInstanceQuery rootChildren = queryListing(List.of(child));
        HistoricProcessInstanceQuery childChildren = queryListing(List.of());
        HistoricProcessInstanceQuery remaining = queryCounting(0);
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(
                findRoot, rootChildren, childChildren, remaining);
        when(copyMapper.countActiveByInstanceIds(any())).thenReturn(2L, 0L);
        when(copyMapper.logicalDeleteByInstanceIds(any(), eq("9"))).thenReturn(2);
        when(controlledLoopExecutionMapper.countByProcessInstanceIds(any()))
                .thenReturn(3L, 0L);
        when(controlledLoopExecutionMapper.deleteByProcessInstanceIds(any())).thenReturn(3);

        WorkflowHistoryDeletionView result = service.deleteCompletedHistory(List.of("root-1"));

        assertThat(result).isEqualTo(new WorkflowHistoryDeletionView(1, 2, 2));
        verify(attachmentMapper).countBoundByProcessInstanceIds(
                eq(Set.of("root-1", "child-1")));
        verify(historyService).deleteHistoricProcessInstance("root-1");
        verify(historyService, never()).deleteHistoricProcessInstance("child-1");
        verify(copyMapper).logicalDeleteByInstanceIds(
                eq(Set.of("root-1", "child-1")), eq("9"));
        verify(controlledLoopExecutionMapper).deleteByProcessInstanceIds(
                eq(Set.of("root-1", "child-1")));
    }

    /**
     * 验证整批历史删除中任一实例仍在运行时不会删除抄送或任何历史。
     *
     * @return 无返回值，预检不是全有或全无时测试失败
     */
    @Test
    void rejectsWholeHistoryBatchBeforeWritesWhenOneInstanceRuns()
    {
        setCurrentUser("9", Set.of("workflow:process:remove"));
        HistoricProcessInstanceQuery first = queryReturning(
                historic("finished-1", "7", true, null));
        HistoricProcessInstanceQuery second = queryReturning(
                historic("running-1", "7", false, null));
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(first, second);

        assertBusinessError(() -> service.deleteCompletedHistory(
                List.of("finished-1", "running-1")), HttpStatus.CONFLICT);

        verifyNoInteractions(attachmentMapper, copyMapper, controlledLoopExecutionMapper);
        verify(historyService, never()).deleteHistoricProcessInstance(anyString());
    }

    /**
     * 验证删除图中任一实例存在已绑定附件时，在抄送和 Flowable 写入前整体拒绝。
     *
     * @return 无返回值，附件审计证据可能失去实例历史时测试失败
     */
    @Test
    void rejectsHistoryDeleteWhenBoundAttachmentExists()
    {
        setCurrentUser("9", Set.of("workflow:process:remove"));
        HistoricProcessInstance root = historic("root-1", "7", true, null);
        HistoricProcessInstance child = historic("child-1", "7", true, "root-1");
        HistoricProcessInstanceQuery findRoot = queryReturning(root);
        HistoricProcessInstanceQuery rootChildren = queryListing(List.of(child));
        HistoricProcessInstanceQuery childChildren = queryListing(List.of());
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(
                findRoot, rootChildren, childChildren);
        when(attachmentMapper.countBoundByProcessInstanceIds(any())).thenReturn(1L);

        assertBusinessError(() -> service.deleteCompletedHistory(List.of("root-1")),
                HttpStatus.CONFLICT);

        verify(attachmentMapper).countBoundByProcessInstanceIds(
                eq(Set.of("root-1", "child-1")));
        verifyNoInteractions(copyMapper);
        verifyNoInteractions(controlledLoopExecutionMapper);
        verify(historyService, never()).deleteHistoricProcessInstance(anyString());
    }

    /**
     * 验证循环审计预检数量与实际删除数量漂移时不会删除 Flowable 历史。
     * @return 无返回值，循环审计孤儿或部分历史删除发生时测试失败
     */
    @Test
    void rejectsHistoryDeleteWhenControlledLoopRowsDrift()
    {
        setCurrentUser("9", Set.of("workflow:process:remove"));
        HistoricProcessInstance root = historic("root-1", "7", true, null);
        HistoricProcessInstanceQuery findRoot = queryReturning(root);
        HistoricProcessInstanceQuery noChildren = queryListing(List.of());
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(
                findRoot, noChildren);
        when(copyMapper.countActiveByInstanceIds(any())).thenReturn(0L);
        when(copyMapper.logicalDeleteByInstanceIds(any(), eq("9"))).thenReturn(0);
        when(controlledLoopExecutionMapper.countByProcessInstanceIds(any())).thenReturn(2L);
        when(controlledLoopExecutionMapper.deleteByProcessInstanceIds(any())).thenReturn(1);

        assertBusinessError(() -> service.deleteCompletedHistory(List.of("root-1")),
                HttpStatus.CONFLICT);

        verify(historyService, never()).deleteHistoricProcessInstance(anyString());
    }

    /**
     * 验证抄送预检数量与实际逻辑删除数量漂移时整批历史删除返回 409。
     *
     * @return 无返回值，关联数据竞争导致历史孤立时测试失败
     */
    @Test
    void rejectsHistoryDeleteWhenCopyRowsDrift()
    {
        setCurrentUser("9", Set.of("workflow:process:remove"));
        HistoricProcessInstance root = historic("root-1", "7", true, null);
        HistoricProcessInstanceQuery findRoot = queryReturning(root);
        HistoricProcessInstanceQuery noChildren = queryListing(List.of());
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(
                findRoot, noChildren);
        when(copyMapper.countActiveByInstanceIds(any())).thenReturn(2L);
        when(copyMapper.logicalDeleteByInstanceIds(any(), eq("9"))).thenReturn(1);

        assertBusinessError(() -> service.deleteCompletedHistory(List.of("root-1")),
                HttpStatus.CONFLICT);

        verify(historyService, never()).deleteHistoricProcessInstance(anyString());
    }

    /**
     * 构造当前登录用户、权限集合和事务内工作流身份。
     *
     * @param userId String，若依用户主键字符串
     * @param permissions Set&lt;String&gt;，本测试允许的工作流按钮权限
     * @return 无返回值，后续 SecurityUtils 与 WorkflowEngineOperations 使用同一用户
     */
    private void setCurrentUser(String userId, Set<String> permissions)
    {
        SysUser user = new SysUser();
        user.setUserId(Long.valueOf(userId));
        user.setUserName("user-" + userId);
        LoginUser loginUser = new LoginUser(Long.valueOf(userId), 1L, user, permissions);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity(userId, Set.of()));
        // 默认让实时权限与测试声明的 Token 权限一致；撤权用例再覆盖精确权限结果。
        when(permissionService.hasPermi(anyString())).thenAnswer(invocation ->
                permissions.contains(invocation.getArgument(0, String.class)));
    }

    /**
     * 配置一次完整的终止后持久化复核数据流。
     *
     * @param startUserId String，历史实例真实发起人
     * @param suspended boolean，动作前运行实例是否挂起
     * @param expectedStatus String，预期历史变量 canceled 或 terminated
     * @return TerminationFixture，可断言的结构化 comment 正文捕获器
     */
    private TerminationFixture stubTermination(String startUserId, boolean suspended,
            String expectedStatus)
    {
        HistoricProcessInstance running = historic(INSTANCE_ID, startUserId, false, null);
        HistoricProcessInstance finished = historic(INSTANCE_ID, startUserId, true, null);
        when(finished.getDeleteReason()).thenReturn(expectedStatus + ": reason");
        stubHistoricSequence(running, finished);
        ProcessInstance current = process(suspended);
        ProcessInstanceQuery currentQuery = processQueryReturning(INSTANCE_ID, current);
        ProcessInstanceQuery treeQuery = mock(ProcessInstanceQuery.class);
        when(treeQuery.processInstanceIds(any())).thenReturn(treeQuery);
        when(treeQuery.list()).thenReturn(List.of(current));
        ProcessInstanceQuery remainingQuery = processQueryCounting(INSTANCE_ID, 0L);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(
                currentQuery, treeQuery, remainingQuery);

        ExecutionQuery treeExecutionQuery = mock(ExecutionQuery.class);
        when(treeExecutionQuery.rootProcessInstanceId(INSTANCE_ID))
                .thenReturn(treeExecutionQuery);
        when(treeExecutionQuery.list()).thenReturn(List.of());
        ExecutionQuery remainingExecutionQuery = mock(ExecutionQuery.class);
        when(remainingExecutionQuery.rootProcessInstanceId(INSTANCE_ID))
                .thenReturn(remainingExecutionQuery);
        when(remainingExecutionQuery.count()).thenReturn(0L);
        when(runtimeService.createExecutionQuery()).thenReturn(treeExecutionQuery,
                remainingExecutionQuery);

        HistoricVariableInstance variable = mock(HistoricVariableInstance.class);
        when(variable.getValue()).thenReturn(expectedStatus);
        HistoricVariableInstanceQuery variableQuery = mock(HistoricVariableInstanceQuery.class);
        when(historyService.createHistoricVariableInstanceQuery()).thenReturn(variableQuery);
        when(variableQuery.processInstanceId(INSTANCE_ID)).thenReturn(variableQuery);
        when(variableQuery.variableName("processStatus")).thenReturn(variableQuery);
        when(variableQuery.singleResult()).thenReturn(variable);

        AtomicReference<String> auditMessage = new AtomicReference<>();
        Comment comment = mock(Comment.class);
        when(comment.getId()).thenReturn("comment-1");
        when(comment.getFullMessage()).thenAnswer(invocation -> auditMessage.get());
        when(taskService.addComment(eq(null), eq(INSTANCE_ID), eq("6"), anyString()))
                .thenAnswer(invocation ->
                {
                    auditMessage.set(invocation.getArgument(3));
                    return comment;
                });
        when(taskService.getProcessInstanceComments(INSTANCE_ID, "6"))
                .thenReturn(List.of(comment));
        TaskQuery remainingTaskQuery = mock(TaskQuery.class);
        when(remainingTaskQuery.processInstanceIdIn(anyCollection()))
                .thenReturn(remainingTaskQuery);
        when(remainingTaskQuery.count()).thenReturn(0L);
        when(taskService.createTaskQuery()).thenReturn(remainingTaskQuery);
        return new TerminationFixture(auditMessage);
    }

    /**
     * 配置终止权限判断前所需的运行历史和运行实例查询。
     *
     * @param startUserId String，历史实例真实发起人
     * @param suspended boolean，运行实例挂起标志
     * @return 无返回值，服务可进入权限分流但尚未配置任何写操作
     */
    private void stubRunningLookup(String startUserId, boolean suspended)
    {
        stubHistoricSequence(historic(INSTANCE_ID, startUserId, false, null));
        stubRuntimeSequence(process(suspended));
    }

    /**
     * 配置单根实例状态切换所需的执行树预检和可选写后复核查询。
     *
     * @param instances ProcessInstance[]，第一项为切换前实例，第二项可选为切换后实例
     * @return 无返回值，后续 updateState 可使用与生产批量查询一致的 mock
     */
    private void stubStateTree(ProcessInstance... instances)
    {
        ProcessInstance before = instances[0];
        ProcessInstanceQuery requestedQuery = processQueryReturning(INSTANCE_ID, before);
        ProcessInstanceQuery treeBefore = processInstancesQuery(List.of(before));
        if (instances.length > 1)
        {
            ProcessInstanceQuery treeAfter = processInstancesQuery(List.of(instances[1]));
            when(runtimeService.createProcessInstanceQuery()).thenReturn(
                    requestedQuery, treeBefore, treeAfter);
        }
        else
        {
            when(runtimeService.createProcessInstanceQuery()).thenReturn(
                    requestedQuery, treeBefore);
        }

        ExecutionQuery executionQuery = mock(ExecutionQuery.class);
        when(executionQuery.rootProcessInstanceId(INSTANCE_ID)).thenReturn(executionQuery);
        when(executionQuery.list()).thenReturn(List.of());
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
    }

    /**
     * 创建按实例主键集合返回完整运行树的 Flowable 查询替身。
     *
     * @param instances List&lt;ProcessInstance&gt;，查询应返回的根及子实例
     * @return ProcessInstanceQuery，支持 processInstanceIds(...).list() 链路
     */
    private ProcessInstanceQuery processInstancesQuery(List<ProcessInstance> instances)
    {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(query.processInstanceIds(any())).thenReturn(query);
        when(query.list()).thenReturn(instances);
        return query;
    }

    /**
     * 配置按调用顺序返回历史实例的链式查询。
     *
     * @param instances HistoricProcessInstance[]，每次 singleResult 的返回值
     * @return 无返回值，HistoryService 后续创建查询时依次使用独立 mock
     */
    private void stubHistoricSequence(HistoricProcessInstance... instances)
    {
        HistoricProcessInstanceQuery[] queries = new HistoricProcessInstanceQuery[instances.length];
        for (int index = 0; index < instances.length; index++)
        {
            queries[index] = queryReturning(instances[index]);
        }
        when(historyService.createHistoricProcessInstanceQuery()).thenReturn(
                queries[0], java.util.Arrays.copyOfRange(queries, 1, queries.length));
    }

    /**
     * 配置按调用顺序返回运行实例的链式查询。
     *
     * @param instances ProcessInstance[]，每次 singleResult 的返回值，允许 null 表示已结束
     * @return 无返回值，RuntimeService 后续创建查询时依次使用独立 mock
     */
    private void stubRuntimeSequence(ProcessInstance... instances)
    {
        ProcessInstanceQuery[] queries = new ProcessInstanceQuery[instances.length];
        for (int index = 0; index < instances.length; index++)
        {
            ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
            when(query.processInstanceId(INSTANCE_ID)).thenReturn(query);
            when(query.singleResult()).thenReturn(instances[index]);
            queries[index] = query;
        }
        when(runtimeService.createProcessInstanceQuery()).thenReturn(
                queries[0], java.util.Arrays.copyOfRange(queries, 1, queries.length));
    }

    /**
     * 创建按实例主键返回单个运行实例的链式查询替身。
     *
     * @param instanceId String，查询必须接收的运行实例主键
     * @param instance ProcessInstance，singleResult 返回的运行实例
     * @return ProcessInstanceQuery，可供 RuntimeService 按调用顺序返回的查询替身
     */
    private ProcessInstanceQuery processQueryReturning(String instanceId,
            ProcessInstance instance)
    {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(query.processInstanceId(instanceId)).thenReturn(query);
        when(query.singleResult()).thenReturn(instance);
        return query;
    }

    /**
     * 创建按实例主键返回运行残留数量的链式查询替身。
     *
     * @param instanceId String，写后对账的根或子实例主键
     * @param count long，查询返回的运行实例数量
     * @return ProcessInstanceQuery，可供终止写后门禁使用的计数查询替身
     */
    private ProcessInstanceQuery processQueryCounting(String instanceId, long count)
    {
        ProcessInstanceQuery query = mock(ProcessInstanceQuery.class);
        when(query.processInstanceId(instanceId)).thenReturn(query);
        when(query.count()).thenReturn(count);
        return query;
    }

    /**
     * 创建返回单个历史实例的链式查询 mock。
     *
     * @param instance HistoricProcessInstance，singleResult 返回值
     * @return HistoricProcessInstanceQuery，可供 HistoryService 返回的查询替身
     */
    private HistoricProcessInstanceQuery queryReturning(HistoricProcessInstance instance)
    {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class);
        when(query.processInstanceId(anyString())).thenReturn(query);
        when(query.singleResult()).thenReturn(instance);
        return query;
    }

    /**
     * 创建返回子流程列表的链式历史查询 mock。
     *
     * @param instances List&lt;HistoricProcessInstance&gt;，直接子流程历史列表
     * @return HistoricProcessInstanceQuery，可执行 superProcessInstanceId/listPage 的替身
     */
    private HistoricProcessInstanceQuery queryListing(List<HistoricProcessInstance> instances)
    {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class);
        when(query.superProcessInstanceId(anyString())).thenReturn(query);
        when(query.listPage(any(Integer.class), any(Integer.class))).thenReturn(instances);
        return query;
    }

    /**
     * 创建返回历史残留数量的链式查询 mock。
     *
     * @param count long，processInstanceIds 查询的返回数量
     * @return HistoricProcessInstanceQuery，可执行集合计数的替身
     */
    private HistoricProcessInstanceQuery queryCounting(long count)
    {
        HistoricProcessInstanceQuery query = mock(HistoricProcessInstanceQuery.class);
        when(query.processInstanceIds(any())).thenReturn(query);
        when(query.count()).thenReturn(count);
        return query;
    }

    /**
     * 创建历史实例替身。
     *
     * @param id String，历史实例主键
     * @param startUserId String，真实发起人用户主键
     * @param finished boolean，是否具有结束时间
     * @param superProcessInstanceId String，可为空的父流程实例主键
     * @return HistoricProcessInstance，字段足以覆盖状态、权限和子流程关系的替身
     */
    private HistoricProcessInstance historic(String id, String startUserId,
            boolean finished, String superProcessInstanceId)
    {
        HistoricProcessInstance historic = mock(HistoricProcessInstance.class);
        when(historic.getId()).thenReturn(id);
        when(historic.getStartUserId()).thenReturn(startUserId);
        when(historic.getEndTime()).thenReturn(finished ? new Date() : null);
        when(historic.getSuperProcessInstanceId()).thenReturn(superProcessInstanceId);
        return historic;
    }

    /**
     * 创建运行实例替身。
     *
     * @param suspended boolean，Flowable 运行实例挂起标志
     * @return ProcessInstance，具有稳定状态的运行实例替身
     */
    private ProcessInstance process(boolean suspended)
    {
        return process(INSTANCE_ID, INSTANCE_ID, null, suspended);
    }

    /**
     * 创建带完整根/子执行关系的运行实例替身。
     *
     * @param instanceId String，当前运行实例主键
     * @param rootInstanceId String，Flowable 声明的根业务实例主键
     * @param superExecutionId String，CallActivity 子实例的父 execution；根实例为空
     * @param suspended boolean，运行实例是否挂起
     * @return ProcessInstance，可用于根解析、树冻结和状态校验的运行实例替身
     */
    private ProcessInstance process(String instanceId, String rootInstanceId,
            String superExecutionId, boolean suspended)
    {
        ProcessInstance processInstance = mock(ProcessInstance.class);
        when(processInstance.getId()).thenReturn(instanceId);
        when(processInstance.getRootProcessInstanceId()).thenReturn(rootInstanceId);
        when(processInstance.getSuperExecutionId()).thenReturn(superExecutionId);
        when(processInstance.isSuspended()).thenReturn(suspended);
        return processInstance;
    }

    /**
     * 断言领域动作抛出指定 HTTP 语义的 ServiceException。
     *
     * @param action ThrowingAction，待执行的领域动作
     * @param expectedCode int，期望 HTTP 业务码
     * @return 无返回值，异常类型或状态码不匹配时测试失败
     */
    private void assertBusinessError(ThrowingAction action, int expectedCode)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
    }

    /**
     * 允许测试传入会抛运行时异常的领域动作。
     */
    @FunctionalInterface
    private interface ThrowingAction
    {
        /**
         * 执行待断言动作。
         *
         * @return 无返回值，业务异常由断言方法捕获
         */
        void run();
    }

    /**
     * 终止场景中由 TaskService 捕获的结构化审计正文。
     *
     * @param auditMessage AtomicReference&lt;String&gt;，addComment 调用时保存的 JSON
     */
    private record TerminationFixture(AtomicReference<String> auditMessage)
    {
    }
}
