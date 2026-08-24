package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 聚焦验证整组退回与完成、加签、减签和终止的并发竞争。
 */
class WorkflowMultiInstanceGroupConcurrencyIntegrationTest
{
    private WorkflowGroupReturnScenario fixture;
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path attachmentRoot;

    /** 创建当前功能所需的整组迁移夹具。 @return void，无返回值 */
    @BeforeEach
    void setUpFixture()
    {
        fixture = new WorkflowGroupReturnScenario(attachmentRoot);
    }

    /** 显式关闭整组迁移夹具。 @return void，无返回值 */
    @AfterEach
    void closeFixture()
    {
        fixture.close();
    }
    /**
     * 让两名真实成员在准备链结束后同时退回，验证只能一个成功且另一个稳定返回 409。
     *
     * @return void，出现双轮次、两个申请人任务或非 409 竞争结果时失败
     * @throws Exception 线程池等待被中断或超时时由测试框架报告
     */
    @Test
    void allowsOnlyOneOfTwoConcurrentGroupReturns() throws Exception
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", members);
        Task firstSource = fixture.task(instance.getId(), "firstAllReview", "201");
        Task secondSource = fixture.task(instance.getId(), "firstAllReview", "202");
        CyclicBarrier preparedBarrier = new CyclicBarrier(2);
        doAnswer(invocation ->
        {
            preparedBarrier.await(10, TimeUnit.SECONDS);
            return WorkflowTaskCopyService.CopyPlan.empty();
        }).when(fixture.taskCopyService).prepare(any(WorkflowTaskCopyAction.class),
                any(Task.class), any(com.ruoyi.flowable.identity.WorkflowCurrentIdentity.class),
                anyList());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<Throwable> first = executor.submit(
                    () -> invokeReturn(firstSource, "201"));
            Future<Throwable> second = executor.submit(
                    () -> invokeReturn(secondSource, "202"));
            // List.of 禁止 null，而 null 在此明确表示一条写链成功。
            List<Throwable> results = Arrays.asList(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));

            assertThat(results).filteredOn(failure -> failure == null).hasSize(1);
            assertThat(results).filteredOn(failure -> failure != null)
                    .singleElement().satisfies(failure ->
                    {
                        assertThat(failure).isInstanceOf(ServiceException.class);
                        assertThat(((ServiceException) failure).getCode())
                                .isEqualTo(HttpStatus.CONFLICT);
                    });
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        Task applicantTask = fixture.returnedTask(instance.getId());
        assertThat(fixture.rounds(instance.getId())).singleElement().satisfies(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);
            assertThat(round.getReturnSourceTaskId())
                    .isIn(firstSource.getId(), secondSource.getId());
            assertThat(round.getReturnActorUserId()).isIn("201", "202");
            assertThat(round.getApplicantTaskId()).isEqualTo(applicantTask.getId());
        });
        assertThat(fixture.roundMapper.selectActiveByProcessInstanceAndActivity(
                instance.getId(), "firstAllReview")).isEmpty();
    }

    /**
     * 让整组退回与另一成员完成在各自生产准备链后同时写入，验证只提交一种状态转换。
     *
     * @return void，出现完成与退回双提交、多个开放轮次或孤立审批任务时失败
     * @throws Exception 并发等待超时或线程中断时由测试框架报告
     */
    @Test
    void serializesGroupReturnAgainstMemberCompletion() throws Exception
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", members);
        Task returnSource = fixture.task(instance.getId(), "firstAllReview", "201");
        Task completeSource = fixture.task(instance.getId(), "firstAllReview", "202");
        synchronizeNextTwoCopyPreparations();

        List<Throwable> results = runRace(
                () -> invokeReturn(returnSource, "201"),
                () -> invokeComplete(completeSource, "202", 0L));

        assertOneSuccessAndOneConflict(results);
        assertSingleOpenRound(instance.getId(), "firstAllReview");
        if (results.get(0) == null)
        {
            assertReturnedWinner(instance.getId(), "firstAllReview", members);
        }
        else
        {
            assertActiveWinner(instance.getId(), "firstAllReview",
                    members, List.of("201", "203"), 1);
        }
    }

    /**
     * 让整组退回与加签在读取同一 ACTIVE 轮次后竞争，验证成员扩展或退回至多一个提交。
     *
     * @return void，出现双 revision、双开放轮次或不属于正式根的任务时失败
     * @throws Exception 并发等待超时或线程中断时由测试框架报告
     */
    @Test
    void serializesGroupReturnAgainstMemberAddition() throws Exception
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", members);
        Task returnSource = fixture.task(instance.getId(), "firstAllReview", "201");
        Task adjustmentSource = fixture.task(instance.getId(), "firstAllReview", "202");
        synchronizeFirstTwoOpenRoundReads();

        List<Throwable> results = runRace(
                () -> invokeReturn(returnSource, "201"),
                () -> invokeAdd(adjustmentSource, 204L));

        assertOneSuccessAndOneConflict(results);
        assertSingleOpenRound(instance.getId(), "firstAllReview");
        if (results.get(0) == null)
        {
            assertReturnedWinner(instance.getId(), "firstAllReview", members);
        }
        else
        {
            assertActiveWinner(instance.getId(), "firstAllReview",
                    List.of("201", "202", "203", "204"),
                    List.of("201", "202", "203", "204"), 1);
        }
    }

    /**
     * 让整组退回与减签在读取同一 ACTIVE 轮次后竞争，验证成员删除或退回至多一个提交。
     *
     * @return void，出现被删除成员孤立任务、双轮次或状态与成员快照分裂时失败
     * @throws Exception 并发等待超时或线程中断时由测试框架报告
     */
    @Test
    void serializesGroupReturnAgainstMemberRemoval() throws Exception
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", members);
        Task returnSource = fixture.task(instance.getId(), "firstAllReview", "201");
        Task adjustmentSource = fixture.task(instance.getId(), "firstAllReview", "202");
        Task removalTarget = fixture.task(instance.getId(), "firstAllReview", "203");
        synchronizeFirstTwoOpenRoundReads();

        List<Throwable> results = runRace(
                () -> invokeReturn(returnSource, "201"),
                () -> invokeRemove(adjustmentSource, removalTarget));

        assertOneSuccessAndOneConflict(results);
        assertSingleOpenRound(instance.getId(), "firstAllReview");
        if (results.get(0) == null)
        {
            assertReturnedWinner(instance.getId(), "firstAllReview", members);
        }
        else
        {
            assertActiveWinner(instance.getId(), "firstAllReview",
                    List.of("201", "202"), List.of("201", "202"), 1);
        }
    }

    /**
     * 让整组退回与生产管理员终止入口在同一瞬间竞争，允许按数据库锁顺序串行化，
     * 但最终不得出现两个开放轮次、无 execution 的孤立任务或终止后遗留任务。
     *
     * @return void，双失败、双开放轮次、孤立任务或终止状态漂移时失败
     * @throws Exception 并发等待超时或线程中断时由测试框架报告
     */
    @Test
    void serializesGroupReturnAgainstProcessTermination() throws Exception
    {
        List<String> members = List.of("201", "202", "203");
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", members);
        Task returnSource = fixture.task(instance.getId(), "firstAllReview", "201");
        WorkflowProcessInstanceService terminationService = terminationService();
        CyclicBarrier startBarrier = new CyclicBarrier(2);

        List<Throwable> results = runRace(
                () ->
                {
                    startBarrier.await(10, TimeUnit.SECONDS);
                    return invokeReturn(returnSource, "201");
                },
                () ->
                {
                    startBarrier.await(10, TimeUnit.SECONDS);
                    return invokeTermination(terminationService, instance.getId());
                });

        // 退回后再终止允许两条命令依锁顺序先后成功；终止先胜出时退回必须稳定失败。
        assertThat(results).anyMatch(result -> result == null);
        assertThat(results).filteredOn(result -> result != null)
                .allSatisfy(failure ->
                {
                    assertThat(failure).isInstanceOf(ServiceException.class);
                    assertThat(((ServiceException) failure).getCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                });

        List<com.ruoyi.flowable.domain.WfMultiInstanceRound> persistedRounds =
                fixture.rounds(instance.getId());
        assertThat(persistedRounds).singleElement();
        List<com.ruoyi.flowable.domain.WfMultiInstanceRound> openRounds = fixture.roundMapper
                .selectOpenByProcessInstanceAndActivity(instance.getId(), "firstAllReview");
        assertThat(openRounds).hasSizeLessThanOrEqualTo(1);
        List<Task> activeTasks = fixture.taskService.createTaskQuery()
                .processInstanceId(instance.getId()).active().list();
        ProcessInstance activeInstance = fixture.runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult();
        if (activeInstance == null)
        {
            assertThat(activeTasks).isEmpty();
            assertThat(fixture.runtimeService.createExecutionQuery()
                    .processInstanceId(instance.getId()).count()).isZero();
            assertThat(openRounds).isEmpty();
            assertThat(persistedRounds.get(0).getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.TERMINATED);
        }
        else
        {
            assertThat(openRounds).singleElement().satisfies(round ->
                    assertThat(round.getRoundStatus())
                            .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED));
            assertThat(activeTasks).singleElement().satisfies(task ->
            {
                assertThat(task.getAssignee()).isEqualTo(fixture.APPLICANT_ID);
                assertThat(fixture.runtimeService.createExecutionQuery()
                        .executionId(task.getExecutionId()).singleResult()).isNotNull();
            });
            fixture.assertDoubleStatus(instance.getId(), WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
        }
    }
    /**
     * 使用本组真实 Flowable、轮次服务、正式附件 Mapper 和同一事务引擎构造生产终止服务。
     *
     * @return WorkflowProcessInstanceService，管理员终止权限已通过正式权限依赖复核
     */
    private WorkflowProcessInstanceService terminationService()
    {
        PermissionService permissionService = mock(PermissionService.class);
        when(permissionService.hasPermi("workflow:process:terminate")).thenReturn(true);
        return new WorkflowProcessInstanceService(fixture.engineOperations, fixture.historyService,
                fixture.runtimeService, fixture.taskService, fixture.attachmentMapper, mock(WfCopyMapper.class),
                mock(WfControlledLoopExecutionMapper.class), fixture.roundMapper,
                fixture.roundTerminationService,
                permissionService, mock(WorkflowTaskSlaRuntimeService.class),
                fixture.notificationService);
    }

    /**
     * 在线程隔离管理员身份下调用真实流程终止入口，并把结果转换成并发断言事实。
     *
     * @param terminationService WorkflowProcessInstanceService，生产终止服务
     * @param processInstanceId String，待终止根流程实例主键
     * @return Throwable，成功返回 null，竞争失败返回生产异常
     */
    private Throwable invokeTermination(
            WorkflowProcessInstanceService terminationService,
            String processInstanceId)
    {
        try
        {
            fixture.setCurrentUser("999");
            terminationService.terminate(new WorkflowInstanceTerminateRequest(
                    processInstanceId, "并发终止整组退回流程"));
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    /**
     * 在线程隔离身份下执行一次生产退回并把异常作为并发结果返回。
     *
     * @param source Task，线程预冻结的真实成员任务
     * @param actorUserId String，该线程的真实办理人
     * @return Throwable，成功返回 null，失败返回生产链抛出的异常
     */
    private Throwable invokeReturn(Task source, String actorUserId)
    {
        try
        {
            fixture.setCurrentUser(actorUserId);
            fixture.lifecycleService.returnTask(new WorkflowTaskReturnRequest(
                    source.getId(), "并发整组退回", List.of()));
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    /**
     * 在线程隔离身份下通过生产生命周期服务完成一个多实例成员任务。
     *
     * @param source Task，待完成的真实成员任务
     * @param actorUserId String，来源任务真实办理人
     * @param expectedRevision Long，客户端冻结的当前 revision
     * @return Throwable，成功返回 null，竞争失败返回生产异常
     */
    private Throwable invokeComplete(Task source, String actorUserId,
            Long expectedRevision)
    {
        try
        {
            fixture.setCurrentUser(actorUserId);
            fixture.lifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                    source.getId(), "并发完成审批", java.util.Map.of(),
                    List.of(), List.of(), expectedRevision));
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    /**
     * 在线程隔离身份下通过生产多实例服务执行加签。
     *
     * @param source Task，当前操作人的真实成员任务
     * @param addedUserId long，待新增用户主键
     * @return Throwable，成功返回 null，竞争失败返回生产异常
     */
    private Throwable invokeAdd(Task source, long addedUserId)
    {
        try
        {
            fixture.addMember(source, 0, addedUserId);
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    /**
     * 在线程隔离身份下通过生产多实例服务执行减签。
     *
     * @param source Task，当前操作人的真实成员任务
     * @param target Task，待移除的同根成员任务
     * @return Throwable，成功返回 null，竞争失败返回生产异常
     */
    private Throwable invokeRemove(Task source, Task target)
    {
        try
        {
            fixture.removeMember(source, target, 0);
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    /**
     * 在两个动作都完成抄送准备后同时放行，确保退回与完成基于同一前置状态竞争。
     *
     * @return void，无返回值；屏障超时会作为动作失败暴露
     */
    private void synchronizeNextTwoCopyPreparations()
    {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation ->
        {
            if (calls.incrementAndGet() <= 2)
            {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return WorkflowTaskCopyService.CopyPlan.empty();
        }).when(fixture.taskCopyService).prepare(any(WorkflowTaskCopyAction.class),
                any(Task.class),
                any(com.ruoyi.flowable.identity.WorkflowCurrentIdentity.class),
                anyList());
    }

    /**
     * 在两个动作首次读取正式 OPEN 轮次时同步放行，随后所有查询继续执行真实 Mapper SQL。
     *
     * @return void，无返回值；仅前两次读取参与屏障，监听器写后查询不会再次等待
     */
    private void synchronizeFirstTwoOpenRoundReads()
    {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation ->
        {
            if (calls.incrementAndGet() <= 2)
            {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return fixture.roundMapperDelegate.selectOpenByProcessInstanceAndActivity(
                    invocation.getArgument(0), invocation.getArgument(1));
        }).when(fixture.roundMapper).selectOpenByProcessInstanceAndActivity(
                anyString(), anyString());
    }

    /**
     * 在独立线程和真实 Spring 事务中执行两条竞争写链。
     *
     * @param first Callable&lt;Throwable&gt;，第一条竞争动作
     * @param second Callable&lt;Throwable&gt;，第二条竞争动作
     * @return List&lt;Throwable&gt;，按动作顺序返回；null 表示对应动作成功
     * @throws Exception 线程等待超时、中断或执行器异常
     */
    private List<Throwable> runRace(Callable<Throwable> first,
            Callable<Throwable> second) throws Exception
    {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<Throwable> firstResult = executor.submit(first);
            Future<Throwable> secondResult = executor.submit(second);
            return Arrays.asList(firstResult.get(20, TimeUnit.SECONDS),
                    secondResult.get(20, TimeUnit.SECONDS));
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 断言两条竞争动作严格一条提交，另一条使用稳定 HTTP 409 契约失败。
     *
     * @param results List&lt;Throwable&gt;，按动作顺序返回的并发结果
     * @return void，双成功、双失败或非稳定冲突均失败
     */
    private void assertOneSuccessAndOneConflict(List<Throwable> results)
    {
        assertThat(results).filteredOn(failure -> failure == null).hasSize(1);
        assertThat(results).filteredOn(failure -> failure != null)
                .singleElement().satisfies(failure ->
                {
                    assertThat(failure).isInstanceOf(ServiceException.class);
                    assertThat(((ServiceException) failure).getCode())
                            .isEqualTo(HttpStatus.CONFLICT);
                });
    }

    /**
     * 断言同实例节点始终只有一条 ACTIVE 或 RETURNED 开放轮次。
     *
     * @param processInstanceId String，竞争流程实例主键
     * @param activityId String，受控多实例节点主键
     * @return void，开放轮次为空或重复时失败
     */
    private void assertSingleOpenRound(String processInstanceId, String activityId)
    {
        assertThat(fixture.roundMapper.selectOpenByProcessInstanceAndActivity(
                processInstanceId, activityId)).singleElement();
    }

    /**
     * 断言退回动作胜出后完整冻结原成员，且只存在唯一申请人任务。
     *
     * @param processInstanceId String，竞争流程实例主键
     * @param activityId String，受控多实例节点主键
     * @param members List&lt;String&gt;，竞争前完整有序成员
     * @return void，旧成员任务或额外开放轮次残留时失败
     */
    private void assertReturnedWinner(String processInstanceId, String activityId,
            List<String> members)
    {
        Task applicantTask = fixture.returnedTask(processInstanceId);
        assertThat(fixture.rounds(processInstanceId)).singleElement().satisfies(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);
            assertThat(round.getMembers()).containsExactlyElementsOf(members);
            assertThat(round.getApplicantTaskId()).isEqualTo(applicantTask.getId());
        });
        assertThat(fixture.taskService.createTaskQuery().processInstanceId(processInstanceId)
                .taskDefinitionKey(activityId).active().list())
                .extracting(Task::getAssignee).containsExactly(fixture.APPLICANT_ID);
    }

    /**
     * 断言完成、加签或减签胜出后成员快照、revision、任务与唯一根严格一致。
     *
     * @param processInstanceId String，竞争流程实例主键
     * @param activityId String，受控多实例节点主键
     * @param snapshotMembers List&lt;String&gt;，胜出动作后的完整有序成员快照
     * @param activeAssignees List&lt;String&gt;，胜出动作后的活动办理人
     * @param revision int，胜出动作后的 revision
     * @return void，出现孤立任务、孤立根或业务轮次漂移时失败
     */
    private void assertActiveWinner(String processInstanceId, String activityId,
            List<String> snapshotMembers, List<String> activeAssignees, int revision)
    {
        com.ruoyi.flowable.domain.WfMultiInstanceRound round =
                fixture.activeRound(processInstanceId, activityId);
        assertThat(round.getMembers()).containsExactlyElementsOf(snapshotMembers);
        assertThat(round.getRevisionNo()).isEqualTo(revision);
        List<Task> activeTasks = fixture.tasks(processInstanceId, activityId);
        assertThat(activeTasks).extracting(Task::getAssignee)
                .containsExactlyElementsOf(activeAssignees.stream().sorted().toList());
        assertThat(activeTasks.stream().map(Task::getExecutionId)
                .map(executionId -> fixture.runtimeService.createExecutionQuery()
                        .executionId(executionId).singleResult())
                .map(Execution::getParentId).collect(Collectors.toSet()))
                .containsExactly(round.getRootExecutionId());
        assertThat(fixture.runtimeService.createExecutionQuery()
                .executionId(round.getRootExecutionId()).singleResult()).isNotNull();
    }
}
