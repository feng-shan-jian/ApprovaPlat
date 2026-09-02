package com.ruoyi.flowable.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowCopyQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowManualUrgeView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessCommentView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessViewerView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 验证页面使用的工作流 HTTP DTO 与 VO 不再暴露无调用方字段。
 */
class WorkflowHttpProtocolContractTest
{
    /**
     * 验证抄送查询只接收页面真实发送的五个条件。
     *
     * @return void，记录组件新增或缺失时测试失败
     */
    @Test
    void copyQueryContainsOnlyPageFilters()
    {
        assertThat(componentNames(WorkflowCopyQueryDto.class)).containsExactlyInAnyOrder(
                "title", "processName", "originatorName", "categoryId", "readStatus");
    }

    /**
     * 验证人工催办 HTTP 响应只暴露页面读取的真实收件人数。
     *
     * @return void，响应记录重新暴露内部事件键或通道数时测试失败
     */
    @Test
    void manualUrgeResponseContainsOnlyRecipientCount()
    {
        assertThat(componentNames(WorkflowManualUrgeView.class))
                .containsExactlyInAnyOrder("recipientCount");
    }

    /**
     * 验证流程意见 HTTP 投影只包含可展示 opinion，不暴露原始正文或审计字段。
     *
     * @return void，record 或 JSON 重新出现 message、audit 时测试失败
     */
    @Test
    void processCommentContainsOnlySafeOpinionProjection()
    {
        String[] expectedFields = { "commentId", "taskId", "type", "typeName",
                "opinion", "userId", "time" };
        assertThat(componentNames(WorkflowProcessCommentView.class))
                .containsExactlyInAnyOrder(expectedFields)
                .doesNotContain("message", "audit");

        WorkflowProcessCommentView comment = new WorkflowProcessCommentView(
                "comment", "task", "1", "通过", "可展示意见", "actor",
                Instant.EPOCH);
        JsonNode json = JsonMapper.shared().valueToTree(comment);
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(expectedFields);
        assertThat(json.has("message")).isFalse();
        assertThat(json.has("audit")).isFalse();
    }

    /**
     * 验证流程详情重提能力是不可空的原始 boolean，JSON 只输出布尔字面量。
     *
     * @return void，字段类型或 JSON 协议退化为 null、数字或字符串时测试失败
     */
    @Test
    void processDetailResubmitCapabilityUsesStrictBooleanProtocol()
    {
        RecordComponent component = Arrays.stream(
                WorkflowProcessDetailView.class.getRecordComponents())
                .filter(candidate -> "resubmitAllowed".equals(candidate.getName()))
                .findFirst().orElseThrow();
        assertThat(component.getType()).isEqualTo(boolean.class);

        WorkflowTaskAccessSnapshot activeTask = taskSnapshot(true);
        JsonNode allowed = JsonMapper.shared().valueToTree(
                processDetail("returned", activeTask, true))
                .path("resubmitAllowed");
        JsonNode disallowed = JsonMapper.shared().valueToTree(
                processDetail("running", activeTask, false))
                .path("resubmitAllowed");

        assertThat(allowed.isBoolean()).isTrue();
        assertThat(allowed.booleanValue()).isTrue();
        assertThat(disallowed.isBoolean()).isTrue();
        assertThat(disallowed.booleanValue()).isFalse();
    }

    /**
     * 验证详情构造器拒绝把 true 绑定到无任务、历史任务或非 returned 流程。
     *
     * @return void，任一矛盾 capability 快照能够构造时测试失败
     */
    @Test
    void processDetailRejectsContradictoryResubmitCapability()
    {
        assertThatThrownBy(() -> processDetail("returned", null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "returned", taskSnapshot(false), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "running", taskSnapshot(true), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 创建协议测试使用的最小流程详情快照。
     *
     * @param processStatus String，待验证的稳定流程状态
     * @param currentTask WorkflowTaskAccessSnapshot，可选活动或历史任务
     * @param resubmitAllowed boolean，待验证的重提能力值
     * @return WorkflowProcessDetailView，可直接交给 Jackson 序列化的详情快照
     */
    private WorkflowProcessDetailView processDetail(String processStatus,
            WorkflowTaskAccessSnapshot currentTask, boolean resubmitAllowed)
    {
        return new WorkflowProcessDetailView("instance", "definition", "process",
                "流程", 1, "category", "deployment", null, "applicant",
                "申请人", Instant.EPOCH, null, null, processStatus, currentTask,
                "DISABLED", null, false, resubmitAllowed, List.of(), null,
                List.of(), List.of(), List.of(), "<definitions/>",
                new WorkflowProcessViewerView(Set.of(), Set.of(), Set.of(),
                        Set.of(), Set.of()));
    }

    /**
     * 创建协议测试使用的活动或历史任务快照。
     *
     * @param active boolean，任务是否仍处于运行时活动态
     * @return WorkflowTaskAccessSnapshot，字段关系一致的最小任务快照
     */
    private WorkflowTaskAccessSnapshot taskSnapshot(boolean active)
    {
        return new WorkflowTaskAccessSnapshot("task", "instance", "definition",
                "approval", "审批", "applicant", null, null, active,
                Instant.EPOCH, active ? null : Instant.EPOCH, null, null, false);
    }

    /**
     * 读取 Java record 的字段名，用于校验默认 HTTP 绑定与序列化的字段集合。
     *
     * @param recordType Class&lt;?&gt;，待检查的 HTTP record 类型
     * @return String[]，record 组件名称数组
     */
    private String[] componentNames(Class<?> recordType)
    {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toArray(String[]::new);
    }
}
