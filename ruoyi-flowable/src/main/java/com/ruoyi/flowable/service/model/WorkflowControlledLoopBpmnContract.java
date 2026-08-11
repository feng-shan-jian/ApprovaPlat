package com.ruoyi.flowable.service.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.UserTask;

/**
 * 受控重复审批循环在作者 BPMN 中使用的固定属性、语义校验和运行变量命名契约。
 */
public final class WorkflowControlledLoopBpmnContract
{
    /** 平台保留的受控循环属性前缀。 */
    public static final String PROPERTY_PREFIX = "approva.controlledLoop.";
    /** 是否启用受控循环。 */
    public static final String ENABLED = PROPERTY_PREFIX + "enabled";
    /** 决定再次进入或退出的任务表单变量。 */
    public static final String DECISION_VARIABLE = PROPERTY_PREFIX + "decisionVariable";
    /** 再次进入循环时必须精确匹配的标量值。 */
    public static final String REPEAT_VALUE = PROPERTY_PREFIX + "repeatValue";
    /** 退出循环时必须精确匹配的标量值。 */
    public static final String EXIT_VALUE = PROPERTY_PREFIX + "exitValue";
    /** 单个流程实例允许完成该节点的最大轮次。 */
    public static final String MAX_ITERATIONS = PROPERTY_PREFIX + "maxIterations";

    /** 作者 BPMN 必须完整提供的受控属性集合。 */
    public static final Set<String> RESERVED_PROPERTIES = Set.of(
            ENABLED, DECISION_VARIABLE, REPEAT_VALUE, EXIT_VALUE, MAX_ITERATIONS);

    /** 表单变量名与平台其他流程变量使用同一安全标识边界。 */
    private static final Pattern VARIABLE_PATTERN =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    /** 最大轮次既防止无限循环，也避免单实例历史和表单响应失控。 */
    private static final int MIN_ITERATIONS = 2;
    private static final int MAX_ITERATIONS_LIMIT = 50;
    /** 单个条件值只允许可理解且有界的标量文本。 */
    private static final int MAX_VALUE_LENGTH = 128;
    /** 运行变量使用独立保留前缀，客户端表单字段无法覆盖。 */
    private static final String ROUTE_VARIABLE_PREFIX = "__approva_loop_route_";
    private static final String ITERATION_VARIABLE_PREFIX = "__approva_loop_iteration_";
    private static final String GENERATED_ID_PREFIX = "__approva_loop_";

    private WorkflowControlledLoopBpmnContract()
    {
    }

    /**
     * 判断属性名是否属于平台受控循环保留集合。
     *
     * @param propertyName String，待检查的 flowable:property 名称
     * @return boolean，名称是受控循环固定属性时返回 true
     */
    public static boolean isReservedProperty(String propertyName)
    {
        return RESERVED_PROPERTIES.contains(propertyName);
    }

    /**
     * 判断业务表单变量是否试图覆盖受控循环运行时保留状态。
     * @param variableName String，待检查的表单或流程变量名
     * @return boolean，命中循环运行变量前缀时返回 true
     */
    public static boolean isReservedRuntimeVariable(String variableName)
    {
        return variableName != null && (variableName.startsWith(ROUTE_VARIABLE_PREFIX)
                || variableName.startsWith(ITERATION_VARIABLE_PREFIX));
    }

