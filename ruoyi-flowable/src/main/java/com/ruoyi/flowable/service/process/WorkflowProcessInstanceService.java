package com.ruoyi.flowable.service.process;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.variable.api.history.HistoricVariableInstance;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WorkflowInstanceState;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceStateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowHistoryDeletionView;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceStateView;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceTerminateView;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.mapper.WfControlledLoopExecutionMapper;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.flowable.mapper.WfMultiInstanceRoundMapper;
import com.ruoyi.flowable.service.task.MultiInstanceRoundTerminationPlan;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceRoundTerminationService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/**
 * 流程实例状态管理、受控终止和已结束历史删除服务。
 */
@Service
public class WorkflowProcessInstanceService
{
    /** Flowable 实例主键数据库列的最大长度。 */
    private static final int MAX_INSTANCE_ID_LENGTH = 64;

    /** 单次历史删除允许的客户端实例数量。 */
    private static final int MAX_DELETE_BATCH_SIZE = 100;

    /** 单次删除连同调用活动子流程允许展开的历史实例总量。 */
    private static final int MAX_DELETE_GRAPH_SIZE = 1_000;

    /** 单次业务终止允许冻结的根实例及 CallActivity 子实例总量。 */
    private static final int MAX_TERMINATE_PROCESS_TREE_SIZE = 2_000;

    /** 终止原因在 DTO 与领域层共同执行的最大长度。 */
    private static final int MAX_TERMINATE_REASON_LENGTH = 500;

    /** 流程管理员实例状态权限。 */
    private static final String STATE_PERMISSION = "workflow:process:state";

    /** 流程管理员历史删除权限。 */
    private static final String REMOVE_PERMISSION = "workflow:process:remove";

    /** 流程管理员终止权限。 */
    private static final String TERMINATE_PERMISSION = "workflow:process:terminate";

    /** 发起人取消本人实例权限。 */
    private static final String CANCEL_PERMISSION = "workflow:process:cancel";

    /** Flowable 兼容旧系统的终止意见类型。 */
    private static final String TERMINATE_COMMENT_TYPE = "6";

    /** 服务端维护的流程状态变量名。 */
    private static final String PROCESS_STATUS_VARIABLE = "processStatus";

    /** 发起人取消流程后的持久化状态。 */
    private static final String CANCELED_STATUS = "canceled";

    /** 办理人驳回流程后的持久化状态。 */
    private static final String REJECTED_STATUS = "rejected";

    /** 流程管理员终止流程后的持久化状态。 */
    private static final String TERMINATED_STATUS = "terminated";

    /** 服务端结构化审计 JSON 序列化器。 */
    private static final ObjectMapper AUDIT_MAPPER = JsonMapper.shared();

    private final WorkflowEngineOperations engineOperations;

    private final HistoryService historyService;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    private final WfAttachmentMapper attachmentMapper;

    private final WfCopyMapper copyMapper;

    private final WfControlledLoopExecutionMapper controlledLoopExecutionMapper;

    /** 多实例轮次快照必须与 Flowable 历史在同一事务中精确删除。 */
    private final WfMultiInstanceRoundMapper multiInstanceRoundMapper;

    /** 受控多实例轮次终止前引擎对账与删除后异常关闭服务。 */
    private final WorkflowMultiInstanceRoundTerminationService
            multiInstanceRoundTerminationService;

    private final PermissionService permissionService;

    /** SLA 时钟冻结和 Flowable timer job 重排服务。 */
    private final WorkflowTaskSlaRuntimeService taskSlaRuntimeService;

    /** 普通审批结果通知服务，业务终态与站内信、外部 Outbox 必须同事务提交。 */
    private final WorkflowNotificationService notificationService;

