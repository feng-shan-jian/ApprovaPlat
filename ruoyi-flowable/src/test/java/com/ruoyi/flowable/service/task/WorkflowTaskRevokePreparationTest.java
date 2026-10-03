package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;
import com.ruoyi.flowable.service.process.WorkflowProcessTaskQueryService;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/** 资格测试仅装配真实引擎与只读准备；写命令单独接入审计和并发执行器。 */
class WorkflowTaskRevokePreparationTest
{
    private WorkflowFlowableEngineTestSupport infra;
    private RuntimeService runtime;
    private TaskService tasks;
    private WorkflowEngineOperations operations;
    private WorkflowIdentityResolver identities;
    private WorkflowTaskRuntimeReader reader;
    private WorkflowTaskRevokePreparation preparation;
    private final WorkflowCurrentIdentity actor = new WorkflowCurrentIdentity("100", Set.of());

    @BeforeEach
    void setUp()
    {
        infra = WorkflowFlowableEngineTestSupport.start("revoke", Map.of());
        var engine = infra.processEngine();
        runtime = engine.getRuntimeService(); tasks = engine.getTaskService();
        identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(actor);
        operations = infra.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(engine.getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identities));
        reader = spy(new WorkflowTaskRuntimeReader(runtime, tasks, engine.getHistoryService()));
        preparation = spy(new WorkflowTaskRevokePreparation(operations, new WorkflowTaskRequestValidator(), reader,
                new WorkflowTaskBpmnReader(engine.getRepositoryService()), new WorkflowTaskMovementPolicy(), runtime, tasks));
        engine.getRepositoryService().createDeployment().category("test").addString("revoke.bpmn20.xml", """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="revoke">
                <process id="revoke" isExecutable="true"><startEvent id="s"/><userTask id="a" flowable:assignee="100"/>
                <userTask id="b" flowable:assignee="200"/><userTask id="c" flowable:assignee="300"/><endEvent id="e"/>
                <sequenceFlow id="sa" sourceRef="s" targetRef="a"/><sequenceFlow id="ab" sourceRef="a" targetRef="b"/>
                <sequenceFlow id="bc" sourceRef="b" targetRef="c"/><sequenceFlow id="ce" sourceRef="c" targetRef="e"/>
                </process></definitions>
                """).deploy();
    }

    @AfterEach
    void close() { infra.close(); }

    @Test
    void pageResolvesIdentityOnceReusesHistoryAndExportDoesNotComputeEligibility()
    {
        completeFirst(); completeFirst();
        var engine = infra.processEngine();
        var query = new WorkflowProcessTaskQueryService(operations, engine.getRepositoryService(),
                engine.getHistoryService(), tasks, identities, mock(WorkflowMultiInstanceUserMapper.class),
                preparation, mock(WorkflowTaskUnclaimPolicy.class));
        clearInvocations(identities, reader, preparation);
        var page = query.listCompleted(null, 1, 10);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.rows()).allMatch(row -> row.revocable());
        verify(identities, times(1)).resolveCurrentIdentity();
        verify(reader, never()).requireCompletedTask(anyString());
        verify(preparation, times(2)).isProcessRevocable(any(), same(actor));
        clearInvocations(preparation);
        assertThat(query.listCompletedForExport(null, 1, 10).rows()).hasSize(2);
        verifyNoInteractions(preparation);
    }

    @Test
    void readProjectionKeepsBusinessFalseAndDataFailureWithoutPoisoningOuterTransaction()
    {
        HistoricTaskInstance completed = completeFirst();
        assertThat(preparation.isProcessRevocable(completed, actor)).isTrue();
        infra.transactionTemplate().executeWithoutResult(status -> {
            assertThat(preparation.isProcessRevocable(completed,
                    new WorkflowCurrentIdentity("999", Set.of()))).isFalse();
            assertThat(status.isRollbackOnly()).isFalse();
        });
        for (int code : List.of(400, 403, 404, 409))
        {
            doThrow(new ServiceException("synthetic", code)).when(reader).requireActiveProcessInstance(completed.getProcessInstanceId());
            assertThat(preparation.isProcessRevocable(completed, actor)).isFalse();
        }
        doThrow(new ServiceException("corruption", 500)).when(reader).requireActiveProcessInstance(completed.getProcessInstanceId());
        assertThatThrownBy(() -> preparation.isProcessRevocable(completed, actor))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(500));
    }

    @Test
    void approvalCommittedAfterPreparationRejectsStaleRevokeWithoutAudit() throws Exception
    {
        HistoricTaskInstance completed = completeFirst();
        String process = completed.getProcessInstanceId();
        Task successor = active(process);
        var ready = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        doAnswer(invocation -> {
            Object plan = invocation.callRealMethod();
            ready.countDown();
            assertThat(resume.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return plan;
        }).when(preparation).requireRevokePreparation(process, completed.getId(), actor);
        var writer = new WorkflowTaskRevokeApplicationService(operations, preparation, new WorkflowTaskActionAuditWriter(tasks),
                new WorkflowTaskConcurrencyExecutor(), runtime, tasks);
        try
        {
            var pending = worker.submit(() -> writer.revokeProcess(new WorkflowProcessRevokeRequest(process, completed.getId(), "undo")));
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            tasks.complete(successor.getId(), "200", Map.of(), false);
            resume.countDown();
            assertThatThrownBy(() -> pending.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ServiceException.class)
                    .satisfies(failure -> assertThat(((ServiceException) failure.getCause()).getCode()).isEqualTo(409));
            assertThat(active(process).getTaskDefinitionKey()).isEqualTo("c");
            assertThat(tasks.getProcessInstanceComments(process, "REVOKE")).isEmpty();
        }
        finally
        {
            resume.countDown(); worker.shutdownNow();
            assertThat(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void downstreamActivityAndCancellationRejectRevokeAndWriteFailureRollsBack()
    {
        HistoricTaskInstance completed = completeFirst();
        String process = completed.getProcessInstanceId();
        var audit = new WorkflowTaskActionAuditWriter(tasks);
        var writer = new WorkflowTaskRevokeApplicationService(operations, preparation, audit,
                new WorkflowTaskConcurrencyExecutor(), runtime, tasks);
        var request = new WorkflowProcessRevokeRequest(process, completed.getId(), "undo");
        Task successor = active(process);
        assertThatThrownBy(() -> infra.transactionTemplate().executeWithoutResult(status -> {
            writer.revokeProcess(request);
            assertThat(active(process).getTaskDefinitionKey()).isEqualTo("a");
            throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(active(process).getId()).isEqualTo(successor.getId());
        assertThat(tasks.getTaskComments(successor.getId())).isEmpty();
        operations.writeAsCurrentUser(() -> tasks.complete(successor.getId(), "200", Map.of(), false));
        assertThat(preparation.isProcessRevocable(completed, actor)).isFalse();
        assertThatThrownBy(() -> writer.revokeProcess(request)).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(409));
        runtime.deleteProcessInstance(process, "cancelled");
        assertThat(preparation.isProcessRevocable(completed, actor)).isFalse();
        HistoricTaskInstance ended = completeFirst();
        tasks.complete(active(ended.getProcessInstanceId()).getId(), "200", Map.of(), false);
        tasks.complete(active(ended.getProcessInstanceId()).getId(), "300", Map.of(), false);
        assertThat(preparation.isProcessRevocable(ended, actor)).isFalse();
    }

    private HistoricTaskInstance completeFirst()
    {
        String id = operations.writeAsCurrentUser(() -> runtime.startProcessInstanceByKey("revoke").getId());
        Task first = active(id);
        operations.writeAsCurrentUser(() -> tasks.complete(first.getId(), "100", Map.of(), false));
        return infra.processEngine().getHistoryService().createHistoricTaskInstanceQuery().taskId(first.getId()).singleResult();
    }

    private Task active(String process)
    {
        return tasks.createTaskQuery().processInstanceId(process).singleResult();
    }
}
