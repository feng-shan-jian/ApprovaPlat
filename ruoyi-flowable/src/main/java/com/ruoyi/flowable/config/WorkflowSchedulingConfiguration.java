package com.ruoyi.flowable.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 集中启用全部工作流固定延迟任务；线程池和关闭行为统一复用 Spring Boot 标准调度配置。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "flowable.scheduling",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class WorkflowSchedulingConfiguration
{
}
