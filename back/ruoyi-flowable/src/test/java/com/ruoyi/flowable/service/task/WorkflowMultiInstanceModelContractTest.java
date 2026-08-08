package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;

class WorkflowMultiInstanceModelContractTest
{
    /**
     * 验证固定 ALL 和 ANY 完成条件分别解析为唯一业务模式。
     *
     * @return 无返回值；任一固定模型未通过或模式映射错误时测试失败
     */
    @Test
    void acceptsOnlyFixedAllAndAnyModes()
    {
        assertThat(WorkflowMultiInstanceModelContract.requireMode(
                dynamicTask(WorkflowMultiInstanceMode.ALL)))
                .isEqualTo(WorkflowMultiInstanceMode.ALL);
        assertThat(WorkflowMultiInstanceModelContract.requireMode(
                dynamicTask(WorkflowMultiInstanceMode.ANY)))
                .isEqualTo(WorkflowMultiInstanceMode.ANY);
    }

    /**
     * 验证固定成员集合保留会签或或签完成语义，但不会被误分类为动态选人节点。
     *
     * @return 无返回值；固定成员解析、来源分类或模式映射错误时测试失败。
     */
    @Test
    void acceptsFixedMembersWithoutClassifyingThemAsDynamic()
    {
        UserTask fixedTask = fixedTask(WorkflowMultiInstanceMode.ALL, "8,9");

        assertThat(WorkflowMultiInstanceModelContract.requireMode(fixedTask))
                .isEqualTo(WorkflowMultiInstanceMode.ALL);
        assertThat(WorkflowMultiInstanceModelContract.usesFixedHandler(
                fixedTask.getLoopCharacteristics())).isTrue();
        assertThat(WorkflowMultiInstanceModelContract.usesDynamicHandler(
                fixedTask.getLoopCharacteristics())).isFalse();
        assertThat(WorkflowMultiInstanceModelContract.requireFixedUserIds(
                fixedTask.getLoopCharacteristics())).containsExactly(8L, 9L);
    }

    /**
     * 验证发起时成员来源使用独立白名单表达式并保留 ALL/ANY 执行语义。
     *
     * @return 无返回值；发起来源被误分类或模式解析错误时测试失败。
     */
    @Test
    void acceptsControlledStartMemberSource()
    {
        UserTask startTask = dynamicTask(WorkflowMultiInstanceMode.ANY);
        startTask.getLoopCharacteristics().setInputDataItem(
                WorkflowMultiInstanceModelContract.START_COLLECTION_EXPRESSION);

        assertThat(WorkflowMultiInstanceModelContract.requireMode(startTask))
                .isEqualTo(WorkflowMultiInstanceMode.ANY);
        assertThat(WorkflowMultiInstanceModelContract.usesStartHandler(
                startTask.getLoopCharacteristics())).isTrue();
        assertThat(WorkflowMultiInstanceModelContract.usesDynamicHandler(
                startTask.getLoopCharacteristics())).isFalse();
        assertThat(WorkflowMultiInstanceModelContract.usesFixedHandler(
                startTask.getLoopCharacteristics())).isFalse();
    }

    /**
     * 验证固定集合成员必须唯一且使用受控数字主键，防止部署模型隐含重复任务。
     *
     * @return 无返回值；重复成员模型被接受时测试失败。
     */
    @Test
    void rejectsFixedMembersWithDuplicates()
    {
        assertUnsupported(fixedTask(WorkflowMultiInstanceMode.ANY, "8,8"));
    }

    /**
     * 验证串行、自由集合、子流程、补偿和边界事件节点均不进入动态调整支持面。
     *
     * @return 无返回值；任一不安全模型被接受时测试失败
     */
    @Test
    void rejectsUnsafeModelVariants()
    {
        UserTask sequential = dynamicTask(WorkflowMultiInstanceMode.ALL);
        sequential.getLoopCharacteristics().setSequential(true);
        assertUnsupported(sequential);

        UserTask alternateCollection = dynamicTask(WorkflowMultiInstanceMode.ALL);
        alternateCollection.getLoopCharacteristics().setCollectionString("users");
        assertUnsupported(alternateCollection);

        UserTask subprocessTask = dynamicTask(WorkflowMultiInstanceMode.ALL);
        SubProcess subprocess = new SubProcess();
        subprocess.setId("sub");
        subprocess.addFlowElement(subprocessTask);
        assertUnsupported(subprocessTask);

        UserTask compensation = dynamicTask(WorkflowMultiInstanceMode.ALL);
        compensation.setForCompensation(true);
        assertUnsupported(compensation);

        UserTask boundary = dynamicTask(WorkflowMultiInstanceMode.ALL);
        boundary.getBoundaryEvents().add(new BoundaryEvent());
        assertUnsupported(boundary);
    }

