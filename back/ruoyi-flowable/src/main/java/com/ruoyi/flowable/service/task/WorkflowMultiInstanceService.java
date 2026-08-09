package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceUserRow;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentAction;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceMemberView;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowUserSelectionValidator;
import com.ruoyi.flowable.mapper.WorkflowMultiInstanceUserMapper;

/**
 * 动态并行多实例查询与调整领域服务，统一执行对象授权、BPMN 白名单、revision CAS 和写后对账。
 */
@Service
public class WorkflowMultiInstanceService
{
    /** 客户端可据此只刷新动态多实例状态的稳定 revision 冲突子码。 */
    public static final String REVISION_CONFLICT_SUB_CODE =
            "WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT";

    /** 普通审批意见使用的既有 Flowable comment 类型。 */
    private static final String NORMAL_COMMENT_TYPE = "1";

    /** 任务和 execution 主键允许的最大字符数。 */
    private static final int MAX_ID_LENGTH = 64;

    /** 审批意见允许的最大字符数。 */
    private static final int MAX_COMMENT_LENGTH = 500;

    /** Flowable 多实例根维护的总实例数变量。 */
    private static final String NUMBER_OF_INSTANCES = "nrOfInstances";

    /** Flowable 多实例根维护的活动实例数变量。 */
    private static final String NUMBER_OF_ACTIVE_INSTANCES = "nrOfActiveInstances";

    /** Flowable 多实例根维护的已完成实例数变量。 */
    private static final String NUMBER_OF_COMPLETED_INSTANCES = "nrOfCompletedInstances";

    /** Flowable 8 加签命令在多实例根已并发消失时使用的唯一稳定消息前缀。 */
    private static final String MISSING_MULTI_INSTANCE_EXECUTION_MESSAGE =
            "No multi instance execution found for activity id ";

    /** 结构化审计专用 JSON 序列化器。 */
    private static final ObjectMapper AUDIT_MAPPER = JsonMapper.shared();

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowIdentityResolver identityResolver;

    private final WorkflowUserSelectionValidator userSelectionValidator;

    private final WorkflowMultiInstanceUserMapper userMapper;

    private final RepositoryService repositoryService;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    private final HistoryService historyService;

    /**
     * 创建动态多实例领域服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一事务、认证和 Flowable 异常翻译边界
     * @param identityResolver WorkflowIdentityResolver，只读状态接口的正式当前用户解析器
     * @param userSelectionValidator WorkflowUserSelectionValidator，加签用户正式主数据校验器
     * @param userMapper WorkflowMultiInstanceUserMapper，成员名称批量查询 Mapper
     * @param repositoryService RepositoryService，部署 BPMN 模型公共查询服务
     * @param runtimeService RuntimeService，execution、变量和动态多实例公共写服务
     * @param taskService TaskService，活动任务和 Flowable comment 公共服务
     * @param historyService HistoryService，区分过期任务和不存在对象的历史查询服务
     * @return 无返回值，构造后由 Spring 管理该领域服务
     */
    public WorkflowMultiInstanceService(WorkflowEngineOperations engineOperations,
            WorkflowIdentityResolver identityResolver,
            WorkflowUserSelectionValidator userSelectionValidator,
            WorkflowMultiInstanceUserMapper userMapper,
            RepositoryService repositoryService, RuntimeService runtimeService,
            TaskService taskService, HistoryService historyService)
    {
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.userSelectionValidator = userSelectionValidator;
        this.userMapper = userMapper;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
    }

