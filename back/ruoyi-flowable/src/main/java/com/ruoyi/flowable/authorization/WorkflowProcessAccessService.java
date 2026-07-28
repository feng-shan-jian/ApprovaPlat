package com.ruoyi.flowable.authorization;

import java.time.Instant;
import java.util.Date;
import java.util.Set;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfCopyMapper;
import com.ruoyi.framework.web.service.PermissionService;

/**
 * 工作流实例与任务的对象级读取授权服务。
 */
@Service
public class WorkflowProcessAccessService
{
    /** 主数据或引擎对象缺失时使用的稳定 404 提示。 */
    private static final String NOT_FOUND_MESSAGE = "工作流对象不存在或已被删除";

    /** 登录用户与目标流程没有业务关系时使用的稳定 403 提示。 */
    private static final String FORBIDDEN_MESSAGE = "无权执行当前工作流操作";

    /** 请求参数为空时使用的稳定 400 提示。 */
    private static final String INVALID_ARGUMENT_MESSAGE = "工作流请求参数不合法";

    /** 流程对象内部关联缺失时使用的稳定 500 提示。 */
    private static final String INVALID_RELATION_MESSAGE = "工作流对象关联数据异常";

    /** 菜单初始化脚本仅授予流程管理员的跨实例状态管理权限。 */
    private static final String WORKFLOW_ADMIN_PERMISSION = "workflow:process:state";

    private final HistoryService historyService;

    private final RuntimeService runtimeService;

    private final TaskService taskService;

    private final WorkflowEngineOperations engineOperations;

    private final WorkflowIdentityResolver identityResolver;

    private final WfCopyMapper copyMapper;

    private final PermissionService permissionService;

    /**
     * 创建工作流对象级授权服务。
     *
     * @param historyService HistoryService，Flowable 历史查询公共服务
     * @param runtimeService RuntimeService，Flowable 实例实时状态公共服务
     * @param taskService TaskService，Flowable 运行时任务公共服务
     * @param engineOperations WorkflowEngineOperations，统一只读事务和异常翻译边界
     * @param identityResolver WorkflowIdentityResolver，当前有效用户及候选组解析器
     * @param copyMapper WfCopyMapper，正式抄送记录查询 Mapper
     * @param permissionService PermissionService，Token 权限与当前正式主数据的统一复核服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowProcessAccessService(HistoryService historyService,
            RuntimeService runtimeService, TaskService taskService,
            WorkflowEngineOperations engineOperations, WorkflowIdentityResolver identityResolver,
            WfCopyMapper copyMapper, PermissionService permissionService)
    {
        this.historyService = historyService;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.engineOperations = engineOperations;
        this.identityResolver = identityResolver;
        this.copyMapper = copyMapper;
        this.permissionService = permissionService;
    }

    /**
     * 校验当前用户可读取指定流程实例，并返回服务端重新查询的不可变实例快照。
     *
     * @param processInstanceId String，待读取的流程实例 ID
     * @return WorkflowProcessAccessSnapshot，授权通过后的真实实例快照
     */
    @Transactional(readOnly = true)
    public WorkflowProcessAccessSnapshot requireReadableInstance(String processInstanceId)
    {
        requireText(processInstanceId);
        WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
        return engineOperations.read(() ->
        {
            HistoricProcessInstance processInstance = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            if (processInstance == null)
            {
                throw notFound();
            }
            if (!canReadInstance(processInstance, actor))
            {
                throw forbidden();
            }
            ProcessInstance runtimeInstance = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            return toProcessSnapshot(processInstance, runtimeInstance);
        });
    }

