package com.ruoyi.flowable.domain.vo;

/**
 * 历史任务候选身份的最小只读视图。
 *
 * @param identityType String，user 或 group
 * @param identityId String，Flowable 中持久化的用户或 ROLE/DEPT 组标识
 */
public record WorkflowCandidateIdentityView(String identityType, String identityId)
{
}
