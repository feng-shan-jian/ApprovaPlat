package com.ruoyi.flowable.testsupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * 真实 MySQL 集成测试的公共环境、隔离库门禁与 Spring 事务代理支撑。
 */
public final class WorkflowMySqlITSupport
{
    /** profile 唯一允许读取的 MySQL JDBC URL 环境变量。 */
    private static final String URL_ENVIRONMENT = "WORKFLOW_MYSQL_TEST_URL";
    /** profile 唯一允许读取的 MySQL 用户名环境变量。 */
    private static final String USERNAME_ENVIRONMENT = "WORKFLOW_MYSQL_TEST_USERNAME";
    /** profile 唯一允许读取的 MySQL 密码环境变量。 */
    private static final String PASSWORD_ENVIRONMENT = "WORKFLOW_MYSQL_TEST_PASSWORD";
    /** 任何真实集成测试都禁止连接的 MySQL 系统 schema。 */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "mysql", "information_schema", "performance_schema", "sys");

    /**
     * 禁止实例化无状态测试基础设施。
     *
     * @return 无返回值，该类只提供静态支撑方法
     */
    private WorkflowMySqlITSupport()
    {
    }

    /**
     * 从 workflow-mysql-it profile 要求的三个环境变量创建真实 MySQL 数据源。
     *
     * @return MysqlDataSource，未猜测默认地址、账号或密码的真实数据源
     */
    public static MysqlDataSource createDataSource()
    {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(requireEnvironment(URL_ENVIRONMENT, false));
        dataSource.setUser(requireEnvironment(USERNAME_ENVIRONMENT, false));
        dataSource.setPassword(requireEnvironment(PASSWORD_ENVIRONMENT, true));
        return dataSource;
    }

    /**
     * 只读核验 MySQL 版本、隔离 schema 身份和当前场景依赖的完整哨兵表。
     *
     * @param dataSource MysqlDataSource，已经由显式环境变量创建的数据源
     * @param scenarioName String，失败消息中标识当前集成场景的名称
     * @param requiredDatabase String，必须精确匹配的隔离库名；为空时仅拒绝系统 schema
     * @param requiredTables Collection&lt;String&gt;，当前场景启动前必须存在的表名
     * @return void，环境不属于 MySQL 8+ 隔离基线时立即失败关闭
     * @throws SQLException JDBC 元数据读取失败时报告
     */
    public static void verifyIsolatedBaseline(MysqlDataSource dataSource,
            String scenarioName, String requiredDatabase,
            Collection<String> requiredTables) throws SQLException
    {
        if (dataSource == null || scenarioName == null || scenarioName.isBlank()
                || requiredTables == null || requiredTables.isEmpty())
        {
            throw new IllegalArgumentException("MySQL IT 隔离库校验参数不合法");
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            String database;
            try (ResultSet environment = statement.executeQuery(
                    "select version(),database()"))
            {
                if (!environment.next())
                {
                    throw new IllegalStateException(scenarioName + " 无法读取 MySQL 环境");
                }
                int major = Integer.parseInt(environment.getString(1).split("\\.")[0]);
                if (major < 8)
                {
                    throw new IllegalStateException(scenarioName + " 只允许 MySQL 8+");
                }
                database = environment.getString(2);
                if (database == null || database.isBlank())
                {
                    throw new IllegalStateException("必须在 JDBC URL 中显式指定隔离 schema");
                }
                String normalizedDatabase = database.toLowerCase(Locale.ROOT);
                if (SYSTEM_SCHEMAS.contains(normalizedDatabase))
                {
                    throw new IllegalStateException("禁止连接 MySQL 系统 schema: " + database);
                }
                if (requiredDatabase != null && !requiredDatabase.isBlank()
                        && !requiredDatabase.equals(database))
                {
                    throw new IllegalStateException(scenarioName + " 拒绝在非 "
                            + requiredDatabase + " 数据库执行");
                }
            }

            Set<String> installedTables = new HashSet<>();
            try (ResultSet tables = statement.executeQuery(
                    "select lower(table_name) from information_schema.tables "
                    + "where table_schema=database()"))
            {
                while (tables.next())
                {
                    installedTables.add(tables.getString(1));
                }
            }
            Set<String> missingTables = new HashSet<>();
            for (String requiredTable : requiredTables)
            {
                String normalizedTable = String.valueOf(requiredTable)
                        .toLowerCase(Locale.ROOT);
                if (!installedTables.contains(normalizedTable))
                {
                    missingTables.add(normalizedTable);
                }
            }
            if (!missingTables.isEmpty())
            {
                throw new IllegalStateException(scenarioName
                        + " 缺少当前隔离基线表: " + missingTables);
            }
        }
    }

    /**
     * 为生产对象应用基于 @Transactional 的 CGLIB Spring 事务代理。
     *
     * @param target T，需要代理的生产对象
     * @param transactionManager PlatformTransactionManager，绑定当前测试数据源的事务管理器
     * @param <T> 生产对象类型
     * @return T，保留生产类型并执行正式事务注解的代理
     */
    @SuppressWarnings("unchecked")
    public static <T> T transactionalProxy(T target,
            PlatformTransactionManager transactionManager)
    {
        if (target == null || transactionManager == null)
        {
            throw new IllegalArgumentException("MySQL IT 事务代理参数不合法");
        }
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return (T) proxyFactory.getProxy();
    }

    /**
     * 读取必需 MySQL 验收环境变量，禁止默认数据库、账号或密码猜测。
     *
     * @param name String，环境变量名
     * @param allowEmpty boolean，是否允许显式配置空密码
     * @return String，当前进程显式提供的值
     */
    private static String requireEnvironment(String name, boolean allowEmpty)
    {
        String value = System.getenv(name);
        if (value == null || (!allowEmpty && value.isBlank()))
        {
            throw new IllegalStateException("未显式配置真实 MySQL 验收变量: " + name);
        }
        return value;
    }
}
