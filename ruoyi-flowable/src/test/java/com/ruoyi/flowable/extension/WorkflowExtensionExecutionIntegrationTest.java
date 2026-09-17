package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.junit.jupiter.api.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowCollaborationProperties;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.*;
import com.ruoyi.flowable.service.model.*;
import com.ruoyi.flowable.service.process.*;
import com.ruoyi.flowable.testsupport.*;
import com.sun.net.httpserver.HttpServer;
import dev.cel.compiler.CelCompilerFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 真实 Flowable 命令、部署资源、数据库事务与本机 HTTP；目录和秘密来源使用隔离替身。 */
class WorkflowExtensionExecutionIntegrationTest
{
    protected WorkflowFlowableEngineTestSupport support;
    private WorkflowExtensionDelegate delegate;
    private WorkflowHttpConnector http;
    private WorkflowSqlConnector sql;
    private WorkflowSqlTemplateValidator validator;
    private WorkflowConnectorEndpointService endpoints;
    private WorkflowSqlDataSourceService sources;
    private WorkflowConnectorSecretResolver secrets;
    protected WorkflowSqlSecretResolver sqlSecrets;
    private WorkflowCollaborationOutboxHandler handler;
    private WorkflowCollaborationOutboxService outbox;
    private WorkflowDeploymentArtifactRepository artifacts;
    private WorkflowJavaExtensionHandlerRegistry handlers;
    private SimpleMeterRegistry meters;
    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> keys = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int responseStatus = 200;
    private byte[] response = "{\"code\":200}".getBytes(StandardCharsets.UTF_8);
    protected boolean failAfter;
    private boolean replay;
    private boolean changedReplay;
    private int serial;
    private WfConnectorEndpoint endpoint;
    protected WfSqlDataSource source;
    private static final String CEL = "{\"expression\":\"amount + 1\",\"resultVariable\":\"answer\",\"resultType\":\"INT\",\"variables\":[{\"name\":\"amount\",\"type\":\"INT\"}]}";

    protected WorkflowFlowableEngineTestSupport startDatabase(Map<Object, Object> beans) throws Exception
    {
        var result = WorkflowFlowableEngineTestSupport.start("c08-extension", beans);
        result.jdbcTemplate().execute("SET MODE MySQL");
        WorkflowH2SchemaMapperSupport.executeSchema(result.dataSource(), "db/h2/wf_collaboration_outbox_capability.sql");
        WorkflowH2SchemaMapperSupport.executeSchema(result.dataSource(), "db/h2/wf_collaboration_channel_audit.sql");
        return result;
    }

    protected void closeDatabase() { }

