package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.UserTask;
import org.springframework.util.StringUtils;

/**
 * 动态多实例 BPMN 白名单契约，只接受固定并行 UserTask 结构和 ALL/ANY 完成条件。
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
                || FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(inputDataItem).matches()
                || COLLECTION_EXPRESSION.equals(collectionString)
                || START_COLLECTION_EXPRESSION.equals(collectionString)
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
        if (loop == null || loop.isSequential() || loop.isNoWaitStatesAsyncLeave()
                || !Objects.equals(ASSIGNEE_EXPRESSION, userTask.getAssignee())
                || (dynamicSource ? 1 : 0) + (startSource ? 1 : 0)
                    + (fixedSource ? 1 : 0) != 1
                || (dynamicSource && !Objects.equals(COLLECTION_EXPRESSION,
                    loop.getInputDataItem()))
                || (startSource && !Objects.equals(START_COLLECTION_EXPRESSION,
                    loop.getInputDataItem()))
                || (fixedSource && !FIXED_COLLECTION_EXPRESSION_PATTERN.matcher(
                    trimToEmpty(loop.getInputDataItem())).matches())
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
