package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;
import com.ruoyi.flowable.service.process.WorkflowConnectorInvocationService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL 连接器部署冻结、安全门禁和主库事务执行测试。
 */
class WorkflowSqlConnectorTest
{
    private JdbcDataSource primaryDataSource;
    private JdbcTemplate jdbc;
    private WorkflowSqlDataSourceService dataSourceService;
    private WorkflowConnectorInvocationService invocationService;
    private WorkflowSqlConnector connector;

    /**
     * 创建独立数据库、受控目录替身和真实 JDBC 执行链。
     * @return void，初始化后每个测试拥有独立主库
     */
    @BeforeEach
    void setUp()
    {
        primaryDataSource = new JdbcDataSource();
        primaryDataSource.setURL("jdbc:h2:mem:workflow_sql_connector;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(primaryDataSource);
        jdbc.execute("drop table if exists wf_sql_it_target");
        jdbc.execute("create table wf_sql_it_target (request_id varchar(64) primary key, result_value varchar(64))");
        jdbc.update("insert into wf_sql_it_target(request_id, result_value) values (?, ?)",
                "req-1", "before");
        dataSourceService = mock(WorkflowSqlDataSourceService.class);
        invocationService = mock(WorkflowConnectorInvocationService.class);
        connector = new WorkflowSqlConnector(primaryDataSource, dataSourceService,
                new WorkflowSqlTemplateValidator(), new WorkflowSqlSecretResolver(),
                invocationService);
    }

    /**
     * 验证部署冻结数据源修订、AST 操作、表清单和摘要，不写入凭据正文。
     * @return void，冻结配置缺字段或包含明文凭据时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void freezesExactPrimaryDataSourceRevision() throws Exception
    {
        WfSqlDataSource source = source("PRIMARY");
        when(dataSourceService.lockEnabledForDeployment("approva.primary"))
                .thenReturn(source);

        String frozen = connector.freezeConfig(config(
                "update wf_sql_it_target set result_value = :resultValue "
                        + "where request_id = :requestId"), false);
        JsonNode node = JsonMapper.shared().readTree(frozen);

        assertThat(node.path("operation").asText()).isEqualTo("UPDATE");
        assertThat(node.path("tables").get(0).asText()).isEqualTo("wf_sql_it_target");
        assertThat(node.path("dataSourceSnapshot").path("revisionNo").asInt()).isEqualTo(3);
        assertThat(frozen).doesNotContain("password", "jdbc:h2:");
    }

    /**
     * 验证主库 SQL 复用 Spring 事务连接，回滚时业务写入不泄漏。
     * @return void，连接器绕开主事务或变量映射漂移时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void participatesInPrimarySpringTransactionAndRollsBack() throws Exception
    {
        when(dataSourceService.lockEnabledForDeployment("approva.primary"))
                .thenReturn(source("PRIMARY"));
        JsonNode frozen = JsonMapper.shared().readTree(connector.freezeConfig(config(
                "update wf_sql_it_target set result_value = :resultValue "
                        + "where request_id = :requestId"), false));
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getVariable("requestId")).thenReturn("req-1");
        when(execution.getVariable("resultValue")).thenReturn("after");
        WfDeployExtensionSnapshot snapshot = new WfDeployExtensionSnapshot();

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(primaryDataSource));
        transaction.executeWithoutResult(status ->
        {
            connector.execute(execution, snapshot, frozen);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject(
                "select result_value from wf_sql_it_target where request_id = 'req-1'",
                String.class)).isEqualTo("before");
        verify(execution).setVariable("affected", 1);
    }

    /**
     * 验证外库写入必须异步且 SQL 显式消费系统幂等键。
     * @return void，不可重放副作用若可部署则测试失败
     */
    @Test
    void rejectsExternalWriteWithoutAsyncIdempotencyContract()
    {
        when(dataSourceService.lockEnabledForDeployment("approva.primary"))
                .thenReturn(source("EXTERNAL"));

        assertThatThrownBy(() -> connector.freezeConfig(config(
                "update wf_sql_it_target set result_value = :resultValue "
                        + "where request_id = :requestId"), false))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("必须异步");
        assertThatThrownBy(() -> connector.freezeConfig(config(
                "update wf_sql_it_target set result_value = :resultValue "
                        + "where request_id = :requestId"), true))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("idempotencyKey");
    }

    /**
     * 构造作者 SQL 配置。
     * @param sql String，单条命名参数 SQL
     * @return JsonNode，字段完整的作者配置
     */
    private JsonNode config(String sql)
    {
        return JsonMapper.shared().createObjectNode()
                .put("dataSourceKey", "approva.primary")
                .put("sql", sql)
                .set("parameters", JsonMapper.shared().createObjectNode()
                        .put("requestId", "requestId")
                        .put("resultValue", "resultValue"))
                .put("resultVariable", "affected")
                .put("maxRows", 100);
    }

    /**
     * 构造摘要完整的数据源当前修订。
     * @param connectionType String，PRIMARY 或 EXTERNAL
     * @return WfSqlDataSource，字段完整的数据源目录
     */
    private WfSqlDataSource source(String connectionType)
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceId(8L);
        source.setDataSourceKey("approva.primary");
        source.setDataSourceName("主业务库");
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
}
