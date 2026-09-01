package com.ruoyi.flowable.mapper;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ruoyi.flowable.domain.WfCollaborationOutbox;

/**
 * H2 MySQL 模式与真实 MySQL 共用的出站动作能力、数据库时钟和 CAS 合同。
 */
final class WfCollaborationOutboxCapabilityMapperContract implements AutoCloseable
{
    private static final String PAYLOAD_HASH = "0".repeat(64);
    private static final String ACTOR_USER_ID = "42";
    private final SqlSession session;
    private final WfCollaborationOutboxMapper mapper;
    private final long endpointId;
    private final String channelId;
    private final AtomicLong sequence = new AtomicLong();

    /**
     * 创建一次共用正式 Mapper XML 的能力合同执行器。
     * @param sessionFactory SqlSessionFactory，当前数据库环境的正式 Mapper 会话工厂
     * @param endpointId long，当前环境已存在或测试 schema 接受的端点主键
     * @param channelId String，当前环境已存在或测试 schema 接受的通道主键
     * @return void，构造后持有自动提交会话
     */
    WfCollaborationOutboxCapabilityMapperContract(SqlSessionFactory sessionFactory,
            long endpointId, String channelId)
    {
        this.session = sessionFactory.openSession(true);
        this.mapper = session.getMapper(WfCollaborationOutboxMapper.class);
        this.endpointId = endpointId;
        this.channelId = channelId;
    }

    /**
     * 执行完整出站能力矩阵、条件更新和旧 revision 失败合同。
     * @return void，任一查询投影、数据库时钟或 CAS 条件漂移时失败
     * @throws SQLException 原始并发修订更新失败时向 JUnit 报告
     */
    void verifyAll() throws SQLException
    {
        shouldProjectCompleteMatrixAndAlignConditionalUpdates();
        shouldRejectStaleRevisionAfterTrueCapabilitySnapshot();
    }

    /**
     * 在真实 MySQL 固定会话数据库时钟后验证 lease_until 与 current_timestamp(3) 精确相等边界。
     * @return void，投影和最终取消条件任一误用小于号时失败
     * @throws SQLException 会话时钟、数据装配或正式 Mapper 执行失败时向 JUnit 报告
     */
    void verifyExactLeaseBoundaryWithFixedMySqlClock() throws SQLException
    {
        // MySQL 的 SET timestamp 只固定当前会话数据库时钟，确保查询与更新都命中真正等号边界。
        try (Statement statement = session.getConnection().createStatement())
        {
            statement.execute("set timestamp=unix_timestamp('2030-01-02 03:04:05')");
        }
        try
        {
            Scenario boundary = new Scenario("DELIVERING 精确等于数据库当前时间",
                    "DELIVERING", 0, false, true);
            WfCollaborationOutbox row = insertScenario(boundary);
            assertCapability(row, false, true, boundary.name() + " 查询投影");
            assertEquals(1, mapper.cancel(row.getMessageId(), row.getRevisionNo()),
                    boundary.name() + " 取消条件更新");
            assertCapability(mapper.selectById(row.getMessageId()), false, false,
                    boundary.name() + " 取消后正式能力");
        }
        finally
        {
            // 恢复实时数据库时钟，避免影响同一连接后续清理和其他测试。
            try (Statement statement = session.getConnection().createStatement())
            {
                statement.execute("set timestamp=0");
            }
            session.clearCache();
        }
    }

    /**
     * 验证完整状态/租约矩阵，并逐项证明能力真假与最终条件更新影响行数一致。
     * @return void，成功写入后还会重新查询正式状态能力
     * @throws SQLException 测试状态装配失败时向 JUnit 报告
     */
    private void shouldProjectCompleteMatrixAndAlignConditionalUpdates() throws SQLException
    {
        for (Scenario scenario : scenarios())
        {
            WfCollaborationOutbox projected = insertScenario(scenario);
            assertCapability(projected, scenario.retryAllowed(), scenario.cancelAllowed(),
                    scenario.name() + " 查询投影");

            WfCollaborationOutbox retryCandidate = insertScenario(scenario);
            int retryUpdated = mapper.compensate(retryCandidate.getMessageId(),
                    retryCandidate.getRevisionNo(), ACTOR_USER_ID);
            assertEquals(scenario.retryAllowed() ? 1 : 0, retryUpdated,
                    scenario.name() + " 补偿条件更新");
            assertRetryResult(retryCandidate, scenario, retryUpdated);

            WfCollaborationOutbox cancelCandidate = insertScenario(scenario);
            int cancelUpdated = mapper.cancel(cancelCandidate.getMessageId(),
                    cancelCandidate.getRevisionNo());
            assertEquals(scenario.cancelAllowed() ? 1 : 0, cancelUpdated,
                    scenario.name() + " 取消条件更新");
            assertCancelResult(cancelCandidate, scenario, cancelUpdated);
        }
    }

