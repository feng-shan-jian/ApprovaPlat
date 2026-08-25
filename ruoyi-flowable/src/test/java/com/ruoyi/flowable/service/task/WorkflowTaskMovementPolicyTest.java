package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.CallActivity;
import org.flowable.bpmn.model.ExclusiveGateway;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.SubProcess;
import org.flowable.bpmn.model.UserTask;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 普通退回与受控多实例整组退回 BPMN 移动边界的聚焦单元测试。
 */
class WorkflowTaskMovementPolicyTest
{
    /** 测试流程定义 key。 */
    private static final String PROCESS_KEY = "movement-policy";

    /** 不支持结构的稳定业务提示。 */
    private static final String UNSUPPORTED_MESSAGE = "当前流程结构不支持该流转操作";

    /** 顺序流主键生成器，避免同一测试图中的连线重名。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 待验证的移动策略。 */
    private final WorkflowTaskMovementPolicy policy = new WorkflowTaskMovementPolicy();

    /**
     * 验证普通用户任务经过安全排他网关的既有串行退回路径保持可用。
     *
     * @return void，策略不抛异常即表示普通路径回归通过
     */
    @Test
    void keepsOrdinarySerialReturnPathCompatible()
    {
        UserTask firstApproval = ordinaryTask("firstApproval");
        ExclusiveGateway route = node(new ExclusiveGateway(), "route");
        UserTask currentApproval = ordinaryTask("currentApproval");
        org.flowable.bpmn.model.Process process = process(
                firstApproval, route, currentApproval);
        connect(process, firstApproval, route);
        connect(process, route, currentApproval);

        policy.requireSafeDirectReturnPath(process, firstApproval, currentApproval);
        assertThat(policy.requireMainProcessReturnSource(model(process), PROCESS_KEY,
                currentApproval.getId())).isSameAs(currentApproval);
    }

    /**
     * 验证受控多实例节点本身就是首审批节点时，可以在同一节点执行整组退回。
     *
     * @return void，策略接受同一受控端点即表示首节点场景通过
     */
    @Test
    void allowsSameControlledMultiInstanceAsFirstApprovalNode()
    {
        UserTask jointReview = controlledTask("jointReview");
        org.flowable.bpmn.model.Process process = process(jointReview);

        policy.requireSafeControlledReturnPath(process, jointReview, jointReview);
        assertThat(policy.requireMainProcessControlledReturnSource(model(process),
                PROCESS_KEY, jointReview.getId())).isSameAs(jointReview);
    }

    /**
     * 验证普通首审批节点到后续受控多实例节点之间仅含安全节点时允许整组退回。
     *
     * @return void，策略不抛异常即表示后续多实例安全路径通过
     */
    @Test
    void allowsOrdinaryFirstNodeToLaterControlledMultiInstance()
    {
        UserTask firstApproval = ordinaryTask("firstApproval");
        ExclusiveGateway route = node(new ExclusiveGateway(), "route");
        UserTask jointReview = controlledTask("jointReview");
        org.flowable.bpmn.model.Process process = process(
                firstApproval, route, jointReview);
        connect(process, firstApproval, route);
        connect(process, route, jointReview);

        policy.requireSafeControlledReturnPath(process, firstApproval, jointReview);
    }

    /**
     * 验证普通首审批经过已完成 ALL 后到 ANY 的连续受控路径允许整组退回。
     *
     * @return void，策略应冻结 ALL 和 ANY 两个受控节点
     */
    @Test
    void allowsOrdinaryAllAnyControlledReturnPath()
    {
        UserTask firstApproval = ordinaryTask("firstApproval");
        UserTask allReview = controlledTask("allReview");
        UserTask anyReview = controlledAnyTask("anyReview");
        org.flowable.bpmn.model.Process process = process(
                firstApproval, allReview, anyReview);
        connect(process, firstApproval, allReview);
        connect(process, allReview, anyReview);

        assertThat(policy.requireSafeControlledReturnPath(
                process, firstApproval, anyReview).controlledActivityIds())
                .containsExactlyInAnyOrder("allReview", "anyReview");
    }

