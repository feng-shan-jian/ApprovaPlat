package com.ruoyi.flowable.mapper;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 多实例轮次 Mapper XML 的快速 H2/MyBatis 回归。
 *
 * 本类只验证 Mapper 绑定、CRUD 和 CAS 语义；MySQL JSON/CHECK 和 InnoDB 并发由
 * {@link WfMultiInstanceRoundMapperMySqlTest} 负责真实验收。
 */
class WfMultiInstanceRoundMapperTest
{
    private static JdbcDataSource dataSource;
    private static SqlSessionFactory sessionFactory;
    private static ExecutorService executor;

    /**
     * 创建 MySQL 兼容模式的内存库并加载生产 Mapper XML。
     *
     * @return void，测试类共享一个 SqlSessionFactory
     * @throws Exception 建表或 Mapper XML 加载失败时向 JUnit 报告
     */
    @BeforeAll
    static void setUpFactory() throws Exception
    {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wf_multi_instance_round;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            statement.execute(schemaSql());
        }
        Environment environment = new Environment("round-h2",
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
     * 停止并发 CAS 测试的固定线程池。
     *
     * @return void，不保留测试线程
     */
    @AfterAll
    static void tearDownFactory()
    {
        executor.shutdownNow();
    }

    /**
     * 每个用例前清空轮次表，避免主键和自然键互相影响。
     *
     * @return void，当前用例从空表开始
     * @throws Exception 清理失败时向 JUnit 报告
     */
    @BeforeEach
    void clearTable() throws Exception
    {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement())
        {
            statement.executeUpdate("delete from wf_multi_instance_round");
        }
    }

    /**
     * 验证插入、根执行/当前轮查询、成员 CAS 与完成状态 CAS。
     *
     * @return void，断言生产 Mapper XML 的核心读写路径
     */
    @Test
    void shouldInsertQueryAndCompareAndSet()
    {
        WfMultiInstanceRound round = activeRound("process-1", "activity-a", "root-1", 1);
        try (SqlSession session = sessionFactory.openSession(true))
        {
            WfMultiInstanceRoundMapper mapper = session.getMapper(WfMultiInstanceRoundMapper.class);
            assertEquals(1, mapper.insert(round));
            assertNotNull(round.getRoundId());
            assertEquals(round.getRoundId(),
                    mapper.selectByRootExecutionId("root-1").getRoundId());
            assertEquals(1, mapper.selectOpenByProcessInstanceAndActivity(
                    "process-1", "activity-a").size());
            assertEquals(1, mapper.selectActiveByProcessInstanceAndActivity(
                    "process-1", "activity-a").size());
            assertEquals(1, mapper.selectMaxRoundNo("process-1", "activity-a"));

            String revisedMembers = WfMultiInstanceRound.encodeMembers(List.of("1", "3", "2"));
            assertEquals(1, mapper.compareAndSetActiveSnapshot(
                    round.getRoundId(), 0, 1, revisedMembers));
            assertEquals(0, mapper.compareAndSetActiveSnapshot(
                    round.getRoundId(), 0, 1, revisedMembers));
            assertEquals(1, mapper.compareAndSetCompletedStatus(
                    round.getRoundId(), 1, revisedMembers));
            WfMultiInstanceRound completed = mapper.selectByRootExecutionId("root-1");
            assertEquals(WorkflowMultiInstanceRoundStatus.COMPLETED,
                    completed.getRoundStatus());
            assertEquals(1, completed.getRevisionNo());
            assertNotNull(completed.getCompleteTime());
            assertEquals(List.of("1", "3", "2"), completed.getMembers());
        }
    }

    /**
     * 验证轮次自然键、根 execution 和同节点开放轮次三项唯一性。
     *
     * @return void，断言任一唯一键冲突都拒绝写入
     */
    @Test
    void shouldEnforceThreeUniqueKeys()
    {
        try (SqlSession session = sessionFactory.openSession(true))
        {
            WfMultiInstanceRoundMapper mapper = session.getMapper(WfMultiInstanceRoundMapper.class);
            mapper.insert(activeRound("process-u", "activity-u", "root-u", 1));
        }

        assertInsertFails(activeRound("process-u", "activity-u", "root-other", 1));
        assertInsertFails(activeRound("process-other", "activity-other", "root-u", 1));
        assertInsertFails(activeRound("process-u", "activity-u", "root-next", 2));
    }

    /**
     * 验证两个事务对同一旧修订号 CAS 时只有一个成功。
     *
     * @return void，断言并发更新影响行数之和为 1
     * @throws Exception 线程协调或 SQL 失败时向 JUnit 报告
     */
    @Test
    void shouldAllowOnlyOneConcurrentCas() throws Exception
    {
        WfMultiInstanceRound round = activeRound("process-cas", "activity-cas", "root-cas", 1);
        try (SqlSession session = sessionFactory.openSession(true))
        {
            session.getMapper(WfMultiInstanceRoundMapper.class).insert(round);
        }

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> concurrentCas(
                round.getRoundId(), "[\"1\",\"2\"]", start));
        Future<Integer> second = executor.submit(() -> concurrentCas(
                round.getRoundId(), "[\"2\",\"1\"]", start));
        start.countDown();

