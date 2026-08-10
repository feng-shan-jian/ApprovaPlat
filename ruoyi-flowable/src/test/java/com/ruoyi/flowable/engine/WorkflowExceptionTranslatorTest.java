package com.ruoyi.flowable.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.stream.Stream;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableForbiddenException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableIllegalStateException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.extension.WorkflowConditionRoutingException;

class WorkflowExceptionTranslatorTest
{
    private final WorkflowExceptionTranslator translator = new WorkflowExceptionTranslator();

    /**
     * 验证 Flowable 各类公共异常映射到稳定 code/message，且内部信息仅保留在 cause。
     *
     * @param source FlowableException，待翻译的引擎异常
     * @param expectedCode int，期望若依业务错误码
     * @param expectedMessage String，期望对外稳定提示
     * @return 无返回值；断言失败时测试失败
     */
    @ParameterizedTest
    @MethodSource("exceptionMappings")
    void mapsPublicFlowableExceptionsWithoutLeakingDetails(FlowableException source,
            int expectedCode, String expectedMessage)
    {
        ServiceException translated = translator.translate(source);

        assertThat(translated.getCode()).isEqualTo(expectedCode);
        assertThat(translated.getMessage()).isEqualTo(expectedMessage).doesNotContain("internal-secret");
        assertThat(translated.getCause()).isSameAs(source);
    }

    /**
     * 验证模型版本数据库唯一约束竞争被翻译为稳定 409，且不泄露数据库约束细节。
     *
     * @return 无返回值；并发冲突被误报为 500 或泄露 SQL 信息时测试失败
     */
    @org.junit.jupiter.api.Test
    void mapsModelVersionUniqueConstraintViolationToConflict()
    {
        SQLException databaseCause = new SQLException(
                "Duplicate entry 'expense-2' for key 'ACT_RE_MODEL.ACT_UNIQ_MODEL'",
                "23000", 1062);
        FlowableException source = new FlowableException("internal model persistence failure", databaseCause);

        ServiceException translated = translator.translate(source);

        assertThat(translated.getCode()).isEqualTo(409);
        assertThat(translated.getMessage()).isEqualTo(WorkflowExceptionTranslator.CONFLICT_MESSAGE)
                .doesNotContain("ACT_UNIQ_MODEL", "expense");
        assertThat(translated.getCause()).isSameAs(source);
    }

    /**
     * 验证 Flowable 表达式包装的专用条件路由冲突保留受控 409 和稳定提示。
     * @return void，路由冲突被误报为 500 或丢失引擎诊断链时测试失败
     */
    @org.junit.jupiter.api.Test
    void preservesTrustedConditionRoutingFailureAcrossFlowableWrapper()
    {
        WorkflowConditionRoutingException routingFailure =
                new WorkflowConditionRoutingException("排他网关有多个条件同时命中，请联系流程设计者修正规则", 409);
        FlowableException source = new FlowableException("internal expression details", routingFailure);

        ServiceException translated = translator.translate(source);

        assertThat(translated.getCode()).isEqualTo(409);
        assertThat(translated.getMessage()).isEqualTo(routingFailure.getMessage())
                .doesNotContain("internal expression");
        assertThat(translated.getCause()).isSameAs(source);
    }

    /**
     * 验证事务提交阶段直接暴露的模型版本唯一键异常同样稳定翻译为 409。
     *
     * @return 无返回值；MyBatis 或 Spring 包装路径回退为 500 时测试失败
     */
    @org.junit.jupiter.api.Test
    void mapsRuntimeWrappedModelVersionConstraintViolationToConflict()
    {
        SQLException databaseCause = new SQLException(
                "Duplicate entry 'expense-2' for key 'ACT_RE_MODEL.ACT_UNIQ_MODEL'",
                "23000", 1062);
        RuntimeException source = new RuntimeException("transaction commit failed", databaseCause);

        assertThat(translator.translateRetryableConcurrencyConflict(source))
                .hasValueSatisfying(translated ->
                {
                    assertThat(translated.getCode()).isEqualTo(409);
                    assertThat(translated.getMessage())
                            .isEqualTo(WorkflowExceptionTranslator.CONFLICT_MESSAGE)
                            .doesNotContain("ACT_UNIQ_MODEL", "expense");
                    assertThat(translated.getCause()).isSameAs(source);
                });
    }

    /**
     * 验证 MyBatis 外层运行时异常包装的 MySQL 死锁被翻译为稳定 409。
     *
     * @return 无返回值；死锁未识别、SQL 信息泄露或非并发异常被误翻译时测试失败
     */
    @org.junit.jupiter.api.Test
    void mapsWrappedDatabaseDeadlockButPreservesUnknownRuntimeFailure()
    {
        SQLTransactionRollbackException databaseCause = new SQLTransactionRollbackException(
                "Deadlock found for internal-secret-table", "40001", 1213);
        RuntimeException deadlock = new RuntimeException("persistence wrapper", databaseCause);

        assertThat(translator.translateDatabaseConcurrencyConflict(deadlock))
                .hasValueSatisfying(translated ->
                {
                    assertThat(translated.getCode()).isEqualTo(409);
                    assertThat(translated.getMessage())
                            .isEqualTo(WorkflowExceptionTranslator.CONFLICT_MESSAGE)
                            .doesNotContain("internal-secret", "Deadlock");
                    assertThat(translated.getCause()).isSameAs(deadlock);
                });
        assertThat(translator.translateDatabaseConcurrencyConflict(
                new IllegalStateException("programming failure"))).isEmpty();
    }

    /**
     * 提供参数、对象不存在、并发、状态、权限和通用引擎异常映射样本。
     *
     * @return Stream&lt;Arguments&gt;，异常及预期稳定错误契约
     */
    private static Stream<Arguments> exceptionMappings()
    {
        return Stream.of(
                Arguments.of(new FlowableIllegalArgumentException("internal-secret-argument"), 400,
                        WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE),
                Arguments.of(new FlowableObjectNotFoundException("internal-secret-object", Object.class), 404,
                        WorkflowExceptionTranslator.OBJECT_NOT_FOUND_MESSAGE),
                Arguments.of(new FlowableOptimisticLockingException("internal-secret-lock"), 409,
                        WorkflowExceptionTranslator.CONFLICT_MESSAGE),
                Arguments.of(new FlowableTaskAlreadyClaimedException("task-1", "internal-secret-user"), 409,
                        WorkflowExceptionTranslator.CONFLICT_MESSAGE),
                Arguments.of(new FlowableIllegalStateException("internal-secret-state"), 409,
                        WorkflowExceptionTranslator.CONFLICT_MESSAGE),
                Arguments.of(new FlowableForbiddenException("internal-secret-forbidden"), 403,
                        WorkflowExceptionTranslator.FORBIDDEN_MESSAGE),
                Arguments.of(new FlowableException("internal-secret-engine"), 500,
                        WorkflowExceptionTranslator.ENGINE_FAILURE_MESSAGE));
    }
}
