package com.ruoyi.flowable.domain;

/**
 * 参与者规则一次成功或拒绝解析的独立业务审计记录。
 */
public class WfParticipantResolutionAudit
{
    private String eventType;
    private String deployId;
    private String processDefinitionId;
    private String processInstanceId;
    private String taskId;
    private String activityId;
    private Long ruleId;
    private String initiatorUserId;
    private String actorUserId;
    private String resolvedUserIds;
    private String resolvedGroupIds;
    private String resultCode;
    private String detailSummary;

    /** @return String，START 或 TASK 审计事件。 */
    public String getEventType() { return eventType; }
    /** @param value String，审计事件。@return void，无返回值。 */
    public void setEventType(String value) { eventType = value; }
    /** @return String，部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，部署主键。@return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，流程定义主键。 */
    public String getProcessDefinitionId() { return processDefinitionId; }
    /** @param value String，流程定义主键。@return void，无返回值。 */
    public void setProcessDefinitionId(String value) { processDefinitionId = value; }
    /** @return String，流程实例主键，发起拒绝时为空。 */
    public String getProcessInstanceId() { return processInstanceId; }
    /** @param value String，流程实例主键。@return void，无返回值。 */
    public void setProcessInstanceId(String value) { processInstanceId = value; }
    /** @return String，任务主键，发起事件为空。 */
    public String getTaskId() { return taskId; }
    /** @param value String，任务主键。@return void，无返回值。 */
    public void setTaskId(String value) { taskId = value; }
    /** @return String，任务节点标识。 */
    public String getActivityId() { return activityId; }
    /** @param value String，任务节点标识。@return void，无返回值。 */
    public void setActivityId(String value) { activityId = value; }
    /** @return Long，命中的部署规则主键。 */
    public Long getRuleId() { return ruleId; }
    /** @param value Long，部署规则主键。@return void，无返回值。 */
    public void setRuleId(Long value) { ruleId = value; }
    /** @return String，流程发起人主键。 */
    public String getInitiatorUserId() { return initiatorUserId; }
    /** @param value String，流程发起人主键。@return void，无返回值。 */
    public void setInitiatorUserId(String value) { initiatorUserId = value; }
    /** @return String，本次发起操作人主键。 */
    public String getActorUserId() { return actorUserId; }
    /** @param value String，本次发起操作人主键。@return void，无返回值。 */
    public void setActorUserId(String value) { actorUserId = value; }
    /** @return String，去重后的用户主键列表。 */
    public String getResolvedUserIds() { return resolvedUserIds; }
    /** @param value String，用户主键列表。@return void，无返回值。 */
    public void setResolvedUserIds(String value) { resolvedUserIds = value; }
    /** @return String，去重后的候选组列表。 */
    public String getResolvedGroupIds() { return resolvedGroupIds; }
    /** @param value String，候选组列表。@return void，无返回值。 */
    public void setResolvedGroupIds(String value) { resolvedGroupIds = value; }
    /** @return String，ALLOWED、RESOLVED、DENIED 或 NO_MATCH。 */
    public String getResultCode() { return resultCode; }
    /** @param value String，稳定结果码。@return void，无返回值。 */
    public void setResultCode(String value) { resultCode = value; }
    /** @return String，不包含敏感内容的解析摘要。 */
    public String getDetailSummary() { return detailSummary; }
    /** @param value String，解析摘要。@return void，无返回值。 */
    public void setDetailSummary(String value) { detailSummary = value; }
}