    /**
     * 创建流程实例写操作服务。
     *
     * @param engineOperations WorkflowEngineOperations，当前身份、事务和引擎异常边界
     * @param historyService HistoryService，历史实例、变量和删除公共 API
     * @param runtimeService RuntimeService，运行实例状态和终止公共 API
     * @param taskService TaskService，流程级结构化审计 comment 公共 API
     * @param attachmentMapper WfAttachmentMapper，已绑定审计附件引用检查 Mapper
     * @param copyMapper WfCopyMapper，正式抄送记录引用检查和逻辑删除 Mapper
     * @param controlledLoopExecutionMapper WfControlledLoopExecutionMapper，受控循环逐轮审计 Mapper
     * @param multiInstanceRoundMapper WfMultiInstanceRoundMapper，多实例轮次快照审计 Mapper
     * @param multiInstanceRoundTerminationService WorkflowMultiInstanceRoundTerminationService，轮次终止严格预检与关闭服务
     * @param permissionService PermissionService，Token 权限与当前正式主数据的统一复核服务
     * @param taskSlaRuntimeService WorkflowTaskSlaRuntimeService，SLA 暂停恢复服务
     * @param notificationService WorkflowNotificationService，显式取消或终止通知服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessInstanceService(WorkflowEngineOperations engineOperations,
            HistoryService historyService, RuntimeService runtimeService,
            TaskService taskService, WfAttachmentMapper attachmentMapper,
            WfCopyMapper copyMapper,
            WfControlledLoopExecutionMapper controlledLoopExecutionMapper,
            WfMultiInstanceRoundMapper multiInstanceRoundMapper,
            WorkflowMultiInstanceRoundTerminationService
                    multiInstanceRoundTerminationService,
            PermissionService permissionService,
            WorkflowTaskSlaRuntimeService taskSlaRuntimeService,
            WorkflowNotificationService notificationService)
    {
        this.engineOperations = engineOperations;
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.attachmentMapper = attachmentMapper;
        this.copyMapper = copyMapper;
        this.controlledLoopExecutionMapper = controlledLoopExecutionMapper;
        this.multiInstanceRoundMapper = multiInstanceRoundMapper;
        this.multiInstanceRoundTerminationService =
                multiInstanceRoundTerminationService;
        this.permissionService = permissionService;
        this.taskSlaRuntimeService = taskSlaRuntimeService;
        this.notificationService = notificationService;
    }

    /**
     * 由具备受控删除权限的流程管理员批量删除已结束实例及其子流程历史。
     *
     * @param rawInstanceIds Collection&lt;String&gt;，路径绑定的一个或多个实例主键
     * @return WorkflowHistoryDeletionView，真实历史实例和抄送记录删除数量
     */
    public WorkflowHistoryDeletionView deleteCompletedHistory(Collection<String> rawInstanceIds)
    {
        List<String> requestedIds = normalizeInstanceIds(rawInstanceIds);
        return engineOperations.writeAsCurrentUser(actor ->
        {
            requirePermission(REMOVE_PERMISSION);

            // 先展开并验证整批历史关系，任何运行实例或异常关系都会在写入前终止请求。
            DeletionGraph graph = inspectDeletionGraph(requestedIds);
            Set<String> allIds = graph.instances().keySet();

            // BOUND 附件属于必须保留的审计证据；未批准保留期策略前禁止制造孤儿附件。
            long boundAttachmentCount = attachmentMapper
                    .countBoundByProcessInstanceIds(allIds);
            if (boundAttachmentCount > 0)
            {
                throw conflict("流程历史存在已绑定附件，不能删除");
            }

            long activeCopyCount = copyMapper.countActiveByInstanceIds(allIds);
            if (activeCopyCount > Integer.MAX_VALUE)
            {
                throw new ServiceException("流程抄送引用数量超过可删除范围", HttpStatus.CONFLICT);
            }

            long controlledLoopCount = controlledLoopExecutionMapper
                    .countByProcessInstanceIds(allIds);
            if (controlledLoopCount > Integer.MAX_VALUE)
            {
                throw new ServiceException("循环审计记录数量超过可删除范围", HttpStatus.CONFLICT);
            }

            // 轮次快照属于历史审计和整组重建依据，必须在删除引擎历史前冻结精确数量。
            long multiInstanceRoundCount = multiInstanceRoundMapper
                    .countByProcessInstanceIds(allIds);
            if (multiInstanceRoundCount > Integer.MAX_VALUE)
            {
                throw new ServiceException("多实例轮次记录数量超过可删除范围", HttpStatus.CONFLICT);
            }

            int deletedCopyCount = copyMapper.logicalDeleteByInstanceIds(allIds, actor.userId());
            if (deletedCopyCount != (int) activeCopyCount)
            {
                throw conflict("流程抄送引用已发生变化，请刷新后重试");
            }

            int deletedControlledLoopCount = controlledLoopExecutionMapper
                    .deleteByProcessInstanceIds(allIds);
            if (deletedControlledLoopCount != (int) controlledLoopCount)
            {
                throw conflict("循环审计记录已发生变化，请刷新后重试");
            }

            int deletedMultiInstanceRoundCount = multiInstanceRoundMapper
                    .deleteByProcessInstanceIds(allIds);
            if (deletedMultiInstanceRoundCount != (int) multiInstanceRoundCount)
            {
                throw conflict("多实例轮次记录已发生变化，请刷新后重试");
            }

            // 只删除请求集合中的顶层根；Flowable 会递归删除其调用活动子流程历史。
            for (String rootId : graph.roots())
            {
                try
                {
                    historyService.deleteHistoricProcessInstance(rootId);
                }
                catch (FlowableObjectNotFoundException exception)
                {
                    throw conflict("流程历史已发生变化，请刷新后重试");
                }
            }

            long remainingHistory = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceIds(allIds)
                    .count();
            long remainingCopies = copyMapper.countActiveByInstanceIds(allIds);
            long remainingControlledLoops = controlledLoopExecutionMapper
                    .countByProcessInstanceIds(allIds);
            long remainingMultiInstanceRounds = multiInstanceRoundMapper
                    .countByProcessInstanceIds(allIds);
            if (remainingHistory != 0 || remainingCopies != 0
                    || remainingControlledLoops != 0 || remainingMultiInstanceRounds != 0)
            {
                throw conflict("流程历史删除结果不完整，请刷新后重试");
            }
            return new WorkflowHistoryDeletionView(requestedIds.size(), allIds.size(),
                    deletedCopyCount);
        });
    }

