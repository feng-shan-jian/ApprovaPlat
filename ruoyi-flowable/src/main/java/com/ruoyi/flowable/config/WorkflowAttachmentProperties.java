package com.ruoyi.flowable.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流私有附件的服务端资源限制配置。
 */
@Component
@ConfigurationProperties(prefix = "flowable.attachment")
public class WorkflowAttachmentProperties
{
    /** 单个附件默认最大 50 MiB。 */
    private long maxSize = 50L * 1024L * 1024L;

    /** 单个用户默认最多保留 100 个尚未绑定的临时附件。 */
    private int maxTemporaryCount = 100;

    /** 单个用户默认最多占用 512 MiB 临时附件磁盘空间。 */
    private long maxTemporaryBytes = 512L * 1024L * 1024L;

    /** 附件落盘后文件系统默认至少保留 1 GiB 可用空间。 */
    private long minFreeBytes = 1024L * 1024L * 1024L;

    /** 临时附件默认保留 24 小时。 */
    private Duration temporaryTtl = Duration.ofHours(24);

    /** 每轮过期清理默认最多处理 100 条。 */
    private int cleanupBatchSize = 100;

    /** 清理领取租约默认保留 5 分钟，超时后允许其他节点重领。 */
    private Duration cleanupLeaseDuration = Duration.ofMinutes(5);

    /** 首次物理清理失败默认延迟 1 分钟重试。 */
    private Duration cleanupRetryInitialDelay = Duration.ofMinutes(1);

    /** 连续物理清理失败退避时间默认最多 6 小时。 */
    private Duration cleanupRetryMaxDelay = Duration.ofHours(6);

    /**
     * 获取单个附件允许的最大实际字节数。
     *
     * @return long，正数文件大小上限
     */
    public long getMaxSize()
    {
        return maxSize;
    }

    /**
     * 设置单个附件允许的最大实际字节数。
     *
     * @param maxSize long，必须处于 1 字节至 2 GiB 范围
     * @return void，无返回值
     */
    public void setMaxSize(long maxSize)
    {
        if (maxSize <= 0L || maxSize > 2L * 1024L * 1024L * 1024L)
        {
            throw new IllegalArgumentException("工作流附件大小上限必须处于1字节至2GiB范围");
        }
        this.maxSize = maxSize;
    }

    /**
     * 获取单个用户允许同时占用的临时附件数量。
     *
     * @return int，正数临时附件数量上限
     */
    public int getMaxTemporaryCount()
    {
        return maxTemporaryCount;
    }

    /**
     * 设置单个用户允许同时占用的临时附件数量。
     *
     * @param maxTemporaryCount int，必须处于 1 至 10000 范围
     * @return void，无返回值
     */
    public void setMaxTemporaryCount(int maxTemporaryCount)
    {
        if (maxTemporaryCount < 1 || maxTemporaryCount > 10_000)
        {
            throw new IllegalArgumentException("工作流临时附件数量上限必须处于1至10000范围");
        }
        this.maxTemporaryCount = maxTemporaryCount;
    }

    /**
     * 获取单个用户允许占用的临时附件总字节数。
     *
     * @return long，正数临时附件累计字节上限
     */
    public long getMaxTemporaryBytes()
    {
        return maxTemporaryBytes;
    }

    /**
     * 设置单个用户允许占用的临时附件总字节数。
     *
     * @param maxTemporaryBytes long，必须处于 1 字节至 1 TiB 范围
     * @return void，无返回值
     */
    public void setMaxTemporaryBytes(long maxTemporaryBytes)
    {
        if (maxTemporaryBytes <= 0L
                || maxTemporaryBytes > 1024L * 1024L * 1024L * 1024L)
        {
            throw new IllegalArgumentException("工作流临时附件总大小上限必须处于1字节至1TiB范围");
        }
        this.maxTemporaryBytes = maxTemporaryBytes;
    }

    /**
     * 获取附件文件系统必须保留的最小可用字节数。
     *
     * @return long，允许为零的磁盘低水位
     */
    public long getMinFreeBytes()
    {
        return minFreeBytes;
    }

    /**
     * 设置附件文件系统必须保留的最小可用字节数。
     *
     * @param minFreeBytes long，必须处于 0 至 16 TiB 范围
     * @return void，无返回值
     */
    public void setMinFreeBytes(long minFreeBytes)
    {
        if (minFreeBytes < 0L
                || minFreeBytes > 16L * 1024L * 1024L * 1024L * 1024L)
        {
            throw new IllegalArgumentException("工作流附件磁盘低水位必须处于0至16TiB范围");
        }
        this.minFreeBytes = minFreeBytes;
    }

