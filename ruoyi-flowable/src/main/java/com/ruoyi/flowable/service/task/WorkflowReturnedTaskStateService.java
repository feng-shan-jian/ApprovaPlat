package com.ruoyi.flowable.service.task;

import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.CONTROLLED_TRANSITION_VARIABLE;
import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.RETURNED_STATUS;
import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE;
import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

/**
 * 申请退回态、原办理配置、独占申请人指派和 running/returned 双状态的唯一边界。
 */
@Service
public class WorkflowReturnedTaskStateService
{
    private final WorkflowReturnedAssignmentCodec assignmentCodec;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    /**
     * 创建退回任务状态服务。
     *
     * @param assignmentCodec WorkflowReturnedAssignmentCodec，办理配置快照编解码器
     * @param runtimeService RuntimeService，流程变量与 business status 写入服务
     * @param taskService TaskService，任务局部变量、身份链和指派写入服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowReturnedTaskStateService(
            WorkflowReturnedAssignmentCodec assignmentCodec,
            RuntimeService runtimeService, TaskService taskService)
    {
        this.assignmentCodec = assignmentCodec;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    /**
     * 核验流程仍处于普通运行态，并兼容未写 business status 的既有运行实例。
     *
     * @param processInstanceId String，活动流程实例主键
     * @return 无返回值，processStatus 非 running 或 businessStatus 为冲突终态时抛出 HTTP 409
     */
    public void requireRunning(String processInstanceId)
    {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        Object processStatus = runtimeService.getVariable(processInstanceId,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE);
        String businessStatus = instance == null ? null : instance.getBusinessStatus();
        if (instance == null || instance.isSuspended()
                || !WorkflowProcessStartService.RUNNING_STATUS.equals(processStatus)
                || (StringUtils.hasText(businessStatus)
                        && !WorkflowProcessStartService.RUNNING_STATUS.equals(
                                businessStatus)))
        {
            // 只兼容历史运行实例未写 businessStatus；任何明确终态继续失败关闭。
            throw conflict();
        }
    }

    /**
     * 写入一次受控迁移标记，使监听器在同一 Flowable 命令中识别正式迁移。
     *
     * @param processInstanceId String，流程实例主键
     * @param marker String，RETURN 或 RESUBMIT 稳定标记
     * @return 无返回值，参数不完整时拒绝写入
     */
    public void markTransition(String processInstanceId, String marker)
    {
        if (!StringUtils.hasText(processInstanceId) || !StringUtils.hasText(marker))
        {
            throw dataError();
        }
        runtimeService.setVariable(processInstanceId,
                CONTROLLED_TRANSITION_VARIABLE, marker);
    }

    /**
     * 在全部写后核验完成后清除受控迁移标记。
     *
     * @param processInstanceId String，流程实例主键
     * @return 无返回值，清除失败由外层事务回滚
     */
    public void clearTransition(String processInstanceId)
    {
        runtimeService.removeVariable(processInstanceId,
                CONTROLLED_TRANSITION_VARIABLE);
    }

