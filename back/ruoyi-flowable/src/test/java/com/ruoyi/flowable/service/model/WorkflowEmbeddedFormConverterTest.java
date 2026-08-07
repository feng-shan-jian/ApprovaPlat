package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.flowable.bpmn.model.FormProperty;
import org.flowable.bpmn.model.FormValue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.extension.WorkflowFormFieldExtension;

class WorkflowEmbeddedFormConverterTest
{
    /**
     * 验证五类受支持 FormProperty 可转换为当前渲染协议并执行真实变量类型和 enum 白名单。
     * @return void，转换、模板校验或变量校验失败时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void convertsSupportedPropertiesToExecutableRendererSchema() throws Exception
    {
        FormProperty text = property("reason", "原因", "string", true);
        FormProperty amount = property("amount", "金额", "long", true);
        FormProperty count = property("count", "数量", "integer", true);
        FormProperty approved = property("approved", "同意", "boolean", false);
        FormProperty date = property("applyDate", "申请日期", "date", true);
        date.setDatePattern("yyyy-MM-dd");
        FormProperty level = property("level", "等级", "enum", true);
        level.setFormValues(List.of(value("L1", "一级"), value("L2", "二级")));

        String content = WorkflowEmbeddedFormConverter.convert(
                List.of(text, amount, count, approved, date, level));

        WorkflowFormTemplateValidator templateValidator = new WorkflowFormTemplateValidator();
        templateValidator.validate(content);
        JsonNode root = JsonMapper.shared().readTree(content);
        assertThat(root.path("fields")).hasSize(6);
        assertThat(root.path("fields").get(1).path("__config__")
                .path("workflowNumberType").textValue()).isEqualTo("long");
        assertThat(root.path("fields").get(2).path("__config__")
                .path("workflowNumberType").textValue()).isEqualTo("integer");
        assertThat(root.path("fields").get(5).path("__slot__").path("options")).hasSize(2);

        WorkflowStartVariableValidator variableValidator =
                new WorkflowStartVariableValidator(templateValidator);
        assertThat(variableValidator.validateAndNormalize(content,
                java.util.Map.of("reason", "出差", "amount", 1200L, "count", 2,
                        "approved", true, "applyDate", "2026-08-04", "level", "L2")))
                .containsEntry("level", "L2");
        assertThatThrownBy(() -> variableValidator.validateAndNormalize(content,
                java.util.Map.of("reason", "出差", "amount", 1200L, "count", 2,
                        "applyDate", "2026-08-04", "level", "L3")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> variableValidator.validateAndNormalize(content,
                java.util.Map.of("reason", "出差", "amount", 1200.5,
                        "count", 2, "applyDate", "2026-08-04", "level", "L2")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("必须为整数: amount");
                });
        assertThatThrownBy(() -> variableValidator.validateAndNormalize(content,
                java.util.Map.of("reason", "出差", "amount", 1200L,
                        "count", 2147483648L, "applyDate", "2026-08-04", "level", "L2")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(400);
                    assertThat(exception.getMessage()).contains("整数范围不合法: count");
                });
    }

    /**
     * 验证只读和不可读标志进入正式 schema，客户端不能覆盖只读字段且详情字段集排除不可读值。
     * @return void，访问约束未落入正式协议时测试失败
     */
    @Test
    void preservesReadAndWriteRestrictions()
    {
        FormProperty readOnly = property("serverCode", "服务端编码", "string", false);
        readOnly.setWriteable(false);
        FormProperty writeOnly = property("secretNote", "私密说明", "string", false);
        writeOnly.setReadable(false);

        String content = WorkflowEmbeddedFormConverter.convert(List.of(readOnly, writeOnly));
        WorkflowFormTemplateValidator templateValidator = new WorkflowFormTemplateValidator();
        assertThat(templateValidator.extractVariableNames(content))
                .containsExactly("serverCode", "secretNote");
        assertThat(templateValidator.extractReadableVariableNames(content))
                .containsExactly("serverCode");

        WorkflowStartVariableValidator variableValidator =
                new WorkflowStartVariableValidator(templateValidator);
        assertThatThrownBy(() -> variableValidator.validateAndNormalize(content,
                java.util.Map.of("serverCode", "forged")))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getMessage()).contains("只读"));
        assertThat(variableValidator.validateAndNormalize(content,
                java.util.Map.of("secretNote", "仅审批人可用")))
                .containsEntry("secretNote", "仅审批人可用");
    }

    /**
     * 验证 custom: 字段只能通过正式解析器映射为固定多行文本组件并携带精确版本元数据。
     * @return void，出现任意组件注入或版本元数据缺失时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void convertsControlledCustomFieldWithFrozenMetadata() throws Exception
    {
        FormProperty field = property("detail", "详细说明",
                "custom:approva.form.textarea", true);
        String schema = WorkflowFormFieldExtension.configSchema();
        String checksum = com.ruoyi.flowable.extension.WorkflowExtensionChecksum.sha256(
                "approva.form.textarea", "FORM_FIELD", "2",
                WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY, schema);
        WorkflowExtensionOptionView option = new WorkflowExtensionOptionView(
                18L, "approva.form.textarea", "多行文本", "FORM_FIELD", 28L, 2,
                WorkflowFormFieldExtension.TEXTAREA_IMPLEMENTATION_KEY, schema, checksum);

        String content = WorkflowEmbeddedFormConverter.convert(List.of(field), key ->
        {
            assertThat(key).isEqualTo("approva.form.textarea");
            return option;
        });

        new WorkflowFormTemplateValidator().validate(content);
        JsonNode component = JsonMapper.shared().readTree(content).path("fields").get(0);
        assertThat(component.path("__config__").path("tag").textValue()).isEqualTo("el-input");
        assertThat(component.path("type").textValue()).isEqualTo("textarea");
        assertThat(component.path("__config__")
                .path(WorkflowFormFieldExtension.VERSION_FIELD).intValue()).isEqualTo(2);
        assertThat(component.path("__config__")
                .path(WorkflowFormFieldExtension.CHECKSUM_FIELD).textValue()).isEqualTo(checksum);
        assertThat(content).doesNotContain("componentName", "template", "script");
    }

    /**
     * 验证表达式、重复或保留变量、未知类型和重复 enum 值均在部署前被拒绝。
     * @return void，任一危险 FormData 通过转换门禁时测试失败
     */
    @Test
    void rejectsExpressionsReservedVariablesUnsupportedTypesAndDuplicateEnums()
    {
        FormProperty expression = property("reason", "原因", "string", false);
        expression.setDefaultExpression("${runtimeService}");
        FormProperty reserved = property("processStatus", "状态", "string", false);
        FormProperty unknown = property("payload", "对象", "json", false);
        FormProperty duplicateEnum = property("level", "等级", "enum", false);
        duplicateEnum.setFormValues(List.of(value("L1", "一级"), value("L1", "重复")));

        for (List<FormProperty> invalid : List.of(
                List.of(expression), List.of(reserved), List.of(unknown),
                List.of(duplicateEnum),
                List.of(property("same", "一", "string", false),
                        property("same", "二", "string", false))))
        {
            assertThatThrownBy(() -> WorkflowEmbeddedFormConverter.convert(invalid))
                    .isInstanceOfSatisfying(ServiceException.class, exception ->
                            assertThat(exception.getCode()).isEqualTo(400));
        }
    }

    /**
     * 构造显式可读可写的 Flowable 表单字段。
     * @param id String，字段主键和变量名
     * @param name String，字段显示名称
     * @param type String，Flowable 字段类型
     * @param required boolean，是否必填
     * @return FormProperty，可直接交给转换器的字段
     */
    private FormProperty property(String id, String name, String type, boolean required)
    {
        FormProperty property = new FormProperty();
        property.setId(id);
        property.setVariable(id);
        property.setName(name);
        property.setType(type);
        property.setReadable(true);
        property.setWriteable(true);
        property.setRequired(required);
        return property;
    }

    /**
     * 构造静态 Flowable enum 选项。
     * @param id String，提交值
     * @param name String，显示文本
     * @return FormValue，静态枚举选项
     */
    private FormValue value(String id, String name)
    {
        FormValue value = new FormValue();
        value.setId(id);
        value.setName(name);
        return value;
    }
}
