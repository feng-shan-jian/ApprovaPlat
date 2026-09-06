package com.ruoyi.flowable.config;

import java.util.ArrayList;
import java.util.List;
import org.flowable.common.engine.api.delegate.event.FlowableEventListener;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.ruoyi.flowable.service.process.WorkflowCallActivityStartListener;
import com.ruoyi.flowable.service.process.WorkflowProcessDefinitionQueryService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;

/**
 * 注册 CallActivity 子流程开始事实初始化监听器。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowCallActivityStartConfiguration
{
    /**
     * 创建 CallActivity 子流程开始监听器。
     *
     * @param definitionQueryServiceProvider ObjectProvider&lt;WorkflowProcessDefinitionQueryService&gt;，延迟读取子流程开始表单
     * @param variableValidatorProvider ObjectProvider&lt;WorkflowStartVariableValidator&gt;，延迟校验输入映射值
     * @return WorkflowCallActivityStartListener，只处理 PROCESS_STARTED 的无状态监听器
     */
    @Bean
    public WorkflowCallActivityStartListener workflowCallActivityStartListener(
            ObjectProvider<WorkflowProcessDefinitionQueryService> definitionQueryServiceProvider,
            ObjectProvider<WorkflowStartVariableValidator> variableValidatorProvider)
    {
        return new WorkflowCallActivityStartListener(
                definitionQueryServiceProvider, variableValidatorProvider);
    }

    /**
     * 把 CallActivity 开始监听器追加到 Flowable 现有全局监听器集合。
     *
     * @param callActivityStartListener WorkflowCallActivityStartListener，子流程开始事实监听器
     * @return ProcessEngineConfigurationConfigurer，在流程引擎创建前追加监听器的配置回调
     */
    @Bean
    public ProcessEngineConfigurationConfigurer workflowCallActivityStartConfigurer(
            WorkflowCallActivityStartListener callActivityStartListener)
    {
        return engineConfiguration ->
        {
            // 始终复制已有集合，保证与完成监听器及框架监听器按显式列表共同注册。
            List<FlowableEventListener> eventListeners = engineConfiguration.getEventListeners() == null
                    ? new ArrayList<>()
                    : new ArrayList<>(engineConfiguration.getEventListeners());
            eventListeners.add(callActivityStartListener);
            engineConfiguration.setEventListeners(eventListeners);
        };
    }
}
