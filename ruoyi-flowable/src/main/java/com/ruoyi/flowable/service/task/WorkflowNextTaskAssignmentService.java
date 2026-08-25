package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;

/**
 * 对受限的直接后继用户任务应用动态办理人，避免客户端任意改写复杂执行树。
 */
@Service
public class WorkflowNextTaskAssignmentService
{
    private final WorkflowUserSelectionValidator userSelectionValidator;

    private final TaskService taskService;

    private final RuntimeService runtimeService;

    /** 受控多实例执行树、成员、模式、revision、计数和活动任务的唯一只读解析器。 */
    private final WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader;

    /**
     * 创建动态下一办理人服务。
     *
     * @param userSelectionValidator WorkflowUserSelectionValidator，正式审批资格用户严格校验器
     * @param taskService TaskService，活动任务和候选身份公共服务
     * @param runtimeService RuntimeService，多实例集合变量和状态迁移公共服务
     * @param snapshotReader WorkflowMultiInstanceRuntimeSnapshotReader，受控多实例唯一运行时快照读取器
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowNextTaskAssignmentService(
            WorkflowUserSelectionValidator userSelectionValidator,
            TaskService taskService, RuntimeService runtimeService,
            WorkflowMultiInstanceRuntimeSnapshotReader snapshotReader)
    {
        this.userSelectionValidator = userSelectionValidator;
        this.taskService = taskService;
        this.runtimeService = runtimeService;
        this.snapshotReader = snapshotReader;
    }

    /**
     * 在完成来源任务前校验用户、BPMN 拓扑和目标分配方式，并为受控多实例后继写入专属集合变量。
     *
     * @param sourceTask Task，已经通过活动态和办理人校验的当前任务
     * @param process Process，生命周期服务已唯一读取并核验的当前部署 BPMN Process
     * @param sourceNode UserTask，生命周期服务已在同一 Process 中唯一定位的当前节点
     * @param requestedUserIds List&lt;Long&gt;，客户端可选的动态下一办理人主键
     * @return AssignmentPlan，不可变分配计划；未选择用户时返回空计划
     */
    public AssignmentPlan prepare(Task sourceTask, org.flowable.bpmn.model.Process process,
            UserTask sourceNode, List<Long> requestedUserIds)
    {
        // 先冻结直接办理资格用户和顺序；多人候选计划在识别目标拓扑后追加完整认领权限门禁。
        List<String> approvalUserIds = userSelectionValidator
                .requireApprovalEligibleUserIds(requestedUserIds);
        if (approvalUserIds.isEmpty())
        {
            return prepareWithoutRequestedUsers(sourceTask, process, sourceNode);
        }
        if (sourceTask == null || !StringUtils.hasText(sourceTask.getId())
                || !StringUtils.hasText(sourceTask.getProcessInstanceId())
                || !StringUtils.hasText(sourceTask.getProcessDefinitionId())
                || !StringUtils.hasText(sourceTask.getTaskDefinitionKey()))
        {
            throw dataError();
        }

        // 动态指定只允许实例当前恰好存在一个活动任务，避免改写并行或多实例分支中的局部办理人。
        List<Task> currentTasks = taskService.createTaskQuery()
                .processInstanceId(sourceTask.getProcessInstanceId())
                .active()
                .list();
        if (currentTasks == null || currentTasks.size() != 1
                || !sourceTask.getId().equals(currentTasks.get(0).getId()))
        {
            throw conflict();
        }

        if (process == null || sourceNode == null
                || !sourceTask.getTaskDefinitionKey().equals(sourceNode.getId())
                || sourceNode.getParentContainer() != process)
        {
            throw conflict();
        }
        if (sourceNode.getLoopCharacteristics() != null)
        {
            throw conflict();
        }

        List<SequenceFlow> outgoingFlows = sourceNode.getOutgoingFlows();
        if (outgoingFlows == null || outgoingFlows.size() != 1)
        {
            throw conflict();
        }
        SequenceFlow outgoingFlow = outgoingFlows.get(0);
        if (outgoingFlow == null || StringUtils.hasText(outgoingFlow.getConditionExpression()))
        {
            throw conflict();
        }
        FlowElement targetElement = outgoingFlow.getTargetFlowElement();
        if (targetElement == null && StringUtils.hasText(outgoingFlow.getTargetRef()))
        {
            targetElement = process.getFlowElement(outgoingFlow.getTargetRef(), false);
        }
        if (!(targetElement instanceof UserTask targetNode)
                || process.getFlowElement(targetNode.getId(), false) == null)
        {
            throw conflict();
        }
        if (targetNode.getLoopCharacteristics() != null)
        {
            if (!WorkflowMultiInstanceModelContract.usesDynamicHandler(
                    targetNode.getLoopCharacteristics()))
            {
                // 固定成员在部署 BPMN 中已冻结，当前任务提交人不得通过 nextUserIds 覆盖成员。
                throw conflict();
            }
            WorkflowMultiInstanceMode multiInstanceMode;
            try
            {
                multiInstanceMode = WorkflowMultiInstanceModelContract.requireMode(targetNode);
            }
            catch (IllegalArgumentException exception)
            {
                throw conflict();
            }
            List<Long> numericUserIds = approvalUserIds.stream().map(Long::valueOf).toList();
            // 集合变量必须先于 TaskService.complete 写入，同一外层事务保证创建失败时变量也回滚。
            runtimeService.setVariable(sourceTask.getProcessInstanceId(),
                    WorkflowMultiInstanceVariables.userCollectionName(targetNode.getId()),
                    numericUserIds);
            return new AssignmentPlan(sourceTask.getProcessInstanceId(), sourceTask.getId(),
                    targetNode.getId(), approvalUserIds, true, multiInstanceMode);
        }
        List<String> assignmentUserIds = approvalUserIds.size() == 1
                ? approvalUserIds
                : userSelectionValidator.requireClaimEligibleUserIds(requestedUserIds);
        return new AssignmentPlan(sourceTask.getProcessInstanceId(), sourceTask.getId(),
                targetNode.getId(), assignmentUserIds, false, null);
    }

