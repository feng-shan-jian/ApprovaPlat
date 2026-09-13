package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.model.WorkflowBpmnFormReference;
import com.ruoyi.flowable.service.model.WorkflowFormFieldPermissionMode;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;
import com.ruoyi.flowable.service.model.WorkflowNodeFormPermissionSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.StringNode;

class WorkflowNestedFormPermissionTest
{
    private final WorkflowFormTemplateValidator templates = new WorkflowFormTemplateValidator();
    private final WorkflowStartVariableValidator variables = new WorkflowStartVariableValidator(templates);

    @Test
    void hiddenAncestorsExcludeRequiredValuesAndRejectForgedStartDraftAndTaskWrites()
    {
        String content = form(row(true, false, false, row(false, true, true, field("secret", true, true, ""))));
        JsonNode root = templates.validate(content);
        String original = root.toString();
        assertThat(variables.validateForStart(content, Map.of()).variables()).isEmpty();
        assertThat(variables.validatePatch(content, Map.of(), Map.of()).variables()).isEmpty();
        assertThat(templates.extractReadableVariableNames(root)).isEmpty();
        assertThat(templates.extractUserIdSourceFieldSignatures(root)).isEmpty();
        assertThat(templates.extractVariableNames(root)).containsExactly("secret");
        assertThatThrownBy(() -> variables.validateForStart(content, Map.of("secret", "forged")))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> variables.validateForDraft(content, Map.of("secret", "forged")))
                .hasMessageContaining("只读");
        assertThatThrownBy(() -> variables.validatePatch(content, Map.of("secret", "forged"), Map.of()))
                .hasMessageContaining("只读");
        assertThat(root.toString()).isEqualTo(original);
        assertThat(variables.describeControlledLoopFields(content)).isEmpty();
    }

    @Test
    void readonlyAncestorsRetainReadAccessWithoutRequiringOrAcceptingUserInput()
    {
        String content = form(row(false, true, false, row(false, true, true, field("locked", true, true, ""))));
        assertThat(variables.validateForStart(content, Map.of()).variables()).isEmpty();
        assertThat(variables.readableFieldNames(content)).containsExactly("locked");
        Map<String, JsonNode> previous = Map.of("locked", StringNode.valueOf("previous"));
        assertThat(variables.validatePatch(content, Map.of(), previous).variables()).isEmpty();
        assertThat(previous.get("locked").stringValue()).isEqualTo("previous");
        assertThatThrownBy(() -> variables.validatePatch(content, Map.of("locked", "forged"), Map.of()))
                .hasMessageContaining("只读");
    }

    @Test
    void invisibleSystemResultsRetainServerWriteEligibilityAndTypeChecks()
    {
        String content = form(row(true, false, false, field("result", false, false, ",\"workflowSystemWritable\":true")));
        assertThat(variables.systemOutputFieldNames(content)).containsExactly("result");
        variables.validateSystemOutputPatch(content, Map.of("result", "server result"));
        assertThatThrownBy(() -> variables.validateSystemOutputPatch(content, Map.of("result", 42)))
                .isInstanceOf(ServiceException.class);
        assertThat(variables.readableFieldNames(content)).isEmpty();
        assertThatThrownBy(() -> variables.validateForStart(content, Map.of("result", "forged")))
                .hasMessageContaining("只读");
    }

    @Test
    void nodeOverridesCannotBroadenContainerPermissionsOrRewriteTheStoredTemplate()
    {
        String content = form(row(false, true, false, field("locked", false, true, "")));
        WorkflowBpmnFormReference reference = new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE,
                1L, "form:1", "task", "Task", null, "nested", WorkflowFormFieldPermissionMode.REQUIRED,
                Map.of("locked", WorkflowFormFieldPermissionMode.REQUIRED));
        String frozen = WorkflowNodeFormPermissionSnapshot.apply(content, reference, templates);
        assertThat(variables.validateForStart(frozen, Map.of()).variables()).isEmpty();
        assertThat(variables.readableFieldNames(frozen)).containsExactly("locked");
        assertThat(templates.validate(frozen).path("fields").get(0).path("workflowWritable").booleanValue()).isFalse();
        assertThat(templates.validate(content).path("fields").get(0).path("children").get(0).path("required").booleanValue()).isFalse();
    }

    @Test
    void ancestorRestrictionsDoNotRepairInvalidLocalMetadata()
    {
        String invalidSystem = field("bad", false, true, ",\"workflowSystemWritable\":true");
        String content = form(row(true, false, false, invalidSystem));
        assertThatThrownBy(() -> templates.validate(content)).hasMessageContaining("系统结果字段必须可读且用户只读");
        assertThatThrownBy(() -> variables.validateForStart(content, Map.of()))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(500));
        assertThatThrownBy(() -> templates.validate(form(row(true, false, false,
                field("bad", false, true, "").replace("\"workflowWritable\":true,", "")))))
                .hasMessageContaining("完整节点权限");
    }

    private String form(String fields) { return "{\"fields\":[" + fields + "]}"; }

    private String row(boolean hidden, boolean readable, boolean writable, String children)
    {
        return "{\"layout\":\"rowFormItem\",\"workflowHidden\":" + hidden + ",\"workflowReadable\":" + readable
                + ",\"workflowWritable\":" + writable + ",\"children\":[" + children + "]}";
    }

    private String field(String name, boolean required, boolean writable, String extra)
    {
        return "{\"layout\":\"colFormItem\",\"tag\":\"el-input\",\"vModel\":\"" + name
                + "\",\"workflowHidden\":false,\"workflowReadable\":true,\"workflowWritable\":" + writable
                + ",\"required\":" + required + extra + "}";
    }
}
