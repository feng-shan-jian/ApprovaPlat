package com.ruoyi.framework.manager;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.ruoyi.common.utils.spring.SpringUtils;

/**
 * 异步审计执行器的 Spring 上下文重建回归测试。
 */
class AsyncManagerTest
{
    /**
     * 验证同一 JVM 重建 Spring 上下文后，异步管理器会使用新上下文线程池继续执行任务。
     *
     * @return void，无返回值；旧线程池关闭后任务必须由新线程池真实执行
     * @throws InterruptedException 等待异步任务时线程被中断
     */
    @Test
    void usesExecutorFromCurrentSpringContextAfterContextRestart() throws InterruptedException
    {
        // 两个执行器分别模拟重建前后的 Spring 应用上下文，锁存器用于证明任务确实执行。
        ScheduledExecutorService firstExecutor = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService secondExecutor = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch firstExecution = new CountDownLatch(1);
        CountDownLatch secondExecution = new CountDownLatch(1);

        try (MockedStatic<SpringUtils> springUtils = mockStatic(SpringUtils.class))
        {
            springUtils.when(() -> SpringUtils.getBean("scheduledExecutorService"))
                    .thenReturn(firstExecutor, firstExecutor, secondExecutor, secondExecutor);

            AsyncManager manager = AsyncManager.me();
            manager.execute(countDownTask(firstExecution));
            assertThat(firstExecution.await(2, SECONDS)).isTrue();
            manager.shutdown();

            // 旧执行器已经关闭；后续任务只能通过第二个上下文的执行器完成。
            assertThat(firstExecutor.isShutdown()).isTrue();
            manager.execute(countDownTask(secondExecution));
            assertThat(secondExecution.await(2, SECONDS)).isTrue();
            manager.shutdown();
            assertThat(secondExecutor.isShutdown()).isTrue();
        }
        finally
        {
            firstExecutor.shutdownNow();
            secondExecutor.shutdownNow();
        }
    }

    /**
     * 创建一次性锁存器递减任务。
     *
     * @param latch CountDownLatch，任务执行后需要释放的完成信号
     * @return TimerTask，供异步管理器调度的可观测任务
     */
    private TimerTask countDownTask(CountDownLatch latch)
    {
        return new TimerTask()
        {
            /**
             * 释放当前异步任务对应的完成信号。
             *
             * @return void，无返回值
             */
            @Override
            public void run()
            {
                latch.countDown();
            }
        };
    }
}
