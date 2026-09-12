package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

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
                participant, artifacts, templates, new WorkflowFormTemplateValidator());
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
        assertThatCode(() -> service.validateAuthorReferences(
                parent(mode, output), ACTOR))
                .doesNotThrowAnyException();
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
        assertThatThrownBy(() -> service.validateAuthorReferences(document, ACTOR))
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
        assertThatThrownBy(() -> service.validateAuthorReferences(parent(mode, true), ACTOR))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void hiddenParentFieldCannotBeMappedIntoChild()
    {
        deploy(Map.of("child", form("childValue")));
        assertThatThrownBy(() -> service.validateAuthorReferences(
                parent(WorkflowFormFieldPermissionMode.HIDDEN, false), ACTOR))
                .isInstanceOf(ServiceException.class);
    }

    @ParameterizedTest @CsvSource({"8,true", "-1,false"})
    void explicitSystemOutputUsesFrozenParentSchemaAndRejectsClientForgery(int value, boolean allowed)
    {
        String content = form("parentValue").replace("el-input", "el-input-number")
                .replace("\"workflowWritable\":true", "\"workflowWritable\":false,\"workflowSystemWritable\":true,\"min\":0");
        WfForm template = new WfForm(); template.setFormId(7L); template.setContent(content);
        when(templates.selectById(7L)).thenReturn(template);
        deploy(Map.of("child", form("childValue").replace("el-input", "el-input-number")));
        var document = parent(WorkflowFormFieldPermissionMode.READONLY, true);
        CallActivity call = (CallActivity) document.bpmnModel().getMainProcess().getFlowElement("call");
        var input = new IOParameter(); input.setSource("parentValue"); input.setTarget("childValue"); call.getInParameters().add(input);
        byte[] bytes = new org.flowable.bpmn.converter.BpmnXMLConverter().convertToXML(document.bpmnModel());
        var prepared = service.prepare(bytes, document, ACTOR);
        String deployment = repository.createDeployment().addBytes("system-parent.bpmn20.xml", prepared.compiledBpmn()).deploy().getId();
        deployments.add(deployment);
        WfDeployForm snapshot = new WfDeployForm(); snapshot.setDeployId(deployment); snapshot.setNodeKey("parentStart");
        snapshot.setNodeName("系统结果"); snapshot.setSourceType("TEMPLATE"); snapshot.setFormKey("key_7");
        snapshot.setFormName("系统结果"); snapshot.setContent(content);
        artifacts.persist(deployment, new WorkflowDeploymentArtifacts(List.of(snapshot), List.of(), List.of(), List.of(), List.of(), List.of(),
                service.snapshotsForDeployment(deployment, prepared, "100"), List.of()));
        var runtime = support.processEngine().getRuntimeService();
        var parent = runtime.startProcessInstanceByKey("parent", Map.of("parentValue", 7));
        var child = runtime.createProcessInstanceQuery().superProcessInstanceId(parent.getId()).singleResult();
        assertThat(runtime.getVariable(child.getId(), "childValue")).isEqualTo(7);
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
        assertThatThrownBy(() -> validator.validateForStart(content, Map.of("parentValue", 999))).isInstanceOf(ServiceException.class);
        // 模板后续改成可写不能放宽当前部署的不可变权限。
        template.setContent(form("parentValue"));
        assertThat(artifacts.selectForms(deployment).get(0).getContent()).isEqualTo(content);
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
        StringBuilder xml = new StringBuilder("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" targetNamespace=\"urn:test:catalog\">");
        forms.keySet().forEach(key -> xml.append("<process id=\"").append(key)
                .append("\" isExecutable=\"true\"><startEvent id=\"").append(key)
                .append("_start\"/><receiveTask id=\"").append(key).append("_wait\"/><endEvent id=\"").append(key)
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
            snapshot.setSourceType("EMBEDDED");
            snapshot.setFormKey(key + "_form");
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
