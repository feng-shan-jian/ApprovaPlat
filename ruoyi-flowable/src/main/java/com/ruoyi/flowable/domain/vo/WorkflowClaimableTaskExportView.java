package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import com.ruoyi.common.annotation.Excel;

/**
 * 当前用户可认领任务的导出视图。
 *
 * @param taskId String，任务主键
 * @param processName String，流程定义名称
 * @param taskName String，任务节点名称
 * @param version int，流程定义版本
 * @param startUserName String，流程发起人显示名称
 * @param createTime Instant，任务接收时间
 * @param dueTime Instant，任务到期时间，允许为空
 */
public record WorkflowClaimableTaskExportView(
        @Excel(name = "任务ID") String taskId,
        @Excel(name = "流程名称") String processName,
        @Excel(name = "任务节点") String taskName,
        @Excel(name = "流程版本", cellType = Excel.ColumnType.NUMERIC) int version,
        @Excel(name = "流程发起人") String startUserName,
        @Excel(name = "接收时间") Instant createTime,
        @Excel(name = "到期时间") Instant dueTime)
{
}
