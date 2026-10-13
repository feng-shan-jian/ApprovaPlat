package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.domain.WfConnectorEndpoint;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.integration.WorkflowConnectorEndpointService;
import com.ruoyi.flowable.service.integration.WorkflowConnectorSecretResolver;
import com.ruoyi.flowable.service.integration.WorkflowHttpExecution;
import com.ruoyi.flowable.service.integration.WorkflowSqlDataSourceService;
import com.ruoyi.flowable.service.integration.WorkflowSqlSecretResolver;
import com.ruoyi.flowable.service.model.WorkflowBpmnEventCodeService;
import com.ruoyi.flowable.service.model.WorkflowEmbeddedFormConverter;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;

/** 扩展规范化与跨 HTTP/SQL 受保护输出合同；仅使用本地 HTTP/H2，不启动流程引擎。 */
class WorkflowExtensionExecutionContractTest
{
    @Test
    void handlerNormalizationKeepsCanonicalOutputAndDirectExecutionValidation()
    {
        var json = JsonMapper.shared();
        var set = new WorkflowSetVariableJavaHandler();
        var execution = mock(org.flowable.engine.delegate.DelegateExecution.class);
        var task = mock(org.flowable.task.service.delegate.DelegateTask.class);
        var config = json.createObjectNode().put("value", 7).put("targetVariable", " result ");
        assertThat(set.validateAndNormalizeConfig(config))
                .isEqualTo("{\"targetVariable\":\"result\",\"value\":7}");
        set.execute(execution, config);
        set.executeTask(task, config);
        verify(execution).setVariable("result", 7L);
        verify(task).setVariable("result", 7L);
        assertThat(config.get("targetVariable").textValue()).isEqualTo(" result ");
        var floatConfig = json.createObjectNode().put("targetVariable", "floatResult").put("value", 0.1f);
        set.execute(execution, floatConfig);
        set.executeTask(task, floatConfig);
        verify(execution).setVariable("floatResult", 0.1d);
        verify(task).setVariable("floatResult", 0.1d);
        var decimalConfig = json.createObjectNode().put("targetVariable", "decimalResult").put("value", new BigDecimal("1"));
        set.execute(execution, decimalConfig);
        set.executeTask(task, decimalConfig);
        verify(execution).setVariable("decimalResult", 1L);
        verify(task).setVariable("decimalResult", 1L);
        var exponentConfig = json.createObjectNode().put("targetVariable", "exponentResult")
                .put("value", new BigDecimal("1E+400"));
        set.execute(execution, exponentConfig);
        set.executeTask(task, exponentConfig);
        verify(execution).setVariable("exponentResult", Double.POSITIVE_INFINITY);
        verify(task).setVariable("exponentResult", Double.POSITIVE_INFINITY);
        for (String overflow : List.of("9223372036854775808", "-9223372036854775809"))
        {
            for (var overflowConfig : List.of(
                    json.createObjectNode().put("targetVariable", "result").put("value", new BigDecimal(overflow)),
                    json.createObjectNode().put("targetVariable", "result").put("value", new java.math.BigInteger(overflow))))
            {
                assertResourceFailure(() -> set.execute(execution, overflowConfig), "扩展快照配置解析失败", 500);
                assertResourceFailure(() -> set.executeTask(task, overflowConfig), "扩展快照配置解析失败", 500);
            }
        }
        for (var invalid : List.of(json.createArrayNode(),
                json.createObjectNode().put("targetVariable", "result").set("value", json.createObjectNode()),
                json.createObjectNode().put("targetVariable", "initiator").put("value", true)))
        {
            assertThatThrownBy(() -> set.execute(execution, invalid))
                    .isInstanceOf(com.ruoyi.common.exception.ServiceException.class);
            assertThatThrownBy(() -> set.executeTask(task, invalid))
                    .isInstanceOf(com.ruoyi.common.exception.ServiceException.class);
        }

        var codes = mock(WorkflowBpmnEventCodeService.class);
        var runtime = mock(com.ruoyi.flowable.service.process.WorkflowBpmnEventRuntimeService.class);
        var event = new WorkflowRaiseBpmnEventHandler(codes, runtime);
        for (String type : List.of("ERROR", "ESCALATION"))
        {
            var code = new WfBpmnEventCode(); code.setEventName("已冻结事件"); code.setNotificationPolicy("NONE");
            when(codes.requireEnabled(type, "TEST_EVENT")).thenReturn(code);
            var author = json.createObjectNode().put("sourceType", "SERVICE_TASK")
                    .put("eventCode", "TEST_EVENT").put("eventType", type);
            String frozen = event.validateAndNormalizeConfig(author);
            assertThat(frozen).isEqualTo("{\"eventType\":\"" + type
                    + "\",\"eventCode\":\"TEST_EVENT\",\"eventName\":\"已冻结事件\","
                    + "\"notificationPolicy\":\"NONE\",\"sourceType\":\"SERVICE_TASK\",\"operator\":\"ALWAYS\"}");
            clearInvocations(codes, runtime);
            event.execute(execution, json.readTree(frozen));
            verifyNoInteractions(codes);
            verify(runtime).raise(eq(execution), argThat(value -> type.equals(value.eventType())
                    && "TEST_EVENT".equals(value.eventCode())));
            for (String required : List.of("eventType", "eventCode", "sourceType"))
            {
                var missing = author.deepCopy(); missing.remove(required);
                assertThatThrownBy(() -> event.execute(execution, missing))
                        .isInstanceOf(com.ruoyi.common.exception.ServiceException.class);
            }
            assertThatThrownBy(() -> event.execute(execution, author.deepCopy().put("eventCode", 1)))
                    .isInstanceOf(com.ruoyi.common.exception.ServiceException.class);
            assertThatThrownBy(() -> event.execute(execution, json.createArrayNode()))
                    .isInstanceOf(com.ruoyi.common.exception.ServiceException.class);
        }
    }

