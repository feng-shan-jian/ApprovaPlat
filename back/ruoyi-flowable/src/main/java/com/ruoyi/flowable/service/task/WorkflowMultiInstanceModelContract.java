package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.BaseElement;
import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.springframework.util.StringUtils;

/**
 * 受控多实例 BPMN 白名单契约，只接受固定并行 UserTask 结构、受控成员来源和 ALL/ANY 完成条件。
 */
public final class WorkflowMultiInstanceModelContract
{
    /** 多实例办理人固定表达式。 */
    public static final String ASSIGNEE_EXPRESSION = "${assignee}";

    /** 多实例元素变量固定名称。 */
    public static final String ELEMENT_VARIABLE = "assignee";

    /** 多实例集合固定表达式。 */
    public static final String COLLECTION_EXPRESSION =
            "${multiInstanceHandler.getUserIds(execution)}";

    /** 发起页面受控成员集合固定表达式。 */
    public static final String START_COLLECTION_EXPRESSION =
            "${multiInstanceHandler.getStartUserIds(execution)}";

    /** 设计时指定用户、角色或部门的受控集合固定表达式。 */
    public static final String CONFIGURED_COLLECTION_EXPRESSION =
            "${multiInstanceHandler.getConfiguredUserIds(execution)}";

    /** 设计时指定身份类型的 Flowable 平台保留属性。 */
    public static final String IDENTITY_TYPE_PROPERTY =
            "approva.multiInstance.identityType";

    /** 设计时指定身份主键集合的 Flowable 平台保留属性。 */
    public static final String IDENTITY_IDS_PROPERTY =
            "approva.multiInstance.identityIds";

    /** 指定身份配置允许引用的最大目标数量；角色或部门展开后的用户仍需单独满足 100 人上限。 */
    public static final int MAX_CONFIGURED_IDENTITIES = 100;

    /** 100 个 Long 主键及分隔符所需的属性值安全上限。 */
    public static final int MAX_IDENTITY_PROPERTY_LENGTH = 2048;

    /** 指定身份配置的完整保留属性集合。 */
    private static final Set<String> IDENTITY_PROPERTY_NAMES = Set.of(
            IDENTITY_TYPE_PROPERTY, IDENTITY_IDS_PROPERTY);

    /** 固定成员多实例集合表达式的严格语法，成员只能是以逗号连接的规范正整数用户主键。 */
    private static final Pattern FIXED_COLLECTION_EXPRESSION_PATTERN = Pattern.compile(
            "\\$\\{multiInstanceHandler\\.getFixedUserIds\\(execution, '([1-9][0-9]*(?:,[1-9][0-9]*)*)'\\)\\}");
    /** 会签固定完成条件。 */
    public static final String ALL_COMPLETION_CONDITION =
            "${nrOfCompletedInstances == nrOfInstances}";

    /** 或签固定完成条件。 */
    public static final String ANY_COMPLETION_CONDITION =
            "${nrOfCompletedInstances > 0}";

    /**
     * 禁止实例化纯模型契约工具类。
     *
     * @return 无返回值，调用时始终抛出 AssertionError
     */
    private WorkflowMultiInstanceModelContract()
    {
        throw new AssertionError("动态多实例模型契约类不能实例化");
    }

    /** 设计时指定身份的受控类型。 */
    public enum ConfiguredIdentityType
    {
        USER,
        ROLE,
        DEPT
    }

    /**
     * 保存已经通过严格属性解析的指定身份配置。
     *
     * @param type ConfiguredIdentityType，USER、ROLE 或 DEPT
     * @param targetIds List&lt;Long&gt;，保持设计顺序且唯一的正式身份主键
     */
    public record ConfiguredIdentity(ConfiguredIdentityType type, List<Long> targetIds)
    {
        /**
         * 创建并冻结已经通过严格解析的指定身份配置。
         *
         * @param type ConfiguredIdentityType，USER、ROLE 或 DEPT
         * @param targetIds List&lt;Long&gt;，保持设计顺序且唯一的正式身份主键
         * @return 无返回值；类型或主键集合为空时拒绝构造
         */
        public ConfiguredIdentity
        {
            Objects.requireNonNull(type, "指定多实例身份类型不能为空");
            targetIds = List.copyOf(Objects.requireNonNull(
                    targetIds, "指定多实例身份主键不能为空"));
        }
    }

