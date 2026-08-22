package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Optional;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.UserTask;
import org.springframework.util.StringUtils;

/**
 * 动态下一办理人模型契约，统一识别必须由当前普通用户任务初始化的动态多实例后继。
 */
public final class WorkflowNextTaskAssignmentContract
{
    /**
     * 禁止实例化纯模型契约工具类。
     *
     * @return 无返回值，调用时始终抛出 AssertionError
     */
    private WorkflowNextTaskAssignmentContract()
    {
        throw new AssertionError("动态下一办理人模型契约类不能实例化");
    }

    /**
     * 识别当前普通用户任务是否通过唯一无条件顺序流直连受控动态多实例节点。
     *
     * @param process Process，已部署定义中与流程定义 key 对应的可执行流程
     * @param sourceTaskDefinitionKey String，当前活动用户任务的 BPMN 节点主键
     * @return Optional&lt;RequiredMultiInstanceTarget&gt;，需要成员初始化时返回目标节点和 ALL/ANY 模式
     */
    public static Optional<RequiredMultiInstanceTarget> findRequiredMultiInstanceTarget(
            org.flowable.bpmn.model.Process process, String sourceTaskDefinitionKey)
    {
        Optional<UserTask> target = findDirectUserTaskTarget(process, sourceTaskDefinitionKey);
        return toRequiredMultiInstanceTarget(target);
    }

    /**
     * 复用完成链已定位的当前 UserTask，识别唯一无条件直连的受控动态多实例节点。
     *
     * @param process Process，生命周期服务已唯一读取的当前部署 BPMN Process
     * @param sourceTask UserTask，生命周期服务已在同一 Process 中定位的当前任务
     * @return Optional&lt;RequiredMultiInstanceTarget&gt;，需要成员初始化时返回目标节点和 ALL/ANY 模式
     */
    public static Optional<RequiredMultiInstanceTarget> findRequiredMultiInstanceTarget(
            org.flowable.bpmn.model.Process process, UserTask sourceTask)
    {
        return toRequiredMultiInstanceTarget(findDirectUserTaskTarget(process, sourceTask));
    }

    /**
     * 把安全直接后继归类为受控动态多实例目标，普通或静态后继保持空结果。
     *
     * @param target Optional&lt;UserTask&gt;，已通过唯一无条件直连约束的后继节点
     * @return Optional&lt;RequiredMultiInstanceTarget&gt;，受控动态多实例目标及完成模式
     */
    private static Optional<RequiredMultiInstanceTarget> toRequiredMultiInstanceTarget(
            Optional<UserTask> target)
    {
        if (target.isEmpty() || target.get().getLoopCharacteristics() == null)
        {
            return Optional.empty();
        }
        UserTask targetTask = target.get();
        if (!WorkflowMultiInstanceModelContract.usesDynamicHandler(
                targetTask.getLoopCharacteristics()))
        {
            return Optional.empty();
        }
        WorkflowMultiInstanceMode mode = WorkflowMultiInstanceModelContract.requireMode(targetTask);
        return Optional.of(new RequiredMultiInstanceTarget(targetTask.getId(), mode));
    }

    /**
     * 解析详情页对当前任务公开的动态下一办理人策略，避免前端依赖试错请求推断模型能力。
     *
     * @param process Process，已部署定义中与流程定义 key 对应的可执行流程
     * @param sourceTaskDefinitionKey String，当前活动用户任务的 BPMN 节点主键
     * @return NextUserAssignmentPolicy，仅返回 DISABLED、OPTIONAL、REQUIRED_ALL 或 REQUIRED_ANY
     */
    public static NextUserAssignmentPolicy resolvePolicy(
            org.flowable.bpmn.model.Process process, String sourceTaskDefinitionKey)
    {
        Optional<UserTask> target = findDirectUserTaskTarget(
                process, sourceTaskDefinitionKey);
        if (target.isEmpty())
        {
            return NextUserAssignmentPolicy.DISABLED;
        }
        UserTask targetTask = target.get();
        if (targetTask.getLoopCharacteristics() == null)
        {
            return NextUserAssignmentPolicy.OPTIONAL;
        }
        if (!WorkflowMultiInstanceModelContract.usesDynamicHandler(
                targetTask.getLoopCharacteristics()))
        {
            // 固定会签或或签成员已固化在部署 BPMN，不向当前办理人开放覆盖入口。
            return NextUserAssignmentPolicy.DISABLED;
        }
        WorkflowMultiInstanceMode mode = WorkflowMultiInstanceModelContract
                .requireMode(targetTask);
        return mode == WorkflowMultiInstanceMode.ALL
                ? NextUserAssignmentPolicy.REQUIRED_ALL
                : NextUserAssignmentPolicy.REQUIRED_ANY;
    }

