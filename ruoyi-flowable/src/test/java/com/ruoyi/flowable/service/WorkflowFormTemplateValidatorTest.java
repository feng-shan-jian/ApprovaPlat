package com.ruoyi.flowable.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;

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
        assertThatCode(() -> validator.validate(content)).doesNotThrowAnyException();
        assertThat(validator.extractVariableNames(content)).containsExactly("files");
        assertThat(validator.extractReadableVariableNames(content)).containsExactly("files");
        assertThat(validator.extractAttachmentVariableNames(content)).containsExactly("files");
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
