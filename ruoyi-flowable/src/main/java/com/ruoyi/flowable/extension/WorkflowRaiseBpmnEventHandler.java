package com.ruoyi.flowable.extension;

import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.service.model.WorkflowBpmnEventCodeService;
import com.ruoyi.flowable.service.process.WorkflowBpmnEventRuntimeService;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 显式产生受控 BPMN Error 或 Escalation 的内置 Java 扩展处理器。
 */
@Component
public class WorkflowRaiseBpmnEventHandler implements WorkflowJavaExtensionHandler
{
    /** 服务端安装处理器稳定键。 */
    public static final String IMPLEMENTATION_KEY = "RAISE_BPMN_EVENT";
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> SOURCE_TYPES = Set.of(
            "SERVICE_TASK", "HTTP", "SQL", "DMN", "MANUAL");
    private static final Set<String> OPERATORS = Set.of(
            "ALWAYS", "EQUALS", "NOT_EQUALS", "TRUE", "FALSE", "PRESENT", "EMPTY");
    /** 作者配置允许的字段；部署冻结后只额外允许名称和通知策略。 */
    private static final Set<String> AUTHOR_FIELDS = Set.of(
            "eventType", "eventCode", "sourceType", "operator", "conditionVariable",
            "expectedValue", "messageVariable");
    /** 部署快照字段必须是作者字段与两项冻结元数据的精确并集。 */
    private static final Set<String> FROZEN_FIELDS = Set.of(
            "eventType", "eventCode", "eventName", "notificationPolicy", "sourceType",
            "operator", "conditionVariable", "expectedValue", "messageVariable");
    private static final String CONFIG_SCHEMA = """
            {"additionalProperties":false,"properties":{"conditionVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"eventCode":{"pattern":"^[A-Z][A-Z0-9_.-]{1,63}$","type":"string"},"eventType":{"enum":["ERROR","ESCALATION"],"type":"string"},"expectedValue":{"maxLength":256,"type":"string"},"messageVariable":{"pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$","type":"string"},"operator":{"enum":["ALWAYS","EQUALS","NOT_EQUALS","TRUE","FALSE","PRESENT","EMPTY"],"type":"string"},"sourceType":{"enum":["SERVICE_TASK","HTTP","SQL","DMN","MANUAL"],"type":"string"}},"required":["eventType","eventCode","sourceType"],"type":"object"}
            """;

    private final WorkflowBpmnEventCodeService codeService;
    private final WorkflowBpmnEventRuntimeService runtimeService;
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 创建受控事件处理器。
     * @param codeService WorkflowBpmnEventCodeService，部署时解析启用目录
     * @param runtimeService WorkflowBpmnEventRuntimeService，运行时审计并产生标准事件
     * @return 无返回值，构造后自动进入处理器注册表
     */
    public WorkflowRaiseBpmnEventHandler(WorkflowBpmnEventCodeService codeService,
            WorkflowBpmnEventRuntimeService runtimeService)
    {
        this.codeService = codeService;
        this.runtimeService = runtimeService;
    }

    /** @return String，RAISE_BPMN_EVENT。 */
    @Override
    public String implementationKey() { return IMPLEMENTATION_KEY; }

    /** @return String，用户可见处理器名称。 */
    @Override
    public String displayName() { return "产生 BPMN 业务错误或升级"; }

    /** @return String，作者配置 JSON Schema。 */
    @Override
    public String configSchema() { return WorkflowExtensionJsonCanonicalizer.canonicalize(CONFIG_SCHEMA); }

