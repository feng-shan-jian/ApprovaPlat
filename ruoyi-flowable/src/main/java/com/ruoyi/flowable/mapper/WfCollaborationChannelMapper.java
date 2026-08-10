package com.ruoyi.flowable.mapper;

import org.apache.ibatis.annotations.Param;
import com.ruoyi.flowable.domain.WfCollaborationChannel;

/** 协作消息顺序通道 Mapper。 */
public interface WfCollaborationChannelMapper
{
    int insertIfAbsent(WfCollaborationChannel channel);
    WfCollaborationChannel selectForUpdate(@Param("channelId") String channelId);
    int advanceOutbound(@Param("channelId") String channelId,
            @Param("expectedRevision") int expectedRevision,
            @Param("sequenceNo") long sequenceNo);
    int advanceInbound(@Param("channelId") String channelId,
            @Param("expectedRevision") int expectedRevision,
            @Param("sequenceNo") long sequenceNo);
}
