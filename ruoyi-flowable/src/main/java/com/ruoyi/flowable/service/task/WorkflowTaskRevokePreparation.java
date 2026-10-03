package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;

/** 撤回资格和安全迁移计划；已办列表与写命令共用同一只读准备规则。 */
@Service
public class WorkflowTaskRevokePreparation
{
    private static final int MAX_ACTIVE_TASKS_FOR_REVOKE = 100;
    private static final Set<Integer> REVOKE_INELIGIBLE_STATUS_CODES = Set.of(
            HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);
    private static final ObjectMapper AUDIT_MAPPER = JsonMapper.shared();

    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRequestValidator requestValidator;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final WorkflowTaskBpmnReader bpmnReader;
    private final WorkflowTaskMovementPolicy movementPolicy;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public WorkflowTaskRevokePreparation(WorkflowEngineOperations engineOperations,
            WorkflowTaskRequestValidator requestValidator, WorkflowTaskRuntimeReader runtimeReader,
            WorkflowTaskBpmnReader bpmnReader, WorkflowTaskMovementPolicy movementPolicy,
            RuntimeService runtimeService, TaskService taskService)
    {
        this.engineOperations = engineOperations;
        this.requestValidator = requestValidator;
        this.runtimeReader = runtimeReader;
        this.bpmnReader = bpmnReader;
        this.movementPolicy = movementPolicy;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    /** 已办页复用同次读取的历史任务和身份；业务不满足在事务内投影为 false。 */
    public boolean isProcessRevocable(HistoricTaskInstance completedTask,
            WorkflowCurrentIdentity actor)
    {
        return engineOperations.readWithServiceExceptionHandler(() ->
        {
            String processInstanceId = requestValidator.requireId(completedTask.getProcessInstanceId());
            requestValidator.requireId(completedTask.getId());
            prepareRevoke(processInstanceId, completedTask, actor);
            return true;
        }, exception ->
        {
            if (REVOKE_INELIGIBLE_STATUS_CODES.contains(exception.getCode())) return false;
            throw exception;
        });
    }

    /**
     * 在同一事务快照中准备撤回来源和安全迁移计划。
     *
     * @param processInstanceId String，已规范化实例主键
     * @param historicTaskId String，已规范化历史任务主键
     * @param actor WorkflowCurrentIdentity，事务内正式身份
     * @return RevokePreparation，已核验来源任务和迁移计划
     */
    RevokePreparation requireRevokePreparation(String processInstanceId,
            String historicTaskId, WorkflowCurrentIdentity actor)
    {
        HistoricTaskInstance completedTask = runtimeReader.requireCompletedTask(
                historicTaskId);
        return prepareRevoke(processInstanceId, completedTask, actor);
    }

    private RevokePreparation prepareRevoke(String processInstanceId,
            HistoricTaskInstance completedTask, WorkflowCurrentIdentity actor)
    {
        requestValidator.requireSame(processInstanceId,
                completedTask.getProcessInstanceId());
        requireTaskCompletedBy(completedTask, actor);
        if (StringUtils.hasText(completedTask.getDeleteReason())
                || completedTask.getEndTime() == null)
        {
            throw conflict();
        }
        runtimeReader.requireActiveProcessInstance(processInstanceId);
        List<Task> activeTasks = requireUntouchedRevokeTasks(
                processInstanceId, completedTask);
        if (runtimeReader.hasFinishedSuccessor(completedTask))
        {
            throw conflict();
        }
        WorkflowTaskBpmnSnapshot context = bpmnReader.require(
                completedTask.getProcessDefinitionId());
        UserTask completedNode = movementPolicy.requireMainProcessUserTask(
                context.model(), context.definition().getKey(),
                completedTask.getTaskDefinitionKey());
        return new RevokePreparation(completedTask,
                requireSafeRevokePlan(context, completedTask,
                        completedNode, activeTasks));
    }

    /**
     * 校验撤回来源由当前用户通过正式 completedBy 字段真实完成。
     *
     * @param completedTask HistoricTaskInstance，已完成来源任务
     * @param actor WorkflowCurrentIdentity，事务内正式身份
     * @return 无返回值，对象不属于当前用户时抛出 HTTP 403
     */
    private void requireTaskCompletedBy(HistoricTaskInstance completedTask,
            WorkflowCurrentIdentity actor)
    {
        String completedBy = completedTask.getCompletedBy();
        if (!StringUtils.hasText(completedBy))
        {
            throw dataError();
        }
        if (!actor.userId().equals(completedBy))
        {
            throw forbidden();
        }
    }

    /**
     * 冻结全部活动后继并确认没有认领、办理、委派或业务副作用。
     *
     * @param processInstanceId String，活动流程实例主键
     * @param completedTask HistoricTaskInstance，撤回来源任务
     * @return List&lt;Task&gt;，按任务主键稳定排序的未处理后继
     */
    List<Task> requireUntouchedRevokeTasks(String processInstanceId,
            HistoricTaskInstance completedTask)
    {
        List<Task> queriedTasks = taskService.createTaskQuery()
                .processInstanceId(processInstanceId).active().list();
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
            List<org.flowable.engine.task.Attachment> attachments =
                    taskService.getTaskAttachments(activeTask.getId());
            List<Comment> comments = taskService.getTaskComments(activeTask.getId());
            List<Task> subTasks = taskService.getSubTasks(activeTask.getId());
            if (attachments == null || comments == null || subTasks == null)
            {
                throw dataError();
            }
            boolean containsBusinessComment = comments.stream()
                    .anyMatch(comment -> !isUntouchedListenerAudit(comment, activeTask));
            if (!attachments.isEmpty() || containsBusinessComment || !subTasks.isEmpty())
            {
                throw conflict();
            }
        }
        return List.copyOf(activeTasks);
    }

