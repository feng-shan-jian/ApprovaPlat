package com.ruoyi.flowable.mapper;

import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfRuntimeEventRequest;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;

/**
 * 运行事件幂等请求和脱敏结果台账 Mapper。
 */
public interface WfRuntimeEventRequestMapper
{
    /** @param query RuntimeEvent，运维筛选条件；@return long，符合条件的请求总数。 */
    long countList(@Param("query") WorkflowOperationsQuery.RuntimeEvent query);

    /**
     * 分页查询脱敏运行事件台账。
     * @param query RuntimeEvent，运维筛选条件
     * @param offset int，数据库起始偏移
     * @param pageSize int，本页最大记录数
     * @return List&lt;WfRuntimeEventRequest&gt;，按时间和请求主键倒序的当前页
     */
    List<WfRuntimeEventRequest> selectList(
            @Param("query") WorkflowOperationsQuery.RuntimeEvent query,
            @Param("offset") int offset, @Param("pageSize") int pageSize);

    /** @param requestId String，幂等请求 UUID；@return WfRuntimeEventRequest，锁定行或 null。 */
    WfRuntimeEventRequest selectByIdForUpdate(@Param("requestId") String requestId);

    /** @param requestId String，幂等请求 UUID；@return WfRuntimeEventRequest，当前行或 null。 */
    WfRuntimeEventRequest selectById(@Param("requestId") String requestId);

    /** @param request WfRuntimeEventRequest，RECEIVED 请求；@return int，影响行数。 */
    int insertReceived(WfRuntimeEventRequest request);

    /** @param request WfRuntimeEventRequest，FAILED 请求；@return int，影响行数。 */
    int insertFailed(WfRuntimeEventRequest request);

    /** @param request WfRuntimeEventRequest，唯一匹配和成功结果；@return int，影响行数。 */
    int markProcessed(WfRuntimeEventRequest request);

    /**
     * 锁定一批已完成且超过保留期的运行事件请求。
     * @param cutoffTime LocalDateTime，完成时间截止点
     * @param limit int，单批最大记录数
     * @return List&lt;String&gt;，按完成时间和请求主键稳定排序的锁定主键
     */
    List<String> selectRetentionIdsForUpdate(@Param("cutoffTime") LocalDateTime cutoffTime,
            @Param("limit") int limit);

    /**
     * 按锁定主键删除仍满足终态和截止时间的运行事件请求。
     * @param requestIds List&lt;String&gt;，当前事务已锁定的请求主键
     * @param cutoffTime LocalDateTime，完成时间截止点
     * @return int，实际删除记录数
     */
    int deleteRetentionByIds(@Param("requestIds") List<String> requestIds,
            @Param("cutoffTime") LocalDateTime cutoffTime);

    /**
     * 查询当前最早的已完成运行事件时间。
     * @return LocalDateTime，最早终态时间；没有终态记录时为空
     */
    LocalDateTime selectOldestRetentionTime();
}
