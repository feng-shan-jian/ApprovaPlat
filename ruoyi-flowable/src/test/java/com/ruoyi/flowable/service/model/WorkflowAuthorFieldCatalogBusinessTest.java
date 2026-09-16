package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.domain.vo.WorkflowAuthorFieldCatalogView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.engine.WorkflowExceptionTranslator;
import com.ruoyi.flowable.identity.*;
import com.ruoyi.flowable.mapper.*;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;

/** 作者目录使用真实 BPMN 解析、权限冻结和业务目录，H2 验证读取不写仓储及保存/部署复核。 */
class WorkflowAuthorFieldCatalogBusinessTest
{
    private WorkflowFlowableEngineTestSupport engine;
    private WorkflowModelService service;
    private WfFormMapper forms;
    private WorkflowDeploymentArtifactRepository artifacts;
    private WorkflowBpmnService bpmn;
    private WorkflowExtensionRegistryService registry;

    @BeforeEach
    void start()
    {
        engine = WorkflowFlowableEngineTestSupport.start("author-fields", Map.of());
        var repository = engine.processEngine().getRepositoryService();
        var identities = mock(WorkflowIdentityResolver.class);
        when(identities.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        var operations = engine.transactionalProxy(new WorkflowEngineOperations(
                new WorkflowAuthenticationContext(engine.processEngine().getIdentityService(), new WorkflowIdentityCodec()),
                new WorkflowExceptionTranslator(), identities));
        var categories = mock(WfCategoryMapper.class);
        var category = new WfCategory();
        category.setCode("test"); category.setCategoryName("测试"); category.setDelFlag("0");
        when(categories.selectByCode("test")).thenReturn(category);
        forms = mock(WfFormMapper.class);
        var identityMapper = mock(WorkflowIdentityMapper.class);
        var templates = new WorkflowFormTemplateValidator();
        registry = mock(WorkflowExtensionRegistryService.class);
        var extensions = new WorkflowFormFieldExtensionService(registry);
        bpmn = new WorkflowBpmnService(repository, extensions, null);
        artifacts = new WorkflowDeploymentArtifactRepository(repository);
        service = new WorkflowModelService(operations, repository, bpmn,
                new WorkflowBpmnIdentityValidator(identityMapper), categories, forms, artifacts, templates,
                new WorkflowExtensionDeploymentService(null, null, null, null), new WorkflowControlledLoopDeploymentService(),
                new WorkflowConditionDeploymentService(), new WorkflowParticipantRuleDeploymentService(identityMapper),
                new WorkflowDmnDecisionService(operations, null, artifacts), extensions,
                new WorkflowCallActivityReferenceService(repository, operations, identities, null, artifacts, forms, templates),
                new WorkflowTaskSlaDeploymentService(null, null, null), new WorkflowRuntimeTaskListenerCompiler());
    }

    @AfterEach void close() { engine.close(); }

    @Test
    void ancestorPermissionsNodeOverridesAndSystemOutputsUseFrozenContracts()
    {
        String content = """
                {"fields":[
                  {"layout":"rowFormItem","workflowHidden":true,"workflowReadable":false,"workflowWritable":false,"children":[
                    {"layout":"colFormItem","tag":"el-input","vModel":"hidden","workflowHidden":false,"workflowReadable":true,"workflowWritable":true},
                    {"layout":"colFormItem","tag":"el-input","vModel":"result","workflowHidden":false,"workflowReadable":true,"workflowWritable":false,"workflowSystemWritable":true}]},
                  {"layout":"rowFormItem","workflowHidden":false,"workflowReadable":true,"workflowWritable":false,"children":[
                    {"layout":"colFormItem","tag":"el-input","vModel":"ancestorReadonly","workflowHidden":false,"workflowReadable":true,"workflowWritable":true}]},
                  {"layout":"colFormItem","tag":"el-select","label":"负责人","vModel":"owner","workflowHidden":false,"workflowReadable":true,"workflowWritable":false,
                   "options":[{"value":"100","label":"张三"}]},
                  {"layout":"colFormItem","tag":"el-select","vModel":"decision","workflowHidden":false,"workflowReadable":true,"workflowWritable":true,"workflowEnum":true,
                   "options":[{"value":"PASS","label":"通过"}]}]}
                """;
        form(1, content);
        String permissions = permission("hidden", true, true) + permission("result", true, false)
                + permission("ancestorReadonly", true, true) + permission("owner", true, true) + permission("decision", true, true);
        String xml = xml("main", templateTask("task", 1, permissions));
        var catalog = service.authorFieldCatalog(xml);
        assertThat(catalog.issues()).isEmpty();
        var node = catalog.nodes().get(0);
        assertThat(node.userIdFields()).extracting(WorkflowAuthorFieldCatalogView.Option::value)
                .containsExactly("ancestorReadonly", "decision", "owner");
        assertThat(node.loopFields()).extracting(WorkflowAuthorFieldCatalogView.Scalar::value).containsExactly("decision", "owner");
        var owner = node.loopFields().get(1);
        assertThat(owner.valueRestricted()).isFalse();
        assertThat(owner.values()).containsExactly(new WorkflowAuthorFieldCatalogView.Option("100", "张三"));
        assertThat(node.loopFields().get(0).values()).containsExactly(new WorkflowAuthorFieldCatalogView.Option("PASS", "通过"));
        var result = catalog.processes().get(0).callFields().stream().filter(field -> field.name().equals("result")).findFirst().orElseThrow();
        assertThat(result.readable()).isFalse();
        assertThat(result.systemWritable()).isTrue();
        var hiddenResult = service.authorFieldCatalog(xml.replace(permission("result", true, false), permission("result", false, false)));
        assertThat(hiddenResult.processes().get(0).callFields().stream().filter(field -> field.name().equals("result")).findFirst().orElseThrow().systemWritable()).isFalse();
        assertThat(forms.selectById(1L).getContent()).isEqualTo(content);
        assertThat(engine.processEngine().getRepositoryService().createDeploymentQuery().count()).isZero();
        assertThat(engine.processEngine().getRepositoryService().createModelQuery().count()).isZero();
    }

    @Test
    void incompleteNodeFailsClosedWithoutBlockingHealthyNodeOrAnotherPool()
    {
        form(1, simpleField(false));
        String main = templateTask("healthy", 1, permission("owner", true, true))
                + "<userTask id=\"broken\" flowable:formKey=\"key_1\"/>";
        String xml = xml("main", main).replace("</definitions>",
                "<process id=\"other\" isExecutable=\"true\">" + embeddedTask("foreign", "owner", "string", true, true) + "</process></definitions>");
        var catalog = service.authorFieldCatalog(xml);
        assertThat(catalog.nodes()).extracting(WorkflowAuthorFieldCatalogView.Node::nodeKey).containsExactly("healthy", "foreign");
        assertThat(catalog.processes()).extracting(WorkflowAuthorFieldCatalogView.Process::processKey).containsExactly("other");
        assertThat(catalog.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.processKey()).isEqualTo("main"); assertThat(issue.nodeKey()).isEqualTo("broken");
        });
        assertThatThrownBy(() -> bpmn.validateForSave(xml.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ServiceException.class);
        String fixed = xml.replace("<userTask id=\"broken\" flowable:formKey=\"key_1\"/>",
                templateTask("broken", 1, permission("owner", true, true)));
        clearInvocations(forms);
        assertThat(service.authorFieldCatalog(fixed).issues()).isEmpty();
        verify(forms, times(1)).selectById(1L);
    }