    /**
     * 由流程管理员将运行实例所在的完整 CallActivity 执行树切换到激活或挂起状态。
     *
     * @param request WorkflowInstanceStateRequest，根或子实例主键和枚举目标状态
     * @return WorkflowInstanceStateView，事务内复核后的状态及是否真实变更
     */
    public WorkflowInstanceStateView updateState(WorkflowInstanceStateRequest request)
    {
        if (request == null || request.state() == null)
        {
            throw invalidArgument("流程实例状态参数不能为空");
        }
        String instanceId = normalizeInstanceId(request.instanceId());
        WorkflowInstanceState targetState = request.state();

        return engineOperations.writeAsCurrentUser(actor ->
        {
            requirePermission(STATE_PERMISSION);
            HistoricProcessInstance historic = requireHistoricInstance(instanceId);
            requireRunningHistory(historic);
            ProcessInstance requestedInstance = requireRuntimeInstance(instanceId);
            ProcessInstance rootInstance = requireRootProcessInstance(requestedInstance);
            List<String> processTreeInstanceIds = requireProcessTreeInstanceIds(rootInstance);
            if (rootInstance.isSuspended() == targetState.suspended())
            {
                return new WorkflowInstanceStateView(instanceId, targetState, false);
            }

            try
            {
                // 激活时先恢复根实例，挂起时最后挂起根实例，避免父 execution 拒绝对子实例执行状态命令。
                List<String> stateChangeOrder = new ArrayList<>(processTreeInstanceIds);
                stateChangeOrder.remove(rootInstance.getId());
                if (targetState.suspended())
                {
                    stateChangeOrder.add(rootInstance.getId());
                    for (String processTreeInstanceId : stateChangeOrder)
                    {
                        runtimeService.suspendProcessInstanceById(processTreeInstanceId);
                        taskSlaRuntimeService.pauseInstance(processTreeInstanceId, actor.userId());
                    }
                }
                else
                {
                    stateChangeOrder.add(0, rootInstance.getId());
                    for (String processTreeInstanceId : stateChangeOrder)
                    {
                        runtimeService.activateProcessInstanceById(processTreeInstanceId);
                        // 激活与 timer job 平移处于同一事务，异步执行器不能抢占未平移的过期作业。
                        taskSlaRuntimeService.resumeInstance(processTreeInstanceId, actor.userId());
                    }
                }
            }
            catch (FlowableObjectNotFoundException exception)
            {
                throw conflict("流程实例状态已发生变化，请刷新后重试");
            }

            List<ProcessInstance> updatedInstances = runtimeService.createProcessInstanceQuery()
                    .processInstanceIds(Set.copyOf(processTreeInstanceIds)).list();
            if (updatedInstances == null
                    || updatedInstances.size() != processTreeInstanceIds.size()
                    || updatedInstances.stream().anyMatch(updated -> updated == null
                            || !StringUtils.hasText(updated.getId())
                            || !processTreeInstanceIds.contains(updated.getId().trim())
                            || updated.isSuspended() != targetState.suspended()))
            {
                throw conflict("流程实例状态已发生变化，请刷新后重试");
            }
            return new WorkflowInstanceStateView(instanceId, targetState, true);
        });
    }

    /**
     * 按权限和根业务对象身份分流发起人取消或流程管理员终止，并保留完整历史审计。
     *
     * @param request WorkflowInstanceTerminateRequest，运行实例主键和业务原因
     * @return WorkflowInstanceTerminateView，最终 canceled/terminated 状态与操作人
     */
    public WorkflowInstanceTerminateView terminate(WorkflowInstanceTerminateRequest request)
    {
        if (request == null)
        {
            throw invalidArgument("流程终止参数不能为空");
        }
        String requestedInstanceId = normalizeInstanceId(request.instanceId());
        String reason = normalizeReason(request.reason());

        return engineOperations.writeAsCurrentUser(actor ->
        {
            HistoricProcessInstance requestedHistoric = requireHistoricInstance(
                    requestedInstanceId);
            requireRunningHistory(requestedHistoric);
            ProcessInstance requestedInstance = requireRuntimeInstance(requestedInstanceId);
            ProcessInstance rootInstance = requireRootProcessInstance(requestedInstance);
            String rootInstanceId = rootInstance.getId().trim();
            HistoricProcessInstance rootHistoric = rootInstanceId.equals(requestedInstanceId)
                    ? requestedHistoric : requireHistoricInstance(rootInstanceId);
            requireRunningHistory(rootHistoric);

            // 客户端子实例 ID 只负责定位业务树；授权、状态和最终持久化均以根实例为准。
            TerminationDecision decision = authorizeTermination(actor, rootHistoric);
            RootTerminationContext termination = terminateRootProcessInstance(requestedInstance,
                    decision.processStatus(),
                    context ->
                    {
                        // 挂起根实例已由统一终止入口临时激活，此处只负责流程级审计 comment。
                        String auditMessage = buildTerminationAudit(decision, actor.userId(),
                                reason, context.wasSuspended(), context.requestedInstanceId(),
                                context.rootInstance().getId(),
                                context.processTreeInstanceIds().size());
                        Comment auditComment = taskService.addComment(null,
                                context.rootInstance().getId(), TERMINATE_COMMENT_TYPE,
                                auditMessage);
                        requireAuditComment(auditComment);
                        return new RootTerminationInstruction(
                                decision.processStatus() + ": " + reason, auditComment);
                    });

            return new WorkflowInstanceTerminateView(termination.rootInstance().getId(),
                    decision.processStatus(), actor.userId(), termination.wasSuspended());
        });
    }

