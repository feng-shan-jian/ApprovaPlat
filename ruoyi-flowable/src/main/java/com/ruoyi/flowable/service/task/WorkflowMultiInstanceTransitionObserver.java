package com.ruoyi.flowable.service.task;

import java.util.List;

/**
 * Handler 和监听器可见的窄化受控迁移观察接口。
 */
public interface WorkflowMultiInstanceTransitionObserver
{
    /**
     * 解析当前受控迁移需要注入集合表达式的成员。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，正在创建的受控节点
     * @param mode WorkflowMultiInstanceMode，部署固定模式
     * @return WorkflowMultiInstanceTransitionMembers，受控迁移指令；普通进入返回 null
     */
    WorkflowMultiInstanceTransitionMembers resolveTransitionMembers(
            String processInstanceId, String activityId,
            WorkflowMultiInstanceMode mode);

    /**
     * 核对流程作用域成员和 revision 与当前迁移冻结事实一致。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，原受控节点
     * @param mode WorkflowMultiInstanceMode，实时模式
     * @param persistedMembers List&lt;String&gt;，实时有序成员
     * @param persistedRevision int，实时 revision
     * @return void，漂移时中止当前命令
     */
    void requirePersistedSnapshot(String processInstanceId, String activityId,
            WorkflowMultiInstanceMode mode, List<String> persistedMembers,
            int persistedRevision);

    /**
     * 观察 RETURN 首节点临时任务创建。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，任务节点
     * @param rootExecutionId String，临时根主键
     * @param taskId String，临时任务主键
     * @param assignee String，初始办理人
     * @return boolean，当前任务属于 RETURN 临时任务时返回 true
     */
    boolean observeTemporaryTask(String processInstanceId, String activityId,
            String rootExecutionId, String taskId, String assignee);

    /**
     * 观察 REOPEN 新审批成员任务创建。
     *
     * @param processInstanceId String，流程实例主键
     * @param activityId String，原审批节点
     * @param rootExecutionId String，新审批根主键
     * @param assignee String，新成员任务办理人
     * @return void，普通创建保持无操作
     */
    void observeReopenedTask(String processInstanceId, String activityId,
            String rootExecutionId, String assignee);

    /**
     * 观察并授权当前受控迁移撤销来源多实例根。
     *
     * @param processInstanceId String，流程实例主键
     * @param processDefinitionId String，流程定义主键
     * @param activityId String，被取消节点
     * @param rootExecutionId String，被取消根主键
     * @param authenticatedUserId String，Flowable 当前认证用户
     * @return MultiInstanceTransitionCancellation，受控迁移授权；普通异常取消返回 null
     */
    MultiInstanceTransitionCancellation observeControlledRootCancellation(
            String processInstanceId, String processDefinitionId, String activityId,
            String rootExecutionId, String authenticatedUserId);
}
