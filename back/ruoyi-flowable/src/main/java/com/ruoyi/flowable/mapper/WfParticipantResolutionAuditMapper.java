package com.ruoyi.flowable.mapper;

import com.ruoyi.flowable.domain.WfParticipantResolutionAudit;

/**
 * 参与者规则解析审计数据访问层。
 */
public interface WfParticipantResolutionAuditMapper
{
    /**
     * 写入一次规则解析结果。
     * @param audit WfParticipantResolutionAudit，已脱敏且字段有界的审计记录
     * @return int，成功必须为 1
     */
    int insert(WfParticipantResolutionAudit audit);
}