    /**
     * 在当前 Flowable 写事务内统一终止根业务流程实例。
     *
     * @param requestedInstance ProcessInstance，客户端直接定位的根或 CallActivity 子实例
     * @param processStatus String，必须写入的 canceled、rejected 或 terminated 业务状态
     * @param beforeDelete Function&lt;RootTerminationContext,RootTerminationInstruction&gt;，
     *        在统一状态写入前执行的任务特有或流程审计写入动作
     * @return RootTerminationContext，已完成根终止和统一后置检查的冻结上下文
     */
    public RootTerminationContext terminateRootProcessInstance(ProcessInstance requestedInstance,
            String processStatus,
            Function<RootTerminationContext, RootTerminationInstruction> beforeDelete)
    {
        if (requestedInstance == null || !StringUtils.hasText(requestedInstance.getId())
                || !StringUtils.hasText(processStatus)
                || beforeDelete == null)
        {
            throw dataError("流程终止写入参数不完整");
        }
        String resultEventType = processResultEventType(processStatus);

        ProcessInstance rootInstance = requireRootProcessInstance(requestedInstance);
        List<String> processTreeInstanceIds = requireProcessTreeInstanceIds(rootInstance);
        RootTerminationContext context = new RootTerminationContext(
                requestedInstance.getId().trim(), rootInstance, processTreeInstanceIds,
                rootInstance.isSuspended());
        try
        {
            // Flowable 禁止向挂起执行树写变量和 comment；当前事务内按根优先顺序激活完整执行树，
            // 使根流程及 CallActivity 子流程的活动任务都能写入终止审计后再统一删除。
            if (context.wasSuspended())
            {
                List<String> activationOrder = new ArrayList<>(
                        context.processTreeInstanceIds());
                activationOrder.remove(rootInstance.getId());
                activationOrder.add(0, rootInstance.getId());
                for (String processTreeInstanceId : activationOrder)
                {
                    runtimeService.activateProcessInstanceById(processTreeInstanceId);
                }
            }

            RootTerminationInstruction instruction = beforeDelete.apply(context);
            if (instruction == null || !StringUtils.hasText(instruction.deleteReason()))
            {
                throw dataError("流程终止审计写入参数不完整");
            }

            // processStatus 与 businessStatus 必须双写，查询、详情和历史归一化分别依赖两者。
            runtimeService.setVariable(rootInstance.getId(), PROCESS_STATUS_VARIABLE,
                    processStatus);
            runtimeService.updateBusinessStatus(rootInstance.getId(), processStatus);
            // 上述引擎写入已经取得 Flowable 写锁；此时执行树仍完整，使用普通 SELECT 冻结
            // 全树开放轮次并逐一对账活动受控根，避免缺行或合法格式篡改被级联删除掩盖。
            MultiInstanceRoundTerminationPlan roundPrecheck =
                    multiInstanceRoundTerminationService
                    .precheckTermination(context.processTreeInstanceIds());
            notificationService.onProcessResult(resultEventType,
                    rootInstance.getProcessDefinitionId(), rootInstance.getId());
            // 只删除根实例，让 Flowable 级联结束 CallActivity 子流程并保留历史审计。
            runtimeService.deleteProcessInstance(rootInstance.getId(),
                    instruction.deleteReason());
            // 删除成功后才取得业务行锁，并要求锁定集合与删除前令牌完全相同；任一失败都会
            // 在同一 Spring 事务中回滚引擎状态写入、根删除及轮次更新。
            multiInstanceRoundTerminationService.terminatePrechecked(roundPrecheck);
            verifyTermination(context, processStatus, instruction.auditComment());
        }
        catch (FlowableObjectNotFoundException exception)
        {
            ServiceException failure = conflict("流程实例状态已发生变化，请刷新后重试");
            failure.initCause(exception);
            throw failure;
        }
        return context;
    }

    /**
     * 仅解析终止动作的根实例，供任务服务在写入任务 comment 前完成根级授权。
     *
     * @param requestedInstance ProcessInstance，客户端或任务所属的根/子流程实例
     * @return ProcessInstance，经过 CallActivity 父子关系校验的根流程实例
     */
    public ProcessInstance resolveRootProcessInstanceForTermination(
            ProcessInstance requestedInstance)
    {
        return requireRootProcessInstance(requestedInstance);
    }

    /**
     * 从客户端直接定位的运行实例解析完整业务树的根实例，并校验 CallActivity 关系。
     *
     * @param directInstance ProcessInstance，客户端请求直接定位的根或子流程实例
     * @return ProcessInstance，必须执行状态写入和级联删除的根业务实例
     */
    private ProcessInstance requireRootProcessInstance(ProcessInstance directInstance)
    {
        if (directInstance == null || !StringUtils.hasText(directInstance.getId()))
        {
            throw dataError("流程实例执行树关系异常");
        }
        String directInstanceId = directInstance.getId().trim();
        String declaredRootId = directInstance.getRootProcessInstanceId();
        String rootInstanceId = StringUtils.hasText(declaredRootId)
                ? declaredRootId.trim() : directInstanceId;
        boolean directIsRoot = directInstanceId.equals(rootInstanceId);
        if (!directIsRoot && !StringUtils.hasText(directInstance.getSuperExecutionId()))
        {
            throw dataError("流程实例执行树关系异常");
        }

        ProcessInstance rootInstance = directIsRoot
                ? directInstance : requireRuntimeInstance(rootInstanceId);
        String rootDeclaredId = rootInstance.getRootProcessInstanceId();
        if (!StringUtils.hasText(rootInstance.getId())
                || !rootInstanceId.equals(rootInstance.getId().trim())
                || StringUtils.hasText(rootInstance.getSuperExecutionId())
                || (StringUtils.hasText(rootDeclaredId)
                        && !rootInstanceId.equals(rootDeclaredId.trim())))
        {
            throw dataError("流程实例执行树关系异常");
        }
        return rootInstance;
    }

