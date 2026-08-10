package com.ruoyi.flowable.domain.vo;

import java.time.LocalDateTime;

/**
 * BPMN 错误或升级运行审计视图。
 *
 * @param auditId Long，审计主键
 * @param processInstanceId String，流程实例主键
 * @param processDefinitionId String，流程定义主键
 * @param sourceElementId String，受控产生节点标识
 * @param sourceType String，SERVICE_TASK、HTTP、SQL、DMN 或 MANUAL
 * @param eventType String，ERROR 或 ESCALATION
 * @param eventCode String，稳定业务编码
 * @param eventName String，冻结名称
 * @param matchStatus String，CAPTURED 或 UNMATCHED
 * @param boundaryEventId String，匹配的边界事件标识
 * @param interrupting Boolean，是否中断附着活动
 * @param messageSummary String，脱敏业务摘要
 * @param createTime LocalDateTime，触发时间
 */
public record WorkflowBpmnEventAuditView(Long auditId, String processInstanceId,
        String processDefinitionId, String sourceElementId, String sourceType,
        String eventType, String eventCode, String eventName, String matchStatus,
        String boundaryEventId, Boolean interrupting, String messageSummary,
        LocalDateTime createTime)
{
}
