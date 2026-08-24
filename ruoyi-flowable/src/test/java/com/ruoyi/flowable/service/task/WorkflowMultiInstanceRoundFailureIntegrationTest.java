package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Map;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfMultiInstanceRound;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 使用共享真实引擎和正式 Mapper 验证 ANY 完成、双向事务回滚及篡改失败关闭。
 */
class WorkflowMultiInstanceRoundFailureIntegrationTest
{
    private WorkflowMultiInstanceRoundScenario fixture;
    private org.flowable.engine.RuntimeService runtimeService;
    private org.flowable.engine.TaskService taskService;
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper roundMapper;
    private WorkflowMultiInstanceService multiInstanceService;

    /** 创建并公开本测试实际使用的轮次依赖。 @return void，无返回值 */
    @BeforeEach
    void setUpFixture()
    {
        fixture = new WorkflowMultiInstanceRoundScenario();
        runtimeService = fixture.runtimeService;
        taskService = fixture.taskService;
        jdbcTemplate = fixture.jdbcTemplate;
        transactionTemplate = fixture.transactionTemplate;
        roundMapper = fixture.roundMapper;
        multiInstanceService = fixture.multiInstanceService;
    }

    /** 显式关闭轮次夹具。 @return void，无返回值 */
    @AfterEach
    void closeFixture()
    {
        fixture.close();
    }
    /**
     * 验证 ANY 任一成员完成即关闭轮次，直达 EndEvent 时不要求已删除的运行时变量仍存在。
     *
     * @return void，流程、旧根、活动任务或轮次终态未同时结束时失败
     */
    @Test
    void completesAnyRoundAndFinishedProcessAtomically()
    {
        ProcessInstance instance = fixture.start("roundDynamicAny", "anyReview",
                List.of("201", "202", "203"), Map.of());

        fixture.complete(fixture.task(instance.getId(), "anyReview", "201"), 0);

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()).isNull();
        assertThat(taskService.createTaskQuery().processInstanceId(instance.getId())
                .active().count()).isZero();
        assertThat(fixture.rounds(instance.getId())).singleElement().satisfies(round ->
        {
            assertThat(round.getRoundStatus())
                    .isEqualTo(WorkflowMultiInstanceRoundStatus.COMPLETED);
            assertThat(round.getRevisionNo()).isEqualTo(1);
            assertThat(round.getMembers())
                    .containsExactly("201", "202", "203");
            assertThat(round.getCompleteTime()).isNotNull();
        });
    }

    /**
     * 在首轮 insert 注入 Mapper 故障，验证任务、execution、变量和轮次全部不提交。
     *
     * @return void，Flowable 启动事实或业务轮次任一残留时失败
     */
    @Test
    void rollsBackFlowableStartWhenRoundInsertFails()
    {
        doThrow(new IllegalStateException("injected round insert failure"))
                .when(roundMapper).insert(any(WfMultiInstanceRound.class));

        assertThatThrownBy(() -> fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected round insert failure");

        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
        assertThat(runtimeService.createExecutionQuery().count()).isZero();
        assertThat(runtimeService.createVariableInstanceQuery().count()).isZero();
        assertThat(taskService.createTaskQuery().count()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wf_multi_instance_round", Long.class)).isZero();
    }

    /**
     * 在 Flowable complete 监听后段注入审计故障，验证引擎完成和业务终态更新整体回滚。
     *
     * @return void，任务、execution、变量或轮次 revision/status 发生部分提交时失败
     */
    @Test
    void rollsBackRoundUpdateWhenFlowableCompletionListenerFails()
    {
        ProcessInstance instance = fixture.start("roundDynamicAny", "anyReview",
                fixture.MEMBERS, Map.of());
        WorkflowMultiInstanceRoundScenario.CoreRuntimeSnapshot before =
                fixture.captureCore(instance.getId());
        fixture.failNextCompleteAudit();

        assertThatThrownBy(() -> fixture.complete(
                fixture.task(instance.getId(), "anyReview", "201"), 0))
                .isInstanceOf(FlowableException.class)
                .hasMessageContaining(fixture.AUDIT_FAILURE_MESSAGE);

        assertThat(fixture.captureCore(instance.getId())).isEqualTo(before);
        WfMultiInstanceRound round = fixture.activeRound(instance.getId(), "anyReview");
        assertThat(round.getRevisionNo()).isZero();
        assertThat(round.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
    }

    /**
     * 在 Flowable revision 推进后令业务 CAS 返回零，验证稳定 409 和完整事务回滚。
     *
     * @return void，成员变量、task-local 标记、execution 或轮次任一发生部分提交时失败
     */
    @Test
    void rollsBackEngineRevisionWhenRoundCasFails()
    {
        ProcessInstance instance = fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of());
        Task current = fixture.task(instance.getId(), "dynamicReview", "201");
        WorkflowMultiInstanceRoundScenario.CoreRuntimeSnapshot before =
                fixture.captureCore(instance.getId());
        doReturn(0).when(roundMapper).compareAndSetActiveSnapshot(
                        anyLong(), anyInt(), anyInt(), anyString());

        assertThatThrownBy(() -> fixture.addMember(current, 0, 203L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getSubCode()).isEqualTo(
                            WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
                });

        assertThat(fixture.captureCore(instance.getId())).isEqualTo(before);
    }

    /**
     * 绕过正式预留链直接调用 Flowable complete，验证监听器拒绝缺失 task-local revision 标记。
     *
     * @return void，直接完成未失败关闭或任务、execution、变量、轮次任一发生变化时失败
     */
    @Test
    void rejectsDirectFlowableCompletionWithoutReservedRevision()
    {
        ProcessInstance instance = fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of());
        Task current = fixture.task(instance.getId(), "dynamicReview", "201");
        WorkflowMultiInstanceRoundScenario.CoreRuntimeSnapshot before =
                fixture.captureCore(instance.getId());

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                status -> taskService.complete(current.getId())))
                .satisfies(this::assertNestedDataError);

        assertThat(fixture.captureCore(instance.getId())).isEqualTo(before);
    }

    /**
     * 篡改有序成员 JSON 后验证正式读取和加签写入均失败关闭且不改变引擎状态。
     *
     * @return void，读取被静默接受或写动作产生 execution/revision 副作用时失败
     */
    @Test
    void rejectsTamperedMemberSnapshotOnReadAndWrite()
    {
        ProcessInstance instance = fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of());
        Task current = fixture.task(instance.getId(), "dynamicReview", "201");
        jdbcTemplate.update("""
                update wf_multi_instance_round
                set members_json='["202","201"]'
                where process_instance_id=? and activity_id='dynamicReview'
                """, instance.getId());
        WorkflowMultiInstanceRoundScenario.CoreRuntimeSnapshot tampered =
                fixture.captureCore(instance.getId());

        assertDataError(() -> transactionTemplate.execute(
                status -> multiInstanceService.getState(current.getId())));
        assertDataError(() -> fixture.addMember(current, 0, 203L));

        assertThat(fixture.captureCore(instance.getId())).isEqualTo(tampered);
    }

    /**
     * 篡改 ACTIVE 状态不允许出现的退回关联字段，验证领域生命周期异常被稳定包装为 HTTP 500。
     *
     * @return void，IllegalStateException 泄漏或读写动作改变任一引擎、变量、轮次事实时失败
     */
    @Test
    void rejectsTamperedLifecycleFieldsOnReadAndWrite()
    {
        ProcessInstance instance = fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of());
        Task current = fixture.task(instance.getId(), "dynamicReview", "201");
        jdbcTemplate.update("""
                update wf_multi_instance_round
                set return_source_task_id='tampered-task'
                where process_instance_id=? and activity_id='dynamicReview'
                """, instance.getId());
        WorkflowMultiInstanceRoundScenario.CoreRuntimeSnapshot tampered =
                fixture.captureCore(instance.getId());

        assertDataError(() -> transactionTemplate.execute(
                status -> multiInstanceService.getState(current.getId())));
        assertDataError(() -> fixture.addMember(current, 0, 203L));

        assertThat(fixture.captureCore(instance.getId())).isEqualTo(tampered);
        assertThat(fixture.activeRound(instance.getId(), "dynamicReview")
                .getReturnSourceTaskId()).isEqualTo("tampered-task");
    }

    /**
     * 同时篡改 Flowable 模式变量和业务行时仍以部署 BPMN 模式为准失败关闭。
     *
     * @return void，变量与业务表成对漂移被误认为合法状态时失败
     */
    @Test
    void rejectsModeDriftEvenWhenEngineVariableAndRoundAgree()
    {
        ProcessInstance instance = fixture.start("roundDynamicAll", "dynamicReview",
                fixture.MEMBERS, Map.of());
        Task current = fixture.task(instance.getId(), "dynamicReview", "201");
        transactionTemplate.executeWithoutResult(status ->
        {
            runtimeService.setVariable(instance.getId(),
                    WorkflowMultiInstanceVariables.modeName("dynamicReview"), "ANY");
            jdbcTemplate.update("""
                    update wf_multi_instance_round set mode='ANY'
                    where process_instance_id=? and activity_id='dynamicReview'
                    """, instance.getId());
        });
        WorkflowMultiInstanceRoundScenario.CoreRuntimeSnapshot tampered =
                fixture.captureCore(instance.getId());

        assertDataError(() -> transactionTemplate.execute(
                status -> multiInstanceService.getState(current.getId())));
        assertDataError(() -> fixture.addMember(current, 0, 203L));

        assertThat(fixture.captureCore(instance.getId())).isEqualTo(tampered);
    }

    /**
     * 断言动作返回稳定 HTTP 500 多实例轮次数据异常。
     *
     * @param action Runnable，正式读取或写入动作
     * @return void，异常类型、状态码或稳定消息不一致时失败
     */
    private void assertDataError(Runnable action)
    {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage())
                            .contains("工作流多实例")
                            .contains("状态不一致");
                });
    }

    /**
     * 从 Flowable 命令包装异常中定位稳定的服务端数据异常。
     *
     * @param failure Throwable，直接完成命令抛出的异常链
     * @return void，异常链不含 HTTP 500 的稳定多实例状态异常时失败
     */
    private void assertNestedDataError(Throwable failure)
    {
        Throwable current = failure;
        while (current != null && !(current instanceof ServiceException))
        {
            current = current.getCause();
        }
        assertThat(current).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
            assertThat(exception.getMessage())
                    .contains("工作流多实例")
                    .contains("状态不一致");
        });
    }
}