    /**
     * 判断循环配置是否声明了受控动态多实例 handler，供保存门禁和运行时完成门禁统一分类。
     *
     * @param loop MultiInstanceLoopCharacteristics，可为空的多实例循环配置
     * @return boolean，inputDataItem 或 collectionString 去空白后命中固定表达式时返回 true
     */
    public static boolean usesControlledHandler(MultiInstanceLoopCharacteristics loop)
    {
        if (loop == null)
        {
            return false;
        }
        String inputDataItem = loop.getInputDataItem() == null
                ? "" : loop.getInputDataItem().trim();
        String collectionString = loop.getCollectionString() == null
                ? "" : loop.getCollectionString().trim();
        return COLLECTION_EXPRESSION.equals(inputDataItem)
                || START_COLLECTION_EXPRESSION.equals(inputDataItem)
                || CONFIGURED_COLLECTION_EXPRESSION.equals(inputDataItem)
                || FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(inputDataItem).matches()
                || COLLECTION_EXPRESSION.equals(collectionString)
                || START_COLLECTION_EXPRESSION.equals(collectionString)
                || CONFIGURED_COLLECTION_EXPRESSION.equals(collectionString)
                || FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(collectionString).matches();
    }

    /**
     * 判断循环是否使用由前置任务动态提交成员的受控集合。
     *
     * @param loop MultiInstanceLoopCharacteristics，可为空的多实例循环配置。
     * @return boolean，仅精确匹配动态集合表达式时返回 true。
     */
    public static boolean usesDynamicHandler(MultiInstanceLoopCharacteristics loop)
    {
        if (loop == null)
        {
            return false;
        }
        return COLLECTION_EXPRESSION.equals(trimToEmpty(loop.getInputDataItem()))
                || COLLECTION_EXPRESSION.equals(trimToEmpty(loop.getCollectionString()));
    }

    /**
     * 判断循环是否使用发起页面专用人员字段提供的受控集合。
     *
     * @param loop MultiInstanceLoopCharacteristics，可为空的多实例循环配置。
     * @return boolean，仅精确匹配发起时受控集合表达式时返回 true。
     */
    public static boolean usesStartHandler(MultiInstanceLoopCharacteristics loop)
    {
        if (loop == null)
        {
            return false;
        }
        return START_COLLECTION_EXPRESSION.equals(trimToEmpty(loop.getInputDataItem()))
                || START_COLLECTION_EXPRESSION.equals(trimToEmpty(loop.getCollectionString()));
    }

    /**
     * 判断循环是否使用设计时指定用户、角色或部门的受控集合。
     *
     * @param loop MultiInstanceLoopCharacteristics，可为空的多实例循环配置
     * @return boolean，仅精确匹配指定身份集合表达式时返回 true
     */
    public static boolean usesConfiguredHandler(MultiInstanceLoopCharacteristics loop)
    {
        if (loop == null)
        {
            return false;
        }
        return CONFIGURED_COLLECTION_EXPRESSION.equals(trimToEmpty(loop.getInputDataItem()))
                || CONFIGURED_COLLECTION_EXPRESSION.equals(
                        trimToEmpty(loop.getCollectionString()));
    }

    /**
     * 判断循环是否使用由 BPMN 预设成员初始化的受控集合。
     *
     * @param loop MultiInstanceLoopCharacteristics，可为空的多实例循环配置。
     * @return boolean，仅精确匹配固定成员集合表达式时返回 true。
     */
    public static boolean usesFixedHandler(MultiInstanceLoopCharacteristics loop)
    {
        if (loop == null)
        {
            return false;
        }
        return FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(
                trimToEmpty(loop.getInputDataItem())).matches()
                || FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(
                        trimToEmpty(loop.getCollectionString())).matches();
    }

    /**
     * 从固定成员集合表达式解析有序且无重复的用户主键。
     *
     * @param loop MultiInstanceLoopCharacteristics，已经从 BPMN 节点读取的循环配置。
     * @return List<Long> 固定成员的规范正整数用户主键。
     */
    public static List<Long> requireFixedUserIds(MultiInstanceLoopCharacteristics loop)
    {
        if (loop == null)
        {
            throw new IllegalArgumentException("固定多实例集合配置不合法");
        }
        String inputDataItem = trimToEmpty(loop.getInputDataItem());
        String collectionString = trimToEmpty(loop.getCollectionString());
        Matcher inputMatcher = FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(inputDataItem);
        Matcher collectionMatcher = FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(collectionString);
        if (inputMatcher.matches() == collectionMatcher.matches())
        {
            throw new IllegalArgumentException("固定多实例集合配置不合法");
        }
        String userIdsText = inputMatcher.matches() ? inputMatcher.group(1)
                : collectionMatcher.group(1);
        String[] userIdTexts = userIdsText.split(",", -1);
        if (userIdTexts.length == 0 || userIdTexts.length > 100)
        {
            throw new IllegalArgumentException("固定多实例成员数量不合法");
        }
        LinkedHashSet<Long> uniqueUserIds = new LinkedHashSet<>();
        for (String userIdText : userIdTexts)
        {
            long userId;
            try
            {
                userId = Long.parseLong(userIdText);
            }
            catch (NumberFormatException exception)
            {
                throw new IllegalArgumentException("固定多实例成员主键不合法", exception);
            }
            if (userId <= 0 || !Long.toString(userId).equals(userIdText)
                    || !uniqueUserIds.add(userId))
            {
                throw new IllegalArgumentException("固定多实例成员主键不合法");
            }
        }
        return List.copyOf(new ArrayList<>(uniqueUserIds));
    }

