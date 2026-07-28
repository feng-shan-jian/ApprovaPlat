package com.ruoyi.flowable.domain;

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
