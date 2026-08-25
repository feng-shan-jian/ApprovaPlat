package com.ruoyi.flowable.mapper;

import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCollaborationMessage;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;

/** 协作消息正式台账 Mapper。 */
public interface WfCollaborationMessageMapper
{
    long countList(@Param("query") WorkflowOperationsQuery.Collaboration query);
    List<WfCollaborationMessage> selectList(
            @Param("query") WorkflowOperationsQuery.Collaboration query,
            @Param("offset") int offset, @Param("pageSize") int pageSize);
    WfCollaborationMessage selectByIdForUpdate(@Param("messageId") String messageId);
    WfCollaborationMessage selectById(@Param("messageId") String messageId);
    WfCollaborationMessage selectNextDueForUpdate();
    int insertReceived(WfCollaborationMessage row);
    int markProcessed(WfCollaborationMessage row);
    int markFailed(WfCollaborationMessage row);
    int markDeadLetter(WfCollaborationMessage row);
    int markRetrying(WfCollaborationMessage row);
    int advanceWaitingOrder(WfCollaborationMessage row);

    /**
     * 锁定一批超过保留期的已处理协作消息，死信不进入候选。
     * @param cutoffTime LocalDateTime，完成时间截止点
     * @param limit int，单批最大记录数
     * @return List&lt;String&gt;，稳定排序的锁定消息主键
     */
    List<String> selectRetentionIdsForUpdate(@Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("limit") int limit);

    /**
     * 删除当前批次入站消息对应的 INBOUND 审计，必须与父记录删除处于同一事务。
     * @param messageIds List&lt;String&gt;，当前事务已锁定的入站消息主键
     * @return int，实际删除审计数
     */
    int deleteRetentionAuditsByIds(@Param("messageIds") List<String> messageIds);

    /**
     * 删除仍处于 PROCESSED 且超过截止时间的锁定消息。
     * @param messageIds List&lt;String&gt;，当前事务已锁定的消息主键
     * @param cutoffTime LocalDateTime，完成时间截止点
     * @return int，实际删除记录数
     */
    int deleteRetentionByIds(@Param("messageIds") List<String> messageIds,
            @Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 查询最早的已处理协作消息完成时间。
     * @return LocalDateTime，最早 PROCESSED 时间；没有时为空
     */
    LocalDateTime selectOldestRetentionTime();
}
