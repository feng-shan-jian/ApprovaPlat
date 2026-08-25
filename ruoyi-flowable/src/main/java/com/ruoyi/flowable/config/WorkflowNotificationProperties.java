package com.ruoyi.flowable.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一工作流通知 worker、重试和催办频控配置。
 */
@Component
@ConfigurationProperties(prefix = "flowable.notification")
public class WorkflowNotificationProperties
{
    private int batchSize = 50;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration maxRetryDelay = Duration.ofMinutes(30);
    private Duration urgeInterval = Duration.ofMinutes(5);

    /** @return int，单轮最多处理的 outbox 数量。 */
    public int getBatchSize() { return batchSize; }

    /**
     * 设置单轮批次。
     * @param batchSize int，1 至 500
     * @return void，无返回值
     */
    public void setBatchSize(int batchSize)
    {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("通知批次必须处于1至500");
        this.batchSize = batchSize;
    }

    /** @return Duration，worker 领取租约。 */
    public Duration getLeaseDuration() { return leaseDuration; }

    /**
     * 设置 worker 租约。
     * @param leaseDuration Duration，10 秒至 10 分钟
     * @return void，无返回值
     */
    public void setLeaseDuration(Duration leaseDuration)
    {
        if (leaseDuration == null || leaseDuration.compareTo(Duration.ofSeconds(10)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(10)) > 0)
        {
            throw new IllegalArgumentException("通知租约必须处于10秒至10分钟");
        }
        this.leaseDuration = leaseDuration;
    }

    /** @return Duration，失败重试最大退避。 */
    public Duration getMaxRetryDelay() { return maxRetryDelay; }

    /**
     * 设置重试最大退避。
     * @param maxRetryDelay Duration，1 秒至 24 小时
     * @return void，无返回值
     */
    public void setMaxRetryDelay(Duration maxRetryDelay)
    {
        if (maxRetryDelay == null || maxRetryDelay.compareTo(Duration.ofSeconds(1)) < 0
                || maxRetryDelay.compareTo(Duration.ofHours(24)) > 0)
        {
            throw new IllegalArgumentException("通知最大退避必须处于1秒至24小时");
        }
        this.maxRetryDelay = maxRetryDelay;
    }

    /** @return Duration，同一发起人对同一流程的最小催办间隔。 */
    public Duration getUrgeInterval() { return urgeInterval; }

    /**
     * 设置人工催办间隔。
     * @param urgeInterval Duration，1 分钟至 24 小时
     * @return void，无返回值
     */
    public void setUrgeInterval(Duration urgeInterval)
    {
        if (urgeInterval == null || urgeInterval.compareTo(Duration.ofMinutes(1)) < 0
                || urgeInterval.compareTo(Duration.ofHours(24)) > 0)
        {
            throw new IllegalArgumentException("人工催办间隔必须处于1分钟至24小时");
        }
        this.urgeInterval = urgeInterval;
    }

}
