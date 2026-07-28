package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import com.ruoyi.common.annotation.Excel;

/**
 * 当前用户真实完成历史任务的导出视图。
 *
 * @param taskId String，历史任务主键
 * @param processName String，流程定义名称
 * @param taskName String，任务节点名称
 * @param version int，流程定义版本
 * @param startUserName String，流程发起人显示名称
 * @param completedBy String，Flowable 记录的真实完成人主键
 * @param createTime Instant，任务接收时间
 * @param finishTime Instant，任务完成时间
 * @param durationMillis Long，任务耗时毫秒
 */
public record WorkflowCompletedTaskExportView(
        @Excel(name = "任务ID") String taskId,
        @Excel(name = "流程名称") String processName,
        @Excel(name = "任务节点") String taskName,
        @Excel(name = "流程版本", cellType = Excel.ColumnType.NUMERIC) int version,
        @Excel(name = "流程发起人") String startUserName,
        @Excel(name = "真实完成人ID") String completedBy,
        @Excel(name = "接收时间") Instant createTime,
        @Excel(name = "完成时间") Instant finishTime,
        @Excel(name = "耗时毫秒", cellType = Excel.ColumnType.NUMERIC) Long durationMillis)
{
}
