package com.ruoyi.flowable.mapper;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 在显式指定的隔离 MySQL 8 空库基线上验收多实例轮次 Mapper 和数据库约束。
 *
 * 本类不创建、变更或自动修复表结构；运行前必须已按当前空库基线安装
 * {@code wf_multi_instance_round}。
 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL",
        matches = "jdbc:mysql:.*")
class WfMultiInstanceRoundMapperMySqlTest
{
    private static final String TEST_INSTANCE_PREFIX = "mi-round-it-";
    private static MysqlDataSource dataSource;
    private static SqlSessionFactory sessionFactory;
    private static ExecutorService executor;

    /**
     * 使用显式环境变量连接隔离基线库，加载生产 Mapper XML 并验证真实表结构。
     *
     * @return void，测试类共享一个 MySQL 数据源和 SqlSessionFactory
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
        sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        executor = Executors.newFixedThreadPool(2);
    }

    /**
     * 停止真实 InnoDB 并发 CAS 用例的固定线程池。
     *
     * @return void，不保留测试线程
     */
    @AfterAll
    static void tearDownMySql()
    {
        if (executor != null)
        {
            executor.shutdownNow();
        }
    }

    /**
     * 每个用例前清除仅属于本测试前缀的轮次记录。
     *
     * @return void，不影响基线库中其他数据
     * @throws SQLException 清理失败时报告
     */
    @BeforeEach
    void clearBefore() throws SQLException
    {
        clearTestRows();
    }

    /**
     * 每个用例后精确清除本测试前缀数据，包括失败用例已写入的记录。
     *
     * @return void，不遗留临时验收数据
     * @throws SQLException 清理失败时报告
     */
    @AfterEach
    void clearAfter() throws SQLException
    {
        clearTestRows();
    }

    /**
     * 验证正式 Mapper 的插入、数据库时钟、查询、CAS 和批量历史删除。
     *
     * @return void，断言 JVM 时钟领先时仍可快速完成，并覆盖 CRUD/CAS 与历史删除闭环
     */
    @Test
    void shouldRunMapperCrudCasHistoryDeletionAndDatabaseClock()
    {
        String processA = instanceId("crud-a");
        String processB = instanceId("crud-b");
        String processC = instanceId("terminate-active");
        WfMultiInstanceRound active = activeRound(processA, "activity-a", rootId(), 1);
        active.setCreateTime(LocalDateTime.now().plusDays(1));
        WfMultiInstanceRound returned = returnedRound(processB, "activity-b", rootId(), 1);
        WfMultiInstanceRound activeToTerminate = activeRound(processC,
                "activity-c", rootId(), 1);
        try (SqlSession session = sessionFactory.openSession(true))
        {
            WfMultiInstanceRoundMapper mapper = session.getMapper(WfMultiInstanceRoundMapper.class);
            assertEquals(1, mapper.insert(active));
            assertEquals(1, mapper.insert(returned));
            assertEquals(1, mapper.insert(activeToTerminate));
            assertNotNull(active.getRoundId());
            WfMultiInstanceRound created = mapper.selectByRootExecutionId(active.getRootExecutionId());
            WfMultiInstanceRound returnedCreated = mapper.selectByRootExecutionId(returned.getRootExecutionId());
            assertEquals(active.getRoundId(), created.getRoundId());
            assertTrue(created.getCreateTime().isBefore(active.getCreateTime().minusHours(23)));
            assertEquals(1, mapper.selectActiveByProcessInstanceAndActivity(
                    processA, "activity-a").size());
            assertEquals(1, mapper.selectOpenByProcessInstanceAndActivity(
                    processB, "activity-b").size());
            assertEquals(1, mapper.selectMaxRoundNo(processA, "activity-a"));
            assertEquals(null, mapper.selectMaxRoundNo(instanceId("missing"), "activity-a"));

            String revised = WfMultiInstanceRound.encodeMembers(List.of("1", "3", "2"));
            assertEquals(1, mapper.compareAndSetActiveSnapshot(
                    active.getRoundId(), 0, 1, revised));
            assertEquals(0, mapper.compareAndSetActiveSnapshot(
                    active.getRoundId(), 0, 1, revised));
            assertEquals(1, mapper.compareAndSetCompletedStatus(
                    active.getRoundId(), 1, revised));
            WfMultiInstanceRound completed = mapper.selectByRootExecutionId(
                    active.getRootExecutionId());
            assertEquals(WorkflowMultiInstanceRoundStatus.COMPLETED,
                    completed.getRoundStatus());
            assertEquals(1, completed.getRevisionNo());
            assertNotNull(completed.getCompleteTime());
            Duration elapsed = Duration.between(created.getCreateTime(), completed.getCompleteTime());
            assertTrue(!elapsed.isNegative() && elapsed.compareTo(Duration.ofHours(1)) < 0);

            Set<Long> terminatingIds = Set.of(returned.getRoundId(), activeToTerminate.getRoundId());
            assertEquals(2, mapper.terminateOpenByRoundIds(terminatingIds));
            List<WfMultiInstanceRound> terminated = mapper.selectByRoundIds(terminatingIds);
            assertTrue(terminated.stream().allMatch(round -> round.getRoundStatus()
                    == WorkflowMultiInstanceRoundStatus.TERMINATED
                    && round.getTerminateTime() != null));
            assertEquals(returnedCreated.getReturnTime(), mapper.selectByRootExecutionId(
                    returned.getRootExecutionId()).getReturnTime());

            Set<String> instances = Set.of(processA, processB, processC);
            assertEquals(3, mapper.countByProcessInstanceIds(instances));
            assertEquals(3, mapper.deleteByProcessInstanceIds(instances));
            assertEquals(0, mapper.countByProcessInstanceIds(instances));
        }
    }