    /**
     * 冻结根实例及其全部活动 CallActivity 子实例，并复核父子关系和挂起状态一致。
     *
     * @param rootInstance ProcessInstance，已经校验的根业务流程实例
     * @return List&lt;String&gt;，排序后的根实例及全部活动子实例主键
     */
    private List<String> requireProcessTreeInstanceIds(ProcessInstance rootInstance)
    {
        String rootInstanceId = rootInstance.getId().trim();
        List<Execution> executions = runtimeService.createExecutionQuery()
                .rootProcessInstanceId(rootInstanceId)
                .list();
        if (executions == null)
        {
            throw dataError("流程实例执行树查询异常");
        }

        // execution 覆盖并行分支和多层 CallActivity，这里只提取正式流程实例主键并去重。
        Set<String> processInstanceIds = new LinkedHashSet<>();
        processInstanceIds.add(rootInstanceId);
        for (Execution execution : executions)
        {
            if (execution == null || !StringUtils.hasText(execution.getProcessInstanceId())
                    || (StringUtils.hasText(execution.getRootProcessInstanceId())
                            && !rootInstanceId.equals(
                                    execution.getRootProcessInstanceId().trim())))
            {
                throw dataError("流程实例执行树关系异常");
            }
            processInstanceIds.add(execution.getProcessInstanceId().trim());
        }
        if (processInstanceIds.size() > MAX_TERMINATE_PROCESS_TREE_SIZE)
        {
            throw conflict("流程实例执行树规模超过单次终止上限");
        }

        List<ProcessInstance> runtimeInstances = runtimeService
                .createProcessInstanceQuery()
                .processInstanceIds(processInstanceIds)
                .list();
        if (runtimeInstances == null || runtimeInstances.size() != processInstanceIds.size())
        {
            throw conflict("流程实例执行树已发生变化，请刷新后重试");
        }

        Map<String, ProcessInstance> instancesById = new LinkedHashMap<>();
        for (ProcessInstance runtimeInstance : runtimeInstances)
        {
            if (runtimeInstance == null || !StringUtils.hasText(runtimeInstance.getId())
                    || runtimeInstance.isSuspended() != rootInstance.isSuspended())
            {
                throw dataError("流程实例执行树状态异常");
            }
            String instanceId = runtimeInstance.getId().trim();
            String declaredRootId = runtimeInstance.getRootProcessInstanceId();
            boolean isRoot = rootInstanceId.equals(instanceId);
            if (isRoot)
            {
                if (StringUtils.hasText(runtimeInstance.getSuperExecutionId())
                        || (StringUtils.hasText(declaredRootId)
                                && !rootInstanceId.equals(declaredRootId.trim())))
                {
                    throw dataError("流程实例根执行关系异常");
                }
            }
            else if (!StringUtils.hasText(declaredRootId)
                    || !rootInstanceId.equals(declaredRootId.trim())
                    || !StringUtils.hasText(runtimeInstance.getSuperExecutionId()))
            {
                throw dataError("流程实例子执行关系异常");
            }
            if (!processInstanceIds.contains(instanceId)
                    || instancesById.put(instanceId, runtimeInstance) != null)
            {
                throw dataError("流程实例执行树存在重复或未知实例");
            }
        }
        if (!instancesById.keySet().equals(processInstanceIds))
        {
            throw dataError("流程实例执行树对账异常");
        }
        return instancesById.keySet().stream().sorted().toList();
    }

    /**
     * 全量预检请求历史及其调用活动子流程，并计算不会重复删除的请求根集合。
     *
     * @param requestedIds List&lt;String&gt;，已规范化且去重的客户端实例主键
     * @return DeletionGraph，全部历史实例和请求顶层删除根
     */
    private DeletionGraph inspectDeletionGraph(List<String> requestedIds)
    {
        LinkedHashMap<String, HistoricProcessInstance> instances = new LinkedHashMap<>();
        Deque<String> pendingParents = new ArrayDeque<>();
        for (String requestedId : requestedIds)
        {
            HistoricProcessInstance historic = requireHistoricInstance(requestedId);
            requireFinishedHistory(historic);
            instances.putIfAbsent(requestedId, historic);
            pendingParents.addLast(requestedId);
        }

        while (!pendingParents.isEmpty())
        {
            String parentId = pendingParents.removeFirst();
            int remainingCapacity = MAX_DELETE_GRAPH_SIZE - instances.size();
            List<HistoricProcessInstance> children = historyService
                    .createHistoricProcessInstanceQuery()
                    .superProcessInstanceId(parentId)
                    .listPage(0, remainingCapacity + 1);
            if (children.size() > remainingCapacity)
            {
                throw invalidArgument("流程历史及子流程数量不能超过" + MAX_DELETE_GRAPH_SIZE);
            }
            for (HistoricProcessInstance child : children)
            {
                if (child == null || !StringUtils.hasText(child.getId()))
                {
                    throw new ServiceException("流程子流程历史关系异常", HttpStatus.ERROR);
                }
                requireFinishedHistory(child);
                if (instances.putIfAbsent(child.getId(), child) == null)
                {
                    pendingParents.addLast(child.getId());
                }
            }
        }

        LinkedHashSet<String> roots = new LinkedHashSet<>(requestedIds);
        for (String requestedId : requestedIds)
        {
            String parentId = instances.get(requestedId).getSuperProcessInstanceId();
            LinkedHashSet<String> visited = new LinkedHashSet<>();
            while (StringUtils.hasText(parentId) && visited.add(parentId))
            {
                if (roots.contains(parentId))
                {
                    roots.remove(requestedId);
                    break;
                }
                HistoricProcessInstance parent = instances.get(parentId);
                parentId = parent == null ? null : parent.getSuperProcessInstanceId();
            }
        }
        return new DeletionGraph(Collections.unmodifiableMap(instances), Set.copyOf(roots));
    }

