package com.ruoyi.flowable.mapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 轮次 Mapper 在 H2 与 MySQL 上共同遵守的数据库中立契约。
 *
 * 本契约直接执行正式 Mapper XML，并保留每个业务场景的显式断言；数据库环境类只负责
 * SqlSessionFactory、数据源生命周期、数据清理和当前数据库可用的稳定时间。
 */
abstract class AbstractWfMultiInstanceRoundMapperContractTest
{
    protected static final String CONTRACT_INSTANCE_PREFIX = "mi-round-contract-";

    private SqlSession contractSession;
    private WfMultiInstanceRoundMapper contractMapper;
    private ExecutorService executor;

    /**
     * 返回当前数据库环境加载了正式 Mapper XML 的会话工厂。
     *
     * @return SqlSessionFactory，供普通写入和独立并发事务共同使用
     */
    protected abstract SqlSessionFactory sessionFactory();

    /**
     * 返回当前数据库可稳定持久化和比较的时间值。
     *
     * @return LocalDateTime，精度必须兼容当前数据库的 timestamp 列
     */
    protected abstract LocalDateTime stableTime();

    /**
     * 为每个契约场景创建自动提交 Mapper 和两线程并发执行器。
     *
     * @return void，场景可直接调用 {@link #mapper()}，并发写入使用独立事务
     */
    @BeforeEach
    void setUpContractInfrastructure()
    {
        contractSession = sessionFactory().openSession(true);
        contractMapper = contractSession.getMapper(WfMultiInstanceRoundMapper.class);
        executor = Executors.newFixedThreadPool(2);
    }