    /**
     * 获取未绑定附件的有效时长。
     *
     * @return Duration，正数临时有效时长
     */
    public Duration getTemporaryTtl()
    {
        return temporaryTtl;
    }

    /**
     * 设置未绑定附件的有效时长。
     *
     * @param temporaryTtl Duration，必须大于零且不超过七天
     * @return void，无返回值
     */
    public void setTemporaryTtl(Duration temporaryTtl)
    {
        if (temporaryTtl == null || temporaryTtl.isZero() || temporaryTtl.isNegative()
                || temporaryTtl.compareTo(Duration.ofDays(7)) > 0)
        {
            throw new IllegalArgumentException("工作流临时附件有效期必须大于0且不能超过7天");
        }
        this.temporaryTtl = temporaryTtl;
    }

    /**
     * 获取单轮清理的最大记录数。
     *
     * @return int，单批清理上限
     */
    public int getCleanupBatchSize()
    {
        return cleanupBatchSize;
    }

    /**
     * 设置单轮清理的最大记录数。
     *
     * @param cleanupBatchSize int，必须处于 1 至 1000 范围
     * @return void，无返回值
     */
    public void setCleanupBatchSize(int cleanupBatchSize)
    {
        if (cleanupBatchSize < 1 || cleanupBatchSize > 1000)
        {
            throw new IllegalArgumentException("工作流附件清理批次必须处于1至1000范围");
        }
        this.cleanupBatchSize = cleanupBatchSize;
    }

    /**
     * 获取附件清理领取租约时长。
     *
     * @return Duration，事务外对象删除允许占用领取权的最长时间
     */
    public Duration getCleanupLeaseDuration()
    {
        return cleanupLeaseDuration;
    }

    /**
     * 设置附件清理领取租约时长。
     *
     * @param cleanupLeaseDuration Duration，必须大于零且不超过一天
     * @return void，无返回值
     */
    public void setCleanupLeaseDuration(Duration cleanupLeaseDuration)
    {
        if (cleanupLeaseDuration == null || cleanupLeaseDuration.isZero()
                || cleanupLeaseDuration.isNegative()
                || cleanupLeaseDuration.compareTo(Duration.ofDays(1)) > 0)
        {
            throw new IllegalArgumentException("工作流附件清理租约必须大于0且不能超过1天");
        }
        this.cleanupLeaseDuration = cleanupLeaseDuration;
    }

    /**
     * 获取首次附件物理清理失败后的重试延迟。
     *
     * @return Duration，正数初始退避时间
     */
    public Duration getCleanupRetryInitialDelay()
    {
        return cleanupRetryInitialDelay;
    }

    /**
     * 设置首次附件物理清理失败后的重试延迟。
     *
     * @param cleanupRetryInitialDelay Duration，必须大于零且不超过一天
     * @return void，无返回值
     */
    public void setCleanupRetryInitialDelay(Duration cleanupRetryInitialDelay)
    {
        if (cleanupRetryInitialDelay == null || cleanupRetryInitialDelay.isZero()
                || cleanupRetryInitialDelay.isNegative()
                || cleanupRetryInitialDelay.compareTo(Duration.ofDays(1)) > 0)
        {
            throw new IllegalArgumentException("工作流附件清理初始退避必须大于0且不能超过1天");
        }
        this.cleanupRetryInitialDelay = cleanupRetryInitialDelay;
    }

    /**
     * 获取连续附件物理清理失败的最大退避时间。
     *
     * @return Duration，不小于初始退避的上限
     */
    public Duration getCleanupRetryMaxDelay()
    {
        return cleanupRetryMaxDelay;
    }

    /**
     * 设置连续附件物理清理失败的最大退避时间。
     *
     * @param cleanupRetryMaxDelay Duration，必须大于零且不超过七天
     * @return void，无返回值
     */
    public void setCleanupRetryMaxDelay(Duration cleanupRetryMaxDelay)
    {
        if (cleanupRetryMaxDelay == null || cleanupRetryMaxDelay.isZero()
                || cleanupRetryMaxDelay.isNegative()
                || cleanupRetryMaxDelay.compareTo(Duration.ofDays(7)) > 0)
        {
            throw new IllegalArgumentException("工作流附件清理最大退避必须大于0且不能超过7天");
        }
        this.cleanupRetryMaxDelay = cleanupRetryMaxDelay;
    }

    /**
     * 校验附件清理退避的跨字段关系，避免最大值小于首次延迟导致配置语义倒置。
     *
     * @return void，配置关系非法时抛出 IllegalArgumentException
     */
    public void validateCleanupRetryBackoff()
    {
        if (cleanupRetryMaxDelay.compareTo(cleanupRetryInitialDelay) < 0)
        {
            throw new IllegalArgumentException("工作流附件清理最大退避不能小于初始退避");
        }
    }
}
