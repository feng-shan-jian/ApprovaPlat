package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import com.ruoyi.common.annotation.Excel;

/**
 * 流程管理员跨用户实例运维导出视图。
 *
 * @param processInstanceId String，流程实例主键
 * @param processName String，流程定义名称
 * @param category String，流程分类编码
 * @param version int，流程定义版本
 * @param businessKey String，业务主键，允许为空
 * @param startUserId String，流程发起人主键
 * @param startUserName String，流程发起人显示名称
 * @param startTime Instant，流程开始时间
 * @param endTime Instant，流程结束时间，运行中为空
 * @param processStatus String，稳定流程状态
 * @param durationMillis Long，流程耗时毫秒，运行中为空
 * @param currentTaskNames String，当前活动任务名称
 */
public record WorkflowManagedProcessExportView(
        @Excel(name = "流程实例ID") String processInstanceId,
        @Excel(name = "流程名称") String processName,
        @Excel(name = "分类编码") String category,
        @Excel(name = "流程版本", cellType = Excel.ColumnType.NUMERIC) int version,
        @Excel(name = "业务主键") String businessKey,
        @Excel(name = "发起人ID") String startUserId,
        @Excel(name = "发起人") String startUserName,
        @Excel(name = "提交时间") Instant startTime,
        @Excel(name = "完成时间") Instant endTime,
        @Excel(name = "流程状态") String processStatus,
        @Excel(name = "耗时毫秒", cellType = Excel.ColumnType.NUMERIC) Long durationMillis,
        @Excel(name = "当前节点") String currentTaskNames)
{
}
