package com.ruoyi.flowable.mapper.param;

/** SLA 审计的一次可变写入参数，承接 MyBatis generated key。 */
public class WfTaskSlaAuditWriteParam
{
    private Long auditId;
    private Long executionId;
    private String actionType;
    private Integer actionOrdinal;
    private String actorUserId;
    private String detail;

    public Long getAuditId() { return auditId; }
    public void setAuditId(Long value) { this.auditId = value; }
    public Long getExecutionId() { return executionId; }
    public void setExecutionId(Long value) { this.executionId = value; }
    public String getActionType() { return actionType; }
    public void setActionType(String value) { this.actionType = value; }
    public Integer getActionOrdinal() { return actionOrdinal; }
    public void setActionOrdinal(Integer value) { this.actionOrdinal = value; }
    public String getActorUserId() { return actorUserId; }
    public void setActorUserId(String value) { this.actorUserId = value; }
    public String getDetail() { return detail; }
    public void setDetail(String value) { this.detail = value; }
}
