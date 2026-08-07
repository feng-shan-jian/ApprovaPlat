package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCollaborationMessageAudit;

/** 协作消息逐次审计 Mapper。 */
public interface WfCollaborationMessageAuditMapper
{
    int insert(WfCollaborationMessageAudit audit);
    List<WfCollaborationMessageAudit> selectByMessageId(@Param("messageId") String messageId);
}
