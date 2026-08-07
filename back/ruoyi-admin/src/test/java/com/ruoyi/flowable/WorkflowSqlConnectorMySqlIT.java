package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.delegate.JavaDelegate;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowSqlConnector;
import com.ruoyi.flowable.mapper.WfDeployExtensionSnapshotMapper;
import com.ruoyi.flowable.mapper.WfSqlDataSourceMapper;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 MySQL 与 Flowable 引擎中的 SQL 连接器提交、回滚和部署快照集成测试。
 */
@SpringBootTest(classes = {RuoYiApplication.class, WorkflowSqlConnectorMySqlIT.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctc3FsLWl0LXRva2VuLXNlY3JldC13b3JrZmxvdy1zcWwtaXQtdG9rZW4tc2VjcmV0LXdvcmtmbG93LXNxbC1pdC10b2tlbi1zZWNyZXQtd29ya2Zsb3ctc3FsLWl0LXRva2VuLXNlY3JldC0=",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=false",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false"
        })
class WorkflowSqlConnectorMySqlIT
{
    private static final String PREFIX = "workflow-sql-connector-it-";
    private static final String ORIGINAL_NAME = "Flowable SQL IT";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private WorkflowSqlConnector connector;
    @Autowired
    private WfDeployExtensionSnapshotMapper snapshotMapper;
    @Autowired
    private WfSqlDataSourceMapper dataSourceMapper;

    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private final java.util.Set<String> deploymentIds = new java.util.LinkedHashSet<>();
    private String dataSourceKey;
    private Long dataSourceId;

    /**
     * 创建本轮唯一业务表和受控主库数据源目录。
     * @return void，完成后可部署真实 SQL ServiceTask
     */
    @BeforeEach
    void setUp()
    {
        dataSourceKey = PREFIX + runId;
        WfSqlDataSource source = source();
        jdbc.update("insert into wf_sql_datasource "
                + "(datasource_key, datasource_name, connection_type, jdbc_url_ref, username_ref, "
                + "password_ref, allowed_tables, connect_timeout_ms, query_timeout_seconds, "
                + "revision_no, status, checksum, create_by, create_time, update_by, update_time) "
                + "values (?, ?, 'PRIMARY', null, null, null, 'wf_sql_datasource', 1000, 10, "
                + "1, 'ENABLED', ?, 'flowable-it', current_timestamp(3), '', null)",
                source.getDataSourceKey(), source.getDataSourceName(), source.getChecksum());
        dataSourceId = jdbc.queryForObject(
                "select datasource_id from wf_sql_datasource where datasource_key = ?",
                Long.class, dataSourceKey);
        WfSqlDataSource persisted = dataSourceMapper.selectEnabledByKeyForUpdate(dataSourceKey);
        assertThat(persisted).usingRecursiveComparison()
                .ignoringFields("dataSourceId", "status", "checksum", "createBy", "createTime",
                        "updateBy", "updateTime")
                .isEqualTo(source);
        assertThat(WorkflowSqlDataSourceService.dataSourceChecksum(persisted))
                .isEqualTo(persisted.getChecksum());
    }

