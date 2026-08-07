package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 受控重复审批循环单轮运行审计，对应 {@code wf_controlled_loop_execution}。
 */
public class WfControlledLoopExecution
{
    private Long executionId;
    private String deployId;
    private String processDefinitionId;
    private String processInstanceId;
    private String activityId;
    private String taskId;
    private Integer iterationNo;
    private String actorUserId;
    private String decisionValue;
    private String outcome;
    private Date createTime;

    /** @return Long，循环轮次审计主键。 */
    public Long getExecutionId() { return executionId; }
    /** @param value Long，循环轮次审计主键。@return void，无返回值。 */
    public void setExecutionId(Long value) { executionId = value; }
    /** @return String，Flowable 部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，Flowable 部署主键。@return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，流程定义主键。 */
    public String getProcessDefinitionId() { return processDefinitionId; }
    /** @param value String，流程定义主键。@return void，无返回值。 */
    public void setProcessDefinitionId(String value) { processDefinitionId = value; }
    /** @return String，流程实例主键。 */
    public String getProcessInstanceId() { return processInstanceId; }
    /** @param value String，流程实例主键。@return void，无返回值。 */
    public void setProcessInstanceId(String value) { processInstanceId = value; }
    /** @return String，循环用户任务标识。 */
    public String getActivityId() { return activityId; }
    /** @param value String，循环用户任务标识。@return void，无返回值。 */
    public void setActivityId(String value) { activityId = value; }
    /** @return String，本轮真实任务主键。 */
    public String getTaskId() { return taskId; }
    /** @param value String，本轮真实任务主键。@return void，无返回值。 */
    public void setTaskId(String value) { taskId = value; }
    /** @return Integer，从 1 开始的完成轮次。 */
    public Integer getIterationNo() { return iterationNo; }
    /** @param value Integer，从 1 开始的完成轮次。@return void，无返回值。 */
    public void setIterationNo(Integer value) { iterationNo = value; }
    /** @return String，本轮真实完成人主键。 */
    public String getActorUserId() { return actorUserId; }
    /** @param value String，本轮真实完成人主键。@return void，无返回值。 */
    public void setActorUserId(String value) { actorUserId = value; }
    /** @return String，经表单 schema 校验的判断值。 */
    public String getDecisionValue() { return decisionValue; }
    /** @param value String，经表单 schema 校验的判断值。@return void，无返回值。 */
    public void setDecisionValue(String value) { decisionValue = value; }
    /** @return String，REPEAT 或 EXIT。 */
    public String getOutcome() { return outcome; }
    /** @param value String，REPEAT 或 EXIT。@return void，无返回值。 */
    public void setOutcome(String value) { outcome = value; }
    /** @return Date，本轮完成时间副本。 */
    public Date getCreateTime() { return copy(createTime); }
    /** @param value Date，本轮完成时间。@return void，无返回值。 */
    public void setCreateTime(Date value) { createTime = copy(value); }

    /**
     * 防御复制可变时间对象。
     * @param value Date，允许为空的时间
     * @return Date，时间副本或 null
     */
    private Date copy(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
