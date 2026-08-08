package com.ruoyi.flowable.extension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.engine.delegate.DelegateExecution;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelValidationResult;
import dev.cel.common.types.CelType;
import dev.cel.common.types.SimpleType;
import dev.cel.compiler.CelCompilerBuilder;
import dev.cel.compiler.CelCompilerFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.runtime.CelRuntimeFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 受控 CEL 编译与运行沙箱。
 *
 * 沙箱只声明节点配置中的类型化变量，不注册文件、网络、进程、反射或 Spring Bean 函数；
 * 部署和运行均重新编译同一不可变配置，防止数据库或运行库漂移绕过门禁。
 */
public class WorkflowCelSandbox
{
    /** CEL 表达式最大 Unicode 码点数，避免超大解析树占用资源。 */
    private static final int MAX_EXPRESSION_CODE_POINTS = 4096;

    /** 单节点最多允许读取的流程变量数量。 */
    private static final int MAX_VARIABLES = 32;

    /** CEL comprehension 最大迭代次数，阻止无界列表计算。 */
    private static final int MAX_COMPREHENSION_ITERATIONS = 1000;

    /** 流程变量和结果变量统一使用安全标识符。 */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    /** 引擎内部变量不能由 CEL 读取或覆盖。 */
    private static final Set<String> RESERVED_VARIABLES = Set.of(
            "initiator", "processStatus", "processInstanceId", "processDefinitionId",
            "deploymentId", "startUserId", "authenticatedUserId", "businessKey",
            "assignee", "nrOfInstances", "nrOfActiveInstances", "nrOfCompletedInstances",
            "loopCounter", "_FLOWABLE_SKIP_EXPRESSION_ENABLED");

    /** 引擎内部前缀不能由 CEL 读取或覆盖。 */
    private static final List<String> RESERVED_PREFIXES = List.of(
            "wfMiUsers_", "_wfMiMembers_", "_wfMiRevision_", "_wfMiMode_",
            "__ruoyi_workflow_");

    /** 当前允许进入 CEL 激活和输出的确定性标量类型。 */
    private static final Map<String, CelType> ALLOWED_TYPES = Map.of(
            "BOOL", SimpleType.BOOL,
            "INT", SimpleType.INT,
            "DOUBLE", SimpleType.DOUBLE,
            "STRING", SimpleType.STRING);

    /** CEL 配置 Schema 由服务端固定，目录版本只能冻结该规范。 */
    private static final String CONFIG_SCHEMA = """
            {
              "type":"object",
              "additionalProperties":false,
              "required":["expression","resultVariable","resultType","variables"],
              "properties":{
                "expression":{"type":"string","minLength":1,"maxLength":4096},
                "resultVariable":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$"},
                "resultType":{"type":"string","enum":["BOOL","INT","DOUBLE","STRING"]},
                "variables":{
                  "type":"array","maxItems":32,
                  "items":{
                    "type":"object","additionalProperties":false,
                    "required":["name","type"],
                    "properties":{
                      "name":{"type":"string","pattern":"^[A-Za-z_][A-Za-z0-9_]{0,127}$"},
                      "type":{"type":"string","enum":["BOOL","INT","DOUBLE","STRING"]}
                    }
                  }
                }
              }
            }
            """;

    /** CEL 资源限制在编译和运行时使用同一份不可变选项。 */
    private static final CelOptions CEL_OPTIONS = CelOptions.current()
            .maxExpressionCodePointSize(MAX_EXPRESSION_CODE_POINTS)
            .maxParseRecursionDepth(64)
            .maxParseErrorRecoveryLimit(10)
            .comprehensionMaxIterations(MAX_COMPREHENSION_ITERATIONS)
            .maxRegexProgramSize(1000)
            .enableOptionalSyntax(false)
            .errorOnDuplicateMapKeys(true)
            .errorOnIntWrap(true)
            .build();

    private final ObjectMapper objectMapper = JsonMapper.shared();
    private final CelRuntime runtime = CelRuntimeFactory.standardCelRuntimeBuilder()
            .setOptions(CEL_OPTIONS)
            .setStandardEnvironmentEnabled(true)
            .build();

    /**
     * 返回服务端固定 CEL 节点配置 Schema。
     * @return String，字段顺序确定的规范 JSON
     */
    public String configSchema()
    {
        return WorkflowExtensionJsonCanonicalizer.canonicalize(CONFIG_SCHEMA);
    }

    /**
     * 校验 CEL 配置、变量白名单、返回类型并编译表达式。
     * @param config JsonNode，作者 BPMN 中的 CEL 配置对象
     * @return String，字段和变量顺序确定的规范 JSON
     */
    public String validateAndNormalizeConfig(JsonNode config)
    {
        return compile(config).normalizedConfig();
    }

