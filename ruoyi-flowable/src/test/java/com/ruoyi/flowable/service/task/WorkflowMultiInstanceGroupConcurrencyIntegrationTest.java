package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 聚焦真实退回与重提命令的并发串行化和 Mapper CAS 冲突。
 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowMultiInstanceGroupConcurrencyIntegrationTest
{
    /** 只调用生产公开入口的业务驱动。 */
    @Autowired
    private WorkflowMultiInstanceBusinessDriver driver;

    /** 只读取最终引擎和轮次事实的状态探针。 */
    @Autowired
    private WorkflowMultiInstanceStateProbe probe;

    /** 只把两条业务链同步到同一正式快照。 */
    @Autowired
    private WorkflowMultiInstanceFailureHook failureHook;

    /**
     * 验证两名成员基于同一 ACTIVE 轮次并发退回时严格一条提交、另一条返回 409。
     *
     * @return void，出现双提交、非稳定冲突或多个申请人任务时失败
     * @throws Exception 并发等待超时或中断时由测试框架报告
     */
    @Test
    void allowsOnlyOneOfTwoConcurrentGroupReturns() throws Exception
    {
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview",
                List.of("201", "202", "203"));
        Task first = probe.task(instance.getId(), "firstAllReview", "201");
        Task second = probe.task(instance.getId(), "firstAllReview", "202");
        failureHook.synchronizeNextTwoCopyPreparations();

        List<Throwable> results = runRace(
                () -> invokeReturn(first, "201"),
                () -> invokeReturn(second, "202"));

        assertOneSuccessAndOneConflict(results);
        Task applicantTask = probe.returnedTask(instance.getId());
        assertThat(probe.rounds(instance.getId())).singleElement().satisfies(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);
            assertThat(round.getReturnSourceTaskId()).isIn(first.getId(), second.getId());
            assertThat(round.getReturnActorUserId()).isIn("201", "202");
            assertThat(round.getApplicantTaskId()).isEqualTo(applicantTask.getId());
        });
    }

    /**
     * 验证两条重提命令基于同一 RETURNED 快照竞争时只有一条创建新轮并提交表单。
     *
     * @return void，出现双新轮、双提交或非 409 竞争结果时失败
     * @throws Exception 并发等待超时或中断时由测试框架报告
     */
    @Test
    void allowsOnlyOneOfTwoConcurrentGroupResubmits() throws Exception
    {
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview",
                List.of("201", "202", "203"));
        driver.returnGroup(probe.task(instance.getId(), "firstAllReview", "201"), "201");
        Task applicantTask = probe.returnedTask(instance.getId());
        failureHook.synchronizeNextTwoReturnedReads();

        List<Throwable> results = runRace(
                () -> invokeResubmit(applicantTask, "并发重提一"),
                () -> invokeResubmit(applicantTask, "并发重提二"));

        assertOneSuccessAndOneConflict(results);
        assertThat(probe.rounds(instance.getId()))
                .extracting(WfMultiInstanceRound::getRoundStatus)
                .containsExactly(WorkflowMultiInstanceRoundStatus.REOPENED,
                        WorkflowMultiInstanceRoundStatus.ACTIVE);
        assertThat(probe.tasks(instance.getId(), "firstAllReview"))
                .extracting(Task::getAssignee)
                .containsExactly("201", "202", "203");
    }

    /**
     * 在独立线程和真实 Spring 事务中执行两条竞争命令。
     *
     * @param first Callable&lt;Throwable&gt;，第一条命令
     * @param second Callable&lt;Throwable&gt;，第二条命令
     * @return List&lt;Throwable&gt;，null 表示对应命令成功
     * @throws Exception 线程池等待失败
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
     * 调用真实退回入口并保留生产异常作为并发结果。
     *
     * @param task Task，预冻结成员任务
     * @param actor String，真实办理人
     * @return Throwable，成功为空，失败为原生产异常
     */
    private Throwable invokeReturn(Task task, String actor)
    {
        try
        {
            driver.returnGroup(task, actor);
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    /**
     * 调用真实重提入口并保留生产异常作为并发结果。
     *
     * @param task Task，唯一申请人任务
     * @param title String，本线程表单标题
     * @return Throwable，成功为空，失败为原生产异常
     */
    private Throwable invokeResubmit(Task task, String title)
    {
        try
        {
            driver.resubmit(task, Map.of("requestTitle", title));
            return null;
        }
        catch (Throwable failure)
        {
            return failure;
        }
    }

    /**
     * 断言两条竞争命令严格一条成功、另一条使用稳定 HTTP 409 失败。
     *
     * @param results List&lt;Throwable&gt;，两条命令结果
     * @return void，数量或错误协议漂移时失败
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
}
