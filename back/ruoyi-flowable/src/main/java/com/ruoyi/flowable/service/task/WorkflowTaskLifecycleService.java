package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ExtensionAttribute;
import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.FlowNode;
import org.flowable.bpmn.model.ParallelGateway;
import org.flowable.bpmn.model.SequenceFlow;
import org.flowable.bpmn.model.StartEvent;
import org.flowable.bpmn.model.UserTask;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.domain.WfDeployForm;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfDeployFormMapper;
import com.ruoyi.flowable.service.attachment.WorkflowAttachmentService;
import com.ruoyi.flowable.service.process.WorkflowFormSubmissionSnapshotCodec;
import com.ruoyi.flowable.service.model.WorkflowFormSourceType;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import com.ruoyi.flowable.service.process.WorkflowStartVariableValidator;
import com.ruoyi.flowable.service.process.WorkflowValidatedStartVariables;

/**
 * 任务完成、驳回、退回、流程取消和已办撤回的事务性生命周期服务。
 */
@Service
public class WorkflowTaskLifecycleService
{
    /** 普通完成意见类型，与旧系统 FlowComment.NORMAL 保持兼容。 */
    private static final String COMPLETE_COMMENT_TYPE = "1";

    /** 退回意见类型，与旧系统 FlowComment.REBACK 保持兼容。 */
    private static final String RETURN_COMMENT_TYPE = "2";

    /** 驳回意见类型，与旧系统 FlowComment.REJECT 保持兼容。 */
    private static final String REJECT_COMMENT_TYPE = "3";

    /** 取消流程意见类型，与旧系统 FlowComment.STOP 保持兼容。 */
    private static final String CANCEL_COMMENT_TYPE = "6";

    /** 撤回流程意见类型，与旧系统 FlowComment.REVOKE 保持兼容。 */
    private static final String REVOKE_COMMENT_TYPE = "7";

    /** 客户端意见写入引擎前允许的最大字符数。 */
    private static final int MAX_OPINION_LENGTH = 500;

    /** 流程、任务和节点主键的服务端安全长度上限。 */
    private static final int MAX_ID_LENGTH = 255;

    /** 整实例取消或驳回时允许原子写入意见的最大活动任务数量。 */
    private static final int MAX_ACTIVE_TASKS_FOR_CANCEL = 2000;

    /** 撤回时允许一次冻结并原子合并的最大直接后继任务数量。 */
    private static final int MAX_ACTIVE_TASKS_FOR_REVOKE = 100;

    /** 动态多实例退回时允许一次冻结并原子合并的最大同节点任务数量。 */
    /** 整实例取消或驳回时允许一次冻结的最大根实例及 CallActivity 子实例数量。 */
    private static final int MAX_ACTIVE_PROCESS_INSTANCES_FOR_TERMINATION = 2000;

    /** 取消后的稳定流程状态。 */
    private static final String CANCELED_STATUS = "canceled";

    /** 驳回后的稳定流程状态，必须与管理员终止 terminated 保持不同业务语义。 */
    private static final String REJECTED_STATUS = "rejected";

    /** 申请退回发起人修改期间的稳定业务状态。 */
    static final String RETURNED_STATUS = "returned";

    /** 退回任务局部保存首审原办理配置的内部变量。 */
    static final String RETURN_ASSIGNMENT_VARIABLE =
            "__ruoyi_workflow_return_assignment";

    /** 退回任务局部保存原发起人主键，供任务监听器严格识别非审批办理人。 */
    static final String RETURN_APPLICANT_VARIABLE =
            "__ruoyi_workflow_return_applicant";

    /** 重新提交由系统生成的审计说明，申请人无需填写审批意见。 */
    private static final String RESUBMIT_AUDIT_OPINION = "申请人修改原表单后重新提交";

    /** 已办列表计算撤回能力时可安全降级为 false 的预期业务状态码。 */
    private static final Set<Integer> REVOKE_INELIGIBLE_STATUS_CODES = Set.of(
            HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);

    /** 详情投影退回能力时可安全归一为 false 的预期业务状态码。 */
    private static final Set<Integer> RETURN_INELIGIBLE_STATUS_CODES = Set.of(
            HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);

    /** 服务端审计 JSON 序列化器，客户端不能控制字段结构。 */
    private static final ObjectMapper AUDIT_MAPPER = JsonMapper.shared();

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowIdentityResolver identityResolver;

    private final RepositoryService repositoryService;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    private final HistoryService historyService;

    private final WfDeployFormMapper deployFormMapper;

    private final WorkflowStartVariableValidator variableValidator;

    private final WorkflowAttachmentService attachmentService;

    private final WorkflowTaskMovementPolicy movementPolicy;

    private final WorkflowTaskCopyService taskCopyService;

    private final WorkflowNextTaskAssignmentService nextTaskAssignmentService;

    private final WorkflowMultiInstanceService multiInstanceService;

    /** 受控重复审批循环的路由、轮次和审计服务。 */
    private final WorkflowControlledLoopService controlledLoopService;

    /**
     * 创建完整任务生命周期服务。
     *
     * @param engineOperations WorkflowEngineOperations，统一事务、认证和异常翻译边界
     * @param identityResolver WorkflowIdentityResolver，读取命令中的正式当前用户解析器
     * @param repositoryService RepositoryService，流程定义和 BPMN 公共查询服务
     * @param runtimeService RuntimeService，流程实例和状态迁移公共服务
     * @param taskService TaskService，活动任务、变量和意见公共服务
     * @param historyService HistoryService，流程及任务历史公共查询服务
     * @param deployFormMapper WfDeployFormMapper，不可变部署表单快照 Mapper
     * @param variableValidator WorkflowStartVariableValidator，表单 schema 变量门禁
     * @param attachmentService WorkflowAttachmentService，任务附件校验、投影和事务绑定服务
     * @param movementPolicy WorkflowTaskMovementPolicy，保守 BPMN 状态迁移策略
     * @param taskCopyService WorkflowTaskCopyService，任务动作抄送计划和事务内持久化服务
     * @param nextTaskAssignmentService WorkflowNextTaskAssignmentService，完成后的动态下一办理人服务
     * @param multiInstanceService WorkflowMultiInstanceService，动态多实例完成 revision CAS 服务
     * @param controlledLoopService WorkflowControlledLoopService，受控循环完成判断和审计服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowTaskLifecycleService(WorkflowEngineOperations engineOperations,
            WorkflowIdentityResolver identityResolver, RepositoryService repositoryService,
            RuntimeService runtimeService, TaskService taskService, HistoryService historyService,
            WfDeployFormMapper deployFormMapper, WorkflowStartVariableValidator variableValidator,
            WorkflowAttachmentService attachmentService,
            WorkflowTaskMovementPolicy movementPolicy,
            WorkflowTaskCopyService taskCopyService,
            WorkflowNextTaskAssignmentService nextTaskAssignmentService,
            WorkflowMultiInstanceService multiInstanceService,
            WorkflowControlledLoopService controlledLoopService)
    {
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.deployFormMapper = deployFormMapper;
        this.variableValidator = variableValidator;
        this.attachmentService = attachmentService;
        this.movementPolicy = movementPolicy;
        this.taskCopyService = taskCopyService;
        this.nextTaskAssignmentService = nextTaskAssignmentService;
        this.multiInstanceService = multiInstanceService;
        this.controlledLoopService = controlledLoopService;
    }

    /**
     * 由流程发起人或受控超级管理员取消 active 或 suspended 的完整业务执行树。
     *
     * @param request WorkflowProcessCancelRequest，流程实例和取消原因
     * @return 无返回值，挂起实例会先在同一事务内激活，再原子提交状态、意见和整树终止
     */
    public void cancelProcess(WorkflowProcessCancelRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        String requestedProcessInstanceId = requireId(request.procInsId());
        String opinion = requireOpinion(request.comment());
        engineOperations.writeAsCurrentUser(actor ->
        {
            ProcessInstance requestedProcessInstance = requireRunningProcessInstanceForCancellation(
                    requestedProcessInstanceId);
            ProcessInstance rootProcessInstance = requireRunningRootProcessInstanceForCancellation(
                    requestedProcessInstance);
            if (!actor.userId().equals(rootProcessInstance.getStartUserId()) && !isAdministrator(actor))
            {
                throw forbidden();
            }

            // Flowable 禁止向挂起执行树写变量和 comment；记录原状态后在同一事务内短暂激活。
            boolean wasSuspended = rootProcessInstance.isSuspended();
            ObjectNode auditPayload = buildAuditPayload(
                    "CANCEL", actor.userId(), opinion, null, null);
            auditPayload.put("wasSuspended", wasSuspended);
            String audit = auditPayload.toString();
            executeConcurrentSensitive(() ->
            {
                if (wasSuspended)
                {
                    runtimeService.activateProcessInstanceById(rootProcessInstance.getId());
                }

                // CallActivity 子实例 ID 只用于定位所属业务树；正式取消始终冻结并终止根实例。
                List<String> processTreeInstanceIds = requireActiveProcessTreeInstanceIds(
                        rootProcessInstance);
                List<Task> activeTasks = taskService.createTaskQuery()
                        .processInstanceIdIn(processTreeInstanceIds)
                        .active()
                        .list();
                if (activeTasks == null || activeTasks.size() > MAX_ACTIVE_TASKS_FOR_CANCEL)
                {
                    throw conflict();
                }

                // 状态变量和 businessStatus 同时维护，供旧查询及 Flowable 8 历史查询分别使用。
                runtimeService.setVariable(rootProcessInstance.getId(),
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, CANCELED_STATUS);
                runtimeService.updateBusinessStatus(rootProcessInstance.getId(), CANCELED_STATUS);
                for (Task activeTask : activeTasks)
                {
                    if (activeTask == null || !StringUtils.hasText(activeTask.getId())
                            || !StringUtils.hasText(activeTask.getProcessInstanceId())
                            || !processTreeInstanceIds.contains(
                                    activeTask.getProcessInstanceId().trim()))
                    {
                        throw dataError();
                    }
                    // 意见必须关联任务真实所属子实例，否则 Flowable 不能保持历史外键语义。
                    taskService.addComment(activeTask.getId(),
                            activeTask.getProcessInstanceId(),
                            CANCEL_COMMENT_TYPE, audit);
                }
                // 删除根实例才能级联结束所有 CallActivity 子流程，并保留统一结构化删除原因。
                runtimeService.deleteProcessInstance(rootProcessInstance.getId(), audit);
                verifyTerminatedInstance(rootProcessInstance.getId(),
                        processTreeInstanceIds, CANCELED_STATUS);
            });
            return null;
        });
    }

    /**
     * 使用与正式撤回命令完全相同的对象授权、状态、执行树和 BPMN 拓扑规则计算只读能力。
     *
     * @param processInstanceId String，已办任务所属流程实例主键
     * @param historicTaskId String，当前用户真实完成的历史任务主键
     * @return boolean，当前快照允许进入撤回命令时返回 true；预期不可撤回状态返回 false
     */
    public boolean isProcessRevocable(String processInstanceId, String historicTaskId)
    {
        return engineOperations.readWithServiceExceptionHandler(() ->
        {
            // 参数、身份、对象授权和执行树资格必须在同一只读事务快照中完成。
            String normalizedProcessInstanceId = requireId(processInstanceId);
            String normalizedHistoricTaskId = requireId(historicTaskId);
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            requireRevokePreparation(normalizedProcessInstanceId,
                    normalizedHistoricTaskId, actor);
            return true;
        }, exception ->
        {
            // 预期权限、对象和状态分支只影响当前行按钮；数据损坏类 500 必须继续阻断列表。
            if (REVOKE_INELIGIBLE_STATUS_CODES.contains(exception.getCode()))
            {
                return false;
            }
            throw exception;
        });
    }

