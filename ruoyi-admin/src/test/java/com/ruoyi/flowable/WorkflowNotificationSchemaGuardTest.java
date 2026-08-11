package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 验证通知 MySQL IT 全量清理的隔离库 fail-closed 门禁。
 */
class WorkflowNotificationSchemaGuardTest
{
    /**
     * 验证当前真实验收库名与必填期望库完全一致时允许继续清理。
     *
     * @return void，验收库命名未被门禁接受时测试失败
     */
    @Test
    void acceptsExplicitAcceptanceSchema()
    {
        assertThatCode(() -> WorkflowNotificationMySqlIT.requireIsolatedNotificationSchema(
                "approvaplat_accept_20260809_1348", "approvaplat_accept_20260809_1348"))
                .doesNotThrowAnyException();
    }

    /**
     * 验证仓库登记的本机 Flowable 隔离库名可执行通知事实清理。
     *
     * @return void，本机隔离库命名未被门禁接受时测试失败
     */
    @Test
    void acceptsRegisteredLocalIntegrationSchemas()
    {
        assertThatCode(() -> WorkflowNotificationMySqlIT.requireIsolatedNotificationSchema(
                "ry_vue_flowable_it", "ry_vue_flowable_it"))
                .doesNotThrowAnyException();
        assertThatCode(() -> WorkflowNotificationMySqlIT.requireIsolatedNotificationSchema(
                "ry_vue_codex_flowable_it", "ry_vue_codex_flowable_it"))
                .doesNotThrowAnyException();
    }

    /**
     * 验证常规生产名即使与当前库相同，也不能执行通知表全量清理。
     *
     * @return void，常规生产库名被错误放行时测试失败
     */
    @Test
    void rejectsOrdinaryProductionSchema()
    {
        assertThatThrownBy(() -> WorkflowNotificationMySqlIT.requireIsolatedNotificationSchema(
                "approvaplat", "approvaplat"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("隔离测试或验收库");
    }

    /**
     * 验证当前连接库与期望隔离库不完全一致时拒绝执行清理。
     *
     * @return void，错库连接被错误放行时测试失败
     */
    @Test
    void rejectsSchemaMismatch()
    {
        assertThatThrownBy(() -> WorkflowNotificationMySqlIT.requireIsolatedNotificationSchema(
                "approvaplat_accept_20260809_1348", "approvaplat_accept_20260809_9999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前库与期望隔离库不一致");
    }

    /**
     * 验证未配置必填期望库时门禁默认拒绝，不允许退化为任意当前库。
     *
     * @return void，空配置被错误放行时测试失败
     */
    @Test
    void rejectsMissingExpectedSchema()
    {
        assertThatThrownBy(() -> WorkflowNotificationMySqlIT.requireIsolatedNotificationSchema(
                " ", "approvaplat_accept_20260809_1348"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须显式配置");
    }
}