    /**
     * 关闭当前 Mapper 会话，并强制回收并发测试线程。
     *
     * @return void，不向后续用例泄漏连接或线程
     * @throws InterruptedException 等待线程池退出被中断时向 JUnit 报告
     */
    @AfterEach
    void tearDownContractInfrastructure() throws InterruptedException
    {
        try
        {
            if (executor != null)
            {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS),
                        "轮次 Mapper 契约线程池未按时停止");
            }
        }
        finally
        {
            if (contractSession != null)
            {
                contractSession.close();
            }
        }
    }

    /**
     * 验证插入、查询、ACTIVE 成员快照 CAS、完成 CAS 和数据库时钟。
     *
     * @return void，断言正式 Mapper XML 的核心 CRUD 与 ACTIVE 状态路径
     */
    @Test
    void shouldInsertQueryAndCompareAndSetActiveSnapshot()
    {
        String processInstanceId = instanceId("crud");
        String rootExecutionId = rootId();
        WfMultiInstanceRound round = activeRound(
                processInstanceId, "activity-crud", rootExecutionId, 1);
        // 客户端时间故意领先一天，证明 insert 和完成 CAS 均采用数据库时钟。
        round.setCreateTime(stableTime().plusDays(1));

        assertEquals(1, mapper().insert(round));
        assertNotNull(round.getRoundId());
        WfMultiInstanceRound created = mapper().selectByRootExecutionId(rootExecutionId);
        assertEquals(round.getRoundId(), created.getRoundId());
        assertTrue(created.getCreateTime().isBefore(round.getCreateTime().minusHours(23)));
        assertEquals(1, mapper().selectOpenByProcessInstanceAndActivity(
                processInstanceId, "activity-crud").size());
        assertEquals(1, mapper().selectActiveByProcessInstanceAndActivity(
                processInstanceId, "activity-crud").size());
        assertEquals(1, mapper().selectMaxRoundNo(processInstanceId, "activity-crud"));
        assertNull(mapper().selectMaxRoundNo(instanceId("missing"), "activity-crud"));

        String revisedMembers = WfMultiInstanceRound.encodeMembers(List.of("1", "3", "2"));
        assertEquals(1, mapper().compareAndSetActiveSnapshot(
                round.getRoundId(), 0, 1, revisedMembers));
        assertEquals(0, mapper().compareAndSetActiveSnapshot(
                round.getRoundId(), 0, 1, revisedMembers));
        assertEquals(1, mapper().compareAndSetCompletedStatus(
                round.getRoundId(), 1, revisedMembers));
        WfMultiInstanceRound completed = mapper().selectByRootExecutionId(rootExecutionId);
        assertEquals(WorkflowMultiInstanceRoundStatus.COMPLETED, completed.getRoundStatus());
        assertEquals(1, completed.getRevisionNo());
        assertNotNull(completed.getCompleteTime());
        assertEquals(List.of("1", "3", "2"), completed.getMembers());
        Duration elapsed = Duration.between(created.getCreateTime(), completed.getCompleteTime());
        assertTrue(!elapsed.isNegative() && elapsed.compareTo(Duration.ofHours(1)) < 0);
    }

    /**
     * 验证 ACTIVE→RETURNED→REOPENED 的严格 CAS、数据库时钟和冻结字段保留。
     *
     * @return void，断言错误 revision 或任一退回关联漂移均失败关闭，成功重提释放开放键
     */
    @Test
    void shouldReturnAndReopenWithStrictCasAndDatabaseClock()
    {
        String processInstanceId = instanceId("return-reopen");
        String rootExecutionId = rootId();
        WfMultiInstanceRound round = activeRound(
                processInstanceId, "activity-return", rootExecutionId, 1);
        round.setMode("ANY");
        round.setMembers(List.of("11", "12", "13"));
        round.setRevisionNo(4);
        round.setCreateTime(stableTime().plusDays(1));
        String sourceTaskId = "task-return-source";
        String returnActorUserId = "11";
        String applicantTaskId = "task-applicant";

        assertEquals(1, mapper().insert(round));
        WfMultiInstanceRound created = mapper().selectByRootExecutionId(rootExecutionId);
        assertTrue(created.getCreateTime().isBefore(round.getCreateTime().minusHours(23)));
        assertNull(created.getReturnTime());

        assertEquals(1, mapper().compareAndSetReturnedStatus(round.getRoundId(), 4,
                sourceTaskId, returnActorUserId, applicantTaskId));
        assertEquals(0, mapper().compareAndSetReturnedStatus(round.getRoundId(), 4,
                "task-racing-source", "12", "task-racing-applicant"));
        WfMultiInstanceRound returned = mapper().selectByRootExecutionId(rootExecutionId);
        assertEquals(WorkflowMultiInstanceRoundStatus.RETURNED, returned.getRoundStatus());
        assertEquals(sourceTaskId, returned.getReturnSourceTaskId());
        assertEquals(returnActorUserId, returned.getReturnActorUserId());
        assertEquals(applicantTaskId, returned.getApplicantTaskId());
        assertNotNull(returned.getReturnTime());
        assertTrue(!returned.getReturnTime().isBefore(created.getCreateTime()));
        assertTrue(Duration.between(created.getCreateTime(), returned.getReturnTime())
                .compareTo(Duration.ofHours(1)) < 0);
        assertEquals(List.of("11", "12", "13"), returned.getMembers());
        assertEquals("ANY", returned.getMode());
        assertEquals(4, returned.getRevisionNo());
        assertEquals(rootExecutionId, returned.getRootExecutionId());
        assertEquals(1, mapper().selectReturnedByApplicantTaskId(applicantTaskId).size());

        // revision、申请人任务、源任务和操作者都是重提 CAS 的冻结条件。
        assertEquals(0, mapper().compareAndSetReopenedStatus(round.getRoundId(), 3,
                applicantTaskId, sourceTaskId, returnActorUserId));
        assertEquals(0, mapper().compareAndSetReopenedStatus(round.getRoundId(), 4,
                "task-other-applicant", sourceTaskId, returnActorUserId));
        assertEquals(0, mapper().compareAndSetReopenedStatus(round.getRoundId(), 4,
                applicantTaskId, "task-other-source", returnActorUserId));
        assertEquals(0, mapper().compareAndSetReopenedStatus(round.getRoundId(), 4,
                applicantTaskId, sourceTaskId, "12"));
        assertEquals(1, mapper().compareAndSetReopenedStatus(round.getRoundId(), 4,
                applicantTaskId, sourceTaskId, returnActorUserId));
        assertEquals(0, mapper().compareAndSetReopenedStatus(round.getRoundId(), 4,
                applicantTaskId, sourceTaskId, returnActorUserId));

        WfMultiInstanceRound reopened = mapper().selectByRootExecutionId(rootExecutionId);
        assertEquals(WorkflowMultiInstanceRoundStatus.REOPENED, reopened.getRoundStatus());
        assertNotNull(reopened.getReopenTime());
        assertTrue(!reopened.getReopenTime().isBefore(returned.getReturnTime()));
        assertEquals(created.getCreateTime(), reopened.getCreateTime());
        assertEquals(returned.getReturnTime(), reopened.getReturnTime());
        assertEquals(returned.getMembersJson(), reopened.getMembersJson());
        assertEquals(returned.getRevisionNo(), reopened.getRevisionNo());
        assertEquals(returned.getRootExecutionId(), reopened.getRootExecutionId());
        assertEquals(0, mapper().selectReturnedByApplicantTaskId(applicantTaskId).size());

        WfMultiInstanceRound nextRound = activeRound(
                processInstanceId, "activity-return", rootId(), 2);
        assertEquals(1, mapper().insert(nextRound));
        assertEquals(1, mapper().selectActiveByProcessInstanceAndActivity(
                processInstanceId, "activity-return").size());
    }

    /**
     * 验证申请人任务查询返回全部 RETURNED 关联，而不是静默截取一行。
     *
     * @return void，断言重复关联可由业务层通过 List 大小明确识别
     */
    @Test
    void shouldExposeDuplicateReturnedApplicantTaskAssociations()
    {
        WfMultiInstanceRound first = returnedRound(
                instanceId("returned-a"), "activity-a", rootId(), 1);
        WfMultiInstanceRound second = returnedRound(
                instanceId("returned-b"), "activity-b", rootId(), 1);
        assertEquals(1, mapper().insert(first));
        assertEquals(1, mapper().insert(second));

        List<WfMultiInstanceRound> returned = mapper().selectReturnedByApplicantTaskId(
                "task-applicant");
        assertEquals(2, returned.size());
        assertTrue(returned.get(0).getRoundId() < returned.get(1).getRoundId());
    }

    /**
     * 验证自然键、根 execution 和同节点开放轮次三项唯一业务语义。
     *
     * @return void，断言 ACTIVE/RETURNED 均占用开放键，终态释放开放键但不释放自然键
     */
    @Test
    void shouldEnforceThreeUniqueKeys()
    {
        String processInstanceId = instanceId("unique");
        WfMultiInstanceRound first = activeRound(
                processInstanceId, "activity-unique", rootId(), 1);
        mapper().insert(first);

        assertInsertRejected(activeRound(
                processInstanceId, "activity-unique", rootId(), 1));
        assertInsertRejected(activeRound(
                instanceId("unique-root"), "activity-other", first.getRootExecutionId(), 1));
        WfMultiInstanceRound nextRound = activeRound(
                processInstanceId, "activity-unique", rootId(), 2);
        assertInsertRejected(nextRound);
        assertInsertRejected(returnedRound(
                processInstanceId, "activity-unique", rootId(), 2));

        assertEquals(1, mapper().compareAndSetCompletedStatus(
                first.getRoundId(), 0, first.getMembersJson()));
        assertInsertRejected(activeRound(
                processInstanceId, "activity-unique", rootId(), 1));
        assertEquals(1, mapper().insert(nextRound));
        assertEquals(2, mapper().selectMaxRoundNo(processInstanceId, "activity-unique"));

        String returnedProcess = instanceId("unique-returned");
        mapper().insert(returnedRound(returnedProcess, "activity-returned", rootId(), 1));
        assertInsertRejected(activeRound(
                returnedProcess, "activity-returned", rootId(), 2));
    }

    /**
     * 验证两个独立事务对同一旧 revision 执行成员快照 CAS 时只有一个成功。
     *
     * @return void，断言并发更新影响行数之和为 1
     * @throws Exception 线程协调或数据库事务失败时向 JUnit 报告
     */
    @Test
    void shouldAllowOnlyOneConcurrentActiveSnapshotCas() throws Exception
    {
        WfMultiInstanceRound round = activeRound(
                instanceId("concurrent-members"), "activity-cas", rootId(), 1);
        mapper().insert(round);

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> concurrentCas(
                round.getRoundId(), WfMultiInstanceRound.encodeMembers(List.of("1", "2")), start));
        Future<Integer> second = executor.submit(() -> concurrentCas(
                round.getRoundId(), WfMultiInstanceRound.encodeMembers(List.of("2", "1")), start));
        start.countDown();

        assertEquals(1, first.get() + second.get());
    }

    /**
     * 验证并发退回和并发重提各自只有一个严格 CAS 成功。
     *
     * @return void，断言退回关联来自同一胜方，且同一冻结关联只能重开一次
     * @throws Exception 线程协调、锁竞争或数据库事务失败时向 JUnit 报告
     */
    @Test
    void shouldAllowOnlyOneConcurrentReturnAndReopenCas() throws Exception
    {
        WfMultiInstanceRound round = activeRound(
                instanceId("concurrent-return"), "activity-return-cas", rootId(), 1);
        mapper().insert(round);

        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> concurrentReturnCas(round.getRoundId(),
                "task-source-a", "1", "task-applicant-a", start));
        Future<Integer> second = executor.submit(() -> concurrentReturnCas(round.getRoundId(),
                "task-source-b", "2", "task-applicant-b", start));
        start.countDown();
        assertEquals(1, first.get() + second.get());

        WfMultiInstanceRound returned = mapper().selectByRootExecutionId(
                round.getRootExecutionId());
        assertEquals(WorkflowMultiInstanceRoundStatus.RETURNED, returned.getRoundStatus());
        boolean firstWon = "task-source-a".equals(returned.getReturnSourceTaskId())
                && "1".equals(returned.getReturnActorUserId())
                && "task-applicant-a".equals(returned.getApplicantTaskId());
        boolean secondWon = "task-source-b".equals(returned.getReturnSourceTaskId())
                && "2".equals(returned.getReturnActorUserId())
                && "task-applicant-b".equals(returned.getApplicantTaskId());
        assertTrue(firstWon || secondWon);

        CountDownLatch reopenStart = new CountDownLatch(1);
        Future<Integer> reopenFirst = executor.submit(() -> concurrentReopenCas(
                returned, reopenStart));
        Future<Integer> reopenSecond = executor.submit(() -> concurrentReopenCas(
                returned, reopenStart));
        reopenStart.countDown();
        assertEquals(1, reopenFirst.get() + reopenSecond.get());
    }

    /**
     * 验证开放轮次批量终止、历史统计和删除保持精确的实例边界。
     *
     * @return void，断言 ACTIVE/RETURNED 可终止，指定历史可删除且保留实例不受影响
     */
    @Test
    void shouldTerminateCountAndDeleteHistoryRounds()
    {
        String activeProcess = instanceId("delete-active");
        String returnedProcess = instanceId("delete-returned");
        String keepProcess = instanceId("keep");
        WfMultiInstanceRound active = activeRound(
                activeProcess, "activity-a", rootId(), 1);
        WfMultiInstanceRound returned = returnedRound(
                returnedProcess, "activity-b", rootId(), 1);
        WfMultiInstanceRound keep = activeRound(
                keepProcess, "activity-c", rootId(), 1);
        mapper().insert(active);
        mapper().insert(returned);
        mapper().insert(keep);
        WfMultiInstanceRound returnedCreated = mapper().selectByRootExecutionId(
                returned.getRootExecutionId());

        Set<Long> terminatingIds = Set.of(active.getRoundId(), returned.getRoundId());
        assertEquals(2, mapper().terminateOpenByRoundIds(terminatingIds));
        List<WfMultiInstanceRound> terminated = mapper().selectByRoundIds(terminatingIds);
        assertEquals(2, terminated.size());
        assertTrue(terminated.stream().allMatch(round -> round.getRoundStatus()
                == WorkflowMultiInstanceRoundStatus.TERMINATED
                && round.getTerminateTime() != null));
        assertEquals(returnedCreated.getReturnTime(), mapper().selectByRootExecutionId(
                returned.getRootExecutionId()).getReturnTime());

        Set<String> deletedInstances = Set.of(activeProcess, returnedProcess);
        assertEquals(2, mapper().countByProcessInstanceIds(deletedInstances));
        assertEquals(2, mapper().deleteByProcessInstanceIds(deletedInstances));
        assertEquals(0, mapper().countByProcessInstanceIds(deletedInstances));
        assertEquals(1, mapper().selectByProcessInstanceId(keepProcess).size());
        assertEquals(0, mapper().countByProcessInstanceIds(Set.of()));
        assertEquals(0, mapper().deleteByProcessInstanceIds(Set.of()));
    }

    /**
     * 返回当前场景共享的正式 Mapper。
     *
     * @return WfMultiInstanceRoundMapper，绑定当前环境正式 XML 的自动提交 Mapper
     */
    protected final WfMultiInstanceRoundMapper mapper()
    {
        return contractMapper;
    }

    /**
     * 构造合法 ACTIVE 轮次，供公共契约和数据库专属负例共同使用。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，多实例节点标识
     * @param rootExecutionId String，多实例根 execution 主键
     * @param roundNo int，同节点轮次号
     * @return WfMultiInstanceRound，字段完整的 ACTIVE 轮次
     */
    protected final WfMultiInstanceRound activeRound(String processInstanceId, String activityId,
            String rootExecutionId, int roundNo)
    {
        WfMultiInstanceRound round = new WfMultiInstanceRound();
        round.setDeployId("deployment-contract");
        round.setProcessDefinitionId("approval:1:definition");
        round.setProcessInstanceId(processInstanceId);
        round.setActivityId(activityId);
        round.setRootExecutionId(rootExecutionId);
        round.setRoundNo(roundNo);
        round.setMode("ALL");
        round.setMembers(List.of("1", "2"));
        round.setRevisionNo(0);
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.ACTIVE);
        round.setCreateTime(stableTime());
        return round;
    }

    /**
     * 构造合法 RETURNED 开放轮次，保留完整退回关联和时间顺序。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，多实例节点标识
     * @param rootExecutionId String，多实例根 execution 主键
     * @param roundNo int，同节点轮次号
     * @return WfMultiInstanceRound，字段完整的 RETURNED 轮次
     */
    protected final WfMultiInstanceRound returnedRound(String processInstanceId, String activityId,
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
     * 生成带固定清理前缀且不超过字段上限的唯一流程实例主键。
     *
     * @param suffix String，用例内可读后缀
     * @return String，可由 MySQL 环境精确清理的唯一实例主键
     */
    protected final String instanceId(String suffix)
    {
        String compactSuffix = suffix.length() > 24 ? suffix.substring(0, 24) : suffix;
        return CONTRACT_INSTANCE_PREFIX + compactSuffix + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 生成不超过数据库字段上限的唯一根 execution 主键。
     *
     * @return String，当前记录独占的根 execution 主键
     */
    protected final String rootId()
    {
        return "mi-root-contract-" + UUID.randomUUID();
    }

    /**
     * 在独立事务中执行成员快照 CAS 并提交。
     *
     * @param roundId long，竞争轮次主键
     * @param membersJson String，竞争者准备写入的有序成员快照
     * @param start CountDownLatch，两个事务的同步起点
     * @return int，当前事务 CAS 影响行数
     * @throws Exception 等待或数据库事务失败时向 Future 传递
     */
    private int concurrentCas(long roundId, String membersJson, CountDownLatch start)
            throws Exception
    {
        start.await();
        try (SqlSession session = sessionFactory().openSession(false))
        {
            int affected = session.getMapper(WfMultiInstanceRoundMapper.class)
                    .compareAndSetActiveSnapshot(roundId, 0, 1, membersJson);
            session.commit();
            return affected;
        }
    }

    /**
     * 在独立事务中执行整组退回 CAS 并提交。
     *
     * @param roundId long，竞争的 ACTIVE 轮次主键
     * @param sourceTaskId String，当前竞争者的退回源任务主键
     * @param actorUserId String，当前竞争者的规范用户主键
     * @param applicantTaskId String，当前竞争者观察到的申请人任务主键
     * @param start CountDownLatch，两个事务的同步起点
     * @return int，当前事务 ACTIVE→RETURNED CAS 的影响行数
     * @throws Exception 等待或数据库事务失败时向 Future 传递
     */
    private int concurrentReturnCas(long roundId, String sourceTaskId,
            String actorUserId, String applicantTaskId, CountDownLatch start) throws Exception
    {
        start.await();
        try (SqlSession session = sessionFactory().openSession(false))
        {
            int affected = session.getMapper(WfMultiInstanceRoundMapper.class)
                    .compareAndSetReturnedStatus(roundId, 0, sourceTaskId,
                            actorUserId, applicantTaskId);
            session.commit();
            return affected;
        }
    }

    /**
     * 在独立事务中用同一冻结关联执行重提 CAS 并提交。
     *
     * @param returned WfMultiInstanceRound，已经由胜方退回并重新读取的冻结轮次
     * @param start CountDownLatch，两个重提事务的同步起点
     * @return int，当前事务 RETURNED→REOPENED CAS 的影响行数
     * @throws Exception 等待、锁竞争或数据库事务失败时向 Future 传递
     */
    private int concurrentReopenCas(WfMultiInstanceRound returned, CountDownLatch start)
            throws Exception
    {
        start.await();
        try (SqlSession session = sessionFactory().openSession(false))
        {
            int affected = session.getMapper(WfMultiInstanceRoundMapper.class)
                    .compareAndSetReopenedStatus(returned.getRoundId(),
                            returned.getRevisionNo(), returned.getApplicantTaskId(),
                            returned.getReturnSourceTaskId(), returned.getReturnActorUserId());
            session.commit();
            return affected;
        }
    }

    /**
     * 断言一次正式 Mapper 写入被数据库唯一约束拒绝。
     *
     * @param round WfMultiInstanceRound，与已有记录冲突的轮次
     * @return void，断言 MyBatis 向上报告数据库冲突
     */
    private void assertInsertRejected(WfMultiInstanceRound round)
    {
        assertThrows(RuntimeException.class, () ->
        {
            try (SqlSession session = sessionFactory().openSession(true))
            {
                session.getMapper(WfMultiInstanceRoundMapper.class).insert(round);
            }
        });
    }
}