    /**
     * 验证普通首审批经过两个连续 ALL 后到 ANY 的路径保持可重放。
     *
     * @return void，三个受控节点必须全部进入服务端重放计划
     */
    @Test
    void allowsOrdinaryAllAllAnyControlledReturnPath()
    {
        UserTask firstApproval = ordinaryTask("firstApproval");
        UserTask firstAll = controlledTask("firstAll");
        UserTask secondAll = controlledTask("secondAll");
        UserTask anyReview = controlledAnyTask("anyReview");
        org.flowable.bpmn.model.Process process = process(
                firstApproval, firstAll, secondAll, anyReview);
        connect(process, firstApproval, firstAll);
        connect(process, firstAll, secondAll);
        connect(process, secondAll, anyReview);

        assertThat(policy.requireSafeControlledReturnPath(
                process, firstApproval, anyReview).controlledActivityIds())
                .containsExactlyInAnyOrder("firstAll", "secondAll", "anyReview");
    }

    /**
     * 验证并行网关会改变 execution 拓扑，整组退回路径必须失败关闭。
     *
     * @return void，策略必须返回 HTTP 409
     */
    @Test
    void rejectsParallelGatewayOnControlledReturnPath()
    {
        UserTask firstApproval = ordinaryTask("firstApproval");
        ParallelGateway fork = node(new ParallelGateway(), "fork");
        UserTask jointReview = controlledTask("jointReview");
        org.flowable.bpmn.model.Process process = process(firstApproval, fork, jointReview);
        connect(process, firstApproval, fork);
        connect(process, fork, jointReview);

        assertUnsupported(() -> policy.requireSafeControlledReturnPath(
                process, firstApproval, jointReview));
    }

    /**
     * 验证 CallActivity 和 ServiceTask 的外部副作用均不能被整组退回重放。
     *
     * @return void，两类路径都必须返回 HTTP 409
     */
    @Test
    void rejectsCallActivityAndServiceTaskOnControlledReturnPath()
    {
        assertUnsafeIntermediate(node(new CallActivity(), "calledProcess"));
        assertUnsafeIntermediate(node(new ServiceTask(), "externalService"));
    }

    /**
     * 验证静态多实例和带边界事件的中间任务仍属于不可安全重放结构。
     *
     * @return void，两类路径都必须使用稳定 HTTP 409 失败关闭
     */
    @Test
    void rejectsStaticMultiInstanceAndBoundaryIntermediate()
    {
        UserTask staticMulti = ordinaryTask("staticMulti");
        MultiInstanceLoopCharacteristics staticLoop =
                new MultiInstanceLoopCharacteristics();
        staticLoop.setInputDataItem("${legacyUsers}");
        staticMulti.setLoopCharacteristics(staticLoop);
        assertUnsafeIntermediate(staticMulti);

        UserTask boundaryTask = ordinaryTask("boundaryTask");
        BoundaryEvent boundary = node(new BoundaryEvent(), "boundaryTimer");
        boundary.setAttachedToRef(boundaryTask);
        boundaryTask.getBoundaryEvents().add(boundary);
        UserTask first = ordinaryTask("firstBoundary");
        UserTask current = controlledTask("currentBoundary");
        org.flowable.bpmn.model.Process process = process(
                first, boundaryTask, boundary, current);
        connect(process, first, boundaryTask);
        connect(process, boundaryTask, current);
        assertUnsupported(() -> policy.requireSafeControlledReturnPath(
                process, first, current));
    }

