package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 使用真实事务、Flowable 和 H2 Mapper 验证整组退回与重提的原子回滚。
 */
@SpringJUnitConfig(WorkflowMultiInstanceEngineHarness.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class WorkflowMultiInstanceGroupRollbackIntegrationTest
{
    /** 只调用生产公开入口的业务驱动。 */
    @Autowired
    private WorkflowMultiInstanceBusinessDriver driver;

    /** 只读取跨引擎和业务表事实的状态探针。 */
    @Autowired
    private WorkflowMultiInstanceStateProbe probe;

    /** 只负责精确 CAS 和监听器故障注入。 */
    @Autowired
    private WorkflowMultiInstanceFailureHook failureHook;

    /**
     * 验证 ACTIVE 到 RETURNED 的 Mapper CAS 失败会回滚引擎迁移和全部变量写入。
     *
     * @return void，CAS 冲突未使用 409 或任一核心事实部分提交时失败
     */
    @Test
    void rollsBackWholeReturnWhenReturnedCasLosesRace()
    {
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview",
                List.of("201", "202", "203"));
        Task source = probe.task(instance.getId(), "firstAllReview", "201");
        WorkflowMultiInstanceStateProbe.GroupSnapshot before =
                probe.captureGroup(instance.getId());
        failureHook.loseReturnedCas();

        assertThatThrownBy(() -> driver.returnGroup(source, "201"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(probe.captureGroup(instance.getId())).isEqualTo(before);
        assertThat(probe.activeRound(instance.getId(), "firstAllReview")
                .getRoundStatus()).isEqualTo(WorkflowMultiInstanceRoundStatus.ACTIVE);
    }

    /**
     * 验证重提已绑定附件后新轮 create 监听器失败，会跨 Flowable、轮次和附件表整体回滚。
     *
     * @return void，附件未恢复 TEMP 或重提核心事实产生部分提交时失败
     */
    @Test
    void rollsBackBoundAttachmentWhenLaterCreateListenerFails()
    {
        String attachmentId = driver.uploadTemporaryAttachment();
        ProcessInstance instance = driver.startLifecycle("roundGroupFirstAll",
                "firstAllReturnStart", "firstAllReview",
                List.of("201", "202", "203"));
        driver.returnGroup(probe.task(instance.getId(), "firstAllReview", "201"), "201");
        Task applicantTask = probe.returnedTask(instance.getId());
        WorkflowMultiInstanceStateProbe.GroupSnapshot before =
                probe.captureGroup(instance.getId());
        failureHook.failNextCreateAudit();

        assertThatThrownBy(() -> driver.resubmit(applicantTask, Map.of(
                "requestTitle", "附件事务回滚申请",
                "evidence", List.of(attachmentId))))
                .isInstanceOf(ServiceException.class)
                .hasRootCauseMessage("injected task create audit failure");

        WfAttachment rolledBack = probe.attachment(attachmentId);
        assertThat(rolledBack.status()).isEqualTo(WorkflowAttachmentStatus.TEMP);
        assertThat(rolledBack.processInstanceId()).isNull();
        assertThat(rolledBack.taskId()).isNull();
        assertThat(rolledBack.nodeKey()).isNull();
        assertThat(rolledBack.boundTime()).isNull();
        assertThat(probe.captureGroup(instance.getId())).isEqualTo(before);
        probe.assertDoubleStatus(instance.getId(),
                WorkflowReturnedApplicationProtocol.RETURNED_STATUS);
    }
}
