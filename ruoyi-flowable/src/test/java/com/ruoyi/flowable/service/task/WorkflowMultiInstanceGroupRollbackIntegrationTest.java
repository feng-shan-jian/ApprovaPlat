package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 聚焦验证整组退回与重提故障触发的完整事务回滚。
 */
class WorkflowMultiInstanceGroupRollbackIntegrationTest
{
    private WorkflowGroupReturnScenario fixture;
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path attachmentRoot;

    /** 创建当前功能所需的整组迁移夹具。 @return void，无返回值 */
    @BeforeEach
    void setUpFixture()
    {
        fixture = new WorkflowGroupReturnScenario(attachmentRoot);
    }

    /** 显式关闭整组迁移夹具。 @return void，无返回值 */
    @AfterEach
    void closeFixture()
    {
        fixture.close();
    }
    /**
    /**
     * 在引擎整组迁移完成后令 ACTIVE 到 RETURNED 的 CAS 返回零，验证全部引擎和业务事实回滚。
     *
     * @return void，任务、execution、变量、轮次或内部退回标记产生部分提交时失败
     */
    @Test
    void rollsBackWholeReturnWhenReturnedCasLosesRace()
    {
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", List.of("201", "202", "203"));
        Task source = fixture.task(instance.getId(), "firstAllReview", "201");
        WorkflowGroupReturnScenario.GroupTransitionSnapshot before = fixture.captureGroupTransition(instance.getId());
        doReturn(0).when(fixture.roundMapper).compareAndSetReturnedStatus(
                anyLong(), anyInt(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> fixture.returnGroup(source, "201"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(fixture.captureGroupTransition(instance.getId())).isEqualTo(before);
        assertThat(fixture.activeRound(instance.getId(), "firstAllReview")
                .getRoundStatus()).isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
    }

    /**
     * 在整组退回最后的稳定通知注入故障，验证 RETURNED CAS、任务迁移和状态变量一并回滚。
     *
     * @return void，通知失败后留下申请人任务或 RETURNED 行时失败
     */
    @Test
    void rollsBackWholeReturnWhenStableNotificationFails()
    {
        ProcessInstance instance = fixture.startLifecycle("roundGroupLaterAll",
                "laterAllReturnStart", "laterAllReview", List.of("201", "202", "203"));
        fixture.completeOrdinary(instance.getId(), "laterInitialApproval", "150");
        Task source = fixture.task(instance.getId(), "laterAllReview", "202");
        fixture.insertTaskSla(source, "ACTIVE");
        WorkflowGroupReturnScenario.GroupTransitionSnapshot before = fixture.captureGroupTransition(instance.getId());
        fixture.failNextStableNotification();

        assertThatThrownBy(() -> fixture.returnGroup(source, "202"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification failure");

        assertThat(fixture.captureGroupTransition(instance.getId())).isEqualTo(before);
    }

    /**
     * 在旧轮 REOPENED CAS 后令新任务 create 监听器失败，验证旧轮、表单和新根整体回滚。
     *
     * @return void，出现 REOPENED 旧轮、ACTIVE 新轮或表单补丁残留时失败
     */
    @Test
    void rollsBackResubmitWhenNewRoundCreateListenerFails()
    {
        ReturnedScenario scenario = returnFirstAll();
        fixture.insertTaskSla(scenario.applicantTask(), "ACTIVE");
        WorkflowGroupReturnScenario.GroupTransitionSnapshot before = fixture.captureGroupTransition(
                scenario.instance().getId());
        fixture.failNextCreateAudit();

        assertThatThrownBy(() -> fixture.resubmitGroup(
                scenario.applicantTask(), "监听器失败申请"))
                .isInstanceOf(ServiceException.class)
                .hasRootCauseMessage("injected task create audit failure");

        assertThat(fixture.captureGroupTransition(scenario.instance().getId())).isEqualTo(before);
        assertThat(fixture.rounds(scenario.instance().getId())).singleElement()
                .extracting(round -> round.getRoundStatus())
                .isEqualTo(WorkflowMultiInstanceRoundStatus.RETURNED);
    }

    /**
     * 精确令开始表单提交快照的 fixture.runtimeService.setVariable 失败，验证表单 patch、
     * 申请人任务、execution 和 RETURNED 轮次全部回滚，不把该异常伪称为附件失败。
     *
     * @return void，提交快照故障后任一表单或流程事实发生变化时失败
     */
    @Test
    void rollsBackResubmitWhenSubmissionSnapshotWriteFails()
    {
        ReturnedScenario scenario = returnFirstAll();
        WorkflowGroupReturnScenario.GroupTransitionSnapshot before = fixture.captureGroupTransition(
                scenario.instance().getId());
        fixture.failNextSubmissionSnapshotWrite();

        assertThatThrownBy(() -> fixture.resubmitGroup(
                scenario.applicantTask(), "提交快照失败申请"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submission snapshot write failure");

        assertThat(fixture.captureGroupTransition(scenario.instance().getId())).isEqualTo(before);
        fixture.assertDoubleStatus(scenario.instance().getId(),
                WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
    }

    /**
     * 用正式附件 Mapper、生产附件服务和私有存储完成 TEMP→BOUND，随后在新轮 create
     * 监听器注入故障，验证附件行以及任务、execution、变量和轮次在同一事务中回滚。
     *
     * @return void，附件保持 BOUND、表单快照残留或流程事实产生部分提交时失败
     */
    @Test
    void rollsBackBoundAttachmentWhenLaterCreateListenerFails()
    {
        String attachmentId = fixture.insertTemporaryAttachment();
        ReturnedScenario scenario = returnFirstAll();
        WorkflowGroupReturnScenario.GroupTransitionSnapshot before = fixture.captureGroupTransition(
                scenario.instance().getId());
        WfAttachment temporary = fixture.attachmentMapper.selectById(attachmentId);
        assertThat(temporary.status()).isEqualTo(WorkflowAttachmentStatus.TEMP);
        fixture.failNextCreateAudit();

        assertThatThrownBy(() -> fixture.resubmitGroup(scenario.applicantTask(), Map.of(
                "requestTitle", "附件事务回滚申请",
                "evidence", List.of(attachmentId))))
                .isInstanceOf(ServiceException.class)
                .hasRootCauseMessage("injected task create audit failure");

        verify(fixture.attachmentMapper).bindTaskAttachment(attachmentId,
                Long.valueOf(fixture.APPLICANT_ID), "evidence", scenario.instance().getId(),
                scenario.applicantTask().getId(), "firstAllReturnStart");
        WfAttachment rolledBack = fixture.attachmentMapper.selectById(attachmentId);
        assertThat(rolledBack.status()).isEqualTo(WorkflowAttachmentStatus.TEMP);
        assertThat(rolledBack.processInstanceId()).isNull();
        assertThat(rolledBack.taskId()).isNull();
        assertThat(rolledBack.nodeKey()).isNull();
        assertThat(rolledBack.boundTime()).isNull();
        assertThat(fixture.captureGroupTransition(scenario.instance().getId())).isEqualTo(before);
        fixture.assertDoubleStatus(scenario.instance().getId(),
                WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
    }
    /**
     * 创建并提交一个首节点 ALL 的 RETURNED 场景，供多种重提故障从同一正式状态开始。
     *
     * @return ReturnedScenario，活动实例与唯一申请人任务
     */
    private ReturnedScenario returnFirstAll()
    {
        ProcessInstance instance = fixture.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview", List.of("201", "202", "203"));
        Task source = fixture.task(instance.getId(), "firstAllReview", "201");
        fixture.returnGroup(source, "201");
        return new ReturnedScenario(instance, fixture.returnedTask(instance.getId()));
    }

    /**
     * 重提回滚夹具。
     *
     * @param instance ProcessInstance，RETURNED 活动流程
     * @param applicantTask Task，唯一发起人待修改任务
     */
    private record ReturnedScenario(ProcessInstance instance, Task applicantTask)
    {
    }
}