    /**
     * 验证循环、损坏引用和超过遍历预算的图稳定返回业务冲突。
     *
     * @return void，异常图不得超时、栈溢出或被当作安全路径
     */
    @Test
    void rejectsLoopDamagedReferenceAndTraversalOverflow()
    {
        UserTask loopFirst = ordinaryTask("loopFirst");
        ExclusiveGateway loopGateway = node(new ExclusiveGateway(), "loopGateway");
        UserTask loopCurrent = controlledTask("loopCurrent");
        org.flowable.bpmn.model.Process loopProcess = process(
                loopFirst, loopGateway, loopCurrent);
        connect(loopProcess, loopFirst, loopGateway);
        connect(loopProcess, loopGateway, loopFirst);
        connect(loopProcess, loopGateway, loopCurrent);
        assertUnsupported(() -> policy.requireSafeControlledReturnPath(
                loopProcess, loopFirst, loopCurrent));

        UserTask damagedFirst = ordinaryTask("damagedFirst");
        UserTask damagedCurrent = controlledTask("damagedCurrent");
        org.flowable.bpmn.model.Process damaged = process(
                damagedFirst, damagedCurrent);
        SequenceFlow broken = new SequenceFlow();
        broken.setId("broken");
        broken.setSourceFlowElement(damagedFirst);
        broken.setTargetRef("missingNode");
        damagedFirst.getOutgoingFlows().add(broken);
        damaged.addFlowElement(broken);
        assertUnsupported(() -> policy.requireSafeControlledReturnPath(
                damaged, damagedFirst, damagedCurrent));

        UserTask wideFirst = ordinaryTask("wideFirst");
        UserTask wideCurrent = controlledTask("wideCurrent");
        org.flowable.bpmn.model.Process wide = process(wideFirst, wideCurrent);
        connect(wide, wideFirst, wideCurrent);
        for (int index = 0; index < 2049; index++)
        {
            EndEvent deadEnd = node(new EndEvent(), "deadEnd" + index);
            wide.addFlowElement(deadEnd);
            connect(wide, wideFirst, deadEnd);
        }
        assertUnsupported(() -> policy.requireSafeControlledReturnPath(
                wide, wideFirst, wideCurrent));
    }

    /**
     * 验证边界事件或异步延续会破坏同步受控迁移，来源节点必须失败关闭。
     *
     * @return void，两类受控多实例节点都必须返回 HTTP 409
     */
    @Test
    void rejectsBoundaryEventAndAsyncControlledSource()
    {
        UserTask boundarySource = controlledTask("boundarySource");
        BoundaryEvent timeout = node(new BoundaryEvent(), "timeout");
        timeout.setAttachedToRef(boundarySource);
        boundarySource.getBoundaryEvents().add(timeout);
        org.flowable.bpmn.model.Process boundaryProcess = process(boundarySource, timeout);
        assertUnsupported(() -> policy.requireMainProcessControlledReturnSource(
                model(boundaryProcess), PROCESS_KEY, boundarySource.getId()));

        UserTask asyncSource = controlledTask("asyncSource");
        asyncSource.setAsynchronous(true);
        org.flowable.bpmn.model.Process asyncProcess = process(asyncSource);
        assertUnsupported(() -> policy.requireMainProcessControlledReturnSource(
                model(asyncProcess), PROCESS_KEY, asyncSource.getId()));
    }

    /**
     * 验证子流程中的受控多实例任务不属于主流程作用域，不能参与整组退回。
     *
     * @return void，跨作用域来源必须返回 HTTP 409
     */
    @Test
    void rejectsControlledMultiInstanceFromNestedScope()
    {
        UserTask nestedJointReview = controlledTask("nestedJointReview");
        SubProcess nestedScope = node(new SubProcess(), "nestedScope");
        nestedScope.addFlowElement(nestedJointReview);
        org.flowable.bpmn.model.Process process = process(nestedScope);

        assertUnsupported(() -> policy.requireMainProcessControlledReturnSource(
                model(process), PROCESS_KEY, nestedJointReview.getId()));
    }