    /**
     * 查找普通来源任务经唯一无条件顺序流直连的用户任务，集中冻结可动态分配的安全拓扑。
     *
     * @param process Process，已部署可执行流程
     * @param sourceTaskDefinitionKey String，当前活动用户任务节点主键
     * @return Optional&lt;UserTask&gt;，拓扑满足约束时返回真实后继用户任务，否则为空
     */
    private static Optional<UserTask> findDirectUserTaskTarget(
            org.flowable.bpmn.model.Process process, String sourceTaskDefinitionKey)
    {
        if (process == null || !StringUtils.hasText(sourceTaskDefinitionKey))
        {
            throw new IllegalArgumentException("动态下一办理人模型上下文不完整");
        }
        FlowElement sourceElement = process.getFlowElement(sourceTaskDefinitionKey, false);
        if (!(sourceElement instanceof UserTask sourceTask)
                || sourceTask.getLoopCharacteristics() != null)
        {
            return Optional.empty();
        }
        return findDirectUserTaskTarget(process, sourceTask);
    }

    /**
     * 从已定位来源节点查找唯一无条件直连用户任务，并核验来源和目标都属于主 Process。
     *
     * @param process Process，已部署可执行流程
     * @param sourceTask UserTask，完成链已定位的当前来源任务
     * @return Optional&lt;UserTask&gt;，拓扑满足约束时返回真实后继用户任务，否则为空
     */
    private static Optional<UserTask> findDirectUserTaskTarget(
            org.flowable.bpmn.model.Process process, UserTask sourceTask)
    {
        if (process == null || sourceTask == null || !StringUtils.hasText(sourceTask.getId()))
        {
            throw new IllegalArgumentException("动态下一办理人模型上下文不完整");
        }
        if (sourceTask.getParentContainer() != process
                || sourceTask.getLoopCharacteristics() != null)
        {
            return Optional.empty();
        }
        List<SequenceFlow> outgoingFlows = sourceTask.getOutgoingFlows();
        if (outgoingFlows == null || outgoingFlows.size() != 1)
        {
            return Optional.empty();
        }
        SequenceFlow outgoingFlow = outgoingFlows.get(0);
        if (outgoingFlow == null || StringUtils.hasText(outgoingFlow.getConditionExpression()))
        {
            return Optional.empty();
        }
        FlowElement targetElement = outgoingFlow.getTargetFlowElement();
        if (targetElement == null && StringUtils.hasText(outgoingFlow.getTargetRef()))
        {
            targetElement = process.getFlowElement(outgoingFlow.getTargetRef(), false);
        }
        if (!(targetElement instanceof UserTask targetTask)
                || process.getFlowElement(targetTask.getId(), false) == null)
        {
            return Optional.empty();
        }
        return Optional.of(targetTask);
    }

    /**
     * 详情与完成接口共用的动态下一办理人能力枚举。
     */
    public enum NextUserAssignmentPolicy
    {
        /** 当前任务拓扑不允许动态指定下一办理人。 */
        DISABLED,
        /** 可选覆盖唯一普通后继用户任务的默认分配。 */
        OPTIONAL,
        /** 必须选择会签成员，全部成员完成后通过。 */
        REQUIRED_ALL,
        /** 必须选择或签成员，任一成员完成后通过。 */
        REQUIRED_ANY
    }

    /**
     * 必须在完成来源任务前初始化成员集合的动态多实例目标。
     *
     * @param taskDefinitionKey String，受控动态多实例用户任务节点主键
     * @param mode WorkflowMultiInstanceMode，固定 ALL 或 ANY 完成模式
     */
    public record RequiredMultiInstanceTarget(String taskDefinitionKey,
            WorkflowMultiInstanceMode mode)
    {
        /**
         * 核验目标节点和模式，防止不完整能力投影进入完成动作。
         *
         * @param taskDefinitionKey String，受控动态多实例用户任务节点主键
         * @param mode WorkflowMultiInstanceMode，固定 ALL 或 ANY 完成模式
         * @return 无返回值，字段不完整时抛出 IllegalArgumentException
         */
        public RequiredMultiInstanceTarget
        {
            if (!StringUtils.hasText(taskDefinitionKey) || mode == null)
            {
                throw new IllegalArgumentException("动态多实例目标契约不完整");
            }
        }
    }
}
