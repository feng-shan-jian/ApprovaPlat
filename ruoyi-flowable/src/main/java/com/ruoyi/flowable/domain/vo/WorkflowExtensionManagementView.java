package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/**
 * 扩展注册表管理页目录和可选最新版视图。
 *
 * @param extensionId Long，扩展目录主键
 * @param extensionKey String，扩展稳定键
 * @param extensionName String，用户可见名称
 * @param extensionType String，扩展类型
 * @param status String，ENABLED 或 DISABLED
 * @param description String，可选业务说明
 * @param versionId Long，当前最新版主键；尚未发布时为空
 * @param versionNo Integer，当前最新版号；尚未发布时为空
 * @param implementationKey String，最新版已安装处理器键；尚未发布时为空
 * @param checksum String，最新版定义摘要；尚未发布时为空
 * @param updateTime Date，目录最近状态变更时间；从未变更时取创建时间
 */
public record WorkflowExtensionManagementView(Long extensionId, String extensionKey,
        String extensionName, String extensionType, String status, String description,
        Long versionId, Integer versionNo, String implementationKey, String checksum,
        Date updateTime)
{
}
