package com.ruoyi.flowable.domain;

import java.util.Date;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 工作流抄送记录，对应业务表 {@code wf_copy}。
 */
public class WfCopy extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 抄送记录主键。 */
    private Long copyId;

    /** 同一次抄送业务事件的稳定幂等键。 */
    private String copyEventId;

    /** 抄送标题。 */
    private String title;

    /** 流程定义主键。 */
    private String processId;

    /** 流程名称。 */
    private String processName;

    /** 流程分类编码。 */
    private String categoryId;

    /** Flowable 部署主键。 */
    private String deploymentId;

    /** 流程实例主键。 */
    private String instanceId;

    /** 产生抄送的任务主键。 */
    private String taskId;

    /** 抄送接收用户主键。 */
    private Long userId;

    /** 流程发起用户主键。 */
    private Long originatorId;

    /** 流程发起用户名称快照。 */
    private String originatorName;

    /** 抄送来源：MANUAL 或 AUTO。 */
    private String sourceType;

    /** 触发类型，如 NODE_ARRIVED、NODE_COMPLETED 或 PROCESS_COMPLETED。 */
    private String triggerType;

    /** 触发抄送的 BPMN 节点主键；流程完成时为空。 */
    private String triggerNodeId;

    /** 触发抄送的 BPMN 节点名称快照；流程完成时为空。 */
    private String triggerNodeName;

    /** 阅读状态：0 表示未读，1 表示已读。 */
    private String readStatus;

    /** 首次阅读时间，由数据库条件更新原子写入。 */
    private Date readTime;

    /** 逻辑删除标志：0 表示有效，2 表示已删除。 */
    private String delFlag;

    /**
     * 获取抄送记录主键。
     * @return Long，抄送记录主键
     */
    public Long getCopyId()
    {
        return copyId;
    }

    /**
     * 设置抄送记录主键。
     * @param copyId Long，抄送记录主键
     * @return void，无返回值
     */
    public void setCopyId(Long copyId)
    {
        this.copyId = copyId;
    }

    /**
     * 获取抄送业务事件幂等键。
     * @return String，抄送业务事件幂等键
     */
    public String getCopyEventId()
    {
        return copyEventId;
    }

    /**
     * 设置抄送业务事件幂等键。
     * @param copyEventId String，抄送业务事件幂等键
     * @return void，无返回值
     */
    public void setCopyEventId(String copyEventId)
    {
        this.copyEventId = copyEventId;
    }

    /**
     * 获取抄送标题。
     * @return String，抄送标题
     */
    public String getTitle()
    {
        return title;
    }

    /**
     * 设置抄送标题。
     * @param title String，抄送标题
     * @return void，无返回值
     */
    public void setTitle(String title)
    {
        this.title = title;
    }

    /**
     * 获取流程定义主键。
     * @return String，流程定义主键
     */
    public String getProcessId()
    {
        return processId;
    }

    /**
     * 设置流程定义主键。
     * @param processId String，流程定义主键
     * @return void，无返回值
     */
    public void setProcessId(String processId)
    {
        this.processId = processId;
    }

    /**
     * 获取流程名称。
     * @return String，流程名称
     */
    public String getProcessName()
    {
        return processName;
    }

    /**
     * 设置流程名称。
     * @param processName String，流程名称
     * @return void，无返回值
     */
    public void setProcessName(String processName)
    {
        this.processName = processName;
    }

    /**
     * 获取流程分类编码。
     * @return String，流程分类编码
     */
    public String getCategoryId()
    {
        return categoryId;
    }

    /**
     * 设置流程分类编码。
     * @param categoryId String，流程分类编码
     * @return void，无返回值
     */
    public void setCategoryId(String categoryId)
    {
        this.categoryId = categoryId;
    }

    /**
     * 获取部署主键。
     * @return String，Flowable 部署主键
     */
    public String getDeploymentId()
    {
        return deploymentId;
    }

    /**
     * 设置部署主键。
     * @param deploymentId String，Flowable 部署主键
     * @return void，无返回值
     */
    public void setDeploymentId(String deploymentId)
    {
        this.deploymentId = deploymentId;
    }

    /**
     * 获取流程实例主键。
     * @return String，流程实例主键
     */
    public String getInstanceId()
    {
        return instanceId;
    }

    /**
     * 设置流程实例主键。
     * @param instanceId String，流程实例主键
     * @return void，无返回值
     */
    public void setInstanceId(String instanceId)
    {
        this.instanceId = instanceId;
    }

    /**
     * 获取任务主键。
     * @return String，产生抄送的任务主键
     */
    public String getTaskId()
    {
        return taskId;
    }

    /**
     * 设置任务主键。
     * @param taskId String，产生抄送的任务主键
     * @return void，无返回值
     */
    public void setTaskId(String taskId)
    {
        this.taskId = taskId;
    }

    /**
     * 获取抄送接收用户主键。
     * @return Long，抄送接收用户主键
     */
    public Long getUserId()
    {
        return userId;
    }

    /**
     * 设置抄送接收用户主键。
     * @param userId Long，抄送接收用户主键
     * @return void，无返回值
     */
    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    /**
     * 获取流程发起用户主键。
     * @return Long，流程发起用户主键
     */
    public Long getOriginatorId()
    {
        return originatorId;
    }

    /**
     * 设置流程发起用户主键。
     * @param originatorId Long，流程发起用户主键
     * @return void，无返回值
     */
    public void setOriginatorId(Long originatorId)
    {
        this.originatorId = originatorId;
    }

    /**
     * 获取流程发起用户名称快照。
     * @return String，流程发起用户名称快照
     */
    public String getOriginatorName()
    {
        return originatorName;
    }

    /**
     * 设置流程发起用户名称快照。
     * @param originatorName String，流程发起用户名称快照
     * @return void，无返回值
     */
    public void setOriginatorName(String originatorName)
    {
        this.originatorName = originatorName;
    }

    /** @return String，MANUAL 或 AUTO 抄送来源。 */
    public String getSourceType()
    {
        return sourceType;
    }

    /** @param sourceType String，MANUAL 或 AUTO 抄送来源；@return void，无返回值。 */
    public void setSourceType(String sourceType)
    {
        this.sourceType = sourceType;
    }

    /** @return String，服务端固定触发类型。 */
    public String getTriggerType()
    {
        return triggerType;
    }

    /** @param triggerType String，服务端固定触发类型；@return void，无返回值。 */
    public void setTriggerType(String triggerType)
    {
        this.triggerType = triggerType;
    }

    /** @return String，触发节点主键；流程级规则返回 null。 */
    public String getTriggerNodeId()
    {
        return triggerNodeId;
    }

    /** @param triggerNodeId String，触发节点主键；@return void，无返回值。 */
    public void setTriggerNodeId(String triggerNodeId)
    {
        this.triggerNodeId = triggerNodeId;
    }

    /** @return String，触发节点名称快照；流程级规则返回 null。 */
    public String getTriggerNodeName()
    {
        return triggerNodeName;
    }

    /** @param triggerNodeName String，触发节点名称快照；@return void，无返回值。 */
    public void setTriggerNodeName(String triggerNodeName)
    {
        this.triggerNodeName = triggerNodeName;
    }

    /** @return String，0 表示未读，1 表示已读。 */
    public String getReadStatus()
    {
        return readStatus;
    }

    /** @param readStatus String，0 或 1 阅读状态；@return void，无返回值。 */
    public void setReadStatus(String readStatus)
    {
        this.readStatus = readStatus;
    }

    /** @return Date，首次阅读时间；未读时为 null。 */
    public Date getReadTime()
    {
        return readTime;
    }

    /** @param readTime Date，首次阅读时间；@return void，无返回值。 */
    public void setReadTime(Date readTime)
    {
        this.readTime = readTime;
    }

    /**
     * 获取逻辑删除标志。
     * @return String，0 表示有效，2 表示已删除
     */
    public String getDelFlag()
    {
        return delFlag;
    }

    /**
     * 设置逻辑删除标志。
     * @param delFlag String，逻辑删除标志
     * @return void，无返回值
     */
    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }
}
