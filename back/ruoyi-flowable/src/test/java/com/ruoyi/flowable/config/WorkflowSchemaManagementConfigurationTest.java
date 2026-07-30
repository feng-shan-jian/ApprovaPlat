package com.ruoyi.flowable.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.ProcessEngineConfigurationConfigurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * WorkflowSchemaManagementConfiguration 的引擎构建前 schema 与 JSON 策略测试。
 */
class WorkflowSchemaManagementConfigurationTest
{
    /**
     * 验证显式 false 配置允许 Flowable 使用预建 schema。
     *
     * @return void，合法策略被错误拒绝时测试失败
     */
    @Test
    void acceptsAuditedSqlSchemaManagement()
    {
        ProcessEngineConfigurationConfigurer configurer = configurer(" jackson ");
        SpringProcessEngineConfiguration engineConfiguration = engineConfiguration(" false ");

        assertThatCode(() -> configurer.configure(engineConfiguration))
                .doesNotThrowAnyException();
    }

    /**
     * 验证空值、自动更新及建删策略都在引擎创建前失败关闭。
     *
     * @param databaseSchemaUpdate String，待验证的 Flowable schema 更新策略
     * @return void，任一非 false 策略能够继续创建引擎时测试失败
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "true", "create", "create-drop", "drop-create" })
    void rejectsApplicationManagedSchema(String databaseSchemaUpdate)
    {
        ProcessEngineConfigurationConfigurer configurer = configurer("jackson");
        SpringProcessEngineConfiguration engineConfiguration =
                engineConfiguration(databaseSchemaUpdate);

        assertThatThrownBy(() -> configurer.configure(engineConfiguration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database-schema-update 只能为 false");
    }

    /**
     * 验证旧 mapper 名称和空字符串会在创建 Flowable 引擎前失败关闭。
     *
     * @param variableJsonMapper String，待验证的 Flowable 变量 JSON mapper 名称
     * @return void，非 Jackson 3 mapper 能继续创建引擎配置器时测试失败
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "jackson2", "gson" })
    void rejectsNonJackson3VariableMapper(String variableJsonMapper)
    {
        assertThatThrownBy(() -> configurer(variableJsonMapper))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("variable-json-mapper 只能为 jackson");
    }

    /**
     * 创建被测 schema 配置器。
     *
     * @param variableJsonMapper String，测试环境声明的 Flowable 变量 JSON mapper 名称
     * @return ProcessEngineConfigurationConfigurer，新的引擎构建前门禁
     */
    private ProcessEngineConfigurationConfigurer configurer(String variableJsonMapper)
    {
        MockEnvironment environment = new MockEnvironment();
        if (variableJsonMapper != null)
        {
            environment.setProperty("flowable.variable-json-mapper", variableJsonMapper);
        }
        return new WorkflowSchemaManagementConfiguration()
                .workflowSchemaManagementConfigurer(environment);
    }

    /**
     * 创建返回指定 schema 策略的 Flowable 引擎配置替身。
     *
     * @param databaseSchemaUpdate String，模拟的最终生效策略
     * @return SpringProcessEngineConfiguration，只暴露当前测试所需的 schema 配置
     */
    private SpringProcessEngineConfiguration engineConfiguration(String databaseSchemaUpdate)
    {
        SpringProcessEngineConfiguration engineConfiguration =
                mock(SpringProcessEngineConfiguration.class);
        when(engineConfiguration.getDatabaseSchemaUpdate()).thenReturn(databaseSchemaUpdate);
        return engineConfiguration;
    }
}
