package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import com.ruoyi.common.annotation.Excel;

/**
 * 当前用户可发起流程定义的导出视图。
 *
 * @param definitionId String，流程定义主键
 * @param processName String，流程定义名称
 * @param processKey String，流程定义标识
 * @param category String，流程分类编码
 * @param version int，流程定义版本
 * @param deploymentId String，部署主键
 * @param deploymentTime Instant，部署时间
 */
public record WorkflowStartableDefinitionExportView(
        @Excel(name = "流程定义ID") String definitionId,
        @Excel(name = "流程名称") String processName,
        @Excel(name = "流程Key") String processKey,
        @Excel(name = "分类编码") String category,
        @Excel(name = "版本", cellType = Excel.ColumnType.NUMERIC) int version,
        @Excel(name = "部署ID") String deploymentId,
        @Excel(name = "部署时间") Instant deploymentTime)
{
}
