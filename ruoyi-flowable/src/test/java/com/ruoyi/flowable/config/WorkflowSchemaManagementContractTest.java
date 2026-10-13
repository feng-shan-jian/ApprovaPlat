package com.ruoyi.flowable.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** These engine configuration rules remain active even when the production gate is disabled. */
class WorkflowSchemaManagementContractTest
{
    private final WorkflowSchemaManagementConfiguration configuration = new WorkflowSchemaManagementConfiguration();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "true", "create", "create-drop", "drop-create", "typo" })
    void schemaUpdatesAreAlwaysRejected(String policy)
    {
        var env = new MockEnvironment().withProperty("flowable.runtime.production-gate-enabled", "false");
        var configurer = configuration.workflowSchemaManagementConfigurer(env);
        var engine = new SpringProcessEngineConfiguration();
        engine.setDatabaseSchemaUpdate(policy);
        assertThatThrownBy(() -> configurer.configure(engine))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("database-schema-update 只能为 false");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "jackson2", "gson", "typo" })
    void nonJackson3StrategiesAreAlwaysRejected(String strategy)
    {
        var env = new MockEnvironment().withProperty("flowable.runtime.production-gate-enabled", "false")
                .withProperty("flowable.variable-json-mapper", strategy);
        assertThatThrownBy(() -> configuration.workflowSchemaManagementConfigurer(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只能为 jackson");
    }

    @ParameterizedTest
    @ValueSource(strings = { "false", " FALSE " })
    void auditedSchemaAndDefaultOrExplicitJacksonAreAcceptedWithoutStartingAnEngine(String policy)
    {
        for (MockEnvironment env : new MockEnvironment[] { new MockEnvironment(),
                new MockEnvironment().withProperty("flowable.variable-json-mapper", " JACKSON ") })
        {
            var engine = new SpringProcessEngineConfiguration();
            engine.setDatabaseSchemaUpdate(policy);
            assertThatCode(() -> configuration.workflowSchemaManagementConfigurer(env).configure(engine))
                    .doesNotThrowAnyException();
        }
    }
}
