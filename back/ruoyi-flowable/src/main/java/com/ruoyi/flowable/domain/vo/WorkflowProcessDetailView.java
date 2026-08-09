package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 * @param processStatus String，running、suspended、completed、canceled、rejected 或 terminated
 * @param currentTask WorkflowTaskAccessSnapshot，请求指定并通过关系核验的任务，允许为空
 * @param nextUserAssignmentPolicy String，DISABLED、OPTIONAL、REQUIRED_ALL 或 REQUIRED_ANY
 * @param nextUserSelectionRequired boolean，完成当前任务前是否必须选择动态多实例成员
 * @param nextUserSelectionMode String，必选动态多实例的 ALL/ANY 模式，否则为空
 * @param multiInstanceState WorkflowMultiInstanceStateView，当前办理人可调整的动态多实例状态，非 MI 为 null
 * @param returnAllowed boolean，当前用户能否对请求任务执行真实退回
 * @param controlledLoopStates List&lt;WorkflowControlledLoopStateView&gt;，部署循环配置、当前轮次和历史轨迹
 * @param currentTaskForm WorkflowProcessFormSnapshotView，当前任务表单及局部值，允许为空
 * @param processFormList List&lt;WorkflowProcessFormSnapshotView&gt;，已执行节点的表单快照和值
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
        String nextUserAssignmentPolicy,
        boolean nextUserSelectionRequired,
        String nextUserSelectionMode,
        WorkflowMultiInstanceStateView multiInstanceState,
        boolean returnAllowed,
        List<WorkflowControlledLoopStateView> controlledLoopStates,
        WorkflowProcessFormSnapshotView currentTaskForm,
        List<WorkflowProcessFormSnapshotView> processFormList,
        List<WorkflowProcessActivityView> historyProcNodeList,
        List<WorkflowProcessRelationView> processRelations,
        String bpmnXml,
        WorkflowProcessViewerView flowViewer)
{
    /**
     * 创建流程详情并复制表单和时间线集合。
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
     * @param processStatus String，稳定流程状态
     * @param currentTask WorkflowTaskAccessSnapshot，通过对象关系核验的任务，允许为空
     * @param nextUserAssignmentPolicy String，动态下一办理人正式策略
     * @param nextUserSelectionRequired boolean，完成前是否必须选择动态多实例成员
     * @param nextUserSelectionMode String，必选动态多实例的 ALL/ANY 模式，否则为空
     * @param multiInstanceState WorkflowMultiInstanceStateView，动态多实例能力和状态，允许为空
     * @param returnAllowed boolean，当前用户和执行树是否满足正式退回前置条件
     * @param controlledLoopStates List&lt;WorkflowControlledLoopStateView&gt;，受控循环状态列表
     * @param currentTaskForm WorkflowProcessFormSnapshotView，当前任务表单，允许为空
     * @param processFormList List&lt;WorkflowProcessFormSnapshotView&gt;，已执行表单快照
     * @param historyProcNodeList List&lt;WorkflowProcessActivityView&gt;，流程历史时间线
     * @param processRelations List&lt;WorkflowProcessRelationView&gt;，父子流程实例关系
     * @param bpmnXml String，经过安全校验的 BPMN XML
     * @param flowViewer WorkflowProcessViewerView，Viewer 活动状态
     * @return 无返回值，构造后领域结果不可变
     */
    public WorkflowProcessDetailView
    {
        processFormList = List.copyOf(Objects.requireNonNull(
                processFormList, "流程表单列表不能为空"));
        controlledLoopStates = List.copyOf(Objects.requireNonNull(
                controlledLoopStates, "受控循环状态不能为空"));
        historyProcNodeList = List.copyOf(Objects.requireNonNull(
                historyProcNodeList, "流程时间线不能为空"));
        processRelations = List.copyOf(Objects.requireNonNull(
                processRelations, "流程实例关系不能为空"));
        Objects.requireNonNull(flowViewer, "Viewer 状态不能为空");
        if (!Set.of("DISABLED", "OPTIONAL", "REQUIRED_ALL", "REQUIRED_ANY")
                .contains(nextUserAssignmentPolicy))
        {
            throw new IllegalArgumentException("动态下一办理人策略不合法");
        }
        String expectedMode = switch (nextUserAssignmentPolicy)
        {
            case "REQUIRED_ALL" -> "ALL";
            case "REQUIRED_ANY" -> "ANY";
            default -> null;
        };
        if (nextUserSelectionRequired != (expectedMode != null)
                || !Objects.equals(nextUserSelectionMode, expectedMode))
        {
            throw new IllegalArgumentException("动态下一办理人能力不完整");
        }
        if (returnAllowed && (currentTask == null || !currentTask.active()))
        {
            throw new IllegalArgumentException("退回能力必须绑定活动任务");
        }
    }

    /**
     * 兼容不需要动态多实例 capability 的既有 Java 调用方。
     *
     * @param processInstanceId String，流程实例主键
     * @param definitionId String，流程定义主键
     * @param processKey String，流程定义标识
     * @param processName String，流程定义名称
     * @param version int，流程定义版本
     * @param category String，流程分类编码
     * @param deploymentId String，部署主键
     * @param businessKey String，业务主键
     * @param startUserId String，发起人主键
     * @param startUserName String，发起人显示名称
     * @param startTime Instant，开始时间
     * @param endTime Instant，结束时间
     * @param durationMillis Long，耗时毫秒
     * @param processStatus String，稳定流程状态
     * @param currentTask WorkflowTaskAccessSnapshot，当前任务
     * @param currentTaskForm WorkflowProcessFormSnapshotView，当前任务表单
     * @param processFormList List&lt;WorkflowProcessFormSnapshotView&gt;，流程表单列表
     * @param historyProcNodeList List&lt;WorkflowProcessActivityView&gt;，历史活动列表
     * @param bpmnXml String，安全 BPMN XML
     * @param flowViewer WorkflowProcessViewerView，Viewer 状态
     * @return 无返回值，multiInstanceState 默认为 null 且 returnAllowed 默认为 false
     */
    public WorkflowProcessDetailView(String processInstanceId, String definitionId,
            String processKey, String processName, int version, String category,
            String deploymentId, String businessKey, String startUserId,
            String startUserName, Instant startTime, Instant endTime,
            Long durationMillis, String processStatus,
            WorkflowTaskAccessSnapshot currentTask,
            WorkflowProcessFormSnapshotView currentTaskForm,
            List<WorkflowProcessFormSnapshotView> processFormList,
            List<WorkflowProcessActivityView> historyProcNodeList,
            String bpmnXml, WorkflowProcessViewerView flowViewer)
    {
        this(processInstanceId, definitionId, processKey, processName, version,
                category, deploymentId, businessKey, startUserId, startUserName,
                startTime, endTime, durationMillis, processStatus, currentTask,
                "DISABLED", false, null, null, false, List.of(), currentTaskForm,
                processFormList, historyProcNodeList, List.of(),
                bpmnXml, flowViewer);
    }

    /**
     * 判断本次详情是否包含可办理任务表单。
     *
     * @return boolean，指定任务存在部署表单快照时返回 true
     */
    public boolean isExistTaskForm()
    {
        return currentTaskForm != null;
    }
}