    /**
     * 校验当前用户可读取指定任务，并返回服务端重新查询的不可变任务快照。
     * 未结束运行时任务（包含挂起）仅允许流程管理员、当前办理人或未认领任务候选身份读取；
     * 历史任务沿用实例参与授权。
     *
     * @param taskId String，待读取的活动或历史任务 ID
     * @return WorkflowTaskAccessSnapshot，授权通过后的真实任务快照
     */
    @Transactional(readOnly = true)
    public WorkflowTaskAccessSnapshot requireReadableTask(String taskId)
    {
        requireText(taskId);
        WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
        return engineOperations.read(() ->
        {
            Task activeTask = taskService.createTaskQuery().taskId(taskId).singleResult();
            if (activeTask != null)
            {
                requireHistoricInstance(activeTask.getProcessInstanceId());
                if (!canReadActiveTask(activeTask, actor))
                {
                    throw forbidden();
                }
                return toTaskSnapshot(activeTask);
            }

            HistoricTaskInstance historicTask = historyService.createHistoricTaskInstanceQuery()
                    .taskId(taskId)
                    .singleResult();
            if (historicTask == null)
            {
                throw notFound();
            }
            HistoricProcessInstance processInstance = requireHistoricInstance(historicTask.getProcessInstanceId());
            if (!canReadInstance(processInstance, actor))
            {
                throw forbidden();
            }
            return toTaskSnapshot(historicTask);
        });
    }

    /**
     * 根据服务端历史记录判断当前身份是否与流程实例存在可验证的业务关系。
     *
     * @param processInstance HistoricProcessInstance，目标流程的真实历史记录
     * @param actor WorkflowCurrentIdentity，事务内核验后的当前身份
     * @return boolean，流程管理员、发起人、参与人、当前办理/候选人或抄送收件人命中时返回 true
     */
    private boolean canReadInstance(HistoricProcessInstance processInstance, WorkflowCurrentIdentity actor)
    {
        String processInstanceId = processInstance.getId();
        if (hasWorkflowAdministrativeRead(actor))
        {
            // 超级管理员或持有流程状态管理权限的流程管理员可执行跨实例受控审计与运维查询。
            return true;
        }
        if (actor.userId().equals(processInstance.getStartUserId()))
        {
            return true;
        }

        long involvedCount = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .involvedUser(actor.userId())
                .count();
        if (involvedCount > 0)
        {
            return true;
        }

        long assignedTaskCount = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskAssignee(actor.userId())
                .count();
        if (assignedTaskCount > 0 || hasCandidateTask(processInstanceId, actor))
        {
            return true;
        }

        long completedByTaskCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .taskCompletedBy(actor.userId())
                .count();
        if (completedByTaskCount > 0)
        {
            // completedBy 记录真实完成人，确保任务转办或委派后实际办理者仍可查看所属流程。
            return true;
        }

        long assignedHistoricTaskCount = historyService.createHistoricTaskInstanceQuery()
                .processInstanceId(processInstanceId)
                .finished()
                .taskAssignee(actor.userId())
                .count();
        if (assignedHistoricTaskCount > 0)
        {
            // 保留静态指派历史兼容，原办理人即使不是最终 completedBy 也属于可验证参与者。
            return true;
        }

        // 抄送权限只认正式业务表中的当前有效记录，客户端 userId 不参与查询条件。
        return copyMapper.countActiveByInstanceAndUser(processInstanceId, Long.parseLong(actor.userId())) > 0;
    }

    /**
     * 判断当前身份是否可读取活动任务尚未提交的表单 schema 和变量。
     *
     * @param task Task，服务端按任务主键重新查询的真实活动任务
     * @param actor WorkflowCurrentIdentity，事务内解析的当前有效用户及候选组
     * @return boolean，流程管理员、当前办理人或未认领任务候选身份命中时返回 true
     */
    private boolean canReadActiveTask(Task task, WorkflowCurrentIdentity actor)
    {
        if (hasWorkflowAdministrativeRead(actor))
        {
            return true;
        }
        if (StringUtils.hasText(task.getAssignee()))
        {
            // 任务一经认领，只允许当前 assignee 读取；旧候选身份不能旁路查看他人正在办理的表单。
            return actor.userId().equals(task.getAssignee());
        }
        return hasCandidateTask(task.getProcessInstanceId(), task.getId(), actor);
    }