    @Test
    void rejectsProtectedOutputsBeforeHttpAndSqlSideEffectsAndPreservesInputReads() throws Exception
    {
        var json = JsonMapper.shared();
        var java = new WorkflowSetVariableJavaHandler();
        var cel = new WorkflowCelSandbox();
        var execution = mock(org.flowable.engine.delegate.DelegateExecution.class);
        var task = mock(org.flowable.task.service.delegate.DelegateTask.class);
        var values = new java.util.LinkedHashMap<String, Object>();
        when(execution.getVariable(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        when(execution.hasVariable(anyString())).thenAnswer(call -> values.containsKey(call.getArgument(0)));
        doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(execution).setVariable(anyString(), any());
        when(execution.getProcessInstanceId()).thenReturn("instance");
        when(execution.getId()).thenReturn("execution");
        when(execution.getCurrentActivityId()).thenReturn("connector");
        values.put("initiator", "100");

        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/effect", exchange -> {
            requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        server.start();
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:output-contract-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        try
        {
            jdbc.execute("create table effects (owner varchar(40))");
            var endpointMapper = mock(com.ruoyi.flowable.mapper.WfConnectorEndpointMapper.class);
            var endpointService = new WorkflowConnectorEndpointService(null, endpointMapper,
                    mock(WorkflowConnectorSecretResolver.class));
            var endpoint = new WfConnectorEndpoint();
            endpoint.setEndpointId(1L); endpoint.setEndpointKey("local"); endpoint.setEndpointName("local");
            endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            endpoint.setAllowedMethods("POST"); endpoint.setPathPrefix("/effect"); endpoint.setAuthType("NONE");
            endpoint.setNetworkScope("PRIVATE"); endpoint.setConnectTimeoutMs(1000); endpoint.setRequestTimeoutMs(1000);
            endpoint.setRevisionNo(1); endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
            when(endpointMapper.selectEnabledByKeyForUpdate("local")).thenReturn(endpoint);
            var metrics = mock(com.ruoyi.flowable.runtime.WorkflowConnectorMetrics.class);
            var http = new WorkflowHttpConnector(new WorkflowHttpExecution(mock(WorkflowConnectorSecretResolver.class)), metrics);
            var httpAuthor = (tools.jackson.databind.node.ObjectNode) json.readTree("""
                    {"endpointKey":"local","method":"POST","path":"/effect","bodyVariable":"initiator","statusVariable":"httpResult"}
                    """);
            var httpFrozen = (tools.jackson.databind.node.ObjectNode) json.readTree(endpointService.freezeConfig(httpAuthor, true));
            var sourceMapper = mock(com.ruoyi.flowable.mapper.WfSqlDataSourceMapper.class);
            var dataSources = new WorkflowSqlDataSourceService(null, sourceMapper, new WorkflowSqlTemplateValidator());
            var source = new WfSqlDataSource();
            source.setDataSourceId(1L); source.setDataSourceKey("local"); source.setDataSourceName("local");
            source.setConnectionType("PRIMARY"); source.setAllowedTables("effects");
            source.setConnectTimeoutMs(1000); source.setQueryTimeoutSeconds(2); source.setRevisionNo(1);
            source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
            when(sourceMapper.selectEnabledByKeyForUpdate("local")).thenReturn(source);
            var sql = new WorkflowSqlConnector(dataSource, new WorkflowSqlTemplateValidator(),
                    mock(WorkflowSqlSecretResolver.class), metrics);
            var sqlAuthor = (tools.jackson.databind.node.ObjectNode) json.readTree("""
                    {"dataSourceKey":"local","sql":"insert into effects (owner) values (:owner)","parameters":{"owner":"initiator"},"resultVariable":"sqlResult"}
                    """);
            var sqlFrozen = (tools.jackson.databind.node.ObjectNode) json.readTree(dataSources.freezeConfig(sqlAuthor, true));
            var validator = new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator());
            for (String name : List.of("nrOfInstances", "initiator", "wfMiUsers_review", "_wfMiMembers_review",
                    "_wfMiRevision_review", "_wfMiMode_review", "_wfMiCompletionRevision", "__approva_loop_route_review",
                    "__approva_loop_iteration_review", "__ruoyi_workflow_form_submission_v2", "__proto__", "Constructor"))
            {
                var javaConfig = json.createObjectNode().put("targetVariable", name).put("value", 999);
                var celConfig = json.createObjectNode().put("expression", "1").put("resultVariable", name).put("resultType", "INT");
                celConfig.putArray("variables");
                assertThatThrownBy(() -> java.validateAndNormalizeConfig(javaConfig)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> java.execute(execution, javaConfig)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> java.executeTask(task, javaConfig)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> cel.validateAndNormalizeConfig(celConfig)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> cel.execute(execution, celConfig)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> endpointService.freezeConfig(httpAuthor.deepCopy().put("statusVariable", name), true)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> http.execute(execution, httpFrozen.deepCopy().put("statusVariable", name))).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> dataSources.freezeConfig(sqlAuthor.deepCopy().put("resultVariable", name), true)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> sql.execute(execution, sqlFrozen.deepCopy().put("resultVariable", name))).as(name).hasMessageContaining("保留变量");
                var property = new org.flowable.bpmn.model.FormProperty(); property.setId(name); property.setType("string");
                assertThatThrownBy(() -> WorkflowEmbeddedFormConverter.convert(List.of(property))).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> validator.validateForStart(FORM.replace("amount", name), Map.of())).as(name).isInstanceOf(com.ruoyi.common.exception.ServiceException.class);
            }
            assertThat(requests).hasValue(0);
            assertThat(jdbc.queryForObject("select count(*) from effects", Integer.class)).isZero();
            assertThat(values).containsExactly(Map.entry("initiator", "100"));
            verify(task, never()).setVariable(anyString(), any());
            http.execute(execution, httpFrozen);
            sql.execute(execution, sqlFrozen);
            assertThat(requests).hasValue(1);
            assertThat(values).containsEntry("httpResult", 204L).containsEntry("sqlResult", 1).containsEntry("initiator", "100");
            assertThat(jdbc.queryForObject("select owner from effects", String.class)).isEqualTo("100");
            for (String name : List.of("wfSignalName", "wfEscalationCode", "wfErrorCode", "Initiator", "ordinaryResult"))
            {
                java.execute(execution, json.createObjectNode().put("targetVariable", name).put("value", true));
                assertThat(values).containsEntry(name, true);
            }
        }
        finally
        {
            server.stop(0); jdbc.execute("SHUTDOWN");
        }
    }

    private static void assertResourceFailure(Runnable action, String message, int code)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(com.ruoyi.common.exception.ServiceException.class,
                failure -> { assertThat(failure.getMessage()).isEqualTo(message); assertThat(failure.getCode()).isEqualTo(code); });
    }

    private static final String FORM = """
            {"fields":[
              {"layout":"colFormItem","tag":"el-input-number","vModel":"amount","workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
              {"layout":"colFormItem","tag":"el-input-number","vModel":"result","workflowHidden":false,"workflowReadable":true,"workflowWritable":false,"workflowSystemWritable":true},
              {"layout":"colFormItem","tag":"el-input","vModel":"note","workflowHidden":false,"workflowReadable":true,"workflowWritable":true}]}
            """;
}
