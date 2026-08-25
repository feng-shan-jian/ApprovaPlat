package com.ruoyi.flowable.service.task;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.TaskService;
import org.flowable.identitylink.api.IdentityLink;
import org.flowable.identitylink.api.IdentityLinkType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * 用户任务监听领域服务，统一校验正式办理身份及实时办理权限并在当前 Flowable 事务内写结构化审计。
 */
@Service
public class WorkflowUserTaskAuditService
{
    /** 用户任务监听审计 comment 的固定类型。 */
    public static final String COMMENT_TYPE = "USER_TASK_LISTENER";

    /** 审计 JSON 的固定 schema 版本。 */
    private static final int AUDIT_SCHEMA_VERSION = 1;

    /** Flowable 任务、实例、定义和节点主键的安全字符上限。 */
    private static final int MAX_ENGINE_ID_LENGTH = 255;

    /** 监听事件到服务端固定审计动作的只读映射。 */
    private static final Map<String, String> AUDIT_ACTIONS = Map.of(
            "create", "USER_TASK_CREATE",
            "assignment", "USER_TASK_ASSIGNMENT",
            "complete", "USER_TASK_COMPLETE");

    private final TaskService taskService;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowIdentityCodec identityCodec;

    /**
     * 创建用户任务监听审计领域服务。
     *
     * @param taskService TaskService，写入 Flowable 正式 comment 的公共 API
     * @param identityResolver WorkflowIdentityResolver，正式启用用户解析器
     * @param identityCodec WorkflowIdentityCodec，用户主键格式规范器
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowUserTaskAuditService(TaskService taskService,
            WorkflowIdentityResolver identityResolver, WorkflowIdentityCodec identityCodec)
    {
        this.taskService = taskService;
        this.identityResolver = identityResolver;
        this.identityCodec = identityCodec;
    }

    /**
     * 校验批准事件、任务关联、assignee/owner 正式身份及 create 事件动态候选资格后，
     * 在当前 Flowable 事务中写入结构化 comment。
     *
     * @param eventName String，create、assignment 或 complete 固定事件名
     * @param taskId String，当前用户任务主键
     * @param processInstanceId String，任务所属流程实例主键
     * @param processDefinitionId String，任务所属流程定义主键
     * @param taskDefinitionKey String，任务对应 BPMN 节点 key
     * @param assignee String，可为空的当前办理用户主键
     * @param owner String，可为空的当前任务所有用户主键
     * @return void，无返回值；校验或 comment 写入失败时当前引擎事务回滚
     */
    public void recordAudit(String eventName, String taskId, String processInstanceId,
            String processDefinitionId, String taskDefinitionKey,
            String assignee, String owner)
    {
        String action = requireApprovedAction(eventName);
        String normalizedTaskId = requireEngineId(taskId);
        String normalizedProcessInstanceId = requireEngineId(processInstanceId);
        String normalizedProcessDefinitionId = requireEngineId(processDefinitionId);
        String normalizedTaskDefinitionKey = requireEngineId(taskDefinitionKey);
        String normalizedAssignee = requireCanonicalOptionalUserId(assignee);
        String normalizedOwner = requireCanonicalOptionalUserId(owner);
        String actorUserId = normalizeAuthenticatedActor(eventName);

        // 退回修改任务允许原发起人临时成为 assignee，但仍要求发起人有效且退回操作人具备审批资格。
        boolean returnedApplicantAssignment = validateReturnedApplicantAssignment(
                eventName, normalizedTaskId, normalizedAssignee,
                normalizedOwner, actorUserId);
        if (!returnedApplicantAssignment)
        {
            // complete 操作人和普通任务身份在写 comment 前一次性查正式主数据，权限撤销后不能留下成功审计。
            requireApprovalEligibleTaskUsers(normalizedAssignee, normalizedOwner,
                    "complete".equals(eventName) ? actorUserId : null);
        }
        requireClaimEligibleCandidates(eventName, normalizedTaskId, normalizedAssignee);

        ObjectNode audit = JsonNodeFactory.instance.objectNode();
        audit.put("schemaVersion", AUDIT_SCHEMA_VERSION);
        audit.put("action", action);
        audit.put("event", eventName);
        audit.put("taskId", normalizedTaskId);
        audit.put("processInstanceId", normalizedProcessInstanceId);
        audit.put("processDefinitionId", normalizedProcessDefinitionId);
        audit.put("taskDefinitionKey", normalizedTaskDefinitionKey);
        if (actorUserId != null)
        {
            audit.put("actorUserId", actorUserId);
        }
        if (normalizedAssignee != null)
        {
            audit.put("assigneeUserId", normalizedAssignee);
        }
        if (normalizedOwner != null)
        {
            audit.put("ownerUserId", normalizedOwner);
        }

        // comment 与任务事件共用当前 Flowable 命令事务，不另开事务也不写 processStatus。
        taskService.addComment(normalizedTaskId, normalizedProcessInstanceId,
                COMMENT_TYPE, audit.toString());
    }

