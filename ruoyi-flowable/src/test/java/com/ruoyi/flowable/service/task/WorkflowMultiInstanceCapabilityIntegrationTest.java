package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.stream.IntStream;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceMemberView;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;

/**
 * 使用真实 Spring、Flowable、事务和正式轮次 Mapper 验证动态多实例能力协议。
 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowMultiInstanceCapabilityIntegrationTest
{
    /** 正式动态调整服务。 */
    @Autowired
    private WorkflowMultiInstanceService multiInstanceService;

    /** 只通过生产入口启动和调整多实例。 */
    @Autowired
    private WorkflowMultiInstanceBusinessDriver driver;

    /** 读取真实任务、execution、变量和轮次快照。 */
    @Autowired
    private WorkflowMultiInstanceStateProbe probe;

    /** Flowable 公共任务服务，仅用于构造 owner/委派测试前置状态。 */
    @Autowired
    private TaskService taskService;

    /** Flowable 公共运行服务，仅用于构造 revision 上限测试前置状态。 */
    @Autowired
    private RuntimeService runtimeService;

    /** 正式业务轮次 Mapper，仅用于把测试前置 revision 同步到上限。 */
    @Autowired
    private WfMultiInstanceRoundMapper roundMapper;

    /** 测试前置状态的真实共享事务。 */
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void sameRequestProjectionMatchesAllAndAnyAndIndependentRequestsReauthorize()
    {
        for (String key : List.of("roundDynamicAll", "roundGroupFirstAny"))
        {
            String activity = key.equals("roundDynamicAll") ? "dynamicReview" : "firstAnyReview";
            ProcessInstance instance = driver.start(key, activity, List.of("201", "202"), java.util.Map.of());
            Task task = probe.task(instance.getId(), activity, "201");
            driver.asUser("201");
            var actor = new com.ruoyi.flowable.identity.WorkflowCurrentIdentity("201", java.util.Set.of());
            WorkflowMultiInstanceStateView projected = transactionTemplate.execute(status -> multiInstanceService.getOptionalState(task,
                    runtimeService.createProcessInstanceQuery().processInstanceId(instance.getId()).singleResult(), actor));
            assertThat(projected).isEqualTo(multiInstanceService.getState(task.getId()));
            taskService.setAssignee(task.getId(), "203");
            assertThat(catchThrowable(() -> multiInstanceService.getState(task.getId())))
                    .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(403));
            taskService.setAssignee(task.getId(), "201");
            runtimeService.suspendProcessInstanceById(instance.getId());
            assertStableConflict(() -> multiInstanceService.getState(task.getId()));
            runtimeService.activateProcessInstanceById(instance.getId());
            driver.complete(task, 0);
            assertStableConflict(() -> multiInstanceService.getState(task.getId()));
        }
    }

    /**
     * 验证九十九名正式成员仍有一个加签容量，且整体调整能力开启。
     *
     * @return void，容量、加签或整体能力协议漂移时测试失败
     */
    @Test
    void exposesOneRemainingSlotForNinetyNineMembers()
    {
        ProcessInstance instance = startWithMembers(99);
        Task currentTask = probe.task(instance.getId(), "dynamicReview", "201");

        WorkflowMultiInstanceStateView state = stateAs(currentTask);

        assertThat(state.remainingCapacity()).isEqualTo(1);
        assertThat(state.addAllowed()).isTrue();
        assertThat(state.adjustmentAllowed()).isTrue();
        assertThat(state.members()).hasSize(99);
    }

    /**
     * 验证加签到一百人后只关闭加签，仍保留符合规则的减签能力。
     *
     * @return void，满容量错误关闭整体调整或目标 removable 时测试失败
     */
    @Test
    void keepsRemovalAvailableAfterAddingTheHundredthMember()
    {
        ProcessInstance instance = startWithMembers(99);
        Task currentTask = probe.task(instance.getId(), "dynamicReview", "201");
        driver.addMember(currentTask, 0, 300L);

        WorkflowMultiInstanceStateView state = stateAs(currentTask);

        assertThat(state.remainingCapacity()).isZero();
        assertThat(state.addAllowed()).isFalse();
        assertThat(state.adjustmentAllowed()).isTrue();
        assertThat(state.members())
                .hasSize(WorkflowUserSelectionValidator.MAX_SELECTED_USERS);
        assertThat(state.members()).filteredOn(WorkflowMultiInstanceMemberView::removable)
                .isNotEmpty();
    }

    /**
     * 验证当前任务出现 owner 后查询和 ADD/REMOVE 写端共同失败关闭且没有任何写副作用。
     *
     * @return void，能力未关闭、错误码漂移或任一正式状态被修改时测试失败
     */
    @Test
    void rejectsOwnerContextBeforeAnyAdjustmentWrite()
    {
        ProcessInstance instance = startWithMembers(3);
        Task currentTask = probe.task(instance.getId(), "dynamicReview", "201");
        Task targetTask = probe.task(instance.getId(), "dynamicReview", "202");
        taskService.setOwner(currentTask.getId(), "900");

        assertClosedContextHasNoWriteSideEffects(instance, currentTask, targetTask, 301L);
    }

    /**
     * 验证当前任务进入真实 PENDING 委派后查询和 ADD/REMOVE 写端共同失败关闭。
     *
     * @return void，委派上下文仍开放能力或任一正式状态被修改时测试失败
     */
    @Test
    void rejectsDelegatedContextBeforeAnyAdjustmentWrite()
    {
        ProcessInstance instance = startWithMembers(3);
        Task sourceTask = probe.task(instance.getId(), "dynamicReview", "201");
        Task targetTask = probe.task(instance.getId(), "dynamicReview", "202");
        taskService.delegateTask(sourceTask.getId(), "301");
        // Flowable 委派会把 assignee 切给受托人；测试再通过公共 API 恢复正式成员，保留真实 owner/PENDING 状态。
        taskService.setAssignee(sourceTask.getId(), "201");
        Task delegatedTask = taskService.createTaskQuery().taskId(sourceTask.getId())
                .active().singleResult();
        assertThat(delegatedTask).isNotNull();

        assertClosedContextHasNoWriteSideEffects(
                instance, delegatedTask, targetTask, 302L);
    }

    /**
     * 验证 revision 达到 Integer.MAX_VALUE 时加签和全部减签在任何写副作用前关闭。
     *
     * @return void，能力未关闭、错误不稳定或正式状态发生变化时测试失败
     */
    @Test
    void rejectsMaximumRevisionBeforeAnyAdjustmentWrite()
    {
        ProcessInstance instance = startWithMembers(3);
        Task currentTask = probe.task(instance.getId(), "dynamicReview", "201");
        Task targetTask = probe.task(instance.getId(), "dynamicReview", "202");
        WfMultiInstanceRound round = probe.activeRound(instance.getId(), "dynamicReview");
        transactionTemplate.execute(status ->
        {
            runtimeService.setVariable(instance.getId(),
                    WorkflowMultiInstanceVariables.revisionName("dynamicReview"),
                    Integer.MAX_VALUE);
            assertThat(roundMapper.compareAndSetActiveSnapshot(round.getRoundId(),
                    round.getRevisionNo(), Integer.MAX_VALUE, round.getMembersJson()))
                    .isEqualTo(1);
            return null;
        });

        WorkflowMultiInstanceStateView state = stateAs(currentTask);
        assertThat(state.addAllowed()).isFalse();
        assertThat(state.adjustmentAllowed()).isFalse();
        assertThat(state.members()).noneMatch(WorkflowMultiInstanceMemberView::removable);

        WorkflowMultiInstanceStateProbe.CoreSnapshot before = probe.captureCore(instance.getId());
        List<CommentFact> commentsBefore = comments(instance.getId());
        assertStableConflict(() -> driver.addMember(
                currentTask, Integer.MAX_VALUE, 301L));
        assertStableConflict(() -> driver.removeMember(
                currentTask, targetTask, Integer.MAX_VALUE));
        assertThat(probe.captureCore(instance.getId())).isEqualTo(before);
        assertThat(comments(instance.getId())).isEqualTo(commentsBefore);
    }

    /**
     * 在 owner 或委派前置状态下核对能力协议、两种写命令和完整零副作用快照。
     *
     * @param instance ProcessInstance，真实运行流程实例
     * @param currentTask Task，当前正式 assignee 的 owner/委派任务
     * @param targetTask Task，待尝试减签的真实 sibling 任务
     * @param addUserId long，待尝试加签且尚未在成员中的用户主键
     * @return void，任一门禁或副作用断言不满足时测试失败
     */
    private void assertClosedContextHasNoWriteSideEffects(ProcessInstance instance,
            Task currentTask, Task targetTask, long addUserId)
    {
        WorkflowMultiInstanceStateView state = stateAs(currentTask);
        assertThat(state.addAllowed()).isFalse();
        assertThat(state.adjustmentAllowed()).isFalse();
        assertThat(state.members()).noneMatch(WorkflowMultiInstanceMemberView::removable);

        WorkflowMultiInstanceStateProbe.CoreSnapshot before = probe.captureCore(instance.getId());
        List<CommentFact> commentsBefore = comments(instance.getId());
        assertStableConflict(() -> driver.addMember(currentTask, 0, addUserId));
        assertStableConflict(() -> driver.removeMember(currentTask, targetTask, 0));
        assertThat(probe.captureCore(instance.getId())).isEqualTo(before);
        assertThat(comments(instance.getId())).isEqualTo(commentsBefore);
    }

    /**
     * 以当前任务办理人身份读取正式多实例状态。
     *
     * @param currentTask Task，当前活动办理任务
     * @return WorkflowMultiInstanceStateView，完成引擎与轮次对账的服务端状态
     */
    private WorkflowMultiInstanceStateView stateAs(Task currentTask)
    {
        driver.asUser(currentTask.getAssignee());
        return multiInstanceService.getState(currentTask.getId());
    }

    /**
     * 启动指定成员数量的真实受控 ALL 多实例流程。
     *
     * @param memberCount int，从用户 201 开始的正式成员数量
     * @return ProcessInstance，停留在 dynamicReview 的真实运行实例
     */
    private ProcessInstance startWithMembers(int memberCount)
    {
        List<String> members = IntStream.range(0, memberCount)
                .mapToObj(index -> String.valueOf(201 + index)).toList();
        return driver.start("roundDynamicAll", "dynamicReview", members,
                java.util.Map.of());
    }

    /**
     * 断言正式调整入口返回既有稳定 409，且不把普通状态冲突伪装成 revision loser 子码。
     *
     * @param action Runnable，待执行的 ADD 或 REMOVE 正式入口
     * @return void，错误类型、状态、消息或子码漂移时测试失败
     */
    private void assertStableConflict(Runnable action)
    {
        Throwable failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getMessage()).isEqualTo("工作流状态已发生变化，请刷新后重试");
            assertThat(exception.getSubCode()).isNull();
        });
    }

    /**
     * 冻结流程全部 comment 的稳定业务字段，验证失败命令没有写入审计。
     *
     * @param processInstanceId String，真实流程实例主键
     * @return List&lt;CommentFact&gt;，按 comment 主键排序的不可变审计快照
     */
    private List<CommentFact> comments(String processInstanceId)
    {
        return taskService.getProcessInstanceComments(processInstanceId).stream()
                .map(comment -> new CommentFact(comment.getId(), comment.getTaskId(),
                        comment.getType(), comment.getFullMessage()))
                .sorted(java.util.Comparator.comparing(CommentFact::id)).toList();
    }

    /**
     * 失败调整前后的最小 Flowable comment 事实。
     *
     * @param id String，comment 主键
     * @param taskId String，关联任务主键
     * @param type String，comment 类型
     * @param message String，完整审计正文
     */
    private record CommentFact(String id, String taskId, String type, String message)
    {
    }
}