        assertEquals(1, first.get() + second.get());
    }

    /**
     * 验证历史删除预检和批量删除同时覆盖 ACTIVE/RETURNED 遗留轮次。
     *
     * @return void，断言指定实例精确删除且其他实例保留
     */
    @Test
    void shouldCountAndDeleteHistoryRoundsInBatches()
    {
        try (SqlSession session = sessionFactory.openSession(true))
        {
            WfMultiInstanceRoundMapper mapper = session.getMapper(WfMultiInstanceRoundMapper.class);
            mapper.insert(activeRound("process-delete-a", "activity-a", "root-da", 1));
            mapper.insert(returnedRound("process-delete-b", "activity-b", "root-db", 1));
            mapper.insert(activeRound("process-keep", "activity-c", "root-keep", 1));

            Set<String> deletedInstances = Set.of("process-delete-a", "process-delete-b");
            assertEquals(2, mapper.countByProcessInstanceIds(deletedInstances));
            assertEquals(2, mapper.deleteByProcessInstanceIds(deletedInstances));
            assertEquals(0, mapper.countByProcessInstanceIds(deletedInstances));
            assertEquals(1, mapper.selectByProcessInstanceId("process-keep").size());
            assertEquals(0, mapper.countByProcessInstanceIds(Set.of()));
            assertEquals(0, mapper.deleteByProcessInstanceIds(Set.of()));
        }
    }

    /**
     * 在独立事务中对指定旧修订号执行成员快照 CAS。
     *
     * @param roundId long，竞争的轮次主键
     * @param membersJson String，当前竞争者准备写入的有序成员
     * @param start CountDownLatch，两个竞争者的同步起点
     * @return int，当前竞争者的 CAS 影响行数
     * @throws Exception 等待或 SQL 事务失败时向 Future 传递
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
     * 断言指定轮次插入因唯一键冲突而失败。
     *
     * @param round WfMultiInstanceRound，与现有记录冲突的轮次
     * @return void，断言 MyBatis 向上抛出数据库异常
     */
    private void assertInsertFails(WfMultiInstanceRound round)
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
     * 构造可插入的 ACTIVE 轮次。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，多实例节点标识
     * @param rootExecutionId String，多实例根 execution 主键
     * @param roundNo int，同节点轮次号
     * @return WfMultiInstanceRound，关联和快照完整的 ACTIVE 轮次
     */
    private WfMultiInstanceRound activeRound(String processInstanceId, String activityId,
            String rootExecutionId, int roundNo)
    {
        WfMultiInstanceRound round = new WfMultiInstanceRound();
        round.setDeployId("deployment-1");
        round.setProcessDefinitionId("approval:1:definition");
        round.setProcessInstanceId(processInstanceId);
        round.setActivityId(activityId);
        round.setRootExecutionId(rootExecutionId);
        round.setRoundNo(roundNo);
        round.setMode("ALL");
        round.setMembers(List.of("1", "2"));
        round.setRevisionNo(0);
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.ACTIVE);
        round.setCreateTime(LocalDateTime.of(2026, 8, 23, 10, 0));
        return round;
    }

    /**
     * 构造可插入且依然属于开放轮次的 RETURNED 记录。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，多实例节点标识
     * @param rootExecutionId String，多实例根 execution 主键
     * @param roundNo int，同节点轮次号
     * @return WfMultiInstanceRound，具备完整退回关联的 RETURNED 轮次
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
        round.setReturnTime(round.getCreateTime().plusMinutes(1));
        return round;
    }

    /**
     * 生成 H2 专用的最小表结构，保留生产 Mapper 依赖的列、三项唯一键和开放轮次语义。
     *
     * @return String，可由 H2 MySQL 兼容模式执行的建表 SQL
     */
    private static String schemaSql()
    {
        return """
                create table wf_multi_instance_round (
                    round_id bigint generated by default as identity primary key,
                    deploy_id varchar(64) not null,
                    process_definition_id varchar(64) not null,
                    process_instance_id varchar(64) not null,
                    activity_id varchar(255) not null,
                    root_execution_id varchar(64) not null,
                    round_no int not null,
                    mode varchar(3) not null,
                    members_json varchar(4096) not null,
                    revision_no int not null,
                    round_status varchar(16) not null,
                    return_source_task_id varchar(64),
                    return_actor_user_id varchar(64),
                    applicant_task_id varchar(64),
                    create_time timestamp(3) not null,
                    return_time timestamp(3),
                    reopen_time timestamp(3),
                    complete_time timestamp(3),
                    terminate_time timestamp(3),
                    unique(process_instance_id, activity_id, round_no),
                    unique(root_execution_id),
                    unique(process_instance_id, activity_id)
                )
                """;
    }
}
