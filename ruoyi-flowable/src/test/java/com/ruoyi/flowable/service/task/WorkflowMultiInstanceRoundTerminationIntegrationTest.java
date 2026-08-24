package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceTerminateView;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 使用真实 Spring Flowable 事务和正式 Mapper SQL 验证三种运行实例删除入口的轮次异常关闭。
 */
class WorkflowMultiInstanceRoundTerminationIntegrationTest
{
    private WorkflowMultiInstanceRoundScenario fixture;
    private org.flowable.engine.RepositoryService repositoryService;
    private org.flowable.engine.RuntimeService runtimeService;
    private org.flowable.engine.TaskService taskService;
    private org.flowable.engine.HistoryService historyService;
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper roundMapper;
    private WorkflowMultiInstanceRoundTerminationService roundTerminationService;
    private com.ruoyi.flowable.engine.WorkflowEngineOperations engineOperations;
    private WorkflowProcessInstanceService processInstanceService;
    private PermissionService permissionService;

    /** 创建当前功能所需的轮次夹具并装配终止服务。 @return void，无返回值 */
    @BeforeEach
    void setUpFixture()
    {
        fixture = new WorkflowMultiInstanceRoundScenario();
        repositoryService = fixture.repositoryService;
        runtimeService = fixture.runtimeService;
        taskService = fixture.taskService;
        historyService = fixture.historyService;
        jdbcTemplate = fixture.jdbcTemplate;
        transactionTemplate = fixture.transactionTemplate;
        roundMapper = fixture.roundMapper;
        roundTerminationService = fixture.roundTerminationService;
        engineOperations = fixture.engineOperations;
        setUpTerminationServices();
    }

    /** 显式关闭轮次夹具。 @return void，无返回值 */
    @AfterEach
    void closeFixture()
    {
        fixture.close();
    }

    /**
     * 部署根流程与 CallActivity 子流程，并以显式 mock 装配未触达的生产依赖。
     *
     * @return void，无返回值；实际 Flowable、轮次 Mapper 和事务管理器均来自公共支撑
     */
    void setUpTerminationServices()
    {
        repositoryService.createDeployment()
                .addClasspathResource(
                        "bpmn/workflow-multi-instance-round-termination.bpmn20.xml")
                .deploy();
        permissionService = mock(PermissionService.class);
        WorkflowNotificationService notificationService =
                mock(WorkflowNotificationService.class);
        processInstanceService = new WorkflowProcessInstanceService(
                engineOperations, historyService, runtimeService, taskService,
                mock(WfAttachmentMapper.class), mock(WfCopyMapper.class),
                mock(WfControlledLoopExecutionMapper.class), roundMapper,
                roundTerminationService, permissionService,
                mock(WorkflowTaskSlaRuntimeService.class),
                notificationService);

    }

    /**
     * 验证管理员终止保留 RETURNED 轮次已有退回审计，同时写专用异常关闭时间。
     *
     * @return void，管理员公开入口或 RETURNED→TERMINATED 字段组合漂移时测试失败
     */
    @Test
    void administratorTerminatesReturnedRoundWithoutLosingReturnAudit()
    {
        RunningTree tree = startTreeAs("201");
        WfMultiInstanceRound returnedBefore = completeRootRoundAndMarkReturned(tree);
        when(permissionService.hasPermi("workflow:process:terminate")).thenReturn(true);
        setCurrentUser("999");

        WorkflowInstanceTerminateView result = transactionTemplate.execute(status ->
                processInstanceService.terminate(new WorkflowInstanceTerminateRequest(
                        tree.rootId(), "管理员终止多实例流程")));

        assertThat(result).isNotNull();
        assertThat(result.processStatus()).isEqualTo("terminated");
        assertTerminatedTree(tree, "terminated");
        WfMultiInstanceRound returnedAfter = roundMapper
                .selectByRootExecutionId(returnedBefore.getRootExecutionId());
        assertThat(returnedAfter.getReturnSourceTaskId())
                .isEqualTo(returnedBefore.getReturnSourceTaskId());
        assertThat(returnedAfter.getReturnActorUserId())
                .isEqualTo(returnedBefore.getReturnActorUserId());
        assertThat(returnedAfter.getApplicantTaskId())
                .isEqualTo(returnedBefore.getApplicantTaskId());
        assertThat(returnedAfter.getReturnTime()).isEqualTo(returnedBefore.getReturnTime());
    }

