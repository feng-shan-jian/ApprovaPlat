package com.ruoyi.flowable.domain.vo;

/**
 * 外部连接器调用台账幂等领取结果。
 *
 * @param invocationId Long，调用台账主键
 * @param status String，当前状态
 * @param claimToken String，本次领取令牌；命中既有成功记录时为空
 * @param attemptCount Integer，累计尝试次数
 * @param resultCode Integer，成功记录的通用结果码；HTTP 使用状态码，SQL 使用 200
 */
public record WorkflowConnectorInvocationClaim(Long invocationId, String status,
        String claimToken, Integer attemptCount, Integer resultCode)
{
}
