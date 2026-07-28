package com.ruoyi.flowable.service.task;

import java.util.Objects;
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
                || COLLECTION_EXPRESSION.equals(collectionString);
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
        if (loop == null || loop.isSequential() || loop.isNoWaitStatesAsyncLeave()
                || !Objects.equals(ASSIGNEE_EXPRESSION, userTask.getAssignee())
                || !Objects.equals(COLLECTION_EXPRESSION, loop.getInputDataItem())
                || !Objects.equals(ELEMENT_VARIABLE, loop.getElementVariable())
                || StringUtils.hasText(loop.getCollectionString())
                || StringUtils.hasText(loop.getLoopCardinality())
                || StringUtils.hasText(loop.getElementIndexVariable())
                || loop.getHandler() != null || loop.getAggregations() != null)
        {
            throw new IllegalArgumentException("当前节点不支持动态多实例");
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
}
