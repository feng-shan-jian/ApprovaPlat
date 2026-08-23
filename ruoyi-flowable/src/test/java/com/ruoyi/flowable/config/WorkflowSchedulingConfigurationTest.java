package com.ruoyi.flowable.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证工作流全局调度开关与 Spring Boot 标准调度线程池的装配边界。
 */
class WorkflowSchedulingConfigurationTest
{
    /** 调度容量与线程命名模拟正式 application.yml，不加载数据库或任何真实调度组件。 */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(WorkflowSchedulingConfiguration.class)
            .withPropertyValues(
                    "spring.task.scheduling.pool.size=5",
                    "spring.task.scheduling.thread-name-prefix=workflow-scheduler-",
                    "spring.task.scheduling.shutdown.await-termination=true",
                    "spring.task.scheduling.shutdown.await-termination-period=30s");

    /**
     * 验证缺少全局开关时保持调度启用，并由 Boot 按正式容量创建标准线程池。
     *
     * @return void，无返回值，断言失败时由 JUnit 报告装配差异
     */
    @Test
    void enablesSchedulingByDefaultWithBootThreadPool()
    {
        contextRunner.run(context ->
        {
            assertThat(context).hasBean(
                    "org.springframework.scheduling.config.internalScheduledAnnotationProcessor");
            assertThat(context).hasSingleBean(TaskScheduler.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);

            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(5);
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("workflow-scheduler-");
        });
    }

    /**
     * 验证显式关闭全局开关后既不注册注解处理器，也不触发 Boot 调度器自动配置。
     *
     * @return void，无返回值，断言失败时由 JUnit 报告意外注册的调度基础设施
     */
    @Test
    void disablesAllSchedulingInfrastructureWhenConfiguredFalse()
    {
        contextRunner
                .withPropertyValues("flowable.scheduling.enabled=false")
                .run(context ->
                {
                    assertThat(context).doesNotHaveBean(
                            "org.springframework.scheduling.config.internalScheduledAnnotationProcessor");
                    assertThat(context).doesNotHaveBean(TaskScheduler.class);
                    assertThat(context).doesNotHaveBean(ThreadPoolTaskScheduler.class);
                });
    }
}