    /**
     * 验证旧快照能力为 true 时，revision 被并发推进后最终写端仍失败关闭。
     * @return void，失败写不增加补偿次数且不覆盖并发后的正式状态
     * @throws SQLException 原始 revision 推进失败时向 JUnit 报告
     */
    private void shouldRejectStaleRevisionAfterTrueCapabilitySnapshot() throws SQLException
    {
        WfCollaborationOutbox retrySnapshot = insertScenario(
                new Scenario("死信旧快照", "DEAD_LETTER", null, true, true));
        assertTrue(retrySnapshot.isRetryAllowed());
        advanceRevision(retrySnapshot.getMessageId());
        assertEquals(0, mapper.compensate(retrySnapshot.getMessageId(),
                retrySnapshot.getRevisionNo(), ACTOR_USER_ID));
        WfCollaborationOutbox retryCurrent = mapper.selectById(retrySnapshot.getMessageId());
        assertEquals("DEAD_LETTER", retryCurrent.getStatus());
        assertEquals(0, retryCurrent.getCompensationCount());
        assertEquals(retrySnapshot.getRevisionNo() + 1, retryCurrent.getRevisionNo());

        WfCollaborationOutbox cancelSnapshot = insertScenario(
                new Scenario("待投递旧快照", "PENDING", null, false, true));
        assertTrue(cancelSnapshot.isCancelAllowed());
        advanceRevision(cancelSnapshot.getMessageId());
        assertEquals(0, mapper.cancel(cancelSnapshot.getMessageId(),
                cancelSnapshot.getRevisionNo()));
        WfCollaborationOutbox cancelCurrent = mapper.selectById(cancelSnapshot.getMessageId());
        assertEquals("PENDING", cancelCurrent.getStatus());
        assertEquals(0, cancelCurrent.getCompensationCount());
        assertEquals(cancelSnapshot.getRevisionNo() + 1, cancelCurrent.getRevisionNo());
    }

