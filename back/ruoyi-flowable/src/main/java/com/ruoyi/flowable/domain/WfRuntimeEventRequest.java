package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 消息、信号和 ReceiveTask 运行事件的正式幂等台账实体。
 */
public class WfRuntimeEventRequest
{
    private String requestId;
    private Long credentialId;
    private String eventType;
    private String eventName;
    private String correlationType;
    private String correlationValue;
    private String variablesSha256;
    private String matchedProcessInstanceId;
    private String matchedExecutionId;
    private String status;
    private String resultCode;
    private String resultSummary;
    private Date createTime;
    private Date completeTime;

    /** @return String，幂等请求 UUID。 */
    public String getRequestId() { return requestId; }
    /** @param requestId String，幂等请求 UUID；@return void，无返回值。 */
    public void setRequestId(String requestId) { this.requestId = requestId; }
    /** @return Long，认证凭据主键。 */
    public Long getCredentialId() { return credentialId; }
    /** @param credentialId Long，认证凭据主键；@return void，无返回值。 */
    public void setCredentialId(Long credentialId) { this.credentialId = credentialId; }
    /** @return String，事件类型。 */
    public String getEventType() { return eventType; }
    /** @param eventType String，事件类型；@return void，无返回值。 */
    public void setEventType(String eventType) { this.eventType = eventType; }
    /** @return String，事件名或 activityId。 */
    public String getEventName() { return eventName; }
    /** @param eventName String，事件名或 activityId；@return void，无返回值。 */
    public void setEventName(String eventName) { this.eventName = eventName; }
    /** @return String，关联条件类型。 */
    public String getCorrelationType() { return correlationType; }
    /** @param correlationType String，关联条件类型；@return void，无返回值。 */
    public void setCorrelationType(String correlationType) { this.correlationType = correlationType; }
    /** @return String，关联条件值。 */
    public String getCorrelationValue() { return correlationValue; }
    /** @param correlationValue String，关联条件值；@return void，无返回值。 */
    public void setCorrelationValue(String correlationValue) { this.correlationValue = correlationValue; }
    /** @return String，规范请求摘要。 */
    public String getVariablesSha256() { return variablesSha256; }
    /** @param variablesSha256 String，规范请求摘要；@return void，无返回值。 */
    public void setVariablesSha256(String variablesSha256) { this.variablesSha256 = variablesSha256; }
    /** @return String，匹配流程实例主键。 */
    public String getMatchedProcessInstanceId() { return matchedProcessInstanceId; }
    /** @param value String，匹配流程实例主键；@return void，无返回值。 */
    public void setMatchedProcessInstanceId(String value) { this.matchedProcessInstanceId = value; }
    /** @return String，匹配执行主键。 */
    public String getMatchedExecutionId() { return matchedExecutionId; }
    /** @param value String，匹配执行主键；@return void，无返回值。 */
    public void setMatchedExecutionId(String value) { this.matchedExecutionId = value; }
    /** @return String，处理状态。 */
    public String getStatus() { return status; }
    /** @param status String，处理状态；@return void，无返回值。 */
    public void setStatus(String status) { this.status = status; }
    /** @return String，稳定结果码。 */
    public String getResultCode() { return resultCode; }
    /** @param resultCode String，稳定结果码；@return void，无返回值。 */
    public void setResultCode(String resultCode) { this.resultCode = resultCode; }
    /** @return String，脱敏结果摘要。 */
    public String getResultSummary() { return resultSummary; }
    /** @param resultSummary String，脱敏结果摘要；@return void，无返回值。 */
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    /** @return Date，首次请求时间。 */
    public Date getCreateTime() { return copy(createTime); }
    /** @param createTime Date，首次请求时间；@return void，无返回值。 */
    public void setCreateTime(Date createTime) { this.createTime = copy(createTime); }
    /** @return Date，完成时间。 */
    public Date getCompleteTime() { return copy(completeTime); }
    /** @param completeTime Date，完成时间；@return void，无返回值。 */
    public void setCompleteTime(Date completeTime) { this.completeTime = copy(completeTime); }

    /**
     * 复制可变时间值。
     * @param value Date，允许为空的时间
     * @return Date，时间副本或 null
     */
    private Date copy(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
