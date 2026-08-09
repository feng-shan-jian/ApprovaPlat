package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ExecutionQuery;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.runtime.ProcessInstanceQuery;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceUserRow;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentAction;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;

class WorkflowMultiInstanceServiceTest
{
    private static final String INSTANCE_ID = "instance-1";

    private static final String DEFINITION_ID = "definition-1";

    private static final String ACTIVITY_ID = "approveTask";

    private static final String ROOT_EXECUTION_ID = "mi-root";

    /** 单元测试固定的当前正式办理人。 */
    private final WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("8", Set.of());

    private WorkflowEngineOperations engineOperations;

    private WorkflowIdentityResolver identityResolver;

    private WorkflowUserSelectionValidator userSelectionValidator;

    private WorkflowMultiInstanceUserMapper userMapper;

    private RepositoryService repositoryService;

    private RuntimeService runtimeService;

    private TaskService taskService;

    private HistoryService historyService;

    private TaskQuery taskQuery;

    private ExecutionQuery executionQuery;

    private ProcessInstanceQuery processInstanceQuery;

    private WorkflowMultiInstanceService service;

    /**
     * 为每条用例创建独立领域依赖，并让统一引擎边界同步执行测试命令。
     *
     * @return 无返回值；依赖初始化失败时测试失败
     */
    @BeforeEach
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void setUp()
    {
        engineOperations = mock(WorkflowEngineOperations.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        userSelectionValidator = mock(WorkflowUserSelectionValidator.class);
        userMapper = mock(WorkflowMultiInstanceUserMapper.class);
        repositoryService = mock(RepositoryService.class);
        runtimeService = mock(RuntimeService.class);
        taskService = mock(TaskService.class);
        historyService = mock(HistoryService.class);
        taskQuery = mock(TaskQuery.class, RETURNS_SELF);
        executionQuery = mock(ExecutionQuery.class, RETURNS_SELF);
        processInstanceQuery = mock(ProcessInstanceQuery.class, RETURNS_SELF);

        when(identityResolver.resolveCurrentIdentity()).thenReturn(actor);
        when(engineOperations.read(any(Supplier.class))).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(engineOperations.writeAsCurrentUser(any(Function.class))).thenAnswer(invocation ->
                ((Function<WorkflowCurrentIdentity, ?>) invocation.getArgument(0)).apply(actor));
        when(engineOperations.withConcurrencyConflictSubCode(
                any(RuntimeException.class), anyString())).thenAnswer(invocation ->
                        invocation.getArgument(0));
        when(taskService.createTaskQuery()).thenReturn(taskQuery);
        when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
        when(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery);
        ProcessDefinition processDefinition = mock(ProcessDefinition.class);
        when(processDefinition.getKey()).thenReturn("process");
        when(repositoryService.getProcessDefinition(DEFINITION_ID))
                .thenReturn(processDefinition);

        service = new WorkflowMultiInstanceService(engineOperations, identityResolver,
                userSelectionValidator, userMapper, repositoryService, runtimeService,
                taskService, historyService);
    }

    /**
     * 验证正常查询返回冻结模式、revision、活动任务和当前办理人的可减签能力。
     *
     * @return 无返回值；页面状态与正式成员快照不一致时测试失败
     */
    @Test
    void getStateReturnsOrderedServerSnapshot()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "8", 3, 0);

        WorkflowMultiInstanceStateView state = service.getState("task-8");

