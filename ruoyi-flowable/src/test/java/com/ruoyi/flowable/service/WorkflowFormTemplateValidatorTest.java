package com.ruoyi.flowable.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.databind.JsonNode;

/**
 * 唯一正式平面表单 Schema 契约测试。
 */
class WorkflowFormTemplateValidatorTest
{
    private final WorkflowFormTemplateValidator validator =
            new WorkflowFormTemplateValidator();

    /**
     * 验证平面 tag、vModel、layout 和权限字段可以通过并正确投影。
     *
     * @return void，正式表单被拒绝或字段投影漂移时测试失败
     */
    @Test
    void acceptsFormalFlatSchema()
    {
        String content = """
                {"fields":[{"tag":"el-upload","vModel":"files",
                "layout":"colFormItem","workflowHidden":false,
                "workflowReadable":true,"workflowWritable":true}]}
                """;
        JsonNode root = validator.validate(content);
        assertThat(validator.extractVariableNames(root)).containsExactly("files");
        assertThat(validator.extractReadableVariableNames(root)).containsExactly("files");
        assertThat(validator.extractAttachmentVariableNames(root)).containsExactly("files");
    }

    @Test
    void projectsValidatedRootWithoutReparsingOrMutation()
    {
        String fields = String.join(",",
                field("first", "el-input", false, true, ""),
                "{\"layout\":\"rowFormItem\",\"workflowHidden\":false,\"workflowReadable\":true,"
                        + "\"workflowWritable\":true,\"children\":["
                        + field("files", "el-upload", false, true, "") + ","
                        + field("first", "el-input", false, true, "") + "]}",
                field("hidden", "el-input", true, false, ""),
                field("unreadable", "el-input", false, false, ""),
                field("mixed", "el-input", false, true, ""),
                field("mixed", "el-input-number", false, true, ""),
                field("multi", "el-select", false, true, ",\"multiple\":true"),
                field("number", "el-input-number", false, true, ""),
                field("choice", "el-select", false, true, ""),
                field("hidden", "el-input", false, true, ""),
                field("choice", "el-select", false, true, ""));
        String content = "{\"fields\":[" + fields + "]}";
        JsonNode root = validator.validate(content);
        String original = root.toString();
        Set<String> all = validator.extractVariableNames(root);
        Set<String> readable = validator.extractReadableVariableNames(root);
        Set<String> attachments = validator.extractAttachmentVariableNames(root);
        Map<String, String> signatures = validator.extractUserIdSourceFieldSignatures(root);

        assertThat(all).containsExactly("first", "files", "hidden", "unreadable", "mixed", "multi", "number", "choice");
        assertThat(readable).containsExactly("first", "files", "mixed", "multi", "number", "choice", "hidden");
        assertThat(attachments).containsExactly("files");
        assertThat(signatures.keySet()).containsExactly("first", "number", "choice");
        assertThat(signatures).containsEntry("first", "el-input")
                .containsEntry("number", "el-input-number").containsEntry("choice", "el-select");
        for (Set<String> projection : List.of(all, readable, attachments))
        {
            assertThatThrownBy(() -> projection.add("extra")).isInstanceOf(UnsupportedOperationException.class);
        }
        assertThatThrownBy(() -> signatures.put("extra", "el-input"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(root.toString()).isEqualTo(original);
    }

    /**
     * 验证唯一解析入口保留语法、重复键、尾随内容、资源、白名单和危险内容门禁。
     * @return void，任一非法正文未以稳定模板 400 拒绝时测试失败
     */
    @Test
    void rejectsInvalidSyntaxResourcesAndDangerousContent()
    {
        String component = field("value", "el-input", false, true, "");
        String nested = component;
        for (int depth = 0; depth < WorkflowFormTemplateValidator.MAX_COMPONENT_DEPTH; depth++)
        {
            nested = field("row", "el-input", false, true, ",\"children\":[" + nested + "]");
        }
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("{", "表单内容必须是合法且无重复键的 JSON 对象"),
                Map.entry("{\"fields\":[],\"fields\":[]}", "表单内容必须是合法且无重复键的 JSON 对象"),
                Map.entry("{\"fields\":[]} {}", "表单内容必须是合法且无重复键的 JSON 对象"),
                Map.entry("{\"fields\":[],\"x\":\"" + "a".repeat(WorkflowFormTemplateValidator.MAX_CONTENT_BYTES) + "\"}",
                        "表单内容不能超过1 MiB"),
                Map.entry("{\"fields\":[],\"x\":" + "[".repeat(101) + "0" + "]".repeat(101) + "}",
                        "表单内容必须是合法且无重复键的 JSON 对象"),
                Map.entry("{\"fields\":[" + nested + "]}", "表单模板组件嵌套深度不能超过20"),
                Map.entry("{\"fields\":[" + String.join(",", java.util.Collections.nCopies(501, component)) + "]}",
                        "表单模板组件数量不能超过500"),
                Map.entry("{\"fields\":[],\"x\":\"javascript:alert(1)\"}", "表单模板包含 javascript: 或 data: 危险协议"),
                Map.entry("{\"fields\":[],\"x\":{\"constructor\":{}}}", "表单模板包含原型污染键"),
                Map.entry("{\"fields\":[" + component.replace("el-input", "arbitrary-widget") + "]}",
                        "不支持的表单组件: arbitrary-widget"));
        invalid.forEach((content, message) -> assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).isEqualTo(message);
                }));
    }

    /**
     * 生成带显式节点权限的平面字段，供真实模板投影和拒绝用例使用。
     * @param name String，变量名
     * @param tag String，组件类型
     * @param hidden boolean，隐藏标志
     * @param readable boolean，可读标志
     * @param extra String，附加 JSON 属性
     * @return String，完整字段 JSON
     */
    private String field(String name, String tag, boolean hidden, boolean readable, String extra)
    {
        return "{\"layout\":\"colFormItem\",\"tag\":\"" + tag + "\",\"vModel\":\"" + name
                + "\",\"workflowHidden\":" + hidden + ",\"workflowReadable\":" + readable
                + ",\"workflowWritable\":false" + extra + "}";
    }

    /**
     * 验证 __config__ 和 __vModel__ 旧生成器正文不能再作为正式协议进入后端。
     *
     * @return void，旧嵌套字段被兼容解析时测试失败
     */
    @Test
    void rejectsNestedGeneratorSchema()
    {
        String content = """
                {"fields":[{"__config__":{"tag":"el-input","layout":"colFormItem"},
                "__vModel__":"title"}]}
                """;
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("已废止");
                });
    }

    /**
     * 验证旧内部字段不能混入表面合法的平面组件形成隐性双协议。
     *
     * @return void，平面字段旁携带旧生成器对象仍被接受时测试失败
     */
    @Test
    void rejectsDeprecatedFieldsInsideOtherwiseFormalComponent()
    {
        String content = """
                {"fields":[{"tag":"el-input","vModel":"title",
                "layout":"colFormItem","workflowHidden":false,
                "workflowReadable":true,"workflowWritable":true,
                "__config__":{"tag":"el-select"}}]}
                """;
        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("已废止");
                });
    }

    /**
     * 验证任一节点权限标志缺失时基础保存校验直接拒绝，不能把错误模板留到渲染阶段。
     *
     * @return void，缺失标志仍可保存时测试失败
     */
    @Test
    void rejectsMissingWorkflowPermissionFlags()
    {
        String content = """
                {"fields":[{"tag":"el-input","vModel":"title",
                "layout":"colFormItem"}]}
                """;

        assertThatThrownBy(() -> validator.validate(content))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("完整节点权限标志");
                });
    }
}
