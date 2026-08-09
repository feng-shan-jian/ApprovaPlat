package com.ruoyi.flowable.service.identity;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfDeployParticipantRule;
import com.ruoyi.flowable.domain.WfParticipantResolutionAudit;
import com.ruoyi.flowable.mapper.WfParticipantResolutionAuditMapper;

/**
 * 在正式业务表中记录参与者规则成功解析和失败拒绝结果。
 */
@Service
public class WorkflowParticipantResolutionAuditService
{
    private final WfParticipantResolutionAuditMapper auditMapper;

    /**
     * 创建参与者解析审计服务。
     * @param auditMapper WfParticipantResolutionAuditMapper，正式审计 Mapper
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowParticipantResolutionAuditService(
            WfParticipantResolutionAuditMapper auditMapper)
    {
        this.auditMapper = auditMapper;
    }

    /**
     * 在当前业务事务中记录成功解析；审计失败必须使任务或发起整体回滚。
     * @param audit WfParticipantResolutionAudit，字段完整的解析结果
     * @return void，写入行数异常时抛出 409
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(WfParticipantResolutionAudit audit)
    {
        if (auditMapper.insert(audit) != 1)
        {
            throw new ServiceException("参与者规则解析审计保存失败", HttpStatus.CONFLICT);
        }
    }

    /**
     * 使用独立事务记录将导致主事务回滚的拒绝结果，避免无匹配原因随任务创建一起消失。
     * @param rule WfDeployParticipantRule，命中的不可变规则快照
     * @param eventType String，START 或 TASK
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，可为空的流程实例主键
     * @param taskId String，可为空的任务主键
     * @param initiatorUserId String，发起人主键
     * @param actorUserId String，可为空的当前操作人主键
     * @param resultCode String，DENIED 或 NO_MATCH
     * @param summary String，稳定且脱敏的拒绝摘要
     * @return void，审计写入失败时向上抛出，禁止伪装成已审计拒绝
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordRejected(WfDeployParticipantRule rule, String eventType,
            String processDefinitionId, String processInstanceId, String taskId,
            String initiatorUserId, String actorUserId, String resultCode, String summary)
    {
        WfParticipantResolutionAudit audit = base(rule, eventType, processDefinitionId,
                processInstanceId, taskId, initiatorUserId, actorUserId, resultCode, summary);
        record(audit);
    }

    /**
     * 构造字段完整的审计对象，调用方再补解析身份列表。
     * @param rule WfDeployParticipantRule，部署规则
     * @param eventType String，START 或 TASK
     * @param processDefinitionId String，流程定义主键
     * @param processInstanceId String，可为空的实例主键
     * @param taskId String，可为空的任务主键
     * @param initiatorUserId String，发起人主键
     * @param actorUserId String，可为空的操作人主键
     * @param resultCode String，结果码
     * @param summary String，脱敏摘要
     * @return WfParticipantResolutionAudit，可直接写入的审计对象
     */
    public WfParticipantResolutionAudit base(WfDeployParticipantRule rule, String eventType,
            String processDefinitionId, String processInstanceId, String taskId,
            String initiatorUserId, String actorUserId, String resultCode, String summary)
    {
        WfParticipantResolutionAudit audit = new WfParticipantResolutionAudit();
        audit.setEventType(eventType);
        audit.setDeployId(rule.getDeployId());
        audit.setProcessDefinitionId(processDefinitionId);
        audit.setProcessInstanceId(processInstanceId);
        audit.setTaskId(taskId);
        audit.setActivityId(rule.getActivityId());
        audit.setRuleId(rule.getRuleId());
        audit.setInitiatorUserId(initiatorUserId);
        audit.setActorUserId(actorUserId);
        audit.setResolvedUserIds("");
        audit.setResolvedGroupIds("");
        audit.setResultCode(resultCode);
        audit.setDetailSummary(summary == null ? "" : summary);
        return audit;
    }
}
