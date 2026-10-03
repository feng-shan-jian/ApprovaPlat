package com.ruoyi.flowable.service.model;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.Process;
import tools.jackson.databind.JsonNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowCallActivityVariableView;
import com.ruoyi.flowable.service.WorkflowFormTemplateValidator;

/** 作者目录与调用活动映射共同使用的纯字段提取、合并与视图规则。 */
final class WorkflowCallActivityFields
{
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private final WorkflowFormTemplateValidator formTemplateValidator;

    WorkflowCallActivityFields(WorkflowFormTemplateValidator formTemplateValidator)
    {
        this.formTemplateValidator = formTemplateValidator;
    }

    /**
     * 从作者文档的正式模板或内嵌表单构建各父流程字段目录。
     * @param document WorkflowBpmnDocument，已解析作者文档
     * @return Map&lt;String, Map&lt;String, FieldSpec&gt;&gt;，按父流程 key 分组的字段目录
     */
    /** 作者目录与最终映射门禁复用同一合并、权限交集和类型冲突规则。 */
    Map<String, List<WorkflowCallActivityVariableView>> describeParentFields(WorkflowBpmnDocument document,
            Map<String, JsonNode> nodeFormContents)
    {
        Map<String, List<WorkflowCallActivityVariableView>> result = new LinkedHashMap<>();
        parentFieldCatalogs(document, nodeFormContents).forEach((key, fields) -> result.put(key, toVariableViews(fields)));
        return Map.copyOf(result);
    }

    Map<String, Map<String, FieldSpec>> parentFieldCatalogs(WorkflowBpmnDocument document,
            Map<String, JsonNode> nodeFormContents)
    {
        Map<String, Map<String, FieldSpec>> catalogs = new LinkedHashMap<>();
        for (WorkflowBpmnFormReference reference : document.formReferences())
        {
            Process process = document.bpmnModel().getProcessById(reference.processKey());
            if (process == null || process.getFlowElement(reference.nodeKey(), true) == null
                    || !nodeFormContents.containsKey(reference.nodeKey()))
            {
                throw dataError("调用活动父表单不属于当前流程节点");
            }
            mergeFields(catalogs.computeIfAbsent(reference.processKey(), key -> new LinkedHashMap<>()),
                    extractFieldSpecs(nodeFormContents.get(reference.nodeKey())), "父流程表单字段类型冲突");
        }
        return catalogs.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
    }

    /**
     * 从已校验表单 JSON 提取可安全映射的标量字段。
     * @param root JsonNode，正式模板或部署快照 JSON 根节点
     * @return Map&lt;String, FieldSpec&gt;，按表单顺序去重的标量字段
     */
    Map<String, FieldSpec> extractFieldSpecs(JsonNode root)
    {
        Map<String, FieldSpec> fields = new LinkedHashMap<>();
        collectFieldSpecs(root, fields);
        return Map.copyOf(fields);
    }

    /**
     * 递归收集表单字段，只开放单值文本、数值、布尔和受控标量。
     * @param root JsonNode，已经验证的完整表单
     * @param fields Map&lt;String, FieldSpec&gt;，调用方维护的字段目录
     * @return void，集合、附件、表格和范围字段不会进入映射目录
     */
    private void collectFieldSpecs(JsonNode root, Map<String, FieldSpec> fields)
    {
        formTemplateValidator.visitFields(root, (field, permission) ->
        {
            String name = text(field, "vModel");
            WorkflowFormTemplateValidator.FieldType fieldType;
            try
            {
                fieldType = formTemplateValidator.resolveFieldType(field);
            }
            catch (ServiceException exception)
            {
                throw dataError(exception.getMessage());
            }
            String type = fieldType == null ? null : fieldType.scalarType();
            if (hasText(name) && VARIABLE_PATTERN.matcher(name).matches() && type != null)
            {
                String label = firstText(text(field, "label"), name);
                boolean required = booleanValue(field, "required", false) && permission.writable();
                boolean readable = permission.readable();
                boolean writable = permission.writable()
                        && !booleanValue(field, "disabled", false);
                boolean systemWritable = booleanValue(field, "workflowSystemWritable", false);
                FieldSpec candidate = new FieldSpec(name, label, type, required, readable, writable, systemWritable);
                FieldSpec previous = fields.putIfAbsent(name, candidate);
                if (previous != null && !previous.type().equals(candidate.type()))
                {
                    throw dataError("流程表单包含同名异构字段");
                }
            }
        });
    }

    /**
     * 合并字段目录并阻止同名异构字段静默覆盖。
     * @param target Map&lt;String, FieldSpec&gt;，目标目录
     * @param source Map&lt;String, FieldSpec&gt;，来源目录
     * @param conflictMessage String，类型冲突提示
     * @return void，类型一致时保留首次字段业务名称
     */
    void mergeFields(Map<String, FieldSpec> target, Map<String, FieldSpec> source,
            String conflictMessage)
    {
        source.forEach((name, field) ->
        {
            FieldSpec previous = target.putIfAbsent(name, field);
            if (previous != null && !previous.type().equals(field.type()))
            {
                throw new ServiceException(conflictMessage + ": " + name + " (" + previous.type() + " / " + field.type() + ")", HttpStatus.CONFLICT);
            }
            if (previous != null)
            {
                // 一个流程变量贯穿多个表单节点；权限取交集，必填约束取并集。
                target.put(name, new FieldSpec(name, previous.label(), previous.type(),
                        previous.required() || field.required(),
                        previous.readable() && field.readable(),
                        previous.writable() && field.writable(), previous.systemWritable() && field.systemWritable()));
            }
        });
    }

    /**
     * 将内部字段规则转换为不泄露表单正文的目录视图。
     * @param fields Map&lt;String, FieldSpec&gt;，字段规则
     * @return List&lt;WorkflowCallActivityVariableView&gt;，按变量名稳定排序的视图
     */
    List<WorkflowCallActivityVariableView> toVariableViews(Map<String, FieldSpec> fields)
    {
        return fields.values().stream().sorted(Comparator.comparing(FieldSpec::name))
                .map(field -> new WorkflowCallActivityVariableView(field.name(), field.label(),
                        field.type(), field.required(), field.readable(), field.writable(), field.systemWritable()))
                .toList();
    }

    /** @param message String，稳定数据错误提示。@return ServiceException，HTTP 500 数据异常。 */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR).setSubCode("CALL_ACTIVITY_CATALOG_DATA_INVALID");
    }

    /** @param node JsonNode，可空对象。@param field String，字段名。@return String，文本值或空串。 */
    private String text(JsonNode node, String field)
    {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    /** @param node JsonNode，可空对象。@param field String，字段名。@param fallback boolean，默认值。@return boolean，布尔值。 */
    private boolean booleanValue(JsonNode node, String field, boolean fallback)
    {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    /** @param preferred String，优先值。@param fallback String，后备值。@return String，第一个非空文本。 */
    private String firstText(String preferred, String fallback)
    {
        return hasText(preferred) ? preferred.trim() : safeText(fallback);
    }

    /** @param value String，可空文本。@return String，空值转换为空串。 */
    private String safeText(String value)
    {
        return value == null ? "" : value.trim();
    }

    /** @param value String，待判断文本。@return boolean，包含非空白字符时返回 true。 */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /** 调用活动映射可使用的表单字段规则。 */
    record FieldSpec(String name, String label, String type, boolean required,
            boolean readable, boolean writable, boolean systemWritable) { }

}
