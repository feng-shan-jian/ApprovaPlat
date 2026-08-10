package com.ruoyi.flowable.mapper;

import com.ruoyi.flowable.domain.WfProcessDraftAudit;

/**
 * 流程申请草稿业务审计数据访问层。
 */
public interface WfProcessDraftAuditMapper
{
    /**
     * 在草稿业务事务中写入不含表单明文的审计记录。
     *
     * @param audit WfProcessDraftAudit，稳定状态迁移和摘要详情
     * @return int，成功写入返回 1
     */
    int insert(WfProcessDraftAudit audit);
}