    /**
     * 验证轮次自然键、根 execution 和同节点开放轮次三项 MySQL 唯一约束。
     *
     * @return void，断言三种冲突分别被 InnoDB 拒绝且终态后可建第二轮
     */
    @Test
    void shouldEnforceAllThreeMySqlUniqueConstraints()
    {
        String process = instanceId("unique");
        WfMultiInstanceRound first = activeRound(process, "activity-u", rootId(), 1);
        try (SqlSession session = sessionFactory.openSession(true))
        {
            WfMultiInstanceRoundMapper mapper = session.getMapper(WfMultiInstanceRoundMapper.class);
            mapper.insert(first);
        }

        WfMultiInstanceRound duplicateRoot = activeRound(
                instanceId("other"), "activity-other", first.getRootExecutionId(), 1);
        assertMapperInsertRejected(duplicateRoot);
        WfMultiInstanceRound duplicateOpen = activeRound(
                process, "activity-u", rootId(), 2);
        assertMapperInsertRejected(duplicateOpen);

        try (SqlSession session = sessionFactory.openSession(true))
        {
            WfMultiInstanceRoundMapper mapper = session.getMapper(WfMultiInstanceRoundMapper.class);
            assertEquals(1, mapper.compareAndSetCompletedStatus(
                    first.getRoundId(), 0, first.getMembersJson()));
        }
        WfMultiInstanceRound duplicateNatural = activeRound(
                process, "activity-u", rootId(), 1);
        assertMapperInsertRejected(duplicateNatural);

        try (SqlSession session = sessionFactory.openSession(true))
        {
            WfMultiInstanceRoundMapper mapper = session.getMapper(WfMultiInstanceRoundMapper.class);
            assertEquals(1, mapper.insert(duplicateOpen));
            assertEquals(2, mapper.selectMaxRoundNo(process, "activity-u"));
        }
    }

    /**
     * 验证 MySQL JSON Schema 对数组类型、数量、唯一性和规范 Long 用户主键的强制约束。
     *
     * @return void，断言 Long 正整数上界可写，且损坏 JSON、空/超长/重复数组和非法用户主键全部被拒绝
     * @throws Exception JSON 编码失败时报告
     */
    @Test
    void shouldEnforceMySqlMemberJsonChecks() throws Exception
    {
        // 先验证规范用户主键的 Long 上界，避免 SQL 正则误伤合法边界值。
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
     * 验证 ACTIVE/RETURNED/REOPENED/COMPLETED/TERMINATED 各时间字段组合和顺序 CHECK。
     *
     * @return void，断言提前完成、ACTIVE 夹带完成时间和重开时间倒置均被拒绝
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
     * 验证两个真实 InnoDB 事务同时用相同旧修订号 CAS 时只有一个成功。
     *
     * @return void，断言两个事务影响行数之和为 1
     * @throws Exception 线程协调、锁等待或 SQL 失败时报告
     */
    @Test
    void shouldAllowOnlyOneRealMySqlConcurrentCas() throws Exception
    {
        WfMultiInstanceRound round = activeRound(
                instanceId("concurrent"), "activity-cas", rootId(), 1);
        try (SqlSession session = sessionFactory.openSession(true))
        {
            session.getMapper(WfMultiInstanceRoundMapper.class).insert(round);
        }

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> concurrentCas(
                round.getRoundId(), WfMultiInstanceRound.encodeMembers(List.of("1", "2")),
                start));
        Future<Integer> second = executor.submit(() -> concurrentCas(
                round.getRoundId(), WfMultiInstanceRound.encodeMembers(List.of("2", "1")),
                start));
        start.countDown();

        assertEquals(1, first.get() + second.get());
    }

