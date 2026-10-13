package com.ruoyi.flowable.service.integration;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.extension.WorkflowSqlConnector;
import com.ruoyi.flowable.extension.WorkflowSqlTemplateValidator;
import com.ruoyi.flowable.mapper.WfSqlDataSourceMapper;
import com.ruoyi.flowable.runtime.WorkflowConnectorMetrics;
import com.ruoyi.flowable.testsupport.WorkflowFlowableEngineTestSupport;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class WorkflowSqlTransactionReplayTest
{
    private static final JsonMapper JSON = JsonMapper.shared();

    @Test
    void primaryWriteRollsBackWithTheFailingJobAndCommitsOnRetry()
    {
        String table = "root_rule_sql_primary";
        ObjectNode frozen = freeze(source("PRIMARY", table), author(table, false));
        AtomicReference<WorkflowSqlConnector> connector = new AtomicReference<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        try (var engine = WorkflowFlowableEngineTestSupport.start("b14-primary", delegates(connector, frozen, fail)))
        {
            JdbcTemplate jdbc = engine.jdbcTemplate();
            jdbc.execute("create table " + table + " (payload varchar(100))");
            connector.set(connector(engine.dataSource(), mock(WorkflowSqlSecretResolver.class)));
            String deployment = deploy(engine);
            String instance = engine.processEngine().getRuntimeService().startProcessInstanceByKey("sqlReplay", Map.of("payload", "first")).getId();
            String job = engine.processEngine().getManagementService().createJobQuery().processInstanceId(instance).singleResult().getId();
            assertThrows(RuntimeException.class, () -> engine.processEngine().getManagementService().executeJob(job));
            assertEquals(0, jdbc.queryForObject("select count(*) from " + table, Integer.class));
            var pending = engine.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).singleResult();
            assertEquals(job, pending.getId());
            assertEquals(2, pending.getRetries());
            fail.set(false);
            assertEquals(job, engine.processEngine().getManagementService().moveTimerToExecutableJob(job).getId());
            engine.processEngine().getManagementService().executeJob(job);
            assertEquals(List.of("first"), jdbc.queryForList("select payload from " + table, String.class));
            assertNull(engine.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(instance).singleResult());
            engine.processEngine().getRepositoryService().deleteDeployment(deployment, true);
        }
    }

    @Test
    void externalCommitSurvivesJobRollbackAndRetryKeepsOneRowWithItsOriginalValue() throws Exception
    {
        try (ExternalFixture external = new ExternalFixture())
        {
            ObjectNode frozen = freeze(source("EXTERNAL", external.table), author(external.table, true));
            AtomicReference<WorkflowSqlConnector> connector = new AtomicReference<>(external.connector);
            AtomicBoolean fail = new AtomicBoolean(true);
            try (var engine = WorkflowFlowableEngineTestSupport.start(external.dataSource, delegates(connector, frozen, fail)))
            {
                String deployment = deploy(engine);
                try
                {
                    String instance = engine.processEngine().getRuntimeService().startProcessInstanceByKey("sqlReplay", Map.of("payload", "original")).getId();
                    String job = engine.processEngine().getManagementService().createJobQuery().processInstanceId(instance).singleResult().getId();
                    String execution = engine.processEngine().getManagementService().createJobQuery().jobId(job).singleResult().getExecutionId();
                    assertThrows(RuntimeException.class, () -> engine.processEngine().getManagementService().executeJob(job));
                    assertEquals(List.of("original"), external.values());
                    var pending = engine.processEngine().getManagementService().createTimerJobQuery().processInstanceId(instance).singleResult();
                    assertEquals(job, pending.getId());
                    assertEquals(execution, pending.getExecutionId());
                    assertEquals(2, pending.getRetries());
                    engine.processEngine().getRuntimeService().setVariable(instance, "payload", "retry-must-not-replace");
                    fail.set(false);
                    assertEquals(job, engine.processEngine().getManagementService().moveTimerToExecutableJob(job).getId());
                    engine.processEngine().getManagementService().executeJob(job);
                    assertEquals(List.of("original"), external.values());
                    assertNull(engine.processEngine().getManagementService().createJobQuery().jobId(job).singleResult());
                    assertNull(engine.processEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(instance).singleResult());
                    assertNotNull(engine.processEngine().getHistoryService().createHistoricProcessInstanceQuery().processInstanceId(instance).finished().singleResult());
                }
                finally
                {
                    engine.processEngine().getRepositoryService().deleteDeployment(deployment, true);
                }
            }
        }
    }

    @Test
    void simultaneousExternalReplaysShareOneIdentityButAnotherExecutionWritesAnotherRow() throws Exception
    {
        try (ExternalFixture external = new ExternalFixture())
        {
            ObjectNode frozen = freeze(source("EXTERNAL", external.table), author(external.table, true));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            var workers = Executors.newFixedThreadPool(2);
            try
            {
                var first = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    external.connector.execute(execution("same", "original-a"), frozen);
                    return null;
                });
                var second = workers.submit(() -> {
                    ready.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    external.connector.execute(execution("same", "original-b"), frozen);
                    return null;
                });
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                release.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
                assertEquals(1, external.values().size());
                String original = external.values().get(0);
                assertTrue(List.of("original-a", "original-b").contains(original));
                external.connector.execute(execution("same", "later"), frozen);
                assertEquals(List.of(original), external.values());
                external.connector.execute(execution("different", "another"), frozen);
                assertEquals(2, external.values().size());
                assertTrue(external.values().containsAll(List.of(original, "another")));
            }
            finally
            {
                release.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void unsafeExternalTemplatesAndResultVariablesAreRejectedBeforeExecution()
    {
        String table = "root_rule_sql_rejected";
        WorkflowSqlSecretResolver secrets = mock(WorkflowSqlSecretResolver.class);
        WorkflowSqlConnector connector = connector(mock(DataSource.class), secrets);
        ObjectNode valid = author(table, true);
        WfSqlDataSource source = source("EXTERNAL", table);
        ObjectNode frozen = freeze(source, valid);
        for (String sql : List.of(
                "update " + table + " set payload = :payload where idem = :idempotencyKey",
                "delete from " + table + " where idem = :idempotencyKey",
                "insert into " + table + "(idem,payload) values (:payload,:payload) on duplicate key update idem=idem",
                "insert into " + table + "(idem,payload) values (:idempotencyKey,:payload) on duplicate key update payload=values(payload)"))
        {
            assertThrows(ServiceException.class, () -> freeze(source, valid.deepCopy().put("sql", sql)));
            assertThrows(ServiceException.class, () -> connector.execute(execution("rejected", "unused"), frozen.deepCopy().put("sql", sql)));
        }
        // Reserved result variables already have broad HTTP/SQL coverage in WorkflowExtensionExecutionContractTest.
        // An ordinary output is also forbidden for external writes: MySQL affected-row counts vary on replay.
        assertThrows(ServiceException.class, () -> freeze(source, valid.deepCopy().put("resultVariable", "affectedRows")));
        assertThrows(ServiceException.class, () -> connector.execute(execution("rejected", "unused"),
                frozen.deepCopy().put("resultVariable", "affectedRows")));
        verifyNoInteractions(secrets);
    }

    private static WorkflowSqlConnector connector(DataSource primary, WorkflowSqlSecretResolver secrets)
    {
        return new WorkflowSqlConnector(primary, new WorkflowSqlTemplateValidator(), secrets, mock(WorkflowConnectorMetrics.class));
    }

    private static DelegateExecution execution(String id, String value)
    {
        DelegateExecution execution = mock(DelegateExecution.class);
        when(execution.getProcessInstanceId()).thenReturn("b14-process");
        when(execution.getId()).thenReturn(id);
        when(execution.getCurrentActivityId()).thenReturn("sql");
        when(execution.getVariable("payload")).thenReturn(value);
        return execution;
    }

    private static WfSqlDataSource source(String type, String table)
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceId(1L);
        source.setDataSourceKey("b14");
        source.setDataSourceName("B14 contract");
        source.setConnectionType(type);
        source.setAllowedTables(table);
        source.setConnectTimeoutMs(1000);
        source.setQueryTimeoutSeconds(5);
        source.setRevisionNo(1);
        if ("EXTERNAL".equals(type))
        {
            source.setJdbcUrlRef("WORKFLOW_SQL_JDBC_URL_B14");
            source.setUsernameRef("WORKFLOW_SQL_USERNAME_B14");
            source.setPasswordRef("WORKFLOW_SQL_PASSWORD_B14");
        }
        source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
        return source;
    }

    private static ObjectNode author(String table, boolean external)
    {
        ObjectNode config = JSON.createObjectNode().put("dataSourceKey", "b14");
        config.put("sql", external ? "insert into " + table + "(idem,payload) values (:idempotencyKey,:payload) on duplicate key update idem=idem"
                : "insert into " + table + "(payload) values (:payload)");
        config.putObject("parameters").put("payload", "payload");
        if (external)
        {
            config.put("idempotencyColumn", "idem");
        }
        return config;
    }

    private static ObjectNode freeze(WfSqlDataSource source, ObjectNode author)
    {
        WfSqlDataSourceMapper mapper = mock(WfSqlDataSourceMapper.class);
        when(mapper.selectEnabledByKeyForUpdate("b14")).thenReturn(source);
        WorkflowSqlDataSourceService catalog = new WorkflowSqlDataSourceService(null, mapper, new WorkflowSqlTemplateValidator());
        return (ObjectNode) JSON.readTree(catalog.freezeConfig(author, true));
    }

    private static Map<Object, Object> delegates(AtomicReference<WorkflowSqlConnector> connector, ObjectNode config, AtomicBoolean fail)
    {
        return Map.of("sqlConnector", (JavaDelegate) execution -> connector.get().execute(execution, config),
                "downstream", (JavaDelegate) execution -> {
                    if (fail.get())
                    {
                        throw new IllegalStateException("controlled downstream failure after SQL");
                    }
                });
    }

    private static String deploy(WorkflowFlowableEngineTestSupport engine)
    {
        return engine.processEngine().getRepositoryService().createDeployment().addString("b14.bpmn20.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="B14">
                  <process id="sqlReplay" isExecutable="true">
                    <startEvent id="start"/><sequenceFlow id="one" sourceRef="start" targetRef="sql"/>
                    <serviceTask id="sql" flowable:async="true" flowable:delegateExpression="${sqlConnector}"/>
                    <sequenceFlow id="two" sourceRef="sql" targetRef="after"/>
                    <serviceTask id="after" flowable:delegateExpression="${downstream}"/>
                    <sequenceFlow id="three" sourceRef="after" targetRef="end"/><endEvent id="end"/>
                  </process>
                </definitions>
                """).deploy().getId();
    }

    private static final class ExternalFixture implements AutoCloseable
    {
        final String table = "root_rule_sql_" + UUID.randomUUID().toString().replace("-", "");
        final DataSource dataSource;
        final JdbcTemplate jdbc;
        final WorkflowSqlConnector connector;

        ExternalFixture()
        {
            String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
            assumeTrue(url != null && !url.isBlank(), "WORKFLOW_MYSQL_TEST_URL required");
            WorkflowMysqlTargetGuard.verify(url);
            String username = System.getenv("WORKFLOW_MYSQL_TEST_USERNAME");
            String password = System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD");
            dataSource = new DriverManagerDataSource(url, username, password);
            jdbc = new JdbcTemplate(dataSource);
            WorkflowSqlSecretResolver secrets = mock(WorkflowSqlSecretResolver.class);
            when(secrets.requireJdbcUrl("WORKFLOW_SQL_JDBC_URL_B14")).thenReturn(url);
            when(secrets.requireUsername("WORKFLOW_SQL_USERNAME_B14")).thenReturn(username);
            when(secrets.requirePassword("WORKFLOW_SQL_PASSWORD_B14")).thenReturn(password);
            connector = connector(dataSource, secrets);
            jdbc.execute("create table " + table + " (idem varchar(64) primary key, payload varchar(100)) engine=InnoDB");
        }

        List<String> values()
        {
            return jdbc.queryForList("select payload from " + table + " order by idem", String.class);
        }

        @Override
        public void close()
        {
            jdbc.execute("drop table " + table);
        }
    }
}
