package com.ruoyi.flowable.domain;

/** Participant/MessageFlow 严格顺序通道，对应 wf_collaboration_channel。 */
public class WfCollaborationChannel
{
    private String channelId;
    private String targetProcessDefinitionKey;
    private String correlationType;
    private String correlationValue;
    private Long outboundSequence;
    private Long inboundSequence;
    private Integer revisionNo;

    public String getChannelId() { return channelId; }
    public void setChannelId(String value) { channelId = value; }
    public String getTargetProcessDefinitionKey() { return targetProcessDefinitionKey; }
    public void setTargetProcessDefinitionKey(String value) { targetProcessDefinitionKey = value; }
    public String getCorrelationType() { return correlationType; }
    public void setCorrelationType(String value) { correlationType = value; }
    public String getCorrelationValue() { return correlationValue; }
    public void setCorrelationValue(String value) { correlationValue = value; }
    public Long getOutboundSequence() { return outboundSequence; }
    public void setOutboundSequence(Long value) { outboundSequence = value; }
    public Long getInboundSequence() { return inboundSequence; }
    public void setInboundSequence(Long value) { inboundSequence = value; }
    public Integer getRevisionNo() { return revisionNo; }
    public void setRevisionNo(Integer value) { revisionNo = value; }
}
