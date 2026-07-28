package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCopy;
import com.ruoyi.flowable.domain.dto.WorkflowTaskClaimRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskDelegateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskResolveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskTransferRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskUnclaimRequest;
import com.ruoyi.flowable.engine.WorkflowProcessEngineAdapter;
import com.ruoyi.flowable.engine.WorkflowTaskWriteHook;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;

class WorkflowTaskActionServiceTest
{
    private WorkflowProcessEngineAdapter processEngineAdapter;

    private WorkflowTaskCopyService taskCopyService;

    private WorkflowTaskActionService taskActionService;

    /**
     * 为每个测试创建独立适配器、抄送服务替身和任务动作服务。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        processEngineAdapter = mock(WorkflowProcessEngineAdapter.class);
        taskCopyService = mock(WorkflowTaskCopyService.class);
        taskActionService = new WorkflowTaskActionService(processEngineAdapter, taskCopyService);
    }

    /**
     * 验证认领、取消认领和完成委派请求把规范化参数传给适配器。
     *
     * @return 无返回值；映射错误时测试失败
     */
    @Test
    void mapsClaimUnclaimAndResolveRequestsToAdapter()
    {
        taskActionService.claim(new WorkflowTaskClaimRequest("  task-1  "));
        taskActionService.unclaim(new WorkflowTaskUnclaimRequest("  task-2  "));
        taskActionService.resolve(new WorkflowTaskResolveRequest(
                "  task-3  ", "  已完成合同核验  ", List.of(10L)));

        verify(processEngineAdapter).claimTaskForCurrentUser("task-1");
        verify(processEngineAdapter).unclaimTaskForCurrentUser("task-2");
        verify(processEngineAdapter).resolveTaskForCurrentUser(
                eq("task-3"), eq("已完成合同核验"), any(WorkflowTaskWriteHook.class));
    }

    /**
     * 验证委派和转办请求把目标用户转换为规范字符串，并去除任务主键和意见首尾空白。
     *
     * @return 无返回值；映射错误时测试失败
     */
    @Test
    void mapsDelegateAndTransferRequestsToAdapter()
    {
        taskActionService.delegate(new WorkflowTaskDelegateRequest(
                "  task-1  ", 8L, "  请协助处理  ", List.of(11L)));
        taskActionService.transfer(new WorkflowTaskTransferRequest(
                "  task-2  ", 9L, "  调整办理人  ", List.of(12L)));

        verify(processEngineAdapter).delegateTaskForCurrentUser(
                eq("task-1"), eq("8"), eq("请协助处理"),
                any(WorkflowTaskWriteHook.class));
        verify(processEngineAdapter).transferTaskForCurrentUser(
                eq("task-2"), eq("9"), eq("调整办理人"),
                any(WorkflowTaskWriteHook.class));
    }