    /**
     * 在空选择场景复用正式部署模型，阻止受控动态多实例在集合变量缺失后才由引擎失败。
     *
     * @param sourceTask Task，已经通过活动态和办理人校验的当前任务
     * @param process Process，生命周期服务已唯一读取并核验的当前部署 BPMN Process
     * @param sourceNode UserTask，生命周期服务已在同一 Process 中唯一定位的当前节点
     * @return AssignmentPlan，普通后继返回空计划；动态多实例后继直接返回稳定 400
     */
    private AssignmentPlan prepareWithoutRequestedUsers(Task sourceTask,
            org.flowable.bpmn.model.Process process, UserTask sourceNode)
    {
        if (sourceTask == null || !StringUtils.hasText(sourceTask.getProcessDefinitionId())
                || !StringUtils.hasText(sourceTask.getTaskDefinitionKey()))
        {
            throw dataError();
        }
        if (process == null || sourceNode == null
                || !sourceTask.getTaskDefinitionKey().equals(sourceNode.getId()))
        {
            throw dataError();
        }
        try
        {
            // 只对受控动态多实例后继强制成员，普通 BPMN 默认分配仍保持既有行为。
            if (WorkflowNextTaskAssignmentContract.findRequiredMultiInstanceTarget(
                    process, sourceNode).isPresent())
            {
                throw new ServiceException("动态多实例下一办理人不能为空", HttpStatus.BAD_REQUEST);
            }
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError();
        }
        return AssignmentPlan.empty();
    }

    /**
     * 来源任务完成后核验唯一真实后继任务，并以所选用户覆盖其办理人与候选身份。
     *
     * @param plan AssignmentPlan，完成命令前冻结的动态分配计划
     * @return 无返回值，任务结构或身份写入不符合计划时抛错并回滚完成事务
     */
    public void apply(AssignmentPlan plan)
    {
        if (plan == null)
        {
            throw dataError();
        }
        if (!plan.requested())
        {
            return;
        }
        if (plan.multiInstance())
        {
            verifyMultiInstanceAssignment(plan);
            return;
        }

        List<Task> nextTasks = taskService.createTaskQuery()
                .processInstanceId(plan.processInstanceId())
                .active()
                .list();
        if (nextTasks == null || nextTasks.size() != 1)
        {
            throw conflict();
        }
        Task nextTask = nextTasks.get(0);
        if (nextTask == null || nextTask.isSuspended()
                || plan.sourceTaskId().equals(nextTask.getId())
                || !plan.expectedTaskDefinitionKey().equals(nextTask.getTaskDefinitionKey())
                || nextTask.getOwner() != null || nextTask.getDelegationState() != null)
        {
            throw conflict();
        }

        removeCandidateLinks(nextTask.getId());
        if (plan.userIds().size() == 1)
        {
            taskService.setAssignee(nextTask.getId(), plan.userIds().get(0));
        }
        else
        {
            taskService.setAssignee(nextTask.getId(), null);
            for (String userId : plan.userIds())
            {
                taskService.addCandidateUser(nextTask.getId(), userId);
            }
        }
        verifyAppliedAssignment(nextTask.getId(), plan.userIds());
    }

