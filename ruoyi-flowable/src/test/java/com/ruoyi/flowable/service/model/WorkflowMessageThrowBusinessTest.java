package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowCollaborationProperties;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.extension.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.WorkflowCollaborationMetrics;
import com.ruoyi.flowable.service.process.*;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 真实 Flowable、生产 MyBatis outbox/通道/审计和同一 Spring 事务；仅隔离外部端点目录。 */
class WorkflowMessageThrowBusinessTest
{
    private WorkflowFlowableEngineTestSupport support;
    private WorkflowBpmnService bpmn;
    private WorkflowExtensionDeploymentService compiler;
    private WorkflowCollaborationOutboxHandler handler;
    private JsonNode frozen;
    private SimpleMeterRegistry metrics;
    private boolean failAfterEnqueue;
    private Consumer<DelegateExecution> afterEnqueue = execution -> {};
    private static final String CONFIG = """
        {"endpointKey":"receiver","path":"/workflow/runtime-event/collaboration/message",
         "messageName":"notice","targetProcessDefinitionKey":"receiver","variableNames":["amount"],"maxAttempts":5}
        """;

    @BeforeEach void open()
    {
        JavaDelegate delegate = execution -> {
            handler.execute(execution, frozen);
            afterEnqueue.accept(execution);
            if (failAfterEnqueue) throw new IllegalStateException("downstream business failure");
        };
        support = WorkflowFlowableEngineTestSupport.start("message-throw", Map.of("workflowExtensionDelegate", delegate));
        support.jdbcTemplate().execute("SET MODE MySQL");
        WorkflowH2SchemaMapperSupport.executeSchema(support.dataSource(), "db/h2/wf_collaboration_outbox_capability.sql");
        WorkflowH2SchemaMapperSupport.executeSchema(support.dataSource(), "db/h2/wf_collaboration_channel_audit.sql");
        metrics = new SimpleMeterRegistry();
        var engine = support.processEngine();
        var outbox = new WorkflowCollaborationOutboxService(mapper(WfCollaborationOutboxMapper.class),
                new WorkflowCollaborationChannelService(mapper(WfCollaborationChannelMapper.class)),
                new WorkflowCollaborationAuditService(mapper(WfCollaborationMessageAuditMapper.class)),
                null, engine.getRepositoryService(), engine.getHistoryService(), null,
                new WorkflowCollaborationProperties(), new WorkflowCollaborationMetrics(metrics));
        var http = mock(WorkflowHttpConnector.class);
        when(http.freezePostEndpoint("receiver", "/workflow/runtime-event/collaboration/message"))
                .thenReturn("{\"endpointSnapshot\":{\"endpointId\":1,\"revisionNo\":1}}");
        handler = new WorkflowCollaborationOutboxHandler(http, outbox);
        var registry = mock(WorkflowExtensionRegistryService.class);
        when(registry.lockLatestForDeployment(WorkflowCollaborationOutboxHandler.EXTENSION_KEY))
                .thenReturn(new WorkflowExtensionOptionView(1L, WorkflowCollaborationOutboxHandler.EXTENSION_KEY,
                        "可靠消息", "JAVA", 1L, 1, WorkflowCollaborationOutboxHandler.IMPLEMENTATION_KEY, "{}", "0".repeat(64)));
        compiler = new WorkflowExtensionDeploymentService(registry,
                new WorkflowJavaExtensionHandlerRegistry(List.of(handler)), http, null);
        bpmn = new WorkflowBpmnService(engine.getRepositoryService(), new WorkflowFormFieldExtensionService(null), null);
    }

    @AfterEach void close() { if (support != null) support.close(); if (metrics != null) metrics.close(); }

