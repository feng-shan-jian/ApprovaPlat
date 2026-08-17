package com.ruoyi.flowable.mapper;

import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCollaborationOutbox;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;

/** SendTask 事务 outbox Mapper。 */
public interface WfCollaborationOutboxMapper
{
    int insert(WfCollaborationOutbox row);
    long countList(@Param("query") WorkflowOperationsQuery.Collaboration query);
    List<WfCollaborationOutbox> selectList(
            @Param("query") WorkflowOperationsQuery.Collaboration query,
            @Param("offset") int offset, @Param("pageSize") int pageSize);
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

    /**
     * 锁定一批超过保留期的成功或取消 outbox，待处理与死信不进入候选。
     * @param cutoffTime LocalDateTime，完成时间截止点
     * @param limit int，单批最大记录数
     * @return List&lt;String&gt;，稳定排序的锁定 outbox 主键
     */
    List<String> selectRetentionIdsForUpdate(@Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("limit") int limit);

    /**
     * 删除当前批次出站消息对应的 OUTBOUND 审计，必须与父记录删除处于同一事务。
     * @param messageIds List&lt;String&gt;，当前事务已锁定的出站消息主键
     * @return int，实际删除审计数
     */
    int deleteRetentionAuditsByIds(@Param("messageIds") List<String> messageIds);

    /**
     * 删除仍处于 PROCESSED/CANCELLED 且超过截止时间的锁定 outbox。
     * @param messageIds List&lt;String&gt;，当前事务已锁定的 outbox 主键
     * @param cutoffTime LocalDateTime，完成时间截止点
     * @return int，实际删除记录数
     */
    int deleteRetentionByIds(@Param("messageIds") List<String> messageIds,
            @Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 查询最早的成功或取消 outbox 完成时间。
     * @return LocalDateTime，最早终态时间；没有时为空
     */
    LocalDateTime selectOldestRetentionTime();
}
