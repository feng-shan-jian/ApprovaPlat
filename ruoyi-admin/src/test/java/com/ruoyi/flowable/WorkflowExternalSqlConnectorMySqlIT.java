package com.ruoyi.flowable;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.repository.Deployment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.RuoYiApplication;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployExtensionSnapshot;
import com.ruoyi.flowable.domain.WfSqlDataSource;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorInvocationClaim;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.extension.WorkflowSqlConnector;
import com.ruoyi.flowable.extension.WorkflowSqlSecretResolver;
import com.ruoyi.flowable.mapper.WfConnectorInvocationMapper;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifactRepository;
import com.ruoyi.flowable.service.model.WorkflowDeploymentArtifacts;
import com.ruoyi.flowable.service.model.WorkflowExtensionDeploymentService;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;
import com.ruoyi.flowable.service.process.WorkflowConnectorInvocationService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 使用两个真实 MySQL schema 和 Flowable executor 验证外库 SQL 至少一次与幂等对账。
 */
@SpringBootTest(classes = {RuoYiApplication.class,
        WorkflowExternalSqlConnectorMySqlIT.TestBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "spring.datasource.druid.master.url=${FLOWABLE_IT_JDBC_URL}",
            "spring.datasource.druid.master.username=${FLOWABLE_IT_USERNAME}",
            "spring.datasource.druid.master.password=${FLOWABLE_IT_PASSWORD}",
            "workflow.test.external.jdbc-url=${FLOWABLE_RBAC_JDBC_URL}",
            "workflow.test.external.username=${FLOWABLE_RBAC_DB_USERNAME}",
            "workflow.test.external.password=${FLOWABLE_RBAC_DB_PASSWORD}",
            "spring.data.redis.database=${FLOWABLE_IT_REDIS_DATABASE:15}",
            "token.secret=d29ya2Zsb3ctleHRlcm5hbC1zcWwtaXQtdG9rZW4tc2VjcmV0LXdvcmtmbG93LWV4dGVybmFsLXNxbC1pdC10b2tlbi1zZWNyZXQtd29ya2Zsb3ctZXh0ZXJuYWwtc3FsLWl0LXRva2VuLXNlY3JldA==",
            "flowable.database-schema-update=false",
            "flowable.async-executor-activate=true",
            "flowable.async-history-executor-activate=false",
            "spring.quartz.auto-startup=false",
            "spring.task.scheduling.enabled=false"
        })
class WorkflowExternalSqlConnectorMySqlIT
{
    /** 外库测试数据统一前缀，仅清理本轮目标和主库台账。 */
    private static final String PREFIX = "workflow-external-sql-it-";
    /** executor 三次重试和正常轮询允许的最长等待时间。 */
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(40);

    @Autowired
    private JdbcTemplate primaryJdbc;
    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private WorkflowSqlConnector connector;
    @Autowired
    private WorkflowDeploymentArtifactRepository artifactRepository;
    @Autowired
    private FaultInjectingInvocationService invocationService;
    @Autowired
    private ExternalSqlTestSettings externalSettings;

    /** 本轮唯一标识，避免并行或失败重跑共享幂等记录。 */
    private final String runId = UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate externalJdbc;
    private String dataSourceKey;
    private Long dataSourceId;
    private Deployment deployment;