    /**
     * 判断任意 BPMN 元素是否声明了受控循环平台属性。
     *
     * @param element BaseElement，待检查的流程元素
     * @return boolean，至少存在一个受控循环保留属性时返回 true
     */
    public static boolean hasReservedProperties(BaseElement element)
    {
        if (element == null || element.getExtensionElements() == null)
        {
            return false;
        }
        for (ExtensionElement container : element.getExtensionElements()
                .getOrDefault("properties", List.of()))
        {
            if (container == null || container.getChildElements() == null)
            {
                continue;
            }
            for (ExtensionElement property : container.getChildElements()
                    .getOrDefault("property", List.of()))
            {
                if (isReservedProperty(property.getAttributeValue(null, "name")))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 从用户任务扩展属性读取并校验完整受控循环配置。
     *
     * @param processKey String，用户任务所属可执行流程标识
     * @param task UserTask，待读取的作者 BPMN 用户任务
     * @return Optional&lt;AuthorConfig&gt;，未声明任何受控属性时为空，完整声明时返回不可变配置
     */
    public static Optional<AuthorConfig> readAuthorConfig(String processKey, UserTask task)
    {
        Map<String, String> values = readPropertyValues(task);
        Set<String> present = new HashSet<>(values.keySet());
        present.retainAll(RESERVED_PROPERTIES);
        if (present.isEmpty())
        {
            return Optional.empty();
        }
        if (!present.equals(RESERVED_PROPERTIES))
        {
            throw new IllegalArgumentException("受控循环配置不完整");
        }
        if (!"true".equals(values.get(ENABLED)))
        {
            throw new IllegalArgumentException("受控循环启用标志不合法");
        }

        String decisionVariable = requireText(values.get(DECISION_VARIABLE), "循环判断字段不能为空");
        if (!VARIABLE_PATTERN.matcher(decisionVariable).matches())
        {
            throw new IllegalArgumentException("循环判断字段不合法");
        }
        String repeatValue = requireConditionValue(values.get(REPEAT_VALUE), "再次进入条件值不合法");
        String exitValue = requireConditionValue(values.get(EXIT_VALUE), "退出条件值不合法");
        if (repeatValue.equals(exitValue))
        {
            throw new IllegalArgumentException("再次进入和退出条件不能相同");
        }
        int maxIterations;
        try
        {
            maxIterations = Integer.parseInt(requireText(
                    values.get(MAX_ITERATIONS), "最大循环轮次不能为空"));
        }
        catch (NumberFormatException exception)
        {
            throw new IllegalArgumentException("最大循环轮次必须是整数", exception);
        }
        if (maxIterations < MIN_ITERATIONS || maxIterations > MAX_ITERATIONS_LIMIT)
        {
            throw new IllegalArgumentException("最大循环轮次必须是 2 至 50");
        }

        String normalizedProcessKey = requireText(processKey, "循环所属流程标识不能为空");
        String activityId = requireText(task == null ? null : task.getId(), "循环节点标识不能为空");
        String token = stableToken(normalizedProcessKey, activityId);
        return Optional.of(new AuthorConfig(normalizedProcessKey, activityId,
                task.getName() == null ? "" : task.getName().trim(), decisionVariable,
                repeatValue, exitValue, maxIterations,
                ROUTE_VARIABLE_PREFIX + token, ITERATION_VARIABLE_PREFIX + token,
                GENERATED_ID_PREFIX + token));
    }

    /**
     * 从编译后的用户任务移除作者可编辑循环属性，运行时只信任部署制品资源。
     *
     * @param task UserTask，正在生成执行资源的用户任务
     * @return void，无返回值；非循环普通属性和其他扩展元素保持不变
     */
    public static void removeAuthorProperties(UserTask task)
    {
        if (task == null || task.getExtensionElements() == null)
        {
            return;
        }
        Map<String, List<ExtensionElement>> extensionElements = task.getExtensionElements();
        List<ExtensionElement> containers = extensionElements.get("properties");
        if (containers == null || containers.isEmpty())
        {
            return;
        }
        List<ExtensionElement> remainingContainers = new ArrayList<>();
        for (ExtensionElement container : containers)
        {
            Map<String, List<ExtensionElement>> children = container.getChildElements();
            if (children == null)
            {
                remainingContainers.add(container);
                continue;
            }
            List<ExtensionElement> properties = children.get("property");
            if (properties == null)
            {
                remainingContainers.add(container);
                continue;
            }
            List<ExtensionElement> remainingProperties = properties.stream()
                    .filter(property -> !isReservedProperty(property.getAttributeValue(null, "name")))
                    .toList();
            if (!remainingProperties.isEmpty())
            {
                Map<String, List<ExtensionElement>> copiedChildren = new HashMap<>(children);
                copiedChildren.put("property", new ArrayList<>(remainingProperties));
                container.setChildElements(copiedChildren);
                remainingContainers.add(container);
            }
        }
        Map<String, List<ExtensionElement>> copiedExtensions = new HashMap<>(extensionElements);
        if (remainingContainers.isEmpty())
        {
            copiedExtensions.remove("properties");
        }
        else
        {
            copiedExtensions.put("properties", remainingContainers);
        }
        task.setExtensionElements(copiedExtensions);
    }

    /**
     * 读取 Flowable properties 容器中的固定名值属性，并拒绝解析结果内部重复。
     *
     * @param task UserTask，作者 BPMN 用户任务
     * @return Map&lt;String,String&gt;，属性名到原始值的不可修改映射
     */
    private static Map<String, String> readPropertyValues(UserTask task)
    {
        if (task == null || task.getExtensionElements() == null)
        {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        List<ExtensionElement> containers = task.getExtensionElements()
                .getOrDefault("properties", List.of());
        for (ExtensionElement container : containers)
        {
            if (container == null || container.getChildElements() == null)
            {
                continue;
            }
            for (ExtensionElement property : container.getChildElements()
                    .getOrDefault("property", List.of()))
            {
                String name = property.getAttributeValue(null, "name");
                if (!isReservedProperty(name))
                {
                    continue;
                }
                String old = values.put(name, property.getAttributeValue(null, "value"));
                if (old != null)
                {
                    throw new IllegalArgumentException("受控循环属性不能重复");
                }
            }
        }
        return Map.copyOf(values);
    }

    /**
     * 规范受控循环必填文本。
     *
     * @param value String，待规范值
     * @param message String，空值时使用的稳定业务提示
     * @return String，去除首尾空白的非空文本
     */
    private static String requireText(String value, String message)
    {
        if (value == null || value.trim().isEmpty())
        {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /**
     * 校验再次进入或退出条件值必须是有界单行标量文本。
     *
     * @param value String，作者配置的条件值
     * @param message String，失败时使用的稳定业务提示
     * @return String，规范化后的条件值
     */
    private static String requireConditionValue(String value, String message)
    {
        String normalized = requireText(value, message);
        if (normalized.length() > MAX_VALUE_LENGTH
                || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0)
        {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    /**
     * 根据流程和节点生成固定长度稳定令牌，避免把作者标识直接拼入运行变量和生成元素主键。
     *
     * @param processKey String，可执行流程标识
     * @param activityId String，用户任务节点标识
     * @return String，SHA-256 前 24 位小写十六进制文本
     */
    private static String stableToken(String processKey, String activityId)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((processKey + "\u0000" + activityId).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int index = 0; index < 12; index++)
            {
                result.append(Character.forDigit((digest[index] >>> 4) & 0x0f, 16));
                result.append(Character.forDigit(digest[index] & 0x0f, 16));
            }
            return result.toString();
        }
        catch (NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    /**
     * 通过作者语义校验的受控循环不可变配置。
     *
     * @param processKey String，可执行流程标识
     * @param activityId String，循环用户任务标识
     * @param activityName String，部署时节点名称快照
     * @param decisionVariable String，判断再次进入或退出的任务表单变量
     * @param repeatValue String，再次进入条件的精确标量值
     * @param exitValue String，退出条件的精确标量值
     * @param maxIterations int，允许完成该节点的最大轮次
     * @param routeVariable String，编译网关读取的服务端保留布尔变量
     * @param iterationVariable String，当前已完成轮次的服务端保留变量
     * @param generatedIdBase String，编译生成元素使用的稳定主键前缀
     */
    public record AuthorConfig(String processKey, String activityId, String activityName,
            String decisionVariable, String repeatValue, String exitValue, int maxIterations,
            String routeVariable, String iterationVariable, String generatedIdBase)
    {
    }
}
