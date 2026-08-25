package com.ruoyi.flowable.service.task;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flowable.task.api.Task;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;

/**
 * 仅封装并发、CAS 和跨表回滚测试需要的精确故障注入点。
 */
final class WorkflowMultiInstanceFailureHook
{
    /** create 审计下一次是否失败。 */
    private final AtomicBoolean failCreateAudit = new AtomicBoolean();

    /** 可委托正式 SQL 的轮次 Mapper mock。 */
    private WfMultiInstanceRoundMapper roundMapper;

    /** 不经过故障代理的正式 Mapper 委托。 */
    private WfMultiInstanceRoundMapper roundMapperDelegate;

    /** 只用于把两条并发业务准备链同步到同一起点。 */
    private WorkflowTaskCopyService taskCopyService;

    /**
     * 绑定配置阶段创建的故障注入对象。
     *
     * @param roundMapper WfMultiInstanceRoundMapper，委托正式 XML 的 mock
     * @param delegate WfMultiInstanceRoundMapper，正式 XML Mapper 委托
     * @return void，无返回值
     */
    void bind(WfMultiInstanceRoundMapper roundMapper,
            WfMultiInstanceRoundMapper delegate)
    {
        this.roundMapper = roundMapper;
        this.roundMapperDelegate = delegate;
    }

    /**
     * 绑定并发准备链需要的抄送边界 mock。
     *
     * @param service WorkflowTaskCopyService，生产服务实际持有的抄送边界
     * @return void，无返回值
     */
    void bindTaskCopyService(WorkflowTaskCopyService service)
    {
        this.taskCopyService = service;
    }

    /**
     * 让下一组两条退回链在抄送准备完成后同时继续。
     *
     * @return void，屏障只拦截前两次准备调用
     */
    void synchronizeNextTwoCopyPreparations()
    {
        if (taskCopyService == null)
        {
            throw new IllegalStateException("并发抄送准备钩子尚未绑定");
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation ->
        {
            if (calls.incrementAndGet() <= 2)
            {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return WorkflowTaskCopyService.CopyPlan.empty();
        }).when(taskCopyService).prepare(any(WorkflowTaskCopyAction.class),
                any(Task.class), any(WorkflowCurrentIdentity.class), anyList());
    }

    /**
     * 让下一组两条重提链读取同一 RETURNED 快照后同时继续。
     *
     * @return void，后续读取仍委托正式 Mapper XML
     */
    void synchronizeNextTwoReturnedReads()
    {
        requireBound();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation ->
        {
            if (calls.incrementAndGet() <= 2)
            {
                barrier.await(10, TimeUnit.SECONDS);
            }
            return roundMapperDelegate.selectReturnedByApplicantTaskId(
                    invocation.getArgument(0));
        }).when(roundMapper).selectReturnedByApplicantTaskId(anyString());
    }

    /** @return void，下一次任务 create 审计抛出固定异常。 */
    void failNextCreateAudit()
    {
        failCreateAudit.set(true);
    }

    /** @return boolean，仅消费一次 create 审计故障。 */
    boolean consumeCreateAuditFailure()
    {
        return failCreateAudit.compareAndSet(true, false);
    }

    /**
     * 令下一次 ACTIVE→RETURNED CAS 返回零行。
     *
     * @return void，无返回值
     */
    void loseReturnedCas()
    {
        requireBound();
        doReturn(0).when(roundMapper).compareAndSetReturnedStatus(
                anyLong(), anyInt(), anyString(), anyString(), anyString());
    }

    /**
     * 校验配置已绑定真实故障注入对象。
     *
     * @return void，配置缺失时立即失败
     */
    private void requireBound()
    {
        if (roundMapper == null || roundMapperDelegate == null)
        {
            throw new IllegalStateException("多实例故障注入器尚未绑定");
        }
    }
}
