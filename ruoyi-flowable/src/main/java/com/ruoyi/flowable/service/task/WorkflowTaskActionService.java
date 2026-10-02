package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.domain.dto.WorkflowTaskClaimRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskUnclaimRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskDelegateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskResolveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskTransferRequest;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/** 编排认领、取消认领、委派、委派办结和转办；引擎、审计、抄送与通知同事务提交。 */
@Service
public class WorkflowTaskActionService
{
    /** 委派和转办意见最大长度，与 HTTP DTO 及应用服务门禁一致。 */
    private static final int MAX_AUDIT_OPINION_LENGTH = 500;

    /** 服务端审计 JSON 序列化器，客户端不能控制审计字段结构。 */
    private static final ObjectMapper AUDIT_MAPPER = JsonMapper.shared();

    private final RepositoryService repositoryService;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowIdentityResolver identityResolver;

    /** 任务动作通知服务，必须与引擎写操作共享当前事务。 */
    private final WorkflowNotificationService notificationService;

    /** 待办、详情与取消认领写入共享的纯规则门禁。 */
    private final WorkflowTaskUnclaimPolicy unclaimPolicy;

    private final WorkflowTaskCopyService taskCopyService;

    /** 使用统一引擎事务边界执行任务动作。 */
    public WorkflowTaskActionService(RepositoryService repositoryService, RuntimeService runtimeService,
            TaskService taskService, WorkflowEngineOperations engineOperations,
            WorkflowIdentityResolver identityResolver,
            WorkflowNotificationService notificationService,
            WorkflowTaskUnclaimPolicy unclaimPolicy, WorkflowTaskCopyService taskCopyService)
    {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.notificationService = notificationService;
        this.unclaimPolicy = unclaimPolicy;
        this.taskCopyService = taskCopyService;
    }

    /** 由当前有效登录用户认领指定任务。 */
    public void claim(WorkflowTaskClaimRequest request)
    {
        String taskId = request.taskId().trim();
        requireText(taskId);
        engineOperations.writeAsCurrentUser(actor ->
        {
            // claim 会把当前用户写入正式 assignee，必须实时具备待签、认领和后续办理完整权限。
            requireClaimEligibleActor(actor);
            Task task = requireActiveTask(taskId);
            requireActiveProcessInstance(task);
            if (task.getAssignee() != null || task.getDelegationState() != null)
            {
                throw conflict();
            }
            if (!isCandidate(taskId, actor))
            {
                throw forbidden();
            }

            // 权限校验通过后再认领；并发认领由 Flowable 乐观锁异常统一翻译为 409。
            taskService.claim(taskId, actor.userId());
            addAuditComment(task, "CLAIM", actor.userId(), null,
                    "用户认领任务");
            notificationService.onStableTaskAction("TASK_CLAIMED", taskId);
            return null;
        });
    }