    @Test
    void processCatalogsRespectHiddenDeclarationsConflictsAndEmbeddedScopes()
    {
        String xml = xml("main", embeddedTask("visible", "owner", "string", true, true)
                + embeddedTask("hidden", "owner", "string", false, false)
                + "<subProcess id=\"sub\"><startEvent id=\"nestedStart\"><extensionElements>"
                + "<flowable:formProperty id=\"notApplication\" type=\"string\"/></extensionElements></startEvent>"
                + embeddedTask("nestedTask", "nested", "enum", true, true) + "</subProcess>");
        var catalog = service.authorFieldCatalog(xml);
        assertThat(catalog.issues()).isEmpty();
        assertThat(catalog.nodes()).extracting(WorkflowAuthorFieldCatalogView.Node::nodeKey).doesNotContain("nestedStart");
        assertThat(catalog.processes().get(0).userIdFields()).extracting(WorkflowAuthorFieldCatalogView.Option::value).containsExactly("nested");
        assertThat(catalog.processes().get(0).callFields().stream().filter(field -> field.name().equals("owner")).findFirst().orElseThrow().readable()).isFalse();
        var conflict = service.authorFieldCatalog(xml("main", embeddedTask("text", "owner", "string", true, true)
                + embeddedTask("number", "owner", "long", true, true)));
        assertThat(conflict.nodes()).hasSize(2);
        assertThat(conflict.processes()).isEmpty();
        assertThat(conflict.issues()).hasSize(1);
    }

