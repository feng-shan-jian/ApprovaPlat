package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.flowable.domain.dto.WorkflowDecisionTableRequest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flowable.dmn.engine.DmnEngine;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.impl.persistence.entity.ModelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.flowable.config.WorkflowDecisionTableConfiguration;
import com.ruoyi.flowable.domain.*;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.engine.*;
import com.ruoyi.flowable.extension.WorkflowConditionRouter;
import com.ruoyi.flowable.extension.*;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.listener.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.task.*;

/** 正式保存、预检、部署与真实 Flowable/DMN 运行的组合模型；目录及无关通知外设隔离。 */
class WorkflowModelCompilationIntegrationTest
{
    @Test
    void referenceCheckerReadsCurrentTopLevelFormIdAndBlocksDamagedMetadata()
    {
        var repository = mock(org.flowable.engine.RepositoryService.class);
        var models = mock(org.flowable.engine.repository.ModelQuery.class, RETURNS_SELF);
        var definitions = mock(org.flowable.engine.repository.ProcessDefinitionQuery.class, RETURNS_SELF);
        var model = mock(org.flowable.engine.repository.Model.class);
        var artifacts = mock(WorkflowDeploymentArtifactRepository.class);
        when(repository.createModelQuery()).thenReturn(models);
        when(models.list()).thenReturn(List.of(model));
        when(repository.createProcessDefinitionQuery()).thenReturn(definitions);
        when(definitions.list()).thenReturn(List.of());
        var checker = new com.ruoyi.flowable.service.impl.FlowableWorkflowReferenceChecker(repository, artifacts);
        when(model.getMetaInfo()).thenReturn("{\"createUser\":\"100\",\"formType\":1,\"formId\":7}");
        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
        assertThat(checker.hasFormReference(List.of(8L))).isFalse();
        for (String metadata : List.of("{}", "{\"formId\":null}", "{\"nested\":{\"formId\":7}}",
                "{\"FORMID\":7}", "{\"formKey\":\"key_7\"}"))
        {
            when(model.getMetaInfo()).thenReturn(metadata);
            assertThat(checker.hasFormReference(List.of(7L))).as(metadata).isFalse();
        }
        for (String metadata : List.of("broken-json", "[]", "1", "{\"formId\":[7]}",
                "{\"formId\":\"key_7\"}", "{\"formId\":0}", "{\"formId\":9223372036854775808}"))
        {
            when(model.getMetaInfo()).thenReturn(metadata);
            assertThat(checker.hasFormReference(List.of(99L))).as(metadata).isTrue();
        }
        when(model.getMetaInfo()).thenReturn("{}");
        var definition = mock(org.flowable.engine.repository.ProcessDefinition.class);
        when(definition.getId()).thenReturn("definition");
        when(definitions.list()).thenReturn(List.of(definition));
        var bpmn = new org.flowable.bpmn.model.BpmnModel();
        var process = new org.flowable.bpmn.model.Process(); process.setId("current");
        var start = new org.flowable.bpmn.model.StartEvent(); start.setId("start"); start.setFormKey("key_7");
        process.addFlowElement(start); bpmn.addProcess(process);
        when(repository.getBpmnModel("definition")).thenReturn(bpmn);
        assertThat(checker.hasFormReference(List.of(7L))).isTrue();
        when(artifacts.hasFormReference(Set.of(8L))).thenReturn(true);
        assertThat(checker.hasFormReference(List.of(8L))).isTrue();
    }

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
            var endpointService = mock(WorkflowConnectorEndpointService.class);
            var endpoint = new WfConnectorEndpoint();
            endpoint.setEndpointId(1L); endpoint.setEndpointKey("local"); endpoint.setEndpointName("local");
            endpoint.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            endpoint.setAllowedMethods("POST"); endpoint.setPathPrefix("/effect"); endpoint.setAuthType("NONE");
            endpoint.setNetworkScope("PRIVATE"); endpoint.setConnectTimeoutMs(1000); endpoint.setRequestTimeoutMs(1000);
            endpoint.setRevisionNo(1); endpoint.setChecksum(WorkflowConnectorEndpointService.endpointChecksum(endpoint));
            when(endpointService.lockEnabledForDeployment("local")).thenReturn(endpoint);
            var metrics = mock(com.ruoyi.flowable.runtime.WorkflowConnectorMetrics.class);
            var http = new WorkflowHttpConnector(endpointService, mock(WorkflowConnectorSecretResolver.class), metrics);
            var httpAuthor = (tools.jackson.databind.node.ObjectNode) json.readTree("""
                    {"endpointKey":"local","method":"POST","path":"/effect","bodyVariable":"initiator","statusVariable":"httpResult"}
                    """);
            var httpFrozen = (tools.jackson.databind.node.ObjectNode) json.readTree(http.freezeConfig(httpAuthor, true));
            var dataSources = mock(WorkflowSqlDataSourceService.class);
            var source = new WfSqlDataSource();
            source.setDataSourceId(1L); source.setDataSourceKey("local"); source.setDataSourceName("local");
            source.setConnectionType("PRIMARY"); source.setAllowedTables("effects");
            source.setConnectTimeoutMs(1000); source.setQueryTimeoutSeconds(2); source.setRevisionNo(1);
            source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
            when(dataSources.lockEnabledForDeployment("local")).thenReturn(source);
            var sql = new WorkflowSqlConnector(dataSource, dataSources, new WorkflowSqlTemplateValidator(),
                    mock(WorkflowSqlSecretResolver.class), metrics);
            var sqlAuthor = (tools.jackson.databind.node.ObjectNode) json.readTree("""
                    {"dataSourceKey":"local","sql":"insert into effects (owner) values (:owner)","parameters":{"owner":"initiator"},"resultVariable":"sqlResult"}
                    """);
            var sqlFrozen = (tools.jackson.databind.node.ObjectNode) json.readTree(sql.freezeConfig(sqlAuthor, true));
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
                assertThatThrownBy(() -> http.freezeConfig(httpAuthor.deepCopy().put("statusVariable", name), true)).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> http.execute(execution, httpFrozen.deepCopy().put("statusVariable", name))).as(name).hasMessageContaining("保留变量");
                assertThatThrownBy(() -> sql.freezeConfig(sqlAuthor.deepCopy().put("resultVariable", name), true)).as(name).hasMessageContaining("保留变量");
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

    private static com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView extensionOption(
            String key, String type, String implementation, String schema)
    {
        String canonical = WorkflowExtensionJsonCanonicalizer.canonicalize(schema);
        return new com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView(1L, key, key, type,
                1L, 1, implementation, canonical,
                WorkflowExtensionChecksum.sha256(key, type, "1", implementation, canonical));
    }

    @Test
    void compilesOnceAndRunsFrozenPermissionsDecisionRoutingAndChildWithoutPollutingAuthor()
    {
        new ApplicationContextRunner().withUserConfiguration(
                Database.class, WorkflowDecisionTableConfiguration.class)
                .withPropertyValues("flowable.database-schema-update=true", "flowable.async-executor-activate=false",
                        "flowable.idm.enabled=false", "flowable.eventregistry.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var engine = context.getBean(ProcessEngine.class);
                    var repository = spy(engine.getRepositoryService());
                    var runtime = engine.getRuntimeService();
                    var tasks = engine.getTaskService();
                    var identities = mock(WorkflowIdentityResolver.class);
                    when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
                    when(identities.resolveApprovalEligibleUserIds(anyCollection())).thenAnswer(invocation -> Set.copyOf(invocation.getArgument(0)));
                    var proxy = new ProxyFactory(new WorkflowEngineOperations(new WorkflowAuthenticationContext(
                            engine.getIdentityService(), new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identities));
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice(new TransactionInterceptor(context.getBean(PlatformTransactionManager.class),
                            new AnnotationTransactionAttributeSource()));
                    var operations = (WorkflowEngineOperations) proxy.getProxy();
                    var categories = mock(WfCategoryMapper.class);
                    var category = new WfCategory(); category.setCode("test"); category.setCategoryName("组合验收"); category.setDelFlag("0");
                    when(categories.selectByCode("test")).thenReturn(category);
                    var forms = mock(WfFormMapper.class);
                    var template = new WfForm(); template.setFormId(7L); template.setFormName("共享表单"); template.setDelFlag("0"); template.setContent(FORM);
                    when(forms.selectById(7L)).thenReturn(template);
                    var identityMapper = mock(WorkflowIdentityMapper.class);
                    var templates = spy(new WorkflowFormTemplateValidator());
                    var variables = new WorkflowStartVariableValidator(templates);
                    var artifacts = spy(new WorkflowDeploymentArtifactRepository(repository));
                    var failure = new AtomicBoolean();
                    doAnswer(invocation -> {
                        Object result = invocation.callRealMethod();
                        if (failure.get()) throw new IllegalStateException("after artifact persistence");
                        return result;
                    }).when(artifacts).persist(anyString(), any());
                    var participants = new WorkflowParticipantRuleRuntimeService(repository, runtime, artifacts, identityMapper,
                            identities, mock(WorkflowParticipantResolutionMetrics.class));
                    var dmn = context.getBean(DmnEngine.class);
                    var decisions = spy(new WorkflowDmnDecisionService(operations, dmn.getDmnRepositoryService(), artifacts));
                    var tables = new WorkflowDecisionTableService(decisions, dmn.getDmnDecisionService(), dmn.getDmnRepositoryService(), operations);
                    String sourceId = tables.publish(decimalTable());
                    String decisionId = dmn.getDmnRepositoryService().createDecisionQuery().deploymentId(sourceId).singleResult().getId();
                    var fieldExtensions = new WorkflowFormFieldExtensionService(null);
                    var eventCodes = mock(WorkflowBpmnEventCodeService.class);
                    var code = new WfBpmnEventCode(); code.setEventCode("REVIEW_LATE"); code.setEventName("审批超时");
                    when(eventCodes.requireEnabled("ESCALATION", "REVIEW_LATE")).thenReturn(code);
                    var calendarMapper = mock(WfTaskSlaMapper.class);
                    var calendar = new WfBusinessCalendar(); calendar.setCalendarKey("WORKDAY"); calendar.setCalendarName("工作日");
                    calendar.setStatus("ENABLED"); calendar.setTimezone("Asia/Shanghai"); calendar.setWorkingDays("1,2,3,4,5");
                    calendar.setWorkStart("09:00"); calendar.setWorkEnd("18:00");
                    when(calendarMapper.selectCalendarByKey("WORKDAY")).thenReturn(calendar);
                    var calendars = new WorkflowBusinessCalendarService(operations, calendarMapper);
                    var configuration = (org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
                    configuration.setBusinessCalendarManager(new com.ruoyi.flowable.config.WorkflowSlaBusinessCalendarManager(configuration::getClock, calendars));
                    var bpmn = new WorkflowBpmnService(repository, fieldExtensions, eventCodes);
                    var conditions = spy(new WorkflowConditionDeploymentService());
                    var calls = spy(new WorkflowCallActivityReferenceService(repository, operations, identities, participants, artifacts, templates));
                    var handler = new WorkflowSetVariableJavaHandler();
                    var handlers = new WorkflowJavaExtensionHandlerRegistry(List.of(handler));
                    var extensionRegistry = mock(WorkflowExtensionRegistryService.class);
                    when(extensionRegistry.lockLatestForDeployment("test-set")).thenReturn(
                            extensionOption("test-set", "JAVA", "SET_VARIABLE", handler.configSchema()));
                    var cel = new WorkflowCelSandbox();
                    when(extensionRegistry.lockLatestForDeployment("test-cel")).thenReturn(
                            extensionOption("test-cel", "CEL", WorkflowExtensionRegistryService.CEL_IMPLEMENTATION_KEY, cel.configSchema()));
                    var extensions = spy(new WorkflowExtensionDeploymentService(extensionRegistry, handlers, null, null));
                    var loops = spy(new WorkflowControlledLoopDeploymentService());
                    var participantRules = spy(new WorkflowParticipantRuleDeploymentService(identityMapper));
                    var sla = spy(new WorkflowTaskSlaDeploymentService(calendars, eventCodes, identities));
                    var listeners = spy(new WorkflowRuntimeTaskListenerCompiler());
                    var service = new WorkflowModelService(operations, repository, bpmn,
                            new WorkflowBpmnIdentityValidator(identityMapper), categories, forms, artifacts, templates,
                            extensions, loops, conditions, participantRules, decisions, fieldExtensions, calls, sla, listeners);
                    context.getBeanFactory().registerSingleton("workflowConditionRouter", new WorkflowConditionRouter(repository, artifacts));
                    context.getBeanFactory().registerSingleton("workflowExtensionDelegate",
                            new WorkflowExtensionDelegate(repository, artifacts, handlers, null, null));
                    context.getBeanFactory().registerSingleton("workflowBusinessListener",
                            new WorkflowBusinessListener(repository, artifacts, handlers));
                    context.getBeanFactory().registerSingleton("workflowSystemOutputListener", new WorkflowSystemOutputListener(repository, artifacts, variables));
                    context.getBeanFactory().registerSingleton("userTaskListener", new WorkflowUserTaskListener(
                            new WorkflowUserTaskAuditService(tasks, new WorkflowIdentityCodec()), mock(WorkflowTaskSlaRuntimeService.class),
                            participants, mock(WorkflowAutomaticCopyService.class), mock(WorkflowNotificationService.class),
                            mock(WorkflowMultiInstanceRoundLifecycleService.class)));
                    String child = save(service, repository, "compiledChild", childXml());
                    service.deployModel(child);
                    String author = parentXml(decisionId).trim();
                    String forbidden = save(service, repository, "unsafeParent",
                            author.replace("compiledParent", "unsafeParent").replace("javaResult", "initiator"));
                    assertThatThrownBy(() -> service.deployModel(forbidden)).hasMessageContaining("保留变量");
                    assertThat(repository.getModel(forbidden).getDeploymentId()).isNull();
                    clearInvocations(templates, forms, decisions, conditions, calls, extensions, loops, participantRules, sla, listeners);
                    String model = save(service, repository, "compiledParent", author);
                    verify(forms, times(1)).selectById(7L);
                    verify(templates, times(1)).validate(FORM);
                    verify(decisions, never()).prepare(any());
                    verify(sla, never()).prepare(any(), anyString());
                    verify(listeners, never()).compile(any());
                    var modelForm = new WorkflowModelDto();
                    modelForm.setModelId(model); modelForm.setFormType(0); modelForm.setFormId(7L);
                    service.updateModel(modelForm);
                    int revision = ((ModelEntity) repository.getModel(model)).getRevision();
                    verifyDesignDraftIsolation(context.getBean(DataSource.class), service, bpmn,
                            operations, identities, repository, forms, model, revision, author);
                    long deploymentCount = repository.createDeploymentQuery().count();
                    long dmnCount = dmn.getDmnRepositoryService().createDeploymentQuery().count();
                    var preflight = service.preflight(model, revision, author);
                    assertThat(preflight.valid()).as(preflight.issues().toString()).isTrue();
                    assertThat(repository.createDeploymentQuery().count()).isEqualTo(deploymentCount);
                    assertThat(dmn.getDmnRepositoryService().createDeploymentQuery().count()).isEqualTo(dmnCount);
                    assertThat(((ModelEntity) repository.getModel(model)).getRevision()).isEqualTo(revision);
                    failure.set(true);
                    assertThatThrownBy(() -> service.deployModel(model)).hasMessageContaining("after artifact persistence");
                    assertThat(repository.createDeploymentQuery().count()).isEqualTo(deploymentCount);
                    assertThat(dmn.getDmnRepositoryService().createDeploymentQuery().count()).isEqualTo(dmnCount);
                    assertThat(repository.getModel(model).getDeploymentId()).isNull();
                    failure.set(false);
                    clearInvocations(templates, forms, decisions, conditions, calls, extensions, loops, participantRules, sla, listeners);
                    String deployment = service.deployModel(model);
                    verify(forms, times(1)).selectById(7L);
                    verify(templates, times(1)).validate(FORM);
                    verify(decisions, times(1)).prepare(any());
                    verify(conditions, times(1)).prepare(any(), any(), any(), eq("100"));
                    verify(calls, times(1)).prepare(any(), any(), any(), any());
                    verify(extensions, times(1)).prepare(any(), any(), eq("100"));
                    verify(loops, times(1)).prepare(any(), any(), any(), eq("100"));
                    verify(participantRules, times(1)).prepare(any(), any(), any(), eq("100"));
                    verify(sla, times(1)).prepare(any(), eq("100"));
                    verify(listeners, times(1)).compile(any());
                    assertThat(artifacts.selectParticipantRules(deployment)).extracting(WfDeployParticipantRule::getChecksum)
                            .contains("e2e6244c69c1748ebbc98e010b838efbbe624a5733ed866a5d9e8abb583a012a");
                    var frozen = artifacts.openRead(deployment).forms().stream().collect(java.util.stream.Collectors.toMap(WfDeployForm::getNodeKey, WfDeployForm::getContent));
                    assertThat(variables.validateForStart(frozen.get("start"), Map.of("amount", BigDecimal.TEN)).variables()).containsKey("amount");
                    assertThatThrownBy(() -> variables.validateForStart(frozen.get("review"), Map.of("amount", BigDecimal.TEN)))
                            .isInstanceOf(com.ruoyi.common.exception.ServiceException.class);
                    var definition = repository.createProcessDefinitionQuery().deploymentId(deployment).processDefinitionKey("compiledParent").singleResult();
                    byte[] executable;
                    try (var input = repository.getResourceAsStream(deployment, definition.getResourceName())) { executable = input.readAllBytes(); }
                    String compiled = new String(executable, StandardCharsets.UTF_8);
                    assertThat(bpmn.executionValidationIssues(executable)).isEmpty();
                    assertThat(compiled).contains("escalationCode=\"REVIEW_LATE\"", "systemWritable=\"true\"");
                    assertThat(compiled).contains("dataObjectRef=\"data\"", "id=\"archive\"", "id=\"decisionProperty\"", "id=\"collaboration\"", "bpmnElement=\"collaboration\"");
                    var executedModel = repository.getBpmnModel(definition.getId());
                    assertThat(executedModel.getFlowElement("decide")).isInstanceOf(org.flowable.bpmn.model.ServiceTask.class);
                    assertThat(((org.flowable.bpmn.model.CallActivity) executedModel.getFlowElement("call")).getCalledElementType()).isEqualTo("id");
                    assertThat(repository.getModelEditorSource(model)).isEqualTo(author.getBytes(StandardCharsets.UTF_8));
                    var instance = operations.writeAsCurrentUser(actor -> runtime.startProcessInstanceByKey("compiledParent",
                            Map.of("amount", BigDecimal.TEN, "initiator", actor.userId())));
                    assertThat(runtime.getVariable(instance.getId(), "result")).isEqualTo(new BigDecimal("-9999999999.12345678"));
                    assertThat(runtime.getVariable(instance.getId(), "javaResult")).isEqualTo(7L);
                    assertThat(runtime.getVariable(instance.getId(), "celResult")).isEqualTo(8L);
                    assertThat(runtime.getVariable(instance.getId(), "listenerResult")).isEqualTo(true);
                    assertThat(runtime.getVariable(instance.getId(), "initiator")).isEqualTo("100");
                    assertThat(engine.getManagementService().createTimerJobQuery().processInstanceId(instance.getId()).count()).isEqualTo(2);
                    var task = tasks.createTaskQuery().processInstanceId(instance.getId()).singleResult();
                    assertThat(task.getTaskDefinitionKey()).isEqualTo("review"); assertThat(task.getAssignee()).isEqualTo("100");
                    operations.writeAsCurrentUser(actor -> { tasks.complete(task.getId(), Map.of("note", "approved")); return null; });
                    assertThat(engine.getManagementService().createTimerJobQuery().processInstanceId(instance.getId()).count()).isZero();
                    var childInstance = runtime.createProcessInstanceQuery().superProcessInstanceId(instance.getId()).singleResult();
                    assertThat(runtime.getVariable(childInstance.getId(), "amount")).isEqualTo(BigDecimal.TEN);
                    clearInvocations(templates);
                    runtime.trigger(runtime.createExecutionQuery().processInstanceId(childInstance.getId()).activityId("childWait").singleResult().getId());
                    assertThat(runtime.createProcessInstanceQuery().processInstanceId(instance.getId()).count()).isZero();
                    assertThat(engine.getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(instance.getId()).variableName("result").singleResult().getValue()).isEqualTo(BigDecimal.TEN);
                    // 监听器读取两个节点快照各一次；验证系统输出使用同一字段解释，没有字符串重入。
                    verify(templates, times(2)).validate(anyString());
                    var fallback = operations.writeAsCurrentUser(actor -> runtime.startProcessInstanceByKey("compiledParent", Map.of("amount", BigDecimal.ZERO, "initiator", actor.userId())));
                    assertThat(engine.getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(fallback.getId()).finished().count()).isOne();
                    assertDmnDeletionBoundaries(operations, dmn.getDmnRepositoryService(), artifacts,
                            sourceId, deployment, tables.publish(decimalTable()));
                    assertExtensionVersionBoundaries(operations);
                    assertResourceReadBoundaries(operations, engine.getRepositoryService(), dmn.getDmnRepositoryService(),
                            deployment, definition.getId(), decisionId, compiled);
                    assertLazyDeploymentRead(engine.getRepositoryService(), deployment, definition.getKey());
                });
    }

    private static void assertDmnDeletionBoundaries(WorkflowEngineOperations operations,
            org.flowable.dmn.api.DmnRepositoryService repository,
            WorkflowDeploymentArtifactRepository artifacts, String referencedSource,
            String parentDeployment, String unreferencedSource)
    {
        var observed = spy(repository);
        var service = new WorkflowDmnDecisionService(operations, observed, artifacts);
        assertResourceFailure(() -> service.delete("missing-dmn"), "DMN 部署不存在", 404);
        verify(observed, times(1)).createDeploymentQuery();
        clearInvocations(observed);
        service.delete(unreferencedSource);
        verify(observed, times(1)).createDeploymentQuery();
        assertThat(repository.createDeploymentQuery().deploymentId(unreferencedSource).count()).isZero();
        assertResourceFailure(() -> service.delete(referencedSource), "DMN 部署已被流程版本冻结引用", 409);
        String frozen = artifacts.selectDmnSnapshots(parentDeployment).get(0).getFrozenDeploymentId();
        assertResourceFailure(() -> service.delete(frozen), "流程冻结 DMN 部署不允许独立删除", 409);
        assertThat(repository.createDeploymentQuery().deploymentId(referencedSource).count()).isOne();
        assertThat(repository.createDeploymentQuery().deploymentId(frozen).count()).isOne();
    }

    private static void assertExtensionVersionBoundaries(WorkflowEngineOperations operations)
    {
        var mapper = mock(WfBpmnExtensionMapper.class);
        var javaHandler = new WorkflowSetVariableJavaHandler();
        var http = mock(WorkflowHttpConnector.class);
        var sql = mock(WorkflowSqlConnector.class);
        when(http.configSchema()).thenReturn("{\"type\":\"object\"}");
        when(sql.configSchema()).thenReturn("{\"type\":\"object\"}");
        var service = new WorkflowExtensionRegistryService(operations, mapper, null,
                new WorkflowJavaExtensionHandlerRegistry(List.of(javaHandler)), http, sql);
        var inserted = new java.util.ArrayList<WfBpmnExtensionVersion>();
        when(mapper.selectMaxVersionNo(1L)).thenReturn(4);
        when(mapper.insertVersion(any())).thenAnswer(invocation -> {
            WfBpmnExtensionVersion version = invocation.getArgument(0);
            version.setVersionId(5L); inserted.add(version); return 1;
        });
        for (String type : List.of("JAVA", "CEL", "HTTP", "SQL", "FORM_FIELD"))
        {
            var extension = new WfBpmnExtension();
            extension.setExtensionId(1L); extension.setExtensionKey("cleanup-test");
            extension.setExtensionType(type);
            when(mapper.selectByIdForUpdate(1L)).thenReturn(extension);
            String key = "JAVA".equals(type) ? javaHandler.implementationKey() : null;
            assertThat(service.createVersion(1L,
                    new com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest(key))).isEqualTo(5L);
            var version = inserted.get(inserted.size() - 1);
            assertThat(version.getVersionNo()).isEqualTo(5);
            assertThat(version.getChecksum()).isEqualTo(WorkflowExtensionChecksum.sha256(
                    "cleanup-test", type, "5", version.getImplementationKey(), version.getConfigSchema()));
            int count = inserted.size();
            if (!"JAVA".equals(type))
            {
                assertResourceFailure(() -> service.createVersion(1L,
                        new com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest("SET_VARIABLE")),
                        "非 JAVA 扩展不得提交服务端实现键", 400);
                assertThat(inserted).hasSize(count);
            }
        }
        assertThat(inserted).hasSize(5);
    }

    /** 同次详情共享两种实际资源，独立入口仍使用当前协议及清单门禁。 */
    private static void assertLazyDeploymentRead(org.flowable.engine.RepositoryService repository,
            String deploymentId, String processKey)
    {
        var observed = spy(repository);
        var artifacts = new WorkflowDeploymentArtifactRepository(observed);
        String artifactId = repository.createDeploymentQuery().parentDeploymentId(deploymentId)
                .deploymentCategory("APPROVAPLAT_WORKFLOW_ARTIFACTS").singleResult().getId();
        var read = artifacts.openRead(deploymentId);
        verifyNoInteractions(observed);
        var forms = read.forms();
        assertThat(read.forms()).isSameAs(forms);
        verify(observed, times(1)).createDeploymentQuery();
        verify(observed, times(1)).getResourceAsStream(artifactId, "approvaplat/manifest-v1.json");
        verify(observed, times(1)).getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
        verifyNoMoreInteractions(observed);
        assertThat(read.controlledLoops(processKey)).isEqualTo(read.controlledLoops(processKey));
        verify(observed, times(1)).getResourceAsStream(artifactId, "approvaplat/controlled-loops-v1.json");
        verifyNoMoreInteractions(observed);
        clearInvocations(observed);
        assertResourceFailure(() -> artifacts.openRead("").controlledLoop("", "activity"), "流程标识不能为空", 400);
        verifyNoInteractions(observed);

        String currentParent = repository.createDeployment().addString("fixture.txt", "current").deploy().getId();
        repository.createDeployment().parentDeploymentId(currentParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .addString("approvaplat/manifest-v1.json", "{\"schemaVersion\":2}")
                .addString("approvaplat/forms-v1.json", "[]")
                .addString("approvaplat/controlled-loops-v1.json", "[]")
                .addString("approvaplat/conditions-v2.json", """
                        [{"processKey":"current","gatewayId":"g","gatewayToken":"token","flowId":"f"}]
                        """).deploy();
        var current = artifacts.openRead(currentParent);
        assertThat(current.forms()).isEmpty();
        assertThat(current.controlledLoops("current")).isEmpty();
        assertThat(artifacts.selectRuntimeConditionRules(currentParent, "current", "token"))
                .extracting(WfDeployConditionRule::getFlowId).containsExactly("f");
        assertThat(artifacts.selectRuntimeConditionRules(currentParent, "unrelated", "token")).isEmpty();
        assertThat(artifacts.openRead("absent-deployment").forms()).isEmpty();

        String invalidParent = repository.createDeployment().addString("fixture.txt", "invalid").deploy().getId();
        for (String manifest : List.of("{\"schemaVersion\":1}", "{\"schemaVersion\":3}", "broken-json"))
        {
            String id = repository.createDeployment().parentDeploymentId(invalidParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                    .addString("approvaplat/manifest-v1.json", manifest).deploy().getId();
            assertResourceFailure(() -> artifacts.openRead(invalidParent).forms(),
                    manifest.startsWith("{") ? "流程部署业务资源协议版本不受支持" : "流程部署业务资源清单无法解析", 500);
            repository.deleteDeployment(id);
        }
        repository.createDeployment().parentDeploymentId(invalidParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .addString("fixture.txt", "missing-manifest").deploy();
        // 实际引擎的缺失资源抛 FlowableObjectNotFoundException；空流的 500 契约由前面的边界用例覆盖。
        assertThatThrownBy(() -> artifacts.openRead(invalidParent).forms())
                .isInstanceOf(org.flowable.common.engine.api.FlowableObjectNotFoundException.class);
        repository.createDeployment().parentDeploymentId(invalidParent).category("APPROVAPLAT_WORKFLOW_ARTIFACTS")
                .addString("fixture.txt", "duplicate").deploy();
        assertResourceFailure(() -> artifacts.openRead(invalidParent).forms(), "流程部署业务资源数量异常", 500);
    }

    /** 在真实部署、DMN 来源和制品定位之后注入资源流故障，覆盖公开读取/编译入口的契约。 */
    private static void assertResourceReadBoundaries(WorkflowEngineOperations operations,
            org.flowable.engine.RepositoryService repository,
            org.flowable.dmn.api.DmnRepositoryService dmnRepository,
            String deploymentId, String definitionId, String decisionId, String compiled)
    {
        var resources = spy(repository);
        var decisions = spy(dmnRepository);
        var artifacts = new WorkflowDeploymentArtifactRepository(resources);
        var deployments = new WorkflowDeploymentService(operations, resources,
                artifacts, null, null, null, null);
        var dmn = new WorkflowDmnDecisionService(operations, decisions, artifacts);
        assertThat(deployments.getBpmnXml(definitionId)).isEqualTo(compiled);
        java.util.function.Supplier<WorkflowDmnDecisionService.DecisionSource> compile = () -> {
            var model = new org.flowable.bpmn.model.BpmnModel();
            var process = new org.flowable.bpmn.model.Process(); process.setId("readBoundary");
            var task = new org.flowable.bpmn.model.BusinessRuleTask();
            task.setId("decision"); task.setRuleNames(List.of(decisionId));
            process.addFlowElement(task); model.addProcess(process);
            return dmn.prepare(model).get(0);
        };
        int xmlLimit = 2 * 1024 * 1024;
        for (int size : new int[] {0, xmlLimit, xmlLimit + 1})
        {
            byte[] bytes = new byte[size]; java.util.Arrays.fill(bytes, (byte) ' ');
            var bpmnStream = spy(new java.io.ByteArrayInputStream(bytes));
            var dmnStream = spy(new java.io.ByteArrayInputStream(bytes));
            doReturn(bpmnStream).when(resources).getProcessModel(definitionId);
            doReturn(dmnStream).when(decisions).getDmnResource(decisionId);
            if (size <= xmlLimit)
            {
                assertThat(deployments.getBpmnXml(definitionId)).isEqualTo(new String(bytes, StandardCharsets.UTF_8));
                assertThat(compile.get().resourceBytes()).containsExactly(bytes);
            }
            else
            {
                assertResourceFailure(() -> deployments.getBpmnXml(definitionId), "BPMN XML 超过大小限制", 400);
                assertResourceFailure(compile::get, "DMN XML 超过大小限制", 400);
            }
            try { verify(bpmnStream).close(); verify(dmnStream).close(); }
            catch (java.io.IOException exception) { throw new AssertionError(exception); }
        }
        doReturn(null).when(resources).getProcessModel(definitionId);
        doReturn(null).when(decisions).getDmnResource(decisionId);
        assertResourceFailure(() -> deployments.getBpmnXml(definitionId), "流程定义 BPMN 不存在", 404);
        assertResourceFailure(compile::get, "DMN 决策资源不存在", 409);
        doReturn(failingResourceStream()).when(resources).getProcessModel(definitionId);
        doReturn(failingResourceStream()).when(decisions).getDmnResource(decisionId);
        assertResourceFailure(() -> deployments.getBpmnXml(definitionId), "流程定义 BPMN 读取失败", 500);
        assertResourceFailure(compile::get, "DMN 业务规则任务编译失败", 400);

        String artifactId = repository.createDeploymentQuery().parentDeploymentId(deploymentId)
                .deploymentCategory("APPROVAPLAT_WORKFLOW_ARTIFACTS").singleResult().getId();
        int artifactLimit = 32 * 1024 * 1024;
        for (int size : new int[] {0, artifactLimit, artifactLimit + 1})
        {
            byte[] bytes = new byte[size]; java.util.Arrays.fill(bytes, (byte) ' ');
            if (size > 0) { bytes[0] = '['; bytes[1] = ']'; }
            doReturn(new java.io.ByteArrayInputStream(bytes)).when(resources)
                    .getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
            if (size == artifactLimit) assertThat(artifacts.openRead(deploymentId).forms()).isEmpty();
            else assertResourceFailure(() -> artifacts.openRead(deploymentId).forms(), size == 0
                    ? "流程部署业务资源无法解析" : "流程部署业务资源大小超过限制", 500);
        }
        doReturn(null).when(resources).getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
        assertResourceFailure(() -> artifacts.openRead(deploymentId).forms(), "流程部署业务资源缺失", 500);
        doReturn(failingResourceStream()).when(resources).getResourceAsStream(artifactId, "approvaplat/forms-v1.json");
        assertResourceFailure(() -> artifacts.openRead(deploymentId).forms(), "流程部署业务资源读取失败", 500);
    }

    private static java.io.InputStream failingResourceStream()
    {
        return new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("resource fault"); }
        };
    }

    private static void assertResourceFailure(Runnable action, String message, int code)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(com.ruoyi.common.exception.ServiceException.class,
                failure -> { assertThat(failure.getMessage()).isEqualTo(message); assertThat(failure.getCode()).isEqualTo(code); });
    }

    private static String save(WorkflowModelService service, org.flowable.engine.RepositoryService repository, String key, String xml)
    {
        var model = repository.newModel(); model.setKey(key); model.setName(key); model.setCategory("test"); model.setMetaInfo("{}"); repository.saveModel(model);
        repository.addModelEditorSource(model.getId(), xml.replace("urn:compile", "urn:initial").getBytes(StandardCharsets.UTF_8));
        var request = new WorkflowModelDto(); request.setModelId(model.getId()); request.setExpectedRevision(((ModelEntity) repository.getModel(model.getId())).getRevision()); request.setBpmnXml(xml);
        service.saveModel(request); return model.getId();
    }

    private static String properties(String scope, String type)
    {
        return "<flowable:properties><flowable:property name=\"approva." + scope + ".ruleVersion\" value=\"1\"/><flowable:property name=\"approva." + scope + ".type\" value=\"" + type + "\"/><flowable:property name=\"approva." + scope + ".targetIds\" value=\"\"/><flowable:property name=\"approva." + scope + ".noMatchPolicy\" value=\"FAIL\"/></flowable:properties>";
    }

    private static String permissions(boolean amountWritable)
    {
        return "<flowable:formProperty id=\"approva_permission_default\" type=\"string\" readable=\"true\" writable=\"true\"/>"
                + "<flowable:formProperty id=\"approva_permission_field_amount\" variable=\"amount\" type=\"string\" readable=\"true\" writable=\"" + amountWritable + "\"/>"
                + "<flowable:formProperty id=\"approva_permission_field_result\" variable=\"result\" type=\"string\" readable=\"true\" writable=\"false\"/>"
                + "<flowable:formProperty id=\"approva_permission_field_note\" variable=\"note\" type=\"string\" readable=\"true\" writable=\"true\"/>";
    }

    private static String childXml()
    {
        return HEADER + "<process id=\"compiledChild\" isExecutable=\"true\"><extensionElements>" + properties("startScope", "PUBLIC")
                + "</extensionElements><startEvent id=\"childStart\" flowable:formKey=\"key_7\"><extensionElements>" + permissions(true)
                + "</extensionElements></startEvent><receiveTask id=\"childWait\"/><endEvent id=\"childEnd\"/>"
                + "<sequenceFlow id=\"c1\" sourceRef=\"childStart\" targetRef=\"childWait\"/><sequenceFlow id=\"c2\" sourceRef=\"childWait\" targetRef=\"childEnd\"/></process></definitions>";
    }

    private static String parentXml(String decision)
    {
        String rule = "{\"version\":1,\"default\":false,\"combinator\":\"AND\",\"groups\":[{\"combinator\":\"AND\",\"rules\":[{\"field\":\"amount\",\"operator\":\"GT\",\"value\":0}]}]}";
        return HEADER + """
                <process id="compiledParent" isExecutable="true"><extensionElements>%s</extensionElements>
                  <startEvent id="start" flowable:formKey="key_7"><extensionElements>%s</extensionElements></startEvent>
                  <serviceTask id="seed" flowable:delegateExpression="${workflowExtensionDelegate}"><extensionElements>
                    <flowable:field name="approvaExtensionKey"><flowable:string>test-set</flowable:string></flowable:field>
                    <flowable:field name="approvaExtensionConfig"><flowable:string><![CDATA[{"targetVariable":"javaResult","value":7}]]></flowable:string></flowable:field>
                  </extensionElements></serviceTask>
                  <serviceTask id="calculate" flowable:delegateExpression="${workflowExtensionDelegate}"><extensionElements>
                    <flowable:field name="approvaExtensionKey"><flowable:string>test-cel</flowable:string></flowable:field>
                    <flowable:field name="approvaExtensionConfig"><flowable:string><![CDATA[{"expression":"javaResult + 1","resultVariable":"celResult","resultType":"INT","variables":[{"name":"javaResult","type":"INT"}]}]]></flowable:string></flowable:field>
                  </extensionElements></serviceTask>
                  <businessRuleTask id="decide" flowable:rules="%s"><property id="decisionProperty"/><dataInputAssociation id="decisionInput"><sourceRef>visibleData</sourceRef><targetRef>decisionProperty</targetRef></dataInputAssociation></businessRuleTask>
                  <exclusiveGateway id="choice" default="fallback"/>
                  <userTask id="review" name="审批" flowable:formKey="key_7"><extensionElements>%s%s
                    <flowable:taskListener event="create" delegateExpression="${workflowBusinessListener}">
                      <flowable:field name="approvaExtensionKey"><flowable:string>test-set</flowable:string></flowable:field>
                      <flowable:field name="approvaExtensionConfig"><flowable:string><![CDATA[{"targetVariable":"listenerResult","value":true}]]></flowable:string></flowable:field>
                    </flowable:taskListener>
                  </extensionElements></userTask>
                  <callActivity id="call" calledElement="compiledChild"><extensionElements><flowable:in source="amount" target="amount"/><flowable:out source="amount" target="result"/></extensionElements><dataOutputAssociation id="output"><targetRef>archive</targetRef></dataOutputAssociation></callActivity>
                  <endEvent id="end"/>
                  <dataObject id="data" name="payload"/><dataObjectReference id="visibleData" dataObjectRef="data"/><dataStoreReference id="archive"/>
                  <sequenceFlow id="f1" sourceRef="start" targetRef="seed"/><sequenceFlow id="seedNext" sourceRef="seed" targetRef="calculate"/><sequenceFlow id="calculated" sourceRef="calculate" targetRef="decide"/><sequenceFlow id="f2" sourceRef="decide" targetRef="choice"/>
                  <sequenceFlow id="accepted" name="有效金额" sourceRef="choice" targetRef="review"><extensionElements><flowable:properties><flowable:property name="approva.conditionRule.config" value='%s'/></flowable:properties></extensionElements></sequenceFlow>
                  <sequenceFlow id="fallback" name="无需审批" sourceRef="choice" targetRef="end"><extensionElements><flowable:properties><flowable:property name="approva.conditionRule.config" value='{"version":1,"default":true}'/></flowable:properties></extensionElements></sequenceFlow>
                  <sequenceFlow id="f3" sourceRef="review" targetRef="call"/><sequenceFlow id="f4" sourceRef="call" targetRef="end"/>
                </process>
                <process id="secondPool" isExecutable="true"><extensionElements><flowable:properties><flowable:property name="approva.startScope.ruleVersion" value="1"/><flowable:property name="approva.startScope.type" value="PUBLIC"/><flowable:property name="approva.startScope.targetIds" value=""/><flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/></flowable:properties></extensionElements><startEvent id="secondStart"><extensionElements><flowable:formProperty id="memo" type="string" readable="true" writable="true"/><flowable:formProperty id="systemValue" type="string" readable="true" writable="false" systemWritable="true"/></extensionElements></startEvent><receiveTask id="secondWait"/><endEvent id="secondEnd"/><sequenceFlow id="s1" sourceRef="secondStart" targetRef="secondWait"/><sequenceFlow id="s2" sourceRef="secondWait" targetRef="secondEnd"/></process>
                <collaboration id="collaboration"><participant id="pool" processRef="compiledParent"/><participant id="external" processRef="secondPool"/></collaboration>
                <bpmndi:BPMNDiagram id="diagram"><bpmndi:BPMNPlane id="plane" bpmnElement="collaboration"><bpmndi:BPMNShape id="poolShape" bpmnElement="pool"><dc:Bounds x="0" y="0" width="1000" height="300"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="externalShape" bpmnElement="external"><dc:Bounds x="0" y="400" width="1000" height="100"/></bpmndi:BPMNShape></bpmndi:BPMNPlane></bpmndi:BPMNDiagram>
                </definitions>
                """.formatted(properties("startScope", "PUBLIC"), permissions(true), decision, permissions(false), properties("assignment", "STARTER").replace("</flowable:properties>", "<flowable:property name=\"approva.assignment.formField\" value=\"\"/>" + SLA + "</flowable:properties>"), rule);
    }

    private static final String SLA = """
            <flowable:property name="approva.sla.enabled" value="true"/>
            <flowable:property name="approva.sla.calendarKey" value="WORKDAY"/>
            <flowable:property name="approva.sla.reminderMinutes" value="10"/>
            <flowable:property name="approva.sla.reminderRepeatMinutes" value="10"/>
            <flowable:property name="approva.sla.maxReminders" value="1"/>
            <flowable:property name="approva.sla.escalationMinutes" value="30"/>
            <flowable:property name="approva.sla.escalationUserId" value=""/>
            <flowable:property name="approva.sla.escalationEventCode" value="REVIEW_LATE"/>
            """;

    private static final String HEADER = "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\" targetNamespace=\"urn:compile\">";
    private static final String FORM = """
            {"fields":[
              {"layout":"colFormItem","tag":"el-input-number","vModel":"amount","workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
              {"layout":"colFormItem","tag":"el-input-number","vModel":"result","workflowHidden":false,"workflowReadable":true,"workflowWritable":false,"workflowSystemWritable":true},
              {"layout":"colFormItem","tag":"el-input","vModel":"note","workflowHidden":false,"workflowReadable":true,"workflowWritable":true}]}
            """;

    static WorkflowDecisionTableRequest decimalTable()
    {
        return JsonMapper.shared().readValue("""
            {"key":"preciseDecision","name":"精确数值","category":"","hitPolicy":"FIRST",
             "inputs":[{"key":"amount","label":"金额","type":"number"}],
             "outputs":[{"key":"result","label":"结果","type":"number"}],
             "rules":[{"conditions":[{"operator":"EQ","value":"9999999999.12345678"}],"results":["9007199254740993"]}],
             "defaultResult":["-9999999999.12345678"],"testValues":{"amount":"9999999999.12345678"}}
            """, WorkflowDecisionTableRequest.class);
    }

    private void verifyDesignDraftIsolation(DataSource dataSource, WorkflowModelService models,
            WorkflowBpmnService bpmn, WorkflowEngineOperations operations, WorkflowIdentityResolver identities,
            org.flowable.engine.RepositoryService repository, WfFormMapper forms,
            String modelId, int revision, String xml)
    {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("""
                create table wf_model_design_draft (model_id varchar(64), owner_user_id varchar(64),
                    base_model_revision int not null, revision int not null, bpmn_xml clob,
                    updated_at timestamp default current_timestamp, primary key (model_id, owner_user_id))
                """);
        var drafts = new WorkflowModelDesignDraftService(dataSource, models, bpmn, operations, identities);
        clearInvocations(repository, forms);
        assertThat(drafts.read(modelId).revision()).isZero();
        verify(repository, times(1)).getModel(modelId);
        verify(repository, never()).getModelEditorSource(anyString());
        verifyNoInteractions(forms);
        var saved = drafts.save(modelId, new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml));
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(drafts.read(modelId).bpmnXml()).isEqualTo(xml);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("200", Set.of()));
        assertThat(drafts.read(modelId).bpmnXml()).isNull();
        assertThatThrownBy(() -> drafts.discard(modelId, 1)).hasMessageContaining("已被更新");
        drafts.save(modelId, new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml));
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml)))
                .hasMessageContaining("已被更新");
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision + 1, 1, xml)))
                .hasMessageContaining("已被更新");
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 1, xml.replace("compiledParent", "wrongKey"))))
                .hasMessageContaining("当前模型所属流程");
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 1, "<!DOCTYPE x>" + xml)))
                .hasMessageContaining("DTD");
        assertThat(drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 1, xml)).revision()).isEqualTo(2);
        assertThatThrownBy(() -> drafts.discard(modelId, 1)).hasMessageContaining("已被更新");
        assertThat(drafts.discard(modelId, 2).revision()).isEqualTo(3);
        assertThat(drafts.read(modelId).bpmnXml()).isNull();
        assertThatThrownBy(() -> drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 0, xml)))
                .hasMessageContaining("已被更新");
        assertThat(drafts.save(modelId,
                new com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest(revision, 3, xml)).revision()).isEqualTo(4);
        assertThatThrownBy(() -> drafts.read("missing-model")).hasMessageContaining("不存在");
        verify(repository, never()).getModelEditorSource(anyString());
        verifyNoInteractions(forms);
        assertThat(repository.getModelEditorSource(modelId)).isEqualTo(xml.getBytes(StandardCharsets.UTF_8));
        assertThat(models.getModel(modelId).bpmnXml()).isEqualTo(xml);
        verify(forms, times(1)).selectById(7L);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("200", Set.of()));
        assertThat(drafts.read(modelId).revision()).isEqualTo(1);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(EngineAutoConfigurations.class)
    static class Database
    {
        @Bean DataSource dataSource()
        {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:decimal-config-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            return source;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
    }

    // 与 Boot 自动配置一样按类名读取元数据，避免反射不存在的可选 App Engine 类型。
    static class EngineAutoConfigurations implements ImportSelector
    {
        @Override public String[] selectImports(AnnotationMetadata metadata)
        {
            return new String[] { "org.flowable.spring.boot.ProcessEngineAutoConfiguration",
                    "org.flowable.spring.boot.dmn.DmnEngineAutoConfiguration",
                    "org.flowable.spring.boot.ProcessEngineServicesAutoConfiguration",
                    "org.flowable.spring.boot.dmn.DmnEngineServicesAutoConfiguration" };
        }
    }
}
