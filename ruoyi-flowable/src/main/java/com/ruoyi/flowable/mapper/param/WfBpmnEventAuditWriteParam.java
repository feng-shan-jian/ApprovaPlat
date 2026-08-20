package com.ruoyi.flowable.mapper.param;

/** BPMN 事件审计的一次可变写入参数，承接 MyBatis generated key。 */
public class WfBpmnEventAuditWriteParam
{
    private Long auditId;
    private String idempotencyKey;
    private String deploymentId;
    private String processInstanceId;
    private String processDefinitionId;
    private String executionId;
    private String sourceElementId;
    private String sourceType;
    private String eventType;
    private String eventCode;
    private String eventName;
    private String matchStatus;
    private String boundaryEventId;
    private Boolean interrupting;
    private String messageSummary;
    private String initiatorUserId;

    public Long getAuditId() { return auditId; }
    public void setAuditId(Long auditId) { this.auditId = auditId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String value) { this.idempotencyKey = value; }
    public String getDeploymentId() { return deploymentId; }
    public void setDeploymentId(String value) { this.deploymentId = value; }
    public String getProcessInstanceId() { return processInstanceId; }
    public void setProcessInstanceId(String value) { this.processInstanceId = value; }
    public String getProcessDefinitionId() { return processDefinitionId; }
    public void setProcessDefinitionId(String value) { this.processDefinitionId = value; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String value) { this.executionId = value; }
    public String getSourceElementId() { return sourceElementId; }
    public void setSourceElementId(String value) { this.sourceElementId = value; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String value) { this.sourceType = value; }
    public String getEventType() { return eventType; }
    public void setEventType(String value) { this.eventType = value; }
    public String getEventCode() { return eventCode; }
    public void setEventCode(String value) { this.eventCode = value; }
    public String getEventName() { return eventName; }
    public void setEventName(String value) { this.eventName = value; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String value) { this.matchStatus = value; }
    public String getBoundaryEventId() { return boundaryEventId; }
    public void setBoundaryEventId(String value) { this.boundaryEventId = value; }
    public Boolean getInterrupting() { return interrupting; }
    public void setInterrupting(Boolean value) { this.interrupting = value; }
    public String getMessageSummary() { return messageSummary; }
    public void setMessageSummary(String value) { this.messageSummary = value; }
    public String getInitiatorUserId() { return initiatorUserId; }
    public void setInitiatorUserId(String value) { this.initiatorUserId = value; }
}
