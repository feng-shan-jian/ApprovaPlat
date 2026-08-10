package com.ruoyi.flowable.service.process;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorInvocationClaim;
import com.ruoyi.flowable.mapper.WfConnectorInvocationMapper;

/**
 * 外部副作用连接器调用台账与幂等租约服务。
 */
@Service
public class WorkflowConnectorInvocationService
{
    /** 单次外部调用租约，必须长于连接器允许的最大请求超时。 */
    private static final int CLAIM_LEASE_SECONDS = 180;

    private final WfConnectorInvocationMapper invocationMapper;

    /**
     * 创建调用台账服务。
     * @param invocationMapper WfConnectorInvocationMapper，幂等调用数据访问层
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowConnectorInvocationService(WfConnectorInvocationMapper invocationMapper)
    {
        this.invocationMapper = invocationMapper;
    }

    /**
     * 在独立事务中创建或领取稳定幂等记录。
     * @param deploymentId String，部署主键
     * @param processInstanceId String，流程实例主键
     * @param executionId String，执行主键
     * @param elementId String，BPMN 元素标识
     * @param connectorType String，连接器类型
     * @param targetKey String，冻结目标逻辑键
     * @param targetRevision int，冻结目标修订号
     * @param idempotencyKey String，稳定幂等键
     * @param operation String，受控操作类型
     * @param targetSummary String，不含业务值的脱敏目标摘要
     * @return WorkflowConnectorInvocationClaim，成功复用或本次领取结果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WorkflowConnectorInvocationClaim begin(String deploymentId, String processInstanceId,
            String executionId, String elementId, String connectorType, String targetKey,
            int targetRevision, String idempotencyKey, String operation, String targetSummary)
    {
        invocationMapper.insertIfAbsent(deploymentId, processInstanceId, executionId, elementId,
                connectorType, targetKey, targetRevision, idempotencyKey, operation, targetSummary);
        WorkflowConnectorInvocationClaim existing = requireClaim(idempotencyKey);
        if ("SUCCESS".equals(existing.status()))
        {
            return new WorkflowConnectorInvocationClaim(existing.invocationId(), existing.status(),
                    null, existing.attemptCount(), existing.resultCode());
        }
        String claimToken = UUID.randomUUID().toString();
        if (invocationMapper.claim(idempotencyKey, claimToken, CLAIM_LEASE_SECONDS) != 1)
        {
            throw new ServiceException("连接器调用正在由另一执行处理", HttpStatus.CONFLICT);
        }
        WorkflowConnectorInvocationClaim claimed = requireClaim(idempotencyKey);
        if (!claimToken.equals(claimed.claimToken()) || !"RUNNING".equals(claimed.status()))
        {
            throw new ServiceException("连接器调用领取结果不一致", HttpStatus.CONFLICT);
        }
        return claimed;
    }

    /**
     * 在独立事务中提交成功台账。
     * @param claim WorkflowConnectorInvocationClaim，本次领取结果
     * @param durationMs long，调用耗时
     * @param resultCode int，通用结果码
     * @param summary String，脱敏结果摘要
     * @return void，令牌过期时拒绝覆盖
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void success(WorkflowConnectorInvocationClaim claim, long durationMs,
            int resultCode, String summary)
    {
        if (invocationMapper.completeSuccess(claim.invocationId(), claim.claimToken(), durationMs,
                resultCode, summary) != 1)
        {
            throw new ServiceException("连接器成功台账提交失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 在独立事务中记录失败并释放租约。
     * @param claim WorkflowConnectorInvocationClaim，本次领取结果
     * @param durationMs long，调用耗时
     * @param resultCode Integer，可空结果码
     * @param errorCode String，稳定错误码
     * @param summary String，脱敏结果摘要
     * @return void，令牌过期时拒绝覆盖
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failure(WorkflowConnectorInvocationClaim claim, long durationMs,
            Integer resultCode, String errorCode, String summary)
    {
        if (invocationMapper.completeFailure(claim.invocationId(), claim.claimToken(), durationMs,
                resultCode, errorCode, summary) != 1)
        {
            throw new ServiceException("连接器失败台账提交失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 查询必须存在的幂等台账。
     * @param idempotencyKey String，稳定幂等键
     * @return WorkflowConnectorInvocationClaim，当前台账状态
     */
    private WorkflowConnectorInvocationClaim requireClaim(String idempotencyKey)
    {
        WorkflowConnectorInvocationClaim claim = invocationMapper.selectClaim(idempotencyKey);
        if (claim == null)
        {
            throw new ServiceException("连接器调用台账不存在", HttpStatus.ERROR);
        }
        return claim;
    }
}