    /**
     * 判断属性名是否属于指定多实例身份的受控平台命名空间。
     *
     * @param propertyName String，Flowable property 名称
     * @return boolean，命中身份类型或身份主键属性时返回 true
     */
    public static boolean isReservedProperty(String propertyName)
    {
        return IDENTITY_PROPERTY_NAMES.contains(propertyName);
    }

    /**
     * 判断任意 BPMN 元素是否声明了指定多实例身份属性。
     *
     * @param element BaseElement，待检查的流程元素
     * @return boolean，至少存在一个指定身份保留属性时返回 true
     */
    public static boolean hasConfiguredIdentityProperties(BaseElement element)
    {
        return !readConfiguredIdentityProperties(element).isEmpty();
    }

    /**
     * 从用户任务的 Flowable properties 中严格解析设计时指定身份。
     *
     * @param task UserTask，使用 CONFIGURED_COLLECTION_EXPRESSION 的受控多实例任务
     * @return ConfiguredIdentity，不可修改且保持作者顺序的身份类型和目标主键
     */
    public static ConfiguredIdentity requireConfiguredIdentity(UserTask task)
    {
        Map<String, String> values = readConfiguredIdentityProperties(task);
        if (values.size() != IDENTITY_PROPERTY_NAMES.size()
                || !values.keySet().equals(IDENTITY_PROPERTY_NAMES))
        {
            throw new IllegalArgumentException("指定多实例身份配置不完整");
        }

        ConfiguredIdentityType type;
        try
        {
            type = ConfiguredIdentityType.valueOf(
                    trimToEmpty(values.get(IDENTITY_TYPE_PROPERTY)));
        }
        catch (IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("指定多实例身份类型不合法", exception);
        }
        List<Long> targetIds = requireCanonicalPositiveIds(
                values.get(IDENTITY_IDS_PROPERTY), "指定多实例身份主键不合法");
        return new ConfiguredIdentity(type, targetIds);
    }

    /**
     * 核验节点完整满足生产动态多实例白名单并解析完成模式。
     *
     * @param flowElement FlowElement，部署模型中的目标活动
     * @return WorkflowMultiInstanceMode，固定 ALL 或 ANY 模式
     */
    public static WorkflowMultiInstanceMode requireMode(FlowElement flowElement)
    {
        // 动态目标必须在来源完成事务内同步创建；skip、异步或非排他语义都会破坏即时写后对账。
        if (!(flowElement instanceof UserTask userTask)
                || !(userTask.getParentContainer() instanceof org.flowable.bpmn.model.Process)
                || userTask.isForCompensation()
                || StringUtils.hasText(userTask.getSkipExpression())
                || hasAsyncContinuation(userTask)
                || (userTask.getBoundaryEvents() != null
                    && !userTask.getBoundaryEvents().isEmpty()))
        {
            throw new IllegalArgumentException("当前节点不支持动态多实例");
        }
        WorkflowMultiInstanceVariables.requireActivityId(userTask.getId());
        MultiInstanceLoopCharacteristics loop = userTask.getLoopCharacteristics();
        boolean dynamicSource = usesDynamicHandler(loop);
        boolean startSource = usesStartHandler(loop);
        boolean fixedSource = usesFixedHandler(loop);
        boolean configuredSource = usesConfiguredHandler(loop);
        boolean configuredProperties = hasConfiguredIdentityProperties(userTask);
        if (loop == null || loop.isSequential() || loop.isNoWaitStatesAsyncLeave()
                || !Objects.equals(ASSIGNEE_EXPRESSION, userTask.getAssignee())
                || (userTask.getCandidateUsers() != null
                    && !userTask.getCandidateUsers().isEmpty())
                || (userTask.getCandidateGroups() != null
                    && !userTask.getCandidateGroups().isEmpty())
                || (dynamicSource ? 1 : 0) + (startSource ? 1 : 0)
                    + (fixedSource ? 1 : 0) + (configuredSource ? 1 : 0) != 1
                || (dynamicSource && !Objects.equals(COLLECTION_EXPRESSION,
                    loop.getInputDataItem()))
                || (startSource && !Objects.equals(START_COLLECTION_EXPRESSION,
                    loop.getInputDataItem()))
                || (configuredSource && !Objects.equals(CONFIGURED_COLLECTION_EXPRESSION,
                    loop.getInputDataItem()))
                || (fixedSource && !FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(
                    trimToEmpty(loop.getInputDataItem())).matches())
                || configuredSource != configuredProperties
                || !Objects.equals(ELEMENT_VARIABLE, loop.getElementVariable())
                || StringUtils.hasText(loop.getCollectionString())
                || StringUtils.hasText(loop.getLoopCardinality())
                || StringUtils.hasText(loop.getElementIndexVariable())
                || loop.getHandler() != null || loop.getAggregations() != null)
        {
            throw new IllegalArgumentException("当前节点不支持动态多实例");
        }
        if (fixedSource)
        {
            requireFixedUserIds(loop);
        }
        if (configuredSource)
        {
            requireConfiguredIdentity(userTask);
        }
        if (Objects.equals(ALL_COMPLETION_CONDITION, loop.getCompletionCondition()))
        {
            return WorkflowMultiInstanceMode.ALL;
        }
        if (Objects.equals(ANY_COMPLETION_CONDITION, loop.getCompletionCondition()))
        {
            return WorkflowMultiInstanceMode.ANY;
        }
        throw new IllegalArgumentException("当前节点不支持动态多实例");
    }

