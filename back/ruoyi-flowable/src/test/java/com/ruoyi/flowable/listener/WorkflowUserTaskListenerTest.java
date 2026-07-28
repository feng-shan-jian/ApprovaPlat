package com.ruoyi.flowable.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.task.service.delegate.DelegateTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.service.task.WorkflowUserTaskAuditService;

class WorkflowUserTaskListenerTest
{
    private WorkflowUserTaskAuditService auditService;
    private WorkflowUserTaskListener listener;

    /**
     * 为每个测试创建隔离的领域服务替身和受控监听器。
     *
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        auditService = mock(WorkflowUserTaskAuditService.class);
        listener = new WorkflowUserTaskListener(auditService);
    }

    /**
     * 验证 Spring 组件名称精确固定为 BPMN delegateExpression 使用的 userTaskListener。
     *
     * @return void，Bean 名称缺失或漂移时测试失败
     */
    @Test
    void exposesExactSpringBeanName()
    {
        Component component = WorkflowUserTaskListener.class.getAnnotation(Component.class);

        assertThat(component).isNotNull();
        assertThat(component.value()).isEqualTo("userTaskListener");
    }

    /**
     * 验证三个批准事件只转发 Flowable 固有任务元数据给领域服务。
     *
     * @param eventName String，create、assignment 或 complete 事件
     * @return void，事件或上下文转发不完整时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = { "create", "assignment", "complete" })
    void forwardsOnlyApprovedEvents(String eventName)
    {
        DelegateTask task = task(eventName);

        listener.notify(task);

        verify(auditService).recordAudit(eventName, "task-7", "instance-8",
                "expense:3:12001", "approveTask", "7", "8");
    }

    /**
     * 验证 delete、update、timeout 及其他非批准事件在领域服务前被拒绝。
     *
     * @param eventName String，未批准的 Flowable 任务事件
     * @return void，未知事件被执行或产生审计副作用时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = { "delete", "update", "timeout", "all", " " })
    void rejectsUnapprovedEvents(String eventName)
    {
        DelegateTask task = task(eventName);

        assertThatThrownBy(() -> listener.notify(task))
                .isInstanceOf(FlowableIllegalArgumentException.class)
                .hasMessage("用户任务监听事件不受支持");

        verify(auditService, never()).recordAudit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 验证缺失 DelegateTask 上下文会阻止监听执行。
     *
     * @return void，空上下文进入领域服务时测试失败
     */
    @Test
    void rejectsMissingDelegateTask()
    {
        assertThatThrownBy(() -> listener.notify(null))
                .isInstanceOf(FlowableIllegalArgumentException.class)
                .hasMessage("用户任务监听上下文不能为空");

        verify(auditService, never()).recordAudit(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * 创建包含固定任务关联、办理人和 owner 的 Flowable 任务替身。
     *
     * @param eventName String，任务当前监听事件名
     * @return DelegateTask，供白名单分派测试使用的任务上下文
     */
    private DelegateTask task(String eventName)
    {
        DelegateTask task = mock(DelegateTask.class);
        when(task.getEventName()).thenReturn(eventName);
        when(task.getId()).thenReturn("task-7");
        when(task.getProcessInstanceId()).thenReturn("instance-8");
        when(task.getProcessDefinitionId()).thenReturn("expense:3:12001");
        when(task.getTaskDefinitionKey()).thenReturn("approveTask");
        when(task.getAssignee()).thenReturn("7");
        when(task.getOwner()).thenReturn("8");
        return task;
    }
}
