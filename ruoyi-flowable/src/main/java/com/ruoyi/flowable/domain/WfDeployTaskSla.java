package com.ruoyi.flowable.domain;

/**
 * 不可变审批 SLA 部署快照，保存在 Flowable 业务制品资源
 * {@code approvaplat/task-sla-v1.json}。
 */
public class WfDeployTaskSla
{
    private String deploymentId;
    private String processKey;
    private String taskDefinitionKey;
    private String calendarKey;
    private String calendarTimezone;
    private String workingDays;
    private String workStart;
    private String workEnd;
    private String calendarDaysJson;
    private Integer reminderMinutes;
    private Integer reminderRepeatMinutes;
    private Integer maxReminders;
    private Integer escalationMinutes;
    private String escalationAssignee;
    private String escalationEventCode;
    private String createBy;

    /** @return String，Flowable 部署主键。 */
    public String getDeploymentId() { return deploymentId; }
    /** @param deploymentId String，Flowable 部署主键；@return void，无返回值。 */
    public void setDeploymentId(String deploymentId) { this.deploymentId = deploymentId; }
    /** @return String，流程定义 key。 */
    public String getProcessKey() { return processKey; }
    /** @param processKey String，流程定义 key；@return void，无返回值。 */
    public void setProcessKey(String processKey) { this.processKey = processKey; }
    /** @return String，审批节点标识。 */
    public String getTaskDefinitionKey() { return taskDefinitionKey; }
    /** @param taskDefinitionKey String，审批节点标识；@return void，无返回值。 */
    public void setTaskDefinitionKey(String taskDefinitionKey) { this.taskDefinitionKey = taskDefinitionKey; }
    /** @return String，日历稳定编码。 */
    public String getCalendarKey() { return calendarKey; }
    /** @param calendarKey String，日历稳定编码；@return void，无返回值。 */
    public void setCalendarKey(String calendarKey) { this.calendarKey = calendarKey; }
    /** @return String，冻结 IANA 时区。 */
    public String getCalendarTimezone() { return calendarTimezone; }
    /** @param calendarTimezone String，冻结 IANA 时区；@return void，无返回值。 */
    public void setCalendarTimezone(String calendarTimezone) { this.calendarTimezone = calendarTimezone; }
    /** @return String，冻结 ISO 工作周序号。 */
    public String getWorkingDays() { return workingDays; }
    /** @param workingDays String，冻结 ISO 工作周序号；@return void，无返回值。 */
    public void setWorkingDays(String workingDays) { this.workingDays = workingDays; }
    /** @return String，冻结工作开始时间。 */
    public String getWorkStart() { return workStart; }
    /** @param workStart String，冻结工作开始时间；@return void，无返回值。 */
    public void setWorkStart(String workStart) { this.workStart = workStart; }
    /** @return String，冻结工作结束时间。 */
    public String getWorkEnd() { return workEnd; }
    /** @param workEnd String，冻结工作结束时间；@return void，无返回值。 */
    public void setWorkEnd(String workEnd) { this.workEnd = workEnd; }
    /** @return String，冻结日期覆盖 JSON。 */
    public String getCalendarDaysJson() { return calendarDaysJson; }
    /** @param calendarDaysJson String，冻结日期覆盖 JSON；@return void，无返回值。 */
    public void setCalendarDaysJson(String calendarDaysJson) { this.calendarDaysJson = calendarDaysJson; }
    /** @return Integer，首次提醒工作分钟。 */
    public Integer getReminderMinutes() { return reminderMinutes; }
    /** @param reminderMinutes Integer，首次提醒工作分钟；@return void，无返回值。 */
    public void setReminderMinutes(Integer reminderMinutes) { this.reminderMinutes = reminderMinutes; }
    /** @return Integer，重复提醒间隔工作分钟。 */
    public Integer getReminderRepeatMinutes() { return reminderRepeatMinutes; }
    /** @param reminderRepeatMinutes Integer，重复提醒间隔工作分钟；@return void，无返回值。 */
    public void setReminderRepeatMinutes(Integer reminderRepeatMinutes) { this.reminderRepeatMinutes = reminderRepeatMinutes; }
    /** @return Integer，最大提醒次数。 */
    public Integer getMaxReminders() { return maxReminders; }
    /** @param maxReminders Integer，最大提醒次数；@return void，无返回值。 */
    public void setMaxReminders(Integer maxReminders) { this.maxReminders = maxReminders; }
    /** @return Integer，升级工作分钟。 */
    public Integer getEscalationMinutes() { return escalationMinutes; }
    /** @param escalationMinutes Integer，升级工作分钟；@return void，无返回值。 */
    public void setEscalationMinutes(Integer escalationMinutes) { this.escalationMinutes = escalationMinutes; }
    /** @return String，升级办理用户主键。 */
    public String getEscalationAssignee() { return escalationAssignee; }
    /** @param escalationAssignee String，升级办理用户主键；@return void，无返回值。 */
    public void setEscalationAssignee(String escalationAssignee) { this.escalationAssignee = escalationAssignee; }
    /** @return String，可空受控 BPMN 升级编码。 */
    public String getEscalationEventCode() { return escalationEventCode; }
    /** @param escalationEventCode String，可空受控 BPMN 升级编码；@return void，无返回值。 */
    public void setEscalationEventCode(String escalationEventCode) { this.escalationEventCode = escalationEventCode; }
    /** @return String，部署操作人。 */
    public String getCreateBy() { return createBy; }
    /** @param createBy String，部署操作人；@return void，无返回值。 */
    public void setCreateBy(String createBy) { this.createBy = createBy; }
}
