package com.ruoyi.flowable.service.process;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.domain.dto.*;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.task.*;
import com.ruoyi.flowable.testsupport.*;
import com.ruoyi.system.service.SysPermissionEvaluator;

/** 最小 CallActivity 根/子流程、正式业务 Mapper 与同库事务，不装配设计目录或外部发送器。 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowProcessInstanceMysqlTest
{
    private WorkflowFlowableEngineTestSupport infra;
    private WorkflowProcessInstanceService service;
    private WorkflowEngineOperations operations;
    private RuntimeService runtime;
    private HistoryService history;
    private SysPermissionEvaluator permissions;
    private WorkflowTaskSlaRuntimeService sla;
    private WfAttachmentMapper attachments;
    private String deployment;
    private String root;
    private String child;
    private String attachment;

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        infra = WorkflowFlowableEngineTestSupport.start(new DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD")), Map.of());
        var engine = infra.processEngine();
        runtime = engine.getRuntimeService();
        history = spy(engine.getHistoryService());
        var identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        operations = infra.transactionalProxy(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                engine.getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
        attachments = mapper(WfAttachmentMapper.class);
        var copies = mapper(WfCopyMapper.class);
        var loops = mapper(WfControlledLoopExecutionMapper.class);
        var rounds = mapper(WfMultiInstanceRoundMapper.class);
        var termination = new WorkflowMultiInstanceRoundTerminationService(new WorkflowMultiInstanceRoundRepository(rounds),
                new WorkflowMultiInstanceRuntimeSnapshotReader(engine.getRepositoryService(), runtime, engine.getTaskService()),
                new WorkflowMultiInstanceTransitionCoordinator(), runtime, engine.getTaskService());
        permissions = mock(SysPermissionEvaluator.class);
        when(permissions.hasPermi(anyString())).thenReturn(true);
        // This slice has no SLA policy or notification policy. Their own state machines are covered separately.
        sla = mock(WorkflowTaskSlaRuntimeService.class);
        service = new WorkflowProcessInstanceService(operations, history, runtime, engine.getTaskService(), attachments,
                copies, loops, rounds, termination, permissions, sla, mock(WorkflowNotificationService.class));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String rootKey = "instanceRoot" + suffix;
        String childKey = "instanceChild" + suffix;
        String bpmn = """
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="instance-contract">
                  <process id="%s" isExecutable="true"><startEvent id="s"/><callActivity id="call" calledElement="%s"/><endEvent id="e"/>
                    <sequenceFlow id="a" sourceRef="s" targetRef="call"/><sequenceFlow id="b" sourceRef="call" targetRef="e"/></process>
                  <process id="%s" isExecutable="true"><startEvent id="cs"/><userTask id="hold" flowable:assignee="100"/><endEvent id="ce"/>
                    <sequenceFlow id="ca" sourceRef="cs" targetRef="hold"/><sequenceFlow id="cb" sourceRef="hold" targetRef="ce"/></process>
                </definitions>
                """.formatted(rootKey, childKey, childKey);
        deployment = engine.getRepositoryService().createDeployment().addString("instances.bpmn20.xml", bpmn).deploy().getId();
        root = operations.writeAsCurrentUser(actor -> runtime.createProcessInstanceBuilder().processDefinitionKey(rootKey).businessStatus("running").start().getId());
        child = runtime.createProcessInstanceQuery().superProcessInstanceId(root).singleResult().getId();
    }

    @AfterEach
    void clean()
    {
        if (infra == null) return;
        try
        {
            if (attachment != null) infra.jdbcTemplate().update("delete from wf_attachment where attachment_id=?", attachment);
            for (String id : new String[]{root, child})
            {
                if (id == null) continue;
                infra.jdbcTemplate().update("delete from wf_copy where instance_id=?", id);
                infra.jdbcTemplate().update("delete from wf_controlled_loop_execution where process_instance_id=?", id);
            }
            if (deployment != null) infra.processEngine().getRepositoryService().deleteDeployment(deployment, true);
        }
        finally { infra.close(); }
    }

    @Test
    void childStateRequestChangesWholeTreeAndRepeatedStateIsIdempotent()
    {
        assertThat(service.updateState(new WorkflowInstanceStateRequest(child, WorkflowInstanceState.SUSPENDED)).changed()).isTrue();
        assertTreeSuspended(true);
        assertThat(service.updateState(new WorkflowInstanceStateRequest(root, WorkflowInstanceState.SUSPENDED)).changed()).isFalse();
        assertThat(service.updateState(new WorkflowInstanceStateRequest(child, WorkflowInstanceState.ACTIVE)).changed()).isTrue();
        assertTreeSuspended(false);
        error(() -> service.deleteCompletedHistory(List.of(root)), 409);
        error(() -> service.updateState(new WorkflowInstanceStateRequest("missing", WorkflowInstanceState.ACTIVE)), 404);
        when(permissions.hasPermi("workflow:process:state")).thenReturn(false);
        error(() -> service.updateState(new WorkflowInstanceStateRequest(root, WorkflowInstanceState.SUSPENDED)), 403);
        assertTreeSuspended(false);
    }

    @Test
    void pauseFailureRollsBackBothEngineStateAndRelatedBusinessWrite()
    {
        addCopy(child);
        doAnswer(call -> {
            infra.jdbcTemplate().update("update wf_copy set del_flag='2' where instance_id=?", child);
            throw new IllegalStateException("pause rollback");
        }).when(sla).pauseInstance(eq(child), anyString());
        assertThatThrownBy(() -> service.updateState(new WorkflowInstanceStateRequest(child, WorkflowInstanceState.SUSPENDED)))
                .isInstanceOf(IllegalStateException.class).hasMessage("pause rollback");
        assertTreeSuspended(false);
        assertThat(copyState()).isEqualTo("0");
    }

    @Test
    void terminatingSuspendedChildEndsRootAndKeepsStructuredAudit()
    {
        service.updateState(new WorkflowInstanceStateRequest(root, WorkflowInstanceState.SUSPENDED));
        var result = service.terminate(new WorkflowInstanceTerminateRequest(child, "contract termination"));
        assertThat(result.instanceId()).isEqualTo(root);
        assertThat(result.processStatus()).isEqualTo("terminated");
        assertThat(result.wasSuspended()).isTrue();
        for (String id : List.of(root, child))
        {
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(id).count()).isZero();
            var past = history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult();
            assertThat(past.getEndTime()).isNotNull();
            assertThat(past.getBusinessStatus()).isEqualTo("terminated");
        }
        var comments = infra.processEngine().getTaskService().getProcessInstanceComments(root);
        assertThat(comments).singleElement().satisfies(comment -> {
            assertThat(comment.getType()).isEqualTo("TERMINATE");
            assertThat(comment.getUserId()).isEqualTo("100");
            assertThat(comment.getFullMessage()).contains(child, root, "contract termination");
        });
        error(() -> service.terminate(new WorkflowInstanceTerminateRequest(child, "again")), 409);
        error(() -> service.updateState(new WorkflowInstanceStateRequest(root, WorkflowInstanceState.ACTIVE)), 409);
    }

    @Test
    void cancellationUsesRootStarterAndBoundEvidencePreventsHistoryDeletion()
    {
        when(permissions.hasPermi("workflow:process:terminate")).thenReturn(false);
        var result = service.terminate(new WorkflowInstanceTerminateRequest(child, "starter cancels"));
        assertThat(result.processStatus()).isEqualTo("canceled");
        bindEvidence(child);
        error(() -> service.deleteCompletedHistory(List.of(root)), 409);
        assertThat(history.createHistoricProcessInstanceQuery().processInstanceIds(Set.of(root, child)).count()).isEqualTo(2);
        infra.jdbcTemplate().update("delete from wf_attachment where attachment_id=?", attachment);
        addCopy(child);
        var removed = service.deleteCompletedHistory(List.of(root, child, root));
        assertThat(removed.requestedCount()).isEqualTo(2);
        assertThat(removed.deletedHistoryCount()).isEqualTo(2);
        assertThat(removed.deletedCopyCount()).isOne();
        assertThat(copyState()).isEqualTo("2");
        error(() -> service.deleteCompletedHistory(List.of(root)), 404);
    }

    @Test
    void failureAfterEngineHistoryDeletionRestoresHistoryAndEarlierCopyAndLoopWrites()
    {
        service.terminate(new WorkflowInstanceTerminateRequest(root, "prepare history"));
        addCopy(child);
        infra.jdbcTemplate().update("insert into wf_controlled_loop_execution(deploy_id,process_definition_id,process_instance_id,activity_id,task_id,iteration_no,actor_user_id,decision_value,outcome) values(?,'definition',?,'hold',?,1,'100','yes','EXIT')", deployment, child, UUID.randomUUID().toString());
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("history rollback"); })
                .when(history).deleteHistoricProcessInstance(root);
        assertThatThrownBy(() -> service.deleteCompletedHistory(List.of(root)))
                .isInstanceOf(IllegalStateException.class).hasMessage("history rollback");
        assertThat(history.createHistoricProcessInstanceQuery().processInstanceIds(Set.of(root, child)).count()).isEqualTo(2);
        assertThat(copyState()).isEqualTo("0");
        assertThat(infra.jdbcTemplate().queryForObject("select count(*) from wf_controlled_loop_execution where process_instance_id=?", Long.class, child)).isOne();
    }

    private <T> T mapper(Class<T> type)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(infra.dataSource(), "instance-" + type.getSimpleName(), type,
                "mapper/flowable/" + type.getSimpleName() + ".xml");
    }

    private void addCopy(String id)
    {
        infra.jdbcTemplate().update("insert into wf_copy(copy_event_id,process_id,deployment_id,instance_id,user_id,originator_id) values(?,'definition',?,?,100,100)", UUID.randomUUID().toString(), deployment, id);
    }

    private String copyState()
    {
        return infra.jdbcTemplate().queryForObject("select del_flag from wf_copy where instance_id=?", String.class, child);
    }

    private void bindEvidence(String id)
    {
        attachment = UUID.randomUUID().toString();
        var now = LocalDateTime.now();
        assertThat(attachments.insert(new WfAttachment(attachment, 100L, "evidence", "evidence.txt",
                "2026/10/04/" + UUID.randomUUID().toString().replace("-", "") + ".txt", "text/plain", 1, "a".repeat(64),
                WorkflowAttachmentStatus.BOUND, now.plusDays(1), null, id, null, "start", now, null, 0, null, null, null, null, now, now))).isOne();
    }

    private void assertTreeSuspended(boolean suspended)
    {
        assertThat(runtime.createProcessInstanceQuery().processInstanceIds(Set.of(root, child)).list()).hasSize(2)
                .allSatisfy(instance -> assertThat(instance.isSuspended()).isEqualTo(suspended));
    }

    private void error(Runnable operation, int code)
    {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
