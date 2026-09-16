package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.task.*;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import com.ruoyi.system.mapper.SysUserMapper;

/** 真实 Controller、分配和用户选择服务到既有安全异常边界；引擎与目录在明确故障点隔离。 */
class WfTaskIdentityFailureControllerTest
{
    @ParameterizedTest
    @ValueSource(strings = { "approval-database", "active-database", "approval-ineligible", "active-ineligible" })
    @SuppressWarnings("unchecked")
    void databaseFailureIsSafeSystemErrorAndIneligibilityRemainsBusinessError(String mode) throws Exception
    {
        var mapper = mock(WorkflowIdentityMapper.class);
        when(mapper.selectApprovalEligibleUserIdsByUserIds(anyList())).thenAnswer(i -> i.getArgument(0));
        var identities = new WorkflowIdentityResolver(mapper, new WorkflowIdentityCodec());
        var databaseFailure = new DataAccessResourceFailureException("SELECT credential-sentinel FROM identity-private-table");
        if (mode.startsWith("approval"))
        {
            if (mode.endsWith("database")) when(mapper.selectApprovalEligibleUserIdsByUserIds(List.of(302L))).thenThrow(databaseFailure);
            else when(mapper.selectApprovalEligibleUserIdsByUserIds(List.of(302L))).thenReturn(List.of());
        }
        else if (mode.endsWith("database")) when(mapper.selectActiveUserIdsByUserIds(List.of(999L))).thenThrow(databaseFailure);
        var tasks = mock(TaskService.class);
        var taskQuery = mock(org.flowable.task.api.TaskQuery.class, RETURNS_SELF);
        when(tasks.createTaskQuery()).thenReturn(taskQuery);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("task-1"); when(task.getAssignee()).thenReturn("301");
        when(task.getProcessInstanceId()).thenReturn("process-1"); when(task.getProcessDefinitionId()).thenReturn("definition-1");
        when(task.getTaskDefinitionKey()).thenReturn("review");
        when(taskQuery.singleResult()).thenReturn(task);
        var runtime = mock(RuntimeService.class);
        var processQuery = mock(org.flowable.engine.runtime.ProcessInstanceQuery.class, RETURNS_SELF);
        when(runtime.createProcessInstanceQuery()).thenReturn(processQuery);
        when(processQuery.singleResult()).thenReturn(mock(org.flowable.engine.runtime.ProcessInstance.class));
        var repository = mock(RepositoryService.class, RETURNS_DEEP_STUBS);
        when(repository.getProcessDefinition("definition-1").getKey()).thenReturn("process");
        var model = new BpmnModel();
        var process = new org.flowable.bpmn.model.Process(); process.setId("process");
        var review = new UserTask(); review.setId("review"); process.addFlowElement(review); model.addProcess(process);
        when(repository.getBpmnModel("definition-1")).thenReturn(model);
        var operations = mock(WorkflowEngineOperations.class);
        when(operations.writeAsCurrentUser(any(Function.class))).thenAnswer(i ->
                ((Function<WorkflowCurrentIdentity, ?>) i.getArgument(0)).apply(new WorkflowCurrentIdentity("301", Set.of())));
        var copyMapper = mock(WfCopyMapper.class);
        var revisions = mock(WorkflowRuntimeTaskMapper.class);
        var notifications = mock(WorkflowNotificationService.class);
        var copies = new WorkflowTaskCopyService(new WorkflowUserSelectionValidator(identities), copyMapper, revisions,
                repository, runtime, mock(SysUserMapper.class), notifications);
        var actions = new WorkflowTaskActionService(repository, runtime, tasks, operations, identities, notifications,
                new WorkflowTaskUnclaimPolicy(), copies);
        var controller = new WfTaskController(actions, mock(WorkflowProcessCancelApplicationService.class),
                mock(WorkflowTaskRevokeApplicationService.class), mock(WorkflowTaskCompletionApplicationService.class),
                mock(WorkflowTaskRejectionApplicationService.class), mock(WorkflowTaskReturnApplicationService.class),
                mock(WorkflowApplicationResubmitApplicationService.class), mock(WorkflowMultiInstanceService.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        var response = mvc.perform(post("/workflow/task/delegate").contentType("application/json")
                .content("{\"taskId\":\"task-1\",\"userId\":302,\"comment\":\"delegate\",\"copyUserIds\":[999]}"))
                .andExpect(jsonPath("$.code").value(mode.endsWith("database") ? 500 : 400));
        if (mode.endsWith("database"))
        {
            response.andExpect(jsonPath("$.errorId").isNotEmpty());
            assertThat(response.andReturn().getResolvedException()).isSameAs(databaseFailure);
            assertThat(response.andReturn().getResponse().getContentAsString()).doesNotContain("SELECT", "credential-sentinel", "identity-private-table");
        }
        else response.andExpect(jsonPath("$.msg").value(mode.startsWith("active") ? "工作流用户选择不合法" : "工作流请求参数不合法"));
        verify(tasks, never()).delegateTask(anyString(), anyString());
        verifyNoInteractions(copyMapper, revisions, notifications);
    }
}