    /**
     * 验证委派和转办先冻结抄送计划，并仅在模拟引擎动作成功后同一钩子持久化。
     *
     * @return 无返回值；计划类型、请求用户或准备/动作/持久化顺序不符合时测试失败
     */
    @Test
    void persistsDelegateAndTransferPlansOnlyAfterSuccessfulEngineAction()
    {
        WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("7", Set.of("ROLE2"));
        Task delegateTask = task("task-1");
        Task transferTask = task("task-2");
        WorkflowTaskCopyService.CopyPlan delegatePlan = copyPlan();
        WorkflowTaskCopyService.CopyPlan transferPlan = copyPlan();
        List<String> phases = new ArrayList<>();

        when(taskCopyService.prepare(WorkflowTaskCopyAction.DELEGATE,
                delegateTask, actor, List.of(11L))).thenAnswer(invocation ->
                {
                    phases.add("delegate-prepare");
                    return delegatePlan;
                });
        when(taskCopyService.prepare(WorkflowTaskCopyAction.TRANSFER,
                transferTask, actor, List.of(12L))).thenAnswer(invocation ->
                {
                    phases.add("transfer-prepare");
                    return transferPlan;
                });
        doAnswer(invocation ->
        {
            phases.add("delegate-persist");
            return null;
        }).when(taskCopyService).persist(delegatePlan);
        doAnswer(invocation ->
        {
            phases.add("transfer-persist");
            return null;
        }).when(taskCopyService).persist(transferPlan);
        doAnswer(invocation ->
        {
            WorkflowTaskWriteHook writeHook = invocation.getArgument(3);
            Runnable afterSuccess = writeHook.prepare(actor, delegateTask);
            phases.add("delegate-engine-success");
            afterSuccess.run();
            return null;
        }).when(processEngineAdapter).delegateTaskForCurrentUser(
                eq("task-1"), eq("8"), eq("请协助"), any(WorkflowTaskWriteHook.class));
        doAnswer(invocation ->
        {
            WorkflowTaskWriteHook writeHook = invocation.getArgument(3);
            Runnable afterSuccess = writeHook.prepare(actor, transferTask);
            phases.add("transfer-engine-success");
            afterSuccess.run();
            return null;
        }).when(processEngineAdapter).transferTaskForCurrentUser(
                eq("task-2"), eq("9"), eq("调整办理"), any(WorkflowTaskWriteHook.class));

        taskActionService.delegate(new WorkflowTaskDelegateRequest(
                "task-1", 8L, "请协助", List.of(11L)));
        taskActionService.transfer(new WorkflowTaskTransferRequest(
                "task-2", 9L, "调整办理", List.of(12L)));

        assertThat(phases).containsExactly(
                "delegate-prepare", "delegate-engine-success", "delegate-persist",
                "transfer-prepare", "transfer-engine-success", "transfer-persist");
    }

    /**
     * 验证抄送计划已经冻结但引擎动作失败时不会执行计划持久化。
     *
     * @return 无返回值；委派或转办失败后仍写入抄送记录时测试失败
     */
    @Test
    void doesNotPersistPreparedPlansWhenEngineActionFails()
    {
        WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("7", Set.of());
        Task delegateTask = task("task-1");
        Task transferTask = task("task-2");
        WorkflowTaskCopyService.CopyPlan delegatePlan = copyPlan();
        WorkflowTaskCopyService.CopyPlan transferPlan = copyPlan();
        ServiceException engineFailure = new ServiceException("模拟引擎失败", HttpStatus.CONFLICT);
        when(taskCopyService.prepare(WorkflowTaskCopyAction.DELEGATE,
                delegateTask, actor, List.of(11L))).thenReturn(delegatePlan);
        when(taskCopyService.prepare(WorkflowTaskCopyAction.TRANSFER,
                transferTask, actor, List.of(12L))).thenReturn(transferPlan);
        doAnswer(invocation ->
        {
            WorkflowTaskWriteHook writeHook = invocation.getArgument(3);
            writeHook.prepare(actor, delegateTask);
            throw engineFailure;
        }).when(processEngineAdapter).delegateTaskForCurrentUser(
                anyString(), anyString(), anyString(), any(WorkflowTaskWriteHook.class));
        doAnswer(invocation ->
        {
            WorkflowTaskWriteHook writeHook = invocation.getArgument(3);
            writeHook.prepare(actor, transferTask);
            throw engineFailure;
        }).when(processEngineAdapter).transferTaskForCurrentUser(
                anyString(), anyString(), anyString(), any(WorkflowTaskWriteHook.class));

        assertThatThrownBy(() -> taskActionService.delegate(new WorkflowTaskDelegateRequest(
                "task-1", 8L, "请协助", List.of(11L)))).isSameAs(engineFailure);
        assertThatThrownBy(() -> taskActionService.transfer(new WorkflowTaskTransferRequest(
                "task-2", 9L, "调整办理", List.of(12L)))).isSameAs(engineFailure);

        verify(taskCopyService, never()).persist(any(WorkflowTaskCopyService.CopyPlan.class));
    }

