package com.ruoyi.flowable.config;

import java.util.ArrayList;
import java.util.List;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService;
import com.ruoyi.flowable.service.process.WorkflowProcessCompletionStatusListener;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;

/**
 * 注册工作流自然完成状态监听器，保证引擎历史与对外业务状态使用同一最终值。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowProcessCompletionStatusConfiguration
{
    /**
     * 创建流程自然完成状态监听器。
     *
     * @param automaticCopyServiceProvider ObjectProvider&lt;WorkflowAutomaticCopyService&gt;，
     *        延迟到引擎事件发生后解析自动抄送服务，避免流程引擎初始化循环依赖
     * @param notificationServiceProvider ObjectProvider&lt;WorkflowNotificationService&gt;，
     *        延迟到完成事件解析通知服务，避免反向依赖 Flowable 公共服务
     * @param notificationOutboxServiceProvider ObjectProvider&lt;WorkflowNotificationOutboxService&gt;，
     *        延迟到子流程完成事件解析催办状态服务
     * @return WorkflowProcessCompletionStatusListener，受 Spring 管理的无状态引擎监听器
     */
    @Bean
    public WorkflowProcessCompletionStatusListener workflowProcessCompletionStatusListener(
            ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider,
            ObjectProvider<WorkflowNotificationService> notificationServiceProvider,
            ObjectProvider<WorkflowNotificationOutboxService> notificationOutboxServiceProvider)
    {
        return new WorkflowProcessCompletionStatusListener(
                automaticCopyServiceProvider, notificationServiceProvider,
                notificationOutboxServiceProvider);
    }

    /**
     * 把状态监听器追加到 Flowable 已有全局监听器集合，保留框架和业务的其他监听器。
     *
     * @param completionStatusListener WorkflowProcessCompletionStatusListener，自然完成状态监听器
     * @return ProcessEngineConfigurationConfigurer，在流程引擎创建前执行的配置回调
     */
    @Bean
    public ProcessEngineConfigurationConfigurer workflowProcessCompletionStatusConfigurer(
            WorkflowProcessCompletionStatusListener completionStatusListener)
    {
        return engineConfiguration ->
        {
            // 复制已有集合后追加，避免修改自动配置传入的不可变列表或覆盖其他正式监听器。
            List<FlowableEventListener> eventListeners = engineConfiguration.getEventListeners() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(engineConfiguration.getEventListeners());
            eventListeners.add(completionStatusListener);
            engineConfiguration.setEventListeners(eventListeners);
        };
    }
}