    /**
     * 验证当前连接是 MySQL 8 且基线表已注册全部必要 CHECK 约束。
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
     * 在独立 InnoDB 事务中执行成员快照 CAS 并提交。
     *
     * @param roundId long，竞争轮次主键
     * @param membersJson String，当前竞争者的有序成员快照
     * @param start CountDownLatch，两个事务的同步起点
     * @return int，当前事务 CAS 影响行数
     * @throws Exception 等待或数据库事务失败时向 Future 传递
     */
    private int concurrentCas(long roundId, String membersJson, CountDownLatch start)
            throws Exception
    {
        start.await();
        try (SqlSession session = sessionFactory.openSession(false))
        {
            int affected = session.getMapper(WfMultiInstanceRoundMapper.class)
                    .compareAndSetActiveSnapshot(roundId, 0, 1, membersJson);
            session.commit();
            return affected;
        }
    }

    /**
     * 断言一次通过生产 Mapper 发起的写入被 MySQL 唯一约束拒绝。
     *
     * @param round WfMultiInstanceRound，与已有记录冲突的轮次
     * @return void，断言 MyBatis 向上报告数据库冲突
     */
    private void assertMapperInsertRejected(WfMultiInstanceRound round)
    {
        assertThrows(RuntimeException.class, () ->
        {
            try (SqlSession session = sessionFactory.openSession(true))
            {
                session.getMapper(WfMultiInstanceRoundMapper.class).insert(round);
            }
        });
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
     * 用 JDBC 执行一次负例写入，避免领域门禁提前拦截而未命中真实 MySQL CHECK。
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
     * 构造可通过真实 MySQL 全部约束的 ACTIVE 轮次。
     *
     * @param processInstanceId String，本测试前缀流程实例主键
     * @param activityId String，多实例节点标识
     * @param rootExecutionId String，唯一根 execution 主键
     * @param roundNo int，同节点轮次号
     * @return WfMultiInstanceRound，字段完整的 ACTIVE 轮次
     */
    private WfMultiInstanceRound activeRound(String processInstanceId, String activityId,
            String rootExecutionId, int roundNo)
    {
        WfMultiInstanceRound round = new WfMultiInstanceRound();
        round.setDeployId("deployment-it");
        round.setProcessDefinitionId("approval:1:definition");
        round.setProcessInstanceId(processInstanceId);
        round.setActivityId(activityId);
        round.setRootExecutionId(rootExecutionId);
        round.setRoundNo(roundNo);
        round.setMode("ALL");
        round.setMembers(List.of("1", "2"));
        round.setRevisionNo(0);
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.ACTIVE);
        round.setCreateTime(LocalDateTime.now().withNano(0));
        return round;
    }

    /**
     * 构造可通过真实 MySQL 全部退回约束的 RETURNED 轮次。
     *
     * @param processInstanceId String，本测试前缀流程实例主键
     * @param activityId String，多实例节点标识
     * @param rootExecutionId String，唯一根 execution 主键
     * @param roundNo int，同节点轮次号
     * @return WfMultiInstanceRound，具备完整退回关联的开放轮次
     */
    private WfMultiInstanceRound returnedRound(String processInstanceId, String activityId,
            String rootExecutionId, int roundNo)
    {
        WfMultiInstanceRound round = activeRound(
                processInstanceId, activityId, rootExecutionId, roundNo);
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.RETURNED);
        round.setReturnSourceTaskId("task-return");
        round.setReturnActorUserId("1");
        round.setApplicantTaskId("task-applicant");
        round.setReturnTime(round.getCreateTime().plusSeconds(1));
        return round;
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
     * 使用 Jackson 将测试值编码为 JSON，避免手工拼接生产快照。
     *
     * @param value Object，负例需要提交给 MySQL 的结构化值
     * @return String，Jackson 产生的 JSON
     * @throws Exception JSON 编码失败时报告
     */
    private String json(Object value) throws Exception
    {
        return JsonMapper.shared().writeValueAsString(value);
    }

    /**
     * 生成带固定测试前缀且不超过 64 字符的流程实例主键。
     *
     * @param suffix String，用例内可读后缀
     * @return String，可被精确清理的唯一实例主键
     */
    private String instanceId(String suffix)
    {
        String random = UUID.randomUUID().toString().substring(0, 8);
        String compactSuffix = suffix.length() > 30 ? suffix.substring(0, 30) : suffix;
        return TEST_INSTANCE_PREFIX + compactSuffix + "-" + random;
    }

    /**
     * 生成不超过 64 字符的唯一多实例根 execution 主键。
     *
     * @return String，当前记录独占的根 execution 主键
     */
    private String rootId()
    {
        return "mi-root-it-" + UUID.randomUUID();
    }

    /**
     * 删除仅属于本类固定实例前缀的测试数据。
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
            statement.setString(1, TEST_INSTANCE_PREFIX + "%");
            statement.executeUpdate();
        }
    }

    /**
     * 绕过 Java 领域门禁、专门验证真实 MySQL CHECK 的原始轮次字段。
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
