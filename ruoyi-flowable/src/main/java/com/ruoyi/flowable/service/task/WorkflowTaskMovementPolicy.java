package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 使用已部署 BPMN 模型计算保守且可证明安全的任务状态迁移边界。
 */
@Component
public class WorkflowTaskMovementPolicy
{
    /** 单次图遍历允许访问的最大节点数，防止异常 BPMN 消耗无限资源。 */
    private static final int MAX_TRAVERSAL_NODES = 2048;

    /** 不支持的执行树或 BPMN 结构使用稳定的冲突提示。 */
    private static final String UNSUPPORTED_MOVEMENT_MESSAGE = "当前流程结构不支持该流转操作";

    /**
     * 从流程定义中解析主流程及指定的普通用户任务节点。
     *
     * @param model BpmnModel，流程定义对应的已部署 BPMN 模型
     * @param processKey String，流程定义 key
     * @param taskDefinitionKey String，待解析的用户任务节点 key
     * @return UserTask，位于主流程且不属于多实例的用户任务节点
     */
    public UserTask requireMainProcessUserTask(BpmnModel model, String processKey,
            String taskDefinitionKey)
    {
        org.flowable.bpmn.model.Process process = requireProcess(model, processKey);
        FlowElement element = process.getFlowElement(taskDefinitionKey, true);
        if (!(element instanceof UserTask userTask) || !isSafeNode(process, userTask))
        {
            throw unsupportedMovement();
        }
        return userTask;
    }

    /**
     * 从主流程中解析退回动作的当前普通用户任务。
     *
     * @param model BpmnModel，流程定义对应的已部署 BPMN 模型
     * @param processKey String，流程定义 key
     * @param taskDefinitionKey String，当前活动用户任务节点 key
     * @return UserTask，不含并行、多实例或其他不安全执行边界的普通用户任务
     */
    public UserTask requireMainProcessReturnSource(BpmnModel model, String processKey,
            String taskDefinitionKey)
    {
        org.flowable.bpmn.model.Process process = requireProcess(model, processKey);
        FlowElement element = process.getFlowElement(taskDefinitionKey, true);
        if (!(element instanceof UserTask userTask) || !isSafeNode(process, userTask))
        {
            throw unsupportedMovement();
        }
        return userTask;
    }

