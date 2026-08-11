package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 受控重复审批循环的不可变部署快照，保存在 Flowable 业务制品资源
 * {@code approvaplat/controlled-loops-v1.json}。
 */
public class WfDeployControlledLoop
{
    private Long loopId;
    private String deployId;
    private String processKey;
    private String activityId;
    private String activityName;
    private String decisionVariable;
    private String repeatValue;
    private String exitValue;
    private Integer maxIterations;
    private String routeVariable;
    private String iterationVariable;
    private String createBy;
    private Date createTime;

    /** @return Long，部署快照主键。 */
    public Long getLoopId() { return loopId; }
    /** @param value Long，部署快照主键。@return void，无返回值。 */
    public void setLoopId(Long value) { loopId = value; }
    /** @return String，Flowable 部署主键。 */
    public String getDeployId() { return deployId; }
    /** @param value String，Flowable 部署主键。@return void，无返回值。 */
    public void setDeployId(String value) { deployId = value; }
    /** @return String，可执行流程标识。 */
    public String getProcessKey() { return processKey; }
    /** @param value String，可执行流程标识。@return void，无返回值。 */
    public void setProcessKey(String value) { processKey = value; }
    /** @return String，循环用户任务标识。 */
    public String getActivityId() { return activityId; }
    /** @param value String，循环用户任务标识。@return void，无返回值。 */
    public void setActivityId(String value) { activityId = value; }
    /** @return String，部署时节点名称。 */
    public String getActivityName() { return activityName; }
    /** @param value String，部署时节点名称。@return void，无返回值。 */
    public void setActivityName(String value) { activityName = value; }
    /** @return String，循环判断表单变量。 */
    public String getDecisionVariable() { return decisionVariable; }
    /** @param value String，循环判断表单变量。@return void，无返回值。 */
    public void setDecisionVariable(String value) { decisionVariable = value; }
    /** @return String，再次进入条件值。 */
    public String getRepeatValue() { return repeatValue; }
    /** @param value String，再次进入条件值。@return void，无返回值。 */
    public void setRepeatValue(String value) { repeatValue = value; }
    /** @return String，退出条件值。 */
    public String getExitValue() { return exitValue; }
    /** @param value String，退出条件值。@return void，无返回值。 */
    public void setExitValue(String value) { exitValue = value; }
    /** @return Integer，最大完成轮次。 */
    public Integer getMaxIterations() { return maxIterations; }
    /** @param value Integer，最大完成轮次。@return void，无返回值。 */
    public void setMaxIterations(Integer value) { maxIterations = value; }
    /** @return String，编译网关路由变量。 */
    public String getRouteVariable() { return routeVariable; }
    /** @param value String，编译网关路由变量。@return void，无返回值。 */
    public void setRouteVariable(String value) { routeVariable = value; }
    /** @return String，已完成轮次变量。 */
    public String getIterationVariable() { return iterationVariable; }
    /** @param value String，已完成轮次变量。@return void，无返回值。 */
    public void setIterationVariable(String value) { iterationVariable = value; }
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
     * @param value Date，允许为空的时间
     * @return Date，时间副本或 null
     */
    private Date copy(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