    /**
     * 验证普通撤回只冻结来源及唯一直接同步后继。
     * @return void，计划节点必须稳定且不可变
     */
    @Test
    void buildsImmutableDirectRevokePlan()
    {
        UserTask source = ordinaryTask("revokeSource");
        UserTask successor = ordinaryTask("revokeSuccessor");
        org.flowable.bpmn.model.Process process = process(source, successor);
        connect(process, source, successor);

        WorkflowTaskMovementPolicy.RevokeMovementPlan plan =
                policy.requireSafeRevokeMovement(process, source);
        assertThat(plan.sourceNodeKey()).isEqualTo("revokeSource");
        assertThat(plan.successorNodeKeys()).containsExactly("revokeSuccessor");
        assertThatThrownBy(() -> plan.successorNodeKeys().add("other"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 验证纯并行拆分撤回按节点 key 冻结全部普通后继。
     * @return void，计划不得依赖 BPMN 登记顺序
     */
    @Test
    void buildsSortedParallelRevokePlan()
    {
        UserTask source = ordinaryTask("parallelSource");
        ParallelGateway fork = node(new ParallelGateway(), "parallelFork");
        UserTask second = ordinaryTask("secondReview");
        UserTask first = ordinaryTask("firstReview");
        org.flowable.bpmn.model.Process process = process(source, fork, second, first);
        connect(process, source, fork);
        connect(process, fork, second);
        connect(process, fork, first);

        assertThat(policy.requireSafeRevokeMovement(process, source)
                .successorNodeKeys()).containsExactly("firstReview", "secondReview");
    }

    /**
     * 验证条件顺序流和复杂后继不能进入撤回计划。
     * @return void，两种结构都必须使用稳定 409 失败关闭
     */
    @Test
    void rejectsConditionalAndComplexRevokeSuccessors()
    {
        UserTask conditionalSource = ordinaryTask("conditionalSource");
        UserTask conditionalTarget = ordinaryTask("conditionalTarget");
        org.flowable.bpmn.model.Process conditionalProcess = process(
                conditionalSource, conditionalTarget);
        connect(conditionalProcess, conditionalSource, conditionalTarget);
        conditionalSource.getOutgoingFlows().get(0).setConditionExpression("${approved}");
        assertRevokeConflict(() -> policy.requireSafeRevokeMovement(
                conditionalProcess, conditionalSource));

        UserTask asyncSource = ordinaryTask("asyncRevokeSource");
        UserTask asyncTarget = ordinaryTask("asyncRevokeTarget");
        asyncTarget.setAsynchronous(true);
        org.flowable.bpmn.model.Process asyncProcess = process(asyncSource, asyncTarget);
        connect(asyncProcess, asyncSource, asyncTarget);
        assertRevokeConflict(() -> policy.requireSafeRevokeMovement(
                asyncProcess, asyncSource));
    }

    /**
     * 为不安全中间节点构造完整可达路径并验证策略拒绝。
     *
     * @param intermediate FlowNode，CallActivity、ServiceTask 等不可重放节点
     * @return void，路径未被拒绝时由断言使测试失败
     */
    private void assertUnsafeIntermediate(FlowNode intermediate)
    {
        UserTask firstApproval = ordinaryTask("first-" + intermediate.getId());
        UserTask jointReview = controlledTask("current-" + intermediate.getId());
        org.flowable.bpmn.model.Process process = process(
                firstApproval, intermediate, jointReview);
        connect(process, firstApproval, intermediate);
        connect(process, intermediate, jointReview);

        assertUnsupported(() -> policy.requireSafeControlledReturnPath(
                process, firstApproval, jointReview));
    }

    /**
     * 创建无多实例、无边界事件和无异步语义的普通审批任务。
     *
     * @param id String，BPMN 用户任务节点 key
     * @return UserTask，可用于普通安全路径的用户任务
     */
    private UserTask ordinaryTask(String id)
    {
        return node(new UserTask(), id);
    }

    /**
     * 创建使用平台动态成员表达式的同步并行多实例任务。
     *
     * @param id String，BPMN 多实例用户任务节点 key
     * @return UserTask，满足移动策略分类所需的受控多实例任务
     */
    private UserTask controlledTask(String id)
    {
        UserTask task = ordinaryTask(id);
        MultiInstanceLoopCharacteristics loop = new MultiInstanceLoopCharacteristics();
        loop.setInputDataItem(WorkflowMultiInstanceModelContract.COLLECTION_EXPRESSION);
        loop.setElementVariable(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE);
        loop.setCompletionCondition(
                WorkflowMultiInstanceModelContract.ALL_COMPLETION_CONDITION);
        loop.setSequential(false);
        task.setAssignee(WorkflowMultiInstanceModelContract.ASSIGNEE_EXPRESSION);
        task.setLoopCharacteristics(loop);
        return task;
    }

    /**
     * 创建使用平台受控 handler 的同步 ANY 或签任务。
     *
     * @param id String，BPMN 用户任务节点 key
     * @return UserTask，完成一个成员即可结束的受控多实例任务
     */
    private UserTask controlledAnyTask(String id)
    {
        UserTask task = controlledTask(id);
        ((MultiInstanceLoopCharacteristics) task.getLoopCharacteristics())
                .setCompletionCondition(
                        WorkflowMultiInstanceModelContract.ANY_COMPLETION_CONDITION);
        return task;
    }

    /**
     * 为 Flowable 节点设置测试图内唯一标识。
     *
     * @param <T> FlowNode 的具体节点类型
     * @param flowNode T，待初始化的 BPMN 节点
     * @param id String，节点 key
     * @return T，已设置 key 的原节点
     */
    private <T extends FlowNode> T node(T flowNode, String id)
    {
        flowNode.setId(id);
        return flowNode;
    }

    /**
     * 创建主流程并登记指定节点，确保 parentContainer 与生产模型一致。
     *
     * @param flowNodes FlowNode[]，按测试图需要登记的主流程节点
     * @return Process，包含指定节点的可执行主流程模型
     */
    private org.flowable.bpmn.model.Process process(FlowNode... flowNodes)
    {
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId(PROCESS_KEY);
        for (FlowNode flowNode : flowNodes)
        {
            process.addFlowElement(flowNode);
        }
        return process;
    }

    /**
     * 将测试主流程装入 BpmnModel，供按流程 key 和节点 key 的解析门禁使用。
     *
     * @param process Process，已经登记节点的测试主流程
     * @return BpmnModel，仅包含该主流程的 BPMN 模型
     */
    private BpmnModel model(org.flowable.bpmn.model.Process process)
    {
        BpmnModel model = new BpmnModel();
        model.addProcess(process);
        return model;
    }

    /**
     * 建立带双向对象引用的顺序流，使策略按真实模型引用遍历测试路径。
     *
     * @param process Process，承载连线的主流程
     * @param source FlowNode，顺序流来源节点
     * @param target FlowNode，顺序流目标节点
     * @return void，连线同时登记到流程及两端节点
     */
    private void connect(org.flowable.bpmn.model.Process process,
            FlowNode source, FlowNode target)
    {
        SequenceFlow flow = new SequenceFlow(source.getId(), target.getId());
        flow.setId("flow-" + sequence.incrementAndGet());
        flow.setSourceFlowElement(source);
        flow.setTargetFlowElement(target);
        source.getOutgoingFlows().add(flow);
        target.getIncomingFlows().add(flow);
        process.addFlowElement(flow);
    }

    /**
     * 断言不安全结构使用稳定 HTTP 409 业务契约失败关闭。
     *
     * @param callable ThrowingCallable，预计被移动策略拒绝的调用
     * @return void，异常类型、状态码或提示不匹配时由断言使测试失败
     */
    private void assertUnsupported(ThrowingCallable callable)
    {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage()).isEqualTo(UNSUPPORTED_MESSAGE);
                });
    }

    /**
     * 断言撤回图规则沿用应用服务的稳定状态冲突契约。
     * @param callable ThrowingCallable，预计被撤回策略拒绝的读取动作
     * @return void，异常类型、状态码或消息不匹配时失败
     */
    private void assertRevokeConflict(ThrowingCallable callable)
    {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getMessage())
                            .isEqualTo("工作流状态已发生变化，请刷新后重试");
                });
    }
}
