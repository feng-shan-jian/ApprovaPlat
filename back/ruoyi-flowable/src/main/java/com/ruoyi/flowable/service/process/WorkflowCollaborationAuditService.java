package com.ruoyi.flowable.service.process;

import java.util.List;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCollaborationMessageAudit;
import com.ruoyi.flowable.mapper.WfCollaborationMessageAuditMapper;

/** 协作消息逐次状态审计服务，所有摘要均禁止包含 Token 和业务正文。 */
@Service
public class WorkflowCollaborationAuditService
{
    private final WfCollaborationMessageAuditMapper mapper;

    /**
     * 创建协作消息审计服务。
     * @param mapper WfCollaborationMessageAuditMapper，正式审计数据访问层
     * @return void，构造后由 Spring 管理
     */
    public WorkflowCollaborationAuditService(WfCollaborationMessageAuditMapper mapper)
    {
        this.mapper = mapper;
    }

    /**
     * 在调用方业务事务内写入一次状态迁移审计。
     * @param messageId String，消息主键
     * @param direction String，INBOUND 或 OUTBOUND
     * @param action String，稳定动作编码
     * @param actorType String，INTEGRATION、SYSTEM 或 USER
     * @param actorId String，凭据、worker 或用户标识
     * @param fromStatus String，可空迁移前状态
     * @param toStatus String，迁移后状态
     * @param attemptNo int，本次投递次数
     * @param errorCode String，可空失败编码
     * @param summary String，不含敏感正文的摘要
     * @return void，审计未写入时回滚调用方事务
     */
    public void record(String messageId, String direction, String action,
            String actorType, String actorId, String fromStatus, String toStatus,
            int attemptNo, String errorCode, String summary)
    {
        WfCollaborationMessageAudit audit = new WfCollaborationMessageAudit();
        audit.setMessageId(messageId);
        audit.setDirection(direction);
        audit.setAction(action);
        audit.setActorType(actorType);
        audit.setActorId(actorId == null ? "" : truncate(actorId, 64));
        audit.setFromStatus(fromStatus);
        audit.setToStatus(toStatus);
        audit.setAttemptNo(Math.max(0, attemptNo));
        audit.setErrorCode(errorCode);
        audit.setSummary(truncate(summary == null ? "" : summary, 512));
        if (mapper.insert(audit) != 1)
        {
            throw new ServiceException("协作消息审计写入不完整", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_AUDIT_INSERT_FAILED");
        }
    }

    /**
     * 查询单条消息的完整状态迁移历史。
     * @param messageId String，消息主键
     * @return List&lt;WfCollaborationMessageAudit&gt;，按审计主键升序的脱敏历史
     */
    public List<WfCollaborationMessageAudit> list(String messageId)
    {
        if (messageId == null || messageId.isBlank())
        {
            throw new ServiceException("协作消息主键不能为空", HttpStatus.BAD_REQUEST);
        }
        return List.copyOf(mapper.selectByMessageId(messageId.trim()));
    }

    /**
     * 截断外部或异常摘要，避免数据库字段溢出。
     * @param value String，待截断文本
     * @param max int，最大字符数
     * @return String，不超过上限的文本
     */
    private String truncate(String value, int max)
    {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
