package com.ruoyi.flowable.domain.vo;

/**
 * 设计器可选择的受控扩展最新版视图。
 *
 * @param extensionId Long，扩展目录主键
 * @param extensionKey String，稳定扩展键
 * @param extensionName String，用户可见名称
 * @param extensionType String，扩展类型
 * @param versionId Long，当前最新版主键
 * @param versionNo Integer，当前最新版号
 * @param implementationKey String，已安装处理器稳定键
 * @param configSchema String，处理器配置 JSON Schema
 * @param checksum String，版本定义校验和
 */
public record WorkflowExtensionOptionView(Long extensionId, String extensionKey,
        String extensionName, String extensionType, Long versionId, Integer versionNo,
        String implementationKey, String configSchema, String checksum)
{
}