    /**
     * 验证业务轮次批量更新影响数不是预检数量时，Flowable 根删除和全部轮次写入均回滚。
     *
     * @return void，公开取消入口未返回数据异常或留下部分删除事实时测试失败
     */
    @Test
    void rollsBackFlowableDeletionWhenRoundTerminationCountDiffers()
    {
        RunningTree tree = startTreeAs("201");
        when(permissionService.hasPermi("workflow:process:cancel")).thenReturn(true);
        doReturn(0).when(roundMapper).terminateOpenByRoundIds(anySet());

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                processInstanceService.terminate(new WorkflowInstanceTerminateRequest(
                        tree.rootId(), "注入轮次更新数量冲突"))))
                .isInstanceOf(ServiceException.class)
                .hasMessage("流程多实例轮次异常关闭数量不一致")
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceIds(tree.instanceIds()).count())
                .isEqualTo(tree.instanceIds().size());
        assertThat(taskService.createTaskQuery()
                .processInstanceIdIn(tree.instanceIds()).active().count()).isEqualTo(4L);
        assertThat(roundsFor(tree)).allSatisfy(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
            assertThat(round.getTerminateTime()).isNull();
        });
    }

    /**
     * 验证活动受控根缺少正式 ACTIVE 轮次时，取消在 Flowable 删除前返回 500 并回滚引擎写入。
     *
     * @return void，缺行未被严格预检发现或其余运行事实发生变化时测试失败
     */
    @Test
    void rejectsCancellationWhenActiveRoundIsMissingAndRollsBackEngine()
    {
        RunningTree tree = startTreeAs("201");
        when(permissionService.hasPermi("workflow:process:cancel")).thenReturn(true);
        WfMultiInstanceRound removed = roundsFor(tree).stream()
                .filter(round -> "rootReview".equals(round.getActivityId()))
                .findFirst().orElseThrow();
        assertThat(jdbcTemplate.update(
                "delete from wf_multi_instance_round where round_id=?",
                removed.getRoundId())).isOne();

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                processInstanceService.terminate(new WorkflowInstanceTerminateRequest(
                        tree.rootId(), "缺失活动轮次时取消"))))
                .isInstanceOf(ServiceException.class)
                .hasMessage("工作流多实例轮次状态不一致")
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.ERROR));

        assertTerminationFailureRolledBack(tree, 4L, 1);
        assertThat(roundsFor(tree)).allSatisfy(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
            assertThat(round.getTerminateTime()).isNull();
        });
    }

    /**
     * 验证合法格式的成员、revision 和根标识被篡改后，管理员终止返回 500 且完整事务回滚。
     *
     * @return void，引擎对账未发现格式合法的字段漂移或留下部分终止事实时测试失败
     */
    @Test
    void rejectsAdministratorTerminationWhenRoundSnapshotWasTampered()
    {
        RunningTree tree = startTreeAs("201");
        WfMultiInstanceRound target = roundsFor(tree).stream()
                .filter(round -> "rootReview".equals(round.getActivityId()))
                .findFirst().orElseThrow();
        String tamperedRoot = "tampered-root-" + target.getRoundId();
        String tamperedMembers = WfMultiInstanceRound.encodeMembers(
                List.of("201", "203"));
        assertThat(jdbcTemplate.update("""
                update wf_multi_instance_round
                set root_execution_id=?, members_json=?, revision_no=?
                where round_id=? and round_status='ACTIVE'
                """, tamperedRoot, tamperedMembers, 7,
                target.getRoundId())).isOne();
        when(permissionService.hasPermi("workflow:process:terminate")).thenReturn(true);
        setCurrentUser("999");

        assertThatThrownBy(() -> transactionTemplate.execute(status ->
                processInstanceService.terminate(new WorkflowInstanceTerminateRequest(
                        tree.rootId(), "篡改轮次后管理员终止"))))
                .isInstanceOf(ServiceException.class)
                .hasMessage("工作流多实例轮次状态不一致")
                .satisfies(exception -> assertThat(
                        ((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.ERROR));

        assertTerminationFailureRolledBack(tree, 4L, 2);
        WfMultiInstanceRound tampered = roundMapper.selectByRootExecutionId(tamperedRoot);
        assertThat(tampered).isNotNull();
        assertThat(tampered.getMembersJson()).isEqualTo(tamperedMembers);
        assertThat(tampered.getRevisionNo()).isEqualTo(7);
        assertThat(tampered.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(tampered.getTerminateTime()).isNull();
    }

    /**
     * 以指定正式用户启动根+CallActivity 子流程，并冻结两级运行实例主键。
     *
     * @param userId String，写入 Flowable startUserId 的发起人用户主键
     * @return RunningTree，包含根和唯一活动子流程实例主键的冻结上下文
     */
    private RunningTree startTreeAs(String userId)
    {
        setCurrentUser(userId);
        ProcessInstance root = transactionTemplate.execute(status ->
                engineOperations.writeAsCurrentUser(() ->
                        runtimeService.startProcessInstanceByKey(
                                "roundTerminationRoot")));
        assertThat(root).isNotNull();
        ProcessInstance child = runtimeService.createProcessInstanceQuery()
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
        assertThat(taskService.createTaskQuery()
                .processInstanceIdIn(tree.instanceIds()).active().count()).isEqualTo(4L);
        return tree;
    }

    /**
     * 复核异常终止失败后完整 Flowable 树、任务、业务状态变量和剩余轮次均保持写前事实。
     *
     * @param tree RunningTree，故障动作前冻结的根及子流程实例主键
     * @param expectedTaskCount long，失败后应继续活动的任务数量
     * @param expectedRoundCount int，已考虑预置篡改后的剩余轮次数量
     * @return void，任一 Flowable 写入或轮次关闭未回滚时测试失败
     */
    private void assertTerminationFailureRolledBack(RunningTree tree,
            long expectedTaskCount, int expectedRoundCount)
    {
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceIds(tree.instanceIds()).count())
                .isEqualTo(tree.instanceIds().size());
        assertThat(taskService.createTaskQuery()
                .processInstanceIdIn(tree.instanceIds()).active().count())
                .isEqualTo(expectedTaskCount);
        ProcessInstance root = runtimeService.createProcessInstanceQuery()
                .processInstanceId(tree.rootId()).singleResult();
        assertThat(root).isNotNull();
        assertThat(root.getBusinessStatus()).isNull();
        assertThat(runtimeService.getVariable(tree.rootId(), "processStatus")).isNull();
        assertThat(roundsFor(tree)).hasSize(expectedRoundCount)
                .allSatisfy(round -> assertThat(round.getTerminateTime()).isNull());
    }

    /**
     * 先正式完成根流程会签使其离开活动根，再把已完成轮次构造成合法 RETURNED 审计夹具。
     *
     * @param tree RunningTree，根和子流程都处于首轮 ACTIVE 的运行流程树
     * @return WfMultiInstanceRound，根活动已离开、子流程仍活动时重新读取的 RETURNED 轮次
     */
    private WfMultiInstanceRound completeRootRoundAndMarkReturned(RunningTree tree)
    {
        setCurrentUser("201");
        complete(task(tree.rootId(), "rootReview", "201"), 0);
        setCurrentUser("202");
        complete(task(tree.rootId(), "rootReview", "202"), 1);
        WfMultiInstanceRound completed = roundsFor(tree).stream()
                .filter(round -> "rootReview".equals(round.getActivityId()))
                .findFirst().orElseThrow();
        assertThat(completed.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.COMPLETED);
        assertThat(tasks(tree.rootId(), "rootReview")).isEmpty();
        assertThat(tasks(tree.instanceIds().stream()
                .filter(instanceId -> !instanceId.equals(tree.rootId()))
                .findFirst().orElseThrow(), "childReview")).hasSize(2);

        // RETURNED 只能表示已离开活动根的合法开放审计；清除正常完成时间并使用数据库退回时间。
        int affected = jdbcTemplate.update("""
                update wf_multi_instance_round
                set round_status='RETURNED', return_source_task_id=?,
                    return_actor_user_id=?, applicant_task_id=?,
                    return_time=current_timestamp(3), complete_time=null
                where round_id=? and round_status='COMPLETED'
                """, "return-source-task", "201", "applicant-task",
                completed.getRoundId());
        assertThat(affected).isOne();
        WfMultiInstanceRound returned = roundMapper
                .selectByRootExecutionId(completed.getRootExecutionId());
        returned.requireValidLifecycle();
        return returned;
    }

    /**
     * 复核 Flowable 完整树已删除、根历史业务状态正确且全部轮次为专用异常终态。
     *
     * @param tree RunningTree，终止前冻结的完整运行流程树
     * @param expectedBusinessStatus String，canceled、rejected 或 terminated
     * @return void，任一引擎、历史或业务轮次事实不一致时测试失败
     */
    private void assertTerminatedTree(RunningTree tree, String expectedBusinessStatus)
    {
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceIds(tree.instanceIds()).count()).isZero();
        assertThat(taskService.createTaskQuery()
                .processInstanceIdIn(tree.instanceIds()).count()).isZero();
        assertThat(historyService.createHistoricProcessInstanceQuery()
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
        assertThat(roundMapper.countOpenByProcessInstanceIds(tree.instanceIds())).isZero();
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
            rounds.addAll(roundMapper.selectByProcessInstanceId(instanceId));
        }
        rounds.sort(java.util.Comparator.comparing(WfMultiInstanceRound::getRoundId));
        return rounds;
    }

    /** 委派测试身份切换。 @param userId String，用户 ID @return void，无返回值 */
    private void setCurrentUser(String userId)
    {
        fixture.setCurrentUser(userId);
    }

    /** 委派受控任务完成。 @param task Task，任务 @param revision int，预期 revision @return void，无返回值 */
    private void complete(Task task, int revision)
    {
        fixture.complete(task, revision);
    }

    /** 委派唯一任务查询。 @param processInstanceId String，实例 ID @param activityId String，活动 ID @param assignee String，办理人 @return Task，唯一任务 */
    private Task task(String processInstanceId, String activityId, String assignee)
    {
        return fixture.task(processInstanceId, activityId, assignee);
    }

    /** 委派任务列表查询。 @param processInstanceId String，实例 ID @param activityId String，活动 ID @return List，活动任务 */
    private List<Task> tasks(String processInstanceId, String activityId)
    {
        return fixture.tasks(processInstanceId, activityId);
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
}