    /**
     * 验证动态目标必须同步创建且不可跳过，避免来源完成后无法立即对账真实 task 和 execution。
     *
     * @return 无返回值；任一异步、非排他或 skip 目标被接受时测试失败
     */
    @Test
    void rejectsAsyncNonExclusiveAndSkippableDynamicTargets()
    {
        UserTask async = dynamicTask(WorkflowMultiInstanceMode.ALL);
        async.setAsynchronous(true);
        assertUnsupported(async);

        UserTask asyncLeave = dynamicTask(WorkflowMultiInstanceMode.ALL);
        asyncLeave.setAsynchronousLeave(true);
        assertUnsupported(asyncLeave);

        UserTask notExclusive = dynamicTask(WorkflowMultiInstanceMode.ALL);
        notExclusive.setNotExclusive(true);
        assertUnsupported(notExclusive);

        UserTask asyncLeaveNotExclusive = dynamicTask(WorkflowMultiInstanceMode.ALL);
        asyncLeaveNotExclusive.setAsynchronousLeaveNotExclusive(true);
        assertUnsupported(asyncLeaveNotExclusive);

        UserTask skippable = dynamicTask(WorkflowMultiInstanceMode.ALL);
        skippable.setSkipExpression("${skipApproval}");
        assertUnsupported(skippable);
    }

    /**
     * 验证动态多实例全部变量前缀被统一识别为保留命名空间。
     *
     * @return 无返回值；客户端可覆盖任一协议变量时测试失败
     */
    @Test
    void identifiesAllReservedVariablePrefixes()
    {
        assertThat(WorkflowMultiInstanceVariables.isReservedVariableName(
                "wfMiUsers_approveTask")).isTrue();
        assertThat(WorkflowMultiInstanceVariables.isReservedVariableName(
                "_wfMiMembers_approveTask")).isTrue();
        assertThat(WorkflowMultiInstanceVariables.isReservedVariableName(
                "_wfMiRevision_approveTask")).isTrue();
        assertThat(WorkflowMultiInstanceVariables.isReservedVariableName(
                "_wfMiMode_approveTask")).isTrue();
        assertThat(WorkflowMultiInstanceVariables.isReservedVariableName("reason"))
                .isFalse();
        assertThatThrownBy(() -> WorkflowMultiInstanceVariables.userCollectionName(
                "../approve")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 创建完整满足固定动态多实例契约的主流程用户任务。
     *
     * @param mode WorkflowMultiInstanceMode，ALL 或 ANY
     * @return UserTask，主流程中的动态并行多实例节点
     */
    private UserTask dynamicTask(WorkflowMultiInstanceMode mode)
    {
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("process");
        UserTask task = new UserTask();
        task.setId("approveTask");
        task.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(mode == WorkflowMultiInstanceMode.ALL
                ? WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION
                : WorkflowMultiInstanceModelContract.ANY_COMPLETION_CONDITION);
        task.setLoopCharacteristics(loop);
        process.addFlowElement(task);
        return task;
    }

    /**
     * 创建完整满足固定成员受控多实例契约的主流程用户任务。
     *
     * @param mode WorkflowMultiInstanceMode，ALL 或 ANY。
     * @param userIds String，逗号分隔且已规范化的固定用户主键。
     * @return UserTask，主流程中的固定成员并行多实例节点。
     */
    private UserTask fixedTask(WorkflowMultiInstanceMode mode, String userIds)
    {
        UserTask task = dynamicTask(mode);
        task.getLoopCharacteristics().setInputDataItem(
                "${multiInstanceHandler.getFixedUserIds(execution, '" + userIds + "')}");
        return task;
    }

    /**
     * 断言指定模型不属于动态多实例支持面。
     *
     * @param task UserTask，待校验的不安全模型节点
     * @return 无返回值；模型未抛出 IllegalArgumentException 时测试失败
     */
    private void assertUnsupported(UserTask task)
    {
        assertThatThrownBy(() -> WorkflowMultiInstanceModelContract.requireMode(task))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
