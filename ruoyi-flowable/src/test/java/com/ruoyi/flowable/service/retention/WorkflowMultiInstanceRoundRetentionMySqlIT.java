package com.ruoyi.flowable.service.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.ruoyi.flowable.config.WorkflowDataRetentionProperties;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;

/**
 * 在显式指定的完整隔离 MySQL 基线上验证多实例轮次生产保留清理器。
 *
 * 测试只写入固定前缀夹具，不创建或修改表结构；未配置专用 URL 时不连接任何数据库。
 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL",
        matches = "jdbc:mysql:.*")
class WorkflowMultiInstanceRoundRetentionMySqlIT
{
    /** 本类唯一拥有的数据前缀，清理 SQL 只允许命中此前缀。 */
    private static final String TEST_PREFIX = "mi-retention-it-";
    /** 生产默认保留天数，本用例同时验证该配置传入真实清理器。 */
    private static final int RETENTION_DAYS = 180;

    private static MysqlDataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private WorkflowDataRetentionCleaner cleaner;

    /**
     * 使用三个显式环境变量连接隔离 MySQL，并只读核验系统、Flowable 与业务基线哨兵表。
     * @return void，数据库连接和完整基线核验成功后保存共享数据源
     * @throws SQLException 连接、版本或基线检查失败时报告
     */
    @BeforeAll
    static void setUpDataSource() throws SQLException
    {
        dataSource = new MysqlDataSource();
        dataSource.setURL(requireEnvironment("WORKFLOW_MYSQL_TEST_URL", false));
        dataSource.setUser(requireEnvironment("WORKFLOW_MYSQL_TEST_USERNAME", false));
        dataSource.setPassword(requireEnvironment("WORKFLOW_MYSQL_TEST_PASSWORD", true));
        verifyInstalledIsolatedBaseline();
    }

    /**
     * 为每个用例创建生产 JdbcTemplate 清理器及基于 @Transactional 的真实 Spring 事务代理。
     * @return void，隔离库存在非本测试轮次时先失败关闭，不运行全表保留清理
     */
    @BeforeEach
    void setUpCleaner()
    {
        jdbcTemplate = new JdbcTemplate(dataSource);
        clearFixtureRows();
        Integer foreignRoundCount = jdbcTemplate.queryForObject(
                "select count(*) from wf_multi_instance_round", Integer.class);
        assertEquals(0, foreignRoundCount,
                "专用保留 IT 库存在非本测试轮次，拒绝运行全局生产清理器");

        WorkflowDataRetentionProperties properties = new WorkflowDataRetentionProperties();
        properties.setBatchSize(10);
        properties.setMultiInstanceRoundRetention(Duration.ofDays(RETENTION_DAYS));
        WorkflowDataRetentionCleaner target = new WorkflowDataRetentionConfiguration()
                .multiInstanceRoundRetentionCleaner(jdbcTemplate, properties);
        cleaner = transactionalProxy(target,
                new DataSourceTransactionManager(dataSource));
    }

    /**
     * 无论断言是否成功，都先删本类轮次再删对应 Flowable 历史，避免留下测试孤儿。
     * @return void，仅清理固定前缀夹具
     */
    @AfterEach
    void tearDownFixtures()
    {
        clearFixtureRows();
    }

    /**
     * 验证旧结束实例的 ACTIVE/RETURNED 轮次全部清除，近期结束和运行实例轮次全部保留。
     * @return void，生产事务代理的候选、删除、边界或孤儿结果不一致时测试失败
     */
    @Test
    void cleansAllRoundsOnlyForExpiredFinishedProcesses()
    {
        LocalDateTime executionTime = LocalDateTime.now().withNano(0);
        LocalDateTime oldEndTime = executionTime.minusDays(RETENTION_DAYS + 1L);
        LocalDateTime recentEndTime = executionTime.minusDays(RETENTION_DAYS - 1L);
        String oldProcessId = processInstanceId("old-finished");
        String recentProcessId = processInstanceId("recent-finished");
        String runningProcessId = processInstanceId("running");

        // 历史结束时间是唯一保留边界；运行夹具故意使用很早的创建时间验证不会按轮次年龄误删。
        insertHistoricProcess(oldProcessId, oldEndTime.minusDays(2), oldEndTime);
        insertHistoricProcess(recentProcessId, recentEndTime.minusDays(2), recentEndTime);
        insertHistoricProcess(runningProcessId, executionTime.minusDays(365), null);
        insertRound(oldProcessId, "old-active", "ACTIVE", oldEndTime.minusDays(2));
        insertRound(oldProcessId, "old-returned", "RETURNED", oldEndTime.minusDays(2));
        insertRound(recentProcessId, "recent-active", "ACTIVE", recentEndTime.minusDays(2));
        insertRound(runningProcessId, "running-returned", "RETURNED",
                executionTime.minusDays(365));
        assertEquals(4, countFixtureRounds());

        WorkflowDataRetentionBatchResult first = cleaner.cleanBatch(executionTime);
        WorkflowDataRetentionBatchResult second = cleaner.cleanBatch(executionTime);

        assertEquals(WorkflowDataRetentionDomain.MULTI_INSTANCE_ROUND, first.domain());
        assertEquals(2, first.scanned());
        assertEquals(2, first.claimed());
        assertEquals(2, first.deleted());
        assertEquals(0, first.failed());
        assertEquals(recentEndTime, first.oldestPendingTime());
        assertEquals(0, second.deleted());
        assertEquals(0, countRounds(oldProcessId));
        assertEquals(1, countRounds(recentProcessId));
        assertEquals(1, countRounds(runningProcessId));
        assertEquals(2, countFixtureRounds());
        assertEquals(3, countFixtureHistories(), "保留清理器不得删除 Flowable 历史");
        assertEquals(0, countOrphanFixtureRounds());
    }

    /**
     * 插入一条满足 Flowable 8 历史表最小非空约束的流程实例。
     * @param processInstanceId String，测试流程实例主键，同时作为历史主键
     * @param startTime LocalDateTime，流程开始时间
     * @param endTime LocalDateTime，可为空；为空表示仍在运行
     * @return void，记录由当前连接自动提交
     */
    private void insertHistoricProcess(String processInstanceId, LocalDateTime startTime,
            LocalDateTime endTime)
    {
        jdbcTemplate.update("insert into ACT_HI_PROCINST "
                + "(ID_,REV_,PROC_INST_ID_,PROC_DEF_ID_,START_TIME_,END_TIME_,TENANT_ID_) "
                + "values (?,1,?,?,?,?, '')",
                processInstanceId, processInstanceId, "retention:1:definition",
                startTime, endTime);
    }

    /**
     * 使用生产领域编码器插入合法 ACTIVE 或 RETURNED 轮次，不手工拼接成员 JSON。
     * @param processInstanceId String，已插入历史的流程实例主键
     * @param activitySuffix String，当前轮次唯一节点后缀
     * @param roundStatus String，ACTIVE 或 RETURNED
     * @param createTime LocalDateTime，轮次创建时间
     * @return void，记录由当前连接自动提交
     */
    private void insertRound(String processInstanceId, String activitySuffix,
            String roundStatus, LocalDateTime createTime)
    {
        boolean returned = "RETURNED".equals(roundStatus);
        LocalDateTime returnTime = returned ? createTime.plusMinutes(1) : null;
        jdbcTemplate.update("insert into wf_multi_instance_round "
                + "(deploy_id,process_definition_id,process_instance_id,activity_id,"
                + "root_execution_id,round_no,mode,members_json,revision_no,round_status,"
                + "return_source_task_id,return_actor_user_id,applicant_task_id,create_time,"
                + "return_time,reopen_time,complete_time) "
                + "values (?,?,?,?,?,1,'ALL',?,0,?,?,?,?,?,?,null,null)",
                "retention-deployment", "retention:1:definition", processInstanceId,
                "activity-" + activitySuffix, uniqueId("root"),
                WfMultiInstanceRound.encodeMembers(List.of("1", "2")), roundStatus,
                returned ? uniqueId("source-task") : null,
                returned ? "1" : null,
                returned ? uniqueId("applicant-task") : null,
                createTime, returnTime);
    }

    /**
     * 查询指定流程实例当前轮次数。
     * @param processInstanceId String，测试流程实例主键
     * @return int，真实 MySQL 当前轮次数
     */
    private int countRounds(String processInstanceId)
    {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from wf_multi_instance_round where process_instance_id=?",
                Integer.class, processInstanceId);
        assertNotNull(count);
        return count;
    }

    /**
     * 查询本类固定前缀下的轮次总数。
     * @return int，真实 MySQL 当前测试轮次数
     */
    private int countFixtureRounds()
    {
        return requiredCount("select count(*) from wf_multi_instance_round "
                + "where process_instance_id like ?");
    }

    /**
     * 查询本类固定前缀下的 Flowable 历史总数。
     * @return int，真实 MySQL 当前测试历史实例数
     */
    private int countFixtureHistories()
    {
        return requiredCount("select count(*) from ACT_HI_PROCINST where PROC_INST_ID_ like ?");
    }

    /**
     * 查询仍引用不存在 Flowable 历史的轮次，作为保留清理后的孤儿门禁。
     * @return int，本类前缀下的孤儿轮次数
     */
    private int countOrphanFixtureRounds()
    {
        return requiredCount("select count(*) from wf_multi_instance_round round_row "
                + "where round_row.process_instance_id like ? and not exists "
                + "(select 1 from ACT_HI_PROCINST history "
                + "where history.PROC_INST_ID_=round_row.process_instance_id)");
    }

    /**
     * 执行带固定实例前缀参数的非空聚合计数。
     * @param sql String，只有一个 LIKE 参数的 count SQL
     * @return int，聚合返回的真实数量
     */
    private int requiredCount(String sql)
    {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, TEST_PREFIX + "%");
        assertNotNull(count);
        return count;
    }

    /**
     * 删除本类固定前缀轮次和历史；父历史必须后删以保持清理顺序明确。
     * @return void，不删除任何其他前缀数据
     */
    private void clearFixtureRows()
    {
        JdbcTemplate cleanup = jdbcTemplate == null ? new JdbcTemplate(dataSource) : jdbcTemplate;
        cleanup.update("delete from wf_multi_instance_round where process_instance_id like ?",
                TEST_PREFIX + "%");
        cleanup.update("delete from ACT_HI_PROCINST where PROC_INST_ID_ like ?",
                TEST_PREFIX + "%");
    }

    /**
     * 为生产对象应用基于 @Transactional 的 CGLIB Spring 事务代理。
     * @param target T，需要代理的生产清理器
     * @param manager DataSourceTransactionManager，共享真实 MySQL 事务管理器
     * @param <T> 生产对象类型
     * @return T，保留生产类型的事务代理
     */
    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(T target, DataSourceTransactionManager manager)
    {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(manager,
                new AnnotationTransactionAttributeSource()));
        return (T) proxyFactory.getProxy();
    }

    /**
     * 生成不超过数据库列长度且带固定清理前缀的流程实例主键。
     * @param suffix String，可读业务场景后缀
     * @return String，当前用例唯一流程实例主键
     */
    private String processInstanceId(String suffix)
    {
        return TEST_PREFIX + suffix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 生成不超过 64 字符的唯一任务或 execution 主键。
     * @param prefix String，主键用途前缀
     * @return String，当前夹具唯一主键
     */
    private String uniqueId(String prefix)
    {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * 只读核验 MySQL 8、非系统 schema 以及完整基线的系统、引擎和业务哨兵表。
     * @return void，环境不属于显式隔离完整基线时拒绝运行
     * @throws SQLException JDBC 元数据查询失败时报告
     */
    private static void verifyInstalledIsolatedBaseline() throws SQLException
    {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            try (ResultSet environment = statement.executeQuery(
                    "select version(),database()"))
            {
                assertTrue(environment.next());
                assertTrue(Integer.parseInt(environment.getString(1).split("\\.")[0]) >= 8,
                        "轮次保留 IT 只允许 MySQL 8+");
                String schema = environment.getString(2);
                assertNotNull(schema, "必须在 URL 中显式指定隔离 schema");
                assertTrue(!List.of("mysql", "information_schema", "performance_schema", "sys")
                        .contains(schema.toLowerCase()), "禁止连接 MySQL 系统 schema");
            }
            try (ResultSet tables = statement.executeQuery(
                    "select count(*) from information_schema.tables "
                    + "where table_schema=database() and lower(table_name) in "
                    + "('sys_config','act_ru_execution','act_hi_procinst','wf_multi_instance_round')"))
            {
                assertTrue(tables.next());
                assertEquals(4, tables.getInt(1), "请先安装当前完整空库基线");
            }
        }
    }

    /**
     * 读取必需 MySQL 验收变量，禁止默认数据库、账号或密码猜测。
     * @param name String，环境变量名
     * @param allowEmpty boolean，是否允许显式空密码
     * @return String，已显式配置的环境变量值
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