        assertThat(state.mode()).isEqualTo("ALL");
        assertThat(state.activityId()).isEqualTo(ACTIVITY_ID);
        assertThat(state.revision()).isEqualTo(3);
        assertThat(state.members()).hasSize(2);
        assertThat(state.members().get(0).userId()).isEqualTo(8L);
        assertThat(state.members().get(0).removable()).isFalse();
        assertThat(state.members().get(1).userId()).isEqualTo(9L);
        assertThat(state.members().get(1).activeTaskId()).isEqualTo("task-9");
        assertThat(state.members().get(1).removable()).isTrue();
    }

    /**
     * 验证 ALL 模式部分完成后保留的 inactive child 不会被误判为幽灵 execution。
     *
     * @return 无返回值；已完成成员无法回显或活动计数漂移时测试失败
     */
    @Test
    void getStateSupportsRetainedInactiveChildren()
    {
        new EngineFixture(List.of("8", "9", "10"), List.of("8", "9"), "8", 2, 1);

        WorkflowMultiInstanceStateView state = service.getState("task-8");

        assertThat(state.members()).hasSize(3);
        assertThat(state.members().get(2).userId()).isEqualTo(10L);
        assertThat(state.members().get(2).active()).isFalse();
        assertThat(state.members().get(2).activeTaskId()).isNull();
    }

    /**
     * 验证动态任务完成必须使用匹配 revision，并仅把服务端 revision 原子推进一位。
     *
     * @return 无返回值；返回区间、写入值或额外 execution/comment 副作用不符合时测试失败
     */
    @Test
    void reservesDynamicCompletionRevision()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 4, 0);

        WorkflowMultiInstanceService.CompletionRevision revision =
                service.reserveCompletionRevision(fixture.task("8"), 4L, actor);

        assertThat(revision.activityId()).isEqualTo(ACTIVITY_ID);
        assertThat(revision.beforeRevision()).isEqualTo(4);
        assertThat(revision.afterRevision()).isEqualTo(5);
        assertThat(revision.applied()).isTrue();
        verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.revisionName(ACTIVITY_ID), 5);
        verify(runtimeService, never()).addMultiInstanceExecution(any(), any(), anyMap());
        verify(runtimeService, never()).deleteMultiInstanceExecution(any(), anyBoolean());
        verify(taskService, never()).addComment(any(), any(), any(), any());
    }

    /**
     * 验证动态任务缺少 expectedRevision 时在任何引擎写操作前返回稳定 400。
     *
     * @return 无返回值；缺参请求推进 revision 或修改 execution 时测试失败
     */
    @Test
    void rejectsMissingDynamicCompletionRevisionWithoutWrites()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 4, 0);

        assertCompletionRevisionError(() -> service.reserveCompletionRevision(
                fixture.task("8"), null, actor), HttpStatus.BAD_REQUEST);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证动态任务的负数和超出 int 上限 revision 均在读取执行树前返回稳定 400。
     *
     * @return 无返回值；越界输入触发变量或 execution 写入时测试失败
     */
    @Test
    void rejectsOutOfRangeDynamicCompletionRevisionWithoutWrites()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 4, 0);

        assertCompletionRevisionError(() -> service.reserveCompletionRevision(
                fixture.task("8"), -1L, actor), HttpStatus.BAD_REQUEST);
        assertCompletionRevisionError(() -> service.reserveCompletionRevision(
                fixture.task("8"), (long) Integer.MAX_VALUE + 1, actor),
                HttpStatus.BAD_REQUEST);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证动态任务的过期 revision 返回 409，并保持 comment、execution 和变量零写入。
     *
     * @return 无返回值；过期完成请求产生任何写副作用时测试失败
     */
    @Test
    void rejectsStaleDynamicCompletionRevisionWithoutWrites()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 4, 0);

        assertServiceError(() -> service.reserveCompletionRevision(
                fixture.task("8"), 3L, actor), HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证服务端 revision 达到 int 上限时完成请求返回 409，且不会溢出写回负数。
     *
     * @return 无返回值；上限请求写入 revision、comment 或 execution 时测试失败
     */
    @Test
    void rejectsDynamicCompletionRevisionOverflowWithoutWrites()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", Integer.MAX_VALUE, 0);

        assertServiceError(() -> service.reserveCompletionRevision(
                fixture.task("8"), (long) Integer.MAX_VALUE, actor),
                HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证普通任务和既有非受控静态多实例保持旧完成契约：revision 为空可通过，非空返回 400。
     *
     * @return 无返回值；兼容任务被错误纳入动态 CAS 或接受客户端 revision 时测试失败
     */
    @Test
    void keepsOrdinaryAndStaticCompletionCompatibility()
    {
        Task ordinaryTask = taskForModel(ordinaryModel());
        WorkflowMultiInstanceService.CompletionRevision ordinaryRevision =
                service.reserveCompletionRevision(ordinaryTask, null, actor);
        assertThat(ordinaryRevision.applied()).isFalse();
        assertCompletionRevisionError(() -> service.reserveCompletionRevision(
                ordinaryTask, 0L, actor), HttpStatus.BAD_REQUEST);

        Task staticTask = taskForModel(staticMultiInstanceModel());
        WorkflowMultiInstanceService.CompletionRevision staticRevision =
                service.reserveCompletionRevision(staticTask, null, actor);
        assertThat(staticRevision.applied()).isFalse();
        assertCompletionRevisionError(() -> service.reserveCompletionRevision(
                staticTask, 0L, actor), HttpStatus.BAD_REQUEST);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证固定和发起时来源进入节点后统一参加成员 revision 与完成 CAS，不能被降级为普通静态任务。
     *
     * @return 无返回值；任一受控来源未加载正式成员状态或未原子推进 revision 时测试失败
     */
    @Test
    void appliesCompletionRevisionToFixedAndStartSources()
    {
        EngineFixture fixedFixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 4, 0);
        fixedFixture.useModel(fixedMultiInstanceModel());
        WorkflowMultiInstanceService.CompletionRevision fixedRevision =
                service.reserveCompletionRevision(fixedFixture.task("8"), 4L, actor);
        assertThat(fixedRevision.applied()).isTrue();
        assertThat(fixedRevision.beforeRevision()).isEqualTo(4);
        assertThat(fixedRevision.afterRevision()).isEqualTo(5);

        setUp();
        EngineFixture startFixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 2, 0);
        startFixture.useModel(startMultiInstanceModel());
        WorkflowMultiInstanceService.CompletionRevision startRevision =
                service.reserveCompletionRevision(startFixture.task("8"), 2L, actor);
        assertThat(startRevision.applied()).isTrue();
        assertThat(startRevision.beforeRevision()).isEqualTo(2);
        assertThat(startRevision.afterRevision()).isEqualTo(3);
    }

    /**
     * 验证 collectionString 命中受控 handler 但完整白名单残缺时返回 409，禁止降级普通任务。
     *
     * @return 无返回值；畸形受控模型绕过 revision 或产生任务写入时测试失败
     */
    @Test
    void rejectsMalformedControlledCollectionStringWithoutWrites()
    {
        Task malformedTask = taskForModel(malformedControlledCollectionModel());

        assertServiceError(() -> service.reserveCompletionRevision(
                malformedTask, null, actor), HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证同一 BPMN 部署包含多个 Process 时，必须按流程定义 key 选择第二个真实动态节点。
     *
     * @return 无返回值；首个诱饵 Process 导致动态完成被降级或 revision 未推进时测试失败
     */
    @Test
    void resolvesDynamicTaskFromDefinitionProcessInMultiProcessModel()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 4, 0);
        when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(
                multiProcessModelWithLoop(dynamicMultiInstanceLoop()));

        WorkflowMultiInstanceService.CompletionRevision revision =
                service.reserveCompletionRevision(fixture.task("8"), 4L, actor);

        assertThat(revision.applied()).isTrue();
        assertThat(revision.beforeRevision()).isEqualTo(4);
        assertThat(revision.afterRevision()).isEqualTo(5);
    }

    /**
     * 验证嵌套 SubProcess 中的普通任务与静态多实例继续沿用既有完成契约。
     *
     * @return 无返回值；递归查找把兼容节点误判为动态节点或接受客户端 revision 时测试失败
     */
    @Test
    void keepsNestedOrdinaryAndStaticCompletionCompatibility()
    {
        Task ordinaryTask = taskForModel(nestedModelWithLoop(null));
        assertThat(service.reserveCompletionRevision(ordinaryTask, null, actor).applied())
                .isFalse();
        assertCompletionRevisionError(() -> service.reserveCompletionRevision(
                ordinaryTask, 0L, actor), HttpStatus.BAD_REQUEST);

        Task staticTask = taskForModel(nestedModelWithLoop(staticMultiInstanceLoop()));
        assertThat(service.reserveCompletionRevision(staticTask, null, actor).applied())
                .isFalse();
        assertCompletionRevisionError(() -> service.reserveCompletionRevision(
                staticTask, 0L, actor), HttpStatus.BAD_REQUEST);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证嵌套 SubProcess 中声明受控 handler 但白名单残缺的候选仍返回 409。
     *
     * @return 无返回值；递归查找遗漏候选并把它降级为普通任务时测试失败
     */
    @Test
    void rejectsNestedControlledCandidateWithoutWrites()
    {
        Task malformedTask = taskForModel(nestedModelWithLoop(
                malformedControlledCollectionLoop()));

        assertServiceError(() -> service.reserveCompletionRevision(
                malformedTask, null, actor), HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证活动任务引用的流程定义被并发删除时返回数据一致性 500，而不是对象 404。
     *
     * @return 无返回值；原始异常未保留或产生 revision、execution、comment 写入时测试失败
     */
    @Test
    void mapsMissingProcessDefinitionToDataErrorWithoutWrites()
    {
        Task task = taskForModel(ordinaryModel());
        FlowableObjectNotFoundException missingDefinition =
                new FlowableObjectNotFoundException("definition missing",
                        ProcessDefinition.class);
        when(repositoryService.getProcessDefinition(DEFINITION_ID))
                .thenThrow(missingDefinition);

        assertThatThrownBy(() -> service.reserveCompletionRevision(task, null, actor))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getCause()).isSameAs(missingDefinition);
                });

        verifyNoWriteSideEffects();
    }

    /**
     * 验证非当前 assignee 即使知道任务主键也只能得到对象级 403，且不读取成员快照。
     *
     * @return 无返回值；越权查询进入多实例状态读取时测试失败
     */
    @Test
    void rejectsNonAssigneeWithForbidden()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "9", 0, 0);

        assertServiceError(() -> service.getState("task-9"), HttpStatus.FORBIDDEN);

        verify(runtimeService, never()).getVariable(anyString(), anyString());
        verify(userMapper, never()).selectUserNamesByIds(anyList());
    }

    /**
     * 验证过期 expectedRevision 在任何 execution、变量和 comment 写入前返回 409。
     *
     * @return 无返回值；过期请求产生任一引擎写副作用时测试失败
     */
    @Test
    void rejectsStaleRevisionWithoutWrites()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "8", 4, 0);

        assertServiceError(() -> service.adjust(addRequest(3L, List.of(10L))),
                HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证内部直接调用即使绕过 Bean Validation 提交空用户元素，也返回稳定 400 且不进入写事务。
     *
     * @return 无返回值；空元素泄漏为 NPE、500 或触发引擎事务时测试失败
     */
    @Test
    void rejectsNullAddUserBeforeOpeningWriteTransaction()
    {
        List<Long> invalidUserIds = new ArrayList<>();
        invalidUserIds.add(10L);
        invalidUserIds.add(null);
        WorkflowMultiInstanceAdjustmentRequest request =
                new WorkflowMultiInstanceAdjustmentRequest("task-8",
                        WorkflowMultiInstanceAdjustmentAction.ADD, 0L, "同意加签",
                        invalidUserIds, null);

        assertServiceError(() -> service.adjust(request), HttpStatus.BAD_REQUEST);

        verify(engineOperations, never()).writeAsCurrentUser(any(Function.class));
        verifyNoWriteSideEffects();
    }

    /**
     * 验证加签集合即使绕过上游校验返回重复用户，领域服务仍按 400 拒绝且零写入。
     *
     * @return 无返回值；重复用户被静默合并或创建 execution 时测试失败
     */
    @Test
    void rejectsDuplicateAddUsersWithoutWrites()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "8", 0, 0);
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(10L, 10L)))
                .thenReturn(List.of("10", "10"));

        assertServiceError(() -> service.adjust(addRequest(0L, List.of(10L, 10L))),
                HttpStatus.BAD_REQUEST);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证启用但无流程办理权限的目标用户在 revision 和 execution 写入前被拒绝。
     *
     * @return 无返回值；错误码、稳定提示或零写副作用契约漂移时测试失败
     */
    @Test
    void rejectsApprovalIneligibleAddUserWithoutWrites()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "8", 0, 0);
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(10L)))
                .thenThrow(new ServiceException(
                        "所选用户不存在、已停用或无流程办理权限",
                        HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.adjust(addRequest(0L, List.of(10L))))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo(
                            "所选用户不存在、已停用或无流程办理权限");
                });

        verifyNoWriteSideEffects();
    }

    /**
     * 验证当前用于对象授权的任务不能成为减签目标，避免操作人在同一命令中失去授权锚点。
     *
     * @return 无返回值；当前任务被删除或写入审计时测试失败
     */
    @Test
    void rejectsRemovingCurrentTaskWithoutWrites()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "8", 0, 0);

        assertServiceError(() -> service.adjust(removeRequest(0L, "task-8")),
                HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证仅剩一个活动实例时禁止减签，即使快照中仍保留已经完成的成员。
     *
     * @return 无返回值；最后合法办理路径被删除时测试失败
     */
    @Test
    void rejectsRemovingLastActiveTaskWithoutWrites()
    {
        new EngineFixture(List.of("8", "9"), List.of("8"), "8", 2, 1);

        assertServiceError(() -> service.adjust(removeRequest(2L, "task-9")),
                HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证存在 owner 的委派来源任务禁止减签，避免破坏 Flowable 委派恢复链。
     *
     * @return 无返回值；owner 任务 execution 被删除时测试失败
     */
    @Test
    void rejectsRemovingOwnedTaskWithoutWrites()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 0, 0);
        when(fixture.task("9").getOwner()).thenReturn("8");

        assertServiceError(() -> service.adjust(removeRequest(0L, "task-9")),
                HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证处于 PENDING 委派状态的 sibling 禁止减签，且拒绝请求不产生任何审计半写。
     *
     * @return 无返回值；委派任务被删除或 revision 改变时测试失败
     */
    @Test
    void rejectsRemovingDelegatedTaskWithoutWrites()
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 0, 0);
        when(fixture.task("9").getDelegationState()).thenReturn(DelegationState.PENDING);

        assertServiceError(() -> service.adjust(removeRequest(0L, "task-9")),
                HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 验证成功加签通过 Flowable 公共 API 创建真实 execution，并同步三项变量、审计和写后状态。
     *
     * @return 无返回值；成员、计数、revision、任务或 comment 任一未闭合时测试失败
     * @throws Exception 结构化审计 JSON 无法解析时测试失败
     */
    @Test
    void addsMembersAndReconcilesWrittenState() throws Exception
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 3, 0);
        fixture.configureAfter(List.of("8", "9", "10", "11"));
        when(userSelectionValidator.requireApprovalEligibleUserIds(List.of(10L, 11L)))
                .thenReturn(List.of("10", "11"));

        WorkflowMultiInstanceStateView state = service.adjust(
                addRequest(3L, List.of(10L, 11L)));

        assertThat(state.revision()).isEqualTo(4);
        assertThat(state.members()).extracting(member -> member.userId())
                .containsExactly(8L, 9L, 10L, 11L);
        verify(runtimeService).addMultiInstanceExecution(ACTIVITY_ID, INSTANCE_ID,
                Map.of(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE, "10"));
        verify(runtimeService).addMultiInstanceExecution(ACTIVITY_ID, INSTANCE_ID,
                Map.of(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE, "11"));
        verifyPersistedState(4, List.of("8", "9", "10", "11"));
        assertAuditComment("MULTI_INSTANCE_ADD", 3, 4, List.of("10", "11"),
                null, null);
        InOrder writeOrder = inOrder(runtimeService, taskService);
        writeOrder.verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.revisionName(ACTIVITY_ID), 4);
        writeOrder.verify(runtimeService).addMultiInstanceExecution(ACTIVITY_ID,
                INSTANCE_ID, Map.of(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE, "10"));
        writeOrder.verify(runtimeService).addMultiInstanceExecution(ACTIVITY_ID,
                INSTANCE_ID, Map.of(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE, "11"));
        writeOrder.verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.memberSnapshotName(ACTIVITY_ID),
                List.of("8", "9", "10", "11"));
        writeOrder.verify(taskService).addComment(eq("task-8"), eq(INSTANCE_ID), eq("1"), any());
    }

    /**
     * 验证成功减签通过公共 API 删除目标 execution，保留历史并同步成员、revision 和结构化审计。
     *
     * @return 无返回值；目标仍活动、快照未收敛或审计缺失时测试失败
     * @throws Exception 结构化审计 JSON 无法解析时测试失败
     */
    @Test
    void removesMemberAndReconcilesWrittenState() throws Exception
    {
        EngineFixture fixture = new EngineFixture(List.of("8", "9"),
                List.of("8", "9"), "8", 6, 0);
        fixture.configureAfter(List.of("8"));

        WorkflowMultiInstanceStateView state = service.adjust(removeRequest(6L, "task-9"));

        assertThat(state.revision()).isEqualTo(7);
        assertThat(state.members()).extracting(member -> member.userId()).containsExactly(8L);
        verify(runtimeService).deleteMultiInstanceExecution("exec-9", false);
        verifyPersistedState(7, List.of("8"));
        assertAuditComment("MULTI_INSTANCE_REMOVE", 6, 7, List.of(),
                "task-9", "9");
        InOrder writeOrder = inOrder(runtimeService, taskService);
        writeOrder.verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.revisionName(ACTIVITY_ID), 7);
        writeOrder.verify(runtimeService).deleteMultiInstanceExecution("exec-9", false);
        writeOrder.verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.memberSnapshotName(ACTIVITY_ID),
                List.of("8"));
        writeOrder.verify(taskService).addComment(eq("task-8"), eq(INSTANCE_ID), eq("1"), any());
    }

    /**
     * 为完成分类测试创建主流程任务，并绑定指定部署模型。
     *
     * @param model BpmnModel，包含普通、静态或畸形受控节点的部署模型
     * @return Task，具有完整定义和节点关联的活动任务替身
     */
    private Task taskForModel(BpmnModel model)
    {
        Task task = mock(Task.class);
        when(task.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
        when(task.getTaskDefinitionKey()).thenReturn(ACTIVITY_ID);
        when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(model);
        return task;
    }

    /**
     * 创建普通用户任务模型，验证不参与动态 revision 契约。
     *
     * @return BpmnModel，主流程只包含普通 approveTask
     */
    private BpmnModel ordinaryModel()
    {
        return modelWithLoop(null);
    }

    /**
     * 创建受控静态集合多实例模型，验证既有静态任务保持完成兼容。
     *
     * @return BpmnModel，集合表达式不命中 multiInstanceHandler 的并行多实例模型
     */
    private BpmnModel staticMultiInstanceModel()
    {
        return modelWithLoop(staticMultiInstanceLoop());
    }

    /**
     * 创建固定成员受控多实例模型，验证其不会进入动态 revision 和成员调整状态机。
     *
     * @return BpmnModel，成员固化在受控 BPMN 集合表达式中的并行会签模型。
     */
    private BpmnModel fixedMultiInstanceModel()
    {
        return modelWithLoop(fixedMultiInstanceLoop());
    }

    /**
     * 创建发起时成员受控多实例模型，验证发起页名单进入节点后沿用统一运行状态机。
     *
     * @return BpmnModel，成员来自发起请求专用字段的并行会签模型
     */
    private BpmnModel startMultiInstanceModel()
    {
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.START_COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(
                WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        return modelWithLoop(loop);
    }

    /**
     * 创建不命中受控 handler 的静态并行多实例循环配置。
     *
     * @return MultiInstanceLoopCharacteristics，集合来自既有普通流程变量
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
     * 创建完整满足固定成员会签契约的并行多实例循环配置。
     *
     * @return MultiInstanceLoopCharacteristics，成员为 8 和 9 的固定会签循环。
     */
    private MultiInstanceLoopCharacteristics fixedMultiInstanceLoop()
    {
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        loop.setInputDataItem(
                "${multiInstanceHandler.getFixedUserIds(execution, '8,9')}");
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(
                WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        return loop;
    }

    /**
     * 创建 collectionString 命中固定 handler、但 inputDataItem 等完整白名单残缺的模型。
     *
     * @return BpmnModel，必须被识别为受控候选并返回冲突的畸形模型
     */
    private BpmnModel malformedControlledCollectionModel()
    {
        return modelWithLoop(malformedControlledCollectionLoop());
    }

    /**
     * 创建 collectionString 命中受控 handler、但缺少正式 inputDataItem 的循环配置。
     *
     * @return MultiInstanceLoopCharacteristics，必须返回 409 的畸形受控候选
     */
    private MultiInstanceLoopCharacteristics malformedControlledCollectionLoop()
    {
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        loop.setCollectionString(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        return loop;
    }

    /**
     * 创建固定受控动态会签循环，用于验证定义 key 对应 Process 的精确解析。
     *
     * @return MultiInstanceLoopCharacteristics，完整满足动态多实例模型白名单
     */
    private MultiInstanceLoopCharacteristics dynamicMultiInstanceLoop()
    {
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setSequential(false);
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(
                WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        return loop;
    }

    /**
     * 创建首个 Process 为同名普通任务诱饵、第二个定义 Process 为目标任务的部署模型。
     *
     * @param loop MultiInstanceLoopCharacteristics，目标 Process 的循环配置
     * @return BpmnModel，可证明 getMainProcess 会选错而 definition key 能选中真实 Process 的模型
     */
    private BpmnModel multiProcessModelWithLoop(MultiInstanceLoopCharacteristics loop)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process decoy = new org.flowable.bpmn.model.Process();
        decoy.setId("decoy");
        decoy.setExecutable(true);
        model.addProcess(decoy);
        UserTask decoyTask = new UserTask();
        decoyTask.setId(ACTIVITY_ID);
        decoy.addFlowElement(decoyTask);

        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process");
        process.setExecutable(true);
        model.addProcess(process);
        UserTask userTask = new UserTask();
        userTask.setId(ACTIVITY_ID);
        userTask.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        userTask.setLoopCharacteristics(loop);
        process.addFlowElement(userTask);
        return model;
    }

    /**
     * 创建目标任务位于嵌套 SubProcess 的模型。
     *
     * @param loop MultiInstanceLoopCharacteristics，可空的普通、静态或受控循环配置
     * @return BpmnModel，任务只能通过 recursive=true 定位
     */
    private BpmnModel nestedModelWithLoop(MultiInstanceLoopCharacteristics loop)
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
        userTask.setId(ACTIVITY_ID);
        userTask.setLoopCharacteristics(loop);
        subProcess.addFlowElement(userTask);
        return model;
    }

    /**
     * 创建包含 approveTask 的最小主流程模型。
     *
     * @param loop MultiInstanceLoopCharacteristics，可空的循环配置
     * @return BpmnModel，任务已挂入主流程并可供模型契约读取
     */
    private BpmnModel modelWithLoop(MultiInstanceLoopCharacteristics loop)
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process");
        process.setExecutable(true);
        model.addProcess(process);
        UserTask userTask = new UserTask();
        userTask.setId(ACTIVITY_ID);
        userTask.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        userTask.setLoopCharacteristics(loop);
        process.addFlowElement(userTask);
        return model;
    }

    /**
     * 验证 revision 达到 int 上限时，加签在身份查询和所有 Flowable 写操作前返回 409。
     *
     * @return 无返回值；revision 溢出、创建 execution 或写入 comment 时测试失败
     */
    @Test
    void rejectsAddWhenRevisionWouldOverflowWithoutWrites()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "8",
                Integer.MAX_VALUE, 0);

        assertServiceError(() -> service.adjust(
                addRequest((long) Integer.MAX_VALUE, List.of(10L))), HttpStatus.CONFLICT);

        verify(userSelectionValidator, never()).requireApprovalEligibleUserIds(anyList());
        verifyNoWriteSideEffects();
    }

    /**
     * 验证 revision 达到 int 上限时，减签不会先删除目标 execution 再发生整数溢出。
     *
     * @return 无返回值；目标 execution、快照或审计发生任一变化时测试失败
     */
    @Test
    void rejectsRemoveWhenRevisionWouldOverflowWithoutWrites()
    {
        new EngineFixture(List.of("8", "9"), List.of("8", "9"), "8",
                Integer.MAX_VALUE, 0);

        assertServiceError(() -> service.adjust(
                removeRequest((long) Integer.MAX_VALUE, "task-9")), HttpStatus.CONFLICT);

        verifyNoWriteSideEffects();
    }

    /**
     * 创建加签命令，固定使用当前任务和受控业务意见。
     *
     * @param revision long，客户端最后读取的服务端 revision
     * @param userIds List&lt;Long&gt;，待加入的正式用户主键
     * @return WorkflowMultiInstanceAdjustmentRequest，可直接提交领域服务的 ADD 请求
     */
    private WorkflowMultiInstanceAdjustmentRequest addRequest(long revision,
            List<Long> userIds)
    {
        return new WorkflowMultiInstanceAdjustmentRequest("task-8",
                WorkflowMultiInstanceAdjustmentAction.ADD, revision, "同意加签",
                userIds, null);
    }

    /**
     * 创建减签命令，固定使用当前任务和受控业务意见。
     *
     * @param revision long，客户端最后读取的服务端 revision
     * @param targetTaskId String，同根目标 sibling 任务主键
     * @return WorkflowMultiInstanceAdjustmentRequest，可直接提交领域服务的 REMOVE 请求
     */
    private WorkflowMultiInstanceAdjustmentRequest removeRequest(long revision,
            String targetTaskId)
    {
        return new WorkflowMultiInstanceAdjustmentRequest("task-8",
                WorkflowMultiInstanceAdjustmentAction.REMOVE, revision, "同意减签",
                List.of(), targetTaskId);
    }

    /**
     * 断言服务异常的 HTTP 语义和稳定刷新提示。
     *
     * @param action ThrowingCallable，预期失败的服务调用
     * @param expectedCode int，预期 HTTP 业务状态码
     * @return 无返回值；异常类型、状态码或消息不匹配时测试失败
     */
    private void assertServiceError(ThrowingCallable action, int expectedCode)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(expectedCode);
            if (expectedCode == HttpStatus.FORBIDDEN)
            {
                assertThat(exception.getMessage()).isEqualTo("无权调整当前多实例任务");
            }
            else if (expectedCode == HttpStatus.BAD_REQUEST)
            {
                assertThat(exception.getMessage()).isEqualTo("工作流多实例调整参数不合法");
            }
            else
            {
                assertThat(exception.getMessage()).isEqualTo("工作流状态已发生变化，请刷新后重试");
            }
        });
    }

    /**
     * 断言完成任务 revision 分类使用独立稳定错误消息，避免与加签/减签参数错误混淆。
     *
     * @param action ThrowingCallable，预期失败的 completion revision 调用
     * @param expectedCode int，预期 HTTP 业务状态码
     * @return 无返回值；状态码或稳定消息不匹配时测试失败
     */
    private void assertCompletionRevisionError(ThrowingCallable action, int expectedCode)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(expectedCode);
            assertThat(exception.getMessage()).isEqualTo("动态多实例任务版本不合法");
        });
    }

    /**
     * 断言拒绝分支没有调用任何动态 execution、变量或 comment 写 API。
     *
     * @return 无返回值；检测到任一写调用时测试失败
     */
    private void verifyNoWriteSideEffects()
    {
        verify(runtimeService, never()).addMultiInstanceExecution(anyString(), anyString(),
                anyMap());
        verify(runtimeService, never()).deleteMultiInstanceExecution(anyString(), anyBoolean());
        verify(runtimeService, never()).setVariable(anyString(), anyString(), any());
        verify(taskService, never()).addComment(anyString(), anyString(), anyString(),
                anyString());
    }

    /**
     * 断言 revision、成员快照和 handler 集合变量在同一服务命令中同步写入。
     *
     * @param revision int，动作后的预期 revision
     * @param members List&lt;String&gt;，动作后的有序成员快照
     * @return 无返回值；任一正式变量未按契约写入时测试失败
     */
    private void verifyPersistedState(int revision, List<String> members)
    {
        verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.revisionName(ACTIVITY_ID), revision);
        verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.memberSnapshotName(ACTIVITY_ID), members);
        verify(runtimeService).setVariable(INSTANCE_ID,
                WorkflowMultiInstanceVariables.userCollectionName(ACTIVITY_ID),
                members.stream().map(Long::valueOf).toList());
    }

    /**
     * 解析并核对结构化 Flowable comment 的动作、revision、目标和操作人字段。
     *
     * @param action String，预期固定动作编码
     * @param beforeRevision int，动作前 revision
     * @param afterRevision int，动作后 revision
     * @param targetUserIds List&lt;String&gt;，ADD 的目标用户集合
     * @param targetTaskId String，REMOVE 的目标任务；ADD 时为 null
     * @param targetUserId String，REMOVE 的目标办理人；ADD 时为 null
     * @return 无返回值；comment 内容或关联对象不完整时测试失败
     * @throws Exception comment 不是合法 JSON 时测试失败
     */
    private void assertAuditComment(String action, int beforeRevision, int afterRevision,
            List<String> targetUserIds, String targetTaskId, String targetUserId)
            throws Exception
    {
        ArgumentCaptor<String> commentCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).addComment(eq("task-8"), eq(INSTANCE_ID), eq("1"),
                commentCaptor.capture());
        JsonNode audit = JsonMapper.shared().readTree(commentCaptor.getValue());
        assertThat(audit.path("action").asText()).isEqualTo(action);
        assertThat(audit.path("actorUserId").asText()).isEqualTo("8");
        assertThat(audit.path("activityId").asText()).isEqualTo(ACTIVITY_ID);
        assertThat(audit.path("beforeRevision").asInt()).isEqualTo(beforeRevision);
        assertThat(audit.path("afterRevision").asInt()).isEqualTo(afterRevision);
        assertThat(audit.path("opinion").asText()).isNotBlank();
        if (!targetUserIds.isEmpty())
        {
            assertThat(audit.path("targetUserIds")).extracting(JsonNode::asText)
                    .containsExactlyElementsOf(targetUserIds);
        }
        if (targetTaskId != null)
        {
            assertThat(audit.path("targetTaskId").asText()).isEqualTo(targetTaskId);
            assertThat(audit.path("targetUserId").asText()).isEqualTo(targetUserId);
        }
    }

    /**
     * 构造可变的真实引擎查询投影，使服务在写前和写后分别读取明确的 execution tree。
     */
    private final class EngineFixture
    {
        /** 流程实例作用域的正式多实例变量。 */
        private final Map<String, Object> processVariables = new HashMap<>();

        /** 多实例根 execution 本地维护的三项 Flowable 计数。 */
        private final Map<String, Integer> engineCounts = new HashMap<>();

        /** 按 assignee 复用任务替身，确保写前写后主键稳定。 */
        private final Map<String, Task> tasksByAssignee = new LinkedHashMap<>();

        /** 按 assignee 复用子 execution 替身。 */
        private final Map<String, Execution> executionsByAssignee = new LinkedHashMap<>();

        /** 包含已完成成员的初始有序成员主键。 */
        private final List<String> initialMemberIds;

        /** 由公共 getActiveActivityIds 投影的当前活动 child execution 主键。 */
        private final Set<String> activeExecutionIds = new java.util.LinkedHashSet<>();

        private final List<Task> initialTasks;

        private final Task currentTask;

        private final Execution rootExecution;

        /**
         * 创建完整满足服务对账规则的写前执行树和变量快照。
         *
         * @param memberIds List&lt;String&gt;，包含已完成成员的正式有序快照
         * @param activeAssignees List&lt;String&gt;，当前仍有活动任务的办理人
         * @param requestedTaskAssignee String，本次 taskId 查询返回的任务办理人
         * @param revision int，服务端当前调整版本
         * @param completedCount int，Flowable 已完成实例数
         * @return 无返回值；构造后服务可立即执行一次只读或失败写命令
         */
        EngineFixture(List<String> memberIds, List<String> activeAssignees,
                String requestedTaskAssignee, int revision, int completedCount)
        {
            processVariables.put(WorkflowMultiInstanceVariables.memberSnapshotName(
                    ACTIVITY_ID), new ArrayList<>(memberIds));
            processVariables.put(WorkflowMultiInstanceVariables.revisionName(
                    ACTIVITY_ID), revision);
            processVariables.put(WorkflowMultiInstanceVariables.modeName(
                    ACTIVITY_ID), WorkflowMultiInstanceMode.ALL.name());
            processVariables.put(WorkflowMultiInstanceVariables.userCollectionName(
                    ACTIVITY_ID), memberIds.stream().map(Long::valueOf).toList());
            engineCounts.put("nrOfInstances", memberIds.size());
            engineCounts.put("nrOfActiveInstances", activeAssignees.size());
            engineCounts.put("nrOfCompletedInstances", completedCount);

            rootExecution = createRootExecution();
            initialMemberIds = List.copyOf(memberIds);
            // Flowable 8 在 ALL 未结束时保留已完成成员的 inactive child execution。
            initialMemberIds.forEach(this::createChildExecution);
            initialTasks = activeAssignees.stream().map(this::createTask).toList();
            activeAssignees.forEach(assignee -> activeExecutionIds.add("exec-" + assignee));
            currentTask = task(requestedTaskAssignee);
            stubStaticEngineState();
            configureAfter(null);
        }

        /**
         * 配置成功写命令应读取到的活动任务集合，并让动态 API 同步更新 Flowable 根计数。
         *
         * @param afterAssignees List&lt;String&gt;，写命令提交前预期的活动办理人；null 表示只读取写前状态
         * @return 无返回值；后续服务调用会依次读取写前和写后执行树
         */
        void configureAfter(List<String> afterAssignees)
        {
            List<Task> afterTasks = afterAssignees == null ? null
                    : afterAssignees.stream().map(this::createTask).toList();
            when(taskQuery.singleResult()).thenReturn(currentTask);
            when(taskQuery.list()).thenReturn(initialTasks,
                    afterTasks == null ? initialTasks : afterTasks);

            List<Execution> sequence = new ArrayList<>(executionSequence());
            if (afterTasks != null)
            {
                sequence.addAll(executionSequence());
            }
            Execution first = sequence.get(0);
            Execution[] remaining = sequence.subList(1, sequence.size())
                    .toArray(Execution[]::new);
            when(executionQuery.singleResult()).thenReturn(first, remaining);
            List<Execution> initialChildren = initialMemberIds.stream()
                    .map(executionsByAssignee::get).toList();
            List<Execution> afterChildren = afterTasks == null ? initialChildren
                    : childExecutionsWithRetainedInactive(afterTasks);
            when(executionQuery.list()).thenReturn(initialChildren,
                    afterChildren);
        }

        /**
         * 按办理人返回本 fixture 中主键稳定的活动任务。
         *
         * @param assignee String，数字用户主键
         * @return Task，具有完整流程、节点和 execution 关联的任务替身
         */
        Task task(String assignee)
        {
            return tasksByAssignee.computeIfAbsent(assignee, this::newTask);
        }

        /**
         * 把当前夹具切换到指定受控成员来源模型，其他执行树和正式变量快照保持不变。
         *
         * @param model BpmnModel，固定、发起时或办理时来源的完整受控多实例模型
         * @return 无返回值；后续服务查询读取该部署模型
         */
        void useModel(BpmnModel model)
        {
            when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(model);
        }

        /**
         * 配置模型、流程实例、变量、用户名称和动态 execution API 的公共行为。
         *
         * @return 无返回值；后续每次写变量都会更新同一正式变量投影
         */
        private void stubStaticEngineState()
        {
            when(repositoryService.getBpmnModel(DEFINITION_ID)).thenReturn(dynamicModel());
            ProcessInstance instance = mock(ProcessInstance.class);
            when(instance.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
            when(processInstanceQuery.singleResult()).thenReturn(instance);
            when(runtimeService.getVariable(eq(INSTANCE_ID), anyString()))
                    .thenAnswer(invocation -> processVariables.get(
                            invocation.getArgument(1, String.class)));
            when(runtimeService.getVariableLocal(eq(ROOT_EXECUTION_ID), anyString()))
                    .thenAnswer(invocation -> engineCounts.get(
                            invocation.getArgument(1, String.class)));
            when(runtimeService.getActiveActivityIds(anyString())).thenAnswer(invocation ->
                    activeExecutionIds.contains(invocation.getArgument(0, String.class))
                            ? List.of(ACTIVITY_ID) : List.of());
            org.mockito.Mockito.doAnswer(invocation ->
            {
                processVariables.put(invocation.getArgument(1, String.class),
                        invocation.getArgument(2));
                return null;
            }).when(runtimeService).setVariable(eq(INSTANCE_ID), anyString(), any());
            org.mockito.Mockito.doAnswer(invocation ->
            {
                Map<String, Object> executionVariables = invocation.getArgument(2);
                String addedAssignee = String.valueOf(executionVariables.get(
                        WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE));
                // 公共 ADD 命令成功后，新 child 立即承载活动 UserTask，活动投影必须同步变化。
                activeExecutionIds.add("exec-" + addedAssignee);
                engineCounts.compute("nrOfInstances", (key, value) -> value + 1);
                engineCounts.compute("nrOfActiveInstances", (key, value) -> value + 1);
                return null;
            }).when(runtimeService).addMultiInstanceExecution(eq(ACTIVITY_ID),
                    eq(INSTANCE_ID), anyMap());
            org.mockito.Mockito.doAnswer(invocation ->
            {
                activeExecutionIds.remove(invocation.getArgument(0, String.class));
                engineCounts.compute("nrOfInstances", (key, value) -> value - 1);
                engineCounts.compute("nrOfActiveInstances", (key, value) -> value - 1);
                return null;
            }).when(runtimeService).deleteMultiInstanceExecution(anyString(), eq(false));
            when(userMapper.selectUserNamesByIds(anyList())).thenAnswer(invocation ->
            {
                List<Long> userIds = invocation.getArgument(0);
                return userIds.stream().map(userId -> new WorkflowMultiInstanceUserRow(
                        userId, "用户" + userId)).toList();
            });
        }

        /**
         * 创建固定动态并行会签模型，覆盖服务读取的部署 BPMN 白名单字段。
         *
         * @return BpmnModel，主流程中包含受控 approveTask 用户任务的模型
         */
        private BpmnModel dynamicModel()
        {
            BpmnModel model = new BpmnModel();
            org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
            process.setId("process");
            process.setExecutable(true);
            model.addProcess(process);
            UserTask userTask = new UserTask();
            userTask.setId(ACTIVITY_ID);
            userTask.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
            MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
            loop.setSequential(false);
            loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
            loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
            loop.setCompletionCondition(
                    WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
            userTask.setLoopCharacteristics(loop);
            process.addFlowElement(userTask);
            return model;
        }

        /**
         * 创建具有稳定主键、办理人和流程关联的活动任务替身。
         *
         * @param assignee String，数字办理人主键
         * @return Task，默认未挂起、无 owner 且无 delegation 的活动任务
         */
        private Task newTask(String assignee)
        {
            Task task = mock(Task.class);
            when(task.getId()).thenReturn("task-" + assignee);
            when(task.getProcessInstanceId()).thenReturn(INSTANCE_ID);
            when(task.getProcessDefinitionId()).thenReturn(DEFINITION_ID);
            when(task.getTaskDefinitionKey()).thenReturn(ACTIVITY_ID);
            when(task.getExecutionId()).thenReturn("exec-" + assignee);
            when(task.getAssignee()).thenReturn(assignee);
            when(task.getCreateTime()).thenReturn(Date.from(Instant.ofEpochSecond(
                    Long.parseLong(assignee))));
            createChildExecution(assignee);
            return task;
        }

        /**
         * 创建或复用直接属于多实例根的活动子 execution。
         *
         * @param assignee String，对应任务办理人主键
         * @return Execution，activityId 与 parentId 均满足固定执行树契约
         */
        private Execution createChildExecution(String assignee)
        {
            return executionsByAssignee.computeIfAbsent(assignee, value ->
            {
                Execution execution = mock(Execution.class);
                when(execution.getId()).thenReturn("exec-" + value);
                when(execution.getParentId()).thenReturn(ROOT_EXECUTION_ID);
                when(execution.getActivityId()).thenReturn(ACTIVITY_ID);
                when(execution.getProcessInstanceId()).thenReturn(INSTANCE_ID);
                return execution;
            });
        }

        /**
         * 创建当前节点唯一并行多实例根 execution。
         *
         * @return Execution，属于当前流程实例且活动 ID 为 approveTask 的根 execution
         */
        private Execution createRootExecution()
        {
            Execution execution = mock(Execution.class);
            when(execution.getId()).thenReturn(ROOT_EXECUTION_ID);
            when(execution.getActivityId()).thenReturn(ACTIVITY_ID);
            when(execution.getProcessInstanceId()).thenReturn(INSTANCE_ID);
            return execution;
        }

        /**
         * 按一次 loadContext 的真实调用顺序生成 executionQuery.singleResult 返回序列。
         *
         * @return List&lt;Execution&gt;，当前任务 execution 和多实例根的有序序列
         */
        private List<Execution> executionSequence()
        {
            List<Execution> sequence = new ArrayList<>();
            sequence.add(executionsByAssignee.get(currentTask.getAssignee()));
            sequence.add(rootExecution);
            return sequence;
        }

        /**
         * 把活动任务集合映射为同顺序的直接子 execution 集合。
         *
         * @param tasks List&lt;Task&gt;，同一多实例根下的活动任务
         * @return List&lt;Execution&gt;，与任务 executionId 一一对应的直接子 execution
         */
        private List<Execution> childExecutions(List<Task> tasks)
        {
            return tasks.stream().map(task -> executionsByAssignee.get(task.getAssignee()))
                    .toList();
        }

        /**
         * 生成写后直接 child 集合，并保留 Flowable 8 在 ALL 模式下尚未清理的 inactive child。
         *
         * @param activeTasks List&lt;Task&gt;，写后仍承载活动用户任务的 child 集合
         * @return List&lt;Execution&gt;，inactive 历史 child 与写后活动 child 的稳定并集
         */
        private List<Execution> childExecutionsWithRetainedInactive(List<Task> activeTasks)
        {
            LinkedHashMap<String, Execution> retainedChildren = new LinkedHashMap<>();
            for (String initialMemberId : initialMemberIds)
            {
                String executionId = "exec-" + initialMemberId;
                if (!activeExecutionIds.contains(executionId))
                {
                    retainedChildren.put(executionId, executionsByAssignee.get(initialMemberId));
                }
            }
            for (Task task : activeTasks)
            {
                Execution execution = executionsByAssignee.get(task.getAssignee());
                retainedChildren.put(execution.getId(), execution);
            }
            return List.copyOf(retainedChildren.values());
        }

        /**
         * 创建任务并确保对应子 execution 同时注册到 fixture。
         *
         * @param assignee String，数字办理人主键
         * @return Task，已注册 execution 的活动任务
         */
        private Task createTask(String assignee)
        {
            return task(assignee);
        }
    }
}