    /**
     * 校验作者配置并冻结目录名称和通知策略；运行快照只复核冻结字段，不重新读取可变目录。
     * @param config JsonNode，作者配置或部署快照配置
     * @return String，字段顺序确定的冻结配置 JSON
     */
    @Override
    public String validateAndNormalizeConfig(JsonNode config)
    {
        if (config == null || !config.isObject())
        {
            throw invalid("BPMN 事件处理器配置必须是对象");
        }
        boolean frozen = config.has("eventName") || config.has("notificationPolicy");
        Set<String> allowedFields = frozen ? FROZEN_FIELDS : AUTHOR_FIELDS;
        if (config.propertyStream().anyMatch(entry -> !allowedFields.contains(entry.getKey())))
        {
            // 未知字段可能绕过前端约束或造成部署快照语义漂移，必须失败关闭。
            throw invalid("BPMN 事件配置包含不受支持的字段");
        }
        String eventType = requiredText(config, "eventType");
        String eventCode = requiredText(config, "eventCode");
        String sourceType = requiredText(config, "sourceType");
        if (!Set.of("ERROR", "ESCALATION").contains(eventType)
                || !eventCode.matches("[A-Z][A-Z0-9_.-]{1,63}")
                || !SOURCE_TYPES.contains(sourceType))
        {
            throw invalid("BPMN 事件类型、编码或来源不合法");
        }
        String operator = optionalText(config, "operator");
        operator = operator == null ? "ALWAYS" : operator;
        String conditionVariable = optionalVariable(config, "conditionVariable");
        String messageVariable = optionalVariable(config, "messageVariable");
        String expectedValue = optionalText(config, "expectedValue");
        if (!OPERATORS.contains(operator)
                || (!"ALWAYS".equals(operator) && conditionVariable == null)
                || (("EQUALS".equals(operator) || "NOT_EQUALS".equals(operator))
                    && expectedValue == null))
        {
            throw invalid("BPMN 事件触发条件不完整");
        }

        String eventName;
        String notificationPolicy;
        if (frozen)
        {
            eventName = requiredText(config, "eventName");
            notificationPolicy = requiredText(config, "notificationPolicy");
            if (!Set.of("NONE", "INITIATOR").contains(notificationPolicy))
            {
                throw invalid("BPMN 事件冻结通知策略不合法");
            }
        }
        else
        {
            WfBpmnEventCode code = codeService.requireEnabled(eventType, eventCode);
            eventName = code.getEventName();
            notificationPolicy = code.getNotificationPolicy();
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("eventType", eventType);
        normalized.put("eventCode", eventCode);
        normalized.put("eventName", eventName);
        normalized.put("notificationPolicy", notificationPolicy);
        normalized.put("sourceType", sourceType);
        normalized.put("operator", operator);
        putOptional(normalized, "conditionVariable", conditionVariable);
        putOptional(normalized, "expectedValue", expectedValue);
        putOptional(normalized, "messageVariable", messageVariable);
        try
        {
            return objectMapper.writeValueAsString(normalized);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("BPMN 事件配置序列化失败", HttpStatus.ERROR);
        }
    }

    /**
     * 条件满足时产生显式 BPMN 业务事件；任何普通异常均原样作为技术失败传播。
     * @param execution DelegateExecution，当前 Flowable 服务任务上下文
     * @param config JsonNode，部署快照冻结配置
     * @return void，条件不满足时正常返回
     */
    @Override
    public void execute(DelegateExecution execution, JsonNode config)
    {
        JsonNode frozen;
        try
        {
            frozen = objectMapper.readTree(validateAndNormalizeConfig(config));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("BPMN 事件快照配置解析失败", HttpStatus.ERROR);
        }
        String operator = frozen.get("operator").textValue();
        String variableName = optionalText(frozen, "conditionVariable");
        Object value = variableName == null ? null : execution.getVariable(variableName);
        if (!matches(operator, value, optionalText(frozen, "expectedValue")))
        {
            return;
        }
        String messageVariable = optionalText(frozen, "messageVariable");
        String message = messageVariable == null ? null : safeMessage(execution.getVariable(messageVariable));
        runtimeService.raise(execution, new WorkflowBpmnEventRuntimeService.FrozenEvent(
                frozen.get("eventType").textValue(), frozen.get("eventCode").textValue(),
                frozen.get("eventName").textValue(), frozen.get("notificationPolicy").textValue(),
                frozen.get("sourceType").textValue(), message));
    }

    /** @param operator String，条件运算符；@param value Object，流程变量；@param expected String，期望值；@return boolean，是否触发。 */
    private boolean matches(String operator, Object value, String expected)
    {
        String scalar = value == null ? null : value.toString();
        return switch (operator)
        {
            case "ALWAYS" -> true;
            case "EQUALS" -> expected.equals(scalar);
            case "NOT_EQUALS" -> !expected.equals(scalar);
            case "TRUE" -> Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(scalar);
            case "FALSE" -> Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(scalar);
            case "PRESENT" -> scalar != null && !scalar.isBlank();
            case "EMPTY" -> scalar == null || scalar.isBlank();
            default -> false;
        };
    }

    /** @param value Object，消息变量；@return String，最大 500 字符的标量摘要。 */
    private String safeMessage(Object value)
    {
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean))
        {
            return null;
        }
        String text = value.toString();
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /** @param config JsonNode，配置；@param name String，字段名；@return String，非空文本。 */
    private String requiredText(JsonNode config, String name)
    {
        String value = optionalText(config, name);
        if (value == null) throw invalid("BPMN 事件配置缺少 " + name);
        return value;
    }

    /** @param config JsonNode，配置；@param name String，字段名；@return String，去空白文本或 null。 */
    private String optionalText(JsonNode config, String name)
    {
        JsonNode node = config.get(name);
        return node != null && node.isTextual() && !node.textValue().isBlank()
                ? node.textValue().trim() : null;
    }

    /** @param config JsonNode，配置；@param name String，变量字段；@return String，合法变量名或 null。 */
    private String optionalVariable(JsonNode config, String name)
    {
        String value = optionalText(config, name);
        if (value != null && !VARIABLE_PATTERN.matcher(value).matches())
        {
            throw invalid("BPMN 事件变量名不合法");
        }
        return value;
    }

    /** @param target ObjectNode，输出对象；@param name String，字段名；@param value String，可空值；@return void，无返回值。 */
    private void putOptional(ObjectNode target, String name, String value)
    {
        if (value != null) target.put(name, value);
    }

    /** @param message String，稳定提示；@return ServiceException，HTTP 400 配置异常。 */
    private ServiceException invalid(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
