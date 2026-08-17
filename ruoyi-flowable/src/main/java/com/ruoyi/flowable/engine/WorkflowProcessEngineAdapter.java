package com.ruoyi.flowable.engine;

import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceModelContract;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;

/**
 * 面向 P3 任务业务服务的核心流程引擎适配器，只调用 Flowable 8 公共 API。
 */
@Component
public class WorkflowProcessEngineAdapter
{
    /** 普通任务操作沿用旧系统正常意见类型。 */
    private static final String NORMAL_COMMENT_TYPE = "1";

    /** 委派意见类型，与旧系统 FlowComment.DELEGATE 保持兼容。 */
    private static final String DELEGATE_COMMENT_TYPE = "4";

    /** 转办意见类型，与旧系统 FlowComment.TRANSFER 保持兼容。 */
    private static final String TRANSFER_COMMENT_TYPE = "5";

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
    private final WorkflowNotificationRegistrar notificationService;

    /**
     * 创建核心流程引擎适配器。
     *
     * @param repositoryService RepositoryService，Flowable 流程定义公共服务
     * @param runtimeService RuntimeService，Flowable 运行时公共服务
     * @param taskService TaskService，Flowable 任务公共服务
     * @param engineOperations WorkflowEngineOperations，统一事务、认证和异常执行边界
     * @param identityResolver WorkflowIdentityResolver，正式用户主数据解析器
     * @param notificationService WorkflowNotificationRegistrar，任务动作事务 outbox 服务
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowProcessEngineAdapter(RepositoryService repositoryService, RuntimeService runtimeService,
            TaskService taskService, WorkflowEngineOperations engineOperations,
            WorkflowIdentityResolver identityResolver,
            WorkflowNotificationRegistrar notificationService)
    {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.notificationService = notificationService;
    }

    /**
     * 查询仍处于运行态的流程实例。
     *
     * @param processInstanceId String，流程实例 ID
     * @return Optional&lt;WorkflowProcessInstanceSnapshot&gt;，存在时返回不可变流程实例快照
     */
    public Optional<WorkflowProcessInstanceSnapshot> findActiveProcessInstance(String processInstanceId)
    {
        requireText(processInstanceId);
        return engineOperations.read(() -> Optional.ofNullable(runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult()).map(this::toProcessSnapshot));
    }

    /**
     * 查询仍处于活动态的用户任务。
     *
     * @param taskId String，Flowable 任务 ID
     * @return Optional&lt;WorkflowTaskSnapshot&gt;，存在时返回不可变任务快照
     */
    public Optional<WorkflowTaskSnapshot> findActiveTask(String taskId)
    {
        requireText(taskId);
        return engineOperations.read(() -> Optional.ofNullable(taskService.createTaskQuery()
                .taskId(taskId)
                .active()
                .singleResult()).map(this::toTaskSnapshot));
    }

    /**
     * 将 Flowable 流程实例转换为模块自有不可变快照，防止运行时对象越过适配层。
     *
     * @param processInstance ProcessInstance，查询得到的活动流程实例
     * @return WorkflowProcessInstanceSnapshot，仅包含业务层读取所需字段的快照
     */
    private WorkflowProcessInstanceSnapshot toProcessSnapshot(ProcessInstance processInstance)
    {
        return new WorkflowProcessInstanceSnapshot(processInstance.getId(),
                processInstance.getProcessDefinitionId(), processInstance.getBusinessKey(),
                processInstance.isSuspended());
    }

    /**
     * 将 Flowable 任务转换为模块自有不可变快照，并把委派枚举转换为稳定字符串。
     *
     * @param task Task，查询得到的活动用户任务
     * @return WorkflowTaskSnapshot，仅包含业务层读取所需字段的快照
     */
    private WorkflowTaskSnapshot toTaskSnapshot(Task task)
    {
        Date engineClaimTime = task.getClaimTime();
        // Flowable 的可变 Date 只在适配层读取，对外统一转换为不可变的 Java 时间类型。
        Instant claimTime = engineClaimTime == null ? null : engineClaimTime.toInstant();
        String delegationState = task.getDelegationState() == null
                ? null : task.getDelegationState().name();
        return new WorkflowTaskSnapshot(task.getId(), task.getName(), task.getProcessInstanceId(),
                task.getTaskDefinitionKey(), task.getAssignee(), task.getClaimedBy(), claimTime,
                task.getOwner(), delegationState, task.isSuspended());
    }