    /**
     * 将当前用户最近正常完成且后继尚未处理的任务撤回为新的活动任务。
     *
     * @param request WorkflowProcessRevokeRequest，实例、本人历史任务和撤回原因
     * @return 无返回值，成功时后继任务关闭、来源任务重建且审计意见同事务持久化
     */
    public void revokeProcess(WorkflowProcessRevokeRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        String processInstanceId = requireId(request.procInsId());
        String historicTaskId = requireId(request.taskId());
        String opinion = requireOpinion(request.comment());
        engineOperations.writeAsCurrentUser(actor ->
        {
            RevokePreparation preparation = requireRevokePreparation(
                    processInstanceId, historicTaskId, actor);
            HistoricTaskInstance completedTask = preparation.completedTask();
            RevokePlan revokePlan = preparation.revokePlan();
            String audit = buildAudit("REVOKE", actor.userId(), opinion,
                    revokePlan.sourceNodeKey(), completedTask.getId());

            executeConcurrentSensitive(() ->
            {
                // 先以稳定顺序更新全部后继任务取得 InnoDB 行锁，再按当前数据库状态二次校验。
                // 认领、开始办理、委派或完成若与撤回竞争，只能有一方提交。
                lockAndRevalidateRevokeTasks(revokePlan, completedTask);
                // 全部后继使用同一结构化意见；任一 comment 或迁移失败都由外层事务整体回滚。
                for (Task successor : revokePlan.successorTasks())
                {
                    taskService.addComment(successor.getId(), processInstanceId,
                            REVOKE_COMMENT_TYPE, audit);
                }
                var stateBuilder = runtimeService.createChangeActivityStateBuilder()
                        .processInstanceId(processInstanceId);
                if (revokePlan.executionIds().size() == 1)
                {
                    stateBuilder.moveExecutionToActivityId(
                            revokePlan.executionIds().get(0), revokePlan.sourceNodeKey());
                }
                else
                {
                    // 并行后继必须由一次 Flowable 命令合并，禁止逐 execution 迁移形成部分成功。
                    stateBuilder.moveExecutionsToSingleActivityId(
                            revokePlan.executionIds(), revokePlan.sourceNodeKey());
                }
                stateBuilder.changeState();
                verifyRevokeResult(revokePlan, audit);
            });
            return null;
        });
    }

