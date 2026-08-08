package com.ruoyi.flowable.domain.dto;

/**
 * 当前用户抄送记录的查询条件，不包含可由客户端伪造的接收用户主键。
 *
 * @param title String，抄送标题模糊条件，允许为空
 * @param processId String，旧接口 processId 对应的流程定义主键精确条件，允许为空
 * @param processName String，流程名称模糊条件，允许为空
 * @param originatorName String，发起人名称模糊条件，允许为空
 * @param instanceId String，流程实例主键精确条件，允许为空
 * @param taskId String，任务主键精确条件，允许为空
 * @param categoryId String，流程分类编码精确条件，允许为空
 * @param deploymentId String，部署主键精确条件，允许为空
 * @param readStatus String，0 未读、1 已读，允许为空
 */
public record WorkflowCopyQueryDto(
        String title,
        String processId,
        String processName,
        String originatorName,
        String instanceId,
        String taskId,
        String categoryId,
        String deploymentId,
        String readStatus)
{
    /**
     * 保留旧调用方的八参数构造方式，未显式筛选时查询全部阅读状态。
     * @param title String，抄送标题
     * @param processId String，流程定义主键
     * @param processName String，流程名称
     * @param originatorName String，发起人名称
     * @param instanceId String，流程实例主键
     * @param taskId String，任务主键
     * @param categoryId String，分类编码
     * @param deploymentId String，部署主键
     */
    public WorkflowCopyQueryDto(String title, String processId, String processName,
            String originatorName, String instanceId, String taskId,
            String categoryId, String deploymentId)
    {
        this(title, processId, processName, originatorName, instanceId, taskId,
                categoryId, deploymentId, null);
    }
}
