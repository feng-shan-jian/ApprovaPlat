package com.ruoyi.flowable.config;

import com.ruoyi.flowable.service.model.WorkflowDecisionTableExecutor;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.dmn.spring.SpringDmnEngineConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class WorkflowDecisionTableConfiguration
{
    @Bean
    public EngineConfigurationConfigurer<SpringDmnEngineConfiguration> workflowDecisionTableConfigurer()
    {
        return configuration -> configuration.setRuleEngineExecutor(new WorkflowDecisionTableExecutor(configuration));
    }
}