    /**
     * 由当前办理人使用不可变部署表单 schema 校验变量后完成活动任务。
     *
     * @param request WorkflowTaskCompleteRequest，任务、审批意见和表单变量
     * @return 无返回值，成功时意见、变量和任务完成在同一事务提交
     */
    public void completeTask(WorkflowTaskCompleteRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        String taskId = requireId(request.taskId());
        String opinion = requireOpinion(request.comment());
        try
        {
            engineOperations.writeAsCurrentUser(actor ->
            {
                Task task = requireActiveTask(taskId);
                requireActiveProcessInstance(task.getProcessInstanceId());
                requireCurrentAssignee(task, actor);
                if (task.getDelegationState() == DelegationState.PENDING)
                {
                    // PENDING 委派必须先按 Flowable 标准语义 resolve，不能直接跳过 owner 回退。
                    throw conflict();
                }

                CompletionVariables completionVariables = validateCompletionVariables(task,
                        request.variables());
                Map<String, Object> projectedVariables = attachmentService.prepareTaskVariables(
                        actor.userId(), task.getProcessInstanceId(), completionVariables.values(),
                        completionVariables.attachmentIdsByField());
                String submissionSnapshot = completionVariables.formSnapshot() == null ? null
                        : WorkflowFormSubmissionSnapshotCodec.encodeTask(
                                completionVariables.deploymentId(),
                                completionVariables.formSnapshot().getSourceType(),
                                completionVariables.formSnapshot().getFormId(),
                                completionVariables.formSnapshot().getFormKey(),
                                 completionVariables.formSnapshot().getNodeKey(), taskId,
                                 completionVariables.localScope(), projectedVariables);
                // 在完成前冻结抄送事件和唯一直接后继拓扑，避免任务删除后再信任客户端或推断来源元数据。
                WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                        WorkflowTaskCopyAction.COMPLETE, task, actor, request.copyUserIds());
                WorkflowNextTaskAssignmentService.AssignmentPlan assignmentPlan =
                        nextTaskAssignmentService.prepare(task, request.nextUserIds());
                executeConcurrentSensitive(() ->
                {
                    // 动态 MI 必须先占用 expectedRevision；普通任务返回空计划并保持原有兼容路径。
                    WorkflowMultiInstanceService.CompletionRevision completionRevision =
                            multiInstanceService.reserveCompletionRevision(task,
                                    request.expectedRevision(), actor);
                    // 循环判断只读取已通过正式节点表单 schema 的投影值，并在任务完成前写入同事务路由与审计。
                    controlledLoopService.prepareCompletion(task,
                            completionVariables.deploymentId(), projectedVariables, actor.userId());
                    addCompletionAuditComment(task, actor.userId(), opinion,
                            completionRevision);
                    // 附件条件更新与意见、变量和任务完成共享事务，任何失败都会整体回滚。
                    attachmentService.bindTaskAttachments(actor.userId(),
                            task.getProcessInstanceId(), taskId, task.getTaskDefinitionKey(),
                            completionVariables.attachmentIdsByField());
                    if (submissionSnapshot != null)
                    {
                        // 内部快照始终使用 task-local，确保历史更新由真实 taskId 强关联且不污染业务变量。
                        taskService.setVariableLocal(taskId,
                                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                                submissionSnapshot);
                    }
                    // Flowable 8 只有显式传入 userId 的重载会写 completedBy；该字段是已办查询、对象授权和审计的正式依据。
                    taskService.complete(taskId, actor.userId(), projectedVariables,
                            completionVariables.localScope());
                    // 完成产生真实后继任务后再应用动态身份，并在写后复核；任一步失败都会回滚整个完成事务。
                    nextTaskAssignmentService.apply(assignmentPlan);
                    taskCopyService.persist(copyPlan);
                });
                return null;
            });
        }
        catch (RuntimeException exception)
        {
            if (request.expectedRevision() == null)
            {
                // 普通任务继续使用原有冲突契约，禁止误附动态多实例 revision 子码。
                throw exception;
            }
            // Flowable 乐观锁可能直到事务代理提交时才暴露，必须在代理外补齐动态 revision 子码。
            throw engineOperations.withConcurrencyConflictSubCode(exception,
                    WorkflowMultiInstanceService.REVISION_CONFLICT_SUB_CODE);
        }
    }

    /**
     * 由当前办理人将普通、并行或多实例流程整实例原子驳回为 rejected 终态。
     *
     * @param request WorkflowTaskRejectRequest，任务和驳回原因
     * @return 无返回值，成功时全部活动任务、rejected 状态、意见和抄送在同一事务提交
     */
    public void rejectTask(WorkflowTaskRejectRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        String taskId = requireId(request.taskId());
        String opinion = requireOpinion(request.comment());
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            ProcessInstance taskProcessInstance = requireActiveProcessInstance(
                    task.getProcessInstanceId());
            ProcessInstance rootProcessInstance = requireActiveRootProcessInstance(
                    taskProcessInstance);
            requireCurrentAssignee(task, actor);
            requireMovableTask(task);
            // 先冻结根实例及全部 CallActivity 子实例主键；Flowable 的
            // processInstanceIdWithChildren 仅查询 entity link，不能代表真实执行树。
            List<String> processTreeInstanceIds = requireActiveProcessTreeInstanceIds(
                    rootProcessInstance);
            List<Task> activeTasks = taskService.createTaskQuery()
                    .processInstanceIdIn(processTreeInstanceIds)
                    .active()
                    .list();
            if (activeTasks == null || activeTasks.isEmpty()
                    || activeTasks.size() > MAX_ACTIVE_TASKS_FOR_CANCEL
                    || activeTasks.stream().noneMatch(activeTask -> activeTask != null
                            && taskId.equals(activeTask.getId())))
            {
                throw conflict();
            }
            WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                    WorkflowTaskCopyAction.REJECT, task, actor, request.copyUserIds());
            String audit = buildAudit("REJECT", actor.userId(), opinion,
                    task.getTaskDefinitionKey(), null);

            executeConcurrentSensitive(() ->
            {
                // 先把同一结构化意见写到全部活动任务，确保并行和多实例 sibling 的历史均可追踪。
                for (Task activeTask : activeTasks)
                {
                    if (activeTask == null || !StringUtils.hasText(activeTask.getId())
                            || !StringUtils.hasText(activeTask.getProcessInstanceId()))
                    {
                        throw dataError();
                    }
                    taskService.addComment(activeTask.getId(),
                            activeTask.getProcessInstanceId(),
                            REJECT_COMMENT_TYPE, audit);
                }
                runtimeService.setVariable(rootProcessInstance.getId(),
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, REJECTED_STATUS);
                runtimeService.updateBusinessStatus(rootProcessInstance.getId(), REJECTED_STATUS);
                // CallActivity 子任务也属于同一业务实例；始终删除根实例才能级联结束全部子流程和 sibling。
                runtimeService.deleteProcessInstance(rootProcessInstance.getId(), audit);
                taskCopyService.persist(copyPlan);
                verifyTerminatedInstance(rootProcessInstance.getId(),
                        processTreeInstanceIds, REJECTED_STATUS);
            });
            return null;
        });
    }

    /**
     * 在整实例取消或驳回写命令后核对运行树已消失且历史业务终态准确。
     *
     * @param processInstanceId String，本次被整实例终止的根流程实例主键
     * @param processTreeInstanceIds List&lt;String&gt;，删除前冻结的根实例及全部子实例主键
     * @param expectedBusinessStatus String，本次动作必须持久化的 canceled 或 rejected 终态
     * @return 无返回值，运行态残留或历史终态漂移时抛出异常并回滚整个事务
     */
    private void verifyTerminatedInstance(String processInstanceId,
            List<String> processTreeInstanceIds, String expectedBusinessStatus)
    {
        long remainingInstanceCount = processTreeInstanceIds.stream()
                .mapToLong(instanceId -> runtimeService.createProcessInstanceQuery()
                        .processInstanceId(instanceId)
                        .count())
                .sum();
        long remainingExecutionCount = runtimeService.createExecutionQuery()
                .rootProcessInstanceId(processInstanceId)
                .count();
        long remainingTaskCount = taskService.createTaskQuery()
                .processInstanceIdIn(processTreeInstanceIds)
                .count();
        HistoricProcessInstance historicInstance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (remainingInstanceCount != 0L || remainingExecutionCount != 0L
                || remainingTaskCount != 0L || historicInstance == null
                || historicInstance.getEndTime() == null
                || !expectedBusinessStatus.equals(historicInstance.getBusinessStatus()))
        {
            throw dataError();
        }
    }

    /**
     * 冻结根流程实例及其全部活动 CallActivity 子实例主键，并校验父子执行边界完整。
     *
     * @param rootProcessInstance ProcessInstance，已经确认活动且无 super execution 的根业务实例
     * @return List&lt;String&gt;，按实例主键排序且至少包含根实例的完整活动流程树
     */
    private List<String> requireActiveProcessTreeInstanceIds(
            ProcessInstance rootProcessInstance)
    {
        String rootProcessInstanceId = rootProcessInstance.getId();
        List<Execution> processTreeExecutions = runtimeService.createExecutionQuery()
                .rootProcessInstanceId(rootProcessInstanceId)
                .list();
        if (processTreeExecutions == null)
        {
            throw dataError();
        }

        // execution 查询覆盖并行分支和多层 CallActivity；这里只提取正式流程实例主键并去重。
        Set<String> processInstanceIds = new LinkedHashSet<>();
        processInstanceIds.add(rootProcessInstanceId);
        for (Execution execution : processTreeExecutions)
        {
            if (execution == null || !StringUtils.hasText(execution.getProcessInstanceId())
                    || (StringUtils.hasText(execution.getRootProcessInstanceId())
                            && !rootProcessInstanceId.equals(
                                    execution.getRootProcessInstanceId().trim())))
            {
                throw dataError();
            }
            processInstanceIds.add(execution.getProcessInstanceId().trim());
        }
        if (processInstanceIds.size() > MAX_ACTIVE_PROCESS_INSTANCES_FOR_TERMINATION)
        {
            throw conflict();
        }

        List<ProcessInstance> activeInstances = runtimeService.createProcessInstanceQuery()
                .processInstanceIds(processInstanceIds)
                .active()
                .list();
        if (activeInstances == null || activeInstances.size() != processInstanceIds.size())
        {
            throw conflict();
        }

        // 再读取正式 ProcessInstance，避免仅凭 execution 字段终止已挂起或父子关系漂移的实例。
        Map<String, ProcessInstance> instancesById = new LinkedHashMap<>();
        for (ProcessInstance activeInstance : activeInstances)
        {
            if (activeInstance == null || !StringUtils.hasText(activeInstance.getId())
                    || activeInstance.isSuspended())
            {
                throw dataError();
            }
            String instanceId = activeInstance.getId().trim();
            String declaredRootId = activeInstance.getRootProcessInstanceId();
            boolean rootInstance = rootProcessInstanceId.equals(instanceId);
            if (rootInstance)
            {
                if (StringUtils.hasText(activeInstance.getSuperExecutionId())
                        || (StringUtils.hasText(declaredRootId)
                                && !rootProcessInstanceId.equals(declaredRootId.trim())))
                {
                    throw dataError();
                }
            }
            else if (!StringUtils.hasText(declaredRootId)
                    || !rootProcessInstanceId.equals(declaredRootId.trim())
                    || !StringUtils.hasText(activeInstance.getSuperExecutionId()))
            {
                throw dataError();
            }
            if (!processInstanceIds.contains(instanceId)
                    || instancesById.put(instanceId, activeInstance) != null)
            {
                throw dataError();
            }
        }
        if (!instancesById.keySet().equals(processInstanceIds))
        {
            throw dataError();
        }
        return instancesById.keySet().stream().sorted().toList();
    }

    /**
     * 由当前办理人将普通串行任务退回实时合法历史节点。
     *
     * @param request WorkflowTaskReturnRequest，任务、目标节点和退回原因
     * @return 无返回值，成功时原任务关闭、目标任务重建且意见同事务持久化
     */
    public void returnTask(WorkflowTaskReturnRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        String taskId = requireId(request.taskId());
        String opinion = requireOpinion(request.comment());
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            ProcessInstance processInstance = requireActiveProcessInstance(task.getProcessInstanceId());
            requireCurrentAssignee(task, actor);
            requireMovableTask(task);
            if (!WorkflowProcessStartService.RUNNING_STATUS.equals(runtimeService.getVariable(
                    processInstance.getId(), WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)))
            {
                throw conflict();
            }
            if (!StringUtils.hasText(processInstance.getStartUserId()))
            {
                throw dataError();
            }
            BpmnContext context = requireBpmnContext(task.getProcessDefinitionId());
            UserTask currentNode = movementPolicy.requireMainProcessReturnSource(context.model(),
                    context.definition().getKey(), task.getTaskDefinitionKey());
            String targetKey = requireFirstApprovalNode(task, context, currentNode);
            List<Task> activeTasks = requireReturnableActiveTasks(task);
            String executionId = activeTasks.get(0).getExecutionId();
            WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                    WorkflowTaskCopyAction.RETURN, task, actor, request.copyUserIds());

            executeConcurrentSensitive(() ->
            {
                // 退回只处理唯一活动审批分支；并行、会签和多实例结构在写审计前已被拒绝。
                for (Task activeTask : activeTasks)
                {
                    // 退回任务关系已由 Flowable comment 的 taskId 固化；sourceTaskId 仅表示撤回来源，不能混入退回契约。
                    addAuditComment(activeTask, RETURN_COMMENT_TYPE, "RETURN",
                            actor.userId(), opinion, targetKey, null);
                }
                var stateBuilder = runtimeService.createChangeActivityStateBuilder()
                        .processInstanceId(task.getProcessInstanceId());
                stateBuilder.moveExecutionToActivityId(executionId, targetKey);
                stateBuilder.changeState();
                Task returnedTask = requireSingleActiveTask(task.getProcessInstanceId(), targetKey);
                ReturnedAssignmentSnapshot assignment = captureAssignment(returnedTask);
                taskService.setVariableLocal(returnedTask.getId(), RETURN_ASSIGNMENT_VARIABLE,
                        encodeReturnedAssignment(assignment));
                taskService.setVariableLocal(returnedTask.getId(), RETURN_APPLICANT_VARIABLE,
                        processInstance.getStartUserId());
                removeCandidateLinks(returnedTask.getId());
                taskService.setOwner(returnedTask.getId(), null);
                runtimeService.setVariable(task.getProcessInstanceId(),
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE, RETURNED_STATUS);
                runtimeService.updateBusinessStatus(task.getProcessInstanceId(), RETURNED_STATUS);
                // 内部发起人标记已落到同一任务后再改派，监听器只对该受控退回场景放宽审批资格。
                taskService.setAssignee(returnedTask.getId(), processInstance.getStartUserId());
                taskCopyService.persist(copyPlan);
                verifyReturnedApplication(returnedTask.getId(), processInstance.getStartUserId());
            });
            return null;
        });
    }

    /**
     * 由发起人保存修改后的原申请表，并恢复首个审批节点在退回前固化的办理配置。
     *
     * @param request WorkflowApplicationResubmitRequest，退回任务和覆盖后的开始表单变量
     * @return 无返回值，表单变量、附件、快照、审计、办理配置和流程状态同事务提交
     */
    public void resubmitApplication(WorkflowApplicationResubmitRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        String taskId = requireId(request.taskId());
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = requireActiveTask(taskId);
            ProcessInstance instance = requireActiveProcessInstance(task.getProcessInstanceId());
            requireCurrentAssignee(task, actor);
            if (!actor.userId().equals(instance.getStartUserId()))
            {
                throw forbidden();
            }
            if (!RETURNED_STATUS.equals(runtimeService.getVariable(
                    instance.getId(), WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)))
            {
                throw conflict();
            }
            Object rawAssignment = taskService.getVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE);
            if (!(rawAssignment instanceof String assignmentJson) || !StringUtils.hasText(assignmentJson))
            {
                throw conflict();
            }
            Object returnedApplicant = taskService.getVariableLocal(taskId,
                    RETURN_APPLICANT_VARIABLE);
            if (!actor.userId().equals(returnedApplicant)
                    || !instance.getStartUserId().equals(returnedApplicant))
            {
                throw conflict();
            }
            ReturnedAssignmentSnapshot assignment = decodeReturnedAssignment(assignmentJson);
            WfDeployForm startForm = requireStartFormSnapshot(task);
            WorkflowValidatedStartVariables validated = variableValidator.validateForStart(
                    startForm.getContent(), request.variables());
            Map<String, Object> projected = attachmentService.prepareTaskVariables(
                    actor.userId(), instance.getId(), validated.variables(),
                    validated.attachmentIdsByField());

            executeConcurrentSensitive(() ->
            {
                // 开始表单附件允许复用同实例已绑定文件，新文件仍与本次真实重新提交任务绑定并参与回滚。
                attachmentService.bindTaskAttachments(actor.userId(), instance.getId(), taskId,
                        startForm.getNodeKey(), validated.attachmentIdsByField());
                replaceStartFormVariables(instance, startForm, projected);
                runtimeService.setVariable(instance.getId(),
                        WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME,
                        WorkflowFormSubmissionSnapshotCodec.encodeStart(
                                instance.getDeploymentId(), startForm.getSourceType(), startForm.getFormId(),
                                startForm.getFormKey(), startForm.getNodeKey(), projected));
                addAuditComment(task, COMPLETE_COMMENT_TYPE, "RESUBMIT",
                        actor.userId(), RESUBMIT_AUDIT_OPINION,
                        task.getTaskDefinitionKey(), null);
                // 先删除退回专用标记，恢复首审批人时必须重新走完整审批资格校验。
                taskService.removeVariableLocal(taskId, RETURN_APPLICANT_VARIABLE);
                restoreAssignment(taskId, assignment);
                taskService.removeVariableLocal(taskId, RETURN_ASSIGNMENT_VARIABLE);
                runtimeService.setVariable(instance.getId(),
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE,
                        WorkflowProcessStartService.RUNNING_STATUS);
                runtimeService.updateBusinessStatus(instance.getId(),
                        WorkflowProcessStartService.RUNNING_STATUS);
                verifyResubmittedApplication(taskId, assignment);
            });
            return null;
        });
    }

    /**
     * 判断当前用户能否对指定任务执行真实退回。
     *
     * @param taskId String，详情页请求并已完成对象关系核验的任务主键
     * @return boolean，正式退回全部只读前置条件满足且至少存在一个合法目标时返回 true
     */
    public boolean isTaskReturnAllowed(String taskId)
    {
        return engineOperations.readWithServiceExceptionHandler(() ->
        {
            // 能力投影复用直接退回发起人的对象、执行树和首审批历史准备链。
            String normalizedTaskId = requireId(taskId);
            WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
            Task task = requireActiveTask(normalizedTaskId);
            ProcessInstance instance = requireActiveProcessInstance(task.getProcessInstanceId());
            requireCurrentAssignee(task, actor);
            requireMovableTask(task);
            if (!WorkflowProcessStartService.RUNNING_STATUS.equals(runtimeService.getVariable(
                    instance.getId(), WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)))
            {
                throw conflict();
            }
            if (!StringUtils.hasText(instance.getStartUserId()))
            {
                throw dataError();
            }
            BpmnContext context = requireBpmnContext(task.getProcessDefinitionId());
            UserTask currentNode = movementPolicy.requireMainProcessReturnSource(context.model(),
                    context.definition().getKey(), task.getTaskDefinitionKey());
            requireFirstApprovalNode(task, context, currentNode);
            requireReturnableActiveTasks(task);
            return true;
        }, exception ->
        {
            // 预期权限、对象和状态分支只关闭按钮；数据损坏类 500 必须继续阻断详情。
            if (RETURN_INELIGIBLE_STATUS_CODES.contains(exception.getCode()))
            {
                return false;
            }
            throw exception;
        });
    }

    /**
     * 冻结普通串行退回任务及其唯一 execution。
     *
     * @param task Task，当前用户真实持有并发起退回的活动任务
     * @param currentNode UserTask，已通过 BPMN 退回来源白名单的普通当前节点
     * @return ReturnExecutionPlan，仅包含当前活动任务和唯一 execution 主键
     */
    private ReturnExecutionPlan prepareReturnExecutionPlan(Task task, UserTask currentNode)
    {
        // 防御性复核 BPMN 来源，避免未来调用绕过 movementPolicy 后重新开放多实例跨轮退回。
        if (currentNode == null || currentNode.hasMultiInstanceLoopCharacteristics())
        {
            throw conflict();
        }
        requireSingleExecutionTree(task);
        return new ReturnExecutionPlan(List.of(task), List.of(task.getExecutionId()));
    }

    /**
     * 核对普通退回状态迁移只生成一个目标活动任务，且原任务已离开运行时表。
     *
     * @param processInstanceId String，发生退回的真实流程实例主键
     * @param targetKey String，服务端实时校验后的目标用户任务节点 key
     * @param sourceTasks List&lt;Task&gt;，退回命令前冻结的单一来源活动任务
     * @return 无返回值，迁移结果不完整时抛出异常并回滚整个事务
     */
    private void verifyReturnResult(String processInstanceId, String targetKey,
            List<Task> sourceTasks)
    {
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list();
        if (activeTasks == null || activeTasks.size() != 1
                || !targetKey.equals(activeTasks.get(0).getTaskDefinitionKey()))
        {
            throw dataError();
        }
        Set<String> sourceTaskIds = sourceTasks.stream()
                .map(Task::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (sourceTaskIds.contains(activeTasks.get(0).getId()))
        {
            throw dataError();
        }
    }

    /**
     * 使用部署时固化的当前节点表单 schema 校验完成变量并解析局部作用域。
     *
     * @param task Task，已经完成活动态和办理人校验的当前任务
     * @param requestedVariables Map&lt;String, Object&gt;，客户端提交的表单变量
     * @return CompletionVariables，规范化变量和 BPMN localScope 设置
     */
    private CompletionVariables validateCompletionVariables(Task task,
            Map<String, Object> requestedVariables)
    {
        BpmnContext context = requireBpmnContext(task.getProcessDefinitionId());
        FlowElement flowElement = context.process().getFlowElement(task.getTaskDefinitionKey(), true);
        if (!(flowElement instanceof UserTask userTask))
        {
            throw dataError();
        }

        Map<String, Object> variables = requestedVariables == null ? Map.of() : requestedVariables;
        String formKey = resolveFormKey(userTask);
        if (formKey == null)
        {
            if (!variables.isEmpty())
            {
                throw invalidArgument();
            }
            return new CompletionVariables(Map.of(), isTaskLocal(userTask), Map.of(),
                    context.definition().getDeploymentId(), null);
        }

        List<WfDeployForm> snapshots = deployFormMapper.selectByDeploymentId(
                context.definition().getDeploymentId());
        if (snapshots == null)
        {
            throw dataError();
        }
        List<WfDeployForm> matched = snapshots.stream()
                .filter(snapshot -> snapshot != null
                        && task.getTaskDefinitionKey().equals(snapshot.getNodeKey())
                        && formKey.equals(snapshot.getFormKey()))
                .toList();
        if (matched.size() != 1
                || !WorkflowFormSourceType.isConsistent(matched.get(0).getSourceType(),
                        matched.get(0).getFormId())
                || !StringUtils.hasText(matched.get(0).getContent()))
        {
            throw dataError();
        }
        WorkflowValidatedStartVariables validated = variableValidator.validateForStart(
                matched.get(0).getContent(), variables);
        return new CompletionVariables(validated.variables(), isTaskLocal(userTask),
                validated.attachmentIdsByField(), context.definition().getDeploymentId(),
                matched.get(0));
    }

    /**
     * 解析用户任务的正式模板或 BPMN 内嵌表单键。
     *
     * @param userTask UserTask，当前 BPMN 用户任务
     * @return String，模板 formKey、内嵌稳定键或无表单时的 null
     */
    private String resolveFormKey(UserTask userTask)
    {
        boolean hasTemplate = StringUtils.hasText(userTask.getFormKey());
        boolean hasEmbedded = userTask.getFormProperties() != null
                && !userTask.getFormProperties().isEmpty();
        if (hasTemplate && hasEmbedded)
        {
            throw dataError();
        }
        if (hasTemplate)
        {
            return userTask.getFormKey();
        }
        return hasEmbedded ? WorkflowFormSourceType.EMBEDDED_FORM_KEY : null;
    }

    /**
     * 读取用户任务的 Flowable localScope 扩展属性。
     *
     * @param userTask UserTask，当前 BPMN 用户任务节点
     * @return boolean，属性值为 true 或 1 时返回 true
     */
    private boolean isTaskLocal(UserTask userTask)
    {
        Map<String, List<ExtensionAttribute>> attributes = userTask.getAttributes();
        if (attributes == null)
        {
            return false;
        }
        List<ExtensionAttribute> localScope = attributes.get("localScope");
        if (localScope == null || localScope.isEmpty() || localScope.get(0) == null)
        {
            return false;
        }
        String value = localScope.get(0).getValue();
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /**
     * 查询活动任务，并区分不存在、已完成和挂起等状态。
     *
     * @param taskId String，任务主键
     * @return Task，未挂起的活动任务
     */
    private Task requireActiveTask(String taskId)
    {
        Task activeTask = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (activeTask != null && !activeTask.isSuspended())
        {
            return activeTask;
        }
        Task existingTask = activeTask != null ? activeTask
                : taskService.createTaskQuery().taskId(taskId).singleResult();
        if (existingTask != null)
        {
            throw conflict();
        }
        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId)
                .singleResult();
        if (historicTask != null)
        {
            // 重复提交命中已结束历史任务时返回状态冲突，而不是误报对象不存在。
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 查询仍活动且未挂起的流程实例，并把历史实例重复操作映射为状态冲突。
     *
     * @param processInstanceId String，流程实例主键
     * @return ProcessInstance，仍处于活动态的流程实例
     */
    private ProcessInstance requireActiveProcessInstance(String processInstanceId)
    {
        if (!StringUtils.hasText(processInstanceId))
        {
            throw conflict();
        }
        ProcessInstance activeInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .active()
                .singleResult();
        if (activeInstance != null && !activeInstance.isSuspended())
        {
            return activeInstance;
        }
        ProcessInstance existingInstance = activeInstance != null ? activeInstance
                : runtimeService.createProcessInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .singleResult();
        if (existingInstance != null)
        {
            throw conflict();
        }
        HistoricProcessInstance historicInstance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (historicInstance != null)
        {
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 查询取消动作目标的未结束流程实例，同时允许 active 和 suspended 状态。
     *
     * @param processInstanceId String，客户端提交的根或 CallActivity 子流程实例主键
     * @return ProcessInstance，仍存在于运行时表且可由发起人取消的实例
     */
    private ProcessInstance requireRunningProcessInstanceForCancellation(String processInstanceId)
    {
        if (!StringUtils.hasText(processInstanceId))
        {
            throw conflict();
        }
        ProcessInstance runningInstance = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (runningInstance != null)
        {
            return runningInstance;
        }
        HistoricProcessInstance historicInstance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (historicInstance != null)
        {
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 从取消请求直接定位的根或子实例解析未结束根业务实例。
     *
     * @param directProcessInstance ProcessInstance，可为 active 或 suspended 的直接目标实例
     * @return ProcessInstance，取消动作必须写状态并级联结束的根业务实例
     */
    private ProcessInstance requireRunningRootProcessInstanceForCancellation(
            ProcessInstance directProcessInstance)
    {
        if (directProcessInstance == null || !StringUtils.hasText(directProcessInstance.getId()))
        {
            throw dataError();
        }
        String directProcessInstanceId = directProcessInstance.getId();
        String declaredRootId = directProcessInstance.getRootProcessInstanceId();
        String rootProcessInstanceId = StringUtils.hasText(declaredRootId)
                ? declaredRootId.trim() : directProcessInstanceId;
        boolean directIsRoot = directProcessInstanceId.equals(rootProcessInstanceId);
        if (!directIsRoot && !StringUtils.hasText(directProcessInstance.getSuperExecutionId()))
        {
            throw dataError();
        }

        ProcessInstance rootProcessInstance = directIsRoot
                ? directProcessInstance
                : requireRunningProcessInstanceForCancellation(rootProcessInstanceId);
        String rootDeclaredId = rootProcessInstance.getRootProcessInstanceId();
        if (!rootProcessInstanceId.equals(rootProcessInstance.getId())
                || StringUtils.hasText(rootProcessInstance.getSuperExecutionId())
                || (StringUtils.hasText(rootDeclaredId)
                        && !rootProcessInstanceId.equals(rootDeclaredId.trim())))
        {
            throw dataError();
        }
        return rootProcessInstance;
    }

    /**
     * 从任意直接实例解析仍活动的根业务流程实例，并校验 CallActivity 父子关系完整。
     *
     * @param directProcessInstance ProcessInstance，任务或请求直接定位的活动流程实例
     * @return ProcessInstance，整业务实例取消或驳回时必须删除的活动根流程实例
     */
    private ProcessInstance requireActiveRootProcessInstance(
            ProcessInstance directProcessInstance)
    {
        if (directProcessInstance == null || !StringUtils.hasText(directProcessInstance.getId()))
        {
            throw dataError();
        }
        String taskProcessInstanceId = directProcessInstance.getId();
        String declaredRootId = directProcessInstance.getRootProcessInstanceId();
        String rootProcessInstanceId = StringUtils.hasText(declaredRootId)
                ? declaredRootId.trim() : taskProcessInstanceId;
        boolean taskBelongsToRoot = taskProcessInstanceId.equals(rootProcessInstanceId);
        if (!taskBelongsToRoot && !StringUtils.hasText(directProcessInstance.getSuperExecutionId()))
        {
            // 根 ID 指向其他实例但缺少 CallActivity super execution，属于不可安全终止的数据漂移。
            throw dataError();
        }

        ProcessInstance rootProcessInstance = taskBelongsToRoot
                ? directProcessInstance : requireActiveProcessInstance(rootProcessInstanceId);
        String rootDeclaredId = rootProcessInstance.getRootProcessInstanceId();
        if (!rootProcessInstanceId.equals(rootProcessInstance.getId())
                || StringUtils.hasText(rootProcessInstance.getSuperExecutionId())
                || (StringUtils.hasText(rootDeclaredId)
                        && !rootProcessInstanceId.equals(rootDeclaredId.trim())))
        {
            throw dataError();
        }
        return rootProcessInstance;
    }

    /**
     * 以同一套授权、状态、执行树和 BPMN 约束准备只读能力判断或正式撤回命令。
     *
     * @param processInstanceId String，客户端或已办列表提供的流程实例主键
     * @param historicTaskId String，拟撤回的历史任务主键
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前正式身份
     * @return RevokePreparation，冻结当前快照得到的历史来源任务和原子迁移计划
     */
    private RevokePreparation requireRevokePreparation(String processInstanceId,
            String historicTaskId, WorkflowCurrentIdentity actor)
    {
        HistoricTaskInstance completedTask = requireCompletedTask(historicTaskId);
        requireSame(processInstanceId, completedTask.getProcessInstanceId());
        requireTaskCompletedBy(completedTask, actor);
        if (StringUtils.hasText(completedTask.getDeleteReason()) || completedTask.getEndTime() == null)
        {
            throw conflict();
        }

        requireActiveProcessInstance(processInstanceId);
        List<Task> activeTasks = requireUntouchedRevokeTasks(processInstanceId, completedTask);
        if (hasFinishedSuccessor(completedTask))
        {
            throw conflict();
        }

        BpmnContext context = requireBpmnContext(completedTask.getProcessDefinitionId());
        UserTask completedNode = movementPolicy.requireMainProcessUserTask(context.model(),
                context.definition().getKey(), completedTask.getTaskDefinitionKey());
        RevokePlan revokePlan = requireSafeRevokePlan(context, completedTask,
                completedNode, activeTasks);
        return new RevokePreparation(completedTask, revokePlan);
    }

    /**
     * 查询撤回来源历史任务并区分未完成、已删除和完全不存在。
     *
     * @param taskId String，客户端指定的历史任务主键
     * @return HistoricTaskInstance，存在且已有结束时间的任务历史
     */
    private HistoricTaskInstance requireCompletedTask(String taskId)
    {
        HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
                .taskId(taskId)
                .finished()
                .singleResult();
        if (historicTask != null)
        {
            return historicTask;
        }
        if (taskService.createTaskQuery().taskId(taskId).singleResult() != null
                || historyService.createHistoricTaskInstanceQuery().taskId(taskId).singleResult() != null)
        {
            throw conflict();
        }
        throw notFound();
    }

    /**
     * 校验撤回来源确实由当前正式用户完成，兼容旧历史缺少 completedBy 的静态办理人数据。
     *
     * @param completedTask HistoricTaskInstance，待撤回的已完成任务
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前身份
     * @return 无返回值，不属于当前用户时抛出 HTTP 403 业务异常
     */
    private void requireTaskCompletedBy(HistoricTaskInstance completedTask,
            WorkflowCurrentIdentity actor)
    {
        String completedBy = completedTask.getCompletedBy();
        boolean ownedByActor = actor.userId().equals(completedBy)
                || (!StringUtils.hasText(completedBy)
                        && actor.userId().equals(completedTask.getAssignee()));
        if (!ownedByActor)
        {
            throw forbidden();
        }
    }

    /**
     * 冻结撤回候选的全部活动任务，并确认每个任务尚未被认领、开始、委派或附加业务证据。
     *
     * @param processInstanceId String，当前活动流程实例主键
     * @param completedTask HistoricTaskInstance，当前用户拟恢复的来源历史任务
     * @return List&lt;Task&gt;，按任务主键稳定排序的未处理直接后继候选
     */
    private List<Task> requireUntouchedRevokeTasks(String processInstanceId,
            HistoricTaskInstance completedTask)
    {
        List<Task> queriedTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .active()
                .list();
        if (queriedTasks == null || queriedTasks.isEmpty()
                || queriedTasks.size() > MAX_ACTIVE_TASKS_FOR_REVOKE)
        {
            throw conflict();
        }

        List<Task> activeTasks = new ArrayList<>(queriedTasks);
        activeTasks.sort(Comparator.comparing(Task::getId,
                Comparator.nullsFirst(String::compareTo)));
        for (Task activeTask : activeTasks)
        {
            if (activeTask == null || !StringUtils.hasText(activeTask.getId())
                    || !processInstanceId.equals(activeTask.getProcessInstanceId())
                    || !completedTask.getProcessDefinitionId().equals(
                            activeTask.getProcessDefinitionId())
                    || !StringUtils.hasText(activeTask.getTaskDefinitionKey())
                    || !StringUtils.hasText(activeTask.getExecutionId())
                    || activeTask.getCreateTime() == null
                    || activeTask.getCreateTime().before(completedTask.getEndTime())
                    || !isCreatedAndUntouched(activeTask))
            {
                throw conflict();
            }
            List<org.flowable.engine.task.Attachment> taskAttachments =
                    taskService.getTaskAttachments(activeTask.getId());
            List<Comment> taskComments = taskService.getTaskComments(activeTask.getId());
            List<Task> subTasks = taskService.getSubTasks(activeTask.getId());
            if (taskAttachments == null || taskComments == null || subTasks == null)
            {
                throw dataError();
            }
            boolean containsBusinessComment = taskComments.stream()
                    .anyMatch(comment -> !isUntouchedListenerAudit(comment, activeTask));
            if (!taskAttachments.isEmpty() || containsBusinessComment || !subTasks.isEmpty())
            {
                throw conflict();
            }
        }
        return List.copyOf(activeTasks);
    }

    /**
     * 取得全部撤回后继任务的数据库行互斥，并用锁后的实时状态重新验证冻结计划。
     *
     * @param revokePlan RevokePlan，拓扑校验后冻结的后继任务和 execution 计划
     * @param completedTask HistoricTaskInstance，当前用户拟恢复的来源历史任务
     * @return 无返回值，任一任务在锁前被处理或锁后元数据漂移时抛出 HTTP 409
     */
    private void lockAndRevalidateRevokeTasks(RevokePlan revokePlan,
            HistoricTaskInstance completedTask)
    {
        List<Task> frozenTasks = new ArrayList<>(revokePlan.successorTasks());
        frozenTasks.sort(Comparator.comparing(Task::getId,
                Comparator.nullsFirst(String::compareTo)));
        for (Task frozenTask : frozenTasks)
        {
            // saveTask 使用 Flowable 公共 API 的 revision 更新；更新行锁持有到外层 Spring 事务结束。
            taskService.saveTask(frozenTask);
        }

        List<Task> lockedTasks = requireUntouchedRevokeTasks(
                revokePlan.processInstanceId(), completedTask);
        if (lockedTasks.size() != frozenTasks.size())
        {
            throw conflict();
        }
        for (int index = 0; index < lockedTasks.size(); index++)
        {
            Task frozenTask = frozenTasks.get(index);
            Task lockedTask = lockedTasks.get(index);
            if (!Objects.equals(frozenTask.getId(), lockedTask.getId())
                    || !Objects.equals(frozenTask.getProcessInstanceId(),
                            lockedTask.getProcessInstanceId())
                    || !Objects.equals(frozenTask.getProcessDefinitionId(),
                            lockedTask.getProcessDefinitionId())
                    || !Objects.equals(frozenTask.getTaskDefinitionKey(),
                            lockedTask.getTaskDefinitionKey())
                    || !Objects.equals(frozenTask.getExecutionId(), lockedTask.getExecutionId())
                    || !Objects.equals(frozenTask.getAssignee(), lockedTask.getAssignee())
                    || !Objects.equals(frozenTask.getCreateTime(), lockedTask.getCreateTime()))
            {
                throw conflict();
            }
        }
    }

    /**
     * 判断活动任务是否仍处于 Flowable 初始 CREATED 状态且没有认领、办理、挂起或委派痕迹。
     *
     * @param task Task，待判断的真实活动任务
     * @return boolean，任务完全未处理时返回 true
     */
    private boolean isCreatedAndUntouched(Task task)
    {
        return Task.CREATED.equals(task.getState())
                && !task.isSuspended()
                && task.getClaimTime() == null
                && !StringUtils.hasText(task.getClaimedBy())
                && task.getInProgressStartTime() == null
                && !StringUtils.hasText(task.getInProgressStartedBy())
                && task.getSuspendedTime() == null
                && !StringUtils.hasText(task.getSuspendedBy())
                && !StringUtils.hasText(task.getOwner())
                && task.getDelegationState() == null
                && !StringUtils.hasText(task.getParentTaskId());
    }

    /**
     * 判断 comment 是否只是受控 userTaskListener 在 create/assignment 事件写入的系统审计。
     *
     * @param comment Comment，当前活动后继任务已有的 Flowable comment
     * @param task Task，comment 必须严格关联的真实活动任务
     * @return boolean，仅固定类型、固定 schema 且字段与任务一致的创建或分配审计返回 true
     */
    private boolean isUntouchedListenerAudit(Comment comment, Task task)
    {
        if (comment == null
                || !WorkflowUserTaskAuditService.COMMENT_TYPE.equals(comment.getType())
                || !task.getId().equals(comment.getTaskId())
                || !task.getProcessInstanceId().equals(comment.getProcessInstanceId())
                || !StringUtils.hasText(comment.getFullMessage()))
        {
            return false;
        }
        try
        {
            JsonNode audit = AUDIT_MAPPER.readTree(comment.getFullMessage());
            String event = audit.path("event").asText();
            String expectedAction = switch (event)
            {
                case "create" -> "USER_TASK_CREATE";
                case "assignment" -> "USER_TASK_ASSIGNMENT";
                default -> null;
            };
            return expectedAction != null
                    && audit.path("schemaVersion").asInt(-1) == 1
                    && expectedAction.equals(audit.path("action").asText())
                    && task.getId().equals(audit.path("taskId").asText())
                    && task.getProcessInstanceId().equals(
                            audit.path("processInstanceId").asText())
                    && task.getProcessDefinitionId().equals(
                            audit.path("processDefinitionId").asText())
                    && task.getTaskDefinitionKey().equals(
                            audit.path("taskDefinitionKey").asText());
        }
        catch (Exception exception)
        {
            // 伪造、损坏或未来未知 schema 一律按人工副作用处理，不能放宽撤回边界。
            return false;
        }
    }

    /**
     * 根据已部署 BPMN 和实时 execution 树构建只包含直接串行或安全并行后继的撤回计划。
     *
     * @param context BpmnContext，已核验的部署定义、模型和主流程
     * @param completedTask HistoricTaskInstance，拟恢复的来源历史任务
     * @param completedNode UserTask，来源历史任务对应的主流程普通用户节点
     * @param activeTasks List&lt;Task&gt;，冻结后的全部未处理活动任务
     * @return RevokePlan，原子迁移需要的来源节点、后继任务和 execution 主键
     */
    private RevokePlan requireSafeRevokePlan(BpmnContext context,
            HistoricTaskInstance completedTask, UserTask completedNode,
            List<Task> activeTasks)
    {
        requirePlainSynchronousUserTask(context.process(), completedNode);
        List<SequenceFlow> sourceOutgoingFlows = completedNode.getOutgoingFlows();
        if (sourceOutgoingFlows == null || sourceOutgoingFlows.size() != 1
                || StringUtils.hasText(completedNode.getDefaultFlow()))
        {
            throw conflict();
        }

        SequenceFlow sourceFlow = sourceOutgoingFlows.get(0);
        FlowElement directTarget = requireUnconditionalTarget(
                context.process(), completedNode, sourceFlow);
        List<UserTask> expectedSuccessors;
        if (directTarget instanceof UserTask directUserTask)
        {
            requirePlainSynchronousUserTask(context.process(), directUserTask);
            requireOnlyIncomingFlow(directUserTask, sourceFlow, completedNode);
            expectedSuccessors = List.of(directUserTask);
        }
        else if (directTarget instanceof ParallelGateway parallelGateway)
        {
            expectedSuccessors = requireSafeParallelSuccessors(
                    context.process(), completedNode, sourceFlow, parallelGateway);
        }
        else
        {
            // ServiceTask、timer、CallActivity、SubProcess 和任意其他中间节点均不是可逆直接后继。
            throw conflict();
        }

        Map<String, Task> tasksByNodeKey = new LinkedHashMap<>();
        for (Task activeTask : activeTasks)
        {
            UserTask activeNode = movementPolicy.requireMainProcessUserTask(context.model(),
                    context.definition().getKey(), activeTask.getTaskDefinitionKey());
            requirePlainSynchronousUserTask(context.process(), activeNode);
            if (tasksByNodeKey.put(activeNode.getId(), activeTask) != null)
            {
                // 同节点出现多个活动任务意味着多实例或执行树漂移，禁止按普通并行分支合并。
                throw conflict();
            }
        }

        List<String> expectedNodeKeys = expectedSuccessors.stream()
                .map(UserTask::getId)
                .sorted()
                .toList();
        List<String> actualNodeKeys = tasksByNodeKey.keySet().stream().sorted().toList();
        if (!expectedNodeKeys.equals(actualNodeKeys))
        {
            throw conflict();
        }

        List<Task> orderedSuccessors = expectedNodeKeys.stream()
                .map(tasksByNodeKey::get)
                .toList();
        List<String> executionIds = requireRevokeExecutions(
                completedTask.getProcessInstanceId(), orderedSuccessors, expectedNodeKeys);
        return new RevokePlan(completedTask.getProcessInstanceId(),
                completedTask.getProcessDefinitionId(), completedTask.getId(),
                completedNode.getId(), orderedSuccessors, executionIds);
    }

    /**
     * 解析并校验一个并行网关只把来源任务直接拆分为多个普通同步用户任务。
     *
     * @param process Process，并行网关所属主流程
     * @param completedNode UserTask，撤回来源用户任务
     * @param sourceFlow SequenceFlow，来源任务到并行网关的唯一顺序流
     * @param parallelGateway ParallelGateway，待核验的直接拆分网关
     * @return List&lt;UserTask&gt;，按节点 key 稳定排序的全部直接并行后继
     */
    private List<UserTask> requireSafeParallelSuccessors(
            org.flowable.bpmn.model.Process process, UserTask completedNode,
            SequenceFlow sourceFlow, ParallelGateway parallelGateway)
    {
        if (parallelGateway.getParentContainer() != process
                || hasAsyncContinuation(parallelGateway)
                || StringUtils.hasText(parallelGateway.getDefaultFlow()))
        {
            throw conflict();
        }
        requireOnlyIncomingFlow(parallelGateway, sourceFlow, completedNode);
        List<SequenceFlow> outgoingFlows = parallelGateway.getOutgoingFlows();
        if (outgoingFlows == null || outgoingFlows.size() < 2
                || outgoingFlows.size() > MAX_ACTIVE_TASKS_FOR_REVOKE)
        {
            throw conflict();
        }

        Map<String, UserTask> successorsByKey = new LinkedHashMap<>();
        for (SequenceFlow outgoingFlow : outgoingFlows)
        {
            FlowElement target = requireUnconditionalTarget(
                    process, parallelGateway, outgoingFlow);
            if (!(target instanceof UserTask successor))
            {
                throw conflict();
            }
            requirePlainSynchronousUserTask(process, successor);
            requireOnlyIncomingFlow(successor, outgoingFlow, parallelGateway);
            if (successorsByKey.put(successor.getId(), successor) != null)
            {
                throw conflict();
            }
        }
        return successorsByKey.values().stream()
                .sorted(Comparator.comparing(UserTask::getId))
                .toList();
    }

    /**
     * 校验用户任务属于主流程、没有多实例、边界事件、补偿或 async 执行语义。
     *
     * @param process Process，撤回动作所属主流程
     * @param userTask UserTask，来源或直接后继用户任务
     * @return 无返回值，存在不可逆或复杂执行边界时抛出 HTTP 409 业务异常
     */
    private void requirePlainSynchronousUserTask(
            org.flowable.bpmn.model.Process process, UserTask userTask)
    {
        if (userTask == null || userTask.getParentContainer() != process
                || userTask.hasMultiInstanceLoopCharacteristics()
                || userTask.isForCompensation()
                || hasAsyncContinuation(userTask)
                || userTask.getBoundaryEvents() == null
                || !userTask.getBoundaryEvents().isEmpty())
        {
            throw conflict();
        }
    }

    /**
     * 判断流程节点是否声明进入或离开时的异步执行语义。
     *
     * @param flowNode FlowNode，待核验的用户任务或并行网关
     * @return boolean，任一 async 或非排他异步标识存在时返回 true
     */
    private boolean hasAsyncContinuation(FlowNode flowNode)
    {
        return flowNode.isAsynchronous()
                || flowNode.isAsynchronousLeave()
                || flowNode.isNotExclusive()
                || flowNode.isAsynchronousLeaveNotExclusive();
    }

    /**
     * 解析一条无条件顺序流的真实目标，并校验来源引用没有损坏或漂移。
     *
     * @param process Process，顺序流所属主流程
     * @param expectedSource FlowNode，契约要求的唯一来源节点
     * @param sequenceFlow SequenceFlow，待解析的已部署顺序流
     * @return FlowElement，属于主流程且具备稳定 key 的真实目标节点
     */
    private FlowElement requireUnconditionalTarget(
            org.flowable.bpmn.model.Process process, FlowNode expectedSource,
            SequenceFlow sequenceFlow)
    {
        if (sequenceFlow == null || StringUtils.hasText(sequenceFlow.getConditionExpression())
                || StringUtils.hasText(sequenceFlow.getSkipExpression()))
        {
            throw conflict();
        }
        FlowElement source = sequenceFlow.getSourceFlowElement();
        if (source == null && StringUtils.hasText(sequenceFlow.getSourceRef()))
        {
            source = process.getFlowElement(sequenceFlow.getSourceRef(), true);
        }
        FlowElement target = sequenceFlow.getTargetFlowElement();
        if (target == null && StringUtils.hasText(sequenceFlow.getTargetRef()))
        {
            target = process.getFlowElement(sequenceFlow.getTargetRef(), true);
        }
        if (source != expectedSource || target == null
                || !StringUtils.hasText(target.getId())
                || target.getParentContainer() != process)
        {
            throw conflict();
        }
        return target;
    }

    /**
     * 校验直接后继或拆分网关只有一条来自预期来源的入边，排除 join 和拓扑歧义。
     *
     * @param target FlowNode，待核验的直接目标节点
     * @param expectedFlow SequenceFlow，来源到目标的预期顺序流
     * @param expectedSource FlowNode，预期来源节点
     * @return 无返回值，入边数量或引用不一致时抛出 HTTP 409 业务异常
     */
    private void requireOnlyIncomingFlow(FlowNode target, SequenceFlow expectedFlow,
            FlowNode expectedSource)
    {
        List<SequenceFlow> incomingFlows = target.getIncomingFlows();
        if (incomingFlows == null || incomingFlows.size() != 1
                || incomingFlows.get(0) != expectedFlow
                || expectedFlow.getSourceFlowElement() != expectedSource)
        {
            throw conflict();
        }
    }

    /**
     * 核对每个后继任务都对应唯一、活动、同实例且非 CallActivity 子实例的真实 execution。
     *
     * @param processInstanceId String，撤回流程实例主键
     * @param successorTasks List&lt;Task&gt;，按节点 key 排序的全部直接后继任务
     * @param expectedNodeKeys List&lt;String&gt;，BPMN 推导的全部直接后继节点 key
     * @return List&lt;String&gt;，与后继任务顺序一致且唯一的 execution 主键
     */
    private List<String> requireRevokeExecutions(String processInstanceId,
            List<Task> successorTasks, List<String> expectedNodeKeys)
    {
        List<String> activeActivityIds = runtimeService.getActiveActivityIds(processInstanceId);
        if (activeActivityIds == null)
        {
            throw dataError();
        }
        List<String> normalizedActiveIds = activeActivityIds.stream().sorted().toList();
        if (!expectedNodeKeys.equals(normalizedActiveIds))
        {
            throw conflict();
        }

        Set<String> uniqueExecutionIds = new LinkedHashSet<>();
        for (Task successorTask : successorTasks)
        {
            Execution execution = runtimeService.createExecutionQuery()
                    .executionId(successorTask.getExecutionId())
                    .singleResult();
            if (execution == null || execution.isEnded() || execution.isSuspended()
                    || !processInstanceId.equals(execution.getProcessInstanceId())
                    || !successorTask.getTaskDefinitionKey().equals(execution.getActivityId())
                    || StringUtils.hasText(execution.getSuperExecutionId())
                    || (StringUtils.hasText(execution.getRootProcessInstanceId())
                            && !processInstanceId.equals(execution.getRootProcessInstanceId()))
                    || !uniqueExecutionIds.add(execution.getId()))
            {
                throw conflict();
            }
        }
        return List.copyOf(uniqueExecutionIds);
    }

    /**
     * 在 Flowable 状态迁移后重新核对唯一恢复任务、runtime 活动节点、历史关闭记录和撤回意见。
     *
     * @param revokePlan RevokePlan，迁移前冻结的来源、后继任务及 execution 快照
     * @param audit String，写入全部后继任务的服务端结构化撤回意见
     * @return 无返回值，写后状态出现任何漂移时抛出异常并回滚当前事务
     */
    private void verifyRevokeResult(RevokePlan revokePlan, String audit)
    {
        List<Task> restoredTasks = taskService.createTaskQuery()
                .processInstanceId(revokePlan.processInstanceId())
                .active()
                .list();
        if (restoredTasks == null || restoredTasks.size() != 1)
        {
            throw dataError();
        }
        Task restoredTask = restoredTasks.get(0);
        if (restoredTask == null || restoredTask.isSuspended()
                || !Task.CREATED.equals(restoredTask.getState())
                || !revokePlan.processInstanceId().equals(restoredTask.getProcessInstanceId())
                || !revokePlan.processDefinitionId().equals(restoredTask.getProcessDefinitionId())
                || !revokePlan.sourceNodeKey().equals(restoredTask.getTaskDefinitionKey())
                || !StringUtils.hasText(restoredTask.getId())
                || !StringUtils.hasText(restoredTask.getExecutionId())
                || revokePlan.sourceHistoricTaskId().equals(restoredTask.getId())
                || restoredTask.getCreateTime() == null)
        {
            throw dataError();
        }

        List<String> activeActivityIds = runtimeService.getActiveActivityIds(
                revokePlan.processInstanceId());
        if (activeActivityIds == null || activeActivityIds.size() != 1
                || !revokePlan.sourceNodeKey().equals(activeActivityIds.get(0)))
        {
            throw dataError();
        }

        List<HistoricTaskInstance> finishedTasks = historyService
                .createHistoricTaskInstanceQuery()
                .processInstanceId(revokePlan.processInstanceId())
                .finished()
                .list();
        if (finishedTasks == null || finishedTasks.stream().noneMatch(task -> task != null
                && revokePlan.sourceHistoricTaskId().equals(task.getId())
                && task.getEndTime() != null))
        {
            throw dataError();
        }
        for (Task successorTask : revokePlan.successorTasks())
        {
            long finishedMatches = finishedTasks.stream().filter(task -> task != null
                    && successorTask.getId().equals(task.getId())
                    && task.getEndTime() != null).count();
            if (finishedMatches != 1L)
            {
                throw dataError();
            }
        }

        List<Comment> revokeComments = taskService.getProcessInstanceComments(
                revokePlan.processInstanceId(), REVOKE_COMMENT_TYPE);
        if (revokeComments == null)
        {
            throw dataError();
        }
        for (Task successorTask : revokePlan.successorTasks())
        {
            long auditMatches = revokeComments.stream().filter(comment -> comment != null
                    && successorTask.getId().equals(comment.getTaskId())
                    && revokePlan.processInstanceId().equals(comment.getProcessInstanceId())
                    && REVOKE_COMMENT_TYPE.equals(comment.getType())
                    && audit.equals(comment.getFullMessage())).count();
            if (auditMatches != 1L)
            {
                throw dataError();
            }
        }
    }

    /**
     * 校验当前活动任务是实例唯一活动节点且拥有可迁移的真实执行主键。
     *
     * @param task Task，待驳回、退回或撤回的活动任务
     * @return 无返回值，并行、孤立或变化中的执行树抛出 HTTP 409 业务异常
     */
    private void requireSingleExecutionTree(Task task)
    {
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(task.getProcessInstanceId())
                .active()
                .list();
        if (activeTasks == null || activeTasks.size() != 1
                || !task.getId().equals(activeTasks.get(0).getId())
                || !StringUtils.hasText(task.getExecutionId()))
        {
            throw conflict();
        }
        List<String> activeActivityIds = runtimeService.getActiveActivityIds(task.getProcessInstanceId());
        if (activeActivityIds == null || activeActivityIds.size() != 1
                || !task.getTaskDefinitionKey().equals(activeActivityIds.get(0)))
        {
            throw conflict();
        }
        Execution execution = runtimeService.createExecutionQuery()
                .executionId(task.getExecutionId())
                .singleResult();
        if (execution == null || execution.isEnded() || execution.isSuspended()
                || !task.getProcessInstanceId().equals(execution.getProcessInstanceId())
                || !task.getTaskDefinitionKey().equals(execution.getActivityId())
                || StringUtils.hasText(execution.getSuperExecutionId())
                || (StringUtils.hasText(execution.getRootProcessInstanceId())
                        && !task.getProcessInstanceId().equals(
                                execution.getRootProcessInstanceId())))
        {
            throw conflict();
        }
    }

    /**
     * 校验状态迁移任务不是委派任务，防止破坏 owner 和 delegation 状态机。
     *
     * @param task Task，待驳回、退回或撤回的活动任务
     * @return 无返回值，存在 owner 或委派状态时抛出 HTTP 409 业务异常
     */
    private void requireMovableTask(Task task)
    {
        if (task.getOwner() != null || task.getDelegationState() != null)
        {
            throw conflict();
        }
    }

    /**
     * 校验当前正式用户是任务的真实办理人，不允许通过强制改 assignee 绕过授权。
     *
     * @param task Task，已确认处于活动态的任务
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前身份
     * @return 无返回值，不是当前办理人时抛出 HTTP 403 业务异常
     */
    private void requireCurrentAssignee(Task task, WorkflowCurrentIdentity actor)
    {
        if (!actor.userId().equals(task.getAssignee()))
        {
            throw forbidden();
        }
    }

    /**
     * 判断撤回来源结束后是否已有其他任务结束，任何已处理后继都会阻止撤回。
     *
     * @param completedTask HistoricTaskInstance，当前用户拟撤回的来源任务
     * @return boolean，来源结束后存在其他结束任务时返回 true
     */
    private boolean hasFinishedSuccessor(HistoricTaskInstance completedTask)
    {
        List<HistoricTaskInstance> finishedTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(completedTask.getProcessInstanceId())
                .finished()
                .list();
        if (finishedTasks == null)
        {
            throw dataError();
        }
        Date sourceEndTime = completedTask.getEndTime();
        return finishedTasks.stream().anyMatch(task -> task != null
                && !completedTask.getId().equals(task.getId())
                && task.getEndTime() != null
                && !task.getEndTime().before(sourceEndTime));
    }

    /**
     * 读取流程定义、BPMN 模型和定义 key 对应的流程，并核验关联完整性。
     *
     * @param processDefinitionId String，任务持久化的流程定义主键
     * @return BpmnContext，已核验的定义、模型和 BPMN 流程
     */
    private BpmnContext requireBpmnContext(String processDefinitionId)
    {
        if (!StringUtils.hasText(processDefinitionId))
        {
            throw dataError();
        }
        ProcessDefinition definition = repositoryService.getProcessDefinition(processDefinitionId);
        if (definition == null)
        {
            throw notFound();
        }
        if (!StringUtils.hasText(definition.getKey())
                || !StringUtils.hasText(definition.getDeploymentId()))
        {
            throw dataError();
        }
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        if (model == null)
        {
            throw dataError();
        }
        org.flowable.bpmn.model.Process process = model.getProcessById(definition.getKey());
        if (process == null)
        {
            throw dataError();
        }
        return new BpmnContext(definition, model, process);
    }

    /**
     * 从当前实例真实历史中确定首个审批节点，避免按静态网关条件猜测重新流转入口。
     *
     * @param task Task，当前审批任务
     * @param context BpmnContext，当前部署 BPMN 上下文
     * @param currentNode UserTask，当前活动且已通过安全边界校验的审批节点
     * @return String，该实例最早创建的主流程用户任务节点 key
     */
    private String requireFirstApprovalNode(Task task, BpmnContext context, UserTask currentNode)
    {
        List<HistoricTaskInstance> historicTasks = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(task.getProcessInstanceId())
                .orderByHistoricTaskInstanceStartTime().asc()
                .listPage(0, 500);
        if (historicTasks == null || historicTasks.isEmpty() || historicTasks.size() > 500)
        {
            throw conflict();
        }
        for (HistoricTaskInstance historicTask : historicTasks)
        {
            if (historicTask != null && StringUtils.hasText(historicTask.getTaskDefinitionKey())
                    && context.process().getFlowElement(
                            historicTask.getTaskDefinitionKey(), false) instanceof UserTask targetNode)
            {
                // 重新执行前只允许首个历史审批节点到当前节点存在完整安全路径，避免重建服务任务、边界事件或并行副作用。
                movementPolicy.requireSafeDirectReturnPath(context.process(), targetNode, currentNode);
                return historicTask.getTaskDefinitionKey();
            }
        }
        throw conflict();
    }

    /**
     * 冻结同一主流程实例的唯一活动审批任务及 execution，供整申请一次性退回。
     *
     * @param sourceTask Task，触发退回的当前任务
     * @return List&lt;Task&gt;，只包含当前来源任务的不可变单元素集合
     */
    private List<Task> requireReturnableActiveTasks(Task sourceTask)
    {
        List<Task> activeTasks = taskService.createTaskQuery()
                .processInstanceId(sourceTask.getProcessInstanceId()).active().list();
        if (activeTasks == null || activeTasks.size() != 1
                || activeTasks.get(0) == null
                || !sourceTask.getId().equals(activeTasks.get(0).getId())
                || !StringUtils.hasText(activeTasks.get(0).getExecutionId())
                || !sourceTask.getProcessDefinitionId().equals(
                        activeTasks.get(0).getProcessDefinitionId()))
        {
            throw conflict();
        }
        return List.copyOf(activeTasks);
    }

    /**
     * 读取状态迁移后唯一的首审批任务，拒绝引擎未合并或节点关系漂移。
     *
     * @param processInstanceId String，流程实例主键
     * @param targetKey String，首个审批节点 key
     * @return Task，重新创建的唯一活动任务
     */
    private Task requireSingleActiveTask(String processInstanceId, String targetKey)
    {
        List<Task> tasks = taskService.createTaskQuery().processInstanceId(processInstanceId)
                .active().list();
        if (tasks == null || tasks.size() != 1
                || !targetKey.equals(tasks.get(0).getTaskDefinitionKey()))
        {
            throw conflict();
        }
        return tasks.get(0);
    }

    /**
     * 固化首审批任务由 BPMN 创建出的原办理人、所有者和候选关系。
     *
     * @param task Task，状态迁移后刚创建的首审批任务
     * @return ReturnedAssignmentSnapshot，可在发起人重新提交时精确恢复的身份快照
     */
    private ReturnedAssignmentSnapshot captureAssignment(Task task)
    {
        List<IdentityLink> links = taskService.getIdentityLinksForTask(task.getId());
        if (links == null)
        {
            throw dataError();
        }
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
     * 删除任务的候选关系，使退回修改期间只有发起人能看到和操作该任务。
     *
     * @param taskId String，退回后首审批任务主键
     * @return 无返回值，候选关系异常时抛出数据错误并回滚
     */
    private void removeCandidateLinks(String taskId)
    {
        List<IdentityLink> links = taskService.getIdentityLinksForTask(taskId);
        if (links == null)
        {
            throw dataError();
        }
        for (IdentityLink link : links)
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
     * 恢复退回前由 BPMN 首审批节点生成的正式办理配置。
     *
     * @param taskId String，发起人修改任务主键
     * @param assignment ReturnedAssignmentSnapshot，退回时固化的原办理配置
     * @return 无返回值，恢复结果由后续写后校验确认
     */
    private void restoreAssignment(String taskId, ReturnedAssignmentSnapshot assignment)
    {
        taskService.setOwner(taskId, assignment.owner());
        taskService.setAssignee(taskId, assignment.assignee());
        assignment.candidateUserIds().forEach(userId ->
                taskService.addCandidateUser(taskId, userId));
        assignment.candidateGroupIds().forEach(groupId ->
                taskService.addCandidateGroup(taskId, groupId));
    }

    /**
     * 查询该实例实际执行的开始节点对应部署表单快照。
     *
     * @param task Task，退回修改任务
     * @return WfDeployForm，原部署开始表单的不可变快照
     */
    private WfDeployForm requireStartFormSnapshot(Task task)
    {
        var starts = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(task.getProcessInstanceId()).activityType("startEvent")
                .orderByHistoricActivityInstanceStartTime().asc().listPage(0, 2);
        if (starts == null || starts.size() != 1
                || !StringUtils.hasText(starts.get(0).getActivityId()))
        {
            throw dataError();
        }
        BpmnContext context = requireBpmnContext(task.getProcessDefinitionId());
        if (!(context.process().getFlowElement(starts.get(0).getActivityId(), false)
                instanceof StartEvent))
        {
            throw dataError();
        }
        List<WfDeployForm> snapshots = deployFormMapper.selectByDeploymentId(
                context.definition().getDeploymentId());
        if (snapshots == null)
        {
            throw dataError();
        }
        List<WfDeployForm> matches = snapshots.stream().filter(snapshot -> snapshot != null
                && starts.get(0).getActivityId().equals(snapshot.getNodeKey())).toList();
        if (matches.size() != 1
                || !WorkflowFormSourceType.isConsistent(matches.get(0).getSourceType(),
                        matches.get(0).getFormId())
                || !StringUtils.hasText(matches.get(0).getContent()))
        {
            throw conflict();
        }
        return matches.get(0);
    }

    /**
     * 使用最新提交完整覆盖原开始表单变量，同时保留流程状态等服务端内部变量。
     *
     * @param instance ProcessInstance，当前退回修改流程实例
     * @param startForm WfDeployForm，原部署开始表单不可变快照
     * @param projected Map&lt;String, Object&gt;，校验及附件投影后的完整新表单值
     * @return 无返回值，旧表单中已删除的字段被移除，新字段和值随后同事务写入
     */
    private void replaceStartFormVariables(ProcessInstance instance, WfDeployForm startForm,
            Map<String, Object> projected)
    {
        Object rawSnapshot = runtimeService.getVariable(instance.getId(),
                WorkflowFormSubmissionSnapshotCodec.VARIABLE_NAME);
        if (!(rawSnapshot instanceof String encodedSnapshot)
                || !StringUtils.hasText(encodedSnapshot))
        {
            throw dataError();
        }
        WorkflowFormSubmissionSnapshotCodec.SubmissionSnapshot previous =
                WorkflowFormSubmissionSnapshotCodec.decode(encodedSnapshot);
        if (previous.kind() != WorkflowFormSubmissionSnapshotCodec.SnapshotKind.START
                || !Objects.equals(instance.getDeploymentId(), previous.deploymentId())
                || !Objects.equals(startForm.getSourceType(), previous.sourceType())
                || !Objects.equals(startForm.getFormId(), previous.formId())
                || !Objects.equals(startForm.getFormKey(), previous.formKey())
                || !Objects.equals(startForm.getNodeKey(), previous.nodeKey()))
        {
            throw dataError();
        }

        // 只删除上一次开始表单快照确认拥有的字段，不能触碰流程状态、办理配置等内部变量。
        LinkedHashSet<String> removedFields = new LinkedHashSet<>(previous.values().keySet());
        removedFields.removeAll(projected.keySet());
        if (!removedFields.isEmpty())
        {
            runtimeService.removeVariables(instance.getId(), removedFields);
        }
        if (!projected.isEmpty())
        {
            runtimeService.setVariables(instance.getId(), projected);
        }
    }

    /**
     * 序列化退回首审批办理配置，供同一任务局部变量持久化。
     *
     * @param assignment ReturnedAssignmentSnapshot，原办理配置
     * @return String，受控 JSON 正文
     */
    private String encodeReturnedAssignment(ReturnedAssignmentSnapshot assignment)
    {
        try
        {
            return AUDIT_MAPPER.writeValueAsString(assignment);
        }
        catch (RuntimeException exception)
        {
            throw dataError();
        }
    }

    /**
     * 解码并校验退回首审批办理配置。
     *
     * @param encoded String，任务局部变量中的受控 JSON
     * @return ReturnedAssignmentSnapshot，结构完整的原办理配置
     */
    private ReturnedAssignmentSnapshot decodeReturnedAssignment(String encoded)
    {
        try
        {
            ReturnedAssignmentSnapshot snapshot = AUDIT_MAPPER.readValue(
                    encoded, ReturnedAssignmentSnapshot.class);
            if (snapshot == null || (!StringUtils.hasText(snapshot.assignee())
                    && snapshot.candidateUserIds().isEmpty()
                    && snapshot.candidateGroupIds().isEmpty()))
            {
                throw conflict();
            }
            return snapshot;
        }
        catch (ServiceException exception)
        {
            throw exception;
        }
        catch (RuntimeException exception)
        {
            throw dataError();
        }
    }

    /**
     * 校验退回后任务、独占办理人和流程状态均已真实写入。
     *
     * @param taskId String，退回后任务主键
     * @param startUserId String，流程发起人主键
     * @return 无返回值，不一致时抛出冲突并回滚
     */
    private void verifyReturnedApplication(String taskId, String startUserId)
    {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null || !startUserId.equals(task.getAssignee())
                || !startUserId.equals(taskService.getVariableLocal(
                        taskId, RETURN_APPLICANT_VARIABLE))
                || !RETURNED_STATUS.equals(runtimeService.getVariable(
                        task.getProcessInstanceId(),
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)))
        {
            throw conflict();
        }
    }

    /**
     * 校验重新提交后首审批办理配置及运行状态已经恢复。
     *
     * @param taskId String，重新开放的首审批任务主键
     * @param assignment ReturnedAssignmentSnapshot，预期原办理配置
     * @return 无返回值，不一致时抛出冲突并回滚
     */
    private void verifyResubmittedApplication(String taskId,
            ReturnedAssignmentSnapshot assignment)
    {
        Task task = taskService.createTaskQuery().taskId(taskId).active().singleResult();
        if (task == null || !Objects.equals(assignment.assignee(), task.getAssignee())
                || !WorkflowProcessStartService.RUNNING_STATUS.equals(runtimeService.getVariable(
                        task.getProcessInstanceId(),
                        WorkflowProcessStartService.PROCESS_STATUS_VARIABLE)))
        {
            throw conflict();
        }
    }

    /**
     * 把服务端固定动作、当前身份和受控业务意见序列化为结构化审计 JSON。
     *
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内重新解析的当前用户主键
     * @param opinion String，已通过长度门禁的业务意见
     * @param targetNodeKey String，可为空的迁移目标 BPMN 节点 key
     * @param sourceTaskId String，可为空的撤回来源历史任务主键
     * @return String，可同时写入 comment 和流程删除原因的 JSON
     */
    private String buildAudit(String action, String actorUserId, String opinion,
            String targetNodeKey, String sourceTaskId)
    {
        return buildAuditPayload(action, actorUserId, opinion,
                targetNodeKey, sourceTaskId).toString();
    }

    /**
     * 构造可继续补充动作专属字段的结构化审计对象。
     *
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内重新解析的当前用户主键
     * @param opinion String，已通过长度门禁的业务意见
     * @param targetNodeKey String，可为空的迁移目标 BPMN 节点 key
     * @param sourceTaskId String，可为空的撤回来源历史任务主键
     * @return ObjectNode，包含全部通用审计字段的可变服务端对象
     */
    private ObjectNode buildAuditPayload(String action, String actorUserId, String opinion,
            String targetNodeKey, String sourceTaskId)
    {
        ObjectNode audit = AUDIT_MAPPER.createObjectNode();
        audit.put("action", action);
        audit.put("actorUserId", actorUserId);
        audit.put("opinion", opinion);
        if (targetNodeKey != null)
        {
            audit.put("targetNodeKey", targetNodeKey);
        }
        if (sourceTaskId != null)
        {
            audit.put("sourceTaskId", sourceTaskId);
        }
        return audit;
    }

    /**
     * 为一个真实活动任务写入服务端生成的结构化审计 comment。
     *
     * @param task Task，动作前重新读取的真实活动任务
     * @param commentType String，兼容旧系统的 comment 类型
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内重新解析的当前用户主键
     * @param opinion String，已通过长度门禁的业务意见
     * @param targetNodeKey String，可为空的迁移目标节点 key
     * @param sourceTaskId String，可为空的撤回来源历史任务主键
     * @return 无返回值，写入失败时由外层事务回滚状态变更
     */
    private void addAuditComment(Task task, String commentType, String action,
            String actorUserId, String opinion, String targetNodeKey, String sourceTaskId)
    {
        taskService.addComment(task.getId(), task.getProcessInstanceId(), commentType,
                buildAudit(action, actorUserId, opinion, targetNodeKey, sourceTaskId));
    }

    /**
     * 写入任务完成审计，并在动态多实例场景记录本次占用的前后 revision。
     *
     * @param task Task，动作前重新读取的真实活动任务
     * @param actorUserId String，事务内重新解析的当前用户主键
     * @param opinion String，已通过长度门禁的业务意见
     * @param completionRevision CompletionRevision，普通任务为空计划，动态任务包含活动及版本区间
     * @return 无返回值，comment 写入失败时 revision 与任务完成一并回滚
     */
    private void addCompletionAuditComment(Task task, String actorUserId, String opinion,
            WorkflowMultiInstanceService.CompletionRevision completionRevision)
    {
        ObjectNode audit = buildAuditPayload("COMPLETE", actorUserId, opinion, null, null);
        if (completionRevision.applied())
        {
            audit.put("multiInstanceActivityId", completionRevision.activityId());
            audit.put("beforeRevision", completionRevision.beforeRevision());
            audit.put("afterRevision", completionRevision.afterRevision());
        }
        taskService.addComment(task.getId(), task.getProcessInstanceId(),
                COMPLETE_COMMENT_TYPE, audit.toString());
    }

    /**
     * 将状态预检后发生的对象消失统一翻译为并发冲突，避免重复提交误报 404。
     *
     * @param action Runnable，comment 与引擎状态变更组成的原子动作
     * @return 无返回值，Flowable 对象并发消失时抛出 HTTP 409 业务异常
     */
    private void executeConcurrentSensitive(Runnable action)
    {
        try
        {
            action.run();
        }
        catch (FlowableObjectNotFoundException exception)
        {
            ServiceException failure = conflict();
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 判断当前身份是否为若依受控超级管理员。
     *
     * @param actor WorkflowCurrentIdentity，事务内重新核验的当前身份
     * @return boolean，用户主键命中若依超级管理员规则时返回 true
     */
    private boolean isAdministrator(WorkflowCurrentIdentity actor)
    {
        try
        {
            return SecurityUtils.isAdmin(Long.valueOf(actor.userId()));
        }
        catch (NumberFormatException exception)
        {
            return false;
        }
    }

    /**
     * 校验客户端 ID 文本并规范化首尾空白。
     *
     * @param value String，流程、任务或节点主键
     * @return String，长度受控的非空主键
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
     * 校验客户端业务意见并规范化首尾空白。
     *
     * @param value String，取消、撤回或审批业务意见
     * @return String，长度受控的非空意见
     */
    private String requireOpinion(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw invalidArgument();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_OPINION_LENGTH)
        {
            throw invalidArgument();
        }
        return normalized;
    }

    /**
     * 校验客户端实例主键与历史任务所属实例完全一致。
     *
     * @param expected String，客户端声明并已规范化的实例主键
     * @param actual String，服务端历史任务中的真实实例主键
     * @return 无返回值，不一致时抛出 HTTP 400 参数异常
     */
    private void requireSame(String expected, String actual)
    {
        if (!expected.equals(actual))
        {
            throw invalidArgument();
        }
    }

    /**
     * 创建稳定的请求参数异常。
     *
     * @return ServiceException，HTTP 400 业务异常
     */
    private ServiceException invalidArgument()
    {
        return new ServiceException("工作流请求参数不合法", HttpStatus.BAD_REQUEST);
    }

    /**
     * 创建稳定的对象不存在异常。
     *
     * @return ServiceException，HTTP 404 业务异常
     */
    private ServiceException notFound()
    {
        return new ServiceException("工作流对象不存在或已被删除", HttpStatus.NOT_FOUND);
    }

    /**
     * 创建稳定的状态或并发冲突异常。
     *
     * @return ServiceException，HTTP 409 业务异常
     */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /**
     * 创建稳定的对象级权限拒绝异常。
     *
     * @return ServiceException，HTTP 403 业务异常
     */
    private ServiceException forbidden()
    {
        return new ServiceException("无权执行当前工作流操作", HttpStatus.FORBIDDEN);
    }

    /**
     * 创建稳定的引擎与业务关联数据异常。
     *
     * @return ServiceException，HTTP 500 业务异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /**
     * 当前任务定义、BPMN 模型和同 key 主流程的不可变上下文。
     *
     * @param definition ProcessDefinition，任务所属真实流程定义
     * @param model BpmnModel，定义对应的已部署 BPMN 模型
     * @param process Process，定义 key 对应的 BPMN 流程
     */
    private record BpmnContext(ProcessDefinition definition, BpmnModel model,
            org.flowable.bpmn.model.Process process)
    {
    }

    /**
     * 退回时固化的首审批任务办理配置。
     *
     * @param assignee String，原直接办理人，候选任务时为空
     * @param owner String，原任务所有者，允许为空
     * @param candidateUserIds List&lt;String&gt;，原候选用户主键
     * @param candidateGroupIds List&lt;String&gt;，原候选组编码
     */
    private record ReturnedAssignmentSnapshot(String assignee, String owner,
            List<String> candidateUserIds, List<String> candidateGroupIds)
    {
        /**
         * 创建不可变办理配置并拒绝空集合引用。
         *
         * @param assignee String，原直接办理人
         * @param owner String，原所有者
         * @param candidateUserIds List&lt;String&gt;，原候选用户
         * @param candidateGroupIds List&lt;String&gt;，原候选组
         * @return 无返回值，候选集合复制为不可修改集合
         */
        private ReturnedAssignmentSnapshot
        {
            candidateUserIds = List.copyOf(Objects.requireNonNull(candidateUserIds));
            candidateGroupIds = List.copyOf(Objects.requireNonNull(candidateGroupIds));
        }
    }

    /**
     * 普通串行退回命令执行前冻结的单一活动任务与 execution。
     *
     * @param activeTasks List&lt;Task&gt;，普通任务单元素集合
     * @param executionIds List&lt;String&gt;，与活动任务对应的唯一 execution 主键
     */
    private record ReturnExecutionPlan(List<Task> activeTasks, List<String> executionIds)
    {
        /**
         * 创建不可变单任务退回计划并拒绝数量漂移。
         *
         * @param activeTasks List&lt;Task&gt;，已经完成运行态校验的来源任务
         * @param executionIds List&lt;String&gt;，已经完成唯一性校验的来源 execution 主键
         * @return 无返回值，构造后两项集合均不可修改
         */
        private ReturnExecutionPlan
        {
            activeTasks = List.copyOf(activeTasks);
            executionIds = List.copyOf(executionIds);
            if (activeTasks.size() != 1 || executionIds.size() != 1)
            {
                throw new IllegalArgumentException("退回执行计划不完整");
            }
        }
    }

    /**
     * 撤回命令执行前冻结的安全迁移计划。
     *
     * @param processInstanceId String，真实流程实例主键
     * @param processDefinitionId String，来源与全部后继共享的流程定义主键
     * @param sourceHistoricTaskId String，当前用户真实完成的来源历史任务主键
     * @param sourceNodeKey String，需要原子恢复的来源 BPMN 节点 key
     * @param successorTasks List&lt;Task&gt;，全部未处理直接后继任务
     * @param executionIds List&lt;String&gt;，与后继任务一一对应的活动 execution 主键
     */
    private record RevokePlan(String processInstanceId, String processDefinitionId,
            String sourceHistoricTaskId, String sourceNodeKey,
            List<Task> successorTasks, List<String> executionIds)
    {
    }

    /**
     * 撤回命令和只读能力字段共享的不可变准备结果。
     *
     * @param completedTask HistoricTaskInstance，已核验真实完成人和结束状态的来源任务
     * @param revokePlan RevokePlan，已核验实时后继与 BPMN 拓扑的迁移计划
     */
    private record RevokePreparation(HistoricTaskInstance completedTask,
            RevokePlan revokePlan)
    {
    }

    /**
     * 经过部署表单 schema 校验的任务完成变量。
     *
     * @param values Map&lt;String, Object&gt;，规范化且不可修改的任务变量
     * @param localScope boolean，是否按任务局部变量持久化
     * @param attachmentIdsByField Map&lt;String, List&lt;String&gt;&gt;，上传字段附件 UUID 引用
     * @param deploymentId String，任务定义所属部署主键
     * @param formSnapshot WfDeployForm，任务节点部署表单快照；无表单节点为空
     */
    private record CompletionVariables(Map<String, Object> values, boolean localScope,
            Map<String, List<String>> attachmentIdsByField, String deploymentId,
            WfDeployForm formSnapshot)
    {
        /**
         * 创建完成变量计划并复制顶层映射。
         *
         * @param values Map&lt;String, Object&gt;，已经通过 schema 校验的变量
         * @param localScope boolean，BPMN 当前节点的变量作用域设置
         * @param attachmentIdsByField Map&lt;String, List&lt;String&gt;&gt;，按字段分组的附件引用
         * @param deploymentId String，任务定义所属部署主键
         * @param formSnapshot WfDeployForm，任务节点部署表单快照；无表单节点为空
         * @return 无返回值，构造后变量与两层附件引用均不可修改
         */
        private CompletionVariables
        {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            LinkedHashMap<String, List<String>> copiedReferences = new LinkedHashMap<>();
            attachmentIdsByField.forEach((fieldName, attachmentIds) ->
                    copiedReferences.put(fieldName, List.copyOf(attachmentIds)));
            attachmentIdsByField = Collections.unmodifiableMap(copiedReferences);
        }
    }
}
