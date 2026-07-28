package com.ruoyi.framework.manager;

import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.ruoyi.common.utils.Threads;
import com.ruoyi.common.utils.spring.SpringUtils;

/**
 * 异步任务管理器
 * 
 * @author ruoyi
 */
public class AsyncManager
{
    /**
     * 操作延迟10毫秒
     */
    private static final int OPERATE_DELAY_TIME = 10;

    /**
     * 单例模式
     */
    private AsyncManager(){}

    private static AsyncManager me = new AsyncManager();

    public static AsyncManager me()
    {
        return me;
    }

    /**
     * 使用当前 Spring 上下文的调度线程池执行异步任务。
     *
     * @param task TimerTask，待异步执行的登录或操作审计任务
     * @return void，无返回值；当前上下文无法提供线程池时直接抛出异常，禁止静默丢失审计
     */
    public void execute(TimerTask task)
    {
        currentExecutor().schedule(task, OPERATE_DELAY_TIME, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止当前 Spring 上下文持有的异步任务线程池。
     *
     * @return void，无返回值；等待已提交任务结束后关闭当前上下文线程池
     */
    public void shutdown()
    {
        Threads.shutdownAndAwaitTermination(currentExecutor());
    }

    /**
     * 获取当前 Spring 上下文管理的调度线程池。
     *
     * @return ScheduledExecutorService，当前应用上下文的异步审计执行器
     */
    private ScheduledExecutorService currentExecutor()
    {
        // Spring 测试、热重载或同 JVM 上下文重建后必须解析新 Bean，禁止缓存已关闭的旧执行器。
        return SpringUtils.getBean("scheduledExecutorService");
    }
}