    /**
     * 将批准事件转换为服务端固定动作，防止非监听器调用绕过事件白名单。
     *
     * @param eventName String，待核验的 Flowable 任务事件名
     * @return String，对应的固定审计动作编码
     */
    private String requireApprovedAction(String eventName)
    {
        String action = AUDIT_ACTIONS.get(eventName);
        if (action == null)
        {
            throw new ServiceException("用户任务监听事件不受支持", HttpStatus.BAD_REQUEST);
        }
        return action;
    }

    /**
     * 校验 Flowable 任务关联主键并限制数据库字段长度。
     *
     * @param value String，任务、实例、定义或 BPMN 节点主键
     * @return String，去除首尾空白后的受控非空主键
     */
    private String requireEngineId(String value)
    {
        if (!StringUtils.hasText(value))
        {
            throw dataError();
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_ENGINE_ID_LENGTH)
        {
            throw dataError();
        }
        return normalized;
    }

    /**
     * 校验可选任务用户主键已经是规范正整数，禁止只规范审计副本而保留不可用引擎身份。
     *
     * @param userId String，可为空的 assignee 或 owner 用户主键
     * @return String，与 Flowable 原始值完全一致的规范用户主键；未设置时为 null
     */
    private String requireCanonicalOptionalUserId(String userId)
    {
        if (userId == null)
        {
            return null;
        }
        String normalized;
        try
        {
            normalized = identityCodec.normalizeUserId(userId);
        }
        catch (ServiceException exception)
        {
            throw invalidTaskIdentity(exception);
        }
        if (!normalized.equals(userId))
        {
            // 只规范审计副本会把 Flowable 中的原始 assignee/owner 留成不可查询身份；
            // 必须在任务创建或分配事务内拒绝，使引擎任务与若依用户主键保持完全一致。
            throw invalidTaskIdentity(null);
        }
        return normalized;
    }

    /**
     * 一次性核验任务 assignee、owner 和完成操作人都是当前具备流程办理权限的正式用户。
     *
     * @param assignee String，可为空的规范办理用户主键
     * @param owner String，可为空的规范任务所有用户主键
     * @param completionActor String，可为空的规范完成操作人主键
     * @return void，无返回值；任一用户停用、不存在或查询异常时中止当前事务
     */
    private void requireApprovalEligibleTaskUsers(String assignee, String owner,
            String completionActor)
    {
        LinkedHashSet<String> expectedUserIds = new LinkedHashSet<>();
        if (assignee != null)
        {
            expectedUserIds.add(assignee);
        }
        if (owner != null)
        {
            expectedUserIds.add(owner);
        }
        if (completionActor != null)
        {
            expectedUserIds.add(completionActor);
        }
        if (expectedUserIds.isEmpty())
        {
            return;
        }

        // 监听器位于任务创建和分配事务内，在此拒绝无办理权限用户可防止产生无法继续的正式任务。
        Set<String> eligibleUserIds = identityResolver.resolveApprovalEligibleUserIds(
                expectedUserIds);
        if (eligibleUserIds == null || !expectedUserIds.containsAll(eligibleUserIds))
        {
            throw dataError();
        }
        if (!eligibleUserIds.equals(expectedUserIds))
        {
            throw invalidTaskIdentity(null);
        }
    }

    /**
     * 识别并校验服务端直接退回发起人的受控 assignment 事件。
     *
     * @param eventName String，当前 Flowable 监听事件
     * @param taskId String，当前用户任务主键
     * @param assignee String，已规范化的当前办理人主键
     * @param owner String，已规范化的当前所有者主键
     * @param actorUserId String，当前 Flowable 认证操作人主键
     * @return boolean，仅受控任务局部标记与 assignee 一致且身份校验通过时返回 true
     */
    private boolean validateReturnedApplicantAssignment(String eventName, String taskId,
            String assignee, String owner, String actorUserId)
    {
        if (!"assignment".equals(eventName) || assignee == null)
        {
            return false;
        }
        Object marker = taskService.getVariableLocal(taskId,
                WorkflowReturnedApplicationProtocol.RETURN_APPLICANT_VARIABLE);
        if (marker == null)
        {
            return false;
        }
        if (!(marker instanceof String applicantUserId)
                || !assignee.equals(applicantUserId)
                || owner != null || actorUserId == null)
        {
            throw dataError();
        }

        Set<String> activeApplicantIds;
        try
        {
            activeApplicantIds = identityResolver.resolveActiveUserIds(
                    List.of(applicantUserId), List.of());
        }
        catch (ServiceException exception)
        {
            if (Integer.valueOf(HttpStatus.ERROR).equals(exception.getCode()))
            {
                ServiceException failure = dataError();
                failure.initCause(exception);
                throw failure;
            }
            throw invalidTaskIdentity(exception);
        }
        if (!Set.of(applicantUserId).equals(activeApplicantIds))
        {
            throw invalidTaskIdentity(null);
        }

        // 非审批发起人只能被合格审批人执行退回时接管任务，不能借内部标记绕过操作人资格。
        requireApprovalEligibleTaskUsers(null, null, actorUserId);
        return true;
    }

