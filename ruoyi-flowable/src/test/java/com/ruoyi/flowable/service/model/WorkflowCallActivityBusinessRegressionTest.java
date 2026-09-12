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
    private final List<String> deployments = new ArrayList<>();

    @BeforeEach
    void open()
    {
        support = WorkflowFlowableEngineTestSupport.start("call-business-regression", Map.of());
        repository = support.processEngine().getRepositoryService();
        artifacts = new WorkflowDeploymentArtifactRepository(repository);
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(ACTOR);
        var operations = support.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(support.processEngine().getIdentityService(),
                        new WorkflowIdentityCodec()), new WorkflowExceptionTranslator(), identity));
        var participant = new WorkflowParticipantRuleRuntimeService(repository,
                support.processEngine().getRuntimeService(), artifacts, mock(WorkflowIdentityMapper.class),
                identity, mock(WorkflowParticipantResolutionMetrics.class));
        var templates = mock(WfFormMapper.class);
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
                .append("_start\"/><endEvent id=\"").append(key)
                .append("_end\"/><sequenceFlow id=\"").append(key).append("_flow\" sourceRef=\"")
                .append(key).append("_start\" targetRef=\"").append(key).append("_end\"/></process>"));
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
