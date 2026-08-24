package com.ruoyi.flowable.service.task;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Component;

/**
 * 生命周期任务动作结构化 comment 的唯一构造与写入边界。
 */
@Component
public class WorkflowTaskActionAuditWriter
{
    private static final ObjectMapper AUDIT_MAPPER = JsonMapper.shared();

    private final TaskService taskService;

    /**
     * 创建任务动作审计写入器。
     *
     * @param taskService TaskService，Flowable comment 写入服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskActionAuditWriter(TaskService taskService)
    {
        this.taskService = taskService;
    }

    /**
     * 构造可用于 comment 或删除原因的通用结构化审计 JSON。
     *
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内正式用户主键
     * @param opinion String，已校验业务意见
     * @param targetNodeKey String，可为空的迁移目标节点
     * @param sourceTaskId String，可为空的撤回来源历史任务
     * @return String，字段结构与既有审计契约一致的 JSON
     */
    public String build(String action, String actorUserId, String opinion,
            String targetNodeKey, String sourceTaskId)
    {
        return payload(action, actorUserId, opinion, targetNodeKey,
                sourceTaskId).toString();
    }

    /**
     * 构造取消动作并附加实例原挂起状态的结构化审计 JSON。
     *
     * @param actorUserId String，事务内正式用户主键
     * @param opinion String，已校验取消意见
     * @param wasSuspended boolean，终止前根实例是否挂起
     * @return String，包含既有 wasSuspended 字段的 JSON
     */
    public String buildCancellation(String actorUserId, String opinion,
            boolean wasSuspended)
    {
        ObjectNode audit = payload("CANCEL", actorUserId, opinion, null, null);
        audit.put("wasSuspended", wasSuspended);
        return audit.toString();
    }

    /**
     * 为真实任务写入服务端结构化动作 comment。
     *
     * @param task Task，动作前真实活动任务
     * @param commentType String，兼容旧系统的 comment 类型
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内正式用户主键
     * @param opinion String，已校验业务意见
     * @param targetNodeKey String，可为空的迁移目标节点
     * @param sourceTaskId String，可为空的撤回来源历史任务
     * @return 无返回值，写入失败由外层事务整体回滚
     */
    public void write(Task task, String commentType, String action,
            String actorUserId, String opinion, String targetNodeKey,
            String sourceTaskId)
    {
        taskService.addComment(task.getId(), task.getProcessInstanceId(), commentType,
                build(action, actorUserId, opinion, targetNodeKey, sourceTaskId));
    }

    /**
     * 写入完成审计并记录动态多实例 revision 区间。
     *
     * @param task Task，动作前真实活动任务
     * @param actorUserId String，事务内正式用户主键
     * @param opinion String，已校验完成意见
     * @param completionRevision CompletionRevision，普通任务为空计划，动态任务含版本区间
     * @return 无返回值，写入失败由外层事务整体回滚
     */
    public void writeCompletion(Task task, String actorUserId, String opinion,
            WorkflowMultiInstanceService.CompletionRevision completionRevision)
    {
        ObjectNode audit = payload("COMPLETE", actorUserId, opinion, null, null);
        if (completionRevision.applied())
        {
            audit.put("multiInstanceActivityId", completionRevision.activityId());
            audit.put("beforeRevision", completionRevision.beforeRevision());
            audit.put("afterRevision", completionRevision.afterRevision());
        }
        taskService.addComment(task.getId(), task.getProcessInstanceId(), "1",
                audit.toString());
    }

    /**
     * 构造全部动作共享的服务端审计字段。
     *
     * @param action String，服务端固定动作编码
     * @param actorUserId String，事务内正式用户主键
     * @param opinion String，已校验业务意见
     * @param targetNodeKey String，可为空的迁移目标节点
     * @param sourceTaskId String，可为空的撤回来源历史任务
     * @return ObjectNode，仅在当前写入方法内补充专属字段的 JSON 对象
     */
    private ObjectNode payload(String action, String actorUserId, String opinion,
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
}