    /**
     * 在候选任务创建事务内核验动态解析后的 candidateUser/candidateGroup 可走通完整认领路径。
     *
     * @param eventName String，已经通过白名单的任务监听事件
     * @param taskId String，当前 Flowable 活动任务主键
     * @param assignee String，可为空的规范直接办理人；存在时无需消费候选身份
     * @return void，无返回值；候选身份为空、非法或无人具备完整认领资格时回滚任务创建
     */
    private void requireClaimEligibleCandidates(String eventName, String taskId,
            String assignee)
    {
        if (!"create".equals(eventName) || assignee != null)
        {
            return;
        }
        List<IdentityLink> identityLinks = taskService.getIdentityLinksForTask(taskId);
        if (identityLinks == null)
        {
            throw dataError();
        }

        LinkedHashSet<String> candidateUserIds = new LinkedHashSet<>();
        LinkedHashSet<String> candidateGroups = new LinkedHashSet<>();
        for (IdentityLink identityLink : identityLinks)
        {
            if (identityLink == null
                    || !IdentityLinkType.CANDIDATE.equals(identityLink.getType()))
            {
                continue;
            }
            if (StringUtils.hasText(identityLink.getUserId()))
            {
                candidateUserIds.add(identityLink.getUserId());
            }
            if (StringUtils.hasText(identityLink.getGroupId()))
            {
                candidateGroups.add(identityLink.getGroupId());
            }
        }
        if (candidateUserIds.isEmpty() && candidateGroups.isEmpty())
        {
            throw invalidTaskIdentity(null);
        }

        Set<String> eligibleDirectUsers;
        Set<String> eligibleGroups;
        try
        {
            // 直接候选用户会逐个写入 identity link，任一用户不可认领都说明动态配置已经失真。
            eligibleDirectUsers = identityResolver.resolveClaimEligibleUserIds(
                    candidateUserIds);
            // 每个候选组都必须使用规范编码且至少有一名完整认领资格成员，不能由同批其他有效组掩盖。
            eligibleGroups = identityResolver.resolveClaimEligibleCandidateGroups(
                    candidateGroups);
        }
        catch (ServiceException exception)
        {
            if (Integer.valueOf(HttpStatus.ERROR).equals(exception.getCode()))
            {
                ServiceException failure = dataError();
                failure.initCause(exception);
                throw failure;
            }
            throw invalidTaskIdentity(exception);
        }
        if (!eligibleDirectUsers.equals(candidateUserIds)
                || !eligibleGroups.equals(candidateGroups))
        {
            throw invalidTaskIdentity(null);
        }
    }

    /**
     * 读取并规范 Flowable 当前认证操作人；complete 事件必须保留明确操作人审计。
     *
     * @param eventName String，已经通过白名单的任务事件名
     * @return String，规范操作人主键；create/assignment 系统事件没有操作人时为 null
     */
    private String normalizeAuthenticatedActor(String eventName)
    {
        String actorUserId = Authentication.getAuthenticatedUserId();
        if (!StringUtils.hasText(actorUserId))
        {
            if ("complete".equals(eventName))
            {
                throw dataError();
            }
            return null;
        }
        try
        {
            return identityCodec.normalizeUserId(actorUserId);
        }
        catch (ServiceException exception)
        {
            ServiceException failure = dataError();
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 创建稳定的任务身份参数错误，并保留内部原因供服务端日志追踪。
     *
     * @param cause Throwable，可为空的身份格式校验异常
     * @return ServiceException，HTTP 400 身份参数异常
     */
    private ServiceException invalidTaskIdentity(Throwable cause)
    {
        ServiceException failure = new ServiceException(
                "用户任务办理身份无效", HttpStatus.BAD_REQUEST);
        if (cause != null)
        {
            failure.initCause(cause);
        }
        return failure;
    }

    /**
     * 创建稳定的监听上下文或主数据一致性异常。
     *
     * @return ServiceException，HTTP 500 数据一致性异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("用户任务监听数据异常", HttpStatus.ERROR);
    }
}