    /**
     * 验证非法、重复和停用抄送用户在模拟引擎动作前由准备钩子整体拒绝。
     *
     * @return 无返回值；非法用户触发引擎状态变化或计划持久化时测试失败
     */
    @Test
    void rejectsInvalidDuplicateAndInactiveCopyUsersBeforeEngineMutation()
    {
        WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("7", Set.of());
        Task task = task("task-1");
        AtomicInteger engineMutationCount = new AtomicInteger();
        ServiceException invalidSelection = new ServiceException(
                "工作流用户选择不合法", HttpStatus.BAD_REQUEST);
        when(taskCopyService.prepare(WorkflowTaskCopyAction.DELEGATE,
                task, actor, List.of(0L))).thenThrow(invalidSelection);
        when(taskCopyService.prepare(WorkflowTaskCopyAction.TRANSFER,
                task, actor, List.of(8L, 8L))).thenThrow(invalidSelection);
        when(taskCopyService.prepare(WorkflowTaskCopyAction.DELEGATE,
                task, actor, List.of(99L))).thenThrow(invalidSelection);
        doAnswer(invocation ->
        {
            WorkflowTaskWriteHook writeHook = invocation.getArgument(3);
            Runnable afterSuccess = writeHook.prepare(actor, task);
            engineMutationCount.incrementAndGet();
            afterSuccess.run();
            return null;
        }).when(processEngineAdapter).delegateTaskForCurrentUser(
                anyString(), anyString(), anyString(), any(WorkflowTaskWriteHook.class));
        doAnswer(invocation ->
        {
            WorkflowTaskWriteHook writeHook = invocation.getArgument(3);
            Runnable afterSuccess = writeHook.prepare(actor, task);
            engineMutationCount.incrementAndGet();
            afterSuccess.run();
            return null;
        }).when(processEngineAdapter).transferTaskForCurrentUser(
                anyString(), anyString(), anyString(), any(WorkflowTaskWriteHook.class));

        assertUserSelectionBadRequest(() -> taskActionService.delegate(
                new WorkflowTaskDelegateRequest("task-1", 8L, "委派", List.of(0L))));
        assertUserSelectionBadRequest(() -> taskActionService.transfer(
                new WorkflowTaskTransferRequest("task-1", 8L, "转办", List.of(8L, 8L))));
        assertUserSelectionBadRequest(() -> taskActionService.delegate(
                new WorkflowTaskDelegateRequest("task-1", 8L, "委派", List.of(99L))));

        assertThat(engineMutationCount.get()).isZero();
        verify(taskCopyService, never()).persist(any(WorkflowTaskCopyService.CopyPlan.class));
    }

    /**
     * 验证五个动作的空请求在进入引擎适配器前统一返回稳定 HTTP 400。
     *
     * @return 无返回值；错误码、提示或副作用不符合契约时测试失败
     */
    @Test
    void rejectsNullRequestsBeforeCallingAdapter()
    {
        assertBadRequest(() -> taskActionService.claim(null));
        assertBadRequest(() -> taskActionService.unclaim(null));
        assertBadRequest(() -> taskActionService.resolve(null));
        assertBadRequest(() -> taskActionService.delegate(null));
        assertBadRequest(() -> taskActionService.transfer(null));

        verifyNoInteractions(processEngineAdapter, taskCopyService);
    }

