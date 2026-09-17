package com.ruoyi.flowable.extension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlTestDatabase;

/** 独占 schema 中复用全部业务判据，并验证 MySQL 外库唯一键与真实连接边界。 */
class WorkflowExtensionExecutionMySqlIT extends WorkflowExtensionExecutionIntegrationTest
{
    private WorkflowMySqlTestDatabase database;

    @Override protected WorkflowFlowableEngineTestSupport startDatabase(Map<Object, Object> beans) throws Exception
    {
        database = new WorkflowMySqlTestDatabase("C08 connector execution");
        var result = WorkflowFlowableEngineTestSupport.startOnExistingDatabase(database.dataSource(), beans);
        try
        {
            result.jdbcTemplate().update("insert into wf_connector_endpoint(endpoint_id,endpoint_key,endpoint_name,base_url,allowed_methods,path_prefix,auth_type,network_scope,connect_timeout_ms,request_timeout_ms,revision_no,checksum,create_by) values(1,'c08','C08 local','http://127.0.0.1','POST','/','NONE','PRIVATE',1000,1000,1,?,'c08')", "0".repeat(64));
            return result;
        }
        catch (RuntimeException failure)
        {
            result.close();
            throw failure;
        }
    }

    @Override protected void closeDatabase() { if (database != null) database.close(); }

    @Test void outboxUsesRealMapperTransactionsAndFrozenDeliveryAcrossFailureRetryAndStaleCompletion()
    {
        verifyOutboxDeliveryRetry();
    }

    @Test void outboxRechecksStoredEndpointAtDeliveryAndRecordsFailureWithoutNetwork()
    {
        verifyOutboxStoredEndpointFailure();
    }

    @Test void externalWriteSurvivesEngineRollbackAndRetryUsesTheSameUniqueKey()
    {
        source.setConnectionType("EXTERNAL");
        source.setJdbcUrlRef("WORKFLOW_SQL_JDBC_URL_C08");
        source.setUsernameRef("WORKFLOW_SQL_USERNAME_C08");
        source.setPasswordRef("WORKFLOW_SQL_PASSWORD_C08");
        when(sqlSecrets.requireJdbcUrl(source.getJdbcUrlRef())).thenReturn(database.dataSource().getURL());
        when(sqlSecrets.requireUsername(source.getUsernameRef())).thenReturn(database.dataSource().getUser());
        when(sqlSecrets.requirePassword(source.getPasswordRef())).thenReturn(System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        var author = read(sqlAuthor("insert into c08_effect(id,amount) values(:idempotencyKey,:amount) on duplicate key update id=id"));
        author.remove("resultVariable"); author.put("idempotencyColumn", "id");
        var frozen = freezeSqlAuthor(author);
        String instance = start(deploy("SQL", frozen, s -> {}), Map.of("amount", 7));
        failAfter = true;
        assertThatThrownBy(() -> run(instance)).isInstanceOf(RuntimeException.class);
        var original = support.jdbcTemplate().queryForMap("select * from c08_effect");
        assertThat(original.get("amount")).isEqualTo(7);
        assertThat((String) original.get("id")).hasSize(64);
        failAfter = false;
        run(instance);
        assertThat(countEffects()).isOne();
        assertThat(support.jdbcTemplate().queryForMap("select * from c08_effect")).isEqualTo(original);
        var bad = frozen.deepCopy();
        bad.put("sql", bad.get("sql").asText().replace("id = id", "id = 'changed'"));
        assertThatThrownBy(() -> run(start(deploy("SQL", bad, s -> {}), Map.of("amount", 9))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("赋回自身");
        assertThat(support.jdbcTemplate().queryForMap("select * from c08_effect")).isEqualTo(original);
    }
}