    /**
     * 精确清理本轮部署、快照、目录和业务行，不影响其他测试数据。
     * @return void，清理后本轮前缀不得残留
     */
    @AfterEach
    void tearDown()
    {
        for (String deploymentId : deploymentIds)
        {
            snapshotMapper.deleteByDeploymentId(deploymentId);
            processEngine.getRepositoryService().deleteDeployment(deploymentId, true);
        }
        jdbc.update("delete from wf_connector_invocation where target_key = ?", dataSourceKey);
        if (dataSourceId != null)
        {
            jdbc.update("delete from wf_sql_datasource where datasource_id = ?", dataSourceId);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from wf_sql_datasource where datasource_key = ?",
                Integer.class, dataSourceKey)).isZero();
    }

    /**
     * 验证 Flowable 正常完成时主库 SQL 与流程事务共同提交。
     * @return void，流程或业务表任一未提交时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void commitsPrimarySqlWithFlowableTransaction() throws Exception
    {
        String processKey = PREFIX + "commit-" + runId;
        Deployment deployment = deploy(processKey, false);
        insertSnapshot(deployment.getId(), processKey);

        var instance = processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("dataSourceKey", dataSourceKey, "resultValue", "committed"));

        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId()).finished().singleResult()).isNotNull();
        assertThat(jdbc.queryForObject(
                "select datasource_name from wf_sql_datasource where datasource_key = ?",
                String.class, dataSourceKey)).isEqualTo("committed");
    }

    /**
     * 验证 SQL 后续 Flowable 节点抛错时，流程实例与同库业务写入一起回滚。
     * @return void，任一侧出现部分提交时测试失败
     * @throws Exception JSON 解析失败
     */
    @Test
    void rollsBackPrimarySqlWhenFollowingFlowableNodeFails() throws Exception
    {
        String processKey = PREFIX + "rollback-" + runId;
        Deployment deployment = deploy(processKey, true);
        insertSnapshot(deployment.getId(), processKey);

        assertThatThrownBy(() -> processEngine.getRuntimeService().startProcessInstanceByKey(
                processKey, Map.of("dataSourceKey", dataSourceKey, "resultValue", "leaked")))
                .hasMessageContaining("FLOWABLE_SQL_ROLLBACK_TEST");

        assertThat(jdbc.queryForObject(
                "select datasource_name from wf_sql_datasource where datasource_key = ?",
                String.class, dataSourceKey)).isEqualTo(ORIGINAL_NAME);
        assertThat(processEngine.getRuntimeService().createProcessInstanceQuery()
                .processDefinitionKey(processKey).count()).isZero();
        assertThat(processEngine.getHistoryService().createHistoricProcessInstanceQuery()
                .processDefinitionKey(processKey).count()).isZero();
    }

    /**
     * 部署含 SQL 扩展节点的最小可执行 BPMN。
     * @param processKey String，本轮唯一流程 key
     * @param failAfterSql boolean，是否在 SQL 后执行故障节点
     * @return Deployment，Flowable 真实部署
     */
    private Deployment deploy(String processKey, boolean failAfterSql)
    {
        String failureTask = failAfterSql
                ? "<serviceTask id=\"failureTask\" flowable:delegateExpression=\"${workflowSqlRollbackDelegate}\"/>"
                        + "<sequenceFlow id=\"f2\" sourceRef=\"sqlTask\" targetRef=\"failureTask\"/>"
                        + "<sequenceFlow id=\"f3\" sourceRef=\"failureTask\" targetRef=\"end\"/>"
                : "<sequenceFlow id=\"f2\" sourceRef=\"sqlTask\" targetRef=\"end\"/>";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"ApprovaPlatIT\">"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><serviceTask id=\"sqlTask\" "
                + "flowable:delegateExpression=\"${workflowExtensionDelegate}\"/>"
                + failureTask + "<endEvent id=\"end\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"sqlTask\"/>"
                + "</process></definitions>";
        Deployment deployment = processEngine.getRepositoryService().createDeployment()
                .name(PREFIX + runId).addBytes(processKey + ".bpmn20.xml",
                        xml.getBytes(StandardCharsets.UTF_8)).deploy();
        deploymentIds.add(deployment.getId());
        return deployment;
    }

    /**
     * 生成并落库与当前 SQL 数据源修订一致的不可变部署快照。
     * @param deploymentId String，Flowable 部署主键
     * @param processKey String，流程 key
     * @return void，快照可由运行时唯一调度器复核
     * @throws Exception JSON 解析失败
     */
    private void insertSnapshot(String deploymentId, String processKey) throws Exception
    {
        String author = JsonMapper.shared().createObjectNode()
                .put("dataSourceKey", dataSourceKey)
                .put("sql", "update wf_sql_datasource set datasource_name = :resultValue "
                        + "where datasource_key = :dataSourceKey")
                .set("parameters", JsonMapper.shared().createObjectNode()
                        .put("dataSourceKey", "dataSourceKey").put("resultValue", "resultValue"))
                .toString();
        String frozen = connector.freezeConfig(JsonMapper.shared().readTree(author), false);
        Map<String, Object> version = jdbc.queryForMap(
                "select v.version_id, v.version_no, v.checksum from wf_bpmn_extension e "
                        + "join wf_bpmn_extension_version v on v.extension_id = e.extension_id "
                        + "where e.extension_key = 'approva.sql-connector' and v.version_no = 1");
        WfDeployExtensionSnapshot snapshot = new WfDeployExtensionSnapshot();
        snapshot.setDeployId(deploymentId);
        snapshot.setProcessKey(processKey);
        snapshot.setElementId("sqlTask");
        snapshot.setExtensionKey("approva.sql-connector");
        snapshot.setExtensionVersionId(((Number) version.get("version_id")).longValue());
        snapshot.setVersionNo(((Number) version.get("version_no")).intValue());
        snapshot.setExtensionType("SQL");
        snapshot.setImplementationKey(WorkflowSqlConnector.IMPLEMENTATION_KEY);
        snapshot.setConfigJson(frozen);
        snapshot.setVersionChecksum(String.valueOf(version.get("checksum")));
        snapshot.setCreateBy("flowable-it");
        snapshot.setSnapshotChecksum(WorkflowExtensionDeploymentService.snapshotChecksum(snapshot));
        assertThat(snapshotMapper.insertBatch(List.of(snapshot))).isEqualTo(1);
    }

    /**
     * 构造与正式目录行完全一致的摘要来源实体。
     * @return WfSqlDataSource，修订 1 的主库目录
     */
    private WfSqlDataSource source()
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceKey(dataSourceKey);
        source.setDataSourceName(ORIGINAL_NAME);
        source.setConnectionType("PRIMARY");
        source.setAllowedTables("wf_sql_datasource");
        source.setConnectTimeoutMs(1000);
        source.setQueryTimeoutSeconds(10);
        source.setRevisionNo(1);
        source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
        return source;
    }

    /**
     * 测试专用故障节点，只用于验证前置 SQL 与 Flowable 命令事务共同回滚。
     */
    @TestConfiguration
    static class TestBeans
    {
        /**
         * 创建始终抛错的受控 JavaDelegate。
         * @return JavaDelegate，执行时抛出稳定故障标识
         */
        @Bean("workflowSqlRollbackDelegate")
        JavaDelegate workflowSqlRollbackDelegate()
        {
            return execution ->
            {
                throw new IllegalStateException("FLOWABLE_SQL_ROLLBACK_TEST");
            };
        }
    }
}
