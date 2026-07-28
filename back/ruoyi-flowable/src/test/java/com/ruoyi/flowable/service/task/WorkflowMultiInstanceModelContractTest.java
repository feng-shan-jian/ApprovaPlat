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
