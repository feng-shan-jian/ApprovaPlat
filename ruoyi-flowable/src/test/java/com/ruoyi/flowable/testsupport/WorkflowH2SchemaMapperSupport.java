package com.ruoyi.flowable.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * H2 schema 与生产 MyBatis XML 的共享测试装配，避免业务测试复制 DDL 或 Mapper 初始化代码。
 */
public final class WorkflowH2SchemaMapperSupport
{
    /** 多实例轮次 H2 正式测试资源。 */
    public static final String MULTI_INSTANCE_ROUND_SCHEMA =
            "db/h2/wf_multi_instance_round.sql";

    /** 附件真实绑定链共用的 H2 表资源。 */
    public static final String ATTACHMENT_SCHEMA = "db/h2/wf_attachment.sql";

    /** 任务 SLA 运行态与不可变审计 H2 表资源。 */
    public static final String TASK_SLA_SCHEMA = "db/h2/wf_task_sla.sql";

    /** 受控重复审批执行记录 H2 表资源。 */
    public static final String CONTROLLED_LOOP_EXECUTION_SCHEMA =
            "db/h2/wf_controlled_loop_execution.sql";

    /** 流程申请草稿 H2 表资源。 */
    public static final String PROCESS_DRAFT_SCHEMA = "db/h2/wf_process_draft.sql";

    /** 邮件配置业务测试共用的 H2 表资源。 */
    public static final String MAIL_CONFIG_SCHEMA =
            "com/ruoyi/flowable/service/notification/workflow-mail-config-h2.sql";

    /**
     * 禁止实例化无状态测试基础设施。
     *
     * @return 无返回值，该类只提供静态装配方法
     */
    private WorkflowH2SchemaMapperSupport()
    {
    }

    /**
     * 创建具备显式生命周期的独立 H2 内存数据源。
     *
     * @param databaseName String，当前测试独占的数据库名称
     * @param mysqlMode boolean，是否从首个连接起启用 H2 MySQL 兼容模式
     * @param lockTimeoutMillis int，数据库锁等待上限毫秒数
     * @return JdbcDataSource，调用方结束时必须执行 {@link #shutdown(DataSource)}
     */
    public static JdbcDataSource createDataSource(String databaseName,
            boolean mysqlMode, int lockTimeoutMillis)
    {
        if (databaseName == null || databaseName.isBlank() || lockTimeoutMillis <= 0)
        {
            throw new IllegalArgumentException("H2 测试数据源参数不合法");
        }
        JdbcDataSource dataSource = new JdbcDataSource();
        String mode = mysqlMode ? ";MODE=MySQL" : "";
        dataSource.setURL("jdbc:h2:mem:" + databaseName + mode
                + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT="
                + lockTimeoutMillis);
        dataSource.setUser("sa");
        return dataSource;
    }

    /**
     * 从 classpath 正式资源执行一份 H2 schema。
     *
     * @param dataSource DataSource，待初始化的独立 H2 数据源
     * @param schemaResource String，classpath 下的 SQL 资源路径
     * @return void，资源缺失或 SQL 不可执行时立即终止测试装配
     */
    public static void executeSchema(DataSource dataSource, String schemaResource)
    {
        if (dataSource == null || schemaResource == null || schemaResource.isBlank())
        {
            throw new IllegalArgumentException("H2 schema 装配参数不合法");
        }
        try (Connection connection = dataSource.getConnection())
        {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(schemaResource));
        }
        catch (SQLException | RuntimeException exception)
        {
            throw new IllegalStateException("测试无法加载 H2 schema: " + schemaResource,
                    exception);
        }
    }

    /**
     * 使用生产 Mapper XML 创建指定事务模型的 MyBatis SqlSessionFactory。
     *
     * @param dataSource DataSource，Mapper 使用的 H2 数据源
     * @param transactionFactory TransactionFactory，JDBC 或 Spring 托管事务实现
     * @param environmentId String，当前测试装配的 MyBatis 环境标识
     * @param mapperType Class&lt;T&gt;，正式 Mapper 接口类型
     * @param mapperResource String，classpath 下的生产 Mapper XML
     * @param <T> Mapper 接口类型
     * @return SqlSessionFactory，已经解析正式 XML 的会话工厂
     */
    public static <T> SqlSessionFactory createSessionFactory(DataSource dataSource,
            TransactionFactory transactionFactory, String environmentId,
            Class<T> mapperType, String mapperResource)
    {
        if (dataSource == null || transactionFactory == null
                || environmentId == null || environmentId.isBlank()
                || mapperType == null || mapperResource == null || mapperResource.isBlank())
        {
            throw new IllegalArgumentException("H2 Mapper 装配参数不合法");
        }
        Environment environment = new Environment(environmentId,
                transactionFactory, dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(mapperType);
        try (InputStream input = WorkflowH2SchemaMapperSupport.class.getClassLoader()
                .getResourceAsStream(mapperResource))
        {
            if (input == null)
            {
                throw new IllegalStateException("测试无法加载正式 Mapper: "
                        + mapperResource);
            }
            new XMLMapperBuilder(input, configuration, mapperResource,
                    configuration.getSqlFragments()).parse();
        }
        catch (IOException | RuntimeException exception)
        {
            throw new IllegalStateException("测试解析正式 Mapper 失败: "
                    + mapperResource, exception);
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 创建参与当前 Spring 数据源事务的正式 Mapper 代理。
     *
     * @param dataSource DataSource，Flowable 与业务表共享的数据源
     * @param environmentId String，当前测试装配的 MyBatis 环境标识
     * @param mapperType Class&lt;T&gt;，正式 Mapper 接口类型
     * @param mapperResource String，classpath 下的生产 Mapper XML
     * @param <T> Mapper 接口类型
     * @return T，使用 SpringManagedTransactionFactory 的 Mapper 代理
     */
    public static <T> T createSpringMapper(DataSource dataSource,
            String environmentId, Class<T> mapperType, String mapperResource)
    {
        SqlSessionFactory factory = createSessionFactory(dataSource,
                new SpringManagedTransactionFactory(), environmentId,
                mapperType, mapperResource);
        return new SqlSessionTemplate(factory).getMapper(mapperType);
    }

    /**
     * 显式关闭 H2 内存库，释放 DB_CLOSE_DELAY=-1 保留的数据库状态。
     *
     * @param dataSource DataSource，当前测试独占且不再使用的数据源
     * @return void，关闭失败会使清理检查失败
     */
    public static void shutdown(DataSource dataSource)
    {
        if (dataSource == null)
        {
            return;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            statement.execute("SHUTDOWN");
        }
        catch (SQLException exception)
        {
            throw new IllegalStateException("H2 测试数据源关闭失败", exception);
        }
    }
}
