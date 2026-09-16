package com.ruoyi.flowable.service.task;

import java.util.Map;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.TaskService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowIdentityCodec;

/**
 * 在当前 Flowable 事务内序列化并记录分配及完成步骤已经确定的任务事实。
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
    private final WorkflowIdentityCodec identityCodec;

    /**
     * 创建用户任务监听审计领域服务。
     *
     * 构造后由 Spring 管理该服务
     */
    public WorkflowUserTaskAuditService(TaskService taskService,
            WorkflowIdentityCodec identityCodec)
    {
        this.taskService = taskService;
        this.identityCodec = identityCodec;
    }

    /**
     * 把分配方传入的最终 assignee/owner 和当前操作人写入既定审计 JSON。
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
        String actorUserId = normalizeAuthenticatedActor(eventName);

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
        if (assignee != null)
        {
            audit.put("assigneeUserId", assignee);
        }
        if (owner != null)
        {
            audit.put("ownerUserId", owner);
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
     * 创建稳定的监听上下文或主数据一致性异常。
     *
     * @return ServiceException，HTTP 500 数据一致性异常
     */
    private ServiceException dataError()
    {
        return new ServiceException("用户任务监听数据异常", HttpStatus.ERROR);
    }
}
