package com.ruoyi.flowable.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorInvocationClaim;

/**
 * 外部连接器调用幂等台账数据访问层。
 */
public interface WfConnectorInvocationMapper
{
    /**
     * 首次创建稳定幂等记录；并发重复时不覆盖既有状态。
     * @param deploymentId String，Flowable 部署主键
     * @param processInstanceId String，流程实例主键
     * @param executionId String，活动执行主键
     * @param elementId String，BPMN 元素标识
     * @param connectorType String，连接器类型，例如 HTTP 或 SQL
     * @param targetKey String，冻结目标逻辑键
     * @param targetRevision Integer，冻结目标修订号
     * @param idempotencyKey String，稳定 SHA-256 幂等键
     * @param operation String，受控操作类型
     * @param targetSummary String，不含参数值的脱敏目标摘要
     * @return int，首次插入为 1，既有记录为 0
     */
    int insertIfAbsent(@Param("deploymentId") String deploymentId,
            @Param("processInstanceId") String processInstanceId,
            @Param("executionId") String executionId, @Param("elementId") String elementId,
            @Param("connectorType") String connectorType,
            @Param("targetKey") String targetKey,
            @Param("targetRevision") Integer targetRevision,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("operation") String operation,
            @Param("targetSummary") String targetSummary);

    /**
     * 领取未完成或租约已过期的调用记录并递增尝试次数。
     * @param idempotencyKey String，稳定幂等键
     * @param claimToken String，本次随机领取令牌
     * @param leaseSeconds int，执行租约秒数
     * @return int，领取成功为 1
     */
    int claim(@Param("idempotencyKey") String idempotencyKey,
            @Param("claimToken") String claimToken, @Param("leaseSeconds") int leaseSeconds);

    /**
     * 查询幂等记录当前状态。
     * @param idempotencyKey String，稳定幂等键
     * @return WorkflowConnectorInvocationClaim，当前状态视图
     */
    WorkflowConnectorInvocationClaim selectClaim(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 以领取令牌完成成功状态，防止过期执行覆盖新尝试。
     * @param invocationId Long，调用主键
     * @param claimToken String，本次领取令牌
     * @param durationMs long，调用耗时
     * @param resultCode int，通用结果码
     * @param resultSummary String，脱敏摘要
     * @return int，更新行数
     */
    int completeSuccess(@Param("invocationId") Long invocationId,
            @Param("claimToken") String claimToken, @Param("durationMs") long durationMs,
            @Param("resultCode") int resultCode,
            @Param("resultSummary") String resultSummary);

    /**
     * 以领取令牌记录失败并释放租约，供 Flowable 后续重试。
     * @param invocationId Long，调用主键
     * @param claimToken String，本次领取令牌
     * @param durationMs long，调用耗时
     * @param resultCode Integer，可空结果码
     * @param errorCode String，稳定错误码
     * @param resultSummary String，脱敏摘要
     * @return int，更新行数
     */
    int completeFailure(@Param("invocationId") Long invocationId,
            @Param("claimToken") String claimToken, @Param("durationMs") long durationMs,
            @Param("resultCode") Integer resultCode, @Param("errorCode") String errorCode,
            @Param("resultSummary") String resultSummary);
}
