package com.ruoyi.flowable.domain;

import java.util.Date;

/** 协作消息可靠投递、幂等和死信台账，对应 wf_collaboration_message。 */
public class WfCollaborationMessage
{
    private String messageId;
    private Long credentialId;
    private String actorUserId;
    private String channelId;
    private Long sequenceNo;
    private String messageName;
    private String sourceProcessDefinitionKey;
    private String targetProcessDefinitionKey;
    private String correlationKey;
    private String targetProcessInstanceId;
    private String matchedProcessInstanceId;
    private String targetExecutionId;
    private String variablesJson;
    private String payloadSha256;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private Integer compensationCount;
    private Integer revisionNo;
    private String lastErrorCode;
    private String lastErrorSummary;
    private Date createTime;
    private Date nextAttemptTime;
    private Date completeTime;

    public String getMessageId() { return messageId; }
    public void setMessageId(String value) { messageId = value; }
    public Long getCredentialId() { return credentialId; }
    public void setCredentialId(Long value) { credentialId = value; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String value) { actorUserId = value; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String value) { channelId = value; }
    public Long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(Long value) { sequenceNo = value; }
    public String getMessageName() { return messageName; }
    public void setMessageName(String value) { messageName = value; }
    public String getSourceProcessDefinitionKey() { return sourceProcessDefinitionKey; }
    public void setSourceProcessDefinitionKey(String value) { sourceProcessDefinitionKey = value; }
    public String getTargetProcessDefinitionKey() { return targetProcessDefinitionKey; }
    public void setTargetProcessDefinitionKey(String value) { targetProcessDefinitionKey = value; }
    public String getCorrelationKey() { return correlationKey; }
    public void setCorrelationKey(String value) { correlationKey = value; }
    public String getTargetProcessInstanceId() { return targetProcessInstanceId; }
    public void setTargetProcessInstanceId(String value) { targetProcessInstanceId = value; }
    public String getMatchedProcessInstanceId() { return matchedProcessInstanceId; }
    public void setMatchedProcessInstanceId(String value) { matchedProcessInstanceId = value; }
    public String getTargetExecutionId() { return targetExecutionId; }
    public void setTargetExecutionId(String value) { targetExecutionId = value; }
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
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String value) { lastErrorCode = value; }
    public String getLastErrorSummary() { return lastErrorSummary; }
    public void setLastErrorSummary(String value) { lastErrorSummary = value; }
    public Date getCreateTime() { return copy(createTime); }
    public void setCreateTime(Date value) { createTime = copy(value); }
    public Date getNextAttemptTime() { return copy(nextAttemptTime); }
    public void setNextAttemptTime(Date value) { nextAttemptTime = copy(value); }
    public Date getCompleteTime() { return copy(completeTime); }
    public void setCompleteTime(Date value) { completeTime = copy(value); }

    private Date copy(Date value) { return value == null ? null : new Date(value.getTime()); }
}
