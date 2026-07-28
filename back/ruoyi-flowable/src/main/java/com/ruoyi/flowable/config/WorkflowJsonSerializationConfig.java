package com.ruoyi.flowable.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.JsonNode;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;

/**
 * 工作流模块的 Jackson 3 HTTP 序列化配置。
 *
 * <p>Flowable 为兼容存量 JSON 变量继续使用 Jackson 2 树模型，本配置只负责在
 * Spring Boot 4 的 Jackson 3 响应边界将该树模型写成原生 JSON。</p>
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowJsonSerializationConfig
{
    /**
     * 注册 Jackson 2 {@link JsonNode} 到 Jackson 3 的受控序列化桥接。
     *
     * @return JacksonModule，供 Spring Boot 自动装配到业务 HTTP ObjectMapper
     */
    @Bean
    public JacksonModule workflowJackson2JsonNodeModule()
    {
        SimpleModule module = new SimpleModule("workflow-jackson2-json-node");
        module.addSerializer(JsonNode.class, new WorkflowJackson2JsonNodeSerializer());
        return module;
    }
}
