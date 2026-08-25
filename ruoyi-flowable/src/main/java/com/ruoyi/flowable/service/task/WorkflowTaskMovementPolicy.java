package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.model.Activity;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ParallelGateway;
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

    /** 撤回只允许一次合并的最大直接并行后继数。 */
    private static final int MAX_REVOKE_SUCCESSORS = 100;

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
     * 从主流程中解析整组退回的受控多实例来源节点。
     *
     * @param model BpmnModel，流程定义对应的已部署 BPMN 模型
     * @param processKey String，流程定义 key
     * @param taskDefinitionKey String，当前活动受控多实例节点 key
     * @return UserTask，仅返回主流程中同步、无边界事件且使用平台受控 handler 的多实例节点
     */
    public UserTask requireMainProcessControlledReturnSource(BpmnModel model,
            String processKey, String taskDefinitionKey)
    {
        org.flowable.bpmn.model.Process process = requireProcess(model, processKey);
        FlowElement element = process.getFlowElement(taskDefinitionKey, true);
        if (!(element instanceof UserTask userTask)
                || !isSafeControlledMultiInstanceNode(process, userTask))
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
     * 校验首审批节点到当前受控多实例节点的重放路径，不放宽任何中间副作用节点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param target UserTask，实例真实历史确定的首个审批节点
     * @param current UserTask，当前受控多实例整组退回来源节点
     * @return ControlledReturnPathPlan，首审批至来源之间按图遍历冻结的受控多实例节点集合
     */
    public ControlledReturnPathPlan requireSafeControlledReturnPath(
            org.flowable.bpmn.model.Process process,
            UserTask target, UserTask current)
    {
        boolean safeTarget = isSafeNode(process, target)
                || isSafeControlledMultiInstanceNode(process, target);
        PathEvaluation evaluation = safeTarget
                && isSafeControlledMultiInstanceNode(process, current)
                        ? evaluateReturnPaths(process, target, current, true)
                        : PathEvaluation.UNREACHABLE;
        if (!isSafeControlledMultiInstanceNode(process, current)
                || !safeTarget || !evaluation.reachable() || !evaluation.safe())
        {
            throw unsupportedMovement();
        }
        return new ControlledReturnPathPlan(
                List.copyOf(evaluation.controlledActivityIds()));
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
        return isSafeReturnReachable(process, source, target, false);
    }

    /**
     * 从已部署 BPMN 计算撤回来源及其全部安全直接后继，不读取任何运行时状态。
     *
     * @param process Process，撤回动作所属主流程
     * @param completedTask UserTask，当前用户此前正常完成的来源节点
     * @return RevokeMovementPlan，来源节点和按 key 排序的预期直接后继节点
     */
    public RevokeMovementPlan requireSafeRevokeMovement(
            org.flowable.bpmn.model.Process process, UserTask completedTask)
    {
        requirePlainSynchronousUserTask(process, completedTask);
        List<SequenceFlow> outgoing = completedTask.getOutgoingFlows();
        if (outgoing == null || outgoing.size() != 1
                || StringUtils.hasText(completedTask.getDefaultFlow()))
        {
            throw unsafeRevokeMovement();
        }
        SequenceFlow sourceFlow = outgoing.get(0);
        FlowElement directTarget = requireUnconditionalTarget(
                process, completedTask, sourceFlow);
        List<UserTask> successors;
        if (directTarget instanceof UserTask directUserTask)
        {
            requirePlainSynchronousUserTask(process, directUserTask);
            requireOnlyIncomingFlow(directUserTask, sourceFlow, completedTask);
            successors = List.of(directUserTask);
        }
        else if (directTarget instanceof ParallelGateway gateway)
        {
            successors = requireSafeParallelSuccessors(
                    process, completedTask, sourceFlow, gateway);
        }
        else
        {
            throw unsafeRevokeMovement();
        }
        return new RevokeMovementPlan(completedTask.getId(), successors.stream()
                .map(UserTask::getId).sorted().toList());
    }

    /**
     * 校验并行网关只将撤回来源直接拆分为多个普通同步用户任务。
     *
     * @param process Process，并行网关所属主流程
     * @param completedTask UserTask，撤回来源节点
     * @param sourceFlow SequenceFlow，来源到网关的唯一顺序流
     * @param gateway ParallelGateway，待核验的直接拆分网关
     * @return List&lt;UserTask&gt;，按节点 key 稳定排序的直接并行后继
     */
    private List<UserTask> requireSafeParallelSuccessors(
            org.flowable.bpmn.model.Process process, UserTask completedTask,
            SequenceFlow sourceFlow, ParallelGateway gateway)
    {
        if (gateway.getParentContainer() != process || hasAsyncContinuation(gateway)
                || StringUtils.hasText(gateway.getDefaultFlow()))
        {
            throw unsafeRevokeMovement();
        }
        requireOnlyIncomingFlow(gateway, sourceFlow, completedTask);
        List<SequenceFlow> outgoing = gateway.getOutgoingFlows();
        if (outgoing == null || outgoing.size() < 2
                || outgoing.size() > MAX_REVOKE_SUCCESSORS)
        {
            throw unsafeRevokeMovement();
        }
        LinkedHashMap<String, UserTask> successors = new LinkedHashMap<>();
        for (SequenceFlow flow : outgoing)
        {
            FlowElement target = requireUnconditionalTarget(process, gateway, flow);
            if (!(target instanceof UserTask successor))
            {
                throw unsafeRevokeMovement();
            }
            requirePlainSynchronousUserTask(process, successor);
            requireOnlyIncomingFlow(successor, flow, gateway);
            if (successors.put(successor.getId(), successor) != null)
            {
                throw unsafeRevokeMovement();
            }
        }
        return successors.values().stream()
                .sorted(Comparator.comparing(UserTask::getId)).toList();
    }

    /**
     * 校验撤回来源或直接后继属于主流程且没有多实例、边界、补偿或异步语义。
     *
     * @param process Process，撤回动作所属主流程
     * @param userTask UserTask，来源或直接后继节点
     * @return 无返回值，存在复杂执行边界时抛出 HTTP 409
     */
    private void requirePlainSynchronousUserTask(
            org.flowable.bpmn.model.Process process, UserTask userTask)
    {
        if (userTask == null || userTask.getParentContainer() != process
                || userTask.hasMultiInstanceLoopCharacteristics()
                || userTask.isForCompensation() || hasAsyncContinuation(userTask)
                || userTask.getBoundaryEvents() == null
                || !userTask.getBoundaryEvents().isEmpty())
        {
            throw unsafeRevokeMovement();
        }
    }

    /**
     * 解析无条件顺序流真实目标并核验来源引用。
     *
     * @param process Process，顺序流所属主流程
     * @param expectedSource FlowNode，契约要求的来源节点
     * @param flow SequenceFlow，待解析顺序流
     * @return FlowElement，属于主流程的真实目标节点
     */
    private FlowElement requireUnconditionalTarget(
            org.flowable.bpmn.model.Process process, FlowNode expectedSource,
            SequenceFlow flow)
    {
        if (flow == null || StringUtils.hasText(flow.getConditionExpression())
                || StringUtils.hasText(flow.getSkipExpression()))
        {
            throw unsafeRevokeMovement();
        }
        FlowElement source = flow.getSourceFlowElement();
        if (source == null && StringUtils.hasText(flow.getSourceRef()))
        {
            source = process.getFlowElement(flow.getSourceRef(), true);
        }
        FlowElement target = flow.getTargetFlowElement();
        if (target == null && StringUtils.hasText(flow.getTargetRef()))
        {
            target = process.getFlowElement(flow.getTargetRef(), true);
        }
        if (source != expectedSource || target == null
                || !StringUtils.hasText(target.getId())
                || target.getParentContainer() != process)
        {
            throw unsafeRevokeMovement();
        }
        return target;
    }

    /**
     * 校验直接目标只有一条来自预期来源的入边。
     *
     * @param target FlowNode，待核验直接目标节点
     * @param expectedFlow SequenceFlow，来源到目标的预期顺序流
     * @param expectedSource FlowNode，预期来源节点
     * @return 无返回值，入边歧义时抛出 HTTP 409
     */
    private void requireOnlyIncomingFlow(FlowNode target,
            SequenceFlow expectedFlow, FlowNode expectedSource)
    {
        List<SequenceFlow> incoming = target.getIncomingFlows();
        if (incoming == null || incoming.size() != 1
                || incoming.get(0) != expectedFlow
                || expectedFlow.getSourceFlowElement() != expectedSource)
        {
            throw unsafeRevokeMovement();
        }
    }

    /**
     * 判断历史任务到退回来源的路径是否安全，并可仅对最终受控多实例端点放宽节点类型。
     *
     * @param process Process，当前流程定义中的主流程
     * @param source FlowNode，历史退回目标节点
     * @param target FlowNode，当前退回来源节点
     * @param allowControlledTarget boolean，是否仅允许最终目标为受控多实例节点
     * @return boolean，路径可达且除受控最终端点外的全部节点均安全时返回 true
     */
    private boolean isSafeReturnReachable(org.flowable.bpmn.model.Process process,
            FlowNode source, FlowNode target, boolean allowControlledTarget)
    {
        PathEvaluation evaluation = evaluateReturnPaths(process, source, target,
                allowControlledTarget);
        return evaluation.reachable() && evaluation.safe();
    }

    /**
     * 使用独立遍历预算计算退回路径，并冻结所有可达安全路径上的受控多实例节点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param source FlowNode，服务端历史确定的首审批节点
     * @param target FlowNode，当前退回来源节点
     * @param allowControlledNodes boolean，是否允许路径中的平台受控同步多实例节点
     * @return PathEvaluation，可达性、安全性和受控节点集合
     */
    private PathEvaluation evaluateReturnPaths(org.flowable.bpmn.model.Process process,
            FlowNode source, FlowNode target, boolean allowControlledNodes)
    {
        return evaluatePaths(process, source, target, new HashSet<>(),
                new TraversalBudget(), allowControlledNodes);
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
        return evaluatePaths(process, current, target, path, budget, false);
    }

    /**
     * 深度优先评估路径，并仅在调用方明确授权时放宽最终受控多实例端点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param current FlowNode，本次递归正在访问的节点
     * @param target FlowNode，路径目标节点
     * @param path Set&lt;String&gt;，当前递归栈已经访问的节点 key
     * @param budget TraversalBudget，单次图遍历共享的节点计数器
     * @param allowControlledTarget boolean，是否允许最终目标为受控多实例用户任务
     * @return PathEvaluation，是否可达及所有可达路径是否安全
     */
    private PathEvaluation evaluatePaths(org.flowable.bpmn.model.Process process,
            FlowNode current, FlowNode target, Set<String> path, TraversalBudget budget,
            boolean allowControlledTarget)
    {
        budget.increment();
        if (current.getId().equals(target.getId()))
        {
            LinkedHashSet<String> controlled = new LinkedHashSet<>();
            if (allowControlledTarget && current instanceof UserTask userTask
                    && isSafeControlledMultiInstanceNode(process, userTask))
            {
                controlled.add(current.getId());
            }
            return new PathEvaluation(true, true, controlled);
        }

        Set<String> nextPath = new HashSet<>(path);
        if (!nextPath.add(current.getId()))
        {
            return PathEvaluation.UNREACHABLE;
        }

        boolean reachable = false;
        boolean safe = true;
        LinkedHashSet<String> controlledActivityIds = new LinkedHashSet<>();
        List<SequenceFlow> outgoingFlows = current.getOutgoingFlows();
        if (outgoingFlows == null)
        {
            return PathEvaluation.UNREACHABLE;
        }
        for (SequenceFlow sequenceFlow : outgoingFlows)
        {
            FlowElement targetElement = resolveTargetElement(process, sequenceFlow);
            if (!(targetElement instanceof FlowNode nextNode))
            {
                continue;
            }
            if (nextPath.contains(nextNode.getId()))
            {
                // 退回重放不能依赖循环的退出条件；一旦真实可遍历图出现回边即稳定失败关闭。
                throw unsupportedMovement();
            }
            PathEvaluation child = evaluatePaths(process, nextNode, target, nextPath,
                    budget, allowControlledTarget);
            if (child.reachable())
            {
                reachable = true;
                boolean safeNextNode = isSafeNode(process, nextNode)
                        || (allowControlledTarget
                                && nextNode instanceof UserTask userTask
                                && isSafeControlledMultiInstanceNode(process, userTask));
                safe = safe && child.safe() && safeNextNode;
                controlledActivityIds.addAll(child.controlledActivityIds());
            }
        }
        if (reachable && allowControlledTarget
                && current instanceof UserTask userTask
                && isSafeControlledMultiInstanceNode(process, userTask))
        {
            controlledActivityIds.add(current.getId());
        }
        return new PathEvaluation(reachable, reachable && safe,
                controlledActivityIds);
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
     * 判断用户任务是否为可由整组协议迁移的主流程受控多实例端点。
     *
     * @param process Process，当前流程定义中的主流程
     * @param userTask UserTask，待核验的多实例退回来源或同节点目标
     * @return boolean，仅同步、非补偿、无边界事件且使用平台受控 handler 时返回 true
     */
    private boolean isSafeControlledMultiInstanceNode(
            org.flowable.bpmn.model.Process process, UserTask userTask)
    {
        return process != null && userTask != null
                && StringUtils.hasText(userTask.getId())
                && userTask.getParentContainer() == process
                && WorkflowMultiInstanceModelContract.usesControlledHandler(
                        userTask.getLoopCharacteristics())
                && !hasAsyncContinuation(userTask)
                && !userTask.isForCompensation()
                && userTask.getBoundaryEvents() != null
                && userTask.getBoundaryEvents().isEmpty();
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
     * 创建与既有撤回应用服务一致的稳定状态冲突。
     *
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException unsafeRevokeMovement()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT);
    }

    /**
     * 纯 BPMN 图规则计算出的撤回来源与直接后继节点计划。
     *
     * @param sourceNodeKey String，撤回后需要恢复的来源节点 key
     * @param successorNodeKeys List&lt;String&gt;，按 key 排序的完整直接后继节点
     */
    public record RevokeMovementPlan(String sourceNodeKey,
            List<String> successorNodeKeys)
    {
        /**
         * 冻结节点列表并拒绝空或重复计划。
         *
         * @return 无返回值，构造时完成不可变约束
         */
        public RevokeMovementPlan
        {
            successorNodeKeys = successorNodeKeys == null
                    ? List.of() : List.copyOf(successorNodeKeys);
            if (!StringUtils.hasText(sourceNodeKey)
                    || successorNodeKeys.isEmpty()
                    || successorNodeKeys.stream().anyMatch(
                            key -> !StringUtils.hasText(key))
                    || new HashSet<>(successorNodeKeys).size()
                            != successorNodeKeys.size())
            {
                throw new IllegalArgumentException("撤回迁移计划不完整");
            }
        }
    }

    /**
     * 首审批到受控退回来源之间可安全重新流转的受控节点计划。
     *
     * @param controlledActivityIds List&lt;String&gt;，所有可达安全路径上的受控多实例节点 key
     */
    public record ControlledReturnPathPlan(List<String> controlledActivityIds)
    {
        /**
         * 冻结并校验节点集合，后续轮次准备不得接受空白或重复节点。
         *
         * @return 无返回值，构造时完成不可变约束
         */
        public ControlledReturnPathPlan
        {
            controlledActivityIds = controlledActivityIds == null
                    ? List.of() : List.copyOf(controlledActivityIds);
            if (controlledActivityIds.stream().anyMatch(
                    activityId -> !StringUtils.hasText(activityId))
                    || new HashSet<>(controlledActivityIds).size()
                            != controlledActivityIds.size())
            {
                throw new IllegalArgumentException("受控退回路径节点不完整");
            }
        }
    }

    /**
     * 图路径可达性与安全性计算结果。
     *
     * @param reachable boolean，当前节点是否可以到达目标
     * @param safe boolean，所有可达路径是否均满足安全边界
     * @param controlledActivityIds Set&lt;String&gt;，可达安全路径中的受控多实例节点
     */
    private record PathEvaluation(boolean reachable, boolean safe,
            Set<String> controlledActivityIds)
    {
        /** 不可达路径结果。 */
        private static final PathEvaluation UNREACHABLE =
                new PathEvaluation(false, false, Set.of());

        /** 已到达目标且端点安全的路径结果。 */
        private static final PathEvaluation REACHABLE_SAFE =
                new PathEvaluation(true, true, Set.of());

        /**
         * 冻结图遍历结果中的受控节点集合。
         *
         * @return 无返回值，构造时完成不可变复制
         */
        private PathEvaluation
        {
            controlledActivityIds = controlledActivityIds == null
                    ? Set.of() : java.util.Collections.unmodifiableSet(
                            new LinkedHashSet<>(controlledActivityIds));
        }
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
