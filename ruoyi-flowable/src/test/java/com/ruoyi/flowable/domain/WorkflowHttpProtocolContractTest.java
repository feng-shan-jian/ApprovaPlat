package com.ruoyi.flowable.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;
import com.ruoyi.flowable.domain.dto.WorkflowCopyQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowManualUrgeView;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceMemberView;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessCommentView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessViewerView;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
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
                processDetail("returned", activeTask, true, false))
                .path("resubmitAllowed");
        JsonNode disallowed = JsonMapper.shared().valueToTree(
                processDetail("running", activeTask, false, false))
                .path("resubmitAllowed");

        assertThat(allowed.isBoolean()).isTrue();
        assertThat(allowed.booleanValue()).isTrue();
        assertThat(disallowed.isBoolean()).isTrue();
        assertThat(disallowed.booleanValue()).isFalse();
    }

    /**
     * 验证流程详情催办能力是不可空的原始 boolean，JSON 稳定输出 true 和 false。
     *
     * @return void，字段类型或任一布尔字面量序列化漂移时测试失败
     */
    @Test
    void processDetailUrgeCapabilityUsesStrictBooleanProtocol()
    {
        RecordComponent component = Arrays.stream(
                WorkflowProcessDetailView.class.getRecordComponents())
                .filter(candidate -> "urgeAllowed".equals(candidate.getName()))
                .findFirst().orElseThrow();
        assertThat(component.getType()).isEqualTo(boolean.class);

        JsonNode allowed = JsonMapper.shared().valueToTree(
                processDetail("running", null, false, true))
                .path("urgeAllowed");
        JsonNode disallowed = JsonMapper.shared().valueToTree(
                processDetail("completed", null, false, false))
                .path("urgeAllowed");

        assertThat(allowed.isBoolean()).isTrue();
        assertThat(allowed.booleanValue()).isTrue();
        assertThat(disallowed.isBoolean()).isTrue();
        assertThat(disallowed.booleanValue()).isFalse();
    }

    /**
     * 验证既有退回能力继续使用不可空原始 boolean，字段名和 JSON 类型保持不变。
     *
     * @return void，returnAllowed 类型或序列化协议漂移时测试失败
     */
    @Test
    void processDetailReturnCapabilityKeepsStrictBooleanProtocol()
    {
        RecordComponent component = Arrays.stream(
                WorkflowProcessDetailView.class.getRecordComponents())
                .filter(candidate -> "returnAllowed".equals(candidate.getName()))
                .findFirst().orElseThrow();
        assertThat(component.getType()).isEqualTo(boolean.class);

        JsonNode json = JsonMapper.shared().valueToTree(
                processDetail("running", taskSnapshot(true), false, false));
        assertThat(json.path("returnAllowed").isBoolean()).isTrue();
        assertThat(json.path("returnAllowed").booleanValue()).isFalse();
        assertThat(json.path("resubmitAllowed").isBoolean()).isTrue();
        assertThat(json.path("urgeAllowed").isBoolean()).isTrue();
    }

    /**
     * 验证多实例调整、加签和剩余容量使用固定原始类型及 JSON 字面量。
     *
     * @return void，字段类型、名称或 JSON 类型漂移时测试失败
     */
    @Test
    void multiInstanceCapabilitiesUseStrictPrimitiveProtocol()
    {
        RecordComponent adjustmentAllowed = component(
                WorkflowMultiInstanceStateView.class, "adjustmentAllowed");
        RecordComponent addAllowed = component(
                WorkflowMultiInstanceStateView.class, "addAllowed");
        RecordComponent remainingCapacity = component(
                WorkflowMultiInstanceStateView.class, "remainingCapacity");
        assertThat(adjustmentAllowed.getType()).isEqualTo(boolean.class);
        assertThat(addAllowed.getType()).isEqualTo(boolean.class);
        assertThat(remainingCapacity.getType()).isEqualTo(int.class);

        WorkflowMultiInstanceMemberView member = new WorkflowMultiInstanceMemberView(
                2L, "成员", "task-2", "execution-2", true, false);
        int expectedRemainingCapacity = WorkflowUserSelectionValidator.MAX_SELECTED_USERS
                - 1;
        WorkflowMultiInstanceStateView state = new WorkflowMultiInstanceStateView(
                "ALL", "approval", 7L, true, true,
                expectedRemainingCapacity, List.of(member));
        JsonNode json = JsonMapper.shared().valueToTree(state);
        assertThat(json.path("adjustmentAllowed").isBoolean()).isTrue();
        assertThat(json.path("adjustmentAllowed").booleanValue()).isTrue();
        assertThat(json.path("addAllowed").isBoolean()).isTrue();
        assertThat(json.path("addAllowed").booleanValue()).isTrue();
        assertThat(json.path("remainingCapacity").isNumber()).isTrue();
        assertThat(json.path("remainingCapacity").intValue())
                .isEqualTo(expectedRemainingCapacity);
    }

    /**
     * 验证多实例状态拒绝空成员、容量与成员数量不一致以及成员数量超过正式上限。
     *
     * @return void，任一矛盾成员容量组合能够构造时测试失败
     */
    @Test
    void multiInstanceStateRejectsContradictoryMemberCapacity()
    {
        WorkflowMultiInstanceMemberView removable = new WorkflowMultiInstanceMemberView(
                2L, "成员", "task-2", "execution-2", true, true);

        assertThatThrownBy(() -> new WorkflowMultiInstanceStateView(
                "ALL", "approval", 7L, false, false,
                WorkflowUserSelectionValidator.MAX_SELECTED_USERS, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("多实例成员状态不能为空");
        assertThatThrownBy(() -> new WorkflowMultiInstanceStateView(
                "ALL", "approval", 7L, true, false, 0, List.of(removable)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("多实例剩余容量与成员数量矛盾");
        assertThatThrownBy(() -> new WorkflowMultiInstanceStateView(
                "ALL", "approval", 7L, true, false, -1,
                java.util.Collections.nCopies(
                        WorkflowUserSelectionValidator.MAX_SELECTED_USERS + 1,
                        removable)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("多实例成员数量超过上限");
    }

    /**
     * 验证 formDetail 始终序列化为对象，四个正式字段名称与 JSON 类型保持稳定。
     *
     * @return void，详情缺少对象、字段名或字段类型发生漂移时测试失败
     */
    @Test
    void processFormDetailUsesStableObjectProtocol()
    {
        List<WorkflowProcessFormSnapshotView> mutableHistory = new ArrayList<>();
        mutableHistory.add(formSnapshot("history-task"));
        WorkflowProcessFormDetailView formDetail = new WorkflowProcessFormDetailView(
                formSnapshot(null), formSnapshot("current-task"),
                mutableHistory, false);
        mutableHistory.clear();
        assertThat(componentNames(WorkflowProcessFormDetailView.class))
                .containsExactlyInAnyOrder("applicationForm", "nodeTaskForm",
                        "historyForms", "returnedApplication");

        JsonNode detailJson = JsonMapper.shared().valueToTree(formDetail);
        assertThat(detailJson.path("applicationForm").isObject()).isTrue();
        assertThat(detailJson.path("nodeTaskForm").isObject()).isTrue();
        assertThat(detailJson.path("historyForms").isArray()).isTrue();
        assertThat(detailJson.has("applicationMissing")).isFalse();
        assertThat(detailJson.path("returnedApplication").isBoolean()).isTrue();
        assertThat(formDetail.historyForms()).hasSize(1);
        assertThatThrownBy(() -> formDetail.historyForms().add(formSnapshot("another-task")))
                .isInstanceOf(UnsupportedOperationException.class);

        JsonNode processJson = JsonMapper.shared().valueToTree(
                processDetail("running", null, false, false));
        assertThat(processJson.path("formDetail").isObject()).isTrue();
        assertThat(processJson.has("currentTaskForm")).isFalse();
        assertThat(processJson.has("processFormList")).isFalse();
    }

    /**
     * 验证表单语义 VO 拒绝全部明确定义的矛盾状态。
     *
     * @return void，任一矛盾组合能够构造时测试失败
     */
    @Test
    void processFormDetailRejectsAllContradictoryCombinations()
    {
        WorkflowProcessFormSnapshotView application = formSnapshot(null);
        WorkflowProcessFormSnapshotView returnedApplication = formSnapshot("task");
        WorkflowProcessFormSnapshotView nodeTask = formSnapshot("node-task");

        assertThatThrownBy(() -> new WorkflowProcessFormDetailView(
                returnedApplication, nodeTask, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProcessFormDetailView(
                returnedApplication, null, List.of(), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProcessFormDetailView(
                formSnapshot(" "), null, List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProcessFormDetailView(
                application, null, List.of(application), false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkflowProcessFormDetailView(
                null, null, List.of(), false))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 验证流程详情拒绝退回表单与稳定状态、活动任务、任务主键及重提能力的跨字段矛盾。
     *
     * @return void，任一跨字段矛盾能够进入 HTTP 响应时测试失败
     */
    @Test
    void processDetailRejectsContradictoryReturnedFormBindings()
    {
        WorkflowProcessFormDetailView returned = new WorkflowProcessFormDetailView(
                formSnapshot("task"), null, List.of(), true);
        WorkflowProcessFormDetailView mismatched = new WorkflowProcessFormDetailView(
                formSnapshot("other-task"), null, List.of(), true);
        WorkflowProcessFormDetailView ordinary = new WorkflowProcessFormDetailView(
                formSnapshot(null), null, List.of(), false);

        assertThatThrownBy(() -> processDetail(
                "running", taskSnapshot(true), false, false, returned))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "returned", taskSnapshot(false), false, false, returned))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "returned", taskSnapshot(true), false, false, mismatched))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "returned", taskSnapshot(true), false, false, ordinary))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "returned", taskSnapshot(true), true, false, ordinary))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "running", null, false, false, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 验证详情构造器拒绝把 true 绑定到无任务、历史任务或非 returned 流程。
     *
     * @return void，任一矛盾 capability 快照能够构造时测试失败
     */
    @Test
    void processDetailRejectsContradictoryResubmitCapability()
    {
        assertThatThrownBy(() -> processDetail("returned", null, true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "returned", taskSnapshot(false), true, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processDetail(
                "running", taskSnapshot(true), true, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 创建协议测试使用的最小流程详情快照。
     *
     * @param processStatus String，待验证的稳定流程状态
     * @param currentTask WorkflowTaskAccessSnapshot，可选活动或历史任务
     * @param resubmitAllowed boolean，待验证的重提能力值
     * @param urgeAllowed boolean，待验证的催办能力值
     * @return WorkflowProcessDetailView，可直接交给 Jackson 序列化的详情快照
     */
    private WorkflowProcessDetailView processDetail(String processStatus,
            WorkflowTaskAccessSnapshot currentTask, boolean resubmitAllowed,
            boolean urgeAllowed)
    {
        WorkflowProcessFormDetailView formDetail = "returned".equals(processStatus)
                && currentTask != null && currentTask.active()
                        ? new WorkflowProcessFormDetailView(
                                formSnapshot(currentTask.taskId()), null,
                                List.of(), true)
                        : new WorkflowProcessFormDetailView(
                                formSnapshot(null), null, List.of(), false);
        return processDetail(processStatus, currentTask, resubmitAllowed,
                urgeAllowed, formDetail);
    }

    /**
     * 创建允许显式注入表单语义的最小流程详情快照。
     *
     * @param processStatus String，待验证的稳定流程状态
     * @param currentTask WorkflowTaskAccessSnapshot，可选活动或历史任务
     * @param resubmitAllowed boolean，待验证的重提能力值
     * @param urgeAllowed boolean，待验证的催办能力值
     * @param formDetail WorkflowProcessFormDetailView，待验证的表单语义组合
     * @return WorkflowProcessDetailView，构造成功时可直接交给 Jackson 序列化
     */
    private WorkflowProcessDetailView processDetail(String processStatus,
            WorkflowTaskAccessSnapshot currentTask, boolean resubmitAllowed,
            boolean urgeAllowed, WorkflowProcessFormDetailView formDetail)
    {
        return new WorkflowProcessDetailView("instance", "definition", "process",
                "流程", 1, "category", "deployment", null, "applicant",
                "申请人", Instant.EPOCH, null, null, processStatus, currentTask,
                "DISABLED", null, false, false, false, false, false, false,
                resubmitAllowed, urgeAllowed, List.of(),
                formDetail, List.of(), List.of(), "<definitions/>",
                new WorkflowProcessViewerView(Set.of(), Set.of(), Set.of(),
                        Set.of(), Set.of()));
    }

    /**
     * 创建协议测试使用的最小表单快照。
     *
     * @param taskId String，开始表单为空，任务表单使用非空主键
     * @return WorkflowProcessFormSnapshotView，不含业务值的不可变表单快照
     */
    private WorkflowProcessFormSnapshotView formSnapshot(String taskId)
    {
        return new WorkflowProcessFormSnapshotView("activity", taskId,
                "TEMPLATE", 1L, "form", "node", "表单", "节点",
                "{\"fields\":[]}", false, java.util.Map.of(), Instant.EPOCH);
    }

    /**
     * 按字段名读取 record 组件。
     *
     * @param recordType Class&lt;?&gt;，待检查的 record 类型
     * @param componentName String，组件名称
     * @return RecordComponent，唯一匹配的组件
     */
    private RecordComponent component(Class<?> recordType, String componentName)
    {
        return Arrays.stream(recordType.getRecordComponents())
                .filter(candidate -> componentName.equals(candidate.getName()))
                .findFirst().orElseThrow();
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