    /**
     * 建立真实外库独立连接并登记只含环境引用的数据源目录。
     * @return void，任一 schema 或正式目录不可用时立即失败
     */
    @BeforeEach
    void setUp()
    {
        dataSourceKey = PREFIX + runId;
        externalJdbc = new JdbcTemplate(externalSettings.dataSource());
        assertThat(primaryJdbc.queryForObject("select database()", String.class))
                .isNotEqualTo(externalJdbc.queryForObject("select database()", String.class));
        invocationService.resetFailure();

        WfSqlDataSource source = externalSource();
        primaryJdbc.update("insert into wf_sql_datasource "
                        + "(datasource_key, datasource_name, connection_type, jdbc_url_ref, "
                        + "username_ref, password_ref, allowed_tables, connect_timeout_ms, "
                        + "query_timeout_seconds, revision_no, status, checksum, create_by, "
                        + "create_time, update_by, update_time) values (?, ?, 'EXTERNAL', "
                        + "'WORKFLOW_SQL_JDBC_URL_IT', 'WORKFLOW_SQL_USERNAME_IT', "
                        + "'WORKFLOW_SQL_PASSWORD_IT', 'wf_connector_invocation', 1000, 10, "
                        + "1, 'ENABLED', ?, 'flowable-it', current_timestamp(3), '', null)",
                dataSourceKey, "外库 SQL 至少一次测试", source.getChecksum());
        dataSourceId = primaryJdbc.queryForObject(
                "select datasource_id from wf_sql_datasource where datasource_key = ?",
                Long.class, dataSourceKey);
        assertThat(dataSourceId).isPositive();
    }

    /**
     * 先删除外库副作用，再按主库外键顺序清理台账、快照、部署和目录。
     * @return void，任一侧残留本轮记录时测试失败
     */
    @AfterEach
    void tearDown()
    {
        externalJdbc.update("delete from wf_connector_invocation where target_key = ?",
                dataSourceKey);
        primaryJdbc.update("delete from wf_connector_invocation where target_key = ?",
                dataSourceKey);
        if (deployment != null)
        {
            artifactRepository.delete(deployment.getId());
            if (processEngine.getRepositoryService().createDeploymentQuery()
                    .deploymentId(deployment.getId()).count() == 1)
            {
                processEngine.getRepositoryService().deleteDeployment(deployment.getId(), true);
            }
        }
        if (dataSourceId != null)
        {
            primaryJdbc.update("delete from wf_sql_datasource where datasource_id = ?",
                    dataSourceId);
        }
        assertThat(primaryJdbc.queryForObject(
                "select count(*) from wf_sql_datasource where datasource_key = ?",
                Integer.class, dataSourceKey)).isZero();
        assertThat(externalJdbc.queryForObject(
                "select count(*) from wf_connector_invocation where target_key = ?",
                Integer.class, dataSourceKey)).isZero();
    }

    /**
     * 验证外库写入成功后本地提交故障会触发重试，且稳定幂等键只产生一个外部副作用。
     * @return void，重试、流程终态或跨库对账不一致时测试失败
     * @throws Exception 部署快照 JSON 处理失败时抛出
     */
    @Test
    void retriesAfterLocalCommitFailureWithoutDuplicatingExternalEffect() throws Exception
    {
        String processKey = PREFIX + "process-" + runId;
        deployment = deployAsyncProcess(processKey);
        insertExternalSnapshot(deployment.getId(), processKey);

        var instance = processEngine.getRuntimeService().startProcessInstanceByKey(processKey,
                Map.of("deploymentMarker", "external-deployment-" + runId,
                        "processMarker", "external-process-" + runId,
                        "executionMarker", "external-execution-" + runId,
                        "elementMarker", "external-element-" + runId,
                        "targetKey", dataSourceKey));
        String processInstanceId = instance.getId();

        awaitCondition("外库 SQL 应在本地提交故障后由 executor 重试并结束流程",
                EXECUTION_TIMEOUT, () -> processEngine.getHistoryService()
                        .createHistoricProcessInstanceQuery().processInstanceId(processInstanceId)
                        .finished().count() == 1);

        Map<String, Object> primary = primaryJdbc.queryForMap(
                "select idempotency_key, status, attempt_count, error_code, claim_token, "
                        + "lease_expires_at from wf_connector_invocation where target_key = ?",
                dataSourceKey);
        Map<String, Object> external = externalJdbc.queryForMap(
                "select idempotency_key, status, attempt_count, target_summary "
                        + "from wf_connector_invocation where target_key = ?", dataSourceKey);
        assertThat(primary).containsEntry("status", "SUCCESS")
                .containsEntry("attempt_count", 2)
                .containsEntry("error_code", null)
                .containsEntry("claim_token", null)
                .containsEntry("lease_expires_at", null);
        assertThat(external).containsEntry("status", "SUCCESS")
                .containsEntry("attempt_count", 1)
                .containsEntry("target_summary", "external-idempotent-effect");
        assertThat(external.get("idempotency_key"))
                .isEqualTo(primary.get("idempotency_key"));
        assertThat(String.valueOf(primary.get("idempotency_key"))).matches("[0-9a-f]{64}");
        assertThat(externalJdbc.queryForObject(
                "select count(*) from wf_connector_invocation where target_key = ?",
                Integer.class, dataSourceKey)).isEqualTo(1);
        assertThat(invocationService.failureInjected()).isTrue();
        assertThat(processEngine.getManagementService().createJobQuery()
                .processInstanceId(processInstanceId).count()).isZero();
        assertThat(processEngine.getManagementService().createDeadLetterJobQuery()
                .processInstanceId(processInstanceId).count()).isZero();
    }