    @Test void preservesAuthorEventAndEnqueuesOnceUsingOriginalNodeInRealTransaction()
    {
        var prepared = prepare(CONFIG);
        String xml = new String(prepared.compiledBpmn(), StandardCharsets.UTF_8);
        assertThat(xml).contains("<serviceTask id=\"originalThrow\"").doesNotContain(WorkflowMessageThrowContract.CONFIG_PROPERTY);
        var snapshot = prepared.snapshots().get(0);
        assertThat(snapshot.getElementId()).isEqualTo("originalThrow");
        assertThat(snapshot.getExtensionKey()).isEqualTo(WorkflowCollaborationOutboxHandler.EXTENSION_KEY);
        assertThat(frozen.path("messageName").asText()).isEqualTo("notice");
        var engine = support.processEngine();
        engine.getRepositoryService().createDeployment().addBytes("throw.bpmn20.xml", prepared.compiledBpmn()).deploy();
        var instance = engine.getRuntimeService().startProcessInstanceByKey("sender", "business-1", Map.of("amount", 17, "privateValue", "excluded"));
        var row = support.jdbcTemplate().queryForMap("select * from wf_collaboration_outbox");
        assertThat(row.get("source_element_id")).isEqualTo("originalThrow");
        assertThat(row.get("source_process_instance_id")).isEqualTo(instance.getId());
        assertThat(row.get("variables_json")).isEqualTo("{\"amount\":17}");
        assertThat(row.get("correlation_key")).isEqualTo("business-1");
        assertThat(support.jdbcTemplate().queryForObject("select count(*) from wf_collaboration_message_audit", Integer.class)).isOne();
        assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(instance.getId())
                .activityId("originalThrow").finished().count()).isOne();
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance.getId()).finished().count()).isOne();
    }

    @Test void senderFailureRollsBackOutboxSequenceAuditAndFlowableInstance()
    {
        var prepared = prepare(CONFIG);
        var engine = support.processEngine();
        engine.getRepositoryService().createDeployment().addBytes("rollback.bpmn20.xml", prepared.compiledBpmn()).deploy();
        failAfterEnqueue = true;
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("sender", "rollback-1", Map.of("amount", 1)))
                .isInstanceOf(RuntimeException.class);
        for (String table : List.of("wf_collaboration_outbox", "wf_collaboration_channel", "wf_collaboration_message_audit"))
            assertThat(support.jdbcTemplate().queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().count()).isZero();
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    }

    @Test void repeatedInvocationWithinOneActivityIsIdempotent()
    {
        var prepared = prepare(CONFIG);
        var engine = support.processEngine();
        engine.getRepositoryService().createDeployment().addBytes("replay.bpmn20.xml", prepared.compiledBpmn()).deploy();
        afterEnqueue = execution -> handler.execute(execution, frozen);
        engine.getRuntimeService().startProcessInstanceByKey("sender", "replay", Map.of("amount", 17));
        assertOutboxProgress(1);
    }

    @Test void changedPayloadWithinOneActivityConflictsAndRollsBack()
    {
        var prepared = prepare(CONFIG);
        var engine = support.processEngine();
        engine.getRepositoryService().createDeployment().addBytes("conflict.bpmn20.xml", prepared.compiledBpmn()).deploy();
        afterEnqueue = execution -> {
            execution.setVariable("amount", 18);
            handler.execute(execution, frozen);
        };
        assertThatThrownBy(() -> engine.getRuntimeService().startProcessInstanceByKey("sender", "conflict", Map.of("amount", 17)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getSubCode()).isEqualTo("COLLAB_OUTBOX_IDEMPOTENCY_CONFLICT"));
        for (String table : List.of("wf_collaboration_outbox", "wf_collaboration_channel", "wf_collaboration_message_audit"))
            assertThat(support.jdbcTemplate().queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        assertThat(engine.getRuntimeService().createProcessInstanceQuery().count()).isZero();
    }

    @Test void revisitingSameNodeEnqueuesEachOccurrenceWithSameOrChangedPayload()
    {
        deployLoop();
        var engine = support.processEngine();
        var runtime = engine.getRuntimeService();
        var instance = runtime.startProcessInstanceByKey("sender", "loop", Map.of("amount", 17));
        String executionId = runtime.createExecutionQuery().processInstanceId(instance.getId()).activityId("review").singleResult().getId();
        runtime.trigger(executionId);
        assertOutboxProgress(2);
        runtime.trigger(executionId, Map.of("amount", 18));

        assertOutboxProgress(3);
        var rows = support.jdbcTemplate().queryForList("select * from wf_collaboration_outbox order by sequence_no");
        assertThat(rows).extracting(row -> row.get("message_id")).doesNotHaveDuplicates();
        assertThat(rows).extracting(row -> row.get("source_execution_id")).containsOnly(executionId);
        assertThat(rows).extracting(row -> row.get("variables_json"))
                .containsExactly("{\"amount\":17}", "{\"amount\":17}", "{\"amount\":18}");
        assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(instance.getId())
                .activityId("originalThrow").finished().list()).hasSize(3)
                .extracting(activity -> activity.getId()).doesNotHaveDuplicates();
    }

    @Test void failedReentryRollsBackThenRetryAllocatesNextSequenceOnce()
    {
        deployLoop();
        var engine = support.processEngine();
        var runtime = engine.getRuntimeService();
        var instance = runtime.startProcessInstanceByKey("sender", "loop-rollback", Map.of("amount", 17));
        String executionId = runtime.createExecutionQuery().processInstanceId(instance.getId()).activityId("review").singleResult().getId();
        var original = support.jdbcTemplate().queryForMap("select * from wf_collaboration_outbox");
        failAfterEnqueue = true;
        assertThatThrownBy(() -> runtime.trigger(executionId, Map.of("amount", 18)))
                .isInstanceOf(IllegalStateException.class).hasMessage("downstream business failure");
        assertOutboxProgress(1);
        assertThat(runtime.getVariable(instance.getId(), "amount")).isEqualTo(17);
        assertThat(runtime.createExecutionQuery().executionId(executionId).singleResult().getActivityId()).isEqualTo("review");
        assertThat(engine.getHistoryService().createHistoricActivityInstanceQuery().processInstanceId(instance.getId())
                .activityId("originalThrow").finished().count()).isOne();

        failAfterEnqueue = false;
        runtime.trigger(executionId, Map.of("amount", 18));
        assertOutboxProgress(2);
        assertThat(support.jdbcTemplate().queryForMap("select * from wf_collaboration_outbox where sequence_no = 1"))
                .isEqualTo(original);
        assertThat(support.jdbcTemplate().queryForObject("select variables_json from wf_collaboration_outbox where sequence_no = 2", String.class))
                .isEqualTo("{\"amount\":18}");
    }

    private void assertOutboxProgress(int occurrences)
    {
        for (String table : List.of("wf_collaboration_outbox", "wf_collaboration_message_audit"))
            assertThat(support.jdbcTemplate().queryForObject("select count(*) from " + table, Integer.class)).as(table).isEqualTo(occurrences);
        assertThat(support.jdbcTemplate().queryForObject("select outbound_sequence from wf_collaboration_channel", Integer.class))
                .isEqualTo(occurrences);
        assertThat(support.jdbcTemplate().queryForList("select sequence_no from wf_collaboration_outbox order by sequence_no", Long.class))
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, occurrences).boxed().toList());
    }

    private void deployLoop()
    {
        String xml = model(CONFIG).replace("<endEvent id=\"end\"/>", "<receiveTask id=\"review\"/>")
                .replace("targetRef=\"end\"", "targetRef=\"review\"")
                .replace("</process>", "<sequenceFlow id=\"back\" sourceRef=\"review\" targetRef=\"originalThrow\"/></process>");
        var prepared = prepare(CONFIG, xml);
        support.processEngine().getRepositoryService().createDeployment().addBytes("loop.bpmn20.xml", prepared.compiledBpmn()).deploy();
    }

    @Test void refusesMessageDriftAndInvalidConfigWithOriginalNodeLocation()
    {
        for (String invalid : List.of(CONFIG.replace("\"notice\"", "\"other\""), CONFIG.replace("\"maxAttempts\":5", "\"maxAttempts\":99"),
                CONFIG.replace("\"variableNames\":[\"amount\"]", "\"variableNames\":[\"amount\",\"amount\"]")))
            assertThatThrownBy(() -> prepare(invalid)).isInstanceOfSatisfying(ServiceException.class, exception -> {
                assertThat(exception.getSubCode()).isEqualTo("BPMN_MESSAGE_THROW_CONFIG_INVALID");
                assertThat(((WorkflowBpmnValidationException) exception.getCause()).issues().get(0).elementId()).isEqualTo("originalThrow");
                assertThat(((WorkflowBpmnValidationException) exception.getCause()).issues().get(0).processId()).isEqualTo("sender");
            });
    }

    private CompiledMessage prepare(String config)
    {
        return prepare(config, model(config));
    }

    private CompiledMessage prepare(String config, String xml)
    {
        var document = bpmn.validateForSave(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(bpmn.authorValidationIssues(document)).isEmpty();
        var executable = bpmn.executionModel(document);
        var snapshots = compiler.prepare(document, executable, "1");
        assertThat(document.bpmnXml()).isEqualTo(xml);
        assertThat(document.bpmnModel().getFlowElement("originalThrow")).isInstanceOf(org.flowable.bpmn.model.ThrowEvent.class);
        assertThat(WorkflowMessageThrowContract.readConfig(document.bpmnModel().getFlowElement("originalThrow"))).isEqualTo(config);
        frozen = JsonMapper.shared().readTree(snapshots.get(0).getConfigJson());
        new WorkflowParticipantRuleDeploymentService(null).prepare(document, executable, null, "1");
        byte[] compiled = WorkflowBpmnXmlPreserver.write(executable, document.bpmnXml());
        assertThat(bpmn.executionValidationIssues(compiled)).isEmpty();
        return new CompiledMessage(compiled, snapshots);
    }

    private record CompiledMessage(byte[] compiledBpmn,
            List<com.ruoyi.flowable.domain.WfDeployExtensionSnapshot> snapshots) { }

    private <T> T mapper(Class<T> type)
    {
        return WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), type.getSimpleName(), type,
                "mapper/flowable/" + type.getSimpleName() + ".xml");
    }

    private String model(String config)
    {
        return """
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="urn:test">
              <message id="message" name="notice"/>
              <process id="sender" isExecutable="true"><extensionElements><flowable:properties>
                <flowable:property name="approva.startScope.ruleVersion" value="1"/>
                <flowable:property name="approva.startScope.type" value="PUBLIC"/>
                <flowable:property name="approva.startScope.targetIds" value=""/>
                <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
              </flowable:properties></extensionElements>
              <startEvent id="start"><extensionElements><flowable:formProperty id="amount" name="金额" type="long" readable="true" writable="true"/></extensionElements></startEvent>
              <intermediateThrowEvent id="originalThrow"><extensionElements><flowable:properties>
                <flowable:property name="approva.messageThrow.config" value="%s"/>
              </flowable:properties></extensionElements><messageEventDefinition messageRef="message"/></intermediateThrowEvent>
              <endEvent id="end"/>
              <sequenceFlow id="f1" sourceRef="start" targetRef="originalThrow"/><sequenceFlow id="f2" sourceRef="originalThrow" targetRef="end"/>
              </process></definitions>
            """.formatted(config.replace("&", "&amp;").replace("\"", "&quot;").replace("\n", "&#10;"));
    }
}
