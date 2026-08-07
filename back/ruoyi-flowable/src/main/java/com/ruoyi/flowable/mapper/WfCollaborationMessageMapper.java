package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCollaborationMessage;

/** 协作消息正式台账 Mapper。 */
public interface WfCollaborationMessageMapper
{
    List<WfCollaborationMessage> selectList();
    WfCollaborationMessage selectByIdForUpdate(@Param("messageId") String messageId);
    WfCollaborationMessage selectById(@Param("messageId") String messageId);
    WfCollaborationMessage selectNextDueForUpdate();
    int insertReceived(WfCollaborationMessage row);
    int markProcessed(WfCollaborationMessage row);
    int markFailed(WfCollaborationMessage row);
    int markDeadLetter(WfCollaborationMessage row);
    int markRetrying(WfCollaborationMessage row);
    int advanceWaitingOrder(WfCollaborationMessage row);
}
