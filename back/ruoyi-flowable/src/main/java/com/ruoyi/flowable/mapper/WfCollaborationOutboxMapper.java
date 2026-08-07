package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCollaborationOutbox;

/** SendTask 事务 outbox Mapper。 */
public interface WfCollaborationOutboxMapper
{
    int insert(WfCollaborationOutbox row);
    List<WfCollaborationOutbox> selectList();
    WfCollaborationOutbox selectById(@Param("messageId") String messageId);
    WfCollaborationOutbox selectByIdForUpdate(@Param("messageId") String messageId);
    WfCollaborationOutbox selectNextDueForUpdate();
    int claim(@Param("messageId") String messageId, @Param("expectedRevision") int expectedRevision,
            @Param("leaseOwner") String leaseOwner, @Param("leaseSeconds") int leaseSeconds);
    int markProcessed(@Param("messageId") String messageId, @Param("leaseOwner") String leaseOwner,
            @Param("expectedRevision") int expectedRevision, @Param("httpStatus") Integer httpStatus);
    int markFailed(@Param("messageId") String messageId, @Param("leaseOwner") String leaseOwner,
            @Param("expectedRevision") int expectedRevision, @Param("targetStatus") String targetStatus,
            @Param("delaySeconds") long delaySeconds, @Param("httpStatus") Integer httpStatus,
            @Param("errorCode") String errorCode, @Param("errorSummary") String errorSummary);
    int compensate(@Param("messageId") String messageId, @Param("expectedRevision") int expectedRevision,
            @Param("actorUserId") String actorUserId);
    int cancel(@Param("messageId") String messageId, @Param("expectedRevision") int expectedRevision);
}
