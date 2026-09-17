package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/** 正式取消/驳回/管理员终止入口，真实挂起/退回状态和同事务失败恢复。 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowProcessTerminalStateIntegrationTest
{
    @Autowired private WorkflowMultiInstanceBusinessDriver driver;
    @Autowired private WorkflowMultiInstanceStateProbe probe;
    @Autowired private WorkflowEngineOperations operations;
    @Autowired private WorkflowTaskRuntimeReader reader;
    @Autowired private WorkflowProcessInstanceService instances;
    @Autowired private WorkflowTaskActionAuditWriter audit;
    @Autowired private WorkflowTaskCopyService copies;
    @Autowired private WorkflowNotificationService notifications;
    @Autowired private RuntimeService runtime;
    @Autowired private TaskService tasks;

    @ParameterizedTest @ValueSource(strings={"canceled","rejected","terminated"})
    void terminalBusinessActionsOwnFinalStateAndRejectRepeatedCommands(String status)
    {
        ProcessInstance process = prepare(status);
        Task source = tasks.createTaskQuery().processInstanceId(process.getId()).list().get(0);
        terminal(status, process.getId(), source);
        assertThat(probe.processInstance(process.getId())).isNull();
        assertThat(probe.executions(process.getId())).isEmpty();
        probe.assertBusinessStatus(process.getId(), status);
        verify(notifications).onProcessResult(event(status), process.getProcessDefinitionId(), process.getId());
        assertThatThrownBy(() -> terminal(status, process.getId(), source)).isInstanceOf(ServiceException.class);
        probe.assertBusinessStatus(process.getId(), status);
        verify(notifications, times(1)).onProcessResult(event(status), process.getProcessDefinitionId(), process.getId());
    }

    @ParameterizedTest @ValueSource(strings={"canceled","rejected","terminated"})
    void terminalNotificationFailureRollsBackTheWholeCommandAndAllowsRetry(String status)
    {
        ProcessInstance process = prepare(status);
        Task source = tasks.createTaskQuery().processInstanceId(process.getId()).list().get(0);
        var before = probe.captureGroup(process.getId());
        boolean suspended = probe.processInstance(process.getId()).isSuspended();
        doThrow(new IllegalStateException("injected terminal notification failure"))
                .when(notifications).onProcessResult(eq(event(status)), anyString(), eq(process.getId()));
        assertThatThrownBy(() -> terminal(status, process.getId(), source))
                .hasStackTraceContaining("injected terminal notification failure");
        assertThat(probe.captureGroup(process.getId())).isEqualTo(before);
        assertThat(probe.processInstance(process.getId()).isSuspended()).isEqualTo(suspended);
        doReturn(0).when(notifications).onProcessResult(eq(event(status)), anyString(), eq(process.getId()));
        terminal(status, process.getId(), source);
        probe.assertBusinessStatus(process.getId(), status);
    }

    private ProcessInstance prepare(String status)
    {
        ProcessInstance process = driver.startLifecycle("roundGroupFirstAll", "firstAllReturnStart",
                "firstAllReview", List.of("201","202"));
        if ("canceled".equals(status))
            driver.returnGroup(probe.task(process.getId(), "firstAllReview", "201"), "201");
        if (!"rejected".equals(status)) runtime.suspendProcessInstanceById(process.getId());
        return process;
    }

    private void terminal(String status, String instanceId, Task source)
    {
        if ("terminated".equals(status)) { driver.terminate(instanceId); return; }
        if ("canceled".equals(status))
        {
            driver.asUser(WorkflowMultiInstanceBusinessDriver.APPLICANT_ID);
            new WorkflowProcessCancelApplicationService(operations, reader, instances, tasks, audit)
                    .cancelProcess(new WorkflowProcessCancelRequest(instanceId, "正式取消"));
        }
        else
        {
            driver.asUser(source.getAssignee());
            new WorkflowTaskRejectionApplicationService(operations, reader, instances, copies, audit, tasks)
                    .rejectTask(new WorkflowTaskRejectRequest(source.getId(), "正式驳回", List.of()));
        }
    }

    private String event(String status)
    {
        return switch(status) { case "canceled" -> "PROCESS_CANCELED"; case "rejected" -> "PROCESS_REJECTED"; default -> "PROCESS_TERMINATED"; };
    }
}
