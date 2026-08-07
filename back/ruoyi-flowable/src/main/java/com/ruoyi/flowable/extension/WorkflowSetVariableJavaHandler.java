package com.ruoyi.flowable.extension;

import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 将受控常量写入流程变量的内置 Java 处理器。
 *
 * 该处理器提供可真实执行且无外部副作用的首个注册表能力，用于状态标记、路由常量和后续节点输入。
 */
@Component
public class WorkflowSetVariableJavaHandler implements WorkflowJavaExtensionHandler
{
    /** 流程变量名必须是稳定英文标识，禁止覆盖 Flowable 内部变量。 */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /** 禁止扩展处理器覆盖的引擎及系统变量前缀。 */
    private static final Set<String> RESERVED_PREFIXES = Set.of("ACT_", "FLOWABLE_", "__", "WF_");

    /** 配置 Schema 由服务端安装包固定，目录版本只能引用，不能由客户端改写。 */
    private static final String CONFIG_SCHEMA = "{\"type\":\"object\",\"additionalProperties\":false,"
            + "\"required\":[\"targetVariable\",\"value\"],\"properties\":{"
            + "\"targetVariable\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z_][A-Za-z0-9_]{0,127}$\"},"
            + "\"value\":{\"type\":[\"string\",\"number\",\"boolean\"]}}}";

    /** Jackson 3 仅用于结构化配置读写，不启用默认类型或反序列化任意类。 */
    private final ObjectMapper objectMapper = JsonMapper.shared();

    /**
     * 返回内置处理器稳定键。
     * @return String，SET_VARIABLE
     */
    @Override
    public String implementationKey()
    {
        return "SET_VARIABLE";
    }

    /**
     * 返回用户可见处理器名称。
     * @return String，设置流程变量
     */
    @Override
    public String displayName()
    {
        return "设置流程变量";
    }

    /**
     * 返回不可变配置 Schema。
     * @return String，JSON Schema 文本
     */
    @Override
    public String configSchema()
    {
        return CONFIG_SCHEMA;
    }

    /**
     * 校验变量名、保留前缀、字段集合和值类型，并按固定字段顺序输出 JSON。
     * @param config JsonNode，待校验节点配置
     * @return String，规范化配置 JSON
     */
    @Override
    public String validateAndNormalizeConfig(JsonNode config)
    {
        if (config == null || !config.isObject() || config.size() != 2
                || !config.has("targetVariable") || !config.has("value"))
        {
            throw invalidConfig("设置变量扩展配置必须且只能包含 targetVariable 和 value");
        }
        JsonNode variableNode = config.get("targetVariable");
        JsonNode valueNode = config.get("value");
        String targetVariable = variableNode == null || !variableNode.isTextual()
                ? "" : variableNode.textValue().trim();
        if (!VARIABLE_PATTERN.matcher(targetVariable).matches())
        {
            throw invalidConfig("设置变量扩展的目标变量名不合法");
        }
        String upperVariable = targetVariable.toUpperCase(java.util.Locale.ROOT);
        if (RESERVED_PREFIXES.stream().anyMatch(upperVariable::startsWith))
        {
            throw invalidConfig("设置变量扩展不能写入系统保留变量");
        }
        if (valueNode == null || !(valueNode.isTextual() || valueNode.isNumber()
                || valueNode.isBoolean()))
        {
            throw invalidConfig("设置变量扩展的 value 只能是字符串、数字或布尔值");
        }

        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("targetVariable", targetVariable);
        normalized.set("value", valueNode.deepCopy());
        try
        {
            return objectMapper.writeValueAsString(normalized);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("扩展配置序列化失败", HttpStatus.ERROR);
        }
    }

    /**
     * 把规范配置中的标量值写入当前流程实例变量。
     * @param execution DelegateExecution，当前 Flowable 活动执行上下文
     * @param config JsonNode，已经重新校验的快照配置
     * @return void，无返回值
     */
    @Override
    public void execute(DelegateExecution execution, JsonNode config)
    {
        ConfiguredVariable configuredVariable = readConfiguredVariable(config);
        execution.setVariable(configuredVariable.name(), configuredVariable.value());
    }

    /**
     * 声明设置变量处理器可安全复用于执行监听器和任务监听器。
     * @return boolean，固定返回 true
     */
    @Override
    public boolean supportsBusinessListener()
    {
        return true;
    }

    /**
     * 在任务生命周期事件中把受控标量写入流程变量。
     * @param task DelegateTask，当前 Flowable 用户任务上下文
     * @param config JsonNode，已经重新校验的不可变快照配置
     * @return void，无返回值
     */
    @Override
    public void executeTask(DelegateTask task, JsonNode config)
    {
        ConfiguredVariable configuredVariable = readConfiguredVariable(config);
        task.setVariable(configuredVariable.name(), configuredVariable.value());
    }

    /**
     * 重新校验快照并转换为可写入 Flowable 变量作用域的 Java 标量。
     * @param config JsonNode，部署快照回读的配置对象
     * @return ConfiguredVariable，变量名和受控标量值
     */
    private ConfiguredVariable readConfiguredVariable(JsonNode config)
    {
        String normalized = validateAndNormalizeConfig(config);
        try
        {
            JsonNode safeConfig = objectMapper.readTree(normalized);
            JsonNode value = safeConfig.get("value");
            Object javaValue;
            if (value.isTextual())
            {
                javaValue = value.textValue();
            }
            else if (value.isBoolean())
            {
                javaValue = value.booleanValue();
            }
            else if (value.isIntegralNumber())
            {
                javaValue = value.longValue();
            }
            else
            {
                javaValue = value.doubleValue();
            }
            return new ConfiguredVariable(
                    safeConfig.get("targetVariable").textValue(), javaValue);
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("扩展快照配置解析失败", HttpStatus.ERROR);
        }
    }

    /**
     * 设置变量处理器规范化后的运行参数。
     * @param name String，目标流程变量名
     * @param value Object，字符串、数字或布尔 Java 标量
     */
    private record ConfiguredVariable(String name, Object value)
    {
    }

    /**
     * 创建对外稳定的 400 配置异常。
     * @param message String，具体配置约束说明
     * @return ServiceException，可直接抛出的业务异常
     */
    private ServiceException invalidConfig(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }
}