    /**
     * 验证空白或超长任务主键不能绕过 Controller 的 DTO 校验进入引擎。
     *
     * @return 无返回值；任一非法任务主键未返回 HTTP 400 时测试失败
     */
    @Test
    void rejectsInvalidTaskIdsBeforeCallingAdapter()
    {
        String oversizedTaskId = "t".repeat(65);
        assertBadRequest(() -> taskActionService.claim(new WorkflowTaskClaimRequest(" ")));
        assertBadRequest(() -> taskActionService.unclaim(new WorkflowTaskUnclaimRequest(oversizedTaskId)));
        assertBadRequest(() -> taskActionService.resolve(
                new WorkflowTaskResolveRequest(" ", "办结意见", List.of())));
        assertBadRequest(() -> taskActionService.delegate(
                new WorkflowTaskDelegateRequest(null, 8L, "委派意见", List.of())));
        assertBadRequest(() -> taskActionService.transfer(
                new WorkflowTaskTransferRequest(" ", 8L, "转办意见", List.of())));

        verifyNoInteractions(processEngineAdapter, taskCopyService);
    }

    /**
     * 验证委派和转办目标用户必须为非空正数，防止非法主键进入正式用户解析。
     *
     * @return 无返回值；任一非法目标用户未返回 HTTP 400 时测试失败
     */
    @Test
    void rejectsInvalidTargetUsersBeforeCallingAdapter()
    {
        assertBadRequest(() -> taskActionService.delegate(
                new WorkflowTaskDelegateRequest("task-1", null, "委派意见", List.of())));
        assertBadRequest(() -> taskActionService.delegate(
                new WorkflowTaskDelegateRequest("task-1", 0L, "委派意见", List.of())));
        assertBadRequest(() -> taskActionService.transfer(
                new WorkflowTaskTransferRequest("task-1", -1L, "转办意见", List.of())));

        verifyNoInteractions(processEngineAdapter, taskCopyService);
    }

    /**
     * 验证空意见和超过 500 字符的意见不能绕过 HTTP 层写入 Flowable 审计记录。
     *
     * @return 无返回值；任一非法意见未返回 HTTP 400 时测试失败
     */
    @Test
    void rejectsInvalidOpinionsBeforeCallingAdapter()
    {
        String oversizedOpinion = "a".repeat(501);
        assertBadRequest(() -> taskActionService.delegate(
                new WorkflowTaskDelegateRequest("task-1", 8L, null, List.of())));
        assertBadRequest(() -> taskActionService.delegate(
                new WorkflowTaskDelegateRequest("task-1", 8L, " ", List.of())));
        assertBadRequest(() -> taskActionService.transfer(
                new WorkflowTaskTransferRequest("task-1", 8L, oversizedOpinion, List.of())));

        verifyNoInteractions(processEngineAdapter, taskCopyService);
    }

    /**
     * 创建动作钩子测试使用的活动任务替身。
     *
     * @param taskId String，Flowable 任务主键
     * @return Task，仅携带测试识别所需任务主键的任务替身
     */
    private Task task(String taskId)
    {
        Task task = mock(Task.class);
        when(task.getId()).thenReturn(taskId);
        return task;
    }

    /**
     * 创建包含单条抄送记录的冻结计划，便于区分委派与转办持久化调用。
     *
     * @return CopyPlan，包含独立记录实例的不可变抄送计划
     */
    private WorkflowTaskCopyService.CopyPlan copyPlan()
    {
        return new WorkflowTaskCopyService.CopyPlan(List.of(new WfCopy()));
    }

    /**
     * 断言用户选择门禁返回稳定 HTTP 400，证明非法接收人不会进入引擎动作。
     *
     * @param action ThrowingCallable，预期被抄送用户校验拒绝的任务动作
     * @return 无返回值；异常类型、HTTP 状态或提示不匹配时测试失败
     */
    private void assertUserSelectionBadRequest(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage()).isEqualTo("工作流用户选择不合法");
        });
    }

    /**
     * 断言应用服务返回稳定参数错误，避免向调用方暴露底层引擎或身份解析细节。
     *
     * @param action ThrowingCallable，预期失败的应用服务调用
     * @return 无返回值；异常类型、HTTP 状态或提示不匹配时测试失败
     */
    private void assertBadRequest(ThrowingCallable action)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getMessage()).isEqualTo("工作流请求参数不合法");
        });
    }
}