    /**
     * 按管理员终止权限和真实发起人取消权限确定唯一业务结果。
     *
     * @param actor WorkflowCurrentIdentity，事务内重新解析的当前有效用户
     * @param historic HistoricProcessInstance，仍在运行的历史实例
     * @return TerminationDecision，固定动作编码和最终流程状态
     */
    private TerminationDecision authorizeTermination(WorkflowCurrentIdentity actor,
            HistoricProcessInstance historic)
    {
        if (hasPermission(TERMINATE_PERMISSION))
        {
            return new TerminationDecision("TERMINATE", TERMINATED_STATUS);
        }
        if (actor.userId().equals(historic.getStartUserId())
                && hasPermission(CANCEL_PERMISSION))
        {
            return new TerminationDecision("CANCEL", CANCELED_STATUS);
        }
        throw new ServiceException("无权结束当前流程实例", HttpStatus.FORBIDDEN);
    }

    /**
     * 构造完全由服务端控制字段结构的流程终止审计 JSON。
     *
     * @param decision TerminationDecision，已授权的动作和结果状态
     * @param actorUserId String，事务内当前操作人用户主键
     * @param reason String，已规范化并限制长度的业务原因
     * @param wasSuspended boolean，动作前根实例挂起状态
     * @param requestedInstanceId String，客户端直接提交的根或子实例主键
     * @param rootInstanceId String，服务端解析并执行终止的根业务实例主键
     * @param processTreeInstanceCount int，终止前冻结的根及子实例总数
     * @return String，可持久化到 Flowable comment 的结构化 JSON
     */
    private String buildTerminationAudit(TerminationDecision decision, String actorUserId,
            String reason, boolean wasSuspended, String requestedInstanceId,
            String rootInstanceId, int processTreeInstanceCount)
    {
        ObjectNode audit = AUDIT_MAPPER.createObjectNode();
        audit.put("action", decision.action());
        audit.put("actorUserId", actorUserId);
        audit.put("processStatus", decision.processStatus());
        audit.put("reason", reason);
        audit.put("wasSuspended", wasSuspended);
        audit.put("requestedInstanceId", requestedInstanceId);
        audit.put("rootInstanceId", rootInstanceId);
        audit.put("processTreeInstanceCount", processTreeInstanceCount);
        return audit.toString();
    }

    /**
     * 统一复核根终止后的运行树、双状态、历史变量和可选流程审计 comment。
     *
     * @param context RootTerminationContext，终止前冻结的根和完整执行树
     * @param processStatus String，本次动作预期持久化的业务终态
     * @param auditComment Comment，可选的流程级类型 6 审计 comment
     * @return 无返回值，任一结果不一致时抛出异常并回滚当前事务
     */
    private void verifyTermination(RootTerminationContext context,
            String processStatus, Comment auditComment)
    {
        String rootInstanceId = context.rootInstance().getId();
        List<String> processTreeInstanceIds = context.processTreeInstanceIds();
        long remainingInstanceCount = runtimeService.createProcessInstanceQuery()
                .processInstanceIds(new LinkedHashSet<>(processTreeInstanceIds))
                .count();
        long remainingExecutionCount = runtimeService.createExecutionQuery()
                .rootProcessInstanceId(rootInstanceId)
                .count();
        long remainingTaskCount = taskService.createTaskQuery()
                .processInstanceIdIn(processTreeInstanceIds)
                .count();
        long remainingOpenRoundCount = multiInstanceRoundMapper
                .countOpenByProcessInstanceIds(
                        new LinkedHashSet<>(processTreeInstanceIds));
        HistoricProcessInstance finished = findHistoricInstance(rootInstanceId);
        if (remainingInstanceCount != 0L || remainingExecutionCount != 0L
                || remainingTaskCount != 0L || remainingOpenRoundCount != 0L
                || finished == null
                || finished.getEndTime() == null
                || !processStatus.equals(finished.getBusinessStatus()))
        {
            throw conflict("流程实例终止结果不完整，请刷新后重试");
        }
        if (!StringUtils.hasText(finished.getDeleteReason()))
        {
            throw new ServiceException("流程实例终止历史记录异常", HttpStatus.ERROR);
        }

        HistoricVariableInstance statusVariable = historyService
                .createHistoricVariableInstanceQuery()
                .processInstanceId(rootInstanceId)
                .variableName(PROCESS_STATUS_VARIABLE)
                .singleResult();
        if (statusVariable == null || !processStatus.equals(statusVariable.getValue()))
        {
            throw new ServiceException("流程实例终止状态记录异常", HttpStatus.ERROR);
        }

        if (auditComment != null)
        {
            List<Comment> comments = taskService.getProcessInstanceComments(rootInstanceId,
                    TERMINATE_COMMENT_TYPE);
            boolean auditPersisted = comments != null && comments.stream()
                    .anyMatch(comment -> comment != null
                            && auditComment.getId().equals(comment.getId())
                            && auditComment.getFullMessage().equals(comment.getFullMessage()));
            if (!auditPersisted)
            {
                throw new ServiceException("流程实例终止审计记录异常", HttpStatus.ERROR);
            }
        }
    }