    /**
     * 由当前有效登录用户认领指定任务。
     *
     * @param taskId String，待认领任务 ID
     * @return 无返回值
     */
    public void claimTaskForCurrentUser(String taskId)
    {
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
            addAuditComment(task, NORMAL_COMMENT_TYPE, "CLAIM", actor.userId(), null,
                    "用户认领任务");
            publishStableTaskAction("TASK_CLAIMED", taskId);
            return null;
        });
    }

    /**
     * 由当前有效登录用户取消认领指定任务。
     *
     * @param taskId String，待取消认领任务 ID
     * @return 无返回值
     */
    public void unclaimTaskForCurrentUser(String taskId)
    {
        requireText(taskId);
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            requireActiveProcessInstance(task);
            if (!actor.userId().equals(task.getAssignee()))
            {
                throw forbidden();
            }
            if (task.getDelegationState() != null || task.getOwner() != null)
            {
                // 委派任务需要按 resolve/complete 语义流转，取消认领会破坏 owner 与 assignee 关系。
                throw conflict();
            }
            if (!actor.userId().equals(task.getClaimedBy()) || task.getClaimTime() == null)
            {
                // 只有通过 claim 产生且认领元数据完整的任务才能取消认领，静态指派和转办任务保持原办理人。
                throw conflict();
            }
            taskService.unclaim(taskId);
            addAuditComment(task, NORMAL_COMMENT_TYPE, "UNCLAIM", actor.userId(), null,
                    "用户取消认领任务");
            publishStableTaskAction("TASK_UNCLAIMED", taskId);
            return null;
        });
    }

    /**
     * 由当前办理人把普通活动任务委派给当前具备流程办理权限的有效用户。
     *
     * @param taskId String，待委派任务 ID
     * @param targetUserId String，目标受托人的若依用户 ID
     * @return 无返回值
     */
    public void delegateTaskForCurrentUser(String taskId, String targetUserId)
    {
        delegateTaskForCurrentUser(taskId, targetUserId, "未填写委派意见");
    }

    /**
     * 由当前办理人把普通活动任务委派给具备流程办理权限的用户，并原子写入受控审计意见。
     *
     * @param taskId String，待委派任务 ID
     * @param targetUserId String，目标受托人的若依用户 ID
     * @param opinion String，客户端业务意见，审计结构由服务端生成
     * @return 无返回值
     */
    public void delegateTaskForCurrentUser(String taskId, String targetUserId, String opinion)
    {
        delegateTaskForCurrentUser(taskId, targetUserId, opinion, WorkflowTaskWriteHook.none());
    }

    /**
     * 由当前办理人委派任务，并把业务侧写入钩子纳入同一引擎事务。
     *
     * @param taskId String，待委派任务 ID
     * @param targetUserId String，目标受托人的若依用户 ID
     * @param opinion String，客户端业务意见，审计结构由服务端生成
     * @param writeHook WorkflowTaskWriteHook，动作前准备且动作成功后执行的业务写入钩子
     * @return 无返回值，任一业务写入失败时委派状态和审计意见整体回滚
     */
    public void delegateTaskForCurrentUser(String taskId, String targetUserId, String opinion,
            WorkflowTaskWriteHook writeHook)
    {
        requireText(taskId);
        requireText(targetUserId);
        if (writeHook == null)
        {
            throw invalidArgument();
        }
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
            Runnable afterSuccess = writeHook.prepare(actor, task);
            if (afterSuccess == null)
            {
                throw dataError();
            }
            taskService.delegateTask(taskId, normalizedTargetUserId);
            addAuditComment(task, DELEGATE_COMMENT_TYPE, "DELEGATE", actor.userId(),
                    normalizedTargetUserId, normalizedOpinion);
            // 业务表写入必须晚于引擎动作，并依赖外层 Spring 事务实现任一失败整体回滚。
            afterSuccess.run();
            publishStableTaskAction("TASK_DELEGATED", taskId);
            return null;
        });
    }

    /**
     * 由当前受托人解决 PENDING 委派任务，并原子写入真实办理意见。
     *
     * @param taskId String，待解决委派的任务 ID
     * @param opinion String，受托人的业务办理意见，审计结构由服务端生成
     * @return 无返回值
     */
    public void resolveTaskForCurrentUser(String taskId, String opinion)
    {
        resolveTaskForCurrentUser(taskId, opinion, WorkflowTaskWriteHook.none());
    }

    /**
     * 由当前受托人解决 PENDING 委派，并把抄送等业务写入纳入同一引擎事务。
     *
     * @param taskId String，待解决委派的任务 ID
     * @param opinion String，受托人的业务办理意见，审计结构由服务端生成
     * @param writeHook WorkflowTaskWriteHook，动作前准备且动作成功后执行的业务写入钩子
     * @return 无返回值，任一业务写入失败时委派状态和审计意见整体回滚
     */
    public void resolveTaskForCurrentUser(String taskId, String opinion,
            WorkflowTaskWriteHook writeHook)
    {
        requireText(taskId);
        if (writeHook == null)
        {
            throw invalidArgument();
        }
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
            Runnable afterSuccess = writeHook.prepare(actor, task);
            if (afterSuccess == null)
            {
                throw dataError();
            }
            taskService.resolveTask(taskId);
            // 受托人意见与 owner 回退写入同一事务，禁止固定占位文案冒充真实办理结果。
            addAuditComment(task, DELEGATE_COMMENT_TYPE, "RESOLVE", actor.userId(), owner,
                    normalizedOpinion);
            // 抄送等业务写入晚于引擎动作，失败时依赖外层事务回滚 resolve 和审计 comment。
            afterSuccess.run();
            publishStableTaskAction("TASK_DELEGATION_RESOLVED", taskId);
            return null;
        });
    }

    /**
     * 由当前办理人把普通活动任务永久转办给当前具备流程办理权限的有效用户。
     *
     * @param taskId String，待转办任务 ID
     * @param targetUserId String，目标办理人的若依用户 ID
     * @return 无返回值
     */
    public void transferTaskForCurrentUser(String taskId, String targetUserId)
    {
        transferTaskForCurrentUser(taskId, targetUserId, "未填写转办意见");
    }

    /**
     * 由当前办理人把普通活动任务永久转办给具备流程办理权限的用户，并原子写入受控审计意见。
     *
     * @param taskId String，待转办任务 ID
     * @param targetUserId String，目标办理人的若依用户 ID
     * @param opinion String，客户端业务意见，审计结构由服务端生成
     * @return 无返回值
     */
    public void transferTaskForCurrentUser(String taskId, String targetUserId, String opinion)
    {
        transferTaskForCurrentUser(taskId, targetUserId, opinion, WorkflowTaskWriteHook.none());
    }

    /**
     * 由当前办理人转办任务，并把业务侧写入钩子纳入同一引擎事务。
     *
     * @param taskId String，待转办任务 ID
     * @param targetUserId String，目标办理人的若依用户 ID
     * @param opinion String，客户端业务意见，审计结构由服务端生成
     * @param writeHook WorkflowTaskWriteHook，动作前准备且动作成功后执行的业务写入钩子
     * @return 无返回值，任一业务写入失败时办理人状态和审计意见整体回滚
     */
    public void transferTaskForCurrentUser(String taskId, String targetUserId, String opinion,
            WorkflowTaskWriteHook writeHook)
    {
        requireText(taskId);
        requireText(targetUserId);
        if (writeHook == null)
        {
            throw invalidArgument();
        }
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
            Runnable afterSuccess = writeHook.prepare(actor, task);
            if (afterSuccess == null)
            {
                throw dataError();
            }
            if (StringUtils.hasText(task.getClaimedBy()) || task.getClaimTime() != null)
            {
                // Flowable 的 setAssignee 只改办理人，不会清除 claim 来源。先走公共 unclaim 命令终结认领，
                // 防止任务转回最初认领人后旧 claimedBy/claimTime 复活并错误开放取消认领。
                taskService.unclaim(taskId);
            }
            taskService.setAssignee(taskId, normalizedTargetUserId);
            addAuditComment(task, TRANSFER_COMMENT_TYPE, "TRANSFER", actor.userId(),
                    normalizedTargetUserId, normalizedOpinion);
            // 业务表写入必须晚于引擎动作，并依赖外层 Spring 事务实现任一失败整体回滚。
            afterSuccess.run();
            publishStableTaskAction("TASK_TRANSFERRED", taskId);
            return null;
        });
    }

    /**
     * 由当前有效登录用户完成指定任务并持久化流程变量。
     *
     * @param taskId String，待完成任务 ID
     * @param variables Map&lt;String, Object&gt;，任务完成变量；传 null 等同空变量
     * @return 无返回值
     */
    public void completeTask(String taskId, Map<String, Object> variables)
    {
        requireText(taskId);
        Map<String, Object> effectiveVariables = variables == null ? Collections.emptyMap() : variables;
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            if (!actor.userId().equals(task.getAssignee()))
            {
                throw forbidden();
            }
            rejectControlledDynamicTaskMutation(task);
            if (task.getDelegationState() == DelegationState.PENDING)
            {
                // PENDING 表示受托人尚未 resolve，直接 complete 会跳过委派回退语义。
                throw conflict();
            }
            // 显式写入真实办理人，避免 Flowable 8 历史任务 completedBy 为空而破坏已办授权与审计。
            taskService.complete(taskId, actor.userId(), effectiveVariables);
            return null;
        });
    }

    /**
     * 在任务公共 API 已提交最终归属、审计和业务钩子后显式登记一次通知。
     * @param eventType String，稳定任务动作事件类型
     * @param taskId String，仍处于活动态的真实任务主键
     * @return void，通知登记失败时由外层引擎事务整体回滚
     */
    private void publishStableTaskAction(String eventType, String taskId)
    {
        notificationService.onStableTaskAction(eventType, taskId);
    }

    /**
     * 拒绝通过低层适配器改写受控动态多实例任务，防止办理人与正式成员快照发生漂移。
     *
     * @param task Task，已经通过活动态和当前办理人校验的真实任务
     * @return 无返回值，受控 handler 候选返回 409，模型元数据漂移返回 500
     */
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

    /**
     * 查询并校验当前命令可操作的活动任务；不存在与非活动态分别映射为 404 和 409。
     *
     * @param taskId String，待执行写命令的任务 ID
     * @return Task，仍存在且处于活动态的 Flowable 公共任务对象
     */
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

    /**
     * 校验任务所属流程实例仍处于活动态，禁止对孤立或已挂起实例中的任务执行写动作。
     *
     * @param task Task，已经确认处于活动态的任务
     * @return 无返回值，实例关联缺失或非活动态时抛出稳定 HTTP 409 业务异常
     */
    private void requireActiveProcessInstance(Task task)
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
    }

    /**
     * 在当前 Flowable 写事务中保存服务端生成的结构化审计 comment。
     *
     * @param task Task，动作前从引擎重新读取的真实任务
     * @param commentType String，兼容旧系统的 Flowable comment 类型
     * @param action String，服务端固定的动作编码
     * @param actorUserId String，事务内重新解析的当前用户主键
     * @param targetUserId String，正式启用目标用户主键；认领和取消认领时为空
     * @param opinion String，已通过长度门禁的受控业务意见
     * @return 无返回值，comment 写入失败时整个任务状态事务回滚
     */
    private void addAuditComment(Task task, String commentType, String action,
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
        taskService.addComment(task.getId(), task.getProcessInstanceId(), commentType, audit.toString());
    }

    /**
     * 二次校验委派或转办意见，防止非 HTTP 调用绕过 DTO 门禁。
     *
     * @param opinion String，待写入审计 JSON 的业务意见
     * @return String，去除首尾空白后的受控意见
     */
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

    /**
     * 判断当前身份是否为任务的直接候选人，或属于任务声明的 ROLE/DEPT 候选组。
     *
     * @param taskId String，待认领任务 ID
     * @param actor WorkflowCurrentIdentity，事务内重新核验后的当前身份
     * @return boolean，满足任一有效 candidate 身份时返回 true
     */
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

    /**
     * 校验当前用户是任务的实际办理人，阻止无关用户执行委派、解决委派或转办。
     *
     * @param task Task，已确认处于活动态的任务
     * @param actor WorkflowCurrentIdentity，事务内重新核验后的当前身份
     * @return 无返回值，不是当前办理人时抛出稳定 HTTP 403 业务异常
     */
    private void requireCurrentAssignee(Task task, WorkflowCurrentIdentity actor)
    {
        if (!actor.userId().equals(task.getAssignee()))
        {
            throw forbidden();
        }
    }

    /**
     * 从正式主数据校验并规范化客户端指定的目标用户。
     *
     * @param targetUserId String，客户端提交的目标若依用户 ID
     * @return String，仍存在、启用且当前具备 workflow:process:approval 权限的规范用户 ID
     */
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

    /**
     * 实时核验当前用户可走通待签、认领、待办详情和审批主路径。
     *
     * @param actor WorkflowCurrentIdentity，事务内重新核验后的当前身份
     * @return 无返回值；当前用户缺少任一完整认领权限时抛出稳定 HTTP 403
     */
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

    /**
     * 判断引擎中保存的 owner 是否为规范格式且仍具备正式流程办理权限。
     *
     * @param ownerUserId String，Flowable 任务保存的 owner 用户 ID
     * @return boolean，仅 owner 非空、格式规范、用户有效且具备 workflow:process:approval 时返回 true
     */
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

    /**
     * 校验引擎适配器必填字符串，避免将空参数传入引擎并泄露内部异常信息。
     *
     * @param value String，待校验参数
     * @return 无返回值，参数为空时抛出稳定 HTTP 400 业务异常
     */
    private void requireText(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalidArgument();
        }
    }

    /**
     * 创建适配器参数错误异常。
     *
     * @return ServiceException，稳定 HTTP 400 业务异常
     */
    private ServiceException invalidArgument()
    {
        return new ServiceException(WorkflowExceptionTranslator.INVALID_ARGUMENT_MESSAGE, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建任务不存在异常。
     *
     * @return ServiceException，稳定 HTTP 404 业务异常
     */
    private ServiceException notFound()
    {
        return new ServiceException(WorkflowExceptionTranslator.OBJECT_NOT_FOUND_MESSAGE, HttpStatus.NOT_FOUND);
    }

    /**
     * 创建任务状态冲突异常。
     *
     * @return ServiceException，稳定 HTTP 409 业务异常
     */
    private ServiceException conflict()
    {
        return new ServiceException(WorkflowExceptionTranslator.CONFLICT_MESSAGE, HttpStatus.CONFLICT);
    }

    /**
     * 创建业务事务钩子返回非法计划时的数据一致性异常。
     *
     * @return ServiceException，稳定 HTTP 500 业务异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /**
     * 创建任务权限拒绝异常。
     *
     * @return ServiceException，稳定 HTTP 403 业务异常
     */
    private ServiceException forbidden()
    {
        return new ServiceException(WorkflowExceptionTranslator.FORBIDDEN_MESSAGE, HttpStatus.FORBIDDEN);
    }
}
