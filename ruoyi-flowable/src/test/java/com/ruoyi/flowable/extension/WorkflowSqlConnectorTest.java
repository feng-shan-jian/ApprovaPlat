package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flowable.engine.delegate.DelegateExecution;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL 连接器的 Flowable Job、事务和外库幂等写契约测试。
 */
class WorkflowSqlConnectorTest
{
    private JdbcDataSource primaryDataSource;
    private JdbcDataSource externalDataSource;
    private JdbcTemplate primaryJdbc;
    private JdbcTemplate externalJdbc;
    private WorkflowSqlDataSourceService dataSourceService;
    private WorkflowSqlSecretResolver secretResolver;
    private SimpleMeterRegistry meterRegistry;
    private WorkflowSqlConnector connector;

    /**
     * 创建相互隔离的主库、外库和真实 JDBC 执行链。
     * @return void，每个测试使用清空后的目标表
     */
    @BeforeEach
    void setUp()
    {
        primaryDataSource = dataSource("workflow_sql_primary");
        externalDataSource = dataSource("workflow_sql_external");
        primaryJdbc = initialize(primaryDataSource);
        externalJdbc = initialize(externalDataSource);
        dataSourceService = mock(WorkflowSqlDataSourceService.class);
        secretResolver = mock(WorkflowSqlSecretResolver.class);
        when(secretResolver.requireJdbcUrl("WORKFLOW_SQL_JDBC_URL_TEST"))
                .thenReturn(externalDataSource.getURL());
        when(secretResolver.requireUsername("WORKFLOW_SQL_USERNAME_TEST")).thenReturn("sa");
        when(secretResolver.requirePassword("WORKFLOW_SQL_PASSWORD_TEST")).thenReturn("");
        meterRegistry = new SimpleMeterRegistry();
        connector = new WorkflowSqlConnector(primaryDataSource, dataSourceService,
                new WorkflowSqlTemplateValidator(), secretResolver,
                new WorkflowConnectorMetrics(meterRegistry));
    }

    /**
     * 验证主库 SQL 作为异步 ServiceTask 仍复用 Flowable 所在 Spring 事务并可整体回滚。
     * @return void，连接器绕开主事务或未写受控结果变量时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void participatesInPrimaryFlowableTransactionAndRollsBack() throws Exception
    {
        when(dataSourceService.lockEnabledForDeployment("approva.primary"))
                .thenReturn(source("PRIMARY"));
        JsonNode frozen = JsonMapper.shared().readTree(connector.freezeConfig(config(
                "update wf_sql_it_target set result_value = :resultValue "
                        + "where request_id = :requestId", null, true), true));
        DelegateExecution execution = execution();
        when(execution.getVariable("requestId")).thenReturn("req-1");
        when(execution.getVariable("resultValue")).thenReturn("after");

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(primaryDataSource));
        transaction.executeWithoutResult(status ->
        {
            connector.execute(execution, new WfDeployExtensionSnapshot(), frozen);
            status.setRollbackOnly();
        });

        assertThat(primaryJdbc.queryForObject(
                "select result_value from wf_sql_it_target where request_id = 'req-1'",
                String.class)).isEqualTo("before");
        verify(execution).setVariable("affected", 1);
    }

    /**
     * 验证同一 Flowable execution 重试外库幂等 INSERT 时只保留一条业务唯一记录。
     * @return void，第二次尝试产生重复副作用或失败时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void retriesExternalIdempotentInsertWithoutDuplicateSideEffect() throws Exception
    {
        when(dataSourceService.lockEnabledForDeployment("approva.primary"))
                .thenReturn(source("EXTERNAL"));
        String sql = "insert into wf_sql_it_target(request_id, result_value) "
                + "values (:idempotencyKey, :resultValue) "
                + "on duplicate key update request_id = request_id";
        JsonNode frozen = JsonMapper.shared().readTree(connector.freezeConfig(
                config(sql, "request_id", false), true));
        DelegateExecution execution = execution();
        when(execution.getVariable("resultValue")).thenReturn("created");

        connector.execute(execution, new WfDeployExtensionSnapshot(), frozen);
        connector.execute(execution, new WfDeployExtensionSnapshot(), frozen);

        assertThat(externalJdbc.queryForObject(
                "select count(*) from wf_sql_it_target where result_value = 'created'",
                Integer.class)).isEqualTo(1);
        assertThat(meterRegistry.get("workflow.connector.attempts")
                .tags("type", "sql", "result", "success").counter().count())
                .isEqualTo(2.0D);
    }

    /**
     * 验证同步节点、普通 UPDATE 和缺少 no-op 重复键分支的外库写都在部署前拒绝。
     * @return void，任何非幂等写能够部署时测试失败
     */
    @Test
    void rejectsSynchronousAndNonIdempotentExternalWrites()
    {
        when(dataSourceService.lockEnabledForDeployment("approva.primary"))
                .thenReturn(source("EXTERNAL"));
        assertThatThrownBy(() -> connector.freezeConfig(config(
                "select result_value from wf_sql_it_target where request_id = :requestId",
                null, true), false))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("必须启用进入前异步");
        assertThatThrownBy(() -> connector.freezeConfig(config(
                "update wf_sql_it_target set result_value = :resultValue "
                        + "where request_id = :requestId", "request_id", true), true))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("只允许使用 idempotencyKey 的幂等 INSERT");
        assertThatThrownBy(() -> connector.freezeConfig(config(
                "insert into wf_sql_it_target(request_id, result_value) "
                        + "values (:idempotencyKey, :resultValue)", "request_id", false), true))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("no-op 重复键分支");
    }

