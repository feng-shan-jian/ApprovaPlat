package com.ruoyi.flowable.service.task;

import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.CONTROLLED_TRANSITION_VARIABLE;
import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.RETURNED_STATUS;
import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE;
import static com.ruoyi.flowable.service.task.WorkflowReturnedApplicationProtocol.RETURN_ASSIGNMENT_VARIABLE;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.flowable.common.engine.impl.identity.Authentication;
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
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

/**
 * 申请退回态、原办理配置、独占申请人指派和 running/returned 业务状态的唯一边界。
 */
@Service
public class WorkflowReturnedTaskStateService
{
    private final WorkflowReturnedAssignmentCodec assignmentCodec;
    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final WorkflowIdentityResolver identityResolver;

    /**
     * 创建退回任务状态服务。
     *
     * 构造后由 Spring 管理
     */
    public WorkflowReturnedTaskStateService(
            WorkflowReturnedAssignmentCodec assignmentCodec,
            RuntimeService runtimeService, TaskService taskService,
            WorkflowIdentityResolver identityResolver)
    {
        this.assignmentCodec = assignmentCodec;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.identityResolver = identityResolver;
    }

    /** 使用当前操作取得的实例读取唯一 businessStatus。 */
    public void requireRunning(ProcessInstance instance)
    {
        if (!WorkflowProcessStartService.RUNNING_STATUS.equals(instance.getBusinessStatus()))
        {
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
     * 冻结迁移后首审批任务的办理配置并切换为发起人独占的 returned 状态。
     *
     * @param taskId String，Flowable 迁移后新建的首审批任务主键
     * @param processInstanceId String，流程实例主键
     * @param applicantUserId String，流程正式发起人主键
     * @return ReturnedAssignmentSnapshot，普通或整组重提所需的原办理配置
     */
    public ReturnedAssignmentSnapshot enterReturned(String taskId,
            String processInstanceId, String applicantUserId)
    {
        Task task = requireTask(taskId, processInstanceId);
        String actor = Authentication.getAuthenticatedUserId();
        if (!StringUtils.hasText(actor))
        {
            throw new ServiceException("用户任务监听数据异常", HttpStatus.ERROR);
        }
        // 退回操作者需要审批资格；接管待修改任务的发起人只需处于活动状态。
        if (!Set.of(actor).equals(identityResolver.resolveApprovalEligibleUserIds(List.of(actor)))
                || !Set.of(applicantUserId).equals(identityResolver.resolveActiveUserIds(
                        List.of(applicantUserId), List.of())))
        {
            throw invalidTaskIdentity();
        }
        ReturnedAssignmentSnapshot assignment = captureAssignment(task);
        taskService.setVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE,
                assignmentCodec.encode(assignment));
        assignReturnedApplicant(task, applicantUserId);
        return assignment;
    }

    /** 同一准备链已取得任务和实例，继续读取申请人局部标记与候选链接。 */
    public void requireReturnedApplicant(Task task, ProcessInstance instance, String applicantUserId)
    {
        String taskId = task.getId();
        if (!applicantUserId.equals(instance.getStartUserId()))
        {
            throw forbidden();
        }
        if (!applicantUserId.equals(task.getAssignee())
                || task.getOwner() != null || task.getDelegationState() != null
                || hasCandidateIdentityLinks(taskId)
                || !applicantUserId.equals(taskService.getVariableLocal(
                        taskId, RETURN_APPLICANT_VARIABLE)))
        {
            throw conflict();
        }
        if (!RETURNED_STATUS.equals(instance.getBusinessStatus()))
        {
            throw conflict();
        }
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
     * 清除普通退回协议变量、恢复原办理配置并切换为 running 业务状态。
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
        // 快照来自上一次退回请求；重提是新的分配时刻，恢复前重新取得实际需要的资格。
        LinkedHashSet<String> directUsers = new LinkedHashSet<>();
        if (assignment.assignee() != null) directUsers.add(assignment.assignee());
        if (assignment.owner() != null) directUsers.add(assignment.owner());
        if (!directUsers.isEmpty() && !directUsers.equals(
                identityResolver.resolveApprovalEligibleUserIds(directUsers)))
        {
            throw invalidTaskIdentity();
        }
        if (assignment.assignee() == null
                && (!new LinkedHashSet<>(assignment.candidateUserIds()).equals(
                        identityResolver.resolveClaimEligibleUserIds(assignment.candidateUserIds()))
                    || !new LinkedHashSet<>(assignment.candidateGroupIds()).equals(
                        identityResolver.resolveClaimEligibleCandidateGroups(assignment.candidateGroupIds()))))
        {
            throw invalidTaskIdentity();
        }
        taskService.removeVariableLocal(taskId, RETURN_APPLICANT_VARIABLE);
        taskService.removeVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE);
        taskService.setOwner(taskId, assignment.owner());
        taskService.setAssignee(taskId, assignment.assignee());
        assignment.candidateUserIds().forEach(userId ->
                taskService.addCandidateUser(taskId, userId));
        assignment.candidateGroupIds().forEach(groupId ->
                taskService.addCandidateGroup(taskId, groupId));
        setBusinessStatus(processInstanceId,
                WorkflowProcessStartService.RUNNING_STATUS);
    }

    /**
     * 清除整组待修改任务协议变量并在重建前切换为 running 业务状态。
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
        setBusinessStatus(processInstanceId,
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
     * 将任务设置为只允许发起人办理并同步 returned 业务状态。
     *
     * @param task Task，待修改真实活动任务
     * @param applicantUserId String，流程正式发起人主键
     * @return 无返回值，任一同步写操作失败时由外层事务回滚
     */
    private void assignReturnedApplicant(Task task, String applicantUserId)
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
        setBusinessStatus(task.getProcessInstanceId(), RETURNED_STATUS);
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
     * 写入唯一 Flowable businessStatus。
     *
     * @param processInstanceId String，流程实例主键
     * @param status String，returned 或 running 状态
     * @return 无返回值，状态写入处于调用方同一事务
     */
    private void setBusinessStatus(String processInstanceId, String status)
    {
        runtimeService.updateBusinessStatus(processInstanceId, status);
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

    private ServiceException invalidTaskIdentity()
    {
        return new ServiceException("用户任务办理身份无效", HttpStatus.BAD_REQUEST);
    }
}