    /**
     * 根据稳定业务状态派生流程结果通知事件，避免三种终止动作各自维护映射。
     *
     * @param processStatus String，canceled、rejected 或 terminated 业务状态
     * @return String，对应 WorkflowNotificationService 的流程结果事件
     */
    private String processResultEventType(String processStatus)
    {
        return switch (processStatus)
        {
            case CANCELED_STATUS -> "PROCESS_CANCELED";
            case REJECTED_STATUS -> "PROCESS_REJECTED";
            case TERMINATED_STATUS -> "PROCESS_TERMINATED";
            default -> throw dataError("流程终止状态不受支持");
        };
    }

    /**
     * 验证 Flowable 已返回可追踪的审计 comment 主键。
     *
     * @param comment Comment，TaskService 新增 comment 的返回值
     * @return 无返回值，返回对象异常时抛出服务端错误并回滚
     */
    private void requireAuditComment(Comment comment)
    {
        if (comment == null || !StringUtils.hasText(comment.getId()))
        {
            throw new ServiceException("流程实例终止审计写入异常", HttpStatus.ERROR);
        }
    }

    /**
     * 查询并要求历史实例存在。
     *
     * @param instanceId String，已规范化的实例主键
     * @return HistoricProcessInstance，存在的历史实例
     */
    private HistoricProcessInstance requireHistoricInstance(String instanceId)
    {
        HistoricProcessInstance historic = findHistoricInstance(instanceId);
        if (historic == null)
        {
            throw new ServiceException("流程实例不存在或已被删除", HttpStatus.NOT_FOUND);
        }
        return historic;
    }

