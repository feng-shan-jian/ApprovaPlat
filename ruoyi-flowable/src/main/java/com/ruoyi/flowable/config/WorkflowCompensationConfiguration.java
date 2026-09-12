package com.ruoyi.flowable.config;

import com.ruoyi.flowable.engine.WorkflowCompensationLifecycle;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 在引擎初始化前安装取消补偿等待语义。 */
@Configuration(proxyBeanMethods = false)
public class WorkflowCompensationConfiguration
{
    @Bean
    public ProcessEngineConfigurationConfigurer workflowCompensationConfigurer()
    {
        return WorkflowCompensationLifecycle::configure;
    }
}
