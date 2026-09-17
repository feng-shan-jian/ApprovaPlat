package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.ruoyi.flowable.service.task.WorkflowNextTaskAssignmentContract.NextUserAssignmentPolicy;
import com.ruoyi.flowable.authorization.WorkflowTaskAccessSnapshot;

/**
 * 完成对象授权和关系核验后的流程实例完整只读详情。
 *
 * @param processInstanceId String，流程实例主键
 * @param definitionId String，流程定义主键
 * @param processKey String，流程定义标识
 * @param processName String，流程定义名称
 * @param version int，流程定义版本
 * @param category String，流程分类编码
 * @param deploymentId String，部署主键
 * @param businessKey String，业务主键，允许为空
 * @param startUserId String，流程发起人主键，允许为空
 * @param startUserName String，流程发起人显示名称，允许为空
 * @param startTime Instant，流程开始时间
 * @param endTime Instant，流程结束时间，运行中为空
 * @param durationMillis Long，流程耗时毫秒，运行中为空
 * @param processStatus String，running、returned、suspended、completed、canceled、rejected 或 terminated
 * @param currentTask WorkflowTaskAccessSnapshot，请求指定并通过关系核验的任务，允许为空
 * @param nextUserAssignmentPolicy NextUserAssignmentPolicy，DISABLED、OPTIONAL、REQUIRED_ALL 或 REQUIRED_ANY
 * @param multiInstanceState WorkflowMultiInstanceStateView，当前办理人可调整的动态多实例状态，非 MI 为 null
 * @param completeAllowed boolean，当前用户能否完成请求任务
 * @param rejectAllowed boolean，当前用户能否驳回请求任务
 * @param returnAllowed boolean，当前用户能否对请求任务执行真实退回
 * @param delegateAllowed boolean，当前用户能否委派请求任务
 * @param transferAllowed boolean，当前用户能否转办请求任务
 * @param resolveAllowed boolean，当前用户能否办结请求任务的委派
 * @param resubmitAllowed boolean，当前用户能否对申请人待修改任务执行真实重提
 * @param urgeAllowed boolean，当前用户能否对运行中的根流程执行真实人工催办
 * @param controlledLoopStates List&lt;WorkflowControlledLoopStateView&gt;，部署循环配置、当前轮次和历史轨迹
 * @param formDetail WorkflowProcessFormDetailView，申请、节点、历史及退回申请态的正式表单语义
 * @param historyProcNodeList List&lt;WorkflowProcessActivityView&gt;，开始、用户任务和结束时间线
 * @param processRelations List&lt;WorkflowProcessRelationView&gt;，同一 CallActivity 根执行树的父子实例关系
 * @param bpmnXml String，经过安全校验的 BPMN XML
 * @param flowViewer WorkflowProcessViewerView，Viewer 活动状态集合
 */
public record WorkflowProcessDetailView(
        String processInstanceId,
        String definitionId,
        String processKey,
        String processName,
        int version,
        String category,
        String deploymentId,
        String businessKey,
        String startUserId,
        String startUserName,
        Instant startTime,
        Instant endTime,
        Long durationMillis,
        String processStatus,
        WorkflowTaskAccessSnapshot currentTask,
        NextUserAssignmentPolicy nextUserAssignmentPolicy,
        WorkflowMultiInstanceStateView multiInstanceState,
        boolean completeAllowed,
        boolean rejectAllowed,
        boolean returnAllowed,
        boolean delegateAllowed,
        boolean transferAllowed,
        boolean resolveAllowed,
        boolean resubmitAllowed,
        boolean urgeAllowed,
        List<WorkflowControlledLoopStateView> controlledLoopStates,
        WorkflowProcessFormDetailView formDetail,
        List<WorkflowProcessActivityView> historyProcNodeList,
        List<WorkflowProcessRelationView> processRelations,
        String bpmnXml,
        WorkflowProcessViewerView flowViewer)
{
    /**
     * 创建流程详情并复制表单和时间线集合。
     *
     * 构造后领域结果不可变
     * @param formDetail WorkflowProcessFormDetailView，语义明确且不可为空的表单详情
     */
    public WorkflowProcessDetailView
    {
        controlledLoopStates = List.copyOf(Objects.requireNonNull(
                controlledLoopStates, "受控循环状态不能为空"));
        Objects.requireNonNull(formDetail, "表单详情不能为空");
        historyProcNodeList = List.copyOf(Objects.requireNonNull(
                historyProcNodeList, "流程时间线不能为空"));
        processRelations = List.copyOf(Objects.requireNonNull(
                processRelations, "流程实例关系不能为空"));
        Objects.requireNonNull(flowViewer, "Viewer 状态不能为空");
    }
}
