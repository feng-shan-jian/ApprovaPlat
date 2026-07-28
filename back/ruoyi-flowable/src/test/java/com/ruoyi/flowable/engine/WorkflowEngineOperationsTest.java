package com.ruoyi.flowable.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLTransactionRollbackException;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.IdentityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

class WorkflowEngineOperationsTest
{
    /**
     * 清理测试线程事务特征，避免模拟的隔离级别泄漏到其他用例。
     *
     * @return 无返回值，当前线程恢复为无事务状态
     */
    @AfterEach
    void clearTransactionCharacteristics()
    {
        TransactionSynchronizationManager.clear();
    }

    /**
     * 验证写操作会设置、清理规范操作人并返回真实业务结果。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void executesWriteInsideAuthenticationBoundary()
    {
        IdentityService identityService = mock(IdentityService.class);
        WorkflowEngineOperations operations = operations(identityService, mock(WorkflowIdentityResolver.class));
        bindRepeatableReadWriteTransaction();

        String result = operations.writeAsUser("0007", () -> "done");

        assertThat(result).isEqualTo("done");
        InOrder calls = inOrder(identityService);
        calls.verify(identityService).setAuthenticatedUserId("7");
        calls.verify(identityService).setAuthenticatedUserId(null);
    }

    /**
     * 验证 Spring 事务先于正式身份核验开启，并在身份回调与认证上下文结束后才提交同一事务。
     *
     * @return 无返回值；调用顺序或事务边界不正确时测试失败
     */
    @Test
    void resolvesIdentityAndExecutesCallbackInsideOneSpringTransaction()
    {
        IdentityService identityService = mock(IdentityService.class);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        WorkflowCurrentIdentity identity = new WorkflowCurrentIdentity("7", Set.of("ROLE2"));
        when(identityResolver.resolveCurrentIdentity()).thenReturn(identity);

        @SuppressWarnings("unchecked")
        Function<WorkflowCurrentIdentity, String> action = mock(Function.class);
        when(action.apply(identity)).thenReturn("done");

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(invocation ->
        {
            bindRepeatableReadWriteTransaction();
            return transactionStatus;
        });
        doAnswer(invocation ->
        {
            TransactionSynchronizationManager.clear();
            return null;
        }).when(transactionManager).commit(transactionStatus);
        WorkflowEngineOperations operations = transactionalProxy(
                operations(identityService, identityResolver), transactionManager);

        assertThat(operations.writeAsCurrentUser(action)).isEqualTo("done");

        InOrder calls = inOrder(transactionManager, identityResolver, identityService, action);
        calls.verify(transactionManager).getTransaction(any(TransactionDefinition.class));
        calls.verify(identityResolver).resolveCurrentIdentity();
        calls.verify(identityService).setAuthenticatedUserId("7");
        calls.verify(action).apply(identity);
        calls.verify(identityService).setAuthenticatedUserId(null);
        calls.verify(transactionManager).commit(transactionStatus);
    }

    /**
     * 验证写边界拒绝缺失、默认、低隔离和只读外层事务，且失败发生在身份解析和引擎认证之前。
     *
     * @return 无返回值；任一不兼容事务进入身份或业务回调时测试失败
     */
    @Test
    void rejectsIncompatibleWriteTransactionsBeforeIdentityResolution()
    {
        assertRejectedWriteTransaction(false, null);
        assertRejectedWriteTransaction(false, Connection.TRANSACTION_READ_COMMITTED);
        assertRejectedWriteTransaction(true, Connection.TRANSACTION_REPEATABLE_READ);

        IdentityService identityService = mock(IdentityService.class);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        WorkflowEngineOperations operations = operations(identityService, identityResolver);
        TransactionSynchronizationManager.clear();

        assertThatThrownBy(() -> operations.writeAsCurrentUser(() -> "forbidden"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo(
                            WorkflowEngineOperations.WRITE_TRANSACTION_CONTRACT_MESSAGE);
                });
        verifyNoInteractions(identityResolver, identityService);
    }

    /**
     * 验证查询或写入中的 Flowable 异常均经过统一翻译并保留原始 cause。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void translatesFlowableFailuresAtExecutionBoundary()
    {
        WorkflowEngineOperations operations = operations(
                mock(IdentityService.class), mock(WorkflowIdentityResolver.class));
        FlowableIllegalArgumentException source = new FlowableIllegalArgumentException("internal-secret");

        assertThatThrownBy(() -> operations.read(() ->
        {
            throw source;
        })).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(400);
            assertThat(exception.getMessage()).isEqualTo(WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE);
            assertThat(exception.getCause()).isSameAs(source);
        });
    }

    /**
     * 验证 P3 业务层主动抛出的 ServiceException 不会被误包装为引擎错误。
     *
     * @return 无返回值；断言失败时测试失败
     */
    @Test
    void preservesExistingBusinessException()
    {
        WorkflowEngineOperations operations = operations(
                mock(IdentityService.class), mock(WorkflowIdentityResolver.class));
        ServiceException source = new ServiceException("业务状态不允许", 409);

        assertThatThrownBy(() -> operations.read(() ->
        {
            throw source;
        })).isSameAs(source);
    }

