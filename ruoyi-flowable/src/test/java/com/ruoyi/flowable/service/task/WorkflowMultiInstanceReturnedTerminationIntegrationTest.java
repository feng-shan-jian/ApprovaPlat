package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener;
import org.flowable.engine.delegate.event.FlowableActivityCancelledEvent;
import org.flowable.engine.delegate.event.FlowableCancelledEvent;
import org.flowable.engine.delegate.event.FlowableMultiInstanceActivityCancelledEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceTerminateView;

/**
 * 使用真实 Flowable 取消事件验证首节点多实例退回期间的显式终止闭环。
 */
class WorkflowMultiInstanceReturnedTerminationIntegrationTest
{
    private WorkflowGroupReturnScenario fixture;
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path attachmentRoot;

    /** 创建当前功能所需的整组迁移夹具。 @return void，无返回值 */
    @BeforeEach
    void setUpFixture()
    {
        fixture = new WorkflowGroupReturnScenario(attachmentRoot);
        setUpTerminationService();
    }

    /** 显式关闭整组迁移夹具。 @return void，无返回值 */
    @AfterEach
    void closeFixture()
    {
        fixture.close();
    }
    /** 仅记录本用例显式删除阶段取消事件顺序的观察器。 */
    private CancellationTraceListener cancellationTrace;

    /**
     * 在公共真实引擎装配完成后创建使用同一轮次服务和事务边界的终止服务。
     *
     * @return void，后续用例通过正式 terminate 入口触发根删除和取消监听器
     */
    void setUpTerminationService()
    {
        fixture.repositoryService.createDeployment()
                .addClasspathResource(
                        "bpmn/workflow-multi-instance-round-termination.bpmn20.xml")
                .deploy();
        cancellationTrace = new CancellationTraceListener();
        fixture.processEngine.getProcessEngineConfiguration().getEventDispatcher()
                .addEventListener(cancellationTrace);
    }

    /**
     * 验证发起人通过完整生命周期取消流程树时，根和子流程 ACTIVE 轮次均异常关闭。
     *
     * @return void，流程树、历史状态或任一轮次终态不一致时测试失败
     */
    @Test
    void cancelsAllOpenRoundsInCompleteProcessTree()
    {
        RunningTree tree = startTreeAs("201");

        fixture.transactionTemplate.executeWithoutResult(status ->
                fixture.lifecycleService.cancelProcess(new WorkflowProcessCancelRequest(
                        tree.rootId(), "发起人取消多实例流程")));

        assertTerminatedTree(tree, "canceled");
    }

    /**
     * 验证从 CallActivity 子流程多实例任务驳回时，统一根删除链关闭全部开放轮次。
     *
     * @return void，公开驳回入口未原子结束流程树和轮次时测试失败
     */
    @Test
    void rejectsAllOpenRoundsInCompleteProcessTree()
    {
        RunningTree tree = startTreeAs("201");
        String childId = tree.instanceIds().stream()
                .filter(instanceId -> !instanceId.equals(tree.rootId()))
                .findFirst().orElseThrow();
        Task assigneeTask = fixture.taskService.createTaskQuery()
                .processInstanceId(childId)
                .taskAssignee("201").active().list().stream().findFirst().orElseThrow();
        fixture.setCurrentUser("201");

        fixture.transactionTemplate.executeWithoutResult(status ->
                fixture.lifecycleService.rejectTask(new WorkflowTaskRejectRequest(
                        assigneeTask.getId(), "办理人驳回多实例流程", List.of())));

        assertTerminatedTree(tree, "rejected");
    }

    /**
     * 验证首审批即受控 MI 时，RETURNED 临时单成员根不会阻断管理员显式终止。
     *
     * @return void，预检、取消事件、流程删除或 RETURNED→TERMINATED 任一步漂移时失败
     */
    @Test
    void terminatesReturnedFirstMultiInstanceApplicantRoot()
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", members);
        WfMultiInstanceRound active = fixture.activeRound(instance.getId(), "firstAllReview");
        Task source = fixture.task(instance.getId(), "firstAllReview", "202");

        fixture.returnGroup(source, "202");

        Task applicantTask = fixture.returnedTask(instance.getId());
        WfMultiInstanceRound returned = fixture.rounds(instance.getId()).get(0);
        Execution applicantExecution = fixture.runtimeService.createExecutionQuery()
                .executionId(applicantTask.getExecutionId()).singleResult();
        assertThat(applicantExecution).isNotNull();
        assertThat(applicantExecution.getParentId())
                .isNotBlank()
                .isNotEqualTo(active.getRootExecutionId());
        assertThat(returned.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);
        assertThat(returned.getApplicantTaskId()).isEqualTo(applicantTask.getId());
        assertThat(returned.getRootExecutionId()).isEqualTo(active.getRootExecutionId());