    /**
     * 查询单个历史流程实例。
     *
     * @param instanceId String，已规范化的实例主键
     * @return HistoricProcessInstance，匹配实例；不存在时返回 null
     */
    private HistoricProcessInstance findHistoricInstance(String instanceId)
    {
        return historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instanceId)
                .singleResult();
    }

    /**
     * 查询并要求 Flowable 运行实例仍然存在。
     *
     * @param instanceId String，已通过历史状态预检的实例主键
     * @return ProcessInstance，当前运行实例
     */
    private ProcessInstance requireRuntimeInstance(String instanceId)
    {
        ProcessInstance processInstance = findRuntimeInstance(instanceId);
        if (processInstance == null)
        {
            throw conflict("流程实例状态已发生变化，请刷新后重试");
        }
        return processInstance;
    }

    /**
     * 查询单个运行流程实例。
     *
     * @param instanceId String，已规范化的实例主键
     * @return ProcessInstance，匹配实例；已结束或不存在时返回 null
     */
    private ProcessInstance findRuntimeInstance(String instanceId)
    {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceId(instanceId)
                .singleResult();
    }

    /**
     * 要求历史实例仍处于运行状态。
     *
     * @param historic HistoricProcessInstance，待执行运行期动作的历史实例
     * @return 无返回值，实例已结束时抛出 409
     */
    private void requireRunningHistory(HistoricProcessInstance historic)
    {
        if (historic.getEndTime() != null)
        {
            throw conflict("流程实例已结束，不能重复执行当前操作");
        }
    }

    /**
     * 要求历史实例已经结束，运行实例禁止进入物理历史删除链路。
     *
     * @param historic HistoricProcessInstance，待删除的历史实例或子流程
     * @return 无返回值，仍在运行时抛出 409
     */
    private void requireFinishedHistory(HistoricProcessInstance historic)
    {
        if (historic.getEndTime() == null)
        {
            throw conflict("运行中的流程实例不能删除，请先取消或终止");
        }
    }

    /**
     * 对路径批量实例主键执行拆分、去重、数量和长度门禁。
     *
     * @param rawInstanceIds Collection&lt;String&gt;，可能包含逗号分隔值的路径绑定结果
     * @return List&lt;String&gt;，保持首次出现顺序的不可变实例主键集合
     */
    private List<String> normalizeInstanceIds(Collection<String> rawInstanceIds)
    {
        if (rawInstanceIds == null || rawInstanceIds.isEmpty())
        {
            throw invalidArgument("流程实例主键不能为空");
        }
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        for (String rawInstanceId : rawInstanceIds)
        {
            if (rawInstanceId == null)
            {
                throw invalidArgument("流程实例主键不能为空");
            }
            for (String item : rawInstanceId.split(",", -1))
            {
                normalizedIds.add(normalizeInstanceId(item));
                if (normalizedIds.size() > MAX_DELETE_BATCH_SIZE)
                {
                    throw invalidArgument("单次最多删除" + MAX_DELETE_BATCH_SIZE + "个流程实例");
                }
            }
        }
        return List.copyOf(normalizedIds);
    }

    /**
     * 校验并规范化单个 Flowable 实例主键。
     *
     * @param instanceId String，客户端提交的实例主键
     * @return String，去除首尾空白后的非空实例主键
     */
    private String normalizeInstanceId(String instanceId)
    {
        if (!StringUtils.hasText(instanceId))
        {
            throw invalidArgument("流程实例主键不能为空");
        }
        String normalized = instanceId.trim();
        if (normalized.length() > MAX_INSTANCE_ID_LENGTH)
        {
            throw invalidArgument("流程实例主键长度不能超过" + MAX_INSTANCE_ID_LENGTH + "个字符");
        }
        return normalized;
    }

    /**
     * 校验并规范化终止原因，防止非 HTTP 调用绕过 DTO 约束。
     *
     * @param reason String，客户端提交的业务原因
     * @return String，去除首尾空白后的受控原因
     */
    private String normalizeReason(String reason)
    {
        if (!StringUtils.hasText(reason))
        {
            throw invalidArgument("流程终止原因不能为空");
        }
        String normalized = reason.trim();
        if (normalized.length() > MAX_TERMINATE_REASON_LENGTH)
        {
            throw invalidArgument("流程终止原因长度不能超过" + MAX_TERMINATE_REASON_LENGTH + "个字符");
        }
        return normalized;
    }

    /**
     * 在领域写入口再次核验当前登录用户拥有指定按钮权限。
     *
     * @param permission String，服务端固定的受控流程权限
     * @return 无返回值，缺少权限时抛出 403
     */
    private void requirePermission(String permission)
    {
        if (!hasPermission(permission))
        {
            throw new ServiceException("无权执行当前流程实例操作", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 通过统一权限服务同时核验当前 Token 快照和正式主数据授权。
     *
     * @param permission String，服务端固定的工作流权限码
     * @return boolean，Token 与实时角色菜单关系都仍命中时返回 true
     */
    private boolean hasPermission(String permission)
    {
        // Controller 的 hasAnyPermi 可能因另一项权限放行，领域动作必须复核本次实际采用的精确权限。
        return permissionService.hasPermi(permission);
    }

    /**
     * 创建稳定的请求参数异常。
     *
     * @param message String，不包含引擎内部数据的参数提示
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalidArgument(String message)
    {
        return new ServiceException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建稳定的流程状态冲突异常。
     *
     * @param message String，不包含底层 SQL 或引擎实现的冲突提示
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException conflict(String message)
    {
        return new ServiceException(message, HttpStatus.CONFLICT);
    }

    /**
     * 创建稳定的数据一致性异常，触发当前 Spring 事务完整回滚。
     *
     * @param message String，不包含底层表名或执行主键的数据异常提示
     * @return ServiceException，HTTP 500 业务一致性异常
     */
    private ServiceException dataError(String message)
    {
        return new ServiceException(message, HttpStatus.ERROR);
    }

    /**
     * 根终止写入前冻结的业务执行树上下文。
     *
     * @param requestedInstanceId String，客户端直接提交的根或子流程实例主键
     * @param rootInstance ProcessInstance，最终负责状态写入和级联删除的根实例
     * @param processTreeInstanceIds List&lt;String&gt;，根及全部活动 CallActivity 子实例主键
     * @param wasSuspended boolean，根实例在本次动作前是否挂起
     */
    public record RootTerminationContext(String requestedInstanceId,
            ProcessInstance rootInstance, List<String> processTreeInstanceIds,
            boolean wasSuspended)
    {
        /**
         * 复制冻结的执行树主键，避免任务特有回调修改统一终止快照。
         *
         * @param requestedInstanceId String，客户端直接提交的实例主键
         * @param rootInstance ProcessInstance，根流程实例
         * @param processTreeInstanceIds List&lt;String&gt;，冻结的执行树实例主键
         * @param wasSuspended boolean，根实例原始挂起状态
         */
        public RootTerminationContext
        {
            processTreeInstanceIds = List.copyOf(processTreeInstanceIds);
        }
    }

    /**
     * 根终止写入前由调用方补充的删除原因和可选流程级审计 comment。
     *
     * @param deleteReason String，写入 Flowable 历史的根实例删除原因
     * @param auditComment Comment，可选的流程级类型 6 审计 comment
     */
    public record RootTerminationInstruction(String deleteReason, Comment auditComment)
    {
    }

    /**
     * 历史删除预检图。
     *
     * @param instances Map&lt;String, HistoricProcessInstance&gt;，目标及全部子流程历史
     * @param roots Set&lt;String&gt;，请求集合中不属于另一请求目标子流程的删除根
     */
    private record DeletionGraph(Map<String, HistoricProcessInstance> instances,
            Set<String> roots)
    {
    }

    /**
     * 终止授权结果。
     *
     * @param action String，结构化审计动作 CANCEL 或 TERMINATE
     * @param processStatus String，历史流程变量 canceled 或 terminated
     */
    private record TerminationDecision(String action, String processStatus)
    {
    }
}
