package com.ruoyi.flowable.domain;

/**
 * 流程申请草稿业务审计写入对象。
 *
 * @param draftId String，草稿 UUID
 * @param ownerUserId Long，草稿所有者正式用户主键
 * @param actionType String，CREATED、SAVED、DELETED 或 SUBMITTED
 * @param fromStatus String，操作前状态，创建时为空
 * @param toStatus String，操作后状态
 * @param fromRevision Long，操作前版本，创建时为空
 * @param toRevision long，操作后版本
 * @param processInstanceId String，提交成功实例主键，其他动作为空
 * @param detailJson String，仅包含定义标识和摘要等非敏感审计详情
 */
public record WfProcessDraftAudit(String draftId, Long ownerUserId,
        String actionType, String fromStatus, String toStatus, Long fromRevision,
        long toRevision, String processInstanceId, String detailJson)
{
}