    /**
     * 读取 BPMN 中与定义 key 一致的可执行流程。
     *
     * @param model BpmnModel，流程定义对应的已部署 BPMN 模型
     * @param processKey String，流程定义 key
     * @return Process，后续流转校验使用的 BPMN 主流程
     */
    public org.flowable.bpmn.model.Process requireProcess(BpmnModel model, String processKey)
    {
        if (model == null || !StringUtils.hasText(processKey))
        {
            throw unsupportedMovement();
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(processKey);
        if (process == null)
        {
            throw unsupportedMovement();
        }
        return process;
    }

    /**
     * 校验服务端自动选择的首个审批节点可以安全回到当前节点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param target UserTask，实例真实历史中的首个审批节点
     * @param current UserTask，当前触发退回的审批节点
     * @return 无返回值，节点或路径存在不可逆副作用时抛出 HTTP 409
     */
    public void requireSafeDirectReturnPath(org.flowable.bpmn.model.Process process,
            UserTask target, UserTask current)
    {
        if (!isSafeNode(process, target) || !isSafeNode(process, current)
                || !isSafeReturnReachable(process, target, current))
        {
            throw unsupportedMovement();
        }
    }

    /**
     * 为驳回操作解析唯一且可从当前节点沿安全串行路径到达的主流程结束节点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param currentTask UserTask，当前活动用户任务节点
     * @return EndEvent，唯一且结构安全的驳回结束节点
     */
    public EndEvent requireRejectEndEvent(org.flowable.bpmn.model.Process process,
            UserTask currentTask)
    {
        requireSafeEndpoint(process, currentTask);
        List<EndEvent> endEvents = process.findFlowElementsOfType(EndEvent.class, false);
        if (endEvents.size() != 1)
        {
            // 接口没有接收结束节点参数，多结束节点模型不能由服务端擅自选择业务终点。
            throw unsupportedMovement();
        }
        EndEvent endEvent = endEvents.get(0);
        requireSafeEndpoint(process, endEvent);
        if (!isSafeSerialReachable(process, currentTask, endEvent))
        {
            throw unsupportedMovement();
        }
        return endEvent;
    }

    /**
     * 校验撤回来源与当前活动任务之间不存在并行、多实例或跨作用域边界。
     *
     * @param process Process，当前流程定义中的主流程
     * @param completedTask UserTask，当前用户此前正常完成的来源节点
     * @param activeTask UserTask，尚未处理的当前后继节点
     * @return 无返回值，路径不安全时抛出 HTTP 409 业务异常
     */
    public void requireSafeRevokePath(org.flowable.bpmn.model.Process process,
            UserTask completedTask, UserTask activeTask)
    {
        requireSafeEndpoint(process, completedTask);
        requireSafeEndpoint(process, activeTask);
        if (!isSafeSerialReachable(process, completedTask, activeTask))
        {
            throw unsupportedMovement();
        }
    }

    /**
     * 校验状态迁移端点属于同一主流程且不是多实例、子流程或并行网关节点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param endpoint FlowNode，迁移来源或目标节点
     * @return 无返回值，端点不安全时抛出 HTTP 409 业务异常
     */
    private void requireSafeEndpoint(org.flowable.bpmn.model.Process process, FlowNode endpoint)
    {
        if (!isSafeNode(process, endpoint))
        {
            throw unsupportedMovement();
        }
    }

    /**
     * 判断两个节点之间所有可达路径均不穿越并行、多实例或跨作用域边界。
     *
     * @param process Process，当前流程定义中的主流程
     * @param source FlowNode，路径来源节点
     * @param target FlowNode，路径目标节点
     * @return boolean，至少存在一条路径且所有能到达目标的路径均安全时返回 true
     */
    private boolean isSafeSerialReachable(org.flowable.bpmn.model.Process process,
            FlowNode source, FlowNode target)
    {
        TraversalBudget budget = new TraversalBudget();
        PathEvaluation evaluation = evaluatePaths(process, source, target,
                new HashSet<>(), budget);
        return evaluation.reachable() && evaluation.safe();
    }

    /**
     * 判断历史普通用户任务到当前退回来源之间的路径是否安全；仅最终来源可为受控动态多实例节点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param source FlowNode，历史退回目标节点
     * @param target FlowNode，当前普通或受控动态多实例用户任务
     * @return boolean，路径可达且中间节点均安全时返回 true
     */
    private boolean isSafeReturnReachable(org.flowable.bpmn.model.Process process,
            FlowNode source, FlowNode target)
    {
        TraversalBudget budget = new TraversalBudget();
        PathEvaluation evaluation = evaluatePaths(process, source, target,
                new HashSet<>(), budget);
        return evaluation.reachable() && evaluation.safe();
    }

    /**
     * 深度优先评估从当前节点到目标的全部无环路径，并传播路径安全性。
     *
     * @param process Process，当前流程定义中的主流程
     * @param current FlowNode，本次递归正在访问的节点
     * @param target FlowNode，路径目标节点
     * @param path Set&lt;String&gt;，当前递归栈已经访问的节点 key
     * @param budget TraversalBudget，单次图遍历共享的节点计数器
     * @return PathEvaluation，是否可达及所有可达路径是否安全
     */
    private PathEvaluation evaluatePaths(org.flowable.bpmn.model.Process process,
            FlowNode current, FlowNode target, Set<String> path, TraversalBudget budget)
    {
        budget.increment();
        if (current.getId().equals(target.getId()))
        {
            return PathEvaluation.REACHABLE_SAFE;
        }

        Set<String> nextPath = new HashSet<>(path);
        if (!nextPath.add(current.getId()))
        {
            return PathEvaluation.UNREACHABLE;
        }

        boolean reachable = false;
        boolean safe = true;
        List<SequenceFlow> outgoingFlows = current.getOutgoingFlows();
        if (outgoingFlows == null)
        {
            return PathEvaluation.UNREACHABLE;
        }
        for (SequenceFlow sequenceFlow : outgoingFlows)
        {
            FlowElement targetElement = resolveTargetElement(process, sequenceFlow);
            if (!(targetElement instanceof FlowNode nextNode) || nextPath.contains(nextNode.getId()))
            {
                continue;
            }
            PathEvaluation child = evaluatePaths(process, nextNode, target, nextPath, budget);
            if (child.reachable())
            {
                reachable = true;
                boolean safeNextNode = isSafeNode(process, nextNode);
                safe = safe && child.safe() && safeNextNode;
            }
        }
        return new PathEvaluation(reachable, reachable && safe);
    }

    /**
     * 解析顺序流的目标节点并拒绝损坏的 BPMN 引用。
     *
     * @param process Process，当前流程定义中的主流程
     * @param sequenceFlow SequenceFlow，待解析的顺序流
     * @return FlowElement，顺序流关联的真实目标节点
     */
    private FlowElement resolveTargetElement(org.flowable.bpmn.model.Process process,
            SequenceFlow sequenceFlow)
    {
        if (sequenceFlow == null)
        {
            throw unsupportedMovement();
        }
        FlowElement target = sequenceFlow.getTargetFlowElement();
        if (target == null && StringUtils.hasText(sequenceFlow.getTargetRef()))
        {
            target = process.getFlowElement(sequenceFlow.getTargetRef(), true);
        }
        if (target == null || !StringUtils.hasText(target.getId()))
        {
            throw unsupportedMovement();
        }
        return target;
    }

    /**
     * 判断单个 BPMN 节点是否位于主流程且不包含状态迁移禁止的执行边界。
     *
     * @param process Process，当前流程定义中的主流程
     * @param node FlowNode，待判断的 BPMN 节点
     * @return boolean，仅普通同步用户任务、安全排他网关和结束事件返回 true
     */
    private boolean isSafeNode(org.flowable.bpmn.model.Process process, FlowNode node)
    {
        if (process == null || node == null || !StringUtils.hasText(node.getId())
                || node.getParentContainer() != process)
        {
            return false;
        }
        if (node instanceof SubProcess || node instanceof CallActivity
                || hasAsyncContinuation(node))
        {
            return false;
        }
        if (node instanceof UserTask userTask)
        {
            // 退回后会重新执行来源至当前节点的完整路径，边界事件、补偿和多实例都可能产生
            // 不可逆或重复副作用，因此端点与中间用户任务必须同时满足普通同步语义。
            return !userTask.hasMultiInstanceLoopCharacteristics()
                    && !userTask.isForCompensation()
                    && userTask.getBoundaryEvents() != null
                    && userTask.getBoundaryEvents().isEmpty();
        }
        if (node instanceof Activity activity && activity.hasMultiInstanceLoopCharacteristics())
        {
            return false;
        }
        // ServiceTask、脚本、消息/定时事件及其他活动都可能在再次到达时重复外部副作用。
        return node instanceof ExclusiveGateway || node instanceof EndEvent;
    }

    /**
     * 判断节点是否声明进入或离开时的异步执行语义。
     *
     * @param node FlowNode，待核验的用户任务、网关或其他流程节点
     * @return boolean，任一 async 或非排他异步标识存在时返回 true
     */
    private boolean hasAsyncContinuation(FlowNode node)
    {
        return node.isAsynchronous()
                || node.isAsynchronousLeave()
                || node.isNotExclusive()
                || node.isAsynchronousLeaveNotExclusive();
    }

    /**
     * 创建不支持状态迁移的稳定冲突异常。
     *
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException unsupportedMovement()
    {
        return new ServiceException(UNSUPPORTED_MOVEMENT_MESSAGE, HttpStatus.CONFLICT);
    }

    /**
     * 图路径可达性与安全性计算结果。
     *
     * @param reachable boolean，当前节点是否可以到达目标
     * @param safe boolean，所有可达路径是否均满足安全边界
     */
    private record PathEvaluation(boolean reachable, boolean safe)
    {
        /** 不可达路径结果。 */
        private static final PathEvaluation UNREACHABLE = new PathEvaluation(false, false);

        /** 已到达目标且端点安全的路径结果。 */
        private static final PathEvaluation REACHABLE_SAFE = new PathEvaluation(true, true);
    }

    /** 单次递归共享的 BPMN 节点访问预算。 */
    private static final class TraversalBudget
    {
        /** 已访问的节点次数。 */
        private int visitedNodes;

        /**
         * 记录一次节点访问并拒绝超出安全上限的异常模型。
         *
         * @return 无返回值，超过上限时抛出 HTTP 409 业务异常
         */
        private void increment()
        {
            visitedNodes++;
            if (visitedNodes > MAX_TRAVERSAL_NODES)
            {
                throw new ServiceException(UNSUPPORTED_MOVEMENT_MESSAGE, HttpStatus.CONFLICT);
            }
        }
    }
}