    @Test
    void unsafeXmlAndDuplicateIdsHaveStableBadRequestResponses()
    {
        for (String xml : List.of("<definitions", "<!DOCTYPE definitions [<!ENTITY x SYSTEM 'file:///no'>]><definitions/>",
                xml("main", "<userTask id=\"same\"/><userTask id=\"same\"/>"), "x".repeat(2 * 1024 * 1024 + 1)))
            assertThatThrownBy(() -> service.authorFieldCatalog(xml)).isInstanceOfSatisfying(ServiceException.class,
                    error -> assertThat(error.getCode()).isEqualTo(400));
    }

    @Test
    void embeddedCustomFieldsUseInstalledAuthorCatalogWithoutDeploymentLocks()
    {
        when(registry.requireLatest("employee", "FORM_FIELD")).thenReturn(
                new com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView(1L, "employee", "员工", "FORM_FIELD",
                        11L, 1, "FORM_FIELD_TEXTAREA_V1", "{}", "a".repeat(64)));
        String xml = xml("main", embeddedTask("custom", "owner", "custom:employee", true, true)
                + embeddedTask("enum", "decision", "enum", true, true));
        var catalog = service.authorFieldCatalog(xml);
        assertThat(catalog.issues()).isEmpty();
        assertThat(catalog.nodes().get(0).userIdFields()).extracting(WorkflowAuthorFieldCatalogView.Option::value).containsExactly("owner");
        assertThat(catalog.nodes().get(0).loopFields()).extracting(WorkflowAuthorFieldCatalogView.Scalar::type).containsExactly("TEXT");
        assertThat(catalog.nodes().get(1).loopFields().get(0).values()).containsExactly(new WorkflowAuthorFieldCatalogView.Option("100", "张三"));
        verify(registry, never()).lockLatestForDeployment(anyString());
        when(registry.requireLatest("employee", "FORM_FIELD")).thenThrow(new ServiceException("扩展已停用", 409));
        var unavailable = service.authorFieldCatalog(xml);
        assertThat(unavailable.nodes()).extracting(WorkflowAuthorFieldCatalogView.Node::nodeKey).containsExactly("enum");
        assertThat(unavailable.processes()).isEmpty();
        assertThat(unavailable.issues()).singleElement().satisfies(issue -> assertThat(issue.nodeKey()).isEqualTo("custom"));
    }