    @BeforeEach void open() throws Exception
    {
        JavaDelegate entry = execution -> {
            delegate.execute(execution);
            if (replay) {
                if (changedReplay) execution.setVariable("amount", 99);
                delegate.execute(execution);
            }
            if (failAfter) throw new IllegalStateException("downstream write failed");
        };
        support = startDatabase(Map.of("workflowExtensionDelegate", entry));
        support.jdbcTemplate().execute("create table c08_effect(id varchar(64) primary key, amount int not null)");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Location", "/redirected");
            int status = exchange.getRequestURI().getPath().startsWith("/workflow/")
                    && !"isolated-test-token".equals(exchange.getRequestHeaders().getFirst("X-Integration-Token"))
                    ? 401 : responseStatus;
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        meters = new SimpleMeterRegistry();
        endpoints = mock(WorkflowConnectorEndpointService.class);
        sources = mock(WorkflowSqlDataSourceService.class);
        secrets = spy(new WorkflowConnectorSecretResolver(null));
        sqlSecrets = mock(WorkflowSqlSecretResolver.class);
        doReturn("isolated-test-token").when(secrets).requireSecret("WORKFLOW_CONNECTOR_SECRET_C08");
        validator = spy(new WorkflowSqlTemplateValidator());
        http = spy(new WorkflowHttpConnector(endpoints, secrets, new WorkflowConnectorMetrics(meters)));
        sql = new WorkflowSqlConnector(support.dataSource(), sources, validator, sqlSecrets, new WorkflowConnectorMetrics(meters));
        var engine = support.processEngine();
        artifacts = new WorkflowDeploymentArtifactRepository(engine.getRepositoryService());
        outbox = support.transactionalProxy(new WorkflowCollaborationOutboxService(mapper(WfCollaborationOutboxMapper.class),
                new WorkflowCollaborationChannelService(mapper(WfCollaborationChannelMapper.class)),
                new WorkflowCollaborationAuditService(mapper(WfCollaborationMessageAuditMapper.class)), http,
                engine.getRepositoryService(), engine.getHistoryService(), null, new WorkflowCollaborationProperties(),
                new WorkflowCollaborationMetrics(meters)));
        handler = new WorkflowCollaborationOutboxHandler(http, outbox);
        handlers = new WorkflowJavaExtensionHandlerRegistry(List.of(handler));
        delegate = new WorkflowExtensionDelegate(engine.getRepositoryService(), artifacts, handlers, http, sql);
        endpoint = new WfConnectorEndpoint();
        endpoint.setEndpointId(1L); endpoint.setEndpointKey("c08"); endpoint.setEndpointName("C08 local");
        endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        endpoint.setAllowedMethods("POST"); endpoint.setPathPrefix("/"); endpoint.setAuthType("NONE");
        endpoint.setConnectTimeoutMs(1000); endpoint.setRequestTimeoutMs(1000);
        endpoint.setNetworkScope("PRIVATE"); endpoint.setRevisionNo(1);
        when(endpoints.lockEnabledForDeployment("c08")).thenAnswer(i -> {
            endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint)); return endpoint;
        });
        source = new WfSqlDataSource();
        source.setDataSourceId(1L); source.setDataSourceKey("c08"); source.setDataSourceName("C08 isolated");
        source.setConnectionType("PRIMARY"); source.setAllowedTables("c08_effect");
        source.setConnectTimeoutMs(1000); source.setQueryTimeoutSeconds(5); source.setRevisionNo(1);
        when(sources.lockEnabledForDeployment("c08")).thenAnswer(i -> {
            source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source)); return source;
        });
    }

    @AfterEach void close()
    {
        try { if (server != null) server.stop(0); if (support != null) support.close(); }
        finally { if (meters != null) meters.close(); closeDatabase(); }
    }

    @Test void compilesAuthorDeploysAndRunsSqlOnceThenRollsBackARealDownstreamFailure()
    {
        String key = deployAuthor("SQL", sqlAuthor("insert into c08_effect(id,amount) values(:id,:amount)"));
        clearInvocations(validator);
        String instance = start(key, Map.of("id", "first", "amount", 7));
        run(instance);
        assertThat(support.jdbcTemplate().queryForMap("select * from c08_effect"))
                .containsEntry("id", "first").containsEntry("amount", 7);
        assertThat(support.processEngine().getRuntimeService().getVariable(instance, "answer")).isEqualTo(1);
        verify(validator).validate(anyString(), anySet(), eq(false), isNull());
        failAfter = true;
        String failing = start(key, Map.of("id", "rolled-back", "amount", 8));
        assertThatThrownBy(() -> run(failing)).isInstanceOf(RuntimeException.class);
        assertThat(countEffects()).isOne();
        failAfter = false;
        run(failing);
        assertThat(countEffects()).isEqualTo(2);
    }

    @Test void preservesOldSqlDefaultFieldsAndBoundedSelectResults()
    {
        support.jdbcTemplate().update("insert into c08_effect values('a',1),('b',2)");
        ObjectNode config = freezeSql("select amount from c08_effect where amount >= :amount");
        config.withObject("parameters").remove("id");
        config.remove("maxRows");
        String instance = start(deploy("SQL", config, s -> {}), Map.of("amount", 1));
        run(instance);
        assertThat(JsonMapper.shared().readTree((String) support.processEngine().getRuntimeService().getVariable(instance, "answer"))).hasSize(2);
    }

    @Test void rejectsIllegalSqlBeforeAnyDatabaseWriteAndPropagatesRealDatabaseFailure()
    {
        ObjectNode valid = freezeSql("insert into c08_effect(id,amount) values(:id,:amount)");
        List<Consumer<ObjectNode>> mutations = List.of(
                c -> c.put("sql", "insert into c08_effect values(:id,:amount); delete from c08_effect"),
                c -> c.put("sql", "delete from c08_effect"),
                c -> c.put("sql", "insert into forbidden(id,amount) values(:id,:amount)"),
                c -> c.withObject("parameters").remove("amount"),
                c -> c.put("operation", "SELECT"),
                c -> c.putArray("tables").add("forbidden"),
                c -> c.withObject("dataSourceSnapshot").put("checksum", "0".repeat(64)));
        for (var mutation : mutations) {
            ObjectNode bad = valid.deepCopy(); mutation.accept(bad);
            String instance = start(deploy("SQL", bad, s -> {}), Map.of("id", "bad", "amount", 1));
            assertThatThrownBy(() -> run(instance)).isInstanceOf(ServiceException.class);
            assertThat(countEffects()).isZero();
        }
        String key = deploy("SQL", valid, s -> {});
        run(start(key, Map.of("id", "duplicate", "amount", 1)));
        assertThatThrownBy(() -> run(start(key, Map.of("id", "duplicate", "amount", 2))))
                .isInstanceOf(ServiceException.class).hasMessage("SQL 连接器执行失败");
        assertThat(support.jdbcTemplate().queryForObject("select amount from c08_effect", Integer.class)).isOne();
    }

    @Test void executesCelWithOneCompilationAndWritesOnlyAfterSuccessfulEvaluation()
    {
        String key = deployAuthor("CEL", CEL);
        String instance = start(key, Map.of("amount", 7));
        try (var compiler = mockStatic(CelCompilerFactory.class, CALLS_REAL_METHODS)) {
            run(instance);
            compiler.verify(CelCompilerFactory::standardCelCompilerBuilder, times(1));
        }
        assertThat(support.processEngine().getRuntimeService().getVariable(instance, "answer")).isEqualTo(8L);
        for (String invalid : List.of(CEL.replace("amount + 1", "Runtime.getRuntime()"),
                CEL.replace("amount + 1", "amount / 0"), CEL.replace("amount", "initiator"),
                CEL.replace("amount + 1", "9223372036854775807 + amount"),
                CEL.replace("amount + 1", "amount".repeat(1000)))) {
            String rejected = start(deploy("CEL", read(invalid), s -> {}), Map.of("amount", 7));
            assertThatThrownBy(() -> run(rejected)).isInstanceOf(ServiceException.class);
            assertThat(support.processEngine().getRuntimeService().hasVariable(rejected, "answer")).isFalse();
        }
        String badType = start(key, Map.of("amount", "7"));
        assertThatThrownBy(() -> run(badType)).isInstanceOf(ServiceException.class).hasMessageContaining("声明类型");
    }

    @Test void sendsOneRealHttpRequestPerAttemptWithStableRetryKeyAndStatus()
    {
        String key = deployAuthor("HTTP", httpAuthor());
        String instance = start(key, Map.of("payload", Map.of("amount", 7)));
        responseStatus = 503;
        assertThatThrownBy(() -> run(instance)).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getSubCode()).isEqualTo("WORKFLOW_CONNECTOR_HTTP_STATUS"));
        assertThat(requests.get()).isOne();
        assertThat(support.processEngine().getRuntimeService().hasVariable(instance, "answer")).isFalse();
        responseStatus = 200;
        run(instance);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(keys.get(0)).isEqualTo(keys.get(1));
        assertThat(bodies).containsExactly("{\"amount\":7}", "{\"amount\":7}");
        assertThat(support.processEngine().getRuntimeService().getVariable(instance, "answer")).isEqualTo(200L);
        verify(http, never()).validateFrozenConfig(any());
    }

    @Test void rejectsEndpointPathCredentialsAndRequestCapacityBeforeHttpSend()
    {
        ObjectNode valid = freezeHttp();
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                c -> c.put("path", "//another-host/path"), c -> c.put("path", "/%2e%2e/escape"),
                c -> c.put("method", "PUT"), c -> c.withObject("endpointSnapshot").put("checksum", "0".repeat(64)))) {
            var bad = valid.deepCopy(); mutation.accept(bad);
            assertThatThrownBy(() -> run(start(deploy("HTTP", bad, s -> {}), Map.of("payload", Map.of("ok", true)))))
                    .isInstanceOf(ServiceException.class);
        }
        endpoint.setNetworkScope("PUBLIC");
        assertThatThrownBy(() -> run(start(deploy("HTTP", freezeHttp(), s -> {}), Map.of("payload", Map.of()))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("非公网地址");
        endpoint.setNetworkScope("PRIVATE"); endpoint.setAuthType("API_KEY"); endpoint.setApiKeyHeader("X-Integration-Token");
        endpoint.setSecretRef("WORKFLOW_CONNECTOR_SECRET_C08");
        var credentialConfig = freezeHttp();
        endpoint.setSecretRef("WORKFLOW_CONNECTOR_SECRET_C08_MISSING");
        credentialConfig.withObject("endpointSnapshot").put("secretRef", endpoint.getSecretRef())
                .put("checksum", WorkflowConnectorEndpointService.endpointChecksum(endpoint));
        assertThatThrownBy(() -> run(start(deploy("HTTP", credentialConfig, s -> {}), Map.of("payload", Map.of()))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("未注入");
        assertThatThrownBy(() -> run(start(deploy("HTTP", valid, s -> {}), Map.of("payload", "x".repeat(70000)))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("大小限制");
        assertThat(requests.get()).isZero();
    }

    @Test void rejectsRedirectAndOversizedResponseWithoutWritingStatusAndKeepsNetworkFailure()
    {
        String key = deployAuthor("HTTP", httpAuthor());
        String instance = start(key, Map.of("payload", Map.of()));
        responseStatus = 302;
        assertThatThrownBy(() -> run(instance)).isInstanceOf(ServiceException.class);
        assertThat(requests.get()).isOne(); // Location was not followed.
        responseStatus = 200; response = new byte[65537];
        assertThatThrownBy(() -> run(instance)).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getSubCode()).isEqualTo("WORKFLOW_CONNECTOR_HTTP_RESPONSE_TOO_LARGE"));
        assertThat(requests.get()).isEqualTo(2);
        server.stop(0);
        assertThatThrownBy(() -> run(instance)).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getSubCode()).isEqualTo("WORKFLOW_CONNECTOR_HTTP_IO_ERROR"));
        assertThat(support.processEngine().getRuntimeService().hasVariable(instance, "answer")).isFalse();
    }

    @Test void rejectsVersionSchemaImplementationAndSnapshotDriftBeforeSideEffects()
    {
        ObjectNode config = freezeHttp();
        for (Consumer<WfDeployExtensionSnapshot> mutation : List.<Consumer<WfDeployExtensionSnapshot>>of(
                s -> s.setVersionNo(2), s -> s.setVersionChecksum("0".repeat(64)),
                s -> s.setImplementationKey("UNINSTALLED"))) {
            String key = deploy("HTTP", config, s -> { mutation.accept(s); s.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(s)); });
            assertThatThrownBy(() -> run(start(key, Map.of("payload", Map.of())))).isInstanceOf(ServiceException.class);
        }
        String corrupt = deploy("HTTP", config, s -> s.setSnapshotChecksum("0".repeat(64)));
        assertThatThrownBy(() -> run(start(corrupt, Map.of("payload", Map.of()))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("快照校验和");
        assertThat(requests.get()).isZero();
    }

    @Test void outboxEnqueueRollsBackWithEngineAndReplayCommitsOneOccurrence()
    {
        String key = deployOutbox();
        failAfter = true;
        String rolledBack = start(key, Map.of("amount", 7));
        assertThatThrownBy(() -> run(rolledBack)).isInstanceOf(RuntimeException.class);
        assertThat(rows("wf_collaboration_outbox")).isZero();
        assertThat(rows("wf_collaboration_channel")).isZero();
        assertThat(rows("wf_collaboration_message_audit")).isZero();
        failAfter = false; replay = true;
        run(rolledBack);
        assertThat(rows("wf_collaboration_outbox")).isOne();
        assertThat(rows("wf_collaboration_channel")).isOne();
        assertThat(rows("wf_collaboration_message_audit")).isOne();
        assertThat(requests.get()).isZero();
    }

    /** 领取 SQL 使用 MySQL interval 语法，必须在 MySQL 子类执行，不改写生产 Mapper 来适配 H2。 */
    protected void verifyOutboxDeliveryRetry()
    {
        run(start(deployOutbox(), Map.of("amount", 7)));
        var first = outbox.claimNext("c08-worker");
        assertThat(first.getStatus()).isEqualTo("DELIVERING");
        assertThat(read(first.getVariablesJson())).isEqualTo(read("{\"amount\":7}"));
        assertThat(outbox.claimNext("other-worker")).isNull();
        // Mutable author directory is no longer read by delivery.
        clearInvocations(endpoints);
        responseStatus = 503;
        var failure = outbox.deliver(first);
        assertThat(failure.errorCode()).isEqualTo("COLLAB_OUTBOX_HTTP_STATUS");
        outbox.completeFailure(first, "c08-worker", failure);
        assertThat(mapper(WfCollaborationOutboxMapper.class).selectById(first.getMessageId()).getStatus()).isEqualTo("RETRYING");
        support.jdbcTemplate().update("update wf_collaboration_outbox set next_attempt_time=current_timestamp where message_id=?", first.getMessageId());
        var second = outbox.claimNext("c08-worker");
        assertThat(second.getAttemptCount()).isEqualTo(2);
        responseStatus = 200;
        var success = outbox.deliver(second);
        assertThat(success.success()).isTrue();
        outbox.completeSuccess(second, "c08-worker", success);
        assertThat(mapper(WfCollaborationOutboxMapper.class).selectById(first.getMessageId()).getStatus()).isEqualTo("PROCESSED");
        assertThatThrownBy(() -> outbox.completeSuccess(first, "c08-worker", success))
                .isInstanceOf(ServiceException.class).hasMessageContaining("状态提交冲突");
        assertThat(keys).containsExactly(first.getMessageId(), first.getMessageId());
        assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
        assertThat(rows("wf_collaboration_message_audit")).isEqualTo(5);
        verify(endpoints, never()).lockEnabledForDeployment(anyString());
    }

    @Test void javaHandlerNormalizationAndChangedOutboxPayloadStillRejectBeforeCommit()
    {
        String key = deployOutbox();
        replay = true; changedReplay = true;
        assertThatThrownBy(() -> run(start(key, Map.of("amount", 7))))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getSubCode()).isEqualTo("COLLAB_OUTBOX_IDEMPOTENCY_CONFLICT"));
        assertThat(rows("wf_collaboration_outbox")).isZero();
        assertThat(rows("wf_collaboration_channel")).isZero();
        replay = false;
        var config = read(handler.freezeConfig(read(outboxAuthor())));
        config.put("messageName", " notice ");
        assertThatThrownBy(() -> run(start(deploy("JAVA", config, s -> {}), Map.of("amount", 7))))
                .isInstanceOf(ServiceException.class).hasMessage("扩展执行配置规范化结果已漂移");
        assertThat(rows("wf_collaboration_outbox")).isZero();
        assertThat(requests.get()).isZero();
    }

    protected void verifyOutboxStoredEndpointFailure()
    {
        String instance = start(deployOutbox(), Map.of("amount", 7));
        run(instance);
        var stored = support.jdbcTemplate().queryForMap("select message_id,delivery_config_json from wf_collaboration_outbox");
        var damaged = read((String) stored.get("delivery_config_json"));
        damaged.withObject("endpointSnapshot").put("checksum", "0".repeat(64));
        support.jdbcTemplate().update("update wf_collaboration_outbox set delivery_config_json=? where message_id=?", damaged.toString(), stored.get("message_id"));
        var claimed = outbox.claimNext("c08-worker");
        var failure = outbox.deliver(claimed);
        assertThat(failure.success()).isFalse();
        assertThat(failure.errorCode()).isEqualTo("COLLAB_OUTBOX_DELIVERY_FAILED");
        outbox.completeFailure(claimed, "c08-worker", failure);
        var after = mapper(WfCollaborationOutboxMapper.class).selectById(claimed.getMessageId());
        assertThat(after.getStatus()).isEqualTo("RETRYING");
        assertThat(after.getAttemptCount()).isOne();
        assertThat(rows("wf_collaboration_message_audit")).isEqualTo(3);
        assertThat(requests.get()).isZero();
    }

    private String deployOutbox()
    {
        endpoint.setAuthType("API_KEY"); endpoint.setApiKeyHeader("X-Integration-Token");
        endpoint.setSecretRef("WORKFLOW_CONNECTOR_SECRET_C08");
        return deployAuthor("JAVA", outboxAuthor());
    }

    private String outboxAuthor() { return "{\"endpointKey\":\"c08\",\"path\":\"/workflow/runtime-event/collaboration/message\",\"messageName\":\"notice\",\"targetProcessDefinitionKey\":\"receiver\",\"variableNames\":[\"amount\"],\"maxAttempts\":3}"; }
    private String httpAuthor() { return "{\"endpointKey\":\"c08\",\"method\":\"POST\",\"path\":\"/invoke\",\"bodyVariable\":\"payload\",\"statusVariable\":\"answer\"}"; }
    private ObjectNode freezeHttp() { return read(http.freezeConfig(read(httpAuthor()), true)); }
    protected String sqlAuthor(String statement)
    {
        var c = JsonMapper.shared().createObjectNode(); c.put("dataSourceKey", "c08"); c.put("sql", statement);
        var p = c.putObject("parameters"); if (statement.contains(":id,")) p.put("id", "id"); p.put("amount", "amount");
        c.put("resultVariable", "answer"); return c.toString();
    }
    protected ObjectNode freezeSql(String statement) { return read(sql.freezeConfig(read(sqlAuthor(statement)), true)); }
    protected ObjectNode freezeSqlAuthor(ObjectNode author) { return read(sql.freezeConfig(author, true)); }
    protected int countEffects() { return rows("c08_effect"); }
    private int rows(String table) { return support.jdbcTemplate().queryForObject("select count(*) from " + table, Integer.class); }
    private <T> T mapper(Class<T> type) { return WorkflowH2SchemaMapperSupport.createSpringMapper(support.dataSource(), "c08", type, "mapper/flowable/" + type.getSimpleName() + ".xml"); }
    protected ObjectNode read(String json) { return (ObjectNode) JsonMapper.shared().readTree(json); }
    protected String start(String key, Map<String, Object> variables) { return support.processEngine().getRuntimeService().startProcessInstanceByKey(key, "business-c08", variables).getId(); }
    /** ManagementService 保留 Flowable Job 失败包装；断言其 cause 中的业务异常。 */
    protected void run(String instance)
    {
        var management = support.processEngine().getManagementService();
        var job = management.createJobQuery().processInstanceId(instance).singleResult();
        if (job == null) {
            var timer = management.createTimerJobQuery().processInstanceId(instance).singleResult();
            assertThat(timer).as("Flowable failed attempt must schedule its native retry").isNotNull();
            job = management.moveTimerToExecutableJob(timer.getId());
        }
        assertThat(job).isNotNull();
        try { support.processEngine().getManagementService().executeJob(job.getId()); }
        catch (org.flowable.common.engine.api.FlowableException failure) {
            assertThat(failure.getMessage()).contains("Job " + job.getId() + " failed");
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }

    /** 正向路径使用正式作者 BPMN 解析、扩展编译、Flowable 部署和制品仓库。 */
    private String deployAuthor(String type, String author)
    {
        String key = "c08_" + ++serial;
        String xml = model(key, "<extensionElements><flowable:field name=\"approvaExtensionKey\"><flowable:string>c08.extension</flowable:string></flowable:field><flowable:field name=\"approvaExtensionConfig\"><flowable:string><![CDATA[" + author + "]]></flowable:string></flowable:field></extensionElements>");
        var registry = mock(WorkflowExtensionRegistryService.class);
        var option = new WorkflowExtensionOptionView(1L, "c08.extension", "C08", type, 1L, 1, implementation(type), schema(type), version(type));
        when(registry.lockLatestForDeployment("c08.extension")).thenReturn(option);
        var compiler = new WorkflowExtensionDeploymentService(registry, handlers, http, sql);
        var bpmn = new WorkflowBpmnService(support.processEngine().getRepositoryService(), new WorkflowFormFieldExtensionService(null), null);
        var document = bpmn.validateForSave(xml.getBytes(StandardCharsets.UTF_8));
        var executable = bpmn.executionModel(document);
        var snapshots = compiler.prepare(document, executable, "1");
        var deployment = support.processEngine().getRepositoryService().createDeployment().addBytes("c08.bpmn20.xml", new BpmnXMLConverter().convertToXML(executable)).deploy();
        snapshots.forEach(s -> { s.setDeployId(deployment.getId()); s.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(s)); });
        persist(deployment.getId(), snapshots);
        return key;
    }

    /** 损坏制品与旧字段场景仍经过真实资源读取和真实 Delegate，不模拟其返回对象。 */
    protected String deploy(String type, JsonNode config, Consumer<WfDeployExtensionSnapshot> mutation)
    {
        String key = "c08_" + ++serial;
        var deployment = support.processEngine().getRepositoryService().createDeployment().addString("c08.bpmn20.xml", model(key, "")).deploy();
        var s = new WfDeployExtensionSnapshot(); s.setDeployId(deployment.getId()); s.setProcessKey(key); s.setElementId("invoke");
        s.setExtensionKey("c08.extension"); s.setExtensionVersionId(1L); s.setVersionNo(1); s.setExtensionType(type);
        s.setImplementationKey(implementation(type)); s.setConfigJson(config.toString()); s.setVersionChecksum(version(type));
        s.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(s)); mutation.accept(s);
        persist(deployment.getId(), List.of(s)); return key;
    }
    private void persist(String id, List<WfDeployExtensionSnapshot> snapshots) { artifacts.persist(id, new WorkflowDeploymentArtifacts(null, null, null, null, snapshots, null, null, null)); }
    private String implementation(String type) { return switch(type) { case "SQL" -> WorkflowSqlConnector.IMPLEMENTATION_KEY; case "HTTP" -> WorkflowHttpConnector.IMPLEMENTATION_KEY; case "CEL" -> WorkflowExtensionRegistryService.CEL_IMPLEMENTATION_KEY; default -> WorkflowCollaborationOutboxHandler.IMPLEMENTATION_KEY; }; }
    private String schema(String type) { return switch(type) { case "SQL" -> sql.configSchema(); case "HTTP" -> http.configSchema(); case "CEL" -> new WorkflowCelSandbox().configSchema(); default -> WorkflowExtensionJsonCanonicalizer.canonicalize(handler.configSchema()); }; }
    private String version(String type) { return WorkflowExtensionChecksum.sha256("c08.extension", type, "1", implementation(type), schema(type)); }
    private String model(String key, String fields) { return """
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="c08">
          <process id="%s" isExecutable="true"><extensionElements><flowable:properties>
            <flowable:property name="approva.startScope.ruleVersion" value="1"/>
            <flowable:property name="approva.startScope.type" value="PUBLIC"/>
            <flowable:property name="approva.startScope.targetIds" value=""/>
            <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
          </flowable:properties></extensionElements><startEvent id="start"><extensionElements>
            <flowable:formProperty id="amount" name="Amount" type="long" readable="true" writable="true"/>
          </extensionElements></startEvent><sequenceFlow id="s1" sourceRef="start" targetRef="invoke"/>
            <serviceTask id="invoke" flowable:async="true" flowable:delegateExpression="${workflowExtensionDelegate}">%s</serviceTask>
            <sequenceFlow id="s2" sourceRef="invoke" targetRef="wait"/><receiveTask id="wait"/>
          </process>
        </definitions>
        """.formatted(key, fields); }
}