    /**
     * 判断活动任务仍处于 Flowable 初始 CREATED 且无处理痕迹。
     *
     * @param task Task，待判断真实活动任务
     * @return boolean，任务完全未处理时返回 true
     */
    private boolean isCreatedAndUntouched(Task task)
    {
        return Task.CREATED.equals(task.getState())
                && !task.isSuspended() && task.getClaimTime() == null
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
     * 判断 comment 是否仅为生产监听器的固定 create/assignment 审计。
     *
     * @param comment Comment，活动后继已有 comment
     * @param task Task，comment 必须关联的真实任务
     * @return boolean，仅固定类型、schema 和对象关系完全一致时返回 true
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
            String expectedAction = switch (audit.path("event").asText())
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
            return false;
        }
    }

    /**
     * 根据部署 BPMN 和实时 execution 构建直接串行或安全并行撤回计划。
     *
     * @param context WorkflowTaskBpmnSnapshot，部署 BPMN 事实
     * @param completedTask HistoricTaskInstance，撤回来源任务
     * @param completedNode UserTask，来源主流程用户节点
     * @param activeTasks List&lt;Task&gt;，全部未处理活动任务
     * @return RevokePlan，原子迁移所需不可变计划
     */
    private RevokePlan requireSafeRevokePlan(WorkflowTaskBpmnSnapshot context,
            HistoricTaskInstance completedTask, UserTask completedNode,
            List<Task> activeTasks)
    {
        WorkflowTaskMovementPolicy.RevokeMovementPlan movementPlan =
                movementPolicy.requireSafeRevokeMovement(
                        context.process(), completedNode);
        Map<String, Task> tasksByNodeKey = new LinkedHashMap<>();
        for (Task activeTask : activeTasks)
        {
            UserTask activeNode = movementPolicy.requireMainProcessUserTask(
                    context.model(), context.definition().getKey(),
                    activeTask.getTaskDefinitionKey());
            if (tasksByNodeKey.put(activeNode.getId(), activeTask) != null)
            {
                throw conflict();
            }
        }
        List<String> expectedNodeKeys = movementPlan.successorNodeKeys();
        List<String> actualNodeKeys = tasksByNodeKey.keySet().stream().sorted().toList();
        if (!expectedNodeKeys.equals(actualNodeKeys))
        {
            throw conflict();
        }
        List<Task> orderedSuccessors = expectedNodeKeys.stream()
                .map(tasksByNodeKey::get).toList();
        List<String> executionIds = requireRevokeExecutions(
                completedTask.getProcessInstanceId(), orderedSuccessors,
                expectedNodeKeys);
        return new RevokePlan(completedTask.getProcessInstanceId(),
                movementPlan.sourceNodeKey(), orderedSuccessors, executionIds);
    }

    /**
     * 核对每个后继任务对应唯一、活动、同实例且非子实例 execution。
     *
     * @param processInstanceId String，撤回实例主键
     * @param successorTasks List&lt;Task&gt;，按节点 key 排序的后继任务
     * @param expectedNodeKeys List&lt;String&gt;，BPMN 推导的后继节点 key
     * @return List&lt;String&gt;，与后继顺序一致且唯一的 execution 主键
     */
    private List<String> requireRevokeExecutions(String processInstanceId,
            List<Task> successorTasks, List<String> expectedNodeKeys)
    {
        List<String> activeActivityIds = runtimeService.getActiveActivityIds(
                processInstanceId);
        if (activeActivityIds == null)
        {
            throw dataError();
        }
        if (!expectedNodeKeys.equals(activeActivityIds.stream().sorted().toList()))
        {
            throw conflict();
        }
        Set<String> executionIds = new LinkedHashSet<>();
        for (Task successor : successorTasks)
        {
            Execution execution = runtimeService.createExecutionQuery()
                    .executionId(successor.getExecutionId()).singleResult();
            if (execution == null || execution.isEnded() || execution.isSuspended()
                    || !processInstanceId.equals(execution.getProcessInstanceId())
                    || !successor.getTaskDefinitionKey().equals(execution.getActivityId())
                    || StringUtils.hasText(execution.getSuperExecutionId())
                    || (StringUtils.hasText(execution.getRootProcessInstanceId())
                            && !processInstanceId.equals(
                                    execution.getRootProcessInstanceId()))
                    || !executionIds.add(execution.getId()))
            {
                throw conflict();
            }
        }
        return List.copyOf(executionIds);
    }

    /**
     * 创建稳定状态冲突错误。
     *
     * @return ServiceException，既有 HTTP 409 错误
     */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

    /**
     * 创建稳定权限错误。
     *
     * @return ServiceException，既有 HTTP 403 错误
     */
    private ServiceException forbidden()
    {
        return new ServiceException("无权执行当前工作流操作", HttpStatus.FORBIDDEN);
    }

    /**
     * 创建稳定关联数据错误。
     *
     * @return ServiceException，既有 HTTP 500 错误
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /** 撤回命令执行前冻结的安全迁移计划。 */
    record RevokePlan(String processInstanceId, String sourceNodeKey,
            List<Task> successorTasks, List<String> executionIds)
    {
    }

    /** 撤回命令和只读能力共享的准备结果。 */
    record RevokePreparation(HistoricTaskInstance completedTask,
            RevokePlan revokePlan)
    {
    }
}