    /**
     * 验证可降级的已翻译业务异常会在事务代理返回前转成结果，事务仍正常提交。
     *
     * @return 无返回值；异常穿过代理导致回滚或未返回降级结果时测试失败
     */
    @Test
    void handlesTranslatedServiceExceptionBeforeTransactionBoundaryReturns()
    {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        WorkflowEngineOperations operations = transactionalProxy(
                operations(mock(IdentityService.class), mock(WorkflowIdentityResolver.class)),
                transactionManager);
        FlowableIllegalArgumentException source =
                new FlowableIllegalArgumentException("internal-secret");

        String result = operations.readWithServiceExceptionHandler(() ->
        {
            throw source;
        }, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCause()).isSameAs(source);
            return "not-available";
        });

        assertThat(result).isEqualTo("not-available");
        verify(transactionManager).commit(transactionStatus);
        verify(transactionManager, never()).rollback(transactionStatus);
    }

    /**
     * 验证 MyBatis 直接外泄的 JDBC 死锁会在统一执行边界映射为稳定 409。
     *
     * @return 无返回值；数据库竞态外泄为 500 或响应包含 SQL 细节时测试失败
     */
    @Test
    void translatesWrappedDatabaseDeadlockAtExecutionBoundary()
    {
        WorkflowEngineOperations operations = operations(
                mock(IdentityService.class), mock(WorkflowIdentityResolver.class));
        RuntimeException source = new RuntimeException("mybatis persistence failure",
                new SQLTransactionRollbackException(
                        "Deadlock on internal-secret-table", "40001", 1213));

        assertThatThrownBy(() -> operations.read(() ->
        {
            throw source;
        })).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(409);
            assertThat(exception.getMessage())
                    .isEqualTo(WorkflowExceptionTranslator.CONFLICT_MESSAGE)
                    .doesNotContain("internal-secret", "Deadlock");
            assertThat(exception.getCause()).isSameAs(source);
        });
    }

    /**
     * 验证领域子码只附加到真实可重试竞态，并保留原始异常链供服务端追踪。
     *
     * @return 无返回值；普通业务冲突被误标、乐观锁未标记或 cause 丢失时测试失败
     */
    @Test
    void addsDomainSubCodeOnlyToRetryableConcurrencyFailures()
    {
        WorkflowEngineOperations operations = operations(
                mock(IdentityService.class), mock(WorkflowIdentityResolver.class));
        FlowableOptimisticLockingException optimisticCause =
                new FlowableOptimisticLockingException("internal optimistic lock");
        ServiceException translatedOptimistic = new WorkflowExceptionTranslator()
                .translate(optimisticCause);
        String subCode = "WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT";

        RuntimeException tagged = operations.withConcurrencyConflictSubCode(
                translatedOptimistic, subCode);

        assertThat(tagged).isSameAs(translatedOptimistic);
        assertThat(translatedOptimistic.getSubCode()).isEqualTo(subCode);
        assertThat(translatedOptimistic.getCause()).isSameAs(optimisticCause);

        RuntimeException rawDeadlock = new RuntimeException("commit wrapper",
                new SQLTransactionRollbackException("deadlock detail", "40001", 1213));
        RuntimeException translatedDeadlock = operations.withConcurrencyConflictSubCode(
                rawDeadlock, subCode);
        assertThat(translatedDeadlock).isInstanceOfSatisfying(ServiceException.class,
                exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode()).isEqualTo(subCode);
                    assertThat(exception.getCause()).isSameAs(rawDeadlock);
                });

        ServiceException ordinaryConflict = new ServiceException("业务状态不允许", 409);
        assertThat(operations.withConcurrencyConflictSubCode(ordinaryConflict, subCode))
                .isSameAs(ordinaryConflict);
        assertThat(ordinaryConflict.getSubCode()).isNull();

        IllegalStateException programmingFailure =
                new IllegalStateException("programming failure");
        assertThat(operations.withConcurrencyConflictSubCode(programmingFailure, subCode))
                .isSameAs(programmingFailure);
    }

    /**
     * 验证全部只读边界都显式固定为可重复读，避免授权和两阶段正文读取受数据库默认隔离级别影响。
     *
     * @return 无返回值；任一只读方法缺少只读标记或 REPEATABLE_READ 时测试失败
     * @throws NoSuchMethodException 只读方法签名与执行边界契约不一致时抛出
     */
    @Test
    void declaresRepeatableReadForAllReadBoundaries() throws NoSuchMethodException
    {
        Transactional supplierBoundary = WorkflowEngineOperations.class
                .getMethod("read", Supplier.class).getAnnotation(Transactional.class);
        Transactional handledBoundary = WorkflowEngineOperations.class
                .getMethod("readWithServiceExceptionHandler", Supplier.class, Function.class)
                .getAnnotation(Transactional.class);
        Transactional runnableBoundary = WorkflowEngineOperations.class
                .getMethod("read", Runnable.class).getAnnotation(Transactional.class);

        assertThat(supplierBoundary).isNotNull();
        assertThat(supplierBoundary.readOnly()).isTrue();
        assertThat(supplierBoundary.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(handledBoundary).isNotNull();
        assertThat(handledBoundary.readOnly()).isTrue();
        assertThat(handledBoundary.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(runnableBoundary).isNotNull();
        assertThat(runnableBoundary.readOnly()).isTrue();
        assertThat(runnableBoundary.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
    }

    /**
     * 验证全部写边界显式固定为可重复读，避免动态多实例 revision 竞争受连接默认隔离级别影响。
     *
     * @return 无返回值；任一写方法缺少回滚契约或 REPEATABLE_READ 时测试失败
     * @throws NoSuchMethodException 写方法签名与执行边界契约不一致时抛出
     */
    @Test
    void declaresRepeatableReadForAllWriteBoundaries() throws NoSuchMethodException
    {
        List<Transactional> boundaries = List.of(
                WorkflowEngineOperations.class
                        .getMethod("writeAsCurrentUser", Supplier.class)
                        .getAnnotation(Transactional.class),
                WorkflowEngineOperations.class
                        .getMethod("writeAsCurrentUser", Function.class)
                        .getAnnotation(Transactional.class),
                WorkflowEngineOperations.class
                        .getMethod("writeAsCurrentUser", Runnable.class)
                        .getAnnotation(Transactional.class),
                WorkflowEngineOperations.class
                        .getMethod("writeAsUser", String.class, Supplier.class)
                        .getAnnotation(Transactional.class),
                WorkflowEngineOperations.class
                        .getMethod("writeAsUser", String.class, Runnable.class)
                        .getAnnotation(Transactional.class));

        // 所有写入都共用同一隔离级别和异常回滚契约，确保 CAS 失败不会留下部分副作用。
        assertThat(boundaries).allSatisfy(boundary ->
        {
            assertThat(boundary).isNotNull();
            assertThat(boundary.readOnly()).isFalse();
            assertThat(boundary.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
            assertThat(boundary.rollbackFor()).contains(Exception.class);
        });
    }

    /**
     * 创建使用真实认证上下文和异常翻译器的执行器。
     *
     * @param identityService IdentityService，测试 Flowable 身份服务替身
     * @param identityResolver WorkflowIdentityResolver，测试正式身份解析器替身
     * @return WorkflowEngineOperations，待测统一执行边界
     */
    private WorkflowEngineOperations operations(IdentityService identityService,
            WorkflowIdentityResolver identityResolver)
    {
        WorkflowAuthenticationContext authenticationContext = new WorkflowAuthenticationContext(
                identityService, new WorkflowIdentityCodec());
        return new WorkflowEngineOperations(
                authenticationContext, new WorkflowExceptionTranslator(), identityResolver);
    }

    /**
     * 为执行器安装基于 @Transactional 的 Spring 代理，以验证真实方法级事务边界。
     *
     * @param target WorkflowEngineOperations，待代理的执行器实例
     * @param transactionManager PlatformTransactionManager，记录事务开始与提交的测试事务管理器
     * @return WorkflowEngineOperations，应用声明式事务拦截器后的执行器代理
     */
    private WorkflowEngineOperations transactionalProxy(WorkflowEngineOperations target,
            PlatformTransactionManager transactionManager)
    {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                (TransactionManager) transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(interceptor);
        return (WorkflowEngineOperations) proxyFactory.getProxy();
    }

    /**
     * 安装显式可重复读写事务特征，供不依赖真实数据库的执行边界单测使用。
     *
     * @return 无返回值，当前测试线程被标记为活动可重复读写事务
     */
    private void bindRepeatableReadWriteTransaction()
    {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                Connection.TRANSACTION_REPEATABLE_READ);
    }

    /**
     * 验证一种活动但不兼容的外层事务会在任何身份或回调副作用前失败。
     *
     * @param readOnly boolean，模拟外层事务是否只读
     * @param isolationLevel Integer，模拟隔离级别；null 表示数据库默认隔离
     * @return 无返回值，门禁返回稳定错误并验证零身份副作用
     */
    private void assertRejectedWriteTransaction(boolean readOnly, Integer isolationLevel)
    {
        IdentityService identityService = mock(IdentityService.class);
        WorkflowIdentityResolver identityResolver = mock(WorkflowIdentityResolver.class);
        @SuppressWarnings("unchecked")
        Function<WorkflowCurrentIdentity, String> action = mock(Function.class);
        WorkflowEngineOperations operations = operations(identityService, identityResolver);
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolationLevel);

        assertThatThrownBy(() -> operations.writeAsCurrentUser(action))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage()).isEqualTo(
                            WorkflowEngineOperations.WRITE_TRANSACTION_CONTRACT_MESSAGE);
                });
        verifyNoInteractions(identityResolver, identityService, action);
    }
}
