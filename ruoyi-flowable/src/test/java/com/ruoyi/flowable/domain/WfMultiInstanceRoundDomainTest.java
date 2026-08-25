package com.ruoyi.flowable.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例轮次领域对象的成员快照和生命周期门禁。
 */
class WfMultiInstanceRoundDomainTest
{
    /**
     * 验证成员 JSON 编码保留引擎顺序，解码结果不可变。
     *
     * @return void，断言成员快照有序往返
     */
    @Test
    void shouldEncodeAndDecodeOrderedMembers()
    {
        String encoded = WfMultiInstanceRound.encodeMembers(
                List.of("9223372036854775807", "7", "42"));

        assertEquals("[\"9223372036854775807\",\"7\",\"42\"]", encoded);
        List<String> decoded = WfMultiInstanceRound.decodeMembers(encoded);
        assertEquals(List.of("9223372036854775807", "7", "42"), decoded);
        assertThrows(UnsupportedOperationException.class, () -> decoded.add("8"));
    }

    /**
     * 验证严格解码拒绝非数组、非文本成员、尾随内容和空数组。
     *
     * @return void，断言结构损坏时全部失败关闭
     */
    @Test
    void shouldRejectMalformedMemberSnapshots()
    {
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.decodeMembers("{}"));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.decodeMembers("[1]"));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.decodeMembers("[\"1\"] trailing"));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.decodeMembers("[]"));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.decodeMembers(null));
    }

    /**
     * 验证用户主键必须规范、不重复且数量不超过 100。
     *
     * @return void，断言非法成员和重复成员均被拒绝
     */
    @Test
    void shouldRejectInvalidOrDuplicateMembers()
    {
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.encodeMembers(List.of("0")));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.encodeMembers(List.of("01")));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.encodeMembers(List.of("9223372036854775808")));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.encodeMembers(List.of("1", "1")));

        List<String> tooMany = new ArrayList<>();
        for (int index = 1; index <= 101; index++)
        {
            tooMany.add(Integer.toString(index));
        }
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.encodeMembers(tooMany));
    }

    /**
     * 验证修订号只能位于 Java Integer 非负区间。
     *
     * @return void，断言边界值通过且越界值被拒绝
     */
    @Test
    void shouldValidateRevisionBounds()
    {
        assertEquals(0, WfMultiInstanceRound.requireRevision(0));
        assertEquals(Integer.MAX_VALUE,
                WfMultiInstanceRound.requireRevision(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.requireRevision(-1));
        assertThrows(IllegalArgumentException.class,
                () -> WfMultiInstanceRound.requireRevision((long) Integer.MAX_VALUE + 1));
    }

    /**
     * 验证状态解析和开放状态语义。
     *
     * @return void，断言 ACTIVE/RETURNED 开放语义和严格文本解析
     */
    @Test
    void shouldEnforceStatusTransitions()
    {
        assertEquals(WorkflowMultiInstanceRoundStatus.ACTIVE,
                WorkflowMultiInstanceRoundStatus.require("ACTIVE"));
        assertTrue(WorkflowMultiInstanceRoundStatus.ACTIVE.isOpen());
        assertTrue(WorkflowMultiInstanceRoundStatus.RETURNED.isOpen());
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowMultiInstanceRoundStatus.require("completed"));
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowMultiInstanceRoundStatus.require("CANCELLED"));
    }

    /**
     * 验证五种持久化状态的完整字段和时间组合。
     *
     * @return void，断言正常、退回、重开和完成记录均可校验
     */
    @Test
    void shouldAcceptEveryValidLifecycleCombination()
    {
        WfMultiInstanceRound active = activeRound();
        active.requireValidLifecycle();

        WfMultiInstanceRound returned = returnedRound();
        returned.requireValidLifecycle();

        WfMultiInstanceRound reopened = returnedRound();
        reopened.setRoundStatus(WorkflowMultiInstanceRoundStatus.REOPENED);
        reopened.setReopenTime(reopened.getReturnTime().plusMinutes(1));
        reopened.requireValidLifecycle();

        WfMultiInstanceRound completed = activeRound();
        completed.setRoundStatus(WorkflowMultiInstanceRoundStatus.COMPLETED);
        completed.setCompleteTime(completed.getCreateTime().plusMinutes(5));
        completed.requireValidLifecycle();

        WfMultiInstanceRound terminatedActive = activeRound();
        terminatedActive.setRoundStatus(WorkflowMultiInstanceRoundStatus.TERMINATED);
        terminatedActive.setTerminateTime(
                terminatedActive.getCreateTime().plusMinutes(2));
        terminatedActive.requireValidLifecycle();

        WfMultiInstanceRound terminatedReturned = returnedRound();
        terminatedReturned.setRoundStatus(WorkflowMultiInstanceRoundStatus.TERMINATED);
        terminatedReturned.setTerminateTime(
                terminatedReturned.getReturnTime().plusMinutes(2));
        terminatedReturned.requireValidLifecycle();
    }

    /**
     * 验证状态、退回关联、模式和时间任一漂移都会失败关闭。
     *
     * @return void，断言不完整或倒置的生命周期组合被拒绝
     */
    @Test
    void shouldRejectInvalidLifecycleCombinations()
    {
        WfMultiInstanceRound activeWithCompletion = activeRound();
        activeWithCompletion.setCompleteTime(activeWithCompletion.getCreateTime().plusSeconds(1));
        assertThrows(IllegalStateException.class, activeWithCompletion::requireValidLifecycle);

        WfMultiInstanceRound incompleteReturn = activeRound();
        incompleteReturn.setRoundStatus(WorkflowMultiInstanceRoundStatus.RETURNED);
        incompleteReturn.setReturnTime(incompleteReturn.getCreateTime().plusSeconds(1));
        assertThrows(IllegalStateException.class, incompleteReturn::requireValidLifecycle);

        WfMultiInstanceRound invalidActor = returnedRound();
        invalidActor.setReturnActorUserId("9223372036854775808");
        assertThrows(IllegalStateException.class, invalidActor::requireValidLifecycle);

        WfMultiInstanceRound reversedTime = activeRound();
        reversedTime.setRoundStatus(WorkflowMultiInstanceRoundStatus.COMPLETED);
        reversedTime.setCompleteTime(reversedTime.getCreateTime().minusSeconds(1));
        assertThrows(IllegalStateException.class, reversedTime::requireValidLifecycle);

        WfMultiInstanceRound invalidMode = activeRound();
        invalidMode.setMode("SERIAL");
        assertThrows(IllegalStateException.class, invalidMode::requireValidLifecycle);

        WfMultiInstanceRound invalidRevision = activeRound();
        invalidRevision.setRevisionNo(-1);
        assertThrows(IllegalStateException.class, invalidRevision::requireValidLifecycle);

        WfMultiInstanceRound terminatedWithoutTime = activeRound();
        terminatedWithoutTime.setRoundStatus(
                WorkflowMultiInstanceRoundStatus.TERMINATED);
        assertThrows(IllegalStateException.class,
                terminatedWithoutTime::requireValidLifecycle);

        WfMultiInstanceRound terminatedBeforeReturn = returnedRound();
        terminatedBeforeReturn.setRoundStatus(
                WorkflowMultiInstanceRoundStatus.TERMINATED);
        terminatedBeforeReturn.setTerminateTime(
                terminatedBeforeReturn.getReturnTime().minusSeconds(1));
        assertThrows(IllegalStateException.class,
                terminatedBeforeReturn::requireValidLifecycle);
    }

    /**
     * 构造所有引擎关联完整的 ACTIVE 领域对象。
     *
     * @return WfMultiInstanceRound，可直接通过领域校验的第 1 轮
     */
    private WfMultiInstanceRound activeRound()
    {
        WfMultiInstanceRound round = new WfMultiInstanceRound();
        round.setDeployId("deployment-1");
        round.setProcessDefinitionId("approval:1:definition");
        round.setProcessInstanceId("process-1");
        round.setActivityId("approveTask");
        round.setRootExecutionId("root-execution-1");
        round.setRoundNo(1);
        round.setMode("ALL");
        round.setMembers(List.of("7", "42"));
        round.setRevisionNo(0);
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.ACTIVE);
        round.setCreateTime(LocalDateTime.of(2026, 8, 23, 9, 0));
        return round;
    }

    /**
     * 构造具备完整整组退回关联的 RETURNED 领域对象。
     *
     * @return WfMultiInstanceRound，可直接通过领域校验的退回轮次
     */
    private WfMultiInstanceRound returnedRound()
    {
        WfMultiInstanceRound round = activeRound();
        round.setRoundStatus(WorkflowMultiInstanceRoundStatus.RETURNED);
        round.setReturnSourceTaskId("task-return-source");
        round.setReturnActorUserId("9223372036854775807");
        round.setApplicantTaskId("task-applicant");
        round.setReturnTime(round.getCreateTime().plusMinutes(3));
        return round;
    }
}
