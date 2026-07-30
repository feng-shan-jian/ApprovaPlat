package com.ruoyi.flowable.config;

import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * 在 Flowable 引擎构建前锁定数据库结构与 JSON 策略，避免未审计 DDL 或 Jackson 2 回流。
 */
@Configuration(proxyBeanMethods = false)
public class WorkflowSchemaManagementConfiguration
{
    /**
     * 创建引擎构建前的 schema 与 JSON 策略门禁。
     *
     * @param environment Environment，读取最终生效的 Flowable JSON mapper 配置
     * @return ProcessEngineConfigurationConfigurer，schema 或 JSON 策略不合规时阻止引擎创建
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public ProcessEngineConfigurationConfigurer workflowSchemaManagementConfigurer(
            Environment environment)
    {
        requireJackson3VariableMapper(environment.getProperty(
                "flowable.variable-json-mapper", "jackson"));
        return engineConfiguration -> requireAuditedSchemaManagement(
                engineConfiguration.getDatabaseSchemaUpdate());
    }

    /**
     * 校验 Flowable 变量 JSON 只能由原生 Jackson 3 mapper 处理。
     *
     * @param variableJsonMapper String，Flowable 最终生效的变量 JSON mapper 名称
     * @return void，配置为空或不是 jackson 时抛出启动异常
     */
    private void requireJackson3VariableMapper(String variableJsonMapper)
    {
        if (variableJsonMapper == null
                || !"jackson".equalsIgnoreCase(variableJsonMapper.trim()))
        {
            throw new IllegalStateException(
                    "Flowable 变量必须使用 Jackson 3，variable-json-mapper 只能为 jackson");
        }
    }

    /**
     * 校验 Flowable 只能读取由正式发布 SQL 预先创建的 schema。
     *
     * @param databaseSchemaUpdate String，Flowable 引擎最终生效的 schema 更新策略
     * @return void，配置为空或允许引擎执行 DDL 时抛出启动异常
     */
    private void requireAuditedSchemaManagement(String databaseSchemaUpdate)
    {
        // fail-closed：任何拼写错误、默认值变化或环境覆盖都不能退化为启动期自动建表。
        if (databaseSchemaUpdate == null
                || !"false".equalsIgnoreCase(databaseSchemaUpdate.trim()))
        {
            throw new IllegalStateException(
                    "Flowable 数据库结构必须由审计 SQL 管理，database-schema-update 只能为 false");
        }
    }
}
