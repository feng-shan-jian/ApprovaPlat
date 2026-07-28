package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import com.ruoyi.common.annotation.Excel;

/**
 * 当前用户正式抄送记录的导出视图。
 *
 * @param copyId Long，抄送记录主键
 * @param title String，抄送标题
 * @param processName String，流程名称
 * @param categoryId String，流程分类编码
 * @param deploymentId String，部署主键
 * @param instanceId String，流程实例主键
 * @param taskId String，产生抄送的任务主键
 * @param originatorId Long，流程发起人主键
 * @param originatorName String，流程发起人名称快照
 * @param createTime Instant，抄送时间
 */
public record WorkflowCopyExportView(
        @Excel(name = "抄送ID", cellType = Excel.ColumnType.NUMERIC) Long copyId,
        @Excel(name = "抄送标题") String title,
        @Excel(name = "流程名称") String processName,
        @Excel(name = "分类编码") String categoryId,
        @Excel(name = "部署ID") String deploymentId,
        @Excel(name = "流程实例ID") String instanceId,
        @Excel(name = "任务ID") String taskId,
        @Excel(name = "发起人ID", cellType = Excel.ColumnType.NUMERIC) Long originatorId,
        @Excel(name = "发起人名称") String originatorName,
        @Excel(name = "抄送时间") Instant createTime)
{
}
