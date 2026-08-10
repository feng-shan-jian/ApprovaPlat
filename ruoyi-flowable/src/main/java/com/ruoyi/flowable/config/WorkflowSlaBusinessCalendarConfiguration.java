package com.ruoyi.flowable.config;

import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;

/**
 * 在 Flowable 初始化前注册审批 SLA 业务日历并保留全部内置日历。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowSlaBusinessCalendarConfiguration
{
    /**
     * 注册内置 duration、dueDate、cycle 和 approvaSla 四类日历。
     * @param calendarService WorkflowBusinessCalendarService，正式日历计算服务
     * @return ProcessEngineConfigurationConfigurer，引擎初始化回调
     */
    @Bean
    public ProcessEngineConfigurationConfigurer workflowSlaBusinessCalendarConfigurer(
            @Lazy WorkflowBusinessCalendarService calendarService)
    {
        return engineConfiguration ->
        {
            // configure 回调早于 initClock，管理器必须在定时器首次运行时再读取非空引擎时钟。
            engineConfiguration.setBusinessCalendarManager(
                    new WorkflowSlaBusinessCalendarManager(
                            engineConfiguration::getClock, calendarService));
        };
    }
}
