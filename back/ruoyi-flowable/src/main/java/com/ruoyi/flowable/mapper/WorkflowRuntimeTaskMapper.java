package com.ruoyi.flowable.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * Flowable 活动任务内部元数据的只读数据访问层，仅承载公共 Task API 未暴露的并发 revision。
 */
public interface WorkflowRuntimeTaskMapper
{
    /**
     * 查询活动任务当前持久化 revision，供任务动作生成稳定且可重试的抄送事件键。
     *
     * @param taskId String，已经完成活动态和对象权限校验的任务主键
     * @return Integer，ACT_RU_TASK 当前 revision；任务已变化或不存在时返回 null
     */
    Integer selectActiveTaskRevision(@Param("taskId") String taskId);
}