    /**
     * 查询当前办理人所在动态多实例根的服务端成员状态和 revision。
     *
     * @param taskId String，当前登录用户真实办理的活动任务主键
     * @return WorkflowMultiInstanceStateView，ALL/ANY、活动 ID、revision 和有序成员状态
     */
    public WorkflowMultiInstanceStateView getState(String taskId)
    {
        String normalizedTaskId = requireId(taskId);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            MultiInstanceContext context = loadContext(normalizedTaskId, actor);
            return buildState(context);
        });
    }

    /**
     * 为流程详情返回无探测式动态多实例能力；普通任务、历史任务或非当前办理人均返回 null。
     *
     * @param taskId String，详情已经完成对象授权的可选任务主键
     * @return WorkflowMultiInstanceStateView，当前用户可调整的动态多实例状态；不适用时为 null
     */
    public WorkflowMultiInstanceStateView getOptionalState(String taskId)
    {
        if (!StringUtils.hasText(taskId))
        {
            return null;
        }
        String normalizedTaskId = requireId(taskId);
        return engineOperations.read(() ->
        {
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            Task task = taskService.createTaskQuery().taskId(normalizedTaskId)
                    .active().singleResult();
            if (task == null || task.isSuspended()
                    || !Objects.equals(actor.userId(), task.getAssignee())
                    || !isSupportedDynamicTask(task))
            {
                return null;
            }
            return buildState(loadContext(normalizedTaskId, actor));
        });
    }

    /**
     * 以当前正式办理人执行动态加签或减签，并在同一事务完成结构变更、CAS、审计和写后对账。
     *
     * @param request WorkflowMultiInstanceAdjustmentRequest，带预期 revision 的受控调整请求
     * @return WorkflowMultiInstanceStateView，提交成功后的最新服务端状态
     */
    public WorkflowMultiInstanceStateView adjust(
            WorkflowMultiInstanceAdjustmentRequest request)
    {
        AdjustmentInput input = normalizeAdjustment(request);
        try
        {
            return engineOperations.writeAsCurrentUser(actor ->
            {
                MultiInstanceContext context = loadContext(input.taskId(), actor);
                if (context.revision() != input.expectedRevision())
                {
                    throw revisionConflict();
                }
                if (input.action() == WorkflowMultiInstanceAdjustmentAction.ADD)
                {
                    return addMembers(context, input, actor);
                }
                return removeMember(context, input, actor);
            });
        }
        catch (RuntimeException exception)
        {
            // Flowable 乐观锁也可能直到事务代理提交时才暴露，必须在代理外补齐动态 revision 子码。
            throw engineOperations.withConcurrencyConflictSubCode(exception,
                    REVISION_CONFLICT_SUB_CODE);
        }
    }

    /**
     * 在完成动态多实例任务前校验客户端 revision，并把服务端 revision 原子推进一位。
     *
     * @param task Task，生命周期服务已完成活动态和办理人校验的当前任务
     * @param expectedRevision Long，客户端从详情读取的动态多实例 revision；普通任务为空
     * @param actor WorkflowCurrentIdentity，当前事务内重新核验的正式办理人
     * @return CompletionRevision，动态多实例返回活动及前后 revision；普通任务返回空计划
     */
    CompletionRevision reserveCompletionRevision(Task task, Long expectedRevision,
            WorkflowCurrentIdentity actor)
    {
        if (task == null || actor == null)
        {
            throw dataError();
        }
        boolean dynamicMultiInstance = isSupportedDynamicTask(task);
        if (!dynamicMultiInstance)
        {
            if (expectedRevision != null)
            {
                throw completionRevisionInvalid();
            }
            return CompletionRevision.none();
        }
        if (expectedRevision == null || expectedRevision < 0
                || expectedRevision > Integer.MAX_VALUE)
        {
            throw completionRevisionInvalid();
        }

        MultiInstanceContext context = loadContext(task.getId(), actor);
        if (context.revision() != expectedRevision.intValue())
        {
            throw revisionConflict();
        }
        int nextRevision = requireNextRevision(context.revision());
        // COMPLETE 与 ADD/REMOVE 共用同一 revision-first 锁顺序，禁止同版本动作双提交。
        advanceRevision(context, nextRevision);
        return new CompletionRevision(context.activityId(), context.revision(), nextRevision);
    }

    /**
     * 二次校验请求的动作专属字段、主键、revision 和业务意见，阻止非 HTTP 调用绕过 DTO 门禁。
     *
     * @param request WorkflowMultiInstanceAdjustmentRequest，客户端或内部调用提交的原始请求
     * @return AdjustmentInput，字段组合唯一且内容已规范化的不可变命令
     */
    private AdjustmentInput normalizeAdjustment(
            WorkflowMultiInstanceAdjustmentRequest request)
    {
        if (request == null || request.action() == null
                || request.expectedRevision() == null
                || request.expectedRevision() < 0
                || request.expectedRevision() > Integer.MAX_VALUE)
        {
            throw invalidArgument();
        }
        String taskId = requireId(request.taskId());
        String comment = requireComment(request.comment());
        List<Long> rawUserIds = request.userIds();
        if (rawUserIds != null && rawUserIds.stream().anyMatch(Objects::isNull))
        {
            // 领域服务必须独立防御非 HTTP 调用，不能让 List.copyOf 的 NPE 泄漏为非稳定 500。
            throw invalidArgument();
        }
        List<Long> userIds = rawUserIds == null ? List.of() : List.copyOf(rawUserIds);
        if (request.action() == WorkflowMultiInstanceAdjustmentAction.ADD)
        {
            if (userIds.isEmpty() || request.targetTaskId() != null)
            {
                throw invalidArgument();
            }
            return new AdjustmentInput(taskId, request.action(),
                    request.expectedRevision().intValue(), comment, userIds, null);
        }
        if (!userIds.isEmpty())
        {
            throw invalidArgument();
        }
        String targetTaskId = requireId(request.targetTaskId());
        return new AdjustmentInput(taskId, request.action(),
                request.expectedRevision().intValue(), comment, List.of(), targetTaskId);
    }

    /**
     * 为当前并行多实例根增加一个或多个正式用户 execution，并通过 revision 变量形成并发 CAS。
     *
     * @param context MultiInstanceContext，写命令前冻结且已完整对账的引擎状态
     * @param input AdjustmentInput，ADD 命令及业务意见
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前办理人
     * @return WorkflowMultiInstanceStateView，写后重新查询并对账的最新状态
     */
    private WorkflowMultiInstanceStateView addMembers(MultiInstanceContext context,
            AdjustmentInput input, WorkflowCurrentIdentity actor)
    {
        // revision 已达到 int 上限时必须在任何 execution、变量和审计写入前拒绝，避免溢出回负数。
        int nextRevision = requireNextRevision(context.revision());
        // 加签执行前实时重查目标用户审批资格，失败时 revision、execution、变量和审计均不得写入。
        List<String> targetUserIds = userSelectionValidator
                .requireApprovalEligibleUserIds(input.userIds());
        if (targetUserIds.isEmpty()
                || context.memberIds().size() + targetUserIds.size()
                    > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw invalidArgument();
        }
        Set<String> existingMembers = new LinkedHashSet<>(context.memberIds());
        for (String targetUserId : targetUserIds)
        {
            if (!existingMembers.add(targetUserId))
            {
                throw invalidArgument();
            }
        }

        // 三类动作必须先争抢同一 revision 变量，再改变 execution，避免反向锁序和双提交。
        advanceRevision(context, nextRevision);
        // Flowable 公共 API 负责真实创建 execution 和 task，禁止直接改写 nrOf* 计数变量。
        for (String targetUserId : targetUserIds)
        {
            addMultiInstanceExecution(context, targetUserId);
        }
        List<String> nextMembers = new ArrayList<>(context.memberIds());
        nextMembers.addAll(targetUserIds);
        persistMemberState(context, nextMembers);
        addAuditComment(context.currentTask(), "MULTI_INSTANCE_ADD", actor.userId(),
                context.activityId(), context.revision(), nextRevision, input.comment(),
                targetUserIds, null, null);

        MultiInstanceContext updated = loadContext(input.taskId(), actor);
        if (updated.revision() != nextRevision || !updated.memberIds().equals(nextMembers))
        {
            throw dataError();
        }
        Set<String> activeAssignees = updated.activeTasks().stream()
                .map(Task::getAssignee).collect(Collectors.toSet());
        if (!activeAssignees.containsAll(targetUserIds))
        {
            throw dataError();
        }
        return buildState(updated);
    }

    /**
     * 删除同一多实例根下尚未完成的 sibling execution，并保证至少保留一个活动办理路径。
     *
     * @param context MultiInstanceContext，写命令前冻结且已完整对账的引擎状态
     * @param input AdjustmentInput，REMOVE 命令、目标任务和业务意见
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前办理人
     * @return WorkflowMultiInstanceStateView，写后重新查询并对账的最新状态
     */
    private WorkflowMultiInstanceStateView removeMember(MultiInstanceContext context,
            AdjustmentInput input, WorkflowCurrentIdentity actor)
    {
        // 与加签共用同一写前上限门禁，确保减签不会先删除 execution 再发现 revision 无法递增。
        int nextRevision = requireNextRevision(context.revision());
        if (Objects.equals(input.taskId(), input.targetTaskId())
                || context.activeTasks().size() <= 1)
        {
            throw conflict();
        }
        Task targetTask = context.activeTasks().stream()
                .filter(task -> input.targetTaskId().equals(task.getId()))
                .findFirst().orElseThrow(this::conflict);
        if (targetTask.isSuspended() || StringUtils.hasText(targetTask.getOwner())
                || targetTask.getDelegationState() != null
                || !StringUtils.hasText(targetTask.getExecutionId())
                || !StringUtils.hasText(targetTask.getAssignee()))
        {
            throw conflict();
        }

        List<String> nextMembers = new ArrayList<>(context.memberIds());
        if (!nextMembers.remove(targetTask.getAssignee())
                || nextMembers.contains(targetTask.getAssignee()))
        {
            throw dataError();
        }
        // 与加签、完成使用相同的 revision-first 锁顺序，输掉 CAS 的事务不得先删除 execution。
        advanceRevision(context, nextRevision);
        // false 保留真实历史语义，减签不得伪装成完成或级联物理删除已完成成员。
        deleteMultiInstanceExecution(targetTask);
        persistMemberState(context, nextMembers);
        addAuditComment(context.currentTask(), "MULTI_INSTANCE_REMOVE", actor.userId(),
                context.activityId(), context.revision(), nextRevision, input.comment(),
                List.of(), targetTask.getId(), targetTask.getAssignee());

        MultiInstanceContext updated = loadContext(input.taskId(), actor);
        boolean targetStillActive = updated.activeTasks().stream()
                .anyMatch(task -> input.targetTaskId().equals(task.getId())
                        || targetTask.getExecutionId().equals(task.getExecutionId()));
        if (targetStillActive || updated.revision() != nextRevision
                || !updated.memberIds().equals(nextMembers))
        {
            throw dataError();
        }
        return buildState(updated);
    }

    /**
     * 调用 Flowable 公共加签 API，并只把“多实例根已并发消失”翻译为稳定 409。
     *
     * @param context MultiInstanceContext，已经通过 revision CAS 的当前多实例上下文
     * @param targetUserId String，待创建 execution 的正式办理人主键
     * @return 无返回值，其他 FlowableException 保持原样交给统一异常翻译器
     */
    private void addMultiInstanceExecution(MultiInstanceContext context,
            String targetUserId)
    {
        try
        {
            runtimeService.addMultiInstanceExecution(context.activityId(),
                    context.processInstanceId(),
                    Map.of(WorkflowMultiInstanceModelContract.ELEMENT_VARIABLE,
                            targetUserId));
        }
        catch (FlowableException exception)
        {
            String missingMessage = MISSING_MULTI_INSTANCE_EXECUTION_MESSAGE
                    + context.activityId();
            if (exception.getClass() == FlowableException.class
                    && Objects.equals(missingMessage, exception.getMessage()))
            {
                throw revisionConflict(exception);
            }
            throw exception;
        }
    }

    /**
     * 调用 Flowable 公共减签 API，并仅在目标 task 与 execution 均已并发消失时翻译其内部 NPE。
     *
     * @param targetTask Task，写前冻结且属于同一多实例根的目标活动任务
     * @return 无返回值，目标仍存在时保留 NPE 作为真实引擎故障
     */
    private void deleteMultiInstanceExecution(Task targetTask)
    {
        try
        {
            runtimeService.deleteMultiInstanceExecution(targetTask.getExecutionId(), false);
        }
        catch (NullPointerException exception)
        {
            // Flowable 8.0.0 的删除命令对缺失 execution 没有空值门禁，只能在窄调用边界二次确认。
            Task remainingTask = taskService.createTaskQuery().taskId(targetTask.getId())
                    .singleResult();
            Execution remainingExecution = runtimeService.createExecutionQuery()
                    .executionId(targetTask.getExecutionId()).singleResult();
            if (remainingTask == null && remainingExecution == null)
            {
                throw revisionConflict(exception);
            }
            throw exception;
        }
    }

    /**
     * 从公共 Flowable API 重新加载并核验当前任务、多实例根、BPMN、变量、任务和 execution 一致性。
     *
     * @param taskId String，当前活动任务主键
     * @param actor WorkflowCurrentIdentity，当前事务内正式用户身份
     * @return MultiInstanceContext，可用于一次读或写决策的完整不可变状态
     */
    private MultiInstanceContext loadContext(String taskId, WorkflowCurrentIdentity actor)
    {
        Task currentTask = requireActiveTask(taskId, actor);
        UserTask userTask = requireDynamicUserTask(currentTask);
        String activityId = userTask.getId();
        Execution rootExecution = requireMultiInstanceRoot(currentTask, activityId);
        EngineCounts counts = loadEngineCounts(rootExecution.getId());
        List<Task> activeTasks = loadActiveSiblingTasks(currentTask, rootExecution,
                activityId, counts);
        List<String> memberIds = loadMemberSnapshot(currentTask.getProcessInstanceId(),
                activityId);
        int revision = loadRevision(currentTask.getProcessInstanceId(), activityId);
        WorkflowMultiInstanceMode mode = loadMode(currentTask.getProcessInstanceId(),
                activityId);
        WorkflowMultiInstanceMode modelMode;
        try
        {
            modelMode = WorkflowMultiInstanceModelContract.requireMode(userTask);
        }
        catch (IllegalArgumentException exception)
        {
            throw conflict();
        }
        if (mode != modelMode)
        {
            throw dataError();
        }
        verifyEngineState(memberIds, activeTasks, counts);
        return new MultiInstanceContext(currentTask, currentTask.getProcessInstanceId(),
                activityId, rootExecution, activeTasks, memberIds, revision, mode, counts);
    }

    /**
     * 查询活动任务并校验流程运行态与当前正式 assignee，对跨用户对象访问返回稳定 403。
     *
     * @param taskId String，已规范化的任务主键
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前用户
     * @return Task，当前用户真实办理且所属实例活动的任务
     */
    private Task requireActiveTask(String taskId, WorkflowCurrentIdentity actor)
    {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null)
        {
            Task runtimeTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (runtimeTask != null)
            {
                throw conflict();
            }
            HistoricTaskInstance historicTask = historyService
                    .createHistoricTaskInstanceQuery().taskId(taskId).singleResult();
            if (historicTask != null && Objects.equals(actor.userId(),
                    historicTask.getAssignee()))
            {
                throw conflict();
            }
            throw notFound();
        }
        if (task.isSuspended())
        {
            throw conflict();
        }
        if (!Objects.equals(actor.userId(), task.getAssignee()))
        {
            throw forbidden();
        }
        if (!StringUtils.hasText(task.getProcessInstanceId())
                || !StringUtils.hasText(task.getProcessDefinitionId())
                || !StringUtils.hasText(task.getTaskDefinitionKey())
                || !StringUtils.hasText(task.getExecutionId()))
        {
            throw dataError();
        }
        ProcessInstance instance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).active().singleResult();
        if (instance == null || instance.isSuspended()
                || !Objects.equals(task.getProcessDefinitionId(),
                    instance.getProcessDefinitionId()))
        {
            throw conflict();
        }
        return task;
    }

    /**
     * 从任务所属流程定义对应的 BPMN Process 递归读取当前节点，并确保它是固定动态并行 UserTask。
     *
     * @param task Task，已经完成活动态和对象授权校验的任务
     * @return UserTask，满足动态多实例白名单的部署节点
     */
    private UserTask requireDynamicUserTask(Task task)
    {
        FlowElement element = requireTaskFlowElement(task);
        if (!(element instanceof UserTask userTask))
        {
            throw conflict();
        }
        try
        {
            WorkflowMultiInstanceModelContract.requireMode(userTask);
        }
        catch (IllegalArgumentException exception)
        {
            throw conflict();
        }
        return userTask;
    }

    /**
     * 判断活动任务是否使用受控动态集合；静态/普通节点兼容返回 false，畸形受控模型拒绝降级。
     *
     * @param task Task，详情任务主键重新读取到的真实活动任务
     * @return boolean，任务所属流程定义节点满足固定动态并行多实例模型时返回 true
     */
    private boolean isSupportedDynamicTask(Task task)
    {
        FlowElement element = requireTaskFlowElement(task);
        if (!(element instanceof UserTask userTask)
                || userTask.getLoopCharacteristics() == null
                || !WorkflowMultiInstanceModelContract.usesDynamicHandler(
                    userTask.getLoopCharacteristics()))
        {
            // 固定成员、普通任务和既有静态多实例均不参加动态 revision 或加减签契约。
            return false;
        }
        try
        {
            WorkflowMultiInstanceModelContract.requireMode(userTask);
            return true;
        }
        catch (IllegalArgumentException exception)
        {
            // 已声明受控 handler 却不满足完整白名单属于部署模型冲突，禁止伪装普通任务绕过 CAS。
            throw conflict();
        }
    }

    /**
     * 按任务的 processDefinitionId 读取流程定义 key，并在对应 BPMN Process 中递归定位节点。
     *
     * @param task Task，待分类的真实活动任务
     * @return FlowElement，任务所属 BPMN Process 中与 taskDefinitionKey 对应的节点
     */
    private FlowElement requireTaskFlowElement(Task task)
    {
        if (task == null || !StringUtils.hasText(task.getProcessDefinitionId())
                || !StringUtils.hasText(task.getTaskDefinitionKey()))
        {
            throw dataError();
        }
        String processDefinitionId = task.getProcessDefinitionId();
        ProcessDefinition definition;
        BpmnModel model;
        try
        {
            definition = repositoryService.getProcessDefinition(processDefinitionId);
            model = repositoryService.getBpmnModel(processDefinitionId);
        }
        catch (FlowableObjectNotFoundException exception)
        {
            // 任务仍存在而部署定义已消失属于服务端关联漂移，不能误报成用户可见的对象 404。
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
        // 同一部署可包含多个 Process；只能在任务定义所属 Process 内递归查找 SubProcess 节点。
        FlowElement element = process.getFlowElement(task.getTaskDefinitionKey(), true);
        if (element == null)
        {
            throw dataError();
        }
        return element;
    }

    /**
     * 从当前任务 execution 定位唯一多实例根，并拒绝结束、挂起或活动 ID 不一致的执行树。
     *
     * @param task Task，当前办理人的真实活动任务
     * @param activityId String，部署 BPMN 用户任务活动 ID
     * @return Execution，当前任务的直接父多实例根 execution
     */
    private Execution requireMultiInstanceRoot(Task task, String activityId)
    {
        Execution taskExecution = runtimeService.createExecutionQuery()
                .executionId(task.getExecutionId()).singleResult();
        if (taskExecution == null || taskExecution.isEnded()
                || taskExecution.isSuspended()
                || !StringUtils.hasText(taskExecution.getParentId())
                || !Objects.equals(activityId, taskExecution.getActivityId()))
        {
            throw conflict();
        }
        Execution rootExecution = runtimeService.createExecutionQuery()
                .executionId(taskExecution.getParentId()).singleResult();
        if (rootExecution == null || rootExecution.isEnded()
                || rootExecution.isSuspended()
                || !Objects.equals(task.getProcessInstanceId(),
                    rootExecution.getProcessInstanceId())
                || !Objects.equals(activityId, rootExecution.getActivityId()))
        {
            throw conflict();
        }
        return rootExecution;
    }

    /**
     * 加载同流程同节点全部活动任务，并证明每个任务 execution 都直接属于同一多实例根。
     *
     * @param currentTask Task，当前用户用于对象授权的活动任务
     * @param rootExecution Execution，当前任务解析出的多实例根
     * @param activityId String，部署 BPMN 活动 ID
     * @param counts EngineCounts，多实例根本地总数、活动数和完成数快照
     * @return List&lt;Task&gt;，按任务创建时间和主键稳定排序的同根活动任务
     */
    private List<Task> loadActiveSiblingTasks(Task currentTask, Execution rootExecution,
            String activityId, EngineCounts counts)
    {
        List<Task> tasks = taskService.createTaskQuery()
                .processInstanceId(currentTask.getProcessInstanceId())
                .taskDefinitionKey(activityId).active().list();
        if (tasks == null || tasks.isEmpty())
        {
            throw conflict();
        }
        List<Execution> childExecutions = runtimeService.createExecutionQuery()
                .parentId(rootExecution.getId()).list();
        if (childExecutions == null || childExecutions.size() != counts.instances()
                || tasks.size() != counts.active()
                || childExecutions.size() - tasks.size() != counts.completed())
        {
            throw dataError();
        }
        Map<String, Execution> childExecutionsById = new LinkedHashMap<>();
        LinkedHashSet<String> activeChildIds = new LinkedHashSet<>();
        int inactiveChildCount = 0;
        for (Execution childExecution : childExecutions)
        {
            if (childExecution == null || childExecution.isEnded()
                    || childExecution.isSuspended()
                    || !Objects.equals(rootExecution.getId(), childExecution.getParentId())
                    || !Objects.equals(currentTask.getProcessInstanceId(),
                        childExecution.getProcessInstanceId())
                    || !Objects.equals(activityId, childExecution.getActivityId())
                    || !StringUtils.hasText(childExecution.getId())
                    || childExecutionsById.putIfAbsent(childExecution.getId(),
                        childExecution) != null)
            {
                throw dataError();
            }
            List<String> activeActivityIds = runtimeService.getActiveActivityIds(
                    childExecution.getId());
            if (activeActivityIds == null)
            {
                throw dataError();
            }
            if (activeActivityIds.isEmpty())
            {
                inactiveChildCount++;
            }
            else if (activeActivityIds.size() == 1
                    && Objects.equals(activityId, activeActivityIds.get(0)))
            {
                activeChildIds.add(childExecution.getId());
            }
            else
            {
                // 固定主流程 UserTask 的 child 不得同时暴露其他活动或复杂子树。
                throw dataError();
            }
        }
        LinkedHashSet<String> taskExecutionIds = new LinkedHashSet<>();
        LinkedHashSet<String> assigneeIds = new LinkedHashSet<>();
        boolean currentTaskFound = false;
        for (Task task : tasks)
        {
            if (task == null || task.isSuspended()
                    || !Objects.equals(currentTask.getProcessDefinitionId(),
                        task.getProcessDefinitionId())
                    || !Objects.equals(activityId, task.getTaskDefinitionKey())
                    || !StringUtils.hasText(task.getId())
                    || !StringUtils.hasText(task.getExecutionId())
                    || !StringUtils.hasText(task.getAssignee())
                    || !taskExecutionIds.add(task.getExecutionId())
                    || !assigneeIds.add(task.getAssignee()))
            {
                throw dataError();
            }
            Execution taskExecution = childExecutionsById.get(task.getExecutionId());
            if (taskExecution == null || !activeChildIds.contains(taskExecution.getId()))
            {
                // 同一查询快照内活动 task 没有对应 active child，属于引擎状态漂移而非客户端过期。
                throw dataError();
            }
            currentTaskFound |= Objects.equals(currentTask.getId(), task.getId());
        }
        if (!currentTaskFound)
        {
            throw conflict();
        }
        // Flowable 8 会保留已完成的 inactive child；公共活动查询必须与 task/counts 精确闭合。
        if (!activeChildIds.equals(taskExecutionIds)
                || activeChildIds.size() != counts.active()
                || inactiveChildCount != counts.completed())
        {
            throw dataError();
        }
        List<Task> sortedTasks = new ArrayList<>(tasks);
        sortedTasks.sort((left, right) ->
        {
            int timeOrder = compareNullable(left.getCreateTime(), right.getCreateTime());
            return timeOrder != 0 ? timeOrder : left.getId().compareTo(right.getId());
        });
        return Collections.unmodifiableList(sortedTasks);
    }

    /**
     * 读取服务端成员快照并严格验证数字格式、顺序、唯一性和 1 至 100 人边界。
     *
     * @param processInstanceId String，当前流程实例主键
     * @param activityId String，动态多实例活动 ID
     * @return List&lt;String&gt;，有序且不可修改的正式成员用户主键
     */
    private List<String> loadMemberSnapshot(String processInstanceId, String activityId)
    {
        Object rawMembers = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.memberSnapshotName(activityId));
        if (!(rawMembers instanceof List<?> members) || members.isEmpty()
                || members.size() > WorkflowUserSelectionValidator.MAX_SELECTED_USERS)
        {
            throw dataError();
        }
        LinkedHashSet<String> canonicalIds = new LinkedHashSet<>();
        for (Object member : members)
        {
            if (!(member instanceof String userId))
            {
                throw dataError();
            }
            String canonicalId = requireCanonicalUserId(userId);
            if (!canonicalIds.add(canonicalId))
            {
                throw dataError();
            }
        }
        return List.copyOf(canonicalIds);
    }

    /**
     * 读取服务端 revision 并拒绝负数、浮点或超出 int 上限的持久化异常。
     *
     * @param processInstanceId String，当前流程实例主键
     * @param activityId String，动态多实例活动 ID
     * @return int，当前非负调整版本
     */
    private int loadRevision(String processInstanceId, String activityId)
    {
        return requireNonNegativeInteger(runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.revisionName(activityId)));
    }

    /**
     * 读取服务端完成模式变量并只接受 ALL 或 ANY。
     *
     * @param processInstanceId String，当前流程实例主键
     * @param activityId String，动态多实例活动 ID
     * @return WorkflowMultiInstanceMode，服务端初始化时冻结的完成模式
     */
    private WorkflowMultiInstanceMode loadMode(String processInstanceId, String activityId)
    {
        Object rawMode = runtimeService.getVariable(processInstanceId,
                WorkflowMultiInstanceVariables.modeName(activityId));
        if (!(rawMode instanceof String mode))
        {
            throw dataError();
        }
        try
        {
            return WorkflowMultiInstanceMode.valueOf(mode);
        }
        catch (IllegalArgumentException exception)
        {
            throw dataError();
        }
    }

    /**
     * 从多实例根本地作用域读取 Flowable 三项计数，禁止服务端自行推测或改写。
     *
     * @param rootExecutionId String，多实例根 execution 主键
     * @return EngineCounts，非负总数、活动数和完成数
     */
    private EngineCounts loadEngineCounts(String rootExecutionId)
    {
        int instances = requireNonNegativeInteger(runtimeService.getVariableLocal(
                rootExecutionId, NUMBER_OF_INSTANCES));
        int active = requireNonNegativeInteger(runtimeService.getVariableLocal(
                rootExecutionId, NUMBER_OF_ACTIVE_INSTANCES));
        int completed = requireNonNegativeInteger(runtimeService.getVariableLocal(
                rootExecutionId, NUMBER_OF_COMPLETED_INSTANCES));
        return new EngineCounts(instances, active, completed);
    }

    /**
     * 对账成员快照、活动任务办理人和 Flowable 根计数，发现漂移时阻断读取或回滚写命令。
     *
     * @param memberIds List&lt;String&gt;，服务端正式成员快照
     * @param activeTasks List&lt;Task&gt;，同根真实活动任务
     * @param counts EngineCounts，Flowable 根本地计数
     * @return 无返回值，任一数量或成员关系不一致时抛出 HTTP 500
     */
    private void verifyEngineState(List<String> memberIds, List<Task> activeTasks,
            EngineCounts counts)
    {
        if (counts.instances() != memberIds.size()
                || counts.active() != activeTasks.size()
                || counts.completed() + counts.active() != counts.instances())
        {
            throw dataError();
        }
        Set<String> members = new LinkedHashSet<>(memberIds);
        for (Task task : activeTasks)
        {
            if (!members.contains(task.getAssignee()))
            {
                throw dataError();
            }
        }
    }

    /**
     * 把完整引擎上下文转换为页面状态，并按一次批量主数据查询补齐最小用户名称。
     *
     * @param context MultiInstanceContext，已通过全部一致性校验的当前状态
     * @return WorkflowMultiInstanceStateView，按成员快照顺序生成的不可变页面视图
     */
    private WorkflowMultiInstanceStateView buildState(MultiInstanceContext context)
    {
        Map<String, Task> activeTasksByUser = context.activeTasks().stream()
                .collect(Collectors.toMap(Task::getAssignee, Function.identity()));
        Map<String, String> names = loadUserNames(context.memberIds());
        List<WorkflowMultiInstanceMemberView> members = new ArrayList<>();
        for (String userId : context.memberIds())
        {
            Task activeTask = activeTasksByUser.get(userId);
            boolean active = activeTask != null;
            boolean removable = active && context.activeTasks().size() > 1
                    && !Objects.equals(context.currentTask().getId(), activeTask.getId())
                    && !activeTask.isSuspended()
                    && !StringUtils.hasText(activeTask.getOwner())
                    && activeTask.getDelegationState() == null;
            members.add(new WorkflowMultiInstanceMemberView(Long.valueOf(userId),
                    names.getOrDefault(userId, userId),
                    active ? activeTask.getId() : null,
                    active ? activeTask.getExecutionId() : null,
                    active, removable));
        }
        return new WorkflowMultiInstanceStateView(context.mode().name(),
                context.activityId(), context.revision(), members);
    }

    /**
     * 批量加载成员最小名称投影，缺失历史用户时回退数字 ID 并拒绝重复 Mapper 行。
     *
     * @param memberIds List&lt;String&gt;，服务端成员快照中的数字用户 ID
     * @return Map&lt;String, String&gt;，用户 ID 到安全展示名称的映射
     */
    private Map<String, String> loadUserNames(List<String> memberIds)
    {
        List<Long> numericIds = memberIds.stream().map(Long::valueOf).toList();
        List<WorkflowMultiInstanceUserRow> rows = userMapper.selectUserNamesByIds(numericIds);
        if (rows == null)
        {
            throw dataError();
        }
        Map<String, String> names = new HashMap<>();
        for (WorkflowMultiInstanceUserRow row : rows)
        {
            if (row == null || row.userId() == null || row.userId() <= 0)
            {
                throw dataError();
            }
            String userId = String.valueOf(row.userId());
            if (!memberIds.contains(userId) || names.putIfAbsent(userId,
                    StringUtils.hasText(row.name()) ? row.name().trim() : userId) != null)
            {
                throw dataError();
            }
        }
        return names;
    }

    /**
     * 把 revision 作为 ADD、REMOVE、COMPLETE 的第一项引擎写操作，形成统一乐观锁 CAS。
     *
     * @param context MultiInstanceContext，动作前已校验的多实例上下文
     * @param nextRevision int，严格递增一且可安全持久化的下一 revision
     * @return 无返回值，输掉变量 revision 乐观锁时整个外层事务回滚
     */
    private void advanceRevision(MultiInstanceContext context, int nextRevision)
    {
        runtimeService.setVariable(context.processInstanceId(),
                WorkflowMultiInstanceVariables.revisionName(context.activityId()),
                nextRevision);
    }

    /**
     * 在 revision CAS 成功后同步正式成员快照与 handler 受控集合变量。
     *
     * @param context MultiInstanceContext，已经推进 revision 的多实例上下文
     * @param nextMembers List&lt;String&gt;，动作后的完整有序成员快照
     * @return 无返回值，任一变量写入失败时 revision 与 execution 变更一并回滚
     */
    private void persistMemberState(MultiInstanceContext context,
            List<String> nextMembers)
    {
        runtimeService.setVariable(context.processInstanceId(),
                WorkflowMultiInstanceVariables.memberSnapshotName(context.activityId()),
                new ArrayList<>(nextMembers));
        // 同步受控集合变量，使 Flowable 重求值 handler 时只能读到与正式快照一致的当前成员。
        runtimeService.setVariable(context.processInstanceId(),
                WorkflowMultiInstanceVariables.userCollectionName(context.activityId()),
                nextMembers.stream().map(Long::valueOf).toList());
    }

    /**
     * 写入包含动作、操作人、节点、前后 revision、目标和业务意见的结构化 Flowable comment。
     *
     * @param currentTask Task，保留用于授权和审计关联的当前活动任务
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内正式操作人主键
     * @param activityId String，动态多实例 BPMN 活动 ID
     * @param beforeRevision int，动作前 revision
     * @param afterRevision int，动作后 revision
     * @param opinion String，受控业务意见
     * @param targetUserIds List&lt;String&gt;，ADD 的目标用户主键
     * @param targetTaskId String，REMOVE 的目标任务主键
     * @param targetUserId String，REMOVE 的目标办理人主键
     * @return 无返回值，comment 写入失败时当前事务整体回滚
     */
    private void addAuditComment(Task currentTask, String action, String actorUserId,
            String activityId, int beforeRevision, int afterRevision, String opinion,
            List<String> targetUserIds, String targetTaskId, String targetUserId)
    {
        ObjectNode audit = AUDIT_MAPPER.createObjectNode();
        audit.put("action", action);
        audit.put("actorUserId", actorUserId);
        audit.put("activityId", activityId);
        audit.put("beforeRevision", beforeRevision);
        audit.put("afterRevision", afterRevision);
        audit.put("opinion", opinion);
        if (!targetUserIds.isEmpty())
        {
            ArrayNode targets = audit.putArray("targetUserIds");
            targetUserIds.forEach(targets::add);
        }
        if (targetTaskId != null)
        {
            audit.put("targetTaskId", targetTaskId);
            audit.put("targetUserId", targetUserId);
        }
        taskService.addComment(currentTask.getId(), currentTask.getProcessInstanceId(),
                NORMAL_COMMENT_TYPE, audit.toString());
    }

    /**
     * 将持久化用户主键规范为无前导零的正整数文本。
     *
     * @param value String，服务端成员变量中的用户主键
     * @return String，规范数字用户主键
     */
    private String requireCanonicalUserId(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw dataError();
        }
        try
        {
            long userId = Long.parseLong(value);
            if (userId <= 0 || !String.valueOf(userId).equals(value))
            {
                throw dataError();
            }
            return value;
        }
        catch (NumberFormatException exception)
        {
            throw dataError();
        }
    }

    /**
     * 把 Flowable 数值变量精确读取为非负 int，拒绝浮点、溢出和类型漂移。
     *
     * @param value Object，revision 或 nrOf* 引擎变量值
     * @return int，未发生截断的非负整数
     */
    private int requireNonNegativeInteger(Object value)
    {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long))
        {
            throw dataError();
        }
        long numericValue = ((Number) value).longValue();
        if (numericValue < 0 || numericValue > Integer.MAX_VALUE)
        {
            throw dataError();
        }
        return (int) numericValue;
    }

    /**
     * 计算下一次动态调整 revision，并在 int 上限处于任何引擎写入前返回稳定冲突。
     *
     * @param currentRevision int，已经从服务端正式变量精确读取的当前非负 revision
     * @return int，可安全持久化且严格递增一的下一 revision
     */
    private int requireNextRevision(int currentRevision)
    {
        if (currentRevision == Integer.MAX_VALUE)
        {
            throw conflict();
        }
        return currentRevision + 1;
    }

    /**
     * 比较可空 Comparable 值，null 始终排在非 null 之前。
     *
     * @param left T，左侧可空值
     * @param right T，右侧可空值
     * @return int，标准比较结果
     */
    private <T extends Comparable<T>> int compareNullable(T left, T right)
    {
        if (left == null)
        {
            return right == null ? 0 : -1;
        }
        return right == null ? 1 : left.compareTo(right);
    }

    /**
     * 校验并规范任务主键。
     *
     * @param value String，客户端任务主键
     * @return String，去除首尾空白且长度受控的主键
     */
    private String requireId(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalidArgument();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ID_LENGTH)
        {
            throw invalidArgument();
        }
        return normalized;
    }

    /**
     * 校验并规范必须持久化的业务意见。
     *
     * @param value String，客户端提交的调整意见
     * @return String，去除首尾空白且不超过 500 字符的意见
     */
    private String requireComment(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalidArgument();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_COMMENT_LENGTH)
        {
            throw invalidArgument();
        }
        return normalized;
    }

    /**
     * 创建稳定的参数错误异常。
     *
     * @return ServiceException，HTTP 400 动态多实例参数错误
     */
    private ServiceException invalidArgument()
    {
        return new ServiceException("工作流多实例调整参数不合法", HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建动态多实例完成请求缺少、越界或误用于普通任务时的稳定参数异常。
     *
     * @return ServiceException，HTTP 400 完成任务 revision 参数错误
     */
    private ServiceException completionRevisionInvalid()
    {
        return new ServiceException("动态多实例任务版本不合法", HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建稳定的对象级授权异常。
     *
     * @return ServiceException，HTTP 403 当前用户不是任务真实办理人
     */
    private ServiceException forbidden()
    {
        return new ServiceException("无权调整当前多实例任务", HttpStatus.FORBIDDEN);
    }

    /**
     * 创建稳定的对象不存在异常。
     *
     * @return ServiceException，HTTP 404 任务不存在且无当前用户可见历史
     */
    private ServiceException notFound()
    {
        return new ServiceException("工作流对象不存在或已被删除", HttpStatus.NOT_FOUND);
    }

    /**
     * 创建稳定的 revision、任务状态或执行树冲突异常。
     *
     * @return ServiceException，HTTP 409 客户端需要刷新后重试
     */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /**
     * 创建仅用于客户端 expectedRevision 与服务端正式 revision 不一致的稳定冲突异常。
     *
     * @return ServiceException，HTTP 409 且携带 WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT 子码
     */
    private ServiceException revisionConflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT)
                .setSubCode(REVISION_CONFLICT_SUB_CODE);
    }

    /**
     * 创建保留引擎首因的动态 revision 并发冲突异常。
     *
     * @param cause Throwable，CAS 后被其他事务删除的多实例根、任务或 execution 异常
     * @return ServiceException，HTTP 409、稳定 revision 子码且 cause 仅供服务端日志追踪
     */
    private ServiceException revisionConflict(Throwable cause)
    {
        ServiceException failure = revisionConflict();
        failure.initCause(cause);
        return failure;
    }

    /**
     * 创建保留引擎首因的稳定并发冲突异常。
     *
     * @param cause Throwable，Flowable 公共命令暴露的原始并发消失异常
     * @return ServiceException，HTTP 409 且 cause 仅供服务端日志追踪
     */
    private ServiceException conflict(Throwable cause)
    {
        ServiceException failure = conflict();
        failure.initCause(cause);
        return failure;
    }

    /**
     * 创建稳定的引擎状态和服务端快照不一致异常。
     *
     * @return ServiceException，HTTP 500 且当前写事务必须回滚
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例状态不一致", HttpStatus.ERROR);
    }

    /**
     * 规范化后的动态多实例调整命令。
     *
     * @param taskId String，当前授权任务主键
     * @param action WorkflowMultiInstanceAdjustmentAction，ADD 或 REMOVE
     * @param expectedRevision int，客户端预期 revision
     * @param comment String，受控业务意见
     * @param userIds List&lt;Long&gt;，ADD 目标用户集合
     * @param targetTaskId String，REMOVE 目标 sibling 任务主键
     */
    private record AdjustmentInput(String taskId,
            WorkflowMultiInstanceAdjustmentAction action, int expectedRevision,
            String comment, List<Long> userIds, String targetTaskId)
    {
    }

    /**
     * 完成任务占用的动态多实例 revision 区间；普通任务返回空计划。
     *
     * @param activityId String，动态多实例活动 ID；普通任务为空
     * @param beforeRevision Integer，完成前 revision；普通任务为空
     * @param afterRevision Integer，完成占用后的 revision；普通任务为空
     */
    record CompletionRevision(String activityId, Integer beforeRevision,
            Integer afterRevision)
    {
        /**
         * 校验普通任务空计划或动态任务完整 revision 区间，禁止部分字段进入审计链。
         *
         * @param activityId String，动态多实例活动 ID；普通任务为空
         * @param beforeRevision Integer，完成前 revision；普通任务为空
         * @param afterRevision Integer，完成占用后的 revision；普通任务为空
         * @return 无返回值，字段不完整或 revision 非严格递增一时拒绝构造
         */
        CompletionRevision
        {
            boolean empty = activityId == null && beforeRevision == null
                    && afterRevision == null;
            boolean complete = StringUtils.hasText(activityId)
                    && beforeRevision != null && beforeRevision >= 0
                    && beforeRevision < Integer.MAX_VALUE
                    && afterRevision != null && afterRevision == beforeRevision + 1;
            if (!empty && !complete)
            {
                throw new IllegalArgumentException("动态多实例完成版本区间不合法");
            }
        }

        /**
         * 创建普通任务的空 revision 计划。
         *
         * @return CompletionRevision，三个字段均为空且不会写入多实例审计扩展
         */
        static CompletionRevision none()
        {
            return new CompletionRevision(null, null, null);
        }

        /**
         * 判断当前完成动作是否已占用动态多实例 revision。
         *
         * @return boolean，三个动态字段完整存在时返回 true
         */
        boolean applied()
        {
            return activityId != null && beforeRevision != null && afterRevision != null;
        }
    }

    /**
     * 单次读写命令冻结的完整动态多实例上下文。
     *
     * @param currentTask Task，当前操作人的授权任务
     * @param processInstanceId String，流程实例主键
     * @param activityId String，BPMN 活动 ID
     * @param rootExecution Execution，多实例根 execution
     * @param activeTasks List&lt;Task&gt;，同根活动任务
     * @param memberIds List&lt;String&gt;，服务端正式成员快照
     * @param revision int，服务端调整版本
     * @param mode WorkflowMultiInstanceMode，ALL 或 ANY
     * @param counts EngineCounts，Flowable 根计数快照
     */
    private record MultiInstanceContext(Task currentTask, String processInstanceId,
            String activityId, Execution rootExecution, List<Task> activeTasks,
            List<String> memberIds, int revision, WorkflowMultiInstanceMode mode,
            EngineCounts counts)
    {
    }

    /**
     * Flowable 多实例根计数快照。
     *
     * @param instances int，总实例数
     * @param active int，活动实例数
     * @param completed int，已完成实例数
     */
    private record EngineCounts(int instances, int active, int completed)
    {
    }
}