    @Test
    void saveAndDeployRecheckCurrentTemplateRatherThanTrustingEarlierCatalog()
    {
        form(1, simpleField(false));
        String xml = xml("main", """
                <extensionElements><flowable:properties>
                  <flowable:property name="approva.startScope.ruleVersion" value="1"/>
                  <flowable:property name="approva.startScope.type" value="PUBLIC"/>
                  <flowable:property name="approva.startScope.targetIds" value=""/>
                  <flowable:property name="approva.startScope.noMatchPolicy" value="FAIL"/>
                </flowable:properties></extensionElements>
                <startEvent id="start"><extensionElements><flowable:formProperty id="reason" type="string"/></extensionElements></startEvent>
                %s<endEvent id="end"/>
                <sequenceFlow id="a" sourceRef="start" targetRef="task"/><sequenceFlow id="b" sourceRef="task" targetRef="end"/>
                """.formatted(templateTask("task", 1, permission("owner", true, true)).replace("</extensionElements>", """
                <flowable:properties>
                  <flowable:property name="approva.assignment.ruleVersion" value="1"/>
                  <flowable:property name="approva.assignment.type" value="FORM_USER"/>
                  <flowable:property name="approva.assignment.targetIds" value=""/>
                  <flowable:property name="approva.assignment.formField" value="owner"/>
                  <flowable:property name="approva.assignment.noMatchPolicy" value="FAIL"/>
                </flowable:properties></extensionElements>
                """)));
        assertThat(service.authorFieldCatalog(xml).nodes().stream().filter(node -> node.nodeKey().equals("task")).findFirst().orElseThrow().userIdFields()).hasSize(1);
        var create = new WorkflowModelDto(); create.setModelKey("main"); create.setModelName("测试"); create.setCategory("test");
        String modelId = service.createModel(create);
        var save = new WorkflowModelDto(); save.setModelId(modelId); save.setBpmnXml(xml); save.setExpectedRevision(service.getModel(modelId).revision());
        service.saveModel(save);
        form(1, simpleField(true));
        assertThat(service.authorFieldCatalog(xml).nodes().stream().filter(node -> node.nodeKey().equals("task")).findFirst().orElseThrow().userIdFields()).isEmpty();
        assertThat(service.validateBpmn(xml).valid()).isFalse();
        save.setExpectedRevision(service.getModel(modelId).revision());
        save.setBpmnXml(xml.replace("id=\"task\" flowable", "id=\"task\" name=\"修改名称\" flowable"));
        assertThatThrownBy(() -> service.saveModel(save)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.deployModel(modelId)).isInstanceOf(ServiceException.class);
        assertThat(engine.processEngine().getRepositoryService().createDeploymentQuery().count()).isZero();
        form(1, simpleField(false));
        String deployment = service.deployModel(modelId);
        assertThat(artifacts.selectForms(deployment)).anySatisfy(snapshot -> {
            assertThat(snapshot.getNodeKey()).isEqualTo("task");
            assertThat(new WorkflowFormTemplateValidator().extractUserIdSourceFieldSignatures(
                    new WorkflowFormTemplateValidator().validate(snapshot.getContent()))).containsKey("owner");
        });
    }

    private void form(long id, String content)
    {
        var form = new WfForm(); form.setFormId(id); form.setFormName("表单"); form.setDelFlag("0"); form.setContent(content);
        when(forms.selectById(id)).thenReturn(form);
    }

    private String simpleField(boolean hiddenParent)
    {
        return "{\"fields\":[{\"layout\":\"rowFormItem\",\"workflowHidden\":" + hiddenParent
                + ",\"workflowReadable\":" + !hiddenParent + ",\"workflowWritable\":" + !hiddenParent
                + ",\"children\":[{\"layout\":\"colFormItem\",\"tag\":\"el-input\",\"vModel\":\"owner\","
                + "\"workflowHidden\":false,\"workflowReadable\":true,\"workflowWritable\":false}]}]}";
    }

    private String xml(String process, String elements)
    {
        return "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"urn:catalog\">"
                + "<process id=\"" + process + "\" isExecutable=\"true\">" + elements + "</process></definitions>";
    }

    private String permission(String field, boolean readable, boolean writable)
    {
        return "<flowable:formProperty id=\"approva_permission_field_" + field + "\" variable=\"" + field
                + "\" type=\"string\" readable=\"" + readable + "\" writable=\"" + writable + "\"/>";
    }

    private String templateTask(String id, long form, String permissions)
    {
        return "<userTask id=\"" + id + "\" flowable:formKey=\"key_" + form + "\"><extensionElements>"
                + "<flowable:formProperty id=\"approva_permission_default\" type=\"string\" readable=\"true\" writable=\"true\"/>"
                + permissions + "</extensionElements></userTask>";
    }

    private String embeddedTask(String id, String field, String type, boolean readable, boolean writable)
    {
        return "<userTask id=\"" + id + "\"><extensionElements><flowable:formProperty id=\"" + field + "\" type=\"" + type
                + "\" readable=\"" + readable + "\" writable=\"" + writable + "\">"
                + (type.equals("enum") ? "<flowable:value id=\"100\" name=\"张三\"/>" : "")
                + "</flowable:formProperty></extensionElements></userTask>";
    }
}