    /**
     * 判断动态多实例目标是否声明进入、离开或非排他的异步执行语义。
     *
     * @param userTask UserTask，待核验的动态多实例目标用户任务
     * @return boolean，任一异步或非排他标识存在时返回 true
     */
    private static boolean hasAsyncContinuation(UserTask userTask)
    {
        // 目标任务必须在来源完成事务内同步创建，异步或非排他配置会破坏任务、execution 与快照的即时对账。
        return userTask.isAsynchronous()
                || userTask.isAsynchronousLeave()
                || userTask.isNotExclusive()
                || userTask.isAsynchronousLeaveNotExclusive();
    }

    /**
     * 读取元素 properties 容器中的指定身份属性，并拒绝模型对象中的重复配置。
     *
     * @param element BaseElement，可能携带 Flowable properties 的流程元素
     * @return Map&lt;String,String&gt;，只包含指定多实例身份属性的不可修改映射
     */
    private static Map<String, String> readConfiguredIdentityProperties(BaseElement element)
    {
        if (element == null || element.getExtensionElements() == null)
        {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
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
                String name = property.getAttributeValue(null, "name");
                if (!isReservedProperty(name))
                {
                    continue;
                }
                if (values.containsKey(name))
                {
                    throw new IllegalArgumentException("指定多实例身份属性不能重复");
                }
                String value = property.getAttributeValue(null, "value");
                if (value == null)
                {
                    throw new IllegalArgumentException("指定多实例身份属性值不能为空");
                }
                values.put(name, value);
            }
        }
        return Map.copyOf(values);
    }

    /**
     * 将逗号分隔文本严格解析为 1 至 100 个规范正整数主键。
     *
     * @param rawIds String，Flowable property 中的原始主键文本
     * @param message String，格式、重复、溢出或数量非法时的稳定错误
     * @return List&lt;Long&gt;，保持作者顺序且不可修改的唯一主键
     */
    private static List<Long> requireCanonicalPositiveIds(String rawIds, String message)
    {
        String[] parts = rawIds == null ? new String[0] : rawIds.split(",", -1);
        if (parts.length < 1 || parts.length > MAX_CONFIGURED_IDENTITIES)
        {
            throw new IllegalArgumentException(message);
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String part : parts)
        {
            long id;
            try
            {
                id = Long.parseLong(part);
            }
            catch (NumberFormatException exception)
            {
                throw new IllegalArgumentException(message, exception);
            }
            if (id <= 0 || !Long.toString(id).equals(part) || !ids.add(id))
            {
                throw new IllegalArgumentException(message);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * 将 BPMN 可选字符串统一为无空白的安全比较值。
     *
     * @param value String，可能为空的 BPMN 属性值。
     * @return String，去除首尾空白后的值；空值返回空串。
     */
    private static String trimToEmpty(String value)
    {
        return value == null ? "" : value.trim();
    }
}