    /**
     * 部署开启 asyncBefore 和一秒重试周期的 SQL ServiceTask。
     * @param processKey String，本轮唯一流程 key
     * @return Deployment，Flowable 正式部署
     */
    private Deployment deployAsyncProcess(String processKey)
    {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" "
                + "xmlns:flowable=\"http://flowable.org/bpmn\" targetNamespace=\"ApprovaPlatIT\">"
                + "<process id=\"" + processKey + "\" isExecutable=\"true\">"
                + "<startEvent id=\"start\"/><serviceTask id=\"sqlTask\" flowable:async=\"true\" "
                + "flowable:delegateExpression=\"${workflowExtensionDelegate}\"><extensionElements>"
                + "<flowable:failedJobRetryTimeCycle>R3/PT1S</flowable:failedJobRetryTimeCycle>"
                + "</extensionElements></serviceTask><endEvent id=\"end\"/>"
                + "<sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"sqlTask\"/>"
                + "<sequenceFlow id=\"f2\" sourceRef=\"sqlTask\" targetRef=\"end\"/>"
                + "</process></definitions>";
        return processEngine.getRepositoryService().createDeployment()
                .name(PREFIX + runId).addBytes(processKey + ".bpmn20.xml",
                        xml.getBytes(StandardCharsets.UTF_8)).deploy();
    }

