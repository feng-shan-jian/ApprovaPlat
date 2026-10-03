package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 准备资格和关联合同独立于审计、附件、抄送、通知及流程引擎装配。 */
class WorkflowTaskPreparationTest
{
    private final RuntimeService runtime = mock(RuntimeService.class);
    private final TaskService tasks = mock(TaskService.class);
    private final Task task = mock(Task.class);
    private final ProcessInstance instance = mock(ProcessInstance.class);
    private final WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("100", Set.of());
    private WorkflowTaskReturnPreparation returns;
    private WorkflowApplicationResubmitPreparation resubmits;
    private WorkflowTaskBpmnSnapshot bpmn;
    private HistoricTaskInstance historic;
    private HistoricActivityInstance start;
    private WfDeployForm form;

    @BeforeEach
    void setUp()
    {
        var operations = new WorkflowEngineOperations(null, new WorkflowExceptionTranslator(), null);
        var reader = new WorkflowTaskRuntimeReader(runtime, tasks, null);
        var returnedState = new WorkflowReturnedTaskStateService(new WorkflowReturnedAssignmentCodec(), runtime, tasks, null);
        var groups = mock(WorkflowMultiInstanceGroupTransitionService.class);
        returns = new WorkflowTaskReturnPreparation(operations, reader, null,
                new WorkflowTaskMovementPolicy(), returnedState, groups);
        resubmits = new WorkflowApplicationResubmitPreparation(operations, reader,
                returnedState, groups, null, runtime);
        when(task.getId()).thenReturn("task");
        when(task.getProcessInstanceId()).thenReturn("instance");
        when(task.getProcessDefinitionId()).thenReturn("definition");
        when(task.getTaskDefinitionKey()).thenReturn("review");
        when(task.getExecutionId()).thenReturn("execution");
        when(task.getAssignee()).thenReturn("100");
        when(instance.getId()).thenReturn("instance");
        when(instance.getDeploymentId()).thenReturn("deployment");
        when(instance.getStartUserId()).thenReturn("100");
        when(instance.getBusinessStatus()).thenReturn("running");
        var process = new org.flowable.bpmn.model.Process();
        process.setId("approval");
        var review = new UserTask(); review.setId("review"); process.addFlowElement(review);
        var model = new BpmnModel(); model.addProcess(process);
        var definition = mock(ProcessDefinition.class); when(definition.getKey()).thenReturn("approval");
        bpmn = new WorkflowTaskBpmnSnapshot(definition, model, process);
        historic = mock(HistoricTaskInstance.class); when(historic.getTaskDefinitionKey()).thenReturn("review");
        start = mock(HistoricActivityInstance.class);
        when(start.getActivityType()).thenReturn("startEvent"); when(start.getActivityId()).thenReturn("start");
        form = new WfDeployForm(); form.setNodeKey("start"); form.setSourceType("TEMPLATE");
        form.setFormId(1L); form.setFormKey("application"); form.setContent("{}");
        when(tasks.getVariableLocal("task", WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE))
                .thenReturn(new WorkflowReturnedAssignmentCodec().encode(
                        new ReturnedAssignmentSnapshot("200", null, List.of(), List.of())));
        when(runtime.getVariable("instance", WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME))
                .thenReturn(WorkflowFormSubmissionSnapshotCodec.encodeStart("deployment", "TEMPLATE", 1L,
                        "application", "start", Map.of("title", "original")));
    }

    @Test
    void returnEligibilityUsesSuppliedFactsAndRejectsChangedAssignments()
    {
        assertThat(returnAllowed()).isTrue();
        verifyNoInteractions(runtime);
        when(task.getAssignee()).thenReturn("200"); assertThat(returnAllowed()).isFalse();
        when(task.getAssignee()).thenReturn("100"); when(task.getOwner()).thenReturn("200");
        assertThat(returnAllowed()).isFalse();
        when(task.getOwner()).thenReturn(null); when(instance.getBusinessStatus()).thenReturn("returned");
        assertThat(returnAllowed()).isFalse();
        when(instance.getBusinessStatus()).thenReturn("running"); when(task.isSuspended()).thenReturn(true);
        assertThat(returnAllowed()).isFalse();
    }

    @Test
    void missingReturnHistoryIsIneligibleButBrokenApplicantAssociationIsAnError()
    {
        assertThat(returns.isTaskReturnAllowed(task, instance, actor, bpmn, null, List::of, List.of(task))).isFalse();
        when(instance.getStartUserId()).thenReturn(null);
        assertThatThrownBy(this::returnAllowed).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(500));
    }

    @Test
    void resubmitRequiresAssignmentAndOriginalFormAssociation()
    {
        assertThat(resubmitAllowed()).isTrue();
        when(tasks.getVariableLocal("task", WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE)).thenReturn(null);
        assertThat(resubmitAllowed()).isFalse();
        verify(runtime, times(1)).getVariable("instance", WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME);
    }

    @Test
    void invalidFormIsIneligibleAndCorruptSubmissionIsNeverHidden()
    {
        form.setNodeKey("different"); assertThat(resubmitAllowed()).isFalse();
        form.setNodeKey("start");
        when(runtime.getVariable("instance", WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME))
                .thenReturn(WorkflowFormSubmissionSnapshotCodec.encodeStart("other-deployment", "TEMPLATE", 1L,
                        "application", "start", Map.of()));
        assertThatThrownBy(this::resubmitAllowed).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(500));
    }

    @Test
    void ineligibleCapabilitiesDoNotMarkTheSharedTransactionRollbackOnly()
    {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:preparation" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("create table committed_marker (id int primary key)");
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        var proxy = new org.springframework.aop.framework.ProxyFactory(
                new WorkflowEngineOperations(null, new WorkflowExceptionTranslator(), null));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var operations = (WorkflowEngineOperations) proxy.getProxy();
        var reader = new WorkflowTaskRuntimeReader(runtime, tasks, null);
        var returnRules = new WorkflowTaskReturnPreparation(operations, reader, null, null, null, null);
        var resubmitRules = new WorkflowApplicationResubmitPreparation(operations, reader, null, null, null, runtime);
        when(task.getAssignee()).thenReturn("200");
        try
        {
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status -> {
                assertThat(returnRules.isTaskReturnAllowed(task, instance, actor, bpmn, null,
                        () -> List.of(historic), List.of(task))).isFalse();
                assertThat(resubmitRules.isApplicationResubmitAllowed(
                        new WorkflowReturnedTaskStateService.ReturnedApplicant(task, instance), actor,
                        bpmn, List.of(task), () -> List.of(form), () -> List.of(start))).isFalse();
                assertThat(status.isRollbackOnly()).isFalse();
                jdbc.update("insert into committed_marker values (1)");
            });
            assertThat(jdbc.queryForObject("select count(*) from committed_marker", Integer.class)).isOne();
        }
        finally
        {
            jdbc.execute("shutdown");
        }
    }

    private boolean returnAllowed()
    {
        return returns.isTaskReturnAllowed(task, instance, actor, bpmn, null,
                () -> List.of(historic), List.of(task));
    }

    private boolean resubmitAllowed()
    {
        return resubmits.isApplicationResubmitAllowed(new WorkflowReturnedTaskStateService.ReturnedApplicant(task, instance),
                actor, bpmn, List.of(task), () -> List.of(form), () -> List.of(start));
    }
}
