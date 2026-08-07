package com.ruoyi.flowable.domain;

import java.util.Date;

/** SendTask 事务 outbox、租约、重试和死信台账。 */
public class WfCollaborationOutbox
{
    private String messageId;
    private String channelId;
    private Long sequenceNo;
    private String sourceProcessDefinitionKey;
    private String sourceProcessInstanceId;
    private String sourceExecutionId;
    private String sourceElementId;
    private String messageName;
    private String targetProcessDefinitionKey;
    private String correlationKey;
    private Long endpointId;
    private Integer endpointRevision;
    private String requestPath;
    private String deliveryConfigJson;
    private String variablesJson;
    private String payloadSha256;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Integer compensationCount;
    private Integer revisionNo;
    private String leaseOwner;
    private Date leaseUntil;
    private Date nextAttemptTime;
    private Integer lastHttpStatus;
    private String lastErrorCode;
    private String lastErrorSummary;
    private Date createTime;
    private Date lastAttemptTime;
    private Date completeTime;

    public String getMessageId() { return messageId; }
    public void setMessageId(String value) { messageId = value; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String value) { channelId = value; }
    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long value) { sequenceNo = value; }
    public String getSourceProcessDefinitionKey() { return sourceProcessDefinitionKey; }
    public void setSourceProcessDefinitionKey(String value) { sourceProcessDefinitionKey = value; }
    public String getSourceProcessInstanceId() { return sourceProcessInstanceId; }
    public void setSourceProcessInstanceId(String value) { sourceProcessInstanceId = value; }
    public String getSourceExecutionId() { return sourceExecutionId; }
    public void setSourceExecutionId(String value) { sourceExecutionId = value; }
    public String getSourceElementId() { return sourceElementId; }
    public void setSourceElementId(String value) { sourceElementId = value; }
    public String getMessageName() { return messageName; }
    public void setMessageName(String value) { messageName = value; }
    public String getTargetProcessDefinitionKey() { return targetProcessDefinitionKey; }
    public void setTargetProcessDefinitionKey(String value) { targetProcessDefinitionKey = value; }
    public String getCorrelationKey() { return correlationKey; }
    public void setCorrelationKey(String value) { correlationKey = value; }
    public Long getEndpointId() { return endpointId; }
    public void setEndpointId(Long value) { endpointId = value; }
    public Integer getEndpointRevision() { return endpointRevision; }
    public void setEndpointRevision(Integer value) { endpointRevision = value; }
    public String getRequestPath() { return requestPath; }
    public void setRequestPath(String value) { requestPath = value; }
    public String getDeliveryConfigJson() { return deliveryConfigJson; }
    public void setDeliveryConfigJson(String value) { deliveryConfigJson = value; }
    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String value) { variablesJson = value; }
    public String getPayloadSha256() { return payloadSha256; }
    public void setPayloadSha256(String value) { payloadSha256 = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public Integer getAttemptCount() { return attemptCount; }
    public void setAttemptCount(Integer value) { attemptCount = value; }
    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer value) { maxAttempts = value; }
    public Integer getCompensationCount() { return compensationCount; }
    public void setCompensationCount(Integer value) { compensationCount = value; }
    public Integer getRevisionNo() { return revisionNo; }
    public void setRevisionNo(Integer value) { revisionNo = value; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String value) { leaseOwner = value; }
    public Date getLeaseUntil() { return copy(leaseUntil); }
    public void setLeaseUntil(Date value) { leaseUntil = copy(value); }
    public Date getNextAttemptTime() { return copy(nextAttemptTime); }
    public void setNextAttemptTime(Date value) { nextAttemptTime = copy(value); }
    public Integer getLastHttpStatus() { return lastHttpStatus; }
    public void setLastHttpStatus(Integer value) { lastHttpStatus = value; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { lastErrorCode = value; }
    public String getLastErrorSummary() { return lastErrorSummary; }
    public void setLastErrorSummary(String value) { lastErrorSummary = value; }
    public Date getCreateTime() { return copy(createTime); }
    public void setCreateTime(Date value) { createTime = copy(value); }
    public Date getLastAttemptTime() { return copy(lastAttemptTime); }
    public void setLastAttemptTime(Date value) { lastAttemptTime = copy(value); }
    public Date getCompleteTime() { return copy(completeTime); }
    public void setCompleteTime(Date value) { completeTime = copy(value); }
    private Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
}
