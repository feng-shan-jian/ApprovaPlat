package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.IOParameter;
import org.flowable.engine.RepositoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.vo.WorkflowCallActivityVariableView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.mapper.WorkflowIdentityMapper;
import com.ruoyi.flowable.runtime.WorkflowParticipantResolutionMetrics;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.process.WorkflowCallActivityStartListener;
import com.ruoyi.flowable.service.process.WorkflowProcessDefinitionQueryService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * R02 与相邻字段权限缺陷：公开目录/校验入口、真实 Flowable 仓库及真实部署制品。
 * 仅当前登录身份和模板表读取为测试目录；不模拟被测服务、制品读取或发起授权规则。
 */
class WorkflowCallActivityBusinessRegressionTest
{
    private static final WorkflowCurrentIdentity ACTOR = new WorkflowCurrentIdentity("100", Set.of());
    private WorkflowFlowableEngineTestSupport support;
    private RepositoryService repository;
    private WorkflowDeploymentArtifactRepository artifacts;
    private WorkflowCallActivityReferenceService service;
    private WfFormMapper templates;
    private WorkflowCallActivityStartListener startListener;
    private final java.util.concurrent.atomic.AtomicReference<org.flowable.engine.delegate.ExecutionListener> outputListener = new java.util.concurrent.atomic.AtomicReference<>();
    private final List<String> deployments = new ArrayList<>();