        // 单独执行同一生产预检，证明失败若发生在后续显式删除及同步取消监听阶段。
        MultiInstanceRoundTerminationPlan precheck =
                fixture.transactionTemplate.execute(status ->
                fixture.roundTerminationService.precheckTermination(
                        List.of(instance.getId())));
        assertThat(precheck).isNotNull();
        cancellationTrace.clear();

        when(fixture.processPermissionService.hasPermi("workflow:process:terminate"))
                .thenReturn(true);
        fixture.setCurrentUser("999");
        WorkflowInstanceTerminateView result = fixture.processInstanceService.terminate(
                new WorkflowInstanceTerminateRequest(instance.getId(),
                        "终止退回待修改流程"));

        assertThat(result.processStatus()).isEqualTo("terminated");
        assertThat(fixture.runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).count()).isZero();
        assertThat(fixture.taskService.createTaskQuery()
                .processInstanceId(instance.getId()).count()).isZero();
        assertThat(fixture.historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()
                .getBusinessStatus()).isEqualTo("terminated");

        WfMultiInstanceRound terminated = fixture.roundMapper.selectByRootExecutionId(
                active.getRootExecutionId());
        assertThat(terminated.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.TERMINATED);
        assertThat(terminated.getTerminateTime()).isNotNull();
        assertThat(terminated.getReturnSourceTaskId())
                .isEqualTo(returned.getReturnSourceTaskId());
        assertThat(terminated.getReturnActorUserId())
                .isEqualTo(returned.getReturnActorUserId());
        assertThat(terminated.getApplicantTaskId())
                .isEqualTo(returned.getApplicantTaskId());
        assertThat(terminated.getReturnTime()).isEqualTo(returned.getReturnTime());
        assertThat(fixture.roundMapper.countOpenByProcessInstanceIds(
                Set.of(instance.getId()))).isZero();
        assertThat(cancellationTrace.events())
                .as("显式删除阶段真实取消事件顺序")
                .containsExactly(
                        new CancellationEventFact("ACTIVITY_CANCELLED",
                                applicantExecution.getId(), "firstAllReview",
                                false, false),
                        new CancellationEventFact("PROCESS_CANCELLED",
                                instance.getId(), null, false, false));
    }

    /**
     * 以指定正式用户启动根和 CallActivity 子流程，并冻结完整运行树。
     *
     * @param userId String，写入 Flowable startUserId 的发起人用户主键
     * @return RunningTree，包含根和唯一活动子流程实例主键
     */
    private RunningTree startTreeAs(String userId)
    {
        fixture.setCurrentUser(userId);
        ProcessInstance root = fixture.transactionTemplate.execute(status ->
                fixture.engineOperations.writeAsCurrentUser(() ->
                        fixture.runtimeService.startProcessInstanceByKey(
                                "roundTerminationRoot")));
        assertThat(root).isNotNull();
        ProcessInstance child = fixture.runtimeService.createProcessInstanceQuery()
                .superProcessInstanceId(root.getId()).singleResult();
        assertThat(child).isNotNull();
        RunningTree tree = new RunningTree(root.getId(),
                new LinkedHashSet<>(List.of(root.getId(), child.getId())));
        assertThat(roundsFor(tree)).hasSize(2).allSatisfy(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
            round.requireValidLifecycle();
        });
        assertThat(fixture.taskService.createTaskQuery()
                .processInstanceIdIn(tree.instanceIds()).active().count()).isEqualTo(4L);
        return tree;
    }

    /**
     * 复核完整树已删除、根历史业务状态正确且全部轮次进入专用异常终态。
     *
     * @param tree RunningTree，终止前冻结的完整运行流程树
     * @param expectedBusinessStatus String，canceled 或 rejected
     * @return void，任一引擎、历史或业务轮次事实不一致时测试失败
     */
    private void assertTerminatedTree(RunningTree tree, String expectedBusinessStatus)
    {
        assertThat(fixture.runtimeService.createProcessInstanceQuery()
                .processInstanceIds(tree.instanceIds()).count()).isZero();
        assertThat(fixture.taskService.createTaskQuery()
                .processInstanceIdIn(tree.instanceIds()).count()).isZero();
        assertThat(fixture.historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(tree.rootId()).singleResult().getBusinessStatus())
                .isEqualTo(expectedBusinessStatus);
        List<WfMultiInstanceRound> rounds = roundsFor(tree);
        assertThat(rounds).hasSize(2).allSatisfy(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.TERMINATED);
            assertThat(round.getTerminateTime()).isNotNull();
            assertThat(round.getCompleteTime()).isNull();
            round.requireValidLifecycle();
        });
        assertThat(fixture.roundMapper.countOpenByProcessInstanceIds(tree.instanceIds())).isZero();
    }

    /**
     * 查询完整流程树的全部轮次并按轮次主键稳定排序。
     *
     * @param tree RunningTree，根及子流程实例主键
     * @return List&lt;WfMultiInstanceRound&gt;，跨实例合并后的正式轮次记录
     */
    private List<WfMultiInstanceRound> roundsFor(RunningTree tree)
    {
        List<WfMultiInstanceRound> rounds = new ArrayList<>();
        for (String instanceId : tree.instanceIds())
        {
            rounds.addAll(fixture.roundMapper.selectByProcessInstanceId(instanceId));
        }
        rounds.sort(java.util.Comparator.comparing(WfMultiInstanceRound::getRoundId));
        return rounds;
    }

    /**
     * 终止动作前冻结的完整运行流程树。
     *
     * @param rootId String，根业务流程实例主键
     * @param instanceIds Set&lt;String&gt;，根与全部活动 CallActivity 子实例主键
     */
    private record RunningTree(String rootId, Set<String> instanceIds)
    {
        /**
         * 复制流程树主键，避免测试动作期间修改预检集合。
         *
         * @param rootId String，根业务流程实例主键
         * @param instanceIds Set&lt;String&gt;，冻结的完整实例主键集合
         * @return void，无返回值
         */
        private RunningTree
        {
            instanceIds = Set.copyOf(instanceIds);
        }
    }

    /**
     * 记录显式终止命令中的活动取消和流程取消事实，不参与任何生产判断。
     */
    private static final class CancellationTraceListener
            extends AbstractFlowableEngineEventListener
    {
        /** 当前测试命令按派发顺序记录的取消事实。 */
        private final List<CancellationEventFact> events = new ArrayList<>();

        /**
         * 创建只订阅取消类事件的测试观察器。
         *
         * @return 无返回值，观察器不会修改 Flowable 或业务状态
         */
        private CancellationTraceListener()
        {
            super(Set.of(FlowableEngineEventType.ACTIVITY_CANCELLED,
                    FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_CANCELLED,
                    FlowableEngineEventType.PROCESS_CANCELLED));
        }

        /**
         * 记录普通活动取消事件及其 execution 根属性。
         *
         * @param event FlowableActivityCancelledEvent，引擎同步派发的活动取消事实
         * @return void，仅追加不可变测试事实
         */
        @Override
        protected void activityCancelled(FlowableActivityCancelledEvent event)
        {
            record("ACTIVITY_CANCELLED", event);
        }

        /**
         * 记录专用多实例根取消事件及其 execution 根属性。
         *
         * @param event FlowableMultiInstanceActivityCancelledEvent，引擎同步派发的多实例取消事实
         * @return void，仅追加不可变测试事实
         */
        @Override
        protected void multiInstanceActivityCancelled(
                FlowableMultiInstanceActivityCancelledEvent event)
        {
            record("MULTI_INSTANCE_ACTIVITY_CANCELLED", event);
        }

        /**
         * 记录流程实例取消事件，供断言其相对活动取消的派发顺序。
         *
         * @param event FlowableCancelledEvent，引擎同步派发的流程取消事实
         * @return void，仅追加不可变测试事实
         */
        @Override
        protected void processCancelled(FlowableCancelledEvent event)
        {
            DelegateExecution execution = getExecution(event);
            events.add(new CancellationEventFact("PROCESS_CANCELLED",
                    event.getExecutionId(), null,
                    execution instanceof ExecutionEntity entity
                            && entity.isMultiInstanceRoot(),
                    execution instanceof ExecutionEntity entity
                            && entity.isDeleted()));
        }

        /**
         * 把活动取消事件转换成稳定、可直接断言的不可变事实。
         *
         * @param type String，普通活动取消或专用多实例取消类型
         * @param event FlowableActivityCancelledEvent，待读取的真实事件
         * @return void，仅追加不可变测试事实
         */
        private void record(String type, FlowableActivityCancelledEvent event)
        {
            DelegateExecution execution = getExecution(event);
            events.add(new CancellationEventFact(type, event.getExecutionId(),
                    event.getActivityId(),
                    execution instanceof ExecutionEntity entity
                            && entity.isMultiInstanceRoot(),
                    execution instanceof ExecutionEntity entity
                            && entity.isDeleted()));
        }

        /**
         * 清除退回迁移本身产生的取消事件，只保留后续显式终止命令事实。
         *
         * @return void，清除后顺序从下一次事件重新开始
         */
        private void clear()
        {
            events.clear();
        }

        /**
         * 返回当前显式终止命令的取消事件快照。
         *
         * @return List&lt;CancellationEventFact&gt;，保持 Flowable 派发顺序的不可变副本
         */
        private List<CancellationEventFact> events()
        {
            return List.copyOf(events);
        }
    }

    /**
     * Flowable 取消事件及同步 execution 状态的测试事实。
     *
     * @param type String，事件类型
     * @param executionId String，事件绑定 execution 主键
     * @param activityId String，活动取消事件节点 key；流程取消为空
     * @param multiInstanceRoot boolean，事件派发时 execution 是否为多实例根
     * @param deleted boolean，事件派发时 execution 是否已经标记删除
     */
    private record CancellationEventFact(String type, String executionId,
            String activityId, boolean multiInstanceRoot, boolean deleted)
    {
    }
}