    /**
     * 使用部署时冻结的 CEL 配置计算布尔结果，不向流程实例写入中间变量。
     * @param activation Map&lt;String,Object&gt;，条件路由已完成原子比较的布尔变量
     * @param configJson String，部署快照中的规范 CEL 配置 JSON
     * @return boolean，受控表达式的布尔计算结果
     */
    public boolean evaluateBoolean(Map<String, Object> activation, String configJson)
    {
        CelCompiledConfig compiled;
        try
        {
            compiled = compile(objectMapper.readTree(configJson));
        }
        catch (JacksonException | ServiceException exception)
        {
            throw new ServiceException("CEL 布尔执行配置无法通过部署时门禁", HttpStatus.ERROR);
        }
        if (!"BOOL".equals(compiled.resultType()))
        {
            throw new ServiceException("CEL 条件路由结果必须声明为 BOOL", HttpStatus.ERROR);
        }

        // 条件路由只能传入快照声明的原子比较结果，拒绝额外运行变量进入 CEL。
        Set<String> declaredNames = compiled.variables().stream()
                .map(CelVariable::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (activation == null || !declaredNames.equals(activation.keySet()))
        {
            throw new ServiceException("CEL 条件路由变量与部署白名单不一致", HttpStatus.ERROR);
        }
        Map<String, Object> normalizedActivation = new LinkedHashMap<>();
        for (CelVariable variable : compiled.variables())
        {
            normalizedActivation.put(variable.name(), normalizeRuntimeValue(
                    activation.get(variable.name()), variable.type(), "条件路由变量"));
        }

        try
        {
            Object result = runtime.createProgram(compiled.ast()).eval(normalizedActivation);
            return (Boolean) normalizeRuntimeValue(result, "BOOL", "条件路由结果");
        }
        catch (CelEvaluationException exception)
        {
            throw new ServiceException("CEL 条件路由表达式执行失败", HttpStatus.ERROR);
        }
    }

    /**
     * 使用冻结配置读取显式变量白名单、执行 CEL 并写入类型化结果。
     * @param execution DelegateExecution，当前 Flowable 活动执行上下文
     * @param config JsonNode，已从部署快照读取的 CEL 配置
     * @return void，成功时只写入一个配置声明的结果变量
     */
    public void execute(DelegateExecution execution, JsonNode config)
    {
        CelCompiledConfig compiled;
        try
        {
            compiled = compile(config);
        }
        catch (ServiceException exception)
        {
            throw new ServiceException("CEL 执行配置无法通过部署时门禁", HttpStatus.ERROR);
        }

        Map<String, Object> activation = new LinkedHashMap<>();
        for (CelVariable variable : compiled.variables())
        {
            if (!execution.hasVariable(variable.name()))
            {
                throw new ServiceException("CEL 白名单变量不存在: " + variable.name(), HttpStatus.ERROR);
            }
            activation.put(variable.name(), normalizeRuntimeValue(
                    execution.getVariable(variable.name()), variable.type(), "输入变量"));
        }

        try
        {
            Object result = runtime.createProgram(compiled.ast()).eval(activation);
            execution.setVariable(compiled.resultVariable(), normalizeRuntimeValue(
                    result, compiled.resultType(), "表达式结果"));
        }
        catch (CelEvaluationException exception)
        {
            throw new ServiceException("CEL 表达式执行失败", HttpStatus.ERROR);
        }
    }

    /**
     * 将结构化配置规范化并编译为带静态类型的 CEL AST。
     * @param config JsonNode，待校验配置对象
     * @return CelCompiledConfig，规范配置、AST、输入白名单和输出契约
     */
    private CelCompiledConfig compile(JsonNode config)
    {
        if (config == null || !config.isObject())
        {
            throw new ServiceException("CEL 配置必须是 JSON 对象", HttpStatus.BAD_REQUEST);
        }
        rejectUnknownFields(config, Set.of("expression", "resultVariable", "resultType", "variables"),
                "CEL 配置");
        String expression = requiredText(config, "expression", MAX_EXPRESSION_CODE_POINTS);
        if (expression.codePointCount(0, expression.length()) > MAX_EXPRESSION_CODE_POINTS)
        {
            throw new ServiceException("CEL 表达式超过长度限制", HttpStatus.BAD_REQUEST);
        }
        String resultVariable = requireVariableName(requiredText(config, "resultVariable", 128), "结果变量");
        String resultType = requireType(requiredText(config, "resultType", 16));
        List<CelVariable> variables = readVariables(config.get("variables"));
        if (variables.stream().anyMatch(variable -> variable.name().equals(resultVariable)))
        {
            throw new ServiceException("CEL 结果变量不能覆盖输入白名单变量", HttpStatus.BAD_REQUEST);
        }

        CelCompilerBuilder compilerBuilder = CelCompilerFactory.standardCelCompilerBuilder()
                .setOptions(CEL_OPTIONS)
                .setStandardEnvironmentEnabled(true)
                .setResultType(ALLOWED_TYPES.get(resultType));
        variables.forEach(variable -> compilerBuilder.addVar(variable.name(), ALLOWED_TYPES.get(variable.type())));
        CelValidationResult validation = compilerBuilder.build().compile(expression, "workflow-cel");
        if (validation.hasError())
        {
            throw new ServiceException("CEL 表达式类型检查失败: " + validation.getErrorString(),
                    HttpStatus.BAD_REQUEST);
        }
        try
        {
            ObjectNode normalized = objectMapper.createObjectNode();
            normalized.put("expression", expression);
            normalized.put("resultVariable", resultVariable);
            normalized.put("resultType", resultType);
            ArrayNode normalizedVariables = normalized.putArray("variables");
            variables.forEach(variable ->
            {
                ObjectNode item = normalizedVariables.addObject();
                item.put("name", variable.name());
                item.put("type", variable.type());
            });
            return new CelCompiledConfig(
                    WorkflowExtensionJsonCanonicalizer.canonicalize(
                            objectMapper.writeValueAsString(normalized)),
                    validation.getAst(), resultVariable, resultType, List.copyOf(variables));
        }
        catch (CelValidationException | JacksonException exception)
        {
            throw new ServiceException("CEL 表达式编译结果不可用", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 读取并排序显式变量白名单，重复、保留或未知字段全部拒绝。
     * @param variablesNode JsonNode，variables 数组节点
     * @return List&lt;CelVariable&gt;，按变量名排序的不可变语义列表
     */
    private List<CelVariable> readVariables(JsonNode variablesNode)
    {
        if (variablesNode == null || !variablesNode.isArray()
                || variablesNode.size() > MAX_VARIABLES)
        {
            throw new ServiceException("CEL variables 必须是最多 32 项的数组", HttpStatus.BAD_REQUEST);
        }
        List<CelVariable> variables = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode item : variablesNode)
        {
            if (!item.isObject())
            {
                throw new ServiceException("CEL 变量声明必须是 JSON 对象", HttpStatus.BAD_REQUEST);
            }
            rejectUnknownFields(item, Set.of("name", "type"), "CEL 变量声明");
            String name = requireVariableName(requiredText(item, "name", 128), "输入变量");
            if (!names.add(name))
            {
                throw new ServiceException("CEL 输入变量重复: " + name, HttpStatus.BAD_REQUEST);
            }
            variables.add(new CelVariable(name, requireType(requiredText(item, "type", 16))));
        }
        variables.sort(Comparator.comparing(CelVariable::name));
        return variables;
    }

    /**
     * 读取必填短文本并拒绝空白、控制字符和超长值。
     * @param object JsonNode，父配置对象
     * @param fieldName String，待读取字段名
     * @param maxLength int，允许的 UTF-16 最大长度
     * @return String，去除首尾空白的文本
     */
    private String requiredText(JsonNode object, String fieldName, int maxLength)
    {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual())
        {
            throw new ServiceException("CEL 配置字段缺失或类型错误: " + fieldName,
                    HttpStatus.BAD_REQUEST);
        }
        String text = value.asText().trim();
        if (text.isEmpty() || text.length() > maxLength || text.chars().anyMatch(Character::isISOControl))
        {
            throw new ServiceException("CEL 配置字段内容不合法: " + fieldName,
                    HttpStatus.BAD_REQUEST);
        }
        return text;
    }

    /**
     * 校验变量名不占用引擎内部命名空间。
     * @param variableName String，输入或结果变量名
     * @param label String，异常中展示的业务含义
     * @return String，校验通过的原变量名
     */
    private String requireVariableName(String variableName, String label)
    {
        boolean reserved = RESERVED_VARIABLES.contains(variableName)
                || RESERVED_PREFIXES.stream().anyMatch(variableName::startsWith);
        if (!VARIABLE_NAME.matcher(variableName).matches() || reserved)
        {
            throw new ServiceException("CEL " + label + "名称不合法或属于保留变量",
                    HttpStatus.BAD_REQUEST);
        }
        return variableName;
    }

    /**
     * 校验 CEL 输入或输出类型必须来自固定标量白名单。
     * @param type String，配置中的大写类型编码
     * @return String，校验通过的类型编码
     */
    private String requireType(String type)
    {
        if (!ALLOWED_TYPES.containsKey(type))
        {
            throw new ServiceException("CEL 变量类型不受支持: " + type, HttpStatus.BAD_REQUEST);
        }
        return type;
    }

    /**
     * 拒绝配置对象中的额外字段，防止前端或数据库夹带未参与摘要的执行参数。
     * @param object JsonNode，待检查对象
     * @param allowedFields Set&lt;String&gt;，允许字段集合
     * @param label String，异常中展示的对象名称
     * @return void，存在额外字段时抛出业务异常
     */
    private void rejectUnknownFields(JsonNode object, Set<String> allowedFields, String label)
    {
        for (String fieldName : object.propertyNames())
        {
            if (!allowedFields.contains(fieldName))
            {
                throw new ServiceException(label + "包含未允许字段: " + fieldName,
                        HttpStatus.BAD_REQUEST);
            }
        }
    }

    /**
     * 将 Flowable 变量或 CEL 结果收敛为声明类型，禁止任意 Java 对象进入表达式。
     * @param value Object，流程变量或 CEL 计算结果
     * @param type String，BOOL、INT、DOUBLE 或 STRING
     * @param label String，异常中展示的值来源
     * @return Object，CEL 和 Flowable 均可安全持久化的标量值
     */
    private Object normalizeRuntimeValue(Object value, String type, String label)
    {
        if (value == null)
        {
            throw new ServiceException("CEL " + label + "不能为空", HttpStatus.ERROR);
        }
        try
        {
            return switch (type)
            {
                case "BOOL" -> value instanceof Boolean booleanValue
                        ? booleanValue : invalidRuntimeType(label, type);
                case "STRING" -> value instanceof String stringValue
                        ? stringValue : invalidRuntimeType(label, type);
                case "INT" -> normalizeInteger(value, label);
                case "DOUBLE" -> normalizeDouble(value, label);
                default -> throw new ServiceException("CEL 运行类型不受支持", HttpStatus.ERROR);
            };
        }
        catch (NumberFormatException | ArithmeticException exception)
        {
            throw new ServiceException("CEL " + label + "数值超出声明类型范围", HttpStatus.ERROR);
        }
    }

    /**
     * 把整型 Number 转换为 CEL int64，拒绝小数和溢出。
     * @param value Object，待转换运行值
     * @param label String，异常中展示的值来源
     * @return Long，精确 int64 值
     */
    private Long normalizeInteger(Object value, String label)
    {
        if (!(value instanceof Number number))
        {
            return (Long) invalidRuntimeType(label, "INT");
        }
        return new BigDecimal(number.toString()).longValueExact();
    }

    /**
     * 把数值转换为有限 CEL double，拒绝 NaN 和无穷大。
     * @param value Object，待转换运行值
     * @param label String，异常中展示的值来源
     * @return Double，有限双精度值
     */
    private Double normalizeDouble(Object value, String label)
    {
        if (!(value instanceof Number number))
        {
            return (Double) invalidRuntimeType(label, "DOUBLE");
        }
        double normalized = number.doubleValue();
        if (!Double.isFinite(normalized))
        {
            throw new ServiceException("CEL " + label + "必须是有限数值", HttpStatus.ERROR);
        }
        return normalized;
    }

    /**
     * 统一抛出运行值与声明类型不一致异常。
     * @param label String，异常中展示的值来源
     * @param type String，期望声明类型
     * @return Object，此函数始终抛出异常而不返回
     */
    private Object invalidRuntimeType(String label, String type)
    {
        throw new ServiceException("CEL " + label + "与声明类型 " + type + " 不一致",
                HttpStatus.ERROR);
    }

    /**
     * 从快照配置 JSON 解析 CEL 配置并执行。
     * @param execution DelegateExecution，当前 Flowable 活动执行上下文
     * @param configJson String，部署快照中的规范配置 JSON
     * @return void，解析和执行成功后写入结果变量
     */
    public void execute(DelegateExecution execution, String configJson)
    {
        try
        {
            execute(execution, objectMapper.readTree(configJson));
        }
        catch (JacksonException exception)
        {
            throw new ServiceException("CEL 执行快照配置无法解析", HttpStatus.ERROR);
        }
    }

    /** 类型化 CEL 输入变量声明。 */
    private record CelVariable(String name, String type)
    {
    }

    /** 规范配置与已检查 CEL AST 的内部编译结果。 */
    private record CelCompiledConfig(String normalizedConfig, CelAbstractSyntaxTree ast,
            String resultVariable, String resultType, List<CelVariable> variables)
    {
    }
}