    /**
     * 判断当前身份是否具有跨实例工作流管理读取能力。
     *
     * @param actor WorkflowCurrentIdentity，已由主数据核验的当前工作流身份
     * @return boolean，若依超级管理员或同一登录用户持有 workflow:process:state 时返回 true
     */
    private boolean hasWorkflowAdministrativeRead(WorkflowCurrentIdentity actor)
    {
        try
        {
            if (SecurityUtils.isAdmin(Long.valueOf(actor.userId())))
            {
                return true;
            }
        }
        catch (NumberFormatException exception)
        {
            return false;
        }

        Authentication authentication = SecurityUtils.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)
                || loginUser.getUserId() == null
                || !actor.userId().equals(loginUser.getUserId().toString())
                || loginUser.getPermissions() == null)
        {
            return false;
        }
        // 身份一致后仍走统一实时权限源，撤销 state 菜单关系必须让已签发 Token 立即失效。
        return permissionService.hasPermi(WORKFLOW_ADMIN_PERMISSION);
    }

    /**
     * 判断当前身份是否为流程实例任一未分配运行时任务的直接候选人或有效候选组成员。
     * 读授权包含挂起任务，写动作仍由工作流服务执行独立的挂起状态校验。
     *
     * @param processInstanceId String，目标流程实例 ID
     * @param actor WorkflowCurrentIdentity，事务内核验后的当前身份
     * @return boolean，存在可认领任务时返回 true
     */
    private boolean hasCandidateTask(String processInstanceId, WorkflowCurrentIdentity actor)
    {
        long directCandidateCount = taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskUnassigned()
                .taskCandidateUser(actor.userId())
                .count();
        if (directCandidateCount > 0)
        {
            return true;
        }

        Set<String> candidateGroups = actor.candidateGroups();
        return !candidateGroups.isEmpty() && taskService.createTaskQuery()
                .processInstanceId(processInstanceId)
                .taskUnassigned()
                .taskCandidateGroupIn(candidateGroups)
                .count() > 0;
    }

    /**
     * 判断当前身份是否为指定未认领运行时任务的直接候选人或有效候选组成员。
     * 读授权包含挂起任务，避免候选人在实例挂起后丢失只读详情权限。
     *
     * @param processInstanceId String，运行时任务所属的真实流程实例主键
     * @param taskId String，服务端重新查询得到的真实运行时任务主键
     * @param actor WorkflowCurrentIdentity，事务内核验后的当前身份
     * @return boolean，任务仍活动且未认领，并命中直接候选用户或候选组时返回 true
     */
    private boolean hasCandidateTask(String processInstanceId, String taskId,
            WorkflowCurrentIdentity actor)
    {
        org.flowable.task.api.TaskQuery query = taskService.createTaskQuery()
                .taskId(taskId)
                .processInstanceId(processInstanceId)
                .taskUnassigned()
                .taskCandidateUser(actor.userId());
        if (!actor.candidateGroups().isEmpty())
        {
            // Flowable 8 在同一候选查询中将 candidateUser 和 candidateGroupIn 按 OR 组合。
            query.taskCandidateGroupIn(actor.candidateGroups());
        }
        return query.count() > 0;
    }

    /**
     * 查询任务所属的历史流程实例，防止客户端伪造 task 与 instance 的关联。
     *
     * @param processInstanceId String，任务对象中的真实流程实例 ID
     * @return HistoricProcessInstance，任务所属历史流程实例
     */
    private HistoricProcessInstance requireHistoricInstance(String processInstanceId)
    {
        if (!StringUtils.hasText(processInstanceId))
        {
            throw invalidRelation();
        }
        HistoricProcessInstance processInstance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (processInstance == null)
        {
            throw invalidRelation();
        }
        return processInstance;
    }

    /**
     * 将 Flowable 历史实例转换为不暴露引擎运行时对象的不可变快照。
     *
     * @param processInstance HistoricProcessInstance，已完成授权校验的实例
     * @param runtimeInstance ProcessInstance，当前运行时实例；实例已结束时为空
     * @return WorkflowProcessAccessSnapshot，供后续详情服务使用的实例快照
     */
    private WorkflowProcessAccessSnapshot toProcessSnapshot(
            HistoricProcessInstance processInstance, ProcessInstance runtimeInstance)
    {
        String engineState = processInstance.getState();
        if (runtimeInstance != null)
        {
            if (!processInstance.getId().equals(runtimeInstance.getId()))
            {
                throw invalidRelation();
            }
            // 历史实例在挂起期间仍可能报告 RUNNING，详情状态必须服从同一事务内的运行时事实。
            engineState = runtimeInstance.isSuspended() ? "suspended" : "running";
        }
        return new WorkflowProcessAccessSnapshot(
                processInstance.getId(),
                processInstance.getProcessDefinitionId(),
                processInstance.getDeploymentId(),
                processInstance.getBusinessKey(),
                processInstance.getStartUserId(),
                toInstant(processInstance.getStartTime()),
                toInstant(processInstance.getEndTime()),
                processInstance.getDeleteReason(),
                processInstance.getBusinessStatus(),
                engineState);
    }

    /**
     * 将 Flowable 活动任务转换为不可变授权快照。
     *
     * @param task Task，已完成对象授权校验的活动任务
     * @return WorkflowTaskAccessSnapshot，活动态任务快照
     */
    private WorkflowTaskAccessSnapshot toTaskSnapshot(Task task)
    {
        String delegationState = task.getDelegationState() == null ? null : task.getDelegationState().name();
        return new WorkflowTaskAccessSnapshot(
                task.getId(),
                task.getProcessInstanceId(),
                task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(),
                task.getName(),
                task.getAssignee(),
                task.getOwner(),
                delegationState,
                true,
                toInstant(task.getCreateTime()),
                null,
                task.getClaimedBy(),
                toInstant(task.getClaimTime()));
    }

    /**
     * 将 Flowable 历史任务转换为不可变授权快照。
     *
     * @param task HistoricTaskInstance，已完成对象授权校验的历史任务
     * @return WorkflowTaskAccessSnapshot，历史态任务快照
     */
    private WorkflowTaskAccessSnapshot toTaskSnapshot(HistoricTaskInstance task)
    {
        return new WorkflowTaskAccessSnapshot(
                task.getId(),
                task.getProcessInstanceId(),
                task.getProcessDefinitionId(),
                task.getTaskDefinitionKey(),
                task.getName(),
                task.getAssignee(),
                task.getOwner(),
                null,
                false,
                toInstant(task.getCreateTime()),
                toInstant(task.getEndTime()),
                task.getClaimedBy(),
                toInstant(task.getClaimTime()));
    }

    /**
     * 将可空 Date 转换为不可变 Instant。
     *
     * @param value Date，可为空的引擎时间
     * @return Instant，可为空的不可变时间值
     */
    private Instant toInstant(Date value)
    {
        return value == null ? null : value.toInstant();
    }

    /**
     * 校验工作流对象 ID 不为空。
     *
     * @param value String，待校验的流程或任务 ID
     * @return 无返回值，参数为空时抛出稳定 400 业务异常
     */
    private void requireText(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw new ServiceException(INVALID_ARGUMENT_MESSAGE, HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 创建对象不存在异常。
     *
     * @return ServiceException，稳定 404 业务异常
     */
    private ServiceException notFound()
    {
        return new ServiceException(NOT_FOUND_MESSAGE, HttpStatus.NOT_FOUND);
    }

    /**
     * 创建对象读取权限拒绝异常。
     *
     * @return ServiceException，稳定 403 业务异常
     */
    private ServiceException forbidden()
    {
        return new ServiceException(FORBIDDEN_MESSAGE, HttpStatus.FORBIDDEN);
    }

    /**
     * 创建任务与实例内部关联异常。
     *
     * @return ServiceException，稳定 500 业务异常
     */
    private ServiceException invalidRelation()
    {
        return new ServiceException(INVALID_RELATION_MESSAGE, HttpStatus.ERROR);
    }
}
