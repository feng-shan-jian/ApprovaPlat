package com.ruoyi.flowable.domain;

import java.time.LocalDateTime;

/**
 * 单个真实审批任务的 SLA 状态，对应 {@code wf_task_sla_execution}。
 */
public class WfTaskSlaExecution
{
    private Long slaExecutionId;
    private String deploymentId;
    private String processInstanceId;
    private String processDefinitionId;
    private String taskId;
    private String taskDefinitionKey;
    private String assigneeUserId;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime reminderDueAt;
    private LocalDateTime escalationDueAt;
    private Integer remindersSent;
    private LocalDateTime pausedAt;
    private Long pausedMillis;
    private Integer revision;

    /** @return Long，SLA 执行主键。 */
    public Long getSlaExecutionId() { return slaExecutionId; }
    /** @param slaExecutionId Long，SLA 执行主键；@return void，无返回值。 */
    public void setSlaExecutionId(Long slaExecutionId) { this.slaExecutionId = slaExecutionId; }
    /** @return String，部署主键。 */
    public String getDeploymentId() { return deploymentId; }
    /** @param deploymentId String，部署主键；@return void，无返回值。 */
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    /** @return String，流程实例主键。 */
    public String getProcessInstanceId() { return processInstanceId; }
    /** @param processInstanceId String，流程实例主键；@return void，无返回值。 */
    public void setProcessInstanceId(String processInstanceId) { this.processInstanceId = processInstanceId; }
    /** @return String，流程定义主键。 */
    public String getProcessDefinitionId() { return processDefinitionId; }
    /** @param processDefinitionId String，流程定义主键；@return void，无返回值。 */
    public void setProcessDefinitionId(String processDefinitionId) { this.processDefinitionId = processDefinitionId; }
    /** @return String，审批任务主键。 */
    public String getTaskId() { return taskId; }
    /** @param taskId String，审批任务主键；@return void，无返回值。 */
    public void setTaskId(String taskId) { this.taskId = taskId; }
    /** @return String，审批节点标识。 */
    public String getTaskDefinitionKey() { return taskDefinitionKey; }
    /** @param taskDefinitionKey String，审批节点标识；@return void，无返回值。 */
    public void setTaskDefinitionKey(String taskDefinitionKey) { this.taskDefinitionKey = taskDefinitionKey; }
    /** @return String，当前办理人用户主键。 */
    public String getAssigneeUserId() { return assigneeUserId; }
    /** @param assigneeUserId String，当前办理人用户主键；@return void，无返回值。 */
    public void setAssigneeUserId(String assigneeUserId) { this.assigneeUserId = assigneeUserId; }
    /** @return String，ACTIVE、COMPLETED 或 ESCALATED。 */
    public String getStatus() { return status; }
    /** @param status String，执行状态；@return void，无返回值。 */
    public void setStatus(String status) { this.status = status; }
    /** @return LocalDateTime，开始时间。 */
    public LocalDateTime getStartedAt() { return startedAt; }
    /** @param startedAt LocalDateTime，开始时间；@return void，无返回值。 */
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    /** @return LocalDateTime，首次提醒时间。 */
    public LocalDateTime getReminderDueAt() { return reminderDueAt; }
    /** @param reminderDueAt LocalDateTime，首次提醒时间；@return void，无返回值。 */
    public void setReminderDueAt(LocalDateTime reminderDueAt) { this.reminderDueAt = reminderDueAt; }
    /** @return LocalDateTime，升级时间。 */
    public LocalDateTime getEscalationDueAt() { return escalationDueAt; }
    /** @param escalationDueAt LocalDateTime，升级时间；@return void，无返回值。 */
    public void setEscalationDueAt(LocalDateTime escalationDueAt) { this.escalationDueAt = escalationDueAt; }
    /** @return Integer，已发送提醒次数。 */
    public Integer getRemindersSent() { return remindersSent; }
    /** @param remindersSent Integer，已发送提醒次数；@return void，无返回值。 */
    public void setRemindersSent(Integer remindersSent) { this.remindersSent = remindersSent; }
    /** @return LocalDateTime，当前暂停开始时间。 */
    public LocalDateTime getPausedAt() { return pausedAt; }
    /** @param pausedAt LocalDateTime，当前暂停开始时间；@return void，无返回值。 */
    public void setPausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; }
    /** @return Long，累计暂停毫秒数。 */
    public Long getPausedMillis() { return pausedMillis; }
    /** @param pausedMillis Long，累计暂停毫秒数；@return void，无返回值。 */
    public void setPausedMillis(Long pausedMillis) { this.pausedMillis = pausedMillis; }
    /** @return Integer，乐观锁版本。 */
    public Integer getRevision() { return revision; }
    /** @param revision Integer，乐观锁版本；@return void，无返回值。 */
    public void setRevision(Integer revision) { this.revision = revision; }
}
