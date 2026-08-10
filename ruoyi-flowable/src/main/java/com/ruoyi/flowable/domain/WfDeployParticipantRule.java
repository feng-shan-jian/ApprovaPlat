package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 流程发起范围或单实例用户任务办理人规则的不可变部署快照。
 */
public class WfDeployParticipantRule
{
    private Long ruleId;
    private String deployId;
    private String processKey;
    private String activityId;
    private String activityName;
    private String ruleScope;
    private String assignmentMode;
    private String ruleType;
    private String targetIds;
    private String formField;
    private String noMatchPolicy;
    private Integer ruleVersion;
    private String checksum;
    private String createBy;
    private Date createTime;

    /** @return Long，规则快照主键。 */
    public Long getRuleId() { return ruleId; }
    /** @param value Long，规则快照主键。@return void，无返回值。 */
    public void setRuleId(Long value) { ruleId = value; }
    /** @return String，Flowable 部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，Flowable 部署主键。@return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，BPMN 可执行流程标识。 */
    public String getProcessKey() { return processKey; }
    /** @param value String，BPMN 可执行流程标识。@return void，无返回值。 */
    public void setProcessKey(String value) { processKey = value; }
    /** @return String，任务节点标识；发起范围为空。 */
    public String getActivityId() { return activityId; }
    /** @param value String，任务节点标识；发起范围为空。@return void，无返回值。 */
    public void setActivityId(String value) { activityId = value; }
    /** @return String，部署时任务名称；发起范围为空。 */
    public String getActivityName() { return activityName; }
    /** @param value String，部署时任务名称。@return void，无返回值。 */
    public void setActivityName(String value) { activityName = value; }
    /** @return String，START 或 TASK 规则作用域。 */
    public String getRuleScope() { return ruleScope; }
    /** @param value String，规则作用域。@return void，无返回值。 */
    public void setRuleScope(String value) { ruleScope = value; }
    /** @return String，START、ASSIGNEE 或 CANDIDATE。 */
    public String getAssignmentMode() { return assignmentMode; }
    /** @param value String，规则输出模式。@return void，无返回值。 */
    public void setAssignmentMode(String value) { assignmentMode = value; }
    /** @return String，受控规则类型。 */
    public String getRuleType() { return ruleType; }
    /** @param value String，受控规则类型。@return void，无返回值。 */
    public void setRuleType(String value) { ruleType = value; }
    /** @return String，逗号分隔的规范用户、角色或部门主键。 */
    public String getTargetIds() { return targetIds; }
    /** @param value String，规范目标主键列表。@return void，无返回值。 */
    public void setTargetIds(String value) { targetIds = value; }
    /** @return String，表单用户变量名。 */
    public String getFormField() { return formField; }
    /** @param value String，表单用户变量名。@return void，无返回值。 */
    public void setFormField(String value) { formField = value; }
    /** @return String，无匹配策略，当前固定为 FAIL。 */
    public String getNoMatchPolicy() { return noMatchPolicy; }
    /** @param value String，无匹配策略。@return void，无返回值。 */
    public void setNoMatchPolicy(String value) { noMatchPolicy = value; }
    /** @return Integer，冻结的规则协议版本。 */
    public Integer getRuleVersion() { return ruleVersion; }
    /** @param value Integer，规则协议版本。@return void，无返回值。 */
    public void setRuleVersion(Integer value) { ruleVersion = value; }
    /** @return String，规范规则内容 SHA-256。 */
    public String getChecksum() { return checksum; }
    /** @param value String，规范规则内容 SHA-256。@return void，无返回值。 */
    public void setChecksum(String value) { checksum = value; }
    /** @return String，部署操作人主键。 */
    public String getCreateBy() { return createBy; }
    /** @param value String，部署操作人主键。@return void，无返回值。 */
    public void setCreateBy(String value) { createBy = value; }
    /** @return Date，快照创建时间副本。 */
    public Date getCreateTime() { return copy(createTime); }
    /** @param value Date，快照创建时间。@return void，无返回值。 */
    public void setCreateTime(Date value) { createTime = copy(value); }

    /**
     * 防御复制可变时间对象。
     * @param value Date，可为空的时间
     * @return Date，时间副本或 null
     */
    private Date copy(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
