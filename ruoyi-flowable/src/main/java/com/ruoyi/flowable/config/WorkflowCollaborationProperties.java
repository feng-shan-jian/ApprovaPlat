package com.ruoyi.flowable.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Participant/MessageFlow 可靠投递后台 worker 的有界资源配置。 */
@Component
@ConfigurationProperties(prefix = "flowable.collaboration")
public class WorkflowCollaborationProperties
{
    private int batchSize = 50;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration maxRetryDelay = Duration.ofMinutes(5);

    /** @return int，每轮最多处理的入站和出站消息数。 */
    public int getBatchSize() { return batchSize; }

    /**
     * 设置单轮有界批次。
     * @param value int，1 至 500
     * @return void，无返回值
     */
    public void setBatchSize(int value)
    {
        if (value < 1 || value > 500) throw new IllegalArgumentException("协作消息批次必须处于 1 至 500");
        batchSize = value;
    }

    /** @return Duration，后台领取租约。 */
    public Duration getLeaseDuration() { return leaseDuration; }

    /**
     * 设置领取租约，必须覆盖正式 HTTP 请求上限。
     * @param value Duration，10 秒至 10 分钟
     * @return void，无返回值
     */
    public void setLeaseDuration(Duration value)
    {
        if (value == null || value.compareTo(Duration.ofSeconds(10)) < 0
                || value.compareTo(Duration.ofMinutes(10)) > 0)
        {
            throw new IllegalArgumentException("协作消息租约必须处于 10 秒至 10 分钟");
        }
        leaseDuration = value;
    }

    /** @return Duration，指数退避最大间隔。 */
    public Duration getMaxRetryDelay() { return maxRetryDelay; }

    /**
     * 设置最大退避时间。
     * @param value Duration，1 秒至 1 小时
     * @return void，无返回值
     */
    public void setMaxRetryDelay(Duration value)
    {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0
                || value.compareTo(Duration.ofHours(1)) > 0)
        {
            throw new IllegalArgumentException("协作消息最大退避必须处于 1 秒至 1 小时");
        }
        maxRetryDelay = value;
    }
}
