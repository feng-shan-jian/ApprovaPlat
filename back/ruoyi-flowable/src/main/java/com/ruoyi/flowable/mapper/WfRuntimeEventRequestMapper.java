package com.ruoyi.flowable.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfRuntimeEventRequest;

/**
 * 运行事件幂等请求和脱敏结果台账 Mapper。
 */
public interface WfRuntimeEventRequestMapper
{
    /** @return List，按时间倒序的审计清单。 */
    List<WfRuntimeEventRequest> selectList();

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
}