    /** 由当前有效登录用户取消认领指定任务。 */
    public void unclaim(WorkflowTaskUnclaimRequest request)
    {
        String taskId = request.taskId().trim();
        requireText(taskId);
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            requireActiveProcessInstance(task);
            if (!actor.userId().equals(task.getAssignee()))
            {
                throw forbidden();
            }
            String delegationState = task.getDelegationState() == null
                    ? null : task.getDelegationState().name();
            boolean unclaimAllowed = unclaimPolicy.isUnclaimAllowed(
                    true,
                    true, task.isSuspended(), actor.userId(), task.getAssignee(),
                    task.getOwner(), delegationState, task.getClaimedBy(),
                    task.getClaimTime() != null);
            if (!unclaimAllowed)
            {
                // 403 已在办理人门禁中确定；其余委派、直接指派、元数据或生命周期漂移保持 409。
                throw conflict();
            }
            taskService.unclaim(taskId);
            addAuditComment(task, "UNCLAIM", actor.userId(), null,
                    "用户取消认领任务");
            notificationService.onStableTaskAction("TASK_UNCLAIMED", taskId);
            return null;
        });
    }

    /** 由当前办理人委派任务，抄送与通知共享引擎事务。 */
    public void delegate(WorkflowTaskDelegateRequest request)
    {
        String taskId = request.taskId().trim();
        String opinion = request.comment().trim();
        String targetUserId = String.valueOf(request.userId());
        requireText(taskId);
        String normalizedOpinion = requireAuditOpinion(opinion);
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            requireActiveProcessInstance(task);
            requireCurrentAssignee(task, actor);
            rejectControlledDynamicTaskMutation(task);
            if (task.getOwner() != null || task.getDelegationState() != null)
            {
                // 目标基础命令不支持嵌套或重复委派，避免覆盖既有 owner 与 delegation 关系。
                throw conflict();
            }

            String normalizedTargetUserId = requireApprovalEligibleTargetUser(targetUserId);
            if (actor.userId().equals(normalizedTargetUserId))
            {
                throw invalidArgument();
            }
            WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                    WorkflowTaskCopyAction.DELEGATE, task, actor, request.copyUserIds());
            taskService.delegateTask(taskId, normalizedTargetUserId);
            addAuditComment(task, "DELEGATE", actor.userId(),
                    normalizedTargetUserId, normalizedOpinion);
            // 业务表写入必须晚于引擎动作，并依赖外层 Spring 事务实现任一失败整体回滚。
            taskCopyService.persist(copyPlan);
            notificationService.onStableTaskAction("TASK_DELEGATED", taskId);
            return null;
        });
    }

    /** 由当前受托人解决 PENDING 委派，并把抄送等业务写入纳入同一引擎事务。 */
    public void resolve(WorkflowTaskResolveRequest request)
    {
        String taskId = request.taskId().trim();
        String opinion = request.comment().trim();
        requireText(taskId);
        String normalizedOpinion = requireAuditOpinion(opinion);
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            requireCurrentAssignee(task, actor);
            if (task.getDelegationState() != DelegationState.PENDING)
            {
                throw conflict();
            }

            String owner = task.getOwner();
            if (!isCanonicalApprovalEligibleUser(owner) || actor.userId().equals(owner))
            {
                // resolve 会把 assignee 还原为 owner；owner 无办理资格或与受托人相同都会产生非法委派状态。
                throw conflict();
            }
            WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                    WorkflowTaskCopyAction.RESOLVE, task, actor, request.copyUserIds());
            taskService.resolveTask(taskId);
            // 受托人意见与 owner 回退写入同一事务，禁止固定占位文案冒充真实办理结果。
            addAuditComment(task, "RESOLVE", actor.userId(), owner,
                    normalizedOpinion);
            // 抄送等业务写入晚于引擎动作，失败时依赖外层事务回滚 resolve 和审计 comment。
            taskCopyService.persist(copyPlan);
            notificationService.onStableTaskAction("TASK_DELEGATION_RESOLVED", taskId);
            return null;
        });
    }

    /** 由当前办理人转办任务，抄送与通知共享引擎事务。 */
    public void transfer(WorkflowTaskTransferRequest request)
    {
        String taskId = request.taskId().trim();
        String opinion = request.comment().trim();
        String targetUserId = String.valueOf(request.userId());
        requireText(taskId);
        String normalizedOpinion = requireAuditOpinion(opinion);
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            requireActiveProcessInstance(task);
            requireCurrentAssignee(task, actor);
            rejectControlledDynamicTaskMutation(task);
            if (task.getOwner() != null || task.getDelegationState() != null)
            {
                // 转办只处理普通任务，禁止绕过标准委派的 resolve/complete 状态机。
                throw conflict();
            }

            String normalizedTargetUserId = requireApprovalEligibleTargetUser(targetUserId);
            if (actor.userId().equals(normalizedTargetUserId))
            {
                throw invalidArgument();
            }
            WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                    WorkflowTaskCopyAction.TRANSFER, task, actor, request.copyUserIds());
            if (StringUtils.hasText(task.getClaimedBy()) || task.getClaimTime() != null)
            {
                // Flowable 的 setAssignee 只改办理人，不会清除 claim 来源。先走公共 unclaim 命令终结认领，
                // 防止任务转回最初认领人后旧 claimedBy/claimTime 复活并错误开放取消认领。
                taskService.unclaim(taskId);
            }
            taskService.setAssignee(taskId, normalizedTargetUserId);
            addAuditComment(task, "TRANSFER", actor.userId(),
                    normalizedTargetUserId, normalizedOpinion);
            // 业务表写入必须晚于引擎动作，并依赖外层 Spring 事务实现任一失败整体回滚。
            taskCopyService.persist(copyPlan);
            notificationService.onStableTaskAction("TASK_TRANSFERRED", taskId);
            return null;
        });
    }

    /** 拒绝通过低层任务动作改写受控动态多实例任务，防止办理人与正式成员快照发生漂移。 */
    private void rejectControlledDynamicTaskMutation(Task task)
    {
        String processDefinitionId = task.getProcessDefinitionId();
        String taskDefinitionKey = task.getTaskDefinitionKey();
        if (!StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(taskDefinitionKey))
        {
            throw dataError();
        }
        ProcessDefinition definition;
        BpmnModel model;
        try
        {
            definition = repositoryService.getProcessDefinition(processDefinitionId);
            model = repositoryService.getBpmnModel(processDefinitionId);
        }
        catch (FlowableObjectNotFoundException exception)
        {
            // 活动任务仍引用已消失的定义属于服务端数据漂移，不能按普通对象不存在返回 404。
            ServiceException failure = dataError();
            failure.initCause(exception);
            throw failure;
        }
        if (definition == null || !StringUtils.hasText(definition.getKey()))
        {
            throw dataError();
        }
        if (model == null)
        {
            throw dataError();
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(
                definition.getKey());
        if (process == null)
        {
            throw dataError();
        }
        // 同一部署可包含多个 Process；按定义 key 选定真实 Process 后递归读取嵌套 SubProcess。
        FlowElement element = process.getFlowElement(taskDefinitionKey, true);
        if (!(element instanceof UserTask userTask))
        {
            throw dataError();
        }
        if (WorkflowMultiInstanceModelContract.usesControlledHandler(
                userTask.getLoopCharacteristics()))
        {
            // 委派、转办会改写 assignee，低层完成会绕过 revision；三者都会破坏正式成员状态机。
            throw conflict();
        }
    }

    /** 查询并校验当前命令可操作的活动任务；不存在与非活动态分别映射为 404 和 409。 */
    private Task requireActiveTask(String taskId)
    {
        Task activeTask = taskService.createTaskQuery()
                .taskId(taskId)
                .active()
                .singleResult();
        if (activeTask != null && !activeTask.isSuspended())
        {
            return activeTask;
        }

        // 活动态查询为空时再次按主键查询，用于区分“任务不存在”和“任务已挂起/状态非法”。
        Task existingTask = activeTask != null ? activeTask : taskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();
        if (existingTask == null)
        {
            throw notFound();
        }
        throw conflict();
    }

    /** 校验任务所属流程实例仍处于活动态，禁止对孤立或已挂起实例中的任务执行写动作。 */
    private ProcessInstance requireActiveProcessInstance(Task task)
    {
        String processInstanceId = task.getProcessInstanceId();
        if (!StringUtils.hasText(processInstanceId))
        {
            throw conflict();
        }
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult();
        if (processInstance == null || processInstance.isSuspended())
        {
            throw conflict();
        }
        return processInstance;
    }

    /** 在当前 Flowable 写事务中保存服务端生成的结构化审计 comment。 */
    private void addAuditComment(Task task, String action,
            String actorUserId, String targetUserId, String opinion)
    {
        ObjectNode audit = AUDIT_MAPPER.createObjectNode();
        audit.put("action", action);
        audit.put("actorUserId", actorUserId);
        if (targetUserId != null)
        {
            audit.put("targetUserId", targetUserId);
        }
        audit.put("opinion", opinion);
        taskService.addComment(task.getId(), task.getProcessInstanceId(), action, audit.toString());
    }

    /** 二次校验委派或转办意见，防止非 HTTP 调用绕过 DTO 门禁。 */
    private String requireAuditOpinion(String opinion)
    {
        if (!StringUtils.hasText(opinion))
        {
            throw invalidArgument();
        }
        String normalizedOpinion = opinion.trim();
        if (normalizedOpinion.length() > MAX_AUDIT_OPINION_LENGTH)
        {
            throw invalidArgument();
        }
        return normalizedOpinion;
    }

    /** 判断当前身份是否为任务的直接候选人，或属于任务声明的 ROLE/DEPT 候选组。 */
    private boolean isCandidate(String taskId, WorkflowCurrentIdentity actor)
    {
        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(taskId);
        if (identityLinks == null)
        {
            return false;
        }
        for (IdentityLink identityLink : identityLinks)
        {
            if (identityLink == null || !IdentityLinkType.CANDIDATE.equals(identityLink.getType()))
            {
                continue;
            }
            if (actor.userId().equals(identityLink.getUserId())
                    || actor.candidateGroups().contains(identityLink.getGroupId()))
            {
                return true;
            }
        }
        return false;
    }

    /** 校验当前用户是任务的实际办理人，阻止无关用户执行委派、解决委派或转办。 */
    private void requireCurrentAssignee(Task task, WorkflowCurrentIdentity actor)
    {
        if (!actor.userId().equals(task.getAssignee()))
        {
            throw forbidden();
        }
        // 委派、解决委派及转办会记录该操作者的审批决定，旧会话不能绕过实时撤权。
        if (!Set.of(actor.userId()).equals(identityResolver.resolveApprovalEligibleUserIds(
                List.of(actor.userId()))))
        {
            throw new ServiceException("用户任务办理身份无效", HttpStatus.BAD_REQUEST);
        }
    }

    /** 从正式主数据校验并规范化客户端指定的目标用户。 */
    private String requireApprovalEligibleTargetUser(String targetUserId)
    {
        // 委派和转办会直接改写正式 assignee，必须在同一写事务内实时核对办理权限。
        Set<String> eligibleUserIds = identityResolver.resolveApprovalEligibleUserIds(
                List.of(targetUserId));
        if (eligibleUserIds.size() != 1)
        {
            throw invalidArgument();
        }
        return eligibleUserIds.iterator().next();
    }

    /** 实时核验当前用户可走通待签、认领、待办详情和审批主路径。 */
    private void requireClaimEligibleActor(WorkflowCurrentIdentity actor)
    {
        Set<String> eligibleUserIds = identityResolver.resolveClaimEligibleUserIds(
                List.of(actor.userId()));
        if (eligibleUserIds == null)
        {
            throw dataError();
        }
        if (eligibleUserIds.size() != 1 || !eligibleUserIds.contains(actor.userId()))
        {
            throw forbidden();
        }
    }

    /** 判断引擎中保存的 owner 是否为规范格式且仍具备正式流程办理权限。 */
    private boolean isCanonicalApprovalEligibleUser(String ownerUserId)
    {
        if (!StringUtils.hasText(ownerUserId))
        {
            return false;
        }
        try
        {
            Set<String> eligibleUserIds = identityResolver.resolveApprovalEligibleUserIds(
                    List.of(ownerUserId));
            return eligibleUserIds.size() == 1 && eligibleUserIds.contains(ownerUserId);
        }
        catch (ServiceException exception)
        {
            if (Integer.valueOf(HttpStatus.BAD_REQUEST).equals(exception.getCode()))
            {
                // owner 来自引擎持久化状态，非法格式属于状态冲突，不按客户端参数错误返回。
                return false;
            }
            throw exception;
        }
    }

    /** 校验引擎任务动作必填字符串，避免将空参数传入引擎并泄露内部异常信息。 */
    private void requireText(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalidArgument();
        }
    }

    /** 创建任务动作参数错误异常。 */
    private ServiceException invalidArgument()
    {
        return new ServiceException("工作流请求参数不合法", HttpStatus.BAD_REQUEST);
    }

    /** 创建任务不存在异常。 */
    private ServiceException notFound()
    {
        return new ServiceException("工作流对象不存在或已被删除", HttpStatus.NOT_FOUND);
    }

    /** 创建任务状态冲突异常。 */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /** 对象关联数据损坏必须终止事务。 */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /** 创建任务权限拒绝异常。 */
    private ServiceException forbidden()
    {
        return new ServiceException("无权执行当前工作流操作", HttpStatus.FORBIDDEN);
    }
}
