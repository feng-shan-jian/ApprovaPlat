package com.ruoyi.flowable.service.process;

import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.WfCollaborationChannel;
import com.ruoyi.flowable.extension.WorkflowExtensionChecksum;
import com.ruoyi.flowable.mapper.WfCollaborationChannelMapper;

/**
 * Participant/MessageFlow 关联通道服务，在正式行锁内分配出站序号并推进入站游标。
 */
@Service
public class WorkflowCollaborationChannelService
{
    private final WfCollaborationChannelMapper mapper;

    /**
     * 创建协作通道服务。
     * @param mapper WfCollaborationChannelMapper，通道游标数据访问层
     * @return void，构造后由 Spring 管理
     */
    public WorkflowCollaborationChannelService(WfCollaborationChannelMapper mapper)
    {
        this.mapper = mapper;
    }

    /**
     * 锁定或创建业务键通道并原子分配下一个出站序号。
     * @param targetProcessDefinitionKey String，接收流程定义 key
     * @param correlationKey String，接收实例业务键
     * @return Allocation，稳定通道键和连续序号
     */
    public Allocation allocateOutbound(String targetProcessDefinitionKey, String correlationKey)
    {
        WfCollaborationChannel channel = lockOrCreate(targetProcessDefinitionKey,
                "BUSINESS_KEY", correlationKey);
        long next = Math.addExact(channel.getOutboundSequence(), 1L);
        if (mapper.advanceOutbound(channel.getChannelId(), channel.getRevisionNo(), next) != 1)
        {
            throw new ServiceException("协作消息出站序号分配冲突", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_CHANNEL_OUTBOUND_CONFLICT");
        }
        return new Allocation(channel.getChannelId(), next);
    }

    /**
     * 锁定入站关联通道并返回当前期望序号，调用方只有成功消费后才能推进。
     * @param targetProcessDefinitionKey String，接收流程定义 key
     * @param correlationType String，BUSINESS_KEY 或 PROCESS_INSTANCE
     * @param correlationValue String，业务键或实例主键
     * @return WfCollaborationChannel，持有数据库行锁的当前游标
     */
    public WfCollaborationChannel lockInbound(String targetProcessDefinitionKey,
            String correlationType, String correlationValue)
    {
        return lockOrCreate(targetProcessDefinitionKey, correlationType, correlationValue);
    }

    /**
     * 在消息已经被 Flowable 唯一消费后推进入站游标。
     * @param channel WfCollaborationChannel，当前事务内锁定的通道
     * @param sequenceNo long，本次已成功消费的连续序号
     * @return void，游标漂移时回滚消息消费事务
     */
    public void advanceInbound(WfCollaborationChannel channel, long sequenceNo)
    {
        if (sequenceNo != Math.addExact(channel.getInboundSequence(), 1L)
                || mapper.advanceInbound(channel.getChannelId(), channel.getRevisionNo(), sequenceNo) != 1)
        {
            throw new ServiceException("协作消息入站顺序游标推进冲突", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_CHANNEL_INBOUND_CONFLICT");
        }
    }

    /**
     * 创建后锁定稳定通道，并复核摘要与业务字段一致以阻止哈希键误用。
     * @param targetProcessDefinitionKey String，接收流程定义 key
     * @param correlationType String，关联类型
     * @param correlationValue String，关联值
     * @return WfCollaborationChannel，当前事务内锁定的通道
     */
    private WfCollaborationChannel lockOrCreate(String targetProcessDefinitionKey,
            String correlationType, String correlationValue)
    {
        String channelId = channelId(targetProcessDefinitionKey, correlationType, correlationValue);
        WfCollaborationChannel candidate = new WfCollaborationChannel();
        candidate.setChannelId(channelId);
        candidate.setTargetProcessDefinitionKey(targetProcessDefinitionKey);
        candidate.setCorrelationType(correlationType);
        candidate.setCorrelationValue(correlationValue);
        mapper.insertIfAbsent(candidate);
        WfCollaborationChannel channel = mapper.selectForUpdate(channelId);
        if (channel == null || !targetProcessDefinitionKey.equals(channel.getTargetProcessDefinitionKey())
                || !correlationType.equals(channel.getCorrelationType())
                || !correlationValue.equals(channel.getCorrelationValue()))
        {
            throw new ServiceException("协作消息关联通道不一致", HttpStatus.CONFLICT)
                    .setSubCode("COLLAB_CHANNEL_INTEGRITY_FAILED");
        }
        return channel;
    }

    /**
     * 计算不暴露业务关联值的稳定通道主键。
     * @param targetProcessDefinitionKey String，接收流程定义 key
     * @param correlationType String，关联类型
     * @param correlationValue String，关联值
     * @return String，64 位小写 SHA-256
     */
    public static String channelId(String targetProcessDefinitionKey,
            String correlationType, String correlationValue)
    {
        if (targetProcessDefinitionKey == null || targetProcessDefinitionKey.isBlank()
                || correlationValue == null || correlationValue.isBlank()
                || !("BUSINESS_KEY".equals(correlationType)
                    || "PROCESS_INSTANCE".equals(correlationType)))
        {
            throw new ServiceException("协作消息关联通道参数不完整", HttpStatus.BAD_REQUEST);
        }
        return WorkflowExtensionChecksum.sha256(targetProcessDefinitionKey.trim(),
                correlationType, correlationValue.trim());
    }

    /**
     * 出站序号分配结果。
     * @param channelId String，稳定通道主键
     * @param sequenceNo long，新分配的连续序号
     */
    public record Allocation(String channelId, long sequenceNo)
    {
    }
}
