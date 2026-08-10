package com.ruoyi.flowable.domain;

/**
 * 流程模型保存请求的持久化幂等投影。
 *
 * @param requestId String，用户一次保存意图的稳定请求主键
 * @param userId String，事务内重新核验的规范工作流用户主键
 * @param sourceModelId String，保存请求最初指向的 Flowable 模型主键
 * @param payloadSha256 String，规范保存载荷的 SHA-256 小写十六进制摘要
 * @param savedModelId String，真实保存成功的 Flowable 模型主键；处理中为 null
 */
public record WorkflowModelSaveRecord(
        String requestId,
        String userId,
        String sourceModelId,
        String payloadSha256,
        String savedModelId)
{
}
