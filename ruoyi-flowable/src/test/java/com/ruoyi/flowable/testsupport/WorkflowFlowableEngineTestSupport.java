package com.ruoyi.flowable.testsupport;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngine;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 为集成测试提供无业务语义的独立 H2、Flowable 和 Spring 事务生命周期。
 */
public final class WorkflowFlowableEngineTestSupport implements AutoCloseable
{
    /** 当前场景独占且在 close 中显式关闭的 H2 数据源。 */
    private final JdbcDataSource dataSource;

    /** Flowable 与测试业务对象共用的数据源事务管理器。 */
    private final DataSourceTransactionManager transactionManager;

    /** 测试表准备和状态查询使用的 JDBC 入口。 */
    private final JdbcTemplate jdbcTemplate;

    /** 并发、回滚及显式事务场景使用的事务模板。 */
    private final TransactionTemplate transactionTemplate;

    /** 当前用例独占且在 close 中停止的真实 Flowable 引擎。 */
    private final ProcessEngine processEngine;

    /**
     * 保存已经完成装配的测试基础设施。
     *
     * @param dataSource JdbcDataSource，当前测试独占数据源
     * @param transactionManager DataSourceTransactionManager，共享事务管理器
     * @param jdbcTemplate JdbcTemplate，测试 JDBC 入口
     * @param transactionTemplate TransactionTemplate，可显式控制的事务模板
     * @param processEngine ProcessEngine，真实 Flowable 引擎
     * @return 无返回值，实例只能由 start 创建
     */
    private WorkflowFlowableEngineTestSupport(JdbcDataSource dataSource,
            DataSourceTransactionManager transactionManager,
            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
            ProcessEngine processEngine)
    {
        this.dataSource = dataSource;
        this.transactionManager = transactionManager;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.processEngine = processEngine;
    }

    /**
     * 启动独立 H2、Spring 事务和真实 Flowable 引擎，不创建任何业务表或部署 BPMN。
     *
     * @param databaseNamePrefix String，随机数据库名称的可读前缀
     * @param beans Map&lt;Object,Object&gt;，调用方 BPMN 表达式或监听器需要的 Bean
     * @return WorkflowFlowableEngineTestSupport，调用方必须在 teardown 中关闭
     */
    public static WorkflowFlowableEngineTestSupport start(String databaseNamePrefix,
            Map<Object, Object> beans)
    {
        if (databaseNamePrefix == null || databaseNamePrefix.isBlank() || beans == null)
        {
            throw new IllegalArgumentException("Flowable 测试引擎参数不合法");
        }
        JdbcDataSource dataSource = WorkflowH2SchemaMapperSupport.createDataSource(
                databaseNamePrefix + "-" + UUID.randomUUID(), false, 10000);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ);
        ProcessEngine processEngine = null;
        try
        {
            SpringProcessEngineConfiguration configuration =
                    new SpringProcessEngineConfiguration();
            configuration.setDataSource(dataSource);
            configuration.setTransactionManager(transactionManager);
            configuration.setDatabaseSchemaUpdate("true");
            configuration.setHistory("full");
            configuration.setBeans(Map.copyOf(beans));
            processEngine = configuration.buildProcessEngine();
            return new WorkflowFlowableEngineTestSupport(dataSource,
                    transactionManager, jdbcTemplate, transactionTemplate, processEngine);
        }
        catch (RuntimeException exception)
        {
            closeAfterStartupFailure(processEngine, dataSource, exception);
            throw exception;
        }
    }

    /**
     * 返回当前测试独占数据源。
     *
     * @return DataSource，Flowable 与测试业务对象共用的数据源
     */
    public DataSource dataSource()
    {
        return dataSource;
    }

    /**
     * 返回真实 Flowable 引擎。
     *
     * @return ProcessEngine，当前业务场景唯一引擎
     */
    public ProcessEngine processEngine()
    {
        return processEngine;
    }

    /**
     * 返回测试表准备与查询入口。
     *
     * @return JdbcTemplate，绑定当前独占 H2 数据源
     */
    public JdbcTemplate jdbcTemplate()
    {
        return jdbcTemplate;
    }

    /**
     * 返回显式事务模板。
     *
     * @return TransactionTemplate，使用 REPEATABLE_READ 隔离级别
     */
    public TransactionTemplate transactionTemplate()
    {
        return transactionTemplate;
    }

    /**
     * 返回 Flowable 与测试业务 Bean 共用的事务管理器。
     *
     * @return DataSourceTransactionManager，绑定当前独立 H2 数据源
     */
    public DataSourceTransactionManager transactionManager()
    {
        return transactionManager;
    }

    /**
     * 为生产服务对象应用真实 @Transactional 拦截器。
     *
     * @param target T，需要加入 Flowable 共享事务的生产服务
     * @param <T> 生产服务类型
     * @return T，保留生产类型的 CGLIB 事务代理
     */
    @SuppressWarnings("unchecked")
    public <T> T transactionalProxy(T target)
    {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (T) proxyFactory.getProxy();
    }

    /**
     * 依次停止真实引擎并关闭 H2 内存库，避免线程、连接和数据库状态跨用例残留。
     *
     * @return void，即使引擎关闭失败也继续尝试释放 H2
     */
    @Override
    public void close()
    {
        RuntimeException failure = null;
        try
        {
            processEngine.close();
        }
        catch (RuntimeException exception)
        {
            failure = exception;
        }
        try
        {
            WorkflowH2SchemaMapperSupport.shutdown(dataSource);
        }
        catch (RuntimeException exception)
        {
            if (failure == null)
            {
                failure = exception;
            }
            else
            {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null)
        {
            throw failure;
        }
    }

    /**
     * 引擎启动失败时尽力释放已创建资源，并把清理异常附加到原异常。
     *
     * @param processEngine ProcessEngine，可能尚未完成构建的引擎
     * @param dataSource DataSource，已经创建且必须关闭的 H2 数据源
     * @param failure RuntimeException，启动阶段原始异常
     * @return void，清理异常通过 suppressed 保留
     */
    private static void closeAfterStartupFailure(ProcessEngine processEngine,
            DataSource dataSource, RuntimeException failure)
    {
        try
        {
            if (processEngine != null)
            {
                processEngine.close();
            }
        }
        catch (RuntimeException closeException)
        {
            failure.addSuppressed(closeException);
        }
        try
        {
            WorkflowH2SchemaMapperSupport.shutdown(dataSource);
        }
        catch (RuntimeException closeException)
        {
            failure.addSuppressed(closeException);
        }
    }
}
