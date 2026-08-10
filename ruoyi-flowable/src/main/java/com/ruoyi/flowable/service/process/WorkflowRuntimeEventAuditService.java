package com.ruoyi.flowable.service.process;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfRuntimeEventRequest;
import com.ruoyi.flowable.mapper.WfRuntimeEventRequestMapper;

/**
 * 在主引擎事务回滚后仍可持久化失败结果的独立运行事件审计服务。
 */
@Service
public class WorkflowRuntimeEventAuditService
{
    private final WfRuntimeEventRequestMapper requestMapper;

    /**
     * 创建运行事件失败审计服务。
     * @param requestMapper WfRuntimeEventRequestMapper，正式运行事件台账 Mapper
     * @return void，构造后由 Spring 管理
     */
    public WorkflowRuntimeEventAuditService(WfRuntimeEventRequestMapper requestMapper)
    {
        this.requestMapper = requestMapper;
    }

    /**
     * 使用新事务记录失败；已有同 requestId 时只允许同载荷失败重放。
     * @param failure WfRuntimeEventRequest，完整失败请求摘要和稳定结果
     * @return void，首次失败落库，重复同载荷保持原记录
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class,
            isolation = Isolation.REPEATABLE_READ)
    public void recordFailure(WfRuntimeEventRequest failure)
    {
        WfRuntimeEventRequest existing = requestMapper.selectByIdForUpdate(failure.getRequestId());
        if (existing == null)
        {
            if (requestMapper.insertFailed(failure) != 1)
            {
                throw new ServiceException("运行事件失败台账保存不完整", HttpStatus.CONFLICT);
            }
            return;
        }
        if (!sameRequest(existing, failure))
        {
            // 冲突 requestId 必须保留首次请求，不允许失败审计覆盖既有证据。
            return;
        }
    }

    /**
     * 比较幂等键之外的完整规范请求签名。
     * @param left WfRuntimeEventRequest，已落库请求
     * @param right WfRuntimeEventRequest，本次失败请求
     * @return boolean，凭据、事件、关联条件和摘要全部一致时返回 true
     */
    private boolean sameRequest(WfRuntimeEventRequest left, WfRuntimeEventRequest right)
    {
        return java.util.Objects.equals(left.getCredentialId(), right.getCredentialId())
                && java.util.Objects.equals(left.getEventType(), right.getEventType())
                && java.util.Objects.equals(left.getEventName(), right.getEventName())
                && java.util.Objects.equals(left.getCorrelationType(), right.getCorrelationType())
                && java.util.Objects.equals(left.getCorrelationValue(), right.getCorrelationValue())
                && java.util.Objects.equals(left.getVariablesSha256(), right.getVariablesSha256());
    }
}
