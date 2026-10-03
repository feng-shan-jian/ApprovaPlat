package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.mapper.WfFormMapper;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

/** 使用真实作者解析和字段规则；无需流程、DMN、参与者或扩展执行引擎。 */
class WorkflowAuthorFieldCatalogTest
{
    private final WfFormMapper forms = mock(WfFormMapper.class);
    private final WorkflowFormTemplateValidator templates = spy(new WorkflowFormTemplateValidator());
    private final WorkflowAuthorFieldCatalogService catalog = new WorkflowAuthorFieldCatalogService(
            new WorkflowBpmnService(null, new WorkflowFormFieldExtensionService(null), null), forms,
            templates, new WorkflowConditionDeploymentService());

    @Test
    void sharesTemplateReadButAppliesEachNodePermissionBeforeScalarAndCallProjection()
    {
        String content = """
                {"fields":[{"layout":"colFormItem","tag":"el-input-number","vModel":"amount","label":"金额","workflowHidden":false,"workflowReadable":true,"workflowWritable":true,"required":true,"workflowSystemWritable":false},
                {"layout":"colFormItem","tag":"el-switch","vModel":"approved","label":"通过","workflowHidden":false,"workflowReadable":true,"workflowWritable":true}]}
                """;
        form(7, content);
        var result = catalog.catalog(xml(node("startEvent", "start", 7, true)
                + node("userTask", "review", 7, false)).replace("</extensionElements>",
                        "<flowable:formProperty id=\"approva_permission_field_approved\" variable=\"approved\" type=\"string\" readable=\"true\" writable=\"true\"/></extensionElements>"));
        assertThat(result.issues()).isEmpty();
        assertThat(result.nodes()).extracting(value -> value.nodeKey()).containsExactly("start", "review");
        assertThat(result.nodes().get(0).loopFields()).extracting(value -> value.value()).contains("amount", "approved");
        var call = result.processes().get(0).callFields().stream().filter(value -> value.name().equals("amount")).findFirst().orElseThrow();
        assertThat(call.type()).isEqualTo("NUMBER");
        assertThat(call.readable()).isTrue();
        assertThat(call.writable()).isFalse();
        assertThat(call.required()).isTrue();
        verify(forms, times(1)).selectById(7L);
        verify(templates, times(1)).validate(content);
    }

    @Test
    void keepsValidNodesWhenAnUnfinishedNodeHasAnUnavailableTemplate()
    {
        form(7, field("el-input", false));
        var result = catalog.catalog(xml(node("startEvent", "start", 7, true)
                + node("userTask", "broken", 999, true)));
        assertThat(result.nodes()).extracting(value -> value.nodeKey()).containsExactly("start");
        assertThat(result.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.nodeKey()).isEqualTo("broken");
            assertThat(issue.message()).contains("不存在或已停用");
        });
        assertThat(result.processes()).isEmpty();
    }

    @Test
    void reportsCrossNodeTypeConflictsWithoutDiscardingNodeCatalogs()
    {
        form(7, field("el-input", false));
        form(8, field("el-input-number", false));
        var result = catalog.catalog(xml(node("startEvent", "start", 7, true)
                + node("userTask", "review", 8, true)));
        assertThat(result.nodes()).hasSize(2);
        assertThat(result.processes()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> assertThat(issue.message()).contains("类型"));
    }

    @Test
    void mergesMappingPermissionsByIntersectionAndRequiredByUnion()
    {
        var rules = new WorkflowCallActivityFields(templates);
        var merged = new LinkedHashMap<String, WorkflowCallActivityFields.FieldSpec>();
        rules.mergeFields(merged, rules.extractFieldSpecs(templates.validate(field("el-input", true))), "冲突");
        rules.mergeFields(merged, rules.extractFieldSpecs(templates.validate(field("el-input", false)
                .replace("\"workflowReadable\":true", "\"workflowReadable\":false")
                .replace("\"workflowWritable\":true", "\"workflowWritable\":false")
                .replace("\"workflowSystemWritable\":false", "\"workflowSystemWritable\":false"))), "冲突");
        assertThat(rules.toVariableViews(merged)).singleElement().satisfies(value -> {
            assertThat(value.required()).isTrue();
            assertThat(value.readable()).isFalse();
            assertThat(value.writable()).isFalse();
            assertThat(value.systemWritable()).isFalse();
        });
        assertThatThrownBy(() -> rules.mergeFields(merged,
                rules.extractFieldSpecs(templates.validate(field("el-input-number", false))), "冲突"))
                .hasMessageContaining("冲突: amount");
    }

    private void form(long id, String content)
    {
        var form = new WfForm(); form.setFormId(id); form.setFormName("字段表单");
        form.setDelFlag("0"); form.setContent(content); when(forms.selectById(id)).thenReturn(form);
    }

    private String field(String tag, boolean required)
    {
        return "{\"fields\":[{\"layout\":\"colFormItem\",\"tag\":\"" + tag + "\",\"vModel\":\"amount\",\"required\":" + required
                + ",\"workflowHidden\":false,\"workflowReadable\":true,\"workflowWritable\":true,\"workflowSystemWritable\":false}]}";
    }

    private String node(String type, String id, long formId, boolean writable)
    {
        return "<" + type + " id=\"" + id + "\" flowable:formKey=\"key_" + formId + "\"><extensionElements>"
                + "<flowable:formProperty id=\"approva_permission_default\" type=\"string\" readable=\"true\" writable=\"true\"/>"
                + "<flowable:formProperty id=\"approva_permission_field_amount\" variable=\"amount\" type=\"string\" readable=\"true\" writable=\"" + writable + "\" required=\"" + writable + "\"/>"
                + "</extensionElements></" + type + ">";
    }

    private String xml(String nodes)
    {
        return "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"catalog\">"
                + "<process id=\"catalog\" isExecutable=\"true\">" + nodes + "</process></definitions>";
    }
}
