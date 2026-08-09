package com.ruoyi.flowable.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

class WorkflowNodeFormPermissionSnapshotTest
{
    private static final String TEMPLATE = """
            {
              "fields": [
                {"__vModel__":"hiddenField","disabled":false,
                 "__config__":{"layout":"colFormItem","tag":"el-input","required":true}},
                {"__vModel__":"readonlyField","disabled":false,
                 "__config__":{"layout":"colFormItem","tag":"el-input","required":true}},
                {"__vModel__":"editableField","disabled":true,
                 "__config__":{"layout":"colFormItem","tag":"el-input","required":true}},
                {"__vModel__":"requiredField","disabled":true,
                 "__config__":{"layout":"colFormItem","tag":"el-input","required":false}},
                {"__vModel__":"addedLater","disabled":true,
                 "__config__":{"layout":"colFormItem","tag":"el-input","required":true}}
              ]
            }
            """;

    private final WorkflowFormTemplateValidator validator =
            new WorkflowFormTemplateValidator();

    /**
     * 验证四态权限会覆盖模板展示属性，并让模板新增字段采用节点批量默认策略。
     *
     * @return void，任一权限标志、必填或 disabled 投影不一致时测试失败
     * @throws Exception 编译结果 JSON 无法解析时抛出
     */
    @Test
    void compilesFourPermissionModesAndAppliesDefaultToNewFields() throws Exception
    {
        WorkflowBpmnFormReference reference = reference(
                WorkflowFormFieldPermissionMode.EDITABLE,
                Map.of(
                        "hiddenField", WorkflowFormFieldPermissionMode.HIDDEN,
                        "readonlyField", WorkflowFormFieldPermissionMode.READONLY,
                        "editableField", WorkflowFormFieldPermissionMode.EDITABLE,
                        "requiredField", WorkflowFormFieldPermissionMode.REQUIRED));

        JsonNode fields = JsonMapper.shared().readTree(
                WorkflowNodeFormPermissionSnapshot.apply(TEMPLATE, reference, validator))
                .path("fields");

        assertMode(fields.get(0), true, false, false, false, true);
        assertMode(fields.get(1), false, true, false, false, true);
        assertMode(fields.get(2), false, true, true, false, false);
        assertMode(fields.get(3), false, true, true, true, false);
        // addedLater 不在作者逐字段覆盖中，部署时必须采用 BPMN 固化的默认策略。
        assertMode(fields.get(4), false, true, true, false, false);
    }

    /**
     * 验证部署拒绝引用当前正式模板中已不存在的字段，防止作者模型与快照错位。
     *
     * @return void，过期字段策略未返回稳定 400 时测试失败
     */
    @Test
    void rejectsStalePermissionField()
    {
        WorkflowBpmnFormReference reference = reference(
                WorkflowFormFieldPermissionMode.READONLY,
                Map.of("removedField", WorkflowFormFieldPermissionMode.EDITABLE));

        assertThatThrownBy(() -> WorkflowNodeFormPermissionSnapshot.apply(
                TEMPLATE, reference, validator))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).contains("不存在的字段", "removedField");
                });
    }

    /**
     * 验证旧模型没有权限描述时保留原模板正文，已部署版本不被后续模板编译覆盖。
     *
     * @return void，兼容模型正文被改写或旧快照随新模板漂移时测试失败
     */
    @Test
    void preservesLegacyContentAndKeepsCompiledSnapshotImmutable()
    {
        WorkflowBpmnFormReference legacy = new WorkflowBpmnFormReference(
                1L, "key_1", "approve", "审批");
        String legacySnapshot = WorkflowNodeFormPermissionSnapshot.apply(
                TEMPLATE, legacy, validator);

        String firstDeployment = WorkflowNodeFormPermissionSnapshot.apply(
                TEMPLATE, reference(WorkflowFormFieldPermissionMode.READONLY, Map.of()), validator);
        String changedTemplate = TEMPLATE.replace("addedLater", "changedAfterDeployment");
        String secondDeployment = WorkflowNodeFormPermissionSnapshot.apply(
                changedTemplate, reference(WorkflowFormFieldPermissionMode.READONLY, Map.of()), validator);

        assertThat(legacySnapshot).isSameAs(TEMPLATE);
        assertThat(firstDeployment).contains("addedLater").doesNotContain("changedAfterDeployment");
        assertThat(secondDeployment).contains("changedAfterDeployment").doesNotContain("addedLater");
    }

    /**
     * 创建正式模板节点权限引用。
     *
     * @param defaultMode WorkflowFormFieldPermissionMode，模板新增字段采用的默认策略
     * @param fieldModes Map&lt;String,WorkflowFormFieldPermissionMode&gt;，逐字段覆盖策略
     * @return WorkflowBpmnFormReference，可交给部署快照编译器的引用
     */
    private WorkflowBpmnFormReference reference(WorkflowFormFieldPermissionMode defaultMode,
            Map<String, WorkflowFormFieldPermissionMode> fieldModes)
    {
        return new WorkflowBpmnFormReference(WorkflowFormSourceType.TEMPLATE, 1L,
                "key_1", "approve", "审批", null, "expense", defaultMode, fieldModes);
    }

    /**
     * 断言单个快照字段的隐藏、可读、可写、必填和页面禁用投影。
     *
     * @param field JsonNode，编译后的字段节点
     * @param hidden boolean，期望隐藏标志
     * @param readable boolean，期望可读标志
     * @param writable boolean，期望可写标志
     * @param required boolean，期望必填标志
     * @param disabled boolean，期望页面禁用标志
     * @return void，任一投影不一致时测试失败
     */
    private void assertMode(JsonNode field, boolean hidden, boolean readable,
            boolean writable, boolean required, boolean disabled)
    {
        JsonNode config = field.path("__config__");
        assertThat(config.path("workflowHidden").booleanValue()).isEqualTo(hidden);
        assertThat(config.path("workflowReadable").booleanValue()).isEqualTo(readable);
        assertThat(config.path("workflowWritable").booleanValue()).isEqualTo(writable);
        assertThat(config.path("required").booleanValue()).isEqualTo(required);
        assertThat(field.path("disabled").booleanValue()).isEqualTo(disabled);
    }
}
