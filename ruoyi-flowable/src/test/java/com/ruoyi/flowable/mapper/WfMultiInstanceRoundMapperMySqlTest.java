package com.ruoyi.flowable.mapper;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ruoyi.flowable.domain.WfMultiInstanceRound;

/**
 * 在显式指定的隔离 MySQL 8 空库基线上执行公共 Mapper 契约和 MySQL 专属门禁。
 *
 * 本类不创建、变更或修复表结构；JSON、CHECK、STORED 生成列、排序规则、InnoDB、
 * 数据库时钟及真实并发均由现有基线和真实 MySQL 执行，不能由 H2 替代。
 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL",
        matches = "jdbc:mysql:.*")
class WfMultiInstanceRoundMapperMySqlTest extends AbstractWfMultiInstanceRoundMapperContractTest
{
    private static MysqlDataSource dataSource;
    private static SqlSessionFactory mySqlSessionFactory;

    /**
     * 使用显式环境变量连接隔离基线库，加载正式 Mapper XML 并验证环境门禁。
     *
     * @return void，当前环境向公共契约提供 MySQL SqlSessionFactory
     * @throws Exception 连接、基线校验或 Mapper XML 加载失败时报告
     */
    @BeforeAll
    static void setUpMySql() throws Exception
    {
        String url = requireEnvironment("WORKFLOW_MYSQL_TEST_URL", false);
        String username = requireEnvironment("WORKFLOW_MYSQL_TEST_USERNAME", false);
        String password = requireEnvironment("WORKFLOW_MYSQL_TEST_PASSWORD", true);
        dataSource = new MysqlDataSource();
        dataSource.setURL(url);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        verifyInstalledBaseline();

        Environment environment = new Environment("round-mysql",
                new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        String mapperResource = "mapper/flowable/WfMultiInstanceRoundMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(mapperResource))
        {
            new XMLMapperBuilder(input, configuration, mapperResource,
                    configuration.getSqlFragments()).parse();
        }
        mySqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 每个场景前精确清除公共契约固定前缀的数据。
     *
     * @return void，不影响隔离库中的其他记录
     * @throws SQLException 清理失败时向 JUnit 报告
     */
    @BeforeEach
    void clearBefore() throws SQLException
    {
        clearTestRows();
    }

    /**
     * 每个场景后再次清理，即使负例中途失败也不遗留测试数据。
     *
     * @return void，不遗留 MySQL 验收记录
     * @throws SQLException 清理失败时向 JUnit 报告
     */
    @AfterEach
    void clearAfter() throws SQLException
    {
        clearTestRows();
    }

    /**
     * 返回已加载正式 Mapper XML 的 MySQL 会话工厂。
     *
     * @return SqlSessionFactory，当前 MySQL 契约环境的会话工厂
     */
    @Override
    protected SqlSessionFactory sessionFactory()
    {
        return mySqlSessionFactory;
    }

    /**
     * 返回 MySQL DATETIME(3) 可稳定表示的当前测试时间。
     *
     * @return LocalDateTime，去除纳秒后的 JVM 当前时间
     */
    @Override
    protected LocalDateTime stableTime()
    {
        return LocalDateTime.now().withNano(0);
    }

    /**
     * 验证真实表继续使用 InnoDB、STORED 生成列和大小写敏感 ASCII 排序规则。
     *
     * @return void，断言开放轮次唯一键所依赖的 MySQL 物理属性没有漂移
     * @throws SQLException information_schema 查询失败时向 JUnit 报告
     */
    @Test
    void shouldRetainMySqlPhysicalSchemaCapabilities() throws SQLException
    {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            try (ResultSet table = statement.executeQuery(
                    "select engine from information_schema.tables "
                    + "where table_schema=database() and table_name='wf_multi_instance_round'"))
            {
                assertTrue(table.next());
                assertEquals("InnoDB", table.getString("engine"));
            }
            try (ResultSet columns = statement.executeQuery(
                    "select column_name, collation_name, extra from information_schema.columns "
                    + "where table_schema=database() and table_name='wf_multi_instance_round' "
                    + "and column_name in ('open_process_instance_id','open_activity_id') "
                    + "order by column_name"))
            {
                int generatedColumnCount = 0;
                while (columns.next())
                {
                    assertEquals("ascii_bin", columns.getString("collation_name"));
                    assertTrue(columns.getString("extra").toUpperCase().contains("STORED GENERATED"));
                    generatedColumnCount++;
                }
                assertEquals(2, generatedColumnCount);
            }
        }
    }

    /**
     * 验证 MySQL JSON Schema 对数组类型、数量、唯一性和规范 Long 用户主键的约束。
     *
     * @return void，断言合法上界可写，损坏 JSON、空/超长/重复数组和非法主键均被拒绝
     * @throws Exception JSON 编码失败时报告
     */
    @Test
    void shouldEnforceMySqlMemberJsonChecks() throws Exception
    {
        RawRound maximumUserId = rawActive("json-max-user-id");
        maximumUserId.membersJson = json(List.of("9223372036854775807"));
        insertRaw(maximumUserId);

        RawRound malformed = rawActive("json-malformed");
        malformed.membersJson = "not-json";
        assertRawInsertRejected(malformed);

        RawRound object = rawActive("json-object");
        object.membersJson = json(Map.of("userId", "1"));
        assertRawInsertRejected(object);

        RawRound empty = rawActive("json-empty");
        empty.membersJson = json(List.of());
        assertRawInsertRejected(empty);

        RawRound duplicate = rawActive("json-duplicate");
        duplicate.membersJson = json(List.of("1", "1"));
        assertRawInsertRejected(duplicate);

        List<String> tooManyMembers = new ArrayList<>();
        for (int index = 1; index <= 101; index++)
        {
            tooManyMembers.add(Integer.toString(index));
        }
        RawRound tooMany = rawActive("json-too-many");
        tooMany.membersJson = json(tooManyMembers);
        assertRawInsertRejected(tooMany);

        for (String invalidUserId : List.of("0", "01", "9223372036854775808"))
        {
            RawRound invalidMember = rawActive("json-invalid-" + invalidUserId);
            invalidMember.membersJson = json(List.of(invalidUserId));
            assertRawInsertRejected(invalidMember);
        }
    }

    /**
     * 验证模式、轮次号、修订号、状态和退回关联字段的 MySQL CHECK。
     *
     * @return void，断言非法枚举、越界数值和不完整状态组合全部被拒绝
     */
    @Test
    void shouldEnforceMySqlModeRevisionAndStatusChecks()
    {
        RawRound invalidMode = rawActive("mode");
        invalidMode.mode = "SER";
        assertRawInsertRejected(invalidMode);
        RawRound invalidRoundNo = rawActive("round-no");
        invalidRoundNo.roundNo = 0;
        assertRawInsertRejected(invalidRoundNo);
        RawRound negativeRevision = rawActive("revision-negative");
        negativeRevision.revisionNo = -1;
        assertRawInsertRejected(negativeRevision);
        RawRound overflowRevision = rawActive("revision-overflow");
        overflowRevision.revisionNo = (long) Integer.MAX_VALUE + 1;
        assertRawInsertRejected(overflowRevision);
        RawRound invalidStatus = rawActive("status");
        invalidStatus.roundStatus = "CANCELLED";
        assertRawInsertRejected(invalidStatus);
        RawRound incompleteReturn = rawActive("return-incomplete");
        incompleteReturn.roundStatus = "RETURNED";
        incompleteReturn.returnTime = incompleteReturn.createTime.plusSeconds(1);
        assertRawInsertRejected(incompleteReturn);
        RawRound overflowReturnActor = rawReturned("return-actor");
        overflowReturnActor.returnActorUserId = "9223372036854775808";
        assertRawInsertRejected(overflowReturnActor);
    }

    /**
     * 验证各轮次状态的生命周期时间组合和先后顺序 CHECK。
     *
     * @return void，断言提前完成、ACTIVE 夹带完成时间和终止/重开时间倒置均被拒绝
     */
    @Test
    void shouldEnforceMySqlLifecycleTimeChecks()
    {
        RawRound activeWithCompletion = rawActive("active-complete-time");
        activeWithCompletion.completeTime = activeWithCompletion.createTime.plusSeconds(1);
        assertRawInsertRejected(activeWithCompletion);

        RawRound completedBeforeCreate = rawActive("completed-before-create");
        completedBeforeCreate.roundStatus = "COMPLETED";
        completedBeforeCreate.completeTime = completedBeforeCreate.createTime.minusSeconds(1);
        assertRawInsertRejected(completedBeforeCreate);

        RawRound reopenedBeforeReturn = rawReturned("reopened-before-return");
        reopenedBeforeReturn.roundStatus = "REOPENED";
        reopenedBeforeReturn.reopenTime = reopenedBeforeReturn.returnTime.minusSeconds(1);
        assertRawInsertRejected(reopenedBeforeReturn);

        RawRound terminatedWithoutTime = rawActive("terminated-without-time");
        terminatedWithoutTime.roundStatus = "TERMINATED";
        assertRawInsertRejected(terminatedWithoutTime);
        RawRound terminatedBeforeCreate = rawActive("terminated-before-create");
        terminatedBeforeCreate.roundStatus = "TERMINATED";
        terminatedBeforeCreate.terminateTime = terminatedBeforeCreate.createTime.minusSeconds(1);
        assertRawInsertRejected(terminatedBeforeCreate);
        RawRound terminatedBeforeReturn = rawReturned("terminated-before-return");
        terminatedBeforeReturn.roundStatus = "TERMINATED";
        terminatedBeforeReturn.terminateTime = terminatedBeforeReturn.returnTime.minusSeconds(1);
        assertRawInsertRejected(terminatedBeforeReturn);
    }

    /**
     * 验证当前连接是 MySQL 8、目标表存在且已安装完整 CHECK 集合。
     *
     * @return void，只读校验环境，不创建或修改表
     * @throws SQLException 连接或 information_schema 查询失败时报告
     */
    private static void verifyInstalledBaseline() throws SQLException
    {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            try (ResultSet version = statement.executeQuery("select version()"))
            {
                assertTrue(version.next() && Integer.parseInt(
                        version.getString(1).split("\\.")[0]) >= 8,
                        "真实 Mapper IT 只允许 MySQL 8+");
            }
            try (ResultSet table = statement.executeQuery(
                    "select count(*) from information_schema.tables "
                    + "where table_schema=database() and table_name='wf_multi_instance_round'"))
            {
                assertTrue(table.next());
                assertEquals(1, table.getInt(1), "请先安装当前空库基线");
            }
            try (ResultSet checks = statement.executeQuery(
                    "select count(*) from information_schema.table_constraints "
                    + "where table_schema=database() and table_name='wf_multi_instance_round' "
                    + "and constraint_type='CHECK'"))
            {
                assertTrue(checks.next());
                assertTrue(checks.getInt(1) >= 9, "多实例轮次 CHECK 约束不完整");
            }
        }
    }

    /**
     * 读取必需的真实 MySQL 验收环境变量，禁止默认账号或密码猜测。
     *
     * @param name String，环境变量名
     * @param allowEmpty boolean，是否允许显式配置空密码
     * @return String，已明确配置的环境变量值
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

    /**
     * 断言原始负例记录被 MySQL 类型或 CHECK 约束拒绝。
     *
     * @param round RawRound，仅某个受控字段不合法的负例记录
     * @return void，断言 JDBC 写入抛出 SQLException
     */
    private void assertRawInsertRejected(RawRound round)
    {
        assertThrows(SQLException.class, () -> insertRaw(round));
    }

    /**
     * 用 JDBC 执行一次负例写入，确保真实命中 MySQL 类型和 CHECK 门禁。
     *
     * @param round RawRound，准备由 MySQL 验证的原始字段
     * @return void，写入成功时正常返回
     * @throws SQLException 列类型、唯一键或 CHECK 拒绝时报告
     */
    private void insertRaw(RawRound round) throws SQLException
    {
        String sql = """
                insert into wf_multi_instance_round
                (deploy_id, process_definition_id, process_instance_id, activity_id,
                 root_execution_id, round_no, mode, members_json, revision_no, round_status,
                 return_source_task_id, return_actor_user_id, applicant_task_id, create_time,
                 return_time, reopen_time, complete_time, terminate_time)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, round.deployId);
            statement.setString(2, round.processDefinitionId);
            statement.setString(3, round.processInstanceId);
            statement.setString(4, round.activityId);
            statement.setString(5, round.rootExecutionId);
            statement.setInt(6, round.roundNo);
            statement.setString(7, round.mode);
            statement.setString(8, round.membersJson);
            statement.setLong(9, round.revisionNo);
            statement.setString(10, round.roundStatus);
            statement.setString(11, round.returnSourceTaskId);
            statement.setString(12, round.returnActorUserId);
            statement.setString(13, round.applicantTaskId);
            statement.setObject(14, round.createTime);
            statement.setObject(15, round.returnTime);
            statement.setObject(16, round.reopenTime);
            statement.setObject(17, round.completeTime);
            statement.setObject(18, round.terminateTime);
            statement.executeUpdate();
        }
    }

    /**
     * 构造可被单字段改写为数据库负例的原始 ACTIVE 记录。
     *
     * @param suffix String，用于隔离当前负例的实例名后缀
     * @return RawRound，默认可通过所有 MySQL 约束的原始记录
     */
    private RawRound rawActive(String suffix)
    {
        return RawRound.from(activeRound(
                instanceId(suffix), "activity-raw", rootId(), 1));
    }

    /**
     * 构造可被单字段改写为数据库负例的原始 RETURNED 记录。
     *
     * @param suffix String，用于隔离当前负例的实例名后缀
     * @return RawRound，默认可通过所有退回约束的原始记录
     */
    private RawRound rawReturned(String suffix)
    {
        return RawRound.from(returnedRound(
                instanceId(suffix), "activity-return", rootId(), 1));
    }

    /**
     * 使用 Jackson 编码 MySQL JSON 负例，避免手工拼接结构化快照。
     *
     * @param value Object，需要提交给 MySQL 的结构化值
     * @return String，Jackson 产生的 JSON
     * @throws Exception JSON 编码失败时报告
     */
    private String json(Object value) throws Exception
    {
        return JsonMapper.shared().writeValueAsString(value);
    }

    /**
     * 删除仅属于公共契约固定实例前缀的 MySQL 测试数据。
     *
     * @return void，不影响其他流程实例轮次
     * @throws SQLException 清理 SQL 失败时报告
     */
    private void clearTestRows() throws SQLException
    {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "delete from wf_multi_instance_round where process_instance_id like ?"))
        {
            statement.setString(1, CONTRACT_INSTANCE_PREFIX + "%");
            statement.executeUpdate();
        }
    }

    /**
     * 绕过 Java 领域门禁、专门验证真实 MySQL 类型和 CHECK 的原始轮次字段。
     */
    private static final class RawRound
    {
        private String deployId;
        private String processDefinitionId;
        private String processInstanceId;
        private String activityId;
        private String rootExecutionId;
        private int roundNo;
        private String mode;
        private String membersJson;
        private long revisionNo;
        private String roundStatus;
        private String returnSourceTaskId;
        private String returnActorUserId;
        private String applicantTaskId;
        private LocalDateTime createTime;
        private LocalDateTime returnTime;
        private LocalDateTime reopenTime;
        private LocalDateTime completeTime;
        private LocalDateTime terminateTime;

        /**
         * 从已通过领域校验的轮次复制全部持久化字段。
         *
         * @param source WfMultiInstanceRound，合法基准轮次
         * @return RawRound，可仅改写一个字段的数据库负例
         */
        private static RawRound from(WfMultiInstanceRound source)
        {
            RawRound target = new RawRound();
            target.deployId = source.getDeployId();
            target.processDefinitionId = source.getProcessDefinitionId();
            target.processInstanceId = source.getProcessInstanceId();
            target.activityId = source.getActivityId();
            target.rootExecutionId = source.getRootExecutionId();
            target.roundNo = source.getRoundNo();
            target.mode = source.getMode();
            target.membersJson = source.getMembersJson();
            target.revisionNo = source.getRevisionNo();
            target.roundStatus = source.getRoundStatus().name();
            target.returnSourceTaskId = source.getReturnSourceTaskId();
            target.returnActorUserId = source.getReturnActorUserId();
            target.applicantTaskId = source.getApplicantTaskId();
            target.createTime = source.getCreateTime();
            target.returnTime = source.getReturnTime();
            target.reopenTime = source.getReopenTime();
            target.completeTime = source.getCompleteTime();
            target.terminateTime = source.getTerminateTime();
            return target;
        }
    }
}