    /**
     * 对账 handler 创建的真实并行多实例任务、execution、服务端快照、模式、revision 和根计数。
     *
     * @param plan AssignmentPlan，完成来源任务前冻结的受控多实例计划
     * @return 无返回值，任一任务、执行或变量与计划不一致时抛出 409 驱动外层事务回滚
     */
    private void verifyMultiInstanceAssignment(AssignmentPlan plan)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(plan.processInstanceId()).active().list();
        if (tasks == null || tasks.size() != plan.userIds().size())
        {
            throw conflict();
        }

        // 新审批组稳定创建后，只通过共享读取器解释 execution、变量和根计数。
        ControlledMultiInstanceSnapshot runtime =
                WorkflowMultiInstanceSnapshotExceptionTranslator.asTransitionConflict(
                        () -> snapshotReader.readTask(tasks.get(0).getId()));
        if (runtime == null
                || !plan.processInstanceId().equals(runtime.processInstanceId())
                || !plan.expectedTaskDefinitionKey().equals(runtime.activityId())
                || runtime.mode() != plan.multiInstanceMode()
                || runtime.revision() != 0
                || !runtime.members().equals(plan.userIds())
                || runtime.counts().instances() != plan.userIds().size()
                || runtime.counts().active() != plan.userIds().size()
                || runtime.counts().completed() != 0
                || runtime.activeTasks().stream().anyMatch(task -> task.owner() != null
                        || task.delegated())
                || !runtime.activeTasks().stream()
                        .map(MultiInstanceActiveTaskSnapshot::assignee)
                        .collect(java.util.stream.Collectors.toCollection(
                                LinkedHashSet::new))
                        .equals(new LinkedHashSet<>(plan.userIds())))
        {
            throw conflict();
        }
    }

    /**
     * 删除 BPMN 静态产生的候选用户和候选组，使动态选择成为后继任务唯一候选来源。
     *
     * @param taskId String，刚创建的唯一后继活动任务主键
     * @return 无返回值，无法识别的候选关系会触发数据异常
     */
    private void removeCandidateLinks(String taskId)
    {
        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(taskId);
        if (identityLinks == null)
        {
            throw dataError();
        }
        for (IdentityLink identityLink : identityLinks)
        {
            if (identityLink == null || !IdentityLinkType.CANDIDATE.equals(identityLink.getType()))
            {
                continue;
            }
            if (StringUtils.hasText(identityLink.getUserId()))
            {
                taskService.deleteCandidateUser(taskId, identityLink.getUserId());
            }
            else if (StringUtils.hasText(identityLink.getGroupId()))
            {
                taskService.deleteCandidateGroup(taskId, identityLink.getGroupId());
            }
            else
            {
                throw dataError();
            }
        }
    }

    /**
     * 重新读取后继任务及候选关系，验证单人 assignee 或多人 candidate users 已真实持久化。
     *
     * @param taskId String，动态分配后的后继任务主键
     * @param expectedUserIds List&lt;String&gt;，按请求顺序保存的预期有效用户主键
     * @return 无返回值，实际状态与计划不一致时抛错回滚
     */
    private void verifyAppliedAssignment(String taskId, List<String> expectedUserIds)
    {
        Task refreshedTask = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (refreshedTask == null || refreshedTask.isSuspended())
        {
            throw conflict();
        }

        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(taskId);
        if (identityLinks == null)
        {
            throw dataError();
        }
        Set<String> candidateUsers = new LinkedHashSet<>();
        Set<String> candidateGroups = new LinkedHashSet<>();
        for (IdentityLink identityLink : identityLinks)
        {
            if (identityLink != null && IdentityLinkType.CANDIDATE.equals(identityLink.getType()))
            {
                if (StringUtils.hasText(identityLink.getUserId()))
                {
                    candidateUsers.add(identityLink.getUserId());
                }
                if (StringUtils.hasText(identityLink.getGroupId()))
                {
                    candidateGroups.add(identityLink.getGroupId());
                }
            }
        }

        if (expectedUserIds.size() == 1)
        {
            if (!expectedUserIds.get(0).equals(refreshedTask.getAssignee())
                    || !candidateUsers.isEmpty() || !candidateGroups.isEmpty())
            {
                throw conflict();
            }
            return;
        }
        if (StringUtils.hasText(refreshedTask.getAssignee()) || !candidateGroups.isEmpty()
                || !candidateUsers.equals(new LinkedHashSet<>(expectedUserIds)))
        {
            throw conflict();
        }
    }

    /**
     * 创建稳定的动态分配结构或并发冲突异常。
     *
     * @return ServiceException，HTTP 409 状态异常
     */
    private ServiceException conflict()
    {
        return new ServiceException("当前流程结构不支持动态指定下一办理人", HttpStatus.CONFLICT);
    }

    /**
     * 创建保留运行时快照漂移原因的稳定冲突。
     *
     * @param cause Throwable，唯一快照读取器发现的真实漂移原因
     * @return ServiceException，HTTP 409 且对外错误信息保持不变
     */
    private ServiceException conflict(Throwable cause)
    {
        ServiceException failure = conflict();
        failure.initCause(cause);
        return failure;
    }

    /**
     * 创建稳定的 BPMN 或任务关联数据异常。
     *
     * @return ServiceException，HTTP 500 数据一致性异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /**
     * 完成命令前冻结的不可变动态后继任务分配计划。
     *
     * @param processInstanceId String，来源任务所属流程实例主键
     * @param sourceTaskId String，完成前的来源任务主键
     * @param expectedTaskDefinitionKey String，唯一无条件直接后继用户任务节点主键
     * @param userIds List&lt;String&gt;，经过正式主数据校验的动态办理人主键
     * @param multiInstance boolean，目标是否为受控动态并行多实例节点
     * @param multiInstanceMode WorkflowMultiInstanceMode，多实例目标的 ALL/ANY 模式；普通任务为 null
     */
    public record AssignmentPlan(String processInstanceId, String sourceTaskId,
            String expectedTaskDefinitionKey, List<String> userIds,
            boolean multiInstance, WorkflowMultiInstanceMode multiInstanceMode)
    {
        /**
         * 创建动态分配计划并复制用户集合，防止完成命令期间请求内容变化。
         *
         * @param processInstanceId String，来源任务所属流程实例主键；空计划允许为 null
         * @param sourceTaskId String，完成前来源任务主键；空计划允许为 null
         * @param expectedTaskDefinitionKey String，预期后继节点主键；空计划允许为 null
         * @param userIds List&lt;String&gt;，有效用户主键集合
         * @param multiInstance boolean，是否为受控动态多实例计划
         * @param multiInstanceMode WorkflowMultiInstanceMode，多实例完成模式
         * @return 无返回值，构造后 userIds 为不可修改集合
         */
        public AssignmentPlan
        {
            if (userIds == null)
            {
                throw new IllegalArgumentException("动态办理人计划不能为空");
            }
            userIds = List.copyOf(new ArrayList<>(userIds));
            if (!userIds.isEmpty() && (!StringUtils.hasText(processInstanceId)
                    || !StringUtils.hasText(sourceTaskId)
                    || !StringUtils.hasText(expectedTaskDefinitionKey)))
            {
                throw new IllegalArgumentException("动态办理人计划关联信息不完整");
            }
            if (multiInstance != (multiInstanceMode != null))
            {
                throw new IllegalArgumentException("动态多实例计划模式不完整");
            }
        }

        /**
         * 创建未请求动态办理人的空计划。
         *
         * @return AssignmentPlan，不改变 BPMN 默认分配结果的空计划
         */
        public static AssignmentPlan empty()
        {
            return new AssignmentPlan(null, null, null, List.of(), false, null);
        }

        /**
         * 判断客户端是否实际请求了动态下一办理人。
         *
         * @return boolean，存在至少一个有效用户时返回 true
         */
        public boolean requested()
        {
            return !userIds.isEmpty();
        }
    }
}