    @BeforeEach
    void open()
    {
        support = WorkflowFlowableEngineTestSupport.start("call-business-regression", Map.of(
                "workflowSystemOutputListener", (org.flowable.engine.delegate.ExecutionListener) execution -> outputListener.get().notify(execution)));
        repository = support.processEngine().getRepositoryService();
        artifacts = new WorkflowDeploymentArtifactRepository(repository);
        outputListener.set(new com.ruoyi.flowable.listener.WorkflowSystemOutputListener(repository, artifacts,
                new com.ruoyi.flowable.service.process.WorkflowStartVariableValidator(new WorkflowFormTemplateValidator())));
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(ACTOR);
        var operations = support.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(support.processEngine().getIdentityService(),
                        new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identity));
        var participant = new WorkflowParticipantRuleRuntimeService(repository,
                support.processEngine().getRuntimeService(), artifacts, mock(WorkflowIdentityMapper.class),
                identity, mock(WorkflowParticipantResolutionMetrics.class));
        templates = mock(WfFormMapper.class);
        WfForm template = new WfForm();
        template.setFormId(7L);
        template.setContent(form("parentValue"));
        when(templates.selectById(7L)).thenReturn(template);
        service = new WorkflowCallActivityReferenceService(repository, operations, identity,
                participant, artifacts, new WorkflowFormTemplateValidator());
        var providers = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        providers.addBean("definitions", new WorkflowProcessDefinitionQueryService(operations, repository, identity,
                mock(com.ruoyi.flowable.authorization.WorkflowProcessAccessService.class),
                mock(WorkflowDeploymentService.class), artifacts, participant));
        providers.addBean("variables", new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator()));
        startListener = new WorkflowCallActivityStartListener(providers.getBeanProvider(WorkflowProcessDefinitionQueryService.class),
                providers.getBeanProvider(WorkflowStartVariableValidator.class));
    }

    @AfterEach
    void close()
    {
        try
        {
            for (String id : deployments)
            {
                artifacts.delete(id);
                repository.deleteDeployment(id, true);
            }
            assertThat(repository.createDeploymentQuery().count()).isZero();
        }
        finally
        {
            support.close();
        }
    }

    @ParameterizedTest
    @CsvSource({ "EDITABLE,false", "READONLY,false", "EDITABLE,true" })
    void healthyTargetIsVisibleAndLegalMappingPasses(WorkflowFormFieldPermissionMode mode, boolean output)
    {
        deploy(Map.of("child", form("childValue")));
        var options = service.listReferenceOptions("");
        assertThat(options).singleElement().satisfies(option ->
                assertThat(option.inputFields()).extracting(WorkflowCallActivityVariableView::name)
                        .containsExactly("childValue"));
        assertThatCode(() -> validateReferences(
                parent(mode, output), ACTOR))
                .doesNotThrowAnyException();
    }

    @Test
    void mappingCatalogAndLoopCatalogAgreeWithRuntimeValueShapes()
    {
        var mapper = JsonMapper.shared();
        var root = mapper.createObjectNode();
        var fields = root.putArray("fields");
        Map<String, String> scalarTypes = new LinkedHashMap<>();
        Map<String, Object> values = new LinkedHashMap<>();
        List<FieldExample> examples = List.of(
                new FieldExample("text", "el-input", Map.of(), "TEXT", "text"),
                new FieldExample("richText", "tinymce", Map.of(), "TEXT", "text"),
                new FieldExample("color", "el-color-picker", Map.of(), "TEXT", "#112233"),
                new FieldExample("number", "el-input-number", Map.of(), "NUMBER", 3),
                new FieldExample("rating", "el-rate", Map.of(), "NUMBER", 3),
                new FieldExample("slider", "el-slider", Map.of(), "NUMBER", 3),
                new FieldExample("switchValue", "el-switch", Map.of(), "BOOLEAN", true),
                new FieldExample("radio", "el-radio-group", Map.of(), "SCALAR", "a"),
                new FieldExample("select", "el-select", Map.of(), "SCALAR", "a"),
                new FieldExample("time", "el-time-picker", Map.of("is-range", false), "TEXT", "09:00:00"),
                new FieldExample("date", "el-date-picker", Map.of("type", "date"), "TEXT", "2026-09-16"),
                new FieldExample("timeRange", "el-time-picker", Map.of("is-range", true), null, List.of("09:00:00", "10:00:00")),
                new FieldExample("dateRange", "el-date-picker", Map.of("type", "daterange"), null, List.of("2026-09-16", "2026-09-17")),
                new FieldExample("timeTypeRange", "el-time-picker", Map.of("type", "TIMERANGE"), null, List.of("09:00:00", "10:00:00")),
                new FieldExample("dateFlagRange", "el-date-picker", Map.of("is-range", true), null, List.of("2026-09-16", "2026-09-17")),
                new FieldExample("sliderRange", "el-slider", Map.of("range", true), null, List.of(1, 2)),
                new FieldExample("multiSelect", "el-select", Map.of("multiple", true), null, List.of("a", "b")),
                new FieldExample("checkbox", "el-checkbox-group", Map.of(), null, List.of("a")),
                new FieldExample("cascader", "el-cascader", Map.of(), null, List.of("a", "b")),
                new FieldExample("files", "el-upload", Map.of(), null, List.of("123e4567-e89b-42d3-a456-426614174000")),
                new FieldExample("table", "el-table", Map.of(), null, List.of(Map.of("cell", "text"))));
        for (FieldExample example : examples)
        {
            fields.add(mapper.readTree(form(example.name(), example.tag(), example.options())).path("fields").get(0));
            values.put(example.name(), example.value());
            if (example.scalarType() != null) scalarTypes.put(example.name(), example.scalarType());
        }
        String content = root.toString();
        deploy(Map.of("child", content));
        var option = service.listReferenceOptions("child").get(0);
        assertThat(option.status()).isEqualTo("ACTIVE");
        for (var catalog : List.of(option.inputFields(), option.outputFields()))
        {
            Map<String, String> actual = new LinkedHashMap<>();
            catalog.forEach(field -> actual.put(field.name(), field.type()));
            assertThat(actual).containsExactlyInAnyOrderEntriesOf(scalarTypes);
        }
        var variables = new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator());
        assertThat(variables.normalizeCallActivityStartValues(content, values)).containsExactlyInAnyOrderEntriesOf(values);
        Map<String, String> loopTypes = new LinkedHashMap<>();
        variables.describeControlledLoopFields(new WorkflowFormTemplateValidator().validate(content)).forEach((name, field) -> loopTypes.put(name, field.kind().name()));
        assertThat(loopTypes).containsExactlyInAnyOrderEntriesOf(scalarTypes);
        for (FieldExample example : examples)
        {
            if (example.scalarType() != null) continue;
            assertThatThrownBy(() -> variables.normalizeCallActivityStartValues(content, Map.of(example.name(), "scalar")))
                    .as(example.name()).isInstanceOfSatisfying(ServiceException.class,
                            error -> assertThat(error.getCode()).isEqualTo(400));
        }
        assertThat(artifacts.selectForms(deployments.get(0)).get(0).getContent()).isEqualTo(content);
    }

    @ParameterizedTest
    @CsvSource({ "el-time-picker,is-range,true", "el-time-picker,type,timerange",
            "el-date-picker,is-range,true", "el-date-picker,type,daterange",
            "el-slider,range,true", "el-select,multiple,true" })
    void collectionMappingsFailBothSaveAndDeploymentGates(String tag, String flag, String value)
    {
        Map<String, Object> options = Map.of(flag, "true".equals(value) ? true : value);
        deploy(Map.of("child", form("childValue", tag, options), "scalarChild", form("childValue")));
        long originalDeployments = repository.createDeploymentQuery().count();
        for (boolean collectionInParent : List.of(false, true))
        {
            if (collectionInParent)
            {
                WfForm template = new WfForm(); template.setFormId(7L);
                template.setContent(form("parentValue", tag, options));
                when(templates.selectById(7L)).thenReturn(template);
            }
            for (boolean output : List.of(false, true))
            {
                var document = parent(WorkflowFormFieldPermissionMode.EDITABLE, output);
                var call = (CallActivity) document.bpmnModel().getMainProcess().getFlowElement("call");
                if (collectionInParent) call.setCalledElement("scalarChild");
                byte[] bytes = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(document.bpmnModel());
                var executable = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToBpmnModel(
                        () -> new java.io.ByteArrayInputStream(bytes), true, true);
                assertThatThrownBy(() -> validateReferences(document, ACTOR))
                        .isInstanceOfSatisfying(ServiceException.class, error ->
                        {
                            assertThat(error.getCode()).isEqualTo(400);
                            assertThat(error.getSubCode()).isEqualTo("CALL_MAPPING_FIELD_MISSING");
                        });
                assertThatThrownBy(() -> service.prepare(executable, document, ACTOR, parentForms(document)))
                        .isInstanceOfSatisfying(ServiceException.class,
                                error -> assertThat(error.getSubCode()).isEqualTo("CALL_MAPPING_FIELD_MISSING"));
                assertThat(new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(document.bpmnModel())).isEqualTo(bytes);
            }
        }
        assertThat(repository.createDeploymentQuery().count()).isEqualTo(originalDeployments);
        assertThat(repository.createProcessDefinitionQuery().processDefinitionKey("parent").count()).isZero();
    }

    @ParameterizedTest
    @CsvSource({ "el-time-picker,is-range", "el-slider,range", "el-select,multiple" })
    void malformedShapeFlagsRemainSnapshotErrors(String tag, String flag)
    {
        String content = form("childValue", tag, Map.of(flag, "true"));
        var variables = new WorkflowStartVariableValidator(new WorkflowFormTemplateValidator());
        assertThatThrownBy(() -> variables.validateForStart(content, Map.of()))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(500));
        assertThatThrownBy(() -> variables.normalizeCallActivityStartValues(content, Map.of()))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(500));
        deploy(Map.of("child", content));
        assertThat(service.listReferenceOptions("child")).singleElement().satisfies(option ->
        {
            assertThat(option.status()).isEqualTo("UNAVAILABLE");
            assertThat(option.inputFields()).isEmpty();
        });
        assertThatThrownBy(() -> validateReferences(parent(WorkflowFormFieldPermissionMode.EDITABLE, false), ACTOR))
                .isInstanceOfSatisfying(ServiceException.class, error ->
                        assertThat(error.getSubCode()).isEqualTo("CALL_ACTIVITY_CATALOG_DATA_INVALID"));
    }

    @Test
    void nonInputComponentsDoNotDisableAnOtherwiseHealthyCatalog()
    {
        String content = nested(form("childValue"), false);
        var mapper = JsonMapper.shared();
        var root = (ObjectNode) mapper.readTree(content);
        var fields = (tools.jackson.databind.node.ArrayNode) root.path("fields");
        fields.add(mapper.readTree(form("button", "el-button", Map.of())).path("fields").get(0));
        fields.add(mapper.readTree(form("column", "el-table-column", Map.of())).path("fields").get(0));
        deploy(Map.of("child", root.toString()));
        assertThat(service.listReferenceOptions("child")).singleElement().satisfies(option ->
        {
            assertThat(option.status()).isEqualTo("ACTIVE");
            assertThat(option.inputFields()).extracting(WorkflowCallActivityVariableView::name).containsExactly("childValue");
        });
    }

    private record FieldExample(String name, String tag, Map<String, Object> options, String scalarType, Object value) { }

    @ParameterizedTest
    @CsvSource({ "el-time-picker,09:00:00", "el-date-picker,2026-09-16" })
    void scalarTemporalMappingDeploysAndCreatesAValidatedChildStartSnapshot(String tag, String value)
    {
        String parentContent = form("parentValue", tag, Map.of("is-range", false));
        WfForm template = new WfForm(); template.setFormId(7L); template.setContent(parentContent);
        when(templates.selectById(7L)).thenReturn(template);
        deploy(Map.of("child", form("childValue", tag, Map.of("is-range", false))));
        var document = parent(WorkflowFormFieldPermissionMode.EDITABLE, false);
        validateReferences(document, ACTOR);
        byte[] bytes = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(document.bpmnModel());
        var executable = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToBpmnModel(
                () -> new java.io.ByteArrayInputStream(bytes), true, true);
        var prepared = service.prepare(executable, document, ACTOR, parentForms(document));
        String deployment = repository.createDeployment().addBytes("temporal-parent.bpmn20.xml", new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(executable)).deploy().getId();
        deployments.add(deployment);
        WfDeployForm snapshot = new WfDeployForm(); snapshot.setDeployId(deployment);
        snapshot.setNodeKey("parentStart"); snapshot.setNodeName("申请");
        snapshot.setSourceType("TEMPLATE"); snapshot.setFormId(7L); snapshot.setFormKey("key_7");
        snapshot.setFormName("日期时间申请"); snapshot.setContent(parentContent);
        artifacts.persist(deployment, new WorkflowDeploymentArtifacts(List.of(snapshot), List.of(), List.of(), List.of(), List.of(), List.of(),
                service.snapshotsForDeployment(deployment, prepared, "100"), List.of()));

        var runtime = support.processEngine().getRuntimeService();
        runtime.addEventListener(startListener);
        var parent = runtime.startProcessInstanceByKey("parent", Map.of("parentValue", value));
        var child = runtime.createProcessInstanceQuery().superProcessInstanceId(parent.getId()).singleResult();
        assertThat(child.getBusinessStatus()).isEqualTo("running");
        assertThat(runtime.getVariable(child.getId(), "childValue")).isEqualTo(value);
        var submission = WorkflowFormSubmissionSnapshotCodec.decode((String) runtime.getVariable(
                child.getId(), WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME));
        assertThat(submission.values()).containsOnlyKeys("childValue");
        assertThat(submission.values().get("childValue").asText()).isEqualTo(value);
        var wait = runtime.createExecutionQuery().processInstanceId(child.getId()).activityId("child_wait").singleResult();
        runtime.trigger(wait.getId());
        assertThat(runtime.createProcessInstanceQuery().count()).isZero();
    }

    private static String form(String variable, String tag, Map<String, Object> options)
    {
        var mapper = JsonMapper.shared();
        var root = mapper.readTree(form(variable));
        var field = (ObjectNode) root.path("fields").get(0);
        field.put("tag", tag);
        options.forEach((key, value) -> field.set(key, mapper.valueToTree(value)));
        return root.toString();
    }

    @Test
    void oneInvalidSnapshotMustNotHideHealthyAuthorizedTargets()
    {
        deploy(Map.of("child", form("childValue")));
        deploy(Map.of("broken", "{\"fields\":[{\"tag\":\"el-input\",\"vModel\":\"legacy\",\"layout\":\"colFormItem\"}]}"));
        assertThat(service.listReferenceOptions(""))
                .anySatisfy(option -> { assertThat(option.processKey()).isEqualTo("child"); assertThat(option.status()).isEqualTo("ACTIVE"); })
                .anySatisfy(option -> { assertThat(option.processKey()).isEqualTo("broken");
                    assertThat(option.status()).isEqualTo("UNAVAILABLE");
                    assertThat(option.inputFields()).isEmpty(); assertThat(option.outputFields()).isEmpty();
                    assertThat(option.unavailableReason()).contains("legacy"); });
    }

    @Test void unauthorizedCorruptTargetIsAbsentWhileSuspendedAuthorizedTargetHasExplicitStatus()
    {
        deploy(Map.of("child", form("childValue")));
        repository.suspendProcessDefinitionById(repository.createProcessDefinitionQuery().processDefinitionKey("child").singleResult().getId());
        deploy(Map.of("privateChild", "{}"));
        assertThat(service.listReferenceOptions(""))
                .singleElement().satisfies(option -> { assertThat(option.processKey()).isEqualTo("child");
                    assertThat(option.status()).isEqualTo("SUSPENDED"); });
    }

    @Test
    void invalidTargetMustStillFailFinalReferenceValidation()
    {
        deploy(Map.of("broken", "{\"fields\":[{\"tag\":\"el-input\",\"vModel\":\"legacy\",\"layout\":\"colFormItem\"}]}"));
        // Isolated from the catalog assertion so a directory failure cannot hide this safety check.
        var document = parent(WorkflowFormFieldPermissionMode.EDITABLE, false);
        ((CallActivity) document.bpmnModel().getMainProcess().getFlowElement("call")).setCalledElement("broken");
        assertThatThrownBy(() -> validateReferences(document, ACTOR))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void outputCatalogMustExcludeOtherProcessesInSameDeployment()
    {
        deploy(Map.of("child", form("childValue"), "otherPool", form("otherPoolSecret")));
        var option = service.listReferenceOptions("child").get(0);
        assertThat(option.outputFields()).extracting(WorkflowCallActivityVariableView::name)
                .containsExactly("childValue");
    }

    @ParameterizedTest
    @EnumSource(value = WorkflowFormFieldPermissionMode.class, names = { "HIDDEN", "READONLY" })
    void outputMappingMustRespectParentNodeWritePermission(WorkflowFormFieldPermissionMode mode)
    {
        deploy(Map.of("child", form("childValue")));
        assertThatThrownBy(() -> validateReferences(parent(mode, true), ACTOR))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void hiddenParentFieldCannotBeMappedIntoChild()
    {
        deploy(Map.of("child", form("childValue")));
        assertThatThrownBy(() -> validateReferences(
                parent(WorkflowFormFieldPermissionMode.HIDDEN, false), ACTOR))
                .isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest @CsvSource({"8,true,false", "-1,false,false", "8,true,true", "-1,false,true"})
    void explicitSystemOutputUsesFrozenParentSchemaAndRejectsClientForgery(int value, boolean allowed, boolean hiddenContainer)
    {
        String content = form("parentValue").replace("el-input", "el-input-number")
                .replace("\"workflowWritable\":true", "\"workflowWritable\":false,\"workflowSystemWritable\":true,\"min\":0");
        if (hiddenContainer) content = nested(content, true);
        WfForm template = new WfForm(); template.setFormId(7L); template.setContent(content);
        when(templates.selectById(7L)).thenReturn(template);
        deploy(Map.of("child", form("childValue").replace("el-input", "el-input-number")));
        var document = parent(WorkflowFormFieldPermissionMode.READONLY, true);
        CallActivity call = (CallActivity) document.bpmnModel().getMainProcess().getFlowElement("call");
        if (!hiddenContainer)
        {
            var input = new IOParameter(); input.setSource("parentValue"); input.setTarget("childValue"); call.getInParameters().add(input);
        }
        byte[] bytes = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(document.bpmnModel());
        var executable = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToBpmnModel(
                () -> new java.io.ByteArrayInputStream(bytes), true, true);
        var prepared = service.prepare(executable, document, ACTOR, parentForms(document));
        String deployment = repository.createDeployment().addBytes("system-parent.bpmn20.xml", new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(executable)).deploy().getId();
        deployments.add(deployment);
        WfDeployForm snapshot = new WfDeployForm(); snapshot.setDeployId(deployment); snapshot.setNodeKey("parentStart");
        snapshot.setNodeName("系统结果"); snapshot.setSourceType("TEMPLATE"); snapshot.setFormKey("key_7");
        snapshot.setFormName("系统结果"); snapshot.setContent(content);
        artifacts.persist(deployment, new WorkflowDeploymentArtifacts(List.of(snapshot), List.of(), List.of(), List.of(), List.of(), List.of(),
                service.snapshotsForDeployment(deployment, prepared, "100"), List.of()));
        var runtime = support.processEngine().getRuntimeService();
        var parent = runtime.startProcessInstanceByKey("parent", Map.of("parentValue", 7));
        var child = runtime.createProcessInstanceQuery().superProcessInstanceId(parent.getId()).singleResult();
        assertThat(runtime.getVariable(child.getId(), "childValue")).isEqualTo(hiddenContainer ? null : 7);
        runtime.setVariable(child.getId(), "childValue", value);
        var childWait = runtime.createExecutionQuery().processInstanceId(child.getId()).activityId("child_wait").singleResult();
        if (allowed)
        {
            runtime.trigger(childWait.getId());
            assertThat(support.processEngine().getHistoryService().createHistoricVariableInstanceQuery().processInstanceId(parent.getId())
                    .variableName("parentValue").singleResult().getValue()).isEqualTo(8);
            assertThat(runtime.createProcessInstanceQuery().processInstanceId(parent.getId()).count()).isZero();
        }
        else
        {
            assertThatThrownBy(() -> runtime.trigger(childWait.getId())).isInstanceOf(RuntimeException.class);
            assertThat(runtime.getVariable(parent.getId(), "parentValue")).isEqualTo(7);
            assertThat(runtime.createExecutionQuery().executionId(childWait.getId()).count()).isOne();
        }
        var validator = new com.ruoyi.flowable.service.process.WorkflowStartVariableValidator(new WorkflowFormTemplateValidator());
        String frozenContent = content;
        assertThatThrownBy(() -> validator.validateForStart(frozenContent, Map.of("parentValue", 999))).isInstanceOf(ServiceException.class);
        assertThat(validator.readableFieldNames(new WorkflowFormTemplateValidator().validate(content)).contains("parentValue")).isEqualTo(!hiddenContainer);
        // 模板后续改成可写不能放宽当前部署的不可变权限。
        template.setContent(form("parentValue"));
        assertThat(artifacts.selectForms(deployment).get(0).getContent()).isEqualTo(content);
    }

    @Test
    void hiddenAncestorPreventsMappingAUserInputIntoTheChild()
    {
        WfForm template = new WfForm(); template.setFormId(7L); template.setContent(nested(form("parentValue"), true));
        when(templates.selectById(7L)).thenReturn(template);
        deploy(Map.of("child", form("childValue")));
        assertThatThrownBy(() -> validateReferences(parent(WorkflowFormFieldPermissionMode.EDITABLE, false), ACTOR))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getSubCode()).isEqualTo("CALL_MAPPING_SOURCE_UNREADABLE"));
    }

    @Test
    void readonlyChildContainerPreventsMappingUserInputIntoItsFields()
    {
        deploy(Map.of("child", nested(form("childValue"), false)));
        assertThatThrownBy(() -> validateReferences(parent(WorkflowFormFieldPermissionMode.EDITABLE, false), ACTOR))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getSubCode()).isEqualTo("CALL_MAPPING_TARGET_UNWRITABLE"));
    }

    private String nested(String content, boolean hidden)
    {
        var mapper = tools.jackson.databind.json.JsonMapper.shared();
        var root = mapper.createObjectNode();
        var row = root.putArray("fields").addObject();
        row.put("layout", "rowFormItem").put("workflowHidden", hidden)
                .put("workflowReadable", !hidden).put("workflowWritable", false);
        row.set("children", mapper.readTree(content).get("fields"));
        return root.toString();
    }

    private Map<String, tools.jackson.databind.JsonNode> parentForms(WorkflowBpmnDocument document)
    {
        Map<String, tools.jackson.databind.JsonNode> roots = new LinkedHashMap<>();
        var validator = new WorkflowFormTemplateValidator();
        for (var ref : document.formReferences())
            roots.put(ref.nodeKey(), WorkflowNodeFormPermissionSnapshot.apply(
                    validator.validate(templates.selectById(ref.formId()).getContent()), ref));
        return roots;
    }

    private void validateReferences(WorkflowBpmnDocument document, WorkflowCurrentIdentity actor)
    {
        service.validateAuthorReferences(document, actor, parentForms(document));
    }

    private WorkflowBpmnDocument parent(WorkflowFormFieldPermissionMode mode, boolean output)
    {
        BpmnModel model = new BpmnModel();
        var process = new org.flowable.bpmn.model.Process();
        process.setId("parent");
        process.setExecutable(true);
        CallActivity call = new CallActivity();
        call.setId("call");
        call.setCalledElement("child");
        IOParameter parameter = new IOParameter();
        parameter.setSource(output ? "childValue" : "parentValue");
        parameter.setTarget(output ? "parentValue" : "childValue");
        if (output) call.setOutParameters(List.of(parameter));
        else call.setInParameters(List.of(parameter));
        var start = new org.flowable.bpmn.model.StartEvent();
        start.setId("parentStart");
        start.setFormKey("key_7");
        var end = new org.flowable.bpmn.model.EndEvent();
        end.setId("parentEnd");
        process.addFlowElement(start);
        process.addFlowElement(call);
        process.addFlowElement(end);
        var first = new org.flowable.bpmn.model.SequenceFlow("parentStart", "call");
        first.setId("parentToCall");
        var last = new org.flowable.bpmn.model.SequenceFlow("call", "parentEnd");
        last.setId("callToEnd");
        process.addFlowElement(first);
        process.addFlowElement(last);
        model.addProcess(process);
        var reference = new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE,
                7L, "key_7", "parentStart", "申请表单", null, "parent", mode,
                Map.of("parentValue", mode));
        return new WorkflowBpmnDocument(model, "", List.of(reference));
    }

    private void deploy(Map<String, String> forms)
    {
        StringBuilder xml = new StringBuilder("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"urn:test:catalog\">");
        forms.keySet().forEach(key -> xml.append("<process id=\"").append(key)
                .append("\" isExecutable=\"true\"><startEvent id=\"").append(key)
                .append("_start\" flowable:formKey=\"key_7\"/><receiveTask id=\"").append(key).append("_wait\"/><endEvent id=\"").append(key)
                .append("_end\"/><sequenceFlow id=\"").append(key).append("_flow\" sourceRef=\"")
                .append(key).append("_start\" targetRef=\"").append(key).append("_wait\"/><sequenceFlow id=\"").append(key)
                .append("_last\" sourceRef=\"").append(key).append("_wait\" targetRef=\"").append(key).append("_end\"/></process>"));
        String deployment = repository.createDeployment().addString("catalog.bpmn20.xml",
                xml.append("</definitions>").toString()).deploy().getId();
        deployments.add(deployment);
        List<WfDeployForm> snapshots = new ArrayList<>();
        List<WfDeployParticipantRule> rules = new ArrayList<>();
        forms.forEach((key, content) ->
        {
            WfDeployForm snapshot = new WfDeployForm();
            snapshot.setDeployId(deployment);
            snapshot.setNodeKey(key + "_start");
            snapshot.setNodeName("申请");
            snapshot.setSourceType("TEMPLATE");
            snapshot.setFormId(7L);
            snapshot.setFormKey("key_7");
            snapshot.setFormName("申请表单");
            snapshot.setContent(content);
            snapshots.add(snapshot);
            WfDeployParticipantRule rule = new WfDeployParticipantRule();
            rule.setProcessKey(key);
            rule.setRuleScope("START");
            rule.setAssignmentMode("START");
            rule.setRuleType(key.equals("privateChild") ? "USERS" : "PUBLIC");
            rule.setTargetIds(key.equals("privateChild") ? "200" : "");
            rule.setNoMatchPolicy("FAIL");
            rule.setRuleVersion(1);
            rule.setChecksum("catalog-regression");
            rules.add(rule);
        });
        artifacts.persist(deployment, new WorkflowDeploymentArtifacts(snapshots, List.of(),
                List.of(), rules, List.of(), List.of(), List.of(), List.of()));
    }

    private static String form(String variable)
    {
        return "{\"fields\":[{\"tag\":\"el-input\",\"vModel\":\"" + variable
                + "\",\"layout\":\"colFormItem\",\"workflowHidden\":false,"
                + "\"workflowReadable\":true,\"workflowWritable\":true}]}";
    }
}
