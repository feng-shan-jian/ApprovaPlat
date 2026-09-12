package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowCollaborationMessageRequest;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;
import com.ruoyi.flowable.testsupport.*;
import org.junit.jupiter.api.*;

/** 真实 Flowable / MyBatis / 共享事务证明消息唯一消费、幂等与失败补偿；仅身份目录为夹具。 */
class WorkflowReliableMessageBusinessTest
{
    private WorkflowFlowableEngineTestSupport support;
    private WorkflowCollaborationMessageService service;
    private java.sql.Connection schemaConnection;

    @BeforeEach void open() throws Exception
    {
        support = WorkflowFlowableEngineTestSupport.start("reliable-message", Map.of());
        support.jdbcTemplate().execute("SET MODE MySQL");
        WorkflowH2SchemaMapperSupport.executeSchema(support.dataSource(), "db/h2/wf_collaboration_channel_audit.sql");
        // H2 2.4 的 CHECK IN 优化器保留建表会话；约束验证期间保持该连接有效。
        schemaConnection = support.dataSource().getConnection();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/h2/wf_collaboration_inbound.sql"))
                .populate(schemaConnection);
        var engine = support.processEngine();
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("1", Set.of()));
        var operations = support.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(engine.getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identity));
        var credentials = mock(WorkflowIntegrationCredentialService.class);
        when(credentials.authenticateAndConsume("test-token", "MESSAGE")).thenReturn(
                new WorkflowIntegrationCredentialService.AuthenticatedCredential(1L, "1", Set.of("amount")));
        when(credentials.authenticateAndConsume("different-token", "MESSAGE")).thenReturn(
                new WorkflowIntegrationCredentialService.AuthenticatedCredential(2L, "1", Set.of("amount")));
        service = new WorkflowCollaborationMessageService(operations, credentials, mapper(WfCollaborationMessageMapper.class),
                engine.getRuntimeService(), engine.getRepositoryService(),
                new WorkflowCollaborationChannelService(mapper(WfCollaborationChannelMapper.class)),
                new WorkflowCollaborationAuditService(mapper(WfCollaborationMessageAuditMapper.class)),
                mock(WorkflowCollaborationMetrics.class));
        engine.getRepositoryService().createDeployment().addString("receiver.bpmn20.xml", """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
             <message id="notice" name="notice"/>
             <process id="receiver" isExecutable="true">
              <startEvent id="start"/><sequenceFlow id="f1" sourceRef="start" targetRef="wait"/>
              <intermediateCatchEvent id="wait"><messageEventDefinition messageRef="notice"/></intermediateCatchEvent>
              <sequenceFlow id="f2" sourceRef="wait" targetRef="review"/><userTask id="review"/>
              <sequenceFlow id="f3" sourceRef="review" targetRef="end"/><endEvent id="end"/>
             </process></definitions>
            """).deploy();
    }

    @AfterEach void close() throws Exception
    {
        try { if (schemaConnection != null) schemaConnection.close(); }
        finally { if (support != null) support.close(); }
    }

    @Test void consumesOnlyExactTargetAndReplaysWithoutDuplicateTasks()
    {
        var runtime = support.processEngine().getRuntimeService();
        var target = runtime.startProcessInstanceByKey("receiver", "target");
        var other = runtime.startProcessInstanceByKey("receiver", "other");
        var request = request("target", 9);
        var result = service.publishPointToPoint("test-token", request);
        assertThat(result.status()).isEqualTo("PROCESSED");
        assertThat(result.matchedProcessInstanceId()).isEqualTo(target.getId());
        assertThat(runtime.getVariable(target.getId(), "amount")).isEqualTo(9);
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(target.getId()).count()).isZero();
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(other.getId()).count()).isOne();
        assertThat(service.publishPointToPoint("test-token", request).status()).isEqualTo("PROCESSED");
        assertThat(support.processEngine().getTaskService().createTaskQuery().processInstanceId(target.getId()).count()).isOne();
        assertThat(support.jdbcTemplate().queryForObject("select count(*) from wf_collaboration_message_audit where action='DELIVER'", Integer.class)).isOne();
        assertThatThrownBy(() -> service.publish("test-token", request)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.publishPointToPoint("different-token", request)).isInstanceOf(ServiceException.class);
        var changed = new WorkflowCollaborationMessageRequest(request.messageId(), "notice", "sender", "receiver", "target", null, 1L, Map.of("amount", 10), 1);
        assertThatThrownBy(() -> service.publishPointToPoint("test-token", changed)).isInstanceOf(ServiceException.class);
    }

    @Test void persistsFailureThenCompensatesAfterTargetArrivesWithoutBypassingCollaborationGate()
    {
        var request = request("late", 4);
        var failed = service.publishPointToPoint("test-token", request);
        assertThat(failed.status()).isEqualTo("DEAD_LETTER");
        assertThat(failed.lastErrorCode()).isEqualTo("COLLAB_MESSAGE_INSTANCE_NOT_FOUND");
        var runtime = support.processEngine().getRuntimeService();
        var target = runtime.startProcessInstanceByKey("receiver", "late");
        var compensated = service.retry(request.messageId());
        assertThat(compensated.status()).isEqualTo("PROCESSED");
        assertThat(compensated.compensationCount()).isOne();
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(target.getId()).count()).isZero();
        var protectedTarget = runtime.startProcessInstanceByKey("receiver", "collaboration");
        var rejected = service.publish("test-token", request("collaboration", 4));
        assertThat(rejected.lastErrorCode()).isEqualTo("COLLAB_MESSAGE_FLOW_NOT_DECLARED");
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(protectedTarget.getId()).count()).isOne();
        assertThat(service.publishPointToPoint("test-token", request("collaboration", 5)).status()).isEqualTo("PROCESSED");
        assertThat(runtime.createEventSubscriptionQuery().processInstanceId(protectedTarget.getId()).count()).isZero();
    }

    @Test void rejectsAmbiguitySuspensionAndNonWhitelistedVariablesWithoutConsuming()
    {
        var runtime = support.processEngine().getRuntimeService();
        runtime.startProcessInstanceByKey("receiver", "duplicate");
        runtime.startProcessInstanceByKey("receiver", "duplicate");
        assertThat(service.publishPointToPoint("test-token", request("duplicate", 1)).lastErrorCode())
                .isEqualTo("COLLAB_MESSAGE_INSTANCE_AMBIGUOUS");
        var suspended = runtime.startProcessInstanceByKey("receiver", "suspended");
        runtime.suspendProcessInstanceById(suspended.getId());
        assertThat(service.publishPointToPoint("test-token", request("suspended", 1)).lastErrorCode())
                .isEqualTo("COLLAB_MESSAGE_INSTANCE_SUSPENDED");
        var denied = new WorkflowCollaborationMessageRequest(UUID.randomUUID().toString(), "notice", "sender", "receiver", "duplicate", null, 1L, Map.of("private", "denied"), 1);
        assertThatThrownBy(() -> service.publishPointToPoint("test-token", denied)).isInstanceOf(ServiceException.class);
        assertThat(runtime.createEventSubscriptionQuery().count()).isEqualTo(3);
    }

    private WorkflowCollaborationMessageRequest request(String key, int amount)
    {
        return new WorkflowCollaborationMessageRequest(UUID.randomUUID().toString(), "notice", "sender", "receiver", key, null, 1L, Map.of("amount", amount), 1);
    }
    private <T> T mapper(Class<T> type)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), type.getSimpleName(), type,
                "mapper/flowable/" + type.getSimpleName() + ".xml");
    }
}