    /**
     * 冻结外库数据源修订、幂等 SQL 和扩展版本并写入正式部署快照。
     * @param deploymentId String，Flowable 部署主键
     * @param processKey String，流程定义 key
     * @return void，运行时可按部署、流程和元素唯一读取快照
     * @throws Exception JSON 解析失败时抛出
     */
    private void insertExternalSnapshot(String deploymentId, String processKey)
            throws Exception
    {
        String sql = "insert into wf_connector_invocation "
                + "(deployment_id, process_instance_id, execution_id, element_id, connector_type, "
                + "target_key, target_revision, idempotency_key, operation, target_summary, "
                + "status, attempt_count, create_time, update_time) values "
                + "(:deploymentMarker, :processMarker, :executionMarker, :elementMarker, 'SQL', "
                + ":targetKey, 1, :idempotencyKey, 'INSERT', 'external-idempotent-effect', "
                + "'SUCCESS', 1, current_timestamp(3), current_timestamp(3)) "
                + "on duplicate key update target_summary = 'external-idempotent-effect'";
        var parameters = JsonMapper.shared().createObjectNode()
                .put("deploymentMarker", "deploymentMarker")
                .put("processMarker", "processMarker")
                .put("executionMarker", "executionMarker")
                .put("elementMarker", "elementMarker")
                .put("targetKey", "targetKey");
        String author = JsonMapper.shared().createObjectNode()
                .put("dataSourceKey", dataSourceKey).put("sql", sql)
                .set("parameters", parameters).put("maxRows", 1).toString();
        String frozen = connector.freezeConfig(JsonMapper.shared().readTree(author), true);
        Map<String, Object> version = primaryJdbc.queryForMap(
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
        artifactRepository.persist(deploymentId, new WorkflowDeploymentArtifacts(
                List.of(), List.of(), List.of(), List.of(), List.of(snapshot),
                List.of(), List.of(), List.of()));
    }

    /**
     * 构造与正式目录行一致且不含凭据正文的外库摘要来源实体。
     * @return WfSqlDataSource，修订 1 外库配置
     */
    private WfSqlDataSource externalSource()
    {
        WfSqlDataSource source = new WfSqlDataSource();
        source.setDataSourceKey(dataSourceKey);
        source.setDataSourceName("外库 SQL 至少一次测试");
        source.setConnectionType("EXTERNAL");
        source.setJdbcUrlRef("WORKFLOW_SQL_JDBC_URL_IT");
        source.setUsernameRef("WORKFLOW_SQL_USERNAME_IT");
        source.setPasswordRef("WORKFLOW_SQL_PASSWORD_IT");
        source.setAllowedTables("wf_connector_invocation");
        source.setConnectTimeoutMs(1000);
        source.setQueryTimeoutSeconds(10);
        source.setRevisionNo(1);
        source.setChecksum(WorkflowSqlDataSourceService.dataSourceChecksum(source));
        return source;
    }

    /**
     * 轮询真实异步结果，禁止通过手工 executeJob 伪造 executor 成功。
     * @param description String，超时断言说明
     * @param timeout Duration，最大等待时间
     * @param condition BooleanSupplier，完成条件
     * @return void，超时前条件必须为 true
     * @throws InterruptedException 测试线程中断时恢复标志并抛出
     */
    private void awaitCondition(String description, Duration timeout,
            BooleanSupplier condition) throws InterruptedException
    {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline))
        {
            if (condition.getAsBoolean())
            {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    /**
     * 测试专用真实外库连接、受控引用解析和本地提交故障注入 Bean。
     */
    @TestConfiguration
    static class TestBeans
    {
        /**
         * 创建不向业务代码暴露正文的外库测试设置。
         * @param jdbcUrl String，第二个真实 MySQL schema JDBC URL
         * @param username String，第二个 schema 最小权限账号
         * @param password String，仅保存在 Spring 测试环境的密码
         * @return ExternalSqlTestSettings，测试连接和受控 resolver 共用设置
         */
        @Bean
        ExternalSqlTestSettings externalSqlTestSettings(
                @Value("${workflow.test.external.jdbc-url}") String jdbcUrl,
                @Value("${workflow.test.external.username}") String username,
                @Value("${workflow.test.external.password}") String password)
        {
            return new ExternalSqlTestSettings(jdbcUrl, username, password);
        }

        /**
         * 用测试环境属性实现受控引用解析，数据库和部署快照仍只保存引用名。
         * @param settings ExternalSqlTestSettings，真实外库测试连接设置
         * @return WorkflowSqlSecretResolver，测试上下文首选 resolver
         */
        @Bean
        @Primary
        WorkflowSqlSecretResolver workflowSqlSecretResolver(ExternalSqlTestSettings settings)
        {
            return new TestSqlSecretResolver(settings);
        }

        /**
         * 创建首轮成功落外库后注入本地台账提交失败的调用服务。
         * @param mapper WfConnectorInvocationMapper，正式主库幂等台账 Mapper
         * @return FaultInjectingInvocationService，测试上下文首选调用服务
         */
        @Bean
        @Primary
        FaultInjectingInvocationService faultInjectingInvocationService(
                WfConnectorInvocationMapper mapper)
        {
            return new FaultInjectingInvocationService(mapper);
        }
    }

    /**
     * 仅供测试上下文持有第二个 schema 凭据，任何正文都不写入快照、台账或报告。
     * @param jdbcUrl String，外库 JDBC URL
     * @param username String，外库用户名
     * @param password String，外库密码
     */
    record ExternalSqlTestSettings(String jdbcUrl, String username, String password)
    {
        /**
         * 每次创建独立 DriverManagerDataSource，保证不参加主库 Spring 事务。
         * @return DataSource，指向第二个真实 MySQL schema
         */
        DataSource dataSource()
        {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setUrl(jdbcUrl);
            dataSource.setUsername(username);
            dataSource.setPassword(password);
            return dataSource;
        }
    }

    /**
     * 将三个固定引用映射到测试环境属性，不改变生产 resolver 的环境变量策略。
     */
    static class TestSqlSecretResolver extends WorkflowSqlSecretResolver
    {
        private final ExternalSqlTestSettings settings;

        /**
         * 创建测试引用解析器。
         * @param settings ExternalSqlTestSettings，第二个真实 schema 设置
         * @return 无返回值，构造后供连接器按引用读取
         */
        TestSqlSecretResolver(ExternalSqlTestSettings settings)
        {
            this.settings = settings;
        }

        /** @param reference String，必须为测试固定 URL 引用；@return String，外库 JDBC URL。 */
        @Override
        public String requireJdbcUrl(String reference)
        {
            assertThat(reference).isEqualTo("WORKFLOW_SQL_JDBC_URL_IT");
            return settings.jdbcUrl();
        }

        /** @param reference String，必须为测试固定用户名引用；@return String，外库用户名。 */
        @Override
        public String requireUsername(String reference)
        {
            assertThat(reference).isEqualTo("WORKFLOW_SQL_USERNAME_IT");
            return settings.username();
        }

        /** @param reference String，必须为测试固定密码引用；@return String，外库密码。 */
        @Override
        public String requirePassword(String reference)
        {
            assertThat(reference).isEqualTo("WORKFLOW_SQL_PASSWORD_IT");
            return settings.password();
        }
    }

    /**
     * 首轮把本地台账标为失败并抛错，第二轮恢复正式成功提交，模拟外部提交后的本地故障。
     */
    static class FaultInjectingInvocationService extends WorkflowConnectorInvocationService
    {
        private final AtomicBoolean injectNextSuccessFailure = new AtomicBoolean(true);
        private final AtomicBoolean failureInjected = new AtomicBoolean();

        /**
         * 创建故障注入服务。
         * @param mapper WfConnectorInvocationMapper，正式主库 Mapper
         * @return 无返回值，构造后首轮 success 会失败
         */
        FaultInjectingInvocationService(WfConnectorInvocationMapper mapper)
        {
            super(mapper);
        }

        /**
         * 首次完成时释放租约并抛出稳定故障，后续调用执行正式成功提交。
         * @param claim WorkflowConnectorInvocationClaim，本次调用租约
         * @param durationMs long，外库调用耗时
         * @param resultCode int，连接器结果码
         * @param summary String，脱敏结果摘要
         * @return void，首次必抛错，重试后正常返回
         */
        @Override
        public void success(WorkflowConnectorInvocationClaim claim, long durationMs,
                int resultCode, String summary)
        {
            if (injectNextSuccessFailure.compareAndSet(true, false))
            {
                failureInjected.set(true);
                super.failure(claim, durationMs, null, "TEST_LOCAL_COMMIT_FAILURE",
                        "test-local-commit-failed");
                throw new ServiceException("TEST_LOCAL_COMMIT_FAILURE", HttpStatus.ERROR);
            }
            super.success(claim, durationMs, resultCode, summary);
        }

        /**
         * 为每个测试恢复一次性故障开关。
         * @return void，下一次 success 将注入失败
         */
        void resetFailure()
        {
            injectNextSuccessFailure.set(true);
            failureInjected.set(false);
        }

        /**
         * 查询本轮是否真实经过故障分支。
         * @return boolean，首轮成功提交已被故障替换时为 true
         */
        boolean failureInjected()
        {
            return failureInjected.get();
        }
    }
}
