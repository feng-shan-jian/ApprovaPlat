package com.ruoyi.flowable.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * BPMN 业务错误与升级编码目录，对应 {@code wf_bpmn_event_code}。
 */
public class WfBpmnEventCode extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 编码目录主键。 */
    private Long eventCodeId;
    /** 事件类型：ERROR 或 ESCALATION。 */
    private String eventType;
    /** BPMN 匹配使用的稳定业务编码。 */
    private String eventCode;
    /** 用户可见事件名称。 */
    private String eventName;
    /** 通知策略：NONE 或 INITIATOR。 */
    private String notificationPolicy;
    /** 目录状态：ENABLED 或 DISABLED。 */
    private String status;

    /** @return Long，编码目录主键。 */
    public Long getEventCodeId() { return eventCodeId; }
    /** @param eventCodeId Long，编码目录主键；@return void，无返回值。 */
    public void setEventCodeId(Long eventCodeId) { this.eventCodeId = eventCodeId; }
    /** @return String，ERROR 或 ESCALATION。 */
    public String getEventType() { return eventType; }
    /** @param eventType String，事件类型；@return void，无返回值。 */
    public void setEventType(String eventType) { this.eventType = eventType; }
    /** @return String，稳定业务编码。 */
    public String getEventCode() { return eventCode; }
    /** @param eventCode String，稳定业务编码；@return void，无返回值。 */
    public void setEventCode(String eventCode) { this.eventCode = eventCode; }
    /** @return String，用户可见名称。 */
    public String getEventName() { return eventName; }
    /** @param eventName String，用户可见名称；@return void，无返回值。 */
    public void setEventName(String eventName) { this.eventName = eventName; }
    /** @return String，通知策略。 */
    public String getNotificationPolicy() { return notificationPolicy; }
    /** @param notificationPolicy String，通知策略；@return void，无返回值。 */
    public void setNotificationPolicy(String notificationPolicy) { this.notificationPolicy = notificationPolicy; }
    /** @return String，ENABLED 或 DISABLED。 */
    public String getStatus() { return status; }
    /** @param status String，目录状态；@return void，无返回值。 */
    public void setStatus(String status) { this.status = status; }
}
