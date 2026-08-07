package com.ruoyi.flowable.domain;

import java.util.Date;

/** 入站和出站协作消息的逐次状态审计。 */
public class WfCollaborationMessageAudit
{
    private Long auditId;
    private String messageId;
    private String direction;
    private String action;
    private String actorType;
    private String actorId;
    private String fromStatus;
    private String toStatus;
    private Integer attemptNo;
    private String errorCode;
    private String summary;
    private Date createTime;

    public Long getAuditId() { return auditId; }
    public void setAuditId(Long value) { auditId = value; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String value) { messageId = value; }
    public String getDirection() { return direction; }
    public void setDirection(String value) { direction = value; }
    public String getAction() { return action; }
    public void setAction(String value) { action = value; }
    public String getActorType() { return actorType; }
    public void setActorType(String value) { actorType = value; }
    public String getActorId() { return actorId; }
    public void setActorId(String value) { actorId = value; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String value) { fromStatus = value; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String value) { toStatus = value; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer value) { attemptNo = value; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String value) { errorCode = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public Date getCreateTime() { return createTime == null ? null : new Date(createTime.getTime()); }
    public void setCreateTime(Date value) { createTime = value == null ? null : new Date(value.getTime()); }
}