    /**
     * 构造作者 SQL 配置。
     * @param sql String，单条命名参数 SQL
     * @param idempotencyColumn String，可空业务唯一列
     * @param includeRequestId boolean，是否映射 requestId 参数
     * @return JsonNode，字段完整的作者配置
     */
    private JsonNode config(String sql, String idempotencyColumn, boolean includeRequestId)
    {
        var parameters = JsonMapper.shared().createObjectNode();
        if (includeRequestId) parameters.put("requestId", "requestId");
        if (sql.contains(":resultValue")) parameters.put("resultValue", "resultValue");
        var config = JsonMapper.shared().createObjectNode()
                .put("dataSourceKey", "approva.primary")
                .put("sql", sql)
                .set("parameters", parameters)
                .put("maxRows", 100);
        if (idempotencyColumn != null) config.put("idempotencyColumn", idempotencyColumn);
        if (!sql.toLowerCase(java.util.Locale.ROOT).startsWith("insert"))
        {
            config.put("resultVariable", "affected");
        }
        return config;
    }

    /**
     * 创建摘要完整的数据源修订。
     * @param connectionType String，PRIMARY 或 EXTERNAL
     * @return WfSqlDataSource，启用的数据源目录项
     */
    private WfSqlDataSource source(String connectionType)
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceId(8L);
        source.setDataSourceKey("approva.primary");
        source.setDataSourceName("业务库");
        source.setConnectionType(connectionType);
        if ("EXTERNAL".equals(connectionType))
        {
            source.setJdbcUrlRef("WORKFLOW_SQL_JDBC_URL_TEST");
            source.setUsernameRef("WORKFLOW_SQL_USERNAME_TEST");
            source.setPasswordRef("WORKFLOW_SQL_PASSWORD_TEST");
        }
        source.setAllowedTables("wf_sql_it_target");
        source.setConnectTimeoutMs(1000);
        source.setQueryTimeoutSeconds(10);
        source.setRevisionNo(3);
        source.setStatus("ENABLED");
        source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
        return source;
    }

    /**
     * 创建固定流程身份的执行上下文。
     * @return DelegateExecution，连接器运行参数
     */
    private DelegateExecution execution()
    {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("instance");
        when(execution.getId()).thenReturn("execution");
        when(execution.getCurrentActivityId()).thenReturn("sqlTask");
        return execution;
    }

    /**
     * 创建 MySQL 兼容 H2 数据源。
     * @param name String，独立内存库名
     * @return JdbcDataSource，可供 Spring JDBC 和 DriverManager 使用的数据源
     */
    private JdbcDataSource dataSource(String name)
    {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    /**
     * 重建测试目标表并写入主库基线记录。
     * @param dataSource JdbcDataSource，待初始化数据库
     * @return JdbcTemplate，绑定该数据库的查询入口
     */
    private JdbcTemplate initialize(JdbcDataSource dataSource)
    {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists wf_sql_it_target");
        jdbc.execute("create table wf_sql_it_target (request_id varchar(64) primary key, "
                + "result_value varchar(64))");
        if (dataSource == primaryDataSource)
        {
            jdbc.update("insert into wf_sql_it_target(request_id, result_value) values (?, ?)",
                    "req-1", "before");
        }
        return jdbc;
    }
}
