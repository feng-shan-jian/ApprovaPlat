package com.ruoyi.flowable.service.model;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;
import org.flowable.bpmn.model.FormProperty;
import org.flowable.bpmn.model.FormValue;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceVariables;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.extension.WorkflowFormFieldExtension;

/**
 * 将 Flowable FormData 转换为系统当前表单渲染和变量校验协议。
 */
public final class WorkflowEmbeddedFormConverter
{
    /** 单个内嵌表单允许的最大字段数，与正式表单服务端上限保持一致。 */
    private static final int MAX_FIELDS = 500;

    /** 单个枚举字段允许的最大静态选项数。 */
    private static final int MAX_ENUM_VALUES = 500;

    /** BPMN 字段和变量使用稳定 ASCII 标识，避免表达式路径歧义。 */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]{0,127}");

    /** 日期格式只允许确定性的显示字符，不接受表达式或代码片段。 */
    private static final Pattern DATE_PATTERN = Pattern.compile("[A-Za-z0-9 /:._-]{1,64}");

    /** 引擎和业务层固定变量，内嵌表单不得覆盖。 */
    private static final Set<String> RESERVED_VARIABLES = Set.of(
            "initiator", "processStatus", "processInstanceId", "processDefinitionId",
            "deploymentId", "startUserId", "authenticatedUserId", "businessKey",
            "assignee", "nrOfInstances", "nrOfActiveInstances",
            "nrOfCompletedInstances", "loopCounter", "_FLOWABLE_SKIP_EXPRESSION_ENABLED");

    /** 仅序列化静态 JSON 节点，不启用类型信息或动态代码能力。 */
    private static final ObjectMapper MAPPER = JsonMapper.shared();

    /**
     * 禁止实例化纯静态转换器。
     *
     * @return 无返回值
     */
    private WorkflowEmbeddedFormConverter()
    {
    }

    /**
     * 将 BPMN FormProperty 列表转换为当前正式表单 JSON。
     *
     * @param properties List&lt;FormProperty&gt;，Flowable 安全解析后的内嵌表单字段
     * @return String，可通过 WorkflowFormTemplateValidator 的确定性表单 JSON
     */
    public static String convert(List<FormProperty> properties)
    {
        return convert(properties, extensionKey ->
        {
            throw invalid("BPMN 内嵌表单自定义字段缺少正式扩展解析器");
        });
    }

    /**
     * 将 BPMN FormProperty 列表转换为正式表单 JSON，并解析受控自定义字段版本。
     * @param properties List&lt;FormProperty&gt;，Flowable 安全解析后的内嵌表单字段
     * @param customFieldResolver Function&lt;String, WorkflowExtensionOptionView&gt;，按稳定键查询正式字段版本
     * @return String，可通过 WorkflowFormTemplateValidator 的确定性表单 JSON
     */
    public static String convert(List<FormProperty> properties,
            Function<String, WorkflowExtensionOptionView> customFieldResolver)
    {
        if (properties == null || properties.isEmpty() || properties.size() > MAX_FIELDS)
        {
            throw invalid("BPMN 内嵌表单字段数量不合法");
        }
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("labelPosition", "right");
        root.put("labelWidth", 120);
        root.put("size", "default");
        root.put("disabled", false);
        root.put("gutter", 16);
        ArrayNode fields = root.putArray("fields");
        Set<String> variables = new HashSet<>();
        for (FormProperty property : properties)
        {
            fields.add(convertProperty(property, variables, customFieldResolver));
        }
        try
        {
            return MAPPER.writeValueAsString(root);
        }
        catch (JacksonException exception)
        {
            ServiceException failure = invalid("BPMN 内嵌表单转换失败");
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 转换单个 FormProperty，并执行变量、类型、表达式和枚举门禁。
     *
     * @param property FormProperty，待转换的 BPMN 表单字段
     * @param variables Set&lt;String&gt;，当前内嵌表单已经占用的变量名
     * @return ObjectNode，当前渲染协议中的单个字段组件
     */
    private static ObjectNode convertProperty(FormProperty property, Set<String> variables,
            Function<String, WorkflowExtensionOptionView> customFieldResolver)
    {
        if (property == null || hasText(property.getExpression())
                || hasText(property.getDefaultExpression()))
        {
            // 默认表达式可能访问 Bean、网络或运行时对象；当前协议只接受客户端显式提交值。
            throw invalid("BPMN 内嵌表单不允许字段表达式或默认表达式");
        }
        String variable = firstText(property.getVariable(), property.getId());
        if (!hasText(variable) || !variable.equals(variable.trim())
                || !VARIABLE_PATTERN.matcher(variable).matches()
                || RESERVED_VARIABLES.contains(variable)
                || WorkflowMultiInstanceVariables.isReservedVariableName(variable)
                || WorkflowFormSubmissionSnapshotCodec.isReservedVariableName(variable)
                || !variables.add(variable))
        {
            throw invalid("BPMN 内嵌表单变量名非法、重复或属于保留变量");
        }
        String label = firstText(property.getName(), variable);
        if (label.length() > 255)
        {
            throw invalid("BPMN 内嵌表单字段名称过长");
        }
        String rawType = hasText(property.getType()) ? property.getType().trim() : "string";
        String type = rawType.toLowerCase(Locale.ROOT);

        ObjectNode component = JsonNodeFactory.instance.objectNode();
        ObjectNode config = component.putObject("__config__");
        config.put("label", label);
        config.put("layout", "colFormItem");
        config.put("span", 24);
        config.put("required", property.isRequired() && property.isWriteable());
        config.put("workflowReadable", property.isReadable());
        config.put("workflowWritable", property.isWriteable());
        component.put("__vModel__", variable);
        component.put("disabled", !property.isWriteable());

        switch (type)
        {
            case "string" ->
            {
                config.put("tag", "el-input");
                component.put("maxlength", 4096);
                component.put("clearable", true);
            }
            case "long", "integer" ->
            {
                config.put("tag", "el-input-number");
                // 将 Flowable FormData 的整数类型冻结到部署表单协议，服务端据此拒绝小数和越界值。
                config.put("workflowNumberType", type);
            }
            case "boolean" -> config.put("tag", "el-switch");
            case "date" -> configureDate(property, component, config);
            case "enum" -> configureEnum(property, component, config);
            default -> configureCustomField(property, component, config, rawType,
                    customFieldResolver);
        }
        return component;
    }

    /**
     * 解析 custom: 扩展类型并应用服务端固定渲染实现。
     * @param property FormProperty，当前 BPMN 内嵌字段
     * @param component ObjectNode，待补齐的正式表单组件
     * @param config ObjectNode，组件 __config__ 配置
     * @param type String，已规范为小写的 BPMN 字段类型
     * @param customFieldResolver Function&lt;String, WorkflowExtensionOptionView&gt;，正式目录解析器
     * @return void，不受控类型或目录异常时抛出稳定业务异常
     */
    private static void configureCustomField(FormProperty property, ObjectNode component,
            ObjectNode config, String type,
            Function<String, WorkflowExtensionOptionView> customFieldResolver)
    {
        if (!type.regionMatches(true, 0, WorkflowFormFieldExtension.TYPE_PREFIX, 0,
                WorkflowFormFieldExtension.TYPE_PREFIX.length())
                || type.length() == WorkflowFormFieldExtension.TYPE_PREFIX.length())
        {
            throw invalid("BPMN 内嵌表单字段类型不受支持: " + type);
        }
        String extensionKey = type.substring(WorkflowFormFieldExtension.TYPE_PREFIX.length());
        if (!extensionKey.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
        {
            throw invalid("BPMN 内嵌表单自定义字段标识不合法");
        }
        WorkflowExtensionOptionView option = customFieldResolver.apply(extensionKey);
        WorkflowFormFieldExtension.configure(property, component, config, option);
    }

    /**
     * 配置确定性的日期输入组件。
     *
     * @param property FormProperty，包含可选 datePattern 的 BPMN 字段
     * @param component ObjectNode，待补充的组件节点
     * @param config ObjectNode，组件 __config__ 节点
     * @return void，格式非法时抛出 400 业务异常
     */
    private static void configureDate(FormProperty property, ObjectNode component,
            ObjectNode config)
    {
        config.put("tag", "el-date-picker");
        component.put("type", "date");
        component.put("clearable", true);
        String datePattern = hasText(property.getDatePattern())
                ? property.getDatePattern().trim() : "yyyy-MM-dd";
        if (!DATE_PATTERN.matcher(datePattern).matches())
        {
            throw invalid("BPMN 内嵌表单日期格式不合法");
        }
        component.put("format", datePattern);
        component.put("value-format", datePattern);
        component.put("maxlength", 128);
    }

    /**
     * 配置只允许静态值的枚举选择组件。
     *
     * @param property FormProperty，包含静态 FormValue 的 BPMN 字段
     * @param component ObjectNode，待补充的组件节点
     * @param config ObjectNode，组件 __config__ 节点
     * @return void，选项为空、重复或超限时抛出 400 业务异常
     */
    private static void configureEnum(FormProperty property, ObjectNode component,
            ObjectNode config)
    {
        List<FormValue> values = property.getFormValues();
        if (values == null || values.isEmpty() || values.size() > MAX_ENUM_VALUES)
        {
            throw invalid("BPMN 内嵌枚举字段必须配置受限静态选项");
        }
        config.put("tag", "el-select");
        config.put("workflowEnum", true);
        component.put("clearable", true);
        ObjectNode slot = component.putObject("__slot__");
        ArrayNode options = slot.putArray("options");
        Set<String> optionIds = new HashSet<>();
        for (FormValue value : values)
        {
            String optionId = value == null ? null : value.getId();
            String optionName = value == null ? null : firstText(value.getName(), optionId);
            if (!hasText(optionId) || !optionId.equals(optionId.trim())
                    || optionId.length() > 255 || !optionIds.add(optionId)
                    || !hasText(optionName) || optionName.length() > 255)
            {
                throw invalid("BPMN 内嵌枚举选项非法或重复");
            }
            ObjectNode option = options.addObject();
            option.put("label", optionName);
            option.put("value", optionId);
        }
    }

    /**
     * 返回第一个非空文本，并保持原始业务值。
     *
     * @param preferred String，优先文本
     * @param fallback String，优先文本为空时的后备值
     * @return String，第一个非空文本；均为空时返回 null
     */
    private static String firstText(String preferred, String fallback)
    {
        return hasText(preferred) ? preferred.trim() : hasText(fallback) ? fallback.trim() : null;
    }

    /**
     * 判断文本是否包含非空白字符。
     *
     * @param value String，待判断文本
     * @return boolean，包含非空白字符时返回 true
     */
    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    /**
     * 创建稳定的内嵌表单 400 校验异常。
     *
     * @param message String，不包含 XML 原文或客户端值的业务提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private static ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
