package com.ruoyi.flowable.config;

import java.util.ArrayList;
import java.util.List;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.flowable.listener.WorkflowMultiInstanceRoundInterruptionListener;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceRoundTerminationService;

/**
 * 注册受控多实例根异常退出监听器，补齐非任务 complete 路径的轮次关闭。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowMultiInstanceRoundInterruptionConfiguration
{
    /**
     * 创建延迟解析轮次服务的多实例根取消监听器。
     *
     * @param terminationServiceProvider ObjectProvider&lt;WorkflowMultiInstanceRoundTerminationService&gt;，
     *        避免引擎配置与 RuntimeService 之间形成初始化循环
     * @return WorkflowMultiInstanceRoundInterruptionListener，只处理根取消事件的同步监听器
     */
    @Bean
    public WorkflowMultiInstanceRoundInterruptionListener
            workflowMultiInstanceRoundInterruptionListener(
                    ObjectProvider<WorkflowMultiInstanceRoundTerminationService>
                            terminationServiceProvider)
    {
        return new WorkflowMultiInstanceRoundInterruptionListener(
                terminationServiceProvider);
    }

    /**
     * 在保留框架及其他业务监听器的前提下追加多实例根异常退出监听器。
     *
     * @param interruptionListener WorkflowMultiInstanceRoundInterruptionListener，
     *        已配置为失败即回滚的同步监听器
     * @return ProcessEngineConfigurationConfigurer，引擎创建前追加全局监听器的配置回调
     */
    @Bean
    public ProcessEngineConfigurationConfigurer
            workflowMultiInstanceRoundInterruptionConfigurer(
                    WorkflowMultiInstanceRoundInterruptionListener interruptionListener)
    {
        return engineConfiguration ->
        {
            // 复制既有集合再追加，禁止覆盖自然完成状态监听器或 Flowable 自动配置监听器。
            List<FlowableEventListener> eventListeners =
                    engineConfiguration.getEventListeners() == null
                            ? new ArrayList<>()
                            : new ArrayList<>(engineConfiguration.getEventListeners());
            eventListeners.add(interruptionListener);
            engineConfiguration.setEventListeners(eventListeners);
        };
    }
}
