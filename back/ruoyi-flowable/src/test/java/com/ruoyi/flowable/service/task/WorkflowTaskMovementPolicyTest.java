package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.EndEvent;
import org.flowable.bpmn.model.MultiInstanceLoopCharacteristics;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.bpmn.model.UserTask;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

class WorkflowTaskMovementPolicyTest
{
    private WorkflowTaskMovementPolicy movementPolicy;

    /**
     * 为每个测试创建无共享状态的 BPMN 流转策略。
     *
     * @return 无返回值，初始化后测试可直接构造模型
     */
    @BeforeEach
    void setUp()
    {
        movementPolicy = new WorkflowTaskMovementPolicy();
    }

    /**
     * 验证串行历史节点按输入顺序返回，状态迁移删除记录不会成为候选节点。
     *
     * @return 无返回值，候选节点错误时测试失败
     */
    @Test
    void findsOnlyNormallyCompletedSerialReturnNodes()
    {
        BpmnFixture fixture = serialFixture();
        HistoricTaskInstance nearest = historicTask("historic-b", "task-b", "复核", null);
        HistoricTaskInstance earliest = historicTask("historic-a", "task-a", "申请", null);
        HistoricTaskInstance moved = historicTask("historic-moved", "task-a", "申请", "Change activity");

        assertThat(movementPolicy.findLegalReturnNodes(fixture.process(), fixture.currentTask(),
                List.of(nearest, moved, earliest)))
                .extracting("id", "name")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("task-b", "复核"),
                        org.assertj.core.groups.Tuple.tuple("task-a", "申请"));
    }

    /**
     * 验证候选节点到当前任务穿越并行网关时不会进入可退节点列表。
     *
     * @return 无返回值，并行路径被错误放行时测试失败
     */
    @Test
    void excludesReturnPathAcrossParallelGateway()
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = process(model);
        UserTask source = userTask(process, "source", "来源");
        ParallelGateway parallel = new ParallelGateway();
        parallel.setId("parallel");
        process.addFlowElement(parallel);
        UserTask current = userTask(process, "current", "当前");
        connect(process, source, parallel, "flow-1");
        connect(process, parallel, current, "flow-2");

        assertThat(movementPolicy.findLegalReturnNodes(process, current,
                List.of(historicTask("historic-source", "source", "来源", null))))
                .isEmpty();
    }

    /**
     * 验证多实例用户任务不能作为状态迁移端点。
     *
     * @return 无返回值，多实例端点未返回冲突时测试失败
     */
    @Test
    void rejectsMultiInstanceMovementEndpoint()
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = process(model);
        UserTask current = userTask(process, "current", "当前");
        current.setLoopCharacteristics(new MultiInstanceLoopCharacteristics());

        assertConflict(() -> movementPolicy.requireMainProcessUserTask(
                model, process.getId(), current.getId()));
    }

    /**
     * 验证历史任务到当前任务之间经过 ServiceTask 时不允许退回，防止重复外部业务副作用。
     *
     * @return 无返回值，服务任务路径仍被列为合法退回目标时测试失败
     */
    @Test
    void excludesReturnPathAcrossServiceTask()
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = process(model);
        UserTask source = userTask(process, "source", "来源");
        ServiceTask sideEffect = new ServiceTask();
        sideEffect.setId("send-payment");
        process.addFlowElement(sideEffect);
        UserTask current = userTask(process, "current", "当前");
        connect(process, source, sideEffect, "flow-source-service");
        connect(process, sideEffect, current, "flow-service-current");

        assertThat(movementPolicy.findLegalReturnNodes(process, current,
                List.of(historicTask("historic-source", "source", "来源", null))))
                .isEmpty();
    }

    /**
     * 验证补偿、边界事件和 async 用户任务均不能作为退回端点。
     *
     * @return 无返回值，任一不可逆用户任务端点未返回冲突时测试失败
     */
    @Test
    void rejectsCompensationBoundaryAndAsyncUserTaskEndpoints()
    {
        BpmnModel compensationModel = new BpmnModel();
        org.flowable.bpmn.model.Process compensationProcess = process(compensationModel);
        UserTask compensationTask = userTask(
                compensationProcess, "compensation", "补偿任务");
        compensationTask.setForCompensation(true);
        assertConflict(() -> movementPolicy.requireMainProcessUserTask(
                compensationModel, compensationProcess.getId(), compensationTask.getId()));

        BpmnModel boundaryModel = new BpmnModel();
        org.flowable.bpmn.model.Process boundaryProcess = process(boundaryModel);
        UserTask boundaryTask = userTask(boundaryProcess, "bounded", "带边界事件任务");
        BoundaryEvent boundaryEvent = new BoundaryEvent();
        boundaryEvent.setId("timeout-boundary");
        boundaryEvent.setAttachedToRef(boundaryTask);
        boundaryTask.getBoundaryEvents().add(boundaryEvent);
        boundaryProcess.addFlowElement(boundaryEvent);
        assertConflict(() -> movementPolicy.requireMainProcessUserTask(
                boundaryModel, boundaryProcess.getId(), boundaryTask.getId()));

        BpmnModel asyncModel = new BpmnModel();
        org.flowable.bpmn.model.Process asyncProcess = process(asyncModel);
        UserTask asyncTask = userTask(asyncProcess, "async-task", "异步任务");
        asyncTask.setAsynchronous(true);
        assertConflict(() -> movementPolicy.requireMainProcessUserTask(
                asyncModel, asyncProcess.getId(), asyncTask.getId()));
    }

    /**
     * 验证驳回只接受唯一且沿安全串行路径可达的主流程结束节点。
     *
     * @return 无返回值，唯一结束节点解析错误时测试失败
     */
    @Test
    void resolvesUniqueSerialRejectEndEvent()
    {
        BpmnFixture fixture = serialFixture();
        EndEvent endEvent = new EndEvent();
        endEvent.setId("end");
        fixture.process().addFlowElement(endEvent);
        connect(fixture.process(), fixture.currentTask(), endEvent, "flow-end");

        assertThat(movementPolicy.requireRejectEndEvent(
                fixture.process(), fixture.currentTask()).getId()).isEqualTo("end");
    }

    /**
     * 验证多个主流程结束节点不能由无目标参数的驳回接口擅自选择。
     *
     * @return 无返回值，多结束节点未返回冲突时测试失败
     */
    @Test
    void rejectsAmbiguousRejectEndEvents()
    {
        BpmnFixture fixture = serialFixture();
        EndEvent first = endEvent(fixture.process(), "end-1");
        EndEvent second = endEvent(fixture.process(), "end-2");
        connect(fixture.process(), fixture.currentTask(), first, "flow-end-1");
        connect(fixture.process(), fixture.currentTask(), second, "flow-end-2");

        assertConflict(() -> movementPolicy.requireRejectEndEvent(
                fixture.process(), fixture.currentTask()));
    }

    /**
     * 验证退回执行前必须使用实时列表匹配目标，过期目标返回状态冲突。
     *
     * @return 无返回值，过期目标被放行时测试失败
     */
    @Test
    void rejectsTargetMissingFromRecomputedReturnList()
    {
        assertConflict(() -> movementPolicy.requireLegalReturnTarget(
                "stale-target", List.of()));
    }

    /**
     * 构造包含两个历史节点和一个当前节点的主流程串行模型。
     *
     * @return BpmnFixture，可直接用于退回和驳回策略测试的模型上下文
     */
    private BpmnFixture serialFixture()
    {
        BpmnModel model = new BpmnModel();
        org.flowable.bpmn.model.Process process = process(model);
        UserTask first = userTask(process, "task-a", "申请");
        UserTask second = userTask(process, "task-b", "复核");
        UserTask current = userTask(process, "task-c", "审批");
        connect(process, first, second, "flow-a-b");
        connect(process, second, current, "flow-b-c");
        return new BpmnFixture(model, process, current);
    }

    /**
     * 创建并注册一个具有稳定 key 的 BPMN 主流程。
     *
     * @param model BpmnModel，待注册流程的模型
     * @return Process，已经加入模型的主流程
     */
    private org.flowable.bpmn.model.Process process(BpmnModel model)
    {
        org.flowable.bpmn.model.Process process = new org.flowable.bpmn.model.Process();
        process.setId("approval-process");
        process.setExecutable(true);
        model.addProcess(process);
        return process;
    }

    /**
     * 创建并注册普通用户任务。
     *
     * @param process Process，任务所属主流程
     * @param id String，任务节点 key
     * @param name String，任务显示名称
     * @return UserTask，已加入主流程的用户任务
     */
    private UserTask userTask(org.flowable.bpmn.model.Process process, String id, String name)
    {
        UserTask task = new UserTask();
        task.setId(id);
        task.setName(name);
        process.addFlowElement(task);
        return task;
    }

    /**
     * 创建并注册主流程结束节点。
     *
     * @param process Process，结束节点所属主流程
     * @param id String，结束节点 key
     * @return EndEvent，已加入主流程的结束节点
     */
    private EndEvent endEvent(org.flowable.bpmn.model.Process process, String id)
    {
        EndEvent endEvent = new EndEvent();
        endEvent.setId(id);
        process.addFlowElement(endEvent);
        return endEvent;
    }

    /**
     * 使用完整双向引用连接两个 BPMN 节点。
     *
     * @param process Process，顺序流所属主流程
     * @param source FlowNode，来源节点
     * @param target FlowNode，目标节点
     * @param id String，顺序流 key
     * @return 无返回值，顺序流注册到流程及两个端点
     */
    private void connect(org.flowable.bpmn.model.Process process,
            org.flowable.bpmn.model.FlowNode source,
            org.flowable.bpmn.model.FlowNode target, String id)
    {
        SequenceFlow flow = new SequenceFlow(source.getId(), target.getId());
        flow.setId(id);
        flow.setSourceFlowElement(source);
        flow.setTargetFlowElement(target);
        source.getOutgoingFlows().add(flow);
        target.getIncomingFlows().add(flow);
        process.addFlowElement(flow);
    }

    /**
     * 创建指定节点的历史任务替身。
     *
     * @param id String，历史任务主键
     * @param taskDefinitionKey String，BPMN 用户任务节点 key
     * @param name String，历史任务名称
     * @param deleteReason String，可为空的任务删除原因
     * @return HistoricTaskInstance，具有策略所需字段的历史任务替身
     */
    private HistoricTaskInstance historicTask(String id, String taskDefinitionKey,
            String name, String deleteReason)
    {
        HistoricTaskInstance task = mock(HistoricTaskInstance.class);
        when(task.getId()).thenReturn(id);
        when(task.getTaskDefinitionKey()).thenReturn(taskDefinitionKey);
        when(task.getName()).thenReturn(name);
        when(task.getDeleteReason()).thenReturn(deleteReason);
        return task;
    }

    /**
     * 断言动作以稳定 HTTP 409 业务异常拒绝。
     *
     * @param action Runnable，预期被策略拒绝的动作
     * @return 无返回值，异常类型或状态码错误时测试失败
     */
    private void assertConflict(Runnable action)
    {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * 流转策略测试使用的 BPMN 主流程上下文。
     *
     * @param model BpmnModel，完整模型
     * @param process Process，模型主流程
     * @param currentTask UserTask，当前活动任务节点
     */
    private record BpmnFixture(BpmnModel model,
            org.flowable.bpmn.model.Process process, UserTask currentTask)
    {
    }
}