    /**
     * 断言补偿成功后的 RETRYING 能力，或失败写完全保留原状态、revision 和补偿次数。
     * @param before WfCollaborationOutbox，执行条件更新前的查询快照
     * @param scenario Scenario，当前权威矩阵场景
     * @param updated int，Mapper 条件更新影响行数
     * @return void，仅执行断言
     */
    private void assertRetryResult(WfCollaborationOutbox before, Scenario scenario, int updated)
    {
        WfCollaborationOutbox after = mapper.selectById(before.getMessageId());
        if (updated == 1)
        {
            assertEquals("RETRYING", after.getStatus());
            assertEquals(1, after.getCompensationCount());
            assertEquals(before.getRevisionNo() + 1, after.getRevisionNo());
            assertCapability(after, false, true, scenario.name() + " 补偿后正式能力");
            return;
        }
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getCompensationCount(), after.getCompensationCount());
        assertEquals(before.getRevisionNo(), after.getRevisionNo());
    }

    /**
     * 断言取消成功后的 CANCELLED 能力，或失败写完全保留原状态、revision 和补偿次数。
     * @param before WfCollaborationOutbox，执行条件更新前的查询快照
     * @param scenario Scenario，当前权威矩阵场景
     * @param updated int，Mapper 条件更新影响行数
     * @return void，仅执行断言
     */
    private void assertCancelResult(WfCollaborationOutbox before, Scenario scenario, int updated)
    {
        WfCollaborationOutbox after = mapper.selectById(before.getMessageId());
        if (updated == 1)
        {
            assertEquals("CANCELLED", after.getStatus());
            assertEquals(before.getCompensationCount(), after.getCompensationCount());
            assertEquals(before.getRevisionNo() + 1, after.getRevisionNo());
            assertCapability(after, false, false, scenario.name() + " 取消后正式能力");
            return;
        }
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(before.getCompensationCount(), after.getCompensationCount());
        assertEquals(before.getRevisionNo(), after.getRevisionNo());
    }

    /**
     * 插入正式 Mapper 记录，再用数据库时钟装配权威状态和租约边界。
     * @param scenario Scenario，待装配的状态、租约偏移和预期能力
     * @return WfCollaborationOutbox，重新执行正式查询得到的能力快照
     * @throws SQLException 状态装配失败时向 JUnit 报告
     */
    private WfCollaborationOutbox insertScenario(Scenario scenario) throws SQLException
    {
        WfCollaborationOutbox row = new WfCollaborationOutbox();
        row.setMessageId(UUID.randomUUID().toString());
        row.setChannelId(channelId);
        row.setSequenceNo(sequence.incrementAndGet());
        row.setSourceProcessDefinitionKey("collab-capability-it");
        row.setSourceProcessInstanceId("source-" + row.getMessageId());
        row.setSourceExecutionId("execution-" + row.getMessageId());
        row.setSourceElementId("send-task-" + row.getMessageId());
        row.setMessageName("CapabilityMessage");
        row.setTargetProcessDefinitionKey("capability-target");
        row.setCorrelationKey("business-" + row.getMessageId());
        row.setEndpointId(endpointId);
        row.setEndpointRevision(1);
        row.setRequestPath("/workflow/runtime-event/collaboration/message");
        row.setDeliveryConfigJson("{}");
        row.setVariablesJson("{}");
        row.setPayloadSha256(PAYLOAD_HASH);
        row.setMaxAttempts(5);
        assertEquals(1, mapper.insert(row));

        boolean completed = List.of("PROCESSED", "DEAD_LETTER", "CANCELLED")
                .contains(scenario.status());
        String leaseExpression = scenario.leaseOffsetSeconds() == null
                ? "null"
                : "timestampadd(second," + scenario.leaseOffsetSeconds()
                        + ",current_timestamp(3))";
        String completeExpression = completed ? "current_timestamp(3)" : "null";
        String sql = "update wf_collaboration_outbox set status=?,attempt_count=?,"
                + "compensation_count=0,revision_no=0,lease_owner=?,lease_until="
                + leaseExpression + ",complete_time=" + completeExpression
                + " where message_id=?";
        try (PreparedStatement statement = session.getConnection().prepareStatement(sql))
        {
            statement.setString(1, scenario.status());
            statement.setInt(2, "DEAD_LETTER".equals(scenario.status()) ? 5 : 0);
            statement.setString(3,
                    scenario.leaseOffsetSeconds() == null ? null : "capability-worker");
            statement.setString(4, row.getMessageId());
            assertEquals(1, statement.executeUpdate());
        }
        session.clearCache();
        return mapper.selectById(row.getMessageId());
    }

    /**
     * 仅推进 revision，模拟能力查询之后发生的并发状态修订。
     * @param messageId String，待推进的 outbox 主键
     * @return void，不修改状态、补偿次数或租约
     * @throws SQLException 原始并发更新失败时向 JUnit 报告
     */
    private void advanceRevision(String messageId) throws SQLException
    {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "update wf_collaboration_outbox set revision_no=revision_no+1 where message_id=?"))
        {
            statement.setString(1, messageId);
            assertEquals(1, statement.executeUpdate());
        }
        session.clearCache();
    }

    /**
     * 断言查询视图中的两个出站能力字段。
     * @param row WfCollaborationOutbox，正式 Mapper 查询结果
     * @param retryAllowed boolean，预期补偿能力
     * @param cancelAllowed boolean，预期取消能力
     * @param context String，失败时的矩阵场景说明
     * @return void，仅执行断言
     */
    private void assertCapability(WfCollaborationOutbox row, boolean retryAllowed,
            boolean cancelAllowed, String context)
    {
        assertEquals(retryAllowed, row.isRetryAllowed(), context + " retryAllowed");
        assertEquals(cancelAllowed, row.isCancelAllowed(), context + " cancelAllowed");
    }

    /**
     * 返回包含等于数据库当前时间边界的完整权威出站矩阵。
     * @return List&lt;Scenario&gt;，租约偏移 0 表示 lease_until 等于写入语句的 current_timestamp(3)
     */
    private List<Scenario> scenarios()
    {
        return List.of(
                new Scenario("PENDING", "PENDING", null, false, true),
                new Scenario("RETRYING", "RETRYING", null, false, true),
                new Scenario("DEAD_LETTER", "DEAD_LETTER", null, true, true),
                new Scenario("DELIVERING 租约未过期", "DELIVERING", 3600, false, false),
                new Scenario("DELIVERING 租约已过期", "DELIVERING", -1, false, true),
                new Scenario("DELIVERING 等于数据库当前时间", "DELIVERING", 0, false, true),
                new Scenario("PROCESSED", "PROCESSED", null, false, false),
                new Scenario("CANCELLED", "CANCELLED", null, false, false));
    }

    /**
     * 关闭合同持有的数据库会话。
     * @return void，会话关闭后不再访问 Mapper
     */
    @Override
    public void close()
    {
        session.close();
    }

    /**
     * @param name String，断言输出使用的矩阵名称
     * @param status String，正式出站状态
     * @param leaseOffsetSeconds Integer，相对数据库当前时间的租约秒数；null 表示没有租约
     * @param retryAllowed boolean，权威补偿能力
     * @param cancelAllowed boolean，权威取消能力
     */
    private record Scenario(String name, String status, Integer leaseOffsetSeconds,
            boolean retryAllowed, boolean cancelAllowed)
    {
    }
}