    /**
     * 冻结普通首审批任务办理配置并切换为发起人独占的 returned 状态。
     *
     * @param taskId String，Flowable 迁移后新建的首审批任务主键
     * @param processInstanceId String，流程实例主键
     * @param applicantUserId String，流程正式发起人主键
     * @return ReturnedAssignmentSnapshot，可供普通重提精确恢复的办理配置
     */
    public ReturnedAssignmentSnapshot enterOrdinaryReturned(String taskId,
            String processInstanceId, String applicantUserId)
    {
        Task task = requireTask(taskId, processInstanceId);
        ReturnedAssignmentSnapshot assignment = captureAssignment(task);
        taskService.setVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE,
                assignmentCodec.encode(assignment));
        enterReturned(task, applicantUserId);
        return assignment;
    }

    /**
     * 冻结整组迁移生成的首审批任务办理配置，再切换为发起人独占 returned 状态。
     *
     * @param taskId String，整组迁移后唯一申请人任务主键
     * @param processInstanceId String，流程实例主键
     * @param applicantUserId String，流程正式发起人主键
     * @return ReturnedAssignmentSnapshot，重提从普通首审批继续时需要恢复的完整办理配置
     */
    public ReturnedAssignmentSnapshot enterGroupReturned(String taskId,
            String processInstanceId,
            String applicantUserId)
    {
        Task task = requireTask(taskId, processInstanceId);
        // 无论退回来源是否为多实例，迁移目标都是真实首审批任务；必须先冻结该节点配置。
        ReturnedAssignmentSnapshot assignment = captureAssignment(task);
        taskService.setVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE,
                assignmentCodec.encode(assignment));
        enterReturned(task, applicantUserId);
        return assignment;
    }

    /**
     * 核验重提操作人就是流程发起人且当前任务保持独占 returned 状态。
     *
     * @param taskId String，申请人待修改任务主键
     * @param processInstanceId String，流程实例主键
     * @param applicantUserId String，事务内已核验操作人主键
     * @return 无返回值，权限不匹配返回 403，状态漂移返回 409
     */
    public void requireReturnedApplicant(String taskId, String processInstanceId,
            String applicantUserId)
    {
        ProcessInstance instance = requireProcess(processInstanceId);
        if (!applicantUserId.equals(instance.getStartUserId()))
        {
            throw forbidden();
        }
        Task task = requireTask(taskId, processInstanceId);
        if (!applicantUserId.equals(task.getAssignee())
                || task.getOwner() != null || task.getDelegationState() != null
                || hasCandidateIdentityLinks(taskId)
                || !applicantUserId.equals(taskService.getVariableLocal(
                        taskId, RETURN_APPLICANT_VARIABLE)))
        {
            throw conflict();
        }
        verifyProcessStatus(processInstanceId, RETURNED_STATUS, true);
    }

    /**
     * 读取普通退回时冻结的完整办理配置。
     *
     * @param taskId String，申请人待修改任务主键
     * @return ReturnedAssignmentSnapshot，完整不可变办理配置
     */
    public ReturnedAssignmentSnapshot requireOrdinaryAssignment(String taskId)
    {
        Object rawAssignment = taskService.getVariableLocal(
                taskId, RETURN_ASSIGNMENT_VARIABLE);
        if (!(rawAssignment instanceof String encoded)
                || !StringUtils.hasText(encoded))
        {
            throw conflict();
        }
        return assignmentCodec.decode(encoded);
    }

    /**
     * 清除普通退回协议变量、恢复原办理配置并切换为 running 双状态。
     *
     * @param taskId String，重新开放的首审批任务主键
     * @param processInstanceId String，流程实例主键
     * @param assignment ReturnedAssignmentSnapshot，退回时冻结的办理配置
     * @return 无返回值，任一同步写操作失败时由外层事务回滚
     */
    public void restoreOrdinary(String taskId, String processInstanceId,
            ReturnedAssignmentSnapshot assignment)
    {
        Objects.requireNonNull(assignment, "退回办理配置不能为空");
        requireTask(taskId, processInstanceId);
        taskService.removeVariableLocal(taskId, RETURN_APPLICANT_VARIABLE);
        taskService.removeVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE);
        taskService.setOwner(taskId, assignment.owner());
        taskService.setAssignee(taskId, assignment.assignee());
        assignment.candidateUserIds().forEach(userId ->
                taskService.addCandidateUser(taskId, userId));
        assignment.candidateGroupIds().forEach(groupId ->
                taskService.addCandidateGroup(taskId, groupId));
        setProcessStatus(processInstanceId,
                WorkflowProcessStartService.RUNNING_STATUS);
    }

    /**
     * 清除整组待修改任务协议变量并在重建前切换为 running 双状态。
     *
     * @param taskId String，待撤销的申请人任务主键
     * @param processInstanceId String，流程实例主键
     * @return 无返回值，任一同步写操作失败时由外层事务回滚
     */
    public void prepareGroupRunning(String taskId, String processInstanceId)
    {
        requireTask(taskId, processInstanceId);
        taskService.removeVariableLocal(taskId, RETURN_APPLICANT_VARIABLE);
        taskService.removeVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE);
        setProcessStatus(processInstanceId,
                WorkflowProcessStartService.RUNNING_STATUS);
    }

    /**
     * 捕获普通任务办理人、所有者和候选关系。
     *
     * @param task Task，迁移后刚创建的首审批任务
     * @return ReturnedAssignmentSnapshot，可精确恢复的身份快照
     */
    private ReturnedAssignmentSnapshot captureAssignment(Task task)
    {
        List<IdentityLink> links = requireIdentityLinks(task.getId());
        LinkedHashSet<String> candidateUsers = new LinkedHashSet<>();
        LinkedHashSet<String> candidateGroups = new LinkedHashSet<>();
        for (IdentityLink link : links)
        {
            if (link == null || !IdentityLinkType.CANDIDATE.equals(link.getType()))
            {
                continue;
            }
            if (StringUtils.hasText(link.getUserId()))
            {
                candidateUsers.add(link.getUserId());
            }
            else if (StringUtils.hasText(link.getGroupId()))
            {
                candidateGroups.add(link.getGroupId());
            }
            else
            {
                throw dataError();
            }
        }
        if (!StringUtils.hasText(task.getAssignee()) && candidateUsers.isEmpty()
                && candidateGroups.isEmpty())
        {
            throw conflict();
        }
        return new ReturnedAssignmentSnapshot(task.getAssignee(), task.getOwner(),
                List.copyOf(candidateUsers), List.copyOf(candidateGroups));
    }

    /**
     * 将任务设置为只允许发起人办理并同步 returned 双状态。
     *
     * @param task Task，待修改真实活动任务
     * @param applicantUserId String，流程正式发起人主键
     * @return 无返回值，任一同步写操作失败时由外层事务回滚
     */
    private void enterReturned(Task task, String applicantUserId)
    {
        ProcessInstance instance = requireProcess(task.getProcessInstanceId());
        if (!Objects.equals(applicantUserId, instance.getStartUserId()))
        {
            throw forbidden();
        }
        taskService.setVariableLocal(task.getId(), RETURN_APPLICANT_VARIABLE,
                applicantUserId);
        removeCandidateLinks(task.getId());
        taskService.setOwner(task.getId(), null);
        setProcessStatus(task.getProcessInstanceId(), RETURNED_STATUS);
        taskService.setAssignee(task.getId(), applicantUserId);
    }

    /**
     * 删除任务全部候选用户和候选组。
     *
     * @param taskId String，申请人待修改任务主键
     * @return 无返回值，异常身份链触发回滚
     */
    private void removeCandidateLinks(String taskId)
    {
        for (IdentityLink link : requireIdentityLinks(taskId))
        {
            if (link == null || !IdentityLinkType.CANDIDATE.equals(link.getType()))
            {
                continue;
            }
            if (StringUtils.hasText(link.getUserId()))
            {
                taskService.deleteCandidateUser(taskId, link.getUserId());
            }
            else if (StringUtils.hasText(link.getGroupId()))
            {
                taskService.deleteCandidateGroup(taskId, link.getGroupId());
            }
            else
            {
                throw dataError();
            }
        }
    }

    /**
     * 查询任务身份链并拒绝引擎返回空集合对象。
     *
     * @param taskId String，任务主键
     * @return List&lt;IdentityLink&gt;，真实身份链
     */
    private List<IdentityLink> requireIdentityLinks(String taskId)
    {
        List<IdentityLink> links = taskService.getIdentityLinksForTask(taskId);
        if (links == null)
        {
            throw dataError();
        }
        return links;
    }

    /**
     * 判断任务是否仍有候选用户或候选组。
     *
     * @param taskId String，任务主键
     * @return boolean，存在候选关系时返回 true
     */
    private boolean hasCandidateIdentityLinks(String taskId)
    {
        return requireIdentityLinks(taskId).stream().anyMatch(link -> link != null
                && IdentityLinkType.CANDIDATE.equals(link.getType()));
    }

    /**
     * 同步流程变量和 Flowable business status。
     *
     * @param processInstanceId String，流程实例主键
     * @param status String，returned 或 running 状态
     * @return 无返回值，两个写操作处于调用方同一事务
     */
    private void setProcessStatus(String processInstanceId, String status)
    {
        runtimeService.setVariable(processInstanceId,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, status);
        runtimeService.updateBusinessStatus(processInstanceId, status);
    }

    /**
     * 核验流程实例和双状态；调用方可选择冲突或数据错误分类。
     *
     * @param processInstanceId String，流程实例主键
     * @param expectedStatus String，期望状态
     * @param conflictOnMismatch boolean，true 返回 409，false 返回 500
     * @return 无返回值，状态漂移时按调用阶段分类
     */
    private void verifyProcessStatus(String processInstanceId,
            String expectedStatus, boolean conflictOnMismatch)
    {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        Object processStatus = runtimeService.getVariable(processInstanceId,
                WorkflowProcessStartService.PROCESS_STATUS_VARIABLE);
        if (instance == null || instance.isSuspended()
                || !expectedStatus.equals(processStatus)
                || !expectedStatus.equals(instance.getBusinessStatus()))
        {
            throw conflictOnMismatch ? conflict() : dataError();
        }
    }

    /**
     * 查询指定实例的真实活动任务。
     *
     * @param taskId String，任务主键
     * @param processInstanceId String，预期流程实例主键
     * @return Task，未挂起且归属正确的活动任务
     */
    private Task requireTask(String taskId, String processInstanceId)
    {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null || task.isSuspended()
                || !processInstanceId.equals(task.getProcessInstanceId()))
        {
            throw conflict();
        }
        return task;
    }

    /**
     * 查询真实运行流程实例。
     *
     * @param processInstanceId String，流程实例主键
     * @return ProcessInstance，未挂起活动实例
     */
    private ProcessInstance requireProcess(String processInstanceId)
    {
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId).singleResult();
        if (instance == null || instance.isSuspended())
        {
            throw conflict();
        }
        return instance;
    }

    /** @return ServiceException，稳定 HTTP 409。 */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT);
    }

    /** @return ServiceException，稳定 HTTP 403。 */
    private ServiceException forbidden()
    {
        return new ServiceException("无权执行当前工作流操作", HttpStatus.FORBIDDEN);
    }

    /** @return ServiceException，稳定 HTTP 500。 */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }
}
